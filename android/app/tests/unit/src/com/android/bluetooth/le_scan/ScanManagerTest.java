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

package com.android.bluetooth.le_scan;

import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;
import static android.bluetooth.BluetoothDevice.PHY_LE_1M;
import static android.bluetooth.BluetoothDevice.PHY_LE_1M_MASK;
import static android.bluetooth.BluetoothDevice.PHY_LE_CODED;
import static android.bluetooth.BluetoothDevice.PHY_LE_CODED_MASK;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.le.ScanSettings.CALLBACK_TYPE_ALL_MATCHES_AUTO_BATCH;
import static android.bluetooth.le.ScanSettings.PHY_LE_ALL_SUPPORTED;
import static android.bluetooth.le.ScanSettings.SCAN_MODE_AMBIENT_DISCOVERY;
import static android.bluetooth.le.ScanSettings.SCAN_MODE_BALANCED;
import static android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY;
import static android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_POWER;
import static android.bluetooth.le.ScanSettings.SCAN_MODE_OPPORTUNISTIC;
import static android.bluetooth.le.ScanSettings.SCAN_MODE_SCREEN_OFF;
import static android.bluetooth.le.ScanSettings.SCAN_MODE_SCREEN_OFF_BALANCED;

import static com.android.bluetooth.TestUtils.mockGetSystemService;
import static com.android.bluetooth.TestUtils.mockSystemPropertyGet;
import static com.android.bluetooth.btservice.AdapterService.DeviceConfigListener.DEFAULT_SCAN_DOWNGRADE_DURATION_BT_CONNECTING;
import static com.android.bluetooth.btservice.AdapterService.DeviceConfigListener.DEFAULT_SCAN_TIMEOUT;
import static com.android.bluetooth.btservice.AdapterService.DeviceConfigListener.DEFAULT_SCAN_UPGRADE_DURATION;
import static com.android.bluetooth.le_scan.ScanManager.MSFT_HCI_EXT_ENABLED;
import static com.android.bluetooth.le_scan.ScanUtil.SCAN_MODE_BALANCED_INTERVAL_MS;
import static com.android.bluetooth.le_scan.ScanUtil.SCAN_MODE_BALANCED_WINDOW_MS;
import static com.android.bluetooth.le_scan.ScanUtil.SCAN_MODE_LOW_LATENCY_INTERVAL_MS;
import static com.android.bluetooth.le_scan.ScanUtil.SCAN_MODE_LOW_LATENCY_WINDOW_MS;
import static com.android.bluetooth.le_scan.ScanUtil.SCAN_MODE_LOW_POWER_INTERVAL_MS;
import static com.android.bluetooth.le_scan.ScanUtil.SCAN_MODE_LOW_POWER_WINDOW_MS;
import static com.android.bluetooth.le_scan.ScanUtil.SCAN_MODE_SCREEN_OFF_BALANCED_INTERVAL;
import static com.android.bluetooth.le_scan.ScanUtil.SCAN_MODE_SCREEN_OFF_BALANCED_WINDOW;
import static com.android.bluetooth.le_scan.ScanUtil.SCAN_MODE_SCREEN_OFF_LOW_POWER_INTERVAL;
import static com.android.bluetooth.le_scan.ScanUtil.SCAN_MODE_SCREEN_OFF_LOW_POWER_WINDOW;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothProtoEnums;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanSettings;
import android.hardware.display.DisplayManager;
import android.location.LocationManager;
import android.os.BatteryStatsManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Message;
import android.os.ParcelUuid;
import android.os.SystemProperties;
import android.os.WorkSource;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Settings;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContentResolver;
import android.util.Log;
import android.util.SparseIntArray;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.bluetooth.BluetoothStatsLog;
import com.android.bluetooth.TestLooper;
import com.android.bluetooth.TestUtils.FakeTimeProvider;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.MetricsLogger;
import com.android.bluetooth.flags.Flags;
import com.android.tests.bluetooth.FlagsWrapper;
import com.android.tests.bluetooth.StaticMockitoRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;

import platform.test.runner.parameterized.ParameterizedAndroidJunit4;
import platform.test.runner.parameterized.Parameters;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Test cases for {@link ScanManager}. */
@SmallTest
@RunWith(ParameterizedAndroidJunit4.class)
public class ScanManagerTest {
    private static final String TAG = ScanManagerTest.class.getSimpleName();

    @Rule
    public final StaticMockitoRule mMockitoRule = new StaticMockitoRule(SystemProperties.class);

    @Rule public final SetFlagsRule mSetFlagsRule;

    @Mock private AdapterService mAdapterService;
    @Mock private BluetoothManager mBluetoothManager;
    @Mock private BluetoothAdapter mAdapter;
    @Mock private LocationManager mLocationManager;
    @Mock private MetricsLogger mMetricsLogger;
    @Mock private ScanNativeInterface mScanNativeInterface;
    @Mock private ScanController mScanController;

    private static final int DEFAULT_REGULAR_SCAN_REPORT_DELAY_MS = 0;
    private static final int DEFAULT_BATCH_SCAN_REPORT_DELAY_MS = 100;
    private static final int DEFAULT_NUM_OFFLOAD_SCAN_FILTER = 16;
    private static final int DEFAULT_BYTES_OFFLOAD_SCAN_RESULT_STORAGE = 4096;
    private static final int DEFAULT_TOTAL_NUM_OF_TRACKABLE_ADVERTISEMENTS = 32;
    private static final int TEST_SCAN_QUOTA_COUNT = 5;
    private static final String TEST_APP_NAME = "Test";
    private static final String TEST_PACKAGE_NAME = "com.test.package";

    private static final Map<Integer, Integer> defaultScanMode =
            Map.of(
                    SCAN_MODE_LOW_POWER, SCAN_MODE_LOW_POWER,
                    SCAN_MODE_BALANCED, SCAN_MODE_BALANCED,
                    SCAN_MODE_LOW_LATENCY, SCAN_MODE_LOW_LATENCY,
                    SCAN_MODE_AMBIENT_DISCOVERY, SCAN_MODE_AMBIENT_DISCOVERY);

    private final FakeTimeProvider mTimeProvider = new FakeTimeProvider();

    private ScanRadioStats mScanRadioStats;
    private AppScanStats mMockAppScanStats;
    private MockContentResolver mMockContentResolver;

    private ScanManager mScanManager;
    private TestLooper mLooper;
    private long mScanReportDelay;
    private InOrder mInOrder;
    private int mClientId;

    @Parameters(name = "{0}")
    public static List<FlagsWrapper> getParams() {
        return FlagsWrapper.progressionOf(Flags.FLAG_SCAN_CONTROLLER_THREAD);
    }

    public ScanManagerTest(FlagsWrapper flags) {
        mSetFlagsRule = new SetFlagsRule(flags.getFlags());
    }

    @Before
    public void setUp() throws Exception {
        doReturn(DEFAULT_SCAN_TIMEOUT).when(mAdapterService).getScanTimeout();
        doReturn(DEFAULT_NUM_OFFLOAD_SCAN_FILTER)
                .when(mAdapterService)
                .getNumOfOffloadedScanFilterSupported();
        doReturn(DEFAULT_BYTES_OFFLOAD_SCAN_RESULT_STORAGE)
                .when(mAdapterService)
                .getOffloadedScanResultStorage();
        doReturn(TEST_SCAN_QUOTA_COUNT).when(mAdapterService).getScanQuotaCount();
        doReturn(SCAN_MODE_SCREEN_OFF_LOW_POWER_WINDOW)
                .when(mAdapterService)
                .getScreenOffLowPowerWindow();
        doReturn(SCAN_MODE_SCREEN_OFF_BALANCED_WINDOW)
                .when(mAdapterService)
                .getScreenOffBalancedWindow();
        doReturn(SCAN_MODE_SCREEN_OFF_LOW_POWER_INTERVAL)
                .when(mAdapterService)
                .getScreenOffLowPowerInterval();
        doReturn(SCAN_MODE_SCREEN_OFF_BALANCED_INTERVAL)
                .when(mAdapterService)
                .getScreenOffBalancedInterval();
        doReturn(DEFAULT_TOTAL_NUM_OF_TRACKABLE_ADVERTISEMENTS)
                .when(mAdapterService)
                .getTotalNumOfTrackableAdvertisements();

        mockGetSystemService(mAdapterService, LocationManager.class, mLocationManager);
        doReturn(true).when(mLocationManager).isLocationEnabled();
        mockGetSystemService(mAdapterService, DisplayManager.class);
        mockGetSystemService(mAdapterService, BatteryStatsManager.class);
        mockGetSystemService(mAdapterService, AlarmManager.class);
        mockGetSystemService(mAdapterService, BluetoothManager.class, mBluetoothManager);
        doReturn(mAdapter).when(mBluetoothManager).getAdapter();

        final var context = InstrumentationRegistry.getInstrumentation().getContext();
        doReturn(context.getResources()).when(mAdapterService).getResources();
        mMockContentResolver = new MockContentResolver(context);
        mMockContentResolver.addProvider(
                Settings.AUTHORITY,
                new MockContentProvider() {
                    @Override
                    public Bundle call(String method, String request, Bundle args) {
                        return Bundle.EMPTY;
                    }
                });
        doReturn(mMockContentResolver).when(mAdapterService).getContentResolver();
        // Needed to mock Native call/callback when hw offload scan filter is enabled
        doReturn(true).when(mAdapter).isOffloadedFilteringSupported();

        // Mock JNI callback in ScanNativeInterface
        doReturn(true).when(mScanNativeInterface).waitForCallback(anyInt());

        mScanRadioStats = new ScanRadioStats(mTimeProvider);
        doReturn(mScanRadioStats).when(mScanController).getScanRadioStats();
        MetricsLogger.setInstanceForTesting(mMetricsLogger);
        mInOrder = inOrder(mMetricsLogger);

        doReturn(context.getUser()).when(mAdapterService).getUser();
        doReturn(context.getPackageName()).when(mAdapterService).getPackageName();

        mClientId = 0;
        mLooper = new TestLooper();
        mScanManager =
                new ScanManager(
                        mAdapterService,
                        mScanController,
                        mScanNativeInterface,
                        mLooper.getLooper(),
                        mTimeProvider);

        mScanReportDelay = DEFAULT_BATCH_SCAN_REPORT_DELAY_MS;
        final int appUid = 1234;
        mMockAppScanStats =
                spy(
                        new AppScanStats(
                                TEST_APP_NAME,
                                null,
                                appUid,
                                mAdapterService,
                                mScanController,
                                mTimeProvider));
    }

    @After
    public void tearDown() throws Exception {
        MetricsLogger.setInstanceForTesting(null);
        MetricsLogger.getInstance();
    }

    private void advanceTime(Duration amountToAdvance) {
        mLooper.moveTimeForward(amountToAdvance.toMillis());
        mTimeProvider.advanceTime(amountToAdvance);
    }

    private void advanceTime(long amountToAdvanceMillis) {
        mLooper.moveTimeForward(amountToAdvanceMillis);
        mTimeProvider.advanceTime(Duration.ofMillis(amountToAdvanceMillis));
    }

    private void startScan(ScanClient client) {
        if (Flags.scanControllerThread()) {
            executeOnScanThread(() -> mScanManager.startScan(client));
        } else {
            sendMessageWaitForProcessed(createStartStopScanMessage(true, client));
        }
    }

    private void stopScan(ScanClient client) {
        if (Flags.scanControllerThread()) {
            executeOnScanThread(() -> mScanManager.stopScan(client.getScannerId()));
        } else {
            sendMessageWaitForProcessed(createStartStopScanMessage(false, client));
        }
    }

