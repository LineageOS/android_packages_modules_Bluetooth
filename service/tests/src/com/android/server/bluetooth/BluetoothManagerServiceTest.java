/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static android.bluetooth.IBluetoothManager.ACTION_BLE_STATE_CHANGED;
import static android.bluetooth.IBluetoothManager.ACTION_STATE_CHANGED;
import static android.bluetooth.IBluetoothManager.EXTRA_PREVIOUS_STATE;
import static android.bluetooth.IBluetoothManager.EXTRA_STATE;

import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;

import static com.android.server.bluetooth.BluetoothManagerService.MESSAGE_BLUETOOTH_SERVICE_CONNECTED;
import static com.android.server.bluetooth.BluetoothManagerService.MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED;
import static com.android.server.bluetooth.BluetoothManagerService.MESSAGE_BLUETOOTH_STATE_CHANGE;
import static com.android.server.bluetooth.BluetoothManagerService.MESSAGE_RESTART_BLUETOOTH_SERVICE;
import static com.android.server.bluetooth.BluetoothManagerService.MESSAGE_RESTORE_USER_SETTING_OFF;
import static com.android.server.bluetooth.BluetoothManagerService.MESSAGE_TIMEOUT_BIND;
import static com.android.server.bluetooth.BluetoothManagerService.SERVICE_RESTART_DELAY;
import static com.android.server.bluetooth.BluetoothManagerService.TIMEOUT_BIND;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.ignoreStubs;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.role.RoleManager;
import android.bluetooth.IAdapter;
import android.bluetooth.IBluetoothCallback;
import android.bluetooth.IBluetoothManager;
import android.bluetooth.IBluetoothManagerCallback;
import android.bluetooth.State;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.IpcDataCache;
import android.os.Message;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.test.TestLooper;
import android.permission.PermissionManager;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.sysprop.BluetoothProperties;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.util.TimeProvider;
import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.tests.bluetooth.FlagsWrapper;
import com.android.tests.bluetooth.StaticMockitoRule;

import org.hamcrest.Matcher;
import org.hamcrest.core.AllOf;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.hamcrest.MockitoHamcrest;

import platform.test.runner.parameterized.ParameterizedAndroidJunit4;
import platform.test.runner.parameterized.Parameters;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

@RunWith(ParameterizedAndroidJunit4.class)
public class BluetoothManagerServiceTest {
    @Rule public final SetFlagsRule mSetFlagsRule;

    @Rule
    public final StaticMockitoRule mMockitoRule =
            new StaticMockitoRule(BluetoothProperties.class, ActivityManager.class);

    @Parameters(name = "{0}")
    public static List<FlagsWrapper> getParams() {
        return FlagsWrapper.progressionOf(
                com.android.bluetooth.beta.flags.Flags.FLAG_SYSTEM_SERVER_DIRECT_SWITCH);
    }

    private static final UserHandle DEFAULT_USER = UserHandle.of(42);
    private static final UserHandle OTHER_USER = UserHandle.of(43);

    public BluetoothManagerServiceTest(FlagsWrapper flagsWrapper) {
        mSetFlagsRule = new SetFlagsRule(flagsWrapper.getFlags());
    }

    private final Context mTargetContext =
            InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final BluetoothComponent mBluetoothComponent = new BluetoothComponent(mTargetContext);

    @Mock BluetoothServerProxy mBluetoothServerProxy;
    @Mock Context mContext;
    @Mock UserManager mUserManager;
    @Mock RoleManager mRoleManager;
    @Mock IBinder mBleBinder;
    @Mock IBinder mBinder;
    @Mock IBluetoothManagerCallback mManagerCallback;
    @Mock IAdapter mAdapterService;
    @Mock AdapterBinder mAdapterBinder;
    @Mock AppOpsManager mAppOpsManager;
    @Mock PermissionManager mPermissionManager;
    @Mock TimeProvider mTimeProvider;

    private int mPersistedState = BluetoothManagerService.BLUETOOTH_OFF;

    private InOrder mInOrder;
    private TestLooper mLooper;
    private BluetoothManagerService mManagerService;
    private UserHandle mCurrentUser;

    private static class ServerQuery extends IpcDataCache.QueryHandler<IBluetoothManager, Integer> {
        @Override
        public Integer apply(IBluetoothManager unusedManager) {
            return -1;
        }

        @Override
        public boolean shouldBypassCache(IBluetoothManager unusedManager) {
            return true;
        }
    }

    static {
        // Required for reading DeviceConfig.
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .adoptShellPermissionIdentity(
                        android.Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE);
    }

    @Before
    public void setUp() throws Exception {
        mInOrder = inOrder(mContext, mManagerCallback, mAdapterBinder);
        ExtendedMockito.doNothing().when(() -> BluetoothProperties.factory_reset(true));
        mCurrentUser = DEFAULT_USER;
        ExtendedMockito.doAnswer(
                        inv -> {
                            return mCurrentUser.getIdentifier();
                        })
                .when(() -> ActivityManager.getCurrentUser());

        IpcDataCache<IBluetoothManager, Integer> testCache =
                new IpcDataCache<>(
                        8,
                        IBluetoothManager.IPC_CACHE_MODULE_SYSTEM,
                        IBluetoothManager.GET_SYSTEM_STATE_API,
                        IBluetoothManager.GET_SYSTEM_STATE_API,
                        new ServerQuery());
        BluetoothAdapterState.disableCacheForTesting = true;
        IpcDataCache.setCacheTestMode(true);
        testCache.disableForCurrentProcess();
        // Mock these functions so security errors won't throw
        doReturn("name")
                .when(mBluetoothServerProxy)
                .settingsSecureGetString(any(), eq("bluetooth_name"));
        doReturn("00:11:22:33:44:55")
                .when(mBluetoothServerProxy)
                .settingsSecureGetString(any(), eq("bluetooth_address"));
        doAnswer(
                        inv -> {
                            return mPersistedState;
                        })
                .when(mBluetoothServerProxy)
                .getBluetoothPersistedState(any(), anyInt());

        doAnswer(
                        inv -> {
                            mPersistedState = inv.getArgument(1);
                            return null;
                        })
                .when(mBluetoothServerProxy)
                .setBluetoothPersistedState(any(), anyInt());

        doAnswer(
                        inv -> {
                            IBinder.DeathRecipient recipient = inv.getArgument(0);
                            recipient.binderDied();
                            return null;
                        })
                .when(mBinder)
                .linkToDeath(any(), anyInt());

        doReturn(List.of("Foo")).when(mRoleManager).getRoleHolders(any());

        doReturn(BluetoothManagerServiceTest.class.getSimpleName()).when(mContext).getPackageName();
        doReturn(mContext).when(mContext).createContextAsUser(any(), anyInt());
        doReturn(mTargetContext.getContentResolver()).when(mContext).getContentResolver();
        doReturn(mTargetContext.getPackageManager()).when(mContext).getPackageManager();
        doReturn(mTargetContext.getAttributionSource()).when(mContext).getAttributionSource();
        doReturn(mRoleManager).when(mContext).getSystemService(RoleManager.class);
        doReturn(mUserManager).when(mContext).getSystemService(UserManager.class);
        doReturn(mAppOpsManager).when(mContext).getSystemService(AppOpsManager.class);
        doReturn(mPermissionManager).when(mContext).getSystemService(PermissionManager.class);
        doReturn(null).when(mContext).registerReceiver(any(), any(), any(), any());
        doNothing().when(mContext).unregisterReceiver(any());
        doReturn(null).when(mContext).registerReceiverForAllUsers(any(), any(), any(), any());

        doReturn(mBinder).when(mManagerCallback).asBinder();

        doReturn(mAdapterBinder).when(mBluetoothServerProxy).createAdapterBinder(any());
        doReturn(mAdapterService).when(mAdapterBinder).getAdapterBinder();
        doReturn(mBinder).when(mAdapterService).asBinder();

        doReturn(true).when(mContext).bindServiceAsUser(any(), any(), anyInt(), any());
        doNothing().when(mContext).unbindService(any());

        BluetoothServerProxy.setInstanceForTesting(mBluetoothServerProxy);

        mLooper = new TestLooper(() -> 0L);

        mManagerService =
                new BluetoothManagerService(
                        mContext,
                        mLooper.getLooper(),
                        "default",
                        mBluetoothComponent,
                        mTimeProvider);
        doReturn(false).when(mUserManager).hasUserRestriction(eq(UserManager.DISALLOW_BLUETOOTH));
        BluetoothRestriction.initialize(
                mContext, mLooper.getLooper(), mManagerService::onRestrictionChange);

        mManagerService.handleOnBootPhase(DEFAULT_USER);
        mManagerService.registerAdapter(mManagerCallback);
    }

