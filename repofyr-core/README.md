# repofyr-core

`repofyr-core` is the Repofyr server runtime. It owns everything between an
inbound HTTP request and a MongoDB document: the Akka HTTP routes for the FHIR
REST API, the per-interaction services, the persistence layer, authentication
and authorization, auditing, the event bus implementation, and the
configuration machinery that assembles all of it at startup.

It is FHIR-release neutral. Nothing here knows whether it is serving R4, R5, or
STU3; a `repofyr-server-*` module supplies that through an
`IFhirServerConfigurator`. It is not, however, runnable on its own - it has no
`main`, no standard definition package, and no operation implementations.

Maven coordinate: `io.repofyr:repofyr-core_2.13`. Every `repofyr-server-*`
module depends on it, so a deployment normally gets it transitively and depends
on it directly only when writing its own `Boot`, operation handlers, or
extension modules.

## What is in it

| Package | Principal APIs |
| --- | --- |
| `io.repofyr` | `Onfhir`, the server instance and its companion factory |
| `io.repofyr.config` | `IFhirServerConfigurator`, `BaseFhirServerConfigurator`, `IFhirConfigurationManager`, `FhirConfigurationManager`, `OnfhirConfig`, `AuditConfig`, `AuthzConfig`, `IndexConfigurator`, `SSLConfig` |
| `io.repofyr.api.endpoint` | `FHIREndpoint` and the per-interaction route traits, `SecurityEndpoint`, `OnFhirInternalEndpoint` |
| `io.repofyr.api.service` | `FHIRInteractionService` and its implementations, `FHIRServiceFactory`, `FHIROperationHandlerService`, `IFHIRPatchHandler` with `JsonPatchHandler` and `FhirPathPatchHandler`, `TargetResourceResolver` |
| `io.repofyr.api.model` | `AkkaHttpModelAdapter`, `FHIRMarshallers`, `JsonToXmlConvertor`, `XmlToJsonConvertor` |
| `io.repofyr.api.validation` | `FHIRResourceValidator`, `FHIRApiValidator`, `IResourceSpecificValidator`, `ReferenceResolver` |
| `io.repofyr.api.util` | `FHIRServerUtil`, `ResourceChecker`, `SubscriptionUtil`, `UnsupportedSubscriptionUtil` |
| `io.repofyr.api.client` | `OnFhirLocalClient`, `OnFhirBulkRequestBuilder` |
| `io.repofyr.db` | `ResourceManager`, `DocumentManager`, `MongoDB`, `TransactionSession`, `IFhirQueryBuilder` and the per-type query builders, `IDBInitializer`, `BaseDBInitializer`, `MongoDBInitializer`, `EmbeddedMongo`, `AggregationHandler` |
| `io.repofyr.authz` | `IAuthorizer`, `ITokenResolver`, `ICustomAuditHandler`, `AuthManager`, `AuthzManager`, `AuthzConfigurationManager`, `SmartAuthorizer`, `BasicAuthorizer`, `JWTResolver`, `ResolverWithTokenIntrospection` |
| `io.repofyr.audit` | `IFhirAuditCreator`, `AuditManager`, `RequestLogManager`, `AgentsInfo` |
| `io.repofyr.operation` | `IFhirOperationLibrary`, `FhirOperationHandlerFactory`, `DefaultOperationHandlers` |
| `io.repofyr.event` | `FhirEventBus`, the `IFhirEventBus` implementation |
| `io.repofyr.exception` | the exception types the error handler maps to FHIR OperationOutcome responses |
| `io.repofyr.server` | `ErrorHandler`, `FHIRRejectionHandler`, `CORSHandler`, `AuthorizationErrorResponseBuilder` |

## Starting a server

`Onfhir` is the entry point. Constructing it initializes the platform -
`FhirConfigurationManager.initialize` reads the FHIR foundation resources,
builds the `FhirServerConfig`, wires the operation libraries, and creates the
authorization, audit, event, and persistence managers - and `start` binds the
HTTP route:

```scala
import io.repofyr.Onfhir
import io.repofyr.r4.config.FhirR4Configurator

object Boot extends App {
  Onfhir.apply(new FhirR4Configurator()).start
}
```

