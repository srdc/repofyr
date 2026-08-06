package io.repofyr.stu3.config

import io.onfhir.api.parsers.IFhirFoundationResourceParser
import io.repofyr.api.util.{SubscriptionUtil, UnsupportedSubscriptionUtil}
import io.repofyr.audit.IFhirAuditCreator
import io.repofyr.config.{AuditConfig, BaseFhirServerConfigurator}
import io.onfhir.config.{FhirCapabilityDefaults, FhirSearchHandling, FhirServerConfig, FhirSubscriptionSettings}
import io.repofyr.stu3.audit.STU3AuditCreator
import io.onfhir.stu3.parsers.STU3Parser


class FhirSTU3Configurator extends BaseFhirServerConfigurator {
  override val fhirVersion: String = "STU3"
  override val FHIR_SUMMARIZATION_INDICATOR_CODE_SYSTEM = "http://hl7.org/fhir/v3/ObservationValue"
  /**
   * Return a class that implements the interface to create AuditEvents conformant to the given base specification
   *
   * @return
   */
  override def getAuditCreator(auditConfig: AuditConfig): IFhirAuditCreator = new STU3AuditCreator

  override def getSubscriptionUtil(
      fhirConfig: FhirServerConfig,
      subscriptionSettings: FhirSubscriptionSettings,
      defaultSearchHandling: FhirSearchHandling
  ): SubscriptionUtil = new UnsupportedSubscriptionUtil(fhirVersion)

  /**
   * Return the parser for foundation resources
   *
   * @param complexTypes   List of FHIR complex types defined in the standard
   * @param primitiveTypes List of FHIR primitive types defined in the standard
   * @return
   */
  override def getFoundationResourceParser(complexTypes: Set[String], primitiveTypes: Set[String], capabilityDefaults: FhirCapabilityDefaults): IFhirFoundationResourceParser =
    new STU3Parser(complexTypes, primitiveTypes, capabilityDefaults)
}