    @After
    public void tearDown() {
        IpcDataCache.setCacheTestMode(false);
        BluetoothAdapterState.disableCacheForTesting = false;
    }

    private void endTest() {
        mLooper.moveTimeForward(200_000);
        assertThat(mLooper.nextMessage()).isNull();
        verifyNoMoreInteractions(ignoreStubs(mContext));
    }

    /**
     * Dispatch all the message on the Looper and check that the what is expected
     *
     * @param what list of message that are expected to be run by the handler
     */
    private void syncHandler(int what) {
        Message msg = mLooper.nextMessage();
        assertWithMessage("Expecting [" + what + "] instead of null Msg").that(msg).isNotNull();
        if (msg.what != what) {
            List<Message> msgList = new ArrayList<>();

            Message nextMsg;
            while ((nextMsg = mLooper.nextMessage()) != null) {
                msgList.add(nextMsg);
            }

            String customError =
                    "Not the expected message."
                            + (" Expected what=[" + what + "] but got what=[" + msg.what + "].\n")
                            + ("  -> Received Msg: " + msg + "\n")
                            + ("  -> List of queued messages: " + msgList);

            assertWithMessage(customError).that(msg.what).isEqualTo(what);
        }
        Log.d("BluetoothManagerServiceTest", "Processing message: " + msg);
        msg.getTarget().dispatchMessage(msg);
    }

    private void discardMessage(int... what) {
        IntStream.of(what)
                .forEach(
                        w -> {
                            Message msg = mLooper.nextMessage();
                            assertThat(msg).isNotNull();
                            assertThat(msg.what).isEqualTo(w);
                            // Drop the message
                        });
    }

    @Test
    public void onUserRestrictionsChanged_whenOn_turnOff() throws Exception {
        mManagerService.enable(0, "onUserRestrictionsChanged_whenOn_turnOff");
        IBluetoothCallback btCallback = transition_offToOn();

        doReturn(true).when(mUserManager).hasUserRestriction(eq(UserManager.DISALLOW_BLUETOOTH));

        BluetoothRestriction.handleRestrictionChange(
                mContext, mManagerService::onRestrictionChange);
        transition_onToOff(btCallback);
        assertThat(mManagerService.getState()).isEqualTo(State.OFF);

        endTest();
    }

    @Test
    public void enable_bindFailure_removesTimeoutAndStaysOff() throws Exception {
        doReturn(false).when(mContext).bindServiceAsUser(any(), any(), anyInt(), any());
        mManagerService.enableBle("enable_bindFailure_removesTimeout", mBleBinder);
        verifyBleStateIntentSent(State.OFF, State.BLE_TURNING_ON);
        mInOrder.verify(mContext).unbindService(any());
        verifyBleStateIntentSent(State.BLE_TURNING_ON, State.OFF);

        assertThat(mManagerService.getState()).isEqualTo(State.OFF);

        endTest();
    }

    @Test
    public void enable_beforeBootCompleted_extendedBindTimeout() throws Exception {
        mManagerService.enableBle("enable_beforeBootCompleted_extendedBindTimeout", mBleBinder);
        verifyBleStateIntentSent(State.OFF, State.BLE_TURNING_ON);

        mLooper.moveTimeForward(TIMEOUT_BIND.multipliedBy(20).toMillis() - 1);
        assertThat(mLooper.nextMessage()).isNull();
        mLooper.moveTimeForward(1);
        syncHandler(MESSAGE_TIMEOUT_BIND);

        mInOrder.verify(mContext).unbindService(any());
        verifyBleStateIntentSent(State.BLE_TURNING_ON, State.OFF);

        mLooper.moveTimeForward(120_000);
        discardMessage(MESSAGE_RESTART_BLUETOOTH_SERVICE); // verify recovery process is started

        endTest();
    }

    @Test
    public void enable_afterBootCompleted_bindTimeout() throws Exception {
        mManagerService.onBootCompleted();
        mManagerService.enableBle("enable_afterBootCompleted_bindTimeout", mBleBinder);
        verifyBleStateIntentSent(State.OFF, State.BLE_TURNING_ON);

        mLooper.moveTimeForward(TIMEOUT_BIND.toMillis());
        syncHandler(MESSAGE_TIMEOUT_BIND);

        mInOrder.verify(mContext).unbindService(any());
        verifyBleStateIntentSent(State.BLE_TURNING_ON, State.OFF);

        mLooper.moveTimeForward(120_000);
        discardMessage(MESSAGE_RESTART_BLUETOOTH_SERVICE); // verify recovery process is started

        endTest();
    }

    @Test
    public void onBootCompleted_whileBinding_rescheduleTimeout() throws Exception {
        mManagerService.enableBle("onBootCompleted_whileBinding_rescheduleTimeout", mBleBinder);
        verifyBleStateIntentSent(State.OFF, State.BLE_TURNING_ON);

        mLooper.moveTimeForward(TIMEOUT_BIND.multipliedBy(20).toMillis() - 1);
        assertThat(mLooper.nextMessage()).isNull();
        mManagerService.onBootCompleted();
        mLooper.moveTimeForward(TIMEOUT_BIND.toMillis() - 1);
        assertThat(mLooper.nextMessage()).isNull();
        mLooper.moveTimeForward(1);
        syncHandler(MESSAGE_TIMEOUT_BIND);

        mInOrder.verify(mContext).unbindService(any());
        verifyBleStateIntentSent(State.BLE_TURNING_ON, State.OFF);

        // Calculate the expected delay for the first retry after a timeout.
        // It should be SERVICE_RESTART_DELAY * 1 (retry) * 10 (for timeout).
        Duration expectedDelay = SERVICE_RESTART_DELAY.multipliedBy(10);

        // Check that the restart message is scheduled with the correct delay.
        mLooper.moveTimeForward(expectedDelay.toMillis() - 1);
        assertThat(mLooper.nextMessage()).isNull();
        mLooper.moveTimeForward(1);
        syncHandler(MESSAGE_RESTART_BLUETOOTH_SERVICE);

        // Let the restart proceed to ensure no other issues.
        transition_offToBleOn();
        assertThat(mManagerService.getState()).isEqualTo(State.BLE_ON);

        endTest();
    }

