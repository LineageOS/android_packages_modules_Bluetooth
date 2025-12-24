/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.bluetooth.pairing

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.Host
import android.bluetooth.PandoraDevice
import android.bluetooth.Utils
import android.bluetooth.pairing.utils.TestUtil
import android.bluetooth.test_utils.EnableBluetoothRule
import android.bluetooth.toAddressBytes
import android.content.Context
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.compatibility.common.util.AdoptShellPermissionsRule
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.junit.MockitoJUnit
import pandora.HostProto.AdvertiseRequest
import pandora.HostProto.DataTypes
import pandora.HostProto.DiscoverabilityMode
import pandora.HostProto.OwnAddressType
import pandora.HostProto.SetDiscoverabilityModeRequest

/** Test cases for [AddressMappingTest]. */
@RunWith(AndroidJUnit4::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AddressMappingTest {
    @get:Rule val mockitoRule = MockitoJUnit.rule()
    @get:Rule(order = 0) val checkFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()
    @get:Rule(order = 1) val permissionRule = AdoptShellPermissionsRule()
    @get:Rule(order = 2) val bumble = PandoraDevice()
    @get:Rule(order = 3) val enableBluetoothRule = EnableBluetoothRule(false, true)

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager: BluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter = manager.adapter
    private lateinit var host: Host

    private lateinit var util: TestUtil

    @Before
    fun setUp() {
        util = TestUtil.Builder(context).setBluetoothAdapter(adapter).build()
        host = Host(context)
        for (bondedDevice in adapter.bondedDevices) {
            util.removeBond(null, bondedDevice)
        }
    }

    @After
    fun tearDown() {
        val bondedDevices: Set<BluetoothDevice> = adapter.bondedDevices
        for (bondedDevice in adapter.bondedDevices) {
            util.removeBond(null, bondedDevice)
        }
        host.close()
    }

    /**
     * Test pairing when RPA rotates on remote device
     *
     * <p>Prerequisites:
     * <ol>
     * <li>Bumble and Android are not bonded
     * <li>Bumble uses RPA for LE advertisements
     * </ol>
     *
     * <p>Steps:
     * <ol>
     * <li>Bumble is discoverable and connectable over LE
     * <li>Android connects and bonds to Bumble over LE
     * <li>Android disconnects from the Bumble device
     * <li>Android removes the Bumble device
     * <li>Restart bumble with address rotation
     * <li>Android connects and bonds to Bumble over LE
     * </ol>
     *
     * <p>Expectation: Pairing is successful after address rotation
     */
    @Test
    @Throws(Exception::class)
    fun testLePairing_whenRpaRotates() {
        val device = testStep_discoverAndPair()
        val firstDevAddr = device.address
        val firstDevIdAddr = device.identityAddress
        device.disconnect()
        // Forget the device
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            host.removeBondAndVerify(device)
        }

        val secondDevice = testStep_discoverAndPair()
        // Verify RPA rotated and Identity address same
        assertThat(firstDevAddr).isNotEqualTo(secondDevice.address)
        assertThat(firstDevIdAddr).isEqualTo(secondDevice.identityAddress)
    }

    @Throws(Exception::class)
    private fun testStep_discoverAndPair(): BluetoothDevice {
        // Make Bumble non-discoverable over BR/EDR
        bumble
            .hostBlocking()
            .setDiscoverabilityMode(
                SetDiscoverabilityModeRequest.newBuilder()
                    .setMode(DiscoverabilityMode.NOT_DISCOVERABLE)
                    .build()
            )

        // Make Bumble connectable using RPA
        val rpa = TestUtil.generateRpa(Utils.BUMBLE_IRK)
        val dataTypeBuilder = DataTypes.newBuilder()
        dataTypeBuilder.completeLocalName = BUMBLE_DEVICE_NAME
        dataTypeBuilder.leDiscoverabilityModeValue = DiscoverabilityMode.DISCOVERABLE_GENERAL_VALUE
        bumble
            .hostBlocking()
            .advertise(
                AdvertiseRequest.newBuilder()
                    .setLegacy(false)
                    .setConnectable(true)
                    .setOwnAddressType(OwnAddressType.RANDOM)
                    .setData(dataTypeBuilder.build())
                    .setRandomAddress(ByteString.copyFrom(rpa.toAddressBytes()))
                    .build()
            )
        val discoveredDevice = host.discoverAndVerify(BUMBLE_DEVICE_NAME)

        // Start pairing
        host.createBondAndVerify(discoveredDevice)
        Log.i(
            TAG,
            "Device > addr:" +
                discoveredDevice.address +
                ", identity:" +
                discoveredDevice.identityAddress +
                " , Address type :" +
                discoveredDevice.addressType,
        )
        return discoveredDevice
    }

    companion object {
        private val TAG = AddressMappingTest::class.java.simpleName
        private const val BUMBLE_DEVICE_NAME = "Bumble"
    }
}
