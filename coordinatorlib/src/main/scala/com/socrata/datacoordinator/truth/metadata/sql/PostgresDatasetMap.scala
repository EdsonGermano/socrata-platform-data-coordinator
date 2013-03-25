package com.socrata.datacoordinator
package truth.metadata
package sql

import scala.collection.immutable.VectorBuilder

import java.sql.{PreparedStatement, Connection}

import org.postgresql.util.PSQLException
import com.rojoma.simplearm.util._

import com.socrata.datacoordinator.truth.{DatasetSystemIdInUseByWriterException, DatasetIdInUseByWriterException}
import com.socrata.datacoordinator.id._
import com.socrata.datacoordinator.util.{TimingReport, PostgresUniqueViolation}
import com.socrata.datacoordinator.util.collection.MutableColumnIdMap
import com.socrata.datacoordinator.truth.metadata.CopyPair
import com.socrata.datacoordinator.truth.metadata.`-impl`.Tag
import scala.concurrent.duration.Duration

trait BasePostgresDatasetMapReader extends `-impl`.BaseDatasetMapReader {
  implicit def tag: Tag = null

  val conn: Connection
  def t: TimingReport

  def snapshotCountQuery = "SELECT count(system_id) FROM copy_map WHERE dataset_system_id = ? AND lifecycle_stage = CAST(? AS dataset_lifecycle_stage)"
  def snapshotCount(dataset: DatasetInfo) =
    using(conn.prepareStatement(snapshotCountQuery)) { stmt =>
      stmt.setLong(1, dataset.systemId.underlying)
      stmt.setString(2, LifecycleStage.Snapshotted.name)
      using(t("shapshot-count", "dataset_id" -> dataset.systemId)(stmt.executeQuery())) { rs =>
        rs.next()
        rs.getInt(1)
      }
    }

  def latestQuery = "SELECT system_id, copy_number, lifecycle_stage :: TEXT, data_version FROM copy_map WHERE dataset_system_id = ? AND lifecycle_stage <> 'Discarded' ORDER BY copy_number DESC LIMIT 1"
  def latest(datasetInfo: DatasetInfo) =
    using(conn.prepareStatement(latestQuery)) { stmt =>
      stmt.setLong(1, datasetInfo.systemId.underlying)
      using(t("latest-copy", "dataset_id" -> datasetInfo.systemId)(stmt.executeQuery())) { rs =>
        if(!rs.next()) sys.error("Looked up a table for " + datasetInfo.datasetName + " but didn't find any copy info?")
        CopyInfo(
          datasetInfo,
          new CopyId(rs.getLong("system_id")),
          rs.getLong("copy_number"),
          LifecycleStage.valueOf(rs.getString("lifecycle_stage")),
          rs.getLong("data_version")
        )
      }
    }

  def allCopiesQuery = "SELECT system_id, copy_number, lifecycle_stage :: TEXT, data_version FROM copy_map WHERE dataset_system_id = ? ORDER BY copy_number"
  def allCopies(datasetInfo: DatasetInfo): Vector[CopyInfo] =
    using(conn.prepareStatement(allCopiesQuery)) { stmt =>
      stmt.setLong(1, datasetInfo.systemId.underlying)
      using(t("all-copies", "dataset_id" -> datasetInfo.systemId)(stmt.executeQuery())) { rs =>
        val result = new VectorBuilder[CopyInfo]
        while(rs.next()) {
          result += CopyInfo(
            datasetInfo,
            new CopyId(rs.getLong("system_id")),
            rs.getLong("copy_number"),
            LifecycleStage.valueOf(rs.getString("lifecycle_stage")),
            rs.getLong("data_version")
          )
        }
        result.result()
      }
    }

  def lookupQuery = "SELECT system_id, copy_number, data_version FROM copy_map WHERE dataset_system_id = ? AND lifecycle_stage = CAST(? AS dataset_lifecycle_stage) ORDER BY copy_number DESC OFFSET ? LIMIT 1"
  def lookup(datasetInfo: DatasetInfo, stage: LifecycleStage, nth: Int = 0): Option[CopyInfo] = {
    using(conn.prepareStatement(lookupQuery)) { stmt =>
      stmt.setLong(1, datasetInfo.systemId.underlying)
      stmt.setString(2, stage.name)
      stmt.setInt(3, nth)
      using(t("lookup-copy","dataset_id" -> datasetInfo.systemId,"lifecycle-stage"->stage,"n" -> nth)(stmt.executeQuery())) { rs =>
        if(rs.next()) {
          Some(CopyInfo(datasetInfo, new CopyId(rs.getLong("system_id")), rs.getLong("copy_number"), stage, rs.getLong("data_version")))
        } else {
          None
        }
      }
    }
  }

