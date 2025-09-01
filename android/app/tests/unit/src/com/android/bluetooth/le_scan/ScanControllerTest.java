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

package com.android.bluetooth.le_scan;

import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;

import static com.android.bluetooth.TestUtils.getTestDevice;
import static com.android.bluetooth.TestUtils.mockGetBluetoothManager;
import static com.android.bluetooth.TestUtils.mockGetRemoteDevice;
import static com.android.bluetooth.TestUtils.mockGetSystemService;
import static com.android.bluetooth.le_scan.ScanUtil.DEFAULT_REPORT_DELAY_FLOOR_MS;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.IPeriodicAdvertisingCallback;
import android.bluetooth.le.IScannerCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.companion.CompanionDeviceManager;
import android.content.AttributionSource;
import android.content.Context;
import android.content.res.Resources;
import android.location.LocationManager;
import android.os.Binder;
import android.os.RemoteException;
import android.os.WorkSource;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.bluetooth.TestLooper;
import com.android.bluetooth.TestUtils.FakeTimeProvider;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.flags.Flags;
import com.android.tests.bluetooth.FlagsWrapper;
import com.android.tests.bluetooth.MockitoRule;

import com.google.protobuf.ByteString;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;

import platform.test.runner.parameterized.ParameterizedAndroidJunit4;
import platform.test.runner.parameterized.Parameters;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Test cases for {@link ScanController}. */
@SmallTest
@RunWith(ParameterizedAndroidJunit4.class)
public class ScanControllerTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Rule public final SetFlagsRule mSetFlagsRule;

    @Mock private AttributionSource mAttributionSource;
    @Mock private AdapterService mAdapterService;
    @Mock private ScanManager mScanManager;
    @Mock private ScanNativeInterface mScanNativeInterface;
    @Mock private PeriodicScanManager mPeriodicScanManager;
    @Mock private PeriodicScanNativeInterface mPeriodicScanNativeInterface;
    @Mock private CompanionDeviceManager mCompanionDeviceManager;
    @Mock private Resources mResources;
    @Mock private ScannerMap mScannerMap;
    @Mock private ScannerMap.ScannerApp mApp;

    private static final int TEST_SCANNER_ID = 1;
    private static final int TEST_STATUS = 0;
    private static final int TEST_ACTION = 1;
    private static final int TEST_CLIENT_IF = 2;
    private static final String TEST_ADDRESS = "00:11:22:33:FF:EE";

    private final FakeTimeProvider mTimeProvider = new FakeTimeProvider();
    private final BluetoothDevice mDevice = getTestDevice(89);

    private ScanController mScanController;
    private TestLooper mLooper;

    @Parameters(name = "{0}")
    public static List<FlagsWrapper> getParams() {
        return FlagsWrapper.progressionOf(Flags.FLAG_SCAN_CONTROLLER_THREAD);
    }

    public ScanControllerTest(FlagsWrapper flags) {
        mSetFlagsRule = new SetFlagsRule(flags.getFlags());
    }

    @Before
    public void setUp() throws Exception {
        doReturn(mResources).when(mAdapterService).getResources();

        final Context context = InstrumentationRegistry.getInstrumentation().getContext();
        doReturn(context.getPackageManager()).when(mAdapterService).getPackageManager();
        doReturn(context.getSharedPreferences("ScanControllerTest", Context.MODE_PRIVATE))
                .when(mAdapterService)
                .getSharedPreferences(anyString(), anyInt());
        doReturn(TEST_ADDRESS).when(mDevice).getAddress();

        mockGetRemoteDevice(mAdapterService, mDevice);
        mockGetBluetoothManager(mAdapterService);
        mockGetSystemService(mAdapterService, LocationManager.class);

        mLooper = new TestLooper();
        mScanController =
                new ScanController(
                        mAdapterService,
                        mScanManager,
                        mScanNativeInterface,
                        mPeriodicScanManager,
                        mPeriodicScanNativeInterface,
                        mScannerMap,
                        mCompanionDeviceManager,
                        mLooper.getLooper(),
                        mTimeProvider);
    }

    @After
    public void tearDown() throws Exception {
        mScanController.cleanup();
    }

    @Test
    public void notifyProfileConnectionStateChange_notify_scanManager() {
        mScanController.notifyProfileConnectionStateChange(
                BluetoothProfile.A2DP, STATE_CONNECTING, STATE_CONNECTED);
        verify(mScanManager)
                .handleBluetoothProfileConnectionStateChanged(
                        BluetoothProfile.A2DP, STATE_CONNECTING, STATE_CONNECTED);
    }

    @Test
    public void onScanResult_remoteException_clientDied() throws Exception {
        // scannable and scan response
        int eventType = 0x0A;
        int addressType = 0;
        int primaryPhy = 0;
        int secondPhy = 0;
        int advertisingSid = 0;
        int txPower = 0;
        int rssi = 0;
        int periodicAdvInt = 0;
        byte[] advData = new byte[0];

        final int appUid = 1234;
        ScanSettings scanSettings =
                new ScanSettings.Builder()
                        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                        .setLegacy(false)
                        .build();
        ScanClient scanClient = new ScanClient(TEST_SCANNER_ID, scanSettings, null, appUid);
        scanClient.setHasNetworkSettingsPermission(true);
        AppScanStats appScanStats = mock(AppScanStats.class);
        mApp.mAppScanStats = appScanStats;
        scanClient.setAppScanStats(Optional.of(appScanStats));
        IScannerCallback callback = mock(IScannerCallback.class);
        mApp.mCallback = callback;
        Set<ScanClient> scanClientSet = Collections.singleton(scanClient);
        doReturn(TEST_ADDRESS).when(mAdapterService).getIdentityAddress(anyString());
        doReturn(scanClientSet).when(mScanManager).getRegularScanQueue();
        doReturn(mApp).when(mScannerMap).getById(scanClient.getScannerId());
        doReturn(appScanStats).when(mScannerMap).getAppScanStatsById(scanClient.getScannerId());

        // Simulate remote client crash
        doThrow(new RemoteException()).when(callback).onScanResult(any());

        mScanController.onScanResult(
                eventType,
                addressType,
                TEST_ADDRESS,
                primaryPhy,
                secondPhy,
                advertisingSid,
                txPower,
                rssi,
                periodicAdvInt,
                advData,
                TEST_ADDRESS);

        assertThat(scanClient.getAppDied()).isTrue();
        verify(appScanStats).recordScanStop(TEST_SCANNER_ID);
    }

    @Test
    public void onScannerRegistered_success_callback() throws RemoteException {
        long uuidLsb = 12345L;
        long uuidMsb = 67890L;
        UUID uuid = new UUID(uuidMsb, uuidLsb);
        IScannerCallback callback = mock(IScannerCallback.class);
        mApp.mCallback = callback;
        doReturn(mApp).when(mScannerMap).getByUuid(uuid);

        mScanController.onScannerRegistered(TEST_STATUS, TEST_SCANNER_ID, uuidLsb, uuidMsb);

        verify(mApp).linkToDeath(any());
        verify(callback).onScannerRegistered(TEST_STATUS, TEST_SCANNER_ID);
        assertThat(mApp.mId).isEqualTo(TEST_SCANNER_ID);
    }

    @Test
    public void onScanFilterEnableDisabled_callbackDone_scanManager() {
        mScanController.onScanFilterEnableDisabled(TEST_ACTION, TEST_STATUS, TEST_CLIENT_IF);
        verify(mScanManager).callbackDone(TEST_CLIENT_IF, TEST_STATUS);
    }

    @Test
    public void onScanFilterParamsConfigured_callbackDone_scanManager() {
        int availableSpace = 3;

        mScanController.onScanFilterParamsConfigured(
                TEST_ACTION, TEST_STATUS, TEST_CLIENT_IF, availableSpace);
        verify(mScanManager).callbackDone(TEST_CLIENT_IF, TEST_STATUS);
    }

    @Test
    public void onScanFilterConfig_callbackDone_scanManager() {
        int filterType = 3;
        int availableSpace = 4;

        mScanController.onScanFilterConfig(
                TEST_ACTION, TEST_STATUS, TEST_CLIENT_IF, filterType, availableSpace);
        verify(mScanManager).callbackDone(TEST_CLIENT_IF, TEST_STATUS);
    }

    @Test
    public void onBatchScanStorageConfigured_callbackDone_scanManager() {
        mScanController.onBatchScanStorageConfigured(TEST_STATUS, TEST_CLIENT_IF);
        verify(mScanManager).callbackDone(TEST_CLIENT_IF, TEST_STATUS);
    }

    @Test
    public void onBatchScanStartStopped_callbackDone_scanManager() {
        int startStopAction = 0;

        mScanController.onBatchScanStartStopped(startStopAction, TEST_STATUS, TEST_CLIENT_IF);
        verify(mScanManager).callbackDone(TEST_CLIENT_IF, TEST_STATUS);
    }

    @Test
    public void onBatchScanReportsInternal_deliverTruncatedBatchScan_expectResults()
            throws RemoteException {
        verifyOnBatchScanReportsInternal(/* expectResults= */ true, /* isTruncated= */ true);
    }

    @Test
    public void onBatchScanReportsInternal_deliverTruncatedBatchScan_noResults()
            throws RemoteException {
        verifyOnBatchScanReportsInternal(/* expectResults= */ false, /* isTruncated= */ true);
    }

    @Test
    public void onBatchScanReportsInternal_deliverFullBatchScan_expectResults()
            throws RemoteException {
        verifyOnBatchScanReportsInternal(/* expectResults= */ true, /* isTruncated= */ false);
    }

    @Test
    public void onBatchScanReportsInternal_deliverFullBatchScan_noResults() throws RemoteException {
        verifyOnBatchScanReportsInternal(/* expectResults= */ false, /* isTruncated= */ false);
    }

    private void verifyOnBatchScanReportsInternal(boolean expectResults, boolean isTruncated)
            throws RemoteException {
        final int reportType =
                isTruncated ? ScanUtil.SCAN_RESULT_TYPE_TRUNCATED : ScanUtil.SCAN_RESULT_TYPE_FULL;
        final int numRecords = 1;
        final byte[] recordData;
        if (isTruncated) {
            recordData =
                    new byte[] {
                        0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0x06, 0x04, 0x02, 0x02, 0x00, 0x00, 0x02
                    };
        } else {
            recordData =
                    new byte[] {
                        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x00, 0x00, 0x00, 0x00
                    };
        }

        final BluetoothDevice device = getTestDevice("02:00:00:00:00:00");
        mockGetRemoteDevice(mAdapterService, device);

        Set<ScanClient> scanClientSet = new HashSet<>();
        final int appUid = 1234;
        ScanSettings scanSettings = new ScanSettings.Builder().build();
        ScanClient scanClient = new ScanClient(TEST_SCANNER_ID, scanSettings, null, appUid);
        scanClient.setAssociatedDevices(new ArrayList<>());
        if (expectResults) {
            if (isTruncated) {
                scanClient.getAssociatedDevices().add("02:00:00:00:00:00");
            } else {
                scanClient.setHasScanWithoutLocationPermission(true);
            }
        }
        scanClientSet.add(scanClient);
        if (isTruncated) {
            doReturn(scanClientSet).when(mScanManager).getBatchScanQueue();
        } else {
            doReturn(scanClientSet).when(mScanManager).getFullBatchScanQueue();
        }
        doReturn(mApp).when(mScannerMap).getById(scanClient.getScannerId());
        mApp.mAppScanStats = mock(AppScanStats.class);
        IScannerCallback callback = mock(IScannerCallback.class);
        mApp.mCallback = callback;

        mScanController.onBatchScanReportsInternal(
                TEST_STATUS, TEST_SCANNER_ID, reportType, numRecords, recordData);
        verify(mScanManager).callbackDone(TEST_SCANNER_ID, TEST_STATUS);
        if (expectResults) {
            verify(callback).onBatchScanResults(any());
        } else {
            verify(callback, never()).onBatchScanResults(any());
        }
    }

    @Test
    public void parseTimestampNanos() {
        long timestampNanos = mScanController.parseTimestampNanos(new byte[] {-54, 7});
        assertThat(timestampNanos).isEqualTo(99700000000L);
    }

    @Test
    public void createOnTrackAdvFoundLostObject() {
        int advPacketLen = 1;
        byte[] advPacket = new byte[] {0x02};
        int scanResponseLen = 3;
        byte[] scanResponse = new byte[] {0x04};
        int filtIndex = 5;
        int advState = ScanController.ADVT_STATE_ONFOUND;
        int advInfoPresent = 7;
        int addrType = BluetoothDevice.ADDRESS_TYPE_RANDOM;
        int txPower = 9;
        int rssiValue = 10;
        int timeStamp = 11;

        AdvtFilterOnFoundOnLostInfo advtFilterOnFoundOnLostInfo =
                new AdvtFilterOnFoundOnLostInfo(
                        TEST_SCANNER_ID,
                        advPacketLen,
                        ByteString.copyFrom(advPacket),
                        scanResponseLen,
                        ByteString.copyFrom(scanResponse),
                        filtIndex,
                        advState,
                        advInfoPresent,
                        TEST_ADDRESS,
                        addrType,
                        txPower,
                        rssiValue,
                        timeStamp);

        AdvtFilterOnFoundOnLostInfo advtFilterOnFoundOnLostInfoCreated =
                mScanController.createOnTrackAdvFoundLostObject(
                        TEST_SCANNER_ID,
                        advPacketLen,
                        advPacket,
                        scanResponseLen,
                        scanResponse,
                        filtIndex,
                        advState,
                        advInfoPresent,
                        TEST_ADDRESS,
                        addrType,
                        txPower,
                        rssiValue,
                        timeStamp);

        assertThat(advtFilterOnFoundOnLostInfo).isEqualTo(advtFilterOnFoundOnLostInfoCreated);
    }

    @Test
    public void onTrackAdvFoundLost() throws RemoteException {
        int advPacketLen = 1;
        byte[] advPacket = new byte[] {0x02};
        int scanResponseLen = 3;
        byte[] scanResponse = new byte[] {0x04};
        int filtIndex = 5;
        int advState = ScanController.ADVT_STATE_ONFOUND;
        int advInfoPresent = 7;
        int addrType = BluetoothDevice.ADDRESS_TYPE_RANDOM;
        int txPower = 9;
        int rssiValue = 10;
        int timeStamp = 11;

        final int appUid = 1234;
        ScanSettings scanSettings = new ScanSettings.Builder().build();
        ScanClient scanClient = new ScanClient(TEST_SCANNER_ID, scanSettings, null, appUid);
        scanClient.setHasNetworkSettingsPermission(true);
        scanClient.setSettings(
                new ScanSettings.Builder()
                        .setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
                        .setLegacy(false)
                        .build());
        Set<ScanClient> scanClientSet = Collections.singleton(scanClient);

        ScannerMap.ScannerApp app = mock(ScannerMap.ScannerApp.class);
        IScannerCallback callback = mock(IScannerCallback.class);
        app.mCallback = callback;

        doReturn(app).when(mScannerMap).getById(TEST_SCANNER_ID);
        doReturn(scanClientSet).when(mScanManager).getRegularScanQueue();

        AdvtFilterOnFoundOnLostInfo advtFilterOnFoundOnLostInfo =
                new AdvtFilterOnFoundOnLostInfo(
                        TEST_SCANNER_ID,
                        advPacketLen,
                        ByteString.copyFrom(advPacket),
                        scanResponseLen,
                        ByteString.copyFrom(scanResponse),
                        filtIndex,
                        advState,
                        advInfoPresent,
                        TEST_ADDRESS,
                        addrType,
                        txPower,
                        rssiValue,
                        timeStamp);

        mScanController.onTrackAdvFoundLost(advtFilterOnFoundOnLostInfo);
        ArgumentCaptor<ScanResult> result = ArgumentCaptor.forClass(ScanResult.class);
        verify(callback).onFoundOrLost(eq(true), result.capture());
        assertThat(result.getValue().getDevice()).isNotNull();
        assertThat(result.getValue().getDevice().getAddress()).isEqualTo(TEST_ADDRESS);
        assertThat(result.getValue().getDevice().getAddressType()).isEqualTo(addrType);
    }

    @Test
    public void registerScanner() {
        IScannerCallback callback = mock(IScannerCallback.class);
        WorkSource workSource = mock(WorkSource.class);
        AppScanStats appScanStats = mock(AppScanStats.class);
        doReturn(appScanStats).when(mScannerMap).getAppScanStatsByUid(Binder.getCallingUid());

        mScanController.registerScanner(callback, workSource, mAttributionSource);
        verify(mScannerMap)
                .addWithCallback(
                        any(),
                        eq(mAttributionSource),
                        eq(workSource),
                        anyInt(),
                        eq(callback),
                        any(),
                        eq(mScanController));
        verify(mScanManager).registerScanner(any());
    }

    @Test
    public void unregisterScanner() {
        mScanController.unregisterScanner(TEST_SCANNER_ID);

        verify(mScannerMap).remove(TEST_SCANNER_ID);
        verify(mScanManager).unregisterScanner(TEST_SCANNER_ID);
    }

    @Test
    public void continuePiStartScan() {
        ScanController.PendingIntentInfo pii =
                new ScanController.PendingIntentInfo(
                        null, new ScanSettings.Builder().build(), null, null, 0);
        mApp.mInfo = pii;

        AppScanStats appScanStats = mock(AppScanStats.class);
        doReturn(appScanStats).when(mScannerMap).getAppScanStatsById(TEST_SCANNER_ID);

        mScanController.continuePiStartScan(TEST_SCANNER_ID, mApp);
        verify(appScanStats)
                .recordScanStart(
                        pii.settings(), pii.filters(), false, false, TEST_SCANNER_ID, null);
        verify(mScanManager).startScan(any());
    }

    @Test
    public void continuePiStartScanCheckUid() {
        ScanController.PendingIntentInfo pii =
                new ScanController.PendingIntentInfo(
                        null, new ScanSettings.Builder().build(), null, null, 123);
        mApp.mInfo = pii;

        AppScanStats appScanStats = mock(AppScanStats.class);
        doReturn(appScanStats).when(mScannerMap).getAppScanStatsById(TEST_SCANNER_ID);

        mScanController.continuePiStartScan(TEST_SCANNER_ID, mApp);
        verify(appScanStats)
                .recordScanStart(
                        pii.settings(), pii.filters(), false, false, TEST_SCANNER_ID, null);
        verify(mScanManager)
                .startScan(
                        argThat(
                                new ArgumentMatcher<ScanClient>() {
                                    @Override
                                    public boolean matches(ScanClient client) {
                                        return pii.callingUid() == client.getAppUid();
                                    }
                                }));
    }

    @Test
    public void flushPendingBatchResults() {
        Set<ScanClient> scanClientSet = new HashSet<>();
        final int appUid = 1234;
        ScanSettings scanSettings = new ScanSettings.Builder().build();
        ScanClient scanClient = new ScanClient(TEST_SCANNER_ID, scanSettings, null, appUid);
        scanClientSet.add(scanClient);
        doReturn(scanClientSet).when(mScanManager).getBatchScanQueue();

        mScanController.flushPendingBatchResults(TEST_SCANNER_ID);
        verify(mScanManager).flushBatchScanResults(scanClient);
    }

    @Test
    public void registerSync() {
        ScanResult scanResult = new ScanResult(mDevice, 1, 2, 3, 4, 5, 6, 7, null, 8);
        int skip = 1;
        int timeout = 2;
        IPeriodicAdvertisingCallback callback = mock(IPeriodicAdvertisingCallback.class);

        mScanController.registerSync(scanResult, skip, timeout, callback);
        verify(mPeriodicScanManager).startSync(scanResult, skip, timeout, callback);
    }

    @Test
    public void unregisterSync() {
        IPeriodicAdvertisingCallback callback = mock(IPeriodicAdvertisingCallback.class);

        mScanController.unregisterSync(callback);
        verify(mPeriodicScanManager).stopSync(callback);
    }

    @Test
    public void transferSync() {
        int serviceData = 1;
        int syncHandle = 2;

        mScanController.transferSync(mDevice, serviceData, syncHandle);
        verify(mPeriodicScanManager).transferSync(mDevice, serviceData, syncHandle);
    }

    @Test
    public void transferSetInfo() {
        int serviceData = 1;
        int advHandle = 2;
        IPeriodicAdvertisingCallback callback = mock(IPeriodicAdvertisingCallback.class);

        mScanController.transferSetInfo(mDevice, serviceData, advHandle, callback);
        verify(mPeriodicScanManager).transferSetInfo(mDevice, serviceData, advHandle, callback);
    }

    @Test
    public void enforceReportDelayFloor() {
        long reportDelayFloorHigher = DEFAULT_REPORT_DELAY_FLOOR_MS + 1;
        ScanSettings scanSettings =
                new ScanSettings.Builder().setReportDelay(reportDelayFloorHigher).build();
        ScanSettings newScanSettings = mScanController.enforceReportDelayFloor(scanSettings);

        assertThat(newScanSettings.getReportDelayMillis())
                .isEqualTo(scanSettings.getReportDelayMillis());

        ScanSettings scanSettingsFloor = new ScanSettings.Builder().setReportDelay(1).build();
        ScanSettings newScanSettingsFloor =
                mScanController.enforceReportDelayFloor(scanSettingsFloor);

        assertThat(newScanSettingsFloor.getReportDelayMillis())
                .isEqualTo(DEFAULT_REPORT_DELAY_FLOOR_MS);
    }

    @Test
    @EnableFlags(Flags.FLAG_RSSI_SCAN_FILTER)
    public void matchesFilters_rssiThreshold() {
        final int rssiThreshold = -50;
        final int rssiAboveThreshold = -40;
        final int rssiBelowThreshold = -60;

        ScanSettings settings = new ScanSettings.Builder().setRssiThreshold(rssiThreshold).build();
        final int appUid = 1234;
        ScanClient client = new ScanClient(TEST_SCANNER_ID, settings, null, appUid);

        ScanRecord mockScanRecord = mock(ScanRecord.class);
        ScanResult resultAboveThreshold =
                new ScanResult(mDevice, 0, 0, 0, 0, 0, rssiAboveThreshold, 0, mockScanRecord, 0);
        assertThat(mScanController.matchesFilters(client, resultAboveThreshold)).isTrue();

        ScanResult resultBelowThreshold =
                new ScanResult(mDevice, 0, 0, 0, 0, 0, rssiBelowThreshold, 0, mockScanRecord, 0);
        assertThat(mScanController.matchesFilters(client, resultBelowThreshold)).isFalse();
    }

    @Test
    @EnableFlags(Flags.FLAG_ORIGINAL_ADDRESS_FILTER_MATCH)
    public void matchesFilters_originalAddress() {
        // This address is different from mDevice.getAddress()
        String originalAddress = "00:11:22:33:CC:DD";
        ScanFilter filter = new ScanFilter.Builder().setDeviceAddress(originalAddress).build();
        List<ScanFilter> filterList = new ArrayList<>();
        filterList.add(filter);
        ScanSettings settings = new ScanSettings.Builder().build();
        ScanRecord mockScanRecord = mock(ScanRecord.class);

        final int appUid = 1234;
        ScanClient client = new ScanClient(TEST_SCANNER_ID, settings, filterList, appUid);
        ScanResult scanResult = new ScanResult(mDevice, 0, 0, 0, 0, 0, 0, 0, mockScanRecord, 0);

        assertThat(mScanController.matchesFilters(client, scanResult, originalAddress)).isTrue();
    }

    @Test
    public void dump_doesNotCrash() {
        StringBuilder sb = new StringBuilder();
        mScanController.dump(sb);
        assertThat(sb.toString()).isNotNull();
    }
}
