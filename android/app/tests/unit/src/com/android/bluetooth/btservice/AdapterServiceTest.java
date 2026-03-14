/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static android.bluetooth.BluetoothDevice.TRANSPORT_LE;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;

import static com.android.bluetooth.TestUtils.getTestDevice;
import static com.android.bluetooth.TestUtils.mockGetSystemService;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.IBluetoothCallback;
import android.bluetooth.IBluetoothConnectionCallback;
import android.bluetooth.State;
import android.companion.CompanionDeviceManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.res.Resources;
import android.hardware.devicestate.DeviceStateManager;
import android.hardware.display.DisplayManager;
import android.media.AudioManager;
import android.os.BatteryStatsManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IpcDataCache;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelUuid;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.permission.PermissionManager;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Settings;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContentResolver;
import android.util.Log;

import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.bluetooth.TestLooper;
import com.android.bluetooth.TestUtils;
import com.android.bluetooth.Util;
import com.android.bluetooth.btservice.bluetoothkeystore.BluetoothKeystoreNativeInterface;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.gatt.AdvertiseManagerNativeInterface;
import com.android.bluetooth.gatt.DistanceMeasurementNativeInterface;
import com.android.bluetooth.gatt.GattNativeInterface;
import com.android.bluetooth.le_audio.LeAudioService;
import com.android.bluetooth.le_scan.PeriodicScanNativeInterface;
import com.android.bluetooth.le_scan.ScanNativeInterface;
import com.android.bluetooth.metrics.MetricsLogger;
import com.android.bluetooth.profile.ProfileService;
import com.android.bluetooth.sdp.SdpManagerNativeInterface;
import com.android.tests.bluetooth.FlagsWrapper;
import com.android.tests.bluetooth.MockitoRule;

import org.hamcrest.Matcher;
import org.hamcrest.core.AllOf;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;

import platform.test.runner.parameterized.ParameterizedAndroidJunit4;
import platform.test.runner.parameterized.Parameters;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Test cases for {@link AdapterService}. */
@MediumTest
@RunWith(ParameterizedAndroidJunit4.class)
public class AdapterServiceTest {
    private static final String TAG = AdapterServiceTest.class.getSimpleName();

    @Rule public final MockitoRule mMockitoRule = new MockitoRule();
    @Rule public final TemporaryFolder mTempFolder = new TemporaryFolder();
    @Rule public final SetFlagsRule mSetFlagsRule;

    @Mock private Context mContext;
    @Mock private AdapterNativeInterface mNativeInterface;
    @Mock private BluetoothKeystoreNativeInterface mKeystoreNativeInterface;
    @Mock private BluetoothQualityReportNativeInterface mQualityNativeInterface;
    @Mock private BluetoothHciVendorSpecificNativeInterface mHciVendorSpecificNativeInterface;
    @Mock private GattNativeInterface mGattNativeInterface;
    @Mock private AdvertiseManagerNativeInterface mAdvertiseNativeInterface;
    @Mock private DistanceMeasurementNativeInterface mDistanceNativeInterface;
    @Mock private SdpManagerNativeInterface mSdpNativeInterface;
    @Mock private LeAudioService mMockLeAudioService;

    @Mock private ApplicationInfo mMockApplicationInfo;
    @Mock private Resources mMockResources;
    @Mock private ProfileService mGattService;
    @Mock private ProfileService mMockService1;
    @Mock private ProfileService mMockService2;
    @Mock private IBluetoothCallback mIBluetoothCallback;
    @Mock private IBluetoothConnectionCallback mConnectionCallback;
    @Mock private Binder mBinder;
    @Mock private MetricsLogger mMockMetricsLogger;
    @Mock private ScanNativeInterface mScanNativeInterface;
    @Mock private PeriodicScanNativeInterface mPeriodicScanNativeInterface;
    @Mock private AdapterNativeCallback mNativeCallback;

    private static final String TEST_BT_ADDR_1 = "00:11:22:33:44:55";
    private static final String TEST_BT_ADDR_2 = "00:11:22:33:44:66";

    private static final int MESSAGE_PROFILE_SERVICE_STATE_CHANGED = 1;
    private static final int MESSAGE_PROFILE_SERVICE_REGISTERED = 2;
    private static final int MESSAGE_PROFILE_SERVICE_UNREGISTERED = 3;

    private final BluetoothDevice mDevice1 = getTestDevice(0);
    private final BluetoothDevice mDevice2 = getTestDevice(2);

    private PackageManager mMockPackageManager;
    private MockContentResolver mMockContentResolver;
    private TestLooper mLooper;

    private MockAdapterService mAdapter;

    private static class MockAdapterService extends AdapterService {
        private final LeAudioService mTestLeAudio;
        int mSetProfileServiceStateCounter = 0;
        int mSendUuidsInternalCounter = 0;
        final Map<BluetoothDevice, Integer> mConnectionStateOverlay = new HashMap<>();

        MockAdapterService(
                Looper looper,
                Context ctx,
                AdapterNativeInterface nativeInterface,
                BluetoothKeystoreNativeInterface keystoreNativeInterface,
                BluetoothQualityReportNativeInterface bluetoothQualityReportNativeInterface,
                BluetoothHciVendorSpecificNativeInterface bluetoothHciVendorSpecificNativeInterface,
                ScanNativeInterface scanNativeInterface,
                PeriodicScanNativeInterface periodicScanNativeInterface,
                GattNativeInterface gattNativeInterface,
                AdvertiseManagerNativeInterface advertiseManagerNativeInterface,
                DistanceMeasurementNativeInterface distanceMeasurementNativeInterface,
                SdpManagerNativeInterface sdpNativeInterface,
                LeAudioService leAudio) {
            super(
                    looper,
                    ctx,
                    nativeInterface,
                    keystoreNativeInterface,
                    bluetoothQualityReportNativeInterface,
                    bluetoothHciVendorSpecificNativeInterface,
                    scanNativeInterface,
                    periodicScanNativeInterface,
                    gattNativeInterface,
                    advertiseManagerNativeInterface,
                    distanceMeasurementNativeInterface,
                    sdpNativeInterface);
            mTestLeAudio = leAudio;
        }

        @Override
        public Optional<LeAudioService> getLeAudioService() {
            return Optional.ofNullable(mTestLeAudio);
        }

        @Override
        void setProfileServiceState(int profileId, int state) {
            mSetProfileServiceStateCounter++;
        }

        @Override
        void sendUuidsInternal(BluetoothDevice device, ParcelUuid[] uuids) {
            mSendUuidsInternalCounter++;
            super.sendUuidsInternal(device, uuids);
        }

        @Override
        public int getConnectionState(BluetoothDevice device) {
            if (mConnectionStateOverlay.containsKey(device)) {
                return mConnectionStateOverlay.get(device);
            }
            return super.getConnectionState(device);
        }
    }

    static void configureEnabledProfiles() {
        Log.e(TAG, "configureEnabledProfiles");

        for (int profileId = 0; profileId <= BluetoothProfile.MAX_PROFILE_ID; profileId++) {
            boolean enabled =
                    profileId == BluetoothProfile.PAN
                            || profileId == BluetoothProfile.PBAP
                            || profileId == BluetoothProfile.GATT;

            Config.setProfileEnabled(profileId, enabled);
        }
    }

    @Parameters(name = "{0}")
    public static List<FlagsWrapper> getParams() {
        return FlagsWrapper.progressionOf();
    }

    public AdapterServiceTest(FlagsWrapper flags) {
        mSetFlagsRule = new SetFlagsRule(flags.getFlags());
    }

