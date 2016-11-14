/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql.execution.datasources.index.lucenerdd.facets

import org.apache.lucene.document.Document
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, Row}
import org.apache.spark.storage.StorageLevel
import org.apache.spark.sql.execution.datasources.index.lucenerdd.LuceneRDD
import org.apache.spark.sql.execution.datasources.index.lucenerdd.aggregate.SparkFacetResultMonoid
import org.apache.spark.sql.execution.datasources.index.lucenerdd.models.SparkFacetResult
import org.apache.spark.sql.execution.datasources.index.lucenerdd.partition.{AbstractLuceneRDDPartition, LuceneRDDPartition}
import org.apache.spark.sql.execution.datasources.index.lucenerdd.response.LuceneRDDResponse
import org.apache.spark.sql.execution.datasources.index.lucenerdd.store.Status

import scala.reflect.ClassTag

/**
 * LuceneRDD with faceted functionality
 */
class FacetedLuceneRDD[T: ClassTag]
  (override protected val partitionsRDD: RDD[AbstractLuceneRDDPartition[T]])
  extends LuceneRDD[T](partitionsRDD) {

  setName("FacetedLuceneRDD")

  override def cache(): this.type = {
    this.persist(StorageLevel.MEMORY_ONLY)
  }

  override def persist(newLevel: StorageLevel): this.type = {
    partitionsRDD.persist(newLevel)
    super.persist(newLevel)
    this
  }

  override def unpersist(blocking: Boolean = true): this.type = {
    partitionsRDD.unpersist(blocking)
    super.unpersist(blocking)
    this
  }

  /**
   * Aggregates faceted search results using monoidal structure [[SparkFacetResultMonoid]]
   *
   * @param f a function that computes faceted search results per partition
   * @return faceted search results
   */
  private def facetResultsAggregator(f: AbstractLuceneRDDPartition[T] => SparkFacetResult)
  : SparkFacetResult = {
    partitionsRDD.map(f(_)).reduce(SparkFacetResultMonoid.plus)
  }

  /**
   * Faceted query
   * @param defaultField Default query field
   * @param searchString Lucene query string
   * @param facetField Field on which to compute facet
   * @param topK Number of results
   * @param facetNum Number of faceted results
   * @return
   */
  def facetQuery(defaultField: String, searchString: String,
                 facetField: String,
                 topK: Int = DefaultTopK,
                 facetNum: Int = DefaultFacetNum
                ): (LuceneRDDResponse, SparkFacetResult) = {
    val aggrTopDocs = partitionMapper(_.query(defaultField, searchString, topK), topK)
    val aggrFacets = facetResultsAggregator(_.facetQuery(searchString, facetField, facetNum))
    (aggrTopDocs, aggrFacets)
  }

  /**
   * Faceted query with multiple facets
   *
   * @param searchString Lucene query string
   * @param facetFields Fields on which to compute facets
   * @param topK Number of results
   * @param facetNum Number of faceted results
   * @return
   */
  def facetQueries(defaultField: String, searchString: String,
                   facetFields: Seq[String],
                   topK: Int = DefaultTopK,
                   facetNum: Int = DefaultFacetNum)
  : (LuceneRDDResponse, Map[String, SparkFacetResult]) = {
    logInfo(s"Faceted query on facet fields ${facetFields.mkString(",")}...")
    val aggrTopDocs = partitionMapper(_.query(defaultField, searchString, topK), topK)
    val aggrFacets = facetFields.map { case facetField =>
      (facetField, facetResultsAggregator(_.facetQuery(searchString, facetField, facetNum)))
    }.toMap[String, SparkFacetResult]
    (aggrTopDocs, aggrFacets)
  }


}

object FacetedLuceneRDD {

  /** All faceted fields are suffixed with _facet */
  val FacetTextFieldSuffix = "_facet"
  val FacetNumericFieldSuffix = "_numFacet"

  /**
   * Instantiate a FacetedLuceneRDD given an RDD[T]
   *
   * @param elems RDD of type T
   * @tparam T Generic type
   * @return
   */
  def apply[T : ClassTag](elems: RDD[T])
                         (implicit conv: T => Document): FacetedLuceneRDD[T] = {
    val partitions = elems.mapPartitions[AbstractLuceneRDDPartition[T]](
      iter => Iterator(LuceneRDDPartition(iter, null, "", Status.Rewrite)),
      preservesPartitioning = true)
    new FacetedLuceneRDD[T](partitions)
  }

  /**
   * Instantiate a FacetedLuceneRDD with an iterable
   *
   * @param elems
   * @param sc
   * @tparam T
   * @return
   */
  def apply[T : ClassTag]
  (elems: Iterable[T])(implicit sc: SparkContext, conv: T => Document)
  : FacetedLuceneRDD[T] = {
    apply(sc.parallelize[T](elems.toSeq))
  }

  /**
   * Instantiate a FacetedLuceneRDD with DataFrame
   *
   * @param dataFrame Spark DataFrame
   * @return
   */
  def apply(dataFrame: DataFrame)
  : FacetedLuceneRDD[Row] = {
    apply(dataFrame.rdd)
  }


  /**
   * Return project information, i.e., version number, build time etc
   * @return
   */
  def version(): Map[String, Any] = {
    // BuildInfo is automatically generated using sbt plugin `sbt-buildinfo`
    // org.apache.spark.sql.execution.datasources.index.lucenerdd.BuildInfo.toMap
    null
  }
}
