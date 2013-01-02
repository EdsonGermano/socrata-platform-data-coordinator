package com.socrata.datacoordinator.id

class RowId(val underlying: Long) extends AnyVal {
  override def toString = s"RowId($underlying)"
}

object RowId {
  implicit val ordering = new Ordering[RowId] {
    def compare(x: RowId, y: RowId): Int = Ordering.Long.compare(x.underlying, y.underlying)
  }
}
