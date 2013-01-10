package com.socrata.datacoordinator.main

class DatasetCreator[CT, CV](mutator: DatabaseMutator[CT, CV], systemColumns: Map[String, CT], idColumnName: String) {
  def createDataset(datasetId: String, username: String) {
    mutator.withTransaction() { providerOfNecessaryThings =>
      import providerOfNecessaryThings._
      val table = datasetMapWriter.create(datasetId, "t")
      val logger = datasetLog(table.datasetInfo)
      val loader = schemaLoader(table, logger)

      loader.create(table)
      truthManifest.create(table.datasetInfo)

      for((name, typ) <- systemColumns) {
        val col = datasetMapWriter.addColumn(table, name, nameForType(typ), physicalColumnBaseForType(typ))
        loader.addColumn(col)
        if(col.logicalName == idColumnName) {
          loader.makePrimaryKey(col) // hm.  I definitely want these indices/constraints, but do I want the log entry?
        }
      }

      val newVersion = logger.endTransaction().getOrElse(sys.error(s"No record of the `working copy created' or ${systemColumns.size} columns?"))
      truthManifest.updateLatestVersion(table.datasetInfo, newVersion)
      globalLog.log(table.datasetInfo, newVersion, now, username)
    }
  }
}
