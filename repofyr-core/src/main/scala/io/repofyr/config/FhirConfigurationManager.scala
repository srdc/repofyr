package io.repofyr.config

import io.repofyr.Onfhir
import io.repofyr.operation.DefaultOperationHandlers.DEFAULT_IMPLEMENTED_FHIR_OPERATIONS
import io.onfhir.api.parsers.{FHIRResultParameterResolver, FHIRSearchParameterValueParser}
import io.repofyr.api.service.TargetResourceResolver
import io.repofyr.api.util.FHIRServerUtil
import io.repofyr.api.util.SubscriptionUtil
import io.repofyr.api.validation.FHIRResourceValidator
import io.onfhir.api.validation.{IFhirResourceValidator, IFhirTerminologyValidator}
import io.repofyr.audit.{AuditManager, IFhirAuditCreator}
import io.repofyr.authz.AuthzManager
import io.onfhir.client.{OnFhirNetworkClient, TerminologyServiceClient}
import io.repofyr.db.{MongoDBInitializer, ResourceManager}
import io.repofyr.event.{FhirEventBus, IFhirEventBus}
import io.repofyr.exception.InternalServerException
import io.repofyr.operation.{FhirOperationHandlerFactory, IFhirOperationLibrary}
import io.onfhir.validation.FhirTerminologyValidator
import org.slf4j.{Logger, LoggerFactory}

import scala.util.Try
import io.onfhir.config.FhirServerConfig
import io.onfhir.config.FSConfigReader

/**
 * Created by tuncay on 11/14/2016.
 * Central OnFhir configurations defining FHIR server capabilities
 */