    private void setScreenOn(boolean isScreenOn) {
        if (Flags.scanControllerThread()) {
            executeOnScanThread(
                    isScreenOn ? mScanManager::handleScreenOn : mScanManager::handleScreenOff);
        } else {
            sendMessageWaitForProcessed(createScreenOnOffMessage(isScreenOn));
        }
    }

    private void setLocationOn(boolean isLocationOn) {
        if (Flags.scanControllerThread()) {
            executeOnScanThread(
                    isLocationOn
                            ? mScanManager::handleResumeScans
                            : mScanManager::handleSuspendScans);
        } else {
            sendMessageWaitForProcessed(createLocationOnOffMessage(isLocationOn));
        }
    }

    private void setAppImportance(boolean isForeground, int uid) {
        final int importance =
                isForeground
                        ? ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
                        : ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE + 1;
        final var uidImportance = new ScanManager.UidImportance(uid, importance);
        if (Flags.scanControllerThread()) {
            executeOnScanThread(() -> mScanManager.handleImportanceChange(uidImportance));
        } else {
            Message message = new Message();
            message.what = ScanManager.MSG_IMPORTANCE_CHANGE;
            message.obj = uidImportance;
            sendMessageWaitForProcessed(message);
        }
    }

    private void setConnectingState(boolean isConnecting) {
        if (Flags.scanControllerThread()) {
            executeOnScanThread(
                    isConnecting
                            ? mScanManager::handleConnectingState
                            : mScanManager::handleClearConnectingState);
        } else {
            sendMessageWaitForProcessed(createConnectingMessage(isConnecting));
        }
    }

    private void executeOnScanThread(Runnable r) {
        mScanManager.mHandler.post(r);
        assertThat(mLooper.dispatchAll()).isEqualTo(1);
    }

    private void sendMessageWaitForProcessed(Message msg) {
        mScanManager.mClientHandler.sendMessage(msg);
        mLooper.dispatchAll();
    }

    private ScanClient createScanClient(
            boolean isFiltered,
            int scanMode,
            boolean isBatch,
            boolean isAutoBatch,
            int appUid,
            AppScanStats appScanStats,
            List<ScanFilter> scanFilterList) {
        ScanSettings scanSettings = createScanSettings(scanMode, isBatch, isAutoBatch);

        mClientId = mClientId + 1;
        ScanClient client = new ScanClient(mClientId, scanSettings, scanFilterList, appUid);
        client.setAppScanStats(Optional.of(appScanStats));
        client.getAppScanStats()
                .get()
                .recordScanStart(scanSettings, scanFilterList, isFiltered, false, mClientId, null);
        return client;
    }

    private ScanClient createScanClient(
            boolean isFiltered,
            boolean isEmptyFilter,
            int scanMode,
            boolean isBatch,
            boolean isAutoBatch,
            int appUid,
            AppScanStats appScanStats) {
        List<ScanFilter> scanFilterList = createScanFilterList(isFiltered, isEmptyFilter);
        return createScanClient(
                isFiltered, scanMode, isBatch, isAutoBatch, appUid, appScanStats, scanFilterList);
    }

    private ScanClient createScanClient(boolean isFiltered, int scanMode) {
        return createScanClient(
                isFiltered,
                false,
                scanMode,
                false,
                false,
                Binder.getCallingUid(),
                mMockAppScanStats);
    }

    private ScanClient createScanClient(
            boolean isFiltered, int scanMode, int appUid, AppScanStats appScanStats) {
        return createScanClient(isFiltered, false, scanMode, false, false, appUid, appScanStats);
    }

    private ScanClient createScanClient(
            boolean isFiltered, int scanMode, boolean isBatch, boolean isAutoBatch) {
        return createScanClient(
                isFiltered,
                false,
                scanMode,
                isBatch,
                isAutoBatch,
                Binder.getCallingUid(),
                mMockAppScanStats);
    }

    private ScanClient createScanClient(boolean isFiltered, boolean isEmptyFilter, int scanMode) {
        return createScanClient(
                isFiltered,
                isEmptyFilter,
                scanMode,
                false,
                false,
                Binder.getCallingUid(),
                mMockAppScanStats);
    }

    private static List<ScanFilter> createScanFilterList(
            boolean isFiltered, boolean isEmptyFilter) {
        List<ScanFilter> scanFilterList = null;
        if (isFiltered) {
            scanFilterList = new ArrayList<>();
            if (isEmptyFilter) {
                scanFilterList.add(new ScanFilter.Builder().build());
            } else {
                scanFilterList.add(new ScanFilter.Builder().setDeviceName("TestName").build());
            }
        }
        return scanFilterList;
    }

    private ScanSettings createScanSettings(int scanMode, boolean isBatch, boolean isAutoBatch) {

        ScanSettings scanSettings = null;
        if (isBatch && isAutoBatch) {
            int autoCallbackType = CALLBACK_TYPE_ALL_MATCHES_AUTO_BATCH;
            scanSettings =
                    new ScanSettings.Builder()
                            .setScanMode(scanMode)
                            .setReportDelay(mScanReportDelay)
                            .setCallbackType(autoCallbackType)
                            .build();
        } else if (isBatch) {
            scanSettings =
                    new ScanSettings.Builder()
                            .setScanMode(scanMode)
                            .setReportDelay(mScanReportDelay)
                            .build();
        } else {
            scanSettings = new ScanSettings.Builder().setScanMode(scanMode).build();
        }
        return scanSettings;
    }

    private static ScanSettings createScanSettingsWithPhy(int scanMode, int phy) {
        ScanSettings scanSettings;
        scanSettings = new ScanSettings.Builder().setScanMode(scanMode).setPhy(phy).build();

        return scanSettings;
    }

    private ScanClient createScanClientWithPhy(
            int id, boolean isFiltered, boolean isEmptyFilter, int scanMode, int phy) {
        List<ScanFilter> scanFilterList = createScanFilterList(isFiltered, isEmptyFilter);
        ScanSettings scanSettings = createScanSettingsWithPhy(scanMode, phy);

        final int appUid = 1234;
        ScanClient client = new ScanClient(id, scanSettings, scanFilterList, appUid);
        client.setAppScanStats(Optional.of(mMockAppScanStats));
        client.getAppScanStats()
                .get()
                .recordScanStart(scanSettings, scanFilterList, isFiltered, false, id, null);
        return client;
    }

    private static Message createStartStopScanMessage(boolean isStartScan, Object obj) {
        Message message = new Message();
        message.what = isStartScan ? ScanManager.MSG_START_BLE_SCAN : ScanManager.MSG_STOP_BLE_SCAN;
        message.obj = obj;
        return message;
    }

    private static Message createScreenOnOffMessage(boolean isScreenOn) {
        Message message = new Message();
        message.what = isScreenOn ? ScanManager.MSG_SCREEN_ON : ScanManager.MSG_SCREEN_OFF;
        message.obj = null;
        return message;
    }

    private static Message createLocationOnOffMessage(boolean isLocationOn) {
        Message message = new Message();
        message.what = isLocationOn ? ScanManager.MSG_RESUME_SCANS : ScanManager.MSG_SUSPEND_SCANS;
        message.obj = null;
        return message;
    }

    private static Message createConnectingMessage(boolean isConnectingOn) {
        Message message = new Message();
        message.what =
                isConnectingOn ? ScanManager.MSG_START_CONNECTING : ScanManager.MSG_STOP_CONNECTING;
        message.obj = null;
        return message;
    }

    @Test
    public void testScreenOffStartUnfilteredScan() {
        // Set filtered scan flag
        final boolean isFiltered = false;

        defaultScanMode.forEach(
                (scanMode, expectedScanMode) -> {
                    mClientId = mClientId + 1;
                    Log.d(TAG, "ScanMode: " + scanMode + " expectedScanMode: " + expectedScanMode);
                    // Turn off screen
                    setScreenOn(false);
                    // Create scan client
                    ScanClient client = createScanClient(isFiltered, scanMode);
                    // Start scan
                    startScan(client);
                    assertThat(mScanManager.getRegularScanQueue()).doesNotContain(client);
                    assertThat(mScanManager.getSuspendedScanQueue()).contains(client);
                    assertThat(client.getSettings().getScanMode()).isEqualTo(expectedScanMode);
                });
    }

