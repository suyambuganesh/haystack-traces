/*
 *  Copyright 2017 Expedia, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */

package com.expedia.www.haystack.trace.commons.unit

import java.util.concurrent.atomic.AtomicInteger

import com.expedia.www.haystack.trace.commons.retries.{MaxRetriesAttemptedException, RetryOperation}
import org.scalatest.{FunSpec, Matchers}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class RetryOperationSpec extends FunSpec with Matchers {
  describe("Retry Operation handler") {
    it("should not retry if main function runs successfully") {
      @volatile var onSuccessCalled = 0
      val mainFuncCalled = new AtomicInteger(0)

      RetryOperation.executeAsyncWithRetryBackoff((callback) => {
        mainFuncCalled.incrementAndGet()
        Future {
          Thread.sleep(500)
          callback.onResult(false)
        }
      },
        RetryOperation.Config(maxRetries = 3, initialBackoffInMillis = 100, backoffFactor = 1.5),
        onSuccess = () => {
          onSuccessCalled = onSuccessCalled + 1
        }, onFailure = (_) => {
          fail("onFailure callback should not be called")
        })

      Thread.sleep(3000)
      mainFuncCalled.get() shouldBe 1
      onSuccessCalled shouldBe 1
    }
  }

  it("should retry if callback says retry but should not fail as last attempt succeeds") {
    @volatile var onSuccessCalled = 0
    val retryConfig = RetryOperation.Config(maxRetries = 3, initialBackoffInMillis = 100, backoffFactor = 1.5)
    val mainFuncCalled = new AtomicInteger(0)

    RetryOperation.executeAsyncWithRetryBackoff((callback) => {
      val count = mainFuncCalled.incrementAndGet()
      if (count < retryConfig.maxRetries) {
        Future {
          Thread.sleep(500)
          callback.onResult(true)
        }
      } else {
        Future {
          Thread.sleep(500)
          callback.onResult(false)
        }
      }
    },
      retryConfig,
      onSuccess = () => {
        onSuccessCalled = onSuccessCalled + 1
      }, onFailure = (_) => {
        fail("onFailure should not be called")
      })

    Thread.sleep(4000)
    mainFuncCalled.get() shouldBe retryConfig.maxRetries
    onSuccessCalled shouldBe 1
  }

  it("should retry if callback asks for a retry and fail finally as all attempts fail") {
    @volatile var onFailureCalled = 0
    val retryConfig = RetryOperation.Config(maxRetries = 2, initialBackoffInMillis = 100, backoffFactor = 1.5)
    val mainFuncCalled = new AtomicInteger(0)

    RetryOperation.executeAsyncWithRetryBackoff((callback) => {
      mainFuncCalled.incrementAndGet()
      Future {
        Thread.sleep(500)
        callback.onResult(true)
      }
    },
      retryConfig,
      onSuccess = () => {
        fail("onSuccess should not be called")
      }, onFailure = (ex) => {
        assert(ex.isInstanceOf[MaxRetriesAttemptedException])
        onFailureCalled = onFailureCalled + 1
      })

    Thread.sleep(4000)
    mainFuncCalled.get() shouldBe (retryConfig.maxRetries + 1)
    onFailureCalled shouldBe 1
  }

  it("retry operation backoff config should return the next backoff config") {
    val retry = RetryOperation.Config(3, 1000, 1.5)

    var nextBackoffConfig = retry.nextBackOffConfig
    nextBackoffConfig.maxRetries shouldBe 3
    nextBackoffConfig.nextBackOffConfig.backoffFactor shouldBe 1.5
    nextBackoffConfig.initialBackoffInMillis shouldBe 1500

    nextBackoffConfig = nextBackoffConfig.nextBackOffConfig
    nextBackoffConfig.maxRetries shouldBe 3
    nextBackoffConfig.nextBackOffConfig.backoffFactor shouldBe 1.5
    nextBackoffConfig.initialBackoffInMillis shouldBe 2250

  }
}