package com.socrata.datacoordinator
package truth.loader
package sql

import scala.collection.JavaConverters._

import com.socrata.datacoordinator.truth.{RowIdMap, DatasetContext}
import com.socrata.datacoordinator.util.collection.LongLikeMap

class TestDatasetContext(val userSchema: LongLikeMap[ColumnId, TestColumnType], val systemIdColumnName: ColumnId, val userPrimaryKeyColumn: Option[ColumnId]) extends DatasetContext[TestColumnType, TestColumnValue] {
  userPrimaryKeyColumn.foreach { pkCol =>
    require(userSchema.contains(pkCol), "PK col defined but does not exist in the schema")
  }

  def hasCopy = sys.error("hasCopy called")

  def userPrimaryKey(row: Row[TestColumnValue]) = for {
    userPKColumn <- userPrimaryKeyColumn
    value <- row.get(userPKColumn)
  } yield value

  def systemId(row: Row[TestColumnValue]) =
    row.get(systemIdColumnName).map(_.asInstanceOf[LongValue].value)

  def systemIdAsValue(row: Row[TestColumnValue]) = row.get(systemIdColumnName)

  def systemColumns(row: Row[TestColumnValue]) = row.keySet.filter(systemSchema.contains).toSet
  val systemSchema = LongLikeMap(systemIdColumnName -> LongColumn)

  val fullSchema = userSchema ++ systemSchema

  def mergeRows(a: Row[TestColumnValue], b: Row[TestColumnValue]) = a ++ b

  def makeIdMap[V]() = {
    require(hasUserPrimaryKey)
    new RowIdMap[TestColumnValue, V] {
      val m = new java.util.HashMap[TestColumnValue, V]
      def put(x: TestColumnValue, v: V) { m.put(x, v) }
      def apply(x: TestColumnValue) = { val r = m.get(x); if(r == null) throw new NoSuchElementException; r }
      def contains(x: TestColumnValue) = m.containsKey(x)

      def get(x: TestColumnValue) = Option(m.get(x))

      def clear() { m.clear() }

      def isEmpty = m.isEmpty

      def size = m.size

      def foreach(f: (TestColumnValue, V) => Unit) {
        val it = m.entrySet.iterator
        while(it.hasNext) {
          val ent = it.next()
          f(ent.getKey, ent.getValue)
        }
      }

      def valuesIterator = m.values().iterator.asScala
    }
  }
}