  def previousVersionQuery = "SELECT system_id, copy_number, lifecycle_stage :: TEXT, data_version FROM copy_map WHERE dataset_system_id = ? AND copy_number < ? AND lifecycle_stage <> 'Discarded' ORDER BY copy_number DESC LIMIT 1"
  def previousVersion(copyInfo: CopyInfo): Option[CopyInfo] = {
    using(conn.prepareStatement(previousVersionQuery)) { stmt =>
      stmt.setLong(1, copyInfo.datasetInfo.systemId.underlying)
      stmt.setLong(2, copyInfo.copyNumber)
      using(t("previous-version","dataset_id" -> copyInfo.datasetInfo.systemId,"copy_num" -> copyInfo.copyNumber)(stmt.executeQuery())) { rs =>
        if(rs.next()) {
          Some(CopyInfo(
            copyInfo.datasetInfo,
            new CopyId(rs.getLong("system_id")),
            rs.getLong("copy_number"),
            LifecycleStage.valueOf(rs.getString("lifecycle_stage")),
            rs.getLong("data_version")))
        } else {
          None
        }
      }
    }
  }

  def copyNumberQuery = "SELECT system_id, lifecycle_stage, data_version FROM copy_map WHERE dataset_system_id = ? AND copy_number = ?"
  def copyNumber(datasetInfo: DatasetInfo, copyNumber: Long): Option[CopyInfo] =
    using(conn.prepareStatement(copyNumberQuery)) { stmt =>
      stmt.setLong(1, datasetInfo.systemId.underlying)
      stmt.setLong(2, copyNumber)
      using(t("copy-by-number", "dataset_id" -> datasetInfo.systemId, "copy_num" -> copyNumber)(stmt.executeQuery())) { rs =>
        if(rs.next()) {
          Some(CopyInfo(
            datasetInfo,
            new CopyId(rs.getLong("system_id")),
            copyNumber,
            LifecycleStage.valueOf(rs.getString("lifecycle_stage")),
            rs.getLong("data_version")
          ))
        } else {
          None
        }
      }
    }

  def schemaQuery = "SELECT system_id, logical_column, type_name, physical_column_base_base, (is_system_primary_key IS NOT NULL) is_system_primary_key, (is_user_primary_key IS NOT NULL) is_user_primary_key FROM column_map WHERE copy_system_id = ?"
  def schema(copyInfo: CopyInfo) = {
    using(conn.prepareStatement(schemaQuery)) { stmt =>
      stmt.setLong(1, copyInfo.systemId.underlying)
      using(t("schema-lookup","dataset_id"->copyInfo.datasetInfo.systemId,"copy_num"->copyInfo.copyNumber)(stmt.executeQuery())) { rs =>
        val result = new MutableColumnIdMap[ColumnInfo]
        while(rs.next()) {
          val systemId = new ColumnId(rs.getLong("system_id"))
          result += systemId -> ColumnInfo(
            copyInfo,
            systemId,
            rs.getString("logical_column"),
            rs.getString("type_name"),
            rs.getString("physical_column_base_base"),
            rs.getBoolean("is_system_primary_key"),
            rs.getBoolean("is_user_primary_key"))
        }
        result.freeze()
      }
    }
  }

  // These are from the reader trait but they're used in the writer tests
  def unpublished(datasetInfo: DatasetInfo) =
    lookup(datasetInfo, LifecycleStage.Unpublished)

  def published(datasetInfo: DatasetInfo) =
    lookup(datasetInfo, LifecycleStage.Published)

  def snapshot(datasetInfo: DatasetInfo, age: Int) =
    lookup(datasetInfo, LifecycleStage.Snapshotted, age)

  def datasetIdByUserIdQuery = "SELECT system_id FROM dataset_map WHERE dataset_name = ?"
  def datasetId(datasetId: String) =
    using(conn.prepareStatement(datasetIdByUserIdQuery)) { stmt =>
      stmt.setString(1, datasetId)
      using(t("dataset-id-by-name","name"->datasetId)(stmt.executeQuery())) { rs =>
        if(rs.next()) Some(new DatasetId(rs.getLong(1)))
        else None
      }
    }
}

class PostgresDatasetMapReader(val conn: Connection, timingReport: TimingReport) extends DatasetMapReader with BasePostgresDatasetMapReader {
  def t = timingReport

  def datasetInfoBySystemIdQuery = "SELECT system_id, dataset_name, table_base_base, next_row_id FROM dataset_map WHERE system_id = ?"
  def datasetInfo(datasetId: DatasetId) =
    using(conn.prepareStatement(datasetInfoBySystemIdQuery)) { stmt =>
      stmt.setLong(1, datasetId.underlying)
      using(t("lookup-dataset", "dataset_id" -> datasetId)(stmt.executeQuery())) { rs =>
        if(rs.next()) {
          Some(DatasetInfo(new DatasetId(rs.getLong("system_id")), rs.getString("dataset_name"), rs.getString("table_base_base"), new RowId(rs.getLong("next_row_id"))))
        } else {
          None
        }
      }
    }
}