    // Don't use @Before because the setUp and the test would be running on different thread. This
    // creates issues with the TestLooper, as it overrides Looper.myLooper for the current thread
    // only.
    private void initTest() {
        Log.e(TAG, "setUp()");
        IpcDataCache.setCacheTestMode(true);

        doReturn(mNativeCallback).when(mNativeInterface).getCallbacks();
        doReturn(true).when(mMockLeAudioService).isAvailable();
        doReturn(CONNECTION_POLICY_ALLOWED).when(mMockLeAudioService).getConnectionPolicy(any());

        mLooper = new TestLooper();
        mAdapter =
                new MockAdapterService(
                        mLooper.getLooper(),
                        mContext,
                        mNativeInterface,
                        mKeystoreNativeInterface,
                        mQualityNativeInterface,
                        mHciVendorSpecificNativeInterface,
                        mScanNativeInterface,
                        mPeriodicScanNativeInterface,
                        mGattNativeInterface,
                        mAdvertiseNativeInterface,
                        mDistanceNativeInterface,
                        mSdpNativeInterface,
                        mMockLeAudioService);

        mMockPackageManager = mock(PackageManager.class);
        try {
            doReturn(new PermissionInfo())
                    .when(mMockPackageManager)
                    .getPermissionInfo(any(), anyInt());
        } catch (PackageManager.NameNotFoundException e) {
            // Nothing
        }

        final Context context = InstrumentationRegistry.getInstrumentation().getContext();
        mMockContentResolver = new MockContentResolver(context);
        mMockContentResolver.addProvider(
                Settings.AUTHORITY,
                new MockContentProvider() {
                    @Override
                    public Bundle call(String method, String request, Bundle args) {
                        return Bundle.EMPTY;
                    }
                });

        doReturn(mTempFolder.getRoot()).when(mContext).getFilesDir();
        doAnswer(it -> new File(mTempFolder.getRoot(), it.getArgument(0)))
                .when(mContext)
                .getDatabasePath(anyString());

        doReturn(mContext).when(mContext).createDeviceProtectedStorageContext();
        doReturn(context.getCacheDir()).when(mContext).getCacheDir();
        doReturn(context.getUser()).when(mContext).getUser();
        doReturn(context.getPackageName()).when(mContext).getPackageName();
        doReturn(mMockApplicationInfo).when(mContext).getApplicationInfo();
        doReturn(mMockContentResolver).when(mContext).getContentResolver();
        doReturn(mContext).when(mContext).getApplicationContext();
        doReturn(mContext).when(mContext).createContextAsUser(UserHandle.SYSTEM, /* flags= */ 0);
        doReturn(mMockResources).when(mContext).getResources();
        doReturn(mMockPackageManager).when(mContext).getPackageManager();

        mockGetSystemService(mContext, AlarmManager.class);
        mockGetSystemService(mContext, AppOpsManager.class);
        mockGetSystemService(mContext, AudioManager.class);
        mockGetSystemService(mContext, ActivityManager.class);
        DevicePolicyManager dpm = mockGetSystemService(mContext, DevicePolicyManager.class);
        doReturn(false).when(dpm).isCommonCriteriaModeEnabled(any());
        mockGetSystemService(mContext, UserManager.class);
        mockGetSystemService(mContext, BatteryStatsManager.class);
        mockGetSystemService(mContext, CompanionDeviceManager.class);

        // SystemService that are not mocked
        mockGetSystemService(mContext, BluetoothManager.class, TestUtils.getBluetoothManager());
        var deviceStateManager = context.getSystemService(DeviceStateManager.class);
        mockGetSystemService(mContext, DeviceStateManager.class, deviceStateManager);
        var displayManager = context.getSystemService(DisplayManager.class);
        mockGetSystemService(mContext, DisplayManager.class, displayManager);
        var permissionManager = context.getSystemService(PermissionManager.class);
        mockGetSystemService(mContext, PermissionManager.class, permissionManager);
        var powerManager = context.getSystemService(PowerManager.class);
        mockGetSystemService(mContext, PowerManager.class, powerManager);

        doReturn(context.getSharedPreferences("AdapterServiceTestPrefs", Context.MODE_PRIVATE))
                .when(mContext)
                .getSharedPreferences(anyString(), anyInt());

        doAnswer(
                        invocation -> {
                            Object[] args = invocation.getArguments();
                            return context.getDatabasePath((String) args[0]);
                        })
                .when(mContext)
                .getDatabasePath(anyString());

        doReturn(mBinder).when(mIBluetoothCallback).asBinder();
        doReturn(mBinder).when(mConnectionCallback).asBinder();

        configureEnabledProfiles();

        mAdapter.onCreate();
        mAdapter.init("CoolName");
        MetricsLogger.setInstanceForTesting(mMockMetricsLogger);
        mLooper.dispatchAll();
        mAdapter.registerRemoteCallback(mIBluetoothCallback);
        mAdapter.getBluetoothConnectionCallbacks().register(mConnectionCallback);
    }

    @After
    public void tearDown() {
        Log.e(TAG, "tearDown()");

        MetricsLogger.setInstanceForTesting(null);
        IpcDataCache.setCacheTestMode(false);
    }

    private void syncHandler(int... what) {
        TestUtils.syncHandler(mLooper, what);
    }

    private void dropNextMessage(int what) {
        Message msg = mLooper.nextMessage();
        assertThat(msg).isNotNull();
        assertWithMessage("Not the expected Message:\n" + msg).that(msg.what).isEqualTo(what);
        Log.d(TAG, "Message dropped on purpose: " + msg);
    }

    private void verifyStateChange(int prevState, int currState) {
        try {
            verify(mIBluetoothCallback).onBluetoothStateChange(prevState, currState);
        } catch (RemoteException e) {
            // the mocked onBluetoothStateChange doesn't throw RemoteException
        }
    }

    private List<ProfileService> listOfMockServices() {
        return Flags.onlyStartScanDuringBleOn()
                ? List.of(mGattService, mMockService1, mMockService2)
                : List.of(mMockService1, mMockService2);
    }

    private static boolean isAdapterSuspendFeatureEnabled() {
        if (!Flags.adapterSuspendMgmt()) return false;
        return SystemProperties.getBoolean(AdapterSuspend.BLUETOOTH_SUSPEND_DISCONNECT_ACL, false)
                || SystemProperties.getBoolean(
                        AdapterSuspend.BLUETOOTH_SUSPEND_SCAN_MODE_NONE, false)
                || SystemProperties.getBoolean(AdapterSuspend.BLUETOOTH_SUSPEND_STOP_LE_SCAN, false)
                || SystemProperties.getBoolean(
                        AdapterSuspend.BLUETOOTH_SUSPEND_PAUSE_ADVERTISEMENT, false);
    }

    void offToBleOn() {
        mAdapter.offToBleOn(false, "default");
        syncHandler(0); // `init` need to be run first
        if (isAdapterSuspendFeatureEnabled()) {
            syncHandler(-2); // Init AdapterSuspendStateMachine
        }
        syncHandler(AdapterState.BLE_TURN_ON);

        if (isAdapterSuspendFeatureEnabled()) {
            // Called after callbacks are registered in DeviceStateManager
            syncHandler(0); // notifySupportedDeviceStateChanged
            syncHandler(0); // notifyDeviceStateChanged
        }

        if (!Flags.onlyStartScanDuringBleOn()) {
            syncHandler(MESSAGE_PROFILE_SERVICE_REGISTERED);
            syncHandler(MESSAGE_PROFILE_SERVICE_STATE_CHANGED);
        }

        verify(mNativeInterface).enable(any());
        mAdapter.stateChangeCallback(AbstractionLayer.BT_STATE_ON);
        syncHandler(AdapterState.BLE_STARTED);
        verifyStateChange(State.BLE_TURNING_ON, State.BLE_ON);
        assertThat(mAdapter.getState()).isEqualTo(State.BLE_ON);
    }

    void onToOff(boolean onlyGatt) {
        mAdapter.onToBleOn();
        syncHandler(AdapterState.USER_TURN_OFF);

        if (!onlyGatt) {
            List<ProfileService> services = listOfMockServices();
            // Stop (if Flags.onlyStartScanDuringBleOn GATT), PBAP, and PAN services
            assertThat(mAdapter.mSetProfileServiceStateCounter).isEqualTo(services.size() * 2);

            for (ProfileService service : services) {
                mAdapter.onProfileServiceStateChanged(service, State.OFF);
                syncHandler(MESSAGE_PROFILE_SERVICE_STATE_CHANGED);
            }
        }

        syncHandler(AdapterState.BREDR_STOPPED);
        verifyStateChange(State.TURNING_OFF, State.BLE_TURNING_OFF);

        assertThat(mAdapter.getState()).isEqualTo(State.BLE_TURNING_OFF);
    }

