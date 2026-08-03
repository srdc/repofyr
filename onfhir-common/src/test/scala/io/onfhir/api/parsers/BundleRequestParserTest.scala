package io.onfhir.api.parsers

import io.onfhir.api.FHIR_INTERACTIONS
import io.onfhir.config.FhirEndpointSettings
import io.onfhir.util.JsonFormatter._
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class BundleRequestParserTest extends Specification {
  "BundleRequestParser" should {
    "accept unescaped FHIR token separators in an entry query" in {
      val entry =
        """{
          |  "request": {
          |    "method": "GET",
          |    "url": "Patient?identifier=urn:oid:1.2.3|12345&flag&empty="
          |  }
          |}""".stripMargin.parseJson

      val request = BundleRequestParser.parseBundleRequestEntry(
        entry,
        FhirEndpointSettings("http://localhost:8080/fhir")
      )

      request.interaction mustEqual FHIR_INTERACTIONS.SEARCH
      request.resourceType must beSome("Patient")
      request.queryParams("identifier") mustEqual List("urn:oid:1.2.3|12345")
      request.queryParams("flag") mustEqual List("")
      request.queryParams("empty") mustEqual List("")
    }

    "report invalid entry paths with a transport-neutral parsing exception" in {
      val entry =
        """{
          |  "request": {
          |    "method": "GET",
          |    "url": "Patient/1/unsupported/path/shape"
          |  }
          |}""".stripMargin.parseJson

      BundleRequestParser.parseBundleRequestEntry(
        entry,
        FhirEndpointSettings("http://localhost:8080/fhir")
      ) must throwA[BundleRequestParsingException]
    }
  }
}
