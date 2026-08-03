package io.onfhir.server

import io.onfhir.api.model.{AuthenticateChallenge, AuthenticationParameters, FHIRResponse, HttpParameter, OutcomeIssue}
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

  def authenticateHeader(authzResult: AuthzResult): AuthenticateChallenge =
    AuthenticateChallenge(
      "Bearer",
      AuthenticationParameters(Vector(
        HttpParameter("realm", "fhir", quoted = true),
        HttpParameter("error", authzResult.errorCodeOrDefault, quoted = true),
        HttpParameter("error_description", authzResult.errorDescOrDefault, quoted = true)
      )))
}
