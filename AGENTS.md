# AGENTS.md

Working contract for AI agents operating in the Repofyr server repository.
Repofyr is the FHIR server; the reusable onFHIR libraries it builds on live in
the sibling `srdc/onfhir-libs` repository and are consumed as released
artifacts.

- Release runbook: `RELEASING.md`. Agents stop at the end of its section 3;
  everything in section 4 is a maintainer action.
- Consumer upgrade path: `docs/migration/onfhir-3.x-to-repofyr-4.0.md`.
- Workflow skills: `.claude/skills/` (verify, release-stage, bump-libs,
  new-operation).

There is no active plan document. The 4.0.0 release-readiness plan and the two
library-server split plans it superseded were retired once their work landed;
git history holds them if you need to know why something was done a particular
way. What they left behind lives in the documents above and in `CHANGELOG.md`.

## Branch policy

- `master` is trunk. It was fast-forwarded to `oss-release` at the 4.0.0
  release cut, the lineage being linear, so no merge commit exists.
- `3.4` is the retired pre-split line, pointing at what was `master` before
  that fast-forward: the old monorepo shape, twelve `onfhir-*` modules in one
  reactor at `3.4-SNAPSHOT`. It is an archive, not a maintenance branch -
  `SECURITY.md` states 3.x receives no fixes. Do not commit to it.
- `updating-operation-handling` was the trunk the split work was built on. Keep
  it: it carries 21 commits of pre-split work that `3.4` does not, since `3.4`
  points at the older `master` tip. Those commits are also reachable from
  `master`, but no version-named branch names them. Do not commit to it.
- Consumers who have not migrated use the published `io.onfhir` 3.x artifacts
  from Maven Central, which are immutable and unaffected by any of the above.
  Neither `3.4` nor any branch is the answer for them; 3.3 is the last released
  3.x, and `3.4` was never released.
- `repository-split` was a fallback during the split and was never pushed. Its
  commits are pre-rewrite SHAs of work now in `master`.
- Do not commit unless explicitly asked and never rewrite published history.
  The `oss-release` history rewrite of 2026-08-07 was permissible only because
  those commits had never been pushed; that is not a precedent.

## Repository boundary

This reactor owns only the Repofyr server family:

- `repofyr-event`
- `repofyr-core`
- `repofyr-operations`
- `repofyr-kafka`
- `repofyr-server-r4`
- `repofyr-server-r5`
- `repofyr-server-stu3`

Reusable models, clients, FHIRPath, query, configuration, expression,
validation, template-engine, and the release parsers belong to
`srdc/onfhir-libs`. Do not copy their sources back into this reactor. Importing
`io.onfhir` types is expected and correct; declaring server code in an
`io.onfhir` package is not, and `scripts/check-server-boundary.ps1` fails the
build when it happens.

## Non-negotiable invariants

1. Repofyr is GPL-3.0 and its published metadata says so. The reusable
   libraries are Apache-2.0. `scripts/check-staged-release.ps1` asserts both
   directions, because a license flip either way is a release-blocking defect.
2. Server artifacts use `io.repofyr:repofyr-*` coordinates and server-owned
   code lives in `io.repofyr.*` packages; the reusable libraries keep
   `io.onfhir` coordinates and `io.onfhir.*` packages. Never rename a library
   coordinate, package, or import from this reactor.
3. Every reusable-library dependency uses `onfhir.libs.version`, never the
   server `${project.version}` - and no server dependency uses
   `onfhir.libs.version`. The two version lines are independent after 4.0.0.
4. Runtime configuration keys, persistence identifiers, Kafka topic names, and
   stored-data conventions stay stable. Note there is no `onfhir.*` config key
   namespace - the top-level sections are `server`, `fhir`, `akka`, `kafka`,
   and `mongodb`. What carries the legacy naming is a set of *values* and
   identifiers: the `onfhir.subscription` Kafka topic, the `onfhir` database
   name and Kafka client id, the `akka.actor.onfhir-blocking-dispatcher`
   dispatcher, `ONFHIR_HOME`, and the `io.onfhir.path` / `io.onfhir.validation`
   logger names. All are deliberately unchanged and must not be "cleaned up" to
   match the new branding. Section 2 of the migration guide is the
   authoritative list.
5. User-visible changes are recorded in `CHANGELOG.md`. A change to a public
   server contract additionally needs a migration-guide entry in the same
   change.

## Verification

Run the `verify` skill, which executes the suite in the right order. The raw
commands, as a fallback:

- Full reactor: `mvn -B test` (baseline 334 tests; `repofyr-server-r4` is the
  main regression net at 162 of them)
- Targeted module: `mvn -B -pl <module> -am test`
- Repository boundary: `powershell -File scripts/check-server-boundary.ps1`

Release verification must additionally resolve `io.onfhir:*` from a fresh
Maven cache, to prove the build does not depend on locally staged artifacts:
`mvn -B -Dmaven.repo.local=<throwaway> -DskipTests compile`.

## Local build notes

- The `repofyr-server-r4` suite boots a full server against embedded MongoDB on
  port 27019. A stale `mongod` or an occupied port fails the whole module -
  check for leftover processes before diagnosing a code fault.
- Run the PowerShell gate scripts bare. Do NOT pipe their output (`|
  Select-String`, `2>&1`, `| tee`): under Windows PowerShell 5.1 with
  `$ErrorActionPreference = "Stop"`, any native stderr line - including a
  harmless JVM warning - becomes a terminating NativeCommandError. Filter
  captured output afterwards instead.
- Module-scoped builds need `-am` while the working tree is ahead of the
  installed artifacts.
- Renaming or deleting a file under `src/main/resources` requires `mvn clean`
  before the result means anything. `maven-resources-plugin` copies new
  resources into `target/classes` but never removes ones that disappeared, so
  the old file stays on the classpath and in the shaded jar. A rename then
  appears to work while both names are live - and any test asserting the new
  arrangement passes for the wrong reason.
- A killed or crashed Maven run can corrupt zinc incremental state under
  `target/`, producing bogus "X is not a member of package Y" errors. Fix with
  `mvn -B -pl <module> clean`, then rebuild.
- This repository may be worked on by parallel sessions: check `git log` before
  assuming the tree state.

Keep scripts ASCII-only for Windows PowerShell 5.1 compatibility. Do not push,
publish, promote a staged release, push a container image, sign with a
replacement identity, or alter credentials without explicit authorization.
