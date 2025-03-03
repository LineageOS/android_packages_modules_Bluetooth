/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.bluetooth.a2dp;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;

import static com.android.bluetooth.Utils.checkCallerTargetSdk;

import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElseGet;

import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothA2dp.OptionalCodecsPreferenceStatus;
import android.bluetooth.BluetoothA2dp.OptionalCodecsSupportStatus;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecStatus;
import android.bluetooth.BluetoothCodecType;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.BufferConstraints;
import android.bluetooth.IBluetoothA2dp;
import android.companion.CompanionDeviceManager;
import android.content.AttributionSource;
import android.content.Intent;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.BluetoothProfileConnectionInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.sysprop.BluetoothProperties;
import android.util.Log;

import com.android.bluetooth.BluetoothMetricsProto;
import com.android.bluetooth.BluetoothStatsLog;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.ActiveDeviceManager;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.MetricsLogger;
import com.android.bluetooth.btservice.ProfileService;
import com.android.bluetooth.btservice.ServiceFactory;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.hfp.HeadsetService;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Provides Bluetooth A2DP profile, as a service in the Bluetooth application. */
public class A2dpService extends ProfileService {
    private static final String TAG = A2dpService.class.getSimpleName();

    // TODO(b/240635097): remove in U
    private static final int SOURCE_CODEC_TYPE_OPUS = 6;

    private static A2dpService sA2dpService;

    private final A2dpNativeInterface mNativeInterface;
    private final A2dpCodecConfig mA2dpCodecConfig;
    private final AdapterService mAdapterService;
    private final AudioManager mAudioManager;
    private final DatabaseManager mDatabaseManager;
    private final CompanionDeviceManager mCompanionDeviceManager;
    private final Looper mLooper;
    private final Handler mHandler;
    // Upper limit of all A2DP devices that are Connected or Connecting
    private final int mMaxConnectedAudioDevices;

    @VisibleForTesting ServiceFactory mFactory = new ServiceFactory();

    @GuardedBy("mStateMachines")
    private BluetoothDevice mActiveDevice;

    private BluetoothDevice mExposedActiveDevice;
    private final ConcurrentMap<BluetoothDevice, A2dpStateMachine> mStateMachines =
            new ConcurrentHashMap<>();

    // Protect setActiveDevice()/removeActiveDevice() so all invoked is handled sequentially
    private final Object mActiveSwitchingGuard = new Object();

    // Upper limit of all A2DP devices: Bonded or Connected
    private static final int MAX_A2DP_STATE_MACHINES = 50;
    // A2DP Offload Enabled in platform
    private final boolean mA2dpOffloadEnabled;

    private final AudioManagerAudioDeviceCallback mAudioManagerAudioDeviceCallback =
            new AudioManagerAudioDeviceCallback();

    public A2dpService(AdapterService adapterService) {
        this(adapterService, null, Looper.getMainLooper());
    }

    @VisibleForTesting
    A2dpService(AdapterService adapterService, A2dpNativeInterface nativeInterface, Looper looper) {
        super(requireNonNull(adapterService));
        mAdapterService = adapterService;
        mNativeInterface =
                requireNonNullElseGet(
                        nativeInterface,
                        () ->
                                new A2dpNativeInterface(
                                        adapterService,
                                        new A2dpNativeCallback(adapterService, this)));
        mDatabaseManager = requireNonNull(mAdapterService.getDatabase());
        mAudioManager = requireNonNull(getSystemService(AudioManager.class));
        mCompanionDeviceManager = requireNonNull(getSystemService(CompanionDeviceManager.class));
        mLooper = requireNonNull(looper);
        mHandler = new Handler(mLooper);

        mMaxConnectedAudioDevices = mAdapterService.getMaxConnectedAudioDevices();
        Log.i(TAG, "Max connected audio devices set to " + mMaxConnectedAudioDevices);

        mA2dpCodecConfig = new A2dpCodecConfig(this, mNativeInterface);

        mNativeInterface.init(
                mMaxConnectedAudioDevices,
                mA2dpCodecConfig.codecConfigPriorities(),
                mA2dpCodecConfig.codecConfigOffloading());

        mA2dpOffloadEnabled = mAdapterService.isA2dpOffloadEnabled();
        Log.d(TAG, "A2DP offload flag set to " + mA2dpOffloadEnabled);

        mAudioManager.registerAudioDeviceCallback(mAudioManagerAudioDeviceCallback, mHandler);

        setA2dpService(this);
    }

    public static boolean isEnabled() {
        return BluetoothProperties.isProfileA2dpSourceEnabled().orElse(false);
    }

    ActiveDeviceManager getActiveDeviceManager() {
        return mAdapterService.getActiveDeviceManager();
    }

    @Override
    protected IProfileServiceBinder initBinder() {
        return new BluetoothA2dpBinder(this);
    }

    @Override
    public void cleanup() {
        Log.i(TAG, "Cleanup A2dp Service");

        if (sA2dpService == null) {
            Log.w(TAG, "cleanup() called before initialization");
            return;
        }

        // Step 9: Clear active device and stop playing audio
        removeActiveDevice(true);

        // Step 8: Mark service as stopped
        setA2dpService(null);

        // Step 7: Unregister Audio Device Callback
        mAudioManager.unregisterAudioDeviceCallback(mAudioManagerAudioDeviceCallback);

        // Step 6: Cleanup native interface
        mNativeInterface.cleanup();

        // Step 4: Destroy state machines and stop handler thread
        synchronized (mStateMachines) {
            for (A2dpStateMachine sm : mStateMachines.values()) {
                sm.doQuit();
            }
            mStateMachines.clear();
        }

        mHandler.removeCallbacksAndMessages(null);
    }

    public static synchronized A2dpService getA2dpService() {
        if (sA2dpService == null) {
            Log.w(TAG, "getA2dpService(): service is null");
            return null;
        }
        if (!sA2dpService.isAvailable()) {
            Log.w(TAG, "getA2dpService(): service is not available");
            return null;
        }
        return sA2dpService;
    }

    private static synchronized void setA2dpService(A2dpService instance) {
        Log.d(TAG, "setA2dpService(): set to: " + instance);
        sA2dpService = instance;
    }

