package io.repofyr.api.service

import io.onfhir.api.Resource
import io.onfhir.api.model.FHIRRequest
import io.repofyr.api.validation.IResourceSpecificValidator
import io.repofyr.config.FhirConfigurationManager


/**
 * Business Rule Validation for FHIR Subscription
 *
 */
class FHIRSubscriptionBusinessValidator extends IResourceSpecificValidator {

  /**
   * Validate extra business rules for the operation
   *
   * @param fhirRequest
   */
  override def validateRequest(fhirRequest: FHIRRequest): Unit = {
    FhirConfigurationManager.subscriptionUtil.validateRequest(fhirRequest)
  }

  /**
   * Validate rules about changes in the content
   *
   * @param oldContent
   * @param newContent
   */
  override def validateChanges(oldContent: Resource, newContent: Resource): Unit = {
    FhirConfigurationManager.subscriptionUtil.validateChanges(oldContent, newContent)
  }
}
