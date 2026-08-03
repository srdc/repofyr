# ADR 0002: Server Event SPI Boundary

- Status: Accepted; permanent disposition approved
- Date: 2026-07-31; Phase 4 disposition 2026-08-03
- Decision owners: onFHIR / Repofyr maintainers
- Applies to: Repofyr server-family module graph

## Context

Event-bus code currently resides in `onfhir-common`, but actor event delivery,
JSON marshalling for that delivery, and Kafka integration are server concerns.
They cannot remain in the Apache-2.0, Akka/Pekko-free library family.

The current server graph also contains an inverted dependency:
`onfhir-core` depends on the optional `onfhir-kafka` adapter. Moving shared
event contracts directly into either module would create or preserve an
undesirable cycle while the in-place split is underway.

## Decision

Phase 1D creates the transitional GPL/server-family Maven module
`io.onfhir:onfhir-event_2.13`.

The following code moves from `onfhir-common` to that module without changing
its Scala package names:

- `io.onfhir.event.*`
- `io.onfhir.util.InternalJsonMarshallers`

Both `onfhir-core` and `onfhir-kafka` declare direct dependencies on
`onfhir-event`. The event module may depend on `onfhir-common` and the
Akka/JSON dependencies needed by the server implementation. No library-family
module may depend on `onfhir-event`.

The transitional graph is:

```text
onfhir-core -> onfhir-kafka -> onfhir-event -> onfhir-common
            -> onfhir-event
```

Core declares its direct event dependency even though a transitive path exists
through Kafka. This makes the contract explicit and prevents accidental
reliance on Kafka as the owner of the event API.

## Architectural Debt

`onfhir-event` is a cycle-breaking workaround, not evidence that four files
deserve a permanent standalone module. The underlying smell is the
`onfhir-core -> onfhir-kafka` dependency.

The preferred end-state is composition at the server bootstrap boundary:

```text
server bootstrap -> onfhir-core
server bootstrap -> onfhir-kafka -> event SPI or core contracts
onfhir-core -X-> onfhir-kafka
```

## Phase 4 Permanent Disposition

The maintainers approved retaining `onfhir-event_2.13` as a deliberately
small, stable, server-only event SPI for the repository split. It remains in
Repofyr and is not part of the reusable library release.

Removing the `onfhir-core -> onfhir-kafka` edge requires a larger server
bootstrap composition change and is deferred to a separate post-split
refactor. That future refactor may reconsider whether the event SPI still
justifies its own artifact, but it is not a prerequisite for Phase 5.

## Consequences

- Server event and marshalling concerns leave `onfhir-common` in Phase 1D.
- The in-place Maven reactor remains acyclic.
- Repofyr retains a small module and one additional published or internal
  artifact coordinate.
- The remaining core/Kafka inversion is explicit architectural debt rather
  than an implicit reason to destabilize the repository split.

## Non-Goals

- This ADR does not make event APIs part of the reusable onFHIR libraries.
- It does not migrate the server from Akka to Pekko.
- It does not resolve the core/Kafka inversion during Phase 1D.
- It does not rename `io.onfhir.*` server packages; a possible `io.repofyr`
  rebrand is a separate post-split major migration.
