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

import static android.bluetooth.BluetoothAdapter.STATE_BLE_ON;
import static android.bluetooth.BluetoothAdapter.STATE_BLE_TURNING_OFF;
import static android.bluetooth.BluetoothAdapter.STATE_BLE_TURNING_ON;
import static android.bluetooth.BluetoothAdapter.STATE_OFF;
import static android.bluetooth.BluetoothAdapter.STATE_ON;
import static android.bluetooth.BluetoothAdapter.STATE_TURNING_OFF;
import static android.bluetooth.BluetoothAdapter.STATE_TURNING_ON;
import static android.bluetooth.BluetoothDevice.TRANSPORT_LE;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import static com.android.bluetooth.TestUtils.getBluetoothManager;
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
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.IBluetoothCallback;
import android.companion.CompanionDeviceManager;
import android.content.Context;
import android.content.SharedPreferences;
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
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.permission.PermissionManager;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Settings;
import android.sysprop.BluetoothProperties;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContentResolver;
import android.util.Log;

import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.bluetooth.TestLooper;
import com.android.bluetooth.TestUtils;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.bluetoothkeystore.BluetoothKeystoreNativeInterface;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.gatt.AdvertiseManagerNativeInterface;
import com.android.bluetooth.gatt.DistanceMeasurementNativeInterface;
import com.android.bluetooth.gatt.GattNativeInterface;
import com.android.bluetooth.le_audio.LeAudioService;
import com.android.bluetooth.le_scan.PeriodicScanNativeInterface;
import com.android.bluetooth.le_scan.ScanNativeInterface;
import com.android.bluetooth.sdp.SdpManagerNativeInterface;
import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.tests.bluetooth.FlagsWrapper;
import com.android.tests.bluetooth.StaticMockitoRule;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;

import platform.test.runner.parameterized.ParameterizedAndroidJunit4;
import platform.test.runner.parameterized.Parameters;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;
import java.util.Optional;

/** Test cases for {@link AdapterService}. */
@MediumTest
@RunWith(ParameterizedAndroidJunit4.class)
public class AdapterServiceTest {
    private static final String TAG = AdapterServiceTest.class.getSimpleName();

    @Rule
    public final StaticMockitoRule mMockitoRule = new StaticMockitoRule(BluetoothProperties.class);

    @Rule public final SetFlagsRule mSetFlagsRule;

    @Mock private Context mMockContext;
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
    @Mock private ProfileService mMockGattService;
    @Mock private ProfileService mMockService1;
    @Mock private ProfileService mMockService2;
    @Mock private IBluetoothCallback mIBluetoothCallback;
    @Mock private Binder mBinder;
    @Mock private MetricsLogger mMockMetricsLogger;
    @Mock private ScanNativeInterface mScanNativeInterface;
    @Mock private PeriodicScanNativeInterface mPeriodicScanNativeInterface;
    @Mock private JniCallbacks mJniCallbacks;

    private static final String TEST_BT_ADDR_1 = "00:11:22:33:44:55";
    private static final String TEST_BT_ADDR_2 = "00:11:22:33:44:66";

    private static final int MESSAGE_PROFILE_SERVICE_STATE_CHANGED = 1;
    private static final int MESSAGE_PROFILE_SERVICE_REGISTERED = 2;
    private static final int MESSAGE_PROFILE_SERVICE_UNREGISTERED = 3;

    private final BluetoothDevice mDevice1 = getTestDevice(0);
    private final BluetoothDevice mDevice2 = getTestDevice(2);

    // SystemService that are not mocked
    private BluetoothManager mBluetoothManager;
    private DeviceStateManager mDeviceStateManager;
    private DisplayManager mDisplayManager;
    private PowerManager mPowerManager;
    private PermissionManager mPermissionManager;

    private static final int CONTEXT_SWITCH_MS = 100;

    private PackageManager mMockPackageManager;
    private MockContentResolver mMockContentResolver;
    private TestLooper mLooper;

    private MockAdapterService mAdapterService;

    private static class MockAdapterService extends AdapterService {
        private final LeAudioService mTestLeAudio;
        int mSetProfileServiceStateCounter = 0;

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
        return FlagsWrapper.progressionOf(
                Flags.FLAG_WATCH_DEVICE_OVERRIDE_AIRPLANE_MODE,
                Flags.FLAG_BOND_STATE_MACHINE_LOOPER,
                Flags.FLAG_ON_TO_BLE_ON_VIA_OFF);
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

        doReturn(mJniCallbacks).when(mNativeInterface).getCallbacks();
        doReturn(true).when(mMockLeAudioService).isAvailable();
        doReturn(CONNECTION_POLICY_ALLOWED).when(mMockLeAudioService).getConnectionPolicy(any());

        mLooper = new TestLooper();
        mAdapterService =
                new MockAdapterService(
                        mLooper.getLooper(),
                        mMockContext,
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

        when(mMockContext.getCacheDir()).thenReturn(context.getCacheDir());
        when(mMockContext.getUser()).thenReturn(context.getUser());
        when(mMockContext.getPackageName()).thenReturn(context.getPackageName());
        when(mMockContext.getApplicationInfo()).thenReturn(mMockApplicationInfo);
        when(mMockContext.getContentResolver()).thenReturn(mMockContentResolver);
        when(mMockContext.getApplicationContext()).thenReturn(mMockContext);
        when(mMockContext.createContextAsUser(UserHandle.SYSTEM, /* flags= */ 0))
                .thenReturn(mMockContext);
        when(mMockContext.getResources()).thenReturn(mMockResources);
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);

        mBluetoothManager = getBluetoothManager();
        mDeviceStateManager = context.getSystemService(DeviceStateManager.class);
        mDisplayManager = context.getSystemService(DisplayManager.class);
        mPermissionManager = context.getSystemService(PermissionManager.class);
        mPowerManager = context.getSystemService(PowerManager.class);

