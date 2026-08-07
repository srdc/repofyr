package io.repofyr.config

import com.typesafe.config.Config
import io.repofyr.config.ConfigReader._

import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

/**
 * Lenient readers for optional configuration values.
 *
 * These use `Try(...).toOption` rather than `Config.hasPath`, deliberately preserving the
 * behavior of the flat readers they replaced: a key that is absent, null, or of the wrong type
 * yields the default rather than failing server startup.
 */
private[config] object ConfigReader {
  def optString(config: Config, path: String): Option[String] = Try(config.getString(path)).toOption
  def optInt(config: Config, path: String): Option[Int] = Try(config.getInt(path)).toOption
  def optLong(config: Config, path: String): Option[Long] = Try(config.getLong(path)).toOption
  def optBoolean(config: Config, path: String): Option[Boolean] = Try(config.getBoolean(path)).toOption
  def optStringSeq(config: Config, path: String): Option[Seq[String]] =
    Try(config.getStringList(path).asScala.toSeq).toOption
}

/**
 * TLS settings for the FHIR endpoint.
 *
 * @param keystorePath path to the Java keystore, absent when TLS is not configured
 * @param keystorePassword password for that keystore
 * @param enabled whether the server serves over HTTPS
 */
final case class SslSettings(
    keystorePath: Option[String],
    keystorePassword: Option[String],
    enabled: Boolean)

object SslSettings {
  val Standard: SslSettings = SslSettings(None, None, enabled = false)

  /**
   * Build the TLS settings from the `server` subtree, reading the relative `ssl` block.
   *
   * @param config the already-scoped `server` subtree
   */
  def fromConfig(config: Config): SslSettings = SslSettings(
    keystorePath = optString(config, "ssl.keystore"),
    keystorePassword = optString(config, "ssl.password"),
    // Preserves the original predicate exactly: an ssl block that exists but is empty counts as
    // enabled, and a null keystore makes getString throw, which falls back to disabled.
    enabled = Try(config.getConfig("ssl").isEmpty || config.getString("ssl.keystore") != null).getOrElse(false))
}

/**
 * The internal (non-FHIR) administrative API, required by the subscription module.
 *
 * @param active whether the internal API listener is started
 * @param port port the internal API listens on
 * @param authenticate whether callers of the internal API must authenticate
 */
final case class InternalApiSettings(
    active: Boolean,
    port: Int,
    authenticate: Boolean)

object InternalApiSettings {
  val Standard: InternalApiSettings = InternalApiSettings(active = false, port = 8081, authenticate = false)

  /**
   * Build the internal API settings from the `server` subtree, reading the relative
   * `internal` block.
   *
   * @param config the already-scoped `server` subtree
   */
  def fromConfig(config: Config): InternalApiSettings = InternalApiSettings(
    active = optBoolean(config, "internal.active").getOrElse(Standard.active),
    port = optInt(config, "internal.port").getOrElse(Standard.port),
    authenticate = optBoolean(config, "internal.authenticate").getOrElse(Standard.authenticate))
}

/**
 * Where and how the FHIR endpoint is served.
 *
 * @param host interface the server binds to
 * @param port port the server binds to
 * @param baseUri path segment the FHIR API is served under, e.g. `fhir`
 * @param ssl TLS settings
 * @param internalApi internal administrative API settings
 */
final case class ServerSettings(
    host: String,
    port: Int,
    baseUri: String,
    ssl: SslSettings,
    internalApi: InternalApiSettings) {

  /** `https` when TLS is configured, otherwise `http`. */
  def protocol: String = if (ssl.enabled) "https" else "http"

  /** Scheme, host and port, without the FHIR base URI. */
  def location: String = s"$protocol://$host:$port"
}

object ServerSettings {
  val Standard: ServerSettings = ServerSettings(
    host = "localhost",
    port = 8080,
    baseUri = "fhir",
    ssl = SslSettings.Standard,
    internalApi = InternalApiSettings.Standard)

