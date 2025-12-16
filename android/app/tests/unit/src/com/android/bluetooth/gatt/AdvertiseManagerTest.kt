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

import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.IAdvertisingSetCallback
import android.bluetooth.le.PeriodicAdvertisingParameters
import android.content.AttributionSource
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.bluetooth.TestLooper
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.mockPackageManager
import com.android.tests.bluetooth.MockitoRule
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.eq
import org.mockito.Mockito.verify
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.whenever

/** Test cases for [AdvertiseManager]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class AdvertiseManagerTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var source: AttributionSource
    @Mock private lateinit var adapterService: AdapterService
    @Mock private lateinit var gattService: GattService
    @Mock private lateinit var advertiserMap: AdvertiserMap
    @Mock private lateinit var nativeInterface: AdvertiseManagerNativeInterface
    @Mock private lateinit var callback: IAdvertisingSetCallback
    @Mock private lateinit var binder: IBinder
    @Mock private lateinit var packageManager: PackageManager

    private lateinit var advertiseManager: AdvertiseManager
    private var advertiserId = 0

    @Before
    fun setUp() {
        adapterService.mockPackageManager(packageManager)
        doReturn(APP_NAME).whenever(packageManager).getNameForUid(anyInt())

        advertiseManager =
            AdvertiseManager(
                adapterService,
                gattService,
                nativeInterface,
                TestLooper().looper,
                advertiserMap,
            )

        val parameters = AdvertisingSetParameters.Builder().build()
        val advertiseData = AdvertiseData.Builder().build()
        val scanResponse = AdvertiseData.Builder().build()
        val periodicParameters = PeriodicAdvertisingParameters.Builder().build()
        val periodicData = AdvertiseData.Builder().build()
        val duration = 10
        val maxExtAdvEvents = 15

        doReturn(binder).whenever(callback).asBinder()
        doNothing().whenever(binder).linkToDeath(any(), eq(0))

        advertiseManager.startAdvertisingSet(
            parameters,
            advertiseData,
            scanResponse,
            periodicParameters,
            periodicData,
            duration,
            maxExtAdvEvents,
            null,
            callback,
            source,
        )

        advertiserId = advertiseManager.mTempRegistrationId
    }

    @After
    fun tearDown() {
        advertiseManager.cleanup()
    }

    @Test
    fun advertisingSet() {
        val enable = true
        val duration = 60
        val maxExtAdvEvents = 100
        advertiseManager.enableAdvertisingSet(
            advertiserId,
            enable,
            duration,
            maxExtAdvEvents,
            source,
        )
        verify(advertiserMap).enableAdvertisingSet(advertiserId, enable, duration, maxExtAdvEvents)
    }

    @Test
    fun advertisingData() {
        val advertiseData = AdvertiseData.Builder().build()

        advertiseManager.setAdvertisingData(advertiserId, advertiseData)
        verify(advertiserMap).setAdvertisingData(advertiserId, advertiseData)
    }

    @Test
    fun scanResponseData() {
        val scanResponse = AdvertiseData.Builder().build()
        advertiseManager.setScanResponseData(advertiserId, scanResponse)
        verify(advertiserMap).setScanResponseData(advertiserId, scanResponse)
    }

    @Test
    fun advertisingParameters() {
        val parameters = AdvertisingSetParameters.Builder().build()
        advertiseManager.setAdvertisingParameters(advertiserId, parameters)
        verify(advertiserMap).setAdvertisingParameters(advertiserId, parameters)
    }

    @Test
    fun periodicAdvertisingParameters() {
        val periodicParameters = PeriodicAdvertisingParameters.Builder().build()
        advertiseManager.setPeriodicAdvertisingParameters(advertiserId, periodicParameters)
        verify(advertiserMap).setPeriodicAdvertisingParameters(advertiserId, periodicParameters)
    }

    @Test
    fun periodicAdvertisingData() {
        val periodicData = AdvertiseData.Builder().build()
        advertiseManager.setPeriodicAdvertisingData(advertiserId, periodicData)
        verify(advertiserMap).setPeriodicAdvertisingData(advertiserId, periodicData)
    }

    private companion object {
        private const val APP_NAME = "com.android.what.a.name"
    }
}
