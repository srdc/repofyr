# Library / Server Split Implementation Plan - Version 2

> Status: accepted 2026-07-31; Phase 4 complete; contributor/IP approval recorded; ready for Phase 5A
>
> Supersedes for future implementation:
> `docs/plans/library-server-split-plan.md`
>
> The original plan is retained as historical design context. Execute only one
> phase per working session.

## 1. Goal

Separate the nine reusable onFHIR library modules from the Repofyr server so
that:

- the library family can be released under Apache-2.0 after the contributor
  and project-IP audit is complete;
- library compile and runtime dependency graphs contain no Akka or Pekko;
- Repofyr remains in this repository and can retain Akka and its existing
  license while that separate decision is pending;
- existing `io.onfhir` group IDs, module artifact IDs, and Scala package roots
  remain stable;
- Repofyr and the libraries can build, version, and release independently;
- consumers receive an explicit migration table for every module relocation
  and public-signature change.

The implementation strategy remains: **refactor in place, prove independent
builds, then split physically**. The in-place period preserves the full
reactor and `onfhir-server-r4` endpoint suite as a regression net.

## 2. Scope And Non-Goals

### Library family - target Apache-2.0 repository

- `onfhir-common`
- `onfhir-client`
- `onfhir-path`
- `onfhir-query`
- `onfhir-config`
- `onfhir-expression`
- `onfhir-validation`
- `onfhir-template-engine`
- `onfhir-r4`

### Server family - remains in Repofyr

- `onfhir-event` - deliberately small server-only event SPI retained by the
  Phase 4 decision; the core/Kafka inversion is deferred post-split
- `onfhir-core`
- `onfhir-operations`
- `onfhir-kafka`
- `onfhir-server-r4`
- `onfhir-server-r5`
- `onfhir-server-stu3`

### Non-goals

- Renaming `io.onfhir` packages or existing module artifact IDs.
- Migrating the Repofyr server from Akka to Pekko.
- Changing the Repofyr license.
- Redesigning the Scala `Future` API merely because the HTTP transport changes.
- Migrating downstream repositories in the same code change as the in-place
  refactor.
- Running `git filter-repo` in this working copy.

## 3. Non-Negotiable Invariants

1. No `akka.*` or `org.apache.pekko.*` reference in library production source,
   Java source, resources, direct dependencies, or resolved dependency graphs.
2. `onfhir-common` contains no routing, response marshalling, actor event bus,
   DB lifecycle, or server configuration singleton.
3. Group ID `io.onfhir`, existing module artifact IDs, and `io.onfhir.*`
   package roots do not change.
4. Every module relocation and public API change is recorded in the Migration
   Tables in the same phase that implements it.
5. Library code is not relicensed until contributor and project-IP approval is
   recorded. Repofyr remains GPL-3.0.
6. Cross-repository dependencies never use the consuming repository's
   `${project.version}`. Repofyr uses a dedicated `onfhir.libs.version`.
7. Each phase finishes with its stated gates green before the next phase
   begins.

## 4. Verified Baseline - 2026-07-31

- Working branch: `repository-split`.
- `repository-split` and `updating-operation-handling` currently point to the
  same commit.
- `updating-operation-handling` is currently 21 commits ahead of `master`.
- Forbidden Scala import statements in library `src/main`: 55 total.
  - `onfhir-common`: 31
  - `onfhir-client`: 22
  - `onfhir-path`: 1
  - `onfhir-config`: 1
  - other library modules: 0
- `onfhir-server-r4` contains 14 Scala/Java test files.
- `onfhir-common/src/main/scala/io/onfhir/api/client` contains 22 Scala files,
  including 18 files matching `*RequestBuilder.scala`.
- The library family has no declared dependency edge into a server-family
  module.
- The downstream import analysis in
  `docs/library-consumer-import-impact-analysis.md` found 126 compiled/test
  imports from spark-on-fhir, 21 from onfhir-cds, and 123 direct imports from
  ignifyr/toFHIR. It is part of the Phase 0 ownership evidence.
- The current repository has one root GPL-3.0 `LICENSE`, and the shared Maven
  parent declares GPL-3.0. A library-only root license switch therefore cannot
  be performed safely before the physical split unless every library module is
  given explicit, correctly packaged license metadata.

The 55-import check remains useful as a progress counter, but it is not proof
of a zero-Akka dependency graph. Section 5 defines the complete gates.

## 5. Permanent Verification Gates

### Gate A - production-source scan

Extend `scripts/check-forbidden-imports.ps1` so it scans all production source
types used by the library modules, including Scala and Java. It must reject
both import statements and fully qualified Akka/Pekko references.

### Gate B - resources scan

Reject Akka/Pekko configuration namespaces and class names in library
`src/main/resources`. The Akka client pool block currently in
`onfhir-client/src/main/resources/application.conf` must be removed or replaced
in Phase 3.

### Gate C - resolved dependency graph

Configure Maven Enforcer in the new/shared library build configuration with
`bannedDependencies` for at least:

- `com.typesafe.akka:*`
- `org.apache.pekko:*`

The rule applies transitively. The end-state build must also produce and check
a resolved dependency tree for all nine library modules. Source cleanliness
without dependency cleanliness is a failure.

### Gate D - tests and compilation

During in-place phases:

1. `mvn -DskipTests compile`
2. touched-module tests
3. `mvn -pl onfhir-server-r4 -am test`
4. Gates A-C

Before and after the physical split:

1. library reactor builds independently;
2. Repofyr builds with library source modules absent and only installed or
   staged library artifacts available;
3. the full Repofyr reactor and server-r4 regression suite pass.

### Gate E - compatibility and migration records

Run a Scala-aware binary compatibility report, preferably MiMa, against the
last released library artifacts. Intentional incompatibilities are allowed for
the new major version, but each must have a corresponding Migration Table
entry. Unexpected incompatibilities block the phase.

## 6. Phases

### Phase 0 - Ratify Contracts And Correct The Baseline

**Purpose:** remove ambiguity before moving code.

**Changes:**

- Accept this plan and mark it as the active split plan.
- Correct baseline counts in `AGENTS.md` and any retained planning documents.
- Pre-populate the module-relocation and public-API migration tables.
- Approve the neutral HTTP model proposed in Phase 2A and record it in
  `docs/adr/0001-neutral-http-contract.md`.
- Approve Maven parent and version ownership:
  - Repofyr retains `io.onfhir:fhir-repository_2.13` as its parent during the
    split. A later `io.repofyr` coordinate and package rebrand is a separate
    post-split major migration.
  - The library repository receives the approved parent coordinate
    `io.onfhir:onfhir-libs-parent`.
  - Repofyr receives an `onfhir.libs.version` property.
- Approve `onfhir-event_2.13` as a transitional cycle-breaking boundary for
  the in-place split. Record an ADR stating that the underlying architectural
  debt is `onfhir-core` depending on the optional `onfhir-kafka` adapter. The
  preferred long-term direction removes that edge and lets a top-level server
  bootstrap compose core and Kafka. By the Phase 4 exit, decide whether to
  retain a narrowed event SPI module permanently or eliminate it after
  dependency inversion. Record this decision in
  `docs/adr/0002-transitional-onfhir-event-boundary.md`.
- Use the approved library repository name `srdc/onfhir-libs`; do not reuse
  `srdc/onfhir`, which redirects to Repofyr.
- Record the approved intended first library release as `4.0.0`, subject to final
  release approval. The planned public HTTP-type changes justify a major
  version.

**Exit criteria:** all choices above are recorded; Migration Tables are no
longer empty; baseline commands are reproducible.

#### Phase 0 decision record - approved 2026-07-31

| Decision | Approved outcome |
|---|---|
| active implementation plan | this Version 2 plan |
| library repository | `srdc/onfhir-libs` |
| library parent | `io.onfhir:onfhir-libs-parent` |
| optional future BOM | `io.onfhir:onfhir-libs-bom` |
| intended first library release | `4.0.0` |
| Repofyr parent during split | `io.onfhir:fhir-repository_2.13` |
| Repofyr library version property | `onfhir.libs.version` |
| later Repofyr rebrand | separate post-split major migration to possible `io.repofyr` coordinates and packages |
| transport-neutral HTTP contract | [ADR 0001](../adr/0001-neutral-http-contract.md) |
| transitional event boundary | [ADR 0002](../adr/0002-transitional-onfhir-event-boundary.md) |

#### Phase 0 reproducible baseline

