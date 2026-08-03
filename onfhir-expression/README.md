# onfhir-expression

Defines the language-neutral expression model and dispatch API used to plug
expression languages into onFHIR.

Maven coordinate: `io.onfhir:onfhir-expression_2.13`. Principal APIs are
`FhirExpression`, `IFhirExpressionLanguageHandler`,
`FhirExpressionEvaluator`, and `FhirExpressionException`. It is intentionally
small and has no dependency on Path; language implementations such as the
template engine are separate artifacts.

```scala
import io.onfhir.expression.{FhirExpression, FhirExpressionEvaluator}

val evaluator = new FhirExpressionEvaluator(Seq(myLanguageHandler))
val expression = FhirExpression(
  name = "eligible",
  language = myLanguageHandler.languageSupported,
  expression = Some("status = 'active'")
)
evaluator.validateExpression(expression)
```

Evaluation is asynchronous and requires an implicit Scala `ExecutionContext`.
Unsupported languages fail explicitly; the module does not include a language
implementation on its own.
