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

package com.android.bluetooth.opp

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Test cases for [BluetoothOppShareInfo]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class BluetoothOppShareInfoTest {

    private lateinit var bluetoothOppShareInfo: BluetoothOppShareInfo

    @Before
    fun setUp() {
        bluetoothOppShareInfo =
            BluetoothOppShareInfo(
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
                false,
            )
    }

    @Test
    fun testConstructor() {
        assertThat(bluetoothOppShareInfo.mUri)
            .isEqualTo(Uri.parse("file://Idontknow//Justmadeitup"))
        assertThat(bluetoothOppShareInfo.mFilename).isEqualTo("random.jpg")
        assertThat(bluetoothOppShareInfo.mMimetype).isEqualTo("image/jpeg")
        assertThat(bluetoothOppShareInfo.mDirection).isEqualTo(BluetoothShare.DIRECTION_INBOUND)
        assertThat(bluetoothOppShareInfo.mDestination).isEqualTo("01:23:45:67:89:AB")
        assertThat(bluetoothOppShareInfo.mVisibility).isEqualTo(BluetoothShare.VISIBILITY_VISIBLE)
        assertThat(bluetoothOppShareInfo.mConfirm)
            .isEqualTo(BluetoothShare.USER_CONFIRMATION_CONFIRMED)
        assertThat(bluetoothOppShareInfo.mStatus).isEqualTo(BluetoothShare.STATUS_PENDING)
        assertThat(bluetoothOppShareInfo.mTotalBytes).isEqualTo(1023)
        assertThat(bluetoothOppShareInfo.mCurrentBytes).isEqualTo(42)
        assertThat(bluetoothOppShareInfo.mTimestamp).isEqualTo(123456789)
        assertThat(bluetoothOppShareInfo.mMediaScanned).isFalse()
    }

    @Test
    fun testReadyToStart() {
        assertThat(bluetoothOppShareInfo.isReadyToStart).isTrue()

        bluetoothOppShareInfo.mDirection = BluetoothShare.DIRECTION_OUTBOUND
        assertThat(bluetoothOppShareInfo.isReadyToStart).isTrue()

        bluetoothOppShareInfo.mStatus = BluetoothShare.STATUS_RUNNING
        assertThat(bluetoothOppShareInfo.isReadyToStart).isFalse()
    }

    @Test
    fun testHasCompletionNotification() {
        assertThat(bluetoothOppShareInfo.hasCompletionNotification()).isFalse()

        bluetoothOppShareInfo.mStatus = BluetoothShare.STATUS_CANCELED
        assertThat(bluetoothOppShareInfo.hasCompletionNotification()).isTrue()

        bluetoothOppShareInfo.mVisibility = BluetoothShare.VISIBILITY_HIDDEN
        assertThat(bluetoothOppShareInfo.hasCompletionNotification()).isFalse()
    }

    @Test
    fun testIsObsolete() {
        assertThat(bluetoothOppShareInfo.isObsolete).isFalse()
        bluetoothOppShareInfo.mStatus = BluetoothShare.STATUS_RUNNING
        assertThat(bluetoothOppShareInfo.isObsolete).isTrue()
    }
}
