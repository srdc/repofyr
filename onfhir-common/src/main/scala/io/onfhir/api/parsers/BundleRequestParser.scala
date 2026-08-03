package io.onfhir.api.parsers

import io.onfhir.api.{FHIR_BUNDLE_FIELDS, FHIR_HTTP_OPTIONS, FHIR_INTERACTIONS, FHIR_METHOD_NAMES}
import io.onfhir.api.Resource
import io.onfhir.api.model.{EntityTagCondition, FHIRRequest, FHIRResponse, HttpMethod, HttpStatus, OrderedQuery, OutcomeIssue}
import io.onfhir.api.util.FHIRUtil
import io.onfhir.config.FhirEndpointSettings
import io.onfhir.util.DateTimeUtil
import io.onfhir.util.JsonFormatter._
import org.json4s.JsonAST.{JArray, JObject, JValue}

import java.net.URI

import scala.util.Try

/**
  * Created by ozan on 23.02.2017.
  * Bundle related supplementary methods
  */
object BundleRequestParser {
  /**
    * Parse the Bundle for batch or transaction and convert them to child FHIRRequest
    * @param bundle                     FHIR Transaction or Batch bundle
    * @param endpointSettings           FHIR endpoint used to resolve absolute entry URLs
    * @param prefer                     General Prefer header
    * @param skipEntriesWithoutRequest  If true, entries without a request are skipped
    * @return
    */
  def parseBundleRequest(bundle:Resource, endpointSettings: FhirEndpointSettings, prefer:Option[String] = None, skipEntriesWithoutRequest:Boolean = false):Seq[FHIRRequest] = {
    try {
      //Get the entries
      (bundle \ FHIR_BUNDLE_FIELDS.ENTRY)
        .extractOpt[JArray]
        .map(_.arr.toSeq).getOrElse(List.empty)
        .filter(entry => !skipEntriesWithoutRequest || entry.asInstanceOf[JObject].obj.exists(_._1 == FHIR_BUNDLE_FIELDS.REQUEST))
        .map(entry => {
          Try(
            parseBundleRequestEntry(entry.asInstanceOf[JObject], endpointSettings)
              .copy(prefer = prefer)
          ).recover {
            case e: BundleRequestParsingException =>
              val requestUrl = (entry \ FHIR_BUNDLE_FIELDS.REQUEST \ FHIR_BUNDLE_FIELDS.URL).extract[String]
              val request = FHIRRequest(interaction = FHIR_INTERACTIONS.UNKNOWN, requestUri = requestUrl)
              //Set the response
              request.setResponse(FHIRResponse.errorResponse(HttpStatus.BadRequest, e.outcomeIssues))
              request
          }.get
        })
    } catch {
      case e: BundleRequestParsingException => throw e
      case e:Exception =>
        throw new BundleRequestParsingException(Seq(
          OutcomeIssue(
            FHIRResponse.SEVERITY_CODES.ERROR, //fatal
            FHIRResponse.OUTCOME_CODES.INVALID,
            None,
            Some(s"Invalid bundle request, please check the sytax of FHIR Bundle"),
            Nil
          )), e)
    }
  }

  /**
   * Parse a document bundle to create child requests for creating document entries as a transaction
   * @param bundle    Document bundle content
   * @param prefer
   * @return
   */
  def parseBundleDocumentRequest(bundle:Resource, prefer:Option[String] = None):Seq[FHIRRequest] = {
    //Get the entries
    (bundle \ FHIR_BUNDLE_FIELDS.ENTRY)
      .extractOpt[JArray]
      .map(_.arr.toSeq).getOrElse(List.empty)
      .map(entry => {
        parseBundleDocumentRequestEntry(entry.asInstanceOf[JObject], prefer)
      })
  }

