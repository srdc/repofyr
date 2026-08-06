package io.repofyr.api.util

import io.onfhir.api.Resource
import io.onfhir.api.model.{FHIRRequest, FHIRResponse, FhirSubscription, FhirSubscriptionChannel, OutcomeIssue, Parameter}
import io.repofyr.exception.NotImplementedException

/**
 * Server-owned contract for FHIR-release-specific Subscription behavior.
 * Concrete parsing and validation rules are supplied by the active server
 * release module.
 */
trait SubscriptionUtil {
  def parseFhirSubscription(subscription: Resource): FhirSubscription

  def parseFhirSubscriptionChannel(channel: Resource): FhirSubscriptionChannel

  def parseAndValidateFhirSubscriptionCriteria(criteria: String): (String, Seq[Parameter])

  def validateRequest(fhirRequest: FHIRRequest): Unit

  def validateChanges(oldContent: Resource, newContent: Resource): Unit
}

/** Explicit strategy for server releases whose Subscription mechanism is not implemented. */
final class UnsupportedSubscriptionUtil(fhirVersion: String) extends SubscriptionUtil {
  override def parseFhirSubscription(subscription: Resource): FhirSubscription = unsupported()

  override def parseFhirSubscriptionChannel(channel: Resource): FhirSubscriptionChannel = unsupported()

  override def parseAndValidateFhirSubscriptionCriteria(criteria: String): (String, Seq[Parameter]) = unsupported()

  override def validateRequest(fhirRequest: FHIRRequest): Unit = unsupported()

  override def validateChanges(oldContent: Resource, newContent: Resource): Unit = unsupported()

  private def unsupported(): Nothing =
    throw new NotImplementedException(Seq(
      OutcomeIssue(
        FHIRResponse.SEVERITY_CODES.ERROR,
        FHIRResponse.OUTCOME_CODES.NOT_SUPPORTED,
        None,
        Some(s"FHIR Subscription behavior is not implemented for $fhirVersion."),
        Seq("Subscription")
      )
    ))
}
