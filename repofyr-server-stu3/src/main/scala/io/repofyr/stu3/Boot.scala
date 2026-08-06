package io.repofyr.stu3

import io.repofyr.Onfhir
import io.repofyr.stu3.config.{FhirSTU3Configurator}

object Boot extends App {
  //Initialize onfhir for DSTU3
  var onfhir = Onfhir.apply(new FhirSTU3Configurator())
  //Start it
  onfhir.start
}
