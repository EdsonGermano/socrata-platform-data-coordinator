package com.socrata.datacoordinator
package truth.metadata

import scala.runtime.ScalaRunTime

import com.rojoma.json.codec.JsonCodec
import com.rojoma.json.ast.JValue
import com.rojoma.json.matcher.{PObject, Variable}
import com.socrata.datacoordinator.id.ColumnId

trait ColumnInfo {
  def versionInfo: VersionInfo
  def systemId: ColumnId
  def logicalName: String
  def typeName: String
  def isUserPrimaryKey: Boolean
  def physicalColumnBaseBase: String

  def physicalColumnBase = physicalColumnBaseBase + "_" + systemId.underlying

  override def toString = ColumnInfo.jCodec.encode(this).toString

  override final def hashCode = ScalaRunTime._hashCode((versionInfo, systemId, logicalName, typeName, isUserPrimaryKey, physicalColumnBaseBase))
  override final def equals(o: Any) = o match {
    case that: ColumnInfo =>
      (this eq that) ||
        (this.versionInfo == that.versionInfo && this.systemId == that.systemId && this.logicalName == that.logicalName && this.typeName == that.typeName && this.isUserPrimaryKey == that.isUserPrimaryKey)
    case _ =>
      false
  }
}

object ColumnInfo {
  implicit val jCodec: JsonCodec[ColumnInfo] = new JsonCodec[ColumnInfo] {
    val versionInfoV = Variable[VersionInfo]
    val systemIdV = Variable[ColumnId]
    val logicalNameV = Variable[String]
    val typeNameV = Variable[String]
    val isUserPrimaryKeyV = Variable[Boolean]
    val physicalColumnBaseBaseV = Variable[String]

    val Pattern = new PObject(
      "versionInfo" -> versionInfoV,
      "systemId" -> systemIdV,
      "logicalName" -> logicalNameV,
      "typeName" -> typeNameV,
      "isPrimaryKey" -> isUserPrimaryKeyV,
      "physicalColumnBaseBase" -> physicalColumnBaseBaseV
    )

    def encode(ci: ColumnInfo): JValue =
      Pattern.generate(
        versionInfoV := ci.versionInfo,
        systemIdV := ci.systemId,
        logicalNameV := ci.logicalName,
        typeNameV := ci.typeName,
        isUserPrimaryKeyV := ci.isUserPrimaryKey,
        physicalColumnBaseBaseV := ci.physicalColumnBaseBase
      )

    def decode(x: JValue) = Pattern.matches(x) map { res =>
      new ColumnInfo {
        val versionInfo = versionInfoV(res)
        val systemId = systemIdV(res)
        val logicalName = logicalNameV(res)
        val typeName = typeNameV(res)
        val isUserPrimaryKey = isUserPrimaryKeyV(res)
        val physicalColumnBaseBase = physicalColumnBaseBaseV(res)
      }
    }
  }
}
