package com.socrata.datacoordinator
package truth.loader
package sql
package perf

import scala.{collection => sc}

import java.sql.{SQLException, Connection, DriverManager}

import com.rojoma.simplearm.util._
import java.io.{FileInputStream, InputStreamReader, BufferedReader}
import com.rojoma.json.io.{BlockJsonTokenIterator, JsonEventIterator, JsonReader}
import com.rojoma.json.codec.JsonCodec
import com.rojoma.json.ast.{JValue, JNull, JNumber, JString}
import java.util.zip.GZIPInputStream
import com.socrata.id.numeric.{InMemoryBlockIdProvider, FixedSizeIdProvider, PushbackIdProvider}
import util.{Counter, LongLikeMap, IdProviderPoolImpl}
import com.socrata.datacoordinator.truth.loader.sql.SqlLoader
import gnu.trove.map.hash.TLongObjectHashMap

class ExecutePlan

object ExecutePlan {
  val log = org.slf4j.LoggerFactory.getLogger(classOf[ExecutePlan])

  val EndOfSection = JString("------")

  def time[T](label: String)(f: => T): T = {
    log.info("Starting " + label)
    val start = System.nanoTime()
    val result = f
    val end = System.nanoTime()
    log.info("Finished " + label + " (" + ((end - start) / 1000000) + "ms)")
    result
  }

