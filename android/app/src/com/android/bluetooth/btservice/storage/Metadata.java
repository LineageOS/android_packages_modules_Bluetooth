/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothA2dp.OptionalCodecsPreferenceStatus;
import android.bluetooth.BluetoothA2dp.OptionalCodecsSupportStatus;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;

import androidx.annotation.NonNull;
import androidx.room.Embedded;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "metadata")
public class Metadata {
    @PrimaryKey @NonNull private final String address;

    @Embedded public ProfilePrioritiesEntity profileConnectionPolicies;

    @Embedded @NonNull public CustomizedMetadataEntity publicMetadata;

    public @OptionalCodecsSupportStatus int a2dpSupportsOptionalCodecs;
    public @OptionalCodecsPreferenceStatus int a2dpOptionalCodecsEnabled;

    public long last_active_time;
    public boolean is_active_a2dp_device;

    public boolean isActiveHfpDevice;

    @Embedded public AudioPolicyEntity audioPolicyMetadata;

    /**
     * The preferred profile to be used for {@link BluetoothDevice#AUDIO_MODE_OUTPUT_ONLY}. This can
     * be either {@link BluetoothProfile#A2DP} or {@link BluetoothProfile#LE_AUDIO}. This value is
     * only used if the remote device supports both A2DP and LE Audio and both transports are
     * connected and active.
     */
    public int preferred_output_only_profile;

    /**
     * The preferred profile to be used for {@link BluetoothDevice#AUDIO_MODE_DUPLEX}. This can be
     * either {@link BluetoothProfile#HEADSET} or {@link BluetoothProfile#LE_AUDIO}. This value is
     * only used if the remote device supports both HFP and LE Audio and both transports are
     * connected and active.
     */
    public int preferred_duplex_profile;

    /** This is used to indicate whether device's active audio policy */
    public int active_audio_device_policy;

    /** This is used to indicate whether device's microphone prefer to use during calls */
    public boolean is_preferred_microphone_for_calls;

    /** This is used to indicate the number of times the bond has been lost */
    public int key_missing_count;

    /** This is used for the LE Audio Unicast input codec preference */
    @Embedded(prefix = "le_audio_unicast_client_input_codec_config_preference_")
    @NonNull
    public LeAudioUnicastClientCodecPreferenceEntity leAudioUnicastClientInputCodecConfigPreference;

    /** This is used for the LE Audio Unicast output codec preference */
    @Embedded(prefix = "le_audio_unicast_client_output_codec_config_preference_")
    @NonNull
    public LeAudioUnicastClientCodecPreferenceEntity
            leAudioUnicastClientOutputCodecConfigPreference;

    public Metadata(String address) {
        this.address = address;
        profileConnectionPolicies = new ProfilePrioritiesEntity();
        publicMetadata = new CustomizedMetadataEntity();
        a2dpSupportsOptionalCodecs = BluetoothA2dp.OPTIONAL_CODECS_SUPPORT_UNKNOWN;
        a2dpOptionalCodecsEnabled = BluetoothA2dp.OPTIONAL_CODECS_PREF_UNKNOWN;
        last_active_time = 0;
        is_active_a2dp_device = false;
        isActiveHfpDevice = false;
        audioPolicyMetadata = new AudioPolicyEntity();
        preferred_output_only_profile = 0;
        preferred_duplex_profile = 0;
        active_audio_device_policy = BluetoothDevice.ACTIVE_AUDIO_DEVICE_POLICY_DEFAULT;
        is_preferred_microphone_for_calls = true;
        key_missing_count = 0;
        leAudioUnicastClientInputCodecConfigPreference =
                new LeAudioUnicastClientCodecPreferenceEntity();
        leAudioUnicastClientOutputCodecConfigPreference =
                new LeAudioUnicastClientCodecPreferenceEntity();
    }

    public String getAddress() {
        return address;
    }