The following results were reconfirmed on `repository-split` on 2026-07-31:

| Check | Result |
|---|---|
| `git rev-list --left-right --count master...updating-operation-handling` | `0 21` |
| `repository-split` versus `updating-operation-handling` | both at `83a47acd9c3aae885b89ae05e8bc3a74a0534205` |
| `scripts/check-forbidden-imports.ps1` | 55 findings: common 31, client 22, path 1, config 1 |
| server-r4 Scala/Java test-file count | 14 |
| common client-package Scala-file count | 22 |
| common client-package request-builder count | 18 |

The forbidden-import script exits nonzero while findings remain; during
Phases 1-3 its output is the recorded progress counter, and zero is the final
pass condition.

### Phase 1A - Move The Client API To onfhir-client

**Scope:** move all 22 Scala files under
`onfhir-common/src/main/scala/io/onfhir/api/client/` to `onfhir-client`, keeping
package `io.onfhir.api.client` unchanged.

**Build changes:**

- Add `onfhir-config -> onfhir-client` because `FhirApiConfigReader` accepts
  `IOnFhirClient`.
- Add explicit client-package imports to `OnFhirLocalClient` and
  `OnFhirBulkRequestBuilder` where same-package visibility previously masked
  them.
- Preserve both existing `FhirClientUtil` objects and their package names.

**Expected import-count effect:**

- `onfhir-common`: 31 -> 24
- `onfhir-client`: 22 -> 29
- library total: remains 55 because this is a semantic relocation, not yet an
  Akka removal.

**Tests:** compile, `onfhir-client` tests, and
`onfhir-server-r4` including `OnFhirLocalClientTest`.

**Exit criteria:** all 22 relocations are recorded; no class remains in the old
directory; package names and public behavior are unchanged.

#### Phase 1A implementation record - completed 2026-08-03

- Moved exactly 22 Scala files from `onfhir-common` to `onfhir-client` with
  their `io.onfhir.api.client.*` packages and file contents unchanged.
- Added the direct `onfhir-config -> onfhir-client` Maven dependency required
  by `FhirApiConfigReader`'s `IOnFhirClient` constructor contract.
- Confirmed the expected forbidden-import redistribution: common 31 -> 24,
  client 22 -> 29, library total unchanged at 55.
- A clean-from-source reactor compile followed by
  `mvn -DskipTests compile`: all 16 reactor modules passed.
- `mvn -pl onfhir-client -am test`: passed; 41 upstream tests passed and the
  client module currently contains no runnable unit tests.
- `mvn -pl onfhir-server-r4 -am test`: passed; 143 tests passed, including 28
  `OnFhirLocalClientTest` cases.

### Phase 1B - Decouple Kafka Construction From OnfhirConfig

This phase handles only the Kafka construction contract. It is a server-side
precondition for the later singleton move and is independently verifiable.

Today `onfhir-core -> onfhir-kafka`; allowing Kafka to reference
`OnfhirConfig` after the singleton moves to core would create a Maven cycle.
Change the Kafka construction contract so core passes in:

- `KafkaConfig`;
- whether FHIR subscriptions are active;
- any other subscription settings required by the producer.

`KafkaEventProducer` and its companion must not import `OnfhirConfig` after
this phase. Do not change library constructors or
`FHIRSearchParameterValueParser` in this phase.

**Verification:** reactor compile, targeted Kafka/core tests if present,
`mvn -pl onfhir-server-r4 -am test`, and:

```powershell
rg -n "OnfhirConfig" onfhir-kafka
```

**Exit criteria:** the command has no matches; Kafka behavior is unchanged;
the reactor remains acyclic and server-r4 is green.

#### Phase 1B implementation record - completed 2026-08-03

- Moved ownership of `new KafkaConfig(OnfhirConfig.config)` into the server
  composition code in `Onfhir`; the Kafka module no longer loads the server
  singleton.
- Changed `KafkaEventProducer` and its `props` factory to receive
  `KafkaConfig`, `FhirServerConfig`, and the FHIR-subscription-active flag
  explicitly.
- Added a Kafka actor characterization suite before changing the production
  constructor, then extended it to cover active and inactive Subscription
  routing plus ordinary resource routing. All 3 final tests pass.
- `rg -n "OnfhirConfig" onfhir-kafka` returns no matches.
- `mvn -DskipTests compile`: all 16 reactor modules passed.
- `mvn -pl onfhir-core -am test`: passed; the core module reports 68 tests
  and the Kafka characterization suite ran in the upstream reactor slice.
- `mvn -pl onfhir-server-r4 -am test`: passed; 255 reactor tests passed,
  including all 143 `onfhir-server-r4` tests and 28
  `OnFhirLocalClientTest` cases.
- The forbidden-import progress counter remains unchanged at 55, as expected
  for this server-only construction change.

### Phase 1C - Characterize And Decouple Library OnfhirConfig Consumers

The server singleton cannot move until every library reference is gone. The
scope is larger than the original eight-value inventory.

#### First action - characterization tests before any parser change

Before changing the constructor, defaults, or implementation of
`FHIRSearchParameterValueParser`, add characterization tests that pin current
behavior. Cover strict and lenient handling, modifiers, prefixes, composites,
chained parameters, malformed inputs, repeated values, and the behavior when
the Prefer handling value is absent.

These tests must be committed or otherwise verified before injecting
`fhirSearchHandling`. Phase 1D may add routing-adapter tests, but it must reuse
this already-established parsing baseline.

Introduce small immutable library-side settings grouped by concern, rather
than a copy of the server singleton. Approved models:

- `FhirEndpointSettings(rootUrl: String)`; preserve the supplied string in
  Phase 1C and reject empty values. URI normalization belongs to Phase 2.
- `FhirRequestDefaults(searchHandling: FhirSearchHandling,
  returnPreference: FhirReturnPreference)`
- `FhirResultDefaults(defaultPageSize: Int,
  paginationMode: FhirPaginationMode,
  totalHandling: FhirSearchTotalHandling)`; page size must be non-negative.
- `FhirSubscriptionSettings(active: Boolean,
  allowedResources: Option[Set[String]])`
- `FhirCapabilityDefaults(versioning: FhirVersioningPolicy, readHistory,
  updateCreate, conditionalCreate, conditionalRead:
  FhirConditionalReadSupport, conditionalUpdate, conditionalDelete:
  FhirConditionalDeleteSupport)`

The closed settings are Scala 2 sealed-trait ADTs. Their legacy wire values
remain unchanged (`handling=strict`, `return=representation`, `page`,
`accurate`, `versioned`, `full-support`, `not-supported`, and alternatives).
Unknown configured values fail fast with `InitializationException` instead of
silently selecting a branch.

Prefer explicit constructor or method parameters where only one value is
needed. The server constructs these values from `OnfhirConfig`; library code
does not load `application.conf`.

| Current library reader | Singleton values to remove | Planned replacement |
|---|---|---|
| `BundleRequestParser` | `fhirRootUrl` | explicit endpoint settings |
| `FHIRSearchParameterValueParser` | `fhirSearchHandling` | parser/request defaults argument |
| `FHIRResultParameterResolver` | `fhirDefaultPageCount`, `fhirDefaultPagination`, `fhirDefaultSearchTotalHandling` | `FhirResultDefaults` argument |
| `FHIRUtil` | `fhirRootUrl`, `fhirDefaultReturnPreference` | endpoint/default arguments; keep pure overloads |
| `ImMemorySearchUtil` | `fhirRootUrl` | explicit comparison context |
| `SubscriptionUtil` | `fhirSubscriptionActive`, `fhirSubscriptionAllowedResources` | subscription settings argument |
| `ReferenceRestrictions` | `fhirRootUrl` | validation context argument |
| `R4Parser` | seven capability defaults | `FhirCapabilityDefaults` argument |
| `TypeRestriction` | unused import only | remove import after verification |

Server-only readers, including audit creation and server configurator logic,
remain temporarily in their current modules and may continue using the
singleton until Phase 1D moves the whole server cluster.

**Hard exit criterion:** this command has no matches outside `onfhir-core` and
the known server-only files scheduled to move in Phase 1D:

```powershell
rg -n "OnfhirConfig" onfhir-common onfhir-client onfhir-path onfhir-query onfhir-config onfhir-expression onfhir-validation onfhir-template-engine onfhir-r4
```

The only permitted matches at this point are:

- `onfhir-common/.../config/OnfhirConfig.scala`
- `onfhir-common/.../audit/IFhirAuditCreator.scala`
- `onfhir-config/.../config/BaseFhirServerConfigurator.scala`

