package io.repofyr.r4

import io.repofyr.Onfhir
import io.repofyr.config.{EmbeddedMongoSupport, OnfhirConfig}
import io.repofyr.r4.config.FhirR4Configurator

object Boot extends App {

  // Embedded MongoDB is not part of the runnable server; it lives in repofyr-dev-server.
  // Reject the setting rather than ignoring it, so a stale configuration is a clear error
  // instead of a connection timeout against a database that was never started.
  EmbeddedMongoSupport.rejectIfRequested(OnfhirConfig.mongoDbSettings.embedded)

  //Initialize onfhir for R4
  var onfhir = Onfhir.apply(new FhirR4Configurator())
  //Start it
  onfhir.start
}
