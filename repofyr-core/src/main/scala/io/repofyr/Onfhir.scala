package io.repofyr

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives.concat
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.settings.ServerSettings
import io.repofyr.api.endpoint.{FHIREndpoint, OnFhirInternalEndpoint}
import io.onfhir.api.model.FHIRRequest
import io.repofyr.audit.{AuditManager, RequestLogManager}
import io.repofyr.authz._
import io.onfhir.authz._
import io.repofyr.config.{FhirConfigurationManager, IFhirServerConfigurator, OnfhirConfig, SSLConfig}
import io.repofyr.db.DBConflictManager
import io.repofyr.event.kafka.{KafkaConfig, KafkaEventProducer}
import io.repofyr.event.{FhirDataEvent, FhirEventSubscription}
import io.repofyr.operation.IFhirOperationLibrary
import org.slf4j.{Logger, LoggerFactory}

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.concurrent._
import scala.io.StdIn
import scala.util.{Failure, Success}

/**
  * Instance of an OnFhir server
  *
  * @param fhirConfigurator      Module that will configure the FHIR capabilities of the server based on the base FHIR version
  * @param fhirOperationLibraries Libraries (factories) that provide the FHIR operation implementations configured within the onfhir
  * @param customAuthorizer      Module to handle authorization with a custom protocol
  * @param customTokenResolver   Module to handle access token resolution with a custom way
  * @param customAuditHandler    Module to handle auditing with a custom strategy
  * @param externalRoutes        External non-fhir routes for the server that uses marshalling and authentication
  * @param cdsHooksRoute         CDS-Hooks compliant CDS route (using onfhir-cds)
  * @param onShutdown            Callbacks run after the HTTP binding has drained and before the
  *                              actor system terminates. Use this for resources started alongside
  *                              the server, such as the embedded MongoDB that repofyr-dev-server
  *                              starts, so they outlive every in-flight request.
  */
