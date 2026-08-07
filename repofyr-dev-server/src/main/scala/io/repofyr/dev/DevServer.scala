package io.repofyr.dev

import io.repofyr.Onfhir
import io.repofyr.config.OnfhirConfig
import io.repofyr.embedded.EmbeddedMongo
import io.repofyr.r4.config.FhirR4Configurator
import io.repofyr.r5.config.FhirR5Configurator
import io.repofyr.stu3.config.FhirSTU3Configurator
import org.slf4j.LoggerFactory

/**
 * Runnable development server.
 *
 * Starts an embedded MongoDB, boots Repofyr for the requested FHIR release against it, and stops
 * the database again on shutdown. It exists so that no runnable production artifact has to carry
 * a component that downloads and executes a `mongod` binary - see repofyr-embedded-mongo.
 *
 * Usage, where the release defaults to R5:
 *
 * {{{
 *   mvn -pl repofyr-dev-server -am exec:java -Dexec.args=r4
 *   java -jar repofyr-dev-server/target/repofyr-dev-server-standalone.jar stu3
 * }}}
 *
 * The database listens at `mongodb.host` from the application configuration and keeps its files
 * in a directory next to the working directory, so data survives a restart. `mongodb.embedded` is
 * not consulted: starting the database is this launcher's entire purpose. For anything other than
 * development, run a `repofyr-server-*` artifact against a real MongoDB.
 */
object DevServer extends App {

  private val logger = LoggerFactory.getLogger(getClass)

  private val releases = Map(
    "r4" -> (() => new FhirR4Configurator()),
    "r5" -> (() => new FhirR5Configurator()),
    "stu3" -> (() => new FhirSTU3Configurator()))

  private val release = args.headOption.map(_.toLowerCase).getOrElse("r5")

  private val configurator =
    releases.getOrElse(
      release,
      throw new IllegalArgumentException(
        s"Unknown FHIR release '$release'. Use one of: ${releases.keys.toSeq.sorted.mkString(", ")}."))

  private val mongoAddress = OnfhirConfig.mongoDbSettings.hosts.head.split(':')
  require(mongoAddress.length == 2, s"mongodb.host must be host:port, was '${mongoAddress.mkString(":")}'")

  logger.info("Starting Repofyr dev server for FHIR {}", release.toUpperCase)
  EmbeddedMongo.start(
    OnfhirConfig.serverName,
    mongoAddress(0),
    mongoAddress(1).toInt,
    withTemporaryDatabaseDir = false)

  Onfhir
    .apply(configurator(), onShutdown = Seq(() => EmbeddedMongo.stop()))
    .start
}
