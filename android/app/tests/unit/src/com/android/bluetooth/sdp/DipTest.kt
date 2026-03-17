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

package com.android.bluetooth.sdp

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothUuid
import android.bluetooth.SdpDipRecord
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.os.ParcelUuid
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.bluetooth.Util.getByteAddress
import com.android.bluetooth.Utils
import com.android.bluetooth.btservice.AbstractionLayer
import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.getTestDevice
import com.android.tests.bluetooth.MockitoRule
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.doCallRealMethod
import org.mockito.Mockito.verify
import org.mockito.kotlin.KArgumentCaptor
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class DipTest {
    @get:Rule val mockitoRule = MockitoRule()

    @Mock private lateinit var adapterService: AdapterService
    @Mock private lateinit var nativeInterface: SdpManagerNativeInterface

    private val device = getTestDevice(123)

    private val intentArgument = argumentCaptor<Intent>()
    private val stringArgument = argumentCaptor<String>()
    private val bundleArgument = argumentCaptor<Bundle>()

    private lateinit var sdpManager: SdpManager

    @Before
    fun setUp() {
        doReturn("00:01:02:03:04:05")
            .whenever(adapterService)
            .getIdentityAddress("00:01:02:03:04:05")
        doCallRealMethod().whenever(adapterService).getBrEdrAddress(any<BluetoothDevice>())
        doCallRealMethod().whenever(adapterService).getBrEdrAddress(any<String>())

        if (Looper.myLooper() == null) {
            Looper.prepare()
        }

        sdpManager = SdpManager(adapterService, nativeInterface)
    }

    private fun verifyDipSdpRecordIntent(
        intentArgument: KArgumentCaptor<Intent>,
        status: Int,
        device: BluetoothDevice,
        uuid: ByteArray,
        specificationId: Int,
        vendorId: Int,
        vendorIdSource: Int,
        productId: Int,
        version: Int,
        primaryRecord: Boolean,
    ) {
        val intent = intentArgument.firstValue

        assertThat(intent).isNotNull()
        assertThat(intent.action).isEqualTo(BluetoothDevice.ACTION_SDP_RECORD)
        assertThat(device)
            .isEqualTo(
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            )
        assertThat(Utils.byteArrayToUuid(uuid)[0])
            .isEqualTo(
                intent.getParcelableExtra(BluetoothDevice.EXTRA_UUID, ParcelUuid::class.java)
            )
        assertThat(status)
            .isEqualTo(intent.getIntExtra(BluetoothDevice.EXTRA_SDP_SEARCH_STATUS, -1))

        val record =
            intent.getParcelableExtra(BluetoothDevice.EXTRA_SDP_RECORD, SdpDipRecord::class.java)
        assertThat(record).isNotNull()
        assertThat(specificationId).isEqualTo(record!!.specificationId)
        assertThat(vendorId).isEqualTo(record.vendorId)
        assertThat(vendorIdSource).isEqualTo(record.vendorIdSource)
        assertThat(productId).isEqualTo(record.productId)
        assertThat(version).isEqualTo(record.version)
        assertThat(primaryRecord).isEqualTo(record.primaryRecord)
    }

    /** Test that an outgoing connection/disconnection succeeds */
    @Test
    fun testDipCallbackSuccess() {
        // DIP uuid in bytes
        val uuid = byteArrayOf(0, 0, 18, 0, 0, 0, 16, 0, -128, 0, 0, -128, 95, -101, 52, -5)
        val specificationId = 0x0103
        val vendorId = 0x18d1
        val vendorIdSource = 1
        val productId = 0x1234
        val version = 0x0100
        val primaryRecord = true
        val moreResults = false

        sdpManager.sdpSearch(device, BluetoothUuid.DIP)
        sdpManager.sdpDipRecordFoundCallback(
            AbstractionLayer.BT_STATUS_SUCCESS,
            device.getByteAddress(),
            uuid,
            specificationId,
            vendorId,
            vendorIdSource,
            productId,
            version,
            primaryRecord,
            moreResults,
        )
        verify(adapterService)
            .sendBroadcast(
                intentArgument.capture(),
                stringArgument.capture(),
                bundleArgument.capture(),
            )
        verifyDipSdpRecordIntent(
            intentArgument,
            AbstractionLayer.BT_STATUS_SUCCESS,
            device,
            uuid,
            specificationId,
            vendorId,
            vendorIdSource,
            productId,
            version,
            primaryRecord,
        )
    }
}
