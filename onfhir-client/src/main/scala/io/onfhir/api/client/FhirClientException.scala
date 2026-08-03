package io.onfhir.api.client

import io.onfhir.api.model.FHIRResponse

case class FhirClientException(msg:String, serverResponse:Option[FHIRResponse] = None) extends Exception(msg) {

  override def toString: String = {
    s"$msg\n"+
      s"${serverResponse.map(r => r.toString()).getOrElse("")}"
  }

}

object FhirClientException {
  def causedBy(message: String, cause: Throwable): FhirClientException = {
    val exception = new FhirClientException(message)
    exception.initCause(cause)
    exception
  }
}
