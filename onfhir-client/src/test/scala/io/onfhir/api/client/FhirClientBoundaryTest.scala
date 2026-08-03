package io.onfhir.api.client

import org.json4s.JObject
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class FhirClientBoundaryTest extends Specification {
  "FhirClientUtil" should {
    "use client-specific failures for missing resource identity fields" in {
      FhirClientUtil.getResourceType(JObject()) must throwA[FhirClientException]
      FhirClientUtil.getResourceId(JObject()) must throwA[FhirClientException]
    }
  }
}
