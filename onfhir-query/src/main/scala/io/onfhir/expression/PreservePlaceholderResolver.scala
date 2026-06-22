package io.onfhir.expression

import io.onfhir.api.parsers.ISearchParamPlaceholderResolver
import io.onfhir.path.{FhirPathEvaluator, FhirPathException}

/**
 * Just keep the FHIR Path expression as it is (do not resolve yet), used for parsing
 */
class PreservePlaceholderResolver extends ISearchParamPlaceholderResolver {
  override def resolveExpression(spValueExpr: String, searchParamType: String,  modifier:String, prefix:String): String = {
    try {
      FhirPathEvaluator.parseStrict(spValueExpr)
      s"{{$spValueExpr}}"
    } catch {
      case e: FhirPathException =>
        throw FhirExpressionException(
          s"Invalid FHIRPath placeholder expression: $spValueExpr",
          expression = Some(spValueExpr),
          t = Some(e)
        )
    }
  }
}