    void doEnable(boolean onlyGatt) {
        Log.e(TAG, "doEnable() start");

        assertThat(mAdapter.getState()).isEqualTo(State.OFF);

        offToBleOn();

        mAdapter.bleOnToOn();
        syncHandler(AdapterState.USER_TURN_ON);

        if (!onlyGatt) {
            List<ProfileService> services = listOfMockServices();
            // Start Mock (if Flags.onlyStartScanDuringBleOn GATT), PBAP, and PAN services
            assertThat(mAdapter.mSetProfileServiceStateCounter).isEqualTo(services.size());

            for (ProfileService service : services) {
                mAdapter.addProfile(service);
                syncHandler(MESSAGE_PROFILE_SERVICE_REGISTERED);
            }
            // Keep in 2 separate loop to first add the services and then eventually trigger the
            // ON transition during the callback
            for (ProfileService service : services) {
                mAdapter.onProfileServiceStateChanged(service, State.ON);
                syncHandler(MESSAGE_PROFILE_SERVICE_STATE_CHANGED);
            }
        }
        syncHandler(AdapterState.BREDR_STARTED);
        verifyStateChange(State.TURNING_ON, State.ON);

        assertThat(mAdapter.getState()).isEqualTo(State.ON);
        Log.e(TAG, "doEnable() complete success");
    }

    private void doDisable(boolean onlyGatt) {
        Log.e(TAG, "doDisable() start");
        assertThat(mAdapter.getState()).isEqualTo(State.ON);

        onToOff(onlyGatt);

        if (!Flags.onlyStartScanDuringBleOn()) {
            syncHandler(MESSAGE_PROFILE_SERVICE_STATE_CHANGED);
            syncHandler(MESSAGE_PROFILE_SERVICE_UNREGISTERED);
        }

        verify(mNativeInterface).disable();
        mAdapter.stateChangeCallback(AbstractionLayer.BT_STATE_OFF);
        syncHandler(AdapterState.BLE_STOPPED);
        // When reaching the OFF state, the cleanup is called that will destroy the state machine of
        // the adapterService. Destroying state machine send a -1 event on the handler
        syncHandler(-1);
        verifyStateChange(State.BLE_TURNING_OFF, State.OFF);

        assertThat(mAdapter.getState()).isEqualTo(State.OFF);
        Log.e(TAG, "doDisable() complete success");
    }

    /** Test: Turn Bluetooth on. Check whether the AdapterService gets started. */
    @Test
    public void testEnable() {
        initTest();
        doEnable(false);
        assertThat(mLooper.nextMessage()).isNull();
    }

    @Test
    public void enableCleanup() {
        initTest();
        doEnable(false);
        assertThat(mLooper.nextMessage()).isNull();

        mAdapter.cleanup();
        mAdapter.unregisterRemoteCallback(mIBluetoothCallback);
    }

    @Test
    public void enable_isCorrectScanMode() {
        initTest();
        final int expectedScanMode = BluetoothAdapter.SCAN_MODE_CONNECTABLE;
        final int halExpectedScanMode = AdapterService.convertScanModeToHal(expectedScanMode);

        doReturn(true).when(mNativeInterface).setScanMode(eq(halExpectedScanMode));

        doEnable(false);

        verify(mNativeInterface).setScanMode(eq(halExpectedScanMode));
        assertThat(mAdapter.getScanMode()).isEqualTo(expectedScanMode);
        assertThat(mLooper.nextMessage()).isNull();
    }

    /** Test: Turn Bluetooth on/off. Check whether the AdapterService gets started and stopped. */
    @Test
    public void testEnableDisable() {
        initTest();
        doEnable(false);
        doDisable(false);
        assertThat(mLooper.nextMessage()).isNull();
    }

    /**
     * Test: Turn Bluetooth on/off with only GATT supported. Check whether the AdapterService gets
     * started and stopped.
     */
    @Test
    @DisableFlags(Flags.FLAG_ONLY_START_SCAN_DURING_BLE_ON)
    public void testEnableDisableOnlyGatt() {
        initTest();
        Context mockContext = mock(Context.class);
        Resources mockResources = mock(Resources.class);

        doReturn(mMockApplicationInfo).when(mockContext).getApplicationInfo();
        doReturn(mMockContentResolver).when(mockContext).getContentResolver();
        doReturn(mockContext).when(mockContext).getApplicationContext();
        doReturn(mockResources).when(mockContext).getResources();
        doReturn(mMockPackageManager).when(mockContext).getPackageManager();

        // Config is set to PBAP, PAN and GATT by default. Turn off PAN and PBAP.
        Config.setProfileEnabled(BluetoothProfile.PAN, false);
        Config.setProfileEnabled(BluetoothProfile.PBAP, false);

        Config.init(mockContext);
        doEnable(true);
        doDisable(true);
        assertThat(mLooper.nextMessage()).isNull();
    }

    /** Test: Don't start GATT Check whether the AdapterService quits gracefully */
    @Test
    @DisableFlags(Flags.FLAG_ONLY_START_SCAN_DURING_BLE_ON)
    public void testGattStartTimeout() {
        initTest();
        assertThat(mAdapter.getState()).isEqualTo(State.OFF);

        mAdapter.offToBleOn(false, "default");
        syncHandler(0); // `init` need to be run first
        if (isAdapterSuspendFeatureEnabled()) {
            syncHandler(-2); // Init AdapterSuspendStateMachine
        }
        syncHandler(AdapterState.BLE_TURN_ON);
        assertThat(mAdapter.getBluetoothGatt()).isNotNull();
        if (isAdapterSuspendFeatureEnabled()) {
            // Called after callbacks are registered in DeviceStateManager
            syncHandler(0); // notifySupportedDeviceStateChanged
            syncHandler(0); // notifyDeviceStateChanged
        }
        syncHandler(MESSAGE_PROFILE_SERVICE_REGISTERED);

        // Fetch next message and never process it to simulate a timeout.
        dropNextMessage(MESSAGE_PROFILE_SERVICE_STATE_CHANGED);

        mLooper.moveTimeForward(120_000); // Skip time so the timeout fires
        syncHandler(AdapterState.BLE_START_TIMEOUT);

        // After the timeout, the state transitions to BLE_TURNING_OFF
        verifyStateChange(State.BLE_TURNING_ON, State.BLE_TURNING_OFF);
        assertThat(mAdapter.getBluetoothGatt()).isNull();

        // The shutdown sequence for GATT profile posts these messages
        syncHandler(MESSAGE_PROFILE_SERVICE_STATE_CHANGED);
        syncHandler(MESSAGE_PROFILE_SERVICE_UNREGISTERED);

        // Simulate the native stack confirming shutdown
        mAdapter.stateChangeCallback(AbstractionLayer.BT_STATE_OFF);
        syncHandler(AdapterState.BLE_STOPPED);

        // When reaching the OFF state, the cleanup is called that will destroy the state machine of
        // the adapterService. Destroying state machine send a -1 event on the handler
        syncHandler(-1);

        verifyStateChange(State.BLE_TURNING_OFF, State.OFF);
        assertThat(mAdapter.getState()).isEqualTo(State.OFF);
        assertThat(mLooper.nextMessage()).isNull();
    }

    /** Test: Don't stop GATT Check whether the AdapterService quits gracefully */
    @Test
    @DisableFlags(Flags.FLAG_ONLY_START_SCAN_DURING_BLE_ON)
    public void testGattStopTimeout() {
        initTest();
        doEnable(false);

        onToOff(false);

        // Fetch Gatt message and never process it to simulate a timeout.
        dropNextMessage(MESSAGE_PROFILE_SERVICE_STATE_CHANGED);
        dropNextMessage(MESSAGE_PROFILE_SERVICE_UNREGISTERED);

        mLooper.moveTimeForward(120_000); // Skip time so the timeout fires
        syncHandler(AdapterState.BLE_STOP_TIMEOUT);
        // When reaching the OFF state, the cleanup is called that will destroy the state machine of
        // the adapterService. Destroying state machine send a -1 event on the handler
        syncHandler(-1);
        verifyStateChange(State.BLE_TURNING_OFF, State.OFF);

        assertThat(mAdapter.getState()).isEqualTo(State.OFF);
        assertThat(mLooper.nextMessage()).isNull();
    }

