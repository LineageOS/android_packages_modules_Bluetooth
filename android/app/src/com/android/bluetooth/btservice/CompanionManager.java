/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.bluetooth.BluetoothDevice.METADATA_SOFTWARE_VERSION;

import static java.util.Objects.requireNonNull;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemProperties;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import com.android.bluetooth.R;
import com.android.bluetooth.Util;
import com.android.bluetooth.flags.Flags;

import java.util.Optional;

/**
 * Manages parameters and connections for companion devices.
 *
 * <p>This manager handles the specific logic for identifying and interacting with paired companion
 * devices. Key behaviors and constraints include:
 *
 * <ul>
 *   <li>A paired device is recognized as a companion device if its {@code
 *       METADATA_SOFTWARE_VERSION} is set to {@code BluetoothDevice.COMPANION_TYPE_PRIMARY} or
 *       {@code BluetoothDevice.COMPANION_TYPE_SECONDARY}.
 *   <li>Only one companion device can be active at a time.
 *   <li>Remove bond does not remove the companion device record.
 *   <li>A factory reset of Bluetooth settings will remove the companion device record.
 *   <li>Each companion device has its own individual GATT connection parameters.
 * </ul>
 */
public class CompanionManager {
    private static final String TAG = Util.BT_PREFIX + CompanionManager.class.getSimpleName();

    public static final String TYPE_PRIMARY_STRING = "COMPANION_PRIMARY";
    public static final String TYPE_SECONDARY_STRING = "COMPANION_SECONDARY";

    private final int[] mGattConnHighPrimary;
    private final int[] mGattConnBalancePrimary;
    private final int[] mGattConnLowPrimary;
    private final int[] mGattConnHighSecondary;
    private final int[] mGattConnBalanceSecondary;
    private final int[] mGattConnLowSecondary;
    private final int[] mGattConnHighDefault;
    private final int[] mGattConnBalanceDefault;
    private final int[] mGattConnLowDefault;
    private final int[] mGattConnDckDefault;

    private final int mPrimarySupervisionTimeout;
    private final int mSecondarySupervisionTimeout;
    private final int mDefaultSupervisionTimeout;

    static final String PRIMARY_SUPERVISION_TIMEOUT_PROP =
            "bluetooth.ble.primary_supervision_timeout.config";

    /** BLE Link supervision timeouts is measured in N * 10ms. */
    public static final int PRIMARY_SUPERVISION_TIMEOUT_DEFAULT = 500;

    public static final int SECONDARY_SUPERVISION_TIMEOUT_DEFAULT = 500;
    public static final int DEFAULT_SUPERVISION_TIMEOUT_DEFAULT = 500;

    @VisibleForTesting static final int COMPANION_TYPE_NONE = 0;
    @VisibleForTesting static final int COMPANION_TYPE_PRIMARY = 1;
    @VisibleForTesting static final int COMPANION_TYPE_SECONDARY = 2;

    public static final int GATT_CONN_INTERVAL_MIN = 0;
    public static final int GATT_CONN_INTERVAL_MAX = 1;
    public static final int GATT_CONN_LATENCY = 2;

    private static final int CONN_HIGH_MODE_INTERVAL_MIN_DEFAULT = 9;
    private static final int CONN_HIGH_MODE_INTERVAL_MAX_DEFAULT = 12;
    private static final int CONN_HIGH_MODE_MAX_LATENCY_DEFAULT = 1;

    private static final int CONN_BALANCED_MODE_INTERVAL_MIN_DEFAULT = 24;
    private static final int CONN_BALANCED_MODE_INTERVAL_MAX_DEFAULT = 40;
    private static final int CONN_BALANCED_MODE_MAX_LATENCY_DEFAULT = 5;

    private static final int CONN_LOWPOWER_MODE_INTERVAL_MIN_DEFAULT = 80;
    private static final int CONN_LOWPOWER_MODE_INTERVAL_MAX_DEFAULT = 100;
    private static final int CONN_LOWPOWER_MODE_MAX_LATENCY_DEFAULT = 7;