  /**
   * Build the server settings from the `server` subtree.
   *
   * Reads the relative keys `host`, `port` and `base-uri`, plus the `ssl` and `internal` blocks.
   * All are optional and fall back to [[Standard]].
   *
   * @param config the already-scoped `server` subtree
   */
  def fromConfig(config: Config): ServerSettings = ServerSettings(
    host = optString(config, "host").getOrElse(Standard.host),
    port = optInt(config, "port").getOrElse(Standard.port),
    baseUri = optString(config, "base-uri").getOrElse(Standard.baseUri),
    ssl = SslSettings.fromConfig(config),
    internalApi = InternalApiSettings.fromConfig(config))
}

/**
 * MongoDB connection pool tuning. Every value is optional; absent means the driver default.
 */
final case class MongoDbPoolingSettings(
    minSize: Option[Int],
    maxSize: Option[Int],
    maxWaitTime: Option[Long],
    maxConnectionLifeTime: Option[Long])

object MongoDbPoolingSettings {
  val Standard: MongoDbPoolingSettings = MongoDbPoolingSettings(None, None, None, None)

  /**
   * Build the pooling settings from the `mongodb` subtree, reading the relative `pooling` block.
   *
   * Returns `None` when no `pooling` block is configured, which is distinct from a block that is
   * present but empty: callers leave the driver's own pool defaults alone in the first case and
   * apply their own in the second.
   *
   * @param config the already-scoped `mongodb` subtree
   */
  def fromConfig(config: Config): Option[MongoDbPoolingSettings] =
    Try(config.getConfig("pooling")).toOption.map(pooling =>
      MongoDbPoolingSettings(
        minSize = optInt(pooling, "minSize"),
        maxSize = optInt(pooling, "maxSize"),
        maxWaitTime = optLong(pooling, "maxWaitTime"),
        maxConnectionLifeTime = optLong(pooling, "maxConnectionLifeTime")))
}

/**
 * Persistence settings for the MongoDB backing store.
 *
 * @param embedded whether to start an embedded MongoDB instance, intended for development
 * @param hosts one or more `host:port` entries
 * @param dbName database name holding the FHIR resources
 * @param authDbName database to authenticate against, when it differs from `dbName`
 * @param username credential user name
 * @param password credential password
 * @param pooling connection pool tuning, absent when no pooling block is configured
 * @param shardingEnabled whether Repofyr initiates sharding on its collections
 * @param useTransaction whether to use MongoDB transactions, requiring a replica set or cluster
 * @param writeConcern write concern applied to writes, e.g. `1` or `majority`
 */
final case class MongoDbSettings(
    embedded: Boolean,
    hosts: Seq[String],
    dbName: String,
    authDbName: Option[String],
    username: Option[String],
    password: Option[String],
    pooling: Option[MongoDbPoolingSettings],
    shardingEnabled: Boolean,
    useTransaction: Boolean,
    writeConcern: String)

object MongoDbSettings {
  val Standard: MongoDbSettings = MongoDbSettings(
    embedded = false,
    hosts = Seq("localhost"),
    dbName = "onfhir",
    authDbName = None,
    username = None,
    password = None,
    pooling = None,
    shardingEnabled = false,
    useTransaction = false,
    writeConcern = "1")

  /**
   * Build the persistence settings from the `mongodb` subtree.
   *
   * `host` accepts either a list or a single comma-separated string; both are normalized to a
   * sequence.
   *
   * @param config the already-scoped `mongodb` subtree
   */
  def fromConfig(config: Config): MongoDbSettings = MongoDbSettings(
    embedded = optBoolean(config, "embedded").getOrElse(Standard.embedded),
    hosts = readHosts(config),
    dbName = optString(config, "db").getOrElse(Standard.dbName),
    authDbName = optString(config, "authdb"),
    username = optString(config, "username"),
    password = optString(config, "password"),
    pooling = MongoDbPoolingSettings.fromConfig(config),
    shardingEnabled = optBoolean(config, "sharding").getOrElse(Standard.shardingEnabled),
    useTransaction = optBoolean(config, "transaction").getOrElse(Standard.useTransaction),
    writeConcern = optString(config, "write-concern").getOrElse(Standard.writeConcern))

