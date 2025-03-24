/*
 * Copyright (C) 2021 The Android Open Source Project
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
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothDevice;
import android.os.Bundle;

import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.List;
import java.util.Set;

/** Test cases for {@link HeadsetClientServiceInterface}. */
@RunWith(AndroidJUnit4.class)
public class HeadsetClientServiceInterfaceTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private HeadsetClientService mMockHeadsetClientService;

    private static final String TEST_NUMBER = "000-111-2222";
    private static final byte TEST_CODE = 0;
    private static final int TEST_CALL_INDEX = 0;
    private static final int TEST_FLAGS = 0;
    private static final Bundle TEST_BUNDLE = new Bundle();

    static {
        TEST_BUNDLE.putInt("test_int", 0);
    }

    private final BluetoothDevice mDevice = getTestDevice(54);
    private final HfpClientCall mTestCall =
            new HfpClientCall(
                    mDevice,
                    /* id= */ 0,
                    HfpClientCall.CALL_STATE_ACTIVE,
                    /* number= */ TEST_NUMBER,
                    /* multiParty= */ false,
                    /* outgoing= */ false,
                    /* inBandRing= */ true);

    private HeadsetClientServiceInterface mServiceInterface;

    @Before
    public void setUp() {
        HeadsetClientService.setHeadsetClientService(mMockHeadsetClientService);
        mServiceInterface = new HeadsetClientServiceInterface();
    }

    @After
    public void tearDown() {
        HeadsetClientService.setHeadsetClientService(null);
        assertThat(HeadsetClientService.getHeadsetClientService()).isNull();
    }

    private void makeHeadsetClientServiceAvailable() {
        when(mMockHeadsetClientService.isAvailable()).thenReturn(true);
        assertThat(HeadsetClientService.getHeadsetClientService())
                .isEqualTo(mMockHeadsetClientService);
    }

    @Test
    public void testDial() {
        assertThat(mServiceInterface.dial(mDevice, TEST_NUMBER)).isNull();
        makeHeadsetClientServiceAvailable();

        doReturn(mTestCall).when(mMockHeadsetClientService).dial(mDevice, TEST_NUMBER);
        assertThat(mServiceInterface.dial(mDevice, TEST_NUMBER)).isEqualTo(mTestCall);
    }

    @Test
    public void testEnterPrivateMode() {
        assertThat(mServiceInterface.enterPrivateMode(mDevice, TEST_CALL_INDEX)).isFalse();
        makeHeadsetClientServiceAvailable();

        doReturn(false).when(mMockHeadsetClientService).enterPrivateMode(mDevice, TEST_CALL_INDEX);
        assertThat(mServiceInterface.enterPrivateMode(mDevice, TEST_CALL_INDEX)).isFalse();
        doReturn(true).when(mMockHeadsetClientService).enterPrivateMode(mDevice, TEST_CALL_INDEX);
        assertThat(mServiceInterface.enterPrivateMode(mDevice, TEST_CALL_INDEX)).isTrue();
    }

    @Test
    public void testSendDTMF() {
        assertThat(mServiceInterface.sendDTMF(mDevice, TEST_CODE)).isFalse();
        makeHeadsetClientServiceAvailable();

        doReturn(false).when(mMockHeadsetClientService).sendDTMF(mDevice, TEST_CODE);
        assertThat(mServiceInterface.sendDTMF(mDevice, TEST_CODE)).isFalse();
        doReturn(true).when(mMockHeadsetClientService).sendDTMF(mDevice, TEST_CODE);
        assertThat(mServiceInterface.sendDTMF(mDevice, TEST_CODE)).isTrue();
    }

    @Test
    public void testTerminateCall() {
        assertThat(mServiceInterface.terminateCall(mDevice, mTestCall)).isFalse();
        makeHeadsetClientServiceAvailable();

        doReturn(true).when(mMockHeadsetClientService).terminateCall(mDevice, mTestCall.getUUID());
        assertThat(mServiceInterface.terminateCall(mDevice, mTestCall)).isTrue();
        doReturn(false).when(mMockHeadsetClientService).terminateCall(mDevice, mTestCall.getUUID());
        assertThat(mServiceInterface.terminateCall(mDevice, mTestCall)).isFalse();
    }

    @Test
    public void testHoldCall() {
        assertThat(mServiceInterface.holdCall(mDevice)).isFalse();
        makeHeadsetClientServiceAvailable();

        doReturn(false).when(mMockHeadsetClientService).holdCall(mDevice);
        assertThat(mServiceInterface.holdCall(mDevice)).isFalse();
        doReturn(true).when(mMockHeadsetClientService).holdCall(mDevice);
        assertThat(mServiceInterface.holdCall(mDevice)).isTrue();
    }

    @Test
    public void testAcceptCall() {
        assertThat(mServiceInterface.acceptCall(mDevice, TEST_FLAGS)).isFalse();
        makeHeadsetClientServiceAvailable();

        doReturn(false).when(mMockHeadsetClientService).acceptCall(mDevice, TEST_FLAGS);
        assertThat(mServiceInterface.acceptCall(mDevice, TEST_CODE)).isFalse();
        doReturn(true).when(mMockHeadsetClientService).acceptCall(mDevice, TEST_FLAGS);
        assertThat(mServiceInterface.acceptCall(mDevice, TEST_CODE)).isTrue();
    }

    @Test
    public void testRejectCall() {
        assertThat(mServiceInterface.rejectCall(mDevice)).isFalse();
        makeHeadsetClientServiceAvailable();

        doReturn(false).when(mMockHeadsetClientService).rejectCall(mDevice);
        assertThat(mServiceInterface.rejectCall(mDevice)).isFalse();
        doReturn(true).when(mMockHeadsetClientService).rejectCall(mDevice);
        assertThat(mServiceInterface.rejectCall(mDevice)).isTrue();
    }

    @Test
    public void testConnectAudio() {
        assertThat(mServiceInterface.connectAudio(mDevice)).isFalse();
        makeHeadsetClientServiceAvailable();

        doReturn(false).when(mMockHeadsetClientService).connectAudio(mDevice);
        assertThat(mServiceInterface.connectAudio(mDevice)).isFalse();
        doReturn(true).when(mMockHeadsetClientService).connectAudio(mDevice);
        assertThat(mServiceInterface.connectAudio(mDevice)).isTrue();
    }

    @Test
    public void testDisconnectAudio() {
        assertThat(mServiceInterface.disconnectAudio(mDevice)).isFalse();
        makeHeadsetClientServiceAvailable();

        doReturn(false).when(mMockHeadsetClientService).disconnectAudio(mDevice);
        assertThat(mServiceInterface.disconnectAudio(mDevice)).isFalse();
        doReturn(true).when(mMockHeadsetClientService).disconnectAudio(mDevice);
        assertThat(mServiceInterface.disconnectAudio(mDevice)).isTrue();
    }

    @Test
    public void testGetCurrentAgFeatures() {
        Set<Integer> features = Set.of(HeadsetClientHalConstants.PEER_FEAT_3WAY);
        assertThat(mServiceInterface.getCurrentAgFeatures(mDevice)).isNull();
        makeHeadsetClientServiceAvailable();

        doReturn(features).when(mMockHeadsetClientService).getCurrentAgFeatures(mDevice);
        assertThat(mServiceInterface.getCurrentAgFeatures(mDevice)).isEqualTo(features);
    }

    @Test
    public void testGetCurrentAgEvents() {
        assertThat(mServiceInterface.getCurrentAgEvents(mDevice)).isNull();
        makeHeadsetClientServiceAvailable();

        doReturn(TEST_BUNDLE).when(mMockHeadsetClientService).getCurrentAgEvents(mDevice);
        assertThat(mServiceInterface.getCurrentAgEvents(mDevice)).isEqualTo(TEST_BUNDLE);
    }

    @Test
    public void testGetConnectedDevices() {
        List<BluetoothDevice> devices = List.of(mDevice);
        assertThat(mServiceInterface.getConnectedDevices()).isNull();
        makeHeadsetClientServiceAvailable();

        doReturn(devices).when(mMockHeadsetClientService).getConnectedDevices();
        assertThat(mServiceInterface.getConnectedDevices()).isEqualTo(devices);
    }

    @Test
    public void testGetCurrentCalls() {
        assertThat(mServiceInterface.getCurrentCalls(mDevice)).isNull();
        makeHeadsetClientServiceAvailable();

        List<HfpClientCall> calls = List.of(mTestCall);
        doReturn(calls).when(mMockHeadsetClientService).getCurrentCalls(mDevice);
        assertThat(mServiceInterface.getCurrentCalls(mDevice)).isEqualTo(calls);
    }

    @Test
    public void testHasHfpClientEcc() {
        Set<Integer> features = Set.of(HeadsetClientHalConstants.PEER_FEAT_3WAY);
        assertThat(mServiceInterface.hasHfpClientEcc(mDevice)).isFalse();
        makeHeadsetClientServiceAvailable();

        doReturn(features).when(mMockHeadsetClientService).getCurrentAgFeatures(mDevice);
        assertThat(mServiceInterface.hasHfpClientEcc(mDevice)).isFalse();

        Set<Integer> featuresWithEcc = Set.of(HeadsetClientHalConstants.PEER_FEAT_ECC);
        doReturn(featuresWithEcc).when(mMockHeadsetClientService).getCurrentAgFeatures(mDevice);
        assertThat(mServiceInterface.hasHfpClientEcc(mDevice)).isTrue();
    }
}
