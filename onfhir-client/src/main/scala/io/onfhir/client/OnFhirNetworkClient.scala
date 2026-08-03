package io.onfhir.client

import com.typesafe.config.Config
import io.onfhir.api.client._
import io.onfhir.api.model.{FHIRRequest, FHIRResponse, OrderedQuery}
import io.onfhir.client.intrcp.{BasicAuthenticationInterceptor, BearerTokenInterceptorFromTokenEndpoint, FixedBasicTokenInterceptor, FixedBearerTokenInterceptor}
import io.onfhir.client.model.{ClientHttpRequest, ClientHttpResponse, ClientHttpSettings}
import io.onfhir.client.parsers.{FHIRRequestMarshaller, FHIRResponseUnmarshaller}
import org.slf4j.{Logger, LoggerFactory}

import java.net.URI
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

final class OnFhirNetworkClient private (
  val serverBaseUrl: String,
  val interceptors: Seq[IHttpRequestInterceptor],
  val httpSettings: ClientHttpSettings,
  private val transport: ClientHttpTransport)(implicit val executionContext: ExecutionContext)
  extends BaseFhirClient {

  def this(serverBaseUrl: String)(implicit executionContext: ExecutionContext) =
    this(serverBaseUrl, Nil, ClientHttpSettings.default,
      new JdkHttpTransport(ClientHttpSettings.default)(executionContext))(executionContext)

  def this(serverBaseUrl: String, interceptors: Seq[IHttpRequestInterceptor])(implicit executionContext: ExecutionContext) =
    this(serverBaseUrl, interceptors, ClientHttpSettings.default,
      new JdkHttpTransport(ClientHttpSettings.default)(executionContext))(executionContext)

  private val logger: Logger = LoggerFactory.getLogger(getClass)
  private val cleanServerBaseUrl = serverBaseUrl.stripSuffix("/")
  private val boundInterceptors = interceptors.map {
    case oauth: BearerTokenInterceptorFromTokenEndpoint => oauth.withTransport(transport)
    case other => other
  }

  override def getBaseUrl(): String = cleanServerBaseUrl

  def withBasicAuthentication(username: String, password: String): OnFhirNetworkClient =
    withInterceptor(new BasicAuthenticationInterceptor(username, password))

  def withFixedBasicTokenAuthentication(token: String): OnFhirNetworkClient =
    withInterceptor(new FixedBasicTokenInterceptor(token))

  def withFixedBearerTokenAuthentication(token: String): OnFhirNetworkClient =
    withInterceptor(FixedBearerTokenInterceptor(token))

  def withOpenIdBearerTokenAuthentication(
    clientId: String,
    clientSecret: String,
    requiredScopes: Seq[String],
    authzServerTokenEndpoint: String,
    clientAuthenticationMethod: String = "client_secret_basic"): OnFhirNetworkClient =
    withInterceptor(new BearerTokenInterceptorFromTokenEndpoint(
      clientId,
      clientSecret,
      requiredScopes,
      authzServerTokenEndpoint,
      clientAuthenticationMethod
    ))

  override def execute(fhirRequest: FHIRRequest): Future[FHIRResponse] = {
    val request = Try(FHIRRequestMarshaller.marshallRequest(fhirRequest, getBaseUrl())) match {
      case Success(value) => Future.successful(value)
      case Failure(error) => Future.failed(error)
    }
    request
      .flatMap(executeHttpRequest)
      .map(FHIRResponseUnmarshaller.unmarshallResponse)
      .recoverWith(handleFailure("Problem while executing FHIR request"))
  }

  override def next[T <: FHIRPaginatedBundle](bundle: T): Future[T] = {
    def findNextPage(paramName: String): Option[String] = {
      val uri = URI.create(bundle.getNext())
      Option(uri.getRawQuery)
        .map(OrderedQuery.parse)
        .flatMap(_.toMultiMap.get(paramName).flatMap(_.headOption))
    }

    val withPaginationParam = bundle.request match {
      case search: FhirSearchRequestBuilder if search.page.isDefined =>
        findNextPage(search.page.get._1).foreach(value => search.page = Some(search.page.get._1 -> value))
        true
      case history: FhirHistoryRequestBuilder if history.page.isDefined =>
        findNextPage(history.page.get._1).foreach(value => history.page = Some(history.page.get._1 -> value))
        true
      case _ => false
    }

    val fhirRequest =
      if (withPaginationParam) bundle.request.compileRequest()
      else getSearchPage(bundle.getNext()).compileRequest()

    execute(fhirRequest)
      .map(response => bundle.request
        .asInstanceOf[IFhirBundleReturningRequestBuilder]
        .constructBundle(response)
        .asInstanceOf[T])
      .recoverWith(handleFailure(s"Problem while executing FHIR request '${bundle.getNext()}'"))
  }

  private def withInterceptor(interceptor: IHttpRequestInterceptor): OnFhirNetworkClient =
    new OnFhirNetworkClient(serverBaseUrl, interceptors :+ interceptor, httpSettings, transport)

  private def executeHttpRequest(request: ClientHttpRequest): Future[ClientHttpResponse] =
    boundInterceptors
      .foldLeft(Future.successful(request)) { (processed, interceptor) =>
        processed.flatMap(interceptor.processRequest)
      }
      .flatMap(transport.execute)

  private def handleFailure[T](message: String): PartialFunction[Throwable, Future[T]] = {
    case clientError: FhirClientException => Future.failed(clientError)
    case error =>
      logger.error(message, error)
      Future.failed(FhirClientException.causedBy(s"$message: ${Option(error.getMessage).getOrElse(error.getClass.getSimpleName)}", error))
  }
}

object OnFhirNetworkClient {
  def apply(serverBaseUrl: String)(implicit executionContext: ExecutionContext): OnFhirNetworkClient =
    create(serverBaseUrl, Nil, ClientHttpSettings.default)

  def apply(
    serverBaseUrl: String,
    interceptors: Seq[IHttpRequestInterceptor])(implicit executionContext: ExecutionContext): OnFhirNetworkClient =
    create(serverBaseUrl, interceptors, ClientHttpSettings.default)

  def apply(
    serverBaseUrl: String,
    interceptor: IHttpRequestInterceptor)(implicit executionContext: ExecutionContext): OnFhirNetworkClient =
    create(serverBaseUrl, Seq(interceptor), ClientHttpSettings.default)

  def apply(
    serverBaseUrl: String,
    settings: ClientHttpSettings)(implicit executionContext: ExecutionContext): OnFhirNetworkClient =
    create(serverBaseUrl, Nil, settings)

  def apply(config: Config)(implicit executionContext: ExecutionContext): OnFhirNetworkClient = {
    val settings = ClientHttpSettings.fromConfig(config)
    val authzInterceptor = Try(config.getConfig("authz")).toOption.map { authzConfig =>
      authzConfig.getString("method") match {
        case "basic" => BasicAuthenticationInterceptor(authzConfig)
        case "oauth2" => BearerTokenInterceptorFromTokenEndpoint(authzConfig)
        case other => throw FhirClientException(s"Unsupported client authorization method: $other")
      }
    }
    create(config.getString("serverBaseUrl"), authzInterceptor.toSeq, settings)
  }

  private def create(
    serverBaseUrl: String,
    interceptors: Seq[IHttpRequestInterceptor],
    settings: ClientHttpSettings)(implicit executionContext: ExecutionContext): OnFhirNetworkClient = {
    val transport = new JdkHttpTransport(settings)
    new OnFhirNetworkClient(serverBaseUrl, interceptors, settings, transport)
  }
}