  private def readHosts(config: Config): Seq[String] =
    Try(config.getStringList("host").asScala) match {
      case Success(list) => list.toSeq
      case Failure(_) => Try(config.getString("host").split(',').toSeq).getOrElse(Standard.hosts)
    }
}

/**
 * Startup settings: which FHIR infrastructure definitions to load, and from where.
 *
 * Every path is optional; an absent path means the packaged classpath default for the server's
 * FHIR release is used.
 *
 * @param initialize whether to run configuration initialization on startup
 * @param persistedBaseDefinitions foundation resource types persisted from the base standard
 * @param baseDefinitionsPath ZIP of the FHIR base definitions used for validation
 * @param conformancePath the server's CapabilityStatement
 * @param profilesPath directory of StructureDefinitions
 * @param parametersPath directory of SearchParameter definitions
 * @param valueSetsPath directory of ValueSets
 * @param codeSystemsPath directory of CodeSystems
 * @param compartmentsPath directory of CompartmentDefinitions
 * @param operationsPath directory of OperationDefinitions
 * @param indexConfPath database index and shard key configuration
 */
final case class FhirInitializationSettings(
    initialize: Boolean,
    persistedBaseDefinitions: Set[String],
    baseDefinitionsPath: Option[String],
    conformancePath: Option[String],
    profilesPath: Option[String],
    parametersPath: Option[String],
    valueSetsPath: Option[String],
    codeSystemsPath: Option[String],
    compartmentsPath: Option[String],
    operationsPath: Option[String],
    indexConfPath: Option[String])

object FhirInitializationSettings {
  val Standard: FhirInitializationSettings = FhirInitializationSettings(
    initialize = false,
    persistedBaseDefinitions = Set.empty,
    baseDefinitionsPath = None,
    conformancePath = None,
    profilesPath = None,
    parametersPath = None,
    valueSetsPath = None,
    codeSystemsPath = None,
    compartmentsPath = None,
    operationsPath = None,
    indexConfPath = None)

  /**
   * Build the startup settings from the `fhir` subtree.
   *
   * Reads the relative keys `initialize` and `persisted-base-definitions`, plus the paths under
   * the relative `initialization` block. All are optional and fall back to [[Standard]].
   *
   * @param config the already-scoped `fhir` subtree
   */
  def fromConfig(config: Config): FhirInitializationSettings = FhirInitializationSettings(
    initialize = optBoolean(config, "initialize").getOrElse(Standard.initialize),
    persistedBaseDefinitions =
      optStringSeq(config, "persisted-base-definitions").map(_.toSet).getOrElse(Standard.persistedBaseDefinitions),
    baseDefinitionsPath = optString(config, "initialization.base-definitions-path"),
    conformancePath = optString(config, "initialization.conformance-path"),
    profilesPath = optString(config, "initialization.profiles-path"),
    parametersPath = optString(config, "initialization.parameters-path"),
    valueSetsPath = optString(config, "initialization.valuesets-path"),
    codeSystemsPath = optString(config, "initialization.codesystems-path"),
    compartmentsPath = optString(config, "initialization.compartments-path"),
    operationsPath = optString(config, "initialization.operations-path"),
    indexConfPath = optString(config, "initialization.index-conf-path"))
}

/**
 * Settings for the bulk import operation.
 *
 * @param numResourcesPerGroup how many resources are written per grouped request
 * @param upsertMode when true, use a MongoDB upsert instead of a FHIR batch. Faster, but NOT
 *                   version aware: it replaces the current version of a resource
 */
final case class BulkSettings(
    numResourcesPerGroup: Int,
    upsertMode: Boolean)

object BulkSettings {
  val Standard: BulkSettings = BulkSettings(numResourcesPerGroup = 200, upsertMode = false)

  /**
   * Build the bulk settings from the `fhir` subtree, reading the relative `bulk` block.
   *
   * @param config the already-scoped `fhir` subtree
   */
  def fromConfig(config: Config): BulkSettings = BulkSettings(
    numResourcesPerGroup = optInt(config, "bulk.num-resources-per-group").getOrElse(Standard.numResourcesPerGroup),
    upsertMode = optBoolean(config, "bulk.upsert").getOrElse(Standard.upsertMode))
}
