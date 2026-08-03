package io.onfhir.r5.audit

import io.onfhir.api.Resource
import io.onfhir.api.model.{FHIRRequest, HttpStatus, OutcomeIssue}
import io.onfhir.audit.{AgentsInfo, IFhirAuditCreator, ONFHIR_AUDIT_DETAIL_TYPES, ONFHIR_AUDIT_ENTITY_ROLES}
import io.onfhir.authz.{AuthContext, AuthzContext}
import io.onfhir.config.{AuditConfig, OnfhirConfig}
import io.onfhir.util.DateTimeUtil
import org.json4s.JsonAST.{JArray, JObject, JString}
import org.json4s.JsonDSL._

import java.time.Instant

class R5AuditCreator(auditConfig: AuditConfig) extends IFhirAuditCreator {

  /**
   * Create FHIR Audit resource for the interaction
   *
   * @param fhirRequest        FHIRRequest
   * @param authContext        Authentication Context
   * @param authzContext       Authorization Context
   * @param statusCode         HTTP Response Status Code
   * @param batchTransactionId If exists, the transaction/batch identifier that this is child request is bound to
   * @return
   */
  override def createAuditResource(fhirRequest: FHIRRequest, authContext: AuthContext, authzContext: Option[AuthzContext], statusCode: HttpStatus, batchTransactionId: Option[String]): Resource = {
    //Resolve agents
    val userAgent = createHumanUserAgent(authContext, authzContext)
    val clientAgent = createClientAgent(authContext, authzContext)
    var allAgents = userAgent.toList ++ clientAgent.toList
    if(allAgents.isEmpty) allAgents = allAgents :+ createAnonymousUserAgent(authContext)
    //Resolve entities
    val relatedPatientEntities = createRelatedPatientEntitities(fhirRequest, authzContext)
    val relatedResourceEntities = createRelatedResourceEntities(fhirRequest)
    val fhirRequestEntity = createRequestEntity(fhirRequest, batchTransactionId)
    val allEntities = relatedResourceEntities ++ relatedPatientEntities :+ fhirRequestEntity


    val auditCodes =
      fhirRequest.interaction match {
        case op if op.startsWith("$") =>
          List(createCodingElement( "http://hl7.org/fhir/restful-interaction", "operation"))
        case oth => List(createCodingElement( "http://hl7.org/fhir/restful-interaction", oth))
      }

    //Construct audit record
    var auditRecord:JObject =
      createBaseAuditEventRecord() ~
        ("category" -> List("coding" -> List(createCodingElement("http://terminology.hl7.org/CodeSystem/audit-event-type", "rest")))) ~
        ("code" -> ("coding" -> auditCodes)) ~
        ("action" -> resolveAuditEventActionCode(fhirRequest)) ~
        ("severity" -> "informational") ~
        ("recorded" -> DateTimeUtil.serializeInstant(Instant.now())) ~
        ("occurredPeriod" ->
          (("start" -> DateTimeUtil.serializeInstant(fhirRequest.requestTime)) ~
            ("end" -> DateTimeUtil.serializeInstant(fhirRequest.responseTime.getOrElse(Instant.now))))) ~
        ("outcome" -> createOutcome(fhirRequest, statusCode)) ~
        ("source" ->  createSource())

    if(allAgents.nonEmpty)
      auditRecord = auditRecord ~ ("agent" -> allAgents)

    if(allEntities.nonEmpty)
      auditRecord = auditRecord ~ ("entity" -> allEntities)

    auditRecord
  }

  protected def createAnonymousUserAgent(authContext: AuthContext):JObject = {
    ("type" -> ("coding" -> Seq(createCodingElement("http://terminology.hl7.org/CodeSystem/provenance-participant-type", "performer")))) ~
      ("who" -> createLogicalReference(None, "UNKNOWN")) ~
      ("networkString" -> authContext.networkAddress) ~
      ("requestor" -> true)
  }