trait BasePostgresDatasetMapWriter extends BasePostgresDatasetMapReader with `-impl`.BaseDatasetMapWriter {
  def lockNotAvailableState = "55P03"

  def createQuery_tableMap = "INSERT INTO dataset_map (dataset_name, table_base_base, next_row_id) VALUES (?, ?, ?) RETURNING system_id"
  def createQuery_copyMap = "INSERT INTO copy_map (dataset_system_id, copy_number, lifecycle_stage, data_version) VALUES (?, ?, CAST(? AS dataset_lifecycle_stage), ?) RETURNING system_id"
  def create(datasetId: String, tableBaseBase: String): CopyInfo = {
    val datasetInfo = using(conn.prepareStatement(createQuery_tableMap)) { stmt =>
      val datasetInfoNoSystemId = DatasetInfo(new DatasetId(-1), datasetId, tableBaseBase, RowId.initial)
      stmt.setString(1, datasetInfoNoSystemId.datasetName)
      stmt.setString(2, datasetInfoNoSystemId.tableBaseBase)
      stmt.setLong(3, datasetInfoNoSystemId.nextRowId.underlying)
      try {
        using(t("create-dataset", "dataset_name" -> datasetId)(stmt.executeQuery())) { rs =>
          val foundSomething = rs.next()
          assert(foundSomething, "INSERT didn't return a system id?")
          datasetInfoNoSystemId.copy(systemId = new DatasetId(rs.getLong(1)))
        }
      } catch {
        case PostgresUniqueViolation("dataset_name") =>
          throw new DatasetAlreadyExistsException(datasetId)
      }
    }

    using(conn.prepareStatement(createQuery_copyMap)) { stmt =>
      val copyInfoNoSystemId = CopyInfo(datasetInfo, new CopyId(-1), 1, LifecycleStage.Unpublished, 0)

      stmt.setLong(1, copyInfoNoSystemId.datasetInfo.systemId.underlying)
      stmt.setLong(2, copyInfoNoSystemId.copyNumber)
      stmt.setString(3, copyInfoNoSystemId.lifecycleStage.name)
      stmt.setLong(4, copyInfoNoSystemId.dataVersion)
      using(t("create-initial-copy", "dataset_id" -> datasetInfo.systemId)(stmt.executeQuery())) { rs =>
        val foundSomething = rs.next()
        assert(foundSomething, "Didn't return a system ID?")
        copyInfoNoSystemId.copy(systemId = new CopyId(rs.getLong(1)))
      }
    }
  }

  def createQuery_copyMapWithSystemId = "INSERT INTO copy_map (system_id, dataset_system_id, copy_number, lifecycle_stage, data_version) VALUES (?, ?, ?, CAST(? AS dataset_lifecycle_stage), ?)"
  def createWithId(systemId: DatasetId, datasetId: String, tableBaseBase: String, initialCopyId: CopyId): CopyInfo = {
    val datasetInfo = unsafeCreateDataset(systemId, datasetId, tableBaseBase, RowId.initial)

    using(conn.prepareStatement(createQuery_copyMapWithSystemId)) { stmt =>
      val copyInfo = CopyInfo(datasetInfo, initialCopyId, 1, LifecycleStage.Unpublished, 0)

      stmt.setLong(1, copyInfo.systemId.underlying)
      stmt.setLong(2, copyInfo.datasetInfo.systemId.underlying)
      stmt.setLong(3, copyInfo.copyNumber)
      stmt.setString(4, copyInfo.lifecycleStage.name)
      stmt.setLong(5, copyInfo.dataVersion)
      try {
        t("create-create-copy-with-system-id", "dataset_id" -> systemId, "copy_id" -> initialCopyId)(stmt.execute())
      } catch {
        case PostgresUniqueViolation("system_id") =>
          throw new CopySystemIdAlreadyInUse(initialCopyId)
      }

      copyInfo
    }
  }

  // Yay no "DELETE ... CASCADE"!
  def deleteQuery_columnMap = "DELETE FROM column_map WHERE copy_system_id IN (SELECT system_id FROM copy_map WHERE dataset_system_id = ?)"
  def deleteQuery_copyMap = "DELETE FROM copy_map WHERE dataset_system_id = ?"
  def deleteQuery_tableMap = "DELETE FROM dataset_map WHERE system_id = ?"
  def delete(tableInfo: DatasetInfo) {
    deleteCopiesOf(tableInfo)
    using(conn.prepareStatement(deleteQuery_tableMap)) { stmt =>
      stmt.setLong(1, tableInfo.systemId.underlying)
      val count = t("delete-dataset", "dataset_id" -> tableInfo.systemId)(stmt.executeUpdate())
      assert(count == 1, "Called delete on a table which is no longer there?")
    }
  }

