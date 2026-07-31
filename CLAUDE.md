# CLAUDE.md

Entry point for Claude Code in this repository. The shared agent working
contract is defined once in `AGENTS.md` and imported below - keep behavior
rules there, not duplicated here, so Codex and Claude Code stay in sync.

@AGENTS.md

## Claude-specific notes

- Where to start: `docs/plans/library-server-split-plan.md` for the current
  restructuring work; execute one phase at a time.
- Build/test: Maven, Scala 2.13, from repo root (PowerShell). Prefer targeted
  module runs (`mvn -pl <module> -am test`) while iterating; end each phase
  with the `onfhir-server-r4` suite.
- Deterministic check: `powershell -File scripts\check-forbidden-imports.ps1`
  reports Akka/Pekko imports in library modules. Run it after any change to a
  library module; the count must never increase.