        mockGetSystemService(mMockContext, AlarmManager.class);
        mockGetSystemService(mMockContext, AppOpsManager.class);
        mockGetSystemService(mMockContext, AudioManager.class);
        mockGetSystemService(mMockContext, ActivityManager.class);
        DevicePolicyManager dpm = mockGetSystemService(mMockContext, DevicePolicyManager.class);
        doReturn(false).when(dpm).isCommonCriteriaModeEnabled(any());
        mockGetSystemService(mMockContext, UserManager.class);
        mockGetSystemService(mMockContext, BatteryStatsManager.class);
        mockGetSystemService(mMockContext, BluetoothManager.class, mBluetoothManager);
        mockGetSystemService(mMockContext, CompanionDeviceManager.class);
        mockGetSystemService(mMockContext, DeviceStateManager.class, mDeviceStateManager);
        mockGetSystemService(mMockContext, DisplayManager.class, mDisplayManager);
        mockGetSystemService(mMockContext, PermissionManager.class, mPermissionManager);
        mockGetSystemService(mMockContext, PowerManager.class, mPowerManager);

        when(mMockContext.getSharedPreferences(anyString(), anyInt()))
                .thenReturn(
                        context.getSharedPreferences(
                                "AdapterServiceTestPrefs", Context.MODE_PRIVATE));

        doAnswer(
                        invocation -> {
                            Object[] args = invocation.getArguments();
                            return context.getDatabasePath((String) args[0]);
                        })
                .when(mMockContext)
                .getDatabasePath(anyString());

        when(mIBluetoothCallback.asBinder()).thenReturn(mBinder);

        configureEnabledProfiles();

        Config.init(mMockContext);
        MetricsLogger.setInstanceForTesting(mMockMetricsLogger);
        mAdapterService.onCreate();
        mLooper.dispatchAll();
        mAdapterService.registerRemoteCallback(mIBluetoothCallback);
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

    private void verifyStateChange(int prevState, int currState, int timeoutMs) {
        try {
            verify(mIBluetoothCallback, timeout(timeoutMs))
                    .onBluetoothStateChange(prevState, currState);
        } catch (RemoteException e) {
            // the mocked onBluetoothStateChange doesn't throw RemoteException
        }
    }

    private static void verifyStateChange(IBluetoothCallback cb, int prevState, int currState) {
        try {
            verify(cb).onBluetoothStateChange(prevState, currState);
        } catch (RemoteException e) {
            // the mocked onBluetoothStateChange doesn't throw RemoteException
        }
    }

    private List<ProfileService> listOfMockServices() {
        return Flags.onlyStartScanDuringBleOn()
                ? List.of(mMockGattService, mMockService1, mMockService2)
                : List.of(mMockService1, mMockService2);
    }

    static void offToBleOn(
            TestLooper looper,
            ProfileService gattService,
            AdapterService adapter,
            Context ctx,
            IBluetoothCallback callback,
            AdapterNativeInterface nativeInterface) {
        adapter.offToBleOn(false, "default");
        TestUtils.syncHandler(looper, 0); // `init` need to be run first
        if (Flags.adapterSuspendMgmt()) {
            TestUtils.syncHandler(looper, -2); // Init AdapterSuspendStateMachine
        }
        TestUtils.syncHandler(looper, AdapterState.BLE_TURN_ON);
        verifyStateChange(callback, STATE_OFF, STATE_BLE_TURNING_ON);

        if (Flags.adapterSuspendMgmt()) {
            // Called after callbacks are registered in DeviceStateManager
            TestUtils.syncHandler(looper, 0); // notifySupportedDeviceStateChanged
            TestUtils.syncHandler(looper, 0); // notifyDeviceStateChanged
        }

        if (!Flags.onlyStartScanDuringBleOn()) {
            TestUtils.syncHandler(looper, MESSAGE_PROFILE_SERVICE_REGISTERED);
            TestUtils.syncHandler(looper, MESSAGE_PROFILE_SERVICE_STATE_CHANGED);
        }

        verify(nativeInterface).enable();
        adapter.stateChangeCallback(AbstractionLayer.BT_STATE_ON);
        TestUtils.syncHandler(looper, AdapterState.BLE_STARTED);
        verifyStateChange(callback, STATE_BLE_TURNING_ON, STATE_BLE_ON);
        assertThat(adapter.getState()).isEqualTo(STATE_BLE_ON);
    }

    static void onToBleOn(
            TestLooper looper,
            MockAdapterService adapter,
            Context ctx,
            IBluetoothCallback callback,
            boolean onlyGatt,
            List<ProfileService> services) {
        adapter.onToBleOn();
        TestUtils.syncHandler(looper, AdapterState.USER_TURN_OFF);
        verifyStateChange(callback, STATE_ON, STATE_TURNING_OFF);

        if (!onlyGatt) {
            // Stop (if Flags.onlyStartScanDuringBleOn GATT), PBAP, and PAN services
            assertThat(adapter.mSetProfileServiceStateCounter).isEqualTo(services.size() * 2);

            for (ProfileService service : services) {
                adapter.onProfileServiceStateChanged(service, STATE_OFF);
                TestUtils.syncHandler(looper, MESSAGE_PROFILE_SERVICE_STATE_CHANGED);
            }
        }

        TestUtils.syncHandler(looper, AdapterState.BREDR_STOPPED);
        verifyStateChange(callback, STATE_TURNING_OFF, STATE_BLE_ON);

        assertThat(adapter.getState()).isEqualTo(STATE_BLE_ON);
    }

