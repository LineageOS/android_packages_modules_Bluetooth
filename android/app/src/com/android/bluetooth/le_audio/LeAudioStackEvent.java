/*
 * Copyright 2020 HIMSA II K/S - www.himsa.com.
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

package com.android.bluetooth.le_audio;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothLeAudioCodecConfig;
import android.bluetooth.BluetoothLeBroadcastMetadata;

import java.util.List;

/**
 * Stack event sent via a callback from JNI to Java, or generated internally by the LeAudio State
 * Machine.
 */
public class LeAudioStackEvent {
    // Event types for STACK_EVENT message (coming from native in bt_le_audio.h)
    private static final int EVENT_TYPE_NONE = 0;
    public static final int EVENT_TYPE_CONNECTION_STATE_CHANGED = 1;
    public static final int EVENT_TYPE_GROUP_STATUS_CHANGED = 2;
    public static final int EVENT_TYPE_GROUP_NODE_STATUS_CHANGED = 3;
    public static final int EVENT_TYPE_AUDIO_CONF_CHANGED = 4;
    public static final int EVENT_TYPE_SINK_AUDIO_LOCATION_AVAILABLE = 5;
    public static final int EVENT_TYPE_AUDIO_LOCAL_CODEC_CONFIG_CAPA_CHANGED = 6;
    public static final int EVENT_TYPE_AUDIO_GROUP_CURRENT_CODEC_CONFIG_CHANGED = 7;
    public static final int EVENT_TYPE_AUDIO_GROUP_SELECTABLE_CODEC_CONFIG_CHANGED = 8;
    public static final int EVENT_TYPE_NATIVE_INITIALIZED = 9;
    public static final int EVENT_TYPE_HEALTH_BASED_DEV_RECOMMENDATION = 10;
    public static final int EVENT_TYPE_HEALTH_BASED_GROUP_RECOMMENDATION = 11;
    public static final int EVENT_TYPE_UNICAST_MONITOR_MODE_STATUS = 12;
    public static final int EVENT_TYPE_GROUP_STREAM_STATUS_CHANGED = 13;
    // -------- DO NOT PUT ANY NEW UNICAST EVENTS BELOW THIS LINE-------------
    public static final int EVENT_TYPE_UNICAST_MAX = 14;

    // Broadcast related events
    public static final int EVENT_TYPE_BROADCAST_CREATED = EVENT_TYPE_UNICAST_MAX + 1;
    public static final int EVENT_TYPE_BROADCAST_DESTROYED = EVENT_TYPE_UNICAST_MAX + 2;
    public static final int EVENT_TYPE_BROADCAST_STATE = EVENT_TYPE_UNICAST_MAX + 3;
    public static final int EVENT_TYPE_BROADCAST_METADATA_CHANGED = EVENT_TYPE_UNICAST_MAX + 4;
    public static final int EVENT_TYPE_BROADCAST_AUDIO_SESSION_CREATED = EVENT_TYPE_UNICAST_MAX + 5;

    // Do not modify without updating the HAL bt_le_audio.h files.
    // Match up with GroupStatus enum of bt_le_audio.h
    static final int CONNECTION_STATE_DISCONNECTED = 0;
    static final int CONNECTION_STATE_CONNECTING = 1;
    static final int CONNECTION_STATE_CONNECTED = 2;
    static final int CONNECTION_STATE_DISCONNECTING = 3;

    // Health based recommendation
    static final int HEALTH_RECOMMENDATION_ACTION_NONE = 0;
    static final int HEALTH_RECOMMENDATION_ACTION_DISABLE = 1;
    static final int HEALTH_RECOMMENDATION_ACTION_CONSIDER_DISABLING = 2;
    static final int HEALTH_RECOMMENDATION_ACTION_INACTIVATE_GROUP = 3;

    static final int GROUP_STATUS_INACTIVE = 0;
    static final int GROUP_STATUS_ACTIVE = 1;
    static final int GROUP_STATUS_TURNED_IDLE_DURING_CALL = 2;
    static final int GROUP_STATUS_AUTONOMOUS_INACTIVE = 3;

    static final int GROUP_NODE_ADDED = 1;
    static final int GROUP_NODE_REMOVED = 2;

    // Do not modify without updating the HAL bt_le_audio.h files.
    // Match up with BroadcastState enum of bt_le_audio.h
    public static final int BROADCAST_STATE_STOPPED = 0;
    public static final int BROADCAST_STATE_CONFIGURING = 1;
    public static final int BROADCAST_STATE_PAUSED = 2;
    public static final int BROADCAST_STATE_ENABLING = 3;
    public static final int BROADCAST_STATE_DISABLING = 4;
    public static final int BROADCAST_STATE_STOPPING = 5;
    public static final int BROADCAST_STATE_STREAMING = 6;

