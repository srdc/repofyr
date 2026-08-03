# Contributing

Thank you for contributing to onFHIR and Repofyr. Before opening a change,
read `AGENTS.md` and the active plan under `docs/plans` when working on the
library/server split.

## Development certificate of origin

Every commit must carry a `Signed-off-by` trailer certifying the
[Developer Certificate of Origin 1.1](https://developercertificate.org/).
Add it with:

```shell
git commit -s
```

By signing off, you certify that you wrote the contribution or otherwise have
the right to submit it under the project's applicable license. Sign-offs are
not a substitute for the explicit historical contributor/IP approval required
before relicensing the extracted library repository.

## Before submitting

- Keep reusable-library code independent of Akka, Pekko, and server runtime
  concerns.
- Record library module relocations and public API changes in the migration
  table in `docs/plans/library-server-split-plan-v2.md`.
- Run the relevant module tests and the `onfhir-server-r4` regression suite.
- Run `powershell -File scripts/check-forbidden-imports.ps1`.
- Do not change license files as part of ordinary code changes.
