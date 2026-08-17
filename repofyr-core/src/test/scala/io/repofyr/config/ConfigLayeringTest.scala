package io.repofyr.config

import com.typesafe.config.ConfigFactory
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

/**
 * Pins how Repofyr's shipped defaults are layered.
 *
 * Before 4.0.0 those defaults lived in `application.conf`, so a deployment pointing
 * `-Dconfig.file` at its own file replaced them wholesale and had to restate all of them. They now
 * ship as `repofyr-reference.conf` and [[OnfhirConfig.config]] inserts them between the
 * deployment's file and the library `reference.conf` files.
 *
 * Two things are easy to break silently here and neither shows up as a compile error:
 *
 *  - the resource going missing or being renamed, which turns every default into whatever the
 *    `Standard` case-class values happen to be
 *  - the `akka.*` overrides losing to Akka's own `reference.conf`, which is exactly what would
 *    happen if the defaults were moved into a plain `reference.conf`
 */
@RunWith(classOf[JUnitRunner])
class ConfigLayeringTest extends Specification {

  "The shipped defaults resource" should {

    "be on the classpath and carry the server defaults" in {
      val defaults = ConfigFactory.parseResources("repofyr-reference.conf").resolve()
      defaults.isEmpty must beFalse
      defaults.getString("mongodb.db") mustEqual "onfhir"
      defaults.getString("mongodb.host") mustEqual "localhost:27017"
      defaults.getBoolean("mongodb.embedded") must beFalse
    }

    "agree with the code-level fallback for the database name" in {
      // These two disagreed until 4.0.0 - the file said 'onfhir' and the case class said 'fhir' -
      // so a configuration that omitted mongodb.db silently addressed a different database.
      val defaults = ConfigFactory.parseResources("repofyr-reference.conf").resolve()
      defaults.getString("mongodb.db") mustEqual MongoDbSettings.Standard.dbName
    }
  }

  "A deployment configuration" should {

    "override only the keys it sets and inherit the rest" in {
      val userFile = ConfigFactory.parseString("""mongodb { db = "my-fhir-db" }""")
      val merged = userFile.withFallback(ConfigFactory.parseResources("repofyr-reference.conf")).resolve()

      val settings = MongoDbSettings.fromConfig(merged.getConfig("mongodb"))
      settings.dbName mustEqual "my-fhir-db"
      settings.hosts mustEqual Seq("localhost:27017")
      settings.writeConcern mustEqual "1"
      settings.useTransaction must beFalse
    }

    "leave MongoDB credentials unset when the environment supplies none" in {
      val settings = MongoDbSettings.fromConfig(
        ConfigFactory.parseResources("repofyr-reference.conf").resolve().getConfig("mongodb"))

      settings.username must beNone
      settings.password must beNone
      settings.authDbName must beNone
    }

    "take MongoDB credentials from the environment" in {
      // The defaults declare username/password/authdb as optional substitutions, which resolve
      // against the root config and then the process environment. Supplying them at the root here
      // exercises the same path DB_USERNAME and DB_PASSWORD take in a container, without the test
      // having to mutate its own environment.
      val resolved = ConfigFactory
        .parseString("""
            |DB_USERNAME = alice
            |DB_PASSWORD = s3cret
            |""".stripMargin)
        .withFallback(ConfigFactory.parseResources("repofyr-reference.conf"))
        .resolve()

      val settings = MongoDbSettings.fromConfig(resolved.getConfig("mongodb"))
      settings.username must beSome("alice")
      settings.password must beSome("s3cret")
      // Unset, so MongoCredentialSupport falls back to admin rather than skipping authentication.
      settings.authDbName must beNone
    }

    "serve plain HTTP when no keystore is configured" in {
      // Worth pinning in its own right: a regression here would have the server quietly serving
      // TLS off the sample keystore that SSLConfig falls back to.
      val settings = ServerSettings.fromConfig(
        ConfigFactory.parseResources("repofyr-reference.conf").resolve().getConfig("server"))

      settings.ssl.enabled must beFalse
      settings.ssl.keystorePath must beNone
      settings.ssl.keystorePassword must beNone
    }

    "enable TLS when the environment supplies a keystore" in {
      // There is no separate on/off switch - naming a keystore is what enables TLS - so this pins
      // the contract SSL_KEYSTORE relies on.
      val resolved = ConfigFactory
        .parseString("""
            |SSL_KEYSTORE = "/etc/repofyr/keystore.jks"
            |SSL_KEYSTORE_PASSWORD = changeit
            |""".stripMargin)
        .withFallback(ConfigFactory.parseResources("repofyr-reference.conf"))
        .resolve()

      val settings = ServerSettings.fromConfig(resolved.getConfig("server"))
      settings.ssl.enabled must beTrue
      settings.ssl.keystorePath must beSome("/etc/repofyr/keystore.jks")
      settings.ssl.keystorePassword must beSome("changeit")
    }
  }

  "The assembled application config" should {

    // Repofyr's akka settings are peers of Akka's own reference.conf the moment they are put in a
    // file named reference.conf, and the tie is then broken by classpath order - which resolves
    // one way on a plain classpath and the other way in a shaded jar. These assertions fail if
    // that layering is ever flattened.
    "rank Repofyr's akka overrides above the library reference" in {
      OnfhirConfig.config.getString("akka.loglevel") mustEqual "OFF"
      OnfhirConfig.config.getString("akka.http.server.server-header") mustEqual "OnFhir.io FHIR Repository"
      OnfhirConfig.config.getString("akka.http.server.parsing.uri-parsing-mode") mustEqual "relaxed"
      OnfhirConfig.config.getDuration("akka.http.server.request-timeout").toSeconds mustEqual 60L
    }

    "keep remote-address-header on, which audit records depend on for the client IP" in {
      OnfhirConfig.config.getString("akka.http.server.remote-address-header") mustEqual "on"
    }

    "expose the server defaults through the same chain" in {
      OnfhirConfig.mongoDbSettings.dbName mustEqual "onfhir"
      OnfhirConfig.serverSettings.port mustEqual 8080
    }

    "hold the server name at its stored value" in {
      // Not a tautology: serverName reads spray.can.server.server-header, a key nothing sets, so it
      // is this constant in practice. Repointing it at akka.http.server.server-header - which the
      // defaults do set, to a different string - looks like an obvious tidy-up and would change
      // AuditEvent.agent.name on every newly written audit record, and the data directory
      // repofyr-dev-server derives from it. Both are stored-data conventions, so the value is
      // pinned here rather than left to a reviewer to notice.
      OnfhirConfig.serverName mustEqual "onFHIR Repository"
      OnfhirConfig.config.getString("akka.http.server.server-header") mustEqual "OnFhir.io FHIR Repository"
    }
  }
}