    @Test
    @DisableFlags(Flags.FLAG_ONLY_START_SCAN_DURING_BLE_ON)
    public void startBleOnly_whenOnlyStartScanDuringBleOnFlagIsOff_onlyStartGattProfile() {
        initTest();
        mAdapter.bringUpBle();

        assertThat(mAdapter.getBluetoothGatt()).isNotNull();

        dropNextMessage(MESSAGE_PROFILE_SERVICE_REGISTERED);
        dropNextMessage(MESSAGE_PROFILE_SERVICE_STATE_CHANGED);
        assertThat(mLooper.nextMessage()).isNull();
    }

    @Test
    @EnableFlags(Flags.FLAG_ONLY_START_SCAN_DURING_BLE_ON)
    public void startBleOnly_whenOnlyStartScanDuringBleOnFlagIsOn_onlyStartScanController() {
        initTest();
        mAdapter.bringUpBle();

        assertThat(mAdapter.getBluetoothGatt()).isNull();
        assertThat(mAdapter.getBluetoothScan()).isNotNull();
        assertThat(mLooper.nextMessage()).isNull();
    }

    @Test
    @EnableFlags(Flags.FLAG_ONLY_START_SCAN_DURING_BLE_ON)
    public void startBleOnly_whenOnlyStartScanDuringBleOnFlagIsOn_startAndStopScanController() {
        initTest();
        assertThat(mAdapter.getBluetoothScan()).isNull();
        assertThat(mAdapter.getBluetoothGatt()).isNull();

        offToBleOn();

        assertThat(mAdapter.getBluetoothScan()).isNotNull();
        assertThat(mAdapter.getBluetoothGatt()).isNull();

        mAdapter.bleOnToOff();
        syncHandler(AdapterState.BLE_TURN_OFF);

        verify(mNativeInterface).disable();
        mAdapter.stateChangeCallback(AbstractionLayer.BT_STATE_OFF);
        syncHandler(AdapterState.BLE_STOPPED);
        // When reaching the OFF state, the cleanup is called that will destroy the state machine of
        // the adapterService. Destroying state machine send a -1 event on the handler
        syncHandler(-1);
        verifyStateChange(State.BLE_TURNING_OFF, State.OFF);

        assertThat(mAdapter.getState()).isEqualTo(State.OFF);

        assertThat(mAdapter.getBluetoothScan()).isNull();
        assertThat(mAdapter.getBluetoothGatt()).isNull();
        assertThat(mLooper.nextMessage()).isNull();
    }

    @Test
    @EnableFlags(Flags.FLAG_ONLY_START_SCAN_DURING_BLE_ON)
    public void startBrDr_whenOnlyStartScanDuringBleOnFlagIsOn_startAndStopScanController() {
        initTest();
        assertThat(mAdapter.getBluetoothScan()).isNull();
        assertThat(mAdapter.getBluetoothGatt()).isNull();
        assertThat(mAdapter.getState()).isEqualTo(State.OFF);

        offToBleOn();

        assertThat(mAdapter.getBluetoothScan()).isNotNull();
        assertThat(mAdapter.getBluetoothGatt()).isNull();

        mAdapter.bleOnToOn();
        syncHandler(AdapterState.USER_TURN_ON);

        // Start Mock PBAP, PAN, and GATT services
        assertThat(mAdapter.mSetProfileServiceStateCounter).isEqualTo(3);
        List<ProfileService> services = List.of(mMockService1, mMockService2, mGattService);

        for (ProfileService service : services) {
            mAdapter.addProfile(service);
            syncHandler(MESSAGE_PROFILE_SERVICE_REGISTERED);
        }

        for (ProfileService service : services) {
            mAdapter.onProfileServiceStateChanged(service, State.ON);
            syncHandler(MESSAGE_PROFILE_SERVICE_STATE_CHANGED);
        }

        syncHandler(AdapterState.BREDR_STARTED);
        verifyStateChange(State.TURNING_ON, State.ON);

        assertThat(mAdapter.getState()).isEqualTo(State.ON);

        mAdapter.onToBleOn();
        syncHandler(AdapterState.USER_TURN_OFF);

        // Stop PBAP, PAN, and GATT services
        assertThat(mAdapter.mSetProfileServiceStateCounter).isEqualTo(6);

        for (ProfileService service : services) {
            mAdapter.onProfileServiceStateChanged(service, State.OFF);
            syncHandler(MESSAGE_PROFILE_SERVICE_STATE_CHANGED);
        }

        syncHandler(AdapterState.BREDR_STOPPED);
        verifyStateChange(State.TURNING_OFF, State.BLE_TURNING_OFF);
        assertThat(mAdapter.getState()).isEqualTo(State.BLE_TURNING_OFF);

        assertThat(mLooper.nextMessage()).isNull();
    }

    /** Test: Don't start a classic profile Check whether the AdapterService quits gracefully */
    @Test
    @DisableFlags(Flags.FLAG_ONLY_START_SCAN_DURING_BLE_ON)
    public void testProfileStartTimeout() {
        initTest();
        assertThat(mAdapter.getState()).isEqualTo(State.OFF);

        offToBleOn();

        mAdapter.bleOnToOn();
        syncHandler(AdapterState.USER_TURN_ON);
        assertThat(mAdapter.mSetProfileServiceStateCounter).isEqualTo(2);

        mAdapter.addProfile(mMockService1);
        syncHandler(MESSAGE_PROFILE_SERVICE_REGISTERED);
        mAdapter.addProfile(mMockService2);
        syncHandler(MESSAGE_PROFILE_SERVICE_REGISTERED);
        mAdapter.onProfileServiceStateChanged(mMockService1, State.ON);
        syncHandler(MESSAGE_PROFILE_SERVICE_STATE_CHANGED);

        // Skip onProfileServiceStateChanged for mMockService2 to be in the test situation

        mLooper.moveTimeForward(120_000); // Skip time so the timeout fires
        syncHandler(AdapterState.BREDR_START_TIMEOUT);

        verifyStateChange(State.TURNING_ON, State.TURNING_OFF);
        assertThat(mAdapter.mSetProfileServiceStateCounter).isEqualTo(4);

        mAdapter.onProfileServiceStateChanged(mMockService1, State.OFF);
        syncHandler(MESSAGE_PROFILE_SERVICE_STATE_CHANGED);
        syncHandler(AdapterState.BREDR_STOPPED);

        verifyStateChange(State.TURNING_OFF, State.BLE_TURNING_OFF);
        if (!Flags.onlyStartScanDuringBleOn()) {
            syncHandler(MESSAGE_PROFILE_SERVICE_STATE_CHANGED);
            syncHandler(MESSAGE_PROFILE_SERVICE_UNREGISTERED);
        }

        assertThat(mLooper.nextMessage()).isNull();
    }

    /** Test: Don't stop a classic profile Check whether the AdapterService quits gracefully */
    @Test
    @DisableFlags(Flags.FLAG_ONLY_START_SCAN_DURING_BLE_ON)
    public void testProfileStopTimeout() {
        initTest();
        doEnable(false);

        mAdapter.onToBleOn();
        syncHandler(AdapterState.USER_TURN_OFF);
        assertThat(mAdapter.mSetProfileServiceStateCounter).isEqualTo(4);

        mAdapter.onProfileServiceStateChanged(mMockService1, State.OFF);
        syncHandler(MESSAGE_PROFILE_SERVICE_STATE_CHANGED);

        // Skip onProfileServiceStateChanged for mMockService2 to be in the test situation

        mLooper.moveTimeForward(120_000); // Skip time so the timeout fires
        syncHandler(AdapterState.BREDR_STOP_TIMEOUT);
        verifyStateChange(State.TURNING_OFF, State.BLE_TURNING_OFF);

        syncHandler(MESSAGE_PROFILE_SERVICE_STATE_CHANGED);
        syncHandler(MESSAGE_PROFILE_SERVICE_UNREGISTERED);

        // TODO(b/280518177): The only timeout to fire here should be the BREDR
        mLooper.moveTimeForward(120_000); // Skip time so the timeout fires
        syncHandler(AdapterState.BLE_STOP_TIMEOUT);
        // When reaching the OFF state, the cleanup is called that will destroy the state machine of
        // the adapterService. Destroying state machine send a -1 event on the handler
        syncHandler(-1);
        verifyStateChange(State.BLE_TURNING_OFF, State.OFF);

        assertThat(mAdapter.getState()).isEqualTo(State.OFF);
        assertThat(mLooper.nextMessage()).isNull();
    }