All three are server-only files that Phase 1D moves. There must be no match in a
file that will remain in the library family. Do not move `AuthzConfig` or
`AuditConfig` separately in this phase: `OnfhirConfig` exposes both types, so
all three must move together.

**Expected import-count effect:** none. This phase removes singleton coupling,
not Akka imports.

**Exit criteria:** only the explicit three-file server allow-list reads
`OnfhirConfig`; library behavior, especially search parsing, is pinned with
tests; compile and server-r4 gates pass.

#### Phase 1C implementation record - completed 2026-08-03

- Added and ran 10 `FHIRSearchParameterValueParser` characterization tests
  before changing production code. They pin strict/lenient fallback,
  modifiers, prefixes, composites, forward/reverse chains, malformed values,
  and repeated-value ordering.
- Implemented the approved immutable models and sealed ADTs in
  `FhirRuntimeSettings.scala`; added 5 model-contract tests for legacy-code
  round trips, fail-fast validation, endpoint preservation, page-size
  validation, and historical capability defaults.
- Replaced library singleton reads with explicit settings across search and
  result parsing, subscription handling, bundle URL parsing, resource
  locations/return preferences, in-memory reference matching, validation
  context, and R4/STU3 capability parsing.
- The server composition layer maps legacy `OnfhirConfig` values into the
  typed models. Kafka receives subscription/search settings explicitly and
  still has no `OnfhirConfig` reference.
- Full 16-module reactor compile passed. Focused common tests passed 15/15;
  Kafka construction/routing tests passed 3/3; isolated
  `OnFhirLocalClientTest` passed 28/28. The server-r5 reactor test compilation
  also passed after its `XFhirQueryParser` fixture was migrated.
- The first full server-r4 run passed 141/143 and hit the pre-existing
  one-second timeout in two local-client cases; both passed in the immediate
  isolated rerun. The final complete rerun passed all 143 server-r4 tests,
  including all 28 local-client cases.
- The forbidden-import counter remains the expected 55. The hard singleton
  scan now returns only `OnfhirConfig.scala`, `IFhirAuditCreator.scala`, and
  `BaseFhirServerConfigurator.scala`, the exact Phase 1D server-file
  allow-list.

### Phase 1D - Move Server Runtime Out Of Library Modules

Create the new GPL/server-family module `onfhir-event_2.13`. Move these files
to it while retaining their existing `io.onfhir.*` package names:

- `io.onfhir.event.*`
- `io.onfhir.util.InternalJsonMarshallers`

Both `onfhir-core` and `onfhir-kafka` depend on `onfhir-event`; the event module
depends on `onfhir-common` and the required Akka/JSON libraries. This is a
transitional cycle-breaking layout, not the declaration of a permanent
four-file module design:

```text
onfhir-core -> onfhir-kafka -> onfhir-event -> onfhir-common
            -> onfhir-event
```

Add `onfhir-event` to the root reactor and dependency management. Declare it as
a direct dependency of both core and Kafka; do not rely on the transitive path
through Kafka for core's event API. Add an architecture-debt record for the
inverted `onfhir-core -> onfhir-kafka` edge with this preferred end-state:

```text
server bootstrap -> onfhir-core
server bootstrap -> onfhir-kafka -> event SPI/core contracts
onfhir-core -X-> onfhir-kafka
```

No later than Phase 4, decide whether `onfhir-event` becomes a deliberately
small permanent SPI or is removed when the dependency is inverted.

Move the rest of the cohesive server-runtime cluster to `onfhir-core`,
retaining its existing `io.onfhir.*` package names:

- `io.onfhir.audit.*`
- `io.onfhir.db.*`
- `io.onfhir.config.OnfhirConfig`
- `io.onfhir.config.AuthzConfig`
- `io.onfhir.config.AuditConfig`
- `io.onfhir.config.IFhirServerConfigurator`
- `io.onfhir.config.IndexConfigurator`
- `io.onfhir.api.validation.IResourceSpecificValidator`
- authorization runtime machinery:
  - `IAuthorizer`
  - `ITokenResolver`
  - `ICustomAuditHandler`
  - `TokenClient`
  - `AuthorizationServerMetadata`
  - `FhirAuthzConstraintRule`
- `onfhir-config`'s `BaseFhirServerConfigurator`

`OnfhirConfig`, `AuthzConfig`, and `AuditConfig` move atomically. This avoids an
invalid intermediate build in which `onfhir-common` would reference types that
already live in `onfhir-core`.

Keep `AuthContext`, `AuthzContext`, and `AuthzResult` in `onfhir-common` because
they are request/decision metadata used by common public models and exceptions.

Split `FHIRSearchParameterValueParser` into:

- library parsing logic in `onfhir-common`;
- Akka routing/directive adapter in `onfhir-core`.

Use the parsing characterization suite established at the start of Phase 1C.
Add adapter-specific tests for the relocated Akka directive without postponing
or replacing that behavioral baseline.

Move these reusable query APIs from `onfhir-common` to `onfhir-query`, keeping
package `io.onfhir.api.parsers` unchanged:

- `FhirQueryParser` - rename the source file from `XFhirQueryParser.scala` to
  `FhirQueryParser.scala` while moving it;
- `FHIRResultParameterResolver`.

They are used by spark-on-fhir and are not server-only. `FhirQueryParser` is
not dead externally. The query module already depends on common, and core
already depends on query, so this ownership remains acyclic. Its Akka `Uri`
use is removed in Phase 2.

Remove the duplicate nested `AgentsInfo` in `AuditManager` only after its use
is proven equivalent to the relocated common definition.

**Expected import-count effect after Phase 1D:**

- `onfhir-common`: 24 -> 13
- the stray `onfhir-config` Akka import leaves the library family because
  `BaseFhirServerConfigurator` moves to `onfhir-core`;
- expected library total after Phase 1: 44 (`onfhir-common` 13,
  `onfhir-client` 29, `onfhir-path` 1, `onfhir-query` 1).

**Hard exit criterion:** the `OnfhirConfig` command from Phase 1C returns no
matches anywhere in the nine library modules.

**Exit criteria:** no routing, marshalling, actor event bus, DB lifecycle,
server configuration singleton, or server authorization machinery remains in
`onfhir-common`; the Maven reactor graph is acyclic; compile and server-r4
gates pass.

#### Phase 1D implementation record - completed 2026-08-03

- Added the transitional server-family `onfhir-event_2.13` module to the root
  reactor and dependency management. Core and Kafka both declare it directly;
  the module owns the three `io.onfhir.event` sources and
  `InternalJsonMarshallers` with packages unchanged.
- Moved 18 server-runtime sources from the library family to
  `onfhir-core_2.13`: audit and DB lifecycle code, the singleton/configuration
  cluster, six authorization-runtime types, `IResourceSpecificValidator`, and
  `BaseFhirServerConfigurator`. The unused nested `AuditManager.AgentsInfo`
  duplicate was removed after confirming all consumers use
  `io.onfhir.audit.AgentsInfo`.
- Moved `FhirQueryParser` and `FHIRResultParameterResolver` to
  `onfhir-query_2.13`; packages remain unchanged and the parser source now
  matches its class name.
- Kept pure search parsing in Common and moved the URI/form Akka directives to
  `FHIRSearchParameterValueParserDirectives` in Core. Two adapter tests pin
  URI/form extraction, Prefer propagation, and the legacy reverse ordering of
  repeated form values.
- Removed Common's unused Akka routing/actor/stream, Typesafe Config, and
  Nimbus dependencies. Client now declares its existing ActorSystem and
  Materializer dependencies directly until Phase 3 removes them.
- The library `OnfhirConfig` scan returns no matches. The forbidden-import scan
  has the expected 44 findings: Common 13, Client 29, Path 1, Query 1, and zero
  in the other five library modules.
- Full reactor compile passed for all 17 modules. The touched-module reactor
  passed, including 70 Core tests and both new adapter tests. The server-r4
  reactor passed with 143 server-r4 tests, including all 28 local-client tests.

### Phase 2A - Freeze The Transport-Neutral HTTP Model

No public type substitution begins until an ADR or an accepted section of this
plan fixes the exact types and their wire semantics.

Approved mapping:

