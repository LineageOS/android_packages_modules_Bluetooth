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

package com.android.bluetooth.btservice;

import static com.android.bluetooth.TestUtils.getTestDevice;
import static com.google.common.truth.Truth.assertThat;

import android.bluetooth.BluetoothDevice;
import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Test cases for {@link AutoConnectMode}. */
@MediumTest
@RunWith(AndroidJUnit4.class)
public class AutoConnectModeTest {

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();
    private final BluetoothDevice mDevice = getTestDevice(0);

    @Test
    public void getMode_default_isManualOnly() {
        // Nothing stored yet: the default must be MANUAL_ONLY.
        assertThat(AutoConnectMode.getMode(mContext, mDevice))
                .isEqualTo(AutoConnectMode.MANUAL_ONLY);
    }

    @Test
    public void setModeAndGetMode_roundTrip_allModes() {
        for (int mode = AutoConnectMode.MANUAL_ONLY; mode <= AutoConnectMode.MAX_MODE; mode++) {
            AutoConnectMode.setMode(mContext, mDevice, mode);
            assertThat(AutoConnectMode.getMode(mContext, mDevice)).isEqualTo(mode);
        }
    }

    @Test
    public void getMode_outOfRangeValue_isManualOnly() {
        // setMode() stores the value as-is; getMode() must clamp out-of-range values.
        AutoConnectMode.setMode(mContext, mDevice, AutoConnectMode.MANUAL_ONLY - 1);
        assertThat(AutoConnectMode.getMode(mContext, mDevice))
                .isEqualTo(AutoConnectMode.MANUAL_ONLY);

        AutoConnectMode.setMode(mContext, mDevice, AutoConnectMode.MAX_MODE + 1);
        assertThat(AutoConnectMode.getMode(mContext, mDevice))
                .isEqualTo(AutoConnectMode.MANUAL_ONLY);
    }
}
