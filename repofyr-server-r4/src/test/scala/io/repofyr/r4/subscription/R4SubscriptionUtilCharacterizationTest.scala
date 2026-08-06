package io.repofyr.r4.subscription

import io.onfhir.config.{FhirSearchHandling, FhirServerConfig, FhirSubscriptionSettings, ResourceConf}
import io.onfhir.api.FHIR_INTERACTIONS
import io.onfhir.api.model.FHIRRequest
import io.repofyr.exception.BadRequestException
import org.json4s.JObject
import org.json4s.jackson.JsonMethods.parse
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class R4SubscriptionUtilCharacterizationTest extends Specification {
  private val fhirConfig = new FhirServerConfig("R4")
  fhirConfig.resourceConfigurations = Map("Observation" -> ResourceConf("Observation"))
  fhirConfig.resourceQueryParameters = Map.empty
  fhirConfig.commonQueryParameters = Map.empty
  fhirConfig.FHIR_RESULT_PARAMETERS = Nil
  fhirConfig.FHIR_SPECIAL_PARAMETERS = Nil

  private val util = new R4SubscriptionUtil(
    fhirConfig,
    FhirSubscriptionSettings(active = true, allowedResources = Some(Set("Observation"))),
    FhirSearchHandling.Strict
  )

  "the existing R4 Subscription implementation" should {
    "parse the R4 criteria, channel, status, and expiration model" in {
      val subscription = parse(
        """
          |{
          |  "resourceType": "Subscription",
          |  "id": "subscription-1",
          |  "status": "requested",
          |  "criteria": "Observation",
          |  "end": "2030-01-01T00:00:00Z",
          |  "channel": {
          |    "type": "rest-hook",
          |    "endpoint": "https://example.org/hook",
          |    "payload": "application/fhir+json",
          |    "header": ["Authorization: Bearer token"]
          |  }
          |}
          |""".stripMargin
      ).asInstanceOf[JObject]

      val parsed = util.parseFhirSubscription(subscription)

      parsed.id mustEqual "subscription-1"
      parsed.rtype mustEqual "Observation"
      parsed.criteria must beEmpty
      parsed.status mustEqual "requested"
      parsed.expiration must beSome("2030-01-01T00:00:00Z")
      parsed.channel.channelType mustEqual "rest-hook"
      parsed.channel.endpoint must beSome("https://example.org/hook")
      parsed.channel.payload must beSome("application/fhir+json")
      parsed.channel.headers mustEqual Seq("Authorization: Bearer token")
    }

    "reject a resource type excluded by the R4 server subscription policy" in {
      util.parseAndValidateFhirSubscriptionCriteria("Patient") must throwA[BadRequestException].like {
        case error: BadRequestException =>
          error.outcomeIssues.head.expression must contain("Subscription.criteria")
      }
    }

    "retain R4 request validation rules behind the release-specific strategy" in {
      val resource = parse(
        """{
          |  "resourceType": "Subscription",
          |  "id": "subscription-1",
          |  "status": "active",
          |  "criteria": "Observation",
          |  "channel": { "type": "rest-hook" }
          |}""".stripMargin
      ).asInstanceOf[JObject]
      val request = FHIRRequest(
        interaction = FHIR_INTERACTIONS.CREATE,
        requestUri = "/Subscription"
      )
      request.resource = Some(resource)

      util.validateRequest(request) must throwA[BadRequestException].like {
        case error: BadRequestException =>
          error.outcomeIssues.head.expression must contain("Subscription.status")
      }
    }
  }
}
