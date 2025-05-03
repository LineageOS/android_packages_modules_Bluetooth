/*
 * Copyright (c) 2017 The Android Open Source Project
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

// Defines an event that is sent via a callback from JNI -> Java.
//
// See examples in HeadsetClientNativeInterface.java
package com.android.bluetooth.hfpclient;

import android.bluetooth.BluetoothDevice;

import com.android.internal.annotations.VisibleForTesting;

class StackEvent {
    // Type of event that signifies a native event and consumed by state machine
    public static final int STACK_EVENT = 100;

    // Event types for STACK_EVENT message (coming from native)
    public static final int EVENT_TYPE_NONE = 0;
    public static final int EVENT_TYPE_CONNECTION_STATE_CHANGED = 1;
    public static final int EVENT_TYPE_AUDIO_STATE_CHANGED = 2;
    public static final int EVENT_TYPE_VR_STATE_CHANGED = 3;
    public static final int EVENT_TYPE_NETWORK_STATE = 4;
    public static final int EVENT_TYPE_ROAMING_STATE = 5;
    public static final int EVENT_TYPE_NETWORK_SIGNAL = 6;
    public static final int EVENT_TYPE_BATTERY_LEVEL = 7;
    public static final int EVENT_TYPE_OPERATOR_NAME = 8;
    public static final int EVENT_TYPE_CALL = 9;
    public static final int EVENT_TYPE_CALLSETUP = 10;
    public static final int EVENT_TYPE_CALLHELD = 11;
    public static final int EVENT_TYPE_RESP_AND_HOLD = 12;
    public static final int EVENT_TYPE_CLIP = 13;
    public static final int EVENT_TYPE_CALL_WAITING = 14;
    public static final int EVENT_TYPE_CURRENT_CALLS = 15;
    public static final int EVENT_TYPE_VOLUME_CHANGED = 16;
    public static final int EVENT_TYPE_CMD_RESULT = 17;
    public static final int EVENT_TYPE_SUBSCRIBER_INFO = 18;
    public static final int EVENT_TYPE_IN_BAND_RINGTONE = 19;
    public static final int EVENT_TYPE_RING_INDICATION = 21;
    public static final int EVENT_TYPE_UNKNOWN_EVENT = 22;

    public static final int CMD_RESULT_TYPE_OK = 0;
    public static final int CMD_RESULT_TYPE_CME_ERROR = 7;

    public int type = EVENT_TYPE_NONE;
    public int valueInt = 0;
    public int valueInt2 = 0;
    public int valueInt3 = 0;
    public int valueInt4 = 0;
    public String valueString = null;
    public BluetoothDevice device = null;

    StackEvent(int type) {
        this.type = type;
    }

    @Override
    public String toString() {
        // event dump
        StringBuilder result = new StringBuilder();
        result.append("StackEvent {type:").append(eventTypeToString(type));
        result.append(", value1:").append(valueInt);
        result.append(", value2:").append(valueInt2);
        result.append(", value3:").append(valueInt3);
        result.append(", value4:").append(valueInt4);
        result.append(", string: \"").append(valueString).append("\"");
        result.append(", device:").append(device).append("}");
        return result.toString();
    }

    // for debugging only
    @VisibleForTesting
    static String eventTypeToString(int type) {
        return switch (type) {
            case EVENT_TYPE_NONE -> "EVENT_TYPE_NONE";
            case EVENT_TYPE_CONNECTION_STATE_CHANGED -> "EVENT_TYPE_CONNECTION_STATE_CHANGED";
            case EVENT_TYPE_AUDIO_STATE_CHANGED -> "EVENT_TYPE_AUDIO_STATE_CHANGED";
            case EVENT_TYPE_VR_STATE_CHANGED -> "EVENT_TYPE_VR_STATE_CHANGED";
            case EVENT_TYPE_NETWORK_STATE -> "EVENT_TYPE_NETWORK_STATE";
            case EVENT_TYPE_ROAMING_STATE -> "EVENT_TYPE_ROAMING_STATE";
            case EVENT_TYPE_NETWORK_SIGNAL -> "EVENT_TYPE_NETWORK_SIGNAL";
            case EVENT_TYPE_BATTERY_LEVEL -> "EVENT_TYPE_BATTERY_LEVEL";
            case EVENT_TYPE_OPERATOR_NAME -> "EVENT_TYPE_OPERATOR_NAME";
            case EVENT_TYPE_CALL -> "EVENT_TYPE_CALL";
            case EVENT_TYPE_CALLSETUP -> "EVENT_TYPE_CALLSETUP";
            case EVENT_TYPE_CALLHELD -> "EVENT_TYPE_CALLHELD";
            case EVENT_TYPE_CLIP -> "EVENT_TYPE_CLIP";
            case EVENT_TYPE_CALL_WAITING -> "EVENT_TYPE_CALL_WAITING";
            case EVENT_TYPE_CURRENT_CALLS -> "EVENT_TYPE_CURRENT_CALLS";
            case EVENT_TYPE_VOLUME_CHANGED -> "EVENT_TYPE_VOLUME_CHANGED";
            case EVENT_TYPE_CMD_RESULT -> "EVENT_TYPE_CMD_RESULT";
            case EVENT_TYPE_SUBSCRIBER_INFO -> "EVENT_TYPE_SUBSCRIBER_INFO";
            case EVENT_TYPE_RESP_AND_HOLD -> "EVENT_TYPE_RESP_AND_HOLD";
            case EVENT_TYPE_RING_INDICATION -> "EVENT_TYPE_RING_INDICATION";
            case EVENT_TYPE_IN_BAND_RINGTONE -> "EVENT_TYPE_IN_BAND_RINGTONE";
            default -> "EVENT_TYPE_UNKNOWN:" + type;
        };
    }
}
