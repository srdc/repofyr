# Repofyr 4.0.0 Release Readiness Plan

Status: in progress - 2026-08-07. Executes on branch `oss-release`.

Completed so far, none of it committed yet:

- **Phase 0** - baseline recorded: 251 tests green (core 78, server-r4 144,
  server-r5 21, kafka 4, server-stu3 4; event and operations have none), full
  reactor 2m30s.
- **Phase 1** - items 1-6 applied. Item 7 (embedded MongoDB scope) is
  deliberately NOT applied and remains an open decision; its analysis was
  corrected after an error was found in the original draft.
- **Phase 2** - `docs/migration/onfhir-3.x-to-repofyr-4.0.md` written (9
  sections, 522 lines), the 10 internal and library-scoped documents purged,
  and ADR 0002's naming preamble folded into its text. Four places where this
  plan's source material disagreed with the code were found and resolved in
  the code's favour; they are listed under "Corrections found during
  execution" below.
- **Phase 3** - complete. README gained Modules, Documentation, and License
  sections, a migration pointer, a dependency snippet, and corrected Docker
  instructions; `CHANGELOG.md`, `SECURITY.md`, and
  `docs/release/known-limitations.md` created; `CONTRIBUTING.md` rewritten; the
  stale `db-index-conf` comments fixed in three shipped config files.
- **Phase 4** - complete. `RELEASING.md`, both gate scripts, `settings.json`,
  and the four skills (verify, release-stage, bump-libs, new-operation) exist,
  and `AGENTS.md` / `CLAUDE.md` were rewritten from split-execution contracts
  into maintenance contracts. `check-server-boundary.ps1` was verified in both
  directions: it passes on a clean tree and exits 1 with all three checks
  firing against an injected violation of each.
- **Phase 5** - complete. Three CI jobs (reactor tests, boundary gate, Windows
  compile plus gate) and dependabot. `server-build` stays red until the
  libraries are on Central; that is expected, not a workflow defect.
- **Phase 7** - module READMEs and type-level scaladoc on the public extension
  surface.

Verified at the end of the session: `mvn -B test` green at **251 tests**, the
same total and the same per-module split as the Phase 0 baseline, and
`check-server-boundary.ps1` PASS. Every document is ASCII-only and every
relative link resolves, except one reference inside
`library-server-split-plan-v2.md` to the ADR deleted in Phase 2 - that file is
itself deleted in Phase 8, so the dangling link is transient.

Outstanding: **Phase 6** (test coverage) and **Phase 8** (retire the plans, cut
the release), plus the Phase 1 item 7 decision.

### Work done outside this plan

Two maintainer-directed changes landed after Phase 7 and are not phases of this
plan. Both are complete and verified.

- **Configuration settings grouped and adopted.** `onfhir-libs` 4.0.0 gained
  `fromConfig` companions for the typed runtime settings (planned and executed
  in that repository). Repofyr adopted them, added its own server-owned groups
  in `io.repofyr.config.ServerRuntimeSettings` (`ServerSettings`,
  `SslSettings`, `InternalApiSettings`, `MongoDbSettings`,
  `MongoDbPoolingSettings`, `FhirInitializationSettings`, `BulkSettings`), and
  then **eliminated every per-key accessor**: `OnfhirConfig` went from 62
  public members to 18. `fhir.search-handling` moved to
  `fhir.default.search-handling` with a deprecated-key fallback. 18 new tests.
- **The STU3 SUBSETTED fix** described below.

Reactor total is now **270 tests**, up from the 251 baseline.

### A permission-model gap worth knowing about

`.claude/settings.json` deny rules match by prefix, so `Bash(mvn deploy:*)`
does **not** match `mvn -B -Prelease deploy` - the exact command that uploads
to Maven Central. Denying that prefix instead would also block the legitimate
local staging command, since deny beats allow and the two share a prefix. Only
content inspection distinguishes them, so the rule lives in a PreToolUse hook,
`.claude/hooks/block-remote-publish.sh`, which denies a Maven deploy lacking
`-DaltDeploymentRepository` plus the Central-publishing, nexus-staging, and
`docker push` forms. It was pipe-tested against nine cases (five allow, four
deny) before being wired in.

The same gap exists in `srdc/onfhir-libs`, whose deny list this was modelled
on. Worth closing there too.

## Bugs found during execution

1. **STU3 emitted the R4-era code system in the SUBSETTED tag - FIXED
   2026-08-07.** `FhirSTU3Configurator` overrides
   `FHIR_SUMMARIZATION_INDICATOR_CODE_SYSTEM`, but
   `BaseFhirServerConfigurator.initializeServerPlatform` copied the sibling
   `FHIR_*` fields and skipped this one, so the override was inert. Fixed on
   maintainer instruction rather than deferred; the missing assignment was
   added, `FhirSTU3ConfiguratorTest` now asserts the propagated value, and one
   `FHIRReadEndpointTest` assertion pins the R4 literal - the existing R4
   assertions compare the response against the config and so hold for any
   value. Retired from known-limitations, recorded in `CHANGELOG.md` and in
   section 7 of the migration guide, since it changes STU3 output.

The remaining item below changes user-visible behavior and stays deferred; it
is recorded in `docs/release/known-limitations.md`.
2. **PATCH is not authorized against its result.**
   `authorizeAgainstGivenContent` returns `true` unconditionally for PATCH
   (`AuthzManager.scala:194`, with a pre-existing TODO). The stored resource
   *is* checked, so this is narrower than it looks, but a caller can still
   patch a resource into a state a full UPDATE would reject. Security-adjacent
   and a real design decision, not a typo - entry 2 in known-limitations.

Also noted, no action taken: `repofyr-server-stu3` depends on
`repofyr-server-r4` although no STU3 source references an R4 type. The
dependency is how `repofyr-operations` reaches the classpath, but it also drags
in the R4 parser and definitions. Worth a dependency-hygiene pass later.

## Corrections found during execution

Recorded because the split plan remains the historical record and these four
entries in it are wrong. The code was authoritative in every case.

1. **`KafkaEventProducer.props` signature.** Split plan section 7.4 rows 1-3
   describe intermediate Phase 1B/1C states that row 5 superseded, implying a
   final `(KafkaConfig, FhirServerConfig, FhirSubscriptionSettings,
   FhirSearchHandling)`. The released signature is
   `props(kafkaConfig: KafkaConfig, fhirSubscriptionActive: Boolean,
   parseFhirSubscription: Resource => FhirSubscription)` - no `FhirServerConfig`
   parameter at all. The migration guide documents the real one.
