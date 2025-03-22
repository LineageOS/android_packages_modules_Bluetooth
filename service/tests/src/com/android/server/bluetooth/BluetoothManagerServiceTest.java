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

import static android.bluetooth.BluetoothAdapter.STATE_BLE_ON;
import static android.bluetooth.BluetoothAdapter.STATE_OFF;
import static android.bluetooth.BluetoothAdapter.STATE_ON;
import static android.bluetooth.BluetoothAdapter.STATE_TURNING_OFF;
import static android.bluetooth.BluetoothAdapter.STATE_TURNING_ON;

import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;

import static com.android.server.bluetooth.BluetoothManagerService.MESSAGE_BLUETOOTH_SERVICE_CONNECTED;
import static com.android.server.bluetooth.BluetoothManagerService.MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED;
import static com.android.server.bluetooth.BluetoothManagerService.MESSAGE_BLUETOOTH_STATE_CHANGE;
import static com.android.server.bluetooth.BluetoothManagerService.MESSAGE_DISABLE;
import static com.android.server.bluetooth.BluetoothManagerService.MESSAGE_ENABLE;
import static com.android.server.bluetooth.BluetoothManagerService.MESSAGE_HANDLE_DISABLE_DELAYED;
import static com.android.server.bluetooth.BluetoothManagerService.MESSAGE_RESTART_BLUETOOTH_SERVICE;
import static com.android.server.bluetooth.BluetoothManagerService.MESSAGE_RESTORE_USER_SETTING_OFF;
import static com.android.server.bluetooth.BluetoothManagerService.MESSAGE_TIMEOUT_BIND;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.validateMockitoUsage;
import static org.mockito.Mockito.verify;

import android.annotation.SuppressLint;
import android.app.AppOpsManager;
import android.app.PropertyInvalidatedCache;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.IBluetooth;
import android.bluetooth.IBluetoothCallback;
import android.bluetooth.IBluetoothManager;
import android.bluetooth.IBluetoothManagerCallback;
import android.bluetooth.IBluetoothStateChangeCallback;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Message;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.test.TestLooper;
import android.permission.PermissionManager;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.FlagsParameterization;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Settings;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.bluetooth.flags.Flags;

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
import org.mockito.MockitoAnnotations;
import org.mockito.hamcrest.MockitoHamcrest;

import platform.test.runner.parameterized.ParameterizedAndroidJunit4;
import platform.test.runner.parameterized.Parameters;

import java.util.List;
import java.util.stream.IntStream;

@RunWith(ParameterizedAndroidJunit4.class)
@SuppressLint("AndroidFrameworkRequiresPermission")
public class BluetoothManagerServiceTest {

    @Rule public final SetFlagsRule mSetFlagsRule;

    @Parameters(name = "{0}")
    public static List<FlagsParameterization> getParams() {
        return FlagsParameterization.allCombinationsOf(
                Flags.FLAG_SYSTEM_SERVER_REMOVE_EXTRA_THREAD_JUMP);
    }

    public BluetoothManagerServiceTest(FlagsParameterization flags) {
        mSetFlagsRule = new SetFlagsRule(flags);
    }

    private static final int STATE_BLE_TURNING_ON = 14; // can't find the symbol because hidden api
    private static final int STATE_BLE_TURNING_OFF = 16; // can't find the symbol because hidden api

    private final Context mTargetContext =
            InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Mock BluetoothServerProxy mBluetoothServerProxy;
    @Mock Context mContext;
    @Mock UserManager mUserManager;
    @Mock UserHandle mUserHandle;
    @Mock IBinder mBinder;
    @Mock IBluetoothManagerCallback mManagerCallback;
    @Mock IBluetoothStateChangeCallback mStateChangeCallback;
    @Mock IBluetooth mAdapterService;
    @Mock AdapterBinder mAdapterBinder;
    @Mock AppOpsManager mAppOpsManager;
    @Mock PermissionManager mPermissionManager;

    private int mPersistedState = BluetoothManagerService.BLUETOOTH_OFF;

    private InOrder mInOrder;
    private TestLooper mLooper;
    private BluetoothManagerService mManagerService;

    private static class ServerQuery
            extends PropertyInvalidatedCache.QueryHandler<IBluetoothManager, Integer> {
        @Override
        public Integer apply(IBluetoothManager x) {
            return -1;
        }

        @Override
        public boolean shouldBypassCache(IBluetoothManager x) {
            return true;
        }
    }

