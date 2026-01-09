/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#pragma once

#include <cstdint>
#include <map>
#include <vector>

#include "hardware/bt_le_audio.h"
#include "jni.h"
#include "nativehelper/ScopedLocalRef.h"

namespace android {

struct JniJavaClass {
  jclass clazz;
  jmethodID constructor;
};

struct JniJavaArrayList {
  jclass clazz;
  jmethodID constructor;
  jmethodID add;
};

extern JniJavaClass android_bluetooth_BluetoothDevice;
extern JniJavaArrayList java_util_ArrayList;
extern JniJavaClass android_bluetooth_BluetoothLeAudioCodecConfigMetadata;
extern JniJavaClass android_bluetooth_BluetoothLeAudioContentMetadata;
extern JniJavaClass android_bluetooth_BluetoothLeBroadcastChannel;
extern JniJavaClass android_bluetooth_BluetoothLeBroadcastSubgroup;
extern JniJavaClass android_bluetooth_BluetoothLeBroadcastMetadata;

void UtilsInit(JNIEnv* env);
void UtilsCleanup(JNIEnv* env);

jobject prepareBluetoothLeBroadcastMetadataObject(
        JNIEnv* env, const ::bluetooth::le_audio::BroadcastMetadata& broadcast_metadata);

jobject prepareLeAudioCodecConfigMetadataObject(
        JNIEnv* env, const std::map<uint8_t, std::vector<uint8_t>>& metadata);

jobject prepareLeAudioContentMetadataObject(
        JNIEnv* env, const std::map<uint8_t, std::vector<uint8_t>>& metadata);

jobject prepareLeBroadcastChannelListObject(
        JNIEnv* env,
        const std::vector<::bluetooth::le_audio::BasicAudioAnnouncementBisConfig>& bis_configs);

jobject prepareLeBroadcastSubgroupListObject(
        JNIEnv* env,
        const std::vector<::bluetooth::le_audio::BasicAudioAnnouncementSubgroup>& subgroup_configs);

}  // namespace android
