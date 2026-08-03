package io.onfhir.api.model

import java.net.{URLDecoder, URLEncoder}
import java.nio.charset.StandardCharsets
import java.time.Instant
import scala.collection.immutable.ListMap

/** Transport-neutral HTTP status with family classification. */
final case class HttpStatus(code: Int) {
  require(code >= 100 && code <= 599, s"Invalid HTTP status code: $code")

  def intValue(): Int = code
  def isInformational: Boolean = code / 100 == 1
  def isSuccess(): Boolean = code / 100 == 2
  def isRedirection: Boolean = code / 100 == 3
  def isClientError: Boolean = code / 100 == 4
  def isServerError: Boolean = code / 100 == 5
  def isFailure(): Boolean = isClientError || isServerError
  def value: String = HttpStatus.reasonPhrases.get(code).fold(code.toString)(reason => s"$code $reason")
}

object HttpStatus {
  val Continue = HttpStatus(100)
  val OK = HttpStatus(200)
  val Created = HttpStatus(201)
  val Accepted = HttpStatus(202)
  val NoContent = HttpStatus(204)
  val NO_CONTENT: HttpStatus = NoContent
  val NotModified = HttpStatus(304)
  val NOT_MODIFIED: HttpStatus = NotModified
  val BadRequest = HttpStatus(400)
  val Unauthorized = HttpStatus(401)
  val Forbidden = HttpStatus(403)
  val NotFound = HttpStatus(404)
  val MethodNotAllowed = HttpStatus(405)
  val NotAcceptable = HttpStatus(406)
  val Conflict = HttpStatus(409)
  val Gone = HttpStatus(410)
  val PreconditionFailed = HttpStatus(412)
  val PRECONDITION_FAILED: HttpStatus = PreconditionFailed
  val UnsupportedMediaType = HttpStatus(415)
  val UnprocessableContent = HttpStatus(422)
  val InternalServerError = HttpStatus(500)
  val NotImplemented = HttpStatus(501)

  private val reasonPhrases = Map(
    100 -> "Continue",
    200 -> "OK",
    201 -> "Created",
    202 -> "Accepted",
    204 -> "No Content",
    304 -> "Not Modified",
    400 -> "Bad Request",
    401 -> "Unauthorized",
    403 -> "Forbidden",
    404 -> "Not Found",
    405 -> "Method Not Allowed",
    406 -> "Not Acceptable",
    409 -> "Conflict",
    410 -> "Gone",
    412 -> "Precondition Failed",
    415 -> "Unsupported Media Type",
    422 -> "Unprocessable Content",
    500 -> "Internal Server Error",
    501 -> "Not Implemented"
  )
}

/** Case-sensitive HTTP method token. */
final case class HttpMethod(value: String) {
  require(HttpSyntax.isToken(value), s"Invalid HTTP method token: $value")
}

object HttpMethod {
  val GET = HttpMethod("GET")
  val HEAD = HttpMethod("HEAD")
  val POST = HttpMethod("POST")
  val PUT = HttpMethod("PUT")
  val DELETE = HttpMethod("DELETE")
  val CONNECT = HttpMethod("CONNECT")
  val OPTIONS = HttpMethod("OPTIONS")
  val TRACE = HttpMethod("TRACE")
  val PATCH = HttpMethod("PATCH")
}

/** Ordered name/value parameter used by media types and authentication challenges. */
final case class HttpParameter(name: String, value: String, quoted: Boolean = false) {
  require(HttpSyntax.isToken(name), s"Invalid HTTP parameter name: $name")

  def render: String = {
    val renderedValue =
      if (quoted || !HttpSyntax.isToken(value)) s"\"${HttpSyntax.escapeQuoted(value)}\""
      else value
    s"$name=$renderedValue"
  }
}

/** Transport-neutral media type retaining ordered and repeated parameters. */
final class FhirMediaType private (
  val mainType: String,
  val subType: String,
  val parameters: Vector[HttpParameter]) {

  require(HttpSyntax.isToken(mainType), s"Invalid media main type: $mainType")
  require(HttpSyntax.isToken(subType), s"Invalid media subtype: $subType")

  val normalizedMainType: String = mainType
  val normalizedSubType: String = subType

  def value: String = s"$normalizedMainType/$normalizedSubType"
  def parameterValues(name: String): Vector[String] =
    parameters.collect { case parameter if parameter.name.equalsIgnoreCase(name) => parameter.value }

  def withParams(params: Map[String, String]): FhirMediaType =
    FhirMediaType(mainType, subType, parameters ++ params.iterator.map { case (name, value) => HttpParameter(name, value) })

  def matches(other: FhirMediaType): Boolean =
    normalizedMainType == other.normalizedMainType &&
      normalizedSubType == other.normalizedSubType &&
      parameters.forall(parameter => other.parameterValues(parameter.name).contains(parameter.value))

  override def toString: String =
    if (parameters.isEmpty) value else s"$value; ${parameters.map(_.render).mkString("; ")}"

  override def equals(other: Any): Boolean = other match {
    case that: FhirMediaType =>
      normalizedMainType == that.normalizedMainType &&
        normalizedSubType == that.normalizedSubType &&
        parameters == that.parameters
    case _ => false
  }

  override def hashCode(): Int = (normalizedMainType, normalizedSubType, parameters).hashCode()
}