  /**
   * Create AuditEvent.agent Backbone element content indicating human user details
   * @param authContext   Authentication context
   * @param authzContext  Authorization context
   * @return
   */
  protected def createHumanUserAgent(authContext: AuthContext, authzContext: Option[AuthzContext]):Option[JObject] = {
    authzContext.flatMap {
      case huser if huser.sub.isDefined =>
        // Try to resolve the corresponding FHIR user
        val fhirUser =
          auditConfig
            .fhirUserClaim //If this is defined
            .flatMap(c => huser.getSimpleParam[String](c))

        // Who is this human user
        val whoReference:JObject =
          fhirUser
            .map(fusr => createLiteralReference(fusr, huser.username)) //Use literal reference to the FHIR user entity if exist
            .getOrElse( createLogicalReference(huser.issuer, huser.sub.get)) //otherwise use the user identifier

        val userRoles:Option[List[JObject]] =
          auditConfig.userRolesClaim
            .flatMap(urc => huser.getListParam[String](urc))
            .map(_.map(role => role.split('|') match {
              case Array(r) => huser.issuer -> r
              case Array(s, r) => Some(s) -> r
            }))
            .map(_.map {
              case (Some(system), role) => "coding" -> Seq(createCodingElement(system, role))
              case (None, role) => "text" -> role
            })


        var temp:JObject =
          ("type" -> ("coding" -> Seq(createCodingElement("http://terminology.hl7.org/CodeSystem/extra-security-role-type", "humanuser")))) ~
            ("who" -> whoReference) ~
            ("requestor" -> true)

        if(userRoles.isDefined)
          temp = temp ~ ("role" -> userRoles.get)

        //If we don't resolve client, put network string here
        if(huser.clientId.isEmpty)
          temp = temp ~  ("networkString" -> authContext.networkAddress)

        Some(temp)
      case _ => None
    }
  }

  /**
   * Create AuditEvent.agent Backbone element content indicating the client application that request is sent
   * @param authContext   Authentication context
   * @param authzContext  Authorization context
   * @return
   */
  protected def createClientAgent(authContext: AuthContext, authzContext: Option[AuthzContext]):Option[JObject] = {
    authzContext
      .filter(_.clientId.isDefined)
      .map(ac => {
        val clientAgent:JObject =
          ("type" -> ("coding" -> Seq(createCodingElement("http://dicom.nema.org/resources/ontology/DCM", " 110150", Some("Application"))))) ~
          ("who" -> createLogicalReference(ac.issuer, ac.clientId.get)) ~
          ("networkString" -> authContext.networkAddress)
        clientAgent
      })
  }

  /**
   * Create AuditEvent.entity Backbone elements indicating the subject of the audit is patient
   * @param fhirRequest     FHIRRequest details
   * @param authzContext    Authorization context
   * @return
   */
  protected def createRelatedPatientEntitities(fhirRequest:FHIRRequest, authzContext: Option[AuthzContext]):Seq[JObject] = {
    val patientRefs = extractRelatedPatientReferences(fhirRequest, authzContext)
    val patientEntities:Seq[JObject] =
      patientRefs.map(patientRef => {
        ("what" -> createLiteralReference(patientRef, None)) ~
          ("role" -> ("coding" -> createCodingElement("http://terminology.hl7.org/CodeSystem/object-role", "1", Some("Patient"))))
      })
    patientEntities
  }

  /**
   * Create AuditEvent.entity Backbone elements indicating the FHIR resources related with the FHIR request
   * @param fhirRequest     FHIRRequest details
   * @return
   */
  protected def createRelatedResourceEntities(fhirRequest:FHIRRequest):Seq[JObject] = {
    val resourceRefs = extractRelatedResources(fhirRequest)
    resourceRefs.map(resourceRef =>
      ("what" -> createLiteralReference(resourceRef, None)) ~
        ("role" -> ("coding" -> createCodingElement("http://terminology.hl7.org/CodeSystem/object-role", "4", Some("Domain Resource"))))
    )
  }

  /**
   * Create an AuditEvent.entity  indicating the request identifier
   * @param requestId Request identifier
   * @return
   */
  protected def createRequestEntity(fhirRequest:FHIRRequest, batchOrTransactionId:Option[String]):JObject = {
    val base64QueryOpt = resolveQueryPart(fhirRequest)

    var roleCodings = List(createCodingElement(ONFHIR_AUDIT_ENTITY_ROLES.CODE_SYSTEM, ONFHIR_AUDIT_ENTITY_ROLES.FHIR_REQUEST))
    if(base64QueryOpt.isDefined)
      roleCodings = roleCodings :+ createCodingElement("http://terminology.hl7.org/CodeSystem/object-role", "24", Some("Query"))

    var requestEntity =
      ("what" ->
        ("identifier" ->
          ("system" -> auditConfig.auditSourceId) ~
          ("value" -> batchOrTransactionId.getOrElse(fhirRequest.id)) ~
          ("type" -> ("text" -> "Trace/Request ID"))
        )
      ) ~
        ("role" -> ("coding" -> roleCodings))

    //Put the details
    val allDetails = getFhirRequestEntityDetails(fhirRequest, batchOrTransactionId)
    if(allDetails.nonEmpty)
      requestEntity = requestEntity ~ ("detail" -> allDetails)

    if(base64QueryOpt.isDefined)
      requestEntity = requestEntity ~ ("query" -> base64QueryOpt.get)

    requestEntity
  }

