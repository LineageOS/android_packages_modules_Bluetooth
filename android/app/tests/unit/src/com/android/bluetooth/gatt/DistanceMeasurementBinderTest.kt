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

package com.android.bluetooth.gatt

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.DistanceMeasurementMethod
import android.bluetooth.le.DistanceMeasurementParams
import android.bluetooth.le.IDistanceMeasurementCallback
import android.os.ParcelUuid
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.bluetooth.TestUtils.MockitoRule
import com.android.bluetooth.TestUtils.getTestDevice
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.gatt.DistanceMeasurementManager.GetResultTask
import java.util.UUID
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.kotlin.whenever

/** Test cases for [DistanceMeasurementBinder] */
@SmallTest
@RunWith(AndroidJUnit4::class)
class DistanceMeasurementBinderTest {

    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var distanceMeasurementManager: DistanceMeasurementManager
    @Mock private lateinit var adapterService: AdapterService

    private val attributionSource =
        InstrumentationRegistry.getInstrumentation()
            .targetContext
            .getSystemService(BluetoothManager::class.java)
            .adapter
            .attributionSource

    private lateinit var binder: DistanceMeasurementBinder

    @Before
    fun setUp() {
        binder = DistanceMeasurementBinder(adapterService, distanceMeasurementManager)
        doReturn(emptyList<DistanceMeasurementMethod>())
            .whenever(distanceMeasurementManager)
            .getSupportedDistanceMeasurementMethods()
        doAnswer { invocationOnMock ->
                val task = invocationOnMock.getArgument<GetResultTask<*>>(0)
                task.result
            }
            .whenever(distanceMeasurementManager)
            .runOnDistanceMeasurementThreadAndWaitForResult(any<GetResultTask<*>>())
        doAnswer { invocation ->
                (invocation.getArgument(0) as Runnable).run()
                null
            }
            .whenever(distanceMeasurementManager)
            .postOnDistanceMeasurementThread(any())
    }

    @Test
    fun getSupportedDistanceMeasurementMethods() {
        binder.getSupportedDistanceMeasurementMethods(attributionSource)
        verify(distanceMeasurementManager).supportedDistanceMeasurementMethods
    }

    @Test
    fun startDistanceMeasurement() {
        val uuid = UUID.randomUUID()
        val device: BluetoothDevice = getTestDevice(3)
        val params =
            DistanceMeasurementParams.Builder(device)
                .setDurationSeconds(123)
                .setFrequency(DistanceMeasurementParams.REPORT_FREQUENCY_LOW)
                .build()
        val callback = mock(IDistanceMeasurementCallback::class.java)
        binder.startDistanceMeasurement(ParcelUuid(uuid), params, callback, attributionSource)
        verify(distanceMeasurementManager).startDistanceMeasurement(uuid, params, callback)
    }

    @Test
    fun stopDistanceMeasurement() {
        val uuid = UUID.randomUUID()
        val device: BluetoothDevice = getTestDevice(3)
        val method = DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI
        binder.stopDistanceMeasurement(ParcelUuid(uuid), device, method, attributionSource)
        verify(distanceMeasurementManager).stopDistanceMeasurement(uuid, device, method, false)
    }
}
