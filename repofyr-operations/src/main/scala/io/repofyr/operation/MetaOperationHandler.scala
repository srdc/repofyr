package io.repofyr.operation

import io.repofyr.api.model.AkkaHttpModelAdapter._

import akka.http.scaladsl.model.StatusCodes
import io.onfhir.api._
import io.onfhir.api.model.{FHIROperationRequest, FHIROperationResponse, FHIRResponse, FHIRSimpleOperationParam, OutcomeIssue}
import io.repofyr.api.service.FHIROperationHandlerService
import io.repofyr.config.IFhirConfigurationManager
import io.onfhir.config.FhirServerConfig
import io.repofyr.db.ResourceManager
import io.repofyr.exception._
import io.onfhir.exception._
import io.onfhir.util.JsonFormatter.formats
import org.json4s.JsonAST.{JArray, JObject, JValue}
import org.json4s.JsonDSL._
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.Future

/**
  * Created by tuncay on 10/4/2017.
  * Handles the FHIR Operations;
  *   - $meta-add
  *   - $meta-delete
  *   - $meta
  */
class MetaOperationHandler(fhirConfigurationManager:IFhirConfigurationManager)  extends FHIROperationHandlerService(fhirConfigurationManager) {
  private val logger: Logger = LoggerFactory.getLogger("MetaOperationHandler")
  /**
    * Execute the operation and prepare the output parameters for the operation
    * @param operationRequest Operation Request including the parameters
    * @param resourceType     The resource type that operation is called if exists
    * @param resourceId       The resource id that operation is called if exists
    * @return The response containing the Http status code and the output parameters
    */
  override def executeOperation(operationName:String, operationRequest: FHIROperationRequest, resourceType: Option[String], resourceId: Option[String]): Future[FHIROperationResponse] = {
     operationName match {
       //See https://www.hl7.org/fhir/resource-operations.html#meta-add
       case "meta-add" => handleMetaAdd(operationRequest, resourceType.get, resourceId.get)
       //See https://www.hl7.org/fhir/resource-operations.html#meta-delete
       case "meta-delete" => handleMetaDelete(operationRequest, resourceType.get, resourceId.get)
       //See https://www.hl7.org/fhir/resource-operations.html#meta
       case "meta" if (resourceType.isDefined && resourceId.isDefined) => handleMeta(resourceType.get, resourceId.get)
       case "meta" => throw new InternalServerException(s"System or type level operation ${operationName} not supported yet by the MetaOperationHandler!!!")
       case _ => throw new InternalServerException(s"Operation ${operationName} not supported by the MetaOperationHandler!!!")
     }
   }

  /**
   * Handle instance level meta
   * @param resourceType
   * @param resourceId
   * @return
   */
  def handleMeta(resourceType:String, resourceId:String):Future[FHIROperationResponse] = {
    fhirConfigurationManager.resourceManager
      .getResource(resourceType, resourceId, includingOrExcludingFields = Some(true -> Set("meta")), excludeExtraFields = true)
      .map {
        case None =>
          logger.debug("resource not found, return 404 NotFound...")
          throw new NotFoundException(Seq(
            OutcomeIssue(
              FHIRResponse.SEVERITY_CODES.INFORMATION,
              FHIRResponse.OUTCOME_CODES.INFORMATIONAL,
              None,
              Some(s"Resource with type (${resourceType}), id (${resourceId}) not found..."),
              Nil
            )
          ))
        case Some(resource) =>
          val response = new FHIROperationResponse(StatusCodes.OK)
          response.setResponse((resource \ FHIR_COMMON_FIELDS.META).asInstanceOf[JObject])
          response
      }
  }

  /**
    * Handles $meta-add operation
    * @param operationRequest Operation Request
    * @param resourceType The resource type
    * @param resourceId The resource id
    * @return
    */
   def handleMetaAdd(operationRequest: FHIROperationRequest, resourceType:String, resourceId:String):Future[FHIROperationResponse] = {
     //Get the input parameter "meta:Meta"
     val newMeta = operationRequest.getParam("meta").get.asInstanceOf[FHIRSimpleOperationParam].value

     fhirConfigurationManager.resourceManager.getResource(resourceType, resourceId, excludeExtraFields = true).flatMap {
       case None =>
         logger.debug("resource not found, return 404 NotFound...")
         throw new NotFoundException(Seq(
           OutcomeIssue(
             FHIRResponse.SEVERITY_CODES.INFORMATION,
             FHIRResponse.OUTCOME_CODES.INFORMATIONAL,
             None,
             Some(s"Resource with type (${resourceType}), id (${resourceId}) not found..."),
             Nil
           )
         ))
       case Some(resource) =>
         //Update the resource with meta
         val updatedResource = resource merge (JObject() ~ (FHIR_COMMON_FIELDS.META -> newMeta.removeField(f => f._1 == FHIR_COMMON_FIELDS.VERSION_ID || f._1 == FHIR_COMMON_FIELDS.LAST_UPDATED)))

         fhirConfigurationManager.resourceManager.replaceResource(resourceType, resourceId, updatedResource).map(isUpdated => {
           if(!isUpdated) throw new InternalServerException("Problem in updating Meta in meta-add operation!!!")
           //Create the response, set the return result
           val response = new FHIROperationResponse(StatusCodes.OK)
           response.setResponse((updatedResource \ FHIR_COMMON_FIELDS.META).asInstanceOf[JObject])
           response
         })
       }
   }