  def deleteCopiesOf(datasetInfo: DatasetInfo) {
    using(conn.prepareStatement(deleteQuery_columnMap)) { stmt =>
      stmt.setLong(1, datasetInfo.systemId.underlying)
      t("delete-dataset-columns", "dataset_id" -> datasetInfo.systemId)(stmt.executeUpdate())
    }
    using(conn.prepareStatement(deleteQuery_copyMap)) { stmt =>
      stmt.setLong(1, datasetInfo.systemId.underlying)
      t("delete-dataset-copies", "dataset_id" -> datasetInfo.systemId)(stmt.executeUpdate())
    }
  }

  // like file descriptors, new columns always get the smallest available ID.  But "smallest available"
  // means "not used by this version OR THE PREVIOUS VERSION" so we can track column identity across
  // publication cycles.
  def findFirstFreeColumnIdQuery =
    """-- Adapted from http://johtopg.blogspot.com/2010/07/smallest-available-id.html
      |-- Use zero if available
      |(SELECT
      |    0 AS next_system_id
      | WHERE
      |    NOT EXISTS
      |        (SELECT 1 FROM column_map WHERE system_id = 0 AND (copy_system_id = ? OR copy_system_id = ?)) )
      |
      |    UNION ALL
      |
      |-- Find the smallest available ID inside a gap, or max + 1
      |-- if there are no gaps.
      |(SELECT
      |    system_id + 1 AS next_system_id
      | FROM
      | (
      |    SELECT
      |        system_id, lead(system_id) OVER (ORDER BY system_id)
      |    FROM
      |        column_map
      |    WHERE
      |        (copy_system_id = ? OR copy_system_id = ?)
      | ) ss
      | WHERE
      |    lead - system_id > 1 OR
      |    lead IS NULL
      | ORDER BY
      |    system_id
      | LIMIT
      |    1)
      |
      |ORDER BY
      |    next_system_id
      |LIMIT
      |    1
      |""".stripMargin
  def findFirstFreeColumnId(copyInfoA: CopyInfo, copyInfoB: CopyInfo): ColumnId = {
    using(conn.prepareStatement(findFirstFreeColumnIdQuery)) { stmt =>
      stmt.setLong(1, copyInfoA.systemId.underlying)
      stmt.setLong(2, copyInfoB.systemId.underlying)
      stmt.setLong(3, copyInfoA.systemId.underlying)
      stmt.setLong(4, copyInfoB.systemId.underlying)
      using(t("find-first-free-column-id", "dataset_id" -> copyInfoA.datasetInfo.systemId, "copy_num_a" -> copyInfoA.copyNumber, "copy_num_b" -> copyInfoB.copyNumber)(stmt.executeQuery())) { rs =>
        val foundSomething = rs.next()
        assert(foundSomething, "Finding the last column info didn't return anything?")
        new ColumnId(rs.getLong("next_system_id"))
      }
    }
  }

  def addColumnQuery = "INSERT INTO column_map (system_id, copy_system_id, logical_column, type_name, physical_column_base_base) VALUES (?, ?, ?, ?, ?)"
  def addColumn(copyInfo: CopyInfo, logicalName: String, typeName: String, physicalColumnBaseBase: String): ColumnInfo = {
    val systemId =
      previousVersion(copyInfo) match {
        case Some(previousCopy) =>
          findFirstFreeColumnId(copyInfo, previousCopy)
        case None =>
          findFirstFreeColumnId(copyInfo, copyInfo)
      }

    addColumnWithId(systemId, copyInfo, logicalName, typeName, physicalColumnBaseBase)
  }

  def addColumnWithId(systemId: ColumnId, copyInfo: CopyInfo, logicalName: String, typeName: String, physicalColumnBaseBase: String): ColumnInfo = {
    using(conn.prepareStatement(addColumnQuery)) { stmt =>
      val columnInfo = ColumnInfo(copyInfo, systemId, logicalName, typeName, physicalColumnBaseBase, isSystemPrimaryKey = false, isUserPrimaryKey = false)

      stmt.setLong(1, columnInfo.systemId.underlying)
      stmt.setLong(2, columnInfo.copyInfo.systemId.underlying)
      stmt.setString(3, logicalName)
      stmt.setString(4, typeName)
      stmt.setString(5, physicalColumnBaseBase)
      try {
        t("add-column-with-id", "dataset_id" -> copyInfo.datasetInfo.systemId, "copy_num" -> copyInfo.copyNumber, "column_id" -> systemId)(stmt.execute())
      } catch {
        case PostgresUniqueViolation("copy_system_id", "system_id") =>
          throw new ColumnSystemIdAlreadyInUse(copyInfo, systemId)
        case PostgresUniqueViolation("copy_system_id", "logical_column") =>
          throw new ColumnAlreadyExistsException(copyInfo, logicalName)
      }

      columnInfo
    }
  }

