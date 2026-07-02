package io.onfhir.server

import akka.http.scaladsl.model.StatusCodes
import io.onfhir.authz.AuthzResult
import io.onfhir.exception.{AuthorizationFailedException, AuthorizationFailedRejection}
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class AuthorizationErrorResponseBuilderTest extends Specification {
  "Authorization error response handling" should {
    "convert undecided authorization rejections without unsafe Option access" in {
      val response =
        FHIRRejectionHandler.fhirRejectionToResponse(
          AuthorizationFailedRejection(AuthzResult.undecided("Cannot decide"))
        )

      response.httpStatus === StatusCodes.Unauthorized
      response.outcomeIssues.head.diagnostics must beSome("Error: invalid_request; Cannot decide")
      response.authenticateHeader.map(_.value()) must beSome((value: String) => value.contains("invalid_request"))
    }

    "convert undecided authorization exceptions without unsafe Option access" in {
      val response =
        ErrorHandler.fhirErrorHandlerToResponse(
          new AuthorizationFailedException(AuthzResult.undecided("Cannot decide"))
        )

      response.httpStatus === StatusCodes.Unauthorized
      response.outcomeIssues.head.diagnostics must beSome("Error: invalid_request; Cannot decide")
    }

    "supply safe defaults for incomplete authorization failures" in {
      val result = AuthzResult(AuthzResult.UNDECIDED)

      result.toOutcomeIssue.flatMap(_.diagnostics) must beSome("Error: invalid_request; Authorization failed")
    }
  }
}
