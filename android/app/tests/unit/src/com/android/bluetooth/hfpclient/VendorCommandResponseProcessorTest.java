/*
 * Copyright 2022 The Android Open Source Project
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

package com.android.bluetooth.hfpclient;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.doReturn;

import android.bluetooth.BluetoothAssignedNumbers;
import android.bluetooth.BluetoothDevice;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestUtils;
import com.android.bluetooth.btservice.AdapterService;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class VendorCommandResponseProcessorTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private NativeInterface mNativeInterface;
    @Mock private AdapterService mAdapterService;
    @Mock private HeadsetClientService mHeadsetClientService;

    private static final int TEST_VENDOR_ID = BluetoothAssignedNumbers.APPLE;

    private final BluetoothDevice mDevice = getTestDevice(65);

    private VendorCommandResponseProcessor mProcessor;

    @Before
    public void setUp() throws Exception {
        TestUtils.setAdapterService(mAdapterService);

        mProcessor = new VendorCommandResponseProcessor(mHeadsetClientService, mNativeInterface);
    }

    @After
    public void tearDown() throws Exception {
        TestUtils.clearAdapterService(mAdapterService);
    }

    @Test
    public void sendCommand_withSemicolon() {
        String atCommand = "command;";

        assertThat(mProcessor.sendCommand(TEST_VENDOR_ID, atCommand, mDevice)).isFalse();
    }

    @Test
    public void sendCommand_withNullDevice() {
        String atCommand = "+XAPL=";

        assertThat(mProcessor.sendCommand(TEST_VENDOR_ID, atCommand, null)).isFalse();
    }

    @Test
    public void sendCommand_withInvalidCommand() {
        String invalidCommand = "invalidCommand";

        assertThat(mProcessor.sendCommand(TEST_VENDOR_ID, invalidCommand, mDevice)).isFalse();
    }

    @Test
    public void sendCommand_withEqualSign() {
        String atCommand = "+XAPL=";
        doReturn(true)
                .when(mNativeInterface)
                .sendATCmd(
                        mDevice,
                        HeadsetClientHalConstants.HANDSFREECLIENT_AT_CMD_VENDOR_SPECIFIC_CMD,
                        0,
                        0,
                        atCommand);

        assertThat(mProcessor.sendCommand(TEST_VENDOR_ID, atCommand, mDevice)).isTrue();
    }

    @Test
    public void sendCommand_withQuestionMarkSign() {
        String atCommand = "+APLSIRI?";
        doReturn(true)
                .when(mNativeInterface)
                .sendATCmd(
                        mDevice,
                        HeadsetClientHalConstants.HANDSFREECLIENT_AT_CMD_VENDOR_SPECIFIC_CMD,
                        0,
                        0,
                        atCommand);

        assertThat(mProcessor.sendCommand(TEST_VENDOR_ID, atCommand, mDevice)).isTrue();
    }

    @Test
    public void sendCommand_failingToSendATCommand() {
        String atCommand = "+APLSIRI?";
        doReturn(false)
                .when(mNativeInterface)
                .sendATCmd(
                        mDevice,
                        HeadsetClientHalConstants.HANDSFREECLIENT_AT_CMD_VENDOR_SPECIFIC_CMD,
                        0,
                        0,
                        atCommand);

        assertThat(mProcessor.sendCommand(TEST_VENDOR_ID, atCommand, mDevice)).isFalse();
    }

    @Test
    public void processEvent_withNullDevice() {
        String atString = "+XAPL=";

        assertThat(mProcessor.processEvent(atString, null)).isFalse();
    }

    @Test
    public void processEvent_withInvalidString() {
        String invalidString = "+APLSIRI?";

        assertThat(mProcessor.processEvent(invalidString, mDevice)).isFalse();
    }

    @Test
    public void processEvent_withEqualSign() {
        String atString = "+XAPL=";

        assertThat(mProcessor.processEvent(atString, mDevice)).isTrue();
    }

    @Test
    public void processEvent_withColonSign() {
        String atString = "+APLSIRI:";

        assertThat(mProcessor.processEvent(atString, mDevice)).isTrue();
    }
}
