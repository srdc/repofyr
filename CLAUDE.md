# CLAUDE.md

Entry point for Claude Code in this repository. The shared agent working
contract is defined once in `AGENTS.md` and imported below - keep behavior
rules there, not duplicated here, so Codex and Claude Code stay in sync.

@AGENTS.md

## Claude-specific notes

- Where to start: `docs/plans/release-readiness-plan.md` for the current
  4.0.0 release preparation; execute one phase at a time. The split plans
  (`library-server-split-plan*.md`) are the completed historical record and
  are retired by that plan's Phase 8.
- Build/test: Maven, Scala 2.13, from repo root (PowerShell). Prefer targeted
  module runs (`mvn -B -pl <module> -am test`) while iterating; end each phase
  with the full reactor, or at minimum the `repofyr-server-r4` suite.
- Reach for a skill before improvising: `verify` for the test-and-gate suite,
  `release-stage` for a signed local staging run, `bump-libs` when moving
  `onfhir.libs.version`, and `new-operation` when adding a FHIR operation.
- Library boundary gates for the reusable `io.onfhir` artifacts (forbidden
  imports, dependency licenses, binary compatibility) live in the sibling
  `onfhir-libs` repository. This reactor has its own gates under `scripts/`,
  covering the server-side package/version boundary and staged-release
  integrity.
