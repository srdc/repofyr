package io.repofyr.db

import io.repofyr.config.MongoDbSettings
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

/**
 * Pins when the MongoDB client authenticates.
 *
 * The interesting case is the second one: requiring `authdb` alongside the credentials meant that
 * the most natural configuration - a user name and a password - was silently discarded, and the
 * resulting authentication failure came from MongoDB rather than from Repofyr, naming neither the
 * missing key nor the cause.
 */
@RunWith(classOf[JUnitRunner])
class MongoCredentialSupportTest extends Specification {

  private def settings(
      username: Option[String] = None,
      password: Option[String] = None,
      authDbName: Option[String] = None): MongoDbSettings =
    MongoDbSettings.Standard.copy(username = username, password = password, authDbName = authDbName)

  "MongoDB credentials" should {

    "be absent when no user name and password are configured" in {
      MongoCredentialSupport.credentialFor(MongoDbSettings.Standard) must beNone
    }

    "authenticate against admin when only a user name and password are configured" in {
      val credential = MongoCredentialSupport.credentialFor(
        settings(username = Some("fhir-user"), password = Some("secret")))

      credential must beSome
      credential.get.getUserName mustEqual "fhir-user"
      credential.get.getSource mustEqual "admin"
    }

    "authenticate against the configured database when authdb is given" in {
      val credential = MongoCredentialSupport.credentialFor(
        settings(username = Some("fhir-user"), password = Some("secret"), authDbName = Some("onfhir")))

      credential must beSome
      credential.get.getSource mustEqual "onfhir"
    }

    "stay absent when only half a credential is configured" in {
      MongoCredentialSupport.credentialFor(settings(username = Some("fhir-user"))) must beNone
      MongoCredentialSupport.credentialFor(settings(password = Some("secret"))) must beNone
    }

    "not authenticate on the strength of authdb alone" in {
      MongoCredentialSupport.credentialFor(settings(authDbName = Some("admin"))) must beNone
    }
  }
}
