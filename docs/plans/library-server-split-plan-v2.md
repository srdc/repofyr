# Library / Server Split Implementation Plan - Version 2

> Status: accepted 2026-07-31; Phase 0 complete; Phase 1A in progress
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

- `onfhir-event` - transitional server-only cycle-breaking module introduced
  in Phase 1D; its permanent status is decided before the physical split
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
than a copy of the server singleton. Proposed models:

- `FhirEndpointSettings(rootUrl: String)`
- `FhirRequestDefaults(searchHandling: String, returnPreference: String)`
- `FhirResultDefaults(pageCount: Int, paginationMode: String, totalHandling: String)`
- `FhirSubscriptionSettings(active: Boolean, allowedResources: Option[Set[String]])`
- `FhirCapabilityDefaults(...)` for the seven R4 capability defaults

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

### Phase 2A - Freeze The Transport-Neutral HTTP Model

No public type substitution begins until an ADR or an accepted section of this
plan fixes the exact types and their wire semantics.

Proposed mapping:

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

### Phase 3 - Rewrite onfhir-client On java.net.http

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

**Exit criteria:** all 29 expected client forbidden imports are gone; the whole
library family passes Gates A-C with zero findings; parity tests, reactor tests,
and server-r4 tests pass.

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
- Rehearse the split in a disposable clone or temporary directory:
  1. construct the proposed library parent and library-only reactor;
  2. install the library artifacts to an isolated local Maven repository;
  3. construct the proposed Repofyr reactor without library source modules;
  4. build Repofyr only against those installed artifacts.

**Exit criteria:** audit approval is recorded or release is explicitly blocked;
both rehearsed builds are green; no cross-repository dependency uses
`${project.version}`.

### Phase 5 - Physical Split, Licensing, And Staging Release

Perform history extraction using `git filter-repo` only in a dedicated clone.
Never run it in this working copy.

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
- Stage the proposed `4.0.0` release and verify its published POMs and resolved
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

| Public package/type | Old artifact | New artifact | Package change | Phase |
|---|---|---|---|---|
| `io.onfhir.api.client.*` - all 22 files | `onfhir-common_2.13` | `onfhir-client_2.13` | none | 1A |
| `io.onfhir.event.*` | `onfhir-common_2.13` | `onfhir-event_2.13` | none | 1D |
| `io.onfhir.util.InternalJsonMarshallers` | `onfhir-common_2.13` | `onfhir-event_2.13` | none | 1D |
| `io.onfhir.audit.*` | `onfhir-common_2.13` | `onfhir-core_2.13` | none | 1D |
| `io.onfhir.db.*` | `onfhir-common_2.13` | `onfhir-core_2.13` | none | 1D |
| server authz runtime types listed in Phase 1D | `onfhir-common_2.13` | `onfhir-core_2.13` | none | 1D |
| `AuthzConfig`, `AuditConfig`, `IFhirServerConfigurator`, `IndexConfigurator` | `onfhir-common_2.13` | `onfhir-core_2.13` | none | 1D |
| `BaseFhirServerConfigurator` | `onfhir-config_2.13` | `onfhir-core_2.13` | none | 1D |
| `IResourceSpecificValidator` | `onfhir-common_2.13` | `onfhir-core_2.13` | none | 1D |
| `OnfhirConfig` | `onfhir-common_2.13` | `onfhir-core_2.13` | none | 1D |
| `FhirQueryParser` | `onfhir-common_2.13` | `onfhir-query_2.13` | none | 1D |
| `FHIRResultParameterResolver` | `onfhir-common_2.13` | `onfhir-query_2.13` | none | 1D |

Consumers relying on transitive availability from `onfhir-common` must add a
direct dependency on the new owning artifact where applicable.

### 7.2 Public HTTP and client signatures

| Old API/type | Planned replacement | Phase |
|---|---|---|
| Akka `Uri` in library signatures | `java.net.URI` | 2 |
| Akka `DateTime` in library signatures | `java.time.Instant` | 2 |
| Akka `StatusCode` | `io.onfhir.api.model.HttpStatus` | 2 |
| Akka `HttpMethod` | `io.onfhir.api.model.HttpMethod` | 2 |
| Akka `MediaType` / `ContentType` | neutral FHIR media/content models | 2 |
| Akka conditional/header types | neutral ETag/date/forwarded models | 2 |
| Akka `WWWAuthenticate` | `AuthenticateChallenge` | 2 |
| `OnFhirNetworkClient(...)(implicit ActorSystem)` | constructor/factory without `ActorSystem` | 3 |
| `FhirClientUtil(...)(implicit ActorSystem)` | factory without `ActorSystem` | 3 |
| interceptor using Akka `HttpRequest` | interceptor using approved `ClientHttpRequest` | 3 |
| existing Future methods with implicit `ExecutionContext` | retained unless separately approved | 3 |

### 7.3 Build and version contracts

| Old contract | New contract | Phase |
|---|---|---|
| one parent `fhir-repository_2.13` for both families | Repofyr parent plus new `onfhir-libs-parent` | 5 |
| all internal dependencies use `${project.version}` | Repofyr cross-repo edges use `${onfhir.libs.version}` | 5 |
| one monorepo revision | independently versioned library and server releases | 5 |

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

Phase 0 is complete. In the next dedicated working session, begin only Phase
1A:

1. update the Status header to `Phase 1A in progress`;
2. rerun and retain the Phase 0 forbidden-import baseline;
3. move exactly the 22 client API Scala files from `onfhir-common` to
   `onfhir-client` without changing their packages;
4. add the planned `onfhir-config -> onfhir-client` dependency and update the
   corresponding Migration Table row with the implemented result;
5. run the Phase 1A compile, client, forbidden-import, and server-r4 gates.

Do not include Phase 1B Kafka construction changes in that session.
