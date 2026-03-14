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

import static com.android.bluetooth.TestUtils.getRealDevice;
import static com.android.bluetooth.TestUtils.getTestDevice;
import static com.android.bluetooth.TestUtils.mockGetSystemService;
import static com.android.bluetooth.le_audio.LeAudioConstants.INVALID_BROADCAST_ID;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.ActivityManager;
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
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.IBluetoothLeAudioCallback;
import android.bluetooth.IBluetoothLeBroadcastCallback;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.BluetoothProfileConnectionInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.annotation.Nullable;
import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.bluetooth.TestLooper;
import com.android.bluetooth.Util;
import com.android.bluetooth.bass_client.BassClientService;
import com.android.bluetooth.btservice.ActiveDeviceManager;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.Config;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.le_scan.ScanController;
import com.android.bluetooth.metrics.MetricsLogger;
import com.android.bluetooth.storage.BluetoothStorageManager;
import com.android.bluetooth.tbs.TbsService;
import com.android.bluetooth.vc.VolumeControlService;
import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.tests.bluetooth.FlagsWrapper;
import com.android.tests.bluetooth.StaticMockitoRule;

import org.hamcrest.Matcher;
import org.hamcrest.core.AllOf;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.hamcrest.MockitoHamcrest;

import platform.test.runner.parameterized.ParameterizedAndroidJunit4;
import platform.test.runner.parameterized.Parameters;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Test cases for {@link LeAudioBroadcastService}. */
@MediumTest
@RunWith(ParameterizedAndroidJunit4.class)
public class LeAudioBroadcastServiceTest {
    @Rule public final SetFlagsRule mSetFlagsRule;
    @Rule public final StaticMockitoRule mMockitoRule = new StaticMockitoRule(Config.class);

    @Mock private ActiveDeviceManager mActiveDeviceManager;
    @Mock private ScanController mScanController;
    @Mock private AdapterService mAdapterService;
    @Mock private BluetoothStorageManager mStorage;
    @Mock private AudioManager mAudioManager;
    @Mock private LeAudioBroadcasterNativeInterface mLeAudioBroadcasterNativeInterface;
    @Mock private LeAudioNativeInterface mLeAudioNativeInterface;
    @Mock private LeAudioTmapGattServer mTmapGattServer;
    @Mock private BassClientService mBassClientService;
    @Mock private VolumeControlService mVolumeControlService;
    @Mock private TbsService mTbsService;
    @Mock private MetricsLogger mMetricsLogger;
    @Mock private IBluetoothLeBroadcastCallback mCallbacks;
    @Mock private IBluetoothLeAudioCallback mLeAudioCallbacks;
    @Mock private IBinder mBinder;
    @Mock private ActivityManager mActivityManager;
    @Mock private PackageManager mPackageManager;

    @Spy private LeAudioObjectsFactory mObjectsFactory = LeAudioObjectsFactory.getInstance();

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

    private final BluetoothDevice mDevice1 = getTestDevice(0);
    private final BluetoothDevice mDevice2 = getTestDevice(1);
    private final BluetoothDevice mBroadcastDevice = getTestDevice("FF:FF:FF:FF:FF:FF");

    private LeAudioService mService;
    private InOrder mInOrder;
    private TestLooper mLooper;

    @Parameters(name = "{0}")
    public static List<FlagsWrapper> getParams() {
        return FlagsWrapper.progressionOf(Flags.FLAG_LEAUDIO_FALLBACK_GROUP_SELECTION);
    }

    public LeAudioBroadcastServiceTest(FlagsWrapper flags) {
        mSetFlagsRule = new SetFlagsRule(flags.getFlags());
    }

    @Before
    public void setUp() throws Exception {
        final var context = InstrumentationRegistry.getInstrumentation().getContext();

        mInOrder = inOrder(mAdapterService);

        doAnswer(invocation -> getLeastRecentlyConnectedDeviceInList(invocation.getArgument(0)))
                .when(mStorage)
                .getLeastRecentlyConnectedDeviceInList(any());
        doReturn(mAdapterService).when(mAdapterService).getApplicationContext();
        doReturn(mAdapterService).when(mAdapterService).createContextAsUser(any(), anyInt());
        doReturn(context.getContentResolver()).when(mAdapterService).getContentResolver();

        doReturn(mBinder).when(mCallbacks).asBinder();
        doReturn(mBinder).when(mLeAudioCallbacks).asBinder();

        // Use spied objects factory
        LeAudioObjectsFactory.setInstanceForTesting(mObjectsFactory);
        doReturn(mTmapGattServer).when(mObjectsFactory).getTmapGattServer(any());

        doReturn(true).when(mAdapterService).isLeAudioBroadcastSourceSupported();

        ExtendedMockito.doReturn(true)
                .when(() -> Config.isProfileSupported(BluetoothProfile.LE_AUDIO_BROADCAST));
        ExtendedMockito.doReturn(true)
                .when(() -> Config.isProfileSupported(BluetoothProfile.LE_AUDIO));

        doReturn(Optional.of(mTbsService)).when(mAdapterService).getTbsService();

        MetricsLogger.setInstanceForTesting(mMetricsLogger);

        mockGetSystemService(mAdapterService, AudioManager.class, mAudioManager);

        mLooper = new TestLooper();

        mService =
                new LeAudioService(
                        mAdapterService,
                        mStorage,
                        mLeAudioNativeInterface,
                        mLeAudioBroadcasterNativeInterface,
                        mActiveDeviceManager,
                        mScanController,
                        mLooper.getLooper(),
                        mActivityManager,
                        mPackageManager);
        mService.setAvailable(true);

        doReturn(Optional.of(mBassClientService)).when(mAdapterService).getBassClientService();
        doReturn(Optional.of(mVolumeControlService))
                .when(mAdapterService)
                .getVolumeControlService();

        // Set up the State Changed receiver
        doReturn(mBroadcastDevice)
                .when(mAdapterService)
                .getDeviceFromByte(Util.getBytesFromAddress("FF:FF:FF:FF:FF:FF"));
    }

