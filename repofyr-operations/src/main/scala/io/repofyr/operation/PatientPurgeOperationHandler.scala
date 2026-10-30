package io.repofyr.operation

import io.repofyr.api.model.AkkaHttpModelAdapter._

import akka.http.scaladsl.model.StatusCodes
import io.onfhir.api.{FHIR_PARAMETER_CATEGORIES, FHIR_PARAMETER_TYPES, FHIR_SEARCH_SPECIAL_PARAMETERS}
import io.onfhir.api.model.{FHIROperationRequest, FHIROperationResponse, FHIRResponse, OutcomeIssue, Parameter}
import io.repofyr.api.service.FHIROperationHandlerService
import io.repofyr.config.IFhirConfigurationManager
import io.repofyr.exception.{MethodNotAllowedException, NotImplementedException}
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.Future

/**
 * Handles the Patient $purge operation (http://hl7.org/fhir/OperationDefinition/Patient-purge, defined in FHIR 6.0.0-ballot3)
 * Permanently removes all current and historical versions of the resources in the Patient compartment and the Patient
 * itself, e.g. for GDPR erasure requests. Nothing is moved to history and no resource events are published.
 *
 * @param fhirConfigurationManager FHIR configuration manager
 */
class PatientPurgeOperationHandler(fhirConfigurationManager:IFhirConfigurationManager) extends FHIROperationHandlerService(fhirConfigurationManager) {
  private val logger: Logger = LoggerFactory.getLogger("PatientPurgeOperationHandler")

  /**
   * Patient compartment relations (resource type -> search parameters linking it to the patient), if configured
   */
  private val patientCompartment: Option[Map[String, Set[String]]] =
    fhirConfigurationManager.fhirConfig.compartmentRelations.get("Patient")

  /**
   * Resource types in the Patient compartment to purge through the compartment (only the ones supported by the server)
   * The Patient itself is not among them; it is purged by its id, as compartment membership via 'link' would also
   * remove other Patient records that merely point at this one
   */
  private val resourceTypesToPurge: Seq[String] =
    patientCompartment
      .map(_.keySet
        .diff(PatientPurgeOperationHandler.EXCLUDED_RESOURCES + "Patient")
        .intersect(fhirConfigurationManager.fhirConfig.resourceConfigurations.keySet)
        .toSeq.sorted)
      .getOrElse(Nil)

