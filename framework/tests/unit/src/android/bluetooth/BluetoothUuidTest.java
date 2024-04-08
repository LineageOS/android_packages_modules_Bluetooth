/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.bluetooth;

import static com.google.common.truth.Truth.assertThat;

import android.os.ParcelUuid;

import com.google.common.truth.Expect;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit test cases for {@link BluetoothUuid}. */
@RunWith(JUnit4.class)
public class BluetoothUuidTest {

    @Rule public Expect expect = Expect.create();

    @Test
    public void testUuid16Parser() {
        byte[] uuid16 = new byte[] {0x0B, 0x11};
        assertThat(BluetoothUuid.parseUuidFrom(uuid16))
                .isEqualTo(ParcelUuid.fromString("0000110B-0000-1000-8000-00805F9B34FB"));
    }

    @Test
    public void testUuid32Parser() {
        byte[] uuid32 = new byte[] {0x0B, 0x11, 0x33, (byte) 0xFE};
        assertThat(BluetoothUuid.parseUuidFrom(uuid32))
                .isEqualTo(ParcelUuid.fromString("FE33110B-0000-1000-8000-00805F9B34FB"));
    }

    @Test
    public void testUuid128Parser() {
        byte[] uuid128 =
                new byte[] {
                    0x01,
                    0x02,
                    0x03,
                    0x04,
                    0x05,
                    0x06,
                    0x07,
                    0x08,
                    0x09,
                    0x0A,
                    0x0B,
                    0x0C,
                    0x0D,
                    0x0E,
                    0x0F,
                    (byte) 0xFF
                };
        assertThat(BluetoothUuid.parseUuidFrom(uuid128))
                .isEqualTo(ParcelUuid.fromString("FF0F0E0D-0C0B-0A09-0807-060504030201"));
    }

    @Test
    public void testUuidType() {
        expect.that(
                        BluetoothUuid.is16BitUuid(
                                ParcelUuid.fromString("0000110B-0000-1000-8000-00805F9B34FB")))
                .isTrue();
        expect.that(
                        BluetoothUuid.is32BitUuid(
                                ParcelUuid.fromString("0000110B-0000-1000-8000-00805F9B34FB")))
                .isFalse();
        expect.that(
                        BluetoothUuid.is16BitUuid(
                                ParcelUuid.fromString("FE33110B-0000-1000-8000-00805F9B34FB")))
                .isFalse();
        expect.that(
                        BluetoothUuid.is32BitUuid(
                                ParcelUuid.fromString("FE33110B-0000-1000-8000-00805F9B34FB")))
                .isTrue();
        expect.that(
                        BluetoothUuid.is32BitUuid(
                                ParcelUuid.fromString("FE33110B-1000-1000-8000-00805F9B34FB")))
                .isFalse();
    }
}
