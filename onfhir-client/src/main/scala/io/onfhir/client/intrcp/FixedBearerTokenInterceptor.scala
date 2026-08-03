package io.onfhir.client.intrcp

import io.onfhir.client.IHttpRequestInterceptor
import io.onfhir.client.model.ClientHttpRequest

import scala.concurrent.{ExecutionContext, Future}

abstract class BearerTokenInterceptor extends IHttpRequestInterceptor {
  def addHeader(httpRequest: ClientHttpRequest, bearerToken:String):ClientHttpRequest =
    httpRequest.addHeader("Authorization", s"Bearer $bearerToken")
}

case class FixedBearerTokenInterceptor(bearerToken:String) extends BearerTokenInterceptor {
  /**
   * Intercept and update the http request according to interceptor's own logic e.g. adding headers
   *
   * @param httpRequest
   * @return
   */
  override def processRequest(httpRequest: ClientHttpRequest)(implicit ex:ExecutionContext): Future[ClientHttpRequest] =
    Future.successful(addHeader(httpRequest, bearerToken))
}
