package io.repofyr.stu3

import akka.actor.ActorSystem
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.testkit.RouteTestTimeout
import io.onfhir.api.Resource
import io.onfhir.api.util.FHIRUtil
import io.onfhir.util.JsonFormatter._
import io.repofyr.api.endpoint.FHIREndpoint
import io.repofyr.api.model.FHIRMarshallers._
import io.repofyr.config.OnfhirConfig
import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

/**
 * End-to-end boot smoke test for the STU3 server.
 *
 * repofyr-server-stu3 previously had a single test that stopped at
 * initializeServerPlatform, so nothing in this module proved that the STU3
 * configurator, the packaged STU3 definitions, the database initialization and
 * the FHIR routes actually fit together. A partial release rehearsal once
 * shipped a broken repofyr-server-stu3 for exactly that reason, and the STU3
 * startup defect fixed in 4.0.0 - packaged resource names that did not match
 * what FhirSTU3Configurator derives - would have been caught here on the first
 * request. Everything is left at its default so this exercises the same
 * classpath lookups a stock deployment performs.
 */
@RunWith(classOf[JUnitRunner])
class STU3ServerBootTest extends OnFhirSTU3Test with FHIREndpoint {
  def actorRefFactory: ActorSystem = system

  implicit def default(implicit system: ActorSystem): RouteTestTimeout =
    RouteTestTimeout(new FiniteDuration(60, TimeUnit.SECONDS))

  private val fhirBase = "/" + OnfhirConfig.serverSettings.baseUri
  private val resourceType = "Patient"

  // Kept minimal and version-neutral on purpose: the point of the round trip is
  // the interaction path, not STU3 resource coverage.
  private val patient =
    """{
      |  "resourceType": "Patient",
      |  "active": true,
      |  "name": [{"use": "official", "family": "Bootcheck", "given": ["Rosalind"]}],
      |  "gender": "female",
      |  "birthDate": "1974-12-25"
      |}""".stripMargin

  sequential

  "Repofyr STU3 server" should {
    "serve a CapabilityStatement declaring FHIR 3.0.1" in {
      Get(fhirBase + "/metadata") ~> fhirRoute ~> check {
        status === OK
        val capabilityStatement = responseAs[Resource]
        (capabilityStatement \ "resourceType").extractOpt[String] must beSome("CapabilityStatement")
        // The version the server advertises comes from the packaged
        // conformance-statement-stu3.json, so this pins the definitions artifact
        // the module actually resolved - the resource whose name mismatch broke
        // STU3 startup before 4.0.0.
        (capabilityStatement \ "fhirVersion").extractOpt[String] must beSome("3.0.1")
        (capabilityStatement \ "rest" \ "resource" \ "type").extract[Seq[String]] must contain(resourceType)
      }
    }

    "round trip a resource through create, read, search and delete" in {
      var createdId = ""

      Post(fhirBase + "/" + resourceType, HttpEntity(patient)) ~> fhirRoute ~> check {
        status === Created
        val created = responseAs[Resource]
        createdId = FHIRUtil.extractValueOption[String](created, "id").getOrElse("")
        createdId must not(beEmpty)
        FHIRUtil.extractValueOptionByPath[String](created, "meta.versionId") must beSome("1")
      }

      Get(fhirBase + "/" + resourceType + "/" + createdId) ~> fhirRoute ~> check {
        status === OK
        val read = responseAs[Resource]
        FHIRUtil.extractValueOption[String](read, "id") must beSome(createdId)
        (read \ "name" \ "family").extract[Seq[String]] must contain("Bootcheck")
      }

      // Searching by a base search parameter proves the search parameter
      // configuration and the database indexes were built for the resource type,
      // not just that the document was stored.
      Get(fhirBase + "/" + resourceType + "?family=Bootcheck") ~> fhirRoute ~> check {
        status === OK
        val bundle = responseAs[Resource]
        (bundle \ "resourceType").extractOpt[String] must beSome("Bundle")
        (bundle \ "type").extractOpt[String] must beSome("searchset")
        (bundle \ "total").extractOpt[Int] must beSome(1)
        (bundle \ "entry" \ "resource" \ "id").extract[Seq[String]] must contain(createdId)
      }

      Delete(fhirBase + "/" + resourceType + "/" + createdId) ~> fhirRoute ~> check {
        status === NoContent
      }

      Get(fhirBase + "/" + resourceType + "/" + createdId) ~> fhirRoute ~> check {
        status === Gone
        (responseAs[Resource] \ "resourceType").extractOpt[String] must beSome("OperationOutcome")
      }
    }
  }
}
