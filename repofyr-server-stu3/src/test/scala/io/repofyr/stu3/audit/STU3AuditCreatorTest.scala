package io.repofyr.stu3.audit

import io.onfhir.api.{FHIR_INTERACTIONS, Resource}
import io.onfhir.api.model.{FHIRRequest, HttpStatus}
import io.onfhir.authz.AuthContext
import io.repofyr.config.OnfhirConfig
import org.json4s.JsonAST.{JArray, JNothing, JString, JValue}
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import java.util.Base64

/**
 * Tests for the STU3 AuditEvent record.
 *
 * These exist because two defects hid here for want of any coverage of the audit creators at all.
 * The record was assembled with `auditRecord ~ ("entity" -> allEntities)` and the result thrown
 * away rather than assigned, so no STU3 audit record ever carried an entity - not the resource
 * touched, not the patient it concerned, not the enclosing batch. And `createQueryEntity` was
 * written but never called, so a search interaction recorded nothing about what was searched for,
 * where the R4 creator records it.
 */
@RunWith(classOf[JUnitRunner])
class STU3AuditCreatorTest extends Specification {

  private val creator = new STU3AuditCreator()
  private val rootUrl = OnfhirConfig.fhirEndpointSettings.rootUrl

  private def auditFor(request: FHIRRequest): Resource =
    creator.createAuditResource(
      request,
      AuthContext(accessToken = None, networkAddress = "127.0.0.1"),
      authzContext = None,
      HttpStatus(200))

  private def entitiesOf(audit: Resource): List[JValue] =
    audit \ "entity" match {
      case JArray(items) => items
      case JNothing => Nil
      case single => List(single)
    }

  private def base64(value: String): String =
    Base64.getEncoder.encodeToString(value.getBytes("UTF-8"))

  "An STU3 audit record" should {

    "record the query of a search interaction" in {
      val audit = auditFor(
        FHIRRequest(
          interaction = FHIR_INTERACTIONS.SEARCH,
          requestUri = s"$rootUrl/Patient?name=john",
          resourceType = Some("Patient")))

      val entities = entitiesOf(audit)
      entities must not(beEmpty)
      // The trait base64-encodes the path and query, having stripped the service root.
      entities.map(_ \ "query") must contain(JString(base64("/Patient?name=john")))
    }

    "record the resource a read touched" in {
      // Independent of the query entity, so it fails on the discarded assignment alone.
      val audit = auditFor(
        FHIRRequest(
          interaction = FHIR_INTERACTIONS.READ,
          requestUri = s"$rootUrl/Patient/123",
          resourceType = Some("Patient"),
          resourceId = Some("123")))

      val references = entitiesOf(audit).map(_ \ "reference" \ "reference")
      references must contain(JString("Patient/123"))
    }

    "not attach a query entity to an interaction that has no query" in {
      val audit = auditFor(
        FHIRRequest(
          interaction = FHIR_INTERACTIONS.READ,
          requestUri = s"$rootUrl/Patient/123",
          resourceType = Some("Patient"),
          resourceId = Some("123")))

      entitiesOf(audit).map(_ \ "query") must not(contain(be_!=(JNothing: JValue)))
    }

    "still produce a valid record when nothing was resolved" in {
      // No entity key at all rather than an empty array, which is what the nonEmpty guard is for.
      val audit = auditFor(
        FHIRRequest(interaction = FHIR_INTERACTIONS.CAPABILITIES, requestUri = s"$rootUrl/metadata"))

      audit \ "resourceType" mustEqual JString("AuditEvent")
      audit \ "entity" mustEqual JNothing
    }
  }
}