    @Test
    public void testScreenOffStartFilteredScan() {
        // Set filtered scan flag
        final boolean isFiltered = true;
        // Set scan mode map {original scan mode (ScanMode) : expected scan mode (expectedScanMode)}
        SparseIntArray scanModeMap = new SparseIntArray();
        scanModeMap.put(SCAN_MODE_LOW_POWER, SCAN_MODE_SCREEN_OFF);
        scanModeMap.put(SCAN_MODE_BALANCED, SCAN_MODE_SCREEN_OFF_BALANCED);
        scanModeMap.put(SCAN_MODE_LOW_LATENCY, SCAN_MODE_LOW_LATENCY);
        scanModeMap.put(SCAN_MODE_AMBIENT_DISCOVERY, SCAN_MODE_SCREEN_OFF_BALANCED);

        for (int i = 0; i < scanModeMap.size(); i++) {
            int scanMode = scanModeMap.keyAt(i);
            int expectedScanMode = scanModeMap.get(scanMode);
            Log.d(TAG, "ScanMode: " + scanMode + " expectedScanMode: " + expectedScanMode);

            // Turn off screen
            setScreenOn(false);
            // Create scan client
            ScanClient client = createScanClient(isFiltered, scanMode);
            // Start scan
            startScan(client);
            assertThat(mScanManager.getRegularScanQueue()).contains(client);
            assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client);
            assertThat(client.getSettings().getScanMode()).isEqualTo(expectedScanMode);
        }
    }

    @Test
    public void testScreenOffStartEmptyFilterScan() {
        // Set filtered scan flag
        final boolean isFiltered = true;
        final boolean isEmptyFilter = true;

        defaultScanMode.forEach(
                (scanMode, expectedScanMode) -> {
                    mClientId = mClientId + 1;
                    Log.d(TAG, "ScanMode: " + scanMode + " expectedScanMode: " + expectedScanMode);

                    // Turn off screen
                    setScreenOn(false);
                    // Create scan client
                    ScanClient client = createScanClient(isFiltered, isEmptyFilter, scanMode);
                    // Start scan
                    startScan(client);
                    assertThat(mScanManager.getRegularScanQueue()).doesNotContain(client);
                    assertThat(mScanManager.getSuspendedScanQueue()).contains(client);
                    assertThat(client.getSettings().getScanMode()).isEqualTo(expectedScanMode);
                });
    }

    @Test
    public void testScreenOnStartUnfilteredScan() {
        // Set filtered scan flag
        final boolean isFiltered = false;

        defaultScanMode.forEach(
                (scanMode, expectedScanMode) -> {
                    mClientId = mClientId + 1;
                    Log.d(TAG, "ScanMode: " + scanMode + " expectedScanMode: " + expectedScanMode);

                    // Turn on screen
                    setScreenOn(true);
                    // Create scan client
                    ScanClient client = createScanClient(isFiltered, scanMode);
                    // Start scan
                    startScan(client);
                    assertThat(mScanManager.getRegularScanQueue()).contains(client);
                    assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client);
                    assertThat(client.getSettings().getScanMode()).isEqualTo(expectedScanMode);
                });
    }

    @Test
    public void testScreenOnStartFilteredScan() {
        // Set filtered scan flag
        final boolean isFiltered = true;

        defaultScanMode.forEach(
                (scanMode, expectedScanMode) -> {
                    mClientId = mClientId + 1;
                    Log.d(TAG, "ScanMode: " + scanMode + " expectedScanMode: " + expectedScanMode);

                    // Turn on screen
                    setScreenOn(true);
                    // Create scan client
                    ScanClient client = createScanClient(isFiltered, scanMode);
                    // Start scan
                    startScan(client);
                    assertThat(mScanManager.getRegularScanQueue()).contains(client);
                    assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client);
                    assertThat(client.getSettings().getScanMode()).isEqualTo(expectedScanMode);
                });
    }

    @Test
    public void testResumeUnfilteredScanAfterScreenOn() {
        // Set filtered scan flag
        final boolean isFiltered = false;
        // Set scan mode map {original scan mode (ScanMode) : expected scan mode (expectedScanMode)}
        SparseIntArray scanModeMap = new SparseIntArray();
        scanModeMap.put(SCAN_MODE_LOW_POWER, SCAN_MODE_SCREEN_OFF);
        scanModeMap.put(SCAN_MODE_BALANCED, SCAN_MODE_SCREEN_OFF_BALANCED);
        scanModeMap.put(SCAN_MODE_LOW_LATENCY, SCAN_MODE_LOW_LATENCY);
        scanModeMap.put(SCAN_MODE_AMBIENT_DISCOVERY, SCAN_MODE_SCREEN_OFF_BALANCED);

        for (int i = 0; i < scanModeMap.size(); i++) {
            int scanMode = scanModeMap.keyAt(i);
            int expectedScanMode = scanModeMap.get(scanMode);
            Log.d(TAG, "ScanMode: " + scanMode + " expectedScanMode: " + expectedScanMode);
            // Turn off screen
            setScreenOn(false);
            // Create scan client
            ScanClient client = createScanClient(isFiltered, scanMode);
            // Start scan
            startScan(client);
            assertThat(mScanManager.getRegularScanQueue()).doesNotContain(client);
            assertThat(mScanManager.getSuspendedScanQueue()).contains(client);
            assertThat(client.getSettings().getScanMode()).isEqualTo(scanMode);
            // Turn on screen
            setScreenOn(true);
            assertThat(mScanManager.getRegularScanQueue()).contains(client);
            assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client);
            assertThat(client.getSettings().getScanMode()).isEqualTo(scanMode);
        }
    }

    @Test
    public void testResumeFilteredScanAfterScreenOn() {
        // Set filtered scan flag
        final boolean isFiltered = true;
        // Set scan mode map {original scan mode (ScanMode) : expected scan mode (expectedScanMode)}
        SparseIntArray scanModeMap = new SparseIntArray();
        scanModeMap.put(SCAN_MODE_LOW_POWER, SCAN_MODE_SCREEN_OFF);
        scanModeMap.put(SCAN_MODE_BALANCED, SCAN_MODE_SCREEN_OFF_BALANCED);
        scanModeMap.put(SCAN_MODE_LOW_LATENCY, SCAN_MODE_LOW_LATENCY);
        scanModeMap.put(SCAN_MODE_AMBIENT_DISCOVERY, SCAN_MODE_SCREEN_OFF_BALANCED);

        for (int i = 0; i < scanModeMap.size(); i++) {
            int scanMode = scanModeMap.keyAt(i);
            int expectedScanMode = scanModeMap.get(scanMode);
            Log.d(TAG, "ScanMode: " + scanMode + " expectedScanMode: " + expectedScanMode);
            // Turn off screen
            setScreenOn(false);
            // Create scan client
            ScanClient client = createScanClient(isFiltered, scanMode);
            // Start scan
            startScan(client);
            assertThat(mScanManager.getRegularScanQueue()).contains(client);
            assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client);
            assertThat(client.getSettings().getScanMode()).isEqualTo(expectedScanMode);
            // Turn on screen
            setScreenOn(true);
            assertThat(mScanManager.getRegularScanQueue()).contains(client);
            assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client);
            assertThat(client.getSettings().getScanMode()).isEqualTo(scanMode);
        }
    }

    @Test
    public void testUnfilteredScanTimeout() {
        // Set filtered scan flag
        final boolean isFiltered = false;

        defaultScanMode.forEach(
                (scanMode, expectedScanMode) -> {
                    mClientId = mClientId + 1;
                    expectedScanMode = SCAN_MODE_OPPORTUNISTIC;
                    Log.d(TAG, "ScanMode: " + scanMode + " expectedScanMode: " + expectedScanMode);
                    // Turn on screen
                    setScreenOn(true);
                    // Create scan client
                    ScanClient client = createScanClient(isFiltered, scanMode);
                    // Start scan
                    startScan(client);
                    assertThat(client.getSettings().getScanMode()).isEqualTo(scanMode);
                    // Wait for scan timeout
                    advanceTime(DEFAULT_SCAN_TIMEOUT);
                    mLooper.dispatchAll();
                    assertThat(client.getSettings().getScanMode()).isEqualTo(expectedScanMode);
                    assertThat(client.getAppScanStats().get().isScanTimeout(client.getScannerId()))
                            .isTrue();
                    // Turn off screen
                    setScreenOn(false);
                    assertThat(client.getSettings().getScanMode()).isEqualTo(expectedScanMode);
                    // Turn on screen
                    setScreenOn(true);
                    assertThat(client.getSettings().getScanMode()).isEqualTo(expectedScanMode);
                    // Set as background app
                    setAppImportance(false, Binder.getCallingUid());
                    assertThat(client.getSettings().getScanMode()).isEqualTo(expectedScanMode);
                    // Set as foreground app
                    setAppImportance(true, Binder.getCallingUid());
                    assertThat(client.getSettings().getScanMode()).isEqualTo(expectedScanMode);
                });
    }

    @Test
    public void testFilteredScanTimeout() {
        // Set filtered scan flag
        final boolean isFiltered = true;

        defaultScanMode.forEach(
                (scanMode, expectedScanMode) -> {
                    mClientId = mClientId + 1;
                    expectedScanMode = SCAN_MODE_LOW_POWER;
                    Log.d(TAG, "ScanMode: " + scanMode + " expectedScanMode: " + expectedScanMode);
                    // Turn on screen
                    setScreenOn(true);
                    // Create scan client
                    ScanClient client = createScanClient(isFiltered, scanMode);
                    // Start scan, this sends scan timeout message with delay
                    startScan(client);
                    assertThat(client.getSettings().getScanMode()).isEqualTo(scanMode);
                    // Move time forward so scan timeout message can be dispatched
                    advanceTime(DEFAULT_SCAN_TIMEOUT);
                    // Since we are using a TestLooper, need to mock AppScanStats.isScanningTooLong
                    // to return true because no real time is elapsed
                    doReturn(true).when(mMockAppScanStats).isScanningTooLong();
                    mLooper.dispatchAll();
                    assertThat(client.getSettings().getScanMode()).isEqualTo(expectedScanMode);
                    assertThat(client.getAppScanStats().get().isScanTimeout(client.getScannerId()))
                            .isTrue();
                    // Turn off screen
                    setScreenOn(false);
                    assertThat(client.getSettings().getScanMode()).isEqualTo(SCAN_MODE_SCREEN_OFF);
                    // Set as background app
                    setAppImportance(false, Binder.getCallingUid());
                    assertThat(client.getSettings().getScanMode()).isEqualTo(SCAN_MODE_SCREEN_OFF);
                    // Turn on screen
                    setScreenOn(true);
                    assertThat(client.getSettings().getScanMode()).isEqualTo(expectedScanMode);
                    // Set as foreground app
                    setAppImportance(true, Binder.getCallingUid());
                    assertThat(client.getSettings().getScanMode()).isEqualTo(expectedScanMode);
                });
    }

    @Test
    public void testScanTimeoutResetForNewScan() {
        // Set filtered scan flag
        final boolean isFiltered = false;
        // Turn on screen
        setScreenOn(true);
        // Create scan client
        ScanClient client = createScanClient(isFiltered, SCAN_MODE_LOW_POWER);

        if (Flags.scanControllerThread()) {
            // Put a timeout runnable in the map to emulate the scan being started already
            Runnable fakeTimeoutRunnable = () -> {};
            mScanManager.mScanTimeoutRunnables.put(client, fakeTimeoutRunnable);
            mScanManager.mHandler.postDelayed(
                    fakeTimeoutRunnable, DEFAULT_SCAN_TIMEOUT.dividedBy(2).toMillis());
            // Start the scan. This should remove the fake runnable and post a new one.
            startScan(client);
        } else {
            // Put a timeout message in the queue to emulate the scan being started already
            Message timeoutMessage =
                    mScanManager.mClientHandler.obtainMessage(ScanManager.MSG_SCAN_TIMEOUT, client);
            mScanManager.mClientHandler.sendMessageDelayed(
                    timeoutMessage, DEFAULT_SCAN_TIMEOUT.dividedBy(2).toMillis());
            mScanManager.mClientHandler.sendMessage(createStartStopScanMessage(true, client));
        }

        if (Flags.scanControllerThread()) {
            // Verify that only the new, real runnable is in the map.
            assertThat(mScanManager.mScanTimeoutRunnables).hasSize(1);
        } else {
            // Dispatching all messages only runs start scan
            assertThat(mLooper.dispatchAll()).isEqualTo(1);
        }

        advanceTime(DEFAULT_SCAN_TIMEOUT.dividedBy(2));
        // After restarting the scan, we can check that the initial timeout message is not triggered
        assertThat(mLooper.dispatchAll()).isEqualTo(0);

        // After timeout, the next message that is run should be a timeout message
        advanceTime(DEFAULT_SCAN_TIMEOUT.dividedBy(2));

        if (Flags.scanControllerThread()) {
            // Dispatching should now execute the real timeout.
            mLooper.dispatchAll();
            // Verify the client was moved to opportunistic mode, proving the timeout logic ran.
            assertThat(client.getSettings().getScanMode()).isEqualTo(SCAN_MODE_OPPORTUNISTIC);
            assertThat(client.getAppScanStats().get().isScanTimeout(client.getScannerId()))
                    .isTrue();
        } else {
            Message nextMessage = mLooper.nextMessage();
            assertThat(nextMessage.what).isEqualTo(ScanManager.MSG_SCAN_TIMEOUT);
            assertThat(nextMessage.obj).isEqualTo(client);
        }
    }

    @Test
    public void testSwitchForeBackgroundUnfilteredScan() {
        // Set filtered scan flag
        final boolean isFiltered = false;

        defaultScanMode.forEach(
                (scanMode, expectedScanMode) -> {
                    mClientId = mClientId + 1;
                    expectedScanMode = SCAN_MODE_LOW_POWER;
                    Log.d(TAG, "ScanMode: " + scanMode + " expectedScanMode: " + expectedScanMode);
                    // Turn on screen
                    setScreenOn(true);
                    // Create scan client
                    ScanClient client = createScanClient(isFiltered, scanMode);
                    // Start scan
                    startScan(client);
                    assertThat(mScanManager.getRegularScanQueue()).contains(client);
                    assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client);
                    assertThat(client.getSettings().getScanMode()).isEqualTo(scanMode);
                    // Set as background app
                    setAppImportance(false, Binder.getCallingUid());
                    assertThat(mScanManager.getRegularScanQueue()).contains(client);
                    assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client);
                    assertThat(client.getSettings().getScanMode()).isEqualTo(expectedScanMode);
                    // Set as foreground app
                    setAppImportance(true, Binder.getCallingUid());
                    assertThat(mScanManager.getRegularScanQueue()).contains(client);
                    assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client);
                    assertThat(client.getSettings().getScanMode()).isEqualTo(scanMode);
                });
    }

    @Test
    public void testSwitchForeBackgroundFilteredScan() {
        // Set filtered scan flag
        final boolean isFiltered = true;

        defaultScanMode.forEach(
                (scanMode, expectedScanMode) -> {
                    mClientId = mClientId + 1;
                    expectedScanMode = SCAN_MODE_LOW_POWER;
                    Log.d(TAG, "ScanMode: " + scanMode + " expectedScanMode: " + expectedScanMode);
                    // Turn on screen
                    setScreenOn(true);
                    // Create scan client
                    ScanClient client = createScanClient(isFiltered, scanMode);
                    // Start scan
                    startScan(client);
                    assertThat(mScanManager.getRegularScanQueue()).contains(client);
                    assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client);
                    assertThat(client.getSettings().getScanMode()).isEqualTo(scanMode);
                    // Set as background app
                    setAppImportance(false, Binder.getCallingUid());
                    assertThat(mScanManager.getRegularScanQueue()).contains(client);
                    assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client);
                    assertThat(client.getSettings().getScanMode()).isEqualTo(expectedScanMode);
                    // Set as foreground app
                    setAppImportance(true, Binder.getCallingUid());
                    assertThat(mScanManager.getRegularScanQueue()).contains(client);
                    assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client);
                    assertThat(client.getSettings().getScanMode()).isEqualTo(scanMode);
                });
    }

    @Test
    public void testUpgradeStartScan() {
        // Set filtered scan flag
        final boolean isFiltered = true;
        // Set scan mode map {original scan mode (ScanMode) : expected scan mode (expectedScanMode)}
        SparseIntArray scanModeMap = new SparseIntArray();
        scanModeMap.put(SCAN_MODE_LOW_POWER, SCAN_MODE_BALANCED);
        scanModeMap.put(SCAN_MODE_BALANCED, SCAN_MODE_LOW_LATENCY);
        scanModeMap.put(SCAN_MODE_LOW_LATENCY, SCAN_MODE_LOW_LATENCY);
        scanModeMap.put(SCAN_MODE_AMBIENT_DISCOVERY, SCAN_MODE_LOW_LATENCY);
        doReturn(DEFAULT_SCAN_UPGRADE_DURATION).when(mAdapterService).getScanUpgradeDuration();

        for (int i = 0; i < scanModeMap.size(); i++) {
            int scanMode = scanModeMap.keyAt(i);
            int expectedScanMode = scanModeMap.get(scanMode);
            Log.d(TAG, "ScanMode: " + scanMode + " expectedScanMode: " + expectedScanMode);
            // Turn on screen
            setScreenOn(true);
            // Set as foreground app
            setAppImportance(true, Binder.getCallingUid());
            // Create scan client
            ScanClient client = createScanClient(isFiltered, scanMode);
            // Start scan
            startScan(client);
            assertThat(mScanManager.getRegularScanQueue()).contains(client);
            assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client);
            assertThat(client.getSettings().getScanMode()).isEqualTo(expectedScanMode);
            // Wait for upgrade duration
            advanceTime(DEFAULT_SCAN_UPGRADE_DURATION);
            mLooper.dispatchAll();
            assertThat(client.getSettings().getScanMode()).isEqualTo(scanMode);
        }
    }

    @Test
    public void testUpDowngradeStartScanForConcurrency() {
        doReturn(DEFAULT_SCAN_UPGRADE_DURATION).when(mAdapterService).getScanUpgradeDuration();
        doReturn(DEFAULT_SCAN_DOWNGRADE_DURATION_BT_CONNECTING)
                .when(mAdapterService)
                .getScanDowngradeDuration();

        // Set filtered scan flag
        final boolean isFiltered = true;

        defaultScanMode.forEach(
                (scanMode, expectedScanMode) -> {
                    mClientId = mClientId + 1;
                    expectedScanMode = SCAN_MODE_BALANCED;
                    Log.d(TAG, "ScanMode: " + scanMode + " expectedScanMode: " + expectedScanMode);
                    // Turn on screen
                    setScreenOn(true);
                    // Set as foreground app
                    setAppImportance(true, Binder.getCallingUid());
                    // Set connecting state
                    setConnectingState(true);
                    // Create scan client
                    ScanClient client = createScanClient(isFiltered, scanMode);
                    // Start scan
                    startScan(client);
                    assertThat(mScanManager.getRegularScanQueue()).contains(client);
                    assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client);
                    assertThat(client.getSettings().getScanMode()).isEqualTo(expectedScanMode);
                    // Wait for upgrade and downgrade duration
                    var maxDuration =
                            DEFAULT_SCAN_UPGRADE_DURATION.compareTo(
                                                    DEFAULT_SCAN_DOWNGRADE_DURATION_BT_CONNECTING)
                                            > 0
                                    ? DEFAULT_SCAN_UPGRADE_DURATION
                                    : DEFAULT_SCAN_DOWNGRADE_DURATION_BT_CONNECTING;
                    advanceTime(maxDuration);
                    mLooper.dispatchAll();
                    assertThat(client.getSettings().getScanMode()).isEqualTo(scanMode);
                });
    }

    @Test
    public void testDowngradeDuringScanForConcurrency() {
        // Set filtered scan flag
        final boolean isFiltered = true;
        // Set scan mode map {original scan mode (ScanMode) : expected scan mode (expectedScanMode)}
        SparseIntArray scanModeMap = new SparseIntArray();
        scanModeMap.put(SCAN_MODE_LOW_POWER, SCAN_MODE_LOW_POWER);
        scanModeMap.put(SCAN_MODE_BALANCED, SCAN_MODE_BALANCED);
        scanModeMap.put(SCAN_MODE_LOW_LATENCY, SCAN_MODE_BALANCED);
        scanModeMap.put(SCAN_MODE_AMBIENT_DISCOVERY, SCAN_MODE_AMBIENT_DISCOVERY);

        doReturn(DEFAULT_SCAN_DOWNGRADE_DURATION_BT_CONNECTING)
                .when(mAdapterService)
                .getScanDowngradeDuration();

        for (int i = 0; i < scanModeMap.size(); i++) {
            int scanMode = scanModeMap.keyAt(i);
            int expectedScanMode = scanModeMap.get(scanMode);
            Log.d(TAG, "ScanMode: " + scanMode + " expectedScanMode: " + expectedScanMode);
            // Turn on screen
            setScreenOn(true);
            // Set as foreground app
            setAppImportance(true, Binder.getCallingUid());
            // Create scan client
            ScanClient client = createScanClient(isFiltered, scanMode);
            // Start scan
            startScan(client);
            assertThat(mScanManager.getRegularScanQueue()).contains(client);
            assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client);
            assertThat(client.getSettings().getScanMode()).isEqualTo(scanMode);
            // Set connecting state
            setConnectingState(true);
            assertThat(client.getSettings().getScanMode()).isEqualTo(expectedScanMode);
            // Wait for downgrade duration
            advanceTime(DEFAULT_SCAN_DOWNGRADE_DURATION_BT_CONNECTING);
            mLooper.dispatchAll();
            assertThat(client.getSettings().getScanMode()).isEqualTo(scanMode);
        }
    }

    @Test
    public void testDowngradeDuringScanForConcurrencyScreenOff() {
        // Set filtered scan flag
        final boolean isFiltered = true;
        // Set scan mode map {original scan mode (ScanMode) : expected scan mode (expectedScanMode)}
        SparseIntArray scanModeMap = new SparseIntArray();
        scanModeMap.put(SCAN_MODE_LOW_POWER, SCAN_MODE_SCREEN_OFF);
        scanModeMap.put(SCAN_MODE_BALANCED, SCAN_MODE_SCREEN_OFF_BALANCED);
        scanModeMap.put(SCAN_MODE_LOW_LATENCY, SCAN_MODE_LOW_LATENCY);
        scanModeMap.put(SCAN_MODE_AMBIENT_DISCOVERY, SCAN_MODE_SCREEN_OFF_BALANCED);

        doReturn(DEFAULT_SCAN_DOWNGRADE_DURATION_BT_CONNECTING)
                .when(mAdapterService)
                .getScanDowngradeDuration();

        for (int i = 0; i < scanModeMap.size(); i++) {
            int scanMode = scanModeMap.keyAt(i);
            int expectedScanMode = scanModeMap.get(scanMode);
            Log.d(TAG, "ScanMode: " + scanMode + " expectedScanMode: " + expectedScanMode);
            // Turn on screen
            setScreenOn(true);
            // Set as foreground app
            setAppImportance(true, Binder.getCallingUid());
            // Create scan client
            ScanClient client = createScanClient(isFiltered, scanMode);
            // Start scan
            startScan(client);
            assertThat(mScanManager.getRegularScanQueue()).contains(client);
            assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client);
            assertThat(client.getSettings().getScanMode()).isEqualTo(scanMode);
            // Set connecting state
            setConnectingState(true);
            // Turn off screen
            setScreenOn(false);
            // Move time forward so that stop connecting action can be dispatched
            advanceTime(DEFAULT_SCAN_DOWNGRADE_DURATION_BT_CONNECTING);
            mLooper.dispatchAll();
            assertThat(mScanManager.getRegularScanQueue()).contains(client);
            assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client);
            assertThat(client.getSettings().getScanMode()).isEqualTo(expectedScanMode);
        }
    }

    @Test
    public void testDowngradeDuringScanForConcurrencyBackground() {
        doReturn(DEFAULT_SCAN_DOWNGRADE_DURATION_BT_CONNECTING)
                .when(mAdapterService)
                .getScanDowngradeDuration();

        // Set filtered scan flag
        final boolean isFiltered = true;

        defaultScanMode.forEach(
                (scanMode, expectedScanMode) -> {
                    mClientId = mClientId + 1;
                    expectedScanMode = SCAN_MODE_LOW_POWER;
                    Log.d(TAG, "ScanMode: " + scanMode + " expectedScanMode: " + expectedScanMode);
                    // Turn on screen
                    setScreenOn(true);
                    // Set as foreground app
                    setAppImportance(true, Binder.getCallingUid());
                    // Create scan client
                    ScanClient client = createScanClient(isFiltered, scanMode);
                    // Start scan
                    startScan(client);
                    assertThat(mScanManager.getRegularScanQueue()).contains(client);
                    assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client);
                    assertThat(client.getSettings().getScanMode()).isEqualTo(scanMode);
                    // Set connecting state
                    setConnectingState(true);
                    // Set as background app
                    setAppImportance(false, Binder.getCallingUid());
                    // Wait for downgrade duration
                    advanceTime(DEFAULT_SCAN_DOWNGRADE_DURATION_BT_CONNECTING);
                    mLooper.dispatchAll();
                    assertThat(mScanManager.getRegularScanQueue()).contains(client);
                    assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client);
                    assertThat(client.getSettings().getScanMode()).isEqualTo(expectedScanMode);
                });
    }

    @Test
    public void testStartUnfilteredBatchScan() {
        // Set filtered and batch scan flag
        final boolean isFiltered = false;
        final boolean isBatch = true;
        final boolean isAutoBatch = false;
        // Set scan mode map {original scan mode (ScanMode) : expected scan mode (expectedScanMode)}
        SparseIntArray scanModeMap = new SparseIntArray();
        scanModeMap.put(SCAN_MODE_LOW_POWER, SCAN_MODE_LOW_POWER);
        scanModeMap.put(SCAN_MODE_BALANCED, SCAN_MODE_BALANCED);
        scanModeMap.put(SCAN_MODE_LOW_LATENCY, SCAN_MODE_LOW_LATENCY);
        scanModeMap.put(SCAN_MODE_AMBIENT_DISCOVERY, SCAN_MODE_LOW_LATENCY);

        for (int i = 0; i < scanModeMap.size(); i++) {
            int scanMode = scanModeMap.keyAt(i);
            int expectedScanMode = scanModeMap.get(scanMode);
            Log.d(TAG, "ScanMode: " + scanMode + " expectedScanMode: " + expectedScanMode);

            // Turn off screen
            setScreenOn(false);
            // Create scan client
            ScanClient client = createScanClient(isFiltered, scanMode, isBatch, isAutoBatch);
            // Start scan
            startScan(client);
            assertThat(mScanManager.getRegularScanQueue()).doesNotContain(client);
            assertThat(mScanManager.getSuspendedScanQueue()).contains(client);
            assertThat(mScanManager.getBatchScanQueue()).doesNotContain(client);
            // Turn on screen
            setScreenOn(true);
            assertThat(mScanManager.getRegularScanQueue()).doesNotContain(client);
            assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client);
            assertThat(mScanManager.getBatchScanQueue()).contains(client);
            assertThat(mScanManager.getBatchScanParams().scanMode()).isEqualTo(expectedScanMode);
        }
    }

    @Test
    public void testStartFilteredBatchScan() {
        // Set filtered and batch scan flag
        final boolean isFiltered = true;
        final boolean isBatch = true;
        final boolean isAutoBatch = false;
        // Set scan mode map {original scan mode (ScanMode) : expected scan mode (expectedScanMode)}
        SparseIntArray scanModeMap = new SparseIntArray();
        scanModeMap.put(SCAN_MODE_LOW_POWER, SCAN_MODE_LOW_POWER);
        scanModeMap.put(SCAN_MODE_BALANCED, SCAN_MODE_BALANCED);
        scanModeMap.put(SCAN_MODE_LOW_LATENCY, SCAN_MODE_LOW_LATENCY);
        scanModeMap.put(SCAN_MODE_AMBIENT_DISCOVERY, SCAN_MODE_LOW_LATENCY);

        for (int i = 0; i < scanModeMap.size(); i++) {
            int scanMode = scanModeMap.keyAt(i);
            int expectedScanMode = scanModeMap.get(scanMode);
            Log.d(TAG, "ScanMode: " + scanMode + " expectedScanMode: " + expectedScanMode);

            // Turn off screen
            setScreenOn(false);
            // Create scan client
            ScanClient client = createScanClient(isFiltered, scanMode, isBatch, isAutoBatch);
            // Start scan
            startScan(client);
            assertThat(mScanManager.getRegularScanQueue()).doesNotContain(client);
            assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client);
            assertThat(mScanManager.getBatchScanParams().scanMode()).isEqualTo(expectedScanMode);
            // Turn on screen
            setScreenOn(true);
            assertThat(mScanManager.getRegularScanQueue()).doesNotContain(client);
            assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client);
            assertThat(mScanManager.getBatchScanQueue()).contains(client);
            assertThat(mScanManager.getBatchScanParams().scanMode()).isEqualTo(expectedScanMode);
        }
    }

    @Test
    public void testUnfilteredAutoBatchScan() {
        // Set filtered and batch scan flag
        final boolean isFiltered = false;
        final boolean isBatch = true;
        final boolean isAutoBatch = true;
        // Set report delay for auto batch scan callback type
        mScanReportDelay = ScanSettings.AUTO_BATCH_MIN_REPORT_DELAY_MILLIS;

        defaultScanMode.forEach(
                (scanMode, expectedScanMode) -> {
                    mClientId = mClientId + 1;
                    expectedScanMode = SCAN_MODE_SCREEN_OFF;
                    Log.d(TAG, "ScanMode: " + scanMode + " expectedScanMode: " + expectedScanMode);

                    // Turn off screen
                    setScreenOn(false);
                    // Create scan client
                    ScanClient client =
                            createScanClient(isFiltered, scanMode, isBatch, isAutoBatch);
                    // Start scan
                    startScan(client);
                    assertThat(mScanManager.getRegularScanQueue()).doesNotContain(client);
                    assertThat(mScanManager.getSuspendedScanQueue()).contains(client);
                    assertThat(mScanManager.getBatchScanQueue()).doesNotContain(client);
                    assertThat(mScanManager.getBatchScanParams()).isNull();
                    // Turn on screen
                    setScreenOn(true);
                    assertThat(mScanManager.getRegularScanQueue()).contains(client);
                    assertThat(client.getSettings().getScanMode()).isEqualTo(scanMode);
                    assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client);
                    assertThat(mScanManager.getBatchScanQueue()).doesNotContain(client);
                    assertThat(mScanManager.getBatchScanParams()).isNull();
                    // Turn off screen
                    setScreenOn(false);
                    assertThat(mScanManager.getRegularScanQueue()).doesNotContain(client);
                    assertThat(mScanManager.getSuspendedScanQueue()).contains(client);
                    assertThat(mScanManager.getBatchScanQueue()).doesNotContain(client);
                    assertThat(mScanManager.getBatchScanParams()).isNull();
                });
    }

    @Test
    public void testFilteredAutoBatchScan() {
        // Set filtered and batch scan flag
        final boolean isFiltered = true;
        final boolean isBatch = true;
        final boolean isAutoBatch = true;
        // Set report delay for auto batch scan callback type
        mScanReportDelay = ScanSettings.AUTO_BATCH_MIN_REPORT_DELAY_MILLIS;

        defaultScanMode.forEach(
                (scanMode, expectedScanMode) -> {
                    mClientId = mClientId + 1;
                    expectedScanMode = SCAN_MODE_SCREEN_OFF;
                    Log.d(TAG, "ScanMode: " + scanMode + " expectedScanMode: " + expectedScanMode);

                    // Turn off screen
                    setScreenOn(false);
                    // Create scan client
                    ScanClient client =
                            createScanClient(isFiltered, scanMode, isBatch, isAutoBatch);
                    // Start scan
                    startScan(client);
                    assertThat(mScanManager.getRegularScanQueue()).doesNotContain(client);
                    assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client);
                    assertThat(mScanManager.getBatchScanQueue()).contains(client);
                    assertThat(mScanManager.getBatchScanParams().scanMode())
                            .isEqualTo(expectedScanMode);
                    // Turn on screen
                    setScreenOn(true);
                    assertThat(mScanManager.getRegularScanQueue()).contains(client);
                    assertThat(client.getSettings().getScanMode()).isEqualTo(scanMode);
                    assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client);
                    assertThat(mScanManager.getBatchScanQueue()).doesNotContain(client);
                    assertThat(mScanManager.getBatchScanParams()).isNull();
                    // Turn off screen
                    setScreenOn(false);
                    assertThat(mScanManager.getRegularScanQueue()).doesNotContain(client);
                    assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client);
                    assertThat(mScanManager.getBatchScanQueue()).contains(client);
                    assertThat(mScanManager.getBatchScanParams().scanMode())
                            .isEqualTo(expectedScanMode);
                });
    }

    @Test
    public void testLocationAndScreenOnOffResumeUnfilteredScan() {
        // Set filtered scan flag
        final boolean isFiltered = false;
        // Set scan mode array
        int[] scanModeArr = {
            SCAN_MODE_LOW_POWER,
            SCAN_MODE_BALANCED,
            SCAN_MODE_LOW_LATENCY,
            SCAN_MODE_AMBIENT_DISCOVERY
        };

        for (int i = 0; i < scanModeArr.length; i++) {
            int scanMode = scanModeArr[i];
            Log.d(TAG, "ScanMode: " + scanMode);
            // Turn on screen
            setScreenOn(true);
            // Create scan client
            ScanClient client = createScanClient(isFiltered, scanMode);
            // Start scan
            startScan(client);
            assertThat(mScanManager.getRegularScanQueue()).contains(client);
            assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client);
            // Turn off location
            doReturn(false).when(mLocationManager).isLocationEnabled();
            setLocationOn(false);
            assertThat(mScanManager.getRegularScanQueue()).doesNotContain(client);
            assertThat(mScanManager.getSuspendedScanQueue()).contains(client);
            // Turn off screen
            setScreenOn(false);
            assertThat(mScanManager.getRegularScanQueue()).doesNotContain(client);
            assertThat(mScanManager.getSuspendedScanQueue()).contains(client);
            // Turn on screen
            setScreenOn(true);
            assertThat(mScanManager.getRegularScanQueue()).doesNotContain(client);
            assertThat(mScanManager.getSuspendedScanQueue()).contains(client);
            // Turn on location
            doReturn(true).when(mLocationManager).isLocationEnabled();
            setLocationOn(true);
            assertThat(mScanManager.getRegularScanQueue()).contains(client);
            assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client);
        }
    }

    @Test
    public void testMetricsAppScanScreenOn() {
        // Set filtered scan flag
        final boolean isFiltered = true;
        final long scanTestDuration = 100;
        // Turn on screen
        setScreenOn(true);

        // Set scan mode map {original scan mode (ScanMode) : logged scan mode (loggedScanMode)}
        SparseIntArray scanModeMap = new SparseIntArray();
        scanModeMap.put(
                SCAN_MODE_LOW_POWER,
                BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__LE_SCAN_MODE__SCAN_MODE_LOW_POWER);
        scanModeMap.put(
                SCAN_MODE_BALANCED,
                BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__LE_SCAN_MODE__SCAN_MODE_BALANCED);
        scanModeMap.put(
                SCAN_MODE_LOW_LATENCY,
                BluetoothStatsLog.LE_APP_SCAN_STATE_CHANGED__LE_SCAN_MODE__SCAN_MODE_LOW_LATENCY);
        scanModeMap.put(
                SCAN_MODE_AMBIENT_DISCOVERY,
                BluetoothStatsLog
                        .LE_APP_SCAN_STATE_CHANGED__LE_SCAN_MODE__SCAN_MODE_AMBIENT_DISCOVERY);

        for (int i = 0; i < scanModeMap.size(); i++) {
            int scanMode = scanModeMap.keyAt(i);
            int loggedScanMode = scanModeMap.get(scanMode);

            // Create workSource for the app
            final String APP_NAME = TEST_APP_NAME + i;
            final int UID = 10000 + i;
            final String PACKAGE_NAME = TEST_PACKAGE_NAME + i;
            WorkSource source = new WorkSource(UID, PACKAGE_NAME);
            // Create app scan stats for the app
            final int appUid = 1234;
            AppScanStats appScanStats =
                    spy(
                            new AppScanStats(
                                    APP_NAME,
                                    source,
                                    appUid,
                                    mAdapterService,
                                    mScanController,
                                    mTimeProvider));
            // Set app importance as Foreground Service for the stats
            appScanStats.setAppImportance(IMPORTANCE_FOREGROUND_SERVICE);
            // Create scan client for the app, which also records scan start
            ScanClient client = createScanClient(isFiltered, scanMode, UID, appScanStats);
            // Verify that the app scan start is logged
            mInOrder.verify(mMetricsLogger)
                    .logAppScanStateChanged(
                            new int[] {UID},
                            new String[] {PACKAGE_NAME},
                            true,
                            true,
                            false,
                            BluetoothStatsLog
                                    .LE_APP_SCAN_STATE_CHANGED__SCAN_CALLBACK_TYPE__TYPE_ALL_MATCHES,
                            BluetoothStatsLog
                                    .LE_APP_SCAN_STATE_CHANGED__LE_SCAN_TYPE__SCAN_TYPE_REGULAR,
                            loggedScanMode,
                            DEFAULT_REGULAR_SCAN_REPORT_DELAY_MS,
                            0,
                            0,
                            true,
                            false,
                            IMPORTANCE_FOREGROUND_SERVICE,
                            "");

            advanceTime(scanTestDuration);
            // Record scan stop
            client.getAppScanStats().get().recordScanStop(mClientId);
            // Verify that the app scan stop is logged
            mInOrder.verify(mMetricsLogger)
                    .logAppScanStateChanged(
                            eq(new int[] {UID}),
                            eq(new String[] {PACKAGE_NAME}),
                            eq(false),
                            eq(true),
                            eq(false),
                            eq(
                                    BluetoothStatsLog
                                            .LE_APP_SCAN_STATE_CHANGED__SCAN_CALLBACK_TYPE__TYPE_ALL_MATCHES),
                            eq(
                                    BluetoothStatsLog
                                            .LE_APP_SCAN_STATE_CHANGED__LE_SCAN_TYPE__SCAN_TYPE_REGULAR),
                            eq(loggedScanMode),
                            eq((long) DEFAULT_REGULAR_SCAN_REPORT_DELAY_MS),
                            eq(scanTestDuration),
                            eq(0),
                            eq(true),
                            eq(false),
                            eq(IMPORTANCE_FOREGROUND_SERVICE),
                            eq(""));
        }
    }

    @Test
    public void testMetricsRadioScanScreenOnOffMultiScan() {
        // Set filtered scan flag
        final boolean isFiltered = true;
        final long scanTestDuration = 100;
        // Turn on screen
        setScreenOn(true);

        // Create workSource for the first app
        final int UID_1 = 10001;
        final String APP_NAME_1 = TEST_APP_NAME + UID_1;
        final String PACKAGE_NAME_1 = TEST_PACKAGE_NAME + UID_1;
        WorkSource source1 = new WorkSource(UID_1, PACKAGE_NAME_1);
        // Create app scan stats for the first app
        final int appUid1 = 12341;
        AppScanStats appScanStats1 =
                spy(
                        new AppScanStats(
                                APP_NAME_1,
                                source1,
                                appUid1,
                                mAdapterService,
                                mScanController,
                                mTimeProvider));
        // Set app importance as Foreground Service for the stats
        appScanStats1.setAppImportance(IMPORTANCE_FOREGROUND_SERVICE);
        // Create scan client for the first app
        ScanClient client1 =
                createScanClient(isFiltered, SCAN_MODE_LOW_POWER, UID_1, appScanStats1);
        // Start scan with lower duty cycle for the first app
        startScan(client1);
        advanceTime(scanTestDuration);

        // Create workSource for the second app
        final int UID_2 = 10002;
        final String APP_NAME_2 = TEST_APP_NAME + UID_2;
        final String PACKAGE_NAME_2 = TEST_PACKAGE_NAME + UID_2;
        WorkSource source2 = new WorkSource(UID_2, PACKAGE_NAME_2);
        // Create app scan stats for the second app
        final int appUid2 = 12342;
        AppScanStats appScanStats2 =
                spy(
                        new AppScanStats(
                                APP_NAME_2,
                                source2,
                                appUid2,
                                mAdapterService,
                                mScanController,
                                mTimeProvider));
        // Set app importance as Foreground Service for the stats
        appScanStats2.setAppImportance(IMPORTANCE_FOREGROUND_SERVICE);
        // Create scan client for the second app
        ScanClient client2 = createScanClient(isFiltered, SCAN_MODE_BALANCED, UID_2, appScanStats2);
        // Start scan with higher duty cycle for the second app
        startScan(client2);
        // Verify radio scan stop is logged with the first app
        mInOrder.verify(mMetricsLogger)
                .logRadioScanStopped(
                        eq(new int[] {UID_1}),
                        eq(new String[] {PACKAGE_NAME_1}),
                        eq(
                                BluetoothStatsLog
                                        .LE_APP_SCAN_STATE_CHANGED__LE_SCAN_TYPE__SCAN_TYPE_REGULAR),
                        eq(
                                BluetoothStatsLog
                                        .LE_APP_SCAN_STATE_CHANGED__LE_SCAN_MODE__SCAN_MODE_LOW_POWER),
                        eq((long) SCAN_MODE_LOW_POWER_INTERVAL_MS),
                        eq((long) SCAN_MODE_LOW_POWER_WINDOW_MS),
                        eq(true),
                        eq(scanTestDuration),
                        eq(IMPORTANCE_FOREGROUND_SERVICE),
                        eq(""));
        advanceTime(scanTestDuration);

        // Create workSource for the third app
        final int UID_3 = 10003;
        final String APP_NAME_3 = TEST_APP_NAME + UID_3;
        final String PACKAGE_NAME_3 = TEST_PACKAGE_NAME + UID_3;
        WorkSource source3 = new WorkSource(UID_3, PACKAGE_NAME_3);
        // Create app scan stats for the third app
        final int appUid3 = 12343;
        AppScanStats appScanStats3 =
                spy(
                        new AppScanStats(
                                APP_NAME_3,
                                source3,
                                appUid3,
                                mAdapterService,
                                mScanController,
                                mTimeProvider));
        // Set app importance as Foreground Service for the stats
        appScanStats3.setAppImportance(IMPORTANCE_FOREGROUND_SERVICE);
        // Create scan client for the third app
        ScanClient client3 =
                createScanClient(isFiltered, SCAN_MODE_LOW_LATENCY, UID_3, appScanStats3);
        // Start scan with highest duty cycle for the third app
        startScan(client3);
        // Verify radio scan stop is logged with the second app
        mInOrder.verify(mMetricsLogger)
                .logRadioScanStopped(
                        eq(new int[] {UID_2}),
                        eq(new String[] {PACKAGE_NAME_2}),
                        eq(
                                BluetoothStatsLog
                                        .LE_APP_SCAN_STATE_CHANGED__LE_SCAN_TYPE__SCAN_TYPE_REGULAR),
                        eq(
                                BluetoothStatsLog
                                        .LE_APP_SCAN_STATE_CHANGED__LE_SCAN_MODE__SCAN_MODE_BALANCED),
                        eq((long) SCAN_MODE_BALANCED_INTERVAL_MS),
                        eq((long) SCAN_MODE_BALANCED_WINDOW_MS),
                        eq(true),
                        eq(scanTestDuration),
                        eq(IMPORTANCE_FOREGROUND_SERVICE),
                        eq(""));
        advanceTime(scanTestDuration);

        // Create workSource for the fourth app
        final int UID_4 = 10004;
        final String APP_NAME_4 = TEST_APP_NAME + UID_4;
        final String PACKAGE_NAME_4 = TEST_PACKAGE_NAME + UID_4;
        WorkSource source4 = new WorkSource(UID_4, PACKAGE_NAME_4);
        // Create app scan stats for the fourth app
        final int appUid4 = 12344;
        AppScanStats appScanStats4 =
                spy(
                        new AppScanStats(
                                APP_NAME_4,
                                source4,
                                appUid4,
                                mAdapterService,
                                mScanController,
                                mTimeProvider));
        // Set app importance as Foreground Service for the stats
        appScanStats4.setAppImportance(IMPORTANCE_FOREGROUND_SERVICE);
        // Create scan client for the fourth app
        ScanClient client4 =
                createScanClient(isFiltered, SCAN_MODE_AMBIENT_DISCOVERY, UID_4, appScanStats4);
        // Start scan with lower duty cycle for the fourth app
        startScan(client4);
        // Verify radio scan stop is not logged with the third app since there is no change in radio
        // scan
        mInOrder.verify(mMetricsLogger, never())
                .logRadioScanStopped(
                        eq(new int[] {UID_3}),
                        eq(new String[] {PACKAGE_NAME_3}),
                        anyInt(),
                        anyInt(),
                        anyLong(),
                        anyLong(),
                        anyBoolean(),
                        anyLong(),
                        anyInt(),
                        eq(""));
        advanceTime(scanTestDuration);

        // Set as background app
        setAppImportance(false, UID_1);
        setAppImportance(false, UID_2);
        setAppImportance(false, UID_3);
        setAppImportance(false, UID_4);
        // Turn off screen
        setScreenOn(false);
        // Verify radio scan stop is logged with the third app when screen turns off
        mInOrder.verify(mMetricsLogger)
                .logRadioScanStopped(
                        eq(new int[] {UID_3}),
                        eq(new String[] {PACKAGE_NAME_3}),
                        eq(
                                BluetoothStatsLog
                                        .LE_APP_SCAN_STATE_CHANGED__LE_SCAN_TYPE__SCAN_TYPE_REGULAR),
                        eq(
                                BluetoothStatsLog
                                        .LE_APP_SCAN_STATE_CHANGED__LE_SCAN_MODE__SCAN_MODE_LOW_LATENCY),
                        eq((long) SCAN_MODE_LOW_LATENCY_INTERVAL_MS),
                        eq((long) SCAN_MODE_LOW_LATENCY_WINDOW_MS),
                        eq(true),
                        eq(scanTestDuration * 2),
                        eq(IMPORTANCE_FOREGROUND_SERVICE),
                        eq(""));
        advanceTime(scanTestDuration);

        // Get the most aggressive scan client when screen is off
        // Since all the clients are updated to SCAN_MODE_SCREEN_OFF when screen is off and
        // app is in background mode, get the first client in the iterator
        Set<ScanClient> scanClients = mScanManager.getRegularScanQueue();
        ScanClient mostAggressiveClient = scanClients.iterator().next();

        // Turn on screen
        setScreenOn(true);
        // Set as foreground app
        setAppImportance(true, UID_1);
        setAppImportance(true, UID_2);
        setAppImportance(true, UID_3);
        setAppImportance(true, UID_4);
        // Verify radio scan stop is logged with the third app when screen turns on
        mInOrder.verify(mMetricsLogger)
                .logRadioScanStopped(
                        eq(new int[] {mostAggressiveClient.getAppUid()}),
                        eq(new String[] {TEST_PACKAGE_NAME + mostAggressiveClient.getAppUid()}),
                        eq(
                                BluetoothStatsLog
                                        .LE_APP_SCAN_STATE_CHANGED__LE_SCAN_TYPE__SCAN_TYPE_REGULAR),
                        eq(AppScanStats.convertScanMode(mostAggressiveClient.getScanModeApp())),
                        eq(SCAN_MODE_SCREEN_OFF_LOW_POWER_INTERVAL.toMillis()),
                        eq(SCAN_MODE_SCREEN_OFF_LOW_POWER_WINDOW.toMillis()),
                        eq(false),
                        eq(scanTestDuration),
                        eq(IMPORTANCE_FOREGROUND_SERVICE + 1),
                        eq(""));
        advanceTime(scanTestDuration);

        // Stop scan for the fourth app
        stopScan(client4);
        // Verify radio scan stop is not logged with the third app since there is no change in radio
        // scan
        mInOrder.verify(mMetricsLogger, never())
                .logRadioScanStopped(
                        eq(new int[] {UID_3}),
                        eq(new String[] {PACKAGE_NAME_3}),
                        anyInt(),
                        anyInt(),
                        anyLong(),
                        anyLong(),
                        anyBoolean(),
                        anyLong(),
                        anyInt(),
                        eq(""));
        advanceTime(scanTestDuration);

        // Stop scan for the third app
        stopScan(client3);
        // Verify radio scan stop is logged with the third app
        mInOrder.verify(mMetricsLogger)
                .logRadioScanStopped(
                        eq(new int[] {UID_3}),
                        eq(new String[] {PACKAGE_NAME_3}),
                        eq(
                                BluetoothStatsLog
                                        .LE_APP_SCAN_STATE_CHANGED__LE_SCAN_TYPE__SCAN_TYPE_REGULAR),
                        eq(
                                BluetoothStatsLog
                                        .LE_APP_SCAN_STATE_CHANGED__LE_SCAN_MODE__SCAN_MODE_LOW_LATENCY),
                        eq((long) SCAN_MODE_LOW_LATENCY_INTERVAL_MS),
                        eq((long) SCAN_MODE_LOW_LATENCY_WINDOW_MS),
                        eq(true),
                        eq(scanTestDuration * 2),
                        eq(IMPORTANCE_FOREGROUND_SERVICE),
                        eq(""));
        advanceTime(scanTestDuration);

        // Stop scan for the second app
        stopScan(client2);
        // Verify radio scan stop is logged with the second app
        mInOrder.verify(mMetricsLogger)
                .logRadioScanStopped(
                        eq(new int[] {UID_2}),
                        eq(new String[] {PACKAGE_NAME_2}),
                        eq(
                                BluetoothStatsLog
                                        .LE_APP_SCAN_STATE_CHANGED__LE_SCAN_TYPE__SCAN_TYPE_REGULAR),
                        eq(
                                BluetoothStatsLog
                                        .LE_APP_SCAN_STATE_CHANGED__LE_SCAN_MODE__SCAN_MODE_BALANCED),
                        eq((long) SCAN_MODE_BALANCED_INTERVAL_MS),
                        eq((long) SCAN_MODE_BALANCED_WINDOW_MS),
                        eq(true),
                        eq(scanTestDuration),
                        eq(IMPORTANCE_FOREGROUND_SERVICE),
                        eq(""));
        advanceTime(scanTestDuration);

        // Stop scan for the first app
        stopScan(client1);
        // Verify radio scan stop is logged with the first app
        mInOrder.verify(mMetricsLogger)
                .logRadioScanStopped(
                        eq(new int[] {UID_1}),
                        eq(new String[] {PACKAGE_NAME_1}),
                        eq(
                                BluetoothStatsLog
                                        .LE_APP_SCAN_STATE_CHANGED__LE_SCAN_TYPE__SCAN_TYPE_REGULAR),
                        eq(
                                BluetoothStatsLog
                                        .LE_APP_SCAN_STATE_CHANGED__LE_SCAN_MODE__SCAN_MODE_LOW_POWER),
                        eq((long) SCAN_MODE_LOW_POWER_INTERVAL_MS),
                        eq((long) SCAN_MODE_LOW_POWER_WINDOW_MS),
                        eq(true),
                        eq(scanTestDuration),
                        eq(IMPORTANCE_FOREGROUND_SERVICE),
                        eq(""));
    }

    @Test
    public void testMetricsScanRadioDurationScreenOn() {
        // Set filtered scan flag
        final boolean isFiltered = true;
        // Turn on screen
        setScreenOn(true);
        Mockito.clearInvocations(mMetricsLogger);
        // Create scan client
        ScanClient client = createScanClient(isFiltered, SCAN_MODE_LOW_POWER);
        // Start scan
        startScan(client);
        mInOrder.verify(mMetricsLogger, never())
                .cacheCount(eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR), anyLong());
        mInOrder.verify(mMetricsLogger, never())
                .cacheCount(
                        eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_ON),
                        anyLong());
        mInOrder.verify(mMetricsLogger, never())
                .cacheCount(
                        eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_OFF),
                        anyLong());
        advanceTime(50);
        // Stop scan
        stopScan(client);
        mInOrder.verify(mMetricsLogger)
                .cacheCount(eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR), anyLong());
        mInOrder.verify(mMetricsLogger)
                .cacheCount(
                        eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_ON),
                        anyLong());
        mInOrder.verify(mMetricsLogger, never())
                .cacheCount(
                        eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_OFF),
                        anyLong());
    }

    @Test
    public void testMetricsScanRadioDurationScreenOnOff() {
        // Set filtered scan flag
        final boolean isFiltered = true;
        // Turn on screen
        setScreenOn(true);
        Mockito.clearInvocations(mMetricsLogger);
        // Create scan client
        ScanClient client = createScanClient(isFiltered, SCAN_MODE_LOW_POWER);
        // Start scan
        startScan(client);
        mInOrder.verify(mMetricsLogger, never())
                .cacheCount(eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR), anyLong());
        mInOrder.verify(mMetricsLogger, never())
                .cacheCount(
                        eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_ON),
                        anyLong());
        mInOrder.verify(mMetricsLogger, never())
                .cacheCount(
                        eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_OFF),
                        anyLong());
        advanceTime(50);
        // Turn off screen
        setScreenOn(false);
        mInOrder.verify(mMetricsLogger)
                .cacheCount(eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR), anyLong());
        mInOrder.verify(mMetricsLogger)
                .cacheCount(
                        eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_ON),
                        anyLong());
        mInOrder.verify(mMetricsLogger, never())
                .cacheCount(
                        eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_OFF),
                        anyLong());
        advanceTime(50);
        // Turn on screen
        setScreenOn(true);
        mInOrder.verify(mMetricsLogger)
                .cacheCount(eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR), anyLong());
        mInOrder.verify(mMetricsLogger, never())
                .cacheCount(
                        eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_ON),
                        anyLong());
        mInOrder.verify(mMetricsLogger)
                .cacheCount(
                        eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_OFF),
                        anyLong());
        advanceTime(50);
        // Stop scan
        stopScan(client);
        mInOrder.verify(mMetricsLogger)
                .cacheCount(eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR), anyLong());
        mInOrder.verify(mMetricsLogger)
                .cacheCount(
                        eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_ON),
                        anyLong());
        mInOrder.verify(mMetricsLogger, never())
                .cacheCount(
                        eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_OFF),
                        anyLong());
    }

    @Test
    public void testMetricsScanRadioDurationMultiScan() {
        // Set filtered scan flag
        final boolean isFiltered = true;
        // Turn on screen
        setScreenOn(true);
        Mockito.clearInvocations(mMetricsLogger);
        // Create scan clients with different duty cycles
        ScanClient client = createScanClient(isFiltered, SCAN_MODE_LOW_POWER);
        ScanClient client2 = createScanClient(isFiltered, SCAN_MODE_BALANCED);
        // Start scan with lower duty cycle
        startScan(client);
        mInOrder.verify(mMetricsLogger, never())
                .cacheCount(eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR), anyLong());
        mInOrder.verify(mMetricsLogger, never())
                .cacheCount(
                        eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_ON),
                        anyLong());
        mInOrder.verify(mMetricsLogger, never())
                .cacheCount(
                        eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_OFF),
                        anyLong());
        advanceTime(50);
        // Start scan with higher duty cycle
        startScan(client2);
        mInOrder.verify(mMetricsLogger)
                .cacheCount(eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR), anyLong());
        mInOrder.verify(mMetricsLogger)
                .cacheCount(
                        eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_ON),
                        anyLong());
        mInOrder.verify(mMetricsLogger, never())
                .cacheCount(
                        eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_OFF),
                        anyLong());
        advanceTime(50);
        // Stop scan with lower duty cycle
        stopScan(client);
        mInOrder.verify(mMetricsLogger, never()).cacheCount(anyInt(), anyLong());
        // Stop scan with higher duty cycle
        stopScan(client2);
        mInOrder.verify(mMetricsLogger)
                .cacheCount(eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR), anyLong());
        mInOrder.verify(mMetricsLogger)
                .cacheCount(
                        eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_ON),
                        anyLong());
        mInOrder.verify(mMetricsLogger, never())
                .cacheCount(
                        eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR_SCREEN_OFF),
                        anyLong());
    }

    @Test
    public void testMetricsScanRadioWeightedDuration() {
        // Set filtered scan flag
        final boolean isFiltered = true;
        final long scanTestDuration = 100;
        // Set scan mode map {scan mode (ScanMode) : scan weight (ScanWeight)}
        SparseIntArray scanModeMap = new SparseIntArray();
        scanModeMap.put(SCAN_MODE_SCREEN_OFF, ScanUtil.WEIGHT_SCREEN_OFF_LOW_POWER);
        scanModeMap.put(SCAN_MODE_LOW_POWER, ScanUtil.WEIGHT_LOW_POWER);
        scanModeMap.put(SCAN_MODE_BALANCED, ScanUtil.WEIGHT_BALANCED);
        scanModeMap.put(SCAN_MODE_LOW_LATENCY, ScanUtil.WEIGHT_LOW_LATENCY);
        scanModeMap.put(SCAN_MODE_AMBIENT_DISCOVERY, ScanUtil.WEIGHT_AMBIENT_DISCOVERY);

        // Turn on screen
        setScreenOn(true);
        for (int i = 0; i < scanModeMap.size(); i++) {
            int scanMode = scanModeMap.keyAt(i);
            long weightedScanDuration =
                    (long) (scanTestDuration * scanModeMap.get(scanMode) * 0.01);
            Log.d(TAG, "ScanMode: " + scanMode + " weightedScanDuration: " + weightedScanDuration);

            // Create scan client
            ScanClient client = createScanClient(isFiltered, scanMode);
            // Start scan
            startScan(client);
            // Wait for scan test duration
            advanceTime(Duration.ofMillis(scanTestDuration));
            // Stop scan
            stopScan(client);
            mInOrder.verify(mMetricsLogger)
                    .cacheCount(
                            eq(BluetoothProtoEnums.LE_SCAN_RADIO_DURATION_REGULAR),
                            eq(weightedScanDuration));
        }
    }

    @Test
    public void testMetricsScreenOnOff() {
        // Turn off screen initially
        setScreenOn(false);
        Mockito.clearInvocations(mMetricsLogger);
        // Turn on screen
        setScreenOn(true);
        mInOrder.verify(mMetricsLogger, never())
                .cacheCount(eq(BluetoothProtoEnums.SCREEN_OFF_EVENT), anyLong());
        mInOrder.verify(mMetricsLogger)
                .cacheCount(eq(BluetoothProtoEnums.SCREEN_ON_EVENT), anyLong());
        // Turn off screen
        setScreenOn(false);
        mInOrder.verify(mMetricsLogger, never())
                .cacheCount(eq(BluetoothProtoEnums.SCREEN_ON_EVENT), anyLong());
        mInOrder.verify(mMetricsLogger)
                .cacheCount(eq(BluetoothProtoEnums.SCREEN_OFF_EVENT), anyLong());
    }

    @Test
    public void testDowngradeWithNonNullClientAppScanStats() {
        // Set filtered scan flag
        final boolean isFiltered = true;

        doReturn(DEFAULT_SCAN_DOWNGRADE_DURATION_BT_CONNECTING)
                .when(mAdapterService)
                .getScanDowngradeDuration();

        // Turn off screen
        setScreenOn(false);
        // Create scan client
        ScanClient client = createScanClient(isFiltered, SCAN_MODE_LOW_LATENCY);
        // Start Scan
        startScan(client);
        assertThat(mScanManager.getRegularScanQueue()).contains(client);
        assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client);
        assertThat(client.getSettings().getScanMode()).isEqualTo(SCAN_MODE_LOW_LATENCY);
        // Set connecting state
        setConnectingState(true);
        // SCAN_MODE_LOW_LATENCY is now downgraded to SCAN_MODE_BALANCED
        assertThat(client.getSettings().getScanMode()).isEqualTo(SCAN_MODE_BALANCED);
    }

    @Test
    public void testDowngradeWithNullClientAppScanStats() {
        // Set filtered scan flag
        final boolean isFiltered = true;

        doReturn(DEFAULT_SCAN_DOWNGRADE_DURATION_BT_CONNECTING)
                .when(mAdapterService)
                .getScanDowngradeDuration();

        // Turn off screen
        setScreenOn(false);
        // Create scan client
        ScanClient client = createScanClient(isFiltered, SCAN_MODE_LOW_LATENCY);
        // Start Scan
        startScan(client);
        assertThat(mScanManager.getRegularScanQueue()).contains(client);
        assertThat(mScanManager.getSuspendedScanQueue()).doesNotContain(client);
        assertThat(client.getSettings().getScanMode()).isEqualTo(SCAN_MODE_LOW_LATENCY);
        // Set AppScanStats to empty
        client.setAppScanStats(Optional.empty());
        // Set connecting state
        setConnectingState(true);
        // Since AppScanStats is null, no downgrade takes place for scan mode
        assertThat(client.getSettings().getScanMode()).isEqualTo(SCAN_MODE_LOW_LATENCY);
    }

    @Test
    public void profileConnectionStateChanged_sendStartConnectionMessage() {
        doReturn(DEFAULT_SCAN_DOWNGRADE_DURATION_BT_CONNECTING)
                .when(mAdapterService)
                .getScanDowngradeDuration();
        assertThat(mScanManager.mIsConnecting).isFalse();

        mScanManager.handleBluetoothProfileConnectionStateChanged(
                BluetoothProfile.A2DP, STATE_DISCONNECTED, STATE_CONNECTING);

        mLooper.dispatchAll();
        assertThat(mScanManager.mIsConnecting).isTrue();
    }

    @Test
    public void multipleProfileConnectionStateChanged_updateCountersCorrectly() {
        doReturn(DEFAULT_SCAN_DOWNGRADE_DURATION_BT_CONNECTING)
                .when(mAdapterService)
                .getScanDowngradeDuration();
        assertThat(mScanManager.mIsConnecting).isFalse();

        mScanManager.handleBluetoothProfileConnectionStateChanged(
                BluetoothProfile.HEADSET, STATE_DISCONNECTED, STATE_CONNECTING);
        mScanManager.handleBluetoothProfileConnectionStateChanged(
                BluetoothProfile.A2DP, STATE_DISCONNECTED, STATE_CONNECTING);
        mScanManager.handleBluetoothProfileConnectionStateChanged(
                BluetoothProfile.HID_HOST, STATE_DISCONNECTED, STATE_CONNECTING);
        mLooper.dispatchAll();
        assertThat(mScanManager.mProfilesConnecting).isEqualTo(3);
    }

    @Test
    public void getNumOfTrackingAdvertisements_withMaxTrackableAdvertisement() {
        ScanSettings scanSettings;
        scanSettings =
                new ScanSettings.Builder()
                        .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                        .build();

        assertThat(mScanManager.getNumOfTrackingAdvertisements(scanSettings))
                .isEqualTo(DEFAULT_TOTAL_NUM_OF_TRACKABLE_ADVERTISEMENTS / 4);
    }

    @Test
    public void startScan_withPhy1M() {
        verifyPhyScanForAllScanModes(
                PHY_LE_1M,
                /* expectedPhyMask= */ PHY_LE_1M_MASK,
                /* expect1m= */ true,
                /* expectCoded= */ false);
    }

    @Test
    public void startScan_withPhyCoded() {
        verifyPhyScanForAllScanModes(
                PHY_LE_CODED,
                /* expectedPhyMask= */ PHY_LE_CODED_MASK,
                /* expect1m= */ false,
                /* expectCoded= */ true);
    }

    @Test
    public void startScan_withAllSupportedPhys() {
        verifyPhyScanForAllScanModes(
                PHY_LE_ALL_SUPPORTED,
                /* expectedPhyMask= */ PHY_LE_1M_MASK | PHY_LE_CODED_MASK,
                /* expect1m= */ true,
                /* expectCoded= */ true);
    }

    // PHY_LE_1M: 1, PHY_LE_CODED: 3, PHY_LE_ALL_SUPPORTED: 255
    private void verifyPhyScanForAllScanModes(
            int phy, int expectedPhyMask, boolean expect1m, boolean expectCoded) {
        final boolean isFiltered = false;
        final boolean isEmptyFilter = false;

        defaultScanMode.forEach(
                (scanMode, expectedScanMode) -> {
                    mClientId = mClientId + 1;
                    Log.d(TAG, "ScanMode: " + scanMode + " expectedScanMode: " + expectedScanMode);

                    // Turn on screen
                    setScreenOn(true);
                    // Create scan client
                    ScanClient client =
                            createScanClientWithPhy(
                                    mClientId, isFiltered, isEmptyFilter, scanMode, phy);
                    // Start scan
                    startScan(client);

                    assertThat(client.getSettings().getPhy()).isEqualTo(phy);
                    verify(mScanNativeInterface)
                            .gattSetScanParameters(
                                    eq(expect1m ? mClientId : 0),
                                    anyInt(),
                                    anyInt(),
                                    eq(expectCoded ? mClientId : 0),
                                    anyInt(),
                                    anyInt(),
                                    eq(expectedPhyMask));

                    // Stop scan
                    stopScan(client);
                });
    }

    @Test
    public void startScan_phyTestMultiplexing() {
        int clientId1m = ++mClientId;
        int clientIdCoded = ++mClientId;

        // Turn on screen
        setScreenOn(true);

        // Create 1m scan client
        ScanClient client1m =
                createScanClientWithPhy(clientId1m, true, false, SCAN_MODE_LOW_LATENCY, PHY_LE_1M);

        // Start scan on 1m
        startScan(client1m);

        assertThat(client1m.getSettings().getPhy()).isEqualTo(PHY_LE_1M);
        verify(mScanNativeInterface)
                .gattSetScanParameters(
                        eq(clientId1m),
                        eq(Utils.millsToUnit(SCAN_MODE_LOW_LATENCY_INTERVAL_MS)),
                        eq(Utils.millsToUnit(SCAN_MODE_LOW_LATENCY_WINDOW_MS)),
                        eq(0),
                        anyInt(),
                        anyInt(),
                        eq(PHY_LE_1M_MASK));

        // Create coded scan client
        ScanClient clientCoded =
                createScanClientWithPhy(
                        clientIdCoded, true, false, SCAN_MODE_BALANCED, PHY_LE_CODED);

        // Start scan on coded
        startScan(clientCoded);

        assertThat(clientCoded.getSettings().getPhy()).isEqualTo(PHY_LE_CODED);
        verify(mScanNativeInterface)
                .gattSetScanParameters(
                        eq(clientId1m),
                        eq(Utils.millsToUnit(SCAN_MODE_LOW_LATENCY_INTERVAL_MS)),
                        eq(Utils.millsToUnit(SCAN_MODE_LOW_LATENCY_WINDOW_MS)),
                        eq(clientIdCoded),
                        eq(Utils.millsToUnit(SCAN_MODE_BALANCED_INTERVAL_MS)),
                        eq(Utils.millsToUnit(SCAN_MODE_BALANCED_WINDOW_MS)),
                        eq(PHY_LE_1M_MASK | PHY_LE_CODED_MASK));

        // Stop scan on 1m
        stopScan(client1m);

        verify(mScanNativeInterface)
                .gattSetScanParameters(
                        eq(0),
                        anyInt(),
                        anyInt(),
                        eq(clientIdCoded),
                        eq(Utils.millsToUnit(SCAN_MODE_BALANCED_INTERVAL_MS)),
                        eq(Utils.millsToUnit(SCAN_MODE_BALANCED_WINDOW_MS)),
                        eq(PHY_LE_CODED_MASK));

        // Stop scan on coded
        stopScan(clientCoded);

        verify(mScanNativeInterface, atLeastOnce()).scan(false);
        verify(mScanNativeInterface, never())
                .gattSetScanParameters(
                        anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), eq(0));
    }

    @Test
    @EnableFlags(Flags.FLAG_LE_SCAN_MSFT_SUPPORT)
    public void testMsftScan() {
        doReturn(true).when(mScanNativeInterface).isMsftSupported();
        doReturn(false).when(mAdapter).isOffloadedFilteringSupported();

        final boolean isFiltered = true;
        final ParcelUuid serviceUuid =
                new ParcelUuid(UUID.fromString("12345678-90AB-CDEF-1234-567890ABCDEF"));
        final byte[] serviceData = new byte[] {0x01, 0x02, 0x03};

        mockSystemPropertyGet(MSFT_HCI_EXT_ENABLED, true);

        // Create new ScanManager since sysprop and MSFT support are only checked when
        // ScanManager is created
        mScanManager =
                new ScanManager(
                        mAdapterService,
                        mScanController,
                        mScanNativeInterface,
                        mLooper.getLooper(),
                        mTimeProvider);

        // Turn on screen
        setScreenOn(true);
        // Create scan client with service data
        List<ScanFilter> scanFilterList =
                List.of(new ScanFilter.Builder().setServiceData(serviceUuid, serviceData).build());
        ScanClient client =
                createScanClient(
                        isFiltered,
                        SCAN_MODE_LOW_POWER,
                        false,
                        false,
                        Binder.getCallingUid(),
                        mMockAppScanStats,
                        scanFilterList);
        // Start scan
        startScan(client);

        // Create another scan client with the same service data
        ScanClient anotherClient =
                createScanClient(
                        isFiltered,
                        SCAN_MODE_LOW_POWER,
                        false,
                        false,
                        Binder.getCallingUid(),
                        mMockAppScanStats,
                        scanFilterList);
        // Start scan
        startScan(anotherClient);

        // Verify MSFT APIs are only called once
        verify(mScanNativeInterface)
                .msftAdvMonitorAdd(
                        any(MsftAdvMonitor.Monitor.class),
                        any(MsftAdvMonitor.Pattern[].class),
                        any(MsftAdvMonitor.Address.class),
                        anyInt());
        verify(mScanNativeInterface).msftAdvMonitorEnable(eq(true));
    }

    @Test
    @EnableFlags(Flags.FLAG_LE_SCAN_MSFT_SUPPORT)
    public void testPreferApcfOverMsftScan() {
        doReturn(true).when(mScanNativeInterface).isMsftSupported();
        doReturn(true).when(mAdapter).isOffloadedFilteringSupported();

        final boolean isFiltered = true;
        final ParcelUuid serviceUuid =
                new ParcelUuid(UUID.fromString("12345678-90AB-CDEF-1234-567890ABCDEF"));
        final byte[] serviceData = new byte[] {0x01, 0x02, 0x03};

        mockSystemPropertyGet(MSFT_HCI_EXT_ENABLED, true);

        // Create new ScanManager since sysprop and MSFT support are only on ScanManager creation
        mScanManager =
                new ScanManager(
                        mAdapterService,
                        mScanController,
                        mScanNativeInterface,
                        mLooper.getLooper(),
                        mTimeProvider);

        // Turn on screen
        setScreenOn(true);
        // Create scan client with service data
        List<ScanFilter> scanFilterList =
                List.of(new ScanFilter.Builder().setServiceData(serviceUuid, serviceData).build());
        ScanClient client =
                createScanClient(
                        isFiltered,
                        SCAN_MODE_LOW_POWER,
                        false,
                        false,
                        Binder.getCallingUid(),
                        mMockAppScanStats,
                        scanFilterList);
        // Start scan
        startScan(client);

        // Verify APCF APIs are called
        verify(mScanNativeInterface).scanFilterParamAdd(any());

        // Verify MSFT APIs are never called
        verify(mScanNativeInterface, never())
                .msftAdvMonitorAdd(
                        any(MsftAdvMonitor.Monitor.class),
                        any(MsftAdvMonitor.Pattern[].class),
                        any(MsftAdvMonitor.Address.class),
                        anyInt());
        verify(mScanNativeInterface, never()).msftAdvMonitorEnable(anyBoolean());

        // Stop scan
        stopScan(client);

        // Verify APCF APIs are called
        verify(mScanNativeInterface).scanFilterParamDelete(anyInt(), anyInt());

        // Verify MSFT APIs are never called
        verify(mScanNativeInterface, never()).msftAdvMonitorRemove(anyInt());
        verify(mScanNativeInterface, never()).msftAdvMonitorEnable(anyBoolean());
    }
}
