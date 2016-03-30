package com.socrata.datacoordinator.service

import com.socrata.datacoordinator.id.{RollupName, DatasetId}
import com.socrata.datacoordinator.resources.SodaResource
import com.socrata.http.server._
import com.socrata.http.server.responses._
import com.socrata.http.server.routing.{Extractor, SimpleRouteContext}
import com.socrata.http.server.routing.SimpleRouteContext._
import com.socrata.datacoordinator.common.util.DatasetIdNormalizer._

case class Router(parseDatasetId: String => Option[DatasetId],
              notFoundDatasetResource: Option[String] => SodaResource,
              datasetResource: DatasetId => SodaResource,
              datasetSchemaResource: DatasetId => SodaResource,
              datasetRollupResource: DatasetId => SodaResource,
              secondaryManifestsResource: Option[String] => SodaResource,
              datasetSecondaryStatusResource: (Option[String], DatasetId) => SodaResource,
              secondariesOfDatasetResource: DatasetId => SodaResource,
              versionResource: SodaResource) {

  type OptString = Option[String]




  implicit object DatasetIdExtractor extends Extractor[DatasetId] {
    def extract(s: String): Option[DatasetId] = parseDatasetId(norm(s))
  }

  implicit object RollupNameExtractor extends Extractor[RollupName] {
    def extract(s: String): Option[RollupName] = Some(new RollupName(s))
  }

  implicit object StringExtractor extends Extractor[OptString]{
    def extract(s: String): Option[OptString] = if (s.isEmpty) Some(None) else Some(Some(s))
  }



  val routes = locally {
    import SimpleRouteContext._
    Routes(
      // "If the thing is parsable as a DatasetId, do something with it, otherwise give a
      // SODA2 not-found response"
      Route("/dataset", notFoundDatasetResource(None)),
      Route("/dataset/{OptString}", notFoundDatasetResource),
      Route("/dataset/{DatasetId}", datasetResource),
      Route("/dataset/{DatasetId}/schema", datasetSchemaResource),

      Route("/dataset-rollup/{DatasetId}", datasetRollupResource),

      Route("/secondary-manifest", secondaryManifestsResource(None)),
      Route("/secondary-manifest/{OptString}", secondaryManifestsResource),
      Route("/secondary-manifest/{OptString}/{DatasetId}", datasetSecondaryStatusResource),

      Route("/secondaries-of-dataset/{DatasetId}", secondariesOfDatasetResource),

      Route("/version", versionResource)
    )
  }


  def handler(req: HttpRequest): HttpResponse =
    routes(req.requestPath) match {
      case Some(s) => s(req)
      case None => NotFound
    }

}