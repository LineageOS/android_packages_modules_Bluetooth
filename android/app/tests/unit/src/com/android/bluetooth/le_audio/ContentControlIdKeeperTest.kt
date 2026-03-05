/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.bluetooth.le_audio

import android.bluetooth.BluetoothLeAudio
import android.os.ParcelUuid
import android.platform.test.flag.junit.SetFlagsRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.android.bluetooth.btservice.AdapterService
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import java.util.Optional
import java.util.UUID
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Test cases for [ContentControlIdKeeper]. */
@MediumTest
@RunWith(AndroidJUnit4::class)
class ContentControlIdKeeperTest {
    @get:Rule val setFlagsRule = SetFlagsRule()
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var adapterService: AdapterService
    @Mock private lateinit var leAudioService: LeAudioService

    @Before
    fun setUp() {
        doReturn(Optional.of(leAudioService)).whenever(adapterService).leAudioService
        ContentControlIdKeeper.initForTesting()
    }

    fun testCcidAcquire(uuid: ParcelUuid, context: Int, expectedListSize: Int): Int {
        val ccid = ContentControlIdKeeper.acquireCcid(adapterService, uuid, context)
        assertThat(ccid).isNotEqualTo(ContentControlIdKeeper.CCID_INVALID)

        verify(leAudioService).setCcidInformation(eq(uuid), eq(ccid), eq(context))
        val uuidToCcidContextPair = ContentControlIdKeeper.getUuidToCcidContextPairMap()
        assertThat(uuidToCcidContextPair).hasSize(expectedListSize)
        assertThat(uuidToCcidContextPair).containsKey(uuid)
        assertThat(uuidToCcidContextPair[uuid]?.first).isEqualTo(ccid)
        assertThat(uuidToCcidContextPair[uuid]?.second).isEqualTo(context)

        return ccid
    }

    fun testCcidRelease(uuid: ParcelUuid, ccid: Int, expectedListSize: Int) {
        var uuidToCcidContextPair = ContentControlIdKeeper.getUuidToCcidContextPairMap()
        assertThat(uuidToCcidContextPair).containsKey(uuid)

        ContentControlIdKeeper.releaseCcid(adapterService, ccid)
        uuidToCcidContextPair = ContentControlIdKeeper.getUuidToCcidContextPairMap()
        assertThat(uuidToCcidContextPair).doesNotContainKey(uuid)

        verify(leAudioService).setCcidInformation(eq(uuid), eq(ccid), eq(0))

        assertThat(uuidToCcidContextPair).hasSize(expectedListSize)
    }

    @Test
    fun testAcquireReleaseCcid() {
        val uuidOne = ParcelUuid(UUID.randomUUID())
        val uuidTwo = ParcelUuid(UUID.randomUUID())

        val ccidOne = testCcidAcquire(uuidOne, BluetoothLeAudio.CONTEXT_TYPE_MEDIA, 1)
        val ccidTwo = testCcidAcquire(uuidTwo, BluetoothLeAudio.CONTEXT_TYPE_RINGTONE, 2)
        assertThat(ccidOne).isNotEqualTo(ccidTwo)

        testCcidRelease(uuidOne, ccidOne, 1)
        testCcidRelease(uuidTwo, ccidTwo, 0)
    }

    @Test
    fun testAcquireReleaseCcidForCompoundContext() {
        val uuid = ParcelUuid(UUID.randomUUID())
        val ccid =
            testCcidAcquire(
                uuid,
                BluetoothLeAudio.CONTEXT_TYPE_MEDIA or BluetoothLeAudio.CONTEXT_TYPE_RINGTONE,
                1,
            )
        testCcidRelease(uuid, ccid, 0)
    }

    @Test
    fun testAcquireInvalidContext() {
        val uuid = ParcelUuid(UUID.randomUUID())

        assertThat(ContentControlIdKeeper.acquireCcid(adapterService, uuid, 0))
            .isEqualTo(ContentControlIdKeeper.CCID_INVALID)

        verify(leAudioService, times(0))
            .setCcidInformation(any<ParcelUuid>(), any<Int>(), any<Int>())
        val uuidToCcidContextPair = ContentControlIdKeeper.getUuidToCcidContextPairMap()
        assertThat(uuidToCcidContextPair).isEmpty()
    }

    @Test
    fun testAcquireContextMoreThanOnce() {
        val uuid = ParcelUuid(UUID.randomUUID())

        val ccidOne = testCcidAcquire(uuid, BluetoothLeAudio.CONTEXT_TYPE_MEDIA, 1)
        val ccidTwo = testCcidAcquire(uuid, BluetoothLeAudio.CONTEXT_TYPE_RINGTONE, 1)

        // This is implementation specific but verifies that the previous CCID was recycled
        assertThat(ccidTwo).isEqualTo(ccidOne)
    }
}
