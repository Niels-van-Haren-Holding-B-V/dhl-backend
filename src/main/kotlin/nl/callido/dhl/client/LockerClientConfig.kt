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

    // Boot 4 (unlike Boot 3) ships no WebClient.Builder bean here — provide it ourselves.
    @Bean
    fun webClientBuilder(): WebClient.Builder = WebClient.builder()

    // The token value must never end up in a log, response or error message.
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
                    // Never leak the cause to the caller; a broken token endpoint is an availability problem.
                    throw LockerUnavailableException(e)
                }
            }
        }
    }

    @Bean
    fun lockerSimBaseUrl(props: DhlProperties): LockerSimBaseUrl = LockerSimBaseUrl { props.lockerSim.baseUrl }
}
