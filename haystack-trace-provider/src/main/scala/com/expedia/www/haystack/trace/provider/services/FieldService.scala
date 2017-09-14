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

package com.expedia.www.haystack.trace.provider.services

import com.expedia.open.tracing.api._
import com.expedia.www.haystack.trace.provider.stores.TraceStore
import io.grpc.stub.StreamObserver

import scala.concurrent.ExecutionContextExecutor

class FieldService(traceStore: TraceStore)(implicit val executor: ExecutionContextExecutor) extends FieldProviderGrpc.FieldProviderImplBase {
  override def getFieldNames(request: Empty, responseObserver: StreamObserver[FieldNames]): Unit = ???
  override def getFieldCardinality(request: FieldQuery, responseObserver: StreamObserver[FieldCardinality]): Unit = ???
  override def getFieldValues(request: FieldQuery, responseObserver: StreamObserver[FieldValues]): Unit = ???
}
