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

package com.android.server.bluetooth;

import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.bluetooth.BluetoothProtoEnums.ENABLE_DISABLE_REASON_AIRPLANE_MODE;
import static android.bluetooth.BluetoothProtoEnums.ENABLE_DISABLE_REASON_APPLICATION_REQUEST;
import static android.bluetooth.BluetoothProtoEnums.ENABLE_DISABLE_REASON_AUTO_ON;
import static android.bluetooth.BluetoothProtoEnums.ENABLE_DISABLE_REASON_CRASH;
import static android.bluetooth.BluetoothProtoEnums.ENABLE_DISABLE_REASON_DISALLOWED;
import static android.bluetooth.BluetoothProtoEnums.ENABLE_DISABLE_REASON_FACTORY_RESET;
import static android.bluetooth.BluetoothProtoEnums.ENABLE_DISABLE_REASON_RESTARTED;
import static android.bluetooth.BluetoothProtoEnums.ENABLE_DISABLE_REASON_RESTORE_USER_SETTING;
import static android.bluetooth.BluetoothProtoEnums.ENABLE_DISABLE_REASON_SATELLITE_MODE;
import static android.bluetooth.BluetoothProtoEnums.ENABLE_DISABLE_REASON_START_ERROR;
import static android.bluetooth.BluetoothProtoEnums.ENABLE_DISABLE_REASON_SYSTEM_BOOT;
import static android.bluetooth.BluetoothProtoEnums.ENABLE_DISABLE_REASON_USER_SWITCH;
import static android.bluetooth.IBluetoothManager.ACTION_BLE_STATE_CHANGED;
import static android.bluetooth.IBluetoothManager.ACTION_LOCAL_NAME_CHANGED;
import static android.bluetooth.IBluetoothManager.ACTION_STATE_CHANGED;
import static android.bluetooth.IBluetoothManager.BT_SNOOP_LOG_MODE_DISABLED;
import static android.bluetooth.IBluetoothManager.BT_SNOOP_LOG_MODE_FILTERED;
import static android.bluetooth.IBluetoothManager.BT_SNOOP_LOG_MODE_FULL;
import static android.bluetooth.IBluetoothManager.EXTRA_LOCAL_NAME;
import static android.bluetooth.IBluetoothManager.EXTRA_PREVIOUS_STATE;
import static android.bluetooth.IBluetoothManager.EXTRA_STATE;
import static android.os.PowerExemptionManager.TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.BroadcastOptions;
import android.bluetooth.IAdapter;
import android.bluetooth.IBluetoothCallback;
import android.bluetooth.IBluetoothManagerCallback;
import android.bluetooth.State;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerExemptionManager;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.sysprop.BluetoothProperties;

import com.android.bluetooth.flags.Flags;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.bluetooth.airplane.AirplaneModeListener;
import com.android.server.bluetooth.satellite.SatelliteModeListener;

import kotlin.Unit;
import kotlin.time.TimeSource;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

class BluetoothManagerService {
    private static final String TAG = BluetoothManagerService.class.getSimpleName();

    private static final int CRASH_LOG_MAX_SIZE = 100;

    // See android.os.Build.HW_TIMEOUT_MULTIPLIER. This should not be set on real hw
    private static final int HW_MULTIPLIER = SystemProperties.getInt("ro.hw_timeout_multiplier", 1);

    private static final Pattern ADDR_PATTERN = Pattern.compile("^([0-9A-F]{2}:){5}[0-9A-F]{2}$");

    // Maximum msec to wait for a bind
    private static final int TIMEOUT_BIND_MS = 4000 * HW_MULTIPLIER;

    // Timeout value for synchronous binder call
    private static final Duration STATE_TIMEOUT = Duration.ofSeconds(4L * HW_MULTIPLIER);

    // Maximum msec to wait for service restart
    @VisibleForTesting static final int SERVICE_RESTART_TIME_MS = 400 * HW_MULTIPLIER;
    // Delay for the addProxy function in msec
    @VisibleForTesting static final int ADD_PROXY_DELAY_MS = 100 * HW_MULTIPLIER;
    // Delay for retrying enable and disable in msec
    @VisibleForTesting static final int ENABLE_DISABLE_DELAY_MS = 300 * HW_MULTIPLIER;

    @VisibleForTesting static final int MESSAGE_HANDLE_DISABLE_DELAYED = 4;

    @VisibleForTesting static final int MESSAGE_BLUETOOTH_SERVICE_CONNECTED = 40;
    @VisibleForTesting static final int MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED = 41;
    @VisibleForTesting static final int MESSAGE_RESTART_BLUETOOTH_SERVICE = 42;
    @VisibleForTesting static final int MESSAGE_BLUETOOTH_STATE_CHANGE = 60;
    @VisibleForTesting static final int MESSAGE_TIMEOUT_BIND = 100;
    @VisibleForTesting static final int MESSAGE_RESTORE_USER_SETTING_OFF = 501;
    @VisibleForTesting static final int MESSAGE_RESTORE_USER_SETTING_ON = 502;

    private static final int MAX_ERROR_RESTART_RETRIES = 6;
    private static final int MAX_WAIT_FOR_ENABLE_DISABLE_RETRIES = 10;

    // Bluetooth persisted setting is off
    @VisibleForTesting static final int BLUETOOTH_OFF = 0;
    // Bluetooth persisted setting is on
    // and Airplane mode won't affect Bluetooth state at start up
    // This is the default value
    @VisibleForTesting static final int BLUETOOTH_ON_BLUETOOTH = 1;
    // Bluetooth persisted setting is on
    // but Airplane mode will affect Bluetooth state at start up
    // and Airplane mode will have higher priority.
    @VisibleForTesting static final int BLUETOOTH_ON_AIRPLANE = 2;

    private final BleAppManager mBleAppManager;
    private final ActiveLogs mActiveLogs;
    private final BluetoothComponent mBluetoothComponent;

    private final BluetoothAdapterState mState = new BluetoothAdapterState();
    private final List<Long> mCrashTimestamps = new ArrayList<>();
    private final RemoteCallbackList<IBluetoothManagerCallback> mCallbacks =
            new RemoteCallbackList<>();
    @VisibleForTesting final BluetoothHandler mHandler;
    private final ContentResolver mContentResolver;
    private final Context mContext;
    private final Looper mLooper;
    private final UserManager mUserManager;

    private final boolean mIsHearingAidProfileSupported;
    private final String mHciInstanceName;
    private AutoOn mAutoOn;
    private SharingRestriction mSharingRestriction;

    private String mAddress;
    private String mName;
    private AdapterBinder mAdapter;
    private Context mUserContext;
    private UserHandle mUser;
    private UserHandle mNextUser; // Non null if a user switch is in progress

    // used inside handler thread
    private boolean mQuietEnable = false;
    private boolean mEnable = false;
    private boolean mShutdownInProgress = false;

    private int mCrashes = 0;
    private Instant mLastEnabledTime;

    // configuration from external IBinder call which is used to
    // synchronize with broadcast receiver.
    private boolean mQuietEnableExternal = false;
    private boolean mEnableExternal = false;

    private int mErrorRecoveryRetryCounter = 0;

    // The code in mBluetoothCallback is running on Binder thread.
    // It must be posted on the local looper to prevent concurrent access.
    private final IBluetoothCallback mBluetoothCallback =
            new IBluetoothCallback.Stub() {
                @Override
                public void onBluetoothStateChange(int prevState, int newState) {
                    Log.d(
                            TAG,
                            "IBluetoothCallback.onBluetoothStateChange:"
                                    + (" prevState=" + State.$.toString(prevState))
                                    + (" newState=" + State.$.toString(newState)));
                    mHandler.obtainMessage(MESSAGE_BLUETOOTH_STATE_CHANGE, prevState, newState)
                            .sendToTarget();
                }

                @Override
                public void onAdapterNameChange(String name) {
                    requireNonNull(name);
                    if (name.isEmpty()) {
                        throw new IllegalArgumentException("Invalid Empty name");
                    }
                    Log.d(TAG, "IBluetoothCallback.onAdapterNameChange: " + name);
                    mHandler.post(() -> storeName(name));
                }

                @Override
                public void onAdapterAddressChange(String address) {
                    requireNonNull(address);
                    if (!ADDR_PATTERN.matcher(address).matches()) {
                        throw new IllegalArgumentException("Invalid address");
                    }
                    Log.d(
                            TAG,
                            "IBluetoothCallback.onAdapterAddressChange: " + Log.address(address));
                    mHandler.post(() -> storeAddress(address));
                }

                @Override
                public void onMediaProfileConnectionChange(boolean connected) {
                    Log.d(TAG, "IBluetoothCallback.onMediaProfileConnectionChange: " + connected);
                    mHandler.post(
                            () -> {
                                AirplaneModeListener.setIsMediaProfileConnected(connected);
                            });
                }

                @Override
                public void onWatchConnectionChange(boolean connected) {
                    Log.d(TAG, "IBluetoothCallback.onWatchConnectionChange: " + connected);
                    mHandler.post(() -> AirplaneModeListener.setWatchConnectionState(connected));
                }

                @Override
                public void setAdapterServiceBinder(IBinder binder) {
                    Log.d(TAG, "IBluetoothCallback.setAdapterServiceBinder: " + binder);
                    mHandler.post(
                            () -> {
                                if (mAdapter == null) {
                                    return;
                                }
                                mAdapter.setAdapterServiceBinder(binder);
                                broadcastToAdapters(
                                        "setAdapterServiceBinder",
                                        (item) -> item.onBluetoothServiceUp(binder));
                            });
                }
            };

    private void storeName(String name) {
        if (!Settings.Secure.putString(mContentResolver, Settings.Secure.BLUETOOTH_NAME, name)) {
            Log.e(TAG, "storeName(" + name + "): Failed. Name is still " + mName);
            return;
        }
        mName = name;
        Log.v(TAG, "storeName(" + mName + "): Success");
        Intent intent =
                new Intent(ACTION_LOCAL_NAME_CHANGED)
                        .putExtra(EXTRA_LOCAL_NAME, name)
                        .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        if (Flags.onlyBroadcastToLocalUser()) {
            mContext.sendBroadcastAsUser(
                    intent, mUser, BLUETOOTH_CONNECT, getTempAllowlistBroadcastOptions());
        } else {
            mContext.sendBroadcastAsUser(
                    intent, UserHandle.ALL, BLUETOOTH_CONNECT, getTempAllowlistBroadcastOptions());
        }
    }

    private void storeAddress(String address) {
        if (!Settings.Secure.putString(
                mContentResolver, Settings.Secure.BLUETOOTH_ADDRESS, address)) {
            Log.e(
                    TAG,
                    "storeAddress("
                            + Log.address(address)
                            + "): Failed. Address is still "
                            + Log.address(mAddress));
            return;
        }
        mAddress = address;
        Log.v(TAG, "storeAddress(" + Log.address(mAddress) + "): Success");
    }

