# ADR 0002: Transitional onfhir-event Boundary

- Status: Accepted
- Date: 2026-07-31
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

By the Phase 4 exit, maintainers must record one of two outcomes:

1. retain `onfhir-event` as a deliberately small, stable server event SPI; or
2. eliminate it after dependency inversion makes the separate module
   unnecessary.

The physical repository split must not proceed without that decision.

## Consequences

- Server event and marshalling concerns leave `onfhir-common` in Phase 1D.
- The in-place Maven reactor remains acyclic.
- Repofyr temporarily gains a small module and one additional published or
  internal artifact coordinate.
- The workaround is visible and time-bounded, reducing the chance that it is
  mistaken for the intended permanent architecture.

## Non-Goals

- This ADR does not make event APIs part of the reusable onFHIR libraries.
- It does not migrate the server from Akka to Pekko.
- It does not resolve the core/Kafka inversion during Phase 1D.
- It does not rename `io.onfhir.*` server packages; a possible `io.repofyr`
  rebrand is a separate post-split major migration.
