package io.repofyr.config

import io.onfhir.exception.InitializationException

/**
 * Guard for the `mongodb.embedded` setting on a standalone server.
 *
 * Embedded MongoDB is not part of the runnable server artifacts. It lives in
 * `repofyr-embedded-mongo` and is started by `repofyr-dev-server`, so that no production jar
 * carries a component whose job is downloading and executing a `mongod` binary.
 *
 * A configuration that still asks for it must fail loudly at startup. Left unchecked the server
 * would come up, find no database at the configured address, and surface a connection timeout
 * that says nothing about the real cause.
 */
object EmbeddedMongoSupport {

  private[repofyr] val UnsupportedMessage: String =
    "mongodb.embedded = true is not supported by the standalone Repofyr server. " +
      "Embedded MongoDB moved to repofyr-dev-server in 4.0.0. Either run repofyr-dev-server " +
      "for a development instance, or start MongoDB separately and set mongodb.embedded = false."

  /**
   * Fail fast when a standalone server is configured to start an embedded database it cannot
   * start.
   *
   * @param embedded the configured value of `mongodb.embedded`
   * @throws InitializationException when embedded MongoDB is requested
   */
  def rejectIfRequested(embedded: Boolean): Unit =
    if (embedded) throw new InitializationException(UnsupportedMessage)
}
