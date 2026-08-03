package io.onfhir.api.client

import io.onfhir.api.FHIR_INTERACTIONS
import io.onfhir.api.model.{EntityTag, EntityTagList, FHIRRequest}

import java.time.Instant

class FhirReadRequestBuilder(onFhirClient: IOnFhirClient, rtype:String, rid:String)
  extends FhirRequestBuilder(onFhirClient, FHIRRequest(interaction = FHIR_INTERACTIONS.READ, requestUri = s"${onFhirClient.getBaseUrl()}/$rtype/$rid", resourceType = Some(rtype), resourceId = Some(rid))) {
  type This = FhirReadRequestBuilder

  def ifModifiedSince(instant:Instant):FhirReadRequestBuilder = {
    request.ifModifiedSince = Some(instant)
    this
  }

  def ifNoneMatch(version:Long):FhirReadRequestBuilder = {
    request.ifNoneMatch = Some(EntityTagList(Vector(EntityTag(""+version, weak = true))))
    this
  }

  def summary(s:String):FhirRequestBuilder = {
    request.queryParams = request.queryParams ++ Map("_summary" -> List(s))
    this
  }

  def elements(el:String*):FhirRequestBuilder = {
    request.queryParams = request.queryParams ++ Map("_elements" -> List(el.mkString(",")))
    this
  }

}