    public byte[] getCustomizedMeta(int key) {
        return switch (key) {
            case BluetoothDevice.METADATA_MANUFACTURER_NAME -> publicMetadata.manufacturer_name;
            case BluetoothDevice.METADATA_MODEL_NAME -> publicMetadata.model_name;
            case BluetoothDevice.METADATA_SOFTWARE_VERSION -> publicMetadata.software_version;
            case BluetoothDevice.METADATA_HARDWARE_VERSION -> publicMetadata.hardware_version;
            case BluetoothDevice.METADATA_COMPANION_APP -> publicMetadata.companion_app;
            case BluetoothDevice.METADATA_MAIN_ICON -> publicMetadata.main_icon;
            case BluetoothDevice.METADATA_IS_UNTETHERED_HEADSET ->
                    publicMetadata.is_untethered_headset;
            case BluetoothDevice.METADATA_UNTETHERED_LEFT_ICON ->
                    publicMetadata.untethered_left_icon;
            case BluetoothDevice.METADATA_UNTETHERED_RIGHT_ICON ->
                    publicMetadata.untethered_right_icon;
            case BluetoothDevice.METADATA_UNTETHERED_CASE_ICON ->
                    publicMetadata.untethered_case_icon;
            case BluetoothDevice.METADATA_UNTETHERED_LEFT_BATTERY ->
                    publicMetadata.untethered_left_battery;
            case BluetoothDevice.METADATA_UNTETHERED_RIGHT_BATTERY ->
                    publicMetadata.untethered_right_battery;
            case BluetoothDevice.METADATA_UNTETHERED_CASE_BATTERY ->
                    publicMetadata.untethered_case_battery;
            case BluetoothDevice.METADATA_UNTETHERED_LEFT_CHARGING ->
                    publicMetadata.untethered_left_charging;
            case BluetoothDevice.METADATA_UNTETHERED_RIGHT_CHARGING ->
                    publicMetadata.untethered_right_charging;
            case BluetoothDevice.METADATA_UNTETHERED_CASE_CHARGING ->
                    publicMetadata.untethered_case_charging;
            case BluetoothDevice.METADATA_ENHANCED_SETTINGS_UI_URI ->
                    publicMetadata.enhanced_settings_ui_uri;
            case BluetoothDevice.METADATA_DEVICE_TYPE -> publicMetadata.device_type;
            case BluetoothDevice.METADATA_MAIN_BATTERY -> publicMetadata.main_battery;
            case BluetoothDevice.METADATA_MAIN_CHARGING -> publicMetadata.main_charging;
            case BluetoothDevice.METADATA_MAIN_LOW_BATTERY_THRESHOLD ->
                    publicMetadata.main_low_battery_threshold;
            case BluetoothDevice.METADATA_UNTETHERED_LEFT_LOW_BATTERY_THRESHOLD ->
                    publicMetadata.untethered_left_low_battery_threshold;
            case BluetoothDevice.METADATA_UNTETHERED_RIGHT_LOW_BATTERY_THRESHOLD ->
                    publicMetadata.untethered_right_low_battery_threshold;
            case BluetoothDevice.METADATA_UNTETHERED_CASE_LOW_BATTERY_THRESHOLD ->
                    publicMetadata.untethered_case_low_battery_threshold;
            case BluetoothDevice.METADATA_SPATIAL_AUDIO -> publicMetadata.spatial_audio;
            case BluetoothDevice.METADATA_FAST_PAIR_CUSTOMIZED_FIELDS ->
                    publicMetadata.fastpair_customized;
            case BluetoothDevice.METADATA_LE_AUDIO -> publicMetadata.le_audio;
            case BluetoothDevice.METADATA_GMCS_CCCD -> publicMetadata.gmcs_cccd;
            case BluetoothDevice.METADATA_GTBS_CCCD -> publicMetadata.gtbs_cccd;
            case BluetoothDevice.METADATA_EXCLUSIVE_MANAGER -> publicMetadata.exclusive_manager;
            case BluetoothDevice.METADATA_ZOOMED_IN_ICON -> publicMetadata.zoomed_in_icon;
            default -> null;
        };
    }
}
