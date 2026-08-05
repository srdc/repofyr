# Contributing

Thank you for contributing to Repofyr. Read `AGENTS.md` and the active plan
under `docs/plans` before changing repository architecture or public server
contracts.

## Development certificate of origin

Every commit must carry a `Signed-off-by` trailer certifying the
[Developer Certificate of Origin 1.1](https://developercertificate.org/).
Add it with:

```shell
git commit -s
```

By signing off, you certify that you wrote the contribution or otherwise have
the right to submit it under the project's applicable license.

## Before submitting

- Keep reusable `io.onfhir` library dependencies external to this reactor.
- Use `onfhir.libs.version` for every reusable-library dependency.
- Run the relevant server module tests and the `onfhir-server-r4` regression
  suite.
- Keep Repofyr GPL-3.0 license metadata unchanged during the split.