    @VisibleForTesting static final String COMPANION_INFO = "bluetooth_companion_info";
    @VisibleForTesting static final String COMPANION_DEVICE_KEY = "companion_device";
    @VisibleForTesting static final String COMPANION_TYPE_KEY = "companion_type";

    static final String PROPERTY_HIGH_MIN_INTERVAL = "bluetooth.gatt.high_priority.min_interval";
    static final String PROPERTY_HIGH_MAX_INTERVAL = "bluetooth.gatt.high_priority.max_interval";
    static final String PROPERTY_HIGH_LATENCY = "bluetooth.gatt.high_priority.latency";
    static final String PROPERTY_BALANCED_MIN_INTERVAL =
            "bluetooth.gatt.balanced_priority.min_interval";
    static final String PROPERTY_BALANCED_MAX_INTERVAL =
            "bluetooth.gatt.balanced_priority.max_interval";
    static final String PROPERTY_BALANCED_LATENCY = "bluetooth.gatt.balanced_priority.latency";
    static final String PROPERTY_LOW_MIN_INTERVAL = "bluetooth.gatt.low_priority_min.interval";
    static final String PROPERTY_LOW_MAX_INTERVAL = "bluetooth.gatt.low_priority_max.interval";
    static final String PROPERTY_LOW_LATENCY = "bluetooth.gatt.low_priority.latency";
    static final String PROPERTY_DCK_MIN_INTERVAL = "bluetooth.gatt.dck_priority_min.interval";
    static final String PROPERTY_DCK_MAX_INTERVAL = "bluetooth.gatt.dck_priority_max.interval";
    static final String PROPERTY_DCK_LATENCY = "bluetooth.gatt.dck_priority.latency";

    static final String PROPERTY_SUFFIX_PRIMARY = ".primary";
    static final String PROPERTY_SUFFIX_SECONDARY = ".secondary";

    private final AdapterService mAdapterService;

    private record CompanionDevice(BluetoothDevice device, int type) {}

    private Optional<CompanionDevice> mCompanion = Optional.empty();

