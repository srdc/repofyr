package io.repofyr.db

import io.repofyr.config.MongoDbSettings
import org.mongodb.scala.MongoCredential

/**
 * Decides whether the MongoDB client is given credentials, and against which database.
 *
 * Split out of [[MongoDB]] so it can be tested: that object touches `Onfhir.actorSystem` and opens
 * a connection pool the moment it is referenced, so nothing about it is reachable from a unit test.
 *
 * Through 4.0.0 the client required `authdb` to be configured before it would authenticate at all,
 * alongside `username` and `password`. Supplying only a user name and password - the obvious thing
 * to do, and all a connection string would need - therefore produced an unauthenticated client and
 * a failure from MongoDB that named neither the missing setting nor the reason. `authdb` is now
 * what it should always have been: optional, defaulting to `admin`.
 */
object MongoCredentialSupport {

  /**
   * The database credentials are verified against when the configuration does not name one.
   *
   * `admin` rather than the FHIR database, because a deployment that creates a dedicated user
   * almost always creates it there, and because it is what the rest of [[MongoDB]] already assumes
   * when it needs an administrative database.
   */
  private[db] final val DefaultAuthDatabase = "admin"

  /**
   * Build the credential for a configuration, if it asks for authentication.
   *
   * @param settings the resolved `mongodb` settings
   * @return the credential, or None when no user name and password are configured
   */
  def credentialFor(settings: MongoDbSettings): Option[MongoCredential] =
    for {
      username <- settings.username
      password <- settings.password
    } yield MongoCredential.createCredential(
      username,
      settings.authDbName.getOrElse(DefaultAuthDatabase),
      password.toCharArray)
}
