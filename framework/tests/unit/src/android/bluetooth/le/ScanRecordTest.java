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

package android.bluetooth.le;

import static com.google.common.truth.Truth.assertThat;

import android.os.ParcelUuid;
import android.platform.test.flag.junit.SetFlagsRule;

import com.android.modules.utils.BytesMatcher;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/**
 * Test cases for {@link ScanRecord}.
 *
 * <p>To run this test, use adb shell am instrument -e class 'android.bluetooth.ScanRecordTest' -w
 * 'com.android.bluetooth.tests/android.bluetooth.BluetoothTestRunner'
 */
@RunWith(JUnit4.class)
public class ScanRecordTest {

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    /** Example raw beacons captured from a Blue Charm BC011 */
    private static final String RECORD_URL =
            "0201060303AAFE1716AAFE10EE01626C7565636861726D626561636F6E730"
                    + "009168020691E0EFE13551109426C7565436861726D5F313639363835000000";

    private static final String RECORD_UUID =
            "0201060303AAFE1716AAFE00EE626C7565636861726D3100000000000100000"
                    + "9168020691E0EFE13551109426C7565436861726D5F313639363835000000";
    private static final String RECORD_TLM =
            "0201060303AAFE1116AAFE20000BF017000008874803FB93540916802069080"
                    + "EFE13551109426C7565436861726D5F313639363835000000000000000000";
    private static final String RECORD_IBEACON =
            "0201061AFF4C000215426C7565436861726D426561636F6E730EFE1355C5091"
                    + "68020691E0EFE13551109426C7565436861726D5F31363936383500000000";

    /** Example Eddystone E2EE-EID beacon from design doc */
    private static final String RECORD_E2EE_EID =
            "0201061816AAFE400000000000000000000000000000000000000000";

    @Test
    public void testMatchesAnyField_Eddystone_Parser() {
        final List<String> found = new ArrayList<>();
        final Predicate<byte[]> matcher =
                (v) -> {
                    found.add(toHexString(v));
                    return false;
                };
        ScanRecord.parseFromBytes(hexStringToByteArray(RECORD_URL)).matchesAnyField(matcher);

        assertThat(found)
                .isEqualTo(
                        Arrays.asList(
                                "020106",
                                "0303AAFE",
                                "1716AAFE10EE01626C7565636861726D626561636F6E7300",
                                "09168020691E0EFE1355",
                                "1109426C7565436861726D5F313639363835"));
    }

    @Test
    public void testMatchesAnyField_Eddystone() {
        final BytesMatcher matcher = BytesMatcher.decode("⊆0016AAFE/00FFFFFF");
        assertMatchesAnyField(RECORD_URL, matcher);
        assertMatchesAnyField(RECORD_UUID, matcher);
        assertMatchesAnyField(RECORD_TLM, matcher);
        assertMatchesAnyField(RECORD_E2EE_EID, matcher);
        assertNotMatchesAnyField(RECORD_IBEACON, matcher);
    }

    @Test
    public void testMatchesAnyField_Eddystone_ExceptE2eeEid() {
        final BytesMatcher matcher =
                BytesMatcher.decode("⊈0016AAFE40/00FFFFFFFF,⊆0016AAFE/00FFFFFF");
        assertMatchesAnyField(RECORD_URL, matcher);
        assertMatchesAnyField(RECORD_UUID, matcher);
        assertMatchesAnyField(RECORD_TLM, matcher);
        assertNotMatchesAnyField(RECORD_E2EE_EID, matcher);
        assertNotMatchesAnyField(RECORD_IBEACON, matcher);
    }

    @Test
    public void testMatchesAnyField_iBeacon_Parser() {
        final List<String> found = new ArrayList<>();
        final Predicate<byte[]> matcher =
                (v) -> {
                    found.add(toHexString(v));
                    return false;
                };
        ScanRecord.parseFromBytes(hexStringToByteArray(RECORD_IBEACON)).matchesAnyField(matcher);

        assertThat(found)
                .isEqualTo(
                        Arrays.asList(
                                "020106",
                                "1AFF4C000215426C7565436861726D426561636F6E730EFE1355C5",
                                "09168020691E0EFE1355",
                                "1109426C7565436861726D5F313639363835"));
    }

    @Test
    public void testMatchesAnyField_iBeacon() {
        final BytesMatcher matcher = BytesMatcher.decode("⊆00FF4C0002/00FFFFFFFF");
        assertNotMatchesAnyField(RECORD_URL, matcher);
        assertNotMatchesAnyField(RECORD_UUID, matcher);
        assertNotMatchesAnyField(RECORD_TLM, matcher);
        assertNotMatchesAnyField(RECORD_E2EE_EID, matcher);
        assertMatchesAnyField(RECORD_IBEACON, matcher);
    }

