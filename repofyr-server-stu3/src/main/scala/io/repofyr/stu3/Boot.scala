package io.repofyr.stu3

import io.repofyr.Onfhir
import io.repofyr.config.OnfhirConfig
import io.repofyr.db.EmbeddedMongo
import io.repofyr.stu3.config.FhirSTU3Configurator

object Boot extends App {

  // Start an embedded mongo if it is configured before any other processing.
  if (OnfhirConfig.mongoDbSettings.embedded) {
    // If it is configured to use an embedded Mongo instance
    val firstHostConfig = OnfhirConfig.mongoDbSettings.hosts.head.split(':')
    EmbeddedMongo.start(OnfhirConfig.serverName, firstHostConfig(0), firstHostConfig(1).toInt, withTemporaryDatabaseDir = false)
  }
  //Initialize onfhir for DSTU3
  var onfhir = Onfhir.apply(new FhirSTU3Configurator())
  //Start it
  onfhir.start
}
