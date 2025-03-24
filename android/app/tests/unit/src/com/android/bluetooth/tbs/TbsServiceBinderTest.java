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

package com.android.bluetooth.tbs;

import static com.android.bluetooth.TestUtils.MockitoRule;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothLeCall;
import android.bluetooth.IBluetoothLeCallControlCallback;
import android.content.AttributionSource;
import android.os.ParcelUuid;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Test cases for {@link TbsServiceBinder}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class TbsServiceBinderTest {

    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private TbsService mService;

    private AttributionSource mAttributionSource;
    private TbsServiceBinder mBinder;

    @Before
    public void setUp() throws Exception {
        when(mService.isAvailable()).thenReturn(true);
        mBinder = new TbsServiceBinder(mService);
        mAttributionSource = new AttributionSource.Builder(1).build();
    }

    @Test
    public void registerBearer() {
        String token = "token";
        IBluetoothLeCallControlCallback callback = mock(IBluetoothLeCallControlCallback.class);
        String uci = "uci";
        List<String> uriSchemes = new ArrayList<>();
        int capabilities = 1;
        String providerName = "providerName";
        int technology = 2;

        mBinder.registerBearer(
                token,
                callback,
                uci,
                uriSchemes,
                capabilities,
                providerName,
                technology,
                mAttributionSource);
        verify(mService)
                .registerBearer(
                        token, callback, uci, uriSchemes, capabilities, providerName, technology);
    }

    @Test
    public void unregisterBearer() {
        String token = "token";

        mBinder.unregisterBearer(token, mAttributionSource);
        verify(mService).unregisterBearer(token);
    }

    @Test
    public void requestResult() {
        int ccid = 1;
        int requestId = 2;
        int result = 3;

        mBinder.requestResult(ccid, requestId, result, mAttributionSource);
        verify(mService).requestResult(ccid, requestId, result);
    }

    @Test
    public void callAdded() {
        int ccid = 1;
        BluetoothLeCall call = mock(BluetoothLeCall.class);

        mBinder.callAdded(ccid, call, mAttributionSource);
        verify(mService).callAdded(ccid, call);
    }

    @Test
    public void callRemoved() {
        int ccid = 1;
        UUID callId = UUID.randomUUID();
        int reason = 2;

        mBinder.callRemoved(ccid, new ParcelUuid(callId), reason, mAttributionSource);
        verify(mService).callRemoved(ccid, callId, reason);
    }

    @Test
    public void callStateChanged() {
        int ccid = 1;
        UUID callId = UUID.randomUUID();
        int state = 2;

        mBinder.callStateChanged(ccid, new ParcelUuid(callId), state, mAttributionSource);
        verify(mService).callStateChanged(ccid, callId, state);
    }

    @Test
    public void currentCallsList() {
        int ccid = 1;
        List<BluetoothLeCall> calls = new ArrayList<>();

        mBinder.currentCallsList(ccid, calls, mAttributionSource);
        verify(mService).currentCallsList(ccid, calls);
    }

    @Test
    public void cleanup_doesNotCrash() {
        mBinder.cleanup();
    }
}