| Akka-era type | Proposed neutral type | Required semantics |
|---|---|---|
| `akka.http.scaladsl.model.Uri` | `java.net.URI` | preserve raw path/query and encoding tests |
| `DateTime` | `java.time.Instant` | RFC 7231 formatting/parsing at transport boundaries |
| `StatusCode` | `io.onfhir.api.model.HttpStatus` | integer code plus success/failure classification |
| `HttpMethod` | `io.onfhir.api.model.HttpMethod` | standard and extension methods without Akka |
| `MediaType` | `io.onfhir.api.model.FhirMediaType` | normalized main/subtype and parameters |
| `ContentType` | `io.onfhir.api.model.FhirContentType` | media type plus optional charset |
| `EntityTag` and conditional header wrappers | neutral ETag/date value models | weak tags, wildcard, and list semantics |
| `WWWAuthenticate` | `AuthenticateChallenge` | scheme and parameters without lossy string parsing |
| `X-Forwarded-For` / `X-Forwarded-Host` | neutral forwarded value models or validated strings | preserve multiple-hop values |
| `Uri.Query` used internally | ordered query-pair encoder | duplicates, ordering, and escaping preserved |

Do not use a raw `Int` for status unless all current `StatusCode` classification
behavior is recreated elsewhere. Do not use a generic `Map[String, String]`
where duplicate headers or query parameters are legal.

Add contract tests for status classification, HTTP-date round trips, ETags,
authentication challenges, content types, forwarded headers, and URI/query
encoding before changing implementations.

#### Phase 2A decision record - completed 2026-08-03

- The maintainers explicitly approved ADR 0001 as the complete
  transport-neutral HTTP contract for Phase 2.
- The approved model includes the exact URI/query, HTTP-date, status, method,
  media/content type, conditional header, authentication challenge, forwarded
  value, and repeated-header semantics listed above and in ADR 0001.
- HTTP-date second precision remains limited to HTTP header serialization; it
  does not reduce FHIR timestamp or search precision.
- No production types or signatures changed in Phase 2A. Phase 2B must add the
  required contract tests before substituting each corresponding Akka type.

### Phase 2B - Remove Akka HTTP Models From Common, Path, And Query

Replace the 13 remaining common imports in:

- `api/api.scala`
- `api/model/FHIRRequest.scala`
- `api/model/FHIRResponse.scala`
- `api/model/FHIROperationResponse.scala`
- `api/parsers/BundleRequestParser.scala`
- `api/util/FHIRUtil.scala`
- `api/util/SubscriptionUtil.scala`
- `config/FhirServerConfig.scala`
- `util/DateTimeUtil.scala`

Also replace the `Uri` use in
`onfhir-path/FhirPathTerminologyServiceFunctions.scala`.

Replace the `Uri` use in the relocated
`onfhir-query/.../api/parsers/FhirQueryParser.scala` and pin duplicate query
parameter and encoding behavior.

Remove Akka HTTP, actor, and stream dependencies from `onfhir-common/pom.xml`.
Remove any now-unused transitive support dependencies. Run Gates A-C; the
resolved graph, rather than only the import count, decides completion.

Add server-side adapters in `onfhir-core` that translate between the neutral
models and Akka HTTP. Akka types must not cross back into public library
signatures.

**Exit criteria:** `onfhir-common`, `onfhir-path`, and `onfhir-query` are
source- and dependency-graph clean; all changed signatures are in the Migration
Table; full reactor and server-r4 tests pass.

#### Phase 2B implementation record - completed 2026-08-03

- Added the approved transport-neutral HTTP models in `onfhir-common` for
  status, method, media/content types, entity-tag conditions, authentication
  challenges, forwarded values, repeated headers, and ordered query pairs.
- Replaced Akka HTTP model types in Common, Path, and Query with the neutral
  models, `java.net.URI`, and `java.time.Instant`. HTTP date serialization is
  second-precision; FHIR instant/dateTime serialization retains its original
  precision.
- Added explicit Akka boundary adapters in `onfhir-core` and the transitional
  Akka client implementation. Akka types no longer cross Common/Path/Query
  signatures.
- Removed the direct Akka HTTP dependency from `onfhir-common`. Resolved
  dependency trees for Common, Path, and Query contain no Akka or Pekko
  artifacts; their source and resources contain no Akka/Pekko references.
- Added 15 neutral-model contract tests, five server-adapter tests, two query
  encoding tests, and a Bundle request-target regression test covering literal
  FHIR token separators plus absent and empty query values.
- The forbidden-import scan now reports 26 imports, all in `onfhir-client` and
  reserved for Phase 3 (down from the Phase 1D total of 44).
- `mvn -DskipTests compile`: all 17 reactor modules passed.
- `mvn -pl onfhir-common,onfhir-query,onfhir-core -am test`: passed; Common
  subsequently passed 34 tests after adding the Bundle regression, Query passed
  two tests, and Core passed 75 tests.
- `mvn -pl onfhir-server-r4 -am test`: passed; `onfhir-server-r4` passed all
  143 tests, including 28 `OnFhirLocalClientTest` cases.

### Phase 3 - Rewrite onfhir-client On java.net.http

#### Phase 3 decision record - approved 2026-08-03

- Replace the implicit Akka `ActorSystem` with a caller-supplied implicit
  Scala `ExecutionContext`; the client does not silently use the global
  execution context.
- Use one reusable JDK `HttpClient` per configured client. Authentication
  variants share that transport. `OnFhirNetworkClient` becomes a normal final
  class; case-class `copy` and `unapply` are not retained.
- Interceptors receive an immutable `ClientHttpRequest` containing the neutral
  method, URI, ordered repeated headers, and optional immutable byte body.
  Interceptors remain asynchronous, run sequentially in registration order,
  and short-circuit on failure. Response interception is out of scope.
- Use HTTP/1.1, never follow redirects by default, retain a ten-second connect
  timeout, and make the total request timeout optional with no default. The
  old 60-second Akka idle timeout is not misrepresented as a total timeout.
- Retry at most five times after the initial attempt, only for replayable
  GET, HEAD, OPTIONS, PUT, DELETE, and TRACE requests that fail at the
  transport layer before a response. POST, PATCH, HTTP status responses, and
  interceptor failures are never retried.
- Reuse JDK-managed pooling and explicitly retire the Akka-specific
  `max-connections` and `max-open-requests` settings; JDK 11 exposes no direct
  per-client equivalents.
- Use JVM trust and hostname verification by default and allow an injected
  `SSLContext` for custom trust. Insecure trust-all and hostname-verification
  bypasses are not provided.
- Preserve `client_secret_basic`, `client_secret_post`, and
  `client_secret_jwt`. Token requests use the JDK transport but bypass FHIR
  request interceptors; cached-token refresh is thread-safe and single-flight.
- Preserve the Scala `Future` API. Underlying transport cancellation and
  failures propagate with their causes, but no new caller-cancellation API is
  introduced.
- Preserve current content behavior: JSON and empty responses are supported;
  XML requests and responses fail explicitly. Full XML support is a separate
  feature.
- Replace the remaining public Akka `DateTime` client APIs with
  `java.time.Instant`, and fix `withFixedBasicTokenAuthentication` so it emits
  `Authorization: Basic`, rather than the current erroneous Bearer header.

The verified Phase 3 baseline is 26 forbidden production imports, all in
`onfhir-client`; the earlier estimate of 29 was superseded by Phase 2B's
implemented type substitutions.

Rewrite the client transport using JDK 11 `java.net.http.HttpClient`.

Remove public and internal requirements for:

- `ActorSystem`
- `Materializer`
- Akka `HttpRequest` / `HttpResponse`
- Akka marshalling and unmarshalling

Retain Scala `ExecutionContext` in existing Future-based APIs unless a separate,
explicitly approved API decision assigns executor ownership to the client.
Removing it is not required for Akka removal and would enlarge the migration.

Introduce a transport-neutral `ClientHttpRequest` for the interceptor API, or
approve another exact interceptor contract before implementation. Convert that
model to JDK requests only inside the transport adapter.

Behavioral parity tests must cover:

- JSON/XML bodies and empty responses;
- URI and duplicate query encoding;
- status, location, ETag, last-modified, and authentication headers;
- basic authentication;
- fixed bearer and generic token authentication;
- OAuth token endpoint with `client_secret_basic`;
- request and connect timeouts;
- redirect policy;
- TLS and custom trust configuration currently supported;
- retry behavior, including which methods are safe to retry;
- concurrency/pool limits or an explicitly documented behavior change;
- interceptor ordering and asynchronous failures;
- cancellation and error propagation.

