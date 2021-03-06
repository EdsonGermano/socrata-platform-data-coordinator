package com.socrata.datacoordinator.id

import org.scalatest.FunSuite
import org.scalatest.MustMatchers

import org.scalatest.prop.PropertyChecks

class GlobalLogEntryIdTest extends FunSuite with MustMatchers with PropertyChecks {
  test("toString must include underlying value") {
    forAll { (underlying: Long) =>
      val id = new GlobalLogEntryId(underlying)

      id.toString must include (underlying.toString)
      id.toString must include (id.getClass().getSimpleName)
    }
  }
}
