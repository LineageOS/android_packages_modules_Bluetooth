/*
 * Copyright 2021 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
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

package com.android.bluetooth.mcp;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothDevice;
import android.content.Context;

import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class McpServiceTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private MediaControlProfile mMediaControlProfile;
    @Mock private Context mContext;

    private McpService mMcpService;

    @Before
    public void setUp() throws Exception {
        mMcpService = new McpService(mContext, mMediaControlProfile);
        mMcpService.setAvailable(true);
    }

    @After
    public void tearDown() {
        mMcpService.cleanup();
        assertThat(McpService.getMcpService()).isNull();
    }

    @Test
    public void testGetService() {
        McpService mMcpServiceDuplicate = McpService.getMcpService();
        assertThat(mMcpServiceDuplicate).isNotNull();
        assertThat(mMcpServiceDuplicate).isSameInstanceAs(mMcpService);
    }

    @Test
    public void testAuthorization() {
        BluetoothDevice device0 = getTestDevice(0);
        BluetoothDevice device1 = getTestDevice(1);

        mMcpService.setDeviceAuthorized(device0, true);
        verify(mMediaControlProfile).onDeviceAuthorizationSet(eq(device0));
        assertThat(mMcpService.getDeviceAuthorization(device0))
                .isEqualTo(BluetoothDevice.ACCESS_ALLOWED);

        mMcpService.setDeviceAuthorized(device1, false);
        verify(mMediaControlProfile).onDeviceAuthorizationSet(eq(device1));
        assertThat(mMcpService.getDeviceAuthorization(device1))
                .isEqualTo(BluetoothDevice.ACCESS_REJECTED);
    }

    @Test
    public void testDumpDoesNotCrash() {
        mMcpService.dump(new StringBuilder());
    }
}
