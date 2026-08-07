package io.repofyr

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import akka.http.scaladsl.model.headers.`Last-Modified`
import akka.http.scaladsl.testkit.Specs2RouteTest
import io.onfhir.api.Resource
import io.onfhir.api.util.FHIRUtil
import io.repofyr.config.OnfhirConfig
import io.repofyr.db.MongoDB
import io.repofyr.embedded.EmbeddedMongo
import io.repofyr.r4.config.FhirR4Configurator
import io.onfhir.util.DateTimeUtil
import org.json4s.JsonAST.{JArray, JObject}
import org.specs2.matcher.MatchResultCombinators
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAfterAll, BeforeAll}
import io.onfhir.util.JsonFormatter._

import scala.collection.mutable.ListBuffer
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration

object OnfhirSetup {
  lazy val environment: Onfhir = {
    Onfhir.apply(new FhirR4Configurator)
  }
}

trait OnFhirTest extends Specification with Specs2RouteTest with BeforeAll {

  override def beforeAll(): Unit = {
    if (OnfhirConfig.mongoDbSettings.embedded) {
      val firstHostConfig = OnfhirConfig.mongoDbSettings.hosts.head.split(':')
      EmbeddedMongo.start(OnfhirConfig.serverName, firstHostConfig(0), firstHostConfig(1).toInt, withTemporaryDatabaseDir = true)
    }
    OnfhirSetup.environment
  }

  override def afterAll(): Unit = {
    Await.result(MongoDB.getDatabase.drop().head(), Duration.apply(5, TimeUnit.SECONDS))
    if (OnfhirConfig.mongoDbSettings.embedded) {
      EmbeddedMongo.stop()
    }
  }

  /**
   * Check Meta version id and last updated and resource id
   *
   * @param resource
   * @param expectedResourceId
   * @param expectedVersion
   * @return
   */
  def checkIdAndMeta(resource: Resource, expectedResourceId: String, expectedVersion: String): org.specs2.matcher.MatchResult[Any] = {
    (if (expectedResourceId == null) FHIRUtil.extractValueOption[String](resource, "id") must beSome else
      FHIRUtil.extractValueOption[String](resource, "id") must beSome(expectedResourceId)) and
      (FHIRUtil.extractValueOptionByPath[String](resource, "meta.versionId") must beSome(expectedVersion)) and
      (FHIRUtil.extractValueOptionByPath[String](resource, "meta.lastUpdated").map(DateTimeUtil.parseFhirDateTimeOrInstant) must beSome((i: Instant) => ChronoUnit.SECONDS.between(i, Instant.now()) < 10))
  }

  def checkHeaders(expectedLastModified: String, expectedVersion: String): org.specs2.matcher.MatchResult[Any] = {
    (
      if (expectedLastModified == null)
        1 === 1
      else
        header("Last-Modified").map(_.asInstanceOf[`Last-Modified`].date.clicks / 1000L) must
          beSome(DateTimeUtil.parseInstant(expectedLastModified).get.getEpochSecond)
      ) and (header("ETag").map(_.value()) must beSome("W/\"" + expectedVersion + "\""))
  }

  def checkHeaders(resource: Resource, expectedRType: String, expectedResourceId: String, expectedVersion: String): org.specs2.matcher.MatchResult[Any] = {
    val isLocationOk = header("Location").map(_.value()) must beSome(FHIRUtil.resourceLocationWithVersion(OnfhirConfig.fhirEndpointSettings, expectedRType, expectedResourceId, expectedVersion.toLong))
    val isLastModifiedOk =
      if (resource != null) header("Last-Modified").map(_.asInstanceOf[`Last-Modified`].date.clicks / 1000L) must
        beSome(DateTimeUtil.parseInstant(FHIRUtil.extractValueOptionByPath[String](resource, "meta.lastUpdated").get).get.getEpochSecond) else
        header("Last-Modified").map(_.asInstanceOf[`Last-Modified`].date.toIsoDateTimeString() + "Z").map(DateTimeUtil.parseFhirDateTimeOrInstant) must beSome((i: Instant) => ChronoUnit.SECONDS.between(i, Instant.now()) < 10)

    val isETagOk = header("ETag").map(_.value()) must beSome("W/\"" + expectedVersion + "\"")

    isLocationOk and isLastModifiedOk and isETagOk
  }

  def checkSearchResult(bundle: Resource, resourceType: String, expectedTotal: Long, query: Option[String], count: Option[Int] = None, page: Option[Int] = None): org.specs2.matcher.MatchResult[Any] = {
    val matchers = new ListBuffer[org.specs2.matcher.MatchResult[Any]]
    matchers.append((bundle \ "type").extractOpt[String] must beSome("searchset"))
    matchers.append((bundle \ "total").extractOpt[Int] must beSome(expectedTotal))


    val selfLink = getLink(bundle, "self")
    matchers.append(selfLink must beSome)
    //matchers.append((selfLink.get \ "url").extractOpt[String] must beSome(OnfhirConfig.fhirEndpointSettings.rootUrl + "/" + resourceType + query.getOrElse("?") + count.map("&_count="+ _).getOrElse("") + "&_page=" + page.getOrElse(1)))

    if (expectedTotal > count.getOrElse(OnfhirConfig.fhirResultDefaults.defaultPageSize) * page.getOrElse(1)) {
      val nextlink = getLink(bundle, "next")
      matchers.append(nextlink must beSome)
      //matchers.append((nextlink.get \ "url").extractOpt[String] must beSome(OnfhirConfig.fhirEndpointSettings.rootUrl + "/" + resourceType + query.getOrElse("?") + count.map("&_count="+ _).getOrElse("") + "&_page="+ (page.getOrElse(1) + 1)))
    }

    if (expectedTotal > 0 && page.getOrElse(1) > 1) {
      val previouslink = getLink(bundle, "previous")
      matchers.append(previouslink must beSome)
      //matchers.append((previouslink.get \ "url").extractOpt[String] must beSome(OnfhirConfig.fhirEndpointSettings.rootUrl + "/" + resourceType + query.getOrElse("?") + count.map("&_count="+ _).getOrElse("") + "&_page="+ (page.getOrElse(1) - 1)))

      val firstLink = getLink(bundle, "first")
      matchers.append(firstLink must beSome)
      //matchers.append((previouslink.get \ "url").extractOpt[String] must beSome(OnfhirConfig.fhirEndpointSettings.rootUrl + "/" + resourceType + query.getOrElse("?") + count.map("&_count="+ _).getOrElse("") + "&_page=1"))


      val lastLink = getLink(bundle, "last")
      matchers.append(lastLink must beSome)
      //matchers.append((lastLink.get \ "url").extractOpt[String] must beSome(OnfhirConfig.fhirEndpointSettings.rootUrl + "/" + resourceType + query.getOrElse("?") + count.map("&_count="+ _).getOrElse("") +"&_page="+ Math.ceil(expectedTotal / count.getOrElse(OnfhirConfig.fhirResultDefaults.defaultPageSize))))
    }
    matchers.reduce((m1, m2) => m1 and m2)
  }

  private def getLink(bundle: Resource, link: String): Option[JObject] = {
    (bundle \ "link").asInstanceOf[JArray].arr.find(l =>
      (l \ "relation").extractOpt[String].contains(link)
    ).map(_.asInstanceOf[JObject])
  }

}
