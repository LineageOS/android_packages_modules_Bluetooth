/*
 * Copyright 2017 The Android Open Source Project
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
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

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
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.res.Resources;
import android.hardware.display.DisplayManager;
import android.media.AudioManager;
import android.os.BatteryStatsManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.permission.PermissionManager;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.FlagsParameterization;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Settings;
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

import libcore.util.HexEncoding;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;

import platform.test.runner.parameterized.ParameterizedAndroidJunit4;
import platform.test.runner.parameterized.Parameters;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@MediumTest
@RunWith(ParameterizedAndroidJunit4.class)
public class AdapterServiceTest {
    private static final String TAG = AdapterServiceTest.class.getSimpleName();

    private static final String TEST_BT_ADDR_1 = "00:11:22:33:44:55";
    private static final String TEST_BT_ADDR_2 = "00:11:22:33:44:66";

    private static final int MESSAGE_PROFILE_SERVICE_STATE_CHANGED = 1;
    private static final int MESSAGE_PROFILE_SERVICE_REGISTERED = 2;
    private static final int MESSAGE_PROFILE_SERVICE_UNREGISTERED = 3;

    private MockAdapterService mAdapterService;

    static class MockAdapterService extends AdapterService {

        int mSetProfileServiceStateCounter = 0;

        MockAdapterService(Looper looper, Context ctx) {
            super(looper, ctx);
        }

        @Override
        void setProfileServiceState(int profileId, int state) {
            mSetProfileServiceStateCounter++;
        }
    }

    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    private @Mock Context mMockContext;
    private @Mock ApplicationInfo mMockApplicationInfo;
    private @Mock LeAudioService mMockLeAudioService;
    private @Mock Resources mMockResources;
    private @Mock ProfileService mMockGattService;
    private @Mock ProfileService mMockService;
    private @Mock ProfileService mMockService2;
    private @Mock IBluetoothCallback mIBluetoothCallback;
    private @Mock Binder mBinder;
    private @Mock MetricsLogger mMockMetricsLogger;
    private @Mock AdapterNativeInterface mNativeInterface;
    private @Mock BluetoothKeystoreNativeInterface mKeystoreNativeInterface;
    private @Mock BluetoothQualityReportNativeInterface mQualityNativeInterface;
    private @Mock BluetoothHciVendorSpecificNativeInterface mHciVendorSpecificNativeInterface;
    private @Mock SdpManagerNativeInterface mSdpNativeInterface;
    private @Mock AdvertiseManagerNativeInterface mAdvertiseNativeInterface;
    private @Mock DistanceMeasurementNativeInterface mDistanceNativeInterface;
    private @Mock GattNativeInterface mGattNativeInterface;
    private @Mock PeriodicScanNativeInterface mPeriodicNativeInterface;
    private @Mock ScanNativeInterface mScanNativeInterface;
    private @Mock JniCallbacks mJniCallbacks;

    @Rule public final SetFlagsRule mSetFlagsRule;

    // SystemService that are not mocked
    private BluetoothManager mBluetoothManager;
    private CompanionDeviceManager mCompanionDeviceManager;
    private DisplayManager mDisplayManager;
    private PowerManager mPowerManager;
    private PermissionManager mPermissionManager;

    private static final int CONTEXT_SWITCH_MS = 100;

    private PackageManager mMockPackageManager;
    private MockContentResolver mMockContentResolver;
    private int mForegroundUserId;
    private TestLooper mLooper;

    private final BluetoothDevice mDevice = getTestDevice(0);
    private final BluetoothDevice mDeviceTwo = getTestDevice(2);

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

    <T> void mockGetSystemService(String serviceName, Class<T> serviceClass, T mockService) {
        TestUtils.mockGetSystemService(mMockContext, serviceName, serviceClass, mockService);
    }

    <T> T mockGetSystemService(String serviceName, Class<T> serviceClass) {
        return TestUtils.mockGetSystemService(mMockContext, serviceName, serviceClass);
    }

    @Parameters(name = "{0}")
    public static List<FlagsParameterization> getParams() {
        return FlagsParameterization.allCombinationsOf();
    }

    public AdapterServiceTest(FlagsParameterization flags) {
        mSetFlagsRule = new SetFlagsRule(flags);
    }

    @Before
    public void setUp() throws PackageManager.NameNotFoundException {
        Log.e(TAG, "setUp()");

        mLooper = new TestLooper();
        Handler handler = new Handler(mLooper.getLooper());

        doReturn(mJniCallbacks).when(mNativeInterface).getCallbacks();

        doReturn(true).when(mMockLeAudioService).isAvailable();
        LeAudioService.setLeAudioService(mMockLeAudioService);
        doReturn(CONNECTION_POLICY_ALLOWED).when(mMockLeAudioService).getConnectionPolicy(any());

        AdapterNativeInterface.setInstance(mNativeInterface);
        BluetoothKeystoreNativeInterface.setInstance(mKeystoreNativeInterface);
        BluetoothQualityReportNativeInterface.setInstance(mQualityNativeInterface);
        BluetoothHciVendorSpecificNativeInterface.setInstance(mHciVendorSpecificNativeInterface);
        SdpManagerNativeInterface.setInstance(mSdpNativeInterface);
        AdvertiseManagerNativeInterface.setInstance(mAdvertiseNativeInterface);
        DistanceMeasurementNativeInterface.setInstance(mDistanceNativeInterface);
        GattNativeInterface.setInstance(mGattNativeInterface);
        PeriodicScanNativeInterface.setInstance(mPeriodicNativeInterface);
        ScanNativeInterface.setInstance(mScanNativeInterface);

        // Post the creation of AdapterService since it rely on Looper.myLooper()
        handler.post(
                () -> mAdapterService = new MockAdapterService(mLooper.getLooper(), mMockContext));
        assertThat(mLooper.dispatchAll()).isEqualTo(1);
        assertThat(mAdapterService).isNotNull();

        mMockPackageManager = mock(PackageManager.class);
        when(mMockPackageManager.getPermissionInfo(any(), anyInt()))
                .thenReturn(new PermissionInfo());

        Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

        mMockContentResolver = new MockContentResolver(targetContext);
        mMockContentResolver.addProvider(
                Settings.AUTHORITY,
                new MockContentProvider() {
                    @Override
                    public Bundle call(String method, String request, Bundle args) {
                        return Bundle.EMPTY;
                    }
                });

        mBluetoothManager = targetContext.getSystemService(BluetoothManager.class);
        mCompanionDeviceManager = targetContext.getSystemService(CompanionDeviceManager.class);
        mDisplayManager = targetContext.getSystemService(DisplayManager.class);
        mPermissionManager = targetContext.getSystemService(PermissionManager.class);
        mPowerManager = targetContext.getSystemService(PowerManager.class);

        when(mMockContext.getCacheDir()).thenReturn(targetContext.getCacheDir());
        when(mMockContext.getUser()).thenReturn(targetContext.getUser());
        when(mMockContext.getPackageName()).thenReturn(targetContext.getPackageName());
        when(mMockContext.getApplicationInfo()).thenReturn(mMockApplicationInfo);
        when(mMockContext.getContentResolver()).thenReturn(mMockContentResolver);
        when(mMockContext.getApplicationContext()).thenReturn(mMockContext);
        when(mMockContext.createContextAsUser(UserHandle.SYSTEM, /* flags= */ 0))
                .thenReturn(mMockContext);
        when(mMockContext.getResources()).thenReturn(mMockResources);
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);

        mockGetSystemService(Context.ALARM_SERVICE, AlarmManager.class);
        mockGetSystemService(Context.APP_OPS_SERVICE, AppOpsManager.class);
        mockGetSystemService(Context.AUDIO_SERVICE, AudioManager.class);
        mockGetSystemService(Context.ACTIVITY_SERVICE, ActivityManager.class);

        DevicePolicyManager dpm =
                mockGetSystemService(Context.DEVICE_POLICY_SERVICE, DevicePolicyManager.class);
        doReturn(false).when(dpm).isCommonCriteriaModeEnabled(any());
        mockGetSystemService(Context.USER_SERVICE, UserManager.class);

        mockGetSystemService(Context.BATTERY_STATS_SERVICE, BatteryStatsManager.class);
        mockGetSystemService(Context.BLUETOOTH_SERVICE, BluetoothManager.class, mBluetoothManager);
        mockGetSystemService(
                Context.COMPANION_DEVICE_SERVICE,
                CompanionDeviceManager.class,
                mCompanionDeviceManager);
        mockGetSystemService(Context.DISPLAY_SERVICE, DisplayManager.class, mDisplayManager);
        mockGetSystemService(
                Context.PERMISSION_SERVICE, PermissionManager.class, mPermissionManager);
        mockGetSystemService(Context.POWER_SERVICE, PowerManager.class, mPowerManager);

        when(mMockContext.getSharedPreferences(anyString(), anyInt()))
                .thenReturn(
                        targetContext.getSharedPreferences(
                                "AdapterServiceTestPrefs", Context.MODE_PRIVATE));

        doAnswer(
                        invocation -> {
                            Object[] args = invocation.getArguments();
                            return targetContext.getDatabasePath((String) args[0]);
                        })
                .when(mMockContext)
                .getDatabasePath(anyString());

        // Sets the foreground user id to match that of the tests (restored in tearDown)
        mForegroundUserId = Utils.getForegroundUserId();
        int callingUid = Binder.getCallingUid();
        UserHandle callingUser = UserHandle.getUserHandleForUid(callingUid);
        Utils.setForegroundUserId(callingUser.getIdentifier());

        when(mIBluetoothCallback.asBinder()).thenReturn(mBinder);

        when(mMockGattService.getName()).thenReturn("GattService");
        when(mMockService.getName()).thenReturn("Service1");
        when(mMockService2.getName()).thenReturn("Service2");

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

        // Restores the foregroundUserId to the ID prior to the test setup
        Utils.setForegroundUserId(mForegroundUserId);

        LeAudioService.setLeAudioService(null);
        mAdapterService.cleanup();
        mAdapterService.unregisterRemoteCallback(mIBluetoothCallback);
        AdapterNativeInterface.setInstance(null);
        BluetoothKeystoreNativeInterface.setInstance(null);
        BluetoothQualityReportNativeInterface.setInstance(null);
        BluetoothHciVendorSpecificNativeInterface.setInstance(null);
        SdpManagerNativeInterface.setInstance(null);
        AdvertiseManagerNativeInterface.setInstance(null);
        DistanceMeasurementNativeInterface.setInstance(null);
        GattNativeInterface.setInstance(null);
        PeriodicScanNativeInterface.setInstance(null);
        ScanNativeInterface.setInstance(null);
        MetricsLogger.setInstanceForTesting(null);
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
                ? List.of(mMockGattService, mMockService, mMockService2)
                : List.of(mMockService, mMockService2);
    }

    static void offToBleOn(
            TestLooper looper,
            ProfileService gattService,
            AdapterService adapter,
            Context ctx,
            IBluetoothCallback callback,
            AdapterNativeInterface nativeInterface) {
        adapter.offToBleOn(false);
        TestUtils.syncHandler(looper, 0); // `init` need to be run first
        TestUtils.syncHandler(looper, AdapterState.BLE_TURN_ON);
        verifyStateChange(callback, STATE_OFF, STATE_BLE_TURNING_ON);

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
        doEnable(false);
        assertThat(mLooper.nextMessage()).isNull();
    }

    @Test
    public void enable_isCorrectScanMode() {
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
        assertThat(mAdapterService.getState()).isEqualTo(STATE_OFF);

        mAdapterService.offToBleOn(false);
        syncHandler(0); // `init` need to be run first
        syncHandler(AdapterState.BLE_TURN_ON);
        verifyStateChange(STATE_OFF, STATE_BLE_TURNING_ON);
        assertThat(mAdapterService.getBluetoothGatt()).isNotNull();
        syncHandler(MESSAGE_PROFILE_SERVICE_REGISTERED);

        // Fetch next message and never process it to simulate a timeout.
        dropNextMessage(MESSAGE_PROFILE_SERVICE_STATE_CHANGED);

        mLooper.moveTimeForward(120_000); // Skip time so the timeout fires
        syncHandler(AdapterState.BLE_START_TIMEOUT);
        assertThat(mAdapterService.getBluetoothGatt()).isNull();

        syncHandler(AdapterState.BLE_STOPPED);
        // When reaching the OFF state, the cleanup is called that will destroy the state machine of
        // the adapterService. Destroying state machine send a -1 event on the handler
        syncHandler(-1);
        syncHandler(MESSAGE_PROFILE_SERVICE_STATE_CHANGED);
        syncHandler(MESSAGE_PROFILE_SERVICE_UNREGISTERED);

        verifyStateChange(STATE_BLE_TURNING_OFF, STATE_OFF);
        assertThat(mAdapterService.getState()).isEqualTo(STATE_OFF);
        assertThat(mLooper.nextMessage()).isNull();
    }

    /** Test: Don't stop GATT Check whether the AdapterService quits gracefully */
    @Test
    @DisableFlags(Flags.FLAG_ONLY_START_SCAN_DURING_BLE_ON)
    public void testGattStopTimeout() {
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
        mAdapterService.bringUpBle();

        assertThat(mAdapterService.getBluetoothGatt()).isNotNull();

        dropNextMessage(MESSAGE_PROFILE_SERVICE_REGISTERED);
        dropNextMessage(MESSAGE_PROFILE_SERVICE_STATE_CHANGED);
        assertThat(mLooper.nextMessage()).isNull();
    }

    @Test
    @EnableFlags(Flags.FLAG_ONLY_START_SCAN_DURING_BLE_ON)
    public void startBleOnly_whenOnlyStartScanDuringBleOnFlagIsOn_onlyStartScanController() {
        mAdapterService.bringUpBle();

        assertThat(mAdapterService.getBluetoothGatt()).isNull();
        assertThat(mAdapterService.getBluetoothScan()).isNotNull();
        assertThat(mLooper.nextMessage()).isNull();
    }

    @Test
    @EnableFlags(Flags.FLAG_ONLY_START_SCAN_DURING_BLE_ON)
    public void startBleOnly_whenOnlyStartScanDuringBleOnFlagIsOn_startAndStopScanController() {
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
        List<ProfileService> services = List.of(mMockService, mMockService2, mMockGattService);

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

        mAdapterService.addProfile(mMockService);
        syncHandler(MESSAGE_PROFILE_SERVICE_REGISTERED);
        mAdapterService.addProfile(mMockService2);
        syncHandler(MESSAGE_PROFILE_SERVICE_REGISTERED);
        mAdapterService.onProfileServiceStateChanged(mMockService, STATE_ON);
        syncHandler(MESSAGE_PROFILE_SERVICE_STATE_CHANGED);

        // Skip onProfileServiceStateChanged for mMockService2 to be in the test situation

        mLooper.moveTimeForward(120_000); // Skip time so the timeout fires
        syncHandler(AdapterState.BREDR_START_TIMEOUT);

        verifyStateChange(STATE_TURNING_ON, STATE_TURNING_OFF);
        assertThat(mAdapterService.mSetProfileServiceStateCounter).isEqualTo(4);

        mAdapterService.onProfileServiceStateChanged(mMockService, STATE_OFF);
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
        doEnable(false);

        mAdapterService.onToBleOn();
        syncHandler(AdapterState.USER_TURN_OFF);
        verifyStateChange(STATE_ON, STATE_TURNING_OFF);
        assertThat(mAdapterService.mSetProfileServiceStateCounter).isEqualTo(4);

        mAdapterService.onProfileServiceStateChanged(mMockService, STATE_OFF);
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
        assertThat(mAdapterService.obfuscateAddress(null)).isEmpty();
        assertThat(mLooper.nextMessage()).isNull();
    }

    @Test
    public void testAddressConsolidation() {
        // Create device properties
        RemoteDevices remoteDevices = mAdapterService.getRemoteDevices();
        remoteDevices.addDeviceProperties(Utils.getBytesFromAddress((TEST_BT_ADDR_1)));
        String identityAddress = mAdapterService.getIdentityAddress(TEST_BT_ADDR_1);
        if (!Flags.identityAddressNullIfNotKnown()) {
            assertThat(identityAddress).isEqualTo(TEST_BT_ADDR_1);
        }

        // Trigger address consolidate callback
        remoteDevices.addressConsolidateCallback(
                Utils.getBytesFromAddress(TEST_BT_ADDR_1),
                Utils.getBytesFromAddress(TEST_BT_ADDR_2));

        // Verify we can get correct identity address
        identityAddress = mAdapterService.getIdentityAddress(TEST_BT_ADDR_1);
        assertThat(identityAddress).isEqualTo(TEST_BT_ADDR_2);
        assertThat(mLooper.nextMessage()).isNull();
    }

    @Test
    @EnableFlags(Flags.FLAG_IDENTITY_ADDRESS_TYPE_API)
    public void testIdentityAddressType() {
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
    @EnableFlags(Flags.FLAG_IDENTITY_ADDRESS_NULL_IF_NOT_KNOWN)
    public void testIdentityAddressNullIfUnknown() {
        BluetoothDevice device = getTestDevice(0);

        assertThat(mAdapterService.getByteIdentityAddress(device)).isNull();
        assertThat(mAdapterService.getIdentityAddress(device.getAddress())).isNull();
        assertThat(mLooper.nextMessage()).isNull();
    }

    public static byte[] getMetricsSalt(Map<String, Map<String, String>> adapterConfig) {
        Map<String, String> metricsSection = adapterConfig.get("Metrics");
        if (metricsSection == null) {
            Log.e(TAG, "Metrics section is null: " + adapterConfig.toString());
            return null;
        }
        String saltString = metricsSection.get("Salt256Bit");
        if (saltString == null) {
            Log.e(TAG, "Salt256Bit is null: " + metricsSection.toString());
            return null;
        }
        byte[] metricsSalt = HexEncoding.decode(saltString, false /* allowSingleChar */);
        if (metricsSalt.length != 32) {
            Log.e(TAG, "Salt length is not 32 bit, but is " + metricsSalt.length);
            return null;
        }
        return metricsSalt;
    }

    public static byte[] obfuscateInJava(byte[] key, BluetoothDevice device) {
        String algorithm = "HmacSHA256";
        try {
            Mac hmac256 = Mac.getInstance(algorithm);
            hmac256.init(new SecretKeySpec(key, algorithm));
            return hmac256.doFinal(Utils.getByteAddress(device));
        } catch (NoSuchAlgorithmException | IllegalStateException | InvalidKeyException exp) {
            exp.printStackTrace();
            return null;
        }
    }

    public static boolean isByteArrayAllZero(byte[] byteArray) {
        for (byte i : byteArray) {
            if (i != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Test: Get id for null address Check if returned value from {@link
     * AdapterService#getMetricId(BluetoothDevice)} is 0 when device address is null
     */
    @Test
    public void testGetMetricId_NullAddress() {
        assertThat(mAdapterService.getMetricId(null)).isEqualTo(0);
        assertThat(mLooper.nextMessage()).isNull();
    }

    @Test
    public void testDump_doesNotCrash() {
        FileDescriptor fd = new FileDescriptor();
        PrintWriter writer = mock(PrintWriter.class);

        mAdapterService.dump(fd, writer, new String[] {});
        mAdapterService.dump(fd, writer, new String[] {"set-test-mode", "enabled"});
        doReturn(new byte[0]).when(mNativeInterface).dumpMetrics();
        mAdapterService.dump(fd, writer, new String[] {"--proto-bin"});
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
    @EnableFlags(Flags.FLAG_ALLOW_GATT_CONNECT_FROM_THE_APPS_WITHOUT_MAKING_LEAUDIO_DEVICE_ACTIVE)
    public void testGattConnectionToLeAudioDevice_whenDeviceIsNotConnected_success() {
        int groupId = 1;
        int getConnectionState_LeAudioService = STATE_CONNECTED;
        int getConnectionState_AdapterService =
                BluetoothDevice.CONNECTION_STATE_ENCRYPTED_LE
                        | BluetoothDevice.CONNECTION_STATE_CONNECTED;
        InOrder order =
                prepareLeAudioWithConnectedDevices(
                        List.of(mDevice),
                        groupId,
                        true,
                        getConnectionState_LeAudioService,
                        getConnectionState_AdapterService);

        mAdapterService.notifyDirectLeGattClientConnect(1, mDevice);

        order.verify(mMockLeAudioService).setAutoActiveModeState(groupId, false);
        assertThat(mAdapterService.mLeGattClientsControllingAutoActiveMode.size()).isEqualTo(1);
    }

    @Test
    @EnableFlags(Flags.FLAG_ALLOW_GATT_CONNECT_FROM_THE_APPS_WITHOUT_MAKING_LEAUDIO_DEVICE_ACTIVE)
    public void testGattConnectionToLeAudioDevice_whenDeviceIsConnected_ignore() {
        int groupId = 1;
        int getConnectionState_LeAudioService = STATE_CONNECTED;
        int getConnectionState_AdapterService =
                BluetoothDevice.CONNECTION_STATE_ENCRYPTED_LE
                        | BluetoothDevice.CONNECTION_STATE_CONNECTED;
        InOrder order =
                prepareLeAudioWithConnectedDevices(
                        List.of(mDevice),
                        groupId,
                        false,
                        getConnectionState_LeAudioService,
                        getConnectionState_AdapterService);

        mAdapterService.notifyDirectLeGattClientConnect(1, mDevice);

        order.verify(mMockLeAudioService).setAutoActiveModeState(groupId, false);
        assertThat(mAdapterService.mLeGattClientsControllingAutoActiveMode).isEmpty();
    }

    @Test
    @EnableFlags(Flags.FLAG_ALLOW_GATT_CONNECT_FROM_THE_APPS_WITHOUT_MAKING_LEAUDIO_DEVICE_ACTIVE)
    public void testGattConnectionToLeAudioDevice_whenLeAudioIsNotAllowed_ignore() {
        int groupId = 1;
        int getConnectionState_LeAudioService = STATE_DISCONNECTED;
        int getConnectionState_AdapterService =
                BluetoothDevice.CONNECTION_STATE_ENCRYPTED_LE
                        | BluetoothDevice.CONNECTION_STATE_CONNECTED;
        InOrder order =
                prepareLeAudioWithConnectedDevices(
                        List.of(mDevice),
                        groupId,
                        false,
                        getConnectionState_LeAudioService,
                        getConnectionState_AdapterService);

        doReturn(CONNECTION_POLICY_FORBIDDEN).when(mMockLeAudioService).getConnectionPolicy(any());
        mAdapterService.notifyDirectLeGattClientConnect(1, mDevice);

        order.verify(mMockLeAudioService, never()).setAutoActiveModeState(groupId, false);
        assertThat(mAdapterService.mLeGattClientsControllingAutoActiveMode).isEmpty();
    }

    @Test
    @EnableFlags(Flags.FLAG_ALLOW_GATT_CONNECT_FROM_THE_APPS_WITHOUT_MAKING_LEAUDIO_DEVICE_ACTIVE)
    public void testGattConnectionToLeAudioDevice_failedToConnect() {
        int groupId = 1;
        int clientIf = 1;

        int getConnectionState_LeAudioService = STATE_CONNECTED;
        int getConnectionState_AdapterService =
                BluetoothDevice.CONNECTION_STATE_ENCRYPTED_LE
                        | BluetoothDevice.CONNECTION_STATE_CONNECTED;
        InOrder order =
                prepareLeAudioWithConnectedDevices(
                        List.of(mDevice),
                        groupId,
                        true,
                        getConnectionState_LeAudioService,
                        getConnectionState_AdapterService);

        mAdapterService.notifyDirectLeGattClientConnect(clientIf, mDevice);

        order.verify(mMockLeAudioService).setAutoActiveModeState(groupId, false);
        assertThat(mAdapterService.mLeGattClientsControllingAutoActiveMode.size()).isEqualTo(1);

        mAdapterService.notifyGattClientConnectFailed(clientIf, mDevice);
        order.verify(mMockLeAudioService).setAutoActiveModeState(groupId, true);
        assertThat(mAdapterService.mLeGattClientsControllingAutoActiveMode).isEmpty();
    }

    @Test
    @EnableFlags(Flags.FLAG_ALLOW_GATT_CONNECT_FROM_THE_APPS_WITHOUT_MAKING_LEAUDIO_DEVICE_ACTIVE)
    public void testGattConnectionToLeAudioDevice_triggerDisconnected() {
        int groupId = 1;
        int clientIf = 1;

        int getConnectionState_LeAudioService = STATE_DISCONNECTED;
        int getConnectionState_AdapterService = BluetoothDevice.CONNECTION_STATE_DISCONNECTED;
        InOrder order =
                prepareLeAudioWithConnectedDevices(
                        List.of(mDevice),
                        groupId,
                        true,
                        getConnectionState_LeAudioService,
                        getConnectionState_AdapterService);
        InOrder orderNative = inOrder(mNativeInterface);

        mAdapterService.notifyDirectLeGattClientConnect(clientIf, mDevice);

        order.verify(mMockLeAudioService).setAutoActiveModeState(groupId, false);
        assertThat(mAdapterService.mLeGattClientsControllingAutoActiveMode.size()).isEqualTo(1);

        mAdapterService.notifyGattClientDisconnect(clientIf, mDevice);
        orderNative.verify(mNativeInterface, never()).disconnectAcl(any(), anyInt());
        order.verify(mMockLeAudioService).setAutoActiveModeState(groupId, true);
        assertThat(mAdapterService.mLeGattClientsControllingAutoActiveMode).isEmpty();
    }

    @Test
    @EnableFlags(Flags.FLAG_ALLOW_GATT_CONNECT_FROM_THE_APPS_WITHOUT_MAKING_LEAUDIO_DEVICE_ACTIVE)
    public void testGattConnectionToLeAudioDevice_triggerDisconnecting() {
        int groupId = 1;
        int clientIf = 1;
        int getConnectionState_LeAudioService = STATE_CONNECTED;
        int getConnectionState_AdapterService =
                BluetoothDevice.CONNECTION_STATE_ENCRYPTED_LE
                        | BluetoothDevice.CONNECTION_STATE_CONNECTED;
        InOrder order =
                prepareLeAudioWithConnectedDevices(
                        List.of(mDevice),
                        groupId,
                        true,
                        getConnectionState_LeAudioService,
                        getConnectionState_AdapterService);

        InOrder orderNative = inOrder(mNativeInterface);

        mAdapterService.notifyDirectLeGattClientConnect(clientIf, mDevice);

        order.verify(mMockLeAudioService).setAutoActiveModeState(groupId, false);
        assertThat(mAdapterService.mLeGattClientsControllingAutoActiveMode.size()).isEqualTo(1);

        mAdapterService.notifyGattClientDisconnect(clientIf, mDevice);
        order.verify(mMockLeAudioService).setAutoActiveModeState(groupId, true);
        orderNative.verify(mNativeInterface).disconnectAcl(any(), eq(BluetoothDevice.TRANSPORT_LE));
        assertThat(mAdapterService.mLeGattClientsControllingAutoActiveMode).isEmpty();
    }

    @Test
    @EnableFlags(Flags.FLAG_ALLOW_GATT_CONNECT_FROM_THE_APPS_WITHOUT_MAKING_LEAUDIO_DEVICE_ACTIVE)
    public void testGattConnectionToLeAudioDevice_connectingMultipleClients() {
        int groupId = 1;
        int clientIf = 1;
        int clientIfTwo = 2;

        int getConnectionState_LeAudioService = STATE_CONNECTED;
        int getConnectionState_AdapterService =
                BluetoothDevice.CONNECTION_STATE_ENCRYPTED_LE
                        | BluetoothDevice.CONNECTION_STATE_CONNECTED;
        InOrder order =
                prepareLeAudioWithConnectedDevices(
                        List.of(mDevice),
                        groupId,
                        true,
                        getConnectionState_LeAudioService,
                        getConnectionState_AdapterService);

        InOrder orderNative = inOrder(mNativeInterface);

        // Connect first client to device
        mAdapterService.notifyDirectLeGattClientConnect(clientIf, mDevice);

        order.verify(mMockLeAudioService).setAutoActiveModeState(groupId, false);
        assertThat(mAdapterService.mLeGattClientsControllingAutoActiveMode.size()).isEqualTo(1);

        // Connect second client to device
        mAdapterService.notifyDirectLeGattClientConnect(clientIfTwo, mDevice);

        order.verify(mMockLeAudioService, never()).setAutoActiveModeState(groupId, false);
        assertThat(mAdapterService.mLeGattClientsControllingAutoActiveMode.size()).isEqualTo(2);

        // Disconnect first client to device
        mAdapterService.notifyGattClientDisconnect(clientIf, mDevice);
        order.verify(mMockLeAudioService, never()).setAutoActiveModeState(groupId, true);
        orderNative.verify(mNativeInterface, never()).disconnectAcl(any(), anyInt());
        assertThat(mAdapterService.mLeGattClientsControllingAutoActiveMode.size()).isEqualTo(1);

        // Disconnect second client to device
        mAdapterService.notifyGattClientDisconnect(clientIfTwo, mDevice);
        order.verify(mMockLeAudioService).setAutoActiveModeState(groupId, true);
        orderNative
                .verify(mNativeInterface, times(1))
                .disconnectAcl(any(), eq(BluetoothDevice.TRANSPORT_LE));
        assertThat(mAdapterService.mLeGattClientsControllingAutoActiveMode).isEmpty();
    }

    @Test
    @EnableFlags(Flags.FLAG_ALLOW_GATT_CONNECT_FROM_THE_APPS_WITHOUT_MAKING_LEAUDIO_DEVICE_ACTIVE)
    public void testGattConnectionToLeAudioDevice_connectingMultipleDevicesInSameGroup() {
        int groupId = 1;
        int clientIf = 1;
        int clientIfTwo = 2;

        int getConnectionState_LeAudioService = STATE_CONNECTED;
        int getConnectionState_AdapterService =
                BluetoothDevice.CONNECTION_STATE_ENCRYPTED_LE
                        | BluetoothDevice.CONNECTION_STATE_CONNECTED;
        InOrder order =
                prepareLeAudioWithConnectedDevices(
                        List.of(mDevice, mDeviceTwo),
                        groupId,
                        true,
                        getConnectionState_LeAudioService,
                        getConnectionState_AdapterService);

        InOrder orderNative = inOrder(mNativeInterface);

        // Connecting device one
        when(mMockLeAudioService.setAutoActiveModeState(groupId, false)).thenReturn(true);
        mAdapterService.notifyDirectLeGattClientConnect(clientIf, mDevice);

        order.verify(mMockLeAudioService).setAutoActiveModeState(groupId, false);
        assertThat(mAdapterService.mLeGattClientsControllingAutoActiveMode.size()).isEqualTo(1);

        // Connecting device two
        mAdapterService.notifyDirectLeGattClientConnect(clientIfTwo, mDeviceTwo);

        order.verify(mMockLeAudioService, never()).setAutoActiveModeState(groupId, false);
        assertThat(mAdapterService.mLeGattClientsControllingAutoActiveMode.size()).isEqualTo(2);

        // Disconnect first device
        mAdapterService.notifyGattClientDisconnect(clientIf, mDevice);
        order.verify(mMockLeAudioService, never()).setAutoActiveModeState(groupId, true);
        orderNative.verify(mNativeInterface, never()).disconnectAcl(any(), anyInt());
        assertThat(mAdapterService.mLeGattClientsControllingAutoActiveMode.size()).isEqualTo(1);

        // Disconnect second device
        mAdapterService.notifyGattClientDisconnect(clientIfTwo, mDeviceTwo);
        order.verify(mMockLeAudioService).setAutoActiveModeState(groupId, true);
        orderNative
                .verify(mNativeInterface, times(2))
                .disconnectAcl(any(), eq(BluetoothDevice.TRANSPORT_LE));
        assertThat(mAdapterService.mLeGattClientsControllingAutoActiveMode).isEmpty();
    }

    @Test
    @EnableFlags(Flags.FLAG_ALLOW_GATT_CONNECT_FROM_THE_APPS_WITHOUT_MAKING_LEAUDIO_DEVICE_ACTIVE)
    public void testGattConnectionToLeAudioDevice_remoteSwitchesToActiveBeforeDisconnect() {
        int groupId = 1;
        int clientIf = 1;
        int clientIfTwo = 2;

        int getConnectionState_LeAudioService = STATE_CONNECTED;
        int getConnectionState_AdapterService =
                BluetoothDevice.CONNECTION_STATE_ENCRYPTED_LE
                        | BluetoothDevice.CONNECTION_STATE_CONNECTED;
        InOrder order =
                prepareLeAudioWithConnectedDevices(
                        List.of(mDevice, mDeviceTwo),
                        groupId,
                        true,
                        getConnectionState_LeAudioService,
                        getConnectionState_AdapterService);

        InOrder orderNative = inOrder(mNativeInterface);

        // Connecting device one
        when(mMockLeAudioService.setAutoActiveModeState(groupId, false)).thenReturn(true);
        mAdapterService.notifyDirectLeGattClientConnect(clientIf, mDevice);

        order.verify(mMockLeAudioService).setAutoActiveModeState(groupId, false);
        assertThat(mAdapterService.mLeGattClientsControllingAutoActiveMode.size()).isEqualTo(1);

        // Connecting device two
        mAdapterService.notifyDirectLeGattClientConnect(clientIfTwo, mDeviceTwo);

        order.verify(mMockLeAudioService, never()).setAutoActiveModeState(groupId, false);
        assertThat(mAdapterService.mLeGattClientsControllingAutoActiveMode.size()).isEqualTo(2);

        // Remote switches to Active
        when(mMockLeAudioService.isAutoActiveModeEnabled(groupId)).thenReturn(true);

        // Disconnect first device
        mAdapterService.notifyGattClientDisconnect(clientIf, mDevice);
        order.verify(mMockLeAudioService, never()).setAutoActiveModeState(groupId, true);
        orderNative.verify(mNativeInterface, never()).disconnectAcl(any(), anyInt());
        assertThat(mAdapterService.mLeGattClientsControllingAutoActiveMode.size()).isEqualTo(1);

        // Disconnect second device
        mAdapterService.notifyGattClientDisconnect(clientIfTwo, mDeviceTwo);

        // Verify devices will not be disconnected
        order.verify(mMockLeAudioService).setAutoActiveModeState(groupId, true);
        orderNative.verify(mNativeInterface, never()).disconnectAcl(any(), anyInt());
        assertThat(mAdapterService.mLeGattClientsControllingAutoActiveMode).isEmpty();
    }
}