2. **`io.onfhir.stu3.parsers` did not move.** It appears in section 7.5's
   seventeen-package prefix-rewrite table, but the section 7.3 follow-up at
   line 1523 reverses it and the code agrees with 7.3: no
   `io/repofyr/stu3/parsers` exists, and `FhirSTU3Configurator` imports
   `io.onfhir.stu3.parsers.STU3Parser` from `onfhir-stu3_2.13`. A consumer
   following the raw table would break their build, so the migration guide's
   table row states the exception inline rather than only in prose beneath it.
3. **Default keystore password is a constant, not a config key.** Section 7.3
   lists it among unchanged configuration. It is
   `SSLConfig.DEFAULT_KEYSTORE_PASSWORD`; `application.conf` ships
   `server.ssl.password = null`. The claim holds, the location does not.
4. **MongoDB database name.** Section 7.3 cites `onfhir-test`, which is a test
   value. The production default is `mongodb.db = onfhir`.
5. **There is no `onfhir.*` configuration key namespace.** The split plan, and
   this plan quoting it, described "the `onfhir.*` key tree in
   `application.conf`" as the thing deliberately left unchanged. No such tree
   exists: `grep '^onfhir' over every shipped .conf returns nothing, and the
   top-level sections are `server`, `fhir`, `akka`, `kafka`, and `mongodb`.
   What actually carries the legacy naming is a set of values and identifiers -
   the `onfhir.subscription` topic value, `kafka.client.id = onfhir`,
   `mongodb.db = onfhir`, and the `akka.actor.onfhir-blocking-dispatcher`
   dispatcher name. This mattered because the wrong version had reached
   user-facing text: it was corrected in the migration guide's section 2 table,
   the root README's rebranding callout, and `AGENTS.md` invariant 4.

`onfhir-libs` 4.0.0 is **not yet released** to Maven Central. That does not
block this work - the reactor builds against the signed staging repository
already in the local Maven cache - but it does gate three specific things,
listed under "Release gate" below. Everything else proceeds now.

This plan takes the Repofyr server reactor from "split complete, builds green"
to "publishable 4.0.0 with a maintainable agentic environment". It replaces
`docs/plans/library-server-split-plan-v2.md` as the active plan; that document
is retired in Phase 8 once its still-live content has been relocated.

Execute one phase at a time. Keep this Status header current. Phases 1-5 are
sequential; Phase 6 and Phase 7 are independent of each other and may run in
either order once Phase 4 lands.

## Context carried forward

The invariants from `AGENTS.md` continue to hold and are not renegotiated here:

1. Repofyr retains GPL-3.0 metadata.
2. Server artifacts are `io.repofyr:repofyr-*` in `io.repofyr.*` packages; the
   reusable libraries keep `io.onfhir` coordinates and packages. Never rename a
   library coordinate, package, or import from this reactor.
3. Every reusable-library dependency uses `onfhir.libs.version`, never
   `${project.version}`.
4. Runtime configuration keys, persistence identifiers, and stored-data
   conventions stay stable. This is why `onfhir.*` config keys, the
   `onfhir.subscription` Kafka topic, `ONFHIR_HOME`, and the
   `io.onfhir.path` / `io.onfhir.validation` logger names are deliberately
   unchanged and must not be "cleaned up" during this work.
5. Scripts stay ASCII-only for Windows PowerShell 5.1 compatibility.

## Decisions already taken

- **Repository name.** `srdc/onfhir` has been renamed to `srdc/repofyr` on
  GitHub; the old URL redirects. The local `origin` remote was repointed to
  `https://github.com/srdc/repofyr.git` on 2026-08-07 and verified with
  `git ls-remote`. The root `pom.xml` `<scm>` block already names
  `srdc/repofyr` and needs only the syntax fix in Phase 1.
- **Branch and trunk.** All of this work happens on `oss-release`, branched
  from `repository-split` on 2026-08-07. When the plan is complete and
  `onfhir-libs` 4.0.0 is published, **`master` is updated to this branch** and
  becomes trunk for the new release line.

  The lineage is linear, with no divergence anywhere:

  ```
  master (2117d0f, 2026-07-17)
    +21 commits -> updating-operation-handling  (pushed; the code this builds on)
    +9  commits -> oss-release                  (unpushed; 4.0.0 split work)
  ```

  `master` is a direct ancestor of `oss-release` and `git rev-list HEAD..master`
  is empty, so the update is a **fast-forward** - no merge commit, no
  conflicts, and no force-push. Verified 2026-08-07; re-verify before merging
  in case anything lands on `master` in the meantime.

  `updating-operation-handling` was the working trunk during the split and its
  21 commits keep their published SHAs - the 2026-08-07 rewrite touched only
  the 9 commits after it. Once `master` is updated, that branch is redundant
  and can be retired. `repository-split` is retained untouched as a fallback
  until the merge lands, then may be deleted.
- **Commit attribution.** The 9 commits unique to this line were rewritten on
  `oss-release` to drop their `Co-Authored-By: Claude` trailers; author,
  committer, dates, and trees are unchanged. This was safe only because none of
  those commits had been pushed. Do not attempt the same on `srdc/onfhir-libs`,
  whose history is published - rewriting it would violate the standing rule
  against rewriting published history.
- **Library consumption.** The server consumes `io.onfhir:*:4.0.0` from Maven
  Central via `onfhir.libs.version`. No `<repositories>` block is added to the
  reactor.
- **Documentation split.** Library-level API changes are documented in
  `srdc/onfhir-libs` (`docs/migration/3.x-to-4.0.0.md`). This repository
  documents only the server-side migration and cross-links rather than
  duplicating.

## Release gate - blocked on the onfhir-libs 4.0.0 publish

Three items cannot complete until `io.onfhir:*:4.0.0` is on Maven Central.
Everything else in this plan is independent of it.

1. **Fresh-cache resolution proof.** Until the libraries are public, the
   reactor only builds because the signed staging artifacts sit in the local
   Maven cache. Once published, prove independence with a throwaway repository
   so a stale cache cannot mask a failure:

   ```bash
   mvn -B -Dmaven.repo.local=C:/tmp/repofyr-precheck -DskipTests compile
   ```

2. **Phase 5 (CI).** The `server-build` job cannot pass before this - a hosted
   runner has no local cache. Write the workflow during Phase 5 if convenient,
   but expect red until the publish, and do not debug that redness as a
   workflow fault.
3. **Phase 8 (release cut).** Setting `revision` to `4.0.0` and staging a
   signed release is meaningless while a dependency is unpublished.

The merge of `oss-release` into `master` should happen after item 1 passes.