class Onfhir(
              val fhirConfigurator:IFhirServerConfigurator,
              val fhirOperationLibraries:Seq[IFhirOperationLibrary],
              val customAuthorizer:Option[IAuthorizer],
              val customTokenResolver:Option[ITokenResolver],
              val customAuditHandler:Option[ICustomAuditHandler],
              val externalRoutes:Seq[(FHIRRequest, (AuthContext, Option[AuthzContext])) => Route],
              val cdsHooksRoute:Option[Route],
              val onShutdown:Seq[() => Unit] = Nil
            )(implicit actorSystem:ActorSystem) extends SSLConfig with FHIREndpoint with OnFhirInternalEndpoint{

  private val logger:Logger = LoggerFactory.getLogger(this.getClass)

  implicit val ec:ExecutionContext = actorSystem.dispatcher
  // FHIR server binding
  private var fhirServerBinding:Http.ServerBinding = _
  // Internal server binding
  private var internalOnFhirServerBinding:Http.ServerBinding = _

  /* Setup or Configure the platform and prepare it for running */
  FhirConfigurationManager.initialize(fhirConfigurator, fhirOperationLibraries)

  /* Setup the authorization module and prepare it for running */
  AuthzConfigurationManager.initialize(customAuthorizer, customTokenResolver)

  //Create audit manager actor, if auditing is enabled
  val auditManager =
    if(OnfhirConfig.fhirAuditingConfig.isDefined)
      Some(Onfhir.actorSystem.actorOf(AuditManager.props(FhirConfigurationManager, customAuditHandler), AuditManager.ACTOR_NAME))
    else
      None

  val requestLogManager =
    Onfhir.actorSystem.actorOf(RequestLogManager.props(), "request-response-logger")

  //Create db conflict manager actor, if transaction is not enabled
  val dbConflictManager =
    if(!OnfhirConfig.mongoDbSettings.useTransaction)
      Some(Onfhir.actorSystem.actorOf(DBConflictManager.props(), DBConflictManager.ACTOR_NAME))
    else
      None


  //Create Kafka producer if enabled or subscription is active
  private val kafkaConfig = new KafkaConfig(OnfhirConfig.config)

  val kafkaEventProducer =
    if(kafkaConfig.kafkaEnabled || OnfhirConfig.fhirSubscriptionSettings.active) {
      val actorRef = Onfhir.actorSystem.actorOf(
        KafkaEventProducer.props(
          kafkaConfig,
          OnfhirConfig.fhirSubscriptionSettings.active,
          FhirConfigurationManager.subscriptionUtil.parseFhirSubscription
        ),
        KafkaEventProducer.ACTOR_NAME
      )

      val fhirSubscriptionAllowedResources =
        if(OnfhirConfig.fhirSubscriptionSettings.active) OnfhirConfig.fhirSubscriptionSettings.allowedResources.map(_.toSeq)
        else Some(Nil)
      val kafkaEnabledResources = kafkaConfig.kafkaEnabledResources
      val resourcesToSendToKafka = (fhirSubscriptionAllowedResources, kafkaEnabledResources) match {
        case (Some(l1), Some(l2)) =>
          if(OnfhirConfig.fhirSubscriptionSettings.active)
            Some(l1 ++ l2 :+ "Subscription")
          else
            Some(l1 ++ l2)
        case (None, _) => None
        case (_, None) => None
      }

      FhirConfigurationManager.eventManager.subscribe(actorRef, FhirEventSubscription(classOf[FhirDataEvent], resourcesToSendToKafka))
      actorRef
    }

  /**
    * Start the server
    */
  def start = {
    // FHIR server definition
    var fhirServer =
      Http()
        .newServerAt(OnfhirConfig.serverSettings.host, OnfhirConfig.serverSettings.port)
        .withSettings(
          ServerSettings(OnfhirConfig.config)
            .withVerboseErrorMessages(true)
        )

    if(OnfhirConfig.serverSettings.ssl.enabled) {
      logger.info("Configuring SSL context...")
      fhirServer = fhirServer.enableHttps(https)
    }
    //Final FHIR route
    val finalRoute =
      cdsHooksRoute match {
        case None => fhirRoute
        case Some(cdsRoute) => concat(cdsRoute, fhirRoute)
      }

    fhirServer
      .bind(finalRoute) onComplete {
        case Success(binding) =>
          fhirServerBinding = binding
          fhirServerBinding.addToCoordinatedShutdown(FiniteDuration.apply(60L, TimeUnit.SECONDS))
          fhirServerBinding.whenTerminated onComplete {
            case Success(t) =>
              logger.info("Closing OnFhir server...")
              // Run after the HTTP binding has drained and before the actor system goes
              // away, so a resource started alongside the server - an embedded database,
              // for instance - outlives every in-flight request. A JVM shutdown hook would
              // not give this ordering: it races Akka's own CoordinatedShutdown hook.
              onShutdown.foreach(hook =>
                try hook()
                catch { case e: Throwable => logger.error("A shutdown hook failed", e) })
              actorSystem.terminate()
              logger.info("OnFhir server is gracefully terminated...")
            case Failure(exception) => logger.error("Problem while gracefully terminating OnFhir server!", exception)
          }
          logger.info("onFHIR FHIR server started on host {} and port {}", OnfhirConfig.serverSettings.host, OnfhirConfig.serverSettings.port)
          //Wait for a shutdown signal
          Await.ready(waitForShutdownSignal(), Duration.Inf)
          fhirServerBinding.terminate(FiniteDuration.apply(60L, TimeUnit.SECONDS))
        case Failure(ex) =>
          logger.error("Problem while binding to the onFhir FHIR server address and port!", ex)
    }

    //If we have internal onFhir api active
    if(OnfhirConfig.serverSettings.internalApi.active){
      val onFhirInternalServer = Http()
        .newServerAt(OnfhirConfig.serverSettings.host, OnfhirConfig.serverSettings.internalApi.port)
        .withSettings(
          ServerSettings(OnfhirConfig.config)
            .withVerboseErrorMessages(true)
        )

      onFhirInternalServer
        .bind(onFhirInternalRoutes) onComplete {
          case Success(binding) =>
            logger.info("OnFhir internal server is started on host {} and port {}...", OnfhirConfig.serverSettings.host, OnfhirConfig.serverSettings.internalApi.port)
            internalOnFhirServerBinding = binding
            internalOnFhirServerBinding.addToCoordinatedShutdown(FiniteDuration.apply(60L, TimeUnit.SECONDS))
            internalOnFhirServerBinding.whenTerminated onComplete {
              case Success(t) =>
                logger.info("OnFhir internal server is gracefully terminated...")
              case Failure(exception) =>
                logger.error("Problem while gracefully terminating OnFhir internal server!", exception)
            }
          case Failure(ex) =>
            logger.error("Problem while binding to the onFhir internal server address and port!", ex)
        }
    }
  }

  /**
   *
   * @return
   */
  protected def waitForShutdownSignal(): Future[Done] = {
    val promise = Promise[Done]()
    sys.addShutdownHook {
      promise.trySuccess(Done)
    }
    Future {
      blocking {
        do {
          val line = StdIn.readLine("Write 'quit' to stop the server...\n")
          if (line.equalsIgnoreCase("quit"))
            promise.trySuccess(Done)
        } while (!promise.isCompleted)
      }
    }
    promise.future
  }
}

