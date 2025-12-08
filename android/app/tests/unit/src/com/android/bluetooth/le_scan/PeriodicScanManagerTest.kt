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

package com.android.bluetooth.le_scan

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.IPeriodicAdvertisingCallback
import android.bluetooth.le.ScanResult
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.bluetooth.TestUtils.getRealDevice
import com.android.bluetooth.TestUtils.mockGetBluetoothManager
import com.android.bluetooth.btservice.AdapterService
import com.android.tests.bluetooth.MockitoRule
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.doNothing
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Test cases for [PeriodicScanManager]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class PeriodicScanManagerTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var adapterService: AdapterService
    @Mock private lateinit var scanController: ScanController
    @Mock private lateinit var periodicScanNativeInterface: PeriodicScanNativeInterface
    @Mock private lateinit var callback: IPeriodicAdvertisingCallback
    @Mock private lateinit var binder: IBinder

    private val device: BluetoothDevice =
        getRealDevice(REMOTE_DEVICE_ADDRESS, BluetoothDevice.ADDRESS_TYPE_RANDOM)

    private lateinit var periodicScanManager: PeriodicScanManager
    private lateinit var scanResult: ScanResult
    private var syncHandle: Int = 0

    @Before
    fun setUp() {
        mockGetBluetoothManager(adapterService)

        periodicScanManager =
            PeriodicScanManager(adapterService, scanController, periodicScanNativeInterface)
        scanResult = ScanResult(device, 0, 0, 0, 0, 0, 0, 0, null, 0)

        doReturn(binder).whenever(callback).asBinder()
        doNothing().whenever(binder).linkToDeath(any(), eq(0))

        syncHandle = periodicScanManager.mTempRegistrationId
    }

    @After
    fun tearDown() {
        periodicScanManager.cleanup()
    }

    @Test
    fun startSync_invokesNative() {
        periodicScanManager.startSync(scanResult, 0, 0, callback)

        verify(periodicScanNativeInterface)
            .startSync(eq(0), eq(REMOTE_DEVICE_ADDRESS), eq(0), eq(0), any())
    }

    @Test
    fun startSync_onSyncStarted() {
        periodicScanManager.startSync(scanResult, 0, 0, callback)

        val regIdCaptor = argumentCaptor<Int>()
        verify(periodicScanNativeInterface)
            .startSync(eq(0), eq(REMOTE_DEVICE_ADDRESS), eq(0), eq(0), regIdCaptor.capture())

        periodicScanManager.onSyncStarted(
            regIdCaptor.firstValue,
            syncHandle,
            0,
            BluetoothDevice.ADDRESS_TYPE_RANDOM,
            REMOTE_DEVICE_ADDRESS,
            0,
            100,
            0,
        )

        verify(callback).onSyncEstablished(eq(syncHandle), eq(device), eq(0), eq(0), eq(0), eq(0))
    }

    companion object {
        private const val REMOTE_DEVICE_ADDRESS = "00:01:02:03:04:05"
    }
}