    public void onUserRestrictionsChanged(UserHandle userHandle) {
        if (Flags.userRestrictionRefactor()) {
            throw new IllegalStateException("userRestrictionRefactor is enabled");
        }
        final boolean newBluetoothDisallowed =
                mUserManager.hasUserRestrictionForUser(UserManager.DISALLOW_BLUETOOTH, userHandle);
        // Disallow Bluetooth sharing when either Bluetooth is disallowed or Bluetooth sharing
        // is disallowed
        final boolean newBluetoothSharingDisallowed =
                mUserManager.hasUserRestrictionForUser(
                                UserManager.DISALLOW_BLUETOOTH_SHARING, userHandle)
                        || newBluetoothDisallowed;

        // Disable OPP activities for this userHandle
        updateOppLauncherComponentState(userHandle, newBluetoothSharingDisallowed);

        // DISALLOW_BLUETOOTH can only be set by DO or PO on the system user.
        // Only trigger once instead of for all users
        if (UserHandle.SYSTEM.equals(userHandle) && newBluetoothDisallowed) {
            sendDisableMsg(ENABLE_DISABLE_REASON_DISALLOWED);
        }
    }

    boolean factoryReset(int count) {
        if (mAutoOn != null) {
            mAutoOn.factoryReset();
        }
        AirplaneModeListener.factoryReset(mContentResolver, mUserContext);
        setBtHciSnoopLogMode(-1);

        if (count == 10 || mState.oneOf(State.OFF)) {
            Log.e(TAG, "factoryReset(" + count + "): Set property to retry when Bluetooth start");
            BluetoothProperties.factory_reset(true);
            return false;
        }

        if (!mState.oneOf(State.BLE_ON, State.ON)) {
            // Bluetooth can not be toggled when it is in a transition state
            mHandler.postDelayed(() -> factoryReset(count + 1), 1_000);
            return false;
        }

        Log.d(TAG, "factoryReset: Will perform service restart after setting reset property");
        BluetoothProperties.factory_reset(true);

        mBleAppManager.clearBleApps();
        mActiveLogs.add(ENABLE_DISABLE_REASON_FACTORY_RESET, false);
        if (mState.oneOf(State.BLE_ON)) {
            bleOnToOff();
        } else {
            onToBleOn();
        }
        return true;
    }

    private int estimateBusyTime(Object token) {
        if (!Flags.gracefulDisableWithoutMessage()
                && mState.oneOf(State.BLE_ON)
                && isBluetoothPersistedStateOn()) {
            // Impossible case, if BrEdr is starting, state would be TURNING_ON
            // Bluetooth is in BLE and is starting classic
            return SERVICE_RESTART_TIME_MS;
        } else if (!mState.oneOf(State.ON, State.OFF, State.BLE_ON, State.BLE_TURNING_ON)) {
            // Bluetooth is in a temporary turning state
            return ADD_PROXY_DELAY_MS;
        } else if ((!Flags.gracefulDisableWithoutMessage()
                        && mHandler.hasMessages(MESSAGE_HANDLE_DISABLE_DELAYED))
                || mHandler.hasMessages(MESSAGE_RESTART_BLUETOOTH_SERVICE)
                || isBinding()
                || (token != ON_SWITCH_USER_TOKEN && mNextUser != null)) {
            Log.d(
                    TAG,
                    "Busy reason:"
                            + " HANDLE_DISABLE_DELAYED="
                            + mHandler.hasMessages(MESSAGE_HANDLE_DISABLE_DELAYED)
                            + " RESTART_BLUETOOTH_SERVICE="
                            + mHandler.hasMessages(MESSAGE_RESTART_BLUETOOTH_SERVICE)
                            + (" isBinding=" + isBinding())
                            + (" mNextUser=" + mNextUser));
            // Bluetooth is restarting
            return SERVICE_RESTART_TIME_MS;
        }
        return 0;
    }

    private void delayModeChangedIfNeeded(Object token, Runnable r, String modeChanged) {
        final int delayMs = estimateBusyTime(token);
        Log.d(
                TAG,
                ("delayModeChangedIfNeeded(" + modeChanged + "):")
                        + (" state=" + mState)
                        + (" Airplane.isOnOverrode=" + AirplaneModeListener.isOnOverrode())
                        + (" Airplane.isOn=" + AirplaneModeListener.isOn())
                        + (" isSatelliteModeOn()=" + isSatelliteModeOn())
                        + (" delayed=" + delayMs + "ms"));

        mHandler.removeCallbacksAndMessages(token);

        if (delayMs > 0) {
            mHandler.postDelayed(
                    () -> delayModeChangedIfNeeded(token, r, modeChanged), token, delayMs);
        } else {
            r.run();
        }
    }

    /** Send Intent to the Notification Service in the Bluetooth app */
    Unit sendToggleNotification(String notificationReason) {
        Intent intent =
                new Intent("android.bluetooth.notification.action.SEND_TOGGLE_NOTIFICATION");
        if (Flags.userRestrictionRefactor()) {
            intent.setComponent(
                    new ComponentName(
                            mBluetoothComponent.getPackageName(),
                            "com.android.bluetooth.notification.NotificationHelperService"));
        } else {
            intent.setComponent(resolveSystemService(intent));
        }
        intent.putExtra(
                "android.bluetooth.notification.extra.NOTIFICATION_REASON", notificationReason);
        mUserContext.startService(intent);
        return Unit.INSTANCE;
    }

    private static final Object ON_AIRPLANE_MODE_CHANGED_TOKEN = new Object();
    private static final Object ON_SATELLITE_MODE_CHANGED_TOKEN = new Object();
    private static final Object ON_SWITCH_USER_TOKEN = new Object();

    @VisibleForTesting
    Unit onAirplaneModeChanged(boolean isAirplaneModeOn) {
        delayModeChangedIfNeeded(
                ON_AIRPLANE_MODE_CHANGED_TOKEN,
                () -> handleAirplaneModeChanged(isAirplaneModeOn),
                "onAirplaneModeChanged");
        return Unit.INSTANCE;
    }

    @VisibleForTesting
    Unit onSatelliteModeChanged(boolean isSatelliteModeOn) {
        delayModeChangedIfNeeded(
                ON_SATELLITE_MODE_CHANGED_TOKEN,
                () -> handleSatelliteModeChanged(isSatelliteModeOn),
                "onSatelliteModeChanged");
        return Unit.INSTANCE;
    }

    // Call is coming from the systemServer main thread and need to be post to avoid race
    void onUserSwitching(UserHandle userHandle) {
        Log.d(TAG, "onUserSwitching(" + userHandle + ")");
        mNextUser = userHandle;
        delayModeChangedIfNeeded(ON_SWITCH_USER_TOKEN, () -> handleSwitchUser(), "onUserSwitching");
    }

    private void forceToOffFromModeChange(int currentState, int reason) {
        // Clear registered LE apps to force shut-off
        mBleAppManager.clearBleApps();

        if (reason == ENABLE_DISABLE_REASON_SATELLITE_MODE
                || !AirplaneModeListener.hasUserToggledApm(mUserContext)) {
            // AirplaneMode can have a state where it does not impact AutoOn
            if (mAutoOn != null) {
                mAutoOn.pause();
            }
        }

        if (currentState == State.ON) {
            mEnable = false;
            mActiveLogs.add(reason, false);
            onToBleOn();
        } else if (currentState == State.BLE_ON) {
            mEnable = false;
            mEnableExternal = false;
            mActiveLogs.add(reason, false);
            bleOnToOff();
        } else if (currentState == State.BLE_TURNING_ON) {
            mEnable = false;
            mActiveLogs.add(reason, false);
            bleTurningOnToOff();
        }
    }

    private void handleAirplaneModeChanged(boolean isAirplaneModeOn) {
        boolean isPersistStateOn = isBluetoothPersistedStateOn();
        if (isPersistStateOn) {
            if (isAirplaneModeOn) {
                setBluetoothPersistedState(BLUETOOTH_ON_AIRPLANE);
            } else {
                setBluetoothPersistedState(BLUETOOTH_ON_BLUETOOTH);
            }
        }

        int currentState = mState.get();

        Log.d(
                TAG,
                ("handleAirplaneModeChanged(" + isAirplaneModeOn + "):")
                        + (" mEnableExternal=" + mEnableExternal)
                        + (" isPersistStateOn=" + isPersistStateOn)
                        + (" currentState=" + State.$.toString(currentState)));

        if (isAirplaneModeOn) {
            forceToOffFromModeChange(currentState, ENABLE_DISABLE_REASON_AIRPLANE_MODE);
        } else if (mEnableExternal && currentState != State.ON && isPersistStateOn) {
            // isPersistStateOn is checked to prevent race with RESTORE_USER_SETTING
            sendEnableMsg(mQuietEnableExternal, ENABLE_DISABLE_REASON_AIRPLANE_MODE);
        } else if (currentState != State.ON) {
            autoOnSetupTimer();
        }
    }

    private void handleSatelliteModeChanged(boolean isSatelliteModeOn) {
        final int currentState = mState.get();

        if (shouldBluetoothBeOn(isSatelliteModeOn) && currentState != State.ON) {
            sendEnableMsg(mQuietEnableExternal, ENABLE_DISABLE_REASON_SATELLITE_MODE);
        } else if (!shouldBluetoothBeOn(isSatelliteModeOn) && currentState != State.OFF) {
            forceToOffFromModeChange(currentState, ENABLE_DISABLE_REASON_SATELLITE_MODE);
        } else if (!isSatelliteModeOn
                && !shouldBluetoothBeOn(isSatelliteModeOn)
                && currentState != State.ON) {
            autoOnSetupTimer();
        }
    }

    private boolean shouldBluetoothBeOn(boolean isSatelliteModeOn) {
        if (!isBluetoothPersistedStateOn()) {
            Log.d(TAG, "shouldBluetoothBeOn: User want BT off.");
            return false;
        }

        if (isSatelliteModeOn) {
            Log.d(TAG, "shouldBluetoothBeOn: BT should be off as satellite mode is on.");
            return false;
        }

        if (AirplaneModeListener.isOnOverrode() && isBluetoothPersistedStateOnAirplane()) {
            Log.d(TAG, "shouldBluetoothBeOn: BT should be off as airplaneMode is on.");
            return false;
        }

        Log.d(TAG, "shouldBluetoothBeOn: BT should be on.");
        return true;
    }

