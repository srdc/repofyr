package io.repofyr.config

import io.onfhir.api.model.FhirMediaType
import io.onfhir.api.parsers.IFhirFoundationResourceParser
import io.repofyr.api.util.SubscriptionUtil
import io.onfhir.api.validation.{IFhirResourceValidator, IFhirTerminologyValidator}
import io.onfhir.api.{FHIR_MEDIA_TYPES, FHIR_SEARCH_RESULT_PARAMETERS, FHIR_SEARCH_SPECIAL_PARAMETERS, Resource}
import io.repofyr.audit.IFhirAuditCreator
import io.repofyr.db.BaseDBInitializer
import io.onfhir.config.IFhirVersionConfigurator
import io.onfhir.config.FhirSearchHandling
import io.onfhir.config.FhirServerConfig
import io.onfhir.config.FhirSubscriptionSettings
import io.onfhir.config.IFhirConfigReader

/**
 * Binds a Repofyr server to a single FHIR release. This is the primary server
 * extension point: `Onfhir.apply` takes one implementation, and everything the
 * runtime knows about the FHIR version it is serving comes from it.
 *
 * A configurator has three jobs.
 *
 *  - Startup configuration. `initializeServerPlatform` reads the base standard
 *    bundle and the deployment's own foundation resources (CapabilityStatement,
 *    StructureDefinition, SearchParameter, OperationDefinition,
 *    CompartmentDefinition, ValueSet, CodeSystem) and produces the
 *    `FhirServerConfig` that drives request handling for the life of the
 *    process. `setupPlatform` performs the one-time or on-change database
 *    setup, and runs only when `fhir.initialize` is true.
 *  - Release-specific strategies. `getAuditCreator` supplies the AuditEvent
 *    shape for the release, and `getSubscriptionUtil` supplies its Subscription
 *    parsing and validation rules. `getFoundationResourceParser`, inherited
 *    from `IFhirVersionConfigurator`, supplies the parser for the foundation
 *    resources themselves.
 *  - Release-neutral defaults. The vals and defs declared here - the supported
 *    result and special search parameters, the media types accepted and
 *    produced, the `_format` mapping, and the summarization indicator code
 *    system - hold what is common across releases, so an implementation
 *    overrides only what its release actually changes.
 *
 * Implement this by extending [[BaseFhirServerConfigurator]], which carries the
 * whole `initializeServerPlatform`/`setupPlatform` implementation; a release
 * module then supplies little more than the parser, the audit creator, the
 * Subscription strategy, and the `fhirVersion` label. `FhirR4Configurator`,
 * `FhirR5Configurator`, and `FhirSTU3Configurator` are the shipped examples.
 *
 * The `fhirVersion` label is load-bearing beyond documentation: it selects the
 * release-suffixed definition, conformance, and database index resources on the
 * classpath. It is not the version reported by the server, which comes from the
 * parsed CapabilityStatement.
 */
trait IFhirServerConfigurator extends IFhirVersionConfigurator {
  /**
   * List of FHIR Result parameters this FHIR version support
   */
  val FHIR_RESULT_PARAMETERS: Seq[String] = Seq(
    FHIR_SEARCH_RESULT_PARAMETERS.SORT,
    FHIR_SEARCH_RESULT_PARAMETERS.COUNT,
    FHIR_SEARCH_RESULT_PARAMETERS.SUMMARY,
    FHIR_SEARCH_RESULT_PARAMETERS.ELEMENTS,
    FHIR_SEARCH_RESULT_PARAMETERS.INCLUDE,
    FHIR_SEARCH_RESULT_PARAMETERS.REVINCLUDE,
    FHIR_SEARCH_RESULT_PARAMETERS.PAGE,
    FHIR_SEARCH_RESULT_PARAMETERS.SEARCH_AFTER,
    FHIR_SEARCH_RESULT_PARAMETERS.SEARCH_BEFORE,
    FHIR_SEARCH_RESULT_PARAMETERS.TOTAL,
    FHIR_SEARCH_RESULT_PARAMETERS.CONTAINED,
    FHIR_SEARCH_RESULT_PARAMETERS.CONTAINED_TYPE,
    FHIR_SEARCH_RESULT_PARAMETERS.SINCE,
    FHIR_SEARCH_RESULT_PARAMETERS.AT
  )

