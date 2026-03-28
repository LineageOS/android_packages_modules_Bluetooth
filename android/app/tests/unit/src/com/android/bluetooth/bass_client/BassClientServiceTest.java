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

package com.android.bluetooth.bass_client;

import static android.bluetooth.BluetoothDevice.ADDRESS_TYPE_PUBLIC;
import static android.bluetooth.BluetoothDevice.ADDRESS_TYPE_RANDOM;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
import static android.bluetooth.BluetoothProfile.EXTRA_PREVIOUS_STATE;
import static android.bluetooth.BluetoothProfile.EXTRA_STATE;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;

import static com.android.bluetooth.TestUtils.getRealDevice;
import static com.android.bluetooth.TestUtils.getTestDevice;
import static com.android.bluetooth.TestUtils.mockGetSystemService;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.BroadcastOptions;
import android.app.Notification;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothLeAudio;
import android.bluetooth.BluetoothLeAudioCodecConfigMetadata;
import android.bluetooth.BluetoothLeAudioContentMetadata;
import android.bluetooth.BluetoothLeBroadcastAssistant;
import android.bluetooth.BluetoothLeBroadcastChannel;
import android.bluetooth.BluetoothLeBroadcastMetadata;
import android.bluetooth.BluetoothLeBroadcastReceiveState;
import android.bluetooth.BluetoothLeBroadcastSubgroup;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.IBluetoothLeBroadcastAssistantCallback;
import android.bluetooth.le.IScannerCallback;
import android.bluetooth.le.PeriodicAdvertisingReport;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;

import com.android.bluetooth.TestLooper;
import com.android.bluetooth.auracast.AuracastUtils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.RemoteDevices;
import com.android.bluetooth.csip.CsipSetCoordinatorService;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.le_audio.LeAudioConstants;
import com.android.bluetooth.le_audio.LeAudioService;
import com.android.bluetooth.le_audio.LeAudioStackEvent;
import com.android.bluetooth.le_scan.ScanController;
import com.android.bluetooth.mcp.McpService;
import com.android.tests.bluetooth.MockitoRule;

import com.google.common.truth.Expect;

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
import org.mockito.Spy;
import org.mockito.hamcrest.MockitoHamcrest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Test cases for {@link BassClientService}. */
@MediumTest
@RunWith(AndroidJUnit4.class)
public class BassClientServiceTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();
    @Rule public Expect expect = Expect.create();
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Spy private BassObjectsFactory mObjectsFactory = BassObjectsFactory.getInstance();
    @Mock private BluetoothManager mBluetoothManager;
    @Mock private BluetoothAdapter mAdapter;
    @Mock private AdapterService mAdapterService;
    @Mock private ScanController mScanController;
    @Mock private CsipSetCoordinatorService mCsipService;
    @Mock private LeAudioService mLeAudioService;
    @Mock private McpService mMcpService;
    @Mock private IBluetoothLeBroadcastAssistantCallback mCallback;
    @Mock private Binder mBinder;

    private static final ParcelUuid[] FAKE_SERVICE_UUIDS = {BluetoothUuid.BASS};

    private static final int TEST_BROADCAST_ID = 42;
    private static final int TEST_BROADCAST_ID_2 = TEST_BROADCAST_ID + 1;
    private static final int TEST_ADVERTISER_SID = 1234;
    private static final int TEST_PA_SYNC_INTERVAL = 100;
    private static final int TEST_PRESENTATION_DELAY_MS = 345;
    private static final int TEST_RSSI = -40;

    private static final int TEST_SYNC_HANDLE = 0;
    private static final int TEST_SYNC_HANDLE_2 = TEST_SYNC_HANDLE + 1;

    private static final int TEST_CODEC_ID = 42;

    // For BluetoothLeAudioCodecConfigMetadata
    private static final long TEST_AUDIO_LOCATION_FRONT_LEFT = 0x01;
    private static final long TEST_AUDIO_LOCATION_FRONT_RIGHT = 0x02;

    // For BluetoothLeAudioContentMetadata
    private static final String TEST_PROGRAM_INFO = "Test";
    // German language code in ISO 639-3
    private static final String TEST_LANGUAGE = "deu";
    private static final int TEST_SOURCE_ID = 10;
    private static final int TEST_NUM_SOURCES = 1;

    private final Map<BluetoothDevice, BassClientStateMachine> mStateMachines =
            new LinkedHashMap<>();

    private final BluetoothDevice mCurrentDevice = getTestDevice(0);
    private final BluetoothDevice mCurrentDevice1 = getTestDevice(1);

    private final BluetoothDevice mSourceDevice =
            getRealDevice("00:11:22:33:44:55", ADDRESS_TYPE_RANDOM);
    private final BluetoothDevice mSourceDevice2 =
            getRealDevice("00:11:22:33:44:66", ADDRESS_TYPE_RANDOM);
    private final BluetoothLeBroadcastMetadata mBroadcastMetadata1 =
            createBroadcastMetadata(TEST_BROADCAST_ID);
    private final BluetoothLeBroadcastMetadata mBroadcastMetadata2 =
            createBroadcastMetadata(TEST_BROADCAST_ID_2);
    private final BluetoothLeBroadcastMetadata mBroadcastMetadataNoPreference =
            createBroadcastMetadataBisNotSelected(TEST_BROADCAST_ID);

    private BassClientService mBassClientService;
    private ArgumentCaptor<IScannerCallback> mBassScanCallbackCaptor;
    private TestLooper mLooper;

    private InOrder mInOrderAdapterService;
    private InOrder mInOrderScanController;

    BluetoothLeBroadcastSubgroup createBroadcastSubgroup() {
        BluetoothLeAudioCodecConfigMetadata codecMetadata =
                new BluetoothLeAudioCodecConfigMetadata.Builder()
                        .setAudioLocation(TEST_AUDIO_LOCATION_FRONT_LEFT)
                        .build();
        BluetoothLeAudioContentMetadata contentMetadata =
                new BluetoothLeAudioContentMetadata.Builder()
                        .setProgramInfo(TEST_PROGRAM_INFO)
                        .setLanguage(TEST_LANGUAGE)
                        .build();
        BluetoothLeBroadcastSubgroup.Builder builder =
                new BluetoothLeBroadcastSubgroup.Builder()
                        .setCodecId(TEST_CODEC_ID)
                        .setCodecSpecificConfig(codecMetadata)
                        .setContentMetadata(contentMetadata);

        BluetoothLeAudioCodecConfigMetadata channelCodecMetadataLeft =
                new BluetoothLeAudioCodecConfigMetadata.Builder()
                        .setAudioLocation(TEST_AUDIO_LOCATION_FRONT_LEFT)
                        .build();

        BluetoothLeAudioCodecConfigMetadata channelCodecMetadataRight =
                new BluetoothLeAudioCodecConfigMetadata.Builder()
                        .setAudioLocation(TEST_AUDIO_LOCATION_FRONT_RIGHT)
                        .build();

        // Make two channels
        BluetoothLeBroadcastChannel channel1 =
                new BluetoothLeBroadcastChannel.Builder()
                        .setSelected(true)
                        .setChannelIndex(1)
                        .setCodecMetadata(channelCodecMetadataLeft)
                        .build();
        builder.addChannel(channel1);
        BluetoothLeBroadcastChannel channel2 =
                new BluetoothLeBroadcastChannel.Builder()
                        .setSelected(true)
                        .setChannelIndex(2)
                        .setCodecMetadata(channelCodecMetadataRight)
                        .build();
        builder.addChannel(channel2);
        return builder.build();
    }

    BluetoothLeBroadcastSubgroup createBroadcastSubgroupBisNotSelected() {
        BluetoothLeAudioCodecConfigMetadata codecMetadata =
                new BluetoothLeAudioCodecConfigMetadata.Builder()
                        .setAudioLocation(TEST_AUDIO_LOCATION_FRONT_LEFT)
                        .build();
        BluetoothLeAudioContentMetadata contentMetadata =
                new BluetoothLeAudioContentMetadata.Builder()
                        .setProgramInfo(TEST_PROGRAM_INFO)
                        .setLanguage(TEST_LANGUAGE)
                        .build();
        BluetoothLeBroadcastSubgroup.Builder builder =
                new BluetoothLeBroadcastSubgroup.Builder()
                        .setCodecId(TEST_CODEC_ID)
                        .setCodecSpecificConfig(codecMetadata)
                        .setContentMetadata(contentMetadata);

        BluetoothLeAudioCodecConfigMetadata channelCodecMetadataLeft =
                new BluetoothLeAudioCodecConfigMetadata.Builder()
                        .setAudioLocation(TEST_AUDIO_LOCATION_FRONT_LEFT)
                        .build();

        BluetoothLeAudioCodecConfigMetadata channelCodecMetadataRight =
                new BluetoothLeAudioCodecConfigMetadata.Builder()
                        .setAudioLocation(TEST_AUDIO_LOCATION_FRONT_RIGHT)
                        .build();

        // builder expect at least one channel
        BluetoothLeBroadcastChannel channel1 =
                new BluetoothLeBroadcastChannel.Builder()
                        .setSelected(false)
                        .setChannelIndex(1)
                        .setCodecMetadata(channelCodecMetadataLeft)
                        .build();
        builder.addChannel(channel1);
        BluetoothLeBroadcastChannel channel2 =
                new BluetoothLeBroadcastChannel.Builder()
                        .setSelected(false)
                        .setChannelIndex(2)
                        .setCodecMetadata(channelCodecMetadataRight)
                        .build();
        builder.addChannel(channel2);
        return builder.build();
    }

    BluetoothLeBroadcastMetadata createBroadcastMetadata(int broadcastId) {
        BluetoothLeBroadcastMetadata.Builder builder =
                new BluetoothLeBroadcastMetadata.Builder()
                        .setEncrypted(false)
                        .setSourceDevice(mSourceDevice, ADDRESS_TYPE_RANDOM)
                        .setSourceAdvertisingSid(TEST_ADVERTISER_SID)
                        .setBroadcastId(broadcastId)
                        .setBroadcastCode(null)
                        .setPaSyncInterval(TEST_PA_SYNC_INTERVAL)
                        .setPresentationDelayMicros(TEST_PRESENTATION_DELAY_MS)
                        .setPublicBroadcast(true)
                        .setAudioConfigQuality(
                                BluetoothLeBroadcastMetadata.AUDIO_CONFIG_QUALITY_HIGH)
                        .setPublicBroadcastMetadata(
                                BluetoothLeAudioContentMetadata.fromRawBytes(
                                        new byte[] {0x02, 0x08, 0x01}))
                        .addSubgroup(createBroadcastSubgroup());
        return builder.build();
    }

    BluetoothLeBroadcastMetadata createBroadcastMetadata(int broadcastId, BluetoothDevice device) {
        BluetoothLeBroadcastMetadata.Builder builder =
                new BluetoothLeBroadcastMetadata.Builder()
                        .setEncrypted(false)
                        .setSourceDevice(device, ADDRESS_TYPE_RANDOM)
                        .setSourceAdvertisingSid(TEST_ADVERTISER_SID)
                        .setBroadcastId(broadcastId)
                        .setBroadcastCode(null)
                        .setPaSyncInterval(TEST_PA_SYNC_INTERVAL)
                        .setPresentationDelayMicros(TEST_PRESENTATION_DELAY_MS)
                        .setPublicBroadcast(true)
                        .setAudioConfigQuality(
                                BluetoothLeBroadcastMetadata.AUDIO_CONFIG_QUALITY_HIGH)
                        .setPublicBroadcastMetadata(
                                BluetoothLeAudioContentMetadata.fromRawBytes(
                                        new byte[] {0x02, 0x08, 0x01}))
                        .addSubgroup(createBroadcastSubgroup());
        return builder.build();
    }

    BluetoothLeBroadcastMetadata createBroadcastMetadataBisNotSelected(int broadcastId) {
        BluetoothLeBroadcastMetadata.Builder builder =
                new BluetoothLeBroadcastMetadata.Builder()
                        .setEncrypted(false)
                        .setSourceDevice(mSourceDevice, ADDRESS_TYPE_RANDOM)
                        .setSourceAdvertisingSid(TEST_ADVERTISER_SID)
                        .setBroadcastId(broadcastId)
                        .setBroadcastCode(null)
                        .setPaSyncInterval(TEST_PA_SYNC_INTERVAL)
                        .setPresentationDelayMicros(TEST_PRESENTATION_DELAY_MS)
                        .setPublicBroadcast(true)
                        .setAudioConfigQuality(
                                BluetoothLeBroadcastMetadata.AUDIO_CONFIG_QUALITY_HIGH)
                        .setPublicBroadcastMetadata(
                                BluetoothLeAudioContentMetadata.fromRawBytes(
                                        new byte[] {0x02, 0x08, 0x01}))
                        .addSubgroup(createBroadcastSubgroupBisNotSelected());
        return builder.build();
    }

    BluetoothLeBroadcastMetadata createEmptyBroadcastMetadata() {
        BluetoothLeBroadcastMetadata.Builder builder =
                new BluetoothLeBroadcastMetadata.Builder()
                        .setEncrypted(false)
                        .setSourceDevice(
                                getRealDevice("00:00:00:00:00:00", ADDRESS_TYPE_RANDOM),
                                ADDRESS_TYPE_RANDOM)
                        .setSourceAdvertisingSid(TEST_ADVERTISER_SID)
                        .setBroadcastId(0)
                        .setBroadcastCode(null)
                        .setPaSyncInterval(TEST_PA_SYNC_INTERVAL)
                        .setPresentationDelayMicros(TEST_PRESENTATION_DELAY_MS)
                        .addSubgroup(createBroadcastSubgroup());
        return builder.build();
    }

    private void verifyRegisterSyncCalled(BluetoothDevice device) {
        mInOrderScanController
                .verify(mScanController)
                .registerSync(eq(device), anyInt(), anyInt(), anyInt(), any());

        if (Flags.leaudioBroadcastAlwaysUseBackgroundScanner()) {
            // Scanner always should be enabled when sync is registered!
            assertThat(mBassClientService.isAnySearchInProgress()).isTrue();
        }
    }

    private void verifyRegisterSyncNeverCalled() {
        mInOrderScanController
                .verify(mScanController, never())
                .registerSync(any(), anyInt(), anyInt(), anyInt(), any());
    }

    private void verifyUnregisterSyncCalled() {
        mInOrderScanController.verify(mScanController).unregisterSync(any());
    }

    private void verifyUnregisterSyncNeverCalled() {
        mInOrderScanController.verify(mScanController, never()).unregisterSync(any());
    }

    private void verifyBackgroundSearchStarted() {
        if (!Flags.leaudioBroadcastAlwaysUseBackgroundScanner()) {
            return;
        }

        mInOrderScanController
                .verify(mScanController)
                .registerAndStartScanInternal(any(), any(), any(), any());
    }

    @Before
    public void setUp() throws Exception {
        mInOrderAdapterService = inOrder(mAdapterService);
        mInOrderScanController = inOrder(mScanController);

        BassObjectsFactory.setInstanceForTesting(mObjectsFactory);

        doReturn(mAdapterService).when(mAdapterService).getBaseContext();
        doReturn(new ParcelUuid[] {BluetoothUuid.BASS})
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));

        doReturn(FAKE_SERVICE_UUIDS)
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
        doReturn(BluetoothDevice.BOND_BONDED)
                .when(mAdapterService)
                .getBondState(any(BluetoothDevice.class));
        doAnswer(invocation -> mStateMachines.keySet()).when(mAdapterService).getBondedDevices();
        mockGetSystemService(mAdapterService, BluetoothManager.class, mBluetoothManager);
        doReturn(mAdapter).when(mBluetoothManager).getAdapter();
        doAnswer(
                        invocation -> {
                            Runnable runnable = invocation.getArgument(0);
                            runnable.run();
                            return null;
                        })
                .when(mScanController)
                .doOnScanThread(any(Runnable.class));

        doAnswer(
                        invocation -> {
                            assertThat(mCurrentDevice).isNotNull();
                            final BassClientStateMachine stateMachine =
                                    mock(BassClientStateMachine.class);
                            doReturn(new ArrayList<>()).when(stateMachine).getAllSources();
                            doReturn(TEST_NUM_SOURCES)
                                    .when(stateMachine)
                                    .getMaximumSourceCapacity();
                            doReturn((BluetoothDevice) invocation.getArgument(0))
                                    .when(stateMachine)
                                    .getDevice();
                            doReturn(true).when(stateMachine).isBassStateReady();
                            doReturn(LeAudioConstants.INVALID_BROADCAST_ID)
                                    .when(stateMachine)
                                    .getPendingOperationBroadcastId();
                            mStateMachines.put(
                                    (BluetoothDevice) invocation.getArgument(0), stateMachine);
                            doAnswer(
                                            inv -> {
                                                return Message.obtain(
                                                        (Handler) null, (int) inv.getArgument(0));
                                            })
                                    .when(stateMachine)
                                    .obtainMessage(anyInt());
                            return stateMachine;
                        })
                .when(mObjectsFactory)
                .makeStateMachine(any(), any(), any(), any(), any());

        mLooper = new TestLooper();

        mBassClientService =
                new BassClientService(mAdapterService, mScanController, mLooper.getLooper());
        mBassClientService.setAvailable(true);

        doReturn(Optional.of(mCsipService)).when(mAdapterService).getCsipSetCoordinatorService();
        doReturn(Optional.of(mLeAudioService)).when(mAdapterService).getLeAudioService();
        doReturn(Optional.of(mMcpService)).when(mAdapterService).getMcpService();

        mBassScanCallbackCaptor = ArgumentCaptor.forClass(IScannerCallback.class);
        doAnswer(
                        invocation -> {
                            try {
                                int scannerId = 1;
                                mBassScanCallbackCaptor
                                        .getValue()
                                        .onScannerRegistered(0, scannerId);
                            } catch (RemoteException e) {
                                // the mocked onScannerRegistered doesn't throw RemoteException
                            }
                            return null;
                        })
                .when(mScanController)
                .registerAndStartScanInternal(
                        mBassScanCallbackCaptor.capture(), any(), any(), any());

        doReturn(mBinder).when(mCallback).asBinder();
        mBassClientService.registerCallback(mCallback);

        assertThat(mBassClientService.mEncryptionStateReceiver).isNotNull();
    }

    @After
    public void tearDown() throws Exception {
        mBassClientService.unregisterCallback(mCallback);

        mBassClientService.cleanup();
        mStateMachines.clear();
        BassObjectsFactory.setInstanceForTesting(null);
    }

    /** Test to verify that BassClientService can be successfully started */
    @Test
    public void testGetBassClientService() {
        // Verify default connection and audio states
        assertThat(mBassClientService.getConnectionState(mCurrentDevice))
                .isEqualTo(STATE_DISCONNECTED);
    }

    /** Test if getProfileConnectionPolicy works after the service is stopped. */
    @Test
    public void testGetPolicyAfterStopped() {
        mBassClientService.cleanup();
        doReturn(CONNECTION_POLICY_UNKNOWN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(
                        mCurrentDevice, BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT);
        assertThat(mBassClientService.getConnectionPolicy(mCurrentDevice))
                .isEqualTo(CONNECTION_POLICY_UNKNOWN);
    }

    /**
     * Test connecting to a test device. - service.connect() should return false -
     * bassClientStateMachine.sendMessage(CONNECT) should be called.
     */
    @Test
    public void testConnect() {
        doReturn(CONNECTION_POLICY_ALLOWED)
                .when(mAdapterService)
                .getProfileConnectionPolicy(
                        any(BluetoothDevice.class),
                        eq(BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT));

        assertThat(mBassClientService.connect(mCurrentDevice)).isTrue();
        verify(mObjectsFactory)
                .makeStateMachine(
                        eq(mCurrentDevice),
                        eq(mBassClientService),
                        eq(mAdapterService),
                        eq(mScanController),
                        any());
        BassClientStateMachine stateMachine = mStateMachines.get(mCurrentDevice);
        assertThat(stateMachine).isNotNull();
        verify(stateMachine).sendMessage(BassClientStateMachine.CONNECT);
    }

    /** Test connecting to a null device. - service.connect() should return false. */
    @Test
    public void testConnect_nullDevice() {
        assertThrows(NullPointerException.class, () -> mBassClientService.connect(null));
    }

    @Test
    public void testConnect_isQuietMode() {
        doReturn(BluetoothDevice.BOND_BONDED).when(mAdapterService).getBondState(any());
        doReturn(CONNECTION_POLICY_ALLOWED)
                .when(mAdapterService)
                .getProfileConnectionPolicy(
                        any(BluetoothDevice.class),
                        eq(BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT));

        doReturn(true).when(mAdapterService).isQuietModeEnabled();
        assertThat(mBassClientService.connect(mCurrentDevice)).isFalse();

        doReturn(false).when(mAdapterService).isQuietModeEnabled();
        assertThat(mBassClientService.connect(mCurrentDevice)).isTrue();
    }

    @Test
    public void testConnect_notBonded_bonding_bonded() {
        doReturn(CONNECTION_POLICY_ALLOWED)
                .when(mAdapterService)
                .getProfileConnectionPolicy(
                        any(BluetoothDevice.class),
                        eq(BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT));

        doReturn(BluetoothDevice.BOND_NONE).when(mAdapterService).getBondState(any());
        assertThat(mBassClientService.connect(mCurrentDevice)).isFalse();

        doReturn(BluetoothDevice.BOND_BONDING).when(mAdapterService).getBondState(any());
        assertThat(mBassClientService.connect(mCurrentDevice)).isFalse();

        doReturn(BluetoothDevice.BOND_BONDED).when(mAdapterService).getBondState(any());
        assertThat(mBassClientService.connect(mCurrentDevice)).isTrue();
    }

    /**
     * Test connecting to a device when the connection policy is forbidden. - service.connect()
     * should return false.
     */
    @Test
    public void testConnect_whenConnectionPolicyIsForbidden() {
        doReturn(CONNECTION_POLICY_FORBIDDEN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(
                        any(BluetoothDevice.class),
                        eq(BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT));
        assertThat(mCurrentDevice).isNotNull();

        assertThat(mBassClientService.connect(mCurrentDevice)).isFalse();
    }

    /** Test connecting to a device without UUID. - service.connect() should return false. */
    @Test
    public void testConnectToDevice_whenUuidIsMissing_returnFalse() {
        // Return No UUID
        doReturn(new ParcelUuid[] {})
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));

        assertThat(mBassClientService.connect(mCurrentDevice)).isFalse();
    }

    /** Test service.startSearchingForSources() calls mScanController.registerScannerInternal() */
    @Test
    public void testStartSearchingForSources() {
        prepareConnectedDeviceGroup();
        List<ScanFilter> scanFilters = new ArrayList<>();

        assertThat(mStateMachines).hasSize(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            clearInvocations(sm);
        }

        assertThat(mBassClientService.isSearchInProgress()).isFalse();
        mBassClientService.startSearchingForSources(scanFilters);
        mInOrderScanController
                .verify(mScanController)
                .registerAndStartScanInternal(any(), any(), any(), any());
        assertThat(mBassClientService.isSearchInProgress()).isTrue();
        for (BassClientStateMachine sm : mStateMachines.values()) {
            verify(sm).sendMessage(BassClientStateMachine.START_SCAN_OFFLOAD);
        }
    }

    private void prepareConnectedDeviceGroup() {
        doReturn(CONNECTION_POLICY_ALLOWED)
                .when(mAdapterService)
                .getProfileConnectionPolicy(
                        any(BluetoothDevice.class),
                        eq(BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT));

        // Mock the CSIP group
        List<BluetoothDevice> groupDevices = new ArrayList<>();
        groupDevices.add(mCurrentDevice);
        groupDevices.add(mCurrentDevice1);
        doReturn(groupDevices)
                .when(mCsipService)
                .getGroupDevicesOrdered(mCurrentDevice, BluetoothUuid.CAP);
        doReturn(groupDevices)
                .when(mCsipService)
                .getGroupDevicesOrdered(mCurrentDevice1, BluetoothUuid.CAP);

        // Prepare connected devices
        assertThat(mBassClientService.connect(mCurrentDevice)).isTrue();
        assertThat(mBassClientService.connect(mCurrentDevice1)).isTrue();

        assertThat(mStateMachines).hasSize(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            // Verify the call
            verify(sm).sendMessage(eq(BassClientStateMachine.CONNECT));

            // Notify the service about the connection event
            BluetoothDevice dev = sm.getDevice();
            doCallRealMethod()
                    .when(sm)
                    .broadcastConnectionState(eq(dev), any(Integer.class), any(Integer.class));
            sm.mService = mBassClientService;
            sm.mDevice = dev;
            sm.broadcastConnectionState(dev, STATE_CONNECTING, STATE_CONNECTED);

            doReturn(STATE_CONNECTED).when(sm).getConnectionState();
            doReturn(true).when(sm).isConnected();
        }

        doReturn(true).when(mLeAudioService).isPrimaryDevice(mCurrentDevice);
        doReturn(false).when(mLeAudioService).isPrimaryDevice(mCurrentDevice1);
        doReturn(Arrays.asList(mCurrentDevice, mCurrentDevice1))
                .when(mLeAudioService)
                .getGroupDevices(mCurrentDevice);
    }

    private void startSearchingForSources() {
        startSearchingForSourcesWithAutoSync(null);
    }

    private void startSearchingForSourcesWithAutoSync(BluetoothDevice device) {
        List<ScanFilter> scanFilters = new ArrayList<>();

        assertThat(mStateMachines).hasSize(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            clearInvocations(sm);
        }

        clearInvocations(mCallback);

        mBassClientService.startSearchingForSources(scanFilters);

        if (device != null) {
            verifyRegisterSyncCalled(device);
        }
        if (!mBassClientService.isAnySearchInProgress()) {
            mInOrderScanController
                    .verify(mScanController)
                    .registerAndStartScanInternal(any(), any(), any(), any());
            for (BassClientStateMachine sm : mStateMachines.values()) {
                verify(sm).sendMessage(BassClientStateMachine.START_SCAN_OFFLOAD);
            }
        }

        mLooper.dispatchAll();
        try {
            verify(mCallback).onSearchStarted(eq(BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST));
        } catch (RemoteException e) {
            // the mocked onSearchStarted doesn't throw RemoteException
        }
    }

    @Test
    public void testStopSearchingForSources() {
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();
        assertThat(mBassClientService.isSearchInProgress()).isTrue();

        // Stop searching
        mBassClientService.stopSearchingForSources();
        mInOrderScanController.verify(mScanController).stopScan(anyInt());

        for (BassClientStateMachine sm : mStateMachines.values()) {
            verify(sm).sendMessage(BassClientStateMachine.STOP_SCAN_OFFLOAD);
        }
        assertThat(mBassClientService.isSearchInProgress()).isFalse();

        // Check if unsyced
        verifyUnregisterSyncCalled();
        expect.that(mBassClientService.getActiveSyncedSources()).isEmpty();
        expect.that(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE)).isNull();
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
    }

    @Test
    public void testStop() {
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();

        // Stop
        mBassClientService.cleanup();
        mInOrderScanController.verify(mScanController).stopScan(anyInt());

        // Check if unsyced
        verifyUnregisterSyncCalled();
        expect.that(mBassClientService.getActiveSyncedSources()).isEmpty();
        expect.that(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE)).isNull();
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
    }

    @Test
    public void testStopSearchingForSources_startAndSyncAgain() {
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();

        // Stop searching
        mBassClientService.stopSearchingForSources();

        // Start searching again
        prepareSyncToSourceAndVerify();
    }

    @Test
    public void testStop_startAndSyncAgain() {
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();

        // Stop
        mBassClientService.cleanup();

        // Start again
        mBassClientService =
                new BassClientService(mAdapterService, mScanController, mLooper.getLooper());
        mBassClientService.setAvailable(true);
        doReturn(mBinder).when(mCallback).asBinder();
        mBassClientService.registerCallback(mCallback);

        // Start searching again
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();
    }

    @Test
    public void testStopSearchingForSources_addSourceCauseSyncEvenWithoutScanning() {
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();

        // Stop searching
        mBassClientService.stopSearchingForSources();

        // Add source to unsynced broadcast, causes synchronization first
        mBassClientService.addSource(mCurrentDevice, mBroadcastMetadata1, /* isGroupOp */ true);
        verifyBackgroundSearchStarted();
        verifyRegisterSyncCalled(mSourceDevice);

        // Verify not getting ADD_BCAST_SOURCE message before source sync
        assertThat(mStateMachines).hasSize(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            verify(sm, never()).sendMessage(any());
        }

        // Source synced which cause execute pending add source
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        expect.that(mBassClientService.getActiveSyncedSources().size()).isEqualTo(1);
        expect.that(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);
        assertThat(mBassClientService.isAnySearchInProgress()).isFalse();

        // Verify all group members getting ADD_BCAST_SOURCE message
        expect.that(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());

            Message msg =
                    messageCaptor.getAllValues().stream()
                            .filter(
                                    m ->
                                            (m.what == BassClientStateMachine.ADD_BCAST_SOURCE)
                                                    && (m.obj == mBroadcastMetadata1))
                            .findFirst()
                            .orElse(null);
            expect.that(msg).isNotNull();
        }

        injectRemoteSourceStateSourceAdded(
                mBroadcastMetadata1, /* isPaSynced */ true, /* isBisSynced */ true);
        verifyUnregisterSyncCalled();
    }

    @Test
    public void testNotRemovingCachedBroadcastOnLostWithoutScanning() throws RemoteException {
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();
        assertThat(mBassClientService.getCachedBroadcast(TEST_BROADCAST_ID)).isNotNull();

        // Sync lost during scanning removes cached broadcast
        onSyncLost();
        checkAndDispatchTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_SYNC_LOST_TIMEOUT);
        assertThat(mBassClientService.getCachedBroadcast(TEST_BROADCAST_ID)).isNull();

        // Add broadcast to cache
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        verifyRegisterSyncCalled(mSourceDevice);

        // Stop searching
        mBassClientService.stopSearchingForSources();

        // Add sync handle by add source
        mBassClientService.addSource(mCurrentDevice, mBroadcastMetadata1, /* isGroupOp */ true);
        verifyBackgroundSearchStarted();
        verifyRegisterSyncCalled(mSourceDevice);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        assertThat(mBassClientService.isAnySearchInProgress()).isFalse();

        // Sync lost without active scanning should not remove broadcast cache
        onSyncLost();
        checkAndDispatchTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_SYNC_LOST_TIMEOUT);
        assertThat(mBassClientService.getCachedBroadcast(TEST_BROADCAST_ID)).isNotNull();
    }

    @Test
    public void testNotRemovingCachedBroadcastOnFailEstablishWithoutScanning()
            throws RemoteException {
        final BluetoothDevice device1 = getRealDevice("00:11:22:33:44:11", ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device2 = getRealDevice("00:11:22:33:44:22", ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device3 = getRealDevice("00:11:22:33:44:33", ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device4 = getRealDevice("00:11:22:33:44:44", ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device5 = getRealDevice("00:11:22:33:44:55", ADDRESS_TYPE_RANDOM);
        final int handle1 = 0;
        final int handle2 = 1;
        final int handle3 = 2;
        final int handle4 = 3;
        final int handle5 = 4;
        final int broadcastId1 = 1111;
        final int broadcastId2 = 2222;
        final int broadcastId3 = 3333;
        final int broadcastId4 = 4444;
        final int broadcastId5 = 5555;

        prepareConnectedDeviceGroup();
        startSearchingForSources();

        // Scan and sync 5 sources cause removing 1 synced element
        onScanResult(device1, broadcastId1);
        verifyRegisterSyncCalled(device1);
        onSyncEstablished(device1, handle1);
        onScanResult(device2, broadcastId2);
        verifyRegisterSyncCalled(device2);
        onSyncEstablished(device2, handle2);
        onScanResult(device3, broadcastId3);
        verifyRegisterSyncCalled(device3);
        onSyncEstablished(device3, handle3);
        onScanResult(device4, broadcastId4);
        verifyRegisterSyncCalled(device4);
        onSyncEstablished(device4, handle4);
        onScanResult(device5, broadcastId5);
        verifyRegisterSyncCalled(device5);
        onSyncEstablished(device5, handle5);

        BluetoothLeBroadcastMetadata.Builder builder =
                new BluetoothLeBroadcastMetadata.Builder()
                        .setEncrypted(false)
                        .setSourceDevice(device1, ADDRESS_TYPE_RANDOM)
                        .setSourceAdvertisingSid(TEST_ADVERTISER_SID)
                        .setBroadcastId(broadcastId1)
                        .setBroadcastCode(null)
                        .setPaSyncInterval(TEST_PA_SYNC_INTERVAL)
                        .setPresentationDelayMicros(TEST_PRESENTATION_DELAY_MS);
        // builder expect at least one subgroup
        builder.addSubgroup(createBroadcastSubgroup());
        BluetoothLeBroadcastMetadata meta = builder.build();

        // Add source to unsynced broadcast, causes synchronization first
        mBassClientService.addSource(mCurrentDevice, meta, /* isGroupOp */ true);
        verifyRegisterSyncCalled(device1);
        assertThat(mBassClientService.getCachedBroadcast(broadcastId1)).isNotNull();

        // Error in syncEstablished causes sourceLost, sourceAddFailed notification
        // and removing cache because scanning is active
        onSyncEstablishedFailed(device1, handle1);
        mLooper.dispatchAll();
        InOrder inOrderCallback = inOrder(mCallback);
        inOrderCallback.verify(mCallback).onSourceLost(eq(broadcastId1));
        inOrderCallback
                .verify(mCallback)
                .onSourceAddFailed(
                        eq(mCurrentDevice),
                        eq(meta),
                        eq(BluetoothStatusCodes.ERROR_LOCAL_NOT_ENOUGH_RESOURCES));
        assertThat(mBassClientService.getCachedBroadcast(broadcastId1)).isNull();

        // Scan and sync again
        onScanResult(device1, broadcastId1);
        verifyRegisterSyncCalled(device1);
        onSyncEstablished(device1, handle1);

        // Stop searching
        mBassClientService.stopSearchingForSources();

        // Add source to unsynced broadcast, causes synchronization first
        mBassClientService.addSource(mCurrentDevice, meta, /* isGroupOp */ true);
        verifyBackgroundSearchStarted();
        verifyRegisterSyncCalled(device1);

        // Error in syncEstablished causes sourceLost, sourceAddFailed notification
        // and not removing cache because scanning is inactive
        onSyncEstablishedFailed(device1, handle1);
        assertThat(mBassClientService.isAnySearchInProgress()).isFalse();
        mLooper.dispatchAll();
        inOrderCallback
                .verify(mCallback)
                .onSourceAddFailed(
                        eq(mCurrentDevice),
                        eq(meta),
                        eq(BluetoothStatusCodes.ERROR_LOCAL_NOT_ENOUGH_RESOURCES));
        assertThat(mBassClientService.getCachedBroadcast(broadcastId1)).isNotNull();
    }

    @Test
    public void testMultipleAddSourceToUnsyncedBroadcaster() {
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();

        // Stop searching to unsync broadcaster
        mBassClientService.stopSearchingForSources();

        // Sink1 aAdd source to unsynced broadcast, causes synchronization first
        mBassClientService.addSource(mCurrentDevice, mBroadcastMetadata1, /* isGroupOp */ false);
        verifyBackgroundSearchStarted();
        verifyRegisterSyncCalled(mSourceDevice);

        // Sink2 add source to unsynced broadcast
        mBassClientService.addSource(mCurrentDevice1, mBroadcastMetadata1, /* isGroupOp */ false);

        // Sync established
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        assertThat(mBassClientService.isAnySearchInProgress()).isFalse();
        mLooper.dispatchAll();

        // Both add sources should be called to state machines
        expect.that(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());

            Message msg =
                    messageCaptor.getAllValues().stream()
                            .filter(
                                    m ->
                                            (m.what == BassClientStateMachine.ADD_BCAST_SOURCE)
                                                    && (m.obj == mBroadcastMetadata1))
                            .findFirst()
                            .orElse(null);
            expect.that(msg).isNotNull();
        }

        // There should be no second selectSource call
        verifyRegisterSyncNeverCalled();
    }

    @Test
    public void testMultipleAddSourceToUnsyncedInactiveBroadcaster() throws RemoteException {
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();

        // Stop searching to unsync broadcaster
        mBassClientService.stopSearchingForSources();

        // Sink1 aAdd source to unsynced broadcast, causes synchronization first
        mBassClientService.addSource(mCurrentDevice, mBroadcastMetadata1, /* isGroupOp */ false);
        verifyBackgroundSearchStarted();
        verifyRegisterSyncCalled(mSourceDevice);

        // Sink2 add source to unsynced broadcast
        mBassClientService.addSource(mCurrentDevice1, mBroadcastMetadata1, /* isGroupOp */ false);

        // Error in syncEstablished causes sourceLost, sourceAddFailed notification for both sinks
        onSyncEstablishedFailed(mSourceDevice, TEST_SYNC_HANDLE);
        assertThat(mBassClientService.isAnySearchInProgress()).isFalse();
        mLooper.dispatchAll();
        InOrder inOrderCallback = inOrder(mCallback);
        inOrderCallback
                .verify(mCallback)
                .onSourceAddFailed(
                        eq(mCurrentDevice),
                        eq(mBroadcastMetadata1),
                        eq(BluetoothStatusCodes.ERROR_LOCAL_NOT_ENOUGH_RESOURCES));
        inOrderCallback
                .verify(mCallback)
                .onSourceAddFailed(
                        eq(mCurrentDevice1),
                        eq(mBroadcastMetadata1),
                        eq(BluetoothStatusCodes.ERROR_LOCAL_NOT_ENOUGH_RESOURCES));

        // There should be no second selectSource call
        verifyRegisterSyncNeverCalled();
    }

    @Test
    public void sourceAddFailed_alreadyPending() throws RemoteException {
        prepareConnectedDeviceGroup();

        // Use both as local to not need sync bass with them
        doReturn(mBroadcastMetadata1).when(mLeAudioService).getBroadcastMetadata(TEST_BROADCAST_ID);
        doReturn(mBroadcastMetadata2)
                .when(mLeAudioService)
                .getBroadcastMetadata(TEST_BROADCAST_ID_2);
        doReturn(true).when(mLeAudioService).isPlaying(TEST_BROADCAST_ID);
        doReturn(true).when(mLeAudioService).isPlaying(TEST_BROADCAST_ID_2);

        // Fake already pending operation for TEST_BROADCAST_ID but not for TEST_BROADCAST_ID_2
        doReturn(true).when(mStateMachines.get(mCurrentDevice)).hasPendingSourceOperation();
        doReturn(true)
                .when(mStateMachines.get(mCurrentDevice))
                .hasPendingSourceOperation(TEST_BROADCAST_ID);
        doReturn(false)
                .when(mStateMachines.get(mCurrentDevice))
                .hasPendingSourceOperation(TEST_BROADCAST_ID_2);

        // Add same broadcast as already pending not cause onSourceAddFailed
        mBassClientService.addSource(mCurrentDevice, mBroadcastMetadata1, /* isGroupOp */ false);
        mLooper.dispatchAll();
        InOrder inOrder = inOrder(mCallback);
        inOrder.verify(mCallback, never())
                .onSourceAddFailed(
                        eq(mCurrentDevice),
                        eq(mBroadcastMetadata1),
                        eq(BluetoothStatusCodes.ERROR_ANOTHER_ACTIVE_REQUEST));
        verify(mStateMachines.get(mCurrentDevice), never()).sendMessage(any());

        // Add source for different broadcast during another pending cause onSourceAddFailed
        mBassClientService.addSource(mCurrentDevice, mBroadcastMetadata2, /* isGroupOp */ false);
        mLooper.dispatchAll();
        inOrder.verify(mCallback)
                .onSourceAddFailed(
                        eq(mCurrentDevice),
                        eq(mBroadcastMetadata2),
                        eq(BluetoothStatusCodes.ERROR_ANOTHER_ACTIVE_REQUEST));
        verify(mStateMachines.get(mCurrentDevice), never()).sendMessage(any());

        // Not pending
        doReturn(false).when(mStateMachines.get(mCurrentDevice)).hasPendingSourceOperation();
        doReturn(false)
                .when(mStateMachines.get(mCurrentDevice))
                .hasPendingSourceOperation(TEST_BROADCAST_ID);
        doReturn(false)
                .when(mStateMachines.get(mCurrentDevice))
                .hasPendingSourceOperation(TEST_BROADCAST_ID_2);

        // Add broadcast while not pending cause ADD_BCAST_SOURCE msg to SM
        mBassClientService.addSource(mCurrentDevice, mBroadcastMetadata1, /* isGroupOp */ false);
        mLooper.dispatchAll();
        inOrder.verify(mCallback, never())
                .onSourceAddFailed(
                        eq(mCurrentDevice),
                        eq(mBroadcastMetadata1),
                        eq(BluetoothStatusCodes.ERROR_ANOTHER_ACTIVE_REQUEST));
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(mStateMachines.get(mCurrentDevice), atLeast(1)).sendMessage(messageCaptor.capture());
        Message msg =
                messageCaptor.getAllValues().stream()
                        .filter(
                                m ->
                                        (m.what == BassClientStateMachine.ADD_BCAST_SOURCE)
                                                && (m.obj == mBroadcastMetadata1))
                        .findFirst()
                        .orElse(null);
        assertThat(msg).isNotNull();
    }

    private void checkNoTimeout(int broadcastId, int message) {
        assertThat(mBassClientService.mTimeoutHandler.isStarted(broadcastId, message)).isFalse();
    }

    private void checkTimeout(int broadcastId, int message) {
        assertThat(mBassClientService.mTimeoutHandler.isStarted(broadcastId, message)).isTrue();
    }

    private void checkAndDispatchTimeout(int broadcastId, int message) {
        checkTimeout(broadcastId, message);
        mBassClientService.mTimeoutHandler.stop(broadcastId, message);
        Handler handler = mBassClientService.mTimeoutHandler.getOrCreateHandler(broadcastId);
        Message newMsg = handler.obtainMessage(message);
        handler.dispatchMessage(newMsg);
    }

    private static byte[] getScanRecord(int broadcastId) {
        return new byte[] {
            0x02,
            0x01,
            0x1a, // advertising flags
            0x05,
            0x02,
            0x52,
            0x18,
            0x0a,
            0x11, // 16 bit service uuids
            0x04,
            0x09,
            0x50,
            0x65,
            0x64, // name
            0x02,
            0x0A,
            (byte) 0xec, // tx power level
            0x05,
            0x30,
            0x54,
            0x65,
            0x73,
            0x74, // broadcast name: Test
            0x06,
            0x16,
            0x52,
            0x18,
            (byte) broadcastId,
            (byte) (broadcastId >> 8),
            (byte) (broadcastId >> 16), // service data, broadcast id
            0x08,
            0x16,
            0x56,
            0x18,
            0x07,
            0x03,
            0x02,
            0x08,
            0x01,
            // service data - public broadcast,
            // feature - 0x7, metadata len - 0x3, metadata - 0x2, 0x8, 0x1
            0x05,
            (byte) 0xff,
            (byte) 0xe0,
            0x00,
            0x02,
            0x15, // manufacturer specific data
            0x03,
            0x50,
            0x01,
            0x02, // an unknown data type won't cause trouble
        };
    }

    private void generateScanResult(ScanResult result) {
        try {
            mBassScanCallbackCaptor.getValue().onScanResult(result);
        } catch (RemoteException e) {
            // the mocked onScanResult doesn't throw RemoteException
        }
    }

    private void onScanResult(BluetoothDevice testDevice, int broadcastId) {
        byte[] scanRecord = getScanRecord(broadcastId);
        onScanResult(testDevice, scanRecord);
    }

    private void onScanResult(BluetoothDevice testDevice, byte[] scanRecord) {
        ScanResult scanResult =
                new ScanResult(
                        testDevice,
                        0,
                        0,
                        0,
                        TEST_ADVERTISER_SID,
                        0,
                        TEST_RSSI,
                        TEST_PA_SYNC_INTERVAL,
                        ScanRecord.parseFromBytes(scanRecord),
                        0);
        generateScanResult(scanResult);
    }

    private static byte[] getPAScanRecord() {
        return new byte[] {
            (byte) 0x02,
            (byte) 0x01,
            (byte) 0x1a, // advertising flags
            (byte) 0x05,
            (byte) 0x02,
            (byte) 0x51,
            (byte) 0x18,
            (byte) 0x0a,
            (byte) 0x11, // 16 bit service uuids
            (byte) 0x04,
            (byte) 0x09,
            (byte) 0x50,
            (byte) 0x65,
            (byte) 0x64, // name
            (byte) 0x02,
            (byte) 0x0A,
            (byte) 0xec, // tx power level
            (byte) 0x27,
            (byte) 0x16,
            (byte) 0x51,
            (byte) 0x18, // service data (base data with 18 bytes)
            // LEVEL 1
            (byte) 0x01,
            (byte) 0x02,
            (byte) 0x03, // mPresentationDelay
            (byte) 0x01, // mNumSubGroups
            // LEVEL 2
            (byte) 0x01, // mNumBises
            (byte) 0x06,
            (byte) 0x00,
            (byte) 0x00,
            (byte) 0x00,
            (byte) 0x00, // LC3
            (byte) 0x0D, // mCodecSpecificConfigurationLength
            (byte) 0x02,
            (byte) 0x01,
            (byte) 0x08,
            (byte) 0x02,
            (byte) 0x02,
            (byte) 0x01,
            (byte) 0x03,
            (byte) 0x04,
            (byte) 0x64,
            (byte) 0x00,
            (byte) 0x02,
            (byte) 0x05,
            (byte) 0x01, // mCodecSpecificConfiguration
            (byte) 0x03, // mMetaDataLength
            (byte) 0x02,
            (byte) 0x08,
            (byte) 0x01, // mMetaData
            // LEVEL 3
            (byte) 0x01, // mIndex
            (byte) 0x06, // mCodecSpecificConfigurationLength
            (byte) 0x05,
            (byte) 0x03,
            (byte) 0x01,
            (byte) 0x00,
            (byte) 0x00,
            (byte) 0x00, // mCodecSpecificConfiguration
            (byte) 0x05,
            (byte) 0xff,
            (byte) 0xe0,
            (byte) 0x00,
            (byte) 0x02,
            (byte) 0x15, // manufacturer specific data
            (byte) 0x03,
            (byte) 0x50,
            (byte) 0x01,
            (byte) 0x02, // an unknown data type won't cause trouble
        };
    }

    private void onPeriodicAdvertisingReport(PeriodicAdvertisingReport report) {
        BassClientService.PACallback callback = mBassClientService.new PACallback();
        callback.onPeriodicAdvertisingReport(report);
    }

    private void onPeriodicAdvertisingReport(byte[] scanRecord) {
        ScanRecord record = ScanRecord.parseFromBytes(scanRecord);
        PeriodicAdvertisingReport report =
                new PeriodicAdvertisingReport(TEST_SYNC_HANDLE, 0, 0, 0, record);
        onPeriodicAdvertisingReport(report);
    }

    private void onPeriodicAdvertisingReport() {
        onPeriodicAdvertisingReport(getPAScanRecord());
    }

    private void onBigInfoAdvertisingReport() {
        BassClientService.PACallback callback = mBassClientService.new PACallback();
        callback.onBigInfoAdvertisingReport(TEST_SYNC_HANDLE, true);
    }

    private void onSyncLost() {
        BassClientService.PACallback callback = mBassClientService.new PACallback();
        callback.onSyncLost(TEST_SYNC_HANDLE);
    }

    private void onSyncEstablished(BluetoothDevice testDevice, int syncHandle) {
        BassClientService.PACallback callback = mBassClientService.new PACallback();
        callback.onSyncEstablished(
                syncHandle, testDevice, TEST_ADVERTISER_SID, 0, 200, BluetoothGatt.GATT_SUCCESS);
    }

    private void onSyncEstablishedFailed(BluetoothDevice testDevice, int syncHandle) {
        BassClientService.PACallback callback = mBassClientService.new PACallback();
        callback.onSyncEstablished(
                syncHandle, testDevice, TEST_ADVERTISER_SID, 0, 200, BluetoothGatt.GATT_FAILURE);
    }

    private void addSourceAndVerify(BluetoothLeBroadcastMetadata meta) {
        // Add broadcast source
        mBassClientService.addSource(mCurrentDevice, meta, /* isGroupOp */ true);

        // Verify all group members getting ADD_BCAST_SOURCE message
        assertThat(mStateMachines).hasSize(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());

            Message msg =
                    messageCaptor.getAllValues().stream()
                            .filter(
                                    m ->
                                            (m.what == BassClientStateMachine.ADD_BCAST_SOURCE)
                                                    && (m.obj == meta))
                            .findFirst()
                            .orElse(null);
            assertThat(msg).isNotNull();
            clearInvocations(sm);
        }
    }

    private static BluetoothLeBroadcastReceiveState injectRemoteSourceState(
            BassClientStateMachine sm,
            BluetoothLeBroadcastMetadata meta,
            int sourceId,
            int paSynState,
            int encryptionState,
            byte[] badCode,
            long bisSyncState) {
        BluetoothLeBroadcastReceiveState recvState =
                new BluetoothLeBroadcastReceiveState(
                        sourceId,
                        meta.getSourceAddressType(),
                        meta.getSourceDevice(),
                        meta.getSourceAdvertisingSid(),
                        meta.getBroadcastId(),
                        paSynState,
                        encryptionState,
                        badCode,
                        meta.getSubgroups().size(),
                        // Bis sync states
                        meta.getSubgroups().stream()
                                .map(e -> bisSyncState)
                                .collect(Collectors.toList()),
                        meta.getSubgroups().stream()
                                .map(e -> e.getContentMetadata())
                                .collect(Collectors.toList()));
        doReturn(meta).when(sm).getCurrentBroadcastMetadata(eq(sourceId));

        List<BluetoothLeBroadcastReceiveState> stateList = sm.getAllSources();
        if (stateList == null) {
            stateList = new ArrayList<BluetoothLeBroadcastReceiveState>();
        } else {
            stateList.removeIf(e -> e.getSourceId() == sourceId);
        }
        stateList.add(recvState);
        doReturn(stateList).when(sm).getAllSources();

        return recvState;
    }

    private BluetoothLeBroadcastReceiveState injectRemoteSourceStateSourceAdded(
            BassClientStateMachine sm,
            BluetoothLeBroadcastMetadata meta,
            int sourceId,
            int paSynState,
            int encryptionState,
            byte[] badCode,
            long bisSyncState) {
        return injectRemoteSourceStateSourceAdded(
                sm, meta, sourceId, paSynState, encryptionState, badCode, bisSyncState, false);
    }

    private BluetoothLeBroadcastReceiveState injectRemoteSourceStateSourceAdded(
            BassClientStateMachine sm,
            BluetoothLeBroadcastMetadata meta,
            int sourceId,
            int paSynState,
            int encryptionState,
            byte[] badCode,
            long bisSyncState,
            boolean autonomous) {
        BluetoothLeBroadcastReceiveState recvState =
                injectRemoteSourceState(
                        sm, meta, sourceId, paSynState, encryptionState, badCode, bisSyncState);

        if (autonomous) {
            mBassClientService
                    .getCallbacks()
                    .notifySourceAdded(
                            sm.getDevice(), recvState, BluetoothStatusCodes.REASON_REMOTE_REQUEST);
        } else {
            mBassClientService
                    .getCallbacks()
                    .notifySourceAdded(
                            sm.getDevice(),
                            recvState,
                            BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST);
        }
        mBassClientService
                .getCallbacks()
                .notifyReceiveStateChanged(sm.getDevice(), recvState.getSourceId(), recvState);
        mLooper.dispatchAll();

        return recvState;
    }

    private void injectRemoteSourceStateSourceAdded(
            BluetoothLeBroadcastMetadata meta, boolean isPaSynced, boolean isBisSynced) {
        injectRemoteSourceStateSourceAdded(meta, isPaSynced, isBisSynced, false);
    }

    private void injectRemoteSourceStateSourceAdded(
            BluetoothLeBroadcastMetadata meta,
            boolean isPaSynced,
            boolean isBisSynced,
            boolean autonomous) {
        for (BassClientStateMachine sm : mStateMachines.values()) {
            if (sm.getDevice().equals(mCurrentDevice)) {
                injectRemoteSourceStateSourceAdded(
                        sm,
                        meta,
                        TEST_SOURCE_ID,
                        isPaSynced
                                ? BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED
                                : BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                        meta.isEncrypted()
                                ? BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING
                                : BluetoothLeBroadcastReceiveState
                                        .BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                        null,
                        isBisSynced ? 1L : 0L,
                        autonomous);
            } else if (sm.getDevice().equals(mCurrentDevice1)) {
                injectRemoteSourceStateSourceAdded(
                        sm,
                        meta,
                        TEST_SOURCE_ID + 1,
                        isPaSynced
                                ? BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED
                                : BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                        meta.isEncrypted()
                                ? BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING
                                : BluetoothLeBroadcastReceiveState
                                        .BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                        null,
                        isBisSynced ? 2L : 0L,
                        autonomous);
            }
        }
    }

    private BluetoothLeBroadcastReceiveState injectRemoteSourceStateChanged(
            BassClientStateMachine sm,
            BluetoothLeBroadcastMetadata meta,
            int sourceId,
            int paSynState,
            int encryptionState,
            byte[] badCode,
            long bisSyncState) {
        BluetoothLeBroadcastReceiveState recvState =
                injectRemoteSourceState(
                        sm, meta, sourceId, paSynState, encryptionState, badCode, bisSyncState);

        mBassClientService
                .getCallbacks()
                .notifyReceiveStateChanged(sm.getDevice(), recvState.getSourceId(), recvState);
        mLooper.dispatchAll();

        return recvState;
    }

    private void injectRemoteSourceStateChanged(
            BassClientStateMachine sm,
            BluetoothLeBroadcastMetadata meta,
            boolean isPaSynced,
            boolean isBisSynced) {
        // Update receiver state
        if (sm.getDevice().equals(mCurrentDevice)) {
            injectRemoteSourceStateChanged(
                    sm,
                    meta,
                    TEST_SOURCE_ID,
                    isPaSynced
                            ? BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED
                            : BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                    meta.isEncrypted()
                            ? BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING
                            : BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                    null,
                    isBisSynced ? 1L : 0L);
        } else if (sm.getDevice().equals(mCurrentDevice1)) {
            injectRemoteSourceStateChanged(
                    sm,
                    meta,
                    TEST_SOURCE_ID + 1,
                    isPaSynced
                            ? BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED
                            : BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                    meta.isEncrypted()
                            ? BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING
                            : BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                    null,
                    isBisSynced ? 2L : 0L);
        }
    }

    private void injectRemoteSourceStateChanged(
            BluetoothLeBroadcastMetadata meta, boolean isPaSynced, boolean isBisSynced) {
        for (BassClientStateMachine sm : mStateMachines.values()) {
            injectRemoteSourceStateChanged(sm, meta, isPaSynced, isBisSynced);
        }
    }

    private void injectRemoteSourceStateChanged(
            BluetoothLeBroadcastMetadata meta, int paSynState, boolean isBisSynced) {
        for (BassClientStateMachine sm : mStateMachines.values()) {
            // Update receiver state
            if (sm.getDevice().equals(mCurrentDevice)) {
                injectRemoteSourceStateChanged(
                        sm,
                        meta,
                        TEST_SOURCE_ID,
                        paSynState,
                        meta.isEncrypted()
                                ? BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING
                                : BluetoothLeBroadcastReceiveState
                                        .BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                        null,
                        isBisSynced ? 1L : 0L);
            } else if (sm.getDevice().equals(mCurrentDevice1)) {
                injectRemoteSourceStateChanged(
                        sm,
                        meta,
                        TEST_SOURCE_ID + 1,
                        paSynState,
                        meta.isEncrypted()
                                ? BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING
                                : BluetoothLeBroadcastReceiveState
                                        .BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                        null,
                        isBisSynced ? 2L : 0L);
            }
        }
    }

    private void injectRemoteSourceStateRemoval(BassClientStateMachine sm, int sourceId) {
        List<BluetoothLeBroadcastReceiveState> stateList = sm.getAllSources();
        if (stateList == null) {
            stateList = new ArrayList<BluetoothLeBroadcastReceiveState>();
        }
        stateList.replaceAll(
                e -> {
                    if (e.getSourceId() != sourceId) return e;
                    return new BluetoothLeBroadcastReceiveState(
                            sourceId,
                            ADDRESS_TYPE_PUBLIC,
                            getRealDevice("00:00:00:00:00:00", ADDRESS_TYPE_PUBLIC),
                            0,
                            0,
                            BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                            BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                            null,
                            0,
                            Arrays.asList(new Long[0]),
                            Arrays.asList(new BluetoothLeAudioContentMetadata[0]));
                });
        doReturn(stateList).when(sm).getAllSources();

        Optional<BluetoothLeBroadcastReceiveState> receiveState =
                stateList.stream().filter(e -> e.getSourceId() == sourceId).findFirst();

        mBassClientService
                .getCallbacks()
                .notifySourceRemoved(
                        sm.getDevice(), sourceId, BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST);
        mBassClientService
                .getCallbacks()
                .notifyReceiveStateChanged(sm.getDevice(), sourceId, receiveState.get());
        mLooper.dispatchAll();
    }

    private void injectDeviceDisconnection(BluetoothDevice device) {
        BassClientStateMachine sm = mStateMachines.get(device);
        doReturn(STATE_DISCONNECTED).when(sm).getConnectionState();
        doReturn(false).when(sm).isConnected();
        doReturn(new ArrayList<>()).when(sm).getAllSources();
        mBassClientService.connectionStateChanged(device, STATE_CONNECTED, STATE_DISCONNECTED);
    }

    private void injectDeviceConnection(BluetoothDevice device) {
        BassClientStateMachine sm = mStateMachines.get(device);
        doReturn(STATE_CONNECTED).when(sm).getConnectionState();
        doReturn(true).when(sm).isConnected();
        mBassClientService.connectionStateChanged(device, STATE_DISCONNECTED, STATE_CONNECTED);
    }

    private void prepareSyncToSourceAndVerify() {
        startSearchingForSources();

        // Scan and sync 1
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        verifyRegisterSyncCalled(mSourceDevice);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        assertThat(mBassClientService.getActiveSyncedSources()).hasSize(1);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);
        assertThat(mBassClientService.getActiveSyncedSources()).containsExactly(TEST_SYNC_HANDLE);
        assertThat(mBassClientService.getBase(TEST_SYNC_HANDLE)).isNull();
    }

    /**
     * Test whether service.addSource() does send proper messages to all the state machines within
     * the Csip coordinated group
     */
    @Test
    public void testAddSourceForGroup() {
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();
        addSourceAndVerify(mBroadcastMetadata1);
    }

    /** Test whether service.addSource() source id can be propagated through callback correctly */
    @Test
    public void testAddSourceCallbackForGroup() throws RemoteException {
        prepareSynchronizedPair();

        // verify source id
        mLooper.dispatchAll();
        verify(mCallback, atLeast(1))
                .onSourceAdded(
                        eq(mCurrentDevice),
                        eq(TEST_SOURCE_ID),
                        eq(BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST));
        verify(mCallback, atLeast(1))
                .onSourceAdded(
                        eq(mCurrentDevice1),
                        eq(TEST_SOURCE_ID + 1),
                        eq(BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST));
    }

    /**
     * Test whether service.modifySource() does send proper messages to all the state machines
     * within the Csip coordinated group
     */
    @Test
    public void testModifySourceForGroup() {
        prepareSynchronizedPair();

        // Update broadcast source using other member of the same group
        BluetoothLeBroadcastMetadata metaUpdate =
                new BluetoothLeBroadcastMetadata.Builder(mBroadcastMetadata1)
                        .setBroadcastId(TEST_BROADCAST_ID_2)
                        .build();
        mBassClientService.modifySource(mCurrentDevice1, TEST_SOURCE_ID + 1, metaUpdate);

        // Verify all group members getting UPDATE_BCAST_SOURCE message on proper sources
        expect.that(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());

            Optional<Message> msg =
                    messageCaptor.getAllValues().stream()
                            .filter(m -> m.what == BassClientStateMachine.UPDATE_BCAST_SOURCE)
                            .findFirst();
            expect.that(msg.isPresent()).isEqualTo(true);
            expect.that(msg.get().obj).isEqualTo(metaUpdate);

            // Verify using the right sourceId on each device
            if (sm.getDevice().equals(mCurrentDevice)) {
                expect.that(msg.get().arg1).isEqualTo(TEST_SOURCE_ID);
            } else if (sm.getDevice().equals(mCurrentDevice1)) {
                expect.that(msg.get().arg1).isEqualTo(TEST_SOURCE_ID + 1);
            }
        }
    }

    /**
     * Test whether service.removeSource() does send proper messages to all the state machines
     * within the Csip coordinated group
     */
    @Test
    public void testRemoveSourceForGroup() {
        prepareSynchronizedPair();

        // Remove broadcast source using other member of the same group
        mBassClientService.removeSource(mCurrentDevice1, TEST_SOURCE_ID + 1);

        // Verify all group members getting REMOVE_BCAST_SOURCE message
        expect.that(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());

            Optional<Message> msg =
                    messageCaptor.getAllValues().stream()
                            .filter(m -> m.what == BassClientStateMachine.REMOVE_BCAST_SOURCE)
                            .findFirst();
            expect.that(msg.isPresent()).isEqualTo(true);

            // Verify using the right sourceId on each device
            if (sm.getDevice().equals(mCurrentDevice)) {
                expect.that(msg.get().arg1).isEqualTo(TEST_SOURCE_ID);
            } else if (sm.getDevice().equals(mCurrentDevice1)) {
                expect.that(msg.get().arg1).isEqualTo(TEST_SOURCE_ID + 1);
            }
        }
    }

    private void verifyRemoveMessageAndInjectSourceRemoval() {
        for (BassClientStateMachine sm : mStateMachines.values()) {
            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());

            Optional<Message> msg =
                    messageCaptor.getAllValues().stream()
                            .filter(m -> m.what == BassClientStateMachine.REMOVE_BCAST_SOURCE)
                            .findFirst();
            assertThat(msg.isPresent()).isTrue();

            if (sm.getDevice().equals(mCurrentDevice)) {
                assertThat(msg.get().arg1).isEqualTo(TEST_SOURCE_ID);
                injectRemoteSourceStateRemoval(sm, TEST_SOURCE_ID);
            } else if (sm.getDevice().equals(mCurrentDevice1)) {
                assertThat(msg.get().arg1).isEqualTo(TEST_SOURCE_ID + 1);
                injectRemoteSourceStateRemoval(sm, TEST_SOURCE_ID + 1);
            }
        }
    }

    private void verifyModifyMessageAndInjectSourceModified() {
        for (BassClientStateMachine sm : mStateMachines.values()) {
            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());

            Optional<Message> msg =
                    messageCaptor.getAllValues().stream()
                            .filter(m -> m.what == BassClientStateMachine.UPDATE_BCAST_SOURCE)
                            .findFirst();
            assertThat(msg.isPresent()).isTrue();

            if (sm.getDevice().equals(mCurrentDevice)) {
                assertThat(msg.get().arg1).isEqualTo(TEST_SOURCE_ID);
                injectRemoteSourceStateChanged(sm, mBroadcastMetadata1, false, false);
            } else if (sm.getDevice().equals(mCurrentDevice1)) {
                assertThat(msg.get().arg1).isEqualTo(TEST_SOURCE_ID + 1);
                injectRemoteSourceStateChanged(sm, mBroadcastMetadata1, false, false);
            }
        }
    }

    /**
     * Test whether service.removeSource() does send modify source if source is from remote receive
     * state. In this case, assistant should be able to remove source which was not managed by BASS
     * service (external manager/no source metadata)
     */
    @Test
    public void testRemoveSourceForGroupAndTriggerModifySourceWithoutMetadata() {
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();

        for (BassClientStateMachine sm : mStateMachines.values()) {
            injectRemoteSourceStateSourceAdded(
                    sm,
                    mBroadcastMetadata1,
                    TEST_SOURCE_ID,
                    BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED,
                    mBroadcastMetadata1.isEncrypted()
                            ? BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING
                            : BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                    null,
                    0L);
            // no current broadcast metadata for external broadcast source
            doReturn(null).when(sm).getCurrentBroadcastMetadata(eq(TEST_SOURCE_ID));
            doReturn(true).when(sm).isSyncedToTheSource(eq(TEST_SOURCE_ID));
        }

        for (BassClientStateMachine sm : mStateMachines.values()) {
            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            mBassClientService.removeSource(sm.getDevice(), TEST_SOURCE_ID);
            // Verify device get update source
            verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());

            Optional<Message> msg =
                    messageCaptor.getAllValues().stream()
                            .filter(m -> m.what == BassClientStateMachine.REMOVE_BCAST_SOURCE)
                            .findFirst();
            assertThat(msg.isPresent()).isTrue();

            assertThat(msg.get().arg1).isEqualTo(TEST_SOURCE_ID);
            // Verify metadata is null
            assertThat(msg.get().obj).isNull();
        }

        for (BassClientStateMachine sm : mStateMachines.values()) {
            injectRemoteSourceStateRemoval(sm, TEST_SOURCE_ID);
        }
    }

    @Test
    public void testGetSourceMetadata() {
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();

        for (BassClientStateMachine sm : mStateMachines.values()) {
            injectRemoteSourceStateSourceAdded(
                    sm,
                    mBroadcastMetadata1,
                    TEST_SOURCE_ID,
                    BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED,
                    mBroadcastMetadata1.isEncrypted()
                            ? BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING
                            : BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                    null,
                    0L);
            doReturn(null).when(sm).getCurrentBroadcastMetadata(eq(TEST_SOURCE_ID));
            assertThat(mBassClientService.getSourceMetadata(sm.getDevice(), TEST_SOURCE_ID))
                    .isNull();

            doReturn(mBroadcastMetadata1).when(sm).getCurrentBroadcastMetadata(eq(TEST_SOURCE_ID));
            doReturn(true).when(sm).isSyncedToTheSource(eq(TEST_SOURCE_ID));
            assertThat(mBassClientService.getSourceMetadata(sm.getDevice(), TEST_SOURCE_ID))
                    .isEqualTo(mBroadcastMetadata1);
        }

        for (BassClientStateMachine sm : mStateMachines.values()) {
            injectRemoteSourceStateRemoval(sm, TEST_SOURCE_ID);
        }
    }

    /** Test whether the group operation flag is set on addSource() and removed on removeSource */
    @Test
    public void testGroupStickyFlagSetUnset() {
        prepareSynchronizedPair();

        // Remove broadcast source
        mBassClientService.removeSource(mCurrentDevice, TEST_SOURCE_ID);
        verifyRemoveMessageAndInjectSourceRemoval();

        // Update broadcast source
        mBassClientService.modifySource(mCurrentDevice, TEST_SOURCE_ID, mBroadcastMetadata2);

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        Optional<Message> msg;

        // Verify that one device got the message...
        verify(mStateMachines.get(mCurrentDevice), atLeast(1)).sendMessage(messageCaptor.capture());
        msg =
                messageCaptor.getAllValues().stream()
                        .filter(m -> m.what == BassClientStateMachine.UPDATE_BCAST_SOURCE)
                        .findFirst();
        expect.that(msg.isPresent()).isTrue();
        expect.that(msg.orElse(null)).isNotNull();

        // ... but not the other one, since the sticky group flag should have been removed
        messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(mStateMachines.get(mCurrentDevice1), atLeast(1))
                .sendMessage(messageCaptor.capture());
        msg =
                messageCaptor.getAllValues().stream()
                        .filter(m -> m.what == BassClientStateMachine.UPDATE_BCAST_SOURCE)
                        .findFirst();
        expect.that(msg.isPresent()).isFalse();
    }

    /** Test switch source will be triggered if adding new source when sink has source */
    @Test
    public void testSwitchSourceAfterSourceAdded() {
        prepareSynchronizedPair();

        // Add another new broadcast source
        onScanResult(mSourceDevice2, TEST_BROADCAST_ID_2);
        onSyncEstablished(mSourceDevice2, TEST_SYNC_HANDLE_2);
        mBassClientService.addSource(mCurrentDevice, mBroadcastMetadata2, /* isGroupOp */ true);

        // Verify all group members getting SWITCH_BCAST_SOURCE message and first source got
        // selected to remove
        expect.that(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());
            if (sm.getDevice().equals(mCurrentDevice)) {
                Optional<Message> msg =
                        messageCaptor.getAllValues().stream()
                                .filter(
                                        m ->
                                                (m.what
                                                                == BassClientStateMachine
                                                                        .SWITCH_BCAST_SOURCE)
                                                        && (m.obj == mBroadcastMetadata2)
                                                        && (m.arg1 == TEST_SOURCE_ID))
                                .findFirst();
                expect.that(msg.isPresent()).isTrue();
                expect.that(msg.orElse(null)).isNotNull();
            } else if (sm.getDevice().equals(mCurrentDevice1)) {
                Optional<Message> msg =
                        messageCaptor.getAllValues().stream()
                                .filter(
                                        m ->
                                                (m.what
                                                                == BassClientStateMachine
                                                                        .SWITCH_BCAST_SOURCE)
                                                        && (m.obj == mBroadcastMetadata2)
                                                        && (m.arg1 == TEST_SOURCE_ID + 1))
                                .findFirst();
                expect.that(msg.isPresent()).isTrue();
                expect.that(msg.orElse(null)).isNotNull();
            } else {
                throw new AssertionError("Unexpected device");
            }
        }
    }

    @Test
    public void testSecondAddSourceWithCapacityGreaterThanOne() {
        prepareConnectedDeviceGroup();

        // Set maximum source capacity to 2
        for (BassClientStateMachine sm : mStateMachines.values()) {
            doReturn(2).when(sm).getMaximumSourceCapacity();
        }

        prepareSyncToSourceAndVerify();
        addSourceAndVerify(mBroadcastMetadata1);
        injectRemoteSourceStateSourceAdded(
                mBroadcastMetadata1, /* isPaSynced */ true, /* isBisSynced */ true);

        // Add another new broadcast source
        onScanResult(mSourceDevice2, TEST_BROADCAST_ID_2);
        onSyncEstablished(mSourceDevice2, TEST_SYNC_HANDLE_2);
        addSourceAndVerify(mBroadcastMetadata2);
    }

    @Test
    public void testDuplicateAddSource() {
        prepareSynchronizedPair();

        BluetoothLeBroadcastMetadata.Builder builder =
                new BluetoothLeBroadcastMetadata.Builder()
                        .setEncrypted(true)
                        .setSourceDevice(mSourceDevice, ADDRESS_TYPE_RANDOM)
                        .setSourceAdvertisingSid(TEST_ADVERTISER_SID)
                        .setBroadcastId(TEST_BROADCAST_ID)
                        .setBroadcastCode(new byte[] {1, 2, 2, 4})
                        .setPaSyncInterval(TEST_PA_SYNC_INTERVAL)
                        .setPresentationDelayMicros(TEST_PRESENTATION_DELAY_MS);
        // builder expect at least one subgroup
        builder.addSubgroup(createBroadcastSubgroup());
        BluetoothLeBroadcastMetadata meta = builder.build();

        // Duplicate add source cause UPDATE_BCAST_SOURCE with passed metadata
        mBassClientService.addSource(mCurrentDevice, meta, /* isGroupOp */ true);
        assertThat(mStateMachines).hasSize(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());
            Message msg =
                    messageCaptor.getAllValues().stream()
                            .filter(
                                    m ->
                                            (m.what == BassClientStateMachine.UPDATE_BCAST_SOURCE)
                                                    && (m.obj.equals(meta)))
                            .findFirst()
                            .orElse(null);
            assertThat(msg).isNotNull();
            clearInvocations(sm);
        }

        // Resume source cause UPDATE_BCAST_SOURCE with stored metadata
        injectRemoteSourceStateChanged(meta, /* isPaSynced */ false, /* isBisSynced */ false);
        mBassClientService.resumeReceiversSourceSynchronization();
        for (BassClientStateMachine sm : mStateMachines.values()) {
            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());
            Message msg =
                    messageCaptor.getAllValues().stream()
                            .filter(
                                    m ->
                                            (m.what == BassClientStateMachine.UPDATE_BCAST_SOURCE)
                                                    && (m.obj.equals(meta)))
                            .findFirst()
                            .orElse(null);
            assertThat(msg).isNotNull();
            clearInvocations(sm);
        }
    }

    /**
     * Test that after multiple calls to service.addSource() with a group operation flag set, there
     * are two call to service.removeSource() needed to clear the flag
     */
    @Test
    public void testAddRemoveMultipleSourcesForGroup() {
        prepareConnectedDeviceGroup();

        // Set maximum source capacity to 2
        for (BassClientStateMachine sm : mStateMachines.values()) {
            doReturn(2).when(sm).getMaximumSourceCapacity();
        }

        prepareSyncToSourceAndVerify();
        addSourceAndVerify(mBroadcastMetadata1);
        assertThat(mStateMachines).hasSize(2);
        injectRemoteSourceStateSourceAdded(
                mBroadcastMetadata1, /* isPaSynced */ true, /* isBisSynced */ true);

        // Add another broadcast source
        onScanResult(mSourceDevice2, TEST_BROADCAST_ID_2);
        onSyncEstablished(mSourceDevice2, TEST_SYNC_HANDLE_2);
        addSourceAndVerify(mBroadcastMetadata2);
        assertThat(mStateMachines).hasSize(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            if (sm.getDevice().equals(mCurrentDevice)) {
                injectRemoteSourceStateSourceAdded(
                        sm,
                        mBroadcastMetadata2,
                        TEST_SOURCE_ID + 2,
                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                        mBroadcastMetadata2.isEncrypted()
                                ? BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING
                                : BluetoothLeBroadcastReceiveState
                                        .BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                        null,
                        0L);
            } else if (sm.getDevice().equals(mCurrentDevice1)) {
                injectRemoteSourceStateSourceAdded(
                        sm,
                        mBroadcastMetadata2,
                        TEST_SOURCE_ID + 3,
                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                        mBroadcastMetadata2.isEncrypted()
                                ? BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING
                                : BluetoothLeBroadcastReceiveState
                                        .BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                        null,
                        0L);
            } else {
                throw new AssertionError("Unexpected device");
            }
        }

        // Remove the first broadcast source
        mBassClientService.removeSource(mCurrentDevice, TEST_SOURCE_ID);
        assertThat(mStateMachines).hasSize(2);
        verifyRemoveMessageAndInjectSourceRemoval();

        // Modify the second one and verify all group members getting UPDATE_BCAST_SOURCE
        BluetoothLeBroadcastMetadata metaUpdate = createBroadcastMetadata(TEST_BROADCAST_ID + 3);
        mBassClientService.modifySource(mCurrentDevice1, TEST_SOURCE_ID + 3, metaUpdate);
        assertThat(mStateMachines).hasSize(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());

            Optional<Message> msg =
                    messageCaptor.getAllValues().stream()
                            .filter(m -> m.what == BassClientStateMachine.UPDATE_BCAST_SOURCE)
                            .findFirst();
            assertThat(msg.isPresent()).isTrue();
            assertThat(msg.get().obj).isEqualTo(metaUpdate);

            // Verify using the right sourceId on each device
            if (sm.getDevice().equals(mCurrentDevice)) {
                assertThat(msg.get().arg1).isEqualTo(TEST_SOURCE_ID + 2);
            } else if (sm.getDevice().equals(mCurrentDevice1)) {
                assertThat(msg.get().arg1).isEqualTo(TEST_SOURCE_ID + 3);
            } else {
                throw new AssertionError("Unexpected device");
            }
        }

        // Remove the second broadcast source and verify all group members getting
        // REMOVE_BCAST_SOURCE message for the second source
        mBassClientService.removeSource(mCurrentDevice, TEST_SOURCE_ID + 2);
        assertThat(mStateMachines).hasSize(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());

            if (sm.getDevice().equals(mCurrentDevice)) {
                Optional<Message> msg =
                        messageCaptor.getAllValues().stream()
                                .filter(
                                        m ->
                                                (m.what
                                                                == BassClientStateMachine
                                                                        .REMOVE_BCAST_SOURCE)
                                                        && (m.arg1 == TEST_SOURCE_ID + 2))
                                .findFirst();
                assertThat(msg.isPresent()).isTrue();
                injectRemoteSourceStateRemoval(sm, TEST_SOURCE_ID + 2);
            } else if (sm.getDevice().equals(mCurrentDevice1)) {
                Optional<Message> msg =
                        messageCaptor.getAllValues().stream()
                                .filter(
                                        m ->
                                                (m.what
                                                                == BassClientStateMachine
                                                                        .REMOVE_BCAST_SOURCE)
                                                        && (m.arg1 == TEST_SOURCE_ID + 3))
                                .findFirst();
                assertThat(msg.isPresent()).isTrue();
                injectRemoteSourceStateRemoval(sm, TEST_SOURCE_ID + 3);
            } else {
                throw new AssertionError("Unexpected device");
            }
        }

        // Fake the autonomous source change - or other client setting the source
        for (BassClientStateMachine sm : mStateMachines.values()) {
            clearInvocations(sm);

            BluetoothLeBroadcastMetadata metaOther =
                    createBroadcastMetadata(TEST_BROADCAST_ID + 20);
            injectRemoteSourceStateSourceAdded(
                    sm,
                    metaOther,
                    TEST_SOURCE_ID + 20,
                    BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                    mBroadcastMetadata1.isEncrypted()
                            ? BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING
                            : BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                    null,
                    0L);
        }

        // Modify this source and verify it is not group managed
        BluetoothLeBroadcastMetadata metaUpdate2 = createBroadcastMetadata(TEST_BROADCAST_ID + 30);
        mBassClientService.modifySource(mCurrentDevice1, TEST_SOURCE_ID + 20, metaUpdate2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            if (sm.getDevice().equals(mCurrentDevice)) {
                verify(sm, never()).sendMessage(any());
            } else if (sm.getDevice().equals(mCurrentDevice1)) {
                ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
                verify(sm).sendMessage(messageCaptor.capture());
                List<Message> msgs =
                        messageCaptor.getAllValues().stream()
                                .filter(
                                        m ->
                                                (m.what
                                                                == BassClientStateMachine
                                                                        .UPDATE_BCAST_SOURCE)
                                                        && (m.arg1 == TEST_SOURCE_ID + 20))
                                .collect(Collectors.toList());
                assertThat(msgs).hasSize(1);
            } else {
                throw new AssertionError("Unexpected device");
            }
        }
    }

    @Test
    public void testInvalidRequestForGroup() throws RemoteException {
        // Prepare the initial state
        prepareConnectedDeviceGroup();

        // Verify errors are reported for the entire group
        mBassClientService.addSource(mCurrentDevice1, null, /* isGroupOp */ true);
        assertThat(mStateMachines).hasSize(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            verify(sm, never()).sendMessage(any());
        }
        prepareSyncToSourceAndVerify();
        addSourceAndVerify(mBroadcastMetadata1);
        injectRemoteSourceStateSourceAdded(
                mBroadcastMetadata1, /* isPaSynced */ true, /* isBisSynced */ false);

        // Verify errors are reported for the entire group
        mBassClientService.modifySource(mCurrentDevice, TEST_SOURCE_ID, null);
        mLooper.dispatchAll();
        assertThat(mStateMachines).hasSize(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            if (sm.getDevice().equals(mCurrentDevice)) {
                verify(mCallback)
                        .onSourceModifyFailed(
                                eq(sm.getDevice()),
                                eq(TEST_SOURCE_ID),
                                eq(BluetoothStatusCodes.ERROR_BAD_PARAMETERS));
            } else if (sm.getDevice().equals(mCurrentDevice1)) {
                verify(mCallback)
                        .onSourceModifyFailed(
                                eq(sm.getDevice()),
                                eq(TEST_SOURCE_ID + 1),
                                eq(BluetoothStatusCodes.ERROR_BAD_PARAMETERS));
            }
        }

        assertThat(mStateMachines).hasSize(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            doReturn(STATE_DISCONNECTED).when(sm).getConnectionState();
        }

        // Verify errors are reported for the entire group
        mBassClientService.removeSource(mCurrentDevice, TEST_SOURCE_ID);
        mLooper.dispatchAll();
        assertThat(mStateMachines).hasSize(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            if (sm.getDevice().equals(mCurrentDevice)) {
                verify(mCallback)
                        .onSourceRemoveFailed(
                                eq(sm.getDevice()),
                                eq(TEST_SOURCE_ID),
                                eq(BluetoothStatusCodes.ERROR_REMOTE_LINK_ERROR));
            } else if (sm.getDevice().equals(mCurrentDevice1)) {
                verify(mCallback)
                        .onSourceRemoveFailed(
                                eq(sm.getDevice()),
                                eq(TEST_SOURCE_ID + 1),
                                eq(BluetoothStatusCodes.ERROR_REMOTE_LINK_ERROR));
            }
        }
    }

    @Test
    public void testCompatibilityOfAudioQuality() throws RemoteException {
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();

        // Remotes has unknown quality
        doReturn(Optional.empty())
                .when(mLeAudioService)
                .isCapableToReceiveHighQualityBroadcastAudio(mCurrentDevice);
        doReturn(Optional.empty())
                .when(mLeAudioService)
                .isCapableToReceiveHighQualityBroadcastAudio(mCurrentDevice1);

        // Broadcast has high quality only
        BluetoothLeBroadcastMetadata metadataHighQuality =
                new BluetoothLeBroadcastMetadata.Builder(mBroadcastMetadata1)
                        .setAudioConfigQuality(
                                BluetoothLeBroadcastMetadata.AUDIO_CONFIG_QUALITY_HIGH)
                        .build();

        // Verify add source pass, unknown quality, broadcast HQ only
        addSourceAndVerify(metadataHighQuality);
        mBassClientService.removeSource(mCurrentDevice1, TEST_SOURCE_ID);

        // Remotes do not support high quality
        doReturn(Optional.of(false))
                .when(mLeAudioService)
                .isCapableToReceiveHighQualityBroadcastAudio(mCurrentDevice);
        doReturn(Optional.of(false))
                .when(mLeAudioService)
                .isCapableToReceiveHighQualityBroadcastAudio(mCurrentDevice1);

        // Broadcast has not defined quality
        BluetoothLeBroadcastMetadata metadataNoQuality =
                new BluetoothLeBroadcastMetadata.Builder(mBroadcastMetadata1)
                        .setAudioConfigQuality(
                                BluetoothLeBroadcastMetadata.AUDIO_CONFIG_QUALITY_NONE)
                        .build();

        // Verify add source pass, group id does not support HQ, broadcast no Q set
        addSourceAndVerify(metadataNoQuality);
        mBassClientService.removeSource(mCurrentDevice1, TEST_SOURCE_ID);

        // Broadcast has standard quality only
        BluetoothLeBroadcastMetadata metadataStandardQuality =
                new BluetoothLeBroadcastMetadata.Builder(mBroadcastMetadata1)
                        .setAudioConfigQuality(
                                BluetoothLeBroadcastMetadata.AUDIO_CONFIG_QUALITY_STANDARD)
                        .build();

        // Verify add source pass, group id does not support HQ, broadcast SQ only
        addSourceAndVerify(metadataStandardQuality);
        mBassClientService.removeSource(mCurrentDevice1, TEST_SOURCE_ID);

        // Verify add source fail, group id does not support HQ, broadcast HQ only
        mBassClientService.addSource(mCurrentDevice, metadataHighQuality, /* isGroupOp */ true);
        mLooper.dispatchAll();
        verify(mCallback)
                .onSourceAddFailed(
                        eq(mCurrentDevice),
                        eq(metadataHighQuality),
                        eq(BluetoothStatusCodes.ERROR_BAD_PARAMETERS));
        verify(mCallback)
                .onSourceAddFailed(
                        eq(mCurrentDevice1),
                        eq(metadataHighQuality),
                        eq(BluetoothStatusCodes.ERROR_BAD_PARAMETERS));

        // Broadcast has standard and high quality
        BluetoothLeBroadcastMetadata metadataBothQuality =
                new BluetoothLeBroadcastMetadata.Builder(mBroadcastMetadata1)
                        .setAudioConfigQuality(
                                BluetoothLeBroadcastMetadata.AUDIO_CONFIG_QUALITY_STANDARD
                                        | BluetoothLeBroadcastMetadata.AUDIO_CONFIG_QUALITY_HIGH)
                        .build();

        // Verify add source pass, group id does not support HQ, broadcast SQ and HQ
        addSourceAndVerify(metadataBothQuality);
        mBassClientService.removeSource(mCurrentDevice1, TEST_SOURCE_ID);

        // Remotes support high quality
        doReturn(Optional.of(true))
                .when(mLeAudioService)
                .isCapableToReceiveHighQualityBroadcastAudio(mCurrentDevice);
        doReturn(Optional.of(true))
                .when(mLeAudioService)
                .isCapableToReceiveHighQualityBroadcastAudio(mCurrentDevice1);

        // Verify add source pass, group id supports HQ, broadcast SQ and HQ
        addSourceAndVerify(metadataBothQuality);
        mBassClientService.removeSource(mCurrentDevice1, TEST_SOURCE_ID);

        // Verify add source pass, group id supports HQ, broadcast HQ only
        addSourceAndVerify(metadataHighQuality);
    }

    /**
     * Test that an outgoing connection to two device that have BASS UUID is successful and a
     * connection state change intent is sent
     */
    @Test
    public void testConnectedIntent() {
        prepareConnectedDeviceGroup();

        expect.that(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            BluetoothDevice dev = sm.getDevice();
            verifyConnectionStateIntent(dev, STATE_CONNECTED, STATE_CONNECTING);
        }

        List<BluetoothDevice> devices = mBassClientService.getConnectedDevices();
        expect.that(devices.contains(mCurrentDevice)).isTrue();
        expect.that(devices.contains(mCurrentDevice1)).isTrue();
    }

    @Test
    public void testActiveSyncedSource_AddRemoveGet() {
        final int handle1 = 1;
        final int handle2 = 2;
        final int handle3 = 3;

        // Check if empty
        assertThat(mBassClientService.getActiveSyncedSources()).isEmpty();

        // Check adding first handle
        mBassClientService.addActiveSyncedSource(handle1);
        assertThat(mBassClientService.getActiveSyncedSources()).hasSize(1);
        assertThat(mBassClientService.getActiveSyncedSources()).containsExactly(handle1);

        // Check if cannot add duplicate element
        mBassClientService.addActiveSyncedSource(handle1);
        assertThat(mBassClientService.getActiveSyncedSources()).hasSize(1);
        assertThat(mBassClientService.getActiveSyncedSources()).containsExactly(handle1);

        // Check adding second element
        mBassClientService.addActiveSyncedSource(handle2);
        assertThat(mBassClientService.getActiveSyncedSources()).hasSize(2);
        assertThat(mBassClientService.getActiveSyncedSources())
                .containsExactly(handle1, handle2)
                .inOrder();

        // Check removing non existing element
        mBassClientService.removeActiveSyncedSource(handle3);
        assertThat(mBassClientService.getActiveSyncedSources()).hasSize(2);
        assertThat(mBassClientService.getActiveSyncedSources())
                .containsExactly(handle1, handle2)
                .inOrder();
        // Check removing second element
        mBassClientService.removeActiveSyncedSource(handle1);
        assertThat(mBassClientService.getActiveSyncedSources()).hasSize(1);
        assertThat(mBassClientService.getActiveSyncedSources()).containsExactly(handle2);

        // Check removing first element
        mBassClientService.removeActiveSyncedSource(handle2);
        assertThat(mBassClientService.getActiveSyncedSources()).isEmpty();

        // Add 2 elements
        mBassClientService.addActiveSyncedSource(handle1);
        mBassClientService.addActiveSyncedSource(handle2);
        assertThat(mBassClientService.getActiveSyncedSources()).hasSize(2);

        // Check removing all at once
        mBassClientService.removeActiveSyncedSource(null);
        assertThat(mBassClientService.getActiveSyncedSources()).isEmpty();
    }

    @Test
    public void testScanResult_withSameBroadcastId() {
        prepareConnectedDeviceGroup();
        startSearchingForSources();

        // First scanResult
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        verifyRegisterSyncCalled(mSourceDevice);
        // Finish select
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);

        // Second scanResult with the same broadcast id
        onScanResult(mSourceDevice2, TEST_BROADCAST_ID);
        verifyRegisterSyncNeverCalled();

        // Third scanResult with new broadcast id
        onScanResult(mSourceDevice2, TEST_BROADCAST_ID_2);
        verifyRegisterSyncCalled(mSourceDevice2);
    }

    @Test
    public void testSelectSource_withSameBroadcastId() {
        prepareConnectedDeviceGroup();
        startSearchingForSources();

        // First selectSource
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        verifyRegisterSyncCalled(mSourceDevice);
        // Finish select
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);

        // Second selectSource with the same broadcast id
        onScanResult(mSourceDevice2, TEST_BROADCAST_ID);
        verifyRegisterSyncNeverCalled();
    }

    @Test
    public void testSelectSource_wrongBassUUID() {
        byte[] scanRecord =
                new byte[] {
                    0x02,
                    0x01,
                    0x1a, // advertising flags
                    0x05,
                    0x02,
                    0x52,
                    0x18,
                    0x0a,
                    0x11, // 16 bit service uuids
                    0x04,
                    0x09,
                    0x50,
                    0x65,
                    0x64, // name
                    0x02,
                    0x0A,
                    (byte) 0xec, // tx power level
                    0x05,
                    0x30,
                    0x54,
                    0x65,
                    0x73,
                    0x74, // broadcast name: Test
                    0x06,
                    0x16,
                    0x00, // WRONG BAAS_UUID UUID
                    0x18,
                    (byte) TEST_BROADCAST_ID,
                    (byte) (TEST_BROADCAST_ID >> 8),
                    (byte) (TEST_BROADCAST_ID >> 16), // service data, broadcast id
                    0x08,
                    0x16,
                    0x56,
                    0x18,
                    0x07,
                    0x03,
                    0x02,
                    0x08,
                    0x01,
                    // service data - public broadcast,
                    // feature - 0x7, metadata len - 0x3, metadata - 0x2, 0x8, 0x1
                    0x05,
                    (byte) 0xff,
                    (byte) 0xe0,
                    0x00,
                    0x02,
                    0x15, // manufacturer specific data
                    0x03,
                    0x50,
                    0x01,
                    0x02, // an unknown data type won't cause trouble
                };

        prepareConnectedDeviceGroup();
        startSearchingForSources();
        onScanResult(mSourceDevice, scanRecord);
        verifyRegisterSyncNeverCalled();
    }

    @Test
    public void testSyncEstablished_statusFailed() {
        prepareConnectedDeviceGroup();
        startSearchingForSources();

        // First scanResult
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        verifyRegisterSyncCalled(mSourceDevice);

        // Finish select with failed status
        onSyncEstablishedFailed(mSourceDevice, TEST_SYNC_HANDLE);

        // Could try to sync again
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        verifyRegisterSyncCalled(mSourceDevice);
    }

    @Test
    public void testSelectSource_wrongPublicBroadcastUUID() {
        byte[] scanRecord =
                new byte[] {
                    0x02,
                    0x01,
                    0x1a, // advertising flags
                    0x05,
                    0x02,
                    0x52,
                    0x18,
                    0x0a,
                    0x11, // 16 bit service uuids
                    0x04,
                    0x09,
                    0x50,
                    0x65,
                    0x64, // name
                    0x02,
                    0x0A,
                    (byte) 0xec, // tx power level
                    0x05,
                    0x30,
                    0x54,
                    0x65,
                    0x73,
                    0x74, // broadcast name: Test
                    0x06,
                    0x16,
                    0x52,
                    0x18,
                    (byte) TEST_BROADCAST_ID,
                    (byte) (TEST_BROADCAST_ID >> 8),
                    (byte) (TEST_BROADCAST_ID >> 16), // service data, broadcast id
                    0x08,
                    0x16,
                    0x00, // WRONG PUBLIC_BROADCAST_UUID
                    0x18,
                    0x07,
                    0x03,
                    0x02,
                    0x08,
                    0x01,
                    // service data - public broadcast,
                    // feature - 0x7, metadata len - 0x3, metadata - 0x2, 0x8, 0x1
                    0x05,
                    (byte) 0xff,
                    (byte) 0xe0,
                    0x00,
                    0x02,
                    0x15, // manufacturer specific data
                    0x03,
                    0x50,
                    0x01,
                    0x02, // an unknown data type won't cause trouble
                };

        prepareConnectedDeviceGroup();
        startSearchingForSources();
        onScanResult(mSourceDevice, scanRecord);
        verifyRegisterSyncCalled(mSourceDevice);
    }

    @Test
    public void testSelectSource_wrongPublicBroadcastData() {
        byte[] scanRecord =
                new byte[] {
                    0x02,
                    0x01,
                    0x1a, // advertising flags
                    0x05,
                    0x02,
                    0x52,
                    0x18,
                    0x0a,
                    0x11, // 16 bit service uuids
                    0x04,
                    0x09,
                    0x50,
                    0x65,
                    0x64, // name
                    0x02,
                    0x0A,
                    (byte) 0xec, // tx power level
                    0x05,
                    0x30,
                    0x54,
                    0x65,
                    0x73,
                    0x74, // broadcast name: Test
                    0x06,
                    0x16,
                    0x52,
                    0x18,
                    (byte) TEST_BROADCAST_ID,
                    (byte) (TEST_BROADCAST_ID >> 8),
                    (byte) (TEST_BROADCAST_ID >> 16), // service data, broadcast id
                    0x08,
                    0x16,
                    0x56,
                    0x18,
                    0x07,
                    0x04, // WRONG PUBLIC_BROADCAST data (metadata size)
                    0x02,
                    0x08,
                    0x01,
                    // service data - public broadcast,
                    // feature - 0x7, metadata len - 0x3, metadata - 0x2, 0x8, 0x1
                    0x05,
                    (byte) 0xff,
                    (byte) 0xe0,
                    0x00,
                    0x02,
                    0x15, // manufacturer specific data
                    0x03,
                    0x50,
                    0x01,
                    0x02, // an unknown data type won't cause trouble
                };

        prepareConnectedDeviceGroup();
        startSearchingForSources();
        onScanResult(mSourceDevice, scanRecord);
        verifyRegisterSyncCalled(mSourceDevice);
    }

    @Test
    public void testSelectSource_queueAndRemoveAfterMaxLimit() {
        final BluetoothDevice device1 = getRealDevice("00:11:22:33:44:11", ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device2 = getRealDevice("00:11:22:33:44:22", ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device3 = getRealDevice("00:11:22:33:44:33", ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device4 = getRealDevice("00:11:22:33:44:44", ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device5 = getRealDevice("00:11:22:33:44:55", ADDRESS_TYPE_RANDOM);
        final int handle1 = 0;
        final int handle2 = 1;
        final int handle3 = 2;
        final int handle4 = 3;
        final int handle5 = 4;
        final int broadcastId1 = 1111;
        final int broadcastId2 = 2222;
        final int broadcastId3 = 3333;
        final int broadcastId4 = 4444;
        final int broadcastId5 = 5555;

        prepareConnectedDeviceGroup();
        startSearchingForSources();

        // Queue two scan requests
        onScanResult(device1, broadcastId1);
        onScanResult(device2, broadcastId2);
        verifyRegisterSyncCalled(device1);

        // Two SyncRequest queued but not synced yet
        assertThat(mBassClientService.getActiveSyncedSources()).isEmpty();
        assertThat(mBassClientService.getDeviceForSyncHandle(handle1)).isNull();
        assertThat(mBassClientService.getDeviceForSyncHandle(handle2)).isNull();
        assertThat(mBassClientService.getDeviceForSyncHandle(handle3)).isNull();
        assertThat(mBassClientService.getDeviceForSyncHandle(handle4)).isNull();
        assertThat(mBassClientService.getDeviceForSyncHandle(handle5)).isNull();
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle1))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle2))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle3))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle4))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle5))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);

        // Sync 1
        onSyncEstablished(device1, handle1);
        verifyRegisterSyncCalled(device2);
        assertThat(mBassClientService.getActiveSyncedSources()).hasSize(1);
        assertThat(mBassClientService.getActiveSyncedSources()).containsExactly(handle1);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle1)).isEqualTo(device1);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle2)).isNull();
        assertThat(mBassClientService.getDeviceForSyncHandle(handle3)).isNull();
        assertThat(mBassClientService.getDeviceForSyncHandle(handle4)).isNull();
        assertThat(mBassClientService.getDeviceForSyncHandle(handle5)).isNull();
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle1)).isEqualTo(broadcastId1);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle2))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle3))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle4))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle5))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);

        // Sync 2
        onSyncEstablished(device2, handle2);
        assertThat(mBassClientService.getActiveSyncedSources()).hasSize(2);
        assertThat(mBassClientService.getActiveSyncedSources())
                .containsExactly(handle1, handle2)
                .inOrder();
        assertThat(mBassClientService.getDeviceForSyncHandle(handle1)).isEqualTo(device1);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle2)).isEqualTo(device2);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle3)).isNull();
        assertThat(mBassClientService.getDeviceForSyncHandle(handle4)).isNull();
        assertThat(mBassClientService.getDeviceForSyncHandle(handle5)).isNull();
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle1)).isEqualTo(broadcastId1);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle2)).isEqualTo(broadcastId2);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle3))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle4))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle5))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);

        // Scan and sync 3
        onScanResult(device3, broadcastId3);
        verifyRegisterSyncCalled(device3);
        onSyncEstablished(device3, handle3);
        assertThat(mBassClientService.getActiveSyncedSources()).hasSize(3);
        assertThat(mBassClientService.getActiveSyncedSources())
                .containsExactly(handle1, handle2, handle3)
                .inOrder();
        assertThat(mBassClientService.getDeviceForSyncHandle(handle2)).isEqualTo(device2);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle3)).isEqualTo(device3);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle4)).isNull();
        assertThat(mBassClientService.getDeviceForSyncHandle(handle5)).isNull();
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle1)).isEqualTo(broadcastId1);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle2)).isEqualTo(broadcastId2);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle3)).isEqualTo(broadcastId3);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle4))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle5))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);

        // Scan and sync 4
        onScanResult(device4, broadcastId4);
        verifyRegisterSyncCalled(device4);
        onSyncEstablished(device4, handle4);
        assertThat(mBassClientService.getActiveSyncedSources()).hasSize(4);
        assertThat(mBassClientService.getActiveSyncedSources())
                .containsExactly(handle1, handle2, handle3, handle4)
                .inOrder();
        assertThat(mBassClientService.getDeviceForSyncHandle(handle1)).isEqualTo(device1);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle2)).isEqualTo(device2);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle3)).isEqualTo(device3);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle4)).isEqualTo(device4);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle5)).isNull();
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle1)).isEqualTo(broadcastId1);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle2)).isEqualTo(broadcastId2);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle3)).isEqualTo(broadcastId3);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle4)).isEqualTo(broadcastId4);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle5))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);

        // Scan 5 cause removing first element
        onScanResult(device5, broadcastId5);
        verifyUnregisterSyncCalled();
        verifyRegisterSyncCalled(device5);
        assertThat(mBassClientService.getActiveSyncedSources()).hasSize(3);
        assertThat(mBassClientService.getActiveSyncedSources())
                .containsExactly(handle2, handle3, handle4)
                .inOrder();
        assertThat(mBassClientService.getDeviceForSyncHandle(handle1)).isNull();
        assertThat(mBassClientService.getDeviceForSyncHandle(handle2)).isEqualTo(device2);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle3)).isEqualTo(device3);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle4)).isEqualTo(device4);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle5)).isNull();
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle1))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle2)).isEqualTo(broadcastId2);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle3)).isEqualTo(broadcastId3);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle4)).isEqualTo(broadcastId4);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle5))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);

        // Sync 5
        onSyncEstablished(device5, handle5);
        expect.that(mBassClientService.getActiveSyncedSources().size()).isEqualTo(4);
        expect.that(mBassClientService.getActiveSyncedSources())
                .containsExactly(handle2, handle3, handle4, handle5)
                .inOrder();
        expect.that(mBassClientService.getDeviceForSyncHandle(handle1)).isNull();
        expect.that(mBassClientService.getDeviceForSyncHandle(handle2)).isEqualTo(device2);
        expect.that(mBassClientService.getDeviceForSyncHandle(handle3)).isEqualTo(device3);
        expect.that(mBassClientService.getDeviceForSyncHandle(handle4)).isEqualTo(device4);
        expect.that(mBassClientService.getDeviceForSyncHandle(handle5)).isEqualTo(device5);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(handle1))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(handle2))
                .isEqualTo(broadcastId2);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(handle3))
                .isEqualTo(broadcastId3);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(handle4))
                .isEqualTo(broadcastId4);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(handle5))
                .isEqualTo(broadcastId5);
    }

    @Test
    public void testSelectSource_removeAfterMaxLimit_notSyncedToAnySink() {
        final BluetoothDevice device1 = getRealDevice("00:11:22:33:44:11", ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device2 = getRealDevice("00:11:22:33:44:22", ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device3 = getRealDevice("00:11:22:33:44:33", ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device4 = getRealDevice("00:11:22:33:44:44", ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device5 = getRealDevice("00:11:22:33:44:55", ADDRESS_TYPE_RANDOM);
        final int handle1 = 0;
        final int handle2 = 1;
        final int handle3 = 2;
        final int handle4 = 3;
        final int handle5 = 4;
        final int broadcastId1 = 1111;
        final int broadcastId2 = 2222;
        final int broadcastId3 = 3333;
        final int broadcastId4 = 4444;
        final int broadcastId5 = 5555;

        prepareConnectedDeviceGroup();
        startSearchingForSources();

        // Scan and sync 4 sources
        onScanResult(device1, broadcastId1);
        onSyncEstablished(device1, handle1);
        onScanResult(device2, broadcastId2);
        onSyncEstablished(device2, handle2);
        onScanResult(device3, broadcastId3);
        onSyncEstablished(device3, handle3);
        onScanResult(device4, broadcastId4);
        onSyncEstablished(device4, handle4);
        assertThat(mBassClientService.getActiveSyncedSources()).hasSize(4);
        assertThat(mBassClientService.getActiveSyncedSources())
                .containsExactly(handle1, handle2, handle3, handle4)
                .inOrder();
        assertThat(mBassClientService.getDeviceForSyncHandle(handle1)).isEqualTo(device1);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle2)).isEqualTo(device2);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle3)).isEqualTo(device3);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle4)).isEqualTo(device4);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle5)).isNull();
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle1)).isEqualTo(broadcastId1);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle2)).isEqualTo(broadcastId2);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle3)).isEqualTo(broadcastId3);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle4)).isEqualTo(broadcastId4);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle5))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);

        // Add source 1
        BluetoothLeBroadcastMetadata meta = createBroadcastMetadata(broadcastId1);
        addSourceAndVerify(meta);
        injectRemoteSourceStateSourceAdded(meta, /* isPaSynced */ true, /* isBisSynced */ true);

        // Scan 5 cause removing first element which is not synced to any sink
        onScanResult(device5, broadcastId5);
        verifyUnregisterSyncCalled();
        verifyRegisterSyncCalled(device5);
        expect.that(mBassClientService.getActiveSyncedSources().size()).isEqualTo(3);
        expect.that(mBassClientService.getActiveSyncedSources())
                .containsExactly(handle1, handle3, handle4)
                .inOrder();
        expect.that(mBassClientService.getDeviceForSyncHandle(handle1)).isEqualTo(device1);
        expect.that(mBassClientService.getDeviceForSyncHandle(handle2)).isNull();
        expect.that(mBassClientService.getDeviceForSyncHandle(handle3)).isEqualTo(device3);
        expect.that(mBassClientService.getDeviceForSyncHandle(handle4)).isEqualTo(device4);
        expect.that(mBassClientService.getDeviceForSyncHandle(handle5)).isNull();
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(handle1))
                .isEqualTo(broadcastId1);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(handle2))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(handle3))
                .isEqualTo(broadcastId3);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(handle4))
                .isEqualTo(broadcastId4);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(handle5))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
    }

    @Test
    public void testSelectSource_removeAfterMaxLimit_firstIfAllSyncedToSinks() {
        final BluetoothDevice device1 = getRealDevice("00:11:22:33:44:11", ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device2 = getRealDevice("00:11:22:33:44:22", ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device3 = getRealDevice("00:11:22:33:44:33", ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device4 = getRealDevice("00:11:22:33:44:44", ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device5 = getRealDevice("00:11:22:33:44:55", ADDRESS_TYPE_RANDOM);
        final int handle1 = 0;
        final int handle2 = 1;
        final int handle3 = 2;
        final int handle4 = 3;
        final int handle5 = 4;
        final int broadcastId1 = 1111;
        final int broadcastId2 = 2222;
        final int broadcastId3 = 3333;
        final int broadcastId4 = 4444;
        final int broadcastId5 = 5555;

        prepareConnectedDeviceGroup();
        startSearchingForSources();

        // Scan and sync 4 sources
        onScanResult(device1, broadcastId1);
        onSyncEstablished(device1, handle1);
        onScanResult(device2, broadcastId2);
        onSyncEstablished(device2, handle2);
        onScanResult(device3, broadcastId3);
        onSyncEstablished(device3, handle3);
        onScanResult(device4, broadcastId4);
        onSyncEstablished(device4, handle4);
        assertThat(mBassClientService.getActiveSyncedSources()).hasSize(4);
        assertThat(mBassClientService.getActiveSyncedSources())
                .containsExactly(handle1, handle2, handle3, handle4)
                .inOrder();
        assertThat(mBassClientService.getDeviceForSyncHandle(handle1)).isEqualTo(device1);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle2)).isEqualTo(device2);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle3)).isEqualTo(device3);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle4)).isEqualTo(device4);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle5)).isNull();
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle1)).isEqualTo(broadcastId1);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle2)).isEqualTo(broadcastId2);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle3)).isEqualTo(broadcastId3);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle4)).isEqualTo(broadcastId4);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle5))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);

        // Fake add 4 sources
        BluetoothLeBroadcastMetadata meta1 = createBroadcastMetadata(broadcastId1);
        BluetoothLeBroadcastMetadata meta2 = createBroadcastMetadata(broadcastId2);
        BluetoothLeBroadcastMetadata meta3 = createBroadcastMetadata(broadcastId3);
        BluetoothLeBroadcastMetadata meta4 = createBroadcastMetadata(broadcastId4);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            injectRemoteSourceStateSourceAdded(
                    sm,
                    meta1,
                    TEST_SOURCE_ID + 1,
                    BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                    meta1.isEncrypted()
                            ? BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING
                            : BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                    null,
                    0L);
            injectRemoteSourceStateSourceAdded(
                    sm,
                    meta2,
                    TEST_SOURCE_ID + 2,
                    BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                    meta2.isEncrypted()
                            ? BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING
                            : BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                    null,
                    0L);
            injectRemoteSourceStateSourceAdded(
                    sm,
                    meta3,
                    TEST_SOURCE_ID + 3,
                    BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                    meta3.isEncrypted()
                            ? BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING
                            : BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                    null,
                    0L);
            injectRemoteSourceStateSourceAdded(
                    sm,
                    meta4,
                    TEST_SOURCE_ID + 4,
                    BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                    meta4.isEncrypted()
                            ? BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING
                            : BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                    null,
                    0L);
        }

        // Scan 5 cause removing first element which is not synced to any sink or first at all
        onScanResult(device5, broadcastId5);
        verifyUnregisterSyncCalled();
        verifyRegisterSyncCalled(device5);
        expect.that(mBassClientService.getActiveSyncedSources().size()).isEqualTo(3);
        expect.that(mBassClientService.getActiveSyncedSources())
                .containsExactly(handle2, handle3, handle4)
                .inOrder();
        expect.that(mBassClientService.getDeviceForSyncHandle(handle1)).isNull();
        expect.that(mBassClientService.getDeviceForSyncHandle(handle2)).isEqualTo(device2);
        expect.that(mBassClientService.getDeviceForSyncHandle(handle3)).isEqualTo(device3);
        expect.that(mBassClientService.getDeviceForSyncHandle(handle4)).isEqualTo(device4);
        expect.that(mBassClientService.getDeviceForSyncHandle(handle5)).isNull();
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(handle1))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(handle2))
                .isEqualTo(broadcastId2);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(handle3))
                .isEqualTo(broadcastId3);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(handle4))
                .isEqualTo(broadcastId4);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(handle5))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
    }

    @Test
    public void testAddSourceToUnsyncedSource_causesSyncBeforeAddingSource() {
        final BluetoothDevice device1 = getRealDevice("00:11:22:33:44:11", ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device2 = getRealDevice("00:11:22:33:44:22", ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device3 = getRealDevice("00:11:22:33:44:33", ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device4 = getRealDevice("00:11:22:33:44:44", ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device5 = getRealDevice("00:11:22:33:44:55", ADDRESS_TYPE_RANDOM);
        final int handle1 = 0;
        final int handle2 = 1;
        final int handle3 = 2;
        final int handle4 = 3;
        final int handle5 = 4;
        final int broadcastId1 = 1111;
        final int broadcastId2 = 2222;
        final int broadcastId3 = 3333;
        final int broadcastId4 = 4444;
        final int broadcastId5 = 5555;

        prepareConnectedDeviceGroup();
        startSearchingForSources();

        // Scan and sync 5 sources cause removing 1 synced element
        onScanResult(device1, broadcastId1);
        verifyRegisterSyncCalled(device1);
        onSyncEstablished(device1, handle1);
        onScanResult(device2, broadcastId2);
        verifyRegisterSyncCalled(device2);
        onSyncEstablished(device2, handle2);
        onScanResult(device3, broadcastId3);
        verifyRegisterSyncCalled(device3);
        onSyncEstablished(device3, handle3);
        onScanResult(device4, broadcastId4);
        verifyRegisterSyncCalled(device4);
        onSyncEstablished(device4, handle4);
        onScanResult(device5, broadcastId5);
        verifyUnregisterSyncCalled();
        verifyRegisterSyncCalled(device5);
        onSyncEstablished(device5, handle5);
        assertThat(mBassClientService.getActiveSyncedSources()).hasSize(4);
        assertThat(mBassClientService.getActiveSyncedSources())
                .containsExactly(handle2, handle3, handle4, handle5)
                .inOrder();
        assertThat(mBassClientService.getDeviceForSyncHandle(handle1)).isNull();
        assertThat(mBassClientService.getDeviceForSyncHandle(handle2)).isEqualTo(device2);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle3)).isEqualTo(device3);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle4)).isEqualTo(device4);
        assertThat(mBassClientService.getDeviceForSyncHandle(handle5)).isEqualTo(device5);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle1))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle2)).isEqualTo(broadcastId2);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle3)).isEqualTo(broadcastId3);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle4)).isEqualTo(broadcastId4);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(handle5)).isEqualTo(broadcastId5);

        BluetoothLeBroadcastMetadata.Builder builder =
                new BluetoothLeBroadcastMetadata.Builder()
                        .setEncrypted(false)
                        .setSourceDevice(device1, ADDRESS_TYPE_RANDOM)
                        .setSourceAdvertisingSid(TEST_ADVERTISER_SID)
                        .setBroadcastId(broadcastId1)
                        .setBroadcastCode(null)
                        .setPaSyncInterval(TEST_PA_SYNC_INTERVAL)
                        .setPresentationDelayMicros(TEST_PRESENTATION_DELAY_MS);
        // builder expect at least one subgroup
        builder.addSubgroup(createBroadcastSubgroup());
        BluetoothLeBroadcastMetadata meta = builder.build();

        // Add source to unsynced broadcast, causes synchronization first
        mBassClientService.addSource(mCurrentDevice, meta, /* isGroupOp */ true);
        verifyRegisterSyncCalled(device1);

        // Verify not getting ADD_BCAST_SOURCE message before source sync
        assertThat(mStateMachines).hasSize(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            verify(sm, never()).sendMessage(any());
        }

        // Source synced which cause execute pending add source
        onSyncEstablished(device1, handle1);

        expect.that(mBassClientService.getActiveSyncedSources().size()).isEqualTo(4);
        expect.that(mBassClientService.getActiveSyncedSources())
                .containsExactly(handle3, handle4, handle5, handle1)
                .inOrder();
        expect.that(mBassClientService.getDeviceForSyncHandle(handle1)).isEqualTo(device1);
        expect.that(mBassClientService.getDeviceForSyncHandle(handle2)).isNull();
        expect.that(mBassClientService.getDeviceForSyncHandle(handle3)).isEqualTo(device3);
        expect.that(mBassClientService.getDeviceForSyncHandle(handle4)).isEqualTo(device4);
        expect.that(mBassClientService.getDeviceForSyncHandle(handle5)).isEqualTo(device5);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(handle1))
                .isEqualTo(broadcastId1);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(handle2))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(handle3))
                .isEqualTo(broadcastId3);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(handle4))
                .isEqualTo(broadcastId4);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(handle5))
                .isEqualTo(broadcastId5);

        // Verify all group members getting ADD_BCAST_SOURCE message
        expect.that(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());

            Message msg =
                    messageCaptor.getAllValues().stream()
                            .filter(
                                    m ->
                                            (m.what == BassClientStateMachine.ADD_BCAST_SOURCE)
                                                    && (m.obj == meta))
                            .findFirst()
                            .orElse(null);
            expect.that(msg).isNotNull();
        }
    }

    @Test
    public void testAddSourceForExternalBroadcast_triggerSetContextMask() {
        final int testGroupId = 1;
        prepareConnectedDeviceGroup();

        // Sync and stop searching to left only cache for broadcast
        prepareSyncToSourceAndVerify();
        mBassClientService.stopSearchingForSources();
        assertThat(mBassClientService.getActiveSyncedSources()).isEmpty();

        // Device as part of active unicast group
        doReturn(testGroupId).when(mLeAudioService).getActiveGroupId();
        doReturn(new ArrayList<BluetoothDevice>(Arrays.asList(mCurrentDevice)))
                .when(mLeAudioService)
                .getActiveDevices();

        // Add source to unsynced broadcast
        mBassClientService.addSource(mCurrentDevice, mBroadcastMetadata1, /* isGroupOp */ true);
        verifyBackgroundSearchStarted();
        verifyRegisterSyncCalled(mSourceDevice);

        // Verify setting allowed context mask is triggered
        verify(mLeAudioService)
                .setActiveGroupAllowedContextMask(
                        eq(
                                BluetoothLeAudio.CONTEXTS_ALL
                                        & ~(BluetoothLeAudio.CONTEXT_TYPE_UNSPECIFIED
                                                | BluetoothLeAudio.CONTEXT_TYPE_SOUND_EFFECTS)),
                        eq(BluetoothLeAudio.CONTEXTS_ALL));

        // Sync to broadcast
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        assertThat(mBassClientService.isAnySearchInProgress()).isFalse();

        // Verify all group members getting ADD_BCAST_SOURCE message
        assertThat(mStateMachines).hasSize(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());

            Message msg =
                    messageCaptor.getAllValues().stream()
                            .filter(
                                    m ->
                                            (m.what == BassClientStateMachine.ADD_BCAST_SOURCE)
                                                    && (m.obj == mBroadcastMetadata1))
                            .findFirst()
                            .orElse(null);
            assertThat(msg).isNotNull();
        }

        mBassClientService
                .getCallbacks()
                .notifySourceAddFailed(
                        mCurrentDevice, mBroadcastMetadata1, BluetoothStatusCodes.ERROR_UNKNOWN);
        mLooper.dispatchAll();
        if (Flags.leaudioBroadcastAlwaysUseBackgroundScanner()) {
            verifyUnregisterSyncCalled();
        }

        // Verify resetting allowed context mask is triggered when switching source failed
        verify(mLeAudioService)
                .setActiveGroupAllowedContextMask(
                        eq(BluetoothLeAudio.CONTEXTS_ALL), eq(BluetoothLeAudio.CONTEXTS_ALL));
    }

    @Test
    public void testSelectSource_orderOfSyncRegisteringByPriorityAndRssi() {
        final BluetoothDevice device1 = getRealDevice("00:11:22:33:44:11", ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device2 = getRealDevice("00:11:22:33:44:22", ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device3 = getRealDevice("00:11:22:33:44:33", ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device4 = getRealDevice("00:11:22:33:44:44", ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device5 = getRealDevice("00:11:22:33:44:55", ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device6 = getRealDevice("00:11:22:33:44:66", ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device7 = getRealDevice("00:11:22:33:44:77", ADDRESS_TYPE_RANDOM);
        final int broadcastId1 = 1111;
        final int broadcastId2 = 2222;
        final int broadcastId3 = 3333;
        final int broadcastId4 = 4444;
        final int broadcastId5 = 5555;
        final int broadcastId6 = 6666;
        final int broadcastId7 = 7777;

        byte[] scanRecord1 = getScanRecord(broadcastId1);
        byte[] scanRecord2 = getScanRecord(broadcastId2);
        byte[] scanRecord3 = getScanRecord(broadcastId3);
        byte[] scanRecord4 = getScanRecord(broadcastId4);
        byte[] scanRecord5 = getScanRecord(broadcastId5);
        byte[] scanRecord6 = getScanRecord(broadcastId6);
        byte[] scanRecord7 = getScanRecord(broadcastId7);

        ScanResult scanResult1 =
                new ScanResult(
                        device1,
                        0,
                        0,
                        0,
                        0,
                        0,
                        TEST_RSSI,
                        0,
                        ScanRecord.parseFromBytes(scanRecord1),
                        0);
        ScanResult scanResult2 =
                new ScanResult(
                        device2,
                        0,
                        0,
                        0,
                        0,
                        0,
                        TEST_RSSI + 3,
                        0,
                        ScanRecord.parseFromBytes(scanRecord2),
                        0);
        ScanResult scanResult3 =
                new ScanResult(
                        device3,
                        0,
                        0,
                        0,
                        0,
                        0,
                        TEST_RSSI + 7,
                        0,
                        ScanRecord.parseFromBytes(scanRecord3),
                        0);
        ScanResult scanResult4 =
                new ScanResult(
                        device4,
                        0,
                        0,
                        0,
                        0,
                        0,
                        TEST_RSSI + 5,
                        0,
                        ScanRecord.parseFromBytes(scanRecord4),
                        0);
        ScanResult scanResult5 =
                new ScanResult(
                        device5,
                        0,
                        0,
                        0,
                        0,
                        0,
                        TEST_RSSI + 2,
                        0,
                        ScanRecord.parseFromBytes(scanRecord5),
                        0);
        ScanResult scanResult6 =
                new ScanResult(
                        device6,
                        0,
                        0,
                        0,
                        0,
                        0,
                        TEST_RSSI + 6,
                        0,
                        ScanRecord.parseFromBytes(scanRecord6),
                        0);
        ScanResult scanResult7 =
                new ScanResult(
                        device7,
                        0,
                        0,
                        0,
                        0,
                        0,
                        TEST_RSSI + 4,
                        0,
                        ScanRecord.parseFromBytes(scanRecord7),
                        0);

        prepareConnectedDeviceGroup();
        startSearchingForSources();

        // Added and executed immediately as no other in queue
        generateScanResult(scanResult1);
        // Added to queue with worst rssi
        generateScanResult(scanResult2);
        // Added to queue with best rssi
        generateScanResult(scanResult3);
        // Added to queue with medium rssi
        generateScanResult(scanResult4);
        // Added to queue with worst rssi (increase priority after all)
        generateScanResult(scanResult5);
        // Added to queue with best rssi (increase priority after all)
        generateScanResult(scanResult6);
        // Added to queue with medium rssi (increase priority after all)
        generateScanResult(scanResult7);

        // Increase priority of last 3 of them
        mBassClientService.addSelectSourceRequest(broadcastId5, /* hasPriority */ true);
        mBassClientService.addSelectSourceRequest(broadcastId6, /* hasPriority */ true);
        mBassClientService.addSelectSourceRequest(broadcastId7, /* hasPriority */ true);

        verifyRegisterSyncCalled(device1);

        onSyncEstablished(device1, TEST_SYNC_HANDLE);
        verifyRegisterSyncCalled(device6);

        onSyncEstablished(device6, TEST_SYNC_HANDLE_2);
        verifyRegisterSyncCalled(device7);

        onSyncEstablished(device7, TEST_SYNC_HANDLE + 2);
        verifyRegisterSyncCalled(device5);

        onSyncEstablished(device5, TEST_SYNC_HANDLE + 3);
        verifyRegisterSyncCalled(device3);

        onSyncEstablished(device3, TEST_SYNC_HANDLE + 4);
        verifyRegisterSyncCalled(device4);

        onSyncEstablished(device4, TEST_SYNC_HANDLE + 5);
        verifyRegisterSyncCalled(device2);
    }

    @Test
    public void testSelectSource_orderOfSyncRegisteringByRssiAndFailsCounter() {
        final BluetoothDevice device1 = getRealDevice("00:11:22:33:44:11", ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device2 = getRealDevice("00:11:22:33:44:22", ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device3 = getRealDevice("00:11:22:33:44:33", ADDRESS_TYPE_RANDOM);
        final int broadcastId1 = 1111;
        final int broadcastId2 = 2222;
        final int broadcastId3 = 3333;

        byte[] scanRecord1 = getScanRecord(broadcastId1);
        byte[] scanRecord2 = getScanRecord(broadcastId2);
        byte[] scanRecord3 = getScanRecord(broadcastId3);

        ScanResult scanResult1 =
                new ScanResult(
                        device1,
                        0,
                        0,
                        0,
                        0,
                        0,
                        TEST_RSSI + 10,
                        0,
                        ScanRecord.parseFromBytes(scanRecord1),
                        0);
        ScanResult scanResult2 =
                new ScanResult(
                        device2,
                        0,
                        0,
                        0,
                        0,
                        0,
                        TEST_RSSI + 9,
                        0,
                        ScanRecord.parseFromBytes(scanRecord2),
                        0);
        ScanResult scanResult3 =
                new ScanResult(
                        device3,
                        0,
                        0,
                        0,
                        0,
                        0,
                        TEST_RSSI,
                        0,
                        ScanRecord.parseFromBytes(scanRecord3),
                        0);

        prepareConnectedDeviceGroup();
        startSearchingForSources();

        // Test using onSyncEstablishedFailed
        // Added and executed immediately as no other in queue, high rssi
        generateScanResult(scanResult1);
        // Added to queue, medium rssi
        generateScanResult(scanResult2);
        // Added to queue, low rssi
        generateScanResult(scanResult3);

        verifyRegisterSyncCalled(device1);

        onSyncEstablishedFailed(device1, TEST_SYNC_HANDLE);
        verifyRegisterSyncCalled(device2);

        // Added to queue again, high rssi
        generateScanResult(scanResult1);

        onSyncEstablishedFailed(device2, TEST_SYNC_HANDLE_2);
        verifyRegisterSyncCalled(device3);

        onSyncEstablished(device3, TEST_SYNC_HANDLE + 2);
        verifyRegisterSyncCalled(device1);

        // Restart searching clears the mSyncFailureCounter
        mBassClientService.stopSearchingForSources();
        mInOrderScanController.verify(mScanController, times(2)).unregisterSync(any());
        startSearchingForSources();

        // Test using onSyncLost
        // Added and executed immediately as no other in queue, high rssi
        generateScanResult(scanResult1);
        // Added to queue, medium rssi
        generateScanResult(scanResult2);
        // Added to queue, low rssi
        generateScanResult(scanResult3);

        verifyRegisterSyncCalled(device1);

        onSyncEstablished(device1, TEST_SYNC_HANDLE);
        verifyRegisterSyncCalled(device2);
        onSyncLost();
        checkAndDispatchTimeout(broadcastId1, BassClientService.MESSAGE_SYNC_LOST_TIMEOUT);

        // Added to queue again, high rssi
        generateScanResult(scanResult1);

        onSyncEstablished(device2, TEST_SYNC_HANDLE_2);
        verifyRegisterSyncCalled(device3);
    }

    @Test
    public void testPeriodicAdvertisementResultMap_updateGetAndModifyNotifiedFlag() {
        final String testBroadcastName = "Test";
        final int testSyncHandle = 1;
        final int testBroadcastId = 42;
        final int testBroadcastIdInvalid = 43;
        final int testAdvertiserSid = 1234;
        final int testAdvInterval = 100;
        final int testRssi = 77;

        // mock the update in selectSource
        mBassClientService.updateSyncHandleForBroadcastId(
                BassConstants.PENDING_SYNC_HANDLE, testBroadcastId);
        mBassClientService.updatePeriodicAdvertisementResultMap(
                mSourceDevice,
                BassConstants.PENDING_SYNC_HANDLE,
                BassConstants.INVALID_ADV_SID,
                testAdvInterval,
                testBroadcastId,
                testRssi,
                null,
                testBroadcastName);

        // mock the update in onSyncEstablished
        mBassClientService.updatePeriodicAdvertisementResultMap(
                mSourceDevice,
                testSyncHandle,
                testAdvertiserSid,
                BassConstants.INVALID_ADV_INTERVAL,
                LeAudioConstants.INVALID_BROADCAST_ID,
                BluetoothLeBroadcastMetadata.RSSI_UNKNOWN,
                null,
                null);

        assertThat(
                        mBassClientService.getPeriodicAdvertisementResult(
                                mSourceDevice, testBroadcastIdInvalid))
                .isNull();
        PeriodicAdvertisementResult paResult =
                mBassClientService.getPeriodicAdvertisementResult(mSourceDevice, testBroadcastId);
        assertThat(paResult.getDevice().getAddressType()).isEqualTo(ADDRESS_TYPE_RANDOM);
        assertThat(paResult.getSyncHandle()).isEqualTo(testSyncHandle);
        assertThat(paResult.getAdvSid()).isEqualTo(testAdvertiserSid);
        assertThat(paResult.getAdvInterval()).isEqualTo(testAdvInterval);
        assertThat(paResult.getBroadcastName()).isEqualTo(testBroadcastName);

        // validate modify notified flag
        paResult.setNotified(true);
        assertThat(paResult.isNotified()).isTrue();
        mBassClientService.clearNotifiedFlags();
        assertThat(paResult.isNotified()).isFalse();
    }

    @Test
    public void testPeriodicAdvertisementResultMap_syncEstablishedOnTheSameSyncHandle() {
        final String testBroadcastName1 = "Test1";
        final String testBroadcastName2 = "Test2";
        final int testSyncHandle = 1;
        final int testBroadcastId1 = 42;
        final int testBroadcastId2 = 43;
        final int testRssi1 = 51;
        final int testRssi2 = 52;
        final int testAdvertiserSid1 = 1234;
        final int testAdvertiserSid2 = 2345;
        final int testAdvInterval1 = 100;
        final int testAdvInterval2 = 200;

        // mock the update in selectSource
        mBassClientService.updateSyncHandleForBroadcastId(
                BassConstants.PENDING_SYNC_HANDLE, testBroadcastId1);
        mBassClientService.updatePeriodicAdvertisementResultMap(
                mSourceDevice,
                BassConstants.PENDING_SYNC_HANDLE,
                BassConstants.INVALID_ADV_SID,
                testAdvInterval1,
                testBroadcastId1,
                testRssi1,
                null,
                testBroadcastName1);

        // mock the update in onSyncEstablished
        mBassClientService.updatePeriodicAdvertisementResultMap(
                mSourceDevice,
                testSyncHandle,
                testAdvertiserSid1,
                BassConstants.INVALID_ADV_INTERVAL,
                LeAudioConstants.INVALID_BROADCAST_ID,
                BluetoothLeBroadcastMetadata.RSSI_UNKNOWN,
                null,
                null);

        assertThat(
                        mBassClientService.getPeriodicAdvertisementResult(
                                mSourceDevice, testBroadcastId2))
                .isNull();
        PeriodicAdvertisementResult paResult =
                mBassClientService.getPeriodicAdvertisementResult(mSourceDevice, testBroadcastId1);
        assertThat(paResult.getDevice().getAddressType()).isEqualTo(ADDRESS_TYPE_RANDOM);
        assertThat(paResult.getSyncHandle()).isEqualTo(testSyncHandle);
        assertThat(paResult.getAdvSid()).isEqualTo(testAdvertiserSid1);
        assertThat(paResult.getAdvInterval()).isEqualTo(testAdvInterval1);
        assertThat(paResult.getRssi()).isEqualTo(testRssi1);
        assertThat(paResult.getBroadcastName()).isEqualTo(testBroadcastName1);

        // mock the update in selectSource
        mBassClientService.updateSyncHandleForBroadcastId(
                BassConstants.PENDING_SYNC_HANDLE, testBroadcastId2);
        mBassClientService.updatePeriodicAdvertisementResultMap(
                mSourceDevice,
                BassConstants.PENDING_SYNC_HANDLE,
                BassConstants.INVALID_ADV_SID,
                testAdvInterval2,
                testBroadcastId2,
                testRssi2,
                null,
                testBroadcastName2);

        // mock the update in onSyncEstablished
        mBassClientService.updatePeriodicAdvertisementResultMap(
                mSourceDevice,
                testSyncHandle,
                testAdvertiserSid2,
                BassConstants.INVALID_ADV_INTERVAL,
                LeAudioConstants.INVALID_BROADCAST_ID,
                BluetoothLeBroadcastMetadata.RSSI_UNKNOWN,
                null,
                null);

        expect.that(
                        mBassClientService.getPeriodicAdvertisementResult(
                                mSourceDevice, testBroadcastId1))
                .isNull();
        paResult =
                mBassClientService.getPeriodicAdvertisementResult(mSourceDevice, testBroadcastId2);
        expect.that(paResult.getDevice().getAddressType()).isEqualTo(ADDRESS_TYPE_RANDOM);
        expect.that(paResult.getSyncHandle()).isEqualTo(testSyncHandle);
        expect.that(paResult.getAdvSid()).isEqualTo(testAdvertiserSid2);
        expect.that(paResult.getAdvInterval()).isEqualTo(testAdvInterval2);
        expect.that(paResult.getRssi()).isEqualTo(testRssi2);
        expect.that(paResult.getBroadcastName()).isEqualTo(testBroadcastName2);
    }

    @Test
    public void onSyncEstablished_forSameDevice_doesNotRemoveOtherSyncs() {
        prepareConnectedDeviceGroup();
        startSearchingForSources();

        // Sync to the first broadcast from mSourceDevice
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);

        // Verify the first sync is correctly mapped
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);

        // Sync to a second broadcast from the *same* source device
        onScanResult(mSourceDevice, TEST_BROADCAST_ID_2);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE_2);

        // Verify the second sync is correctly mapped
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE_2))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE_2))
                .isEqualTo(TEST_BROADCAST_ID_2);

        // Verify the first sync is still present
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);
    }

    @Test
    public void testSyncHandleToBroadcastIdMap_getSyncHandleAndGetBroadcastId() {
        final int testSyncHandle = 1;
        final int testSyncHandleInvalid = 2;
        final int testBroadcastId = 42;
        final int testBroadcastIdInvalid = 43;

        prepareConnectedDeviceGroup();
        startSearchingForSources();
        onScanResult(mSourceDevice, testBroadcastId);
        onSyncEstablished(mSourceDevice, testSyncHandle);

        assertThat(mBassClientService.getSyncHandleForBroadcastId(testBroadcastIdInvalid))
                .isEqualTo(BassConstants.INVALID_SYNC_HANDLE);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(testSyncHandleInvalid))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
        assertThat(mBassClientService.getSyncHandleForBroadcastId(testBroadcastId))
                .isEqualTo(testSyncHandle);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(testSyncHandle))
                .isEqualTo(testBroadcastId);
    }

    private void verifyAllGroupMembersGettingUpdateOrAddSource(BluetoothLeBroadcastMetadata meta) {
        assertThat(mStateMachines).hasSize(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());
            long count;

            if (sm.getDevice().equals(mCurrentDevice)) {
                count =
                        messageCaptor.getAllValues().stream()
                                .filter(
                                        m ->
                                                ((m.what
                                                                        == BassClientStateMachine
                                                                                .UPDATE_BCAST_SOURCE)
                                                                && (m.obj.equals(meta))
                                                                && (m.arg1 == TEST_SOURCE_ID)
                                                                && (m.arg2
                                                                        == BassConstants
                                                                                .FLAG_SYNC_PA))
                                                        || ((m.what
                                                                        == BassClientStateMachine
                                                                                .ADD_BCAST_SOURCE)
                                                                && (m.obj.equals(meta)))
                                                        || ((m.what
                                                                        == BassClientStateMachine
                                                                                .SWITCH_BCAST_SOURCE)
                                                                && (m.obj.equals(meta))
                                                                && (m.arg1 == TEST_SOURCE_ID)))
                                .count();
                assertThat(count).isEqualTo(1);
            } else if (sm.getDevice().equals(mCurrentDevice1)) {
                count =
                        messageCaptor.getAllValues().stream()
                                .filter(
                                        m ->
                                                ((m.what
                                                                        == BassClientStateMachine
                                                                                .UPDATE_BCAST_SOURCE)
                                                                && (m.obj.equals(meta))
                                                                && (m.arg1 == TEST_SOURCE_ID + 1)
                                                                && (m.arg2
                                                                        == BassConstants
                                                                                .FLAG_SYNC_PA))
                                                        || ((m.what
                                                                        == BassClientStateMachine
                                                                                .ADD_BCAST_SOURCE)
                                                                && (m.obj.equals(meta)))
                                                        || ((m.what
                                                                        == BassClientStateMachine
                                                                                .SWITCH_BCAST_SOURCE)
                                                                && (m.obj.equals(meta))
                                                                && (m.arg1 == TEST_SOURCE_ID + 1)))
                                .count();
                assertThat(count).isEqualTo(1);
            }
        }
    }

    @Test
    public void testSuspendResumeSourceSynchronization() {
        prepareSynchronizedPair();

        /* Imitate broadcast source stop, sink notify about loosing PA and BIS sync */
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ false);

        /* Unicast would like to stream */
        mBassClientService.cacheSuspendingSources(TEST_BROADCAST_ID);

        mBassClientService.resumeReceiversSourceSynchronization();
        verifyAllGroupMembersGettingUpdateOrAddSource(mBroadcastMetadata1);
    }

    @Test
    public void testHandleUnicastSourceStreamStatusChange() {
        prepareSynchronizedPair();

        /* Unicast would like to stream */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                LeAudioStackEvent.STATUS_LOCAL_STREAM_REQUESTED);

        /* Imitate broadcast source stop, sink notify about loosing PA and BIS sync */
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ false);

        /* Unicast finished streaming */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                LeAudioStackEvent.STATUS_LOCAL_STREAM_SUSPENDED);

        verifyAllGroupMembersGettingUpdateOrAddSource(mBroadcastMetadata1);
    }

    @Test
    public void testHandleUnicastSourceStreamStatusChange_MultipleRequests() {
        prepareSynchronizedPair();

        /* Unicast would like to stream */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                LeAudioStackEvent.STATUS_LOCAL_STREAM_REQUESTED_NO_CONTEXT_VALIDATE);

        /* Imitate broadcast source stop, sink notify about loosing BIS sync */
        verifyModifyMessageAndInjectSourceModified();

        assertThat(mStateMachines).hasSize(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            clearInvocations(sm);
        }
        // Make another stream request with no context validate
        mBassClientService.handleUnicastSourceStreamStatusChange(
                LeAudioStackEvent.STATUS_LOCAL_STREAM_REQUESTED_NO_CONTEXT_VALIDATE);

        // Make another stream request
        mBassClientService.handleUnicastSourceStreamStatusChange(
                LeAudioStackEvent.STATUS_LOCAL_STREAM_REQUESTED);

        /* Unicast finished streaming */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                LeAudioStackEvent.STATUS_LOCAL_STREAM_SUSPENDED);

        // Verify all group members resume with the previous cached source
        for (BassClientStateMachine sm : mStateMachines.values()) {
            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());

            Message msg =
                    messageCaptor.getAllValues().stream()
                            .filter(
                                    m ->
                                            (m.what == BassClientStateMachine.UPDATE_BCAST_SOURCE)
                                                    && (m.obj == mBroadcastMetadata1))
                            .findFirst()
                            .orElse(null);
            expect.that(msg).isNotNull();
        }
    }

    @Test
    public void testIsAnyReceiverActive() {
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();
        addSourceAndVerify(mBroadcastMetadata1);
        injectRemoteSourceStateSourceAdded(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ false);

        List<BluetoothDevice> devices = mBassClientService.getConnectedDevices();
        // Verify isAnyReceiverActive returns false if no PA and no BIS synced
        assertThat(mBassClientService.isAnyReceiverActive(devices)).isFalse();

        // Update receiver state with PA sync
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ true, /* isBisSynced */ false);
        BluetoothDevice invalidDevice = getTestDevice(2);
        // Verify isAnyReceiverActive returns false if invalid device
        expect.that(mBassClientService.isAnyReceiverActive(List.of(invalidDevice))).isFalse();
        // Verify isAnyReceiverActive returns true if PA synced
        expect.that(mBassClientService.isAnyReceiverActive(devices)).isTrue();

        // Update receiver state with PA and BIS sync
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ true, /* isBisSynced */ true);
        // Verify isAnyReceiverActive returns true if PA and BIS synced
        expect.that(mBassClientService.isAnyReceiverActive(devices)).isTrue();

        // Update receiver state with BIS only sync
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ true);
        // Verify isAnyReceiverActive returns true if BIS only synced
        expect.that(mBassClientService.isAnyReceiverActive(devices)).isTrue();
    }

    @Test
    public void testGetSyncedBroadcastSinks() {
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();
        BluetoothLeBroadcastMetadata metaNoBroadcast = createEmptyBroadcastMetadata();

        addSourceAndVerify(mBroadcastMetadata1);
        injectRemoteSourceStateSourceAdded(
                metaNoBroadcast, /* isPaSynced */ true, /* isBisSynced */ false);

        // Verify getSyncedBroadcastSinks returns empty device list if no broadcast ID
        assertThat(mBassClientService.getSyncedBroadcastSinks().isEmpty()).isTrue();
        assertThat(mBassClientService.getSyncedBroadcastSinks(TEST_BROADCAST_ID).isEmpty())
                .isTrue();

        // Update receiver state with broadcast ID
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ true, /* isBisSynced */ false);

        List<BluetoothDevice> activeSinks = mBassClientService.getSyncedBroadcastSinks();
        // Verify getSyncedBroadcastSinks returns correct device list if no BIS synced
        assertThat(activeSinks).hasSize(2);
        assertThat(activeSinks.contains(mCurrentDevice)).isTrue();
        assertThat(activeSinks.contains(mCurrentDevice1)).isTrue();

        activeSinks.clear();
        // Verify getSyncedBroadcastSinks by broadcast id
        activeSinks = mBassClientService.getSyncedBroadcastSinks(TEST_BROADCAST_ID);
        // Verify getSyncedBroadcastSinks returns correct device list if no BIS synced
        assertThat(activeSinks.size()).isEqualTo(2);
        assertThat(activeSinks.contains(mCurrentDevice)).isTrue();
        assertThat(activeSinks.contains(mCurrentDevice1)).isTrue();

        // Update receiver state with BIS sync
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ true, /* isBisSynced */ true);

        // Verify getSyncedBroadcastSinks returns correct device list if BIS synced
        activeSinks = mBassClientService.getSyncedBroadcastSinks();
        expect.that(activeSinks.size()).isEqualTo(2);
        expect.that(activeSinks.contains(mCurrentDevice)).isTrue();
        expect.that(activeSinks.contains(mCurrentDevice1)).isTrue();
    }

    private void prepareTwoSynchronizedDevicesForLocalBroadcast() throws RemoteException {
        doReturn(mBroadcastMetadata1).when(mLeAudioService).getBroadcastMetadata(TEST_BROADCAST_ID);
        prepareConnectedDeviceGroup();
        addSourceAndVerify(mBroadcastMetadata1);
        injectRemoteSourceStateSourceAdded(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ false);
        // verify source id
        mLooper.dispatchAll();
        verify(mCallback, atLeast(1))
                .onSourceAdded(
                        eq(mCurrentDevice),
                        eq(TEST_SOURCE_ID),
                        eq(BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST));
        verify(mCallback, atLeast(1))
                .onSourceAdded(
                        eq(mCurrentDevice1),
                        eq(TEST_SOURCE_ID + 1),
                        eq(BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST));
    }

    @Test
    public void testLocalAddSourceWhenBroadcastIsPlaying() throws RemoteException {
        doReturn(true).when(mLeAudioService).isPlaying(TEST_BROADCAST_ID);
        doReturn(false).when(mLeAudioService).isPaused(TEST_BROADCAST_ID);

        prepareTwoSynchronizedDevicesForLocalBroadcast();
    }

    @Test
    public void testLocalAddSourceWhenBroadcastIsPaused() throws RemoteException {
        doReturn(false).when(mLeAudioService).isPlaying(TEST_BROADCAST_ID);
        doReturn(true).when(mLeAudioService).isPaused(TEST_BROADCAST_ID);

        prepareTwoSynchronizedDevicesForLocalBroadcast();
    }

    @Test
    public void testLocalAddSourceWhenBroadcastIsStopped() throws RemoteException {
        doReturn(false).when(mLeAudioService).isPlaying(TEST_BROADCAST_ID);
        doReturn(false).when(mLeAudioService).isPaused(TEST_BROADCAST_ID);

        doReturn(mBroadcastMetadata1).when(mLeAudioService).getBroadcastMetadata(TEST_BROADCAST_ID);
        prepareConnectedDeviceGroup();
        mBassClientService.addSource(mCurrentDevice, mBroadcastMetadata1, /* isGroupOp */ true);
        mLooper.dispatchAll();
        verify(mCallback, atLeast(1))
                .onSourceAddFailed(
                        eq(mCurrentDevice),
                        eq(mBroadcastMetadata1),
                        eq(BluetoothStatusCodes.ERROR_LOCAL_NOT_ENOUGH_RESOURCES));
    }

    @Test
    public void testSinksDisconnectionWhenBroadcastIsPlaying() throws RemoteException {
        /* Imitate broadcast being active */
        doReturn(true).when(mLeAudioService).isPlaying(TEST_BROADCAST_ID);
        doReturn(false).when(mLeAudioService).isPaused(TEST_BROADCAST_ID);

        prepareTwoSynchronizedDevicesForLocalBroadcast();

        /* Imitate scenario when if there would be broadcast - stop would be called */
        mBassClientService.handleDeviceDisconnection(mCurrentDevice, true);
        mBassClientService.handleDeviceDisconnection(mCurrentDevice1, true);

        verify(mLeAudioService).stopBroadcast(eq(TEST_BROADCAST_ID));
    }

    @Test
    public void testSinksDisconnectionWhenBroadcastIsPaused() throws RemoteException {
        /* Imitate broadcast being active */
        doReturn(false).when(mLeAudioService).isPlaying(TEST_BROADCAST_ID);
        doReturn(true).when(mLeAudioService).isPaused(TEST_BROADCAST_ID);

        prepareTwoSynchronizedDevicesForLocalBroadcast();

        /* Imitate scenario when if there would be broadcast - stop would be called */
        mBassClientService.handleDeviceDisconnection(mCurrentDevice, true);
        mBassClientService.handleDeviceDisconnection(mCurrentDevice1, true);

        verify(mLeAudioService).stopBroadcast(eq(TEST_BROADCAST_ID));
    }

    @Test
    public void testSinksDisconnectionWhenBroadcastIsStopped() throws RemoteException {
        /* Imitate broadcast being active */
        doReturn(true).when(mLeAudioService).isPlaying(TEST_BROADCAST_ID);
        doReturn(false).when(mLeAudioService).isPaused(TEST_BROADCAST_ID);

        prepareTwoSynchronizedDevicesForLocalBroadcast();

        doReturn(false).when(mLeAudioService).isPlaying(TEST_BROADCAST_ID);

        /* Imitate scenario when if there would be broadcast - stop would be called */
        mBassClientService.handleDeviceDisconnection(mCurrentDevice, true);
        mBassClientService.handleDeviceDisconnection(mCurrentDevice1, true);

        verify(mLeAudioService, never()).stopBroadcast(eq(TEST_BROADCAST_ID));
    }

    @Test
    public void testPrivateBroadcastIntentionalDisconnection() throws RemoteException {
        /* Imitate broadcast being active */
        doReturn(true).when(mLeAudioService).isPlaying(TEST_BROADCAST_ID);

        prepareTwoSynchronizedDevicesForLocalBroadcast();

        /* Imitate devices being primary */
        doReturn(true).when(mLeAudioService).isPrimaryDevice(mCurrentDevice);
        doReturn(true).when(mLeAudioService).isPrimaryDevice(mCurrentDevice1);

        /* Imitate device 1/2 disconnection from StateMachine context */
        mBassClientService.handleDeviceDisconnection(mCurrentDevice, true);

        /* After first device disconnection and de-synchronization expect not stopping broadcast */
        verify(mLeAudioService, never()).stopBroadcast(eq(TEST_BROADCAST_ID));

        /* Imitate first device being in disconnected state */
        doReturn(STATE_DISCONNECTED).when(mStateMachines.get(mCurrentDevice)).getConnectionState();

        /* Imitate device 2/2 disconnection from StateMachine context */
        mBassClientService.handleDeviceDisconnection(mCurrentDevice1, true);

        /* After second device disconnection and de-synchronization expect stopping broadcast */
        verify(mLeAudioService).stopBroadcast(eq(TEST_BROADCAST_ID));
    }

    @Test
    public void testPrivateBroadcastUnintentionalDisconnection() throws RemoteException {
        /* Imitate broadcast being active */
        doReturn(true).when(mLeAudioService).isPlaying(TEST_BROADCAST_ID);

        prepareTwoSynchronizedDevicesForLocalBroadcast();

        /* Imitate devices being primary */
        doReturn(true).when(mLeAudioService).isPrimaryDevice(mCurrentDevice);
        doReturn(true).when(mLeAudioService).isPrimaryDevice(mCurrentDevice1);

        /* Imitate device 1/2 disconnection from StateMachine context */
        mBassClientService.handleDeviceDisconnection(mCurrentDevice, false);

        /* After first device disconnection and de-synchronization expect not stopping broadcast */
        verify(mLeAudioService, never()).stopBroadcast(eq(TEST_BROADCAST_ID));

        /* Imitate first device being in disconnected state */
        doReturn(STATE_DISCONNECTED).when(mStateMachines.get(mCurrentDevice)).getConnectionState();

        /* Imitate device 2/2 disconnection from StateMachine context */
        mBassClientService.handleDeviceDisconnection(mCurrentDevice1, false);

        /* After second device disconnection and de-synchronization expect stopping broadcast */
        verify(mLeAudioService).stopBroadcast(eq(TEST_BROADCAST_ID));
    }

    @Test
    public void testAudioSharingIntentionalDisconnection() throws RemoteException {
        /* Imitate broadcast being active */
        doReturn(true).when(mLeAudioService).isPlaying(TEST_BROADCAST_ID);

        prepareTwoSynchronizedDevicesForLocalBroadcast();

        /* Imitate devices being primary */
        doReturn(true).when(mLeAudioService).isPrimaryDevice(mCurrentDevice);
        doReturn(false).when(mLeAudioService).isPrimaryDevice(mCurrentDevice1);

        /* Imitate device 1/2 disconnection from StateMachine context */
        mBassClientService.handleDeviceDisconnection(mCurrentDevice, true);

        /* After first device disconnection and de-synchronization expect stopping broadcast */
        verify(mLeAudioService).stopBroadcast(eq(TEST_BROADCAST_ID));

        /* Imitate first device being in disconnected state */
        doReturn(STATE_DISCONNECTED).when(mStateMachines.get(mCurrentDevice)).getConnectionState();

        /* Imitate device 2/2 disconnection from StateMachine context */
        mBassClientService.handleDeviceDisconnection(mCurrentDevice1, true);

        /* After second device disconnection and de-synchronization expect not stopping broadcast */
        verify(mLeAudioService).stopBroadcast(eq(TEST_BROADCAST_ID));
    }

    @Test
    public void testAudioSharingUnintentionalDisconnection() throws RemoteException {
        /* Imitate broadcast being active */
        doReturn(true).when(mLeAudioService).isPlaying(TEST_BROADCAST_ID);

        prepareTwoSynchronizedDevicesForLocalBroadcast();

        /* Imitate devices being primary */
        doReturn(true).when(mLeAudioService).isPrimaryDevice(mCurrentDevice);
        doReturn(false).when(mLeAudioService).isPrimaryDevice(mCurrentDevice1);

        /* Imitate device 1/2 disconnection from StateMachine context */
        mBassClientService.handleDeviceDisconnection(mCurrentDevice, false);

        /* After first device disconnection and de-synchronization expect not stopping broadcast */
        verify(mLeAudioService, never()).stopBroadcast(eq(TEST_BROADCAST_ID));

        /* Imitate first device being in disconnected state */
        doReturn(STATE_DISCONNECTED).when(mStateMachines.get(mCurrentDevice)).getConnectionState();

        /* Imitate device 2/2 disconnection from StateMachine context */
        mBassClientService.handleDeviceDisconnection(mCurrentDevice1, false);

        /* After second device disconnection and de-synchronization timeout to be fired */
        verify(mLeAudioService, never()).stopBroadcast(eq(TEST_BROADCAST_ID));
    }

    @Test
    public void testNotifyBroadcastStateChangedStopped() throws RemoteException {
        /* Imitate broadcast being active */
        doReturn(true).when(mLeAudioService).isPlaying(TEST_BROADCAST_ID);

        prepareTwoSynchronizedDevicesForLocalBroadcast();

        mBassClientService.notifyBroadcastStateChanged(
                LeAudioStackEvent.BROADCAST_STATE_STOPPED, TEST_BROADCAST_ID);

        /* Imitate scenario when if there would be broadcast - stop would be called */
        mBassClientService.handleDeviceDisconnection(mCurrentDevice, true);
        mBassClientService.handleDeviceDisconnection(mCurrentDevice1, true);

        /* After second device disconnection and de-synchronization expect not calling broadcast to
         * stop due to previous broadcast stream stopped */
        verify(mLeAudioService, never()).stopBroadcast(eq(TEST_BROADCAST_ID));
    }

    @Test
    public void onPeriodicAdvertisingReport_withoutBaseData_cancelActiveSync() {
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();

        byte[] scanRecord =
                new byte[] {
                    0x02,
                    0x01,
                    0x1a, // advertising flags
                    0x05,
                    0x02,
                    0x0b,
                    0x11,
                    0x0a,
                    0x11, // 16 bit service uuids
                    0x04,
                    0x09,
                    0x50,
                    0x65,
                    0x64, // name
                    0x02,
                    0x0A,
                    (byte) 0xec, // tx power level
                    0x05,
                    0x16,
                    0x0b,
                    0x11,
                    0x50,
                    0x64, // service data
                    0x05,
                    (byte) 0xff,
                    (byte) 0xe0,
                    0x00,
                    0x02,
                    0x15, // manufacturer specific data
                    0x03,
                    0x50,
                    0x01,
                    0x02, // an unknown data type won't cause trouble
                };
        PeriodicAdvertisingReport report =
                new PeriodicAdvertisingReport(
                        TEST_SYNC_HANDLE, 0, 0, 0, ScanRecord.parseFromBytes(scanRecord));

        BassClientService.PACallback callback = mBassClientService.new PACallback();

        callback.onPeriodicAdvertisingReport(report);
        callback.onPeriodicAdvertisingReport(report);
        callback.onPeriodicAdvertisingReport(report);
        callback.onPeriodicAdvertisingReport(report);

        // Not canceled, not updated base
        assertThat(mBassClientService.getActiveSyncedSources()).hasSize(1);
        assertThat(mBassClientService.getActiveSyncedSources()).containsExactly(TEST_SYNC_HANDLE);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);
        assertThat(mBassClientService.getBase(TEST_SYNC_HANDLE)).isNull();
        verifyUnregisterSyncNeverCalled();

        callback.onPeriodicAdvertisingReport(report);

        // Canceled, not updated base
        expect.that(mBassClientService.getActiveSyncedSources()).isEmpty();
        expect.that(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE)).isNull();
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
        expect.that(mBassClientService.getBase(TEST_SYNC_HANDLE)).isNull();
        verifyUnregisterSyncCalled();
    }

    @Test
    public void onPeriodicAdvertisingReport_wrongBaseData_cancelActiveSync() {
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();

        byte[] scanRecord =
                new byte[] {
                    (byte) 0x02,
                    (byte) 0x01,
                    (byte) 0x1a, // advertising flags
                    (byte) 0x05,
                    (byte) 0x02,
                    (byte) 0x51,
                    (byte) 0x18,
                    (byte) 0x0a,
                    (byte) 0x11, // 16 bit service uuids
                    (byte) 0x04,
                    (byte) 0x09,
                    (byte) 0x50,
                    (byte) 0x65,
                    (byte) 0x64, // name
                    (byte) 0x02,
                    (byte) 0x0A,
                    (byte) 0xec, // tx power level
                    (byte) 0x19,
                    (byte) 0x16,
                    (byte) 0x51,
                    (byte) 0x18, // service data (base data with 18 bytes)
                    // LEVEL 1
                    (byte) 0x01,
                    (byte) 0x02,
                    (byte) 0x03, // mPresentationDelay
                    (byte) 0x01, // mNumSubGroups
                    // LEVEL 3
                    (byte) 0x01, // mIndex
                    (byte) 0x06, // mCodecSpecificConfigurationLength
                    (byte) 0x05,
                    (byte) 0x03,
                    (byte) 0x01,
                    (byte) 0x00,
                    (byte) 0x00,
                    (byte) 0x00, // mCodecSpecificConfiguration
                    (byte) 0x05,
                    (byte) 0xff,
                    (byte) 0xe0,
                    (byte) 0x00,
                    (byte) 0x02,
                    (byte) 0x15, // manufacturer specific data
                    (byte) 0x03,
                    (byte) 0x50,
                    (byte) 0x01,
                    (byte) 0x02, // an unknown data type won't cause trouble
                };
        PeriodicAdvertisingReport report =
                new PeriodicAdvertisingReport(
                        TEST_SYNC_HANDLE, 0, 0, 0, ScanRecord.parseFromBytes(scanRecord));

        BassClientService.PACallback callback = mBassClientService.new PACallback();

        callback.onPeriodicAdvertisingReport(report);
        callback.onPeriodicAdvertisingReport(report);
        callback.onPeriodicAdvertisingReport(report);
        callback.onPeriodicAdvertisingReport(report);

        // Not canceled, not updated base
        assertThat(mBassClientService.getActiveSyncedSources()).hasSize(1);
        assertThat(mBassClientService.getActiveSyncedSources()).containsExactly(TEST_SYNC_HANDLE);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);
        assertThat(mBassClientService.getBase(TEST_SYNC_HANDLE)).isNull();
        verifyUnregisterSyncNeverCalled();

        callback.onPeriodicAdvertisingReport(report);

        // Canceled, not updated base
        expect.that(mBassClientService.getActiveSyncedSources()).isEmpty();
        expect.that(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE)).isNull();
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
        expect.that(mBassClientService.getBase(TEST_SYNC_HANDLE)).isNull();
        verifyUnregisterSyncCalled();
    }

    @Test
    public void onPeriodicAdvertisingReport_updateBase() {
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();

        onPeriodicAdvertisingReport();

        // Not canceled, updated base
        expect.that(mBassClientService.getActiveSyncedSources().size()).isEqualTo(1);
        expect.that(mBassClientService.getActiveSyncedSources()).containsExactly(TEST_SYNC_HANDLE);
        expect.that(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);
        expect.that(mBassClientService.getBase(TEST_SYNC_HANDLE)).isNotNull();
        verifyUnregisterSyncNeverCalled();
    }

    @Test
    public void onPeriodicAdvertisingReport_updateBaseAfterWrongBaseData() {
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();

        byte[] scanRecordNoBaseData =
                new byte[] {
                    0x02,
                    0x01,
                    0x1a, // advertising flags
                    0x05,
                    0x02,
                    0x0b,
                    0x11,
                    0x0a,
                    0x11, // 16 bit service uuids
                    0x04,
                    0x09,
                    0x50,
                    0x65,
                    0x64, // name
                    0x02,
                    0x0A,
                    (byte) 0xec, // tx power level
                    0x05,
                    0x16,
                    0x0b,
                    0x11,
                    0x50,
                    0x64, // service data
                    0x05,
                    (byte) 0xff,
                    (byte) 0xe0,
                    0x00,
                    0x02,
                    0x15, // manufacturer specific data
                    0x03,
                    0x50,
                    0x01,
                    0x02, // an unknown data type won't cause trouble
                };

        byte[] scanRecordWrongBaseData =
                new byte[] {
                    (byte) 0x02,
                    (byte) 0x01,
                    (byte) 0x1a, // advertising flags
                    (byte) 0x05,
                    (byte) 0x02,
                    (byte) 0x51,
                    (byte) 0x18,
                    (byte) 0x0a,
                    (byte) 0x11, // 16 bit service uuids
                    (byte) 0x04,
                    (byte) 0x09,
                    (byte) 0x50,
                    (byte) 0x65,
                    (byte) 0x64, // name
                    (byte) 0x02,
                    (byte) 0x0A,
                    (byte) 0xec, // tx power level
                    (byte) 0x19,
                    (byte) 0x16,
                    (byte) 0x51,
                    (byte) 0x18, // service data (base data with 18 bytes)
                    // LEVEL 1
                    (byte) 0x01,
                    (byte) 0x02,
                    (byte) 0x03, // mPresentationDelay
                    (byte) 0x01, // mNumSubGroups
                    // LEVEL 3
                    (byte) 0x01, // mIndex
                    (byte) 0x06, // mCodecSpecificConfigurationLength
                    (byte) 0x05,
                    (byte) 0x03,
                    (byte) 0x01,
                    (byte) 0x00,
                    (byte) 0x00,
                    (byte) 0x00, // mCodecSpecificConfiguration
                    (byte) 0x05,
                    (byte) 0xff,
                    (byte) 0xe0,
                    (byte) 0x00,
                    (byte) 0x02,
                    (byte) 0x15, // manufacturer specific data
                    (byte) 0x03,
                    (byte) 0x50,
                    (byte) 0x01,
                    (byte) 0x02, // an unknown data type won't cause trouble
                };

        BassClientService.PACallback callback = mBassClientService.new PACallback();

        PeriodicAdvertisingReport report =
                new PeriodicAdvertisingReport(
                        TEST_SYNC_HANDLE, 0, 0, 0, ScanRecord.parseFromBytes(scanRecordNoBaseData));
        callback.onPeriodicAdvertisingReport(report);
        report =
                new PeriodicAdvertisingReport(
                        TEST_SYNC_HANDLE,
                        0,
                        0,
                        0,
                        ScanRecord.parseFromBytes(scanRecordWrongBaseData));
        callback.onPeriodicAdvertisingReport(report);

        // Not canceled, not updated base
        assertThat(mBassClientService.getActiveSyncedSources()).hasSize(1);
        assertThat(mBassClientService.getActiveSyncedSources()).containsExactly(TEST_SYNC_HANDLE);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);
        assertThat(mBassClientService.getBase(TEST_SYNC_HANDLE)).isNull();
        verifyUnregisterSyncNeverCalled();

        onPeriodicAdvertisingReport();

        // Not canceled, updated base
        expect.that(mBassClientService.getActiveSyncedSources().size()).isEqualTo(1);
        expect.that(mBassClientService.getActiveSyncedSources()).containsExactly(TEST_SYNC_HANDLE);
        expect.that(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);
        expect.that(mBassClientService.getBase(TEST_SYNC_HANDLE)).isNotNull();
        verifyUnregisterSyncNeverCalled();
    }

    @Test
    public void notifySourceFound_once_updateMetadata() throws RemoteException {
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();

        onPeriodicAdvertisingReport();

        // Not canceled, updated base
        assertThat(mBassClientService.getActiveSyncedSources()).hasSize(1);
        assertThat(mBassClientService.getActiveSyncedSources()).containsExactly(TEST_SYNC_HANDLE);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);
        assertThat(mBassClientService.getBase(TEST_SYNC_HANDLE)).isNotNull();

        // Notified
        mLooper.dispatchAll();
        ArgumentCaptor<BluetoothLeBroadcastMetadata> metaData =
                ArgumentCaptor.forClass(BluetoothLeBroadcastMetadata.class);
        InOrder inOrder = inOrder(mCallback);
        inOrder.verify(mCallback).onSourceFound(metaData.capture());
        assertThat(metaData.getValue().getSourceDevice()).isEqualTo(mSourceDevice);
        assertThat(metaData.getValue().getSourceAddressType())
                .isEqualTo(mSourceDevice.getAddressType());
        assertThat(metaData.getValue().getSourceAdvertisingSid()).isEqualTo(TEST_ADVERTISER_SID);
        assertThat(metaData.getValue().getBroadcastId()).isEqualTo(TEST_BROADCAST_ID);
        assertThat(metaData.getValue().getPaSyncInterval()).isEqualTo(TEST_PA_SYNC_INTERVAL);
        assertThat(metaData.getValue().getRssi()).isEqualTo(TEST_RSSI);
        assertThat(metaData.getValue().getBroadcastName()).isEqualTo("Test");
        assertThat(metaData.getValue().isEncrypted()).isEqualTo(true);
        assertThat(metaData.getValue().isPublicBroadcast()).isEqualTo(true);

        // Any of them should not notified second time
        onPeriodicAdvertisingReport();
        onBigInfoAdvertisingReport();

        // Not notified second time
        mLooper.dispatchAll();
        inOrder.verify(mCallback, never()).onSourceFound(any());
    }

    @Test
    public void notifySourceFound_without_public_announcement() throws RemoteException {
        byte[] broadcastScanRecord =
                new byte[] {
                    0x02,
                    0x01,
                    0x1a, // advertising flags
                    0x05,
                    0x02,
                    0x52,
                    0x18,
                    0x0a,
                    0x11, // 16 bit service uuids
                    0x04,
                    0x09,
                    0x50,
                    0x65,
                    0x64, // name
                    0x02,
                    0x0A,
                    (byte) 0xec, // tx power level
                    0x05,
                    0x30,
                    0x54,
                    0x65,
                    0x73,
                    0x74, // broadcast name: Test
                    0x06,
                    0x16,
                    0x52,
                    0x18,
                    (byte) TEST_BROADCAST_ID,
                    (byte) (TEST_BROADCAST_ID >> 8),
                    (byte) (TEST_BROADCAST_ID >> 16), // service data, broadcast id
                    0x05,
                    (byte) 0xff,
                    (byte) 0xe0,
                    0x00,
                    0x02,
                    0x15, // manufacturer specific data
                    0x03,
                    0x50,
                    0x01,
                    0x02, // an unknown data type won't cause trouble
                };

        prepareConnectedDeviceGroup();
        startSearchingForSources();
        onScanResult(mSourceDevice, broadcastScanRecord);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        assertThat(mBassClientService.getActiveSyncedSources()).hasSize(1);
        assertThat(mBassClientService.getActiveSyncedSources()).containsExactly(TEST_SYNC_HANDLE);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);
        assertThat(mBassClientService.getBase(TEST_SYNC_HANDLE)).isNull();

        // No public announcement so it will not notify
        onPeriodicAdvertisingReport();

        // Not canceled, updated base
        assertThat(mBassClientService.getActiveSyncedSources()).hasSize(1);
        assertThat(mBassClientService.getActiveSyncedSources()).containsExactly(TEST_SYNC_HANDLE);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);
        assertThat(mBassClientService.getBase(TEST_SYNC_HANDLE)).isNotNull();

        // Not notified
        mLooper.dispatchAll();
        InOrder inOrder = inOrder(mCallback);
        inOrder.verify(mCallback, never()).onSourceFound(any());

        // onBigInfoAdvertisingReport causes notification
        onBigInfoAdvertisingReport();

        // Notified
        mLooper.dispatchAll();
        inOrder.verify(mCallback).onSourceFound(any());
    }

    @Test
    public void notifySourceFound_periodic_after_big() throws RemoteException {
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();

        // Big report before periodic so before base update
        onBigInfoAdvertisingReport();

        // Not notified
        mLooper.dispatchAll();
        InOrder inOrder = inOrder(mCallback);
        inOrder.verify(mCallback, never()).onSourceFound(any());

        onPeriodicAdvertisingReport();

        // Not canceled, updated base
        assertThat(mBassClientService.getActiveSyncedSources()).hasSize(1);
        assertThat(mBassClientService.getActiveSyncedSources()).containsExactly(TEST_SYNC_HANDLE);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);
        assertThat(mBassClientService.getBase(TEST_SYNC_HANDLE)).isNotNull();

        // Notified
        mLooper.dispatchAll();
        inOrder.verify(mCallback).onSourceFound(any());
    }

    @Test
    public void notifySourceFound_periodic_after_wrong_periodic() throws RemoteException {
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();

        byte[] scanRecordNoBaseData =
                new byte[] {
                    0x02,
                    0x01,
                    0x1a, // advertising flags
                    0x05,
                    0x02,
                    0x0b,
                    0x11,
                    0x0a,
                    0x11, // 16 bit service uuids
                    0x04,
                    0x09,
                    0x50,
                    0x65,
                    0x64, // name
                    0x02,
                    0x0A,
                    (byte) 0xec, // tx power level
                    0x05,
                    0x16,
                    0x0b,
                    0x11,
                    0x50,
                    0x64, // service data
                    0x05,
                    (byte) 0xff,
                    (byte) 0xe0,
                    0x00,
                    0x02,
                    0x15, // manufacturer specific data
                    0x03,
                    0x50,
                    0x01,
                    0x02, // an unknown data type won't cause trouble
                };

        BassClientService.PACallback callback = mBassClientService.new PACallback();

        PeriodicAdvertisingReport report =
                new PeriodicAdvertisingReport(
                        TEST_SYNC_HANDLE, 0, 0, 0, ScanRecord.parseFromBytes(scanRecordNoBaseData));

        // Wrong base data not cause notification
        callback.onPeriodicAdvertisingReport(report);

        // Not notified
        mLooper.dispatchAll();
        InOrder inOrder = inOrder(mCallback);
        inOrder.verify(mCallback, never()).onSourceFound(any());

        onPeriodicAdvertisingReport();

        // Not canceled, updated base
        expect.that(mBassClientService.getActiveSyncedSources().size()).isEqualTo(1);
        expect.that(mBassClientService.getActiveSyncedSources()).containsExactly(TEST_SYNC_HANDLE);
        expect.that(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);
        expect.that(mBassClientService.getBase(TEST_SYNC_HANDLE)).isNotNull();

        // Notified
        mLooper.dispatchAll();
        inOrder.verify(mCallback).onSourceFound(any());
    }

    @Test
    public void notifySourceFound_alreadySynced_clearFlag() throws RemoteException {
        // Scan
        prepareConnectedDeviceGroup();
        startSearchingForSources();
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);

        // Source synced
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);

        onPeriodicAdvertisingReport();

        // Notified
        mLooper.dispatchAll();
        InOrder inOrder = inOrder(mCallback);
        inOrder.verify(mCallback).onSourceFound(any());

        // Stop searching, unsyc all broadcasters and clear all data except mCachedBroadcasts
        mBassClientService.stopSearchingForSources();

        // Add source to unsynced broadcast, causes synchronization first
        mBassClientService.addSource(mCurrentDevice, mBroadcastMetadata1, /* isGroupOp */ true);
        verifyBackgroundSearchStarted();
        verifyRegisterSyncCalled(mSourceDevice);

        // Source synced
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        assertThat(mBassClientService.isAnySearchInProgress()).isFalse();

        onPeriodicAdvertisingReport();
        mLooper.dispatchAll();

        // Start searching again clears timeout, mCachedBroadcasts and notifiedFlags but keep syncs
        startSearchingForSources();

        onPeriodicAdvertisingReport();
        // Notified
        mLooper.dispatchAll();
        inOrder.verify(mCallback).onSourceFound(any());
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_ALWAYS_CLEAR_NOTIFIED_FLAGS)
    public void testStartSearchingForSources_ClearNotifiedFlags_WhenAlreadySearching()
            throws RemoteException {
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();

        // 1. Source found and notified
        onPeriodicAdvertisingReport();
        mLooper.dispatchAll();
        verify(mCallback).onSourceFound(any());
        clearInvocations(mCallback);

        // 2. Source report again - should NOT notify
        onPeriodicAdvertisingReport();
        mLooper.dispatchAll();
        verify(mCallback, never()).onSourceFound(any());

        // 3. Start searching AGAIN (while already running)
        // With the flag enabled, this should clear notified flags, even if it returns error.
        assertThat(mBassClientService.isSearchInProgress()).isTrue();

        mBassClientService.startSearchingForSources(new ArrayList<>());
        mLooper.dispatchAll();

        // It should fail with ALREADY_IN_TARGET_STATE because it is already searching
        verify(mCallback).onSearchStartFailed(BluetoothStatusCodes.ERROR_ALREADY_IN_TARGET_STATE);

        // 4. Source report again - SHOULD notify because flags were cleared
        onPeriodicAdvertisingReport();
        mLooper.dispatchAll();
        verify(mCallback).onSourceFound(any());
    }

    @Test
    public void onSyncLost_notifySourceLostAndCancelSync() throws RemoteException {
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();

        onSyncLost();
        checkAndDispatchTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_SYNC_LOST_TIMEOUT);

        mLooper.dispatchAll();
        verify(mCallback).onSourceLost(eq(TEST_BROADCAST_ID));

        // Cleaned all
        assertThat(mBassClientService.getActiveSyncedSources()).isEmpty();
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE)).isNull();
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);

        // Could try to sync again
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        verifyRegisterSyncCalled(mSourceDevice);
    }

    @Test
    public void onSyncLost_stopTimeoutsOnStopSearching() throws RemoteException {
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();

        onSyncLost();
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_SYNC_LOST_TIMEOUT);

        mBassClientService.stopSearchingForSources();
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_SYNC_LOST_TIMEOUT);

        mLooper.dispatchAll();
        verify(mCallback, never()).onSourceLost(eq(TEST_BROADCAST_ID));
    }

    @Test
    public void monitorBroadcastAfterSyncMaxLimit() throws RemoteException {
        final BluetoothDevice device1 = getRealDevice("00:11:22:33:44:11", ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device2 = getRealDevice("00:11:22:33:44:22", ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device3 = getRealDevice("00:11:22:33:44:33", ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device4 = getRealDevice("00:11:22:33:44:44", ADDRESS_TYPE_RANDOM);
        final BluetoothDevice device5 = getRealDevice("00:11:22:33:44:55", ADDRESS_TYPE_RANDOM);
        final int handle1 = 0;
        final int handle2 = 1;
        final int handle3 = 2;
        final int handle4 = 3;
        final int handle5 = 4;
        final int broadcastId1 = 1111;
        final int broadcastId2 = 2222;
        final int broadcastId3 = 3333;
        final int broadcastId4 = 4444;
        final int broadcastId5 = 5555;

        prepareConnectedDeviceGroup();
        startSearchingForSources();

        // Scan and sync 5 sources cause removing 1 synced element
        onScanResult(device1, broadcastId1);
        verifyRegisterSyncCalled(device1);
        onSyncEstablished(device1, handle1);
        onScanResult(device2, broadcastId2);
        verifyRegisterSyncCalled(device2);
        onSyncEstablished(device2, handle2);
        onScanResult(device3, broadcastId3);
        verifyRegisterSyncCalled(device3);
        onSyncEstablished(device3, handle3);
        onScanResult(device4, broadcastId4);
        verifyRegisterSyncCalled(device4);
        onSyncEstablished(device4, handle4);
        onScanResult(device5, broadcastId5);
        verifyUnregisterSyncCalled();
        checkTimeout(broadcastId1, BassClientService.MESSAGE_SYNC_LOST_TIMEOUT);

        verifyRegisterSyncCalled(device5);
        onSyncEstablished(device5, handle5);

        // Couldn't sync again as broadcast is in the cache
        onScanResult(device1, broadcastId1);
        verifyRegisterSyncNeverCalled();

        // Lost should notify about lost and clear cache
        checkAndDispatchTimeout(broadcastId1, BassClientService.MESSAGE_SYNC_LOST_TIMEOUT);

        mLooper.dispatchAll();
        verify(mCallback).onSourceLost(eq(broadcastId1));

        // Could try to sync again
        onScanResult(device1, broadcastId1);
        verifyRegisterSyncCalled(device1);
    }

    private void prepareSynchronizedPair() {
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();

        // Add source
        addSourceAndVerify(mBroadcastMetadata1);

        // Bis synced
        injectRemoteSourceStateSourceAdded(
                mBroadcastMetadata1, /* isPaSynced */ true, /* isBisSynced */ true);
        verify(mLeAudioService).activeBroadcastAssistantNotification(eq(true));
    }

    private void prepareSynchronizedPairAndStopSearching() {
        prepareSynchronizedPair();

        // Stop searching
        mBassClientService.stopSearchingForSources();
        verifyUnregisterSyncCalled();
    }

    private void prepareSynchronizedPairNoPreferenceAndStopSearching() {
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();

        // Add source
        addSourceAndVerify(mBroadcastMetadataNoPreference);

        // Bis synced
        injectRemoteSourceStateSourceAdded(
                mBroadcastMetadata1, /* isPaSynced */ true, /* isBisSynced */ true);
        verify(mLeAudioService).activeBroadcastAssistantNotification(eq(true));

        // Stop searching
        mBassClientService.stopSearchingForSources();
        verifyUnregisterSyncCalled();
    }

    private void bigMonitoringWithoutScanning() {
        prepareSynchronizedPairAndStopSearching();

        // Bis and PA unsynced, BIG_MONITORING
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ false);
        verifyRegisterSyncCalled(mSourceDevice);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);
    }

    private void bigMonitoringDuringScanning() {
        prepareSynchronizedPair();

        // Bis and PA unsynced, BIG_MONITORING
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ false);
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);
    }

    private void checkResumeSynchronizationByBig() {
        // BIG causes resume synchronization
        for (BassClientStateMachine sm : mStateMachines.values()) {
            clearInvocations(sm);
        }
        onPeriodicAdvertisingReport();
        onBigInfoAdvertisingReport();
        verifyAllGroupMembersGettingUpdateOrAddSource(mBroadcastMetadata1);
    }

    private void checkNoResumeSynchronizationByBig() {
        // BIG not cause resume synchronization
        for (BassClientStateMachine sm : mStateMachines.values()) {
            clearInvocations(sm);
        }
        // In case of add source to inactive
        if (!mBassClientService.getActiveSyncedSources().contains(TEST_SYNC_HANDLE)) {
            mBassClientService.addSelectSourceRequest(TEST_BROADCAST_ID, /* hasPriority */ true);
            clearInvocations(mScanController);
            onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        }

        onPeriodicAdvertisingReport();
        onBigInfoAdvertisingReport();
        for (BassClientStateMachine sm : mStateMachines.values()) {
            verify(sm, never()).sendMessage(any());
        }
    }

    private void checkResumeSynchronizationByHost() {
        for (BassClientStateMachine sm : mStateMachines.values()) {
            clearInvocations(sm);
        }
        mBassClientService.resumeReceiversSourceSynchronization();
        if (!mBassClientService.getActiveSyncedSources().contains(TEST_SYNC_HANDLE)) {
            verifyBackgroundSearchStarted();
            verifyRegisterSyncCalled(mSourceDevice);
            onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
            assertThat(mBassClientService.isAnySearchInProgress()).isFalse();
            onPeriodicAdvertisingReport();
            onBigInfoAdvertisingReport();
        }
        verifyAllGroupMembersGettingUpdateOrAddSource(mBroadcastMetadata1);
    }

    private void checkNoResumeSynchronizationByHost() {
        // Verify empty resume list
        for (BassClientStateMachine sm : mStateMachines.values()) {
            clearInvocations(sm);
        }
        mBassClientService.resumeReceiversSourceSynchronization();
        for (BassClientStateMachine sm : mStateMachines.values()) {
            verify(sm, never()).sendMessage(any());
        }
        verifyRegisterSyncNeverCalled();
    }

    private void verifyStopBroadcastMonitoringWithUnsync() {
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_OOR_MONITOR_TIMEOUT);
        verifyUnregisterSyncCalled();
    }

    private void verifyStopBroadcastMonitoringWithoutUnsync() {
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_OOR_MONITOR_TIMEOUT);
        verifyUnregisterSyncNeverCalled();
    }

    private void resyncAndVerifyWithUnsync() {
        // Resync, verify stopBroadcastMonitoring with broadcast unsync
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ true, /* isBisSynced */ true);
        verifyStopBroadcastMonitoringWithUnsync();
    }

    private void resyncAndVerifyWithoutUnsync() {
        // Resync, verify stopBroadcastMonitoring without broadcast unsync
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ true, /* isBisSynced */ true);
        verifyStopBroadcastMonitoringWithoutUnsync();
    }

    private void checkNotAllowBroadcastMonitoring() {
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ true, /* isBisSynced */ false);
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ false);
        verifyRegisterSyncNeverCalled();
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_OOR_MONITOR_TIMEOUT);
    }

    private void checkAllowBroadcastMonitoring() {
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ true, /* isBisSynced */ false);
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ false);
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);
    }

    @Test
    public void bigMonitoring_resync_withoutScanning() {
        bigMonitoringWithoutScanning();

        checkResumeSynchronizationByBig();
        resyncAndVerifyWithUnsync();
    }

    @Test
    public void bigMonitoring_resync_duringScanning() {
        bigMonitoringDuringScanning();

        checkResumeSynchronizationByBig();
        resyncAndVerifyWithoutUnsync();
    }

    @Test
    public void bigMonitoring_resyncByRemote_withoutScanning() {
        bigMonitoringWithoutScanning();

        resyncAndVerifyWithUnsync();
    }

    @Test
    public void bigMonitoring_resyncByRemote_duringScanning() {
        bigMonitoringDuringScanning();

        resyncAndVerifyWithoutUnsync();
    }

    @Test
    public void bigMonitoring_addNewSource() {
        bigMonitoringDuringScanning();

        // Scan and sync second broadcast
        onScanResult(mSourceDevice2, TEST_BROADCAST_ID_2);
        verifyRegisterSyncCalled(mSourceDevice2);
        onSyncEstablished(mSourceDevice2, TEST_SYNC_HANDLE_2);

        // Add second source, SUSPENDED_BY_HOST
        BluetoothLeBroadcastMetadata.Builder builder =
                new BluetoothLeBroadcastMetadata.Builder()
                        .setEncrypted(false)
                        .setSourceDevice(mSourceDevice2, ADDRESS_TYPE_RANDOM)
                        .setSourceAdvertisingSid(TEST_ADVERTISER_SID)
                        .setBroadcastId(TEST_BROADCAST_ID_2)
                        .setBroadcastCode(null)
                        .setPaSyncInterval(TEST_PA_SYNC_INTERVAL)
                        .setPresentationDelayMicros(TEST_PRESENTATION_DELAY_MS);
        // builder expect at least one subgroup
        builder.addSubgroup(createBroadcastSubgroup());
        BluetoothLeBroadcastMetadata meta2 = builder.build();
        mBassClientService.addSource(mCurrentDevice, meta2, /* isGroupOp */ true);
        verifyStopBroadcastMonitoringWithoutUnsync();

        // BIG for first broadcast not cause resume synchronization
        checkNoResumeSynchronizationByBig();
    }

    @Test
    public void bigMonitoring_addSameSource() {
        bigMonitoringDuringScanning();

        // Verify add source clear the BIG_MONITORING
        mBassClientService.addSource(mCurrentDevice, mBroadcastMetadata1, /* isGroupOp */ true);
        checkAllowBroadcastMonitoring();
    }

    @Test
    public void bigMonitoring_removeSource_withoutScanning() {
        bigMonitoringWithoutScanning();

        // Remove source, SUSPENDED_BY_HOST
        mBassClientService.removeSource(mCurrentDevice, TEST_SOURCE_ID);
        verifyStopBroadcastMonitoringWithUnsync();
        verifyRemoveMessageAndInjectSourceRemoval();
        checkNoResumeSynchronizationByBig();
    }

    @Test
    public void bigMonitoring_removeSource_duringScanning() {
        bigMonitoringDuringScanning();

        // Remove source, SUSPENDED_BY_HOST
        mBassClientService.removeSource(mCurrentDevice, TEST_SOURCE_ID);
        verifyStopBroadcastMonitoringWithoutUnsync();
        verifyRemoveMessageAndInjectSourceRemoval();
        checkNoResumeSynchronizationByBig();
    }

    @Test
    public void bigMonitoring_stopReceivers_withoutScanning() {
        bigMonitoringWithoutScanning();

        // Stop receivers, SUSPENDED_BY_HOST
        mBassClientService.stopReceiversSourceSynchronization(TEST_BROADCAST_ID);
        verifyStopBroadcastMonitoringWithUnsync();
        verifyRemoveMessageAndInjectSourceRemoval();
        checkNoResumeSynchronizationByBig();
        checkNoResumeSynchronizationByHost();
    }

    @Test
    public void bigMonitoring_stopReceivers_duringScanning() {
        bigMonitoringDuringScanning();

        // Stop receivers, SUSPENDED_BY_HOST
        mBassClientService.stopReceiversSourceSynchronization(TEST_BROADCAST_ID);
        verifyStopBroadcastMonitoringWithoutUnsync();
        verifyRemoveMessageAndInjectSourceRemoval();
        checkNoResumeSynchronizationByBig();
        checkNoResumeSynchronizationByHost();
    }

    @Test
    public void bigMonitoring_suspendAllReceivers_withoutScanning() {
        bigMonitoringWithoutScanning();

        // Suspend all receivers, SUSPENDED_BY_HOST
        mBassClientService.suspendAllReceiversSourceSynchronization();
        verifyStopBroadcastMonitoringWithUnsync();
        verifyModifyMessageAndInjectSourceModified();
        checkNoResumeSynchronizationByBig();
        checkResumeSynchronizationByHost();
    }

    @Test
    public void bigMonitoring_suspendAllReceivers_duringScanning() {
        bigMonitoringDuringScanning();

        // Suspend all receivers, SUSPENDED_BY_HOST
        mBassClientService.suspendAllReceiversSourceSynchronization();
        verifyStopBroadcastMonitoringWithoutUnsync();
        verifyModifyMessageAndInjectSourceModified();
        checkNoResumeSynchronizationByBig();
        checkResumeSynchronizationByHost();
    }

    @Test
    public void bigMonitoring_unsync_withoutScanning() {
        bigMonitoringWithoutScanning();

        // Unsync not all sinks not cause stop monitoring
        for (BassClientStateMachine sm : mStateMachines.values()) {
            // Update receiver state
            if (sm.getDevice().equals(mCurrentDevice)) {
                injectRemoteSourceStateChanged(
                        sm,
                        createEmptyBroadcastMetadata(),
                        TEST_SOURCE_ID,
                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                        BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                        null,
                        0L);
            }
        }
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);

        // Unsync all sinks cause stop monitoring
        for (BassClientStateMachine sm : mStateMachines.values()) {
            // Update receiver state
            if (sm.getDevice().equals(mCurrentDevice1)) {
                injectRemoteSourceStateChanged(
                        sm,
                        createEmptyBroadcastMetadata(),
                        TEST_SOURCE_ID + 1,
                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                        BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                        null,
                        0L);
            }
        }
        verifyStopBroadcastMonitoringWithUnsync();
        checkNoResumeSynchronizationByBig();
    }

    @Test
    public void bigMonitoring_unsync_duringScanning() {
        bigMonitoringDuringScanning();

        // Unsync not all sinks not cause stop monitoring
        for (BassClientStateMachine sm : mStateMachines.values()) {
            // Update receiver state
            if (sm.getDevice().equals(mCurrentDevice)) {
                injectRemoteSourceStateChanged(
                        sm,
                        createEmptyBroadcastMetadata(),
                        TEST_SOURCE_ID,
                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                        BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                        null,
                        0L);
            }
        }
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);

        // Unsync all sinks cause stop monitoring
        for (BassClientStateMachine sm : mStateMachines.values()) {
            // Update receiver state
            if (sm.getDevice().equals(mCurrentDevice1)) {
                injectRemoteSourceStateChanged(
                        sm,
                        createEmptyBroadcastMetadata(),
                        TEST_SOURCE_ID + 1,
                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                        BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                        null,
                        0L);
            }
        }
        verifyStopBroadcastMonitoringWithoutUnsync();
        checkNoResumeSynchronizationByBig();
    }

    @Test
    public void bigMonitoring_disconnect_withoutScanning() {
        bigMonitoringWithoutScanning();

        // Disconnect not all sinks not cause stop monitoring
        injectDeviceDisconnection(mCurrentDevice);
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);

        // Disconnect all sinks cause stop monitoring
        injectDeviceDisconnection(mCurrentDevice1);
        verifyStopBroadcastMonitoringWithUnsync();
        checkNoResumeSynchronizationByBig();
    }

    @Test
    public void bigMonitoring_disconnect_duringScanning() {
        bigMonitoringDuringScanning();

        // Disconnect not all sinks not cause stop monitoring
        injectDeviceDisconnection(mCurrentDevice);
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);

        // Disconnect all sinks cause stop monitoring
        injectDeviceDisconnection(mCurrentDevice1);
        verifyStopBroadcastMonitoringWithoutUnsync();
        checkNoResumeSynchronizationByBig();
    }

    @Test
    public void bigMonitoring_syncLost_withoutScanning_outOfRange() {
        bigMonitoringWithoutScanning();

        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_OOR_MONITOR_TIMEOUT);

        onSyncLost();
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_OOR_MONITOR_TIMEOUT);
        verifyBackgroundSearchStarted();
        verifyRegisterSyncCalled(mSourceDevice);

        onSyncEstablishedFailed(mSourceDevice, TEST_SYNC_HANDLE);
        if (Flags.leaudioBroadcastAlwaysUseBackgroundScanner()) {
            onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        }
        verifyRegisterSyncCalled(mSourceDevice);

        checkAndDispatchTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_OOR_MONITOR_TIMEOUT);
        assertThat(mBassClientService.isAnySearchInProgress()).isFalse();
        verifyUnregisterSyncCalled();
        verifyRemoveMessageAndInjectSourceRemoval();
        checkNoResumeSynchronizationByBig();
    }

    @Test
    public void bigMonitoring_syncLost_duringScanning_outOfRange() {
        bigMonitoringDuringScanning();

        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_OOR_MONITOR_TIMEOUT);

        onSyncLost();
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_OOR_MONITOR_TIMEOUT);
        verifyRegisterSyncCalled(mSourceDevice);

        onSyncEstablishedFailed(mSourceDevice, TEST_SYNC_HANDLE);

        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        verifyRegisterSyncCalled(mSourceDevice);

        checkAndDispatchTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_OOR_MONITOR_TIMEOUT);
        verifyRegisterSyncNeverCalled();
        verifyRemoveMessageAndInjectSourceRemoval();
        checkNoResumeSynchronizationByBig();
    }

    @Test
    public void bigMonitoring_bigMonitorTimeout_withoutScanning() {
        bigMonitoringWithoutScanning();

        checkAndDispatchTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);
        verifyUnregisterSyncCalled();
        verifyRemoveMessageAndInjectSourceRemoval();
        checkNoResumeSynchronizationByBig();
    }

    @Test
    public void bigMonitoring_bigMonitorTimeout_duringScanning() {
        bigMonitoringDuringScanning();

        checkAndDispatchTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);
        verifyRegisterSyncNeverCalled();
        verifyRemoveMessageAndInjectSourceRemoval();
        checkNoResumeSynchronizationByBig();
    }

    @Test
    public void bigMonitoring_handleUnicastSourceStreamStatusChange_withoutScanning() {
        bigMonitoringWithoutScanning();

        /* Unicast would like to stream */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                LeAudioStackEvent.STATUS_LOCAL_STREAM_REQUESTED);
        verifyStopBroadcastMonitoringWithUnsync();

        /* Unicast finished streaming */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                LeAudioStackEvent.STATUS_LOCAL_STREAM_SUSPENDED);
        verifyBackgroundSearchStarted();
        verifyRegisterSyncCalled(mSourceDevice);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        assertThat(mBassClientService.isAnySearchInProgress()).isFalse();
        onPeriodicAdvertisingReport();
        onBigInfoAdvertisingReport();
        verifyAllGroupMembersGettingUpdateOrAddSource(mBroadcastMetadata1);
    }

    @Test
    public void bigMonitoring_handleUnicastSourceStreamStatusChange_duringScanning() {
        bigMonitoringDuringScanning();

        /* Unicast would like to stream */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                LeAudioStackEvent.STATUS_LOCAL_STREAM_REQUESTED);
        verifyStopBroadcastMonitoringWithoutUnsync();
        checkNoResumeSynchronizationByBig();

        /* Unicast finished streaming */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                LeAudioStackEvent.STATUS_LOCAL_STREAM_SUSPENDED);
        verifyAllGroupMembersGettingUpdateOrAddSource(mBroadcastMetadata1);
    }

    @Test
    public void bigMonitoring_handleUnicastSourceStreamStatusChangeNoContext_withoutScanning() {
        bigMonitoringWithoutScanning();

        /* Unicast would like to stream */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                LeAudioStackEvent.STATUS_LOCAL_STREAM_REQUESTED_NO_CONTEXT_VALIDATE);
        verifyStopBroadcastMonitoringWithUnsync();
        verifyModifyMessageAndInjectSourceModified();

        /* Unicast finished streaming */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                LeAudioStackEvent.STATUS_LOCAL_STREAM_SUSPENDED);
        verifyBackgroundSearchStarted();
        verifyRegisterSyncCalled(mSourceDevice);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        assertThat(mBassClientService.isAnySearchInProgress()).isFalse();
        onPeriodicAdvertisingReport();
        onBigInfoAdvertisingReport();
        verifyAllGroupMembersGettingUpdateOrAddSource(mBroadcastMetadata1);
    }

    @Test
    public void bigMonitoring_handleUnicastSourceStreamStatusChangeNoContext_duringScanning() {
        bigMonitoringDuringScanning();

        /* Unicast would like to stream */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                LeAudioStackEvent.STATUS_LOCAL_STREAM_REQUESTED_NO_CONTEXT_VALIDATE);
        verifyStopBroadcastMonitoringWithoutUnsync();
        verifyModifyMessageAndInjectSourceModified();
        checkNoResumeSynchronizationByBig();

        /* Unicast finished streaming */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                LeAudioStackEvent.STATUS_LOCAL_STREAM_SUSPENDED);
        verifyAllGroupMembersGettingUpdateOrAddSource(mBroadcastMetadata1);
    }

    @Test
    public void bigMonitoring_remainEstablishedSync_onStopSearching() {
        bigMonitoringDuringScanning();

        // Scan and sync to another broadcaster
        onScanResult(mSourceDevice2, TEST_BROADCAST_ID_2);
        verifyRegisterSyncCalled(mSourceDevice2);
        onSyncEstablished(mSourceDevice2, TEST_SYNC_HANDLE_2);

        // Scan and add add sync to pending
        final BluetoothDevice sourceDevice3 =
                getRealDevice("00:11:22:33:44:11", ADDRESS_TYPE_RANDOM);
        onScanResult(sourceDevice3, TEST_BROADCAST_ID + 2);
        verifyRegisterSyncCalled(sourceDevice3);

        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(2);
        assertThat(mBassClientService.getActiveSyncedSources())
                .containsExactly(TEST_SYNC_HANDLE, TEST_SYNC_HANDLE_2);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE_2))
                .isEqualTo(mSourceDevice2);
        assertThat(mBassClientService.getDeviceForSyncHandle(BassConstants.PENDING_SYNC_HANDLE))
                .isEqualTo(sourceDevice3);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE_2))
                .isEqualTo(TEST_BROADCAST_ID_2);
        assertThat(
                        mBassClientService.getBroadcastIdForSyncHandle(
                                BassConstants.PENDING_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID + 2);

        // Verify that stop searching remain the monitored broadcast sync
        mBassClientService.stopSearchingForSources();
        assertThat(mBassClientService.isAnySearchInProgress()).isFalse();
        // Monitored broadcast sync remain, another sync was removed, pending was canceled
        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(1);
        assertThat(mBassClientService.getActiveSyncedSources()).containsExactly(TEST_SYNC_HANDLE);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE_2)).isNull();
        assertThat(mBassClientService.getDeviceForSyncHandle(BassConstants.PENDING_SYNC_HANDLE))
                .isNull();
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE_2))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
        assertThat(
                        mBassClientService.getBroadcastIdForSyncHandle(
                                BassConstants.PENDING_SYNC_HANDLE))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);

        // Resume without another register sync is possible
        mBassClientService.resumeReceiversSourceSynchronization();
        verifyRegisterSyncNeverCalled();
        verifyAllGroupMembersGettingUpdateOrAddSource(mBroadcastMetadata1);
    }

    @Test
    public void waitingForPast_remainPendingSync_onStopSearching() {
        prepareSynchronizedPair();

        // Scan and sync to another broadcaster
        onScanResult(mSourceDevice2, TEST_BROADCAST_ID_2);
        verifyRegisterSyncCalled(mSourceDevice2);
        onSyncEstablished(mSourceDevice2, TEST_SYNC_HANDLE_2);

        // Sync lost without triggering timeout to keep cache
        onSyncLost();

        // Sync info request force syncing to broadcaster and add sinks pending for PAST
        mBassClientService.syncRequestForPast(mCurrentDevice, TEST_BROADCAST_ID, TEST_SOURCE_ID);
        mBassClientService.syncRequestForPast(
                mCurrentDevice1, TEST_BROADCAST_ID, TEST_SOURCE_ID + 1);
        verifyRegisterSyncCalled(mSourceDevice);

        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(1);
        assertThat(mBassClientService.getActiveSyncedSources()).containsExactly(TEST_SYNC_HANDLE_2);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE)).isNull();
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE_2))
                .isEqualTo(mSourceDevice2);
        assertThat(mBassClientService.getDeviceForSyncHandle(BassConstants.PENDING_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE_2))
                .isEqualTo(TEST_BROADCAST_ID_2);
        assertThat(
                        mBassClientService.getBroadcastIdForSyncHandle(
                                BassConstants.PENDING_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);

        // Verify that stop searching remain the pending sync
        mBassClientService.stopSearchingForSources();
        if (Flags.leaudioBroadcastAlwaysUseBackgroundScanner()) {
            assertThat(mBassClientService.isAnySearchInProgress()).isTrue();
        }
        // Pending remain, another unsynced
        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(0);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE)).isNull();
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE_2)).isNull();
        assertThat(mBassClientService.getDeviceForSyncHandle(BassConstants.PENDING_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE_2))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
        assertThat(
                        mBassClientService.getBroadcastIdForSyncHandle(
                                BassConstants.PENDING_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);

        // Establishment possible without register sync
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        assertThat(mBassClientService.isAnySearchInProgress()).isFalse();
        verifyInitiatePaSyncTransferAndNoOthers(TEST_SYNC_HANDLE, TEST_SOURCE_ID);
    }

    @Test
    public void pendingSourceToAdd_remainPendingSync_onStopSearching() {
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();

        // Scan and sync to another broadcaster
        onScanResult(mSourceDevice2, TEST_BROADCAST_ID_2);
        verifyRegisterSyncCalled(mSourceDevice2);
        onSyncEstablished(mSourceDevice2, TEST_SYNC_HANDLE_2);

        // Sync lost without triggering timeout to keep cache
        onSyncLost();

        // Add source force syncing to broadcaster and add sinks to pendingSourcesToAdd
        mBassClientService.addSource(mCurrentDevice, mBroadcastMetadata1, /* isGroupOp */ true);
        verifyRegisterSyncCalled(mSourceDevice);

        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(1);
        assertThat(mBassClientService.getActiveSyncedSources()).containsExactly(TEST_SYNC_HANDLE_2);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE)).isNull();
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE_2))
                .isEqualTo(mSourceDevice2);
        assertThat(mBassClientService.getDeviceForSyncHandle(BassConstants.PENDING_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE_2))
                .isEqualTo(TEST_BROADCAST_ID_2);
        assertThat(
                        mBassClientService.getBroadcastIdForSyncHandle(
                                BassConstants.PENDING_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);

        // Verify that stop searching remain the pending sync
        mBassClientService.stopSearchingForSources();
        if (Flags.leaudioBroadcastAlwaysUseBackgroundScanner()) {
            assertThat(mBassClientService.isAnySearchInProgress()).isTrue();
        }
        // Pending remain, another unsynced
        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(0);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE)).isNull();
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE_2)).isNull();
        assertThat(mBassClientService.getDeviceForSyncHandle(BassConstants.PENDING_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE_2))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
        assertThat(
                        mBassClientService.getBroadcastIdForSyncHandle(
                                BassConstants.PENDING_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);

        // Establishment possible without register sync
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        assertThat(mBassClientService.isAnySearchInProgress()).isFalse();
        verifyAllGroupMembersGettingUpdateOrAddSource(mBroadcastMetadata1);
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_ALWAYS_USE_BACKGROUND_SCANNER)
    public void waitingForMetadataUpdate_remainSync_onStopSearching() {
        prepareConnectedDeviceGroup();
        // This syncs TEST_BROADCAST_ID (handle 0)
        prepareSyncToSourceAndVerify();

        // Mock not local
        doReturn(null).when(mLeAudioService).getBroadcastMetadata(TEST_BROADCAST_ID);

        // Trigger metadata sync request via remote source addition
        BluetoothLeBroadcastMetadata metadata =
                createBroadcastMetadata(TEST_BROADCAST_ID, mSourceDevice);
        BluetoothLeBroadcastReceiveState receiveState =
                createReceiveState(metadata, TEST_SOURCE_ID);

        mBassClientService
                .getCallbacks()
                .notifySourceAdded(
                        mCurrentDevice, receiveState, BluetoothStatusCodes.REASON_REMOTE_REQUEST);

        // Stop searching
        mBassClientService.stopSearchingForSources();

        // Verify that broadcast is kept synced
        List<Integer> activeSynced = mBassClientService.getActiveSyncedSources();
        assertThat(activeSynced).contains(TEST_SYNC_HANDLE);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_ALWAYS_USE_BACKGROUND_SCANNER)
    public void pendingSourceOperation_remainSync_onStopSearching() {
        prepareConnectedDeviceGroup();
        // This syncs TEST_BROADCAST_ID (handle 0)
        prepareSyncToSourceAndVerify();

        // Mock pending operation on the state machine
        BassClientStateMachine sm = mStateMachines.get(mCurrentDevice);
        doReturn(TEST_BROADCAST_ID).when(sm).getPendingOperationBroadcastId();

        // Stop searching
        mBassClientService.stopSearchingForSources();

        // Verify that broadcast is kept synced
        List<Integer> activeSynced = mBassClientService.getActiveSyncedSources();
        assertThat(activeSynced).contains(TEST_SYNC_HANDLE);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
    }

    @Test
    public void alreadySynced_remainSyncAndCache_onStartSearching() {
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();

        // Scan and sync to another broadcaster
        onScanResult(mSourceDevice2, TEST_BROADCAST_ID_2);
        verifyRegisterSyncCalled(mSourceDevice2);
        onSyncEstablished(mSourceDevice2, TEST_SYNC_HANDLE_2);

        // Cancel all syncs by stop searching
        mBassClientService.stopSearchingForSources();
        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(0);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE)).isNull();
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE_2)).isNull();
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE_2))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
        assertThat(mBassClientService.getCachedBroadcast(TEST_BROADCAST_ID)).isNotNull();
        assertThat(mBassClientService.getCachedBroadcast(TEST_BROADCAST_ID_2)).isNotNull();

        // Add source force syncing to broadcaster
        // Not finished to not add BIG_MONITORING or to not unsync
        mBassClientService.addSource(mCurrentDevice, mBroadcastMetadata1, /* isGroupOp */ true);
        verifyBackgroundSearchStarted();
        verifyRegisterSyncCalled(mSourceDevice);

        // Synced
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        assertThat(mBassClientService.isAnySearchInProgress()).isFalse();
        verifyAllGroupMembersGettingUpdateOrAddSource(mBroadcastMetadata1);
        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(1);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE_2)).isNull();
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE_2))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);

        // Start searching sources remain synced broadcasters and their cache but remove others
        startSearchingForSources();
        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(1);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE_2)).isNull();
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE_2))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
        assertThat(mBassClientService.getCachedBroadcast(TEST_BROADCAST_ID)).isNotNull();
        assertThat(mBassClientService.getCachedBroadcast(TEST_BROADCAST_ID_2)).isNull();
    }

    @Test
    public void alreadySyncedWithSinks_syncAndRemainCache_onStartSearching() {
        prepareSynchronizedPair();

        // Scan and sync to another broadcaster
        onScanResult(mSourceDevice2, TEST_BROADCAST_ID_2);
        verifyRegisterSyncCalled(mSourceDevice2);
        onSyncEstablished(mSourceDevice2, TEST_SYNC_HANDLE_2);

        // Cancel all syncs by stop searching
        mBassClientService.stopSearchingForSources();
        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(0);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE)).isNull();
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE_2)).isNull();
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE_2))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
        assertThat(mBassClientService.getCachedBroadcast(TEST_BROADCAST_ID)).isNotNull();
        assertThat(mBassClientService.getCachedBroadcast(TEST_BROADCAST_ID_2)).isNotNull();

        // Start searching sources syncs to the broadcasters already synced with sinks
        startSearchingForSourcesWithAutoSync(mSourceDevice);
        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(0);
        assertThat(mBassClientService.getDeviceForSyncHandle(BassConstants.PENDING_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE_2)).isNull();
        assertThat(
                        mBassClientService.getBroadcastIdForSyncHandle(
                                BassConstants.PENDING_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE_2))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
        assertThat(mBassClientService.getCachedBroadcast(TEST_BROADCAST_ID)).isNotNull();
        assertThat(mBassClientService.getCachedBroadcast(TEST_BROADCAST_ID_2)).isNull();

        // Synced
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(1);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE_2)).isNull();
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE_2))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
    }

    @Test
    public void waitingForPast_remainPendingSyncAndCache_onStartSearching() {
        prepareSynchronizedPair();

        // Scan and sync to another broadcaster
        onScanResult(mSourceDevice2, TEST_BROADCAST_ID_2);
        verifyRegisterSyncCalled(mSourceDevice2);
        onSyncEstablished(mSourceDevice2, TEST_SYNC_HANDLE_2);

        // Cancel all syncs by stop searching
        mBassClientService.stopSearchingForSources();
        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(0);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE)).isNull();
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE_2)).isNull();
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE_2))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
        assertThat(mBassClientService.getCachedBroadcast(TEST_BROADCAST_ID)).isNotNull();
        assertThat(mBassClientService.getCachedBroadcast(TEST_BROADCAST_ID_2)).isNotNull();

        // Sync info request force syncing to broadcaster and add sinks pending for PAST
        mBassClientService.syncRequestForPast(mCurrentDevice, TEST_BROADCAST_ID, TEST_SOURCE_ID);
        mBassClientService.syncRequestForPast(
                mCurrentDevice1, TEST_BROADCAST_ID, TEST_SOURCE_ID + 1);
        verifyBackgroundSearchStarted();
        verifyRegisterSyncCalled(mSourceDevice);

        // Start searching sources remain pending sync and cache for broadcaster waiting for past
        startSearchingForSources();
        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(0);
        assertThat(mBassClientService.getDeviceForSyncHandle(BassConstants.PENDING_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE_2)).isNull();
        assertThat(
                        mBassClientService.getBroadcastIdForSyncHandle(
                                BassConstants.PENDING_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE_2))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
        assertThat(mBassClientService.getCachedBroadcast(TEST_BROADCAST_ID)).isNotNull();
        assertThat(mBassClientService.getCachedBroadcast(TEST_BROADCAST_ID_2)).isNull();

        // Synced
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        verifyInitiatePaSyncTransferAndNoOthers(TEST_SYNC_HANDLE, TEST_SOURCE_ID);
        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(1);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE_2)).isNull();
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE_2))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
    }

    @Test
    public void pendingSourcesToAdd_remainPendingSyncAndCache_onStartSearching() {
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();

        // Scan and sync to another broadcaster
        onScanResult(mSourceDevice2, TEST_BROADCAST_ID_2);
        verifyRegisterSyncCalled(mSourceDevice2);
        onSyncEstablished(mSourceDevice2, TEST_SYNC_HANDLE_2);

        // Cancel all syncs by stop searching
        mBassClientService.stopSearchingForSources();
        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(0);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE)).isNull();
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE_2)).isNull();
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE_2))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
        assertThat(mBassClientService.getCachedBroadcast(TEST_BROADCAST_ID)).isNotNull();
        assertThat(mBassClientService.getCachedBroadcast(TEST_BROADCAST_ID_2)).isNotNull();

        // Add source force syncing to broadcaster
        // Not finished to not add BIG_MONITORING or to not unsync
        mBassClientService.addSource(mCurrentDevice, mBroadcastMetadata1, /* isGroupOp */ true);
        verifyBackgroundSearchStarted();
        verifyRegisterSyncCalled(mSourceDevice);

        // Start searching sources remain pending sync and cache for broadcaster
        startSearchingForSources();
        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(0);
        assertThat(mBassClientService.getDeviceForSyncHandle(BassConstants.PENDING_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE_2)).isNull();
        assertThat(
                        mBassClientService.getBroadcastIdForSyncHandle(
                                BassConstants.PENDING_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE_2))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
        assertThat(mBassClientService.getCachedBroadcast(TEST_BROADCAST_ID)).isNotNull();
        assertThat(mBassClientService.getCachedBroadcast(TEST_BROADCAST_ID_2)).isNull();

        // Synced
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        verifyAllGroupMembersGettingUpdateOrAddSource(mBroadcastMetadata1);
        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(1);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE_2)).isNull();
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE_2))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
    }

    @Test
    public void suspendedByHost_SyncAndRemainCache_onStartSearching() {
        prepareSynchronizedPair();

        // Scan and sync to another broadcaster
        onScanResult(mSourceDevice2, TEST_BROADCAST_ID_2);
        verifyRegisterSyncCalled(mSourceDevice2);
        onSyncEstablished(mSourceDevice2, TEST_SYNC_HANDLE_2);

        // Cancel all syncs by stop searching
        mBassClientService.stopSearchingForSources();
        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(0);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE)).isNull();
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE_2)).isNull();
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE_2))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
        assertThat(mBassClientService.getCachedBroadcast(TEST_BROADCAST_ID)).isNotNull();
        assertThat(mBassClientService.getCachedBroadcast(TEST_BROADCAST_ID_2)).isNotNull();

        // Suspend all receivers, SUSPENDED_BY_HOST
        mBassClientService.suspendAllReceiversSourceSynchronization();
        verifyModifyMessageAndInjectSourceModified();

        // Start searching sources sync to paused broadcaster and remain cache
        startSearchingForSourcesWithAutoSync(mSourceDevice);
        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(0);
        assertThat(mBassClientService.getDeviceForSyncHandle(BassConstants.PENDING_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE_2)).isNull();
        assertThat(
                        mBassClientService.getBroadcastIdForSyncHandle(
                                BassConstants.PENDING_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE_2))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
        assertThat(mBassClientService.getCachedBroadcast(TEST_BROADCAST_ID)).isNotNull();
        assertThat(mBassClientService.getCachedBroadcast(TEST_BROADCAST_ID_2)).isNull();

        // Synced
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        assertThat(mBassClientService.getActiveSyncedSources().size()).isEqualTo(1);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE_2)).isNull();
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(TEST_BROADCAST_ID);
        assertThat(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE_2))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);

        // Resume broadcast
        mBassClientService.resumeReceiversSourceSynchronization();
        verifyRegisterSyncNeverCalled();
        verifyAllGroupMembersGettingUpdateOrAddSource(mBroadcastMetadata1);
        injectRemoteSourceStateSourceAdded(
                mBroadcastMetadata1, /* isPaSynced */ true, /* isBisSynced */ true);
        assertThat(mBassClientService.getCachedBroadcast(TEST_BROADCAST_ID)).isNotNull();
        assertThat(mBassClientService.getCachedBroadcast(TEST_BROADCAST_ID_2)).isNull();
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_ALWAYS_USE_BACKGROUND_SCANNER)
    public void waitingForMetadataUpdate_syncAndRemainCache_onStartSearching() {
        prepareConnectedDeviceGroup();
        // This syncs TEST_BROADCAST_ID (handle 0)
        prepareSyncToSourceAndVerify();

        // Mock not local
        doReturn(null).when(mLeAudioService).getBroadcastMetadata(TEST_BROADCAST_ID);

        // Stop searching to clear active syncs
        mBassClientService.stopSearchingForSources();
        verifyUnregisterSyncCalled();

        // Trigger metadata sync request via remote source addition
        BluetoothLeBroadcastMetadata metadata =
                createBroadcastMetadata(TEST_BROADCAST_ID, mSourceDevice);
        BluetoothLeBroadcastReceiveState receiveState =
                createReceiveState(metadata, TEST_SOURCE_ID);

        // This puts it in mSinksWaitingForMetadata
        mBassClientService
                .getCallbacks()
                .notifySourceAdded(
                        mCurrentDevice, receiveState, BluetoothStatusCodes.REASON_REMOTE_REQUEST);

        // Ensure it is synced (background)
        verifyRegisterSyncCalled(mSourceDevice);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);

        // Start searching
        startSearchingForSources();

        // Verify that broadcast is kept synced
        List<Integer> activeSynced = mBassClientService.getActiveSyncedSources();
        assertThat(activeSynced).contains(TEST_SYNC_HANDLE);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getCachedBroadcast(TEST_BROADCAST_ID)).isNotNull();
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_ALWAYS_USE_BACKGROUND_SCANNER)
    public void pendingSourceOperation_syncAndRemainCache_onStartSearching() {
        prepareConnectedDeviceGroup();
        // This syncs TEST_BROADCAST_ID (handle 0)
        prepareSyncToSourceAndVerify();

        // Stop searching to clear active syncs
        mBassClientService.stopSearchingForSources();
        verifyUnregisterSyncCalled();

        // Mock pending operation on the state machine
        BassClientStateMachine sm = mStateMachines.get(mCurrentDevice);
        doReturn(TEST_BROADCAST_ID).when(sm).getPendingOperationBroadcastId();

        // Start searching
        startSearchingForSources();

        // Verify that broadcast is synced
        verifyRegisterSyncCalled(mSourceDevice);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);

        List<Integer> activeSynced = mBassClientService.getActiveSyncedSources();
        assertThat(activeSynced).contains(TEST_SYNC_HANDLE);
        assertThat(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(mSourceDevice);
        assertThat(mBassClientService.getCachedBroadcast(TEST_BROADCAST_ID)).isNotNull();
    }

    @Test
    public void suspendedByHost_addSameSource() {
        prepareSynchronizedPair();

        // Remove source, SUSPENDED_BY_HOST
        mBassClientService.removeSource(mCurrentDevice, TEST_SOURCE_ID);
        checkNotAllowBroadcastMonitoring();
        verifyRemoveMessageAndInjectSourceRemoval();

        // Verify add source clear the SUSPENDED_BY_HOST
        mBassClientService.addSource(mCurrentDevice, mBroadcastMetadata1, /* isGroupOp */ true);
        addSourceAndVerify(mBroadcastMetadata1);
        checkAllowBroadcastMonitoring();
    }

    @Test
    public void suspendedByHost_removeSource_withoutScanning() {
        prepareSynchronizedPairAndStopSearching();

        // Remove source, SUSPENDED_BY_HOST
        mBassClientService.removeSource(mCurrentDevice, TEST_SOURCE_ID);
        checkNotAllowBroadcastMonitoring();
        verifyRemoveMessageAndInjectSourceRemoval();
    }

    @Test
    public void suspendedByHost_removeSource_duringScanning() {
        prepareSynchronizedPair();

        // Remove source, SUSPENDED_BY_HOST
        mBassClientService.removeSource(mCurrentDevice, TEST_SOURCE_ID);
        checkNotAllowBroadcastMonitoring();
        verifyRemoveMessageAndInjectSourceRemoval();
    }

    @Test
    public void suspendedByHost_stopReceivers_withoutScanning() {
        prepareSynchronizedPairAndStopSearching();

        // Stop receivers, SUSPENDED_BY_HOST
        mBassClientService.stopReceiversSourceSynchronization(TEST_BROADCAST_ID);
        checkNotAllowBroadcastMonitoring();
        verifyRemoveMessageAndInjectSourceRemoval();
    }

    @Test
    public void suspendedByHost_stopReceivers_duringScanning() {
        prepareSynchronizedPair();

        // Stop receivers, SUSPENDED_BY_HOST
        mBassClientService.stopReceiversSourceSynchronization(TEST_BROADCAST_ID);
        checkNotAllowBroadcastMonitoring();
        verifyRemoveMessageAndInjectSourceRemoval();
    }

    @Test
    public void suspendedByHost_suspendAllReceivers_withoutScanning() {
        prepareSynchronizedPairAndStopSearching();

        // Suspend all receivers, SUSPENDED_BY_HOST
        mBassClientService.suspendAllReceiversSourceSynchronization();
        checkNotAllowBroadcastMonitoring();
        checkResumeSynchronizationByHost();
    }

    @Test
    public void suspendedByHost_suspendAllReceivers_duringScanning() {
        prepareSynchronizedPair();

        // Suspend all receivers, SUSPENDED_BY_HOST
        mBassClientService.suspendAllReceiversSourceSynchronization();
        checkNotAllowBroadcastMonitoring();
        checkResumeSynchronizationByHost();
    }

    @Test
    public void suspendedByHost_handleUnicastSourceStreamStatusChange_withoutScanning() {
        prepareSynchronizedPairAndStopSearching();

        /* Unicast would like to stream */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                LeAudioStackEvent.STATUS_LOCAL_STREAM_REQUESTED);
        checkNotAllowBroadcastMonitoring();

        /* Unicast finished streaming */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                LeAudioStackEvent.STATUS_LOCAL_STREAM_SUSPENDED);
        verifyBackgroundSearchStarted();
        verifyRegisterSyncCalled(mSourceDevice);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        assertThat(mBassClientService.isAnySearchInProgress()).isFalse();
        onPeriodicAdvertisingReport();
        onBigInfoAdvertisingReport();
        verifyAllGroupMembersGettingUpdateOrAddSource(mBroadcastMetadata1);
    }

    @Test
    public void suspendedByHost_handleUnicastSourceStreamStatusChange_duringScanning() {
        prepareSynchronizedPair();

        /* Unicast would like to stream */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                LeAudioStackEvent.STATUS_LOCAL_STREAM_REQUESTED);
        checkNotAllowBroadcastMonitoring();

        /* Unicast finished streaming */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                LeAudioStackEvent.STATUS_LOCAL_STREAM_SUSPENDED);
        verifyAllGroupMembersGettingUpdateOrAddSource(mBroadcastMetadata1);
    }

    @Test
    public void suspendedByHost_handleUnicastSourceStreamStatusChange_beforeResumeCompleted() {
        prepareSynchronizedPairAndStopSearching();

        /* Unicast would like to stream */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                LeAudioStackEvent.STATUS_LOCAL_STREAM_REQUESTED);
        checkNotAllowBroadcastMonitoring();

        /* Unicast finished streaming */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                LeAudioStackEvent.STATUS_LOCAL_STREAM_SUSPENDED);
        verifyBackgroundSearchStarted();
        verifyRegisterSyncCalled(mSourceDevice);

        /* Unicast would like to stream again before previous resume was complete*/
        mBassClientService.handleUnicastSourceStreamStatusChange(
                LeAudioStackEvent.STATUS_LOCAL_STREAM_REQUESTED);
        assertThat(mBassClientService.isAnySearchInProgress()).isFalse();
        verifyUnregisterSyncCalled();

        /* Unicast finished streaming */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                LeAudioStackEvent.STATUS_LOCAL_STREAM_SUSPENDED);
        verifyBackgroundSearchStarted();
        verifyRegisterSyncCalled(mSourceDevice);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        assertThat(mBassClientService.isAnySearchInProgress()).isFalse();
        onPeriodicAdvertisingReport();
        onBigInfoAdvertisingReport();
        verifyAllGroupMembersGettingUpdateOrAddSource(mBroadcastMetadata1);
    }

    @Test
    public void suspendedByHost_handleUnicastSourceStreamStatusChangeNoContext_withoutScanning() {
        prepareSynchronizedPairAndStopSearching();

        /* Unicast would like to stream */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                LeAudioStackEvent.STATUS_LOCAL_STREAM_REQUESTED_NO_CONTEXT_VALIDATE);
        checkNotAllowBroadcastMonitoring();
        verifyModifyMessageAndInjectSourceModified();

        /* Unicast finished streaming */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                LeAudioStackEvent.STATUS_LOCAL_STREAM_SUSPENDED);
        verifyBackgroundSearchStarted();
        verifyRegisterSyncCalled(mSourceDevice);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        assertThat(mBassClientService.isAnySearchInProgress()).isFalse();
        onPeriodicAdvertisingReport();
        onBigInfoAdvertisingReport();
        verifyAllGroupMembersGettingUpdateOrAddSource(mBroadcastMetadata1);
    }

    @Test
    public void suspendedByHost_handleUnicastSourceStreamStatusChangeNoContext_duringScanning() {
        prepareSynchronizedPair();

        /* Unicast would like to stream */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                LeAudioStackEvent.STATUS_LOCAL_STREAM_REQUESTED_NO_CONTEXT_VALIDATE);
        checkNotAllowBroadcastMonitoring();
        verifyModifyMessageAndInjectSourceModified();

        /* Unicast finished streaming */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                LeAudioStackEvent.STATUS_LOCAL_STREAM_SUSPENDED);
        verifyAllGroupMembersGettingUpdateOrAddSource(mBroadcastMetadata1);
    }

    @Test
    public void outOfRange_syncEstablishedFailed_stopMonitoringAfterTimeout() {
        prepareSynchronizedPairAndStopSearching();

        // Bis and PA unsynced, BIG_MONITORING
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ false);
        verifyBackgroundSearchStarted();
        verifyRegisterSyncCalled(mSourceDevice);
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_OOR_MONITOR_TIMEOUT);

        onSyncEstablishedFailed(mSourceDevice, TEST_SYNC_HANDLE);
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_OOR_MONITOR_TIMEOUT);
        if (Flags.leaudioBroadcastAlwaysUseBackgroundScanner()) {
            onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        }
        verifyRegisterSyncCalled(mSourceDevice);

        onSyncEstablishedFailed(mSourceDevice, TEST_SYNC_HANDLE);
        if (Flags.leaudioBroadcastAlwaysUseBackgroundScanner()) {
            onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        }
        verifyRegisterSyncCalled(mSourceDevice);

        checkAndDispatchTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_OOR_MONITOR_TIMEOUT);
        assertThat(mBassClientService.isAnySearchInProgress()).isFalse();
        verifyUnregisterSyncCalled();
    }

    @Test
    public void outOfRange_syncEstablishedFailed_clearTimeout() {
        prepareSynchronizedPairAndStopSearching();

        // Bis and PA unsynced, BIG_MONITORING
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ false);
        verifyBackgroundSearchStarted();
        verifyRegisterSyncCalled(mSourceDevice);
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_OOR_MONITOR_TIMEOUT);

        onSyncEstablishedFailed(mSourceDevice, TEST_SYNC_HANDLE);
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_OOR_MONITOR_TIMEOUT);
        if (Flags.leaudioBroadcastAlwaysUseBackgroundScanner()) {
            onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        }
        verifyRegisterSyncCalled(mSourceDevice);

        onSyncEstablishedFailed(mSourceDevice, TEST_SYNC_HANDLE);
        if (Flags.leaudioBroadcastAlwaysUseBackgroundScanner()) {
            onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        }
        verifyRegisterSyncCalled(mSourceDevice);

        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_OOR_MONITOR_TIMEOUT);
        assertThat(mBassClientService.isAnySearchInProgress()).isFalse();
    }

    @Test
    public void outOfRange_syncEstablishedFailed_restartSearching() {
        prepareSynchronizedPairAndStopSearching();

        // Bis and PA unsynced, BIG_MONITORING
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ false);
        verifyBackgroundSearchStarted();
        verifyRegisterSyncCalled(mSourceDevice);
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_OOR_MONITOR_TIMEOUT);

        // Start OOR monitoring
        onSyncEstablishedFailed(mSourceDevice, TEST_SYNC_HANDLE);
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_OOR_MONITOR_TIMEOUT);
        if (Flags.leaudioBroadcastAlwaysUseBackgroundScanner()) {
            onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        }
        verifyRegisterSyncCalled(mSourceDevice);

        // Starting a search should not clear the cache for BIG_MONITORING, which allows
        // register sync again if available or synchronization attempts after stopping the search
        startSearchingForSources();
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);
        onSyncEstablishedFailed(mSourceDevice, TEST_SYNC_HANDLE);

        // During a search, monitored broadcasts are monitored via onScanResult
        // Test below does not guarantee that the cache is preserved; this will be checked later
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        verifyRegisterSyncCalled(mSourceDevice);
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_OOR_MONITOR_TIMEOUT);
        onSyncEstablishedFailed(mSourceDevice, TEST_SYNC_HANDLE);

        mBassClientService.stopSearchingForSources();
        if (!Flags.leaudioBroadcastAlwaysUseBackgroundScanner()) {
            // After a search is stopped, start syncing in a loop for monitored broadcasts
            verifyRegisterSyncCalled(mSourceDevice);

            // Still OOR
            onSyncEstablishedFailed(mSourceDevice, TEST_SYNC_HANDLE);
        }
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_OOR_MONITOR_TIMEOUT);
        if (Flags.leaudioBroadcastAlwaysUseBackgroundScanner()) {
            assertThat(mBassClientService.isAnySearchInProgress()).isTrue();
            onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        }
        verifyRegisterSyncCalled(mSourceDevice);

        // Check if cache is not cleared after start searching by using addSource
        startSearchingForSources();
        onSyncEstablishedFailed(mSourceDevice, TEST_SYNC_HANDLE);
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);

        mBassClientService.addSource(mCurrentDevice, mBroadcastMetadata1, /* isGroupOp */ true);
        verifyRegisterSyncCalled(mSourceDevice);
    }

    @Test
    public void outOfRange_syncEstablishedFailed_allowSyncAnotherBroadcaster() {
        prepareSynchronizedPairAndStopSearching();

        // Bis and PA unsynced, BIG_MONITORING
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ false);
        verifyBackgroundSearchStarted();
        verifyRegisterSyncCalled(mSourceDevice);
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_OOR_MONITOR_TIMEOUT);

        // Start OOR monitoring
        onSyncEstablishedFailed(mSourceDevice, TEST_SYNC_HANDLE);
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_OOR_MONITOR_TIMEOUT);
        if (Flags.leaudioBroadcastAlwaysUseBackgroundScanner()) {
            onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        }
        verifyRegisterSyncCalled(mSourceDevice);

        // Starting a search should not clear the cache for BIG_MONITORING, which allows
        // register sync again if available or synchronization attempts after stopping the search
        startSearchingForSources();
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);

        // Check sync to another broadcaster during OOR monitoring
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);
        onScanResult(mSourceDevice2, TEST_BROADCAST_ID_2);
        onSyncEstablishedFailed(mSourceDevice, TEST_SYNC_HANDLE);
        verifyRegisterSyncCalled(mSourceDevice2);
    }

    @Test
    public void autoSyncToBroadcast_AlreadySyncedToSink_onStartSearching() {
        prepareSynchronizedPairAndStopSearching();

        // Verify that start searching cause sync when broadcaster synced to sinks
        startSearchingForSourcesWithAutoSync(mSourceDevice);
    }

    /**
     * Test resume source will be triggered if device is reconnected and its peer is synced to
     * broadcast source
     */
    @Test
    public void sinkBassStateReady_resumeSourceIfPeerDeviceSynced() throws RemoteException {
        // Imitate broadcast being active
        doReturn(true).when(mLeAudioService).isPlaying(TEST_BROADCAST_ID);
        prepareTwoSynchronizedDevicesForLocalBroadcast();

        // Disconnect device but left source added
        mBassClientService.connectionStateChanged(
                mCurrentDevice, STATE_CONNECTED, STATE_DISCONNECTED);

        mBassClientService.getCallbacks().notifyBassStateReady(mCurrentDevice);
        mLooper.dispatchAll();

        // Verify modify source when source available but not synced to PA/BIS
        for (BassClientStateMachine sm : mStateMachines.values()) {
            if (sm.getDevice().equals(mCurrentDevice1)) {
                verify(sm, never()).sendMessage(any());
            } else if (sm.getDevice().equals(mCurrentDevice)) {
                ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
                verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());

                Message msg =
                        messageCaptor.getAllValues().stream()
                                .filter(m -> (m.what == BassClientStateMachine.UPDATE_BCAST_SOURCE))
                                .findFirst()
                                .orElse(null);
                assertThat(msg).isNotNull();
                clearInvocations(sm);
            } else {
                throw new AssertionError("Unexpected device");
            }
        }

        // Update receiver state with PA and BIS sync
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ true, /* isBisSynced */ true);

        mBassClientService.getCallbacks().notifyBassStateReady(mCurrentDevice);
        mLooper.dispatchAll();

        // No resuming source if device remain synced to PA/BIS
        assertThat(mStateMachines).hasSize(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            verify(sm, never()).sendMessage(any());
        }

        // Remove source on the mCurrentDevice
        injectRemoteSourceStateRemoval(mStateMachines.get(mCurrentDevice), TEST_SOURCE_ID);

        mBassClientService.getCallbacks().notifyBassStateReady(mCurrentDevice);
        mLooper.dispatchAll();

        // Verify add source when source not available
        for (BassClientStateMachine sm : mStateMachines.values()) {
            if (sm.getDevice().equals(mCurrentDevice1)) {
                verify(sm, never()).sendMessage(any());
            } else if (sm.getDevice().equals(mCurrentDevice)) {
                ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
                verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());

                Message msg =
                        messageCaptor.getAllValues().stream()
                                .filter(m -> (m.what == BassClientStateMachine.ADD_BCAST_SOURCE))
                                .findFirst()
                                .orElse(null);
                assertThat(msg).isNotNull();
                clearInvocations(sm);
            } else {
                throw new AssertionError("Unexpected device");
            }
        }
    }

    @Test
    public void sinkBassStateReady_addToPausedSinksIfPausedByHost() throws RemoteException {
        prepareSynchronizedPairAndStopSearching();

        // Disconnect device to remove its data
        injectDeviceDisconnection(mCurrentDevice);

        // Suspend all receivers, SUSPENDED_BY_HOST
        mBassClientService.suspendAllReceiversSourceSynchronization();
        for (BassClientStateMachine sm : mStateMachines.values()) {
            clearInvocations(sm);
        }
        injectRemoteSourceStateChanged(
                mStateMachines.get(mCurrentDevice1), mBroadcastMetadata1, false, false);

        injectDeviceConnection(mCurrentDevice);
        mBassClientService.getCallbacks().notifyBassStateReady(mCurrentDevice);
        mLooper.dispatchAll();

        // No resuming during pause
        assertThat(mStateMachines).hasSize(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            verify(sm, never()).sendMessage(any());
        }

        // Verify resume on both devices
        checkResumeSynchronizationByHost();
    }

    @Test
    public void sinkBassStateReady_addToPausedSinksIfMonitored() throws RemoteException {
        bigMonitoringWithoutScanning();

        // Disconnect device to remove its data
        injectDeviceDisconnection(mCurrentDevice);

        injectDeviceConnection(mCurrentDevice);
        mBassClientService.getCallbacks().notifyBassStateReady(mCurrentDevice);
        mLooper.dispatchAll();

        // No resuming during monitoring
        assertThat(mStateMachines).hasSize(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            verify(sm, never()).sendMessage(any());
        }

        // Verify resume on both devices
        checkResumeSynchronizationByBig();
    }

    /**
     * Test that if a sink connects and its peer has an active source that the sink does not know
     * about, the service will add the peer's source to the newly connected sink to synchronize the
     * group.
     */
    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_ADD_SOURCE_FROM_PEER_DEVICE)
    public void sinkBassStateReady_addNewSourceFromPeer() throws RemoteException {
        prepareConnectedDeviceGroup();
        doReturn(true).when(mLeAudioService).isPlaying(TEST_BROADCAST_ID);
        doReturn(mBroadcastMetadata1).when(mLeAudioService).getBroadcastMetadata(TEST_BROADCAST_ID);
        // Add broadcast source to peer device mCurrentDevice
        mBassClientService.addSource(mCurrentDevice, mBroadcastMetadata1, /* isGroupOp */ false);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            if (sm.getDevice().equals(mCurrentDevice)) {
                clearInvocations(sm);
                injectRemoteSourceStateSourceAdded(
                        sm,
                        mBroadcastMetadata1,
                        TEST_SOURCE_ID,
                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED,
                        mBroadcastMetadata1.isEncrypted()
                                ? BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING
                                : BluetoothLeBroadcastReceiveState
                                        .BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                        null,
                        0L);
                doReturn(mBroadcastMetadata1)
                        .when(sm)
                        .getCurrentBroadcastMetadata(eq(TEST_SOURCE_ID));
                doReturn(true).when(sm).isSyncedToTheSource(eq(TEST_SOURCE_ID));
            }
        }

        mBassClientService.getCallbacks().notifyBassStateReady(mCurrentDevice1);
        mLooper.dispatchAll();

        // Verify adding source is performed on mCurrentDevice1 once BASS state ready
        for (BassClientStateMachine sm : mStateMachines.values()) {
            if (sm.getDevice().equals(mCurrentDevice1)) {
                ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
                verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());

                Message msg =
                        messageCaptor.getAllValues().stream()
                                .filter(m -> (m.what == BassClientStateMachine.ADD_BCAST_SOURCE))
                                .findFirst()
                                .orElse(null);
                assertThat(msg).isNotNull();
                clearInvocations(sm);
            }
        }
    }

    /**
     * Test that if a sink connects and its peer has an active source that the sink does not know
     * about, the service will NOT add the peer's source to the newly connected sink if broadcast is
     * suspended.
     */
    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_ADD_SOURCE_FROM_PEER_DEVICE)
    public void sinkBassStateReady_doNotAddNewSourceFromPeerIfSuspended() throws RemoteException {
        prepareConnectedDeviceGroup();
        doReturn(true).when(mLeAudioService).isPlaying(TEST_BROADCAST_ID);
        doReturn(mBroadcastMetadata1).when(mLeAudioService).getBroadcastMetadata(TEST_BROADCAST_ID);
        // Add broadcast source to peer device mCurrentDevice
        mBassClientService.addSource(mCurrentDevice, mBroadcastMetadata1, /* isGroupOp */ false);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            if (sm.getDevice().equals(mCurrentDevice)) {
                clearInvocations(sm);
                injectRemoteSourceStateSourceAdded(
                        sm,
                        mBroadcastMetadata1,
                        TEST_SOURCE_ID,
                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED,
                        mBroadcastMetadata1.isEncrypted()
                                ? BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING
                                : BluetoothLeBroadcastReceiveState
                                        .BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                        null,
                        0L);
                doReturn(mBroadcastMetadata1)
                        .when(sm)
                        .getCurrentBroadcastMetadata(eq(TEST_SOURCE_ID));
                doReturn(true).when(sm).isSyncedToTheSource(eq(TEST_SOURCE_ID));
            }
        }

        mBassClientService.suspendAllReceiversSourceSynchronization();

        mBassClientService.getCallbacks().notifyBassStateReady(mCurrentDevice1);
        mLooper.dispatchAll();

        // Verify adding source is NOT performed on mCurrentDevice1 once BASS state ready
        for (BassClientStateMachine sm : mStateMachines.values()) {
            if (sm.getDevice().equals(mCurrentDevice1)) {
                ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
                verify(sm, never()).sendMessage(messageCaptor.capture());
            }
        }
    }

    /** Test add pending source when BASS state get ready */
    @Test
    public void sinkBassStateReady_addPendingSource() throws RemoteException {
        prepareConnectedDeviceGroup();
        // Verify adding source when Bass state not ready
        for (BassClientStateMachine sm : mStateMachines.values()) {
            doReturn(false).when(sm).isBassStateReady();
        }
        doReturn(true).when(mLeAudioService).isPlaying(TEST_BROADCAST_ID);
        doReturn(mBroadcastMetadata1).when(mLeAudioService).getBroadcastMetadata(TEST_BROADCAST_ID);
        // Add broadcast source and got queued due to BASS not ready
        mBassClientService.addSource(mCurrentDevice, mBroadcastMetadata1, /* isGroupOp */ false);

        mBassClientService.getCallbacks().notifyBassStateSetupFailed(mCurrentDevice);
        mLooper.dispatchAll();

        // Verify adding source callback is triggered if BASS state initiate failed
        verify(mCallback, atLeast(1))
                .onSourceAddFailed(
                        eq(mCurrentDevice),
                        eq(mBroadcastMetadata1),
                        eq(BluetoothStatusCodes.ERROR_REMOTE_NOT_ENOUGH_RESOURCES));

        // Verify not getting ADD_BCAST_SOURCE message if no pending source to add
        for (BassClientStateMachine sm : mStateMachines.values()) {
            doReturn(true).when(sm).isBassStateReady();
        }
        mBassClientService.getCallbacks().notifyBassStateReady(mCurrentDevice);
        mLooper.dispatchAll();

        for (BassClientStateMachine sm : mStateMachines.values()) {
            if (sm.getDevice().equals(mCurrentDevice)) {
                verify(sm, never()).sendMessage(any());
                clearInvocations(sm);
            }
        }

        for (BassClientStateMachine sm : mStateMachines.values()) {
            doReturn(false).when(sm).isBassStateReady();
        }
        // Add broadcast source and got queued due to BASS not ready
        mBassClientService.addSource(mCurrentDevice, mBroadcastMetadata1, /* isGroupOp */ false);

        for (BassClientStateMachine sm : mStateMachines.values()) {
            doReturn(true).when(sm).isBassStateReady();
        }
        mBassClientService.getCallbacks().notifyBassStateReady(mCurrentDevice);
        mLooper.dispatchAll();

        // Verify adding source is resumed once BASS state ready
        for (BassClientStateMachine sm : mStateMachines.values()) {
            if (sm.getDevice().equals(mCurrentDevice)) {
                ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
                verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());

                Message msg =
                        messageCaptor.getAllValues().stream()
                                .filter(m -> (m.what == BassClientStateMachine.ADD_BCAST_SOURCE))
                                .findFirst()
                                .orElse(null);
                assertThat(msg).isNotNull();
                clearInvocations(sm);
            }
        }
    }

    /** Test add pending source when BASS state get ready */
    @Test
    public void sinkBassStateReady_addPendingSourceGroup_oneByOneReady() throws RemoteException {
        prepareConnectedDeviceGroup();
        // Verify adding source when Bass state not ready
        for (BassClientStateMachine sm : mStateMachines.values()) {
            doReturn(false).when(sm).isBassStateReady();
        }
        doReturn(true).when(mLeAudioService).isPlaying(TEST_BROADCAST_ID);
        doReturn(mBroadcastMetadata1).when(mLeAudioService).getBroadcastMetadata(TEST_BROADCAST_ID);
        // Add broadcast source and got queued due to BASS not ready
        mBassClientService.addSource(mCurrentDevice, mBroadcastMetadata1, /* isGroupOp */ true);

        // First BASS ready
        doReturn(true).when(mStateMachines.get(mCurrentDevice)).isBassStateReady();
        mBassClientService.getCallbacks().notifyBassStateReady(mCurrentDevice);
        mLooper.dispatchAll();

        // Verify adding source is resumed once BASS state ready
        for (BassClientStateMachine sm : mStateMachines.values()) {
            if (sm.getDevice().equals(mCurrentDevice)) {
                ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
                verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());

                Message msg =
                        messageCaptor.getAllValues().stream()
                                .filter(m -> (m.what == BassClientStateMachine.ADD_BCAST_SOURCE))
                                .findFirst()
                                .orElse(null);
                assertThat(msg).isNotNull();
                clearInvocations(sm);
            } else if (sm.getDevice().equals(mCurrentDevice1)) {
                verify(sm, never()).sendMessage(any());
            }
        }

        // Second BASS ready
        doReturn(true).when(mStateMachines.get(mCurrentDevice1)).isBassStateReady();
        mBassClientService.getCallbacks().notifyBassStateReady(mCurrentDevice1);
        mLooper.dispatchAll();

        // Verify adding source is resumed once BASS state ready
        for (BassClientStateMachine sm : mStateMachines.values()) {
            if (sm.getDevice().equals(mCurrentDevice)) {
                verify(sm, never()).sendMessage(any());
            } else if (sm.getDevice().equals(mCurrentDevice1)) {
                ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
                verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());

                Message msg =
                        messageCaptor.getAllValues().stream()
                                .filter(m -> (m.what == BassClientStateMachine.ADD_BCAST_SOURCE))
                                .findFirst()
                                .orElse(null);
                assertThat(msg).isNotNull();
                clearInvocations(sm);
            }
        }
    }

    @Test
    public void testIsLocalBroadcast() {
        int broadcastId = 12345;

        BluetoothLeBroadcastMetadata metadata = createBroadcastMetadata(broadcastId);
        BluetoothLeBroadcastReceiveState receiveState =
                new BluetoothLeBroadcastReceiveState(
                        TEST_SOURCE_ID,
                        metadata.getSourceAddressType(),
                        metadata.getSourceDevice(),
                        metadata.getSourceAdvertisingSid(),
                        metadata.getBroadcastId(),
                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED,
                        BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                        null,
                        metadata.getSubgroups().size(),
                        // Bis sync states
                        metadata.getSubgroups().stream().map(e -> 1L).collect(Collectors.toList()),
                        metadata.getSubgroups().stream()
                                .map(e -> e.getContentMetadata())
                                .collect(Collectors.toList()));

        /* External broadcast check */
        doReturn(null).when(mLeAudioService).getBroadcastMetadata(broadcastId);

        assertThat(mBassClientService.isLocalBroadcast(metadata)).isFalse();
        assertThat(mBassClientService.isLocalBroadcast(receiveState)).isFalse();

        /* Local broadcast check */
        doReturn(metadata).when(mLeAudioService).getBroadcastMetadata(broadcastId);

        assertThat(mBassClientService.isLocalBroadcast(metadata)).isTrue();
        assertThat(mBassClientService.isLocalBroadcast(receiveState)).isTrue();
    }

    private void verifyInitiatePaSyncTransferAndNoOthers(int syncHandle, int sourceId) {
        expect.that(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());
            long count;
            if (sm.getDevice().equals(mCurrentDevice)) {
                count =
                        messageCaptor.getAllValues().stream()
                                .filter(
                                        m ->
                                                (m.what
                                                                == BassClientStateMachine
                                                                        .INITIATE_PA_SYNC_TRANSFER)
                                                        && (m.arg1 == syncHandle)
                                                        && (m.arg2 == sourceId))
                                .count();
                assertThat(count).isEqualTo(1);
                count =
                        messageCaptor.getAllValues().stream()
                                .filter(
                                        m ->
                                                m.what
                                                        != BassClientStateMachine
                                                                .INITIATE_PA_SYNC_TRANSFER)
                                .count();
                assertThat(count).isEqualTo(0);
            } else if (sm.getDevice().equals(mCurrentDevice1)) {
                count =
                        messageCaptor.getAllValues().stream()
                                .filter(
                                        m ->
                                                (m.what
                                                                == BassClientStateMachine
                                                                        .INITIATE_PA_SYNC_TRANSFER)
                                                        && (m.arg1 == syncHandle)
                                                        && (m.arg2 == sourceId + 1))
                                .count();
                assertThat(count).isEqualTo(1);
                count =
                        messageCaptor.getAllValues().stream()
                                .filter(
                                        m ->
                                                m.what
                                                        != BassClientStateMachine
                                                                .INITIATE_PA_SYNC_TRANSFER)
                                .count();
                assertThat(count).isEqualTo(0);
            } else {
                throw new AssertionError("Unexpected device");
            }
        }
    }

    @Test
    public void initiatePaSyncTransfer() {
        prepareSynchronizedPairAndStopSearching();

        // Sync info request force syncing to broadcaster and add sinks pending for PAST
        mBassClientService.syncRequestForPast(mCurrentDevice, TEST_BROADCAST_ID, TEST_SOURCE_ID);
        mBassClientService.syncRequestForPast(
                mCurrentDevice1, TEST_BROADCAST_ID, TEST_SOURCE_ID + 1);
        verifyBackgroundSearchStarted();
        verifyRegisterSyncCalled(mSourceDevice);

        // Sync will INITIATE_PA_SYNC_TRANSFER
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        assertThat(mBassClientService.isAnySearchInProgress()).isFalse();
        verifyInitiatePaSyncTransferAndNoOthers(TEST_SYNC_HANDLE, TEST_SOURCE_ID);
    }

    @Test
    public void initiatePaSyncTransfer_concurrentWithResume() {
        prepareSynchronizedPairAndStopSearching();

        // Cache sinks for resume and set SUSPENDED_BY_HOST pause
        mBassClientService.handleUnicastSourceStreamStatusChange(
                LeAudioStackEvent.STATUS_LOCAL_STREAM_REQUESTED);
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ false);

        // Resume source and trigger sync info request from sink side
        mBassClientService.resumeReceiversSourceSynchronization();
        verifyBackgroundSearchStarted();
        verifyRegisterSyncCalled(mSourceDevice);

        // Sync info request add sinks pending for PAST
        mBassClientService.syncRequestForPast(mCurrentDevice, TEST_BROADCAST_ID, TEST_SOURCE_ID);
        mBassClientService.syncRequestForPast(
                mCurrentDevice1, TEST_BROADCAST_ID, TEST_SOURCE_ID + 1);

        // Sync will send INITIATE_PA_SYNC_TRANSFER
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        assertThat(mBassClientService.isAnySearchInProgress()).isFalse();
        verifyInitiatePaSyncTransferAndNoOthers(TEST_SYNC_HANDLE, TEST_SOURCE_ID);
    }

    @Test
    public void resumeSourceSynchronization_omitWhenPaRequestedOrBisSynced() {
        prepareSynchronizedPair();

        // Cache sinks for resume and set SUSPENDED_BY_HOST pause
        // Try resume while sync info requested
        mBassClientService.handleUnicastSourceStreamStatusChange(
                LeAudioStackEvent.STATUS_LOCAL_STREAM_REQUESTED);
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1,
                BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCINFO_REQUEST,
                /* isBisSynced */ false);
        checkNoResumeSynchronizationByHost();

        // Try resume while BIS synced
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ true);
        checkNoResumeSynchronizationByHost();

        // Cache sinks for resume and set SUSPENDED_BY_HOST pause
        // Try resume while pa unsynced
        mBassClientService.handleUnicastSourceStreamStatusChange(
                LeAudioStackEvent.STATUS_LOCAL_STREAM_REQUESTED);
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ false);
        checkResumeSynchronizationByHost();
    }

    @Test
    public void multipleSinkMetadata_clearWhenSourceAddFailed() throws RemoteException {
        prepareSynchronizedPair();
        mBassClientService.stopSearchingForSources();

        // Add source to try sync again ended with source add failed, should remove metadata
        mBassClientService.addSource(mCurrentDevice, mBroadcastMetadata1, /* isGroupOp */ true);
        verifyBackgroundSearchStarted();
        verifyRegisterSyncCalled(mSourceDevice);
        onSyncEstablishedFailed(mSourceDevice, TEST_SYNC_HANDLE);
        mLooper.dispatchAll();
        verify(mCallback)
                .onSourceAddFailed(
                        eq(mCurrentDevice),
                        eq(mBroadcastMetadata1),
                        eq(BluetoothStatusCodes.ERROR_LOCAL_NOT_ENOUGH_RESOURCES));
        verify(mCallback)
                .onSourceAddFailed(
                        eq(mCurrentDevice1),
                        eq(mBroadcastMetadata1),
                        eq(BluetoothStatusCodes.ERROR_LOCAL_NOT_ENOUGH_RESOURCES));
        assertThat(mBassClientService.isAnySearchInProgress()).isFalse();

        startSearchingForSourcesWithAutoSync(mSourceDevice);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ false);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            clearInvocations(sm);
        }

        // Cache and resume should not resume at all
        mBassClientService.cacheSuspendingSources(TEST_BROADCAST_ID);
        mBassClientService.resumeReceiversSourceSynchronization();
        assertThat(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            verify(sm, never()).sendMessage(any());
        }
    }

    @Test
    public void multipleSinkMetadata_clearWhenSwitch() {
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();
        addSourceAndVerify(mBroadcastMetadata1);
        injectRemoteSourceStateSourceAdded(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ false);

        // Add another new broadcast source should remove old metadata during switch
        onScanResult(mSourceDevice2, TEST_BROADCAST_ID_2);
        onSyncEstablished(mSourceDevice2, TEST_SYNC_HANDLE_2);
        mBassClientService.addSource(mCurrentDevice, mBroadcastMetadata2, /* isGroupOp */ true);
        verifyAllGroupMembersGettingUpdateOrAddSource(mBroadcastMetadata2);

        BassClientStateMachine sm1 = mStateMachines.get(mCurrentDevice);
        BassClientStateMachine sm2 = mStateMachines.get(mCurrentDevice1);

        // Mock pending switch operation
        doReturn(true).when(sm1).hasPendingSwitchingSourceOperation(TEST_BROADCAST_ID_2);
        doReturn(true).when(sm2).hasPendingSwitchingSourceOperation(TEST_BROADCAST_ID_2);

        // Simulate source removal
        injectRemoteSourceStateRemoval(sm1, TEST_SOURCE_ID);
        injectRemoteSourceStateRemoval(sm2, TEST_SOURCE_ID + 1);

        clearInvocations(sm1);
        clearInvocations(sm2);
        injectRemoteSourceStateSourceAdded(
                mBroadcastMetadata2, /* isPaSynced */ false, /* isBisSynced */ false);

        // Cache and resume should resume only new broadcast
        mBassClientService.cacheSuspendingSources(TEST_BROADCAST_ID_2);
        mBassClientService.resumeReceiversSourceSynchronization();
        // Verify that only one message per sink was sent
        verify(sm1).sendMessage(any());
        verify(sm2).sendMessage(any());

        // And this message is to resume broadcast
        verifyAllGroupMembersGettingUpdateOrAddSource(mBroadcastMetadata2);
    }

    @Test
    public void multipleSinkMetadata_clearWhenSwitch_duringSuspend() {
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();
        addSourceAndVerify(mBroadcastMetadata1);
        injectRemoteSourceStateSourceAdded(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ false);

        /* Unicast would like to stream */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                LeAudioStackEvent.STATUS_LOCAL_STREAM_REQUESTED_NO_CONTEXT_VALIDATE);
        verifyModifyMessageAndInjectSourceModified();
        for (BassClientStateMachine sm : mStateMachines.values()) {
            clearInvocations(sm);
        }

        // Add another new broadcast source should remove old metadata
        onScanResult(mSourceDevice2, TEST_BROADCAST_ID_2);
        onSyncEstablished(mSourceDevice2, TEST_SYNC_HANDLE_2);
        mBassClientService.addSource(mCurrentDevice, mBroadcastMetadata2, /* isGroupOp */ true);
        verifyAllGroupMembersGettingUpdateOrAddSource(mBroadcastMetadata2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            clearInvocations(sm);
        }
        injectRemoteSourceStateSourceAdded(
                mBroadcastMetadata2, /* isPaSynced */ false, /* isBisSynced */ false);

        // Cache and resume should resume only new broadcast
        mBassClientService.cacheSuspendingSources(TEST_BROADCAST_ID_2);
        mBassClientService.resumeReceiversSourceSynchronization();
        // Verify that only one message per sink was sent
        for (BassClientStateMachine sm : mStateMachines.values()) {
            verify(sm).sendMessage(any());
        }
        // And this message is to resume broadcast
        verifyAllGroupMembersGettingUpdateOrAddSource(mBroadcastMetadata2);
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_ALWAYS_USE_BACKGROUND_SCANNER)
    public void testStopSearchingForSources_stopsScanner_whenSyncedToAllKeptBroadcasts() {
        prepareConnectedDeviceGroup();
        // Sync to a broadcast
        prepareSyncToSourceAndVerify();

        // Mock not local
        doReturn(null).when(mLeAudioService).getBroadcastMetadata(TEST_BROADCAST_ID);

        // Trigger metadata sync request via remote source addition to make it "kept"
        BluetoothLeBroadcastMetadata metadata =
                createBroadcastMetadata(TEST_BROADCAST_ID, mSourceDevice);
        BluetoothLeBroadcastReceiveState receiveState =
                createReceiveState(metadata, TEST_SOURCE_ID);

        mBassClientService
                .getCallbacks()
                .notifySourceAdded(
                        mCurrentDevice, receiveState, BluetoothStatusCodes.REASON_REMOTE_REQUEST);

        // Stop searching
        mBassClientService.stopSearchingForSources();

        // Verify scanner stopped because we are already synced
        mInOrderScanController.verify(mScanController).stopScan(anyInt());

        // Verify sync maintained
        assertThat(mBassClientService.getActiveSyncedSources()).contains(TEST_SYNC_HANDLE);

        // Inject Periodic Advertising Report to satisfy metadata update (provides BaseData)
        onPeriodicAdvertisingReport();

        // Verify sync is unregistered as we don't need it anymore
        verifyUnregisterSyncCalled();
        assertThat(mBassClientService.getActiveSyncedSources()).doesNotContain(TEST_SYNC_HANDLE);
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_ALWAYS_USE_BACKGROUND_SCANNER)
    public void testStopSearchingForSources_keepsScanner_whenNotSyncedToKeptBroadcast() {
        prepareConnectedDeviceGroup();
        startSearchingForSources();

        // Inject scan result so addSource can find it in cache
        onScanResult(mSourceDevice2, TEST_BROADCAST_ID_2);

        // Add source (this triggers background scan and adds to pending sources)
        BluetoothLeBroadcastMetadata metadata =
                createBroadcastMetadata(TEST_BROADCAST_ID_2, mSourceDevice2);
        mBassClientService.addSource(mCurrentDevice, metadata, true);

        // Verify sync is registered for the new source
        verifyRegisterSyncCalled(mSourceDevice2);

        // Stop foreground search
        mBassClientService.stopSearchingForSources();

        // Verify scanner is STILL active (background) because we are waiting for
        // TEST_BROADCAST_ID_2
        assertThat(mBassClientService.isAnySearchInProgress()).isTrue();
        mInOrderScanController.verify(mScanController, never()).stopScan(anyInt());

        // Establish sync
        onSyncEstablished(mSourceDevice2, TEST_SYNC_HANDLE_2);

        // Verify scanner is stopped as we synced to the pending source
        mInOrderScanController.verify(mScanController).stopScan(anyInt());

        // Verify sync is still active (waiting for remote to sync)
        assertThat(mBassClientService.getActiveSyncedSources()).contains(TEST_SYNC_HANDLE_2);

        // Inject remote source added (synced)
        injectRemoteSourceStateSourceAdded(
                mStateMachines.get(mCurrentDevice),
                metadata,
                TEST_SOURCE_ID + 2,
                BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED,
                BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                null,
                0L);

        // Verify sync is unregistered
        verifyUnregisterSyncCalled();
        assertThat(mBassClientService.getActiveSyncedSources()).doesNotContain(TEST_SYNC_HANDLE_2);
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_ALWAYS_USE_BACKGROUND_SCANNER)
    public void testOnSearchStoppedCallback_foregroundVsBackground() throws RemoteException {
        prepareConnectedDeviceGroup();

        // 1. Start foreground search
        startSearchingForSources();
        mLooper.dispatchAll();
        verify(mCallback).onSearchStarted(BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST);

        // 2. Stop foreground search -> Expect callback
        mBassClientService.stopSearchingForSources();
        mLooper.dispatchAll();
        verify(mCallback).onSearchStopped(BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST);
        assertThat(mBassClientService.isAnySearchInProgress()).isFalse();

        // 3. Start foreground search again
        startSearchingForSources();
        clearInvocations(mCallback);

        // 4. Trigger background requirement (Add Source)
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        BluetoothLeBroadcastMetadata metadata =
                createBroadcastMetadata(TEST_BROADCAST_ID, mSourceDevice);
        mBassClientService.addSource(mCurrentDevice, metadata, true);

        // 5. Stop foreground search
        mBassClientService.stopSearchingForSources();
        mLooper.dispatchAll();

        // Expect callback because foreground search stopped
        verify(mCallback).onSearchStopped(anyInt());
        // But scanner should still be running (background)
        assertThat(mBassClientService.isAnySearchInProgress()).isTrue();

        // 6. Satisfy background requirement (Sync established)
        verifyRegisterSyncCalled(mSourceDevice);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);

        // Scanner should stop now
        mInOrderScanController.verify(mScanController).stopScan(anyInt());
        assertThat(mBassClientService.isAnySearchInProgress()).isFalse();

        // Expect NO additional onSearchStopped callback (background stop shouldn't trigger it)
        // We already verified one call in step 5.
        mLooper.dispatchAll();
        verify(mCallback, times(1)).onSearchStopped(BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST);
    }

    @Test
    public void testOnSearchStopFailedCallback() throws RemoteException {
        prepareConnectedDeviceGroup();

        // Stop searching without starting it
        mBassClientService.stopSearchingForSources();
        mLooper.dispatchAll();

        verify(mCallback).onSearchStopFailed(BluetoothStatusCodes.ERROR_ALREADY_IN_TARGET_STATE);
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_ALWAYS_USE_BACKGROUND_SCANNER)
    public void testStartSearchingForSources_stateTransitions() throws RemoteException {
        prepareConnectedDeviceGroup();

        // 1. First start (Foreground)
        startSearchingForSources();
        mLooper.dispatchAll();
        verify(mCallback).onSearchStarted(BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST);
        assertThat(mBassClientService.isSearchInProgress()).isTrue();
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);

        // 2. Already started (Foreground) -> Fail
        clearInvocations(mCallback);
        List<ScanFilter> scanFilters = new ArrayList<>();
        mBassClientService.startSearchingForSources(scanFilters);
        mLooper.dispatchAll();
        verify(mCallback).onSearchStartFailed(BluetoothStatusCodes.ERROR_ALREADY_IN_TARGET_STATE);
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);

        // 3. Stop Foreground
        mBassClientService.stopSearchingForSources();
        mLooper.dispatchAll();
        verify(mCallback).onSearchStopped(BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST);
        assertThat(mBassClientService.isSearchInProgress()).isFalse();

        // 4. Background Scan Start (via addSource)
        BluetoothLeBroadcastMetadata meta =
                createBroadcastMetadata(TEST_BROADCAST_ID, mSourceDevice);
        mBassClientService.addSource(mCurrentDevice, meta, true);

        // Verify background scan is active
        assertThat(mBassClientService.isAnySearchInProgress()).isTrue();
        assertThat(mBassClientService.isSearchInProgress()).isFalse();

        // 5. Start Foreground while Background active
        clearInvocations(mCallback);
        startSearchingForSources();
        mLooper.dispatchAll();

        // Should succeed and notify started
        verify(mCallback).onSearchStarted(BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST);
        assertThat(mBassClientService.isSearchInProgress()).isTrue();
    }

    @Test
    public void multipleSinkMetadata_clearWhenRemove() {
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();
        mBassClientService.addSource(mCurrentDevice, mBroadcastMetadata1, /* isGroupOp */ false);
        mBassClientService.addSource(mCurrentDevice1, mBroadcastMetadata1, /* isGroupOp */ false);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            clearInvocations(sm);
        }
        injectRemoteSourceStateSourceAdded(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ false);

        // Remove source should remove metadata
        // Do not clear receive state
        mBassClientService.removeSource(mCurrentDevice, TEST_SOURCE_ID);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            clearInvocations(sm);
        }

        // Cache and resume should resume only one broadcaster
        mBassClientService.cacheSuspendingSources(TEST_BROADCAST_ID);
        mBassClientService.resumeReceiversSourceSynchronization();
        assertThat(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            if (sm.getDevice().equals(mCurrentDevice)) {
                verify(sm, never()).sendMessage(any());
                clearInvocations(sm);
            } else if (sm.getDevice().equals(mCurrentDevice1)) {
                ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
                verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());

                Message msg =
                        messageCaptor.getAllValues().stream()
                                .filter(m -> (m.what == BassClientStateMachine.UPDATE_BCAST_SOURCE))
                                .findFirst()
                                .orElse(null);
                assertThat(msg).isNotNull();
                clearInvocations(sm);
            }
        }

        // Remove source should remove metadata
        // Do not clear receive state
        mBassClientService.removeSource(mCurrentDevice1, TEST_SOURCE_ID + 1);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            clearInvocations(sm);
        }

        // Cache and resume should not resume at all
        mBassClientService.cacheSuspendingSources(TEST_BROADCAST_ID);
        mBassClientService.resumeReceiversSourceSynchronization();
        assertThat(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            verify(sm, never()).sendMessage(any());
        }
    }

    @Test
    public void multipleSinkMetadata_clearWhenAllDisconnected() {
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();
        addSourceAndVerify(mBroadcastMetadata1);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            clearInvocations(sm);
        }
        injectRemoteSourceStateSourceAdded(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ false);

        // Disconnect first sink not cause removing metadata
        injectDeviceDisconnection(mCurrentDevice);

        // Connect again first sink
        injectDeviceConnection(mCurrentDevice);
        injectRemoteSourceStateSourceAdded(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ false);

        // Cache and resume should resume all devices
        mBassClientService.cacheSuspendingSources(TEST_BROADCAST_ID);
        mBassClientService.resumeReceiversSourceSynchronization();
        verifyAllGroupMembersGettingUpdateOrAddSource(mBroadcastMetadata1);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            clearInvocations(sm);
        }

        // Disconnect first sink not cause removing metadata
        injectDeviceDisconnection(mCurrentDevice);

        // Disconnect second sink cause remove metadata for both devices
        injectDeviceDisconnection(mCurrentDevice1);

        // Connect again both devices
        injectDeviceConnection(mCurrentDevice);
        injectDeviceConnection(mCurrentDevice1);
        injectRemoteSourceStateSourceAdded(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ false);

        // Cache and resume should not resume at all
        mBassClientService.cacheSuspendingSources(TEST_BROADCAST_ID);
        mBassClientService.resumeReceiversSourceSynchronization();
        assertThat(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            verify(sm, never()).sendMessage(any());
        }
    }

    @Test
    public void multipleSinkMetadata_clearWhenAllDisconnected_duringSuspend() {
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();
        addSourceAndVerify(mBroadcastMetadata1);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            clearInvocations(sm);
        }
        injectRemoteSourceStateSourceAdded(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ false);

        /* Unicast would like to stream */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                LeAudioStackEvent.STATUS_LOCAL_STREAM_REQUESTED_NO_CONTEXT_VALIDATE);
        verifyModifyMessageAndInjectSourceModified();
        for (BassClientStateMachine sm : mStateMachines.values()) {
            clearInvocations(sm);
        }

        // Disconnect first sink not cause removing metadata
        injectDeviceDisconnection(mCurrentDevice);

        // Connect again first sink
        injectDeviceConnection(mCurrentDevice);
        injectRemoteSourceStateSourceAdded(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ false);

        // Cache and resume should resume all devices
        mBassClientService.cacheSuspendingSources(TEST_BROADCAST_ID);
        mBassClientService.resumeReceiversSourceSynchronization();
        verifyAllGroupMembersGettingUpdateOrAddSource(mBroadcastMetadata1);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            clearInvocations(sm);
        }

        /* Unicast would like to stream */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                LeAudioStackEvent.STATUS_LOCAL_STREAM_REQUESTED_NO_CONTEXT_VALIDATE);
        verifyModifyMessageAndInjectSourceModified();
        for (BassClientStateMachine sm : mStateMachines.values()) {
            clearInvocations(sm);
        }

        // Disconnect first sink not cause removing metadata
        injectDeviceDisconnection(mCurrentDevice);

        // Disconnect second sink cause remove metadata for both devices
        injectDeviceDisconnection(mCurrentDevice1);

        // Connect again both devices
        injectDeviceConnection(mCurrentDevice);
        injectDeviceConnection(mCurrentDevice1);
        injectRemoteSourceStateSourceAdded(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ false);

        // Cache and resume should not resume at all
        mBassClientService.cacheSuspendingSources(TEST_BROADCAST_ID);
        mBassClientService.resumeReceiversSourceSynchronization();
        assertThat(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            verify(sm, never()).sendMessage(any());
        }
    }

    @Test
    public void multipleSinkMetadata_clearWhenRemove_oneDisconnectedFirst() {
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();
        mBassClientService.addSource(mCurrentDevice, mBroadcastMetadata1, /* isGroupOp */ false);
        mBassClientService.addSource(mCurrentDevice1, mBroadcastMetadata1, /* isGroupOp */ false);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            clearInvocations(sm);
        }
        injectRemoteSourceStateSourceAdded(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ false);

        // Disconnect first sink not cause removing metadata
        injectDeviceDisconnection(mCurrentDevice);

        // Remove second source should remove metadata for both
        // Do not clear receive state
        mBassClientService.removeSource(mCurrentDevice1, TEST_SOURCE_ID + 1);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            clearInvocations(sm);
        }

        // Connect again first sink
        injectDeviceConnection(mCurrentDevice);
        injectRemoteSourceStateSourceAdded(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ false);

        // Cache and resume should not resume at all
        mBassClientService.cacheSuspendingSources(TEST_BROADCAST_ID);
        mBassClientService.resumeReceiversSourceSynchronization();
        assertThat(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            verify(sm, never()).sendMessage(any());
        }
    }

    @Test
    public void multipleSinkMetadata_clearWhenRemove_oneDisconnectedFirst_duringSuspend() {
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();
        mBassClientService.addSource(mCurrentDevice, mBroadcastMetadata1, /* isGroupOp */ false);
        mBassClientService.addSource(mCurrentDevice1, mBroadcastMetadata1, /* isGroupOp */ false);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            clearInvocations(sm);
        }
        injectRemoteSourceStateSourceAdded(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ false);

        /* Unicast would like to stream */
        mBassClientService.handleUnicastSourceStreamStatusChange(
                LeAudioStackEvent.STATUS_LOCAL_STREAM_REQUESTED_NO_CONTEXT_VALIDATE);
        verifyModifyMessageAndInjectSourceModified();
        for (BassClientStateMachine sm : mStateMachines.values()) {
            clearInvocations(sm);
        }

        // Disconnect first sink not cause removing metadata
        injectDeviceDisconnection(mCurrentDevice);

        // Remove second source should remove metadata for both
        // Do not clear receive state
        mBassClientService.removeSource(mCurrentDevice1, TEST_SOURCE_ID + 1);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            clearInvocations(sm);
        }

        // Connect again first sink
        injectDeviceConnection(mCurrentDevice);
        injectRemoteSourceStateSourceAdded(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ false);

        // Cache and resume should not resume at all
        mBassClientService.cacheSuspendingSources(TEST_BROADCAST_ID);
        mBassClientService.resumeReceiversSourceSynchronization();
        assertThat(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            verify(sm, never()).sendMessage(any());
        }
    }

    @Test
    public void clearPendingSourceToAdd_oneByOne_whenDisconnected() {
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();

        // Sync lost without triggering timeout to keep cache
        onSyncLost();

        // Add source force syncing to broadcaster and add sinks to pendingSourcesToAdd
        mBassClientService.addSource(mCurrentDevice, mBroadcastMetadata1, /* isGroupOp */ false);
        mBassClientService.addSource(mCurrentDevice1, mBroadcastMetadata1, /* isGroupOp */ false);

        // Disconnect first sink should remove pendingSourceToAdd for it
        injectDeviceDisconnection(mCurrentDevice);

        // Sync established should add source on only one sink
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        assertThat(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            if (sm.getDevice().equals(mCurrentDevice)) {
                verify(sm, never()).sendMessage(any());
            } else if (sm.getDevice().equals(mCurrentDevice1)) {
                ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
                verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());

                Message msg =
                        messageCaptor.getAllValues().stream()
                                .filter(m -> (m.what == BassClientStateMachine.ADD_BCAST_SOURCE))
                                .findFirst()
                                .orElse(null);
                assertThat(msg).isNotNull();
                clearInvocations(sm);
            } else {
                throw new AssertionError("Unexpected device");
            }
        }
    }

    @Test
    public void clearPendingSourceToAdd_group_whenDisconnected() {
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();

        // Sync lost without triggering timeout to keep cache
        onSyncLost();

        // Add source force syncing to broadcaster and add sinks to pendingSourcesToAdd
        mBassClientService.addSource(mCurrentDevice, mBroadcastMetadata1, /* isGroupOp */ true);

        // Disconnect first sink should remove pendingSourceToAdd for it
        injectDeviceDisconnection(mCurrentDevice);

        // Sync established should add source on only one sink
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        assertThat(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            if (sm.getDevice().equals(mCurrentDevice)) {
                verify(sm, never()).sendMessage(any());
            } else if (sm.getDevice().equals(mCurrentDevice1)) {
                ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
                verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());

                Message msg =
                        messageCaptor.getAllValues().stream()
                                .filter(m -> (m.what == BassClientStateMachine.ADD_BCAST_SOURCE))
                                .findFirst()
                                .orElse(null);
                assertThat(msg).isNotNull();
                clearInvocations(sm);
            } else {
                throw new AssertionError("Unexpected device");
            }
        }
    }

    @Test
    public void doNotAllowDuplicatesInAddSelectSource() {
        prepareSynchronizedPairAndStopSearching();

        mBassClientService.addSelectSourceRequest(TEST_BROADCAST_ID, /* hasPriority */ true);
        verifyBackgroundSearchStarted();
        verifyRegisterSyncCalled(mSourceDevice);

        mBassClientService.addSelectSourceRequest(TEST_BROADCAST_ID, /* hasPriority */ true);

        // On sync failed should be no more sync registration
        onSyncEstablishedFailed(mSourceDevice, TEST_SYNC_HANDLE);
        assertThat(mBassClientService.isAnySearchInProgress()).isFalse();
        verifyRegisterSyncNeverCalled();
    }

    @Test
    public void sinkDisconnectionDuringResuming() {
        prepareSynchronizedPairAndStopSearching();

        mBassClientService.suspendAllReceiversSourceSynchronization();
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ false);

        // Prepare disconnection of one sink
        doReturn(STATE_DISCONNECTED).when(mStateMachines.get(mCurrentDevice)).getConnectionState();
        doReturn(false).when(mStateMachines.get(mCurrentDevice)).isConnected();
        doAnswer(
                        invocation -> {
                            mBassClientService.connectionStateChanged(
                                    mCurrentDevice, STATE_CONNECTED, STATE_DISCONNECTED);
                            return null;
                        })
                .when(mScanController)
                .registerSync(any(), anyInt(), anyInt(), anyInt(), any());

        mBassClientService.resumeReceiversSourceSynchronization();
    }

    @Test
    public void broadcastMonitoringOnResume_ResumeByBig() {
        prepareSynchronizedPairAndStopSearching();

        // Handover to unicast
        mBassClientService.cacheSuspendingSources(TEST_BROADCAST_ID);
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ false);
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_OOR_MONITOR_TIMEOUT);

        // Resume
        mBassClientService.resumeReceiversSourceSynchronization();
        verifyBackgroundSearchStarted();
        verifyRegisterSyncCalled(mSourceDevice);
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_OOR_MONITOR_TIMEOUT);

        // Sync
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        assertThat(mBassClientService.isAnySearchInProgress()).isFalse();
        checkResumeSynchronizationByBig();
        resyncAndVerifyWithUnsync();
    }

    @Test
    public void broadcastMonitoringOnResume_outOfRange() {
        prepareSynchronizedPairAndStopSearching();

        // Handover to unicast
        mBassClientService.cacheSuspendingSources(TEST_BROADCAST_ID);
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ false);
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_OOR_MONITOR_TIMEOUT);

        // Resume
        mBassClientService.resumeReceiversSourceSynchronization();
        verifyBackgroundSearchStarted();
        verifyRegisterSyncCalled(mSourceDevice);
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_OOR_MONITOR_TIMEOUT);

        // Out of range or other sync failed
        onSyncEstablishedFailed(mSourceDevice, TEST_SYNC_HANDLE);
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_OOR_MONITOR_TIMEOUT);
        if (Flags.leaudioBroadcastAlwaysUseBackgroundScanner()) {
            onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        }
        verifyRegisterSyncCalled(mSourceDevice);

        // Sync
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_OOR_MONITOR_TIMEOUT);
        checkResumeSynchronizationByBig();
        resyncAndVerifyWithUnsync();
        assertThat(mBassClientService.isAnySearchInProgress()).isFalse();
    }

    @Test
    public void broadcastMonitoringOnResume_failedSyncOnPastRequest() {
        prepareSynchronizedPairAndStopSearching();

        // Handover to unicast
        mBassClientService.cacheSuspendingSources(TEST_BROADCAST_ID);
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ false);
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_OOR_MONITOR_TIMEOUT);

        // Resume
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1,
                BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCINFO_REQUEST,
                false);
        mBassClientService.syncRequestForPast(mCurrentDevice, TEST_BROADCAST_ID, TEST_SOURCE_ID);
        mBassClientService.syncRequestForPast(
                mCurrentDevice1, TEST_BROADCAST_ID, TEST_SOURCE_ID + 1);
        verifyBackgroundSearchStarted();
        verifyRegisterSyncCalled(mSourceDevice);
        mBassClientService.resumeReceiversSourceSynchronization();
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_OOR_MONITOR_TIMEOUT);

        // Out of range or other sync failed
        onSyncEstablishedFailed(mSourceDevice, TEST_SYNC_HANDLE);
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_OOR_MONITOR_TIMEOUT);
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ false);
        if (Flags.leaudioBroadcastAlwaysUseBackgroundScanner()) {
            onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        }
        verifyRegisterSyncCalled(mSourceDevice);

        // Sync
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_OOR_MONITOR_TIMEOUT);
        checkResumeSynchronizationByBig();
        resyncAndVerifyWithUnsync();
        assertThat(mBassClientService.isAnySearchInProgress()).isFalse();
    }

    @Test
    @DisableFlags(Flags.FLAG_LEAUDIO_BROADCAST_TREAT_EMPTY_RS_EXPLICITLY)
    public void broadcastMonitoringOnResume_stopSourceReceivers() {
        prepareSynchronizedPairAndStopSearching();

        // Handover to unicast
        mBassClientService.cacheSuspendingSources(TEST_BROADCAST_ID);
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ false);
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_OOR_MONITOR_TIMEOUT);

        // injectSourceRemoval
        injectRemoteSourceStateRemoval(mStateMachines.get(mCurrentDevice), TEST_SOURCE_ID);
        injectRemoteSourceStateRemoval(mStateMachines.get(mCurrentDevice1), TEST_SOURCE_ID + 1);

        // Resume set BIG_MONITORING
        mBassClientService.resumeReceiversSourceSynchronization();
        verifyBackgroundSearchStarted();
        verifyRegisterSyncCalled(mSourceDevice);

        // Check if monitoring is stopped when timer fires with broadcast suspended by remove
        checkAndDispatchTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);
        // Background searching and sync should be stopped
        assertThat(mBassClientService.isAnySearchInProgress()).isFalse();
        verifyUnregisterSyncCalled();
        checkNoResumeSynchronizationByBig();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_STOP_BIG_MONITORING_BASED_ON_BIS_SYNC,
        Flags.FLAG_LEAUDIO_BROADCAST_CHECK_SYNC_ADVANCEMENT_ON_REMOTE_RESUME
    })
    public void broadcastMonitoring_stopOnSuspendedByHost() {
        prepareSynchronizedPairAndStopSearching();

        BluetoothLeBroadcastMetadata mBroadcastMetadata1BisNotSelected =
                createBroadcastMetadataBisNotSelected(TEST_BROADCAST_ID);

        // deselect all BISes - we are stopping listening to broadcast
        mBassClientService.modifySource(
                mCurrentDevice, TEST_SOURCE_ID, mBroadcastMetadata1BisNotSelected);

        // Inject Receiver State without synchronized PA. With BIG MONITORING,
        // we'd expect this to cause resynchronization attempt.
        // Assure BIG MONITORING is off
        // Check corner cases, such as a repeated Receive State or losing PA sync before BIS unsync
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1BisNotSelected, /* isPaSynced */ true, /* isBisSynced */ true);
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1BisNotSelected, /* isPaSynced */ false, /* isBisSynced */ true);
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1BisNotSelected, /* isPaSynced */ false, /* isBisSynced */ false);
        verifyStopBroadcastMonitoringWithoutUnsync();
        checkNoResumeSynchronizationByHost();
        checkNoResumeSynchronizationByBig();
        checkNoTimeout(TEST_SOURCE_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);

        // Reselect all BISes - we are resuming stream
        // Update broadcast source - sync again to BISes
        mBassClientService.modifySource(mCurrentDevice, TEST_SOURCE_ID, mBroadcastMetadata1);

        // Simulate BIS and PA sync lost, BIG MONITORING is on
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ false);
        verifyBackgroundSearchStarted();
        verifyRegisterSyncCalled(mSourceDevice);
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_STOP_BIG_MONITORING_BASED_ON_BIS_SYNC)
    public void broadcastMonitoring_stopOnSuspendedByHost_handoverToUnicast() {
        prepareSynchronizedPairAndStopSearching();

        BluetoothLeBroadcastMetadata mBroadcastMetadata1BisNotSelected =
                createBroadcastMetadataBisNotSelected(TEST_BROADCAST_ID);

        // deselect all BISes - we are stopping listening to broadcast
        mBassClientService.modifySource(
                mCurrentDevice, TEST_SOURCE_ID, mBroadcastMetadata1BisNotSelected);

        // Inject Receiver State without synchronized PA. With BIG MONITORING,
        // we'd expect this to cause resynchronization attempt.
        // Assure BIG MONITORING is off
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1BisNotSelected, /* isPaSynced */ false, /* isBisSynced */ false);
        verifyStopBroadcastMonitoringWithoutUnsync();
        checkNoResumeSynchronizationByHost();
        checkNoResumeSynchronizationByBig();
        checkNoTimeout(TEST_SOURCE_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);

        // Handover to unicast
        mBassClientService.cacheSuspendingSources(TEST_BROADCAST_ID);
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ false);
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_OOR_MONITOR_TIMEOUT);

        // Handover to broadcast
        mBassClientService.resumeReceiversSourceSynchronization();
        checkNoResumeSynchronizationByHost();
        checkNoResumeSynchronizationByBig();
        checkNoTimeout(TEST_SOURCE_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);

        // Reselect all BISes - we are resuming stream
        // Update broadcast source - sync again to BISes
        mBassClientService.modifySource(mCurrentDevice, TEST_SOURCE_ID, mBroadcastMetadata1);

        // Inject Receiver State with synchronized BIS and PA
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ true, /* isBisSynced */ true);

        // Simulate BIS and PA sync lost, BIG MONITORING is on
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ false);
        verifyBackgroundSearchStarted();
        verifyRegisterSyncCalled(mSourceDevice);
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_STOP_BIG_MONITORING_BASED_ON_BIS_SYNC)
    public void broadcastMonitoring_stopOnSuspendedByHost_resumeFromRemote() {
        prepareSynchronizedPairAndStopSearching();

        // deselect all BISes - we are stopping listening to broadcast
        mBassClientService.modifySource(
                mCurrentDevice, TEST_SOURCE_ID, mBroadcastMetadataNoPreference);

        // Inject Receiver State without synchronized PA. With BIG MONITORING,
        // we'd expect this to cause resynchronization attempt.
        // Assure BIG MONITORING is off
        injectRemoteSourceStateChanged(
                mBroadcastMetadataNoPreference, /* isPaSynced */ false, /* isBisSynced */ false);
        verifyStopBroadcastMonitoringWithoutUnsync();
        checkNoResumeSynchronizationByHost();
        checkNoResumeSynchronizationByBig();
        checkNoTimeout(TEST_SOURCE_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);

        // Inject Receiver State with synchronized BIS and PA
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ true, /* isBisSynced */ true);

        // Simulate BIS and PA sync lost, BIG MONITORING is on
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ false);
        verifyBackgroundSearchStarted();
        verifyRegisterSyncCalled(mSourceDevice);
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_STOP_BIG_MONITORING_BASED_ON_BIS_SYNC)
    public void broadcastMonitoring_noPreference_resumeOnHandover() {
        prepareSynchronizedPairNoPreferenceAndStopSearching();

        BassClientStateMachine sm1 = mStateMachines.get(mCurrentDevice);
        BassClientStateMachine sm2 = mStateMachines.get(mCurrentDevice1);

        // Receiver state received after sync
        injectRemoteSourceStateChanged(
                sm1, mBroadcastMetadata1, /* isPaSynced */ true, /* isBisSynced */ true);
        injectRemoteSourceStateChanged(
                sm2, mBroadcastMetadata1, /* isPaSynced */ true, /* isBisSynced */ true);

        // Handover to unicast
        mBassClientService.cacheSuspendingSources(TEST_BROADCAST_ID);
        // Receiver state received after sync
        injectRemoteSourceStateChanged(
                sm1, mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ false);
        injectRemoteSourceStateChanged(
                sm2, mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ false);
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_OOR_MONITOR_TIMEOUT);

        // Resume
        mBassClientService.resumeReceiversSourceSynchronization();
        verifyBackgroundSearchStarted();
        verifyRegisterSyncCalled(mSourceDevice);
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_OOR_MONITOR_TIMEOUT);
    }

    private void verifyUpdateMetadataAndNoOthers() {
        expect.that(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());
            long count;
            if (sm.getDevice().equals(mCurrentDevice)) {
                count =
                        messageCaptor.getAllValues().stream()
                                .filter(
                                        m ->
                                                (m.what == BassClientStateMachine.UPDATE_METADATA)
                                                        && (m.arg1 == TEST_SOURCE_ID)
                                                        && (((BluetoothLeBroadcastMetadata) (m.obj))
                                                                        .getBroadcastId()
                                                                == mBroadcastMetadata1
                                                                        .getBroadcastId()))
                                .count();
                assertThat(count).isEqualTo(1);
                count =
                        messageCaptor.getAllValues().stream()
                                .filter(m -> m.what != BassClientStateMachine.UPDATE_METADATA)
                                .count();
                assertThat(count).isEqualTo(0);
            } else if (sm.getDevice().equals(mCurrentDevice1)) {
                count =
                        messageCaptor.getAllValues().stream()
                                .filter(
                                        m ->
                                                (m.what == BassClientStateMachine.UPDATE_METADATA)
                                                        && (m.arg1 == TEST_SOURCE_ID + 1)
                                                        && (((BluetoothLeBroadcastMetadata) (m.obj))
                                                                        .getBroadcastId()
                                                                == mBroadcastMetadata1
                                                                        .getBroadcastId()))
                                .count();
                assertThat(count).isEqualTo(1);
                count =
                        messageCaptor.getAllValues().stream()
                                .filter(m -> m.what != BassClientStateMachine.UPDATE_METADATA)
                                .count();
                assertThat(count).isEqualTo(0);
            } else {
                throw new AssertionError("Unexpected device");
            }
        }
    }

    @Test
    public void syncRequestForMetadata_localBroadcast() {
        prepareConnectedDeviceGroup();

        // Local Broadcast
        doReturn(mBroadcastMetadata1).when(mLeAudioService).getBroadcastMetadata(TEST_BROADCAST_ID);

        // Autonomous sync
        injectRemoteSourceStateSourceAdded(
                mBroadcastMetadata1,
                true /* isPaSynced */,
                true /* isBisSynced */,
                true /* autonomous */);

        // Immediate update as it is local
        verifyUpdateMetadataAndNoOthers();
    }

    @Test
    public void syncRequestForMetadata_scannerOn_synced_bigReport() {
        prepareConnectedDeviceGroup();

        // External broadcast synced, scanner on
        prepareSyncToSourceAndVerify();
        onPeriodicAdvertisingReport();
        onBigInfoAdvertisingReport();
        assertThat(mBassClientService.isSearchInProgress()).isTrue();

        // Autonomous sync
        injectRemoteSourceStateSourceAdded(
                mBroadcastMetadata1,
                true /* isPaSynced */,
                true /* isBisSynced */,
                true /* autonomous */);

        // Check enabling timeout
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_UPDATE_METADATA_TIMEOUT);

        // Update after BIG report, check disabling timeout
        onBigInfoAdvertisingReport();
        verifyUpdateMetadataAndNoOthers();
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_UPDATE_METADATA_TIMEOUT);

        // Scanner not disabled because it was started by user
        assertThat(mBassClientService.isSearchInProgress()).isTrue();

        // No more updates
        assertThat(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            clearInvocations(sm);
        }
        onPeriodicAdvertisingReport();
        onBigInfoAdvertisingReport();
        for (BassClientStateMachine sm : mStateMachines.values()) {
            verify(sm, never()).sendMessage(any());
        }
    }

    @Test
    public void syncRequestForMetadata_scannerOn_synced_paReport() {
        prepareConnectedDeviceGroup();

        // External broadcast synced, scanner on
        prepareSyncToSourceAndVerify();
        onPeriodicAdvertisingReport();
        onBigInfoAdvertisingReport();
        assertThat(mBassClientService.isSearchInProgress()).isTrue();

        // Autonomous sync
        injectRemoteSourceStateSourceAdded(
                mBroadcastMetadata1,
                true /* isPaSynced */,
                true /* isBisSynced */,
                true /* autonomous */);

        // Check enabling timeout
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_UPDATE_METADATA_TIMEOUT);

        // Update after PA report, check disabling timeout
        onPeriodicAdvertisingReport();
        verifyUpdateMetadataAndNoOthers();
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_UPDATE_METADATA_TIMEOUT);

        // Scanner not disabled because it was started by user
        assertThat(mBassClientService.isSearchInProgress()).isTrue();

        // No more updates
        assertThat(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            clearInvocations(sm);
        }
        onPeriodicAdvertisingReport();
        onBigInfoAdvertisingReport();
        for (BassClientStateMachine sm : mStateMachines.values()) {
            verify(sm, never()).sendMessage(any());
        }
    }

    @Test
    public void syncRequestForMetadata_scannerOn_notSynced_cached() {
        prepareConnectedDeviceGroup();

        // External broadcast synced
        prepareSyncToSourceAndVerify();
        onPeriodicAdvertisingReport();
        onBigInfoAdvertisingReport();

        // Unsynced but scanner still on
        onSyncLost();
        assertThat(mBassClientService.isSearchInProgress()).isTrue();

        // Autonomous sync
        injectRemoteSourceStateSourceAdded(
                mBroadcastMetadata1,
                true /* isPaSynced */,
                true /* isBisSynced */,
                true /* autonomous */);

        // Check enabling timeout
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_UPDATE_METADATA_TIMEOUT);

        // Check if syncRegistered, sync to it
        verifyRegisterSyncCalled(mSourceDevice);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);

        // Update after PA report, check disabling timeout
        onPeriodicAdvertisingReport();
        verifyUpdateMetadataAndNoOthers();
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_UPDATE_METADATA_TIMEOUT);

        // Scanner not disabled because it was started by user
        assertThat(mBassClientService.isSearchInProgress()).isTrue();

        // No more updates
        assertThat(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            clearInvocations(sm);
        }
        onPeriodicAdvertisingReport();
        onBigInfoAdvertisingReport();
        for (BassClientStateMachine sm : mStateMachines.values()) {
            verify(sm, never()).sendMessage(any());
        }
    }

    @Test
    public void syncRequestForMetadata_scannerOff_notSynced_cached() {
        prepareConnectedDeviceGroup();

        // External broadcast cached, scanner off
        prepareSyncToSourceAndVerify();
        onPeriodicAdvertisingReport();
        onBigInfoAdvertisingReport();
        mBassClientService.stopSearchingForSources();
        assertThat(mBassClientService.isAnySearchInProgress()).isFalse();

        // Autonomous sync
        injectRemoteSourceStateSourceAdded(
                mBroadcastMetadata1,
                true /* isPaSynced */,
                true /* isBisSynced */,
                true /* autonomous */);

        // Check enabling timeout
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_UPDATE_METADATA_TIMEOUT);
        verifyBackgroundSearchStarted();

        if (!Flags.leaudioBroadcastAlwaysUseBackgroundScanner()) {
            // Check if scanner started
            assertThat(mBassClientService.isAnySearchInProgress()).isTrue();
        }

        // Check if syncRegistered, sync to it
        verifyRegisterSyncCalled(mSourceDevice);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);

        // Update after PA report, check disabling timeout
        onPeriodicAdvertisingReport();
        verifyUpdateMetadataAndNoOthers();
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_UPDATE_METADATA_TIMEOUT);

        // Check if scanner stopped
        assertThat(mBassClientService.isAnySearchInProgress()).isFalse();

        // Check if unsyced
        verifyUnregisterSyncCalled();
        expect.that(mBassClientService.getActiveSyncedSources()).isEmpty();
        expect.that(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE)).isNull();
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
    }

    @Test
    public void syncRequestForMetadata_scannerOff_notSynced_notCached() {
        prepareConnectedDeviceGroup();

        // Check is scanner stopped
        assertThat(mBassClientService.isAnySearchInProgress()).isFalse();

        // Autonomous sync
        injectRemoteSourceStateSourceAdded(
                mBroadcastMetadata1,
                true /* isPaSynced */,
                true /* isBisSynced */,
                true /* autonomous */);

        // Check enabling timeout
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_UPDATE_METADATA_TIMEOUT);

        // Check if scanner started
        assertThat(mBassClientService.isAnySearchInProgress()).isTrue();

        // Not cached so no syncRegistered
        verifyRegisterSyncNeverCalled();

        // Broadcast sync
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        verifyRegisterSyncCalled(mSourceDevice);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);

        // Update after PA report, check disabling timeout
        onPeriodicAdvertisingReport();
        verifyUpdateMetadataAndNoOthers();
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_UPDATE_METADATA_TIMEOUT);

        // Check if scanner stopped
        assertThat(mBassClientService.isAnySearchInProgress()).isFalse();

        // Check if unsyced
        verifyUnregisterSyncCalled();
        expect.that(mBassClientService.getActiveSyncedSources()).isEmpty();
        expect.that(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE)).isNull();
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
    }

    @Test
    public void syncRequestForMetadata_scannerOff_notSynced_notCached_retries() {
        prepareConnectedDeviceGroup();

        // Check is scanner stopped
        assertThat(mBassClientService.isAnySearchInProgress()).isFalse();

        // Autonomous sync
        injectRemoteSourceStateSourceAdded(
                mBroadcastMetadata1,
                true /* isPaSynced */,
                true /* isBisSynced */,
                true /* autonomous */);

        // Check enabling timeout
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_UPDATE_METADATA_TIMEOUT);

        // Check if scanner started
        assertThat(mBassClientService.isAnySearchInProgress()).isTrue();

        // Not cached so no syncRegistered
        verifyRegisterSyncNeverCalled();

        // Broadcast sync failure
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        verifyRegisterSyncCalled(mSourceDevice);
        onSyncEstablishedFailed(mSourceDevice, TEST_SYNC_HANDLE);

        // Broadcast sync
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        verifyRegisterSyncCalled(mSourceDevice);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);

        // Update after PA report, check disabling timeout
        onPeriodicAdvertisingReport();
        verifyUpdateMetadataAndNoOthers();
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_UPDATE_METADATA_TIMEOUT);

        // Check if scanner stopped
        assertThat(mBassClientService.isAnySearchInProgress()).isFalse();

        // Check if unsyced
        verifyUnregisterSyncCalled();
        expect.that(mBassClientService.getActiveSyncedSources()).isEmpty();
        expect.that(mBassClientService.getDeviceForSyncHandle(TEST_SYNC_HANDLE)).isNull();
        expect.that(mBassClientService.getBroadcastIdForSyncHandle(TEST_SYNC_HANDLE))
                .isEqualTo(LeAudioConstants.INVALID_BROADCAST_ID);
    }

    @Test
    public void syncRequestForMetadata_scannerOff_notSynced_notCached_timeout() {
        prepareConnectedDeviceGroup();

        // Check is scanner stopped
        assertThat(mBassClientService.isAnySearchInProgress()).isFalse();

        // Autonomous sync
        injectRemoteSourceStateSourceAdded(
                mBroadcastMetadata1,
                true /* isPaSynced */,
                true /* isBisSynced */,
                true /* autonomous */);

        // Check enabling timeout
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_UPDATE_METADATA_TIMEOUT);

        // Check if scanner started
        assertThat(mBassClientService.isAnySearchInProgress()).isTrue();

        // Not cached so no syncRegistered
        verifyRegisterSyncNeverCalled();

        // Broadcast sync failure
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        verifyRegisterSyncCalled(mSourceDevice);
        onSyncEstablishedFailed(mSourceDevice, TEST_SYNC_HANDLE);

        // Timeout
        checkAndDispatchTimeout(
                TEST_BROADCAST_ID, BassClientService.MESSAGE_UPDATE_METADATA_TIMEOUT);
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_UPDATE_METADATA_TIMEOUT);

        // Check if scanner stopped
        assertThat(mBassClientService.isAnySearchInProgress()).isFalse();

        // External broadcast synced by user
        prepareSyncToSourceAndVerify();

        // Check if no metadataUpdate on PA/BIG report
        assertThat(mStateMachines.size()).isEqualTo(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            clearInvocations(sm);
        }
        onPeriodicAdvertisingReport();
        onBigInfoAdvertisingReport();
        for (BassClientStateMachine sm : mStateMachines.values()) {
            verify(sm, never()).sendMessage(any());
        }
    }

    @Test
    public void syncRequestForMetadata_scannerOff_notSynced_notCached_retries_userScan() {
        prepareConnectedDeviceGroup();

        // Check is scanner stopped
        assertThat(mBassClientService.isAnySearchInProgress()).isFalse();

        // Autonomous sync
        injectRemoteSourceStateSourceAdded(
                mBroadcastMetadata1,
                true /* isPaSynced */,
                true /* isBisSynced */,
                true /* autonomous */);

        // Check enabling timeout
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_UPDATE_METADATA_TIMEOUT);

        // Check if scanner started
        assertThat(mBassClientService.isAnySearchInProgress()).isTrue();

        // Not cached so no syncRegistered
        verifyRegisterSyncNeverCalled();

        // Broadcast sync failure
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        verifyRegisterSyncCalled(mSourceDevice);
        onSyncEstablishedFailed(mSourceDevice, TEST_SYNC_HANDLE);

        // User start scanner
        List<ScanFilter> scanFilters = new ArrayList<>();
        mBassClientService.startSearchingForSources(scanFilters);

        // Broadcast sync
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        verifyRegisterSyncCalled(mSourceDevice);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);

        // Update after PA report, check disabling timeout
        onPeriodicAdvertisingReport();
        verifyUpdateMetadataAndNoOthers();
        checkNoTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_UPDATE_METADATA_TIMEOUT);

        // Scanner not disabled because it was started by user
        assertThat(mBassClientService.isAnySearchInProgress()).isTrue();
    }

    @Test
    public void syncRequestForMetadata_scannerOff_notSynced_notCached_anotherOOR() {
        prepareConnectedDeviceGroup();

        // Add source to first device
        prepareSyncToSourceAndVerify();
        mBassClientService.addSource(mCurrentDevice, mBroadcastMetadata1, /* isGroupOp */ false);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        injectRemoteSourceStateSourceAdded(
                mBroadcastMetadata1, /* isPaSynced */ true, /* isBisSynced */ true);
        mBassClientService.stopSearchingForSources();

        // Autonomous sync on second device and different broadcast id causes background scan
        injectRemoteSourceStateSourceAdded(
                mStateMachines.get(mCurrentDevice1),
                mBroadcastMetadata2,
                TEST_SOURCE_ID + 1,
                BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED,
                BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                null,
                1L,
                true);
        verifyBackgroundSearchStarted();

        // Trigger OOR monitor
        injectRemoteSourceStateSourceAdded(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ false);
        verifyRegisterSyncCalled(mSourceDevice);
        onSyncEstablishedFailed(mSourceDevice, TEST_SYNC_HANDLE);
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_OOR_MONITOR_TIMEOUT);

        // Scan result from OOR broadcast should cause sync registering
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        verifyRegisterSyncCalled(mSourceDevice);
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_FALLBACK_GROUP_SELECTION)
    public void testUpdateDefaultBroadcastToUnicastFallbackGroupWhenFlagEnabled()
            throws RemoteException {
        // Preparation: A connected device and a mock LeAudioService.
        prepareConnectedDeviceGroup();
        doReturn(new ArrayList<>()).when(mLeAudioService).getConnectedDevices();
        doReturn(mBroadcastMetadata1).when(mLeAudioService).getBroadcastMetadata(TEST_BROADCAST_ID);

        // Create an ArgumentCaptor to capture the Set arguments.
        ArgumentCaptor<Set> setCaptor = ArgumentCaptor.forClass(Set.class);

        // 1. Simulate adding a broadcast receiver. This triggers the update logic twice.
        // The first call is from the setup, and the second from this action.
        mBassClientService.addSource(mCurrentDevice, mBroadcastMetadata1, /* isGroupOp */ true);
        injectRemoteSourceStateSourceAdded(mBroadcastMetadata1, true, true);

        // Verification: We expect a total of two invocations for this step.
        verify(mLeAudioService, times(2))
                .selectDefaultBroadcastToUnicastFallbackGroup(setCaptor.capture());

        // Check that both captured sets are correct.
        List<Set> capturedSets = setCaptor.getAllValues();
        Set<BluetoothDevice> expectedSet = new HashSet<>(Arrays.asList(mCurrentDevice));
        assertThat(capturedSets.get(0)).containsExactlyElementsIn(expectedSet);
        expectedSet = new HashSet<>(Arrays.asList(mCurrentDevice, mCurrentDevice1));
        assertThat(capturedSets.get(1)).containsExactlyElementsIn(expectedSet);

        // 2. Simulate a broadcast stopping, which is the fourth invocation.
        mBassClientService.notifyBroadcastStateChanged(
                LeAudioStackEvent.BROADCAST_STATE_STOPPED, TEST_BROADCAST_ID);

        // Verification: The function should have been called a total of 4 times.
        verify(mLeAudioService, times(3))
                .selectDefaultBroadcastToUnicastFallbackGroup(any(Set.class));
    }

    private BluetoothLeBroadcastReceiveState createReceiveState(
            boolean isSourceAdded, int paState, boolean isBisSynced) {
        if (!isSourceAdded) {
            return new BluetoothLeBroadcastReceiveState(
                    TEST_SOURCE_ID,
                    ADDRESS_TYPE_PUBLIC,
                    getRealDevice("00:00:00:00:00:00", ADDRESS_TYPE_PUBLIC),
                    0,
                    0,
                    BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                    BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                    null,
                    0,
                    Arrays.asList(new Long[0]),
                    Arrays.asList(new BluetoothLeAudioContentMetadata[0]));
        } else {
            BluetoothLeBroadcastMetadata metadata = createBroadcastMetadata(TEST_BROADCAST_ID);
            return new BluetoothLeBroadcastReceiveState(
                    TEST_SOURCE_ID,
                    metadata.getSourceAddressType(),
                    metadata.getSourceDevice(),
                    metadata.getSourceAdvertisingSid(),
                    metadata.getBroadcastId(),
                    paState,
                    BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                    null,
                    metadata.getSubgroups().size(),
                    // Bis sync states
                    metadata.getSubgroups().stream()
                            .map(e -> (isBisSynced ? 1L : 0L))
                            .collect(Collectors.toList()),
                    metadata.getSubgroups().stream()
                            .map(e -> e.getContentMetadata())
                            .collect(Collectors.toList()));
        }
    }

    private static BluetoothLeBroadcastReceiveState createReceiveState(
            BluetoothLeBroadcastMetadata metadata, int sourceId) {
        return new BluetoothLeBroadcastReceiveState(
                sourceId,
                metadata.getSourceAddressType(),
                metadata.getSourceDevice(),
                metadata.getSourceAdvertisingSid(),
                metadata.getBroadcastId(),
                BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                null,
                0,
                Arrays.asList(new Long[0]),
                Arrays.asList(new BluetoothLeAudioContentMetadata[0]));
    }

    @Test
    public void broadcastSyncAdvancing() {
        assertThat(
                        mBassClientService.isBroadcastSyncAdvancing(
                                mCurrentDevice,
                                createReceiveState(
                                        false /* isSourceAdded */,
                                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                                        false /* isBisSynced */)))
                .isFalse();

        assertThat(
                        mBassClientService.isBroadcastSyncAdvancing(
                                mCurrentDevice,
                                createReceiveState(
                                        true /* isSourceAdded */,
                                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                                        false /* isBisSynced */)))
                .isTrue();

        assertThat(
                        mBassClientService.isBroadcastSyncAdvancing(
                                mCurrentDevice,
                                createReceiveState(
                                        true /* isSourceAdded */,
                                        BluetoothLeBroadcastReceiveState
                                                .PA_SYNC_STATE_SYNCINFO_REQUEST,
                                        false /* isBisSynced */)))
                .isFalse();

        assertThat(
                        mBassClientService.isBroadcastSyncAdvancing(
                                mCurrentDevice,
                                createReceiveState(
                                        true /* isSourceAdded */,
                                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED,
                                        false /* isBisSynced */)))
                .isTrue();

        assertThat(
                        mBassClientService.isBroadcastSyncAdvancing(
                                mCurrentDevice,
                                createReceiveState(
                                        true /* isSourceAdded */,
                                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED,
                                        false /* isBisSynced */)))
                .isFalse();

        assertThat(
                        mBassClientService.isBroadcastSyncAdvancing(
                                mCurrentDevice,
                                createReceiveState(
                                        true /* isSourceAdded */,
                                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED,
                                        true /* isBisSynced */)))
                .isTrue();

        assertThat(
                        mBassClientService.isBroadcastSyncAdvancing(
                                mCurrentDevice,
                                createReceiveState(
                                        true /* isSourceAdded */,
                                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                                        true /* isBisSynced */)))
                .isFalse();

        assertThat(
                        mBassClientService.isBroadcastSyncAdvancing(
                                mCurrentDevice,
                                createReceiveState(
                                        true /* isSourceAdded */,
                                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED,
                                        true /* isBisSynced */)))
                .isFalse();

        assertThat(
                        mBassClientService.isBroadcastSyncAdvancing(
                                mCurrentDevice,
                                createReceiveState(
                                        true /* isSourceAdded */,
                                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED,
                                        false /* isBisSynced */)))
                .isFalse();

        assertThat(
                        mBassClientService.isBroadcastSyncAdvancing(
                                mCurrentDevice,
                                createReceiveState(
                                        true /* isSourceAdded */,
                                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED,
                                        true /* isBisSynced */)))
                .isTrue();

        assertThat(
                        mBassClientService.isBroadcastSyncAdvancing(
                                mCurrentDevice,
                                createReceiveState(
                                        true /* isSourceAdded */,
                                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED,
                                        false /* isBisSynced */)))
                .isFalse();

        assertThat(
                        mBassClientService.isBroadcastSyncAdvancing(
                                mCurrentDevice,
                                createReceiveState(
                                        true /* isSourceAdded */,
                                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                                        false /* isBisSynced */)))
                .isFalse();

        assertThat(
                        mBassClientService.isBroadcastSyncAdvancing(
                                mCurrentDevice,
                                createReceiveState(
                                        false /* isSourceAdded */,
                                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                                        false /* isBisSynced */)))
                .isFalse();
    }

    @Test
    public void broadcastSyncAdvancing_independentDevicesAndDisconnection() {
        prepareConnectedDeviceGroup();

        assertThat(
                        mBassClientService.isBroadcastSyncAdvancing(
                                mCurrentDevice,
                                createReceiveState(
                                        true /* isSourceAdded */,
                                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                                        false /* isBisSynced */)))
                .isTrue();

        assertThat(
                        mBassClientService.isBroadcastSyncAdvancing(
                                mCurrentDevice,
                                createReceiveState(
                                        true /* isSourceAdded */,
                                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                                        false /* isBisSynced */)))
                .isFalse();

        assertThat(
                        mBassClientService.isBroadcastSyncAdvancing(
                                mCurrentDevice1,
                                createReceiveState(
                                        true /* isSourceAdded */,
                                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                                        false /* isBisSynced */)))
                .isTrue();

        injectDeviceDisconnection(mCurrentDevice);

        assertThat(
                        mBassClientService.isBroadcastSyncAdvancing(
                                mCurrentDevice,
                                createReceiveState(
                                        true /* isSourceAdded */,
                                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                                        false /* isBisSynced */)))
                .isTrue();

        assertThat(
                        mBassClientService.isBroadcastSyncAdvancing(
                                mCurrentDevice1,
                                createReceiveState(
                                        true /* isSourceAdded */,
                                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                                        false /* isBisSynced */)))
                .isFalse();
    }

    @Test
    public void autonomousInactive_afterAddSourceCommand_reactivation() {
        final int groupId = 1;
        final int skCtx =
                BluetoothLeAudio.CONTEXTS_ALL
                        & ~(BluetoothLeAudio.CONTEXT_TYPE_UNSPECIFIED
                                | BluetoothLeAudio.CONTEXT_TYPE_SOUND_EFFECTS);
        final int srCtx = BluetoothLeAudio.CONTEXTS_ALL;
        doReturn(groupId).when(mLeAudioService).getGroupId(any());
        doReturn(groupId).when(mLeAudioService).getActiveGroupId();
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();
        InOrder inOrderLeAudio = inOrder(mLeAudioService);
        inOrderLeAudio.verify(mLeAudioService, never()).setActiveDevice(any());

        mBassClientService.addSource(mCurrentDevice, mBroadcastMetadata1, /* isGroupOp */ true);
        mBassClientService.notifyLeAudioGroupAutonomousInactivated(mCurrentDevice);
        inOrderLeAudio.verify(mLeAudioService).setActiveDevice(eq(mCurrentDevice));
        inOrderLeAudio.verify(mLeAudioService).setGroupAllowedContextMask(groupId, skCtx, srCtx);
    }

    @Test
    public void autonomousInactive_afterModifySourceCommand_reactivation() {
        final int groupId = 1;
        final int skCtx =
                BluetoothLeAudio.CONTEXTS_ALL
                        & ~(BluetoothLeAudio.CONTEXT_TYPE_UNSPECIFIED
                                | BluetoothLeAudio.CONTEXT_TYPE_SOUND_EFFECTS);
        final int srCtx = BluetoothLeAudio.CONTEXTS_ALL;
        doReturn(groupId).when(mLeAudioService).getGroupId(any());
        doReturn(groupId).when(mLeAudioService).getActiveGroupId();
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();
        InOrder inOrderLeAudio = inOrder(mLeAudioService);
        inOrderLeAudio.verify(mLeAudioService, never()).setActiveDevice(any());

        mBassClientService.modifySource(mCurrentDevice, TEST_SOURCE_ID, mBroadcastMetadata1);
        mBassClientService.notifyLeAudioGroupAutonomousInactivated(mCurrentDevice);
        inOrderLeAudio.verify(mLeAudioService).setActiveDevice(eq(mCurrentDevice));
        inOrderLeAudio.verify(mLeAudioService).setGroupAllowedContextMask(groupId, skCtx, srCtx);
    }

    @Test
    public void autonomousInactive_afterSwitchSourceCommand_reactivation() {
        final int groupId = 1;
        final int skCtx =
                BluetoothLeAudio.CONTEXTS_ALL
                        & ~(BluetoothLeAudio.CONTEXT_TYPE_UNSPECIFIED
                                | BluetoothLeAudio.CONTEXT_TYPE_SOUND_EFFECTS);
        final int srCtx = BluetoothLeAudio.CONTEXTS_ALL;
        doReturn(groupId).when(mLeAudioService).getGroupId(any());
        doReturn(groupId).when(mLeAudioService).getActiveGroupId();
        prepareSynchronizedPair();
        mLooper.moveTimeForward(BassClientService.sAutoInactiveMonitorTimeout.toMillis());
        mLooper.dispatchAll();
        InOrder inOrderLeAudio = inOrder(mLeAudioService);
        inOrderLeAudio.verify(mLeAudioService, never()).setActiveDevice(any());

        // Add another new broadcast source, switch source
        onScanResult(mSourceDevice2, TEST_BROADCAST_ID_2);
        onSyncEstablished(mSourceDevice2, TEST_SYNC_HANDLE_2);
        mBassClientService.addSource(mCurrentDevice, mBroadcastMetadata2, /* isGroupOp */ true);
        mBassClientService.notifyLeAudioGroupAutonomousInactivated(mCurrentDevice);
        inOrderLeAudio.verify(mLeAudioService).setActiveDevice(eq(mCurrentDevice));
        inOrderLeAudio.verify(mLeAudioService).setGroupAllowedContextMask(groupId, skCtx, srCtx);
    }

    @Test
    public void autonomousInactive_beforeBroadcastSync_reactivation() {
        final int groupId = 1;
        final int skCtx =
                BluetoothLeAudio.CONTEXTS_ALL
                        & ~(BluetoothLeAudio.CONTEXT_TYPE_UNSPECIFIED
                                | BluetoothLeAudio.CONTEXT_TYPE_SOUND_EFFECTS);
        final int srCtx = BluetoothLeAudio.CONTEXTS_ALL;
        doReturn(groupId).when(mLeAudioService).getGroupId(any());
        doReturn(BluetoothLeAudio.GROUP_ID_INVALID).when(mLeAudioService).getActiveGroupId();
        prepareSynchronizedPair();
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ true, /* isBisSynced */ false);
        mLooper.moveTimeForward(BassClientService.sAutoInactiveMonitorTimeout.toMillis());
        mLooper.dispatchAll();
        InOrder inOrderLeAudio = inOrder(mLeAudioService);
        inOrderLeAudio.verify(mLeAudioService, never()).setActiveDevice(any());

        mBassClientService.notifyLeAudioGroupAutonomousInactivated(mCurrentDevice);
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ true, /* isBisSynced */ true);
        inOrderLeAudio.verify(mLeAudioService).setActiveDevice(eq(mCurrentDevice));
        inOrderLeAudio.verify(mLeAudioService).setGroupAllowedContextMask(groupId, skCtx, srCtx);
    }

    @Test
    public void autonomousInactive_afterBroadcastSync_reactivation() {
        final int groupId = 1;
        final int skCtx =
                BluetoothLeAudio.CONTEXTS_ALL
                        & ~(BluetoothLeAudio.CONTEXT_TYPE_UNSPECIFIED
                                | BluetoothLeAudio.CONTEXT_TYPE_SOUND_EFFECTS);
        final int srCtx = BluetoothLeAudio.CONTEXTS_ALL;
        doReturn(groupId).when(mLeAudioService).getGroupId(any());
        doReturn(groupId).when(mLeAudioService).getActiveGroupId();
        prepareSynchronizedPair();
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ true, /* isBisSynced */ false);
        mLooper.moveTimeForward(BassClientService.sAutoInactiveMonitorTimeout.toMillis());
        mLooper.dispatchAll();
        InOrder inOrderLeAudio = inOrder(mLeAudioService);
        inOrderLeAudio.verify(mLeAudioService, never()).setActiveDevice(any());

        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ true, /* isBisSynced */ true);
        mBassClientService.notifyLeAudioGroupAutonomousInactivated(mCurrentDevice);
        inOrderLeAudio.verify(mLeAudioService).setActiveDevice(eq(mCurrentDevice));
        inOrderLeAudio.verify(mLeAudioService).setGroupAllowedContextMask(groupId, skCtx, srCtx);
    }

    @Test
    public void autonomousInactive_tooLateBroadcastSync() {
        final int groupId = 1;
        doReturn(groupId).when(mLeAudioService).getGroupId(any());
        doReturn(groupId).when(mLeAudioService).getActiveGroupId();
        prepareSynchronizedPair();
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ true, /* isBisSynced */ false);
        mLooper.moveTimeForward(BassClientService.sAutoInactiveMonitorTimeout.toMillis());
        mLooper.dispatchAll();
        InOrder inOrderLeAudio = inOrder(mLeAudioService);
        inOrderLeAudio.verify(mLeAudioService, never()).setActiveDevice(any());

        mBassClientService.notifyLeAudioGroupAutonomousInactivated(mCurrentDevice);
        mLooper.moveTimeForward(BassClientService.sAutoInactiveMonitorTimeout.toMillis());
        mLooper.dispatchAll();

        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ true, /* isBisSynced */ true);
        inOrderLeAudio.verify(mLeAudioService, never()).setActiveDevice(any());
        inOrderLeAudio
                .verify(mLeAudioService, never())
                .setGroupAllowedContextMask(anyInt(), anyInt(), anyInt());
    }

    @Test
    public void autonomousInactive_tooEarlyBroadcastSync() {
        final int groupId = 1;
        doReturn(groupId).when(mLeAudioService).getGroupId(any());
        doReturn(groupId).when(mLeAudioService).getActiveGroupId();
        prepareSynchronizedPair();
        mLooper.moveTimeForward(BassClientService.sAutoInactiveMonitorTimeout.toMillis());
        mLooper.dispatchAll();

        mBassClientService.notifyLeAudioGroupAutonomousInactivated(mCurrentDevice);
        verify(mLeAudioService, never()).setActiveDevice(any());
        verify(mLeAudioService, never()).setGroupAllowedContextMask(anyInt(), anyInt(), anyInt());
    }

    /**
     * Test that when adding a source for a group while not scanning, the Periodic Advertising (PA)
     * sync is maintained as long as the remote devices have not fully synchronized to the PA. Once
     * the remote devices reports being PA synced, the BASS client will unregister from the PA sync.
     */
    @Test
    public void addSourceForGroup_noScanning_keepSync() {
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();
        mBassClientService.stopSearchingForSources();

        mBassClientService.addSource(mCurrentDevice, mBroadcastMetadata1, /* isGroupOp */ true);
        verifyBackgroundSearchStarted();
        verifyRegisterSyncCalled(mSourceDevice);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        assertThat(mBassClientService.isAnySearchInProgress()).isFalse();

        injectRemoteSourceStateSourceAdded(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ false);
        verifyUnregisterSyncNeverCalled();

        injectRemoteSourceStateSourceAdded(
                mBroadcastMetadata1, /* isPaSynced */ true, /* isBisSynced */ false);
        verifyUnregisterSyncCalled();
    }

    /**
     * Test that when two sinks lose BIS and PA sync one after another, both are correctly added to
     * monitoring and can be resumed. This covers a scenario where the second sink might have been
     * incorrectly considered synced after losing BIS sync, preventing proper monitoring.
     */
    @Test
    public void bigMonitoring_addSinksSeparately() {
        prepareSynchronizedPair();

        BassClientStateMachine sm1 = mStateMachines.get(mCurrentDevice);
        BassClientStateMachine sm2 = mStateMachines.get(mCurrentDevice1);

        injectRemoteSourceStateChanged(sm1, mBroadcastMetadata1, true, false);
        injectRemoteSourceStateChanged(sm1, mBroadcastMetadata1, false, false);

        injectRemoteSourceStateChanged(sm2, mBroadcastMetadata1, true, false);
        injectRemoteSourceStateChanged(sm2, mBroadcastMetadata1, false, false);

        mBassClientService.resumeReceiversSourceSynchronization();
        verifyAllGroupMembersGettingUpdateOrAddSource(mBroadcastMetadata1);
    }

    /**
     * Test that when two sinks lose sync sequentially, and a resume operation is triggered for the
     * first one, a subsequent sync loss on the second sink during the resume process correctly
     * re-triggers the resume operation. This ensures that the newly paused sink is also covered by
     * the resume logic.
     */
    @Test
    public void bigMonitoring_addSinksSeparately_resumingInTheMiddle() {
        prepareSynchronizedPair();

        BassClientStateMachine sm1 = mStateMachines.get(mCurrentDevice);
        BassClientStateMachine sm2 = mStateMachines.get(mCurrentDevice1);

        injectRemoteSourceStateChanged(sm1, mBroadcastMetadata1, true, false);
        injectRemoteSourceStateChanged(sm1, mBroadcastMetadata1, false, false);

        mBassClientService.resumeReceiversSourceSynchronization();
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        Optional<Message> msg;
        verify(sm1).sendMessage(messageCaptor.capture());
        msg =
                messageCaptor.getAllValues().stream()
                        .filter(m -> m.what == BassClientStateMachine.UPDATE_BCAST_SOURCE)
                        .findFirst();
        expect.that(msg.isPresent()).isTrue();
        expect.that(msg.orElse(null)).isNotNull();
        clearInvocations(sm1);
        verify(sm2, never()).sendMessage(any());

        injectRemoteSourceStateChanged(sm2, mBroadcastMetadata1, true, false);
        injectRemoteSourceStateChanged(sm2, mBroadcastMetadata1, false, false);
        verifyAllGroupMembersGettingUpdateOrAddSource(mBroadcastMetadata1);
    }

    @Test
    public void syncRequestForPast_retriesDuringScanning() {
        prepareSynchronizedPair();
        onSyncLost();

        mBassClientService.syncRequestForPast(mCurrentDevice, TEST_BROADCAST_ID, TEST_SOURCE_ID);
        verifyRegisterSyncCalled(mSourceDevice);

        // Not try again on sync failed because there is active scanning
        onSyncEstablishedFailed(mSourceDevice, TEST_SYNC_HANDLE);
        verifyRegisterSyncNeverCalled();

        // Try again on scan result even if already cached
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        verifyRegisterSyncCalled(mSourceDevice);
    }

    @Test
    public void syncRequestForPast_retriesWithoutScanning() {
        prepareSynchronizedPairAndStopSearching();

        mBassClientService.syncRequestForPast(mCurrentDevice, TEST_BROADCAST_ID, TEST_SOURCE_ID);
        verifyBackgroundSearchStarted();
        verifyRegisterSyncCalled(mSourceDevice);

        // Try again on scan result after sync failed
        onSyncEstablishedFailed(mSourceDevice, TEST_SYNC_HANDLE);
        if (Flags.leaudioBroadcastAlwaysUseBackgroundScanner()) {
            onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        }
        verifyRegisterSyncCalled(mSourceDevice);
    }

    @Test
    public void syncRequestForPast_clearOnTimeout() {
        prepareSynchronizedPairAndStopSearching();

        mBassClientService.syncRequestForPast(mCurrentDevice, TEST_BROADCAST_ID, TEST_SOURCE_ID);
        verifyBackgroundSearchStarted();
        verifyRegisterSyncCalled(mSourceDevice);

        // Stop SYNC monitoring after timeout
        mLooper.moveTimeForward(BassClientService.sPastResponseTimeout.toMillis());
        mLooper.dispatchAll();
        if (Flags.leaudioBroadcastAlwaysUseBackgroundScanner()) {
            // Stop background searching and unregiser pending sync
            assertThat(mBassClientService.isAnySearchInProgress()).isFalse();
            verifyUnregisterSyncCalled();
        } else {
            onSyncEstablishedFailed(mSourceDevice, TEST_SYNC_HANDLE);
            verifyRegisterSyncNeverCalled();
        }
    }

    @Test
    public void syncRequestForPast_clearOnDisconnect() {
        prepareSynchronizedPairAndStopSearching();

        mBassClientService.syncRequestForPast(mCurrentDevice, TEST_BROADCAST_ID, TEST_SOURCE_ID);
        verifyBackgroundSearchStarted();
        verifyRegisterSyncCalled(mSourceDevice);

        // Stop PAST monitoring after disconnection
        injectDeviceDisconnection(mCurrentDevice);
        if (Flags.leaudioBroadcastAlwaysUseBackgroundScanner()) {
            // Stop background searching and unregiser pending sync
            assertThat(mBassClientService.isAnySearchInProgress()).isFalse();
            verifyUnregisterSyncCalled();
        } else {
            onSyncEstablishedFailed(mSourceDevice, TEST_SYNC_HANDLE);
            verifyRegisterSyncNeverCalled();
        }
    }

    @Test
    public void syncRequestForPast_clearOnReceiveStateChanged() {
        prepareSynchronizedPairAndStopSearching();

        mBassClientService.syncRequestForPast(mCurrentDevice, TEST_BROADCAST_ID, TEST_SOURCE_ID);
        verifyBackgroundSearchStarted();
        verifyRegisterSyncCalled(mSourceDevice);

        // Stop PAST monitoring after RS change
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ true, /* isBisSynced */ false);
        if (Flags.leaudioBroadcastAlwaysUseBackgroundScanner()) {
            // Stop background searching and unregiser pending sync
            assertThat(mBassClientService.isAnySearchInProgress()).isFalse();
            verifyUnregisterSyncCalled();
        } else {
            onSyncEstablishedFailed(mSourceDevice, TEST_SYNC_HANDLE);
            verifyRegisterSyncNeverCalled();
        }
    }

    @Test
    public void syncRequestForPast_multipleSourceId() {
        prepareConnectedDeviceGroup();

        // Set maximum source capacity to 2
        for (BassClientStateMachine sm : mStateMachines.values()) {
            doReturn(2).when(sm).getMaximumSourceCapacity();
        }

        prepareSyncToSourceAndVerify();
        addSourceAndVerify(mBroadcastMetadata1);
        injectRemoteSourceStateSourceAdded(
                mBroadcastMetadata1, /* isPaSynced */ true, /* isBisSynced */ true);

        // Add another new broadcast source
        onScanResult(mSourceDevice2, TEST_BROADCAST_ID_2);
        onSyncEstablished(mSourceDevice2, TEST_SYNC_HANDLE_2);
        addSourceAndVerify(mBroadcastMetadata2);
        assertThat(mStateMachines).hasSize(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            if (sm.getDevice().equals(mCurrentDevice)) {
                injectRemoteSourceStateSourceAdded(
                        sm,
                        mBroadcastMetadata2,
                        TEST_SOURCE_ID + 2,
                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                        mBroadcastMetadata2.isEncrypted()
                                ? BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING
                                : BluetoothLeBroadcastReceiveState
                                        .BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                        null,
                        0L);
            } else if (sm.getDevice().equals(mCurrentDevice1)) {
                injectRemoteSourceStateSourceAdded(
                        sm,
                        mBroadcastMetadata2,
                        TEST_SOURCE_ID + 3,
                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                        mBroadcastMetadata2.isEncrypted()
                                ? BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING
                                : BluetoothLeBroadcastReceiveState
                                        .BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                        null,
                        0L);
            } else {
                throw new AssertionError("Unexpected device");
            }
        }

        mBassClientService.stopSearchingForSources();

        // PAST request for first broadcaster
        mBassClientService.syncRequestForPast(mCurrentDevice, TEST_BROADCAST_ID, TEST_SOURCE_ID);
        mBassClientService.syncRequestForPast(
                mCurrentDevice1, TEST_BROADCAST_ID, TEST_SOURCE_ID + 1);
        verifyBackgroundSearchStarted();
        verifyRegisterSyncCalled(mSourceDevice);

        // PAST request for another broadcaster
        mBassClientService.syncRequestForPast(
                mCurrentDevice, TEST_BROADCAST_ID_2, TEST_SOURCE_ID + 2);
        mBassClientService.syncRequestForPast(
                mCurrentDevice1, TEST_BROADCAST_ID_2, TEST_SOURCE_ID + 3);
        // It will be registered on first sync established (pass or fail)

        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        verifyInitiatePaSyncTransferAndNoOthers(TEST_SYNC_HANDLE, TEST_SOURCE_ID);
        if (Flags.leaudioBroadcastAlwaysUseBackgroundScanner()) {
            assertThat(mBassClientService.isAnySearchInProgress()).isTrue();
        }
        verifyRegisterSyncCalled(mSourceDevice2);
        onSyncEstablished(mSourceDevice2, TEST_SYNC_HANDLE_2);
        verifyInitiatePaSyncTransferAndNoOthers(TEST_SYNC_HANDLE_2, TEST_SOURCE_ID + 2);
        assertThat(mBassClientService.isAnySearchInProgress()).isFalse();
    }

    @Test
    public void syncRequestForPast_multipleSourceId_clearOnTimeout() {
        prepareConnectedDeviceGroup();

        // Set maximum source capacity to 2
        for (BassClientStateMachine sm : mStateMachines.values()) {
            doReturn(2).when(sm).getMaximumSourceCapacity();
        }

        prepareSyncToSourceAndVerify();
        addSourceAndVerify(mBroadcastMetadata1);
        injectRemoteSourceStateSourceAdded(
                mBroadcastMetadata1, /* isPaSynced */ true, /* isBisSynced */ true);

        // Add another new broadcast source
        onScanResult(mSourceDevice2, TEST_BROADCAST_ID_2);
        onSyncEstablished(mSourceDevice2, TEST_SYNC_HANDLE_2);
        addSourceAndVerify(mBroadcastMetadata2);
        assertThat(mStateMachines).hasSize(2);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            if (sm.getDevice().equals(mCurrentDevice)) {
                injectRemoteSourceStateSourceAdded(
                        sm,
                        mBroadcastMetadata2,
                        TEST_SOURCE_ID + 2,
                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                        mBroadcastMetadata2.isEncrypted()
                                ? BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING
                                : BluetoothLeBroadcastReceiveState
                                        .BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                        null,
                        0L);
            } else if (sm.getDevice().equals(mCurrentDevice1)) {
                injectRemoteSourceStateSourceAdded(
                        sm,
                        mBroadcastMetadata2,
                        TEST_SOURCE_ID + 3,
                        BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                        mBroadcastMetadata2.isEncrypted()
                                ? BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_DECRYPTING
                                : BluetoothLeBroadcastReceiveState
                                        .BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                        null,
                        0L);
            } else {
                throw new AssertionError("Unexpected device");
            }
        }

        mBassClientService.stopSearchingForSources();

        // PAST request for first broadcaster
        mBassClientService.syncRequestForPast(mCurrentDevice, TEST_BROADCAST_ID, TEST_SOURCE_ID);
        verifyBackgroundSearchStarted();
        verifyRegisterSyncCalled(mSourceDevice);

        // Move time forward half of time
        mLooper.moveTimeForward(BassClientService.sPastResponseTimeout.toMillis() / 2);
        mLooper.dispatchAll();

        // PAST request for another broadcaster
        mBassClientService.syncRequestForPast(
                mCurrentDevice, TEST_BROADCAST_ID_2, TEST_SOURCE_ID + 2);
        // It will be registered on first sync established (pass or fail)

        // Try again on scan result after sync failed for both broadcasters
        onSyncEstablishedFailed(mSourceDevice, TEST_SYNC_HANDLE);
        verifyRegisterSyncCalled(mSourceDevice2);
        onSyncEstablishedFailed(mSourceDevice2, TEST_SYNC_HANDLE_2);
        if (Flags.leaudioBroadcastAlwaysUseBackgroundScanner()) {
            onScanResult(mSourceDevice, TEST_BROADCAST_ID);
            onScanResult(mSourceDevice2, TEST_BROADCAST_ID_2);
        }
        verifyRegisterSyncCalled(mSourceDevice);

        // Move time forward half of time, timeout for first broadcaster
        // Background searching not stopped as second broadcast is monitored but unregister
        // pending sync of first broadacaster
        mLooper.moveTimeForward(BassClientService.sPastResponseTimeout.toMillis() / 2);
        mLooper.dispatchAll();
        if (Flags.leaudioBroadcastAlwaysUseBackgroundScanner()) {
            assertThat(mBassClientService.isAnySearchInProgress()).isTrue();
            verifyUnregisterSyncCalled();
        } else {
            onSyncEstablishedFailed(mSourceDevice, TEST_SYNC_HANDLE);
        }

        // Not try again on scan result after sync failed for first broadcaster but only for second
        verifyRegisterSyncCalled(mSourceDevice2);
        onSyncEstablishedFailed(mSourceDevice2, TEST_SYNC_HANDLE_2);
        if (Flags.leaudioBroadcastAlwaysUseBackgroundScanner()) {
            onScanResult(mSourceDevice, TEST_BROADCAST_ID);
            onScanResult(mSourceDevice2, TEST_BROADCAST_ID_2);
        }
        verifyRegisterSyncCalled(mSourceDevice2);

        // Move time forward, timeout for second broadcaster
        mLooper.moveTimeForward(BassClientService.sPastResponseTimeout.toMillis());
        mLooper.dispatchAll();
        if (Flags.leaudioBroadcastAlwaysUseBackgroundScanner()) {
            // Stop background searching and unregiser pending sync
            assertThat(mBassClientService.isAnySearchInProgress()).isFalse();
            verifyUnregisterSyncCalled();
        } else {
            // Not try again on sync failed for second broadcaster
            onSyncEstablishedFailed(mSourceDevice2, TEST_SYNC_HANDLE_2);
            verifyRegisterSyncNeverCalled();
        }
    }

    private void verifyConnectionStateIntent(BluetoothDevice device, int newState, int prevState) {
        verifyIntentSent(
                hasAction(BluetoothLeBroadcastAssistant.ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
                hasExtra(EXTRA_STATE, newState),
                hasExtra(EXTRA_PREVIOUS_STATE, prevState));
    }

    @SafeVarargs
    private void verifyIntentSent(Matcher<Intent>... matchers) {
        mInOrderAdapterService
                .verify(mAdapterService, timeout(1000))
                .sendBroadcastMultiplePermissions(
                        MockitoHamcrest.argThat(AllOf.allOf(matchers)),
                        any(),
                        any(BroadcastOptions.class));
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_SOURCE_CHANNEL_MAP_CLASSIFICATION)
    public void testNotifyReceiveStateChanged_addClientForBigChannelMapwhenPaSynced() {
        // Mock that the broadcast is local
        doReturn(mBroadcastMetadata1).when(mLeAudioService).getBroadcastMetadata(anyInt());
        doReturn(true).when(mAdapterService).isLeBigSetChannelClassificationSupported();
        prepareConnectedDeviceGroup();

        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ false);

        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ true, /* isBisSynced */ false);

        // Verify that setBigChannelMapClassification is called with ADD action
        verify(mLeAudioService)
                .setBigChannelMapClassification(
                        eq(BassClientService.SetBigChannelMapClassificationAction.ADD.getValue()),
                        eq(mCurrentDevice),
                        eq(mBroadcastMetadata1.getBroadcastId()));
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_SOURCE_CHANNEL_MAP_CLASSIFICATION,
        Flags.FLAG_LEAUDIO_BROADCAST_SOURCE_CHANNEL_MAP_CLASSIFICATION_IMPROVEMENT
    })
    public void testNotifyReceiveStateChanged_addClientForBigChannelMapwhenBisSynced() {
        // Mock that the broadcast is local
        doReturn(mBroadcastMetadata1).when(mLeAudioService).getBroadcastMetadata(anyInt());
        doReturn(true).when(mAdapterService).isLeBigSetChannelClassificationSupported();
        prepareConnectedDeviceGroup();

        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ false);

        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ true);

        // Verify that setBigChannelMapClassification is called with ADD action
        verify(mLeAudioService)
                .setBigChannelMapClassification(
                        eq(BassClientService.SetBigChannelMapClassificationAction.ADD.getValue()),
                        eq(mCurrentDevice),
                        eq(mBroadcastMetadata1.getBroadcastId()));
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_SOURCE_CHANNEL_MAP_CLASSIFICATION)
    public void testNotifyReceiveStateChanged_deleteClientForBigChannelMap() {
        // Mock that the broadcast is local
        doReturn(mBroadcastMetadata1).when(mLeAudioService).getBroadcastMetadata(anyInt());
        doReturn(true).when(mAdapterService).isLeBigSetChannelClassificationSupported();
        prepareConnectedDeviceGroup();

        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ true, /* isBisSynced */ false);

        // Clear the mock so we can focus on verifying the DELETE action.
        clearInvocations(mLeAudioService);

        // Create a new metadata object with a null device to simulate removal.
        BluetoothLeBroadcastMetadata emptyMetadata =
                new BluetoothLeBroadcastMetadata.Builder(mBroadcastMetadata1)
                        .setSourceDevice(
                                getRealDevice("00:00:00:00:00:00", ADDRESS_TYPE_PUBLIC),
                                ADDRESS_TYPE_PUBLIC)
                        .build();

        // Inject a state change using the empty metadata
        // This should cause `isEmptyBluetoothDevice` to be true, and `newSyncStatus` to be
        // `NOT_SYNCED`
        injectRemoteSourceStateChanged(
                emptyMetadata, /* isPaSynced */ false, /* isBisSynced */ false);

        // Verify that setBigChannelMapClassification is called with DELETE action
        verify(mLeAudioService)
                .setBigChannelMapClassification(
                        eq(
                                BassClientService.SetBigChannelMapClassificationAction.DELETE
                                        .getValue()),
                        eq(mCurrentDevice),
                        eq(mBroadcastMetadata1.getBroadcastId()));
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_SOURCE_CHANNEL_MAP_CLASSIFICATION)
    public void testNotifyReceiveStateChanged_notLocalBroadcast_doNothing() {
        // Mock that the broadcast is not local
        doReturn(null).when(mLeAudioService).getBroadcastMetadata(anyInt());
        doReturn(true).when(mAdapterService).isLeBigSetChannelClassificationSupported();
        prepareConnectedDeviceGroup();

        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ false);

        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ true, /* isBisSynced */ false);

        // Verify that setBigChannelMapClassification is never called
        verify(mLeAudioService, never())
                .setBigChannelMapClassification(anyInt(), any(BluetoothDevice.class), anyInt());
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_SOURCE_CHANNEL_MAP_CLASSIFICATION)
    public void testNotifyReceiveStateChanged_noTargetPaSyncStateChange_doNothing() {
        doReturn(mBroadcastMetadata1).when(mLeAudioService).getBroadcastMetadata(anyInt());
        doReturn(true).when(mAdapterService).isLeBigSetChannelClassificationSupported();
        prepareConnectedDeviceGroup();

        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ false, /* isBisSynced */ false);

        // Verify that setBigChannelMapClassification is never called
        verify(mLeAudioService, never())
                .setBigChannelMapClassification(anyInt(), any(BluetoothDevice.class), anyInt());
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_TREAT_EMPTY_RS_EXPLICITLY)
    public void sourcesRemovedAutonomously() {
        prepareSynchronizedPair();

        BassClientStateMachine sm1 = mStateMachines.get(mCurrentDevice);
        BassClientStateMachine sm2 = mStateMachines.get(mCurrentDevice1);

        // Simulate PA and BIG lost which is mandatory to remove the source
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced= */ false, /* isBisSynced= */ false);

        // That will start BIG monitoring
        checkTimeout(TEST_BROADCAST_ID, BassClientService.MESSAGE_BIG_MONITOR_TIMEOUT);

        // Simulate autonomous sources removal by injecting a source removal on both devices
        injectRemoteSourceStateRemoval(sm1, TEST_SOURCE_ID);
        injectRemoteSourceStateRemoval(sm2, TEST_SOURCE_ID + 1);

        checkNoResumeSynchronizationByBig();
        checkNoResumeSynchronizationByHost();
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_TREAT_EMPTY_RS_EXPLICITLY)
    public void sourceRemovedWhilePausedByHost() {
        prepareSynchronizedPair();

        BassClientStateMachine sm1 = mStateMachines.get(mCurrentDevice);
        BassClientStateMachine sm2 = mStateMachines.get(mCurrentDevice1);

        // Suspend all receivers, which sets the paused sinks
        mBassClientService.suspendAllReceiversSourceSynchronization();
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced= */ true, /* isBisSynced= */ false);
        clearInvocations(sm1);
        clearInvocations(sm2);

        // Simulate PA lost which is mandatory to remove the source
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced= */ false, /* isBisSynced= */ false);

        // Simulate autonomous source removal by injecting a source removal event on first device
        injectRemoteSourceStateRemoval(sm1, TEST_SOURCE_ID);

        // Verify that resume will update only second device
        mBassClientService.resumeReceiversSourceSynchronization();
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        Optional<Message> msg;
        verify(sm2).sendMessage(messageCaptor.capture());
        msg =
                messageCaptor.getAllValues().stream()
                        .filter(m -> m.what == BassClientStateMachine.UPDATE_BCAST_SOURCE)
                        .findFirst();
        expect.that(msg.isPresent()).isTrue();
        expect.that(msg.orElse(null)).isNotNull();
        verify(sm1, never()).sendMessage(any());
    }

    @Test
    public void testIsEncrypted() {
        // Device does not exist/never been connected -> false
        assertThat(mBassClientService.isEncrypted(getTestDevice(99))).isFalse();

        // Simulate device connection
        prepareConnectedDeviceGroup();

        // Device connected -> no ACTION_ENCRYPTION_CHANGE broadcast -> isEncrypted() -> false
        assertThat(mBassClientService.isEncrypted(mCurrentDevice)).isFalse();

        // Simulate ACTION_ENCRYPTION_CHANGE broadcast with encryption disabled
        Intent encryptionChangeIntentDisabled =
                new Intent(BluetoothDevice.ACTION_ENCRYPTION_CHANGE);
        encryptionChangeIntentDisabled.putExtra(BluetoothDevice.EXTRA_DEVICE, mCurrentDevice);
        encryptionChangeIntentDisabled.putExtra(BluetoothDevice.EXTRA_ENCRYPTION_ENABLED, false);
        encryptionChangeIntentDisabled.putExtra(
                BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE);
        mBassClientService.mEncryptionStateReceiver.onReceive(
                ApplicationProvider.getApplicationContext(), encryptionChangeIntentDisabled);
        assertThat(mBassClientService.isEncrypted(mCurrentDevice)).isFalse();

        // Simulate ACTION_ENCRYPTION_CHANGE broadcast with encryption enabled
        Intent encryptionChangeIntentEnabled = new Intent(BluetoothDevice.ACTION_ENCRYPTION_CHANGE);
        encryptionChangeIntentEnabled.putExtra(BluetoothDevice.EXTRA_DEVICE, mCurrentDevice);
        encryptionChangeIntentEnabled.putExtra(BluetoothDevice.EXTRA_ENCRYPTION_ENABLED, true);
        encryptionChangeIntentEnabled.putExtra(
                BluetoothDevice.EXTRA_ENCRYPTION_STATUS, BluetoothStatusCodes.SUCCESS);
        encryptionChangeIntentEnabled.putExtra(
                BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_LE);
        mBassClientService.mEncryptionStateReceiver.onReceive(
                ApplicationProvider.getApplicationContext(), encryptionChangeIntentEnabled);
        assertThat(mBassClientService.isEncrypted(mCurrentDevice)).isTrue();

        // Device disconnected -> isEncrypted() -> false
        injectDeviceDisconnection(mCurrentDevice);
        assertThat(mBassClientService.isEncrypted(mCurrentDevice)).isFalse();
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_AUTO_SWITCH_ANNOUNCEMENT)
    public void testResumeSynchronization_SpecificBroadcast_BigInfoReport() {
        prepareConnectedDeviceGroup();

        // Set maximum source capacity to 2
        for (BassClientStateMachine sm : mStateMachines.values()) {
            doReturn(2).when(sm).getMaximumSourceCapacity();
        }

        // Add source 1
        prepareSyncToSourceAndVerify();
        addSourceAndVerify(mBroadcastMetadata1);
        injectRemoteSourceStateSourceAdded(
                mBroadcastMetadata1, /* isPaSynced */ true, /* isBisSynced */ true);

        // Add source 2
        onScanResult(mSourceDevice2, TEST_BROADCAST_ID_2);
        onSyncEstablished(mSourceDevice2, TEST_SYNC_HANDLE_2);
        addSourceAndVerify(mBroadcastMetadata2);

        // For metadata 2, inject with correct source IDs (TEST_SOURCE_ID + 2/3)
        for (BassClientStateMachine sm : mStateMachines.values()) {
            int sourceId =
                    sm.getDevice().equals(mCurrentDevice) ? TEST_SOURCE_ID + 2 : TEST_SOURCE_ID + 3;
            injectRemoteSourceStateSourceAdded(
                    sm,
                    mBroadcastMetadata2,
                    sourceId,
                    BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED,
                    BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                    null,
                    1L);
        }

        // Simulate loss of sync for both to trigger BIG_MONITORING
        // Source 1 lost
        injectRemoteSourceStateChanged(
                mStateMachines.get(mCurrentDevice),
                mBroadcastMetadata1,
                TEST_SOURCE_ID,
                BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                null,
                0L);
        injectRemoteSourceStateChanged(
                mStateMachines.get(mCurrentDevice1),
                mBroadcastMetadata1,
                TEST_SOURCE_ID + 1,
                BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                null,
                0L);

        // Source 2 lost
        injectRemoteSourceStateChanged(
                mStateMachines.get(mCurrentDevice),
                mBroadcastMetadata2,
                TEST_SOURCE_ID + 2,
                BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                null,
                0L);
        injectRemoteSourceStateChanged(
                mStateMachines.get(mCurrentDevice1),
                mBroadcastMetadata2,
                TEST_SOURCE_ID + 3,
                BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                null,
                0L);

        // Clear invocations
        for (BassClientStateMachine sm : mStateMachines.values()) {
            clearInvocations(sm);
        }

        // Trigger BIG Info report for Source 1
        onPeriodicAdvertisingReport();
        onBigInfoAdvertisingReport();

        // Verify Source 1 resumed
        verifyAllGroupMembersGettingUpdateOrAddSource(mBroadcastMetadata1);

        // Verify Source 2 NOT resumed
        for (BassClientStateMachine sm : mStateMachines.values()) {
            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            verify(sm, atLeast(0)).sendMessage(messageCaptor.capture());
            for (Message msg : messageCaptor.getAllValues()) {
                if (msg.what == BassClientStateMachine.ADD_BCAST_SOURCE
                        || msg.what == BassClientStateMachine.UPDATE_BCAST_SOURCE) {
                    if (msg.obj instanceof BluetoothLeBroadcastMetadata) {
                        BluetoothLeBroadcastMetadata meta = (BluetoothLeBroadcastMetadata) msg.obj;
                        if (meta.getBroadcastId() == TEST_BROADCAST_ID_2) {
                            throw new AssertionError("Should not resume Broadcast 2");
                        }
                    }
                }
            }
        }
    }

    private BluetoothLeBroadcastMetadata createInstructionalBroadcastMetadata(int broadcastId) {
        BluetoothLeAudioContentMetadata contentMetadata =
                BluetoothLeAudioContentMetadata.fromRawBytes(
                        new byte[] {
                            0x03,
                            0x02, // Type: Streaming Audio Contexts
                            (byte) (BluetoothLeAudio.CONTEXT_TYPE_INSTRUCTIONAL & 0xFF),
                            (byte) ((BluetoothLeAudio.CONTEXT_TYPE_INSTRUCTIONAL >> 8) & 0xFF),
                            0x02,
                            0x08, // Type: Audio Active State
                            0x00 // Value: False
                        });

        BluetoothLeBroadcastSubgroup subgroup =
                new BluetoothLeBroadcastSubgroup.Builder()
                        .setCodecId(TEST_CODEC_ID)
                        .setCodecSpecificConfig(
                                new BluetoothLeAudioCodecConfigMetadata.Builder()
                                        .setAudioLocation(TEST_AUDIO_LOCATION_FRONT_LEFT)
                                        .build())
                        .setContentMetadata(contentMetadata)
                        .addChannel(
                                new BluetoothLeBroadcastChannel.Builder()
                                        .setSelected(true)
                                        .setChannelIndex(1)
                                        .setCodecMetadata(
                                                new BluetoothLeAudioCodecConfigMetadata.Builder()
                                                        .setAudioLocation(
                                                                TEST_AUDIO_LOCATION_FRONT_LEFT)
                                                        .build())
                                        .build())
                        .build();

        return new BluetoothLeBroadcastMetadata.Builder()
                .setEncrypted(false)
                .setSourceDevice(mSourceDevice, ADDRESS_TYPE_RANDOM)
                .setSourceAdvertisingSid(TEST_ADVERTISER_SID)
                .setBroadcastId(broadcastId)
                .setPaSyncInterval(TEST_PA_SYNC_INTERVAL)
                .setPresentationDelayMicros(TEST_PRESENTATION_DELAY_MS)
                .addSubgroup(subgroup)
                .build();
    }

    private static PeriodicAdvertisingReport createPeriodicAdvertisingReportWithAudioActiveState(
            int syncHandle, boolean active) {
        byte[] scanRecord =
                new byte[] {
                    0x02,
                    0x01,
                    0x1a, // advertising flags
                    0x03,
                    0x02,
                    0x51,
                    0x18, // Service UUID 0x1851 (Basic Audio)
                    (byte) 0x18, // Length of Service Data
                    0x16,
                    0x51,
                    0x18, // Service Data UUID 0x1851
                    // Base Data
                    (byte) 0x01,
                    (byte) 0x02,
                    (byte) 0x03, // mPresentationDelay
                    (byte) 0x01, // mNumSubGroups
                    // Subgroup
                    (byte) 0x01, // mNumBises
                    (byte) 0x06,
                    (byte) 0x00,
                    (byte) 0x00,
                    (byte) 0x00,
                    (byte) 0x00, // Codec ID
                    (byte) 0x00, // mCodecSpecificConfigurationLength
                    (byte) 0x07, // mMetaDataLength
                    // Metadata: Audio Active State
                    (byte) 0x02, // Length
                    (byte) 0x08, // Type: Audio Active State
                    (byte) (active ? 0x01 : 0x00), // Value
                    // Metadata: Streaming Audio Contexts
                    (byte) 0x03, // Length
                    (byte) 0x02, // Type: Streaming Audio Contexts
                    (byte) (BluetoothLeAudio.CONTEXT_TYPE_INSTRUCTIONAL & 0xFF),
                    (byte) ((BluetoothLeAudio.CONTEXT_TYPE_INSTRUCTIONAL >> 8) & 0xFF),
                    // BIS
                    (byte) 0x01, // BIS Index
                    (byte) 0x00 // Codec Specific Config Length
                };
        return new PeriodicAdvertisingReport(
                syncHandle, 0, 0, 0, ScanRecord.parseFromBytes(scanRecord));
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_AUTO_SWITCH_ANNOUNCEMENT,
        Flags.FLAG_LEAUDIO_BROADCAST_ALWAYS_USE_BACKGROUND_SCANNER
    })
    public void announcementMonitoring_StartMonitoringOnInstructional_ResumeBroadcast() {
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();

        // Add source with instructional metadata
        BluetoothLeBroadcastMetadata instructionalMetadata =
                createInstructionalBroadcastMetadata(TEST_BROADCAST_ID);
        mBassClientService.addSource(mCurrentDevice, instructionalMetadata, /* isGroupOp */ true);
        injectRemoteSourceStateSourceAdded(instructionalMetadata, true, true);

        // Pause it via Unicast (REQUESTED)
        mBassClientService.handleUnicastSourceStreamStatusChange(
                LeAudioStackEvent.STATUS_LOCAL_STREAM_REQUESTED);
        injectRemoteSourceStateChanged(
                instructionalMetadata, /* isPaSynced */ true, /* isBisSynced */ false);

        // Clear invocations
        for (BassClientStateMachine sm : mStateMachines.values()) {
            clearInvocations(sm);
        }

        // Verify that monitoring started by checking if PA report triggers action
        // Inject PA report with Audio Active State = TRUE
        PeriodicAdvertisingReport report =
                createPeriodicAdvertisingReportWithAudioActiveState(TEST_SYNC_HANDLE, true);
        onPeriodicAdvertisingReport(report);
        verifyAllGroupMembersGettingUpdateOrAddSource(instructionalMetadata);
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_AUTO_SWITCH_ANNOUNCEMENT,
        Flags.FLAG_LEAUDIO_BROADCAST_ALWAYS_USE_BACKGROUND_SCANNER
    })
    public void announcementMonitoring_NotStartMonitoringWithoutInstructional() {
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();

        // Add source without instructional metadata
        mBassClientService.addSource(mCurrentDevice, mBroadcastMetadata1, /* isGroupOp */ true);
        injectRemoteSourceStateSourceAdded(mBroadcastMetadata1, true, true);

        // Pause it via Unicast (REQUESTED)
        mBassClientService.handleUnicastSourceStreamStatusChange(
                LeAudioStackEvent.STATUS_LOCAL_STREAM_REQUESTED);
        injectRemoteSourceStateChanged(
                mBroadcastMetadata1, /* isPaSynced */ true, /* isBisSynced */ false);

        // Clear invocations
        for (BassClientStateMachine sm : mStateMachines.values()) {
            clearInvocations(sm);
        }

        // Verify that monitoring is not started
        // Inject PA report with Audio Active State = TRUE
        PeriodicAdvertisingReport report =
                createPeriodicAdvertisingReportWithAudioActiveState(TEST_SYNC_HANDLE, true);
        onPeriodicAdvertisingReport(report);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            verify(sm, never()).sendMessage(any());
        }
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_AUTO_SWITCH_ANNOUNCEMENT,
        Flags.FLAG_LEAUDIO_BROADCAST_ALWAYS_USE_BACKGROUND_SCANNER
    })
    public void announcementMonitoring_Active_UnicastResumeFlagBehavior() {
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();

        // Add source with instructional metadata
        BluetoothLeBroadcastMetadata instructionalMetadata =
                createInstructionalBroadcastMetadata(TEST_BROADCAST_ID);
        mBassClientService.addSource(mCurrentDevice, instructionalMetadata, /* isGroupOp */ true);
        injectRemoteSourceStateSourceAdded(instructionalMetadata, true, true);

        // Pause it via Unicast (REQUESTED)
        mBassClientService.handleUnicastSourceStreamStatusChange(
                LeAudioStackEvent.STATUS_LOCAL_STREAM_REQUESTED);
        injectRemoteSourceStateChanged(
                instructionalMetadata, /* isPaSynced */ true, /* isBisSynced */ false);

        // Case 1: Unicast Streaming
        // mIsUnicastAutoResuming = true if active state becomes true
        mBassClientService.handleUnicastSourceStreamStatusChange(
                LeAudioStackEvent.STATUS_LOCAL_STREAM_STREAMING);
        PeriodicAdvertisingReport reportActive =
                createPeriodicAdvertisingReportWithAudioActiveState(TEST_SYNC_HANDLE, true);
        onPeriodicAdvertisingReport(reportActive);

        // Inject PA report (Inactive) should resume unicast
        PeriodicAdvertisingReport reportInactive =
                createPeriodicAdvertisingReportWithAudioActiveState(TEST_SYNC_HANDLE, false);
        onPeriodicAdvertisingReport(reportInactive);
        verify(mMcpService).playRequest();

        // Case 2: Unicast Suspended
        // mIsUnicastAutoResuming = false if active state is false during unicast suspending
        clearInvocations(mMcpService);
        mBassClientService.handleUnicastSourceStreamStatusChange(
                LeAudioStackEvent.STATUS_LOCAL_STREAM_SUSPENDED);

        // Inject PA report (Active)
        onPeriodicAdvertisingReport(reportActive);

        // Inject PA report (Inactive)
        onPeriodicAdvertisingReport(reportInactive);

        // Verify playRequest NOT called
        verify(mMcpService, never()).playRequest();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_AUTO_SWITCH_ANNOUNCEMENT,
        Flags.FLAG_LEAUDIO_BROADCAST_ALWAYS_USE_BACKGROUND_SCANNER
    })
    public void announcementMonitoring_StopMonitoringOnSourceRemoval() {
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();

        // Add source with instructional metadata
        BluetoothLeBroadcastMetadata instructionalMetadata =
                createInstructionalBroadcastMetadata(TEST_BROADCAST_ID);
        mBassClientService.addSource(mCurrentDevice, instructionalMetadata, /* isGroupOp */ true);
        injectRemoteSourceStateSourceAdded(instructionalMetadata, true, true);

        // Pause it via Unicast (REQUESTED)
        mBassClientService.handleUnicastSourceStreamStatusChange(
                LeAudioStackEvent.STATUS_LOCAL_STREAM_REQUESTED);
        injectRemoteSourceStateChanged(
                instructionalMetadata, /* isPaSynced */ true, /* isBisSynced */ false);

        // mIsUnicastAutoResuming = true if active state becomes true
        mBassClientService.handleUnicastSourceStreamStatusChange(
                LeAudioStackEvent.STATUS_LOCAL_STREAM_STREAMING);
        PeriodicAdvertisingReport reportActive =
                createPeriodicAdvertisingReportWithAudioActiveState(TEST_SYNC_HANDLE, true);
        onPeriodicAdvertisingReport(reportActive);

        // Inject Source Removal on first sink not cause disabling monitoring
        injectRemoteSourceStateRemoval(mStateMachines.get(mCurrentDevice), TEST_SOURCE_ID);

        // Inject PA report (Inactive) should resume unicast
        PeriodicAdvertisingReport reportInactive =
                createPeriodicAdvertisingReportWithAudioActiveState(TEST_SYNC_HANDLE, false);
        onPeriodicAdvertisingReport(reportInactive);
        verify(mMcpService).playRequest();
        clearInvocations(mMcpService);

        // Inject PA report (Active)
        onPeriodicAdvertisingReport(reportActive);

        // Inject Source Removal on second sink should disable monitoring
        injectRemoteSourceStateRemoval(mStateMachines.get(mCurrentDevice1), TEST_SOURCE_ID + 1);

        // Inject PA report (Inactive) should not try to resume unicast
        onPeriodicAdvertisingReport(reportInactive);
        verify(mMcpService, never()).playRequest();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_AUTO_SWITCH_ANNOUNCEMENT,
        Flags.FLAG_LEAUDIO_BROADCAST_ALWAYS_USE_BACKGROUND_SCANNER
    })
    public void announcementMonitoring_StopMonitoringOnDeviceDisconnection() {
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();

        // Add source with instructional metadata
        BluetoothLeBroadcastMetadata instructionalMetadata =
                createInstructionalBroadcastMetadata(TEST_BROADCAST_ID);
        mBassClientService.addSource(mCurrentDevice, instructionalMetadata, /* isGroupOp */ true);
        injectRemoteSourceStateSourceAdded(instructionalMetadata, true, true);

        // Pause it via Unicast (REQUESTED)
        mBassClientService.handleUnicastSourceStreamStatusChange(
                LeAudioStackEvent.STATUS_LOCAL_STREAM_REQUESTED);
        injectRemoteSourceStateChanged(
                instructionalMetadata, /* isPaSynced */ true, /* isBisSynced */ false);

        // mIsUnicastAutoResuming = true if active state becomes true
        mBassClientService.handleUnicastSourceStreamStatusChange(
                LeAudioStackEvent.STATUS_LOCAL_STREAM_STREAMING);
        PeriodicAdvertisingReport reportActive =
                createPeriodicAdvertisingReportWithAudioActiveState(TEST_SYNC_HANDLE, true);
        onPeriodicAdvertisingReport(reportActive);

        // Disconnect first sink not cause disabling monitoring
        injectDeviceDisconnection(mCurrentDevice);

        // Inject PA report (Inactive) should resume unicast
        PeriodicAdvertisingReport reportInactive =
                createPeriodicAdvertisingReportWithAudioActiveState(TEST_SYNC_HANDLE, false);
        onPeriodicAdvertisingReport(reportInactive);
        verify(mMcpService).playRequest();
        clearInvocations(mMcpService);

        // Inject PA report (Active)
        onPeriodicAdvertisingReport(reportActive);

        // Disconnect second sink should disable monitoring
        injectDeviceDisconnection(mCurrentDevice1);

        // Inject PA report (Inactive) should not try to resume unicast
        onPeriodicAdvertisingReport(reportInactive);
        verify(mMcpService, never()).playRequest();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_AUTO_SWITCH_ANNOUNCEMENT,
        Flags.FLAG_LEAUDIO_BROADCAST_ALWAYS_USE_BACKGROUND_SCANNER
    })
    public void announcementMonitoring_Inactive_SwitchesToAlternativeInstructional() {
        prepareConnectedDeviceGroup();
        prepareSyncToSourceAndVerify();

        // Increase capacity
        for (BassClientStateMachine sm : mStateMachines.values()) {
            doReturn(2).when(sm).getMaximumSourceCapacity();
        }

        BluetoothLeBroadcastMetadata meta1 =
                createInstructionalBroadcastMetadata(TEST_BROADCAST_ID);
        mBassClientService.addSource(mCurrentDevice, meta1, /* isGroupOp */ true);
        injectRemoteSourceStateSourceAdded(meta1, true, true);

        // Add Source 2
        BluetoothLeBroadcastMetadata meta2 =
                createInstructionalBroadcastMetadata(TEST_BROADCAST_ID_2);
        // Mock syncing to Source 2
        onScanResult(mSourceDevice2, TEST_BROADCAST_ID_2);
        onSyncEstablished(mSourceDevice2, TEST_SYNC_HANDLE_2);
        mBassClientService.addSource(mCurrentDevice, meta2, /* isGroupOp */ true);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            int sourceId =
                    sm.getDevice().equals(mCurrentDevice) ? TEST_SOURCE_ID + 2 : TEST_SOURCE_ID + 3;
            injectRemoteSourceStateSourceAdded(
                    sm,
                    meta2,
                    sourceId,
                    BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_SYNCHRONIZED,
                    BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                    null,
                    1L);
        }

        // Pause it via Unicast (REQUESTED)
        mBassClientService.handleUnicastSourceStreamStatusChange(
                LeAudioStackEvent.STATUS_LOCAL_STREAM_REQUESTED);
        injectRemoteSourceStateChanged(meta1, /* isPaSynced */ false, /* isBisSynced */ false);
        for (BassClientStateMachine sm : mStateMachines.values()) {
            int sourceId =
                    sm.getDevice().equals(mCurrentDevice) ? TEST_SOURCE_ID + 2 : TEST_SOURCE_ID + 3;
            injectRemoteSourceStateSourceAdded(
                    sm,
                    meta2,
                    sourceId,
                    BluetoothLeBroadcastReceiveState.PA_SYNC_STATE_IDLE,
                    BluetoothLeBroadcastReceiveState.BIG_ENCRYPTION_STATE_NOT_ENCRYPTED,
                    null,
                    0L);
        }

        // Make both Active but only first sync
        PeriodicAdvertisingReport report1Active =
                createPeriodicAdvertisingReportWithAudioActiveState(TEST_SYNC_HANDLE, true);
        PeriodicAdvertisingReport report2Active =
                createPeriodicAdvertisingReportWithAudioActiveState(TEST_SYNC_HANDLE_2, true);
        onPeriodicAdvertisingReport(report1Active);
        onPeriodicAdvertisingReport(report2Active);
        injectRemoteSourceStateChanged(meta1, /* isPaSynced */ true, /* isBisSynced */ true);

        // Clear invocations
        for (BassClientStateMachine sm : mStateMachines.values()) {
            clearInvocations(sm);
        }

        // Make Source 1 Inactive
        PeriodicAdvertisingReport report1Inactive =
                createPeriodicAdvertisingReportWithAudioActiveState(TEST_SYNC_HANDLE, false);
        onPeriodicAdvertisingReport(report1Inactive);

        // Verify switch to Source 2 (resume broadcast 2)
        for (BassClientStateMachine sm : mStateMachines.values()) {
            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());

            Optional<Message> msg =
                    messageCaptor.getAllValues().stream()
                            .filter(m -> m.what == BassClientStateMachine.UPDATE_BCAST_SOURCE)
                            .findFirst();
            assertThat(msg.isPresent()).isTrue();
            assertThat(msg.get().obj).isEqualTo(meta2);

            // Verify using the right sourceId on each device
            if (sm.getDevice().equals(mCurrentDevice)) {
                assertThat(msg.get().arg1).isEqualTo(TEST_SOURCE_ID + 2);
            } else if (sm.getDevice().equals(mCurrentDevice1)) {
                assertThat(msg.get().arg1).isEqualTo(TEST_SOURCE_ID + 3);
            } else {
                throw new AssertionError("Unexpected device");
            }
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_AURACAST_CREDENTIAL_EXTENSION)
    public void testAddSourceByUri_emptyName() {
        mBassClientService.addSourceByUri(
                mCurrentDevice, "", LeAudioConstants.INVALID_BROADCAST_ID, null);
        // Since the name is empty, it should return early and not add to the pending list
        assertThat(mBassClientService.mPendingNfcJoiningDevices).isEmpty();
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_AURACAST_CREDENTIAL_EXTENSION)
    public void testNfcJoinReceiver_missingMetadata() {
        Intent intent = new Intent(AuracastUtils.ACTION_CONNECT_STREAM);
        mBassClientService.mNfcJoinReceiver.onReceive(
                ApplicationProvider.getApplicationContext(), intent);

        // Should not crash and pending list should remain empty
        assertThat(mBassClientService.mPendingNfcJoiningDevices).isEmpty();
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_AURACAST_CREDENTIAL_EXTENSION)
    public void testLocalNotifySourceAdded_clearsPendingNfcDevices() {
        // Preparation: A connected device and a mock LeAudioService.
        prepareConnectedDeviceGroup();
        doReturn(Optional.of(mLeAudioService)).when(mAdapterService).getLeAudioService();
        doReturn(new ArrayList<>()).when(mLeAudioService).getConnectedDevices();
        doReturn(mBroadcastMetadata1).when(mLeAudioService).getBroadcastMetadata(TEST_BROADCAST_ID);
        doReturn(true).when(mLeAudioService).isPlaying(TEST_BROADCAST_ID);

        // Add a device to the pending list to ensure it actually gets cleared
        mBassClientService.mPendingNfcJoiningDevices.add(mCurrentDevice);
        assertThat(mBassClientService.mPendingNfcJoiningDevices).isNotEmpty();

        // Simulate adding a broadcast receiver. This triggers the update logic twice.
        // The first call is from the setup, and the second from this action.
        mBassClientService.addSource(mCurrentDevice, mBroadcastMetadata1, /* isGroupOp */ false);
        injectRemoteSourceStateSourceAdded(mBroadcastMetadata1, true, true);

        // Success should clear the pending join list completely
        assertThat(mBassClientService.mPendingNfcJoiningDevices).isEmpty();
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_AURACAST_CREDENTIAL_EXTENSION)
    public void testLocalNotifySourceAddFailed_showsNotificationWhenEmpty() {
        NotificationManager mockNm = mock(NotificationManager.class);
        RemoteDevices mockRemoteDevices = mock(RemoteDevices.class);

        mockGetSystemService(mAdapterService, NotificationManager.class, mockNm);
        doReturn(ApplicationProvider.getApplicationContext().getResources())
                .when(mAdapterService)
                .getResources();

        doReturn(ApplicationProvider.getApplicationContext().getApplicationInfo())
                .when(mBassClientService.getBaseContext())
                .getApplicationInfo();

        // Mock the RemoteDevices cache for the new alias/name logic
        doReturn(mockRemoteDevices).when(mAdapterService).getRemoteDevices();

        BluetoothDevice device = mock(BluetoothDevice.class);
        doReturn("Test Alias").when(mockRemoteDevices).getAlias(device);
        doReturn("Test Name").when(mockRemoteDevices).getName(device);

        mBassClientService.mPendingNfcJoiningDevices.add(device);

        BluetoothLeBroadcastMetadata metadata = mock(BluetoothLeBroadcastMetadata.class);
        doReturn(1).when(metadata).getBroadcastId();
        doReturn("TestName").when(metadata).getBroadcastName();

        mBassClientService
                .getCallbacks()
                .notifySourceAddFailed(device, metadata, BluetoothStatusCodes.ERROR_UNKNOWN);

        assertThat(mBassClientService.mPendingNfcJoiningDevices).isEmpty();

        // Capture the notification passed to the NotificationManager
        ArgumentCaptor<Notification> notificationCaptor =
                ArgumentCaptor.forClass(Notification.class);
        verify(mockNm).notify(eq(AuracastUtils.NOTIFICATION_ID), notificationCaptor.capture());

        // Extract the text from the captured notification's extras
        Notification notification = notificationCaptor.getValue();
        String text = notification.extras.getString(Notification.EXTRA_TEXT);

        // Verify the exact text hits the Alias branch properly
        assertThat(text)
                .isEqualTo("Failed to connect to TestName audio stream on your Test Alias.");
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_AURACAST_CREDENTIAL_EXTENSION)
    public void testAddSourceByUri_matchByName_ProcessAndAddSource() {
        prepareConnectedDeviceGroup();

        byte[] broadcastCode = new byte[] {1, 2, 3, 4};
        String broadcastName = "Test"; // "Test" is hardcoded in getScanRecord()

        // 1. addSourceByUri triggers the initial background scan and queues the source
        mBassClientService.addSourceByUri(
                mCurrentDevice,
                broadcastName,
                LeAudioConstants.INVALID_BROADCAST_ID,
                broadcastCode);

        // 2. Scan result matches the broadcast name and updates the broadcast ID in pending list
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);

        // 3. PA report triggers updateMetadata & processPendingAddSourceByUri -> ADD_BCAST_SOURCE
        onPeriodicAdvertisingReport();
        mLooper.dispatchAll();

        verifyAddBroadcastSourceWithExpectedData(broadcastName, TEST_BROADCAST_ID, broadcastCode);
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_AURACAST_CREDENTIAL_EXTENSION)
    public void testAddSourceByUri_matchByBroadcastId_ProcessAndAddSource() {
        prepareConnectedDeviceGroup();

        byte[] broadcastCode = new byte[] {1, 2, 3, 4};
        String correctBroadcastName = "Test"; // "Test" is hardcoded in getScanRecord()
        String wrongBroadcastName = "WrongName";

        // 1. addSourceByUri triggers the initial background scan and queues the source
        // We test with TEST_BROADCAST_ID to verify explicit ID matching instead of name matching
        mBassClientService.addSourceByUri(
                mCurrentDevice, wrongBroadcastName, TEST_BROADCAST_ID, broadcastCode);

        // 2. Scan result matches the exact broadcast ID and registers sync
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);

        // 3. PA report triggers processPendingAddSourceByUri -> ADD_BCAST_SOURCE
        onPeriodicAdvertisingReport();
        mLooper.dispatchAll();

        // Should add the source with the correct name from the broadcast metadata, not the wrong
        // one we provided initially
        verifyAddBroadcastSourceWithExpectedData(
                correctBroadcastName, TEST_BROADCAST_ID, broadcastCode);
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_AURACAST_CREDENTIAL_EXTENSION)
    public void testNfcJoinReceiver_withValidUri() {
        prepareConnectedDeviceGroup();

        NotificationManager mockNm = mock(NotificationManager.class);
        mockGetSystemService(mAdapterService, NotificationManager.class, mockNm);

        // "Test" in base64 is "VGVzdA==" | "123456" in base64 is "MTIzNDU2"
        // TEST_BROADCAST_ID is 42, which is 0x00002A in hex
        String uriStr = "BLUETOOTH:UUID:184F;BN:VGVzdA==;BI:00002A;BC:MTIzNDU2;;";
        Intent intent = new Intent(AuracastUtils.ACTION_CONNECT_STREAM);
        intent.putExtra(AuracastUtils.EXTRA_METADATA, uriStr);

        mBassClientService.mNfcJoinReceiver.onReceive(mAdapterService, intent);

        verify(mockNm).cancel(AuracastUtils.NOTIFICATION_ID);

        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        onPeriodicAdvertisingReport();
        mLooper.dispatchAll();

        verifyAddBroadcastSourceWithExpectedData(
                "Test",
                TEST_BROADCAST_ID,
                "123456".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_AURACAST_CREDENTIAL_EXTENSION)
    public void testNfcJoinReceiver_withoutBroadcastId_fallsBackToNameMatch() {
        prepareConnectedDeviceGroup();

        NotificationManager mockNm = mock(NotificationManager.class);
        mockGetSystemService(mAdapterService, NotificationManager.class, mockNm);

        // URI without BI field
        String uriStr = "BLUETOOTH:UUID:184F;BN:VGVzdA==;BC:MTIzNDU2;;";
        Intent intent = new Intent(AuracastUtils.ACTION_CONNECT_STREAM);
        intent.putExtra(AuracastUtils.EXTRA_METADATA, uriStr);

        mBassClientService.mNfcJoinReceiver.onReceive(mAdapterService, intent);

        verify(mockNm).cancel(AuracastUtils.NOTIFICATION_ID);

        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        onPeriodicAdvertisingReport();
        mLooper.dispatchAll();

        verifyAddBroadcastSourceWithExpectedData(
                "Test",
                TEST_BROADCAST_ID,
                "123456".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void verifyAddBroadcastSourceWithExpectedData(
            String expectedName, int expectedId, byte[] expectedCode) {
        verifyAddBroadcastSourceWithExpectedData(
                mStateMachines.values(), expectedName, expectedId, expectedCode);
    }

    private static void verifyAddBroadcastSourceWithExpectedData(
            Iterable<BassClientStateMachine> stateMachines,
            String expectedName,
            int expectedId,
            byte[] expectedCode) {
        for (BassClientStateMachine sm : stateMachines) {
            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            verify(sm, atLeast(1)).sendMessage(messageCaptor.capture());

            Message addSourceMsg =
                    messageCaptor.getAllValues().stream()
                            .filter(m -> m.what == BassClientStateMachine.ADD_BCAST_SOURCE)
                            .findFirst()
                            .orElse(null);

            assertThat(addSourceMsg).isNotNull();
            assertThat(addSourceMsg.obj).isInstanceOf(BluetoothLeBroadcastMetadata.class);

            BluetoothLeBroadcastMetadata metadata = (BluetoothLeBroadcastMetadata) addSourceMsg.obj;
            assertThat(metadata.getBroadcastName()).isEqualTo(expectedName);
            assertThat(metadata.getBroadcastId()).isEqualTo(expectedId);
            if (expectedCode != null) {
                assertThat(metadata.getBroadcastCode()).isEqualTo(expectedCode);
            } else {
                assertThat(metadata.getBroadcastCode()).isNull();
            }
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_AURACAST_CREDENTIAL_EXTENSION)
    public void testAddSourceByUri_MultiplePendingDifferentGroups() {
        prepareConnectedDeviceGroup(); // Sets up mCurrentDevice and mCurrentDevice1 in Group 1

        // Setup a third device in a different CSIP group
        BluetoothDevice device3 = getTestDevice(2);
        doReturn(Collections.singletonList(device3))
                .when(mCsipService)
                .getGroupDevicesOrdered(device3, BluetoothUuid.CAP);

        assertThat(mBassClientService.connect(device3)).isTrue();

        // Mock connection properties for device3
        BassClientStateMachine sm3 = mStateMachines.get(device3);
        doCallRealMethod().when(sm3).broadcastConnectionState(any(), anyInt(), anyInt());
        sm3.mService = mBassClientService;
        sm3.mDevice = device3;
        sm3.broadcastConnectionState(device3, STATE_CONNECTING, STATE_CONNECTED);

        doReturn(STATE_CONNECTED).when(sm3).getConnectionState();
        doReturn(true).when(sm3).isConnected();
        doReturn(true).when(mLeAudioService).isPrimaryDevice(device3);

        // Two brodcast codes only for test purposes
        byte[] broadcastCode1 = new byte[] {1, 2, 3, 4};
        byte[] broadcastCode2 = new byte[] {5, 6, 7, 8};
        String broadcastName = "Test"; // "Test" is hardcoded in getScanRecord()

        // 1. Queue addSourceByUri for devices across two different groups
        mBassClientService.addSourceByUri(
                mCurrentDevice,
                broadcastName,
                LeAudioConstants.INVALID_BROADCAST_ID,
                broadcastCode1);
        mBassClientService.addSourceByUri(
                device3, broadcastName, LeAudioConstants.INVALID_BROADCAST_ID, broadcastCode2);

        // 2. Scan result matches the broadcast name and updates the broadcast ID in pending list.
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);

        // 3. PA report triggers processPendingAddSourceByUri
        onPeriodicAdvertisingReport();
        mLooper.dispatchAll();

        // Verify the first group receives the ADD_BCAST_SOURCE with the first broadcast code
        BassClientStateMachine sm1 = mStateMachines.get(mCurrentDevice);
        BassClientStateMachine sm2 = mStateMachines.get(mCurrentDevice1);
        verifyAddBroadcastSourceWithExpectedData(
                Arrays.asList(sm1, sm2), broadcastName, TEST_BROADCAST_ID, broadcastCode1);

        // Verify the second group receives the ADD_BCAST_SOURCE with the second broadcast code
        verifyAddBroadcastSourceWithExpectedData(
                Collections.singletonList(sm3), broadcastName, TEST_BROADCAST_ID, broadcastCode2);
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_AURACAST_CREDENTIAL_EXTENSION)
    public void testDeviceDisconnection_clearsPendingNfcData() {
        prepareConnectedDeviceGroup();

        mBassClientService.mPendingNfcJoiningDevices.add(mCurrentDevice);
        mBassClientService.addSourceByUri(
                mCurrentDevice, "Test", LeAudioConstants.INVALID_BROADCAST_ID, null);

        assertThat(mBassClientService.mPendingNfcJoiningDevices).contains(mCurrentDevice);

        // Disconnect device
        injectDeviceDisconnection(mCurrentDevice);

        // Verify device is removed from NFC joining devices
        assertThat(mBassClientService.mPendingNfcJoiningDevices).doesNotContain(mCurrentDevice);

        // Verify device is removed from pending sources by name.
        // Re-connect the device to test that it doesn't process the stale pending source.
        injectDeviceConnection(mCurrentDevice);

        onScanResult(mSourceDevice, TEST_BROADCAST_ID); // Hardcoded getScanRecord() has name "Test"
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        onPeriodicAdvertisingReport();
        mLooper.dispatchAll();

        BassClientStateMachine sm = mStateMachines.get(mCurrentDevice);
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(sm, atLeast(0)).sendMessage(messageCaptor.capture());

        boolean hasAddSource =
                messageCaptor.getAllValues().stream()
                        .anyMatch(m -> m.what == BassClientStateMachine.ADD_BCAST_SOURCE);
        assertThat(hasAddSource).isFalse();
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_AURACAST_CREDENTIAL_EXTENSION)
    public void testCleanup_clearsPendingNfcData() {
        prepareConnectedDeviceGroup();
        mBassClientService.mPendingNfcJoiningDevices.add(mCurrentDevice);
        mBassClientService.cleanup();
        assertThat(mBassClientService.mPendingNfcJoiningDevices).isEmpty();
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_AURACAST_CREDENTIAL_EXTENSION)
    public void testAddSourceByUri_timeoutExpires_removesPendingAndShowsNotification() {
        prepareConnectedDeviceGroup();

        NotificationManager mockNm = mock(NotificationManager.class);
        RemoteDevices mockRemoteDevices = mock(RemoteDevices.class);

        mockGetSystemService(mAdapterService, NotificationManager.class, mockNm);
        doReturn(ApplicationProvider.getApplicationContext().getResources())
                .when(mAdapterService)
                .getResources();
        doReturn(ApplicationProvider.getApplicationContext().getApplicationInfo())
                .when(mBassClientService.getBaseContext())
                .getApplicationInfo();
        doReturn(mockRemoteDevices).when(mAdapterService).getRemoteDevices();
        doReturn("Test Alias").when(mockRemoteDevices).getAlias(mCurrentDevice);

        mBassClientService.mPendingNfcJoiningDevices.add(mCurrentDevice);
        mBassClientService.mPendingNfcJoiningDevices.add(mCurrentDevice1);

        String broadcastName = "TestBroadcast";
        mBassClientService.addSourceByUri(
                mCurrentDevice, broadcastName, LeAudioConstants.INVALID_BROADCAST_ID, null);

        if (Flags.leaudioBroadcastAlwaysUseBackgroundScanner()) {
            assertThat(mBassClientService.isAnySearchInProgress()).isTrue();
        }

        // Fast-forward time to trigger the timeout
        mLooper.moveTimeForward(BassClientService.sAddSourceByUriTimeout.toMillis());
        mLooper.dispatchAll();

        assertThat(mBassClientService.mPendingNfcJoiningDevices).isEmpty();
        assertThat(mBassClientService.isAnySearchInProgress()).isFalse();
        verify(mockNm).notify(eq(AuracastUtils.NOTIFICATION_ID), any(Notification.class));
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_AURACAST_CREDENTIAL_EXTENSION)
    public void testAddSourceByUri_sourceFoundBeforeTimeout_cancelsTimeout() {
        prepareConnectedDeviceGroup();

        NotificationManager mockNm = mock(NotificationManager.class);
        mockGetSystemService(mAdapterService, NotificationManager.class, mockNm);

        mBassClientService.mPendingNfcJoiningDevices.add(mCurrentDevice);
        mBassClientService.mPendingNfcJoiningDevices.add(mCurrentDevice1);

        String broadcastName = "Test"; // Matches getScanRecord()
        mBassClientService.addSourceByUri(
                mCurrentDevice, broadcastName, LeAudioConstants.INVALID_BROADCAST_ID, null);

        // Simulate broadcast found and synced before timeout
        onScanResult(mSourceDevice, TEST_BROADCAST_ID);
        onSyncEstablished(mSourceDevice, TEST_SYNC_HANDLE);
        onPeriodicAdvertisingReport();
        mLooper.dispatchAll();

        // Now fast-forward time past the timeout duration
        mLooper.moveTimeForward(BassClientService.sAddSourceByUriTimeout.toMillis());
        mLooper.dispatchAll();

        // Notification should NOT be shown because the timeout was canceled
        verify(mockNm, never()).notify(eq(AuracastUtils.NOTIFICATION_ID), any(Notification.class));
    }
}
