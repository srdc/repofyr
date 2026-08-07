package io.repofyr.config

import com.typesafe.config.ConfigFactory
import io.onfhir.config._
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

/**
 * Characterization tests for configuration-driven settings construction.
 *
 * These pin two things that are easy to break silently:
 *
 *  - the library-owned `fromConfig` companions produce, from Repofyr's shipped key layout, the
 *    same values the hand-rolled readers produced before 4.0.0
 *  - the deprecated `fhir.search-handling` key still works after moving into `fhir.default`
 */
@RunWith(classOf[JUnitRunner])
class RuntimeSettingsTest extends Specification {

  "Library-owned settings from Repofyr's key layout" should {

    "build capability defaults from a fully populated fhir.default block" in {
      val config = ConfigFactory.parseString(
        """
          |versioning = "versioned"
          |read-history = true
          |update-create = true
          |conditional-create = true
          |conditional-read = "modified-since"
          |conditional-update = true
          |conditional-delete = "multiple"
        """.stripMargin)

      val defaults = FhirCapabilityDefaults.fromConfig(config)

      defaults.versioning mustEqual FhirVersioningPolicy.Versioned
      defaults.readHistory must beTrue
      defaults.updateCreate must beTrue
      defaults.conditionalCreate must beTrue
      defaults.conditionalRead mustEqual FhirConditionalReadSupport.ModifiedSince
      defaults.conditionalUpdate must beTrue
      defaults.conditionalDelete mustEqual FhirConditionalDeleteSupport.Multiple
    }

    "fall back to Standard for every absent key" in {
      // conditional-read is absent from Repofyr's shipped application.conf, so this is the
      // real-world case, not a synthetic one.
      FhirCapabilityDefaults.fromConfig(ConfigFactory.empty()) mustEqual FhirCapabilityDefaults.Standard
      FhirResultDefaults.fromConfig(ConfigFactory.empty()) mustEqual FhirResultDefaults.Standard
      FhirRequestDefaults.fromConfig(ConfigFactory.empty()) mustEqual FhirRequestDefaults.Standard
      FhirSubscriptionSettings.fromConfig(ConfigFactory.empty()) mustEqual FhirSubscriptionSettings.Standard
    }

    "override only the keys that are present" in {
      val defaults = FhirCapabilityDefaults.fromConfig(ConfigFactory.parseString("""update-create = true"""))

      defaults.updateCreate must beTrue
      defaults.versioning mustEqual FhirCapabilityDefaults.Standard.versioning
      defaults.conditionalRead mustEqual FhirCapabilityDefaults.Standard.conditionalRead
    }

    "build result defaults from the shipped page-count and pagination keys" in {
      val results = FhirResultDefaults.fromConfig(ConfigFactory.parseString(
        """
          |page-count = 20
          |pagination = "page"
          |search-total = "accurate"
        """.stripMargin))

      results.defaultPageSize mustEqual 20
      results.paginationMode mustEqual FhirPaginationMode.Page
      results.totalHandling mustEqual FhirSearchTotalHandling.Accurate
    }

    "treat an absent allowed-resources as no restriction rather than an empty set" in {
      FhirSubscriptionSettings.fromConfig(ConfigFactory.parseString("active = true")).allowedResources must beNone
      FhirSubscriptionSettings
        .fromConfig(ConfigFactory.parseString("""allowed-resources = ["Observation"]"""))
        .allowedResources mustEqual Some(Set("Observation"))
    }

    "reject an invalid enum value with a message naming the allowed values" in {
      FhirCapabilityDefaults.fromConfig(ConfigFactory.parseString("""versioning = "sometimes"""")) must
        throwAn[Exception].like { case e => e.getMessage must contain("versioned") }
    }
  }

  "Prefer-header defaults" should {

    "accept the bare token, which is canonical in configuration" in {
      val defaults = FhirRequestDefaults.fromConfig(ConfigFactory.parseString(
        """
          |search-handling = lenient
          |return-preference = minimal
        """.stripMargin))

      defaults.searchHandling mustEqual FhirSearchHandling.Lenient
      defaults.returnPreference mustEqual FhirReturnPreference.Minimal
    }

    "still accept the full header code, so pre-4.0.0 files keep working" in {
      val defaults = FhirRequestDefaults.fromConfig(ConfigFactory.parseString(
        """
          |search-handling = "handling=lenient"
          |return-preference = "return=minimal"
        """.stripMargin))

      defaults.searchHandling mustEqual FhirSearchHandling.Lenient
      defaults.returnPreference mustEqual FhirReturnPreference.Minimal
    }

    // FHIRApiValidator compares the configured value against FHIR_HTTP_OPTIONS.FHIR_SEARCH_STRICT,
    // which is the prefixed form. Exposing the bare configured string there would make that
    // comparison silently false and turn every request lenient.
    "expose the full header code regardless of how the value is written" in {
      FhirRequestDefaults
        .fromConfig(ConfigFactory.parseString("search-handling = strict"))
        .searchHandling.code mustEqual "handling=strict"
    }
  }

  "The deprecated fhir.search-handling key" should {

    "still be honoured when fhir.default.search-handling is absent" in {
      val root = ConfigFactory.parseString(
        """
          |fhir {
          |  search-handling = "handling=lenient"
          |  default { return-preference = representation }
          |}
        """.stripMargin)

      val defaults = FhirRequestDefaults.fromConfig(OnfhirConfig.fhirDefaultsWithLegacyFallback(root))
      defaults.searchHandling mustEqual FhirSearchHandling.Lenient
    }

    "lose to the new key when both are present" in {
      val root = ConfigFactory.parseString(
        """
          |fhir {
          |  search-handling = "handling=lenient"
          |  default { search-handling = strict }
          |}
        """.stripMargin)

      val defaults = FhirRequestDefaults.fromConfig(OnfhirConfig.fhirDefaultsWithLegacyFallback(root))
      defaults.searchHandling mustEqual FhirSearchHandling.Strict
    }

    "leave the defaults untouched when neither key is present" in {
      val root = ConfigFactory.parseString("""fhir { default { page-count = 10 } }""")
      val defaults = OnfhirConfig.fhirDefaultsWithLegacyFallback(root)

      defaults.hasPath("search-handling") must beFalse
      FhirRequestDefaults.fromConfig(defaults).searchHandling mustEqual FhirRequestDefaults.Standard.searchHandling
    }
  }

  "Server-owned settings" should {

    "build from the server subtree" in {
      val server = ServerSettings.fromConfig(ConfigFactory.parseString(
        """
          |host = "0.0.0.0"
          |port = 9999
          |base-uri = "fhir"
          |internal { active = true, port = 8082, authenticate = true }
        """.stripMargin))

      server.host mustEqual "0.0.0.0"
      server.port mustEqual 9999
      server.protocol mustEqual "http"
      server.location mustEqual "http://0.0.0.0:9999"
      server.internalApi.active must beTrue
      server.internalApi.port mustEqual 8082
      server.internalApi.authenticate must beTrue
    }

    "fall back to Standard for an empty subtree" in {
      ServerSettings.fromConfig(ConfigFactory.empty()) mustEqual ServerSettings.Standard
      MongoDbSettings.fromConfig(ConfigFactory.empty()) mustEqual MongoDbSettings.Standard
      BulkSettings.fromConfig(ConfigFactory.empty()) mustEqual BulkSettings.Standard
      FhirInitializationSettings.fromConfig(ConfigFactory.empty()) mustEqual FhirInitializationSettings.Standard
    }

    "accept mongodb.host as either a list or a comma separated string" in {
      MongoDbSettings
        .fromConfig(ConfigFactory.parseString("""host = ["a:27017", "b:27017"]"""))
        .hosts mustEqual Seq("a:27017", "b:27017")

      MongoDbSettings
        .fromConfig(ConfigFactory.parseString("""host = "a:27017,b:27017""""))
        .hosts mustEqual Seq("a:27017", "b:27017")
    }

    // An absent pooling block must stay absent rather than becoming an all-default one: the
    // MongoDB client applies its own pool defaults in that case, and only overrides them when a
    // block is actually configured.
    "read mongodb pooling only when the block is present" in {
      MongoDbSettings.fromConfig(ConfigFactory.empty()).pooling must beNone

      val pooled = MongoDbSettings
        .fromConfig(ConfigFactory.parseString("""pooling { minSize = 5, maxSize = 50 }"""))
        .pooling

      pooled must beSome
      pooled.get.minSize must beSome(5)
      pooled.get.maxSize must beSome(50)
      pooled.get.maxWaitTime must beNone
    }

    "read initialization paths and bulk settings from the fhir subtree" in {
      val fhir = ConfigFactory.parseString(
        """
          |initialize = true
          |persisted-base-definitions = ["SearchParameter"]
          |initialization { conformance-path = "conf/cs.json", index-conf-path = "conf/idx.json" }
          |bulk { num-resources-per-group = 500, upsert = true }
        """.stripMargin)

      val init = FhirInitializationSettings.fromConfig(fhir)
      init.initialize must beTrue
      init.persistedBaseDefinitions mustEqual Set("SearchParameter")
      init.conformancePath must beSome("conf/cs.json")
      init.indexConfPath must beSome("conf/idx.json")
      init.profilesPath must beNone

      val bulk = BulkSettings.fromConfig(fhir)
      bulk.numResourcesPerGroup mustEqual 500
      bulk.upsertMode must beTrue
    }

    "treat TLS as disabled when no keystore is configured" in {
      SslSettings.fromConfig(ConfigFactory.empty()).enabled must beFalse
      ServerSettings.fromConfig(ConfigFactory.empty()).protocol mustEqual "http"
    }
  }
}
