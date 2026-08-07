package io.repofyr.stu3

import io.repofyr.Onfhir
import io.repofyr.config.OnfhirConfig
import io.repofyr.db.MongoDB
import io.repofyr.embedded.EmbeddedMongo
import io.repofyr.stu3.config.FhirSTU3Configurator
import akka.http.scaladsl.testkit.Specs2RouteTest
import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAll

import java.util.concurrent.TimeUnit
import scala.concurrent.Await
import scala.concurrent.duration.Duration

/**
 * Boots the STU3 server once for the whole module, on the embedded MongoDB
 * configured in src/test/resources/application.conf.
 *
 * This mirrors io.repofyr.OnFhirTest in repofyr-server-r4 rather than sharing
 * it: the R4 harness lives in that module's test sources, and a test-jar
 * dependency between two server modules would be a heavier coupling than the
 * dozen lines it saves.
 */
object OnfhirSTU3Setup {
  lazy val environment: Onfhir = {
    Onfhir.apply(new FhirSTU3Configurator)
  }
}

trait OnFhirSTU3Test extends Specification with Specs2RouteTest with BeforeAll {

  override def beforeAll(): Unit = {
    if (OnfhirConfig.mongoDbSettings.embedded) {
      val firstHostConfig = OnfhirConfig.mongoDbSettings.hosts.head.split(':')
      EmbeddedMongo.start(OnfhirConfig.serverName, firstHostConfig(0), firstHostConfig(1).toInt, withTemporaryDatabaseDir = true)
    }
    OnfhirSTU3Setup.environment
  }

  override def afterAll(): Unit = {
    Await.result(MongoDB.getDatabase.drop().head(), Duration.apply(5, TimeUnit.SECONDS))
    if (OnfhirConfig.mongoDbSettings.embedded) {
      EmbeddedMongo.stop()
    }
  }
}
