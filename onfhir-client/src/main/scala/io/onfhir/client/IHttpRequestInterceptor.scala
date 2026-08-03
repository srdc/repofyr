package io.onfhir.client

import io.onfhir.client.model.ClientHttpRequest

import scala.concurrent.{ExecutionContext, Future}

trait IHttpRequestInterceptor {
  /**
   * Intercept and update the http request according to interceptor's own logic e.g. adding headers
   * @param httpRequest
   * @return
   */
  def processRequest(httpRequest: ClientHttpRequest)(implicit ex:ExecutionContext):Future[ClientHttpRequest]
}
