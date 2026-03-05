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

package com.android.bluetooth.hid

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.app.ActivityManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.bluetooth.BluetoothProfile.STATE_CONNECTING
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTING
import android.bluetooth.IBluetoothHidDeviceCallback
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Bundle
import android.os.Looper
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.android.bluetooth.TestLooper
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.getTestDevice
import com.android.bluetooth.mockGetSystemService
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import org.hamcrest.Matcher
import org.hamcrest.core.AllOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.InOrder
import org.mockito.Mock
import org.mockito.hamcrest.MockitoHamcrest.argThat
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Test cases for [HidDeviceService]. */
@MediumTest
@RunWith(AndroidJUnit4::class)
class HidDeviceServiceTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var adapterService: AdapterService
    @Mock private lateinit var nativeInterface: HidDeviceNativeInterface
    @Mock private lateinit var callback: IBluetoothHidDeviceCallback.Stub
    @Mock private lateinit var binder: Binder

    private val device = getTestDevice(87)

    private lateinit var service: HidDeviceService
    private lateinit var inOrder: InOrder
    private lateinit var looper: TestLooper
    private lateinit var settings: BluetoothHidDeviceAppSdpSettings

    @Before
    fun setUp() {
        if (Looper.myLooper() == null) {
            Looper.prepare()
        }

        adapterService.mockGetSystemService<ActivityManager>()
        doReturn(binder).whenever(callback).asBinder()

        inOrder = inOrder(adapterService)
        looper = TestLooper()

        service = HidDeviceService(adapterService, looper.looper, nativeInterface)
        service.isAvailable = true

        // Force unregister app first
        service.unregisterApp()

        // Dummy SDP settings
        settings =
            BluetoothHidDeviceAppSdpSettings(
                "Unit test",
                "test",
                "Android",
                BluetoothHidDevice.SUBCLASS1_COMBO,
                byteArrayOf(),
            )

        // Set up the Connection State Changed receiver
        val filter = IntentFilter()
        filter.priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        filter.addAction(BluetoothHidDevice.ACTION_CONNECTION_STATE_CHANGED)
    }

    @After
    fun tearDown() {
        service.cleanup()
    }

    private fun verifyConnectionStateIntent(newState: Int, prevState: Int) {
        verifyIntentSent(
            hasAction(BluetoothHidDevice.ACTION_CONNECTION_STATE_CHANGED),
            hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
            hasExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, prevState),
            hasExtra(BluetoothProfile.EXTRA_STATE, newState),
        )
    }

    @SafeVarargs
    private fun verifyIntentSent(vararg matchers: Matcher<Intent>) {
        inOrder
            .verify(adapterService)
            .sendBroadcast(
                argThat(AllOf.allOf(matchers.toList())),
                eq(BLUETOOTH_CONNECT),
                any<Bundle>(),
            )
    }

    /**
     * Test the logic in registerApp and unregisterApp. Should get a callback
     * onApplicationStateChangedFromNative.
     */
    @Test
    fun testRegistration() {
        doReturn(true)
            .whenever(nativeInterface)
            .registerApp(any(), any(), any(), any(), any(), anyOrNull(), anyOrNull())

        verify(nativeInterface, never())
            .registerApp(any(), any(), any(), any(), any(), anyOrNull(), anyOrNull())

        // Register app
        assertThat(service.registerApp(settings, null, null, callback)).isTrue()
        verify(nativeInterface)
            .registerApp(any(), any(), any(), any(), any(), anyOrNull(), anyOrNull())

        // App registered
        service.onApplicationStateChangedFromNative(device, true)
        assertThat(looper.dispatchAll()).isEqualTo(1)
        verify(callback).onAppStatusChanged(device, true)

        // Unregister app
        doReturn(true).whenever(nativeInterface).unregisterApp()
        assertThat(service.unregisterApp()).isTrue()

        verify(nativeInterface).unregisterApp()

        service.onApplicationStateChangedFromNative(device, false)
        assertThat(looper.dispatchAll()).isEqualTo(1)
        verify(callback).onAppStatusChanged(device, false)
    }

    /** Test the logic in sendReport(). This should fail when the app is not registered. */
    @Test
    fun testSendReport() {
        doReturn(true).whenever(nativeInterface).sendReport(any(), any())
        // sendReport() should fail without app registered
        assertThat(service.sendReport(device, SAMPLE_REPORT_ID.toInt(), SAMPLE_HID_REPORT))
            .isFalse()

        // Register app
        doReturn(true)
            .whenever(nativeInterface)
            .registerApp(any(), any(), any(), any(), any(), anyOrNull(), anyOrNull())
        assertThat(service.registerApp(settings, null, null, callback)).isTrue()

        // App registered
        service.onApplicationStateChangedFromNative(device, true)
        assertThat(looper.dispatchAll()).isEqualTo(1)
        verify(callback).onAppStatusChanged(device, true)

        // sendReport() should work when app is registered
        assertThat(service.sendReport(device, SAMPLE_REPORT_ID.toInt(), SAMPLE_HID_REPORT)).isTrue()

        verify(nativeInterface).sendReport(eq(SAMPLE_REPORT_ID.toInt()), eq(SAMPLE_HID_REPORT))

        // Unregister app
        doReturn(true).whenever(nativeInterface).unregisterApp()
        assertThat(service.unregisterApp()).isTrue()
    }

    /** Test the logic in replyReport(). This should fail when the app is not registered. */
    @Test
    fun testReplyReport() {
        doReturn(true).whenever(nativeInterface).replyReport(any(), any(), any())
        // replyReport() should fail without app registered
        assertThat(
                service.replyReport(device, SAMPLE_REPORT_TYPE, SAMPLE_REPORT_ID, SAMPLE_HID_REPORT)
            )
            .isFalse()

        // Register app
        doReturn(true)
            .whenever(nativeInterface)
            .registerApp(any(), any(), any(), any(), any(), anyOrNull(), anyOrNull())
        assertThat(service.registerApp(settings, null, null, callback)).isTrue()

        // App registered
        service.onApplicationStateChangedFromNative(device, true)
        assertThat(looper.dispatchAll()).isEqualTo(1)
        verify(callback).onAppStatusChanged(device, true)

        // replyReport() should work when app is registered
        assertThat(
                service.replyReport(device, SAMPLE_REPORT_TYPE, SAMPLE_REPORT_ID, SAMPLE_HID_REPORT)
            )
            .isTrue()

        verify(nativeInterface)
            .replyReport(eq(SAMPLE_REPORT_TYPE), eq(SAMPLE_REPORT_ID), eq(SAMPLE_HID_REPORT))

        // Unregister app
        doReturn(true).whenever(nativeInterface).unregisterApp()
        assertThat(service.unregisterApp()).isTrue()
    }

    /** Test the logic in reportError(). This should fail when the app is not registered. */
    @Test
    fun testReportError() {
        doReturn(true).whenever(nativeInterface).reportError(any())
        // reportError() should fail without app registered
        assertThat(service.reportError(device, SAMPLE_REPORT_ERROR)).isFalse()

        // Register app
        doReturn(true)
            .whenever(nativeInterface)
            .registerApp(any(), any(), any(), any(), any(), anyOrNull(), anyOrNull())
        assertThat(service.registerApp(settings, null, null, callback)).isTrue()

        // App registered
        service.onApplicationStateChangedFromNative(device, true)
        assertThat(looper.dispatchAll()).isEqualTo(1)
        verify(callback).onAppStatusChanged(device, true)

        // reportError() should work when app is registered
        assertThat(service.reportError(device, SAMPLE_REPORT_ERROR)).isTrue()

        verify(nativeInterface).reportError(eq(SAMPLE_REPORT_ERROR))

        // Unregister app
        doReturn(true).whenever(nativeInterface).unregisterApp()
        assertThat(service.unregisterApp()).isTrue()
    }

    /** Test that an outgoing connection/disconnection succeeds */
    @Test
    fun testOutgoingConnectDisconnectSuccess() {
        doReturn(true).whenever(nativeInterface).connect(any())
        doReturn(true).whenever(nativeInterface).disconnect()

        // Register app
        doReturn(true)
            .whenever(nativeInterface)
            .registerApp(any(), any(), any(), any(), any(), anyOrNull(), anyOrNull())
        service.registerApp(settings, null, null, null)

        // App registered
        service.onApplicationStateChangedFromNative(device, true)
        assertThat(looper.dispatchAll()).isEqualTo(1)

        // Send a connect request
        assertThat(service.connect(device)).isTrue()

        service.onConnectStateChangedFromNative(device, HidDeviceService.HAL_CONN_STATE_CONNECTING)
        assertThat(looper.dispatchAll()).isEqualTo(1)
        // Verify the connection state broadcast
        verifyConnectionStateIntent(STATE_CONNECTING, STATE_DISCONNECTED)
        assertThat(service.getConnectionState(device)).isEqualTo(STATE_CONNECTING)

        service.onConnectStateChangedFromNative(device, HidDeviceService.HAL_CONN_STATE_CONNECTED)
        assertThat(looper.dispatchAll()).isEqualTo(1)
        // Verify the connection state broadcast
        verifyConnectionStateIntent(STATE_CONNECTED, STATE_CONNECTING)
        assertThat(service.getConnectionState(device)).isEqualTo(STATE_CONNECTED)

        // Verify the list of connected devices
        assertThat(service.getDevicesMatchingConnectionStates(intArrayOf(STATE_CONNECTED)))
            .contains(device)

        // Send a disconnect request
        assertThat(service.disconnect(device)).isTrue()

        service.onConnectStateChangedFromNative(
            device,
            HidDeviceService.HAL_CONN_STATE_DISCONNECTING,
        )
        assertThat(looper.dispatchAll()).isEqualTo(1)
        // Verify the connection state broadcast
        verifyConnectionStateIntent(STATE_DISCONNECTING, STATE_CONNECTED)
        assertThat(service.getConnectionState(device)).isEqualTo(STATE_DISCONNECTING)

        service.onConnectStateChangedFromNative(
            device,
            HidDeviceService.HAL_CONN_STATE_DISCONNECTED,
        )
        assertThat(looper.dispatchAll()).isEqualTo(1)
        // Verify the connection state broadcast
        verifyConnectionStateIntent(STATE_DISCONNECTED, STATE_DISCONNECTING)
        assertThat(service.getConnectionState(device)).isEqualTo(STATE_DISCONNECTED)

        // Verify the list of connected devices
        assertThat(service.getDevicesMatchingConnectionStates(intArrayOf(STATE_CONNECTED)))
            .doesNotContain(device)

        // Unregister app
        doReturn(true).whenever(nativeInterface).unregisterApp()
        assertThat(service.unregisterApp()).isTrue()
    }

    /**
     * Test the logic in callback functions from native stack: onGetReport, onSetReport,
     * onSetProtocol, onInterruptData, onVirtualCableUnplug. The HID Device server should send the
     * callback to the user app.
     */
    @Test
    fun testCallbacks() {
        doReturn(true)
            .whenever(nativeInterface)
            .registerApp(any(), any(), any(), any(), any(), anyOrNull(), anyOrNull())

        verify(nativeInterface, never())
            .registerApp(any(), any(), any(), any(), any(), anyOrNull(), anyOrNull())

        // Register app
        assertThat(service.registerApp(settings, null, null, callback)).isTrue()
        verify(nativeInterface)
            .registerApp(any(), any(), any(), any(), any(), anyOrNull(), anyOrNull())

        // App registered
        service.onApplicationStateChangedFromNative(device, true)
        assertThat(looper.dispatchAll()).isEqualTo(1)
        verify(callback).onAppStatusChanged(device, true)

        // Received callback: onGetReport
        service.onGetReportFromNative(
            SAMPLE_REPORT_TYPE,
            SAMPLE_REPORT_ID,
            SAMPLE_BUFFER_SIZE.toShort(),
        )
        assertThat(looper.dispatchAll()).isEqualTo(1)
        verify(callback)
            .onGetReport(device, SAMPLE_REPORT_TYPE, SAMPLE_REPORT_ID, SAMPLE_BUFFER_SIZE.toInt())

        // Received callback: onSetReport
        service.onSetReportFromNative(SAMPLE_REPORT_TYPE, SAMPLE_REPORT_ID, SAMPLE_HID_REPORT)
        assertThat(looper.dispatchAll()).isEqualTo(1)
        verify(callback)
            .onSetReport(device, SAMPLE_REPORT_TYPE, SAMPLE_REPORT_ID, SAMPLE_HID_REPORT)

        // Received callback: onSetProtocol
        service.onSetProtocolFromNative(BluetoothHidDevice.PROTOCOL_BOOT_MODE)
        assertThat(looper.dispatchAll()).isEqualTo(1)
        verify(callback).onSetProtocol(device, BluetoothHidDevice.PROTOCOL_BOOT_MODE)

        // Received callback: onInterruptData
        service.onInterruptDataFromNative(SAMPLE_REPORT_ID, SAMPLE_HID_REPORT)
        assertThat(looper.dispatchAll()).isEqualTo(1)
        verify(callback).onInterruptData(device, SAMPLE_REPORT_ID, SAMPLE_HID_REPORT)

        // Received callback: onVirtualCableUnplug
        service.onVirtualCableUnplugFromNative()
        assertThat(looper.dispatchAll()).isEqualTo(1)
        verify(callback).onVirtualCableUnplug(device)

        // Unregister app
        doReturn(true).whenever(nativeInterface).unregisterApp()
        assertThat(service.unregisterApp()).isTrue()
        verify(nativeInterface).unregisterApp()

        service.onApplicationStateChangedFromNative(device, false)
        assertThat(looper.dispatchAll()).isEqualTo(1)
        verify(callback).onAppStatusChanged(device, false)
    }

    companion object {
        private val SAMPLE_HID_REPORT = byteArrayOf(0x01, 0x00, 0x02)
        private const val SAMPLE_REPORT_ID: Byte = 0x05
        private const val SAMPLE_REPORT_TYPE: Byte = 0x04
        private const val SAMPLE_REPORT_ERROR: Byte = 0x02
        private const val SAMPLE_BUFFER_SIZE: Byte = 100
    }
}
