package io.repofyr.event

import io.onfhir.api.Resource
import org.json4s.{JObject, JString}
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

/**
 * Tests for the Kafka message key format.
 *
 * `KafkaEventProducer` uses `FhirDataEvent.getTopicKey()` as the record key, which makes the
 * `<rtype>:<rid>` encoding a partitioning contract: consumers parse it back to route events,
 * and Kafka derives the partition from it, so changing the shape would both break parsing and
 * scatter a resource's history across partitions.
 */
@RunWith(classOf[JUnitRunner])
class FhirEventUtilTest extends Specification {

  private val patient: Resource =
    JObject("resourceType" -> JString("Patient"), "id" -> JString("p1"))

  "A topic key" should {

    "round trip a resource type and id" in {
      val key = FhirEventUtil.getTopicKey("Patient", "p1")
      key mustEqual "Patient:p1"

      val (rtype, rid) = FhirEventUtil.parseTopicKey(key)
      rtype mustEqual "Patient"
      rid mustEqual "p1"
    }

    // The FHIR id grammar is [A-Za-z0-9-.]{1,64}, so a real id contains dots and dashes but
    // never a colon. That is what makes the single-colon split safe, and this pins the two
    // non-alphanumeric characters an id is actually allowed to carry.
    "round trip an id containing the dots and dashes the FHIR id grammar allows" in {
      val (rtype, rid) = FhirEventUtil.parseTopicKey(FhirEventUtil.getTopicKey("Observation", "obs-1.2"))

      rtype mustEqual "Observation"
      rid mustEqual "obs-1.2"
    }

    // Characterization, not an endorsement: parseTopicKey takes the first and last split
    // segments, so an id containing a colon comes back truncated. Unreachable for FHIR ids,
    // but if the key format is ever widened to non-FHIR identifiers this is what breaks first.
    "lose the middle of an id that contains a colon, which no valid FHIR id does" in {
      val (rtype, rid) = FhirEventUtil.parseTopicKey(FhirEventUtil.getTopicKey("Patient", "urn:uuid:1"))

      rtype mustEqual "Patient"
      rid mustEqual "1"
    }

    "be derived from the event itself, which is what the producer sends as the record key" in {
      ResourceCreated("Patient", "p1", patient).getTopicKey() mustEqual "Patient:p1"
      ResourceDeleted("Patient", "p1", patient).getTopicKey() mustEqual "Patient:p1"
    }
  }

  "A data event" should {

    // Trigger definitions name these events as configuration strings, so the mapping from
    // event class to event name is read by deployments, not just by code.
    "answer isRelated for the umbrella trigger events it belongs to" in {
      val created = ResourceCreated("Patient", "p1", patient)
      val deleted = ResourceDeleted("Patient", "p1", patient)

      created.isRelated("data-changed") must beTrue
      created.isRelated("data-upserted") must beTrue
      created.isRelated("data-added") must beTrue
      created.isRelated("data-removed") must beFalse

      deleted.isRelated("data-changed") must beTrue
      // A delete leaves no new content behind, so it must not satisfy an upsert trigger.
      deleted.isRelated("data-upserted") must beFalse
    }
  }
}
