package io.repofyr.expression

import io.onfhir.api.FHIR_PARAMETER_TYPES
import io.onfhir.api.model.Parameter
import io.onfhir.config.{FSConfigReader, FhirSearchHandling, FhirServerConfig}
import io.onfhir.path.FhirPathEvaluator
import io.repofyr.r5.config.FhirR5Configurator
import org.json4s.JsonAST._
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import io.onfhir.expression.XFhirQueryParser
import io.onfhir.expression.FhirExpressionException

@RunWith(classOf[JUnitRunner])
class XFhirQueryParserTest extends Specification {
  val fhirConfigurator = new FhirR5Configurator
  val fileSystemConfigReader = new FSConfigReader(fhirVersion = "R5")
  val fhirServerConfig: FhirServerConfig = fhirConfigurator.initializeServerPlatform(fileSystemConfigReader, Set.empty[String])
  val fhirPathEvaluator: FhirPathEvaluator = FhirPathEvaluator()
  val xFhirQueryParser = new XFhirQueryParser(fhirServerConfig, FhirSearchHandling.Strict, fhirPathEvaluator)

  sequential

  private def findNormalParamByType(ptype:String):(String, String) = {
    fhirServerConfig.resourceQueryParameters.toSeq
      .flatMap { case (rtype, params) => params.values.map(sp => (rtype, sp.pname, sp.ptype)) }
      .find(_._3 == ptype)
      .map(v => (v._1, v._2))
      .getOrElse(throw new IllegalStateException(s"No supported '$ptype' parameter found in R5 config."))
  }

  private def getValue(params: List[Parameter], name:String):Option[String] =
    params.find(_.name == name).map(_.valuePrefixList.map(_._2).mkString(","))

  private def getAllValues(params: List[Parameter], name:String):List[String] =
    params.filter(_.name == name).flatMap(_.valuePrefixList.map(_._2))

