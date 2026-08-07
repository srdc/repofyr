package io.repofyr.event

import java.time.Instant
import java.time.temporal.ChronoUnit

import io.onfhir.api.Resource
import io.onfhir.api.model.FhirTriggerEvents
import io.repofyr.util.InternalJsonMarshallers
import org.json4s.{JBool, JNothing, JObject, JString}
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

/**
 * Round-trip tests for the event wire format.
 *
 * `KafkaEventProducer` publishes every FHIR data event as
 * `InternalJsonMarshallers.serializeToJson(event)` onto the `onfhir.subscription` topic, so
 * what that object emits is a published contract with independently deployed consumers rather
 * than an implementation detail. Nothing inside the server ever reads its own events back,
 * which means a regression here would only ever surface as a third party silently failing to
 * deserialize - hence this suite.
 */
@RunWith(classOf[JUnitRunner])
class EventSerializationTest extends Specification {

  private val patient: Resource =
    JObject("resourceType" -> JString("Patient"), "id" -> JString("p1"), "active" -> JBool(true))

  private val previousPatient: Resource =
    JObject("resourceType" -> JString("Patient"), "id" -> JString("p1"), "active" -> JBool(false))

  /** Serialize exactly the way `KafkaEventProducer` does, then read the payload back. */
  private def roundTrip[T <: IFhirEvent](event: T)(implicit manifest: Manifest[T]): T =
    InternalJsonMarshallers.parseAndExtract[T](InternalJsonMarshallers.serializeToJson(event))

