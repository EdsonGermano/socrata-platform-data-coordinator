package com.socrata.datacoordinator.id

class StoreId(val underlying: Long) extends AnyVal {
  override def toString = s"StoreId($underlying)"
}
