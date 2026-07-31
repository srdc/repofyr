# Library / Server Split Implementation Plan

> Status: superseded 2026-07-31 by
> `docs/plans/library-server-split-plan-v2.md`; historical design context only
> Derived from: license investigation of 2026-07-30 (CRT session): onFHIR is
> GPL-3.0 and links BSL-licensed Akka 2.8.5; CRT/spark-on-fhir need an
> Apache-2.0, Akka-free library family. Decisions confirmed by Tuncay:
> library family splits out under Apache-2.0; server (repofyr) keeps Akka for
> now; working branch is `repository-split` (created 2026-07-31 from master +
> `updating-operation-handling`, `mvn test` green).
> Date: 2026-07-31

## Goal

Separate the nine reusable library modules (onfhir-common, -client, -path,
-query, -config, -expression, -validation, -template-engine, -r4) from the
FHIR server (repofyr) so the libraries can be released to Maven Central under
Apache-2.0 with zero Akka/Pekko dependencies, unblocking the open-source
release chain: onfhir libs -> spark-on-fhir -> CRT (R1, mid-September 2026).

Strategy: **refactor in place first, split physically last.** The risky work
(evicting server code from onfhir-common, replacing akka-http model types,
rewriting onfhir-client) happens while the full reactor and the
onfhir-server-r4 test suite still exist in one build - that suite (14 test
files) is the main regression net, since library-module coverage is thin
(0-3 test files each). The physical split is mechanical once the tree is in
its final shape.

## Refactoring Policy (decided 2026-07-31)