  "The json4s type hint" should {

    // This is the load-bearing property of the whole module. Repofyr 4.0.0 renamed the server
    // packages from io.onfhir.* to io.repofyr.*, and a fully qualified type hint would have
    // baked that rename into every Kafka payload - forcing producers and consumers to be
    // upgraded together. ShortTypeHints emits only the simple class name, so a 3.x consumer
    // reads 4.0.0 events unchanged and the two sides can move independently. Assert against the
    // raw string: a parsed-and-compared hint would still pass if json4s started emitting the
    // package, and the whole point is what goes on the wire.
    "be the simple class name, never a package qualified one" in {
      val hinted = Seq(
        ResourceCreated("Patient", "p1", patient) -> "ResourceCreated",
        ResourceUpdated("Patient", "p1", patient, previousPatient) -> "ResourceUpdated",
        ResourceDeleted("Patient", "p1", previousPatient) -> "ResourceDeleted",
        ResourceAccessed("Patient", "p1", patient) -> "ResourceAccessed",
        FhirNamedEvent("admission", ResourceCreated("Patient", "p1", patient)) -> "FhirNamedEvent",
        FhirTimeEvent(Instant.parse("2026-08-07T09:15:30.123Z")) -> "FhirTimeEvent"
      )

      forall(hinted) { case (event, simpleName) =>
        val json = InternalJsonMarshallers.serializeToJson(event)

        // "jsonClass" is json4s' default hint field name, and it is spelled out rather than
        // read back from `formats` because a json4s upgrade that changed the default would
        // itself be a wire break that this test has to catch.
        json must contain(s""""jsonClass":"$simpleName"""")
        json must not(contain("io.repofyr"))
        json must not(contain("io.onfhir"))
      }
    }
  }

  "A ResourceCreated event" should {

    "survive a serialize and parse round trip" in {
      val event = ResourceCreated("Patient", "p1", patient)
      val parsed = roundTrip(event)

      parsed.rtype mustEqual "Patient"
      parsed.rid mustEqual "p1"
      parsed.resource mustEqual patient
      parsed mustEqual event
    }

    "expose the created resource as its content and no context parameters" in {
      val event = ResourceCreated("Patient", "p1", patient)

      event.getContent mustEqual patient
      event.getContextParams must beEmpty
      event.getEvent mustEqual FhirTriggerEvents.RESOURCE_CREATED
    }
  }

  "A ResourceUpdated event" should {

    // Both resource versions have to make the trip. A consumer evaluating a FHIR subscription
    // criterion needs the previous content to tell "still matching" from "newly matching".
    "carry both the new and the previous resource content" in {
      val event = ResourceUpdated("Patient", "p1", patient, previousPatient)
      val parsed = roundTrip(event)

      parsed.resource mustEqual patient
      parsed.previous mustEqual previousPatient
      parsed mustEqual event
    }

    "expose the previous content as the `previous` context parameter" in {
      val parsed = roundTrip(ResourceUpdated("Patient", "p1", patient, previousPatient))

      parsed.getContent mustEqual patient
      parsed.getContextParams mustEqual Map("previous" -> previousPatient)
      parsed.getEvent mustEqual FhirTriggerEvents.RESOURCE_UPDATED
    }
  }

  "A ResourceDeleted event" should {

    // A delete has no current content by definition, so `previous` is the only payload a
    // consumer gets and losing it would leave the event unusable.
    "carry the previous content and report no content of its own" in {
      val event = ResourceDeleted("Patient", "p1", previousPatient)
      val parsed = roundTrip(event)

      parsed.previous mustEqual previousPatient
      parsed.getContent mustEqual JNothing
      parsed.getContextParams mustEqual Map("previous" -> previousPatient)
      parsed.getEvent mustEqual FhirTriggerEvents.RESOURCE_DELETED
      parsed mustEqual event
    }
  }

  "A ResourceAccessed event" should {

    "survive a serialize and parse round trip" in {
      val event = ResourceAccessed("Patient", "p1", patient)
      val parsed = roundTrip(event)

      parsed.resource mustEqual patient
      parsed.getContent mustEqual patient
      parsed.getEvent mustEqual FhirTriggerEvents.RESOURCE_ACCESSED
      parsed mustEqual event
    }
  }

  "A FhirNamedEvent" should {

    // The wrapped event is typed as the `IFhirEvent` trait, so this only survives the trip
    // because the nested value carries its own type hint. It is the one place in the model
    // where the type hint is load bearing for correctness rather than just for compatibility.
    "restore the wrapped event to its concrete type" in {
      val wrapped = ResourceUpdated("Patient", "p1", patient, previousPatient)
      val parsed = roundTrip(FhirNamedEvent("admission", wrapped))

      parsed.eventName mustEqual "admission"
      parsed.event must beAnInstanceOf[ResourceUpdated]
      parsed.event mustEqual wrapped
    }

    "add eventName to the wrapped event's context parameters rather than replacing them" in {
      val parsed = roundTrip(FhirNamedEvent("admission", ResourceUpdated("Patient", "p1", patient, previousPatient)))

      parsed.getContent mustEqual patient
      parsed.getContextParams mustEqual Map(
        "previous" -> previousPatient,
        "eventName" -> JString("admission")
      )
    }
  }

  "A FhirTimeEvent" should {

    // Instant has no json4s mapping of its own; it only survives because
    // InternalJsonMarshallers registers FhirDateTimeSerializer. Dropping that serializer would
    // not fail compilation, it would fail at runtime on the first scheduled trigger.
    "carry its scheduled instant through the custom Instant serializer" in {
      val scheduled = Instant.parse("2026-08-07T09:15:30.123Z")
      val event = FhirTimeEvent(scheduled, Map("tenant" -> "acme"))

      InternalJsonMarshallers.serializeToJson(event) must contain("2026-08-07T09:15:30.123Z")

      val parsed = roundTrip(event)
      parsed.scheduledTime mustEqual scheduled
      parsed.context mustEqual Map("tenant" -> "acme")
    }

    // The FHIR instant format stops at milliseconds, so anything finer is lost on the wire.
    // Pinned rather than fixed: consumers already parse a millisecond timestamp, and widening
    // it would be the wire change, not the current truncation.
    "round the scheduled instant to millisecond precision" in {
      val nanos = Instant.parse("2026-08-07T09:15:30.123456789Z")

      roundTrip(FhirTimeEvent(nanos)).scheduledTime mustEqual nanos.truncatedTo(ChronoUnit.MILLIS)
    }

    "expose the scheduled time and the attached context as context parameters" in {
      val scheduled = Instant.parse("2026-08-07T09:15:30.123Z")
      val parsed = roundTrip(FhirTimeEvent(scheduled, Map("tenant" -> "acme")))

      parsed.getContent mustEqual JNothing
      parsed.getContextParams mustEqual Map(
        "eventScheduledTime" -> JString("2026-08-07T09:15:30.123Z"),
        "tenant" -> JString("acme")
      )
    }
  }

  "A FhirPatternEvent" should {

    // It was the one IFhirEvent missing from the marshaller type hints, so it serialized without
    // a hint and could not be extracted back. Nothing publishes it in this reactor today, which
    // is why it went unnoticed - registering it makes the whole event hierarchy round-trippable
    // rather than all-but-one.
    "round trip, including the events nested inside each matched pattern" in {
      val created = ResourceCreated("Patient", "p1", patient)
      val accessed = ResourceAccessed("Patient", "p1", patient)
      val event = FhirPatternEvent(Map("admission-then-read" -> Seq(created, accessed)))

      val parsed = roundTrip(event)

      parsed.matchedPatterns.keySet mustEqual Set("admission-then-read")
      parsed.matchedPatterns("admission-then-read") mustEqual Seq(created, accessed)
    }

    "carry a simple class name hint like every other event" in {
      val json = InternalJsonMarshallers.serializeToJson(FhirPatternEvent(Map.empty))

      json must contain("\"jsonClass\":\"FhirPatternEvent\"")
      json must not(contain("io.repofyr"))
      json must not(contain("io.onfhir"))
    }
  }
}
