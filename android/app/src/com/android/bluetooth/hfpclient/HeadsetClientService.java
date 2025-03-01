/*
 * Copyright (c) 2014 The Android Open Source Project
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

package com.android.bluetooth.hfpclient;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.content.pm.PackageManager.FEATURE_WATCH;

import static java.util.Objects.requireNonNull;

import android.annotation.RequiresPermission;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadsetClient;
import android.bluetooth.BluetoothHeadsetClientCall;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSinkAudioPolicy;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.IBluetoothHeadsetClient;
import android.content.AttributionSource;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.Message;
import android.os.SystemProperties;
import android.sysprop.BluetoothProperties;
import android.util.Log;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.ProfileService;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Provides Bluetooth Headset Client (HF Role) profile, as a service in the Bluetooth application.
 */
public class HeadsetClientService extends ProfileService {
    private static final String TAG = HeadsetClientService.class.getSimpleName();

    // Maximum number of devices we can try connecting to in one session
    private static final int MAX_STATE_MACHINES_POSSIBLE = 100;

    @VisibleForTesting static final int MAX_HFP_SCO_VOICE_CALL_VOLUME = 15; // HFP 1.5 spec.
    @VisibleForTesting static final int MIN_HFP_SCO_VOICE_CALL_VOLUME = 1; // HFP 1.5 spec.

    // This is also used as a lock for shared data in {@link HeadsetClientService}
    @GuardedBy("mStateMachineMap")
    private final HashMap<BluetoothDevice, HeadsetClientStateMachine> mStateMachineMap =
            new HashMap<>();

    private static HeadsetClientService sHeadsetClientService;

    private final HandlerThread mSmThread;
    private final AdapterService mAdapterService;
    private final DatabaseManager mDatabaseManager;
    private final AudioManager mAudioManager;
    private final NativeInterface mNativeInterface;
    private final BatteryManager mBatteryManager;
    private final HeadsetClientStateMachineFactory mSmFactory;
    private final int mMaxAmVcVol;
    private final int mMinAmVcVol;

    private int mLastBatteryLevel = -1;

    public static final String HFP_CLIENT_STOP_TAG = "hfp_client_stop_tag";

    public HeadsetClientService(AdapterService adapterService) {
        super(requireNonNull(adapterService));
        mAdapterService = adapterService;
        mDatabaseManager = requireNonNull(adapterService.getDatabase());
        mAudioManager = requireNonNull(getSystemService(AudioManager.class));
        mMaxAmVcVol = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
        mMinAmVcVol = mAudioManager.getStreamMinVolume(AudioManager.STREAM_VOICE_CALL);

        // Setup the JNI service
        mNativeInterface = NativeInterface.getInstance();
        mNativeInterface.initialize();

        mBatteryManager = getSystemService(BatteryManager.class);

        // start AudioManager in a known state
        mAudioManager.setHfpEnabled(false);

        mSmFactory = new HeadsetClientStateMachineFactory();
        synchronized (mStateMachineMap) {
            mStateMachineMap.clear();
        }

        IntentFilter filter = new IntentFilter(AudioManager.ACTION_VOLUME_CHANGED);
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        registerReceiver(mBroadcastReceiver, filter);

        // Start the HfpClientConnectionService to create connection with telecom when HFP
        // connection is available on non-wearable device.
        if (getPackageManager() != null && !getPackageManager().hasSystemFeature(FEATURE_WATCH)) {
            Intent startIntent = new Intent(this, HfpClientConnectionService.class);
            startService(startIntent);
        }

        // Create the thread on which all State Machines will run
        mSmThread = new HandlerThread("HeadsetClient.SM");
        mSmThread.start();

        setHeadsetClientService(this);
    }

    public static boolean isEnabled() {
        return BluetoothProperties.isProfileHfpHfEnabled().orElse(false);
    }

    @Override
    public IProfileServiceBinder initBinder() {
        return new BluetoothHeadsetClientBinder(this);
    }

    @Override
    public void cleanup() {
        Log.i(TAG, "Cleanup Headset Client Service");

        synchronized (HeadsetClientService.class) {
            if (sHeadsetClientService == null) {
                Log.w(TAG, "cleanup() called before initialization");
                return;
            }

            // Stop the HfpClientConnectionService for non-wearables devices.
            if (getPackageManager() != null
                    && !getPackageManager().hasSystemFeature(FEATURE_WATCH)) {
                Intent stopIntent = new Intent(this, HfpClientConnectionService.class);
                sHeadsetClientService.stopService(stopIntent);
            }
        }

        setHeadsetClientService(null);

        unregisterReceiver(mBroadcastReceiver);

        synchronized (mStateMachineMap) {
            for (HeadsetClientStateMachine sm : mStateMachineMap.values()) {
                sm.doQuit();
            }
            mStateMachineMap.clear();
        }

        // Stop the handler thread
        mSmThread.quit();

        mNativeInterface.cleanup();
    }