    private final BroadcastReceiver mReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (Intent.ACTION_SETTING_RESTORED.equals(action)) {
                        final String name = intent.getStringExtra(Intent.EXTRA_SETTING_NAME);
                        if (Settings.Global.BLUETOOTH_ON.equals(name)) {
                            // The Bluetooth On state may be changed during system restore.
                            final String prevValue =
                                    intent.getStringExtra(Intent.EXTRA_SETTING_PREVIOUS_VALUE);
                            final String newValue =
                                    intent.getStringExtra(Intent.EXTRA_SETTING_NEW_VALUE);

                            Log.d(
                                    TAG,
                                    "ACTION_SETTING_RESTORED with BLUETOOTH_ON"
                                            + (" prevValue=" + prevValue)
                                            + (" newValue=" + newValue));

                            if ((newValue == null)
                                    || (prevValue == null)
                                    || prevValue.equals(newValue)) {
                                return;
                            }
                            mHandler.sendEmptyMessage(
                                    newValue.equals("0")
                                            ? MESSAGE_RESTORE_USER_SETTING_OFF
                                            : MESSAGE_RESTORE_USER_SETTING_ON);
                        }
                    } else if (action.equals(Intent.ACTION_SHUTDOWN)) {
                        Log.i(TAG, "Device is shutting down.");
                        mShutdownInProgress = true;
                        mEnable = false;
                        mEnableExternal = false;
                        if (mState.oneOf(State.BLE_ON)) {
                            bleOnToOff();
                        } else if (mState.oneOf(State.ON)) {
                            onToBleOn();
                        }
                    }
                }
            };

    private final BluetoothManagerServiceApi mApi = new Api();

    BluetoothManagerService(
            @NonNull Context context,
            @NonNull Looper looper,
            @NonNull String hciInstanceName,
            @Nullable BluetoothComponent bluetoothComponent) {
        mContext = requireNonNull(context, "Context cannot be null");
        mContentResolver = requireNonNull(mContext.getContentResolver(), "Resolver cannot be null");
        mLooper = requireNonNull(looper, "Looper cannot be null");
        mHciInstanceName = requireNonNull(hciInstanceName, "Hci instance name cannot be null");
        mBluetoothComponent = bluetoothComponent;
        mActiveLogs = new ActiveLogs();

        mUserManager =
                requireNonNull(
                        mContext.getSystemService(UserManager.class),
                        "UserManager system service cannot be null");

        mHandler = new BluetoothHandler(mLooper);
        mBleAppManager = new BleAppManager(mLooper, this::bleOnToOffIfNeeded);

        // Observe BLE scan only mode settings change.
        BleScanSettingListener.initialize(mLooper, mContentResolver, this::onBleScanDisabled);

        // Disable ASHA if BLE is not supported, overriding any system property
        if (!isBleSupported(mContext)) {
            mIsHearingAidProfileSupported = false;
        } else {
            // ASHA default value is:
            //   * disabled on Automotive, TV, and Watch.
            //   * enabled for other form factor
            // This default value can be overridden with a system property
            final boolean isAshaEnabledByDefault =
                    !(isAutomotive(mContext) || isWatch(mContext) || isTv(mContext));
            mIsHearingAidProfileSupported =
                    BluetoothProperties.isProfileAshaCentralEnabled()
                            .orElse(isAshaEnabledByDefault);
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SETTING_RESTORED);
        filter.addAction(Intent.ACTION_SHUTDOWN);
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        mContext.registerReceiver(mReceiver, filter, null, mHandler);

        IntentFilter filterUser = new IntentFilter();
        if (!Flags.userRestrictionRefactor()) {
            filterUser.addAction(UserManager.ACTION_USER_RESTRICTIONS_CHANGED);
        }
        filterUser.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        if (!Flags.userRestrictionRefactor()) {
            mContext.registerReceiverForAllUsers(
                    new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent intent) {
                            switch (intent.getAction()) {
                                case UserManager.ACTION_USER_RESTRICTIONS_CHANGED -> {
                                    onUserRestrictionsChanged(getSendingUser());
                                }
                                default -> {
                                    Log.e(
                                            TAG,
                                            "Unknown broadcast received in BluetoothManagerService"
                                                    + " receiver registered across all users");
                                }
                            }
                        }
                    },
                    filterUser,
                    null,
                    mHandler);
        }

        mName =
                BluetoothServerProxy.getInstance()
                        .settingsSecureGetString(mContentResolver, Settings.Secure.BLUETOOTH_NAME);
        mAddress =
                BluetoothServerProxy.getInstance()
                        .settingsSecureGetString(
                                mContentResolver, Settings.Secure.BLUETOOTH_ADDRESS);
        Log.d(
                TAG,
                "Local adapter: Name="
                        + mName
                        + ", Address="
                        + Log.address(mAddress)
                        + " HciInstanceName="
                        + mHciInstanceName);

        if (isBluetoothPersistedStateOn()) {
            Log.i(TAG, "Startup: Bluetooth persisted state is ON.");
            mEnableExternal = true;
        }

        mConfigAllowAutoOn =
                SystemProperties.getBoolean("bluetooth.server.automatic_turn_on", false);
        Log.d(TAG, "AutoOn allowed by config=" + mConfigAllowAutoOn);
    }

    private class Api implements BluetoothManagerServiceApi {
        private void enforceCorrectThread() {
            if (mLooper == Looper.myLooper()) {
                return;
            }
            throw new IllegalThreadStateException("Must be called on BluetoothSystemServer looper");
        }

        @Override
        public IBinder registerAdapter(IBluetoothManagerCallback callback) {
            enforceCorrectThread();
            return BluetoothManagerService.this.registerAdapter(callback);
        }

        @Override
        public void unregisterAdapter(IBluetoothManagerCallback callback) {
            enforceCorrectThread();
            BluetoothManagerService.this.unregisterAdapter(callback);
        }

        @Override
        public int getState() {
            // This method is designed to work concurrently
            return BluetoothManagerService.this.getState();
        }

        @Override
        public boolean waitForState(int state) {
            // This method is designed to work concurrently
            return BluetoothManagerService.this.waitForState(state);
        }

        @Override
        public String getAddress() {
            enforceCorrectThread();
            return BluetoothManagerService.this.getAddress();
        }

        @Override
        public String getName() {
            enforceCorrectThread();
            return BluetoothManagerService.this.getName();
        }

        @Override
        public boolean isBleScanAvailable() {
            enforceCorrectThread();
            return BluetoothManagerService.this.isBleScanAvailable();
        }

        @Override
        public boolean isHearingAidProfileSupported() {
            enforceCorrectThread();
            return BluetoothManagerService.this.isHearingAidProfileSupported();
        }

        @Override
        public boolean enable(int reason, String packageName) {
            enforceCorrectThread();
            return BluetoothManagerService.this.enable(reason, packageName);
        }

        @Override
        public boolean enableBle(String packageName, IBinder token) {
            enforceCorrectThread();
            return BluetoothManagerService.this.enableBle(packageName, token);
        }

        @Override
        public boolean enableNoAutoConnect(String packageName) {
            enforceCorrectThread();
            return BluetoothManagerService.this.enableNoAutoConnect(packageName);
        }

        @Override
        public boolean disable(String packageName, boolean persist) {
            enforceCorrectThread();
            return BluetoothManagerService.this.disable(packageName, persist);
        }

        @Override
        public boolean disableBle(String packageName, IBinder token) {
            enforceCorrectThread();
            return BluetoothManagerService.this.disableBle(packageName, token);
        }

        @Override
        public boolean factoryReset(int count) {
            enforceCorrectThread();
            return BluetoothManagerService.this.factoryReset(count);
        }

        @Override
        public int setBtHciSnoopLogMode(int mode) {
            enforceCorrectThread();
            return BluetoothManagerService.this.setBtHciSnoopLogMode(mode);
        }

        @Override
        public int getBtHciSnoopLogMode() {
            enforceCorrectThread();
            return BluetoothManagerService.this.getBtHciSnoopLogMode();
        }

        @Override
        public boolean isAutoOnSupported() {
            enforceCorrectThread();
            return BluetoothManagerService.this.isAutoOnSupported();
        }

        @Override
        public boolean isAutoOnEnabled() {
            enforceCorrectThread();
            return BluetoothManagerService.this.isAutoOnEnabled();
        }

        @Override
        public void setAutoOnEnabled(boolean status) {
            enforceCorrectThread();
            BluetoothManagerService.this.setAutoOnEnabled(status);
        }

        @Override
        public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
            enforceCorrectThread();
            BluetoothManagerService.this.dump(fd, writer, args);
        }

        @Override
        public void onUserRestrictionsChanged(UserHandle userHandle) {
            enforceCorrectThread();
            BluetoothManagerService.this.onUserRestrictionsChanged(userHandle);
        }

        @Override
        public void onBleScanDisabled() {
            enforceCorrectThread();
            BluetoothManagerService.this.onBleScanDisabled();
        }
    }

    BluetoothManagerServiceApi getApi() {
        return mApi;
    }

    Unit onBluetoothDisallowed() {
        if (mState.oneOf(State.OFF)) {
            return Unit.INSTANCE;
        }

        Log.i(TAG, "onBluetoothDisallowed: Shutting down");

        mBleAppManager.clearBleApps();

        mEnable = false;
        mEnableExternal = false;
        mActiveLogs.add(ENABLE_DISABLE_REASON_DISALLOWED, false);

        if (mState.oneOf(State.BLE_ON)) {
            bleOnToOff();
        } else if (mState.oneOf(State.ON)) {
            onToBleOn();
        }
        return Unit.INSTANCE;
    }

    @VisibleForTesting
    Unit onBleScanDisabled() {
        if (mState.oneOf(State.OFF, State.BLE_TURNING_OFF)) {
            Log.i(TAG, "onBleScanDisabled: Nothing to do, Bluetooth is already turning off");
            return Unit.INSTANCE;
        }
        mBleAppManager.clearBleApps();

        if (mState.oneOf(State.BLE_ON)) {
            Log.i(TAG, "onBleScanDisabled: Shutting down BLE_ON mode");
            mEnable = false;
            bleOnToOff();
        } else {
            Log.i(TAG, "onBleScanDisabled: Bluetooth is not in BLE_ON, staying on");
        }
        return Unit.INSTANCE;
    }

    /** Returns true if satellite mode is turned on. */
    private static boolean isSatelliteModeOn() {
        return SatelliteModeListener.isOn();
    }

    /** Returns true if the Bluetooth saved state is "on" */
    private boolean isBluetoothPersistedStateOn() {
        final int state =
                BluetoothServerProxy.getInstance()
                        .getBluetoothPersistedState(mContentResolver, BLUETOOTH_ON_BLUETOOTH);
        Log.d(TAG, "isBluetoothPersistedStateOn: " + state);
        return state != BLUETOOTH_OFF;
    }

    private boolean isBluetoothPersistedStateOnAirplane() {
        final int state =
                BluetoothServerProxy.getInstance()
                        .getBluetoothPersistedState(mContentResolver, BLUETOOTH_ON_BLUETOOTH);
        Log.d(TAG, "isBluetoothPersistedStateOnAirplane: " + state);
        return state == BLUETOOTH_ON_AIRPLANE;
    }

    /** Returns true if the Bluetooth saved state is BLUETOOTH_ON_BLUETOOTH */
    private boolean isBluetoothPersistedStateOnBluetooth() {
        final int state =
                BluetoothServerProxy.getInstance()
                        .getBluetoothPersistedState(mContentResolver, BLUETOOTH_ON_BLUETOOTH);
        Log.d(TAG, "isBluetoothPersistedStateOnBluetooth: " + state);
        return state == BLUETOOTH_ON_BLUETOOTH;
    }

    private void setBluetoothPersistedState(int state) {
        BluetoothServerProxy.getInstance().setBluetoothPersistedState(mContentResolver, state);
    }

    IBinder registerAdapter(IBluetoothManagerCallback callback) {
        mCallbacks.register(callback);
        if (mAdapter == null) {
            return null;
        }
        return mAdapter.getAdapterServiceBinder();
    }

    void unregisterAdapter(IBluetoothManagerCallback callback) {
        mCallbacks.unregister(callback);
    }

    boolean isEnabled() {
        return getState() == State.ON;
    }

    int getState() {
        return mState.get();
    }

    boolean isBleScanAvailable() {
        if (mUser == null) {
            return false;
        }
        if (AirplaneModeListener.isOn() && !mEnable) {
            return false;
        }
        if (SatelliteModeListener.isOn()) {
            return false;
        }
        return BleScanSettingListener.isScanAllowed();
    }

    boolean isHearingAidProfileSupported() {
        return mIsHearingAidProfileSupported;
    }

    Context getUserContext() {
        return mUserContext;
    }

    boolean enableBle(String packageName, IBinder token) {
        Log.i(
                TAG,
                ("enableBle(" + packageName + ", " + token + "):")
                        + (" mAdapter=" + mAdapter)
                        + (" isBinding=" + isBinding())
                        + (" mState=" + mState));

        if (AirplaneModeListener.isOn() && !mEnable) {
            Log.d(TAG, "enableBle: not enabling - Airplane mode is ON on system");
            return false;
        }

        if (isSatelliteModeOn()) {
            Log.d(TAG, "enableBle: not enabling - Satellite mode is on.");
            return false;
        }

        if (mUser == null) {
            Log.e(TAG, "enableBle: No user found to enable for");
            return false;
        }

        if (mNextUser != null) {
            Log.d(TAG, "enableBle: user switch in progress");
            return false;
        }

        if (!BleScanSettingListener.isScanAllowed()) {
            Log.d(TAG, "enableBle: not enabling - Scan mode is not allowed.");
            return false;
        }

        if (!mBleAppManager.addBleApp(token, packageName)) {
            Log.w(TAG, "enableBle: could not monitor app " + packageName + ", it is already dead");
            return false;
        }

        if (mState.oneOf(
                State.ON,
                State.BLE_ON,
                State.TURNING_ON,
                State.TURNING_OFF,
                State.BLE_TURNING_ON)) {
            Log.i(TAG, "enableBle: Bluetooth is already in state " + mState);
            return true;
        }
        sendEnableMsg(false, ENABLE_DISABLE_REASON_APPLICATION_REQUEST, packageName, true);
        return true;
    }

    boolean disableBle(String packageName, IBinder token) {
        Log.i(
                TAG,
                ("disableBle(" + packageName + ", " + token + "):")
                        + (" mAdapter=" + mAdapter)
                        + (" isBinding=" + isBinding())
                        + (" mState=" + mState));

        if (mState.oneOf(State.OFF)) {
            Log.i(TAG, "disableBle: Already disabled");
            return false;
        }

        mBleAppManager.removeBleApp(ENABLE_DISABLE_REASON_APPLICATION_REQUEST, token, packageName);
        return true;
    }

    private Unit bleOnToOffIfNeeded(int reason, String packageName) {
        if (!mState.oneOf(State.BLE_ON)) {
            Log.d(TAG, "bleOnToOffIfNeeded: Incorrect state=" + mState);
            return Unit.INSTANCE;
        }
        mEnable = false;
        mActiveLogs.add(reason, false, packageName, true);
        bleOnToOff();
        return Unit.INSTANCE;
    }

    /**
     * Will call startBrEdr() if bluetooth classic should be on and will call stopBle if bluetooth
     * BLE should be off
     */
    private void continueFromBleOnState() {
        if (!mState.oneOf(State.BLE_ON)) {
            Log.e(TAG, "continueFromBleOnState: Impossible transition from " + mState);
            return;
        }
        if (!mEnableExternal && !mBleAppManager.isBleAppPresent()) {
            // TODO(b/262605980): this code is unlikely to be trigger and will never be once
            // enableBle & disableBle are executed on the handler
            Log.i(TAG, "continueFromBleOnState: Disabled while enabling BLE, disable BLE now");
            mEnable = false;
            bleOnToOff();
            return;
        }
        if (isBluetoothPersistedStateOnBluetooth() || !mBleAppManager.isBleAppPresent()) {
            Log.i(TAG, "continueFromBleOnState: Starting br edr");
            // This triggers transition to State.ON
            bleOnToOn();
            setBluetoothPersistedState(BLUETOOTH_ON_BLUETOOTH);
        } else {
            Log.i(TAG, "continueFromBleOnState: Staying in BLE_ON");
        }
    }

    /**
     * Inform BluetoothAdapter instances that BREDR part is down and turn off all service and stack
     * if no LE app needs it
     */
    private void sendBrEdrDownCallback() {
        if (mAdapter == null) {
            Log.d(TAG, "sendBrEdrDownCallback: mAdapter is null");
            return;
        }
        if (Flags.onToBleOnViaOff()) {
            Log.i(TAG, "sendBrEdrDownCallback: onToBleOnViaOff is on, going to OFF");
            bleOnToOff();
            return;
        }
        if (BleScanSettingListener.isScanAllowed()
                && !AirplaneModeListener.isOn()
                && mBleAppManager.isBleAppPresent()) {
            // Need to stay at BLE ON. Disconnect all Gatt connections
            Log.i(TAG, "sendBrEdrDownCallback: Staying in BLE_ON");
            try {
                mAdapter.unregAllGattClient();
            } catch (RemoteException e) {
                Log.e(TAG, "sendBrEdrDownCallback: failed to call unregAllGattClient()", e);
            }
        } else {
            Log.i(TAG, "sendBrEdrDownCallback: Stopping ble");
            bleOnToOff();
        }
    }

    private Unit enableFromAutoOn() {
        if (isBluetoothDisallowed()) {
            Log.d(TAG, "Bluetooth is not allowed, preventing AutoOn");
            return Unit.INSTANCE;
        }
        sendToggleNotification("auto_on_bt_enabled_notification");
        enable(ENABLE_DISABLE_REASON_AUTO_ON, mContext.getPackageName());
        return Unit.INSTANCE;
    }

    boolean enableNoAutoConnect(String packageName) {
        if (isSatelliteModeOn()) {
            Log.d(TAG, "enableNoAutoConnect(" + packageName + "): Blocked by satellite mode");
            return false;
        }

        if (mUser == null) {
            Log.e(TAG, "enableNoAutoConnect: No user found to enable for");
            return false;
        }

        if (mNextUser != null) {
            Log.d(TAG, "enableNoAutoConnect: user switch in progress");
            return false;
        }

        mQuietEnableExternal = true;
        mEnableExternal = true;
        sendEnableMsg(true, ENABLE_DISABLE_REASON_APPLICATION_REQUEST, packageName);
        return true;
    }

    boolean enable(int reason, String packageName) {
        Log.d(
                TAG,
                ("enable(" + packageName + "):")
                        + (" mAdapter=" + mAdapter)
                        + (" isBinding=" + isBinding())
                        + (" mState=" + mState));

        if (isSatelliteModeOn()) {
            Log.d(TAG, "enable: not enabling - satellite mode is on.");
            return false;
        }

        if (mUser == null) {
            Log.e(TAG, "enable: No user found to enable for");
            return false;
        }

        if (mNextUser != null) {
            Log.d(TAG, "enable: user switch in progress");
            return false;
        }

        mQuietEnableExternal = false;
        mEnableExternal = true;
        AirplaneModeListener.notifyUserToggledBluetooth(mContentResolver, mUserContext, true);
        sendEnableMsg(false, reason, packageName);
        return true;
    }

    boolean disable(String packageName, boolean persist) {
        Log.d(
                TAG,
                ("disable(" + packageName + ", " + persist + "):")
                        + (" mAdapter=" + mAdapter)
                        + (" isBinding=" + isBinding())
                        + (" mState=" + mState));

        AirplaneModeListener.notifyUserToggledBluetooth(mContentResolver, mUserContext, false);

        if (persist) {
            setBluetoothPersistedState(BLUETOOTH_OFF);
        }
        mEnableExternal = false;
        sendDisableMsg(ENABLE_DISABLE_REASON_APPLICATION_REQUEST, packageName);
        return true;
    }

    private static CompletableFuture<Void> createDeathNotifier(IBinder binder) {
        CompletableFuture<Void> deathNotifier = new CompletableFuture<>();
        try {
            binder.linkToDeath(
                    () -> {
                        Log.i(TAG, "Successfully received Bluetooth death");
                        deathNotifier.complete(null);
                    },
                    0);
        } catch (RemoteException e) {
            Log.e(TAG, "listenBinderDeath(): Failed to linkToDeath", e);
            deathNotifier.complete(null);
        }
        return deathNotifier;
    }

    private static void killBluetoothProcess(
            AdapterBinder adapter, CompletableFuture<Void> deathNotifier) {
        try {
            // Force kill Bluetooth to make sure its process is not reused.
            // Note: In a perfect world, we should be able to re-init the same process.
            // Unfortunately, this requires an heavy rework of the Bluetooth app
            // TODO: b/339501753 - Properly stop Bluetooth without killing it
            adapter.killBluetoothProcess();

            deathNotifier.get(2_000, TimeUnit.MILLISECONDS);
        } catch (android.os.DeadObjectException e) {
            // Reduce exception to info and skip waiting (Bluetooth is dead as wanted)
            Log.i(TAG, "killBluetoothProcess(): Bluetooth already dead 💀");
        } catch (RemoteException e) {
            Log.e(TAG, "killBluetoothProcess(): Unable to call killBluetoothProcess", e);
        } catch (TimeoutException | InterruptedException | ExecutionException e) {
            Log.e(TAG, "killBluetoothProcess(): Bluetooth death not received after > 2000ms", e);
        }
    }

    void unbindAndFinish() {
        Log.d(TAG, "unbindAndFinish(): mAdapter=" + mAdapter + " isBinding=" + isBinding());

        mHandler.removeMessages(MESSAGE_BLUETOOTH_STATE_CHANGE);
        if (mAdapter == null) {
            // mAdapter can be null when Bluetooth crashed and sent SERVICE_DISCONNECTED
            return;
        }

        try {
            mAdapter.unregisterCallback(mBluetoothCallback);
        } catch (RemoteException e) {
            Log.e(TAG, "unbindAndFinish(): Unable to unregister BluetoothCallback", e);
        }

        CompletableFuture<Void> deathNotifier =
                createDeathNotifier(mAdapter.getAdapterBinder().asBinder());

        // Unbind first to avoid receiving unwanted "onServiceDisconnected"
        mContext.unbindService(mConnection);

        killBluetoothProcess(mAdapter, deathNotifier);

        // TODO: b/356931756 - Remove sleep
        Log.d(TAG, "Force sleep 100 ms for propagating Bluetooth app death");
        SystemClock.sleep(100); // required to let the ActivityManager be notified of BT death

        mAdapter = null; // Don't call resetAdapter as we already call unbindService
        mHandler.removeMessages(MESSAGE_TIMEOUT_BIND);
    }

    /**
     * Send enable message and set adapter name and address. Called when the boot phase becomes
     * PHASE_SYSTEM_SERVICES_READY.
     */
    void handleOnBootPhase(UserHandle userHandle) {
        mUser = userHandle;
        mUserContext = mContext.createContextAsUser(userHandle, 0);

        if (mConfigAllowAutoOn) {
            mAutoOn = new AutoOn(mLooper, mUserContext, mUser, mState, this::enableFromAutoOn);
        }

        AirplaneModeListener.initialize(
                mLooper,
                mContentResolver,
                mState,
                this::onAirplaneModeChanged,
                this::sendToggleNotification,
                this::getUserContext,
                TimeSource.Monotonic.INSTANCE);

        SatelliteModeListener.initialize(mLooper, mContentResolver, this::onSatelliteModeChanged);

        if (Flags.userRestrictionRefactor()) {
            mSharingRestriction =
                    new SharingRestriction(mUserContext, mLooper, mBluetoothComponent, mUser);
        }

        if (isBluetoothDisallowed()) {
            Log.i(TAG, "handleOnBootPhase: Bluetooth is disallowed");
            return;
        }
        if (mContext.getPackageManager().isSafeMode()) {
            Log.i(TAG, "handleOnBootPhase: SafeMode prevent auto-enabling of Bluetooth");
            return;
        }
        if (!mEnableExternal || !isBluetoothPersistedStateOnBluetooth()) {
            Log.i(TAG, "handleOnBootPhase: Bluetooth not started");
            autoOnSetupTimer();
            return;
        }
        Log.i(TAG, "handleOnBootPhase: Auto-enabling Bluetooth for " + mUser);
        sendEnableMsg(mQuietEnableExternal, ENABLE_DISABLE_REASON_SYSTEM_BOOT);
    }

    /** Called when switching to a different foreground user. */
    @VisibleForTesting
    void handleSwitchUser() {
        Log.d(TAG, "handleSwitchUser()");
        if (mUser.equals(mNextUser)) {
            Log.d(TAG, "Skip fast switch on same user=" + mUser);
            return;
        }

        if (mAutoOn != null) {
            mAutoOn.pause();
            mAutoOn = null;
            Log.d(TAG, "Stopping AutoOn for" + mUser);
        }

        if (Flags.userRestrictionRefactor()) {
            mSharingRestriction.stop();
        }

        if (mState.oneOf(State.OFF)) {
            executeUserSwitch();
        } else {
            prepareUserSwitch();
        }
    }

    @FunctionalInterface
    public interface RemoteExceptionConsumer<T> {
        void accept(T t) throws RemoteException;
    }

    private void broadcastToAdapters(
            String logAction, RemoteExceptionConsumer<IBluetoothManagerCallback> action) {
        final int itemCount = mCallbacks.beginBroadcast();
        Log.d(TAG, "Broadcasting " + logAction + "() to " + itemCount + " receivers.");
        for (int i = 0; i < itemCount; i++) {
            try {
                action.accept(mCallbacks.getBroadcastItem(i));
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException while calling " + logAction + "()#" + i, e);
            }
        }
        mCallbacks.finishBroadcast();
    }

    private void sendBluetoothOnCallback() {
        broadcastToAdapters("sendBluetoothOnCallback", IBluetoothManagerCallback::onBluetoothOn);
    }

    private void sendBluetoothOffCallback() {
        broadcastToAdapters("sendBluetoothOffCallback", IBluetoothManagerCallback::onBluetoothOff);
    }

    private void sendBluetoothServiceDownCallback() {
        broadcastToAdapters(
                "sendBluetoothServiceDownCallback",
                IBluetoothManagerCallback::onBluetoothServiceDown);
    }

    // Called from unsafe binder thread
    String getAddress() {
        return mAddress;
    }

    // Called from unsafe binder thread
    String getName() {
        return mName;
    }

    @VisibleForTesting
    class BluetoothServiceConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            String name = componentName.getClassName();
            Log.d(TAG, "ServiceConnection.onServiceConnected(" + name + ", " + service + ")");
            if (!name.equals("com.android.bluetooth.btservice.AdapterService")) {
                Log.e(TAG, "Unknown service connected: " + name);
                return;
            }
            mHandler.obtainMessage(MESSAGE_BLUETOOTH_SERVICE_CONNECTED, service).sendToTarget();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            // Called if we unexpectedly disconnect.
            String name = componentName.getClassName();
            Log.d(TAG, "ServiceConnection.onServiceDisconnected(" + name + ")");
            if (!name.equals("com.android.bluetooth.btservice.AdapterService")) {
                Log.e(TAG, "Unknown service disconnected: " + name);
                return;
            }

            if (Flags.setComponentAvailableFix()) {
                mHandler.obtainMessage(
                                MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED,
                                componentName.getPackageName())
                        .sendToTarget();
            } else {
                mHandler.sendEmptyMessage(MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED);
            }
        }

        @Override
        public void onBindingDied(ComponentName componentName) {
            String name = componentName.getClassName();
            Log.wtf(TAG, "ServiceConnection.onBindingDied(" + name + ")");
        }

        @Override
        public void onNullBinding(ComponentName componentName) {
            String name = componentName.getClassName();
            Log.wtf(TAG, "ServiceConnection.onNullBinding(" + name + ")");
        }
    }

    private final BluetoothServiceConnection mConnection = new BluetoothServiceConnection();
    private int mWaitForDisableRetry;

    @VisibleForTesting
    class BluetoothHandler extends Handler {
        BluetoothHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_HANDLE_DISABLE_DELAYED -> {
                    if (Flags.gracefulDisableWithoutMessage()) {
                        throw new IllegalStateException("gracefulDisableWithoutMessage is enabled");
                    }
                    Log.d(TAG, "MESSAGE_HANDLE_DISABLE_DELAYED: mAdapter=" + mAdapter);

                    handleDisableDelayed();
                }
                case MESSAGE_RESTORE_USER_SETTING_OFF -> {
                    if (!mEnable) {
                        Log.w(TAG, "RESTORE_USER_SETTING_OFF: Unhandled: already disabled");
                        break;
                    }
                    Log.d(TAG, "RESTORE_USER_SETTING_OFF: set Bluetooth state to disabled");
                    setBluetoothPersistedState(BLUETOOTH_OFF);
                    mEnableExternal = false;
                    sendDisableMsg(ENABLE_DISABLE_REASON_RESTORE_USER_SETTING);
                }
                case MESSAGE_RESTORE_USER_SETTING_ON -> {
                    if (mEnable) {
                        Log.w(TAG, "RESTORE_USER_SETTING_ON: Unhandled: already enabled");
                        break;
                    }
                    Log.d(TAG, "RESTORE_USER_SETTING_ON: set Bluetooth state to enabled");
                    mQuietEnableExternal = false;
                    mEnableExternal = true;
                    sendEnableMsg(false, ENABLE_DISABLE_REASON_RESTORE_USER_SETTING);
                }
                case MESSAGE_BLUETOOTH_SERVICE_CONNECTED -> {
                    IBinder service = (IBinder) msg.obj;

                    // Handle case where disable was called before binding complete.
                    if (!isBinding() && !mEnable) {
                        Log.d(TAG, "MESSAGE_BLUETOOTH_SERVICE_CONNECTED: after cancelling binding");
                        AdapterBinder adapter =
                                BluetoothServerProxy.getInstance().createAdapterBinder(service);
                        killBluetoothProcess(adapter, createDeathNotifier(service));
                        break;
                    }
                    Log.d(TAG, "MESSAGE_BLUETOOTH_SERVICE_CONNECTED: service=" + service);

                    // Remove timeout
                    mHandler.removeMessages(MESSAGE_TIMEOUT_BIND);

                    mAdapter = BluetoothServerProxy.getInstance().createAdapterBinder(service);

                    try {
                        mAdapter.registerCallback(mBluetoothCallback);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Unable to register BluetoothCallback", e);
                    }

                    propagateOffToBleOn(mHciInstanceName);
                }
                case MESSAGE_BLUETOOTH_STATE_CHANGE -> {
                    int prevState = msg.arg1;
                    int newState = msg.arg2;
                    Log.d(
                            TAG,
                            "MESSAGE_BLUETOOTH_STATE_CHANGE:"
                                    + (" prevState=" + State.$.toString(prevState))
                                    + (" newState=" + State.$.toString(newState)));
                    if (mAdapter == null) {
                        Log.e(TAG, "State change received after bluetooth has crashed");
                        break;
                    }
                    bluetoothStateChangeHandler(prevState, newState);
                    // handle error state transition case from TURNING_ON to OFF
                    // unbind and rebind bluetooth service and enable bluetooth
                    if ((prevState == State.BLE_TURNING_ON) && (newState == State.OFF) && mEnable) {
                        recoverBluetoothServiceFromError(false);
                    }
                    if ((prevState == State.TURNING_ON) && (newState == State.BLE_ON) && mEnable) {
                        recoverBluetoothServiceFromError(true);
                    }
                    // If we tried to enable BT while BT was in the process of shutting down,
                    // wait for the BT process to fully tear down and then force a restart
                    // here. This is a bit of a hack (b/29363429).
                    if (prevState == State.BLE_TURNING_OFF && newState == State.OFF) {
                        if (mHandler.hasMessages(0, ON_AIRPLANE_MODE_CHANGED_TOKEN)) {
                            mHandler.removeCallbacksAndMessages(ON_AIRPLANE_MODE_CHANGED_TOKEN);
                            Log.d(TAG, "Handling delayed airplane mode event");
                            handleAirplaneModeChanged(AirplaneModeListener.isOnOverrode());
                        }
                        // When performing FactoryReset, we currently depend on this to restart
                        if (mEnable && !isBinding()) {
                            Log.d(TAG, "Entering State.OFF but mEnable is true; restarting.");
                            handleRestartMessage();
                        }
                    }
                    if (newState == State.ON || newState == State.BLE_ON) {
                        // bluetooth is working, reset the counter
                        if (mErrorRecoveryRetryCounter != 0) {
                            Log.w(TAG, "bluetooth is recovered from error");
                            mErrorRecoveryRetryCounter = 0;
                        }
                    }
                }
                case MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED -> {
                    Log.e(TAG, "MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED");

                    if (Flags.setComponentAvailableFix()) {
                        disableBluetoothComponents((String) msg.obj);
                    }

                    if (!resetAdapter()) {
                        break;
                    }

                    // log the unexpected crash
                    addCrashLog();
                    mActiveLogs.add(ENABLE_DISABLE_REASON_CRASH, false);
                    if (mEnable) {
                        prepareRestartMessage();
                    }

                    sendBluetoothServiceDownCallback();

                    // Send BT state broadcast to update listener correctly (like Bt icon)
                    if (mState.oneOf(State.TURNING_ON, State.ON)) {
                        bluetoothStateChangeHandler(mState.get(), State.TURNING_OFF);
                    }
                    if (mState.oneOf(State.TURNING_OFF)) {
                        bluetoothStateChangeHandler(mState.get(), State.BLE_ON);
                    }
                    if (mState.oneOf(State.BLE_ON)) {
                        bluetoothStateChangeHandler(mState.get(), State.BLE_TURNING_OFF);
                    }
                    if (mState.oneOf(State.BLE_TURNING_ON, State.BLE_TURNING_OFF)) {
                        bluetoothStateChangeHandler(mState.get(), State.OFF);
                    }

                    mHandler.removeMessages(MESSAGE_BLUETOOTH_STATE_CHANGE);
                    mHandler.removeMessages(MESSAGE_BLUETOOTH_SERVICE_CONNECTED);
                }
                case MESSAGE_RESTART_BLUETOOTH_SERVICE -> handleRestartMessage();

                case MESSAGE_TIMEOUT_BIND -> {
                    Log.e(TAG, "TIMEOUT_BIND: Impossible to bind to Bluetooth service");
                    mContext.unbindService(mConnection);
                    bluetoothStateChangeHandler(State.BLE_TURNING_ON, State.OFF);
                    mHandler.removeMessages(MESSAGE_BLUETOOTH_SERVICE_CONNECTED);
                    if (mEnable) {
                        prepareRestartMessage();
                    }
                }

                default -> {} // Nothing to do
            }
        }
    }

    private boolean isBinding() {
        return mHandler.hasMessages(MESSAGE_TIMEOUT_BIND);
    }

    private void handleEnableMessage(boolean quietEnable, boolean isBle) {
        String logHeader = "handleEnableMessage(" + quietEnable + ", " + isBle + "): ";
        if (mShutdownInProgress) {
            Log.d(TAG, logHeader + "Skip Bluetooth Enable in device shutdown process");
            return;
        }

        mHandler.removeMessages(MESSAGE_RESTART_BLUETOOTH_SERVICE);
        mEnable = true;

        if (!isBle) {
            setBluetoothPersistedState(BLUETOOTH_ON_BLUETOOTH);
        }

        if (mState.oneOf(State.BLE_TURNING_ON, State.TURNING_ON, State.ON)) {
            Log.i(TAG, logHeader + "Already enabled. Current state=" + mState);
            return;
        }

        if (mState.oneOf(State.BLE_ON) && isBle) {
            Log.i(TAG, logHeader + "Already in BLE_ON while being requested to go to BLE_ON");
            return;
        }

        if (mState.oneOf(State.BLE_ON)) {
            Log.i(TAG, logHeader + "Bluetooth transition from State.BLE_ON to State.ON");
            bleOnToOn();
            return;
        }

        mQuietEnable = quietEnable;
        handleEnable();
    }

    private void handleDisableMessage() {
        mHandler.removeMessages(MESSAGE_RESTART_BLUETOOTH_SERVICE);

        if (Flags.gracefulDisableWithoutMessage() && mState.oneOf(State.OFF)) {
            Log.d(TAG, "Disable while already OFF. Nothing to do");
        } else if (isBinding()) {
            Log.d(TAG, "Disable while binding");
            mEnable = false;
            mContext.unbindService(mConnection);
            mHandler.removeMessages(MESSAGE_TIMEOUT_BIND);
            bluetoothStateChangeHandler(State.BLE_TURNING_ON, State.OFF);
            mHandler.removeMessages(MESSAGE_BLUETOOTH_SERVICE_CONNECTED);
        } else if (mState.oneOf(State.BLE_TURNING_ON)) {
            Log.d(TAG, "Disable while BLE_TURNING_ON");
            mEnable = false;
            bluetoothStateChangeHandler(State.BLE_TURNING_ON, State.OFF);
        } else if (Flags.gracefulDisableWithoutMessage()) {
            if (mState.oneOf(State.ON)) {
                Log.d(TAG, "Disable while ON");
                onToBleOn();
            } else if (mState.oneOf(State.TURNING_ON)) {
                Log.d(TAG, "Disable while TURNING_ON, set mEnable for later");
            } else if (mState.oneOf(State.BLE_ON)) {
                Log.d(TAG, "Disable while BLE_ON");
            } else if (mEnable) {
                Log.w(TAG, "Disable during unexpected state " + mState + ". mEnable is true !");
            } else {
                Log.d(TAG, "Disable during state " + mState + ". mEnable is false. Nothing to do");
            }
        } else if (!Flags.gracefulDisableWithoutMessage()) {
            if (mEnable && mAdapter != null) {
                mWaitForDisableRetry = 0;
                handleDisableDelayed();
            } else {
                mEnable = false;
                onToBleOn();
            }
        }

        if (Flags.gracefulDisableWithoutMessage()) {
            mEnable = false;
        }
    }

    private void prepareRestartMessage() {
        mEnable = false;

        mErrorRecoveryRetryCounter++;
        if (mErrorRecoveryRetryCounter > MAX_ERROR_RESTART_RETRIES) {
            resetAdapter();
            Log.e(TAG, "Reached maximum retry to restart Bluetooth!");
            return;
        }

        var delay = mErrorRecoveryRetryCounter * SERVICE_RESTART_TIME_MS;
        if (mErrorRecoveryRetryCounter > MAX_ERROR_RESTART_RETRIES / 2) {
            // Last attempts should leave more time
            delay = delay * 10;
        }

        Log.d(TAG, "Recovery " + mErrorRecoveryRetryCounter + " scheduled in " + delay + "ms");
        mHandler.sendEmptyMessageDelayed(MESSAGE_RESTART_BLUETOOTH_SERVICE, delay);
    }

    private void handleRestartMessage() {
        // Enable without persisting the setting as it doesn't change when Bluetooth restarts
        mEnable = true;
        mActiveLogs.add(ENABLE_DISABLE_REASON_RESTARTED, true);
        handleEnable();
    }

    private void prepareUserSwitch() {
        Log.d(TAG, "prepareUserSwitch(): Next user is " + mNextUser);

        // Clear registered LE apps to force shut-off
        mBleAppManager.clearBleApps();

        mEnable = false;
        mEnableExternal = false;

        mActiveLogs.add(ENABLE_DISABLE_REASON_USER_SWITCH, false);
        switch (mState.get()) {
            case State.ON -> onToBleOn();
            case State.BLE_ON -> bleOnToOff();
            case State.BLE_TURNING_ON -> bleTurningOnToOff();
            default -> throw new IllegalStateException("From impossible state: " + mState);
        }
    }

    private void executeUserSwitch() {
        mUser = mNextUser;
        mNextUser = null;
        mUserContext = mContext.createContextAsUser(mUser, 0);

        if (mConfigAllowAutoOn) {
            mAutoOn = new AutoOn(mLooper, mUserContext, mUser, mState, this::enableFromAutoOn);
        }
        if (Flags.userRestrictionRefactor()) {
            mSharingRestriction =
                    new SharingRestriction(mUserContext, mLooper, mBluetoothComponent, mUser);
        }

        if (isBluetoothDisallowed()) {
            Log.i(TAG, "executeUserSwitch: Bluetooth is disallowed");
            return;
        }
        if (mContext.getPackageManager().isSafeMode()) {
            Log.i(TAG, "executeUserSwitch: SafeMode prevent auto-enabling of Bluetooth");
            return;
        }
        if (!isBluetoothPersistedStateOnBluetooth()) {
            Log.i(TAG, "executeUserSwitch: Bluetooth not started");
            autoOnSetupTimer();
            return;
        }
        Log.i(TAG, "executeUserSwitch: Auto-enabling Bluetooth for " + mUser);
        mEnableExternal = true;
        sendEnableMsg(false, ENABLE_DISABLE_REASON_USER_SWITCH);
    }

    private boolean resetAdapter() {
        if (mAdapter == null) {
            return false;
        }
        mAdapter = null;
        mContext.unbindService(mConnection);
        return true;
    }

    private void handleEnable() {
        if (mAdapter != null) {
            Log.w(TAG, "handleEnable: Adapter already created");
            return;
        } else if (isBinding()) {
            Log.w(TAG, "handleEnable: Binding in progress");
            return;
        }

        bluetoothStateChangeHandler(State.OFF, State.BLE_TURNING_ON);
        RolePermissionListener.registerForUser(mLooper, mUserContext, mUser, this::onRoleGranted);
    }

    private Unit onRoleGranted() {
        if (!(mEnableExternal || mBleAppManager.isBleAppPresent())) {
            Log.w(TAG, "onRoleGranted: external=" + mEnableExternal + " ble=" + mBleAppManager);
            bluetoothStateChangeHandler(State.BLE_TURNING_ON, State.OFF);
        } else if (mAdapter != null) {
            Log.w(TAG, "onRoleGranted: Adapter already created");
        } else if (isBinding()) {
            Log.w(TAG, "onRoleGranted: Binding in progress");
        } else {
            bindToAdapter();
        }
        return Unit.INSTANCE;
    }

    private void bindToAdapter() {
        requireNonNull(mUser, "There is no user to start for.");
        int flags = Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT;
        Intent intent = new Intent(IAdapter.class.getName());
        if (Flags.userRestrictionRefactor()) {
            intent.setComponent(mBluetoothComponent.getComponentName());
        } else {
            intent.setComponent(resolveSystemService(intent));
        }

        Log.d(TAG, "Start binding to the Bluetooth service with intent=" + intent);
        if (!mContext.bindServiceAsUser(intent, mConnection, flags, mUser)) {
            Log.e(TAG, "Fail to bind to intent=" + intent);
            mContext.unbindService(mConnection);
            bluetoothStateChangeHandler(State.BLE_TURNING_ON, State.OFF);
            mBleAppManager.clearBleApps();
            mActiveLogs.add(ENABLE_DISABLE_REASON_START_ERROR, false);
            return;
        }
        mHandler.sendEmptyMessageDelayed(MESSAGE_TIMEOUT_BIND, TIMEOUT_BIND_MS);
    }

    private void handleDisableDelayed() {
        if (Flags.gracefulDisableWithoutMessage()) {
            throw new IllegalStateException("gracefulDisableWithoutMessage is enabled");
        }
        // The Bluetooth is turning on, wait for State.ON
        if (!mState.oneOf(State.ON)) {
            if (mWaitForDisableRetry < MAX_WAIT_FOR_ENABLE_DISABLE_RETRIES) {
                mWaitForDisableRetry++;
                mHandler.sendEmptyMessageDelayed(
                        MESSAGE_HANDLE_DISABLE_DELAYED, ENABLE_DISABLE_DELAY_MS);
                return;
            } else {
                Log.e(TAG, "Wait for State.ON timeout");
            }
        }
        // Either state is changed to State.ON or reaches the maximum retry, we
        // should move forward to the next step.
        mWaitForDisableRetry = 0;
        mEnable = false;
        onToBleOn();
    }

    private void propagateOffToBleOn(String hciInstanceName) {
        if (!mState.oneOf(State.BLE_TURNING_ON)) {
            Log.e(TAG, "propagateOffToBleOn: Impossible transition from " + mState);
            return;
        }
        Log.d(TAG, "propagateOffToBleOn: Sending request hciInstanceName " + hciInstanceName);
        try {
            mAdapter.offToBleOn(mQuietEnable, hciInstanceName);
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to call offToBleOn()", e);
        }
    }

    private void onToBleOn() {
        if (!mState.oneOf(State.ON)) {
            Log.e(TAG, "onToBleOn: Impossible transition from " + mState);
            return;
        }
        Log.d(TAG, "onToBleOn: Sending request");
        try {
            mAdapter.onToBleOn();
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to call onToBleOn()", e);
        }
        bluetoothStateChangeHandler(State.ON, State.TURNING_OFF);
    }

    private void bleOnToOn() {
        if (!mState.oneOf(State.BLE_ON)) {
            Log.e(TAG, "bleOnToOn: Impossible transition from " + mState);
            return;
        }
        Log.d(TAG, "bleOnToOn: sending request");
        try {
            mAdapter.bleOnToOn();
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to call bleOnToOn()", e);
        }
        bluetoothStateChangeHandler(State.BLE_ON, State.TURNING_ON);
    }

    private void bleOnToOff() {
        if (!mState.oneOf(State.BLE_ON)) {
            Log.e(TAG, "bleOnToOff: Impossible transition from " + mState);
            return;
        }
        Log.d(TAG, "bleOnToOff: Sending request");
        try {
            mAdapter.bleOnToOff();
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to call bleOnToOff()", e);
        }
        bluetoothStateChangeHandler(State.BLE_ON, State.BLE_TURNING_OFF);
    }

    private void bleTurningOnToOff() {
        if (!mState.oneOf(State.BLE_TURNING_ON)) {
            throw new IllegalStateException("bleTurningOnToOff: Impossible from " + mState);
        }
        Log.d(TAG, "bleTurningOnToOff: Sending request");
        if (mAdapter == null) {
            // When Bluetooth was not yet bound, prevent binding to complete
            mContext.unbindService(mConnection);
            mHandler.removeMessages(MESSAGE_BLUETOOTH_SERVICE_CONNECTED);
        }
        mHandler.removeMessages(MESSAGE_BLUETOOTH_STATE_CHANGE);
        bluetoothStateChangeHandler(State.BLE_TURNING_ON, State.OFF);
    }

    private void broadcastIntentStateChange(String action, int prevState, int newState) {
        Log.d(
                TAG,
                "broadcastIntentStateChange:"
                        + (" action=" + action.substring(action.lastIndexOf('.') + 1))
                        + (" prevState=" + State.$.toString(prevState))
                        + (" newState=" + State.$.toString(newState)));
        // Send broadcast message to everyone else
        Intent intent =
                new Intent(action)
                        .putExtra(EXTRA_PREVIOUS_STATE, prevState)
                        .putExtra(EXTRA_STATE, newState)
                        .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        if (Flags.onlyBroadcastToLocalUser()) {
            mContext.sendBroadcastAsUser(intent, mUser, null, getTempAllowlistBroadcastOptions());
        } else {
            mContext.sendBroadcastAsUser(
                    intent, UserHandle.ALL, null, getTempAllowlistBroadcastOptions());
        }
    }

    private static boolean isBleState(int state) {
        return switch (state) {
            case State.BLE_ON, State.BLE_TURNING_ON, State.BLE_TURNING_OFF -> true;
            default -> false;
        };
    }

    private void bluetoothStateChangeHandler(int prevState, int newState) {
        String header =
                "bluetoothStateChangeHandler("
                        + State.$.toString(prevState)
                        + ", "
                        + State.$.toString(newState)
                        + "): ";
        if (mState.oneOf(newState)) {
            Log.d(TAG, header + "Already in state " + mState);
            return;
        }

        if (newState == State.OFF) {
            // If Bluetooth is off, send service down event to proxy objects, and unbind
            Log.d(TAG, header + "Send ServiceDown");
            sendBluetoothServiceDownCallback();
            unbindAndFinish();
        }

        mState.set(newState);

        broadcastIntentStateChange(ACTION_BLE_STATE_CHANGED, prevState, newState);

        // BLE state are shown as State.OFF for BrEdr users
        final int prevBrEdrState = isBleState(prevState) ? State.OFF : prevState;
        final int newBrEdrState = isBleState(newState) ? State.OFF : newState;

        if (prevBrEdrState != newBrEdrState) { // Only broadcast when there is a BrEdr state change.
            broadcastIntentStateChange(ACTION_STATE_CHANGED, prevBrEdrState, newBrEdrState);
            if (newBrEdrState == State.OFF) {
                sendBluetoothOffCallback();
                sendBrEdrDownCallback();
            }
        }

        if (prevState == State.ON) {
            autoOnSetupTimer();
            AirplaneModeListener.setIsMediaProfileConnected(false);
            AirplaneModeListener.setWatchConnectionState(false);
        }

        if (newState == State.ON) {
            actionWhenBluetoothReachStateOn();
        } else if (newState == State.BLE_ON && prevState == State.BLE_TURNING_ON) {
            continueFromBleOnState();
        } else if (newState == State.OFF) {
            actionWhenBluetoothReachStateOff(prevState);
        }
    }

    private void actionWhenBluetoothReachStateOn() {
        String header = "actionWhenBluetoothReachStateOn(): ";
        if (mAutoOn != null) {
            mAutoOn.notifyBluetoothOn();
        }

        if (Flags.gracefulDisableWithoutMessage() && !mEnable) {
            Log.d(TAG, header + "mEnable is false. Turning off");
            onToBleOn();
            return;
        }

        if (mNextUser != null) {
            mHandler.removeCallbacksAndMessages(ON_SWITCH_USER_TOKEN);
            if (!mNextUser.equals(mUser)) {
                Log.d(TAG, header + "Resume user switch");
                prepareUserSwitch();
                return;
            }
            Log.d(TAG, header + "Already on correct user. Discard user switch");
            mNextUser = null;
        }

        sendBluetoothOnCallback();
    }

    private void actionWhenBluetoothReachStateOff(int prevState) {
        String header = "actionWhenBluetoothReachStateOff(): ";

        if (mNextUser != null) {
            Log.d(TAG, header + "Resume user switch");
            mHandler.removeCallbacksAndMessages(ON_SWITCH_USER_TOKEN);
            // Once everything is done finish the user switch if present
            executeUserSwitch();
            return;
        }

        if (Flags.onToBleOnViaOff()
                && prevState == State.BLE_TURNING_OFF
                && !mHandler.hasMessages(MESSAGE_RESTART_BLUETOOTH_SERVICE)) {
            if (mBleAppManager.isBleAppPresent() && !AirplaneModeListener.isOn()) {
                Log.d(TAG, header + "Ble app present, restarting in BLE_ON");
                sendEnableMsg(
                        false,
                        ENABLE_DISABLE_REASON_APPLICATION_REQUEST,
                        mContext.getPackageName(),
                        true);
            } else {
                Log.d(
                        TAG,
                        header
                                + "staying OFF and clearing ble apps."
                                + (" ble app present=" + mBleAppManager)
                                + (" airplane mode=" + AirplaneModeListener.isOn()));
                mBleAppManager.clearBleApps();
            }
        }
    }

    boolean waitForState(int... states) {
        Log.v(TAG, "Waiting " + STATE_TIMEOUT + " for state: " + Arrays.toString(states));
        return mState.waitForState(STATE_TIMEOUT, states);
    }

    private void sendDisableMsg(int reason) {
        sendDisableMsg(reason, mContext.getPackageName());
    }

    private void sendDisableMsg(int reason, String packageName) {
        mActiveLogs.add(reason, false, packageName, false);
        handleDisableMessage();
    }

    private void sendEnableMsg(boolean quietMode, int reason) {
        sendEnableMsg(quietMode, reason, mContext.getPackageName());
    }

    private void sendEnableMsg(boolean quietMode, int reason, String packageName) {
        sendEnableMsg(quietMode, reason, packageName, false);
    }

    private void sendEnableMsg(boolean quietMode, int reason, String packageName, boolean isBle) {
        mActiveLogs.add(reason, true, packageName, isBle);
        mLastEnabledTime = Instant.now();
        handleEnableMessage(quietMode, isBle);
    }

    private void addCrashLog() {
        synchronized (mCrashTimestamps) {
            if (mCrashTimestamps.size() == CRASH_LOG_MAX_SIZE) {
                mCrashTimestamps.remove(0);
            }
            mCrashTimestamps.add(System.currentTimeMillis());
            mCrashes++;
        }
    }

    private void recoverBluetoothServiceFromError(boolean clearBle) {
        Log.e(TAG, "recoverBluetoothServiceFromError");
        boolean repeatAirplaneRunnable = false;

        // 0 means we are matching unset `what` since we are using a token instead
        if (mHandler.hasMessages(0, ON_AIRPLANE_MODE_CHANGED_TOKEN)) {
            mHandler.removeCallbacksAndMessages(ON_AIRPLANE_MODE_CHANGED_TOKEN);
            repeatAirplaneRunnable = true;
        }

        if (mAdapter != null) {
            try {
                mAdapter.unregisterCallback(mBluetoothCallback);
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to unregister", e);
            }
        }

        Log.d(TAG, "Force sleep 500 ms for recovering from error");
        SystemClock.sleep(500);

        // disable
        mActiveLogs.add(ENABLE_DISABLE_REASON_START_ERROR, false);
        onToBleOn();

        waitForState(State.OFF);

        sendBluetoothServiceDownCallback();

        resetAdapter();

        mHandler.removeMessages(MESSAGE_BLUETOOTH_STATE_CHANGE);
        mState.set(State.OFF);

        if (clearBle) {
            mBleAppManager.clearBleApps();
        }

        prepareRestartMessage();

        if (repeatAirplaneRunnable) {
            onAirplaneModeChanged(AirplaneModeListener.isOnOverrode());
        }
    }

    private boolean isBluetoothDisallowed() {
        if (Flags.userRestrictionRefactor()) {
            return !BluetoothRestriction.isBluetoothAllowed();
        }
        final long callingIdentity = Binder.clearCallingIdentity();
        try {
            return mContext.getSystemService(UserManager.class)
                    .hasUserRestrictionForUser(UserManager.DISALLOW_BLUETOOTH, UserHandle.SYSTEM);
        } finally {
            Binder.restoreCallingIdentity(callingIdentity);
        }
    }

    /**
     * Disables BluetoothOppLauncherActivity component, so the Bluetooth sharing option is not
     * offered to the user if Bluetooth or sharing is disallowed. Puts the component to its default
     * state if Bluetooth is not disallowed.
     *
     * @param userHandle user to disable bluetooth sharing for
     * @param bluetoothSharingDisallowed whether bluetooth sharing is disallowed.
     */
    private void updateOppLauncherComponentState(
            UserHandle userHandle, boolean bluetoothSharingDisallowed) {
        try {
            int newState;
            if (bluetoothSharingDisallowed) {
                newState = PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            } else if (BluetoothProperties.isProfileOppEnabled().orElse(false)) {
                newState = PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
            } else {
                newState = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
            }

            // Bluetooth OPP activities that should always be enabled,
            // even when Bluetooth is turned OFF.
            List<String> baseBluetoothOppActivities =
                    List.of(
                            // Base sharing activity
                            "com.android.bluetooth.opp.BluetoothOppLauncherActivity",
                            // BT enable activities
                            "com.android.bluetooth.opp.BluetoothOppBtEnableActivity",
                            "com.android.bluetooth.opp.BluetoothOppBtEnablingActivity",
                            "com.android.bluetooth.opp.BluetoothOppBtErrorActivity");

            PackageManager systemPackageManager = mContext.getPackageManager();
            PackageManager userPackageManager =
                    mContext.createContextAsUser(userHandle, 0).getPackageManager();
            var allPackages = systemPackageManager.getPackagesForUid(Process.BLUETOOTH_UID);
            for (String candidatePackage : allPackages) {
                Log.v(TAG, "Searching package " + candidatePackage);
                PackageInfo packageInfo;
                try {
                    packageInfo =
                            systemPackageManager.getPackageInfo(
                                    candidatePackage,
                                    PackageManager.PackageInfoFlags.of(
                                            PackageManager.GET_ACTIVITIES
                                                    | PackageManager.MATCH_ANY_USER
                                                    | PackageManager.MATCH_UNINSTALLED_PACKAGES
                                                    | PackageManager.MATCH_DISABLED_COMPONENTS));
                } catch (PackageManager.NameNotFoundException e) {
                    // ignore, try next package
                    Log.e(TAG, "Could not find package " + candidatePackage);
                    continue;
                } catch (Exception e) {
                    Log.e(TAG, "Error while loading package" + e);
                    continue;
                }
                if (packageInfo.activities == null) {
                    continue;
                }
                for (var activity : packageInfo.activities) {
                    Log.v(TAG, "Checking activity " + activity.name);
                    if (baseBluetoothOppActivities.contains(activity.name)) {
                        for (String activityName : baseBluetoothOppActivities) {
                            userPackageManager.setComponentEnabledSetting(
                                    new ComponentName(candidatePackage, activityName),
                                    newState,
                                    PackageManager.DONT_KILL_APP);
                        }
                        return;
                    }
                }
            }

            Log.e(
                    TAG,
                    "Cannot toggle Bluetooth OPP activities, could not find them in any package");
        } catch (Exception e) {
            Log.e(TAG, "updateOppLauncherComponentState failed: " + e);
        }
    }

    void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        String errorMsg = null;

        writer.println("Bluetooth Status");
        writer.println("  enabled: " + isEnabled());
        writer.println("  state: " + mState);
        writer.println("  address: " + Log.address(mAddress));
        writer.println("  name: " + mName);
        if (mEnable) {
            Duration elapsed = Duration.between(mLastEnabledTime, Instant.now());
            writer.println(
                    "  time since enabled: "
                            + elapsed.toString()
                                    .substring(2)
                                    .replaceAll("(\\d[HMS])(?!$)", "$1 ")
                                    .toLowerCase(Locale.US));
        }

        writer.println("");
        mActiveLogs.dump(writer);

        writer.println("");

        writer.println("Bluetooth crashed " + mCrashes + " time" + (mCrashes == 1 ? "" : "s"));
        if (mCrashes == CRASH_LOG_MAX_SIZE) {
            writer.println("(last " + CRASH_LOG_MAX_SIZE + ")");
        }
        for (Long time : mCrashTimestamps) {
            writer.println("  " + Log.timeToStringWithZone(time));
        }

        writer.println("");
        writer.println("Ble app registered: " + mBleAppManager);

        writer.println("");
        writer.println("BluetoothManagerService:");
        writer.println("  mEnable:" + mEnable);
        writer.println("  mQuietEnable:" + mQuietEnable);
        writer.println("  mEnableExternal:" + mEnableExternal);
        writer.println("  mQuietEnableExternal:" + mQuietEnableExternal);

        writer.println("");
        writer.flush();

        dumpBluetoothFlags(writer);
        writer.println("");

        if (mAdapter == null) {
            errorMsg = "Bluetooth Service not connected";
        } else {
            if (args.length == 0) {
                // Add arg to produce output
                args = new String[1];
                args[0] = "--print";
            }

            try {
                mAdapter.getAdapterBinder().asBinder().dumpAsync(fd, args);
            } catch (RemoteException re) {
                errorMsg = "RemoteException while dumping Bluetooth Service";
            }
        }
        if (errorMsg != null) {
            writer.println(errorMsg);
        }
    }

    private static void dumpBluetoothFlags(PrintWriter writer) {
        writer.println("🚩Flag dump:");
        Pattern pattern = Pattern.compile("_([0-9a-z])");
        // When a flag contains a number, the camelCase method doesn't provide information if the
        // number should have an underscore before or not. Example: a2dpVersion14 is for
        // a2dp_version_1_4...
        // To fix that, we first need to get the static flag value, then we convert the SNAKE_NAME
        // to camelCase and call the associated method to get the flag value
        Arrays.stream(Flags.class.getDeclaredFields())
                .filter((Field f) -> f.getType() == String.class)
                .forEach(
                        (Field f) -> {
                            try {
                                String flagName =
                                        ((String) f.get(null))
                                                .replaceFirst(
                                                        "com.android.bluetooth.*\\.flags\\.", "");
                                String methodName =
                                        pattern.matcher(flagName)
                                                .replaceAll(m -> m.group(1).toUpperCase(Locale.US));
                                boolean flagValue =
                                        (boolean)
                                                Flags.class
                                                        .getDeclaredMethod(methodName)
                                                        .invoke(null);
                                writer.println(
                                        "\t" + (flagValue ? "[■]" : "[ ]") + ": " + flagName);
                            } catch (IllegalAccessException
                                    | InvocationTargetException
                                    | NoSuchMethodException e) {
                                writer.println("Cannot invoke flag value for " + f);
                                throw new RuntimeException(e);
                            }
                        });
    }

    static @NonNull Bundle getTempAllowlistBroadcastOptions() {
        final long duration = 10_000;
        final BroadcastOptions bOptions = BroadcastOptions.makeBasic();
        bOptions.setTemporaryAppAllowlist(
                duration,
                TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED,
                PowerExemptionManager.REASON_BLUETOOTH_BROADCAST,
                "");
        return bOptions.toBundle();
    }

    private ComponentName resolveSystemService(@NonNull Intent intent) {
        if (Flags.userRestrictionRefactor()) {
            throw new IllegalStateException("userRestrictionRefactor is enabled");
        }
        List<ComponentName> results =
                mContext.getPackageManager().queryIntentServices(intent, 0).stream()
                        .filter(
                                ri ->
                                        (ri.serviceInfo.applicationInfo.flags
                                                        & ApplicationInfo.FLAG_SYSTEM)
                                                != 0)
                        .map(
                                ri ->
                                        new ComponentName(
                                                ri.serviceInfo.applicationInfo.packageName,
                                                ri.serviceInfo.name))
                        .collect(Collectors.toList());
        return switch (results.size()) {
            case 0 -> throw new IllegalStateException("No services can handle intent " + intent);
            case 1 -> results.get(0);
            default -> {
                throw new IllegalStateException(
                        "Multiples services can handle intent " + intent + ": " + results);
            }
        };
    }

    int setBtHciSnoopLogMode(int mode) {
        final BluetoothProperties.snoop_log_mode_values snoopMode =
                switch (mode) {
                    case BT_SNOOP_LOG_MODE_DISABLED ->
                            BluetoothProperties.snoop_log_mode_values.DISABLED;
                    case BT_SNOOP_LOG_MODE_FILTERED ->
                            BluetoothProperties.snoop_log_mode_values.FILTERED;
                    case BT_SNOOP_LOG_MODE_FULL -> BluetoothProperties.snoop_log_mode_values.FULL;
                    default -> null;
                };
        try {
            BluetoothProperties.snoop_log_mode(snoopMode);
        } catch (RuntimeException e) {
            Log.e(TAG, "setBtHciSnoopLogMode: Failed to set mode to " + mode + ": " + e);
            return Integer.MAX_VALUE;
        }
        return 0;
    }

    int getBtHciSnoopLogMode() {
        return switch (BluetoothProperties.snoop_log_mode()
                .orElse(BluetoothProperties.snoop_log_mode_values.DISABLED)) {
            case BluetoothProperties.snoop_log_mode_values.FILTERED -> BT_SNOOP_LOG_MODE_FILTERED;
            case BluetoothProperties.snoop_log_mode_values.FULL -> BT_SNOOP_LOG_MODE_FULL;
            default -> BT_SNOOP_LOG_MODE_DISABLED;
        };
    }

    private final boolean mConfigAllowAutoOn;

    private void autoOnSetupTimer() {
        if (mAutoOn == null) {
            Log.d(TAG, "AutoOn is not active: Not creating a timer");
            return;
        }
        mAutoOn.resetAutoOnTimer();
    }

    boolean isAutoOnSupported() {
        return mAutoOn != null && mAutoOn.isSupported();
    }

    boolean isAutoOnEnabled() {
        if (mAutoOn == null) {
            throw new IllegalStateException("AutoOn is not supported in current config");
        }
        return mAutoOn.isEnabled();
    }

    void setAutoOnEnabled(boolean status) {
        if (mAutoOn == null) {
            throw new IllegalStateException("AutoOn is not supported in current config");
        }
        mAutoOn.setEnabled(status);
    }

    /** Check if BLE is supported by this platform */
    private static boolean isBleSupported(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE);
    }

    /** Check if this is an automotive device */
    private static boolean isAutomotive(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE);
    }

    /** Check if this is a watch device */
    private static boolean isWatch(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH);
    }

    /** Check if this is a TV device */
    private static boolean isTv(Context context) {
        PackageManager pm = context.getPackageManager();
        return pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
                || pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }

    /**
     * In case of a Bluetooth crash, mark it's enabled components as non longer available to trigger
     * the PACKAGE_CHANGED intent. This should not be needed in a normal shutdown as the Bluetooth
     * clean its components on its own
     */
    private void disableBluetoothComponents(String packageName) {
        PackageManager pm = mContext.getPackageManager();
        PackageInfo packageInfo = null;

        try {
            packageInfo =
                    pm.getPackageInfo(
                            packageName,
                            PackageManager.GET_SERVICES
                                    | PackageManager.GET_ACTIVITIES
                                    | PackageManager.GET_RECEIVERS
                                    | PackageManager.GET_PROVIDERS);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Package not found: " + packageName, e);
            return;
        }

        // Refer to updateOppLauncherComponentState()
        List<String> baseBluetoothOppActivities =
                List.of(
                        "com.android.bluetooth.opp.BluetoothOppLauncherActivity",
                        "com.android.bluetooth.opp.BluetoothOppBtEnableActivity",
                        "com.android.bluetooth.opp.BluetoothOppBtEnablingActivity",
                        "com.android.bluetooth.opp.BluetoothOppBtErrorActivity");

        disableComponents(pm, packageInfo.activities, packageName, baseBluetoothOppActivities);
        disableComponents(pm, packageInfo.services, packageName, null);
        disableComponents(pm, packageInfo.receivers, packageName, null);
        disableComponents(pm, packageInfo.providers, packageName, null);
    }

    private static <T extends android.content.pm.ComponentInfo> void disableComponents(
            PackageManager pm, T[] components, String packageName, List<String> componentsToKeep) {
        if (components == null) {
            return;
        }

        Arrays.stream(components)
                .filter(componentInfo -> !componentInfo.enabled)
                .map(componentInfo -> new ComponentName(packageName, componentInfo.name))
                .filter(
                        componentName ->
                                (componentsToKeep == null
                                        || !componentsToKeep.contains(
                                                componentName.getClassName())))
                .forEach(
                        componentName -> {
                            pm.setComponentEnabledSetting(
                                    componentName,
                                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                                    PackageManager.DONT_KILL_APP);
                            Log.i(TAG, "Disabled component: " + componentName.flattenToString());
                        });
    }
}
