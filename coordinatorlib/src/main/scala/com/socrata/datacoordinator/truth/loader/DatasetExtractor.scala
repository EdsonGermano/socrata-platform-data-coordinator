package com.socrata.datacoordinator
package truth.loader

import com.rojoma.simplearm.Managed

trait DatasetExtractor[CV] {
  def allRows(limit: Option[Long], offset: Option[Long], sorted: Boolean, rowId: Option[CV]): Managed[Iterator[Row[CV]]]
}
