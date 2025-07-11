/*
 * Copyright 2023 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.annotation.SuppressLint;
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
import android.provider.Settings;
import android.sysprop.BluetoothProperties;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.bluetooth.flags.Flags;
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

import java.util.List;
import java.util.stream.IntStream;

@RunWith(ParameterizedAndroidJunit4.class)
@SuppressLint("AndroidFrameworkRequiresPermission")
public class BluetoothManagerServiceTest {
    @Rule public final SetFlagsRule mSetFlagsRule;

    @Rule
    public final StaticMockitoRule mMockitoRule = new StaticMockitoRule(BluetoothProperties.class);

    @Parameters(name = "{0}")
    public static List<FlagsWrapper> getParams() {
        return FlagsWrapper.progressionOf(
                Flags.FLAG_CLEANUP_STARTING_USER,
                Flags.FLAG_USER_SWITCH_DURING_BLE_ON,
                Flags.FLAG_USER_RESTRICTION_REFACTOR,
                Flags.FLAG_GRACEFUL_DISABLE_WITHOUT_MESSAGE);
    }

    public BluetoothManagerServiceTest(FlagsWrapper flagsWrapper) {
        mSetFlagsRule = new SetFlagsRule(flagsWrapper.getFlags());
    }

    private final Context mTargetContext =
            InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Mock BluetoothServerProxy mBluetoothServerProxy;
    @Mock Context mContext;
    @Mock UserManager mUserManager;
    @Mock RoleManager mRoleManager;
    @Mock UserHandle mUser;
    @Mock UserHandle mNextUser;
    @Mock IBinder mBleBinder;
    @Mock IBinder mBinder;
    @Mock IBluetoothManagerCallback mManagerCallback;
    @Mock IAdapter mAdapterService;
    @Mock AdapterBinder mAdapterBinder;
    @Mock AppOpsManager mAppOpsManager;
    @Mock PermissionManager mPermissionManager;

    private int mPersistedState = BluetoothManagerService.BLUETOOTH_OFF;

    private InOrder mInOrder;
    private TestLooper mLooper;
    private BluetoothManagerService mManagerService;

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
                .settingsSecureGetString(any(), eq(Settings.Secure.BLUETOOTH_NAME));
        doReturn("00:11:22:33:44:55")
                .when(mBluetoothServerProxy)
                .settingsSecureGetString(any(), eq(Settings.Secure.BLUETOOTH_ADDRESS));
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
        doReturn(mRoleManager).when(mContext).getSystemService(RoleManager.class);
        doReturn(mUserManager).when(mContext).getSystemService(UserManager.class);
        doReturn(mAppOpsManager).when(mContext).getSystemService(AppOpsManager.class);
        doReturn(mPermissionManager).when(mContext).getSystemService(PermissionManager.class);

        doReturn(mBinder).when(mManagerCallback).asBinder();

        doReturn(mAdapterBinder).when(mBluetoothServerProxy).createAdapterBinder(any());
        doReturn(mAdapterService).when(mAdapterBinder).getAdapterBinder();
        doReturn(mBinder).when(mAdapterService).asBinder();

        doReturn(true).when(mContext).bindServiceAsUser(any(), any(), anyInt(), any());
        doNothing().when(mContext).unbindService(any());

        BluetoothServerProxy.setInstanceForTesting(mBluetoothServerProxy);

        mLooper = new TestLooper();

        mManagerService = new BluetoothManagerService(mContext, mLooper.getLooper(), "default");
        mManagerService.internalHandleOnBootPhase(mUser);

        mManagerService.registerAdapter(mManagerCallback);
    }

    @After
    public void tearDown() {
        IpcDataCache.setCacheTestMode(false);
        BluetoothAdapterState.disableCacheForTesting = false;
    }

    private void endTest() {
        mLooper.moveTimeForward(120_000);
        assertThat(mLooper.nextMessage()).isNull();
    }

    /**
     * Dispatch all the message on the Looper and check that the what is expected
     *
     * @param what list of message that are expected to be run by the handler
     */
    private void syncHandler(int... what) {
        IntStream.of(what)
                .forEach(
                        w -> {
                            String log = "Expecting message " + w + ": but got ";

                            Message msg = mLooper.nextMessage();
                            assertWithMessage(log + "null").that(msg).isNotNull();
                            assertWithMessage(log + msg.what).that(msg.what).isEqualTo(w);
                            msg.getTarget().dispatchMessage(msg);
                        });
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
    @DisableFlags(Flags.FLAG_USER_RESTRICTION_REFACTOR)
    public void onUserRestrictionsChanged_disallowBluetooth_onlySendDisableMessageOnSystemUser()
            throws InterruptedException {
        // Mimic the case when restriction settings changed
        doReturn(true)
                .when(mUserManager)
                .hasUserRestrictionForUser(eq(UserManager.DISALLOW_BLUETOOTH), any());

        // Check if disable message sent once for system user only

        // test run on user -1, should not turning Bluetooth off
        mManagerService.onUserRestrictionsChanged(UserHandle.CURRENT);
        assertThat(mLooper.nextMessage()).isNull();

        // called from SYSTEM user, should try to toggle Bluetooth off
        mManagerService.onUserRestrictionsChanged(UserHandle.SYSTEM);

        endTest();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_USER_RESTRICTION_REFACTOR,
        Flags.FLAG_LIMIT_USER_SWITCH_PROPAGATION,
    })
    public void onUserRestrictionsChanged_whenOn_turnOff() throws Exception {
        mManagerService.enable(0, "onUserRestrictionsChanged_whenOn_turnOff");
        IBluetoothCallback btCallback = transition_offToOn();

        doReturn(true).when(mUserManager).hasUserRestriction(eq(UserManager.DISALLOW_BLUETOOTH));

        mLooper.getNewExecutor()
                .execute(
                        () ->
                                UserRestriction.handleRestrictionChange(
                                        mContext,
                                        UserHandle.SYSTEM,
                                        mManagerService::onBluetoothDisallowed));
        assertThat(mLooper.dispatchAll()).isEqualTo(1);
        transition_onToOff(btCallback);
        assertThat(mManagerService.getState()).isEqualTo(State.OFF);

        endTest();
    }

    @Test
    public void enable_bindFailure_removesTimeoutAndStaysOff() throws Exception {
        doReturn(false).when(mContext).bindServiceAsUser(any(), any(), anyInt(), any());
        mManagerService.enableBle("enable_bindFailure_removesTimeout", mBleBinder);
        if (Flags.userSwitchDuringBleOn()) {
            verifyBleStateIntentSent(State.OFF, State.BLE_TURNING_ON);
        }
        mInOrder.verify(mContext).unbindService(any());
        if (Flags.userSwitchDuringBleOn()) {
            verifyBleStateIntentSent(State.BLE_TURNING_ON, State.OFF);
        }

        assertThat(mManagerService.getState()).isEqualTo(State.OFF);

        endTest();
    }

    @Test
    public void enable_bindTimeout() throws Exception {
        mManagerService.enableBle("enable_bindTimeout", mBleBinder);

        mLooper.moveTimeForward(120_000); // 120 seconds
        syncHandler(MESSAGE_TIMEOUT_BIND);
        // Force handling the message now without waiting for the timeout to fire

        // TODO(b/280518177): A lot of stuff is wrong here since when a timeout occur:
        //   * No error is printed to the user
        //   * Code stop trying to start the bluetooth.
        //   * if user ask to enable again, it will start a second bind but the first still run

        endTest();
    }

    private ServiceConnection acceptBluetoothBinding() {
        ComponentName compName =
                new ComponentName("", "com.android.bluetooth.btservice.AdapterService");

        var captor = ArgumentCaptor.forClass(ServiceConnection.class);
        mInOrder.verify(mContext).bindServiceAsUser(any(), captor.capture(), anyInt(), any());
        assertThat(captor.getAllValues()).hasSize(1);

        var serviceConnection = captor.getAllValues().get(0);
        serviceConnection.onServiceConnected(compName, mBinder);
        syncHandler(MESSAGE_BLUETOOTH_SERVICE_CONNECTED);
        return serviceConnection;
    }

    private IBluetoothCallback captureBluetoothCallback() throws Exception {
        var captor = ArgumentCaptor.forClass(IBluetoothCallback.class);
        mInOrder.verify(mAdapterBinder).registerCallback(captor.capture());
        assertThat(captor.getAllValues()).hasSize(1);
        return captor.getValue();
    }

    IBluetoothCallback transition_offToBleOn() throws Exception {
        if (Flags.userSwitchDuringBleOn()) {
            verifyBleStateIntentSent(State.OFF, State.BLE_TURNING_ON);
        }
        acceptBluetoothBinding();

        IBluetoothCallback btCallback = captureBluetoothCallback();
        mInOrder.verify(mAdapterBinder).offToBleOn(anyBoolean(), anyString());
        if (!Flags.userSwitchDuringBleOn()) {
            verifyBleStateIntentSent(State.OFF, State.BLE_TURNING_ON);
        }
        btCallback.setAdapterServiceBinder(mBinder);
        syncHandler(0); // To post setAdapterServiceBinder
        mInOrder.verify(mManagerCallback).onBluetoothServiceUp(mBinder);

        assertThat(mManagerService.getState()).isEqualTo(State.BLE_TURNING_ON);

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

        // AdapterService go to turning_on and start all profile on its own
        btCallback.onBluetoothStateChange(State.BLE_ON, State.TURNING_ON);
        syncHandler(MESSAGE_BLUETOOTH_STATE_CHANGE);
        verifyBleStateIntentSent(State.BLE_ON, State.TURNING_ON);
        verifyStateIntentSent(State.OFF, State.TURNING_ON);
        // When all the profile are started, adapterService consider it is ON
        btCallback.onBluetoothStateChange(State.TURNING_ON, State.ON);
        syncHandler(MESSAGE_BLUETOOTH_STATE_CHANGE);
        verifyBleStateIntentSent(State.TURNING_ON, State.ON);
        verifyStateIntentSent(State.TURNING_ON, State.ON);

        return btCallback;
    }

    private void transition_onToBleOn(IBluetoothCallback btCallback) throws Exception {
        mInOrder.verify(mAdapterBinder).onToBleOn();

        btCallback.onBluetoothStateChange(State.TURNING_OFF, State.BLE_ON);
        syncHandler(MESSAGE_BLUETOOTH_STATE_CHANGE);
    }

    private void transition_bleOnToOff(IBluetoothCallback btCallback) throws Exception {
        mInOrder.verify(mAdapterBinder).bleOnToOff();
        // When all the profile are started, adapterService consider it is ON
        btCallback.onBluetoothStateChange(State.BLE_TURNING_OFF, State.OFF);
        syncHandler(MESSAGE_BLUETOOTH_STATE_CHANGE);
    }

    private void transition_onToOff(IBluetoothCallback btCallback) throws Exception {
        transition_onToBleOn(btCallback);
        transition_bleOnToOff(btCallback);
    }

    @Test
    public void enable_whileTurningToBleOn_shouldEnable() throws Exception {
        mManagerService.enableBle("enable_whileTurningToBleOn_shouldEnable", mBleBinder);

        acceptBluetoothBinding();
        IBluetoothCallback btCallback = captureBluetoothCallback();
        assertThat(mManagerService.getState()).isEqualTo(State.BLE_TURNING_ON);

        // receive enable when Bluetooth is in BLE_TURNING_ON
        mManagerService.enable(0, "enable_whileTurningToBleOn_shouldEnable");

        btCallback.onBluetoothStateChange(State.BLE_TURNING_ON, State.BLE_ON);
        syncHandler(MESSAGE_BLUETOOTH_STATE_CHANGE);

        mInOrder.verify(mAdapterBinder).bleOnToOn();

        endTest();
    }

    @Test
    public void enable_whileNotYetBoundToBle_shouldEnable() throws Exception {
        mManagerService.enableBle("enable_whileTurningToBleOn_shouldEnable", mBleBinder);
        if (Flags.userSwitchDuringBleOn()) {
            assertThat(mManagerService.getState()).isEqualTo(State.BLE_TURNING_ON);
        } else {
            assertThat(mManagerService.getState()).isEqualTo(State.OFF);
        }

        // receive enable when Bluetooth is OFF and not yet binded
        mManagerService.enable(0, "enable_whileTurningToBleOn_shouldEnable");

        acceptBluetoothBinding();
        IBluetoothCallback btCallback = captureBluetoothCallback();
        assertThat(mManagerService.getState()).isEqualTo(State.BLE_TURNING_ON);

        btCallback.onBluetoothStateChange(State.BLE_TURNING_ON, State.BLE_ON);
        syncHandler(MESSAGE_BLUETOOTH_STATE_CHANGE);

        mInOrder.verify(mAdapterBinder).bleOnToOn();

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
        mInOrder.verify(mAdapterBinder).offToBleOn(anyBoolean(), anyString());
        btCallback.onBluetoothStateChange(State.OFF, State.BLE_TURNING_ON);
        syncHandler(MESSAGE_BLUETOOTH_STATE_CHANGE);
        assertThat(mManagerService.getState()).isEqualTo(State.BLE_TURNING_ON);

        serviceConnection.onServiceDisconnected(
                new ComponentName("", "com.android.bluetooth.btservice.AdapterService"));
        syncHandler(MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED);
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
    public void disableAirplane_whenNothing_startBluetooth() throws Exception {
        mManagerService.enable(0, "disableAirplane_whenNothing_startBluetooth");
        transition_offToOn();

        assertThat(mLooper.nextMessage()).isNull();

        mManagerService.onAirplaneModeChanged(false);

        endTest();
    }

    @Test
    public void disable_whenBinding_bluetoothShouldStop_new() throws Exception {
        mManagerService.enable(0, "disable_whenBinding_bluetoothShouldStop_new");
        mInOrder.verify(mContext).bindServiceAsUser(any(), any(), anyInt(), any());
        mManagerService.disable("disable_whenBinding_bluetoothShouldStop_new", true);
        mInOrder.verify(mContext).unbindService(any());
        assertThat(mManagerService.getState()).isEqualTo(State.OFF);

        endTest();
    }

    @Test
    public void disable_whenTurningBleOn_bluetoothShouldStop() throws Exception {
        mManagerService.enable(0, "disable_whenBinding_bluetoothShouldStop_new");
        acceptBluetoothBinding();
        assertThat(mManagerService.getState()).isEqualTo(State.BLE_TURNING_ON);
        mManagerService.disable("disable_whenBinding_bluetoothShouldStop_new", true);
        mInOrder.verify(mContext).unbindService(any());
        assertThat(mManagerService.getState()).isEqualTo(State.OFF);

        endTest();
    }

    @Test
    public void disableAirplane_whenFactoryReset_doesNotStartBluetooth() throws Exception {
        mManagerService.enable(0, "disableAirplane_whenFactoryReset_doesNotStartBluetooth");
        IBluetoothCallback btCallback = transition_offToOn();
        assertThat(mManagerService.getState()).isEqualTo(State.ON);

        mManagerService.mHandler.sendEmptyMessage(MESSAGE_RESTORE_USER_SETTING_OFF);
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

        transition_onToBleOn(btCallback);
        mInOrder.verify(mAdapterBinder).bleOnToOff();
        assertThat(mManagerService.getState()).isEqualTo(State.BLE_TURNING_OFF);

        // As soon as we left BLE_ON, generate a call from 3p app that request to turn on Bluetooth
        mManagerService.enableBle(
                "enableBle_whenDisableAirplaneIsDelayed_startBluetooth", mBleBinder);

        // When all the profile are started, adapterService consider it is ON
        btCallback.onBluetoothStateChange(State.BLE_TURNING_OFF, State.OFF);
        syncHandler(MESSAGE_BLUETOOTH_STATE_CHANGE);

        transition_offToOn();
        assertThat(mManagerService.getState()).isEqualTo(State.ON);

        endTest();
    }

    @Test
    public void factoryReset_whileBtOff_savePropertyForLater() throws Exception {
        mManagerService.factoryReset(0);
        if (Flags.factoryResetClearAdditionalData()) {
            ExtendedMockito.verify(() -> BluetoothProperties.snoop_log_mode(null));
        }
        ExtendedMockito.verify(() -> BluetoothProperties.factory_reset(true));

        endTest();
    }

    @Test
    public void factoryReset_whileBtOn_restartWithProperty() throws Exception {
        mManagerService.enable(0, "factoryReset_whileBtOn_restartWithProperty");
        IBluetoothCallback btCallback = transition_offToOn();
        assertThat(mManagerService.getState()).isEqualTo(State.ON);

        mManagerService.factoryReset(0);
        if (Flags.factoryResetClearAdditionalData()) {
            ExtendedMockito.verify(() -> BluetoothProperties.snoop_log_mode(null));
        }
        ExtendedMockito.verify(() -> BluetoothProperties.factory_reset(true));

        transition_onToOff(btCallback);
        transition_offToOn();
        assertThat(mManagerService.getState()).isEqualTo(State.ON);

        endTest();
    }

    @Test
    public void initialStart_whenPersistentStorageOn_bluetoothStart() throws Exception {
        mPersistedState = BluetoothManagerService.BLUETOOTH_ON_BLUETOOTH;

        mManagerService = new BluetoothManagerService(mContext, mLooper.getLooper(), "default");
        mManagerService.internalHandleOnBootPhase(mUser);

        mManagerService.registerAdapter(mManagerCallback);

        // No need to call enable, Bluetooth will start automatically
        transition_offToOn();
        assertThat(mManagerService.getState()).isEqualTo(State.ON);

        endTest();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_CLEANUP_STARTING_USER,
        Flags.FLAG_USER_SWITCH_DURING_BLE_ON
    })
    public void userSwitch_whenBtOff_stayOff() throws Exception {
        mManagerService.handleSwitchUser(mNextUser);
        assertThat(mManagerService.getState()).isEqualTo(State.OFF);

        endTest();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_CLEANUP_STARTING_USER,
        Flags.FLAG_USER_SWITCH_DURING_BLE_ON
    })
    public void userSwitch_whenBleOn_stopAndDontRestart() throws Exception {
        mManagerService.enableBle("userSwitch_whenBleOn_stopAndDontRestart", mBleBinder);
        IBluetoothCallback btCallback = transition_offToBleOn();
        assertThat(mManagerService.getState()).isEqualTo(State.BLE_ON);

        mManagerService.handleSwitchUser(mNextUser);
        transition_bleOnToOff(btCallback);

        endTest();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_CLEANUP_STARTING_USER,
        Flags.FLAG_USER_SWITCH_DURING_BLE_ON
    })
    public void userSwitch_whenOn_stopAndRestart() throws Exception {
        mManagerService.enable(0, "userSwitch_whenOn_stopAndRestart");
        IBluetoothCallback btCallback = transition_offToOn();
        assertThat(mManagerService.getState()).isEqualTo(State.ON);

        mManagerService.handleSwitchUser(mNextUser);

        transition_onToOff(btCallback);
        transition_offToOn();
        assertThat(mManagerService.getState()).isEqualTo(State.ON);

        endTest();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_CLEANUP_STARTING_USER,
        Flags.FLAG_USER_SWITCH_DURING_BLE_ON
    })
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
    @EnableFlags({
        Flags.FLAG_CLEANUP_STARTING_USER,
        Flags.FLAG_USER_SWITCH_DURING_BLE_ON
    })
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
    @EnableFlags({
        Flags.FLAG_CLEANUP_STARTING_USER,
        Flags.FLAG_USER_SWITCH_DURING_BLE_ON
    })
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
    @EnableFlags({
        Flags.FLAG_CLEANUP_STARTING_USER,
        Flags.FLAG_USER_SWITCH_DURING_BLE_ON
    })
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
    @EnableFlags({
        Flags.FLAG_GRACEFUL_DISABLE_WITHOUT_MESSAGE,
        Flags.FLAG_USER_SWITCH_DURING_BLE_ON
    })
    public void disable_whenTurningOn_shouldAbortAndTurnOff() throws Exception {
        mManagerService.enable(0, "disable_whenTurningOn_shouldAbortAndTurnOff");
        IBluetoothCallback btCallback = transition_offToBleOn();
        mInOrder.verify(mAdapterBinder).bleOnToOn();

        // AdapterService go to turning_on
        btCallback.onBluetoothStateChange(State.BLE_ON, State.TURNING_ON);
        syncHandler(MESSAGE_BLUETOOTH_STATE_CHANGE);
        assertThat(mManagerService.getState()).isEqualTo(State.TURNING_ON);

        // Call disable during TURNING_ON
        mManagerService.disable("disable_whenTurningOn_shouldAbortAndTurnOff", true);

        // When all profiles are started, adapterService consider it is ON
        btCallback.onBluetoothStateChange(State.TURNING_ON, State.ON);
        syncHandler(MESSAGE_BLUETOOTH_STATE_CHANGE);

        // Because of graceful disable, it should immediately call onToBleOn
        // and then go through the full off transition.
        transition_onToOff(btCallback);

        assertThat(mManagerService.getState()).isEqualTo(State.OFF);

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