    void doEnable(boolean onlyGatt) {
        doEnable(
                mLooper,
                mMockGattService,
                mAdapterService,
                mMockContext,
                onlyGatt,
                listOfMockServices(),
                mNativeInterface);
    }

    // Method is re-used in other AdapterService*Test
    static void doEnable(
            TestLooper looper,
            ProfileService gattService,
            MockAdapterService adapter,
            Context ctx,
            boolean onlyGatt,
            List<ProfileService> services,
            AdapterNativeInterface nativeInterface) {
        Log.e(TAG, "doEnable() start");

        IBluetoothCallback callback = mock(IBluetoothCallback.class);
        Binder binder = mock(Binder.class);
        doReturn(binder).when(callback).asBinder();
        adapter.registerRemoteCallback(callback);

        assertThat(adapter.getState()).isEqualTo(STATE_OFF);

        offToBleOn(looper, gattService, adapter, ctx, callback, nativeInterface);

        adapter.bleOnToOn();
        TestUtils.syncHandler(looper, AdapterState.USER_TURN_ON);
        verifyStateChange(callback, STATE_BLE_ON, STATE_TURNING_ON);

        if (!onlyGatt) {
            // Start Mock (if Flags.onlyStartScanDuringBleOn GATT), PBAP, and PAN services
            assertThat(adapter.mSetProfileServiceStateCounter).isEqualTo(services.size());

            for (ProfileService service : services) {
                adapter.addProfile(service);
                TestUtils.syncHandler(looper, MESSAGE_PROFILE_SERVICE_REGISTERED);
            }
            // Keep in 2 separate loop to first add the services and then eventually trigger the
            // ON transition during the callback
            for (ProfileService service : services) {
                adapter.onProfileServiceStateChanged(service, STATE_ON);
                TestUtils.syncHandler(looper, MESSAGE_PROFILE_SERVICE_STATE_CHANGED);
            }
        }
        TestUtils.syncHandler(looper, AdapterState.BREDR_STARTED);
        verifyStateChange(callback, STATE_TURNING_ON, STATE_ON);

        assertThat(adapter.getState()).isEqualTo(STATE_ON);
        adapter.unregisterRemoteCallback(callback);
        Log.e(TAG, "doEnable() complete success");
    }

    void doDisable(boolean onlyGatt) {
        doDisable(
                mLooper,
                mAdapterService,
                mMockContext,
                onlyGatt,
                listOfMockServices(),
                mNativeInterface);
    }

    private static void doDisable(
            TestLooper looper,
            MockAdapterService adapter,
            Context ctx,
            boolean onlyGatt,
            List<ProfileService> services,
            AdapterNativeInterface nativeInterface) {
        Log.e(TAG, "doDisable() start");
        IBluetoothCallback callback = mock(IBluetoothCallback.class);
        Binder binder = mock(Binder.class);
        doReturn(binder).when(callback).asBinder();
        adapter.registerRemoteCallback(callback);

        assertThat(adapter.getState()).isEqualTo(STATE_ON);

        onToBleOn(looper, adapter, ctx, callback, onlyGatt, services);

        adapter.bleOnToOff();
        TestUtils.syncHandler(looper, AdapterState.BLE_TURN_OFF);
        verifyStateChange(callback, STATE_BLE_ON, STATE_BLE_TURNING_OFF);

        if (!Flags.onlyStartScanDuringBleOn()) {
            TestUtils.syncHandler(looper, MESSAGE_PROFILE_SERVICE_STATE_CHANGED);
            TestUtils.syncHandler(looper, MESSAGE_PROFILE_SERVICE_UNREGISTERED);
        }

        verify(nativeInterface).disable();
        adapter.stateChangeCallback(AbstractionLayer.BT_STATE_OFF);
        TestUtils.syncHandler(looper, AdapterState.BLE_STOPPED);
        // When reaching the OFF state, the cleanup is called that will destroy the state machine of
        // the adapterService. Destroying state machine send a -1 event on the handler
        TestUtils.syncHandler(looper, -1);
        verifyStateChange(callback, STATE_BLE_TURNING_OFF, STATE_OFF);

        assertThat(adapter.getState()).isEqualTo(STATE_OFF);
        adapter.unregisterRemoteCallback(callback);
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

        mAdapterService.cleanup();
        mAdapterService.unregisterRemoteCallback(mIBluetoothCallback);
    }

