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

package com.android.bluetooth.hfp;

import static com.android.bluetooth.Utils.BackgroundExecutor;

import static java.util.Objects.requireNonNull;

import android.bluetooth.BluetoothDevice;
import android.os.Handler;
import android.os.Looper;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SignalStrengthUpdateRequest;
import android.telephony.SubscriptionManager;
import android.telephony.SubscriptionManager.OnSubscriptionsChangedListener;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.bluetooth.btservice.AdapterService;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.Collections;
import java.util.HashMap;
import java.util.concurrent.ExecutionException;

/**
 * Class that manages Telephony states
 *
 * <p>Note: The methods in this class are not thread safe, don't call them from multiple threads.
 * Call them from the HeadsetPhoneStateMachine message handler only.
 */
public class HeadsetPhoneState {
    private static final String TAG = HeadsetPhoneState.class.getSimpleName();

    private final HeadsetService mHeadsetService;
    private final TelephonyManager mTelephonyManager;
    private final SubscriptionManager mSubscriptionManager;
    private final Handler mHandler;

    private ServiceState mServiceState;

    // HFP 1.6 CIND service value
    private int mCindService = HeadsetHalConstants.NETWORK_STATE_NOT_AVAILABLE;
    // Number of active (foreground) calls
    private int mNumActive;
    // Current Call Setup State
    private int mCallState = HeadsetHalConstants.CALL_STATE_IDLE;
    // Number of held (background) calls
    private int mNumHeld;
    // HFP 1.6 CIND signal value
    private int mCindSignal;
    // HFP 1.6 CIND roam value
    private int mCindRoam = HeadsetHalConstants.SERVICE_TYPE_HOME;
    // HFP 1.6 CIND battchg value
    private int mCindBatteryCharge;

    @GuardedBy("mDeviceEventMap")
    private final HashMap<BluetoothDevice, Integer> mDeviceEventMap = new HashMap<>();

    private final OnSubscriptionsChangedListener mOnSubscriptionsChangedListener =
            new HeadsetPhoneStateOnSubscriptionChangedListener();

    private HeadsetPhoneStateListener mPhoneStateListener;

    HeadsetPhoneState(AdapterService adapterService, HeadsetService headsetService, Looper looper) {
        mHeadsetService = requireNonNull(headsetService);
        mTelephonyManager = requireNonNull(adapterService.getSystemService(TelephonyManager.class));
        // Register for SubscriptionInfo list changes which is guaranteed to invoke
        // onSubscriptionInfoChanged and which in turns calls loadInBackground.
        mSubscriptionManager =
                requireNonNull(adapterService.getSystemService(SubscriptionManager.class));

        // Initialize subscription on the handler thread
        mHandler = new Handler(looper);
        mSubscriptionManager.addOnSubscriptionsChangedListener(
                mHandler::post, mOnSubscriptionsChangedListener);
    }

    /** Cleanup this instance. Instance can no longer be used after calling this method. */
    public void cleanup() {
        mSubscriptionManager.removeOnSubscriptionsChangedListener(mOnSubscriptionsChangedListener);
        synchronized (mDeviceEventMap) {
            mDeviceEventMap.clear();
            stopListenForPhoneState();
        }
    }

    @Override
    public String toString() {
        int telephonyEvents;
        synchronized (mDeviceEventMap) {
            telephonyEvents = getTelephonyEventsToListen();
        }
        return "HeadsetPhoneState "
                + ("[mTelephonyServiceAvailability=" + mCindService)
                + (", mNumActive=" + mNumActive)
                + (", mCallState=" + mCallState)
                + (", mNumHeld=" + mNumHeld)
                + (", mSignal=" + mCindSignal)
                + (", mRoam=" + mCindRoam)
                + (", mBatteryCharge=" + mCindBatteryCharge)
                + (", TelephonyEvents=" + telephonyEvents + "]");
    }

