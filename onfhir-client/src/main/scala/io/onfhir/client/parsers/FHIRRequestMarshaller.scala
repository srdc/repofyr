package io.onfhir.client.parsers

import io.onfhir.api.client.FhirClientException
import io.onfhir.api.model._
import io.onfhir.api.{FHIR_CONTENT_TYPES, FHIR_INTERACTIONS, Resource}
import io.onfhir.client.model.{ClientHttpEntity, ClientHttpRequest}
import io.onfhir.util.DateTimeUtil
import io.onfhir.util.JsonFormatter._
import org.json4s.JsonAST.{JArray, JObject, JString}
import org.json4s.JsonDSL._
import org.json4s.jackson.Serialization

import java.net.URI
import java.nio.charset.StandardCharsets

object FHIRRequestMarshaller {
  private val formContentType = FhirContentType(FhirMediaType.application("x-www-form-urlencoded"), Some("UTF-8"))
  private val pathSegmentCharacters: Set[Char] =
    ("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789" +
      "-._~!$&'()*+,;=:@").toSet

  def marshallRequest(fhirRequest: FHIRRequest, fhirServerBaseUrl: String): ClientHttpRequest = {
    val baseUri = validateBaseUri(fhirServerBaseUrl)
    val method = getHttpMethod(fhirRequest)
    ClientHttpRequest(
      method = method,
      uri = getRequestUri(fhirRequest, baseUri, method),
      headers = getHeaders(fhirRequest),
      entity = getEntity(fhirRequest, method, baseUri)
    )
  }

  private def getEntity(
    fhirRequest: FHIRRequest,
    method: HttpMethod,
    fhirServerBaseUrl: URI): Option[ClientHttpEntity] = {
    val contentType = fhirRequest.contentType.getOrElse(FHIR_CONTENT_TYPES.FHIR_JSON_CONTENT_TYPE)
    fhirRequest.interaction match {
      case FHIR_INTERACTIONS.CREATE | FHIR_INTERACTIONS.UPDATE | FHIR_INTERACTIONS.PATCH =>
        Some(getEntityContent(fhirRequest.resource.getOrElse(JObject()), contentType))
      case FHIR_INTERACTIONS.BATCH | FHIR_INTERACTIONS.TRANSACTION =>
        val body = JObject(
          "resourceType" -> JString("Bundle"),
          "type" -> JString(fhirRequest.interaction),
          "entry" -> JArray(fhirRequest.childRequests.map(createBundleRequestEntry(_, fhirServerBaseUrl)).toList)
        )
        Some(getEntityContent(body, contentType))
      case FHIR_INTERACTIONS.SEARCH if method == HttpMethod.POST =>
        getQuery(fhirRequest.queryParams).map(query => ClientHttpEntity.utf8(formContentType, query.render))
      case operation if operation.startsWith("$") =>
        fhirRequest.resource.map(resource => getEntityContent(resource, contentType))
      case _ => None
    }
  }

  private def getEntityContent(body: Resource, contentType: FhirContentType): ClientHttpEntity = {
    val mediaType = contentType.mediaType
    if (mediaType == FHIR_CONTENT_TYPES.FHIR_JSON_CONTENT_TYPE.mediaType)
      ClientHttpEntity.utf8(contentType, body.toJson)
    else if (mediaType == FHIR_CONTENT_TYPES.FHIR_JSON_PATCH_CONTENT_TYPE.mediaType)
      ClientHttpEntity.utf8(contentType, Serialization.write(body \ "patches"))
    else if (
      mediaType == FHIR_CONTENT_TYPES.FHIR_XML_CONTENT_TYPE.mediaType ||
        mediaType == FHIR_CONTENT_TYPES.FHIR_XML_PATCH_CONTENT_TYPE.mediaType)
      throw FhirClientException("XML content types are not supported in OnFhirClient")
    else
      throw FhirClientException(s"Content type $contentType is not supported in OnFhirClient")
  }

