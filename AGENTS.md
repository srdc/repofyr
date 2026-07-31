# AGENTS.md

Working contract for AI agents (Claude Code, Codex, or others) operating in
this repository. This repo is onFHIR (rebranded repofyr on GitHub:
https://github.com/srdc/repofyr), currently being restructured: the reusable
library modules are being separated from the FHIR server so the libraries can
be released under Apache-2.0 while the server keeps its own license.

The active restructuring plan lives at
`docs/plans/library-server-split-plan-v2.md`. Read it before any implementation
work; execute one phase at a time and keep its Status header current. The
original plan is retained only as historical design context.

## Branch Policy

- Working branch for the split: `repository-split` (created 2026-07-31 from
  master + `updating-operation-handling`; `mvn test` green). All split work
  goes here.
- Trunk for the new release line: `updating-operation-handling` (21 commits
  ahead of `master`, fast-forwardable; confirmed 2026-07-31). It will become
  the new `master` at release time.
- Do not base work on `master`; it is legacy.

## Refactoring Policy

Module membership is decided by logic and semantics, not by downstream usage.
Dependent projects (ignifyr, gitlab.srdc.com.tr repos) are pinned to released
onfhir versions and adapt afterwards. The cost of this freedom is that the
Migration Table in the split plan is mandatory: every module relocation and
public-signature change is recorded there in the same change that makes it.
Coordinates do not change during the split (invariant 3 below).

## Module Map And Target Architecture

Two families. The split plan moves the library family to its own repo once
the in-place refactoring is complete (refactor first, split last).

Library family (target: Apache-2.0, zero Akka/Pekko dependencies):

| Module | Akka status (baseline 2026-07-31: 55 imports total) |
|---|---|
| onfhir-common | 31 imports: client API + akka-http model types + misfiled server files (Phases 1A-2) |
| onfhir-client | 22 imports: akka-http client, rewrite on java.net.http (Phase 3) |
| onfhir-path | 1 stray (`FhirPathTerminologyServiceFunctions`: akka Uri) - fix in Phase 2 |
| onfhir-query | clean |
| onfhir-config | 1 stray (`BaseFhirServerConfigurator`: akka MediaType) - moves to server in Phase 1D |
| onfhir-expression | clean |
| onfhir-validation | clean |
| onfhir-template-engine | clean |
| onfhir-r4 | clean |

Note: onfhir-path and onfhir-config declare no Akka POM dependency - their
stray imports compile via transitive Akka from onfhir-common. They are the
proof that only `scripts/check-forbidden-imports.ps1` (source-level), not POM
inspection, is authoritative for invariant 1.

Server family (stays in this repo as repofyr; Akka remains until a separate
Pekko/license decision, out of scope here):

- onfhir-event (transitional Phase 1D cycle breaker), onfhir-core,
  onfhir-operations, onfhir-kafka,
  onfhir-server-r4, onfhir-server-r5, onfhir-server-stu3

Verified inter-module facts (2026-07-31): the library family is a closed
dependency set with no edges into server modules. The two historical edges
(`onfhir-validation -> onfhir-server-r4`, `onfhir-path -> onfhir-client`) are
commented out in the POMs and were test-scoped. Do not reintroduce either.

## Non-Negotiable Invariants

1. No `import akka.` or `import org.apache.pekko.` in library-family
   `src/main`. Enforced by `scripts/check-forbidden-imports.ps1`; the count
   must never increase, and reaches zero by the end of Phase 3.
2. No server concerns in `onfhir-common`: no HTTP routing (Directives), no
   response marshalling, no actor-based event bus. Such code moves to the
   server modules.
3. Coordinate stability during the split: groupId `io.onfhir`, existing
   artifactIds, and Scala package roots (`io.onfhir.*`) do not change.
   Hundreds of files across downstream repos (spark-on-fhir, tofhir,
   onfhir-feast, CRT, and others) import these. A later Repofyr rebrand may
   deliberately adopt `io.repofyr` coordinates and packages, but that is a
   separate post-split major migration - never a side effect of this work.
4. Public API changes in library modules are recorded in the migration table
   in the split plan (old signature -> new signature), in the same change.
5. License: library modules must not gain dependencies that are incompatible
   with Apache-2.0 (no GPL/LGPL/BSL/SSPL). The relicense itself is pending a
   contributor audit - do not change LICENSE files until that decision is
   recorded.

## Verification

- Reactor compile: `mvn -DskipTests compile`
- Targeted module tests: `mvn -pl <module> -am test`
- The main regression net is `onfhir-server-r4` (14 test files, endpoint
  tests exercising the library code underneath). Library-module test coverage
  is thin (0-3 files each), so every phase must end with the server-r4 suite
  green, not just the touched module's tests.
- Before refactoring a low-coverage class, add characterization tests first
  (pin current behavior, then change). This applies especially to
  `FHIRSearchParameterValueParser`.

## Working Conventions

- Plan-first: multi-file work starts from `docs/plans/`; one phase per
  session.
- Keep scripts ASCII-only (Windows PowerShell 5.1 parses no-BOM files as
  ANSI; non-ASCII characters corrupt parsing).
- Do not commit unless asked. Never rewrite published history; the eventual
  repo split uses git filter-repo on a dedicated clone, not on this working
  copy.

## Imported Claude Cowork project instructions