    public CompanionManager(AdapterService service) {
        mAdapterService = service;
        mGattConnHighDefault =
                (Flags.leSubrateManager())
                        ? new int[] {
                            SystemProperties.getInt(
                                    PROPERTY_HIGH_MIN_INTERVAL,
                                    CONN_HIGH_MODE_INTERVAL_MIN_DEFAULT),
                            SystemProperties.getInt(
                                    PROPERTY_HIGH_MAX_INTERVAL,
                                    CONN_HIGH_MODE_INTERVAL_MAX_DEFAULT),
                            SystemProperties.getInt(
                                    PROPERTY_HIGH_LATENCY, CONN_HIGH_MODE_MAX_LATENCY_DEFAULT)
                        }
                        : new int[] {
                            getGattConfig(
                                    PROPERTY_HIGH_MIN_INTERVAL,
                                    R.integer.gatt_high_priority_min_interval),
                            getGattConfig(
                                    PROPERTY_HIGH_MAX_INTERVAL,
                                    R.integer.gatt_high_priority_max_interval),
                            getGattConfig(
                                    PROPERTY_HIGH_LATENCY, R.integer.gatt_high_priority_latency)
                        };
        mGattConnBalanceDefault =
                (Flags.leSubrateManager())
                        ? new int[] {
                            SystemProperties.getInt(
                                    PROPERTY_BALANCED_MIN_INTERVAL,
                                    CONN_BALANCED_MODE_INTERVAL_MIN_DEFAULT),
                            SystemProperties.getInt(
                                    PROPERTY_BALANCED_MAX_INTERVAL,
                                    CONN_BALANCED_MODE_INTERVAL_MAX_DEFAULT),
                            SystemProperties.getInt(
                                    PROPERTY_BALANCED_LATENCY,
                                    CONN_BALANCED_MODE_MAX_LATENCY_DEFAULT)
                        }
                        : new int[] {
                            getGattConfig(
                                    PROPERTY_BALANCED_MIN_INTERVAL,
                                    R.integer.gatt_balanced_priority_min_interval),
                            getGattConfig(
                                    PROPERTY_BALANCED_MAX_INTERVAL,
                                    R.integer.gatt_balanced_priority_max_interval),
                            getGattConfig(
                                    PROPERTY_BALANCED_LATENCY,
                                    R.integer.gatt_balanced_priority_latency)
                        };
        mGattConnLowDefault =
                (Flags.leSubrateManager())
                        ? new int[] {
                            SystemProperties.getInt(
                                    PROPERTY_LOW_MIN_INTERVAL,
                                    CONN_LOWPOWER_MODE_INTERVAL_MIN_DEFAULT),
                            SystemProperties.getInt(
                                    PROPERTY_LOW_MAX_INTERVAL,
                                    CONN_LOWPOWER_MODE_INTERVAL_MAX_DEFAULT),
                            SystemProperties.getInt(
                                    PROPERTY_LOW_LATENCY, CONN_LOWPOWER_MODE_MAX_LATENCY_DEFAULT)
                        }
                        : new int[] {
                            getGattConfig(
                                    PROPERTY_LOW_MIN_INTERVAL,
                                    R.integer.gatt_low_power_min_interval),
                            getGattConfig(
                                    PROPERTY_LOW_MAX_INTERVAL,
                                    R.integer.gatt_low_power_max_interval),
                            getGattConfig(PROPERTY_LOW_LATENCY, R.integer.gatt_low_power_latency)
                        };
        mGattConnDckDefault =
                new int[] {
                    getGattConfig(
                            PROPERTY_DCK_MIN_INTERVAL, R.integer.gatt_dck_priority_min_interval),
                    getGattConfig(
                            PROPERTY_DCK_MAX_INTERVAL, R.integer.gatt_dck_priority_max_interval),
                    getGattConfig(PROPERTY_DCK_LATENCY, R.integer.gatt_dck_priority_latency)
                };

        mGattConnHighPrimary =
                new int[] {
                    getGattConfig(
                            PROPERTY_HIGH_MIN_INTERVAL + PROPERTY_SUFFIX_PRIMARY,
                            R.integer.gatt_high_priority_min_interval_primary),
                    getGattConfig(
                            PROPERTY_HIGH_MAX_INTERVAL + PROPERTY_SUFFIX_PRIMARY,
                            R.integer.gatt_high_priority_max_interval_primary),
                    getGattConfig(
                            PROPERTY_HIGH_LATENCY + PROPERTY_SUFFIX_PRIMARY,
                            R.integer.gatt_high_priority_latency_primary)
                };
        mGattConnBalancePrimary =
                new int[] {
                    getGattConfig(
                            PROPERTY_BALANCED_MIN_INTERVAL + PROPERTY_SUFFIX_PRIMARY,
                            R.integer.gatt_balanced_priority_min_interval_primary),
                    getGattConfig(
                            PROPERTY_BALANCED_MAX_INTERVAL + PROPERTY_SUFFIX_PRIMARY,
                            R.integer.gatt_balanced_priority_max_interval_primary),
                    getGattConfig(
                            PROPERTY_BALANCED_LATENCY + PROPERTY_SUFFIX_PRIMARY,
                            R.integer.gatt_balanced_priority_latency_primary)
                };
        mGattConnLowPrimary =
                new int[] {
                    getGattConfig(
                            PROPERTY_LOW_MIN_INTERVAL + PROPERTY_SUFFIX_PRIMARY,
                            R.integer.gatt_low_power_min_interval_primary),
                    getGattConfig(
                            PROPERTY_LOW_MAX_INTERVAL + PROPERTY_SUFFIX_PRIMARY,
                            R.integer.gatt_low_power_max_interval_primary),
                    getGattConfig(
                            PROPERTY_LOW_LATENCY + PROPERTY_SUFFIX_PRIMARY,
                            R.integer.gatt_low_power_latency_primary)
                };

        mGattConnHighSecondary =
                new int[] {
                    getGattConfig(
                            PROPERTY_HIGH_MIN_INTERVAL + PROPERTY_SUFFIX_SECONDARY,
                            R.integer.gatt_high_priority_min_interval_secondary),
                    getGattConfig(
                            PROPERTY_HIGH_MAX_INTERVAL + PROPERTY_SUFFIX_SECONDARY,
                            R.integer.gatt_high_priority_max_interval_secondary),
                    getGattConfig(
                            PROPERTY_HIGH_LATENCY + PROPERTY_SUFFIX_SECONDARY,
                            R.integer.gatt_high_priority_latency_secondary)
                };
        mGattConnBalanceSecondary =
                new int[] {
                    getGattConfig(
                            PROPERTY_BALANCED_MIN_INTERVAL + PROPERTY_SUFFIX_SECONDARY,
                            R.integer.gatt_balanced_priority_min_interval_secondary),
                    getGattConfig(
                            PROPERTY_BALANCED_MAX_INTERVAL + PROPERTY_SUFFIX_SECONDARY,
                            R.integer.gatt_balanced_priority_max_interval_secondary),
                    getGattConfig(
                            PROPERTY_BALANCED_LATENCY + PROPERTY_SUFFIX_SECONDARY,
                            R.integer.gatt_balanced_priority_latency_secondary)
                };
        mGattConnLowSecondary =
                new int[] {
                    getGattConfig(
                            PROPERTY_LOW_MIN_INTERVAL + PROPERTY_SUFFIX_SECONDARY,
                            R.integer.gatt_low_power_min_interval_secondary),
                    getGattConfig(
                            PROPERTY_LOW_MAX_INTERVAL + PROPERTY_SUFFIX_SECONDARY,
                            R.integer.gatt_low_power_max_interval_secondary),
                    getGattConfig(
                            PROPERTY_LOW_LATENCY + PROPERTY_SUFFIX_SECONDARY,
                            R.integer.gatt_low_power_latency_secondary)
                };

        int primaryTimeout =
                SystemProperties.getInt(
                        PRIMARY_SUPERVISION_TIMEOUT_PROP, PRIMARY_SUPERVISION_TIMEOUT_DEFAULT);
        if (primaryTimeout > PRIMARY_SUPERVISION_TIMEOUT_DEFAULT) {
            mPrimarySupervisionTimeout = primaryTimeout;
        } else {
            Log.d(TAG, "Invalid Primary Supervision Timeout property, restoring to default");
            mPrimarySupervisionTimeout = PRIMARY_SUPERVISION_TIMEOUT_DEFAULT;
        }
        mSecondarySupervisionTimeout =
                SECONDARY_SUPERVISION_TIMEOUT_DEFAULT; // 5s. measured in N * 10ms
        mDefaultSupervisionTimeout =
                DEFAULT_SUPERVISION_TIMEOUT_DEFAULT; // 5s. measured in N * 10ms
    }