The factory takes the customization points as optional arguments:

```scala
Onfhir.apply(
  fhirConfigurator = new FhirR4Configurator(),
  fhirOperationLibraries = Seq(new MyOperationLibrary()),
  customAuthorizer = Some(new MyAuthorizer()),
  customTokenResolver = Some(new MyTokenResolver()),
  customAuditHandler = Some(new MyAuditHandler()),
  externalRoutes = Seq(myRoute),
  cdsRoute = None)
```

`Onfhir` is a singleton: the second `apply` returns the first instance and
ignores its arguments. `FhirConfigurationManager` and
`AuthzConfigurationManager` are likewise objects, initialized once at startup.

## Extension points

These are the interfaces a downstream application is expected to implement.
Everything else in the module is machinery.

| Interface | Responsibility |
| --- | --- |
| `io.repofyr.config.IFhirServerConfigurator` | Binds the server to a FHIR release: parses foundation resources into a `FhirServerConfig`, sets up the database, and supplies the release's audit creator and Subscription strategy. Extend `BaseFhirServerConfigurator` rather than implementing it directly |
| `io.repofyr.operation.IFhirOperationLibrary` | Declares a set of operation URLs and returns a handler for each. Handlers extend `FHIROperationHandlerService` |
| `io.repofyr.authz.IAuthorizer` | Turns an `AuthzContext` and a `FHIRRequest` into an `AuthzResult`. `SmartAuthorizer` is the built-in SMART on FHIR implementation; `BaseAuthorizer` adds constraint-rule support |
| `io.repofyr.authz.ITokenResolver` | Resolves an access token into an `AuthzContext`. `JWTResolver` and `ResolverWithTokenIntrospection` are the built-in implementations |
| `io.repofyr.authz.ICustomAuditHandler` | Replaces the built-in audit repository with a custom sink |
| `io.repofyr.audit.IFhirAuditCreator` | Builds AuditEvent resources in a release's shape; supplied by the configurator, not registered separately |
| `io.repofyr.api.util.SubscriptionUtil` | Release-specific Subscription parsing, criteria validation, and change policy; supplied by the configurator |
| `io.repofyr.api.validation.IResourceSpecificValidator` | Business validation for a particular resource type, beyond profile validation |
| `io.repofyr.api.service.IFHIRPatchHandler` | A patch format. `JsonPatchHandler` and `FhirPathPatchHandler` implement JSON Patch and FHIRPath Patch |
| `io.repofyr.db.IDBInitializer` | Database preparation. `BaseDBInitializer` and `MongoDBInitializer` implement it for MongoDB |
| External Akka routes | Not an interface: `(FHIRRequest, (AuthContext, Option[AuthzContext])) => Route` functions passed to `Onfhir.apply`, merged into the FHIR route so they share its authentication and marshalling |

## The request pipeline

`FHIREndpoint.fhirRoute` is the composed route. Under
`pathPrefix(OnfhirConfig.baseUri)` it applies, in order:

1. CORS handling (`CORSHandler`).
2. Content negotiation - `FHIRServerUtil.resolveResponseMediaRange` reconciles
   the `_format` parameter, the `Content-Type`, and the `Accept` header,
   answering `406 Not Acceptable` when nothing matches, and rewrites `Accept`
   so downstream marshalling agrees with the decision.
3. Construction of the neutral `FHIRRequest`, carrying the request URI, the
   `X-Forwarded-For`, `X-Forwarded-Host`, and `X-Intermediary` headers, and the
   `X-Request-Id` as the request identifier.
4. `AuthManager.authenticate()`, producing an `AuthContext` and an optional
   `AuthzContext`.
5. `RequestLogManager.logRequest` and `AuditManager.audit`, both of which
   observe the completed result.
6. `ErrorHandler.fhirErrorHandler` and `FHIRRejectionHandler`, which turn
   exceptions and rejections into FHIR OperationOutcome responses.
7. The per-interaction routes, merged in a fixed order, then any external
   routes, then the security route when authorization is configured.

