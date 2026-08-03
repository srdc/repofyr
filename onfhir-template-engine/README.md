# onfhir-template-engine

A standalone JSON template language that fills FHIRPath-based placeholders in
FHIR or non-FHIR JSON content. Its language MIME type is
`application/fhir-template+json`.

Maven coordinate: `io.onfhir:onfhir-template-engine` (intentionally no Scala
suffix). The public entry point is `FhirTemplateExpressionHandler`, used
directly or registered with `FhirExpressionEvaluator`. It depends on
Expression and Path, and has no server dependency.

```scala
import io.onfhir.expression.FhirExpression
import io.onfhir.template.FhirTemplateExpressionHandler
import org.json4s.jackson.JsonMethods.parse
import scala.concurrent.ExecutionContext.Implicits.global

val handler = new FhirTemplateExpressionHandler(isSourceContentFhir = true)
val patient = parse("""{"resourceType":"Patient","id":"p1"}""")
val template = FhirExpression(
  "patient-summary",
  handler.languageSupported,
  value = Some(parse("""{"id":"{{ Patient.id }}"}"""))
)
val rendered = handler.evaluateExpression(template, Map.empty, patient)
```

Placeholders are evaluated as FHIRPath. Static and per-call context variables,
custom function libraries, terminology services, and identity services can be
configured on the handler. Whole-value placeholders can preserve JSON types;
embedded placeholders produce strings. The handler cannot be used for boolean
applicability checks and does not load templates from `reference` URLs.

See the executable
[`FhirTemplateReadmeExampleTest`](src/test/scala/io/onfhir/template/FhirTemplateReadmeExampleTest.scala)
and run it with `mvn -pl onfhir-template-engine -am test`.