Remove Akka dependencies from `onfhir-client/pom.xml` and remove the Akka HTTP
configuration block from client resources.

**Exit criteria:** all 26 verified client forbidden imports are gone; the whole
library family passes Gates A-C with zero findings; parity tests, reactor tests,
and server-r4 tests pass.

#### Phase 3 implementation record - completed 2026-08-03

- Replaced the Akka HTTP client, marshalling, unmarshalling, actor, stream, and
  materializer code with a reusable JDK 11 `java.net.http.HttpClient`
  transport. The client uses HTTP/1.1, never follows redirects, accepts
  optional request and custom-`SSLContext` settings, and retries only approved
  replayable methods after transport failures.
- Added the immutable `ClientHttpRequest` interceptor contract using Phase 2's
  neutral methods and ordered repeated headers plus an optional immutable byte
  entity. Interceptors run asynchronously and sequentially, and failures stop
  the chain before transport execution.
- Replaced the implicit `ActorSystem` construction contract with an implicit
  caller-owned `ExecutionContext`. `OnFhirNetworkClient` is now a final class
  whose authentication variants share the configured JDK transport.
- Migrated the remaining client `DateTime` APIs to `Instant`; URI, query,
  status, response-header, and operation-path behavior now use the neutral
  models. The fixed-Basic-token helper now correctly sends `Basic`, not
  `Bearer`.
- Reworked OAuth client-credentials retrieval onto the same JDK transport,
  retained basic/post/JWT client authentication, and made cached-token refresh
  thread-safe and single-flight.
- Removed all four Akka dependencies from `onfhir-client/pom.xml`, deleted the
  transitional Akka model adapter, and replaced the `akka.http` resource block
  with `onfhir.client.http` settings.
- Added 14 local JDK-server transport tests covering JSON and empty bodies,
  response metadata, duplicate/encoded queries, literal FHIR operation paths,
  Basic/fixed/OAuth authentication, interceptor ordering and failures,
  redirects, request timeout, safe retry, custom SSL context, and explicit XML
  rejection. `mvn -pl onfhir-client -am test` passed: Common 34, Path 38, and
  Client 14 tests.
- `scripts/check-forbidden-imports.ps1` passes with zero findings across all
  nine library modules. The current-reactor client dependency tree has no
  `com.typesafe.akka` or `org.apache.pekko` artifacts.
- `mvn -DskipTests compile` passed for all 17 reactor modules. The server-r4
  Surefire reports contain all 143 tests with zero failures or errors,
  including all 28 `OnFhirLocalClientTest` cases.

### Phase 3.5 - Harden Library/Server Exception And Subscription Boundaries

Complete this semantic-boundary cleanup before release hygiene or an
independent-build rehearsal. Module ownership is determined by responsibility,
not merely by current downstream usage. Exceptions that encode HTTP response
outcomes belong to the server family; reusable library code reports neutral
domain, parsing, configuration, or client failures.

#### Phase 3.5 decision record - approved 2026-08-03

- Preserve `io.onfhir.exception` package names while moving all ten HTTP
  response exception definitions from Common to Core.
- Introduce neutral `BundleRequestParsingException` in Common. Core maps it to
  HTTP 400; client bundle construction wraps it in `FhirClientException`.
  Client resource/id precondition failures also use `FhirClientException`.
- Replace Common's impossible-state `InternalServerException` uses with
  `IllegalStateException`; keep the HTTP 500 exception only in Core.
- Core owns `SubscriptionUtil` as the release-specific parsing and validation
  contract. `IFhirServerConfigurator` constructs the active implementation,
  and `FhirConfigurationManager` exposes it to server services.
- `onfhir-server-r4` supplies `R4SubscriptionUtil`, including the existing R4
  criteria, channel, status, payload, and update validation rules. R5 and STU3
  use an explicit unsupported implementation until their own mechanisms are
  implemented; they never fall back to R4 behavior.
- Kafka receives only `Resource => FhirSubscription` plus the active flag.
  Core injects the selected parser, avoiding both a Kafka-to-Core cycle and a
  Kafka dependency on a release module.

#### Exception ownership

Move the following server response exceptions from `onfhir-common` to
`onfhir-core`, preserving their `io.onfhir.exception` package names:

- `AuthorizationFailedException`
- `ConflictException`
- `MethodNotAllowedException`
- `NotImplementedException`
- `NotModifiedException`
- `PreconditionFailedException`
- `UnprocessableEntityException`

Refactor the remaining library callers before moving these HTTP-specific
exceptions to `onfhir-core`:

- `BadRequestException`: client-side request construction must use a
  client-specific failure, while reusable bundle parsing must expose a neutral
  parsing/validation failure. Server code maps that failure to HTTP 400.
- `NotFoundException`: reusable bundle parsing must not select HTTP 404;
  server code makes that mapping at the API boundary.
- `InternalServerException`: impossible states in common search utilities use
  `IllegalStateException` or an approved neutral library exception. HTTP 500
  remains a server mapping.

Keep `InitializationException`, `InvalidParameterException`,
`UnsupportedParameterException`, and `InvalidParamRequest` in the library
family because they represent reusable configuration, parsing, or model
failures rather than HTTP response outcomes.

Add characterization tests for the current exception-to-`FHIRResponse`
mapping and for each affected reusable caller before changing exception types.
Record every artifact relocation and public thrown-exception change in the
Migration Tables and reconcile it with MiMa during Phase 4.

#### Version-specific subscription ownership

`SubscriptionUtil` is server runtime behavior and moves from `onfhir-common`
to `onfhir-core`, preserving its package name during the split. Core owns the
subscription orchestration facade and a release-specific strategy contract;
the active FHIR version wiring supplies the strategy without making core or
Kafka depend on a concrete FHIR release module.

The existing behavior is explicitly characterized as the R4 subscription
model and mechanism. Extract that behavior behind the core contract as the R4
implementation supplied by `onfhir-server-r4`. Do not treat it as a release-
neutral implementation or reuse it silently for R5: FHIR R5 changed both the
Subscription resource model and its mechanism, so an R5 implementation must
be supplied separately by `onfhir-server-r5` when R5 subscription support is
implemented.

Remove direct construction of `SubscriptionUtil` from `onfhir-kafka`. Core
must provide the release-appropriate parsed/validated subscription behavior to
Kafka through an injected contract or normalized server-owned value. Approve
the exact strategy and construction signatures in the Phase 3.5 decision
record before implementation, then add them to the Migration Tables.

**Exit criteria:** no HTTP-status-specific exception is defined in or thrown
by a library-family production module; `SubscriptionUtil` is absent from
library-family artifacts; R4 subscription characterization tests and the
exception mapping tests pass; core and Kafka contain no compile-time edge to
`onfhir-server-r4` or another release module; all permanent gates, the full
reactor compile, targeted tests, and the server-r4 regression suite pass.

#### Phase 3.5 implementation record - completed 2026-08-03

- Moved all ten HTTP response exceptions from Common to Core without changing
  their `io.onfhir.exception` packages. Common bundle parsing now reports
  `BundleRequestParsingException`; Core maps it to HTTP 400, Client wraps it in
  `FhirClientException`, and Common impossible states use
  `IllegalStateException`.
- Replaced Common's concrete subscription utility with a Core-owned
  `SubscriptionUtil` contract. R4 supplies `R4SubscriptionUtil`; R5 and STU3
  explicitly select `UnsupportedSubscriptionUtil` until release-specific
  support is implemented. Kafka receives only the selected parser function
  and activation flag.
- Added characterization and boundary coverage for server exception mapping,
  neutral bundle parsing, client exception translation, R4 subscription
  parsing/validation, and Kafka parser injection. The focused suites passed
  13 tests with zero failures or errors.
- The forbidden-import gate, library HTTP-exception scan, library
  `SubscriptionUtil` scan, server-release dependency scan, and
  `git diff --check` all passed. A clean 17-module reactor compile succeeded.
- `mvn -pl onfhir-server-r4 -am test` passed all 146 tests with zero failures
  or errors, including all 28 `OnFhirLocalClientTest` cases.

### Phase 3.6 - Move In-Memory FHIR Search Execution To Query

`ImMemorySearchUtil` evaluates parsed FHIR search parameters against Json4s
resources, and `InMemoryPrefixModifierHandler` implements the corresponding
FHIR prefix and modifier semantics. These are query-execution responsibilities,
not Common primitives, and belong in `onfhir-query`.

#### Phase 3.6 decision record - approved 2026-08-03