  /** List of FHIR Special parameters this FHIR version support */
  var FHIR_SPECIAL_PARAMETERS: Seq[String] = Seq(
    FHIR_SEARCH_SPECIAL_PARAMETERS.ID,
    FHIR_SEARCH_SPECIAL_PARAMETERS.LIST,
    FHIR_SEARCH_SPECIAL_PARAMETERS.QUERY,
    FHIR_SEARCH_SPECIAL_PARAMETERS.FILTER,
    FHIR_SEARCH_SPECIAL_PARAMETERS.HAS,
    FHIR_SEARCH_SPECIAL_PARAMETERS.TEXT,
    FHIR_SEARCH_SPECIAL_PARAMETERS.CONTENT
  )

  val FHIR_JSON_MEDIA_TYPE = FHIR_MEDIA_TYPES.FHIR_JSON_MEDIA_TYPE
  val FHIR_XML_MEDIA_TYPE = FHIR_MEDIA_TYPES.FHIR_XML_MEDIA_TYPE

  /** MediaType configurations for this FHIR version */
  // List of Supported FHIR JSON Media Types
  def FHIR_JSON_MEDIA_TYPES(fhirVersion: String): Seq[FhirMediaType] = Seq(
    FhirMediaType.application("json"),
    FHIR_JSON_MEDIA_TYPE,
    FHIR_JSON_MEDIA_TYPE.withParams(Map("fhirVersion" -> fhirVersion))
  )

  // List of Supported FHIR XML Media Types
  def FHIR_XML_MEDIA_TYPES(fhirVersion: String): Seq[FhirMediaType] = Seq(
    FhirMediaType.application("xml"),
    FHIR_XML_MEDIA_TYPE,
    FHIR_XML_MEDIA_TYPE.withParams(Map("fhirVersion" -> fhirVersion))
  )

  // Patch media types supported by onFHIR
  val FHIR_PATCH_MEDIA_TYPES: Seq[FhirMediaType] = Seq(FHIR_MEDIA_TYPES.FHIR_JSON_PATCH_MEDIA_TYPE)
  //Map from _format param value to actual MediaType
  val FHIR_FORMAT_MIME_TYPE_MAP: Map[String, FhirMediaType] = Map(
    "html" -> FhirMediaType.text("html"),
    "text/html" -> FhirMediaType.text("html"),
    "application/json" -> FhirMediaType.application("json"),
    "application/xml" -> FhirMediaType.application("xml"),
    "application/fhir+json" -> FHIR_JSON_MEDIA_TYPE,
    "application/fhir+xml" -> FHIR_XML_MEDIA_TYPE,
    "json" -> FHIR_JSON_MEDIA_TYPE,
    "xml" -> FHIR_XML_MEDIA_TYPE,
    "text/xml" -> FhirMediaType.text("xml")
  )
  //Default media type used when no match
  val FHIR_DEFAULT_MEDIA_TYPE: FhirMediaType = FHIR_JSON_MEDIA_TYPE

  //Code system to indicate a search result is summarized
  val FHIR_SUMMARIZATION_INDICATOR_CODE_SYSTEM = "http://terminology.hl7.org/CodeSystem/v3-ObservationValue"


  /**
   * Parse the base FHIR standard bundle and supplied FHIR foundation resources and provide a configuration for the server
   * @param configReader        Reader for configuration files (FHIR standard, Foundation resources)
   * @param fhirOperationsImplemented   URLs of FHIR Operation implementations that an implementation is provided
   * @return
   */
  def initializeServerPlatform(configReader: IFhirConfigReader,  fhirOperationsImplemented:Set[String]): FhirServerConfig


  /**
   * Setup the platform (database initialization) for the first time (or updated the configurations)
   * @param configReader        Configuration reader
   * @param baseDBInitializer   Database initializer
   * @param fhirConfig          FHIR configuration
   */
  def setupPlatform(configReader: IFhirConfigReader,
                    baseDBInitializer: BaseDBInitializer,
                    fhirConfig: FhirServerConfig):Unit



  /**
   * Return a class that implements the interface to create AuditEvents compliant to the given base specification
   * @param auditConfig Auditing configuration
   * @return
   */
  def getAuditCreator(auditConfig: AuditConfig): IFhirAuditCreator

  /** Create the Subscription strategy for this FHIR release. */
  def getSubscriptionUtil(
      fhirConfig: FhirServerConfig,
      subscriptionSettings: FhirSubscriptionSettings,
      defaultSearchHandling: FhirSearchHandling
  ): SubscriptionUtil
}