Each route fills in the `FHIRRequest` and hands it to a service.
`FHIRServiceFactory.getFHIRService` selects the `FHIRInteractionService` for
the interaction, and `executeInteraction` runs authorization, validation, and
the interaction itself. Batch and transaction requests fan back through the
same factory for each entry, with a `TransactionSession` threaded through when
MongoDB transactions are enabled.

`AkkaHttpModelAdapter` is the seam between Akka HTTP and the neutral HTTP value
types in `onfhir-common`. Route and marshalling code converts at the edge so
that services, persistence, and events stay framework-neutral.

## Persistence

Two layers sit between a service and MongoDB:

- `ResourceManager` is the FHIR-level API: `searchResources`,
  `getResource`, `getResourceHistory`, `createResource`, `updateResource`,
  `upsertResource`, `deleteResource`, `bulkUpsertResources`,
  `searchLastOrFirstNResources`, and the `_include`/`_revinclude` resolution
  around them. It also publishes the resulting `ResourceCreated`,
  `ResourceUpdated`, and `ResourceDeleted` events to the event bus.
- `DocumentManager` is the MongoDB-level API: document reads and writes,
  history collections, version handling, projections, sorting, and the
  aggregation pipelines.

Search parameters become queries through `ResourceQueryBuilder`, which
dispatches to a builder per parameter type - `StringQueryBuilder`,
`TokenQueryBuilder`, `DateQueryBuilder`, `NumberQueryBuilder`,
`QuantityQueryBuilder`, `ReferenceQueryBuilder`, `UriQueryBuilder` - all
sharing the `IFhirQueryBuilder` contract.

`MongoDB` owns the client and collection handles, `TransactionSession` wraps a
MongoDB session, `EmbeddedMongo` starts a throwaway instance for development
and tests, and `DBConflictManager` handles version conflicts when transactions
are disabled.

## Calling the server in process

`OnFhirLocalClient` implements the `onfhir-client` request builder API against
the local server, bypassing HTTP. It is how operation handlers and extension
code run FHIR interactions without a network hop:

```scala
import io.repofyr.api.client.OnFhirLocalClient

val bundle: Future[FHIRSearchSetBundle] =
  OnFhirLocalClient
    .search("Observation")
    .where("patient", "Patient/p1")
    .executeAndReturnBundle()
```

It extends `io.onfhir.api.client.BaseFhirClient`, so the request builder DSL is
the one documented by `onfhir-client`; only `execute` differs, dispatching
through `FHIRServiceFactory` instead of over the wire.

`OnFhirLocalClient.bulkUpsert(rtype)` returns an `OnFhirBulkRequestBuilder` for
the bulk create-or-update path.

## Configuration

`OnfhirConfig` is the typed view of `application.conf`. The module ships a
fully commented default at `src/main/resources/application.conf` and a logging
default at `src/main/resources/logback.xml`; copy the former and override what
you need.

The key groups are `server.*` (host, port, base URI, SSL, the internal API),
`mongodb.*` (hosts, database, credentials, pooling, sharding, transactions,
embedded mode), `fhir.default.*` (return preference, search handling, page
count, versioning, search total, pagination, and the conditional interaction
switches), `fhir.initialization.*` (the paths to the CapabilityStatement,
profiles, search parameters, operations, compartments, value sets, code
systems, base definitions, and index configuration), `fhir.authorization.*`,
`fhir.auditing`, `fhir.subscription.*`, `fhir.bulk.*`, and `kafka.*`.

Each group has a typed counterpart, so nothing has to read raw keys:

| Section | Type | Owner |
|---|---|---|
| `server.*` | `ServerSettings`, `SslSettings`, `InternalApiSettings` | this module |
| `mongodb.*` | `MongoDbSettings`, `MongoDbPoolingSettings` | this module |
| `fhir.initialization.*` | `FhirInitializationSettings` | this module |
| `fhir.bulk.*` | `BulkSettings` | this module |
| `fhir.default.*` | `FhirRequestDefaults`, `FhirResultDefaults`, `FhirCapabilityDefaults` | `onfhir-common` |
| `fhir.subscription.*` | `FhirSubscriptionSettings` | `onfhir-common` |

