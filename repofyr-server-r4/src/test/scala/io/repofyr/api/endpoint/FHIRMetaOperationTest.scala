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
import org.json4s.JsonAST.{JArray, JObject, JString}
import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner

import scala.concurrent.duration.FiniteDuration
import scala.io.Source

/**
 * Endpoint coverage for the `$meta` and `$meta-add` operations, served by
 * `io.repofyr.operation.MetaOperationHandler`.
 *
 * Both route to one handler that switches on the operation name, so the property worth pinning is
 * that the name survives the URL and the reflective dispatch table intact - not merely that one of
 * them works.
 *
 * `$meta-delete` is deliberately absent: it throws a `ClassCastException` on any resource whose
 * meta has no `security` array, which is most of them. See entry 14 of
 * `docs/release/known-limitations.md`; the test belongs with the fix.
 */
@RunWith(classOf[JUnitRunner])
class FHIRMetaOperationTest extends OnFhirTest with FHIREndpoint {
  def actorRefFactory: ActorSystem = system
  implicit def default(implicit system: ActorSystem): RouteTestTimeout =
    RouteTestTimeout(new FiniteDuration(60, TimeUnit.SECONDS))

  val resourceType = "Patient"
  // A distinct id: the suites in this module share one database and no order is guaranteed
  // between them. An update also requires the body id to match the URL, so the sample is
  // rewritten rather than posted as-is.
  val resourceId = "meta-op-example"

  val patient: String = {
    val sample = Source.fromInputStream(getClass.getResourceAsStream("/fhir/samples/Patient/patient.json")).mkString
    sample.parseJson.asInstanceOf[JObject].transformField {
      case ("id", _) => "id" -> JString(resourceId)
    }.asInstanceOf[JObject].toJson
  }

  val base: String = "/" + OnfhirConfig.serverSettings.baseUri
  val tagSystem = "http://example.org/tags"
  val tagCode = "for-review"

  /** A Parameters body carrying a `meta` parameter with a single tag. */
  private def metaParameters(system: String, code: String): String =
    JObject(
      "resourceType" -> JString("Parameters"),
      "parameter" -> JArray(List(
        JObject(
          "name" -> JString("meta"),
          "valueMeta" -> JObject(
            "tag" -> JArray(List(
              JObject("system" -> JString(system), "code" -> JString(code)))))))))
      .toJson

  // The handler answers with Parameters carrying a `return` parameter of type Meta, so every
  // assertion reads through parameter.valueMeta rather than treating the body as a Meta.
  private def returnedTagCodes(body: Resource): Seq[String] =
    (body \ "parameter" \ "valueMeta" \ "tag" \ "code").extract[Seq[String]]

  private def returnedVersionIds(body: Resource): Seq[String] =
    (body \ "parameter" \ "valueMeta" \ "versionId").extract[Seq[String]]

  sequential

  "FHIR $meta operations" should {

    "create the resource the operations act on" in {
      Put(s"$base/$resourceType/$resourceId", HttpEntity(patient)) ~> fhirRoute ~> check {
        status === Created
      }
    }

    "return the meta of an existing resource" in {
      Get(s"$base/$resourceType/$resourceId/" + "$meta") ~> fhirRoute ~> check {
        status === OK
        (responseAs[Resource] \ "resourceType").extractOpt[String] must beSome("Parameters")
        returnedVersionIds(responseAs[Resource]) must contain("1")
      }
    }

    "add a tag through $meta-add" in {
      Post(s"$base/$resourceType/$resourceId/" + "$meta-add", HttpEntity(metaParameters(tagSystem, tagCode))) ~> fhirRoute ~> check {
        status === OK
        returnedTagCodes(responseAs[Resource]) must contain(tagCode)
      }
    }

    // Read back separately: $meta-add echoes what it wrote, so asserting only on its response
    // would not prove the tag was persisted.
    "persist the added tag" in {
      Get(s"$base/$resourceType/$resourceId/" + "$meta") ~> fhirRoute ~> check {
        status === OK
        returnedTagCodes(responseAs[Resource]) must contain(tagCode)
      }
    }

    "report 404 for the meta of a resource that does not exist" in {
      Get(s"$base/$resourceType/no-such-patient/" + "$meta") ~> fhirRoute ~> check {
        status === NotFound
        (responseAs[Resource] \ "resourceType").extractOpt[String] must beSome("OperationOutcome")
      }
    }
  }
}