    public boolean connect(BluetoothDevice device) {
        Log.d(TAG, "connect(): " + device);

        if (getConnectionPolicy(device) == CONNECTION_POLICY_FORBIDDEN) {
            Log.e(TAG, "Cannot connect to " + device + " : CONNECTION_POLICY_FORBIDDEN");
            return false;
        }
        if (!Utils.arrayContains(mAdapterService.getRemoteUuids(device), BluetoothUuid.A2DP_SINK)) {
            Log.e(TAG, "Cannot connect to " + device + " : Remote does not have A2DP Sink UUID");
            return false;
        }

        synchronized (mStateMachines) {
            if (!connectionAllowedCheckMaxDevices(device)) {
                // when mMaxConnectedAudioDevices is one, disconnect current device first.
                if (mMaxConnectedAudioDevices == 1) {
                    List<BluetoothDevice> sinks =
                            getDevicesMatchingConnectionStates(
                                    new int[] {
                                        STATE_CONNECTED, STATE_CONNECTING, STATE_DISCONNECTING
                                    });
                    for (BluetoothDevice sink : sinks) {
                        if (sink.equals(device)) {
                            Log.w(TAG, "Connecting to device " + device + " : disconnect skipped");
                            continue;
                        }
                        disconnect(sink);
                    }
                } else {
                    Log.e(TAG, "Cannot connect to " + device + " : too many connected devices");
                    return false;
                }
            }
            A2dpStateMachine smConnect = getOrCreateStateMachine(device);
            if (smConnect == null) {
                Log.e(TAG, "Cannot connect to " + device + " : no state machine");
                return false;
            }
            smConnect.sendMessage(A2dpStateMachine.MESSAGE_CONNECT);
            return true;
        }
    }

    /**
     * Disconnects A2dp for the remote bluetooth device
     *
     * @param device is the device with which we would like to disconnect a2dp
     * @return true if profile disconnected, false if device not connected over a2dp
     */
    public boolean disconnect(BluetoothDevice device) {
        Log.d(TAG, "disconnect(): " + device);

        synchronized (mStateMachines) {
            A2dpStateMachine sm = mStateMachines.get(device);
            if (sm == null) {
                Log.e(TAG, "Ignored disconnect request for " + device + " : no state machine");
                return false;
            }
            sm.sendMessage(A2dpStateMachine.MESSAGE_DISCONNECT);
            return true;
        }
    }

    public List<BluetoothDevice> getConnectedDevices() {
        synchronized (mStateMachines) {
            List<BluetoothDevice> devices = new ArrayList<>();
            for (A2dpStateMachine sm : mStateMachines.values()) {
                if (sm.isConnected()) {
                    devices.add(sm.getDevice());
                }
            }
            return devices;
        }
    }

    /**
     * Check whether can connect to a peer device. The check considers the maximum number of
     * connected peers.
     *
     * @param device the peer device to connect to
     * @return true if connection is allowed, otherwise false
     */
    private boolean connectionAllowedCheckMaxDevices(BluetoothDevice device) {
        int connected = 0;
        // Count devices that are in the process of connecting or already connected
        synchronized (mStateMachines) {
            for (A2dpStateMachine sm : mStateMachines.values()) {
                switch (sm.getConnectionState()) {
                    case STATE_CONNECTING:
                    case STATE_CONNECTED:
                        if (Objects.equals(device, sm.getDevice())) {
                            return true; // Already connected or accounted for
                        }
                        connected++;
                        break;
                    default:
                        break;
                }
            }
        }
        return (connected < mMaxConnectedAudioDevices);
    }

    /**
     * Check whether can connect to a peer device. The check considers a number of factors during
     * the evaluation.
     *
     * @param device the peer device to connect to
     * @param isOutgoingRequest if true, the check is for outgoing connection request, otherwise is
     *     for incoming connection request
     * @return true if connection is allowed, otherwise false
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public boolean okToConnect(BluetoothDevice device, boolean isOutgoingRequest) {
        Log.i(TAG, "okToConnect: device " + device + " isOutgoingRequest: " + isOutgoingRequest);
        // Check if this is an incoming connection in Quiet mode.
        if (mAdapterService.isQuietModeEnabled() && !isOutgoingRequest) {
            Log.e(TAG, "okToConnect: cannot connect to " + device + " : quiet mode enabled");
            return false;
        }
        // Check if too many devices
        if (!connectionAllowedCheckMaxDevices(device)) {
            Log.e(
                    TAG,
                    "okToConnect: cannot connect to " + device + " : too many connected devices");
            return false;
        }
        // Check connectionPolicy and accept or reject the connection.
        int connectionPolicy = getConnectionPolicy(device);
        if (!Flags.donotValidateBondStateFromProfiles()) {
            int bondState = mAdapterService.getBondState(device);
            // Allow this connection only if the device is bonded. Any attempt to connect while
            // bonding would potentially lead to an unauthorized connection.
            if (bondState != BluetoothDevice.BOND_BONDED) {
                Log.w(TAG, "okToConnect: return false, bondState=" + bondState);
                return false;
            }
        }
        if (connectionPolicy != CONNECTION_POLICY_UNKNOWN
                && connectionPolicy != CONNECTION_POLICY_ALLOWED) {
            if (!isOutgoingRequest) {
                HeadsetService headsetService = HeadsetService.getHeadsetService();
                if (headsetService != null && headsetService.okToAcceptConnection(device, true)) {
                    Log.d(
                            TAG,
                            "okToConnect: return false,"
                                    + " Fallback connection to allowed HFP profile");
                    headsetService.connect(device);
                    return false;
                }
            }
            // Otherwise, reject the connection if connectionPolicy is not valid.
            Log.w(TAG, "okToConnect: return false, connectionPolicy=" + connectionPolicy);
            return false;
        }
        return true;
    }

    List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        List<BluetoothDevice> devices = new ArrayList<>();
        if (states == null) {
            return devices;
        }
        final BluetoothDevice[] bondedDevices = mAdapterService.getBondedDevices();
        if (bondedDevices == null) {
            return devices;
        }
        synchronized (mStateMachines) {
            for (BluetoothDevice device : bondedDevices) {
                if (!Utils.arrayContains(
                        mAdapterService.getRemoteUuids(device), BluetoothUuid.A2DP_SINK)) {
                    continue;
                }
                int connectionState = STATE_DISCONNECTED;
                A2dpStateMachine sm = mStateMachines.get(device);
                if (sm != null) {
                    connectionState = sm.getConnectionState();
                }
                for (int state : states) {
                    if (connectionState == state) {
                        devices.add(device);
                        break;
                    }
                }
            }
            return devices;
        }
    }

    /**
     * Get the list of devices that have state machines.
     *
     * @return the list of devices that have state machines
     */
    @VisibleForTesting
    List<BluetoothDevice> getDevices() {
        List<BluetoothDevice> devices = new ArrayList<>();
        synchronized (mStateMachines) {
            for (A2dpStateMachine sm : mStateMachines.values()) {
                devices.add(sm.getDevice());
            }
            return devices;
        }
    }

    public int getConnectionState(BluetoothDevice device) {
        synchronized (mStateMachines) {
            A2dpStateMachine sm = mStateMachines.get(device);
            if (sm == null) {
                return STATE_DISCONNECTED;
            }
            return sm.getConnectionState();
        }
    }

