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

package android.bluetooth;

import static android.Manifest.permission.BLUETOOTH_CONNECT;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.RequiresNoPermission;
import android.annotation.RequiresPermission;
import android.bluetooth.BluetoothDevice.EncryptionAlgorithm;

import android.os.Parcel;
import android.os.Parcelable;

import com.android.bluetooth.flags.Flags;

/**
 * Represents the encryption status of a Bluetooth device.
 *
 * <p>This class is used to hold the encryption status details like key size and algorithm of a
 * Bluetooth device.
 */
@FlaggedApi(Flags.FLAG_LINK_STATUS_API)
public final class EncryptionStatus {
    private final int keySize;
    private final int algorithm;

    public EncryptionStatus(
            @IntRange(from = 1, to = 16) int keySize, @EncryptionAlgorithm int algorithm) {
        this.keySize = keySize;
        this.algorithm = algorithm;
    }

    /**
     * @return the size of the encryption key, in number of bytes. i.e. value of 16 means 16-octets,
     *     or 128 bit key size.
     */
    @RequiresNoPermission
    public @IntRange(from = 1, to = 16) int getKeySize() {
        return keySize;
    }

    /**
     * @return the encryption algorithm used for the encrypting the link.
     */
    @RequiresNoPermission
    public @EncryptionAlgorithm int getAlgorithm() {
        return algorithm;
    }

    @Override
    public String toString() {
        return "EncryptionStatus{keySize=" + keySize + ", algorithm=" + algorithm + "}";
    }
}
