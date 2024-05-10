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

package android.bluetooth

import android.bluetooth.DckTestRule.LeScanResult
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import androidx.test.core.app.ApplicationProvider
import com.android.compatibility.common.util.AdoptShellPermissionsRule
import com.google.common.truth.Truth.assertThat
import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** DCK LE Scan Tests */
@RunWith(TestParameterInjector::class)
class DckScanTest(
    private @TestParameter val isBluetoothToggled: Boolean,
    private @TestParameter val isRemoteAdvertisingWithUuid: Boolean,
    private @TestParameter val isGattConnected: Boolean
) {
    // TODO(315852141): Include variations for LE only vs. Dual mode Bumble when supported
    // TODO(315852141): Include variations for two advertisements at the same time
    // TODO(303502437): Include variations for other callback types when supported in rootcanal

    private val context: Context = ApplicationProvider.getApplicationContext()

    // Gives shell permissions during the test.
    @Rule(order = 0) @JvmField val shellPermissionRule = AdoptShellPermissionsRule()

    // Setup a Bumble Pandora device for the duration of the test.
    // Acting as a Pandora client, it can be interacted with through the Pandora APIs.
    @Rule(order = 1) @JvmField val bumble = PandoraDevice()

    // Test rule for common DCK test setup and teardown procedures, along with utility APIs.
    @get:Rule(order = 2)
    public val dck =
        DckTestRule(
            context,
            bumble,
            isBluetoothToggled = isBluetoothToggled,
            isRemoteAdvertisingWithUuid = isRemoteAdvertisingWithUuid,
            isGattConnected = isGattConnected
        )

    @Test
    fun scanForIrkAndIdentityAddress_remoteFound() {
        // TODO(316001793): Retrieve identity address from Bumble
        val scanFilter =
            ScanFilter.Builder()
                .setDeviceAddress(
                    TEST_ADDRESS_RANDOM_STATIC,
                    BluetoothDevice.ADDRESS_TYPE_RANDOM,
                    Utils.BUMBLE_IRK
                )
                .build()
        val scanSettings =
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setMatchMode(ScanSettings.MATCH_MODE_STICKY)
                .build()

        val result: LeScanResult = runBlocking {
            withTimeout(TIMEOUT_MS) { dck.scanWithPendingIntent(scanFilter, scanSettings).first() }
        }

        assertThat(result).isInstanceOf(LeScanResult.Success::class.java)
        assertThat((result as LeScanResult.Success).callbackType)
            .isEqualTo(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        assertThat((result as LeScanResult.Success).scanResult.device.address)
            .isEqualTo(TEST_ADDRESS_RANDOM_STATIC)
    }

    @Test
    fun scanForUuid_remoteFound() {
        // Assume isRemoteAdvertisingWithUuid is true to skip tests in which
        // device is not advertising with UUID
        assumeTrue(isRemoteAdvertisingWithUuid)
        val scanFilter = ScanFilter.Builder().setServiceUuid(ParcelUuid(CCC_DK_UUID)).build()
        val scanSettings =
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .build()

        val result: LeScanResult = runBlocking {
            withTimeout(TIMEOUT_MS) { dck.scanWithCallback(scanFilter, scanSettings).first() }
        }

        assertThat(result).isInstanceOf(LeScanResult.Success::class.java)
        assertThat((result as LeScanResult.Success).callbackType)
            .isEqualTo(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        assertThat((result as LeScanResult.Success).scanResult.device.address)
            .isEqualTo(Utils.BUMBLE_RANDOM_ADDRESS)
    }

    companion object {
        private const val TIMEOUT_MS = 3000L
        private const val TEST_ADDRESS_RANDOM_STATIC = "F0:43:A8:23:10:11"
        private val CCC_DK_UUID = UUID.fromString("0000FFF5-0000-1000-8000-00805f9b34fb")
    }
}