  def unsafeCreateDatasetQuery = "INSERT INTO dataset_map (system_id, dataset_name, table_base_base, next_row_id) VALUES (?, ?, ?, ?)"
  def unsafeCreateDataset(systemId: DatasetId, datasetId: String, tableBaseBase: String, nextRowId: RowId): DatasetInfo = {
    val datasetInfo = DatasetInfo(systemId, datasetId, tableBaseBase, nextRowId)

    using(conn.prepareStatement(unsafeCreateDatasetQuery)) { stmt =>
      stmt.setLong(1, datasetInfo.systemId.underlying)
      stmt.setString(2, datasetInfo.datasetName)
      stmt.setString(3, datasetInfo.tableBaseBase)
      stmt.setLong(4, datasetInfo.nextRowId.underlying)
      try {
        t("unsafe-create-dataset", "dataset_id" -> systemId)(stmt.execute())
        stmt.execute()
      } catch {
        case PostgresUniqueViolation("system_id") =>
          throw new DatasetSystemIdAlreadyInUse(systemId)
        case PostgresUniqueViolation("dataset_name") =>
          throw new DatasetAlreadyExistsException(datasetId)
      }
    }

    datasetInfo
  }

  val unsafeReloadDatasetQuery = "UPDATE dataset_map SET dataset_name = ?, table_base_base = ?, next_row_id = ? WHERE system_id = ?"
  def unsafeReloadDataset(datasetInfo: DatasetInfo,
                          datasetId: String,
                          tableBaseBase: String,
                          nextRowId: RowId): DatasetInfo = {
    val newDatasetInfo = DatasetInfo(datasetInfo.systemId, datasetId, tableBaseBase, nextRowId)

    using(conn.prepareStatement(unsafeReloadDatasetQuery)) { stmt =>
      stmt.setString(1, newDatasetInfo.datasetName)
      stmt.setString(2, newDatasetInfo.tableBaseBase)
      stmt.setLong(3, newDatasetInfo.nextRowId.underlying)
      stmt.setLong(4, newDatasetInfo.systemId.underlying)
      try {
        val updated = t("unsafe-reload-dataset", "dataset_id" -> datasetInfo.systemId)(stmt.executeUpdate())
        assert(updated == 1, s"Dataset ${datasetInfo.systemId.underlying} does not exist?")
      } catch {
        case PostgresUniqueViolation("dataset_name") =>
          throw new DatasetAlreadyExistsException(datasetId)
      }
    }

    deleteCopiesOf(newDatasetInfo)

    newDatasetInfo
  }

  def unsafeCreateCopyQuery = "INSERT INTO copy_map (system_id, dataset_system_id, copy_number, lifecycle_stage, data_version) values (?, ?, ?, CAST(? AS dataset_lifecycle_stage), ?)"
  def unsafeCreateCopy(datasetInfo: DatasetInfo,
                       systemId: CopyId,
                       copyNumber: Long,
                       lifecycleStage: LifecycleStage,
                       dataVersion: Long): CopyInfo = {
    val newCopy = CopyInfo(datasetInfo, systemId, copyNumber, lifecycleStage, dataVersion)

    using(conn.prepareStatement(unsafeCreateCopyQuery)) { stmt =>
      stmt.setLong(1, newCopy.systemId.underlying)
      stmt.setLong(2, newCopy.datasetInfo.systemId.underlying)
      stmt.setLong(3, newCopy.copyNumber)
      stmt.setString(4, newCopy.lifecycleStage.name)
      stmt.setLong(5, newCopy.dataVersion)
      try {
        t("unsafe-create-copy", "dataset_id" -> datasetInfo.systemId, "copy_num" -> copyNumber)(stmt.execute())
      } catch {
        case PostgresUniqueViolation("system_id") =>
          throw new CopySystemIdAlreadyInUse(systemId)
      }
    }

    newCopy
  }

  def dropColumnQuery = "DELETE FROM column_map WHERE copy_system_id = ? AND system_id = ?"
  def dropColumn(columnInfo: ColumnInfo) {
    using(conn.prepareStatement(dropColumnQuery)) { stmt =>
      stmt.setLong(1, columnInfo.copyInfo.systemId.underlying)
      stmt.setLong(2, columnInfo.systemId.underlying)
      val count = t("drop-column", "dataset_id" -> columnInfo.copyInfo.datasetInfo.systemId, "copy_num" -> columnInfo.copyInfo.copyNumber, "column_id" -> columnInfo.systemId)(stmt.executeUpdate())
      assert(count == 1, "Column did not exist to be dropped?")
    }
  }

  def renameColumnQuery = "UPDATE column_map SET logical_column = ? WHERE copy_system_id = ? AND system_id = ?"
  def renameColumn(columnInfo: ColumnInfo, newLogicalName: String): ColumnInfo =
    using(conn.prepareStatement(renameColumnQuery)) { stmt =>
      stmt.setString(1, newLogicalName)
      stmt.setLong(2, columnInfo.copyInfo.systemId.underlying)
      stmt.setLong(3, columnInfo.systemId.underlying)
      val count = t("rename-column", "dataset_id" -> columnInfo.copyInfo.datasetInfo.systemId, "copy_num" -> columnInfo.copyInfo.copyNumber, "column_id" -> columnInfo.systemId)(stmt.executeUpdate())
      assert(count == 1, "Column did not exist to be renamed?")
      columnInfo.copy(logicalName =  newLogicalName)
    }