  /**
   * Handles the purge operation
   * @param operationName     Operation name as defined after '$' symbol i.e. purge
   * @param operationRequest  Operation Request (no input parameters)
   * @param resourceType      Patient
   * @param resourceId        Patient id
   * @return The response containing the OperationOutcome
   */
  override def executeOperation(operationName: String, operationRequest: FHIROperationRequest, resourceType: Option[String], resourceId: Option[String]): Future[FHIROperationResponse] = {
    //If Patient compartment is not defined, we cannot find the patient's data
    if(patientCompartment.isEmpty)
      throw new MethodNotAllowedException(Seq(
        OutcomeIssue(
          FHIRResponse.SEVERITY_CODES.ERROR,
          FHIRResponse.OUTCOME_CODES.NOT_SUPPORTED,
          None,
          Some(s"The operation 'purge' is not supported as Patient compartment is not configured for this server!"),
          Nil
        )
      ))
    //Check every compartment parameter can be queried before deleting anything, so a misconfiguration
    //fails the whole purge up front instead of halfway, and never leaves data reachable only through an unsupported parameter
    val unsupported =
      PatientPurgeOperationHandler.findUnsupportedCompartmentParams(
        patientCompartment.get,
        resourceTypesToPurge,
        rtype => fhirConfigurationManager.fhirConfig.getSupportedParameters(rtype).keySet
      )
    if(unsupported.nonEmpty)
      throw new NotImplementedException(Seq(
        OutcomeIssue(
          FHIRResponse.SEVERITY_CODES.ERROR,
          FHIRResponse.OUTCOME_CODES.NOT_SUPPORTED,
          None,
          Some(s"The operation 'purge' cannot reach all data in the Patient compartment, as the following search parameters of the compartment are not supported by this server: ${unsupported.mkString(", ")}. Nothing is purged; declare them in the CapabilityStatement!"),
          Nil
        )
      ))

    val pid = resourceId.get
    logger.info(s"Purging all data of Patient/$pid ...")
    //Purge the compartment sequentially and the Patient last, so a failed run still finds the Patient and can be retried
    purgeCompartment(pid, resourceTypesToPurge)
      .flatMap(compartmentCounts =>
        fhirConfigurationManager.resourceManager
          .purgeResources("Patient", List(Parameter(FHIR_PARAMETER_CATEGORIES.SPECIAL, FHIR_PARAMETER_TYPES.TOKEN, FHIR_SEARCH_SPECIAL_PARAMETERS.ID, Seq("" -> pid))))
          .map(patientCount => compartmentCounts :+ ("Patient" -> patientCount))
      )
      .map(counts => {
        val removed = counts.filter(_._2 > 0).map(c => s"${c._1}=${c._2}").mkString(", ")
        logger.info(s"Purged Patient/$pid, removed versions: $removed")
        // 'success' severity only exists from FHIR 6, so R4/R5 report the outcome as information
        val outcome = FHIRResponse.createOperationOutcome(Seq(
          OutcomeIssue(
            FHIRResponse.SEVERITY_CODES.INFORMATION,
            FHIRResponse.OUTCOME_CODES.INFORMATIONAL,
            None,
            Some(s"All current and historical versions of Patient/$pid and the resources in its compartment (except ${PatientPurgeOperationHandler.EXCLUDED_RESOURCES.toSeq.sorted.mkString(", ")}) are purged. Removed versions per resource type: $removed"),
            Nil
          )
        ))
        val response = new FHIROperationResponse(StatusCodes.OK)
        response.setComplexOrResourceParam("return", outcome)
        response
      })
  }

  /**
   * Purge the resources of the given types in the patient's compartment one type after another
   * @param pid     Patient id
   * @param rtypes  Remaining resource types
   * @return Number of removed versions for each resource type
   */
  private def purgeCompartment(pid:String, rtypes:Seq[String]):Future[Seq[(String, Long)]] = {
    rtypes.foldLeft(Future.successful(Seq.empty[(String, Long)]))((acc, rtype) =>
      acc.flatMap(counts =>
        fhirConfigurationManager.resourceManager
          .purgeResources(rtype, List(fhirConfigurationManager.fhirSearchParameterValueParser.constructCompartmentSearchParameter("Patient", pid, rtype)))
          .map(c => counts :+ (rtype -> c))
      )
    )
  }
}

object PatientPurgeOperationHandler {
  /**
   * Resource types in the Patient compartment that are not purged;
   *  - AuditEvent keeps the audit trail (including the record of this purge)
   *  - Group is shared among patients (member), purging it would remove a whole cohort
   */
  final val EXCLUDED_RESOURCES = Set("AuditEvent", "Group")

  /**
   * Find the compartment search parameters that the server cannot query for the given resource types
   * @param compartmentRelations  Resource type -> search parameters linking it to the Patient compartment
   * @param rtypes                Resource types to purge
   * @param supportedParams       Search parameters the server supports for a resource type
   * @return Unsupported parameters as ResourceType.param, sorted
   */
  def findUnsupportedCompartmentParams(compartmentRelations:Map[String, Set[String]], rtypes:Seq[String], supportedParams:String => Set[String]):Seq[String] = {
    rtypes.flatMap(rtype =>
      compartmentRelations.getOrElse(rtype, Set.empty[String])
        .filterNot(_ == "_id") //Resolved directly by the query builder, not through a search parameter
        .diff(supportedParams(rtype))
        .map(p => s"$rtype.$p")
    ).sorted
  }
}
