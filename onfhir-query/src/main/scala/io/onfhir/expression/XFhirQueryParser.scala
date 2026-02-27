package io.onfhir.expression

import io.onfhir.api.model.Parameter
import io.onfhir.api.parsers.FHIRSearchParameterValueParser
import io.onfhir.config.FhirServerConfig
import io.onfhir.exception.InvalidParameterException
import io.onfhir.path.FhirPathEvaluator
import org.json4s.JsonAST.{JNothing, JValue}

/**
 * FHIR configuration aware x-fhir-query statement parser and resolver
 *
 * e.g. Observation?date=lt{{today()}}&code={{%hba1cCodes}} -->
 *        Observation?date=lt2026-02-27&code=http://loinc.org|5384-4,http://loinc.org|5382-4
 *
 * @param fhirServerConfig    Target FHIR server configuration
 * @param fhirPathEvaluator   FHIR Path evaluator for placeholder resolution
 */
class XFhirQueryParser(fhirServerConfig: FhirServerConfig,
                       fhirPathEvaluator: FhirPathEvaluator) {

  private val fhirQueryParser = new FHIRSearchParameterValueParser(fhirServerConfig)

  /**
   * Parse/validate a given x-fhir-query statement against the server config by resolving the FHIRPath expressions in the placeholders
   * @param rtype             Resource type that query will be executed on
   * @param queryStmt         The x-fhir-query statement's query part (after ?)
   *                          e.g. code=http://loinc.org|65972-2&date=gt{{today()-7 days}}&subject={{%patient.id}}
   * @param contextParams     Context parameters for FHIRPath expression resolution
   * @param input             Input given for FHIRPath expression resolution
   *                          Note: used for CDS Prefetch queries e.g. MedicationRequest?patient={{context.patientId}}&status=active
   * @return
   */
  def parseXFhirQuery(rtype:String, queryStmt:String, contextParams:Map[String, JValue] = Map.empty, input:JValue = JNothing):List[Parameter] = {
    val queryParams = XFhirQueryUtil.parseRawQueryPreserveSpecials(queryStmt)
    val resolver = new FhirQueryPlaceholderResolver(fhirPathEvaluator, contextParams, input)
    try {
      fhirQueryParser.parseSearchParameterWithResolver(rtype, queryParams, resolver)
    } catch {
      case ex:InvalidParameterException =>
        throw FhirExpressionException(s"Invalid x-fhir-query!", expression = Some(s"$rtype?$queryStmt"), t = Some(ex))
    }
  }
}

object XFhirQueryUtil {

  // Assumption:
  // - each parameter value has at most one non-nested {{...}} placeholder
  // - braces only appear as part of that placeholder syntax
  private val TopLevelAmpersandSplit =
    "&(?=(?:[^{}]*\\{\\{[^{}]*\\}\\})?[^{}]*$)"

  private val TopLevelEqualsSplit =
    "=(?=(?:[^{}]*\\{\\{[^{}]*\\}\\})?[^{}]*$)"


  /**
   * Parse raw FHIR query part supplied
   * As x-fhir-query may include special parameters of FHIR Path
   * @param query Query statement (after ?)
   * @return
   */
  def parseRawQueryPreserveSpecials(query: String): Map[String, List[String]] = {
    query.split(TopLevelAmpersandSplit)
      .toList
      .filter(_.nonEmpty)
      .map { part =>
        val kv = part.split(TopLevelEqualsSplit, 2)
        if (kv.length == 2) kv(0) -> kv(1)
        else kv(0) -> ""
      }
      .groupMap(_._1)(_._2)
  }

}
