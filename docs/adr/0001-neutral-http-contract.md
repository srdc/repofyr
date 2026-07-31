# ADR 0001: Transport-Neutral HTTP Contract

- Status: Accepted
- Date: 2026-07-31
- Decision owners: onFHIR / Repofyr maintainers
- Applies to: library-family public and internal HTTP-facing models

## Context

The reusable onFHIR libraries expose Akka HTTP types in public signatures and
internal models. The library repository must become Akka/Pekko-free without
losing HTTP semantics such as duplicate query parameters, weak entity tags,
unknown status codes, or repeated headers.

Replacing rich Akka types with raw strings, integers, or maps would make the
dependency graph clean while silently weakening the API. The neutral contract
therefore has to be fixed before Phase 2 substitutes types.

## Decision

Neutral HTTP models live under `io.onfhir.api.model` unless a more specific
existing library package owns the behavior. They must be immutable and must
not depend on Akka, Pekko, or a server module.

### URI and query semantics

- Replace Akka `Uri` in library signatures with `java.net.URI`.
- Absolute and relative URIs are both allowed where the owning API currently
  permits them; validation is performed at the call site that requires one.
- Code must not call `normalize` implicitly or reconstruct an unmodified URI
  from decoded components.
- Adapters use raw path and raw query components when forwarding an existing
  URI so percent encoding is preserved.
- Query construction uses an ordered sequence of name/value pairs. It
  preserves duplicate keys, pair order, and the distinction between a missing
  value and an empty value.
- Logical query values are encoded exactly once by the transport boundary.

### Time

- Replace Akka `DateTime` in library contracts with `java.time.Instant`.
- HTTP adapters emit IMF-fixdate in GMT and at HTTP-date second precision.
- Parsers accept the HTTP-date forms required for compatible HTTP handling and
  return an `Instant`.
- Sub-second precision may exist internally but is truncated only when an HTTP
  date is serialized.

This second-precision rule applies only to HTTP date headers such as
`Last-Modified` and `If-Modified-Since`. It does not apply to FHIR `instant`,
FHIR `dateTime`, `Resource.meta.lastUpdated`, `_since`, `_at`, or FHIR search
precision/range handling. Those values retain their FHIR-specific parsers and
serializers, including milliseconds where supplied. `Instant` is the shared
in-memory representation, not a shared wire format.

### Status

- `HttpStatus` represents an integer from 100 through 599.
- Unknown or extension codes within that range are allowed.
- It supplies informational, success, redirection, client-error, and
  server-error classification based on the status-code family.
- A raw `Int` is not the public replacement for Akka `StatusCode`.

### Method

- `HttpMethod` contains a validated HTTP token.
- Standard methods are provided as constants.
- Extension methods are allowed and retain their exact case because HTTP
  method tokens are case-sensitive.

### Media and content types

- `FhirMediaType` contains normalized lowercase main type and subtype plus an
  ordered parameter collection.
- Parameter-name comparison is case-insensitive; parameter values and quoting
  semantics are preserved.
- The representation does not collapse repeated parameter occurrences merely
  by converting them to a map.
- `FhirContentType` contains a `FhirMediaType` and an optional charset.

### Entity tags and conditional values

- `EntityTag` contains an opaque tag value and an explicit weak/strong flag.
- Wildcard conditions and ordered lists of entity tags use distinct algebraic
  variants rather than sentinel strings.
- Parsing and formatting preserve weak tags, wildcards, quoting, list order,
  and multiple values.
- Conditional date values use `Instant` and the HTTP-date boundary rules above.

### Authentication challenges

- `AuthenticateChallenge` contains a scheme and either a `token68` value or an
  ordered collection of authentication parameters, as allowed by HTTP syntax.
- Parameter-name comparison is case-insensitive.
- Formatting preserves parameter values, quoting, escaping, and order without
  lossy split-on-comma parsing.

### Forwarded values

- Forwarded-for and forwarded-host models preserve hop order and repeated
  values.
- Values are validated strings rather than `InetAddress`-only values so legal
  tokens such as `unknown`, host ports, and implementation-specific values are
  not discarded.

### General headers

- Neutral request and response headers use an ordered repeated-value
  collection.
- Header-name lookup is case-insensitive while original values and repeated
  field occurrences are preserved.
- A generic `Map[String, String]` is not sufficient for this contract.

## Compatibility Requirements

Before replacing any Akka type, characterization and contract tests must cover:

- raw and encoded URI round trips;
- duplicate, ordered, missing, and empty query values;
- all status families and unknown valid codes;
- HTTP-date parsing, formatting, and second precision;
- weak/strong entity tags, wildcard conditions, and tag lists;
- standard and extension methods;
- media/content parameters and charsets;
- authentication `token68` and parameter challenges;
- multi-hop forwarded values;
- case-insensitive repeated headers.

Server adapters translate these models to and from Akka HTTP. Akka types must
not cross back into a library signature.

## Consequences

- The library API remains semantically rich while becoming transport-neutral.
- Some public signatures intentionally break and require the major-version
  migration entries already listed in the split plan.
- URI and header adapters require more care than simple string conversion.
- The same models can support the JDK HTTP client in Phase 3 and other future
  transports without adding another public API migration.
