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

package com.android.bluetooth.map;

import static android.content.pm.PackageManager.FEATURE_TELEPHONY_MESSAGING;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.content.pm.PackageManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.bluetooth.map.BluetoothMapSmsPdu.SmsPdu;
import com.android.tests.bluetooth.MockitoRule;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;

/** Test cases for {@link BluetoothMapbMessageSms}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class BluetoothMapbMessageSmsTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private BluetoothMapService mMapService;

    private static final String TEST_SMS_BODY = "test_sms_body";
    private static final String TEST_MESSAGE = "test";
    private static final String TEST_ADDRESS = "12";

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();

    private List<SmsPdu> TEST_SMS_BODY_PDUS;

    @Before
    public void setUp() {
        // Do not run test if sms is not supported
        PackageManager packageManager = mContext.getPackageManager();
        Assume.assumeTrue(packageManager.hasSystemFeature(FEATURE_TELEPHONY_MESSAGING));

        TEST_SMS_BODY_PDUS = BluetoothMapSmsPdu.getSubmitPdus(mContext, TEST_MESSAGE, TEST_ADDRESS);
    }

    @Test
    public void settersAndGetters() {
        BluetoothMapbMessageSms messageSms = new BluetoothMapbMessageSms(mMapService);
        messageSms.setSmsBody(TEST_SMS_BODY);
        messageSms.setSmsBodyPdus(TEST_SMS_BODY_PDUS);

        assertThat(messageSms.getSmsBody()).isEqualTo(TEST_SMS_BODY);
        assertThat(messageSms.mEncoding).isEqualTo(TEST_SMS_BODY_PDUS.get(0).getEncodingString());
    }

    @Test
    public void parseMsgInit() {
        BluetoothMapbMessageSms messageSms = new BluetoothMapbMessageSms(mMapService);
        messageSms.parseMsgInit();
        assertThat(messageSms.getSmsBody()).isEqualTo("");
    }

    @Test
    public void encodeToByteArray_thenAddByParsing() throws Exception {
        BluetoothMapbMessageSms messageSmsToEncode = new BluetoothMapbMessageSms(mMapService);
        messageSmsToEncode.setType(BluetoothMapUtils.TYPE.SMS_GSM);
        messageSmsToEncode.setFolder("placeholder");
        messageSmsToEncode.setStatus(true);
        messageSmsToEncode.setSmsBodyPdus(TEST_SMS_BODY_PDUS);

        byte[] encodedMessageSms = messageSmsToEncode.encode();
        InputStream inputStream = new ByteArrayInputStream(encodedMessageSms);

        BluetoothMapbMessage messageParsed =
                BluetoothMapbMessage.parse(
                        mMapService, inputStream, BluetoothMapAppParams.CHARSET_NATIVE);
        assertThat(messageParsed).isInstanceOf(BluetoothMapbMessageSms.class);
        BluetoothMapbMessageSms messageSmsParsed = (BluetoothMapbMessageSms) messageParsed;
        assertThat(messageSmsParsed.getSmsBody()).isEqualTo(TEST_MESSAGE);
    }

    @Test
    public void encodeToByteArray_withEmptyMessage_thenAddByParsing() throws Exception {
        BluetoothMapbMessageSms messageSmsToEncode = new BluetoothMapbMessageSms(mMapService);
        messageSmsToEncode.setType(BluetoothMapUtils.TYPE.SMS_GSM);
        messageSmsToEncode.setFolder("placeholder");
        messageSmsToEncode.setStatus(true);

        byte[] encodedMessageSms = messageSmsToEncode.encode();
        InputStream inputStream = new ByteArrayInputStream(encodedMessageSms);

        BluetoothMapbMessage messageParsed =
                BluetoothMapbMessage.parse(
                        mMapService, inputStream, BluetoothMapAppParams.CHARSET_UTF8);
        assertThat(messageParsed).isInstanceOf(BluetoothMapbMessageSms.class);
        BluetoothMapbMessageSms messageSmsParsed = (BluetoothMapbMessageSms) messageParsed;
        assertThat(messageSmsParsed.getSmsBody()).isEqualTo("");
    }
}
