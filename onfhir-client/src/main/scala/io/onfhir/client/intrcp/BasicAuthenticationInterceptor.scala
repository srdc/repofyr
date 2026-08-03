package io.onfhir.client.intrcp
import com.typesafe.config.Config
import io.onfhir.client.IHttpRequestInterceptor
import io.onfhir.client.model.ClientHttpRequest

import java.nio.charset.StandardCharsets
import java.util.Base64

import scala.concurrent.{ExecutionContext, Future}

/**
 * Basic authentication handler
 * @param username  Username
 * @param password  Password
 */
class BasicAuthenticationInterceptor(username:String, password:String) extends IHttpRequestInterceptor with Serializable {
  /**
   * Intercept and update the http request according to interceptor's own logic e.g. adding headers
   *
   * @param httpRequest
   * @return
   */
  override def processRequest(httpRequest: ClientHttpRequest)(implicit ex: ExecutionContext): Future[ClientHttpRequest] =
    Future.successful(httpRequest.addHeader("Authorization", BasicAuthenticationInterceptor.credentials(username, password)))
}

object BasicAuthenticationInterceptor {
  private[client] def credentials(username: String, password: String): String = {
    val encoded = Base64.getEncoder.encodeToString(s"$username:$password".getBytes(StandardCharsets.UTF_8))
    s"Basic $encoded"
  }

  def apply(username:String, password:String):BasicAuthenticationInterceptor = new BasicAuthenticationInterceptor(username, password)

  def apply(config:Config):BasicAuthenticationInterceptor = {
    new BasicAuthenticationInterceptor(config.getString("username"), config.getString("password"))
  }
}
