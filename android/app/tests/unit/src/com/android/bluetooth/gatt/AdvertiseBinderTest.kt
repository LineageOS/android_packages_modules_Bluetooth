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

import android.bluetooth.IBluetoothGattServerCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.IAdvertisingSetCallback
import android.bluetooth.le.PeriodicAdvertisingParameters
import android.content.AttributionSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.bluetooth.btservice.AdapterService
import com.android.tests.bluetooth.MockitoRule
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Test cases for [AdvertiseBinder]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class AdvertiseBinderTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var source: AttributionSource
    @Mock private lateinit var adapterService: AdapterService
    @Mock private lateinit var gattService: GattService
    @Mock private lateinit var advertiseManager: AdvertiseManager

    private lateinit var binder: AdvertiseBinder

    @Before
    fun setUp() {
        doReturn(true).whenever(gattService).isAvailable
        doAnswer { invocation ->
                (invocation.getArgument(0) as Runnable).run()
                null
            }
            .whenever(advertiseManager)
            .doOnAdvertiseThread(any())
        binder = AdvertiseBinder(adapterService, gattService, advertiseManager)
    }

    @Test
    fun startAdvertisingSet() {
        val parameters = AdvertisingSetParameters.Builder().build()
        val advertiseData = AdvertiseData.Builder().build()
        val scanResponse = AdvertiseData.Builder().build()
        val periodicParameters = PeriodicAdvertisingParameters.Builder().build()
        val periodicData = AdvertiseData.Builder().build()
        val duration = 1
        val maxExtAdvEvents = 2
        val serverCallback = mock<IBluetoothGattServerCallback>()
        val callback = mock<IAdvertisingSetCallback>()

        binder.startAdvertisingSet(
            parameters,
            advertiseData,
            scanResponse,
            periodicParameters,
            periodicData,
            duration,
            maxExtAdvEvents,
            serverCallback,
            callback,
            source,
        )
        verify(advertiseManager)
            .startAdvertisingSet(
                parameters,
                advertiseData,
                scanResponse,
                periodicParameters,
                periodicData,
                duration,
                maxExtAdvEvents,
                serverCallback,
                callback,
                source,
            )
    }

    @Test
    fun stopAdvertisingSet() {
        val callback = mock<IAdvertisingSetCallback>()

        binder.stopAdvertisingSet(callback, source)
        verify(advertiseManager).stopAdvertisingSet(callback)
    }

    @Test
    fun setAdvertisingData() {
        val advertiserId = 1
        val data = AdvertiseData.Builder().build()

        binder.setAdvertisingData(advertiserId, data, source)
        verify(advertiseManager).setAdvertisingData(advertiserId, data)
    }

    @Test
    fun setAdvertisingParameters() {
        val advertiserId = 1
        val parameters = AdvertisingSetParameters.Builder().build()

        binder.setAdvertisingParameters(advertiserId, parameters, source)
        verify(advertiseManager).setAdvertisingParameters(advertiserId, parameters)
    }

    @Test
    fun setPeriodicAdvertisingData() {
        val advertiserId = 1
        val data = AdvertiseData.Builder().build()

        binder.setPeriodicAdvertisingData(advertiserId, data, source)
        verify(advertiseManager).setPeriodicAdvertisingData(advertiserId, data)
    }

    @Test
    fun setPeriodicAdvertisingEnable() {
        val advertiserId = 1
        val enable = true

        binder.setPeriodicAdvertisingEnable(advertiserId, enable, source)
        verify(advertiseManager).setPeriodicAdvertisingEnable(advertiserId, enable)
    }

    @Test
    fun setPeriodicAdvertisingParameters() {
        val advertiserId = 1
        val parameters = PeriodicAdvertisingParameters.Builder().build()

        binder.setPeriodicAdvertisingParameters(advertiserId, parameters, source)
        verify(advertiseManager).setPeriodicAdvertisingParameters(advertiserId, parameters)
    }

    @Test
    fun setScanResponseData() {
        val advertiserId = 1
        val data = AdvertiseData.Builder().build()

        binder.setScanResponseData(advertiserId, data, source)
        verify(advertiseManager).setScanResponseData(advertiserId, data)
    }

    @Test
    fun getOwnAddress() {
        val advertiserId = 1

        binder.getOwnAddress(advertiserId, source)
        verify(advertiseManager).getOwnAddress(advertiserId)
    }

    @Test
    fun enableAdvertisingSet() {
        val advertiserId = 1
        val enable = true
        val duration = 3
        val maxExtAdvEvents = 4

        binder.enableAdvertisingSet(advertiserId, enable, duration, maxExtAdvEvents, source)
        verify(advertiseManager)
            .enableAdvertisingSet(advertiserId, enable, duration, maxExtAdvEvents, source)
    }

    @Test
    fun cleanup_doesNotCrash() {
        binder.cleanup()
    }
}