  def convertColumnQuery = "UPDATE column_map SET type_name = ?, physical_column_base_base = ? WHERE copy_system_id = ? AND system_id = ?"
  def convertColumn(columnInfo: ColumnInfo, newType: String, newPhysicalColumnBaseBase: String): ColumnInfo =
    using(conn.prepareStatement(convertColumnQuery)) { stmt =>
      stmt.setString(1, newType)
      stmt.setString(2, newPhysicalColumnBaseBase)
      stmt.setLong(3, columnInfo.copyInfo.systemId.underlying)
      stmt.setLong(4, columnInfo.systemId.underlying)
      val count = t("convert-column", "dataset_id" -> columnInfo.copyInfo.datasetInfo.systemId, "copy_num" -> columnInfo.copyInfo.copyNumber, "column_id" -> columnInfo.systemId)(stmt.executeUpdate())
      assert(count == 1, "Column did not exist to be converted?")
      columnInfo.copy(typeName = newType, physicalColumnBaseBase = newPhysicalColumnBaseBase)
    }

  def setSystemPrimaryKeyQuery = "UPDATE column_map SET is_system_primary_key = 'Unit' WHERE copy_system_id = ? AND system_id = ?"
  def setSystemPrimaryKey(columnInfo: ColumnInfo) =
    using(conn.prepareStatement(setSystemPrimaryKeyQuery)) { stmt =>
      stmt.setLong(1, columnInfo.copyInfo.systemId.underlying)
      stmt.setLong(2, columnInfo.systemId.underlying)
      val count = t("set-system-primary-key", "dataset_id" -> columnInfo.copyInfo.datasetInfo.systemId, "copy_num" -> columnInfo.copyInfo.copyNumber, "column_id" -> columnInfo.systemId)(stmt.executeUpdate())
      assert(count == 1, "Column did not exist to have it set as primary key?")
      columnInfo.copy(isSystemPrimaryKey = true)
    }

  def setUserPrimaryKeyQuery = "UPDATE column_map SET is_user_primary_key = 'Unit' WHERE copy_system_id = ? AND system_id = ?"
  def setUserPrimaryKey(columnInfo: ColumnInfo) =
    using(conn.prepareStatement(setUserPrimaryKeyQuery)) { stmt =>
      stmt.setLong(1, columnInfo.copyInfo.systemId.underlying)
      stmt.setLong(2, columnInfo.systemId.underlying)
      val count = t("set-user-primary-key", "dataset_id" -> columnInfo.copyInfo.datasetInfo.systemId, "copy_num" -> columnInfo.copyInfo.copyNumber, "column_id" -> columnInfo.systemId)(stmt.executeUpdate())
      assert(count == 1, "Column did not exist to have it set as primary key?")
      columnInfo.copy(isUserPrimaryKey = true)
    }

  def clearUserPrimaryKeyQuery = "UPDATE column_map SET is_user_primary_key = NULL WHERE copy_system_id = ? and system_id = ?"
  def clearUserPrimaryKey(columnInfo: ColumnInfo) {
    require(columnInfo.isUserPrimaryKey, "Requested clearing a non-primary key")
    using(conn.prepareStatement(clearUserPrimaryKeyQuery)) { stmt =>
      stmt.setLong(1, columnInfo.copyInfo.systemId.underlying)
      stmt.setLong(2, columnInfo.systemId.underlying)
      stmt.executeUpdate()
    }
  }

  def updateNextRowIdQuery = "UPDATE dataset_map SET next_row_id = ? WHERE system_id = ?"
  def updateNextRowId(datasetInfo: DatasetInfo, newNextRowId: RowId): DatasetInfo = {
    assert(newNextRowId.underlying >= datasetInfo.nextRowId.underlying)
    if(newNextRowId != datasetInfo.nextRowId) {
      using(conn.prepareStatement(updateNextRowIdQuery)) { stmt =>
        stmt.setLong(1, newNextRowId.underlying)
        stmt.setLong(2, datasetInfo.systemId.underlying)
        t("update-next-row-id", "dataset_id" -> datasetInfo.systemId)(stmt.executeUpdate())
      }
      datasetInfo.copy(nextRowId = newNextRowId)
    } else {
      datasetInfo
    }
  }

  def updateNextRowId(copyInfo: CopyInfo, newNextRowId: RowId): CopyInfo =
    copyInfo.copy(datasetInfo = updateNextRowId(copyInfo.datasetInfo, newNextRowId))