  /**
   * Parse an entry in a document bundle
   * @param entry
   * @return
   */
  def parseBundleDocumentRequestEntry(entry:Resource, prefer:Option[String]):FHIRRequest = {
    //Parse the entry
    val fullUrl = (entry \  FHIR_BUNDLE_FIELDS.FULL_URL).extractOpt[String].filter(_.startsWith("urn:uuid:"))
    //Get the resource
    val resource = (entry \ FHIR_BUNDLE_FIELDS.RESOURCE).extractOpt[JObject].getOrElse(JObject())
    //Get resource type and resource id
    val resourceType = FHIRUtil.extractValue[String](resource, "resourceType")
    val resourceIdOpt = FHIRUtil.extractValueOption[String](resource, "id")
    resourceIdOpt match {
      case None =>
        val fhirRequest = FHIRRequest(interaction = FHIR_INTERACTIONS.CREATE, requestUri = "/" + resourceType)
        fhirRequest.initializeCreateRequest(resourceType, None, None)
        fhirRequest.resource = Some(resource)
        fhirRequest.prefer = prefer
        fhirRequest.setId(fullUrl)
      case Some(rid) =>
        val fhirRequest = FHIRRequest(interaction = FHIR_INTERACTIONS.UPDATE, requestUri = "/" + resourceType + "/" + rid)
        fhirRequest.initializeUpdateRequest(resourceType, Some(rid), None, None)
        fhirRequest.resource = Some(resource)
        fhirRequest.prefer = prefer
        fhirRequest.setId(fullUrl)
    }
  }