object FhirConfigurationManager extends IFhirConfigurationManager {
  protected val logger: Logger = LoggerFactory.getLogger(this.getClass)
  //FHIR capabilities configurations
  var fhirConfig: FhirServerConfig = _
  //FHIR Resource validator
  var fhirValidator: IFhirResourceValidator = _
  //FHIR terminology validator
  var fhirTerminologyValidator: IFhirTerminologyValidator = _
  //Audit generator in FHIR AuditEvent format based on the specific version
  var fhirAuditCreator: IFhirAuditCreator = _
  //Authorization manager
  var authzManager:AuthzManager = _
  var targetResourceResolver:TargetResourceResolver = _
  //FHIR resource manager
  var resourceManager:ResourceManager = _
  //FHIR event manager
  var eventManager:IFhirEventBus = _
  //FHIR Operation handler factories
  var fhirOperationImplLibraries:Seq[IFhirOperationLibrary] = _
  //Other utilities
  var fhirSearchParameterValueParser:FHIRSearchParameterValueParser = _
  var fhirResultParameterResolver:FHIRResultParameterResolver = _
  var fhirServerUtil:FHIRServerUtil = _
  //FHIR-release-specific Subscription parsing and validation strategy
  var subscriptionUtil: SubscriptionUtil = _
  /**
   * Read FHIR foundational definitions and configure the platform
   *
   * @param fhirConfigurator        Specific FHIR configurator for the FHIR version to be supported
   * @param fhirOperationFactories  Factories/libraries providing implementation for configured FHIR Operations
   */
  def initialize(fhirConfigurator: IFhirServerConfigurator, fhirOperationFactories: Seq[IFhirOperationLibrary]): Unit = {
    val fsConfigReader = new FSConfigReader(
      fhirVersion = fhirConfigurator.fhirVersion,
      fhirStandardZipFilePath = OnfhirConfig.baseDefinitions,
      conformancePath = OnfhirConfig.conformancePath,
      profilesPath = OnfhirConfig.profilesPath,
      valueSetsPath = OnfhirConfig.valueSetsPath,
      codeSystemsPath = OnfhirConfig.codeSystemsPath,
      searchParametersPath = OnfhirConfig.searchParametersPath,
      operationDefinitionsPath = OnfhirConfig.operationDefinitionsPath,
      compartmentDefinitionsPath = OnfhirConfig.compartmentDefinitionsPath
    )
    //Initialize platform, and save the configuration
    fhirConfig =
      fhirConfigurator
        .initializeServerPlatform(
          configReader = fsConfigReader,
          fhirOperationsImplemented =
            DEFAULT_IMPLEMENTED_FHIR_OPERATIONS.keySet ++ //Default FHIR operations
              fhirOperationFactories.flatMap(_.listSupportedOperations()).toSet //Provided implementation factories
        )
    subscriptionUtil = fhirConfigurator.getSubscriptionUtil(
      fhirConfig,
      OnfhirConfig.fhirSubscriptionSettings,
      OnfhirConfig.fhirRequestDefaults.searchHandling
    )
    val allSupportedOps = fhirConfig.supportedOperations.map(_.url).toSet

    //Initialize operation libraries
    fhirOperationImplLibraries =
      (fhirOperationFactories :+ new FhirOperationHandlerFactory(DEFAULT_IMPLEMENTED_FHIR_OPERATIONS))
        .map(ol => ol -> ol.listSupportedOperations().intersect(allSupportedOps))
        .filter(_._2.nonEmpty)
        .map {
          case (ol, urls) =>
            val failedImpl =
              urls
                .map(url => url -> Try(ol.getOperationHandler(url)(this)))
                .filter(_._2.isFailure)
            if(failedImpl.nonEmpty)
              throw new InternalServerException(s"Operation handlers for FHIR Operations ${failedImpl.map(_._1).mkString(", ")} cannot be created!!!")
            ol
        }

    authzManager = new AuthzManager(this)
    targetResourceResolver = new TargetResourceResolver(this)
    eventManager = new FhirEventBus(fhirConfig)
    resourceManager = new ResourceManager(fhirConfig,eventManager)
    //If it is the first setup or update of the platform (definition of new profile, etc), apply the setups
    if (OnfhirConfig.fhirInitialize) {
      //Read the Value sets
      fhirConfigurator.setupPlatform(fsConfigReader, new MongoDBInitializer(resourceManager), fhirConfig)
    }
    //Initialize FHIR Resource and terminology Validator
    fhirValidator = new FHIRResourceValidator(this)

    //Integrated terminology services
    val integratedTerminologyServices =
      OnfhirConfig
      .integratedTerminologyServices
      .map(_.map(tsConf => tsConf._1 -> new TerminologyServiceClient(OnFhirNetworkClient.apply(tsConf._2)(Onfhir.actorSystem.dispatcher))(Onfhir.actorSystem.dispatcher)))
      .getOrElse(Nil)

    fhirTerminologyValidator = new FhirTerminologyValidator(fhirConfig, integratedTerminologyServices)
    //Initialize FHIR Audit creator if necessary
    OnfhirConfig
      .fhirAuditingConfig
      .foreach(auditConfig =>
        fhirAuditCreator = fhirConfigurator.getAuditCreator(auditConfig)
      )

    fhirSearchParameterValueParser = new FHIRSearchParameterValueParser(
      fhirConfig,
      OnfhirConfig.fhirRequestDefaults.searchHandling)
    fhirResultParameterResolver = new FHIRResultParameterResolver(fhirConfig, OnfhirConfig.fhirResultDefaults)
    fhirServerUtil = new FHIRServerUtil(fhirConfig)
    printSystemRuntime()
  }

  /**
   * Print Memory and Processor details
   */
  private def printSystemRuntime() = {
    logger.info("** Initialization completed ...")
    val mb = 1024 * 1024
    val runtime = Runtime.getRuntime
    logger.info("** Used Memory:  " + (runtime.totalMemory - runtime.freeMemory) / mb + " MB")
    logger.info("** Free Memory:  " + runtime.freeMemory / mb + " MB")
    logger.info("** Total Memory: " + runtime.totalMemory / mb + " MB")
    logger.info("** Max Memory:   " + runtime.maxMemory / mb + " MB")
    logger.info("** Available Processors:" + runtime.availableProcessors())
  }
}
