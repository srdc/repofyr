package io.onfhir.client.util

import io.onfhir.client.OnFhirNetworkClient
import io.onfhir.client.model.{BasicAuthenticationSettings, BearerTokenAuthorizationSettings, FixedTokenAuthenticationSettings, IFhirRepositorySecuritySettings}

import scala.concurrent.ExecutionContext

object FhirClientUtil {
  /**
   * Creates an `OnFhirNetworkClient` instance configured with optional security settings.
   * This client is used to interact with an FHIR repository, supporting various authentication
   * mechanisms such as bearer token, basic authentication, or fixed token authentication.
   *
   * @param fhirRepoUrl      The base URL of the FHIR repository that the client will communicate with.
   * @param securitySettings An optional parameter specifying the security settings for authentication.
   *                         These settings determine the type of authentication applied, if any.
   *                           - `BearerTokenAuthorizationSettings` for OAuth2/OpenID Connect bearer tokens.
   *                           - `BasicAuthenticationSettings` for basic username/password authentication.
   *                           - `FixedTokenAuthenticationSettings` for fixed token-based authentication.
   * @param executionContext Execution context used for asynchronous client work.
   * @return A configured `OnFhirNetworkClient` ready for use.
   */
  def createOnFhirClient(fhirRepoUrl: String, securitySettings: Option[IFhirRepositorySecuritySettings] = None)
                        (implicit executionContext: ExecutionContext): OnFhirNetworkClient = {
    // Create a basic client with the provided FHIR repository URL.
    val client = OnFhirNetworkClient.apply(fhirRepoUrl)

    // Apply optional security settings for authentication, based on the type provided.
    securitySettings
      .map {
        // Handle bearer token-based authentication (OAuth2/OpenID Connect).
        case BearerTokenAuthorizationSettings(clientId, clientSecret, requiredScopes, authzServerTokenEndpoint, clientAuthenticationMethod) =>
          client.withOpenIdBearerTokenAuthentication(clientId, clientSecret, requiredScopes, authzServerTokenEndpoint, clientAuthenticationMethod)

        // Handle basic authentication with a username and password.
        case BasicAuthenticationSettings(username, password) =>
          client.withBasicAuthentication(username, password)

        // Handle fixed token-based authentication.
        case FixedTokenAuthenticationSettings(token) =>
          client.withFixedBasicTokenAuthentication(token)
      }
      // If no security settings are provided, return the basic client without authentication.
      .getOrElse(client)
  }
}
