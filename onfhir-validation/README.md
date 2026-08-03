# onfhir-validation

Validates json4s FHIR content against parsed StructureDefinition constraints,
terminology bindings, references, cardinalities, fixed/pattern values, types,
and FHIRPath invariants.

Maven coordinate: `io.onfhir:onfhir-validation_2.13`. Principal APIs include
`FhirContentValidator`, `FhirTerminologyValidator`,
`AbstractStructureDefinitionParser`, `BaseFhirProfileHandler`, and the
restriction model. It depends on Common and Path. Release-specific structure
definition parsing is supplied by modules such as `onfhir-r4`.

```scala
import io.onfhir.validation.FhirContentValidator
import scala.concurrent.ExecutionContext.Implicits.global

val configured = loadFhirConfigurationWithProfiles()
val validator = new FhirContentValidator(
  configured,
  "http://hl7.org/fhir/StructureDefinition/Patient"
)
val issues = validator.validateComplexContent(patientJsonObject)
```

The example placeholders must be supplied as json4s `JObject` and a populated
`BaseFhirConfig`; validation cannot work from a profile URL alone. Reference
and terminology checks require optional resolver/service implementations.
Results are `Future[Seq[OutcomeIssue]]`. This module does not persist content
or translate issues into HTTP responses.
