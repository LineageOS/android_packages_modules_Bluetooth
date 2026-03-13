/*
 * Copyright 2021 HIMSA II K/S - www.himsa.com.
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

/*
 * Defines the native interface that is used by state machine/service to
 * send or receive messages from the native stack. This file is registered
 * for the native methods in the corresponding JNI C++ file.
 */

package com.android.bluetooth.le_audio;

import static java.util.Objects.requireNonNull;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothLeBroadcastMetadata;
import android.util.Log;

import com.android.bluetooth.Util;
import com.android.bluetooth.Utils;
import com.android.bluetooth.bass_client.BassClientService.SetBigChannelMapClassificationAction;
import com.android.bluetooth.btservice.AdapterService;

/** LeAudio Native Interface to/from JNI. */
public class LeAudioBroadcasterNativeInterface {
    private static final String TAG = LeAudioBroadcasterNativeInterface.class.getSimpleName();
    private static final byte[] EMPTY_ADDRESS_BYTES = new byte[] {0, 0, 0, 0, 0, 0};

    private final AdapterService mAdapterService;
    private final LeAudioService mService;

    LeAudioBroadcasterNativeInterface(AdapterService adapterService, LeAudioService service) {
        mAdapterService = requireNonNull(adapterService);
        mService = requireNonNull(service);
    }

    BluetoothDevice getDevice(byte[] address) {
        return mAdapterService.getRemoteDevice(Utils.getAddressStringFromByte(address));
    }

    // Callbacks from the native stack back into the Java framework.
    void onBroadcastCreated(int broadcastId, boolean success) {
        Log.d(TAG, "onBroadcastCreated: broadcastId=" + broadcastId);
        final var event = new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_CREATED);