  private def createBundleRequestEntry(childRequest: FHIRRequest, fhirServerBaseUrl: URI): JObject = {
    val fullUrlField = if (!childRequest.isIdGenerated) Some("fullUrl" -> JString(childRequest.id)) else None
    val resourceField = childRequest.resource.map(resource => "resource" -> resource)
    val method = getHttpMethod(childRequest)
    val absoluteUri = getRequestUri(childRequest, fhirServerBaseUrl, method).toString
    val basePrefix = fhirServerBaseUrl.toString.stripSuffix("/") + "/"
    var request = JObject(
      "method" -> JString(method.value),
      "url" -> JString(absoluteUri.stripPrefix(basePrefix))
    )
    childRequest.ifMatch.foreach(value => request = request ~ ("ifMatch" -> JString(value.render)))
    childRequest.ifModifiedSince.foreach(value => request = request ~ ("ifModifiedSince" -> JString(DateTimeUtil.serializeDateTime(value))))
    childRequest.ifNoneMatch.foreach(value => request = request ~ ("ifNoneMatch" -> JString(value.render)))
    childRequest.ifNoneExist.foreach(value => request = request ~ ("ifNoneExist" -> JString(value)))
    JObject((fullUrlField.toSeq ++ resourceField.toSeq ++ Seq("request" -> request)).toList)
  }

  private def getHttpMethod(fhirRequest: FHIRRequest): HttpMethod = fhirRequest.interaction match {
    case FHIR_INTERACTIONS.CREATE | FHIR_INTERACTIONS.TRANSACTION | FHIR_INTERACTIONS.BATCH => HttpMethod.POST
    case FHIR_INTERACTIONS.UPDATE => HttpMethod.PUT
    case FHIR_INTERACTIONS.PATCH => HttpMethod.PATCH
    case FHIR_INTERACTIONS.DELETE => HttpMethod.DELETE
    case FHIR_INTERACTIONS.READ | FHIR_INTERACTIONS.VREAD |
         FHIR_INTERACTIONS.HISTORY_INSTANCE | FHIR_INTERACTIONS.HISTORY_TYPE |
         FHIR_INTERACTIONS.HISTORY_SYSTEM | FHIR_INTERACTIONS.SEARCH |
         FHIR_INTERACTIONS.CAPABILITIES | FHIR_INTERACTIONS.GET_SEARCH_PAGE =>
      fhirRequest.httpMethod.getOrElse(HttpMethod.GET)
    case operation if operation.startsWith("$") => fhirRequest.httpMethod.getOrElse(HttpMethod.POST)
    case other => throw FhirClientException(s"Invalid FHIR interaction $other")
  }

  private def getQuery(queryParams: Map[String, List[String]]): Option[OrderedQuery] =
    if (queryParams.nonEmpty) Some(OrderedQuery.fromMultiMap(queryParams)) else None

