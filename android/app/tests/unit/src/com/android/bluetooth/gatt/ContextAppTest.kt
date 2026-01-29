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

import android.bluetooth.BluetoothDevice.TRANSPORT_LE
import android.bluetooth.IBluetoothGattCallback
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.bluetooth.getTestDevice
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import java.util.UUID
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Test cases for [ContextApp]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class ContextAppTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var callback: IBluetoothGattCallback
    @Mock private lateinit var binder: IBinder
    @Mock private lateinit var deathRecipient: IBinder.DeathRecipient

    private val device = getTestDevice(22)

    private lateinit var contextApp: ContextApp<IBluetoothGattCallback>

    @Before
    fun setUp() {
        doReturn(binder).whenever(callback).asBinder()
        contextApp = ContextApp(UID, APP_NAME, RANDOM_UUID, callback, TRANSPORT_LE, TAG)
    }

    @Test
    fun linkToDeath_success() {
        contextApp.linkToDeath(deathRecipient)
        verify(binder).linkToDeath(eq(deathRecipient), eq(0))
    }

    @Test
    fun unlinkToDeath_success() {
        contextApp.linkToDeath(deathRecipient)
        contextApp.unlinkToDeath()
        verify(binder).unlinkToDeath(eq(deathRecipient), eq(0))
    }

    @Test
    fun unlinkToDeath_noRecipient_doesNothing() {
        contextApp.unlinkToDeath()
        verify(binder, never()).unlinkToDeath(anyOrNull(), any())
    }

    @Test
    fun queueAndPopCallback_followsFifoLogic() {
        assertThat(contextApp.popQueuedCallback()).isNull()

        val callbackInfo1 = ContextApp.CallbackInfo(device, STATUS_1)
        contextApp.queueCallback(callbackInfo1)

        assertThat(contextApp.popQueuedCallback()).isEqualTo(callbackInfo1)
        assertThat(contextApp.popQueuedCallback()).isNull()

        val callbackInfo2 = ContextApp.CallbackInfo(device, STATUS_2, HANDLE, VALUE_BYTES)
        contextApp.queueCallback(callbackInfo1)
        contextApp.queueCallback(callbackInfo2)

        assertThat(contextApp.popQueuedCallback()).isEqualTo(callbackInfo1)
        assertThat(contextApp.popQueuedCallback()).isEqualTo(callbackInfo2)
        assertThat(contextApp.popQueuedCallback()).isNull()
    }

    @Test
    fun callbackInfo_valueByteArray() {
        val infoWithNull = ContextApp.CallbackInfo(device, STATUS_1)
        assertThat(infoWithNull.valueByteArray()).isNull()

        val infoWithValue = ContextApp.CallbackInfo(device, STATUS_2, HANDLE, VALUE_BYTES)
        assertThat(infoWithValue.valueByteArray()).isEqualTo(VALUE_BYTES.toByteArray())
    }

    private companion object {
        private const val UID = 1001
        private const val APP_NAME = "com.android.test.app"
        private const val TAG = "TestTag"
        private const val STATUS_1 = 1
        private const val STATUS_2 = 2
        private const val HANDLE = 5
        private val RANDOM_UUID = UUID.randomUUID()
        private val VALUE_BYTES = ByteString.copyFrom(byteArrayOf(1, 2, 3))
    }
}
