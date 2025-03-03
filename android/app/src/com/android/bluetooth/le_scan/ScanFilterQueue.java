/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.bluetooth.BluetoothAssignedNumbers.OrganizationId;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.TransportBlockFilter;
import android.os.ParcelUuid;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

/** Helper class used to manage advertisement package filters. */
/* package */ class ScanFilterQueue {
    @VisibleForTesting static final int TYPE_DEVICE_ADDRESS = 0;
    @VisibleForTesting static final int TYPE_SERVICE_DATA_CHANGED = 1;
    @VisibleForTesting static final int TYPE_SERVICE_UUID = 2;
    @VisibleForTesting static final int TYPE_SOLICIT_UUID = 3;
    @VisibleForTesting static final int TYPE_LOCAL_NAME = 4;
    @VisibleForTesting static final int TYPE_MANUFACTURER_DATA = 5;
    @VisibleForTesting static final int TYPE_SERVICE_DATA = 6;
    @VisibleForTesting static final int TYPE_TRANSPORT_DISCOVERY_DATA = 7;
    @VisibleForTesting static final int TYPE_ADVERTISING_DATA_TYPE = 8;

    private static final int TYPE_INVALID = 0x00; // Meta data type for Transport Block Filter
    private static final int TYPE_WIFI_NAN_HASH = 0x01; // WIFI NAN HASH type

    // Max length is 31 - 3(flags) - 2 (one byte for length and one byte for type).
    private static final int MAX_LEN_PER_FIELD = 26;

    static class Entry {
        public byte type;
        public String address;
        public byte addr_type;
        public byte[] irk;
        public UUID uuid;
        public UUID uuid_mask;
        public String name;
        public int company;
        public int company_mask;
        public int ad_type;
        public byte[] data;
        public byte[] data_mask;
        public int org_id;
        public int tds_flags;
        public int tds_flags_mask;
        public int meta_data_type;
        public byte[] meta_data;
    }

    private final Set<Entry> mEntries = new HashSet<>();

    @VisibleForTesting
    void addDeviceAddress(String address, byte type, byte[] irk) {
        Entry entry = new Entry();
        entry.type = TYPE_DEVICE_ADDRESS;
        entry.address = address;
        entry.addr_type = type;
        entry.irk = irk;
        mEntries.add(entry);
    }

    @VisibleForTesting
    void addUuid(UUID uuid) {
        addUuid(uuid, new UUID(0, 0));
    }

    @VisibleForTesting
    void addUuid(UUID uuid, UUID uuidMask) {
        Entry entry = new Entry();
        entry.type = TYPE_SERVICE_UUID;
        entry.uuid = uuid;
        entry.uuid_mask = uuidMask;
        mEntries.add(entry);
    }

    @VisibleForTesting
    void addSolicitUuid(UUID uuid) {
        addSolicitUuid(uuid, new UUID(0, 0));
    }

    @VisibleForTesting
    void addSolicitUuid(UUID uuid, UUID uuidMask) {
        Entry entry = new Entry();
        entry.type = TYPE_SOLICIT_UUID;
        entry.uuid = uuid;
        entry.uuid_mask = uuidMask;
        mEntries.add(entry);
    }

    @VisibleForTesting
    void addName(String name) {
        Entry entry = new Entry();
        entry.type = TYPE_LOCAL_NAME;
        entry.name = name;
        mEntries.add(entry);
    }

    @VisibleForTesting
    void addManufacturerData(int company, byte[] data) {
        int companyMask = 0xFFFF;
        byte[] dataMask = new byte[data.length];
        Arrays.fill(dataMask, (byte) 0xFF);
        addManufacturerData(company, companyMask, data, dataMask);
    }

    @VisibleForTesting
    void addManufacturerData(int company, int companyMask, byte[] data, byte[] dataMask) {
        Entry entry = new Entry();
        entry.type = TYPE_MANUFACTURER_DATA;
        entry.company = company;
        entry.company_mask = companyMask;
        entry.data = data;
        entry.data_mask = dataMask;
        mEntries.add(entry);
    }

    @VisibleForTesting
    void addServiceData(byte[] data, byte[] dataMask) {
        Entry entry = new Entry();
        entry.type = TYPE_SERVICE_DATA;
        entry.data = data;
        entry.data_mask = dataMask;
        mEntries.add(entry);
    }

    @VisibleForTesting
    void addTransportDiscoveryData(
            int orgId,
            int tdsFlags,
            int tdsFlagsMask,
            byte[] transportData,
            byte[] transportDataMask,
            int metaDataType,
            byte[] metaData) {
        Entry entry = new Entry();
        entry.type = TYPE_TRANSPORT_DISCOVERY_DATA;
        entry.org_id = orgId;
        entry.tds_flags = tdsFlags;
        entry.tds_flags_mask = tdsFlagsMask;
        entry.data = transportData;
        entry.data_mask = transportDataMask;
        entry.meta_data_type = metaDataType;
        entry.meta_data = metaData;
        mEntries.add(entry);
    }

    @VisibleForTesting
    void addAdvertisingDataType(int adType, byte[] data, byte[] dataMask) {
        Entry entry = new Entry();
        entry.type = TYPE_ADVERTISING_DATA_TYPE;
        entry.ad_type = adType;
        entry.data = data;
        entry.data_mask = dataMask;
        mEntries.add(entry);
    }

    @VisibleForTesting
    Entry pop() {
        if (mEntries.isEmpty()) {
            return null;
        }
        Iterator<Entry> iterator = mEntries.iterator();
        Entry entry = iterator.next();
        iterator.remove();
        return entry;
    }

    // Compute feature selection based on the filters presented.
    int getFeatureSelection() {
        int selection = 0;
        for (Entry entry : mEntries) {
            selection |= (1 << entry.type);
        }
        return selection;
    }

    ScanFilterQueue.Entry[] toArray() {
        return mEntries.toArray(new ScanFilterQueue.Entry[mEntries.size()]);
    }

    // Add ScanFilter to scan filter queue.
    void addScanFilter(ScanFilter filter) {
        if (filter == null) {
            return;
        }
        if (filter.getDeviceName() != null) {
            addName(filter.getDeviceName());
        }
        if (filter.getDeviceAddress() != null) {
            /*
             * Pass the address type here. This address type will be used for the resolving
             * address, however, the host stack will force the type to 0x02 for the APCF filter
             * in btm_ble_adv_filter.cc#BTM_LE_PF_addr_filter(...)
             */
            addDeviceAddress(
                    filter.getDeviceAddress(), (byte) filter.getAddressType(), filter.getIrk());
        }
        if (filter.getServiceUuid() != null) {
            if (filter.getServiceUuidMask() == null) {
                addUuid(filter.getServiceUuid().getUuid());
            } else {
                addUuid(filter.getServiceUuid().getUuid(), filter.getServiceUuidMask().getUuid());
            }
        }
        if (filter.getServiceSolicitationUuid() != null) {
            if (filter.getServiceSolicitationUuidMask() == null) {
                addSolicitUuid(filter.getServiceSolicitationUuid().getUuid());
            } else {
                addSolicitUuid(
                        filter.getServiceSolicitationUuid().getUuid(),
                        filter.getServiceSolicitationUuidMask().getUuid());
            }
        }
        if (filter.getManufacturerData() != null) {
            if (filter.getManufacturerDataMask() == null) {
                addManufacturerData(filter.getManufacturerId(), filter.getManufacturerData());
            } else {
                addManufacturerData(
                        filter.getManufacturerId(),
                        0xFFFF,
                        filter.getManufacturerData(),
                        filter.getManufacturerDataMask());
            }
        }
        if (filter.getServiceDataUuid() != null && filter.getServiceData() != null) {
            ParcelUuid serviceDataUuid = filter.getServiceDataUuid();
            byte[] serviceData = filter.getServiceData();
            byte[] serviceDataMask = filter.getServiceDataMask();
            if (serviceDataMask == null) {
                serviceDataMask = new byte[serviceData.length];
                Arrays.fill(serviceDataMask, (byte) 0xFF);
            }
            serviceData = concatenate(serviceDataUuid, serviceData, false);
            serviceDataMask = concatenate(serviceDataUuid, serviceDataMask, true);
            if (serviceData != null && serviceDataMask != null) {
                addServiceData(serviceData, serviceDataMask);
            }
        }
        if (filter.getAdvertisingDataType() > 0) {
            addAdvertisingDataType(
                    filter.getAdvertisingDataType(),
                    filter.getAdvertisingData(),
                    filter.getAdvertisingDataMask());
        }
        final TransportBlockFilter transportBlockFilter = filter.getTransportBlockFilter();
        if (transportBlockFilter != null) {
            if (transportBlockFilter.getOrgId()
                    == OrganizationId.WIFI_ALLIANCE_NEIGHBOR_AWARENESS_NETWORKING) {
                addTransportDiscoveryData(
                        transportBlockFilter.getOrgId(),
                        transportBlockFilter.getTdsFlags(),
                        transportBlockFilter.getTdsFlagsMask(),
                        null,
                        null,
                        TYPE_WIFI_NAN_HASH,
                        transportBlockFilter.getWifiNanHash());
            } else {
                addTransportDiscoveryData(
                        transportBlockFilter.getOrgId(),
                        transportBlockFilter.getTdsFlags(),
                        transportBlockFilter.getTdsFlagsMask(),
                        transportBlockFilter.getTransportData(),
                        transportBlockFilter.getTransportDataMask(),
                        TYPE_INVALID,
                        null);
            }
        }
    }

    private static byte[] concatenate(
            ParcelUuid serviceDataUuid, byte[] serviceData, boolean isMask) {
        byte[] uuid = BluetoothUuid.uuidToBytes(serviceDataUuid);

        int dataLen = uuid.length + serviceData.length;
        // If data is too long, don't add it to hardware scan filter.
        if (dataLen > MAX_LEN_PER_FIELD) {
            return null;
        }
        byte[] concatenated = new byte[dataLen];
        if (isMask) {
            // For the UUID portion of the mask fill it with 0xFF to indicate that all bits of the
            // UUID need to match the service data filter.
            Arrays.fill(concatenated, 0, uuid.length, (byte) 0xFF);
        } else {
            System.arraycopy(uuid, 0, concatenated, 0, uuid.length);
        }
        System.arraycopy(serviceData, 0, concatenated, uuid.length, serviceData.length);
        return concatenated;
    }
}
