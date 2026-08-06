package io.repofyr.r5

import io.repofyr.Onfhir
import io.repofyr.config.OnfhirConfig
import io.repofyr.db.EmbeddedMongo
import io.repofyr.r5.config.FhirR5Configurator

object Boot extends App {

  // Start an embedded mongo if it is configured before any other processing.
  if (OnfhirConfig.mongoEmbedded) {
    // If it is configured to use an embedded Mongo instance
    val firstHostConfig = OnfhirConfig.mongodbHosts.head.split(':')
    EmbeddedMongo.start(OnfhirConfig.serverName, firstHostConfig(0), firstHostConfig(1).toInt, withTemporaryDatabaseDir = false)
  }

  //Initialize onfhir for R5
  var onfhir = Onfhir.apply(new FhirR5Configurator())
  //Start it
  onfhir.start
}
