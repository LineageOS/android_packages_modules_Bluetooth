/*
 * Copyright (C) 2012 The Android Open Source Project
 * Copyright (C) 2016-2017 The Linux Foundation
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

package com.android.bluetooth.btservice;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_SCAN;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;

import static com.android.bluetooth.Utils.BD_ADDR_LEN;
import static com.android.bluetooth.Utils.TYPED_BD_ADDR_LEN;

import android.annotation.NonNull;
import android.app.BroadcastOptions;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothMap;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSap;
import android.bluetooth.BluetoothUtils;
import android.bluetooth.BufferConstraint;
import android.bluetooth.BufferConstraints;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemProperties;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.VisibleForTesting;

import com.android.bluetooth.Util;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.RemoteDevices.DeviceProperties;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.metrics.MetricsLogger;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class AdapterProperties {
    private static final String TAG = Util.BT_PREFIX + AdapterProperties.class.getSimpleName();

    private static final String MAX_CONNECTED_AUDIO_DEVICES_PROPERTY =
            "persist.bluetooth.maxconnectedaudiodevices";
    private static final int MAX_CONNECTED_AUDIO_DEVICES_LOWER_BOUND = 1;
    private static final int MAX_CONNECTED_AUDIO_DEVICES_UPPER_BOUND = 5;
    private static final String A2DP_OFFLOAD_SUPPORTED_PROPERTY =
            "ro.bluetooth.a2dp_offload.supported";
    private static final String A2DP_OFFLOAD_DISABLED_PROPERTY =
            "persist.bluetooth.a2dp_offload.disabled";

    private static final long DEFAULT_DISCOVERY_TIMEOUT_MS = 12800;
    @VisibleForTesting static final int BLUETOOTH_NAME_MAX_LENGTH_BYTES = 248;

    private volatile byte[] mAddress;
    private volatile BluetoothClass mBluetoothClass;
    private volatile int mScanMode;
    private volatile int mDiscoverableTimeout;
    private volatile ParcelUuid[] mUuids;

    private final Set<BluetoothDevice> mBondedDevices = ConcurrentHashMap.newKeySet();

    private int mProfilesConnecting, mProfilesConnected, mProfilesDisconnecting;
    private final HashMap<Integer, Pair<Integer, Integer>> mProfileConnectionState =
            new HashMap<>();

    private final CompletableFuture<List<BufferConstraint>> mBufferConstraintList =
            new CompletableFuture<>();

    private volatile int mConnectionState = BluetoothAdapter.STATE_DISCONNECTED;
    private int mMaxConnectedAudioDevices = 1;
    private boolean mA2dpOffloadEnabled = false;

    private final AdapterService mService;
    private final BluetoothAdapter mAdapter;
    private final RemoteDevices mRemoteDevices;
    private final Handler mHandler;

    private boolean mNativeDiscovering;
    private long mDiscoveryEndMs; // < Time (ms since epoch) that discovery ended or will end.
    // TODO - all hw capabilities to be exposed as a class
    private int mNumOfAdvertisementInstancesSupported;
    private int mNumOfOffloadedScanFilterSupported;
    private int mOffloadedScanResultStorageBytes;
    private int mVersSupported;
    private int mTotNumOfTrackableAdv;
    private boolean mIsExtendedScanSupported;
    private boolean mIsDebugLogSupported;
    private boolean mIsActivityAndEnergyReporting;
    private boolean mIsLe2MPhySupported;
    private boolean mIsLeCodedPhySupported;
    private boolean mIsLeHighDataThroughputPhySupported;
    private boolean mIsLeExtendedAdvertisingSupported;
    private boolean mIsLePeriodicAdvertisingSupported;
    private int mLeMaximumAdvertisingDataLength;
    private boolean mIsOffloadedTransportDiscoveryDataScanSupported;
    private boolean mIsLeBigSetChannelClassificationSupported;

    private int mIsDynamicAudioBufferSizeSupported;
    private int mDynamicAudioBufferSizeSupportedCodecsGroup1;
    private int mDynamicAudioBufferSizeSupportedCodecsGroup2;

    private boolean mIsLePeriodicAdvertisingSyncTransferSenderSupported;
    private boolean mIsLePeriodicAdvertisingSyncTransferRecipientSupported;
    private boolean mIsLeConnectedIsochronousStreamCentralSupported;
    private boolean mIsLeConnectedIsochronousStreamPeripheralSupported;
    private boolean mIsLeIsochronousBroadcasterSupported;
    private boolean mIsLeChannelSoundingSupported;

    private int mNumberOfSupportedOffloadedLeCocSockets;
    private int mNumberOfSupportedOffloadedRfcommSockets;
    private int mSupportedOffloadedGattClientProperties;
    private int mSupportedOffloadedGattServerProperties;

    // Lock for all getters and setters.
    // If finer grained locking is needer, more locks can be added here.
    private final Object mObject = new Object();

    AdapterProperties(AdapterService service, RemoteDevices remoteDevices, Looper looper) {
        mService = service;
        mAdapter = mService.getSystemService(BluetoothManager.class).getAdapter();
        mRemoteDevices = remoteDevices;
        mHandler = new Handler(looper);
        invalidateBluetoothCaches();
    }

    @Override
    public Object clone() throws CloneNotSupportedException {
        throw new CloneNotSupportedException();
    }

    void init() {
        mProfileConnectionState.clear();

        // Get default max connected audio devices from config.xml
        int configDefaultMaxConnectedAudioDevices =
                mService.getResources()
                        .getInteger(
                                com.android.bluetooth.R.integer
                                        .config_bluetooth_max_connected_audio_devices);
        // Override max connected audio devices if MAX_CONNECTED_AUDIO_DEVICES_PROPERTY is set
        int propertyOverlaidMaxConnectedAudioDevices =
                SystemProperties.getInt(
                        MAX_CONNECTED_AUDIO_DEVICES_PROPERTY,
                        configDefaultMaxConnectedAudioDevices);
        // Make sure the final value of max connected audio devices is within allowed range
        mMaxConnectedAudioDevices =
                Math.min(
                        Math.max(
                                propertyOverlaidMaxConnectedAudioDevices,
                                MAX_CONNECTED_AUDIO_DEVICES_LOWER_BOUND),
                        MAX_CONNECTED_AUDIO_DEVICES_UPPER_BOUND);
        infoLog(
                "init(), maxConnectedAudioDevices"
                        + (" default=" + configDefaultMaxConnectedAudioDevices)
                        + (", propertyOverlaid=" + propertyOverlaidMaxConnectedAudioDevices)
                        + (", finalValue=" + mMaxConnectedAudioDevices));

        mA2dpOffloadEnabled =
                SystemProperties.getBoolean(A2DP_OFFLOAD_SUPPORTED_PROPERTY, false)
                        && !SystemProperties.getBoolean(A2DP_OFFLOAD_DISABLED_PROPERTY, false);

        invalidateBluetoothCaches();
    }

    void cleanup() {
        mProfileConnectionState.clear();

        mBondedDevices.clear();
        invalidateBluetoothCaches();
    }

    private static void invalidateBluetoothCaches() {
        invalidateGetProfileConnectionStateCache();
        invalidateIsOffloadedFilteringSupportedCache();
        invalidateGetConnectionStateCache();
        invalidateGetBondStateCache();
        invalidateBluetoothGetConnectionStateCache();
    }

    private static void invalidateGetProfileConnectionStateCache() {
        BluetoothAdapter.invalidateGetProfileConnectionStateCache();
    }

    private static void invalidateIsOffloadedFilteringSupportedCache() {
        BluetoothAdapter.invalidateIsOffloadedFilteringSupportedCache();
    }

    private static void invalidateGetConnectionStateCache() {
        BluetoothAdapter.invalidateGetAdapterConnectionStateCache();
    }

    private static void invalidateGetBondStateCache() {
        BluetoothDevice.invalidateBluetoothGetBondStateCache();
    }

    private static void invalidateBluetoothGetConnectionStateCache() {
        BluetoothMap.invalidateBluetoothGetConnectionStateCache();
        BluetoothSap.invalidateBluetoothGetConnectionStateCache();
    }

    public ParcelUuid[] getUuids() {
        return mUuids;
    }

    void setConnectionState(int connectionState) {
        mConnectionState = connectionState;
        invalidateGetConnectionStateCache();
    }

    int getConnectionState() {
        return mConnectionState;
    }

    int getNumOfAdvertisementInstancesSupported() {
        return mNumOfAdvertisementInstancesSupported;
    }

    int getNumOfOffloadedScanFilterSupported() {
        return mNumOfOffloadedScanFilterSupported;
    }

    int getOffloadedScanResultStorage() {
        return mOffloadedScanResultStorageBytes;
    }

    /**
     * @return tx/rx/idle activity and energy info
     */
    boolean isActivityAndEnergyReportingSupported() {
        return mIsActivityAndEnergyReporting;
    }

    boolean isLe2MPhySupported() {
        return mIsLe2MPhySupported;
    }

    boolean isLeCodedPhySupported() {
        return mIsLeCodedPhySupported;
    }

    boolean isLeHighDataThroughputPhySupported() {
        return mIsLeHighDataThroughputPhySupported;
    }

    boolean isLeExtendedAdvertisingSupported() {
        return mIsLeExtendedAdvertisingSupported;
    }

    boolean isLePeriodicAdvertisingSupported() {
        return mIsLePeriodicAdvertisingSupported;
    }

    boolean isLePeriodicAdvertisingSyncTransferSenderSupported() {
        return mIsLePeriodicAdvertisingSyncTransferSenderSupported;
    }

    boolean isLePeriodicAdvertisingSyncTransferRecipientSupported() {
        return mIsLePeriodicAdvertisingSyncTransferRecipientSupported;
    }

    boolean isLeConnectedIsochronousStreamCentralSupported() {
        return mIsLeConnectedIsochronousStreamCentralSupported;
    }

    boolean isLeConnectedIsochronousStreamPeripheralSupported() {
        return mIsLeConnectedIsochronousStreamPeripheralSupported;
    }

    boolean isLeIsochronousBroadcasterSupported() {
        return mIsLeIsochronousBroadcasterSupported;
    }

    boolean isLeChannelSoundingSupported() {
        return mIsLeChannelSoundingSupported;
    }

    int getLeMaximumAdvertisingDataLength() {
        return mLeMaximumAdvertisingDataLength;
    }

    int getTotalNumOfTrackableAdvertisements() {
        return mTotNumOfTrackableAdv;
    }

    boolean isOffloadedTransportDiscoveryDataScanSupported() {
        return mIsOffloadedTransportDiscoveryDataScanSupported;
    }

    int getMaxConnectedAudioDevices() {
        return mMaxConnectedAudioDevices;
    }

    boolean isA2dpOffloadEnabled() {
        return mA2dpOffloadEnabled;
    }

    boolean isLeBigSetChannelClassificationSupported() {
        return mIsLeBigSetChannelClassificationSupported;
    }

    /**
     * @return Dynamic Audio Buffer support
     */
    int getDynamicBufferSupport() {
        if (!mA2dpOffloadEnabled) {
            // TODO: Enable Dynamic Audio Buffer for A2DP software encoding when ready.
            mIsDynamicAudioBufferSizeSupported = BluetoothA2dp.DYNAMIC_BUFFER_SUPPORT_NONE;
        } else {
            if ((mDynamicAudioBufferSizeSupportedCodecsGroup1 != 0)
                    || (mDynamicAudioBufferSizeSupportedCodecsGroup2 != 0)) {
                mIsDynamicAudioBufferSizeSupported =
                        BluetoothA2dp.DYNAMIC_BUFFER_SUPPORT_A2DP_OFFLOAD;
            } else {
                mIsDynamicAudioBufferSizeSupported = BluetoothA2dp.DYNAMIC_BUFFER_SUPPORT_NONE;
            }
        }
        return mIsDynamicAudioBufferSizeSupported;
    }

    /**
     * @return Dynamic Audio Buffer Capability
     */
    BufferConstraints getBufferConstraints() {
        return new BufferConstraints(mBufferConstraintList.join());
    }

    /**
     * Set the dynamic audio buffer size
     *
     * @param codec the codecs to set
     * @param size the size to set
     */
    boolean setBufferLengthMillis(int codec, int size) {
        return mService.getNative().setBufferLengthMillis(codec, size);
    }

    @NonNull
    Set<BluetoothDevice> getBondedDevices() {
        return Collections.unmodifiableSet(mBondedDevices);
    }

    // This function shall be invoked from BondStateMachine whenever the bond
    // state changes.
    @VisibleForTesting
    void onBondStateChanged(BluetoothDevice device, int state) {
        if (device == null) {
            Log.w(TAG, "onBondStateChanged, device is null");
            return;
        }
        try {
            byte[] addrByte = Util.getByteAddress(device);
            DeviceProperties prop = mRemoteDevices.getDeviceProperties(device);
            if (prop == null) {
                prop = mRemoteDevices.addDeviceProperties(addrByte, device.getAddressType());
            }
            device = prop.getDevice();
            prop.setBondState(state);

            if (state == BluetoothDevice.BOND_BONDED) {
                // add if not already in list
                if (mBondedDevices.add(device)) {
                    debugLog("Adding bonded device:" + device);
                    cleanupPrevBondRecordsFor(device);
                }
            } else if (state == BluetoothDevice.BOND_NONE) {
                // remove device from list
                if (mBondedDevices.remove(device)) {
                    debugLog("Removing bonded device:" + device);
                } else {
                    debugLog("Failed to remove device: " + device);
                }
            }
            invalidateGetBondStateCache();
        } catch (Exception ee) {
            Log.w(TAG, "onBondStateChanged: Exception ", ee);
        }
    }

    void cleanupPrevBondRecordsFor(BluetoothDevice device) {
        String address = device.getAddress();
        String identityAddress = mService.getBrEdrAddress(device);
        int deviceType = mRemoteDevices.getDeviceProperties(device).getDeviceType();
        debugLog("cleanupPrevBondRecordsFor: " + device + ", device type: " + deviceType);
        if (identityAddress == null) {
            return;
        }

        if (deviceType != BluetoothDevice.DEVICE_TYPE_LE) {
            return;
        }

        Iterator<BluetoothDevice> iterator = mBondedDevices.iterator();
        while (iterator.hasNext()) {
            BluetoothDevice existingDevice = iterator.next();
            String existingAddress = existingDevice.getAddress();
            String existingIdentityAddress = mService.getBrEdrAddress(existingDevice);
            int existingDeviceType =
                    mRemoteDevices.getDeviceProperties(existingDevice).getDeviceType();

            boolean removeExisting = false;
            if (identityAddress.equals(existingIdentityAddress)
                    && !address.equals(existingAddress)) {
                // Existing device record should be removed only if the device type is LE-only
                removeExisting = (existingDeviceType == BluetoothDevice.DEVICE_TYPE_LE);
            }

            if (removeExisting) {
                // Found an existing LE-only device with the same identity address but different
                // pseudo address
                if (mService.getNative().removeBond(Util.getBytesFromAddress(existingAddress))) {
                    iterator.remove();
                    infoLog(
                            "Removing old bond"
                                    + (" record: " + existingDevice)
                                    + (" for the device: " + device));
                } else {
                    Log.e(
                            TAG,
                            "Unexpected error while removing old bond"
                                    + (" record:" + existingDevice)
                                    + (" for the device: " + device));
                }
                break;
            }
        }
    }

    int getDiscoverableTimeout() {
        return mDiscoverableTimeout;
    }

    boolean setDiscoverableTimeout(int timeout) {
        synchronized (mObject) {
            return mService.getNative()
                    .setAdapterProperty(
                            AbstractionLayer.BT_PROPERTY_ADAPTER_DISCOVERABLE_TIMEOUT,
                            Utils.intToByteArray(timeout));
        }
    }

    int getProfileConnectionState(int profile) {
        synchronized (mObject) {
            Pair<Integer, Integer> p = mProfileConnectionState.get(profile);
            if (p != null) {
                return p.first;
            }
            return STATE_DISCONNECTED;
        }
    }

    long discoveryEndMillis() {
        return mDiscoveryEndMs;
    }

    boolean isNativeDiscovering() {
        return mNativeDiscovering;
    }

    void updateOnProfileConnectionChanged(
            BluetoothDevice device, int profile, int newState, int prevState) {
        String logInfo =
                ("profile=" + BluetoothProfile.getProfileName(profile))
                        + (" device=" + device)
                        + (" state [" + prevState + " -> " + newState + "]");
        debugLog("updateOnProfileConnectionChanged: " + logInfo);
        if (!isNormalStateTransition(prevState, newState)) {
            Log.w(TAG, "updateOnProfileConnectionChanged: Unexpected transition. " + logInfo);
        }
        MetricsLogger.getInstance().logDeviceConnectionStateChanges(device, profile, newState);
        if (!validateProfileConnectionState(newState)
                || !validateProfileConnectionState(prevState)) {
            // Previously, an invalid state was broadcast anyway,
            // with the invalid state converted to -1 in the intent.
            // Better to log an error and not send an intent with
            // invalid contents or set mAdapterConnectionState to -1.
            Log.e(TAG, "updateOnProfileConnectionChanged: Invalid transition. " + logInfo);
            return;
        }

        synchronized (mObject) {
            updateProfileConnectionState(profile, newState, prevState);

            if (!updateCountersAndCheckForConnectionStateChange(profile, newState, prevState)) {
                // No need for ACTION_CONNECTION_STATE_CHANGED. Device connection is the same.
                return;
            }
            int newAdapterState = convertToAdapterState(newState);
            int prevAdapterState = convertToAdapterState(prevState);
            setConnectionState(newAdapterState);

            Intent intent =
                    new Intent(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
                            .putExtra(BluetoothDevice.EXTRA_DEVICE, device)
                            .putExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE, newAdapterState)
                            .putExtra(
                                    BluetoothAdapter.EXTRA_PREVIOUS_CONNECTION_STATE,
                                    prevAdapterState)
                            .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            MetricsLogger.getInstance()
                    .logProfileConnectionStateChange(device, profile, newState, prevState);
            debugLog("updateOnProfileConnectionChanged: " + logInfo);
            mService.sendBroadcast(intent, BLUETOOTH_CONNECT, Util.getTempBroadcastBundle());
        }
    }

    private static boolean validateProfileConnectionState(int state) {
        return (state == STATE_DISCONNECTED
                || state == STATE_CONNECTING
                || state == STATE_CONNECTED
                || state == STATE_DISCONNECTING);
    }

    private static int convertToAdapterState(int state) {
        return switch (state) {
            case STATE_DISCONNECTED -> BluetoothAdapter.STATE_DISCONNECTED;
            case STATE_DISCONNECTING -> BluetoothAdapter.STATE_DISCONNECTING;
            case STATE_CONNECTED -> BluetoothAdapter.STATE_CONNECTED;
            case STATE_CONNECTING -> BluetoothAdapter.STATE_CONNECTING;
            default -> {
                Log.e(TAG, "convertToAdapterState, unknown state " + state);
                yield -1;
            }
        };
    }

    private static boolean isNormalStateTransition(int prevState, int nextState) {
        return switch (prevState) {
            case STATE_DISCONNECTED -> nextState == STATE_CONNECTING;
            case STATE_CONNECTED -> nextState == STATE_DISCONNECTING;
            case STATE_DISCONNECTING, STATE_CONNECTING ->
                    (nextState == STATE_DISCONNECTED) || (nextState == STATE_CONNECTED);
            default -> false;
        };
    }

    private void throwIllegalStateTransition(int profile, int state, int prevState) {
        throw new IllegalStateException(
                "Received invalid sate transition for profile="
                        + BluetoothProfile.getProfileName(profile)
                        + ": "
                        + BluetoothProfile.getConnectionStateName(prevState)
                        + " -> "
                        + state
                        + ". connecting:"
                        + mProfilesConnecting
                        + " connected:"
                        + mProfilesConnected
                        + " disconnecting:"
                        + mProfilesDisconnecting);
    }

    private boolean updateCountersAndCheckForConnectionStateChange(
            int profile, int state, int prevState) {
        switch (prevState) {
            case STATE_CONNECTING -> {
                if (mProfilesConnecting > 0) {
                    mProfilesConnecting--;
                } else {
                    throwIllegalStateTransition(profile, state, prevState);
                }
            }
            case STATE_CONNECTED -> {
                if (mProfilesConnected > 0) {
                    mProfilesConnected--;
                } else {
                    throwIllegalStateTransition(profile, state, prevState);
                }
            }
            case STATE_DISCONNECTING -> {
                if (mProfilesDisconnecting > 0) {
                    mProfilesDisconnecting--;
                } else {
                    throwIllegalStateTransition(profile, state, prevState);
                }
            }
            default -> {} // Nothing to do
        }

        return switch (state) {
            case STATE_CONNECTING -> {
                mProfilesConnecting++;
                yield (mProfilesConnected == 0 && mProfilesConnecting == 1);
            }
            case STATE_CONNECTED -> {
                mProfilesConnected++;
                yield (mProfilesConnected == 1);
            }
            case STATE_DISCONNECTING -> {
                mProfilesDisconnecting++;
                yield (mProfilesConnected == 0 && mProfilesDisconnecting == 1);
            }
            case STATE_DISCONNECTED -> (mProfilesConnected == 0 && mProfilesConnecting == 0);
            default -> true;
        };
    }

    private void updateProfileConnectionState(int profile, int newState, int oldState) {
        // mProfileConnectionState is a hashmap - <Integer, Pair<Integer, Integer>>
        // The key is the profile, the value is a pair. first element is the state
        // and the second element is the number of devices in that state.
        int numDev = 1;
        int newHashState = newState;
        boolean update = true;

        // The following conditions are considered in this function:
        // 1. If there is no record of profile and state - update
        // 2. If a new device's state is current hash state - increment
        //    number of devices in the state.
        // 3. If a state change has happened to Connected or Connecting
        //    (if current state is not connected), update.
        // 4. If numDevices is 1 and that device state is being updated, update
        // 5. If numDevices is > 1 and one of the devices is changing state, decrement numDevices
        //     but maintain oldState if it is Connected or Connecting
        Pair<Integer, Integer> stateNumDev = mProfileConnectionState.get(profile);
        if (stateNumDev != null) {
            int currHashState = stateNumDev.first;
            numDev = stateNumDev.second;

            if (newState == currHashState) {
                numDev++;
            } else if (newState == STATE_CONNECTED
                    || (newState == STATE_CONNECTING && currHashState != STATE_CONNECTED)) {
                numDev = 1;
            } else if (numDev == 1 && oldState == currHashState) {
                update = true;
            } else if (numDev > 1 && oldState == currHashState) {
                numDev--;

                if (currHashState == STATE_CONNECTED || currHashState == STATE_CONNECTING) {
                    newHashState = currHashState;
                }
            } else {
                update = false;
            }
        }

        if (update) {
            mProfileConnectionState.put(profile, new Pair<>(newHashState, numDev));
            invalidateGetProfileConnectionStateCache();
        }
    }

    void adapterPropertyChangedCallback(int[] types, byte[][] values) {
        mHandler.post(() -> adapterPropertyChangedCallbackInternal(types, values));
    }

    private void adapterPropertyChangedCallbackInternal(int[] types, byte[][] values) {
        int type;
        byte[] val;
        for (int i = 0; i < types.length; i++) {
            val = values[i];
            type = types[i];
            infoLog("adapterPropertyChangedCallback with type:" + type + " len:" + val.length);
            synchronized (mObject) {
                switch (type) {
                    case AbstractionLayer.BT_PROPERTY_BDADDR -> {
                        if (Arrays.equals(mAddress, val)) {
                            debugLog("Address already set");
                            break;
                        }
                        mAddress = val;
                        String address = Utils.getAddressStringFromByte(mAddress);
                        mService.updateAdapterAddress(address);
                    }
                    case AbstractionLayer.BT_PROPERTY_CLASS_OF_DEVICE -> {
                        if (val == null || val.length != 3) {
                            debugLog("Invalid BT CoD value from stack.");
                            return;
                        }
                        int bluetoothClass =
                                ((int) val[0] << 16) + ((int) val[1] << 8) + (int) val[2];
                        if (bluetoothClass != 0) {
                            mBluetoothClass = new BluetoothClass(bluetoothClass);
                        }
                        debugLog("BT Class:" + mBluetoothClass);
                    }
                    case AbstractionLayer.BT_PROPERTY_UUIDS -> {
                        mUuids = Utils.byteArrayToUuid(val);
                    }
                    case AbstractionLayer.BT_PROPERTY_ADAPTER_BONDED_DEVICES ->
                            updateBondedDevices(val);
                    case AbstractionLayer.BT_PROPERTY_ADAPTER_DISCOVERABLE_TIMEOUT -> {
                        mDiscoverableTimeout = Utils.byteArrayToInt(val, 0);
                        debugLog("Discoverable Timeout:" + mDiscoverableTimeout);
                    }
                    case AbstractionLayer.BT_PROPERTY_LOCAL_LE_FEATURES -> {
                        updateFeatureSupport(val);
                        mService.updateLeAudioProfileServiceState();
                    }
                    case AbstractionLayer.BT_PROPERTY_DYNAMIC_AUDIO_BUFFER ->
                            updateDynamicAudioBufferSupport(val);
                    case AbstractionLayer.BT_PROPERTY_LPP_OFFLOAD_FEATURES ->
                            updateLppOffloadFeatureSupport(val);
                    default -> Log.e(TAG, "Property change not handled in Java land:" + type);
                }
            }
        }
    }

    private void updateBondedDevices(byte[] val) {
        int number = val.length / TYPED_BD_ADDR_LEN;
        int addressType;
        byte[] addrByte = new byte[BD_ADDR_LEN];
        for (int j = 0; j < number; j++) {
            System.arraycopy(val, j * TYPED_BD_ADDR_LEN, addrByte, 0, BD_ADDR_LEN);
            addressType = val[(j * TYPED_BD_ADDR_LEN) + BD_ADDR_LEN];
            String address = Utils.getAddressStringFromByte(addrByte);

            debugLog(
                    "updateBondedDevices: Add device: "
                            + BluetoothUtils.toAnonymizedAddress(address)
                            + ("[" + Util.addressTypeToString(addressType) + "]"));

            BluetoothDevice device =
                    Flags.retainAddressType()
                            ? mService.getRemoteDevice(address, addressType)
                            : mAdapter.getRemoteDevice(address);
            onBondStateChanged(device, BluetoothDevice.BOND_BONDED);
        }
    }

    private void updateFeatureSupport(byte[] val) {
        mVersSupported = ((0xFF & ((int) val[1])) << 8) + (0xFF & ((int) val[0]));
        mNumOfAdvertisementInstancesSupported = (0xFF & ((int) val[3]));
        var rpaOffloadSupported = ((0xFF & ((int) val[4])) != 0);
        var numOfOffloadedIrkSupported = (0xFF & ((int) val[5]));
        mNumOfOffloadedScanFilterSupported = (0xFF & ((int) val[6]));
        mIsActivityAndEnergyReporting = ((0xFF & ((int) val[7])) != 0);
        mOffloadedScanResultStorageBytes = ((0xFF & ((int) val[9])) << 8) + (0xFF & ((int) val[8]));
        mTotNumOfTrackableAdv = ((0xFF & ((int) val[11])) << 8) + (0xFF & ((int) val[10]));
        mIsExtendedScanSupported = ((0xFF & ((int) val[12])) != 0);
        mIsDebugLogSupported = ((0xFF & ((int) val[13])) != 0);
        mIsLe2MPhySupported = ((0xFF & ((int) val[14])) != 0);
        mIsLeCodedPhySupported = ((0xFF & ((int) val[15])) != 0);
        mIsLeExtendedAdvertisingSupported = ((0xFF & ((int) val[16])) != 0);
        mIsLePeriodicAdvertisingSupported = ((0xFF & ((int) val[17])) != 0);
        mLeMaximumAdvertisingDataLength =
                (0xFF & ((int) val[18])) + ((0xFF & ((int) val[19])) << 8);
        mDynamicAudioBufferSizeSupportedCodecsGroup1 =
                ((0xFF & ((int) val[21])) << 8) + (0xFF & ((int) val[20]));
        mDynamicAudioBufferSizeSupportedCodecsGroup2 =
                ((0xFF & ((int) val[23])) << 8) + (0xFF & ((int) val[22]));
        mIsLePeriodicAdvertisingSyncTransferSenderSupported = ((0xFF & ((int) val[24])) != 0);
        mIsLeConnectedIsochronousStreamCentralSupported = ((0xFF & ((int) val[25])) != 0);
        mIsLeIsochronousBroadcasterSupported = ((0xFF & ((int) val[26])) != 0);
        mIsLePeriodicAdvertisingSyncTransferRecipientSupported = ((0xFF & ((int) val[27])) != 0);
        mIsOffloadedTransportDiscoveryDataScanSupported = ((0x01 & ((int) val[28])) != 0);
        mIsLeChannelSoundingSupported = ((0xFF & ((int) val[30])) != 0);
        mIsLeHighDataThroughputPhySupported = ((0xFF & ((int) val[31])) != 0);
        mIsLeConnectedIsochronousStreamPeripheralSupported = ((0xFF & ((int) val[32])) != 0);
        mIsLeBigSetChannelClassificationSupported = ((0xFF & ((int) val[33])) != 0);

        debugLog(
                "BT_PROPERTY_LOCAL_LE_FEATURES: update from BT controller"
                        + (" mNumOfAdvertisementInstancesSupported="
                                + mNumOfAdvertisementInstancesSupported)
                        + (", rpaOffloadSupported=" + rpaOffloadSupported)
                        + (", numOfOffloadedIrkSupported=" + numOfOffloadedIrkSupported)
                        + (", numOfOffloadedScanFilterSupported="
                                + mNumOfOffloadedScanFilterSupported)
                        + (", offloadedScanResultStorageBytes= " + mOffloadedScanResultStorageBytes)
                        + (", isActivityAndEnergyReporting=" + mIsActivityAndEnergyReporting)
                        + (", versSupported=" + mVersSupported)
                        + (", totNumOfTrackableAdv=" + mTotNumOfTrackableAdv)
                        + (", isExtendedScanSupported=" + mIsExtendedScanSupported)
                        + (", isDebugLogSupported=" + mIsDebugLogSupported)
                        + (", isLe2MPhySupported=" + mIsLe2MPhySupported)
                        + (", isLeCodedPhySupported=" + mIsLeCodedPhySupported)
                        + (", isLeExtendedAdvertisingSupported="
                                + mIsLeExtendedAdvertisingSupported)
                        + (", isLePeriodicAdvertisingSupported="
                                + mIsLePeriodicAdvertisingSupported)
                        + (", leMaximumAdvertisingDataLength=" + mLeMaximumAdvertisingDataLength)
                        + (", dynamicAudioBufferSizeSupportedCodecsGroup1="
                                + mDynamicAudioBufferSizeSupportedCodecsGroup1)
                        + (", dynamicAudioBufferSizeSupportedCodecsGroup2="
                                + mDynamicAudioBufferSizeSupportedCodecsGroup2)
                        + (", isLePeriodicAdvertisingSyncTransferSenderSupported="
                                + mIsLePeriodicAdvertisingSyncTransferSenderSupported)
                        + (", isLeConnectedIsochronousStreamCentralSupported="
                                + mIsLeConnectedIsochronousStreamCentralSupported)
                        + (", isLeConnectedIsochronousStreamPeripheralSupported="
                                + mIsLeConnectedIsochronousStreamPeripheralSupported)
                        + (", isLeIsochronousBroadcasterSupported="
                                + mIsLeIsochronousBroadcasterSupported)
                        + (", isLePeriodicAdvertisingSyncTransferRecipientSupported="
                                + mIsLePeriodicAdvertisingSyncTransferRecipientSupported)
                        + (", isOffloadedTransportDiscoveryDataScanSupported="
                                + mIsOffloadedTransportDiscoveryDataScanSupported)
                        + (", isLeChannelSoundingSupported = " + mIsLeChannelSoundingSupported)
                        + (", isLeHighDataThroughputPhySupported = "
                                + mIsLeHighDataThroughputPhySupported)
                        + (", isLeBigSetChannelClassificationSupported = "
                                + mIsLeBigSetChannelClassificationSupported));
        invalidateIsOffloadedFilteringSupportedCache();
    }

    private void updateDynamicAudioBufferSupport(byte[] val) {
        if (mBufferConstraintList.isDone()) {
            return;
        }

        // bufferConstraints is the table indicates the capability of all the codecs
        // with buffer time. The raw is codec number, and the column is buffer type. There are 3
        // buffer types - default/maximum/minimum.
        // The maximum number of raw is BUFFER_CODEC_MAX_NUM(32).
        // The maximum number of column is BUFFER_TYPE_MAX(3).
        // The array element indicates the buffer time, the size is two octet.
        List<BufferConstraint> bufferConstraintList = new ArrayList<>();

        for (int i = 0; i < BufferConstraints.BUFFER_CODEC_MAX_NUM; i++) {
            int defaultBufferTime =
                    ((0xFF & ((int) val[i * 6 + 1])) << 8) + (0xFF & ((int) val[i * 6]));
            int maximumBufferTime =
                    ((0xFF & ((int) val[i * 6 + 3])) << 8) + (0xFF & ((int) val[i * 6 + 2]));
            int minimumBufferTime =
                    ((0xFF & ((int) val[i * 6 + 5])) << 8) + (0xFF & ((int) val[i * 6 + 4]));
            bufferConstraintList.add(
                    new BufferConstraint(defaultBufferTime, maximumBufferTime, minimumBufferTime));
        }

        mBufferConstraintList.complete(bufferConstraintList);
    }

    int getNumberOfSupportedOffloadedLeCocSockets() {
        return mNumberOfSupportedOffloadedLeCocSockets;
    }

    int getNumberOfSupportedOffloadedRfcommSockets() {
        return mNumberOfSupportedOffloadedRfcommSockets;
    }

    int getSupportedOffloadedGattClientProperties() {
        return mSupportedOffloadedGattClientProperties;
    }

    int getSupportedOffloadedGattServerProperties() {
        return mSupportedOffloadedGattServerProperties;
    }

    private void updateLppOffloadFeatureSupport(byte[] val) {
        if (val == null || val.length < 4) {
            Log.e(TAG, "BT_PROPERTY_LPP_OFFLOAD_FEATURES: invalid value length");
            return;
        }
        mNumberOfSupportedOffloadedLeCocSockets = (0xFF & ((int) val[0]));
        mNumberOfSupportedOffloadedRfcommSockets = (0xFF & ((int) val[1]));
        mSupportedOffloadedGattClientProperties = (0xFF & ((int) val[2]));
        mSupportedOffloadedGattServerProperties = (0xFF & ((int) val[3]));

        debugLog(
                "BT_PROPERTY_LPP_OFFLOAD_FEATURES: update from Offload HAL"
                        + " mNumberOfSupportedOffloadedLeCocSockets = "
                        + mNumberOfSupportedOffloadedLeCocSockets
                        + " mNumberOfSupportedOffloadedRfcommSockets = "
                        + mNumberOfSupportedOffloadedRfcommSockets
                        + " mSupportedOffloadedGattClientProperties = "
                        + mSupportedOffloadedGattClientProperties
                        + " mSupportedOffloadedGattServerProperties = "
                        + mSupportedOffloadedGattServerProperties);
    }

    void onBluetoothReady() {
        debugLog("onBluetoothReady ScanMode=" + mScanMode);

        synchronized (mObject) {
            // Reset adapter and profile connection states
            setConnectionState(BluetoothAdapter.STATE_DISCONNECTED);
            mProfileConnectionState.clear();
            invalidateGetProfileConnectionStateCache();
            mProfilesConnected = 0;
            mProfilesConnecting = 0;
            mProfilesDisconnecting = 0;
            // This keeps NV up-to date on first-boot after flash.
            setDiscoverableTimeout(mDiscoverableTimeout);
        }
    }

    void discoveryStateChangeCallback(int state) {
        infoLog("Callback:discoveryStateChangeCallback with state:" + state);
        synchronized (mObject) {
            Intent intent;
            if (state == AbstractionLayer.BT_DISCOVERY_STOPPED) {
                mNativeDiscovering = false;
                mService.clearDiscoveryData();
                mDiscoveryEndMs = System.currentTimeMillis();
                intent = new Intent(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
                mService.sendBroadcast(
                        intent, BLUETOOTH_SCAN, getBroadcastOptionsForDiscoveryFinished());
            } else if (state == AbstractionLayer.BT_DISCOVERY_STARTED) {
                mNativeDiscovering = true;
                mDiscoveryEndMs = System.currentTimeMillis() + DEFAULT_DISCOVERY_TIMEOUT_MS;
                intent = new Intent(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
                mService.sendBroadcast(intent, BLUETOOTH_SCAN, Util.getTempBroadcastBundle());
            }
        }
    }

    /**
     * @return broadcast options for ACTION_DISCOVERY_FINISHED broadcast
     */
    private static @NonNull Bundle getBroadcastOptionsForDiscoveryFinished() {
        final BroadcastOptions options = Util.getTempBroadcastOptions();
        options.setDeliveryGroupPolicy(BroadcastOptions.DELIVERY_GROUP_POLICY_MOST_RECENT);
        options.setDeferralPolicy(BroadcastOptions.DEFERRAL_POLICY_UNTIL_ACTIVE);
        return options.toBundle();
    }

    protected void dump(PrintWriter writer) {
        writer.println(TAG);
        writer.println("  " + "Name: " + mService.getName());
        writer.println("  " + "Address: " + Util.getRedactedAddressStringFromByte(mAddress));
        writer.println("  " + "ConnectionState: " + dumpConnectionState(getConnectionState()));
        writer.println("  " + "MaxConnectedAudioDevices: " + getMaxConnectedAudioDevices());
        writer.println("  " + "A2dpOffloadEnabled: " + mA2dpOffloadEnabled);
        writer.println("  " + "Discovering: " + mService.isDiscovering());
        writer.println("  " + "DiscoveryEndMs: " + mDiscoveryEndMs);
        writer.println();
    }

    private static String dumpConnectionState(int state) {
        return switch (state) {
            case BluetoothAdapter.STATE_DISCONNECTED -> "STATE_DISCONNECTED";
            case BluetoothAdapter.STATE_DISCONNECTING -> "STATE_DISCONNECTING";
            case BluetoothAdapter.STATE_CONNECTING -> "STATE_CONNECTING";
            case BluetoothAdapter.STATE_CONNECTED -> "STATE_CONNECTED";
            default -> "Unknown Connection State " + state;
        };
    }

    private static void infoLog(String msg) {
        Log.i(TAG, msg);
    }

    private static void debugLog(String msg) {
        Log.d(TAG, msg);
    }
}
