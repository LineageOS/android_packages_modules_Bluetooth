/*
 * Copyright 2021 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
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

package com.android.bluetooth.le_audio;

import static android.bluetooth.BluetoothDevice.BOND_BONDED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.EXTRA_PREVIOUS_STATE;
import static android.bluetooth.BluetoothProfile.EXTRA_STATE;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;
import static android.bluetooth.IBluetoothLeAudio.LE_AUDIO_GROUP_ID_INVALID;

import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;
import static com.android.bluetooth.TestUtils.mockGetSystemService;
import static com.android.bluetooth.bass_client.BassConstants.INVALID_BROADCAST_ID;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.Nullable;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothLeAudio;
import android.bluetooth.BluetoothLeAudioCodecConfig;
import android.bluetooth.BluetoothLeAudioCodecConfigMetadata;
import android.bluetooth.BluetoothLeAudioContentMetadata;
import android.bluetooth.BluetoothLeBroadcastChannel;
import android.bluetooth.BluetoothLeBroadcastMetadata;
import android.bluetooth.BluetoothLeBroadcastSettings;
import android.bluetooth.BluetoothLeBroadcastSubgroup;
import android.bluetooth.BluetoothLeBroadcastSubgroupSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.IBluetoothLeAudioCallback;
import android.bluetooth.IBluetoothLeBroadcastCallback;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.BluetoothProfileConnectionInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestUtils;
import com.android.bluetooth.Utils;
import com.android.bluetooth.bass_client.BassClientService;
import com.android.bluetooth.btservice.ActiveDeviceManager;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.MetricsLogger;
import com.android.bluetooth.btservice.ServiceFactory;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.tbs.TbsService;

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
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.hamcrest.MockitoHamcrest;

import java.util.ArrayList;
import java.util.List;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class LeAudioBroadcastServiceTest {
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private ActiveDeviceManager mActiveDeviceManager;
    @Mock private AdapterService mAdapterService;
    @Mock private DatabaseManager mDatabaseManager;
    @Mock private AudioManager mAudioManager;
    @Mock private LeAudioBroadcasterNativeInterface mLeAudioBroadcasterNativeInterface;
    @Mock private LeAudioNativeInterface mLeAudioNativeInterface;
    @Mock private LeAudioTmapGattServer mTmapGattServer;
    @Mock private BassClientService mBassClientService;
    @Mock private TbsService mTbsService;
    @Mock private MetricsLogger mMetricsLogger;
    @Mock private IBluetoothLeBroadcastCallback mCallbacks;
    @Mock private IBluetoothLeAudioCallback mLeAudioCallbacks;
    @Mock private IBinder mBinder;

    @Spy private LeAudioObjectsFactory mObjectsFactory = LeAudioObjectsFactory.getInstance();
    @Spy private ServiceFactory mServiceFactory = new ServiceFactory();

    private static final int CREATE_BROADCAST_TIMEOUT_MS = 6000;
    private static final String TEST_MAC_ADDRESS = "00:11:22:33:44:55";
    private static final int TEST_BROADCAST_ID = 42;
    private static final int TEST_ADVERTISER_SID = 1234;
    private static final int TEST_PA_SYNC_INTERVAL = 100;
    private static final int TEST_PRESENTATION_DELAY_MS = 345;
    private static final int TEST_CODEC_ID = 42;
    private static final int TEST_CHANNEL_INDEX = 56;
    // For BluetoothLeAudioCodecConfigMetadata
    private static final long TEST_AUDIO_LOCATION_FRONT_LEFT = 0x01;
    private static final long TEST_AUDIO_LOCATION_FRONT_RIGHT = 0x02;
    // For BluetoothLeAudioContentMetadata
    private static final String TEST_PROGRAM_INFO = "Test";
    // German language code in ISO 639-3
    private static final String TEST_LANGUAGE = "deu";
    private static final String TEST_BROADCAST_NAME = "Name Test";
    private static final BluetoothLeAudioCodecConfig LC3_16KHZ_CONFIG =
            new BluetoothLeAudioCodecConfig.Builder()
                    .setCodecType(BluetoothLeAudioCodecConfig.SOURCE_CODEC_TYPE_LC3)
                    .setSampleRate(BluetoothLeAudioCodecConfig.SAMPLE_RATE_16000)
                    .build();
    private static final BluetoothLeAudioCodecConfig LC3_48KHZ_CONFIG =
            new BluetoothLeAudioCodecConfig.Builder()
                    .setCodecType(BluetoothLeAudioCodecConfig.SOURCE_CODEC_TYPE_LC3)
                    .setSampleRate(BluetoothLeAudioCodecConfig.SAMPLE_RATE_48000)
                    .build();

    private static final List<BluetoothLeAudioCodecConfig> INPUT_SELECTABLE_CONFIG_STANDARD =
            List.of(LC3_16KHZ_CONFIG);
    private static final List<BluetoothLeAudioCodecConfig> OUTPUT_SELECTABLE_CONFIG_STANDARD =
            List.of(LC3_16KHZ_CONFIG);

    private static final List<BluetoothLeAudioCodecConfig> INPUT_SELECTABLE_CONFIG_HIGH =
            List.of(LC3_48KHZ_CONFIG);
    private static final List<BluetoothLeAudioCodecConfig> OUTPUT_SELECTABLE_CONFIG_HIGH =
            List.of(LC3_48KHZ_CONFIG);

    private final Context mTargetContext =
            InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final BluetoothAdapter mAdapter =
            mTargetContext.getSystemService(BluetoothManager.class).getAdapter();
    private final BluetoothDevice mDevice = getTestDevice(0);
    private final BluetoothDevice mDevice2 = getTestDevice(1);

    private final BluetoothDevice mBroadcastDevice = getTestDevice(1);

    private LeAudioService mService;
    private InOrder mInOrder;

    @Before
    public void setUp() throws Exception {
        mInOrder = inOrder(mAdapterService);

        doReturn(mAdapterService).when(mAdapterService).getApplicationContext();
        doReturn(mAdapterService).when(mAdapterService).createContextAsUser(any(), anyInt());
        doReturn(mTargetContext.getContentResolver()).when(mAdapterService).getContentResolver();

        doReturn(mBinder).when(mCallbacks).asBinder();
        doReturn(mBinder).when(mLeAudioCallbacks).asBinder();

        // Use spied objects factory
        LeAudioObjectsFactory.setInstanceForTesting(mObjectsFactory);
        doReturn(mTmapGattServer).when(mObjectsFactory).getTmapGattServer(any());

        if (Looper.myLooper() == null) {
            Looper.prepare();
        }

        doReturn(mDatabaseManager).when(mAdapterService).getDatabase();
        doReturn(true).when(mAdapterService).isLeAudioBroadcastSourceSupported();
        doReturn(
                        (long) (1 << BluetoothProfile.LE_AUDIO_BROADCAST)
                                | (1 << BluetoothProfile.LE_AUDIO))
                .when(mAdapterService)
                .getSupportedProfilesBitMask();
        doReturn(mActiveDeviceManager).when(mAdapterService).getActiveDeviceManager();

        MetricsLogger.setInstanceForTesting(mMetricsLogger);

        LeAudioBroadcasterNativeInterface.setInstance(mLeAudioBroadcasterNativeInterface);
        mockGetSystemService(
                mAdapterService, Context.AUDIO_SERVICE, AudioManager.class, mAudioManager);

        mService = new LeAudioService(mAdapterService, mLeAudioNativeInterface);
        mService.setAvailable(true);

        mService.mServiceFactory = mServiceFactory;
        mService.mTbsService = mTbsService;
        when(mServiceFactory.getBassClientService()).thenReturn(mBassClientService);
        // Set up the State Changed receiver

        when(mAdapterService.getDeviceFromByte(Utils.getBytesFromAddress("FF:FF:FF:FF:FF:FF")))
                .thenReturn(mBroadcastDevice);
    }

    @After
    public void tearDown() throws Exception {
        mService.cleanup();
        ;
        assertThat(LeAudioService.getLeAudioService()).isNull();
        LeAudioBroadcasterNativeInterface.setInstance(null);
        MetricsLogger.setInstanceForTesting(null);
    }

    @Test
    public void testGetLeAudioService() {
        assertThat(LeAudioService.getLeAudioService()).isEqualTo(mService);
    }

    void verifyBroadcastStarted(int broadcastId, BluetoothLeBroadcastSettings settings)
            throws RemoteException {
        mService.createBroadcast(settings);

        List<BluetoothLeBroadcastSubgroupSettings> settingsList = settings.getSubgroupSettings();

        int[] expectedQualityArray =
                settingsList.stream().mapToInt(setting -> setting.getPreferredQuality()).toArray();
        byte[][] expectedDataArray =
                settingsList.stream()
                        .map(setting -> setting.getContentMetadata().getRawMetadata())
                        .toArray(byte[][]::new);

        verify(mLeAudioBroadcasterNativeInterface)
                .createBroadcast(
                        eq(true),
                        eq(TEST_BROADCAST_NAME),
                        eq(settings.getBroadcastCode()),
                        eq(settings.getPublicBroadcastMetadata().getRawMetadata()),
                        eq(expectedQualityArray),
                        eq(expectedDataArray));

        // Check if broadcast is started automatically when created
        LeAudioStackEvent create_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_CREATED);
        create_event.valueInt1 = broadcastId;
        create_event.valueBool1 = true;
        mService.messageFromNative(create_event);

        verify(mLeAudioBroadcasterNativeInterface).startBroadcast(eq(broadcastId));

        // Notify initial paused state
        LeAudioStackEvent state_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_STATE);
        state_event.valueInt1 = broadcastId;
        state_event.valueInt2 = LeAudioStackEvent.BROADCAST_STATE_PAUSED;
        mService.messageFromNative(state_event);

        // Switch to active streaming
        state_event = new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_STATE);
        state_event.valueInt1 = broadcastId;
        state_event.valueInt2 = LeAudioStackEvent.BROADCAST_STATE_STREAMING;
        mService.messageFromNative(state_event);

        // Check if metadata is requested when the broadcast starts to stream
        verify(mLeAudioBroadcasterNativeInterface).getBroadcastMetadata(eq(broadcastId));
        TestUtils.waitForLooperToFinishScheduledTask(Looper.getMainLooper());

        verify(mCallbacks, never()).onBroadcastStartFailed(anyInt());
        verify(mCallbacks)
                .onBroadcastStarted(eq(BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST), anyInt());
    }

    void verifyBroadcastStopped(int broadcastId) throws RemoteException {
        Mockito.clearInvocations(mMetricsLogger);

        mService.stopBroadcast(broadcastId);
        verify(mLeAudioBroadcasterNativeInterface).stopBroadcast(eq(broadcastId));

        LeAudioStackEvent state_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_STATE);
        state_event.valueInt1 = broadcastId;
        state_event.valueInt2 = LeAudioStackEvent.BROADCAST_STATE_STOPPED;
        mService.messageFromNative(state_event);

        // Verify if broadcast is auto-destroyed on stop
        verify(mLeAudioBroadcasterNativeInterface).destroyBroadcast(eq(broadcastId));

        state_event = new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_DESTROYED);
        state_event.valueInt1 = broadcastId;
        mService.messageFromNative(state_event);

        TestUtils.waitForLooperToFinishScheduledTask(Looper.getMainLooper());

        // Verify broadcast audio session is logged when session stopped
        verify(mMetricsLogger)
                .logLeAudioBroadcastAudioSession(
                        eq(broadcastId),
                        eq(new int[] {0x2}), // STATS_SESSION_AUDIO_QUALITY_HIGH
                        eq(0),
                        anyLong(),
                        anyLong(),
                        anyLong(),
                        eq(0x3)); // STATS_SESSION_SETUP_STATUS_STREAMING
        verify(mCallbacks)
                .onBroadcastStopped(eq(BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST), anyInt());
        verify(mCallbacks, never()).onBroadcastStopFailed(anyInt());
    }

    @Test
    public void testCreateBroadcastNative() throws RemoteException {
        int broadcastId = 243;
        byte[] code = {0x00, 0x01, 0x00, 0x02};

        synchronized (mService.mBroadcastCallbacks) {
            mService.mBroadcastCallbacks.register(mCallbacks);
        }

        BluetoothLeAudioContentMetadata meta =
                new BluetoothLeAudioContentMetadata.Builder()
                        .setLanguage("deu")
                        .setProgramInfo("Subgroup broadcast info")
                        .build();

        verifyBroadcastStarted(broadcastId, buildBroadcastSettingsFromMetadata(meta, code, 1));
    }

    @Test
    public void testCreateBroadcastNativeMultiGroups() throws RemoteException {
        int broadcastId = 243;
        byte[] code = {0x00, 0x01, 0x00, 0x02};

        synchronized (mService.mBroadcastCallbacks) {
            mService.mBroadcastCallbacks.register(mCallbacks);
        }

        BluetoothLeAudioContentMetadata meta =
                new BluetoothLeAudioContentMetadata.Builder()
                        .setLanguage("deu")
                        .setProgramInfo("Subgroup broadcast info")
                        .build();

        verifyBroadcastStarted(broadcastId, buildBroadcastSettingsFromMetadata(meta, code, 3));
    }

    @Test
    public void testCreateBroadcastNativeFailed() throws RemoteException {
        int broadcastId = 243;
        byte[] code = {0x00, 0x01, 0x00, 0x02};

        synchronized (mService.mBroadcastCallbacks) {
            mService.mBroadcastCallbacks.register(mCallbacks);
        }
        Mockito.clearInvocations(mMetricsLogger);

        BluetoothLeAudioContentMetadata meta =
                new BluetoothLeAudioContentMetadata.Builder()
                        .setLanguage("deu")
                        .setProgramInfo("Public broadcast info")
                        .build();
        BluetoothLeBroadcastSettings settings = buildBroadcastSettingsFromMetadata(meta, code, 1);
        mService.createBroadcast(settings);

        // Test data with only one subgroup
        int[] expectedQualityArray = {settings.getSubgroupSettings().get(0).getPreferredQuality()};
        byte[][] expectedDataArray = {
            settings.getSubgroupSettings().get(0).getContentMetadata().getRawMetadata()
        };

        verify(mLeAudioBroadcasterNativeInterface)
                .createBroadcast(
                        eq(true),
                        eq(TEST_BROADCAST_NAME),
                        eq(code),
                        eq(settings.getPublicBroadcastMetadata().getRawMetadata()),
                        eq(expectedQualityArray),
                        eq(expectedDataArray));

        LeAudioStackEvent create_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_CREATED);
        create_event.valueInt1 = broadcastId;
        create_event.valueBool1 = false;
        mService.messageFromNative(create_event);

        TestUtils.waitForLooperToFinishScheduledTask(Looper.getMainLooper());

        // Verify broadcast audio session is logged when session failed to create
        verify(mMetricsLogger)
                .logLeAudioBroadcastAudioSession(
                        eq(INVALID_BROADCAST_ID),
                        eq(new int[] {0x2}), // STATS_SESSION_AUDIO_QUALITY_HIGH
                        eq(0),
                        eq(0L),
                        eq(0L),
                        eq(0L),
                        eq(0x4)); // STATS_SESSION_SETUP_STATUS_CREATED_FAILED

        verify(mCallbacks, never()).onBroadcastStarted(anyInt(), anyInt());
        verify(mCallbacks).onBroadcastStartFailed(eq(BluetoothStatusCodes.ERROR_UNKNOWN));
    }

    @Test
    public void testCreateBroadcastTimeout() throws RemoteException {
        byte[] code = {0x00, 0x01, 0x00, 0x02};

        synchronized (mService.mBroadcastCallbacks) {
            mService.mBroadcastCallbacks.register(mCallbacks);
        }
        Mockito.clearInvocations(mMetricsLogger);

        BluetoothLeAudioContentMetadata meta =
                new BluetoothLeAudioContentMetadata.Builder()
                        .setLanguage("deu")
                        .setProgramInfo("Public broadcast info")
                        .build();
        BluetoothLeBroadcastSettings settings = buildBroadcastSettingsFromMetadata(meta, code, 1);
        mService.createBroadcast(settings);

        if (Flags.leaudioBigDependsOnAudioState()) {
            verify(mCallbacks, timeout(CREATE_BROADCAST_TIMEOUT_MS))
                    .onBroadcastStartFailed(eq(BluetoothStatusCodes.ERROR_TIMEOUT));
        } else {
            int broadcastId = 243;
            // Test data with only one subgroup
            int[] expectedQualityArray = {
                settings.getSubgroupSettings().get(0).getPreferredQuality()
            };
            byte[][] expectedDataArray = {
                settings.getSubgroupSettings().get(0).getContentMetadata().getRawMetadata()
            };

            verify(mLeAudioBroadcasterNativeInterface)
                    .createBroadcast(
                            eq(true),
                            eq(TEST_BROADCAST_NAME),
                            eq(code),
                            eq(settings.getPublicBroadcastMetadata().getRawMetadata()),
                            eq(expectedQualityArray),
                            eq(expectedDataArray));

            // Check if broadcast is started automatically when created
            LeAudioStackEvent create_event =
                    new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_CREATED);
            create_event.valueInt1 = broadcastId;
            create_event.valueBool1 = true;
            mService.messageFromNative(create_event);

            // Verify if broadcast is auto-started on start
            verify(mLeAudioBroadcasterNativeInterface).startBroadcast(eq(broadcastId));
            TestUtils.waitForLooperToFinishScheduledTask(Looper.getMainLooper());

            verify(mCallbacks)
                    .onBroadcastStarted(
                            eq(BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST), anyInt());

            // Notify initial paused state
            LeAudioStackEvent state_event =
                    new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_STATE);
            state_event.valueInt1 = broadcastId;
            state_event.valueInt2 = LeAudioStackEvent.BROADCAST_STATE_PAUSED;
            mService.messageFromNative(state_event);

            // Check if broadcast is destroyed after timeout
            verify(mLeAudioBroadcasterNativeInterface, timeout(CREATE_BROADCAST_TIMEOUT_MS))
                    .destroyBroadcast(eq(broadcastId));

            state_event = new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_DESTROYED);
            state_event.valueInt1 = broadcastId;
            mService.messageFromNative(state_event);

            // Verify broadcast audio session is logged when session failed to stream
            verify(mMetricsLogger)
                    .logLeAudioBroadcastAudioSession(
                            eq(broadcastId),
                            eq(new int[] {0x2}), // STATS_SESSION_AUDIO_QUALITY_HIGH
                            eq(0),
                            anyLong(),
                            anyLong(),
                            eq(0L),
                            eq(0x5)); // STATS_SESSION_SETUP_STATUS_STREAMING_FAILED
        }
    }

    @Test
    public void testCreateBroadcast_noAudioQualityUpdate() {
        byte[] code = {0x00, 0x01, 0x00, 0x02};
        int groupId = 1;

        initializeNative();
        prepareConnectedUnicastDevice(groupId, mDevice);

        synchronized (mService.mBroadcastCallbacks) {
            mService.mBroadcastCallbacks.register(mCallbacks);
        }

        BluetoothLeAudioContentMetadata meta =
                new BluetoothLeAudioContentMetadata.Builder().build();
        BluetoothLeBroadcastSettings settings = buildBroadcastSettingsFromMetadata(meta, code, 1);

        when(mBassClientService.getConnectedDevices()).thenReturn(List.of(mDevice));
        // update input selectable configs to be STANDARD quality
        // keep output selectable configs as HIGH quality
        injectGroupSelectableCodecConfigChanged(
                groupId, INPUT_SELECTABLE_CONFIG_STANDARD, OUTPUT_SELECTABLE_CONFIG_HIGH);
        injectGroupCurrentCodecConfigChanged(groupId, LC3_16KHZ_CONFIG, LC3_48KHZ_CONFIG);

        mService.createBroadcast(settings);

        // Test data with only one subgroup
        // Verify quality is  not updated to standard because HIGH is selectable in output
        int[] expectedQualityArray = {BluetoothLeBroadcastSubgroupSettings.QUALITY_HIGH};
        byte[][] expectedDataArray = {
            settings.getSubgroupSettings().get(0).getContentMetadata().getRawMetadata()
        };

        verify(mLeAudioBroadcasterNativeInterface)
                .createBroadcast(
                        eq(true),
                        eq(TEST_BROADCAST_NAME),
                        eq(code),
                        eq(settings.getPublicBroadcastMetadata().getRawMetadata()),
                        eq(expectedQualityArray),
                        eq(expectedDataArray));
    }

    @Test
    public void testCreateBroadcast_updateAudioQualityToStandard() {
        byte[] code = {0x00, 0x01, 0x00, 0x02};
        int groupId = 1;

        initializeNative();
        prepareConnectedUnicastDevice(groupId, mDevice);

        synchronized (mService.mBroadcastCallbacks) {
            mService.mBroadcastCallbacks.register(mCallbacks);
        }

        BluetoothLeAudioContentMetadata meta =
                new BluetoothLeAudioContentMetadata.Builder().build();
        BluetoothLeBroadcastSettings settings = buildBroadcastSettingsFromMetadata(meta, code, 1);

        when(mBassClientService.getConnectedDevices()).thenReturn(List.of(mDevice));
        // update output selectable configs to be STANDARD quality
        injectGroupSelectableCodecConfigChanged(
                groupId, INPUT_SELECTABLE_CONFIG_HIGH, OUTPUT_SELECTABLE_CONFIG_STANDARD);
        injectGroupCurrentCodecConfigChanged(groupId, LC3_16KHZ_CONFIG, LC3_48KHZ_CONFIG);

        mService.createBroadcast(settings);

        // Test data with only one subgroup
        // Verify quality is updated to standard per sinks capabilities
        int[] expectedQualityArray = {BluetoothLeBroadcastSubgroupSettings.QUALITY_STANDARD};
        byte[][] expectedDataArray = {
            settings.getSubgroupSettings().get(0).getContentMetadata().getRawMetadata()
        };

        verify(mLeAudioBroadcasterNativeInterface)
                .createBroadcast(
                        eq(true),
                        eq(TEST_BROADCAST_NAME),
                        eq(code),
                        eq(settings.getPublicBroadcastMetadata().getRawMetadata()),
                        eq(expectedQualityArray),
                        eq(expectedDataArray));
    }

    @Test
    public void testStartStopBroadcastNative() throws RemoteException {
        int broadcastId = 243;
        byte[] code = {0x00, 0x01, 0x00, 0x02};

        synchronized (mService.mBroadcastCallbacks) {
            mService.mBroadcastCallbacks.register(mCallbacks);
        }

        BluetoothLeAudioContentMetadata meta =
                new BluetoothLeAudioContentMetadata.Builder()
                        .setLanguage("deu")
                        .setProgramInfo("Subgroup broadcast info")
                        .build();

        verifyBroadcastStarted(broadcastId, buildBroadcastSettingsFromMetadata(meta, code, 1));
        verifyBroadcastStopped(broadcastId);
    }

    @Test
    public void testBroadcastInvalidBroadcastIdRequest() throws RemoteException {
        int broadcastId = 243;

        synchronized (mService.mBroadcastCallbacks) {
            mService.mBroadcastCallbacks.register(mCallbacks);
        }

        // Stop non-existing broadcast
        mService.stopBroadcast(broadcastId);

        TestUtils.waitForLooperToFinishScheduledTask(Looper.getMainLooper());

        verify(mCallbacks, never()).onBroadcastStopped(anyInt(), anyInt());
        verify(mCallbacks)
                .onBroadcastStopFailed(
                        eq(BluetoothStatusCodes.ERROR_LE_BROADCAST_INVALID_BROADCAST_ID));

        // Update metadata for non-existing broadcast
        BluetoothLeAudioContentMetadata meta =
                new BluetoothLeAudioContentMetadata.Builder()
                        .setLanguage("eng")
                        .setProgramInfo("Public broadcast info")
                        .build();
        mService.updateBroadcast(broadcastId, buildBroadcastSettingsFromMetadata(meta, null, 1));

        TestUtils.waitForLooperToFinishScheduledTask(Looper.getMainLooper());

        verify(mCallbacks, never()).onBroadcastUpdated(anyInt(), anyInt());
        verify(mCallbacks).onBroadcastUpdateFailed(anyInt(), anyInt());
    }

    private static BluetoothLeBroadcastSubgroup createBroadcastSubgroup() {
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

        BluetoothLeAudioCodecConfigMetadata channelCodecMetadata =
                new BluetoothLeAudioCodecConfigMetadata.Builder()
                        .setAudioLocation(TEST_AUDIO_LOCATION_FRONT_RIGHT)
                        .build();

        // builder expect at least one channel
        BluetoothLeBroadcastChannel channel =
                new BluetoothLeBroadcastChannel.Builder()
                        .setSelected(true)
                        .setChannelIndex(TEST_CHANNEL_INDEX)
                        .setCodecMetadata(channelCodecMetadata)
                        .build();
        builder.addChannel(channel);
        return builder.build();
    }

    private BluetoothLeBroadcastMetadata createBroadcastMetadata() {
        BluetoothDevice testDevice =
                mAdapter.getRemoteLeDevice(TEST_MAC_ADDRESS, BluetoothDevice.ADDRESS_TYPE_RANDOM);

        BluetoothLeBroadcastMetadata.Builder builder =
                new BluetoothLeBroadcastMetadata.Builder()
                        .setEncrypted(false)
                        .setSourceDevice(testDevice, BluetoothDevice.ADDRESS_TYPE_RANDOM)
                        .setSourceAdvertisingSid(TEST_ADVERTISER_SID)
                        .setBroadcastId(TEST_BROADCAST_ID)
                        .setBroadcastCode(null)
                        .setPaSyncInterval(TEST_PA_SYNC_INTERVAL)
                        .setPresentationDelayMicros(TEST_PRESENTATION_DELAY_MS);
        // builder expect at least one subgroup
        builder.addSubgroup(createBroadcastSubgroup());
        return builder.build();
    }

    @Test
    public void testGetAllBroadcastMetadata() {
        int broadcastId = 243;
        byte[] code = {0x00, 0x01, 0x00, 0x02};

        BluetoothLeAudioContentMetadata meta =
                new BluetoothLeAudioContentMetadata.Builder()
                        .setLanguage("ENG")
                        .setProgramInfo("Public broadcast info")
                        .build();
        mService.createBroadcast(buildBroadcastSettingsFromMetadata(meta, code, 1));

        LeAudioStackEvent create_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_CREATED);
        create_event.valueInt1 = broadcastId;
        create_event.valueBool1 = true;
        mService.messageFromNative(create_event);

        TestUtils.waitForLooperToFinishScheduledTask(Looper.getMainLooper());

        // Inject metadata stack event and verify if getter API works as expected
        LeAudioStackEvent state_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_METADATA_CHANGED);
        state_event.valueInt1 = broadcastId;
        state_event.broadcastMetadata = createBroadcastMetadata();
        mService.messageFromNative(state_event);

        TestUtils.waitForLooperToFinishScheduledTask(mService.getMainLooper());

        assertThat(mService.getAllBroadcastMetadata())
                .containsExactly(state_event.broadcastMetadata);
    }

    @Test
    public void testIsBroadcastActive() {
        int broadcastId = 243;
        byte[] code = {0x00, 0x01, 0x00, 0x02};

        BluetoothLeAudioContentMetadata meta =
                new BluetoothLeAudioContentMetadata.Builder()
                        .setLanguage("ENG")
                        .setProgramInfo("Public broadcast info")
                        .build();
        mService.createBroadcast(buildBroadcastSettingsFromMetadata(meta, code, 1));

        LeAudioStackEvent create_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_CREATED);
        create_event.valueInt1 = broadcastId;
        create_event.valueBool1 = true;
        mService.messageFromNative(create_event);

        // Inject metadata stack event and verify if getter API works as expected
        LeAudioStackEvent state_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_METADATA_CHANGED);
        state_event.valueInt1 = broadcastId;
        state_event.broadcastMetadata = createBroadcastMetadata();
        mService.messageFromNative(state_event);

        // Verify if broadcast is active
        assertThat(mService.isBroadcastActive()).isTrue();

        mService.stopBroadcast(broadcastId);
        verify(mLeAudioBroadcasterNativeInterface).stopBroadcast(eq(broadcastId));

        state_event = new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_STATE);
        state_event.valueInt1 = broadcastId;
        state_event.valueInt2 = LeAudioStackEvent.BROADCAST_STATE_STOPPED;
        mService.messageFromNative(state_event);

        verify(mLeAudioBroadcasterNativeInterface).destroyBroadcast(eq(broadcastId));

        state_event = new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_DESTROYED);
        state_event.valueInt1 = broadcastId;
        mService.messageFromNative(state_event);

        // Verify if broadcast is not active
        assertThat(mService.isBroadcastActive()).isFalse();
    }

    private void initializeNative() {
        LeAudioStackEvent stackEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_NATIVE_INITIALIZED);
        mService.messageFromNative(stackEvent);
        assertThat(mService.mLeAudioNativeIsInitialized).isTrue();
    }

    private void prepareConnectedUnicastDevice(int groupId, BluetoothDevice device) {
        int direction = 3;
        int snkAudioLocation = 3;
        int srcAudioLocation = 4;
        int availableContexts = 5 + BluetoothLeAudio.CONTEXT_TYPE_RINGTONE;

        /* Prepare active group to cause pending broadcast */
        doReturn(BOND_BONDED).when(mAdapterService).getBondState(any(BluetoothDevice.class));
        doReturn(true).when(mLeAudioNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        when(mDatabaseManager.getProfileConnectionPolicy(device, BluetoothProfile.LE_AUDIO))
                .thenReturn(CONNECTION_POLICY_ALLOWED);
        doReturn(new ParcelUuid[] {BluetoothUuid.LE_AUDIO})
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
        assertThat(mService.connect(device)).isTrue();

        // Verify the connection state broadcast, and that we are in Connected state
        verifyConnectionStateIntent(device, STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(device)).isEqualTo(STATE_CONNECTING);

        LeAudioStackEvent create_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        create_event.device = device;
        create_event.valueInt1 = LeAudioStackEvent.CONNECTION_STATE_CONNECTED;
        mService.messageFromNative(create_event);

        verifyConnectionStateIntent(device, STATE_CONNECTED, STATE_CONNECTING);
        assertThat(mService.getConnectionState(device)).isEqualTo(STATE_CONNECTED);

        create_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_NODE_STATUS_CHANGED);
        create_event.device = device;
        create_event.valueInt1 = groupId;
        create_event.valueInt2 = LeAudioStackEvent.GROUP_NODE_ADDED;
        mService.messageFromNative(create_event);

        create_event = new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_AUDIO_CONF_CHANGED);
        create_event.device = device;
        create_event.valueInt1 = direction;
        create_event.valueInt2 = groupId;
        create_event.valueInt3 = snkAudioLocation;
        create_event.valueInt4 = srcAudioLocation;
        create_event.valueInt5 = availableContexts;
        mService.messageFromNative(create_event);

        // Set default codec config to HIGH quality
        injectGroupSelectableCodecConfigChanged(
                groupId, INPUT_SELECTABLE_CONFIG_HIGH, OUTPUT_SELECTABLE_CONFIG_HIGH);
        injectGroupCurrentCodecConfigChanged(groupId, LC3_16KHZ_CONFIG, LC3_48KHZ_CONFIG);

        mService.deviceConnected(device);
    }

    @Test
    public void testCreatePendingBroadcast() {
        int groupId = 1;
        int broadcastId = 243;
        byte[] code = {0x00, 0x01, 0x00, 0x02};

        mSetFlagsRule.enableFlags(Flags.FLAG_LEAUDIO_BIG_DEPENDS_ON_AUDIO_STATE);

        initializeNative();
        prepareConnectedUnicastDevice(groupId, mDevice);

        LeAudioStackEvent stackEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        stackEvent.valueInt1 = groupId;
        stackEvent.valueInt2 = LeAudioStackEvent.GROUP_STATUS_ACTIVE;
        mService.messageFromNative(stackEvent);

        /* Prepare create broadcast */
        BluetoothLeAudioContentMetadata meta =
                new BluetoothLeAudioContentMetadata.Builder()
                        .setLanguage("ENG")
                        .setProgramInfo("Public broadcast info")
                        .build();

        BluetoothLeBroadcastSettings settings = buildBroadcastSettingsFromMetadata(meta, code, 1);
        mService.createBroadcast(settings);

        /* Successfully created audio session notification */
        stackEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_AUDIO_SESSION_CREATED);
        stackEvent.valueBool1 = true;
        mService.messageFromNative(stackEvent);

        /* Check if broadcast is started automatically when created */
        stackEvent = new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_CREATED);
        stackEvent.valueInt1 = broadcastId;
        stackEvent.valueBool1 = true;
        mService.messageFromNative(stackEvent);

        /* Active group should become inactive */
        int activeGroup = mService.getActiveGroupId();
        assertThat(activeGroup).isEqualTo(LE_AUDIO_GROUP_ID_INVALID);

        /* Imitate group inactivity to cause create broadcast */
        stackEvent = new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        stackEvent.valueInt1 = groupId;
        stackEvent.valueInt2 = LeAudioStackEvent.GROUP_STATUS_INACTIVE;
        mService.messageFromNative(stackEvent);

        List<BluetoothLeBroadcastSubgroupSettings> settingsList = settings.getSubgroupSettings();

        int[] expectedQualityArray =
                settingsList.stream().mapToInt(setting -> setting.getPreferredQuality()).toArray();
        byte[][] expectedDataArray =
                settingsList.stream()
                        .map(setting -> setting.getContentMetadata().getRawMetadata())
                        .toArray(byte[][]::new);

        verify(mLeAudioBroadcasterNativeInterface)
                .createBroadcast(
                        eq(true),
                        eq(TEST_BROADCAST_NAME),
                        eq(settings.getBroadcastCode()),
                        eq(settings.getPublicBroadcastMetadata().getRawMetadata()),
                        eq(expectedQualityArray),
                        eq(expectedDataArray));

        activeGroup = mService.getActiveGroupId();
        assertThat(activeGroup).isEqualTo(-1);
    }

    @Test
    public void testCreateBroadcastMoreThanMaxFailed() throws RemoteException {
        int broadcastId = 243;
        byte[] code = {0x00, 0x01, 0x00, 0x02};

        synchronized (mService.mBroadcastCallbacks) {
            mService.mBroadcastCallbacks.register(mCallbacks);
        }

        BluetoothLeAudioContentMetadata meta =
                new BluetoothLeAudioContentMetadata.Builder()
                        .setLanguage("deu")
                        .setProgramInfo("Subgroup broadcast info")
                        .build();
        BluetoothLeBroadcastSettings settings = buildBroadcastSettingsFromMetadata(meta, code, 1);

        TestUtils.waitForLooperToFinishScheduledTask(Looper.getMainLooper());

        verifyBroadcastStarted(broadcastId, settings);
        Mockito.clearInvocations(mCallbacks);

        // verify creating another broadcast will fail
        mService.createBroadcast(settings);

        TestUtils.waitForLooperToFinishScheduledTask(Looper.getMainLooper());

        verify(mCallbacks, never()).onBroadcastStarted(anyInt(), anyInt());
        verify(mCallbacks)
                .onBroadcastStartFailed(eq(BluetoothStatusCodes.ERROR_LOCAL_NOT_ENOUGH_RESOURCES));
    }

    private void prepareHandoverStreamingBroadcast(int groupId, int broadcastId, byte[] code) {
        mSetFlagsRule.enableFlags(Flags.FLAG_LEAUDIO_BIG_DEPENDS_ON_AUDIO_STATE);

        synchronized (mService.mBroadcastCallbacks) {
            mService.mBroadcastCallbacks.register(mCallbacks);
        }

        initializeNative();
        prepareConnectedUnicastDevice(groupId, mDevice);

        LeAudioStackEvent create_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        create_event.valueInt1 = groupId;
        create_event.valueInt2 = LeAudioStackEvent.GROUP_STATUS_ACTIVE;
        mService.messageFromNative(create_event);

        verify(mTbsService).setInbandRingtoneSupport(eq(mDevice));

        /* Verify Unicast input and output devices changed from null to mDevice */
        verify(mAudioManager, times(2))
                .handleBluetoothActiveDeviceChanged(
                        eq(mDevice), eq(null), any(BluetoothProfileConnectionInfo.class));
        Mockito.clearInvocations(mAudioManager);

        mService.notifyActiveDeviceChanged(mDevice);

        /* Prepare create broadcast */
        BluetoothLeAudioContentMetadata meta =
                new BluetoothLeAudioContentMetadata.Builder()
                        .setLanguage("ENG")
                        .setProgramInfo("Public broadcast info")
                        .build();

        BluetoothLeBroadcastSettings settings = buildBroadcastSettingsFromMetadata(meta, code, 1);
        mService.createBroadcast(settings);

        /* Successfully created audio session notification */
        create_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_AUDIO_SESSION_CREATED);
        create_event.valueBool1 = true;
        mService.messageFromNative(create_event);

        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(mBroadcastDevice), eq(null), any(BluetoothProfileConnectionInfo.class));

        /* Active group should become inactive */
        int activeGroup = mService.getActiveGroupId();
        assertThat(activeGroup).isEqualTo(LE_AUDIO_GROUP_ID_INVALID);

        /* Imitate group inactivity to cause create broadcast */
        create_event = new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        create_event.valueInt1 = groupId;
        create_event.valueInt2 = LeAudioStackEvent.GROUP_STATUS_INACTIVE;
        mService.messageFromNative(create_event);

        if (Flags.leaudioUseAudioRecordingListener()) {
            verify(mAudioManager, times(2))
                    .handleBluetoothActiveDeviceChanged(
                            eq(null), eq(mDevice), any(BluetoothProfileConnectionInfo.class));
        } else {
            /* Only one Unicast device should become active due to Sink monitor mode */
            verify(mAudioManager)
                    .handleBluetoothActiveDeviceChanged(
                            eq(null), eq(mDevice), any(BluetoothProfileConnectionInfo.class));
        }
        Mockito.clearInvocations(mAudioManager);
        List<BluetoothLeBroadcastSubgroupSettings> settingsList = settings.getSubgroupSettings();

        int[] expectedQualityArray =
                settingsList.stream().mapToInt(setting -> setting.getPreferredQuality()).toArray();
        byte[][] expectedDataArray =
                settingsList.stream()
                        .map(setting -> setting.getContentMetadata().getRawMetadata())
                        .toArray(byte[][]::new);

        verify(mLeAudioBroadcasterNativeInterface)
                .createBroadcast(
                        eq(true),
                        eq(TEST_BROADCAST_NAME),
                        eq(settings.getBroadcastCode()),
                        eq(settings.getPublicBroadcastMetadata().getRawMetadata()),
                        eq(expectedQualityArray),
                        eq(expectedDataArray));
        if (!Flags.leaudioUseAudioRecordingListener()) {
            verify(mLeAudioNativeInterface)
                    .setUnicastMonitorMode(eq(LeAudioStackEvent.DIRECTION_SINK), eq(true));
        }

        activeGroup = mService.getActiveGroupId();
        assertThat(activeGroup).isEqualTo(LE_AUDIO_GROUP_ID_INVALID);

        /* Check if broadcast is started automatically when created */
        create_event = new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_CREATED);
        create_event.valueInt1 = broadcastId;
        create_event.valueBool1 = true;
        mService.messageFromNative(create_event);

        /* Switch to active streaming */
        create_event = new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_STATE);
        create_event.device = mBroadcastDevice;
        create_event.valueInt1 = broadcastId;
        create_event.valueInt2 = LeAudioStackEvent.BROADCAST_STATE_STREAMING;
        mService.messageFromNative(create_event);
    }

    @Test
    public void testAudioModeDrivenBroadcastSwitch() {
        int groupId = 1;
        int broadcastId = 243;
        byte[] code = {0x00, 0x01, 0x00, 0x02};

        prepareHandoverStreamingBroadcast(groupId, broadcastId, code);

        /* Expect clear of Inband Ringtone Support when device is changing to inactive and there is
         * no unicast to broadcast fallback device set
         */
        verify(mTbsService, times(1)).clearInbandRingtoneSupport(eq(mDevice));

        /* Imitate setting device in call */
        mService.handleAudioModeChange(AudioManager.MODE_IN_CALL);

        assertThat(mService.mBroadcastIdDeactivatedForUnicastTransition.isPresent()).isTrue();

        /* Check if broadcast is paused by AudioMode handling */
        verify(mLeAudioBroadcasterNativeInterface).pauseBroadcast(eq(broadcastId));

        LeAudioStackEvent state_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_STATE);
        state_event.valueInt1 = broadcastId;
        state_event.valueInt2 = LeAudioStackEvent.BROADCAST_STATE_PAUSED;
        mService.messageFromNative(state_event);

        LeAudioStackEvent create_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        create_event.valueInt1 = groupId;
        create_event.valueInt2 = LeAudioStackEvent.GROUP_STATUS_ACTIVE;
        mService.messageFromNative(create_event);

        if (Flags.leaudioUseAudioRecordingListener()) {
            verify(mAudioManager, times(2))
                    .handleBluetoothActiveDeviceChanged(
                            eq(mDevice), eq(null), any(BluetoothProfileConnectionInfo.class));
        } else {
            /* Only one Unicast device should become inactive due to Sink monitor mode */
            verify(mAudioManager)
                    .handleBluetoothActiveDeviceChanged(
                            eq(mDevice), eq(null), any(BluetoothProfileConnectionInfo.class));
        }
        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(null), eq(mBroadcastDevice), any(BluetoothProfileConnectionInfo.class));

        /* Active group should become the one that was active before broadcasting */
        int activeGroup = mService.getActiveGroupId();
        assertThat(activeGroup).isEqualTo(groupId);

        /* Imitate group change request by Bluetooth Sink HAL suspend request */
        create_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_UNICAST_MONITOR_MODE_STATUS);
        create_event.valueInt1 = LeAudioStackEvent.DIRECTION_SINK;
        create_event.valueInt2 = LeAudioStackEvent.STATUS_LOCAL_STREAM_SUSPENDED;
        mService.messageFromNative(create_event);

        /* Device should not be inactivated if in IN_CALL audio mode */
        verify(mLeAudioNativeInterface).groupSetActive(eq(LE_AUDIO_GROUP_ID_INVALID));

        /* Imitate setting device not in call */
        mService.handleAudioModeChange(AudioManager.MODE_NORMAL);

        verify(mLeAudioNativeInterface, times(2)).groupSetActive(eq(LE_AUDIO_GROUP_ID_INVALID));

        /* Imitate group inactivity to cause start broadcast */
        create_event = new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        create_event.valueInt1 = groupId;
        create_event.valueInt2 = LeAudioStackEvent.GROUP_STATUS_INACTIVE;
        mService.messageFromNative(create_event);

        if (Flags.leaudioUseAudioRecordingListener()) {
            verify(mAudioManager, times(2))
                    .handleBluetoothActiveDeviceChanged(
                            eq(null), eq(mDevice), any(BluetoothProfileConnectionInfo.class));
        } else {
            /* Only one Unicast device should become active due to Sink monitor mode */
            verify(mAudioManager)
                    .handleBluetoothActiveDeviceChanged(
                            eq(null), eq(mDevice), any(BluetoothProfileConnectionInfo.class));
        }
        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(mBroadcastDevice), eq(null), any(BluetoothProfileConnectionInfo.class));

        if (Flags.leaudioBigDependsOnAudioState()) {
            /* Verify if broadcast triggers transition */
            assertThat(mService.mBroadcastIdDeactivatedForUnicastTransition.isPresent()).isFalse();
        }

        /* Verify if broadcast is auto-started on start */
        verify(mLeAudioBroadcasterNativeInterface, times(2)).startBroadcast(eq(broadcastId));
    }

    @Test
    public void testBroadcastResumeUnicastGroupChangeRequestDriven() {
        int groupId = 1;
        int broadcastId = 243;
        byte[] code = {0x00, 0x01, 0x00, 0x02};

        prepareHandoverStreamingBroadcast(groupId, broadcastId, code);

        /* Expect clear of Inband Ringtone Support when device is changing to inactive and there is
         * no unicast to broadcast fallback device set
         */
        verify(mTbsService, times(1)).clearInbandRingtoneSupport(eq(mDevice));

        verify(mLeAudioBroadcasterNativeInterface).startBroadcast(eq(broadcastId));

        /* Imitate group change request by Bluetooth Sink HAL resume request */
        LeAudioStackEvent create_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_UNICAST_MONITOR_MODE_STATUS);
        create_event.valueInt1 = LeAudioStackEvent.DIRECTION_SINK;
        create_event.valueInt2 = LeAudioStackEvent.STATUS_LOCAL_STREAM_REQUESTED;
        mService.messageFromNative(create_event);

        assertThat(mService.mBroadcastIdDeactivatedForUnicastTransition.isPresent()).isTrue();

        /* Check if broadcast is paused triggered by group change request */
        verify(mLeAudioBroadcasterNativeInterface).pauseBroadcast(eq(broadcastId));

        LeAudioStackEvent state_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_STATE);
        state_event.valueInt1 = broadcastId;
        state_event.valueInt2 = LeAudioStackEvent.BROADCAST_STATE_PAUSED;
        mService.messageFromNative(state_event);

        create_event = new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        create_event.valueInt1 = groupId;
        create_event.valueInt2 = LeAudioStackEvent.GROUP_STATUS_ACTIVE;
        mService.messageFromNative(create_event);

        if (Flags.leaudioUseAudioRecordingListener()) {
            verify(mAudioManager, times(2))
                    .handleBluetoothActiveDeviceChanged(
                            eq(mDevice), eq(null), any(BluetoothProfileConnectionInfo.class));
        } else {
            /* Only one Unicast device should become inactive due to Sink monitor mode */
            verify(mAudioManager)
                    .handleBluetoothActiveDeviceChanged(
                            eq(mDevice), eq(null), any(BluetoothProfileConnectionInfo.class));
        }
        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(null), eq(mBroadcastDevice), any(BluetoothProfileConnectionInfo.class));

        /* Active group should become the one that was active before broadcasting */
        int activeGroup = mService.getActiveGroupId();
        assertThat(activeGroup).isEqualTo(groupId);

        /* Imitate group change request by Bluetooth Sink HAL suspend request */
        create_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_UNICAST_MONITOR_MODE_STATUS);
        create_event.valueInt1 = LeAudioStackEvent.DIRECTION_SINK;
        create_event.valueInt2 = LeAudioStackEvent.STATUS_LOCAL_STREAM_SUSPENDED;
        mService.messageFromNative(create_event);

        verify(mLeAudioNativeInterface, times(2)).groupSetActive(eq(LE_AUDIO_GROUP_ID_INVALID));

        /* Imitate group inactivity to cause start broadcast */
        create_event = new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        create_event.valueInt1 = groupId;
        create_event.valueInt2 = LeAudioStackEvent.GROUP_STATUS_INACTIVE;
        mService.messageFromNative(create_event);

        if (Flags.leaudioUseAudioRecordingListener()) {
            verify(mAudioManager, times(2))
                    .handleBluetoothActiveDeviceChanged(
                            eq(null), eq(mDevice), any(BluetoothProfileConnectionInfo.class));
        } else {
            /* Only one Unicast device should become active due to Sink monitor mode */
            verify(mAudioManager)
                    .handleBluetoothActiveDeviceChanged(
                            eq(null), eq(mDevice), any(BluetoothProfileConnectionInfo.class));
        }
        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(mBroadcastDevice), eq(null), any(BluetoothProfileConnectionInfo.class));

        if (Flags.leaudioBigDependsOnAudioState()) {
            /* Verify if broadcast triggers transition */
            assertThat(mService.mBroadcastIdDeactivatedForUnicastTransition.isPresent()).isFalse();
        }

        verify(mLeAudioBroadcasterNativeInterface, times(2)).startBroadcast(eq(broadcastId));
    }

    @Test
    public void testAudioModeDrivenBroadcastSwitchDuringInternalPause() {
        mSetFlagsRule.enableFlags(Flags.FLAG_LEAUDIO_BIG_DEPENDS_ON_AUDIO_STATE);
        int groupId = 1;
        int broadcastId = 243;
        byte[] code = {0x00, 0x01, 0x00, 0x02};

        prepareHandoverStreamingBroadcast(groupId, broadcastId, code);

        /* Expect clear of Inband Ringtone Support when device is changing to inactive and there is
         * no unicast to broadcast fallback device set
         */
        verify(mTbsService, times(1)).clearInbandRingtoneSupport(eq(mDevice));

        /* Internal broadcast paused due to onAudioSuspend */
        LeAudioStackEvent state_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_STATE);
        state_event.valueInt1 = broadcastId;
        state_event.valueInt2 = LeAudioStackEvent.BROADCAST_STATE_PAUSED;
        mService.messageFromNative(state_event);

        /* Imitate setting device in call */
        mService.handleAudioModeChange(AudioManager.MODE_IN_CALL);

        assertThat(mService.mBroadcastIdDeactivatedForUnicastTransition.isPresent()).isTrue();

        /* Broadcast already paused, not call pause again by AudioMode handling */
        verify(mLeAudioBroadcasterNativeInterface, never()).pauseBroadcast(eq(broadcastId));

        LeAudioStackEvent create_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        create_event.valueInt1 = groupId;
        create_event.valueInt2 = LeAudioStackEvent.GROUP_STATUS_ACTIVE;
        mService.messageFromNative(create_event);

        if (Flags.leaudioUseAudioRecordingListener()) {
            verify(mAudioManager, times(2))
                    .handleBluetoothActiveDeviceChanged(
                            eq(mDevice), eq(null), any(BluetoothProfileConnectionInfo.class));
        } else {
            /* Only one Unicast device should become inactive due to Sink monitor mode */
            verify(mAudioManager)
                    .handleBluetoothActiveDeviceChanged(
                            eq(mDevice), eq(null), any(BluetoothProfileConnectionInfo.class));
        }
        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(null), eq(mBroadcastDevice), any(BluetoothProfileConnectionInfo.class));

        /* Active group should become the one that was active before broadcasting */
        int activeGroup = mService.getActiveGroupId();
        assertThat(activeGroup).isEqualTo(groupId);

        /* Imitate group change request by Bluetooth Sink HAL suspend request */
        create_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_UNICAST_MONITOR_MODE_STATUS);
        create_event.valueInt1 = LeAudioStackEvent.DIRECTION_SINK;
        create_event.valueInt2 = LeAudioStackEvent.STATUS_LOCAL_STREAM_SUSPENDED;
        mService.messageFromNative(create_event);

        /* Device should not be inactivated if in IN_CALL audio mode */
        verify(mLeAudioNativeInterface).groupSetActive(eq(LE_AUDIO_GROUP_ID_INVALID));

        /* Imitate setting device not in call */
        mService.handleAudioModeChange(AudioManager.MODE_NORMAL);

        verify(mLeAudioNativeInterface, times(2)).groupSetActive(eq(LE_AUDIO_GROUP_ID_INVALID));

        /* Imitate group inactivity to cause start broadcast */
        create_event = new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        create_event.valueInt1 = groupId;
        create_event.valueInt2 = LeAudioStackEvent.GROUP_STATUS_INACTIVE;
        mService.messageFromNative(create_event);

        if (Flags.leaudioUseAudioRecordingListener()) {
            verify(mAudioManager, times(2))
                    .handleBluetoothActiveDeviceChanged(
                            eq(null), eq(mDevice), any(BluetoothProfileConnectionInfo.class));
        } else {
            /* Only one Unicast device should become active due to Sink monitor mode */
            verify(mAudioManager)
                    .handleBluetoothActiveDeviceChanged(
                            eq(null), eq(mDevice), any(BluetoothProfileConnectionInfo.class));
        }
        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(mBroadcastDevice), eq(null), any(BluetoothProfileConnectionInfo.class));

        /* Verify if broadcast triggers transition */
        assertThat(mService.mBroadcastIdDeactivatedForUnicastTransition.isPresent()).isFalse();
    }

    @Test
    public void testBroadcastResumeUnicastGroupChangeRequestDrivenDuringInternalPause() {
        mSetFlagsRule.enableFlags(Flags.FLAG_LEAUDIO_BIG_DEPENDS_ON_AUDIO_STATE);
        int groupId = 1;
        int broadcastId = 243;
        byte[] code = {0x00, 0x01, 0x00, 0x02};

        prepareHandoverStreamingBroadcast(groupId, broadcastId, code);

        /* Expect clear of Inband Ringtone Support when device is changing to inactive and there is
         * no unicast to broadcast fallback device set
         */
        verify(mTbsService, times(1)).clearInbandRingtoneSupport(eq(mDevice));

        /* Internal broadcast paused due to onAudioSuspend */
        LeAudioStackEvent state_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_STATE);
        state_event.valueInt1 = broadcastId;
        state_event.valueInt2 = LeAudioStackEvent.BROADCAST_STATE_PAUSED;
        mService.messageFromNative(state_event);

        /* Imitate group change request by Bluetooth Sink HAL resume request */
        LeAudioStackEvent create_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_UNICAST_MONITOR_MODE_STATUS);
        create_event.valueInt1 = LeAudioStackEvent.DIRECTION_SINK;
        create_event.valueInt2 = LeAudioStackEvent.STATUS_LOCAL_STREAM_REQUESTED;
        mService.messageFromNative(create_event);

        assertThat(mService.mBroadcastIdDeactivatedForUnicastTransition.isPresent()).isTrue();

        /* Broadcast already paused, not call pause again by group change request */
        verify(mLeAudioBroadcasterNativeInterface, never()).pauseBroadcast(eq(broadcastId));

        create_event = new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        create_event.valueInt1 = groupId;
        create_event.valueInt2 = LeAudioStackEvent.GROUP_STATUS_ACTIVE;
        mService.messageFromNative(create_event);

        if (Flags.leaudioUseAudioRecordingListener()) {
            verify(mAudioManager, times(2))
                    .handleBluetoothActiveDeviceChanged(
                            eq(mDevice), eq(null), any(BluetoothProfileConnectionInfo.class));
        } else {
            /* Only one Unicast device should become inactive due to Sink monitor mode */
            verify(mAudioManager)
                    .handleBluetoothActiveDeviceChanged(
                            eq(mDevice), eq(null), any(BluetoothProfileConnectionInfo.class));
        }
        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(null), eq(mBroadcastDevice), any(BluetoothProfileConnectionInfo.class));

        /* Active group should become the one that was active before broadcasting */
        int activeGroup = mService.getActiveGroupId();
        assertThat(activeGroup).isEqualTo(groupId);

        /* Imitate group change request by Bluetooth Sink HAL suspend request */
        create_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_UNICAST_MONITOR_MODE_STATUS);
        create_event.valueInt1 = LeAudioStackEvent.DIRECTION_SINK;
        create_event.valueInt2 = LeAudioStackEvent.STATUS_LOCAL_STREAM_SUSPENDED;
        mService.messageFromNative(create_event);

        verify(mLeAudioNativeInterface, times(2)).groupSetActive(eq(LE_AUDIO_GROUP_ID_INVALID));

        /* Imitate group inactivity to cause start broadcast */
        create_event = new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        create_event.valueInt1 = groupId;
        create_event.valueInt2 = LeAudioStackEvent.GROUP_STATUS_INACTIVE;
        mService.messageFromNative(create_event);

        if (Flags.leaudioUseAudioRecordingListener()) {
            verify(mAudioManager, times(2))
                    .handleBluetoothActiveDeviceChanged(
                            eq(null), eq(mDevice), any(BluetoothProfileConnectionInfo.class));
        } else {
            /* Only one Unicast device should become active due to Sink monitor mode */
            verify(mAudioManager)
                    .handleBluetoothActiveDeviceChanged(
                            eq(null), eq(mDevice), any(BluetoothProfileConnectionInfo.class));
        }
        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(mBroadcastDevice), eq(null), any(BluetoothProfileConnectionInfo.class));
        /* Verify if broadcast triggers transition */
        assertThat(mService.mBroadcastIdDeactivatedForUnicastTransition.isPresent()).isFalse();
    }

    @Test
    public void testCacheAndResmueSuspendingSources() {
        mSetFlagsRule.enableFlags(Flags.FLAG_LEAUDIO_BIG_DEPENDS_ON_AUDIO_STATE);
        int groupId = 1;
        int broadcastId = 243;
        byte[] code = {0x00, 0x01, 0x00, 0x02};

        prepareHandoverStreamingBroadcast(groupId, broadcastId, code);

        /* Expect clear of Inband Ringtone Support when device is changing to inactive and there is
         * no unicast to broadcast fallback device set
         */
        verify(mTbsService, times(1)).clearInbandRingtoneSupport(eq(mDevice));

        /* Internal broadcast paused due to onAudioSuspend */
        LeAudioStackEvent state_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_STATE);
        state_event.valueInt1 = broadcastId;
        state_event.valueInt2 = LeAudioStackEvent.BROADCAST_STATE_PAUSED;
        mService.messageFromNative(state_event);

        verify(mBassClientService).cacheSuspendingSources(eq(broadcastId));

        /* Internal broadcast resumed due to onAudioResumed */
        state_event.valueInt2 = LeAudioStackEvent.BROADCAST_STATE_STREAMING;
        mService.messageFromNative(state_event);

        verify(mBassClientService).resumeReceiversSourceSynchronization();
    }

    @Test
    @DisableFlags(Flags.FLAG_LEAUDIO_BROADCAST_PRIMARY_GROUP_SELECTION)
    public void testUpdateFallbackInputDevice() {
        int groupId = 1;
        int groupId2 = 2;
        int broadcastId = 243;
        byte[] code = {0x00, 0x01, 0x00, 0x02};

        initializeNative();
        prepareConnectedUnicastDevice(groupId2, mDevice2);
        prepareHandoverStreamingBroadcast(groupId, broadcastId, code);

        /* group 1 is deactivated due to broadcast and group 2 is set by default as broadcast to
         * unicast fallback group (first add device)
         */
        verify(mTbsService, never()).clearInbandRingtoneSupport(eq(mDevice2));
        verify(mTbsService, times(1)).clearInbandRingtoneSupport(eq(mDevice));

        assertThat(mService.mUnicastGroupIdDeactivatedForBroadcastTransition).isEqualTo(groupId);

        reset(mAudioManager);

        assertThat(mService.setActiveDevice(mDevice2)).isTrue();

        if (!Flags.leaudioUseAudioRecordingListener()) {
            /* Update fallback active device (only input is active) */
            ArgumentCaptor<BluetoothProfileConnectionInfo> connectionInfoArgumentCaptor =
                    ArgumentCaptor.forClass(BluetoothProfileConnectionInfo.class);

            assertThat(mService.setActiveDevice(mDevice2)).isTrue();
            verify(mAudioManager)
                    .handleBluetoothActiveDeviceChanged(
                            eq(mDevice2), eq(mDevice), connectionInfoArgumentCaptor.capture());
            List<BluetoothProfileConnectionInfo> connInfos =
                    connectionInfoArgumentCaptor.getAllValues();
            assertThat(connInfos).hasSize(1);
            assertThat(connInfos.get(0).isLeOutput()).isFalse();
        }
        assertThat(mService.mUnicastGroupIdDeactivatedForBroadcastTransition).isEqualTo(groupId2);
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_API_MANAGE_PRIMARY_GROUP,
        Flags.FLAG_LEAUDIO_BROADCAST_PRIMARY_GROUP_SELECTION
    })
    public void testOnBroadcastToUnicastFallbackGroupChanged() throws RemoteException {
        int groupId1 = 1;
        int groupId2 = 2;
        int broadcastId = 243;
        byte[] code = {0x00, 0x01, 0x00, 0x02};
        List<BluetoothDevice> devices = new ArrayList<>();

        when(mDatabaseManager.getMostRecentlyConnectedDevices()).thenReturn(devices);

        synchronized (mService.mLeAudioCallbacks) {
            mService.mLeAudioCallbacks.register(mLeAudioCallbacks);
        }

        initializeNative();
        devices.add(mDevice2);
        prepareConnectedUnicastDevice(groupId2, mDevice2);
        devices.add(mDevice);
        prepareHandoverStreamingBroadcast(groupId1, broadcastId, code);

        /* group 1 is deactivated due to broadcast and group 2 is set by default as broadcast to
         * unicast fallback group (first add device)
         */
        verify(mTbsService, never()).clearInbandRingtoneSupport(eq(mDevice2));
        verify(mTbsService, times(1)).clearInbandRingtoneSupport(eq(mDevice));

        TestUtils.waitForLooperToFinishScheduledTask(Looper.getMainLooper());
        assertThat(mService.mUnicastGroupIdDeactivatedForBroadcastTransition).isEqualTo(groupId2);

            verify(mLeAudioCallbacks).onBroadcastToUnicastFallbackGroupChanged(groupId2);

        synchronized (mService.mLeAudioCallbacks) {
            mService.mLeAudioCallbacks.unregister(mLeAudioCallbacks);
        }
    }

    @Test
    public void testGetLocalBroadcastReceivers() throws RemoteException {
        int broadcastId = 243;
        byte[] code = {0x00, 0x01, 0x00, 0x02};

        synchronized (mService.mBroadcastCallbacks) {
            mService.mBroadcastCallbacks.register(mCallbacks);
        }

        BluetoothLeAudioContentMetadata meta =
                new BluetoothLeAudioContentMetadata.Builder()
                        .setLanguage("deu")
                        .setProgramInfo("Subgroup broadcast info")
                        .build();

        verifyBroadcastStarted(broadcastId, buildBroadcastSettingsFromMetadata(meta, code, 1));
        when(mBassClientService.getSyncedBroadcastSinks(broadcastId)).thenReturn(List.of(mDevice));
        assertThat(mService.getLocalBroadcastReceivers().size()).isEqualTo(1);
        assertThat(mService.getLocalBroadcastReceivers()).containsExactly(mDevice);

        verifyBroadcastStopped(broadcastId);
        assertThat(mService.getLocalBroadcastReceivers()).isEmpty();
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_PRIMARY_GROUP_SELECTION,
        Flags.FLAG_LEAUDIO_BROADCAST_API_MANAGE_PRIMARY_GROUP
    })
    public void testManageBroadcastToUnicastFallbackGroup() {
        int groupId = 1;
        int groupId2 = 2;
        int broadcastId = 243;
        byte[] code = {0x00, 0x01, 0x00, 0x02};
        List<BluetoothDevice> devices = new ArrayList<>();

        when(mDatabaseManager.getMostRecentlyConnectedDevices()).thenReturn(devices);

        initializeNative();
        devices.add(mDevice);
        prepareHandoverStreamingBroadcast(groupId, broadcastId, code);
        mService.deviceConnected(mDevice);
        devices.add(mDevice2);
        prepareConnectedUnicastDevice(groupId2, mDevice2);

        InOrder tbsOrder = inOrder(mTbsService);
        tbsOrder.verify(mTbsService, never()).clearInbandRingtoneSupport(eq(mDevice2));
        tbsOrder.verify(mTbsService, never()).clearInbandRingtoneSupport(eq(mDevice));

        assertThat(mService.mUnicastGroupIdDeactivatedForBroadcastTransition).isEqualTo(groupId);
        tbsOrder.verify(mTbsService, times(1)).setInbandRingtoneSupport(eq(mDevice));

        reset(mAudioManager);

        mService.setBroadcastToUnicastFallbackGroup(groupId2);
        tbsOrder.verify(mTbsService, times(1)).clearInbandRingtoneSupport(eq(mDevice));
        tbsOrder.verify(mTbsService, times(1)).setInbandRingtoneSupport(eq(mDevice2));

        /* Update fallback active device (only input is active) */
        ArgumentCaptor<BluetoothProfileConnectionInfo> connectionInfoArgumentCaptor =
                ArgumentCaptor.forClass(BluetoothProfileConnectionInfo.class);

        if (!Flags.leaudioUseAudioRecordingListener()) {
            verify(mAudioManager)
                    .handleBluetoothActiveDeviceChanged(
                            eq(mDevice2), eq(mDevice), connectionInfoArgumentCaptor.capture());
            List<BluetoothProfileConnectionInfo> connInfos =
                    connectionInfoArgumentCaptor.getAllValues();
            assertThat(connInfos.size()).isEqualTo(1);
            assertThat(connInfos.get(0).isLeOutput()).isFalse();
        }

        assertThat(mService.getBroadcastToUnicastFallbackGroup()).isEqualTo(groupId2);
    }

    private void disconnectDevice(BluetoothDevice device) {
        doReturn(true).when(mLeAudioNativeInterface).disconnectLeAudio(any(BluetoothDevice.class));
        assertThat(mService.disconnect(device)).isTrue();

        // Verify the connection state broadcast, and that we are in Connected state
        verifyConnectionStateIntent(device, STATE_DISCONNECTING, STATE_CONNECTED);
        assertThat(mService.getConnectionState(device)).isEqualTo(STATE_DISCONNECTING);

        LeAudioStackEvent create_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        create_event.device = device;
        create_event.valueInt1 = LeAudioStackEvent.CONNECTION_STATE_DISCONNECTED;
        mService.messageFromNative(create_event);

        verifyConnectionStateIntent(device, STATE_DISCONNECTED, STATE_DISCONNECTING);
        assertThat(mService.getConnectionState(device)).isEqualTo(STATE_DISCONNECTED);
        mService.deviceDisconnected(device, false);
        TestUtils.waitForLooperToFinishScheduledTask(Looper.getMainLooper());
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_PRIMARY_GROUP_SELECTION,
        Flags.FLAG_LEAUDIO_BROADCAST_API_MANAGE_PRIMARY_GROUP,
        Flags.FLAG_LEAUDIO_USE_AUDIO_RECORDING_LISTENER
    })
    public void testSetDefaultBroadcastToUnicastFallbackGroup() {
        int groupId = 1;
        int groupId2 = 2;
        List<BluetoothDevice> devices = new ArrayList<>();

        when(mDatabaseManager.getMostRecentlyConnectedDevices()).thenReturn(devices);

        /* If no connected devices - no fallback device */
        assertThat(mService.getBroadcastToUnicastFallbackGroup())
                .isEqualTo(LE_AUDIO_GROUP_ID_INVALID);

        initializeNative();
        devices.add(mDevice);
        prepareConnectedUnicastDevice(groupId, mDevice);
        mService.deviceConnected(mDevice);
        devices.add(mDevice2);
        prepareConnectedUnicastDevice(groupId2, mDevice2);

        LeAudioStackEvent stackEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        stackEvent.valueInt1 = groupId2;
        stackEvent.valueInt2 = LeAudioStackEvent.GROUP_STATUS_ACTIVE;
        mService.messageFromNative(stackEvent);

        /* First connected group become fallback group */
        assertThat(mService.getBroadcastToUnicastFallbackGroup()).isEqualTo(groupId);

        /* Force group as fallback 1 -> 2 */
        mService.setBroadcastToUnicastFallbackGroup(groupId2);
        assertThat(mService.getBroadcastToUnicastFallbackGroup()).isEqualTo(groupId2);

        /* Disconnected last device from fallback should trigger set default group 2 -> 1 */
        disconnectDevice(mDevice2);
        assertThat(mService.getBroadcastToUnicastFallbackGroup()).isEqualTo(groupId);
        stackEvent.valueInt1 = groupId;
        mService.messageFromNative(stackEvent);

        /* Disconnected last device from fallback should trigger set default group 1 -> -1 */
        disconnectDevice(mDevice);
        assertThat(mService.getBroadcastToUnicastFallbackGroup())
                .isEqualTo(LE_AUDIO_GROUP_ID_INVALID);
    }

    @Test
    @EnableFlags({
        Flags.FLAG_LEAUDIO_BROADCAST_API_MANAGE_PRIMARY_GROUP,
        Flags.FLAG_LEAUDIO_BROADCAST_PRIMARY_GROUP_SELECTION
    })
    public void testUpdateFallbackDeviceWhileSettingActiveDevice() throws RemoteException {
        int groupId = 1;
        int groupId2 = 2;
        int broadcastId = 243;
        byte[] code = {0x00, 0x01, 0x00, 0x02};
        List<BluetoothDevice> devices = new ArrayList<>();

        when(mDatabaseManager.getMostRecentlyConnectedDevices()).thenReturn(devices);

        synchronized (mService.mLeAudioCallbacks) {
            mService.mLeAudioCallbacks.register(mLeAudioCallbacks);
        }

        initializeNative();
        devices.add(mDevice2);
        prepareConnectedUnicastDevice(groupId2, mDevice2);
        devices.add(mDevice);
        prepareHandoverStreamingBroadcast(groupId, broadcastId, code);

        /* group 1 is deactivated due to broadcast and group 2 is set by default as broadcast to
         * unicast fallback group (first add device)
         */
        verify(mTbsService, never()).clearInbandRingtoneSupport(eq(mDevice2));
        verify(mTbsService, times(1)).clearInbandRingtoneSupport(eq(mDevice));

        /* Earliest connected group (2) become fallback device */
        assertThat(mService.mUnicastGroupIdDeactivatedForBroadcastTransition).isEqualTo(groupId2);
            verify(mLeAudioCallbacks).onBroadcastToUnicastFallbackGroupChanged(groupId2);

        Mockito.clearInvocations(mLeAudioCallbacks);
        reset(mAudioManager);

        /* Change active device while broadcasting - result in replacing fallback group 2->1 */
        assertThat(mService.setActiveDevice(mDevice)).isTrue();
        TestUtils.waitForLooperToFinishScheduledTask(Looper.getMainLooper());
        assertThat(mService.mUnicastGroupIdDeactivatedForBroadcastTransition).isEqualTo(groupId);
            verify(mLeAudioCallbacks).onBroadcastToUnicastFallbackGroupChanged(groupId);

        /* Verify that fallback device is not changed when there is no running broadcast */
        Mockito.clearInvocations(mLeAudioCallbacks);
        verifyBroadcastStopped(broadcastId);
        assertThat(mService.setActiveDevice(mDevice2)).isTrue();
        TestUtils.waitForLooperToFinishScheduledTask(Looper.getMainLooper());
        verify(mLeAudioCallbacks, never()).onBroadcastToUnicastFallbackGroupChanged(anyInt());
    }

    @Test
    public void registerUnregisterLeBroadcastCallback() {
        IBluetoothLeBroadcastCallback callback = Mockito.mock(IBluetoothLeBroadcastCallback.class);
        Binder binder = Mockito.mock(Binder.class);
        doReturn(binder).when(callback).asBinder();

        synchronized (mService.mBroadcastCallbacks) {
            assertThat(mService.mBroadcastCallbacks.beginBroadcast()).isEqualTo(0);
            mService.mBroadcastCallbacks.finishBroadcast();

            mService.registerLeBroadcastCallback(callback);
            assertThat(mService.mBroadcastCallbacks.beginBroadcast()).isEqualTo(1);
            mService.mBroadcastCallbacks.finishBroadcast();

            mService.unregisterLeBroadcastCallback(callback);
            assertThat(mService.mBroadcastCallbacks.beginBroadcast()).isEqualTo(0);
            mService.mBroadcastCallbacks.finishBroadcast();
        }
    }

    private static BluetoothLeBroadcastSettings buildBroadcastSettingsFromMetadata(
            BluetoothLeAudioContentMetadata contentMetadata,
            @Nullable byte[] broadcastCode,
            int numOfGroups) {
        BluetoothLeAudioContentMetadata meta =
                new BluetoothLeAudioContentMetadata.Builder()
                        .setProgramInfo("Public broadcast info")
                        .build();

        BluetoothLeBroadcastSubgroupSettings.Builder subgroupBuilder =
                new BluetoothLeBroadcastSubgroupSettings.Builder()
                        .setContentMetadata(contentMetadata)
                        .setPreferredQuality(BluetoothLeBroadcastSubgroupSettings.QUALITY_HIGH);

        BluetoothLeBroadcastSettings.Builder builder =
                new BluetoothLeBroadcastSettings.Builder()
                        .setPublicBroadcast(true)
                        .setBroadcastName(TEST_BROADCAST_NAME)
                        .setBroadcastCode(broadcastCode)
                        .setPublicBroadcastMetadata(meta);
        // builder expect at least one subgroup setting
        for (int i = 0; i < numOfGroups; i++) {
            // add subgroup settings with the same content
            builder.addSubgroupSettings(subgroupBuilder.build());
        }
        return builder.build();
    }

    private void injectGroupCurrentCodecConfigChanged(
            int groupId,
            BluetoothLeAudioCodecConfig inputCodecConfig,
            BluetoothLeAudioCodecConfig outputCodecConfig) {
        int eventType = LeAudioStackEvent.EVENT_TYPE_AUDIO_GROUP_CURRENT_CODEC_CONFIG_CHANGED;

        LeAudioStackEvent groupCodecConfigChangedEvent = new LeAudioStackEvent(eventType);
        groupCodecConfigChangedEvent.valueInt1 = groupId;
        groupCodecConfigChangedEvent.valueCodec1 = inputCodecConfig;
        groupCodecConfigChangedEvent.valueCodec2 = outputCodecConfig;
        mService.messageFromNative(groupCodecConfigChangedEvent);
    }

    private void injectGroupSelectableCodecConfigChanged(
            int groupId,
            List<BluetoothLeAudioCodecConfig> inputSelectableCodecConfig,
            List<BluetoothLeAudioCodecConfig> outputSelectableCodecConfig) {
        int eventType = LeAudioStackEvent.EVENT_TYPE_AUDIO_GROUP_SELECTABLE_CODEC_CONFIG_CHANGED;

        LeAudioStackEvent groupCodecConfigChangedEvent = new LeAudioStackEvent(eventType);
        groupCodecConfigChangedEvent.valueInt1 = groupId;
        groupCodecConfigChangedEvent.valueCodecList1 = inputSelectableCodecConfig;
        groupCodecConfigChangedEvent.valueCodecList2 = outputSelectableCodecConfig;
        mService.messageFromNative(groupCodecConfigChangedEvent);
    }

    private void verifyConnectionStateIntent(BluetoothDevice device, int newState, int prevState) {
        verifyIntentSent(
                hasAction(BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
                hasExtra(EXTRA_STATE, newState),
                hasExtra(EXTRA_PREVIOUS_STATE, prevState));

        if (newState == STATE_CONNECTED) {
            // ActiveDeviceManager calls deviceConnected when connected.
            mService.deviceConnected(device);
        } else if (newState == STATE_DISCONNECTED) {
            // ActiveDeviceManager calls deviceDisconnected when connected.
            mService.deviceDisconnected(device, false);
        }
    }

    @SafeVarargs
    private void verifyIntentSent(Matcher<Intent>... matchers) {
        mInOrder.verify(mAdapterService, timeout(1000))
                .sendBroadcastAsUser(
                        MockitoHamcrest.argThat(AllOf.allOf(matchers)), any(), any(), any());
    }
}