    static {
        // Required for reading DeviceConfig.
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .adoptShellPermissionIdentity(
                        android.Manifest.permission.INTERACT_ACROSS_USERS_FULL);
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mInOrder = inOrder(mContext, mManagerCallback, mAdapterBinder);

        PropertyInvalidatedCache<IBluetoothManager, Integer> testCache =
                new PropertyInvalidatedCache<>(
                        8,
                        IBluetoothManager.IPC_CACHE_MODULE_SYSTEM,
                        IBluetoothManager.GET_SYSTEM_STATE_API,
                        IBluetoothManager.GET_SYSTEM_STATE_API,
                        new ServerQuery());
        PropertyInvalidatedCache.setTestMode(true);
        testCache.testPropertyName();
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

        doReturn(BluetoothManagerServiceTest.class.getSimpleName()).when(mContext).getPackageName();
        doReturn(mContext).when(mContext).createContextAsUser(any(), anyInt());
        doReturn(mTargetContext.getContentResolver()).when(mContext).getContentResolver();
        doReturn(mTargetContext.getPackageManager()).when(mContext).getPackageManager();
        doReturn(mUserManager).when(mContext).getSystemService(UserManager.class);
        doReturn(mAppOpsManager).when(mContext).getSystemService(AppOpsManager.class);
        doReturn(mPermissionManager).when(mContext).getSystemService(PermissionManager.class);

        doReturn(mBinder).when(mManagerCallback).asBinder();

        doReturn(mAdapterBinder).when(mBluetoothServerProxy).createAdapterBinder(any());
        doReturn(mAdapterService).when(mAdapterBinder).getAdapterBinder();
        doReturn(mBinder).when(mAdapterService).asBinder();

        doReturn(mock(Intent.class))
                .when(mContext)
                .registerReceiverForAllUsers(any(), any(), eq(null), eq(null));

        doReturn(true)
                .when(mContext)
                .bindServiceAsUser(
                        any(Intent.class),
                        any(ServiceConnection.class),
                        anyInt(),
                        any(UserHandle.class));
        doNothing().when(mContext).unbindService(any());

        BluetoothServerProxy.setInstanceForTesting(mBluetoothServerProxy);

        mLooper = new TestLooper();

        mManagerService = new BluetoothManagerService(mContext, mLooper.getLooper());
        mManagerService.initialize(mUserHandle);

        mManagerService.registerAdapter(mManagerCallback);
    }

    @After
    public void tearDown() {
        PropertyInvalidatedCache.setTestMode(false);
        if (mManagerService != null) {
            mManagerService.unregisterAdapter(mManagerCallback);
            mManagerService = null;
        }
        mLooper.moveTimeForward(120_000); // 120 seconds

        assertThat(mLooper.nextMessage()).isNull();
        validateMockitoUsage();
    }

