# onfhir-r4

FHIR R4-specific parsing support for the reusable libraries. It translates R4
foundation resources into onFHIR's neutral capability, profile, terminology,
search, and operation configuration models.

Maven coordinate: `io.onfhir:onfhir-r4_2.13`. Principal APIs are `R4Parser`
and `StructureDefinitionParser`. It builds on Common and Validation and does
not contain the R4 HTTP server or subscription runtime; those remain in
`onfhir-server-r4`.

```scala
import io.onfhir.r4.parsers.R4Parser

val parser = new R4Parser()
val compactCapability = parser.parseCapabilityStatement(capabilityJson)
```

Inputs are parsed json4s FHIR resources. Constructor defaults cover the R4
standard primitive/complex types and standard capability defaults; deployments
may supply explicit sets and `FhirCapabilityDefaults`.
