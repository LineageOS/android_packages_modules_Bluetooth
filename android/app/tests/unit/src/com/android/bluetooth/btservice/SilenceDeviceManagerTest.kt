/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.bluetooth.btservice

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
import android.content.Intent
import android.os.Bundle
import android.os.HandlerThread
import android.os.Looper
import android.platform.test.flag.junit.SetFlagsRule
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra
import androidx.test.filters.SmallTest
import com.android.bluetooth.TestUtils
import com.android.bluetooth.a2dp.A2dpService
import com.android.bluetooth.getTestDevice
import com.android.bluetooth.hfp.HeadsetService
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import java.util.Optional
import org.hamcrest.Matcher
import org.hamcrest.core.AllOf.allOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.InOrder
import org.mockito.Mock
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.hamcrest.MockitoHamcrest.argThat
import org.mockito.kotlin.whenever

/** Test cases for [SilenceDeviceManager]. */
@SmallTest
@RunWith(TestParameterInjector::class)
class SilenceDeviceManagerTest {
    @get:Rule val mockitoRule = MockitoRule()
    @get:Rule val setFlagsRule = SetFlagsRule()

    @Mock private lateinit var adapterService: AdapterService
    @Mock private lateinit var a2dpService: A2dpService
    @Mock private lateinit var headsetService: HeadsetService

    private val device = getTestDevice(28)

    private lateinit var inOrder: InOrder
    private lateinit var handlerThread: HandlerThread
    private lateinit var looper: Looper
    private lateinit var manager: SilenceDeviceManager

    @Before
    fun setUp() {
        doReturn(Optional.of(a2dpService)).whenever(adapterService).a2dpService
        doReturn(Optional.of(headsetService)).whenever(adapterService).headsetService
        inOrder = inOrder(adapterService)
        handlerThread = HandlerThread("SilenceManagerTestHandlerThread")
        handlerThread.start()
        looper = handlerThread.looper
        manager = SilenceDeviceManager(adapterService, looper)
    }

    @After
    fun tearDown() {
        manager.cleanup()
        handlerThread.quit()
    }

    @Test
    fun setGetDeviceSilence(
        @TestParameter wasSilenced: Boolean,
        @TestParameter enableSilence: Boolean,
    ) {
        doReturn(true).whenever(a2dpService).setSilenceMode(device, enableSilence)
        doReturn(true).whenever(headsetService).setSilenceMode(device, enableSilence)

        // Send A2DP/HFP connected intent
        a2dpConnected(device)
        headsetConnected(device)

        // Set pre-state for mSilenceDeviceManager
        if (wasSilenced) {
            assertThat(manager.setSilenceMode(device, true)).isTrue()
            TestUtils.waitForLooperToFinishScheduledTask(looper)
            verifySilenceStateIntent()
        }

        // Set silence state and check whether state changed successfully
        assertThat(manager.setSilenceMode(device, enableSilence)).isTrue()
        TestUtils.waitForLooperToFinishScheduledTask(looper)
        assertThat(manager.getSilenceMode(device)).isEqualTo(enableSilence)

        // Check for silence state changed intent
        if (wasSilenced != enableSilence) {
            verifySilenceStateIntent()
        }

        // Remove test devices
        a2dpDisconnected(device)
        headsetDisconnected(device)

        assertThat(manager.getSilenceMode(device)).isFalse()
        if (enableSilence) {
            // If the silence mode is enabled, it should be automatically disabled
            // after device is disconnected.
            verifyIntentSent()
        }
    }

    @Test
    fun testSetGetDeviceSilenceDisconnectedCase(@TestParameter enableSilence: Boolean) {
        // Set silence mode and it should stay disabled
        assertThat(manager.setSilenceMode(device, enableSilence)).isTrue()
        TestUtils.waitForLooperToFinishScheduledTask(looper)
        assertThat(manager.getSilenceMode(device)).isFalse()

        verifyNoIntentSent() // Should be no intent been broadcasted
    }

    /** Helper to indicate A2dp connected for a device. */
    private fun a2dpConnected(device: BluetoothDevice) {
        manager.a2dpConnectionStateChanged(device, STATE_DISCONNECTED, STATE_CONNECTED)
        TestUtils.waitForLooperToFinishScheduledTask(looper)
    }

    /** Helper to indicate A2dp disconnected for a device. */
    private fun a2dpDisconnected(device: BluetoothDevice) {
        manager.a2dpConnectionStateChanged(device, STATE_CONNECTED, STATE_DISCONNECTED)
        TestUtils.waitForLooperToFinishScheduledTask(looper)
    }

    /** Helper to indicate Headset connected for a device. */
    private fun headsetConnected(device: BluetoothDevice) {
        manager.hfpConnectionStateChanged(device, STATE_DISCONNECTED, STATE_CONNECTED)
        TestUtils.waitForLooperToFinishScheduledTask(looper)
    }

    /** Helper to indicate Headset disconnected for a device. */
    private fun headsetDisconnected(device: BluetoothDevice) {
        manager.hfpConnectionStateChanged(device, STATE_CONNECTED, STATE_DISCONNECTED)
        TestUtils.waitForLooperToFinishScheduledTask(looper)
    }

    private fun verifyNoIntentSent() {
        inOrder.verify(adapterService, never()).sendBroadcast(any(), any(), any())
    }

    @SafeVarargs
    private fun verifyIntentSent(vararg matchers: Matcher<Intent>) {
        inOrder
            .verify(adapterService)
            .sendBroadcast(argThat(allOf(*matchers)), eq(BLUETOOTH_CONNECT), any<Bundle>())
    }

    private fun verifySilenceStateIntent() {
        verifyIntentSent(
            hasAction(BluetoothDevice.ACTION_SILENCE_MODE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
        )
    }
}
