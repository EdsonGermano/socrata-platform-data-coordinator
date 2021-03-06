package com.socrata.datacoordinator.secondary

import com.socrata.datacoordinator.common.DataSourceConfig
import com.socrata.datacoordinator.secondary.config.{SecondaryConfig => ServiceSecondaryConfig}
import com.socrata.datacoordinator.secondary.messaging.eurybates.MessageProducerConfig
import com.typesafe.config.{Config, ConfigException}
import java.util.UUID

import com.socrata.curator.{CuratorConfig, DiscoveryConfig}
import com.socrata.datacoordinator.common.collocation.CollocationConfig

import scala.concurrent.duration._

class SecondaryWatcherConfig(config: Config, root: String) {
  private def k(s: String) = root + "." + s
  val log4j = config.getConfig(k("log4j"))
  val database = new DataSourceConfig(config, k("database"))
  val curator = new CuratorConfig(config, k("curator"))
  val discovery = new DiscoveryConfig(config, k("service-advertisement"))
  val secondaryConfig = new ServiceSecondaryConfig(config.getConfig(k("secondary")))
  val instance = config.getString(k("instance"))
  val collocation = new CollocationConfig(config, k("collocation"))
  val metrics = config.getConfig(k("metrics"))
  val watcherId = UUID.fromString(config.getString(k("watcher-id")))
  val claimTimeout = config.getDuration(k("claim-timeout"), MILLISECONDS).longValue.millis
  val maxRetries = config.getInt(k("max-retries"))
  val maxReplays = try { Some(config.getInt(k("max-replays"))) } catch { case _: ConfigException.Missing => None }
  val backoffInterval = config.getDuration(k("backoff-interval"), MILLISECONDS).longValue.millis
  val maxReplayWait = config.getDuration(k("max-replay-wait"), MILLISECONDS).longValue.millis
  val replayWait = config.getDuration(k("replay-wait"), MILLISECONDS).longValue.millis
  val tmpdir = new java.io.File(config.getString(k("tmpdir"))).getAbsoluteFile
  val messageProducerConfig = try { Some(new MessageProducerConfig(config, k("message-producer"))) } catch { case _: ConfigException.Missing => None }
}
