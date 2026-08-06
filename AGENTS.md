# AGENTS.md

Working contract for AI agents operating in the Repofyr server repository.
The reusable onFHIR libraries have been physically extracted to the sibling
`onfhir-libs` repository during Phase 5A.

The active restructuring plan is
`docs/plans/library-server-split-plan-v2.md`. Execute one phase at a time and
keep its Status header current.

## Branch policy

- Split work belongs on `repository-split`.
- `updating-operation-handling` is the intended trunk for the new release
  line; do not base split work on legacy `master`.
- Do not commit unless explicitly asked and never rewrite published history.

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
validation, template-engine, and R4 parser code belong to `srdc/onfhir-libs`.
Do not copy their sources back into this reactor.

## Non-negotiable invariants

1. Repofyr retains GPL-3.0 metadata until a separate license decision.
2. Since Phase 5B, server artifacts use `io.repofyr:repofyr-*` coordinates and
   server-owned code lives in `io.repofyr.*` packages; the reusable libraries
   keep `io.onfhir` coordinates and `io.onfhir.*` packages. Never rename a
   library coordinate, package, or import from this reactor.
3. Every reusable-library dependency uses `onfhir.libs.version`, never the
   server `${project.version}`.
4. Runtime configuration keys, persistence identifiers, and stored-data
   conventions remain stable during the namespace migration.
5. Public moves are recorded in the migration tables in the active plan.

## Verification

- Reactor compile: `mvn -DskipTests compile`
- Targeted server tests: `mvn -pl <module> -am test`
- Main regression net: `mvn -pl repofyr-server-r4 -am test`
- Release verification must use a fresh Maven cache configured to resolve
  `io.onfhir:*` libraries only from the staged library repository.

Keep scripts ASCII-only for Windows PowerShell 5.1 compatibility. Do not push,
publish, sign with a replacement identity, or alter credentials without
explicit authorization.