    @After
    public void tearDown() throws Exception {
        mService.cleanup();
        MetricsLogger.setInstanceForTesting(null);
    }

    private static BluetoothDevice getLeastRecentlyConnectedDeviceInList(
            List<BluetoothDevice> devices) {
        if (devices.isEmpty()) {
            return null;
        }
        return devices.get(0);
    }

    void startBroadcastAndVerify(int broadcastId, BluetoothLeBroadcastSettings settings)
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
        LeAudioStackEvent leAudioStackEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_AUDIO_SESSION_CREATED);
        leAudioStackEvent.valueBool1 = true;
        mService.messageFromNative(leAudioStackEvent);
        mLooper.dispatchAll();

        leAudioStackEvent = new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_CREATED);
        leAudioStackEvent.valueInt1 = broadcastId;
        leAudioStackEvent.valueBool1 = true;
        mService.messageFromNative(leAudioStackEvent);
        mLooper.dispatchAll();

        verify(mLeAudioBroadcasterNativeInterface).startBroadcast(eq(broadcastId));

        // Notify initial paused state
        LeAudioStackEvent state_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_STATE);
        state_event.valueInt1 = broadcastId;
        state_event.valueInt2 = LeAudioStackEvent.BROADCAST_STATE_PAUSED;
        mService.messageFromNative(state_event);
        mLooper.dispatchAll();

        // Switch to active streaming
        state_event = new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_STATE);
        state_event.valueInt1 = broadcastId;
        state_event.valueInt2 = LeAudioStackEvent.BROADCAST_STATE_STREAMING;
        mService.messageFromNative(state_event);
        mLooper.dispatchAll();

        // Check if metadata is requested when the broadcast starts to stream
        verify(mLeAudioBroadcasterNativeInterface).getBroadcastMetadata(eq(broadcastId));
        verify(mCallbacks, never()).onBroadcastStartFailed(anyInt());
        verify(mCallbacks)
                .onBroadcastStarted(eq(BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST), anyInt());
    }

    void stopBroadcastAndVerify(int broadcastId) throws RemoteException {
        Mockito.clearInvocations(mMetricsLogger);

        mService.stopBroadcast(broadcastId);
        verify(mLeAudioBroadcasterNativeInterface).stopBroadcast(eq(broadcastId));

        LeAudioStackEvent state_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_STATE);
        state_event.valueInt1 = broadcastId;
        state_event.valueInt2 = LeAudioStackEvent.BROADCAST_STATE_STOPPED;
        mService.messageFromNative(state_event);
        mLooper.dispatchAll();

        // Verify if broadcast is auto-destroyed on stop
        verify(mLeAudioBroadcasterNativeInterface).destroyBroadcast(eq(broadcastId));

        state_event = new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_DESTROYED);
        state_event.valueInt1 = broadcastId;
        mService.messageFromNative(state_event);
        mLooper.dispatchAll();

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

        startBroadcastAndVerify(broadcastId, buildBroadcastSettingsFromMetadata(meta, code, 1));
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

        startBroadcastAndVerify(broadcastId, buildBroadcastSettingsFromMetadata(meta, code, 3));
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
        mLooper.dispatchAll();

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

        mLooper.moveTimeForward(LeAudioService.CREATE_BROADCAST_TIMEOUT_MS);
        mLooper.dispatchAll();
        verify(mCallbacks).onBroadcastStartFailed(eq(BluetoothStatusCodes.ERROR_TIMEOUT));

        // Try again
        mService.createBroadcast(settings);
        mLooper.moveTimeForward(LeAudioService.CREATE_BROADCAST_TIMEOUT_MS);
        mLooper.dispatchAll();
        verify(mCallbacks, times(2)).onBroadcastStartFailed(eq(BluetoothStatusCodes.ERROR_TIMEOUT));
    }

    @Test
    public void testCreateBroadcast_noAudioQualityUpdate() {
        byte[] code = {0x00, 0x01, 0x00, 0x02};
        int groupId = 1;

        initializeNative();
        prepareConnectedUnicastDevice(groupId, mDevice1);

        synchronized (mService.mBroadcastCallbacks) {
            mService.mBroadcastCallbacks.register(mCallbacks);
        }

        BluetoothLeAudioContentMetadata meta =
                new BluetoothLeAudioContentMetadata.Builder().build();
        BluetoothLeBroadcastSettings settings = buildBroadcastSettingsFromMetadata(meta, code, 1);

        doReturn(List.of(mDevice1)).when(mBassClientService).getConnectedDevices();
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
        prepareConnectedUnicastDevice(groupId, mDevice1);

        synchronized (mService.mBroadcastCallbacks) {
            mService.mBroadcastCallbacks.register(mCallbacks);
        }

        BluetoothLeAudioContentMetadata meta =
                new BluetoothLeAudioContentMetadata.Builder().build();
        BluetoothLeBroadcastSettings settings = buildBroadcastSettingsFromMetadata(meta, code, 1);

        doReturn(List.of(mDevice1)).when(mBassClientService).getConnectedDevices();
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

        startBroadcastAndVerify(broadcastId, buildBroadcastSettingsFromMetadata(meta, code, 1));
        stopBroadcastAndVerify(broadcastId);
    }

    @Test
    public void testBroadcastInvalidBroadcastIdRequest() throws RemoteException {
        int broadcastId = 243;

        synchronized (mService.mBroadcastCallbacks) {
            mService.mBroadcastCallbacks.register(mCallbacks);
        }

        // Stop non-existing broadcast
        mService.stopBroadcast(broadcastId);
        mLooper.dispatchAll();

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
        mLooper.dispatchAll();

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

    private static BluetoothLeBroadcastMetadata createBroadcastMetadata() {
        BluetoothDevice testDevice =
                getRealDevice(TEST_MAC_ADDRESS, BluetoothDevice.ADDRESS_TYPE_RANDOM);

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
        mLooper.dispatchAll();

        // Inject metadata stack event and verify if getter API works as expected
        LeAudioStackEvent state_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_METADATA_CHANGED);
        state_event.valueInt1 = broadcastId;
        state_event.broadcastMetadata = createBroadcastMetadata();
        mService.messageFromNative(state_event);
        mLooper.dispatchAll();

        assertThat(mService.getAllBroadcastMetadata())
                .containsExactly(state_event.broadcastMetadata);

        assertThat(mService.getBroadcastMetadata(broadcastId))
                .isEqualTo(state_event.broadcastMetadata);
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
        mLooper.dispatchAll();

        // Inject metadata stack event and verify if getter API works as expected
        LeAudioStackEvent state_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_METADATA_CHANGED);
        state_event.valueInt1 = broadcastId;
        state_event.broadcastMetadata = createBroadcastMetadata();
        mService.messageFromNative(state_event);
        mLooper.dispatchAll();

        // Verify if broadcast is active
        assertThat(mService.isBroadcastActive()).isTrue();

        mService.stopBroadcast(broadcastId);
        verify(mLeAudioBroadcasterNativeInterface).stopBroadcast(eq(broadcastId));

        state_event = new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_STATE);
        state_event.valueInt1 = broadcastId;
        state_event.valueInt2 = LeAudioStackEvent.BROADCAST_STATE_STOPPED;
        mService.messageFromNative(state_event);
        mLooper.dispatchAll();

        verify(mLeAudioBroadcasterNativeInterface).destroyBroadcast(eq(broadcastId));

        state_event = new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_DESTROYED);
        state_event.valueInt1 = broadcastId;
        mService.messageFromNative(state_event);
        mLooper.dispatchAll();

        // Verify if broadcast is not active
        assertThat(mService.isBroadcastActive()).isFalse();
    }

    private void initializeNative() {
        LeAudioStackEvent stackEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_NATIVE_INITIALIZED);
        mService.messageFromNative(stackEvent);
        mLooper.dispatchAll();
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
        doReturn(CONNECTION_POLICY_ALLOWED)
                .when(mAdapterService)
                .getProfileConnectionPolicy(device, BluetoothProfile.LE_AUDIO);
        doReturn(new ParcelUuid[] {BluetoothUuid.LE_AUDIO})
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
        assertThat(mService.connect(device)).isTrue();
        mLooper.dispatchAll();

        // Verify the connection state broadcast, and that we are in Connected state
        verifyConnectionStateIntent(device, STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(device)).isEqualTo(STATE_CONNECTING);

        LeAudioStackEvent create_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        create_event.device = device;
        create_event.valueInt1 = LeAudioStackEvent.CONNECTION_STATE_CONNECTED;
        mService.messageFromNative(create_event);
        mLooper.dispatchAll();

        verifyConnectionStateIntent(device, STATE_CONNECTED, STATE_CONNECTING);
        assertThat(mService.getConnectionState(device)).isEqualTo(STATE_CONNECTED);

        create_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_NODE_STATUS_CHANGED);
        create_event.device = device;
        create_event.valueInt1 = groupId;
        create_event.valueInt2 = LeAudioStackEvent.GROUP_NODE_ADDED;
        mService.messageFromNative(create_event);
        mLooper.dispatchAll();

        create_event = new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_AUDIO_CONF_CHANGED);
        create_event.device = device;
        create_event.valueInt1 = direction;
        create_event.valueInt2 = groupId;
        create_event.valueInt3 = snkAudioLocation;
        create_event.valueInt4 = srcAudioLocation;
        create_event.valueInt5 = availableContexts;
        mService.messageFromNative(create_event);
        mLooper.dispatchAll();

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

        initializeNative();
        prepareConnectedUnicastDevice(groupId, mDevice1);

        LeAudioStackEvent stackEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        stackEvent.valueInt1 = groupId;
        stackEvent.valueInt2 = LeAudioStackEvent.GROUP_STATUS_ACTIVE;
        mService.messageFromNative(stackEvent);
        mLooper.dispatchAll();

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
        mLooper.dispatchAll();

        /* Check if broadcast is started automatically when created */
        stackEvent = new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_CREATED);
        stackEvent.valueInt1 = broadcastId;
        stackEvent.valueBool1 = true;
        mService.messageFromNative(stackEvent);
        mLooper.dispatchAll();

        /* Active group should become inactive */
        int activeGroup = mService.getActiveGroupId();
        assertThat(activeGroup).isEqualTo(LE_AUDIO_GROUP_ID_INVALID);

        /* Imitate group inactivity to cause create broadcast */
        stackEvent = new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        stackEvent.valueInt1 = groupId;
        stackEvent.valueInt2 = LeAudioStackEvent.GROUP_STATUS_INACTIVE;
        mService.messageFromNative(stackEvent);
        mLooper.dispatchAll();

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
        mLooper.dispatchAll();

        startBroadcastAndVerify(broadcastId, settings);
        Mockito.clearInvocations(mCallbacks);

        // verify creating another broadcast will fail
        mService.createBroadcast(settings);
        mLooper.dispatchAll();

        verify(mCallbacks, never()).onBroadcastStarted(anyInt(), anyInt());
        verify(mCallbacks)
                .onBroadcastStartFailed(eq(BluetoothStatusCodes.ERROR_LOCAL_NOT_ENOUGH_RESOURCES));
    }

    private void prepareHandoverStreamingBroadcast(int groupId, int broadcastId, byte[] code) {
        synchronized (mService.mBroadcastCallbacks) {
            mService.mBroadcastCallbacks.register(mCallbacks);
        }

        initializeNative();
        prepareConnectedUnicastDevice(groupId, mDevice1);

        LeAudioStackEvent create_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        create_event.valueInt1 = groupId;
        create_event.valueInt2 = LeAudioStackEvent.GROUP_STATUS_ACTIVE;
        mService.messageFromNative(create_event);
        mLooper.dispatchAll();

        verify(mTbsService).setInbandRingtoneSupport(eq(mDevice1));

        /* Verify Unicast input and output devices changed from null to mDevice1 */
        verify(mAudioManager, times(2))
                .handleBluetoothActiveDeviceChanged(
                        eq(mDevice1), eq(null), any(BluetoothProfileConnectionInfo.class));
        Mockito.clearInvocations(mAudioManager);

        mService.notifyActiveDeviceChanged(mDevice1);

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
        mLooper.dispatchAll();

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
        mLooper.dispatchAll();

        verify(mAudioManager, times(2))
                .handleBluetoothActiveDeviceChanged(
                        eq(null), eq(mDevice1), any(BluetoothProfileConnectionInfo.class));

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

        activeGroup = mService.getActiveGroupId();
        assertThat(activeGroup).isEqualTo(LE_AUDIO_GROUP_ID_INVALID);

        /* Check if broadcast is started automatically when created */
        create_event = new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_CREATED);
        create_event.valueInt1 = broadcastId;
        create_event.valueBool1 = true;
        mService.messageFromNative(create_event);
        mLooper.dispatchAll();

        /* Switch to active streaming */
        create_event = new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_STATE);
        create_event.device = mBroadcastDevice;
        create_event.valueInt1 = broadcastId;
        create_event.valueInt2 = LeAudioStackEvent.BROADCAST_STATE_STREAMING;
        mService.messageFromNative(create_event);
        mLooper.dispatchAll();
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_FALLBACK_GROUP_SELECTION)
    public void testAudioModeDrivenBroadcastSwitch() {
        int groupId = 1;
        int broadcastId = 243;
        byte[] code = {0x00, 0x01, 0x00, 0x02};

        prepareHandoverStreamingBroadcast(groupId, broadcastId, code);

        /* Expect clear of Inband Ringtone Support when device is changing to inactive and there is
         * no unicast to broadcast fallback device set
         */
        verify(mTbsService, times(1)).clearInbandRingtoneSupport(eq(mDevice1));

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
        mLooper.dispatchAll();

        LeAudioStackEvent create_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        create_event.valueInt1 = groupId;
        create_event.valueInt2 = LeAudioStackEvent.GROUP_STATUS_ACTIVE;
        mService.messageFromNative(create_event);
        mLooper.dispatchAll();

        verify(mAudioManager, times(2))
                .handleBluetoothActiveDeviceChanged(
                        eq(mDevice1), eq(null), any(BluetoothProfileConnectionInfo.class));
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
        mLooper.dispatchAll();

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
        mLooper.dispatchAll();

        verify(mAudioManager, times(2))
                .handleBluetoothActiveDeviceChanged(
                        eq(null), eq(mDevice1), any(BluetoothProfileConnectionInfo.class));
        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(mBroadcastDevice), eq(null), any(BluetoothProfileConnectionInfo.class));

        /* Verify if broadcast triggers transition */
        assertThat(mService.mBroadcastIdDeactivatedForUnicastTransition.isPresent()).isFalse();

        /* Verify if broadcast is auto-started on start */
        verify(mLeAudioBroadcasterNativeInterface, times(2)).startBroadcast(eq(broadcastId));
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_FALLBACK_GROUP_SELECTION)
    public void testBroadcastResumeUnicastGroupChangeRequestDriven() {
        int groupId = 1;
        int broadcastId = 243;
        byte[] code = {0x00, 0x01, 0x00, 0x02};

        prepareHandoverStreamingBroadcast(groupId, broadcastId, code);

        /* Expect clear of Inband Ringtone Support when device is changing to inactive and there is
         * no unicast to broadcast fallback device set
         */
        verify(mTbsService, times(1)).clearInbandRingtoneSupport(eq(mDevice1));

        verify(mLeAudioBroadcasterNativeInterface).startBroadcast(eq(broadcastId));

        /* Imitate group change request by Bluetooth Sink HAL resume request */
        LeAudioStackEvent create_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_UNICAST_MONITOR_MODE_STATUS);
        create_event.valueInt1 = LeAudioStackEvent.DIRECTION_SINK;
        create_event.valueInt2 = LeAudioStackEvent.STATUS_LOCAL_STREAM_REQUESTED;
        mService.messageFromNative(create_event);
        mLooper.dispatchAll();

        assertThat(mService.mBroadcastIdDeactivatedForUnicastTransition.isPresent()).isTrue();

        /* Check if broadcast is paused triggered by group change request */
        verify(mLeAudioBroadcasterNativeInterface).pauseBroadcast(eq(broadcastId));

        LeAudioStackEvent state_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_STATE);
        state_event.valueInt1 = broadcastId;
        state_event.valueInt2 = LeAudioStackEvent.BROADCAST_STATE_PAUSED;
        mService.messageFromNative(state_event);
        mLooper.dispatchAll();

        create_event = new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        create_event.valueInt1 = groupId;
        create_event.valueInt2 = LeAudioStackEvent.GROUP_STATUS_ACTIVE;
        mService.messageFromNative(create_event);
        mLooper.dispatchAll();

        verify(mAudioManager, times(2))
                .handleBluetoothActiveDeviceChanged(
                        eq(mDevice1), eq(null), any(BluetoothProfileConnectionInfo.class));
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
        mLooper.dispatchAll();

        verify(mLeAudioNativeInterface, times(2)).groupSetActive(eq(LE_AUDIO_GROUP_ID_INVALID));

        /* Imitate group inactivity to cause start broadcast */
        create_event = new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        create_event.valueInt1 = groupId;
        create_event.valueInt2 = LeAudioStackEvent.GROUP_STATUS_INACTIVE;
        mService.messageFromNative(create_event);
        mLooper.dispatchAll();

        verify(mAudioManager, times(2))
                .handleBluetoothActiveDeviceChanged(
                        eq(null), eq(mDevice1), any(BluetoothProfileConnectionInfo.class));
        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(mBroadcastDevice), eq(null), any(BluetoothProfileConnectionInfo.class));

        /* Verify if broadcast triggers transition */
        assertThat(mService.mBroadcastIdDeactivatedForUnicastTransition.isPresent()).isFalse();

        verify(mLeAudioBroadcasterNativeInterface, times(2)).startBroadcast(eq(broadcastId));
    }

    @Test
    public void testAudioModeDrivenBroadcastSwitchDuringInternalPause() {
        int groupId = 1;
        int broadcastId = 243;
        byte[] code = {0x00, 0x01, 0x00, 0x02};
        Set<BluetoothDevice> broadcastReceivers = new HashSet<>();

        prepareHandoverStreamingBroadcast(groupId, broadcastId, code);

        if (Flags.leaudioFallbackGroupSelection()) {
            broadcastReceivers.add(mDevice1);
            mService.selectDefaultBroadcastToUnicastFallbackGroup(broadcastReceivers);

            /* Expect clear of inband rington support after broadcast is create and before
             * fallback group would be set
             */
            verify(mTbsService, times(1)).clearInbandRingtoneSupport(eq(mDevice1));
        } else {
            verify(mTbsService, never()).clearInbandRingtoneSupport(eq(mDevice1));
        }

        /* Internal broadcast paused due to onAudioSuspend */
        LeAudioStackEvent state_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_STATE);
        state_event.valueInt1 = broadcastId;
        state_event.valueInt2 = LeAudioStackEvent.BROADCAST_STATE_PAUSED;
        mService.messageFromNative(state_event);
        mLooper.dispatchAll();

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
        mLooper.dispatchAll();

        verify(mAudioManager, times(2))
                .handleBluetoothActiveDeviceChanged(
                        eq(mDevice1), eq(null), any(BluetoothProfileConnectionInfo.class));
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
        mLooper.dispatchAll();

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
        mLooper.dispatchAll();

        verify(mAudioManager, times(2))
                .handleBluetoothActiveDeviceChanged(
                        eq(null), eq(mDevice1), any(BluetoothProfileConnectionInfo.class));
        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(mBroadcastDevice), eq(null), any(BluetoothProfileConnectionInfo.class));

        /* Verify if broadcast triggers transition */
        assertThat(mService.mBroadcastIdDeactivatedForUnicastTransition.isPresent()).isFalse();
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_FALLBACK_GROUP_SELECTION)
    public void testBroadcastResumeUnicastGroupChangeRequestDrivenDuringInternalPause() {
        int groupId = 1;
        int broadcastId = 243;
        byte[] code = {0x00, 0x01, 0x00, 0x02};

        prepareHandoverStreamingBroadcast(groupId, broadcastId, code);

        /* Expect clear of Inband Ringtone Support when device is changing to inactive and there is
         * no unicast to broadcast fallback device set
         */
        verify(mTbsService, times(1)).clearInbandRingtoneSupport(eq(mDevice1));

        /* Internal broadcast paused due to onAudioSuspend */
        LeAudioStackEvent state_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_STATE);
        state_event.valueInt1 = broadcastId;
        state_event.valueInt2 = LeAudioStackEvent.BROADCAST_STATE_PAUSED;
        mService.messageFromNative(state_event);
        mLooper.dispatchAll();

        /* Imitate group change request by Bluetooth Sink HAL resume request */
        LeAudioStackEvent create_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_UNICAST_MONITOR_MODE_STATUS);
        create_event.valueInt1 = LeAudioStackEvent.DIRECTION_SINK;
        create_event.valueInt2 = LeAudioStackEvent.STATUS_LOCAL_STREAM_REQUESTED;
        mService.messageFromNative(create_event);
        mLooper.dispatchAll();

        assertThat(mService.mBroadcastIdDeactivatedForUnicastTransition.isPresent()).isTrue();

        /* Broadcast already paused, not call pause again by group change request */
        verify(mLeAudioBroadcasterNativeInterface, never()).pauseBroadcast(eq(broadcastId));

        create_event = new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        create_event.valueInt1 = groupId;
        create_event.valueInt2 = LeAudioStackEvent.GROUP_STATUS_ACTIVE;
        mService.messageFromNative(create_event);
        mLooper.dispatchAll();

        verify(mAudioManager, times(2))
                .handleBluetoothActiveDeviceChanged(
                        eq(mDevice1), eq(null), any(BluetoothProfileConnectionInfo.class));
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
        mLooper.dispatchAll();

        verify(mLeAudioNativeInterface, times(2)).groupSetActive(eq(LE_AUDIO_GROUP_ID_INVALID));

        /* Imitate group inactivity to cause start broadcast */
        create_event = new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        create_event.valueInt1 = groupId;
        create_event.valueInt2 = LeAudioStackEvent.GROUP_STATUS_INACTIVE;
        mService.messageFromNative(create_event);
        mLooper.dispatchAll();

        verify(mAudioManager, times(2))
                .handleBluetoothActiveDeviceChanged(
                        eq(null), eq(mDevice1), any(BluetoothProfileConnectionInfo.class));
        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(mBroadcastDevice), eq(null), any(BluetoothProfileConnectionInfo.class));
        /* Verify if broadcast triggers transition */
        assertThat(mService.mBroadcastIdDeactivatedForUnicastTransition.isPresent()).isFalse();
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_FALLBACK_GROUP_SELECTION)
    public void testCacheAndResumeSuspendingSources() {
        int groupId = 1;
        int broadcastId = 243;
        byte[] code = {0x00, 0x01, 0x00, 0x02};

        prepareHandoverStreamingBroadcast(groupId, broadcastId, code);

        /* Expect clear of Inband Ringtone Support when device is changing to inactive and there is
         * no unicast to broadcast fallback device set
         */
        verify(mTbsService, times(1)).clearInbandRingtoneSupport(eq(mDevice1));

        /* Internal broadcast paused due to onAudioSuspend */
        LeAudioStackEvent state_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_STATE);
        state_event.valueInt1 = broadcastId;
        state_event.valueInt2 = LeAudioStackEvent.BROADCAST_STATE_PAUSED;
        mService.messageFromNative(state_event);
        mLooper.dispatchAll();

        verify(mBassClientService).cacheSuspendingSources(eq(broadcastId));

        /* Internal broadcast resumed due to onAudioResumed */
        state_event.valueInt2 = LeAudioStackEvent.BROADCAST_STATE_STREAMING;
        mService.messageFromNative(state_event);
        mLooper.dispatchAll();

        verify(mBassClientService).resumeReceiversSourceSynchronization();
    }

    @Test
    public void testOnBroadcastToUnicastFallbackGroupChanged() throws RemoteException {
        int groupId1 = 1;
        int groupId2 = 2;
        int broadcastId = 243;
        byte[] code = {0x00, 0x01, 0x00, 0x02};
        Set<BluetoothDevice> broadcastReceivers = new HashSet<>();

        synchronized (mService.mLeAudioCallbacks) {
            mService.mLeAudioCallbacks.register(mLeAudioCallbacks);
        }

        initializeNative();
        prepareConnectedUnicastDevice(groupId2, mDevice2);
        prepareHandoverStreamingBroadcast(groupId1, broadcastId, code);

        /* Mock device1 and device2 as receiving broadcast devices */
        broadcastReceivers.addAll(Arrays.asList(mDevice2, mDevice1));
        mService.selectDefaultBroadcastToUnicastFallbackGroup(broadcastReceivers);

        /* group 1 is deactivated due to broadcast and group 2 is set by default as broadcast to
         * unicast fallback group (first add device)
         */
        verify(mTbsService, never()).clearInbandRingtoneSupport(eq(mDevice2));
        verify(mTbsService, times(1)).clearInbandRingtoneSupport(eq(mDevice1));

        mLooper.dispatchAll();
        assertThat(mService.mBroadcastToUnicastFallbackGroup).isEqualTo(groupId2);

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

        startBroadcastAndVerify(broadcastId, buildBroadcastSettingsFromMetadata(meta, code, 1));
        doReturn(List.of(mDevice1)).when(mBassClientService).getSyncedBroadcastSinks(broadcastId);
        assertThat(mService.getLocalBroadcastReceivers().size()).isEqualTo(1);
        assertThat(mService.getLocalBroadcastReceivers()).containsExactly(mDevice1);

        stopBroadcastAndVerify(broadcastId);
        assertThat(mService.getLocalBroadcastReceivers()).isEmpty();
    }

    @Test
    public void testManageBroadcastToUnicastFallbackGroup() {
        int groupId = 1;
        int groupId2 = 2;
        int broadcastId = 243;
        byte[] code = {0x00, 0x01, 0x00, 0x02};
        Set<BluetoothDevice> broadcastReceivers = new HashSet<>();

        initializeNative();
        prepareHandoverStreamingBroadcast(groupId, broadcastId, code);
        mService.deviceConnected(mDevice1);

        if (Flags.leaudioFallbackGroupSelection()) {
            /* Mock mDevice1 as receiving broadcast device */
            broadcastReceivers.add(mDevice1);
            mService.selectDefaultBroadcastToUnicastFallbackGroup(broadcastReceivers);
        }

        prepareConnectedUnicastDevice(groupId2, mDevice2);

        InOrder tbsOrder = inOrder(mTbsService);
        tbsOrder.verify(mTbsService, never()).clearInbandRingtoneSupport(eq(mDevice2));
        if (Flags.leaudioFallbackGroupSelection()) {
            /* Expect clear of inband rington support after broadcast is create and before
             * fallback group would be set
             */
            tbsOrder.verify(mTbsService, times(1)).clearInbandRingtoneSupport(eq(mDevice1));
        } else {
            tbsOrder.verify(mTbsService, never()).clearInbandRingtoneSupport(eq(mDevice1));
        }

        assertThat(mService.mBroadcastToUnicastFallbackGroup).isEqualTo(groupId);
        tbsOrder.verify(mTbsService, times(1)).setInbandRingtoneSupport(eq(mDevice1));

        reset(mAudioManager);

        mService.setBroadcastToUnicastFallbackGroup(groupId2);
        tbsOrder.verify(mTbsService, times(1)).clearInbandRingtoneSupport(eq(mDevice1));
        tbsOrder.verify(mTbsService, times(1)).setInbandRingtoneSupport(eq(mDevice2));

        assertThat(mService.getBroadcastToUnicastFallbackGroup()).isEqualTo(groupId2);
    }

    private void disconnectDevice(BluetoothDevice device) {
        doReturn(true).when(mLeAudioNativeInterface).disconnectLeAudio(any(BluetoothDevice.class));
        assertThat(mService.disconnect(device)).isTrue();
        mLooper.dispatchAll();

        // Verify the connection state broadcast, and that we are in Connected state
        verifyConnectionStateIntent(device, STATE_DISCONNECTING, STATE_CONNECTED);
        assertThat(mService.getConnectionState(device)).isEqualTo(STATE_DISCONNECTING);

        LeAudioStackEvent create_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        create_event.device = device;
        create_event.valueInt1 = LeAudioStackEvent.CONNECTION_STATE_DISCONNECTED;
        mService.messageFromNative(create_event);
        mLooper.dispatchAll();

        verifyConnectionStateIntent(device, STATE_DISCONNECTED, STATE_DISCONNECTING);
        assertThat(mService.getConnectionState(device)).isEqualTo(STATE_DISCONNECTED);
        mService.deviceDisconnected(device, false);
        mLooper.dispatchAll();
    }

    @Test
    public void testSetDefaultBroadcastToUnicastFallbackGroup() {
        int groupId = 1;
        int groupId2 = 2;
        Set<BluetoothDevice> broadcastReceivers = new HashSet<>();

        /* If no connected devices - no fallback device */
        assertThat(mService.getBroadcastToUnicastFallbackGroup())
                .isEqualTo(LE_AUDIO_GROUP_ID_INVALID);

        initializeNative();
        prepareConnectedUnicastDevice(groupId, mDevice1);
        mService.deviceConnected(mDevice1);
        prepareConnectedUnicastDevice(groupId2, mDevice2);

        /* Mock device1 and device2 as receiving broadcast devices */
        broadcastReceivers.addAll(Arrays.asList(mDevice2, mDevice1));
        mService.selectDefaultBroadcastToUnicastFallbackGroup(broadcastReceivers);

        LeAudioStackEvent stackEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        stackEvent.valueInt1 = groupId2;
        stackEvent.valueInt2 = LeAudioStackEvent.GROUP_STATUS_ACTIVE;
        mService.messageFromNative(stackEvent);
        mLooper.dispatchAll();

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
        mLooper.dispatchAll();

        /* Disconnected last device from fallback should trigger set default group 1 -> -1 */
        disconnectDevice(mDevice1);
        assertThat(mService.getBroadcastToUnicastFallbackGroup())
                .isEqualTo(LE_AUDIO_GROUP_ID_INVALID);
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

    @Test
    public void testBroadcastNoPriorUnicastHandover() throws RemoteException {
        int broadcastId = 243;
        byte[] code = {0x00, 0x01, 0x00, 0x02};

        synchronized (mService.mBroadcastCallbacks) {
            mService.mBroadcastCallbacks.register(mCallbacks);
        }

        BluetoothLeAudioContentMetadata meta =
                new BluetoothLeAudioContentMetadata.Builder()
                        .setLanguage("pol")
                        .setProgramInfo("Subgroup broadcast info")
                        .build();

        /* Start broadcast without prior unicast to broadcast handover, broadcast device should be
         * exposed to AudioManager
         */
        startBroadcastAndVerify(broadcastId, buildBroadcastSettingsFromMetadata(meta, code, 1));
        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(mBroadcastDevice), eq(null), any(BluetoothProfileConnectionInfo.class));

        /* Stop broadcast without prior broadcast to unicast handover, broadcast device should be
         * cleaned in AudioManager
         */
        stopBroadcastAndVerify(broadcastId);
        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(null), eq(mBroadcastDevice), any(BluetoothProfileConnectionInfo.class));
    }

    @Test
    public void testCreateBroadcastWhileAudioModeChange() {
        /* Imitate setting device in call (without active unicast device) */
        mService.handleAudioModeChange(AudioManager.MODE_IN_CALL);

        int broadcastId = 243;
        byte[] code = {0x00, 0x01, 0x00, 0x02};

        synchronized (mService.mBroadcastCallbacks) {
            mService.mBroadcastCallbacks.register(mCallbacks);
        }

        /* call create broadcast */
        BluetoothLeAudioContentMetadata meta =
                new BluetoothLeAudioContentMetadata.Builder()
                        .setLanguage("pol")
                        .setProgramInfo("Subgroup broadcast info")
                        .build();

        BluetoothLeBroadcastSettings settings = buildBroadcastSettingsFromMetadata(meta, code, 1);
        mService.createBroadcast(settings);

        /* Imitate setting device not in call */
        mService.handleAudioModeChange(AudioManager.MODE_NORMAL);

        /* Successfully created audio session notification */
        LeAudioStackEvent stackEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_AUDIO_SESSION_CREATED);
        stackEvent.valueBool1 = true;
        mService.messageFromNative(stackEvent);
        mLooper.dispatchAll();

        /* Check if broadcast is started automatically when created */
        stackEvent = new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_CREATED);
        stackEvent.valueInt1 = broadcastId;
        stackEvent.valueBool1 = true;
        mService.messageFromNative(stackEvent);
        mLooper.dispatchAll();

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

        /* Verify if broadcast is auto-started on start */
        verify(mLeAudioBroadcasterNativeInterface, times(1)).startBroadcast(eq(broadcastId));
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
        mLooper.dispatchAll();
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
        mLooper.dispatchAll();
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
        mInOrder.verify(mAdapterService)
                .sendBroadcast(MockitoHamcrest.argThat(AllOf.allOf(matchers)), any(), any());
    }

    @Test
    public void testStopBroadcastDuringAudioModeDrivenBroadcastSwitch() throws RemoteException {
        int groupId = 1;
        int broadcastId = 243;
        byte[] code = {0x00, 0x01, 0x00, 0x02};

        InOrder inOrderNative = inOrder(mLeAudioNativeInterface);

        prepareHandoverStreamingBroadcast(groupId, broadcastId, code);

        /* Check if unicast to broadcast handover clear active group */
        inOrderNative.verify(mLeAudioNativeInterface).groupSetActive(eq(LE_AUDIO_GROUP_ID_INVALID));

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
        mLooper.dispatchAll();

        LeAudioStackEvent create_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        create_event.valueInt1 = groupId;
        create_event.valueInt2 = LeAudioStackEvent.GROUP_STATUS_ACTIVE;
        mService.messageFromNative(create_event);
        mLooper.dispatchAll();

        verify(mAudioManager, times(2))
                .handleBluetoothActiveDeviceChanged(
                        eq(mDevice1), eq(null), any(BluetoothProfileConnectionInfo.class));
        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(null), eq(mBroadcastDevice), any(BluetoothProfileConnectionInfo.class));

        /* Active group should become the one that was active before broadcasting */
        int activeGroup = mService.getActiveGroupId();
        assertThat(activeGroup).isEqualTo(groupId);

        /* Broadcast Stopped during Audio mode driven Broadcast switch*/
        stopBroadcastAndVerify(broadcastId);

        /* mBroadcastIdDeactivatedForUnicastTransition should be cleared after stop broadcast */
        assertThat(mService.mBroadcastIdDeactivatedForUnicastTransition.isPresent()).isFalse();

        /* Imitate group change request by Bluetooth Sink HAL suspend request */
        create_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_UNICAST_MONITOR_MODE_STATUS);
        create_event.valueInt1 = LeAudioStackEvent.DIRECTION_SINK;
        create_event.valueInt2 = LeAudioStackEvent.STATUS_LOCAL_STREAM_SUSPENDED;
        mService.messageFromNative(create_event);
        mLooper.dispatchAll();

        /* Device should not be inactivated if in IN_CALL audio mode */
        inOrderNative
                .verify(mLeAudioNativeInterface, never())
                .groupSetActive(eq(LE_AUDIO_GROUP_ID_INVALID));

        /* Imitate setting device not in call */
        mService.handleAudioModeChange(AudioManager.MODE_NORMAL);

        /* Device should not be inactivated if broadcast is stopped previously */
        inOrderNative
                .verify(mLeAudioNativeInterface, never())
                .groupSetActive(eq(LE_AUDIO_GROUP_ID_INVALID));

        /* Active group should keep same with the one that was active before broadcasting */
        activeGroup = mService.getActiveGroupId();
        assertThat(activeGroup).isEqualTo(groupId);
    }

    @Test
    public void testFallbackToUnicastWhenCreateBroadcastFailed() {
        int groupId = 1;
        int broadcastId = 243;
        byte[] code = {0x00, 0x01, 0x00, 0x02};

        initializeNative();
        prepareConnectedUnicastDevice(groupId, mDevice1);

        LeAudioStackEvent stackEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        stackEvent.valueInt1 = groupId;
        stackEvent.valueInt2 = LeAudioStackEvent.GROUP_STATUS_ACTIVE;
        mService.messageFromNative(stackEvent);
        mLooper.dispatchAll();

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
        mLooper.dispatchAll();

        /* Active group should become inactive */
        int activeGroup = mService.getActiveGroupId();
        assertThat(activeGroup).isEqualTo(LE_AUDIO_GROUP_ID_INVALID);

        /* Imitate group inactivity to cause create broadcast */
        stackEvent = new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        stackEvent.valueInt1 = groupId;
        stackEvent.valueInt2 = LeAudioStackEvent.GROUP_STATUS_INACTIVE;
        mService.messageFromNative(stackEvent);
        mLooper.dispatchAll();

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

        /* Check if fallback to unicast group while create broadcast failed */
        stackEvent = new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_BROADCAST_CREATED);
        stackEvent.valueInt1 = broadcastId;
        stackEvent.valueBool1 = false;
        mService.messageFromNative(stackEvent);
        mLooper.dispatchAll();

        /* Verify if unicast group is activated while create broadcast failed */
        verify(mLeAudioNativeInterface).groupSetActive(eq(groupId));

        LeAudioStackEvent create_event =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        create_event.valueInt1 = groupId;
        create_event.valueInt2 = LeAudioStackEvent.GROUP_STATUS_ACTIVE;
        mService.messageFromNative(create_event);
        mLooper.dispatchAll();

        /* Active group should become the one that was active before broadcasting */
        activeGroup = mService.getActiveGroupId();
        assertThat(activeGroup).isEqualTo(groupId);
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_BROADCAST_SOURCE_CHANNEL_MAP_CLASSIFICATION)
    public void testSetBigChannelMapClassification_noBroadcast() {
        final int broadcastId = 1;
        final int action = 3;
        final BluetoothDevice sink = getTestDevice(0);

        // Call the method to be tested without creating a broadcast first
        mService.setBigChannelMapClassification(action, sink, broadcastId);

        // Verify that the call is NOT forwarded to the native interface
        verify(mLeAudioBroadcasterNativeInterface, never())
                .setBigChannelMapClassification(anyInt(), any(BluetoothDevice.class), anyInt());
    }

    /**
     * Test that handleRecordingModeChange does not re-execute logic if the state hasn't changed.
     */
    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_FIX_STREAM_CONFIRM_DATAPATH_RACE)
    public void testHandleRecordingModeChange_deduplication() throws RemoteException {
        int groupId = 1;
        int broadcastId = 243;
        byte[] code = {0x00, 0x01, 0x00, 0x02};

        // 1. Perepare Unicast and start Broadcast
        InOrder inOrderNative = inOrder(mLeAudioNativeInterface);

        prepareHandoverStreamingBroadcast(groupId, broadcastId, code);
        /* Check if unicast to broadcast handover clear active group */
        inOrderNative.verify(mLeAudioNativeInterface).groupSetActive(eq(LE_AUDIO_GROUP_ID_INVALID));

        // 2. Enable Recording Mode (First Time)
        mService.handleRecordingModeChange(true);
        verify(mLeAudioBroadcasterNativeInterface).pauseBroadcast(broadcastId);

        Mockito.clearInvocations(mLeAudioBroadcasterNativeInterface);

        // 3. Enable Recording Mode (Second Time - Duplicate)
        mService.handleRecordingModeChange(true);
        verify(mLeAudioBroadcasterNativeInterface, never()).pauseBroadcast(anyInt());

        // 4. Disable Recording Mode (First Time)
        mService.handleRecordingModeChange(false);
        verify(mLeAudioNativeInterface).groupSetActive(eq(LE_AUDIO_GROUP_ID_INVALID));
    }

    /** Test that handleAudioModeChange does not re-execute logic if the mode hasn't changed. */
    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_FIX_STREAM_CONFIRM_DATAPATH_RACE)
    public void testHandleAudioModeChange_deduplication() throws RemoteException {
        int groupId = 1;
        int broadcastId = 243;
        byte[] code = {0x00, 0x01, 0x00, 0x02};

        // 1. Perepare Unicast and start Broadcast
        InOrder inOrderNative = inOrder(mLeAudioNativeInterface);

        prepareHandoverStreamingBroadcast(groupId, broadcastId, code);
        /* Check if unicast to broadcast handover clear active group */
        inOrderNative.verify(mLeAudioNativeInterface).groupSetActive(eq(LE_AUDIO_GROUP_ID_INVALID));

        // 2. Set Audio Mode to IN_CALL (First Time)
        mService.handleAudioModeChange(AudioManager.MODE_IN_CALL);
        verify(mLeAudioBroadcasterNativeInterface).pauseBroadcast(broadcastId);

        Mockito.clearInvocations(mLeAudioBroadcasterNativeInterface);

        // 3. Set Audio Mode to IN_CALL (Second Time - Duplicate)
        mService.handleAudioModeChange(AudioManager.MODE_IN_CALL);
        verify(mLeAudioBroadcasterNativeInterface, never()).pauseBroadcast(anyInt());

        // 4. Audio Mode back to normal (First Time)
        mService.handleAudioModeChange(AudioManager.MODE_NORMAL);
        verify(mLeAudioNativeInterface).groupSetActive(eq(LE_AUDIO_GROUP_ID_INVALID));
    }
}
