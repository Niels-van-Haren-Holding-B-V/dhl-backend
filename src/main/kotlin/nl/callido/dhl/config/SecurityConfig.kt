package nl.callido.dhl.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoders
import org.springframework.security.oauth2.jwt.SupplierReactiveJwtDecoder
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers.pathMatchers
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.reactive.CorsConfigurationSource
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource

/**
 * Two independent resource-server chains:
 *  - "/api" routes        → realm `courier` (the courier app / machine page)
 *  - "/locker-api" routes → realm `locker`  (only the backend's own
 *                           client-credentials identity ever holds such a token)
 *
 * Decoders are supplier-based so the app starts without Keycloak being up;
 * OIDC metadata is fetched lazily on the first request per chain.
 */
@Configuration
@EnableWebFluxSecurity
class SecurityConfig(
    private val props: DhlProperties,
    // docs endpoints exist only in dev (OPENAPI_ENABLED); their anonymous
    // carve-out must come and go with them — no leftover hole by default
    @param:Value("\${springdoc.api-docs.enabled:false}") private val openApiEnabled: Boolean,
) {

    @Bean
    fun courierJwtDecoder(): ReactiveJwtDecoder = decoder(props.security.courierIssuer, props.security.courierJwks)

    @Bean
    fun lockerJwtDecoder(): ReactiveJwtDecoder = decoder(props.security.lockerIssuer, props.security.lockerJwks)

    private fun decoder(issuer: String, jwksOverride: String?): ReactiveJwtDecoder = SupplierReactiveJwtDecoder {
        if (jwksOverride.isNullOrBlank()) {
            ReactiveJwtDecoders.fromIssuerLocation(issuer)
        } else {
            // Dockerized local stack: keys fetched in-network, issuer still validated.
            NimbusReactiveJwtDecoder.withJwkSetUri(jwksOverride).build()
                .apply { setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer)) }
        }
    }

    @Bean
    @Order(0)
    fun publicChain(http: ServerHttpSecurity): SecurityWebFilterChain {
        // health stays anonymous: kubelet probes and the CI smoke check
        val open = mutableListOf("/actuator/health", "/actuator/health/**")
        if (openApiEnabled) {
            // dev-only: npm run generate fetches the spec without a token
            open += listOf("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/webjars/**")
        }
        return http
            .securityMatcher(pathMatchers(*open.toTypedArray()))
            .authorizeExchange { it.anyExchange().permitAll() }
            .csrf { it.disable() }
            .build()
    }

    @Bean
    @Order(1)
    fun courierChain(http: ServerHttpSecurity, courierJwtDecoder: ReactiveJwtDecoder): SecurityWebFilterChain = http
        .securityMatcher(pathMatchers("/api/**"))
        .authorizeExchange {
            // CORS preflights carry no credentials by spec
            it.pathMatchers(HttpMethod.OPTIONS).permitAll()
            it.anyExchange().authenticated()
        }
        .oauth2ResourceServer { rs -> rs.jwt { it.jwtDecoder(courierJwtDecoder) } }
        .cors { }
        .csrf { it.disable() }
        .build()

    @Bean
    @Order(2)
    fun lockerChain(http: ServerHttpSecurity, lockerJwtDecoder: ReactiveJwtDecoder): SecurityWebFilterChain = http
        .securityMatcher(pathMatchers("/locker-api/**"))
        .authorizeExchange { it.anyExchange().authenticated() }
        .oauth2ResourceServer { rs -> rs.jwt { it.jwtDecoder(lockerJwtDecoder) } }
        .csrf { it.disable() }
        .build()

    @Bean
    @Order(3)
    fun denyAllChain(http: ServerHttpSecurity): SecurityWebFilterChain = http
        .authorizeExchange { it.anyExchange().denyAll() }
        .csrf { it.disable() }
        .build()

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val cfg = CorsConfiguration().apply {
            allowedOrigins = props.cors.allowedOrigins
            allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS")
            allowedHeaders = listOf("*")
        }
        return UrlBasedCorsConfigurationSource().apply { registerCorsConfiguration("/api/**", cfg) }
    }
}
