# onfhir-config

Builds reusable onFHIR configuration models from FHIR infrastructure
resources. It reads CapabilityStatement, StructureDefinition,
SearchParameter, ValueSet, CodeSystem, OperationDefinition, and
CompartmentDefinition content from files, ZIPs, or a FHIR API.

Maven coordinate: `io.onfhir:onfhir-config_2.13`. Principal APIs include
`FSConfigReader`, `FhirApiConfigReader`, `BaseConfigReader`,
`BaseFhirConfigurator`, and `SearchParameterConfigurator`. It composes Common,
Client, and Validation; concrete release configurators live in the relevant
FHIR release/server modules.

```scala
import io.onfhir.config.FSConfigReader

val reader = new FSConfigReader(
  fhirVersion = "R4",
  fhirStandardZipFilePath = Some("definitions.json.zip"),
  profilesPath = Some("profiles")
)
val capabilityStatement = reader.readCapabilityStatement()
```

This module reads and interprets definitions; it does not start a FHIR server,
configure persistence, or own global runtime configuration.
