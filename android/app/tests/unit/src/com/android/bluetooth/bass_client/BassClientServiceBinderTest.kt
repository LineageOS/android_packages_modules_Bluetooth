/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.bluetooth.bass_client

import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED
import android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
import android.bluetooth.IBluetoothLeBroadcastAssistantCallback
import android.bluetooth.le.ScanFilter
import android.content.AttributionSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.bluetooth.getTestDevice
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

/** Test cases for [BassClientServiceBinder]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class BassClientServiceBinderTest {

    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var source: AttributionSource
    @Mock private lateinit var service: BassClientService

    private val device = getTestDevice(0)

    private lateinit var binder: BassClientServiceBinder

    @Before
    fun setUp() {
        binder = BassClientServiceBinder(service)
    }

    @Test
    fun cleanUp() {
        binder.cleanup()
    }

    @Test
    fun getConnectionState() {
        binder.getConnectionState(device, source)
        verify(service).getConnectionState(device)

        binder.cleanup()
        assertThat(binder.getConnectionState(device, source)).isEqualTo(STATE_DISCONNECTED)
    }

    @Test
    fun getDevicesMatchingConnectionStates() {
        val states = intArrayOf(STATE_DISCONNECTED)
        binder.getDevicesMatchingConnectionStates(states, source)
        verify(service).getDevicesMatchingConnectionStates(states)

        binder.cleanup()
        assertThat(binder.getDevicesMatchingConnectionStates(states, source)).isEmpty()
    }

    @Test
    fun getConnectedDevices() {
        binder.getConnectedDevices(source)
        verify(service).connectedDevices

        binder.cleanup()
        assertThat(binder.getConnectedDevices(source)).isEmpty()
    }

    @Test
    fun setConnectionPolicy() {
        binder.setConnectionPolicy(device, CONNECTION_POLICY_ALLOWED, source)
        verify(service).setConnectionPolicy(device, CONNECTION_POLICY_ALLOWED)

        binder.cleanup()
        assertThat(binder.setConnectionPolicy(device, CONNECTION_POLICY_ALLOWED, source)).isFalse()
    }

    @Test
    fun getConnectionPolicy() {
        binder.getConnectionPolicy(device, source)
        verify(service).getConnectionPolicy(device)

        binder.cleanup()
        assertThat(binder.getConnectionPolicy(device, source))
            .isEqualTo(CONNECTION_POLICY_FORBIDDEN)
    }

    @Test
    fun registerCallback() {
        val cb = mock<IBluetoothLeBroadcastAssistantCallback>()
        binder.registerCallback(cb, source)
        verify(service).registerCallback(cb)
    }

    @Test
    fun registerCallback_afterCleanup_doNothing() {
        binder.cleanup()
        binder.registerCallback(null, source)
        verify(service, never()).registerCallback(anyOrNull())
    }

    @Test
    fun unregisterCallback() {
        val cb = mock<IBluetoothLeBroadcastAssistantCallback>()
        binder.unregisterCallback(cb, source)
        verify(service).unregisterCallback(cb)
    }

    @Test
    fun unregisterCallback_afterCleanup_doNothing() {
        binder.cleanup()
        binder.unregisterCallback(null, source)
        verify(service, never()).unregisterCallback(anyOrNull())
    }

    @Test
    fun startSearchingForSources() {
        val filters: List<ScanFilter> = emptyList()
        binder.startSearchingForSources(filters, source)
        verify(service).startSearchingForSources(filters)
    }

    @Test
    fun startSearchingForSources_afterCleanup_doNothing() {
        binder.cleanup()
        binder.startSearchingForSources(null, source)
        verify(service, never()).startSearchingForSources(anyOrNull())
    }

    @Test
    fun stopSearchingForSources() {
        binder.stopSearchingForSources(source)
        verify(service).stopSearchingForSources()
    }

    @Test
    fun stopSearchingForSources_afterCleanup_doNothing() {
        binder.cleanup()
        binder.stopSearchingForSources(source)
        verify(service, never()).stopSearchingForSources()
    }

    @Test
    fun isSearchInProgress() {
        binder.isSearchInProgress(source)
        verify(service).isSearchInProgress

        binder.cleanup()
        assertThat(binder.isSearchInProgress(source)).isFalse()
    }

    @Test
    fun addSource() {
        binder.addSource(device, null, false, source)
        verify(service).addSource(device, null, false)
    }

    @Test
    fun addSource_afterCleanup_doNothing() {
        binder.cleanup()
        binder.addSource(device, null, false, source)
        verify(service, never()).addSource(device, null, false)
    }

    @Test
    fun modifySource() {
        binder.modifySource(device, 0, null, source)
        verify(service).modifySource(device, 0, null)
    }

    @Test
    fun modifySource_afterCleanup_doNothing() {
        binder.cleanup()
        binder.modifySource(device, 0, null, source)
        verify(service, never()).modifySource(device, 0, null)
    }

    @Test
    fun removeSource() {
        binder.removeSource(device, 0, source)
        verify(service).removeSource(device, 0)
    }

    @Test
    fun removeSource_afterCleanup_doNothing() {
        binder.cleanup()
        binder.removeSource(device, 0, source)
        verify(service, never()).removeSource(device, 0)
    }

    @Test
    fun getAllSources() {
        binder.getAllSources(device, source)
        verify(service).getAllSources(device)

        binder.cleanup()
        assertThat(binder.getAllSources(device, source)).isEmpty()
    }

    @Test
    fun getMaximumSourceCapacity() {
        binder.getMaximumSourceCapacity(device, source)
        verify(service).getMaximumSourceCapacity(device)

        binder.cleanup()
        assertThat(binder.getMaximumSourceCapacity(device, source)).isEqualTo(0)
    }
}