object FhirMediaType {
  def apply(
    mainType: String,
    subType: String,
    parameters: Vector[HttpParameter] = Vector.empty): FhirMediaType =
    new FhirMediaType(
      mainType.toLowerCase(java.util.Locale.ROOT),
      subType.toLowerCase(java.util.Locale.ROOT),
      parameters
    )

  def application(subType: String, parameters: Vector[HttpParameter] = Vector.empty): FhirMediaType =
    FhirMediaType("application", subType, parameters)

  def text(subType: String, parameters: Vector[HttpParameter] = Vector.empty): FhirMediaType =
    FhirMediaType("text", subType, parameters)

  def parse(value: String): FhirMediaType = {
    val parts = HttpSyntax.splitOutsideQuotes(value, ';').map(_.trim)
    val typeParts = parts.headOption.getOrElse("").split("/", 2)
    require(typeParts.length == 2, s"Invalid media type: $value")
    val parameters = parts.drop(1).filter(_.nonEmpty).map(HttpSyntax.parseParameter).toVector
    FhirMediaType(typeParts(0), typeParts(1), parameters)
  }
}

/** Media type plus an optional charset. */
final case class FhirContentType(mediaType: FhirMediaType, charset: Option[String] = None) {
  def value: String = charset.fold(mediaType.toString)(name => s"${mediaType.toString}; charset=$name")
  override def toString: String = value
}

/** Opaque strong or weak entity tag. */
final case class EntityTag(value: String, weak: Boolean = false) {
  require(!value.exists(character => character == '\r' || character == '\n'), "Entity tag contains control characters")
  def render: String = s"${if (weak) "W/" else ""}\"${HttpSyntax.escapeQuoted(value)}\""
  override def toString: String = render
}

sealed trait EntityTagCondition {
  def render: String
}

case object AnyEntityTag extends EntityTagCondition {
  override val render: String = "*"
}

final case class EntityTagList(tags: Vector[EntityTag]) extends EntityTagCondition {
  require(tags.nonEmpty, "An entity-tag list cannot be empty")
  override def render: String = tags.map(_.render).mkString(", ")
}

object EntityTagCondition {
  def parse(value: String): EntityTagCondition = {
    val trimmed = value.trim
    if (trimmed == "*") AnyEntityTag
    else {
      val tags = HttpSyntax.splitOutsideQuotes(trimmed, ',').map(parseTag).toVector
      EntityTagList(tags)
    }
  }

  private def parseTag(value: String): EntityTag = {
    val trimmed = value.trim
    val weak = trimmed.startsWith("W/")
    val quoted = if (weak) trimmed.drop(2).trim else trimmed
    require(quoted.length >= 2 && quoted.head == '"' && quoted.last == '"', s"Invalid entity tag: $value")
    EntityTag(HttpSyntax.unescapeQuoted(quoted.substring(1, quoted.length - 1)), weak)
  }
}

sealed trait AuthenticationCredentials
final case class Token68(value: String) extends AuthenticationCredentials {
  require(value.matches("[A-Za-z0-9\\-._~+/]+=*"), s"Invalid token68 value: $value")
}
final case class AuthenticationParameters(values: Vector[HttpParameter]) extends AuthenticationCredentials {
  require(values.nonEmpty, "Authentication parameters cannot be empty")
}

/** A single WWW-Authenticate challenge. */
final case class AuthenticateChallenge(scheme: String, credentials: AuthenticationCredentials) {
  require(HttpSyntax.isToken(scheme), s"Invalid authentication scheme: $scheme")

  def render: String = credentials match {
    case Token68(value) => s"$scheme $value"
    case AuthenticationParameters(values) => s"$scheme ${values.map(_.render).mkString(", ")}"
  }
  def value(): String = render
}

object AuthenticateChallenge {
  def parse(value: String): AuthenticateChallenge = {
    val separator = value.indexWhere(_.isWhitespace)
    require(separator > 0, s"Invalid authentication challenge: $value")
    val scheme = value.substring(0, separator)
    val credentials = value.substring(separator).trim
    if (credentials.matches("[A-Za-z0-9\\-._~+/]+=*"))
      AuthenticateChallenge(scheme, Token68(credentials))
    else
      AuthenticateChallenge(
        scheme,
        AuthenticationParameters(HttpSyntax.splitOutsideQuotes(credentials, ',').map(HttpSyntax.parseParameter).toVector)
      )
  }
}

