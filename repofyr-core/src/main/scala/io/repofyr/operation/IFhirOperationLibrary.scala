package io.repofyr.operation

import io.repofyr.api.service.FHIROperationHandlerService
import io.repofyr.config.IFhirConfigurationManager

/**
 * Interface for FHIR operation libraries that provides implementation for a set of standard or custom FHIR operations
 */
trait IFhirOperationLibrary {
  /**
   * Return the list of URLs for supported FHIR operations
   * @return
   */
  def listSupportedOperations():Set[String]

  /**
   * Return the service implementation that handles the FHIR Operation
   * @param url Url of the FHIR Operation i.e. FHIR OperationDefinition.url
   * @param fhirConfigurationManager    FHIR configuration manager
   * @return
   */
  def getOperationHandler(url:String)(implicit fhirConfigurationManager: IFhirConfigurationManager):FHIROperationHandlerService
}
