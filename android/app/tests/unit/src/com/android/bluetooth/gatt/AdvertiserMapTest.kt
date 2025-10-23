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
import android.bluetooth.le.PeriodicAdvertisingParameters
import android.content.AttributionSource
import android.os.Binder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify

/** Test cases for [AdvertiserMap]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class AdvertiserMapTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var source: AttributionSource

    @Test
    fun getByMethods() {
        val advertiserMap = AdvertiserMap()
        val uid = Binder.getCallingUid()
        val id = 12345
        advertiserMap.addAppAdvertiseStats(uid, APP_NAME, id, source)

        val stats = advertiserMap.getAppAdvertiseStatsById(id)
        assertThat(stats.mAppName).isEqualTo(APP_NAME)
    }

    @Test
    fun clear() {
        val advertiserMap = AdvertiserMap()
        val uid = Binder.getCallingUid()
        val id = 12345
        advertiserMap.addAppAdvertiseStats(uid, APP_NAME, id, source)

        val stats = advertiserMap.getAppAdvertiseStatsById(id)
        assertThat(stats.mAppName).isEqualTo(APP_NAME)

        advertiserMap.clear()
        assertThat(advertiserMap.getAppAdvertiseStatsById(id)).isNull()
    }

    @Test
    fun advertisingSetAndData() {
        val advertiserMap = AdvertiserMap()
        val id = 12345
        val appAdvertiseStats = spy(AppAdvertiseStats(Binder.getCallingUid(), id, APP_NAME, source))
        advertiserMap.addAppAdvertiseStats(id, appAdvertiseStats)

        val duration = 60
        val maxExtAdvEvents = 100
        val instanceCount = 1
        advertiserMap.enableAdvertisingSet(id, true, duration, maxExtAdvEvents)
        verify(appAdvertiseStats)
            .enableAdvertisingSet(true, duration, maxExtAdvEvents, instanceCount)

        val advertiseData = AdvertiseData.Builder().build()
        advertiserMap.setAdvertisingData(id, advertiseData)
        verify(appAdvertiseStats).setAdvertisingData(advertiseData)

        val scanResponse = AdvertiseData.Builder().build()
        advertiserMap.setScanResponseData(id, scanResponse)
        verify(appAdvertiseStats).setScanResponseData(scanResponse)

        val parameters = AdvertisingSetParameters.Builder().build()
        advertiserMap.setAdvertisingParameters(id, parameters)
        verify(appAdvertiseStats).setAdvertisingParameters(parameters)

        val periodicParameters = PeriodicAdvertisingParameters.Builder().build()
        advertiserMap.setPeriodicAdvertisingParameters(id, periodicParameters)
        verify(appAdvertiseStats).setPeriodicAdvertisingParameters(periodicParameters)

        val periodicData = AdvertiseData.Builder().build()
        advertiserMap.setPeriodicAdvertisingData(id, periodicData)
        verify(appAdvertiseStats).setPeriodicAdvertisingData(periodicData)

        advertiserMap.onPeriodicAdvertiseEnabled(id, true)
        verify(appAdvertiseStats).onPeriodicAdvertiseEnabled(true)

        val toBeRemoved = advertiserMap.getAppAdvertiseStatsById(id)
        assertThat(toBeRemoved).isNotNull()

        advertiserMap.removeAppAdvertiseStats(id)

        val isRemoved = advertiserMap.getAppAdvertiseStatsById(id)
        assertThat(isRemoved).isNull()
    }

    @Test
    fun emptyStop_doesNotCrash() {
        val advertiserMap = AdvertiserMap()
        val id = 12345
        advertiserMap.recordAdvertiseStop(id)
    }

    @Test
    fun testDump_doesNotCrash() {
        val sb = StringBuilder()
        val advertiserMap = AdvertiserMap()
        val uid = Binder.getCallingUid()
        val id = 12345
        advertiserMap.addAppAdvertiseStats(uid, APP_NAME, id, source)
        advertiserMap.recordAdvertiseStop(id)

        val idSecond = 54321
        advertiserMap.addAppAdvertiseStats(uid, APP_NAME, idSecond, source)
        advertiserMap.dump(sb)
    }

    private companion object {
        private const val APP_NAME = "com.android.what.a.name"
    }
}
