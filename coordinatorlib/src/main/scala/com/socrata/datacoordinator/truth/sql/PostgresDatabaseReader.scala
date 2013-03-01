package com.socrata.datacoordinator.truth
package sql

import com.rojoma.simplearm.Managed
import com.rojoma.simplearm.util._

import com.socrata.datacoordinator.truth.metadata.{DatasetMapReader, CopyInfo, ColumnInfo}
import com.socrata.datacoordinator.util.collection.{MutableColumnIdMap, ColumnIdMap}
import com.socrata.datacoordinator.id.ColumnId
import javax.sql.DataSource
import java.sql.{ResultSet, Connection}
import com.rojoma.simplearm.SimpleArm
import com.socrata.datacoordinator.truth.loader.sql.RepBasedDatasetExtractor

// Does this need to be *Postgres*, or is all postgres-specific stuff encapsulated in its paramters?
class PostgresDatabaseReader[CT, CV](dataSource: DataSource,
                                     mapReaderFactory: Connection => DatasetMapReader,
                                     repFor: ColumnInfo => SqlColumnReadRep[CT, CV])
  extends LowLevelDatabaseReader[CV]
{
  private class S(conn: Connection) extends ReadContext {
    val datasetMap: DatasetMapReader = mapReaderFactory(conn)

    def loadDataset(datasetName: String, latest: Boolean): Option[(CopyInfo, ColumnIdMap[ColumnInfo])] = {
      val map = datasetMap
      for {
        datasetId <- map.datasetId(datasetName)
        datasetInfo <- map.datasetInfo(datasetId)
        copyInfo <- if(latest) Some(map.latest(datasetInfo)) else map.published(datasetInfo)
      } yield (copyInfo, map.schema(copyInfo))
    }

    def withRows[A](ci: CopyInfo, schema: ColumnIdMap[ColumnInfo], f: (Iterator[ColumnIdMap[CV]]) => A): A =
      new RepBasedDatasetExtractor(conn, ci.dataTableName, schema.mapValuesStrict(repFor)).allRows.map(f)
  }

  def openDatabase: Managed[ReadContext] = new SimpleArm[ReadContext] {
    def flatMap[A](f: ReadContext => A): A =
      using(dataSource.getConnection()) { conn =>
        conn.setAutoCommit(false)
        conn.setReadOnly(true)
        f(new S(conn))
      }
  }
}