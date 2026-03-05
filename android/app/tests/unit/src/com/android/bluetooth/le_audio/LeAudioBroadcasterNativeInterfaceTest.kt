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

import android.bluetooth.BluetoothLeBroadcastMetadata
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.bluetooth.btservice.AdapterService
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.verify
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.whenever

/** Test cases for [LeAudioBroadcasterNativeInterface]. */
@RunWith(AndroidJUnit4::class)
class LeAudioBroadcasterNativeInterfaceTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var adapterService: AdapterService
    @Mock private lateinit var mockService: LeAudioService

    private lateinit var nativeInterface: LeAudioBroadcasterNativeInterface

    @Before
    fun setUp() {
        doReturn(true).whenever(mockService).isAvailable
        nativeInterface = LeAudioBroadcasterNativeInterface(adapterService, mockService)
    }

    @Test
    fun onBroadcastCreated() {
        val broadcastId = 1
        val success = true
        nativeInterface.onBroadcastCreated(broadcastId, success)

        val event = argumentCaptor<LeAudioStackEvent>()
        verify(mockService).messageFromNative(event.capture())
        assertThat(event.firstValue.type).isEqualTo(LeAudioStackEvent.EVENT_TYPE_BROADCAST_CREATED)
    }

    @Test
    fun onBroadcastDestroyed() {
        val broadcastId = 1

        nativeInterface.onBroadcastDestroyed(broadcastId)

        val event = argumentCaptor<LeAudioStackEvent>()
        verify(mockService).messageFromNative(event.capture())
        assertThat(event.firstValue.type)
            .isEqualTo(LeAudioStackEvent.EVENT_TYPE_BROADCAST_DESTROYED)
    }

    @Test
    fun onBroadcastStateChanged() {
        val broadcastId = 1
        val state = 0
        nativeInterface.onBroadcastStateChanged(broadcastId, state)

        val event = argumentCaptor<LeAudioStackEvent>()
        verify(mockService).messageFromNative(event.capture())
        assertThat(event.firstValue.type).isEqualTo(LeAudioStackEvent.EVENT_TYPE_BROADCAST_STATE)
    }

    @Test
    fun onBroadcastMetadataChanged() {
        val broadcastId = 1
        val metadata: BluetoothLeBroadcastMetadata? = null
        nativeInterface.onBroadcastMetadataChanged(broadcastId, metadata)

        val event = argumentCaptor<LeAudioStackEvent>()
        verify(mockService).messageFromNative(event.capture())
        assertThat(event.firstValue.type)
            .isEqualTo(LeAudioStackEvent.EVENT_TYPE_BROADCAST_METADATA_CHANGED)
    }
}