    /**
     * Test: Obfuscate a null Bluetooth Check if returned value from {@link
     * AdapterService#obfuscateAddress(BluetoothDevice)} is an empty array when device address is
     * null
     */
    @Test
    public void testObfuscateBluetoothAddress_NullAddress() {
        initTest();
        assertThat(mAdapter.obfuscateAddress(null)).isEmpty();
        assertThat(mLooper.nextMessage()).isNull();
    }

    @Test
    public void testAddressConsolidation() {
        initTest();
        doEnable(false); // Need BluetoothAdapter for mAdapter.getRemoteDevice
        RemoteDevices remoteDevices = mAdapter.getRemoteDevices();
        remoteDevices.addDeviceProperties(Util.getBytesFromAddress((TEST_BT_ADDR_1)));

        // Trigger address consolidate callback
        remoteDevices.addressConsolidateCallback(
                Util.getBytesFromAddress(TEST_BT_ADDR_1), Util.getBytesFromAddress(TEST_BT_ADDR_2));

        // Verify we can get correct identity address
        String identityAddress = mAdapter.getIdentityAddress(TEST_BT_ADDR_1);
        assertThat(identityAddress).isEqualTo(TEST_BT_ADDR_2);
        assertThat(mLooper.nextMessage()).isNull();
    }

    @Test
    public void testIdentityAddressType() {
        initTest();
        doEnable(false); // Need BluetoothAdapter for mAdapter.getRemoteDevice
        RemoteDevices remoteDevices = mAdapter.getRemoteDevices();
        remoteDevices.addDeviceProperties(Util.getBytesFromAddress((TEST_BT_ADDR_1)));

        int identityAddressTypePublic = 0x00; // Should map to BluetoothDevice.ADDRESS_TYPE_PUBLIC
        int identityAddressTypeRandom = 0x01; // Should map to BluetoothDevice.ADDRESS_TYPE_RANDOM

        remoteDevices.leAddressAssociateCallback(
                Util.getBytesFromAddress(TEST_BT_ADDR_1),
                Util.getBytesFromAddress(TEST_BT_ADDR_2),
                identityAddressTypePublic);

        BluetoothDevice.BluetoothAddress bluetoothAddress =
                mAdapter.getIdentityAddressWithType(TEST_BT_ADDR_1);
        assertThat(bluetoothAddress.getAddress()).isEqualTo(TEST_BT_ADDR_2);
        assertThat(bluetoothAddress.getAddressType())
                .isEqualTo(BluetoothDevice.ADDRESS_TYPE_PUBLIC);

        remoteDevices.leAddressAssociateCallback(
                Util.getBytesFromAddress(TEST_BT_ADDR_1),
                Util.getBytesFromAddress(TEST_BT_ADDR_2),
                identityAddressTypeRandom);

        bluetoothAddress = mAdapter.getIdentityAddressWithType(TEST_BT_ADDR_1);
        assertThat(bluetoothAddress.getAddress()).isEqualTo(TEST_BT_ADDR_2);
        assertThat(bluetoothAddress.getAddressType())
                .isEqualTo(BluetoothDevice.ADDRESS_TYPE_RANDOM);
    }

    @Test
    public void testIdentityAddressNullIfUnknown() {
        initTest();
        doEnable(false); // Need BluetoothAdapter for mAdapter.getRemoteDevice
        BluetoothDevice device = getTestDevice(0);

        assertThat(mAdapter.getByteIdentityAddress(device)).isNull();
        assertThat(mAdapter.getIdentityAddress(device.getAddress())).isNull();
        assertThat(mLooper.nextMessage()).isNull();
    }

    @Test
    public void getByteBrEdrAddress_withIdentityAddress_returnsIdentityAddress() {
        initTest();
        doEnable(false); // Needed for getRemoteDevice to work
        RemoteDevices remoteDevices = mAdapter.getRemoteDevices();
        BluetoothDevice device = getTestDevice(0);
        String identityAddressString = "0A:0B:0C:0D:0E:0F";
        byte[] identityAddressBytes = Util.getBytesFromAddress(identityAddressString);

        // Set up the identity address for the device
        remoteDevices.addDeviceProperties(Util.getBytesFromAddress(device.getAddress()));
        remoteDevices.leAddressAssociateCallback(
                Util.getBytesFromAddress(device.getAddress()),
                identityAddressBytes,
                BluetoothDevice.ADDRESS_TYPE_PUBLIC);

        // Call the method under test
        byte[] result = mAdapter.getByteBrEdrAddress(device);

        // Verify that the identity address is returned
        assertThat(result).isEqualTo(identityAddressBytes);
    }

    @Test
    public void getByteBrEdrAddress_withoutIdentityAddress_returnsDeviceAddress() {
        initTest();
        doEnable(false); // Needed for getRemoteDevice to work
        BluetoothDevice device = getTestDevice(0);
        byte[] deviceAddressBytes = Util.getByteAddress(device);

        // Ensure no identity address is set (this is the default state)
        assertThat(mAdapter.getByteIdentityAddress(device)).isNull();

        // Call the method under test
        byte[] result = mAdapter.getByteBrEdrAddress(device);

        // Verify that the device's own address is returned
        assertThat(result).isEqualTo(deviceAddressBytes);
    }

