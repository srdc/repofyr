package io.onfhir.client.intrcp

import io.onfhir.client.IHttpRequestInterceptor
import io.onfhir.client.model.ClientHttpRequest

import scala.concurrent.{ExecutionContext, Future}

/**
 * Interceptor to inject a fixed basic token to Http Requests
 *
 * @param token The fixed token
 */
class FixedBasicTokenInterceptor(token: String) extends IHttpRequestInterceptor with Serializable {
  /**
   * Intercept and update the http request by adding the following Authorization header: "Basic <token>"
   *
   * @param httpRequest
   * @return
   */
  override def processRequest(httpRequest: ClientHttpRequest)(implicit ex: ExecutionContext): Future[ClientHttpRequest] =
    Future.successful(httpRequest.addHeader("Authorization", s"Basic $token"))
}
