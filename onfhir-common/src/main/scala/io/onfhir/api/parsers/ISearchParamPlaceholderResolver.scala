package io.onfhir.api.parsers

/**
 * Interface for FHIRPath expression resolver for x-fhir-query statement parsing (FHIR query with placeholders)
 */
trait ISearchParamPlaceholderResolver {
  /**
   * Resolve the placeholder in the expression
   * @param spValueExpr Search parameter value expression
   *                    e.g. {{5.2 'mg'}} -> 5.2|http://unitsofmeasure.org|mg
   *                    e.g. {{%hba1cCodes}} -> http://loing.org|1044-2,http://loing.org|5488-2  given codes in context parameter
   * @param searchParamType FHIR search parameter type given as hint for expression resolution
   * @param modifier        Modifier used for search if exists, empty string otherwise
   * @param prefix          Prefix used for search if exists, empty string otherwise
   * @return
   */
  def resolveExpression(spValueExpr:String, searchParamType:String, modifier:String, prefix:String):String
}
