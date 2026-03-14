/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static java.util.Objects.requireNonNull;

import android.bluetooth.BluetoothDevice;

import com.android.bluetooth.Util;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.profile.NativeInterface;

/**
 * Defines native calls that are used by state machine/service to either send or receive messages
 * to/from the native stack. This file is registered for the native methods in corresponding CPP
 * file.
 */
public class HeadsetNativeInterface extends NativeInterface<HeadsetNativeCallback> {
    private final AdapterService mAdapterService;

    HeadsetNativeInterface(HeadsetNativeCallback nativeCallback, AdapterService adapterService) {
        super(requireNonNull(nativeCallback));
        mAdapterService = adapterService;
    }

    private byte[] getByteAddress(BluetoothDevice device) {
        if (device == null) {
            // Set bt_stack's active device to default if java layer set active device to null
            return Util.getBytesFromAddress("00:00:00:00:00:00");
        }
        return mAdapterService.getByteBrEdrAddress(device);
    }

    /**
     * Initialize native stack
     *
     * @param maxHfClients maximum number of headset clients that can be connected simultaneously
     * @param inbandRingingEnabled whether in-band ringing is enabled on this AG
     */
    void init(int maxHfClients, boolean inbandRingingEnabled) {
        initializeNative(maxHfClients, inbandRingingEnabled);
    }

    @Override
    public void cleanup() {
        cleanupNative();
    }

    /**
     * ok/error response
     *
     * @param device target device
     * @param responseCode 0 - ERROR, 1 - OK
     * @param errorCode error code in case of ERROR
     * @return True on success, False on failure
     */
    boolean atResponseCode(BluetoothDevice device, int responseCode, int errorCode) {
        return atResponseCodeNative(responseCode, errorCode, getByteAddress(device));
    }

    /**
     * Pre-formatted AT response, typically in response to unknown AT cmd
     *
     * @param device target device
     * @param responseString formatted AT response string
     * @return True on success, False on failure
     */
    boolean atResponseString(BluetoothDevice device, String responseString) {
        return atResponseStringNative(responseString, getByteAddress(device));
    }

    /**
     * Connect to headset
     *
     * @param device target headset
     * @return True on success, False on failure
     */
    boolean connectHfp(BluetoothDevice device) {
        return connectHfpNative(getByteAddress(device));
    }

    /**
     * Disconnect from headset
     *
     * @param device target headset
     * @return True on success, False on failure
     */
    boolean disconnectHfp(BluetoothDevice device) {
        return disconnectHfpNative(getByteAddress(device));
    }

    /**
     * Connect HFP audio (SCO) to headset
     *
     * @param device target headset
     * @return True on success, False on failure
     */
    boolean connectAudio(BluetoothDevice device) {
        return connectAudioNative(getByteAddress(device));
    }

    /**
     * Disconnect HFP audio (SCO) from to headset
     *
     * @param device target headset
     * @return True on success, False on failure
     */
    boolean disconnectAudio(BluetoothDevice device) {
        return disconnectAudioNative(getByteAddress(device));
    }

    /**
     * Checks whether the device support echo cancellation and/or noise reduction via the AT+BRSF
     * bitmask
     *
     * @param device target headset
     * @return true if the device support echo cancellation or noise reduction, false otherwise
     */
    boolean isNoiseReductionSupported(BluetoothDevice device) {
        return isNoiseReductionSupportedNative(getByteAddress(device));
    }

    /**
     * Checks whether the device supports voice recognition via the AT+BRSF bitmask
     *
     * @param device target headset
     * @return true if the device supports voice recognition, false otherwise
     */
    boolean isVoiceRecognitionSupported(BluetoothDevice device) {
        return isVoiceRecognitionSupportedNative(getByteAddress(device));
    }

    /**
     * Start voice recognition
     *
     * @param device target headset
     * @param sendResult whether a BVRA response should be sent
     * @return True on success, False on failure
     */
    boolean startVoiceRecognition(BluetoothDevice device, boolean sendResult) {
        return startVoiceRecognitionNative(getByteAddress(device), sendResult);
    }

    /**
     * Stop voice recognition
     *
     * @param device target headset
     * @return True on success, False on failure
     */
    boolean stopVoiceRecognition(BluetoothDevice device) {
        return stopVoiceRecognitionNative(getByteAddress(device));
    }

    /**
     * Set HFP audio (SCO) volume
     *
     * @param device target headset
     * @param volumeType type of volume
     * @param volume value value
     * @return True on success, False on failure
     */
    boolean setVolume(BluetoothDevice device, int volumeType, int volume) {
        return setVolumeNative(volumeType, volume, getByteAddress(device));
    }

    /**
     * Response for CIND command
     *
     * @param device target device
     * @param service service availability, 0 - no service, 1 - presence of service
     * @param numActive number of active calls
     * @param numHeld number of held calls
     * @param callState overall call state [0-6]
     * @param signal signal quality [0-5]
     * @param roam roaming indicator, 0 - not roaming, 1 - roaming
     * @param batteryCharge battery charge level [0-5]
     * @return True on success, False on failure
     */
    boolean cindResponse(
            BluetoothDevice device,
            int service,
            int numActive,
            int numHeld,
            int callState,
            int signal,
            int roam,
            int batteryCharge) {
        return cindResponseNative(
                service,
                numActive,
                numHeld,
                callState,
                signal,
                roam,
                batteryCharge,
                getByteAddress(device));
    }

    /**
     * Combined device status change notification
     *
     * @param device target device
     * @param deviceState device status object
     * @return True on success, False on failure
     */
    boolean notifyDeviceStatus(BluetoothDevice device, HeadsetDeviceState deviceState) {
        return notifyDeviceStatusNative(
                deviceState.mService,
                deviceState.mRoam,
                deviceState.mSignal,
                deviceState.mBatteryCharge,
                getByteAddress(device));
    }

