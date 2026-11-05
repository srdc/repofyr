package io.onfhir.operation

import akka.http.scaladsl.model.StatusCodes
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.{Curve, ECKey}
import com.nimbusds.jose.{JWSAlgorithm, JWSHeader, JWSObject, Payload}
import io.onfhir.api.Resource
import io.onfhir.api.model.{FHIRMultiOperationParam, FHIROperationRequest, FHIROperationResponse, FHIRResponse, FHIRSimpleOperationParam, OutcomeIssue}
import io.onfhir.api.parsers.FHIRSearchParameterValueParser
import io.onfhir.api.service.FHIROperationHandlerService
import io.onfhir.api.util.FHIRUtil
import io.onfhir.config.{FhirConfigurationManager, OnfhirConfig}
import io.onfhir.db.ResourceManager
import io.onfhir.exception.{BadRequestException, InternalServerException}
import io.onfhir.util.JsonFormatter._
import org.json4s.JsonDSL._
import org.json4s._
import org.slf4j.{Logger, LoggerFactory}

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.util.Try

/**
 * SMART Health Cards $health-cards-issue operation (POST [base]/Patient/[id]/$health-cards-issue)
 * See https://spec.smarthealth.cards and http://hl7.org/fhir/uv/smart-health-cards-and-links
 */
class HealthCardIssueOperationHandler extends FHIROperationHandlerService {

  private val logger: Logger = LoggerFactory.getLogger(this.getClass)

  private val HEALTH_CARD_TYPE = "https://smarthealth.cards#health-card"

  /**
   * Key id ('kid') of the signing key in the resource server JWKS (fhir.authorization.resource-server-jwks-path),
   * configurable with 'fhir.health-cards.signing-key-id'; without it, the first private EC P-256 key of the JWKS is used.
   * Each deployment is a separate issuer, so it should have its own key.
   */
  private val SIGNING_KEY_ID: Option[String] =
    Try(OnfhirConfig.config.getString("fhir.health-cards.signing-key-id")).toOption.map(_.trim).filter(_.nonEmpty)

  /**
   * Identity claims put into the card when the request has no includeIdentityClaim.
   * Configurable with 'fhir.health-cards.default-identity-claims' (an empty list puts the whole Patient in the card);
   * nothing profile specific (e.g. Patient extensions) is assumed here, list them in the config if they are needed.
   */
  private val DEFAULT_IDENTITY_CLAIMS: Seq[String] =
    Try(OnfhirConfig.config.getStringList("fhir.health-cards.default-identity-claims").asScala.toList)
      .getOrElse(List("Patient.name", "Patient.birthDate", "Patient.gender"))

  /** Legacy SHC credential types (deprecated since SHC 1.4.0) mapped to FHIR resource types */
  private val LEGACY_CREDENTIAL_TYPES = Map("immunization" -> "Immunization", "laboratory" -> "Observation")

  /**
   * credentialType for an International Patient Summary: the patient's latest IPS Composition (LOINC 60591-5)
   * with the resources it references
   */
  private val IPS_CREDENTIAL_TYPE = "#ips"
  private val IPS_COMPOSITION_TYPE = "http://loinc.org|60591-5"

  /**
   * How many levels of references are followed from a Composition, e.g. 2: the section entries and author (level 1),
   * then what they reference such as MedicationStatement.medicationReference (level 2).
   * Configurable with 'fhir.health-cards.composition-reference-depth'.
   */
  private val COMPOSITION_REFERENCE_DEPTH: Int =
    Try(OnfhirConfig.config.getInt("fhir.health-cards.composition-reference-depth")).getOrElse(2)

  /** Search parameter used to filter each supported resource type with the credentialValueSet codes */
  private val CODE_SEARCH_PARAM = Map(
    "Immunization" -> "vaccine-code",
    "Observation" -> "code",
    "Condition" -> "code",
    "MedicationStatement" -> "code",
    "FamilyMemberHistory" -> "code"
  )

