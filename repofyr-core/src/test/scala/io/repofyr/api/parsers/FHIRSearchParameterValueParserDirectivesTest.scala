package io.repofyr.api.parsers

import akka.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import akka.http.scaladsl.server.Directives.{complete, path}
import akka.http.scaladsl.testkit.Specs2RouteTest
import io.onfhir.api.model.Parameter
import io.onfhir.config.{FhirSearchHandling, FhirServerConfig}
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import io.onfhir.api.parsers.FHIRSearchParameterValueParser

@RunWith(classOf[JUnitRunner])
class FHIRSearchParameterValueParserDirectivesTest extends Specification with Specs2RouteTest {
  sequential

  "FHIRSearchParameterValueParserDirectives" should {
    "extract URI query parameters and pass the Prefer handling value to the parser" in {
      val parser = new RecordingParser
      val directives = new FHIRSearchParameterValueParserDirectives(parser)
      val route = path("Patient") {
        directives.parseSearchParametersFromUri("Patient", Some("strict")) { _ =>
          complete(StatusCodes.OK)
        }
      }

      Get("/Patient?name=Alice&name=Bob") ~> route ~> check {
        status mustEqual StatusCodes.OK
        parser.resourceType mustEqual "Patient"
        parser.parameters mustEqual Map("name" -> List("Alice", "Bob"))
        parser.preferHeader must beSome("strict")
      }
    }

    "extract form fields and pass the Prefer handling value to the parser" in {
      val parser = new RecordingParser
      val directives = new FHIRSearchParameterValueParserDirectives(parser)
      val route = path("Patient") {
        directives.parseSearchParametersFromEntity("Patient", Some("lenient")) { _ =>
          complete(StatusCodes.OK)
        }
      }
      val entity = HttpEntity(ContentTypes.`application/x-www-form-urlencoded`, "name=Alice&name=Bob")

      Post("/Patient", entity) ~> route ~> check {
        status mustEqual StatusCodes.OK
        parser.resourceType mustEqual "Patient"
        // Akka's formFieldMultiMap preserves the legacy reverse order for repeated fields.
        parser.parameters mustEqual Map("name" -> List("Bob", "Alice"))
        parser.preferHeader must beSome("lenient")
      }
    }
  }

  private final class RecordingParser
    extends FHIRSearchParameterValueParser(new FhirServerConfig("R4"), FhirSearchHandling.Strict) {

    var resourceType: String = ""
    var parameters: Map[String, List[String]] = Map.empty
    var preferHeader: Option[String] = None

    override def parseSearchParameters(
      resourceType: String,
      parameters: Map[String, List[String]],
      preferHeader: Option[String]
    ): List[Parameter] = {
      this.resourceType = resourceType
      this.parameters = parameters
      this.preferHeader = preferHeader
      Nil
    }
  }
}