    private ServiceConnection acceptBluetoothBinding() {
        verifyBleStateIntentSent(State.OFF, State.BLE_TURNING_ON);

        ComponentName compName =
                new ComponentName("", "com.android.bluetooth.btservice.AdapterService");

        var captor = ArgumentCaptor.forClass(ServiceConnection.class);
        mInOrder.verify(mContext)
                .bindServiceAsUser(any(), captor.capture(), anyInt(), eq(mCurrentUser));
        assertThat(captor.getAllValues()).hasSize(1);

        var serviceConnection = captor.getAllValues().get(0);
        serviceConnection.onServiceConnected(compName, mBinder);
        syncHandler(MESSAGE_BLUETOOTH_SERVICE_CONNECTED);
        return serviceConnection;
    }

    private IBluetoothCallback captureBluetoothCallback() throws Exception {
        var captor = ArgumentCaptor.forClass(IBluetoothCallback.class);
        mInOrder.verify(mAdapterBinder).registerCallback(captor.capture());
        mInOrder.verify(mAdapterBinder).offToBleOn(anyBoolean(), anyString());
        assertThat(mManagerService.getState()).isEqualTo(State.BLE_TURNING_ON);
        assertThat(captor.getAllValues()).hasSize(1);
        return captor.getValue();
    }

    IBluetoothCallback transition_offToBleOn() throws Exception {
        acceptBluetoothBinding();

        IBluetoothCallback btCallback = captureBluetoothCallback();
        btCallback.setAdapterServiceBinder(mBinder);
        syncHandler(0); // To post setAdapterServiceBinder
        mInOrder.verify(mManagerCallback).onBluetoothServiceUp(mBinder);

        // GattService has been started by AdapterService and it will enable native side then
        // trigger the stateChangeCallback from native
        btCallback.onBluetoothStateChange(State.BLE_TURNING_ON, State.BLE_ON);
        syncHandler(MESSAGE_BLUETOOTH_STATE_CHANGE);
        verifyBleStateIntentSent(State.BLE_TURNING_ON, State.BLE_ON);
        return btCallback;
    }

    private IBluetoothCallback transition_offToOn() throws Exception {
        IBluetoothCallback btCallback = transition_offToBleOn();
        mInOrder.verify(mAdapterBinder).bleOnToOn();
        verifyBleStateIntentSent(State.BLE_ON, State.TURNING_ON);
        verifyStateIntentSent(State.OFF, State.TURNING_ON);

        // Emulate AdapterService that completed starting all profiles
        btCallback.onBluetoothStateChange(State.TURNING_ON, State.ON);
        syncHandler(MESSAGE_BLUETOOTH_STATE_CHANGE);
        verifyBleStateIntentSent(State.TURNING_ON, State.ON);
        verifyStateIntentSent(State.TURNING_ON, State.ON);

        return btCallback;
    }

    private void transition_onToTurningOff() throws Exception {
        mInOrder.verify(mAdapterBinder).onToBleOn();
        verifyBleStateIntentSent(State.ON, State.TURNING_OFF);
        verifyStateIntentSent(State.ON, State.TURNING_OFF);
    }

    private void transition_turningOffToBleTurningOff() throws Exception {
        syncHandler(MESSAGE_BLUETOOTH_STATE_CHANGE);
        verifyBleStateIntentSent(State.TURNING_OFF, State.BLE_TURNING_OFF);
        verifyStateIntentSent(State.TURNING_OFF, State.OFF);
    }

    private void transition_bleTurningOffToOff() throws Exception {
        syncHandler(MESSAGE_BLUETOOTH_STATE_CHANGE);
        verifyBleStateIntentSent(State.BLE_TURNING_OFF, State.OFF);
    }

    private void transition_bleOnToOff(IBluetoothCallback btCallback) throws Exception {
        mInOrder.verify(mAdapterBinder).bleOnToOff();
        verifyBleStateIntentSent(State.BLE_ON, State.BLE_TURNING_OFF);

        // Emulate AdapterService that completed stopping all profiles
        btCallback.onBluetoothStateChange(State.BLE_TURNING_OFF, State.OFF);
        syncHandler(MESSAGE_BLUETOOTH_STATE_CHANGE);
        verifyBleStateIntentSent(State.BLE_TURNING_OFF, State.OFF);
    }

    private void transition_onToOff(IBluetoothCallback btCallback) throws Exception {
        transition_onToTurningOff();

        btCallback.onBluetoothStateChange(State.TURNING_OFF, State.BLE_TURNING_OFF);
        transition_turningOffToBleTurningOff();

        btCallback.onBluetoothStateChange(State.BLE_TURNING_OFF, State.OFF);
        transition_bleTurningOffToOff();
    }

    @Test
    public void enable_duringBleTurningOn_shouldEnable() throws Exception {
        mManagerService.enableBle("enable_whileTurningToBleOn_shouldEnable", mBleBinder);

        acceptBluetoothBinding();
        IBluetoothCallback btCallback = captureBluetoothCallback();

        // receive enable when Bluetooth is in BLE_TURNING_ON
        mManagerService.enable(0, "enable_whileTurningToBleOn_shouldEnable");

        btCallback.onBluetoothStateChange(State.BLE_TURNING_ON, State.BLE_ON);
        syncHandler(MESSAGE_BLUETOOTH_STATE_CHANGE);
        verifyBleStateIntentSent(State.BLE_TURNING_ON, State.BLE_ON);

        mInOrder.verify(mAdapterBinder).bleOnToOn();
        verifyBleStateIntentSent(State.BLE_ON, State.TURNING_ON);
        verifyStateIntentSent(State.OFF, State.TURNING_ON);

        endTest();
    }

    @Test
    public void enable_whileNotYetBoundToBle_shouldEnable() throws Exception {
        mManagerService.enableBle("enable_whileTurningToBleOn_shouldEnable", mBleBinder);
        assertThat(mManagerService.getState()).isEqualTo(State.BLE_TURNING_ON);

        // receive enable when Bluetooth is OFF and not yet binded
        mManagerService.enable(0, "enable_whileTurningToBleOn_shouldEnable");

        acceptBluetoothBinding();
        IBluetoothCallback btCallback = captureBluetoothCallback();

        btCallback.onBluetoothStateChange(State.BLE_TURNING_ON, State.BLE_ON);
        syncHandler(MESSAGE_BLUETOOTH_STATE_CHANGE);
        verifyBleStateIntentSent(State.BLE_TURNING_ON, State.BLE_ON);

        mInOrder.verify(mAdapterBinder).bleOnToOn();
        verifyBleStateIntentSent(State.BLE_ON, State.TURNING_ON);
        verifyStateIntentSent(State.OFF, State.TURNING_ON);

        endTest();
    }

    @Test
    public void offToBleOn() throws Exception {
        mManagerService.enableBle("offToBleOn", mBleBinder);

        transition_offToBleOn();

        // Check that there was no transition to State.ON
        mInOrder.verify(mAdapterBinder, never()).bleOnToOn();
        assertThat(mManagerService.getState()).isEqualTo(State.BLE_ON);

        endTest();
    }

    @Test
    public void offToOn() throws Exception {
        mManagerService.enable(0, "offToOn");

        transition_offToOn();

        assertThat(mManagerService.getState()).isEqualTo(State.ON);

        endTest();
    }