    // Do not modify without updating the HAL bt_le_audio.h files.
    // Match up with UnicastMonitorModeStatus enum of bt_le_audio.h
    public static final int STATUS_LOCAL_STREAM_REQUESTED = 0;
    public static final int STATUS_LOCAL_STREAM_STREAMING = 1;
    public static final int STATUS_LOCAL_STREAM_SUSPENDED = 2;
    public static final int STATUS_LOCAL_STREAM_REQUESTED_NO_CONTEXT_VALIDATE = 3;

    // Do not modify without updating le_audio_types.h
    // Match up with defines of le_audio_types.h
    static final int DIRECTION_SINK = 1;
    static final int DIRECTION_SOURCE = 2;

    static final int GROUP_STREAM_STATUS_IDLE = 0;
    static final int GROUP_STREAM_STATUS_STREAMING = 1;

    public int type = EVENT_TYPE_NONE;
    public BluetoothDevice device;
    public int valueInt1 = 0;
    public int valueInt2 = 0;
    public int valueInt3 = 0;
    public int valueInt4 = 0;
    public int valueInt5 = 0;
    public boolean valueBool1 = false;
    public BluetoothLeAudioCodecConfig valueCodec1;
    public BluetoothLeAudioCodecConfig valueCodec2;
    public List<BluetoothLeAudioCodecConfig> valueCodecList1;
    public List<BluetoothLeAudioCodecConfig> valueCodecList2;
    public BluetoothLeBroadcastMetadata broadcastMetadata;

    LeAudioStackEvent(int type) {
        this.type = type;
    }

    @Override
    public String toString() {
        // event dump
        StringBuilder result = new StringBuilder();
        result.append("LeAudioStackEvent {type:").append(eventTypeToString(type));
        if (type == EVENT_TYPE_NATIVE_INITIALIZED) {
            result.append("}");
            return result.toString();
        }

        result.append(", device:").append(device);
        if (type != EVENT_TYPE_AUDIO_LOCAL_CODEC_CONFIG_CAPA_CHANGED) {
            result.append(", value1:").append(eventTypeValue1ToString(type, valueInt1));
            result.append(", value2:").append(eventTypeValue2ToString(type, valueInt2));
            result.append(", value3:").append(eventTypeValue3ToString(type, valueInt3));
            result.append(", value4:").append(eventTypeValue4ToString(type, valueInt4));
            result.append(", value5:").append(eventTypeValue5ToString(type, valueInt5));
            result.append(", valueBool1:").append(eventTypeValueBool1ToString(type, valueBool1));
        } else {
            result.append(", valueCodecList1:")
                    .append(eventTypeValueCodecList1ToString(type, valueCodecList1));
            result.append(", valueCodecList2:")
                    .append(eventTypeValueCodecList2ToString(type, valueCodecList2));
        }

        if (type == EVENT_TYPE_AUDIO_GROUP_CURRENT_CODEC_CONFIG_CHANGED) {
            result.append(", valueCodec1:").append(eventTypeValueCodec1ToString(type, valueCodec1));
            result.append(", valueCodec2:").append(eventTypeValueCodec2ToString(type, valueCodec2));
        }

        if (type == EVENT_TYPE_AUDIO_GROUP_SELECTABLE_CODEC_CONFIG_CHANGED) {
            result.append(", valueCodecList1:")
                    .append(eventTypeValueCodecList1ToString(type, valueCodecList1));
            result.append(", valueCodecList2:")
                    .append(eventTypeValueCodecList2ToString(type, valueCodecList2));
        }

        if (type == EVENT_TYPE_BROADCAST_METADATA_CHANGED) {
            result.append(", broadcastMetadata:")
                    .append(eventTypeValueBroadcastMetadataToString(broadcastMetadata));
        }
        result.append("}");
        return result.toString();
    }

