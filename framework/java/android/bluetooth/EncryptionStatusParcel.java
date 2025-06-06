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

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.bluetooth.BluetoothDevice.EncryptionAlgorithm;
import android.bluetooth.EncryptionStatus;

import android.os.Parcel;
import android.os.Parcelable;

import com.android.bluetooth.flags.Flags;

/**
 * Builder class for {@link EncryptionStatus} This class is added to prevent the {@link
 * EncryptionStatus} from being implementing Parcelable directly.
 *
 * @hide
 */
public final class EncryptionStatusParcel implements Parcelable {
    private int keySize;
    private int algorithm;

    public EncryptionStatusParcel(int keySize, int algorithm) {
        this.keySize = keySize;
        this.algorithm = algorithm;
    }

    @FlaggedApi(Flags.FLAG_LINK_STATUS_API)
    public @NonNull EncryptionStatus toEncryptionStatus() {
        return new EncryptionStatus(keySize, algorithm);
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeInt(keySize);
        out.writeInt(algorithm);
    }

    private EncryptionStatusParcel(@NonNull Parcel in) {
        this(in.readInt(), in.readInt());
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** {@link Parcelable.Creator} interface implementation. */
    public static final @NonNull Parcelable.Creator<EncryptionStatusParcel> CREATOR =
            new Parcelable.Creator<EncryptionStatusParcel>() {
                public @NonNull EncryptionStatusParcel createFromParcel(Parcel in) {
                    return new EncryptionStatusParcel(in);
                }

                public @NonNull EncryptionStatusParcel[] newArray(int size) {
                    return new EncryptionStatusParcel[size];
                }
            };
}
