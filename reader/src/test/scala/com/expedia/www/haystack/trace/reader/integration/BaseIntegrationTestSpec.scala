/*
 *  Copyright 2017 Expedia, Inc.
 *
 *       Licensed under the Apache License, Version 2.0 (the "License");
 *       you may not use this file except in compliance with the License.
 *      You may obtain a copy of the License at
 *
 *           http://www.apache.org/licenses/LICENSE-2.0
 *
 *       Unless required by applicable law or agreed to in writing, software
 *       distributed under the License is distributed on an "AS IS" BASIS,
 *       WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *       See the License for the specific language governing permissions and
 *       limitations under the License.
 */

package com.expedia.www.haystack.trace.reader.integration

import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.concurrent.Executors
import java.util.{Date, UUID}

import com.datastax.driver.core.querybuilder.QueryBuilder
import com.datastax.driver.core.{Cluster, ResultSet, Session, SimpleStatement}
import com.expedia.open.tracing.Span
import com.expedia.open.tracing.api.TraceReaderGrpc
import com.expedia.open.tracing.api.TraceReaderGrpc.TraceReaderBlockingStub
import com.expedia.open.tracing.buffer.SpanBuffer
import com.expedia.www.haystack.trace.commons.clients.cassandra.CassandraTableSchema
import com.expedia.www.haystack.trace.commons.clients.es.document.TraceIndexDoc
import com.expedia.www.haystack.trace.commons.config.entities.{WhiteListIndexFields, WhitelistIndexField}
import com.expedia.www.haystack.trace.reader.Service
import io.grpc.ManagedChannelBuilder
import io.searchbox.client.config.HttpClientConfig
import io.searchbox.client.{JestClient, JestClientFactory}
import io.searchbox.core.Index
import io.searchbox.indices.CreateIndex
import org.json4s.DefaultFormats
import org.json4s.jackson.Serialization
import org.scalatest._
import collection.JavaConverters._
import scala.collection.mutable

trait BaseIntegrationTestSpec extends FunSpec with GivenWhenThen with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {
  protected implicit val formats = DefaultFormats
  protected var client: TraceReaderBlockingStub = _

  private val CASSANDRA_ENDPOINT = "cassandra"
  private val CASSANDRA_KEYSPACE = "haystack"
  private val CASSANDRA_TABLE = "traces"

  private val ELASTIC_SEARCH_ENDPOINT = "http://elasticsearch:9200"
  private val ELASTIC_SEARCH_WHITELIST_INDEX = "reload-configs"
  private val ELASTIC_SEARCH_WHITELIST_TYPE = "whitelist-index-fields"
  private val SPANS_INDEX_TYPE = "spans"

  private val executors = Executors.newSingleThreadExecutor()

  private val HAYSTACK_TRACES_INDEX = {
    val formatter = new SimpleDateFormat("yyyy-MM-dd")
    s"haystack-traces-${formatter.format(new Date())}"
  }
  private val INDEX_TEMPLATE = """{
                                 |    "template": "haystack-traces*",
                                 |    "settings": {
                                 |        "number_of_shards": 1,
                                 |        "index.mapping.ignore_malformed": true,
                                 |        "analysis": {
                                 |            "normalizer": {
                                 |                "lowercase_normalizer": {
                                 |                    "type": "custom",
                                 |                    "filter": ["lowercase"]
                                 |                }
                                 |            }
                                 |        }
                                 |    },
                                 |    "aliases": {
                                 |        "haystack-traces": {}
                                 |    },
                                 |    "mappings": {
                                 |        "spans": {
                                 |            "_all": {
                                 |                "enabled": false
                                 |            },
                                 |            "_source": {
                                 |                "includes": ["traceid"]
                                 |            },
                                 |            "properties": {
                                 |                "spans": {
                                 |                    "type": "nested",
                                 |                    "properties": {
                                 |                        "servicename": {
                                 |                            "type": "keyword",
                                 |                            "normalizer": "lowercase_normalizer",
                                 |                            "doc_values": true,
                                 |                            "norms": false
                                 |                        },
                                 |                        "operationname": {
                                 |                            "type": "keyword",
                                 |                            "normalizer": "lowercase_normalizer",
                                 |                            "doc_values": true,
                                 |                            "norms": false
                                 |                        }
                                 |                    }
                                 |                }
                                 |            },
                                 |            "dynamic_templates": [{
                                 |                "strings_as_keywords_1": {
                                 |                    "match_mapping_type": "string",
                                 |                    "mapping": {
                                 |                        "type": "keyword",
                                 |                        "normalizer": "lowercase_normalizer",
                                 |                        "doc_values": false,
                                 |                        "norms": false
                                 |                    }
                                 |                }
                                 |            }, {
                                 |                "longs_disable_doc_norms": {
                                 |                    "match_mapping_type": "long",
                                 |                    "mapping": {
                                 |                        "type": "long",
                                 |                        "doc_values": false,
                                 |                        "norms": false
                                 |                    }
                                 |                }
                                 |            }]
                                 |        }
                                 |    }
                                 |}
                                 |""".stripMargin


