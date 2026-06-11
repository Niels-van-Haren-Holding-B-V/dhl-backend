package nl.callido.dhl.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.callido.dhl.config.DhlProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.web.reactive.function.client.WebClient

@Configuration
class LockerClientConfig {

    // spring-boot-webclient ships the classes but (unlike Boot 3) no
    // WebClient.Builder bean in this setup — provide it ourselves.
    @Bean
    fun webClientBuilder(): WebClient.Builder = WebClient.builder()

    /**
     * Client-credentials flow against the `locker` realm. The registration is
     * built by hand (token endpoint from config, no issuer discovery) so the
     * app boots even when Keycloak is still starting. The manager caches the
     * token and refreshes it before expiry; the blocking token request runs
     * on Dispatchers.IO behind the suspend interface.
     *
     * The token value must never end up in a log, response or error message.
     */
    @Bean
    fun lockerTokenProvider(props: DhlProperties): LockerTokenProvider {
        val registration = ClientRegistration.withRegistrationId("locker")
            .clientId(props.lockerClient.clientId)
            .clientSecret(props.lockerClient.clientSecret)
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
            .tokenUri(props.lockerClient.tokenUri)
            .build()
        val registrations = InMemoryClientRegistrationRepository(registration)
        val manager = AuthorizedClientServiceOAuth2AuthorizedClientManager(
            registrations,
            InMemoryOAuth2AuthorizedClientService(registrations),
        )
        return LockerTokenProvider {
            withContext(Dispatchers.IO) {
                val request = OAuth2AuthorizeRequest.withClientRegistrationId("locker")
                    .principal(props.lockerClient.clientId)
                    .build()
                try {
                    manager.authorize(request)?.accessToken?.tokenValue
                        ?: throw LockerUnavailableException()
                } catch (e: LockerUnavailableException) {
                    throw e
                } catch (e: Exception) {
                    // misconfigured/unreachable token endpoint (e.g. missing
                    // LOCKER_CLIENT_SECRET) is an availability problem, never
                    // a raw 500 — and never leak the cause to the caller
                    throw LockerUnavailableException(e)
                }
            }
        }
    }

    @Bean
    fun lockerSimBaseUrl(props: DhlProperties): LockerSimBaseUrl = LockerSimBaseUrl { props.lockerSim.baseUrl }
}
