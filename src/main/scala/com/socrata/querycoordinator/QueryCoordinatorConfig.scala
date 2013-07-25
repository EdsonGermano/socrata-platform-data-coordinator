package com.socrata.querycoordinator

import scala.concurrent.duration._

import com.typesafe.config.Config

class QueryCoordinatorConfig(config: Config, root: String) {
  private def k(s: String) = root + "." + s

  val log4j = config.getConfig(k("log4j"))
  val curator = new CuratorConfig(config, k("curator"))
  val advertisement = new AdvertisementConfig(config, k("service-advertisement"))
  val network = new NetworkConfig(config, k("network"))

  val schemaTimeout = config.getMilliseconds(k("get-schema-timeout")).longValue.millis
  val initialResponseTimeout = config.getMilliseconds(k("initial-response-timeout")).longValue.millis
  val responseDataTimeout = config.getMilliseconds(k("response-data-timeout")).longValue.millis
}