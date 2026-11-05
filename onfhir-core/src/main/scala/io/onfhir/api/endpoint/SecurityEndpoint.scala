package io.onfhir.api.endpoint

import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.nimbusds.jose.jwk.JWKSet
import io.onfhir.config.OnfhirConfig

trait SecurityEndpoint {
  /**
    * Routes related with security issues
    */
  val securityRoute:Route =
    get {
      //Returning our JWKSet (public keys only); .well-known/jwks.json is where SMART Health Card verifiers look for the issuer keys
      path(".well-known" / "jwks.json") {
        jwks
      } ~
      path(".well-known" / "jwks") {
        jwks
      } ~
      path("jwks") {
        //TODO
        //respondWithMediaType(AuthzConfig.JWKSET_MEDIA_TYPE) {
        jwks
        //}
      }
    }

  private def jwks = {
    complete {
      Option(OnfhirConfig.authzConfig).flatMap(config => Option(config.protectedResourceJWKSet)) match {
        case Some(jwkSet) =>
          HttpResponse(StatusCodes.OK, entity = HttpEntity(ContentTypes.`application/json`, jwkSet.toPublicJWKSet.toString))
        case None =>
          HttpResponse(StatusCodes.NotFound)
      }
    }
  }

}
