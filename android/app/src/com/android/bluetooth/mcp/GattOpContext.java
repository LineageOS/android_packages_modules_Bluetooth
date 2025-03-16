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

package com.android.bluetooth.mcp;

import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;

import com.google.protobuf.ByteString;

public record GattOpContext(
        Operation operation,
        int requestId,
        BluetoothGattCharacteristic characteristic,
        BluetoothGattDescriptor descriptor,
        boolean preparedWrite,
        boolean responseNeeded,
        int offset,
        ByteString value) {
    public enum Operation {
        READ_CHARACTERISTIC,
        WRITE_CHARACTERISTIC,
        READ_DESCRIPTOR,
        WRITE_DESCRIPTOR,
    }

    public GattOpContext(
            Operation operation,
            int requestId,
            BluetoothGattCharacteristic characteristic,
            BluetoothGattDescriptor descriptor,
            int offset) {
        this(operation, requestId, characteristic, descriptor, false, false, offset, null);
    }
}
