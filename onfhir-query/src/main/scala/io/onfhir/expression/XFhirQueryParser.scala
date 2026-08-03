package io.onfhir.expression

import io.onfhir.api.{FHIR_PARAMETER_CATEGORIES, FHIR_SEARCH_RESULT_PARAMETERS}
import io.onfhir.api.model.Parameter
import io.onfhir.api.parsers.FHIRSearchParameterValueParser
import io.onfhir.config.{FhirSearchHandling, FhirServerConfig}
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
 * @param defaultSearchHandling Handling used when the query has no request-level override
 * @param fhirPathEvaluator   FHIR Path evaluator for placeholder resolution
 */
class XFhirQueryParser(fhirServerConfig: FhirServerConfig,
                       defaultSearchHandling: FhirSearchHandling,
                       fhirPathEvaluator: FhirPathEvaluator) {

  private val fhirQueryParser = new FHIRSearchParameterValueParser(fhirServerConfig, defaultSearchHandling)

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

  /**
   * Parses and validates query shape, preserves placeholders unresolved
   * @param rtype       Resource type that query will be executed on
   * @param queryStmt   The x-fhir-query statement's query part (after ?)
   * @return
   */
  def parseXFhirQueryShape(rtype:String, queryStmt:String):List[Parameter] = {
    val queryParams = XFhirQueryUtil.parseRawQueryPreserveSpecials(queryStmt)
    try {
      fhirQueryParser.parseSearchParameterWithResolver(rtype, queryParams, new PreservePlaceholderResolver)
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
   * Split an x-fhir-query statement to FHIR resource type and optional query part
   * e.g. Observation?code={{...}}&date=ge2015 -> Observation, Some(code={{...}}...)
   * @param query The x-fhir-query statement
   * @return
   */
  def splitResourceTypeAndQuery(query: String): (String, Option[String]) = {
    val trimmed = query.trim
    if (trimmed.isEmpty)
      throw FhirExpressionException("Invalid x-fhir-query: query is empty.")

    val parts = trimmed.split("\\?", 2)
    val rtype = parts(0).trim

    if (rtype.isEmpty)
      throw FhirExpressionException(s"Invalid x-fhir-query: missing resource type in '$query'.")

    val queryPart =
      if (parts.length == 2 && parts(1).nonEmpty) Some(parts(1))
      else None

    rtype -> queryPart
  }


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

  /**
   * Encode the parameter while preserving the FHIRPath placeholders
   * @param parameter Parsed parameter
   * @return
   */
  def encodeParameterPreservingPlaceholders(parameter: Parameter): String = {
    if (!parameter.valuePrefixList.exists(_._2.contains("{{"))) parameter.encode
    else {
      val namePart = parameter.paramCategory match {
        case FHIR_PARAMETER_CATEGORIES.CHAINED =>
          parameter.chain.map(c => c._2 + ":" + c._1).mkString(".") + "." + parameter.name
        case FHIR_PARAMETER_CATEGORIES.REVCHAINED =>
          parameter.chain.map(c => "_has" + c._1 + ":" + c._2).mkString(":") + ":" + parameter.name
        case _ =>
          parameter.name + parameter.suffix
      }

      val valuePart =
        if (parameter.paramCategory == FHIR_PARAMETER_CATEGORIES.RESULT &&
          (parameter.name == FHIR_SEARCH_RESULT_PARAMETERS.INCLUDE || parameter.name ==
            FHIR_SEARCH_RESULT_PARAMETERS.REVINCLUDE))
          parameter.valuePrefixList.map { case (typ, prName) => s"$typ:$prName" }.head
        else
          parameter.valuePrefixList.map { case (prefix, value) => prefix + value }.mkString(",")

      s"$namePart=$valuePart"
    }
  }
}