    @Test
    public void crash_whileTransitionState_canRecover() throws Exception {
        mManagerService.enableBle("crash_whileTransitionState_canRecover", mBleBinder);

        var serviceConnection = acceptBluetoothBinding();

        IBluetoothCallback btCallback = captureBluetoothCallback();

        serviceConnection.onServiceDisconnected(
                new ComponentName("", "com.android.bluetooth.btservice.AdapterService"));
        syncHandler(MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED);
        verifyBleStateIntentSent(State.BLE_TURNING_ON, State.OFF);
        assertThat(mManagerService.getState()).isEqualTo(State.OFF);

        // Send a late bluetoothStateChange (since it can happen concurrently)
        btCallback.onBluetoothStateChange(State.BLE_TURNING_ON, State.BLE_ON);
        syncHandler(MESSAGE_BLUETOOTH_STATE_CHANGE);

        // Bluetooth is still OFF and doesn't crash
        assertThat(mManagerService.getState()).isEqualTo(State.OFF);

        mLooper.moveTimeForward(120_000);
        discardMessage(MESSAGE_RESTART_BLUETOOTH_SERVICE);

        endTest();
    }

    @Test
    public void crash_whenOn_goesToOffCorrectly_withBleOnWhenTurningOffFlagOn() throws Exception {
        mManagerService.enable(0, "crash_whenOn_goesToOffCorrectly_withBleOnWhenTurningOffFlagOn");

        // Manually perform transition to ON to get the ServiceConnection
        var serviceConnection = acceptBluetoothBinding();
        IBluetoothCallback btCallback = captureBluetoothCallback();
        btCallback.setAdapterServiceBinder(mBinder);
        syncHandler(0); // To post setAdapterServiceBinder
        mInOrder.verify(mManagerCallback).onBluetoothServiceUp(mBinder);
        btCallback.onBluetoothStateChange(State.BLE_TURNING_ON, State.BLE_ON);
        syncHandler(MESSAGE_BLUETOOTH_STATE_CHANGE);
        verifyBleStateIntentSent(State.BLE_TURNING_ON, State.BLE_ON);

        mInOrder.verify(mAdapterBinder).bleOnToOn();
        verifyBleStateIntentSent(State.BLE_ON, State.TURNING_ON);
        verifyStateIntentSent(State.OFF, State.TURNING_ON);
        btCallback.onBluetoothStateChange(State.TURNING_ON, State.ON);
        syncHandler(MESSAGE_BLUETOOTH_STATE_CHANGE);
        verifyBleStateIntentSent(State.TURNING_ON, State.ON);
        verifyStateIntentSent(State.TURNING_ON, State.ON);
        assertThat(mManagerService.getState()).isEqualTo(State.ON);

        // Simulate crash
        serviceConnection.onServiceDisconnected(
                new ComponentName("", "com.android.bluetooth.btservice.AdapterService"));
        syncHandler(MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED);

        // Verify state transitions
        // 1. ON -> TURNING_OFF
        verifyBleStateIntentSent(State.ON, State.TURNING_OFF);
        verifyStateIntentSent(State.ON, State.TURNING_OFF);

        // 2. TURNING_OFF -> BLE_TURNING_OFF
        verifyBleStateIntentSent(State.TURNING_OFF, State.BLE_TURNING_OFF);
        verifyStateIntentSent(State.TURNING_OFF, State.OFF);

        // 3. BLE_TURNING_OFF -> OFF
        verifyBleStateIntentSent(State.BLE_TURNING_OFF, State.OFF);

        assertThat(mManagerService.getState()).isEqualTo(State.OFF);

        // Verify recovery is scheduled
        mLooper.moveTimeForward(120_000);
        discardMessage(MESSAGE_RESTART_BLUETOOTH_SERVICE);

        endTest();
    }

    @Test
    public void disableAirplane_whenNothing_startBluetooth() throws Exception {
        mManagerService.enable(0, "disableAirplane_whenNothing_startBluetooth");
        transition_offToOn();

        assertThat(mLooper.nextMessage()).isNull();

        mManagerService.onAirplaneModeChanged(false);

        endTest();
    }

    @Test
    public void disable_whenBinding_bluetoothShouldStop() throws Exception {
        mManagerService.enable(0, "disable_whenBinding_bluetoothShouldStop");
        verifyBleStateIntentSent(State.OFF, State.BLE_TURNING_ON);
        mInOrder.verify(mContext).bindServiceAsUser(any(), any(), anyInt(), any());
        mManagerService.disable("disable_whenBinding_bluetoothShouldStop", true);
        mInOrder.verify(mContext).unbindService(any());
        verifyBleStateIntentSent(State.BLE_TURNING_ON, State.OFF);
        assertThat(mManagerService.getState()).isEqualTo(State.OFF);

        endTest();
    }

    @Test
    public void disable_whenTurningBleOn_bluetoothShouldStop() throws Exception {
        mManagerService.enable(0, "disable_whenTurningBleOn_bluetoothShouldStop");
        acceptBluetoothBinding();
        assertThat(mManagerService.getState()).isEqualTo(State.BLE_TURNING_ON);
        mManagerService.disable("disable_whenTurningBleOn_bluetoothShouldStop", true);
        mInOrder.verify(mContext).unbindService(any());
        verifyBleStateIntentSent(State.BLE_TURNING_ON, State.OFF);
        assertThat(mManagerService.getState()).isEqualTo(State.OFF);

        endTest();
    }

    @Test
    public void disable_whenBindingToBle_bluetoothShouldKeepStarting() throws Exception {
        mManagerService.enableBle(
                "disable_whenBindingToBle_bluetoothShouldKeepStarting", mBleBinder);
        assertThat(mManagerService.getState()).isEqualTo(State.BLE_TURNING_ON);
        mManagerService.disable("disable_whenBindingToBle_bluetoothShouldKeepStarting", true);
        assertThat(mManagerService.getState()).isEqualTo(State.BLE_TURNING_ON);
        transition_offToBleOn();

        endTest();
    }

    @Test
    public void disable_whenTurningBleOnToBle_bluetoothShouldKeepStarting() throws Exception {
        mManagerService.enableBle(
                "disable_whenTurningBleOnToBle_bluetoothShouldKeepStarting", mBleBinder);
        acceptBluetoothBinding();
        assertThat(mManagerService.getState()).isEqualTo(State.BLE_TURNING_ON);
        mManagerService.disable("disable_whenTurningBleOnToBle_bluetoothShouldKeepStarting", true);
        assertThat(mManagerService.getState()).isEqualTo(State.BLE_TURNING_ON);

        endTest();
    }

    @Test
    public void disableAirplane_whenFactoryReset_doesNotStartBluetooth() throws Exception {
        mManagerService.enable(0, "disableAirplane_whenFactoryReset_doesNotStartBluetooth");
        IBluetoothCallback btCallback = transition_offToOn();
        assertThat(mManagerService.getState()).isEqualTo(State.ON);

        mManagerService.mHandler.sendMessageAtTime(
                mManagerService.mHandler.obtainMessage(MESSAGE_RESTORE_USER_SETTING_OFF), 0);
        syncHandler(MESSAGE_RESTORE_USER_SETTING_OFF);
        transition_onToOff(btCallback);

        mManagerService.onAirplaneModeChanged(false);

        endTest();
    }

