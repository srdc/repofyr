# CLAUDE.md

Entry point for Claude Code in this repository. The shared agent working
contract is defined once in `AGENTS.md` and imported below - keep behavior
rules there, not duplicated here, so Codex and Claude Code stay in sync.

@AGENTS.md

## Claude-specific notes

- Where to start: `docs/plans/library-server-split-plan-v2.md` for the current
  restructuring work; execute one phase at a time.
- Build/test: Maven, Scala 2.13, from repo root (PowerShell). Prefer targeted
  module runs (`mvn -pl <module> -am test`) while iterating; end each phase
  with the `repofyr-server-r4` suite.
- Library boundary gates (forbidden-import, license, binary-compatibility
  scripts) live in the sibling `onfhir-libs` repository, not here. This
  reactor has no library modules; keep `io.onfhir` limited to library
  dependencies and imports.
