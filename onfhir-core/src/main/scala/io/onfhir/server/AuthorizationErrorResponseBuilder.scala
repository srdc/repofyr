package io.onfhir.server

import akka.http.scaladsl.model.headers.{HttpChallenge, `WWW-Authenticate`}
import io.onfhir.api.model.{FHIRResponse, OutcomeIssue}
import io.onfhir.authz.AuthzResult

object AuthorizationErrorResponseBuilder {
  def response(authzResult: AuthzResult): FHIRResponse =
    FHIRResponse.authorizationErrorResponse(
      Seq(outcomeIssue(authzResult)),
      Some(authenticateHeader(authzResult))
    )

  def outcomeIssue(authzResult: AuthzResult): OutcomeIssue =
    OutcomeIssue(
      FHIRResponse.SEVERITY_CODES.ERROR,
      FHIRResponse.OUTCOME_CODES.SECURITY,
      None,
      Some(authzResult.errorMessage),
      Seq("Header: Authorization")
    )

  def authenticateHeader(authzResult: AuthzResult): `WWW-Authenticate` =
    `WWW-Authenticate`(
      HttpChallenge(
        "Bearer",
        "fhir",
        Map(
          "error" -> authzResult.errorCodeOrDefault,
          "error_description" -> authzResult.errorDescOrDefault
        )
      )
    )
}
