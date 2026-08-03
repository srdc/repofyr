# onfhir-common

Foundational, transport-neutral models and utilities shared by the reusable
onFHIR libraries. It contains FHIR JSON aliases/constants, configuration
models, request/response value objects, validation/service interfaces, and
general JSON, date, URI, and I/O helpers.

This module is deliberately **not** a destination for unrelated convenience
code. HTTP routing, response marshalling, persistence, event buses, concrete
server configuration, and release-specific server behavior belong in server
modules; query execution belongs in `onfhir-query`.

Maven coordinate: `io.onfhir:onfhir-common_2.13`. Principal APIs include
`Resource`, `FHIRRequest`, `FHIRResponse`, `FhirServerConfig`,
`FhirRuntimeSettings`, `JsonFormatter`, and the interfaces under
`io.onfhir.api.service` and `io.onfhir.api.validation`. Most other library
modules depend on Common.

```scala
import io.onfhir.config.{FhirEndpointSettings, FhirSearchHandling}

val endpoint = FhirEndpointSettings("https://example.org/fhir")
val handling = FhirSearchHandling.Strict
```

Common does not provide a runnable server or a network client. Add only the
more specific artifacts needed by an application.
