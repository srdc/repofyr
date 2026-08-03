package io.onfhir.client.model

import io.onfhir.api.model.{FhirContentType, HttpHeader, HttpHeaders, HttpMethod, HttpStatus}

import java.net.URI
import java.nio.charset.StandardCharsets

/** Immutable request body passed through client interceptors. */
final case class ClientHttpEntity(contentType: FhirContentType, bytes: Vector[Byte])

object ClientHttpEntity {
  def utf8(contentType: FhirContentType, value: String): ClientHttpEntity =
    ClientHttpEntity(contentType, value.getBytes(StandardCharsets.UTF_8).toVector)
}

/** Transport-neutral request contract exposed to client interceptors. */
final case class ClientHttpRequest(
  method: HttpMethod,
  uri: URI,
  headers: HttpHeaders = HttpHeaders(),
  entity: Option[ClientHttpEntity] = None) {

  def addHeader(name: String, value: String): ClientHttpRequest =
    copy(headers = headers.add(HttpHeader(name, value)))
}

/** Internal response returned by a client transport. */
private[client] final case class ClientHttpResponse(
  status: HttpStatus,
  headers: HttpHeaders,
  body: Vector[Byte])
