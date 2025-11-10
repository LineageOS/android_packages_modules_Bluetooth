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

import static android.bluetooth.BluetoothDevice.Transport;

import static java.util.Objects.requireNonNull;

import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.RequiresNoPermission;

import com.android.bluetooth.flags.Flags;

import java.util.concurrent.Executor;

/**
 * Defines parameters for creating BluetoothGatt connection.
 *
 * <p>Used with {@link BluetoothDevice#connectGatt} to create a Gatt client connection.
 *
 * <p>{@link BluetoothDevice#connectGatt} ensures It applies the Gatt settings passed as part of
 * {@link BluetoothGattConnectionSettings}
 *
 * @see BluetoothDevice#connectGatt
 */
@FlaggedApi(Flags.FLAG_GATT_CONN_SETTINGS)
public final class BluetoothGattConnectionSettings {
    /**
     * Setting this to true, would enable the automatic connection to remote device when It is
     * available, false would trigger direction LE connection to remote device
     */
    private final boolean mAutoConnectEnabled;

    /** Determine if this GATT client connection is opportunistic or not */
    private final boolean mOpportunisticEnabled;


    /** Transport to be used for GATT connection. */
    private final @Transport int mTransport;

    /**
     * Bluetooth gatt callback object {@link BluetoothGattCallback} which will be used to notify
     * application with various Bluetooth Gatt related statuses
     */
    private final @NonNull BluetoothGattCallback mBluetoothGattCallback;

    /** Executor on which callbacks will be invoked */
    private final @NonNull Executor mCallbackExecutor;

    /** Returns true if auto connection enabled or false otherwise. */
    @RequiresNoPermission
    public boolean isAutoConnectEnabled() {
        return mAutoConnectEnabled;
    }

    /** Returns if the GATT connection is opportunistic or not. */
    @RequiresNoPermission
    public boolean isOpportunisticEnabled() {
        return mOpportunisticEnabled;
    }

    /** Returns the transport to be used for GATT connection. */
    @RequiresNoPermission
    public @Transport int getTransport() {
        return mTransport;
    }

    /** Returns callback handle to receive the Bluetooth Gatt related callbacks. */
    public @NonNull BluetoothGattCallback getBluetoothGattCallback() {
        return mBluetoothGattCallback;
    }

    /** Returns the callback executor on which Bluetooth Gatt related callbacks will be invoked */
    @RequiresNoPermission
    public @NonNull Executor getBluetoothGattCallbackExecutor() {
        return mCallbackExecutor;
    }

    /**
     * Returns a {@link String} that describes each BluetoothGattConnectionSettings parameter
     * current value.
     */
    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("BluetoothGattConnectionSettings{");
        builder.append(", mIsAutoConnectEnabled=")
                .append(mAutoConnectEnabled)
                .append(", mIsOpportunisticEnabled=")
                .append(mOpportunisticEnabled)
                .append(", mTransport=")
                .append(mTransport)
                .append(", mBluetoothGattCallback=")
                .append(mBluetoothGattCallback)
                .append(", mCallbackExecutor=")
                .append(mCallbackExecutor)
                .append("}");
        return builder.toString();
    }

    private BluetoothGattConnectionSettings(
            boolean isAutoConnectEnabled,
            boolean isOpportunisticEnabled,
            @Transport int transport,
            @CallbackExecutor Executor executor,
            BluetoothGattCallback bluetoothGattCallback) {
        mAutoConnectEnabled = isAutoConnectEnabled;
        mOpportunisticEnabled = isOpportunisticEnabled;
        mTransport = transport;
        mBluetoothGattCallback = bluetoothGattCallback;
        mCallbackExecutor = executor;
    }

    /** Builder for {@link BluetoothGattConnectionSettings}. */
    public static final class Builder {
        private boolean mAutoConnectEnabled = false;
        private boolean mOpportunisticEnabled = false;
        private @Transport int mTransport = BluetoothDevice.TRANSPORT_LE;
        private @NonNull BluetoothGattCallback mBluetoothGattCallback;
        private @NonNull Executor mExecutor;

        /**
         * Creates a new Builder for {@link BluetoothGattConnectionSettings}.
         *
         * @param executor The executor on which GATT callbacks will be invoked.
         * @param bluetoothGattCallback The callback object to receive GATT events.
         */
        public Builder(
                @NonNull @CallbackExecutor Executor executor,
                @NonNull BluetoothGattCallback bluetoothGattCallback) {
            mExecutor = requireNonNull(executor);
            mBluetoothGattCallback = requireNonNull(bluetoothGattCallback);
        }

        /**
         * Setting this to true will enable the automatic connection to remote device when It is
         * available. Setting it to False would trigger direct connect to remote device
         *
         * @param autoConnectEnabled true if auto connection enabled, false otherwise.
         * @return This builder.
         */
        @NonNull
        @RequiresNoPermission
        public Builder setAutoConnectEnabled(boolean autoConnectEnabled) {
            mAutoConnectEnabled = autoConnectEnabled;
            return this;
        }

        /**
         * Sets whether this GATT client is opportunistic. An opportunistic GATT client does not
         * hold a GATT connection. It automatically disconnects when no other GATT connections are
         * active for the remote device
         *
         * @param opportunisticEnabled true if this connection is opportunistic, false otherwise.
         * @return This builder.
         */
        @NonNull
        @RequiresNoPermission
        public Builder setOpportunisticEnabled(boolean opportunisticEnabled) {
            mOpportunisticEnabled = opportunisticEnabled;
            return this;
        }

        /**
         * Sets the transport for this Gatt settings. preferred transport for GATT connections to
         * remote dual-mode devices.
         *
         * @return This builder.
         */
        @NonNull
        @RequiresNoPermission
        public Builder setTransport(@Transport int transport) {
            mTransport = transport;
            return this;
        }

        /**
         * Builds a {@link BluetoothGattConnectionSettings} object.
         *
         * @return A new {@link BluetoothGattConnectionSettings} object with the configured
         *     parameters.
         * @throws IllegalArgumentException on invalid parameters
         */
        @NonNull
        @RequiresNoPermission
        public BluetoothGattConnectionSettings build() {
            return new BluetoothGattConnectionSettings(
                    mAutoConnectEnabled,
                    mOpportunisticEnabled,
                    mTransport,
                    mExecutor,
                    mBluetoothGattCallback);
        }
    }
}