Every one of them follows the same shape: a case class, a `Standard` preset,
and a `fromConfig(config)` companion that reads an **already-scoped subtree**
with per-key fallback to `Standard`. Because they take a subtree rather than
reading absolute paths, an embedder can mount Repofyr's configuration anywhere
and still build them:

```scala
val settings = ServerSettings.fromConfig(myConfig.getConfig("my-app.server"))
```

`OnfhirConfig` is simply the composition root that loads the application config
once and slices it into these groups; its flat members are forwarders kept for
existing call sites.

Configuration keys did not change in the 4.0.0 rename, with the single
exception of `fhir.search-handling`, which moved to
`fhir.default.search-handling` to sit beside `return-preference` - both are
`Prefer` header defaults. The old key is still read, with a deprecation
warning. Note there is no `onfhir.*` key namespace: what keeps the legacy
naming is a set of *values* - the `onfhir.subscription` Kafka topic,
`kafka.client.id`, the `onfhir` database name, and the
`akka.actor.onfhir-blocking-dispatcher` dispatcher. Those are operational
contracts, and an existing deployment must upgrade without editing them.

Setting `fhir.initialize = true` runs `IFhirServerConfigurator.setupPlatform`
on startup, which creates the collections and indexes and stores the
infrastructure resources. Run it on first boot and after changing the
configured profiles, search parameters, or index configuration.

## The internal API

When `server.internal.active` is true, `OnFhirInternalEndpoint` binds a second
port carrying `onfhir/internal` routes for companion modules: paged retrieval
of subscriptions, subscription status updates, search parameter configuration
lookup, and search parameter parsing. It is authenticated separately through
`AuthManager.authenticateForInternalApi` and is not part of the FHIR API.

## Relationship to the other modules

- `repofyr-event` supplies the event contracts; this module supplies
  `FhirEventBus`, the implementation that matches events against subscriptions
  using `ResourceChecker`.
- `repofyr-kafka` is a compile dependency, but the producer actor is only
  created when Kafka or FHIR Subscription is enabled.
- `repofyr-operations` is not a dependency. `DefaultOperationHandlers` names
  the handler classes and `FhirOperationHandlerFactory` loads them from the
  class loader at startup, so the operations arrive on the runtime classpath
  rather than through the compile graph.
- `repofyr-server-r4`, `repofyr-server-r5`, and `repofyr-server-stu3` each
  supply an `IFhirServerConfigurator` and a `Boot`.
- The reusable `io.onfhir` libraries supply the neutral models and constants
  (`onfhir-common`), definition loading (`onfhir-config`), profile and
  terminology validation (`onfhir-validation`), FHIRPath (`onfhir-path`), query
  handling (`onfhir-query`), expressions (`onfhir-expression`), and the client
  request builders (`onfhir-client`).

## Scope boundary

Reusable, transport-neutral, server-independent code belongs in the
`srdc/onfhir-libs` repository, not here. What lives in this module is what
needs Akka HTTP, MongoDB, or the server's own lifecycle. Release-specific
behavior belongs in a `repofyr-server-*` module, and operation implementations
belong in `repofyr-operations`.

## Tests

| Suite | What it covers |
| --- | --- |
| `JsonPatchHandlerTest` | JSON Patch add, remove, replace, move, copy, and multi-operation patches |
| `FhirPathPatchHandlerTest` | FHIRPath Patch operations |
| `AkkaHttpModelAdapterTest` | conversion between Akka HTTP types and the neutral HTTP models |
| `FHIRSearchParameterValueParserDirectivesTest` | extraction of search parameters from URI queries and form fields, and propagation of the `Prefer` handling value |
| `SearchParameterConfiguratorTest` | search parameter path and XPath expression parsing, including `as`, restrictions, and indexes |
| `ServerExceptionMappingTest` | the HTTP status and OutcomeIssue payload each `io.repofyr.exception` type maps to |
| `AuthorizationErrorResponseBuilderTest` | conversion of authorization rejections and exceptions, including incomplete ones, into responses |

These are unit suites and need no database. The end-to-end regression net is
the `repofyr-server-r4` suite, which boots a full server against an embedded
MongoDB.

```shell
mvn -pl repofyr-core -am test
```
