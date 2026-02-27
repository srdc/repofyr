package io.onfhir.expression

import io.onfhir.api.FHIR_PARAMETER_TYPES
import io.onfhir.api.parsers.ISearchParamPlaceholderResolver
import io.onfhir.api.util.FHIRUtil
import io.onfhir.path._
import org.json4s.JsonAST.{JArray, JNothing, JObject, JString, JValue}

import scala.util.Try

class FhirQueryPlaceholderResolver(fhirPathEvaluator:FhirPathEvaluator,
                                   contextParams:Map[String, JValue] = Map.empty,
                                   input:JValue = JNothing) extends ISearchParamPlaceholderResolver {

  /**
   * Resolve the placeholder in the expression
   *
   * @param spValueExpr     Search parameter value expression
   *                        e.g. ge{{5.2 'mg'}} -> ge5.2|http://unitsofmeasure.org|mg
   *                        e.g. {{%hba1cCodes}} -> 1044-2,5488-2  given codes in context parameter
   * @param searchParamType FHIR search parameter type given as hint for expression resolution
   * @return
   */
  override def resolveExpression(spValueExpr: String, searchParamType: String): String = {
    searchParamType match {
      case FHIR_PARAMETER_TYPES.REFERENCE => normalizeForReference(spValueExpr)
      case FHIR_PARAMETER_TYPES.NUMBER => normalizeForNumber(spValueExpr)
      case FHIR_PARAMETER_TYPES.QUANTITY => normalizeForQuantity(spValueExpr)
      case FHIR_PARAMETER_TYPES.DATE => normalizeForDate(spValueExpr)
      case FHIR_PARAMETER_TYPES.TOKEN => normalizeForToken(spValueExpr)
      case FHIR_PARAMETER_TYPES.URI | FHIR_PARAMETER_TYPES.STRING => normalizeForUriOrString(spValueExpr)
      case FHIR_PARAMETER_TYPES.COMPOSITE =>
        throw FhirExpressionException("Unresolved x-fhir-query! FHIRPath placeholders are not supported for composite search parameters.")
    }
  }

  private def normalizeForReference(fhirPathExpression:String):String = {
    evaluateFhirPath(fhirPathExpression) match {
      case Nil =>
        throw FhirExpressionException(s"Unresolved x-fhir-query! The FHIRPath expression $fhirPathExpression does not return any value a reference type search parameter.")
      case fps if fps.forall(_.isInstanceOf[FhirPathString]) =>
        fps.map(_.asInstanceOf[FhirPathString].s).mkString(",")
      case fpr if fpr.forall(_.isInstanceOf[FhirPathComplex]) =>
        val evaluatedReferences =
          fpr.map(_.asInstanceOf[FhirPathComplex])
            .map(refObj => Try(FHIRUtil.extractValueOption[String](refObj.json, "reference")).toOption.flatten)

        if(evaluatedReferences.exists(_.isEmpty))
          throw FhirExpressionException(s"Unresolved x-fhir-query! The FHIRPath expression $fhirPathExpression does not return string or Reference type values for a reference type search parameter.")
        else
          evaluatedReferences.map(_.get).mkString(",")
      case _ =>
        throw FhirExpressionException(s"Unresolved x-fhir-query! The FHIRPath expression $fhirPathExpression does not return string or Reference type values for a reference type search parameter.")
    }
  }

  private def normalizeForNumber(fhirPathExpression:String):String = {
    evaluateFhirPath(fhirPathExpression) match {
      case Nil =>
        throw FhirExpressionException(s"Unresolved x-fhir-query! The FHIRPath expression $fhirPathExpression does not return any value for a number type search parameter.")
      case fps if fps.forall(_.isInstanceOf[FhirPathNumber]) =>
        fps.map(_.asInstanceOf[FhirPathNumber].v.bigDecimal.stripTrailingZeros().toPlainString).mkString(",")
      case _ =>
        throw FhirExpressionException(s"Unresolved x-fhir-query! The FHIRPath expression $fhirPathExpression does not return number values for a number type search parameter.")
    }
  }

  private def normalizeForQuantity(fhirPathExpression:String):String = {
    evaluateFhirPath(fhirPathExpression) match {
      case Nil =>
        throw FhirExpressionException(s"Unresolved x-fhir-query! The FHIRPath expression $fhirPathExpression does not return any value for a quantity type search parameter.")
      case fps if fps.forall(_.isInstanceOf[FhirPathQuantity]) =>
        fps.map(_.asInstanceOf[FhirPathQuantity]).map(toSearchQuantity).mkString(",")
      case fps if fps.forall(_.isInstanceOf[FhirPathComplex]) =>
        fps.map(_.asInstanceOf[FhirPathComplex].json).map(toSearchQuantity).mkString(",")
      case _ =>
        throw FhirExpressionException(s"Unresolved x-fhir-query! The FHIRPath expression $fhirPathExpression does not return Quantity values for a quantity type search parameter.")
    }
  }

  private def normalizeForDate(fhirPathExpression:String):String = {
    evaluateFhirPath(fhirPathExpression) match {
      case Nil =>
        throw FhirExpressionException(s"Unresolved x-fhir-query! The FHIRPath expression $fhirPathExpression does not return any value for a date type search parameter.")
      case fps if fps.forall(_.isInstanceOf[FhirPathDateTime]) =>
        fps.map(_.toJson).map {
          case JString(s) => s
          case _ =>
            throw FhirExpressionException(s"Unresolved x-fhir-query! The FHIRPath expression $fhirPathExpression returns invalid date values for a date type search parameter.")
        }.mkString(",")
      case _ =>
        throw FhirExpressionException(s"Unresolved x-fhir-query! The FHIRPath expression $fhirPathExpression does not return date/dateTime/instant values for a date type search parameter.")
    }
  }

  private def normalizeForToken(fhirPathExpression:String):String = {
    evaluateFhirPath(fhirPathExpression) match {
      case Nil =>
        throw FhirExpressionException(s"Unresolved x-fhir-query! The FHIRPath expression $fhirPathExpression does not return any value for a token type search parameter.")
      case fps if fps.forall(_.isInstanceOf[FhirPathString]) =>
        fps.map(_.asInstanceOf[FhirPathString].s).mkString(",")
      case fps if fps.forall(_.isInstanceOf[FhirPathComplex]) =>
        fps.map(_.asInstanceOf[FhirPathComplex].json).flatMap(toSearchTokenValues).mkString(",")
      case _ =>
        throw FhirExpressionException(s"Unresolved x-fhir-query! The FHIRPath expression $fhirPathExpression does not return string, Coding, CodeableConcept, or Identifier values for a token type search parameter.")
    }
  }

  private def normalizeForUriOrString(fhirPathExpression:String):String = {
    evaluateFhirPath(fhirPathExpression) match {
      case Nil =>
        throw FhirExpressionException(s"Unresolved x-fhir-query! The FHIRPath expression $fhirPathExpression does not return any value for a uri/string type search parameter.")
      case fps if fps.forall(_.isInstanceOf[FhirPathString]) =>
        fps.map(_.asInstanceOf[FhirPathString].s).mkString(",")
      case _ =>
        throw FhirExpressionException(s"Unresolved x-fhir-query! The FHIRPath expression $fhirPathExpression does not return string values for a uri/string type search parameter.")
    }
  }

  private def evaluateFhirPath(fhirPathExpression:String):Seq[FhirPathResult] = {
    getFhirPathEvaluatorForContext.evaluate(fhirPathExpression, input)
  }

  private def toSearchQuantity(q:FhirPathQuantity):String = {
    val value = q.q.v.bigDecimal.stripTrailingZeros().toPlainString
    val unit = q.unit.stripPrefix("'").stripSuffix("'").stripPrefix("\"").stripSuffix("\"")
    if(unit.nonEmpty) s"$value|http://unitsofmeasure.org|$unit" else value
  }

  private def toSearchQuantity(q:JObject):String = {
    val value = Try(FHIRUtil.extractValueOption[Any](q, "value")).toOption.flatten
      .flatMap {
        case d:Double => Option(BigDecimal(d).bigDecimal.stripTrailingZeros().toPlainString)
        case f:Float => Option(BigDecimal.decimal(f).bigDecimal.stripTrailingZeros().toPlainString)
        case i:Int => Option(i.toString)
        case l:Long => Option(l.toString)
        case bd:BigDecimal => Option(bd.bigDecimal.stripTrailingZeros().toPlainString)
        case bd:java.math.BigDecimal => Option(bd.stripTrailingZeros().toPlainString)
        case _ => None
      }

    if(value.isEmpty)
      throw FhirExpressionException("Unresolved x-fhir-query! Quantity value should include a numeric 'value' element.")

    val system = Try(FHIRUtil.extractValueOption[String](q, "system")).toOption.flatten.getOrElse("")
    val code = Try(FHIRUtil.extractValueOption[String](q, "code")).toOption.flatten
      .orElse(Try(FHIRUtil.extractValueOption[String](q, "unit")).toOption.flatten)
      .getOrElse("")

    if(system.nonEmpty || code.nonEmpty) s"${value.get}|$system|$code" else value.get
  }

  private def toSearchTokenValues(jobj:JObject):Seq[String] = {
    val codings = Try(FHIRUtil.extractValueOption[JArray](jobj, "coding")).toOption.flatten.map(_.arr).getOrElse(Nil)
      .collect { case o:JObject => o }
    if(codings.nonEmpty){
      codings.map(toSearchTokenValue)
    } else {
      Seq(toSearchTokenValue(jobj))
    }
  }

  private def toSearchTokenValue(jobj:JObject):String = {
    val codeOrValue = Try(FHIRUtil.extractValueOption[String](jobj, "code")).toOption.flatten
      .orElse(Try(FHIRUtil.extractValueOption[String](jobj, "value")).toOption.flatten)
    val system = Try(FHIRUtil.extractValueOption[String](jobj, "system")).toOption.flatten

    (system, codeOrValue) match {
      case (Some(s), Some(c)) => s"$s|$c"
      case (Some(s), None) => s"$s|"
      case (None, Some(c)) => c
      case _ =>
        throw FhirExpressionException("Unresolved x-fhir-query! Token resolution requires at least one of code/value/system fields.")
    }
  }

  private def getFhirPathEvaluatorForContext:FhirPathEvaluator = {
    if(contextParams.isEmpty)
      fhirPathEvaluator
    else
      contextParams.foldLeft(fhirPathEvaluator){
        case (fpe, cp) => fpe.withEnvironmentVariable(cp._1, cp._2)
      }
  }
}
