package io.onfhir.api.parsers

/**
 * Interface for FHIRPath expression resolver for x-fhir-query statement parsing (FHIR query with placeholders)
 */
trait ISearchParamPlaceholderResolver {
  /**
   * Resolve the placeholder in the expression
   * @param spValueExpr Search parameter value expression
   *                    e.g. ge{{5.2 'mg'}} -> ge5.2|http://unitsofmeasure.org|mg
   *                    e.g. {{%hba1cCodes}} -> 1044-2,5488-2  given codes in context parameter
   * @param searchParamType FHIR search parameter type given as hint for expression resolution
   * @return
   */
  def resolveExpression(spValueExpr:String, searchParamType:String):String
}
