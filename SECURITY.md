# Security Policy

Repofyr is a FHIR data repository. A deployment holds protected health
information, so a defect in authorization, auditing, or query handling is a
patient-privacy defect. We take vulnerability reports seriously and prefer
coordinated disclosure.

## Reporting a vulnerability

Please do NOT open a public GitHub issue for a suspected vulnerability.

- Preferred: use GitHub private vulnerability reporting on `srdc/repofyr`
  (Security tab, "Report a vulnerability").
- Alternatively, email `onfhir@srdc.com.tr`.

Include the affected artifact and version, the FHIR release involved (R4,
R5, or STU3), a description, and reproduction steps or a proof of concept
if available. We aim to acknowledge reports within five business days.

## Supported versions

| Version | Supported |
|---|---|
| 4.0.x | yes |
| 3.x and earlier (`io.onfhir` monorepo server line) | no |

The `io.onfhir:onfhir-server-*` artifacts ended at 3.x and receive no
security fixes. Moving to `io.repofyr:repofyr-server-*` 4.0.x is the
supported path; the upgrade needs no configuration or data migration. See
the [migration guide](docs/migration/onfhir-3.x-to-repofyr-4.0.md).

## Scope notes

Repofyr stores and serves PHI, so the following are in scope:

- **Authorization.** Any way to read or write a resource that the presented
  access token does not grant: SMART scope evaluation, compartment
  constraints, token resolution and introspection, and the content
  constraints applied to submitted resources.
- **Auditing.** An interaction that should produce an AuditEvent and does
  not, or an AuditEvent that misattributes the actor or the target.
- **Search parameter handling.** Query construction is where caller input
  reaches the database. Injection into a MongoDB query, a search parameter
  that returns resources outside the caller's authorized set, and a
  `_include`, `_revinclude`, or chained parameter that discloses a resource
  the caller could not read directly are all vulnerabilities.
- **Content is not trusted code.** FHIRPath expressions, profiles, search
  parameter definitions, and mapping templates are data. Server behavior
  that lets such content read process state, the environment, or the
  filesystem is a vulnerability.

## Deployment assumptions

Two properties of the design are intended, and a deployment that ignores
them is misconfigured rather than exploiting a defect:

- `OperationDefinition.name` holds a fully qualified handler class name
  that Repofyr instantiates reflectively. Write access to
  OperationDefinition resources, and to the configured operation
  definitions folder, is therefore an administrative privilege and must not
  be granted to ordinary API clients.
- Running with `server.ssl.keystore` unset, or with
  `fhir.authorization.method` set to `none`, disables transport security or
  authorization by request. Neither is a vulnerability on its own.

Known, deliberately documented gaps are listed in
[`docs/release/known-limitations.md`](docs/release/known-limitations.md).
Those are not vulnerabilities in themselves, but a report showing that one
is exploitable beyond what is documented there is, and is welcome.

## Reusable libraries

Repofyr depends on the `io.onfhir` reusable libraries, released separately
under Apache-2.0. A vulnerability in FHIRPath evaluation, profile or
terminology validation, the FHIR client, the query engine, or the neutral
HTTP models belongs to
[`srdc/onfhir-libs`](https://github.com/srdc/onfhir-libs) and should be
reported through that repository's security policy. If you are not sure
which side a defect is on, report it here and we will route it - the
dividing line is the coordinate: `io.repofyr` here, `io.onfhir` there.
