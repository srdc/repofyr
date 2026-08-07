# Contributing

Thank you for contributing to Repofyr. Read `AGENTS.md` for the working
contract and the repository boundary, and `RELEASING.md` for how a release
is cut. Read the
[3.x to 4.0.0 migration guide](docs/migration/onfhir-3.x-to-repofyr-4.0.md)
before changing a public server contract.

## Development certificate of origin

Every commit must carry a `Signed-off-by` trailer certifying the
[Developer Certificate of Origin 1.1](https://developercertificate.org/).
Add it with:

```shell
git commit -s
```

By signing off, you certify that you wrote the contribution or otherwise
have the right to submit it under the project's applicable license. The
requirement applies from 4.0.0 forward; commits predating the open-source
release are not retroactively signed off.

## Repository boundary

Repofyr is the server. The reusable models, clients, FHIRPath, query,
configuration, expression, validation, template-engine, and FHIR release
parser code lives in
[`srdc/onfhir-libs`](https://github.com/srdc/onfhir-libs) and is consumed
here as released `io.onfhir` artifacts. Do not copy library sources into
this reactor, and never rename an `io.onfhir` coordinate, package, or
import from here.

- Server-owned code declares `io.repofyr.*` packages. Importing
  `io.onfhir.*` is expected and correct; *declaring* server code in that
  namespace is not.
- Every `io.onfhir` dependency uses `${onfhir.libs.version}`, never
  `${project.version}`. No `io.repofyr` dependency uses
  `${onfhir.libs.version}`.

`scripts/check-server-boundary.ps1` enforces all three mechanically.

## Before submitting

- Run the relevant server module tests, then the `repofyr-server-r4`
  regression suite, which is the main net:
  `mvn -pl repofyr-server-r4 -am test`.
- Run `powershell -File scripts/check-server-boundary.ps1`. It must print
  `check-server-boundary: PASS`. Run it bare rather than piped - under
  PowerShell 5.1 a piped native stderr line becomes a terminating error.
- Record user-visible changes in `CHANGELOG.md`. A change to a server API,
  a configuration key, a packaging convention, or a persistence identifier
  additionally needs a migration guide entry under `docs/migration/` in the
  same change, and is only accepted for the next major release.
- Keep runtime configuration keys, persistence identifiers, and stored-data
  conventions stable. The `onfhir.*` key tree, the `onfhir.subscription`
  Kafka topic, `ONFHIR_HOME`, and the `io.onfhir.path` /
  `io.onfhir.validation` logger names are deliberately unchanged and must
  not be "cleaned up".
- Keep Repofyr's GPL-3.0 license metadata unchanged. The consumed
  `io.onfhir` libraries are Apache-2.0 and the two must not be conflated.
- Keep scripts ASCII-only, for Windows PowerShell 5.1 compatibility.

Agents working in this repository can run the same suite through the
`verify` skill in `.claude/skills/`.
