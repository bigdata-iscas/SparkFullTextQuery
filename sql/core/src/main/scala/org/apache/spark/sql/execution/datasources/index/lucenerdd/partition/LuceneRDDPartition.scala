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

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.lucene.document._
import org.apache.lucene.facet.taxonomy.directory.DirectoryTaxonomyReader
import org.apache.lucene.index.{DirectoryReader, IndexOptions}
import org.apache.lucene.search._
import org.apache.lucene.store.Directory
import org.apache.solr.store.hdfs.HdfsDirectory
import org.apache.spark.SparkContext
import org.apache.spark.deploy.SparkHadoopUtil
import org.apache.spark.sql.execution.datasources.index.lucenerdd.AllFields
import org.apache.spark.sql.{Row, SparkSession}
import org.apache.spark.sql.internal.SessionState
import org.joda.time.DateTime
import org.apache.spark.sql.execution.datasources.index.lucenerdd.facets.FacetedLuceneRDD
import org.apache.spark.sql.execution.datasources.index.lucenerdd.models.{SparkFacetResult, SparkScoreDoc}
import org.apache.spark.sql.execution.datasources.index.lucenerdd.query.LuceneQueryHelpers
import org.apache.spark.sql.execution.datasources.index.lucenerdd.response.LuceneRDDResponsePartition
import org.apache.spark.sql.execution.datasources.index.lucenerdd.store.{IndexWithTaxonomyWriter, Status}

import scala.reflect.{ClassTag, _}

private[lucenerdd] class LuceneRDDPartition[T]
(private val iter: Iterator[T])(conf: Configuration)(
  path: String, status: Status)
(implicit docConversion: T => Document,
 override implicit val kTag: ClassTag[T])
  extends AbstractLuceneRDDPartition[T]
  with IndexWithTaxonomyWriter {

  logInfo("Instance is created...")
  status match {
    case Status.Rewrite =>
      val deletePath = new Path(path)
      val deleteDf = FileSystem.get(conf)
      if(deleteDf.exists(deletePath)) {
        logInfo(s"Delete existing path recursively: ${deletePath}...")
        deleteDf.delete(deletePath, true)
      }
    case _ =>
  }
  val time = System.currentTimeMillis



  override protected val IndexDir: Directory = {
    val fullPath = status match {
      case Status.Rewrite =>
        s"${
          path}/indexDirectory.${time}.${
          Thread.currentThread().getId}"
      case Status.Exists =>
        path
    }
    val hdfsPath = new Path(fullPath)
    val fs = hdfsPath.getFileSystem(conf)
    val qualified = hdfsPath.makeQualified(fs.getUri, fs.getWorkingDirectory)
    // val globPath = SparkHadoopUtil.get.globPathIfNecessary(qualified)
    logInfo(s"HDFS Index Diretory init: ${qualified}")
    new HdfsDirectory(qualified, conf)
  }

  override protected val TaxonomyDir: Directory = {
    val fullPath = status match {
      case Status.Rewrite =>
        s"${
          path}/taxonomyDirectory.${time}.${
          Thread.currentThread().getId}"
      case Status.Exists =>
        path.replaceAll("indexDirectory", "taxonomyDirectory")
    }
    val hdfsPath = new Path(fullPath)
    val fs = hdfsPath.getFileSystem(conf)
    val qualified = hdfsPath.makeQualified(fs.getUri, fs.getWorkingDirectory)
    // val globPath = SparkHadoopUtil.get.globPathIfNecessary(qualified)
    logInfo(s"HDFS Taxonomy Diretory init: ${qualified}")
    new HdfsDirectory(qualified, conf)
  }
  private val (iterOriginal, iterIndex) = iter.duplicate

  status match {
    case Status.Rewrite =>
      val startTime = new DateTime(System.currentTimeMillis())
      logInfo(s"Indexing process initiated at ${startTime}...")
      iterIndex.foreach { case elem =>
        // (implicitly) convert type T to Lucene document
        logInfo(s"Process data: ${elem}...")
        val doc = docConversion(elem)
        indexWriter.addDocument(FacetsConfig.build(taxoWriter, doc))
      }
      val endTime = new DateTime(System.currentTimeMillis())
      logInfo(s"Indexing process completed at ${endTime}...")
      logInfo(s"Indexing process took ${(endTime.getMillis
        - startTime.getMillis) / 1000} seconds...")

      // Close the indexWriter and taxonomyWriter (for faceted search)
      closeAllWriters()
      logDebug("Closing index writers...")
    case Status.Exists =>
      logInfo(s"No need index built from exiting index")
  }



  logDebug("Instantiating index/facet readers")
  private val indexReader = DirectoryReader.open(IndexDir)
  private val indexSearcher = new IndexSearcher(indexReader)
  private val taxoReader = new DirectoryTaxonomyReader(TaxonomyDir)
  logDebug("Index readers instantiated successfully")
  logInfo(s"Indexed ${size} documents")

  override def fields(): Set[String] = {
    LuceneQueryHelpers.fields(indexSearcher)
  }

  override def size: Long = {
    LuceneQueryHelpers.totalDocs(indexSearcher)
  }

