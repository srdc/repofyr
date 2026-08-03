package io.onfhir.api.parsers

import io.onfhir.api.model.{OrderedQuery, Parameter}
import io.onfhir.config.{FhirSearchHandling, FhirServerConfig}

import java.net.URI

/**
 * A utility class to parse/validate a FHIR query statement against the fhir configuration
 * @param fhirServerConfig      The FHIR server configuration
 * @param defaultSearchHandling Handling used when the query has no request-level override
 */
class FhirQueryParser(fhirServerConfig: FhirServerConfig, defaultSearchHandling: FhirSearchHandling) {
  private val searchParamParser = new FHIRSearchParameterValueParser(fhirServerConfig, defaultSearchHandling)

  /**
   * Parse the given x-fhir-query statement without any FHIR Path referencing
   * e.g. Patient?gender=male
   *
   * @param query FHIR Query statement
   * @return
   */
  def parseQuery(query: String): (String, List[Parameter]) = {
    val uri = URI.create(query)
    val queryParams = OrderedQuery.parse(Option(uri.getRawQuery).getOrElse("")).toMultiMap
    val pathSegments = Option(uri.getPath).getOrElse("").split("/").filter(_.nonEmpty)
    val rtype = pathSegments match {
      case Array(resourceType) => resourceType
      case _ => throw new IllegalArgumentException("Invalid FHIR query, FHIR resource type is missing")
    }

    rtype -> searchParamParser.parseSearchParameters(rtype, queryParams)
  }

  /**
   * Parse the given FHIR query for the specified FHIR Resource type
   *
   * @param rtype FHIR resource type
   * @param query FHIR Query statement
   *              e.g. ?code=...&value=...
   * @return
   */
  private def parseQuery(rtype: String, query: String): List[Parameter] = {
    val queryParams =
      OrderedQuery
        .parse(Option(URI.create(query).getRawQuery).getOrElse(""))
        .toMultiMap

    searchParamParser.parseSearchParameters(rtype, queryParams)
  }
}