  /**
   * Get the details to put in the entity for FHIR Request
   * @param fhirRequest
   * @param batchOrTransactionId
   * @return
   */
  protected def getFhirRequestEntityDetails(fhirRequest:FHIRRequest, batchOrTransactionId:Option[String]):List[JObject] = {
    //If this a child request add the child request id as details
    val childRequestIdDetail =
      batchOrTransactionId.map(_ =>
        ("type" -> ("coding" -> List(createCodingElement(ONFHIR_AUDIT_DETAIL_TYPES.CODE_SYSTEM, ONFHIR_AUDIT_DETAIL_TYPES.CHILD_REQUEST_ID)))) ~
          ("valueString" -> fhirRequest.id)
      )

    val resourceTypeDetail =
      fhirRequest.resourceType.map(rtype =>
        ("type" -> ("coding" -> List(createCodingElement(ONFHIR_AUDIT_DETAIL_TYPES.CODE_SYSTEM, ONFHIR_AUDIT_DETAIL_TYPES.RESOURCE_TYPE)))) ~
          ("valueCodeableConcept" -> ("coding" -> List(createCodingElement("http://hl7.org/fhir/fhir-types", rtype))))
      )

    val operationNameDetail =
      if(fhirRequest.interaction.startsWith("$"))
        Some(
          ("type" -> ("coding" -> List(createCodingElement(ONFHIR_AUDIT_DETAIL_TYPES.CODE_SYSTEM, ONFHIR_AUDIT_DETAIL_TYPES.OPERATION_NAME)))) ~
            ("valueString" -> fhirRequest.interaction)
        )
      else None

    val allDetails = childRequestIdDetail.toList ++ resourceTypeDetail ++ operationNameDetail.toList
    allDetails
  }

  /**
   * Create AuditEvent.outcome part
   * @param fhirRequest   FHIR request details
   * @param statusCode    Overall HTTP status code
   * @return
   */
  protected def createOutcome(fhirRequest:FHIRRequest, statusCode: HttpStatus):JObject = {
    val outcomeCode = statusCode.intValue() match {
      case succ if succ <300 && succ >= 200 => "success" //SUCCESS
      case mf if mf <500 && mf >= 400 => "error" //Minor Failure
      case sf if sf <600 && sf >= 500 => "fatal" //Serious Failure
      case _ =>  "information"
    }

    val outcomeDetails:Seq[JObject] =
      if(outcomeCode == "success") {
        Nil
      } //Error outcomes
      else {
        fhirRequest
          .response
          .map(fhirResponse => fhirResponse.outcomeIssues.filter(_.isError).map(convertOutcomeIssueToCodeableConcept))
          .getOrElse(Nil)
      }

    //TODO Put the HTTP status code as detail

    var outcome:JObject = "code" -> createCodingElement("http://hl7.org/fhir/issue-severity",outcomeCode)
    if(outcomeDetails.nonEmpty)
      outcome = outcome ~ ("detail" -> outcomeDetails.toList)
    outcome
  }

  private def convertOutcomeIssueToCodeableConcept(issue:OutcomeIssue):JObject = {
    var outcomeDetail:JObject = "text" -> (issue.code + s" => Diagnostics: [${issue.diagnostics.getOrElse("-")}], Expression: [${issue.expression.mkString(", ")}]")
    issue
      .details
      .foreach(outcomeDetailCode =>
        outcomeDetail = outcomeDetail ~ ("coding" -> List(createCodingElement("http://terminology.hl7.org/CodeSystem/operation-outcome", outcomeDetailCode)))
      )
    outcomeDetail
  }

  override def resolveAuditEventOutcomeCode(statusCode: HttpStatus):String = {
    statusCode.intValue() match {
      case succ if succ <300 && succ >= 200 => "success" //SUCCESS
      case mf if mf <500 && mf >= 400 => "error" //Minor Failure
      case sf if sf <600 && sf >= 500 => "fatal" //Serious Failure
      case _ =>  "information"
    }
  }

  /**
   * Create AuditEvent.source element indicating the source of the audit event
   * @return
   */
  protected def createSource():JObject = {
    var observer:JObject =
      ("type" -> "Device") ~
        (auditConfig.auditSourceId.split('|') match {
          case Array(system, value) => createLogicalReference(Some(system), value)
          case _ => createLogicalReference(None, auditConfig.auditSourceId)
        })

    if(auditConfig.auditSourceName.isDefined)
      observer = observer ~ ("display" -> auditConfig.auditSourceName.get)

    ("type" -> List("coding" -> List(createCodingElement("http://terminology.hl7.org/CodeSystem/security-source-type","4", Some("Application Server"))))) ~
      ("observer" -> observer)
  }

}