/**
  * Companion object to initialize Akka Actor System and OnFhir
  */
object Onfhir {
  // Base Akka Actor system for the whole system. It is handed OnfhirConfig.config rather than
  // left to load its own: Repofyr's akka settings ship in repofyr-reference.conf, which sits
  // above every library reference.conf only in the chain OnfhirConfig assembles. A bare
  // ActorSystem("onfhir") would call ConfigFactory.load() and never see that layer.
  implicit val actorSystem: ActorSystem = ActorSystem("onfhir", OnfhirConfig.config)
  //Singleton onfhir server instance
  private var _instance:Onfhir = null


  def apply(): Onfhir =  _instance

  /**
    * Initialize OnFhir
    * @param fhirConfigurator     Module that will configure the FHIR capabilities of the server based on the version
    * @param fhirOperationLibraries Libraries (factories) that provide the FHIR operation implementations configured within the onfhir
    * @param customAuthorizer     Module to handle authorization with a custom protocol, if not supplied decided based on configurations
    * @param customTokenResolver  Module to handle access token resolution with a custom way, if not supplied decided based on configurations
    * @param customAuditHandler   Module to handle auditing with a custom strategy, if not supplied decided based on configurations
    * @param externalRoutes       External non-fhir routes for the server
    * @param cdsRoute             CDS-Hooks compliant CDS route (using onfhir-cds and repository together)
    * @param onShutdown           Callbacks run after the HTTP binding has drained and before the actor system terminates, for resources started alongside the server
    * @return
    */
  def apply(
             fhirConfigurator:IFhirServerConfigurator,
             fhirOperationLibraries:Seq[IFhirOperationLibrary] = Nil,
             customAuthorizer:Option[IAuthorizer] = None,
             customTokenResolver:Option[ITokenResolver] = None,
             customAuditHandler:Option[ICustomAuditHandler] = None,
             externalRoutes:Seq[(FHIRRequest, (AuthContext, Option[AuthzContext])) => Route] = Nil,
             cdsRoute:Option[Route] = None,
             onShutdown:Seq[() => Unit] = Nil
           ): Onfhir = {

    if(_instance == null)
      _instance = new Onfhir(fhirConfigurator, fhirOperationLibraries, customAuthorizer, customTokenResolver,customAuditHandler,externalRoutes, cdsRoute, onShutdown)
    _instance
  }
}
