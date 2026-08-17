package io.repofyr.stu3.audit

import java.time.Instant

import io.onfhir.api.Resource
import io.onfhir.api.model.{FHIRRequest, HttpStatus}
import io.repofyr.audit.{AgentsInfo, IFhirAuditCreator}
import io.onfhir.authz.{AuthContext, AuthzContext}
import io.repofyr.config.OnfhirConfig
import io.onfhir.util.DateTimeUtil
import org.json4s.JsonAST._
import org.json4s.JsonDSL._

/**
  * Created by tuncay on 5/15/2017.
  */
class STU3AuditCreator extends IFhirAuditCreator {
  /**
    * Create STU3 AuditEvent for FHIR interactions on FHIR repository
    * @param fhirRequest FHIRRequest object indicating the request and response
    * @param authContext Authentication Context
    * @param authzContext Authorization Context
    * @param statusCode Resulting HTTP StatusCode
    * @return AuditEvent in JSON4s format
    */
  def createAuditResource(fhirRequest: FHIRRequest,
                          authContext: AuthContext,
                          authzContext: Option[AuthzContext],
                          statusCode: HttpStatus,
                          batchTransactionId:Option[String] = None):Resource = {

    //Resolve agents
    val agentsInfo = extractAgentInfoFromAuthzContext(authContext, authzContext)
    val userAgent = createUserAgent(agentsInfo)
    val clientAgent = createClientAgent(agentsInfo)
    val anonymousAgent = createAnonymousAgent(agentsInfo)
    val receiverAgent =createReceiverAgent(agentsInfo)
    //Resolve entities
    val relatedResourceEntities = createRelatedResourceEntitities(fhirRequest)
    val relatedPatientEntities = createRelatedPatientEntitities(fhirRequest, authzContext)
    val allEntities =
      if(batchTransactionId.isDefined)
        relatedResourceEntities ++ relatedPatientEntities :+ createRelatedBatchTransactionEntity(batchTransactionId.get)
      else
        relatedResourceEntities ++ relatedPatientEntities

    //Construct audit record
    var auditRecord = createBaseAuditEventRecord() ~
      ("type" ->  createCodingElement("http://hl7.org/fhir/audit-event-type","rest")) ~
      ("subtype" -> createCodingElement( "http://hl7.org/fhir/restful-interaction", fhirRequest.interaction)) ~
      ("action" -> resolveAuditEventActionCode(fhirRequest)) ~
      ("recorded" -> DateTimeUtil.serializeInstant(Instant.now())) ~
      ("outcome" -> resolveAuditEventOutcomeCode(statusCode)) ~
      ("outcomeDesc" -> resolveAuditEventOutcomeDescription(statusCode)) ~
      ("agent" -> Seq(Some(receiverAgent), anonymousAgent, userAgent, clientAgent).flatten) ~
      ("source" -> ("site" -> OnfhirConfig.fhirEndpointSettings.rootUrl))

    if(allEntities.nonEmpty)
      auditRecord ~ ("entity" -> allEntities)

    auditRecord
  }

  private def createUserAgent(agentsInfo:AgentsInfo): Option[JObject] = {
    agentsInfo.userId.map(uid => {
      var temp =
        ("userId" -> createIdentifierElement(OnfhirConfig.authzConfig.authzServerMetadata.issuer, uid)) ~
          ("requestor" -> true) ~
          ("network" ->
            ("address" -> agentsInfo.networkAddress) ~
              ("type" -> "2")
            )

      if(agentsInfo.userName.isDefined)
        temp = temp ~ ("name" -> agentsInfo.userName.get)

      if(agentsInfo.refToIdentityResource.isDefined)
        temp = temp ~ ("reference" -> ("reference" -> agentsInfo.refToIdentityResource.get))

      if(agentsInfo.roles.nonEmpty)
        temp = temp ~ ("role" -> agentsInfo.roles.map {
          case (None, code) => JObject("text" -> JString(code))
          case (Some(system), code) => createCodingElement(system, code)
        })

      temp
    })
  }

  private def createClientAgent(agentsInfo:AgentsInfo): Option[JObject] = {
    agentsInfo.clientId.map(cid => {
      var temp =
        ("userId" -> createIdentifierElement(OnfhirConfig.authzConfig.authzServerMetadata.issuer, cid)) ~
          ("requestor" -> agentsInfo.userId.isEmpty) ~
          ("role" -> Seq(createCodingElement("http://nema.org/dicom/dicm", "110153")))

      if(agentsInfo.clientName.isDefined)
        temp = temp ~ ("name" -> agentsInfo.clientName.get)

      if(agentsInfo.userId.isEmpty)
        temp = temp ~ ("network" -> ("address" -> agentsInfo.networkAddress) ~ ("type" -> "2"))

      temp
    })
  }

  private def createAnonymousAgent(agentsInfo:AgentsInfo):Option[JObject] = {
    if(agentsInfo.userId.isEmpty && agentsInfo.clientId.isEmpty)
      Some(
        ("requestor" -> agentsInfo.userId.isEmpty) ~
          ("network" -> ("address" -> agentsInfo.networkAddress) ~ ("type" -> "2")) ~
          ("role" -> Seq(createCodingElement("http://nema.org/dicom/dicm", "110153")))
      )
    else None
  }

  private def createReceiverAgent(agentsInfo:AgentsInfo):JObject = {
    ("name" -> OnfhirConfig.serverName) ~
      ("requestor" -> false) ~
      ("network" -> ("address" -> OnfhirConfig.fhirEndpointSettings.rootUrl) ~ ("type" -> "2")) ~
      ("role" -> Seq(createCodingElement("http://nema.org/dicom/dicm", "110152")))
  }


  private def createQueryEntity(fhirRequest:FHIRRequest):Option[JObject] = {
    resolveQueryPart(fhirRequest).map(query =>
      ("query" -> query) ~
        ("type" ->
          fhirRequest.resourceType
            .map(rt =>  createCodingElement("http://hl7.org/fhir/resource-types", rt))
            .getOrElse(createCodingElement("http://hl7.org/fhir/audit-entity-type", "2"))) ~
        ("role" -> createCodingElement("http://hl7.org/fhir/object-role", "24"))
    )
  }

  private def createRelatedResourceEntitities(fhirRequest:FHIRRequest):Seq[JObject] ={
    extractRelatedResources(fhirRequest).map(rref => {
      ("reference" -> ("reference" -> rref)) ~
        ("type" ->
          fhirRequest.resourceType
            .map(rt =>  createCodingElement("http://hl7.org/fhir/resource-types", rt))
            .getOrElse(createCodingElement("http://hl7.org/fhir/audit-entity-type", "2"))) ~
        ("role" -> createCodingElement("http://hl7.org/fhir/object-role", "4"))
    })
  }

  private def createRelatedPatientEntitities(fhirRequest:FHIRRequest, authzContext: Option[AuthzContext]):Seq[JObject] = {
    extractRelatedPatientReferences(fhirRequest, authzContext).map(rref =>
      ("reference" -> ("reference" -> rref)) ~
        ("type" -> createCodingElement("http://hl7.org/fhir/audit-entity-type", "1")) ~
        ("role" -> createCodingElement("http://hl7.org/fhir/object-role", "1"))
    )
  }

  private def createRelatedBatchTransactionEntity(batchTransactionId:String):JObject = {
    ("reference" -> ("reference" -> ("Bundle/"+batchTransactionId) )) ~
      ("type" -> createCodingElement("http://hl7.org/fhir/audit-entity-type", "4")) ~
      ("role" -> createCodingElement("http://hl7.org/fhir/object-role", "21"))
  }
}
