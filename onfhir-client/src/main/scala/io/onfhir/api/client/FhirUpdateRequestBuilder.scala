package io.onfhir.api.client

import io.onfhir.api.model.{EntityTag, EntityTagList}
import io.onfhir.api.{FHIR_INTERACTIONS, Resource}
import io.onfhir.api.model.FHIRRequest
import io.onfhir.api.util.FHIRUtil

/**
 * Request builder for FHIR Update
 *
 * @param onFhirClient
 * @param resource
 */
class FhirUpdateRequestBuilder(onFhirClient: IOnFhirClient, rtype:String, rid:Option[String], resource: Resource, useIfMatch:Boolean = true)
  extends FhirSearchLikeRequestBuilder(onFhirClient, {
    FHIRRequest(interaction = FHIR_INTERACTIONS.UPDATE, requestUri = s"${onFhirClient.getBaseUrl()}/$rtype/${rid.map("/" + _).getOrElse("")}", resourceType = Some(rtype), resourceId = rid, resource = Some(resource))
  }) {
  type This = FhirUpdateRequestBuilder

  protected override def compile(): Unit = {
    super.compile()
    if(useIfMatch){
      FHIRUtil.extractVersionOptionFromResource(resource)
        .foreach(v =>
          request.ifMatch = Some(EntityTagList(Vector(EntityTag(s"$v", weak = true))))
        )
    }
  }
}
