/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.bluetooth.bass_client;

import android.util.Log;

import com.android.bluetooth.flags.Flags;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Helper class to parse the Broadcast Announcement BASE data */
record BaseData(Base base) {
    private static final String TAG = BassClientService.TAG + "." + BaseData.class.getSimpleName();

    private static final int MIN_DATA_LENGTH = 14;
    private static final int MIN_SUBGROUP_DATA_LENGTH = 10;
    private static final int MIN_BIS_DATA_LENGTH = 2;

    static class Base {
        int mPresentationDelay;
        List<BaseSubgroup> mSubgroups = new ArrayList<>();

        void print(String prefix) {
            Log.d(TAG, prefix + "mPresentationDelay: " + mPresentationDelay + " [us]");
            Log.d(TAG, prefix + "mSubgroups: [");
            if (mSubgroups != null && !mSubgroups.isEmpty()) {
                mSubgroups.forEach(subgroup -> subgroup.print(prefix + "  "));
            } else {
                Log.d(TAG, prefix + "  (empty)");
            }
            Log.d(TAG, prefix + "]");
        }
    }

    static class BaseSubgroup {
        long mCodecId;
        byte[] mCodecSpecificConfiguration;
        byte[] mMetadata;
        LtvData mLtvData;
        List<BaseBis> mBises = new ArrayList<>();

        void print(String prefix) {
            Log.d(TAG, prefix + "BaseSubgroup {");
            Log.d(TAG, prefix + "  mCodecId: " + mCodecId);
            Log.d(
                    TAG,
                    prefix
                            + "  mCodecSpecificConfiguration: "
                            + Arrays.toString(mCodecSpecificConfiguration));
            Log.d(TAG, prefix + "  mMetadata: " + Arrays.toString(mMetadata));
            if (Flags.leaudioBroadcastExtendAudioActiveState()) {
                if (mLtvData != null) {
                    Log.d(TAG, prefix + "  " + mLtvData.toString());
                }
            }
            Log.d(TAG, prefix + "  mBises: [");
            if (mBises != null && !mBises.isEmpty()) {
                mBises.forEach(bis -> bis.print(prefix + "    "));
            } else {
                Log.d(TAG, prefix + "    (empty)");
            }
            Log.d(TAG, prefix + "  ]");
            Log.d(TAG, prefix + "}");
        }
    }

    static class BaseBis {
        int mIndex;
        byte[] mCodecSpecificConfiguration;

        void print(String prefix) {
            Log.d(TAG, prefix + "BaseBis {");
            Log.d(TAG, prefix + "  mIndex: " + mIndex);
            Log.d(
                    TAG,
                    prefix
                            + "  mCodecSpecificConfiguration: "
                            + Arrays.toString(mCodecSpecificConfiguration));
            Log.d(TAG, prefix + "}");
        }
    }

    static BaseData parseBaseData(byte[] serviceData) {
        if (serviceData == null) {
            Log.e(TAG, "Invalid service data for BaseData construction");
            throw new IllegalArgumentException("BaseData: serviceData is null");
        }

        if (serviceData.length < MIN_DATA_LENGTH) {
            Log.w(TAG, "parseBaseData: Invalid length of BASE data: " + serviceData.length);
            return null;
        }

        // Parse Level 1 BASE
        Base base = new Base();

        int offset = 0;
        base.mPresentationDelay =
                ((int) serviceData[offset++] & 0xFF)
                        | (((int) serviceData[offset++] & 0xFF) << 8)
                        | (((int) serviceData[offset++] & 0xFF) << 16);

        int numSubgroups = (int) serviceData[offset++] & 0xFF;
        if (numSubgroups < 1) {
            Log.w(TAG, "parseBaseData: invalid number of subgroups: " + numSubgroups);
            return null;
        }

        // Parse Level 2 BASE (subgroups)
        for (int i = 0; i < numSubgroups; i++) {
            int subgroupLength = serviceData.length - offset;
            if (subgroupLength < MIN_SUBGROUP_DATA_LENGTH) {
                Log.w(TAG, "parseBaseData: Invalid length of subgroup data: " + subgroupLength);
                return null;
            }

            int numBis = (int) serviceData[offset++] & 0xFF;
            BaseSubgroup subgroup = new BaseSubgroup();

            subgroup.mCodecId =
                    ((long) serviceData[offset++] & 0xFF)
                            | ((long) (serviceData[offset++] & 0xFF)) << 8
                            | ((long) (serviceData[offset++] & 0xFF)) << 16
                            | ((long) (serviceData[offset++] & 0xFF)) << 24
                            | ((long) (serviceData[offset++] & 0xFF)) << 32;

            // Copy and validate codec configuration data
            int codecConfigurationLength = (int) serviceData[offset++] & 0xFF;
            if (serviceData.length - offset < codecConfigurationLength) {
                Log.w(
                        TAG,
                        "parseBaseData: Invalid length of "
                                + i
                                + " subgroup codec "
                                + "configuration data: "
                                + codecConfigurationLength);
                return null;
            }
            subgroup.mCodecSpecificConfiguration = new byte[codecConfigurationLength];
            System.arraycopy(
                    serviceData,
                    offset,
                    subgroup.mCodecSpecificConfiguration,
                    0,
                    codecConfigurationLength);
            offset += codecConfigurationLength;

            // Copy and validate metadata
            int metadataLength = (int) serviceData[offset++] & 0xFF;
            if (serviceData.length - offset < metadataLength) {
                Log.w(
                        TAG,
                        "parseBaseData: Invalid length of "
                                + i
                                + " subgroup metadata "
                                + metadataLength);
                return null;
            }
            subgroup.mMetadata = new byte[metadataLength];
            System.arraycopy(serviceData, offset, subgroup.mMetadata, 0, metadataLength);
            if (Flags.leaudioBroadcastExtendAudioActiveState()) {
                subgroup.mLtvData = LtvData.parse(subgroup.mMetadata);
            }
            offset += metadataLength;

            // Parse level 3 BASE (bises)
            for (int j = 0; j < numBis; j++) {
                int bisLength = serviceData.length - offset;
                if (bisLength < MIN_BIS_DATA_LENGTH) {
                    Log.w(TAG, "parseBaseData: Invalid length of bis data: " + bisLength);
                    return null;
                }

                BaseBis bis = new BaseBis();

                bis.mIndex = serviceData[offset++];

                // Copy and validate codec configuration data
                codecConfigurationLength = (int) serviceData[offset++] & 0xFF;
                if (serviceData.length - offset < codecConfigurationLength) {
                    Log.w(
                            TAG,
                            "parseBaseData: Invalid length of "
                                    + i
                                    + " subgroup, "
                                    + j
                                    + " bis "
                                    + "configuration data: "
                                    + codecConfigurationLength);
                    return null;
                }
                bis.mCodecSpecificConfiguration = new byte[codecConfigurationLength];
                System.arraycopy(
                        serviceData,
                        offset,
                        bis.mCodecSpecificConfiguration,
                        0,
                        codecConfigurationLength);
                offset += codecConfigurationLength;

                subgroup.mBises.add(bis);
            }

            base.mSubgroups.add(subgroup);
        }

        if (offset != serviceData.length) {
            Log.w(
                    TAG,
                    "parseBaseData: Invalid length of service data, defined: "
                            + serviceData.length
                            + ", parsed: "
                            + offset);
            return null;
        }

        return new BaseData(base);
    }

    void print() {
        base.print("- ");
    }
}
