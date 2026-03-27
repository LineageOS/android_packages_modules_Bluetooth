/*
 * Copyright 2020 HIMSA II K/S - www.himsa.com.
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
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
import static android.bluetooth.BluetoothProfile.EXTRA_PREVIOUS_STATE;
import static android.bluetooth.BluetoothProfile.EXTRA_STATE;
import static android.bluetooth.BluetoothProfile.LE_AUDIO_BROADCAST;
import static android.bluetooth.BluetoothProfile.LE_CALL_CONTROL;
import static android.bluetooth.BluetoothProfile.MCP_SERVER;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;
import static android.bluetooth.IBluetoothLeAudio.LE_AUDIO_GROUP_ID_INVALID;
import static android.bluetooth.IBluetoothVolumeControl.VOLUME_CONTROL_UNKNOWN_VOLUME;

import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;

import static com.android.bluetooth.TestUtils.getTestDevice;
import static com.android.bluetooth.TestUtils.mockGetSystemService;
import static com.android.bluetooth.le_audio.LeAudioStateMachine.CONNECT_TIMEOUT;
import static com.android.bluetooth.le_audio.LeAudioTmapGattServer.TMAP_ROLE_FLAG_BMS;
import static com.android.bluetooth.le_audio.LeAudioTmapGattServer.TMAP_ROLE_FLAG_CG;
import static com.android.bluetooth.le_audio.LeAudioTmapGattServer.TMAP_ROLE_FLAG_UMS;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.ActivityManager;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothLeAudio;
import android.bluetooth.BluetoothLeAudioCodecConfig;
import android.bluetooth.BluetoothLeAudioCodecStatus;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.IBluetoothLeAudioCallback;
import android.bluetooth.le.IScannerCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.BluetoothProfileConnectionInfo;
import android.os.Binder;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.sysprop.BluetoothProperties;
import android.util.Pair;

import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.bluetooth.TestLooper;
import com.android.bluetooth.Util;
import com.android.bluetooth.a2dp.A2dpService;
import com.android.bluetooth.bass_client.BassClientService;
import com.android.bluetooth.btservice.ActiveDeviceManager;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.Config;
import com.android.bluetooth.csip.CsipSetCoordinatorService;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.hap.HapClientService;
import com.android.bluetooth.hearingaid.HearingAidService;
import com.android.bluetooth.hfp.HeadsetService;
import com.android.bluetooth.hid.HidHostService;
import com.android.bluetooth.le_scan.ScanController;
import com.android.bluetooth.mcp.McpService;
import com.android.bluetooth.storage.BluetoothStorageManager;
import com.android.bluetooth.tbs.TbsService;
import com.android.bluetooth.vc.VolumeControlService;
import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.tests.bluetooth.FlagsWrapper;
import com.android.tests.bluetooth.StaticMockitoRule;

import org.hamcrest.Matcher;
import org.hamcrest.core.AllOf;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.hamcrest.MockitoHamcrest;

import platform.test.runner.parameterized.ParameterizedAndroidJunit4;
import platform.test.runner.parameterized.Parameters;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Test cases for {@link LeAudioService}. */
@MediumTest
@RunWith(ParameterizedAndroidJunit4.class)
public class LeAudioServiceTest {
    @Rule public final SetFlagsRule mSetFlagsRule;
    @Rule public final StaticMockitoRule mMockitoRule = new StaticMockitoRule(Config.class);
    @Rule public final TemporaryFolder mTempFolder = new TemporaryFolder();

    @Mock private AdapterService mAdapterService;
    @Mock private ActiveDeviceManager mActiveDeviceManager;
    @Mock private ScanController mScanController;
    @Mock private AudioManager mAudioManager;
    @Mock private LeAudioNativeInterface mNativeInterface;
    @Mock private ApplicationInfo mMockApplicationInfo;
    @Mock private LeAudioBroadcasterNativeInterface mLeAudioBroadcasterNativeInterface;
    @Mock private LeAudioTmapGattServer mTmapGattServer;
    @Mock private ActivityManager mActivityManager;
    @Mock private PackageManager mPackageManager;
    @Mock private A2dpService mA2dpService;
    @Mock private BassClientService mBassClientService;
    @Mock private CsipSetCoordinatorService mCsipSetCoordinatorService;
    @Mock private HapClientService mHapClientService;
    @Mock private HeadsetService mHeadsetService;
    @Mock private HearingAidService mHearingAidService;
    @Mock private HidHostService mHidHostService;
    @Mock private McpService mMcpService;
    @Mock private TbsService mTbsService;
    @Mock private VolumeControlService mVolumeControlService;

    @Spy private LeAudioObjectsFactory mObjectsFactory = LeAudioObjectsFactory.getInstance();

    private static final int MAX_LE_AUDIO_CONNECTIONS = 5;
    private static final int LE_AUDIO_GROUP_ID_INVALID = -1;

    private final HashSet<BluetoothDevice> mBondedDevices = new HashSet<>();
    private final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();
    private final BluetoothDevice mLeftDevice = getTestDevice(0);
    private final BluetoothDevice mRightDevice = getTestDevice(1);
    private final BluetoothDevice mSingleDevice = getTestDevice(2);
    private final BluetoothDevice mSingleDevice_2 = getTestDevice(3);
    private final BluetoothDevice mBroadcastDevice = getTestDevice("FF:FF:FF:FF:FF:FF");

    private static final int TEST_GROUP_ID = 1;
    private static final int TEST_GROUP_ID2 = 2;
    private boolean onGroupStatusCallbackCalled = false;
    private boolean onGroupStreamStatusCallbackCalled = false;
    private boolean onGroupCodecConfChangedCallbackCalled = false;
    private BluetoothLeAudioCodecStatus testCodecStatus = null;

    private static final BluetoothLeAudioCodecConfig EMPTY_CONFIG =
            new BluetoothLeAudioCodecConfig.Builder().build();

    private static final BluetoothLeAudioCodecConfig LC3_16KHZ_CONFIG =
            new BluetoothLeAudioCodecConfig.Builder()
                    .setCodecType(BluetoothLeAudioCodecConfig.SOURCE_CODEC_TYPE_LC3)
                    .setCodecPriority(BluetoothLeAudioCodecConfig.CODEC_PRIORITY_DEFAULT)
                    .setSampleRate(BluetoothLeAudioCodecConfig.SAMPLE_RATE_16000)
                    .build();
    private static final BluetoothLeAudioCodecConfig LC3_48KHZ_CONFIG =
            new BluetoothLeAudioCodecConfig.Builder()
                    .setCodecType(BluetoothLeAudioCodecConfig.SOURCE_CODEC_TYPE_LC3)
                    .setCodecPriority(BluetoothLeAudioCodecConfig.CODEC_PRIORITY_DEFAULT)
                    .setSampleRate(BluetoothLeAudioCodecConfig.SAMPLE_RATE_48000)
                    .build();

    private static final BluetoothLeAudioCodecConfig LC3_48KHZ_16KHZ_CONFIG =
            new BluetoothLeAudioCodecConfig.Builder()
                    .setCodecType(BluetoothLeAudioCodecConfig.SOURCE_CODEC_TYPE_LC3)
                    .setCodecPriority(BluetoothLeAudioCodecConfig.CODEC_PRIORITY_DEFAULT)
                    .setSampleRate(
                            BluetoothLeAudioCodecConfig.SAMPLE_RATE_48000
                                    | BluetoothLeAudioCodecConfig.SAMPLE_RATE_16000)
                    .build();

    private static final BluetoothLeAudioCodecConfig OPUS_48KHZ_CONFIG =
            new BluetoothLeAudioCodecConfig.Builder()
                    .setCodecType(BluetoothLeAudioCodecConfig.SOURCE_CODEC_TYPE_OPUS)
                    .setCodecPriority(BluetoothLeAudioCodecConfig.CODEC_PRIORITY_DEFAULT + 1)
                    .setSampleRate(BluetoothLeAudioCodecConfig.SAMPLE_RATE_48000)
                    .setBitsPerSample(BluetoothLeAudioCodecConfig.BITS_PER_SAMPLE_24)
                    .setChannelCount(BluetoothLeAudioCodecConfig.CHANNEL_COUNT_2)
                    .setFrameDuration(BluetoothLeAudioCodecConfig.FRAME_DURATION_10000)
                    .setOctetsPerFrame(90)
                    .setMinOctetsPerFrame(90)
                    .setMaxOctetsPerFrame(100)
                    .build();

    private static final BluetoothLeAudioCodecConfig OPUS_HI_RES_96KHZ_CONFIG =
            new BluetoothLeAudioCodecConfig.Builder()
                    .setCodecType(BluetoothLeAudioCodecConfig.SOURCE_CODEC_TYPE_OPUS_HI_RES)
                    .setCodecPriority(BluetoothLeAudioCodecConfig.CODEC_PRIORITY_DEFAULT + 1)
                    .setSampleRate(BluetoothLeAudioCodecConfig.SAMPLE_RATE_96000)
                    .setBitsPerSample(BluetoothLeAudioCodecConfig.BITS_PER_SAMPLE_24)
                    .setChannelCount(BluetoothLeAudioCodecConfig.CHANNEL_COUNT_2)
                    .setFrameDuration(BluetoothLeAudioCodecConfig.FRAME_DURATION_20000)
                    .setOctetsPerFrame(100)
                    .setMinOctetsPerFrame(90)
                    .setMaxOctetsPerFrame(120)
                    .build();

    private static final List<BluetoothLeAudioCodecConfig> INPUT_CAPABILITIES_CONFIG =
            List.of(LC3_48KHZ_16KHZ_CONFIG);

    private static final List<BluetoothLeAudioCodecConfig> OUTPUT_CAPABILITIES_CONFIG =
            List.of(LC3_48KHZ_16KHZ_CONFIG);

    private static final List<BluetoothLeAudioCodecConfig> INPUT_SELECTABLE_CONFIG =
            List.of(LC3_16KHZ_CONFIG);

    private static final List<BluetoothLeAudioCodecConfig> INPUT_EMPTY_CONFIG =
            List.of(EMPTY_CONFIG);

    private static final List<BluetoothLeAudioCodecConfig> OUTPUT_SELECTABLE_CONFIG =
            List.of(LC3_48KHZ_16KHZ_CONFIG);

    private static final List<BluetoothLeAudioCodecConfig> OPUS_SELECTABLE_CONFIGS =
            List.of(LC3_48KHZ_16KHZ_CONFIG, OPUS_48KHZ_CONFIG, OPUS_HI_RES_96KHZ_CONFIG);

    private LeAudioService mService;
    private BluetoothStorageManager mStorage;
    private TestLooper mLooper;
    private InOrder mInOrder;

    @Parameters(name = "{0}")
    public static List<FlagsWrapper> getParams() {
        return FlagsWrapper.progressionOf();
    }

    public LeAudioServiceTest(FlagsWrapper flags) {
        mSetFlagsRule = new SetFlagsRule(flags.getFlags());
    }

    @Before
    public void setUp() throws Exception {
        doReturn(mMockApplicationInfo).when(mAdapterService).getApplicationInfo();
        doReturn(mTempFolder.getRoot()).when(mAdapterService).getFilesDir();
        doAnswer(it -> new File(mTempFolder.getRoot(), it.getArgument(0)))
                .when(mAdapterService)
                .getDatabasePath(anyString());

        doReturn(mAdapterService).when(mAdapterService).createDeviceProtectedStorageContext();
        mBondedDevices.clear();

        mockGetSystemService(mAdapterService, AudioManager.class, mAudioManager);
        doReturn(mAdapterService).when(mAdapterService).getApplicationContext();
        doReturn(mAdapterService).when(mAdapterService).createContextAsUser(any(), anyInt());
        doReturn(CONNECTION_POLICY_UNKNOWN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(any(), eq(BluetoothProfile.LE_AUDIO));
        doReturn(mContext.getContentResolver()).when(mAdapterService).getContentResolver();
        doReturn(MAX_LE_AUDIO_CONNECTIONS).when(mAdapterService).getMaxConnectedAudioDevices();

        ExtendedMockito.doReturn(true)
                .when(() -> Config.isProfileSupported(BluetoothProfile.LE_AUDIO_BROADCAST));
        ExtendedMockito.doReturn(true)
                .when(() -> Config.isProfileSupported(BluetoothProfile.LE_AUDIO));

        doReturn(Optional.of(mTbsService)).when(mAdapterService).getTbsService();

        doAnswer(invocation -> mBondedDevices).when(mAdapterService).getBondedDevices();
        doAnswer(
                        invocation -> {
                            Runnable runnable = invocation.getArgument(0);
                            runnable.run();
                            return null;
                        })
                .when(mScanController)
                .doOnScanThread(any(Runnable.class));
        mockGetSystemService(mAdapterService, AudioManager.class, mAudioManager);
        doReturn(BOND_BONDED).when(mAdapterService).getBondState(any(BluetoothDevice.class));
        doReturn(
                        new ParcelUuid[] {
                            BluetoothUuid.LE_AUDIO,
                            BluetoothUuid.VOLUME_CONTROL,
                            BluetoothUuid.HAS,
                            BluetoothUuid.COORDINATED_SET,
                            BluetoothUuid.BASS
                        })
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));

        LeAudioObjectsFactory.setInstanceForTesting(mObjectsFactory);
        doReturn(mTmapGattServer).when(mObjectsFactory).getTmapGattServer(any());

        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectLeAudio(any(BluetoothDevice.class));

        doReturn(mBroadcastDevice)
                .when(mAdapterService)
                .getDeviceFromByte(Util.getBytesFromAddress("FF:FF:FF:FF:FF:FF"));

        doReturn(Optional.of(mA2dpService)).when(mAdapterService).getA2dpService();
        doReturn(Optional.of(mBassClientService)).when(mAdapterService).getBassClientService();
        doReturn(Optional.of(mCsipSetCoordinatorService))
                .when(mAdapterService)
                .getCsipSetCoordinatorService();
        doReturn(Optional.of(mHapClientService)).when(mAdapterService).getHapClientService();
        doReturn(Optional.of(mHeadsetService)).when(mAdapterService).getHeadsetService();
        doReturn(Optional.of(mHidHostService)).when(mAdapterService).getHidHostService();
        doReturn(Optional.of(mHearingAidService)).when(mAdapterService).getHearingAidService();
        doReturn(Optional.of(mMcpService)).when(mAdapterService).getMcpService();
        doReturn(Optional.of(mVolumeControlService))
                .when(mAdapterService)
                .getVolumeControlService();

        mLooper = new TestLooper();

        mStorage = Mockito.spy(new BluetoothStorageManager(mAdapterService));
        mInOrder = inOrder(mAdapterService, mAudioManager, mNativeInterface, mStorage);

        mService =
                new LeAudioService(
                        mAdapterService,
                        mStorage,
                        mNativeInterface,
                        mLeAudioBroadcasterNativeInterface,
                        mActiveDeviceManager,
                        mScanController,
                        mLooper.getLooper(),
                        mActivityManager,
                        mPackageManager);
        mService.setAvailable(true);