  /**
   * Execute the operation and prepare the output parameters for the operation
   *
   * @param operationName    Operation name as defined after '$' symbol e.g. meta-add
   * @param operationRequest Operation Request including the parameters
   * @param resourceType     The resource type that operation is called if exists
   * @param resourceId       The resource id that operation is called if exists
   * @return The response containing the Http status code and the output parameters
   */
  override def executeOperation(operationName: String, operationRequest: FHIROperationRequest, resourceType: Option[String], resourceId: Option[String]): Future[FHIROperationResponse] = {
    if (resourceId.isEmpty)
      Future.failed(new BadRequestException(Seq(invalid("$health-cards-issue must be called on a Patient instance, i.e. POST [base]/Patient/[id]/$health-cards-issue"))))
    else
      prepareBundle(operationRequest, resourceId.get) map {
        //No matching clinical data -> empty Parameters (no credential issued)
        case None => new FHIROperationResponse(StatusCodes.OK)
        case Some((bundle, bundledResources)) =>
          val resp = new FHIROperationResponse(StatusCodes.OK)
          resp.setPrimitiveParam("verifiableCredential", issueHealthCard(bundle))
          bundledResources.foreach { case (index, rtype, rid) =>
            resp.setMultiParam("resourceLink", FHIRMultiOperationParam(Seq(
              "vcIndex" -> FHIRSimpleOperationParam(JInt(0)),
              "bundledResource" -> FHIRSimpleOperationParam(JString(s"resource:$index")),
              "hostedResource" -> FHIRSimpleOperationParam(JString(s"$issuerUrl/$rtype/$rid"))
            )))
          }
          resp
      }
  }

  /**
   * Issuer URL (JWT iss): SHALL use https and SHALL NOT end with '/'
   */
  private def issuerUrl: String = OnfhirConfig.fhirRootUrl.stripSuffix("/")

  /**
   * Wrap the FHIR Bundle into a VC, minify, raw-DEFLATE and sign it as a compact JWS (ES256, zip=DEF, kid=JWK thumbprint)
   */
  private def issueHealthCard(bundle: Resource): String = {
    if (!issuerUrl.startsWith("https://"))
      logger.warn(s"SMART Health Card issuer '$issuerUrl' does not use https; verifiers will reject the card.")
    val credentialSubject = ("fhirVersion" -> FhirConfigurationManager.fhirConfig.fhirVersion) ~ ("fhirBundle" -> bundle)
    val vc = ("type" -> JArray(List(JString(HEALTH_CARD_TYPE)))) ~ ("credentialSubject" -> credentialSubject)
    val payload = ("iss" -> issuerUrl) ~ ("nbf" -> System.currentTimeMillis() / 1000) ~ ("vc" -> vc)
    val jwk = signingKey
    val signer = new ECDSASigner(jwk)
    val jwsObject = new JWSObject(
      new JWSHeader.Builder(JWSAlgorithm.ES256).customParam("zip", "DEF").keyID(jwk.computeThumbprint().toString).build(),
      new Payload(deflate(payload.toJson))
    )
    jwsObject.sign(signer)
    jwsObject.serialize()
  }

  /**
   * The ES256 signing key: the configured key id, otherwise the first private EC P-256 key of the JWKS
   */
  private def signingKey: ECKey = {
    val jwkSet = Option(OnfhirConfig.authzConfig).flatMap(config => Option(config.protectedResourceJWKSet))
      .getOrElse(throw new InternalServerException("No JWKS to sign SMART Health Cards with (fhir.authorization.resource-server-jwks-path)."))
    val keys = jwkSet.getKeys.asScala.collect { case key: ECKey if key.isPrivate && key.getCurve == Curve.P_256 => key }
    SIGNING_KEY_ID match {
      case Some(keyId) =>
        keys.find(_.getKeyID == keyId)
          .getOrElse(throw new InternalServerException(s"Signing key '$keyId' (fhir.health-cards.signing-key-id) is not a private EC P-256 key of the JWKS."))
      case None =>
        keys.headOption.getOrElse(throw new InternalServerException("The JWKS has no private EC P-256 key to sign SMART Health Cards with."))
    }
  }

  /**
   * Collect the patient and the requested clinical resources and build the SHC FHIR Bundle
   * @return None if no clinical resource matches, otherwise the bundle and (entry index, resource type, resource id) of each entry
   */
  private def prepareBundle(operationRequest: FHIROperationRequest, patientId: String): Future[Option[(Resource, Seq[(Int, String, String)])]] = {
    ResourceManager.getResource("Patient", patientId, excludeExtraFields = true) flatMap {
      case None =>
        throw new BadRequestException(Seq(invalid(s"Patient '$patientId' not found.")))
      case Some(patient) =>
        val patientRef = "Patient/" + patientId
        val since = operationRequest.extractParamValues[String]("_since").headOption
        val requestedTypes =
          operationRequest.extractParamValues[String]("credentialType")
            .flatMap(normalizeCredentialType)
            .filterNot(_ == "Patient") //Patient (identity) is always included
            .distinct
        getValueSetCodes(operationRequest)
          .flatMap(codes => Future.sequence(requestedTypes.map(rtype => searchClinicalResources(rtype, patientRef, codes, since))))
          .map(results => {
            val clinicalResources = results.flatten
            if (clinicalResources.isEmpty)
              None
            else
              Some(buildShcBundle(applyIdentityClaims(patient, requestedOrDefaultClaims(operationRequest)), clinicalResources))
          })
    }
  }