    @Test
    public void enableBle_whenDisableAirplaneIsDelayed_startBluetooth() throws Exception {
        mManagerService.enable(0, "enableBle_whenDisableAirplaneIsDelayed_startBluetooth");
        IBluetoothCallback btCallback = transition_offToOn();
        mManagerService.onAirplaneModeChanged(true);
        assertThat(mManagerService.getState()).isEqualTo(State.TURNING_OFF);

        // Generate an event that will be delayed due to the TURNING_OFF state
        mManagerService.onAirplaneModeChanged(false);

        transition_onToTurningOff();

        btCallback.onBluetoothStateChange(State.TURNING_OFF, State.BLE_TURNING_OFF);
        transition_turningOffToBleTurningOff();

        // As soon as we start turning down BLE, emulate request to go to BLE_ON
        assertThat(mManagerService.getState()).isEqualTo(State.BLE_TURNING_OFF);
        mManagerService.enableBle(
                "enableBle_whenDisableAirplaneIsDelayed_startBluetooth", mBleBinder);

        // When all the profile are started, adapterService consider it is ON
        btCallback.onBluetoothStateChange(State.BLE_TURNING_OFF, State.OFF);
        syncHandler(MESSAGE_BLUETOOTH_STATE_CHANGE);
        verifyBleStateIntentSent(State.BLE_TURNING_OFF, State.OFF);

        transition_offToOn();
        assertThat(mManagerService.getState()).isEqualTo(State.ON);

        endTest();
    }

    @Test
    public void factoryReset_whileBtOff_savePropertyForLater() throws Exception {
        mManagerService.factoryReset(0);
        ExtendedMockito.verify(() -> BluetoothProperties.snoop_log_mode(null));
        ExtendedMockito.verify(() -> BluetoothProperties.factory_reset(true));

        endTest();
    }

    @Test
    public void factoryReset_whileBtOn_restartWithProperty() throws Exception {
        mManagerService.enable(0, "factoryReset_whileBtOn_restartWithProperty");
        IBluetoothCallback btCallback = transition_offToOn();
        assertThat(mManagerService.getState()).isEqualTo(State.ON);

        mManagerService.factoryReset(0);
        ExtendedMockito.verify(() -> BluetoothProperties.snoop_log_mode(null));
        ExtendedMockito.verify(() -> BluetoothProperties.factory_reset(true));

        transition_onToOff(btCallback);
        transition_offToOn();
        assertThat(mManagerService.getState()).isEqualTo(State.ON);

        endTest();
    }

    @Test
    public void onToBleOn_whenBleApp_goesThroughOff() throws Exception {
        mManagerService.enable(0, "onToBleOn_whenBleApp_goesThroughOff");
        IBluetoothCallback btCallback = transition_offToOn();
        assertThat(mManagerService.getState()).isEqualTo(State.ON);

        // Start a ble app to make sure we restart
        mManagerService.enableBle("onToBleOn_whenBleApp_goesThroughOff", mBleBinder);

        mManagerService.disable("onToBleOn_whenBleApp_goesThroughOff", true);
        transition_onToOff(btCallback);

        // Because a BLE app is active, it should restart into BLE_ON mode.
        transition_offToBleOn();
        assertThat(mManagerService.getState()).isEqualTo(State.BLE_ON);

        endTest();
    }

    @Test
    public void onToBleOn_whenNoBleApp_staysOff() throws Exception {
        mManagerService.enable(0, "onToBleOn_whenNoBleApp_staysOff");
        IBluetoothCallback btCallback = transition_offToOn();
        assertThat(mManagerService.getState()).isEqualTo(State.ON);

        // No BLE app started.

        mManagerService.disable("onToBleOn_whenNoBleApp_staysOff", true);
        transition_onToOff(btCallback);

        // Because no BLE app is active, it should stay OFF.
        assertThat(mManagerService.getState()).isEqualTo(State.OFF);

        endTest();
    }

    @Test
    public void initialStart_whenPersistentStorageOn_bluetoothStart() throws Exception {
        mPersistedState = BluetoothManagerService.BLUETOOTH_ON_BLUETOOTH;

        mManagerService =
                new BluetoothManagerService(
                        mContext,
                        mLooper.getLooper(),
                        "default",
                        mBluetoothComponent,
                        mTimeProvider);
        mManagerService.handleOnBootPhase(DEFAULT_USER);

        mManagerService.registerAdapter(mManagerCallback);

        // No need to call enable, Bluetooth will start automatically
        transition_offToOn();
        assertThat(mManagerService.getState()).isEqualTo(State.ON);

        endTest();
    }

    @Test
    public void initialStart_whenUserIsRestricted_staysOff() throws Exception {
        doReturn(true).when(mUserManager).hasUserRestriction(eq(UserManager.DISALLOW_BLUETOOTH));
        BluetoothRestriction.handleRestrictionChange(
                mContext, mManagerService::onRestrictionChange);

        mManagerService =
                new BluetoothManagerService(
                        mContext,
                        mLooper.getLooper(),
                        "default",
                        mBluetoothComponent,
                        mTimeProvider);
        mManagerService.handleOnBootPhase(DEFAULT_USER);

        assertThat(mManagerService.getState()).isEqualTo(State.OFF);

        endTest();
    }

    @Test
    public void userSwitch_fastSwitch_restartsForLatestUser() throws Exception {
        mManagerService.enable(0, "userSwitch_fastSwitch_restartsForLatestUser");
        IBluetoothCallback btCallback = transition_offToOn();
        assertThat(mManagerService.getState()).isEqualTo(State.ON);

        mManagerService.onUserSwitching(OTHER_USER);
        // A third user switch arrives before the first one is processed.
        UserHandle anotherUser = mock(UserHandle.class);
        mManagerService.onUserSwitching(anotherUser);

        transition_onToOff(btCallback);

        mCurrentUser = anotherUser;

        transition_offToOn();
        assertThat(mManagerService.getState()).isEqualTo(State.ON);

        endTest();
    }

    @Test
    public void userSwitch_onSameUserWhenBtOff_canStillStart() throws Exception {
        // This scenario sometimes happen on Boot, when Bluetooth start for secondary user and
        // received a user switch to secondary user simultaneously
        mManagerService.onUserSwitching(DEFAULT_USER);

        mManagerService.enable(0, "userSwitch_onSameUserWhenBtOff_canStillStart");
        transition_offToOn();
        assertThat(mManagerService.getState()).isEqualTo(State.ON);

        endTest();
    }

    @Test
    @DisableFlags(com.android.bluetooth.beta.flags.Flags.FLAG_SYSTEM_SERVER_DIRECT_SWITCH)
    public void userSwitch_onSameUserWhenBtOn_doesNothing() throws Exception {
        mManagerService.enable(0, "userSwitch_onSameUserWhenBtOn_doesNothing");
        transition_offToOn();
        assertThat(mManagerService.getState()).isEqualTo(State.ON);

        mManagerService.onUserSwitching(DEFAULT_USER);

        assertThat(mManagerService.getState()).isEqualTo(State.ON);

        // Verify a subsequent enable call still works (is not blocked by a pending user switch).
        assertThat(mManagerService.enable(0, "userSwitch_onSameUserWhenBtOn_doesNothing")).isTrue();

        endTest();
    }

