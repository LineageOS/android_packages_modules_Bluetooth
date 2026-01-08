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

#define LOG_TAG "BluetoothLeAudioUtilsJni"

#include "com_android_bluetooth_le_audio_utils.h"

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <vector>

#include "bluetooth/log.h"
#include "bluetooth/types/address.h"
#include "bta/le_audio/le_audio_types.h"

namespace android {
namespace {

namespace log = ::bluetooth::log;
namespace bt_le_audio = ::bluetooth::le_audio;

std::mutex ref_mutex;
uint32_t ref_count = 0;

size_t RawPacketSize(const std::map<uint8_t, std::vector<uint8_t>>& values) {
  size_t bytes = 0;
  for (auto const& value : values) {
    bytes += (/* ltv_len + ltv_type */ 2 + value.second.size());
  }
  return bytes;
}

jbyteArray prepareRawLtvArray(JNIEnv* env,
                              const std::map<uint8_t, std::vector<uint8_t>>& metadata) {
  auto raw_meta_size = RawPacketSize(metadata);

  jbyteArray raw_metadata = env->NewByteArray(raw_meta_size);
  if (!raw_metadata) {
    log::error("Failed to create new jbyteArray for raw LTV");
    return nullptr;
  }

  jsize offset = 0;
  for (auto const& kv_pair : metadata) {
    // Length
    const jbyte ltv_sz = kv_pair.second.size() + 1;
    env->SetByteArrayRegion(raw_metadata, offset, 1, &ltv_sz);
    offset += 1;
    // Type
    env->SetByteArrayRegion(raw_metadata, offset, 1, (const jbyte*)&kv_pair.first);
    offset += 1;
    // Value
    env->SetByteArrayRegion(raw_metadata, offset, kv_pair.second.size(),
                            (const jbyte*)kv_pair.second.data());
    offset += kv_pair.second.size();
  }

  return raw_metadata;
}

jlong getAudioLocationOrDefault(const std::map<uint8_t, std::vector<uint8_t>>& metadata,
                                jlong default_location) {
  if (metadata.count(bt_le_audio::kLeAudioLtvTypeAudioChannelAllocation) == 0) {
    return default_location;
  }

  auto& vec = metadata.at(bt_le_audio::kLeAudioLtvTypeAudioChannelAllocation);
  return VEC_UINT8_TO_UINT32(vec);
}

jint getSamplingFrequencyOrDefault(const std::map<uint8_t, std::vector<uint8_t>>& metadata,
                                   jint default_sampling_frequency) {
  if (metadata.count(bt_le_audio::kLeAudioLtvTypeSamplingFreq) == 0) {
    return default_sampling_frequency;
  }

  auto& vec = metadata.at(bt_le_audio::kLeAudioLtvTypeSamplingFreq);
  return (jint)(vec.data()[0]);
}

jint getFrameDurationOrDefault(const std::map<uint8_t, std::vector<uint8_t>>& metadata,
                               jint default_frame_duration) {
  if (metadata.count(bt_le_audio::kLeAudioLtvTypeFrameDuration) == 0) {
    return default_frame_duration;
  }

  auto& vec = metadata.at(bt_le_audio::kLeAudioLtvTypeFrameDuration);
  return (jint)(vec.data()[0]);
}

jint getOctetsPerFrameOrDefault(const std::map<uint8_t, std::vector<uint8_t>>& metadata,
                                jint default_octets_per_frame) {
  if (metadata.count(bt_le_audio::kLeAudioLtvTypeOctetsPerCodecFrame) == 0) {
    return default_octets_per_frame;
  }

  auto& vec = metadata.at(bt_le_audio::kLeAudioLtvTypeOctetsPerCodecFrame);
  return VEC_UINT8_TO_UINT16(vec);
}

jobject prepareLeBroadcastChannelObject(
        JNIEnv* env, const bt_le_audio::BasicAudioAnnouncementBisConfig& bis_config) {
  ScopedLocalRef<jobject> meta_object(
          env, prepareLeAudioCodecConfigMetadataObject(env, bis_config.codec_specific_params));
  if (!meta_object.get()) {
    log::error("Failed to create new metadata object for bis config");
    return nullptr;
  }

  jobject obj = env->NewObject(android_bluetooth_BluetoothLeBroadcastChannel.clazz,
                               android_bluetooth_BluetoothLeBroadcastChannel.constructor, false,
                               bis_config.bis_index, meta_object.get());

  return obj;
}

jobject prepareBluetoothDeviceObject(JNIEnv* env, const RawAddress& addr, int addr_type) {
  // The address string has to be uppercase or the BluetoothDevice constructor
  // will treat it as invalid.
  auto addr_str = addr.ToString();
  std::transform(addr_str.begin(), addr_str.end(), addr_str.begin(),
                 [](unsigned char c) { return std::toupper(c); });

  ScopedLocalRef<jstring> addr_jstr(env, env->NewStringUTF(addr_str.c_str()));
  if (!addr_jstr.get()) {
    log::error("Failed to create new preset name String for preset name");
    return nullptr;
  }

  return env->NewObject(android_bluetooth_BluetoothDevice.clazz,
                        android_bluetooth_BluetoothDevice.constructor, addr_jstr.get(),
                        (jint)addr_type);
}

jobject prepareLeBroadcastSubgroupObject(
        JNIEnv* env, const bt_le_audio::BasicAudioAnnouncementSubgroup& subgroup) {
  // Serialize codec ID
  jlong jlong_codec_id = subgroup.codec_config.codec_id |
                         ((jlong)subgroup.codec_config.vendor_company_id << 16) |
                         ((jlong)subgroup.codec_config.vendor_codec_id << 32);

  ScopedLocalRef<jobject> codec_config_meta_obj(
          env, prepareLeAudioCodecConfigMetadataObject(
                       env, subgroup.codec_config.codec_specific_params));
  if (!codec_config_meta_obj.get()) {
    log::error("Failed to create new codec config metadata");
    return nullptr;
  }

  ScopedLocalRef<jobject> content_meta_obj(
          env, prepareLeAudioContentMetadataObject(env, subgroup.metadata));
  if (!content_meta_obj.get()) {
    log::error("Failed to create new codec config metadata");
    return nullptr;
  }

  ScopedLocalRef<jobject> channel_list_obj(
          env, prepareLeBroadcastChannelListObject(env, subgroup.bis_configs));
  if (!channel_list_obj.get()) {
    log::error("Failed to create new codec config metadata");
    return nullptr;
  }

  // Create the subgroup
  return env->NewObject(android_bluetooth_BluetoothLeBroadcastSubgroup.clazz,
                        android_bluetooth_BluetoothLeBroadcastSubgroup.constructor, jlong_codec_id,
                        codec_config_meta_obj.get(), content_meta_obj.get(),
                        channel_list_obj.get());
}

}  // namespace

JniJavaClass android_bluetooth_BluetoothDevice;
JniJavaArrayList java_util_ArrayList;
JniJavaClass android_bluetooth_BluetoothLeAudioCodecConfigMetadata;
JniJavaClass android_bluetooth_BluetoothLeAudioContentMetadata;
JniJavaClass android_bluetooth_BluetoothLeBroadcastChannel;
JniJavaClass android_bluetooth_BluetoothLeBroadcastSubgroup;
JniJavaClass android_bluetooth_BluetoothLeBroadcastMetadata;

void UtilsInit(JNIEnv* env) {
  std::lock_guard<std::mutex> lock(ref_mutex);

  if (ref_count++ > 0) {
    return;
  }

  android_bluetooth_BluetoothDevice.clazz =
          (jclass)env->NewGlobalRef(env->FindClass("android/bluetooth/BluetoothDevice"));
  android_bluetooth_BluetoothDevice.constructor = env->GetMethodID(
          android_bluetooth_BluetoothDevice.clazz, "<init>", "(Ljava/lang/String;I)V");

  java_util_ArrayList.clazz = (jclass)env->NewGlobalRef(env->FindClass("java/util/ArrayList"));
  java_util_ArrayList.constructor = env->GetMethodID(java_util_ArrayList.clazz, "<init>", "()V");
  java_util_ArrayList.add =
          env->GetMethodID(java_util_ArrayList.clazz, "add", "(Ljava/lang/Object;)Z");

  android_bluetooth_BluetoothLeAudioCodecConfigMetadata.clazz = (jclass)env->NewGlobalRef(
          env->FindClass("android/bluetooth/BluetoothLeAudioCodecConfigMetadata"));
  android_bluetooth_BluetoothLeAudioCodecConfigMetadata.constructor = env->GetMethodID(
          android_bluetooth_BluetoothLeAudioCodecConfigMetadata.clazz, "<init>", "(JIII[B)V");

  android_bluetooth_BluetoothLeAudioContentMetadata.clazz = (jclass)env->NewGlobalRef(
          env->FindClass("android/bluetooth/BluetoothLeAudioContentMetadata"));
  android_bluetooth_BluetoothLeAudioContentMetadata.constructor =
          env->GetMethodID(android_bluetooth_BluetoothLeAudioContentMetadata.clazz, "<init>",
                           "(Ljava/lang/String;Ljava/lang/String;[B)V");

  android_bluetooth_BluetoothLeBroadcastChannel.clazz = (jclass)env->NewGlobalRef(
          env->FindClass("android/bluetooth/BluetoothLeBroadcastChannel"));
  android_bluetooth_BluetoothLeBroadcastChannel.constructor =
          env->GetMethodID(android_bluetooth_BluetoothLeBroadcastChannel.clazz, "<init>",
                           "(ZILandroid/bluetooth/BluetoothLeAudioCodecConfigMetadata;)V");

  android_bluetooth_BluetoothLeBroadcastSubgroup.clazz = (jclass)env->NewGlobalRef(
          env->FindClass("android/bluetooth/BluetoothLeBroadcastSubgroup"));
  android_bluetooth_BluetoothLeBroadcastSubgroup.constructor =
          env->GetMethodID(android_bluetooth_BluetoothLeBroadcastSubgroup.clazz, "<init>",
                           "(JLandroid/bluetooth/BluetoothLeAudioCodecConfigMetadata;"
                           "Landroid/bluetooth/BluetoothLeAudioContentMetadata;"
                           "Ljava/util/List;)V");

  android_bluetooth_BluetoothLeBroadcastMetadata.clazz = (jclass)env->NewGlobalRef(
          env->FindClass("android/bluetooth/BluetoothLeBroadcastMetadata"));
  android_bluetooth_BluetoothLeBroadcastMetadata.constructor =
          env->GetMethodID(android_bluetooth_BluetoothLeBroadcastMetadata.clazz, "<init>",
                           "(ILandroid/bluetooth/BluetoothDevice;IIIZZLjava/lang/String;"
                           "[BIIILandroid/bluetooth/BluetoothLeAudioContentMetadata;"
                           "Ljava/util/List;)V");
}

void UtilsCleanup(JNIEnv* env) {
  std::lock_guard<std::mutex> lock(ref_mutex);

  if (!ref_count) {
    log::error("UtilsCleanup called with ref_count == 0");
    return;
  }
  if (--ref_count > 0) {
    return;
  }

  env->DeleteGlobalRef(android_bluetooth_BluetoothDevice.clazz);
  android_bluetooth_BluetoothDevice.clazz = nullptr;

  env->DeleteGlobalRef(java_util_ArrayList.clazz);
  java_util_ArrayList.clazz = nullptr;

  env->DeleteGlobalRef(android_bluetooth_BluetoothLeAudioCodecConfigMetadata.clazz);
  android_bluetooth_BluetoothLeAudioCodecConfigMetadata.clazz = nullptr;

  env->DeleteGlobalRef(android_bluetooth_BluetoothLeAudioContentMetadata.clazz);
  android_bluetooth_BluetoothLeAudioContentMetadata.clazz = nullptr;

  env->DeleteGlobalRef(android_bluetooth_BluetoothLeBroadcastChannel.clazz);
  android_bluetooth_BluetoothLeBroadcastChannel.clazz = nullptr;

  env->DeleteGlobalRef(android_bluetooth_BluetoothLeBroadcastSubgroup.clazz);
  android_bluetooth_BluetoothLeBroadcastSubgroup.clazz = nullptr;

  env->DeleteGlobalRef(android_bluetooth_BluetoothLeBroadcastMetadata.clazz);
  android_bluetooth_BluetoothLeBroadcastMetadata.clazz = nullptr;
}

jobject prepareLeAudioCodecConfigMetadataObject(
        JNIEnv* env, const std::map<uint8_t, std::vector<uint8_t>>& metadata) {
  jlong audio_location = getAudioLocationOrDefault(metadata, -1);
  jint sampling_frequency = getSamplingFrequencyOrDefault(metadata, 0);
  jint frame_duration = getFrameDurationOrDefault(metadata, -1);
  jint octets_per_frame = getOctetsPerFrameOrDefault(metadata, 0);
  ScopedLocalRef<jbyteArray> raw_metadata(env, prepareRawLtvArray(env, metadata));
  if (!raw_metadata.get()) {
    log::error("Failed to create raw metadata jbyteArray");
    return nullptr;
  }

  jobject obj = env->NewObject(android_bluetooth_BluetoothLeAudioCodecConfigMetadata.clazz,
                               android_bluetooth_BluetoothLeAudioCodecConfigMetadata.constructor,
                               audio_location, sampling_frequency, frame_duration, octets_per_frame,
                               raw_metadata.get());

  return obj;
}

jobject prepareLeAudioContentMetadataObject(
        JNIEnv* env, const std::map<uint8_t, std::vector<uint8_t>>& metadata) {
  jstring program_info_str = nullptr;
  if (metadata.count(bt_le_audio::kLeAudioMetadataTypeProgramInfo)) {
    // Convert the metadata vector to string with null terminator
    std::string p_str((const char*)metadata.at(bt_le_audio::kLeAudioMetadataTypeProgramInfo).data(),
                      metadata.at(bt_le_audio::kLeAudioMetadataTypeProgramInfo).size());

    program_info_str = env->NewStringUTF(p_str.c_str());
    if (!program_info_str) {
      log::error("Failed to create new preset name String for preset name");
      return nullptr;
    }
  }

  jstring language_str = nullptr;
  if (metadata.count(bt_le_audio::kLeAudioMetadataTypeLanguage)) {
    // Convert the metadata vector to string with null terminator
    std::string l_str((const char*)metadata.at(bt_le_audio::kLeAudioMetadataTypeLanguage).data(),
                      metadata.at(bt_le_audio::kLeAudioMetadataTypeLanguage).size());

    language_str = env->NewStringUTF(l_str.c_str());
    if (!language_str) {
      log::error("Failed to create new preset name String for language");
      return nullptr;
    }
  }

  // This can be nullptr
  ScopedLocalRef<jbyteArray> raw_metadata(env, prepareRawLtvArray(env, metadata));
  if (!raw_metadata.get()) {
    log::error("Failed to create raw_metadata jbyteArray");
    return nullptr;
  }

  jobject obj = env->NewObject(android_bluetooth_BluetoothLeAudioContentMetadata.clazz,
                               android_bluetooth_BluetoothLeAudioContentMetadata.constructor,
                               program_info_str, language_str, raw_metadata.get());

  if (program_info_str) {
    env->DeleteLocalRef(program_info_str);
  }

  if (language_str) {
    env->DeleteLocalRef(language_str);
  }

  return obj;
}

jobject prepareLeBroadcastChannelListObject(
        JNIEnv* env, const std::vector<bt_le_audio::BasicAudioAnnouncementBisConfig>& bis_configs) {
  jobject array = env->NewObject(java_util_ArrayList.clazz, java_util_ArrayList.constructor);
  if (!array) {
    log::error("Failed to create array for subgroups");
    return nullptr;
  }

  for (const auto& el : bis_configs) {
    ScopedLocalRef<jobject> channel_obj(env, prepareLeBroadcastChannelObject(env, el));
    if (!channel_obj.get()) {
      log::error("Failed to create new channel object");
      return nullptr;
    }

    env->CallBooleanMethod(array, java_util_ArrayList.add, channel_obj.get());
  }
  return array;
}

jobject prepareLeBroadcastSubgroupListObject(
        JNIEnv* env,
        const std::vector<bt_le_audio::BasicAudioAnnouncementSubgroup>& subgroup_configs) {
  jobject array = env->NewObject(java_util_ArrayList.clazz, java_util_ArrayList.constructor);
  if (!array) {
    log::error("Failed to create array for subgroups");
    return nullptr;
  }

  for (const auto& el : subgroup_configs) {
    ScopedLocalRef<jobject> subgroup_obj(env, prepareLeBroadcastSubgroupObject(env, el));
    if (!subgroup_obj.get()) {
      log::error("Failed to create new subgroup object");
      return nullptr;
    }

    env->CallBooleanMethod(array, java_util_ArrayList.add, subgroup_obj.get());
  }
  return array;
}

jobject prepareBluetoothLeBroadcastMetadataObject(
        JNIEnv* env, const bt_le_audio::BroadcastMetadata& broadcast_metadata) {
  ScopedLocalRef<jobject> device_obj(
          env,
          prepareBluetoothDeviceObject(env, broadcast_metadata.addr, broadcast_metadata.addr_type));
  if (!device_obj.get()) {
    log::error("Failed to create new BluetoothDevice");
    return nullptr;
  }

  ScopedLocalRef<jobject> subgroup_list_obj(
          env, prepareLeBroadcastSubgroupListObject(
                       env, broadcast_metadata.basic_audio_announcement.subgroup_configs));
  if (!subgroup_list_obj.get()) {
    log::error("Failed to create new Subgroup array");
    return nullptr;
  }

  // Remove the ending null char bytes
  int nativeCodeSize = 16;
  if (broadcast_metadata.broadcast_code) {
    auto& nativeCode = broadcast_metadata.broadcast_code.value();
    nativeCodeSize =
            std::find_if(nativeCode.cbegin(), nativeCode.cend(), [](int x) { return x == 0x00; }) -
            nativeCode.cbegin();
  }

  ScopedLocalRef<jbyteArray> code(env, env->NewByteArray(nativeCodeSize));
  if (!code.get()) {
    log::error("Failed to create new jbyteArray for the broadcast code");
    return nullptr;
  }

  if (broadcast_metadata.broadcast_code) {
    env->SetByteArrayRegion(code.get(), 0, nativeCodeSize,
                            (const jbyte*)broadcast_metadata.broadcast_code->data());
    log::assert_that(!env->ExceptionCheck(), "assert failed: !env->ExceptionCheck() ");
  }

  ScopedLocalRef<jstring> broadcast_name(
          env, env->NewStringUTF(broadcast_metadata.broadcast_name.c_str()));
  if (!broadcast_name.get()) {
    log::error("Failed to create new broadcast name String");
    return nullptr;
  }

  jint audio_cfg_quality = 0;
  if (broadcast_metadata.public_announcement.features & bt_le_audio::kLeAudioQualityStandard) {
    // Set bit 0 for AUDIO_CONFIG_QUALITY_STANDARD
    audio_cfg_quality |= 0x1 << bt_le_audio::QUALITY_STANDARD;
  }
  if (broadcast_metadata.public_announcement.features & bt_le_audio::kLeAudioQualityHigh) {
    // Set bit 1 for AUDIO_CONFIG_QUALITY_HIGH
    audio_cfg_quality |= 0x1 << bt_le_audio::QUALITY_HIGH;
  }

  ScopedLocalRef<jobject> public_meta_obj(
          env, prepareLeAudioContentMetadataObject(
                       env, broadcast_metadata.public_announcement.metadata));
  if (!public_meta_obj.get()) {
    log::error("Failed to create new public metadata obj");
    return nullptr;
  }

  return env->NewObject(
          android_bluetooth_BluetoothLeBroadcastMetadata.clazz,
          android_bluetooth_BluetoothLeBroadcastMetadata.constructor,
          (jint)broadcast_metadata.addr_type, device_obj.get(), (jint)broadcast_metadata.adv_sid,
          (jint)broadcast_metadata.broadcast_id, (jint)broadcast_metadata.pa_interval,
          broadcast_metadata.broadcast_code ? true : false, broadcast_metadata.is_public,
          broadcast_name.get(), broadcast_metadata.broadcast_code ? code.get() : nullptr,
          (jint)broadcast_metadata.basic_audio_announcement.presentation_delay_us,
          audio_cfg_quality, (jint)bt_le_audio::kLeAudioSourceRssiUnknown, public_meta_obj.get(),
          subgroup_list_obj.get());
}

}  // namespace android
