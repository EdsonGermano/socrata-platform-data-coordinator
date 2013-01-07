package com.socrata.datacoordinator
package truth.loader.sql

import java.sql.Connection

import org.postgresql.copy.CopyManager
import org.postgresql.core.BaseConnection

import com.socrata.datacoordinator.truth.sql.RepBasedSqlDatasetContext
import com.socrata.datacoordinator.util.{ReaderWriterPair, StringBuilderReader}
import java.util.concurrent.{Callable, ExecutorService, Executor}

class PostgresRepBasedDataSqlizer[CT, CV](tableName: String,
                                          datasetContext: RepBasedSqlDatasetContext[CT, CV],
                                          executor: ExecutorService,
                                          extractCopier: Connection => CopyManager = PostgresRepBasedDataSqlizer.pgCopyManager)
  extends AbstractRepBasedDataSqlizer(tableName, datasetContext)
{
  val bulkInsertStatement =
    "COPY " + dataTableName + " (" + repSchema.values.flatMap(_.physColumnsForInsert).mkString(",") + ") from stdin with csv"

  def insertBatch(conn: Connection)(f: (Inserter) => Unit) = {
    val inserter = new InserterImpl
    val result = executor.submit(new Callable[Long] {
      def call() = {
        val copyManager = extractCopier(conn)
        copyManager.copyIn(bulkInsertStatement, inserter.rw.reader)
      }
    })
    try {
      try {
        f(inserter)
      } finally {
        inserter.rw.writer.close()
      }
      result.get()
    } catch {
      case e: Throwable =>
        // Ensure the future has completed before we leave this method.
        // We don't actually care if it failed, because we're exiting
        // abnormally.
        try { result.get() }
        catch { case e: Exception => /* ok */ }
        throw e
    }
  }

  class InserterImpl extends Inserter {
    val rw = new ReaderWriterPair(100000, 1)
    def insert(row: Row[CV]) {
      val sb = new java.lang.StringBuilder
      var didOne = false
      val it = repSchema.iterator
      while(it.hasNext) {
        val (k,v) = it.next()
        if(didOne) sb.append(',')
        else didOne = true

        val value = row.getOrElse(k, nullValue)
        v.csvifyForInsert(sb, value)
      }
      sb.append('\n')
      rw.writer.write(sb.toString)
    }

    def close() {}
  }
}

object PostgresRepBasedDataSqlizer {
  def pgCopyManager(conn: Connection) = conn.asInstanceOf[BaseConnection].getCopyAPI
}