    @Test
    @EnableFlags(com.android.bluetooth.beta.flags.Flags.FLAG_SYSTEM_SERVER_DIRECT_SWITCH)
    public void userSwitch_onSameUserWhenTurningOn_doesNothing() throws Exception {
        mManagerService.enable(0, "userSwitch_onSameUserWhenBtOn_doesNothing");

        // To TURNING_ON state
        IBluetoothCallback btCallback = transition_offToBleOn();
        mInOrder.verify(mAdapterBinder).bleOnToOn();
        verifyBleStateIntentSent(State.BLE_ON, State.TURNING_ON);
        verifyStateIntentSent(State.OFF, State.TURNING_ON);

        assertThat(mManagerService.getState()).isEqualTo(State.TURNING_ON);

        mManagerService.onUserSwitching(DEFAULT_USER); // this should be discarded

        // Finish transition to ON
        btCallback.onBluetoothStateChange(State.TURNING_ON, State.ON);
        syncHandler(MESSAGE_BLUETOOTH_STATE_CHANGE);
        verifyBleStateIntentSent(State.TURNING_ON, State.ON);
        verifyStateIntentSent(State.TURNING_ON, State.ON);
        assertThat(mManagerService.getState()).isEqualTo(State.ON);

        // Verify a subsequent enable call still works (is not blocked by a pending user switch).
        assertThat(mManagerService.enable(0, "userSwitch_onSameUserWhenBtOn_doesNothing")).isTrue();

        endTest();
    }

    @Test
    @EnableFlags(com.android.bluetooth.beta.flags.Flags.FLAG_SYSTEM_SERVER_DIRECT_SWITCH)
    public void userSwitch_onSameUserWhenBinding_startImmediatelyOnNewUser() throws Exception {
        mManagerService.enable(0, "userSwitch_onSameUserWhenBtOn_doesNothing");
        verifyBleStateIntentSent(State.OFF, State.BLE_TURNING_ON);

        mManagerService.onUserSwitching(OTHER_USER);
        verifyBleStateIntentSent(State.BLE_TURNING_ON, State.OFF);

        mCurrentUser = OTHER_USER;
        transition_offToOn();

        endTest();
    }

    @Test
    public void userSwitch_fastSwitchOnInitialUser_restartsForInitialUser() throws Exception {
        mManagerService.enable(0, "userSwitch_fastSwitch_restartsForLatestUser");
        IBluetoothCallback btCallback = transition_offToOn();
        assertThat(mManagerService.getState()).isEqualTo(State.ON);

        mManagerService.onUserSwitching(OTHER_USER);
        mManagerService.onUserSwitching(DEFAULT_USER);

        transition_onToOff(btCallback);

        transition_offToOn();
        assertThat(mManagerService.getState()).isEqualTo(State.ON);

        endTest();
    }

    @Test
    public void userSwitch_duringShutdownForSwitch_restartsForLatestUser() throws Exception {
        mManagerService.enable(0, "userSwitch_duringShutdownForSwitch_restartsForLatestUser");
        IBluetoothCallback btCallback = transition_offToOn();
        assertThat(mManagerService.getState()).isEqualTo(State.ON);

        mManagerService.onUserSwitching(OTHER_USER);

        // Start the shutdown process
        transition_onToTurningOff();
        assertThat(mManagerService.getState()).isEqualTo(State.TURNING_OFF);

        // Switch user again while shutting down
        UserHandle anotherUser = mock(UserHandle.class);
        mManagerService.onUserSwitching(anotherUser);

        // Complete the shutdown to OFF
        btCallback.onBluetoothStateChange(State.TURNING_OFF, State.BLE_TURNING_OFF);
        transition_turningOffToBleTurningOff();

        btCallback.onBluetoothStateChange(State.BLE_TURNING_OFF, State.OFF);
        transition_bleTurningOffToOff();

        mCurrentUser = anotherUser;

        transition_offToOn();
        assertThat(mManagerService.getState()).isEqualTo(State.ON);

        endTest();
    }

    @Test
    public void userSwitch_duringRestartForSwitch_completesThenRestartsForLatestUser()
            throws Exception {
        mManagerService.enable(
                0, "userSwitch_duringRestartForSwitch_completesThenRestartsForLatestUser");
        IBluetoothCallback btCallback = transition_offToOn();
        assertThat(mManagerService.getState()).isEqualTo(State.ON);

        mManagerService.onUserSwitching(OTHER_USER);
        transition_onToOff(btCallback);

        mCurrentUser = OTHER_USER;

        // Restart for OTHER_USER begins
        IBluetoothCallback newBtCallback = transition_offToBleOn();
        mInOrder.verify(mAdapterBinder).bleOnToOn();
        verifyBleStateIntentSent(State.BLE_ON, State.TURNING_ON);
        verifyStateIntentSent(State.OFF, State.TURNING_ON);
        assertThat(mManagerService.getState()).isEqualTo(State.TURNING_ON);

        // Switch user again in the middle of TURNING_ON
        UserHandle anotherUser = mock(UserHandle.class);
        mManagerService.onUserSwitching(anotherUser);

        // The current startup should complete
        newBtCallback.onBluetoothStateChange(State.TURNING_ON, State.ON);
        syncHandler(MESSAGE_BLUETOOTH_STATE_CHANGE);
        verifyBleStateIntentSent(State.TURNING_ON, State.ON);
        verifyStateIntentSent(State.TURNING_ON, State.ON);

        // Now it should shut down for the new user switch and never stay ON
        assertThat(mManagerService.getState()).isNotEqualTo(State.ON);
        transition_onToOff(newBtCallback);

        mCurrentUser = anotherUser;

        transition_offToOn();
        assertThat(mManagerService.getState()).isEqualTo(State.ON);

        endTest();
    }

    @Test
    public void userSwitch_whenBleTurningOn_abortsAndStaysOff() throws Exception {
        mManagerService.enableBle("userSwitch_whenBleTurningOn_abortsAndStaysOff", mBleBinder);
        acceptBluetoothBinding();
        IBluetoothCallback btCallback = captureBluetoothCallback();
        assertThat(mManagerService.getState()).isEqualTo(State.BLE_TURNING_ON);

        // A late state change that will be posted after the user switching and will be ignored
        btCallback.onBluetoothStateChange(State.BLE_TURNING_ON, State.BLE_ON);

        mManagerService.onUserSwitching(OTHER_USER);

        // The service should be unbound, and state should go to OFF.
        mInOrder.verify(mContext).unbindService(any());
        verifyBleStateIntentSent(State.BLE_TURNING_ON, State.OFF);
        assertThat(mManagerService.getState()).isEqualTo(State.OFF);

        endTest();
    }

    @Test
    public void userSwitch_whenBtOff_stayOff() throws Exception {
        mManagerService.onUserSwitching(OTHER_USER);
        assertThat(mManagerService.getState()).isEqualTo(State.OFF);

        endTest();
    }

    @Test
    public void userSwitch_whenBleOn_stopAndDontRestart() throws Exception {
        mManagerService.enableBle("userSwitch_whenBleOn_stopAndDontRestart", mBleBinder);
        IBluetoothCallback btCallback = transition_offToBleOn();
        assertThat(mManagerService.getState()).isEqualTo(State.BLE_ON);

        mManagerService.onUserSwitching(OTHER_USER);
        transition_bleOnToOff(btCallback);

        endTest();
    }

