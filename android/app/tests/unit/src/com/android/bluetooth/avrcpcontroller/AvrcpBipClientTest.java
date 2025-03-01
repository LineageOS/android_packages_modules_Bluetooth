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

package com.android.bluetooth.avrcpcontroller;

import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.getConnectionStateName;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.bluetooth.BluetoothDevice;
import android.content.Intent;

import androidx.test.filters.SmallTest;
import androidx.test.rule.ServiceTestRule;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class AvrcpBipClientTest {
    private static final int TEST_PSM = 1;

    @Rule
    public final ServiceTestRule mBluetoothBrowserMediaServiceTestRule = new ServiceTestRule();

    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AvrcpControllerService mService;
    @Mock private AvrcpCoverArtManager.Callback mCallback;

    private final BluetoothDevice mDevice = getTestDevice(68);

    private AvrcpCoverArtManager mArtManager;
    private AvrcpBipClient mClient;

    @Before
    public void setUp() throws Exception {
        final Intent bluetoothBrowserMediaServiceStartIntent =
                TestUtils.prepareIntentToStartBluetoothBrowserMediaService();
        mBluetoothBrowserMediaServiceTestRule.startService(bluetoothBrowserMediaServiceStartIntent);

        mArtManager = new AvrcpCoverArtManager(mService, mCallback);

        mClient = new AvrcpBipClient(mDevice, TEST_PSM, mArtManager.new BipClientCallback(mDevice));
    }

    @After
    public void tearDown() {
        mArtManager.cleanup();
    }

    @Test
    public void constructor() {
        assertThat(mClient.getL2capPsm()).isEqualTo(TEST_PSM);
    }

    @Test
    public void constructor_withNullDevice() {
        assertThrows(
                NullPointerException.class,
                () ->
                        new AvrcpBipClient(
                                null, TEST_PSM, mArtManager.new BipClientCallback(mDevice)));
    }

    @Test
    public void constructor_withNullCallback() {
        assertThrows(NullPointerException.class, () -> new AvrcpBipClient(mDevice, TEST_PSM, null));
    }

    @Test
    public void setConnectionState() {
        mClient.setConnectionState(STATE_CONNECTING);

        assertThat(mClient.getState()).isEqualTo(STATE_CONNECTING);
    }

    @Test
    public void toString_returnsClientInfo() {
        String expected =
                "<AvrcpBipClient"
                        + (" device=" + mDevice)
                        + (" psm=" + TEST_PSM)
                        + (" state=" + getConnectionStateName(mClient.getState()))
                        + ">";
        assertThat(mClient.toString()).isEqualTo(expected);
    }
}
