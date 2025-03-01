/*
 * Copyright 2025 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
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
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.permission.PermissionManager;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.FlagsParameterization;
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

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import platform.test.runner.parameterized.ParameterizedAndroidJunit4;
import platform.test.runner.parameterized.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@MediumTest
@RunWith(ParameterizedAndroidJunit4.class)
public class AdapterServiceTest {
    private static final String TAG = AdapterServiceTest.class.getSimpleName();

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

    @Rule public MockitoRule mockitoRule = MockitoJUnit.rule();

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

        // BatteryStatsManager is final and cannot be mocked with regular mockito, so just return
        // real implementation
        mockGetSystemService(
                Context.BATTERY_STATS_SERVICE,
                BatteryStatsManager.class,
                targetContext.getSystemService(BatteryStatsManager.class));
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

    private List<ProfileService> listOfMockServices() {
        return Flags.onlyStartScanDuringBleOn()
                ? List.of(mMockGattService, mMockService, mMockService2)
                : List.of(mMockService, mMockService2);
    }

    private void offToBleOn() {
        mAdapterService.offToBleOn(false);
        syncHandler(0); // `init` need to be run first
        syncHandler(AdapterState.BLE_TURN_ON);
        verifyStateChange(STATE_OFF, STATE_BLE_TURNING_ON);

        if (!Flags.onlyStartScanDuringBleOn()) {
            syncHandler(MESSAGE_PROFILE_SERVICE_REGISTERED);
            syncHandler(MESSAGE_PROFILE_SERVICE_STATE_CHANGED);
        }

        verify(mNativeInterface).enable();
        mAdapterService.stateChangeCallback(AbstractionLayer.BT_STATE_ON);
        syncHandler(AdapterState.BLE_STARTED);
        verifyStateChange(STATE_BLE_TURNING_ON, STATE_BLE_ON);
        assertThat(mAdapterService.getState()).isEqualTo(STATE_BLE_ON);
    }

    private void onToBleOn() {
        mAdapterService.onToBleOn();
        syncHandler(AdapterState.USER_TURN_OFF);
        verifyStateChange(STATE_ON, STATE_TURNING_OFF);

        // Stop (if Flags.onlyStartScanDuringBleOn GATT), PBAP, and PAN services
        assertThat(mAdapterService.mSetProfileServiceStateCounter)
                .isEqualTo(listOfMockServices().size() * 2);

        for (ProfileService service : listOfMockServices()) {
            mAdapterService.onProfileServiceStateChanged(service, STATE_OFF);
            syncHandler(MESSAGE_PROFILE_SERVICE_STATE_CHANGED);
        }

        syncHandler(AdapterState.BREDR_STOPPED);
        verifyStateChange(STATE_TURNING_OFF, STATE_BLE_ON);

        assertThat(mAdapterService.getState()).isEqualTo(STATE_BLE_ON);
    }

    private void doEnable() {
        Log.e(TAG, "doEnable() start");

        IBluetoothCallback callback = mock(IBluetoothCallback.class);
        Binder binder = mock(Binder.class);
        doReturn(binder).when(callback).asBinder();
        mAdapterService.registerRemoteCallback(callback);

        assertThat(mAdapterService.getState()).isEqualTo(STATE_OFF);

        offToBleOn();

        mAdapterService.bleOnToOn();
        syncHandler(AdapterState.USER_TURN_ON);
        verifyStateChange(STATE_BLE_ON, STATE_TURNING_ON);

        // Start Mock (if Flags.onlyStartScanDuringBleOn GATT), PBAP, and PAN services
        assertThat(mAdapterService.mSetProfileServiceStateCounter)
                .isEqualTo(listOfMockServices().size());

        for (ProfileService service : listOfMockServices()) {
            mAdapterService.addProfile(service);
            syncHandler(MESSAGE_PROFILE_SERVICE_REGISTERED);
        }
        // Keep in 2 separate loop to first add the services and then eventually trigger the
        // ON transition during the callback
        for (ProfileService service : listOfMockServices()) {
            mAdapterService.onProfileServiceStateChanged(service, STATE_ON);
            syncHandler(MESSAGE_PROFILE_SERVICE_STATE_CHANGED);
        }
        syncHandler(AdapterState.BREDR_STARTED);
        verifyStateChange(STATE_TURNING_ON, STATE_ON);

        assertThat(mAdapterService.getState()).isEqualTo(STATE_ON);
        mAdapterService.unregisterRemoteCallback(callback);
        Log.e(TAG, "doEnable() complete success");
    }

    /** Test: Toggle snoop logging setting Check whether the AdapterService restarts fully */
    @Test
    public void testSnoopLoggingChange() {
        BluetoothProperties.snoop_log_mode_values snoopSetting =
                BluetoothProperties.snoop_log_mode()
                        .orElse(BluetoothProperties.snoop_log_mode_values.EMPTY);
        BluetoothProperties.snoop_log_mode(BluetoothProperties.snoop_log_mode_values.DISABLED);
        doEnable();

        assertThat(
                        BluetoothProperties.snoop_log_mode()
                                .orElse(BluetoothProperties.snoop_log_mode_values.EMPTY))
                .isNotEqualTo(BluetoothProperties.snoop_log_mode_values.FULL);

        BluetoothProperties.snoop_log_mode(BluetoothProperties.snoop_log_mode_values.FULL);

        onToBleOn();

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

        // Restore earlier setting
        BluetoothProperties.snoop_log_mode(snoopSetting);
        assertThat(mLooper.nextMessage()).isNull();
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_GATT_CLEAR_CACHE_ON_FACTORY_RESET)
    public void testClearStorage() throws Exception {
        // clearStorage should remove all files under /data/misc/bluetooth/ && /data/misc/bluedroid/
        final Path testCachePath = Paths.get("/data/misc/bluetooth/gatt_cache_a475b9a23d72");
        final Path testHashPath =
                Paths.get("/data/misc/bluetooth/gatt_hash_400D017CB2563A6FB62A2DC4C2AEFD6F");
        final Path randomFileUnderBluedroidPath =
                Paths.get("/data/misc/bluedroid/random_test_file.txt");
        final Path randomFileUnderBluetoothPath =
                Paths.get("/data/misc/bluetooth/random_test_file.txt");

        try {
            Files.createFile(testCachePath);
            Files.createFile(testHashPath);
            Files.createFile(randomFileUnderBluedroidPath);
            Files.createFile(randomFileUnderBluetoothPath);

            assertThat(Files.exists(testCachePath)).isTrue();
            assertThat(Files.exists(testHashPath)).isTrue();
            assertThat(Files.exists(randomFileUnderBluedroidPath)).isTrue();
            assertThat(Files.exists(randomFileUnderBluetoothPath)).isTrue();

            mAdapterService.clearStorage();

            assertThat(Files.exists(testCachePath)).isFalse();
            assertThat(Files.exists(testHashPath)).isFalse();
            assertThat(Files.exists(randomFileUnderBluedroidPath)).isFalse();
            assertThat(Files.exists(randomFileUnderBluetoothPath)).isFalse();
        } finally {
            Files.deleteIfExists(testCachePath);
            Files.deleteIfExists(testHashPath);
            Files.deleteIfExists(randomFileUnderBluedroidPath);
            Files.deleteIfExists(randomFileUnderBluetoothPath);
        }
        assertThat(mLooper.nextMessage()).isNull();
    }
}
