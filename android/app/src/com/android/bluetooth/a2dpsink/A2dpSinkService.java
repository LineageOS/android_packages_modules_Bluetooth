/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.bluetooth.a2dpsink;

import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;

import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElseGet;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.os.Handler;
import android.os.Looper;
import android.sysprop.BluetoothProperties;
import android.util.Log;

import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.media_audio.sink.MediaAudioServer;
import com.android.bluetooth.profile.ConnectableProfile;
import com.android.bluetooth.profile.ProfileService;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Provides Bluetooth A2DP Sink profile, as a service in the Bluetooth application. */
public class A2dpSinkService extends ConnectableProfile {
    private static final String TAG = A2dpSinkService.class.getSimpleName();

    private MediaAudioServer mMediaAudioServer;

    // This is also used as a lock for shared data in {@link A2dpSinkService}
    @GuardedBy("mDeviceStateMap")
    private final Map<BluetoothDevice, A2dpSinkStateMachine> mDeviceStateMap =
            new ConcurrentHashMap<>(1);

    private final Object mActiveDeviceLock = new Object();
    private final Object mStreamHandlerLock = new Object();

    private final A2dpSinkNativeInterface mNativeInterface;
    private final Looper mLooper;
    private final Handler mHandler;

    private final int mMaxConnectedAudioDevices;

    @GuardedBy("mStreamHandlerLock")
    private final A2dpSinkStreamHandler mA2dpSinkStreamHandler;

    @GuardedBy("mActiveDeviceLock")
    private BluetoothDevice mActiveDevice = null;

    public static boolean isEnabled() {
        return BluetoothProperties.isProfileA2dpSinkEnabled().orElse(false);
    }

    // ---------------------------------------------------------------------------------------------
    // Lifecycle Functions
    // ---------------------------------------------------------------------------------------------

    public A2dpSinkService(AdapterService adapterService) {
        this(adapterService, null, Looper.getMainLooper());
    }

    @VisibleForTesting
    A2dpSinkService(
            AdapterService adapterService, A2dpSinkNativeInterface nativeInterface, Looper looper) {
        super(BluetoothProfile.A2DP_SINK, adapterService);
        var nativeCallback = new A2dpSinkNativeCallback(getAdapterService(), this);

        if (Flags.mediaAudioServer()) {
            mMediaAudioServer = requireNonNull(adapterService.getMediaAudioServer().orElse(null));
        }

        mNativeInterface =
                requireNonNullElseGet(
                        nativeInterface,
                        () -> new A2dpSinkNativeInterface(nativeCallback, getAdapterService()));
        mLooper = requireNonNull(looper);
        mHandler = new Handler(mLooper);
        mMaxConnectedAudioDevices = getAdapterService().getMaxConnectedAudioDevices();
        mNativeInterface.init(mMaxConnectedAudioDevices);

        if (Flags.mediaAudioServer()) {
            mA2dpSinkStreamHandler = null;
        } else {
            synchronized (mStreamHandlerLock) {
                mA2dpSinkStreamHandler =
                        new A2dpSinkStreamHandler(getAdapterService(), mNativeInterface);
            }
        }
    }

