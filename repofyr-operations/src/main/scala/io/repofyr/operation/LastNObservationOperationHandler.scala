package io.repofyr.operation

import io.repofyr.api.model.AkkaHttpModelAdapter._

import akka.http.scaladsl.model.StatusCodes
import io.onfhir.api.{FHIR_BUNDLE_TYPES, FHIR_SEARCH_RESULT_PARAMETERS, FHIR_SUMMARY_OPTIONS}
import io.onfhir.api.model.{FHIROperationRequest, FHIROperationResponse, FHIRResponse, OutcomeIssue}
import io.repofyr.api.service.FHIROperationHandlerService
import io.repofyr.api.util.FHIRServerUtil
import io.onfhir.api.util.FHIRUtil
import io.repofyr.config.{IFhirConfigurationManager, OnfhirConfig}
import io.onfhir.config.FhirServerConfig
import io.repofyr.db.ResourceManager
import io.repofyr.exception.BadRequestException
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.Future

/**
 * Handles the FHIR `\$lastn` operation on Observation; see
 * https://www.hl7.org/fhir/observation-operation-lastn.html
 *
 * Returns the most recent `max` Observations (default 1) per code for the
 * requested subject, grouped on `code` and ordered on `date`, as a searchset
 * Bundle in the `return` output parameter. Included resources requested through
 * the query are appended to the Bundle and marked as includes, and the summary
 * and `_elements` parameters are honored when building the entries.
 *
 * Two inputs are mandatory here that the base specification treats more
 * loosely, and each is rejected with a `BadRequestException` carrying an
 * `invalid` OperationOutcome issue:
 *
 *  - `patient` or `subject`, because the operation is defined per subject.
 *  - one of the code parameters (`code`, `code-value-concept`,
 *    `code-value-date`, `code-value-quantity`, `code-value-string`). FHIR
 *    leaves `code` optional, but the grouping expression cannot be built for
 *    multiple coded values, so onFHIR requires it.
 *
 * @param fhirConfigurationManager FHIR configuration manager supplying the
 *                                 resource manager and the Bundle entry builder
 */
class LastNObservationOperationHandler(fhirConfigurationManager:IFhirConfigurationManager)  extends FHIROperationHandlerService(fhirConfigurationManager) {
  private val logger: Logger = LoggerFactory.getLogger("DocumentOperationHandler")

  val paramsOnCode = Set("code", "code-value-concept", "code-value-date", "code-value-quantity", "code-value-string")
  /**
   * Execute the operation and prepare the output parameters for the operation
   *
   * @param operationName    Operation name as defined after '$' symbol e.g. meta-add
   * @param operationRequest Operation Request including the parameters
   * @param resourceType     The resource type that operation is called if exists
   * @param resourceId       The resource id that operation is called if exists
   * @return The response containing the Http status code and the output parameters
   */
  override def executeOperation(operationName: String, operationRequest: FHIROperationRequest, resourceType: Option[String], resourceId: Option[String]): Future[FHIROperationResponse] = {
    val lastOrFirstN = operationRequest.extractParamValue[Int]("max").getOrElse(1) * -1

    if(!operationRequest.queryParams.exists(p => p.name == "patient"  || p.name == "subject"))
      throw new BadRequestException(Seq(
        OutcomeIssue(
          FHIRResponse.SEVERITY_CODES.ERROR,
          FHIRResponse.OUTCOME_CODES.INVALID,
          None,
          Some("Search parameter 'patient' or 'subject' is required for Observation/$lastN operation!"),
          Nil
      )))

    //NOTE: Although FHIR specifies code as optional parameter, in onFHIR we need to specify it as mandatory as we cannot specify groupBy expression for multiple coded values
    val queryParamOnCode = operationRequest.queryParams.find(p => paramsOnCode.contains(p.name))
    if(queryParamOnCode.isEmpty)
      throw new BadRequestException(Seq(
        OutcomeIssue(
          FHIRResponse.SEVERITY_CODES.ERROR,
          FHIRResponse.OUTCOME_CODES.INVALID,
          None,
          Some(s"Search parameter on Observation.code (either ${paramsOnCode.mkString(", ")}) is mandatory in onFhir.io for Observation/lastN operation!"),
          Nil
        )))

    fhirConfigurationManager.resourceManager
      .searchLastOrFirstNResources("Observation", operationRequest.queryParams, List("date"), List("code"), lastOrFirstN, true)
      .map(results => {
        val matchedResources = results._1.flatMap(_._2)
        val includedResources = results._2
        //Whether search results are summarized or not
        val summaryParamValue = operationRequest.queryParams.find(p => p.name == FHIR_SEARCH_RESULT_PARAMETERS.SUMMARY).map(_.valuePrefixList.head._2)
        val isElementsParamExist =  operationRequest.queryParams.exists(p => p.name == FHIR_SEARCH_RESULT_PARAMETERS.ELEMENTS)
        val isSummarized = summaryParamValue.exists(_ != FHIR_SUMMARY_OPTIONS.FALSE) || isElementsParamExist

        //Create FHIR bundle entries
        val bundleEntries =
          matchedResources.map(matchedResource => fhirConfigurationManager.fhirServerUtil.createBundleEntry(matchedResource, FHIR_BUNDLE_TYPES.SEARCH_SET, isSummarized)) ++
            includedResources.map(includedResource => fhirConfigurationManager.fhirServerUtil.createBundleEntry(includedResource, FHIR_BUNDLE_TYPES.SEARCH_SET, isSummarized, isMatched = false))

        logger.debug(s"Returning ${matchedResources.length} resources and ${includedResources.length} documents are marked as include...")
        //Create and return bundle
        val resultBundle = FHIRUtil.createBundle(FHIR_BUNDLE_TYPES.SEARCH_SET, List.empty, bundleEntries, matchedResources.length, summaryParamValue)
        val response = new FHIROperationResponse(StatusCodes.OK)
        response.setComplexOrResourceParam("return", resultBundle)
        response
      })
  }
}
