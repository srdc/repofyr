package io.repofyr.r5

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
 * End-to-end boot smoke test for the R5 server.
 *
 * repofyr-server-r5 previously had a single test that never started a server,
 * so nothing in this module proved that the R5 configurator, the packaged R5
 * definitions, the database initialization and the FHIR routes actually fit
 * together. A partial release rehearsal once shipped a broken sibling module
 * for exactly that reason, and the STU3 startup defect fixed in 4.0.0 - packaged
 * resource names that did not match what the configurator derives - would have
 * been caught here on the first request. Everything is left at its default so
 * this exercises the same classpath lookups a stock deployment performs.
 */
@RunWith(classOf[JUnitRunner])
class R5ServerBootTest extends OnFhirR5Test with FHIREndpoint {
  def actorRefFactory: ActorSystem = system

  implicit def default(implicit system: ActorSystem): RouteTestTimeout =
    RouteTestTimeout(new FiniteDuration(60, TimeUnit.SECONDS))

  private val fhirBase = "/" + OnfhirConfig.serverSettings.baseUri
  private val resourceType = "Patient"

  // Kept minimal and version-neutral on purpose: the point of the round trip is
  // the interaction path, not R5 resource coverage.
  private val patient =
    """{
      |  "resourceType": "Patient",
      |  "active": true,
      |  "name": [{"use": "official", "family": "Bootcheck", "given": ["Rosalind"]}],
      |  "gender": "female",
      |  "birthDate": "1974-12-25"
      |}""".stripMargin

  sequential

  "Repofyr R5 server" should {
    "serve a CapabilityStatement declaring FHIR 5.0.0" in {
      Get(fhirBase + "/metadata") ~> fhirRoute ~> check {
        status === OK
        val capabilityStatement = responseAs[Resource]
        (capabilityStatement \ "resourceType").extractOpt[String] must beSome("CapabilityStatement")
        // The version the server advertises comes from the packaged
        // conformance-statement-r5.json, so this pins the definitions artifact
        // the module actually resolved.
        (capabilityStatement \ "fhirVersion").extractOpt[String] must beSome("5.0.0")
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
