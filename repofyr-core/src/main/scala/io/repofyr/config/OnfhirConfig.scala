package io.repofyr.config

import java.time.Duration
import com.typesafe.config.{Config, ConfigFactory}
import io.onfhir.api.util.FHIRUtil
import io.onfhir.api.FHIR_VALIDATION_ALTERNATIVES

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters._
import scala.jdk.CollectionConverters._
import scala.util.Try
import io.onfhir.config.FhirCapabilityDefaults
import io.onfhir.config.FhirEndpointSettings
import io.onfhir.config.FhirRequestDefaults
import io.onfhir.config.FhirResultDefaults
import io.onfhir.config.FhirSubscriptionSettings
import io.onfhir.config.TerminologyServiceConf
import org.slf4j.{Logger, LoggerFactory}

/**
 * Repofyr application configuration.
 *
 * This object is the composition root for configuration: it loads the application `Config` once
 * and slices it into typed setting groups. Two families of groups exist.
 *
 *  - Library-owned groups from `io.onfhir.config` ([[fhirRequestDefaults]],
 *    [[fhirResultDefaults]], [[fhirCapabilityDefaults]], [[fhirSubscriptionSettings]]) are built
 *    with the `fromConfig` companions in `onfhir-common`, so any consumer of the reusable
 *    libraries can construct them the same way.
 *  - Server-owned groups from `io.repofyr.config` ([[serverSettings]], [[mongoDbSettings]],
 *    [[fhirInitializationSettings]], [[bulkSettings]]) describe server infrastructure and stay
 *    here, following the same shape.
 *
 * Every grouped setting is reached through its group - there are no flat per-key accessors for
 * them. What remains flat below is only what belongs to no section: values whose keys live in
 * foreign namespaces (`spray.can.*`, `akka.http.*`) and settings whose own shape is already a
 * group, such as [[authzConfig]] and [[fhirAuditingConfig]].
 */
object OnfhirConfig {

  private val logger: Logger = LoggerFactory.getLogger(getClass)

  /** Application config object. */
  val config: com.typesafe.config.Config = ConfigFactory.load()

  private def subtree(path: String): Config =
    Try(config.getConfig(path)).getOrElse(ConfigFactory.empty())

  private lazy val serverConfig: Config = subtree("server")
  private lazy val fhirConfig: Config = subtree("fhir")
  private lazy val mongoConfig: Config = subtree("mongodb")

  /**
   * The `fhir.default` subtree, with the deprecated top-level `fhir.search-handling` folded in
   * when the new `fhir.default.search-handling` is absent.
   *
   * `search-handling` is the default value of the `Prefer: handling=` header, which makes it the
   * sibling of `return-preference` rather than a top-level FHIR setting; it moved into
   * `fhir.default` in 4.0.0. The fallback keeps pre-4.0.0 configuration working: without it, a
   * deployment configured for lenient handling would silently revert to strict and start
   * rejecting requests carrying unknown search parameters.
   */
  private[config] def fhirDefaultsWithLegacyFallback(root: Config): Config = {
    val defaults = Try(root.getConfig("fhir.default")).getOrElse(ConfigFactory.empty())
    if (!defaults.hasPath("search-handling") && root.hasPath("fhir.search-handling")) {
      logger.warn(
        "Configuration key 'fhir.search-handling' is deprecated and will be removed in a future " +
          "major release. Move it to 'fhir.default.search-handling'.")
      defaults.withValue("search-handling", root.getValue("fhir.search-handling"))
    } else defaults
  }

  private lazy val fhirDefaultsConfig: Config = fhirDefaultsWithLegacyFallback(config)

  /* ---------------------------------------------------------------------------------------- */
  /* Typed setting groups                                                                      */
  /* ---------------------------------------------------------------------------------------- */

  /** Where the FHIR endpoint is served, and how. */
  lazy val serverSettings: ServerSettings = ServerSettings.fromConfig(serverConfig)

  /** MongoDB connection and persistence behavior. */
  lazy val mongoDbSettings: MongoDbSettings = MongoDbSettings.fromConfig(mongoConfig)

  /** Which FHIR infrastructure definitions to load on startup, and from where. */
  lazy val fhirInitializationSettings: FhirInitializationSettings =
    FhirInitializationSettings.fromConfig(fhirConfig)

  /** Settings for the bulk import operation. */
  lazy val bulkSettings: BulkSettings = BulkSettings.fromConfig(fhirConfig)

