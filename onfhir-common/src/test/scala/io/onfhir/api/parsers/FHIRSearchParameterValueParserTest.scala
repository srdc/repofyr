package io.onfhir.api.parsers

import io.onfhir.api.{FHIR_HTTP_OPTIONS, FHIR_PARAMETER_CATEGORIES, FHIR_PARAMETER_TYPES, FHIR_PREFIXES_MODIFIERS}
import io.onfhir.config.{FhirSearchHandling, FhirServerConfig, SearchParameterConf}
import io.onfhir.exception.{InvalidParameterException, UnsupportedParameterException}
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class FHIRSearchParameterValueParserTest extends Specification {
  sequential

  private val fhirConfig = new FhirServerConfig("R4")
  fhirConfig.FHIR_RESULT_PARAMETERS = Nil
  fhirConfig.FHIR_SPECIAL_PARAMETERS = Nil
  fhirConfig.commonQueryParameters = Map.empty
  fhirConfig.resourceQueryParameters = Map(
    "Patient" -> Map(
      "name" -> searchParameter("name", FHIR_PARAMETER_TYPES.STRING),
      "birthdate" -> searchParameter("birthdate", FHIR_PARAMETER_TYPES.DATE),
      "general-practitioner" -> searchParameter(
        "general-practitioner",
        FHIR_PARAMETER_TYPES.REFERENCE,
        targets = Seq("Practitioner")
      )
    ),
    "Practitioner" -> Map(
      "name" -> searchParameter("name", FHIR_PARAMETER_TYPES.STRING)
    ),
    "Observation" -> Map(
      "subject" -> searchParameter(
        "subject",
        FHIR_PARAMETER_TYPES.REFERENCE,
        targets = Seq("Patient")
      ),
      "code" -> searchParameter("code", FHIR_PARAMETER_TYPES.TOKEN),
      "code-value" -> searchParameter(
        "code-value",
        FHIR_PARAMETER_TYPES.COMPOSITE,
        targets = Seq("code", "value-quantity")
      ),
      "value-quantity" -> searchParameter("value-quantity", FHIR_PARAMETER_TYPES.QUANTITY)
    )
  )

  private val parser = new FHIRSearchParameterValueParser(fhirConfig, FhirSearchHandling.Strict)

  "FHIRSearchParameterValueParser" should {
    "reject unsupported parameters when strict handling is requested" in {
      parser.parseSearchParameters(
        "Patient",
        Map("unknown" -> List("value")),
        Some(FHIR_HTTP_OPTIONS.FHIR_SEARCH_STRICT)
      ) must throwA[UnsupportedParameterException]
    }

    "ignore unsupported parameters when lenient handling is requested" in {
      parser.parseSearchParameters(
        "Patient",
        Map("unknown" -> List("value")),
        Some(FHIR_HTTP_OPTIONS.FHIR_SEARCH_LENIENT)
      ) must beEmpty
    }

    "use strict handling when the Prefer handling value is absent" in {
      parser.parseSearchParameters(
        "Patient",
        Map("unknown" -> List("value"))
      ) must throwA[UnsupportedParameterException]
    }

    "preserve supported string modifiers" in {
      val result = parser.parseSearchParameters(
        "Patient",
        Map("name:contains" -> List("Ann"))
      )

      result must haveSize(1)
      result.head.name mustEqual "name"
      result.head.suffix mustEqual FHIR_PREFIXES_MODIFIERS.CONTAINS
      result.head.valuePrefixList mustEqual Seq("" -> "Ann")
    }

    "preserve date prefixes" in {
      val result = parser.parseSearchParameters(
        "Patient",
        Map("birthdate" -> List("ge2020-01-01"))
      )

      result must haveSize(1)
      result.head.valuePrefixList mustEqual Seq(FHIR_PREFIXES_MODIFIERS.GREATER_THAN_EQUAL -> "2020-01-01")
    }

    "parse composite values without splitting their components" in {
      val result = parser.parseSearchParameters(
        "Observation",
        Map("code-value" -> List("http://loinc.org|1234-5$gt5|mg"))
      )

      result must haveSize(1)
      result.head.paramType mustEqual FHIR_PARAMETER_TYPES.COMPOSITE
      result.head.valuePrefixList mustEqual Seq("" -> "http://loinc.org|1234-5$gt5|mg")
    }

    "parse forward chained parameters" in {
      val result = parser.parseSearchParameters(
        "Patient",
        Map("general-practitioner.name" -> List("Smith"))
      )

      result must haveSize(1)
      result.head.paramCategory mustEqual FHIR_PARAMETER_CATEGORIES.CHAINED
      result.head.name mustEqual "name"
      result.head.chain mustEqual Seq("Practitioner" -> "general-practitioner")
      result.head.valuePrefixList mustEqual Seq("" -> "Smith")
    }

    "parse reverse chained parameters" in {
      val result = parser.parseSearchParameters(
        "Patient",
        Map("_has:Observation:subject:code" -> List("http://loinc.org|1234-5"))
      )

      result must haveSize(1)
      result.head.paramCategory mustEqual FHIR_PARAMETER_CATEGORIES.REVCHAINED
      result.head.name mustEqual "code"
      result.head.chain mustEqual Seq("Observation" -> "subject")
      result.head.valuePrefixList mustEqual Seq("" -> "http://loinc.org|1234-5")
    }

    "reject malformed values for supported parameters" in {
      parser.parseSearchParameters(
        "Patient",
        Map("birthdate" -> List("not-a-date"))
      ) must throwA[InvalidParameterException]
    }

    "preserve repeated values as separate parameters in their input order" in {
      val result = parser.parseSearchParameters(
        "Patient",
        Map("name" -> List("Alice", "Bob"))
      )

      result.map(_.name) mustEqual Seq("name", "name")
      result.map(_.valuePrefixList) mustEqual Seq(
        Seq("" -> "Alice"),
        Seq("" -> "Bob")
      )
    }
  }

  private def searchParameter(
    name: String,
    parameterType: String,
    targets: Seq[String] = Nil
  ): SearchParameterConf =
    SearchParameterConf(
      url = s"http://example.org/SearchParameter/$name",
      pname = name,
      ptype = parameterType,
      paths = Seq(name),
      targets = targets
    )
}
