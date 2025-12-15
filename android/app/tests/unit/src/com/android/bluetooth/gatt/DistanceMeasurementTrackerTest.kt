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

import android.bluetooth.le.DistanceMeasurementMethod
import android.bluetooth.le.DistanceMeasurementParams
import android.bluetooth.le.IDistanceMeasurementCallback
import android.os.HandlerThread
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.bluetooth.getTestDevice
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.kotlin.after
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify

/** Test cases for [DistanceMeasurementTracker]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class DistanceMeasurementTrackerTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var distanceMeasurementManager: DistanceMeasurementManager
    @Mock private lateinit var callback: IDistanceMeasurementCallback

    private val device = getTestDevice(35)

    private lateinit var tracker: DistanceMeasurementTracker
    private lateinit var uuid: UUID
    private lateinit var params: DistanceMeasurementParams
    private lateinit var handlerThread: HandlerThread

    @Before
    fun setUp() {
        uuid = UUID.randomUUID()
        params =
            DistanceMeasurementParams.Builder(device)
                .setDurationSeconds(TIMEOUT_S.inWholeSeconds.toInt())
                .setFrequency(DistanceMeasurementParams.REPORT_FREQUENCY_LOW)
                .setMethodId(METHOD)
                .build()
        tracker =
            DistanceMeasurementTracker(
                distanceMeasurementManager,
                APP_UID,
                params,
                device.address,
                uuid,
                1000,
                callback,
            )
        handlerThread =
            HandlerThread("DistanceMeasurementTrackerTestHandlerThread").apply { start() }
    }

    @After
    fun tearDown() {
        handlerThread.quitSafely()
        handlerThread.join(TIMEOUT_MS.inWholeMilliseconds)
    }

    @Test
    fun startTimer_triggersStopAfterTimeout() {
        tracker.startTimer(handlerThread.looper)
        verify(distanceMeasurementManager, timeout(TIMEOUT_MS.inWholeMilliseconds))
            .stopDistanceMeasurement(uuid, device, METHOD, true)
    }

    @Test
    fun cancelTimer_preventsStop() {
        tracker.startTimer(handlerThread.looper)
        tracker.cancelTimer()
        verify(distanceMeasurementManager, after(TIMEOUT_MS.inWholeMilliseconds).never())
            .stopDistanceMeasurement(uuid, device, METHOD, true)
    }

    @Test
    fun testEquals() {
        val otherTracker =
            DistanceMeasurementTracker(
                distanceMeasurementManager,
                APP_UID,
                params,
                device.address,
                uuid,
                1000,
                callback,
            )
        assertThat(tracker).isEqualTo(otherTracker)
    }

    @Test
    fun testHashCode() {
        val otherTracker =
            DistanceMeasurementTracker(
                distanceMeasurementManager,
                APP_UID,
                params,
                device.address,
                uuid,
                1000,
                callback,
            )
        assertThat(tracker.hashCode()).isEqualTo(otherTracker.hashCode())
    }

    companion object {
        private const val METHOD = DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI
        private const val APP_UID = 100
        private val TIMEOUT_S = 1.seconds
        private val TIMEOUT_MS = 1500.milliseconds
    }
}
