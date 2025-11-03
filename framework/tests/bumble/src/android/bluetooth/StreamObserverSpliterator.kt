/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.bluetooth

import android.util.Log
import io.grpc.stub.ClientCallStreamObserver
import io.grpc.stub.ClientResponseObserver
import java.util.Spliterator
import java.util.Spliterators
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.function.Consumer

private const val TAG = "StreamObserverSpliterator"

class StreamObserverSpliterator<ReqT, RespT> :
    Spliterator<RespT>, ClientResponseObserver<ReqT, RespT> {

    private val queue: BlockingQueue<Any> = LinkedBlockingQueue()
    private var requestStream: ClientCallStreamObserver<ReqT>? = null

    /**
     * Creates and returns an iterator over the elements contained in the internal blocking queue.
     *
     * The iterator is based on this class's Spliterator implementation. As elements are consumed
     * from the iterator, they are removed from the queue. The iterator continues to provide
     * elements as long as new items are added to the queue via the onNext method or until the
     * onCompleted method is called.
     *
     * If the onError method was called previously and the corresponding Throwable is retrieved by
     * the iterator, it will throw a RuntimeException wrapping the original Throwable.
     *
     * @return an iterator over the elements contained in the internal blocking queue
     */
    fun iterator(): Iterator<RespT> = Spliterators.iterator(this)

    /** Cancels the ongoing call. See [ClientCallStreamObserver.cancel]. */
    fun cancel(message: String) {
        val stream = requestStream
        if (stream != null) {
            stream.cancel(message, null)
            try {
                Thread.sleep(WAIT_TIME_FOR_CANCEL_MS)
            } catch (e: Exception) {
                Log.e(TAG, "Exception happened while waiting for cancel", e)
            }
        } else {
            throw UnsupportedOperationException(
                "Canceling operation is not supported when request type is missing!"
            )
        }
    }

    override fun beforeStart(requestStream: ClientCallStreamObserver<ReqT>) {
        this@StreamObserverSpliterator.requestStream = requestStream
    }

    override fun characteristics() = Spliterator.ORDERED or Spliterator.NONNULL

    override fun estimateSize() = Long.MAX_VALUE

    override fun tryAdvance(action: Consumer<in RespT>): Boolean {
        try {
            when (val item = queue.take()) {
                COMPLETED_INDICATOR -> return false
                is Throwable -> throw RuntimeException(item)
                else -> {
                    @Suppress("UNCHECKED_CAST") action.accept(item as RespT)
                    return true
                }
            }
        } catch (e: InterruptedException) {
            throw RuntimeException(e)
        }
    }

    override fun trySplit(): Spliterator<RespT>? = null

    override fun onNext(value: RespT) {
        queue.add(value)
    }

    override fun onError(t: Throwable) {
        queue.add(t)
    }

    override fun onCompleted() {
        queue.add(COMPLETED_INDICATOR)
    }

    private companion object {
        private val COMPLETED_INDICATOR = Any()
        private const val WAIT_TIME_FOR_CANCEL_MS = 100L
    }
}