  def updateDataVersionQuery = "UPDATE copy_map SET data_version = ? WHERE system_id = ?"
  def updateDataVersion(copyInfo: CopyInfo, newDataVersion: Long): CopyInfo = {
    // Not "== copyInfo.dataVersion + 1" because if a working copy was dropped
    assert(newDataVersion > copyInfo.dataVersion, s"Setting data version to $newDataVersion when it was ${copyInfo.dataVersion}")
    using(conn.prepareStatement(updateDataVersionQuery)) { stmt =>
      stmt.setLong(1, newDataVersion)
      stmt.setLong(2, copyInfo.systemId.underlying)
      val count = t("update-data-version", "dataset_id" -> copyInfo.datasetInfo.systemId, "copy_num" -> copyInfo.copyNumber)(stmt.executeUpdate())
      assert(count == 1)
    }
    copyInfo.copy(dataVersion = newDataVersion)
  }

  def dropCopyQuery = "UPDATE copy_map SET lifecycle_stage = 'Discarded' WHERE system_id = ? AND lifecycle_stage = CAST(? AS dataset_lifecycle_stage)"
  def dropCopy(copyInfo: CopyInfo) {
    if(copyInfo.lifecycleStage != LifecycleStage.Snapshotted && copyInfo.lifecycleStage != LifecycleStage.Unpublished) {
      throw new IllegalArgumentException("Can only drop a snapshot or an unpublished copy of a dataset.")
    }
    if(copyInfo.lifecycleStage == LifecycleStage.Unpublished && copyInfo.copyNumber == 1) {
      throw new IllegalArgumentException("Cannot drop the initial version")
    }

    using(conn.prepareStatement(dropCopyQuery)) { stmt =>
      stmt.setLong(1, copyInfo.systemId.underlying)
      stmt.setString(2, copyInfo.lifecycleStage.name) // just to make sure the user wasn't mistaken about the stage
      val count = t("drop-copy", "dataset_id" -> copyInfo.datasetInfo.systemId, "copy_num" -> copyInfo.copyNumber)(stmt.executeUpdate())
      assert(count == 1, "Copy did not exist to be dropped?")
    }
  }

  def ensureUnpublishedCopyQuery_newCopyNumber = "SELECT max(copy_number) + 1 FROM copy_map WHERE dataset_system_id = ?"
  def ensureUnpublishedCopyQuery_copyMap = "INSERT INTO copy_map (dataset_system_id, copy_number, lifecycle_stage, data_version) values (?, ?, CAST(? AS dataset_lifecycle_stage), ?) RETURNING system_id"
  def ensureUnpublishedCopy(tableInfo: DatasetInfo): Either[CopyInfo, CopyPair[CopyInfo]] =
    ensureUnpublishedCopy(tableInfo, None)

  def createUnpublishedCopyWithId(tableInfo: DatasetInfo, copyId: CopyId): CopyPair[CopyInfo] =
    ensureUnpublishedCopy(tableInfo, Some(copyId)).right.getOrElse {
      throw new CopySystemIdAlreadyInUse(copyId)
    }

  def ensureUnpublishedCopy(tableInfo: DatasetInfo, newCopyId: Option[CopyId]): Either[CopyInfo, CopyPair[CopyInfo]] =
    lookup(tableInfo, LifecycleStage.Unpublished) match {
      case Some(unpublished) =>
        Left(unpublished)
      case None =>
        lookup(tableInfo, LifecycleStage.Published) match {
          case Some(publishedCopy) =>
            val newCopyNumber = using(conn.prepareStatement(ensureUnpublishedCopyQuery_newCopyNumber)) { stmt =>
              stmt.setLong(1, publishedCopy.datasetInfo.systemId.underlying)
              using(t("find-next-copy-number","dataset_id" -> tableInfo.systemId)(stmt.executeQuery())) { rs =>
                rs.next()
                rs.getLong(1)
              }
            }

            val newCopy = newCopyId match {
              case None =>
                val newCopyWithoutSystemId = publishedCopy.copy(
                  systemId = new CopyId(-1),
                  copyNumber = newCopyNumber,
                  lifecycleStage = LifecycleStage.Unpublished)

                val newCopy = using(conn.prepareStatement(ensureUnpublishedCopyQuery_copyMap)) { stmt =>
                  stmt.setLong(1, newCopyWithoutSystemId.datasetInfo.systemId.underlying)
                  stmt.setLong(2, newCopyWithoutSystemId.copyNumber)
                  stmt.setString(3, newCopyWithoutSystemId.lifecycleStage.name)
                  stmt.setLong(4, newCopyWithoutSystemId.dataVersion)
                  using(t("create-new-copy", "dataset_id" -> newCopyWithoutSystemId.datasetInfo.systemId)(stmt.executeQuery())) { rs =>
                    val foundSomething = rs.next()
                    assert(foundSomething, "Insert didn't create a row?")
                    newCopyWithoutSystemId.copy(systemId = new CopyId(rs.getLong(1)))
                  }
                }

                copySchemaIntoUnpublishedCopy(publishedCopy, newCopy)

                newCopy
              case Some(cid) =>
                unsafeCreateCopy(
                  publishedCopy.datasetInfo,
                  cid,
                  newCopyNumber,
                  LifecycleStage.Unpublished,
                  publishedCopy.dataVersion)
            }

            Right(CopyPair(publishedCopy, newCopy))
          case None =>
            sys.error("No published copy available?")
        }
    }