  /**
   * Map a credentialType value to a FHIR resource type; legacy '#immunization' / '#laboratory' values are supported,
   * '#ips' is kept as is (see IPS_CREDENTIAL_TYPE)
   */
  private def normalizeCredentialType(credentialType: String): Option[String] = {
    val t = credentialType.stripPrefix("https://smarthealth.cards").stripPrefix("#")
    if (t == IPS_CREDENTIAL_TYPE.stripPrefix("#"))
      Some(IPS_CREDENTIAL_TYPE)
    else
      LEGACY_CREDENTIAL_TYPES.get(t).orElse(if (t.nonEmpty && t.head.isUpper) Some(t) else None)
  }

  /**
   * Search the patient's resources of the given type (raw, ids kept; minimization happens in buildShcBundle)
   */
  private def searchClinicalResources(rtype: String, patientRef: String, codes: Seq[String], since: Option[String]): Future[Seq[Resource]] = {
    val commonParams: Map[String, List[String]] = Map("patient" -> List(patientRef)) ++ since.toList.map(s => "_lastUpdated" -> List("ge" + s))
    rtype match {
      case "Immunization" | "Observation" | "Condition" | "MedicationStatement" | "FamilyMemberHistory" =>
        //Codes are OR'ed in a single comma separated value; separate values of a search parameter are AND'ed
        val codeFilter = if (codes.isEmpty) Map.empty[String, List[String]] else Map(CODE_SEARCH_PARAM(rtype) -> List(codes.map(_.replace(",", "\\,")).mkString(",")))
        ResourceManager.searchResources(rtype, getParams(rtype, commonParams ++ codeFilter), excludeExtraParams = true)
          .map(_._2)
      case "CarePlan" =>
        ResourceManager.searchResources("CarePlan", getParams("CarePlan", commonParams ++ Map("status" -> List("active"), "_include" -> List("*"))), excludeExtraParams = true)
          .map { case (_, carePlans, includedResources) => carePlans ++ includedResources }
      case "Composition" =>
        //credentialValueSet codes filter Composition.type
        searchLatestComposition(patientRef, codes, since)
      case IPS_CREDENTIAL_TYPE =>
        searchLatestComposition(patientRef, Seq(IPS_COMPOSITION_TYPE), since)
      case other =>
        logger.warn(s"credentialType '$other' is not supported by $$health-cards-issue; ignored.")
        Future.apply(Seq.empty)
    }
  }

  /**
   * The patient's latest Composition (by Composition.date) of one of the given types, followed by the resources it references
   * (see COMPOSITION_REFERENCE_DEPTH). Clients save the document's Composition (e.g. an IPS) before asking for a card.
   */
  private def searchLatestComposition(patientRef: String, types: Seq[String], since: Option[String]): Future[Seq[Resource]] = {
    val params: Map[String, List[String]] =
      Map("subject" -> List(patientRef), "_sort" -> List("-date"), "_count" -> List("1")) ++
        since.toList.map(s => "_lastUpdated" -> List("ge" + s)) ++
        (if (types.isEmpty) Nil else List("type" -> List(types.map(_.replace(",", "\\,")).mkString(","))))
    ResourceManager.searchResources("Composition", getParams("Composition", params), excludeExtraParams = true) flatMap {
      case (_, compositions, _) => compositions.headOption match {
        case None => Future.apply(Seq.empty)
        case Some(composition) =>
          resolveReferences(Seq(composition), COMPOSITION_REFERENCE_DEPTH, Set(resourceKey(composition), patientRef)).map(composition +: _)
      }
    }
  }

