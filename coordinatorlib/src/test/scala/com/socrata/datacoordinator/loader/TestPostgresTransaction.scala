package com.socrata.datacoordinator.loader

import scala.collection.immutable.VectorBuilder

import java.sql.{Connection, DriverManager}

import org.scalatest.FunSuite
import org.scalatest.matchers.MustMatchers
import org.scalatest.prop.PropertyChecks
import com.rojoma.simplearm.util._

import com.socrata.id.numeric.{PushbackIdProvider, FixedSizeIdProvider, InMemoryBlockIdProvider}

class TestPostgresTransaction extends FunSuite with MustMatchers with PropertyChecks {
  // In Java 6 (sun and open) driver registration is not thread-safe!
  // So since SBT will run these tests in parallel, sometimes one of the
  // first tests to run will randomly fail.  By forcing the driver to
  // be loaded up front we can avoid this.
  Class.forName("org.postgresql.Driver")

  def idProvider(initial: Int) = new IdProviderPoolImpl(new InMemoryBlockIdProvider(releasable = false) { override def start = initial }, new FixedSizeIdProvider(_, 1024))

  def withDB[T]()(f: Connection => T): T = {
    using(DriverManager.getConnection("jdbc:postgresql://localhost/robertm", "blist", "blist")) { conn =>
      conn.setAutoCommit(false)
      f(conn)
    }
  }

  def execute(conn: Connection, sql: String) {
    using(conn.createStatement()) { stmt =>
      stmt.execute(sql)
    }
  }

  def query(conn: Connection, sql: String): Seq[Map[String, Any]] =
    for {
      stmt <- managed(conn.createStatement())
      rs <- managed(stmt.executeQuery(sql))
    } yield {
      val result = new VectorBuilder[Map[String, Any]]
      while(rs.next()) {
        result += (1 to rs.getMetaData.getColumnCount).foldLeft(Map.empty[String, Any]) { (row, col) =>
          row + (rs.getMetaData.getColumnLabel(col).toUpperCase -> rs.getObject(col))
        }
      }
      result.result()
    }

  def makeTables(conn: Connection, ctx: DatasetContext[TestColumnType, TestColumnValue]) {
    execute(conn, "drop table if exists " + ctx.baseName + "_data")
    execute(conn, "drop table if exists " + ctx.baseName + "_log")
    execute(conn, "CREATE TABLE " + ctx.baseName + "_data (id bigint not null primary key," + ctx.userSchema.map { case (c,t) =>
      val sqltype = t match {
        case LongColumn => "BIGINT"
        case StringColumn => "VARCHAR(100)"
      }
      "u_" + c + " " + sqltype + (if(ctx.userPrimaryKeyColumn == Some(c)) " NOT NULL" else " NULL")
    }.mkString(",") + ")")
    ctx.userPrimaryKeyColumn.foreach { pkCol =>
      execute(conn, "CREATE INDEX " + ctx.baseName + "_data_userid ON " + ctx.baseName + "_data(u_" + pkCol + ")")
    }
    // varchar rows because h2 returns a clob for TEXT columns instead of a string
    execute(conn, "CREATE TABLE " + ctx.baseName + "_log (id bigint not null primary key, rows varchar(65536) not null, who varchar(100) null)")
  }

  def preload(conn: Connection, ctx: DatasetContext[TestColumnType, TestColumnValue])(rows: Map[String, TestColumnValue]*) {
    makeTables(conn, ctx)
    for(row <- rows) {
      val LongValue(id) = row.getOrElse(ctx.systemIdColumnName, sys.error("No :id"))
      val remainingColumns = row - ctx.systemIdColumnName
      assert(remainingColumns.keySet.subsetOf(ctx.userSchema.keySet), "row contains extraneous keys")
      val sql = "insert into " + ctx.baseName + "_data (id," + remainingColumns.keys.map("u_" + _).mkString(",") + ") values (" + id + "," + remainingColumns.values.map(_.sqlize).mkString(",") + ")"
      execute(conn, sql)
    }
  }

  val standardTableName = "test"
  val standardSchema = Map("num" -> LongColumn, "str" -> StringColumn)

