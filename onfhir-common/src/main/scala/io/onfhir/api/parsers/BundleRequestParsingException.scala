package io.onfhir.api.parsers

import io.onfhir.api.model.OutcomeIssue

/**
 * Neutral failure raised while parsing the request entries of a FHIR Bundle.
 * HTTP status selection belongs to the server or client boundary.
 */
final class BundleRequestParsingException(
    val outcomeIssues: Seq[OutcomeIssue],
    cause: Throwable = null
) extends Exception(
      outcomeIssues.flatMap(_.diagnostics).headOption.getOrElse("Invalid FHIR Bundle request"),
      cause
    )
