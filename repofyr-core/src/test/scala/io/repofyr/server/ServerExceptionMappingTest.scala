package io.repofyr.server

import io.onfhir.api.model.{FHIRResponse, HttpStatus, OutcomeIssue}
import io.onfhir.api.parsers.BundleRequestParsingException
import io.onfhir.authz.AuthzResult
import io.repofyr.exception._
import io.onfhir.exception._
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class ServerExceptionMappingTest extends Specification {
  private val issue = OutcomeIssue(
    FHIRResponse.SEVERITY_CODES.ERROR,
    FHIRResponse.OUTCOME_CODES.INVALID,
    details = None,
    diagnostics = Some("characterized failure"),
    expression = Nil
  )

  "ErrorHandler" should {
    "preserve the HTTP status selected for every intentional server exception" in {
      val mappings = Seq[Throwable](
        new AuthorizationFailedException(AuthzResult.undecided("denied")),
        new BadRequestException(Seq(issue)),
        new ConflictException(issue),
        new InternalServerException("failed", Seq(issue)),
        new MethodNotAllowedException(Seq(issue)),
        new NotFoundException(Seq(issue)),
        new NotImplementedException(Seq(issue)),
        new NotModifiedException,
        new PreconditionFailedException(Seq(issue)),
        new UnprocessableEntityException(Seq(issue))
      )

      mappings.map(ErrorHandler.fhirErrorHandlerToResponse(_).httpStatus) mustEqual Seq(
        HttpStatus.Unauthorized,
        HttpStatus.BadRequest,
        HttpStatus.Conflict,
        HttpStatus.InternalServerError,
        HttpStatus.MethodNotAllowed,
        HttpStatus.NotFound,
        HttpStatus.NotImplemented,
        HttpStatus.NotModified,
        HttpStatus.PreconditionFailed,
        HttpStatus.UnprocessableContent
      )
    }

    "preserve OutcomeIssue payloads carried by server exceptions" in {
      val response = ErrorHandler.fhirErrorHandlerToResponse(new BadRequestException(Seq(issue)))

      response.outcomeIssues mustEqual Seq(issue)
    }

    "select HTTP 400 only at the server boundary for neutral Bundle parsing failures" in {
      val response = ErrorHandler.fhirErrorHandlerToResponse(
        new BundleRequestParsingException(Seq(issue))
      )

      response.httpStatus mustEqual HttpStatus.BadRequest
      response.outcomeIssues mustEqual Seq(issue)
    }
  }
}
