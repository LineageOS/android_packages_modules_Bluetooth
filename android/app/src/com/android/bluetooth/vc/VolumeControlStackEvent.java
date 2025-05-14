/*
 * Copyright 2021 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
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

package com.android.bluetooth.vc;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;

public class VolumeControlStackEvent {
    // Event types for STACK_EVENT message (coming from native)
    private static final int EVENT_TYPE_NONE = 0;
    public static final int EVENT_TYPE_CONNECTION_STATE_CHANGED = 1;
    public static final int EVENT_TYPE_VOLUME_STATE_CHANGED = 2;
    public static final int EVENT_TYPE_DEVICE_AVAILABLE = 3;
    public static final int EVENT_TYPE_EXT_AUDIO_OUT_VOL_OFFSET_CHANGED = 4;
    public static final int EVENT_TYPE_EXT_AUDIO_OUT_LOCATION_CHANGED = 5;
    public static final int EVENT_TYPE_EXT_AUDIO_OUT_DESCRIPTION_CHANGED = 6;

    public int type;
    public BluetoothDevice device;
    public int valueInt1;
    public int valueInt2;
    public int valueInt3;
    public boolean valueBool1;
    public boolean valueBool2;
    public String valueString1;

    /* Might need more for other callbacks*/

    VolumeControlStackEvent(int type) {
        this.type = type;
    }

    @Override
    public String toString() {
        // event dump
        StringBuilder result = new StringBuilder();
        result.append("VolumeControlStackEvent {type:").append(eventTypeToString(type));
        result.append(", device:").append(device);
        result.append(", valueInt1:").append(eventTypeValue1ToString(type, valueInt1));
        result.append(", valueInt2:").append(eventTypeValue2ToString(type, valueInt2));
        result.append(", valueInt3:").append(eventTypeValue3ToString(type, valueInt3));
        result.append(", valueBool1:").append(eventTypeValueBool1ToString(type, valueBool1));
        result.append(", valueBool2:").append(eventTypeValueBool2ToString(type, valueBool2));
        result.append(", valueString1:").append(eventTypeString1ToString(type, valueString1));
        result.append("}");
        return result.toString();
    }

    private static String eventTypeToString(int type) {
        return switch (type) {
            case EVENT_TYPE_NONE -> "EVENT_TYPE_NONE";
            case EVENT_TYPE_CONNECTION_STATE_CHANGED -> "EVENT_TYPE_CONNECTION_STATE_CHANGED";
            case EVENT_TYPE_VOLUME_STATE_CHANGED -> "EVENT_TYPE_VOLUME_STATE_CHANGED";
            case EVENT_TYPE_DEVICE_AVAILABLE -> "EVENT_TYPE_DEVICE_AVAILABLE";
            case EVENT_TYPE_EXT_AUDIO_OUT_VOL_OFFSET_CHANGED ->
                    "EVENT_TYPE_EXT_AUDIO_OUT_VOL_OFFSET_CHANGED";
            case EVENT_TYPE_EXT_AUDIO_OUT_LOCATION_CHANGED ->
                    "EVENT_TYPE_EXT_AUDIO_OUT_LOCATION_CHANGED";
            case EVENT_TYPE_EXT_AUDIO_OUT_DESCRIPTION_CHANGED ->
                    "EVENT_TYPE_EXT_AUDIO_OUT_DESCRIPTION_CHANGED";
            default -> "EVENT_TYPE_UNKNOWN:" + type;
        };
    }

    private static String eventTypeValue1ToString(int type, int value) {
        return switch (type) {
            case EVENT_TYPE_CONNECTION_STATE_CHANGED ->
                    BluetoothProfile.getConnectionStateName(value);
            case EVENT_TYPE_VOLUME_STATE_CHANGED -> "{group_id:" + value + "}";
            case EVENT_TYPE_DEVICE_AVAILABLE -> "{group_id:" + value + "}";
            case EVENT_TYPE_EXT_AUDIO_OUT_VOL_OFFSET_CHANGED,
                    EVENT_TYPE_EXT_AUDIO_OUT_LOCATION_CHANGED,
                    EVENT_TYPE_EXT_AUDIO_OUT_DESCRIPTION_CHANGED ->
                    "{ext output id:" + value + "}";
            default -> Integer.toString(value);
        };
    }

    private static String eventTypeValue2ToString(int type, int value) {
        return switch (type) {
            case EVENT_TYPE_VOLUME_STATE_CHANGED -> "{volume:" + value + "}";
            case EVENT_TYPE_DEVICE_AVAILABLE -> "{num_ext_outputs:" + value + "}";
            default -> Integer.toString(value);
        };
    }

    private static String eventTypeValue3ToString(int type, int value) {
        return switch (type) {
            case EVENT_TYPE_VOLUME_STATE_CHANGED -> "{flags:" + value + "}";
            case EVENT_TYPE_DEVICE_AVAILABLE -> "{num_ext_inputs:" + value + "}";
            default -> Integer.toString(value);
        };
    }

    private static String eventTypeValueBool1ToString(int type, boolean value) {
        return switch (type) {
            case EVENT_TYPE_VOLUME_STATE_CHANGED -> "{muted:" + value + "}";
            default -> Boolean.toString(value);
        };
    }

    private static String eventTypeValueBool2ToString(int type, boolean value) {
        return switch (type) {
            case EVENT_TYPE_VOLUME_STATE_CHANGED -> "{isAutonomous:" + value + "}";
            default -> Boolean.toString(value);
        };
    }

    private static String eventTypeString1ToString(int type, String value) {
        return switch (type) {
            case EVENT_TYPE_EXT_AUDIO_OUT_DESCRIPTION_CHANGED -> "{description:" + value + "}";
            default -> value;
        };
    }
}
