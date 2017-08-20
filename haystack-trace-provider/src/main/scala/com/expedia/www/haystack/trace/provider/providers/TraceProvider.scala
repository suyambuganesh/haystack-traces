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

package com.expedia.www.haystack.trace.provider.providers

import com.expedia.open.tracing.internal._
import com.expedia.www.haystack.trace.provider.config.entities.{CassandraConfiguration, ElasticSearchConfiguration}
import com.expedia.www.haystack.trace.provider.metrics.MetricsSupport
import com.expedia.www.haystack.trace.provider.readers.cassandra.CassandraReader
import io.grpc.stub.StreamObserver
import org.slf4j.LoggerFactory
import scala.concurrent.ExecutionContext.Implicits.global

class TraceProvider(elasticSearchConfiguration: ElasticSearchConfiguration, cassandraConfiguration: CassandraConfiguration)
  extends TraceProviderGrpc.TraceProviderImplBase
    with GrpcResponseHandler
    with MetricsSupport {

  val cassandraReader = new CassandraReader(cassandraConfiguration)

  val handleSearchResponse = handle[TracesSearchResult](
    LoggerFactory.getLogger(TraceProviderGrpc.METHOD_SEARCH_TRACES.getFullMethodName),
    metricRegistry.timer(TraceProviderGrpc.METHOD_SEARCH_TRACES.getFullMethodName),
    metricRegistry.meter(s"${TraceProviderGrpc.METHOD_SEARCH_TRACES.getFullMethodName}.failures")) _

  val handleGetResponse = handle[Trace](
    LoggerFactory.getLogger(TraceProviderGrpc.METHOD_GET_TRACE.getFullMethodName),
    metricRegistry.timer(TraceProviderGrpc.METHOD_GET_TRACE.getFullMethodName),
    metricRegistry.meter(s"${TraceProviderGrpc.METHOD_GET_TRACE.getFullMethodName}.failures")) _

  override def searchTraces(request: TracesSearchRequest, responseObserver: StreamObserver[TracesSearchResult]): Unit = {
    handleSearchResponse(responseObserver) {
      // TODO search in elasticsearch and get further details from cassandra
      null
    }
  }

  override def getTrace(request: TraceRequest, responseObserver: StreamObserver[Trace]): Unit = {
    handleGetResponse(responseObserver) {
      // TODO create a layer to construct query and fetch/process trace got from cassandra
      cassandraReader.read(s"SELECT * FROM spans WHERE traceId=${request.getTraceId};")
    }
  }
}
