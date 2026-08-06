package io.repofyr.event.kafka

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestActorRef, TestKit}
import com.typesafe.config.ConfigFactory
import io.onfhir.api.model.{FhirSubscription, FhirSubscriptionChannel}
import io.repofyr.event.{FhirDataEvent, ResourceCreated}
import org.json4s.JObject
import org.junit.runner.RunWith
import org.specs2.specification.AfterAll
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import scala.collection.mutable.ArrayBuffer

@RunWith(classOf[JUnitRunner])
class KafkaEventProducerTest extends Specification with AfterAll {
  sequential

  implicit private val actorSystem: ActorSystem = ActorSystem("kafka-event-producer-test")

  override def afterAll(): Unit = TestKit.shutdownActorSystem(actorSystem)

  "KafkaEventProducer" should {
    "route Subscription events to the ordinary FHIR event topic when subscriptions are inactive" in {
      val producer = createProducer(fhirSubscriptionActive = false)
      val event = ResourceCreated("Subscription", "subscription-1", JObject())

      producer ! event
      val recordingProducer = producer.underlyingActor

      recordingProducer.subscriptionEvents must beEmpty
      recordingProducer.sentEvents.map(event => event.topic -> event.key) mustEqual
        Seq("fhir-events" -> "Subscription:subscription-1")
    }

    "route non-Subscription events to the ordinary FHIR event topic" in {
      val producer = createProducer(fhirSubscriptionActive = false)
      val event = ResourceCreated("Patient", "patient-1", JObject())

      producer ! event
      val recordingProducer = producer.underlyingActor

      recordingProducer.subscriptionEvents must beEmpty
      recordingProducer.sentEvents.map(event => event.topic -> event.key) mustEqual
        Seq("fhir-events" -> "Patient:patient-1")
    }

    "route Subscription events to the subscription topic path when subscriptions are active" in {
      val producer = createProducer(fhirSubscriptionActive = true)
      val event = ResourceCreated("Subscription", "subscription-1", JObject())

      producer ! event
      val recordingProducer = producer.underlyingActor

      recordingProducer.subscriptionEvents mustEqual Seq(event)
      recordingProducer.sentEvents must beEmpty
    }

    "use the release-specific parser injected by core" in {
      val parsedResources = ArrayBuffer.empty[JObject]
      val sentEvents = ArrayBuffer.empty[SentEvent]
      val producer = TestActorRef[KafkaEventProducer](Props(new KafkaEventProducer(
        kafkaConfig,
        fhirSubscriptionActive = true,
        parseFhirSubscription = resource => {
          parsedResources += resource
          subscription
        }
      ) {
        override def sendString(topic: String, key: String, value: String): Unit =
          sentEvents += SentEvent(topic, key, value)
      }))
      val resource = JObject()

      producer ! ResourceCreated("Subscription", "subscription-1", resource)

      parsedResources mustEqual Seq(resource)
      sentEvents.map(event => event.topic -> event.key) mustEqual
        Seq("fhir-subscriptions" -> "subscription-1")
    }
  }

  private def createProducer(
    fhirSubscriptionActive: Boolean
  ): TestActorRef[RecordingKafkaEventProducer] =
    TestActorRef[RecordingKafkaEventProducer](
      Props(new RecordingKafkaEventProducer(fhirSubscriptionActive))
    )

  private case class SentEvent(topic: String, key: String, value: String)

  private def kafkaConfig = new KafkaConfig(ConfigFactory.parseString(
    """
      |kafka.fhir-topic = "fhir-events"
      |kafka.fhir-subscription-topic = "fhir-subscriptions"
      |""".stripMargin
  ))

  private def subscription = FhirSubscription(
    "subscription-1",
    "Observation",
    FhirSubscriptionChannel("rest-hook", None, None),
    status = "requested",
    expiration = None
  )

  private class RecordingKafkaEventProducer(fhirSubscriptionActive: Boolean) extends KafkaEventProducer(
    kafkaConfig,
    fhirSubscriptionActive,
    _ => subscription
  ) {
    var sentEvents: Seq[SentEvent] = Seq.empty
    var subscriptionEvents: Seq[FhirDataEvent] = Seq.empty

    override def sendString(topic: String, key: String, value: String): Unit =
      sentEvents = sentEvents :+ SentEvent(topic, key, value)

    override def handleSubscription(event: FhirDataEvent): Unit =
      subscriptionEvents = subscriptionEvents :+ event
  }
}
