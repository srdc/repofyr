package io.repofyr.api.parsers

import akka.http.scaladsl.server.{Directive1, Directives}
import io.onfhir.api.model.Parameter
import io.onfhir.api.parsers.FHIRSearchParameterValueParser

/**
 * Akka HTTP adapter for the transport-neutral FHIR search parameter parser.
 */
final class FHIRSearchParameterValueParserDirectives(parser: FHIRSearchParameterValueParser) {

  /** Parse search parameters from the request URI query. */
  def parseSearchParametersFromUri(
    resourceType: String,
    preferHeader: Option[String]
  ): Directive1[List[Parameter]] =
    Directives.parameterMultiMap.map(parser.parseSearchParameters(resourceType, _, preferHeader))

  /** Parse search parameters from an application/x-www-form-urlencoded entity. */
  def parseSearchParametersFromEntity(
    resourceType: String,
    preferHeader: Option[String]
  ): Directive1[List[Parameter]] =
    Directives.formFieldMultiMap.map(parser.parseSearchParameters(resourceType, _, preferHeader))
}
