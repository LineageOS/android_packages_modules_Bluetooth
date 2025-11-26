/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.bluetooth.BluetoothLeAudioContentMetadata;
import android.bluetooth.BluetoothLeBroadcastMetadata;
import android.util.Log;

import com.android.bluetooth.flags.Flags;

import java.util.Arrays;

/** Helper class to parse the Public Broadcast Announcement data */
class PublicBroadcastData {
    private static final String TAG =
            BassClientService.TAG + "." + PublicBroadcastData.class.getSimpleName();

    private static final int FEATURES_ENCRYPTION_BIT = 0x01 << 0;
    private static final int FEATURES_STANDARD_QUALITY_BIT = 0x01 << 1;
    private static final int FEATURES_HIGH_QUALITY_BIT = 0x01 << 2;
    // public announcement service data should at least include features and metadata length
    private static final int PUBLIC_BROADCAST_SERVICE_DATA_LEN_MIN = 2;

    private final PublicBroadcastInfo mPublicBroadcastInfo;

    public static class PublicBroadcastInfo {
        public byte[] metaData;
        public boolean isEncrypted;
        public int audioConfigQuality;
        public LtvData ltvData;

        PublicBroadcastInfo() {
            metaData = new byte[0];
            isEncrypted = false;
            audioConfigQuality = BluetoothLeBroadcastMetadata.AUDIO_CONFIG_QUALITY_NONE;
            ltvData = LtvData.parse(null);
            log("PublicBroadcastInfo is Initialized");
        }

        void print() {
            log("**BEGIN: Public Broadcast Information**");
            log("encrypted: " + isEncrypted);
            log("audio config quality: " + audioConfigQuality);
            log("metaDataLength: " + metaData.length);
            if (metaData.length != 0) {
                log("metaData: " + Arrays.toString(metaData));
            }
            if (Flags.leaudioBroadcastExtendAudioActiveState()) {
                if (ltvData != null) {
                    log(ltvData.toString());
                }
            }
            log("**END: Public Broadcast Information****");
        }
    }

    PublicBroadcastData(PublicBroadcastInfo publicBroadcastInfo) {
        mPublicBroadcastInfo = publicBroadcastInfo;
    }

    static PublicBroadcastData buildPublicBroadcastData(BluetoothLeBroadcastMetadata metadata) {
        if (metadata == null || !metadata.isPublicBroadcast()) {
            return null;
        }

        PublicBroadcastInfo publicBroadcastInfo = new PublicBroadcastInfo();
        publicBroadcastInfo.audioConfigQuality = metadata.getAudioConfigQuality();
        publicBroadcastInfo.isEncrypted = metadata.isEncrypted();
        BluetoothLeAudioContentMetadata publicBroadcastMetadata =
                metadata.getPublicBroadcastMetadata();
        if (publicBroadcastMetadata != null) {
            publicBroadcastInfo.metaData = publicBroadcastMetadata.getRawMetadata();
            if (Flags.leaudioBroadcastExtendAudioActiveState()) {
                publicBroadcastInfo.ltvData = LtvData.parse(publicBroadcastInfo.metaData);
            }
        }

        publicBroadcastInfo.print();
        return new PublicBroadcastData(publicBroadcastInfo);
    }

    static PublicBroadcastData parsePublicBroadcastData(byte[] serviceData) {
        if (serviceData == null || serviceData.length < PUBLIC_BROADCAST_SERVICE_DATA_LEN_MIN) {
            Log.w(TAG, "Invalid service data for PublicBroadcastData construction");
            return null;
        }
        PublicBroadcastInfo publicBroadcastInfo = new PublicBroadcastInfo();

        log("PublicBroadcast input" + Arrays.toString(serviceData));

        int offset = 0;
        // Parse Public broadcast announcement features
        int features = serviceData[offset++];
        publicBroadcastInfo.isEncrypted =
                ((features & FEATURES_ENCRYPTION_BIT) != 0) ? true : false;
        publicBroadcastInfo.audioConfigQuality =
                BluetoothLeBroadcastMetadata.AUDIO_CONFIG_QUALITY_NONE;
        if ((features & FEATURES_STANDARD_QUALITY_BIT) != 0) {
            publicBroadcastInfo.audioConfigQuality |=
                    BluetoothLeBroadcastMetadata.AUDIO_CONFIG_QUALITY_STANDARD;
        }
        if ((features & FEATURES_HIGH_QUALITY_BIT) != 0) {
            publicBroadcastInfo.audioConfigQuality |=
                    BluetoothLeBroadcastMetadata.AUDIO_CONFIG_QUALITY_HIGH;
        }

        // Parse Public broadcast announcement metadata
        int metaDataLength = serviceData[offset++] & 0xff;
        if (serviceData.length != (metaDataLength + PUBLIC_BROADCAST_SERVICE_DATA_LEN_MIN)) {
            Log.w(TAG, "Invalid meta data length for PublicBroadcastData construction");
            return null;
        }
        if (metaDataLength != 0) {
            publicBroadcastInfo.metaData = new byte[metaDataLength];
            System.arraycopy(serviceData, offset, publicBroadcastInfo.metaData, 0, metaDataLength);
            if (Flags.leaudioBroadcastExtendAudioActiveState()) {
                publicBroadcastInfo.ltvData = LtvData.parse(publicBroadcastInfo.metaData);
            }
        }
        publicBroadcastInfo.print();
        return new PublicBroadcastData(publicBroadcastInfo);
    }

    boolean isEncrypted() {
        return mPublicBroadcastInfo.isEncrypted;
    }

    int getAudioConfigQuality() {
        return mPublicBroadcastInfo.audioConfigQuality;
    }

    int getMetadataLength() {
        return mPublicBroadcastInfo.metaData.length;
    }

    byte[] getMetadata() {
        return mPublicBroadcastInfo.metaData;
    }

    LtvData getLtvData() {
        return mPublicBroadcastInfo.ltvData;
    }

    void print() {
        mPublicBroadcastInfo.print();
    }

    static void log(String msg) {
        Log.d(TAG, msg);
    }
}
