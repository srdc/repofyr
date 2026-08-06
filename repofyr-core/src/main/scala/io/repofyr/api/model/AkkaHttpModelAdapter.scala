package io.repofyr.api.model

import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{ContentType, DateTime, HttpHeader => AkkaHttpHeader, HttpMethod => AkkaHttpMethod, MediaType, StatusCode, StatusCodes, Uri}

import java.net.URI
import java.time.Instant
import scala.language.implicitConversions
import io.onfhir.api.model.AuthenticateChallenge
import io.onfhir.api.model.EntityTagCondition
import io.onfhir.api.model.FhirContentType
import io.onfhir.api.model.FhirMediaType
import io.onfhir.api.model.ForwardedFor
import io.onfhir.api.model.ForwardedHost
import io.onfhir.api.model.HttpMethod
import io.onfhir.api.model.HttpStatus

/** Akka HTTP conversions owned by the server transport boundary. */
object AkkaHttpModelAdapter {
  def toNeutralStatus(status: StatusCode): HttpStatus = HttpStatus(status.intValue)

  def toAkkaStatus(status: HttpStatus): StatusCode =
    StatusCodes.getForKey(status.code).getOrElse {
      val reason = status.value.stripPrefix(status.code.toString).trim
      StatusCodes.custom(status.code, if (reason.nonEmpty) reason else "Unknown Status", "")
    }

  def toNeutralUri(uri: Uri): URI = URI.create(uri.toString())
  def toAkkaUri(uri: URI): Uri = Uri(uri.toString)

  def toNeutralInstant(dateTime: DateTime): Instant = Instant.ofEpochMilli(dateTime.clicks)
  def toAkkaDateTime(instant: Instant): DateTime = DateTime(instant.toEpochMilli)

  def toNeutralMediaType(mediaType: MediaType): FhirMediaType = FhirMediaType.parse(mediaType.value)

  def toAkkaMediaType(mediaType: FhirMediaType): MediaType =
    MediaType.parse(mediaType.toString) match {
      case Right(value) => value
      case Left(errors) => throw new IllegalArgumentException(errors.map(_.formatPretty).mkString("; "))
    }

  def toAkkaContentType(contentType: FhirContentType): ContentType =
    ContentType.parse(contentType.value) match {
      case Right(value) => value
      case Left(errors) => throw new IllegalArgumentException(errors.map(_.formatPretty).mkString("; "))
    }

  def toNeutralContentType(contentType: ContentType): FhirContentType = {
    val mediaType = toNeutralMediaType(contentType.mediaType)
    FhirContentType(mediaType, contentType.charsetOption.map(_.value))
  }

  def toNeutralEntityTagCondition(header: AkkaHttpHeader): EntityTagCondition =
    EntityTagCondition.parse(header.value())

  def toNeutralMethod(method: AkkaHttpMethod): HttpMethod = HttpMethod(method.value)
  def toAkkaMethod(method: HttpMethod): AkkaHttpMethod =
    akka.http.scaladsl.model.HttpMethods.getForKey(method.value).getOrElse(AkkaHttpMethod.custom(method.value))

  def toAkkaBinaryMediaType(mediaType: FhirMediaType): MediaType.Binary =
    toAkkaMediaType(mediaType) match {
      case binary: MediaType.Binary => binary
      case other => throw new IllegalArgumentException(s"Expected a binary media type, found $other")
    }

  def toNeutralForwardedFor(header: AkkaHttpHeader): ForwardedFor =
    ForwardedFor(splitHeaderValues(header.value()))

  def toNeutralForwardedHost(header: AkkaHttpHeader): ForwardedHost =
    ForwardedHost(splitHeaderValues(header.value()))

  def toAkkaAuthenticateHeader(challenge: AuthenticateChallenge): AkkaHttpHeader =
    RawHeader("WWW-Authenticate", challenge.render)

  implicit def akkaStatusToNeutral(status: StatusCode): HttpStatus = toNeutralStatus(status)
  implicit def neutralStatusToAkka(status: HttpStatus): StatusCode = toAkkaStatus(status)
  implicit def akkaUriToNeutral(uri: Uri): URI = toNeutralUri(uri)
  implicit def neutralUriToAkka(uri: URI): Uri = toAkkaUri(uri)
  implicit def akkaDateTimeToNeutral(dateTime: DateTime): Instant = toNeutralInstant(dateTime)
  implicit def neutralInstantToAkka(instant: Instant): DateTime = toAkkaDateTime(instant)

  private def splitHeaderValues(value: String): Vector[String] =
    value.split(',').iterator.map(_.trim).filter(_.nonEmpty).toVector
}
