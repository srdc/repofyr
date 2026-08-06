# ADR 0002: Server Event SPI Boundary

- Status: Accepted; permanent disposition approved
- Date: 2026-07-31; Phase 4 disposition 2026-08-03; renamed in Phase 5B 2026-08-06
- Decision owners: onFHIR / Repofyr maintainers
- Applies to: Repofyr server-family module graph

> Naming note: this ADR was written before Phase 5B. The module it creates was
> `io.onfhir:onfhir-event_2.13` with package `io.onfhir.event`; since Phase 5B
> it is `io.repofyr:repofyr-event_2.13` with package `io.repofyr.event`, and
> the sibling server modules carry `repofyr-` names likewise. The decision and
> its rationale are unchanged; names below are current.

## Context

Event-bus code currently resides in `onfhir-common`, but actor event delivery,
JSON marshalling for that delivery, and Kafka integration are server concerns.
They cannot remain in the Apache-2.0, Akka/Pekko-free library family.

The current server graph also contains an inverted dependency:
`repofyr-core` depends on the optional `repofyr-kafka` adapter. Moving shared
event contracts directly into either module would create or preserve an
undesirable cycle while the in-place split is underway.

## Decision

Phase 1D creates the transitional GPL/server-family Maven module
`io.repofyr:repofyr-event_2.13`.

The following code moves from `onfhir-common` to that module without changing
its Scala package names:

- `io.repofyr.event.*`
- `io.repofyr.util.InternalJsonMarshallers`

Both `repofyr-core` and `repofyr-kafka` declare direct dependencies on
`repofyr-event`. The event module may depend on `onfhir-common` and the
Akka/JSON dependencies needed by the server implementation. No library-family
module may depend on `repofyr-event`.

The transitional graph is:

```text
repofyr-core -> repofyr-kafka -> repofyr-event -> onfhir-common
            -> repofyr-event
```

Core declares its direct event dependency even though a transitive path exists
through Kafka. This makes the contract explicit and prevents accidental
reliance on Kafka as the owner of the event API.

## Architectural Debt

`repofyr-event` is a cycle-breaking workaround, not evidence that four files
deserve a permanent standalone module. The underlying smell is the
`repofyr-core -> repofyr-kafka` dependency.

The preferred end-state is composition at the server bootstrap boundary:

```text
server bootstrap -> repofyr-core
server bootstrap -> repofyr-kafka -> event SPI or core contracts
repofyr-core -X-> repofyr-kafka
```

## Phase 4 Permanent Disposition

The maintainers approved retaining `repofyr-event_2.13` as a deliberately
small, stable, server-only event SPI for the repository split. It remains in
Repofyr and is not part of the reusable library release.

Removing the `repofyr-core -> repofyr-kafka` edge requires a larger server
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
- It did not itself rename `io.onfhir.*` server packages; that rebrand was
  carried out separately by Phase 5B.