    @Test
    @EnableFlags(Flags.FLAG_BROADCAST_UUIDS_FROM_MAIN_LOOPER)
    public void deviceUuidsUpdated_inStateOn_broadcastsIntent() {
        initTest();
        doEnable(false); // State will be STATE_ON

        ParcelUuid[] sampleUuids = new ParcelUuid[] {BluetoothUuid.A2DP_SINK};
        mAdapter.deviceUuidsUpdated(mDevice1, sampleUuids, true);

        assertThat(mAdapter.mSendUuidsInternalCounter).isEqualTo(1);

        verifyIntentSent(
                hasAction(BluetoothDevice.ACTION_UUID),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice1));
    }

    @Test
    @EnableFlags(Flags.FLAG_BROADCAST_UUIDS_FROM_MAIN_LOOPER)
    public void deviceUuidsUpdated_inStateOn_fails_broadcastsIntent() {
        initTest();
        doEnable(false); // State will be STATE_ON

        ParcelUuid[] sampleUuids = new ParcelUuid[] {BluetoothUuid.A2DP_SINK};
        mAdapter.deviceUuidsUpdated(mDevice1, sampleUuids, false);

        assertThat(mAdapter.mSendUuidsInternalCounter).isEqualTo(0);

        verifyIntentSent(
                hasAction(BluetoothDevice.ACTION_UUID),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice1));
    }

    @Test
    @EnableFlags(Flags.FLAG_BROADCAST_UUIDS_FROM_MAIN_LOOPER)
    public void deviceUuidsUpdated_inStateBleOn_doesNotBroadcastIntent() {
        initTest();
        offToBleOn(); // State will be STATE_BLE_ON

        ParcelUuid[] sampleUuids = new ParcelUuid[] {BluetoothUuid.A2DP_SINK};
        mAdapter.deviceUuidsUpdated(mDevice1, sampleUuids, true);

        assertThat(mAdapter.mSendUuidsInternalCounter).isEqualTo(1);

        verify(mContext, never()).sendBroadcast(any(), any(), any());
    }

    @Test
    @EnableFlags(Flags.FLAG_BROADCAST_UUIDS_FROM_MAIN_LOOPER)
    public void deviceUuidsUpdated_inStateBleTurningOn_doesNotBroadcastIntent() {
        initTest();
        mAdapter.offToBleOn(false, "default");
        syncHandler(0); // `init` need to be run first
        if (isAdapterSuspendFeatureEnabled()) {
            syncHandler(-2); // Init AdapterSuspendStateMachine
        }
        syncHandler(AdapterState.BLE_TURN_ON);

        assertThat(mAdapter.getState()).isEqualTo(State.BLE_TURNING_ON);

        ParcelUuid[] sampleUuids = new ParcelUuid[] {BluetoothUuid.A2DP_SINK};
        mAdapter.deviceUuidsUpdated(mDevice1, sampleUuids, true);

        assertThat(mAdapter.mSendUuidsInternalCounter).isEqualTo(1);

        verify(mContext, never()).sendBroadcast(any(), any(), any());
    }

    @Test
    @EnableFlags(Flags.FLAG_BROADCAST_UUIDS_FROM_MAIN_LOOPER)
    public void deviceUuidsUpdated_inStateTurningOn_doesNotBroadcastIntent() {
        initTest();
        offToBleOn(); // State will be STATE_BLE_ON

        mAdapter.bleOnToOn();
        syncHandler(AdapterState.USER_TURN_ON);

        assertThat(mAdapter.getState()).isEqualTo(State.TURNING_ON);

        ParcelUuid[] sampleUuids = new ParcelUuid[] {BluetoothUuid.A2DP_SINK};
        mAdapter.deviceUuidsUpdated(mDevice1, sampleUuids, true);

        assertThat(mAdapter.mSendUuidsInternalCounter).isEqualTo(1);

        verify(mContext, never()).sendBroadcast(any(), any(), any());
    }

    @Test
    @EnableFlags(Flags.FLAG_BROADCAST_UUIDS_FROM_MAIN_LOOPER)
    public void deviceUuidsUpdated_inStateOff_dropsUpdate() {
        initTest(); // State is STATE_OFF

        ParcelUuid[] sampleUuids = new ParcelUuid[] {BluetoothUuid.A2DP_SINK};
        mAdapter.deviceUuidsUpdated(mDevice1, sampleUuids, true);

        assertThat(mAdapter.mSendUuidsInternalCounter).isEqualTo(0);

        verify(mContext, never()).sendBroadcast(any(), any(), any());
    }

    @Test
    @EnableFlags(Flags.FLAG_BROADCAST_UUIDS_FROM_MAIN_LOOPER)
    public void deviceUuidsUpdated_inStateTurningOff_dropsUpdate() {
        initTest();
        doEnable(false); // State will be STATE_ON

        mAdapter.onToBleOn();
        syncHandler(AdapterState.USER_TURN_OFF);

        assertThat(mAdapter.getState()).isEqualTo(State.TURNING_OFF);

        ParcelUuid[] sampleUuids = new ParcelUuid[] {BluetoothUuid.A2DP_SINK};
        mAdapter.deviceUuidsUpdated(mDevice1, sampleUuids, true);

        assertThat(mAdapter.mSendUuidsInternalCounter).isEqualTo(0);

        verify(mContext, never()).sendBroadcast(any(), any(), any());
    }

    @Test
    @EnableFlags(Flags.FLAG_BROADCAST_UUIDS_FROM_MAIN_LOOPER)
    public void deviceUuidsUpdated_inStateBleTurningOff_dropsUpdate() {
        initTest();
        offToBleOn(); // State will be STATE_BLE_ON

        mAdapter.bleOnToOff();
        syncHandler(AdapterState.BLE_TURN_OFF);

        assertThat(mAdapter.getState()).isEqualTo(State.BLE_TURNING_OFF);

        ParcelUuid[] sampleUuids = new ParcelUuid[] {BluetoothUuid.A2DP_SINK};
        mAdapter.deviceUuidsUpdated(mDevice1, sampleUuids, true);

        assertThat(mAdapter.mSendUuidsInternalCounter).isEqualTo(0);

        verify(mContext, never()).sendBroadcast(any(), any(), any());
    }

    /**
     * Test: Get id for null address Check if returned value from {@link
     * AdapterService#getMetricId(BluetoothDevice)} is 0 when device address is null
     */
    @Test
    public void testGetMetricId_NullAddress() {
        initTest();
        assertThat(mAdapter.getMetricId(null)).isEqualTo(0);
        assertThat(mLooper.nextMessage()).isNull();
    }

    @Test
    public void testDump_doesNotCrash() {
        initTest();
        FileDescriptor fd = new FileDescriptor();
        PrintWriter writer = mock(PrintWriter.class);

        mAdapter.dump(fd, writer, new String[] {});
        mAdapter.dump(fd, writer, new String[] {"set-test-mode", "enabled"});
        mAdapter.dump(fd, writer, new String[] {"random", "arguments"});
        assertThat(mLooper.nextMessage()).isNull();
    }

    InOrder prepareLeAudioWithConnectedDevices(
            List<BluetoothDevice> devices,
            int groupId,
            boolean returnOnSetAutoActiveModeState,
            int returnOnGetConnectionStateLeAudio,
            int returnOnGetConnectionStateAdapter) {
        doEnable(false);

        doReturn(groupId).when(mMockLeAudioService).getGroupId(any());

        doReturn(returnOnGetConnectionStateLeAudio)
                .when(mMockLeAudioService)
                .getConnectionState(any());

        for (BluetoothDevice device : devices) {
            mAdapter.mConnectionStateOverlay.put(device, returnOnGetConnectionStateAdapter);
        }

        doReturn(returnOnSetAutoActiveModeState)
                .when(mMockLeAudioService)
                .setAutoActiveModeState(groupId, false);
        doReturn(devices).when(mMockLeAudioService).getGroupDevices(groupId);

        return inOrder(mMockLeAudioService);
    }

    @Test
    public void testGattConnectionToLeAudioDevice_whenDeviceIsNotConnected_success() {
        initTest();
        int groupId = 1;
        int getConnectionState_LeAudioService = STATE_CONNECTED;
        int getConnectionState_AdapterService =
                BluetoothDevice.CONNECTION_STATE_ENCRYPTED_LE
                        | BluetoothDevice.CONNECTION_STATE_CONNECTED;
        InOrder order =
                prepareLeAudioWithConnectedDevices(
                        List.of(mDevice1),
                        groupId,
                        true,
                        getConnectionState_LeAudioService,
                        getConnectionState_AdapterService);

        mAdapter.notifyDirectLeGattClientConnect(1, mDevice1);

        order.verify(mMockLeAudioService).setAutoActiveModeState(groupId, false);
        assertThat(mAdapter.mLeGattClientsControllingAutoActiveMode.size()).isEqualTo(1);
    }

    @Test
    public void testGattConnectionToLeAudioDevice_whenDeviceIsConnected_ignore() {
        initTest();
        int groupId = 1;
        int getConnectionState_LeAudioService = STATE_CONNECTED;
        int getConnectionState_AdapterService =
                BluetoothDevice.CONNECTION_STATE_ENCRYPTED_LE
                        | BluetoothDevice.CONNECTION_STATE_CONNECTED;
        InOrder order =
                prepareLeAudioWithConnectedDevices(
                        List.of(mDevice1),
                        groupId,
                        false,
                        getConnectionState_LeAudioService,
                        getConnectionState_AdapterService);

        mAdapter.notifyDirectLeGattClientConnect(1, mDevice1);

        order.verify(mMockLeAudioService).setAutoActiveModeState(groupId, false);
        assertThat(mAdapter.mLeGattClientsControllingAutoActiveMode).isEmpty();
    }

    @Test
    public void testGattConnectionToLeAudioDevice_whenLeAudioIsNotAllowed_ignore() {
        initTest();
        int groupId = 1;
        int getConnectionState_LeAudioService = STATE_DISCONNECTED;
        int getConnectionState_AdapterService =
                BluetoothDevice.CONNECTION_STATE_ENCRYPTED_LE
                        | BluetoothDevice.CONNECTION_STATE_CONNECTED;
        InOrder order =
                prepareLeAudioWithConnectedDevices(
                        List.of(mDevice1),
                        groupId,
                        false,
                        getConnectionState_LeAudioService,
                        getConnectionState_AdapterService);

        doReturn(CONNECTION_POLICY_FORBIDDEN).when(mMockLeAudioService).getConnectionPolicy(any());
        mAdapter.notifyDirectLeGattClientConnect(1, mDevice1);

        order.verify(mMockLeAudioService, never()).setAutoActiveModeState(groupId, false);
        assertThat(mAdapter.mLeGattClientsControllingAutoActiveMode).isEmpty();
    }

    @Test
    public void testGattConnectionToLeAudioDevice_failedToConnect() {
        initTest();
        int groupId = 1;
        int clientIf = 1;

        int getConnectionState_LeAudioService = STATE_CONNECTED;
        int getConnectionState_AdapterService =
                BluetoothDevice.CONNECTION_STATE_ENCRYPTED_LE
                        | BluetoothDevice.CONNECTION_STATE_CONNECTED;
        InOrder order =
                prepareLeAudioWithConnectedDevices(
                        List.of(mDevice1),
                        groupId,
                        true,
                        getConnectionState_LeAudioService,
                        getConnectionState_AdapterService);

        mAdapter.notifyDirectLeGattClientConnect(clientIf, mDevice1);

        order.verify(mMockLeAudioService).setAutoActiveModeState(groupId, false);
        assertThat(mAdapter.mLeGattClientsControllingAutoActiveMode.size()).isEqualTo(1);

        mAdapter.notifyGattClientConnectFailed(clientIf, mDevice1);
        order.verify(mMockLeAudioService).setAutoActiveModeState(groupId, true);
        assertThat(mAdapter.mLeGattClientsControllingAutoActiveMode).isEmpty();
    }

    @Test
    public void testGattConnectionToLeAudioDevice_triggerDisconnected() {
        initTest();
        int groupId = 1;
        int clientIf = 1;

        int getConnectionState_LeAudioService = STATE_DISCONNECTED;
        int getConnectionState_AdapterService = BluetoothDevice.CONNECTION_STATE_DISCONNECTED;
        InOrder order =
                prepareLeAudioWithConnectedDevices(
                        List.of(mDevice1),
                        groupId,
                        true,
                        getConnectionState_LeAudioService,
                        getConnectionState_AdapterService);
        InOrder orderNative = inOrder(mNativeInterface);

        mAdapter.notifyDirectLeGattClientConnect(clientIf, mDevice1);

        order.verify(mMockLeAudioService).setAutoActiveModeState(groupId, false);
        assertThat(mAdapter.mLeGattClientsControllingAutoActiveMode.size()).isEqualTo(1);

        mAdapter.notifyGattClientDisconnect(clientIf, mDevice1);
        orderNative.verify(mNativeInterface, never()).disconnectAcl(any(), anyInt());
        order.verify(mMockLeAudioService).setAutoActiveModeState(groupId, true);
        assertThat(mAdapter.mLeGattClientsControllingAutoActiveMode).isEmpty();
    }

    @Test
    public void testGattConnectionToLeAudioDevice_triggerDisconnecting() {
        initTest();
        int groupId = 1;
        int clientIf = 1;
        int getConnectionState_LeAudioService = STATE_CONNECTED;
        int getConnectionState_AdapterService =
                BluetoothDevice.CONNECTION_STATE_ENCRYPTED_LE
                        | BluetoothDevice.CONNECTION_STATE_CONNECTED;
        InOrder order =
                prepareLeAudioWithConnectedDevices(
                        List.of(mDevice1),
                        groupId,
                        true,
                        getConnectionState_LeAudioService,
                        getConnectionState_AdapterService);

        InOrder orderNative = inOrder(mNativeInterface);

        mAdapter.notifyDirectLeGattClientConnect(clientIf, mDevice1);

        order.verify(mMockLeAudioService).setAutoActiveModeState(groupId, false);
        assertThat(mAdapter.mLeGattClientsControllingAutoActiveMode.size()).isEqualTo(1);

        mAdapter.notifyGattClientDisconnect(clientIf, mDevice1);
        order.verify(mMockLeAudioService).setAutoActiveModeState(groupId, true);
        orderNative.verify(mNativeInterface).disconnectAcl(any(), eq(TRANSPORT_LE));
        assertThat(mAdapter.mLeGattClientsControllingAutoActiveMode).isEmpty();
    }

    @Test
    public void testGattConnectionToLeAudioDevice_connectingMultipleClients() {
        initTest();
        int groupId = 1;
        int clientIf = 1;
        int clientIfTwo = 2;

        int getConnectionState_LeAudioService = STATE_CONNECTED;
        int getConnectionState_AdapterService =
                BluetoothDevice.CONNECTION_STATE_ENCRYPTED_LE
                        | BluetoothDevice.CONNECTION_STATE_CONNECTED;
        InOrder order =
                prepareLeAudioWithConnectedDevices(
                        List.of(mDevice1),
                        groupId,
                        true,
                        getConnectionState_LeAudioService,
                        getConnectionState_AdapterService);

        InOrder orderNative = inOrder(mNativeInterface);

        // Connect first client to device
        mAdapter.notifyDirectLeGattClientConnect(clientIf, mDevice1);

        order.verify(mMockLeAudioService).setAutoActiveModeState(groupId, false);
        assertThat(mAdapter.mLeGattClientsControllingAutoActiveMode.size()).isEqualTo(1);

        // Connect second client to device
        mAdapter.notifyDirectLeGattClientConnect(clientIfTwo, mDevice1);

        order.verify(mMockLeAudioService, never()).setAutoActiveModeState(groupId, false);
        assertThat(mAdapter.mLeGattClientsControllingAutoActiveMode.size()).isEqualTo(2);

        // Disconnect first client to device
        mAdapter.notifyGattClientDisconnect(clientIf, mDevice1);
        order.verify(mMockLeAudioService, never()).setAutoActiveModeState(groupId, true);
        orderNative.verify(mNativeInterface, never()).disconnectAcl(any(), anyInt());
        assertThat(mAdapter.mLeGattClientsControllingAutoActiveMode.size()).isEqualTo(1);

        // Disconnect second client to device
        mAdapter.notifyGattClientDisconnect(clientIfTwo, mDevice1);
        order.verify(mMockLeAudioService).setAutoActiveModeState(groupId, true);
        orderNative.verify(mNativeInterface, times(1)).disconnectAcl(any(), eq(TRANSPORT_LE));
        assertThat(mAdapter.mLeGattClientsControllingAutoActiveMode).isEmpty();
    }

    @Test
    public void testGattConnectionToLeAudioDevice_connectingMultipleDevicesInSameGroup() {
        initTest();
        int groupId = 1;
        int clientIf = 1;
        int clientIfTwo = 2;

        int getConnectionState_LeAudioService = STATE_CONNECTED;
        int getConnectionState_AdapterService =
                BluetoothDevice.CONNECTION_STATE_ENCRYPTED_LE
                        | BluetoothDevice.CONNECTION_STATE_CONNECTED;
        InOrder order =
                prepareLeAudioWithConnectedDevices(
                        List.of(mDevice1, mDevice2),
                        groupId,
                        true,
                        getConnectionState_LeAudioService,
                        getConnectionState_AdapterService);

        InOrder orderNative = inOrder(mNativeInterface);

        // Connecting device one
        doReturn(true).when(mMockLeAudioService).setAutoActiveModeState(groupId, false);
        mAdapter.notifyDirectLeGattClientConnect(clientIf, mDevice1);

        order.verify(mMockLeAudioService).setAutoActiveModeState(groupId, false);
        assertThat(mAdapter.mLeGattClientsControllingAutoActiveMode.size()).isEqualTo(1);

        // Connecting device two
        mAdapter.notifyDirectLeGattClientConnect(clientIfTwo, mDevice2);

        order.verify(mMockLeAudioService, never()).setAutoActiveModeState(groupId, false);
        assertThat(mAdapter.mLeGattClientsControllingAutoActiveMode.size()).isEqualTo(2);

        // Disconnect first device
        mAdapter.notifyGattClientDisconnect(clientIf, mDevice1);
        order.verify(mMockLeAudioService, never()).setAutoActiveModeState(groupId, true);
        orderNative.verify(mNativeInterface, never()).disconnectAcl(any(), anyInt());
        assertThat(mAdapter.mLeGattClientsControllingAutoActiveMode.size()).isEqualTo(1);

        // Disconnect second device
        mAdapter.notifyGattClientDisconnect(clientIfTwo, mDevice2);
        order.verify(mMockLeAudioService).setAutoActiveModeState(groupId, true);
        orderNative.verify(mNativeInterface, times(2)).disconnectAcl(any(), eq(TRANSPORT_LE));
        assertThat(mAdapter.mLeGattClientsControllingAutoActiveMode).isEmpty();
    }

    @Test
    public void testGattConnectionToLeAudioDevice_remoteSwitchesToActiveBeforeDisconnect() {
        initTest();
        int groupId = 1;
        int clientIf = 1;
        int clientIfTwo = 2;

        int getConnectionState_LeAudioService = STATE_CONNECTED;
        int getConnectionState_AdapterService =
                BluetoothDevice.CONNECTION_STATE_ENCRYPTED_LE
                        | BluetoothDevice.CONNECTION_STATE_CONNECTED;
        InOrder order =
                prepareLeAudioWithConnectedDevices(
                        List.of(mDevice1, mDevice2),
                        groupId,
                        true,
                        getConnectionState_LeAudioService,
                        getConnectionState_AdapterService);

        InOrder orderNative = inOrder(mNativeInterface);

        // Connecting device one
        doReturn(true).when(mMockLeAudioService).setAutoActiveModeState(groupId, false);
        mAdapter.notifyDirectLeGattClientConnect(clientIf, mDevice1);

        order.verify(mMockLeAudioService).setAutoActiveModeState(groupId, false);
        assertThat(mAdapter.mLeGattClientsControllingAutoActiveMode.size()).isEqualTo(1);

        // Connecting device two
        mAdapter.notifyDirectLeGattClientConnect(clientIfTwo, mDevice2);

        order.verify(mMockLeAudioService, never()).setAutoActiveModeState(groupId, false);
        assertThat(mAdapter.mLeGattClientsControllingAutoActiveMode.size()).isEqualTo(2);

        // Remote switches to Active
        doReturn(true).when(mMockLeAudioService).isAutoActiveModeEnabled(groupId);

        // Disconnect first device
        mAdapter.notifyGattClientDisconnect(clientIf, mDevice1);
        order.verify(mMockLeAudioService, never()).setAutoActiveModeState(groupId, true);
        orderNative.verify(mNativeInterface, never()).disconnectAcl(any(), anyInt());
        assertThat(mAdapter.mLeGattClientsControllingAutoActiveMode.size()).isEqualTo(1);

        // Disconnect second device
        mAdapter.notifyGattClientDisconnect(clientIfTwo, mDevice2);

        // Verify devices will not be disconnected
        order.verify(mMockLeAudioService).setAutoActiveModeState(groupId, true);
        orderNative.verify(mNativeInterface, never()).disconnectAcl(any(), anyInt());
        assertThat(mAdapter.mLeGattClientsControllingAutoActiveMode).isEmpty();
    }

    @Test
    public void testSuspendWithoutPendingSetScanRequest() {
        initTest();
        InOrder order = inOrder(mNativeInterface);
        final int scanModeNone =
                AdapterService.convertScanModeToHal(BluetoothAdapter.SCAN_MODE_NONE);
        final int scanModeConnectable =
                AdapterService.convertScanModeToHal(BluetoothAdapter.SCAN_MODE_CONNECTABLE);

        doReturn(true).when(mNativeInterface).setScanMode(anyInt());

        // When suspending, we should set the scan state to none.
        mAdapter.setScanMode(BluetoothAdapter.SCAN_MODE_CONNECTABLE, "test");
        order.verify(mNativeInterface).setScanMode(eq(scanModeConnectable));
        mAdapter.setSuspendState(true);
        order.verify(mNativeInterface).setScanMode(eq(scanModeNone));

        // Extraneous call to suspend won't trigger another setScanMode.
        mAdapter.setSuspendState(true);
        order.verify(mNativeInterface, never()).setScanMode(anyInt());

        // When resuming, we should restore the scan state.
        mAdapter.setSuspendState(false);
        order.verify(mNativeInterface).setScanMode(eq(scanModeConnectable));
    }

    @Test
    public void testSuspendWithPendingSetScanRequest() {
        initTest();
        InOrder order = inOrder(mNativeInterface);
        final int scanModeNone =
                AdapterService.convertScanModeToHal(BluetoothAdapter.SCAN_MODE_NONE);
        final int scanModeConnectable =
                AdapterService.convertScanModeToHal(BluetoothAdapter.SCAN_MODE_CONNECTABLE);
        final int scanModeDiscoverable =
                AdapterService.convertScanModeToHal(
                        BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE);

        doReturn(true).when(mNativeInterface).setScanMode(anyInt());

        // When suspending, we should set the scan state to none.
        mAdapter.setScanMode(BluetoothAdapter.SCAN_MODE_CONNECTABLE, "test");
        order.verify(mNativeInterface).setScanMode(eq(scanModeConnectable));
        mAdapter.setSuspendState(true);
        order.verify(mNativeInterface).setScanMode(eq(scanModeNone));

        // If during suspending process we receive a set scan request, we should not carry it out.
        mAdapter.setScanMode(BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE, "test");
        order.verify(mNativeInterface, never()).setScanMode(eq(scanModeDiscoverable));

        // The pending request shall be carried out during resume.
        mAdapter.setSuspendState(false);
        order.verify(mNativeInterface).setScanMode(eq(scanModeDiscoverable));
    }

    @Test
    public void onDeviceDisconnected_reasonReported() throws RemoteException {
        initTest();
        doEnable(false);

        final int reason = BluetoothStatusCodes.ERROR_UNKNOWN;
        final byte[] address = Util.getByteAddress(mDevice1);

        mAdapter.getRemoteDevices()
                .aclStateChangeCallback(
                        0, // status
                        address,
                        mDevice1.getAddressType(),
                        1, // BluetoothDevice.TRANSPORT_BR_EDR
                        AbstractionLayer.BT_ACL_STATE_DISCONNECTED,
                        reason,
                        0 // handle
                        );
        mLooper.dispatchAll();

        ArgumentCaptor<BluetoothDevice> deviceCaptor =
                ArgumentCaptor.forClass(BluetoothDevice.class);
        verify(mConnectionCallback).onDeviceDisconnected(deviceCaptor.capture(), eq(reason));
        assertThat(deviceCaptor.getValue().getAddress()).isEqualTo(mDevice1.getAddress());
    }

    /**
     * Test: Verify that on TV devices, {@link AdapterService#disconnectAllEnabledProfiles} calls
     * {@link AdapterNativeInterface#disconnectAllAcls} directly.
     */
    @Test
    public void disconnectAllEnabledProfiles_onTv_disconnectsAcl() {
        initTest();
        doEnable(false);
        doReturn(true).when(mMockPackageManager).hasSystemFeature(PackageManager.FEATURE_LEANBACK);

        final int reason = BluetoothStatusCodes.SUCCESS;
        int result = mAdapter.disconnectAllEnabledProfiles(mDevice1, reason);

        assertThat(result).isEqualTo(BluetoothStatusCodes.SUCCESS);
        verify(mNativeInterface).disconnectAllAcls(eq(mDevice1));
    }

    /**
     * Test: Verify that on non-TV devices, {@link AdapterService#disconnectAllEnabledProfiles} does
     * not call {@link AdapterNativeInterface#disconnectAllAcls} directly.
     */
    @Test
    public void disconnectAllEnabledProfiles_onNonTv_doesNotDisconnectAcl() {
        initTest();
        doEnable(false);
        doReturn(false).when(mMockPackageManager).hasSystemFeature(PackageManager.FEATURE_LEANBACK);

        final int reason = BluetoothStatusCodes.SUCCESS;
        int result = mAdapter.disconnectAllEnabledProfiles(mDevice1, reason);

        assertThat(result).isEqualTo(BluetoothStatusCodes.SUCCESS);
        verify(mNativeInterface, never()).disconnectAllAcls(any(BluetoothDevice.class));
    }

    @SafeVarargs
    private void verifyIntentSent(Matcher<Intent>... matchers) {
        verify(mContext)
                .sendBroadcast(
                        org.mockito.hamcrest.MockitoHamcrest.argThat(AllOf.allOf(matchers)),
                        any(),
                        any());
    }
}
