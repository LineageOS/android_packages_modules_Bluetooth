/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.bluetooth;

import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;

import java.util.Iterator;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

public class StreamObserverSpliterator<ReqT, RespT>
        implements Spliterator<RespT>, ClientResponseObserver<ReqT, RespT> {
    private static final Object COMPLETED_INDICATOR = new Object();

    private final BlockingQueue<Object> mQueue = new LinkedBlockingQueue<>();

    private ClientCallStreamObserver<ReqT> mRequestStream;

    /**
     * Creates and returns an iterator over the elements contained in the internal blocking queue.
     *
     * <p>The iterator is based on this class's Spliterator implementation. As elements are consumed
     * from the iterator, they are removed from the queue. The iterator continues to provide
     * elements as long as new items are added to the queue via the onNext method or until the
     * onCompleted method is called.
     *
     * <p>If the onError method was called previously and the corresponding Throwable is retrieved
     * by the iterator, it will throw a RuntimeException wrapping the original Throwable.
     *
     * @return an iterator over the elements contained in the internal blocking queue
     */
    public Iterator<RespT> iterator() {
        return Spliterators.iterator(this);
    }

    /** Cancels the ongoing call. See {@link ClientCallStreamObserver#cancel(String, Throwable)}. */
    public void cancel(String message) {
        if (mRequestStream != null) {
            mRequestStream.cancel(message, null);
        } else {
            throw new UnsupportedOperationException(
                    "Canceling operation is not supported when request type is missing!");
        }
    }

    @Override
    public void beforeStart(ClientCallStreamObserver<ReqT> requestStream) {
        mRequestStream = requestStream;
    }

    @Override
    public int characteristics() {
        return ORDERED | NONNULL;
    }

    @Override
    public long estimateSize() {
        return Long.MAX_VALUE;
    }

    @Override
    public boolean tryAdvance(Consumer<? super RespT> action) {
        try {
            Object item = mQueue.take();
            if (item == COMPLETED_INDICATOR) {
                return false;
            }
            if (item instanceof Throwable) {
                throw new RuntimeException((Throwable) item);
            }
            action.accept((RespT) item);
            return true;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Spliterator<RespT> trySplit() {
        return null;
    }

    @Override
    public void onNext(RespT value) {
        mQueue.add(value);
    }

    @Override
    public void onError(Throwable t) {
        mQueue.add(t);
    }

    @Override
    public void onCompleted() {
        mQueue.add(COMPLETED_INDICATOR);
    }
}