  private def getRequestUri(fhirRequest: FHIRRequest, baseUri: URI, method: HttpMethod): URI = {
    val basePath = Option(baseUri.getRawPath).getOrElse("").stripSuffix("/")
    val (rawPath, rawQuery) = fhirRequest.interaction match {
      case FHIR_INTERACTIONS.TRANSACTION | FHIR_INTERACTIONS.BATCH => basePath -> None
      case FHIR_INTERACTIONS.CREATE => appendSegments(basePath, fhirRequest.resourceType.get) -> None
      case FHIR_INTERACTIONS.UPDATE | FHIR_INTERACTIONS.PATCH | FHIR_INTERACTIONS.DELETE =>
        fhirRequest.resourceId match {
          case Some(id) => appendSegments(basePath, fhirRequest.resourceType.get, id) -> None
          case None => appendSegments(basePath, fhirRequest.resourceType.get) -> getQuery(fhirRequest.queryParams).map(_.render)
        }
      case FHIR_INTERACTIONS.READ =>
        appendSegments(basePath, fhirRequest.resourceType.get, fhirRequest.resourceId.get) -> getQuery(fhirRequest.queryParams).map(_.render)
      case FHIR_INTERACTIONS.VREAD =>
        appendSegments(basePath, fhirRequest.resourceType.get, fhirRequest.resourceId.get, "_history", fhirRequest.versionId.get) -> None
      case FHIR_INTERACTIONS.HISTORY_INSTANCE =>
        appendSegments(basePath, fhirRequest.resourceType.get, fhirRequest.resourceId.get, "_history") -> getQuery(fhirRequest.queryParams).map(_.render)
      case FHIR_INTERACTIONS.HISTORY_TYPE =>
        appendSegments(basePath, fhirRequest.resourceType.get, "_history") -> getQuery(fhirRequest.queryParams).map(_.render)
      case FHIR_INTERACTIONS.HISTORY_SYSTEM => appendSegments(basePath, "_history") -> getQuery(fhirRequest.queryParams).map(_.render)
      case FHIR_INTERACTIONS.CAPABILITIES => appendSegments(basePath, "metadata") -> None
      case FHIR_INTERACTIONS.SEARCH =>
        val compartmentPath = fhirRequest.compartmentType match {
          case Some(compartmentType) => appendSegments(basePath, compartmentType, fhirRequest.compartmentId.get)
          case None => basePath
        }
        val resourcePath = fhirRequest.resourceType.fold(compartmentPath)(resourceType => appendSegments(compartmentPath, resourceType))
        val searchPath = if (method == HttpMethod.POST) appendSegments(resourcePath, "_search") else resourcePath
        searchPath -> (if (method == HttpMethod.GET) getQuery(fhirRequest.queryParams).map(_.render) else None)
      case FHIR_INTERACTIONS.GET_SEARCH_PAGE =>
        val separator = fhirRequest.requestUri.indexOf('?')
        val relativePath = if (separator < 0) fhirRequest.requestUri else fhirRequest.requestUri.substring(0, separator)
        val query = if (separator < 0) None else Some(fhirRequest.requestUri.substring(separator + 1))
        val pagePath = if (relativePath.isEmpty) basePath else basePath + "/" + relativePath.stripPrefix("/")
        pagePath -> query
      case operation if operation.startsWith("$") =>
        val resourcePath = fhirRequest.resourceType.fold(basePath)(resourceType => appendSegments(basePath, resourceType))
        val instancePath = fhirRequest.resourceId.fold(resourcePath)(resourceId => appendSegments(resourcePath, resourceId))
        appendSegments(instancePath, operation) -> getQuery(fhirRequest.queryParams).map(_.render)
    }
    buildUri(baseUri, rawPath, rawQuery)
  }

  private def getHeaders(fhirRequest: FHIRRequest): HttpHeaders = HttpHeaders(
    fhirRequest.prefer.toVector.map(HttpHeader("Prefer", _)) ++
      fhirRequest.ifNoneMatch.toVector.map(value => HttpHeader("If-None-Match", value.render)) ++
      fhirRequest.ifModifiedSince.toVector.map(value => HttpHeader("If-Modified-Since", DateTimeUtil.formatHttpDate(value))) ++
      fhirRequest.ifMatch.toVector.map(value => HttpHeader("If-Match", value.render)) ++
      fhirRequest.ifNoneExist.toVector.map(value => HttpHeader("If-None-Exist", value)) ++
      Vector(
        HttpHeader("Accept", FHIR_CONTENT_TYPES.FHIR_JSON_CONTENT_TYPE.mediaType.value),
        HttpHeader("X-Request-Id", fhirRequest.id)
      )
  )

  private def validateBaseUri(value: String): URI = {
    val uri = URI.create(value.stripSuffix("/"))
    require(uri.isAbsolute && Set("http", "https").contains(Option(uri.getScheme).map(_.toLowerCase).orNull),
      s"FHIR server base URL must be an absolute HTTP(S) URI: $value")
    require(uri.getRawQuery == null && uri.getRawFragment == null, s"FHIR server base URL cannot contain query or fragment: $value")
    uri
  }

  private def appendSegments(path: String, segments: String*): String =
    segments.foldLeft(if (path.isEmpty) "" else path.stripSuffix("/")) { (current, segment) =>
      current + "/" + encodePathSegment(segment)
    }

  private def encodePathSegment(value: String): String =
    value.getBytes(StandardCharsets.UTF_8).map { byte =>
      val character = (byte & 0xff).toChar
      if (pathSegmentCharacters.contains(character)) character.toString
      else f"${byte & 0xff}%02X".prepended('%')
    }.mkString

  private def buildUri(baseUri: URI, rawPath: String, rawQuery: Option[String]): URI = {
    val authority = baseUri.getRawAuthority
    URI.create(s"${baseUri.getScheme}://$authority${if (rawPath.startsWith("/")) rawPath else "/" + rawPath}${rawQuery.map("?" + _).getOrElse("")}")
  }
}
