package io.onfhir.client.intrcp

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.oauth2.sdk.auth.{ClientSecretBasic, ClientSecretJWT, ClientSecretPost, Secret}
import com.nimbusds.oauth2.sdk.http.{HTTPResponse => NimbusHttpResponse}
import com.nimbusds.oauth2.sdk.id.ClientID
import com.nimbusds.oauth2.sdk.{AccessTokenResponse, ClientCredentialsGrant, Scope, TokenErrorResponse, TokenRequest, TokenResponse}
import com.typesafe.config.Config
import io.onfhir.api.client.FhirClientException
import io.onfhir.api.model.{FhirContentType, FhirMediaType, HttpHeader, HttpHeaders, HttpMethod}
import io.onfhir.client.{ClientHttpTransport, IHttpRequestInterceptor}
import io.onfhir.client.model.{ClientHttpEntity, ClientHttpRequest, ClientHttpResponse}
import org.slf4j.{Logger, LoggerFactory}

import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._

/** Retrieves and caches an OAuth2 client-credentials bearer token. */
class BearerTokenInterceptorFromTokenEndpoint(
  clientId: String,
  clientSecret: String,
  requiredScopes: Seq[String],
  authzServerTokenEndpoint: String,
  clientAuthenticationMethod: String = "client_secret_basic",
  private[client] val suppliedTransport: Option[ClientHttpTransport] = None)
  extends BearerTokenInterceptor with Serializable {

  private val logger: Logger = LoggerFactory.getLogger(classOf[BearerTokenInterceptorFromTokenEndpoint])
  private val tokenEndpointUri = new URI(authzServerTokenEndpoint)

  private val authentication = clientAuthenticationMethod match {
    case "client_secret_basic" => new ClientSecretBasic(new ClientID(clientId), new Secret(clientSecret))
    case "client_secret_post" => new ClientSecretPost(new ClientID(clientId), new Secret(clientSecret))
    case "client_secret_jwt" => new ClientSecretJWT(new ClientID(clientId), tokenEndpointUri, JWSAlgorithm.HS512, new Secret(clientSecret))
    case other => throw FhirClientException(s"Client authentication method $other not supported by this interceptor!")
  }

  private val grant = new ClientCredentialsGrant
  @volatile private var token: Option[(String, Instant)] = None
  private var refreshInProgress: Option[Future[(String, Instant)]] = None

  private[client] def withTransport(transport: ClientHttpTransport): BearerTokenInterceptorFromTokenEndpoint =
    new BearerTokenInterceptorFromTokenEndpoint(
      clientId,
      clientSecret,
      requiredScopes,
      authzServerTokenEndpoint,
      clientAuthenticationMethod,
      Some(transport)
    )

  override def processRequest(httpRequest: ClientHttpRequest)(implicit ex: ExecutionContext): Future[ClientHttpRequest] =
    getToken().map(value => addHeader(httpRequest, value))

  /** Returns a valid token, sharing a single refresh across concurrent callers. */
  def getToken()(implicit executionContext: ExecutionContext): Future[String] = synchronized {
    token.filter { case (_, expiresAt) => expiresAt.isAfter(Instant.now()) } match {
      case Some((value, _)) => Future.successful(value)
      case None =>
        refreshInProgress.getOrElse {
          val refresh = retrieveToken().andThen { case result =>
            synchronized {
              result.foreach(value => token = Some(value))
              refreshInProgress = None
            }
          }
          refreshInProgress = Some(refresh)
          refresh
        }.map(_._1)
    }
  }

  private def retrieveToken()(implicit executionContext: ExecutionContext): Future[(String, Instant)] = {
    val transport = suppliedTransport.getOrElse(
      throw FhirClientException("OAuth token interceptor must be attached to an OnFhirNetworkClient before use")
    )
    logger.debug(s"Retrieving access token for client '$clientId' ...")
    val tokenRequest = new TokenRequest(tokenEndpointUri, authentication, grant, new Scope(requiredScopes: _*))
    val nimbusRequest = tokenRequest.toHTTPRequest
    val rawHeaders = nimbusRequest.getHeaderMap
      .asInstanceOf[java.util.Map[String, java.util.List[String]]]
      .asScala
      .toVector
      .flatMap { case (name, values) => values.asScala.map(value => HttpHeader(name, value)) }
    val headers = Option(nimbusRequest.getAuthorization) match {
      case Some(value) if !rawHeaders.exists(_.name.equalsIgnoreCase("Authorization")) =>
        rawHeaders :+ HttpHeader("Authorization", value)
      case _ => rawHeaders
    }
    val contentType = Option(nimbusRequest.getEntityContentType)
      .map(value => FhirContentType(FhirMediaType.parse(value.toString)))
      .getOrElse(FhirContentType(FhirMediaType.application("x-www-form-urlencoded")))
    val request = ClientHttpRequest(
      method = HttpMethod(nimbusRequest.getMethod.toString),
      uri = nimbusRequest.getURI,
      headers = HttpHeaders(headers),
      entity = Option(nimbusRequest.getQuery).map(value =>
        ClientHttpEntity(contentType, value.getBytes(StandardCharsets.UTF_8).toVector)
      )
    )

    transport.execute(request).map(parseTokenResponse)
  }

  private def parseTokenResponse(response: ClientHttpResponse): (String, Instant) = {
    val nimbusResponse = new NimbusHttpResponse(response.status.code)
    response.headers.entries.groupBy(_.name).foreach { case (name, headers) =>
      nimbusResponse.setHeader(name, headers.map(_.value): _*)
    }
    nimbusResponse.setContent(new String(response.body.toArray, StandardCharsets.UTF_8))
    TokenResponse.parse(nimbusResponse) match {
      case accessTokenResponse: AccessTokenResponse =>
        val bearerToken = accessTokenResponse.getTokens.getBearerAccessToken
        val expiresAt = Instant.now().plusSeconds(math.max(0L, bearerToken.getLifetime - 60L))
        bearerToken.getValue -> expiresAt
      case errorResponse: TokenErrorResponse =>
        val error = errorResponse.getErrorObject
        throw FhirClientException(s"Error obtaining access token: ${error.getCode} - ${error.getDescription}")
    }
  }
}

object BearerTokenInterceptorFromTokenEndpoint {
  def getFromConfig(config: Config, requiredScopes: Seq[String]): BearerTokenInterceptorFromTokenEndpoint = {
    val authzConfig = config.getConfig("onfhir.client.authz")
    new BearerTokenInterceptorFromTokenEndpoint(
      authzConfig.getString("client_id"),
      authzConfig.getString("client_secret"),
      requiredScopes,
      authzConfig.getString("token_endpoint"),
      authzConfig.getString("token_endpoint_auth_method")
    )
  }

  def apply(
    clientId: String,
    clientSecret: String,
    requiredScopes: Seq[String],
    authzServerTokenEndpoint: String,
    clientAuthenticationMethod: String): BearerTokenInterceptorFromTokenEndpoint =
    new BearerTokenInterceptorFromTokenEndpoint(
      clientId,
      clientSecret,
      requiredScopes,
      authzServerTokenEndpoint,
      clientAuthenticationMethod
    )

  def apply(config: Config): BearerTokenInterceptorFromTokenEndpoint =
    new BearerTokenInterceptorFromTokenEndpoint(
      config.getString("client_id"),
      config.getString("client_secret"),
      config.getStringList("scopes").asScala.toSeq,
      config.getString("token_endpoint"),
      config.getString("token_endpoint_auth_method")
    )
}
