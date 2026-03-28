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
import android.bluetooth.BluetoothDevice.BOND_BONDED
import android.bluetooth.BluetoothDevice.BOND_NONE
import android.bluetooth.BluetoothDevice.METADATA_MODEL_NAME
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN
import android.bluetooth.BluetoothSinkAudioPolicy
import android.content.Context
import android.content.pm.PackageManager
import android.platform.test.flag.junit.SetFlagsRule
import androidx.test.core.app.ApplicationProvider
import com.android.bluetooth.TestUtils.getTestDevice
import com.android.bluetooth.TestUtils.mockGetRemoteDevice
import com.android.bluetooth.btservice.AdapterService
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
    private val key = METADATA_MODEL_NAME
    private val value = "This is the value".toByteArray()

    private lateinit var testDispatcher: TestDispatcher
    private lateinit var storageManager: BluetoothStorageManager

    @Before
    fun setUp() {
        doReturn(emptySet<BluetoothDevice>()).whenever(adapterService).bondedDevices
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

    fun emulateBluetoothRestart() {
        // cleanup cancels the scope allowing DataStore to release the file lock
        storageManager.cleanup()

        // Create a new storage manager to simulate a restart
        storageManager = BluetoothStorageManager(adapterService, testDispatcher)
        storageManager.initialize()
    }

    @Test
    fun setGetProfileConnectionPolicy() =
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
    fun setGetCustomMetadata() =
        runTest(testDispatcher) {
            assertThat(storageManager.getCustomMetadata(device1, key)).isNull()
            assertThat(storageManager.setCustomMetadata(device1, key, value)).isTrue()
            assertThat(storageManager.getCustomMetadata(device1, key)).isEqualTo(value)
        }

    @Test
    fun setCustomMetadata_twiceWithSameValue_returnsFalse() =
        runTest(testDispatcher) {
            assertThat(storageManager.getCustomMetadata(device1, key)).isNull()
            assertThat(storageManager.setCustomMetadata(device1, key, value)).isTrue()
            assertThat(storageManager.setCustomMetadata(device1, key, value)).isFalse()
        }

    @Test
    fun setCustomMetadata_withEmptyValue_removesMetadata() =
        runTest(testDispatcher) {
            setGetCustomMetadata()

            // Set an empty byte array, which should remove the metadata
            storageManager.setCustomMetadata(device1, key, byteArrayOf())

            // Verify the metadata is removed (get returns null)
            assertThat(storageManager.getCustomMetadata(device1, key)).isNull()
        }

    @Test
    fun deviceConnectionHistory() =
        runTest(testDispatcher) {
            doReturn(setOf(device1, device2)).whenever(adapterService).bondedDevices
            assertThat(storageManager.getMostRecentlyConnectedDevices()).isEmpty()

            storageManager.onDeviceConnected(device1, BluetoothProfile.A2DP)
            assertThat(storageManager.getMostRecentlyConnectedDevices()).containsExactly(device1)

            storageManager.onDeviceConnected(device2, BluetoothProfile.HEADSET)
            assertThat(storageManager.getMostRecentlyConnectedDevices())
                .containsExactly(device2, device1)
                .inOrder()
        }

    @Test
    fun onBondNone_removeDeviceFromConnectionHistory() =
        runTest(testDispatcher) {
            deviceConnectionHistory()

            doReturn(setOf(device2)).whenever(adapterService).bondedDevices
            storageManager.onBondStateChanged(device1, BOND_BONDED, BOND_NONE)

            assertThat(storageManager.getMostRecentlyConnectedDevices()).containsExactly(device2)
        }

    @Test
    fun activeA2dp() =
        runTest(testDispatcher) {
            doReturn(setOf(device1, device2)).whenever(adapterService).bondedDevices
            assertThat(storageManager.getMostRecentlyActiveA2dpDevice()).isNull()

            storageManager.onDeviceConnected(device1, BluetoothProfile.A2DP)
            assertThat(storageManager.getMostRecentlyActiveA2dpDevice()).isEqualTo(device1)

            storageManager.onDeviceConnected(device2, BluetoothProfile.A2DP)
            assertThat(storageManager.getMostRecentlyActiveA2dpDevice()).isEqualTo(device2)

            storageManager.onDeviceDisconnected(device2, BluetoothProfile.A2DP)
            assertThat(storageManager.getMostRecentlyActiveA2dpDevice()).isEqualTo(device1)
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
            // Scenario: The device is present in storage, but has no HFP settings.
            // We add it to storage by setting some other metadata.
            storageManager.setCustomMetadata(device1, key, value)

            assertThat(storageManager.getAudioPolicyMetadata(device1))
                .isEqualTo(BluetoothSinkAudioPolicy.Builder().build())
        }

    @Test
    fun setGetAudioPolicyMetadata_returnsCorrectPolicy() =
        runTest(testDispatcher) {
            val testPolicy =
                BluetoothSinkAudioPolicy.Builder()
                    .setCallEstablishPolicy(BluetoothSinkAudioPolicy.POLICY_ALLOWED)
                    .setActiveDevicePolicyAfterConnection(BluetoothSinkAudioPolicy.POLICY_ALLOWED)
                    .setInBandRingtonePolicy(BluetoothSinkAudioPolicy.POLICY_ALLOWED)
                    .build()

            storageManager.setAudioPolicyMetadata(device1, testPolicy)
            assertThat(storageManager.getAudioPolicyMetadata(device1)).isEqualTo(testPolicy)
        }

    @Test
    fun memoryOnlyCache_evictsEldestUnbonded() =
        runTest(testDispatcher) {
            // Add 21 unbonded devices (MAX_UNBONDED_CACHE_SIZE is 20)
            for (i in 1..21) {
                val testDevice = getTestDevice(i)
                storageManager.setCustomMetadata(testDevice, key, "test$i".toByteArray())
            }

            // The first device (getTestDevice(1)) should have been evicted from the cache
            val firstDevice = getTestDevice(1)
            assertThat(storageManager.getCustomMetadata(firstDevice, key)).isNull()

            // The last device (getTestDevice(21)) should still be in the cache
            val lastDevice = getTestDevice(21)
            assertThat(storageManager.getCustomMetadata(lastDevice, key))
                .isEqualTo("test21".toByteArray())
        }

    @Test
    fun unbondedDevices_areNotPersisted() =
        runTest(testDispatcher) {
            // Add an unbonded device
            storageManager.setCustomMetadata(device1, key, value)

            // Ensure it is accessible from the current memory cache
            assertThat(storageManager.getCustomMetadata(device1, key)).isEqualTo(value)

            emulateBluetoothRestart()

            // The unbonded device should not be loaded from disk
            assertThat(storageManager.getCustomMetadata(device1, key)).isNull()
        }

    @Test
    fun unbondedDevicesWithMetadata_getBonded_dataIsMigratedFromMemoryCache() =
        runTest(testDispatcher) {
            // Add unbonded devices
            storageManager.setCustomMetadata(device1, key, value)

            // Bond it after the ma device
            doReturn(setOf(device1)).whenever(adapterService).bondedDevices
            storageManager.onBondStateChanged(device1, BOND_NONE, BOND_BONDED)

            emulateBluetoothRestart()

            // The now bonded device should be loaded from disk
            assertThat(storageManager.getCustomMetadata(device1, key)).isEqualTo(value)
        }

    companion object {
        @JvmStatic @Parameters(name = "{0}") fun getParams() = FlagsWrapper.progressionOf()
    }
}
