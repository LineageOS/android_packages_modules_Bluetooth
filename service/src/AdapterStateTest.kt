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

package com.android.server.bluetooth.test

import android.bluetooth.State
import com.android.server.bluetooth.BluetoothAdapterState
import com.android.server.bluetooth.Log
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@kotlinx.coroutines.ExperimentalCoroutinesApi
class BluetoothAdapterStateTest {
    @get:Rule val testName = TestName()

    lateinit var state: BluetoothAdapterState

    @Before
    fun setUp() {
        BluetoothAdapterState.disableCacheForTesting = true
        Log.i("BluetoothAdapterStateTest", "\t--> setup of ${testName.methodName}")
        state = BluetoothAdapterState()
    }

    @After
    fun tearDown() {
        BluetoothAdapterState.disableCacheForTesting = false
    }

    @Test
    fun init_isStateOff() {
        Log.d("BluetoothAdapterStateTest", "Initial state is $state")
        assertThat(state.get()).isEqualTo(State.OFF)
    }

    @Test
    fun get_afterBusy_returnLastValue() {
        val max = 10
        for (i in 0..max) state.set(i)
        assertThat(state.get()).isEqualTo(max)
    }

    @Test
    fun immediateReturn_whenStateIsAlreadyCorrect() = runTest {
        val stateNow = 10
        state.set(stateNow)
        assertThat(runBlocking { state.waitForState(100.days, stateNow) }).isTrue()
    }

    @Test fun expectTimeout() = runTest { assertThat(state.waitForState(100.days, -1)).isFalse() }

    @Test
    fun expectTimeout_CalledJavaApi() = runTest {
        assertThat(state.waitForState(java.time.Duration.ofMillis(10), -1)).isFalse()
    }

    @Test
    fun setState_whileWaiting() = runTest {
        val stateNow = 42
        val waiter = async { state.waitForState(100.days, stateNow) }
        state.set(stateNow)
        assertThat(waiter.await()).isTrue()
    }

    @Test
    fun concurrentWaiter_NoStateMissed() = runTest {
        val state0 = 42
        val state1 = 50
        val state2 = 65
        val waiter0 =
            async(start = CoroutineStart.UNDISPATCHED) { state.waitForState(100.days, state0) }
        val waiter1 =
            async(start = CoroutineStart.UNDISPATCHED) { state.waitForState(100.days, state1) }
        val waiter2 =
            async(start = CoroutineStart.UNDISPATCHED) { state.waitForState(100.days, state2) }
        val waiter3 =
            async(start = CoroutineStart.UNDISPATCHED) { state.waitForState(100.days, -1) }
        state.set(state0)
        yield()
        state.set(state1)
        yield()
        state.set(state2)
        assertThat(waiter0.await()).isTrue()
        assertThat(waiter1.await()).isTrue()
        assertThat(waiter2.await()).isTrue()
        assertThat(waiter3.await()).isFalse()
    }

    @Test
    fun expectTimeout_waitAfterOverride() = runTest {
        val state0 = 42
        val state1 = 50
        state.set(state0)
        yield()
        state.set(state1)
        val waiter =
            async(start = CoroutineStart.UNDISPATCHED) { state.waitForState(100.days, state0) }
        assertThat(waiter.await()).isFalse()
    }

    @Test
    fun oneOf_expectMatch() {
        val state0 = 42
        val state1 = 50
        state.set(state0)
        assertThat(state.oneOf(state0, state1)).isTrue()
    }

    @Test
    fun oneOf_expectNotMatch() {
        val state0 = 42
        val state1 = 50
        val state2 = 65
        state.set(state0)
        assertThat(state.oneOf(state1, state2)).isFalse()
    }
}