    /**
     * Removes the current active device.
     *
     * @param stopAudio whether the current media playback should be stopped.
     * @return true on success, otherwise false
     */
    public boolean removeActiveDevice(boolean stopAudio) {
        synchronized (mActiveSwitchingGuard) {
            BluetoothDevice previousActiveDevice = null;
            synchronized (mStateMachines) {
                if (mActiveDevice == null) return true;
                previousActiveDevice = mActiveDevice;
                mActiveDevice = null;
            }
            updateAndBroadcastActiveDevice(null);

            // Make sure the Audio Manager knows the previous active device is no longer active.
            mAudioManager.handleBluetoothActiveDeviceChanged(
                    null,
                    previousActiveDevice,
                    BluetoothProfileConnectionInfo.createA2dpInfo(!stopAudio, -1));

            synchronized (mStateMachines) {
                // Make sure the Active device in native layer is set to null and audio is off
                if (!mNativeInterface.setActiveDevice(null)) {
                    Log.w(
                            TAG,
                            "setActiveDevice(null): Cannot remove active device in native "
                                    + "layer");
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Process a change in the silence mode for a {@link BluetoothDevice}.
     *
     * @param device the device to change silence mode
     * @param silence true to enable silence mode, false to disable.
     * @return true on success, false on error
     */
    @VisibleForTesting
    public boolean setSilenceMode(@NonNull BluetoothDevice device, boolean silence) {
        Log.d(TAG, "setSilenceMode(" + device + "): " + silence);
        synchronized (mStateMachines) {
            if (silence && Objects.equals(mActiveDevice, device)) {
                removeActiveDevice(true);
            } else if (!silence && mActiveDevice == null) {
                // Set the device as the active device if currently no active device.
                setActiveDevice(device);
            }
        }
        if (!mNativeInterface.setSilenceDevice(device, silence)) {
            Log.e(TAG, "Cannot set " + device + " silence mode " + silence + " in native layer");
            return false;
        }
        return true;
    }

    /**
     * Set the active device.
     *
     * @param device the active device. Should not be null.
     * @return true on success, otherwise false
     */
    public boolean setActiveDevice(@NonNull BluetoothDevice device) {
        if (device == null) {
            Log.e(TAG, "device should not be null!");
            return false;
        }

        synchronized (mActiveSwitchingGuard) {
            A2dpStateMachine sm = null;
            BluetoothDevice previousActiveDevice = null;
            synchronized (mStateMachines) {
                if (Objects.equals(device, mActiveDevice)) {
                    Log.i(
                            TAG,
                            "setActiveDevice("
                                    + device
                                    + "): current is "
                                    + mActiveDevice
                                    + " no changed");
                    // returns true since the device is activated even double attempted
                    return true;
                }
                Log.d(TAG, "setActiveDevice(" + device + "): current is " + mActiveDevice);
                sm = mStateMachines.get(device);
                if (sm == null) {
                    Log.e(
                            TAG,
                            "setActiveDevice("
                                    + device
                                    + "): Cannot set as active: "
                                    + "no state machine");
                    return false;
                }
                if (sm.getConnectionState() != STATE_CONNECTED) {
                    Log.e(
                            TAG,
                            "setActiveDevice("
                                    + device
                                    + "): Cannot set as active: "
                                    + "device is not connected");
                    return false;
                }
                previousActiveDevice = mActiveDevice;
                mActiveDevice = device;
            }

            // Switch from one A2DP to another A2DP device
            Log.d(TAG, "Switch A2DP devices to " + device + " from " + previousActiveDevice);

            updateLowLatencyAudioSupport(device);

            BluetoothDevice newActiveDevice = null;
            synchronized (mStateMachines) {
                if (!mNativeInterface.setActiveDevice(device)) {
                    Log.e(
                            TAG,
                            "setActiveDevice("
                                    + device
                                    + "): Cannot set as active in native "
                                    + "layer");
                    // Remove active device and stop playing audio.
                    removeActiveDevice(true);
                    return false;
                }
                // Send an intent with the active device codec config
                BluetoothCodecStatus codecStatus = sm.getCodecStatus();
                if (codecStatus != null) {
                    broadcastCodecConfig(mActiveDevice, codecStatus);
                }
                newActiveDevice = mActiveDevice;
            }

            // Tasks of Bluetooth are done, and now restore the AudioManager side.
            int rememberedVolume = -1;
            if (mFactory.getAvrcpTargetService() != null) {
                rememberedVolume =
                        mFactory.getAvrcpTargetService()
                                .getRememberedVolumeForDevice(newActiveDevice);
            }
            // Make sure the Audio Manager knows the previous Active device is disconnected,
            // and the new Active device is connected.
            // And inform the Audio Service about the codec configuration
            // change, so the Audio Service can reset accordingly the audio
            // feeding parameters in the Audio HAL to the Bluetooth stack.
            mAudioManager.handleBluetoothActiveDeviceChanged(
                    newActiveDevice,
                    previousActiveDevice,
                    BluetoothProfileConnectionInfo.createA2dpInfo(true, rememberedVolume));
        }
        return true;
    }

    /**
     * Get the active device.
     *
     * @return the active device or null if no device is active
     */
    public BluetoothDevice getActiveDevice() {
        synchronized (mStateMachines) {
            return mActiveDevice;
        }
    }

    private boolean isActiveDevice(BluetoothDevice device) {
        synchronized (mStateMachines) {
            return (device != null) && Objects.equals(device, mActiveDevice);
        }
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
                device, BluetoothProfile.A2DP, connectionPolicy)) {
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
        return mDatabaseManager.getProfileConnectionPolicy(device, BluetoothProfile.A2DP);
    }

    public void setAvrcpAbsoluteVolume(int volume) {
        if (mFactory.getAvrcpTargetService() != null) {
            mFactory.getAvrcpTargetService().sendVolumeChanged(volume);
            return;
        }
    }

    boolean isA2dpPlaying(BluetoothDevice device) {
        Log.d(TAG, "isA2dpPlaying(" + device + ")");
        synchronized (mStateMachines) {
            A2dpStateMachine sm = mStateMachines.get(device);
            if (sm == null) {
                return false;
            }
            return sm.isPlaying();
        }
    }

    /** Returns the list of locally supported codec types. */
    public List<BluetoothCodecType> getSupportedCodecTypes() {
        Log.d(TAG, "getSupportedCodecTypes()");
        return mNativeInterface.getSupportedCodecTypes();
    }

    /**
     * Gets the current codec status (configuration and capability).
     *
     * @param device the remote Bluetooth device. If null, use the current active A2DP Bluetooth
     *     device.
     * @return the current codec status
     */
    public BluetoothCodecStatus getCodecStatus(BluetoothDevice device) {
        Log.d(TAG, "getCodecStatus(" + device + ")");
        synchronized (mStateMachines) {
            if (device == null) {
                device = mActiveDevice;
            }
            if (device == null) {
                return null;
            }
            A2dpStateMachine sm = mStateMachines.get(device);
            if (sm != null) {
                return sm.getCodecStatus();
            }
            return null;
        }
    }

    /**
     * Sets the codec configuration preference.
     *
     * @param device the remote Bluetooth device. If null, use the current active A2DP Bluetooth
     *     device.
     * @param codecConfig the codec configuration preference
     */
    public void setCodecConfigPreference(BluetoothDevice device, BluetoothCodecConfig codecConfig) {
        Log.d(TAG, "setCodecConfigPreference(" + device + "): " + Objects.toString(codecConfig));
        if (device == null) {
            synchronized (mStateMachines) {
                device = mActiveDevice;
            }
        }
        if (device == null) {
            Log.e(TAG, "setCodecConfigPreference: Invalid device");
            return;
        }
        if (codecConfig == null) {
            Log.e(TAG, "setCodecConfigPreference: Codec config can't be null");
            return;
        }
        BluetoothCodecStatus codecStatus = getCodecStatus(device);
        if (codecStatus == null) {
            Log.e(TAG, "setCodecConfigPreference: Codec status is null");
            return;
        }
        mA2dpCodecConfig.setCodecConfigPreference(device, codecStatus, codecConfig);
    }

    /**
     * Enables the optional codecs.
     *
     * @param device the remote Bluetooth device. If null, use the current active A2DP Bluetooth
     *     device.
     */
    public void enableOptionalCodecs(BluetoothDevice device) {
        Log.d(TAG, "enableOptionalCodecs(" + device + ")");
        if (device == null) {
            synchronized (mStateMachines) {
                device = mActiveDevice;
            }
        }
        if (device == null) {
            Log.e(TAG, "enableOptionalCodecs: Invalid device");
            return;
        }
        if (getSupportsOptionalCodecs(device) != BluetoothA2dp.OPTIONAL_CODECS_SUPPORTED) {
            Log.e(TAG, "enableOptionalCodecs: No optional codecs");
            return;
        }
        BluetoothCodecStatus codecStatus = getCodecStatus(device);
        if (codecStatus == null) {
            Log.e(TAG, "enableOptionalCodecs: Codec status is null");
            return;
        }
        updateLowLatencyAudioSupport(device);
        mA2dpCodecConfig.enableOptionalCodecs(device, codecStatus.getCodecConfig());
    }

    /**
     * Disables the optional codecs.
     *
     * @param device the remote Bluetooth device. If null, use the current active A2DP Bluetooth
     *     device.
     */
    public void disableOptionalCodecs(BluetoothDevice device) {
        Log.d(TAG, "disableOptionalCodecs(" + device + ")");
        if (device == null) {
            synchronized (mStateMachines) {
                device = mActiveDevice;
            }
        }
        if (device == null) {
            Log.e(TAG, "disableOptionalCodecs: Invalid device");
            return;
        }
        if (getSupportsOptionalCodecs(device) != BluetoothA2dp.OPTIONAL_CODECS_SUPPORTED) {
            Log.e(TAG, "disableOptionalCodecs: No optional codecs");
            return;
        }
        BluetoothCodecStatus codecStatus = getCodecStatus(device);
        if (codecStatus == null) {
            Log.e(TAG, "disableOptionalCodecs: Codec status is null");
            return;
        }
        updateLowLatencyAudioSupport(device);
        mA2dpCodecConfig.disableOptionalCodecs(device, codecStatus.getCodecConfig());
    }

    /**
     * Checks whether optional codecs are supported
     *
     * @param device is the remote bluetooth device.
     * @return whether optional codecs are supported. Possible values are: {@link
     *     OptionalCodecsSupportStatus#OPTIONAL_CODECS_SUPPORTED}, {@link
     *     OptionalCodecsSupportStatus#OPTIONAL_CODECS_NOT_SUPPORTED}, {@link
     *     OptionalCodecsSupportStatus#OPTIONAL_CODECS_SUPPORT_UNKNOWN}.
     */
    public @OptionalCodecsSupportStatus int getSupportsOptionalCodecs(BluetoothDevice device) {
        return mDatabaseManager.getA2dpSupportsOptionalCodecs(device);
    }

    public void setSupportsOptionalCodecs(BluetoothDevice device, boolean doesSupport) {
        int value =
                doesSupport
                        ? BluetoothA2dp.OPTIONAL_CODECS_SUPPORTED
                        : BluetoothA2dp.OPTIONAL_CODECS_NOT_SUPPORTED;
        mDatabaseManager.setA2dpSupportsOptionalCodecs(device, value);
    }

    /**
     * Checks whether optional codecs are enabled
     *
     * @param device is the remote bluetooth device
     * @return whether the optional codecs are enabled. Possible values are: {@link
     *     OptionalCodecsPreferenceStatus#OPTIONAL_CODECS_PREF_ENABLED}, {@link
     *     OptionalCodecsPreferenceStatus#OPTIONAL_CODECS_PREF_DISABLED}, {@link
     *     OptionalCodecsPreferenceStatus#OPTIONAL_CODECS_PREF_UNKNOWN}.
     */
    public @OptionalCodecsPreferenceStatus int getOptionalCodecsEnabled(BluetoothDevice device) {
        return mDatabaseManager.getA2dpOptionalCodecsEnabled(device);
    }

    /**
     * Sets the optional codecs to be set to the passed in value
     *
     * @param device is the remote bluetooth device
     * @param value is the new status for the optional codecs. Possible values are: {@link
     *     OptionalCodecsPreferenceStatus#OPTIONAL_CODECS_PREF_ENABLED}, {@link
     *     OptionalCodecsPreferenceStatus#OPTIONAL_CODECS_PREF_DISABLED}, {@link
     *     OptionalCodecsPreferenceStatus#OPTIONAL_CODECS_PREF_UNKNOWN}.
     */
    public void setOptionalCodecsEnabled(
            BluetoothDevice device, @OptionalCodecsPreferenceStatus int value) {
        if (value != BluetoothA2dp.OPTIONAL_CODECS_PREF_UNKNOWN
                && value != BluetoothA2dp.OPTIONAL_CODECS_PREF_DISABLED
                && value != BluetoothA2dp.OPTIONAL_CODECS_PREF_ENABLED) {
            Log.w(TAG, "Unexpected value passed to setOptionalCodecsEnabled:" + value);
            return;
        }
        mDatabaseManager.setA2dpOptionalCodecsEnabled(device, value);
    }

    /**
     * Get dynamic audio buffer size supported type
     *
     * @return support
     *     <p>Possible values are {@link BluetoothA2dp#DYNAMIC_BUFFER_SUPPORT_NONE}, {@link
     *     BluetoothA2dp#DYNAMIC_BUFFER_SUPPORT_A2DP_OFFLOAD}, {@link
     *     BluetoothA2dp#DYNAMIC_BUFFER_SUPPORT_A2DP_SOFTWARE_ENCODING}.
     */
    @RequiresPermission(BLUETOOTH_PRIVILEGED)
    public int getDynamicBufferSupport() {
        enforceCallingOrSelfPermission(
                BLUETOOTH_PRIVILEGED, "Need BLUETOOTH_PRIVILEGED permission");
        return mAdapterService.getDynamicBufferSupport();
    }

    /**
     * Get dynamic audio buffer size
     *
     * @return BufferConstraints
     */
    @RequiresPermission(BLUETOOTH_PRIVILEGED)
    public BufferConstraints getBufferConstraints() {
        enforceCallingOrSelfPermission(
                BLUETOOTH_PRIVILEGED, "Need BLUETOOTH_PRIVILEGED permission");
        return mAdapterService.getBufferConstraints();
    }

    /**
     * Set dynamic audio buffer size
     *
     * @param codec Audio codec
     * @param value buffer millis
     * @return true if the settings is successful, false otherwise
     */
    @RequiresPermission(BLUETOOTH_PRIVILEGED)
    public boolean setBufferLengthMillis(int codec, int value) {
        enforceCallingOrSelfPermission(
                BLUETOOTH_PRIVILEGED, "Need BLUETOOTH_PRIVILEGED permission");
        return mAdapterService.setBufferLengthMillis(codec, value);
    }

    // Handle messages from native (JNI) to Java
    void messageFromNative(A2dpStackEvent stackEvent) {
        if (!isAvailable()) {
            Log.w(TAG, "messageFromNative(): service is not available");
            return;
        }
        BluetoothDevice device = requireNonNull(stackEvent.device);
        synchronized (mStateMachines) {
            A2dpStateMachine sm = mStateMachines.get(device);
            if (sm == null) {
                if (stackEvent.type == A2dpStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED) {
                    switch (stackEvent.valueInt) {
                        case STATE_CONNECTED, STATE_CONNECTING -> {
                            // Create a new state machine only when connecting to a device
                            if (!connectionAllowedCheckMaxDevices(device)) {
                                Log.e(
                                        TAG,
                                        "Cannot connect to "
                                                + device
                                                + " : too many connected devices");
                                return;
                            }
                            sm = getOrCreateStateMachine(device);
                        }
                    }
                }
            }
            if (sm == null) {
                Log.e(TAG, "Cannot process stack event: no state machine: " + stackEvent);
                return;
            }
            sm.sendMessage(A2dpStateMachine.MESSAGE_STACK_EVENT, stackEvent);
        }
    }

    /**
     * The codec configuration for a device has been updated.
     *
     * @param device the remote device
     * @param codecStatus the new codec status
     * @param sameAudioFeedingParameters if true the audio feeding parameters haven't been changed
     */
    @VisibleForTesting
    public void codecConfigUpdated(
            BluetoothDevice device,
            BluetoothCodecStatus codecStatus,
            boolean sameAudioFeedingParameters) {
        // Log codec config and capability metrics
        BluetoothCodecConfig codecConfig = codecStatus.getCodecConfig();
        int metricId = mAdapterService.getMetricId(device);
        BluetoothStatsLog.write(
                BluetoothStatsLog.BLUETOOTH_A2DP_CODEC_CONFIG_CHANGED,
                mAdapterService.obfuscateAddress(device),
                codecConfig.getCodecType(),
                codecConfig.getCodecPriority(),
                codecConfig.getSampleRate(),
                codecConfig.getBitsPerSample(),
                codecConfig.getChannelMode(),
                codecConfig.getCodecSpecific1(),
                codecConfig.getCodecSpecific2(),
                codecConfig.getCodecSpecific3(),
                codecConfig.getCodecSpecific4(),
                metricId);
        List<BluetoothCodecConfig> codecCapabilities =
                codecStatus.getCodecsSelectableCapabilities();
        for (BluetoothCodecConfig codecCapability : codecCapabilities) {
            BluetoothStatsLog.write(
                    BluetoothStatsLog.BLUETOOTH_A2DP_CODEC_CAPABILITY_CHANGED,
                    mAdapterService.obfuscateAddress(device),
                    codecCapability.getCodecType(),
                    codecCapability.getCodecPriority(),
                    codecCapability.getSampleRate(),
                    codecCapability.getBitsPerSample(),
                    codecCapability.getChannelMode(),
                    codecConfig.getCodecSpecific1(),
                    codecConfig.getCodecSpecific2(),
                    codecConfig.getCodecSpecific3(),
                    codecConfig.getCodecSpecific4(),
                    metricId);
        }

        broadcastCodecConfig(device, codecStatus);

        // Inform the Audio Service about the codec configuration change,
        // so the Audio Service can reset accordingly the audio feeding
        // parameters in the Audio HAL to the Bluetooth stack.
        // Until we are able to detect from device_port_proxy if the config has changed or not,
        // the Bluetooth stack can only disable the audio session and need to ask audioManager to
        // restart the session even if feeding parameter are the same. (sameAudioFeedingParameters
        // is left unused until there)
        if (isActiveDevice(device)) {
            mAudioManager.handleBluetoothActiveDeviceChanged(
                    device, device, BluetoothProfileConnectionInfo.createA2dpInfo(false, -1));
        }
    }

    private A2dpStateMachine getOrCreateStateMachine(BluetoothDevice device) {
        if (device == null) {
            Log.e(TAG, "getOrCreateStateMachine failed: device cannot be null");
            return null;
        }
        synchronized (mStateMachines) {
            A2dpStateMachine sm = mStateMachines.get(device);
            if (sm != null) {
                return sm;
            }
            // Limit the maximum number of state machines to avoid DoS attack
            if (mStateMachines.size() >= MAX_A2DP_STATE_MACHINES) {
                Log.e(
                        TAG,
                        "Maximum number of A2DP state machines reached: "
                                + MAX_A2DP_STATE_MACHINES);
                return null;
            }
            Log.d(TAG, "Creating a new state machine for " + device);
            sm =
                    new A2dpStateMachine(
                            this,
                            device,
                            mNativeInterface,
                            mA2dpOffloadEnabled,
                            mLooper);
            mStateMachines.put(device, sm);
            return sm;
        }
    }

    /* Notifications of audio device connection/disconnection events. */
    private class AudioManagerAudioDeviceCallback extends AudioDeviceCallback {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
            if (mAudioManager == null || mAdapterService == null) {
                Log.e(TAG, "Callback called when A2dpService is stopped");
                return;
            }

            synchronized (mStateMachines) {
                for (AudioDeviceInfo deviceInfo : addedDevices) {
                    if (deviceInfo.getType() != AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
                        continue;
                    }

                    String address = deviceInfo.getAddress();
                    if (address.equals("00:00:00:00:00:00")) {
                        continue;
                    }

                    byte[] addressBytes = Utils.getBytesFromAddress(address);
                    BluetoothDevice device = mAdapterService.getDeviceFromByte(addressBytes);

                    Log.d(
                            TAG,
                            " onAudioDevicesAdded: "
                                    + device
                                    + ", device type: "
                                    + deviceInfo.getType());

                    /* Don't expose already exposed active device */
                    if (device.equals(mExposedActiveDevice)) {
                        Log.d(TAG, " onAudioDevicesAdded: " + device + " is already exposed");
                        return;
                    }

                    if (!device.equals(mActiveDevice)) {
                        Log.e(
                                TAG,
                                "Added device does not match to the one activated here. ("
                                        + device
                                        + " != "
                                        + mActiveDevice
                                        + " / "
                                        + mActiveDevice
                                        + ")");
                        continue;
                    }

                    mExposedActiveDevice = device;
                    updateAndBroadcastActiveDevice(device);
                    return;
                }
            }
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
            if (mAudioManager == null || mAdapterService == null) {
                Log.e(TAG, "Callback called when LeAudioService is stopped");
                return;
            }

            synchronized (mStateMachines) {
                for (AudioDeviceInfo deviceInfo : removedDevices) {
                    if (deviceInfo.getType() != AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
                        continue;
                    }

                    String address = deviceInfo.getAddress();
                    if (address.equals("00:00:00:00:00:00")) {
                        continue;
                    }

                    mExposedActiveDevice = null;

                    Log.d(
                            TAG,
                            " onAudioDevicesRemoved: "
                                    + address
                                    + ", device type: "
                                    + deviceInfo.getType()
                                    + ", mActiveDevice: "
                                    + mActiveDevice);
                }
            }
        }
    }

    @VisibleForTesting
    void updateAndBroadcastActiveDevice(BluetoothDevice device) {
        Log.d(TAG, "updateAndBroadcastActiveDevice(" + device + ")");

        // Make sure volume has been store before device been remove from active.
        if (mFactory.getAvrcpTargetService() != null) {
            mFactory.getAvrcpTargetService().handleA2dpActiveDeviceChanged(device);
        }

        mAdapterService.handleActiveDeviceChange(BluetoothProfile.A2DP, device);

        BluetoothStatsLog.write(
                BluetoothStatsLog.BLUETOOTH_ACTIVE_DEVICE_CHANGED,
                BluetoothProfile.A2DP,
                mAdapterService.obfuscateAddress(device),
                mAdapterService.getMetricId(device));

        Intent intent = new Intent(BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.addFlags(
                Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                        | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        sendBroadcast(intent, BLUETOOTH_CONNECT, Utils.getTempBroadcastOptions().toBundle());
    }

    private void broadcastCodecConfig(BluetoothDevice device, BluetoothCodecStatus codecStatus) {
        Log.d(TAG, "broadcastCodecConfig(" + device + "): " + codecStatus);
        Intent intent = new Intent(BluetoothA2dp.ACTION_CODEC_CONFIG_CHANGED);
        intent.putExtra(BluetoothCodecStatus.EXTRA_CODEC_STATUS, codecStatus);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.addFlags(
                Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                        | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        sendBroadcast(intent, BLUETOOTH_CONNECT, Utils.getTempBroadcastOptions().toBundle());
    }

    public void handleBondStateChanged(BluetoothDevice device, int fromState, int toState) {
        mHandler.post(() -> bondStateChanged(device, toState));
    }

    // TODO: b/395691070 delete this method
    @VisibleForTesting
    void bondStateChangedFromTest(BluetoothDevice device, int bondState) {
        Log.d(
                TAG,
                ("bondStateChangedFromTest(" + device + ", " + bondState + "): ")
                        + "called while A2DP_CLEANUP_ON_REMOVE_DEVICE is set to "
                        + Flags.a2dpCleanupOnRemoveDevice());
        bondStateChanged(device, bondState);
    }

    /**
     * Process a change in the bonding state for a device.
     *
     * @param device the device whose bonding state has changed
     * @param bondState the new bond state for the device. Possible values are: {@link
     *     BluetoothDevice#BOND_NONE}, {@link BluetoothDevice#BOND_BONDING}, {@link
     *     BluetoothDevice#BOND_BONDED}.
     */
    @VisibleForTesting
    void bondStateChanged(BluetoothDevice device, int bondState) {
        Log.d(TAG, "Bond state changed for device: " + device + " state: " + bondState);
        // Remove state machine if the bonding for a device is removed
        if (bondState != BluetoothDevice.BOND_NONE) {
            return;
        }
        if (mFactory.getAvrcpTargetService() != null) {
            Log.d(TAG, "bondStateChanged: going for removeStoredVolumeForDevice");
            mFactory.getAvrcpTargetService().removeStoredVolumeForDevice(device);
        }
        synchronized (mStateMachines) {
            A2dpStateMachine sm = mStateMachines.get(device);
            if (sm == null) {
                Log.d(TAG, "bondStateChanged: SM is null, return ");
                return;
            }

            // Bond removal implies that the ACL is disconnected and device properties are removed.
            // If pseudo address is not same as the identity address, all further events from the
            // native stack would get ignored. So the state machine must be removed right away.
            if (!Flags.a2dpCleanupOnRemoveDevice()
                    && sm.getConnectionState() != STATE_DISCONNECTED) {
                Log.d(TAG, "bondStateChanged: not in STATE_DISCONNECTED, return ");
                return;
            }
        }
        removeStateMachine(device);
    }

    private void removeStateMachine(BluetoothDevice device) {
        synchronized (mStateMachines) {
            A2dpStateMachine sm = mStateMachines.get(device);
            if (sm == null) {
                Log.w(
                        TAG,
                        "removeStateMachine: device " + device + " does not have a state machine");
                return;
            }
            Log.i(TAG, "removeStateMachine: removing state machine for device: " + device);
            sm.doQuit();
            mStateMachines.remove(device);
        }
    }

    /**
     * Update and initiate optional codec status change to native.
     *
     * @param device the device to change optional codec status
     */
    @VisibleForTesting
    public void updateOptionalCodecsSupport(BluetoothDevice device) {
        int previousSupport = getSupportsOptionalCodecs(device);
        boolean supportsOptional = false;
        boolean hasMandatoryCodec = false;

        synchronized (mStateMachines) {
            A2dpStateMachine sm = mStateMachines.get(device);
            if (sm == null) {
                return;
            }
            BluetoothCodecStatus codecStatus = sm.getCodecStatus();
            if (codecStatus != null) {
                for (BluetoothCodecConfig config : codecStatus.getCodecsSelectableCapabilities()) {
                    if (config.isMandatoryCodec()) {
                        hasMandatoryCodec = true;
                    } else {
                        supportsOptional = true;
                    }
                }
            }
        }
        if (!hasMandatoryCodec) {
            // Mandatory codec(SBC) is not selectable. It could be caused by the remote device
            // select codec before native finish get codec capabilities. Stop use this codec
            // status as the reference to support/enable optional codecs.
            Log.i(TAG, "updateOptionalCodecsSupport: Mandatory codec is not selectable.");
            return;
        }

        if (previousSupport == BluetoothA2dp.OPTIONAL_CODECS_SUPPORT_UNKNOWN
                || supportsOptional
                        != (previousSupport == BluetoothA2dp.OPTIONAL_CODECS_SUPPORTED)) {
            setSupportsOptionalCodecs(device, supportsOptional);
        }
        if (supportsOptional) {
            int enabled = getOptionalCodecsEnabled(device);
            switch (enabled) {
                case BluetoothA2dp.OPTIONAL_CODECS_PREF_UNKNOWN:
                    // Enable optional codec by default.
                    setOptionalCodecsEnabled(device, BluetoothA2dp.OPTIONAL_CODECS_PREF_ENABLED);
                    // Fall through intended
                case BluetoothA2dp.OPTIONAL_CODECS_PREF_ENABLED:
                    enableOptionalCodecs(device);
                    break;
                case BluetoothA2dp.OPTIONAL_CODECS_PREF_DISABLED:
                    disableOptionalCodecs(device);
                    break;
            }
        }
    }

    /**
     * Check for low-latency codec support and inform AdapterService
     *
     * @param device device whose audio low latency will be allowed or disallowed
     */
    @VisibleForTesting
    public void updateLowLatencyAudioSupport(BluetoothDevice device) {
        synchronized (mStateMachines) {
            A2dpStateMachine sm = mStateMachines.get(device);
            if (sm == null) {
                return;
            }
            BluetoothCodecStatus codecStatus = sm.getCodecStatus();
            boolean lowLatencyAudioAllow = false;
            BluetoothCodecConfig lowLatencyCodec =
                    new BluetoothCodecConfig.Builder()
                            .setCodecType(SOURCE_CODEC_TYPE_OPUS) // remove in U
                            .build();

            if (codecStatus != null
                    && codecStatus.isCodecConfigSelectable(lowLatencyCodec)
                    && getOptionalCodecsEnabled(device)
                            == BluetoothA2dp.OPTIONAL_CODECS_PREF_ENABLED) {
                lowLatencyAudioAllow = true;
            }
            mAdapterService.allowLowLatencyAudio(lowLatencyAudioAllow, device);
        }
    }

    void handleConnectionStateChanged(BluetoothDevice device, int fromState, int toState) {
        mHandler.post(() -> connectionStateChanged(device, fromState, toState));
    }

    void connectionStateChanged(BluetoothDevice device, int fromState, int toState) {
        if (!isAvailable()) {
            Log.w(TAG, "connectionStateChanged: service is not available");
            return;
        }

        if ((device == null) || (fromState == toState)) {
            return;
        }
        if (toState == STATE_CONNECTED) {
            MetricsLogger.logProfileConnectionEvent(BluetoothMetricsProto.ProfileId.A2DP);
        }
        // Set the active device if only one connected device is supported and it was connected
        if (toState == STATE_CONNECTED && (mMaxConnectedAudioDevices == 1)) {
            setActiveDevice(device);
        }
        // When disconnected, ActiveDeviceManager will call setActiveDevice(null)

        // Check if the device is disconnected - if unbond, remove the state machine
        if (toState == STATE_DISCONNECTED) {
            if (mAdapterService.getBondState(device) == BluetoothDevice.BOND_NONE) {
                if (mFactory.getAvrcpTargetService() != null) {
                    mFactory.getAvrcpTargetService().removeStoredVolumeForDevice(device);
                }
                removeStateMachine(device);
            }
        }
        if (mFactory.getAvrcpTargetService() != null) {
            mFactory.getAvrcpTargetService().handleA2dpConnectionStateChanged(device, toState);
        }
        mAdapterService.notifyProfileConnectionStateChangeToGatt(
                BluetoothProfile.A2DP, fromState, toState);
        mAdapterService.handleProfileConnectionStateChange(
                BluetoothProfile.A2DP, device, fromState, toState);
        mAdapterService
                .getActiveDeviceManager()
                .profileConnectionStateChanged(BluetoothProfile.A2DP, device, fromState, toState);
        mAdapterService
                .getSilenceDeviceManager()
                .a2dpConnectionStateChanged(device, fromState, toState);
        mAdapterService.updateProfileConnectionAdapterProperties(
                device, BluetoothProfile.A2DP, toState, fromState);
    }

    /** Retrieves the most recently connected device in the A2DP connected devices list. */
    public BluetoothDevice getFallbackDevice() {
        DatabaseManager dbManager = mAdapterService.getDatabase();
        return dbManager != null
                ? dbManager.getMostRecentlyConnectedDevicesInList(getConnectedDevices())
                : null;
    }

    /** Binder object: must be a static class or memory leak may occur. */
    @VisibleForTesting
    static class BluetoothA2dpBinder extends IBluetoothA2dp.Stub implements IProfileServiceBinder {
        private A2dpService mService;

        @RequiresPermission(BLUETOOTH_CONNECT)
        private A2dpService getServiceAndEnforceConnect(AttributionSource source) {
            // Cache mService because it can change while getService is called
            A2dpService service = mService;

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

        private A2dpService getService() {
            // Cache mService because it can change while getService is called
            A2dpService service = mService;

            if (Utils.isInstrumentationTestMode()) {
                return service;
            }

            if (!Utils.checkServiceAvailable(service, TAG)
                    || !Utils.checkCallerIsSystemOrActiveOrManagedUser(service, TAG)) {
                return null;
            }
            return service;
        }

        BluetoothA2dpBinder(A2dpService svc) {
            mService = svc;
        }

        @Override
        public void cleanup() {
            mService = null;
        }

        @Override
        public boolean connect(BluetoothDevice device, AttributionSource source) {
            A2dpService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return false;
            }

            return service.connect(device);
        }

        @Override
        public boolean disconnect(BluetoothDevice device, AttributionSource source) {
            A2dpService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return false;
            }

            return service.disconnect(device);
        }

        @Override
        public List<BluetoothDevice> getConnectedDevices(AttributionSource source) {
            A2dpService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return Collections.emptyList();
            }

            return service.getConnectedDevices();
        }

        @Override
        public List<BluetoothDevice> getDevicesMatchingConnectionStates(
                int[] states, AttributionSource source) {
            A2dpService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return Collections.emptyList();
            }

            return service.getDevicesMatchingConnectionStates(states);
        }

        @Override
        public int getConnectionState(BluetoothDevice device, AttributionSource source) {
            A2dpService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return STATE_DISCONNECTED;
            }

            return service.getConnectionState(device);
        }

        @Override
        public boolean setActiveDevice(BluetoothDevice device, AttributionSource source) {
            A2dpService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return false;
            }

            if (device == null) {
                return service.removeActiveDevice(false);
            } else {
                return service.setActiveDevice(device);
            }
        }

        @Override
        public BluetoothDevice getActiveDevice(AttributionSource source) {
            A2dpService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return null;
            }

            return service.getActiveDevice();
        }

