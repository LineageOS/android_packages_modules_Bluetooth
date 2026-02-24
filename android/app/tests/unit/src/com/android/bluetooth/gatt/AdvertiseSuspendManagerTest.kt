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

package com.android.bluetooth.gatt

import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSetParameters
import android.content.AttributionSource
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.bluetooth.btservice.AdapterSuspend
import com.android.bluetooth.flags.Flags
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

/** Test cases for [AdvertiseSuspendManager]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class AdvertiseSuspendManagerTest {
    @get:Rule val mockitoRule = MockitoRule()
    @get:Rule val setFlagsRule = SetFlagsRule()

    @Mock private lateinit var adapterSuspend: AdapterSuspend
    @Mock private lateinit var advertiseManager: AdvertiseManager
    @Mock private lateinit var source: AttributionSource

    private lateinit var advertiseSuspendManager: AdvertiseSuspendManager

    @Before
    fun setUp() {
        advertiseSuspendManager = AdvertiseSuspendManager(advertiseManager, adapterSuspend)
    }

    @Test
    @EnableFlags(Flags.FLAG_ADAPTER_SUSPEND_ADVERTISEMENT)
    fun suspendWithOngoingAdvertisement() {
        // Start an advertisement
        advertiseSuspendManager.onStartAdvertisingSet(
            REG_ID1,
            DURATION1,
            MAX_EXT_ADV_EVENTS1,
            source,
        )
        advertiseSuspendManager.onAdvertisingSetStarted(REG_ID1, ADVERTISER_ID1, STATUS_OK)

        // On suspend, verify we disable the advertisement
        advertiseSuspendManager.enterSuspend()
        verify(advertiseManager)
            .enableAdvertisingSet(eq(ADVERTISER_ID1), eq(false), any<Int>(), any<Int>(), eq(source))
        verify(adapterSuspend, never()).advertiseSuspendReady()

        advertiseSuspendManager.onAdvertisingEnabled(ADVERTISER_ID1, false, STATUS_OK)
        verify(adapterSuspend).advertiseSuspendReady()

        // On resume, verify we reenable the advertisement
        advertiseSuspendManager.exitSuspend()
        verify(advertiseManager)
            .enableAdvertisingSet(
                eq(ADVERTISER_ID1),
                eq(true),
                eq(DURATION1),
                eq(MAX_EXT_ADV_EVENTS1),
                eq(source),
            )
        advertiseSuspendManager.onAdvertisingEnabled(ADVERTISER_ID1, true, STATUS_OK)
    }

    @Test
    @EnableFlags(Flags.FLAG_ADAPTER_SUSPEND_ADVERTISEMENT)
    fun suspendWithoutOngoingAdvertisement() {
        // Start an advertisement
        advertiseSuspendManager.onStartAdvertisingSet(
            REG_ID1,
            DURATION1,
            MAX_EXT_ADV_EVENTS1,
            source,
        )
        advertiseSuspendManager.onAdvertisingSetStarted(REG_ID1, ADVERTISER_ID1, STATUS_OK)

        // Disable the advertisement
        advertiseSuspendManager.onEnableAdvertisingSet(ADVERTISER_ID1)
        advertiseSuspendManager.onAdvertisingEnabled(ADVERTISER_ID1, false, STATUS_OK)

        // On suspend, verify we report ready immediately
        advertiseSuspendManager.enterSuspend()
        verify(adapterSuspend).advertiseSuspendReady()

        // Verify we never enable/disable any advertisements
        advertiseSuspendManager.exitSuspend()
        verify(advertiseManager, never())
            .enableAdvertisingSet(any<Int>(), any<Boolean>(), any<Int>(), any<Int>(), eq(source))
    }

    @Test
    @EnableFlags(Flags.FLAG_ADAPTER_SUSPEND_ADVERTISEMENT)
    fun suspendWhenCreatingAdvertisement() {
        val order = inOrder(advertiseManager)

        // Create two advertisements. The native layer hasn't finished creating it.
        advertiseSuspendManager.onStartAdvertisingSet(
            REG_ID1,
            DURATION1,
            MAX_EXT_ADV_EVENTS1,
            source,
        )
        advertiseSuspendManager.onStartAdvertisingSet(
            REG_ID2,
            DURATION2,
            MAX_EXT_ADV_EVENTS2,
            source,
        )

        // We suspend here. Verify we wait instead of enabling/disabling any advertisements or going
        // to suspend.
        advertiseSuspendManager.enterSuspend()
        order
            .verify(advertiseManager, never())
            .enableAdvertisingSet(any<Int>(), any<Boolean>(), any<Int>(), any<Int>(), eq(source))
        verify(adapterSuspend, never()).advertiseSuspendReady()

        // The native layer registered advertisement A. We still wait.
        advertiseSuspendManager.onAdvertisingSetStarted(REG_ID1, ADVERTISER_ID1, STATUS_OK)
        order
            .verify(advertiseManager, never())
            .enableAdvertisingSet(any<Int>(), any<Boolean>(), any<Int>(), any<Int>(), eq(source))
        verify(adapterSuspend, never()).advertiseSuspendReady()

        // The native layer registered advertisement B with failure.
        // We move to the next step which is to disable advertisement A. Verify we do nothing else.
        advertiseSuspendManager.onAdvertisingSetStarted(REG_ID2, ADVERTISER_ID2, STATUS_FAIL)
        order
            .verify(advertiseManager)
            .enableAdvertisingSet(eq(ADVERTISER_ID1), eq(false), any<Int>(), any<Int>(), eq(source))
        order
            .verify(advertiseManager, never())
            .enableAdvertisingSet(any<Int>(), any<Boolean>(), any<Int>(), any<Int>(), eq(source))
        verify(adapterSuspend, never()).advertiseSuspendReady()

        // Advertisement A is disabled. We should move to suspend step.
        advertiseSuspendManager.onAdvertisingEnabled(ADVERTISER_ID1, false, STATUS_OK)
        order
            .verify(advertiseManager, never())
            .enableAdvertisingSet(any<Int>(), any<Boolean>(), any<Int>(), any<Int>(), eq(source))
        verify(adapterSuspend).advertiseSuspendReady()

        // On resume, verify we reenable advertising A only.
        advertiseSuspendManager.exitSuspend()
        order
            .verify(advertiseManager)
            .enableAdvertisingSet(
                eq(ADVERTISER_ID1),
                eq(true),
                eq(DURATION1),
                eq(MAX_EXT_ADV_EVENTS1),
                eq(source),
            )
        advertiseSuspendManager.onAdvertisingEnabled(ADVERTISER_ID1, true, STATUS_OK)
        order
            .verify(advertiseManager, never())
            .enableAdvertisingSet(any<Int>(), any<Boolean>(), any<Int>(), any<Int>(), eq(source))
    }

    @Test
    @EnableFlags(Flags.FLAG_ADAPTER_SUSPEND_ADVERTISEMENT)
    fun suspendWhenDisablingAdvertisement() {
        val order = inOrder(advertiseManager)

        // Start an advertisement.
        advertiseSuspendManager.onStartAdvertisingSet(
            REG_ID1,
            DURATION1,
            MAX_EXT_ADV_EVENTS1,
            source,
        )
        advertiseSuspendManager.onAdvertisingSetStarted(REG_ID1, ADVERTISER_ID1, STATUS_OK)

        // Disable the advertisement. The process is not yet done.
        advertiseSuspendManager.onEnableAdvertisingSet(ADVERTISER_ID1)

        // Enter suspend. Verify we wait for the previous disablement.
        advertiseSuspendManager.enterSuspend()
        verify(adapterSuspend, never()).advertiseSuspendReady()

        // The advertisement disablement is finally completed.
        // Verify we report ready without any other enable/disablement effort.
        advertiseSuspendManager.onAdvertisingEnabled(ADVERTISER_ID1, false, STATUS_OK)
        verify(adapterSuspend).advertiseSuspendReady()
        order
            .verify(advertiseManager, never())
            .enableAdvertisingSet(any<Int>(), any<Boolean>(), any<Int>(), any<Int>(), eq(source))

        // On resume, verify we don't reenable the advertisement.
        advertiseSuspendManager.exitSuspend()
        order
            .verify(advertiseManager, never())
            .enableAdvertisingSet(any<Int>(), any<Boolean>(), any<Int>(), any<Int>(), eq(source))
    }

    @Test
    @EnableFlags(Flags.FLAG_ADAPTER_SUSPEND_ADVERTISEMENT)
    fun suspendThenQueueFutureRequests() {
        val order = inOrder(advertiseManager)

        // Start an advertisement.
        advertiseSuspendManager.onStartAdvertisingSet(
            REG_ID1,
            DURATION1,
            MAX_EXT_ADV_EVENTS1,
            source,
        )
        advertiseSuspendManager.onAdvertisingSetStarted(REG_ID1, ADVERTISER_ID1, STATUS_OK)

        // Create another advertisement which isn't yet started.
        advertiseSuspendManager.onStartAdvertisingSet(
            REG_ID2,
            DURATION2,
            MAX_EXT_ADV_EVENTS2,
            source,
        )

        // Suspend here. We shouldn't report ready because we're still waiting for the 2nd adv.
        advertiseSuspendManager.enterSuspend()
        verify(adapterSuspend, never()).advertiseSuspendReady()

        // Receive some requests from app. All of them should be queued.
        assertThat(advertiseSuspendManager.shouldQueueCommand()).isTrue()
        val advertiseData = AdvertiseData.Builder().build()
        advertiseSuspendManager.queueSetAdvertisingData(ADVERTISER_ID1, advertiseData)
        val scanResponse = AdvertiseData.Builder().build()
        advertiseSuspendManager.queueSetScanResponseData(ADVERTISER_ID1, scanResponse)
        order.verify(advertiseManager, never()).setAdvertisingData(any<Int>(), any())
        order.verify(advertiseManager, never()).setScanResponseData(any<Int>(), any())

        // Native layer is done (with failure). We should proceed to disable advertisement 1.
        advertiseSuspendManager.onAdvertisingSetStarted(REG_ID2, ADVERTISER_ID2, STATUS_FAIL)
        order
            .verify(advertiseManager)
            .enableAdvertisingSet(eq(ADVERTISER_ID1), eq(false), any<Int>(), any<Int>(), eq(source))
        advertiseSuspendManager.onEnableAdvertisingSet(ADVERTISER_ID1)
        advertiseSuspendManager.onAdvertisingEnabled(ADVERTISER_ID1, false, STATUS_OK)

        // At this time suspend is ready. Verify we haven't process the queue.
        verify(adapterSuspend).advertiseSuspendReady()
        order.verify(advertiseManager, never()).setAdvertisingData(any<Int>(), any())
        order.verify(advertiseManager, never()).setScanResponseData(any<Int>(), any())

        // On resume, we first reenable advertisement 1. Verify we haven't process the queue.
        advertiseSuspendManager.exitSuspend()
        order
            .verify(advertiseManager)
            .enableAdvertisingSet(
                eq(ADVERTISER_ID1),
                eq(true),
                eq(DURATION1),
                eq(MAX_EXT_ADV_EVENTS1),
                eq(source),
            )
        order.verify(advertiseManager, never()).setAdvertisingData(any<Int>(), any())
        order.verify(advertiseManager, never()).setScanResponseData(any<Int>(), any())

        // If at this time the app sends another request, it too shall be queued.
        assertThat(advertiseSuspendManager.shouldQueueCommand()).isTrue()
        val parameters = AdvertisingSetParameters.Builder().build()
        advertiseSuspendManager.queueSetAdvertisingParameters(ADVERTISER_ID1, parameters)
        order.verify(advertiseManager, never()).setAdvertisingParameters(any<Int>(), any())

        // Only when we finish re-enabling can we process the queue.
        advertiseSuspendManager.onAdvertisingEnabled(ADVERTISER_ID1, true, STATUS_OK)
        order.verify(advertiseManager).setAdvertisingData(eq(ADVERTISER_ID1), eq(advertiseData))
        order.verify(advertiseManager).setScanResponseData(eq(ADVERTISER_ID1), eq(scanResponse))
        order.verify(advertiseManager).setAdvertisingParameters(eq(ADVERTISER_ID1), eq(parameters))
    }

    companion object {
        private const val REG_ID1 = -1
        private const val REG_ID2 = -2
        private const val ADVERTISER_ID1 = 1
        private const val ADVERTISER_ID2 = 2
        private const val DURATION1 = 60
        private const val DURATION2 = 61
        private const val MAX_EXT_ADV_EVENTS1 = 10
        private const val MAX_EXT_ADV_EVENTS2 = 11
        private const val STATUS_OK = 0
        private const val STATUS_FAIL = 1
    }
}
