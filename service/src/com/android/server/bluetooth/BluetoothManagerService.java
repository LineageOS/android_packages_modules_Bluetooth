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
import static android.bluetooth.BluetoothAdapter.STATE_BLE_ON;
import static android.bluetooth.BluetoothAdapter.STATE_BLE_TURNING_OFF;
import static android.bluetooth.BluetoothAdapter.STATE_BLE_TURNING_ON;
import static android.bluetooth.BluetoothAdapter.STATE_OFF;
import static android.bluetooth.BluetoothAdapter.STATE_ON;
import static android.bluetooth.BluetoothAdapter.STATE_TURNING_OFF;
import static android.bluetooth.BluetoothAdapter.STATE_TURNING_ON;
import static android.bluetooth.BluetoothAdapter.nameForState;
import static android.bluetooth.BluetoothProtoEnums.ENABLE_DISABLE_REASON_AIRPLANE_MODE;
import static android.bluetooth.BluetoothProtoEnums.ENABLE_DISABLE_REASON_APPLICATION_REQUEST;
import static android.bluetooth.BluetoothProtoEnums.ENABLE_DISABLE_REASON_CRASH;
import static android.bluetooth.BluetoothProtoEnums.ENABLE_DISABLE_REASON_DISALLOWED;
import static android.bluetooth.BluetoothProtoEnums.ENABLE_DISABLE_REASON_FACTORY_RESET;
import static android.bluetooth.BluetoothProtoEnums.ENABLE_DISABLE_REASON_RESTARTED;
import static android.bluetooth.BluetoothProtoEnums.ENABLE_DISABLE_REASON_RESTORE_USER_SETTING;
import static android.bluetooth.BluetoothProtoEnums.ENABLE_DISABLE_REASON_SATELLITE_MODE;
import static android.bluetooth.BluetoothProtoEnums.ENABLE_DISABLE_REASON_START_ERROR;
import static android.bluetooth.BluetoothProtoEnums.ENABLE_DISABLE_REASON_SYSTEM_BOOT;
import static android.bluetooth.BluetoothProtoEnums.ENABLE_DISABLE_REASON_USER_SWITCH;
import static android.os.PowerExemptionManager.TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED;

import static com.android.modules.utils.build.SdkLevel.isAtLeastV;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.BroadcastOptions;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.IBluetooth;
import android.bluetooth.IBluetoothCallback;
import android.bluetooth.IBluetoothManager;
import android.bluetooth.IBluetoothManagerCallback;
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
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.Build;
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
import android.util.proto.ProtoOutputStream;

import androidx.annotation.RequiresApi;

import com.android.bluetooth.flags.Flags;
import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.expresslog.Counter;
import com.android.modules.expresslog.Histogram;
import com.android.server.BluetoothManagerServiceDumpProto;
import com.android.server.bluetooth.airplane.AirplaneModeListener;
import com.android.server.bluetooth.satellite.SatelliteModeListener;

import libcore.util.SneakyThrow;

import kotlin.Unit;
import kotlin.time.TimeSource;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

class BluetoothManagerService {
    private static final String TAG = BluetoothManagerService.class.getSimpleName();

    private static final int CRASH_LOG_MAX_SIZE = 100;

    // See android.os.Build.HW_TIMEOUT_MULTIPLIER. This should not be set on real hw
    private static final int HW_MULTIPLIER = SystemProperties.getInt("ro.hw_timeout_multiplier", 1);

    // Maximum msec to wait for a bind
    private static final int TIMEOUT_BIND_MS = 4000 * HW_MULTIPLIER;

    // Timeout value for synchronous binder call
    private static final Duration STATE_TIMEOUT = Duration.ofSeconds(4L * HW_MULTIPLIER);

    // Maximum msec to wait for service restart
    private static final int SERVICE_RESTART_TIME_MS = 400 * HW_MULTIPLIER;
    // Maximum msec to wait for restart due to error
    private static final int ERROR_RESTART_TIME_MS = 3000 * HW_MULTIPLIER;
    // Maximum msec to delay MESSAGE_USER_SWITCHED
    private static final int USER_SWITCHED_TIME_MS = 200 * HW_MULTIPLIER;
    // Delay for the addProxy function in msec
    private static final int ADD_PROXY_DELAY_MS = 100 * HW_MULTIPLIER;
    // Delay for retrying enable and disable in msec
    @VisibleForTesting static final int ENABLE_DISABLE_DELAY_MS = 300 * HW_MULTIPLIER;

    @VisibleForTesting static final int MESSAGE_ENABLE = 1;
    @VisibleForTesting static final int MESSAGE_DISABLE = 2;
    @VisibleForTesting static final int MESSAGE_HANDLE_ENABLE_DELAYED = 3;
    @VisibleForTesting static final int MESSAGE_HANDLE_DISABLE_DELAYED = 4;
    @VisibleForTesting static final int MESSAGE_BLUETOOTH_SERVICE_CONNECTED = 40;
    @VisibleForTesting static final int MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED = 41;
    @VisibleForTesting static final int MESSAGE_RESTART_BLUETOOTH_SERVICE = 42;
    @VisibleForTesting static final int MESSAGE_BLUETOOTH_STATE_CHANGE = 60;
    @VisibleForTesting static final int MESSAGE_TIMEOUT_BIND = 100;
    // TODO: b/368120237 delete MESSAGE_GET_NAME_AND_ADDRESS
    @VisibleForTesting static final int MESSAGE_GET_NAME_AND_ADDRESS = 200;
    @VisibleForTesting static final int MESSAGE_USER_SWITCHED = 300;
    @VisibleForTesting static final int MESSAGE_USER_UNLOCKED = 301;
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

    // Map of apps registered to keep BLE scanning on.
    private final Map<IBinder, ClientDeathRecipient> mBleApps = new ConcurrentHashMap<>();

    private final BluetoothAdapterState mState = new BluetoothAdapterState();
    private final List<Long> mCrashTimestamps = new ArrayList<>();
    private final RemoteCallbackList<IBluetoothManagerCallback> mCallbacks =
            new RemoteCallbackList<>();
    private final BluetoothServiceBinder mBinder;
    @VisibleForTesting final BluetoothHandler mHandler;
    private final ContentResolver mContentResolver;
    private final Context mContext;
    private final Looper mLooper;
    private final UserManager mUserManager;

    private final boolean mIsHearingAidProfileSupported;

    private String mAddress;
    private String mName;
    private AdapterBinder mAdapter;
    private Context mCurrentUserContext;

    // used inside handler thread
    private boolean mQuietEnable = false;
    private boolean mEnable = false;
    private boolean mShutdownInProgress = false;