  private var cassandraSession: Session = _
  private var esClient: JestClient = _

  override def beforeAll() {
    // setup cassandra
    cassandraSession = Cluster
      .builder()
      .addContactPoints(CASSANDRA_ENDPOINT)
      .build()
      .connect(CASSANDRA_KEYSPACE)
    deleteCassandraTableRows()

    // setup elasticsearch
    val factory = new JestClientFactory()
    factory.setHttpClientConfig(
      new HttpClientConfig.Builder(ELASTIC_SEARCH_ENDPOINT)
        .multiThreaded(true)
        .build())
    esClient = factory.getObject
    esClient.execute(new CreateIndex.Builder(HAYSTACK_TRACES_INDEX)
      .settings(INDEX_TEMPLATE)
      .build)

    executors.submit(new Runnable {
      override def run(): Unit = Service.main(null)
    })

    Thread.sleep(5000)

    client = TraceReaderGrpc.newBlockingStub(ManagedChannelBuilder.forAddress("localhost", 8088)
      .usePlaintext(true)
      .build())
  }

  private def deleteCassandraTableRows(): Unit = {
    cassandraSession.execute(new SimpleStatement(s"TRUNCATE $CASSANDRA_TABLE"))
  }

  protected def putTraceInCassandraAndEs(traceId: String = UUID.randomUUID().toString,
                                         spanId: String = UUID.randomUUID().toString,
                                         serviceName: String = "",
                                         operationName: String = "",
                                         tags: Map[String, String] = Map.empty): Unit = {
    insertTraceInCassandra(traceId, spanId, serviceName, operationName, tags)
    insertTraceInEs(traceId, spanId, serviceName, operationName, tags)
  }

  private def insertTraceInEs(traceId: String,
                              spanId: String,
                              serviceName: String,
                              operationName: String,
                              tags: Map[String, String]) = {
    import TraceIndexDoc._
    // create map using service, operation and tags
    val fieldMap = mutable.Map(
      SPAN_ID_KEY_NAME -> spanId,
      SERVICE_KEY_NAME -> serviceName,
      OPERATION_KEY_NAME -> operationName,
      START_TIME_KEY_NAME -> (System.currentTimeMillis() * 1000)
    )
    tags.foreach(pair => fieldMap.put(pair._1.toLowerCase(), pair._2))

    // index the document
    val result = esClient.execute(new Index.Builder(TraceIndexDoc(traceId, 0, Seq(fieldMap)).json)
      .index(HAYSTACK_TRACES_INDEX)
      .`type`(SPANS_INDEX_TYPE)
      .build)

    if(result.getErrorMessage != null) {
      fail("Fail to execute the indexing request " + result.getErrorMessage)
    }
    // wait for few sec to let ES refresh its index
    Thread.sleep(5000)
  }

  protected def putWhitelistIndexFieldsInEs(fields: List[String]): Unit = {
    val whitelistFields = for(field <- fields) yield WhitelistIndexField(field, "string")
    esClient.execute(new Index.Builder(Serialization.write(WhiteListIndexFields(whitelistFields)))
      .index(ELASTIC_SEARCH_WHITELIST_INDEX)
      .`type`(ELASTIC_SEARCH_WHITELIST_TYPE)
      .build)

    // wait for few sec to let ES refresh its index and app to reload its config
    Thread.sleep(10000)
  }

  private def insertTraceInCassandra(traceId: String,
                                     spanId: String,
                                     serviceName: String,
                                     operationName: String,
                                     tags: Map[String, String]): ResultSet = {
    import CassandraTableSchema._
    val spanBuffer = createSpanBufferWithSingleSpan(traceId, spanId, serviceName, operationName, tags)

    cassandraSession.execute(QueryBuilder
      .insertInto(CASSANDRA_TABLE)
      .value(ID_COLUMN_NAME, traceId)
      .value(TIMESTAMP_COLUMN_NAME, new Date())
      .value(SPANS_COLUMN_NAME, ByteBuffer.wrap(spanBuffer.toByteArray)))
  }

  private def createSpanBufferWithSingleSpan(traceId: String,
                                             spanId: String,
                                             serviceName: String,
                                             operationName: String,
                                             tags: Map[String, String]) = {
    val spanTags = tags.map(tag => com.expedia.open.tracing.Tag.newBuilder().setKey(tag._1).setVStr(tag._2).build())

    SpanBuffer
      .newBuilder()
      .setTraceId(traceId)
      .addChildSpans(Span
        .newBuilder()
        .setTraceId(traceId)
        .setSpanId(spanId)
        .setOperationName(operationName)
        .setServiceName(serviceName)
        .addAllTags(spanTags.asJava)
        .build())
      .build()
  }

  protected def putTraceInCassandra(traceId: String,
                                    spanId: String = UUID.randomUUID().toString,
                                    serviceName: String = "",
                                    operationName: String = "",
                                    tags: Map[String, String] = Map.empty): ResultSet = {
    insertTraceInCassandra(traceId, spanId, serviceName, operationName, tags)
  }
}