    @Test
    public void userSwitch_whenOn_stopAndRestart() throws Exception {
        mManagerService.enable(0, "userSwitch_whenOn_stopAndRestart");
        IBluetoothCallback btCallback = transition_offToOn();
        assertThat(mManagerService.getState()).isEqualTo(State.ON);

        mManagerService.onUserSwitching(OTHER_USER);

        transition_onToOff(btCallback);

        mCurrentUser = OTHER_USER;

        transition_offToOn();
        assertThat(mManagerService.getState()).isEqualTo(State.ON);

        endTest();
    }

    @Test
    public void enable_afterLeSession_canStart() throws Exception {
        mManagerService.enableBle("enable_afterLeSession_canStart", mBleBinder);
        IBluetoothCallback btCallback = transition_offToBleOn();
        assertThat(mManagerService.getState()).isEqualTo(State.BLE_ON);
        mManagerService.disableBle("enable_afterLeSession_canStart", mBleBinder);
        transition_bleOnToOff(btCallback);
        assertThat(mManagerService.getState()).isEqualTo(State.OFF);

        mManagerService.enable(0, "enable_afterLeSession_canStart");
        transition_offToOn();
        assertThat(mManagerService.getState()).isEqualTo(State.ON);

        endTest();
    }

    @Test
    public void bleBinderDeath_whenBleOn_isOff() throws Exception {
        mManagerService.enableBle("bleBinderDeath_whenBleOn_isOff", mBleBinder);
        IBluetoothCallback btCallback = transition_offToBleOn();
        assertThat(mManagerService.getState()).isEqualTo(State.BLE_ON);

        var captor = ArgumentCaptor.forClass(IBinder.DeathRecipient.class);
        verify(mBleBinder).linkToDeath(captor.capture(), anyInt());
        captor.getValue().binderDied();
        syncHandler(0); // To post from the binder death

        transition_bleOnToOff(btCallback);
        assertThat(mManagerService.getState()).isEqualTo(State.OFF);

        endTest();
    }

    @Test
    public void bleBinderDeath_whenOn_staysOn() throws Exception {
        mManagerService.enable(0, "bleBinderDeath_whenOn_staysOn");
        transition_offToOn();
        assertThat(mManagerService.getState()).isEqualTo(State.ON);

        mManagerService.enableBle("bleBinderDeath_whenOn_staysOn", mBleBinder);

        var captor = ArgumentCaptor.forClass(IBinder.DeathRecipient.class);
        verify(mBleBinder).linkToDeath(captor.capture(), anyInt());
        captor.getValue().binderDied();
        syncHandler(0); // To post from the binder death

        endTest(); // Nothing happen
    }

    @Test
    public void bleBinderDeath_whenOtherApp_staysOn() throws Exception {
        mManagerService.enableBle("bleBinderDeath_whenOtherApp_staysOn", mBleBinder);
        transition_offToBleOn();
        assertThat(mManagerService.getState()).isEqualTo(State.BLE_ON);

        mManagerService.enableBle("other_bleBinderDeath_whenOtherApp_staysOn", mock(IBinder.class));

        var captor = ArgumentCaptor.forClass(IBinder.DeathRecipient.class);
        verify(mBleBinder).linkToDeath(captor.capture(), anyInt());
        captor.getValue().binderDied();
        syncHandler(0); // To post from the binder death

        endTest(); // Nothing happen
    }

    @Test
    public void disable_whenTurningOn_shouldAbortAndTurnOff() throws Exception {
        mManagerService.enable(0, "disable_whenTurningOn_shouldAbortAndTurnOff");
        IBluetoothCallback btCallback = transition_offToBleOn();
        mInOrder.verify(mAdapterBinder).bleOnToOn();
        verifyBleStateIntentSent(State.BLE_ON, State.TURNING_ON);
        verifyStateIntentSent(State.OFF, State.TURNING_ON);
        assertThat(mManagerService.getState()).isEqualTo(State.TURNING_ON);

        // Call disable during TURNING_ON
        mManagerService.disable("disable_whenTurningOn_shouldAbortAndTurnOff", true);

        // When all profiles are started, adapterService consider it is ON
        btCallback.onBluetoothStateChange(State.TURNING_ON, State.ON);
        syncHandler(MESSAGE_BLUETOOTH_STATE_CHANGE);
        verifyBleStateIntentSent(State.TURNING_ON, State.ON);
        verifyStateIntentSent(State.TURNING_ON, State.ON);

        // Because of graceful disable, it should immediately call onToBleOn
        // and then go through the full off transition.
        transition_onToOff(btCallback);

        assertThat(mManagerService.getState()).isEqualTo(State.OFF);

        endTest();
    }

    @Test
    public void timeout_whenBrEdrTurningOn_verifyTurnOffAndRetry() throws Exception {
        mManagerService.enable(0, "timeout_whenBrEdrTurningOn_verifyTurnOffAndRetry");
        IBluetoothCallback btCallback = transition_offToBleOn();
        mInOrder.verify(mAdapterBinder).bleOnToOn();
        verifyBleStateIntentSent(State.BLE_ON, State.TURNING_ON);
        verifyStateIntentSent(State.OFF, State.TURNING_ON);
        assertThat(mManagerService.getState()).isEqualTo(State.TURNING_ON);

        // AdapterState.java is handling the timeout, and this is triggered by returning TURNING_OFF
        btCallback.onBluetoothStateChange(State.TURNING_ON, State.TURNING_OFF);
        syncHandler(MESSAGE_BLUETOOTH_STATE_CHANGE);

        verifyBleStateIntentSent(State.TURNING_ON, State.TURNING_OFF);
        verifyStateIntentSent(State.TURNING_ON, State.TURNING_OFF);

        // Because of graceful disable, it should immediately call onToBleOn
        // and then go through the full off transition.
        btCallback.onBluetoothStateChange(State.TURNING_OFF, State.BLE_TURNING_OFF);
        transition_turningOffToBleTurningOff();

        btCallback.onBluetoothStateChange(State.BLE_TURNING_OFF, State.OFF);
        transition_bleTurningOffToOff();

        transition_offToOn(); // reaching OFF when mEnable is true
        assertThat(mManagerService.getState()).isEqualTo(State.ON);

        endTest();
    }

    @Test
    public void timeout_whenBrEdrTurningOnWithBleApp_verifyTurnOffAndRetry() throws Exception {
        mManagerService.enable(0, "timeout_whenBrEdrTurningOnWithBleApp_verifyTurnOffAndRetry");
        IBluetoothCallback btCallback = transition_offToBleOn();
        mManagerService.enableBle(
                "timeout_whenBrEdrTurningOnWithBleApp_verifyTurnOffAndRetry", mBleBinder);
        mInOrder.verify(mAdapterBinder).bleOnToOn();
        verifyBleStateIntentSent(State.BLE_ON, State.TURNING_ON);
        verifyStateIntentSent(State.OFF, State.TURNING_ON);
        assertThat(mManagerService.getState()).isEqualTo(State.TURNING_ON);

        // AdapterState.java is handling the timeout, and this is triggered by returning TURNING_OFF
        btCallback.onBluetoothStateChange(State.TURNING_ON, State.TURNING_OFF);
        syncHandler(MESSAGE_BLUETOOTH_STATE_CHANGE);

        verifyBleStateIntentSent(State.TURNING_ON, State.TURNING_OFF);
        verifyStateIntentSent(State.TURNING_ON, State.TURNING_OFF);

        // Because of graceful disable, it should immediately call onToBleOn
        // and then go through the full off transition.
        btCallback.onBluetoothStateChange(State.TURNING_OFF, State.BLE_TURNING_OFF);
        transition_turningOffToBleTurningOff();

        btCallback.onBluetoothStateChange(State.BLE_TURNING_OFF, State.OFF);
        transition_bleTurningOffToOff();

        transition_offToOn(); // reaching OFF when mEnable is true
        assertThat(mManagerService.getState()).isEqualTo(State.ON);

        endTest();
    }