  def ensureUnpublishedCopyQuery_columnMap = "INSERT INTO column_map (copy_system_id, system_id, logical_column, type_name, physical_column_base_base, is_system_primary_key, is_user_primary_key) SELECT ?, system_id, logical_column, type_name, physical_column_base_base, null, null FROM column_map WHERE copy_system_id = ?"
  def copySchemaIntoUnpublishedCopy(oldCopy: CopyInfo, newCopy: CopyInfo) {
    using(conn.prepareStatement(ensureUnpublishedCopyQuery_columnMap)) { stmt =>
      stmt.setLong(1, newCopy.systemId.underlying)
      stmt.setLong(2, oldCopy.systemId.underlying)
      t("copy-schema-to-unpublished-copy", "dataset_id" -> oldCopy.datasetInfo.systemId, "old_copy_num" -> oldCopy.copyNumber, "new_copy_num" -> newCopy.copyNumber)(stmt.execute())
    }
  }

  def publishQuery = "UPDATE copy_map SET lifecycle_stage = CAST(? AS dataset_lifecycle_stage) WHERE system_id = ?"
  def publish(unpublishedCopy: CopyInfo): CopyInfo = {
    if(unpublishedCopy.lifecycleStage != LifecycleStage.Unpublished) {
      throw new IllegalArgumentException("Input does not name an unpublished copy")
    }
    using(conn.prepareStatement(publishQuery)) { stmt =>
      for(published <- lookup(unpublishedCopy.datasetInfo, LifecycleStage.Published)) {
        stmt.setString(1, LifecycleStage.Snapshotted.name)
        stmt.setLong(2, published.systemId.underlying)
        val count = t("snapshotify-published-copy", "dataset_id" -> published.datasetInfo.systemId, "copy_num" -> published.copyNumber)(stmt.executeUpdate())
        assert(count == 1, "Snapshotting a published copy didn't change a row?")
      }
      stmt.setString(1, LifecycleStage.Published.name)
      stmt.setLong(2, unpublishedCopy.systemId.underlying)
      val count = t("publish-unpublished-copy", "dataset_id" -> unpublishedCopy.datasetInfo.systemId, "copy_num" -> unpublishedCopy.copyNumber)(stmt.executeUpdate())
      assert(count == 1, "Publishing an unpublished copy didn't change a row?")
      unpublishedCopy.copy(lifecycleStage = LifecycleStage.Published)
    }
  }
}

class PostgresDatasetMapWriter(val conn: Connection, timingReport: TimingReport) extends DatasetMapWriter with BasePostgresDatasetMapWriter with BackupDatasetMap {
  require(!conn.getAutoCommit, "Connection is in auto-commit mode")

  def t = timingReport

  def datasetInfoBySystemIdQuery = "SELECT system_id, dataset_name, table_base_base, next_row_id FROM dataset_map WHERE system_id = ? FOR UPDATE"
  def datasetInfo(datasetId: DatasetId, timeout: Duration) = {
    val savepoint = conn.setSavepoint()
    try {
      // ok.  For now we're going to assume that we're the only one setting this parameter.
      // If this turns out not to be true, we'll have to use SHOW to query the current value.
      if(timeout.isFinite()) {
        using(conn.createStatement()) { stmt =>
          stmt.execute("SET LOCAL statement_timeout TO " + timeout.toMillis)
        }
      }
      try {
        val res = using(conn.prepareStatement(datasetInfoBySystemIdQuery)) { stmt =>
          stmt.setLong(1, datasetId.underlying)
          using(t("lookup-dataset-for-update", "dataset_id" -> datasetId)(stmt.executeQuery())) { rs =>
            if(rs.next()) {
              Some(DatasetInfo(new DatasetId(rs.getLong("system_id")), rs.getString("dataset_name"), rs.getString("table_base_base"), new RowId(rs.getLong("next_row_id"))))
            } else {
              None
            }
          }
        }
        if(timeout.isFinite()) {
          using(conn.createStatement()) { stmt =>
            stmt.execute("SET LOCAL statement_timeout TO DEFAULT")
          }
        }
        res
      } catch {
        case e: PSQLException =>
          println(e.getSQLState)
          conn.rollback(savepoint)
          throw new DatasetSystemIdInUseByWriterException(datasetId, e)
      }
    } finally {
      conn.releaseSavepoint(savepoint)
    }
  }
}
