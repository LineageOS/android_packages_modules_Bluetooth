/*
 * Copyright 2020 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
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

package android.bluetooth;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothLeAudioCodecConfig;
import android.bluetooth.BluetoothLeAudioCodecStatus;
import android.bluetooth.BluetoothLeAudioContentMetadata;
import android.bluetooth.BluetoothLeBroadcastMetadata;
import android.bluetooth.BluetoothLeBroadcastSettings;
import android.bluetooth.IBluetoothLeAudioCallback;
import android.bluetooth.IBluetoothLeBroadcastCallback;
import android.content.AttributionSource;

import android.os.ParcelUuid;

/**
 * APIs for Bluetooth LE Audio service
 *
 * @hide
 */
interface IBluetoothLeAudio {
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    boolean connect(in BluetoothDevice device, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    boolean disconnect(in BluetoothDevice device, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    List<BluetoothDevice> getConnectedDevices(in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    List<BluetoothDevice> getDevicesMatchingConnectionStates(in int[] states, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    int getConnectionState(in BluetoothDevice device, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    boolean setActiveDevice(in BluetoothDevice device, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    List<BluetoothDevice> getActiveDevices(in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
    boolean setConnectionPolicy(in BluetoothDevice device, int connectionPolicy, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    int getConnectionPolicy(in BluetoothDevice device, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    BluetoothDevice getConnectedGroupLeadDevice(int groupId, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    BluetoothLeAudioCodecStatus getCodecStatus(in int groupId, in AttributionSource source);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    void setCodecConfigPreference(in int groupId, in BluetoothLeAudioCodecConfig inputCodecConfig, in BluetoothLeAudioCodecConfig outputCodecConfig, in AttributionSource source);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
    void registerCallback(in IBluetoothLeAudioCallback callback, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
    void unregisterCallback(in IBluetoothLeAudioCallback callback, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
    void setCcidInformation(in ParcelUuid userUuid, in int ccid, in int contextType, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
    void setInCall(in boolean inCall, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
    void setInactiveForHfpHandover(in BluetoothDevice device, in AttributionSource attributionSource);

    /* Same value as bluetooth::groups::kGroupUnknown */
    const int LE_AUDIO_GROUP_ID_INVALID = -1;

    const int GROUP_STATUS_INACTIVE = 0;
    const int GROUP_STATUS_ACTIVE = 1;
    const int GROUP_STATUS_TURNED_IDLE_DURING_CALL = 2;

    const int GROUP_NODE_ADDED = 1;
    const int GROUP_NODE_REMOVED = 2;

    const int GROUP_STREAM_STATUS_IDLE = 0;
    const int GROUP_STREAM_STATUS_STREAMING = 1;

    /**
     * Get device group id. Devices with same group id belong to same group (i.e left and right
     * earbud)
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)")
    int getGroupId(in BluetoothDevice device, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
    oneway void setVolume(int volume, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
    boolean groupAddNode(int group_id, in BluetoothDevice device, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
    boolean groupRemoveNode(int group_id, in BluetoothDevice device, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
    int getAudioLocation(in BluetoothDevice device, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
    boolean isInbandRingtoneEnabled(in AttributionSource attributionSource, int groupId);

    // Broadcaster API
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
    void registerLeBroadcastCallback(in IBluetoothLeBroadcastCallback callback, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
    void unregisterLeBroadcastCallback(in IBluetoothLeBroadcastCallback callback, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
    void startBroadcast(in BluetoothLeBroadcastSettings broadcastSettings, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
    void stopBroadcast(int broadcastId, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
    void updateBroadcast(int broadcastId, in BluetoothLeBroadcastSettings broadcastSettings, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT})")
    boolean isPlaying(int broadcastId, in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
    List<BluetoothLeBroadcastMetadata> getAllBroadcastMetadata(in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT})")
    int getMaximumNumberOfBroadcasts(in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT})")
    int getMaximumStreamsPerBroadcast(in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT})")
    int getMaximumSubgroupsPerBroadcast(in AttributionSource attributionSource);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(allOf={android.Manifest.permission.BLUETOOTH_CONNECT,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
    boolean isBroadcastActive(in AttributionSource attributionSource);
}
