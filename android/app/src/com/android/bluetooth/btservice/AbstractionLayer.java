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

package com.android.bluetooth.btservice;

public final class AbstractionLayer {
    // Do not modify without updating the HAL files
    // Do not modify without migrating data in the config

    // TODO: Some of the constants are repeated from BluetoothAdapter.java
    // Get rid of them and maintain just one
    static final int BT_STATE_OFF = 0x00;
    static final int BT_STATE_ON = 0x01;

    static final int BT_SCAN_MODE_NONE = 0x00;
    static final int BT_SCAN_MODE_CONNECTABLE = 0x01;
    static final int BT_SCAN_MODE_CONNECTABLE_DISCOVERABLE = 0x02;

    static final int BT_PROPERTY_BDNAME = 0x01;
    static final int BT_PROPERTY_BDADDR = 0x02;
    static final int BT_PROPERTY_UUIDS = 0x03;
    static final int BT_PROPERTY_CLASS_OF_DEVICE = 0x04;
    static final int BT_PROPERTY_TYPE_OF_DEVICE = 0x05;
    static final int BT_PROPERTY_SERVICE_RECORD = 0x06;
    static final int BT_PROPERTY_ADAPTER_BONDED_DEVICES = 0x08;
    static final int BT_PROPERTY_ADAPTER_DISCOVERABLE_TIMEOUT = 0x09;

    static final int BT_PROPERTY_REMOTE_FRIENDLY_NAME = 0x0A;
    static final int BT_PROPERTY_REMOTE_RSSI = 0x0B;

    static final int BT_PROPERTY_REMOTE_VERSION_INFO = 0x0C;
    static final int BT_PROPERTY_LOCAL_LE_FEATURES = 0x0D;

    static final int BT_PROPERTY_DYNAMIC_AUDIO_BUFFER = 0x10;
    static final int BT_PROPERTY_REMOTE_IS_COORDINATED_SET_MEMBER = 0x11;
    static final int BT_PROPERTY_REMOTE_ASHA_CAPABILITY = 0X15;
    static final int BT_PROPERTY_REMOTE_ASHA_TRUNCATED_HISYNCID = 0X16;
    static final int BT_PROPERTY_REMOTE_MODEL_NUM = 0x17;
    static final int BT_PROPERTY_LPP_OFFLOAD_FEATURES = 0x1B;
    static final int BT_PROPERTY_UUIDS_LE = 0x1C;
    static final int BT_PROPERTY_DISCOVERY_RESULT_TYPE = 0x1D;
    static final int BT_PROPERTY_UUIDS_FROM_EXTENDED_INQUIRY_RESPONSE = 0x1E;
    static final int BT_PROPERTY_UUIDS_FROM_LE_ADVERTISING_DATA = 0x1F;
    static final int BT_PROPERTY_REMOTE_CONTROLLER_SECURE_CONNECTIONS_SUPPORTED = 0x20;
    static final int BT_PROPERTY_BREDR_PAIRING_TYPE = 0x21;
    static final int BT_PROPERTY_LE_PAIRING_TYPE = 0x22;

    public static final int BT_DEVICE_TYPE_BREDR = 0x01;
    static final int BT_DEVICE_TYPE_BLE = 0x02;
    static final int BT_DEVICE_TYPE_DUAL = 0x03;

    static final int BT_BOND_STATE_NONE = 0x00;
    static final int BT_BOND_STATE_BONDING = 0x01;
    static final int BT_BOND_STATE_BONDED = 0x02;

    static final int BT_LEGACY_PAIRING_VARIANT_PIN = 0x00;
    static final int BT_LEGACY_PAIRING_VARIANT_PIN_16 = 0x01;

    static final int BT_PAIRING_VARIANT_PASSKEY_CONFIRMATION = 0x00;
    static final int BT_PAIRING_VARIANT_PASSKEY_ENTRY = 0x01;
    static final int BT_PAIRING_VARIANT_CONSENT = 0x02;
    static final int BT_PAIRING_VARIANT_PASSKEY_NOTIFICATION = 0x03;
    static final int BT_PAIRING_VARIANT_PARTICIPATION = 0x04; // Incoming LE pairing request

    static final int BT_DISCOVERY_STOPPED = 0x00;
    static final int BT_DISCOVERY_STARTED = 0x01;

    static final int BT_ACL_STATE_CONNECTED = 0x00;
    static final int BT_ACL_STATE_DISCONNECTED = 0x01;

    static final int BT_REASON_FOR_NO_UUIDS_EMPTY_UUID_LIST = 0x01;
    static final int BT_REASON_FOR_NO_UUIDS_NO_UUID_TYPES_EXIST = 0x02;

    static final int BT_UUID_SIZE = 16; // bytes

    public static final int BT_STATUS_SUCCESS = 0;
    static final int BT_STATUS_FAIL = 1;
    static final int BT_STATUS_NOT_READY = 2;
    static final int BT_STATUS_NOMEM = 3;
    static final int BT_STATUS_BUSY = 4;
    static final int BT_STATUS_DONE = 5;
    static final int BT_STATUS_UNSUPPORTED = 6;
    static final int BT_STATUS_PARM_INVALID = 7;
    static final int BT_STATUS_UNHANDLED = 8;
    static final int BT_STATUS_AUTH_FAILURE = 9;
    static final int BT_STATUS_RMT_DEV_DOWN = 10;
    static final int BT_STATUS_AUTH_REJECTED = 11;
    static final int BT_STATUS_AUTH_TIMEOUT = 12;

    static final int BT_PAIRING_INITIATOR_APP = 0;
    static final int BT_PAIRING_INITIATOR_REMOTE_DEVICE = 1;
    static final int BT_PAIRING_INITIATOR_SERVICE_ACCESS_REQ = 2;
    static final int BT_PAIRING_INITIATOR_CTKD = 3;
    static final int BT_PAIRING_INITIATOR_REPAIRING = 4;

    static final int BT_PAIRING_ALGORITHM_NONE = 0;
    static final int BT_PAIRING_ALGORITHM_LE_LEGACY = 1;
    static final int BT_PAIRING_ALGORITHM_BREDR_LEGACY = 2;
    static final int BT_PAIRING_ALGORITHM_SSP = 3;
    static final int BT_PAIRING_ALGORITHM_SC = 4;

    private AbstractionLayer() {}
}