Module membership is decided by **logic and semantics**: a class lives in the
module it conceptually belongs to. Downstream projects (public:
https://github.com/srdc/ignifyr; internal: gitlab.srdc.com.tr) verify and
adapt **afterwards** - their builds are pinned to already-released onfhir
versions, so no dependent breaks at the moment of the refactor.

An earlier position (gate every move behind a downstream usage inventory) was
considered and rejected: it would freeze the current, partly wrong
`onfhir-common` layout and block the Akka removal. The trade-off accepted in
exchange is that the **Migration Table below is mandatory** - it is the only
thing consumers get - and coordinates still do not change (invariant 3).

### The semantic rule for onfhir-common

`onfhir-common` holds what *any* FHIR consumer needs: the FHIR domain model,
spec metadata models, shared abstractions/SPIs, and exceptions. It does not
hold:

- **server runtime**: authorization machinery, audit generation, event bus,
  DB lifecycle, the `application.conf` singleton, HTTP routing/marshalling;
- **client-specific API**: the fluent request builders, which belong to
  `onfhir-client`.

Naming is not evidence of placement. `FhirServerConfig` stays in common
despite its name, because `onfhir-validation` and `onfhir-query` genuinely
need it - it is FHIR capability metadata (which resource types, search
parameters and operations exist), not server runtime.

## Invariants Touched

- Library modules end Akka-free (AGENTS.md invariant 1); enforced by
  `scripts/check-forbidden-imports.ps1`.
- Coordinates (groupId `io.onfhir`, artifactIds, package roots) stay stable
  (invariant 3) - hundreds of downstream files depend on them.
- Public API changes are breaking for consumers (spark-on-fhir imports akka
  `Uri`, `StatusCodes`, `ActorSystem` through onfhir-client's API); every
  change lands in the Migration Table below.
- LICENSE stays GPL-3.0 until the contributor audit completes (invariant 5);
  the relicense is a separate, explicit step.

## Baseline (Phase 0 evidence)

- Trunk `updating-operation-handling`: 21 commits ahead of master, no
  divergence, clean working tree (2026-07-31).
- Forbidden-import baseline (recorded 2026-07-31): **55 total** -
  onfhir-common 31, onfhir-client 22, onfhir-path 1
  (`FhirPathTerminologyServiceFunctions.scala`: akka Uri), onfhir-config 1
  (`BaseFhirServerConfigurator.scala`: akka MediaType). The path/config
  strays compile via transitive Akka (no direct POM dep) and fold into
  Phase 2. Notable Phase 2 detail surfaced by the listing: `onfhir-common`
  hosts client-side request builders (`io.onfhir.api.client.*`) and public
  model classes (`FHIRRequest`, `FHIRResponse`, `FHIROperationResponse`)
  whose signatures carry akka types - these are the consumer-visible API
  breaks to record in the Migration Table.
- Re-confirmed on `repository-split` (2026-07-31): the check script still
  reports exactly 55, same distribution - the master merge did not change the
  Akka surface.
- Full-reactor test baseline: `mvn test` reported green on `repository-split`
  by Tuncay (2026-07-31). Per-module test counts and environment
  prerequisites (embedded Mongo download for server tests) not yet itemized.
- Module file counts (2026-07-31), which is why Phase 1 is scoped the way it
  is: onfhir-common **109** source files vs onfhir-core 85, onfhir-path 20,
  onfhir-validation 15, onfhir-client 13, onfhir-operations 7,
  onfhir-config 6, onfhir-expression 4, onfhir-query 3, onfhir-r4 2,
  onfhir-kafka 2, onfhir-template-engine 1. onfhir-common is a grab-bag: 23
  of its files are client API and ~19 are server runtime.

## Phases

### Phase 0 - Baseline And Environment

- **Files:** AGENTS.md, CLAUDE.md, scripts/check-forbidden-imports.ps1, this
  plan (all created 2026-07-31).
- **Change:** record the two baselines above in this section; fix any test
  that fails on the untouched trunk before starting Phase 1 (a refactor
  cannot be verified against a red baseline).
- **Verify:** `mvn test` green (or failures documented as pre-existing);
  check script output recorded.

### Phase 1 - Put Classes In The Module They Belong To

Scoped from a full usage sweep of onfhir-common's 109 files (2026-07-31).
Three independent steps; each ends with the server-r4 suite green. Expected
effect on the forbidden-import count: onfhir-common **31 -> 13**, entirely by
relocation and one dead-file deletion, with no type substitution (that is
Phase 2's job).

#### Phase 1A - Client request builders -> onfhir-client (23 files, 7 imports)

- **Files:** all of `onfhir-common/src/main/scala/io/onfhir/api/client/`
  (`IOnFhirClient`, `BaseFhirClient`, `FHIRBundle`, `FhirClientException`,
  and the 15 `Fhir*RequestBuilder` classes) -> `onfhir-client`.
- **Why:** these are the client-side fluent API. onfhir-client already exists
  for exactly this, and today has to reach back into common for its own API.
- **Evidence it is clean:** nothing in onfhir-common outside `api/client/`
  references the package (verified by grepping every declared type
  repo-wide). Coupling is strictly one-directional: api/client -> common's
  `api.model`, `api.util.FHIRUtil`, `api.parsers.BundleRequestParser`,
  `exception`, `util` - all of which onfhir-client already depends on.
- **Keep the package name** `io.onfhir.api.client` (invariant 3). This is a
  module move, not a package rename.
- **Consequences to handle:**
  - New POM edge `onfhir-config -> onfhir-client`, because
    `onfhir-config/.../FhirApiConfigReader.scala` uses `IOnFhirClient`.
    Verified acyclic: onfhir-client has zero `io.onfhir.config` imports, and
    onfhir-path's test-scoped dependency on onfhir-client is commented out.
    Resulting graph: config -> client -> path -> common.
  - `io.onfhir.api.client` stays a split package: onfhir-core keeps
    `OnFhirLocalClient` and `OnFhirBulkRequestBuilder` (they import
    `io.onfhir.Onfhir`, `FHIRServiceFactory`, `FhirConfigurationManager`,
    `ErrorHandler` - all core). They currently rely on same-package
    visibility and need explicit `import io.onfhir.api.client._` added.
    onfhir-core already depends on onfhir-client, so no POM change.
  - Two distinct `object FhirClientUtil` exist:
    `io.onfhir.api.client` (in `BaseFhirClient.scala:187`, resource-type/id
    extraction) and `io.onfhir.client.util` (network client factory). They
    will live in the same module after the move - keep both package names as
    they are; do not "tidy" them into one package.
  - `onfhir-server-r4`'s `OnFhirLocalClientTest` (~50 usage sites) is the
    regression net for the builder API.

#### Phase 1B - Server runtime cluster -> onfhir-core (~19 files, 10 imports)

These move as **one group**, because `IFhirServerConfigurator` is the hinge
that holds the whole cluster in common (it imports `db.BaseDBInitializer`,
`audit.IFhirAuditCreator`, plus validation/parser SPIs in one trait).

| From onfhir-common | Evidence |
|---|---|
| `io.onfhir.event` (3 files) | referenced only by onfhir-core + onfhir-kafka; its one library-side consumer is `InternalJsonMarshallers`, itself server-only |
| `io.onfhir.audit` (3 files) | onfhir-core + the three server-r* audit creators only; `audit/package.scala` has exactly one consumer (R5AuditCreator) |
| `io.onfhir.util.InternalJsonMarshallers` | onfhir-kafka + `OnFhirInternalEndpoint` only; exists purely to serialize `io.onfhir.event` types |
| `io.onfhir.db` (`BaseDBInitializer`, `IDBInitializer`) | server-lifecycle SPI (create collections, indexes, sharding). Driver-agnostic (no BSON/mongo types), but reachable from a library module *only* via the hinge trait |
| `config/AuthzConfig`, `config/AuditConfig` | Typesafe `Config` + nimbus OAuth2 + akka MediaType; consumers are core endpoints and the server-r* configurators |
| `config/IFhirServerConfigurator` | implemented only by `BaseFhirServerConfigurator` + the three `Fhir{R4,R5,STU3}Configurator`s; consumed only by `Onfhir.scala` and `FhirConfigurationManager` |
| `api/parsers/FHIRResultParameterResolver` | zero library references; five onfhir-core call sites |
| `api/validation/IResourceSpecificValidator` | zero library references; one onfhir-core implementor, no registration site (near-dead) |
| authz **machinery**: `IAuthorizer`, `ITokenResolver`, `ICustomAuditHandler`, `TokenClient`, `AuthorizationServerMetadata`, `FhirAuthzConstraintRule` | server security runtime; all consumers are onfhir-core endpoints/services and the server-r* audit creators |

- **Stays in common** from the authz package: `AuthContext`, `AuthzContext`,
  `AuthzResult`. They are interaction metadata (who is asking, what was
  decided), and common's own public surface refers to them -
  `FHIROperationRequest.authzContext` and `AuthorizationFailedException(ar)`.
  Splitting the package here (context/result stay, authorizer/resolver/token
  client go) is the semantic line and it avoids surgery on two public types.
  `AuthzResult` imports `FHIRSearchParameterValueParser`, which also stays.
- **`BaseFhirServerConfigurator` decision (blocking 1B):** it lives in
  onfhir-config but its `setupPlatform` creates DB collections and indexes -
  that is server bootstrap, so it follows `IFhirServerConfigurator` to
  onfhir-core. onfhir-config keeps the library-grade pieces:
  `BaseFhirConfigurator` (validation-only config), `BaseConfigReader`,
  `FSConfigReader`, `FhirApiConfigReader`, `SearchParameterConfigurator`.
  `config/IndexConfigurator` (concrete logic misfiled in common) follows its
  only caller, i.e. to onfhir-core with `BaseFhirServerConfigurator`.
- **`FHIRSearchParameterValueParser` split** (as previously planned): parsing
  logic stays in common; the `Directives`/`Directive1` wrapper moves to
  onfhir-core next to the routing code. Add characterization tests BEFORE
  this split - pin current parse results for representative
  search-parameter inputs. This is the one real surgery in Phase 1.

#### Phase 1C - Decouple library modules from the server config singleton

- **Files:** `onfhir-validation/.../ReferenceRestrictions.scala:109`
  (`OnfhirConfig.fhirRootUrl`); `onfhir-r4/.../R4Parser.scala:60-66` (seven
  `OnfhirConfig.fhirDefault*` capability defaults).
- **Why:** `OnfhirConfig` is a `ConfigFactory.load()` singleton reading
  `server.*`, `mongodb.*`, `akka.*`. Today onfhir-validation and onfhir-r4
  cannot be used without a server `application.conf` on the classpath - a
  real defect for library consumers, and the reason the current split "feels
  odd".
- **Change:** pass these eight values in explicitly (constructor parameter or
  a small library-side defaults object). Once no library module reads it,
  `OnfhirConfig` itself becomes a Phase 2 move to onfhir-core.
- **Incidental cleanups (do them here, they are free):** delete the dead
  `onfhir-common/.../api/parsers/XFhirQueryParser.scala` - it declares
  `class FhirQueryParser` (name does not match the file), has zero
  references repo-wide, and the live implementation is
  `onfhir-query/.../expression/XFhirQueryParser.scala`; remove the duplicate
  nested `case class AgentsInfo` at `onfhir-core/.../audit/AuditManager.scala:262`
  which shadows common's.

- **Verify (each step):** `mvn -DskipTests compile` on the reactor; then
  `mvn -pl onfhir-server-r4 -am test`; then the check script - onfhir-common
  should read **24** after 1A, **14** after 1B, **13** after 1C. Record every
  relocation in the Migration Table.

### Phase 2 - Replace akka-http Model Types In onfhir-common

- **Files:** the 13 imports left in onfhir-common after Phase 1, in 9 files -
  `api/api.scala`, `api/model/{FHIRRequest,FHIRResponse,FHIROperationResponse}`,
  `api/parsers/BundleRequestParser`, `api/util/{FHIRUtil,SubscriptionUtil}`,
  `config/FhirServerConfig`, `util/DateTimeUtil` - plus the two strays in
  other library modules (`onfhir-path/FhirPathTerminologyServiceFunctions`:
  akka `Uri`; `onfhir-config/BaseFhirServerConfigurator`: akka `MediaType` -
  note this file moves to onfhir-core in Phase 1B, which resolves it there
  instead). Also move `config/OnfhirConfig` to onfhir-core once Phase 1C has
  removed its library-side readers.
- **Change:** substitute JDK/small-ADT equivalents: `Uri` -> `java.net.URI`
  (watch query-encoding differences), `DateTime` -> `java.time` (watch HTTP
  date formatting in headers), `StatusCode` -> Int or a small ADT,
  media/content types and EntityTag -> small case classes in
  `io.onfhir.api.model`. Where a type appears in a public signature, record
  the old -> new mapping in the Migration Table.
- **Verify:** reactor compile; full test run; check script: onfhir-common = 0.

### Phase 3 - Rewrite onfhir-client On java.net.http

- **Files:** onfhir-client main sources (akka-http client, Materializer,
  ActorSystem); its 3 test files; POM.
- **Change:** replace the transport with `java.net.http.HttpClient` (JDK 11+,
  zero deps). Preserve behavior: basic auth, fixed bearer token, token
  endpoint (client_secret_basic), timeouts, redirects, TLS. Capture
  request/response fixtures from the current implementation first and pin
  them as tests (parity evidence). The public API drops any ActorSystem /
  ExecutionContext requirements from signatures - each is a Migration Table
  entry.
- **Verify:** onfhir-client tests green; reactor green; check script: 0
  findings overall (flip it to blocking); cross-repo smoke: build
  spark-on-fhir against the local snapshot - its four `ActorSystem` files
  and akka `Uri`/`StatusCodes` imports should now be removable (tracked in
  spark-on-fhir, not here).

### Phase 4 - License And Contribution Hygiene

- **Files:** CI workflow (.github/workflows/maven.yml), NOTICE (new),
  CONTRIBUTING.md (new, with DCO), license headers in library modules.
- **Change:** wire check-forbidden-imports into CI as a blocking step; add a
  dependency-license gate for library modules (allowed-list, e.g.
  license-maven-plugin); add DCO sign-off requirement. LICENSE swap to
  Apache-2.0 for the library family happens only when the contributor audit
  is recorded (owner: Tuncay; external consents needed: msfyuksel@gmail.com,
  cam.emre090@gmail.com, gursesyunus@hotmail.com, okanmercan16@gmail.com,
  keremyilmaz499@gmail.com, james@parall.ax; plus EU project IPR check).
- **Verify:** CI run green with both gates active.

### Phase 5 - Physical Split

- **Files:** new repository (recommendation: `srdc/onfhir-libs`; do NOT reuse
  the `srdc/onfhir` name - GitHub's redirect from the rename to repofyr would
  break); new parent POM for the library family; this repo drops the nine
  library modules and depends on released library artifacts.
- **Change:** extract the nine module directories WITH history via
  `git filter-repo` on a dedicated clone (history preservation matters for
  the contributor audit and provenance). Keep groupId `io.onfhir` and
  artifactIds unchanged. Carry over: central-publishing-maven-plugin + GPG
  config, CI, the check scripts, AGENTS.md (adapted). Decide `revision`
  scheme for the first library release (suggestion: continue 3.x to signal
  API continuity, e.g. 4.0.0 if the Phase 2/3 API breaks warrant a major).
- **Verify:** both repos build independently; library repo publishes a
  staging release; server repo builds against it.

### Phase 6 - Consumer Migration (cross-repo, tracked here for sequencing)

- spark-on-fhir: bump onfhir version, remove akka imports/exclusions, drop
  ActorSystem plumbing, release.
- CRT: bump spark-on-fhir + onfhir, de-SNAPSHOT, remove the
  `.codex-tmp-m2` Dockerfile seed hack, quickstart + README (tracked in
  CRT's own launch plan).
- Other internal consumers (tofhir, onfhir-feast, cds, cep, subscription,
  event-pipeline) migrate at their own pace - coordinates are stable, so
  only the version bump plus Migration Table items apply.

## Migration Table (public API changes for consumers)

| Module | Old (Akka-era) | New | Phase |
|---|---|---|---|
| (fill as changes land) | | | |

## Risks

1. **Thin library test coverage.** Mitigation: characterization tests before
   surgery (parser, client fixtures); server-r4 suite after every phase.
2. **`DateTime`/header semantics drift** (akka DateTime vs java.time
   formatting, ETag/If-Modified-Since behavior). Mitigation: pin header
   round-trips in tests during Phase 2.
3. **Client behavioral parity** (auth flows, redirects, timeouts, connection
   reuse). Mitigation: fixture tests captured from the current client before
   the rewrite.
4. **Consumer breakage via leaked types** (spark-on-fhir imports akka types
   through onfhir-client's API). Mitigation: Migration Table + Phase 3
   cross-repo smoke build.
5. **Split before refactor temptation.** Splitting first would strand the
   library refactor without the server test net. The phase order is the
   mitigation; do not reorder.
6. **filter-repo hash rewrite.** Only the new repo gets rewritten history;
   this repo's published history is untouched. Never run filter-repo on this
   working copy.

## Remaining Work

- Phase 0: done (baselines recorded above; itemized per-module test counts
  still missing, not blocking).
- Phases 1-6 as above. Next action: Phase 1A (move
  `io.onfhir.api.client` to onfhir-client), it is the largest
  win for the least risk - 23 files, move-only, one new POM edge.
- Open decisions, each blocking the phase noted:
  - Whether `AuthzContext`/`AuthzResult` stay in onfhir-common while the
    authz machinery moves (plan says yes - avoids surgery on
    `FHIROperationRequest` and `AuthorizationFailedException`) - Phase 1B.
  - Whether `BaseFhirServerConfigurator` is server code (plan says yes, it
    creates DB collections/indexes) - Phase 1B.
  - New repo name (`onfhir-libs` recommended) - Phase 5.
  - First library release version (3.x continuation vs 4.0.0) - Phase 5.
  - Contributor consents + EU IPR check - Phase 4 LICENSE swap.
  - Server (repofyr) license and Pekko question - explicitly out of scope
    here; separate decision document.