        event.valueInt1 = broadcastId;
        event.valueBool1 = success;
        mService.messageFromNative(event);
    }

    void onBroadcastDestroyed(int broadcastId) {
        Log.d(TAG, "onBroadcastDestroyed: broadcastId=" + broadcastId);
        final var event = new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_DESTROYED);

        event.valueInt1 = broadcastId;
        mService.messageFromNative(event);
    }

    void onBroadcastStateChanged(int broadcastId, int state) {
        Log.d(TAG, "onBroadcastStateChanged: broadcastId=" + broadcastId + " state=" + state);
        final var event = new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_STATE);

        /* NOTICE: This is a fake device to satisfy Audio Manager in the upper
         * layers which needs a device instance to route audio streams to the
         * proper module (here it's Bluetooth). Broadcast has no concept of a
         * destination or peer device therefore this fake device was created.
         * For now it's only important that this device is a Bluetooth device.
         */
        event.device = getDevice(Util.getBytesFromAddress("FF:FF:FF:FF:FF:FF"));
        event.valueInt1 = broadcastId;
        event.valueInt2 = state;
        mService.messageFromNative(event);
    }

    void onBroadcastMetadataChanged(int broadcastId, BluetoothLeBroadcastMetadata metadata) {
        Log.d(TAG, "onBroadcastMetadataChanged: broadcastId=" + broadcastId);
        final var event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_METADATA_CHANGED);

        event.valueInt1 = broadcastId;
        event.broadcastMetadata = metadata;
        mService.messageFromNative(event);
    }

    void onBroadcastAudioSessionCreated(boolean success) {
        Log.d(TAG, "onBroadcastAudioSessionCreated: success=" + success);
        final var event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_AUDIO_SESSION_CREATED);

        event.valueBool1 = success;
        mService.messageFromNative(event);
    }

    /**
     * Initializes the native interface.
     *
     * <p>priorities to configure.
     */
    void init() {
        initNative();
    }

    /** Stop the Broadcast Service. */
    void stop() {
        stopNative();
    }

    /** Cleanup the native interface. */
    void cleanup() {
        cleanupNative();
    }

    /**
     * Creates LeAudio Broadcast instance.
     *
     * @param isPublicBroadcast this BIG is public broadcast
     * @param broadcastName BIG broadcast name
     * @param broadcastCode BIG broadcast code
     * @param publicMetadata BIG public broadcast meta data
     * @param qualityArray BIG sub group audio quality array
     * @param metadataArray BIG sub group metadata array
     *     <p>qualityArray and metadataArray use the same subgroup index
     */
    void createBroadcast(
            boolean isPublicBroadcast,
            String broadcastName,
            byte[] broadcastCode,
            byte[] publicMetadata,
            int[] qualityArray,
            byte[][] metadataArray) {
        createBroadcastNative(
                isPublicBroadcast,
                broadcastName,
                broadcastCode,
                publicMetadata,
                qualityArray,
                metadataArray);
    }

    /**
     * Update LeAudio Broadcast instance metadata.
     *
     * @param broadcastId broadcast instance identifier
     * @param broadcastName BIG broadcast name
     * @param publicMetadata BIG public broadcast meta data
     * @param metadataArray BIG sub group metadata array
     */
    void updateMetadata(
            int broadcastId, String broadcastName, byte[] publicMetadata, byte[][] metadataArray) {
        updateMetadataNative(broadcastId, broadcastName, publicMetadata, metadataArray);
    }

    /**
     * Start LeAudio Broadcast instance.
     *
     * @param broadcastId broadcast instance identifier
     */
    void startBroadcast(int broadcastId) {
        startBroadcastNative(broadcastId);
    }

    /**
     * Stop LeAudio Broadcast instance.
     *
     * @param broadcastId broadcast instance identifier
     */
    void stopBroadcast(int broadcastId) {
        stopBroadcastNative(broadcastId);
    }

    /**
     * Pause LeAudio Broadcast instance.
     *
     * @param broadcastId broadcast instance identifier
     */
    void pauseBroadcast(int broadcastId) {
        pauseBroadcastNative(broadcastId);
    }

    /**
     * Destroy LeAudio Broadcast instance.
     *
     * @param broadcastId broadcast instance identifier
     */
    void destroyBroadcast(int broadcastId) {
        destroyBroadcastNative(broadcastId);
    }

    /** Get all LeAudio Broadcast instance states. */
    void getBroadcastMetadata(int broadcastId) {
        getBroadcastMetadataNative(broadcastId);
    }

    /**
     * Sends parameters to native stack to set the BIG Channel Map by map classification of sink.
     * This method calls the corresponding private native method.
     *
     * @param action The action for set BIG channel map classification.
     * @param sink The Bluetooth device of the sink device.
     * @param broadcastId The Broadcast ID.
     */
    void setBigChannelMapClassification(int action, BluetoothDevice sink, int broadcastId) {
        if (action == SetBigChannelMapClassificationAction.NO_ACTION.getValue()) {
            Log.e(TAG, "NO_ACTION for SetBigChannelMapClassification");
            return;
        }

        if (action != SetBigChannelMapClassificationAction.CLEAR.getValue() && sink == null) {
            Log.e(
                    TAG,
                    "Action "
                            + SetBigChannelMapClassificationAction.toString(action)
                            + " requires a non-null sink device, but sink is null.");
            return;
        }

        byte[] sinkAddr;
        if (action == SetBigChannelMapClassificationAction.CLEAR.getValue()) {
            sinkAddr = EMPTY_ADDRESS_BYTES;
        } else {
            sinkAddr = Util.getByteAddress(sink);
        }
        setBigChannelMapClassificationNative(action, sinkAddr, broadcastId);
    }

    // Native methods that call into the JNI interface
    private native void initNative();

    private native void stopNative();

    private native void cleanupNative();

    private native void createBroadcastNative(
            boolean isPublicBroadcast,
            String broadcastName,
            byte[] broadcastCode,
            byte[] publicMetadata,
            int[] qualityArray,
            byte[][] metadataArray);

    private native void updateMetadataNative(
            int broadcastId, String broadcastName, byte[] publicMetadata, byte[][] metadataArray);

    private native void startBroadcastNative(int broadcastId);

    private native void stopBroadcastNative(int broadcastId);

    private native void pauseBroadcastNative(int broadcastId);

    private native void destroyBroadcastNative(int broadcastId);

    private native void getBroadcastMetadataNative(int broadcastId);

    private native void setBigChannelMapClassificationNative(
            int action, byte[] sinkAddr, int broadcastId);
}
