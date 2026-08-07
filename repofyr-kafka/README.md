# repofyr-kafka

`repofyr-kafka` publishes Repofyr server events to Apache Kafka. It contains
one Akka actor that subscribes to the server event bus and writes each event to
a Kafka topic, and the configuration object that reads its settings from the
server's `application.conf`.

Maven coordinate: `io.repofyr:repofyr-kafka_2.13`. `repofyr-core` depends on
it, so every server deployment already has it on the classpath; the producer
actor is only created when configuration asks for it.

## What is in it

| Type | Purpose |
| --- | --- |
| `io.repofyr.event.kafka.KafkaEventProducer` | Akka actor that receives `FhirDataEvent` messages and writes them to Kafka |
| `KafkaEventProducer.props` | Actor `Props` factory; `KafkaEventProducer.ACTOR_NAME` is `kafka-event-producer` |
| `io.repofyr.event.kafka.KafkaConfig` | Typesafe Config view of the `kafka.*` settings |

## Configuration

`KafkaConfig` wraps a `com.typesafe.config.Config` and reads these keys, each
with a fallback:

| Key | Default | Meaning |
| --- | --- | --- |
| `kafka.enabled` | `false` | master switch for event publishing |
| `kafka.bootstrap-servers` | `["localhost:9092"]` | broker list |
| `kafka.client.id` | `onfhir` | Kafka `client.id` |
| `kafka.fhir-topic` | `fhir` | topic for resource create/update/delete events |
| `kafka.fhir-subscription-topic` | `onfhir.subscription` | topic for parsed FHIR Subscription resources |
| `kafka.enabled-resources` | unset | if given, only these resource types are published |

`isKafkaEnabled(rtype)` combines the switch and the allow list. Key names and
default topic names keep the legacy `onfhir` spelling on purpose: they are
runtime and wire configuration, not code identity, and existing deployments
must keep working across the 4.0.0 rename.

## How it is wired

`io.repofyr.Onfhir` creates the actor when either `kafka.enabled` or
`fhir.subscription.active` is true, then subscribes it to the server event bus
for the union of the Kafka-enabled resource types and the subscription-allowed
resource types:

```scala
val actorRef = actorSystem.actorOf(
  KafkaEventProducer.props(
    kafkaConfig,
    OnfhirConfig.fhirSubscriptionActive,
    FhirConfigurationManager.subscriptionUtil.parseFhirSubscription),
  KafkaEventProducer.ACTOR_NAME)

FhirConfigurationManager.eventManager.subscribe(
  actorRef, FhirEventSubscription(classOf[FhirDataEvent], resourcesToSendToKafka))
```

Records are keyed by `FhirDataEvent.getTopicKey()`, which is `rtype:rid`, and
valued by `InternalJsonMarshallers.serializeToJson`. Producer `acks` is `0`,
and a send failure is logged rather than propagated, so a broker outage does
not fail the FHIR interaction that produced the event.

Subscription resources take a second path. When `fhirSubscriptionActive` is
true, an event on the `Subscription` resource type is also written to
`kafka.fhir-subscription-topic`, keyed by resource id: create and update
publish the parsed `FhirSubscription`, and delete publishes an empty value as a
tombstone. Parsing is release-specific, so the module does not do it itself -
the parser function is injected as a constructor argument by `repofyr-core`,
which takes it from the active `SubscriptionUtil`.

## Scope boundary

This module produces; it does not consume. There is no Kafka consumer, no
subscription delivery channel, and no retry or outbox machinery. It also does
not decide which resource types are eligible - `repofyr-core` computes that set
and expresses it as an event bus subscription.

## Tests

| Suite | What it covers |
| --- | --- |
| `KafkaEventProducerTest` | topic routing for Subscription and ordinary resources, record keys, and the injected Subscription parser |

The suite drives the actor through an Akka `TestActorRef` and captures the
records that would be sent, so it needs no broker.

```shell
mvn -pl repofyr-kafka -am test
```
