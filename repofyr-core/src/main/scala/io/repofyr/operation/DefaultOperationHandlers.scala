package io.repofyr.operation

/**
 * FHIR Operations that are defined by FHIR and implemented in default within the server.
 * The server owns this dispatch table because the implementation classes are server code;
 * the operation URLs keep their published values.
 */
object DefaultOperationHandlers {
  val DEFAULT_IMPLEMENTED_FHIR_OPERATIONS: Map[String, String] =
    Map(
      "http://hl7.org/fhir/OperationDefinition/Resource-meta" -> "io.repofyr.operation.MetaOperationHandler",
      "http://hl7.org/fhir/OperationDefinition/Resource-meta-add" -> "io.repofyr.operation.MetaOperationHandler",
      "http://hl7.org/fhir/OperationDefinition/Resource-meta-delete" -> "io.repofyr.operation.MetaOperationHandler",
      "http://hl7.org/fhir/OperationDefinition/Resource-validate" -> "io.repofyr.operation.ValidationOperationHandler",
      "http://hl7.org/fhir/OperationDefinition/ValueSet-expand" -> "io.repofyr.operation.ExpandOperationHandler",
      "http://hl7.org/fhir/OperationDefinition/Composition-document" -> "io.repofyr.operation.DocumentOperationHandler",
      "http://hl7.org/fhir/OperationDefinition/Observation-lastn" -> "io.repofyr.operation.LastNObservationOperationHandler",
      "http://onfhir.io/fhir/OperationDefinition/import" -> "io.repofyr.operation.BulkOperationHandler",
      "http://hl7.org/fhir/OperationDefinition/Patient-everything" -> "io.repofyr.operation.PatientEverythingOperationHandler"
    )
}
