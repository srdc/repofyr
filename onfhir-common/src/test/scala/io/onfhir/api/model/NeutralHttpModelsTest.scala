package io.onfhir.api.model

import io.onfhir.util.DateTimeUtil
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import java.net.URI
import java.time.Instant

@RunWith(classOf[JUnitRunner])
class NeutralHttpModelsTest extends Specification {
  sequential

  "HttpStatus" should {
    "classify standard and unknown valid status codes" in {
      HttpStatus(100).isInformational must beTrue
      HttpStatus(204).isSuccess() must beTrue
      HttpStatus(399).isRedirection must beTrue
      HttpStatus(499).isClientError must beTrue
      HttpStatus(599).isServerError must beTrue
      HttpStatus(599).isFailure() must beTrue
    }

    "reject values outside the HTTP status range" in {
      HttpStatus(99) must throwA[IllegalArgumentException]
      HttpStatus(600) must throwA[IllegalArgumentException]
    }
  }

  "HttpMethod" should {
    "provide standard methods and preserve extension-method case" in {
      HttpMethod.GET.value mustEqual "GET"
      HttpMethod("Custom-Method").value mustEqual "Custom-Method"
      HttpMethod("custom-method").value mustEqual "custom-method"
    }

    "reject non-token characters" in {
      HttpMethod("NOT A METHOD") must throwA[IllegalArgumentException]
    }
  }

  "FhirMediaType" should {
    "normalize type names while preserving ordered repeated parameters" in {
      val mediaType = FhirMediaType(
        "Application",
        "FHIR+JSON",
        Vector(
          HttpParameter("Profile", "first", quoted = true),
          HttpParameter("profile", "second")
        )
      )

      mediaType.mainType mustEqual "application"
      mediaType.subType mustEqual "fhir+json"
      mediaType.parameterValues("PROFILE") mustEqual Vector("first", "second")
      mediaType.parameters.map(_.quoted) mustEqual Vector(true, false)
    }

    "retain an optional content charset" in {
      FhirContentType(FhirMediaType.application("fhir+json"), Some("UTF-8")).charset must beSome("UTF-8")
    }
  }

  "EntityTagCondition" should {
    "round-trip weak and strong ordered tag lists" in {
      val condition = EntityTagCondition.parse("W/\"one\", \"two\"")

      condition mustEqual EntityTagList(Vector(EntityTag("one", weak = true), EntityTag("two", weak = false)))
      condition.render mustEqual "W/\"one\", \"two\""
    }

    "represent the wildcard separately" in {
      EntityTagCondition.parse("*") mustEqual AnyEntityTag
      AnyEntityTag.render mustEqual "*"
    }
  }

  "AuthenticateChallenge" should {
    "round-trip token68 credentials" in {
      val challenge = AuthenticateChallenge.parse("Bearer abc.def-123==")

      challenge.credentials mustEqual Token68("abc.def-123==")
      challenge.render mustEqual "Bearer abc.def-123=="
    }

    "preserve ordered quoted authentication parameters" in {
      val challenge = AuthenticateChallenge.parse("Bearer realm=\"fhir api\", error=\"invalid_token\"")

      challenge.credentials mustEqual AuthenticationParameters(Vector(
        HttpParameter("realm", "fhir api", quoted = true),
        HttpParameter("error", "invalid_token", quoted = true)
      ))
      challenge.render mustEqual "Bearer realm=\"fhir api\", error=\"invalid_token\""
    }
  }

  "Forwarded and general header models" should {
    "preserve hop and repeated-header order with case-insensitive lookup" in {
      ForwardedFor(Vector("unknown", "192.0.2.1")).values mustEqual Vector("unknown", "192.0.2.1")
      ForwardedHost(Vector("example.org:8443", "proxy.internal")).values mustEqual
        Vector("example.org:8443", "proxy.internal")

      val headers = HttpHeaders(Vector(
        HttpHeader("Warning", "first"),
        HttpHeader("warning", "second")
      ))
      headers.values("WARNING") mustEqual Vector("first", "second")
    }
  }

  "OrderedQuery" should {
    "preserve duplicate order and distinguish absent from empty values" in {
      val query = OrderedQuery.parse("name=Alice&flag&empty=&name=Bob")

      query.pairs mustEqual Vector(
        QueryPair("name", Some("Alice")),
        QueryPair("flag", None),
        QueryPair("empty", Some("")),
        QueryPair("name", Some("Bob"))
      )
      query.render mustEqual "name=Alice&flag&empty=&name=Bob"
    }

    "decode once and encode logical values exactly once" in {
      val query = OrderedQuery.parse("code=a%2Bb&display=hello+world")

      query.pairs.map(_.value) mustEqual Vector(Some("a+b"), Some("hello world"))
      query.render mustEqual "code=a%2Bb&display=hello+world"
    }
  }

  "URI and HTTP date boundaries" should {
    "preserve raw URI path and query encoding" in {
      val uri = URI.create("Patient/a%2Fb?code=a%2Bb")

      uri.getRawPath mustEqual "Patient/a%2Fb"
      uri.getRawQuery mustEqual "code=a%2Bb"
    }

    "serialize HTTP dates at second precision without changing FHIR instant serialization" in {
      val instant = Instant.parse("2008-04-09T23:55:38.987Z")

      DateTimeUtil.formatHttpDate(instant) mustEqual "Wed, 09 Apr 2008 23:55:38 GMT"
      DateTimeUtil.parseHttpDate("Wed, 09 Apr 2008 23:55:38 GMT") mustEqual
        Instant.parse("2008-04-09T23:55:38Z")
      DateTimeUtil.serializeInstant(instant) mustEqual "2008-04-09T23:55:38.987Z"
    }
  }
}
