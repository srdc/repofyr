package io.onfhir.client.model

import com.typesafe.config.Config

import java.time.Duration
import javax.net.ssl.SSLContext

/** Configuration owned by the JDK HTTP transport. */
final case class ClientHttpSettings(
  connectTimeout: Duration = Duration.ofSeconds(10),
  requestTimeout: Option[Duration] = None,
  maxRetries: Int = 5,
  sslContext: Option[SSLContext] = None) {

  require(!connectTimeout.isNegative && !connectTimeout.isZero, "Connect timeout must be positive")
  require(requestTimeout.forall(value => !value.isNegative && !value.isZero), "Request timeout must be positive")
  require(maxRetries >= 0, "Maximum retries must be non-negative")
}

object ClientHttpSettings {
  val default: ClientHttpSettings = ClientHttpSettings()

  def fromConfig(config: Config): ClientHttpSettings = {
    val httpConfig = if (config.hasPath("http")) config.getConfig("http") else config
    ClientHttpSettings(
      connectTimeout =
        if (httpConfig.hasPath("connect-timeout")) httpConfig.getDuration("connect-timeout")
        else default.connectTimeout,
      requestTimeout =
        if (httpConfig.hasPath("request-timeout")) Some(httpConfig.getDuration("request-timeout"))
        else default.requestTimeout,
      maxRetries =
        if (httpConfig.hasPath("max-retries")) httpConfig.getInt("max-retries")
        else default.maxRetries
    )
  }
}
