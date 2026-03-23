package io.onfhir.config

import com.typesafe.config.Config

import scala.util.Try

class AuditConfig(val auditConfig:Config) {
  /**
   * Configuration about the application that will act as audit source.
   * This can be given in FHIR identifier format
   * e.g. http://myfhir.server.com|node1 -> system, value (to indicate the Repofyr node that audit event is created in case multiple servers)
   * e.g. http://myfhir.server.com  -> value  (to indicate the whole Repofyr deployment)
   */
  lazy val auditSourceId:String = auditConfig.getString("source.id")
  lazy val auditSourceName:Option[String] = Try(auditConfig.getString("source.name")).toOption
  /**
   * Auditing mechanism to use;
   * - 'local' -> means we will store them locally to this FHIR repository instance as FHIR AuditEvent resources
   * - 'remote' -> means we send the FHIR AuditEvent records to an external FHIR repository that accepts FHIR AuditEvent resources
   * - 'custom' -> means a custom auditing mechanism will be used. For this, you need to provide a custom module implementing io.onfhir.audit.ICustomAuditHandler and register it to OnFhir.
   */
  lazy val auditRepositoryType:String = auditConfig.getString("repository")

  /**
   * If audits will be sent to a remote repository, details of that remote FHIR audit repository
   */
  //Base url of Audit Repository (if remote)
  lazy val remoteAuditRepositoryUrl:Option[String] = Try(auditConfig.getString("remote.repository-url")).toOption
  //If remote audit repository needs authentication or not
  lazy val remoteAuditRepositoryIsSecure:Boolean = Try(auditConfig.getBoolean("remote.is-secure")).toOption.getOrElse(false)
  //If auditing mechanism is remote, onFhir sends the audit records in batches. This indicates the batch intervals in minutes. Default is 5 minutes.
  lazy val remoteAuditBatchInterval:Int = Try(auditConfig.getInt("remote.batch-interval")).toOption.getOrElse(5)
  //This indicates the maximum batch size. If # of audit records passed this amount or batch interval time comes, audits are send to remote server. Default is 50.
  lazy val remoteAuditBatchSize:Int = Try(auditConfig.getInt("remote.batch-size")).toOption.getOrElse(50)

  /**
   * Configuration about claims that can be used for audit creation
   */
  // Claim path/name in authorization context indicating the FHIR entity of user e.g. fhirUser
  lazy val fhirUserClaim:Option[String] = Try(auditConfig.getString("claims.fhir-user-claim")).toOption
  // Claim path/name in authorization context indicating roles of the user e.g. realm-access.roles (Keycloak realm roles)
  lazy val userRolesClaim:Option[String] = Try(auditConfig.getString("claims.user-roles-claim")).toOption
  /** Return all further claims required in the access token for auditing */
  def getFurtherClaims:Set[String] = fhirUserClaim.toSet ++ userRolesClaim.toSet
}