  /** Library-owned: the FHIR service root URL. */
  lazy val fhirEndpointSettings: FhirEndpointSettings = FhirEndpointSettings(fhirRootUrl)

  /** Library-owned: defaults applied to a request when the client sends no `Prefer` header. */
  lazy val fhirRequestDefaults: FhirRequestDefaults = FhirRequestDefaults.fromConfig(fhirDefaultsConfig)

  /** Library-owned: defaults governing search result paging and totals. */
  lazy val fhirResultDefaults: FhirResultDefaults = FhirResultDefaults.fromConfig(fhirDefaultsConfig)

  /** Library-owned: CapabilityStatement defaults for interactions not stated by a profile. */
  lazy val fhirCapabilityDefaults: FhirCapabilityDefaults =
    FhirCapabilityDefaults.fromConfig(fhirDefaultsConfig)

  /** Library-owned: FHIR Subscription handling. */
  lazy val fhirSubscriptionSettings: FhirSubscriptionSettings =
    FhirSubscriptionSettings.fromConfig(subtree("fhir.subscription"))

  /* ---------------------------------------------------------------------------------------- */
  /* Settings with no group                                                                    */
  /*                                                                                           */
  /* These belong to no section: their keys live in foreign namespaces Repofyr does not own,   */
  /* so grouping them would force a companion to read absolute paths.                          */
  /* ---------------------------------------------------------------------------------------- */

  /** Name of the server, from the legacy Spray key `spray.can.server.server-header`. */
  lazy val serverName: String =
    Try(config.getString("spray.can.server.server-header")).getOrElse("onFHIR Repository")

  /** Request timeout, from the Akka HTTP key `akka.http.server.request-timeout`. */
  lazy val fhirRequestTimeout: Duration =
    Try(config.getDuration("akka.http.server.request-timeout")).toOption.getOrElse(Duration.ofSeconds(30))

  /* ---------------------------------------------------------------------------------------- */
  /* Settings whose shape is their own group                                                   */
  /* ---------------------------------------------------------------------------------------- */

  /** Allowed mime-types for FHIR Binary resources */
  lazy val fhirBinaryAllowedMimeTypes: Seq[String] =
    Try(config.getStringList("fhir.binary.allowed-mime-types").asScala.toSeq).toOption.getOrElse(Nil)

  /**
   * The FHIR service root URL, defaulting to the server's own location and base URI.
   *
   * Private because [[fhirEndpointSettings]] is the public form; this exists only to compute it.
   */
  private lazy val fhirRootUrl: String =
    Try(config.getString("fhir.root-url")).toOption
      .getOrElse(s"${serverSettings.location}/${serverSettings.baseUri}")

  lazy val fhirValidation: String =
    Try(config.getString("fhir.validation")).toOption.getOrElse(FHIR_VALIDATION_ALTERNATIVES.PROFILE)

  /** Auditing related configurations */
  lazy val fhirAuditingConfig: Option[AuditConfig] =
    Try(config.getConfig("fhir.auditing")).toOption.map(c => new AuditConfig(c))

  /** Authorization configurations */
  lazy val authzConfig: AuthzConfig = new AuthzConfig(OnfhirConfig.config.getConfig("fhir.authorization"))

  /** Whether to log failed requests and issues related with them */
  lazy val logFailedRequests: Boolean =
    Try(config.getBoolean("fhir.failed-request-logging")).toOption.getOrElse(false)

  /** Configurations for integrated terminology services */
  lazy val integratedTerminologyServices: Option[Seq[(TerminologyServiceConf, Config)]] =
    Try(config.getObject("fhir.integrated-terminology-services").asScala)
      .toOption
      .map(cnf =>
        cnf
          .map(entry => {
            val sname = entry._1

            val timeout =
              Try(config.getDuration(s"fhir.integrated-terminology-services.$sname.timeout"))
                .toOption.map(_.toScala)
                .getOrElse(FiniteDuration.apply(1, TimeUnit.SECONDS))
            val supportedValueSets =
              config.getStringList(s"fhir.integrated-terminology-services.$sname.value-sets")
                .asScala
                .map(vs => FHIRUtil.parseCanonicalValue(vs))
                .groupBy(_._1)
                .map(g => g._1 -> (g._2.flatMap(_._2).toSeq match {
                  case Nil => None
                  case oth => Some(oth.toSet)
                }))

            TerminologyServiceConf(
              sname,
              timeout,
              supportedValueSets
            ) ->
              config.getConfig(s"fhir.integrated-terminology-services.$sname")
          })
          .toSeq
      )
}