    @GuardedBy("mDeviceEventMap")
    private int getTelephonyEventsToListen() {
        return mDeviceEventMap.values().stream()
                .reduce(PhoneStateListener.LISTEN_NONE, (a, b) -> a | b);
    }

    /**
     * Start or stop listening for phone state change
     *
     * @param device remote device that subscribes to this phone state update
     * @param events events in {@link PhoneStateListener} to listen to
     */
    void listenForPhoneState(BluetoothDevice device, int events) {
        synchronized (mDeviceEventMap) {
            int prevEvents = getTelephonyEventsToListen();
            if (events == PhoneStateListener.LISTEN_NONE) {
                mDeviceEventMap.remove(device);
            } else {
                mDeviceEventMap.put(device, events);
            }
            int updatedEvents = getTelephonyEventsToListen();
            if (prevEvents != updatedEvents) {
                stopListenForPhoneState();
                startListenForPhoneState();
            }
        }
    }

    @GuardedBy("mDeviceEventMap")
    private void startListenForPhoneState() {
        int events = getTelephonyEventsToListen();
        Runnable asyncRunnable =
                () -> {
                    if (mPhoneStateListener != null) {
                        Log.w(TAG, "startListenForPhoneState: already listening");
                        return;
                    }
                    if (events == PhoneStateListener.LISTEN_NONE) {
                        Log.w(TAG, "startListenForPhoneState: no event to listen");
                        return;
                    }
                    int subId = SubscriptionManager.getDefaultSubscriptionId();
                    if (!SubscriptionManager.isValidSubscriptionId(subId)) {
                        // Will retry listening for phone state in onSubscriptionsChanged() callback
                        Log.w(TAG, "startListenForPhoneState: invalid subId=" + subId);
                        return;
                    }
                    Log.i(TAG, "startListenForPhoneState: subId=" + subId + " events=" + events);
                    mPhoneStateListener = new HeadsetPhoneStateListener(events);
                };
        try {
            BackgroundExecutor.submit(asyncRunnable).get();
        } catch (ExecutionException | InterruptedException e) {
            Log.e(TAG, "Exception in startListenForPhoneState", e);
        }
    }

    @GuardedBy("mDeviceEventMap")
    private void stopListenForPhoneState() {
        Runnable asyncRunnable =
                () -> {
                    if (mPhoneStateListener == null) {
                        Log.i(TAG, "stopListenForPhoneState: no listener");
                        return;
                    }
                    mPhoneStateListener.stopListener();
                    mPhoneStateListener = null;
                };
        // We intentionally drop this future. If `start` is called afterward, it will implicitly
        // await completion. Otherwise, the stack is shutting down, making a wait unnecessary.
        var unusedFuture = BackgroundExecutor.submit(asyncRunnable);
    }

    int getCindService() {
        return mCindService;
    }

