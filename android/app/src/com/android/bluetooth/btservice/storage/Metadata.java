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

import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothA2dp.OptionalCodecsPreferenceStatus;
import android.bluetooth.BluetoothA2dp.OptionalCodecsSupportStatus;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUtils;

import androidx.annotation.NonNull;
import androidx.room.Embedded;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import java.util.ArrayList;
import java.util.List;

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

    Metadata(String address) {
        this(address, false, false);
    }

    private Metadata(String address, boolean isActiveA2dp, boolean isActiveHfp) {
        this.address = address;
        profileConnectionPolicies = new ProfilePrioritiesEntity();
        publicMetadata = new CustomizedMetadataEntity();
        a2dpSupportsOptionalCodecs = BluetoothA2dp.OPTIONAL_CODECS_SUPPORT_UNKNOWN;
        a2dpOptionalCodecsEnabled = BluetoothA2dp.OPTIONAL_CODECS_PREF_UNKNOWN;
        last_active_time = MetadataDatabase.sCurrentConnectionNumber++;
        is_active_a2dp_device = isActiveA2dp;
        isActiveHfpDevice = isActiveHfp;
        audioPolicyMetadata = new AudioPolicyEntity();
        preferred_output_only_profile = 0;
        preferred_duplex_profile = 0;
        active_audio_device_policy = BluetoothDevice.ACTIVE_AUDIO_DEVICE_POLICY_DEFAULT;
        is_preferred_microphone_for_calls = true;
        key_missing_count = 0;
    }

    static final class Builder {
        final String mAddress;
        boolean mIsActiveA2dpDevice = false;
        boolean mIsActiveHfpDevice = false;

        Builder(String address) {
            mAddress = address;
        }

        Builder setActiveA2dp() {
            mIsActiveA2dpDevice = true;
            return this;
        }

        Builder setActiveHfp() {
            mIsActiveHfpDevice = true;
            return this;
        }

        Metadata build() {
            return new Metadata(mAddress, mIsActiveA2dpDevice, mIsActiveHfpDevice);
        }
    }

    public String getAddress() {
        return address;
    }

    /**
     * Returns the anonymized hardware address. The first three octets will be suppressed for
     * anonymization.
     *
     * <p>For example, "XX:XX:XX:AA:BB:CC".
     *
     * @return Anonymized bluetooth hardware address as string
     */
    @NonNull
    public String getAnonymizedAddress() {
        return BluetoothUtils.toAnonymizedAddress(address);
    }

    void setProfileConnectionPolicy(int profile, int connectionPolicy) {
        // We no longer support BluetoothProfile.PRIORITY_AUTO_CONNECT and are merging it into
        // CONNECTION_POLICY_ALLOWED
        if (connectionPolicy > CONNECTION_POLICY_ALLOWED) {
            connectionPolicy = CONNECTION_POLICY_ALLOWED;
        }

        switch (profile) {
            case BluetoothProfile.A2DP ->
                    profileConnectionPolicies.a2dp_connection_policy = connectionPolicy;
            case BluetoothProfile.A2DP_SINK ->
                    profileConnectionPolicies.a2dp_sink_connection_policy = connectionPolicy;
            case BluetoothProfile.HEADSET ->
                    profileConnectionPolicies.hfp_connection_policy = connectionPolicy;
            case BluetoothProfile.HEADSET_CLIENT ->
                    profileConnectionPolicies.hfp_client_connection_policy = connectionPolicy;
            case BluetoothProfile.HID_HOST ->
                    profileConnectionPolicies.hid_host_connection_policy = connectionPolicy;
            case BluetoothProfile.PAN ->
                    profileConnectionPolicies.pan_connection_policy = connectionPolicy;
            case BluetoothProfile.PBAP ->
                    profileConnectionPolicies.pbap_connection_policy = connectionPolicy;
            case BluetoothProfile.PBAP_CLIENT ->
                    profileConnectionPolicies.pbap_client_connection_policy = connectionPolicy;
            case BluetoothProfile.MAP ->
                    profileConnectionPolicies.map_connection_policy = connectionPolicy;
            case BluetoothProfile.MAP_CLIENT ->
                    profileConnectionPolicies.map_client_connection_policy = connectionPolicy;
            case BluetoothProfile.SAP ->
                    profileConnectionPolicies.sap_connection_policy = connectionPolicy;
            case BluetoothProfile.HEARING_AID ->
                    profileConnectionPolicies.hearing_aid_connection_policy = connectionPolicy;
            case BluetoothProfile.HAP_CLIENT ->
                    profileConnectionPolicies.hap_client_connection_policy = connectionPolicy;
            case BluetoothProfile.LE_AUDIO ->
                    profileConnectionPolicies.le_audio_connection_policy = connectionPolicy;
            case BluetoothProfile.VOLUME_CONTROL ->
                    profileConnectionPolicies.volume_control_connection_policy = connectionPolicy;
            case BluetoothProfile.CSIP_SET_COORDINATOR ->
                    profileConnectionPolicies.csip_set_coordinator_connection_policy =
                            connectionPolicy;
            case BluetoothProfile.LE_CALL_CONTROL ->
                    profileConnectionPolicies.le_call_control_connection_policy = connectionPolicy;
            case BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT ->
                    profileConnectionPolicies.bass_client_connection_policy = connectionPolicy;
            case BluetoothProfile.BATTERY ->
                    profileConnectionPolicies.battery_connection_policy = connectionPolicy;
            default -> throw new IllegalArgumentException("invalid profile " + profile);
        }
    }

    public int getProfileConnectionPolicy(int profile) {
        return switch (profile) {
            case BluetoothProfile.A2DP -> profileConnectionPolicies.a2dp_connection_policy;
            case BluetoothProfile.A2DP_SINK ->
                    profileConnectionPolicies.a2dp_sink_connection_policy;
            case BluetoothProfile.HEADSET -> profileConnectionPolicies.hfp_connection_policy;
            case BluetoothProfile.HEADSET_CLIENT ->
                    profileConnectionPolicies.hfp_client_connection_policy;
            case BluetoothProfile.HID_HOST -> profileConnectionPolicies.hid_host_connection_policy;
            case BluetoothProfile.PAN -> profileConnectionPolicies.pan_connection_policy;
            case BluetoothProfile.PBAP -> profileConnectionPolicies.pbap_connection_policy;
            case BluetoothProfile.PBAP_CLIENT ->
                    profileConnectionPolicies.pbap_client_connection_policy;
            case BluetoothProfile.MAP -> profileConnectionPolicies.map_connection_policy;
            case BluetoothProfile.MAP_CLIENT ->
                    profileConnectionPolicies.map_client_connection_policy;
            case BluetoothProfile.SAP -> profileConnectionPolicies.sap_connection_policy;
            case BluetoothProfile.HEARING_AID ->
                    profileConnectionPolicies.hearing_aid_connection_policy;
            case BluetoothProfile.HAP_CLIENT ->
                    profileConnectionPolicies.hap_client_connection_policy;
            case BluetoothProfile.LE_AUDIO -> profileConnectionPolicies.le_audio_connection_policy;
            case BluetoothProfile.VOLUME_CONTROL ->
                    profileConnectionPolicies.volume_control_connection_policy;
            case BluetoothProfile.CSIP_SET_COORDINATOR ->
                    profileConnectionPolicies.csip_set_coordinator_connection_policy;
            case BluetoothProfile.LE_CALL_CONTROL ->
                    profileConnectionPolicies.le_call_control_connection_policy;
            case BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT ->
                    profileConnectionPolicies.bass_client_connection_policy;
            case BluetoothProfile.BATTERY -> profileConnectionPolicies.battery_connection_policy;
            default -> CONNECTION_POLICY_UNKNOWN;
        };
    }

    void setCustomizedMeta(int key, byte[] value) {
        switch (key) {
            case BluetoothDevice.METADATA_MANUFACTURER_NAME ->
                    publicMetadata.manufacturer_name = value;
            case BluetoothDevice.METADATA_MODEL_NAME -> publicMetadata.model_name = value;
            case BluetoothDevice.METADATA_SOFTWARE_VERSION ->
                    publicMetadata.software_version = value;
            case BluetoothDevice.METADATA_HARDWARE_VERSION ->
                    publicMetadata.hardware_version = value;
            case BluetoothDevice.METADATA_COMPANION_APP -> publicMetadata.companion_app = value;
            case BluetoothDevice.METADATA_MAIN_ICON -> publicMetadata.main_icon = value;
            case BluetoothDevice.METADATA_IS_UNTETHERED_HEADSET ->
                    publicMetadata.is_untethered_headset = value;
            case BluetoothDevice.METADATA_UNTETHERED_LEFT_ICON ->
                    publicMetadata.untethered_left_icon = value;
            case BluetoothDevice.METADATA_UNTETHERED_RIGHT_ICON ->
                    publicMetadata.untethered_right_icon = value;
            case BluetoothDevice.METADATA_UNTETHERED_CASE_ICON ->
                    publicMetadata.untethered_case_icon = value;
            case BluetoothDevice.METADATA_UNTETHERED_LEFT_BATTERY ->
                    publicMetadata.untethered_left_battery = value;
            case BluetoothDevice.METADATA_UNTETHERED_RIGHT_BATTERY ->
                    publicMetadata.untethered_right_battery = value;
            case BluetoothDevice.METADATA_UNTETHERED_CASE_BATTERY ->
                    publicMetadata.untethered_case_battery = value;
            case BluetoothDevice.METADATA_UNTETHERED_LEFT_CHARGING ->
                    publicMetadata.untethered_left_charging = value;
            case BluetoothDevice.METADATA_UNTETHERED_RIGHT_CHARGING ->
                    publicMetadata.untethered_right_charging = value;
            case BluetoothDevice.METADATA_UNTETHERED_CASE_CHARGING ->
                    publicMetadata.untethered_case_charging = value;
            case BluetoothDevice.METADATA_ENHANCED_SETTINGS_UI_URI ->
                    publicMetadata.enhanced_settings_ui_uri = value;
            case BluetoothDevice.METADATA_DEVICE_TYPE -> publicMetadata.device_type = value;
            case BluetoothDevice.METADATA_MAIN_BATTERY -> publicMetadata.main_battery = value;
            case BluetoothDevice.METADATA_MAIN_CHARGING -> publicMetadata.main_charging = value;
            case BluetoothDevice.METADATA_MAIN_LOW_BATTERY_THRESHOLD ->
                    publicMetadata.main_low_battery_threshold = value;
            case BluetoothDevice.METADATA_UNTETHERED_LEFT_LOW_BATTERY_THRESHOLD ->
                    publicMetadata.untethered_left_low_battery_threshold = value;
            case BluetoothDevice.METADATA_UNTETHERED_RIGHT_LOW_BATTERY_THRESHOLD ->
                    publicMetadata.untethered_right_low_battery_threshold = value;
            case BluetoothDevice.METADATA_UNTETHERED_CASE_LOW_BATTERY_THRESHOLD ->
                    publicMetadata.untethered_case_low_battery_threshold = value;
            case BluetoothDevice.METADATA_SPATIAL_AUDIO -> publicMetadata.spatial_audio = value;
            case BluetoothDevice.METADATA_FAST_PAIR_CUSTOMIZED_FIELDS ->
                    publicMetadata.fastpair_customized = value;
            case BluetoothDevice.METADATA_LE_AUDIO -> publicMetadata.le_audio = value;
            case BluetoothDevice.METADATA_GMCS_CCCD -> publicMetadata.gmcs_cccd = value;
            case BluetoothDevice.METADATA_GTBS_CCCD -> publicMetadata.gtbs_cccd = value;
            case BluetoothDevice.METADATA_EXCLUSIVE_MANAGER ->
                    publicMetadata.exclusive_manager = value;
            default -> {} // Nothing to do
        }
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
            default -> null;
        };
    }

    List<Integer> getChangedCustomizedMeta() {
        List<Integer> list = new ArrayList<>();
        for (int key = 0; key <= BluetoothDevice.getMaxMetadataKey(); key++) {
            if (getCustomizedMeta(key) != null) {
                list.add(key);
            }
        }
        return list;
    }

    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append(getAnonymizedAddress())
                .append(" last_active_time=")
                .append(last_active_time)
                .append(" {profile connection policy(")
                .append(profileConnectionPolicies)
                .append("), optional codec(support=")
                .append(a2dpSupportsOptionalCodecs)
                .append("|enabled=")
                .append(a2dpOptionalCodecsEnabled)
                .append("), isActiveHfpDevice (")
                .append(isActiveHfpDevice)
                .append("), custom metadata(")
                .append(publicMetadata)
                .append("), hfp client audio policy(")
                .append(audioPolicyMetadata)
                .append("), is_preferred_microphone_for_calls(")
                .append(is_preferred_microphone_for_calls)
                .append(")}");

        return builder.toString();
    }
}