    @Override
    public void cleanup() {
        Log.i(TAG, "cleanup()");

        mNativeInterface.cleanup();
        synchronized (mDeviceStateMap) {
            for (A2dpSinkStateMachine stateMachine : mDeviceStateMap.values()) {
                stateMachine.quitNow();
            }
            mDeviceStateMap.clear();
        }
        if (!Flags.mediaAudioServer()) {
            synchronized (mStreamHandlerLock) {
                mA2dpSinkStreamHandler.cleanup();
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Internally Available Functions
    // ---------------------------------------------------------------------------------------------

    /**
     * Connect the given Bluetooth device.
     *
     * @return true if connection is successful, false otherwise.
     */
    @Override
    public boolean connect(BluetoothDevice device) {
        Log.i(TAG, "connect(device=" + device + ")");
        if (device == null) {
            return false;
        }

        if (getConnectionPolicy(requireNonNull(device)) == CONNECTION_POLICY_FORBIDDEN) {
            Log.w(TAG, "Connection not allowed: <" + device + "> is CONNECTION_POLICY_FORBIDDEN");
            return false;
        }

        mHandler.post(
                () -> {
                    A2dpSinkStateMachine stateMachine = getOrCreateStateMachine(device);
                    stateMachine.connect();
                });

        return true;
    }

    /**
     * Disconnect the given Bluetooth device.
     *
     * @return true if disconnect is successful, false otherwise.
     */
    @Override
    public boolean disconnect(BluetoothDevice device) {
        Log.i(TAG, "disconnect(device=" + device + ")");
        if (device == null) {
            return false;
        }

        A2dpSinkStateMachine stateMachine;
        synchronized (mDeviceStateMap) {
            stateMachine = mDeviceStateMap.get(device);
        }
        // a state machine instance doesn't exist. maybe it is already gone?
        if (stateMachine == null) {
            return false;
        }
        int connectionState = stateMachine.getState();
        if (connectionState == STATE_DISCONNECTED || connectionState == STATE_DISCONNECTING) {
            return false;
        }

        // upon completion of disconnect, the state machine will remove itself from the available
        // devices map
        stateMachine.disconnect();
        return true;
    }

    /** Set the device that should be allowed to actively stream */
    public boolean setActiveDevice(BluetoothDevice device) {
        Log.i(TAG, "setActiveDevice(device=" + device + ")");
        synchronized (mActiveDeviceLock) {
            if (mNativeInterface.setActiveDevice(device)) {
                mActiveDevice = device;
                return true;
            }
            return false;
        }
    }

    /** Get the device that is allowed to be actively streaming */
    public BluetoothDevice getActiveDevice() {
        synchronized (mActiveDeviceLock) {
            return mActiveDevice;
        }
    }

    /** Request audio focus such that the designated device can stream audio */
    public void requestAudioFocus(BluetoothDevice device, boolean request) {
        Log.i(TAG, "requestAudioFocus(device=" + device + ", focus=" + request + ")");

        if (Flags.mediaAudioServer()) {
            Log.w(TAG, "MediaAudioServer owns focus requests, not A2DP");
            return;
        }

        synchronized (mStreamHandlerLock) {
            mA2dpSinkStreamHandler.requestAudioFocus(request);
        }
    }

    /**
     * Get the current Bluetooth Audio focus state
     *
     * @return AudioManager.AUDIOFOCUS_* states on success, or AudioManager.ERROR on error
     */
    // TODO(Flags.mediaAudioServer): Remove after flag clean up
    public int getFocusState() {
        synchronized (mStreamHandlerLock) {
            return mA2dpSinkStreamHandler.getFocusState();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // A2DP Sink Binder APIs
    // ---------------------------------------------------------------------------------------------

    @Override
    protected IProfileServiceBinder initBinder() {
        return new A2dpSinkServiceBinder(this);
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
    @Override
    public boolean setConnectionPolicy(BluetoothDevice device, int connectionPolicy) {
        Log.i(TAG, "setConnectionPolicy(device=" + device + ", policy=" + connectionPolicy + ")");

        getAdapterService().setProfileConnectionPolicy(device, getProfileId(), connectionPolicy);

        if (connectionPolicy == CONNECTION_POLICY_ALLOWED) {
            connect(device);
        } else if (connectionPolicy == CONNECTION_POLICY_FORBIDDEN) {
            disconnect(device);
        }
        return true;
    }

    public List<BluetoothDevice> getConnectedDevices() {
        return getDevicesMatchingConnectionStates(new int[] {BluetoothAdapter.STATE_CONNECTED});
    }

    List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        List<BluetoothDevice> deviceList = new ArrayList<>();
        int connectionState;
        for (BluetoothDevice device : getAdapterService().getBondedDevices()) {
            connectionState = getConnectionState(device);
            for (int i = 0; i < states.length; i++) {
                if (connectionState == states[i]) {
                    deviceList.add(device);
                }
            }
        }
        return deviceList;
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
    @Override
    public int getConnectionState(BluetoothDevice device) {
        if (device == null) return STATE_DISCONNECTED;
        A2dpSinkStateMachine stateMachine;
        synchronized (mDeviceStateMap) {
            stateMachine = mDeviceStateMap.get(device);
        }
        return (stateMachine == null) ? STATE_DISCONNECTED : stateMachine.getState();
    }

    boolean isA2dpPlaying(BluetoothDevice device) {
        if (Flags.mediaAudioServer()) {
            synchronized (mDeviceStateMap) {
                for (A2dpSinkStateMachine stateMachine : mDeviceStateMap.values()) {
                    if (stateMachine.isPlaying()) {
                        return true;
                    }
                }
                return false;
            }
        }

        synchronized (mStreamHandlerLock) {
            return mA2dpSinkStreamHandler.isPlaying();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Messages From Native
    // ---------------------------------------------------------------------------------------------

    void onConnectionStateChangedFromNative(BluetoothDevice device, int state) {
        Log.d(
                TAG,
                "onConnectionStateChangedFromNative("
                        + ("device=" + device)
                        + (", state=" + state)
                        + ")");

        if (device == null) {
            return;
        }

        mHandler.post(
                () -> {
                    A2dpSinkStateMachine stateMachine = getOrCreateStateMachine(device);
                    stateMachine.onConnectionStateChanged(state);
                });
    }

    void onAudioStateChangedFromNative(BluetoothDevice device, int state) {
        Log.d(
                TAG,
                "onAudioStateChangedFromNative("
                        + ("device=" + device)
                        + (", state=" + A2dpSinkNativeInterface.audioStateToString(state))
                        + ")");
        if (device == null) {
            return;
        }

        A2dpSinkStateMachine stateMachine = getStateMachineForDevice(device);
        if (stateMachine == null) {
            Log.w(
                    TAG,
                    "onAudioStateChangedFromNative("
                            + ("device=" + device)
                            + (", state=" + A2dpSinkNativeInterface.audioStateToString(state))
                            + "): Not connected");
            return;
        }

        stateMachine.onAudioStateChanged(state);

        // New code doesn't use the stream handler
        if (Flags.mediaAudioServer()) {
            return;
        }

        synchronized (mStreamHandlerLock) {
            mA2dpSinkStreamHandler.onAudioStateChanged(state);
        }
    }

    void onAudioConfigChangedFromNative(BluetoothDevice device, int sampleRate, int channelCount) {
        Log.d(
                TAG,
                "onAudioConfigChangedFromNative("
                        + ("device=" + device)
                        + (", sampleRate=" + sampleRate)
                        + (", channelCount=" + channelCount)
                        + ")");

        if (device == null) {
            return;
        }

        A2dpSinkStateMachine stateMachine = getStateMachineForDevice(device);
        if (stateMachine == null) {
            Log.w(TAG, "onAudioConfigChangedFromNative(device=" + device + "): Not connected");
            return;
        }
        stateMachine.onAudioConfigChanged(sampleRate, channelCount);
    }

    // ---------------------------------------------------------------------------------------------
    // Device State Machine Management
    // ---------------------------------------------------------------------------------------------

    protected A2dpSinkStateMachine getOrCreateStateMachine(BluetoothDevice device) {
        synchronized (mDeviceStateMap) {
            A2dpSinkStateMachine sm = mDeviceStateMap.get(device);
            if (sm != null) {
                return sm;
            }
            sm =
                    new A2dpSinkStateMachine(
                            this, device, mMediaAudioServer, mLooper, mNativeInterface);
            mDeviceStateMap.put(device, sm);
            return sm;
        }
    }

    @VisibleForTesting
    protected A2dpSinkStateMachine getStateMachineForDevice(BluetoothDevice device) {
        synchronized (mDeviceStateMap) {
            return mDeviceStateMap.get(device);
        }
    }

    /**
     * Remove a device's state machine.
     *
     * <p>Called by the state machines when they disconnect.
     *
     * <p>Visible for testing so it can be mocked and verified on.
     */
    void removeStateMachine(A2dpSinkStateMachine stateMachine) {
        if (stateMachine == null) {
            return;
        }
        synchronized (mDeviceStateMap) {
            mDeviceStateMap.remove(stateMachine.getDevice());
        }
        stateMachine.quitNow();
    }

    /** Called from a state machine on connection state changes */
    void connectionStateChanged(BluetoothDevice device, int fromState, int toState) {
        getAdapterService()
                .notifyProfileConnectionStateChangeToScan(getProfileId(), fromState, toState);
        getAdapterService()
                .updateProfileConnectionAdapterProperties(
                        device, getProfileId(), toState, fromState);
    }

    // ---------------------------------------------------------------------------------------------
    // Utilities
    // ---------------------------------------------------------------------------------------------

    @Override
    public void dump(StringBuilder sb) {
        super.dump(sb);
        ProfileService.println(sb, "Active Device = " + getActiveDevice());
        ProfileService.println(sb, "Max Connected Devices = " + mMaxConnectedAudioDevices);
        synchronized (mDeviceStateMap) {
            ProfileService.println(sb, "Devices Tracked = " + mDeviceStateMap.size());
            for (A2dpSinkStateMachine stateMachine : mDeviceStateMap.values()) {
                ProfileService.println(
                        sb, "==== StateMachine for " + stateMachine.getDevice() + " ====");
                stateMachine.dump(sb);
            }
        }
    }
}
