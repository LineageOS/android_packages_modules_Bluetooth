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

package com.android.bluetooth.opp;

import static com.google.common.truth.Truth.assertThat;

import android.net.Uri;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class BluetoothOppShareInfoTest {
    private BluetoothOppShareInfo mBluetoothOppShareInfo;

    @Before
    public void setUp() throws Exception {
        mBluetoothOppShareInfo =
                new BluetoothOppShareInfo(
                        0,
                        Uri.parse("file://Idontknow//Justmadeitup"),
                        "this is a object that take 4 bytes",
                        "random.jpg",
                        "image/jpeg",
                        BluetoothShare.DIRECTION_INBOUND,
                        "01:23:45:67:89:AB",
                        BluetoothShare.VISIBILITY_VISIBLE,
                        BluetoothShare.USER_CONFIRMATION_CONFIRMED,
                        BluetoothShare.STATUS_PENDING,
                        1023,
                        42,
                        123456789,
                        false);
    }

    @Test
    public void testConstructor() {
        assertThat(mBluetoothOppShareInfo.mUri)
                .isEqualTo(Uri.parse("file://Idontknow//Justmadeitup"));
        assertThat(mBluetoothOppShareInfo.mFilename).isEqualTo("random.jpg");
        assertThat(mBluetoothOppShareInfo.mMimetype).isEqualTo("image/jpeg");
        assertThat(mBluetoothOppShareInfo.mDirection).isEqualTo(BluetoothShare.DIRECTION_INBOUND);
        assertThat(mBluetoothOppShareInfo.mDestination).isEqualTo("01:23:45:67:89:AB");
        assertThat(mBluetoothOppShareInfo.mVisibility).isEqualTo(BluetoothShare.VISIBILITY_VISIBLE);
        assertThat(mBluetoothOppShareInfo.mConfirm)
                .isEqualTo(BluetoothShare.USER_CONFIRMATION_CONFIRMED);
        assertThat(mBluetoothOppShareInfo.mStatus).isEqualTo(BluetoothShare.STATUS_PENDING);
        assertThat(mBluetoothOppShareInfo.mTotalBytes).isEqualTo(1023);
        assertThat(mBluetoothOppShareInfo.mCurrentBytes).isEqualTo(42);
        assertThat(mBluetoothOppShareInfo.mTimestamp).isEqualTo(123456789);
        assertThat(mBluetoothOppShareInfo.mMediaScanned).isEqualTo(false);
    }

    @Test
    public void testReadyToStart() {
        assertThat(mBluetoothOppShareInfo.isReadyToStart()).isTrue();

        mBluetoothOppShareInfo.mDirection = BluetoothShare.DIRECTION_OUTBOUND;
        assertThat(mBluetoothOppShareInfo.isReadyToStart()).isTrue();

        mBluetoothOppShareInfo.mStatus = BluetoothShare.STATUS_RUNNING;
        assertThat(mBluetoothOppShareInfo.isReadyToStart()).isFalse();
    }

    @Test
    public void testHasCompletionNotification() {
        assertThat(mBluetoothOppShareInfo.hasCompletionNotification()).isFalse();

        mBluetoothOppShareInfo.mStatus = BluetoothShare.STATUS_CANCELED;
        assertThat(mBluetoothOppShareInfo.hasCompletionNotification()).isTrue();

        mBluetoothOppShareInfo.mVisibility = BluetoothShare.VISIBILITY_HIDDEN;
        assertThat(mBluetoothOppShareInfo.hasCompletionNotification()).isFalse();
    }

    @Test
    public void testIsObsolete() {
        assertThat(mBluetoothOppShareInfo.isObsolete()).isFalse();
        mBluetoothOppShareInfo.mStatus = BluetoothShare.STATUS_RUNNING;
        assertThat(mBluetoothOppShareInfo.isObsolete()).isTrue();
    }
}
