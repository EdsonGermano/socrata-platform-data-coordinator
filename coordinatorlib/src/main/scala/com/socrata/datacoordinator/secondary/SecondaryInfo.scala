package com.socrata.datacoordinator.secondary

import java.sql.ResultSet

import org.joda.time.DateTime

case class SecondaryConfigInfo(storeId: String, nextRunTime: DateTime, runIntervalSeconds: Int)
object SecondaryConfigInfo {
  def apply(rs: ResultSet): SecondaryConfigInfo =
    SecondaryConfigInfo(rs.getString("store_id"),
      new DateTime(rs.getTimestamp("next_run_time").getTime),
      rs.getInt("interval_in_seconds"))
}

trait SecondaryConfig {
  def list: Set[SecondaryConfigInfo]
  def lookup(storeId: String): Option[SecondaryConfigInfo]
  def create(secondaryInfo: SecondaryConfigInfo): SecondaryConfigInfo
  def updateNextRunTime(storeId: String, newNextRunTime: DateTime)
}