    int hfToAmVol(int hfVol) {
        int amRange = mMaxAmVcVol - mMinAmVcVol;
        int hfRange = MAX_HFP_SCO_VOICE_CALL_VOLUME - MIN_HFP_SCO_VOICE_CALL_VOLUME;
        int amVol =
                (int)
                                Math.round(
                                        (hfVol - MIN_HFP_SCO_VOICE_CALL_VOLUME)
                                                * ((double) amRange / hfRange))
                        + mMinAmVcVol;
        Log.d(TAG, "HF -> AM " + hfVol + " " + amVol);
        return amVol;
    }

    int amToHfVol(int amVol) {
        int amRange = (mMaxAmVcVol > mMinAmVcVol) ? (mMaxAmVcVol - mMinAmVcVol) : 1;
        int hfRange = MAX_HFP_SCO_VOICE_CALL_VOLUME - MIN_HFP_SCO_VOICE_CALL_VOLUME;
        int hfVol =
                (int) Math.round((amVol - mMinAmVcVol) * ((double) hfRange / amRange))
                        + MIN_HFP_SCO_VOICE_CALL_VOLUME;
        Log.d(TAG, "AM -> HF " + amVol + " " + hfVol);
        return hfVol;
    }

    private final BroadcastReceiver mBroadcastReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();