  /**
   * Resources on this server referenced by the given resources, following references up to the given depth.
   * Patients are not followed (the card's identity is the operation's Patient); unknown resource types and missing
   * resources are skipped, their references are dropped when the bundle is built.
   * @param known 'Type/id' of the resources already collected
   */
  private def resolveReferences(resources: Seq[Resource], depth: Int, known: Set[String]): Future[Seq[Resource]] = {
    val references =
      resources
        .flatMap(collectReferences)
        .map(normalizeReference)
        .filter(_.matches("[A-Z][A-Za-z]+/[A-Za-z0-9\\-.]{1,64}"))
        .filterNot(ref => known.contains(ref) || ref.startsWith("Patient/"))
        .distinct
        .filter(ref => FhirConfigurationManager.fhirConfig.resourceConfigurations.contains(ref.takeWhile(_ != '/')))
    if (depth <= 0 || references.isEmpty)
      Future.apply(Seq.empty)
    else
      Future.sequence(
        references.groupBy(_.takeWhile(_ != '/')).toSeq.map { case (rtype, refs) =>
          ResourceManager.getResourcesWithIds(rtype, refs.map(_.dropWhile(_ != '/').drop(1)).toSet, excludeExtraFields = true)
        }
      ).flatMap(found => {
        val resolved = found.flatten
        resolveReferences(resolved, depth - 1, known ++ references).map(resolved ++ _)
      })
  }

  /** Literal Reference.reference values in a resource (contained '#...' references excluded) */
  private def collectReferences(value: JValue): Seq[String] = value match {
    case JObject(fields) => fields.flatMap {
      case ("reference", JString(ref)) if !ref.startsWith("#") => Seq(ref)
      case (_, child) => collectReferences(child)
    }
    case JArray(elements) => elements.flatMap(collectReferences)
    case _ => Nil
  }

  private def resourceKey(resource: Resource): String = {
    val (rtype, rid) = FHIRUtil.extractResourceTypeAndId(resource)
    s"$rtype/$rid"
  }

  private def getParams(resourceType: String, params: Map[String, List[String]]) = {
    FHIRSearchParameterValueParser.parseSearchParameters(resourceType, params.filter(_._2.nonEmpty))
  }

  /**
   * Identity claims requested with includeIdentityClaim, otherwise the configured defaults
   */
  private def requestedOrDefaultClaims(operationRequest: FHIROperationRequest): Seq[String] = {
    val requested = operationRequest.extractParamValues[String]("includeIdentityClaim")
    if (requested.nonEmpty) requested else DEFAULT_IDENTITY_CLAIMS
  }

  /**
   * Identity claims (e.g. Patient.name, Patient.birthDate) restrict the Patient elements put into the card; no claims -> whole Patient
   */
  private def applyIdentityClaims(patient: Resource, claims: Seq[String]): Resource = {
    if (claims.isEmpty)
      patient
    else {
      val elementsToKeep = claims.map(_.stripPrefix("Patient.").takeWhile(_ != '.')).toSet ++ Set("resourceType", "id")
      JObject(patient.obj.filter { case (key, _) => elementsToKeep.contains(key) })
    }
  }

  /**
   * Build the SHC FHIR Bundle:
   *  - Patient is always resource:0, other resources follow (duplicates and other Patients removed)
   *  - onFHIR DB fields (_id, __method, __scode) removed
   *  - Resource.id, Resource.meta (except meta.security) and DomainResource.text removed
   *  - References rewritten to resource:N; references to resources not in the bundle are dropped
   *  - Bundle.type = collection (a 'document' would need a Composition as first entry)
   */
  private def buildShcBundle(patient: Resource, resources: Seq[Resource]): (Resource, Seq[(Int, String, String)]) = {
    val allResources =
      (patient +: resources.filterNot(r => FHIRUtil.extractResourceType(r) == "Patient"))
        .map(FHIRUtil.clearExtraFields)
        .foldLeft(Vector.empty[Resource])((acc, r) =>
          if (acc.exists(a => FHIRUtil.extractResourceTypeAndId(a) == FHIRUtil.extractResourceTypeAndId(r))) acc else acc :+ r
        )
    val typesAndIds = allResources.map(FHIRUtil.extractResourceTypeAndId)
    val referenceMap = typesAndIds.zipWithIndex.map { case ((rtype, rid), index) => s"$rtype/$rid" -> s"resource:$index" }.toMap
    val entries =
      allResources.zipWithIndex.map { case (resource, index) =>
        ("fullUrl" -> s"resource:$index") ~ ("resource" -> rewriteReferences(minimize(resource), referenceMap))
      }
    val bundle: Resource = ("resourceType" -> "Bundle") ~ ("type" -> "collection") ~ ("entry" -> JArray(entries.toList))
    bundle -> typesAndIds.zipWithIndex.map { case ((rtype, rid), index) => (index, rtype, rid) }
  }