        LeAudioStackEvent stackEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_NATIVE_INITIALIZED);
        mService.messageFromNative(stackEvent);
        mLooper.dispatchAll();
        assertThat(mService.mLeAudioNativeIsInitialized).isTrue();

        mInOrder.verify(mNativeInterface).init(any());
    }

    @Test
    public void tmapRoleMask_whenSupportLeCallControl_isCG() {
        assertTmapRole(TMAP_ROLE_FLAG_CG, LE_CALL_CONTROL);
    }

    @Test
    public void tmapRoleMask_whenSupportMcpServer_isUMS() {
        assertTmapRole(TMAP_ROLE_FLAG_UMS, MCP_SERVER);
    }

    @Test
    public void tmapRoleMask_whenSupportLeCallControlAndMcpServer_isCGAndUMS() {
        assertTmapRole(TMAP_ROLE_FLAG_CG | TMAP_ROLE_FLAG_UMS, LE_CALL_CONTROL, MCP_SERVER);
    }

    @Test
    public void tmapRoleMask_whenSupportBroadcast_isBMS() {
        assertTmapRole(TMAP_ROLE_FLAG_BMS, LE_AUDIO_BROADCAST);
    }

    @Test
    public void tmapRoleMask_whenSupportBroadcastAndLeCallControl_isBMSAndCG() {
        assertTmapRole(TMAP_ROLE_FLAG_CG | TMAP_ROLE_FLAG_BMS, LE_AUDIO_BROADCAST, LE_CALL_CONTROL);
    }

    @Test
    public void tmapRoleMask_whenSupportBroadcastAndMcpServer_isBMSAndUMS() {
        assertTmapRole(TMAP_ROLE_FLAG_UMS | TMAP_ROLE_FLAG_BMS, LE_AUDIO_BROADCAST, MCP_SERVER);
    }

    @Test
    public void tmapRoleMask_whenSupportLeCallControlAndBroadcastAndMcpServer_isBMSAndUMSAndCG() {
        assertTmapRole(
                TMAP_ROLE_FLAG_CG | TMAP_ROLE_FLAG_UMS | TMAP_ROLE_FLAG_BMS,
                LE_AUDIO_BROADCAST,
                MCP_SERVER,
                LE_CALL_CONTROL);
    }

    private void assertTmapRole(int expectedMasks, int... supportedProfiles) {
        // revert the profile set
        ExtendedMockito.doReturn(false).when(() -> Config.isProfileSupported(anyInt()));
        for (int profile : supportedProfiles) {
            ExtendedMockito.doReturn(true).when(() -> Config.isProfileSupported(profile));
        }
        int mask =
                Flags.leaudioCentralizeTmap()
                        ? LeAudioTmapGattServer.calculateTmapRoleMask()
                        : new LeAudioService(
                                        mAdapterService,
                                        mStorage,
                                        mNativeInterface,
                                        mLeAudioBroadcasterNativeInterface,
                                        mActiveDeviceManager,
                                        mScanController,
                                        mLooper.getLooper(),
                                        mActivityManager,
                                        mPackageManager)
                                .getTmapRoleMask();
        assertThat(mask).isEqualTo(expectedMasks);
    }

    /** Test enabling disabling device autoconnections when connection policy is set */
    @Test
    public void testEnableDisableProfile() {
        // Make sure the device is known to the service and is not forbidden to connect
        mService.createDeviceDescriptor(mSingleDevice, true);
        doReturn(CONNECTION_POLICY_UNKNOWN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(mSingleDevice, BluetoothProfile.LE_AUDIO);

        // Verify the device is enabled in the service when policy is not FORBIDDEN during BT Enable
        mService.handleBluetoothEnabled();
        mInOrder.verify(mNativeInterface).setEnableState(eq(mSingleDevice), eq(true));

        // Verify the device is disabled in the service when policy is set to FORBIDDEN
        mService.setConnectionPolicy(mSingleDevice, CONNECTION_POLICY_FORBIDDEN);
        mInOrder.verify(mNativeInterface).setEnableState(eq(mSingleDevice), eq(false));
    }

    /** Test stop LeAudio Service */
    @Test
    public void testStopLeAudioService() {
        // Prepare: connect
        connectDevice(mLeftDevice);
        mService.cleanup();
    }

    @Test
    public void testGetSetPriority() {
        doReturn(CONNECTION_POLICY_UNKNOWN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(mLeftDevice, BluetoothProfile.LE_AUDIO);
        assertThat(mService.getConnectionPolicy(mLeftDevice)).isEqualTo(CONNECTION_POLICY_UNKNOWN);

        doReturn(CONNECTION_POLICY_FORBIDDEN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(mLeftDevice, BluetoothProfile.LE_AUDIO);
        assertThat(mService.getConnectionPolicy(mLeftDevice))
                .isEqualTo(CONNECTION_POLICY_FORBIDDEN);

        doReturn(CONNECTION_POLICY_ALLOWED)
                .when(mAdapterService)
                .getProfileConnectionPolicy(mLeftDevice, BluetoothProfile.LE_AUDIO);
        assertThat(mService.getConnectionPolicy(mLeftDevice)).isEqualTo(CONNECTION_POLICY_ALLOWED);
    }

    /**
     * Helper function to test okToConnect() method
     *
     * @param device test device
     * @param bondState bond state value, could be invalid
     * @param priority value, could be invalid, could be invalid
     * @param expected expected result from okToConnect()
     */
    private void testOkToConnectCase(
            BluetoothDevice device, int bondState, int priority, boolean expected) {
        doReturn(bondState).when(mAdapterService).getBondState(device);
        doReturn(priority)
                .when(mAdapterService)
                .getProfileConnectionPolicy(device, BluetoothProfile.LE_AUDIO);
        assertThat(mService.okToConnect(device)).isEqualTo(expected);
    }

    /** Test okToConnect method using various test cases */
    @Test
    public void testOkToConnect() {
        int badPriorityValue = 1024;
        int badBondState = 42;
        testOkToConnectCase(
                mSingleDevice, BluetoothDevice.BOND_NONE, CONNECTION_POLICY_UNKNOWN, false);
        testOkToConnectCase(
                mSingleDevice, BluetoothDevice.BOND_NONE, CONNECTION_POLICY_FORBIDDEN, false);
        testOkToConnectCase(
                mSingleDevice, BluetoothDevice.BOND_NONE, CONNECTION_POLICY_ALLOWED, false);
        testOkToConnectCase(mSingleDevice, BluetoothDevice.BOND_NONE, badPriorityValue, false);
        testOkToConnectCase(
                mSingleDevice, BluetoothDevice.BOND_BONDING, CONNECTION_POLICY_UNKNOWN, false);
        testOkToConnectCase(
                mSingleDevice, BluetoothDevice.BOND_BONDING, CONNECTION_POLICY_FORBIDDEN, false);
        testOkToConnectCase(
                mSingleDevice, BluetoothDevice.BOND_BONDING, CONNECTION_POLICY_ALLOWED, false);
        testOkToConnectCase(mSingleDevice, BluetoothDevice.BOND_BONDING, badPriorityValue, false);
        testOkToConnectCase(mSingleDevice, BOND_BONDED, CONNECTION_POLICY_UNKNOWN, true);
        testOkToConnectCase(mSingleDevice, BOND_BONDED, CONNECTION_POLICY_FORBIDDEN, false);
        testOkToConnectCase(mSingleDevice, BOND_BONDED, CONNECTION_POLICY_ALLOWED, true);
        testOkToConnectCase(mSingleDevice, BOND_BONDED, badPriorityValue, false);
        testOkToConnectCase(mSingleDevice, badBondState, CONNECTION_POLICY_UNKNOWN, false);
        testOkToConnectCase(mSingleDevice, badBondState, CONNECTION_POLICY_FORBIDDEN, false);
        testOkToConnectCase(mSingleDevice, badBondState, CONNECTION_POLICY_ALLOWED, false);
        testOkToConnectCase(mSingleDevice, badBondState, badPriorityValue, false);
    }

    /** Test that an outgoing connection to device that does not have Le Audio UUID is rejected */
    @Test
    public void testOutgoingConnectMissingLeAudioUuid() {
        // Update the device priority so okToConnect() returns true
        doReturn(CONNECTION_POLICY_ALLOWED)
                .when(mAdapterService)
                .getProfileConnectionPolicy(mLeftDevice, BluetoothProfile.LE_AUDIO);
        doReturn(CONNECTION_POLICY_FORBIDDEN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(mRightDevice, BluetoothProfile.LE_AUDIO);
        doReturn(CONNECTION_POLICY_FORBIDDEN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(mSingleDevice, BluetoothProfile.LE_AUDIO);

        // Return No UUID
        doReturn(new ParcelUuid[] {})
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));

        // Send a connect request
        assertThat(mService.connect(mLeftDevice)).isFalse();
    }

    /** Test that an outgoing connection to device with PRIORITY_OFF is rejected */
    @Test
    public void testOutgoingConnectPriorityOff() {
        // Set the device priority to PRIORITY_OFF so connect() should fail
        doReturn(CONNECTION_POLICY_FORBIDDEN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(mLeftDevice, BluetoothProfile.LE_AUDIO);

        // Send a connect request
        assertThat(mService.connect(mLeftDevice)).isFalse();
    }

    /** Test that an outgoing connection times out */
    @Test
    public void testOutgoingConnectTimeout() {
        // Update the device priority so okToConnect() returns true
        doReturn(CONNECTION_POLICY_ALLOWED)
                .when(mAdapterService)
                .getProfileConnectionPolicy(mLeftDevice, BluetoothProfile.LE_AUDIO);
        doReturn(CONNECTION_POLICY_FORBIDDEN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(mRightDevice, BluetoothProfile.LE_AUDIO);
        doReturn(CONNECTION_POLICY_FORBIDDEN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(mSingleDevice, BluetoothProfile.LE_AUDIO);

        // Send a connect request
        assertThat(mService.connect(mLeftDevice)).isTrue();
        mLooper.dispatchAll();

        // Verify the connection state broadcast, and that we are in Connecting state
        verifyConnectionStateIntent(mLeftDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_CONNECTING);

        mLooper.moveTimeForward(CONNECT_TIMEOUT.toMillis());
        mLooper.dispatchAll();

        // Verify the connection state broadcast, and that we are in Disconnected state
        verifyConnectionStateIntent(mLeftDevice, STATE_DISCONNECTED, STATE_CONNECTING);
        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_DISCONNECTED);
    }

    private void injectAudioDeviceAdded(
            BluetoothDevice device,
            int type,
            boolean isSink,
            boolean isSource,
            boolean expectedIntent) {
        mService.handleAudioDeviceAdded(device, type, isSink, isSource);
        mLooper.dispatchAll();
        if (expectedIntent) {
            verifyActiveDeviceStateIntent(device);
        } else {
            verifyNoIntentSent();
        }
    }

    private void injectAudioDeviceRemoved(
            BluetoothDevice device,
            int type,
            boolean isSink,
            boolean isSource,
            boolean expectedIntent) {
        mService.handleAudioDeviceRemoved(device, type, isSink, isSource);
        mLooper.dispatchAll();
        if (expectedIntent) {
            verifyActiveDeviceStateIntent(null);
        } else {
            verifyNoIntentSent();
        }
    }

    private void injectAndVerifyDeviceConnected(BluetoothDevice device) {
        generateConnectionMessageFromNative(
                device,
                LeAudioStackEvent.CONNECTION_STATE_CONNECTED,
                LeAudioStackEvent.CONNECTION_STATE_DISCONNECTED);
    }

    private void injectNoVerifyDeviceConnected(BluetoothDevice device) {
        generateUnexpectedConnectionMessageFromNative(
                device, LeAudioStackEvent.CONNECTION_STATE_CONNECTED);
    }

    private void injectAndVerifyDeviceDisconnected(BluetoothDevice device) {
        generateConnectionMessageFromNative(
                device,
                LeAudioStackEvent.CONNECTION_STATE_DISCONNECTED,
                LeAudioStackEvent.CONNECTION_STATE_CONNECTED);
    }

    private void injectNoVerifyDeviceDisconnected(BluetoothDevice device) {
        generateUnexpectedConnectionMessageFromNative(
                device, LeAudioStackEvent.CONNECTION_STATE_DISCONNECTED);
    }

    /** Test that the outgoing connect/disconnect and audio switch is successful. */
    @Test
    public void testAudioManagerConnectDisconnect() {
        // Update the device priority so okToConnect() returns true
        doReturn(CONNECTION_POLICY_ALLOWED)
                .when(mAdapterService)
                .getProfileConnectionPolicy(mLeftDevice, BluetoothProfile.LE_AUDIO);
        doReturn(CONNECTION_POLICY_ALLOWED)
                .when(mAdapterService)
                .getProfileConnectionPolicy(mRightDevice, BluetoothProfile.LE_AUDIO);
        doReturn(CONNECTION_POLICY_FORBIDDEN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(mSingleDevice, BluetoothProfile.LE_AUDIO);

        // Send a connect request
        assertThat(mService.connect(mLeftDevice)).isTrue();
        assertThat(mService.connect(mRightDevice)).isTrue();
        mLooper.dispatchAll();

        // Verify the connection state broadcast, and that we are in Connecting state
        verifyConnectionStateIntent(mLeftDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_CONNECTING);
        verifyConnectionStateIntent(mRightDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mRightDevice)).isEqualTo(STATE_CONNECTING);

        LeAudioStackEvent connCompletedEvent;
        // Send a message to trigger connection completed
        connCompletedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connCompletedEvent.device = mLeftDevice;
        connCompletedEvent.valueInt1 = LeAudioStackEvent.CONNECTION_STATE_CONNECTED;
        mService.messageFromNative(connCompletedEvent);
        mLooper.dispatchAll();

        // Verify the connection state broadcast, and that we are in Connected state
        verifyConnectionStateIntent(mLeftDevice, STATE_CONNECTED, STATE_CONNECTING);
        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_CONNECTED);

        // Send a message to trigger connection completed for right side
        connCompletedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connCompletedEvent.device = mRightDevice;
        connCompletedEvent.valueInt1 = LeAudioStackEvent.CONNECTION_STATE_CONNECTED;
        mService.messageFromNative(connCompletedEvent);
        mLooper.dispatchAll();

        // Verify the connection state broadcast, and that we are in Connected state for right side
        verifyConnectionStateIntent(mRightDevice, STATE_CONNECTED, STATE_CONNECTING);
        assertThat(mService.getConnectionState(mRightDevice)).isEqualTo(STATE_CONNECTED);

        // Verify the list of connected devices
        assertThat(mService.getConnectedDevices().contains(mLeftDevice)).isTrue();
        assertThat(mService.getConnectedDevices().contains(mRightDevice)).isTrue();

        // Send a disconnect request
        assertThat(mService.disconnect(mLeftDevice)).isTrue();
        assertThat(mService.disconnect(mRightDevice)).isTrue();
        mLooper.dispatchAll();

        // Verify the connection state broadcast, and that we are in Disconnecting state
        verifyConnectionStateIntent(mLeftDevice, STATE_DISCONNECTING, STATE_CONNECTED);
        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_DISCONNECTING);
        verifyConnectionStateIntent(mRightDevice, STATE_DISCONNECTING, STATE_CONNECTED);
        assertThat(mService.getConnectionState(mRightDevice)).isEqualTo(STATE_DISCONNECTING);

        // Send a message to trigger disconnection completed
        connCompletedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connCompletedEvent.device = mLeftDevice;
        connCompletedEvent.valueInt1 = LeAudioStackEvent.CONNECTION_STATE_DISCONNECTED;
        mService.messageFromNative(connCompletedEvent);
        mLooper.dispatchAll();

        // Verify the connection state broadcast, and that we are in Disconnected state
        verifyConnectionStateIntent(mLeftDevice, STATE_DISCONNECTED, STATE_DISCONNECTING);
        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_DISCONNECTED);

        // Send a message to trigger disconnection completed to the right device
        connCompletedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connCompletedEvent.device = mRightDevice;
        connCompletedEvent.valueInt1 = LeAudioStackEvent.CONNECTION_STATE_DISCONNECTED;
        mService.messageFromNative(connCompletedEvent);
        mLooper.dispatchAll();

        // Verify the connection state broadcast, and that we are in Disconnected state
        verifyConnectionStateIntent(mRightDevice, STATE_DISCONNECTED, STATE_DISCONNECTING);
        assertThat(mService.getConnectionState(mRightDevice)).isEqualTo(STATE_DISCONNECTED);

        // Verify the list of connected devices
        assertThat(mService.getConnectedDevices().contains(mLeftDevice)).isFalse();
        assertThat(mService.getConnectedDevices().contains(mRightDevice)).isFalse();
    }

    /**
     * Test that only CONNECTION_STATE_CONNECTED or CONNECTION_STATE_CONNECTING Le Audio stack
     * events will create a state machine.
     */
    @Test
    public void testCreateStateMachineStackEvents() {
        // Update the device priority so okToConnect() returns true
        doReturn(CONNECTION_POLICY_ALLOWED)
                .when(mAdapterService)
                .getProfileConnectionPolicy(mLeftDevice, BluetoothProfile.LE_AUDIO);
        doReturn(CONNECTION_POLICY_FORBIDDEN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(mRightDevice, BluetoothProfile.LE_AUDIO);
        doReturn(CONNECTION_POLICY_FORBIDDEN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(mSingleDevice, BluetoothProfile.LE_AUDIO);

        // Create device descriptor with connect request
        assertThat(mService.connect(mLeftDevice)).isTrue();
        mLooper.dispatchAll();

        // Le Audio stack event: CONNECTION_STATE_CONNECTING - state machine should be created
        generateConnectionMessageFromNative(mLeftDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_CONNECTING);
        assertThat(mService.getDevices().contains(mLeftDevice)).isTrue();

        // LeAudio stack event: CONNECTION_STATE_DISCONNECTED - state machine should be removed
        generateConnectionMessageFromNative(mLeftDevice, STATE_DISCONNECTED, STATE_CONNECTING);
        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_DISCONNECTED);
        assertThat(mService.getDevices().contains(mLeftDevice)).isTrue();
        mService.bondStateChanged(mLeftDevice, BluetoothDevice.BOND_NONE);
        mLooper.dispatchAll();
        assertThat(mService.getDevices().contains(mLeftDevice)).isFalse();

        // Remove bond will remove also device descriptor. Device has to be connected again
        assertThat(mService.connect(mLeftDevice)).isTrue();
        mLooper.dispatchAll();
        verifyConnectionStateIntent(mLeftDevice, STATE_CONNECTING, STATE_DISCONNECTED);

        // stack event: CONNECTION_STATE_CONNECTED - state machine should be created
        generateConnectionMessageFromNative(mLeftDevice, STATE_CONNECTED, STATE_CONNECTING);
        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices().contains(mLeftDevice)).isTrue();

        // stack event: CONNECTION_STATE_DISCONNECTED - state machine should be removed
        generateConnectionMessageFromNative(mLeftDevice, STATE_DISCONNECTED, STATE_CONNECTED);
        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_DISCONNECTED);
        assertThat(mService.getDevices().contains(mLeftDevice)).isTrue();
        mService.bondStateChanged(mLeftDevice, BluetoothDevice.BOND_NONE);
        mLooper.dispatchAll();
        assertThat(mService.getDevices().contains(mLeftDevice)).isFalse();

        // stack event: CONNECTION_STATE_DISCONNECTING - state machine should not be created
        generateUnexpectedConnectionMessageFromNative(mLeftDevice, STATE_DISCONNECTING);
        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_DISCONNECTED);
        assertThat(mService.getDevices().contains(mLeftDevice)).isFalse();

        // stack event: CONNECTION_STATE_DISCONNECTED - state machine should not be created
        generateUnexpectedConnectionMessageFromNative(mLeftDevice, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_DISCONNECTED);
        assertThat(mService.getDevices().contains(mLeftDevice)).isFalse();
    }

    /**
     * Test that a state machine in DISCONNECTED state is removed only after the device is unbond.
     */
    @Test
    public void testDeleteStateMachineUnbondEvents() {
        // Update the device priority so okToConnect() returns true
        doReturn(CONNECTION_POLICY_ALLOWED)
                .when(mAdapterService)
                .getProfileConnectionPolicy(mLeftDevice, BluetoothProfile.LE_AUDIO);
        doReturn(CONNECTION_POLICY_FORBIDDEN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(mRightDevice, BluetoothProfile.LE_AUDIO);
        doReturn(CONNECTION_POLICY_FORBIDDEN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(mSingleDevice, BluetoothProfile.LE_AUDIO);

        // Create device descriptor with connect request
        assertThat(mService.connect(mLeftDevice)).isTrue();
        mLooper.dispatchAll();

        // LeAudio stack event: CONNECTION_STATE_CONNECTING - state machine should be created
        generateConnectionMessageFromNative(mLeftDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_CONNECTING);
        assertThat(mService.getDevices().contains(mLeftDevice)).isTrue();
        // Device unbond - state machine is not removed
        mService.bondStateChanged(mLeftDevice, BluetoothDevice.BOND_NONE);
        mLooper.dispatchAll();
        assertThat(mService.getDevices().contains(mLeftDevice)).isTrue();
        verifyConnectionStateIntent(mLeftDevice, STATE_DISCONNECTED, STATE_CONNECTING);

        // LeAudio stack event: CONNECTION_STATE_CONNECTED - state machine is not removed
        mService.bondStateChanged(mLeftDevice, BOND_BONDED);
        mLooper.dispatchAll();
        generateConnectionMessageFromNative(mLeftDevice, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getDevices().contains(mLeftDevice)).isTrue();
        // Device unbond - state machine is not removed
        mService.bondStateChanged(mLeftDevice, BluetoothDevice.BOND_NONE);
        mLooper.dispatchAll();
        assertThat(mService.getDevices().contains(mLeftDevice)).isTrue();
        verifyConnectionStateIntent(mLeftDevice, STATE_DISCONNECTING, STATE_CONNECTED);
        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_DISCONNECTING);
        assertThat(mService.getDevices().contains(mLeftDevice)).isTrue();

        // LeAudio stack event: CONNECTION_STATE_DISCONNECTING - state machine is not removed
        mService.bondStateChanged(mLeftDevice, BOND_BONDED);
        mLooper.dispatchAll();
        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_DISCONNECTING);
        // Device unbond - state machine is not removed
        mService.bondStateChanged(mLeftDevice, BluetoothDevice.BOND_NONE);
        mLooper.dispatchAll();
        assertThat(mService.getDevices().contains(mLeftDevice)).isTrue();

        // LeAudio stack event: CONNECTION_STATE_DISCONNECTED - state machine is not removed
        mService.bondStateChanged(mLeftDevice, BOND_BONDED);
        mLooper.dispatchAll();
        generateConnectionMessageFromNative(mLeftDevice, STATE_DISCONNECTED, STATE_DISCONNECTING);
        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_DISCONNECTED);
        assertThat(mService.getDevices().contains(mLeftDevice)).isTrue();
        // Device unbond - state machine is removed
        mService.bondStateChanged(mLeftDevice, BluetoothDevice.BOND_NONE);
        mLooper.dispatchAll();
        assertThat(mService.getDevices().contains(mLeftDevice)).isFalse();
    }

    /**
     * Test that a CONNECTION_STATE_DISCONNECTED Le Audio stack event will remove the state machine
     * only if the device is unbond.
     */
    @Test
    public void testDeleteStateMachineDisconnectEvents() {
        // Update the device priority so okToConnect() returns true
        doReturn(CONNECTION_POLICY_ALLOWED)
                .when(mAdapterService)
                .getProfileConnectionPolicy(mLeftDevice, BluetoothProfile.LE_AUDIO);
        doReturn(CONNECTION_POLICY_FORBIDDEN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(mRightDevice, BluetoothProfile.LE_AUDIO);
        doReturn(CONNECTION_POLICY_FORBIDDEN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(mSingleDevice, BluetoothProfile.LE_AUDIO);

        // Create device descriptor with connect request
        assertThat(mService.connect(mLeftDevice)).isTrue();
        mLooper.dispatchAll();

        // LeAudio stack event: CONNECTION_STATE_CONNECTING - state machine should be created
        generateConnectionMessageFromNative(mLeftDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_CONNECTING);
        assertThat(mService.getDevices().contains(mLeftDevice)).isTrue();

        // LeAudio stack event: CONNECTION_STATE_DISCONNECTED - state machine is not removed
        generateConnectionMessageFromNative(mLeftDevice, STATE_DISCONNECTED, STATE_CONNECTING);
        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_DISCONNECTED);
        assertThat(mService.getDevices().contains(mLeftDevice)).isTrue();

        // LeAudio stack event: CONNECTION_STATE_CONNECTING - state machine remains
        generateConnectionMessageFromNative(mLeftDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_CONNECTING);
        assertThat(mService.getDevices().contains(mLeftDevice)).isTrue();

        // Device bond state marked as unbond - state machine is not removed
        doReturn(BluetoothDevice.BOND_NONE)
                .when(mAdapterService)
                .getBondState(any(BluetoothDevice.class));
        assertThat(mService.getDevices().contains(mLeftDevice)).isTrue();

        // LeAudio stack event: CONNECTION_STATE_DISCONNECTED - state machine is removed
        generateConnectionMessageFromNative(mLeftDevice, STATE_DISCONNECTED, STATE_CONNECTING);
        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_DISCONNECTED);
        assertThat(mService.getDevices().contains(mLeftDevice)).isFalse();
    }

    private void connectDevice(BluetoothDevice device) {
        LeAudioStackEvent connCompletedEvent;

        List<BluetoothDevice> prevConnectedDevices = mService.getConnectedDevices();

        doReturn(CONNECTION_POLICY_ALLOWED)
                .when(mAdapterService)
                .getProfileConnectionPolicy(device, BluetoothProfile.LE_AUDIO);
        doReturn(true).when(mNativeInterface).connectLeAudio(device);
        doReturn(true).when(mNativeInterface).disconnectLeAudio(device);

        // Send a connect request
        assertThat(mService.connect(device)).isTrue();
        mLooper.dispatchAll();

        // Verify the connection state broadcast, and that we are in Connecting state
        verifyConnectionStateIntent(device, STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(device)).isEqualTo(STATE_CONNECTING);

        // Send a message to trigger connection completed
        connCompletedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connCompletedEvent.device = device;
        connCompletedEvent.valueInt1 = LeAudioStackEvent.CONNECTION_STATE_CONNECTED;
        mService.messageFromNative(connCompletedEvent);
        mLooper.dispatchAll();

        // Verify the connection state broadcast, and that we are in Connected state
        verifyConnectionStateIntent(device, STATE_CONNECTED, STATE_CONNECTING);
        assertThat(mService.getConnectionState(device)).isEqualTo(STATE_CONNECTED);

        // Verify that the device is in the list of connected devices
        assertThat(mService.getConnectedDevices().contains(device)).isTrue();
        // Verify the list of previously connected devices
        for (BluetoothDevice prevDevice : prevConnectedDevices) {
            assertThat(mService.getConnectedDevices().contains(prevDevice)).isTrue();
        }
    }

    private void generateConnectionMessageFromNative(
            BluetoothDevice device, int newConnectionState, int oldConnectionState) {
        LeAudioStackEvent stackEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        stackEvent.device = device;
        stackEvent.valueInt1 = newConnectionState;
        mService.messageFromNative(stackEvent);
        mLooper.dispatchAll();
        // Verify the connection state broadcast
        verifyConnectionStateIntent(device, newConnectionState, oldConnectionState);
    }

    private void generateUnexpectedConnectionMessageFromNative(
            BluetoothDevice device, int newConnectionState) {
        LeAudioStackEvent stackEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        stackEvent.device = device;
        stackEvent.valueInt1 = newConnectionState;
        mService.messageFromNative(stackEvent);
        mLooper.dispatchAll();
        // Verify the connection state broadcast
        verifyNoIntentSent();
    }

    private void generateGroupNodeAdded(BluetoothDevice device, int groupId) {
        LeAudioStackEvent nodeGroupAdded =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_NODE_STATUS_CHANGED);
        nodeGroupAdded.device = device;
        nodeGroupAdded.valueInt1 = groupId;
        nodeGroupAdded.valueInt2 = LeAudioStackEvent.GROUP_NODE_ADDED;
        mService.messageFromNative(nodeGroupAdded);
        mLooper.dispatchAll();
    }

    private void generateGroupNodeRemoved(BluetoothDevice device, int groupId) {
        LeAudioStackEvent nodeGroupRemoved =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_NODE_STATUS_CHANGED);
        nodeGroupRemoved.device = device;
        nodeGroupRemoved.valueInt1 = groupId;
        nodeGroupRemoved.valueInt2 = LeAudioStackEvent.GROUP_NODE_REMOVED;
        mService.messageFromNative(nodeGroupRemoved);
        mLooper.dispatchAll();
    }

    /** Test setting connection policy */
    @Test
    public void testSetConnectionPolicy() {
        doReturn(true).when(mVolumeControlService).setConnectionPolicy(any(), anyInt());
        doReturn(true).when(mCsipSetCoordinatorService).setConnectionPolicy(any(), anyInt());
        doReturn(true).when(mHapClientService).setConnectionPolicy(any(), anyInt());
        doReturn(true).when(mBassClientService).setConnectionPolicy(any(), anyInt());
        doReturn(CONNECTION_POLICY_UNKNOWN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(mSingleDevice, BluetoothProfile.LE_AUDIO);

        assertThat(mService.setConnectionPolicy(mSingleDevice, CONNECTION_POLICY_ALLOWED)).isTrue();
        mLooper.dispatchAll();

        // Verify connection policy for CSIP and VCP are also set
        verify(mVolumeControlService).setConnectionPolicy(mSingleDevice, CONNECTION_POLICY_ALLOWED);
        verify(mCsipSetCoordinatorService)
                .setConnectionPolicy(mSingleDevice, CONNECTION_POLICY_ALLOWED);
        verify(mHapClientService).setConnectionPolicy(mSingleDevice, CONNECTION_POLICY_ALLOWED);
        if (BluetoothProperties.isProfileBapBroadcastAssistEnabled().orElse(false)) {
            verify(mBassClientService)
                    .setConnectionPolicy(mSingleDevice, CONNECTION_POLICY_ALLOWED);
        }
        // Verify the connection state broadcast, and that we are in Connecting state
        verifyConnectionStateIntent(mSingleDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mSingleDevice)).isEqualTo(STATE_CONNECTING);

        LeAudioStackEvent connCompletedEvent;
        // Send a message to trigger connection completed
        connCompletedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connCompletedEvent.device = mSingleDevice;
        connCompletedEvent.valueInt1 = LeAudioStackEvent.CONNECTION_STATE_CONNECTED;
        mService.messageFromNative(connCompletedEvent);
        mLooper.dispatchAll();

        // Verify the connection state broadcast, and that we are in Connected state
        verifyConnectionStateIntent(mSingleDevice, STATE_CONNECTED, STATE_CONNECTING);
        assertThat(mService.getConnectionState(mSingleDevice)).isEqualTo(STATE_CONNECTED);

        // Set connection policy to forbidden
        assertThat(mService.setConnectionPolicy(mSingleDevice, CONNECTION_POLICY_FORBIDDEN))
                .isTrue();
        mLooper.dispatchAll();

        // Verify connection policy for CSIP and VCP are also set
        verify(mVolumeControlService)
                .setConnectionPolicy(mSingleDevice, CONNECTION_POLICY_FORBIDDEN);
        verify(mCsipSetCoordinatorService)
                .setConnectionPolicy(mSingleDevice, CONNECTION_POLICY_FORBIDDEN);
        verify(mHapClientService).setConnectionPolicy(mSingleDevice, CONNECTION_POLICY_FORBIDDEN);
        if (BluetoothProperties.isProfileBapBroadcastAssistEnabled().orElse(false)) {
            verify(mBassClientService)
                    .setConnectionPolicy(mSingleDevice, CONNECTION_POLICY_FORBIDDEN);
        }
        // Verify the connection state broadcast, and that we are in Connecting state
        verifyConnectionStateIntent(mSingleDevice, STATE_DISCONNECTING, STATE_CONNECTED);
        assertThat(mService.getConnectionState(mSingleDevice)).isEqualTo(STATE_DISCONNECTING);

        // Send a message to trigger disconnection completed
        connCompletedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connCompletedEvent.device = mSingleDevice;
        connCompletedEvent.valueInt1 = LeAudioStackEvent.CONNECTION_STATE_DISCONNECTED;
        mService.messageFromNative(connCompletedEvent);
        mLooper.dispatchAll();

        // Verify the connection state broadcast, and that we are in Disconnected state
        verifyConnectionStateIntent(mSingleDevice, STATE_DISCONNECTED, STATE_DISCONNECTING);
        assertThat(mService.getConnectionState(mSingleDevice)).isEqualTo(STATE_DISCONNECTED);
    }

    /**
     * Verify that LE Audio service does not set profile connection policy to ALLOWED for
     * non-available services.
     */
    @Test
    public void testSetConnectionPolicyLeOnlyUUID() {
        doReturn(new ParcelUuid[] {BluetoothUuid.LE_AUDIO})
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
        // Make LE Audio related services setConnectionPolicy() method return true.
        // These should NOT be called if not available
        doReturn(true).when(mVolumeControlService).setConnectionPolicy(any(), anyInt());
        doReturn(true).when(mCsipSetCoordinatorService).setConnectionPolicy(any(), anyInt());
        doReturn(true).when(mHapClientService).setConnectionPolicy(any(), anyInt());
        doReturn(true).when(mBassClientService).setConnectionPolicy(any(), anyInt());
        doReturn(CONNECTION_POLICY_UNKNOWN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(mSingleDevice, BluetoothProfile.LE_AUDIO);

        assertThat(mService.setConnectionPolicy(mSingleDevice, CONNECTION_POLICY_ALLOWED)).isTrue();
        mLooper.dispatchAll();

        // Verify connection policy for CSIP and VCP are also set to FORBIDDEN
        verify(mVolumeControlService, never())
                .setConnectionPolicy(mSingleDevice, CONNECTION_POLICY_ALLOWED);
        verify(mCsipSetCoordinatorService, never())
                .setConnectionPolicy(mSingleDevice, CONNECTION_POLICY_ALLOWED);
        verify(mHapClientService, never())
                .setConnectionPolicy(mSingleDevice, CONNECTION_POLICY_ALLOWED);
        if (BluetoothProperties.isProfileBapBroadcastAssistEnabled().orElse(false)) {
            verify(mBassClientService, never())
                    .setConnectionPolicy(mSingleDevice, CONNECTION_POLICY_ALLOWED);
        }
    }

    private void setTestDeviceIntoConnectingState(BluetoothDevice device, int groupId) {
        assertThat(mService.connect(device)).isTrue();
        mLooper.dispatchAll();

        // Make device bonded
        mBondedDevices.add(device);

        LeAudioStackEvent nodeGroupAdded =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_NODE_STATUS_CHANGED);
        nodeGroupAdded.device = device;
        nodeGroupAdded.valueInt1 = groupId;
        nodeGroupAdded.valueInt2 = LeAudioStackEvent.GROUP_NODE_ADDED;
        mService.messageFromNative(nodeGroupAdded);
        mLooper.dispatchAll();

        verifyConnectionStateIntent(device, STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(device)).isEqualTo(STATE_CONNECTING);
    }

    private void connectTestDevice(BluetoothDevice device, int GroupId) {
        List<BluetoothDevice> prevConnectedDevices = mService.getConnectedDevices();

        assertThat(mService.connect(device)).isTrue();
        mLooper.dispatchAll();

        // Make device bonded
        mBondedDevices.add(device);

        LeAudioStackEvent nodeGroupAdded =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_NODE_STATUS_CHANGED);
        nodeGroupAdded.device = device;
        nodeGroupAdded.valueInt1 = GroupId;
        nodeGroupAdded.valueInt2 = LeAudioStackEvent.GROUP_NODE_ADDED;
        mService.messageFromNative(nodeGroupAdded);
        mLooper.dispatchAll();

        verifyConnectionStateIntent(device, STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(device)).isEqualTo(STATE_CONNECTING);

        // Use connected event to indicate that device is connected
        LeAudioStackEvent connCompletedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connCompletedEvent.device = device;
        connCompletedEvent.valueInt1 = LeAudioStackEvent.CONNECTION_STATE_CONNECTED;
        mService.messageFromNative(connCompletedEvent);
        mLooper.dispatchAll();

        verifyConnectionStateIntent(device, STATE_CONNECTED, STATE_CONNECTING);
        assertThat(mService.getConnectionState(device)).isEqualTo(STATE_CONNECTED);

        // Verify that the device is in the list of connected devices
        assertThat(mService.getConnectedDevices()).contains(device);
        assertThat(mService.getConnectedDevices()).containsAtLeastElementsIn(prevConnectedDevices);
    }

    /** Test adding node */
    @Test
    public void testGroupAddRemoveNode() {
        doReturn(true).when(mNativeInterface).groupAddNode(TEST_GROUP_ID, mSingleDevice);
        doReturn(true).when(mNativeInterface).groupRemoveNode(TEST_GROUP_ID, mSingleDevice);

        assertThat(mService.groupAddNode(TEST_GROUP_ID, mSingleDevice)).isTrue();
        assertThat(mService.groupRemoveNode(TEST_GROUP_ID, mSingleDevice)).isTrue();
    }

    /** Test setting active device group with Ringtone context */
    @Test
    public void testSetActiveDeviceGroup() {
        /* AUDIO_DIRECTION_OUTPUT_BIT = 0x01 */
        int direction = 1;
        int availableContexts = 5 + BluetoothLeAudio.CONTEXT_TYPE_RINGTONE;

        // Not connected device
        assertThat(mService.setActiveDevice(mSingleDevice)).isFalse();

        // Connect device
        connectTestDevice(mSingleDevice, TEST_GROUP_ID);

        // Add location support
        injectAudioConfChanged(mSingleDevice, TEST_GROUP_ID, availableContexts, direction);

        assertThat(mService.setActiveDevice(mSingleDevice)).isTrue();
        mInOrder.verify(mNativeInterface).groupSetActive(TEST_GROUP_ID);

        // Set group and device as active
        LeAudioStackEvent groupStatusChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        groupStatusChangedEvent.valueInt1 = TEST_GROUP_ID;
        groupStatusChangedEvent.valueInt2 = LeAudioStackEvent.GROUP_STATUS_ACTIVE;
        mService.messageFromNative(groupStatusChangedEvent);
        mLooper.dispatchAll();

        verify(mTbsService).setInbandRingtoneSupport(mSingleDevice);

        // no active device
        assertThat(mService.removeActiveDevice(false)).isTrue();
        mInOrder.verify(mNativeInterface).groupSetActive(BluetoothLeAudio.GROUP_ID_INVALID);

        // Set group and device as inactive
        groupStatusChangedEvent.valueInt2 = LeAudioStackEvent.GROUP_STATUS_INACTIVE;
        mService.messageFromNative(groupStatusChangedEvent);
        mLooper.dispatchAll();

        verify(mTbsService).clearInbandRingtoneSupport(mSingleDevice);
    }

    /** Test setting active device group for already active group */
    @Test
    public void testSetActiveDeviceGroupTwice() {
        /* AUDIO_DIRECTION_OUTPUT_BIT = 0x01 */
        int direction = 1;
        int availableContexts = 5 + BluetoothLeAudio.CONTEXT_TYPE_RINGTONE;

        // Not connected device
        assertThat(mService.setActiveDevice(mSingleDevice)).isFalse();

        // Connect device
        connectTestDevice(mSingleDevice, TEST_GROUP_ID);

        // Add location support
        injectAudioConfChanged(mSingleDevice, TEST_GROUP_ID, availableContexts, direction);

        assertThat(mService.setActiveDevice(mSingleDevice)).isTrue();
        mInOrder.verify(mNativeInterface).groupSetActive(TEST_GROUP_ID);

        // Set group and device as active
        LeAudioStackEvent groupStatusChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        groupStatusChangedEvent.valueInt1 = TEST_GROUP_ID;
        groupStatusChangedEvent.valueInt2 = LeAudioStackEvent.GROUP_STATUS_ACTIVE;
        mService.messageFromNative(groupStatusChangedEvent);
        mLooper.dispatchAll();

        verify(mTbsService).setInbandRingtoneSupport(mSingleDevice);

        /* Expect 2 calls to Audio Manager - one for output  as this is Ringtone use case */
        mInOrder.verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        any(), any(), any(BluetoothProfileConnectionInfo.class));
        /* Since LeAudioService called AudioManager - assume Audio manager calls properly callback
         * mAudioManager.onAudioDeviceAdded
         */
        injectAudioDeviceAdded(mSingleDevice, AudioDeviceInfo.TYPE_BLE_HEADSET, true, false, true);

        // set active device again
        assertThat(mService.setActiveDevice(mSingleDevice)).isTrue();
        mInOrder.verify(mNativeInterface, never()).groupSetActive(TEST_GROUP_ID);

        verifyActiveDeviceStateIntent(mSingleDevice);
    }

    /** Test setting active devices from the same group */
    @Test
    public void testSetActiveDevicesFromSameGroup() {
        /* AUDIO_DIRECTION_OUTPUT_BIT = 0x01 */
        /* AUDIO_DIRECTION_INPUT_BIT = 0x02 */
        int direction = 3;
        int availableContexts = 5 + BluetoothLeAudio.CONTEXT_TYPE_RINGTONE;

        ArgumentCaptor<BluetoothProfileConnectionInfo> connectionInfoArgumentCaptor =
                ArgumentCaptor.forClass(BluetoothProfileConnectionInfo.class);

        // Not connected device
        assertThat(mService.setActiveDevice(mSingleDevice)).isFalse();

        // Connect device
        connectTestDevice(mLeftDevice, TEST_GROUP_ID);
        connectTestDevice(mRightDevice, TEST_GROUP_ID);

        // Add location support
        injectAudioConfChanged(mSingleDevice, TEST_GROUP_ID, availableContexts, direction);

        assertThat(mService.setActiveDevice(mLeftDevice)).isTrue();
        mInOrder.verify(mNativeInterface).groupSetActive(TEST_GROUP_ID);

        // Set group and device as active
        LeAudioStackEvent groupStatusChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        groupStatusChangedEvent.valueInt1 = TEST_GROUP_ID;
        groupStatusChangedEvent.valueInt2 = LeAudioStackEvent.GROUP_STATUS_ACTIVE;
        mService.messageFromNative(groupStatusChangedEvent);
        mLooper.dispatchAll();

        mInOrder.verify(mAudioManager, times(2))
                .handleBluetoothActiveDeviceChanged(
                        eq(mLeftDevice), eq(null), connectionInfoArgumentCaptor.capture());
        List<BluetoothProfileConnectionInfo> connInfos =
                connectionInfoArgumentCaptor.getAllValues();
        assertThat(connInfos).hasSize(2);
        assertThat(connInfos.get(0).isLeOutput()).isTrue();
        assertThat(connInfos.get(1).isLeOutput()).isFalse();

        assertThat(mService.setActiveDevice(mRightDevice)).isTrue();
        mInOrder.verify(mAudioManager, never())
                .handleBluetoothActiveDeviceChanged(
                        any(), any(), any(BluetoothProfileConnectionInfo.class));
        connInfos = connectionInfoArgumentCaptor.getAllValues();
        assertThat(connInfos).hasSize(2);
    }

    /** Test setting active device group with not available contexts */
    @Test
    public void testSetActiveDeviceGroupWithNoContextTypes() {
        /* AUDIO_DIRECTION_OUTPUT_BIT = 0x01 */
        int direction = 1;
        int availableContexts = 0;

        // Not connected device
        assertThat(mService.setActiveDevice(mSingleDevice)).isFalse();

        // Connect device
        connectTestDevice(mSingleDevice, TEST_GROUP_ID);

        // Add location support
        injectAudioConfChanged(mSingleDevice, TEST_GROUP_ID, availableContexts, direction);

        assertThat(mService.setActiveDevice(mSingleDevice)).isFalse();
        mInOrder.verify(mNativeInterface, never()).groupSetActive(TEST_GROUP_ID);
    }

    /** Test switching active groups */
    @Test
    public void testSwitchActiveGroups() {
        /* AUDIO_DIRECTION_OUTPUT_BIT = 0x01 */
        int direction = 1;
        int availableContexts = 5 + BluetoothLeAudio.CONTEXT_TYPE_RINGTONE;

        InOrder tbsOrder = inOrder(mTbsService);

        // Not connected device
        assertThat(mService.setActiveDevice(mSingleDevice)).isFalse();

        // Define some return values needed in test
        doReturn(-1).when(mVolumeControlService).getAudioDeviceGroupVolume(anyInt());

        // Connect both
        connectTestDevice(mSingleDevice, TEST_GROUP_ID);
        connectTestDevice(mSingleDevice_2, TEST_GROUP_ID2);

        // Add location support
        injectAudioConfChanged(mSingleDevice, TEST_GROUP_ID, availableContexts, direction);
        injectAudioConfChanged(mSingleDevice, TEST_GROUP_ID2, availableContexts, direction);

        assertThat(mService.setActiveDevice(mSingleDevice)).isTrue();
        mInOrder.verify(mNativeInterface).groupSetActive(TEST_GROUP_ID);

        // Set group and device as active
        LeAudioStackEvent groupStatusChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        groupStatusChangedEvent.valueInt1 = TEST_GROUP_ID;
        groupStatusChangedEvent.valueInt2 = LeAudioStackEvent.GROUP_STATUS_ACTIVE;
        mService.messageFromNative(groupStatusChangedEvent);
        mLooper.dispatchAll();

        tbsOrder.verify(mTbsService).setInbandRingtoneSupport(mSingleDevice);
        tbsOrder.verify(mTbsService, never()).setInbandRingtoneSupport(mSingleDevice_2);
        tbsOrder.verify(mTbsService, never()).clearInbandRingtoneSupport(any());

        ArgumentCaptor<BluetoothProfileConnectionInfo> connectionInfoArgumentCaptor =
                ArgumentCaptor.forClass(BluetoothProfileConnectionInfo.class);

        mInOrder.verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(mSingleDevice), eq(null), connectionInfoArgumentCaptor.capture());

        injectAudioDeviceAdded(mSingleDevice, AudioDeviceInfo.TYPE_BLE_HEADSET, true, false, true);

        BluetoothProfileConnectionInfo connInfo = connectionInfoArgumentCaptor.getValue();
        assertThat(connInfo.isSuppressNoisyIntent()).isTrue();

        // set active device again
        assertThat(mService.setActiveDevice(mSingleDevice_2)).isTrue();
        mInOrder.verify(mNativeInterface).groupSetActive(TEST_GROUP_ID2);

        // First wait for ACTIVE state will be sent from native
        LeAudioStackEvent activeGroupState =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        activeGroupState.valueInt1 = TEST_GROUP_ID2;
        activeGroupState.valueInt2 = LeAudioStackEvent.GROUP_STATUS_ACTIVE;
        activeGroupState.valueInt3 = TEST_GROUP_ID;
        mService.messageFromNative(activeGroupState);
        mLooper.dispatchAll();

        tbsOrder.verify(mTbsService).setInbandRingtoneSupport(mSingleDevice_2);
        tbsOrder.verify(mTbsService).clearInbandRingtoneSupport(mSingleDevice);
        tbsOrder.verify(mTbsService, never()).setInbandRingtoneSupport(mSingleDevice);

        mInOrder.verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(mSingleDevice_2),
                        eq(mSingleDevice),
                        connectionInfoArgumentCaptor.capture());
        connInfo = connectionInfoArgumentCaptor.getValue();
        assertThat(connInfo.isSuppressNoisyIntent()).isTrue();

        injectAudioDeviceRemoved(
                mSingleDevice, AudioDeviceInfo.TYPE_BLE_HEADSET, true, false, false);
        injectAudioDeviceAdded(
                mSingleDevice_2, AudioDeviceInfo.TYPE_BLE_HEADSET, true, false, true);
        mInOrder.verify(mNativeInterface, never())
                .groupSetActive(BluetoothLeAudio.GROUP_ID_INVALID);
    }

    /** Test switching active groups */
    @Test
    public void testAudioFrameworkAutonomousDeviceRemoval() {
        /* AUDIO_DIRECTION_OUTPUT_BIT = 0x01 */
        int direction = 1;
        int availableContexts = 5 + BluetoothLeAudio.CONTEXT_TYPE_RINGTONE;

        // Not connected device
        assertThat(mService.setActiveDevice(mSingleDevice)).isFalse();

        // Define some return values needed in test
        doReturn(-1).when(mVolumeControlService).getAudioDeviceGroupVolume(anyInt());
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));

        // Connect both
        connectTestDevice(mSingleDevice, TEST_GROUP_ID);

        // Add location support
        injectAudioConfChanged(mSingleDevice, TEST_GROUP_ID, availableContexts, direction);

        assertThat(mService.setActiveDevice(mSingleDevice)).isTrue();
        mInOrder.verify(mNativeInterface).groupSetActive(TEST_GROUP_ID);

        // Set group and device as active
        LeAudioStackEvent groupStatusChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        groupStatusChangedEvent.valueInt1 = TEST_GROUP_ID;
        groupStatusChangedEvent.valueInt2 = LeAudioStackEvent.GROUP_STATUS_ACTIVE;
        mService.messageFromNative(groupStatusChangedEvent);
        mLooper.dispatchAll();

        verify(mTbsService).setInbandRingtoneSupport(mSingleDevice);

        ArgumentCaptor<BluetoothProfileConnectionInfo> connectionInfoArgumentCaptor =
                ArgumentCaptor.forClass(BluetoothProfileConnectionInfo.class);

        mInOrder.verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(mSingleDevice), eq(null), connectionInfoArgumentCaptor.capture());

        injectAudioDeviceAdded(mSingleDevice, AudioDeviceInfo.TYPE_BLE_HEADSET, true, false, true);

        BluetoothProfileConnectionInfo connInfo = connectionInfoArgumentCaptor.getValue();
        assertThat(connInfo.isSuppressNoisyIntent()).isTrue();

        // AudioManager removes audio device
        // We just ignore it as AudioManager is going to restart and re-add devices
        injectAudioDeviceRemoved(
                mSingleDevice, AudioDeviceInfo.TYPE_BLE_HEADSET, true, false, false);

        mInOrder.verify(mNativeInterface, never())
                .groupSetActive(BluetoothLeAudio.GROUP_ID_INVALID);
    }

    /** Test setting active device group without Ringtone context */
    @Test
    public void testSetActiveDeviceGroupWithoutRingtoneContext() {
        /* AUDIO_DIRECTION_OUTPUT_BIT = 0x01 */
        int direction = 1;
        int availableContexts = 5;

        // Not connected device
        assertThat(mService.setActiveDevice(mSingleDevice)).isFalse();

        // Connect device
        connectTestDevice(mSingleDevice, TEST_GROUP_ID);

        // Add location support
        injectAudioConfChanged(mSingleDevice, TEST_GROUP_ID, availableContexts, direction);

        assertThat(mService.setActiveDevice(mSingleDevice)).isTrue();
        mInOrder.verify(mNativeInterface).groupSetActive(TEST_GROUP_ID);

        // Set group and device as active
        LeAudioStackEvent groupStatusChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        groupStatusChangedEvent.valueInt1 = TEST_GROUP_ID;
        groupStatusChangedEvent.valueInt2 = LeAudioStackEvent.GROUP_STATUS_ACTIVE;
        mService.messageFromNative(groupStatusChangedEvent);
        mLooper.dispatchAll();

        // no active device
        assertThat(mService.removeActiveDevice(false)).isTrue();
        mInOrder.verify(mNativeInterface).groupSetActive(BluetoothLeAudio.GROUP_ID_INVALID);

        // Set group and device as inactive
        groupStatusChangedEvent.valueInt2 = LeAudioStackEvent.GROUP_STATUS_INACTIVE;
        mService.messageFromNative(groupStatusChangedEvent);
        mLooper.dispatchAll();

        verify(mTbsService, never()).clearInbandRingtoneSupport(mSingleDevice);
    }

    /** Test getting active device */
    @Test
    public void testGetActiveDevices() {
        /* AUDIO_DIRECTION_OUTPUT_BIT = 0x01 */
        int direction = 1;
        int availableContexts = 5;
        int nodeStatus = LeAudioStackEvent.GROUP_NODE_ADDED;
        int groupStatus = LeAudioStackEvent.GROUP_STATUS_ACTIVE;

        // Single active device
        connectTestDevice(mSingleDevice, TEST_GROUP_ID);

        // Add device to group
        LeAudioStackEvent nodeStatusChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_NODE_STATUS_CHANGED);
        nodeStatusChangedEvent.device = mSingleDevice;
        nodeStatusChangedEvent.valueInt1 = TEST_GROUP_ID;
        nodeStatusChangedEvent.valueInt2 = nodeStatus;
        mService.messageFromNative(nodeStatusChangedEvent);
        mLooper.dispatchAll();

        assertThat(mService.setActiveDevice(mSingleDevice)).isFalse();

        // Add location support
        injectAudioConfChanged(mSingleDevice, TEST_GROUP_ID, availableContexts, direction);

        assertThat(mService.setActiveDevice(mSingleDevice)).isTrue();

        // Set group and device as active
        LeAudioStackEvent groupStatusChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        groupStatusChangedEvent.device = mSingleDevice;
        groupStatusChangedEvent.valueInt1 = TEST_GROUP_ID;
        groupStatusChangedEvent.valueInt2 = groupStatus;
        mService.messageFromNative(groupStatusChangedEvent);
        mLooper.dispatchAll();

        assertThat(mService.getActiveDevices().contains(mSingleDevice)).isTrue();

        // Remove device from group
        groupStatusChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_NODE_STATUS_CHANGED);
        groupStatusChangedEvent.device = mSingleDevice;
        groupStatusChangedEvent.valueInt1 = TEST_GROUP_ID;
        groupStatusChangedEvent.valueInt2 = LeAudioStackEvent.GROUP_NODE_REMOVED;
        mService.messageFromNative(groupStatusChangedEvent);
        mLooper.dispatchAll();

        assertThat(mService.getActiveDevices().contains(mSingleDevice)).isFalse();
    }

    private void injectGroupStatusChange(int groupId, int groupStatus) {
        int eventType = LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED;
        LeAudioStackEvent groupStatusChangedEvent = new LeAudioStackEvent(eventType);
        groupStatusChangedEvent.valueInt1 = groupId;
        groupStatusChangedEvent.valueInt2 = groupStatus;
        mService.messageFromNative(groupStatusChangedEvent);
        mLooper.dispatchAll();
    }

    private void injectGroupStreamStatusChange(int groupId, int groupStreamStatus) {
        int eventType = LeAudioStackEvent.EVENT_TYPE_GROUP_STREAM_STATUS_CHANGED;
        LeAudioStackEvent groupStreamStatusChangedEvent = new LeAudioStackEvent(eventType);
        groupStreamStatusChangedEvent.valueInt1 = groupId;
        groupStreamStatusChangedEvent.valueInt2 = groupStreamStatus;
        mService.messageFromNative(groupStreamStatusChangedEvent);
        mLooper.dispatchAll();
    }

    private void injectAudioConfChanged(
            BluetoothDevice device, int groupId, Integer availableContexts, int direction) {
        int snkAudioLocation = 3;
        int srcAudioLocation = 4;
        int eventType = LeAudioStackEvent.EVENT_TYPE_AUDIO_CONF_CHANGED;

        // Add device to group
        LeAudioStackEvent audioConfChangedEvent = new LeAudioStackEvent(eventType);
        audioConfChangedEvent.device = device;
        audioConfChangedEvent.valueInt1 = direction;
        audioConfChangedEvent.valueInt2 = groupId;
        audioConfChangedEvent.valueInt3 = snkAudioLocation;
        audioConfChangedEvent.valueInt4 = srcAudioLocation;
        audioConfChangedEvent.valueInt5 = availableContexts;
        mService.messageFromNative(audioConfChangedEvent);
        mLooper.dispatchAll();
    }

    /** Test group direction changed */
    @Test
    public void testGroupDirectionChanged_AudioConfChangedActiveGroup() {
        int testVolume = 100;

        ArgumentCaptor<BluetoothProfileConnectionInfo> testConnectioInfoCapture =
                ArgumentCaptor.forClass(BluetoothProfileConnectionInfo.class);

        doReturn(testVolume).when(mVolumeControlService).getAudioDeviceGroupVolume(TEST_GROUP_ID);

        connectTestDevice(mSingleDevice, TEST_GROUP_ID);
        injectAudioConfChanged(
                mSingleDevice,
                TEST_GROUP_ID,
                BluetoothLeAudio.CONTEXT_TYPE_MEDIA | BluetoothLeAudio.CONTEXT_TYPE_CONVERSATIONAL,
                3);
        injectGroupStatusChange(TEST_GROUP_ID, BluetoothLeAudio.GROUP_STATUS_ACTIVE);

        mInOrder.verify(mAudioManager, times(2))
                .handleBluetoothActiveDeviceChanged(
                        eq(mSingleDevice), eq(null), testConnectioInfoCapture.capture());

        injectAudioDeviceAdded(mSingleDevice, AudioDeviceInfo.TYPE_BLE_HEADSET, true, false, true);
        injectAudioDeviceAdded(mSingleDevice, AudioDeviceInfo.TYPE_BLE_HEADSET, false, true, false);

        /* Verify input and output has been connected to AF*/
        List<BluetoothProfileConnectionInfo> connInfos = testConnectioInfoCapture.getAllValues();
        assertThat(connInfos).hasSize(2);
        assertThat(connInfos.get(0).isLeOutput()).isTrue();
        assertThat(connInfos.get(1).isLeOutput()).isFalse();

        // Remove source direction
        injectAudioConfChanged(
                mSingleDevice,
                TEST_GROUP_ID,
                BluetoothLeAudio.CONTEXT_TYPE_MEDIA | BluetoothLeAudio.CONTEXT_TYPE_CONVERSATIONAL,
                1);

        mInOrder.verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(null), eq(mSingleDevice), testConnectioInfoCapture.capture());

        injectAudioDeviceAdded(mSingleDevice, AudioDeviceInfo.TYPE_BLE_HEADSET, true, false, false);
        injectAudioDeviceRemoved(
                mSingleDevice, AudioDeviceInfo.TYPE_BLE_HEADSET, false, true, false);

        connInfos = testConnectioInfoCapture.getAllValues();
        assertThat(connInfos).hasSize(3);
        assertThat(connInfos.get(2).isLeOutput()).isFalse();

        // remove Sink and add Source back

        injectAudioConfChanged(
                mSingleDevice,
                TEST_GROUP_ID,
                BluetoothLeAudio.CONTEXT_TYPE_MEDIA | BluetoothLeAudio.CONTEXT_TYPE_CONVERSATIONAL,
                2);

        mInOrder.verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(null), eq(mSingleDevice), testConnectioInfoCapture.capture());
        mInOrder.verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(mSingleDevice), eq(null), testConnectioInfoCapture.capture());

        injectAudioDeviceRemoved(
                mSingleDevice, AudioDeviceInfo.TYPE_BLE_HEADSET, true, false, false);
        injectAudioDeviceAdded(mSingleDevice, AudioDeviceInfo.TYPE_BLE_HEADSET, false, true, false);

        connInfos = testConnectioInfoCapture.getAllValues();
        assertThat(connInfos).hasSize(5);
        assertThat(connInfos.get(3).isLeOutput()).isTrue();
        assertThat(connInfos.get(4).isLeOutput()).isFalse();
    }

    /** Test native interface audio configuration changed message handling */
    @Test
    public void testMessageFromNativeAudioConfChangedActiveGroup() {
        connectTestDevice(mSingleDevice, TEST_GROUP_ID);
        injectAudioConfChanged(
                mSingleDevice,
                TEST_GROUP_ID,
                BluetoothLeAudio.CONTEXT_TYPE_MEDIA | BluetoothLeAudio.CONTEXT_TYPE_CONVERSATIONAL,
                3);
        injectGroupStatusChange(TEST_GROUP_ID, BluetoothLeAudio.GROUP_STATUS_ACTIVE);

        /* Expect 2 calls to Audio Manager - one for output and second for input as this is
         * Conversational use case */
        mInOrder.verify(mAudioManager, times(2))
                .handleBluetoothActiveDeviceChanged(
                        any(), any(), any(BluetoothProfileConnectionInfo.class));
        /* Since LeAudioService called AudioManager - assume Audio manager calls properly callback
         * mAudioManager.onAudioDeviceAdded
         */
        injectAudioDeviceAdded(mSingleDevice, AudioDeviceInfo.TYPE_BLE_HEADSET, true, false, true);
    }

    /** Test native interface audio configuration changed message handling */
    @Test
    public void testMessageFromNativeAudioConfChangedInactiveGroup() {
        connectTestDevice(mSingleDevice, TEST_GROUP_ID);

        Integer contexts =
                BluetoothLeAudio.CONTEXT_TYPE_MEDIA | BluetoothLeAudio.CONTEXT_TYPE_CONVERSATIONAL;
        injectAudioConfChanged(mSingleDevice, TEST_GROUP_ID, contexts, 3);

        verifyNoIntentSent();
    }

    /** Test native interface audio configuration changed message handling */
    @Test
    public void testMessageFromNativeAudioConfChangedNoGroupChanged() {
        connectTestDevice(mSingleDevice, TEST_GROUP_ID);

        injectAudioConfChanged(mSingleDevice, TEST_GROUP_ID, 0, 3);
        verifyNoIntentSent();
    }

    /**
     * Test native interface health base action message handling. It does not much, just checks
     * stack even and that service not crash
     */
    @Test
    public void testHealthBaseDeviceAction() {
        connectTestDevice(mSingleDevice, TEST_GROUP_ID);

        LeAudioStackEvent healthBaseDevAction =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_HEALTH_BASED_DEV_RECOMMENDATION);
        healthBaseDevAction.device = mSingleDevice;
        healthBaseDevAction.valueInt1 = LeAudioStackEvent.HEALTH_RECOMMENDATION_ACTION_DISABLE;
        mService.messageFromNative(healthBaseDevAction);
        mLooper.dispatchAll();
        assertThat(mService.mLeAudioNativeIsInitialized).isTrue();
    }

    @Test
    public void testHealthBasedGroupAction() {
        connectTestDevice(mSingleDevice, TEST_GROUP_ID);

        LeAudioStackEvent healthBasedGroupAction =
                new LeAudioStackEvent(
                        LeAudioStackEvent.EVENT_TYPE_HEALTH_BASED_GROUP_RECOMMENDATION);
        healthBasedGroupAction.valueInt1 = TEST_GROUP_ID;
        healthBasedGroupAction.valueInt2 = LeAudioStackEvent.HEALTH_RECOMMENDATION_ACTION_DISABLE;
        mService.messageFromNative(healthBasedGroupAction);
        mLooper.dispatchAll();
        assertThat(mService.mLeAudioNativeIsInitialized).isTrue();
    }

    @Test
    public void testHealthBasedGroupAction_recommendDisable() {
        doReturn(true).when(mNativeInterface).connectLeAudio(any(BluetoothDevice.class));
        connectTestDevice(mLeftDevice, TEST_GROUP_ID);
        connectTestDevice(mRightDevice, TEST_GROUP_ID);

        LeAudioStackEvent healthBasedGroupAction =
                new LeAudioStackEvent(
                        LeAudioStackEvent.EVENT_TYPE_HEALTH_BASED_GROUP_RECOMMENDATION);
        healthBasedGroupAction.valueInt1 = TEST_GROUP_ID;
        healthBasedGroupAction.valueInt2 = LeAudioStackEvent.HEALTH_RECOMMENDATION_ACTION_DISABLE;
        mService.messageFromNative(healthBasedGroupAction);
        mLooper.dispatchAll();
        assertThat(mService.mLeAudioNativeIsInitialized).isTrue();

        verify(mAdapterService)
                .setProfileConnectionPolicy(
                        mLeftDevice, BluetoothProfile.LE_AUDIO, CONNECTION_POLICY_FORBIDDEN);
        verify(mAdapterService)
                .setProfileConnectionPolicy(
                        mRightDevice, BluetoothProfile.LE_AUDIO, CONNECTION_POLICY_FORBIDDEN);
    }

    private void sendEventAndVerifyIntentForGroupStatusChanged(int groupId, int groupStatus) {
        onGroupStatusCallbackCalled = false;

        IBluetoothLeAudioCallback leAudioCallbacks =
                new IBluetoothLeAudioCallback.Stub() {
                    @Override
                    public void onCodecConfigChanged(int gid, BluetoothLeAudioCodecStatus status) {}

                    @Override
                    public void onGroupStatusChanged(int gid, int gStatus) {
                        onGroupStatusCallbackCalled = true;
                        assertThat(gid == groupId).isTrue();
                        assertThat(gStatus == groupStatus).isTrue();
                    }

                    @Override
                    public void onGroupNodeAdded(BluetoothDevice device, int gid) {}

                    @Override
                    public void onGroupNodeRemoved(BluetoothDevice device, int gid) {}

                    @Override
                    public void onGroupStreamStatusChanged(int groupId, int groupStreamStatus) {}

                    @Override
                    public void onBroadcastToUnicastFallbackGroupChanged(int groupId) {}
                };

        synchronized (mService.mLeAudioCallbacks) {
            mService.mLeAudioCallbacks.register(leAudioCallbacks);
        }

        injectGroupStatusChange(groupId, groupStatus);

        mLooper.dispatchAll();
        assertThat(onGroupStatusCallbackCalled).isTrue();

        onGroupStatusCallbackCalled = false;
        synchronized (mService.mLeAudioCallbacks) {
            mService.mLeAudioCallbacks.unregister(leAudioCallbacks);
        }
    }

    /** Test native interface group status message handling */
    @Test
    public void testMessageFromNativeGroupStatusChanged() {
        connectTestDevice(mSingleDevice, TEST_GROUP_ID);

        injectAudioConfChanged(
                mSingleDevice,
                TEST_GROUP_ID,
                BluetoothLeAudio.CONTEXT_TYPE_MEDIA | BluetoothLeAudio.CONTEXT_TYPE_CONVERSATIONAL,
                3);

        sendEventAndVerifyIntentForGroupStatusChanged(
                TEST_GROUP_ID, LeAudioStackEvent.GROUP_STATUS_ACTIVE);
        sendEventAndVerifyIntentForGroupStatusChanged(
                TEST_GROUP_ID, LeAudioStackEvent.GROUP_STATUS_INACTIVE);
    }

    @Test
    public void testMessageFromNativeGroupStatusChanged_autonomousInactive() {
        connectTestDevice(mSingleDevice, TEST_GROUP_ID);
        injectAudioConfChanged(
                mSingleDevice,
                TEST_GROUP_ID,
                BluetoothLeAudio.CONTEXT_TYPE_MEDIA | BluetoothLeAudio.CONTEXT_TYPE_CONVERSATIONAL,
                3);
        injectGroupStatusChange(TEST_GROUP_ID, LeAudioStackEvent.GROUP_STATUS_ACTIVE);

        injectGroupStatusChange(TEST_GROUP_ID, LeAudioStackEvent.GROUP_STATUS_AUTONOMOUS_INACTIVE);
        verify(mBassClientService)
                .notifyLeAudioGroupAutonomousInactivated(
                        eq(mService.getConnectedGroupLeadDevice(TEST_GROUP_ID)));
    }

    private void sendEventAndVerifyGroupStreamStatusChanged(int groupId, int groupStreamStatus) {
        onGroupStreamStatusCallbackCalled = false;

        IBluetoothLeAudioCallback leAudioCallbacks =
                new IBluetoothLeAudioCallback.Stub() {
                    @Override
                    public void onCodecConfigChanged(int gid, BluetoothLeAudioCodecStatus status) {}

                    @Override
                    public void onGroupStatusChanged(int gid, int gStatus) {}

                    @Override
                    public void onGroupNodeAdded(BluetoothDevice device, int gid) {}

                    @Override
                    public void onGroupNodeRemoved(BluetoothDevice device, int gid) {}

                    @Override
                    public void onGroupStreamStatusChanged(int gid, int gStreamStatus) {
                        onGroupStreamStatusCallbackCalled = true;
                        assertThat(gid == groupId).isTrue();
                        assertThat(gStreamStatus == groupStreamStatus).isTrue();
                    }

                    @Override
                    public void onBroadcastToUnicastFallbackGroupChanged(int groupId) {}
                };

        synchronized (mService.mLeAudioCallbacks) {
            mService.mLeAudioCallbacks.register(leAudioCallbacks);
        }

        injectGroupStreamStatusChange(groupId, groupStreamStatus);

        mLooper.dispatchAll();
        assertThat(onGroupStreamStatusCallbackCalled).isTrue();

        onGroupStreamStatusCallbackCalled = false;
        synchronized (mService.mLeAudioCallbacks) {
            mService.mLeAudioCallbacks.unregister(leAudioCallbacks);
        }
    }

    /** Test native interface group stream status message handling */
    @Test
    public void testMessageFromNativeGroupStreamStatusChanged() {
        connectTestDevice(mSingleDevice, TEST_GROUP_ID);

        injectAudioConfChanged(
                mSingleDevice,
                TEST_GROUP_ID,
                BluetoothLeAudio.CONTEXT_TYPE_MEDIA | BluetoothLeAudio.CONTEXT_TYPE_CONVERSATIONAL,
                3);

        sendEventAndVerifyGroupStreamStatusChanged(
                TEST_GROUP_ID, LeAudioStackEvent.GROUP_STREAM_STATUS_IDLE);
        sendEventAndVerifyGroupStreamStatusChanged(
                TEST_GROUP_ID, LeAudioStackEvent.GROUP_STREAM_STATUS_STREAMING);
    }

    private void injectLocalCodecConfigCapaChanged(
            List<BluetoothLeAudioCodecConfig> inputCodecCapa,
            List<BluetoothLeAudioCodecConfig> outputCodecCapa) {
        int eventType = LeAudioStackEvent.EVENT_TYPE_AUDIO_LOCAL_CODEC_CONFIG_CAPA_CHANGED;

        // Add device to group
        LeAudioStackEvent localCodecCapaEvent = new LeAudioStackEvent(eventType);
        localCodecCapaEvent.valueCodecList1 = inputCodecCapa;
        localCodecCapaEvent.valueCodecList2 = outputCodecCapa;
        mService.messageFromNative(localCodecCapaEvent);
        mLooper.dispatchAll();
    }

    private void injectGroupCurrentCodecConfigChanged(
            int groupId,
            BluetoothLeAudioCodecConfig inputCodecConfig,
            BluetoothLeAudioCodecConfig outputCodecConfig) {
        int eventType = LeAudioStackEvent.EVENT_TYPE_AUDIO_GROUP_CURRENT_CODEC_CONFIG_CHANGED;

        // Add device to group
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

        // Add device to group
        LeAudioStackEvent groupCodecConfigChangedEvent = new LeAudioStackEvent(eventType);
        groupCodecConfigChangedEvent.valueInt1 = groupId;
        groupCodecConfigChangedEvent.valueCodecList1 = inputSelectableCodecConfig;
        groupCodecConfigChangedEvent.valueCodecList2 = outputSelectableCodecConfig;
        mService.messageFromNative(groupCodecConfigChangedEvent);
        mLooper.dispatchAll();
    }

    /** Test native interface group status message handling */
    @Test
    public void testMessageFromNativeGroupCodecConfigChangedNonActiveDevice() {
        onGroupCodecConfChangedCallbackCalled = false;

        injectLocalCodecConfigCapaChanged(INPUT_CAPABILITIES_CONFIG, OUTPUT_CAPABILITIES_CONFIG);

        connectTestDevice(mSingleDevice, TEST_GROUP_ID);

        testCodecStatus =
                new BluetoothLeAudioCodecStatus(
                        LC3_16KHZ_CONFIG,
                        LC3_48KHZ_CONFIG,
                        INPUT_CAPABILITIES_CONFIG,
                        OUTPUT_CAPABILITIES_CONFIG,
                        INPUT_SELECTABLE_CONFIG,
                        OUTPUT_SELECTABLE_CONFIG);

        IBluetoothLeAudioCallback leAudioCallbacks =
                new IBluetoothLeAudioCallback.Stub() {
                    @Override
                    public void onCodecConfigChanged(int gid, BluetoothLeAudioCodecStatus status) {
                        onGroupCodecConfChangedCallbackCalled = true;
                        assertThat(status.equals(testCodecStatus)).isTrue();
                    }

                    @Override
                    public void onGroupStatusChanged(int gid, int gStatus) {}

                    @Override
                    public void onGroupNodeAdded(BluetoothDevice device, int gid) {}

                    @Override
                    public void onGroupNodeRemoved(BluetoothDevice device, int gid) {}

                    @Override
                    public void onGroupStreamStatusChanged(int groupId, int groupStreamStatus) {}

                    @Override
                    public void onBroadcastToUnicastFallbackGroupChanged(int groupId) {}
                };

        synchronized (mService.mLeAudioCallbacks) {
            mService.mLeAudioCallbacks.register(leAudioCallbacks);
        }

        injectGroupSelectableCodecConfigChanged(
                TEST_GROUP_ID, INPUT_SELECTABLE_CONFIG, OUTPUT_SELECTABLE_CONFIG);
        // Inject configuration and check that AF is NOT notified.
        injectGroupCurrentCodecConfigChanged(TEST_GROUP_ID, LC3_16KHZ_CONFIG, LC3_48KHZ_CONFIG);

        mLooper.dispatchAll();
        assertThat(onGroupCodecConfChangedCallbackCalled).isTrue();

        mInOrder.verify(mAudioManager, never())
                .handleBluetoothActiveDeviceChanged(
                        any(), any(), any(BluetoothProfileConnectionInfo.class));

        onGroupCodecConfChangedCallbackCalled = false;

        // Now inject again new configuration and check that AF is not notified.
        testCodecStatus =
                new BluetoothLeAudioCodecStatus(
                        LC3_16KHZ_CONFIG,
                        LC3_16KHZ_CONFIG,
                        INPUT_CAPABILITIES_CONFIG,
                        OUTPUT_CAPABILITIES_CONFIG,
                        INPUT_SELECTABLE_CONFIG,
                        OUTPUT_SELECTABLE_CONFIG);

        injectGroupCurrentCodecConfigChanged(TEST_GROUP_ID, LC3_16KHZ_CONFIG, LC3_16KHZ_CONFIG);

        mLooper.dispatchAll();
        assertThat(onGroupCodecConfChangedCallbackCalled).isTrue();

        mInOrder.verify(mAudioManager, never())
                .handleBluetoothActiveDeviceChanged(
                        any(), any(), any(BluetoothProfileConnectionInfo.class));

        onGroupCodecConfChangedCallbackCalled = false;
        synchronized (mService.mLeAudioCallbacks) {
            mService.mLeAudioCallbacks.unregister(leAudioCallbacks);
        }
    }

    /** Test native interface group status message handling */
    @Test
    public void testMessageFromNativeGroupCodecConfigChangedActiveDevice_DifferentConfiguration() {
        onGroupCodecConfChangedCallbackCalled = false;

        injectLocalCodecConfigCapaChanged(INPUT_CAPABILITIES_CONFIG, OUTPUT_CAPABILITIES_CONFIG);

        connectTestDevice(mSingleDevice, TEST_GROUP_ID);

        testCodecStatus =
                new BluetoothLeAudioCodecStatus(
                        LC3_16KHZ_CONFIG,
                        LC3_48KHZ_CONFIG,
                        INPUT_CAPABILITIES_CONFIG,
                        OUTPUT_CAPABILITIES_CONFIG,
                        INPUT_SELECTABLE_CONFIG,
                        OUTPUT_SELECTABLE_CONFIG);

        IBluetoothLeAudioCallback leAudioCallbacks =
                new IBluetoothLeAudioCallback.Stub() {
                    @Override
                    public void onCodecConfigChanged(int gid, BluetoothLeAudioCodecStatus status) {
                        onGroupCodecConfChangedCallbackCalled = true;
                        assertThat(status.equals(testCodecStatus)).isTrue();
                    }

                    @Override
                    public void onGroupStatusChanged(int gid, int gStatus) {}

                    @Override
                    public void onGroupNodeAdded(BluetoothDevice device, int gid) {}

                    @Override
                    public void onGroupNodeRemoved(BluetoothDevice device, int gid) {}

                    @Override
                    public void onGroupStreamStatusChanged(int groupId, int groupStreamStatus) {}

                    @Override
                    public void onBroadcastToUnicastFallbackGroupChanged(int groupId) {}
                };

        synchronized (mService.mLeAudioCallbacks) {
            mService.mLeAudioCallbacks.register(leAudioCallbacks);
        }

        injectGroupSelectableCodecConfigChanged(
                TEST_GROUP_ID, INPUT_SELECTABLE_CONFIG, OUTPUT_SELECTABLE_CONFIG);

        injectGroupCurrentCodecConfigChanged(TEST_GROUP_ID, LC3_16KHZ_CONFIG, LC3_48KHZ_CONFIG);

        injectAudioConfChanged(
                mSingleDevice,
                TEST_GROUP_ID,
                BluetoothLeAudio.CONTEXT_TYPE_MEDIA | BluetoothLeAudio.CONTEXT_TYPE_CONVERSATIONAL,
                3);

        injectGroupStatusChange(TEST_GROUP_ID, LeAudioStackEvent.GROUP_STATUS_ACTIVE);

        mLooper.dispatchAll();
        assertThat(onGroupCodecConfChangedCallbackCalled).isTrue();

        mInOrder.verify(mAudioManager, times(2))
                .handleBluetoothActiveDeviceChanged(
                        any(), any(), any(BluetoothProfileConnectionInfo.class));

        onGroupCodecConfChangedCallbackCalled = false;

        // Now inject configuration different sample rate on one direction
        testCodecStatus =
                new BluetoothLeAudioCodecStatus(
                        OPUS_48KHZ_CONFIG,
                        OPUS_48KHZ_CONFIG,
                        INPUT_CAPABILITIES_CONFIG,
                        OUTPUT_CAPABILITIES_CONFIG,
                        INPUT_SELECTABLE_CONFIG,
                        OUTPUT_SELECTABLE_CONFIG);

        injectGroupCurrentCodecConfigChanged(TEST_GROUP_ID, OPUS_48KHZ_CONFIG, OPUS_48KHZ_CONFIG);

        mLooper.dispatchAll();
        assertThat(onGroupCodecConfChangedCallbackCalled).isTrue();

        mInOrder.verify(mAudioManager, times(2))
                .handleBluetoothActiveDeviceChanged(
                        any(), any(), any(BluetoothProfileConnectionInfo.class));

        synchronized (mService.mLeAudioCallbacks) {
            mService.mLeAudioCallbacks.unregister(leAudioCallbacks);
        }

        onGroupCodecConfChangedCallbackCalled = false;
    }

    /** Test native interface group status message handling */
    @Test
    public void testGroupCodecConfigChangedActiveDevice_DifferentSampleFreqWithLc3() {
        onGroupCodecConfChangedCallbackCalled = false;

        injectLocalCodecConfigCapaChanged(INPUT_CAPABILITIES_CONFIG, OUTPUT_CAPABILITIES_CONFIG);

        connectTestDevice(mSingleDevice, TEST_GROUP_ID);

        testCodecStatus =
                new BluetoothLeAudioCodecStatus(
                        LC3_16KHZ_CONFIG,
                        LC3_48KHZ_CONFIG,
                        INPUT_CAPABILITIES_CONFIG,
                        OUTPUT_CAPABILITIES_CONFIG,
                        INPUT_SELECTABLE_CONFIG,
                        OUTPUT_SELECTABLE_CONFIG);

        IBluetoothLeAudioCallback leAudioCallbacks =
                new IBluetoothLeAudioCallback.Stub() {
                    @Override
                    public void onCodecConfigChanged(int gid, BluetoothLeAudioCodecStatus status) {
                        onGroupCodecConfChangedCallbackCalled = true;
                        assertThat(status.equals(testCodecStatus)).isTrue();
                    }

                    @Override
                    public void onGroupStatusChanged(int gid, int gStatus) {}

                    @Override
                    public void onGroupNodeAdded(BluetoothDevice device, int gid) {}

                    @Override
                    public void onGroupNodeRemoved(BluetoothDevice device, int gid) {}

                    @Override
                    public void onGroupStreamStatusChanged(int groupId, int groupStreamStatus) {}

                    @Override
                    public void onBroadcastToUnicastFallbackGroupChanged(int groupId) {}
                };

        synchronized (mService.mLeAudioCallbacks) {
            mService.mLeAudioCallbacks.register(leAudioCallbacks);
        }

        injectGroupSelectableCodecConfigChanged(
                TEST_GROUP_ID, INPUT_SELECTABLE_CONFIG, OUTPUT_SELECTABLE_CONFIG);

        injectGroupCurrentCodecConfigChanged(TEST_GROUP_ID, LC3_16KHZ_CONFIG, LC3_48KHZ_CONFIG);

        injectAudioConfChanged(
                mSingleDevice,
                TEST_GROUP_ID,
                BluetoothLeAudio.CONTEXT_TYPE_MEDIA | BluetoothLeAudio.CONTEXT_TYPE_CONVERSATIONAL,
                3);

        injectGroupStatusChange(TEST_GROUP_ID, LeAudioStackEvent.GROUP_STATUS_ACTIVE);

        mLooper.dispatchAll();
        assertThat(onGroupCodecConfChangedCallbackCalled).isTrue();

        mInOrder.verify(mAudioManager, times(2))
                .handleBluetoothActiveDeviceChanged(
                        any(), any(), any(BluetoothProfileConnectionInfo.class));

        onGroupCodecConfChangedCallbackCalled = false;

        // Now inject configuration different sample rate on one direction
        testCodecStatus =
                new BluetoothLeAudioCodecStatus(
                        LC3_48KHZ_CONFIG,
                        LC3_48KHZ_CONFIG,
                        INPUT_CAPABILITIES_CONFIG,
                        OUTPUT_CAPABILITIES_CONFIG,
                        INPUT_SELECTABLE_CONFIG,
                        OUTPUT_SELECTABLE_CONFIG);

        injectGroupCurrentCodecConfigChanged(TEST_GROUP_ID, LC3_48KHZ_CONFIG, LC3_48KHZ_CONFIG);

        mLooper.dispatchAll();
        assertThat(onGroupCodecConfChangedCallbackCalled).isTrue();

        mInOrder.verify(mAudioManager, never())
                .handleBluetoothActiveDeviceChanged(
                        any(), any(), any(BluetoothProfileConnectionInfo.class));

        synchronized (mService.mLeAudioCallbacks) {
            mService.mLeAudioCallbacks.unregister(leAudioCallbacks);
        }

        onGroupCodecConfChangedCallbackCalled = false;
    }

    /** Test native interface group status message handling */
    @Test
    public void testMessageFromNativeGroupCodecConfigChanged_OneDirectionOnly() {
        onGroupCodecConfChangedCallbackCalled = false;

        injectLocalCodecConfigCapaChanged(INPUT_CAPABILITIES_CONFIG, OUTPUT_CAPABILITIES_CONFIG);

        connectTestDevice(mSingleDevice, TEST_GROUP_ID);

        testCodecStatus =
                new BluetoothLeAudioCodecStatus(
                        null,
                        LC3_48KHZ_CONFIG,
                        INPUT_CAPABILITIES_CONFIG,
                        OUTPUT_CAPABILITIES_CONFIG,
                        new ArrayList<>(),
                        OUTPUT_SELECTABLE_CONFIG);

        IBluetoothLeAudioCallback leAudioCallbacks =
                new IBluetoothLeAudioCallback.Stub() {
                    @Override
                    public void onCodecConfigChanged(int gid, BluetoothLeAudioCodecStatus status) {
                        onGroupCodecConfChangedCallbackCalled = true;
                        assertThat(status.equals(testCodecStatus)).isTrue();
                    }

                    @Override
                    public void onGroupStatusChanged(int gid, int gStatus) {}

                    @Override
                    public void onGroupNodeAdded(BluetoothDevice device, int gid) {}

                    @Override
                    public void onGroupNodeRemoved(BluetoothDevice device, int gid) {}

                    @Override
                    public void onGroupStreamStatusChanged(int groupId, int groupStreamStatus) {}

                    @Override
                    public void onBroadcastToUnicastFallbackGroupChanged(int groupId) {}
                };

        synchronized (mService.mLeAudioCallbacks) {
            mService.mLeAudioCallbacks.register(leAudioCallbacks);
        }

        injectGroupSelectableCodecConfigChanged(
                TEST_GROUP_ID, INPUT_EMPTY_CONFIG, OUTPUT_SELECTABLE_CONFIG);
        injectGroupCurrentCodecConfigChanged(TEST_GROUP_ID, EMPTY_CONFIG, LC3_48KHZ_CONFIG);

        mLooper.dispatchAll();
        assertThat(onGroupCodecConfChangedCallbackCalled).isTrue();

        onGroupCodecConfChangedCallbackCalled = false;
        synchronized (mService.mLeAudioCallbacks) {
            mService.mLeAudioCallbacks.unregister(leAudioCallbacks);
        }

        BluetoothLeAudioCodecStatus codecStatus = mService.getCodecStatus(TEST_GROUP_ID);
        assertThat(codecStatus.getInputCodecConfig()).isNull();
        assertThat(codecStatus.getOutputCodecConfig()).isNotNull();
    }

    /** Test native interface group status message handling */
    @Test
    public void testLeadGroupDeviceDisconnects() {
        /* AUDIO_DIRECTION_OUTPUT_BIT = 0x01 */
        int direction = 1;
        int availableContexts = 5 + BluetoothLeAudio.CONTEXT_TYPE_RINGTONE;

        int groupStatus = LeAudioStackEvent.GROUP_STATUS_ACTIVE;
        BluetoothDevice leadDevice;
        BluetoothDevice memberDevice = mLeftDevice;

        connectTestDevice(mLeftDevice, TEST_GROUP_ID);
        connectTestDevice(mRightDevice, TEST_GROUP_ID);

        leadDevice = mService.getConnectedGroupLeadDevice(TEST_GROUP_ID);
        if (Objects.equals(leadDevice, mLeftDevice)) {
            memberDevice = mRightDevice;
        }

        assertThat(mService.setActiveDevice(leadDevice)).isFalse();

        injectAudioConfChanged(mSingleDevice, TEST_GROUP_ID, availableContexts, direction);

        assertThat(mService.setActiveDevice(leadDevice)).isTrue();

        // Set group and device as active
        LeAudioStackEvent groupStatusChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        groupStatusChangedEvent.valueInt1 = TEST_GROUP_ID;
        groupStatusChangedEvent.valueInt2 = groupStatus;
        mService.messageFromNative(groupStatusChangedEvent);
        mLooper.dispatchAll();

        assertThat(mService.getActiveDevices().contains(leadDevice)).isTrue();
        mInOrder.verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(leadDevice), any(), any(BluetoothProfileConnectionInfo.class));
        /* Since LeAudioService called AudioManager - assume Audio manager calls properly callback
         * mAudioManager.onAudioDeviceAdded
         */
        injectAudioDeviceAdded(leadDevice, AudioDeviceInfo.TYPE_BLE_HEADSET, true, false, true);
        doReturn(BOND_BONDED).when(mAdapterService).getBondState(leadDevice);
        injectNoVerifyDeviceDisconnected(leadDevice);

        // We should not change the audio device
        assertThat(mService.getConnectionState(leadDevice)).isEqualTo(STATE_CONNECTED);

        injectAndVerifyDeviceDisconnected(memberDevice);
        mLooper.dispatchAll();

        // Verify the connection state broadcast, and that we are in Connecting state
        verifyConnectionStateIntent(leadDevice, STATE_DISCONNECTED, STATE_CONNECTED);

        mInOrder.verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        any(), eq(leadDevice), any(BluetoothProfileConnectionInfo.class));

        verify(mTbsService).setInbandRingtoneSupport(mLeftDevice);
        verify(mTbsService).setInbandRingtoneSupport(mRightDevice);
    }

    /** Test native interface group status message handling */
    @Test
    public void testLeadGroupDeviceReconnects() {
        /* AUDIO_DIRECTION_OUTPUT_BIT = 0x01 */
        int direction = 1;
        int availableContexts = 5 + BluetoothLeAudio.CONTEXT_TYPE_RINGTONE;

        int groupStatus = LeAudioStackEvent.GROUP_STATUS_ACTIVE;
        BluetoothDevice leadDevice;
        BluetoothDevice memberDevice = mLeftDevice;

        connectTestDevice(mLeftDevice, TEST_GROUP_ID);
        connectTestDevice(mRightDevice, TEST_GROUP_ID);

        leadDevice = mService.getConnectedGroupLeadDevice(TEST_GROUP_ID);
        if (Objects.equals(leadDevice, mLeftDevice)) {
            memberDevice = mRightDevice;
        }

        assertThat(mService.setActiveDevice(leadDevice)).isFalse();

        // Add location support
        injectAudioConfChanged(mSingleDevice, TEST_GROUP_ID, availableContexts, direction);

        assertThat(mService.setActiveDevice(leadDevice)).isTrue();

        // Set group and device as active
        LeAudioStackEvent groupStatusChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        groupStatusChangedEvent.valueInt1 = TEST_GROUP_ID;
        groupStatusChangedEvent.valueInt2 = groupStatus;
        mService.messageFromNative(groupStatusChangedEvent);
        mLooper.dispatchAll();

        assertThat(mService.getActiveDevices().contains(leadDevice)).isTrue();
        mInOrder.verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(leadDevice), any(), any(BluetoothProfileConnectionInfo.class));
        /* Since LeAudioService called AudioManager - assume Audio manager calls properly callback
         * mAudioManager.onAudioDeviceAdded
         */
        injectAudioDeviceAdded(leadDevice, AudioDeviceInfo.TYPE_BLE_HEADSET, true, false, true);

        /* We don't want to distribute DISCONNECTION event, instead will try to reconnect
         * (in native)
         */
        injectNoVerifyDeviceDisconnected(leadDevice);
        assertThat(mService.getConnectionState(leadDevice)).isEqualTo(STATE_CONNECTED);

        /* Reconnect device, there should be no intent about that, as device was pretending
         * connected
         */
        injectNoVerifyDeviceConnected(leadDevice);

        injectAndVerifyDeviceDisconnected(memberDevice);
        injectAndVerifyDeviceDisconnected(leadDevice);

        mInOrder.verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(null), eq(leadDevice), any(BluetoothProfileConnectionInfo.class));

        verify(mTbsService).setInbandRingtoneSupport(mLeftDevice);
        verify(mTbsService).setInbandRingtoneSupport(mRightDevice);
    }

    /** Test volume caching for the group */
    @Test
    public void testVolumeCache() {
        int volume = 100;
        /* AUDIO_DIRECTION_OUTPUT_BIT = 0x01 */
        int direction = 1;
        int availableContexts = 4;

        connectTestDevice(mLeftDevice, TEST_GROUP_ID);
        connectTestDevice(mRightDevice, TEST_GROUP_ID);

        assertThat(mService.setActiveDevice(mLeftDevice)).isFalse();

        ArgumentCaptor<BluetoothProfileConnectionInfo> profileInfo =
                ArgumentCaptor.forClass(BluetoothProfileConnectionInfo.class);

        // Add location support.
        injectAudioConfChanged(mSingleDevice, TEST_GROUP_ID, availableContexts, direction);

        assertThat(mService.setActiveDevice(mLeftDevice)).isTrue();
        mLooper.dispatchAll();

        doReturn(VOLUME_CONTROL_UNKNOWN_VOLUME)
                .when(mVolumeControlService)
                .getAudioDeviceGroupVolume(TEST_GROUP_ID);
        // Set group and device as active.
        injectGroupStatusChange(TEST_GROUP_ID, LeAudioStackEvent.GROUP_STATUS_ACTIVE);

        mInOrder.verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(any(), eq(null), profileInfo.capture());
        assertThat(profileInfo.getValue().getVolume()).isEqualTo(-1);

        mService.setVolume(volume);
        verify(mVolumeControlService).setGroupVolume(TEST_GROUP_ID, volume);

        // Set group to inactive.
        injectGroupStatusChange(TEST_GROUP_ID, LeAudioStackEvent.GROUP_STATUS_INACTIVE);

        mInOrder.verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(null), any(), any(BluetoothProfileConnectionInfo.class));
        mLooper.dispatchAll();

        doReturn(volume).when(mVolumeControlService).getAudioDeviceGroupVolume(TEST_GROUP_ID);

        // Set back to active and check if last volume is restored.
        injectGroupStatusChange(TEST_GROUP_ID, LeAudioStackEvent.GROUP_STATUS_ACTIVE);

        mInOrder.verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(any(), eq(null), profileInfo.capture());

        assertThat(profileInfo.getValue().getVolume()).isEqualTo(volume);
    }

    /** Test volume setting for broadcast sink devices */
    @Test
    public void testSetVolumeForBroadcastSinks() {
        int volume = 100;
        int newVolume = 120;
        /* AUDIO_DIRECTION_OUTPUT_BIT = 0x01 */
        int direction = 1;
        int availableContexts = 4;
        Set<BluetoothDevice> broadcastReceivers = new HashSet<>();

        doReturn(mLeftDevice).when(mStorage).getLeastRecentlyConnectedDeviceInList(any());

        connectTestDevice(mLeftDevice, TEST_GROUP_ID);
        connectTestDevice(mRightDevice, TEST_GROUP_ID);
        assertThat(mService.setActiveDevice(mLeftDevice)).isFalse();

        connectTestDevice(mSingleDevice, TEST_GROUP_ID2);

        ArgumentCaptor<BluetoothProfileConnectionInfo> profileInfo =
                ArgumentCaptor.forClass(BluetoothProfileConnectionInfo.class);

        // Add location support.
        injectAudioConfChanged(mSingleDevice, TEST_GROUP_ID, availableContexts, direction);
        assertThat(mService.setActiveDevice(mLeftDevice)).isTrue();
        mLooper.dispatchAll();

        doReturn(volume).when(mVolumeControlService).getAudioDeviceGroupVolume(TEST_GROUP_ID);
        doReturn(volume).when(mVolumeControlService).getAudioDeviceGroupVolume(TEST_GROUP_ID2);
        // Set group and device as active.
        injectGroupStatusChange(TEST_GROUP_ID, LeAudioStackEvent.GROUP_STATUS_ACTIVE);

        mInOrder.verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(any(), eq(null), profileInfo.capture());
        assertThat(profileInfo.getValue().getVolume()).isEqualTo(volume);

        // Set group to inactive, only keep them connected as broadcast sink devices.
        injectGroupStatusChange(TEST_GROUP_ID, LeAudioStackEvent.GROUP_STATUS_INACTIVE);
        injectGroupStatusChange(TEST_GROUP_ID2, LeAudioStackEvent.GROUP_STATUS_INACTIVE);

        mInOrder.verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(null), any(), any(BluetoothProfileConnectionInfo.class));
        mLooper.dispatchAll();

        // Verify setGroupVolume will not be called if no synced sinks
        doReturn(new ArrayList<>()).when(mBassClientService).getSyncedBroadcastSinks();
        mService.setVolume(newVolume);
        verify(mVolumeControlService, never()).setGroupVolume(TEST_GROUP_ID, newVolume);

        broadcastReceivers.addAll(Arrays.asList(mLeftDevice, mRightDevice, mSingleDevice));
        mService.selectDefaultBroadcastToUnicastFallbackGroup(broadcastReceivers);

        // Verify setGroupVolume will be called if synced sinks
        doReturn(List.of(mLeftDevice, mRightDevice, mSingleDevice))
                .when(mBassClientService)
                .getSyncedBroadcastSinks();
        mService.setVolume(newVolume);

        // Verify set volume only on primary group
        verify(mVolumeControlService).setGroupVolume(TEST_GROUP_ID, newVolume);
        verify(mVolumeControlService, never()).setGroupVolume(TEST_GROUP_ID2, newVolume);
    }

    @Test
    public void testGetAudioDeviceGroupVolume_whenVolumeControlServiceIsNull() {
        doReturn(Optional.empty()).when(mAdapterService).getVolumeControlService();

        assertThat(mService.getAudioDeviceGroupVolume(TEST_GROUP_ID))
                .isEqualTo(VOLUME_CONTROL_UNKNOWN_VOLUME);
    }

    @Test
    public void testGetAudioLocation() {
        connectTestDevice(mSingleDevice, TEST_GROUP_ID);

        assertThat(mService.getAudioLocation(null))
                .isEqualTo(BluetoothLeAudio.AUDIO_LOCATION_INVALID);

        int sinkAudioLocation = 10;
        LeAudioStackEvent stackEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_SINK_AUDIO_LOCATION_AVAILABLE);
        stackEvent.device = mSingleDevice;
        stackEvent.valueInt1 = sinkAudioLocation;
        mService.messageFromNative(stackEvent);
        mLooper.dispatchAll();

        assertThat(mService.getAudioLocation(mSingleDevice)).isEqualTo(sinkAudioLocation);
    }

    @Test
    public void testGetConnectedPeerDevices() {
        connectTestDevice(mLeftDevice, TEST_GROUP_ID);
        connectTestDevice(mRightDevice, TEST_GROUP_ID);

        List<BluetoothDevice> peerDevices = mService.getConnectedPeerDevices(TEST_GROUP_ID);
        assertThat(peerDevices.contains(mLeftDevice)).isTrue();
        assertThat(peerDevices.contains(mRightDevice)).isTrue();
    }

    @Test
    public void testGetDevicesMatchingConnectionStates() {
        assertThat(mService.getDevicesMatchingConnectionStates(null)).isEmpty();

        int[] states = new int[] {STATE_CONNECTED};
        doReturn(Collections.emptySet()).when(mAdapterService).getBondedDevices();
        assertThat(mService.getDevicesMatchingConnectionStates(states)).isEmpty();

        doReturn(Set.of(mSingleDevice)).when(mAdapterService).getBondedDevices();
        assertThat(mService.getDevicesMatchingConnectionStates(states)).isEmpty();
    }

    @Test
    public void testDefaultValuesOfSeveralGetters() {
        assertThat(mService.getMaximumNumberOfBroadcasts()).isEqualTo(1);
        assertThat(mService.getMaximumStreamsPerBroadcast()).isEqualTo(1);
        assertThat(mService.getMaximumSubgroupsPerBroadcast()).isEqualTo(1);
        assertThat(mService.isPlaying(100)).isFalse();
        assertThat(mService.isValidDeviceGroup(LE_AUDIO_GROUP_ID_INVALID)).isFalse();
    }

    @Test
    public void testHandleGroupIdleDuringCall() {
        BluetoothDevice headsetDevice = getTestDevice(5);
        HeadsetService headsetService = Mockito.mock(HeadsetService.class);
        doReturn(Optional.of(headsetService)).when(mAdapterService).getHeadsetService();

        mService.mHfpHandoverDevice = null;
        mService.handleGroupIdleDuringCall();
        verify(headsetService, never()).getActiveDevice();

        mService.mHfpHandoverDevice = headsetDevice;
        doReturn(headsetDevice).when(headsetService).getActiveDevice();
        mService.handleGroupIdleDuringCall();
        verify(headsetService).connectAudio();
        assertThat(mService.mHfpHandoverDevice).isNull();

        mService.mHfpHandoverDevice = headsetDevice;
        doReturn(null).when(headsetService).getActiveDevice();
        mService.handleGroupIdleDuringCall();
        verify(headsetService).setActiveDevice(headsetDevice);
        assertThat(mService.mHfpHandoverDevice).isNull();
    }

    @Test
    public void testDump_doesNotCrash() {
        doReturn(new ParcelUuid[] {BluetoothUuid.LE_AUDIO})
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
        doReturn(Set.of(mSingleDevice)).when(mAdapterService).getBondedDevices();
        doReturn(CONNECTION_POLICY_UNKNOWN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(mSingleDevice, BluetoothProfile.LE_AUDIO);

        connectTestDevice(mSingleDevice, TEST_GROUP_ID);

        StringBuilder sb = new StringBuilder();
        mService.dump(sb);
    }

    /** Test setting authorization for LeAudio device in the McpService */
    @Test
    public void testAuthorizeMcpServiceWhenDeviceConnecting() {
        mService.handleBluetoothEnabled();

        connectTestDevice(mLeftDevice, TEST_GROUP_ID);
        connectTestDevice(mRightDevice, TEST_GROUP_ID);
        verify(mMcpService).setDeviceAuthorized(mLeftDevice, true);
        verify(mMcpService).setDeviceAuthorized(mRightDevice, true);
    }

    /** Test setting authorization for LeAudio device in the McpService */
    @Test
    public void testAuthorizeMcpServiceOnBluetoothEnableAndNodeRemoval() {
        connectTestDevice(mLeftDevice, TEST_GROUP_ID);
        connectTestDevice(mRightDevice, TEST_GROUP_ID);

        generateGroupNodeAdded(mLeftDevice, TEST_GROUP_ID);
        generateGroupNodeAdded(mRightDevice, TEST_GROUP_ID);

        verify(mMcpService, never()).setDeviceAuthorized(mLeftDevice, true);
        verify(mMcpService, never()).setDeviceAuthorized(mRightDevice, true);

        mService.handleBluetoothEnabled();

        verify(mMcpService).setDeviceAuthorized(mLeftDevice, true);
        verify(mMcpService).setDeviceAuthorized(mRightDevice, true);

        generateGroupNodeRemoved(mLeftDevice, TEST_GROUP_ID);
        verify(mMcpService).setDeviceAuthorized(mLeftDevice, false);

        generateGroupNodeRemoved(mRightDevice, TEST_GROUP_ID);
        verify(mMcpService).setDeviceAuthorized(mRightDevice, false);
    }

    /**
     * Test verifying that when the LE Audio connection policy of a device is set to {@link
     * BluetoothProfile#CONNECTION_POLICY_FORBIDDEN}, we unauthorize McpService and TbsService. When
     * the LE Audio connection policy is set to {@link BluetoothProfile#CONNECTION_POLICY_ALLOWED},
     * we will authorize these services.
     */
    @Test
    public void testMcsAndTbsAuthorizationWithConnectionPolicy() {
        mService.handleBluetoothEnabled();
        doReturn(CONNECTION_POLICY_UNKNOWN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(mSingleDevice, BluetoothProfile.LE_AUDIO);

        // Ensures GATT server services are not authorized when the device does not have a group
        assertThat(mService.setConnectionPolicy(mSingleDevice, CONNECTION_POLICY_ALLOWED)).isTrue();
        verify(mMcpService, never()).setDeviceAuthorized(mSingleDevice, false);
        verify(mTbsService, never()).setDeviceAuthorized(mSingleDevice, false);

        // Connects the test device and verifies GATT server services are authorized
        connectTestDevice(mSingleDevice, TEST_GROUP_ID);
        verify(mMcpService).setDeviceAuthorized(mSingleDevice, true);
        verify(mTbsService).setDeviceAuthorized(mSingleDevice, true);

        // Ensure that disconnecting unauthorizes GATT server services
        assertThat(mService.setConnectionPolicy(mSingleDevice, CONNECTION_POLICY_FORBIDDEN))
                .isTrue();
        verify(mMcpService).setDeviceAuthorized(mSingleDevice, false);
        verify(mTbsService).setDeviceAuthorized(mSingleDevice, false);

        // Connecting a device that has a group re-authorizes the GATT server services
        assertThat(mService.setConnectionPolicy(mSingleDevice, CONNECTION_POLICY_ALLOWED)).isTrue();
        verify(mMcpService, times(2)).setDeviceAuthorized(mSingleDevice, true);
        verify(mTbsService, times(2)).setDeviceAuthorized(mSingleDevice, true);
    }

    /**
     * Test that HidHostService authorization is managed correctly when the LE Audio connection
     * policy changes and the headtracker_connection_policy flag is enabled.
     */
    @Test
    @EnableFlags(Flags.FLAG_HEADTRACKER_CONNECTION_POLICY)
    public void testHidHostAuthorizationWithConnectionPolicy_flagEnabled() {
        mService.handleBluetoothEnabled();
        doReturn(CONNECTION_POLICY_UNKNOWN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(mSingleDevice, BluetoothProfile.LE_AUDIO);

        // Ensures HidHostService is not authorized when the device does not have a group
        assertThat(mService.setConnectionPolicy(mSingleDevice, CONNECTION_POLICY_ALLOWED)).isTrue();
        verify(mHidHostService, never()).setAndroidHeadTrackerEnabled(mSingleDevice, true);

        // Connects the test device and verifies HidHostService is authorized.
        // Authorization happens in handleGroupNodeAdded via connectTestDevice.
        connectTestDevice(mSingleDevice, TEST_GROUP_ID);
        verify(mHidHostService).setAndroidHeadTrackerEnabled(mSingleDevice, true);

        // Ensure that setting policy to forbidden unauthorizes HidHostService
        assertThat(mService.setConnectionPolicy(mSingleDevice, CONNECTION_POLICY_FORBIDDEN))
                .isTrue();
        verify(mHidHostService).setAndroidHeadTrackerEnabled(mSingleDevice, false);

        // Setting policy to allowed on a device that has a group re-authorizes HidHostService
        assertThat(mService.setConnectionPolicy(mSingleDevice, CONNECTION_POLICY_ALLOWED)).isTrue();
        verify(mHidHostService, times(2)).setAndroidHeadTrackerEnabled(mSingleDevice, true);
    }

    /**
     * Test that HidHostService authorization is not changed when the LE Audio connection policy
     * changes and the headtracker_connection_policy flag is disabled.
     */
    @Test
    @DisableFlags(Flags.FLAG_HEADTRACKER_CONNECTION_POLICY)
    public void testHidHostAuthorizationWithConnectionPolicy_flagDisabled() {
        mService.handleBluetoothEnabled();
        doReturn(CONNECTION_POLICY_UNKNOWN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(mSingleDevice, BluetoothProfile.LE_AUDIO);

        // Connects the test device
        connectTestDevice(mSingleDevice, TEST_GROUP_ID);

        // Ensure that setting policy to forbidden does not touch HidHostService
        assertThat(mService.setConnectionPolicy(mSingleDevice, CONNECTION_POLICY_FORBIDDEN))
                .isTrue();

        // Setting policy to allowed on a device that has a group does not touch HidHostService
        assertThat(mService.setConnectionPolicy(mSingleDevice, CONNECTION_POLICY_ALLOWED)).isTrue();

        // Verify HidHostService is never called for authorization changes
        verify(mHidHostService, never()).setAndroidHeadTrackerEnabled(any(), anyBoolean());
    }

    @Test
    public void testGetGroupDevices() {
        int TEST_GROUP_ID2 = 2;

        connectTestDevice(mLeftDevice, TEST_GROUP_ID);
        connectTestDevice(mRightDevice, TEST_GROUP_ID);
        connectTestDevice(mSingleDevice, TEST_GROUP_ID2);

        // Checks group device lists for groupId 1
        List<BluetoothDevice> firstGroupDevicesById = mService.getGroupDevices(TEST_GROUP_ID);
        List<BluetoothDevice> firstGroupDevicesByLeftDevice = mService.getGroupDevices(mLeftDevice);
        List<BluetoothDevice> firstGroupDevicesByRightDevice =
                mService.getGroupDevices(mRightDevice);

        assertThat(firstGroupDevicesById).hasSize(2);
        assertThat(firstGroupDevicesById.contains(mLeftDevice)).isTrue();
        assertThat(firstGroupDevicesById.contains(mRightDevice)).isTrue();
        assertThat(firstGroupDevicesById.contains(mSingleDevice)).isFalse();
        assertThat(firstGroupDevicesById.equals(firstGroupDevicesByLeftDevice)).isTrue();
        assertThat(firstGroupDevicesById.equals(firstGroupDevicesByRightDevice)).isTrue();

        // Checks group device lists for groupId 2
        List<BluetoothDevice> secondGroupDevicesById = mService.getGroupDevices(TEST_GROUP_ID2);
        List<BluetoothDevice> secondGroupDevicesByDevice = mService.getGroupDevices(mSingleDevice);

        assertThat(secondGroupDevicesById).hasSize(1);
        assertThat(secondGroupDevicesById.contains(mSingleDevice)).isTrue();
        assertThat(secondGroupDevicesById.contains(mLeftDevice)).isFalse();
        assertThat(secondGroupDevicesById.contains(mRightDevice)).isFalse();
        assertThat(secondGroupDevicesById.equals(secondGroupDevicesByDevice)).isTrue();
    }

    /**
     * Tests that {@link LeAudioService#sendPreferredAudioProfileChangeToAudioFramework()} sends
     * requests to the audio framework for each active LEA device.
     */
    @Test
    public void testSendPreferredAudioProfileChangeToAudioFramework() {
        doReturn(true).when(mAdapterService).isAllSupportedClassicAudioProfilesActive(any());

        // TEST 1: Verify no requests are sent to the audio framework if there is no active device
        assertThat(mService.removeActiveDevice(false)).isTrue();
        List<BluetoothDevice> activeDevices = mService.getActiveDevices();
        assertThat(activeDevices.get(0)).isNull();
        assertThat(activeDevices.get(1)).isNull();
        assertThat(mService.sendPreferredAudioProfileChangeToAudioFramework()).isEqualTo(0);

        // TEST 2: Verify we send one request for each active direction
        /* AUDIO_DIRECTION_OUTPUT_BIT = 0x01 | AUDIO_DIRECTION_INPUT_BIT = 0x02; */
        int direction = 3;
        int availableContexts = 5;
        int nodeStatus = LeAudioStackEvent.GROUP_NODE_ADDED;
        int groupStatus = LeAudioStackEvent.GROUP_STATUS_ACTIVE;

        // Single active device
        connectTestDevice(mSingleDevice, TEST_GROUP_ID);

        // Add device to group
        LeAudioStackEvent nodeStatusChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_NODE_STATUS_CHANGED);
        nodeStatusChangedEvent.device = mSingleDevice;
        nodeStatusChangedEvent.valueInt1 = TEST_GROUP_ID;
        nodeStatusChangedEvent.valueInt2 = nodeStatus;
        mService.messageFromNative(nodeStatusChangedEvent);
        mLooper.dispatchAll();

        assertThat(mService.setActiveDevice(mSingleDevice)).isFalse();

        // Add location support
        injectAudioConfChanged(mSingleDevice, TEST_GROUP_ID, availableContexts, direction);

        assertThat(mService.setActiveDevice(mSingleDevice)).isTrue();

        // Set group and device as active
        LeAudioStackEvent groupStatusChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        groupStatusChangedEvent.device = mSingleDevice;
        groupStatusChangedEvent.valueInt1 = TEST_GROUP_ID;
        groupStatusChangedEvent.valueInt2 = groupStatus;
        mService.messageFromNative(groupStatusChangedEvent);
        mLooper.dispatchAll();

        assertThat(mService.getActiveDevices().contains(mSingleDevice)).isTrue();
        assertThat(mService.sendPreferredAudioProfileChangeToAudioFramework()).isEqualTo(2);
    }

    @Test
    public void testInactivateDeviceWhenNoAvailableContextTypes() {
        /* AUDIO_DIRECTION_OUTPUT_BIT = 0x01 */
        int direction = 1;
        int availableContexts = 5 + BluetoothLeAudio.CONTEXT_TYPE_RINGTONE;

        connectTestDevice(mLeftDevice, TEST_GROUP_ID);
        connectTestDevice(mRightDevice, TEST_GROUP_ID);

        // Checks group device lists for groupId 1
        List<BluetoothDevice> groupDevicesById = mService.getGroupDevices(TEST_GROUP_ID);

        assertThat(groupDevicesById).hasSize(2);
        assertThat(groupDevicesById.contains(mLeftDevice)).isTrue();
        assertThat(groupDevicesById.contains(mRightDevice)).isTrue();

        // Add location support
        injectAudioConfChanged(mSingleDevice, TEST_GROUP_ID, availableContexts, direction);

        assertThat(mService.setActiveDevice(mLeftDevice)).isTrue();
        mInOrder.verify(mNativeInterface).groupSetActive(TEST_GROUP_ID);

        // Set group and device as active.
        injectGroupStatusChange(TEST_GROUP_ID, LeAudioStackEvent.GROUP_STATUS_ACTIVE);
        mInOrder.verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        any(BluetoothDevice.class),
                        eq(null),
                        any(BluetoothProfileConnectionInfo.class));

        /* Don't expect any change. */
        injectAudioConfChanged(mSingleDevice, TEST_GROUP_ID, availableContexts, direction);
        mInOrder.verify(mNativeInterface, never()).groupSetActive(TEST_GROUP_ID);

        /* Expect device to be inactive */
        injectAudioConfChanged(mSingleDevice, TEST_GROUP_ID, 0, direction);

        mInOrder.verify(mNativeInterface).groupSetActive(BluetoothLeAudio.GROUP_ID_INVALID);
        injectGroupStatusChange(TEST_GROUP_ID, LeAudioStackEvent.GROUP_STATUS_INACTIVE);

        mInOrder.verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(null),
                        any(BluetoothDevice.class),
                        any(BluetoothProfileConnectionInfo.class));

        /* Expect device to be inactive */
        injectAudioConfChanged(mSingleDevice, TEST_GROUP_ID, 1, direction);

        mInOrder.verify(mNativeInterface).groupSetActive(TEST_GROUP_ID);

        // Set group and device as active.
        injectGroupStatusChange(TEST_GROUP_ID, LeAudioStackEvent.GROUP_STATUS_ACTIVE);
        mInOrder.verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        any(BluetoothDevice.class),
                        eq(null),
                        any(BluetoothProfileConnectionInfo.class));
    }

    @Test
    public void testAutoActiveMode_verifyDefaultState() {
        /* Test scenario:
         * 1. Connected two devices
         * 2. Verify that Auto Active Mode is true be default.
         */

        connectTestDevice(mLeftDevice, TEST_GROUP_ID);
        connectTestDevice(mRightDevice, TEST_GROUP_ID);

        assertThat(mService.isAutoActiveModeEnabled(TEST_GROUP_ID)).isTrue();
    }

    @Test
    public void testAutoActiveMode_whenDeviceIsConnecting_failToDisableIt() {
        /* Test scenario:
         * 1. Connecting device
         * 2. Verify that Auto Active Mode cannot be set.
         */

        setTestDeviceIntoConnectingState(mSingleDevice, TEST_GROUP_ID);
        assertThat(mService.setAutoActiveModeState(TEST_GROUP_ID, false)).isFalse();
    }

    @Test
    public void testAutoActiveMode_whenDeviceIsConnected_failToDisableIt() {
        /* AUDIO_DIRECTION_OUTPUT_BIT = 0x01 */
        int direction = 1;
        int availableContexts = 5 + BluetoothLeAudio.CONTEXT_TYPE_RINGTONE;

        /* Test scenario:
         * 1. Connected two devices
         * 2. Disconnect one device
         * 3. Verify that Auto Active Mode cannot be set.
         */

        connectTestDevice(mLeftDevice, TEST_GROUP_ID);
        connectTestDevice(mRightDevice, TEST_GROUP_ID);

        assertThat(mService.setAutoActiveModeState(TEST_GROUP_ID, false)).isFalse();

        // Checks group device lists for groupId 1
        List<BluetoothDevice> groupDevicesById = mService.getGroupDevices(TEST_GROUP_ID);

        assertThat(groupDevicesById).containsExactly(mLeftDevice, mRightDevice);

        injectAudioConfChanged(mSingleDevice, TEST_GROUP_ID, availableContexts, direction);
        assertThat(mService.isGroupAvailableForStream(TEST_GROUP_ID)).isTrue();

        injectAndVerifyDeviceDisconnected(mLeftDevice);

        assertThat(mService.setAutoActiveModeState(TEST_GROUP_ID, false)).isFalse();
    }

    @Test
    public void testAutoActiveMode_disabledWithSuccess() {
        /* AUDIO_DIRECTION_OUTPUT_BIT = 0x01 */
        int direction = 1;
        int availableContexts = 5 + BluetoothLeAudio.CONTEXT_TYPE_RINGTONE;

        /* Test scenario:
         * 1. Connected two devices
         * 2. Disconnect both devices
         * 3. Verify that Auto Active Mode can be set.
         */

        connectTestDevice(mLeftDevice, TEST_GROUP_ID);
        connectTestDevice(mRightDevice, TEST_GROUP_ID);

        // Checks group device lists for groupId 1
        List<BluetoothDevice> groupDevicesById = mService.getGroupDevices(TEST_GROUP_ID);

        assertThat(groupDevicesById).containsExactly(mLeftDevice, mRightDevice);

        injectAudioConfChanged(mSingleDevice, TEST_GROUP_ID, availableContexts, direction);

        assertThat(mService.isGroupAvailableForStream(TEST_GROUP_ID)).isTrue();

        injectAndVerifyDeviceDisconnected(mLeftDevice);
        injectAndVerifyDeviceDisconnected(mRightDevice);

        assertThat(mService.setAutoActiveModeState(TEST_GROUP_ID, false)).isTrue();
        assertThat(mService.isAutoActiveModeEnabled(TEST_GROUP_ID)).isFalse();
    }

    @Test
    public void testAutoActiveMode_whenUserSetsDeviceAsActive_resetToDefault() {
        /* AUDIO_DIRECTION_OUTPUT_BIT = 0x01 */
        int direction = 1;
        int availableContexts = 5 + BluetoothLeAudio.CONTEXT_TYPE_RINGTONE;

        /* Test scenario:
         * 1. Connected two devices
         * 2. Disconnect both devices
         * 3. Disable Auto Active Mode
         * 4. Connect at least one device
         * 5. Set group as Active
         * 6. Verify Auto Active Mode is back to default
         */

        mService.handleBluetoothEnabled();

        connectTestDevice(mLeftDevice, TEST_GROUP_ID);
        connectTestDevice(mRightDevice, TEST_GROUP_ID);

        // Checks group device lists for groupId 1
        List<BluetoothDevice> groupDevicesById = mService.getGroupDevices(TEST_GROUP_ID);

        assertThat(groupDevicesById).containsExactly(mLeftDevice, mRightDevice);

        injectAudioConfChanged(mSingleDevice, TEST_GROUP_ID, availableContexts, direction);

        assertThat(mService.isGroupAvailableForStream(TEST_GROUP_ID)).isTrue();

        injectAndVerifyDeviceDisconnected(mLeftDevice);
        injectAndVerifyDeviceDisconnected(mRightDevice);

        assertThat(mService.setAutoActiveModeState(TEST_GROUP_ID, false)).isTrue();
        assertThat(mService.isAutoActiveModeEnabled(TEST_GROUP_ID)).isFalse();

        injectAndVerifyDeviceConnected(mLeftDevice);
        injectAudioConfChanged(mSingleDevice, TEST_GROUP_ID, availableContexts, direction);

        assertThat(mService.setActiveDevice(mLeftDevice)).isTrue();
        assertThat(mService.isAutoActiveModeEnabled(TEST_GROUP_ID)).isTrue();
    }

    @Test
    public void testAutoActiveMode_whenRemoteUsesTargetedAnnouncements_resetToDefault()
            throws RemoteException {
        /* AUDIO_DIRECTION_OUTPUT_BIT = 0x01 */
        int direction = 1;
        int availableContexts = 5 + BluetoothLeAudio.CONTEXT_TYPE_RINGTONE;

        /* Test scenario:
         * 1. Connected two devices
         * 2. Disconnect both devices
         * 3. Disable Auto Active Mode
         * 4. Connect at least one device
         * 5. Detect TA on remote device
         * 6. Verify Auto Active Mode is back to default
         */

        mService.handleBluetoothEnabled();
        ArgumentCaptor<IScannerCallback> scanCallbacks =
                ArgumentCaptor.forClass(IScannerCallback.class);

        connectTestDevice(mLeftDevice, TEST_GROUP_ID);
        connectTestDevice(mRightDevice, TEST_GROUP_ID);

        // Checks group device lists for groupId 1
        List<BluetoothDevice> groupDevicesById = mService.getGroupDevices(TEST_GROUP_ID);

        assertThat(groupDevicesById).containsExactly(mLeftDevice, mRightDevice);
        injectAudioConfChanged(mSingleDevice, TEST_GROUP_ID, availableContexts, direction);

        assertThat(mService.isGroupAvailableForStream(TEST_GROUP_ID)).isTrue();

        injectAndVerifyDeviceDisconnected(mLeftDevice);
        injectAndVerifyDeviceDisconnected(mRightDevice);

        assertThat(mService.setAutoActiveModeState(TEST_GROUP_ID, false)).isTrue();
        assertThat(mService.isAutoActiveModeEnabled(TEST_GROUP_ID)).isFalse();

        injectAndVerifyDeviceConnected(mLeftDevice);
        injectAudioConfChanged(mSingleDevice, TEST_GROUP_ID, availableContexts, direction);

        verify(mScanController)
                .registerAndStartScanInternal(scanCallbacks.capture(), any(), any(), any());

        ScanResult scanResult = new ScanResult(mRightDevice, null, 0, 0);

        scanCallbacks.getValue().onScanResult(scanResult);

        assertThat(mService.isAutoActiveModeEnabled(TEST_GROUP_ID)).isTrue();
    }

    /**
     * Test the group is activated once the available contexts are back.
     *
     * <pre>
     * Scenario:
     *   1. Have a group of 2 devices that initially does not expose any available contexts. The
     * group shall be inactive at this point.
     *   2. Once the available contexts are updated with non-zero value, the group shall become
     * active.
     *   3. The available contexts are changed to zero. Group becomes inactive.
     *   4. The available contexts are back again. Group becomes active.
     * </pre>
     */
    @Test
    public void testActivateGroupWhenAvailableContextAreBack_Scenario1() {
        /* AUDIO_DIRECTION_OUTPUT_BIT = 0x01 */
        int direction = 1;
        int availableContexts = 5 + BluetoothLeAudio.CONTEXT_TYPE_RINGTONE;

        connectTestDevice(mLeftDevice, TEST_GROUP_ID);
        connectTestDevice(mRightDevice, TEST_GROUP_ID);

        // Checks group device lists for groupId 1
        List<BluetoothDevice> groupDevicesById = mService.getGroupDevices(TEST_GROUP_ID);

        assertThat(groupDevicesById).hasSize(2);
        assertThat(groupDevicesById.contains(mLeftDevice)).isTrue();
        assertThat(groupDevicesById.contains(mRightDevice)).isTrue();

        // Add location support
        injectAudioConfChanged(mSingleDevice, TEST_GROUP_ID, 0, direction);

        assertThat(mService.setActiveDevice(mLeftDevice)).isFalse();
        mInOrder.verify(mNativeInterface, never()).groupSetActive(TEST_GROUP_ID);

        // Expect device to be active
        injectAudioConfChanged(mSingleDevice, TEST_GROUP_ID, availableContexts, direction);

        mInOrder.verify(mNativeInterface).groupSetActive(TEST_GROUP_ID);

        // Set group and device as active.
        injectGroupStatusChange(TEST_GROUP_ID, LeAudioStackEvent.GROUP_STATUS_ACTIVE);
        mInOrder.verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        any(BluetoothDevice.class),
                        eq(null),
                        any(BluetoothProfileConnectionInfo.class));

        // Expect device to be inactive
        injectAudioConfChanged(mSingleDevice, TEST_GROUP_ID, 0, direction);

        mInOrder.verify(mNativeInterface).groupSetActive(BluetoothLeAudio.GROUP_ID_INVALID);
        injectGroupStatusChange(TEST_GROUP_ID, LeAudioStackEvent.GROUP_STATUS_INACTIVE);

        mInOrder.verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(null),
                        any(BluetoothDevice.class),
                        any(BluetoothProfileConnectionInfo.class));

        // Expect device to be active
        injectAudioConfChanged(mSingleDevice, TEST_GROUP_ID, availableContexts, direction);

        mInOrder.verify(mNativeInterface).groupSetActive(TEST_GROUP_ID);

        // Set group and device as active.
        injectGroupStatusChange(TEST_GROUP_ID, LeAudioStackEvent.GROUP_STATUS_ACTIVE);
        mInOrder.verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        any(BluetoothDevice.class),
                        eq(null),
                        any(BluetoothProfileConnectionInfo.class));
    }

    /**
     * Test the group is activated once the available contexts are back.
     *
     * <pre>
     * Scenario:
     *   1. Have a group of 2 devices. The available contexts are non-zero. The group shall be
     * active at this point.
     *   2. Once the available contexts are updated with zero value, the group shall become
     * inactive.
     *   3. All group devices are disconnected.
     *   4. Group devices are reconnected. The available contexts are still zero.
     *   5. The available contexts are updated with non-zero value. Group becomes active.
     * </pre>
     */
    @Test
    public void testActivateDeviceWhenAvailableContextAreBack_Scenario2() {
        /* AUDIO_DIRECTION_OUTPUT_BIT = 0x01 */
        int direction = 1;
        int availableContexts = 5 + BluetoothLeAudio.CONTEXT_TYPE_RINGTONE;

        connectTestDevice(mLeftDevice, TEST_GROUP_ID);
        connectTestDevice(mRightDevice, TEST_GROUP_ID);

        // Checks group device lists for groupId 1
        List<BluetoothDevice> groupDevicesById = mService.getGroupDevices(TEST_GROUP_ID);

        assertThat(groupDevicesById).hasSize(2);
        assertThat(groupDevicesById.contains(mLeftDevice)).isTrue();
        assertThat(groupDevicesById.contains(mRightDevice)).isTrue();

        // Add location support
        injectAudioConfChanged(mSingleDevice, TEST_GROUP_ID, availableContexts, direction);

        assertThat(mService.setActiveDevice(mLeftDevice)).isTrue();
        mInOrder.verify(mNativeInterface).groupSetActive(TEST_GROUP_ID);

        // Set group and device as active.
        injectGroupStatusChange(TEST_GROUP_ID, LeAudioStackEvent.GROUP_STATUS_ACTIVE);
        mInOrder.verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        any(BluetoothDevice.class),
                        eq(null),
                        any(BluetoothProfileConnectionInfo.class));

        // Expect device to be inactive
        injectAudioConfChanged(mSingleDevice, TEST_GROUP_ID, 0, direction);

        mInOrder.verify(mNativeInterface).groupSetActive(BluetoothLeAudio.GROUP_ID_INVALID);
        injectGroupStatusChange(TEST_GROUP_ID, LeAudioStackEvent.GROUP_STATUS_INACTIVE);

        mInOrder.verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        eq(null),
                        any(BluetoothDevice.class),
                        any(BluetoothProfileConnectionInfo.class));

        // Send a message to trigger disconnection completed to the left device
        injectAndVerifyDeviceDisconnected(mLeftDevice);

        // Send a message to trigger disconnection completed to the right device
        injectAndVerifyDeviceDisconnected(mRightDevice);

        // Verify the list of connected devices
        assertThat(mService.getConnectedDevices().contains(mLeftDevice)).isFalse();
        assertThat(mService.getConnectedDevices().contains(mRightDevice)).isFalse();

        // Expect device to be inactive
        injectAudioConfChanged(mSingleDevice, TEST_GROUP_ID, 0, direction);

        generateConnectionMessageFromNative(mLeftDevice, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getConnectedDevices().contains(mLeftDevice)).isTrue();

        // Expect device to be inactive
        injectAudioConfChanged(mSingleDevice, TEST_GROUP_ID, 0, direction);

        generateConnectionMessageFromNative(mRightDevice, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mRightDevice)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getConnectedDevices().contains(mRightDevice)).isTrue();

        // Expect device to be active
        injectAudioConfChanged(mSingleDevice, TEST_GROUP_ID, availableContexts, direction);

        mInOrder.verify(mNativeInterface).groupSetActive(TEST_GROUP_ID);

        // Set group and device as active.
        injectGroupStatusChange(TEST_GROUP_ID, LeAudioStackEvent.GROUP_STATUS_ACTIVE);
        mInOrder.verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        any(BluetoothDevice.class),
                        eq(null),
                        any(BluetoothProfileConnectionInfo.class));
    }

    //
    // Test the group is activated once the available contexts are back.
    //
    // Scenario:
    //  1. Have a group of 2 devices. The available contexts are non-zero.
    //     The group shall be active at this point.
    //  2. All group devices are disconnected.
    //  3. Group devices are reconnected. The available contexts are zero.
    //  4. The available contexts are updated with non-zero value. Group becomes active.
    //
    @Test
    public void testActivateDeviceWhenAvailableContextAreBack_Scenario3() {
        /* AUDIO_DIRECTION_OUTPUT_BIT = 0x01 */
        int direction = 1;
        int availableContexts = 5 + BluetoothLeAudio.CONTEXT_TYPE_RINGTONE;

        connectTestDevice(mLeftDevice, TEST_GROUP_ID);
        connectTestDevice(mRightDevice, TEST_GROUP_ID);

        // Checks group device lists for groupId 1
        List<BluetoothDevice> groupDevicesById = mService.getGroupDevices(TEST_GROUP_ID);

        assertThat(groupDevicesById).hasSize(2);
        assertThat(groupDevicesById.contains(mLeftDevice)).isTrue();
        assertThat(groupDevicesById.contains(mRightDevice)).isTrue();

        // Add location support
        injectAudioConfChanged(mSingleDevice, TEST_GROUP_ID, availableContexts, direction);

        assertThat(mService.setActiveDevice(mLeftDevice)).isTrue();
        mInOrder.verify(mNativeInterface).groupSetActive(TEST_GROUP_ID);

        // Set group and device as active.
        injectGroupStatusChange(TEST_GROUP_ID, LeAudioStackEvent.GROUP_STATUS_ACTIVE);
        mInOrder.verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        any(BluetoothDevice.class),
                        eq(null),
                        any(BluetoothProfileConnectionInfo.class));

        // Send a message to trigger disconnection completed to the right device
        injectAndVerifyDeviceDisconnected(mRightDevice);

        // Send a message to trigger disconnection completed to the left device
        injectAndVerifyDeviceDisconnected(mLeftDevice);

        // Expect device to be inactive
        injectAudioConfChanged(mSingleDevice, TEST_GROUP_ID, 0, direction);

        generateConnectionMessageFromNative(mLeftDevice, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mLeftDevice)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getConnectedDevices().contains(mLeftDevice)).isTrue();

        // Expect device to be inactive
        injectAudioConfChanged(mSingleDevice, TEST_GROUP_ID, 0, direction);

        generateConnectionMessageFromNative(mRightDevice, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mService.getConnectionState(mRightDevice)).isEqualTo(STATE_CONNECTED);
        assertThat(mService.getConnectedDevices().contains(mRightDevice)).isTrue();

        // Expect device to be active
        injectAudioConfChanged(mSingleDevice, TEST_GROUP_ID, availableContexts, direction);

        mInOrder.verify(mNativeInterface).groupSetActive(TEST_GROUP_ID);

        // Set group and device as active.
        injectGroupStatusChange(TEST_GROUP_ID, LeAudioStackEvent.GROUP_STATUS_ACTIVE);
        mInOrder.verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        any(BluetoothDevice.class),
                        eq(null),
                        any(BluetoothProfileConnectionInfo.class));
    }

    /** Test setting allowed contexts for active group */
    @Test
    public void testSetAllowedContextsForActiveGroup() {
        /* AUDIO_DIRECTION_OUTPUT_BIT = 0x01 */
        int direction = 1;
        int availableContexts = 5 + BluetoothLeAudio.CONTEXT_TYPE_RINGTONE;

        // Not connected device
        assertThat(mService.setActiveDevice(mSingleDevice)).isFalse();

        // Connect device
        connectTestDevice(mSingleDevice, TEST_GROUP_ID);

        // Add location support
        injectAudioConfChanged(mSingleDevice, TEST_GROUP_ID, availableContexts, direction);

        assertThat(mService.setActiveDevice(mSingleDevice)).isTrue();
        mInOrder.verify(mNativeInterface).groupSetActive(TEST_GROUP_ID);

        // Set group and device as active
        LeAudioStackEvent groupStatusChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        groupStatusChangedEvent.valueInt1 = TEST_GROUP_ID;
        groupStatusChangedEvent.valueInt2 = LeAudioStackEvent.GROUP_STATUS_ACTIVE;
        mService.messageFromNative(groupStatusChangedEvent);
        mLooper.dispatchAll();

        // Trigger update of allowed context for active group
        int sinkContextTypes =
                BluetoothLeAudio.CONTEXTS_ALL & ~BluetoothLeAudio.CONTEXT_TYPE_SOUND_EFFECTS;
        int sourceContextTypes =
                BluetoothLeAudio.CONTEXTS_ALL
                        & ~(BluetoothLeAudio.CONTEXT_TYPE_NOTIFICATIONS
                                | BluetoothLeAudio.CONTEXT_TYPE_GAME);

        mService.setActiveGroupAllowedContextMask(sinkContextTypes, sourceContextTypes);
        mInOrder.verify(mNativeInterface)
                .setGroupAllowedContextMask(TEST_GROUP_ID, sinkContextTypes, sourceContextTypes);

        // no active device, allowed context should be reset
        assertThat(mService.removeActiveDevice(false)).isTrue();
        mInOrder.verify(mNativeInterface).groupSetActive(BluetoothLeAudio.GROUP_ID_INVALID);

        // Set group and device as inactive
        groupStatusChangedEvent.valueInt2 = LeAudioStackEvent.GROUP_STATUS_INACTIVE;
        mService.messageFromNative(groupStatusChangedEvent);
        mLooper.dispatchAll();

        mInOrder.verify(mNativeInterface)
                .setGroupAllowedContextMask(
                        TEST_GROUP_ID,
                        BluetoothLeAudio.CONTEXTS_ALL,
                        BluetoothLeAudio.CONTEXTS_ALL);
    }

    @Test
    public void testSetCodecConfigPreference() {
        assertThat(mService.setActiveDevice(mSingleDevice)).isFalse();

        connectTestDevice(mSingleDevice, TEST_GROUP_ID);

        // Add location support
        injectAudioConfChanged(
                mSingleDevice,
                TEST_GROUP_ID,
                BluetoothLeAudio.CONTEXT_TYPE_RINGTONE,
                0x01 /*AUDIO_DIRECTION_OUTPUT_BIT*/);

        assertThat(mService.setActiveDevice(mSingleDevice)).isTrue();
        mInOrder.verify(mNativeInterface).groupSetActive(TEST_GROUP_ID);

        // Set group and device as active
        injectGroupStatusChange(TEST_GROUP_ID, LeAudioStackEvent.GROUP_STATUS_ACTIVE);

        // Set the initial group codec status
        injectGroupSelectableCodecConfigChanged(
                TEST_GROUP_ID, OPUS_SELECTABLE_CONFIGS, OPUS_SELECTABLE_CONFIGS);
        injectGroupCurrentCodecConfigChanged(TEST_GROUP_ID, LC3_16KHZ_CONFIG, LC3_48KHZ_CONFIG);

        // Update of codec config preference with LC3_16KHZ_CONFIG
        mService.setCodecConfigPreference(TEST_GROUP_ID, LC3_16KHZ_CONFIG, LC3_16KHZ_CONFIG);
        mInOrder.verify(mStorage)
                .setLeAudioCodecPreferences(
                        List.of(mSingleDevice),
                        Map.of(
                                LC3_16KHZ_CONFIG.getCodecType(),
                                new Pair<>(LC3_16KHZ_CONFIG, LC3_16KHZ_CONFIG)));

        // Update of codec config preference with OPUS_48KHZ_CONFIG
        mService.setCodecConfigPreference(TEST_GROUP_ID, OPUS_48KHZ_CONFIG, OPUS_48KHZ_CONFIG);
        mInOrder.verify(mStorage)
                .setLeAudioCodecPreferences(
                        List.of(mSingleDevice),
                        Map.of(
                                LC3_16KHZ_CONFIG.getCodecType(),
                                new Pair<>(LC3_16KHZ_CONFIG, LC3_16KHZ_CONFIG),
                                OPUS_48KHZ_CONFIG.getCodecType(),
                                new Pair<>(OPUS_48KHZ_CONFIG, OPUS_48KHZ_CONFIG)));

        // Update of codec config preference with OPUS_HI_RES_96KHZ_CONFIG
        mService.setCodecConfigPreference(
                TEST_GROUP_ID, OPUS_HI_RES_96KHZ_CONFIG, OPUS_HI_RES_96KHZ_CONFIG);
        mInOrder.verify(mStorage)
                .setLeAudioCodecPreferences(
                        List.of(mSingleDevice),
                        Map.of(
                                LC3_16KHZ_CONFIG.getCodecType(),
                                new Pair<>(LC3_16KHZ_CONFIG, LC3_16KHZ_CONFIG),
                                OPUS_48KHZ_CONFIG.getCodecType(),
                                new Pair<>(OPUS_48KHZ_CONFIG, OPUS_48KHZ_CONFIG),
                                OPUS_HI_RES_96KHZ_CONFIG.getCodecType(),
                                new Pair<>(OPUS_HI_RES_96KHZ_CONFIG, OPUS_HI_RES_96KHZ_CONFIG)));
    }

    @Test
    public void testCodecConfigPreferenceRestore() {
        assertThat(mService.setActiveDevice(mSingleDevice)).isFalse();
        connectTestDevice(mSingleDevice, TEST_GROUP_ID);

        // Add location support
        injectAudioConfChanged(
                mSingleDevice,
                TEST_GROUP_ID,
                BluetoothLeAudio.CONTEXT_TYPE_RINGTONE,
                0x01 /*AUDIO_DIRECTION_OUTPUT_BIT*/);

        assertThat(mService.setActiveDevice(mSingleDevice)).isTrue();
        mInOrder.verify(mNativeInterface).groupSetActive(TEST_GROUP_ID);

        // Set group and device as active
        injectGroupStatusChange(TEST_GROUP_ID, LeAudioStackEvent.GROUP_STATUS_ACTIVE);

        // Set the initial group codec status
        injectGroupSelectableCodecConfigChanged(
                TEST_GROUP_ID, OPUS_SELECTABLE_CONFIGS, OPUS_SELECTABLE_CONFIGS);
        injectGroupCurrentCodecConfigChanged(TEST_GROUP_ID, LC3_16KHZ_CONFIG, LC3_48KHZ_CONFIG);

        mService.setCodecConfigPreference(TEST_GROUP_ID, LC3_16KHZ_CONFIG, LC3_16KHZ_CONFIG);
        mService.setCodecConfigPreference(TEST_GROUP_ID, OPUS_48KHZ_CONFIG, OPUS_48KHZ_CONFIG);
        mService.setCodecConfigPreference(
                TEST_GROUP_ID, OPUS_HI_RES_96KHZ_CONFIG, OPUS_HI_RES_96KHZ_CONFIG);

        injectGroupStatusChange(TEST_GROUP_ID, LeAudioStackEvent.GROUP_STATUS_INACTIVE);

        // Set group and device as active.
        injectGroupStatusChange(TEST_GROUP_ID, LeAudioStackEvent.GROUP_STATUS_ACTIVE);
        injectGroupSelectableCodecConfigChanged(
                TEST_GROUP_ID, OPUS_SELECTABLE_CONFIGS, OPUS_SELECTABLE_CONFIGS);
        injectGroupCurrentCodecConfigChanged(
                TEST_GROUP_ID, OPUS_HI_RES_96KHZ_CONFIG, OPUS_HI_RES_96KHZ_CONFIG);

        // Verify if preferences were retrieved and reapplied to native
        mInOrder.verify(mStorage).getLeAudioCodecPreferences(List.of(mSingleDevice));

        mInOrder.verify(mNativeInterface)
                .setCodecConfigPreference(TEST_GROUP_ID, LC3_16KHZ_CONFIG, LC3_16KHZ_CONFIG);
        mInOrder.verify(mNativeInterface)
                .setCodecConfigPreference(TEST_GROUP_ID, OPUS_48KHZ_CONFIG, OPUS_48KHZ_CONFIG);
        mInOrder.verify(mNativeInterface)
                .setCodecConfigPreference(
                        TEST_GROUP_ID, OPUS_HI_RES_96KHZ_CONFIG, OPUS_HI_RES_96KHZ_CONFIG);
    }

    // TODO: Add test with different codec priorities

    /** Test managing broadcast to unicast fallback group */
    @Test
    public void testManageBroadcastToUnicastFallbackGroup() {
        /* AUDIO_DIRECTION_OUTPUT_BIT = 0x01 */
        int direction = 1;
        int availableContexts = 5 + BluetoothLeAudio.CONTEXT_TYPE_RINGTONE;
        Set<BluetoothDevice> broadcastReceivers = new HashSet<>();

        doReturn(mSingleDevice).when(mStorage).getLeastRecentlyConnectedDeviceInList(any());

        // Not connected device
        assertThat(mService.setActiveDevice(mSingleDevice)).isFalse();
        assertThat(mService.getBroadcastToUnicastFallbackGroup())
                .isEqualTo(BluetoothLeAudio.GROUP_ID_INVALID);

        // Connect device
        connectTestDevice(mSingleDevice, TEST_GROUP_ID);

        /* Mock device1 as receiving broadcast device */
        broadcastReceivers.add(mSingleDevice);
        mService.selectDefaultBroadcastToUnicastFallbackGroup(broadcastReceivers);

        // Group should be updated to default (earliest connected)
        assertThat(mService.getBroadcastToUnicastFallbackGroup()).isEqualTo(TEST_GROUP_ID);

        // Add location support
        injectAudioConfChanged(mSingleDevice, TEST_GROUP_ID, availableContexts, direction);

        assertThat(mService.setActiveDevice(mSingleDevice)).isTrue();
        mInOrder.verify(mNativeInterface).groupSetActive(TEST_GROUP_ID);

        // Set group and device as active
        LeAudioStackEvent groupStatusChangedEvent =
                new LeAudioStackEvent(LeAudioStackEvent.EVENT_TYPE_GROUP_STATUS_CHANGED);
        groupStatusChangedEvent.valueInt1 = TEST_GROUP_ID;
        groupStatusChangedEvent.valueInt2 = LeAudioStackEvent.GROUP_STATUS_ACTIVE;
        mService.messageFromNative(groupStatusChangedEvent);
        mLooper.dispatchAll();

        // Set fallback group to not valid (not connected)
        mService.setBroadcastToUnicastFallbackGroup(TEST_GROUP_ID2);

        // Connect second device
        connectTestDevice(mLeftDevice, TEST_GROUP_ID2);
        mService.deviceConnected(mLeftDevice);

        /* Mock device2 as receiving broadcast device */
        broadcastReceivers.add(mLeftDevice);
        mService.selectDefaultBroadcastToUnicastFallbackGroup(broadcastReceivers);

        // Fallback device should remain earliest connected
        assertThat(mService.getBroadcastToUnicastFallbackGroup()).isEqualTo(TEST_GROUP_ID);

        // Set fallback group to valid second
        mService.setBroadcastToUnicastFallbackGroup(TEST_GROUP_ID2);

        // Fallback device should be changed to second
        assertThat(mService.getBroadcastToUnicastFallbackGroup()).isEqualTo(TEST_GROUP_ID2);

        // no active device
        assertThat(mService.removeActiveDevice(false)).isTrue();
        mInOrder.verify(mNativeInterface).groupSetActive(BluetoothLeAudio.GROUP_ID_INVALID);

        // Set group and device as inactive
        groupStatusChangedEvent.valueInt2 = LeAudioStackEvent.GROUP_STATUS_INACTIVE;
        mService.messageFromNative(groupStatusChangedEvent);
        mLooper.dispatchAll();
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_FALLBACK_GROUP_SELECTION)
    public void testSelectDefaultBroadcastToUnicastFallbackGroupWhenFlagEnabled() {
        doReturn(mRightDevice).when(mStorage).getLeastRecentlyConnectedDeviceInList(any());

        // Connect devices to groups and create descriptors.
        int groupIdLeft = 1;
        int groupIdRight = 2;
        int groupIdSingle = 3;
        connectTestDevice(mLeftDevice, groupIdLeft);
        connectTestDevice(mRightDevice, groupIdRight);
        connectTestDevice(mSingleDevice, groupIdSingle);

        // Invoke the new group selection function.
        mService.selectDefaultBroadcastToUnicastFallbackGroup(Set.of(mRightDevice, mSingleDevice));

        // Verification: Although mLeftDevice is in the "mostRecent" list, it is not a receiver,
        // so the selected group is from mRightDevice, which is the oldest connection among the
        // receivers.
        assertThat(mService.getBroadcastToUnicastFallbackGroup()).isEqualTo(groupIdRight);

        // Change the list of receivers to contain only the oldest device.
        doReturn(mLeftDevice).when(mStorage).getLeastRecentlyConnectedDeviceInList(any());
        mService.selectDefaultBroadcastToUnicastFallbackGroup(Set.of(mLeftDevice));

        // Verification: Now the selected group is from mLeftDevice.
        assertThat(mService.getBroadcastToUnicastFallbackGroup()).isEqualTo(groupIdLeft);
    }

    @Test
    public void registerUnregisterCallback() {
        IBluetoothLeAudioCallback callback = Mockito.mock(IBluetoothLeAudioCallback.class);
        Binder binder = Mockito.mock(Binder.class);
        doReturn(binder).when(callback).asBinder();

        synchronized (mService.mLeAudioCallbacks) {
            assertThat(mService.mLeAudioCallbacks.beginBroadcast()).isEqualTo(0);
            mService.mLeAudioCallbacks.finishBroadcast();

            mService.registerCallback(callback);
            assertThat(mService.mLeAudioCallbacks.beginBroadcast()).isEqualTo(1);
            mService.mLeAudioCallbacks.finishBroadcast();

            mService.unregisterCallback(callback);
            assertThat(mService.mLeAudioCallbacks.beginBroadcast()).isEqualTo(0);
            mService.mLeAudioCallbacks.finishBroadcast();
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_GAME_DETECTOR)
    public void testGameModeActivation_singleGame() throws Exception {
        int testUid = 1001;
        String gamePackageName = "com.example.game";

        // Mock package manager to identify the app as a game
        doReturn(new String[] {gamePackageName}).when(mPackageManager).getPackagesForUid(testUid);
        ApplicationInfo gameAppInfo = new ApplicationInfo();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            gameAppInfo.category = ApplicationInfo.CATEGORY_GAME;
        } else {
            gameAppInfo.flags = ApplicationInfo.FLAG_IS_GAME;
        }

        doReturn(gameAppInfo)
                .when(mPackageManager)
                .getApplicationInfo(eq(gamePackageName), anyInt());

        ArgumentCaptor<ActivityManager.OnUidImportanceListener> listenerCaptor =
                ArgumentCaptor.forClass(ActivityManager.OnUidImportanceListener.class);

        verify(mActivityManager)
                .addOnUidImportanceListener(
                        listenerCaptor.capture(),
                        eq(ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE));

        // Initial state: no games running
        assertThat(mService.mGameTrackingList).isEmpty();
        verify(mNativeInterface, never()).setInGame(anyBoolean());

        // 1. Game app comes to foreground
        listenerCaptor
                .getValue()
                .onUidImportance(
                        testUid, ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
        mLooper.dispatchAll();

        // Verify game mode is enabled
        verify(mNativeInterface).setInGame(true);
        assertThat(mService.mGameTrackingList).hasSize(1);
        assertThat(mService.mGameTrackingList.get(0).getUid()).isEqualTo(testUid);

        // 2. Game app goes to background (cached)
        listenerCaptor
                .getValue()
                .onUidImportance(testUid, ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED);
        mLooper.dispatchAll();

        // Verify game mode is still enabled, and background monitor is active
        verify(mNativeInterface, times(1)).setInGame(true); // no change
        assertThat(mService.mGameTrackingList).hasSize(1);
        assertThat(mService.mGameTrackingList.get(0).isBackgroundMonitorActive()).isTrue();

        // 3. Game app is gone
        listenerCaptor
                .getValue()
                .onUidImportance(testUid, ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE);
        mLooper.dispatchAll();

        // Verify game mode is disabled
        verify(mNativeInterface).setInGame(false);
        assertThat(mService.mGameTrackingList).isEmpty();
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_GAME_DETECTOR)
    public void testGameModeActivation_multipleGames() throws Exception {
        int testUid1 = 1001;
        String gamePackageName1 = "com.example.game1";
        int testUid2 = 1002;
        String gamePackageName2 = "com.example.game2";

        // Mock package manager for two game apps
        doReturn(new String[] {gamePackageName1}).when(mPackageManager).getPackagesForUid(testUid1);
        doReturn(new String[] {gamePackageName2}).when(mPackageManager).getPackagesForUid(testUid2);
        ApplicationInfo gameAppInfo = new ApplicationInfo();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            gameAppInfo.category = ApplicationInfo.CATEGORY_GAME;
        } else {
            gameAppInfo.flags = ApplicationInfo.FLAG_IS_GAME;
        }
        doReturn(gameAppInfo)
                .when(mPackageManager)
                .getApplicationInfo(eq(gamePackageName1), anyInt());
        doReturn(gameAppInfo)
                .when(mPackageManager)
                .getApplicationInfo(eq(gamePackageName2), anyInt());

        ArgumentCaptor<ActivityManager.OnUidImportanceListener> listenerCaptor =
                ArgumentCaptor.forClass(ActivityManager.OnUidImportanceListener.class);

        verify(mActivityManager)
                .addOnUidImportanceListener(
                        listenerCaptor.capture(),
                        eq(ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE));
        // Initial state
        assertThat(mService.mGameTrackingList).isEmpty();

        // 1. First game app comes to foreground
        listenerCaptor
                .getValue()
                .onUidImportance(
                        testUid1, ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
        mLooper.dispatchAll();

        // Verify game mode is enabled
        verify(mNativeInterface, times(1)).setInGame(true);
        assertThat(mService.mGameTrackingList).hasSize(1);

        // 2. Second game app comes to foreground
        listenerCaptor
                .getValue()
                .onUidImportance(
                        testUid2, ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
        mLooper.dispatchAll();

        // Verify setInGame(true) is not called again
        verify(mNativeInterface, times(1)).setInGame(true);
        assertThat(mService.mGameTrackingList).hasSize(2);

        // 3. First game app goes away
        listenerCaptor
                .getValue()
                .onUidImportance(testUid1, ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE);
        mLooper.dispatchAll();

        // Verify game mode is still enabled
        verify(mNativeInterface, never()).setInGame(false);
        assertThat(mService.mGameTrackingList).hasSize(1);

        // 4. Second game app goes to background (cached)
        listenerCaptor
                .getValue()
                .onUidImportance(testUid2, ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED);
        mLooper.dispatchAll();

        // Verify game mode is still enabled and background monitor is active
        verify(mNativeInterface, never()).setInGame(false);
        assertThat(mService.mGameTrackingList).hasSize(1);
        assertThat(mService.mGameTrackingList.get(0).isBackgroundMonitorActive()).isTrue();

        // 5. Second game app comes back to foreground
        listenerCaptor
                .getValue()
                .onUidImportance(
                        testUid2, ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
        mLooper.dispatchAll();

        // Verify background monitor is stopped
        assertThat(mService.mGameTrackingList.get(0).isBackgroundMonitorActive()).isFalse();

        // 6. Second game app finally goes away
        listenerCaptor
                .getValue()
                .onUidImportance(testUid2, ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE);
        mLooper.dispatchAll();

        // Verify game mode is disabled
        verify(mNativeInterface).setInGame(false);
        assertThat(mService.mGameTrackingList).isEmpty();
    }

    private void verifyActiveDeviceStateIntent(BluetoothDevice device) {
        verifyIntentSentMultiplePermissions(
                hasAction(BluetoothLeAudio.ACTION_LE_AUDIO_ACTIVE_DEVICE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, device));
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

    private void verifyNoIntentSent() {
        mInOrder.verify(mAdapterService, never()).sendBroadcast(any(), any(), any());
        mInOrder.verify(mAdapterService, never())
                .sendBroadcastWithMultiplePermissions(any(), any());
    }

    @Test
    public void testSetAllowlistFlag() {
        BluetoothDevice device = getTestDevice(10);
        mService.setAllowlistFlag(device, true);
        verify(mNativeInterface).setAllowlistFlag(device, true);
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_ALLOWLIST_REFACTOR)
    public void testDeviceConnected_callsSetAllowlistFlag() {
        BluetoothDevice device = getTestDevice(11);
        mService.createDeviceDescriptor(device, true);

        doReturn(true).when(mAdapterService).isLeAudioAllowed(device);

        mService.deviceConnected(device);

        verify(mNativeInterface).setAllowlistFlag(device, true);
    }

    private void verifyIntentSentMultiplePermissions(Matcher<Intent>... matchers) {
        mInOrder.verify(mAdapterService)
                .sendBroadcastWithMultiplePermissions(
                        MockitoHamcrest.argThat(AllOf.allOf(matchers)), any());
    }

    @SafeVarargs
    private void verifyIntentSent(Matcher<Intent>... matchers) {
        mInOrder.verify(mAdapterService)
                .sendBroadcast(MockitoHamcrest.argThat(AllOf.allOf(matchers)), any(), any());
    }
}
