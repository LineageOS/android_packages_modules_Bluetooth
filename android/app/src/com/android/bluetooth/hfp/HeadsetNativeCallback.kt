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

package com.android.bluetooth.hfp

import com.android.bluetooth.btservice.AdapterService
import com.android.bluetooth.profile.NativeCallback

class HeadsetNativeCallback(adapterService: AdapterService, private val service: HeadsetService) :
    NativeCallback(adapterService) {

    fun onConnectionStateChanged(state: Int, address: ByteArray, reason: Int) {
        val event =
            HeadsetStackEvent(
                HeadsetStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED,
                state,
                getDevice(address),
            )
        event.reason = reason
        service.messageFromNative(event)
    }

    private fun onAudioStateChanged(state: Int, address: ByteArray, reason: Int) {
        val event =
            HeadsetStackEvent(
                HeadsetStackEvent.EVENT_TYPE_AUDIO_STATE_CHANGED,
                state,
                getDevice(address),
            )
        event.reason = reason
        service.messageFromNative(event)
    }

    private fun onVrStateChanged(state: Int, address: ByteArray) {
        val event =
            HeadsetStackEvent(
                HeadsetStackEvent.EVENT_TYPE_VR_STATE_CHANGED,
                state,
                getDevice(address),
            )
        service.messageFromNative(event)
    }

    private fun onAnswerCall(address: ByteArray) {
        val event = HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_ANSWER_CALL, getDevice(address))
        service.messageFromNative(event)
    }

    private fun onHangupCall(address: ByteArray) {
        val event = HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_HANGUP_CALL, getDevice(address))
        service.messageFromNative(event)
    }

    private fun onVolumeChanged(type: Int, volume: Int, address: ByteArray) {
        val event =
            HeadsetStackEvent(
                HeadsetStackEvent.EVENT_TYPE_VOLUME_CHANGED,
                type,
                volume,
                getDevice(address),
            )
        service.messageFromNative(event)
    }

    private fun onDialCall(number: String?, address: ByteArray) {
        val event =
            HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_DIAL_CALL, number, getDevice(address))
        service.messageFromNative(event)
    }

    private fun onSendDtmf(dtmf: Int, address: ByteArray) {
        val event =
            HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_SEND_DTMF, dtmf, getDevice(address))
        service.messageFromNative(event)
    }

    private fun onNoiseReductionEnable(enable: Boolean, address: ByteArray) {
        val event =
            HeadsetStackEvent(
                HeadsetStackEvent.EVENT_TYPE_NOISE_REDUCTION,
                if (enable) 1 else 0,
                getDevice(address),
            )
        service.messageFromNative(event)
    }

    private fun onWBS(codec: Int, address: ByteArray) {
        val event = HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_WBS, codec, getDevice(address))
        service.messageFromNative(event)
    }

    private fun onSWB(codec: Int, swb: Int, address: ByteArray) {
        val event =
            HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_SWB, codec, swb, getDevice(address))
        service.messageFromNative(event)
    }

    private fun onAtChld(chld: Int, address: ByteArray) {
        val event =
            HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_AT_CHLD, chld, getDevice(address))
        service.messageFromNative(event)
    }

    private fun onAtCnum(address: ByteArray) {
        val event =
            HeadsetStackEvent(
                HeadsetStackEvent.EVENT_TYPE_SUBSCRIBER_NUMBER_REQUEST,
                getDevice(address),
            )
        service.messageFromNative(event)
    }

    private fun onAtCind(address: ByteArray) {
        val event = HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_AT_CIND, getDevice(address))
        service.messageFromNative(event)
    }

    private fun onAtCops(address: ByteArray) {
        val event = HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_AT_COPS, getDevice(address))
        service.messageFromNative(event)
    }

    private fun onAtClcc(address: ByteArray) {
        val event = HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_AT_CLCC, getDevice(address))
        service.messageFromNative(event)
    }

    private fun onUnknownAt(atString: String?, address: ByteArray) {
        val event =
            HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_UNKNOWN_AT, atString, getDevice(address))
        service.messageFromNative(event)
    }

    private fun onKeyPressed(address: ByteArray) {
        val event = HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_KEY_PRESSED, getDevice(address))
        service.messageFromNative(event)
    }

    private fun onATBind(atString: String?, address: ByteArray) {
        val event =
            HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_BIND, atString, getDevice(address))
        service.messageFromNative(event)
    }

    private fun onATBiev(indId: Int, indValue: Int, address: ByteArray) {
        val event =
            HeadsetStackEvent(
                HeadsetStackEvent.EVENT_TYPE_BIEV,
                indId,
                indValue,
                getDevice(address),
            )
        service.messageFromNative(event)
    }

    private fun onAtBia(
        srvc: Boolean,
        roam: Boolean,
        signal: Boolean,
        battery: Boolean,
        address: ByteArray,
    ) {
        val agIndicatorEnableState = HeadsetAgIndicatorEnableState(srvc, roam, signal, battery)
        val event =
            HeadsetStackEvent(
                HeadsetStackEvent.EVENT_TYPE_BIA,
                agIndicatorEnableState,
                getDevice(address),
            )
        service.messageFromNative(event)
    }

    private fun onAtBcc(address: ByteArray) {
        val event = HeadsetStackEvent(HeadsetStackEvent.EVENT_TYPE_BCC, getDevice(address))
        service.messageFromNative(event)
    }
}