- Move both objects from Common to Query while preserving their existing
  `io.onfhir.api.util` package names and public object names.
- Keep `ImMemorySearchUtil` as the public execution facade. Core must not call
  `InMemoryPrefixModifierHandler` directly; missing-parameter evaluation is
  routed through the facade so the helper can be narrowed separately later.
- Do not combine the ownership move with correction of the historical
  `ImMemorySearchUtil` spelling. A correctly spelled replacement and any
  deprecation alias require a separate public-API decision.
- Add characterization tests in Common and run them before relocating the
  implementation; relocate those tests to Query with the production files.

**Exit criteria:** neither object is present in the Common source or compiled
artifact; Query owns both implementations and their characterization tests;
Core uses only the execution facade; the relocation and artifact dependency
impact are recorded in the Migration Tables; Query/Common focused tests, a
clean reactor compile, permanent gates, and the server-r4 regression suite
pass.

#### Phase 3.6 implementation record - completed 2026-08-03

- Added seven characterization tests in Common before changing production
  ownership. They pin string modifiers, numeric prefixes, implicit date
  ranges, token matching, local reference normalization, missing values, and
  restricted-path extraction.
- Moved `ImMemorySearchUtil` and `InMemoryPrefixModifierHandler`, unchanged in
  package and public name, from Common to Query. The characterization suite
  moved with them and remained green; all nine Query tests pass.
- Core's `ResourceChecker` now delegates missing-parameter evaluation through
  `ImMemorySearchUtil`, so it no longer calls the helper directly. The complete
  Query/Core reactor slice passed, including all 78 Core tests.
- A clean 17-module reactor compile proved both classes absent from Common's
  compiled artifact and present in Query's artifact. The full server-r4 suite
  passed all 146 tests with zero failures or errors.
- The forbidden-import gate, Common source/artifact ownership checks, Core
  direct-helper scan, and `git diff --check` all passed.

### Phase 4 - Release Hygiene And Independent-Build Rehearsal

This phase prepares licensing and release mechanics but does **not** replace the
monorepo root GPL license.

**Changes:**

- Wire Gates A-C into CI as blocking jobs.
- Add dependency-license allow-list enforcement for library modules.
- Complete and record the contributor and EU/project-IP audit.
- Add DCO/CONTRIBUTING requirements.
- Produce NOTICE content and proposed Apache headers, but apply the new root
  license only in the extracted library repository after approval.
- Add MiMa compatibility reporting and reconcile every intended break with the
  Migration Table.
- Create or update a README for each of the nine library modules. Every module
  README must briefly describe its purpose, scope and non-goals, Maven
  coordinates, principal public APIs, relationships to other onFHIR modules,
  and a minimal usage example.
- Give standalone or tool-like modules, especially `onfhir-path`,
  `onfhir-query`, `onfhir-validation`, and `onfhir-template-engine`, expanded
  usage documentation covering supported functionality, configuration,
  limitations, and runnable examples. Document `onfhir-common` explicitly as
  a foundational module rather than a destination for unrelated utilities.
- Update the library root README with a module catalog and guidance for
  selecting only the artifacts a consumer needs.
- Rehearse the split in a disposable clone or temporary directory:
  1. construct the proposed library parent and library-only reactor;
  2. install the library artifacts to an isolated local Maven repository;
  3. construct the proposed Repofyr reactor without library source modules;
  4. build Repofyr only against those installed artifacts.

**Exit criteria:** audit approval is recorded or release is explicitly blocked;
all nine library modules have the required README content; tool-like module
examples and intra-repository documentation links are verified in the
library-only layout; both rehearsed builds are green; no cross-repository
dependency uses `${project.version}`.

#### Phase 4 implementation record - completed 2026-08-03

- Expanded the permanent PowerShell boundary gate to scan Scala and Java
  production sources for imports and fully qualified references and to scan
  production resources for Akka/Pekko namespaces or class names. Added the
  transitive Maven Enforcer rule to each library module and wired Gates A-C as
  blocking CI work.
- Added a dependency-license allow-list check. The aggregate production graph
  contains 33 external dependencies with an approved permissive license path;
  the missing ANTLR 3.3 POM metadata is explicitly reviewed as BSD-3-Clause.
  No monorepo license was changed.
- Added DCO contribution guidance, proposed NOTICE/header content, and the
  contributor/IP audit record. The maintainer confirmed that all ten human
  identity groups are current or former SRDC employees and that SRDC has
  relicensing authority. Apache-2.0 extraction is approved for Phase 5.
- Added MiMa reporting against public version 3.3, an accepted deterministic
  baseline, and issue-family reconciliation with the migration tables. Query
  and Template Engine are recorded as new artifacts; Expression is binary
  compatible; the intentional major-version breaks are documented.
- Added `onfhir.libs.version` and changed every library-family dependency edge
  to use it. Server-family dependencies continue to use `${project.version}`.
  This makes the server parent ready for independent library versioning.
- Added or updated all nine module READMEs and a root module catalog. The four
  tool-like modules document functionality, configuration, limitations, and
  examples. All local documentation links resolve; the library test reactor
  passed 98 tests with zero failures or errors, including an executable
  Template Engine README example.
- Recorded the approved permanent disposition of `onfhir-event_2.13` as a
  deliberately small server-only SPI. The `onfhir-core -> onfhir-kafka`
  inversion remains explicit post-split architectural debt.
- Added a disposable split-rehearsal script. The generated
  `io.onfhir:onfhir-libs-parent` reactor installed all nine libraries to an
  isolated Maven repository; a source-free Repofyr library boundary then
  compiled and ran all 146 server-r4 tests against those artifacts with zero
  failures or errors.
- The forbidden source/resource scan, transitive Enforcer gate, license gate,
  README link check, library reactor, isolated server-r4 suite, and
  `git diff --check` passed. The approval required before Phase 5 relicensing
  is recorded in `docs/release/library-relicensing-audit.md`.

### Phase 5A - Physical Split, Licensing, And Staging Release

Perform history extraction using `git filter-repo` only in a dedicated clone.
Never run it in this working copy.

Approved local layout:

