/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.bluetooth.opp;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.BluetoothMethodProxy;
import com.android.bluetooth.btservice.AdapterService;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

/** Test cases for {@link BluetoothOppPreference}. */
@RunWith(AndroidJUnit4.class)
public class BluetoothOppPreferenceTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AdapterService mAdapterService;

    private static final String TEST_PREF = "BluetoothOppPreferenceTest";

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();

    private final BluetoothDevice mDevice = getTestDevice(45);

    private BluetoothMethodProxy mCallProxy;
    private SharedPreferences mPrefs;
    private BluetoothOppPreference mBluetoothOppPreference;

    @Before
    public void setUp() {
        mCallProxy = spy(BluetoothMethodProxy.getInstance());
        BluetoothMethodProxy.setInstanceForTesting(mCallProxy);
        mPrefs = mContext.getSharedPreferences(TEST_PREF, Context.MODE_PRIVATE);
        doReturn(mPrefs).when(mAdapterService).getSharedPreferences(anyString(), anyInt());
        final String address = mDevice.getAddress();
        doReturn(address).when(mAdapterService).getIdentityAddress(address);

        doReturn(null)
                .when(mCallProxy)
                .contentResolverInsert(any(), eq(BluetoothShare.CONTENT_URI), any());

        mBluetoothOppPreference = BluetoothOppPreference.getInstance(mAdapterService);
    }

    @After
    public void tearDown() {
        mPrefs.edit().clear().apply();
        mContext.deleteSharedPreferences(TEST_PREF);

        BluetoothMethodProxy.setInstanceForTesting(null);
        BluetoothOppUtility.sSendFileMap.clear();
        BluetoothOppManager.setInstanceForTesting(null);
        BluetoothOppPreference.setInstance(null);
    }

    @Test
    public void dump_shouldNotThrow() {
        mBluetoothOppPreference.dump();
    }

    @Test
    public void setNameAndGetNameAndRemoveName_setsAndGetsAndRemovesNameCorrectly() {
        final var name = "randomName";
        mBluetoothOppPreference.setName(mDevice, name);
        assertThat(mBluetoothOppPreference.getName(mDevice)).isEqualTo(name);

        // Undo the change so this will not be saved on share preference
        mBluetoothOppPreference.removeName(mDevice);
        assertThat(mBluetoothOppPreference.getName(mDevice)).isNull();
    }

    @Test
    public void setChannelAndGetAndRemoveChannel_setsAndGetsAndRemovesChannelCorrectly() {
        int uuid = 1234;
        int channel = 78910;
        mBluetoothOppPreference.setChannel(mDevice, uuid, channel);
        assertThat(mBluetoothOppPreference.getChannel(mDevice, uuid)).isEqualTo(channel);

        // Undo the change so this will not be saved on share preference
        mBluetoothOppPreference.removeChannel(mDevice, uuid);
        assertThat(mBluetoothOppPreference.getChannel(mDevice, uuid)).isEqualTo(-1);
    }
}