  def main(args: Array[String]) {
    val planFile = args(0)
    val trialsPerDataPoint = args(1).toInt

    val executor = java.util.concurrent.Executors.newCachedThreadPool()
    try {
      val datapoints = for(trial <- 1 to trialsPerDataPoint) yield {
        val idProvider = new IdProviderPoolImpl(new InMemoryBlockIdProvider(releasable = false), new FixedSizeIdProvider(_, 1000))
        for {
          planReader <- managed(new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(planFile)), "UTF-8")))
          conn <- managed(DriverManager.getConnection("jdbc:postgresql://10.0.5.104:5432/robertm", "robertm", "lof9afw3"))
        } yield {
          val plan = new JsonReader(new JsonEventIterator(new BlockJsonTokenIterator(planReader, blockSize = 10240)))
          implicit def connImplict = conn

          conn.setAutoCommit(false)
          executeDDL("DROP TABLE IF EXISTS perf_data")
          executeDDL("DROP TABLE IF EXISTS perf_log")
          val schema = JsonCodec.fromJValue[Map[String, String]](plan.read()).getOrElse(sys.error("Cannot read schema"))

          val schemaCtr = new Counter
          val schemaIdMap = schema.map { case (k, v) => k -> ColumnId(schemaCtr()) }
          val idColumnId = ColumnId(schemaCtr())
          val cId = "c_" + idColumnId
          val cUid = "c_" + schemaIdMap("uid")

          val createSql = schema.toSeq.map { case (col, typ) => "c_" + schemaIdMap(col) + " " + typ }.mkString("CREATE TABLE perf_data (" + cId + " bigint not null primary key,",",",")")
          log.info(createSql)
          executeDDL(createSql)
          executeDDL("ALTER TABLE perf_data ALTER COLUMN " + cUid + " SET NOT NULL")
          executeDDL("CREATE UNIQUE INDEX perf_data_uid ON perf_data(" + cUid + ")")
          executeDDL("ALTER TABLE perf_data ADD UNIQUE USING INDEX perf_data_uid")
          executeDDL("CREATE TABLE perf_log (version bigint not null, subversion bigint not null, what varchar(16) not null, aux bytea not null, primary key (version, subversion))")

          val userSchema = new LongLikeMap[ColumnId, PerfType](schema.map {
            case (k, "TEXT") => schemaIdMap(k) -> PTText
            case (k, "NUMERIC") => schemaIdMap(k) -> PTNumber
          })
          val datasetContext = new PerfDatasetContext(userSchema, idColumnId, Some(schemaIdMap("uid")))
          val sqlizer = new PerfDataSqlizer("perf", datasetContext)

          val rowPreparer = new RowPreparer[PerfValue] {
            def prepareForInsert(row: Row[PerfValue], systemId: Long) = {
              val newRow = new LongLikeMap(row)
              newRow(datasetContext.systemIdColumnName) = PVId(systemId)
              newRow
            }

            def prepareForUpdate(row: Row[PerfValue]) =
              row
          }

          time("Prepopulating") {
            import org.postgresql.copy.CopyManager
            import org.postgresql.core.BaseConnection

            idProvider.withProvider { idProvider =>
              val copier = new CopyManager(conn.asInstanceOf[BaseConnection])
              val reader = new java.io.Reader {
                var line = ""
                var offset = 0
                def read(cbuf: Array[Char], off: Int, len: Int): Int = {
                  if(line == null) return -1
                  if(offset == line.length) refill()
                  if(line == null) return -1
                  def loop(soFar: Int, off: Int, len: Int): Int = {
                    if(line == null || len == 0) return soFar
                    var src = offset
                    var dst = off
                    val count = java.lang.Math.min(line.length - offset, len)
                    var remaining = count
                    while(remaining > 0) {
                      cbuf(dst) = line.charAt(src)
                      dst += 1
                      src += 1
                      remaining -= 1
                    }
                    offset = src
                    if(offset == line.length) refill()
                    loop(soFar + count, off + count, len - count)
                  }
                  loop(0, off, len)
                }
                def close() {}
                def refill() {
                  val raw = plan.read()
                  if(raw == EndOfSection) line = null
                  else {
                    val ins = JsonCodec.fromJValue[Insert](raw).getOrElse(sys.error("Cannot read insert"))
                    val sb = new java.lang.StringBuilder(idProvider.allocate().toString)
                    for((k, t) <- schema) {
                      sb.append(',')
                      ins.fields(k) match {
                        case JString(s) => sb.append('"').append(s.replaceAllLiterally("\"", "\"\"")).append('"')
                        case JNumber(n) => sb.append(n)
                        case JNull => /* nothing */
                        case other => sys.error("Unexpected JSON datum " + other)
                      }
                    }
                    sb.append("\n")
                    line = sb.toString
                    offset = 0
                  }
                }
              }
              copier.copyIn("COPY perf_data (" + cId + "," + schema.keys.toSeq.map(c => "c_"+schemaIdMap(c)).mkString(",") + ") from stdin with csv", reader)
              time("Committing prepopulation") {
                conn.commit()
              }
            }
          }
          conn.setAutoCommit(true)
          time("Analyzing") {
            executeDDL("VACUUM ANALYZE perf_data")
          }
          conn.setAutoCommit(false)

          log.info("Executing...")

          def convertValue(field: String, in: JValue): PerfValue = {
            if(in == JNull) PVNull
            else {
              userSchema(schemaIdMap(field)) match {
                case PTText => PVText(in.asInstanceOf[JString].string)
                case PTNumber => PVNumber(in.asInstanceOf[JNumber].number)
              }
            }
          }

          def convert(row: sc.Map[String, JValue]): Row[PerfValue] = {
            val result = new Row[PerfValue]
            for((k, v) <- row) {
              result(schemaIdMap(k)) = convertValue(k, v)
            }
            result
          }

          val start = System.nanoTime()
          val report = for {
            dataLogger <- managed(new SqlLogger(conn, sqlizer, () => new PerfRowCodec))
            txn <- managed(SqlLoader(conn, rowPreparer, sqlizer, dataLogger, idProvider, executor))
          } yield {
            def loop() {
              val line = plan.read()
              if(line != EndOfSection) {
                val op = JsonCodec.fromJValue[Operation](line).getOrElse(sys.error("Can't decode op"))
                op match {
                  case Insert(_, row) => txn.upsert(convert(row.fields))
                  case Update(_, row) => txn.upsert(convert(row.fields))
                  case Delete(_, id) => txn.delete(convertValue("uid", id))
                }
                loop()
              }
            }
            loop()
            val result = txn.report
            dataLogger.endTransaction()
            result
          }
          conn.commit()
          val end = System.nanoTime()

          log.info("inserted: " + report.inserted.size + "; updated: " + report.updated.size + "; deleted: " + report.deleted.size + "; elided: " + report.elided.size + "; errors: " + report.errors.size)
          if(report.errors != 0) {
            report.errors.toSeq.sortBy(_._1).take(20).foreach { x => log.info(x.toString) }
          }

          val delta = end - start
          log.info("Trial {}: {}ms", trial: Any, (delta/1000000):Any)
          delta
        }
      }
      val avg = datapoints.sum / trialsPerDataPoint
      log.info("Average runtime (upsert process only): " + (avg / 1000000))
    } catch {
      case e: SQLException =>
        if(e.getNextException != null) {
          e.getNextException.printStackTrace()
        } else {
          e.printStackTrace()
        }
    } finally {
      executor.shutdown()
    }
  }

  def executeDDL(sql: String)(implicit conn: Connection) {
    using(conn.createStatement()) { stmt =>
      stmt.execute(sql)
    }
  }

  def executeUpdate(sql: String)(implicit conn: Connection): Int = {
    using(conn.createStatement()) { stmt =>
      stmt.execute(sql)
      stmt.getUpdateCount
    }
  }
}
