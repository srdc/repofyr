---
name: verify
description: Run the full Repofyr server verification suite - reactor tests plus the boundary gate - in the right order with correct invocation and environment handling. Use when asked to verify the build, run the gates, or check release readiness.
---

# Verify Repofyr

Two steps. Run them in order and report each verdict verbatim.

1. **Full reactor tests**

   ```
   mvn -B test
   ```

   Expect zero failures and zero errors across all modules. Baseline is
   334 tests: `repofyr-server-r4` 162, `repofyr-core` 115,
   `repofyr-server-r5` 23, `repofyr-event` 19, `repofyr-server-stu3` 11,
   `repofyr-kafka` 4.

   `repofyr-operations`, `repofyr-embedded-mongo` and `repofyr-dev-server`
   have no `src/test` at all, so a module with no test output is expected
   rather than a wiring failure. Operations coverage lives in
   `repofyr-server-r4` - `DefaultOperationHandlersTest` resolves every
   entry of the default dispatch table there, because those handler class
   names are strings no compiler checks.

   Treat a total below the baseline as a wiring or discovery failure, not
   a clean run: a module whose tests stop being found still reports
   SUCCESS. Compare the per-module split, not just the total.

2. **Repository boundary gate**

   ```
   powershell -File scripts/check-server-boundary.ps1
   ```

   Expect: `check-server-boundary: PASS - server modules stay in io.repofyr.*`

   It takes no parameters. It checks three things: no `package io.onfhir`
   declaration under any `repofyr-*/src/main`; every `io.onfhir`
   dependency versioned with `${onfhir.libs.version}`; and no
   `io.repofyr` dependency versioned with `${onfhir.libs.version}`.
   `import io.onfhir.*` is legitimate and expected - the reusable
   libraries are consumed - so only a package DECLARATION is a violation.
   A FAIL prints each hit as `relative:line text`; fix the source or the
   POM, never the gate.

There is no binary-compatibility gate and no Akka ban in this repository.
The server legitimately depends on Akka HTTP; those two gates live in
`srdc/onfhir-libs` and do not apply here.

For release staging beyond these two steps, use the `release-stage` skill.

## Environment rules (learned the hard way)

- The `repofyr-server-r4` suite boots a full server on embedded MongoDB at
  `localhost:27019`. A leftover `mongod` from a killed run, or anything
  else holding that port, fails the whole module with errors that look
  like code faults. Check for stray processes before debugging the code.
- Run the gate script bare. Do NOT pipe its output (`| Select-String`,
  `2>&1`, `| tee`): under Windows PowerShell 5.1 with
  `$ErrorActionPreference = "Stop"`, any native stderr line - including a
  harmless JVM warning - becomes a terminating NativeCommandError. Filter
  the captured output afterwards instead.
- Module-scoped builds need `-am` while the working tree is ahead of the
  installed artifacts: `mvn -B -pl <module> -am test`. Without it Maven
  resolves a stale sibling from `~/.m2` and the result is meaningless.
- A killed or crashed Maven run can corrupt zinc incremental state under
  `target/`, producing bogus "X is not a member of package Y" errors. Fix:
  `mvn -B -pl <module> clean`, then rebuild.
- This repository may be worked on by parallel sessions: check `git log`
  before assuming the tree state, and before attributing a failure to your
  own change.