    private static String eventTypeToString(int type) {
        return switch (type) {
            case EVENT_TYPE_NONE -> "EVENT_TYPE_NONE";
            case EVENT_TYPE_CONNECTION_STATE_CHANGED -> "EVENT_TYPE_CONNECTION_STATE_CHANGED";
            case EVENT_TYPE_GROUP_STATUS_CHANGED -> "EVENT_TYPE_GROUP_STATUS_CHANGED";
            case EVENT_TYPE_GROUP_NODE_STATUS_CHANGED -> "EVENT_TYPE_GROUP_NODE_STATUS_CHANGED";
            case EVENT_TYPE_AUDIO_CONF_CHANGED -> "EVENT_TYPE_AUDIO_CONF_CHANGED";
            case EVENT_TYPE_SINK_AUDIO_LOCATION_AVAILABLE ->
                    "EVENT_TYPE_SINK_AUDIO_LOCATION_AVAILABLE";
            case EVENT_TYPE_BROADCAST_CREATED -> "EVENT_TYPE_BROADCAST_CREATED";
            case EVENT_TYPE_BROADCAST_DESTROYED -> "EVENT_TYPE_BROADCAST_DESTROYED";
            case EVENT_TYPE_BROADCAST_STATE -> "EVENT_TYPE_BROADCAST_STATE";
            case EVENT_TYPE_BROADCAST_METADATA_CHANGED -> "EVENT_TYPE_BROADCAST_METADATA_CHANGED";
            case EVENT_TYPE_BROADCAST_AUDIO_SESSION_CREATED ->
                    "EVENT_TYPE_BROADCAST_AUDIO_SESSION_CREATED";
            case EVENT_TYPE_AUDIO_LOCAL_CODEC_CONFIG_CAPA_CHANGED ->
                    "EVENT_TYPE_AUDIO_LOCAL_CODEC_CONFIG_CAPA_CHANGED";
            case EVENT_TYPE_AUDIO_GROUP_CURRENT_CODEC_CONFIG_CHANGED ->
                    "EVENT_TYPE_AUDIO_GROUP_CURRENT_CODEC_CONFIG_CHANGED";
            case EVENT_TYPE_AUDIO_GROUP_SELECTABLE_CODEC_CONFIG_CHANGED ->
                    "EVENT_TYPE_AUDIO_GROUP_SELECTABLE_CODEC_CONFIG_CHANGED";
            case EVENT_TYPE_NATIVE_INITIALIZED -> "EVENT_TYPE_NATIVE_INITIALIZED";
            case EVENT_TYPE_HEALTH_BASED_DEV_RECOMMENDATION ->
                    "EVENT_TYPE_HEALTH_BASED_DEV_RECOMMENDATION";
            case EVENT_TYPE_HEALTH_BASED_GROUP_RECOMMENDATION ->
                    "EVENT_TYPE_HEALTH_BASED_GROUP_RECOMMENDATION";
            case EVENT_TYPE_UNICAST_MONITOR_MODE_STATUS -> "EVENT_TYPE_UNICAST_MONITOR_MODE_STATUS";
            case EVENT_TYPE_GROUP_STREAM_STATUS_CHANGED -> "EVENT_TYPE_GROUP_STREAM_STATUS_CHANGED";
            default -> "EVENT_TYPE_UNKNOWN:" + type;
        };
    }

    private static String eventTypeValue1ToString(int type, int value) {
        return switch (type) {
            case EVENT_TYPE_CONNECTION_STATE_CHANGED ->
                    switch (value) {
                        case CONNECTION_STATE_DISCONNECTED -> "CONNECTION_STATE_DISCONNECTED";
                        case CONNECTION_STATE_CONNECTING -> "CONNECTION_STATE_CONNECTING";
                        case CONNECTION_STATE_CONNECTED -> "CONNECTION_STATE_CONNECTED";
                        case CONNECTION_STATE_DISCONNECTING -> "CONNECTION_STATE_DISCONNECTING";
                        default -> "UNKNOWN";
                    };
            case EVENT_TYPE_GROUP_NODE_STATUS_CHANGED,
                    EVENT_TYPE_AUDIO_GROUP_CURRENT_CODEC_CONFIG_CHANGED,
                    EVENT_TYPE_AUDIO_GROUP_SELECTABLE_CODEC_CONFIG_CHANGED,
                    EVENT_TYPE_GROUP_STREAM_STATUS_CHANGED,
                    EVENT_TYPE_GROUP_STATUS_CHANGED ->
                    "{group_id:" + Integer.toString(value) + "}";
            // FIXME: It should have proper direction names here
            case EVENT_TYPE_AUDIO_CONF_CHANGED -> "{direction:" + value + "}";
            case EVENT_TYPE_SINK_AUDIO_LOCATION_AVAILABLE -> "{sink_audio_location:" + value + "}";
            case EVENT_TYPE_BROADCAST_CREATED,
                    EVENT_TYPE_BROADCAST_DESTROYED,
                    EVENT_TYPE_BROADCAST_METADATA_CHANGED,
                    EVENT_TYPE_BROADCAST_STATE ->
                    "{broadcastId:" + value + "}";
            case EVENT_TYPE_HEALTH_BASED_GROUP_RECOMMENDATION -> "{group_id: " + value + "}";
            case EVENT_TYPE_HEALTH_BASED_DEV_RECOMMENDATION ->
                    switch (value) {
                        case HEALTH_RECOMMENDATION_ACTION_DISABLE -> "ACTION_DISABLE";
                        case HEALTH_RECOMMENDATION_ACTION_CONSIDER_DISABLING ->
                                "ACTION_CONSIDER_DISABLING";
                        case HEALTH_RECOMMENDATION_ACTION_INACTIVATE_GROUP ->
                                "ACTION_INACTIVATE_GROUP";
                        default -> "UNKNOWN";
                    };
            case EVENT_TYPE_UNICAST_MONITOR_MODE_STATUS ->
                    switch (value) {
                        case DIRECTION_SINK -> "DIRECTION_SINK";
                        case DIRECTION_SOURCE -> "DIRECTION_SOURCE";
                        default -> "UNKNOWN";
                    };
            default -> Integer.toString(value);
        };
    }

