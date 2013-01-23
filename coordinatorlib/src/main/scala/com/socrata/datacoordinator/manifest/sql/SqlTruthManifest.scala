package com.socrata.datacoordinator
package manifest
package sql

import java.sql.Connection

import com.rojoma.simplearm.util._

import com.socrata.datacoordinator.truth.metadata.DatasetInfo

class SqlTruthManifest(conn: Connection) extends TruthManifest {
  def create(dataset: DatasetInfo) {
    using(conn.prepareStatement("INSERT INTO truth_manifest (dataset_system_id, published_version, latest_version) VALUES (?, 0, 0)")) { stmt =>
      stmt.setLong(1, dataset.systemId.underlying)
      stmt.execute()
    }
  }

  def updatePublishedVersion(dataset: DatasetInfo, version: Long) {
    using(conn.prepareStatement("UPDATE truth_manifest SET published_version = ? WHERE dataset_system_id = ?")) { stmt =>
      stmt.setLong(1, version)
      stmt.setLong(2, dataset.systemId.underlying)
      val count = stmt.executeUpdate()
      assert(count == 1, "updatePublishedVersion didn't update anything?")
    }
  }

  def updateLatestVersion(dataset: DatasetInfo, version: Long) {
    using(conn.prepareStatement("UPDATE truth_manifest SET latest_version = ? WHERE dataset_system_id = ?")) { stmt =>
      stmt.setLong(1, version)
      stmt.setLong(2, dataset.systemId.underlying)
      val count = stmt.executeUpdate()
      assert(count == 1, "updateLatestVersion didn't update anything?")
    }
  }

  def latestVersion(dataset: DatasetInfo): Long = {
    using(conn.prepareStatement("SELECT latest_version FROM truth_manifest WHERE dataset_system_id = ?")) { stmt =>
      stmt.setLong(1, dataset.systemId.underlying)
      using(stmt.executeQuery()) { rs =>
        val foundSomething = rs.next()
        assert(foundSomething, "No such dataset?")
        rs.getLong("latest_version")
      }
    }
  }
}