    /**
     * Response for CLCC command. Can be iteratively called for each call index. Call index of 0
     * will be treated as NULL termination (Completes response)
     *
     * @param device target device
     * @param index index of the call given by the sequence of setting up or receiving the calls as
     *     seen by the served subscriber. Calls hold their number until they are released. New calls
     *     take the lowest available number.
     * @param dir direction of the call, 0 (outgoing), 1 (incoming)
     * @param status 0 = Active, 1 = Held, 2 = Dialing (outgoing calls only), 3 = Alerting (outgoing
     *     calls only), 4 = Incoming (incoming calls only), 5 = Waiting (incoming calls only), 6 =
     *     Call held by Response and Hold
     * @param mode 0 (Voice), 1 (Data), 2 (FAX)
     * @param mpty 0 - this call is NOT a member of a multi-party (conference) call, 1 - this call
     *     IS a member of a multi-party (conference) call
     * @param number optional
     * @param type optional
     * @return True on success, False on failure
     */
    boolean clccResponse(
            BluetoothDevice device,
            int index,
            int dir,
            int status,
            int mode,
            boolean mpty,
            String number,
            int type) {
        return clccResponseNative(
                index, dir, status, mode, mpty, number, type, getByteAddress(device));
    }

    /**
     * Response for COPS command
     *
     * @param device target device
     * @param operatorName operator name
     * @return True on success, False on failure
     */
    boolean copsResponse(BluetoothDevice device, String operatorName) {
        return copsResponseNative(operatorName, getByteAddress(device));
    }

    /**
     * Notify of a call state change Each update notifies 1. Number of active/held/ringing calls 2.
     * call_state: This denotes the state change that triggered this msg This will take one of the
     * values from BtHfCallState 3. number & type: valid only for incoming & waiting call
     *
     * @param device target device for this update
     * @param callState callState structure
     * @return True on success, False on failure
     */
    boolean phoneStateChange(BluetoothDevice device, HeadsetCallState callState) {
        return phoneStateChangeNative(
                callState.mNumActive,
                callState.mNumHeld,
                callState.mCallState,
                callState.mNumber,
                callState.mType,
                callState.mName,
                getByteAddress(device));
    }

    /**
     * Set whether we will initiate SCO or not
     *
     * @param value True to enable, False to disable
     * @return True on success, False on failure
     */
    boolean setScoAllowed(boolean value) {
        return setScoAllowedNative(value);
    }

    /**
     * Enable or disable in-band ringing for the current service level connection through sending
     * +BSIR AT command
     *
     * @param value True to enable, False to disable
     * @return True on success, False on failure
     */
    boolean sendBsir(BluetoothDevice device, boolean value) {
        return sendBsirNative(value, getByteAddress(device));
    }

    /**
     * Set the current active headset device for SCO audio
     *
     * @param device current active SCO device
     * @return true on success
     */
    boolean setActiveDevice(BluetoothDevice device) {
        return setActiveDeviceNative(getByteAddress(device));
    }

    /**
     * Enable Super Wide Band
     *
     * @param swbCodec SWB Codec
     * @param enable True to enable, False to disable
     * @param device current active SCO device
     * @return True on success, False on failure
     */
    boolean enableSwb(int swbCodec, boolean enable, BluetoothDevice device) {
        return enableSwbNative(swbCodec, enable, getByteAddress(device));
    }

    /**
     * Set whether we will use the new SCO Management path based on the java flag value/sys prop
     *
     * @param value True to enable, False to disable
     * @return True on success, False on failure
     */
    boolean setIsScoManagedByAudio(boolean value) {
        return setIsScoManagedByAudioNative(value);
    }

    /* Native methods */
    private native boolean atResponseCodeNative(int responseCode, int errorCode, byte[] address);

    private native boolean atResponseStringNative(String responseString, byte[] address);

    private native void initializeNative(int maxHfClients, boolean inbandRingingEnabled);

    private native void cleanupNative();

    private native boolean connectHfpNative(byte[] address);

    private native boolean disconnectHfpNative(byte[] address);

    private native boolean connectAudioNative(byte[] address);

    private native boolean disconnectAudioNative(byte[] address);

    private native boolean isNoiseReductionSupportedNative(byte[] address);

    private native boolean isVoiceRecognitionSupportedNative(byte[] address);

    private native boolean startVoiceRecognitionNative(byte[] address, boolean sendResult);

    private native boolean stopVoiceRecognitionNative(byte[] address);

    private native boolean setVolumeNative(int volumeType, int volume, byte[] address);

    private native boolean cindResponseNative(
            int service,
            int numActive,
            int numHeld,
            int callState,
            int signal,
            int roam,
            int batteryCharge,
            byte[] address);

    private native boolean notifyDeviceStatusNative(
            int networkState, int serviceType, int signal, int batteryCharge, byte[] address);

    private native boolean clccResponseNative(
            int index,
            int dir,
            int status,
            int mode,
            boolean mpty,
            String number,
            int type,
            byte[] address);

    private native boolean copsResponseNative(String operatorName, byte[] address);

    private native boolean phoneStateChangeNative(
            int numActive,
            int numHeld,
            int callState,
            String number,
            int type,
            String name,
            byte[] address);

    private native boolean setScoAllowedNative(boolean value);

    private native boolean sendBsirNative(boolean value, byte[] address);

    private native boolean setActiveDeviceNative(byte[] address);

    private native boolean enableSwbNative(int swbCodec, boolean enable, byte[] address);

    private native boolean setIsScoManagedByAudioNative(boolean value);
}