    private static String eventTypeValue2ToString(int type, int value) {
        return switch (type) {
            case EVENT_TYPE_GROUP_STATUS_CHANGED ->
                    switch (value) {
                        case GROUP_STATUS_ACTIVE -> "GROUP_STATUS_ACTIVE";
                        case GROUP_STATUS_INACTIVE -> "GROUP_STATUS_INACTIVE";
                        case GROUP_STATUS_TURNED_IDLE_DURING_CALL ->
                                "GROUP_STATUS_TURNED_IDLE_DURING_CALL";
                        case GROUP_STATUS_AUTONOMOUS_INACTIVE -> "GROUP_STATUS_AUTONOMOUS_INACTIVE";
                        default -> Integer.toString(value);
                    };
            case EVENT_TYPE_GROUP_NODE_STATUS_CHANGED ->
                    switch (value) {
                        case GROUP_NODE_ADDED -> "GROUP_NODE_ADDED";
                        case GROUP_NODE_REMOVED -> "GROUP_NODE_REMOVED";
                        default -> "UNKNOWN";
                    };
            case EVENT_TYPE_AUDIO_CONF_CHANGED -> "{group_id:" + Integer.toString(value) + "}";
            case EVENT_TYPE_BROADCAST_STATE -> "{state:" + broadcastStateToString(value) + "}";
            case EVENT_TYPE_HEALTH_BASED_GROUP_RECOMMENDATION ->
                    switch (value) {
                        case HEALTH_RECOMMENDATION_ACTION_DISABLE -> "ACTION_DISABLE";
                        case HEALTH_RECOMMENDATION_ACTION_CONSIDER_DISABLING ->
                                "ACTION_CONSIDER_DISABLING";
                        case HEALTH_RECOMMENDATION_ACTION_INACTIVATE_GROUP ->
                                "ACTION_INACTIVATE_GROUP";
                        default -> "UNKNOWN";
                    };
            case EVENT_TYPE_UNICAST_MONITOR_MODE_STATUS ->
                    switch (value) {
                        case STATUS_LOCAL_STREAM_REQUESTED -> "STATUS_LOCAL_STREAM_REQUESTED";
                        case STATUS_LOCAL_STREAM_STREAMING -> "STATUS_LOCAL_STREAM_STREAMING";
                        case STATUS_LOCAL_STREAM_SUSPENDED -> "STATUS_LOCAL_STREAM_SUSPENDED";
                        case STATUS_LOCAL_STREAM_REQUESTED_NO_CONTEXT_VALIDATE ->
                                "STATUS_LOCAL_STREAM_REQUESTED_NO_CONTEXT_VALIDATE";
                        default -> "UNKNOWN";
                    };
            case EVENT_TYPE_GROUP_STREAM_STATUS_CHANGED ->
                    switch (value) {
                        case GROUP_STREAM_STATUS_IDLE -> "GROUP_STREAM_STATUS_IDLE";
                        case GROUP_STREAM_STATUS_STREAMING -> "GROUP_STREAM_STATUS_STREAMING";
                        default -> "UNKNOWN";
                    };
            default -> Integer.toString(value);
        };
    }

    private static String eventTypeValue3ToString(int type, int value) {
        return switch (type) {
            // FIXME: It should have proper location names here
            case EVENT_TYPE_AUDIO_CONF_CHANGED -> "{snk_audio_loc:" + value + "}";
            default -> Integer.toString(value);
        };
    }

