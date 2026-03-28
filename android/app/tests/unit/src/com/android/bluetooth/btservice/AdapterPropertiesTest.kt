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

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothDevice.DEVICE_TYPE_LE
import android.bluetooth.BluetoothManager
import android.companion.CompanionDeviceManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.HandlerThread
import android.platform.test.flag.junit.SetFlagsRule
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.bluetooth.Utils
import com.android.bluetooth.mockBluetoothManager
import com.android.bluetooth.mockGetSystemService
import com.android.bluetooth.mockPackageManager
import com.android.tests.bluetooth.FlagsWrapper
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doCallRealMethod
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

/** Test cases for [AdapterProperties]. */
@MediumTest
@RunWith(ParameterizedAndroidJunit4::class)
class AdapterPropertiesTest(flags: FlagsWrapper) {
    @get:Rule val mockitoRule = MockitoRule()
    @get:Rule val setFlagsRule = SetFlagsRule(flags.flags)

    @Mock private lateinit var adapterService: AdapterService
    @Mock private lateinit var packageManager: PackageManager
    @Mock private lateinit var nativeInterface: AdapterNativeInterface

    private lateinit var adapterProperties: AdapterProperties
    private lateinit var remoteDevices: RemoteDevices
    private lateinit var handlerThread: HandlerThread

    @Before
    fun setUp() {
        doReturn(nativeInterface).whenever(adapterService).native
        handlerThread = HandlerThread("RemoteDevicesTestHandlerThread")
        handlerThread.start()

        adapterService.mockBluetoothManager()
        adapterService.mockGetSystemService<CompanionDeviceManager>()
        adapterService.mockPackageManager(packageManager)
        doCallRealMethod().whenever(adapterService).getBrEdrAddress(any<BluetoothDevice>())
        doCallRealMethod().whenever(adapterService).getBrEdrAddress(any<String>())
        doReturn(Utils.getAddressStringFromByte(TEST_BT_ADDR_BYTES))
            .whenever(adapterService)
            .getIdentityAddress(Utils.getAddressStringFromByte(TEST_BT_ADDR_BYTES))
        doReturn(Utils.getAddressStringFromByte(TEST_BT_ADDR_BYTES))
            .whenever(adapterService)
            .getIdentityAddress(Utils.getAddressStringFromByte(TEST_BT_ADDR_BYTES_2))
        doReturn(true).whenever(nativeInterface).removeBond(any<ByteArray>())

        remoteDevices = RemoteDevices(adapterService, handlerThread.looper)
        verify(adapterService).getSystemService(BluetoothManager::class.java)

        remoteDevices.reset()

        doReturn(handlerThread.looper).whenever(adapterService).mainLooper
        doReturn(InstrumentationRegistry.getInstrumentation().context.resources)
            .whenever(adapterService)
            .resources

        // Must be called to initialize services
        adapterProperties = AdapterProperties(adapterService, remoteDevices, handlerThread.looper)
        adapterProperties.init()
    }

    @Test
    fun testCleanupPrevBondRecordsFor() {
        remoteDevices.reset()
        remoteDevices.addDeviceProperties(TEST_BT_ADDR_BYTES).deviceType = DEVICE_TYPE_LE
        remoteDevices.addDeviceProperties(TEST_BT_ADDR_BYTES_2).deviceType = DEVICE_TYPE_LE
        val device1 = remoteDevices.getDevice(TEST_BT_ADDR_BYTES)
        val device2 = remoteDevices.getDevice(TEST_BT_ADDR_BYTES_2)

        // Bond record for device1 should be deleted when pairing with device2
        // as they are same device (have same identity address)
        adapterProperties.onBondStateChanged(device1, BluetoothDevice.BOND_BONDED)
        adapterProperties.onBondStateChanged(device2, BluetoothDevice.BOND_BONDED)
        assertThat(adapterProperties.getBondedDevices()).containsExactly(device2)
    }

    @Test
    fun isNativeDiscovering_initialValueIsFalse() {
        // Verifies that the default discovery state is false.
        assertThat(adapterProperties.isNativeDiscovering).isFalse()
    }

    @Test
    fun discoveryStateChangeCallback_Started_setsNativeDiscoveringTrue() {
        // Verifies that starting discovery updates the state and broadcasts the correct intent.
        assertThat(adapterProperties.isNativeDiscovering).isFalse()

        // Trigger discovery started callback.
        adapterProperties.discoveryStateChangeCallback(AbstractionLayer.BT_DISCOVERY_STARTED)

        // Assert that native discovering is now true.
        assertThat(adapterProperties.isNativeDiscovering).isTrue()

        // Verify that an ACTION_DISCOVERY_STARTED intent was broadcast.
        val intentCaptor = argumentCaptor<Intent>()
        verify(adapterService)
            .sendBroadcast(
                intentCaptor.capture(),
                eq(android.Manifest.permission.BLUETOOTH_SCAN),
                any<Bundle>(),
            )
        assertThat(intentCaptor.firstValue.action)
            .isEqualTo(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
    }

    @Test
    fun discoveryStateChangeCallback_Stopped_setsNativeDiscoveringFalse() {
        // Verifies that stopping discovery updates the state and broadcasts the correct intent.
        // Start discovery first to ensure the state changes.
        adapterProperties.discoveryStateChangeCallback(AbstractionLayer.BT_DISCOVERY_STARTED)
        assertThat(adapterProperties.isNativeDiscovering).isTrue()
        // Clear invocations on the mock from the setup call to isolate verification.
        clearInvocations(adapterService)

        // Trigger discovery stopped callback.
        adapterProperties.discoveryStateChangeCallback(AbstractionLayer.BT_DISCOVERY_STOPPED)

        // Assert that native discovering is now false.
        assertThat(adapterProperties.isNativeDiscovering).isFalse()

        // Verify that clearDiscoveryData is called.
        verify(adapterService).clearDiscoveryData()

        // Verify that an ACTION_DISCOVERY_FINISHED intent was broadcast.
        val intentCaptor = argumentCaptor<Intent>()
        verify(adapterService)
            .sendBroadcast(
                intentCaptor.capture(),
                eq(android.Manifest.permission.BLUETOOTH_SCAN),
                any<Bundle>(),
            )
        assertThat(intentCaptor.firstValue.action)
            .isEqualTo(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
    }

    companion object {
        private val TEST_BT_ADDR_BYTES = byteArrayOf(0, 11, 22, 33, 44, 55)
        private val TEST_BT_ADDR_BYTES_2 = byteArrayOf(0, 11, 22, 33, 44, 66)

        @JvmStatic @Parameters(name = "{0}") fun getParams() = FlagsWrapper.progressionOf()
    }
}
