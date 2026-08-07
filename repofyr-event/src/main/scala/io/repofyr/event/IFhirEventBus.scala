package io.repofyr.event

import akka.actor.ActorRef
import akka.event.{EventBus, ScanningClassification}

/**
 * SPI for the event bus that Repofyr publishes FHIR resource activity through.
 *
 * It fixes the three abstract members of Akka's `EventBus` to the server's
 * event contract: events are [[FhirDataEvent]] values, classifiers are
 * [[FhirEventSubscription]] values, and subscribers are actors. With
 * `ScanningClassification`, every subscription is tested against every
 * published event, so a subscription may narrow on any combination of event
 * class, resource type, resource id, and FHIR query rather than on a single
 * lookup key.
 *
 * An implementation supplies the three members `ScanningClassification`
 * leaves abstract: `compareClassifiers`, `compareSubscribers`, and `matches`,
 * plus `publish` for delivery. Evaluating the query dimension of a
 * subscription needs the server's search parameter configuration, which is why
 * this module declares the SPI but does not implement it; the implementation
 * is `io.repofyr.event.FhirEventBus` in `repofyr-core`.
 *
 * Publishing is fire-and-forget from the producer's point of view. A subscriber
 * that fails or falls behind must not be able to fail the FHIR interaction that
 * produced the event.
 */
trait IFhirEventBus  extends EventBus with ScanningClassification {
  override type Event = FhirDataEvent
  override type Classifier = FhirEventSubscription
  override type Subscriber = ActorRef
}
