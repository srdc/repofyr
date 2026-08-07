---
name: bump-libs
description: Move the Repofyr server to a new io.onfhir reusable-library release by updating onfhir.libs.version, rebuilding the reactor, and triaging failures against the library migration guide. Use when asked to upgrade, bump, or adopt a new onfhir-libs version.
---

# Bump the onFHIR library version

The server consumes the reusable libraries as external artifacts from
`srdc/onfhir-libs`. `onfhir.libs.version` in the root `pom.xml` is the
single property that selects them, and the boundary gate enforces that
every `io.onfhir` dependency uses it.

The two version lines are independent: a library bump does not change the
server `revision`, and the server version says nothing about which library
release it embeds.

**Constraint:** a library MAJOR may legitimately require server code
changes. Never absorb one silently by patching whatever fails to compile.
Work from the library migration guide so each edit has a documented cause;
an undocumented compile fix usually means the library change was not
understood, and it will resurface at runtime.

## Flow

1. **Read before editing.** In the sibling `srdc/onfhir-libs` repository,
   read the target version's entry in `CHANGELOG.md`, and for a major also
   its migration guide under `docs/migration/`. Note every relocation,
   signature change, and behavior change that touches something the server
   uses. Do this first - it is what makes step 4 triage rather than
   guesswork.
2. **Update the property.** Set `onfhir.libs.version` in the root
   `pom.xml`. That is the only place: no module POM carries a literal
   `io.onfhir` version, and adding one is a boundary-gate failure.
3. **Build the full reactor.** `mvn -B test`. Not a single module - a
   library change can land in any of the seven, and `repofyr-server-stu3`
   has caught breaks the R4 suite missed.
4. **Triage failures against the guide, not the symptom.** For each
   failure, find the migration-guide row that explains it and apply the
   documented change. If a failure has no matching row, that is a gap in
   the library release: report it and, if the libraries are still
   unpublished, raise it in `srdc/onfhir-libs` rather than working around
   it here. Never change an `io.onfhir` coordinate, package, or import to
   make a build pass - the library namespace is not editable from this
   repository.
5. **Check the runtime surface too.** A library change can be
   source-compatible and still move behavior: configuration key handling,
   FHIR definition content, validation strictness, and search-parameter
   parsing all live in the libraries. Read the changelog for those before
   trusting a green build.
6. **Record it.** Add a `CHANGELOG.md` entry naming the old and new
   library versions. For a library major, summarize the server-visible
   consequences and link the library migration guide, so a Repofyr user
   who does not read `onfhir-libs` still learns what changed under them.
7. **Verify.** Run the `verify` skill: full reactor tests plus
   `scripts/check-server-boundary.ps1`. The gate is what proves the bump
   did not introduce a hardcoded library version or a stray
   `${onfhir.libs.version}` on an `io.repofyr` dependency.

## Before a release

A release requires `onfhir.libs.version` to name a released, non-SNAPSHOT
version available on Maven Central (RELEASING.md section 1). Bumping to a
locally installed or staging-only library build is fine for development
but blocks publishing, so say so explicitly when the bump target is not
yet on Central.