  /**
    * Handles $meta-delete operation
    * @param operationRequest Operation Request
    * @param resourceType The resource type
    * @param resourceId The resource id
    * @return
    */
   def handleMetaDelete(operationRequest: FHIROperationRequest, resourceType:String, resourceId:String):Future[FHIROperationResponse] = {
     //Get the input parameter "meta:Meta"
     val metaToBeDeleted = operationRequest.getParam("meta").get.asInstanceOf[FHIRSimpleOperationParam].value

     // excludeExtraFields, as $meta-add does: without it the internal Mongo _id travels with
     // the resource and replaceResource is rejected for altering an immutable field.
     fhirConfigurationManager.resourceManager.getResource(resourceType, resourceId, excludeExtraFields = true).flatMap {
       case None =>
         logger.debug("resource not found, return 404 NotFound...")
         throw new NotFoundException(Seq(
           OutcomeIssue(
             FHIRResponse.SEVERITY_CODES.INFORMATION,
             FHIRResponse.OUTCOME_CODES.INFORMATIONAL,
             None,
             Some(s"Resource with type (${resourceType}), id (${resourceId}) not found..."),
             Nil
           )
         ))
       case Some(resource) =>
         // What to remove is exactly what the client submitted. This used to be derived from
         // `storedMeta diff submittedMeta`, whose `deleted` component is the entries present in
         // the resource and ABSENT from the request - the complement of the intent. When the
         // request named exactly the tag to remove, which is the normal case, that set was empty
         // and the operation silently removed nothing.
         val requestedMeta = metaToBeDeleted.removeField(f =>
           f._1 == FHIR_COMMON_FIELDS.VERSION_ID || f._1 == FHIR_COMMON_FIELDS.LAST_UPDATED)

         // An absent category is JNothing rather than an empty array, so match instead of casting.
         def requestedCodings(field:String):Seq[String] =
           requestedMeta \ field match {
             case codings:JArray => codings.arr.map(processCoding)
             case _ => Nil
           }

         val profilesToBeDeleted:Seq[String] =
           (requestedMeta \ FHIR_COMMON_FIELDS.PROFILE).extractOpt[Seq[String]].getOrElse(Nil)
         val securityTagsToBeDeleted:Seq[String] = requestedCodings(FHIR_COMMON_FIELDS.SECURITY)
         val tagsToBeDeleted:Seq[String] = requestedCodings(FHIR_COMMON_FIELDS.TAG)
         //Update the resource delete the meta fields
         val updatedResource = resource.transformField {
           case (FHIR_COMMON_FIELDS.META, meta) =>
             FHIR_COMMON_FIELDS.META -> meta.transformField {
               case (FHIR_COMMON_FIELDS.PROFILE, profiles:JArray) => (FHIR_COMMON_FIELDS.PROFILE -> profiles.remove(p => profilesToBeDeleted.contains(p.extract[String])))
               // JValue.remove walks the whole subtree, so the predicate also sees the array node
               // itself, not only its elements - casting it to JObject threw. processCoding takes
               // a JValue and reads through safe field access, so no cast is needed.
               case (FHIR_COMMON_FIELDS.SECURITY, securityTags:JArray) =>  (FHIR_COMMON_FIELDS.SECURITY -> securityTags.remove(st => securityTagsToBeDeleted.contains(processCoding(st))))
               case (FHIR_COMMON_FIELDS.TAG, tags:JArray) =>  (FHIR_COMMON_FIELDS.TAG -> tags.remove(t => tagsToBeDeleted.contains(processCoding(t))))
             }
         }.asInstanceOf[JObject]
         //Replace the resource
         fhirConfigurationManager.resourceManager.replaceResource(resourceType, resourceId, updatedResource).map(isUpdated => {
           if(!isUpdated) throw new InternalServerException("Problem in updating Meta in meta-add operation!!!")
           //Create the response, set the return result
           val response = new FHIROperationResponse(StatusCodes.OK)
           response.setResponse((updatedResource \ FHIR_COMMON_FIELDS.META).asInstanceOf[JObject])
           response
         })
     }
   }

  /**
    * Create a key for the Coding to understand if two Codings are same
    * @param coding
    * @return
    */
   private def processCoding(coding:JValue):String = {
     val key =
       (coding \ FHIR_COMMON_FIELDS.SYSTEM).extractOpt[String].getOrElse("") + ":" +
         (coding \ "version").extractOpt[String].getOrElse("") +":" +
         (coding \ FHIR_COMMON_FIELDS.CODE).extractOpt[String].getOrElse("")

     key
   }
}