    /**
     * Dispatch all the message on the Loopper and check that the what is expected
     *
     * @param what list of message that are expected to be run by the handler
     */
    private void syncHandler(int... what) {
        IntStream.of(what)
                .forEach(
                        w -> {
                            String log = "Expecting message " + w + ": ";

                            Message msg = mLooper.nextMessage();
                            assertWithMessage(log + "but got null").that(msg).isNotNull();
                            assertWithMessage(log + "but got " + msg.what)
                                    .that(msg.what)
                                    .isEqualTo(w);
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
    public void onUserRestrictionsChanged_disallowBluetooth_onlySendDisableMessageOnSystemUser()
            throws InterruptedException {
        // Mimic the case when restriction settings changed
        doReturn(true)
                .when(mUserManager)
                .hasUserRestrictionForUser(eq(UserManager.DISALLOW_BLUETOOTH), any());
        doReturn(false)
                .when(mUserManager)
                .hasUserRestrictionForUser(eq(UserManager.DISALLOW_BLUETOOTH_SHARING), any());

        // Check if disable message sent once for system user only

        // test run on user -1, should not turning Bluetooth off
        mManagerService.onUserRestrictionsChanged(UserHandle.CURRENT);
        assertThat(mLooper.nextMessage()).isNull();

        // called from SYSTEM user, should try to toggle Bluetooth off
        mManagerService.onUserRestrictionsChanged(UserHandle.SYSTEM);
        if (!Flags.systemServerRemoveExtraThreadJump()) {
            syncHandler(MESSAGE_DISABLE);
        }
    }

    @Test
    public void enable_bindFailure_removesTimeout() throws Exception {
        doReturn(false)
                .when(mContext)
                .bindServiceAsUser(
                        any(Intent.class),
                        any(ServiceConnection.class),
                        anyInt(),
                        any(UserHandle.class));
        mManagerService.enableBle("enable_bindFailure_removesTimeout", mBinder);
        if (!Flags.systemServerRemoveExtraThreadJump()) {
            syncHandler(MESSAGE_ENABLE);
        }
        verify(mContext).unbindService(any());
        mInOrder.verify(mContext).unbindService(any());

        // TODO(b/280518177): Failed to start should be noted / reported in metrics
        // Maybe show a popup or a crash notification
        // Should we attempt to re-bind ?
    }

    @Test
    public void enable_bindTimeout() throws Exception {
        mManagerService.enableBle("enable_bindTimeout", mBinder);
        if (!Flags.systemServerRemoveExtraThreadJump()) {
            syncHandler(MESSAGE_ENABLE);
        }

        mLooper.moveTimeForward(120_000); // 120 seconds
        syncHandler(MESSAGE_TIMEOUT_BIND);
        // Force handling the message now without waiting for the timeout to fire

        // TODO(b/280518177): A lot of stuff is wrong here since when a timeout occur:
        //   * No error is printed to the user
        //   * Code stop trying to start the bluetooth.
        //   * if user ask to enable again, it will start a second bind but the first still run
    }

    private BluetoothManagerService.BluetoothServiceConnection acceptBluetoothBinding() {
        ComponentName compName =
                new ComponentName("", "com.android.bluetooth.btservice.AdapterService");

        ArgumentCaptor<BluetoothManagerService.BluetoothServiceConnection> captor =
                ArgumentCaptor.forClass(BluetoothManagerService.BluetoothServiceConnection.class);
        mInOrder.verify(mContext)
                .bindServiceAsUser(
                        any(Intent.class), captor.capture(), anyInt(), any(UserHandle.class));
        assertThat(captor.getAllValues()).hasSize(1);

        BluetoothManagerService.BluetoothServiceConnection serviceConnection =
                captor.getAllValues().get(0);
        serviceConnection.onServiceConnected(compName, mBinder);
        syncHandler(MESSAGE_BLUETOOTH_SERVICE_CONNECTED);
        return serviceConnection;
    }

    private IBluetoothCallback captureBluetoothCallback() throws Exception {
        ArgumentCaptor<IBluetoothCallback> captor =
                ArgumentCaptor.forClass(IBluetoothCallback.class);
        mInOrder.verify(mAdapterBinder).registerCallback(captor.capture(), any());
        assertThat(captor.getAllValues()).hasSize(1);
        return captor.getValue();
    }

    IBluetoothCallback transition_offToBleOn() throws Exception {
        // Binding of IBluetooth
        acceptBluetoothBinding();

        IBluetoothCallback btCallback = captureBluetoothCallback();
        mInOrder.verify(mAdapterBinder).offToBleOn(anyBoolean(), any());
        verifyBleStateIntentSent(STATE_OFF, STATE_BLE_TURNING_ON);
        mInOrder.verify(mManagerCallback).onBluetoothServiceUp(any());

        assertThat(mManagerService.getState()).isEqualTo(STATE_BLE_TURNING_ON);

        // GattService has been started by AdapterService and it will enable native side then
        // trigger the stateChangeCallback from native
        btCallback.onBluetoothStateChange(STATE_BLE_TURNING_ON, STATE_BLE_ON);
        syncHandler(MESSAGE_BLUETOOTH_STATE_CHANGE);
        verifyBleStateIntentSent(STATE_BLE_TURNING_ON, STATE_BLE_ON);
        return btCallback;
    }

    private IBluetoothCallback transition_offToOn() throws Exception {
        IBluetoothCallback btCallback = transition_offToBleOn();
        mInOrder.verify(mAdapterBinder).bleOnToOn(any());

        // AdapterService go to turning_on and start all profile on its own
        btCallback.onBluetoothStateChange(STATE_BLE_ON, STATE_TURNING_ON);
        syncHandler(MESSAGE_BLUETOOTH_STATE_CHANGE);
        verifyBleStateIntentSent(STATE_BLE_ON, STATE_TURNING_ON);
        verifyStateIntentSent(STATE_OFF, STATE_TURNING_ON);
        // When all the profile are started, adapterService consider it is ON
        btCallback.onBluetoothStateChange(STATE_TURNING_ON, STATE_ON);
        syncHandler(MESSAGE_BLUETOOTH_STATE_CHANGE);
        verifyBleStateIntentSent(STATE_TURNING_ON, STATE_ON);
        verifyStateIntentSent(STATE_TURNING_ON, STATE_ON);

        return btCallback;
    }

    private void transition_onToBleOn(IBluetoothCallback btCallback) throws Exception {
        mInOrder.verify(mAdapterBinder).onToBleOn(any());

        btCallback.onBluetoothStateChange(STATE_TURNING_OFF, STATE_BLE_ON);
        syncHandler(MESSAGE_BLUETOOTH_STATE_CHANGE);
    }

    private void transition_onToOff(IBluetoothCallback btCallback) throws Exception {
        transition_onToBleOn(btCallback);
        mInOrder.verify(mAdapterBinder).bleOnToOff(any());

        // When all the profile are started, adapterService consider it is ON
        btCallback.onBluetoothStateChange(STATE_BLE_TURNING_OFF, STATE_OFF);
        syncHandler(MESSAGE_BLUETOOTH_STATE_CHANGE);
    }

    @Test
    public void enable_whileTurningToBleOn_shouldEnable() throws Exception {
        mManagerService.enableBle("enable_whileTurningToBleOn_shouldEnable", mBinder);
        if (!Flags.systemServerRemoveExtraThreadJump()) {
            syncHandler(MESSAGE_ENABLE);
        }

        acceptBluetoothBinding();
        IBluetoothCallback btCallback = captureBluetoothCallback();
        assertThat(mManagerService.getState()).isEqualTo(STATE_BLE_TURNING_ON);

        // receive enable when Bluetooth is in BLE_TURNING_ON
        mManagerService.enable("enable_whileTurningToBleOn_shouldEnable");
        if (!Flags.systemServerRemoveExtraThreadJump()) {
            syncHandler(MESSAGE_ENABLE);
        }

        btCallback.onBluetoothStateChange(STATE_BLE_TURNING_ON, STATE_BLE_ON);
        syncHandler(MESSAGE_BLUETOOTH_STATE_CHANGE);

        mInOrder.verify(mAdapterBinder).bleOnToOn(any());
    }

    @Test
    public void enable_whileNotYetBoundToBle_shouldEnable() throws Exception {
        mManagerService.enableBle("enable_whileTurningToBleOn_shouldEnable", mBinder);
        if (!Flags.systemServerRemoveExtraThreadJump()) {
            syncHandler(MESSAGE_ENABLE);
        }
        assertThat(mManagerService.getState()).isEqualTo(STATE_OFF);

        // receive enable when Bluetooth is OFF and not yet binded
        mManagerService.enable("enable_whileTurningToBleOn_shouldEnable");
        if (!Flags.systemServerRemoveExtraThreadJump()) {
            syncHandler(MESSAGE_ENABLE);
        }

        acceptBluetoothBinding();
        IBluetoothCallback btCallback = captureBluetoothCallback();
        assertThat(mManagerService.getState()).isEqualTo(STATE_BLE_TURNING_ON);

        btCallback.onBluetoothStateChange(STATE_BLE_TURNING_ON, STATE_BLE_ON);
        syncHandler(MESSAGE_BLUETOOTH_STATE_CHANGE);

        mInOrder.verify(mAdapterBinder).bleOnToOn(any());
    }

    @Test
    public void offToBleOn() throws Exception {
        mManagerService.enableBle("test_offToBleOn", mBinder);
        if (!Flags.systemServerRemoveExtraThreadJump()) {
            syncHandler(MESSAGE_ENABLE);
        }

        transition_offToBleOn();

        // Check that there was no transition to STATE_ON
        mInOrder.verify(mAdapterBinder, never()).bleOnToOn(any());
        assertThat(mManagerService.getState()).isEqualTo(STATE_BLE_ON);
    }

    @Test
    public void offToOn() throws Exception {
        mManagerService.enable("test_offToOn");
        if (!Flags.systemServerRemoveExtraThreadJump()) {
            syncHandler(MESSAGE_ENABLE);
        }

        transition_offToOn();

        assertThat(mManagerService.getState()).isEqualTo(STATE_ON);
    }

    @Test
    public void crash_whileTransitionState_canRecover() throws Exception {
        mManagerService.enableBle("crash_whileTransitionState_canRecover", mBinder);
        if (!Flags.systemServerRemoveExtraThreadJump()) {
            syncHandler(MESSAGE_ENABLE);
        }

        BluetoothManagerService.BluetoothServiceConnection serviceConnection =
                acceptBluetoothBinding();

        IBluetoothCallback btCallback = captureBluetoothCallback();
        mInOrder.verify(mAdapterBinder).offToBleOn(anyBoolean(), any());
        btCallback.onBluetoothStateChange(STATE_OFF, STATE_BLE_TURNING_ON);
        syncHandler(MESSAGE_BLUETOOTH_STATE_CHANGE);
        assertThat(mManagerService.getState()).isEqualTo(STATE_BLE_TURNING_ON);

        serviceConnection.onServiceDisconnected(
                new ComponentName("", "com.android.bluetooth.btservice.AdapterService"));
        syncHandler(MESSAGE_BLUETOOTH_SERVICE_DISCONNECTED);
        assertThat(mManagerService.getState()).isEqualTo(STATE_OFF);

        // Send a late bluetoothStateChange (since it can happen concurrently)
        btCallback.onBluetoothStateChange(STATE_BLE_TURNING_ON, STATE_BLE_ON);
        syncHandler(MESSAGE_BLUETOOTH_STATE_CHANGE);

        // Bluetooth is still OFF and doesn't crash
        assertThat(mManagerService.getState()).isEqualTo(STATE_OFF);

        mLooper.moveTimeForward(120_000);
        discardMessage(MESSAGE_RESTART_BLUETOOTH_SERVICE);
    }

    @Test
    public void disableAirplane_whenNothing_startBluetooth() throws Exception {
        if (!Flags.systemServerRemoveExtraThreadJump()) {
            mPersistedState = BluetoothManagerService.BLUETOOTH_ON_BLUETOOTH;
        }
        mManagerService.enable("disableAirplane_whenNothing_startBluetooth");
        if (!Flags.systemServerRemoveExtraThreadJump()) {
            discardMessage(MESSAGE_ENABLE);
        } else {
            transition_offToOn();
        }

        assertThat(mLooper.nextMessage()).isNull();

        mManagerService.onAirplaneModeChanged(false);
        if (!Flags.systemServerRemoveExtraThreadJump()) {
            discardMessage(MESSAGE_ENABLE);
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_SYSTEM_SERVER_REMOVE_EXTRA_THREAD_JUMP)
    public void disable_whenBinding_bluetoothShouldStop_new() throws Exception {
        mManagerService.enable("disable_whenBinding_bluetoothShouldStop_new");
        mInOrder.verify(mContext).bindServiceAsUser(any(), any(), anyInt(), any());
        mManagerService.disable("disable_whenBinding_bluetoothShouldStop_new", true);
        mInOrder.verify(mContext).unbindService(any());
        assertThat(mManagerService.getState()).isEqualTo(STATE_OFF);
    }

    @Test
    @EnableFlags(Flags.FLAG_SYSTEM_SERVER_REMOVE_EXTRA_THREAD_JUMP)
    public void disable_whenTurningBleOn_bluetoothShouldStop() throws Exception {
        mManagerService.enable("disable_whenBinding_bluetoothShouldStop_new");
        acceptBluetoothBinding();
        assertThat(mManagerService.getState()).isEqualTo(STATE_BLE_TURNING_ON);
        mManagerService.disable("disable_whenBinding_bluetoothShouldStop_new", true);
        mInOrder.verify(mContext).unbindService(any());
        assertThat(mManagerService.getState()).isEqualTo(STATE_OFF);
    }

    @Test
    @DisableFlags(Flags.FLAG_SYSTEM_SERVER_REMOVE_EXTRA_THREAD_JUMP)
    public void disable_whenBinding_bluetoothShouldStop_old() throws Exception {
        mManagerService.enable("disable_whenBinding_bluetoothShouldStop_old");
        syncHandler(MESSAGE_ENABLE);
        mManagerService.disable("disable_whenBinding_bluetoothShouldStop_old", true);
        syncHandler(MESSAGE_DISABLE);

        IBluetoothCallback btCallback = transition_offToBleOn();
        assertThat(mManagerService.getState()).isEqualTo(STATE_BLE_TURNING_OFF);

        btCallback.onBluetoothStateChange(STATE_BLE_TURNING_OFF, STATE_OFF);
        syncHandler(MESSAGE_BLUETOOTH_STATE_CHANGE);

        mLooper.moveTimeForward(BluetoothManagerService.ENABLE_DISABLE_DELAY_MS);
        syncHandler(MESSAGE_DISABLE);

        assertThat(mManagerService.getState()).isEqualTo(STATE_OFF);
    }

    @Test
    public void disableAirplane_whenFactoryReset_doesNotStartBluetooth() throws Exception {
        mManagerService.enable("disableAirplane_whenFactoryReset_doesNotStartBluetooth");
        if (!Flags.systemServerRemoveExtraThreadJump()) {
            syncHandler(MESSAGE_ENABLE);
        }
        IBluetoothCallback btCallback = transition_offToOn();
        assertThat(mManagerService.getState()).isEqualTo(STATE_ON);

        mManagerService.mHandler.sendEmptyMessage(MESSAGE_RESTORE_USER_SETTING_OFF);
        syncHandler(MESSAGE_RESTORE_USER_SETTING_OFF);
        if (!Flags.systemServerRemoveExtraThreadJump()) {
            syncHandler(MESSAGE_DISABLE);
            mLooper.moveTimeForward(BluetoothManagerService.ENABLE_DISABLE_DELAY_MS);
            syncHandler(MESSAGE_HANDLE_DISABLE_DELAYED);
            mLooper.moveTimeForward(BluetoothManagerService.ENABLE_DISABLE_DELAY_MS);
            syncHandler(MESSAGE_HANDLE_DISABLE_DELAYED);
        }
        transition_onToOff(btCallback);

        mManagerService.onAirplaneModeChanged(false);
        assertThat(mLooper.nextMessage()).isNull(); // Must not create a MESSAGE_ENABLE
    }

    @Test
    @EnableFlags({
        Flags.FLAG_SYSTEM_SERVER_REMOVE_EXTRA_THREAD_JUMP,
        Flags.FLAG_ENABLE_BLE_WHILE_DISABLING_AIRPLANE
    })
    public void enableBle_whenDisableAirplaneIsDelayed_startBluetooth() throws Exception {
        mManagerService.enable("enableBle_whenDisableAirplaneIsDelayed_startBluetooth");
        IBluetoothCallback btCallback = transition_offToOn();
        mManagerService.onAirplaneModeChanged(true);
        assertThat(mManagerService.getState()).isEqualTo(STATE_TURNING_OFF);

        // Generate an event that will be delayed due to the TURNING_OFF state
        mManagerService.onAirplaneModeChanged(false);

        transition_onToBleOn(btCallback);
        mInOrder.verify(mAdapterBinder).bleOnToOff(any());
        assertThat(mManagerService.getState()).isEqualTo(STATE_BLE_TURNING_OFF);

        // As soon as we left BLE_ON, generate a call from 3p app that request to turn on Bluetooth
        mManagerService.enableBle("enableBle_whenDisableAirplaneIsDelayed_startBluetooth", mBinder);

        // When all the profile are started, adapterService consider it is ON
        btCallback.onBluetoothStateChange(STATE_BLE_TURNING_OFF, STATE_OFF);
        syncHandler(MESSAGE_BLUETOOTH_STATE_CHANGE);

        transition_offToOn();
        assertThat(mManagerService.getState()).isEqualTo(STATE_ON);
    }

    @SafeVarargs
    private void verifyIntentSent(Matcher<Intent>... matchers) {
        mInOrder.verify(mContext)
                .sendBroadcastAsUser(
                        MockitoHamcrest.argThat(AllOf.allOf(matchers)), any(), any(), any());
    }

    private void verifyBleStateIntentSent(int from, int to) {
        verifyIntentSent(
                hasAction(BluetoothAdapter.ACTION_BLE_STATE_CHANGED),
                hasExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, from),
                hasExtra(BluetoothAdapter.EXTRA_STATE, to));
    }

    private void verifyStateIntentSent(int from, int to) {
        verifyIntentSent(
                hasAction(BluetoothAdapter.ACTION_STATE_CHANGED),
                hasExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, from),
                hasExtra(BluetoothAdapter.EXTRA_STATE, to));
    }
}
