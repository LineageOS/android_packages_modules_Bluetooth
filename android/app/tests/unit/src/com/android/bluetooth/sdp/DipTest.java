/*
 * Copyright 2018 The Android Open Source Project
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

package com.android.bluetooth.sdp;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.SdpDipRecord;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestUtils;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AbstractionLayer;
import com.android.bluetooth.btservice.AdapterService;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class DipTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AdapterService mAdapterService;
    @Mock private SdpManagerNativeInterface mNativeInterface;

    private final BluetoothDevice mDevice = getTestDevice(123);

    private final ArgumentCaptor<Intent> mIntentArgument = ArgumentCaptor.forClass(Intent.class);
    private final ArgumentCaptor<String> mStringArgument = ArgumentCaptor.forClass(String.class);
    private final ArgumentCaptor<Bundle> mBundleArgument = ArgumentCaptor.forClass(Bundle.class);

    private SdpManager mSdpManager;

    @Before
    public void setUp() throws Exception {
        SdpManagerNativeInterface.setInstance(mNativeInterface);
        TestUtils.setAdapterService(mAdapterService);
        doReturn("00:01:02:03:04:05").when(mAdapterService).getIdentityAddress("00:01:02:03:04:05");

        if (Looper.myLooper() == null) {
            Looper.prepare();
        }

        mSdpManager = new SdpManager(mAdapterService);
    }

    @After
    public void tearDown() throws Exception {
        TestUtils.clearAdapterService(mAdapterService);
        SdpManagerNativeInterface.setInstance(null);
    }

    private static void verifyDipSdpRecordIntent(
            ArgumentCaptor<Intent> intentArgument,
            int status,
            BluetoothDevice device,
            byte[] uuid,
            int specificationId,
            int vendorId,
            int vendorIdSource,
            int productId,
            int version,
            boolean primaryRecord) {
        Intent intent = intentArgument.getValue();

        assertThat(intent).isNotNull();
        assertThat(intent.getAction()).isEqualTo(BluetoothDevice.ACTION_SDP_RECORD);
        assertThat(device).isEqualTo(intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE));
        assertThat(Utils.byteArrayToUuid(uuid)[0])
                .isEqualTo(intent.getParcelableExtra(BluetoothDevice.EXTRA_UUID));
        assertThat(status)
                .isEqualTo(intent.getIntExtra(BluetoothDevice.EXTRA_SDP_SEARCH_STATUS, -1));

        SdpDipRecord record = intent.getParcelableExtra(BluetoothDevice.EXTRA_SDP_RECORD);
        assertThat(record).isNotNull();
        assertThat(specificationId).isEqualTo(record.getSpecificationId());
        assertThat(vendorId).isEqualTo(record.getVendorId());
        assertThat(vendorIdSource).isEqualTo(record.getVendorIdSource());
        assertThat(productId).isEqualTo(record.getProductId());
        assertThat(version).isEqualTo(record.getVersion());
        assertThat(primaryRecord).isEqualTo(record.getPrimaryRecord());
    }

    /** Test that an outgoing connection/disconnection succeeds */
    @Test
    @SmallTest
    public void testDipCallbackSuccess() {
        // DIP uuid in bytes
        byte[] uuid = {0, 0, 18, 0, 0, 0, 16, 0, -128, 0, 0, -128, 95, -101, 52, -5};
        int specificationId = 0x0103;
        int vendorId = 0x18d1;
        int vendorIdSource = 1;
        int productId = 0x1234;
        int version = 0x0100;
        boolean primaryRecord = true;
        boolean moreResults = false;

        mSdpManager.sdpSearch(mDevice, BluetoothUuid.DIP);
        mSdpManager.sdpDipRecordFoundCallback(
                AbstractionLayer.BT_STATUS_SUCCESS,
                Utils.getByteAddress(mDevice),
                uuid,
                specificationId,
                vendorId,
                vendorIdSource,
                productId,
                version,
                primaryRecord,
                moreResults);
        verify(mAdapterService)
                .sendBroadcast(
                        mIntentArgument.capture(),
                        mStringArgument.capture(),
                        mBundleArgument.capture());
        verifyDipSdpRecordIntent(
                mIntentArgument,
                AbstractionLayer.BT_STATUS_SUCCESS,
                mDevice,
                uuid,
                specificationId,
                vendorId,
                vendorIdSource,
                productId,
                version,
                primaryRecord);
    }
}
