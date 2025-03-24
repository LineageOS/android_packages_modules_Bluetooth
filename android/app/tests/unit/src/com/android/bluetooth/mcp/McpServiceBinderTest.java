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

package com.android.bluetooth.mcp;

import static com.android.bluetooth.TestUtils.MockitoRule;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothDevice;
import android.content.AttributionSource;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

/** Test cases for {@link McpServiceBinder}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class McpServiceBinderTest {

    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private McpService mService;

    private AttributionSource mAttributionSource;
    private McpServiceBinder mBinder;

    @Before
    public void setUp() throws Exception {
        when(mService.isAvailable()).thenReturn(true);
        mBinder = new McpServiceBinder(mService);
        mAttributionSource = new AttributionSource.Builder(1).build();
    }

    @Test
    public void setDeviceAuthorized() {
        BluetoothDevice device = mock(BluetoothDevice.class);
        boolean isAuthorized = true;

        mBinder.setDeviceAuthorized(device, isAuthorized, mAttributionSource);
        verify(mService).setDeviceAuthorized(device, isAuthorized);
    }

    @Test
    public void cleanup_doesNotCrash() {
        mBinder.cleanup();
    }
}