//  override def isDefined(elem: T): Boolean = {
//    iterOriginal.contains(elem)
//  }

  override def multiTermQuery(docMap: Map[String, String],
                              topK: Int,
                              booleanClause: BooleanClause.Occur = BooleanClause.Occur.MUST)
  : LuceneRDDResponsePartition = {
   val results = LuceneQueryHelpers.multiTermQuery(indexSearcher, docMap, topK,
     booleanClause: BooleanClause.Occur)

    LuceneRDDResponsePartition(results.toIterator)
  }

  override def iterator: Iterator[T] = {
    iterOriginal
  }

  override def filter(pred: T => Boolean): AbstractLuceneRDDPartition[T] =
    new LuceneRDDPartition(
      iterOriginal.filter(pred))(conf)(path, status)(docConversion, kTag)

  override def termQuery(fieldName: String, fieldText: String,
                         topK: Int = 1): LuceneRDDResponsePartition = {
    val results = LuceneQueryHelpers.termQuery(indexSearcher, fieldName, fieldText, topK)

    LuceneRDDResponsePartition(results.toIterator)
  }

  override def query(defaultField: String, searchString: String,
                     topK: Int): LuceneRDDResponsePartition = {
    val results = LuceneQueryHelpers.searchParser(indexSearcher,
      defaultField, searchString, topK)(Analyzer)

    LuceneRDDResponsePartition(results.toIterator)
  }

  override def queries(defaultField: String, searchStrings: Iterable[String],
                     topK: Int): Iterable[(String, LuceneRDDResponsePartition)] = {
    searchStrings.map( searchString =>
      (searchString, query(defaultField, searchString, topK))
    )
  }

  override def prefixQuery(fieldName: String, fieldText: String,
                           topK: Int): LuceneRDDResponsePartition = {
    val results = LuceneQueryHelpers.prefixQuery(indexSearcher, fieldName, fieldText, topK)

    LuceneRDDResponsePartition(results.toIterator)
  }

  override def fuzzyQuery(fieldName: String, fieldText: String,
                          maxEdits: Int, topK: Int): LuceneRDDResponsePartition = {
    val results = LuceneQueryHelpers
      .fuzzyQuery(indexSearcher, fieldName, fieldText, maxEdits, topK)

    LuceneRDDResponsePartition(results.toIterator)
  }

  override def phraseQuery(fieldName: String, fieldText: String,
                           topK: Int): LuceneRDDResponsePartition = {
    val results = LuceneQueryHelpers
      .phraseQuery(indexSearcher, fieldName, fieldText, topK)(Analyzer)

    LuceneRDDResponsePartition(results.toIterator)
  }

  override def facetQuery(searchString: String,
                          facetField: String,
                          topK: Int): SparkFacetResult = {
    LuceneQueryHelpers.facetedTextSearch(indexSearcher, taxoReader, FacetsConfig,
      searchString,
      facetField + FacetedLuceneRDD.FacetTextFieldSuffix,
      topK)(Analyzer)
  }
}

object  LuceneRDDPartition {
  def apply[T: ClassTag]
      (iter: Iterator[T], conf: Configuration, path: String, status: Status)(
      implicit docConversion: T => Document): LuceneRDDPartition[T] = {
    new LuceneRDDPartition[T](iter)(conf)(path, status)(docConversion, classTag[T])
  }
  def apply
  (indexColumns: Seq[String], iter: Iterator[Row], conf: Configuration,
   path: String, status: Status): LuceneRDDPartition[Row] = {
    implicit val columns = indexColumns.foldLeft(Set[String]())((set, column) => set + column)
    val Stored = Field.Store.YES
    /**
      * Compatible to div fieldType
      * @param s
      * @tparam T
      * @return
      */
    def typeToDocument[T: ClassTag](doc: Document, fieldName: String,
                                    s: T): Document = {
      s match {
        case x: String if x != null =>
          doc.add(new TextField(fieldName, x, Field.Store.YES))
        case x: Long if x != null =>
          doc.add(new LongField(fieldName, x, Field.Store.YES))
        case x: Int if x != null =>
          doc.add(new IntField(fieldName, x, Field.Store.YES))
        case x: Float if x != null =>
          doc.add(new FloatField(fieldName, x, Field.Store.YES))
        case x: Double if x != null =>
          doc.add(new DoubleField(fieldName, x, Field.Store.YES))
        case _ => Unit
      }
      doc
    }

    def typeToDocumentWithoutIndex[T: ClassTag](doc: Document, fieldName: String,
                                    s: T): Document = {
      s match {
        case x: String if x != null =>
          doc.add(new Field(fieldName, x, AllFields.notIndex_textFieldType))
        case x: Long if x != null =>
          doc.add(new LongField(fieldName, x, AllFields.notIndex_longFieldType))
        case x: Int if x != null =>
          doc.add(new IntField(fieldName, x, AllFields.notIndex_intFieldType))
        case x: Float if x != null =>
          doc.add(new FloatField(fieldName, x, AllFields.notIndex_floatFieldType))
        case x: Double if x != null =>
          doc.add(new DoubleField(fieldName, x, AllFields.notIndex_doubleFieldType))
        case _ => Unit
      }
      doc
    }
    def docConversion(row: Row)(implicit columns: Set[String]): Document = {
      val doc = new Document
      val fieldNames = row.schema.fieldNames
      fieldNames.foreach{ case fieldName =>
        val index = row.fieldIndex(fieldName)
        if(columns.contains(fieldName)) {
          typeToDocument(doc, fieldName, row.get(index))
        } else {
          typeToDocumentWithoutIndex(doc, fieldName, row.get(index))
        }

      }
      doc
    }
    new LuceneRDDPartition[Row](iter)(conf)(path, status)(docConversion, classTag[Row])
  }
}