    @Test
    public void enable_isCorrectScanMode() {
        initTest();
        final int expectedScanMode = BluetoothAdapter.SCAN_MODE_CONNECTABLE;
        final int halExpectedScanMode = AdapterService.convertScanModeToHal(expectedScanMode);

        doReturn(true).when(mNativeInterface).setScanMode(eq(halExpectedScanMode));

        doEnable(false);

        verify(mNativeInterface).setScanMode(eq(halExpectedScanMode));
        assertThat(mAdapterService.getScanMode()).isEqualTo(expectedScanMode);
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

        when(mockContext.getApplicationInfo()).thenReturn(mMockApplicationInfo);
        when(mockContext.getContentResolver()).thenReturn(mMockContentResolver);
        when(mockContext.getApplicationContext()).thenReturn(mockContext);
        when(mockContext.getResources()).thenReturn(mockResources);
        when(mockContext.getPackageManager()).thenReturn(mMockPackageManager);

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
        assertThat(mAdapterService.getState()).isEqualTo(STATE_OFF);

        mAdapterService.offToBleOn(false, "default");
        syncHandler(0); // `init` need to be run first
        if (Flags.adapterSuspendMgmt()) {
            syncHandler(-2); // Init AdapterSuspendStateMachine
        }
        syncHandler(AdapterState.BLE_TURN_ON);
        verifyStateChange(STATE_OFF, STATE_BLE_TURNING_ON);
        assertThat(mAdapterService.getBluetoothGatt()).isNotNull();
        if (Flags.adapterSuspendMgmt()) {
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
        verifyStateChange(STATE_BLE_TURNING_ON, STATE_BLE_TURNING_OFF);
        assertThat(mAdapterService.getBluetoothGatt()).isNull();

        // The shutdown sequence for GATT profile posts these messages
        syncHandler(MESSAGE_PROFILE_SERVICE_STATE_CHANGED);
        syncHandler(MESSAGE_PROFILE_SERVICE_UNREGISTERED);

        // Simulate the native stack confirming shutdown
        mAdapterService.stateChangeCallback(AbstractionLayer.BT_STATE_OFF);
        syncHandler(AdapterState.BLE_STOPPED);

        // When reaching the OFF state, the cleanup is called that will destroy the state machine of
        // the adapterService. Destroying state machine send a -1 event on the handler
        syncHandler(-1);

        verifyStateChange(STATE_BLE_TURNING_OFF, STATE_OFF);
        assertThat(mAdapterService.getState()).isEqualTo(STATE_OFF);
        assertThat(mLooper.nextMessage()).isNull();
    }

    /** Test: Don't stop GATT Check whether the AdapterService quits gracefully */
    @Test
    @DisableFlags(Flags.FLAG_ONLY_START_SCAN_DURING_BLE_ON)
    public void testGattStopTimeout() {
        initTest();
        doEnable(false);

        onToBleOn(
                mLooper,
                mAdapterService,
                mMockContext,
                mIBluetoothCallback,
                false,
                listOfMockServices());

        mAdapterService.bleOnToOff();
        syncHandler(AdapterState.BLE_TURN_OFF);
        verifyStateChange(STATE_BLE_ON, STATE_BLE_TURNING_OFF, CONTEXT_SWITCH_MS);
        assertThat(mAdapterService.getBluetoothGatt()).isNull();

        // Fetch Gatt message and never process it to simulate a timeout.
        dropNextMessage(MESSAGE_PROFILE_SERVICE_STATE_CHANGED);
        dropNextMessage(MESSAGE_PROFILE_SERVICE_UNREGISTERED);

        mLooper.moveTimeForward(120_000); // Skip time so the timeout fires
        syncHandler(AdapterState.BLE_STOP_TIMEOUT);
        // When reaching the OFF state, the cleanup is called that will destroy the state machine of
        // the adapterService. Destroying state machine send a -1 event on the handler
        syncHandler(-1);
        verifyStateChange(STATE_BLE_TURNING_OFF, STATE_OFF);

        assertThat(mAdapterService.getState()).isEqualTo(STATE_OFF);
        assertThat(mLooper.nextMessage()).isNull();
    }

    @Test
    @DisableFlags(Flags.FLAG_ONLY_START_SCAN_DURING_BLE_ON)
    public void startBleOnly_whenOnlyStartScanDuringBleOnFlagIsOff_onlyStartGattProfile() {
        initTest();
        mAdapterService.bringUpBle();

        assertThat(mAdapterService.getBluetoothGatt()).isNotNull();

        dropNextMessage(MESSAGE_PROFILE_SERVICE_REGISTERED);
        dropNextMessage(MESSAGE_PROFILE_SERVICE_STATE_CHANGED);
        assertThat(mLooper.nextMessage()).isNull();
    }

    @Test
    @EnableFlags(Flags.FLAG_ONLY_START_SCAN_DURING_BLE_ON)
    public void startBleOnly_whenOnlyStartScanDuringBleOnFlagIsOn_onlyStartScanController() {
        initTest();
        mAdapterService.bringUpBle();

        assertThat(mAdapterService.getBluetoothGatt()).isNull();
        assertThat(mAdapterService.getBluetoothScan()).isNotNull();
        assertThat(mLooper.nextMessage()).isNull();
    }

    @Test
    @EnableFlags(Flags.FLAG_ONLY_START_SCAN_DURING_BLE_ON)
    public void startBleOnly_whenOnlyStartScanDuringBleOnFlagIsOn_startAndStopScanController() {
        initTest();
        assertThat(mAdapterService.getBluetoothScan()).isNull();
        assertThat(mAdapterService.getBluetoothGatt()).isNull();

        IBluetoothCallback callback = mock(IBluetoothCallback.class);
        Binder binder = mock(Binder.class);
        doReturn(binder).when(callback).asBinder();
        mAdapterService.registerRemoteCallback(callback);

        offToBleOn(
                mLooper,
                mMockGattService,
                mAdapterService,
                mMockContext,
                mIBluetoothCallback,
                mNativeInterface);

        assertThat(mAdapterService.getBluetoothScan()).isNotNull();
        assertThat(mAdapterService.getBluetoothGatt()).isNull();

        mAdapterService.bleOnToOff();
        syncHandler(AdapterState.BLE_TURN_OFF);
        verifyStateChange(callback, STATE_BLE_ON, STATE_BLE_TURNING_OFF);

        verify(mNativeInterface).disable();
        mAdapterService.stateChangeCallback(AbstractionLayer.BT_STATE_OFF);
        syncHandler(AdapterState.BLE_STOPPED);
        // When reaching the OFF state, the cleanup is called that will destroy the state machine of
        // the adapterService. Destroying state machine send a -1 event on the handler
        syncHandler(-1);
        verifyStateChange(callback, STATE_BLE_TURNING_OFF, STATE_OFF);

        assertThat(mAdapterService.getState()).isEqualTo(STATE_OFF);
        mAdapterService.unregisterRemoteCallback(callback);

        assertThat(mAdapterService.getBluetoothScan()).isNull();
        assertThat(mAdapterService.getBluetoothGatt()).isNull();
        assertThat(mLooper.nextMessage()).isNull();
    }

    @Test
    @EnableFlags(Flags.FLAG_ONLY_START_SCAN_DURING_BLE_ON)
    public void startBrDr_whenOnlyStartScanDuringBleOnFlagIsOn_startAndStopScanController() {
        initTest();
        assertThat(mAdapterService.getBluetoothScan()).isNull();
        assertThat(mAdapterService.getBluetoothGatt()).isNull();

        IBluetoothCallback callback = mock(IBluetoothCallback.class);
        Binder binder = mock(Binder.class);
        doReturn(binder).when(callback).asBinder();
        mAdapterService.registerRemoteCallback(callback);

        assertThat(mAdapterService.getState()).isEqualTo(STATE_OFF);

        offToBleOn(
                mLooper,
                mMockGattService,
                mAdapterService,
                mMockContext,
                mIBluetoothCallback,
                mNativeInterface);

        assertThat(mAdapterService.getBluetoothScan()).isNotNull();
        assertThat(mAdapterService.getBluetoothGatt()).isNull();

        mAdapterService.bleOnToOn();
        TestUtils.syncHandler(mLooper, AdapterState.USER_TURN_ON);
        verifyStateChange(callback, STATE_BLE_ON, STATE_TURNING_ON);

        // Start Mock PBAP, PAN, and GATT services
        assertThat(mAdapterService.mSetProfileServiceStateCounter).isEqualTo(3);
        List<ProfileService> services = List.of(mMockService1, mMockService2, mMockGattService);

        for (ProfileService service : services) {
            mAdapterService.addProfile(service);
            TestUtils.syncHandler(mLooper, MESSAGE_PROFILE_SERVICE_REGISTERED);
        }

        for (ProfileService service : services) {
            mAdapterService.onProfileServiceStateChanged(service, STATE_ON);
            TestUtils.syncHandler(mLooper, MESSAGE_PROFILE_SERVICE_STATE_CHANGED);
        }

        TestUtils.syncHandler(mLooper, AdapterState.BREDR_STARTED);
        verifyStateChange(callback, STATE_TURNING_ON, STATE_ON);

        assertThat(mAdapterService.getState()).isEqualTo(STATE_ON);

        mAdapterService.onToBleOn();
        TestUtils.syncHandler(mLooper, AdapterState.USER_TURN_OFF);
        verifyStateChange(callback, STATE_ON, STATE_TURNING_OFF);

        // Stop PBAP, PAN, and GATT services
        assertThat(mAdapterService.mSetProfileServiceStateCounter).isEqualTo(6);

        for (ProfileService service : services) {
            mAdapterService.onProfileServiceStateChanged(service, STATE_OFF);
            TestUtils.syncHandler(mLooper, MESSAGE_PROFILE_SERVICE_STATE_CHANGED);
        }

        TestUtils.syncHandler(mLooper, AdapterState.BREDR_STOPPED);
        verifyStateChange(callback, STATE_TURNING_OFF, STATE_BLE_ON);

        assertThat(mAdapterService.getState()).isEqualTo(STATE_BLE_ON);

        mAdapterService.unregisterRemoteCallback(callback);
        assertThat(mLooper.nextMessage()).isNull();
    }

    /** Test: Don't start a classic profile Check whether the AdapterService quits gracefully */
    @Test
    @DisableFlags(Flags.FLAG_ONLY_START_SCAN_DURING_BLE_ON)
    public void testProfileStartTimeout() {
        initTest();
        assertThat(mAdapterService.getState()).isEqualTo(STATE_OFF);

        offToBleOn(
                mLooper,
                mMockGattService,
                mAdapterService,
                mMockContext,
                mIBluetoothCallback,
                mNativeInterface);

        mAdapterService.bleOnToOn();
        syncHandler(AdapterState.USER_TURN_ON);
        verifyStateChange(STATE_BLE_ON, STATE_TURNING_ON);
        assertThat(mAdapterService.mSetProfileServiceStateCounter).isEqualTo(2);

        mAdapterService.addProfile(mMockService1);
        syncHandler(MESSAGE_PROFILE_SERVICE_REGISTERED);
        mAdapterService.addProfile(mMockService2);
        syncHandler(MESSAGE_PROFILE_SERVICE_REGISTERED);
        mAdapterService.onProfileServiceStateChanged(mMockService1, STATE_ON);
        syncHandler(MESSAGE_PROFILE_SERVICE_STATE_CHANGED);

        // Skip onProfileServiceStateChanged for mMockService2 to be in the test situation

        mLooper.moveTimeForward(120_000); // Skip time so the timeout fires
        syncHandler(AdapterState.BREDR_START_TIMEOUT);

        verifyStateChange(STATE_TURNING_ON, STATE_TURNING_OFF);
        assertThat(mAdapterService.mSetProfileServiceStateCounter).isEqualTo(4);

        mAdapterService.onProfileServiceStateChanged(mMockService1, STATE_OFF);
        syncHandler(MESSAGE_PROFILE_SERVICE_STATE_CHANGED);
        syncHandler(AdapterState.BREDR_STOPPED);
        verifyStateChange(STATE_TURNING_OFF, STATE_BLE_ON);

        // Ensure GATT is still running
        assertThat(mAdapterService.getBluetoothGatt()).isNotNull();
        assertThat(mLooper.nextMessage()).isNull();
    }

    /** Test: Don't stop a classic profile Check whether the AdapterService quits gracefully */
    @Test
    @DisableFlags(Flags.FLAG_ONLY_START_SCAN_DURING_BLE_ON)
    public void testProfileStopTimeout() {
        initTest();
        doEnable(false);

        mAdapterService.onToBleOn();
        syncHandler(AdapterState.USER_TURN_OFF);
        verifyStateChange(STATE_ON, STATE_TURNING_OFF);
        assertThat(mAdapterService.mSetProfileServiceStateCounter).isEqualTo(4);

        mAdapterService.onProfileServiceStateChanged(mMockService1, STATE_OFF);
        syncHandler(MESSAGE_PROFILE_SERVICE_STATE_CHANGED);

        // Skip onProfileServiceStateChanged for mMockService2 to be in the test situation

        mLooper.moveTimeForward(120_000); // Skip time so the timeout fires
        syncHandler(AdapterState.BREDR_STOP_TIMEOUT);
        verifyStateChange(STATE_TURNING_OFF, STATE_BLE_TURNING_OFF);

        syncHandler(MESSAGE_PROFILE_SERVICE_STATE_CHANGED);
        syncHandler(MESSAGE_PROFILE_SERVICE_UNREGISTERED);

        // TODO(b/280518177): The only timeout to fire here should be the BREDR
        mLooper.moveTimeForward(120_000); // Skip time so the timeout fires
        syncHandler(AdapterState.BLE_STOP_TIMEOUT);
        // When reaching the OFF state, the cleanup is called that will destroy the state machine of
        // the adapterService. Destroying state machine send a -1 event on the handler
        syncHandler(-1);
        verifyStateChange(STATE_BLE_TURNING_OFF, STATE_OFF);

        assertThat(mAdapterService.getState()).isEqualTo(STATE_OFF);
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
        assertThat(mAdapterService.obfuscateAddress(null)).isEmpty();
        assertThat(mLooper.nextMessage()).isNull();
    }

    @Test
    public void testAddressConsolidation() {
        initTest();
        doEnable(false); // Need BluetoothAdapter for mAdapterService.getRemoteDevice
        RemoteDevices remoteDevices = mAdapterService.getRemoteDevices();
        remoteDevices.addDeviceProperties(Utils.getBytesFromAddress((TEST_BT_ADDR_1)));

        // Trigger address consolidate callback
        remoteDevices.addressConsolidateCallback(
                Utils.getBytesFromAddress(TEST_BT_ADDR_1),
                Utils.getBytesFromAddress(TEST_BT_ADDR_2));

        // Verify we can get correct identity address
        String identityAddress = mAdapterService.getIdentityAddress(TEST_BT_ADDR_1);
        assertThat(identityAddress).isEqualTo(TEST_BT_ADDR_2);
        assertThat(mLooper.nextMessage()).isNull();
    }

    @Test
    public void testIdentityAddressType() {
        initTest();
        doEnable(false); // Need BluetoothAdapter for mAdapterService.getRemoteDevice
        RemoteDevices remoteDevices = mAdapterService.getRemoteDevices();
        remoteDevices.addDeviceProperties(Utils.getBytesFromAddress((TEST_BT_ADDR_1)));

        int identityAddressTypePublic = 0x00; // Should map to BluetoothDevice.ADDRESS_TYPE_PUBLIC
        int identityAddressTypeRandom = 0x01; // Should map to BluetoothDevice.ADDRESS_TYPE_RANDOM

        remoteDevices.leAddressAssociateCallback(
                Utils.getBytesFromAddress(TEST_BT_ADDR_1),
                Utils.getBytesFromAddress(TEST_BT_ADDR_2),
                identityAddressTypePublic);

        BluetoothDevice.BluetoothAddress bluetoothAddress =
                mAdapterService.getIdentityAddressWithType(TEST_BT_ADDR_1);
        assertThat(bluetoothAddress.getAddress()).isEqualTo(TEST_BT_ADDR_2);
        assertThat(bluetoothAddress.getAddressType())
                .isEqualTo(BluetoothDevice.ADDRESS_TYPE_PUBLIC);

        remoteDevices.leAddressAssociateCallback(
                Utils.getBytesFromAddress(TEST_BT_ADDR_1),
                Utils.getBytesFromAddress(TEST_BT_ADDR_2),
                identityAddressTypeRandom);

        bluetoothAddress = mAdapterService.getIdentityAddressWithType(TEST_BT_ADDR_1);
        assertThat(bluetoothAddress.getAddress()).isEqualTo(TEST_BT_ADDR_2);
        assertThat(bluetoothAddress.getAddressType())
                .isEqualTo(BluetoothDevice.ADDRESS_TYPE_RANDOM);
    }

    @Test
    public void testIdentityAddressNullIfUnknown() {
        initTest();
        doEnable(false); // Need BluetoothAdapter for mAdapterService.getRemoteDevice
        BluetoothDevice device = getTestDevice(0);

        assertThat(mAdapterService.getByteIdentityAddress(device)).isNull();
        assertThat(mAdapterService.getIdentityAddress(device.getAddress())).isNull();
        assertThat(mLooper.nextMessage()).isNull();
    }

    /**
     * Test: Get id for null address Check if returned value from {@link
     * AdapterService#getMetricId(BluetoothDevice)} is 0 when device address is null
     */
    @Test
    public void testGetMetricId_NullAddress() {
        initTest();
        assertThat(mAdapterService.getMetricId(null)).isEqualTo(0);
        assertThat(mLooper.nextMessage()).isNull();
    }

    @Test
    public void testDump_doesNotCrash() {
        initTest();
        FileDescriptor fd = new FileDescriptor();
        PrintWriter writer = mock(PrintWriter.class);

        mAdapterService.dump(fd, writer, new String[] {});
        mAdapterService.dump(fd, writer, new String[] {"set-test-mode", "enabled"});
        mAdapterService.dump(fd, writer, new String[] {"random", "arguments"});
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
        doReturn(returnOnGetConnectionStateAdapter)
                .when(mNativeInterface)
                .getConnectionState(any());

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

        mAdapterService.notifyDirectLeGattClientConnect(1, mDevice1);

        order.verify(mMockLeAudioService).setAutoActiveModeState(groupId, false);
        assertThat(mAdapterService.mLeGattClientsControllingAutoActiveMode.size()).isEqualTo(1);
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

        mAdapterService.notifyDirectLeGattClientConnect(1, mDevice1);

        order.verify(mMockLeAudioService).setAutoActiveModeState(groupId, false);
        assertThat(mAdapterService.mLeGattClientsControllingAutoActiveMode).isEmpty();
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
        mAdapterService.notifyDirectLeGattClientConnect(1, mDevice1);

        order.verify(mMockLeAudioService, never()).setAutoActiveModeState(groupId, false);
        assertThat(mAdapterService.mLeGattClientsControllingAutoActiveMode).isEmpty();
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

        mAdapterService.notifyDirectLeGattClientConnect(clientIf, mDevice1);

        order.verify(mMockLeAudioService).setAutoActiveModeState(groupId, false);
        assertThat(mAdapterService.mLeGattClientsControllingAutoActiveMode.size()).isEqualTo(1);

        mAdapterService.notifyGattClientConnectFailed(clientIf, mDevice1);
        order.verify(mMockLeAudioService).setAutoActiveModeState(groupId, true);
        assertThat(mAdapterService.mLeGattClientsControllingAutoActiveMode).isEmpty();
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

        mAdapterService.notifyDirectLeGattClientConnect(clientIf, mDevice1);

        order.verify(mMockLeAudioService).setAutoActiveModeState(groupId, false);
        assertThat(mAdapterService.mLeGattClientsControllingAutoActiveMode.size()).isEqualTo(1);

        mAdapterService.notifyGattClientDisconnect(clientIf, mDevice1);
        orderNative.verify(mNativeInterface, never()).disconnectAcl(any(), anyInt());
        order.verify(mMockLeAudioService).setAutoActiveModeState(groupId, true);
        assertThat(mAdapterService.mLeGattClientsControllingAutoActiveMode).isEmpty();
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

        mAdapterService.notifyDirectLeGattClientConnect(clientIf, mDevice1);

        order.verify(mMockLeAudioService).setAutoActiveModeState(groupId, false);
        assertThat(mAdapterService.mLeGattClientsControllingAutoActiveMode.size()).isEqualTo(1);

        mAdapterService.notifyGattClientDisconnect(clientIf, mDevice1);
        order.verify(mMockLeAudioService).setAutoActiveModeState(groupId, true);
        orderNative.verify(mNativeInterface).disconnectAcl(any(), eq(TRANSPORT_LE));
        assertThat(mAdapterService.mLeGattClientsControllingAutoActiveMode).isEmpty();
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
        mAdapterService.notifyDirectLeGattClientConnect(clientIf, mDevice1);

        order.verify(mMockLeAudioService).setAutoActiveModeState(groupId, false);
        assertThat(mAdapterService.mLeGattClientsControllingAutoActiveMode.size()).isEqualTo(1);

        // Connect second client to device
        mAdapterService.notifyDirectLeGattClientConnect(clientIfTwo, mDevice1);

        order.verify(mMockLeAudioService, never()).setAutoActiveModeState(groupId, false);
        assertThat(mAdapterService.mLeGattClientsControllingAutoActiveMode.size()).isEqualTo(2);

        // Disconnect first client to device
        mAdapterService.notifyGattClientDisconnect(clientIf, mDevice1);
        order.verify(mMockLeAudioService, never()).setAutoActiveModeState(groupId, true);
        orderNative.verify(mNativeInterface, never()).disconnectAcl(any(), anyInt());
        assertThat(mAdapterService.mLeGattClientsControllingAutoActiveMode.size()).isEqualTo(1);

        // Disconnect second client to device
        mAdapterService.notifyGattClientDisconnect(clientIfTwo, mDevice1);
        order.verify(mMockLeAudioService).setAutoActiveModeState(groupId, true);
        orderNative.verify(mNativeInterface, times(1)).disconnectAcl(any(), eq(TRANSPORT_LE));
        assertThat(mAdapterService.mLeGattClientsControllingAutoActiveMode).isEmpty();
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
        when(mMockLeAudioService.setAutoActiveModeState(groupId, false)).thenReturn(true);
        mAdapterService.notifyDirectLeGattClientConnect(clientIf, mDevice1);

        order.verify(mMockLeAudioService).setAutoActiveModeState(groupId, false);
        assertThat(mAdapterService.mLeGattClientsControllingAutoActiveMode.size()).isEqualTo(1);

        // Connecting device two
        mAdapterService.notifyDirectLeGattClientConnect(clientIfTwo, mDevice2);

        order.verify(mMockLeAudioService, never()).setAutoActiveModeState(groupId, false);
        assertThat(mAdapterService.mLeGattClientsControllingAutoActiveMode.size()).isEqualTo(2);

        // Disconnect first device
        mAdapterService.notifyGattClientDisconnect(clientIf, mDevice1);
        order.verify(mMockLeAudioService, never()).setAutoActiveModeState(groupId, true);
        orderNative.verify(mNativeInterface, never()).disconnectAcl(any(), anyInt());
        assertThat(mAdapterService.mLeGattClientsControllingAutoActiveMode.size()).isEqualTo(1);

        // Disconnect second device
        mAdapterService.notifyGattClientDisconnect(clientIfTwo, mDevice2);
        order.verify(mMockLeAudioService).setAutoActiveModeState(groupId, true);
        orderNative.verify(mNativeInterface, times(2)).disconnectAcl(any(), eq(TRANSPORT_LE));
        assertThat(mAdapterService.mLeGattClientsControllingAutoActiveMode).isEmpty();
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
        when(mMockLeAudioService.setAutoActiveModeState(groupId, false)).thenReturn(true);
        mAdapterService.notifyDirectLeGattClientConnect(clientIf, mDevice1);

        order.verify(mMockLeAudioService).setAutoActiveModeState(groupId, false);
        assertThat(mAdapterService.mLeGattClientsControllingAutoActiveMode.size()).isEqualTo(1);

        // Connecting device two
        mAdapterService.notifyDirectLeGattClientConnect(clientIfTwo, mDevice2);

        order.verify(mMockLeAudioService, never()).setAutoActiveModeState(groupId, false);
        assertThat(mAdapterService.mLeGattClientsControllingAutoActiveMode.size()).isEqualTo(2);

        // Remote switches to Active
        when(mMockLeAudioService.isAutoActiveModeEnabled(groupId)).thenReturn(true);

        // Disconnect first device
        mAdapterService.notifyGattClientDisconnect(clientIf, mDevice1);
        order.verify(mMockLeAudioService, never()).setAutoActiveModeState(groupId, true);
        orderNative.verify(mNativeInterface, never()).disconnectAcl(any(), anyInt());
        assertThat(mAdapterService.mLeGattClientsControllingAutoActiveMode.size()).isEqualTo(1);

        // Disconnect second device
        mAdapterService.notifyGattClientDisconnect(clientIfTwo, mDevice2);

        // Verify devices will not be disconnected
        order.verify(mMockLeAudioService).setAutoActiveModeState(groupId, true);
        orderNative.verify(mNativeInterface, never()).disconnectAcl(any(), anyInt());
        assertThat(mAdapterService.mLeGattClientsControllingAutoActiveMode).isEmpty();
    }

    @Test
    public void testRemovePermissionBondedToBonding() {
        initTest();
        SharedPreferences mockPreferences = mock(SharedPreferences.class);
        SharedPreferences.Editor mockEditor = mock(SharedPreferences.Editor.class);

        when(mMockContext.getSharedPreferences(anyString(), anyInt())).thenReturn(mockPreferences);
        when(mockPreferences.edit()).thenReturn(mockEditor);

        mAdapterService.handleBondStateChanged(
                mDevice1, BluetoothDevice.BOND_BONDED, BluetoothDevice.BOND_BONDING);

        verify(mockEditor, times(3)).remove(anyString());
    }

    @Test
    @DisableFlags(Flags.FLAG_ON_TO_BLE_ON_VIA_OFF)
    public void onToBleOn_afterUpdatingSnoopLogValue_forceTurnOffBluetooth() {
        initTest();
        Optional<BluetoothProperties.snoop_log_mode_values> snoopSettingEmpty =
                Optional.of(BluetoothProperties.snoop_log_mode_values.EMPTY);
        ExtendedMockito.doReturn(snoopSettingEmpty)
                .when(() -> BluetoothProperties.snoop_log_mode());

        doEnable(false);

        Optional<BluetoothProperties.snoop_log_mode_values> snoopSettingFull =
                Optional.of(BluetoothProperties.snoop_log_mode_values.FULL);
        ExtendedMockito.doReturn(snoopSettingFull).when(() -> BluetoothProperties.snoop_log_mode());

        onToBleOn(
                mLooper,
                mAdapterService,
                mMockContext,
                mIBluetoothCallback,
                false,
                listOfMockServices());

        // Do not call bleOnToOff().  The Adapter should turn itself off.
        syncHandler(AdapterState.BLE_TURN_OFF);
        verifyStateChange(STATE_BLE_ON, STATE_BLE_TURNING_OFF, CONTEXT_SWITCH_MS);

        if (!Flags.onlyStartScanDuringBleOn()) {
            syncHandler(MESSAGE_PROFILE_SERVICE_STATE_CHANGED); // stop GATT
            syncHandler(MESSAGE_PROFILE_SERVICE_UNREGISTERED);
        }

        verify(mNativeInterface).disable();

        mAdapterService.stateChangeCallback(AbstractionLayer.BT_STATE_OFF);
        syncHandler(AdapterState.BLE_STOPPED);
        // When reaching the OFF state, the cleanup is called that will destroy the state machine of
        // the adapterService. Destroying state machine send a -1 event on the handler
        syncHandler(-1);

        verifyStateChange(STATE_BLE_TURNING_OFF, STATE_OFF);
        assertThat(mAdapterService.getState()).isEqualTo(STATE_OFF);
        assertThat(mLooper.nextMessage()).isNull();
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
        mAdapterService.setScanMode(BluetoothAdapter.SCAN_MODE_CONNECTABLE, "test");
        order.verify(mNativeInterface).setScanMode(eq(scanModeConnectable));
        mAdapterService.setSuspendState(true);
        order.verify(mNativeInterface).setScanMode(eq(scanModeNone));

        // Extraneous call to suspend won't trigger another setScanMode.
        mAdapterService.setSuspendState(true);
        order.verify(mNativeInterface, never()).setScanMode(anyInt());

        // When resuming, we should restore the scan state.
        mAdapterService.setSuspendState(false);
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
        mAdapterService.setScanMode(BluetoothAdapter.SCAN_MODE_CONNECTABLE, "test");
        order.verify(mNativeInterface).setScanMode(eq(scanModeConnectable));
        mAdapterService.setSuspendState(true);
        order.verify(mNativeInterface).setScanMode(eq(scanModeNone));

        // If during suspending process we receive a set scan request, we should not carry it out.
        mAdapterService.setScanMode(BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE, "test");
        order.verify(mNativeInterface, never()).setScanMode(eq(scanModeDiscoverable));

        // The pending request shall be carried out during resume.
        mAdapterService.setSuspendState(false);
        order.verify(mNativeInterface).setScanMode(eq(scanModeDiscoverable));
    }
}
