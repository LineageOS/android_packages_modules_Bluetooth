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

package com.android.bluetooth.pan

import android.bluetooth.BluetoothPan.PAN_ROLE_NONE
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
import android.net.TetheringInterface
import android.net.TetheringManager
import android.os.UserManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.bluetooth.TestLooper
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.getTestDevice
import com.android.bluetooth.mockGetSystemService
import com.android.bluetooth.pan.PanNativeCallback.Companion.convertHalState
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mock
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.kotlin.whenever

/** Test cases for [PanService]. */
@MediumTest
@RunWith(AndroidJUnit4::class)
class PanServiceTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var adapterService: AdapterService
    @Mock private lateinit var panNativeCallback: PanNativeCallback
    @Mock private lateinit var nativeInterface: PanNativeInterface
    @Mock private lateinit var userManager: UserManager

    private val remoteDevice = getTestDevice(0)
    private val context = InstrumentationRegistry.getInstrumentation().context

    private lateinit var service: PanService
    private lateinit var testLooper: TestLooper

    @Before
    fun setUp() {
        doReturn(context.resources).whenever(adapterService).resources
        adapterService.mockGetSystemService<TetheringManager>()

        testLooper = TestLooper()
        service =
            PanService(
                adapterService,
                panNativeCallback,
                nativeInterface,
                userManager,
                testLooper.looper,
            )
        service.isAvailable = true
    }

    @After
    fun tearDown() {
        service.cleanup()
    }

    @Test
    fun connect_whenGuestUser_returnsFalse() {
        doReturn(true).whenever(userManager).isGuestUser
        assertThat(service.connect(remoteDevice)).isFalse()
    }

    @Test
    fun connect_inConnectedState_returnsFalse() {
        doReturn(false).whenever(userManager).isGuestUser
        service.mPanDevices[remoteDevice] =
            PanService.BluetoothPanDevice(STATE_CONNECTED, PAN_ROLE_NONE, PAN_ROLE_NONE)
        assertThat(service.connect(remoteDevice)).isFalse()
    }

    @Test
    fun connect() {
        doReturn(false).whenever(userManager).isGuestUser
        service.mPanDevices[remoteDevice] =
            PanService.BluetoothPanDevice(STATE_DISCONNECTED, PAN_ROLE_NONE, PAN_ROLE_NONE)
        assertThat(service.connect(remoteDevice)).isTrue()
        testLooper.dispatchAll()
        verify(nativeInterface, timeout(TIMEOUT_MS)).connect(any())
    }

    @Test
    fun disconnect_returnsTrue() {
        assertThat(service.disconnect(remoteDevice)).isTrue()
        testLooper.dispatchAll()
        verify(nativeInterface, timeout(TIMEOUT_MS)).disconnect(any())
    }

    @Test
    fun convertHalState() {
        assertThat(convertHalState(PanNativeCallback.CONN_STATE_CONNECTED))
            .isEqualTo(STATE_CONNECTED)
        assertThat(convertHalState(PanNativeCallback.CONN_STATE_CONNECTING))
            .isEqualTo(BluetoothProfile.STATE_CONNECTING)
        assertThat(convertHalState(PanNativeCallback.CONN_STATE_DISCONNECTED))
            .isEqualTo(STATE_DISCONNECTED)
        assertThat(convertHalState(PanNativeCallback.CONN_STATE_DISCONNECTING))
            .isEqualTo(BluetoothProfile.STATE_DISCONNECTING)
        assertThat(convertHalState(-24664)) // illegal value
            .isEqualTo(STATE_DISCONNECTED)
    }

    @Test
    fun dump() {
        service.mPanDevices[remoteDevice] =
            PanService.BluetoothPanDevice(STATE_DISCONNECTED, PAN_ROLE_NONE, PAN_ROLE_NONE)
        service.dump(StringBuilder())
    }

    @Test
    fun onConnectStateChanged_doesNotCrash() {
        service.onConnectStateChanged(REMOTE_DEVICE_ADDRESS_AS_ARRAY, 1, 2, 3, 4)
    }

    @Test
    fun onConnectStateChanged_doesNotCrashAfterCleanup() {
        service.cleanup()
        service.onConnectStateChanged(REMOTE_DEVICE_ADDRESS_AS_ARRAY, 1, 2, 3, 4)
    }

    @Test
    fun onControlStateChanged_doesNotCrash() {
        service.onControlStateChanged(1, 2, 3, "ifname")
    }

    @Test
    fun setConnectionPolicy() {
        assertThat(service.setConnectionPolicy(remoteDevice, CONNECTION_POLICY_ALLOWED)).isTrue()
        testLooper.dispatchAll()
        verify(nativeInterface, timeout(TIMEOUT_MS)).connect(any())
        assertThat(service.setConnectionPolicy(remoteDevice, CONNECTION_POLICY_FORBIDDEN)).isTrue()
        testLooper.dispatchAll()
        verify(nativeInterface, timeout(TIMEOUT_MS)).disconnect(any())
    }

    @Test
    fun connectState_constructor() {
        val state = 1
        val error = 2
        val localRole = 3
        val remoteRole = 4
        val connectState =
            PanService.ConnectState(
                REMOTE_DEVICE_ADDRESS_AS_ARRAY,
                state,
                error,
                localRole,
                remoteRole,
            )
        assertThat(connectState.addr).isEqualTo(REMOTE_DEVICE_ADDRESS_AS_ARRAY)
        assertThat(connectState.state).isEqualTo(state)
        assertThat(connectState.error).isEqualTo(error)
        assertThat(connectState.local_role).isEqualTo(localRole)
        assertThat(connectState.remote_role).isEqualTo(remoteRole)
    }

    @Test
    fun tetheringCallback_onError_clearsPanDevices() {
        service.mIsTethering = true
        service.mPanDevices[remoteDevice] =
            PanService.BluetoothPanDevice(STATE_DISCONNECTED, PAN_ROLE_NONE, PAN_ROLE_NONE)
        val iface = TetheringInterface(TetheringManager.TETHERING_BLUETOOTH, "iface")
        service.mTetheringCallback.onError(iface, TetheringManager.TETHER_ERROR_SERVICE_UNAVAIL)
        assertThat(service.mPanDevices).isEmpty()
        assertThat(service.mIsTethering).isFalse()
    }

    companion object {
        private val REMOTE_DEVICE_ADDRESS_AS_ARRAY = byteArrayOf(0, 0, 0, 0, 0, 0)
        private const val TIMEOUT_MS = 5000L
    }
}