                    // We handle the volume changes for Voice calls here since HFP audio volume
                    // control does
                    // not go through audio manager (audio mixer). see
                    // ({@link HeadsetClientStateMachine#SET_SPEAKER_VOLUME} in
                    // {@link HeadsetClientStateMachine} for details.
                    if (action.equals(AudioManager.ACTION_VOLUME_CHANGED)) {
                        Log.d(
                                TAG,
                                "Volume changed for stream: "
                                        + intent.getIntExtra(
                                                AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1));
                        int streamType =
                                intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1);
                        if (streamType == AudioManager.STREAM_VOICE_CALL) {
                            int streamValue =
                                    intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_VALUE, -1);
                            int hfVol = amToHfVol(streamValue);
                            Log.d(
                                    TAG,
                                    "Setting volume to audio manager: "
                                            + streamValue
                                            + " hands free: "
                                            + hfVol);
                            mAudioManager.setHfpVolume(hfVol);
                            synchronized (mStateMachineMap) {
                                for (HeadsetClientStateMachine sm : mStateMachineMap.values()) {
                                    if (sm != null) {
                                        sm.sendMessage(
                                                HeadsetClientStateMachine.SET_SPEAKER_VOLUME,
                                                streamValue);
                                    }
                                }
                            }
                        }
                    } else if (action.equals(Intent.ACTION_BATTERY_CHANGED)) {
                        int batteryIndicatorID = 2;
                        int batteryLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);

                        if (batteryLevel == mLastBatteryLevel) {
                            return;
                        }
                        mLastBatteryLevel = batteryLevel;

                        Log.d(
                                TAG,
                                "Send battery level update BIEV(2," + batteryLevel + ") command");

                        synchronized (mStateMachineMap) {
                            for (HeadsetClientStateMachine sm : mStateMachineMap.values()) {
                                if (sm != null) {
                                    sm.sendMessage(
                                            HeadsetClientStateMachine.SEND_BIEV,
                                            batteryIndicatorID,
                                            batteryLevel);
                                }
                            }
                        }
                    }
                }
            };

    /**
     * Convert {@code HfpClientCall} to legacy {@code BluetoothHeadsetClientCall} still used by some
     * clients.
     */
    static BluetoothHeadsetClientCall toLegacyCall(HfpClientCall call) {
        if (call == null) return null;
        return new BluetoothHeadsetClientCall(
                call.getDevice(),
                call.getId(),
                call.getUUID(),
                call.getState(),
                call.getNumber(),
                call.isMultiParty(),
                call.isOutgoing(),
                call.isInBandRing());
    }

    /** Handlers for incoming service calls */
    @VisibleForTesting
    static class BluetoothHeadsetClientBinder extends IBluetoothHeadsetClient.Stub
            implements IProfileServiceBinder {
        private HeadsetClientService mService;

        BluetoothHeadsetClientBinder(HeadsetClientService svc) {
            mService = svc;
        }

        @Override
        public void cleanup() {
            mService = null;
        }

        @RequiresPermission(BLUETOOTH_CONNECT)
        private HeadsetClientService getService(AttributionSource source) {
            // Cache mService because it can change while getService is called
            HeadsetClientService service = mService;

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
            HeadsetClientService service = getService(source);
            if (service == null) {
                return false;
            }

            return service.connect(device);
        }

        @Override
        public boolean disconnect(BluetoothDevice device, AttributionSource source) {
            HeadsetClientService service = getService(source);
            if (service == null) {
                return false;
            }

            return service.disconnect(device);
        }

        @Override
        public List<BluetoothDevice> getConnectedDevices(AttributionSource source) {
            HeadsetClientService service = getService(source);
            if (service == null) {
                return Collections.emptyList();
            }

            service.enforceCallingPermission(BLUETOOTH_PRIVILEGED, null);

            return service.getConnectedDevices();
        }

        @Override
        public List<BluetoothDevice> getDevicesMatchingConnectionStates(
                int[] states, AttributionSource source) {
            HeadsetClientService service = getService(source);
            if (service == null) {
                return Collections.emptyList();
            }

            service.enforceCallingPermission(BLUETOOTH_PRIVILEGED, null);

            return service.getDevicesMatchingConnectionStates(states);
        }

        @Override
        public int getConnectionState(BluetoothDevice device, AttributionSource source) {
            HeadsetClientService service = getService(source);
            if (service == null) {
                return STATE_DISCONNECTED;
            }

            service.enforceCallingPermission(BLUETOOTH_PRIVILEGED, null);

            return service.getConnectionState(device);
        }

        @Override
        public boolean setConnectionPolicy(
                BluetoothDevice device, int connectionPolicy, AttributionSource source) {
            HeadsetClientService service = getService(source);
            if (service == null) {
                return false;
            }

            service.enforceCallingPermission(BLUETOOTH_PRIVILEGED, null);

            return service.setConnectionPolicy(device, connectionPolicy);
        }

        @Override
        public int getConnectionPolicy(BluetoothDevice device, AttributionSource source) {
            HeadsetClientService service = getService(source);
            if (service == null) {
                return CONNECTION_POLICY_UNKNOWN;
            }

            service.enforceCallingPermission(BLUETOOTH_PRIVILEGED, null);

            return service.getConnectionPolicy(device);
        }

        @Override
        public boolean startVoiceRecognition(BluetoothDevice device, AttributionSource source) {
            HeadsetClientService service = getService(source);
            if (service == null) {
                return false;
            }

            return service.startVoiceRecognition(device);
        }

        @Override
        public boolean stopVoiceRecognition(BluetoothDevice device, AttributionSource source) {
            HeadsetClientService service = getService(source);
            if (service == null) {
                return false;
            }

            return service.stopVoiceRecognition(device);
        }

        @Override
        public int getAudioState(BluetoothDevice device, AttributionSource source) {
            HeadsetClientService service = getService(source);
            if (service == null) {
                return BluetoothHeadsetClient.STATE_AUDIO_DISCONNECTED;
            }

            return service.getAudioState(device);
        }

        @Override
        public void setAudioRouteAllowed(
                BluetoothDevice device, boolean allowed, AttributionSource source) {
            HeadsetClientService service = getService(source);
            if (service == null) {
                Log.w(TAG, "Service handle is null for setAudioRouteAllowed!");
                return;
            }

            service.setAudioRouteAllowed(device, allowed);
        }

        @Override
        public boolean getAudioRouteAllowed(BluetoothDevice device, AttributionSource source) {
            HeadsetClientService service = getService(source);
            if (service == null) {
                Log.w(TAG, "Service handle is null for getAudioRouteAllowed!");
                return false;
            }

            return service.getAudioRouteAllowed(device);
        }

        @Override
        public boolean connectAudio(BluetoothDevice device, AttributionSource source) {
            HeadsetClientService service = getService(source);
            if (service == null) {
                return false;
            }

            return service.connectAudio(device);
        }

        @Override
        public boolean disconnectAudio(BluetoothDevice device, AttributionSource source) {
            HeadsetClientService service = getService(source);
            if (service == null) {
                return false;
            }

            return service.disconnectAudio(device);
        }

        @Override
        public boolean acceptCall(BluetoothDevice device, int flag, AttributionSource source) {
            HeadsetClientService service = getService(source);
            if (service == null) {
                return false;
            }

            return service.acceptCall(device, flag);
        }

        @Override
        public boolean rejectCall(BluetoothDevice device, AttributionSource source) {
            HeadsetClientService service = getService(source);
            if (service == null) {
                return false;
            }

            return service.rejectCall(device);
        }

        @Override
        public boolean holdCall(BluetoothDevice device, AttributionSource source) {
            HeadsetClientService service = getService(source);
            if (service == null) {
                return false;
            }

            return service.holdCall(device);
        }

        @Override
        public boolean terminateCall(
                BluetoothDevice device, BluetoothHeadsetClientCall call, AttributionSource source) {
            HeadsetClientService service = getService(source);
            if (service == null) {
                Log.w(TAG, "service is null");
                return false;
            }

            return service.terminateCall(device, call != null ? call.getUUID() : null);
        }

        @Override
        public boolean explicitCallTransfer(BluetoothDevice device, AttributionSource source) {
            HeadsetClientService service = getService(source);
            if (service == null) {
                return false;
            }

            return service.explicitCallTransfer(device);
        }

        @Override
        public boolean enterPrivateMode(
                BluetoothDevice device, int index, AttributionSource source) {
            HeadsetClientService service = getService(source);
            if (service == null) {
                return false;
            }

            return service.enterPrivateMode(device, index);
        }

        @Override
        public BluetoothHeadsetClientCall dial(
                BluetoothDevice device, String number, AttributionSource source) {
            HeadsetClientService service = getService(source);
            if (service == null) {
                return null;
            }

            return toLegacyCall(service.dial(device, number));
        }

        @Override
        public List<BluetoothHeadsetClientCall> getCurrentCalls(
                BluetoothDevice device, AttributionSource source) {
            HeadsetClientService service = getService(source);
            List<BluetoothHeadsetClientCall> currentCalls = new ArrayList<>();
            if (service == null) {
                return currentCalls;
            }

            List<HfpClientCall> calls = service.getCurrentCalls(device);
            if (calls != null) {
                for (HfpClientCall call : calls) {
                    currentCalls.add(toLegacyCall(call));
                }
            }
            return currentCalls;
        }

        @Override
        public boolean sendDTMF(BluetoothDevice device, byte code, AttributionSource source) {
            HeadsetClientService service = getService(source);
            if (service == null) {
                return false;
            }

            return service.sendDTMF(device, code);
        }

        @Override
        public boolean getLastVoiceTagNumber(BluetoothDevice device, AttributionSource source) {
            HeadsetClientService service = getService(source);
            if (service == null) {
                return false;
            }

            return service.getLastVoiceTagNumber(device);
        }

        @Override
        public Bundle getCurrentAgEvents(BluetoothDevice device, AttributionSource source) {
            HeadsetClientService service = getService(source);
            if (service == null) {
                return null;
            }

            service.enforceCallingPermission(BLUETOOTH_PRIVILEGED, null);

            return service.getCurrentAgEvents(device);
        }

        @Override
        public boolean sendVendorAtCommand(
                BluetoothDevice device, int vendorId, String atCommand, AttributionSource source) {
            HeadsetClientService service = getService(source);
            if (service == null) {
                return false;
            }

            return service.sendVendorAtCommand(device, vendorId, atCommand);
        }

        @Override
        public Bundle getCurrentAgFeatures(BluetoothDevice device, AttributionSource source) {
            HeadsetClientService service = getService(source);
            if (service == null) {
                return null;
            }

            return service.getCurrentAgFeaturesBundle(device);
        }
    }

    // API methods
    public static synchronized HeadsetClientService getHeadsetClientService() {
        if (sHeadsetClientService == null) {
            Log.w(TAG, "getHeadsetClientService(): service is null");
            return null;
        }
        if (!sHeadsetClientService.isAvailable()) {
            Log.w(TAG, "getHeadsetClientService(): service is not available ");
            return null;
        }
        return sHeadsetClientService;
    }

    /** Set a {@link HeadsetClientService} instance. */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public static synchronized void setHeadsetClientService(HeadsetClientService instance) {
        Log.d(TAG, "setHeadsetClientService(): set to: " + instance);
        sHeadsetClientService = instance;
    }

    public boolean connect(BluetoothDevice device) {
        Log.d(TAG, "connect " + device);
        if (getConnectionPolicy(device) == CONNECTION_POLICY_FORBIDDEN) {
            Log.w(
                    TAG,
                    "Connection not allowed: <"
                            + device.getAddress()
                            + "> is CONNECTION_POLICY_FORBIDDEN");
            return false;
        }
        HeadsetClientStateMachine sm = getStateMachine(device, true);
        if (sm == null) {
            Log.e(TAG, "Cannot allocate SM for device " + device);
            return false;
        }

        sm.sendMessage(HeadsetClientStateMachine.CONNECT, device);
        return true;
    }

    /**
     * Disconnects hfp client for the remote bluetooth device
     *
     * @param device is the device with which we are attempting to disconnect the profile
     * @return true if hfp client profile successfully disconnected, false otherwise
     */
    public boolean disconnect(BluetoothDevice device) {
        HeadsetClientStateMachine sm = getStateMachine(device);
        if (sm == null) {
            Log.e(TAG, "SM does not exist for device " + device);
            return false;
        }

        int connectionState = sm.getConnectionState(device);
        if (connectionState != STATE_CONNECTED && connectionState != STATE_CONNECTING) {
            return false;
        }

        sm.sendMessage(HeadsetClientStateMachine.DISCONNECT, device);
        return true;
    }

    /**
     * @return A list of connected {@link BluetoothDevice}.
     */
    public List<BluetoothDevice> getConnectedDevices() {
        ArrayList<BluetoothDevice> connectedDevices = new ArrayList<>();
        synchronized (mStateMachineMap) {
            for (BluetoothDevice bd : mStateMachineMap.keySet()) {
                HeadsetClientStateMachine sm = mStateMachineMap.get(bd);
                if (sm != null && sm.getConnectionState(bd) == STATE_CONNECTED) {
                    connectedDevices.add(bd);
                }
            }
        }
        return connectedDevices;
    }

    List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        List<BluetoothDevice> devices = new ArrayList<BluetoothDevice>();
        synchronized (mStateMachineMap) {
            for (BluetoothDevice bd : mStateMachineMap.keySet()) {
                for (int state : states) {
                    HeadsetClientStateMachine sm = mStateMachineMap.get(bd);
                    if (sm != null && sm.getConnectionState(bd) == state) {
                        devices.add(bd);
                    }
                }
            }
        }
        return devices;
    }

    /**
     * Get the current connection state of the profile
     *
     * @param device is the remote bluetooth device
     * @return {@link BluetoothProfile#STATE_DISCONNECTED} if this profile is disconnected, {@link
     *     BluetoothProfile#STATE_CONNECTING} if this profile is being connected, {@link
     *     BluetoothProfile#STATE_CONNECTED} if this profile is connected, or {@link
     *     BluetoothProfile#STATE_DISCONNECTING} if this profile is being disconnected
     */
    public int getConnectionState(BluetoothDevice device) {
        HeadsetClientStateMachine sm = getStateMachine(device);
        if (sm != null) {
            return sm.getConnectionState(device);
        }

        return STATE_DISCONNECTED;
    }

    /**
     * Set connection policy of the profile and connects it if connectionPolicy is {@link
     * BluetoothProfile#CONNECTION_POLICY_ALLOWED} or disconnects if connectionPolicy is {@link
     * BluetoothProfile#CONNECTION_POLICY_FORBIDDEN}
     *
     * <p>The device should already be paired. Connection policy can be one of: {@link
     * BluetoothProfile#CONNECTION_POLICY_ALLOWED}, {@link
     * BluetoothProfile#CONNECTION_POLICY_FORBIDDEN}, {@link
     * BluetoothProfile#CONNECTION_POLICY_UNKNOWN}
     *
     * @param device Paired bluetooth device
     * @param connectionPolicy is the connection policy to set to for this profile
     * @return true if connectionPolicy is set, false on error
     */
    public boolean setConnectionPolicy(BluetoothDevice device, int connectionPolicy) {
        Log.d(TAG, "Saved connectionPolicy " + device + " = " + connectionPolicy);

        if (!mDatabaseManager.setProfileConnectionPolicy(
                device, BluetoothProfile.HEADSET_CLIENT, connectionPolicy)) {
            return false;
        }
        if (connectionPolicy == CONNECTION_POLICY_ALLOWED) {
            connect(device);
        } else if (connectionPolicy == CONNECTION_POLICY_FORBIDDEN) {
            disconnect(device);
        }
        return true;
    }

    /**
     * Get the connection policy of the profile.
     *
     * <p>The connection policy can be any of: {@link BluetoothProfile#CONNECTION_POLICY_ALLOWED},
     * {@link BluetoothProfile#CONNECTION_POLICY_FORBIDDEN}, {@link
     * BluetoothProfile#CONNECTION_POLICY_UNKNOWN}
     *
     * @param device Bluetooth device
     * @return connection policy of the device
     */
    public int getConnectionPolicy(BluetoothDevice device) {
        return mDatabaseManager.getProfileConnectionPolicy(device, BluetoothProfile.HEADSET_CLIENT);
    }

    boolean startVoiceRecognition(BluetoothDevice device) {
        HeadsetClientStateMachine sm = getStateMachine(device);
        if (sm == null) {
            Log.e(TAG, "SM does not exist for device " + device);
            return false;
        }
        int connectionState = sm.getConnectionState(device);
        if (connectionState != STATE_CONNECTED) {
            return false;
        }
        sm.sendMessage(HeadsetClientStateMachine.VOICE_RECOGNITION_START);
        return true;
    }

    boolean stopVoiceRecognition(BluetoothDevice device) {
        HeadsetClientStateMachine sm = getStateMachine(device);
        if (sm == null) {
            Log.e(TAG, "SM does not exist for device " + device);
            return false;
        }
        int connectionState = sm.getConnectionState(device);
        if (connectionState != STATE_CONNECTED) {
            return false;
        }
        sm.sendMessage(HeadsetClientStateMachine.VOICE_RECOGNITION_STOP);
        return true;
    }

    /**
     * Gets audio state of the connection with {@code device}.
     *
     * <p>Can be one of {@link STATE_AUDIO_CONNECTED}, {@link STATE_AUDIO_CONNECTING}, or {@link
     * STATE_AUDIO_DISCONNECTED}.
     */
    public int getAudioState(BluetoothDevice device) {
        HeadsetClientStateMachine sm = getStateMachine(device);
        if (sm == null) {
            Log.e(TAG, "SM does not exist for device " + device);
            return -1;
        }

        return sm.getAudioState(device);
    }

    public void setAudioRouteAllowed(BluetoothDevice device, boolean allowed) {
        Log.i(
                TAG,
                "setAudioRouteAllowed: device="
                        + device
                        + ", allowed="
                        + allowed
                        + ", "
                        + Utils.getUidPidString());
        synchronized (mStateMachineMap) {
            HeadsetClientStateMachine sm = mStateMachineMap.get(device);
            if (sm != null) {
                sm.setAudioRouteAllowed(allowed);
            }
        }
    }

    public boolean getAudioRouteAllowed(BluetoothDevice device) {
        synchronized (mStateMachineMap) {
            HeadsetClientStateMachine sm = mStateMachineMap.get(device);
            if (sm != null) {
                return sm.getAudioRouteAllowed();
            }
        }
        return false;
    }

    /**
     * sends the {@link BluetoothSinkAudioPolicy} object to the state machine of the corresponding
     * device to store and send to the remote device using Android specific AT commands.
     *
     * @param device for whom the policies to be set
     * @param policies to be set policies
     */
    public void setAudioPolicy(BluetoothDevice device, BluetoothSinkAudioPolicy policies) {
        Log.i(
                TAG,
                "setAudioPolicy: device="
                        + device
                        + ", "
                        + policies.toString()
                        + ", "
                        + Utils.getUidPidString());
        HeadsetClientStateMachine sm = getStateMachine(device);
        if (sm != null) {
            sm.setAudioPolicy(policies);
        }
    }

    /**
     * sets the audio policy feature support status for the corresponding device.
     *
     * @param device for whom the policies to be set
     * @param supported support status
     */
    public void setAudioPolicyRemoteSupported(BluetoothDevice device, boolean supported) {
        Log.i(TAG, "setAudioPolicyRemoteSupported: " + supported);
        HeadsetClientStateMachine sm = getStateMachine(device);
        if (sm != null) {
            sm.setAudioPolicyRemoteSupported(supported);
        }
    }

    /**
     * gets the audio policy feature support status for the corresponding device.
     *
     * @param device for whom the policies to be set
     * @return int support status
     */
    public int getAudioPolicyRemoteSupported(BluetoothDevice device) {
        HeadsetClientStateMachine sm = getStateMachine(device);
        if (sm != null) {
            return sm.getAudioPolicyRemoteSupported();
        }
        return BluetoothStatusCodes.FEATURE_NOT_CONFIGURED;
    }

    public boolean connectAudio(BluetoothDevice device) {
        Log.i(TAG, "connectAudio: device=" + device + ", " + Utils.getUidPidString());
        HeadsetClientStateMachine sm = getStateMachine(device);
        if (sm == null) {
            Log.e(TAG, "SM does not exist for device " + device);
            return false;
        }

        if (!sm.isConnected()) {
            return false;
        }
        if (sm.isAudioOn()) {
            return false;
        }
        sm.sendMessage(HeadsetClientStateMachine.CONNECT_AUDIO);
        return true;
    }

    public boolean disconnectAudio(BluetoothDevice device) {
        HeadsetClientStateMachine sm = getStateMachine(device);
        if (sm == null) {
            Log.e(TAG, "SM does not exist for device " + device);
            return false;
        }

        if (!sm.isAudioOn()) {
            return false;
        }
        sm.sendMessage(HeadsetClientStateMachine.DISCONNECT_AUDIO);
        return true;
    }

    public boolean holdCall(BluetoothDevice device) {
        HeadsetClientStateMachine sm = getStateMachine(device);
        if (sm == null) {
            Log.e(TAG, "SM does not exist for device " + device);
            return false;
        }

        int connectionState = sm.getConnectionState(device);
        if (connectionState != STATE_CONNECTED && connectionState != STATE_CONNECTING) {
            return false;
        }
        Message msg = sm.obtainMessage(HeadsetClientStateMachine.HOLD_CALL);
        sm.sendMessage(msg);
        return true;
    }

    public boolean acceptCall(BluetoothDevice device, int flag) {
        /* Phonecalls from a single device are supported, hang up any calls on the other phone */
        synchronized (mStateMachineMap) {
            for (Map.Entry<BluetoothDevice, HeadsetClientStateMachine> entry :
                    mStateMachineMap.entrySet()) {
                if (entry.getValue() == null || entry.getKey().equals(device)) {
                    continue;
                }
                int connectionState = entry.getValue().getConnectionState(entry.getKey());
                Log.d(
                        TAG,
                        "Accepting a call on device "
                                + device
                                + ". Possibly disconnecting on "
                                + entry.getValue());
                if (connectionState == STATE_CONNECTED) {
                    entry.getValue()
                            .obtainMessage(HeadsetClientStateMachine.TERMINATE_CALL)
                            .sendToTarget();
                }
            }
        }
        HeadsetClientStateMachine sm = getStateMachine(device);
        if (sm == null) {
            Log.e(TAG, "SM does not exist for device " + device);
            return false;
        }

        int connectionState = sm.getConnectionState(device);
        if (connectionState != STATE_CONNECTED) {
            return false;
        }
        Message msg = sm.obtainMessage(HeadsetClientStateMachine.ACCEPT_CALL);
        msg.arg1 = flag;
        sm.sendMessage(msg);
        return true;
    }

    public boolean rejectCall(BluetoothDevice device) {
        HeadsetClientStateMachine sm = getStateMachine(device);
        if (sm == null) {
            Log.e(TAG, "SM does not exist for device " + device);
            return false;
        }

        int connectionState = sm.getConnectionState(device);
        if (connectionState != STATE_CONNECTED && connectionState != STATE_CONNECTING) {
            return false;
        }

        Message msg = sm.obtainMessage(HeadsetClientStateMachine.REJECT_CALL);
        sm.sendMessage(msg);
        return true;
    }

    public boolean terminateCall(BluetoothDevice device, UUID uuid) {
        HeadsetClientStateMachine sm = getStateMachine(device);
        if (sm == null) {
            Log.e(TAG, "SM does not exist for device " + device);
            return false;
        }

        int connectionState = sm.getConnectionState(device);
        if (connectionState != STATE_CONNECTED && connectionState != STATE_CONNECTING) {
            return false;
        }

        Message msg = sm.obtainMessage(HeadsetClientStateMachine.TERMINATE_CALL);
        msg.obj = uuid;
        sm.sendMessage(msg);
        return true;
    }

    public boolean enterPrivateMode(BluetoothDevice device, int index) {
        HeadsetClientStateMachine sm = getStateMachine(device);
        if (sm == null) {
            Log.e(TAG, "SM does not exist for device " + device);
            return false;
        }

        int connectionState = sm.getConnectionState(device);
        if (connectionState != STATE_CONNECTED && connectionState != STATE_CONNECTING) {
            return false;
        }

        Message msg = sm.obtainMessage(HeadsetClientStateMachine.ENTER_PRIVATE_MODE);
        msg.arg1 = index;
        sm.sendMessage(msg);
        return true;
    }

    public HfpClientCall dial(BluetoothDevice device, String number) {
        HeadsetClientStateMachine sm = getStateMachine(device);
        if (sm == null) {
            Log.e(TAG, "SM does not exist for device " + device);
            return null;
        }

        int connectionState = sm.getConnectionState(device);
        if (connectionState != STATE_CONNECTED && connectionState != STATE_CONNECTING) {
            return null;
        }

        // Some platform does not support three way calling (ex: watch)
        final boolean support_three_way_calling =
                SystemProperties.getBoolean(
                        "bluetooth.headset_client.three_way_calling.enabled", true);
        if (!support_three_way_calling && !getCurrentCalls(device).isEmpty()) {
            Log.e(TAG, "dial(" + device + "): Line is busy, reject dialing");
            return null;
        }

        HfpClientCall call =
                new HfpClientCall(
                        device,
                        HeadsetClientStateMachine.HF_ORIGINATED_CALL_ID,
                        HfpClientCall.CALL_STATE_DIALING,
                        number,
                        false /* multiparty */,
                        true /* outgoing */,
                        sm.getInBandRing());
        Message msg = sm.obtainMessage(HeadsetClientStateMachine.DIAL_NUMBER);
        msg.obj = call;
        sm.sendMessage(msg);
        return call;
    }

    public boolean sendDTMF(BluetoothDevice device, byte code) {
        HeadsetClientStateMachine sm = getStateMachine(device);
        if (sm == null) {
            Log.e(TAG, "SM does not exist for device " + device);
            return false;
        }

        int connectionState = sm.getConnectionState(device);
        if (connectionState != STATE_CONNECTED && connectionState != STATE_CONNECTING) {
            return false;
        }
        Message msg = sm.obtainMessage(HeadsetClientStateMachine.SEND_DTMF);
        msg.arg1 = code;
        sm.sendMessage(msg);
        return true;
    }

    public boolean getLastVoiceTagNumber(BluetoothDevice device) {
        return false;
    }

    public List<HfpClientCall> getCurrentCalls(BluetoothDevice device) {
        HeadsetClientStateMachine sm = getStateMachine(device);
        if (sm == null) {
            Log.e(TAG, "SM does not exist for device " + device);
            return null;
        }

        int connectionState = sm.getConnectionState(device);
        if (connectionState != STATE_CONNECTED) {
            return null;
        }
        return sm.getCurrentCalls();
    }

    public boolean explicitCallTransfer(BluetoothDevice device) {
        HeadsetClientStateMachine sm = getStateMachine(device);
        if (sm == null) {
            Log.e(TAG, "SM does not exist for device " + device);
            return false;
        }

        int connectionState = sm.getConnectionState(device);
        if (connectionState != STATE_CONNECTED && connectionState != STATE_CONNECTING) {
            return false;
        }
        Message msg = sm.obtainMessage(HeadsetClientStateMachine.EXPLICIT_CALL_TRANSFER);
        sm.sendMessage(msg);
        return true;
    }

    /** Send vendor AT command. */
    public boolean sendVendorAtCommand(BluetoothDevice device, int vendorId, String atCommand) {
        HeadsetClientStateMachine sm = getStateMachine(device);
        if (sm == null) {
            Log.e(TAG, "SM does not exist for device " + device);
            return false;
        }

        int connectionState = sm.getConnectionState(device);
        if (connectionState != STATE_CONNECTED) {
            return false;
        }

        Message msg =
                sm.obtainMessage(
                        HeadsetClientStateMachine.SEND_VENDOR_AT_COMMAND, vendorId, 0, atCommand);
        sm.sendMessage(msg);
        return true;
    }

    public Bundle getCurrentAgEvents(BluetoothDevice device) {
        HeadsetClientStateMachine sm = getStateMachine(device);
        if (sm == null) {
            Log.e(TAG, "SM does not exist for device " + device);
            return null;
        }

        int connectionState = sm.getConnectionState(device);
        if (connectionState != STATE_CONNECTED) {
            return null;
        }
        return sm.getCurrentAgEvents();
    }

    public Bundle getCurrentAgFeaturesBundle(BluetoothDevice device) {
        HeadsetClientStateMachine sm = getStateMachine(device);
        if (sm == null) {
            Log.e(TAG, "SM does not exist for device " + device);
            return null;
        }
        int connectionState = sm.getConnectionState(device);
        if (connectionState != STATE_CONNECTED) {
            return null;
        }
        return sm.getCurrentAgFeaturesBundle();
    }

    public Set<Integer> getCurrentAgFeatures(BluetoothDevice device) {
        HeadsetClientStateMachine sm = getStateMachine(device);
        if (sm == null) {
            Log.e(TAG, "SM does not exist for device " + device);
            return null;
        }
        int connectionState = sm.getConnectionState(device);
        if (connectionState != STATE_CONNECTED) {
            return null;
        }
        return sm.getCurrentAgFeatures();
    }

    // Handle messages from native (JNI) to java
    public void messageFromNative(StackEvent stackEvent) {
        requireNonNull(stackEvent.device);

        HeadsetClientStateMachine sm =
                getStateMachine(stackEvent.device, isConnectionEvent(stackEvent));
        if (sm == null) {
            throw new IllegalStateException(
                    "State machine not found for stack event: " + stackEvent);
        }
        sm.sendMessage(StackEvent.STACK_EVENT, stackEvent);
    }

    private static boolean isConnectionEvent(StackEvent stackEvent) {
        if (stackEvent.type == StackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED) {
            if ((stackEvent.valueInt == HeadsetClientHalConstants.CONNECTION_STATE_CONNECTING)
                    || (stackEvent.valueInt
                            == HeadsetClientHalConstants.CONNECTION_STATE_CONNECTED)) {
                return true;
            }
        }
        return false;
    }

    private HeadsetClientStateMachine getStateMachine(BluetoothDevice device) {
        return getStateMachine(device, false);
    }

    private HeadsetClientStateMachine getStateMachine(
            BluetoothDevice device, boolean isConnectionEvent) {
        if (device == null) {
            Log.e(TAG, "getStateMachine failed: Device cannot be null");
            return null;
        }

        HeadsetClientStateMachine sm;
        synchronized (mStateMachineMap) {
            sm = mStateMachineMap.get(device);
        }

        if (sm != null) {
            Log.d(TAG, "Found SM for device " + device);
        } else if (isConnectionEvent) {
            // The only time a new state machine should be created when none was found is for
            // connection events.
            sm = allocateStateMachine(device);
            if (sm == null) {
                Log.e(TAG, "SM could not be allocated for device " + device);
            }
        }
        return sm;
    }

    private HeadsetClientStateMachine allocateStateMachine(BluetoothDevice device) {
        if (device == null) {
            Log.e(TAG, "allocateStateMachine failed: Device cannot be null");
            return null;
        }

        if (getHeadsetClientService() == null) {
            // Preconditions: {@code setHeadsetClientService(this)} is the last thing {@code start}
            // does, and {@code setHeadsetClientService(null)} is (one of) the first thing
            // {@code stop does}.
            Log.e(
                    TAG,
                    "Cannot allocate SM if service has begun stopping or has not completed"
                            + " startup.");
            return null;
        }

        synchronized (mStateMachineMap) {
            HeadsetClientStateMachine sm = mStateMachineMap.get(device);
            if (sm != null) {
                Log.d(TAG, "allocateStateMachine: SM already exists for device " + device);
                return sm;
            }

            // There is a possibility of a DOS attack if someone populates here with a lot of fake
            // BluetoothAddresses. If it so happens instead of blowing up we can at least put a
            // limit on how long the attack would survive
            if (mStateMachineMap.keySet().size() > MAX_STATE_MACHINES_POSSIBLE) {
                Log.e(
                        TAG,
                        "Max state machines reached, possible DOS attack "
                                + MAX_STATE_MACHINES_POSSIBLE);
                return null;
            }

            // Allocate a new SM
            Log.d(TAG, "Creating a new state machine");
            sm = mSmFactory.make(mAdapterService, this, mSmThread, mNativeInterface);
            mStateMachineMap.put(device, sm);
            return sm;
        }
    }

    // Check if any of the state machines have routed the SCO audio stream.
    boolean isScoRouted() {
        synchronized (mStateMachineMap) {
            for (Map.Entry<BluetoothDevice, HeadsetClientStateMachine> entry :
                    mStateMachineMap.entrySet()) {
                if (entry.getValue() != null) {
                    int audioState = entry.getValue().getAudioState(entry.getKey());
                    if (audioState == HeadsetClientHalConstants.AUDIO_STATE_CONNECTED) {
                        Log.d(
                                TAG,
                                "Device "
                                        + entry.getKey()
                                        + " audio state "
                                        + audioState
                                        + " Connected");
                        return true;
                    }
                }
            }
        }
        return false;
    }

    void handleBatteryLevelChanged(BluetoothDevice device, int batteryLevel) {
        mAdapterService.getRemoteDevices().handleAgBatteryLevelChanged(device, batteryLevel);
    }

    @Override
    public void dump(StringBuilder sb) {
        super.dump(sb);
        synchronized (mStateMachineMap) {
            for (HeadsetClientStateMachine sm : mStateMachineMap.values()) {
                if (sm != null) {
                    sm.dump(sb);
                }
            }

            sb.append("\n");
            HfpClientConnectionService.dump(sb);
        }
    }

    // For testing
    protected Map<BluetoothDevice, HeadsetClientStateMachine> getStateMachineMap() {
        synchronized (mStateMachineMap) {
            return mStateMachineMap;
        }
    }

    protected AudioManager getAudioManager() {
        return mAudioManager;
    }

    protected void updateBatteryLevel() {
        int batteryLevel = mBatteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        int batteryIndicatorID = 2;

        synchronized (mStateMachineMap) {
            for (HeadsetClientStateMachine sm : mStateMachineMap.values()) {
                if (sm != null) {
                    sm.sendMessage(
                            HeadsetClientStateMachine.SEND_BIEV, batteryIndicatorID, batteryLevel);
                }
            }
        }
    }
}
