/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.os.test.TestLooper;

import androidx.test.filters.SmallTest;

import com.google.common.truth.Expect;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Test cases for {@link CallbackWrapper}. */
@SmallTest
@RunWith(JUnit4.class)
public class CallbackWrapperTest {

    @Rule public final MockitoRule mockito = MockitoJUnit.rule();
    @Rule public Expect expect = Expect.create();

    private final Consumer<int[]> mRegisterConsumer = (int[] counter) -> counter[0]++;
    private final Consumer<int[]> mUnregisterConsumer = (int[] counter) -> counter[0]--;
    private final int[] mUnusedCounter = {0};

    private interface Callback {
        void onCallbackCalled();
    }

    @Mock private Callback mCallback;
    @Mock private Callback mCallback2;

    private TestLooper mLooper;
    private Executor mExecutor;
    private Map<Callback, Executor> mCallbackExecutorMap;
    private CallbackWrapper<Callback, int[]> mCallbackWrapper;

    @Before
    public void setUp() {
        mLooper = new TestLooper();
        mExecutor = mLooper.getNewExecutor();
        mCallbackExecutorMap = new HashMap();
        mCallbackWrapper =
                new CallbackWrapper(mRegisterConsumer, mUnregisterConsumer, mCallbackExecutorMap);
    }

    @After
    public void tearDown() {
        assertThat(mLooper.nextMessage()).isNull();
    }

    @Test
    public void registerCallback_enforceValidParams() {
        assertThrows(
                NullPointerException.class,
                () -> mCallbackWrapper.registerCallback(mUnusedCounter, mCallback, null));
        assertThrows(
                NullPointerException.class,
                () -> mCallbackWrapper.registerCallback(mUnusedCounter, null, mExecutor));

        // Service can be null, the following should not crash
        mCallbackWrapper.registerCallback(null, mCallback, mExecutor);
        assertThat(mCallbackExecutorMap).containsExactly(mCallback, mExecutor);
    }

    @Test
    public void unregisterCallback_enforceValidParams() {
        assertThrows(
                NullPointerException.class,
                () -> mCallbackWrapper.unregisterCallback(mUnusedCounter, null));
    }

    @Test
    public void registerToNewService_enforceValidParams() {
        assertThrows(NullPointerException.class, () -> mCallbackWrapper.registerToNewService(null));
    }

    @Test
    public void registerCallback_whenEmpty_callConsumer() {
        int[] counter = {0};

        mCallbackWrapper.registerCallback(counter, mCallback, mExecutor);

        assertThat(counter[0]).isEqualTo(1);
        assertThat(mCallbackExecutorMap).containsExactly(mCallback, mExecutor);
    }

    @Test
    public void unregisterCallback_whenRegistered_callConsumer() {
        mCallbackWrapper.registerCallback(mUnusedCounter, mCallback, mExecutor);
        int[] counter = {0};

        mCallbackWrapper.unregisterCallback(counter, mCallback);

        assertThat(counter[0]).isEqualTo(-1);
        assertThat(mCallbackExecutorMap).isEmpty();
    }

    @Test
    public void unregisterCallbackWithNoService_whenRegistered_stillRemovedFromMap() {
        mCallbackWrapper.registerCallback(mUnusedCounter, mCallback, mExecutor);

        mCallbackWrapper.unregisterCallback(null, mCallback);

        assertThat(mCallbackExecutorMap).isEmpty();
    }

    @Test
    public void registerCallback_whenWrapperAlreadyRegisteredToService_doNothing() {
        mCallbackWrapper.registerCallback(mUnusedCounter, mCallback, mExecutor);
        int[] counter = {0};

        mCallbackWrapper.registerCallback(counter, mCallback2, mExecutor);

        assertThat(counter[0]).isEqualTo(0);
        assertThat(mCallbackExecutorMap)
                .containsExactly(mCallback, mExecutor, mCallback2, mExecutor);
    }

    @Test
    public void unregisterCallback_whenMultiplesCallbackAreRegister_doNothing() {
        mCallbackWrapper.registerCallback(mUnusedCounter, mCallback, mExecutor);
        mCallbackWrapper.registerCallback(mUnusedCounter, mCallback2, mExecutor);
        int[] counter = {0};

        mCallbackWrapper.unregisterCallback(counter, mCallback2);

        assertThat(counter[0]).isEqualTo(0);
        assertThat(mCallbackExecutorMap).containsExactly(mCallback, mExecutor);
    }

    @Test
    public void registerCallback_whenCallbackAlreadyRegistered_throwException() {
        mCallbackWrapper.registerCallback(null, mCallback, mExecutor);

        assertThrows(
                IllegalArgumentException.class,
                () -> mCallbackWrapper.registerCallback(null, mCallback, mExecutor));
        assertThat(mCallbackExecutorMap).containsExactly(mCallback, mExecutor);
    }

    @Test
    public void unregisterCallback_whenCallbackNotRegistered_throwException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> mCallbackWrapper.unregisterCallback(null, mCallback));
        assertThat(mCallbackExecutorMap).isEmpty();
    }

    @Test
    public void registerToNewService_whenNoCallback_doNothing() {
        int[] counter = {0};

        mCallbackWrapper.registerToNewService(counter);

        assertThat(counter[0]).isEqualTo(0);
    }

    @Test
    public void registerToNewService_whenCallback_callConsumer() {
        mCallbackWrapper.registerCallback(null, mCallback, mExecutor);
        int[] counter = {0};

        mCallbackWrapper.registerToNewService(counter);

        assertThat(counter[0]).isEqualTo(1);
    }

    @Test
    public void triggerCallback_whenRegistered_dispatch() {
        mCallbackWrapper.registerCallback(mUnusedCounter, mCallback, mExecutor);
        mCallbackWrapper.registerCallback(mUnusedCounter, mCallback2, mExecutor);

        mCallbackWrapper.forEach((cb) -> cb.onCallbackCalled());

        assertThat(mLooper.dispatchAll()).isEqualTo(2);

        verify(mCallback).onCallbackCalled();
        verify(mCallback2).onCallbackCalled();
    }

    @Test
    public void triggerCallback_whenRegistered_dispatchOnlyOnCurrentlyRegistered() {
        mCallbackWrapper.registerCallback(mUnusedCounter, mCallback, mExecutor);
        mCallbackWrapper.registerCallback(mUnusedCounter, mCallback2, mExecutor);
        mCallbackWrapper.unregisterCallback(mUnusedCounter, mCallback2);

        mCallbackWrapper.forEach((cb) -> cb.onCallbackCalled());

        assertThat(mLooper.dispatchAll()).isEqualTo(1);

        verify(mCallback).onCallbackCalled();
        verify(mCallback2, never()).onCallbackCalled();
    }
}
