package io.repofyr.r4.subscription

import io.onfhir.api._
import io.repofyr.api.model._
import io.onfhir.api.model._
import io.onfhir.api.parsers.FHIRSearchParameterValueParser
import io.repofyr.api.util.SubscriptionUtil
import io.onfhir.api.util.FHIRUtil
import io.onfhir.config.{FhirSearchHandling, FhirServerConfig, FhirSubscriptionSettings}
import io.repofyr.exception.BadRequestException
import org.json4s.JsonAST.JObject

import scala.util.{Failure, Success, Try}

/** FHIR R4 Subscription resource parsing, policy, and validation behavior. */
final class R4SubscriptionUtil(
    fhirConfig: FhirServerConfig,
    subscriptionSettings: FhirSubscriptionSettings,
    defaultSearchHandling: FhirSearchHandling
) extends SubscriptionUtil {
  private val searchParameterValueParser =
    new FHIRSearchParameterValueParser(fhirConfig, defaultSearchHandling)

  override def parseFhirSubscription(subscription: Resource): FhirSubscription = {
    val criteriaStr = FHIRUtil.extractValue[String](subscription, "criteria")
    val (resourceType, parsedCriteria) = parseAndValidateFhirSubscriptionCriteria(criteriaStr)
    FhirSubscription(
      id = FHIRUtil.extractValue[String](subscription, "id"),
      rtype = resourceType,
      channel = parseFhirSubscriptionChannel((subscription \ "channel").asInstanceOf[JObject]),
      criteria = parsedCriteria,
      status = FHIRUtil.extractValue[String](subscription, "status"),
      expiration = FHIRUtil.extractValueOption[String](subscription, "end")
    )
  }

  override def parseFhirSubscriptionChannel(channel: Resource): FhirSubscriptionChannel =
    FhirSubscriptionChannel(
      channelType = FHIRUtil.extractValue[String](channel, "type"),
      endpoint = FHIRUtil.extractValueOption[String](channel, "endpoint"),
      payload = FHIRUtil.extractValueOption[String](channel, "payload"),
      headers = FHIRUtil.extractValueOption[Seq[String]](channel, "header").getOrElse(Nil)
    )

  override def parseAndValidateFhirSubscriptionCriteria(criteriaStr: String): (String, Seq[Parameter]) = {
    val parts = criteriaStr.split('?')
    val resourceType = parts.head
    if (parts.length > 2)
      badRequest(
        FHIRResponse.OUTCOME_CODES.NOT_SUPPORTED,
        s"Invalid FHIR Subscription criteria '$criteriaStr', it is not a valid FHIR Query statement!",
        "Subscription.criteria"
      )

    if (!fhirConfig.resourceConfigurations.contains(resourceType))
      badRequest(
        FHIRResponse.OUTCOME_CODES.NOT_SUPPORTED,
        s"Resource type '$resourceType' mentioned in given FHIR Subscription content is not supported in this onFhir instance! Please check the conformance statement of the server...",
        "Subscription.criteria"
      )

    if (subscriptionSettings.active && !subscriptionSettings.allowedResources.forall(_.contains(resourceType)))
      badRequest(
        FHIRResponse.OUTCOME_CODES.SECURITY,
        s"Resource type '$resourceType' mentioned in given FHIR Subscription content is not allowed for subscription in this onFhir instance! Please contact with administrator...",
        "Subscription.criteria"
      )

    val parsedParameters = Try(
      parts.drop(1).headOption
        .map(query => searchParameterValueParser.parseSearchParameters(resourceType, OrderedQuery.parse(query).toMultiMap))
        .getOrElse(Nil)
    ) match {
      case Success(parameters) => parameters
      case Failure(error) =>
        badRequest(
          FHIRResponse.OUTCOME_CODES.NOT_SUPPORTED,
          s"Invalid FHIR Subscription criteria '$criteriaStr'! One or more parameters are not supported or given query statement is not valid.${error.getMessage}",
          "Subscription.criteria"
        )
    }

    if (!parsedParameters.forall(parameter =>
      parameter.paramCategory == FHIR_PARAMETER_CATEGORIES.NORMAL || parameter.name == "_id"))
      badRequest(
        FHIRResponse.OUTCOME_CODES.NOT_SUPPORTED,
        s"Invalid FHIR Subscription criteria '$criteriaStr'! Only normal search parameters (chaining, reverse chaining, or special parameters are not supported) are supported for subscription in onFhir.io.",
        "Subscription.criteria"
      )

    resourceType -> parsedParameters
  }

  override def validateRequest(fhirRequest: FHIRRequest): Unit =
    fhirRequest.interaction match {
      case FHIR_INTERACTIONS.CREATE | FHIR_INTERACTIONS.UPDATE =>
        val resource = fhirRequest.resource.get
        parseAndValidateFhirSubscriptionCriteria(FHIRUtil.extractValue[String](resource, "criteria"))

        val channelType = FHIRUtil.extractValueOptionByPath[String](resource, "channel.type").get
        if (!SUPPORTED_SUBSCRIPTION_CHANNELS.contains(channelType))
          badRequest(
            FHIRResponse.OUTCOME_CODES.NOT_SUPPORTED,
            s"FHIR Subscription channel type $channelType not supported by onFhir.io yet!",
            "Subscription.channel.type"
          )

        val status = FHIRUtil.extractValue[String](resource, "status")
        if (status != SubscriptionStatusCodes.requested && status != SubscriptionStatusCodes.off)
          badRequest(
            FHIRResponse.OUTCOME_CODES.INVALID,
            s"Clients are not allowed to set FHIR Subscription status to values other than '${SubscriptionStatusCodes.requested}' or ${SubscriptionStatusCodes.off}!",
            "Subscription.status"
          )

        if (!FHIRUtil.extractValueOptionByPath[String](resource, "channel.payload")
          .forall(mimeType => fhirConfig.FHIR_SUPPORTED_RESULT_MEDIA_TYPES.map(_.toString).contains(mimeType)))
          badRequest(
            FHIRResponse.OUTCOME_CODES.NOT_SUPPORTED,
            "Given mime type is not supported for subscription mechanism!",
            "Subscription.channel.payload"
          )

      case FHIR_INTERACTIONS.PATCH =>
      case _ =>
    }

  override def validateChanges(oldContent: Resource, newContent: Resource): Unit =
    if (extractOption[String](oldContent, "criteria") != extractOption[String](newContent, "criteria"))
      badRequest(
        FHIRResponse.OUTCOME_CODES.INVALID,
        "Changing criteria for FHIR Subscription is forbidden in onFhir.io! You should delete the subscription and create a new one if you need different criteria!",
        "Subscription.criteria"
      )

  private def extractOption[T: Manifest](resource: Resource, path: String): Option[T] =
    FHIRUtil.extractValueOptionByPath[T](resource, path)

  private def badRequest(code: String, diagnostics: String, expression: String): Nothing =
    throw new BadRequestException(Seq(
      OutcomeIssue(
        FHIRResponse.SEVERITY_CODES.ERROR,
        code,
        None,
        Some(diagnostics),
        Seq(expression)
      )
    ))
}
