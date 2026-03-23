package io.onfhir

package object audit {
  object ONFHIR_AUDIT_ENTITY_ROLES {
    /**
     * Code system to used in onfhir for AuditEvent.entity.role bindings for specific roles
     */
    final val CODE_SYSTEM = "http://repofyr.io/fhir/CodeSystem/audit-entity-role"
    // Code to indicate that entity gives the FHIR request details
    final val FHIR_REQUEST = "fhir-request"
  }


  object ONFHIR_AUDIT_DETAIL_TYPES {
    /**
     * Code system to used in onfhir for AuditEvent.entity.detail.type bindings for specific detail types
     */
    final val CODE_SYSTEM = "http://repofyr.io/fhir/CodeSystem/audit-detail-type"

    // Detail providing the related FHIR Resource type about FHIR request
    final val RESOURCE_TYPE = "resource-type"
    // Detail providing the child request identifier for FHIR Transaction and Batch requests for tracing errors and linking between main AuditEvent record and event for requests
    final val CHILD_REQUEST_ID = "child-request-id"
    // Detail providing the FHIR operation name e.g. $validate for FHIR Operation related audit events
    final val OPERATION_NAME = "operation-name"
  }

}
