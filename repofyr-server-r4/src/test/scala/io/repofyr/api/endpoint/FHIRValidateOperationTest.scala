package io.repofyr.api.endpoint

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.testkit.RouteTestTimeout
import io.onfhir.api.Resource
import io.onfhir.util.JsonFormatter._
import io.repofyr.OnFhirTest
import io.repofyr.api.model.FHIRMarshallers._
import io.repofyr.config.OnfhirConfig
import org.json4s.JsonAST.{JObject, JString}
import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner

import scala.concurrent.duration.FiniteDuration
import scala.io.Source

/**
 * Endpoint coverage for the `$validate` operation, served by
 * `io.repofyr.operation.ValidationOperationHandler`.
 *
 * `repofyr-operations` had no tests of its own before 4.0.0. The handlers are reached through a
 * reflective dispatch table (see `DefaultOperationHandlersTest`) and are only exercised over HTTP,
 * so an endpoint suite is the honest way to cover them: it proves the route, the dispatch, the
 * parameter parsing and the handler together.
 */
@RunWith(classOf[JUnitRunner])
class FHIRValidateOperationTest extends OnFhirTest with FHIREndpoint {
  def actorRefFactory: ActorSystem = system
  implicit def default(implicit system: ActorSystem): RouteTestTimeout =
    RouteTestTimeout(new FiniteDuration(60, TimeUnit.SECONDS))

  val patient: String =
    Source.fromInputStream(getClass.getResourceAsStream("/fhir/samples/Patient/patient.json")).mkString

  val resourceType = "Patient"
  val base: String = "/" + OnfhirConfig.serverSettings.baseUri

  sequential

  /** Wrap a resource as the `resource` parameter of a Parameters body. */
  private def parameters(resource: String): String =
    JObject(
      "resourceType" -> JString("Parameters"),
      "parameter" -> org.json4s.JsonAST.JArray(List(
        JObject(
          "name" -> JString("resource"),
          "resource" -> resource.parseJson)))).toJson

  private def parametersWithMode(resource: String, mode: String): String =
    JObject(
      "resourceType" -> JString("Parameters"),
      "parameter" -> org.json4s.JsonAST.JArray(List(
        JObject("name" -> JString("resource"), "resource" -> resource.parseJson),
        JObject("name" -> JString("mode"), "valueCode" -> JString(mode))))).toJson

  "FHIR $validate operation" should {

    "return an OperationOutcome for a valid resource" in {
      Post(s"$base/$resourceType/" + "$validate", HttpEntity(parameters(patient))) ~> fhirRoute ~> check {
        status === OK
        val outcome = responseAs[Resource]
        // The handler answers with the OperationOutcome directly rather than wrapping it in
        // Parameters, so assert on the resource type actually returned.
        (outcome \ "resourceType").extractOpt[String] must beSome("OperationOutcome")
      }
    }

    "accept an explicit create validation mode" in {
      Post(s"$base/$resourceType/" + "$validate", HttpEntity(parametersWithMode(patient, "create"))) ~> fhirRoute ~> check {
        status === OK
        (responseAs[Resource] \ "resourceType").extractOpt[String] must beSome("OperationOutcome")
      }
    }

    // The handler rejects an unknown mode before touching the resource, so this pins the
    // parameter validation rather than the profile machinery.
    "reject an unsupported validation mode" in {
      Post(s"$base/$resourceType/" + "$validate", HttpEntity(parametersWithMode(patient, "sideways"))) ~> fhirRoute ~> check {
        status === BadRequest
        (responseAs[Resource] \ "resourceType").extractOpt[String] must beSome("OperationOutcome")
      }
    }

    // Everything but delete mode needs a body; an empty Parameters is the shape a client most
    // easily gets wrong.
    "reject a missing resource parameter" in {
      val empty = JObject("resourceType" -> JString("Parameters")).toJson
      Post(s"$base/$resourceType/" + "$validate", HttpEntity(empty)) ~> fhirRoute ~> check {
        status === BadRequest
        (responseAs[Resource] \ "resourceType").extractOpt[String] must beSome("OperationOutcome")
      }
    }

    "report issues for a resource that violates the base profile" in {
      // gender is a coded element; an arbitrary string is invalid against the base Patient profile.
      val invalid = patient.parseJson.asInstanceOf[JObject].transformField {
        case ("gender", _) => "gender" -> JString("not-a-gender")
      }.asInstanceOf[JObject].toJson

      Post(s"$base/$resourceType/" + "$validate", HttpEntity(parameters(invalid))) ~> fhirRoute ~> check {
        val outcome = responseAs[Resource]
        (outcome \ "resourceType").extractOpt[String] must beSome("OperationOutcome")
        // Validation failures are reported in the outcome, not as a transport error.
        (outcome \ "issue" \ "severity").extract[Seq[String]] must contain("error")
      }
    }
  }
}
