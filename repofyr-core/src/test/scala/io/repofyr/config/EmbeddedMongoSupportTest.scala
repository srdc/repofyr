package io.repofyr.config

import io.onfhir.exception.InitializationException
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

/**
 * Embedded MongoDB left the runnable server artifacts in 4.0.0, so `mongodb.embedded = true`
 * against a standalone server is now a configuration error.
 *
 * That error path is the most likely user-visible symptom of the move and the one thing no other
 * test can reach: the production classpath has no `EmbeddedMongo` to exercise, so without this
 * suite the guard would ship untested.
 */
@RunWith(classOf[JUnitRunner])
class EmbeddedMongoSupportTest extends Specification {

  "The standalone server embedded MongoDB guard" should {

    "accept a configuration that does not ask for an embedded database" in {
      EmbeddedMongoSupport.rejectIfRequested(embedded = false) must not(throwAn[Exception])
    }

    "reject mongodb.embedded = true rather than starting without a database" in {
      EmbeddedMongoSupport.rejectIfRequested(embedded = true) must throwAn[InitializationException]
    }

    // The message is the whole point of failing fast: without it the operator sees a connection
    // timeout against an address nothing is listening on.
    "name the replacement and the way out in its message" in {
      EmbeddedMongoSupport.UnsupportedMessage must contain("repofyr-dev-server")
      EmbeddedMongoSupport.UnsupportedMessage must contain("mongodb.embedded = false")
    }
  }
}
