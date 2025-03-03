/*
 * Copyright 2017 The Android Open Source Project
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


import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothSinkAudioPolicy;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.PowerManager;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.bluetooth.telephony.BluetoothInCallService;
import com.android.internal.annotations.VisibleForTesting;

import java.util.List;

/**
 * Defines system calls that is used by state machine/service to either send or receive messages
 * from the Android System.
 */
class HeadsetSystemInterface {
    private static final String TAG = HeadsetSystemInterface.class.getSimpleName();

    private final HeadsetService mHeadsetService;
    private final AudioManager mAudioManager;
    private final HeadsetPhoneState mHeadsetPhoneState;
    private PowerManager.WakeLock mVoiceRecognitionWakeLock;
    private final TelephonyManager mTelephonyManager;
    private final TelecomManager mTelecomManager;

    HeadsetSystemInterface(HeadsetService headsetService) {
        if (headsetService == null) {
            Log.wtf(TAG, "HeadsetService parameter is null");
        }
        mHeadsetService = headsetService;
        mAudioManager = mHeadsetService.getSystemService(AudioManager.class);
        PowerManager powerManager = mHeadsetService.getSystemService(PowerManager.class);
        mVoiceRecognitionWakeLock =
                powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG + ":VoiceRecognition");
        mVoiceRecognitionWakeLock.setReferenceCounted(false);
        mHeadsetPhoneState = new com.android.bluetooth.hfp.HeadsetPhoneState(mHeadsetService);
        mTelephonyManager = mHeadsetService.getSystemService(TelephonyManager.class);
        mTelecomManager = mHeadsetService.getSystemService(TelecomManager.class);
    }

    private static BluetoothInCallService getBluetoothInCallServiceInstance() {
        return BluetoothInCallService.getInstance();
    }

    /** Stop this system interface */
    public synchronized void stop() {
        mHeadsetPhoneState.cleanup();
    }

    /**
     * Get audio manager. Most audio manager operations are pass through and therefore are not
     * individually managed by this class
     *
     * @return audio manager for setting audio parameters
     */
    public AudioManager getAudioManager() {
        return mAudioManager;
    }

    /**
     * Get wake lock for voice recognition
     *
     * @return wake lock for voice recognition
     */
    @VisibleForTesting
    public PowerManager.WakeLock getVoiceRecognitionWakeLock() {
        return mVoiceRecognitionWakeLock;
    }

    /**
     * Get HeadsetPhoneState instance to interact with Telephony service
     *
     * @return HeadsetPhoneState interface to interact with Telephony service
     */
    @VisibleForTesting
    public HeadsetPhoneState getHeadsetPhoneState() {
        return mHeadsetPhoneState;
    }

    /**
     * Answer the current incoming call in Telecom service
     *
     * @param device the Bluetooth device used for answering this call
     */
    @VisibleForTesting
    public void answerCall(BluetoothDevice device) {
        Log.d(TAG, "answerCall");
        if (device == null) {
            Log.w(TAG, "answerCall device is null");
            return;
        }
        BluetoothInCallService bluetoothInCallService = getBluetoothInCallServiceInstance();
        if (bluetoothInCallService == null) {
            Log.e(TAG, "Handsfree phone proxy null for answering call");
            return;
        }
        BluetoothSinkAudioPolicy callAudioPolicy = mHeadsetService.getHfpCallAudioPolicy(device);
        if (callAudioPolicy == null
                || callAudioPolicy.getCallEstablishPolicy()
                        != BluetoothSinkAudioPolicy.POLICY_NOT_ALLOWED) {
            mHeadsetService.setActiveDevice(device);
        }
        bluetoothInCallService.answerCall();
    }

    /**
     * Hangup the current call, could either be Telecom call or virtual call
     *
     * @param device the Bluetooth device used for hanging up this call
     */
    @VisibleForTesting
    public void hangupCall(BluetoothDevice device) {
        if (device == null) {
            Log.w(TAG, "hangupCall device is null");
            return;
        }
        // Close the virtual call if active. Virtual call should be
        // terminated for CHUP callback event
        if (mHeadsetService.isVirtualCallStarted()) {
            mHeadsetService.stopScoUsingVirtualVoiceCall();
        } else {
            BluetoothInCallService bluetoothInCallService = getBluetoothInCallServiceInstance();
            if (bluetoothInCallService != null) {
                bluetoothInCallService.hangupCall();
            } else {
                Log.e(TAG, "Handsfree phone proxy null for hanging up call");
            }
        }
    }

    /**
     * Instructs Telecom to play the specified DTMF tone for the current foreground call
     *
     * @param dtmf dtmf code
     * @param device the Bluetooth device that sent this code
     */
    boolean sendDtmf(int dtmf, BluetoothDevice device) {
        if (device == null) {
            Log.w(TAG, "sendDtmf device is null");
            return false;
        }
        BluetoothInCallService bluetoothInCallService = getBluetoothInCallServiceInstance();
        if (bluetoothInCallService != null) {
            return bluetoothInCallService.sendDtmf(dtmf);
        } else {
            Log.e(TAG, "Handsfree phone proxy null for sending DTMF");
        }
        return false;
    }

    /**
     * Instructs Telecom hold an incoming call
     *
     * @param chld index of the call to hold
     */
    @VisibleForTesting
    public boolean processChld(HeadsetService headsetService, int chld) {
        BluetoothInCallService bluetoothInCallService = getBluetoothInCallServiceInstance();
        if (bluetoothInCallService != null) {
            return bluetoothInCallService.processChld(headsetService, chld);
        } else {
            Log.e(TAG, "Handsfree phone proxy null for sending DTMF");
        }
        return false;
    }

    /** Check for HD codec for voice call */
    @VisibleForTesting
    public boolean isHighDefCallInProgress() {
        BluetoothInCallService bluetoothInCallService = getBluetoothInCallServiceInstance();
        if (bluetoothInCallService != null) {
            return bluetoothInCallService.isHighDefCallInProgress();
        } else {
            Log.e(TAG, "Handsfree phone proxy null");
        }
        return false;
    }

    /**
     * Get the the alphabetic name of current registered operator.
     *
     * @return null on error, empty string if not available
     */
    @VisibleForTesting
    public String getNetworkOperator() {
        BluetoothInCallService bluetoothInCallService = getBluetoothInCallServiceInstance();
        if (bluetoothInCallService == null) {
            Log.e(TAG, "getNetworkOperator() failed: mBluetoothInCallService is null");
            return null;
        }
        // Should never return null
        return bluetoothInCallService.getNetworkOperator();
    }

    /**
     * Get the phone number of this device without incall service
     *
     * @return empty if unavailable
     */
    private String getNumberWithoutInCallService() {
        PhoneAccount account = null;
        String address = "";

        // Get the label for the default Phone Account.
        List<PhoneAccountHandle> handles =
                mTelecomManager.getPhoneAccountsSupportingScheme(PhoneAccount.SCHEME_TEL);
        while (handles.iterator().hasNext()) {
            account = mTelecomManager.getPhoneAccount(handles.iterator().next());
            break;
        }

        if (account != null) {
            Uri addressUri = account.getAddress();

            if (addressUri != null) {
                address = addressUri.getSchemeSpecificPart();
            }
        }

        if (address.isEmpty()) {
            address = mTelephonyManager.getLine1Number();
            if (address == null) address = "";
        }

        Log.i(TAG, "get phone number -> '" + address + "'");

        return address;
    }

    /**
     * Get the phone number of this device
     *
     * @return null if unavailable
     */
    @VisibleForTesting
    public String getSubscriberNumber() {
        BluetoothInCallService bluetoothInCallService = getBluetoothInCallServiceInstance();
        if (bluetoothInCallService == null) {
            Log.e(TAG, "getSubscriberNumber() failed: mBluetoothInCallService is null");
            Log.i(TAG, "Try to get phone number without mBluetoothInCallService.");
            return getNumberWithoutInCallService();
        }
        return bluetoothInCallService.getSubscriberNumber();
    }

    /**
     * Ask the Telecom service to list current list of calls through CLCC response {@link
     * BluetoothHeadset#clccResponse(int, int, int, int, boolean, String, int)}
     */
    @VisibleForTesting
    public boolean listCurrentCalls(HeadsetService headsetService) {
        BluetoothInCallService bluetoothInCallService = getBluetoothInCallServiceInstance();
        if (bluetoothInCallService == null) {
            Log.e(TAG, "listCurrentCalls() failed: mBluetoothInCallService is null");
            return false;
        }
        return bluetoothInCallService.listCurrentCalls(headsetService);
    }

    /**
     * Request Telecom service to send an update of the current call state to the headset service
     * through {@link BluetoothHeadset#phoneStateChanged(int, int, int, String, int)}
     */
    @VisibleForTesting
    public void queryPhoneState(HeadsetService headsetService) {
        BluetoothInCallService bluetoothInCallService = getBluetoothInCallServiceInstance();
        if (bluetoothInCallService != null) {
            bluetoothInCallService.queryPhoneState(headsetService);
        } else {
            Log.e(TAG, "Handsfree phone proxy null for query phone state");
        }
    }

    /**
     * Check if we are currently in a phone call
     *
     * @return True iff we are in a phone call
     */
    boolean isInCall() {
        return ((mHeadsetPhoneState.getNumActiveCall() > 0)
                || (mHeadsetPhoneState.getNumHeldCall() > 0)
                || ((mHeadsetPhoneState.getCallState() != HeadsetHalConstants.CALL_STATE_IDLE)
                        && (mHeadsetPhoneState.getCallState()
                                != HeadsetHalConstants.CALL_STATE_INCOMING)));
    }

    /**
     * Check if there is currently an incoming call
     *
     * @return True iff there is an incoming call
     */
    @VisibleForTesting
    public boolean isRinging() {
        return mHeadsetPhoneState.getCallState() == HeadsetHalConstants.CALL_STATE_INCOMING;
    }

    /**
     * Check if call status is idle
     *
     * @return true if call state is neither ringing nor in call
     */
    @VisibleForTesting
    public boolean isCallIdle() {
        return !isInCall() && !isRinging();
    }

    /**
     * Activate voice recognition on Android system
     *
     * @return true if activation succeeds, caller should wait for {@link
     *     BluetoothHeadset#startVoiceRecognition(BluetoothDevice)} callback that will then trigger
     *     {@link HeadsetService#startVoiceRecognition(BluetoothDevice)}, false if failed to
     *     activate
     */
    @VisibleForTesting
    public boolean activateVoiceRecognition() {
        Intent intent = new Intent(Intent.ACTION_VOICE_COMMAND);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            mHeadsetService.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "activateVoiceRecognition, failed due to activity not found for " + intent);
            return false;
        }
        return true;
    }

    /**
     * Deactivate voice recognition on Android system
     *
     * @return true if activation succeeds, caller should wait for {@link
     *     BluetoothHeadset#stopVoiceRecognition(BluetoothDevice)} callback that will then trigger
     *     {@link HeadsetService#stopVoiceRecognition(BluetoothDevice)}, false if failed to activate
     */
    @VisibleForTesting
    public boolean deactivateVoiceRecognition() {
        // TODO: need a method to deactivate voice recognition on Android
        return true;
    }
}
