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

package com.android.bluetooth.storage

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN
import android.bluetooth.BluetoothSinkAudioPolicy
import android.content.Context
import android.content.pm.PackageManager
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import androidx.test.core.app.ApplicationProvider
import com.android.bluetooth.TestUtils.getTestDevice
import com.android.bluetooth.TestUtils.mockGetRemoteDevice
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.flags.Flags
import com.android.tests.bluetooth.FlagsWrapper
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyString
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.whenever
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@RunWith(ParameterizedAndroidJunit4::class)
@ExperimentalCoroutinesApi
class BluetoothStorageManagerTest(flags: FlagsWrapper) {
    @get:Rule val mockitoRule = MockitoRule()
    @get:Rule val setFlagsRule = SetFlagsRule(flags.flags)
    @get:Rule val tempFolder = TemporaryFolder()

    @Mock private lateinit var adapterService: AdapterService
    @Mock private lateinit var packageManager: PackageManager

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val device1: BluetoothDevice = getTestDevice(0)
    private val device2: BluetoothDevice = getTestDevice(1)

    private lateinit var testDispatcher: TestDispatcher
    private lateinit var storageManager: BluetoothStorageManager

    @Before
    fun setUp() {
        doReturn(adapterService).whenever(adapterService).createDeviceProtectedStorageContext()
        doReturn(context.applicationInfo).whenever(adapterService).applicationInfo
        doReturn("com.android.bluetooth").whenever(adapterService).packageName
        doReturn(tempFolder.root).whenever(adapterService).filesDir
        whenever(adapterService.getDatabasePath(anyString())).thenAnswer {
            File(tempFolder.root, it.getArgument(0) as String)
        }
        doReturn(packageManager).whenever(adapterService).packageManager
        doReturn("test.app").whenever(packageManager).getNameForUid(anyInt())
        mockGetRemoteDevice(adapterService, device1, device2)

        // Use UnconfinedTestDispatcher to execute coroutines eagerly
        testDispatcher = UnconfinedTestDispatcher()
        storageManager = BluetoothStorageManager(adapterService, testDispatcher)

        storageManager.initialize()
    }

    @Test
    fun testSetAndGetProfileConnectionPolicy() =
        runTest(testDispatcher) {
            val profile = BluetoothProfile.A2DP
            val policy = CONNECTION_POLICY_ALLOWED

            assertThat(storageManager.getProfileConnectionPolicy(device1, profile))
                .isEqualTo(CONNECTION_POLICY_UNKNOWN)

            storageManager.setProfileConnectionPolicy(device1, profile, policy)

            assertThat(storageManager.getProfileConnectionPolicy(device1, profile))
                .isEqualTo(policy)
        }

    @Test
    fun testSetAndGetCustomMetadata() =
        runTest(testDispatcher) {
            doReturn(arrayOf(device1)).whenever(adapterService).bondedDevices
            val key = BluetoothDevice.METADATA_MANUFACTURER_NAME
            val value = "Test Manufacturer".toByteArray()

            assertThat(storageManager.getCustomMetadata(device1, key)).isNull()

            assertThat(storageManager.setCustomMetadata(device1, key, value)).isTrue()

            assertThat(storageManager.getCustomMetadata(device1, key)).isEqualTo(value)
        }

    @Test
    @EnableFlags(Flags.FLAG_STORAGE_PREVENT_CUSTOM_METADATA_ON_UNBONDED_DEVICE)
    fun setCustomMetadata_twice_refustToChangeDatabase() =
        runTest(testDispatcher) {
            doReturn(arrayOf(device1)).whenever(adapterService).bondedDevices
            val key = BluetoothDevice.METADATA_MANUFACTURER_NAME
            val value = "Test Manufacturer".toByteArray()

            assertThat(storageManager.getCustomMetadata(device1, key)).isNull()
            assertThat(storageManager.setCustomMetadata(device1, key, value)).isTrue()

            assertThat(storageManager.setCustomMetadata(device1, key, value)).isFalse()
        }

    @Test
    @EnableFlags(Flags.FLAG_STORAGE_PREVENT_CUSTOM_METADATA_ON_UNBONDED_DEVICE)
    fun setCustomMetadata_onUnknownDevice_refuseToChangeDatabase() =
        runTest(testDispatcher) {
            doReturn(arrayOf<BluetoothDevice>()).whenever(adapterService).bondedDevices
            val key = BluetoothDevice.METADATA_MANUFACTURER_NAME
            val value = "Test Manufacturer".toByteArray()

            assertThat(storageManager.getCustomMetadata(device1, key)).isNull()

            assertThat(storageManager.setCustomMetadata(device1, key, value)).isFalse()

            assertThat(storageManager.getCustomMetadata(device1, key)).isNull()
        }

    @Test
    fun setCustomMetadata_withEmptyValue_removesMetadata() =
        runTest(testDispatcher) {
            val key = BluetoothDevice.METADATA_MANUFACTURER_NAME
            testSetAndGetCustomMetadata()

            // Set an empty byte array, which should remove the metadata
            storageManager.setCustomMetadata(device1, key, byteArrayOf())

            // Verify the metadata is removed (get returns null)
            assertThat(storageManager.getCustomMetadata(device1, key)).isNull()
        }

