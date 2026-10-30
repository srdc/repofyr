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
import io.repofyr.config.{FhirConfigurationManager, OnfhirConfig}
import io.repofyr.db.DocumentManager
import io.repofyr.exception.InternalServerException
import io.repofyr.operation.PatientPurgeOperationHandler
import com.mongodb.MongoClientSettings
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.bson.collection.immutable.Document
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.Filters
import org.json4s.JsonAST.{JArray, JObject, JString}
import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.io.Source

/**
 * Endpoint coverage for Patient `$purge`, served by `io.repofyr.operation.PatientPurgeOperationHandler`.
 *
 * The property worth pinning is that nothing of the patient survives: neither the current versions
 * nor the history a normal FHIR delete leaves behind - while another patient's data stays untouched.
 */
@RunWith(classOf[JUnitRunner])
class FHIRPurgeOperationTest extends OnFhirTest with FHIREndpoint {
  def actorRefFactory: ActorSystem = system
  implicit def default(implicit system: ActorSystem): RouteTestTimeout =
    RouteTestTimeout(new FiniteDuration(60, TimeUnit.SECONDS))

  // Distinct ids: the suites in this module share one database and no order is guaranteed between them.
  val purgedPatient = "purge-op-patient"
  val deletedPatient = "purge-op-deleted-patient"
  val otherPatient = "purge-op-other-patient"
  val linkedPatientId = "purge-op-linked-patient"

  val base: String = "/" + OnfhirConfig.serverSettings.baseUri

  private def patient(id: String): String =
    Source.fromInputStream(getClass.getResourceAsStream("/fhir/samples/Patient/patient.json")).mkString
      .parseJson.asInstanceOf[JObject].transformField {
        case ("id", _) => "id" -> JString(id)
      }.asInstanceOf[JObject].toJson

  private def observation(id: String, pid: String, status: String = "final"): String =
    JObject(
      "resourceType" -> JString("Observation"),
      "id" -> JString(id),
      "status" -> JString(status),
      "code" -> JObject("text" -> JString("Body weight")),
      "subject" -> JObject("reference" -> JString(s"Patient/$pid"))
    ).toJson

  private def condition(id: String, pid: String): String =
    JObject(
      "resourceType" -> JString("Condition"),
      "id" -> JString(id),
      "clinicalStatus" -> JObject("coding" -> JArray(List(
        JObject("system" -> JString("http://terminology.hl7.org/CodeSystem/condition-clinical"), "code" -> JString("active"))))),
      "code" -> JObject("text" -> JString("Asthma")),
      "subject" -> JObject("reference" -> JString(s"Patient/$pid"))
    ).toJson

  // Appointment joins the compartment through 'actor', a participant list rather than a single subject.
  // 'proposed' because a booked appointment must carry start and end (app-3).
  private def appointment(id: String, pids: String*): String =
    JObject(
      "resourceType" -> JString("Appointment"),
      "id" -> JString(id),
      "status" -> JString("proposed"),
      "participant" -> JArray(pids.map(pid =>
        JObject("actor" -> JObject("reference" -> JString(s"Patient/$pid")), "status" -> JString("accepted"))).toList)
    ).toJson

  // A different Patient record pointing at the purged one, as a duplicate or merged record would.
  private def linkedPatient(id: String, linkedTo: String): String =
    patient(id).parseJson.asInstanceOf[JObject].merge(JObject(
      "link" -> JArray(List(JObject(
        "other" -> JObject("reference" -> JString(s"Patient/$linkedTo")),
        "type" -> JString("seealso")))))).toJson

  sequential

