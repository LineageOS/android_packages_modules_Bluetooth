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

package com.android.bluetooth.le_scan;

import static com.google.common.truth.Truth.assertThat;

import android.bluetooth.BluetoothUuid;
import android.bluetooth.le.ScanFilter;
import android.os.ParcelUuid;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.google.common.primitives.Bytes;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.UUID;

/** Test cases for {@link ScanFilterQueue}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class ScanFilterQueueTest {
    private static final String TEST_UUID_STRING = "00001805-0000-1000-8000-00805f9b34fb";
    private static final String UNMATCHED_UUID_STRING = "00001815-0000-1000-8000-00805f9b34fb";
    private static final byte[] TEST_SERVICE_DATA = new byte[] {(byte) 0x18, (byte) 0x0F};
    private static final byte[] PARTIALLY_MATCHED_SERVICE_DATA =
            new byte[] {(byte) 0x08, (byte) 0x0F, (byte) 0xAB, (byte) 0xCD};
    private static final byte[] UNMATCHED_SERVICE_DATA = new byte[] {(byte) 0x08, (byte) 0x0E};
    private static final byte[] PARTIAL_SERVICE_DATA_MASK = new byte[] {(byte) 0x00, (byte) 0xFF};
    private static final byte[] FULL_SERVICE_DATA_MASK = new byte[] {(byte) 0xFF, (byte) 0xFF};

    @Test
    public void scanFilterQueueParams() {
        ScanFilterQueue queue = new ScanFilterQueue();

        String address = "address";
        byte type = 1;
        byte[] irk = new byte[] {0x02};
        queue.addDeviceAddress(address, type, irk);

        UUID uuid = UUID.randomUUID();
        queue.addUuid(uuid);

        UUID uuidMask = UUID.randomUUID();
        queue.addUuid(uuid, uuidMask);

        UUID solicitUuid = UUID.randomUUID();
        UUID solicitUuidMask = UUID.randomUUID();
        queue.addSolicitUuid(solicitUuid, solicitUuidMask);

        String name = "name";
        queue.addName(name);

        int company = 2;
        byte[] data = new byte[] {0x04};
        queue.addManufacturerData(company, data);

        int companyMask = 2;
        byte[] dataMask = new byte[] {0x05};
        queue.addManufacturerData(company, companyMask, data, dataMask);

        byte[] serviceData = new byte[] {0x06};
        byte[] serviceDataMask = new byte[] {0x08};
        queue.addServiceData(serviceData, serviceDataMask);

        int adType = 3;
        byte[] adData = new byte[] {0x10};
        byte[] adDataMask = new byte[] {0x12};
        queue.addAdvertisingDataType(adType, adData, adDataMask);

        ScanFilterQueue.Entry[] entries = queue.toArray();
        int entriesLength = 9;
        assertThat(entries.length).isEqualTo(entriesLength);

        for (ScanFilterQueue.Entry entry : entries) {
            switch (entry.type) {
                case ScanFilterQueue.TYPE_DEVICE_ADDRESS:
                    assertThat(entry.address).isEqualTo(address);
                    assertThat(entry.addr_type).isEqualTo(type);
                    assertThat(entry.irk).isEqualTo(irk);
                    break;
                case ScanFilterQueue.TYPE_SERVICE_DATA_CHANGED:
                    assertThat(entry).isNotNull();
                    break;
                case ScanFilterQueue.TYPE_SERVICE_UUID:
                    assertThat(entry.uuid).isEqualTo(uuid);
                    break;
                case ScanFilterQueue.TYPE_SOLICIT_UUID:
                    assertThat(entry.uuid).isEqualTo(solicitUuid);
                    assertThat(entry.uuid_mask).isEqualTo(solicitUuidMask);
                    break;
                case ScanFilterQueue.TYPE_LOCAL_NAME:
                    assertThat(entry.name).isEqualTo(name);
                    break;
                case ScanFilterQueue.TYPE_MANUFACTURER_DATA:
                    assertThat(entry.company).isEqualTo(company);
                    assertThat(entry.data).isEqualTo(data);
                    break;
                case ScanFilterQueue.TYPE_SERVICE_DATA:
                    assertThat(entry.data).isEqualTo(serviceData);
                    assertThat(entry.data_mask).isEqualTo(serviceDataMask);
                    break;
                case ScanFilterQueue.TYPE_ADVERTISING_DATA_TYPE:
                    assertThat(entry.ad_type).isEqualTo(adType);
                    assertThat(entry.data).isEqualTo(adData);
                    assertThat(entry.data_mask).isEqualTo(adDataMask);
                    break;
            }
        }
    }

    @Test
    public void popEmpty() {
        ScanFilterQueue queue = new ScanFilterQueue();

        ScanFilterQueue.Entry entry = queue.pop();
        assertThat(entry).isNull();
    }

    @Test
    public void popFromQueue() {
        ScanFilterQueue queue = new ScanFilterQueue();

        byte[] serviceData = new byte[] {0x02};
        byte[] serviceDataMask = new byte[] {0x04};
        queue.addServiceData(serviceData, serviceDataMask);

        ScanFilterQueue.Entry entry = queue.pop();
        assertThat(entry.data).isEqualTo(serviceData);
        assertThat(entry.data_mask).isEqualTo(serviceDataMask);
    }

    @Test
    public void checkFeatureSelection() {
        ScanFilterQueue queue = new ScanFilterQueue();

        byte[] serviceData = new byte[] {0x02};
        byte[] serviceDataMask = new byte[] {0x04};
        queue.addServiceData(serviceData, serviceDataMask);

        int feature = 1 << ScanFilterQueue.TYPE_SERVICE_DATA;
        assertThat(queue.getFeatureSelection()).isEqualTo(feature);
    }

    @Test
    public void convertQueueToArray() {
        ScanFilterQueue queue = new ScanFilterQueue();

        byte[] serviceData = new byte[] {0x02};
        byte[] serviceDataMask = new byte[] {0x04};
        queue.addServiceData(serviceData, serviceDataMask);

        ScanFilterQueue.Entry[] entries = queue.toArray();
        int entriesLength = 1;
        assertThat(entries.length).isEqualTo(entriesLength);

        ScanFilterQueue.Entry entry = entries[0];
        assertThat(entry.data).isEqualTo(serviceData);
        assertThat(entry.data_mask).isEqualTo(serviceDataMask);
    }

    @Test
    public void queueAddScanFilter() {
        ScanFilterQueue queue = new ScanFilterQueue();

        String name = "name";
        String deviceAddress = "00:11:22:33:FF:EE";
        ParcelUuid serviceUuid = ParcelUuid.fromString(UUID.randomUUID().toString());
        ParcelUuid serviceSolicitationUuid = ParcelUuid.fromString(UUID.randomUUID().toString());
        int manufacturerId = 0;
        byte[] manufacturerData = new byte[0];
        ParcelUuid serviceDataUuid = ParcelUuid.fromString(UUID.randomUUID().toString());
        byte[] serviceData = new byte[0];
        int advertisingDataType = 1;

        ScanFilter filter =
                new ScanFilter.Builder()
                        .setDeviceName(name)
                        .setDeviceAddress(deviceAddress)
                        .setServiceUuid(serviceUuid)
                        .setServiceSolicitationUuid(serviceSolicitationUuid)
                        .setManufacturerData(manufacturerId, manufacturerData)
                        .setServiceData(serviceDataUuid, serviceData)
                        .setAdvertisingDataType(advertisingDataType)
                        .build();
        queue.addScanFilter(filter);

        int numOfEntries = 7;
        assertThat(queue.toArray().length).isEqualTo(numOfEntries);
    }

    @Test
    public void serviceDataFilterNoMask1() {
        ScanFilter filter =
                new ScanFilter.Builder()
                        .setServiceData(ParcelUuid.fromString(TEST_UUID_STRING), TEST_SERVICE_DATA)
                        .build();
        testServiceDataFilter(filter, false);
    }

    @Test
    public void serviceDataFilterWithFullMask() {
        ScanFilter filter =
                new ScanFilter.Builder()
                        .setServiceData(
                                ParcelUuid.fromString(TEST_UUID_STRING),
                                TEST_SERVICE_DATA,
                                FULL_SERVICE_DATA_MASK)
                        .build();
        testServiceDataFilter(filter, false);
    }

    @Test
    public void serviceDataFilterWithPartialMask() {
        ScanFilter filter =
                new ScanFilter.Builder()
                        .setServiceData(
                                ParcelUuid.fromString(TEST_UUID_STRING),
                                TEST_SERVICE_DATA,
                                PARTIAL_SERVICE_DATA_MASK)
                        .build();
        testServiceDataFilter(filter, true);
    }

    private static void testServiceDataFilter(
            ScanFilter filter, boolean partialServiceDataMatchResult) {
        ScanFilterQueue queue = new ScanFilterQueue();
        queue.addScanFilter(filter);
        ScanFilterQueue.Entry entry = queue.pop();
        assertThat(entry.type).isEqualTo(ScanFilterQueue.TYPE_SERVICE_DATA);
        assertThat(entry.data)
                .isEqualTo(
                        Bytes.concat(
                                BluetoothUuid.uuidToBytes(ParcelUuid.fromString(TEST_UUID_STRING)),
                                TEST_SERVICE_DATA));
        assertThat(
                        serviceDataMatches(
                                entry.data,
                                Bytes.concat(
                                        BluetoothUuid.uuidToBytes(
                                                ParcelUuid.fromString(TEST_UUID_STRING)),
                                        TEST_SERVICE_DATA),
                                entry.data_mask))
                .isTrue();
        assertThat(
                        serviceDataMatches(
                                entry.data,
                                Bytes.concat(
                                        BluetoothUuid.uuidToBytes(
                                                ParcelUuid.fromString(UNMATCHED_UUID_STRING)),
                                        TEST_SERVICE_DATA),
                                entry.data_mask))
                .isFalse();
        assertThat(
                        serviceDataMatches(
                                entry.data,
                                Bytes.concat(
                                        BluetoothUuid.uuidToBytes(
                                                ParcelUuid.fromString(TEST_UUID_STRING)),
                                        UNMATCHED_SERVICE_DATA),
                                entry.data_mask))
                .isFalse();
        assertThat(
                        serviceDataMatches(
                                entry.data,
                                Bytes.concat(
                                        BluetoothUuid.uuidToBytes(
                                                ParcelUuid.fromString(TEST_UUID_STRING)),
                                        PARTIALLY_MATCHED_SERVICE_DATA),
                                entry.data_mask))
                .isEqualTo(partialServiceDataMatchResult);
    }

    private static boolean serviceDataMatches(byte[] filterData, byte[] resultData, byte[] mask) {
        if (filterData.length > resultData.length || filterData.length != mask.length) {
            return false;
        }
        for (int i = 0; i < filterData.length; i++) {
            if ((filterData[i] & mask[i]) != (resultData[i] & mask[i])) {
                return false;
            }
        }
        return true;
    }
}