    private int mCrashes = 0;
    private long mLastEnabledTime;

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
                                    + (" prevState=" + nameForState(prevState))
                                    + (" newState=" + nameForState(newState)));
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
                    if (!BluetoothAdapter.checkBluetoothAddress(address)) {
                        throw new IllegalArgumentException("Invalid address");
                    }
                    Log.d(TAG, "IBluetoothCallback.onAdapterAddressChange: " + logAddress(address));
                    mHandler.post(() -> storeAddress(address));
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
                new Intent(BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED)
                        .putExtra(BluetoothAdapter.EXTRA_LOCAL_NAME, name)
                        .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        mContext.sendBroadcastAsUser(
                intent, UserHandle.ALL, BLUETOOTH_CONNECT, getTempAllowlistBroadcastOptions());
    }

    private void storeAddress(String address) {
        if (!Settings.Secure.putString(
                mContentResolver, Settings.Secure.BLUETOOTH_ADDRESS, address)) {
            Log.e(
                    TAG,
                    "storeAddress("
                            + logAddress(address)
                            + "): Failed. Address is still "
                            + logAddress(mAddress));
            return;
        }
        mAddress = address;
        Log.v(TAG, "storeAddress(" + logAddress(mAddress) + "): Success");
    }

    public void onUserRestrictionsChanged(UserHandle userHandle) {
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

    boolean onFactoryResetFromBinder() {
        // Wait for stable state if bluetooth is temporary state.
        int state = getState();
        if (state == STATE_BLE_TURNING_ON
                || state == STATE_TURNING_ON
                || state == STATE_TURNING_OFF) {
            if (!waitForState(STATE_BLE_ON, STATE_ON)) {
                return false;
            }
        }
        return postAndWait(() -> onFactoryReset());
    }

    @VisibleForTesting
    boolean onFactoryReset() {
        // Clear registered LE apps to force shut-off Bluetooth
        clearBleApps();
        int state = getState();
        if (state == STATE_BLE_ON) {
            ActiveLogs.add(ENABLE_DISABLE_REASON_FACTORY_RESET, false);
            bleOnToOff();
            return true;
        } else if (state == STATE_ON) {
            ActiveLogs.add(ENABLE_DISABLE_REASON_FACTORY_RESET, false);
            onToBleOn();
            return true;
        }
        return false;
    }

    private int estimateBusyTime(int state) {
        if (state == STATE_BLE_ON && isBluetoothPersistedStateOn()) {
            // Bluetooth is in BLE and is starting classic
            return SERVICE_RESTART_TIME_MS;
        } else if (state != STATE_ON && state != STATE_OFF && state != STATE_BLE_ON) {
            // Bluetooth is turning state
            return ADD_PROXY_DELAY_MS;
        } else if (mHandler.hasMessages(MESSAGE_ENABLE)
                || mHandler.hasMessages(MESSAGE_DISABLE)
                || mHandler.hasMessages(MESSAGE_HANDLE_ENABLE_DELAYED)
                || mHandler.hasMessages(MESSAGE_HANDLE_DISABLE_DELAYED)
                || mHandler.hasMessages(MESSAGE_RESTART_BLUETOOTH_SERVICE)
                || mHandler.hasMessages(MESSAGE_TIMEOUT_BIND)) {
            Log.d(
                    TAG,
                    "Busy reason:"
                            + " ENABLE="
                            + mHandler.hasMessages(MESSAGE_ENABLE)
                            + " DISABLE="
                            + mHandler.hasMessages(MESSAGE_DISABLE)
                            + " HANDLE_ENABLE_DELAYED="
                            + mHandler.hasMessages(MESSAGE_HANDLE_ENABLE_DELAYED)
                            + " HANDLE_DISABLE_DELAYED="
                            + mHandler.hasMessages(MESSAGE_HANDLE_DISABLE_DELAYED)
                            + " RESTART_BLUETOOTH_SERVICE="
                            + mHandler.hasMessages(MESSAGE_RESTART_BLUETOOTH_SERVICE)
                            + " TIMEOUT_BIND="
                            + mHandler.hasMessages(MESSAGE_TIMEOUT_BIND));
            // Bluetooth is restarting
            return SERVICE_RESTART_TIME_MS;
        }
        return 0;
    }

    private void delayModeChangedIfNeeded(Object token, Runnable r, String modechanged) {
        final int state = getState();
        final int delayMs = estimateBusyTime(state);
        Log.d(
                TAG,
                ("delayModeChangedIfNeeded(" + modechanged + "):")
                        + (" state=" + nameForState(state))
                        + (" Airplane.isOnOverrode=" + AirplaneModeListener.isOnOverrode())
                        + (" Airplane.isOn=" + AirplaneModeListener.isOn())
                        + (" isSatelliteModeOn()=" + isSatelliteModeOn())
                        + (" delayed=" + delayMs + "ms"));

        mHandler.removeCallbacksAndMessages(token);

        if (delayMs > 0) {
            mHandler.postDelayed(
                    () -> delayModeChangedIfNeeded(token, r, modechanged), token, delayMs);
        } else {
            r.run();
        }
    }

    /** Send Intent to the Notification Service in the Bluetooth app */
    Unit sendToggleNotification(String notificationReason) {
        Intent intent =
                new Intent("android.bluetooth.notification.action.SEND_TOGGLE_NOTIFICATION");
        intent.setComponent(resolveSystemService(intent));
        intent.putExtra(
                "android.bluetooth.notification.extra.NOTIFICATION_REASON", notificationReason);
        mCurrentUserContext.startService(intent);
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

    private Unit onSatelliteModeChanged(boolean isSatelliteModeOn) {
        delayModeChangedIfNeeded(
                ON_SATELLITE_MODE_CHANGED_TOKEN,
                () -> handleSatelliteModeChanged(isSatelliteModeOn),
                "onSatelliteModeChanged");
        return Unit.INSTANCE;
    }

    // Call is coming from the systemServer main thread and need to be post to avoid race
    void onSwitchUser(UserHandle userHandle) {
        mHandler.post(
                () ->
                        delayModeChangedIfNeeded(
                                ON_SWITCH_USER_TOKEN,
                                () -> handleSwitchUser(userHandle),
                                "onSwitchUser"));
    }

    private void forceToOffFromModeChange(int currentState, int reason) {
        // Clear registered LE apps to force shut-off
        clearBleApps();

        if (reason == ENABLE_DISABLE_REASON_SATELLITE_MODE
                || !AirplaneModeListener.hasUserToggledApm(mCurrentUserContext)) {
            // AirplaneMode can have a state where it does not impact the AutoOnFeature
            AutoOnFeature.pause();
        }

        if (currentState == STATE_ON) {
            mEnable = false;
            ActiveLogs.add(reason, false);
            onToBleOn();
        } else if (currentState == STATE_BLE_ON) {
            // If currentState is BLE_ON make sure we trigger stopBle
            mEnable = false;
            mEnableExternal = false;
            ActiveLogs.add(reason, false);
            bleOnToOff();
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
                        + (" currentState=" + nameForState(currentState)));

        if (isAirplaneModeOn) {
            forceToOffFromModeChange(currentState, ENABLE_DISABLE_REASON_AIRPLANE_MODE);
        } else if (mEnableExternal && currentState != STATE_ON && isPersistStateOn) {
            // isPersistStateOn is checked to prevent race with RESTORE_USER_SETTING
            sendEnableMsg(mQuietEnableExternal, ENABLE_DISABLE_REASON_AIRPLANE_MODE);
        } else if (currentState != STATE_ON) {
            autoOnSetupTimer();
        }
    }

    private void handleSatelliteModeChanged(boolean isSatelliteModeOn) {
        final int currentState = mState.get();

        if (shouldBluetoothBeOn(isSatelliteModeOn) && currentState != STATE_ON) {
            sendEnableMsg(mQuietEnableExternal, ENABLE_DISABLE_REASON_SATELLITE_MODE);
        } else if (!shouldBluetoothBeOn(isSatelliteModeOn) && currentState != STATE_OFF) {
            forceToOffFromModeChange(currentState, ENABLE_DISABLE_REASON_SATELLITE_MODE);
        } else if (!isSatelliteModeOn
                && !shouldBluetoothBeOn(isSatelliteModeOn)
                && currentState != STATE_ON) {
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
                    if (!Flags.getNameAndAddressAsCallback()
                            && BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED.equals(action)) {
                        String newName = intent.getStringExtra(BluetoothAdapter.EXTRA_LOCAL_NAME);
                        if (newName != null) {
                            Log.d(TAG, "Local name changed to: " + newName);
                            storeNameAndAddress(newName, null);
                        }
                    } else if (!Flags.getNameAndAddressAsCallback()
                            && BluetoothAdapter.ACTION_BLUETOOTH_ADDRESS_CHANGED.equals(action)) {
                        String newAddress =
                                intent.getStringExtra(BluetoothAdapter.EXTRA_BLUETOOTH_ADDRESS);
                        if (newAddress != null) {
                            Log.d(TAG, "Local address changed to: " + logAddress(newAddress));
                            storeNameAndAddress(null, newAddress);
                        } else {
                            Log.e(TAG, "No Bluetooth Adapter address parameter found");
                        }
                    } else if (Intent.ACTION_SETTING_RESTORED.equals(action)) {
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
                        if (mState.oneOf(STATE_BLE_ON)) {
                            bleOnToOff();
                        } else if (mState.oneOf(STATE_ON)) {
                            onToBleOn();
                        }
                    }
                }
            };

    private final Histogram mShutdownLatencyHistogram =
            new Histogram(
                    "bluetooth.value_shutdown_latency", new Histogram.UniformOptions(50, 0, 3000));

    BluetoothManagerService(@NonNull Context context, @NonNull Looper looper) {
        mContext = requireNonNull(context, "Context cannot be null");
        mContentResolver = requireNonNull(mContext.getContentResolver(), "Resolver cannot be null");
        mLooper = requireNonNull(looper, "Looper cannot be null");

        mUserManager =
                requireNonNull(
                        mContext.getSystemService(UserManager.class),
                        "UserManager system service cannot be null");

        mBinder = new BluetoothServiceBinder(this, mLooper, mContext, mUserManager);
        mHandler = new BluetoothHandler(mLooper);

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
        if (!Flags.getNameAndAddressAsCallback()) {
            filter.addAction(BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED);
            filter.addAction(BluetoothAdapter.ACTION_BLUETOOTH_ADDRESS_CHANGED);
        }
        filter.addAction(Intent.ACTION_SETTING_RESTORED);
        filter.addAction(Intent.ACTION_SHUTDOWN);
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        mContext.registerReceiver(mReceiver, filter, null, mHandler);

        IntentFilter filterUser = new IntentFilter();
        filterUser.addAction(UserManager.ACTION_USER_RESTRICTIONS_CHANGED);
        filterUser.addAction(Intent.ACTION_USER_SWITCHED);
        filterUser.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        mContext.registerReceiverForAllUsers(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        switch (intent.getAction()) {
                            case Intent.ACTION_USER_SWITCHED:
                                int foregroundUserId =
                                        intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                                propagateForegroundUserId(foregroundUserId);
                                break;
                            case UserManager.ACTION_USER_RESTRICTIONS_CHANGED:
                                onUserRestrictionsChanged(getSendingUser());
                                break;
                            default:
                                Log.e(
                                        TAG,
                                        "Unknown broadcast received in BluetoothManagerService"
                                                + " receiver registered across all users");
                        }
                    }
                },
                filterUser,
                null,
                mHandler);

        if (Flags.getNameAndAddressAsCallback()) {
            mName =
                    BluetoothServerProxy.getInstance()
                            .settingsSecureGetString(
                                    mContentResolver, Settings.Secure.BLUETOOTH_NAME);
            mAddress =
                    BluetoothServerProxy.getInstance()
                            .settingsSecureGetString(
                                    mContentResolver, Settings.Secure.BLUETOOTH_ADDRESS);

            Log.d(TAG, "Local adapter: Name=" + mName + ", Address=" + logAddress(mAddress));
        } else {
            loadStoredNameAndAddress();
        }

        if (isBluetoothPersistedStateOn()) {
            Log.i(TAG, "Startup: Bluetooth persisted state is ON.");
            mEnableExternal = true;
        }

        mDeviceConfigAllowAutoOn =
                SystemProperties.getBoolean("bluetooth.server.automatic_turn_on", false);
        Log.d(TAG, "AutoOnFeature property=" + mDeviceConfigAllowAutoOn);
        if (mDeviceConfigAllowAutoOn) {
            Counter.logIncrement("bluetooth.value_auto_on_supported");
        }
    }

    private Unit onBleScanDisabled() {
        if (mState.oneOf(STATE_OFF, STATE_BLE_TURNING_OFF)) {
            Log.i(TAG, "onBleScanDisabled: Nothing to do, Bluetooth is already turning off");
            return Unit.INSTANCE;
        }
        clearBleApps();

        if (mState.oneOf(STATE_BLE_ON)) {
            Log.i(TAG, "onBleScanDisabled: Shutting down BLE_ON mode");
            bleOnToOff();
        } else {
            Log.i(TAG, "onBleScanDisabled: Bluetooth is not in BLE_ON, staying on");
        }
        return Unit.INSTANCE;
    }

    IBluetoothManager.Stub getBinder() {
        return mBinder;
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

    /** Returns true if the Bluetooth Adapter's name and address is locally cached */
    private boolean isNameAndAddressSet() {
        return mName != null && mAddress != null && mName.length() > 0 && mAddress.length() > 0;
    }

    private void loadStoredNameAndAddress() {
        if (BluetoothProperties.isAdapterAddressValidationEnabled().orElse(false)
                && Settings.Secure.getInt(mContentResolver, Settings.Secure.BLUETOOTH_ADDR_VALID, 0)
                        == 0) {
            // if the valid flag is not set, don't load the address and name
            Log.w(TAG, "There is no valid bluetooth name and address stored");
            return;
        }
        mName =
                BluetoothServerProxy.getInstance()
                        .settingsSecureGetString(mContentResolver, Settings.Secure.BLUETOOTH_NAME);
        mAddress =
                BluetoothServerProxy.getInstance()
                        .settingsSecureGetString(
                                mContentResolver, Settings.Secure.BLUETOOTH_ADDRESS);

        Log.d(TAG, "loadStoredNameAndAddress: Name=" + mName + ", Address=" + logAddress(mAddress));
    }

    private static String logAddress(String address) {
        if (address == null) {
            return "[address is null]";
        }
        if (address.length() != 17) {
            return "[address invalid]";
        }
        return "XX:XX:XX:XX:" + address.substring(address.length() - 5);
    }

    /**
     * Save the Bluetooth name and address in the persistent store. Only non-null values will be
     * saved.
     */
    private void storeNameAndAddress(String name, String address) {
        final String logHeader = "storeNameAndAddress(" + name + ", " + logAddress(address) + "): ";
        if (name != null) {
            if (Settings.Secure.putString(mContentResolver, Settings.Secure.BLUETOOTH_NAME, name)) {
                mName = name;
            } else {
                Log.e(TAG, logHeader + "Failed. Name is still " + mName);
            }
        }

        if (address != null) {
            if (Settings.Secure.putString(
                    mContentResolver, Settings.Secure.BLUETOOTH_ADDRESS, address)) {
                mAddress = address;
            } else {
                Log.e(TAG, logHeader + "Failed. Address is still " + logAddress(mAddress));
            }
        }

        if ((mName != null) && (mAddress != null)) {
            Settings.Secure.putInt(mContentResolver, Settings.Secure.BLUETOOTH_ADDR_VALID, 1);
        }
        Log.d(TAG, logHeader + "Completed successfully");
    }

    // Called from unsafe binder thread
    IBluetooth registerAdapter(IBluetoothManagerCallback callback) {
        mCallbacks.register(callback);
        // Copy to local variable to avoid race condition when checking for null
        AdapterBinder adapter = mAdapter;
        return adapter != null ? adapter.getAdapterBinder() : null;
    }

    void unregisterAdapter(IBluetoothManagerCallback callback) {
        mCallbacks.unregister(callback);
    }

    boolean isEnabled() {
        return getState() == STATE_ON;
    }

    /**
     * Sends the current foreground user id to the Bluetooth process. This user id is used to
     * determine if Binder calls are coming from the active user.
     *
     * @param userId is the foreground user id we are propagating to the Bluetooth process
     */
    private void propagateForegroundUserId(int userId) {
        if (mAdapter == null) {
            return;
        }
        try {
            mAdapter.setForegroundUserId(userId, mContext.getAttributionSource());
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to set foreground user id", e);
        }
    }

    int getState() {
        return mState.get();
    }

    class ClientDeathRecipient implements IBinder.DeathRecipient {
        private final String mPackageName;

        ClientDeathRecipient(String packageName) {
            mPackageName = packageName;
        }

        public void binderDied() {
            Log.w(TAG, "Binder is dead - unregister " + mPackageName);

            for (Map.Entry<IBinder, ClientDeathRecipient> entry : mBleApps.entrySet()) {
                IBinder token = entry.getKey();
                ClientDeathRecipient deathRec = entry.getValue();
                if (deathRec.equals(this)) {
                    updateBleAppCount(token, false, mPackageName);
                    break;
                }
            }
        }

        public String getPackageName() {
            return mPackageName;
        }
    }

    boolean isBleScanAvailable() {
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

    Context getCurrentUserContext() {
        return mCurrentUserContext;
    }

    boolean isMediaProfileConnected() {
        if (!mState.oneOf(STATE_ON)) {
            return false;
        }
        return mAdapter.isMediaProfileConnected(mContext.getAttributionSource());
    }

    // Disable ble scan only mode.
    private void disableBleScanMode() {
        if (mState.oneOf(STATE_ON)) {
            Log.d(TAG, "disableBleScanMode: Resetting the mEnable flag for clean disable");
            mEnable = false;
        }
    }

    private int updateBleAppCount(IBinder token, boolean enable, String packageName) {
        String header = "updateBleAppCount(" + token + ", " + enable + ", " + packageName + ")";
        ClientDeathRecipient r = mBleApps.get(token);
        if (r == null && enable) {
            ClientDeathRecipient deathRec = new ClientDeathRecipient(packageName);
            try {
                token.linkToDeath(deathRec, 0);
            } catch (RemoteException ex) {
                throw new IllegalArgumentException("BLE app (" + packageName + ") already dead!");
            }
            mBleApps.put(token, deathRec);
            Log.d(TAG, header + " linkToDeath");
        } else if (!enable && r != null) {
            // Unregister death recipient as the app goes away.
            token.unlinkToDeath(r, 0);
            mBleApps.remove(token);
            Log.d(TAG, header + " unlinkToDeath");
        }
        int appCount = mBleApps.size();
        Log.d(TAG, header + " Number of BLE app registered: appCount=" + appCount);
        return appCount;
    }

    boolean enableBleFromBinder(String packageName, IBinder token) {
        return postAndWait(() -> enableBle(packageName, token));
    }

    @VisibleForTesting
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

        if (!BleScanSettingListener.isScanAllowed()) {
            Log.d(TAG, "enableBle: not enabling - Scan mode is not allowed.");
            return false;
        }

        updateBleAppCount(token, true, packageName);

        if (mState.oneOf(
                STATE_ON,
                STATE_BLE_ON,
                STATE_TURNING_ON,
                STATE_TURNING_OFF,
                STATE_BLE_TURNING_ON)) {
            Log.i(TAG, "enableBle: Bluetooth is already in state" + mState);
            return true;
        }
        sendEnableMsg(false, ENABLE_DISABLE_REASON_APPLICATION_REQUEST, packageName, true);
        return true;
    }

    boolean disableBleFromBinder(String packageName, IBinder token) {
        return postAndWait(() -> disableBle(packageName, token));
    }

    @VisibleForTesting
    boolean disableBle(String packageName, IBinder token) {
        Log.i(
                TAG,
                ("disableBle(" + packageName + ", " + token + "):")
                        + (" mAdapter=" + mAdapter)
                        + (" isBinding=" + isBinding())
                        + (" mState=" + mState));

        if (mState.oneOf(STATE_OFF)) {
            Log.i(TAG, "disableBle: Already disabled");
            return false;
        }
        updateBleAppCount(token, false, packageName);

        if (mState.oneOf(STATE_BLE_ON) && !isBleAppPresent()) {
            if (mEnable) {
                disableBleScanMode();
            }
            if (!mEnableExternal) {
                ActiveLogs.add(ENABLE_DISABLE_REASON_APPLICATION_REQUEST, false, packageName, true);
                sendBrEdrDownCallback();
            }
        }
        return true;
    }

    // Clear all apps using BLE scan only mode.
    private void clearBleApps() {
        mBleApps.clear();
    }

    private boolean isBleAppPresent() {
        Log.d(TAG, "isBleAppPresent(): Number of BLE app registered: " + mBleApps.size());
        return mBleApps.size() > 0;
    }

    /**
     * Will call startBrEdr() if bluetooth classic should be on and will call stopBle if bluetooth
     * BLE should be off
     */
    private void continueFromBleOnState() {
        if (!mState.oneOf(STATE_BLE_ON)) {
            Log.e(TAG, "continueFromBleOnState: Impossible transition from " + mState);
            return;
        }
        if (!mEnableExternal && !isBleAppPresent()) {
            // TODO(b/262605980): this code is unlikely to be trigger and will never be once
            // enableBle & disableBle are executed on the handler
            Log.i(TAG, "continueFromBleOnState: Disabled while enabling BLE, disable BLE now");
            mEnable = false;
            bleOnToOff();
            return;
        }
        if (isBluetoothPersistedStateOnBluetooth() || !isBleAppPresent()) {
            Log.i(TAG, "continueFromBleOnState: Starting br edr");
            // This triggers transition to STATE_ON
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
        if (BleScanSettingListener.isScanAllowed()
                && !AirplaneModeListener.isOn()
                && isBleAppPresent()) {
            // Need to stay at BLE ON. Disconnect all Gatt connections
            Log.i(TAG, "sendBrEdrDownCallback: Staying in BLE_ON");
            try {
                mAdapter.unregAllGattClient(mContext.getAttributionSource());
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
        Counter.logIncrement("bluetooth.value_auto_on_triggered");
        sendToggleNotification("auto_on_bt_enabled_notification");
        enable("BluetoothSystemServer/AutoOn");
        return Unit.INSTANCE;
    }

    boolean enableNoAutoConnectFromBinder(String packageName) {
        return postAndWait(() -> enableNoAutoConnect(packageName));
    }

    @VisibleForTesting
    boolean enableNoAutoConnect(String packageName) {
        if (isSatelliteModeOn()) {
            Log.d(TAG, "enableNoAutoConnect(" + packageName + "): Blocked by satellite mode");
            return false;
        }

        mQuietEnableExternal = true;
        mEnableExternal = true;
        sendEnableMsg(true, ENABLE_DISABLE_REASON_APPLICATION_REQUEST, packageName);
        return true;
    }

    boolean enableFromBinder(String packageName) {
        return postAndWait(() -> enable(packageName));
    }

    @VisibleForTesting
    boolean enable(String packageName) {
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

        mQuietEnableExternal = false;
        mEnableExternal = true;
        AirplaneModeListener.notifyUserToggledBluetooth(
                mContentResolver, mCurrentUserContext, true);
        sendEnableMsg(false, ENABLE_DISABLE_REASON_APPLICATION_REQUEST, packageName);
        return true;
    }

    boolean disableFromBinder(String packageName, boolean persist) {
        return postAndWait(() -> disable(packageName, persist));
    }

    @VisibleForTesting
    boolean disable(String packageName, boolean persist) {
        Log.d(
                TAG,
                ("disable(" + packageName + ", " + persist + "):")
                        + (" mAdapter=" + mAdapter)
                        + (" isBinding=" + isBinding())
                        + (" mState=" + mState));

        AirplaneModeListener.notifyUserToggledBluetooth(
                mContentResolver, mCurrentUserContext, false);

        if (persist) {
            setBluetoothPersistedState(BLUETOOTH_OFF);
        }
        mEnableExternal = false;
        sendDisableMsg(ENABLE_DISABLE_REASON_APPLICATION_REQUEST, packageName);
        return true;
    }

    void unbindAndFinish() {
        Log.d(TAG, "unbindAndFinish(): mAdapter=" + mAdapter + " isBinding=" + isBinding());

        mHandler.removeMessages(MESSAGE_BLUETOOTH_STATE_CHANGE);
        if (mAdapter == null) {
            // mAdapter can be null when Bluetooth crashed and sent SERVICE_DISCONNECTED
            return;
        }
        long currentTimeMs = System.currentTimeMillis();

        try {
            mAdapter.unregisterCallback(mBluetoothCallback, mContext.getAttributionSource());
        } catch (RemoteException e) {
            Log.e(TAG, "unbindAndFinish(): Unable to unregister BluetoothCallback", e);
        }

        CompletableFuture<Void> binderDead = new CompletableFuture<>();
        try {
            mAdapter.getAdapterBinder()
                    .asBinder()
                    .linkToDeath(
                            () -> {
                                Log.i(TAG, "Successfully received Bluetooth death");
                                binderDead.complete(null);
                            },
                            0);
        } catch (RemoteException e) {
            Log.e(TAG, "unbindAndFinish(): Failed to linkToDeath", e);
            binderDead.complete(null);
        }

        // Unbind first to avoid receiving unwanted "onServiceDisconnected"
        mContext.unbindService(mConnection);

        try {
            // Force kill Bluetooth to make sure its process is not reused.
            // Note: In a perfect world, we should be able to re-init the same process.
            // Unfortunately, this requires an heavy rework of the Bluetooth app
            // TODO: b/339501753 - Properly stop Bluetooth without killing it
            mAdapter.killBluetoothProcess();

            binderDead.get(2_000, TimeUnit.MILLISECONDS);
        } catch (android.os.DeadObjectException e) {
            // Reduce exception to info and skip waiting (Bluetooth is dead as wanted)
            Log.i(TAG, "unbindAndFinish(): Bluetooth already dead 💀");
        } catch (RemoteException e) {
            Log.e(TAG, "unbindAndFinish(): Unable to call killBluetoothProcess", e);
        } catch (TimeoutException | InterruptedException | ExecutionException e) {
            Log.e(TAG, "unbindAndFinish(): Bluetooth death not received after > 2000ms", e);
        }

        long timeSpentForShutdown = System.currentTimeMillis() - currentTimeMs;
        mShutdownLatencyHistogram.logSample((float) timeSpentForShutdown);

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
        mHandler.post(() -> internalHandleOnBootPhase(userHandle));
    }

    @VisibleForTesting
    void initialize(UserHandle userHandle) {
        mCurrentUserContext =
                requireNonNull(
                        mContext.createContextAsUser(userHandle, 0),
                        "Current User Context cannot be null");
        AirplaneModeListener.initialize(
                mLooper,
                mContentResolver,
                mState,
                this::onAirplaneModeChanged,
                this::sendToggleNotification,
                this::isMediaProfileConnected,
                this::getCurrentUserContext,
                TimeSource.Monotonic.INSTANCE);

        SatelliteModeListener.initialize(mLooper, mContentResolver, this::onSatelliteModeChanged);
    }

    private void internalHandleOnBootPhase(UserHandle userHandle) {
        Log.d(TAG, "internalHandleOnBootPhase(" + userHandle + "): Bluetooth boot completed");

        initialize(userHandle);

        final boolean isBluetoothDisallowed = isBluetoothDisallowed();
        if (isBluetoothDisallowed) {
            return;
        }
        final boolean isSafeMode = mContext.getPackageManager().isSafeMode();
        if (mEnableExternal && isBluetoothPersistedStateOnBluetooth() && !isSafeMode) {
            Log.i(TAG, "internalHandleOnBootPhase: Auto-enabling Bluetooth.");
            sendEnableMsg(mQuietEnableExternal, ENABLE_DISABLE_REASON_SYSTEM_BOOT);
        } else if (!Flags.removeOneTimeGetNameAndAddress() && !isNameAndAddressSet()) {
            Log.i(TAG, "internalHandleOnBootPhase: Getting adapter name and address");
            mHandler.sendEmptyMessage(MESSAGE_GET_NAME_AND_ADDRESS);
        } else {
            autoOnSetupTimer();
        }
    }

    /** Called when switching to a different foreground user. */
    private void handleSwitchUser(UserHandle userHandle) {
        Log.d(TAG, "handleSwitchUser(" + userHandle + ")");
        mHandler.obtainMessage(MESSAGE_USER_SWITCHED, userHandle).sendToTarget();
    }

    /** Called when user is unlocked. */
    void handleOnUnlockUser(UserHandle userHandle) {
        Log.d(TAG, "handleOnUnlockUser(" + userHandle + ")");
        mHandler.obtainMessage(MESSAGE_USER_UNLOCKED, userHandle).sendToTarget();
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

    private void sendBluetoothServiceUpCallback() {
        broadcastToAdapters(
                "sendBluetoothServiceUpCallback",
                (item) -> item.onBluetoothServiceUp(mAdapter.getAdapterBinder().asBinder()));
    }

    private void sendBluetoothServiceDownCallback() {
        broadcastToAdapters(
                "sendBluetoothServiceDownCallback",
                IBluetoothManagerCallback::onBluetoothServiceDown);
    }

    // Called from unsafe binder thread
    String getAddress() {
        if (Flags.getNameAndAddressAsCallback()) {
            return mAddress;
        }
        // Copy to local variable to avoid race condition when checking for null
        AdapterBinder adapter = mAdapter;
        if (adapter != null) {
            try {
                return adapter.getAddress(mContext.getAttributionSource());
            } catch (RemoteException e) {
                Log.e(TAG, "getAddress(): Returning cached address", e);
            }
        }

        // mAddress is accessed from outside.
        // It is alright without a lock. Here, bluetooth is off, no other thread is
        // changing mAddress
        return mAddress;
    }

    // Called from unsafe binder thread
    String getName() {
        if (Flags.getNameAndAddressAsCallback()) {
            return mName;
        }
        // Copy to local variable to avoid race condition when checking for null
        AdapterBinder adapter = mAdapter;
        if (adapter != null) {
            try {
                return adapter.getName(mContext.getAttributionSource());
            } catch (RemoteException e) {
                Log.e(TAG, "getName(): Returning cached name", e);
            }
        }

        // mName is accessed from outside.
        // It alright without a lock. Here, bluetooth is off, no other thread is
        // changing mName
        return mName;
    }

    @VisibleForTesting
    class BluetoothServiceConnection implements ServiceConnection {
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            String name = componentName.getClassName();
            Log.d(TAG, "ServiceConnection.onServiceConnected(" + name + ", " + service + ")");
            if (!name.equals("com.android.bluetooth.btservice.AdapterService")) {
                Log.e(TAG, "Unknown service connected: " + name);
                return;
            }
            mHandler.obtainMessage(MESSAGE_BLUETOOTH_SERVICE_CONNECTED, service).sendToTarget();
        }

        public void onServiceDisconnected(ComponentName componentName) {
            // Called if we unexpectedly disconnect.
            String name = componentName.getClassName();
            Log.d(TAG, "ServiceConnection.onServiceDisconnected(" + name + ")");
            if (!name.equals("com.android.bluetooth.btservice.AdapterService")) {
                Log.e(TAG, "Unknown service disconnected: " + name);
                return;
            }

            if (Flags.setComponentAvailableFix()) {
                mHandler
                    .obtainMessage(MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED,
                        componentName.getPackageName())
                    .sendToTarget();
            } else {
                mHandler.sendEmptyMessage(MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED);
            }
        }
    }

    private final BluetoothServiceConnection mConnection = new BluetoothServiceConnection();
    private int mWaitForEnableRetry;
    private int mWaitForDisableRetry;

    @VisibleForTesting
    class BluetoothHandler extends Handler {
        // TODO: b/368120237 delete mGetNameAddressOnly
        boolean mGetNameAddressOnly = false;

        BluetoothHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_GET_NAME_AND_ADDRESS:
                    if (Flags.removeOneTimeGetNameAndAddress()) {
                        Log.e(TAG, "MESSAGE_GET_NAME_AND_ADDRESS is not supported anymore");
                        break;
                    }
                    Log.d(TAG, "MESSAGE_GET_NAME_AND_ADDRESS");
                    if (mAdapter == null && !isBinding()) {
                        Log.d(TAG, "Binding to service to get name and address");
                        mGetNameAddressOnly = true;
                        bindToAdapter();
                    } else if (mAdapter != null) {
                        if (!Flags.getNameAndAddressAsCallback()) {
                            try {
                                storeNameAndAddress(
                                        mAdapter.getName(mContext.getAttributionSource()),
                                        mAdapter.getAddress(mContext.getAttributionSource()));
                            } catch (RemoteException e) {
                                Log.e(TAG, "Unable to grab name or address", e);
                            }
                        }
                        if (mGetNameAddressOnly && !mEnable) {
                            unbindAndFinish();
                        }
                        mGetNameAddressOnly = false;
                    }
                    break;

                case MESSAGE_ENABLE:
                    int quietEnable = msg.arg1;
                    int isBle = msg.arg2;

                    Log.d(
                            TAG,
                            ("MESSAGE_ENABLE(quietEnable=" + quietEnable + ", isBle=" + isBle + ")")
                                    + (": mAdapter=" + mAdapter));

                    handleEnableMessage(quietEnable, isBle);

                    break;

                case MESSAGE_DISABLE:
                    Log.d(TAG, "MESSAGE_DISABLE: mAdapter=" + mAdapter);

                    handleDisableMessage();
                    break;

                case MESSAGE_HANDLE_ENABLE_DELAYED:
                    // The Bluetooth is turning off, wait for STATE_OFF
                    if (!mState.oneOf(STATE_OFF)) {
                        if (mWaitForEnableRetry < MAX_WAIT_FOR_ENABLE_DISABLE_RETRIES) {
                            mWaitForEnableRetry++;
                            mHandler.sendEmptyMessageDelayed(
                                    MESSAGE_HANDLE_ENABLE_DELAYED, ENABLE_DISABLE_DELAY_MS);
                            break;
                        } else {
                            Log.e(TAG, "Wait for STATE_OFF timeout");
                        }
                    }
                    // Either state is changed to STATE_OFF or reaches the maximum retry, we
                    // should move forward to the next step.
                    mWaitForEnableRetry = 0;
                    mHandler.sendEmptyMessageDelayed(
                            MESSAGE_RESTART_BLUETOOTH_SERVICE, getServiceRestartMs());
                    Log.d(TAG, "Handle enable is finished");
                    break;

                case MESSAGE_HANDLE_DISABLE_DELAYED:
                    boolean disabling = (msg.arg1 == 1);
                    Log.d(TAG, "MESSAGE_HANDLE_DISABLE_DELAYED: disabling:" + disabling);
                    if (!disabling) {
                        // The Bluetooth is turning on, wait for STATE_ON
                        if (!mState.oneOf(STATE_ON)) {
                            if (mWaitForDisableRetry < MAX_WAIT_FOR_ENABLE_DISABLE_RETRIES) {
                                mWaitForDisableRetry++;
                                mHandler.sendEmptyMessageDelayed(
                                        MESSAGE_HANDLE_DISABLE_DELAYED, ENABLE_DISABLE_DELAY_MS);
                                break;
                            } else {
                                Log.e(TAG, "Wait for STATE_ON timeout");
                            }
                        }
                        // Either state is changed to STATE_ON or reaches the maximum retry, we
                        // should move forward to the next step.
                        mWaitForDisableRetry = 0;
                        mEnable = false;
                        onToBleOn();
                        // Wait for state exiting STATE_ON
                        Message disableDelayedMsg =
                                mHandler.obtainMessage(MESSAGE_HANDLE_DISABLE_DELAYED, 1, 0);
                        mHandler.sendMessageDelayed(disableDelayedMsg, ENABLE_DISABLE_DELAY_MS);
                    } else {
                        // The Bluetooth is turning off, wait for exiting STATE_ON
                        if (mState.oneOf(STATE_ON)) {
                            if (mWaitForDisableRetry < MAX_WAIT_FOR_ENABLE_DISABLE_RETRIES) {
                                mWaitForDisableRetry++;
                                Message disableDelayedMsg =
                                        mHandler.obtainMessage(
                                                MESSAGE_HANDLE_DISABLE_DELAYED, 1, 0);
                                mHandler.sendMessageDelayed(
                                        disableDelayedMsg, ENABLE_DISABLE_DELAY_MS);
                                break;
                            } else {
                                Log.e(TAG, "Wait for exiting STATE_ON timeout");
                            }
                        }
                        // Either state is exited from STATE_ON or reaches the maximum retry, we
                        // should move forward to the next step.
                        Log.d(TAG, "Handle disable is finished");
                    }
                    break;

                case MESSAGE_RESTORE_USER_SETTING_OFF:
                    if (!mEnable) {
                        Log.w(TAG, "RESTORE_USER_SETTING_OFF: Unhandled: already disabled");
                        break;
                    }
                    Log.d(TAG, "RESTORE_USER_SETTING_OFF: set Bluetooth state to disabled");
                    setBluetoothPersistedState(BLUETOOTH_OFF);
                    mEnableExternal = false;
                    sendDisableMsg(ENABLE_DISABLE_REASON_RESTORE_USER_SETTING);
                    break;

                case MESSAGE_RESTORE_USER_SETTING_ON:
                    if (mEnable) {
                        Log.w(TAG, "RESTORE_USER_SETTING_ON: Unhandled: already enabled");
                        break;
                    }
                    Log.d(TAG, "RESTORE_USER_SETTING_ON: set Bluetooth state to enabled");
                    mQuietEnableExternal = false;
                    mEnableExternal = true;
                    sendEnableMsg(false, ENABLE_DISABLE_REASON_RESTORE_USER_SETTING);
                    break;

                case MESSAGE_BLUETOOTH_SERVICE_CONNECTED:
                    IBinder service = (IBinder) msg.obj;
                    Log.d(TAG, "MESSAGE_BLUETOOTH_SERVICE_CONNECTED: service=" + service);

                    // Remove timeout
                    mHandler.removeMessages(MESSAGE_TIMEOUT_BIND);

                    mAdapter = BluetoothServerProxy.getInstance().createAdapterBinder(service);

                    propagateForegroundUserId(ActivityManager.getCurrentUser());

                    if (!Flags.removeOneTimeGetNameAndAddress()) {
                        if (!isNameAndAddressSet()) {
                            mHandler.sendEmptyMessage(MESSAGE_GET_NAME_AND_ADDRESS);
                            if (mGetNameAddressOnly) {
                                return;
                            }
                        }
                    }

                    try {
                        mAdapter.registerCallback(
                                mBluetoothCallback, mContext.getAttributionSource());
                    } catch (RemoteException e) {
                        Log.e(TAG, "Unable to register BluetoothCallback", e);
                    }

                    offToBleOn();
                    sendBluetoothServiceUpCallback();

                    if (!mEnable) {
                        waitForState(STATE_ON);
                        onToBleOn();
                    }
                    break;

                case MESSAGE_BLUETOOTH_STATE_CHANGE:
                    int prevState = msg.arg1;
                    int newState = msg.arg2;
                    Log.d(
                            TAG,
                            "MESSAGE_BLUETOOTH_STATE_CHANGE:"
                                    + (" prevState=" + nameForState(prevState))
                                    + (" newState=" + nameForState(newState)));
                    if (mAdapter == null) {
                        Log.e(TAG, "State change received after bluetooth has crashed");
                        break;
                    }
                    bluetoothStateChangeHandler(prevState, newState);
                    // handle error state transition case from TURNING_ON to OFF
                    // unbind and rebind bluetooth service and enable bluetooth
                    if ((prevState == STATE_BLE_TURNING_ON) && (newState == STATE_OFF) && mEnable) {
                        recoverBluetoothServiceFromError(false);
                    }
                    if ((prevState == STATE_TURNING_ON) && (newState == STATE_BLE_ON) && mEnable) {
                        recoverBluetoothServiceFromError(true);
                    }
                    // If we tried to enable BT while BT was in the process of shutting down,
                    // wait for the BT process to fully tear down and then force a restart
                    // here.  This is a bit of a hack (b/29363429).
                    if (prevState == STATE_BLE_TURNING_OFF && newState == STATE_OFF) {
                        if (mEnable) {
                            Log.d(TAG, "Entering STATE_OFF but mEnabled is true; restarting.");
                            waitForState(STATE_OFF);
                            mHandler.sendEmptyMessageDelayed(
                                    MESSAGE_RESTART_BLUETOOTH_SERVICE, getServiceRestartMs());
                        }
                    }
                    if (newState == STATE_ON || newState == STATE_BLE_ON) {
                        // bluetooth is working, reset the counter
                        if (mErrorRecoveryRetryCounter != 0) {
                            Log.w(TAG, "bluetooth is recovered from error");
                            mErrorRecoveryRetryCounter = 0;
                        }
                    }
                    break;

                case MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED:
                    Log.e(TAG, "MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED");

                    if (Flags.setComponentAvailableFix()) {
                        disableBluetoothComponents((String) msg.obj);
                    }

                    if (!resetAdapter()) {
                        break;
                    }

                    // log the unexpected crash
                    addCrashLog();
                    ActiveLogs.add(ENABLE_DISABLE_REASON_CRASH, false);
                    if (mEnable) {
                        mEnable = false;
                        mHandler.sendEmptyMessageDelayed(
                                MESSAGE_RESTART_BLUETOOTH_SERVICE, getServiceRestartMs());
                    }

                    sendBluetoothServiceDownCallback();

                    // Send BT state broadcast to update
                    // the BT icon correctly
                    if (mState.oneOf(STATE_TURNING_ON, STATE_ON)) {
                        bluetoothStateChangeHandler(STATE_ON, STATE_TURNING_OFF);
                    }
                    if (mState.oneOf(STATE_TURNING_OFF)) {
                        bluetoothStateChangeHandler(STATE_TURNING_OFF, STATE_OFF);
                    }

                    mHandler.removeMessages(MESSAGE_BLUETOOTH_STATE_CHANGE);
                    mState.set(STATE_OFF);
                    break;

                case MESSAGE_RESTART_BLUETOOTH_SERVICE:
                    mErrorRecoveryRetryCounter++;
                    Log.d(
                            TAG,
                            "MESSAGE_RESTART_BLUETOOTH_SERVICE: retry count="
                                    + mErrorRecoveryRetryCounter);
                    if (mErrorRecoveryRetryCounter < MAX_ERROR_RESTART_RETRIES) {
                        /* Enable without persisting the setting as
                         * it doesn't change when IBluetooth
                         * service restarts */
                        mEnable = true;
                        ActiveLogs.add(ENABLE_DISABLE_REASON_RESTARTED, true);
                        handleEnable();
                    } else {
                        resetAdapter();
                        Log.e(TAG, "Reach maximum retry to restart Bluetooth!");
                    }
                    break;

                case MESSAGE_TIMEOUT_BIND:
                    Log.e(TAG, "MESSAGE_TIMEOUT_BIND");
                    // TODO(b/286082382): Timeout should be more than a log. We should at least call
                    // context.unbindService, eventually log a metric with it
                    break;

                case MESSAGE_USER_SWITCHED:
                    UserHandle userTo = (UserHandle) msg.obj;
                    Log.d(TAG, "MESSAGE_USER_SWITCHED: userTo=" + userTo);
                    mHandler.removeMessages(MESSAGE_USER_SWITCHED);

                    AutoOnFeature.pause();

                    mCurrentUserContext = mContext.createContextAsUser(userTo, 0);

                    /* disable and enable BT when detect a user switch */
                    if (mState.oneOf(STATE_ON)) {
                        restartForNewUser(userTo);
                    } else if (isBinding() || mAdapter != null) {
                        Message userMsg = Message.obtain(msg);
                        userMsg.arg1++;
                        // if user is switched when service is binding retry after a delay
                        mHandler.sendMessageDelayed(userMsg, USER_SWITCHED_TIME_MS);
                        Log.d(
                                TAG,
                                "MESSAGE_USER_SWITCHED:"
                                        + (" userTo=" + userTo)
                                        + (" number of retry attempt=" + userMsg.arg1)
                                        + (" isBinding=" + isBinding())
                                        + (" mAdapter=" + mAdapter));
                    } else {
                        autoOnSetupTimer();
                    }
                    break;

                case MESSAGE_USER_UNLOCKED:
                    Log.d(TAG, "MESSAGE_USER_UNLOCKED");
                    mHandler.removeMessages(MESSAGE_USER_SWITCHED);

                    if (mEnable && !isBinding() && (mAdapter == null)) {
                        // We should be connected, but we gave up for some
                        // reason; maybe the Bluetooth service wasn't encryption
                        // aware, so try binding again.
                        Log.d(TAG, "Enabled but not bound; retrying after unlock");
                        handleEnable();
                    }
                    break;
            }
        }

        private void restartForNewUser(UserHandle unusedNewUser) {
            try {
                mAdapter.unregisterCallback(mBluetoothCallback, mContext.getAttributionSource());
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to unregister", e);
            }

            // disable
            ActiveLogs.add(ENABLE_DISABLE_REASON_USER_SWITCH, false);
            onToBleOn();
            // Pbap service need receive STATE_TURNING_OFF intent to close
            bluetoothStateChangeHandler(STATE_ON, STATE_TURNING_OFF);

            boolean didDisableTimeout = !waitForState(STATE_OFF);

            bluetoothStateChangeHandler(STATE_TURNING_OFF, STATE_OFF);

            //
            // If disabling Bluetooth times out, wait for an
            // additional amount of time to ensure the process is
            // shut down completely before attempting to restart.
            //
            if (didDisableTimeout) {
                Log.d(TAG, "Force sleep 3000 ms for user switch that timed out");
                SystemClock.sleep(3000);
            } else {
                Log.d(TAG, "Force sleep 100 ms for");
                SystemClock.sleep(100);
            }

            mHandler.removeMessages(MESSAGE_BLUETOOTH_STATE_CHANGE);
            // enable
            ActiveLogs.add(ENABLE_DISABLE_REASON_USER_SWITCH, true);
            // mEnable flag could have been reset on stopBle. Reenable it.
            mEnable = true;
            handleEnable();
        }
    }

    private boolean isBinding() {
        return mHandler.hasMessages(MESSAGE_TIMEOUT_BIND);
    }

    private void handleEnableMessage(int quietEnable, int isBle) {
        if (mShutdownInProgress) {
            Log.d(TAG, "Skip Bluetooth Enable in device shutdown process");
            return;
        }

        if (mHandler.hasMessages(MESSAGE_HANDLE_DISABLE_DELAYED)
                || mHandler.hasMessages(MESSAGE_HANDLE_ENABLE_DELAYED)) {
            // We are handling enable or disable right now, wait for it.
            mHandler.sendMessageDelayed(
                    mHandler.obtainMessage(MESSAGE_ENABLE, quietEnable, isBle),
                    ENABLE_DISABLE_DELAY_MS);
            return;
        }

        mHandler.removeMessages(MESSAGE_RESTART_BLUETOOTH_SERVICE);
        mEnable = true;

        if (isBle == 0) {
            setBluetoothPersistedState(BLUETOOTH_ON_BLUETOOTH);
        }

        if (mState.oneOf(STATE_BLE_TURNING_ON, STATE_TURNING_ON, STATE_ON)) {
            Log.i(TAG, "MESSAGE_ENABLE: already enabled. Current state=" + mState);
            return;
        }

        if (mState.oneOf(STATE_BLE_ON) && isBle == 1) {
            Log.i(TAG, "MESSAGE_ENABLE: Already in BLE_ON while being requested to go to BLE_ON");
            return;
        }

        if (mState.oneOf(STATE_BLE_ON)) {
            Log.i(TAG, "MESSAGE_ENABLE: Bluetooth transition from STATE_BLE_ON to STATE_ON");
            bleOnToOn();
            return;
        }

        if (mAdapter != null) {
            // TODO: b/339548431 - Adapt this after removal of Flags.explicitKillFromSystemServer
            //
            // We need to wait until transitioned to STATE_OFF and the previous Bluetooth process
            // has exited. The waiting period has three components:
            // (a) Wait until the local state is STATE_OFF. This is accomplished by sending delay a
            //     message MESSAGE_HANDLE_ENABLE_DELAYED
            // (b) Wait until the STATE_OFF state is updated to all components.
            // (c) Wait until the Bluetooth process exits, and ActivityManager detects it.
            //
            // The waiting for (b) and (c) is accomplished by delaying the
            // MESSAGE_RESTART_BLUETOOTH_SERVICE message. The delay time is backed off if Bluetooth
            // continuously failed to turn on itself.
            mWaitForEnableRetry = 0;
            mHandler.sendEmptyMessageDelayed(
                    MESSAGE_HANDLE_ENABLE_DELAYED, ENABLE_DISABLE_DELAY_MS);
            return;
        }

        mQuietEnable = (quietEnable == 1);
        handleEnable();
    }

    private void handleDisableMessage() {
        if (mHandler.hasMessages(MESSAGE_HANDLE_DISABLE_DELAYED)
                || isBinding()
                || mHandler.hasMessages(MESSAGE_HANDLE_ENABLE_DELAYED)) {
            // We are handling enable or disable right now, wait for it.
            mHandler.sendEmptyMessageDelayed(MESSAGE_DISABLE, ENABLE_DISABLE_DELAY_MS);
            return;
        }

        mHandler.removeMessages(MESSAGE_RESTART_BLUETOOTH_SERVICE);

        if (mEnable && mAdapter != null) {
            mWaitForDisableRetry = 0;
            mHandler.sendEmptyMessageDelayed(
                    MESSAGE_HANDLE_DISABLE_DELAYED, ENABLE_DISABLE_DELAY_MS);
        } else {
            mEnable = false;
            onToBleOn();
        }
    }

    private void bindToAdapter() {
        UserHandle user = UserHandle.CURRENT;
        int flags = Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT;
        Intent intent = new Intent(IBluetooth.class.getName());
        ComponentName comp = resolveSystemService(intent);
        if (comp == null && !Flags.enforceResolveSystemServiceBehavior()) {
            Log.e(TAG, "No ComponentName found for intent=" + intent);
            return;
        }
        intent.setComponent(comp);

        mHandler.sendEmptyMessageDelayed(MESSAGE_TIMEOUT_BIND, TIMEOUT_BIND_MS);
        Log.d(TAG, "Start binding to the Bluetooth service with intent=" + intent);
        if (!mContext.bindServiceAsUser(intent, mConnection, flags, user)) {
            Log.e(TAG, "Fail to bind to intent=" + intent);
            mContext.unbindService(mConnection);
            mHandler.removeMessages(MESSAGE_TIMEOUT_BIND);
        }
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
        if (mAdapter == null && !isBinding()) {
            bindToAdapter();
        }
    }

    private void offToBleOn() {
        if (!mState.oneOf(STATE_OFF)) {
            Log.e(TAG, "offToBleOn: Impossible transition from " + mState);
            return;
        }
        Log.d(TAG, "offToBleOn: Sending request");
        try {
            mAdapter.offToBleOn(mQuietEnable, mContext.getAttributionSource());
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to call offToBleOn()", e);
        }
        bluetoothStateChangeHandler(STATE_OFF, STATE_BLE_TURNING_ON);
    }

    private void onToBleOn() {
        if (!mState.oneOf(STATE_ON)) {
            Log.e(TAG, "onToBleOn: Impossible transition from " + mState);
            return;
        }
        Log.d(TAG, "onToBleOn: Sending request");
        try {
            mAdapter.onToBleOn(mContext.getAttributionSource());
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to call onToBleOn()", e);
        }
        bluetoothStateChangeHandler(STATE_ON, STATE_TURNING_OFF);
    }

    private void bleOnToOn() {
        if (!mState.oneOf(STATE_BLE_ON)) {
            Log.e(TAG, "bleOnToOn: Impossible transition from " + mState);
            return;
        }
        Log.d(TAG, "bleOnToOn: sending request");
        try {
            mAdapter.bleOnToOn(mContext.getAttributionSource());
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to call bleOnToOn()", e);
        }
        bluetoothStateChangeHandler(STATE_BLE_ON, STATE_TURNING_ON);
    }

    private void bleOnToOff() {
        if (!mState.oneOf(STATE_BLE_ON)) {
            Log.e(TAG, "bleOnToOff: Impossible transition from " + mState);
            return;
        }
        Log.d(TAG, "bleOnToOff: Sending request");
        try {
            mAdapter.bleOnToOff(mContext.getAttributionSource());
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to call bleOnToOff()", e);
        }
        bluetoothStateChangeHandler(STATE_BLE_ON, STATE_BLE_TURNING_OFF);
    }

    private void broadcastIntentStateChange(String action, int prevState, int newState) {
        Log.d(
                TAG,
                "broadcastIntentStateChange:"
                        + (" action=" + action.substring(action.lastIndexOf('.') + 1))
                        + (" prevState=" + nameForState(prevState))
                        + (" newState=" + nameForState(newState)));
        // Send broadcast message to everyone else
        Intent intent = new Intent(action);
        intent.putExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, prevState);
        intent.putExtra(BluetoothAdapter.EXTRA_STATE, newState);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        mContext.sendBroadcastAsUser(
                intent, UserHandle.ALL, null, getTempAllowlistBroadcastOptions());
    }

    private static boolean isBleState(int state) {
        switch (state) {
            case STATE_BLE_ON:
            case STATE_BLE_TURNING_ON:
            case STATE_BLE_TURNING_OFF:
                return true;
        }
        return false;
    }

    private void bluetoothStateChangeHandler(int prevState, int newState) {
        if (mState.oneOf(newState)) { // Already in correct state
            Log.d(TAG, "bluetoothStateChangeHandler: Already in state " + mState);
            return;
        }

        if (newState == STATE_OFF) {
            // If Bluetooth is off, send service down event to proxy objects, and unbind
            Log.d(TAG, "bluetoothStateChangeHandler: Bluetooth is OFF send Service Down");
            sendBluetoothServiceDownCallback();
            unbindAndFinish();
        }

        mState.set(newState);

        broadcastIntentStateChange(BluetoothAdapter.ACTION_BLE_STATE_CHANGED, prevState, newState);

        // BLE state are shown as STATE_OFF for BrEdr users
        final int prevBrEdrState = isBleState(prevState) ? STATE_OFF : prevState;
        final int newBrEdrState = isBleState(newState) ? STATE_OFF : newState;

        if (prevBrEdrState != newBrEdrState) { // Only broadcast when there is a BrEdr state change.
            if (newBrEdrState == STATE_OFF) {
                sendBluetoothOffCallback();
                sendBrEdrDownCallback();
            }
            broadcastIntentStateChange(
                    BluetoothAdapter.ACTION_STATE_CHANGED, prevBrEdrState, newBrEdrState);
        }

        if (prevState == STATE_ON) {
            autoOnSetupTimer();
        }

        // Notify all proxy objects first of adapter state change
        if (newState == STATE_ON) {
            if (isAtLeastV() && mDeviceConfigAllowAutoOn) {
                AutoOnFeature.notifyBluetoothOn(mCurrentUserContext);
            }
            sendBluetoothOnCallback();
        } else if (newState == STATE_BLE_ON && prevState == STATE_BLE_TURNING_ON) {
            continueFromBleOnState();
        } // Nothing specific to do for STATE_TURNING_<X>
    }

    boolean waitForManagerState(int state) {
        return mState.waitForState(STATE_TIMEOUT, state);
    }

    private boolean waitForState(int... states) {
        Log.d(TAG, "Waiting " + STATE_TIMEOUT + " for state: " + Arrays.toString(states));
        return mState.waitForState(STATE_TIMEOUT, states);
    }

    private void sendDisableMsg(int reason) {
        sendDisableMsg(reason, mContext.getPackageName());
    }

    private void sendDisableMsg(int reason, String packageName) {
        mHandler.sendEmptyMessage(MESSAGE_DISABLE);
        ActiveLogs.add(reason, false, packageName, false);
    }

    private void sendEnableMsg(boolean quietMode, int reason) {
        sendEnableMsg(quietMode, reason, mContext.getPackageName());
    }

    private void sendEnableMsg(boolean quietMode, int reason, String packageName) {
        sendEnableMsg(quietMode, reason, packageName, false);
    }

    private void sendEnableMsg(boolean quietMode, int reason, String packageName, boolean isBle) {
        mHandler.obtainMessage(MESSAGE_ENABLE, quietMode ? 1 : 0, isBle ? 1 : 0).sendToTarget();
        ActiveLogs.add(reason, true, packageName, isBle);
        mLastEnabledTime = SystemClock.elapsedRealtime();
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
                mAdapter.unregisterCallback(mBluetoothCallback, mContext.getAttributionSource());
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to unregister", e);
            }
        }

        Log.d(TAG, "Force sleep 500 ms for recovering from error");
        SystemClock.sleep(500);

        // disable
        ActiveLogs.add(ENABLE_DISABLE_REASON_START_ERROR, false);
        onToBleOn();

        waitForState(STATE_OFF);

        sendBluetoothServiceDownCallback();

        resetAdapter();

        mHandler.removeMessages(MESSAGE_BLUETOOTH_STATE_CHANGE);
        mState.set(STATE_OFF);

        if (clearBle) {
            clearBleApps();
        }

        mEnable = false;

        // Send a Bluetooth Restart message to reenable bluetooth
        mHandler.sendEmptyMessageDelayed(MESSAGE_RESTART_BLUETOOTH_SERVICE, ERROR_RESTART_TIME_MS);

        if (repeatAirplaneRunnable) {
            onAirplaneModeChanged(AirplaneModeListener.isOnOverrode());
        }
    }

    private boolean isBluetoothDisallowed() {
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

    private int getServiceRestartMs() {
        return (mErrorRecoveryRetryCounter + 1) * SERVICE_RESTART_TIME_MS;
    }

    void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        if ((args.length > 0) && args[0].startsWith("--proto")) {
            dumpProto(fd);
            return;
        }
        String errorMsg = null;

        writer.println("Bluetooth Status");
        writer.println("  enabled: " + isEnabled());
        writer.println("  state: " + mState);
        writer.println("  address: " + logAddress(mAddress));
        writer.println("  name: " + mName);
        if (mEnable) {
            long onDuration = SystemClock.elapsedRealtime() - mLastEnabledTime;
            String onDurationString =
                    android.bluetooth.BluetoothUtils.formatSimple(
                            "%02d:%02d:%02d.%03d",
                            onDuration / (1000 * 60 * 60),
                            (onDuration / (1000 * 60)) % 60,
                            (onDuration / 1000) % 60,
                            onDuration % 1000);
            writer.println("  time since enabled: " + onDurationString);
        }

        writer.println("");
        ActiveLogs.dump(writer);

        writer.println("");

        writer.println("Bluetooth crashed " + mCrashes + " time" + (mCrashes == 1 ? "" : "s"));
        if (mCrashes == CRASH_LOG_MAX_SIZE) {
            writer.println("(last " + CRASH_LOG_MAX_SIZE + ")");
        }
        for (Long time : mCrashTimestamps) {
            writer.println("  " + Log.timeToStringWithZone(time));
        }

        writer.println("");
        writer.println("Number of Ble app registered: " + mBleApps.size());
        for (ClientDeathRecipient app : mBleApps.values()) {
            writer.println("  " + app.getPackageName());
        }

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
        Arrays.stream(Flags.class.getDeclaredMethods())
                .forEach(
                        (Method m) -> {
                            String name =
                                    m.getName().replaceAll("([A-Z])", "_$1").toLowerCase(Locale.US);
                            boolean flagValue;
                            try {
                                flagValue = (boolean) m.invoke(null);
                            } catch (IllegalAccessException | InvocationTargetException e) {
                                writer.println("Cannot invoke " + name + " flag:" + e);
                                throw new RuntimeException(e);
                            }
                            writer.println("\t" + (flagValue ? "[■]" : "[ ]") + ": " + name);
                        });
    }

    private void dumpProto(FileDescriptor fd) {
        final ProtoOutputStream proto = new ProtoOutputStream(new FileOutputStream(fd));
        proto.write(BluetoothManagerServiceDumpProto.ENABLED, isEnabled());
        proto.write(BluetoothManagerServiceDumpProto.STATE, mState.get());
        proto.write(BluetoothManagerServiceDumpProto.STATE_NAME, nameForState(mState.get()));
        proto.write(BluetoothManagerServiceDumpProto.ADDRESS, logAddress(mAddress));
        proto.write(BluetoothManagerServiceDumpProto.NAME, mName);
        if (mEnable) {
            proto.write(BluetoothManagerServiceDumpProto.LAST_ENABLED_TIME_MS, mLastEnabledTime);
        }
        proto.write(
                BluetoothManagerServiceDumpProto.CURR_TIMESTAMP_MS, SystemClock.elapsedRealtime());
        ActiveLogs.dumpProto(proto);
        proto.write(BluetoothManagerServiceDumpProto.NUM_CRASHES, mCrashes);
        proto.write(
                BluetoothManagerServiceDumpProto.CRASH_LOG_MAXED, mCrashes == CRASH_LOG_MAX_SIZE);
        for (Long time : mCrashTimestamps) {
            proto.write(BluetoothManagerServiceDumpProto.CRASH_TIMESTAMPS_MS, time);
        }
        proto.write(BluetoothManagerServiceDumpProto.NUM_BLE_APPS, mBleApps.size());
        for (ClientDeathRecipient app : mBleApps.values()) {
            proto.write(
                    BluetoothManagerServiceDumpProto.BLE_APP_PACKAGE_NAMES, app.getPackageName());
        }
        proto.flush();
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

    private ComponentName legacyresolveSystemService(@NonNull Intent intent) {
        List<ResolveInfo> results = mContext.getPackageManager().queryIntentServices(intent, 0);
        if (results == null) {
            return null;
        }
        ComponentName comp = null;
        for (int i = 0; i < results.size(); i++) {
            ResolveInfo ri = results.get(i);
            if ((ri.serviceInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                continue;
            }
            ComponentName foundComp =
                    new ComponentName(
                            ri.serviceInfo.applicationInfo.packageName, ri.serviceInfo.name);
            if (comp != null) {
                throw new IllegalStateException(
                        "Multiple system services handle "
                                + intent
                                + ": "
                                + comp
                                + ", "
                                + foundComp);
            }
            comp = foundComp;
        }
        return comp;
    }

    private ComponentName resolveSystemService(@NonNull Intent intent) {
        if (!Flags.enforceResolveSystemServiceBehavior()) {
            return legacyresolveSystemService(intent);
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
        switch (results.size()) {
            case 0 -> throw new IllegalStateException("No services can handle intent " + intent);
            case 1 -> {
                return results.get(0);
            }
            default -> {
                throw new IllegalStateException(
                        "Multiples services can handle intent " + intent + ": " + results);
            }
        }
    }

    int setBtHciSnoopLogMode(int mode) {
        final BluetoothProperties.snoop_log_mode_values snoopMode;

        switch (mode) {
            case BluetoothAdapter.BT_SNOOP_LOG_MODE_DISABLED:
                snoopMode = BluetoothProperties.snoop_log_mode_values.DISABLED;
                break;
            case BluetoothAdapter.BT_SNOOP_LOG_MODE_FILTERED:
                snoopMode = BluetoothProperties.snoop_log_mode_values.FILTERED;
                break;
            case BluetoothAdapter.BT_SNOOP_LOG_MODE_FULL:
                snoopMode = BluetoothProperties.snoop_log_mode_values.FULL;
                break;
            default:
                throw new IllegalArgumentException("Invalid HCI snoop log mode param value");
        }
        try {
            BluetoothProperties.snoop_log_mode(snoopMode);
        } catch (RuntimeException e) {
            Log.e(TAG, "setBtHciSnoopLogMode: Failed to set mode to " + mode + ": " + e);
            return BluetoothStatusCodes.ERROR_UNKNOWN;
        }
        return BluetoothStatusCodes.SUCCESS;
    }

    int getBtHciSnoopLogMode() {
        BluetoothProperties.snoop_log_mode_values mode =
                BluetoothProperties.snoop_log_mode()
                        .orElse(BluetoothProperties.snoop_log_mode_values.DISABLED);
        if (mode == BluetoothProperties.snoop_log_mode_values.FILTERED) {
            return BluetoothAdapter.BT_SNOOP_LOG_MODE_FILTERED;
        } else if (mode == BluetoothProperties.snoop_log_mode_values.FULL) {
            return BluetoothAdapter.BT_SNOOP_LOG_MODE_FULL;
        }
        return BluetoothAdapter.BT_SNOOP_LOG_MODE_DISABLED;
    }

    private final boolean mDeviceConfigAllowAutoOn;

    private void autoOnSetupTimer() {
        if (!mDeviceConfigAllowAutoOn) {
            Log.d(TAG, "No support for AutoOn feature: Not creating a timer");
            return;
        }
        AutoOnFeature.resetAutoOnTimerForUser(
                mLooper, mCurrentUserContext, mState, this::enableFromAutoOn);
    }

    private <T> T postAndWait(Callable<T> callable) {
        FutureTask<T> task = new FutureTask(callable);

        mHandler.post(task);
        try {
            // Any method calling postAndWait should most likely be done in under 1 seconds.
            // But real life shows that the system server thread may sometimes be unwillingly busy.
            // By putting a 10 seconds timeout we make sure this will generate an ANR (on purpose),
            // and investigation on what is happening in the system server thread and be fixed
            return task.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException | InterruptedException e) {
            SneakyThrow.sneakyThrow(e);
        } catch (ExecutionException e) {
            SneakyThrow.sneakyThrow(e.getCause());
        }
        return null;
    }

    boolean isAutoOnSupported() {
        return mDeviceConfigAllowAutoOn
                && postAndWait(
                        () ->
                                AutoOnFeature.isUserSupported(
                                        mCurrentUserContext.getContentResolver()));
    }

    boolean isAutoOnEnabled() {
        if (!mDeviceConfigAllowAutoOn) {
            throw new IllegalStateException("AutoOnFeature is not supported in current config");
        }
        return postAndWait(() -> AutoOnFeature.isUserEnabled(mCurrentUserContext));
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    void setAutoOnEnabled(boolean status) {
        if (!mDeviceConfigAllowAutoOn) {
            throw new IllegalStateException("AutoOnFeature is not supported in current config");
        }
        postAndWait(
                Executors.callable(
                        () ->
                                AutoOnFeature.setUserEnabled(
                                        mLooper,
                                        mCurrentUserContext,
                                        mState,
                                        status,
                                        this::enableFromAutoOn)));
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
     * In case of a Bluetooth crash, mark it's enabled components as non longer available to
     * trigger the PACKAGE_CHANGED intent. This should not be needed in a normal shutdown as the
     * Bluetooth clean its components on its own
     */
    private void disableBluetoothComponents(String packageName) {
        PackageManager pm = mContext.getPackageManager();
        PackageInfo packageInfo = null;

        try {
            packageInfo = pm.getPackageInfo(
                    packageName,
                    PackageManager.GET_SERVICES |
                            PackageManager.GET_ACTIVITIES |
                            PackageManager.GET_RECEIVERS |
                            PackageManager.GET_PROVIDERS);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Package not found: " + packageName, e);
            return;
        }

        // Refer to updateOppLauncherComponentState()
        List<String> baseBluetoothOppActivities = List.of(
            "com.android.bluetooth.opp.BluetoothOppLauncherActivity",
            "com.android.bluetooth.opp.BluetoothOppBtEnableActivity",
            "com.android.bluetooth.opp.BluetoothOppBtEnablingActivity",
            "com.android.bluetooth.opp.BluetoothOppBtErrorActivity"
        );

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
            .filter(componentName ->
                        (componentsToKeep == null ||
                            !componentsToKeep.contains(componentName.getClassName())))
            .forEach(componentName -> {
                pm.setComponentEnabledSetting(
                    componentName,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
                Log.i(TAG, "Disabled component: " + componentName.flattenToString());
            });
    }
}