    @Test
    fun testDeviceConnectionHistory() =
        runTest(testDispatcher) {
            assertThat(storageManager.getMostRecentlyConnectedDevices()).isEmpty()

            storageManager.onDeviceConnected(device1, BluetoothProfile.A2DP)

            var connectedDevices = storageManager.getMostRecentlyConnectedDevices()
            assertThat(connectedDevices).hasSize(1)
            assertThat(connectedDevices[0]).isEqualTo(device1)

            storageManager.onDeviceConnected(device2, BluetoothProfile.HEADSET)

            connectedDevices = storageManager.getMostRecentlyConnectedDevices()
            assertThat(connectedDevices).hasSize(2)
            assertThat(connectedDevices[0]).isEqualTo(device2)
            assertThat(connectedDevices[1]).isEqualTo(device1)
        }

    @Test
    fun testRemoveDevice() =
        runTest(testDispatcher) {
            storageManager.onDeviceConnected(device1, BluetoothProfile.A2DP)
            storageManager.onDeviceConnected(device2, BluetoothProfile.A2DP)

            assertThat(storageManager.getMostRecentlyConnectedDevices()).hasSize(2)

            storageManager.removeDevice(device1)

            val connectedDevices = storageManager.getMostRecentlyConnectedDevices()
            assertThat(connectedDevices).hasSize(1)
            assertThat(connectedDevices[0]).isEqualTo(device2)
        }

    @Test
    fun testActiveA2dpDevice() =
        runTest(testDispatcher) {
            assertThat(storageManager.getMostRecentlyActiveA2dpDevice()).isNull()

            storageManager.onDeviceConnected(device1, BluetoothProfile.A2DP)
            assertThat(storageManager.getMostRecentlyActiveA2dpDevice()).isEqualTo(device1)

            storageManager.onDeviceConnected(device2, BluetoothProfile.A2DP)
            assertThat(storageManager.getMostRecentlyActiveA2dpDevice()).isEqualTo(device2)

            storageManager.onDeviceDisconnected(device2, BluetoothProfile.A2DP)
            assertThat(storageManager.getMostRecentlyActiveA2dpDevice()).isEqualTo(device1)
        }

    @Test
    fun testCleanup_removesUnbondedDevices() =
        runTest(testDispatcher) {
            // device1 is bonded, device2 is not
            doReturn(arrayOf(device1)).whenever(adapterService).bondedDevices

            storageManager.setProfileConnectionPolicy(
                device1,
                BluetoothProfile.A2DP,
                CONNECTION_POLICY_ALLOWED,
            )
            storageManager.setProfileConnectionPolicy(
                device2,
                BluetoothProfile.A2DP,
                CONNECTION_POLICY_ALLOWED,
            )

            // Both devices should be in storage before cleanup
            assertThat(storageManager.getProfileConnectionPolicy(device1, BluetoothProfile.A2DP))
                .isEqualTo(CONNECTION_POLICY_ALLOWED)
            assertThat(storageManager.getProfileConnectionPolicy(device2, BluetoothProfile.A2DP))
                .isEqualTo(CONNECTION_POLICY_ALLOWED)

            // cleanup is synchronous and will wait for the underlying job to finish
            storageManager.cleanup()

            // Re-create storage manager to simulate restart and read from disk
            val newStorageManager = BluetoothStorageManager(adapterService, testDispatcher)
            newStorageManager.initialize()

            // device1 should still be there
            assertThat(newStorageManager.getProfileConnectionPolicy(device1, BluetoothProfile.A2DP))
                .isEqualTo(CONNECTION_POLICY_ALLOWED)
            // device2 should be gone
            assertThat(newStorageManager.getProfileConnectionPolicy(device2, BluetoothProfile.A2DP))
                .isEqualTo(CONNECTION_POLICY_UNKNOWN)
        }

    @Test
    fun getAudioPolicyMetadata_deviceNotInStorage_returnsDefault() =
        runTest(testDispatcher) {
            assertThat(storageManager.getAudioPolicyMetadata(device1))
                .isEqualTo(BluetoothSinkAudioPolicy.Builder().build())
        }

    @Test
    fun getAudioPolicyMetadata_deviceInStorageWithoutHfpSettings_returnsDefault() =
        runTest(testDispatcher) {
            doReturn(arrayOf(device1)).whenever(adapterService).bondedDevices
            // Scenario: The device is present in storage, but has no HFP settings.
            // We add it to storage by setting some other metadata.
            storageManager.setCustomMetadata(
                device1,
                BluetoothDevice.METADATA_MANUFACTURER_NAME,
                "test".toByteArray(),
            )

            assertThat(storageManager.getAudioPolicyMetadata(device1))
                .isEqualTo(BluetoothSinkAudioPolicy.Builder().build())
        }

    @Test
    fun setAndGetAudioPolicyMetadata_returnsCorrectPolicy() =
        runTest(testDispatcher) {
            val testPolicy =
                BluetoothSinkAudioPolicy.Builder()
                    .setCallEstablishPolicy(BluetoothSinkAudioPolicy.POLICY_ALLOWED)
                    .setActiveDevicePolicyAfterConnection(BluetoothSinkAudioPolicy.POLICY_ALLOWED)
                    .setInBandRingtonePolicy(BluetoothSinkAudioPolicy.POLICY_ALLOWED)
                    .build()

            storageManager.setAudioPolicyMetadata(device1, testPolicy)
            val retrievedPolicy = storageManager.getAudioPolicyMetadata(device1)

            assertThat(retrievedPolicy).isEqualTo(testPolicy)
        }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams() =
            FlagsWrapper.progressionOf(
                Flags.FLAG_STORAGE_PREVENT_CUSTOM_METADATA_ON_UNBONDED_DEVICE
            )
    }
}
