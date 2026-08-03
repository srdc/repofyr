package io.onfhir.config

import io.onfhir.exception.InitializationException

/** Library-safe endpoint settings. The URL is intentionally kept as a String until Phase 2. */
final case class FhirEndpointSettings(rootUrl: String) {
  if (rootUrl.trim.isEmpty)
    throw new InitializationException("FHIR root URL cannot be empty")
}

final case class FhirRequestDefaults(
    searchHandling: FhirSearchHandling,
    returnPreference: FhirReturnPreference)

final case class FhirResultDefaults(
    defaultPageSize: Int,
    paginationMode: FhirPaginationMode,
    totalHandling: FhirSearchTotalHandling) {
  if (defaultPageSize < 0)
    throw new InitializationException("FHIR default page size cannot be negative")
}

final case class FhirSubscriptionSettings(
    active: Boolean,
    allowedResources: Option[Set[String]])

final case class FhirCapabilityDefaults(
    versioning: FhirVersioningPolicy,
    readHistory: Boolean,
    updateCreate: Boolean,
    conditionalCreate: Boolean,
    conditionalRead: FhirConditionalReadSupport,
    conditionalUpdate: Boolean,
    conditionalDelete: FhirConditionalDeleteSupport)

object FhirCapabilityDefaults {
  val Standard: FhirCapabilityDefaults = FhirCapabilityDefaults(
    FhirVersioningPolicy.Versioned,
    readHistory = false,
    updateCreate = false,
    conditionalCreate = false,
    FhirConditionalReadSupport.FullSupport,
    conditionalUpdate = false,
    FhirConditionalDeleteSupport.NotSupported)
}

sealed trait FhirSearchHandling { def code: String }
object FhirSearchHandling {
  case object Strict extends FhirSearchHandling { val code = "handling=strict" }
  case object Lenient extends FhirSearchHandling { val code = "handling=lenient" }

  def fromCode(value: String): FhirSearchHandling = value match {
    case Strict.code => Strict
    case Lenient.code => Lenient
    case other => FhirRuntimeSettingsValidation.invalid("FHIR search handling", other, Seq(Strict.code, Lenient.code))
  }
}

sealed trait FhirReturnPreference { def code: String }
object FhirReturnPreference {
  case object Minimal extends FhirReturnPreference { val code = "return=minimal" }
  case object Representation extends FhirReturnPreference { val code = "return=representation" }
  case object OperationOutcome extends FhirReturnPreference { val code = "return=OperationOutcome" }

  def fromCode(value: String): FhirReturnPreference = value match {
    case Minimal.code => Minimal
    case Representation.code => Representation
    case OperationOutcome.code => OperationOutcome
    case other => FhirRuntimeSettingsValidation.invalid("FHIR return preference", other, Seq(Minimal.code, Representation.code, OperationOutcome.code))
  }
}

sealed trait FhirPaginationMode { def code: String }
object FhirPaginationMode {
  case object Page extends FhirPaginationMode { val code = "page" }
  case object Offset extends FhirPaginationMode { val code = "offset" }

  def fromCode(value: String): FhirPaginationMode = value match {
    case Page.code => Page
    case Offset.code => Offset
    case other => FhirRuntimeSettingsValidation.invalid("FHIR pagination mode", other, Seq(Page.code, Offset.code))
  }
}

sealed trait FhirSearchTotalHandling { def code: String }
object FhirSearchTotalHandling {
  case object None extends FhirSearchTotalHandling { val code = "none" }
  case object Estimate extends FhirSearchTotalHandling { val code = "estimate" }
  case object Accurate extends FhirSearchTotalHandling { val code = "accurate" }

  def fromCode(value: String): FhirSearchTotalHandling = value match {
    case None.code => None
    case Estimate.code => Estimate
    case Accurate.code => Accurate
    case other => FhirRuntimeSettingsValidation.invalid("FHIR search total handling", other, Seq(None.code, Estimate.code, Accurate.code))
  }
}

sealed trait FhirVersioningPolicy { def code: String }
object FhirVersioningPolicy {
  case object NoVersion extends FhirVersioningPolicy { val code = "no-version" }
  case object Versioned extends FhirVersioningPolicy { val code = "versioned" }
  case object VersionedUpdate extends FhirVersioningPolicy { val code = "versioned-update" }

  def fromCode(value: String): FhirVersioningPolicy = value match {
    case NoVersion.code => NoVersion
    case Versioned.code => Versioned
    case VersionedUpdate.code => VersionedUpdate
    case other => FhirRuntimeSettingsValidation.invalid("FHIR versioning policy", other, Seq(NoVersion.code, Versioned.code, VersionedUpdate.code))
  }
}

sealed trait FhirConditionalReadSupport { def code: String }
object FhirConditionalReadSupport {
  case object NotSupported extends FhirConditionalReadSupport { val code = "not-supported" }
  case object ModifiedSince extends FhirConditionalReadSupport { val code = "modified-since" }
  case object NotMatch extends FhirConditionalReadSupport { val code = "not-match" }
  case object FullSupport extends FhirConditionalReadSupport { val code = "full-support" }

  def fromCode(value: String): FhirConditionalReadSupport = value match {
    case NotSupported.code => NotSupported
    case ModifiedSince.code => ModifiedSince
    case NotMatch.code => NotMatch
    case FullSupport.code => FullSupport
    case other => FhirRuntimeSettingsValidation.invalid("FHIR conditional read support", other, Seq(NotSupported.code, ModifiedSince.code, NotMatch.code, FullSupport.code))
  }
}

sealed trait FhirConditionalDeleteSupport { def code: String }
object FhirConditionalDeleteSupport {
  case object NotSupported extends FhirConditionalDeleteSupport { val code = "not-supported" }
  case object Single extends FhirConditionalDeleteSupport { val code = "single" }
  case object Multiple extends FhirConditionalDeleteSupport { val code = "multiple" }

  def fromCode(value: String): FhirConditionalDeleteSupport = value match {
    case NotSupported.code => NotSupported
    case Single.code => Single
    case Multiple.code => Multiple
    case other => FhirRuntimeSettingsValidation.invalid("FHIR conditional delete support", other, Seq(NotSupported.code, Single.code, Multiple.code))
  }
}

private[config] object FhirRuntimeSettingsValidation {
  def invalid[A](setting: String, value: String, allowed: Seq[String]): A =
    throw new InitializationException(s"Invalid $setting '$value'. Allowed values: ${allowed.mkString(", ")}")
}