        @Override
        public boolean setConnectionPolicy(
                BluetoothDevice device, int connectionPolicy, AttributionSource source) {
            A2dpService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return false;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);

            return service.setConnectionPolicy(device, connectionPolicy);
        }

        @Override
        public int getConnectionPolicy(BluetoothDevice device, AttributionSource source) {
            A2dpService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return CONNECTION_POLICY_UNKNOWN;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);

            return service.getConnectionPolicy(device);
        }

        @Override
        public void setAvrcpAbsoluteVolume(int volume, AttributionSource source) {
            A2dpService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return;
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);

            service.setAvrcpAbsoluteVolume(volume);
        }

        @Override
        public boolean isA2dpPlaying(BluetoothDevice device, AttributionSource source) {
            A2dpService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return false;
            }

            return service.isA2dpPlaying(device);
        }

        @Override
        public List<BluetoothCodecType> getSupportedCodecTypes() {
            A2dpService service = getService();
            if (service == null) {
                return Collections.emptyList();
            }

            service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);

            return service.getSupportedCodecTypes();
        }

        @Override
        public BluetoothCodecStatus getCodecStatus(
                BluetoothDevice device, AttributionSource source) {
            requireNonNull(device);
            A2dpService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return null;
            }

            Utils.enforceCdmAssociationIfNotBluetoothPrivileged(
                    service, service.mCompanionDeviceManager, source, device);

            return service.getCodecStatus(device);
        }

        @Override
        public void setCodecConfigPreference(
                BluetoothDevice device,
                BluetoothCodecConfig codecConfig,
                AttributionSource source) {
            requireNonNull(device);
            A2dpService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return;
            }

            Utils.enforceCdmAssociationIfNotBluetoothPrivileged(
                    service, service.mCompanionDeviceManager, source, device);

            service.setCodecConfigPreference(device, codecConfig);
        }

        @Override
        public void enableOptionalCodecs(BluetoothDevice device, AttributionSource source) {
            A2dpService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return;
            }

            if (checkCallerTargetSdk(
                    mService, source.getPackageName(), Build.VERSION_CODES.TIRAMISU)) {
                service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            }
            service.enableOptionalCodecs(device);
        }

        @Override
        public void disableOptionalCodecs(BluetoothDevice device, AttributionSource source) {
            A2dpService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return;
            }

            if (checkCallerTargetSdk(
                    mService, source.getPackageName(), Build.VERSION_CODES.TIRAMISU)) {
                service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            }
            service.disableOptionalCodecs(device);
        }

        @Override
        public int isOptionalCodecsSupported(BluetoothDevice device, AttributionSource source) {
            A2dpService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return BluetoothA2dp.OPTIONAL_CODECS_SUPPORT_UNKNOWN;
            }

            if (checkCallerTargetSdk(
                    mService, source.getPackageName(), Build.VERSION_CODES.TIRAMISU)) {
                service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            }
            return service.getSupportsOptionalCodecs(device);
        }

        @Override
        public int isOptionalCodecsEnabled(BluetoothDevice device, AttributionSource source) {
            A2dpService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return BluetoothA2dp.OPTIONAL_CODECS_PREF_UNKNOWN;
            }

            if (checkCallerTargetSdk(
                    mService, source.getPackageName(), Build.VERSION_CODES.TIRAMISU)) {
                service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            }
            return service.getOptionalCodecsEnabled(device);
        }

        @Override
        public void setOptionalCodecsEnabled(
                BluetoothDevice device, int value, AttributionSource source) {
            A2dpService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return;
            }

            if (checkCallerTargetSdk(
                    mService, source.getPackageName(), Build.VERSION_CODES.TIRAMISU)) {
                service.enforceCallingOrSelfPermission(BLUETOOTH_PRIVILEGED, null);
            }
            service.setOptionalCodecsEnabled(device, value);
        }

        @Override
        public int getDynamicBufferSupport(AttributionSource source) {
            A2dpService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return BluetoothA2dp.DYNAMIC_BUFFER_SUPPORT_NONE;
            }

            return service.getDynamicBufferSupport();
        }

        @Override
        public BufferConstraints getBufferConstraints(AttributionSource source) {
            A2dpService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return null;
            }

            return service.getBufferConstraints();
        }

        @Override
        public boolean setBufferLengthMillis(int codec, int value, AttributionSource source) {
            A2dpService service = getServiceAndEnforceConnect(source);
            if (service == null) {
                return false;
            }

            return service.setBufferLengthMillis(codec, value);
        }
    }

    @Override
    public void dump(StringBuilder sb) {
        super.dump(sb);
        synchronized (mStateMachines) {
            ProfileService.println(sb, "mActiveDevice: " + mActiveDevice);
        }
        ProfileService.println(sb, "mMaxConnectedAudioDevices: " + mMaxConnectedAudioDevices);
        ProfileService.println(sb, "codecConfigPriorities:");
        for (BluetoothCodecConfig codecConfig : mA2dpCodecConfig.codecConfigPriorities()) {
            ProfileService.println(
                    sb,
                    "  "
                            + BluetoothCodecConfig.getCodecName(codecConfig.getCodecType())
                            + ": "
                            + codecConfig.getCodecPriority());
        }
        ProfileService.println(sb, "mA2dpOffloadEnabled: " + mA2dpOffloadEnabled);
        if (mA2dpOffloadEnabled) {
            ProfileService.println(sb, "codecConfigOffloading:");
            for (BluetoothCodecConfig codecConfig : mA2dpCodecConfig.codecConfigOffloading()) {
                ProfileService.println(sb, "  " + codecConfig);
            }
        }
        for (A2dpStateMachine sm : mStateMachines.values()) {
            sm.dump(sb);
        }
    }

    public void switchCodecByBufferSize(BluetoothDevice device, boolean isLowLatency) {
        if (getOptionalCodecsEnabled(device) != BluetoothA2dp.OPTIONAL_CODECS_PREF_ENABLED) {
            return;
        }
        mA2dpCodecConfig.switchCodecByBufferSize(
                device, isLowLatency, getCodecStatus(device).getCodecConfig().getCodecType());
    }

    /**
     * Sends the preferred audio profile change requested from a call to {@link
     * BluetoothAdapter#setPreferredAudioProfiles(BluetoothDevice, Bundle)} to the audio framework
     * to apply the change. The audio framework will call {@link
     * BluetoothAdapter#notifyActiveDeviceChangeApplied(BluetoothDevice)} once the change is
     * successfully applied.
     *
     * @return the number of requests sent to the audio framework
     */
    public int sendPreferredAudioProfileChangeToAudioFramework() {
        synchronized (mStateMachines) {
            if (mActiveDevice == null) {
                Log.e(TAG, "sendPreferredAudioProfileChangeToAudioFramework: no active device");
                return 0;
            }
            mAudioManager.handleBluetoothActiveDeviceChanged(
                    mActiveDevice,
                    mActiveDevice,
                    BluetoothProfileConnectionInfo.createA2dpInfo(false, -1));
            return 1;
        }
    }
}