    private int getGattConfig(String property, int resId) {
        return SystemProperties.getInt(property, mAdapterService.getResources().getInteger(resId));
    }

    void loadCompanionInfo() {
        String address = getCompanionPreferences().getString(COMPANION_DEVICE_KEY, "");
        int companionType =
                getCompanionPreferences().getInt(COMPANION_TYPE_KEY, COMPANION_TYPE_NONE);
        if (BluetoothAdapter.checkBluetoothAddress(address)) {
            mCompanion =
                    Optional.of(
                            new CompanionDevice(
                                    mAdapterService.getRemoteDevice(address), companionType));
        }

        if (mCompanion.isEmpty()) {
            // We don't have any companion phone registered, try look from the bonded devices
            for (BluetoothDevice device : mAdapterService.getBondedDevices()) {
                byte[] metadata = mAdapterService.getMetadata(device, METADATA_SOFTWARE_VERSION);
                if (metadata == null) {
                    continue;
                }
                if (setCompanionDevice(device, metadata)) {
                    Log.i(TAG, "Found companion device from the database!");
                    break;
                }
            }
        }
        Log.i(TAG, "Companion device is " + mCompanion);
    }

    boolean setCompanionDevice(BluetoothDevice device, byte[] rawType) {
        if (mCompanion.isPresent()) {
            return true; // Companion already set
        }
        String typeStr = new String(rawType);
        int type;
        if (typeStr.equals(TYPE_PRIMARY_STRING)) {
            type = COMPANION_TYPE_PRIMARY;
        } else if (typeStr.equals(TYPE_SECONDARY_STRING)) {
            type = COMPANION_TYPE_SECONDARY;
        } else {
            return false; // invalid companion
        }
        Log.i(TAG, "setCompanionDevice(" + device + ", " + type + ")");
        mCompanion = Optional.of(new CompanionDevice(device, type));
        getCompanionPreferences()
                .edit()
                .putString(COMPANION_DEVICE_KEY, device.getAddress())
                .putInt(COMPANION_TYPE_KEY, type)
                .apply();
        return true;
    }