```text
C:\srdc\codes\onfhir-io\
|-- onfhir\       existing Git working copy; becomes the Repofyr repository
`-- onfhir-libs\  new filtered Git repository for the reusable libraries
```

Create the filtered history in a disposable clone under `C:\tmp` before
placing the validated result in the sibling `onfhir-libs` folder. The current
working copy and its `.git` directory remain the Repofyr repository. Do not
push a repository or publish artifacts without separate authorization.

#### New library repository

- Recommended repository: `srdc/onfhir-libs`.
- Include the nine library module histories and the build/support files needed
  for a standalone reactor.
- Create parent `io.onfhir:onfhir-libs-parent`.
- Preserve every existing module artifact ID exactly, including the unsuffixed
  `onfhir-template-engine` artifact.
- Add an optional `io.onfhir:onfhir-libs-bom` for consumers that use several
  modules.
- After audit approval, set root `LICENSE` to Apache-2.0, add `NOTICE`, update
  POM license metadata, and package license/notice files in published JARs.
- Stage the approved `4.0.0` release and verify its published POMs and resolved
  dependency graphs.

#### Repofyr repository

- Retain parent `io.onfhir:fhir-repository_2.13` and GPL-3.0 metadata.
- Remove the nine library source modules from its reactor.
- Add `onfhir.libs.version` and use it for every library dependency.
- Build against the staged library artifacts using an isolated Maven local
  repository.

**Exit criteria:** both repositories build independently from fresh checkouts;
library staging contains correct licenses, sources, Javadocs/ScalaDocs, POMs,
and signatures; Repofyr server-r4 tests pass against staged artifacts.

### Phase 5B - Repofyr Maven And Package Namespace Migration

Start this only after Phase 5A is green. The reusable libraries retain the
`io.onfhir` group, existing artifact IDs, and `io.onfhir.*` packages. Rename
only server-owned Repofyr code and artifacts; this is not a global text
replacement because Repofyr continues to import `io.onfhir` library APIs.

Approved Maven mapping:

| Existing server coordinate | Repofyr coordinate |
|---|---|
| `io.onfhir:fhir-repository_2.13` | `io.repofyr:repofyr-parent` |
| `io.onfhir:onfhir-event_2.13` | `io.repofyr:repofyr-event_2.13` |
| `io.onfhir:onfhir-core_2.13` | `io.repofyr:repofyr-core_2.13` |
| `io.onfhir:onfhir-operations_2.13` | `io.repofyr:repofyr-operations_2.13` |
| `io.onfhir:onfhir-kafka_2.13` | `io.repofyr:repofyr-kafka_2.13` |
| `io.onfhir:onfhir-server-r4_2.13` | `io.repofyr:repofyr-server-r4_2.13` |
| `io.onfhir:onfhir-server-r5_2.13` | `io.repofyr:repofyr-server-r5_2.13` |
| `io.onfhir:onfhir-server-stu3_2.13` | `io.repofyr:repofyr-server-stu3_2.13` |

Rename module directories to match the new artifact names. Inventory every
server-owned type before changing packages, then move it from `io.onfhir.*` to
the corresponding `io.repofyr.*` namespace. Keep reusable contracts such as
neutral HTTP models, FHIR configuration models, client APIs, FHIRPath, query,
validation, and R4 library parsers under `io.onfhir.*`.

Update Scala imports, reflection and plugin class names,
`OperationDefinition.name` implementation paths, application resources,
tests, fixtures, documentation, and migration tables. Audit event/serialization
payloads for persisted or transmitted fully qualified class names.

The first release under both new version lines is `4.0.0`:

- reusable libraries: `io.onfhir:*:4.0.0`;
- Repofyr server: `io.repofyr:*:4.0.0`;
- Repofyr declares `onfhir.libs.version=4.0.0`.

The matching initial number does not couple future releases; the library and
server versions may diverge after `4.0.0`. The old `io.onfhir` server artifact
line ends at 3.x; version 4 server artifacts use `io.repofyr` coordinates.

Keep existing `onfhir.*` runtime configuration keys, MongoDB collection names,
FHIR URLs, persistence identifiers, and other stored-data conventions during
Phase 5B. Any such operational namespace migration requires a later,
compatibility-focused phase.

**Exit criteria:** all server modules use the approved `io.repofyr` Maven
coordinates and server-owned packages; no reusable library coordinate or
package changed; reflection/configuration references resolve; migration
guidance covers every public server move; Repofyr builds only against staged
`io.onfhir:*:4.0.0` artifacts; all server-r4 tests pass.

### Phase 6 - Consumer Migration And Release Chain

1. Update spark-on-fhir to library `4.0.0`, apply the Migration Table, remove
   leaked Akka client plumbing, run its complete build, and release it.
2. Update CRT to released spark-on-fhir and onFHIR library versions, remove
   SNAPSHOT/local-Maven workarounds, and run its launch verification.
3. Migrate other public and internal consumers on their own release schedules.
4. Record discovered migration omissions back in this plan and release notes.

## 7. Pre-Populated Migration Tables

These rows are planned contracts. Each implementation phase replaces
`planned` with the actual released signature or artifact relationship.

### 7.1 Module relocations

| Public package/type | Old artifact | New artifact | Package change | Phase | Status |
|---|---|---|---|---|---|
| `io.onfhir.api.client.*` - all 22 files | `onfhir-common_2.13` | `onfhir-client_2.13` | none | 1A | implemented 2026-08-03 |
| `io.onfhir.event.*` | `onfhir-common_2.13` | `onfhir-event_2.13` | none | 1D | implemented 2026-08-03 |
| `io.onfhir.util.InternalJsonMarshallers` | `onfhir-common_2.13` | `onfhir-event_2.13` | none | 1D | implemented 2026-08-03 |
| `io.onfhir.audit.*` | `onfhir-common_2.13` | `onfhir-core_2.13` | none | 1D | implemented 2026-08-03 |
| `io.onfhir.db.*` | `onfhir-common_2.13` | `onfhir-core_2.13` | none | 1D | implemented 2026-08-03 |
| server authz runtime types listed in Phase 1D | `onfhir-common_2.13` | `onfhir-core_2.13` | none | 1D | implemented 2026-08-03 |
| `AuthzConfig`, `AuditConfig`, `IFhirServerConfigurator`, `IndexConfigurator` | `onfhir-common_2.13` | `onfhir-core_2.13` | none | 1D | implemented 2026-08-03 |
| `BaseFhirServerConfigurator` | `onfhir-config_2.13` | `onfhir-core_2.13` | none | 1D | implemented 2026-08-03 |
| `IResourceSpecificValidator` | `onfhir-common_2.13` | `onfhir-core_2.13` | none | 1D | implemented 2026-08-03 |
| `OnfhirConfig` | `onfhir-common_2.13` | `onfhir-core_2.13` | none | 1D | implemented 2026-08-03 |
| `FhirQueryParser` | `onfhir-common_2.13` | `onfhir-query_2.13` | none | 1D | implemented 2026-08-03 |
| `FHIRResultParameterResolver` | `onfhir-common_2.13` | `onfhir-query_2.13` | none | 1D | implemented 2026-08-03 |
| all ten HTTP response exceptions listed in Phase 3.5 | `onfhir-common_2.13` | `onfhir-core_2.13` | none | 3.5 | implemented 2026-08-03 |
| `io.onfhir.api.util.SubscriptionUtil` contract | `onfhir-common_2.13` | `onfhir-core_2.13` | none | 3.5 | implemented 2026-08-03 |
| `io.onfhir.api.util.ImMemorySearchUtil` | `onfhir-common_2.13` | `onfhir-query_2.13` | none | 3.6 | implemented 2026-08-03 |
| `io.onfhir.api.util.InMemoryPrefixModifierHandler` | `onfhir-common_2.13` | `onfhir-query_2.13` | none | 3.6 | implemented 2026-08-03 |

Consumers relying on transitive availability from `onfhir-common` must add a
direct dependency on the new owning artifact where applicable.

### 7.2 Public HTTP and client signatures

| Old API/type | Planned replacement | Phase |
|---|---|---|
| `new FHIRSearchParameterValueParser(FhirServerConfig)` | `new FHIRSearchParameterValueParser(FhirServerConfig, FhirSearchHandling)` | 1C |
| `new FhirQueryParser(FhirServerConfig)` | `new FhirQueryParser(FhirServerConfig, FhirSearchHandling)` | 1C |
| `new XFhirQueryParser(FhirServerConfig, FhirPathEvaluator)` | `new XFhirQueryParser(FhirServerConfig, FhirSearchHandling, FhirPathEvaluator)` | 1C |
| `new FHIRResultParameterResolver(FhirServerConfig)` | `new FHIRResultParameterResolver(FhirServerConfig, FhirResultDefaults)` | 1C |
| `new SubscriptionUtil(FhirServerConfig)` | `new SubscriptionUtil(FhirServerConfig, FhirSubscriptionSettings, FhirSearchHandling)` | 1C |
| `BundleRequestParser.parseBundleRequest(bundle, prefer, skip)` | `parseBundleRequest(bundle, FhirEndpointSettings, prefer, skip)` | 1C |
| `BundleRequestParser.parseBundleRequestEntry(entry)` | `parseBundleRequestEntry(entry, FhirEndpointSettings)` | 1C |
| `BundleRequestParser.parseUrl(uri)` | `parseUrl(uri, FhirEndpointSettings)` | 1C |
| `FHIRRequest.initializeTransactionOrBatchRequest(resource, prefer)` | `initializeTransactionOrBatchRequest(resource, FhirEndpointSettings, prefer)` | 1C |
| `FHIRUtil.resourceLocation(type, id)` | `resourceLocation(FhirEndpointSettings, type, id)` | 1C |
| `FHIRUtil.resourceLocationWithVersion(type, id, version)` | `resourceLocationWithVersion(FhirEndpointSettings, type, id, version)` | 1C |
| `FHIRUtil.getResourceContentByPreference(resource, prefer)` | `getResourceContentByPreference(resource, prefer, FhirReturnPreference)` | 1C |
| `ImMemorySearchUtil.handleSimpleParameter(parameter, config, values)` | adds `FhirEndpointSettings` | 1C |
| `ImMemorySearchUtil.handleCompositeParameter(parameter, config, values, configs)` | adds `FhirEndpointSettings` | 1C |
| `new FhirContentValidator(..., resourceValidator)` | optional local endpoint in the validator context; server factory receives `FhirEndpointSettings` | 1C |
| `IFhirVersionConfigurator.getFoundationResourceParser(complex, primitive)` | `getFoundationResourceParser(complex, primitive, FhirCapabilityDefaults)` | 1C |
| `new R4Parser(complex, primitive)` | optional third `FhirCapabilityDefaults` argument; historical `Standard` remains the direct-constructor default | 1C |
| `FHIRSearchParameterValueParser.parseSearchParametersFromUri/Entity(...)` | `new FHIRSearchParameterValueParserDirectives(parser).parseSearchParametersFromUri/Entity(...)` in `onfhir-core_2.13` | 1D |
| `FHIRRequest` Akka `HttpMethod`, content type, ETag, date, and forwarded fields | `HttpMethod`, `FhirContentType`, `EntityTagCondition`, `Instant`, `ForwardedFor`, and `ForwardedHost` | 2B - implemented 2026-08-03 |
| `FHIRResponse` / `FHIROperationResponse` Akka `StatusCode`, `Uri`, `DateTime`, and `WWWAuthenticate` fields | `HttpStatus`, `java.net.URI`, `Instant`, and `AuthenticateChallenge` | 2B - implemented 2026-08-03 |
| `FHIRResponse.errorResponse(StatusCode, ...)` and authorization helpers using Akka challenges | helpers using `HttpStatus` and `AuthenticateChallenge` | 2B - implemented 2026-08-03 |
| FHIR media and content constants/configuration using Akka `MediaType` / `ContentType` | `FhirMediaType` / `FhirContentType` | 2B - implemented 2026-08-03 |
| `BundleRequestParser.parseUrl(akka.http.scaladsl.model.Uri, FhirEndpointSettings)` | `parseUrl(java.net.URI, FhirEndpointSettings)` | 2B - implemented 2026-08-03 |
| `DateTimeUtil.parseHttpDateToDateTime(String): akka.http.scaladsl.model.DateTime` | `parseHttpDate(String): Instant`; compatibility method now also returns `Instant` | 2B - implemented 2026-08-03 |
| `FHIRUtil` status/date signatures using Akka `StatusCode` / `DateTime` | signatures using `HttpStatus` / `Instant` | 2B - implemented 2026-08-03 |
| Path and query internals using Akka `Uri.Query` | `OrderedQuery`, preserving order, duplicates, encoding, and absent versus empty values | 2B - implemented 2026-08-03 |
| `IFhirAuditCreator` Akka `StatusCode` contract | `HttpStatus` | 2B - implemented 2026-08-03 |
| `OnFhirNetworkClient(...)(implicit ActorSystem)` | constructor/factory with caller-owned implicit `ExecutionContext` | 3 - implemented 2026-08-03 |
| `FhirClientUtil(...)(implicit ActorSystem)` | factory with caller-owned implicit `ExecutionContext` | 3 - implemented 2026-08-03 |
| interceptor using Akka `HttpRequest` | interceptor using approved `ClientHttpRequest` | 3 - implemented 2026-08-03 |
| existing Future methods with implicit `ExecutionContext` | retained | 3 - implemented 2026-08-03 |
| `OnFhirNetworkClient` case-class `copy` / `unapply` surface | normal final class with factories and shared JDK transport | 3 - implemented 2026-08-03 |
| `FhirReadRequestBuilder.ifModifiedSince(DateTime)` | `ifModifiedSince(Instant)` | 3 - implemented 2026-08-03 |
| `FHIRHistoryBundle` history timestamps as Akka `DateTime` | history timestamps as `Instant` | 3 - implemented 2026-08-03 |
| `BearerTokenInterceptorFromTokenEndpoint.getToken: Option[String]` | asynchronous `getToken(): Future[String]` with single-flight refresh | 3 - implemented 2026-08-03 |
| `akka.http.client` and `akka.http.host-connection-pool` configuration | `onfhir.client.http` connect timeout, optional request timeout, and retry settings | 3 - implemented 2026-08-03 |
| `BundleRequestParser` throwing `BadRequestException` / `NotFoundException` | `BundleRequestParsingException`; Core maps to HTTP 400 and Client wraps in `FhirClientException` | 3.5 - implemented 2026-08-03 |
| `BaseFhirClient` missing resource type/id throwing `BadRequestException` | `FhirClientException` | 3.5 - implemented 2026-08-03 |
| Common impossible states throwing `InternalServerException` | `IllegalStateException`; HTTP 500 exception remains in Core | 3.5 - implemented 2026-08-03 |
| concrete `new SubscriptionUtil(config, settings, handling)` | release-specific `SubscriptionUtil` obtained from `IFhirServerConfigurator.getSubscriptionUtil(...)` | 3.5 - implemented 2026-08-03 |

### 7.3 Build and version contracts

| Old contract | New contract | Phase |
|---|---|---|
| one parent `fhir-repository_2.13` for both families | Repofyr parent plus new `onfhir-libs-parent` | 5 |
| all internal dependencies use `${project.version}` | library-family edges use `${onfhir.libs.version}`; server-family edges retain `${project.version}` | 4 - implemented 2026-08-03 |
| one monorepo revision | independently versioned library and server releases | 5 |

### 7.4 Server construction contracts

| Old contract | New contract | Phase | Status |
|---|---|---|---|
| `KafkaEventProducer.props(FhirServerConfig)` plus companion-owned `KafkaConfig` loaded from `OnfhirConfig` | `KafkaEventProducer.props(KafkaConfig, FhirServerConfig, Boolean)` with all values supplied by `Onfhir` | 1B | implemented 2026-08-03 |
| `new KafkaEventProducer(KafkaConfig, FhirServerConfig)` with subscription activation read from `OnfhirConfig` | `new KafkaEventProducer(KafkaConfig, FhirServerConfig, Boolean)` | 1B | implemented 2026-08-03 |
| Phase 1B `KafkaEventProducer(..., Boolean)` subscription flag | `KafkaEventProducer(..., FhirSubscriptionSettings, FhirSearchHandling)` | 1C | implemented 2026-08-03 |
| `new ResourceChecker(FhirServerConfig)` | `new ResourceChecker(FhirServerConfig, FhirEndpointSettings)` | 1C | implemented 2026-08-03 |
| common-owned `SubscriptionUtil` instantiated directly by core and Kafka | core-owned contract selected by `IFhirServerConfigurator`; R4 implementation supplied by `onfhir-server-r4`; Kafka receives an injected neutral parser function | 3.5 | implemented 2026-08-03 |

## 8. Risks And Mitigations

1. **Hidden server singleton coupling.** Mitigation: exhaustive
   `OnfhirConfig` inventory in Phase 1C and a zero-reference Phase 1D gate.
2. **False Akka-free result.** Mitigation: source, resource, Maven Enforcer,
   and resolved-graph gates.
3. **HTTP semantic drift.** Mitigation: approve the neutral model first and
   pin header/date/URI round trips before substitution.
4. **Client transport parity gaps.** Mitigation: fixture plus behavioral tests
   for authentication, retries, timeouts, redirects, TLS, and concurrency.
5. **Accidental API expansion.** Mitigation: retain `ExecutionContext` and
   treat unrelated async redesign as separate work.
6. **Independent version resolution errors.** Mitigation: dedicated
   `onfhir.libs.version` and isolated two-reactor rehearsal.
7. **Mixed-license ambiguity.** Mitigation: keep the monorepo root GPL;
   apply Apache-2.0 only to the new library repository after audit approval.
8. **Incomplete migration guidance.** Mitigation: pre-populated tables, MiMa,
   and same-change updates.
9. **History rewrite damage.** Mitigation: filter only a dedicated clone and
   preserve the original Repofyr history.
10. **FHIR release-specific subscription drift.** Mitigation: core owns only
    orchestration and the strategy contract; each server release module owns
    and tests its Subscription model and mechanism implementation.

## 9. Final Definition Of Done

The split is complete only when all of the following are true:

- the nine library modules build from a fresh standalone checkout;
- their production sources, resources, and complete dependency graphs contain
  no Akka or Pekko;
- the library artifacts have stable existing coordinates and an independent
  parent/version;
- Apache-2.0 relicensing approval is recorded and published artifacts contain
  correct `LICENSE` and `NOTICE` metadata;
- Repofyr retains its server modules, GPL metadata, and existing server parent;
- Repofyr builds without library source directories and uses
  `onfhir.libs.version` for staged/released library artifacts;
- `onfhir-server-r4` tests pass against those external artifacts;
- every expected binary break and module move is documented;
- spark-on-fhir and CRT complete the planned release-chain smoke tests;
- no history-filtering command was run against the original working copy.

## 10. Next Action

Phase 3.6 is complete. In the next dedicated working session, begin only Phase
4: add release-hygiene automation and rehearse the library-only build and
repository extraction without changing the monorepo's GPL license or filtering
the original working copy.
