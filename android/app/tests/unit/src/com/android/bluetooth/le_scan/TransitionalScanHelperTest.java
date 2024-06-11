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

package com.android.bluetooth.le_scan;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.IPeriodicAdvertisingCallback;
import android.bluetooth.le.IScannerCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.AttributionSource;
import android.content.Context;
import android.content.res.Resources;
import android.location.LocationManager;
import android.os.Binder;
import android.os.RemoteException;
import android.os.WorkSource;
import android.os.test.TestLooper;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.rule.ServiceTestRule;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestUtils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.CompanionManager;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.gatt.ContextMap;
import com.android.bluetooth.gatt.GattNativeInterface;
import com.android.bluetooth.gatt.GattObjectsFactory;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Test cases for {@link TransitionalScanHelper}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class TransitionalScanHelperTest {

    private static final String REMOTE_DEVICE_ADDRESS = "00:00:00:00:00:00";

    private TransitionalScanHelper mScanHelper;
    @Mock private TransitionalScanHelper.ScannerMap mScannerMap;

    @Mock private ContextMap.App mApp;

    @Mock private TransitionalScanHelper.PendingIntentInfo mPiInfo;
    @Mock private PeriodicScanManager mPeriodicScanManager;
    @Mock private ScanManager mScanManager;

    @Rule public final ServiceTestRule mServiceRule = new ServiceTestRule();
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private BluetoothDevice mDevice;
    private BluetoothAdapter mAdapter;
    private AttributionSource mAttributionSource;

    @Mock private Resources mResources;
    @Mock private AdapterService mAdapterService;
    @Mock private GattObjectsFactory mGattObjectsFactory;
    @Mock private ScanObjectsFactory mScanObjectsFactory;
    @Mock private GattNativeInterface mNativeInterface;
    private CompanionManager mBtCompanionManager;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        TestUtils.setAdapterService(mAdapterService);

        GattObjectsFactory.setInstanceForTesting(mGattObjectsFactory);
        ScanObjectsFactory.setInstanceForTesting(mScanObjectsFactory);
        doReturn(mNativeInterface).when(mGattObjectsFactory).getNativeInterface();
        doReturn(mScanManager)
                .when(mScanObjectsFactory)
                .createScanManager(any(), any(), any(), any(), any());
        doReturn(mPeriodicScanManager).when(mScanObjectsFactory).createPeriodicScanManager(any());

        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mAttributionSource = mAdapter.getAttributionSource();
        mDevice = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(REMOTE_DEVICE_ADDRESS);

        when(mAdapterService.getResources()).thenReturn(mResources);
        when(mResources.getInteger(anyInt())).thenReturn(0);
        when(mAdapterService.getSharedPreferences(anyString(), anyInt()))
                .thenReturn(
                        InstrumentationRegistry.getTargetContext()
                                .getSharedPreferences(
                                        "GattServiceTestPrefs", Context.MODE_PRIVATE));

        TestUtils.mockGetSystemService(
                mAdapterService, Context.LOCATION_SERVICE, LocationManager.class);

        mBtCompanionManager = new CompanionManager(mAdapterService, null);
        doReturn(mBtCompanionManager).when(mAdapterService).getCompanionManager();

        TestLooper testLooper = new TestLooper();
        testLooper.startAutoDispatch();

        mScanHelper =
                new TransitionalScanHelper(InstrumentationRegistry.getTargetContext(), () -> false);
        mScanHelper.start(testLooper.getLooper());

        mScanHelper.setScannerMap(mScannerMap);
    }

    @After
    public void tearDown() throws Exception {
        mScanHelper.stop();
        mScanHelper = null;

        TestUtils.clearAdapterService(mAdapterService);
        GattObjectsFactory.setInstanceForTesting(null);
        ScanObjectsFactory.setInstanceForTesting(null);
    }

    @Test
    public void testParseBatchTimestamp() {
        long timestampNanos = mScanHelper.parseTimestampNanos(new byte[] {-54, 7});
        assertThat(timestampNanos).isEqualTo(99700000000L);
    }

    @Test
    public void continuePiStartScan() {
        int scannerId = 1;

        mPiInfo.settings = new ScanSettings.Builder().build();
        mApp.info = mPiInfo;

        AppScanStats appScanStats = mock(AppScanStats.class);
        doReturn(appScanStats).when(mScannerMap).getAppScanStatsById(scannerId);

        mScanHelper.continuePiStartScan(scannerId, mApp);

        verify(appScanStats)
                .recordScanStart(mPiInfo.settings, mPiInfo.filters, false, false, scannerId);
        verify(mScanManager).startScan(any());
    }

    @Test
    public void continuePiStartScanCheckUid() {
        int scannerId = 1;

        mPiInfo.settings = new ScanSettings.Builder().build();
        mPiInfo.callingUid = 123;
        mApp.info = mPiInfo;

        AppScanStats appScanStats = mock(AppScanStats.class);
        doReturn(appScanStats).when(mScannerMap).getAppScanStatsById(scannerId);

        mScanHelper.continuePiStartScan(scannerId, mApp);

        verify(appScanStats)
                .recordScanStart(mPiInfo.settings, mPiInfo.filters, false, false, scannerId);
        verify(mScanManager)
                .startScan(
                        argThat(
                                new ArgumentMatcher<ScanClient>() {
                                    @Override
                                    public boolean matches(ScanClient client) {
                                        return mPiInfo.callingUid == client.appUid;
                                    }
                                }));
    }

    @Test
    public void onBatchScanReportsInternal_deliverBatchScan() throws RemoteException {
        int status = 1;
        int scannerId = 2;
        int reportType = ScanManager.SCAN_RESULT_TYPE_FULL;
        int numRecords = 1;
        byte[] recordData =
                new byte[] {
                    0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x00, 0x00, 0x00, 0x00
                };

        Set<ScanClient> scanClientSet = new HashSet<>();
        ScanClient scanClient = new ScanClient(scannerId);
        scanClient.associatedDevices = new ArrayList<>();
        scanClient.associatedDevices.add("02:00:00:00:00:00");
        scanClient.scannerId = scannerId;
        scanClientSet.add(scanClient);
        doReturn(scanClientSet).when(mScanManager).getFullBatchScanQueue();
        doReturn(mApp).when(mScannerMap).getById(scanClient.scannerId);

        mScanHelper.onBatchScanReportsInternal(
                status, scannerId, reportType, numRecords, recordData);
        verify(mScanManager).callbackDone(scannerId, status);

        reportType = ScanManager.SCAN_RESULT_TYPE_TRUNCATED;
        recordData =
                new byte[] {
                    0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0x06, 0x04, 0x02, 0x02, 0x00, 0x00, 0x02
                };
        doReturn(scanClientSet).when(mScanManager).getBatchScanQueue();
        IScannerCallback callback = mock(IScannerCallback.class);
        mApp.callback = callback;

        mScanHelper.onBatchScanReportsInternal(
                status, scannerId, reportType, numRecords, recordData);
        verify(callback).onBatchScanResults(any());
    }

    @Test
    public void enforceReportDelayFloor() {
        long reportDelayFloorHigher = TransitionalScanHelper.DEFAULT_REPORT_DELAY_FLOOR + 1;
        ScanSettings scanSettings =
                new ScanSettings.Builder().setReportDelay(reportDelayFloorHigher).build();

        ScanSettings newScanSettings = mScanHelper.enforceReportDelayFloor(scanSettings);

        assertThat(newScanSettings.getReportDelayMillis())
                .isEqualTo(scanSettings.getReportDelayMillis());

        ScanSettings scanSettingsFloor = new ScanSettings.Builder().setReportDelay(1).build();

        ScanSettings newScanSettingsFloor = mScanHelper.enforceReportDelayFloor(scanSettingsFloor);

        assertThat(newScanSettingsFloor.getReportDelayMillis())
                .isEqualTo(TransitionalScanHelper.DEFAULT_REPORT_DELAY_FLOOR);
    }

    @Test
    public void registerScanner() throws Exception {
        IScannerCallback callback = mock(IScannerCallback.class);
        WorkSource workSource = mock(WorkSource.class);

        AppScanStats appScanStats = mock(AppScanStats.class);
        doReturn(appScanStats).when(mScannerMap).getAppScanStatsByUid(Binder.getCallingUid());

        mScanHelper.registerScanner(callback, workSource, mAttributionSource);
        verify(mScannerMap)
                .add(any(), eq(workSource), eq(callback), eq(null), any(), eq(mScanHelper));
        verify(mScanManager).registerScanner(any());
    }

    @Test
    public void flushPendingBatchResults() {
        int scannerId = 3;

        mScanHelper.flushPendingBatchResults(scannerId, mAttributionSource);
        verify(mScanManager).flushBatchScanResults(new ScanClient(scannerId));
    }

    @Test
    public void onScanResult_remoteException_clientDied() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_LE_SCAN_FIX_REMOTE_EXCEPTION);
        int scannerId = 1;

        int eventType = 0;
        int addressType = 0;
        String address = "02:00:00:00:00:00";
        int primaryPhy = 0;
        int secondPhy = 0;
        int advertisingSid = 0;
        int txPower = 0;
        int rssi = 0;
        int periodicAdvInt = 0;
        byte[] advData = new byte[0];

        ScanClient scanClient = new ScanClient(scannerId);
        scanClient.scannerId = scannerId;
        scanClient.hasNetworkSettingsPermission = true;
        scanClient.settings =
                new ScanSettings.Builder()
                        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                        .setLegacy(false)
                        .build();

        AppScanStats appScanStats = mock(AppScanStats.class);
        IScannerCallback callback = mock(IScannerCallback.class);

        mApp.callback = callback;
        mApp.appScanStats = appScanStats;
        Set<ScanClient> scanClientSet = Collections.singleton(scanClient);

        doReturn(address).when(mAdapterService).getIdentityAddress(anyString());
        doReturn(scanClientSet).when(mScanManager).getRegularScanQueue();
        doReturn(mApp).when(mScannerMap).getById(scanClient.scannerId);
        doReturn(appScanStats).when(mScannerMap).getAppScanStatsById(scanClient.scannerId);

        // Simulate remote client crash
        doThrow(new RemoteException()).when(callback).onScanResult(any());

        mScanHelper.onScanResult(
                eventType,
                addressType,
                address,
                primaryPhy,
                secondPhy,
                advertisingSid,
                txPower,
                rssi,
                periodicAdvInt,
                advData,
                address);

        assertThat(scanClient.appDied).isTrue();
        verify(appScanStats).recordScanStop(scannerId);
    }

    @Test
    public void registerSync() {
        ScanResult scanResult = new ScanResult(mDevice, 1, 2, 3, 4, 5, 6, 7, null, 8);
        int skip = 1;
        int timeout = 2;
        IPeriodicAdvertisingCallback callback = mock(IPeriodicAdvertisingCallback.class);

        mScanHelper.registerSync(scanResult, skip, timeout, callback, mAttributionSource);
        verify(mPeriodicScanManager).startSync(scanResult, skip, timeout, callback);
    }

    @Test
    public void transferSync() {
        int serviceData = 1;
        int syncHandle = 2;

        mScanHelper.transferSync(mDevice, serviceData, syncHandle, mAttributionSource);
        verify(mPeriodicScanManager).transferSync(mDevice, serviceData, syncHandle);
    }

    @Test
    public void transferSetInfo() {
        int serviceData = 1;
        int advHandle = 2;
        IPeriodicAdvertisingCallback callback = mock(IPeriodicAdvertisingCallback.class);

        mScanHelper.transferSetInfo(mDevice, serviceData, advHandle, callback, mAttributionSource);
        verify(mPeriodicScanManager).transferSetInfo(mDevice, serviceData, advHandle, callback);
    }

    @Test
    public void unregisterSync() {
        IPeriodicAdvertisingCallback callback = mock(IPeriodicAdvertisingCallback.class);

        mScanHelper.unregisterSync(callback, mAttributionSource);
        verify(mPeriodicScanManager).stopSync(callback);
    }

    @Test
    public void getCurrentUsedTrackingAdvertisement() {
        mScanHelper.getCurrentUsedTrackingAdvertisement();
        verify(mScanManager).getCurrentUsedTrackingAdvertisement();
    }

    @Test
    public void cleanUp_doesNotCrash() {
        mScanHelper.cleanup();
    }

    @Test
    public void profileConnectionStateChanged_notifyScanManager() {
        mScanHelper.notifyProfileConnectionStateChange(
                BluetoothProfile.A2DP,
                BluetoothProfile.STATE_CONNECTING,
                BluetoothProfile.STATE_CONNECTED);
        verify(mScanManager)
                .handleBluetoothProfileConnectionStateChanged(
                        BluetoothProfile.A2DP,
                        BluetoothProfile.STATE_CONNECTING,
                        BluetoothProfile.STATE_CONNECTED);
    }

    @Test
    @EnableFlags(Flags.FLAG_LE_SCAN_USE_ADDRESS_TYPE)
    public void onTrackAdvFoundLost() throws Exception {
        int scannerId = 1;
        int advPktLen = 1;
        byte[] advPkt = new byte[] {0x02};
        int scanRspLen = 3;
        byte[] scanRsp = new byte[] {0x04};
        int filtIndex = 5;

        int advState = TransitionalScanHelper.ADVT_STATE_ONFOUND;
        int advInfoPresent = 7;
        String address = "00:11:22:33:FF:EE";
        int addrType = BluetoothDevice.ADDRESS_TYPE_RANDOM;
        int txPower = 9;
        int rssiValue = 10;
        int timeStamp = 11;

        ScanClient scanClient = new ScanClient(scannerId);
        scanClient.hasNetworkSettingsPermission = true;
        scanClient.settings =
                new ScanSettings.Builder()
                        .setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
                        .setLegacy(false)
                        .build();
        Set<ScanClient> scanClientSet = Collections.singleton(scanClient);

        ContextMap.App app = mock(ContextMap.App.class);
        IScannerCallback callback = mock(IScannerCallback.class);

        app.callback = callback;
        app.info = mock(TransitionalScanHelper.PendingIntentInfo.class);

        doReturn(app).when(mScannerMap).getById(scannerId);
        doReturn(scanClientSet).when(mScanManager).getRegularScanQueue();

        AdvtFilterOnFoundOnLostInfo advtFilterOnFoundOnLostInfo =
                new AdvtFilterOnFoundOnLostInfo(
                        scannerId,
                        advPktLen,
                        advPkt,
                        scanRspLen,
                        scanRsp,
                        filtIndex,
                        advState,
                        advInfoPresent,
                        address,
                        addrType,
                        txPower,
                        rssiValue,
                        timeStamp);

        mScanHelper.onTrackAdvFoundLost(advtFilterOnFoundOnLostInfo);
        ArgumentCaptor<ScanResult> result = ArgumentCaptor.forClass(ScanResult.class);
        verify(callback).onFoundOrLost(eq(true), result.capture());
        assertThat(result.getValue().getDevice()).isNotNull();
        assertThat(result.getValue().getDevice().getAddress()).isEqualTo(address);
        assertThat(result.getValue().getDevice().getAddressType()).isEqualTo(addrType);
    }
}
