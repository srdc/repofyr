package io.onfhir.client

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import io.onfhir.api.FHIR_CONTENT_TYPES
import io.onfhir.api.client.FhirClientException
import io.onfhir.api.model.{HttpStatus, HttpHeader}
import io.onfhir.client.model.{ClientHttpRequest, ClientHttpSettings}
import io.onfhir.client.intrcp.BasicAuthenticationInterceptor
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import org.specs2.specification.BeforeAfterAll

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import javax.net.ssl.SSLContext
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try

@RunWith(classOf[JUnitRunner])
class OnFhirNetworkClientTransportTest extends Specification with BeforeAfterAll {
  sequential

  private val lastMethod = new AtomicReference[String]()
  private val lastRawQuery = new AtomicReference[String]()
  private val lastPath = new AtomicReference[String]()
  private val lastAuthorization = new AtomicReference[String]()
  private val lastBody = new AtomicReference[String]()
  private val lastOrderHeaders = new AtomicReference[Seq[String]]()
  private val retryRequests = new AtomicInteger()
  private val tokenRequests = new AtomicInteger()
  private var server: HttpServer = _
  private var baseUrl: String = _

  implicit private val executionContext: ExecutionContext = ExecutionContext.global

  override def beforeAll(): Unit = {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/fhir", new HttpHandler {
      override def handle(exchange: HttpExchange): Unit = {
        lastMethod.set(exchange.getRequestMethod)
        lastRawQuery.set(exchange.getRequestURI.getRawQuery)
        lastPath.set(exchange.getRequestURI.getRawPath)
        lastAuthorization.set(exchange.getRequestHeaders.getFirst("Authorization"))
        lastBody.set(new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8))
        lastOrderHeaders.set(Option(exchange.getRequestHeaders.get("X-Order")).map(_.toArray.toSeq.map(_.toString)).getOrElse(Nil))

        if (exchange.getRequestURI.getPath == "/fhir/Patient/retry" && retryRequests.getAndIncrement() == 0) {
          exchange.close()
          return
        }
        if (exchange.getRequestURI.getPath == "/fhir/Patient/slow") {
          Thread.sleep(250)
        }

        val (status, body) = exchange.getRequestURI.getPath match {
          case "/fhir/Patient/missing" => 204 -> ""
          case "/fhir/Patient/redirect" =>
            exchange.getResponseHeaders.add("Location", s"$baseUrl/Patient/p1")
            302 -> ""
          case path if path.endsWith("/_search") || path == "/fhir/Patient" =>
            200 -> """{"resourceType":"Bundle","type":"searchset","total":0,"entry":[]}"""
          case _ =>
            200 -> """{"resourceType":"Patient","id":"p1"}"""
        }

        exchange.getResponseHeaders.add("Content-Type", "application/fhir+json; charset=UTF-8")
        exchange.getResponseHeaders.add("Location", s"$baseUrl/Patient/p1")
        exchange.getResponseHeaders.add("ETag", "W/\"7\"")
        exchange.getResponseHeaders.add("Last-Modified", "Sun, 06 Nov 1994 08:49:37 GMT")
        val bytes = body.getBytes(StandardCharsets.UTF_8)
        if (status == 204) exchange.sendResponseHeaders(status, -1)
        else exchange.sendResponseHeaders(status, bytes.length)
        if (bytes.nonEmpty) exchange.getResponseBody.write(bytes)
        exchange.close()
      }
    })
    server.createContext("/token", new HttpHandler {
      override def handle(exchange: HttpExchange): Unit = {
        tokenRequests.incrementAndGet()
        lastAuthorization.set(exchange.getRequestHeaders.getFirst("Authorization"))
        lastBody.set(new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8))
        val body = """{"access_token":"access-1","token_type":"Bearer","expires_in":3600}"""
        val bytes = body.getBytes(StandardCharsets.UTF_8)
        exchange.getResponseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.length)
        exchange.getResponseBody.write(bytes)
        exchange.close()
      }
    })
    server.start()
    baseUrl = s"http://127.0.0.1:${server.getAddress.getPort}/fhir"
  }

  override def afterAll(): Unit = {
    if (server != null) server.stop(0)
  }

  "OnFhirNetworkClient transport" should {
    "round-trip JSON bodies and FHIR response headers" in {
      val response = Await.result(OnFhirNetworkClient(baseUrl).read("Patient", "p1").execute(), 5.seconds)

      response.httpStatus mustEqual HttpStatus.OK
      response.responseBody.map(_ \ "id").map(_.values) must beSome("p1")
      response.location.map(_.toString) must beSome(s"$baseUrl/Patient/p1")
      response.newVersion must beSome("7")
      response.lastModified.map(_.getEpochSecond) must beSome(784111777L)
      lastMethod.get mustEqual "GET"
    }

    "preserve duplicate query values and encode them exactly once" in {
      Await.result(
        OnFhirNetworkClient(baseUrl)
          .search("Patient")
          .where("identifier", "urn:oid:1|a b")
          .where("identifier", "urn:oid:2|c+d")
          .execute(),
        5.seconds)

      val rawQuery = lastRawQuery.get
      rawQuery must contain("identifier=urn%3Aoid%3A1%7Ca+b")
      rawQuery must contain("identifier=urn%3Aoid%3A2%7Cc%2Bd")
    }

    "preserve legal FHIR operation path separators" in {
      Await.result(OnFhirNetworkClient(baseUrl).operation("validate").on("Patient").execute(), 5.seconds)
      lastPath.get mustEqual "/fhir/Patient/$validate"
    }

    "support empty successful responses" in {
      val response = Await.result(OnFhirNetworkClient(baseUrl).read("Patient", "missing").execute(), 5.seconds)
      response.httpStatus mustEqual HttpStatus.NoContent
      response.responseBody must beNone
    }

    "apply basic authentication" in {
      Await.result(
        OnFhirNetworkClient(baseUrl, new BasicAuthenticationInterceptor("alice", "secret"))
          .read("Patient", "p1")
          .execute(),
        5.seconds)

      lastAuthorization.get mustEqual "Basic YWxpY2U6c2VjcmV0"
    }

    "apply interceptors in registration order" in {
      def addOrder(value: String) = new IHttpRequestInterceptor {
        override def processRequest(request: ClientHttpRequest)(implicit ex: ExecutionContext) =
          Future.successful(request.copy(headers = request.headers.add(HttpHeader("X-Order", value))))
      }

      Await.result(
        OnFhirNetworkClient(baseUrl, Seq(addOrder("first"), addOrder("second")))
          .read("Patient", "p1")
          .execute(),
        5.seconds)

      lastOrderHeaders.get mustEqual Seq("first", "second")
    }

    "short-circuit when an interceptor fails" in {
      val expected = new IllegalStateException("interceptor failed")
      val failing = new IHttpRequestInterceptor {
        override def processRequest(request: ClientHttpRequest)(implicit ex: ExecutionContext) = Future.failed(expected)
      }

      val thrown = Try(Await.result(OnFhirNetworkClient(baseUrl, failing).read("Patient", "p1").execute(), 5.seconds)).failed.get
      thrown must beAnInstanceOf[FhirClientException]
      thrown.getCause must beTheSameAs(expected)
    }

    "not follow redirects" in {
      val response = Await.result(OnFhirNetworkClient(baseUrl).read("Patient", "redirect").execute(), 5.seconds)
      response.httpStatus.code mustEqual 302
      response.location.map(_.toString) must beSome(s"$baseUrl/Patient/p1")
    }

    "enforce a configured total request timeout" in {
      val settings = ClientHttpSettings(requestTimeout = Some(Duration.ofMillis(50)), maxRetries = 0)
      val thrown = Try(Await.result(OnFhirNetworkClient(baseUrl, settings).read("Patient", "slow").execute(), 5.seconds)).failed.get
      thrown must beAnInstanceOf[FhirClientException]
      thrown.getCause must not(beNull)
    }

    "retry a replayable GET after a transport failure" in {
      retryRequests.set(0)
      val settings = ClientHttpSettings(maxRetries = 1)
      val response = Await.result(OnFhirNetworkClient(baseUrl, settings).read("Patient", "retry").execute(), 5.seconds)
      response.httpStatus mustEqual HttpStatus.OK
      retryRequests.get must be_>=(2)
    }

    "send JSON request bodies and reject XML explicitly" in {
      val patient = org.json4s.JsonAST.JObject(
        "resourceType" -> org.json4s.JsonAST.JString("Patient"),
        "id" -> org.json4s.JsonAST.JString("p1")
      )
      Await.result(OnFhirNetworkClient(baseUrl).create(patient).execute(), 5.seconds)
      lastBody.get must contain("\"resourceType\":\"Patient\"")

      val xmlRequest = OnFhirNetworkClient(baseUrl).create(patient)
      xmlRequest.request.contentType = Some(FHIR_CONTENT_TYPES.FHIR_XML_CONTENT_TYPE)
      Try(Await.result(xmlRequest.execute(), 5.seconds)).failed.get must beAnInstanceOf[FhirClientException]
    }

    "use the fixed Basic token helper rather than Bearer" in {
      Await.result(
        OnFhirNetworkClient(baseUrl).withFixedBasicTokenAuthentication("encoded-token")
          .read("Patient", "p1").execute(),
        5.seconds)
      lastAuthorization.get mustEqual "Basic encoded-token"
    }

    "retrieve and cache OAuth client-credentials tokens through the JDK transport" in {
      tokenRequests.set(0)
      val client = OnFhirNetworkClient(baseUrl).withOpenIdBearerTokenAuthentication(
        "client-1",
        "secret-1",
        Seq("system/Patient.read"),
        s"http://127.0.0.1:${server.getAddress.getPort}/token"
      )

      Await.result(Future.sequence(Seq(
        client.read("Patient", "p1").execute(),
        client.read("Patient", "p1").execute()
      )), 5.seconds)

      tokenRequests.get mustEqual 1
      lastAuthorization.get mustEqual "Bearer access-1"
    }

    "accept a caller supplied SSLContext" in {
      val settings = ClientHttpSettings(sslContext = Some(SSLContext.getDefault))
      Await.result(OnFhirNetworkClient(baseUrl, settings).read("Patient", "p1").execute(), 5.seconds).httpStatus mustEqual HttpStatus.OK
    }
  }
}