  /**
   * Remove top level Resource.id, Resource.meta (keeping meta.security if present) and DomainResource.text
   */
  private def minimize(resource: Resource): JValue = {
    JObject(resource.obj.flatMap {
      case ("id", _) | ("text", _) => Nil
      case ("meta", meta) =>
        meta \ "security" match {
          case security: JArray if security.arr.nonEmpty => List("meta" -> JObject("security" -> security))
          case _ => Nil
        }
      case other => List(other)
    })
  }

  /**
   * Recursively rewrite Reference.reference to resource:N, drop unresolvable references (keeping Reference.display),
   * then drop empty objects/arrays and extensions left without a value
   */
  private def rewriteReferences(value: JValue, referenceMap: Map[String, String]): JValue = value match {
    case JObject(fields) =>
      JObject(fields.flatMap {
        case ("reference", JString(ref)) if ref.startsWith("#") => List("reference" -> JString(ref)) //Contained resource reference
        case ("reference", JString(ref)) => referenceMap.get(normalizeReference(ref)).map(r => "reference" -> JString(r)).toList
        case (key@("extension" | "modifierExtension"), JArray(extensions)) =>
          val kept = extensions.map(rewriteReferences(_, referenceMap)).filterNot(e => e == JObject(Nil) || isBareExtension(e))
          if (kept.isEmpty) Nil else List(key -> JArray(kept))
        case (key, child) =>
          rewriteReferences(child, referenceMap) match {
            case JObject(Nil) | JArray(Nil) => Nil
            case newChild => List(key -> newChild)
          }
      })
    case JArray(elements) =>
      JArray(elements.map(rewriteReferences(_, referenceMap)).filterNot(_ == JObject(Nil)))
    case other => other
  }

  /**
   * [issuer]/Patient/1/_history/2 or Patient/1/_history/2 -> Patient/1
   */
  private def normalizeReference(ref: String): String =
    ref.stripPrefix(issuerUrl + "/").split('/').take(2).mkString("/")

  /**
   * An extension with only a url (its value was an unresolvable reference) is invalid (ext-1), so it is dropped
   */
  private def isBareExtension(value: JValue): Boolean = value match {
    case JObject(fields) => fields.forall(_._1 == "url")
    case _ => false
  }

  /**
   * Resolve credentialValueSet URLs to 'system|code' tokens (only ValueSets that enumerate compose.include.concept)
   */
  private def getValueSetCodes(operationRequest: FHIROperationRequest): Future[Seq[String]] = {
    val valueSetUrls = operationRequest.extractParamValues[String]("credentialValueSet")
    if (valueSetUrls.isEmpty)
      Future.apply(Seq.empty)
    else {
      val searchParams = FHIRSearchParameterValueParser.parseSearchParameters("ValueSet", Map("url" -> valueSetUrls.toList))
      ResourceManager.searchResources("ValueSet", searchParams, excludeExtraParams = true) map { case (_, valueSets, _) =>
        val codes = for {
          valueSet <- valueSets
          JArray(includes) <- List(valueSet \ "compose" \ "include")
          include <- includes
          JString(system) <- List(include \ "system")
          JArray(concepts) <- List(include \ "concept")
          concept <- concepts
          JString(code) <- List(concept \ "code")
        } yield s"$system|$code"
        if (codes.isEmpty)
          logger.warn(s"credentialValueSet ${valueSetUrls.mkString(", ")} resolved to no enumerated codes; no code filter applied.")
        codes
      }
    }
  }

  /**
   * Raw DEFLATE (RFC 1951, no zlib/gzip header) as required for SHC JWS payloads
   */
  private def deflate(input: String): Array[Byte] = {
    val deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true)
    try {
      deflater.setInput(input.getBytes("UTF-8"))
      deflater.finish()
      val output = new ByteArrayOutputStream()
      val buffer = new Array[Byte](4096)
      while (!deflater.finished())
        output.write(buffer, 0, deflater.deflate(buffer))
      output.toByteArray
    } finally {
      deflater.end()
    }
  }

  private def invalid(message: String): OutcomeIssue =
    OutcomeIssue(FHIRResponse.SEVERITY_CODES.ERROR, FHIRResponse.OUTCOME_CODES.INVALID, None, Some(message), Nil)
}