    private static String eventTypeValue4ToString(int type, int value) {
        return switch (type) {
            // FIXME: It should have proper location names here
            case EVENT_TYPE_AUDIO_CONF_CHANGED -> "{src_audio_loc:" + value + "}";
            default -> Integer.toString(value);
        };
    }

    private static String eventTypeValue5ToString(int type, int value) {
        return switch (type) {
            case EVENT_TYPE_AUDIO_CONF_CHANGED ->
                    "{available_contexts:" + Integer.toBinaryString(value) + "}";
            default -> Integer.toString(value);
        };
    }

    private static String eventTypeValueBool1ToString(int type, boolean value) {
        return switch (type) {
            case EVENT_TYPE_BROADCAST_AUDIO_SESSION_CREATED, EVENT_TYPE_BROADCAST_CREATED ->
                    "{success:" + value + "}";
            default -> "<unused>";
        };
    }

    private static String eventTypeValueCodec1ToString(
            int type, BluetoothLeAudioCodecConfig value) {
        return switch (type) {
            case EVENT_TYPE_AUDIO_GROUP_CURRENT_CODEC_CONFIG_CHANGED ->
                    "{input codec =" + value + "}";
            default -> "<unused>";
        };
    }

    private static String eventTypeValueCodec2ToString(
            int type, BluetoothLeAudioCodecConfig value) {
        return switch (type) {
            case EVENT_TYPE_AUDIO_GROUP_CURRENT_CODEC_CONFIG_CHANGED ->
                    "{output codec =" + value + "}";
            default -> "<unused>";
        };
    }

    private static String eventTypeValueCodecList1ToString(
            int type, List<BluetoothLeAudioCodecConfig> value) {
        String valueStr = "";
        return switch (type) {
            case EVENT_TYPE_AUDIO_LOCAL_CODEC_CONFIG_CAPA_CHANGED -> {
                for (BluetoothLeAudioCodecConfig n : value) {
                    valueStr = valueStr.concat(n.toString() + "\n");
                }
                yield "{input local capa codec =\n" + valueStr + "}";
            }
            case EVENT_TYPE_AUDIO_GROUP_SELECTABLE_CODEC_CONFIG_CHANGED -> {
                for (BluetoothLeAudioCodecConfig n : value) {
                    valueStr = valueStr.concat(n.toString() + "\n");
                }
                yield "{input selectable codec =" + valueStr + "}";
            }
            default -> "<unused>";
        };
    }

    private static String eventTypeValueCodecList2ToString(
            int type, List<BluetoothLeAudioCodecConfig> value) {
        String valueStr = "";
        return switch (type) {
            case EVENT_TYPE_AUDIO_LOCAL_CODEC_CONFIG_CAPA_CHANGED -> {
                for (BluetoothLeAudioCodecConfig n : value) {
                    valueStr = valueStr.concat(n.toString() + "\n");
                }
                yield "{output local capa codec =\n" + valueStr + "}";
            }
            case EVENT_TYPE_AUDIO_GROUP_SELECTABLE_CODEC_CONFIG_CHANGED -> {
                for (BluetoothLeAudioCodecConfig n : value) {
                    valueStr = valueStr.concat(n.toString() + "\n");
                }
                yield "{output selectable codec =" + valueStr + "}";
            }
            default -> "<unused>";
        };
    }

    private static String broadcastStateToString(int state) {
        return switch (state) {
            case BROADCAST_STATE_STOPPED -> "BROADCAST_STATE_STOPPED";
            case BROADCAST_STATE_CONFIGURING -> "BROADCAST_STATE_CONFIGURING";
            case BROADCAST_STATE_PAUSED -> "BROADCAST_STATE_PAUSED";
            case BROADCAST_STATE_ENABLING -> "BROADCAST_STATE_ENABLING";
            case BROADCAST_STATE_DISABLING -> "BROADCAST_STATE_DISABLING";
            case BROADCAST_STATE_STOPPING -> "BROADCAST_STATE_STOPPING";
            case BROADCAST_STATE_STREAMING -> "BROADCAST_STATE_STREAMING";
            default -> "UNKNOWN";
        };
    }

    private static String eventTypeValueBroadcastMetadataToString(
            BluetoothLeBroadcastMetadata meta) {
        return meta.toString();
    }

    protected static String encodeHexString(byte[] pduData) {
        StringBuilder out = new StringBuilder(pduData.length * 2);
        for (int i = 0; i < pduData.length; i++) {
            // MS-nibble first
            out.append(Integer.toString((pduData[i] >> 4) & 0x0f, 16));
            out.append(Integer.toString(pduData[i] & 0x0f, 16));
        }
        return out.toString();
    }
}
