# Migrating from onFHIR server 3.x to Repofyr 4.0.0

This guide covers upgrading a deployment from
`io.onfhir:onfhir-server-*:3.x` to `io.repofyr:repofyr-server-*:4.0.0`.

It is the complete reference for the server side of the change. The
reusable libraries are documented separately in
[the onfhir-libs migration guide](https://github.com/srdc/onfhir-libs/blob/master/docs/migration/3.x-to-4.0.0.md).
Read that guide too if your code compiles against onFHIR library types
(neutral HTTP models, the client, FHIRPath, query, validation, the
configuration models): its section 6 lists API and signature changes that
this guide deliberately does not restate.

## 1. Overview

Through 3.x, one monorepo produced both the reusable onFHIR libraries and
the FHIR server built on them. In 4.0.0 that repository was split into two
independently versioned families:

- **Reusable libraries** - `io.onfhir:onfhir-*`, released from
  `srdc/onfhir-libs` under **Apache-2.0**. These keep the `io.onfhir`
  Maven coordinates and the `io.onfhir.*` Scala packages.
- **The server** - `io.repofyr:repofyr-*`, released from this repository
  under **GPL-3.0**. Server-owned code moved to `io.repofyr.*` packages.

The consequence for a consumer is direct: **the `io.onfhir` server artifact
line ends at 3.x.** There is no `io.onfhir:onfhir-server-r4:4.x` and there
never will be. The server continues as `io.repofyr:repofyr-server-r4_2.13`
and its siblings.

The two families start at the same version number, 4.0.0, but they are not
coupled after that. The server declares its library dependencies through an
`onfhir.libs.version` property, separate from its own `revision`, so the
two can move independently.

The rename is a rename. It changes Maven coordinates, Scala package names,
the standalone jar file name, and the container image tags. It changes
nothing that a running deployment stores or transmits - see section 2,
which is the most important section of this guide for an operator.

## 2. What did not change

**An existing deployment's configuration files, MongoDB database, and Kafka
wire traffic continue to work untouched.** Phase 5B of the split renamed
Maven coordinates and Scala packages only. Every runtime configuration key,
persistence identifier, topic name, and stored-data convention deliberately
keeps its existing `onfhir` value, because changing them would force an
operational migration for no benefit.

Specifically unchanged in 4.0.0:

| Item | Value, unchanged |
|---|---|
| Configuration keys | every key in `application.conf`, under the unchanged top-level sections `server`, `fhir`, `akka`, `kafka`, and `mongodb` - with one exception, `fhir.search-handling`, described below |
| Blocking dispatcher | `akka.actor.onfhir-blocking-dispatcher` |
| Kafka subscription topic | `onfhir.subscription` (`kafka.fhir-subscription-topic`) |
| Kafka client id | `kafka.client.id = onfhir` |
| MongoDB database name | `mongodb.db = onfhir`; test setups keep names such as `onfhir-test` |
| Log file paths | `logs/fhir-repository.log`, `logs/fhir-repository.%i.log.zip` |
| Default keystore password | `fhir-repository`, the built-in fallback when `server.ssl.password` is unset |
| Container home | `ONFHIR_HOME` environment variable, `/usr/local/onfhir` path |
| Docker volume and service names | volume `onfhirdata`, service and container `onfhir` |
| Logback logger names | `io.onfhir.path`, `io.onfhir.validation` |
| Operation canonical URLs | including `http://onfhir.io/fhir/OperationDefinition/import` |

### The one configuration key that moved

`fhir.search-handling` is now `fhir.default.search-handling`.

It is the default value of the `Prefer: handling=` header when a client sends
none, which makes it the sibling of `fhir.default.return-preference`, the
default for `Prefer: return=`. The two belong in the same block; they were
separated by accident.

**Your existing configuration keeps working.** The old key is still read when
the new one is absent, and the server logs a deprecation warning naming both
paths. Nothing breaks on upgrade. Move the key when convenient; the fallback
will be removed in a future major release.

The accepted value also gained the bare form, which is now canonical and
matches its neighbour:

```
fhir {
  default {
    return-preference = representation
    search-handling = strict      # was: fhir.search-handling = "handling=strict"
  }
}
```

Both spellings parse - `strict` and `handling=strict` are equivalent, as are
`representation` and `return=representation` - so a copied older file is
accepted as-is.

Two of the unchanged items above deserve a note.

**The two Logback logger names are correct as written.** `io.onfhir.path`
and `io.onfhir.validation` target loggers inside the *library* artifacts,
which kept their `io.onfhir` packages. Do not "fix" them to `io.repofyr` -
those categories do not exist, the level configuration would stop applying,
and you would silently re-enable debug-level noise from FHIRPath evaluation
and profile validation.

**Kafka event payloads carry no package prefix.** The event serializer uses
json4s `ShortTypeHints`, which emits simple class names rather than
fully-qualified ones. A consumer deserializing Repofyr 4.0.0 events sees
exactly the type hints it saw from 3.x, so producers and consumers can be
upgraded independently and in any order.

The one place a *string* does carry a package prefix, and therefore does
need editing, is operation handler class names in `OperationDefinition`
resources. That is section 5, and it is the single most likely thing to
break an otherwise clean upgrade.

## 3. Maven coordinates

Seven server modules changed groupId and artifactId. The `_2.13` Scala
binary-version suffix is retained on all of them.

| 3.x coordinate | 4.0.0 coordinate |
|---|---|
| `io.onfhir:fhir-repository_2.13` (parent) | `io.repofyr:repofyr-parent` |
| `io.onfhir:onfhir-event_2.13` | `io.repofyr:repofyr-event_2.13` |
| `io.onfhir:onfhir-core_2.13` | `io.repofyr:repofyr-core_2.13` |
| `io.onfhir:onfhir-operations_2.13` | `io.repofyr:repofyr-operations_2.13` |
| `io.onfhir:onfhir-kafka_2.13` | `io.repofyr:repofyr-kafka_2.13` |
| `io.onfhir:onfhir-server-r4_2.13` | `io.repofyr:repofyr-server-r4_2.13` |
| `io.onfhir:onfhir-server-r5_2.13` | `io.repofyr:repofyr-server-r5_2.13` |
| `io.onfhir:onfhir-server-stu3_2.13` | `io.repofyr:repofyr-server-stu3_2.13` |

Most deployments name only a `repofyr-server-*` module and receive the
other four transitively. The `repofyr-event`, `repofyr-operations`, and
`repofyr-kafka` rows are listed because any build that pinned them
explicitly, or that excluded them, must update those declarations too.

An example server dependency after the upgrade:

```xml
<dependency>
  <groupId>io.repofyr</groupId>
  <artifactId>repofyr-server-r4_2.13</artifactId>
  <version>4.0.0</version>
</dependency>
```

Library dependencies stay on `io.onfhir` coordinates. If your build names
both families, keep two version properties - the server version and the
library version - and do not collapse them into one.

Library-side coordinate changes (including the new
`io.onfhir:onfhir-r5_2.13`, the definitions artifacts, and the
`onfhir-template-engine` suffix correction) are documented in
[sections 3.1 and 3.2 of the libs guide](https://github.com/srdc/onfhir-libs/blob/master/docs/migration/3.x-to-4.0.0.md#3-maven-coordinates).
The same coordinate table above appears from the library side in
[section 3.3 of that guide](https://github.com/srdc/onfhir-libs/blob/master/docs/migration/3.x-to-4.0.0.md#33-server-artifacts-moved-to-repofyr).

## 4. Scala package renames

**The rule:** a server-owned type moves from `io.onfhir.X` to
`io.repofyr.X`, keeping its simple name and the rest of its package path.
Every reusable library type keeps `io.onfhir.*`.

This is mechanical but it is **not** a global find-and-replace of
`io.onfhir` with `io.repofyr`. Doing that will break your build, because
the library types your code also imports did not move.

165 server sources migrated across 33 packages. They are not enumerated
individually, because the package name is enough to derive the new name of
any type in it. The 33 packages divide into two groups, and the group
determines how you rewrite the import.

### 4.1 The 17 server-only packages - rewrite by prefix

No `io.onfhir` type of the same package name remains. Every import from
these packages can be rewritten by replacing the `io.onfhir` prefix with
`io.repofyr`, including wildcard imports - with the single annotated
exception on the last row, which is explained under the table.

| Old package | New package |
|---|---|
| `io.onfhir.api.endpoint` | `io.repofyr.api.endpoint` |
| `io.onfhir.async` | `io.repofyr.async` |
| `io.onfhir.audit` | `io.repofyr.audit` |
| `io.onfhir.db` | `io.repofyr.db` |
| `io.onfhir.event` | `io.repofyr.event` |
| `io.onfhir.event.kafka` | `io.repofyr.event.kafka` |
| `io.onfhir.operation` | `io.repofyr.operation` |
| `io.onfhir.server` | `io.repofyr.server` |
| `io.onfhir.r4.audit` | `io.repofyr.r4.audit` |
| `io.onfhir.r4.config` | `io.repofyr.r4.config` |
| `io.onfhir.r4.subscription` | `io.repofyr.r4.subscription` |
| `io.onfhir.r5.audit` | `io.repofyr.r5.audit` |
| `io.onfhir.r5.config` | `io.repofyr.r5.config` |
| `io.onfhir.stu3` | `io.repofyr.stu3` |
| `io.onfhir.stu3.audit` | `io.repofyr.stu3.audit` |
| `io.onfhir.stu3.config` | `io.repofyr.stu3.config` |
| `io.onfhir.stu3.parsers` | `io.onfhir.stu3.parsers` - unchanged, see below |

**The exception: `io.onfhir.stu3.parsers` did not move.** It was renamed to
`io.repofyr.stu3.parsers` during the split, then moved back before release,
because the STU3 release parsers are reusable library code rather than
server runtime. In 4.0.0 as shipped, `STU3Parser` and
`STU3StructureDefinitionParser` are in `io.onfhir.stu3.parsers` - the
package they always had - but they are now published in the new
`io.onfhir:onfhir-stu3_2.13` library artifact instead of the STU3 server
module.

So if you import either type, **change the Maven coordinate, not the
import**. Applying the prefix rule to this package will not compile.
`STU3StructureDefinitionParser` had no external caller, so in practice this
affects only users of `STU3Parser`.

### 4.2 The 16 split packages - one import becomes two

These package names exist in **both** repositories after the split. A
prefix rewrite is wrong here: the server-owned types moved to `io.repofyr`
while the library types of the same package name stayed in `io.onfhir`. If
your code imported both kinds from one package, that import line must
become two.

Wildcard imports from these packages are the highest-risk case, since they
will silently pull in only half of what they used to.

| Shared package | Server-owned types, now `io.repofyr.*` | Library types, still `io.onfhir.*` |
|---|---|---|
| `io.onfhir` (top level) | the `Onfhir` server bootstrap, in `repofyr-core_2.13` | the whole remaining `io.onfhir.*` library namespace, including the `io.onfhir.api` package object and its constants |
| `io.onfhir.api.client` | `OnFhirLocalClient`, `OnFhirBulkRequestBuilder` | the client API in `onfhir-client_2.13`: `IOnFhirClient`, `BaseFhirClient`, `FHIRBundle`, `FhirClientException`, and the request builders |
| `io.onfhir.api.model` | the Akka boundary adapters `AkkaHttpModelAdapter`, `FHIRMarshallers`, `JsonToXmlConvertor`, `XmlToJsonConvertor` | the neutral models in `onfhir-common_2.13`: `FHIRRequest`, `FHIRResponse`, `FHIROperationRequest`/`Response`, `NeutralHttpModels`, `FhirSubscription`, `Parameter`, `OutcomeIssue`, and neighbours |
| `io.onfhir.api.parsers` | `FHIRSearchParameterValueParserDirectives` | `BundleRequestParser`, `FHIRSearchParameterValueParser`, `IFhirFoundationResourceParser`, `ISearchParamPlaceholderResolver`, and the query-owned `FhirQueryParser` and `FHIRResultParameterResolver` |
| `io.onfhir.api.service` | the 19 server services: `FHIRCreateService`, `FHIRReadService`, `FHIRSearchService`, `FHIRUpdateService`, `FHIRPatchService`, `FHIRDeleteService`, `FHIRHistoryService`, `FHIRBatchTransactionService`, `FHIRBulkService`, `FHIRInteractionService`, `FHIROperationHandler`, `FHIROperationHandlerService`, `FHIRServiceFactory`, `FHIRSubscriptionBusinessValidator`, `FhirPathPatchHandler`, `JsonPatchHandler`, `IFHIRPatchHandler`, `OnFhirInternalApiService`, `TargetResourceResolver` | the external service contracts `IFhirTerminologyService` and `IFhirIdentityService` |
| `io.onfhir.api.util` | `FHIRServerUtil`, `ResourceChecker`, the core-owned `SubscriptionUtil` contract | `FHIRUtil`, `FhirPatchUtil`, `IOUtil`, `ImMemorySearchUtil`, `InMemoryPrefixModifierHandler` |
| `io.onfhir.api.validation` | `FHIRApiValidator`, `FHIRResourceValidator`, `IResourceSpecificValidator`, `ReferenceResolver` | `AbstractFhirContentValidator`, `IFhirResourceValidator`, `IFhirTerminologyValidator`, `ProfileRestrictions`, the reference-resolver contracts and default implementations |
| `io.onfhir.authz` | the 14 server authorization runtime types: `AuthManager`, `AuthzManager`, `AuthzConfigurationManager`, `IAuthorizer`, `BaseAuthorizer`, `BasicAuthorizer`, `SmartAuthorizer`, `ITokenResolver`, `JWTResolver`, `ResolverWithTokenIntrospection`, `ICustomAuditHandler`, `TokenClient`, `AuthorizationServerMetadata`, `FhirAuthzConstraintRule` | the request/decision metadata kept in Common: `AuthContext`, `AuthzContext`, `AuthzResult` |
| `io.onfhir.client` | test fixtures only (`OnFhirLocalClientTest`) | `OnFhirNetworkClient`, `JdkHttpTransport`, `IHttpRequestInterceptor`, `TerminologyServiceClient`, `IdentityServiceClient`, and the `client.intrcp`, `client.model`, `client.parsers`, `client.util` subpackages |
| `io.onfhir.config` | the server configuration cluster: `OnfhirConfig`, `AuthzConfig`, `AuditConfig`, `SSLConfig`, `IndexConfigurator`, `IFhirServerConfigurator`, `BaseFhirServerConfigurator`, `FhirConfigurationManager`, `IFhirConfigurationManager` | the FHIR configuration models and readers: `BaseFhirConfig`, `FhirServerConfig`, `FhirRuntimeSettings`, `FHIRSearchParameter`, `OperationConf`, `ResourceConf`, `IFhirConfigReader`, `IFhirVersionConfigurator`, `BaseFhirConfigurator`, `FSConfigReader`, `FhirApiConfigReader`, `SearchParameterConfigurator`, and neighbours |
| `io.onfhir.exception` | the ten HTTP response exceptions plus `AuthorizationFailedRejection` and `TransientRejection` | `InitializationException`, `InvalidParameterException`, `UnsupportedParameterException` |
| `io.onfhir.expression` | test fixtures only (`XFhirQueryParserTest`) | `FhirExpression`, `FhirExpressionEvaluator`, `FhirExpressionException`, `IFhirExpressionLanguageHandler`, `XFhirQueryParser`, and the placeholder resolvers |
| `io.onfhir.r4` | `Boot` and the `r4.audit`, `r4.config`, `r4.subscription` subpackages | `io.onfhir.r4.parsers`: `R4Parser` and `StructureDefinitionParser` |
| `io.onfhir.r5` | `Boot` and the `r5.audit`, `r5.config` subpackages | `io.onfhir.r5.parsers`: `R5Parser` |
| `io.onfhir.util` | `InternalJsonMarshallers`, in `repofyr-event_2.13` | `DateTimeUtil`, `JsonFormatter`, `OnFhirZipInputStream` |
| `io.onfhir.validation` | test fixtures only (`ProfileValidationTest`, `StructureDefinitionParserTest`, `TerminologyParserTest`) | `FhirValidator`, `FhirContentValidator`, `FhirTerminologyValidator`, `TerminologyParser`, `AbstractStructureDefinitionParser`, the restriction types, and `BaseFhirProfileHandler`, which now ships in `onfhir-config_2.13` |

The `io.onfhir.config` row is the one most consumers hit. Code that
imported `OnfhirConfig` and `FhirServerConfig` together from
`io.onfhir.config` now needs:

```scala
import io.repofyr.config.OnfhirConfig
import io.onfhir.config.FhirServerConfig
```

All of these are compile errors, so the compiler finds them for you. The
next section covers the one that it cannot.

## 5. Operation dispatch, and the string the compiler cannot check

The default operation dispatch map changed both owner and package:

| 3.x | 4.0.0 |
|---|---|
| `DEFAULT_IMPLEMENTED_FHIR_OPERATIONS` in package `io.onfhir.api`, artifact `onfhir-common_2.13` | `io.repofyr.operation.DefaultOperationHandlers.DEFAULT_IMPLEMENTED_FHIR_OPERATIONS`, artifact `repofyr-core_2.13` |

Code that read the library constant to discover the built-in operations
must now read the object in `repofyr-core_2.13`. That is a compile error
and it is easy to fix.

### The failure mode a compiler cannot catch

**`OperationDefinition.name` holds a fully-qualified handler class name as
a plain string.** Repofyr reads it and reflectively instantiates that
class. It is data, not code. Nothing in your build will flag it.

If any `OperationDefinition` resource you deploy, or any server
configuration that names operation handlers, contains a value beginning
`io.onfhir.operation.`, you **must** edit that string to
`io.repofyr.operation.`. If you do not, the server compiles, builds,
starts, and then fails at the moment the operation is first invoked,
because the named class no longer exists.

This applies to `OperationDefinition` resources wherever you keep them:
the configured operation definitions folder, files loaded during
initialization, and any copies already persisted in MongoDB from a
previous run.

The built-in handlers and their canonical URLs in 4.0.0:

| Operation URL | Handler class (4.0.0) |
|---|---|
| `http://hl7.org/fhir/OperationDefinition/Resource-meta` | `io.repofyr.operation.MetaOperationHandler` |
| `http://hl7.org/fhir/OperationDefinition/Resource-meta-add` | `io.repofyr.operation.MetaOperationHandler` |
| `http://hl7.org/fhir/OperationDefinition/Resource-meta-delete` | `io.repofyr.operation.MetaOperationHandler` |
| `http://hl7.org/fhir/OperationDefinition/Resource-validate` | `io.repofyr.operation.ValidationOperationHandler` |
| `http://hl7.org/fhir/OperationDefinition/ValueSet-expand` | `io.repofyr.operation.ExpandOperationHandler` |
| `http://hl7.org/fhir/OperationDefinition/Composition-document` | `io.repofyr.operation.DocumentOperationHandler` |
| `http://hl7.org/fhir/OperationDefinition/Observation-lastn` | `io.repofyr.operation.LastNObservationOperationHandler` |
| `http://hl7.org/fhir/OperationDefinition/Patient-everything` | `io.repofyr.operation.PatientEverythingOperationHandler` |
| `http://onfhir.io/fhir/OperationDefinition/import` | `io.repofyr.operation.BulkOperationHandler` |

**The operation URLs are unchanged**, including the onFHIR-specific
`http://onfhir.io/fhir/OperationDefinition/import`. Clients invoking
operations need no change; the `$import` endpoint keeps its published
canonical URL. Only the handler class strings on the server side move.

The same rule applies to your own custom operation handlers if you moved
them into `io.repofyr.*` packages as part of this upgrade - the string in
the `OperationDefinition` must track wherever the class actually lives.

A useful pre-upgrade check is to grep your deployed OperationDefinition
resources for `io.onfhir.operation` and fix every hit before starting the
new server.

## 6. Server construction contracts

If you embed Repofyr rather than running the standalone jar, four
construction points changed. All four are compile errors.

**`KafkaEventProducer`** (`io.repofyr.event.kafka`, `repofyr-kafka_2.13`)
no longer reads anything from the `OnfhirConfig` singleton and no longer
takes a `FhirServerConfig`. It receives its Kafka configuration, the
subscription activation flag, and an injected release-neutral subscription
parser function, all supplied by `Onfhir` at startup:

```scala
// 3.x
KafkaEventProducer.props(fhirServerConfig)
new KafkaEventProducer(kafkaConfig, fhirServerConfig)

// 4.0.0
KafkaEventProducer.props(
  kafkaConfig: KafkaConfig,
  fhirSubscriptionActive: Boolean,
  parseFhirSubscription: Resource => FhirSubscription
): Props

new KafkaEventProducer(
  kafkaConfig: KafkaConfig,
  fhirSubscriptionActive: Boolean,
  parseFhirSubscription: Resource => FhirSubscription
)
```

The parser function is how the Kafka module stays free of any FHIR-release
dependency: it never parses a `Subscription` itself, it calls what it is
given.

**`ResourceChecker`** (`io.repofyr.api.util`, `repofyr-core_2.13`) takes
the endpoint settings explicitly instead of reaching for a singleton:

```scala
// 3.x
new ResourceChecker(fhirConfig)

// 4.0.0
new ResourceChecker(fhirConfig: FhirServerConfig,
                    endpointSettings: FhirEndpointSettings)
```

**`SubscriptionUtil`** is no longer constructed directly. In 3.x it lived
in the common library and callers instantiated it. In 4.0.0 the contract is
owned by core and the implementation is release-specific, so you obtain it
from the configurator:

```scala
// 4.0.0
val subscriptionUtil = fhirConfigurator.getSubscriptionUtil(
  fhirConfig: FhirServerConfig,
  subscriptionSettings: FhirSubscriptionSettings,
  defaultSearchHandling: FhirSearchHandling
)
```

`IFhirServerConfigurator` is `io.repofyr.config.IFhirServerConfigurator`.
`FhirR4Configurator` and `FhirR5Configurator` return working
implementations; `FhirSTU3Configurator` returns an
`UnsupportedSubscriptionUtil`, since STU3 has no Subscription support in
this release.

If you implement `IFhirServerConfigurator` yourself, you must now provide
`getSubscriptionUtil`.

The broader pattern behind all four changes is that server components
receive their configuration as parameters instead of reading the global
`OnfhirConfig` singleton. Prefer injection for anything new.

### `OnfhirConfig` exposes groups, not individual keys

If you read settings off `OnfhirConfig` directly, those per-key accessors are
**gone**. They were replaced by typed groups, each a case class with a
`Standard` preset and a `fromConfig` companion that reads an already-scoped
subtree.

**No configuration key changed.** This is a source-level change only; your
`application.conf` needs no edit. What changes is how Scala code reaches the
values:

| 3.x accessor | 4.0.0 |
|---|---|
| `OnfhirConfig.baseUri`, `serverHost`, `serverPort`, `serverSsl`, `serverLocation` | `OnfhirConfig.serverSettings.{baseUri, host, port, ssl.enabled, location}` |
| `OnfhirConfig.internalApiActive`, `internalApiPort`, `internalApiAuthenticate` | `OnfhirConfig.serverSettings.internalApi.{active, port, authenticate}` |
| `OnfhirConfig.mongodbHosts`, `mongodbName`, `mongodbUser`, `mongoEmbedded`, ... | `OnfhirConfig.mongoDbSettings.{hosts, dbName, username, embedded, ...}` |
| `OnfhirConfig.mongodbPooling*` | `OnfhirConfig.mongoDbSettings.pooling`, an `Option` that is absent when no `pooling` block is configured |
| `OnfhirConfig.conformancePath`, `profilesPath`, `baseDefinitions`, `fhirInitialize`, ... | `OnfhirConfig.fhirInitializationSettings.{conformancePath, profilesPath, baseDefinitionsPath, initialize, ...}` |
| `OnfhirConfig.bulkNumResourcesPerGroup`, `bulkUpsertMode` | `OnfhirConfig.bulkSettings.{numResourcesPerGroup, upsertMode}` |
| `OnfhirConfig.fhirRootUrl` | `OnfhirConfig.fhirEndpointSettings.rootUrl` |
| `OnfhirConfig.fhirDefaultPageCount`, `fhirDefaultPagination` | `OnfhirConfig.fhirResultDefaults.{defaultPageSize, paginationMode}` |
| `OnfhirConfig.fhirSearchHandling`, `fhirDefaultReturnPreference` | `OnfhirConfig.fhirRequestDefaults.{searchHandling, returnPreference}` |
| `OnfhirConfig.fhirDefaultVersioning`, `fhirDefaultConditional*`, `fhirDefaultReadHistory`, `fhirDefaultUpdateCreate` | `OnfhirConfig.fhirCapabilityDefaults.{versioning, conditional*, readHistory, updateCreate}` |
| `OnfhirConfig.fhirSubscriptionActive`, `fhirSubscriptionAllowedResources` | `OnfhirConfig.fhirSubscriptionSettings.{active, allowedResources}` |

Two of these changed type as well as location. `paginationMode` and
`searchHandling` are now typed values rather than strings, so compare against
`FhirPaginationMode.Offset` and `FhirSearchHandling.Strict` instead of `"offset"`
and `"handling=strict"`; use `.code` if you need the wire form.
`allowedResources` is an `Option[Set[String]]` rather than
`Option[Seq[String]]`.

What stays flat on `OnfhirConfig` is only what belongs to no section:
`serverName` and `fhirRequestTimeout` (their keys live in the `spray.can.*` and
`akka.http.*` namespaces), `fhirValidation`, `fhirBinaryAllowedMimeTypes`,
`logFailedRequests`, `integratedTerminologyServices`, and the two settings that
were already their own group, `authzConfig` and `fhirAuditingConfig`.

Because the groups take a scoped subtree rather than reading absolute paths,
an embedder can now build them from its own configuration layout:

```scala
val settings = ServerSettings.fromConfig(myConfig.getConfig("my-app.server"))
```

## 7. Packaging and deployment

### Standalone jar

The shaded executable jar is renamed:

| 3.x | 4.0.0 |
|---|---|
| `onfhir-server-standalone.jar` | `repofyr-server-standalone.jar` |

All three release modules produce it, each under its own
`target/` directory. Update every deployment script, systemd unit,
Dockerfile `COPY` line, and `docker-entrypoint.sh` reference.

```bash
java -jar target/repofyr-server-standalone.jar
java -Dconfig.file=/path/to/application.conf \
     -jar target/repofyr-server-standalone.jar
```

The `<mainClass>` entries changed with the packages:

| Module | Main class |
|---|---|
| `repofyr-server-r4` | `io.repofyr.r4.Boot` |
| `repofyr-server-r5` | `io.repofyr.r5.Boot` |
| `repofyr-server-stu3` | `io.repofyr.stu3.Boot` |

This matters if you launch by class name rather than by `java -jar`.

### Container images

| 3.x image | 4.0.0 image |
|---|---|
| `srdc/onfhir:r4` | `srdc/repofyr:r4` |
| `srdc/onfhir:r5` | `srdc/repofyr:r5` |

Everything inside the container is deliberately unchanged: `ONFHIR_HOME`
still points at `/usr/local/onfhir`, config still mounts at
`/usr/local/onfhir/conf`, the compose volume is still `onfhirdata`, and
the service and container are still named `onfhir`. Repointing the image
tag is the whole change - existing bind mounts and named volumes attach
exactly as before.

### FHIR definitions are no longer embedded

The R4 and R5 server modules used to embed their own copy of
`definitions-rX.json.zip` and `conformance-statement-rX.json` in
`src/main/resources`. In 4.0.0 they declare a resources-only library
artifact instead, removing roughly 18 MB of duplicated resources:

| Module | Definitions dependency |
|---|---|
| `repofyr-server-r4` | `io.onfhir:onfhir-definitions-r4` |
| `repofyr-server-r5` | `io.onfhir:onfhir-definitions-r5` |
| `repofyr-server-stu3` | `io.onfhir:onfhir-definitions-stu3` |

These coordinates carry **no `_2.13` suffix**, because they contain no
compiled code. They are versioned with the library family, so they resolve
at `${onfhir.libs.version}`.

Both files still resolve by the same bare classpath name as before -
loading goes through `ClassLoader.getResourceAsStream` and a streaming
`ZipInputStream` - so nothing about resolution changed for an operator.

One packaging warning for anyone customizing the build: the definitions
dependency must be at **compile** scope. Setting it to `provided` or
`test` passes every test and then produces a standalone jar with no
definitions in it, which fails at startup.

`db-index-conf-rX.json` is server configuration, not a FHIR definition, and
stays in the server modules.

### STU3 startup was fixed in this release

`repofyr-server-stu3` previously packaged `conformance-statement.json`,
`definitions.json.zip`, and `db-index-conf.json` - none of which the
default resolution branch could find. `FhirSTU3Configurator` reports its
release as `STU3`, so all three lookups derive a `-stu3` suffixed name and
threw at startup. This is a defect that predates the split.

In 4.0.0 the names line up. The first two now come from
`onfhir-definitions-stu3` as `conformance-statement-stu3.json` and
`definitions-stu3.json.zip`, and the module's own index configuration was
renamed to `db-index-conf-stu3.json`.

- A STU3 deployment that **overrode** `fhir.initialization.index-conf-path`
  to a filesystem path is unaffected; your override still wins.
- A STU3 deployment that **relied on the packaged classpath defaults** now
  gets a server that starts. If you were carrying a local workaround for
  this, you can drop it.

### STU3 summarized responses now carry the STU3 code system

This is the one place where an STU3 server's **output** changes.

`FhirSTU3Configurator` has always declared
`http://hl7.org/fhir/v3/ObservationValue` as the code system for the
`SUBSETTED` tag that marks a summarized read or search response - the
pre-`terminology.hl7.org` URL that STU3 uses. That declaration never took
effect: the platform initializer copied its sibling release-specific fields
onto the runtime configuration and skipped this one, so STU3 emitted the
R4-era `http://terminology.hl7.org/CodeSystem/v3-ObservationValue`.

From 4.0.0 the declared value is used. Concretely, a `_summary` or
`_elements` response from an STU3 server now carries:

```json
{ "system": "http://hl7.org/fhir/v3/ObservationValue", "code": "SUBSETTED" }
```

Only the `system` changes. The tag `code` is still `SUBSETTED`, and nothing
else about the response differs, so a client matching on the code alone needs
no change. A client matching on the `system` string must accept the new value.
R4 and R5 are unaffected - their configured value already equalled the runtime
default, so their output is byte-identical to 3.x.

`repofyr-server-r5` also now parses foundation resources with `R5Parser`
from `onfhir-r5_2.13` rather than `R4Parser`. `R5Parser` extends
`R4Parser` with an identical constructor and no overrides, so behavior is
unchanged; R5 simply gains a real extension point.

## 8. Upgrade recipe

Ordered and mechanical. Steps 1 through 3 are compiler-checked; step 4 is
not, which is why it is called out separately.

1. **Bump the coordinates.** Change `io.onfhir:onfhir-server-*` and any
   other of the seven server artifacts to
   `io.repofyr:repofyr-*_2.13` at `4.0.0` (section 3). Keep your library
   dependencies on `io.onfhir` and give them their own version property.

2. **Prefix-rewrite the 17 server-only packages** (section 4.1). A
   scripted `io.onfhir.X` -> `io.repofyr.X` replacement is safe here, one
   package at a time. Do not run an unscoped `io.onfhir` -> `io.repofyr`
   replacement across the tree.

3. **Hand-split the 16 shared packages** (section 4.2). Expand any
   wildcard import from these packages first, then split each import line
   according to the table. Recompile and let the compiler find the rest.

4. **Update handler class strings** in every `OperationDefinition`
   resource and in server configuration: `io.onfhir.operation.*` becomes
   `io.repofyr.operation.*` (section 5). Grep for `io.onfhir.operation`
   across your deployed resources, your configuration folder, and any
   OperationDefinitions already stored in MongoDB. **Nothing else in this
   list will catch a miss here.**

5. **Fix embedded construction sites** if you embed Repofyr:
   `KafkaEventProducer`, `ResourceChecker`, and `getSubscriptionUtil`
   (section 6).

6. **Rename the jar** in deployment scripts, systemd units, Dockerfiles,
   and entrypoints: `onfhir-server-standalone.jar` becomes
   `repofyr-server-standalone.jar` (section 7). Update `<mainClass>`
   references if you launch by class name.

7. **Repoint image tags** from `srdc/onfhir:{r4,r5}` to
   `srdc/repofyr:{r4,r5}` (section 7). Leave volumes, mounts, and
   environment variables alone.

8. **Rebuild and run your test suite.** If your code also uses onFHIR
   library types, work through
   [section 6 of the libs guide](https://github.com/srdc/onfhir-libs/blob/master/docs/migration/3.x-to-4.0.0.md#6-public-api-changes)
   for API changes - particularly the neutral HTTP model replacing Akka
   types, and the client no longer needing an `ActorSystem`.

9. **Deploy.** Point the new build at your existing `application.conf`,
   your existing MongoDB, and your existing Kafka topics.

**Configuration and data need no migration.** There is no schema change, no
config rewrite, no topic rename, and no data backfill in this upgrade. If a
step in your runbook proposes one, it is not required by 4.0.0.

## 9. Getting help

- **Library migration** - API changes, the neutral HTTP model, client
  construction, FHIRPath and validation behavior, and library coordinate
  changes are in the
  [onfhir-libs migration guide](https://github.com/srdc/onfhir-libs/blob/master/docs/migration/3.x-to-4.0.0.md).
  Its section 3.3 shows this repository's coordinate move from the library
  side, and its section 5.2 lists library types that became server types.
- **Binary compatibility detail** - the machine-readable break inventory
  for the library family, with grouped explanations, is in the
  `docs/compatibility/` directory of `srdc/onfhir-libs`.
- **Release notes** - see [the changelog](../../CHANGELOG.md) and the 4.0.0
  release page for this repository.
- **Known gaps** - deliberate limitations in 4.0.0 are listed in
  [known limitations](../release/known-limitations.md).
- **Questions and problems** - open an issue on
  [`srdc/repofyr`](https://github.com/srdc/repofyr/issues) for server problems,
  or on [`srdc/onfhir-libs`](https://github.com/srdc/onfhir-libs/issues) for
  library problems. If you are not sure which, the dividing line is the
  coordinate: `io.repofyr` here, `io.onfhir` there. Note that this repository
  was renamed from `srdc/onfhir`; the old URL redirects.