  test("adding a new row with system PK succeeds") {
    val dsContext = new TestDatasetContext(standardTableName, standardSchema, None)
    val dataSqlizer = new TestDataSqlizer("hello", dsContext)

    withDB() { conn =>
      makeTables(conn, dsContext)
      conn.commit()

      using(PostgresTransaction(conn, TestTypeContext, dataSqlizer, idProvider(15))) { txn =>
        txn.upsert(Map("num" -> LongValue(1), "str" -> StringValue("a")))
        val report = txn.report
        report.inserted must equal (Map(0 -> LongValue(15)))
        report.updated must be ('empty)
        report.deleted must be ('empty)
        report.errors must be ('empty)
        report.elided must be ('empty)
        txn.commit()
      }

      query(conn, "SELECT id as ID, u_num as NUM, u_str as STR from test_data") must equal (Seq(
        Map("ID" -> 15L, "NUM" -> 1L, "STR" -> "a")
      ))
      query(conn, "SELECT id as ID, rows as ROWS, who as WHO from test_log") must equal (Seq(
        Map("ID" -> 1, "ROWS" -> """[{"i":{":id":15,"num":1,"str":"a"}}]""", "WHO" -> "hello")
      ))
    }
  }

  test("inserting and then updating a new row with system PK succeeds") {
    val ids = idProvider(15)
    val dsContext = new TestDatasetContext(standardTableName, standardSchema, None)
    val dataSqlizer = new TestDataSqlizer("hello", dsContext)

    withDB() { conn =>
      makeTables(conn, dsContext)
      conn.commit()

      using(PostgresTransaction(conn, TestTypeContext, dataSqlizer, ids)) { txn =>
        txn.upsert(Map("num" -> LongValue(1), "str" -> StringValue("a")))
        txn.upsert(Map(":id" -> LongValue(15), "num" -> LongValue(2), "str" -> StringValue("b")))
        val report = txn.report
        report.inserted must equal (Map(0 -> LongValue(15)))
        report.updated must be ('empty)
        report.deleted must be ('empty)
        report.errors must be ('empty)
        report.elided must equal (Map(1 -> (LongValue(15), 0)))
        txn.commit()
      }

      ids.borrow().allocate() must be (16)

      query(conn, "SELECT id as ID, u_num as NUM, u_str as STR from test_data") must equal (Seq(Map("ID" -> 15L, "NUM" -> 2L, "STR" -> "b")))
      query(conn, "SELECT id as ID, rows as ROWS, who as WHO from test_log") must equal (Seq(
        Map("ID" -> 1, "ROWS" -> """[{"i":{":id":15,"num":2,"str":"b"}}]""", "WHO" -> "hello")
      ))
    }
  }

  test("trying to add a new row with a NULL system PK fails") {
    val dsContext = new TestDatasetContext(standardTableName, standardSchema, None)
    val dataSqlizer = new TestDataSqlizer("hello", dsContext)

    withDB() { conn =>
      makeTables(conn, dsContext)
      conn.commit()

      using(PostgresTransaction(conn, TestTypeContext, dataSqlizer, idProvider(22))) { txn =>
        txn.upsert(Map(":id" -> NullValue, "num" -> LongValue(1), "str" -> StringValue("a")))
        val report = txn.report
        report.inserted must be ('empty)
        report.updated must be ('empty)
        report.deleted must be ('empty)
        report.errors must equal (Map(0 -> NullPrimaryKey))
        report.elided must be ('empty)
        txn.commit()
      }

      query(conn, "SELECT id as ID, u_num as NUM, u_str as STR from test_data") must equal (Seq.empty)
      query(conn, "SELECT id as ID, rows as ROWS, who as WHO from test_log") must equal (Seq.empty)
    }
  }

  test("trying to add a new row with a nonexistant system PK fails") {
    val dsContext = new TestDatasetContext(standardTableName, standardSchema, None)
    val dataSqlizer = new TestDataSqlizer("hello", dsContext)

    withDB() { conn =>
      makeTables(conn, dsContext)
      conn.commit()

      using(PostgresTransaction(conn, TestTypeContext, dataSqlizer, idProvider(6))) { txn =>
        txn.upsert(Map(":id" -> LongValue(77), "num" -> LongValue(1), "str" -> StringValue("a")))
        val report = txn.report
        report.inserted must be ('empty)
        report.updated must be ('empty)
        report.deleted must be ('empty)
        report.errors must equal (Map(0 -> NoSuchRowToUpdate(LongValue(77))))
        report.elided must be ('empty)
        txn.commit()
      }

      query(conn, "SELECT id as ID, u_num as NUM, u_str as STR from test_data") must equal (Seq.empty)
      query(conn, "SELECT id as ID, rows as ROWS, who as WHO from test_log") must equal (Seq.empty)
    }
  }

  test("updating an existing row by system id succeeds") {
    val ids = idProvider(13)
    val dsContext = new TestDatasetContext(standardTableName, standardSchema, None)
    val dataSqlizer = new TestDataSqlizer("hello", dsContext)

    withDB() { conn =>
      preload(conn, dsContext)(
        Map(":id" -> LongValue(7), "str" -> StringValue("q"), "num" -> LongValue(2))
      )
      conn.commit()

      using(PostgresTransaction(conn, TestTypeContext, dataSqlizer, ids)) { txn =>
        txn.upsert(Map(":id" -> LongValue(7), "num" -> LongValue(44)))
        val report = txn.report
        report.inserted must be ('empty)
        report.updated must equal (Map(0 -> LongValue(7)))
        report.deleted must be ('empty)
        report.errors must be ('empty)
        report.elided must be ('empty)
        txn.commit()
      }

      ids.borrow().allocate() must be (13)

      query(conn, "SELECT id as ID, u_num as NUM, u_str as STR from test_data") must equal (Seq(
        Map("ID" -> 7L, "NUM" -> 44L, "STR" -> "q")
      ))
      query(conn, "SELECT id as ID, rows as ROWS, who as WHO from test_log") must equal (Seq(
        Map("ID" -> 1, "ROWS" -> """[{"u":{":id":7,"num":44}}]""", "WHO" -> "hello")
      ))
    }
  }

  def userCast[A,B](t: PostgresTransaction[A,B]) = t.asInstanceOf[UserPKPostgresTransaction[A,B]]

  test("adding a new row with user PK succeeds") {
    val ids = idProvider(15)
    val dsContext = new TestDatasetContext(standardTableName, standardSchema, Some("str"))
    val dataSqlizer = new TestDataSqlizer("hello", dsContext)

    withDB() { conn =>
      makeTables(conn, dsContext)
      conn.commit()

      using(userCast(PostgresTransaction(conn, TestTypeContext, dataSqlizer, ids))) { txn =>
        txn.upsert(Map("num" -> LongValue(1), "str" -> StringValue("a")))
        val report = txn.report
        report.inserted must equal (Map(0 -> StringValue("a")))
        report.updated must be ('empty)
        report.deleted must be ('empty)
        report.errors must be ('empty)
        report.elided must be ('empty)
        txn.commit()
      }

      ids.borrow().allocate() must equal (16)

      query(conn, "SELECT id as ID, u_num as NUM, u_str as STR from test_data") must equal (Seq(
        Map("ID" -> 15L, "NUM" -> 1L, "STR" -> "a")
      ))
      query(conn, "SELECT id as ID, rows as ROWS, who as WHO from test_log") must equal (Seq(
        Map("ID" -> 1, "ROWS" -> """[{"i":{":id":15,"num":1,"str":"a"}}]""", "WHO" -> "hello")
      ))
    }
  }

  test("trying to add a new row with a NULL user PK fails") {
    val ids = idProvider(22)
    val dsContext = new TestDatasetContext(standardTableName, standardSchema, Some("str"))
    val dataSqlizer = new TestDataSqlizer("hello", dsContext)

    withDB() { conn =>
      makeTables(conn, dsContext)
      conn.commit()

      using(userCast(PostgresTransaction(conn, TestTypeContext, dataSqlizer, ids))) { txn =>
        txn.upsert(Map("num" -> LongValue(1), "str" -> NullValue))
        val report = txn.report
        report.inserted must be ('empty)
        report.updated must be ('empty)
        report.deleted must be ('empty)
        report.errors must equal (Map(0 -> NullPrimaryKey))
        report.elided must be ('empty)
        txn.commit()
      }

      ids.borrow().allocate() must equal (22)

      query(conn, "SELECT id as ID, u_num as NUM, u_str as STR from test_data") must equal (Seq.empty)
      query(conn, "SELECT id as ID, rows as ROWS, who as WHO from test_log") must equal (Seq.empty)
    }
  }

  test("trying to add a new row without user PK fails") {
    val ids = idProvider(22)
    val dsContext = new TestDatasetContext(standardTableName, standardSchema, Some("str"))
    val dataSqlizer = new TestDataSqlizer("hello", dsContext)

    withDB() { conn =>
      makeTables(conn, dsContext)
      conn.commit()

      using(userCast(PostgresTransaction(conn, TestTypeContext, dataSqlizer, ids))) { txn =>
        txn.upsert(Map("num" -> LongValue(1)))
        val report = txn.report
        report.inserted must be ('empty)
        report.updated must be ('empty)
        report.deleted must be ('empty)
        report.errors must be (Map(0 -> NoPrimaryKey))
        report.elided must be ('empty)
        txn.commit()
      }

      ids.borrow().allocate() must equal (22)

      query(conn, "SELECT id as ID, u_num as NUM, u_str as STR from test_data") must equal (Seq.empty)
      query(conn, "SELECT id as ID, rows as ROWS, who as WHO from test_log") must equal (Seq.empty)
    }
  }

  test("updating an existing row by user pk succeeds") {
    val ids = idProvider(13)
    val dsContext = new TestDatasetContext(standardTableName, standardSchema, Some("str"))
    val dataSqlizer = new TestDataSqlizer("hello", dsContext)

    withDB() { conn =>
      preload(conn, dsContext)(
        Map(":id" -> LongValue(7), "str" -> StringValue("q"), "num" -> LongValue(2))
      )
      conn.commit()

      using(userCast(PostgresTransaction(conn, TestTypeContext, dataSqlizer, ids))) { txn =>
        txn.upsert(Map("str" -> StringValue("q"), "num" -> LongValue(44)))
        val report = txn.report
        report.inserted must be ('empty)
        report.updated must equal (Map(0 -> StringValue("q")))
        report.deleted must be ('empty)
        report.errors must be ('empty)
        report.elided must be ('empty)
        txn.commit()
      }

      ids.borrow().allocate() must be (13)

      query(conn, "SELECT id as ID, u_num as NUM, u_str as STR from test_data") must equal (Seq(
        Map("ID" -> 7L, "NUM" -> 44L, "STR" -> "q")
      ))
      query(conn, "SELECT id as ID, rows as ROWS, who as WHO from test_log") must equal (Seq(
        Map("ID" -> 1, "ROWS" -> """[{"u":{":id":7,"num":44,"str":"q"}}]""", "WHO" -> "hello")
      ))
    }
  }

  test("inserting and then updating a new row with user PK succeeds") {
    val ids = idProvider(15)
    val dsContext = new TestDatasetContext(standardTableName, standardSchema, Some("str"))
    val dataSqlizer = new TestDataSqlizer("hello", dsContext)

    withDB() { conn =>
      makeTables(conn, dsContext)
      conn.commit()

      using(userCast(PostgresTransaction(conn, TestTypeContext, dataSqlizer, ids))) { txn =>
        txn.upsert(Map("num" -> LongValue(1), "str" -> StringValue("q")))
        txn.upsert(Map("num" -> LongValue(2), "str" -> StringValue("q")))
        val report = txn.report
        report.inserted must equal (Map(0 -> StringValue("q")))
        report.updated must be ('empty)
        report.deleted must be ('empty)
        report.errors must be ('empty)
        report.elided must equal (Map(1 -> (StringValue("q"), 0)))
        txn.commit()
      }

      ids.borrow().allocate() must be (16)

      query(conn, "SELECT id as ID, u_num as NUM, u_str as STR from test_data") must equal (Seq(
        Map("ID" -> 15L, "NUM" -> 2L, "STR" -> "q")
      ))
      query(conn, "SELECT id as ID, rows as ROWS, who as WHO from test_log") must equal (Seq(
        Map("ID" -> 1, "ROWS" -> """[{"i":{":id":15,"num":2,"str":"q"}}]""", "WHO" -> "hello")
      ))
    }
  }

  test("specifying :id when there's a user PK fails") {
    val ids = idProvider(15)
    val dsContext = new TestDatasetContext(standardTableName, standardSchema, Some("str"))
    val dataSqlizer = new TestDataSqlizer("hello", dsContext)

    withDB() { conn =>
      makeTables(conn, dsContext)
      conn.commit()

      using(userCast(PostgresTransaction(conn, TestTypeContext, dataSqlizer, ids))) { txn =>
        txn.upsert(Map(":id" -> LongValue(15), "num" -> LongValue(1), "str" -> StringValue("q")))
        val report = txn.report
        report.inserted must be ('empty)
        report.updated must be ('empty)
        report.deleted must be ('empty)
        report.errors must equal (Map(0 -> SystemColumnsSet(Set(":id"))))
        report.elided must be ('empty)
        txn.commit()
      }

      ids.borrow().allocate() must be (15)

      query(conn, "SELECT id as ID, u_num as NUM, u_str as STR from test_data") must equal (Seq.empty)
      query(conn, "SELECT id as ID, rows as ROWS, who as WHO from test_log") must equal (Seq.empty)
    }
  }

  test("deleting a row just inserted with a user PK succeeds") {
    val ids = idProvider(15)
    val dsContext = new TestDatasetContext(standardTableName, standardSchema, Some("str"))
    val dataSqlizer = new TestDataSqlizer("hello", dsContext)

    withDB() { conn =>
      makeTables(conn, dsContext)
      conn.commit()

      using(userCast(PostgresTransaction(conn, TestTypeContext, dataSqlizer, ids))) { txn =>
        txn.upsert(Map("num" -> LongValue(1), "str" -> StringValue("q")))
        txn.delete(StringValue("q"))
        val report = txn.report
        report.inserted must be ('empty)
        report.updated must be ('empty)
        report.deleted must equal (Map(1 -> StringValue("q")))
        report.elided must equal (Map(0 -> (StringValue("q"), 1)))
        report.errors must be ('empty)
        txn.commit()
      }

      ids.borrow().allocate() must be (15) // and it never even allocated a sid for it

      query(conn, "SELECT id as ID, u_num as NUM, u_str as STR from test_data") must equal (Seq.empty)
      query(conn, "SELECT id as ID, rows as ROWS, who as WHO from test_log") must equal (Seq.empty)
    }
  }

  test("deleting a row just inserted with a system PK succeeds") {
    // This isn't a useful thing to be able to do, since in the real system
    // IDs won't be user-predictable, but it's a valuable sanity check anyway.
    val ids = idProvider(15)
    val dsContext = new TestDatasetContext(standardTableName, standardSchema, None)
    val dataSqlizer = new TestDataSqlizer("hello", dsContext)

    withDB() { conn =>
      makeTables(conn, dsContext)
      conn.commit()

      using(PostgresTransaction(conn, TestTypeContext, dataSqlizer, ids)) { txn =>
        txn.upsert(Map("num" -> LongValue(1), "str" -> StringValue("q")))
        txn.delete(LongValue(15))
        val report = txn.report
        report.inserted must be ('empty)
        report.updated must be ('empty)
        report.deleted must equal (Map(1 -> LongValue(15)))
        report.errors must be ('empty)
        report.elided must equal (Map(0 -> (LongValue(15), 1)))
        txn.commit()
      }

      ids.borrow().allocate() must be (16)

      query(conn, "SELECT id as ID, u_num as NUM, u_str as STR from test_data") must equal (Seq.empty)
      query(conn, "SELECT id as ID, rows as ROWS, who as WHO from test_log") must equal (Seq.empty)
    }
  }

  test("must contain the same data as a table manipulated by a StupidPostgresTransaction when using user IDs") {
    import org.scalacheck.{Gen, Arbitrary}

    sealed abstract class Op
    case class Upsert(id: Long, num: Option[Long], data: Option[String]) extends Op
    case class Delete(id: Long) extends Op

    val genId = Gen.choose(0L, 100L)

    val genUpsert = for {
      id <- genId
      num <- Gen.frequency(2 -> Arbitrary.arbitrary[Long].map(Some(_)), 1 -> Gen.value(None))
      data <- Gen.frequency(2 -> Arbitrary.arbitrary[String].map(Some(_)), 1 -> Gen.value(None))
    } yield Upsert(id, num, data.map(_.filterNot(_ == '\0')))

    val genDelete = genId.map(Delete(_))

    val genOp = Gen.frequency(2 -> genUpsert, 1 -> genDelete)

    implicit val arbOp = Arbitrary[Op](genOp)

    val ids = idProvider(1)

    val schema = Map(
      "id" -> LongColumn,
      "num" -> LongColumn,
      "str" -> StringColumn
    )

    val dsContext = new TestDatasetContext(standardTableName, schema, Some("id"))
    val dataSqlizer = new TestDataSqlizer("hello", dsContext)

    val stupidDsContext = new TestDatasetContext("stupid_data", schema, Some("id"))
    val stupidDataSqlizer = new TestDataSqlizer("hello", stupidDsContext)

    def applyOps(txn: Transaction[TestColumnValue], ops: List[Op]) {
      for(op <- ops) op match {
        case Delete(id) => txn.delete(LongValue(id))
        case Upsert(id, num, data) => txn.upsert(Map(
          "id" -> LongValue(id),
          "num" -> num.map(LongValue(_)).getOrElse(NullValue),
          "str" -> data.map(StringValue(_)).getOrElse(NullValue)
        ))
      }
    }

    forAll { (opss: List[List[Op]]) =>
      withDB() { stupidConn =>
        withDB() { smartConn =>
          makeTables(smartConn, dsContext)
          makeTables(stupidConn, stupidDsContext)

          def runCompareTest(ops: List[Op]) {
            val smartReport = using(userCast(PostgresTransaction(smartConn, TestTypeContext, dataSqlizer, ids))) { txn =>
              applyOps(txn, ops)
              val report = txn.report
              txn.commit()
              report
            }
            val stupidReport = using(new StupidPostgresTransaction(stupidConn, TestTypeContext, stupidDataSqlizer, ids)) { txn =>
              applyOps(txn, ops)
              val report = txn.report
              txn.commit()
              report
            }

            def q(t: String) = "SELECT u_id AS ID, u_num AS NUM, u_str AS STR FROM " + t + " ORDER BY u_id"
            /* -- "must" is too expensive to call in an inner loop
              for {
                smartStmt <- managed(smartConn.createStatement())
                smartRs <- managed(smartStmt.executeQuery(q))
                stupidStmt <- managed(stupidConn.createStatement())
                stupidRs <- managed(stupidStmt.executeQuery(q))
              } {
                while(smartRs.next()) {
                  stupidRs.next() must be (true)
                  smartRs.getLong("ID") must equal (stupidRs.getLong("ID"))
                  smartRs.getLong("NUM") must equal (stupidRs.getLong("NUM"))
                  smartRs.wasNull() must equal (stupidRs.wasNull())
                  smartRs.getString("STR") must equal (stupidRs.getString("STR"))
                }
                stupidRs.next() must be (false)
              }
 */
            val smartData = for {
              smartStmt <- managed(smartConn.createStatement())
              smartRs <- managed(smartStmt.executeQuery(q(dataSqlizer.dataTableName)))
            } yield {
              val fromSmart = new VectorBuilder[(Long, Long, Boolean, String)]
              while(smartRs.next()) {
                val id = smartRs.getLong("ID")
                val num = smartRs.getLong("NUM")
                val numWasNull = smartRs.wasNull()
                val str = smartRs.getString("STR")

                fromSmart += ((id, num, numWasNull, str))
              }
              fromSmart.result
            }

            val stupidData = for {
              stupidStmt <- managed(stupidConn.createStatement())
              stupidRs <- managed(stupidStmt.executeQuery(q(stupidDataSqlizer.dataTableName)))
            } yield {
              val fromStupid = new VectorBuilder[(Long, Long, Boolean, String)]
              while(stupidRs.next()) {
                val id = stupidRs.getLong("ID")
                val num = stupidRs.getLong("NUM")
                val numWasNull = stupidRs.wasNull()
                val str = stupidRs.getString("STR")

                fromStupid += ((id, num, numWasNull, str))
              }
              fromStupid.result
            }

            smartData must equal (stupidData)
          }

          opss.foreach(runCompareTest)
        }
      }
    }
  }

  test("must contain the same data as a table manipulated by a StupidPostgresTransaction when using system IDs") {
    import org.scalacheck.{Gen, Arbitrary}

    sealed abstract class Op
    case class Upsert(id: Option[Long], num: Option[Long], data: Option[String]) extends Op
    case class Delete(id: Long) extends Op

    val genId = Gen.choose(0L, 100L)

    val genUpsert = for {
      id <- Gen.frequency(1 -> genId.map(Some(_)), 2 -> Gen.value(None))
      num <- Gen.frequency(2 -> Arbitrary.arbitrary[Long].map(Some(_)), 1 -> Gen.value(None))
      data <- Gen.frequency(2 -> Arbitrary.arbitrary[String].map(Some(_)), 1 -> Gen.value(None))
    } yield Upsert(id, num, data.map(_.filterNot(_ == '\0')))

    val genDelete = genId.map(Delete(_))

    val genOp = Gen.frequency(2 -> genUpsert, 1 -> genDelete)

    implicit val arbOp = Arbitrary[Op](genOp)

    val dsContext = new TestDatasetContext(standardTableName, standardSchema, None)
    val dataSqlizer = new TestDataSqlizer("hello", dsContext)

    val stupidDsContext = new TestDatasetContext("stupid_data", standardSchema, None)
    val stupidDataSqlizer = new TestDataSqlizer("hello", stupidDsContext)

    def applyOps(txn: Transaction[TestColumnValue], ops: List[Op]) {
      for(op <- ops) op match {
        case Delete(id) => txn.delete(LongValue(id))
        case Upsert(id, num, data) =>
          val baseRow = Map(
            "num" -> num.map(LongValue(_)).getOrElse(NullValue),
            "str" -> data.map(StringValue(_)).getOrElse(NullValue)
          )
          val row = id match {
            case Some(sid) => baseRow + (":id" -> LongValue(sid))
            case None => baseRow
          }
          txn.upsert(row)
      }
    }

    forAll { (opss: List[List[Op]]) =>
      withDB() { stupidConn =>
        withDB() { smartConn =>
          makeTables(smartConn, dsContext)
          makeTables(stupidConn, stupidDsContext)

          val smartIds = idProvider(1)
          val stupidIds = idProvider(1)

          def runCompareTest(ops: List[Op]) {
            val smartReport = locally {
              val txn = PostgresTransaction(smartConn, TestTypeContext, dataSqlizer, smartIds)
              applyOps(txn, ops)
              val report = txn.report
              txn.commit()
              report
            }
            val stupidReport = locally {
              val txn = new StupidPostgresTransaction(stupidConn, TestTypeContext, stupidDataSqlizer, stupidIds)
              applyOps(txn, ops)
              val report = txn.report
              txn.commit()
              report
            }

            def q(t: String) = "SELECT u_num AS NUM, u_str AS STR FROM " + t + " ORDER BY u_num, u_str"
/* -- "must" is too expensive to call in an inner loop
            val q = "SELECT u_num AS NUM, u_str AS STR FROM test_data ORDER BY u_num, u_str"
            for {
              smartStmt <- managed(smartConn.createStatement())
              smartRs <- managed(smartStmt.executeQuery(q))
              stupidStmt <- managed(stupidConn.createStatement())
              stupidRs <- managed(stupidStmt.executeQuery(q))
            } {
              while(smartRs.next()) {
                stupidRs.next() must be (true)
                smartRs.getLong("NUM") must equal (stupidRs.getLong("NUM"))
                smartRs.wasNull() must equal (stupidRs.wasNull())
                smartRs.getString("STR") must equal (stupidRs.getString("STR"))
              }
              stupidRs.next() must be (false)
            }
*/
            val smartData = for {
              smartStmt <- managed(smartConn.createStatement())
              smartRs <- managed(smartStmt.executeQuery(q(dataSqlizer.dataTableName)))
            } yield {
              val fromSmart = new VectorBuilder[(Long, Boolean, String)]
              while(smartRs.next()) {
                val num = smartRs.getLong("NUM")
                val numWasNull = smartRs.wasNull()
                val str = smartRs.getString("STR")

                fromSmart += ((num, numWasNull, str))
              }
              fromSmart.result
            }

            val stupidData = for {
              stupidStmt <- managed(stupidConn.createStatement())
              stupidRs <- managed(stupidStmt.executeQuery(q(stupidDataSqlizer.dataTableName)))
            } yield {
              val fromStupid = new VectorBuilder[(Long, Boolean, String)]
              while(stupidRs.next()) {
                val num = stupidRs.getLong("NUM")
                val numWasNull = stupidRs.wasNull()
                val str = stupidRs.getString("STR")

                fromStupid += ((num, numWasNull, str))
              }
              fromStupid.result
            }

            smartData must equal (stupidData)
          }
          opss.foreach(runCompareTest)
        }
      }
    }
  }
}