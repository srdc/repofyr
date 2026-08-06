package io.repofyr.api.client

import io.repofyr.Onfhir
import io.onfhir.api.model.{FHIRRequest, FHIRResponse}
import io.repofyr.api.service.FHIRServiceFactory

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import io.repofyr.config.{FhirConfigurationManager, OnfhirConfig}
import io.repofyr.server.ErrorHandler
import io.onfhir.api.client.BaseFhirClient
import io.onfhir.api.client.FhirGetSearchPageRequestBuilder
import io.onfhir.api.client.FHIRHistoryBundle
import io.onfhir.api.client.FHIRPaginatedBundle
import io.onfhir.api.client.FhirSearchRequestBuilder
import io.onfhir.api.client.FHIRSearchSetBundle

object OnFhirLocalClient extends BaseFhirClient {
  implicit val executionContext: ExecutionContextExecutor = Onfhir.actorSystem.dispatcher

  override def getBaseUrl(): String = OnfhirConfig.fhirRootUrl.stripSuffix("/")

  /**
   * Execute a FHIR Request internally and return FHIR Response
   * @param fhirRequest
   * @return
   */
  override def execute(fhirRequest: FHIRRequest): Future[FHIRResponse] = {

    val service = FHIRServiceFactory.getFHIRService(fhirRequest, None)
    FhirConfigurationManager
      .targetResourceResolver
      .resolveTargetResourceUpdateRequest(fhirRequest)
      .flatMap(_ => service.executeInteraction(fhirRequest))
      .recover[FHIRResponse](ErrorHandler.fhirErrorHandlerToResponse)
  }

  /**
   *
   * @param bundle
   * @return
   */
  override def next[T<:FHIRPaginatedBundle](bundle: T):Future[T] = {
    bundle match {
      case s:FHIRSearchSetBundle =>
        s.request match {
          case baseRequest:FhirSearchRequestBuilder =>
            val paginationParam = baseRequest.page.map(_._1).getOrElse("_page")
            val nextPage = bundle.getNextPage(paginationParam).get
            baseRequest.page = Some(paginationParam -> nextPage)
            baseRequest.executeAndReturnBundle().map(_.asInstanceOf[T])
          case _:FhirGetSearchPageRequestBuilder =>
            this.getSearchPage(bundle.getNext()).executeAndReturnBundle().map(_.asInstanceOf[T])
        }

      case h:FHIRHistoryBundle =>
        val baseRequest = h.request
        val paginationParam = baseRequest.page.map(_._1).getOrElse("_page")
        val nextPage = bundle.getNextPage(paginationParam).get
        baseRequest.page = Some(paginationParam -> nextPage)
        baseRequest.executeAndReturnBundle().map(_.asInstanceOf[T])
    }
  }

  /**
   * Special request to create/update resources in bulk
   * @return
   */
  def bulkUpsert(rtype:String): OnFhirBulkRequestBuilder = new OnFhirBulkRequestBuilder(this, rtype)
}
