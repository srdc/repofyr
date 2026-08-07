package io.repofyr.stu3

import io.onfhir.config.{FSConfigReader, FhirServerConfig}
import io.repofyr.stu3.config.FhirSTU3Configurator
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

/**
 * Initialization smoke test for the STU3 server.
 *
 * Every path is left at its default, so this exercises the classpath lookup of
 * the three release-suffixed resources that STU3 startup depends on:
 * `definitions-stu3.json.zip` and `conformance-statement-stu3.json`, both now
 * supplied by `onfhir-definitions-stu3`, and the module's own
 * `db-index-conf-stu3.json`. Before those names were aligned with the
 * suffixing convention the configurator threw here, so this suite is the
 * regression net for that fix as well as for the parser move to `onfhir-stu3`.
 */
@RunWith(classOf[JUnitRunner])
class FhirSTU3ConfiguratorTest extends Specification {
  sequential

  val fhirConfigurator = new FhirSTU3Configurator
  val configReader = new FSConfigReader(fhirVersion = "STU3")
  val fhirServerConfig: FhirServerConfig =
    fhirConfigurator.initializeServerPlatform(configReader, Set.empty[String])

  "FhirSTU3Configurator" should {
    "initialize the server platform from the default classpath locations" in {
      // The configurator's release label drives resource-name resolution, while
      // the parsed config carries the FHIR version from the capability statement.
      fhirConfigurator.fhirVersion mustEqual "STU3"
      fhirServerConfig.fhirVersion mustEqual "3.0.1"
    }

    "read the STU3 standard definitions bundle" in {
      fhirServerConfig.FHIR_RESOURCE_TYPES must contain("Patient")
      fhirServerConfig.FHIR_COMPLEX_TYPES must contain("CodeableConcept")
      fhirServerConfig.FHIR_PRIMITIVE_TYPES must contain("string")
      fhirServerConfig.profileRestrictions must not(beEmpty)
    }

    "read the STU3 base capability statement" in {
      fhirServerConfig.resourceConfigurations must not(beEmpty)
      fhirServerConfig.resourceConfigurations.keySet must contain("Patient")
    }

    "parse the STU3 database index configuration" in {
      fhirConfigurator.indexConfigurations must not(beEmpty)
    }

    // The configurator declares the STU3-era code system for the SUBSETTED tag, but until 4.0.0
    // initializeServerPlatform never copied it onto FhirServerConfig - it copied the sibling
    // FHIR_* fields and skipped this one. FHIRServerUtil reads it from FhirServerConfig, so the
    // override was inert and summarized STU3 responses carried the R4-era terminology.hl7.org
    // system. Asserting the propagated value, not the configurator's own field, is what makes
    // this a regression test rather than a restatement of the override.
    "propagate the STU3 summarization code system onto the server config" in {
      fhirConfigurator.FHIR_SUMMARIZATION_INDICATOR_CODE_SYSTEM mustEqual
        "http://hl7.org/fhir/v3/ObservationValue"
      fhirServerConfig.FHIR_SUMMARIZATION_INDICATOR_CODE_SYSTEM mustEqual
        "http://hl7.org/fhir/v3/ObservationValue"
    }
  }
}