    @Test
    public void testParser() {
        byte[] scanRecord =
                new byte[] {
                    0x02,
                    0x01,
                    0x1a, // advertising flags
                    0x05,
                    0x02,
                    0x0b,
                    0x11,
                    0x0a,
                    0x11, // 16 bit service uuids
                    0x04,
                    0x09,
                    0x50,
                    0x65,
                    0x64, // name
                    0x02,
                    0x0A,
                    (byte) 0xec, // tx power level
                    0x05,
                    0x16,
                    0x0b,
                    0x11,
                    0x50,
                    0x64, // service data
                    0x05,
                    (byte) 0xff,
                    (byte) 0xe0,
                    0x00,
                    0x02,
                    0x15, // manufacturer specific data
                    0x03,
                    0x50,
                    0x01,
                    0x02, // an unknown data type won't cause trouble
                };
        ScanRecord data = ScanRecord.parseFromBytes(scanRecord);

        assertThat(data.getAdvertiseFlags()).isEqualTo(0x1a);

        ParcelUuid uuid1 = ParcelUuid.fromString("0000110A-0000-1000-8000-00805F9B34FB");
        ParcelUuid uuid2 = ParcelUuid.fromString("0000110B-0000-1000-8000-00805F9B34FB");
        assertThat(data.getServiceUuids()).isNotNull();
        assertThat(data.getServiceUuids().contains(uuid1)).isTrue();
        assertThat(data.getServiceUuids().contains(uuid2)).isTrue();

        assertThat(data.getDeviceName()).isEqualTo("Ped");
        assertThat(data.getTxPowerLevel()).isEqualTo(-20);

        assertThat(data.getManufacturerSpecificData().get(0x00E0)).isNotNull();
        assertThat(data.getManufacturerSpecificData().get(0x00E0))
                .isEqualTo(new byte[] {0x02, 0x15});

        assertThat(data.getServiceData().containsKey(uuid2)).isTrue();
        assertThat(data.getServiceData().get(uuid2)).isEqualTo(new byte[] {0x50, 0x64});
    }

    @Test
    public void testParserMultipleManufacturerSpecificData() {
        byte[] scanRecord =
                new byte[] {
                    0x02,
                    0x01,
                    0x1a, // advertising flags
                    0x05,
                    0x02,
                    0x0b,
                    0x11,
                    0x0a,
                    0x11, // 16 bit service uuids
                    0x04,
                    0x09,
                    0x50,
                    0x65,
                    0x64, // name
                    0x02,
                    0x0A,
                    (byte) 0xec, // tx power level
                    0x05,
                    0x16,
                    0x0b,
                    0x11,
                    0x50,
                    0x64, // service data
                    0x05,
                    (byte) 0xff,
                    (byte) 0xe0,
                    0x00,
                    0x02,
                    0x15, // manufacturer specific data #1
                    0x05,
                    (byte) 0xff,
                    (byte) 0xe0,
                    0x00,
                    0x04,
                    0x16, // manufacturer specific data #2
                    0x03,
                    0x50,
                    0x01,
                    0x02, // an unknown data type won't cause trouble
                };

        ScanRecord data = ScanRecord.parseFromBytes(scanRecord);

        assertThat(data.getAdvertiseFlags()).isEqualTo(0x1a);

        ParcelUuid uuid1 = ParcelUuid.fromString("0000110A-0000-1000-8000-00805F9B34FB");
        ParcelUuid uuid2 = ParcelUuid.fromString("0000110B-0000-1000-8000-00805F9B34FB");
        assertThat(data.getServiceUuids()).isNotNull();
        assertThat(data.getServiceUuids().contains(uuid1)).isTrue();
        assertThat(data.getServiceUuids().contains(uuid2)).isTrue();

        assertThat(data.getDeviceName()).isEqualTo("Ped");
        assertThat(data.getTxPowerLevel()).isEqualTo(-20);

        assertThat(data.getManufacturerSpecificData().get(0x00E0)).isNotNull();
        assertThat(data.getManufacturerSpecificData().get(0x00E0))
                .isEqualTo(new byte[] {0x02, 0x15, 0x04, 0x16});

        assertThat(data.getServiceData().containsKey(uuid2)).isTrue();
        assertThat(data.getServiceData().get(uuid2)).isEqualTo(new byte[] {0x50, 0x64});
    }

    private static void assertMatchesAnyField(String record, BytesMatcher matcher) {
        assertThat(ScanRecord.parseFromBytes(hexStringToByteArray(record)).matchesAnyField(matcher))
                .isTrue();
    }

    private static void assertNotMatchesAnyField(String record, BytesMatcher matcher) {
        assertThat(ScanRecord.parseFromBytes(hexStringToByteArray(record)).matchesAnyField(matcher))
                .isFalse();
    }

    private static final char[] HEX_DIGITS = {
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'
    };

    private static String toHexString(byte[] array) {
        char[] buf = new char[array.length * 2];

        int bufIndex = 0;
        for (int i = 0; i < array.length; i++) {
            byte b = array[i];
            buf[bufIndex++] = HEX_DIGITS[(b >>> 4) & 0x0F];
            buf[bufIndex++] = HEX_DIGITS[b & 0x0F];
        }

        return new String(buf);
    }

    private static int toByte(char c) {
        if (c >= '0' && c <= '9') return (c - '0');
        if (c >= 'A' && c <= 'F') return (c - 'A' + 10);
        if (c >= 'a' && c <= 'f') return (c - 'a' + 10);

        throw new RuntimeException("Invalid hex char '" + c + "'");
    }

    private static byte[] hexStringToByteArray(String hexString) {
        int length = hexString.length();
        byte[] buffer = new byte[length / 2];

        for (int i = 0; i < length; i += 2) {
            buffer[i / 2] =
                    (byte) ((toByte(hexString.charAt(i)) << 4) | toByte(hexString.charAt(i + 1)));
        }

        return buffer;
    }
}
