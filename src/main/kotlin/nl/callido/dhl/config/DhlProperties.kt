package nl.callido.dhl.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("dhl")
data class DhlProperties(
    val backend: Backend,
    val security: Security,
    val lockerSim: LockerSim,
    val lockerClient: LockerClient,
    val simPassthrough: SimPassthrough,
    val reaper: Reaper,
    val cors: Cors,
) {
    data class Backend(val enabled: Boolean)
    /**
     * The jwks overrides exist for the dockerized local stack: the backend
     * container fetches keys via the compose network (http://keycloak:8081)
     * while tokens carry the public issuer (http://localhost:8081). Blank =
     * derive everything from the issuer.
     */
    data class Security(
        val courierIssuer: String,
        val lockerIssuer: String,
        val courierJwks: String? = null,
        val lockerJwks: String? = null,
    )
    data class LockerSim(val baseUrl: String, val serve: Boolean)
    data class LockerClient(val tokenUri: String, val clientId: String, val clientSecret: String)
    data class SimPassthrough(val enabled: Boolean)
    data class Reaper(val enabled: Boolean, val timeout: Duration)
    data class Cors(val allowedOrigins: List<String>)
}