## Open decisions

- **Embedded MongoDB packaging** (Phase 1, item 7). Recommendation stated
  there; confirm or reject.
- **DCO sign-off backfill.** See "Commit hygiene" below.

## Commit hygiene - DCO sign-off

`CONTRIBUTING.md` requires every commit to carry a `Signed-off-by` trailer
certifying the Developer Certificate of Origin 1.1. Only 2 of the 30 commits
ahead of `master` have one; 9 of those 30 are the unpushed commits on
`oss-release` and could still be amended.

This is a maintainer action, not an agent action. A `Signed-off-by` trailer is
a legal certification that the signer has the right to submit the work - only
the human author can make that attestation, so no agent should add one on
their behalf. If you want the unpushed 9 backfilled, run it yourself:

```bash
git filter-branch --msg-filter 'cat; grep -q "^Signed-off-by:" || echo "\nSigned-off-by: Tuncay Namli <tuncay@srdc.com.tr>"' -- updating-operation-handling..HEAD
```

The 21 already-published commits cannot be fixed this way. If strict DCO
coverage matters for the OSS release, the practical options are to state in
`CONTRIBUTING.md` that the requirement applies from 4.0.0 forward, or to
squash-merge `oss-release` into `master` as a single signed-off commit.
Recommendation: the former - it is honest, and it keeps the split's commit
history readable.

---

## Phase 0 - Baseline

Establish the numbers every later phase is measured against. The Maven Central
check is deliberately *not* here - see "Release gate" above.

1. On branch `oss-release`, working tree clean, and `git log` reviewed for work
   from parallel sessions.
2. Full reactor green against the locally cached 4.0.0 libraries:

   ```bash
   mvn -B test
   ```

Record in the Phase 0 completion note: the total reactor test count and the
per-module counts. Phase 6 adds tests and needs to show it added them without
changing any existing result; Phase 1 changes the build and needs to show it
changed nothing.

---

## Phase 1 - Build metadata and release safety

Small, mechanical, and touches the published artifact - so it goes first.

### Changes

1. **`pom.xml:71`** - `<developerConnection>` is malformed. It mixes URL scheme
   with scp syntax: `scm:git:ssh://github.com:srdc/repofyr.git`. Replace with
   `scm:git:ssh://git@github.com/srdc/repofyr.git`.

2. **`pom.xml:303-304`** - the `release` profile sets `<autoPublish>true</autoPublish>`
   and `<waitUntil>published</waitUntil>`, so a single `mvn -Prelease deploy`
   with credentials present publishes irreversibly to Maven Central. Change to
   `<autoPublish>false</autoPublish>` and `<waitUntil>validated</waitUntil>`,
   matching `onfhir-libs`. Promotion becomes a deliberate act in the Central
   portal, documented in `RELEASING.md`.

3. **Java 11 target enforcement.** `maven.compiler.source/target=11` only
   governs Java compilation, and this reactor has no Java sources - so nothing
   currently prevents Scala from emitting bytecode against JDK 17 APIs while CI
   builds on JDK 17. Add `-release` / `11` to the `scala-maven-plugin` `<args>`
   in the root `pom.xml` `pluginManagement` block, and switch the properties to
   `<maven.compiler.release>11</maven.compiler.release>` for correctness.
   Verify the full reactor still compiles; if a source genuinely needs a
   post-11 API, stop and report rather than reverting the flag silently.

   The `<args>` block already exists at `pom.xml:146-149` with `-deprecation`
   and `-feature`; add to it rather than creating a second one.

4. **Package `LICENSE` into artifacts.** GPL-3.0 distribution should carry the
   license text inside the jar. Add a `maven-resources-plugin` execution to the
   root build, mirroring `onfhir-libs`:

   - id `package-license-files`, phase `process-resources`, goal `copy-resources`
   - output `${project.build.outputDirectory}/META-INF`
   - resource directory `${maven.multiModuleProjectDirectory}`, including
     `LICENSE`

   No `NOTICE` file is added: NOTICE is an Apache-2.0 convention, and this
   reactor has none. Verify with
   `jar tf repofyr-core/target/repofyr-core_2.13-4.0.0-SNAPSHOT.jar | grep META-INF/LICENSE`.

5. **Stale module names in poms.**
   - `repofyr-core/pom.xml` `<name>` is "Core functionality of onFHIR" ->
     "Repofyr core server runtime".
   - `repofyr-operations/pom.xml` `<name>` is "onFHIR operations" ->
     "Repofyr FHIR operation handlers".

   Add a one-line `<description>` to each of the seven module poms while here;
   they currently inherit only the parent description, which makes all seven
   published POMs describe the same thing.

6. **Untrack the machine-local git config.** `.codex-safe-gitconfig` is tracked
   and hardcodes `C:/srdc/codes/onfhir-io/onfhir`, a path that is now wrong for
   anyone including its author. `git rm --cached .codex-safe-gitconfig` and add
   it to `.gitignore`.

7. **Embedded MongoDB dependency scope** (decision). `de.flapdoodle.embed.mongo`
   is declared without a scope - therefore compile - in
   `repofyr-core/pom.xml:136-140`, its version managed at `pom.xml:548-552`,
   because `io.repofyr.db.EmbeddedMongo` lives in `src/main`. It ships inside
   the production standalone jar and propagates transitively to every consumer
   embedding `repofyr-core`.

   Correction to an earlier draft of this item, which claimed marking the
   dependency `<optional>true</optional>` would leave the standalone jar
   unaffected. **That is wrong.** `optional` suppresses transitive propagation
   to *every* consumer, and the `repofyr-server-*` modules are consumers of
   `repofyr-core` - so flapdoodle would drop off their classpath, the shade
   plugin would not bundle it, and `embedded = true` would fail at runtime in
   the standalone jar. Any option here must be verified by inspecting the
   shaded jar, not reasoned about from scope rules alone.

   The three real options:

   - **A - `optional` plus explicit re-declaration.** Mark it optional in
     `repofyr-core`, then declare `de.flapdoodle.embed.mongo` explicitly at
     compile scope in each of the three `repofyr-server-*` modules, and at
     test scope wherever tests call `EmbeddedMongo`. Consumers embedding
     `repofyr-core` as a library stop inheriting it; the standalone jars keep
     working and keep their current weight. Low risk, but it does not shrink
     the artifact - it only stops the leak.
   - **B - remove it from `src/main`.** Move `EmbeddedMongo` to test sources or
     a separate dev-only module. This is the only option that actually shrinks
     the production artifact, and it removes `embedded = true` as a production
     capability - a breaking change for anyone using it.
   - **C - do nothing** for 4.0.0 and record it in
     `docs/release/known-limitations.md`.

   Recommendation: **A**, verified by confirming flapdoodle classes are still
   present in `repofyr-server-r4/target/repofyr-server-standalone.jar` and that
   `mvn -pl repofyr-server-r4 -am test` still starts the harness. B is the
   better end state but is a breaking change that does not belong in a release
   whose headline is "we renamed things and changed nothing else".

   This item is unresolved and was deliberately NOT applied during the
   2026-08-07 execution of Phase 1; items 1-6 were.

