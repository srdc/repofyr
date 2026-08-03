package io.onfhir.template

import io.onfhir.expression.FhirExpression
import org.json4s.JsonAST.JString
import org.json4s.jackson.JsonMethods.parse
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

@RunWith(classOf[JUnitRunner])
class FhirTemplateReadmeExampleTest extends Specification {
  sequential

  "the README example" should {
    "render a FHIRPath placeholder" in {
      implicit val executionContext: ExecutionContext = ExecutionContext.global
      val handler = new FhirTemplateExpressionHandler(isSourceContentFhir = true)
      val patient = parse("""{"resourceType":"Patient","id":"p1"}""")
      val template = FhirExpression(
        "patient-summary",
        handler.languageSupported,
        value = Some(parse("""{"id":"{{ Patient.id }}"}""")))

      val rendered = Await.result(handler.evaluateExpression(template, Map.empty, patient), 5.seconds)
      (rendered \ "id") mustEqual JString("p1")
    }
  }
}
