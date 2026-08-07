# Changelog

User-visible changes to the Repofyr FHIR server.

Repofyr is versioned independently of the `io.onfhir` reusable libraries it
consumes. Both families start at 4.0.0 because they were split out of a
single monorepo in this release; from the next release onwards the two
version lines move separately. The library version the server builds
against is the `onfhir.libs.version` property, never `revision`.

All seven server artifacts release together at one version, set by the
`revision` property in the root POM. Patches are fixes only, minors are
additive, and a breaking change to a server API, a configuration key, or a
packaging convention occurs only in a major release - where it additionally
gets an entry in the
[migration guide](docs/migration/onfhir-3.x-to-repofyr-4.0.md).

## 4.0.0 (unreleased)

First release of the FHIR server as Repofyr. Through 3.x one repository
produced both the reusable onFHIR libraries and the server built on them.
In 4.0.0 the two were split into independently versioned families: the
libraries continue as `io.onfhir` under Apache-2.0 from
[`srdc/onfhir-libs`](https://github.com/srdc/onfhir-libs), and the server
continues here as `io.repofyr` under GPL-3.0.

**The `io.onfhir` server artifact line ends at 3.x.** There is no
`io.onfhir:onfhir-server-r4:4.x` and there never will be. This is an
intentional major release; the complete upgrade path is in the
[migration guide](docs/migration/onfhir-3.x-to-repofyr-4.0.md).

The rename is a rename. It changes Maven coordinates, Scala package names,
the standalone jar name, and the container image tags. It changes nothing a
running deployment stores or transmits - see "Unchanged (deliberate)"
below.

### Added

- `repofyr-embedded-mongo` and `repofyr-dev-server`. The first starts an
  embedded MongoDB for development and tests; the second is a runnable
  development server that starts one and boots Repofyr for a chosen FHIR
  release against it, defaulting to R5:
  `mvn -pl repofyr-dev-server -am exec:java -Dexec.args=r4`.
  `repofyr-dev-server` is a development convenience and is not published.
- `Onfhir.apply` accepts `onShutdown: Seq[() => Unit]`, run after the HTTP
  binding has drained and before the actor system terminates. It exists so a
  resource started alongside the server outlives every in-flight request; a JVM
  shutdown hook would race Akka's own `CoordinatedShutdown` instead. The
  parameter defaults to `Nil`, so existing callers are unaffected.
- Published jars now carry the GPL-3.0 license text at `META-INF/LICENSE`.
- Each of the seven module POMs publishes its own `<name>` and
  `<description>`. Previously all seven inherited only the parent's, so
  every published POM described the same thing.
- `docs/migration/onfhir-3.x-to-repofyr-4.0.md` - the complete server-side
  upgrade path from onFHIR 3.x, with the coordinate table, the package
  rename tables, and an ordered upgrade recipe.
- `docs/release/known-limitations.md` - the deliberate, documented gaps in
  this release, with what to do instead for each.
- Typed, grouped server settings in `io.repofyr.config`: `ServerSettings`,
  `SslSettings`, `InternalApiSettings`, `MongoDbSettings`,
  `MongoDbPoolingSettings`, `FhirInitializationSettings` and `BulkSettings`.
  Each is a case class with a `Standard` preset and a `fromConfig` companion
  that reads an already-scoped subtree, so an embedder can build them from its
  own configuration layout instead of relying on `OnfhirConfig` loading the
  application config globally.

### Changed

- **`fhir.search-handling` moved to `fhir.default.search-handling`**, joining
  `return-preference` - both are defaults for the `Prefer` header. The old key
  is still read when the new one is absent, with a deprecation warning, so
  existing configuration keeps working. The value may now be written as the
  bare token (`strict`) as well as the full header code (`handling=strict`);
  the bare form is canonical and matches its neighbour. See section 2 of the
  migration guide.
- **`OnfhirConfig` exposes typed groups instead of per-key accessors.** It now
  builds the FHIR settings with the `fromConfig` companions in
  `io.onfhir.config` (`onfhir-common` 4.0.0) rather than mapping roughly twenty
  raw keys by hand, and slices the server-owned sections into the typed groups
  listed above. Its public surface drops from 62 members to 18: the nine
  groups, plus the settings that belong to no section.

  Every per-key accessor for a grouped setting is **removed**, not deprecated.
  Embedders reading them must move to the group, for example
  `OnfhirConfig.baseUri` to `OnfhirConfig.serverSettings.baseUri`,
  `OnfhirConfig.mongodbHosts` to `OnfhirConfig.mongoDbSettings.hosts`, and
  `OnfhirConfig.conformancePath` to
  `OnfhirConfig.fhirInitializationSettings.conformancePath`. This is a
  source-level change only: no configuration key changed name or meaning, so no
  deployment configuration needs editing.

- **Maven coordinates.** The seven server modules move from
  `io.onfhir:onfhir-*` to `io.repofyr:repofyr-*_2.13`: `repofyr-event`,
  `repofyr-core`, `repofyr-operations`, `repofyr-kafka`,
  `repofyr-server-r4`, `repofyr-server-r5`, and `repofyr-server-stu3`,
  under the new parent `io.repofyr:repofyr-parent`.
- **Scala packages.** A server-owned type moves from `io.onfhir.X` to
  `io.repofyr.X`, keeping its simple name. Library types stay in
  `io.onfhir.*`, so this is **not** a global find-and-replace: 16 package
  names now exist in both families, and an import from one of them may have
  to become two. See section 4 of the migration guide.
- **Reusable code is now an external dependency.** The models, client,
  FHIRPath, query, configuration, expression, validation, template-engine,
  and FHIR release parser code is consumed as released `io.onfhir:*:4.0.0`
  artifacts instead of being built here. Its own changes are listed in the
  [onfhir-libs changelog](https://github.com/srdc/onfhir-libs/blob/master/CHANGELOG.md)
  and
  [migration guide](https://github.com/srdc/onfhir-libs/blob/master/docs/migration/3.x-to-4.0.0.md).
- **Operation handler class names in `OperationDefinition.name` must be
  edited by hand.** That value is a fully qualified class name held as a
  plain string and instantiated reflectively, so nothing in a build flags
  it. Any value beginning `io.onfhir.operation.` must become
  `io.repofyr.operation.` - in your operation definitions folder, in server
  configuration, and in any OperationDefinition already persisted in
  MongoDB. Operation canonical URLs are unchanged, including
  `http://onfhir.io/fhir/OperationDefinition/import`. This is the single
  most likely thing to break an otherwise clean upgrade.
- `DEFAULT_IMPLEMENTED_FHIR_OPERATIONS` moved from `io.onfhir.api` in
  `onfhir-common_2.13` to `io.repofyr.operation.DefaultOperationHandlers`
  in `repofyr-core_2.13`.
- `SubscriptionUtil` is no longer constructed directly. Obtain it from
  `IFhirServerConfigurator.getSubscriptionUtil(fhirConfig,
  subscriptionSettings, defaultSearchHandling)`. Anyone implementing
  `IFhirServerConfigurator` must now provide that method.
- `KafkaEventProducer.props` and the `KafkaEventProducer` constructor take
  `(KafkaConfig, Boolean, Resource => FhirSubscription)` instead of a
  `FhirServerConfig`. The Kafka module now reads nothing from the
  `OnfhirConfig` singleton and carries no FHIR release dependency - it
  never parses a `Subscription` itself, it calls the injected parser.
- `ResourceChecker` takes `(FhirServerConfig, FhirEndpointSettings)`
  instead of reaching for a singleton.
- **Packaging.** The shaded executable jar is
  `repofyr-server-standalone.jar` (was `onfhir-server-standalone.jar`), the
  main classes are `io.repofyr.{r4,r5,stu3}.Boot`, and the container images
  are `srdc/repofyr:{r4,r5}` (were `srdc/onfhir:{r4,r5}`). Everything
  inside the container is unchanged, so repointing the image tag is the
  whole change.
- `repofyr-server-r5` parses foundation resources with `R5Parser` from
  `io.onfhir:onfhir-r5_2.13` rather than `R4Parser`. `R5Parser` extends
  `R4Parser` with an identical constructor and no overrides, so behavior is
  unchanged; R5 simply gains a real extension point.
- The `release` profile no longer publishes on its own. `autoPublish` is
  `false` and `waitUntil` is `validated`, so `mvn -Prelease deploy` uploads
  and validates a deployment but never releases it. Promotion is a
  deliberate act in the Central portal.
- Scala sources compile with `-release 11`, so a build on a newer JDK
  cannot silently emit bytecode that fails at runtime on Java 11.
- The `db-index-conf` comment in the shipped `application.conf` files named
  a module and a file that no longer exist. It now names
  `repofyr-server-r4/db-index-conf-r4.json`.

### Removed

- **Embedded MongoDB is no longer part of the runnable servers.**
  `io.onfhir.db.EmbeddedMongo` was compile-scoped in the core module, so it
  shipped inside every standalone jar and reached every consumer embedding
  `repofyr-core` - a component whose job is downloading a `mongod` binary over
  the network and executing it. It moved to `repofyr-embedded-mongo` as
  `io.repofyr.embedded.EmbeddedMongo`, and no `repofyr-server-*` artifact
  depends on it.

  `mongodb.embedded = true` against a standalone server is now rejected at
  startup, naming `repofyr-dev-server` as the replacement, rather than silently
  starting without a database and failing later on a connection timeout. The
  `DB_EMBEDDED` environment variable is gone from the Docker entrypoint; the
  shipped `docker-compose.yml` is unaffected, having always run a real `mongo`
  service.
- Embedded FHIR definitions from the R4 and R5 server modules -
  `conformance-statement-rX.json` and `definitions-rX.json.zip`, 18.3 MB of
  duplicated resources. They now come from the resources-only
  `io.onfhir:onfhir-definitions-r4` and `-r5` artifacts, which carry no
  `_2.13` suffix and resolve at `${onfhir.libs.version}`. Both files still
  load by the same bare classpath name, so nothing changes for an operator.
  If you customize the build, keep the definitions dependency at **compile**
  scope: `provided` or `test` passes every test and then produces a
  standalone jar with no definitions in it, which fails at startup.

### Fixed

- **STU3 summarized responses carry the STU3 code system in the SUBSETTED
  tag.** `FhirSTU3Configurator` overrides
  `FHIR_SUMMARIZATION_INDICATOR_CODE_SYSTEM` to
  `http://hl7.org/fhir/v3/ObservationValue`, but
  `BaseFhirServerConfigurator.initializeServerPlatform` never copied that field
  onto the `FhirServerConfig` that `FHIRServerUtil` reads - it copied the
  sibling `FHIR_*` fields and skipped this one - so the override was inert and
  STU3 emitted the R4-era `terminology.hl7.org` system. An STU3 client matching
  on the tag `system` rather than its `code` will see the corrected value. R4
  and R5 are unaffected: their configured value already equalled the
  `FhirServerConfig` default.

- **STU3 servers start with the packaged classpath defaults.**
  `repofyr-server-stu3` shipped `conformance-statement.json`,
  `definitions.json.zip`, and `db-index-conf.json`, but
  `FhirSTU3Configurator` reports its release as `STU3` and therefore
  derived a `-stu3` suffixed name for all three lookups, so startup threw.
  The names now line up: the first two come from
  `io.onfhir:onfhir-definitions-stu3` as `conformance-statement-stu3.json`
  and `definitions-stu3.json.zip`, and the module's own index
  configuration was renamed to `db-index-conf-stu3.json`. A deployment that
  overrode `fhir.initialization.index-conf-path` to a filesystem path was
  never affected and still is not; a deployment carrying a local workaround
  for this can drop it. The defect predates the split.

### Unchanged (deliberate)

An existing deployment's configuration files, MongoDB database, and Kafka
wire traffic keep working untouched. Every runtime configuration key,
persistence identifier, topic name, and stored-data convention deliberately
keeps its existing name and value: every `application.conf` key, the
`onfhir.subscription` topic and `kafka.client.id = onfhir`, the
`mongodb.db = onfhir` default, the log file paths, the default keystore
password, `ONFHIR_HOME` and `/usr/local/onfhir`, the Docker volume and
service names, the `io.onfhir.path` and `io.onfhir.validation` Logback
logger names, and the operation canonical URLs.

The two logger names are correct as written - they target loggers inside
the library artifacts, which kept their `io.onfhir` packages. Do not "fix"
them to `io.repofyr`.

Kafka event payloads carry no package prefix either: the serializer uses
json4s `ShortTypeHints`, which emits simple class names, so producers and
consumers can be upgraded independently and in any order.

The full list is in
[section 2 of the migration guide](docs/migration/onfhir-3.x-to-repofyr-4.0.md#2-what-did-not-change).
There is no schema change, no config rewrite, no topic rename, and no data
backfill in this upgrade.
