package io.onfhir.api.util

import io.onfhir.api.{FHIR_DATA_TYPES, FHIR_PARAMETER_CATEGORIES, FHIR_PARAMETER_TYPES, FHIR_PREFIXES_MODIFIERS}
import io.onfhir.api.model.Parameter
import io.onfhir.config.{FhirEndpointSettings, SearchParameterConf}
import io.onfhir.util.JsonFormatter._
import org.json4s.JValue
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class InMemorySearchUtilCharacterizationTest extends Specification {
  private val endpointSettings = FhirEndpointSettings("http://localhost:8080/fhir")

  private val patient =
    """{
      |  "resourceType": "Patient",
      |  "name": [{"family": "Smith"}],
      |  "birthDate": "1980-01-15",
      |  "score": 10.02,
      |  "identifier": [{"system": "urn:system", "value": "12345"}],
      |  "managingOrganization": {"reference": "Organization/42"},
      |  "telecom": [
      |    {"system": "email", "value": "person@example.org"},
      |    {"system": "phone", "value": "+90-555-0100"}
      |  ]
      |}""".stripMargin.parseJson

  private def searchParameter(
      name: String,
      parameterType: String,
      path: String,
      targetType: String,
      targets: Seq[String] = Nil,
      restrictions: Seq[(String, String)] = Nil): SearchParameterConf =
    SearchParameterConf(
      url = s"http://example.org/SearchParameter/$name",
      pname = name,
      ptype = parameterType,
      paths = Seq(path),
      targets = targets,
      targetTypes = Seq(targetType),
      restrictions = Seq(restrictions)
    )

  private def matches(
      resource: JValue,
      config: SearchParameterConf,
      value: String,
      prefix: String = "",
      suffix: String = ""): Boolean = {
    val parameter = Parameter(
      FHIR_PARAMETER_CATEGORIES.NORMAL,
      config.ptype,
      config.pname,
      Seq(prefix -> value),
      suffix
    )
    val values = ImMemorySearchUtil.extractValuesAndTargetTypes(config, resource)
    ImMemorySearchUtil.handleSimpleParameter(parameter, config, values, endpointSettings)
  }

  "ImMemorySearchUtil" should {
    "apply the default, exact, and contains string semantics" in {
      val config = searchParameter("family", FHIR_PARAMETER_TYPES.STRING, "name.family", FHIR_DATA_TYPES.STRING)

      matches(patient, config, "smi") must beTrue
      matches(patient, config, "smith", suffix = FHIR_PREFIXES_MODIFIERS.EXACT) must beTrue
      matches(patient, config, "mit", suffix = FHIR_PREFIXES_MODIFIERS.CONTAINS) must beTrue
      matches(patient, config, "jones") must beFalse
    }

    "apply implicit precision and comparison prefixes to number searches" in {
      val config = searchParameter("score", FHIR_PARAMETER_TYPES.NUMBER, "score", FHIR_DATA_TYPES.DECIMAL)

      matches(patient, config, "10.0", prefix = FHIR_PREFIXES_MODIFIERS.EQUAL) must beTrue
      matches(patient, config, "10.1", prefix = FHIR_PREFIXES_MODIFIERS.GREATER_THAN) must beFalse
      matches(patient, config, "10.0", prefix = FHIR_PREFIXES_MODIFIERS.GREATER_THAN) must beTrue
    }

    "match a date value against an implicit year range" in {
      val config = searchParameter("birthdate", FHIR_PARAMETER_TYPES.DATE, "birthDate", FHIR_DATA_TYPES.DATE)

      matches(patient, config, "1980", prefix = FHIR_PREFIXES_MODIFIERS.EQUAL) must beTrue
      matches(patient, config, "1981", prefix = FHIR_PREFIXES_MODIFIERS.EQUAL) must beFalse
    }

    "match token system and value pairs" in {
      val config = searchParameter("identifier", FHIR_PARAMETER_TYPES.TOKEN, "identifier", FHIR_DATA_TYPES.IDENTIFIER)

      matches(patient, config, "urn:system|12345") must beTrue
      matches(patient, config, "urn:other|12345") must beFalse
    }

    "match relative and local absolute references equivalently" in {
      val config = searchParameter(
        "organization",
        FHIR_PARAMETER_TYPES.REFERENCE,
        "managingOrganization",
        FHIR_DATA_TYPES.REFERENCE,
        targets = Seq("Organization")
      )

      matches(patient, config, "Organization/42") must beTrue
      matches(patient, config, "http://localhost:8080/fhir/Organization/42") must beTrue
      matches(patient, config, "Organization/43") must beFalse
    }

    "evaluate the missing modifier through the public facade" in {
      val absent = searchParameter("deceased", FHIR_PARAMETER_TYPES.DATE, "deceasedDateTime", FHIR_DATA_TYPES.DATETIME)
      val present = searchParameter("birthdate", FHIR_PARAMETER_TYPES.DATE, "birthDate", FHIR_DATA_TYPES.DATE)

      matches(patient, absent, "true", suffix = FHIR_PREFIXES_MODIFIERS.MISSING) must beTrue
      matches(patient, present, "false", suffix = FHIR_PREFIXES_MODIFIERS.MISSING) must beTrue
    }

    "extract only values satisfying path restrictions" in {
      val config = searchParameter(
        "phone",
        FHIR_PARAMETER_TYPES.STRING,
        "telecom.value",
        FHIR_DATA_TYPES.STRING,
        restrictions = Seq("@.system" -> "phone")
      )

      ImMemorySearchUtil.extractValuesAndTargetTypes(config, patient).flatMap(_._1).flatMap(_.extractOpt[String]) mustEqual
        Seq("+90-555-0100")
    }
  }
}