### Verification

```bash
mvn -B test
```

Test count matches Phase 0. `git status` shows only intended changes.

### Definition of done

Seven items above applied (or item 7 explicitly deferred with a recorded
reason), reactor green, and no change to any runtime configuration key.

---

## Phase 2 - Server migration guide, then docs purge

Write the replacement before deleting the source material.

### Step 2.1 - Write `docs/migration/onfhir-3.x-to-repofyr-4.0.md`

Audience: someone upgrading from `io.onfhir:onfhir-server-*:3.x` to
`io.repofyr:repofyr-server-*:4.0.0`. Source material is
`docs/plans/library-server-split-plan-v2.md` sections 7.1 (lines 1405-1449),
7.3 (1504-1524), 7.4 (1526-1534), and 7.5 (1536-1615).

Deliberately **out of scope**: sections 7.1 rows describing moves *between
library artifacts* and all of section 7.2 (library API and HTTP signature
changes). Those are already documented in
`srdc/onfhir-libs` -> `docs/migration/3.x-to-4.0.0.md`, whose section 3.3
covers "server artifacts moved to Repofyr" from the other direction. Link to
it; do not restate it.

Required structure:

1. **Overview** - the monorepo split into two independently versioned
   families; `io.onfhir` 3.x server artifacts have no 4.x successor; the server
   line continues as `io.repofyr` and stays GPL-3.0 while the libraries are
   Apache-2.0.
2. **What did not change** - put this early, because it is the section that
   saves operators the most work. Source: the "deliberately unchanged" row at
   plan line 1520. Cover `onfhir.*` configuration keys, the
   `akka.actor.onfhir-blocking-dispatcher` name, Kafka topic
   `onfhir.subscription` and `kafka.client.id`, MongoDB database names, log
   file paths, the keystore default, `ONFHIR_HOME` and the
   `/usr/local/onfhir` container paths, Docker volume and service names, the
   `io.onfhir.path` / `io.onfhir.validation` Logback logger names, and the
   fact that json4s `ShortTypeHints` emits simple class names so event payloads
   carry no package prefix. State plainly: an existing deployment's
   configuration, database, and Kafka traffic keep working untouched.
3. **Maven coordinates** - the seven `io.onfhir:onfhir-*` server artifacts to
   `io.repofyr:repofyr-*_2.13`, with a table. Include the `repofyr-event`,
   `repofyr-operations`, and `repofyr-kafka` rows even though most consumers
   only name a server module.
