package com.socrata.datacoordinator.truth.loader.sql

import java.util.concurrent.ExecutorService
import java.sql.Connection

import com.socrata.datacoordinator.truth.TypeContext
import com.socrata.datacoordinator.truth.metadata.{DatasetCopyContext, ColumnInfo}
import com.socrata.datacoordinator.truth.sql.{RepBasedSqlDatasetContext, SqlColumnRep}
import com.socrata.datacoordinator.truth.loader.{ReportWriter, Loader, Logger, RowPreparer}
import java.io.OutputStream
import com.socrata.datacoordinator.util.{RowIdProvider, RowVersionProvider, TransferrableContextTimingReport, RowDataProvider}

abstract class AbstractSqlLoaderProvider[CT, CV](val executor: ExecutorService, typeContext: TypeContext[CT, CV], repFor: ColumnInfo[CT] => SqlColumnRep[CT, CV], isSystemColumn: ColumnInfo[CT] => Boolean)
  extends ((Connection, DatasetCopyContext[CT], RowPreparer[CV], Boolean, RowIdProvider, RowVersionProvider, Logger[CT, CV], ReportWriter[CV], TransferrableContextTimingReport) => Loader[CV])
{
  def produce(tableName: String, datasetContext: RepBasedSqlDatasetContext[CT, CV]): DataSqlizer[CT, CV]

  def apply(conn: Connection, copyCtx: DatasetCopyContext[CT], rowPreparer: RowPreparer[CV], updateOnly: Boolean, idProvider: RowIdProvider, versionProvider: RowVersionProvider, logger: Logger[CT, CV], reportWriter: ReportWriter[CV], timingReport: TransferrableContextTimingReport) = {
    val tableName = copyCtx.copyInfo.dataTableName

    val repSchema = copyCtx.schema.mapValuesStrict(repFor)

    val userPrimaryKeyInfo = copyCtx.userIdCol.map(_.systemId)

    val systemPrimaryKey = copyCtx.systemIdCol.getOrElse {
      sys.error(s"No system primary key column?")
    }.systemId

    val version = copyCtx.versionCol.getOrElse {
      sys.error(s"No version column?")
    }.systemId

    val datasetContext =
      RepBasedSqlDatasetContext(typeContext, repSchema, userPrimaryKeyInfo, systemPrimaryKey, version, copyCtx.schema.filter { case (_, ci) => isSystemColumn(ci) }.keySet)

    val sqlizer = produce(tableName, datasetContext)
    SqlLoader(conn, rowPreparer, updateOnly, sqlizer, logger, idProvider, versionProvider, executor, reportWriter, timingReport)
  }
}

trait StandardSqlLoaderProvider[CT, CV] { this: AbstractSqlLoaderProvider[CT, CV] =>
  def produce(tableName: String, datasetContext: RepBasedSqlDatasetContext[CT, CV]) =
    new StandardRepBasedDataSqlizer(tableName, datasetContext)
}

trait PostgresSqlLoaderProvider[CT, CV] { this: AbstractSqlLoaderProvider[CT, CV] =>
  def copyIn(conn: Connection, sql: String, output: OutputStream => Unit): Long

  def produce(tableName: String, datasetContext: RepBasedSqlDatasetContext[CT, CV]) =
    new PostgresRepBasedDataSqlizer(tableName, datasetContext, copyIn)
}
