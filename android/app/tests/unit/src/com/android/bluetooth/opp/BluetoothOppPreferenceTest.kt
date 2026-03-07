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

package com.android.bluetooth.opp

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.bluetooth.BluetoothMethodProxy
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.getTestDevice
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.spy
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever

/** Test cases for [BluetoothOppPreference]. */
@RunWith(AndroidJUnit4::class)
class BluetoothOppPreferenceTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var adapterService: AdapterService

    private val context = InstrumentationRegistry.getInstrumentation().context
    private val device = getTestDevice(45)

    private lateinit var callProxy: BluetoothMethodProxy
    private lateinit var prefs: SharedPreferences
    private lateinit var bluetoothOppPreference: BluetoothOppPreference

    @Before
    fun setUp() {
        callProxy = spy(BluetoothMethodProxy.getInstance())
        BluetoothMethodProxy.setInstanceForTesting(callProxy)
        prefs = context.getSharedPreferences(TEST_PREF, Context.MODE_PRIVATE)

        doReturn(prefs).whenever(adapterService).getSharedPreferences(any<String>(), any<Int>())
        val address = device.address
        doReturn(address).whenever(adapterService).getIdentityAddress(address)

        doReturn(null)
            .whenever(callProxy)
            .contentResolverInsert(any(), eq(BluetoothShare.CONTENT_URI), any())

        bluetoothOppPreference = BluetoothOppPreference.getInstance(adapterService)
    }

    @After
    fun tearDown() {
        prefs.edit().clear().apply()
        context.deleteSharedPreferences(TEST_PREF)

        BluetoothMethodProxy.setInstanceForTesting(null)
        BluetoothOppUtility.sSendFileMap.clear()
        BluetoothOppManager.setInstanceForTesting(null)
        BluetoothOppPreference.setInstance(null)
    }

    @Test
    fun dump_shouldNotThrow() {
        bluetoothOppPreference.dump()
    }

    @Test
    fun setNameAndGetNameAndRemoveName_setsAndGetsAndRemovesNameCorrectly() {
        val name = "randomName"
        bluetoothOppPreference.setName(device, name)
        assertThat(bluetoothOppPreference.getName(device)).isEqualTo(name)

        // Undo the change so this will not be saved on share preference
        bluetoothOppPreference.removeName(device)
        assertThat(bluetoothOppPreference.getName(device)).isNull()
    }

    @Test
    fun setChannelAndGetAndRemoveChannel_setsAndGetsAndRemovesChannelCorrectly() {
        val uuid = 1234
        val channel = 78910
        bluetoothOppPreference.setChannel(device, uuid, channel)
        assertThat(bluetoothOppPreference.getChannel(device, uuid)).isEqualTo(channel)

        // Undo the change so this will not be saved on share preference
        bluetoothOppPreference.removeChannel(device, uuid)
        assertThat(bluetoothOppPreference.getChannel(device, uuid)).isEqualTo(-1)
    }

    companion object {
        private const val TEST_PREF = "BluetoothOppPreferenceTest"
    }
}