4. **Scala package renames** - reproduce the two tables from plan section 7.5:
   the 17 server-only packages that a consumer may rewrite by prefix, and the
   16 split packages where one import line must become two because the library
   types of the same package name stayed in `io.onfhir.*`. Lead with the rule
   ("a server-owned type moves from `io.onfhir.X` to `io.repofyr.X`, keeping
   its simple name; every reusable library type keeps `io.onfhir.*`") so the
   tables are a reference rather than the explanation.
5. **Operation dispatch** - `DEFAULT_IMPLEMENTED_FHIR_OPERATIONS` moved from
   `io.onfhir.api` in `onfhir-common_2.13` to
   `io.repofyr.operation.DefaultOperationHandlers` in `repofyr-core_2.13`. Call
   out explicitly that any `OperationDefinition.name` or server configuration
   naming `io.onfhir.operation.*` handler classes is a **string** that must be
   updated to `io.repofyr.operation.*` - this is the failure mode a compiler
   cannot catch. Operation URLs are unchanged, including
   `http://onfhir.io/fhir/OperationDefinition/import`.
6. **Server construction contracts** - the five rows of plan section 7.4:
   `KafkaEventProducer.props`, the `KafkaEventProducer` constructor,
   `ResourceChecker`, and `SubscriptionUtil` now being obtained from
   `IFhirServerConfigurator.getSubscriptionUtil(...)` rather than constructed.
7. **Packaging and deployment** - `onfhir-server-standalone.jar` ->
   `repofyr-server-standalone.jar`; `<mainClass>` entries now
   `io.repofyr.{r4,r5,stu3}.Boot`; container images `srdc/onfhir:{r4,r5}` ->
   `srdc/repofyr:{r4,r5}`. Note that the R4 and R5 server modules no longer
   embed FHIR definitions - they resolve `io.onfhir:onfhir-definitions-r4`/`-r5`
   - and that STU3 startup was fixed in this release (the packaged
   `conformance-statement-stu3.json`, `definitions-stu3.json.zip`, and
   `db-index-conf-stu3.json` names now match what `FhirSTU3Configurator`
   derives; a pre-4.0.0 STU3 server relying on packaged defaults threw at
   startup).
8. **Upgrade recipe** - an ordered, mechanical checklist: bump coordinates,
   prefix-rewrite the 17 packages, hand-split the 16, update handler class
   strings in OperationDefinitions, rename the jar in deployment scripts,
   repoint image tags, rebuild. Note that configuration and data need no
   migration.
9. **Getting help** - link the libs migration guide, the changelog, and the
   issue tracker.

### Step 2.2 - Purge internal documents

Delete, with `git rm`:

- `docs/spark-on-fhir-imports.txt`, `docs/onfhir-cds-imports.txt`,
  `docs/ignifyr-imports.txt` - raw IntelliJ "Find Usages" exports, 762 lines of
  UI framing text.
- `docs/library-consumer-import-impact-analysis.md` - its conclusions are
  spent; it also lists modules that no longer exist here.
- `docs/compatibility/mima-3.3-accepted.txt`,
  `docs/compatibility/mima-3.3-reconciliation.md` - library-scoped; the
  authoritative copies live in `onfhir-libs`, which owns the MiMa gate. This
  reactor has no binary-compatibility gate to feed. (The tracked copy also
  carries a UTF-8 BOM.)
- `docs/release/library-relicensing-audit.md`,
  `docs/release/proposed-apache-source-header.txt`,
  `docs/release/proposed-library-NOTICE.txt` - all three are library-scoped;
  the NOTICE draft self-declares it is "not effective in the GPL-3.0
  monorepo". Confirm each exists in `onfhir-libs` before deleting here.

Keep `docs/adr/0002-transitional-onfhir-event-boundary.md` - it documents a
live server design boundary. Its title and body describe the module by its
former name and correct it in a preamble note; fold the correction into the
text so the note is no longer needed.

`docs/adr/0001-neutral-http-contract.md` also exists in `onfhir-libs`, which
owns that contract - but the two copies have diverged, and this one is the
stale side. The libs copy was rewritten into post-implementation normative form
("it remains the normative specification for these models"), while this copy
still reads as a pre-implementation proposal describing work to be done. Delete
it here rather than reconciling it; a contract owned by another repository
should not have a second, drifting copy. Leave `0002` numbered as it is rather
than renumbering to close the gap.

The two split plans are **not** deleted in this phase - Phase 4 still mines
them for the release runbook. They go in Phase 8.

### Verification

```bash
git status
```

`docs/` afterwards contains exactly: `adr/0002-...md`, and
`migration/onfhir-3.x-to-repofyr-4.0.md`, plus `plans/` pending Phase 8.
Grep the surviving tree for `srdc/onfhir-libs` links and confirm each resolves.

---

## Phase 3 - Root documentation

### 3.1 `README.md` edits

The README is already rebranded and largely accurate; these are corrections
and additions, not a rewrite.

- **Line 85** - "the default server Boot configuration for onfhir-server-r4"
  is the last stale server module name in the file. Use `repofyr-server-r4`.
- **Add a Modules section** after Overview. `repofyr-event`,
  `repofyr-operations`, and `repofyr-kafka` are currently never mentioned; a
  reader cannot tell what the reactor contains. One table, one line each,
  linking to the module READMEs added in Phase 7.
- **Add a License section** - GPL-3.0, linking `LICENSE`, and stating that the
  consumed `io.onfhir` libraries are Apache-2.0. A GPL project should say so in
  its README.
- **Add a Migration pointer** in the rebranding callout, linking
  `docs/migration/onfhir-3.x-to-repofyr-4.0.md`.
- **Add a dependency snippet** to the Extensibility section showing how to
  depend on `io.repofyr:repofyr-server-r4_2.13` when building a custom server -
  the section tells users to "import the corresponding server module" without
  showing the coordinates.
- **Fix the Docker instructions** - `docker/docker-compose.yml:17` pins
  `image: srdc/repofyr:r5` while README line 133 copies the **r4** jar into
  `sample-setup`. Make them agree; r4 is the better default given the rest of
  the README.
- Verify the Maven Central badge only after the first publish; it will render
  "not found" until then, which is acceptable pre-release but should be
  checked once live.

### 3.2 New `CHANGELOG.md`

Follow the `onfhir-libs` format: a short policy preamble, then
`## 4.0.0 (unreleased)` with Added / Changed / Removed / Fixed subsections.
Content is drawn from the plan's phase records, written for users rather than
implementers. At minimum:

- **Changed** - coordinates `io.onfhir:onfhir-server-*` ->
  `io.repofyr:repofyr-*`; packages `io.onfhir.*` -> `io.repofyr.*` for
  server-owned types; standalone jar and image names; reusable libraries now
  consumed as external `io.onfhir` 4.0.0 artifacts; `SubscriptionUtil` obtained
  from the configurator; `KafkaEventProducer` and `ResourceChecker`
  construction signatures.
- **Removed** - embedded FHIR definitions resources from the R4/R5 server
  modules (18.3 MB), now resolved from `onfhir-definitions-*`.
- **Fixed** - STU3 startup with packaged classpath defaults.
- **Unchanged (deliberate)** - a short pointer to the migration guide's
  "What did not change" section.

State the versioning policy in the preamble: the Repofyr server version line is
independent of the `io.onfhir` library line, which is why both start at 4.0.0
but will diverge.

### 3.3 New `SECURITY.md`

Mirror the `onfhir-libs` policy: private vulnerability reporting via the GitHub
Security tab on `srdc/repofyr` or `onfhir@srdc.com.tr`, five-business-day
acknowledgement, and a supported-versions table (4.0.x yes; 3.x monorepo line
no). Add the reciprocal cross-reference: vulnerabilities in the reusable
libraries go to `srdc/onfhir-libs`. Include a scope note that Repofyr is a data
repository handling PHI, so authorization, audit, and search-parameter handling
are in scope.

### 3.4 `CONTRIBUTING.md` corrections

- Line 24 tells contributors to run "the `onfhir-server-r4` regression suite";
  the module is `repofyr-server-r4`.
- Line 4 points at "the active plan under `docs/plans`", which Phase 8 deletes.
  Repoint to `AGENTS.md` and `RELEASING.md`.
- Line 26 says to keep license metadata unchanged "during the split"; the split
  is over. State the standing rule instead.
- Add the `verify` skill and the boundary gate to the "Before submitting" list
  once Phase 4 lands.

### 3.5 New `docs/release/known-limitations.md`

Mirror the `onfhir-libs` pattern: deliberate, documented gaps in 4.0.0, grouped
by area, each destined to become a GitHub issue at publish time. Seed it from
the 39 main-tree TODOs, promoting the ones that describe user-visible behavior:

- Remote FHIR reference resolution unimplemented
  (`repofyr-core/.../api/validation/ReferenceResolver.scala:37,107`).
- Token-endpoint client authentication options beyond the implemented one
  (`.../authz/TokenClient.scala:38`).
- JWT and JWT-with-introspection token resolution paths incomplete
  (`.../authz/AuthzConfigurationManager.scala:296-297`,
  `.../authz/AuthManager.scala:57`).
- CareTeam-based SMART authorization unimplemented
  (`.../authz/SmartAuthorizer.scala:291,300`).
- Reference search modifiers `:above` / `:below` unsupported
  (`.../db/ReferenceQueryBuilder.scala:17,22`).
- `:iterate` not handled in `_include` / `_revinclude`
  (`.../db/ResourceManager.scala:591`).
- Single shard keys only (`.../db/MongoDBInitializer.scala:387`).
- Accent, diacritic, and punctuation insensitivity not implemented for string
  search.

Each entry: what is missing, where, and what a user should do instead.

### 3.6 Stale comments in shipped configuration

Three shipped files carry a comment naming a module and a file that both no
longer exist - "default file given under the specific FHIR version module
(onfhir-server-r4/db-index-conf.json)". The file is now `db-index-conf-r4.json`
in `repofyr-server-r4`. Fix all three:

- `repofyr-core/src/main/resources/application.conf:71`
- `repofyr-server-r4/src/test/resources/application.conf:63`
- `docker/sample-setup/conf/application-docker.conf:61`

### Verification

Render every changed Markdown file and confirm links resolve. No command gate
in this phase.

---

## Phase 4 - Agentic and release infrastructure

This is the phase that makes the repository maintainable after release. It
mirrors `srdc/onfhir-libs` structurally but not literally: the server has no
MiMa gate and no Akka ban, and it has a maintenance task the libraries do not -
tracking the library version.

### 4.1 `RELEASING.md`

Follow the `onfhir-libs` runbook shape, including its most important property:
**agents stop at the end of section 3; every step in section 4 is manual.**

- **Versioning policy** - all seven server artifacts release together at one
  version, set by the `revision` property. The server line is independent of
  `onfhir.libs.version`. Patch = fixes; minor = additive; major = breaking
  server API or configuration changes, which additionally require a migration
  guide entry. Published releases are immutable; fixes roll forward.
- **1. Pre-flight** - clean tree reviewed with `git log`; `CHANGELOG.md` entry
  complete with the release date stamped; migration guide covers any
  server-contract change; the `verify` skill green; and a fresh-checkout
  rehearsal (clone to a temp directory, run the reactor there) to catch
  working-copy-only state. Add the server-specific check: `onfhir.libs.version`
  names a released, non-SNAPSHOT library version available on Central.
- **2. Stage a signed release locally** - the SRDC release GPG key with
  `--pinentry-mode loopback`; then
  `mvn -B -Prelease deploy -DaltDeploymentRepository=staging::file:///<abs-path>`;
  then `powershell -File scripts/check-staged-release.ps1 -RepositoryPath <path> -Version <version>`.
  State that signing must not be disabled to get a green run; unsigned
  rehearsal only on maintainer request via `-Dgpg.skip=true` plus
  `-SkipSignatures`.
- **3. Consumer rehearsal** - this is where the remaining Phase 6 work from the
  split plan lands, converted from a one-time migration task into a standing
  release step. Required for majors and for any change to publishing mechanics:
  build spark-on-fhir against the staged artifacts; then CRT launch
  verification; then other internal consumers. Purge `io/repofyr` from the
  rehearsal local repository first. Record discovered omissions back into the
  migration guide.
- **4. Publish (maintainer only)** - promote the validated deployment in the
  Central portal; tag `v<version>` and push repository and tag; build and push
  the `srdc/repofyr:{r4,r5}` images; create the GitHub release with a changelog
  excerpt and a migration-guide link.
- **5. Post-publish** - convert `docs/release/known-limitations.md` entries into
  GitHub issues and link the numbers back; bump `revision` to the next
  development version; verify the README Maven Central badge renders.

### 4.2 `scripts/check-server-boundary.ps1`

The server counterpart to the libs forbidden-import gate. ASCII-only,
`$ErrorActionPreference = "Stop"`, `$repoRoot` from `Split-Path -Parent $PSScriptRoot`,
no parameters, and a single terminal verdict line. Three checks:

1. No `package io.onfhir` declaration and no `io.onfhir.*` **server** type
   under any `repofyr-*/src/main` - this enforces invariant 2 mechanically.
   Note that `import io.onfhir.*` is legitimate and expected (the libraries are
   consumed); only *declaring* server code in the `io.onfhir` namespace is
   forbidden. Match on `^\s*package\s+io\.onfhir` at file scope.
2. Every `<groupId>io.onfhir</groupId>` dependency in every pom uses
   `${onfhir.libs.version}` - invariant 3.
3. No `<groupId>io.repofyr</groupId>` dependency uses `${onfhir.libs.version}`
   - the inverse mistake, which would silently pin server modules to the
   library version.

Exit 1 with a `FAIL - <n> ...` line listing each hit as `relative:line text`;
exit 0 with `check-server-boundary: PASS - server modules stay in io.repofyr.*`.
End with an explicit `exit 0` on success: `onfhir-libs` learned the hard way
that `shell: pwsh` CI steps exit with `$LASTEXITCODE`, which may hold a stale
nonzero value from a native call.

### 4.3 `scripts/check-staged-release.ps1`

Adapt the `onfhir-libs` script. Parameters `-RepositoryPath` (mandatory),
`-Version`, `-SkipSignatures`. Artifact table of **eight** coordinates:
`repofyr-parent` (pom) plus the seven `repofyr-*_2.13` jars. For each:

- POM exists at `io/repofyr/<artifactId>/<version>/<artifactId>-<version>.pom`.
- POM text matches `GNU General Public License` and does **not** match
  `Apache License` - the inverse of the libs assertion. This is the check that
  catches an accidental license flip in either direction.
- Binary, `-sources.jar`, and `-javadoc.jar` present.
- `jar tf` shows `META-INF/LICENSE` (added in Phase 1, item 4).
- `gpg --batch --verify <file>.asc <file>` passes for every file, unless
  `-SkipSignatures`.

Final line: `check-staged-release: PASS - 8 <version> artifacts verified.`

### 4.4 `.claude/settings.json`

Copy the `onfhir-libs` permission design, which is the valuable part: read-only
and test-only commands allowed, `git push` on ask, and every publish-capable
Maven goal denied outright so no agent can publish by accident.

- `allow`: `mvn -B test:*`, `mvn test:*`, `mvn clean test:*`, `mvn -B package:*`,
  `mvn -B -pl:*`, `mvn -pl:*`, `mvn -B -DskipTests:*`, `git status:*`,
  `git log:*`, `git diff:*`, `git show:*`, `git ls-files:*`,
  `powershell -File scripts/check-server-boundary.ps1:*`,
  `powershell -File scripts/check-staged-release.ps1:*`
- `ask`: `git push:*`
- `deny`: `mvn deploy:*`, `mvn -B deploy:*`, `mvn release:*`,
  `mvn nexus-staging:*`, `mvn org.sonatype.central:*`, `docker push:*`

### 4.5 Skills

Frontmatter is `name` and `description` only, matching `onfhir-libs`.

**`.claude/skills/verify/SKILL.md`** - the ordered verification suite:
`mvn -B test`, then `powershell -File scripts/check-server-boundary.ps1`, with
expected verdict strings quoted. Include an "Environment rules" section, which
is where this repository's hard-won operational knowledge belongs:

- The R4 test suite boots a full server on embedded MongoDB (port 27019 by
  default); a stale `mongod` or an occupied port fails the whole module. Check
  for leftover processes before assuming a code fault.
- Run gate scripts bare - never piped - because under PowerShell 5.1 with
  `$ErrorActionPreference = "Stop"` any native stderr line becomes a
  terminating `NativeCommandError`.
- Module-scoped builds need `-am` while the tree is ahead of installed
  artifacts.
- A killed Maven run corrupts zinc incremental state and produces bogus
  "not a member of package" errors; fix with `mvn -B -pl <module> clean`.
- This repository may be worked on by parallel sessions; check `git log` before
  assuming tree state.

**`.claude/skills/release-stage/SKILL.md`** - implements `RELEASING.md`
sections 1-3 and hard-stops before section 4. Opens with the stop declaration:
never run a remote `mvn deploy`, never promote in the Central portal, never
`git push`, never `docker push`. Ends by requiring a hand-over report: staged
path, artifact count, every gate verdict, and what remains for the maintainer.

**`.claude/skills/bump-libs/SKILL.md`** - the server-specific maintenance task
and the one with no counterpart in `onfhir-libs`. Flow: read the target
version's entry in the `onfhir-libs` `CHANGELOG.md` and, for a major, its
migration guide; update `onfhir.libs.version` in the root pom; run the full
reactor; triage failures against the migration guide rather than patching
symptoms; record the bump in `CHANGELOG.md`. State the constraint that a
library major may require server code changes and must not be absorbed
silently.

**`.claude/skills/new-operation/SKILL.md`** - the recurring server extension
task, and the counterpart to the libs `new-module` skill. Checklist for adding
a FHIR operation: implement the handler in `repofyr-operations` extending
`FHIROperationHandlerService`; register it in
`io.repofyr.operation.DefaultOperationHandlers` if it is a default; author the
`OperationDefinition` JSON with the **fully qualified handler class name in
`OperationDefinition.name`**; reference it from the CapabilityStatement; add
the definition to each server module's conformance resources that should expose
it; add an endpoint test; update the module README and `CHANGELOG.md`. Flag the
two easy-to-miss steps: the handler class name is a string that no compiler
checks, and an operation added to only one FHIR-version module silently does
not exist in the others.

### 4.6 `AGENTS.md` and `CLAUDE.md` rewrite

`AGENTS.md` currently describes split execution: it points at the active plan,
sets a branch policy for split work, and frames invariants as split-time rules.
Rewrite it as a maintenance contract:

- Keep the repository boundary section and the five invariants, restated as
  standing rules rather than migration constraints (drop "during the namespace
  migration" from invariant 4 and "during the split" framing generally).
- Replace the branch policy with the post-merge state: `master` is trunk,
  `oss-release` is merged and gone, `updating-operation-handling` and
  `repository-split` are retired. Drop the 2026-08-07 rewrite note, which by
  then documents a branch that no longer exists.
- Replace the plan pointer with pointers to `RELEASING.md`, the migration
  guide, and `.claude/skills/`.
- Move the verification commands to reference the `verify` skill, keeping the
  raw commands as the fallback.
- Add a "Local build notes" section in the shape of the libs one, carrying the
  same operational knowledge as the `verify` skill's environment rules.
- Keep "do not push, publish, sign with a replacement identity, or alter
  credentials without explicit authorization".

`CLAUDE.md` shrinks to the libs shape: the `@AGENTS.md` import, a line naming
`RELEASING.md` as the runbook with the stop-before-publish note, and a line
listing the four skills. Delete the "Where to start" pointer to the split plan
and the note that gate scripts live in the sibling repository - after this
phase, they live here too.

### Verification

```bash
mvn -B test
```

```bash
powershell -File scripts/check-server-boundary.ps1
```

Both green. Confirm the boundary gate actually fails when it should by
temporarily adding a `package io.onfhir.probe` file under
`repofyr-core/src/main/scala`, running the gate, and deleting the probe. A gate
that has never failed has not been tested.

---

## Phase 5 - Continuous integration

Partially blocked: see "Release gate", item 2. The workflow can be written and
committed now, but `server-build` stays red until `io.onfhir:*:4.0.0` is on
Central, because a hosted runner has no local Maven cache. Do not treat that
redness as a workflow defect, and do not work around it by adding a
`<repositories>` block or committing staged artifacts.

Rewrite `.github/workflows/maven.yml` with three jobs, all on JDK 17 temurin
with `cache: maven`:

- **`server-build`** (ubuntu-latest) - `mvn -B test`. Unchanged except for the
  branch filter.
- **`boundary`** (ubuntu-latest) - `./scripts/check-server-boundary.ps1` with
  `shell: pwsh`.
- **`build-windows`** (windows-latest) - `mvn -B -DskipTests package` followed
  by the boundary script. Deliberately skips tests: the R4 suite downloads and
  starts a real `mongod` through flapdoodle, which is slow and flaky on hosted
  Windows runners. The job's purpose is to keep the primary development
  platform compiling and to keep the PowerShell gate scripts working there.

Set the branch filter to `[master, oss-release]`, dropping `repository-split`
and `updating-operation-handling`: the first is a fallback nobody commits to
and the second is retired at Phase 8. Add `.github/dependabot.yml`
with weekly `maven` and `github-actions` updates, `open-pull-requests-limit: 5`,
and a leading comment noting that dependency bumps are reviewed by the boundary
gate and the reactor suite. Once `io.onfhir` is on Central, Dependabot will
also propose `onfhir.libs.version` bumps, which the `bump-libs` skill handles.

### Verification

Push to a branch and confirm all three jobs pass. This is the first real
end-to-end proof that the library dependency resolves from Central without a
local cache.

---

## Phase 6 - Test coverage

Current state: 140 main sources against 23 test files, with
`repofyr-operations` (7 sources, every FHIR operation handler) and
`repofyr-event` (4 sources) at zero, and R5 and STU3 at one file each.

The integration harness is `io.repofyr.OnFhirTest` in `repofyr-server-r4`,
which starts embedded MongoDB and boots a full server. That is the right tool
for operation coverage, so most of this phase lands in the server modules
rather than in `repofyr-operations` itself.

### 6.1 Operation coverage (highest value)

Add endpoint-level suites in `repofyr-server-r4` exercising each handler in
`repofyr-operations` over HTTP, using the existing `OnFhirTest` trait. One
suite per operation, in priority order:

1. `$validate` - the most used, and pure enough to also warrant direct unit
   tests of `ValidationOperationHandler`.
2. `$everything` (patient) - 466 lines with the largest untested surface.
3. `$document`, `$meta`, `$lastn`, `$expand`.
4. `$import` (bulk) - async job handling; at minimum assert the job is accepted
   and its status endpoint responds.

For each: happy path, a malformed-parameters case asserting the OperationOutcome
status, and where applicable an unsupported-parameter case. Cover the dispatch
path too - an assertion that `DefaultOperationHandlers` resolves each
operation URL to the expected class, which is the mapping that silently breaks
when a handler is renamed.

### 6.2 R5 and STU3 smoke tests

Each of these modules has one test that does not boot a server. The split plan
records that a partial rehearsal once missed a compile break in
`repofyr-server-stu3`, and the STU3 startup defect fixed in this release was
exactly the kind a boot test catches.

Add, for both `repofyr-server-r5` and `repofyr-server-stu3`, a suite mirroring
`OnFhirTest` that boots the server on embedded MongoDB, fetches
`/fhir/metadata` and asserts the CapabilityStatement `fhirVersion`, then does a
create / read / search / delete round trip on a simple resource.

Assign distinct embedded ports to keep the modules independent: R4 keeps 27019,
R5 takes 27020, STU3 takes 27021. If the `OnFhirTest` trait is worth sharing
rather than copying, promote it into a test-jar from a common module - decide
based on how much diverges once the R5 version is written; do not build the
abstraction first.

### 6.3 `repofyr-event`

Add a serialization round-trip suite: every event type through
`InternalJsonMarshallers` and back. This module has no tests at all, and its
wire format is a compatibility contract - the migration guide states that
json4s `ShortTypeHints` emits simple class names, which is exactly the kind of
property a round-trip test pins down.

### Verification

```bash
mvn -B test
```

Report the new totals per module against the Phase 0 baseline. No previously
passing test may change behavior to accommodate a new one.

---

## Phase 7 - Module and API documentation

### 7.1 Module READMEs

All fourteen `onfhir-libs` modules have one; none of the seven here do. Write
`<module>/README.md` for each, following the libs template: purpose, scope and
non-goals, Maven coordinates, principal public APIs, relationship to the other
modules, and a minimal usage example. Depth should match the module - the libs
range runs from 57 to 869 lines, and `repofyr-event` does not need what
`repofyr-core` needs.

Link them from the README Modules table added in Phase 3.

### 7.2 Type-level scaladoc on extension points

Method-level scaladoc across `repofyr-core` is already good - the db, authz, and
service layers use `@param` and `@return` consistently. The gap is type-level
documentation, and it falls precisely on the extension points the README tells
users to implement. Add class, trait, or object scaladoc to at least:

- `io.repofyr.config.IFhirServerConfigurator` - methods documented, the trait
  itself is not, and it is the primary server extension point.
- `io.repofyr.event.IFhirEventBus` - the event SPI trait has no scaladoc at all.
- `io.repofyr.db.DocumentManager` - 1446 lines, 41 method doc blocks, no
  type-level doc.
- `io.repofyr.db.ResourceManager` - the class-level position holds a bare
  `//TODO` about logical reference resolution instead of a description. Keep
  the TODO, add the description.
- `DocumentOperationHandler` and `LastNObservationOperationHandler` in
  `repofyr-operations` - no class-level doc.

Also fix the two blemishes in `io.repofyr.Onfhir`, the class every user's `Boot`
touches: the legacy `Created by tuncay on 10/16/2017.` opener and the
`@param fhirOperationLibaries` typo.

### Verification

```bash
mvn -B -Prelease -Dgpg.skip=true package
```

Confirms the scaladoc jar still builds - a malformed scaladoc tag fails this
and nothing else.

---

## Phase 8 - Retire the plans and cut the release

Blocked until `onfhir-libs` 4.0.0 is published and "Release gate" item 1
passes. Steps 1-3 are documentation cleanup and may run earlier; steps 4-5 may
not.

1. `git rm docs/plans/library-server-split-plan.md` and
   `docs/plans/library-server-split-plan-v2.md`. Before deleting, confirm each
   still-live element has a home: the migration tables are in the migration
   guide (Phase 2), the remaining consumer-migration work is `RELEASING.md`
   section 3 (Phase 4), and the deliberate-unchanged list is in the migration
   guide's "What did not change" section. Git history retains both files.
2. Delete this plan file. `docs/plans/` should end up empty and removed.
3. Confirm no surviving file references `docs/plans` - check `AGENTS.md`,
   `CLAUDE.md`, `CONTRIBUTING.md`, and `README.md`.
4. Update `master` to this branch. The lineage is linear, so this is a
   fast-forward - confirm that is still true before doing it, and stop if it
   is not rather than forcing:

   ```bash
   git rev-list --count oss-release..master
   ```

   Expect `0`. Then fast-forward `master`, push it, and push nothing else:

   ```bash
   git checkout master && git merge --ff-only oss-release
   ```

   Pushing is a maintainer action. Afterwards, retire
   `updating-operation-handling` and delete `repository-split`.
5. Set `revision` to `4.0.0` in the root `pom.xml`.
6. Run the `verify` skill, then the `release-stage` skill, and stop. Publishing
   is a maintainer action; no agent proceeds past staging.

---

## Definition of done

- `docs/` contains only user-facing material: the migration guide, the surviving
  ADR, and `release/known-limitations.md`.
- A user upgrading from onFHIR 3.x can do so from `docs/migration/` alone.
- `README.md`, `CHANGELOG.md`, `SECURITY.md`, `CONTRIBUTING.md`, `RELEASING.md`,
  and `LICENSE` are present, accurate, and mutually consistent.
- Every module has a README; every advertised extension point has type-level
  scaladoc.
- `repofyr-operations` and `repofyr-event` have tests; R5 and STU3 boot in CI.
- `scripts/check-server-boundary.ps1` and `scripts/check-staged-release.ps1`
  exist, are exercised by CI, and have been observed failing on a deliberate
  violation.
- `.claude/` carries the four skills and a permission set that cannot publish.
- No agent-facing document references a plan that no longer exists.
- `mvn -B test` green; all CI jobs green; a signed local staging run passes
  `check-staged-release.ps1`.

## Deferred - explicitly not in this plan

- **GPL per-file source headers.** None of the 140 main sources carry one.
  Mechanical but noisy; better as a single isolated commit after 4.0.0, if at
  all.
- **Shaded-jar third-party notice aggregation.** The standalone jar bundles
  Apache-2.0 dependencies whose NOTICE files are not aggregated. The
  `maven-shade-plugin` `ApacheNoticeResourceTransformer` would handle it; worth
  doing, but it changes the published artifact and should not ride along with a
  documentation release.
- **The remaining 30-odd TODOs** not promoted to `known-limitations.md`. They
  are implementation notes, not user-visible gaps.
