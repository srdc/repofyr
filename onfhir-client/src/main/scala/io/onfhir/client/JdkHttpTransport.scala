package io.onfhir.client

import io.onfhir.api.model.{HttpHeader, HttpHeaders, HttpMethod, HttpStatus}
import io.onfhir.client.model.{ClientHttpRequest, ClientHttpResponse, ClientHttpSettings}

import java.io.IOException
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.concurrent.{CompletionException, ExecutionException, Executor}
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.jdk.FutureConverters._

private[client] trait ClientHttpTransport {
  def execute(request: ClientHttpRequest): Future[ClientHttpResponse]
}

private[client] final class JdkHttpTransport(
  settings: ClientHttpSettings)(implicit executionContext: ExecutionContext) extends ClientHttpTransport {

  private val client: HttpClient = {
    val executor = new Executor {
      override def execute(command: Runnable): Unit = executionContext.execute(command)
    }
    val builder = HttpClient.newBuilder()
      .connectTimeout(settings.connectTimeout)
      .executor(executor)
      .followRedirects(HttpClient.Redirect.NEVER)
      .version(HttpClient.Version.HTTP_1_1)
    settings.sslContext.foreach(builder.sslContext)
    builder.build()
  }

  override def execute(request: ClientHttpRequest): Future[ClientHttpResponse] =
    send(request, settings.maxRetries)

  private def send(request: ClientHttpRequest, retriesRemaining: Int): Future[ClientHttpResponse] = {
    val sent = try {
      client
        .sendAsync(toJdkRequest(request), HttpResponse.BodyHandlers.ofByteArray())
        .asScala
        .map(toNeutralResponse)
    } catch {
      case error: Throwable => Future.failed(error)
    }

    sent.recoverWith {
      case error if retriesRemaining > 0 && isRetryable(request.method, unwrap(error)) =>
        send(request, retriesRemaining - 1)
      case error => Future.failed(unwrap(error))
    }
  }

  private def toJdkRequest(request: ClientHttpRequest): HttpRequest = {
    val builder = HttpRequest.newBuilder(request.uri)
      .version(HttpClient.Version.HTTP_1_1)
    settings.requestTimeout.foreach(builder.timeout)
    request.headers.entries.foreach(header => builder.header(header.name, header.value))
    request.entity.foreach(entity => builder.header("Content-Type", entity.contentType.value))
    val publisher = request.entity
      .map(entity => HttpRequest.BodyPublishers.ofByteArray(entity.bytes.toArray))
      .getOrElse(HttpRequest.BodyPublishers.noBody())
    builder.method(request.method.value, publisher).build()
  }

  private def toNeutralResponse(response: HttpResponse[Array[Byte]]): ClientHttpResponse = {
    val headers = response.headers().map().asScala.toVector.flatMap { case (name, values) =>
      values.asScala.map(value => HttpHeader(name, value))
    }
    ClientHttpResponse(
      status = HttpStatus(response.statusCode()),
      headers = HttpHeaders(headers),
      body = Option(response.body()).fold(Vector.empty[Byte])(_.toVector)
    )
  }

  private def isRetryable(method: HttpMethod, error: Throwable): Boolean =
    JdkHttpTransport.retryableMethods.contains(method.value) && error.isInstanceOf[IOException]

  private def unwrap(error: Throwable): Throwable = error match {
    case completion: CompletionException if completion.getCause != null => unwrap(completion.getCause)
    case execution: ExecutionException if execution.getCause != null => unwrap(execution.getCause)
    case other => other
  }
}

private object JdkHttpTransport {
  val retryableMethods: Set[String] = Set("GET", "HEAD", "OPTIONS", "PUT", "DELETE", "TRACE")
}