  "XFhirQueryParser" should {
    "resolve reference from string list" in {
      val context = Map("refs" -> JArray(List(JString("Patient/1"), JString("Patient/2"))))

      val params = xFhirQueryParser.parseXFhirQuery("Observation", "subject={{%refs}}", context)
      getValue(params, "subject") must beSome("Patient/1,Patient/2")
    }

    "resolve reference from Reference objects" in {
      val context = Map("refs" -> JArray(List(
        JObject(List(JField("reference", JString("Patient/11")))),
        JObject(List(JField("reference", JString("Patient/22"))))
      )))

      val params = xFhirQueryParser.parseXFhirQuery("Observation",  "subject={{%refs}}", context)
      getValue(params, "subject") must beSome("Patient/11,Patient/22")
    }

    "handle simple x-fhir-query for token for simple string" in {
      val context = Map("hba1cLabCodes" -> JString("4548-4"))
      val params = xFhirQueryParser.parseXFhirQuery("Observation", "code={{%hba1cLabCodes}}&status=final", context)

      getValue(params, "code") must beSome("4548-4")
      getValue(params, "status") must beSome("final")
      params.length must_== 2
    }

    "resolve token from complex Coding-like object" in {
      val context = Map("coding" -> JObject(List(
        JField("system", JString("http://loinc.org")),
        JField("code", JString("4548-4"))
      )))

      val params = xFhirQueryParser.parseXFhirQuery("Observation", "code={{%coding}}", context)
      getValue(params, "code") must beSome("http://loinc.org|4548-4")
    }

    "resolve token from formatted string list" in {
      val context = Map("tokenVals" -> JArray(List(JString("http://loinc.org|4548-4"), JString("4575-5"))))

      val params = xFhirQueryParser.parseXFhirQuery("Observation", s"code={{%tokenVals}}", context)
      getValue(params, "code") must beSome("http://loinc.org|4548-4,4575-5")
    }

    "resolve quantity from FHIRPath quantity literal" in {
      val params = xFhirQueryParser.parseXFhirQuery("Observation", s"value-quantity={{4.5 'mg'}}")
      getValue(params, "value-quantity") must beSome("4.5|http://unitsofmeasure.org|mg")
    }

    "resolve quantity from complex Quantity object" in {
      val context = Map("q" -> JObject(List(
        JField("value", JDecimal(BigDecimal("4.5"))),
        JField("system", JString("http://unitsofmeasure.org")),
        JField("code", JString("mg"))
      )))

      val params = xFhirQueryParser.parseXFhirQuery("Observation", s"value-quantity={{%q}}", context)
      getValue(params,  "value-quantity") must beSome("4.5|http://unitsofmeasure.org|mg")
    }

    "resolve date from FHIRPath today()" in {
      val params = xFhirQueryParser.parseXFhirQuery("Observation", "date={{today()}}")
      getValue(params, "date") must beSome.which(_.matches("\\d{4}-\\d{2}-\\d{2}"))
    }

    "resolve number from numeric literal" in {
      val (rtype, pname) = findNormalParamByType(FHIR_PARAMETER_TYPES.NUMBER)

      val params = xFhirQueryParser.parseXFhirQuery(rtype, s"$pname={{5.0}}")
      getValue(params, pname) must beSome("5")
    }

    "reject number from string literal" in {
      val (rtype, pname) = findNormalParamByType(FHIR_PARAMETER_TYPES.NUMBER)
      xFhirQueryParser.parseXFhirQuery(rtype, s"$pname={{'5'}}") must throwA[FhirExpressionException]
    }

    "resolve string from string literal" in {
      val (rtype, pname) = findNormalParamByType(FHIR_PARAMETER_TYPES.STRING)

      val params = xFhirQueryParser.parseXFhirQuery(rtype, s"$pname={{'alpha'}}")
      getValue(params, pname) must beSome("alpha")
    }

    "reject string from numeric literal" in {
      val (rtype, pname) = findNormalParamByType(FHIR_PARAMETER_TYPES.STRING)
      xFhirQueryParser.parseXFhirQuery(rtype, s"$pname={{5}}") must throwA[FhirExpressionException]
    }

    "resolve uri from string literal" in {
      val (rtype, pname) = findNormalParamByType(FHIR_PARAMETER_TYPES.URI)

      val params = xFhirQueryParser.parseXFhirQuery(rtype, s"$pname={{'http://example.org/x'}}")
      getValue(params, pname) must beSome("http://example.org/x")
    }

    "reject date from string literal" in {
      val (rtype, pname) = findNormalParamByType(FHIR_PARAMETER_TYPES.DATE)
      xFhirQueryParser.parseXFhirQuery(rtype, s"$pname={{'2024-01-01'}}") must throwA[FhirExpressionException]
    }

    "preserve date prefix with placeholder resolution" in {
      val params = xFhirQueryParser.parseXFhirQuery("Observation", "date=gt{{today()}}")
      getValue(params, "date") must beSome.which(_.matches("\\d{4}-\\d{2}-\\d{2}"))
      params.find(_.name == "date").flatMap(_.valuePrefixList.headOption.map(_._1)) must beSome("gt")
    }

    "preserve quantity prefix with placeholder resolution" in {
      val params = xFhirQueryParser.parseXFhirQuery("Observation", "value-quantity=le{{4.5 'mg'}}")
      getValue(params, "value-quantity") must beSome("4.5|http://unitsofmeasure.org|mg")
      params.find(_.name == "value-quantity").flatMap(_.valuePrefixList.headOption.map(_._1)) must beSome("le")
    }

    "resolve multiple parameters together" in {
      val context = Map(
        "codes" -> JArray(List(JString("http://loinc.org|4548-4"), JString("4575-5"))),
        "refs" -> JArray(List(JString("Patient/1"), JString("Patient/2")))
      )

      val params = xFhirQueryParser.parseXFhirQuery("Observation", "code={{%codes}}&status=final&subject={{%refs}}", context)
      getValue(params, "code") must beSome("http://loinc.org|4548-4,4575-5")
      getValue(params, "status") must beSome("final")
      getValue(params, "subject") must beSome("Patient/1,Patient/2")
      params.length must_== 3
    }

    "keep repeated parameter keys as separate entries" in {
      val context = Map(
        "c1" -> JString("http://loinc.org|4548-4"),
        "c2" -> JString("4575-5")
      )

      val params = xFhirQueryParser.parseXFhirQuery("Observation", "code={{%c1}}&code={{%c2}}", context)
      getAllValues(params, "code") must contain(exactly("http://loinc.org|4548-4", "4575-5"))
    }

    "reject date placeholder value with prefix when expression returns string" in {
      xFhirQueryParser.parseXFhirQuery("Observation", "date=gt{{'2024-01-01'}}") must throwA[FhirExpressionException]
    }

    "reject number placeholder value with prefix when expression returns string" in {
      val (rtype, pname) = findNormalParamByType(FHIR_PARAMETER_TYPES.NUMBER)
      xFhirQueryParser.parseXFhirQuery(rtype, s"$pname=lt{{'5'}}") must throwA[FhirExpressionException]
    }

    "parse non-placeholder query unchanged" in {
      val params = xFhirQueryParser.parseXFhirQuery("Observation", "status=final&code=http://loinc.org|4548-4")
      getValue(params, "status") must beSome("final")
      getValue(params, "code") must beSome("http://loinc.org|4548-4")
    }
  }
}
