# onfhir-path

A standalone Scala FHIRPath parser and evaluator over json4s `JValue` content.
It supports navigation, collection and boolean operations, typed scalar
results, path discovery, environment variables, custom function libraries,
and optional terminology, identity, and reference-resolution services.

Maven coordinate: `io.onfhir:onfhir-path_2.13`. The primary APIs are
`FhirPathEvaluator`, `FhirPathResult`, `FhirPathEnvironment`, and
`IFhirPathFunctionLibraryFactory`. It depends on `onfhir-common`; optional
service integrations are supplied through Common interfaces. It has no server
runtime dependency.

```scala
import io.onfhir.path.FhirPathEvaluator
import org.json4s.jackson.JsonMethods.parse

val patient = parse("""{"resourceType":"Patient","active":true}""")
val evaluator = FhirPathEvaluator()

assert(evaluator.satisfies("Patient.active = true", patient))
assert(evaluator.evaluateBoolean("Patient.active", patient) == Seq(true))
```

Use `withEnvironmentVariable`, `withFunctionLibrary`,
`withTerminologyService`, or `withIdentityService` to configure extensions.
`parseStrict` rejects trailing or malformed input; `parse` retains the
historical parser behavior. Service-backed functions require the corresponding
service implementation, and this library does not fetch FHIR resources by
itself.
