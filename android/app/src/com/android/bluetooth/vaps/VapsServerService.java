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

package com.android.bluetooth.vaps;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.BLUETOOTH_PRIVILEGED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothUtils.RemoteExceptionIgnoringConsumer;

import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElseGet;

import android.bluetooth.BluetoothCsipSetCoordinator;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothLeAudio;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.BluetoothUuid;
import com.android.bluetooth.le_audio.ContentControlIdKeeper;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.RemoteCallbackList;
import android.util.Log;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.provider.Settings;
import android.database.ContentObserver;
import android.net.Uri;

import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.ActiveDeviceManager;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.ProfileService;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.bluetooth.flags.Flags;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

/** Provides Bluetooth Voice Assistant profile, as a service. */
public class VapsServerService extends ProfileService {
    private static final String TAG = VapsServerService.class.getSimpleName();

    private static VapsServerService sVapsServer;
    private final AssistantSettingObserver mAssistantSettingObserver;
    private final Handler mHandler;
    private final VapsServerNativeInterface mNativeInterface;

    public static boolean isEnabled() {
        return (Flags.addProfileAsIntentExtra() ? true : false);
    }

    @VisibleForTesting
    static synchronized void setVapsServer(VapsServerService instance) {
        Log.d(TAG, "setVapsServer(): set to: " + instance);
        sVapsServer = instance;
    }

    /**
     * Get the VapsServerService instance
     *
     * @return VapsServerService instance
     */
    public static synchronized VapsServerService getVapsServerService() {
        if (sVapsServer == null) {
            Log.w(TAG, "getVapsServerService(): service is NULL");
            return null;
        }

        if (!sVapsServer.isAvailable()) {
            Log.w(TAG, "getVapsServerService(): service is not available");
            return null;
        }
        return sVapsServer;
    }

    public VapsServerService(AdapterService adapterService) {
        this(adapterService, null, null);
    }

    @VisibleForTesting
    VapsServerService(
            AdapterService adapterService,
            Looper looper,
            VapsServerNativeInterface nativeInterface) {
        super(BluetoothProfile.VAPS_SERVER, adapterService);
        mNativeInterface =
                requireNonNullElseGet(
                        nativeInterface,
                        () ->
                                new VapsServerNativeInterface(
                                        new VapsServerNativeCallback(adapterService, this)));
        Log.d(TAG, " VapsServerService(): service is starting");

        if (looper == null) {
            mHandler = new Handler(requireNonNull(Looper.getMainLooper()));
        } else {
            mHandler = new Handler(looper);
        }

        // Initialize native interface
        mNativeInterface.init();

        // Mark service as started
        setVapsServer(this);

        mAssistantSettingObserver = new AssistantSettingObserver();
        getContentResolver().registerContentObserver(
            Settings.Secure.getUriFor("assistant"), false, mAssistantSettingObserver);
    }

    @Override
    protected IProfileServiceBinder initBinder() {
        return null;
    }

    @Override
    public void cleanup() {
        Log.i(TAG, "Cleanup VapsServer Service");

        if (sVapsServer == null) {
            Log.w(TAG, "cleanup() called before initialization");
            return;
        }

        // Marks service as stopped
        setVapsServer(null);

        // Unregister Handler and stop all queued messages.
        mHandler.removeCallbacksAndMessages(null);

        // Cleanup GATT interface
        mNativeInterface.cleanup();

        getContentResolver().unregisterContentObserver(mAssistantSettingObserver);
    }

    private class AssistantSettingObserver extends ContentObserver {
        private AssistantSettingObserver() {
            super(mHandler);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            super.onChange(selfChange, uri);
            String vaeName = getCurrentVaeName();
            Log.d(TAG, " Voice Assistant changed");
            mNativeInterface.setVaeName(vaeName);
        }
    }

    /** Process a change in the bonding state for a device */
    public void handleBondStateChanged(BluetoothDevice device, int fromState, int toState) {
        mHandler.post(() -> bondStateChanged(device, toState));
    }

    @VisibleForTesting
    void bondStateChanged(BluetoothDevice device, int bondState) {
        Log.d(TAG, "Bond state changed for device: " + device + " state: " + bondState);

        if (bondState != BluetoothDevice.BOND_NONE) {
            return;
        }
    }

    @Override
    public void dump(StringBuilder sb) {
        super.dump(sb);
    }

    public void setCcid() {
        int ccid =
                ContentControlIdKeeper.acquireCcid(
                        mAdapterService,
                        BluetoothUuid.VAPS,
                        BluetoothLeAudio.CONTEXT_TYPE_VOICE_ASSISTANTS);
        if (ccid == ContentControlIdKeeper.CCID_INVALID) {
            Log.e(TAG, "Unable to acquire valid CCID!");
            return;
        }
        Log.d(TAG, "CCID acquired: " + ccid);
        mNativeInterface.setCcid(ccid);
    }

    public void setVaeName() {
        String vaeName = getCurrentVaeName();
        mNativeInterface.setVaeName(vaeName);
    }

    public String getCurrentVaeName() {
        //Get Default Digital Assistant from Settings
        String assistantName =
            Settings.Secure.getString(getApplicationContext().getContentResolver(), "assistant");
        Log.d(TAG, " assistantName"+ assistantName);
        if (assistantName != null) {
            Log.d(TAG, " component Name:"+ ComponentName.unflattenFromString(assistantName));
        }
        String vaeName = assistantName;
        String[] parts = assistantName.split("/");

        if (parts.length == 2) {
            vaeName = parts[0];
        }
        Log.d(TAG, " vae Name:"+ vaeName);
        return vaeName;
    }

    public boolean activateVoiceRecognition(BluetoothDevice device) {
        Intent intent = new Intent(Intent.ACTION_VOICE_COMMAND);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothProfile.EXTRA_PROFILE, BluetoothProfile.LE_AUDIO);

        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Log.d(TAG, "activateVoiceRecognition: ");
        try {
            sVapsServer.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "activateVoiceRecognition, failed due to activity not found for " + intent);
            return false;
        }
        return true;
    }

    public boolean deactivateVoiceRecognition(BluetoothDevice device) {
        Log.d(TAG, "deactivateVoiceRecognition: ");
        Intent intent = new Intent(Intent.ACTION_STOP_VOICE_COMMAND);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothProfile.EXTRA_PROFILE, BluetoothProfile.LE_AUDIO);
        sVapsServer.sendBroadcast(intent);
        return true;
    }

    void messageFromNative(VapsServerStackEvent stackEvent) {
        if (!isAvailable()) {
            Log.e(TAG, "Event ignored, service not available: " + stackEvent);
            return;
        }
        BluetoothDevice device = stackEvent.device;

        switch (stackEvent.type) {
            case VapsServerStackEvent.EVENT_TYPE_ON_INITIALIZED -> {
                Log.d(TAG, "onInitialized");
                setCcid();
                Log.d(TAG, "Calling setVaeName after initialization");
                setVaeName();
            }
            case VapsServerStackEvent.EVENT_TYPE_ON_START_VA_SESSION -> {
                Log.d(TAG, "start VA session by remote Headset:" + device);

                if (!activateVoiceRecognition(device)) {
                    Log.w(TAG, "start VA session by remote Headset: failed request from " + device);
                }
            }
            case VapsServerStackEvent.EVENT_TYPE_ON_STOP_VA_SESSION -> {
                Log.d(TAG, "stop VA session by remote Headset:"+ device);
                if (!deactivateVoiceRecognition(device)) {
                    Log.w(TAG, "stop VA session by remote Headset: failed request from " + device);
                }
            }
            default -> {}
        }
    }
}

