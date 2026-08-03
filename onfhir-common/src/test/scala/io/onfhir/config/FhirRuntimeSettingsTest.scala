package io.onfhir.config

import io.onfhir.exception.InitializationException
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class FhirRuntimeSettingsTest extends Specification {

  "FHIR runtime settings" should {
    "parse every supported legacy code without changing its wire value" in {
      Seq(
        FhirSearchHandling.fromCode("handling=strict").code,
        FhirSearchHandling.fromCode("handling=lenient").code,
        FhirReturnPreference.fromCode("return=minimal").code,
        FhirReturnPreference.fromCode("return=representation").code,
        FhirReturnPreference.fromCode("return=OperationOutcome").code,
        FhirPaginationMode.fromCode("page").code,
        FhirPaginationMode.fromCode("offset").code,
        FhirSearchTotalHandling.fromCode("none").code,
        FhirSearchTotalHandling.fromCode("estimate").code,
        FhirSearchTotalHandling.fromCode("accurate").code,
        FhirVersioningPolicy.fromCode("no-version").code,
        FhirVersioningPolicy.fromCode("versioned").code,
        FhirVersioningPolicy.fromCode("versioned-update").code,
        FhirConditionalReadSupport.fromCode("not-supported").code,
        FhirConditionalReadSupport.fromCode("modified-since").code,
        FhirConditionalReadSupport.fromCode("not-match").code,
        FhirConditionalReadSupport.fromCode("full-support").code,
        FhirConditionalDeleteSupport.fromCode("not-supported").code,
        FhirConditionalDeleteSupport.fromCode("single").code,
        FhirConditionalDeleteSupport.fromCode("multiple").code
      ) mustEqual Seq(
        "handling=strict", "handling=lenient",
        "return=minimal", "return=representation", "return=OperationOutcome",
        "page", "offset", "none", "estimate", "accurate",
        "no-version", "versioned", "versioned-update",
        "not-supported", "modified-since", "not-match", "full-support",
        "not-supported", "single", "multiple")
    }

    "fail fast for an unsupported closed-set value" in {
      FhirPaginationMode.fromCode("cursor") must throwA[InitializationException]
    }

    "reject an empty endpoint without normalizing a valid endpoint" in {
      FhirEndpointSettings(" https://example.test/fhir/ ").rootUrl mustEqual " https://example.test/fhir/ "
      FhirEndpointSettings("   ") must throwA[InitializationException]
    }

    "reject a negative default page size" in {
      FhirResultDefaults(-1, FhirPaginationMode.Page, FhirSearchTotalHandling.Accurate) must
        throwA[InitializationException]
    }

    "retain the historical capability defaults" in {
      FhirCapabilityDefaults.Standard mustEqual FhirCapabilityDefaults(
        FhirVersioningPolicy.Versioned,
        readHistory = false,
        updateCreate = false,
        conditionalCreate = false,
        FhirConditionalReadSupport.FullSupport,
        conditionalUpdate = false,
        FhirConditionalDeleteSupport.NotSupported)
    }
  }
}