    private SharedPreferences getCompanionPreferences() {
        return mAdapterService.getSharedPreferences(COMPANION_INFO, Context.MODE_PRIVATE);
    }

    @VisibleForTesting
    int getCompanionType(BluetoothDevice device) {
        BluetoothDevice companion = mCompanion.stream().map(c -> c.device).findFirst().orElse(null);
        if (device.equals(companion)) {
            return mCompanion.get().type;
        }
        return COMPANION_TYPE_NONE;
    }

    /**
     * Gets the GATT connection parameters of the device
     *
     * @param device the Bluetooth device
     * @param type type of the parameter, can be GATT_CONN_INTERVAL_MIN, GATT_CONN_INTERVAL_MAX or
     *     GATT_CONN_LATENCY
     * @param priority the priority of the connection, can be
     *     BluetoothGatt.CONNECTION_PRIORITY_HIGH, BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER or
     *     BluetoothGatt.CONNECTION_PRIORITY_BALANCED
     * @return the connection parameter in integer
     */
    public int getGattConnParameters(BluetoothDevice device, int type, int priority) {
        int companionType = getCompanionType(requireNonNull(device));
        return switch (companionType) {
            case COMPANION_TYPE_PRIMARY -> getGattConnParameterPrimary(type, priority);
            case COMPANION_TYPE_SECONDARY -> getGattConnParameterSecondary(type, priority);
            default -> getGattConnParameterDefault(type, priority);
        };
    }

    /**
     * Gets the GATT connection supervision timeout parameter
     *
     * @param device the Bluetooth device
     * @return the connection parameter in integer
     */
    public int getGattSupervisionTimeout(BluetoothDevice device) {
        int companionType = getCompanionType(requireNonNull(device));
        Log.i(TAG, "companionType: " + companionType);
        return switch (companionType) {
            case COMPANION_TYPE_PRIMARY -> mPrimarySupervisionTimeout;
            case COMPANION_TYPE_SECONDARY -> mSecondarySupervisionTimeout;
            default -> mDefaultSupervisionTimeout;
        };
    }

    private int getGattConnParameterPrimary(int type, int priority) {
        return switch (priority) {
            case BluetoothGatt.CONNECTION_PRIORITY_HIGH -> mGattConnHighPrimary[type];
            case BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER -> mGattConnLowPrimary[type];
            default -> mGattConnBalancePrimary[type];
        };
    }

    private int getGattConnParameterSecondary(int type, int priority) {
        return switch (priority) {
            case BluetoothGatt.CONNECTION_PRIORITY_HIGH -> mGattConnHighSecondary[type];
            case BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER -> mGattConnLowSecondary[type];
            default -> mGattConnBalanceSecondary[type];
        };
    }

    private int getGattConnParameterDefault(int type, int mode) {
        return switch (mode) {
            case BluetoothGatt.CONNECTION_PRIORITY_HIGH -> mGattConnHighDefault[type];
            case BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER -> mGattConnLowDefault[type];
            case BluetoothGatt.CONNECTION_PRIORITY_DCK -> mGattConnDckDefault[type];
            default -> mGattConnBalanceDefault[type];
        };
    }
}