  /**
    * Parse an entry in Bundle for transaction or batch request and convert it to FHIRRequest
    * @param entry
    * @param endpointSettings FHIR endpoint used to resolve absolute entry URLs
    * @return
    */
  def parseBundleRequestEntry(entry:Resource, endpointSettings: FhirEndpointSettings):FHIRRequest = {
    //Parse the entry
    val fullUrl = (entry \  FHIR_BUNDLE_FIELDS.FULL_URL).extractOpt[String].filter(_.startsWith("urn:uuid:"))

    val requestMethod = (entry \ FHIR_BUNDLE_FIELDS.REQUEST \ FHIR_BUNDLE_FIELDS.METHOD).extract[String]
    val requestUrl = (entry \ FHIR_BUNDLE_FIELDS.REQUEST \ FHIR_BUNDLE_FIELDS.URL).extract[String]
    val resource = (entry \ FHIR_BUNDLE_FIELDS.RESOURCE).extractOpt[JObject].getOrElse(JObject())
    //Headers
    val ifMatch =
      (entry \ FHIR_BUNDLE_FIELDS.REQUEST \ FHIR_HTTP_OPTIONS.rIF_MATCH)
        .extractOpt[String]
        .map(EntityTagCondition.parse)
    val ifNoneExist = (entry \ FHIR_BUNDLE_FIELDS.REQUEST \ FHIR_HTTP_OPTIONS.rIF_NONE_EXIST).extractOpt[String]
    val ifNoneMatch =
      (entry \ FHIR_BUNDLE_FIELDS.REQUEST \ FHIR_HTTP_OPTIONS.rIF_NONE_MATCH)
        .extractOpt[String]
        .map(EntityTagCondition.parse)
    val ifModifiedSince =
      (entry \ FHIR_BUNDLE_FIELDS.REQUEST \ FHIR_HTTP_OPTIONS.rIF_MODIFIED_SINCE)
        .extractOpt[String]
        .flatMap(h =>
          DateTimeUtil
            .parseInstant(h)
            .map(identity)
        )

    // Bundle entry request URLs commonly contain unescaped FHIR token
    // separators such as '|'. Parse the query independently so the neutral
    // java.net.URI path model does not reject a valid FHIR request target.
    val querySeparator = requestUrl.indexOf('?')
    val requestPath = if (querySeparator < 0) requestUrl else requestUrl.substring(0, querySeparator)
    val requestQuery =
      if (querySeparator < 0) OrderedQuery.empty
      else OrderedQuery.parse(requestUrl.substring(querySeparator + 1))
    val sprayUrl = URI.create(requestPath)

    val fhirRequest = new FHIRRequest(interaction = FHIR_INTERACTIONS.UNKNOWN, requestUri = requestUrl)

    requestMethod match {
      //Delete Interaction
      case FHIR_METHOD_NAMES.METHOD_DELETE => {
        parseUrl(sprayUrl, endpointSettings) match {
          case Seq(rtype, rid) =>
            fhirRequest.initializeDeleteRequest(rtype, Some(rid))
          case Seq(rtype) =>
            fhirRequest.initializeDeleteRequest(rtype, None)
            fhirRequest.queryParams = requestQuery.toMultiMap
          case _ => throw new BundleRequestParsingException(invalidOperation(FHIR_INTERACTIONS.DELETE, requestUrl))
        }
      }
      //Update Interaction
      case FHIR_METHOD_NAMES.METHOD_PUT => {
        parseUrl(sprayUrl, endpointSettings) match {
          case Seq(rtype, rid) =>
            fhirRequest.initializeUpdateRequest(rtype, Some(rid), ifMatch, None)
            fhirRequest.resource = Some(resource)
            fhirRequest.setId(fullUrl)
          case Seq(rtype) =>
            fhirRequest.initializeUpdateRequest(rtype, None, ifMatch, None)
            fhirRequest.queryParams = requestQuery.toMultiMap
            fhirRequest.resource = Some(resource)
            fhirRequest.setId(fullUrl)
          case _ => throw new BundleRequestParsingException(invalidOperation(FHIR_INTERACTIONS.UPDATE, requestUrl))
        }
      }
      case FHIR_METHOD_NAMES.METHOD_PATCH =>
        parseUrl(sprayUrl, endpointSettings) match {
          case Seq(rtype, rid) =>
            fhirRequest.initializePatchRequest(rtype, Some(rid), ifMatch, None)
            fhirRequest.resource = Some(resource)
            fhirRequest.setId(fullUrl)
          case Seq(rtype) =>
            fhirRequest.initializePatchRequest(rtype, None, ifMatch, None)
            fhirRequest.queryParams = requestQuery.toMultiMap
            fhirRequest.resource = Some(resource)
            fhirRequest.setId(fullUrl)
          case _ => throw new BundleRequestParsingException(invalidOperation(FHIR_INTERACTIONS.PATCH, requestUrl))
        }
      case  FHIR_METHOD_NAMES.METHOD_POST => {
        parseUrl(sprayUrl, endpointSettings) match {
          //System level search
          case Seq(FHIR_HTTP_OPTIONS.SEARCH) =>
            fhirRequest.initializeSearchRequest(None)
          //Search with post
          case Seq(rtype, FHIR_HTTP_OPTIONS.SEARCH) =>
            fhirRequest.initializeSearchRequest(rtype, None)
            fhirRequest.queryParams = requestQuery.toMultiMap
          //Compartment search with post
          case Seq(ctype, cid, rtype, FHIR_HTTP_OPTIONS.SEARCH) =>
            fhirRequest.initializeCompartmentSearchRequest(ctype, cid, rtype, None)
            fhirRequest.queryParams = requestQuery.toMultiMap
          //Create interaction
          case Seq(rtype) =>
            fhirRequest.initializeCreateRequest(rtype, ifNoneExist, None)
            fhirRequest.resource = Some(resource)
            fhirRequest.setId(fullUrl)
          //Type and instance level Operations
          case Seq(rtype, operation) if operation.startsWith("$") =>
            fhirRequest.httpMethod = Some(HttpMethod.POST)
            fhirRequest.initializeOperationRequest(operation, Some(rtype))
            fhirRequest.queryParams = requestQuery.toMultiMap
            fhirRequest.resource = Some(resource)
            fhirRequest.setId(fullUrl)
          case Seq(rtype, rid, operation) if  operation.startsWith("$")=>
            fhirRequest.httpMethod = Some(HttpMethod.POST)
            fhirRequest.initializeOperationRequest(operation, Some(rtype), Some(rid))
            fhirRequest.queryParams = requestQuery.toMultiMap
            fhirRequest.resource = Some(resource)
            fhirRequest.setId(fullUrl)
          case _ =>
            throw new BundleRequestParsingException(invalidOperation("Create or Search or Operation", requestUrl))
        }
      }
      //ORDER IS IMPORTANT
      case FHIR_METHOD_NAMES.METHOD_GET => {
        parseUrl(sprayUrl, endpointSettings) match {
          case Nil =>
            fhirRequest.initializeSearchRequest(None)
          case Seq("metadata") =>
            fhirRequest.initializeCapabilitiesRequest()
          //Search with Get
          case Seq(rtype)=>
            fhirRequest.initializeSearchRequest(rtype, None)
            fhirRequest.queryParams = requestQuery.toMultiMap
          //History interaction
          case Seq(rtype, FHIR_HTTP_OPTIONS.HISTORY) =>
            fhirRequest.initializeHistoryRequest(FHIR_INTERACTIONS.HISTORY_TYPE, Some(rtype), None)
            fhirRequest.queryParams = requestQuery.toMultiMap
          //Type level operation
          case Seq(rtype, operation) if operation.startsWith("$") =>
            fhirRequest.httpMethod = Some(HttpMethod.GET)
            fhirRequest.initializeOperationRequest(operation, Some(rtype))
            fhirRequest.queryParams = requestQuery.toMultiMap
            fhirRequest.setId(fullUrl)
          //Instance level operation
          case Seq(rtype, rid, operation) if  operation.startsWith("$")=>
            fhirRequest.httpMethod = Some(HttpMethod.GET)
            fhirRequest.initializeOperationRequest(operation, Some(rtype), Some(rid))
            fhirRequest.queryParams = requestQuery.toMultiMap
            fhirRequest.setId(fullUrl)
          //Read interaction
          case Seq(rtype, rid) =>
            fhirRequest.initializeReadRequest(rtype, rid, ifModifiedSince, ifNoneMatch, None, None)
            fhirRequest.queryParams = requestQuery.toMultiMap
          case Seq(rtype, rid, FHIR_HTTP_OPTIONS.HISTORY) =>
            fhirRequest.initializeHistoryRequest( FHIR_INTERACTIONS.HISTORY_INSTANCE, Some(rtype), Some(rid))
            fhirRequest.queryParams = requestQuery.toMultiMap
          //Compartment search with get
          case Seq(ctype, cid, rtype) =>
            fhirRequest.initializeCompartmentSearchRequest(ctype, cid, rtype, None)
            fhirRequest.queryParams = requestQuery.toMultiMap
          //VRead interaction
          case Seq(rtype, rid, FHIR_HTTP_OPTIONS.HISTORY, vid) =>
            fhirRequest.initializeVReadRequest(rtype,rid, vid)

          case _ =>
            throw new BundleRequestParsingException(invalidOperation("Invalid HTTP Get", requestUrl))
        }
      }
    }
    //Return the request
    fhirRequest
  }

  /**
    * Return Outcome issues for invalid interaction
    * @param interaction
    * @param path
    * @return
    */
  def invalidOperation(interaction:String, path:String):Seq[OutcomeIssue] = {
    Seq(
      OutcomeIssue(
        FHIRResponse.SEVERITY_CODES.ERROR, //fatal
        FHIRResponse.OUTCOME_CODES.INVALID,
        None,
        Some(s"Invalid type of path for ${interaction} interaction; path: '$path'"),
        Nil
      )
    )
  }

  /**
    * Parse the url and return Seq of segments
    * @param sprayUrl
    * @param endpointSettings FHIR endpoint used to identify local absolute URLs
    * @return
    */
  def parseUrl(sprayUrl:URI, endpointSettings: FhirEndpointSettings):Seq[String] = {
    sprayUrl
      .getPath
      .split(endpointSettings.rootUrl)
      .last
      .split("/")
      .filterNot(_.equals(""))
      .toIndexedSeq
  }

}
