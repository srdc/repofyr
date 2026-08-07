package io.repofyr.r5

import io.repofyr.Onfhir
import io.repofyr.config.OnfhirConfig
import io.repofyr.db.MongoDB
import io.repofyr.embedded.EmbeddedMongo
import io.repofyr.r5.config.FhirR5Configurator
import akka.http.scaladsl.testkit.Specs2RouteTest
import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAll

import java.util.concurrent.TimeUnit
import scala.concurrent.Await
import scala.concurrent.duration.Duration

/**
 * Boots the R5 server once for the whole module, on the embedded MongoDB
 * configured in src/test/resources/application.conf.
 *
 * This mirrors io.repofyr.OnFhirTest in repofyr-server-r4 rather than sharing
 * it: the R4 harness lives in that module's test sources, and a test-jar
 * dependency between two server modules would be a heavier coupling than the
 * dozen lines it saves.
 */
object OnfhirR5Setup {
  lazy val environment: Onfhir = {
    Onfhir.apply(new FhirR5Configurator)
  }
}

trait OnFhirR5Test extends Specification with Specs2RouteTest with BeforeAll {

  override def beforeAll(): Unit = {
    if (OnfhirConfig.mongoDbSettings.embedded) {
      val firstHostConfig = OnfhirConfig.mongoDbSettings.hosts.head.split(':')
      EmbeddedMongo.start(OnfhirConfig.serverName, firstHostConfig(0), firstHostConfig(1).toInt, withTemporaryDatabaseDir = true)
    }
    OnfhirR5Setup.environment
  }

  override def afterAll(): Unit = {
    Await.result(MongoDB.getDatabase.drop().head(), Duration.apply(5, TimeUnit.SECONDS))
    if (OnfhirConfig.mongoDbSettings.embedded) {
      EmbeddedMongo.stop()
    }
  }
}