    @Test
    public void disableScan_whenBleOn_isTurnedOff() throws Exception {
        mManagerService.enableBle("disableScan_whenBleOn_isTurnedOff", mBleBinder);
        IBluetoothCallback btCallback = transition_offToBleOn();
        assertThat(mManagerService.getState()).isEqualTo(State.BLE_ON);

        mManagerService.onBleScanDisabled();
        transition_bleOnToOff(btCallback);

        assertThat(mManagerService.getState()).isEqualTo(State.OFF);

        endTest();
    }

    @Test
    public void crashLoop_recoveryTimeIncrease() throws Exception {
        Duration[] recoveryDelays = {
            SERVICE_RESTART_DELAY,
            SERVICE_RESTART_DELAY.multipliedBy(2),
            SERVICE_RESTART_DELAY.multipliedBy(3),
            SERVICE_RESTART_DELAY.multipliedBy(40),
            SERVICE_RESTART_DELAY.multipliedBy(50),
            SERVICE_RESTART_DELAY.multipliedBy(60),
            Duration.ZERO
        };

        mManagerService.enableBle("crashLoop_recoveryTimeIncrease", mBleBinder);

        for (Duration delay : recoveryDelays) {
            var serviceConnection = acceptBluetoothBinding();
            serviceConnection.onServiceDisconnected(
                    new ComponentName("", "com.android.bluetooth.btservice.AdapterService"));
            syncHandler(MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED);
            verifyBleStateIntentSent(State.BLE_TURNING_ON, State.OFF);
            assertThat(mManagerService.getState()).isEqualTo(State.OFF);

            if (!delay.isPositive()) {
                // Last restart attempt
                break;
            }

            mLooper.moveTimeForward(delay.toMillis() - 1);
            assertThat(mLooper.nextMessage()).isNull();
            mLooper.moveTimeForward(1);
            syncHandler(MESSAGE_RESTART_BLUETOOTH_SERVICE);
        }

        endTest();
    }

    @Test
    public void satelliteMode_whenOn_turnsBluetoothOffAndOn() throws Exception {
        mManagerService.enable(0, "satelliteMode_whenOn_turnsBluetoothOffAndOn");
        IBluetoothCallback btCallback = transition_offToOn();
        assertThat(mManagerService.getState()).isEqualTo(State.ON);

        mManagerService.onSatelliteModeChanged(true);
        transition_onToOff(btCallback);
        assertThat(mManagerService.getState()).isEqualTo(State.OFF);

        mManagerService.onSatelliteModeChanged(false);
        transition_offToOn();
        assertThat(mManagerService.getState()).isEqualTo(State.ON);

        endTest();
    }

    @Test // replaced by BluetoothSupervisorTest.onUserStopping_currentUser_stopsService
    @EnableFlags(Flags.FLAG_SWITCH_WHEN_CURRENT_USER_STOP)
    public void supervisor__userStop_whenCurrent_emulateSwitch() throws Exception {
        mLooper = new TestLooper(() -> 0L);
        mManagerService =
                new BluetoothManagerService(
                        mContext,
                        mLooper.getLooper(),
                        "default",
                        mBluetoothComponent,
                        mTimeProvider);
        BluetoothRestriction.initialize(
                mContext, mLooper.getLooper(), mManagerService::onRestrictionChange);
        mManagerService.registerAdapter(mManagerCallback);

        var supervisor =
                new BluetoothSupervisorLegacy(
                        mContext, mLooper.getLooper(), mBluetoothComponent, mManagerService);
        supervisor.onUserStartingFromJava(DEFAULT_USER);

        mCurrentUser = OTHER_USER;
        supervisor.onUserStoppingFromJava(DEFAULT_USER);

        mManagerService.enable(0, "supervisor__userStop_priorToUserSwitch_emulateSwitch");
        transition_offToOn(); // Enforce mCurrentUser is used (see acceptBluetoothBinding)

        endTest();
    }

    @Test // replaced by BluetoothSupervisorTest.onUserStopping_otherUser_ignored
    @EnableFlags(Flags.FLAG_SWITCH_WHEN_CURRENT_USER_STOP)
    public void supervisor__userStop_whenNotCurrent_nothingHappen() throws Exception {
        mLooper = new TestLooper(() -> 0L);
        mManagerService =
                new BluetoothManagerService(
                        mContext,
                        mLooper.getLooper(),
                        "default",
                        mBluetoothComponent,
                        mTimeProvider);
        BluetoothRestriction.initialize(
                mContext, mLooper.getLooper(), mManagerService::onRestrictionChange);
        mManagerService.registerAdapter(mManagerCallback);
        var supervisor =
                new BluetoothSupervisorLegacy(
                        mContext, mLooper.getLooper(), mBluetoothComponent, mManagerService);
        supervisor.onUserStartingFromJava(DEFAULT_USER);
        supervisor.onUserStoppingFromJava(OTHER_USER);

        mManagerService.enable(0, "supervisor__userStop_priorToUserSwitch_emulateSwitch");
        transition_offToOn(); // Enforce mCurrentUser is used (see acceptBluetoothBinding)

        endTest();
    }

    @Test // replaced by BluetoothSupervisorTest.onUserStopping_onForegroundUser_isNotSupported
    @EnableFlags(Flags.FLAG_SWITCH_WHEN_CURRENT_USER_STOP)
    public void supervisor__foregroundUserStop_whenCurrent_isUnsupported() throws Exception {
        mLooper = new TestLooper(() -> 0L);
        mManagerService =
                new BluetoothManagerService(
                        mContext,
                        mLooper.getLooper(),
                        "default",
                        mBluetoothComponent,
                        mTimeProvider);
        BluetoothRestriction.initialize(
                mContext, mLooper.getLooper(), mManagerService::onRestrictionChange);
        mManagerService.registerAdapter(mManagerCallback);
        var supervisor =
                new BluetoothSupervisorLegacy(
                        mContext, mLooper.getLooper(), mBluetoothComponent, mManagerService);

        mCurrentUser = OTHER_USER;
        supervisor.onUserStartingFromJava(OTHER_USER);
        assertThrows(
                IllegalStateException.class, () -> supervisor.onUserStoppingFromJava(mCurrentUser));

        endTest();
    }

    @SafeVarargs
    private void verifyIntentSent(Matcher<Intent>... matchers) {
        mInOrder.verify(mContext)
                .sendBroadcastAsUser(
                        MockitoHamcrest.argThat(AllOf.allOf(matchers)), any(), any(), any());
    }

    private void verifyBleStateIntentSent(int from, int to) {
        verifyIntentSent(
                hasAction(ACTION_BLE_STATE_CHANGED),
                hasExtra(EXTRA_PREVIOUS_STATE, from),
                hasExtra(EXTRA_STATE, to));
    }

    private void verifyStateIntentSent(int from, int to) {
        verifyIntentSent(
                hasAction(ACTION_STATE_CHANGED),
                hasExtra(EXTRA_PREVIOUS_STATE, from),
                hasExtra(EXTRA_STATE, to));
    }
}
