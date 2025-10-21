/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.bluetooth.btservice.storage;

import android.bluetooth.BluetoothLeAudioCodecConfig;
import android.util.Log;

import androidx.room.Entity;
import androidx.room.TypeConverter;
import androidx.room.TypeConverters;

import com.android.bluetooth.BluetoothLeAudioStorageProtos;

import java.util.ArrayList;
import java.util.List;

/* Note: Even though BluetoothLeAudioCodecConfig is a parcelable type, the marshalled parcel
 * data must not be used for persistent storage, as it may not maintain the compatibility with
 * other versions of the platform. Serialize with Protobuf instead.
 */
class LeAudioUnicastClientCodecPreferenceConverters {
    private static final String TAG =
            LeAudioUnicastClientCodecPreferenceConverters.class.getSimpleName();

    @TypeConverter
    public static List<BluetoothLeAudioCodecConfig> toList(byte[] serializedListData) {
        List<BluetoothLeAudioCodecConfig> codecConfigList = new ArrayList<>();

        if (serializedListData == null) {
            Log.e(TAG, "Invalid storage data");
            return codecConfigList;
        }

        try {
            BluetoothLeAudioStorageProtos.CodecConfigList codecConfigListProto =
                    BluetoothLeAudioStorageProtos.CodecConfigList.parser()
                            .parseFrom(serializedListData);

            for (BluetoothLeAudioStorageProtos.CodecConfig codecConfigProto :
                    codecConfigListProto.getConfigsList()) {
                BluetoothLeAudioCodecConfig codecConfig =
                        new BluetoothLeAudioCodecConfig.Builder()
                                .setCodecType(codecConfigProto.getCodecType())
                                .setCodecPriority(codecConfigProto.getCodecPriority())
                                .setSampleRate(codecConfigProto.getSampleRate())
                                .setBitsPerSample(codecConfigProto.getBitsPerSample())
                                .setChannelCount(codecConfigProto.getChannelCount())
                                .setFrameDuration(codecConfigProto.getFrameDuration())
                                .setOctetsPerFrame(codecConfigProto.getOctetsPerFrame())
                                .setMinOctetsPerFrame(codecConfigProto.getMinOctetsPerFrame())
                                .setMaxOctetsPerFrame(codecConfigProto.getMaxOctetsPerFrame())
                                .build();
                codecConfigList.add(codecConfig);
            }

        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
            Log.e(TAG, e.toString() + "\n" + Log.getStackTraceString(new Throwable()));
        }

        return codecConfigList;
    }

    @TypeConverter
    public static byte[] fromList(List<BluetoothLeAudioCodecConfig> codecConfigList) {
        if (codecConfigList == null) {
            Log.e(TAG, "No valid codec configuration list");
            return new byte[0];
        }

        BluetoothLeAudioStorageProtos.CodecConfigList.Builder codecConfigListProtoBuilder =
                BluetoothLeAudioStorageProtos.CodecConfigList.newBuilder();

        for (BluetoothLeAudioCodecConfig codecConfig : codecConfigList) {
            BluetoothLeAudioStorageProtos.CodecConfig codecConfigProto =
                    BluetoothLeAudioStorageProtos.CodecConfig.newBuilder()
                            .setCodecType(codecConfig.getCodecType())
                            .setCodecPriority(codecConfig.getCodecPriority())
                            .setSampleRate(codecConfig.getSampleRate())
                            .setBitsPerSample(codecConfig.getBitsPerSample())
                            .setChannelCount(codecConfig.getChannelCount())
                            .setFrameDuration(codecConfig.getFrameDuration())
                            .setOctetsPerFrame(codecConfig.getOctetsPerFrame())
                            .setMinOctetsPerFrame(codecConfig.getMinOctetsPerFrame())
                            .setMaxOctetsPerFrame(codecConfig.getMaxOctetsPerFrame())
                            .build();
            codecConfigListProtoBuilder.addConfigs(codecConfigProto);
        }

        return codecConfigListProtoBuilder.build().toByteArray();
    }
}

@Entity
public class LeAudioUnicastClientCodecPreferenceEntity {
    @TypeConverters(LeAudioUnicastClientCodecPreferenceConverters.class)
    public List<BluetoothLeAudioCodecConfig> list = new ArrayList<>();

    public String toString() {
        return list.toString();
    }
}
