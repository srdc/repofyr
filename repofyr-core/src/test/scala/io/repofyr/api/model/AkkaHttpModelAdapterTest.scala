package io.repofyr.api.model

import akka.http.scaladsl.model.{DateTime, HttpMethod => AkkaHttpMethod, MediaTypes, StatusCodes, Uri}
import akka.http.scaladsl.model.headers.RawHeader
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import java.net.URI
import java.time.Instant
import io.onfhir.api.model.AuthenticateChallenge
import io.onfhir.api.model.EntityTag
import io.onfhir.api.model.EntityTagList
import io.onfhir.api.model.FhirContentType
import io.onfhir.api.model.FhirMediaType
import io.onfhir.api.model.ForwardedFor
import io.onfhir.api.model.HttpMethod
import io.onfhir.api.model.HttpStatus

@RunWith(classOf[JUnitRunner])
class AkkaHttpModelAdapterTest extends Specification {
  sequential

  "AkkaHttpModelAdapter" should {
    "round-trip standard and extension status codes" in {
      AkkaHttpModelAdapter.toNeutralStatus(StatusCodes.NoContent) mustEqual HttpStatus.NoContent

      val extension = HttpStatus(471)
      AkkaHttpModelAdapter.toNeutralStatus(AkkaHttpModelAdapter.toAkkaStatus(extension)) mustEqual extension
    }

    "preserve raw URI encoding at the transport boundary" in {
      val neutral = URI.create("Patient/a%2Fb?code=a%2Bb")
      val akka = AkkaHttpModelAdapter.toAkkaUri(neutral)

      akka mustEqual Uri("Patient/a%2Fb?code=a%2Bb")
      AkkaHttpModelAdapter.toNeutralUri(akka).toASCIIString mustEqual neutral.toASCIIString
    }

    "apply HTTP second precision only at the Akka date boundary" in {
      val instant = Instant.parse("2026-08-03T10:20:30.987Z")
      val httpPrecision = Instant.parse("2026-08-03T10:20:30Z")

      AkkaHttpModelAdapter.toNeutralInstant(AkkaHttpModelAdapter.toAkkaDateTime(instant)) mustEqual httpPrecision
      AkkaHttpModelAdapter.toAkkaDateTime(instant) mustEqual DateTime(instant.toEpochMilli)
    }

    "convert media, content, and extension method values" in {
      val mediaType = FhirMediaType.application("fhir+json")
      AkkaHttpModelAdapter.toAkkaMediaType(mediaType).value mustEqual "application/fhir+json"
      AkkaHttpModelAdapter.toNeutralMediaType(MediaTypes.`application/json`) mustEqual
        FhirMediaType.application("json")

      val contentType = FhirContentType(mediaType, Some("UTF-8"))
      AkkaHttpModelAdapter.toNeutralContentType(AkkaHttpModelAdapter.toAkkaContentType(contentType)) mustEqual
        contentType

      val method = HttpMethod("PURGE")
      val akkaMethod: AkkaHttpMethod = AkkaHttpModelAdapter.toAkkaMethod(method)
      AkkaHttpModelAdapter.toNeutralMethod(akkaMethod) mustEqual method
    }

    "preserve conditional, forwarded, and authentication header values" in {
      AkkaHttpModelAdapter.toNeutralEntityTagCondition(RawHeader("If-Match", "W/\"one\", \"two\"")) mustEqual
        EntityTagList(Vector(EntityTag("one", weak = true), EntityTag("two")))
      AkkaHttpModelAdapter.toNeutralForwardedFor(RawHeader("X-Forwarded-For", "unknown, 192.0.2.1")) mustEqual
        ForwardedFor(Vector("unknown", "192.0.2.1"))

      val challenge = AuthenticateChallenge.parse("Bearer realm=\"fhir api\", error=\"invalid_token\"")
      AkkaHttpModelAdapter.toAkkaAuthenticateHeader(challenge).value() mustEqual challenge.render
    }
  }
}