final case class ForwardedFor(values: Vector[String]) {
  require(values.nonEmpty && values.forall(HttpSyntax.isHeaderValue), "Invalid X-Forwarded-For value")
}

final case class ForwardedHost(values: Vector[String]) {
  require(values.nonEmpty && values.forall(HttpSyntax.isHeaderValue), "Invalid X-Forwarded-Host value")
}

final case class HttpHeader(name: String, value: String) {
  require(HttpSyntax.isToken(name), s"Invalid HTTP header name: $name")
  require(HttpSyntax.isHeaderValue(value), s"Invalid HTTP header value for $name")
}

final case class HttpHeaders(entries: Vector[HttpHeader] = Vector.empty) {
  def values(name: String): Vector[String] =
    entries.collect { case header if header.name.equalsIgnoreCase(name) => header.value }
  def add(header: HttpHeader): HttpHeaders = copy(entries = entries :+ header)
}

final case class QueryPair(name: String, value: Option[String])

/** Ordered query representation preserving duplicates and absent versus empty values. */
final case class OrderedQuery(pairs: Vector[QueryPair]) {
  def render: String = pairs.map { pair =>
    val encodedName = OrderedQuery.encode(pair.name)
    pair.value.fold(encodedName)(value => s"$encodedName=${OrderedQuery.encode(value)}")
  }.mkString("&")

  def toMultiMap: Map[String, List[String]] =
    pairs.foldLeft(ListMap.empty[String, List[String]]) { (result, pair) =>
      result.updated(pair.name, result.getOrElse(pair.name, Nil) :+ pair.value.getOrElse(""))
    }
}

object OrderedQuery {
  val empty: OrderedQuery = OrderedQuery(Vector.empty)

  def parse(rawQuery: String): OrderedQuery = {
    if (rawQuery == null || rawQuery.isEmpty) empty
    else OrderedQuery(rawQuery.split("&", -1).toVector.map { component =>
      val separator = component.indexOf('=')
      if (separator < 0) QueryPair(decode(component), None)
      else QueryPair(decode(component.substring(0, separator)), Some(decode(component.substring(separator + 1))))
    })
  }

  def fromMultiMap(parameters: Map[String, List[String]]): OrderedQuery =
    OrderedQuery(parameters.iterator.flatMap { case (name, values) =>
      if (values.isEmpty) Iterator.single(QueryPair(name, None))
      else values.iterator.map(value => QueryPair(name, Some(value)))
    }.toVector)

  private[model] def encode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name())

  private def decode(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}

private object HttpSyntax {
  private val tokenPattern = "[!#$%&'*+.^_`|~0-9A-Za-z-]+".r

  def isToken(value: String): Boolean = value != null && tokenPattern.pattern.matcher(value).matches()
  def isHeaderValue(value: String): Boolean =
    value != null && value.nonEmpty && !value.exists(character => character == '\r' || character == '\n')

  def escapeQuoted(value: String): String = value.flatMap {
    case '\\' => "\\\\"
    case '"' => "\\\""
    case other => other.toString
  }

  def unescapeQuoted(value: String): String = {
    val result = new StringBuilder
    var escaped = false
    value.foreach { character =>
      if (escaped) {
        result.append(character)
        escaped = false
      } else if (character == '\\') escaped = true
      else result.append(character)
    }
    require(!escaped, "Invalid trailing escape in quoted value")
    result.toString()
  }

  def splitOutsideQuotes(value: String, delimiter: Char): Vector[String] = {
    val result = Vector.newBuilder[String]
    val current = new StringBuilder
    var quoted = false
    var escaped = false
    value.foreach { character =>
      if (escaped) {
        current.append(character)
        escaped = false
      } else character match {
        case '\\' if quoted =>
          current.append(character)
          escaped = true
        case '"' =>
          current.append(character)
          quoted = !quoted
        case value if value == delimiter && !quoted =>
          result += current.toString()
          current.clear()
        case other => current.append(other)
      }
    }
    require(!quoted && !escaped, s"Invalid quoted HTTP value: $value")
    result += current.toString()
    result.result()
  }

  def parseParameter(value: String): HttpParameter = {
    val separator = value.indexOf('=')
    require(separator > 0, s"Invalid HTTP parameter: $value")
    val name = value.substring(0, separator).trim
    val rawValue = value.substring(separator + 1).trim
    val quoted = rawValue.length >= 2 && rawValue.head == '"' && rawValue.last == '"'
    val decoded = if (quoted) unescapeQuoted(rawValue.substring(1, rawValue.length - 1)) else rawValue
    HttpParameter(name, decoded, quoted)
  }
}
