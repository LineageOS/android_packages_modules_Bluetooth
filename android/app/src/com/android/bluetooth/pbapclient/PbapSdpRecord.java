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

package com.android.bluetooth.pbapclient;

import static java.util.Objects.requireNonNull;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.SdpPseRecord;

/**
 * This object represents an SDP Record for the PBAP profile. It extends the framework class by
 * housing all the supported feature masks.
 */
public class PbapSdpRecord {
    public static final int VERSION_1_0 = 0x0100;
    public static final int VERSION_1_1 = 0x0101;
    public static final int VERSION_1_2 = 0x0102;

    public static final int FEATURES_EXCLUDED = -1;
    public static final int FEATURE_DOWNLOADING = 1 << 0;
    public static final int FEATURE_BROWSING = 1 << 1;
    public static final int FEATURE_DATABASE_IDENTIFIER = 1 << 2;
    public static final int FEATURE_FOLDER_VERSION_COUNTERS = 1 << 3;
    public static final int FEATURE_VCARD_SELECTING = 1 << 4;
    public static final int FEATURE_ENHANCED_MISSED_CALLS = 1 << 5;
    public static final int FEATURE_XBT_UCI_VCARD_PROPERTY = 1 << 6;
    public static final int FEATURE_XBT_UID_VCARD_PROPERTY = 1 << 7;
    public static final int FEATURE_CONTACT_REFERENCING = 1 << 8;
    public static final int FEATURE_DEFAULT_IMAGE_FORMAT = 1 << 9;

    // PBAP v1.2.3 Sec. 7.1.2
    public static final int REPOSITORY_LOCAL_PHONEBOOK = 1 << 0;
    public static final int REPOSITORY_SIM_CARD = 1 << 1;
    public static final int REPOSITORY_SPEED_DIAL = 1 << 2;
    public static final int REPOSITORY_FAVORITES = 1 << 3;

    public static final int FIELD_MISSING = -1;

    private final BluetoothDevice mDevice;
    private final SdpPseRecord mSdpRecord;

    PbapSdpRecord(BluetoothDevice device, SdpPseRecord record) {
        mDevice = requireNonNull(device);
        mSdpRecord = requireNonNull(record);
    }

    /** Get the device associated with this SDP record */
    public BluetoothDevice getDevice() {
        return mDevice;
    }

    /** Get the profile version associated with this SDP record */
    public int getProfileVersion() {
        return mSdpRecord.getProfileVersion();
    }

    /** Get the service name associated with this SDP record */
    public String getServiceName() {
        return mSdpRecord.getServiceName();
    }

    /** Get the L2CAP PSM associated with this SDP record */
    public int getL2capPsm() {
        return mSdpRecord.getL2capPsm();
    }

    /** Get the RFCOMM channel number associated with this SDP record */
    public int getRfcommChannelNumber() {
        return mSdpRecord.getRfcommChannelNumber();
    }

    /** Get the supported features associated with this SDP record */
    public int getSupportedFeatures() {
        return mSdpRecord.getSupportedFeatures();
    }

    /** Returns true if this SDP record supports a given feature */
    public boolean isFeatureSupported(int feature) {
        int remoteFeatures = mSdpRecord.getSupportedFeatures();
        if (remoteFeatures != FIELD_MISSING) {
            return (feature & remoteFeatures) != 0;
        }
        return false;
    }

    /** Git the supported repositories bitmask associated with this SDP record */
    public int getSupportedRepositories() {
        return mSdpRecord.getSupportedRepositories();
    }

    /** Returns true if this SDP record supports a given repository */
    public boolean isRepositorySupported(int repository) {
        int remoteRepositories = mSdpRecord.getSupportedRepositories();
        if (remoteRepositories != FIELD_MISSING) {
            return (repository & remoteRepositories) != 0;
        }
        return false;
    }

    /** Get a string representation of this SDP record */
    @Override
    public String toString() {
        return mSdpRecord.toString();
    }

    /**
     * Get a string representation of any of the SDP PBAP version constants
     *
     * <p>Version is represented as a series of specification defined constants, in the form:
     * 0x[Major 2 bytes][Minor 2 bytes] -> [Major].[Minor]
     *
     * <p>For example, 0x0102 is 1.2.
     */
    public static String versionToString(int version) {
        switch (version) {
            case FIELD_MISSING:
                return "VERSION_UNKNOWN";
            case VERSION_1_0:
                return "VERSION_1_0";
            case VERSION_1_1:
                return "VERSION_1_1";
            case VERSION_1_2:
                return "VERSION_1_2";
            default:
                return "VERSION_UNRECOGNIZED_" + String.format("%04X", version);
        }
    }

    /** Get a string representation of any of the SDP feature constants */
    public static String featureToString(int feature) {
        switch (feature) {
            case FEATURE_DOWNLOADING:
                return "FEATURE_DOWNLOADING";
            case FEATURE_BROWSING:
                return "FEATURE_BROWSING";
            case FEATURE_DATABASE_IDENTIFIER:
                return "FEATURE_DATABASE_IDENTIFIER";
            case FEATURE_FOLDER_VERSION_COUNTERS:
                return "FEATURE_FOLDER_VERSION_COUNTERS";
            case FEATURE_VCARD_SELECTING:
                return "FEATURE_VCARD_SELECTING";
            case FEATURE_ENHANCED_MISSED_CALLS:
                return "FEATURE_ENHANCED_MISSED_CALLS";
            case FEATURE_XBT_UCI_VCARD_PROPERTY:
                return "FEATURE_XBT_UCI_VCARD_PROPERTY";
            case FEATURE_XBT_UID_VCARD_PROPERTY:
                return "FEATURE_XBT_UID_VCARD_PROPERTY";
            case FEATURE_CONTACT_REFERENCING:
                return "FEATURE_CONTACT_REFERENCING";
            case FEATURE_DEFAULT_IMAGE_FORMAT:
                return "FEATURE_DEFAULT_IMAGE_FORMAT";
            default:
                return "FEATURE_RESERVED_BIT_" + feature;
        }
    }

    /** Get a string representation of any of the SDP repository constants */
    public static String repositoryToString(int repository) {
        switch (repository) {
            case REPOSITORY_LOCAL_PHONEBOOK:
                return "REPOSITORY_LOCAL_PHONEBOOK";
            case REPOSITORY_SIM_CARD:
                return "REPOSITORY_SIM_CARD";
            case REPOSITORY_SPEED_DIAL:
                return "REPOSITORY_SPEED_DIAL";
            case REPOSITORY_FAVORITES:
                return "REPOSITORY_FAVORITES";
            default:
                return "REPOSITORY_RESERVED_BIT_" + repository;
        }
    }
}
