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

package org.apache.spark.sql.execution.datasources.index.lucenerdd.partition

import org.apache.lucene.search.BooleanClause
import org.apache.spark.sql.execution.datasources.index.lucenerdd.models.{SparkFacetResult, SparkScoreDoc}
import org.apache.spark.sql.execution.datasources.index.lucenerdd.response.LuceneRDDResponsePartition

import scala.reflect.ClassTag

/**
 * LuceneRDD partition.
 *
 * @tparam T the type associated with each entry in the set.
 */
private[lucenerdd] abstract class AbstractLuceneRDDPartition[T] extends Serializable
  with AutoCloseable {

  protected implicit def kTag: ClassTag[T]

  def size: Long

  def iterator: Iterator[T]

//  def isDefined(key: T): Boolean

  def fields(): Set[String]

  /**
   * Multi term query
   *
   * @param docMap Map of field names to terms
   * @param topK Number of documents to return
   * @return
   */
  def multiTermQuery(docMap: Map[String, String],
                     topK: Int,
                     boolClause: BooleanClause.Occur = BooleanClause.Occur.MUST)
  : LuceneRDDResponsePartition


  /**
   * Generic Lucene Query using QueryParser
   * @param defaultField Default query field
   * @param searchString Lucene query string, i.e., textField:hello*
   * @param topK Number of documents to return
   * @return
   */
  def query(defaultField: String, searchString: String, topK: Int): LuceneRDDResponsePartition

  /**
   * Multiple generic Lucene Queries using QueryParser
   * @param defaultField Default query field
   * @param searchString  Lucene query string
   * @param topK Number of results to return
   * @return
   */
  def queries(defaultField: String, searchString: Iterable[String], topK: Int)
  : Iterable[(String, LuceneRDDResponsePartition)]

  /**
   * Generic Lucene faceted Query using QueryParser
   * @param searchString Lucene query string, i.e., textField:hello*
   * @param topK Number of facets to return
   * @return
   */
  def facetQuery(searchString: String, facetField: String, topK: Int)
  : SparkFacetResult

  /**
   * Term Query
   * @param fieldName Name of field
   * @param query Query text
   * @param topK Number of documents to return
   * @return
   */
  def termQuery(fieldName: String, query: String, topK: Int): LuceneRDDResponsePartition

  /**
   * Prefix Query
   * @param fieldName Name of field
   * @param query Prefix query
   * @param topK Number of documents to return
   * @return
   */
  def prefixQuery(fieldName: String, query: String, topK: Int): LuceneRDDResponsePartition

  /**
   * Fuzzy Query
   * @param fieldName Name of field
   * @param query Query text
   * @param maxEdits Fuzziness, edit distance
   * @param topK Number of documents to return
   * @return
   */
  def fuzzyQuery(fieldName: String, query: String,
                 maxEdits: Int, topK: Int): LuceneRDDResponsePartition

  /**
   * PhraseQuery
   * @param fieldName Name of field
   * @param query Phrase query, i.e., "hello world"
   * @param topK Number of documents to return
   * @return
   */
  def phraseQuery(fieldName: String, query: String, topK: Int): LuceneRDDResponsePartition

  /**
   * Restricts the entries to those satisfying a predicate
   * @param pred Predicate to filter on
   * @return
   */
  def filter(pred: T => Boolean): AbstractLuceneRDDPartition[T]
}
