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

package com.android.bluetooth.le_audio;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.IBluetoothLeAudio.LE_AUDIO_GROUP_ID_INVALID;

import static java.util.Objects.requireNonNull;

import android.annotation.RequiresPermission;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothLeAudio;
import android.bluetooth.BluetoothLeAudioCodecConfig;
import android.bluetooth.BluetoothLeAudioCodecStatus;
import android.bluetooth.BluetoothLeBroadcastMetadata;
import android.bluetooth.BluetoothLeBroadcastSettings;
import android.bluetooth.IBluetoothLeAudio;
import android.bluetooth.IBluetoothLeAudioCallback;
import android.bluetooth.IBluetoothLeBroadcastCallback;
import android.content.AttributionSource;
import android.os.ParcelUuid;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.ProfileService.IProfileServiceBinder;

import java.util.Collections;
import java.util.List;

class LeAudioServiceBinder extends IBluetoothLeAudio.Stub implements IProfileServiceBinder {
    private static final String TAG = LeAudioServiceBinder.class.getSimpleName();

    private LeAudioService mService;

    LeAudioServiceBinder(LeAudioService svc) {
        mService = svc;
    }

    @Override
    public void cleanup() {
        mService = null;
    }

    private LeAudioService getService() {
        LeAudioService service = mService;

        if (Utils.isInstrumentationTestMode()) {
            return service;
        }

        if (!Utils.checkServiceAvailable(service, TAG)
                || !Utils.checkCallerIsSystemOrActiveOrManagedUser(service, TAG)) {
            return null;
        }
        return service;
    }

    @RequiresPermission(BLUETOOTH_CONNECT)
    private LeAudioService getServiceAndEnforceConnect(AttributionSource source) {
        requireNonNull(source);
        LeAudioService service = mService;

        if (Utils.isInstrumentationTestMode()) {
            return service;
        }

        if (!Utils.checkServiceAvailable(service, TAG)
                || !Utils.checkCallerIsSystemOrActiveOrManagedUser(service, TAG)
                || !Utils.checkConnectPermissionForDataDelivery(service, source, TAG)) {
            return null;
        }
        return service;
    }

    @Override
    public boolean connect(BluetoothDevice device, AttributionSource source) {
        requireNonNull(device);
        requireNonNull(source);

        LeAudioService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return false;
        }

