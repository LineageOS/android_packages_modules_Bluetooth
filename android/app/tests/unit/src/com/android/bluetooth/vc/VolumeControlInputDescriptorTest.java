/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.bluetooth.vc;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.*;

import android.bluetooth.AudioInputControl.AudioInputStatus;
import android.bluetooth.AudioInputControl.AudioInputType;
import android.bluetooth.AudioInputControl.GainMode;
import android.bluetooth.AudioInputControl.Mute;
import android.bluetooth.BluetoothDevice;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

/** Test cases for {@link VolumeControlInputDescriptor}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class VolumeControlInputDescriptorTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Mock private VolumeControlNativeInterface mNativeInterface;

    private static final int NUMBER_OF_INPUT = 3;
    private static final int NUMBER_OF_FIELD_IN_STRUCT = 9;
    private static final int VALID_ID = 1;
    private static final int INVALID_ID = NUMBER_OF_INPUT;
    private static final int INVALID_ID2 = -1;

    private final BluetoothDevice mDevice = getTestDevice(0x42);

    private VolumeControlInputDescriptor mDescriptor;

    @Before
    public void setUp() {
        mDescriptor = new VolumeControlInputDescriptor(mNativeInterface, mDevice, NUMBER_OF_INPUT);
    }

    @Test
    public void dump_noCrash() {
        StringBuilder sb = new StringBuilder();

        mDescriptor.dump(sb);
        int nLines = NUMBER_OF_INPUT * (NUMBER_OF_FIELD_IN_STRUCT + 1); // +1 for the id
        assertThat(sb.toString()).containsMatch("(        .*\n){" + nLines + "}");
    }

    @Test
    public void setFoo_withAllValidId_valuesAreUpdated() {
        for (int i = 0; i < NUMBER_OF_INPUT; i++) {
            assertThat(mDescriptor.getStatus(i))
                    .isEqualTo(bluetooth.constants.aics.AudioInputStatus.INACTIVE);
            mDescriptor.onStatusChanged(i, bluetooth.constants.aics.AudioInputStatus.ACTIVE);
            assertThat(mDescriptor.getStatus(i))
                    .isEqualTo(bluetooth.constants.aics.AudioInputStatus.ACTIVE);
        }
    }

    @Test
    public void getStatus_whenNeverSet_defaultToInactive() {
        assertThat(mDescriptor.getStatus(VALID_ID))
                .isEqualTo(bluetooth.constants.aics.AudioInputStatus.INACTIVE);
    }

    @Test
    public void setStatus_withValidId_valueIsUpdated() {
        @AudioInputStatus int status = bluetooth.constants.aics.AudioInputStatus.ACTIVE;
        mDescriptor.onStatusChanged(VALID_ID, status);

        assertThat(mDescriptor.getStatus(VALID_ID)).isEqualTo(status);
    }

    @Test
    public void setStatus_withInvalidId_valueIsNotUpdated() {
        @AudioInputStatus int status = bluetooth.constants.aics.AudioInputStatus.ACTIVE;
        mDescriptor.onStatusChanged(INVALID_ID, status);

        assertThat(mDescriptor.getStatus(INVALID_ID)).isNotEqualTo(status);
    }

    @Test
    public void getType_whenNeverSet_defaultToUnspecified() {
        assertThat(mDescriptor.getType(VALID_ID))
                .isEqualTo(bluetooth.constants.AudioInputType.UNSPECIFIED);
    }

    @Test
    public void setType_withValidId_valueIsUpdated() {
        @AudioInputType int type = bluetooth.constants.AudioInputType.AMBIENT;
        mDescriptor.setType(VALID_ID, type);

        assertThat(mDescriptor.getType(VALID_ID)).isEqualTo(type);
    }

    @Test
    public void setType_withInvalidId_valueIsNotUpdated() {
        @AudioInputType int type = bluetooth.constants.AudioInputType.BLUETOOTH;
        mDescriptor.setType(INVALID_ID2, type);

        assertThat(mDescriptor.getType(INVALID_ID2)).isNotEqualTo(type);
    }

    @Test
    public void setState_withValidIdButIncorrectSettings_valueIsNotUpdated() {
        mDescriptor.onStateChanged(
                VALID_ID,
                34,
                bluetooth.constants.aics.Mute.NOT_MUTED,
                bluetooth.constants.aics.GainMode.MANUAL);

        assertThat(mDescriptor.getGainSetting(VALID_ID)).isEqualTo(0);
        assertThat(mDescriptor.getGainMode(VALID_ID))
                .isEqualTo(bluetooth.constants.aics.GainMode.MANUAL_ONLY);
        assertThat(mDescriptor.getMute(VALID_ID)).isEqualTo(bluetooth.constants.aics.Mute.DISABLED);
    }

    @Test
    public void setState_withValidIdAndCorrectSettings_valueIsUpdated() {
        int max = 100;
        int min = 0;
        int unit = 1;
        mDescriptor.onGainSettingsPropertiesChanged(VALID_ID, unit, min, max);

        int gainSetting = 42;
        @Mute int mute = bluetooth.constants.aics.Mute.MUTED;
        @GainMode int gainMode = bluetooth.constants.aics.GainMode.MANUAL;
        mDescriptor.onStateChanged(VALID_ID, gainSetting, mute, gainMode);

        assertThat(mDescriptor.getGainSetting(VALID_ID)).isEqualTo(gainSetting);
        assertThat(mDescriptor.getGainMode(VALID_ID)).isEqualTo(gainMode);
        assertThat(mDescriptor.getMute(VALID_ID)).isEqualTo(mute);
    }

    @Test
    public void setState_withInvalidId_valueIsNotUpdated() {
        int max = 100;
        int min = 0;
        int unit = 1;
        // Should be no-op but we want to copy the working case test, just with an invalid id
        mDescriptor.onGainSettingsPropertiesChanged(INVALID_ID, unit, min, max);

        mDescriptor.onStateChanged(
                INVALID_ID,
                35,
                bluetooth.constants.aics.Mute.MUTED,
                bluetooth.constants.aics.GainMode.MANUAL);

        assertThat(mDescriptor.getGainSetting(INVALID_ID)).isEqualTo(0);
        assertThat(mDescriptor.getGainMode(INVALID_ID))
                .isEqualTo(bluetooth.constants.aics.GainMode.AUTOMATIC_ONLY);
        assertThat(mDescriptor.getMute(INVALID_ID))
                .isEqualTo(bluetooth.constants.aics.Mute.DISABLED);
    }

    @Test
    public void getDescription_whenNeverSet_defaultToEmptyString() {
        assertThat(mDescriptor.getDescription(VALID_ID)).isEmpty();
    }

    @Test
    public void setDescription_withValidId_valueIsUpdated() {
        String newDescription = "what a nice description";
        mDescriptor.onDescriptionChanged(VALID_ID, newDescription, false);

        assertThat(mDescriptor.getDescription(VALID_ID)).isEqualTo(newDescription);
    }

    @Test
    public void setDescription_withInvalidId_valueIsNotUpdated() {
        String newDescription = "what a nice description";
        mDescriptor.onDescriptionChanged(INVALID_ID, newDescription, true);

        assertThat(mDescriptor.getDescription(INVALID_ID)).isNotEqualTo(newDescription);
    }
}