  "FHIR Patient $purge operation" should {

    "set up a patient with current, updated, and deleted resources" in {
      Put(s"$base/Patient/$purgedPatient", HttpEntity(patient(purgedPatient))) ~> fhirRoute ~> check(status === Created)
      // Two versions, so there is history to purge
      Put(s"$base/Observation/purge-op-obs1", HttpEntity(observation("purge-op-obs1", purgedPatient, "preliminary"))) ~> fhirRoute ~> check(status === Created)
      Put(s"$base/Observation/purge-op-obs1", HttpEntity(observation("purge-op-obs1", purgedPatient))) ~> fhirRoute ~> check(status === OK)
      // Soft deleted, so its content lives only in history
      Put(s"$base/Observation/purge-op-obs2", HttpEntity(observation("purge-op-obs2", purgedPatient))) ~> fhirRoute ~> check(status === Created)
      Delete(s"$base/Observation/purge-op-obs2") ~> fhirRoute ~> check(status === NoContent)
      // Another patient's data, which must survive
      Put(s"$base/Patient/$otherPatient", HttpEntity(patient(otherPatient))) ~> fhirRoute ~> check(status === Created)
      Put(s"$base/Observation/purge-op-obs3", HttpEntity(observation("purge-op-obs3", otherPatient))) ~> fhirRoute ~> check(status === Created)
      // Further compartment types, reached through other search parameters
      Put(s"$base/Condition/purge-op-cond1", HttpEntity(condition("purge-op-cond1", purgedPatient))) ~> fhirRoute ~> check(status === Created)
      Put(s"$base/Appointment/purge-op-appt1", HttpEntity(appointment("purge-op-appt1", purgedPatient))) ~> fhirRoute ~> check(status === Created)
      // Shared between the purged and the other patient
      Put(s"$base/Appointment/purge-op-appt2", HttpEntity(appointment("purge-op-appt2", purgedPatient, otherPatient))) ~> fhirRoute ~> check(status === Created)
      Put(s"$base/Patient/$linkedPatientId", HttpEntity(linkedPatient(linkedPatientId, purgedPatient))) ~> fhirRoute ~> check(status === Created)
    }

    // The guard that keeps a purge from turning into a collection wipe: without a query,
    // deleteMany would match every document of the type.
    "refuse to purge without a query, leaving the collection intact" in {
      Await.result(FhirConfigurationManager.resourceManager.purgeResources("Observation", Nil), Duration(10, TimeUnit.SECONDS)) must
        throwA[InternalServerException]
      Get(s"$base/Observation/purge-op-obs1") ~> fhirRoute ~> check(status === OK)
      Get(s"$base/Observation/purge-op-obs3") ~> fhirRoute ~> check(status === OK)
    }

    // The last gate, right before deleteMany, for queries that exist but still match everything
    "refuse to remove documents with a query matching the whole collection" in {
      Seq[Bson](Document(), Filters.and(), Filters.or(Document(), Filters.equal("id", "x")), Filters.and(Filters.and()))
        .foreach(q =>
          Await.result(DocumentManager.removeDocuments("Observation", q), Duration(10, TimeUnit.SECONDS)) must
            throwA[InternalServerException]
        )
      Get(s"$base/Observation/purge-op-obs1") ~> fhirRoute ~> check(status === OK)
      Get(s"$base/Observation/purge-op-obs3") ~> fhirRoute ~> check(status === OK)
    }

    // Every compartment query the purge will run is restricted to the patient, across the whole R4 compartment
    "build a patient restricted query for every purged resource type" in {
      val relations = FhirConfigurationManager.fhirConfig.compartmentRelations("Patient")
      val rtypes = relations.keySet
        .diff(PatientPurgeOperationHandler.EXCLUDED_RESOURCES + "Patient")
        .intersect(FhirConfigurationManager.fhirConfig.resourceConfigurations.keySet)
      rtypes must not(beEmpty)
      foreach(rtypes.toSeq.sorted) { rtype =>
        val param = FhirConfigurationManager.fhirSearchParameterValueParser.constructCompartmentSearchParameter("Patient", purgedPatient, rtype)
        val (query, stages) = FhirConfigurationManager.resourceManager.constructQueryNew(rtype, List(param))
        stages must beEmpty
        val rendered = query.get.toBsonDocument(classOf[BsonDocument], MongoClientSettings.getDefaultCodecRegistry)
        DocumentManager.matchesAllDocuments(rendered) must beFalse
        rendered.toJson must contain(purgedPatient)
      }
    }

    "purge the patient and return an OperationOutcome" in {
      Post(s"$base/Patient/$purgedPatient/" + "$purge") ~> fhirRoute ~> check {
        status === OK
        (responseAs[Resource] \ "resourceType").extractOpt[String] must beSome("OperationOutcome")
      }
    }

    "leave no current version of the patient or its compartment" in {
      Get(s"$base/Patient/$purgedPatient") ~> fhirRoute ~> check(status === NotFound)
      Get(s"$base/Observation/purge-op-obs1") ~> fhirRoute ~> check(status === NotFound)
      Get(s"$base/Observation/purge-op-obs2") ~> fhirRoute ~> check(status === NotFound)
    }

    // A FHIR delete answers 410 Gone and keeps the versions; after a purge there is nothing to find.
    "leave no history of the patient or its compartment" in {
      Get(s"$base/Patient/$purgedPatient/_history") ~> fhirRoute ~> check(status === NotFound)
      Get(s"$base/Observation/purge-op-obs1/_history") ~> fhirRoute ~> check(status === NotFound)
      Get(s"$base/Observation/purge-op-obs2/_history") ~> fhirRoute ~> check(status === NotFound)
      Get(s"$base/Observation/purge-op-obs1/_history/1") ~> fhirRoute ~> check(status === NotFound)
    }

    "purge every compartment type, whatever parameter links it to the patient" in {
      Get(s"$base/Condition/purge-op-cond1") ~> fhirRoute ~> check(status === NotFound)
      Get(s"$base/Appointment/purge-op-appt1") ~> fhirRoute ~> check(status === NotFound)
    }

    // Documents a consequence of the spec rather than a choice of ours: a resource in two patients'
    // compartments belongs to both, so purging one removes it for the other as well.
    "purge a resource shared with another patient" in {
      Get(s"$base/Appointment/purge-op-appt2") ~> fhirRoute ~> check(status === NotFound)
      Get(s"$base/Appointment/purge-op-appt2/_history") ~> fhirRoute ~> check(status === NotFound)
    }

    "keep the data of other patients" in {
      Get(s"$base/Patient/$otherPatient") ~> fhirRoute ~> check(status === OK)
      Get(s"$base/Observation/purge-op-obs3") ~> fhirRoute ~> check(status === OK)
    }

    // The Patient is purged by id only: compartment membership through Patient.link would also
    // remove a different Patient record that merely points at this one.
    "keep a different Patient record that links to the purged one" in {
      Get(s"$base/Patient/$linkedPatientId") ~> fhirRoute ~> check(status === OK)
    }

    "report 404 for a patient that does not exist" in {
      Post(s"$base/Patient/$purgedPatient/" + "$purge") ~> fhirRoute ~> check {
        status === NotFound
        (responseAs[Resource] \ "resourceType").extractOpt[String] must beSome("OperationOutcome")
      }
    }

    // An erasure request may arrive after the patient was deleted the FHIR way; its history must still go.
    "purge a patient that was already deleted" in {
      Put(s"$base/Patient/$deletedPatient", HttpEntity(patient(deletedPatient))) ~> fhirRoute ~> check(status === Created)
      Put(s"$base/Observation/purge-op-obs4", HttpEntity(observation("purge-op-obs4", deletedPatient))) ~> fhirRoute ~> check(status === Created)
      Delete(s"$base/Patient/$deletedPatient") ~> fhirRoute ~> check(status === NoContent)

      Post(s"$base/Patient/$deletedPatient/" + "$purge") ~> fhirRoute ~> check(status === OK)

      Get(s"$base/Patient/$deletedPatient/_history") ~> fhirRoute ~> check(status === NotFound)
      Get(s"$base/Observation/purge-op-obs4") ~> fhirRoute ~> check(status === NotFound)
    }
  }
}