        return service.connect(device);
    }

    @Override
    public boolean disconnect(BluetoothDevice device, AttributionSource source) {
        requireNonNull(device);
        requireNonNull(source);

        LeAudioService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return false;
        }
        return service.disconnect(device);
    }

    @Override
    public List<BluetoothDevice> getConnectedDevices(AttributionSource source) {
        requireNonNull(source);

        LeAudioService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return Collections.emptyList();
        }
        return service.getConnectedDevices();
    }

    @Override
    public BluetoothDevice getConnectedGroupLeadDevice(int groupId, AttributionSource source) {
        requireNonNull(source);

        LeAudioService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return null;
        }
        return service.getConnectedGroupLeadDevice(groupId);
    }

    @Override
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(
            int[] states, AttributionSource source) {
        requireNonNull(source);

        LeAudioService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return Collections.emptyList();
        }
        return service.getDevicesMatchingConnectionStates(states);
    }

    @Override
    public int getConnectionState(BluetoothDevice device, AttributionSource source) {
        requireNonNull(device);
        requireNonNull(source);

        LeAudioService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return STATE_DISCONNECTED;
        }
        return service.getConnectionState(device);
    }

    @Override
    public boolean setActiveDevice(BluetoothDevice device, AttributionSource source) {
        requireNonNull(source);

        LeAudioService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return false;
        }

        if (device == null) {
            return service.removeActiveDevice(true);
        } else {
            return service.setActiveDevice(device);
        }
    }

    @Override
    public List<BluetoothDevice> getActiveDevices(AttributionSource source) {
        requireNonNull(source);

        LeAudioService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return Collections.emptyList();
        }
        return service.getActiveDevices();
    }

    @Override
    public int getAudioLocation(BluetoothDevice device, AttributionSource source) {
        requireNonNull(device);
        requireNonNull(source);

        LeAudioService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return BluetoothLeAudio.AUDIO_LOCATION_INVALID;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getAudioLocation(device);
    }

    @Override
    public boolean isInbandRingtoneEnabled(AttributionSource source, int groupId) {
        requireNonNull(source);

        LeAudioService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return false;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.isInbandRingtoneEnabled(groupId);
    }

    @Override
    public boolean setConnectionPolicy(
            BluetoothDevice device, int connectionPolicy, AttributionSource source) {
        requireNonNull(device);
        requireNonNull(source);

        LeAudioService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return false;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.setConnectionPolicy(device, connectionPolicy);
    }

    @Override
    public int getConnectionPolicy(BluetoothDevice device, AttributionSource source) {
        requireNonNull(device);
        requireNonNull(source);

        LeAudioService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return CONNECTION_POLICY_UNKNOWN;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getConnectionPolicy(device);
    }

    @Override
    public void setCcidInformation(
            ParcelUuid userUuid, int ccid, int contextType, AttributionSource source) {
        requireNonNull(userUuid);
        requireNonNull(source);

        LeAudioService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        service.setCcidInformation(userUuid, ccid, contextType);
    }

    @Override
    public int getGroupId(BluetoothDevice device, AttributionSource source) {
        requireNonNull(device);
        requireNonNull(source);

        LeAudioService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return LE_AUDIO_GROUP_ID_INVALID;
        }
        return service.getGroupId(device);
    }

    @Override
    public boolean groupAddNode(int groupId, BluetoothDevice device, AttributionSource source) {
        requireNonNull(device);
        requireNonNull(source);

        LeAudioService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return false;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.groupAddNode(groupId, device);
    }

    @Override
    public void setInCall(boolean inCall, AttributionSource source) {
        requireNonNull(source);

        LeAudioService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        service.setInCall(inCall);
    }

    @Override
    public void setInactiveForHfpHandover(
            BluetoothDevice hfpHandoverDevice, AttributionSource source) {
        requireNonNull(source);

        LeAudioService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        service.setInactiveForHfpHandover(hfpHandoverDevice);
    }

    @Override
    public boolean groupRemoveNode(int groupId, BluetoothDevice device, AttributionSource source) {
        requireNonNull(device);
        requireNonNull(source);

        LeAudioService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return false;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.groupRemoveNode(groupId, device);
    }

    @Override
    public void setVolume(int volume, AttributionSource source) {
        requireNonNull(source);

        LeAudioService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        service.setVolume(volume);
    }

    @Override
    public void registerCallback(IBluetoothLeAudioCallback callback, AttributionSource source) {
        requireNonNull(callback);
        requireNonNull(source);

        LeAudioService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        service.registerCallback(callback);
    }

    @Override
    public void unregisterCallback(IBluetoothLeAudioCallback callback, AttributionSource source) {
        requireNonNull(callback);
        requireNonNull(source);

        LeAudioService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        service.unregisterCallback(callback);
    }

    @Override
    public void registerLeBroadcastCallback(
            IBluetoothLeBroadcastCallback callback, AttributionSource source) {
        requireNonNull(callback);
        requireNonNull(source);

        LeAudioService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        service.registerLeBroadcastCallback(callback);
    }

    @Override
    public void unregisterLeBroadcastCallback(
            IBluetoothLeBroadcastCallback callback, AttributionSource source) {
        requireNonNull(callback);
        requireNonNull(source);

        LeAudioService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        service.unregisterLeBroadcastCallback(callback);
    }

    @Override
    public void startBroadcast(
            BluetoothLeBroadcastSettings broadcastSettings, AttributionSource source) {
        LeAudioService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        service.createBroadcast(broadcastSettings);
    }

    @Override
    public void stopBroadcast(int broadcastId, AttributionSource source) {
        LeAudioService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        service.stopBroadcast(broadcastId);
    }

    @Override
    public void updateBroadcast(
            int broadcastId,
            BluetoothLeBroadcastSettings broadcastSettings,
            AttributionSource source) {
        LeAudioService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        service.updateBroadcast(broadcastId, broadcastSettings);
    }

    @Override
    public boolean isPlaying(int broadcastId, AttributionSource source) {
        LeAudioService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return false;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.isPlaying(broadcastId);
    }

    @Override
    public List<BluetoothLeBroadcastMetadata> getAllBroadcastMetadata(AttributionSource source) {
        LeAudioService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return Collections.emptyList();
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getAllBroadcastMetadata();
    }

    @Override
    public int getMaximumNumberOfBroadcasts() {
        LeAudioService service = getService();
        if (service == null) {
            return 0;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getMaximumNumberOfBroadcasts();
    }

    @Override
    public int getMaximumStreamsPerBroadcast() {
        LeAudioService service = getService();
        if (service == null) {
            return 0;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getMaximumStreamsPerBroadcast();
    }

    @Override
    public int getMaximumSubgroupsPerBroadcast() {
        LeAudioService service = getService();
        if (service == null) {
            return 0;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getMaximumSubgroupsPerBroadcast();
    }

    @Override
    public BluetoothLeAudioCodecStatus getCodecStatus(int groupId, AttributionSource source) {
        LeAudioService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return null;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getCodecStatus(groupId);
    }

    @Override
    public void setCodecConfigPreference(
            int groupId,
            BluetoothLeAudioCodecConfig inputCodecConfig,
            BluetoothLeAudioCodecConfig outputCodecConfig,
            AttributionSource source) {
        LeAudioService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        service.setCodecConfigPreference(groupId, inputCodecConfig, outputCodecConfig);
    }

    @Override
    public void setBroadcastToUnicastFallbackGroup(int groupId, AttributionSource source) {
        LeAudioService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        service.setBroadcastToUnicastFallbackGroup(groupId);
    }

    @Override
    public int getBroadcastToUnicastFallbackGroup(AttributionSource source) {
        LeAudioService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return LE_AUDIO_GROUP_ID_INVALID;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.getBroadcastToUnicastFallbackGroup();
    }

    @Override
    public boolean isBroadcastActive(AttributionSource source) {
        LeAudioService service = getServiceAndEnforceConnect(source);
        if (service == null) {
            return false;
        }

        service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
        return service.isBroadcastActive();
    }
}
