# repofyr-event

`repofyr-event` holds the contracts the Repofyr server publishes resource
activity through: the event model, the subscription classifier that selects
which events a subscriber receives, the event bus SPI, and the json4s
marshalling that lets those events cross a process boundary.

It is a contract module only. It contains no bus implementation, no
persistence, and no HTTP surface. The concrete bus is
`io.repofyr.event.FhirEventBus` in `repofyr-core`, and the only consumer
shipped in this reactor is `repofyr-kafka`.

Maven coordinate: `io.repofyr:repofyr-event_2.13`. Both `repofyr-core` and
`repofyr-kafka` depend on it, so a server deployment receives it transitively;
depend on it directly only when your own code produces or consumes these
events.

## What is in it

| Area | Principal APIs |
| --- | --- |
| Event model | `IFhirEvent`, `FhirNamedEvent`, `FhirPatternEvent`, `FhirTimeEvent` |
| Data events | `FhirDataEvent` and its cases `ResourceCreated`, `ResourceUpdated`, `ResourceDeleted`, `ResourceAccessed` |
| Subscription classifier | `FhirEventSubscription` |
| Bus SPI | `IFhirEventBus` |
| Helpers | `FhirEventUtil`, `io.repofyr.util.InternalJsonMarshallers` |

Everything except `InternalJsonMarshallers` lives in `io.repofyr.event`; the
marshallers sit in `io.repofyr.util` because they also serialize the neutral
`InternalEntity` types that `onfhir-common` defines.

## Events

`IFhirEvent` extends `io.onfhir.api.model.InternalEntity` and exposes two
accessors: `getContent` returns the JSON payload and `getContextParams`
returns named context values. `FhirDataEvent` narrows that to a resource
instance by adding `rtype` and `rid`, and names the event through `getEvent`:

| Case | `getEvent` | Content | Context |
| --- | --- | --- | --- |
| `ResourceCreated` | `data-added` | the created resource | none |
| `ResourceUpdated` | `data-modified` | the updated resource | `previous` |
| `ResourceDeleted` | `data-removed` | none | `previous` |
| `ResourceAccessed` | `data-accessed` | the accessed resource | none |

`FhirDataEvent.isRelated` matches an event against a trigger name and widens
the two aggregate triggers from `io.onfhir.api.model.FhirTriggerEvents`:
`RESOURCE_CHANGED` matches create, update, and delete; `RESOURCE_NEW_CONTENT`
matches create and update. `getTopicKey()` renders the `rtype:rid` key that
`repofyr-kafka` uses as its record key.

## Subscribing

`IFhirEventBus` is an Akka `EventBus` with `ScanningClassification`, fixed to
`FhirDataEvent` events, `FhirEventSubscription` classifiers, and `ActorRef`
subscribers. A subscription narrows by event class, resource type, resource
id, and a parsed FHIR query, each independently optional:

```scala
import akka.actor.ActorRef
import io.repofyr.event.{FhirDataEvent, FhirEventSubscription, IFhirEventBus}

val bus: IFhirEventBus = ???
val subscriber: ActorRef = ???

// Every data event on Observation and Condition
bus.subscribe(subscriber, FhirEventSubscription(
  classOf[FhirDataEvent],
  rtype = Some(Seq("Observation", "Condition"))))
```

`None` means "do not narrow on this dimension", so the default
`FhirEventSubscription()` receives every data event. Evaluating the `query`
dimension needs server configuration, which is why matching is implemented by
`FhirEventBus` in `repofyr-core` rather than here.

## Serializing

`InternalJsonMarshallers` supplies the json4s `Formats` used for internal
traffic, plus Akka HTTP marshallers and unmarshallers for `Seq[InternalEntity]`
and an `Instant` serializer that reads and writes FHIR `instant`/`dateTime`
strings:

```scala
import io.repofyr.event.ResourceCreated
import io.repofyr.util.InternalJsonMarshallers

val json = InternalJsonMarshallers.serializeToJson(
  ResourceCreated("Patient", "p1", patient))
```

The formats register short type hints for the concrete event and subscription
classes, so a consumer can discriminate the case it received. The hinted set is
a wire contract: adding or renaming a hinted class changes what existing
consumers can parse.

## Scope boundary

Producing events, matching them against subscriptions, and delivering them are
all outside this module. Keep this module to types both a producer and a
consumer must agree on.