    int getNumActiveCall() {
        return mNumActive;
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public void setNumActiveCall(int numActive) {
        mNumActive = numActive;
    }

    int getCallState() {
        return mCallState;
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public void setCallState(int callState) {
        mCallState = callState;
    }

    int getNumHeldCall() {
        return mNumHeld;
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public void setNumHeldCall(int numHeldCall) {
        mNumHeld = numHeldCall;
    }

    ServiceState getServiceState() {
        return mServiceState;
    }

    int getCindSignal() {
        return mCindSignal;
    }

    int getCindRoam() {
        return mCindRoam;
    }

    /**
     * Set battery level value used for +CIND result
     *
     * @param batteryLevel battery level value
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public void setCindBatteryCharge(int batteryLevel) {
        if (mCindBatteryCharge != batteryLevel) {
            mCindBatteryCharge = batteryLevel;
            sendDeviceStateChanged();
        }
    }

    int getCindBatteryCharge() {
        return mCindBatteryCharge;
    }

    boolean isInCall() {
        return (mNumActive >= 1);
    }

    private synchronized void sendDeviceStateChanged() {
        Log.d(
                TAG,
                "sendDeviceStateChanged. "
                        + ("mService=" + mCindService)
                        + (" mSignal=" + mCindSignal)
                        + (" mRoam=" + mCindRoam)
                        + (" mBatteryCharge=" + mCindBatteryCharge));
        mHeadsetService.onDeviceStateChanged(
                new HeadsetDeviceState(mCindService, mCindRoam, mCindSignal, mCindBatteryCharge));
    }

    private class HeadsetPhoneStateOnSubscriptionChangedListener
            extends OnSubscriptionsChangedListener {
        @Override
        public void onSubscriptionsChanged() {
            synchronized (mDeviceEventMap) {
                int simState = mTelephonyManager.getSimState();
                if (simState != TelephonyManager.SIM_STATE_READY) {
                    mServiceState = null;
                    mCindSignal = 0;
                    mCindService = HeadsetHalConstants.NETWORK_STATE_NOT_AVAILABLE;
                    sendDeviceStateChanged();
                }
                stopListenForPhoneState();
                startListenForPhoneState();
            }
        }
    }

    private class HeadsetPhoneStateListener extends PhoneStateListener {
        private static final SignalStrengthUpdateRequest SIGNAL_STRENGTH_UPDATE_REQUEST =
                new SignalStrengthUpdateRequest.Builder()
                        .setSignalThresholdInfos(Collections.EMPTY_LIST)
                        .setSystemThresholdReportingRequestedWhileIdle(true)
                        .build();

        private final int mEvents;

        HeadsetPhoneStateListener(int events) {
            super(mHandler::post);
            mEvents = events;

            Log.i(TAG, "startListener: events=" + mEvents);
            mTelephonyManager.listen(this, mEvents);
            if ((mEvents & PhoneStateListener.LISTEN_SIGNAL_STRENGTHS) != 0) {
                mTelephonyManager.setSignalStrengthUpdateRequest(SIGNAL_STRENGTH_UPDATE_REQUEST);
            }
        }

        void stopListener() {
            Log.i(TAG, "stopListener: events=" + mEvents);
            if ((mEvents & PhoneStateListener.LISTEN_SIGNAL_STRENGTHS) != 0) {
                mTelephonyManager.clearSignalStrengthUpdateRequest(SIGNAL_STRENGTH_UPDATE_REQUEST);
            }
            mTelephonyManager.listen(this, PhoneStateListener.LISTEN_NONE);
        }

        @Override
        public synchronized void onServiceStateChanged(ServiceState serviceState) {
            mServiceState = serviceState;
            int cindService =
                    (serviceState.getState() == ServiceState.STATE_IN_SERVICE)
                            ? HeadsetHalConstants.NETWORK_STATE_AVAILABLE
                            : HeadsetHalConstants.NETWORK_STATE_NOT_AVAILABLE;
            int newRoam =
                    serviceState.getRoaming()
                            ? HeadsetHalConstants.SERVICE_TYPE_ROAMING
                            : HeadsetHalConstants.SERVICE_TYPE_HOME;

            if (cindService == mCindService && newRoam == mCindRoam) {
                // De-bounce the state change
                return;
            }
            mCindService = cindService;
            if (mCindService == HeadsetHalConstants.NETWORK_STATE_NOT_AVAILABLE) {
                mCindSignal = 0;
            }
            mCindRoam = newRoam;
            sendDeviceStateChanged();
        }

        @Override
        public void onSignalStrengthsChanged(SignalStrength signalStrength) {
            if (mCindService == HeadsetHalConstants.NETWORK_STATE_NOT_AVAILABLE) {
                mCindSignal = 0;
                // sendDeviceStateChanged is sent in onServiceStateChanged for this case
                return;
            }

            int prevSignal = mCindSignal;

            // +CIND "signal" indicator is always between 0 to 5
            mCindSignal = Integer.max(Integer.min(signalStrength.getLevel() + 1, 5), 0);

            // This results in a lot of duplicate messages, hence this check
            if (prevSignal != mCindSignal) {
                sendDeviceStateChanged();
            }
        }
    }
}
