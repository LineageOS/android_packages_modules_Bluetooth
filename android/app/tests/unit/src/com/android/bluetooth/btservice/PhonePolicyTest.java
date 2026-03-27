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

import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;

import static com.android.bluetooth.TestUtils.getTestDevice;
import static com.android.bluetooth.TestUtils.mockGetRemoteDevice;
import static com.android.bluetooth.TestUtils.mockSystemPropertyGet;
import static com.android.bluetooth.btservice.PhonePolicy.BYPASS_LE_AUDIO_ALLOWLIST_PROPERTY;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.bluetooth.BluetoothCsipSetCoordinator;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.State;
import android.content.pm.ApplicationInfo;
import android.os.ParcelUuid;
import android.os.SystemProperties;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;

import com.android.bluetooth.TestLooper;
import com.android.bluetooth.Utils;
import com.android.bluetooth.a2dp.A2dpService;
import com.android.bluetooth.csip.CsipSetCoordinatorService;
import com.android.bluetooth.hap.HapClientService;
import com.android.bluetooth.hearingaid.HearingAidService;
import com.android.bluetooth.hfp.HeadsetService;
import com.android.bluetooth.le_audio.LeAudioService;
import com.android.bluetooth.mcp.McpClientService;
import com.android.bluetooth.storage.BluetoothStorageManager;
import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.tests.bluetooth.StaticMockitoRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Test cases for {@link PhonePolicy}. */
@MediumTest
@RunWith(AndroidJUnit4.class)
public class PhonePolicyTest {
    @Rule
    public final StaticMockitoRule mMockitoRule =
            new StaticMockitoRule(SystemProperties.class, McpClientService.class);

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();
    @Rule public final TemporaryFolder mTempFolder = new TemporaryFolder();

    @Mock private AdapterService mAdapterService;
    @Mock private HeadsetService mHeadsetService;
    @Mock private A2dpService mA2dpService;
    @Mock private LeAudioService mLeAudioService;
    @Mock private CsipSetCoordinatorService mCsipSetCoordinatorService;
    @Mock private HearingAidService mHearingAidService;
    @Mock private ApplicationInfo mMockApplicationInfo;
    @Mock private HapClientService mHapClientService;
    @Mock private McpClientService mMcpClientService;

    private static final int MAX_CONNECTED_AUDIO_DEVICES = 5;

    private final BluetoothDevice mDevice1 = getTestDevice(0);
    private final BluetoothDevice mDevice2 = getTestDevice(1);
    private final BluetoothDevice mDevice3 = getTestDevice(2);
    private final BluetoothDevice mDevice4 = getTestDevice(3);

    private PhonePolicy mPhonePolicy;
    private BluetoothStorageManager mStorage;
    private boolean mOriginalDualModeState;
    private TestLooper mLooper;

    private List<BluetoothDevice> mLeAudioAllowedConnectionPolicyList;

    @Before
    public void setUp() throws Exception {
        mLeAudioAllowedConnectionPolicyList = new ArrayList<>();
        mLooper = new TestLooper();

        // Stub A2DP and HFP
        doReturn(true).when(mHeadsetService).connect(any());
        doReturn(true).when(mA2dpService).connect(any());

        doReturn(mMockApplicationInfo).when(mAdapterService).getApplicationInfo();
        doReturn(mTempFolder.getRoot()).when(mAdapterService).getFilesDir();
        doAnswer(it -> new File(mTempFolder.getRoot(), it.getArgument(0)))
                .when(mAdapterService)
                .getDatabasePath(anyString());

        doReturn(Collections.emptySet()).when(mAdapterService).getBondedDevices();
        doReturn(mAdapterService).when(mAdapterService).createDeviceProtectedStorageContext();
        doReturn(State.ON).when(mAdapterService).getState();
        doReturn(MAX_CONNECTED_AUDIO_DEVICES).when(mAdapterService).getMaxConnectedAudioDevices();
        doReturn(Optional.of(mA2dpService)).when(mAdapterService).getA2dpService();
        doReturn(Optional.of(mCsipSetCoordinatorService))
                .when(mAdapterService)
                .getCsipSetCoordinatorService();
        doReturn(Optional.of(mHeadsetService)).when(mAdapterService).getHeadsetService();
        doReturn(Optional.of(mHearingAidService)).when(mAdapterService).getHearingAidService();
        doReturn(Optional.of(mHapClientService)).when(mAdapterService).getHapClientService();
        doReturn(Optional.of(mLeAudioService)).when(mAdapterService).getLeAudioService();
        doReturn(Optional.of(mMcpClientService)).when(mAdapterService).getMcpClientService();

        // Most common default
        doReturn(CONNECTION_POLICY_UNKNOWN).when(mHeadsetService).getConnectionPolicy(any());
        doReturn(CONNECTION_POLICY_UNKNOWN).when(mA2dpService).getConnectionPolicy(any());
        doReturn(CONNECTION_POLICY_UNKNOWN).when(mLeAudioService).getConnectionPolicy(any());
        doReturn(CONNECTION_POLICY_UNKNOWN).when(mMcpClientService).getConnectionPolicy(any());
        ExtendedMockito.doReturn(true).when(() -> McpClientService.isEnabled());
        doReturn(STATE_DISCONNECTED).when(mA2dpService).getConnectionState(any());
        doReturn(STATE_DISCONNECTED).when(mHeadsetService).getConnectionState(any());
        doReturn(Collections.emptyList()).when(mA2dpService).getConnectedDevices();
        doReturn(Collections.emptyList()).when(mHeadsetService).getConnectedDevices();

        mockGetRemoteDevice(mAdapterService, mDevice1, mDevice2, mDevice3, mDevice4);

        mStorage = spy(new BluetoothStorageManager(mAdapterService));

        mPhonePolicy = new PhonePolicy(mAdapterService, mLooper.getLooper(), mStorage);
        mOriginalDualModeState = Utils.isDualModeAudioEnabled();
    }

    @After
    public void tearDown() throws Exception {
        Utils.setDualModeAudioStateForTesting(mOriginalDualModeState);
    }

    int getLeAudioConnectionPolicy(BluetoothDevice dev) {
        if (!mLeAudioAllowedConnectionPolicyList.contains(dev)) {
            return CONNECTION_POLICY_UNKNOWN;
        }
        return CONNECTION_POLICY_ALLOWED;
    }

    boolean setLeAudioAllowedConnectionPolicy(BluetoothDevice dev) {
        mLeAudioAllowedConnectionPolicyList.add(dev);
        return true;
    }

    /**
     * Test that when new UUIDs are refreshed for a device then we set the priorities for various
     * profiles accurately. The following profiles should have ON priorities: A2DP, HFP, HID and PAN
     */
    @Test
    public void testProcessInitProfilePriorities() {
        mPhonePolicy.mAutoConnectProfilesSupported = false;

        ParcelUuid[] uuids = {BluetoothUuid.HFP, BluetoothUuid.A2DP_SINK};
        mPhonePolicy.onUuidsDiscovered(mDevice1, uuids);

        verify(mAdapterService)
                .setProfileConnectionPolicy(
                        mDevice1, BluetoothProfile.HEADSET, CONNECTION_POLICY_ALLOWED);
        verify(mAdapterService)
                .setProfileConnectionPolicy(
                        mDevice1, BluetoothProfile.A2DP, CONNECTION_POLICY_ALLOWED);
    }

    @Test
    public void testProcessInitProfilePriorities_McpClient() {
        mPhonePolicy.mAutoConnectProfilesSupported = true;

        ParcelUuid[] uuids = {BluetoothUuid.GENERIC_MEDIA_CONTROL};
        mPhonePolicy.onUuidsDiscovered(mDevice1, uuids);

        verify(mMcpClientService).setConnectionPolicy(mDevice1, CONNECTION_POLICY_ALLOWED);
    }

    private void processInitProfilePriorities_LeAudioOnlyHelper(
            int csipGroupId, int groupSize, boolean dualMode, boolean ashaDevice) {
        Utils.setDualModeAudioStateForTesting(false);
        mPhonePolicy.mLeAudioEnabledByDefault = true;
        mPhonePolicy.mAutoConnectProfilesSupported = true;
        mockSystemPropertyGet(BYPASS_LE_AUDIO_ALLOWLIST_PROPERTY, false);

        int testedDeviceType = BluetoothDevice.DEVICE_TYPE_LE;
        if (dualMode) {
            /* If CSIP mode, use DUAL type only for single device */
            testedDeviceType = BluetoothDevice.DEVICE_TYPE_DUAL;
        }

        List<BluetoothDevice> allConnectedDevices = new ArrayList<>();
        for (int i = 0; i < groupSize; i++) {
            BluetoothDevice device = getTestDevice(i);
            allConnectedDevices.add(device);
        }

        doReturn(csipGroupId).when(mCsipSetCoordinatorService).getGroupId(any(), any());
        doReturn(groupSize).when(mCsipSetCoordinatorService).getDesiredGroupSize(csipGroupId);

        /* Build list of test UUIDs */
        int numOfServices = 1;
        if (groupSize > 1) {
            numOfServices++;
        }
        if (ashaDevice) {
            numOfServices++;
        }
        ParcelUuid[] uuids = new ParcelUuid[numOfServices];
        int iter = 0;
        uuids[iter++] = BluetoothUuid.LE_AUDIO;
        if (groupSize > 1) {
            uuids[iter++] = BluetoothUuid.COORDINATED_SET;
        }
        if (ashaDevice) {
            uuids[iter++] = BluetoothUuid.HEARING_AID;
        }

        doReturn(uuids).when(mAdapterService).getRemoteUuids(any());
        doReturn(true)
                .when(mAdapterService)
                .isProfileSupported(any(), eq(BluetoothProfile.LE_AUDIO));
        doReturn(ashaDevice)
                .when(mAdapterService)
                .isProfileSupported(any(), eq(BluetoothProfile.HEARING_AID));

        List<BluetoothDevice> connectedDevices = new ArrayList<>();
        doReturn(connectedDevices)
                .when(mCsipSetCoordinatorService)
                .getGroupDevicesOrdered(csipGroupId);

        for (BluetoothDevice dev : allConnectedDevices) {
            doReturn(BluetoothDevice.BOND_BONDED).when(mAdapterService).getBondState(dev);
            doAnswer(
                            invocation -> {
                                return setLeAudioAllowedConnectionPolicy(dev);
                            })
                    .when(mLeAudioService)
                    .setConnectionPolicy(dev, CONNECTION_POLICY_ALLOWED);
            doAnswer(
                            invocation -> {
                                return getLeAudioConnectionPolicy(dev);
                            })
                    .when(mLeAudioService)
                    .getConnectionPolicy(dev);

            /* First device is always LE only second depends on dualMode */
            if (groupSize == 1 || connectedDevices.size() >= 1) {
                doReturn(testedDeviceType).when(mAdapterService).getRemoteType(dev);
            } else {
                doReturn(BluetoothDevice.DEVICE_TYPE_LE).when(mAdapterService).getRemoteType(dev);
            }

            mPhonePolicy.onUuidsDiscovered(dev, uuids);
            if (groupSize > 1) {
                connectedDevices.add(dev);
                // Simulate CSIP connection
                mPhonePolicy.profileConnectionStateChanged(
                        BluetoothProfile.CSIP_SET_COORDINATOR,
                        dev,
                        STATE_DISCONNECTED,
                        STATE_CONNECTED);
                mLooper.dispatchAll();
            }
        }
    }

    @Test
    public void testConnectLeAudioOnlyDevices_BandedHeadphones() {
        // Single device, no CSIP
        processInitProfilePriorities_LeAudioOnlyHelper(
                BluetoothCsipSetCoordinator.GROUP_ID_INVALID, 1, false, false);
        verify(mLeAudioService)
                .setConnectionPolicy(any(BluetoothDevice.class), eq(CONNECTION_POLICY_ALLOWED));
    }

    @Test
    public void testConnectLeAudioOnlyDevices_CsipSet() {
        // CSIP Le Audio only devices
        processInitProfilePriorities_LeAudioOnlyHelper(1, 2, false, false);
        verify(mLeAudioService, times(2))
                .setConnectionPolicy(any(BluetoothDevice.class), eq(CONNECTION_POLICY_ALLOWED));
    }

    @Test
    public void testConnectLeAudioOnlyDevices_DualModeCsipSet() {
        // CSIP Dual mode devices
        processInitProfilePriorities_LeAudioOnlyHelper(1, 2, true, false);
        verify(mLeAudioService, never())
                .setConnectionPolicy(any(BluetoothDevice.class), eq(CONNECTION_POLICY_ALLOWED));
    }

    @Test
    public void testConnectLeAudioOnlyDevices_AshaAndCsipSet() {
        // CSIP Dual mode devices
        processInitProfilePriorities_LeAudioOnlyHelper(1, 2, false, true);
        verify(mLeAudioService, never())
                .setConnectionPolicy(any(BluetoothDevice.class), eq(CONNECTION_POLICY_ALLOWED));
    }

    @Test
    public void testProcessInitProfilePriorities_WithAutoConnect() {
        mPhonePolicy.mAutoConnectProfilesSupported = true;

        // Inject an event for UUIDs updated for a remote device with only HFP enabled
        ParcelUuid[] uuids = new ParcelUuid[2];
        uuids[0] = BluetoothUuid.HFP;
        uuids[1] = BluetoothUuid.A2DP_SINK;
        mPhonePolicy.onUuidsDiscovered(mDevice1, uuids);

        // Check auto connect
        verify(mA2dpService).setConnectionPolicy(mDevice1, CONNECTION_POLICY_ALLOWED);
        verify(mHeadsetService).setConnectionPolicy(mDevice1, CONNECTION_POLICY_ALLOWED);
    }

    @Test
    public void testProcessInitProfilePriorities_LeAudioDisabledByDefault() {
        doReturn(true).when(mAdapterService).isLeAudioAllowed(mDevice1);

        // Auto connect to LE audio, HFP, A2DP
        processInitProfilePriorities_LeAudioHelper(true, true, false, false);
        verify(mLeAudioService).setConnectionPolicy(mDevice1, CONNECTION_POLICY_ALLOWED);
        verify(mA2dpService).setConnectionPolicy(mDevice1, CONNECTION_POLICY_ALLOWED);
        verify(mHeadsetService).setConnectionPolicy(mDevice1, CONNECTION_POLICY_ALLOWED);

        // Does not auto connect and allow HFP and A2DP to be connected
        processInitProfilePriorities_LeAudioHelper(true, false, false, false);
        verify(mAdapterService)
                .setProfileConnectionPolicy(
                        mDevice1, BluetoothProfile.LE_AUDIO, CONNECTION_POLICY_ALLOWED);
        verify(mAdapterService)
                .setProfileConnectionPolicy(
                        mDevice1, BluetoothProfile.A2DP, CONNECTION_POLICY_ALLOWED);
        verify(mAdapterService)
                .setProfileConnectionPolicy(
                        mDevice1, BluetoothProfile.HEADSET, CONNECTION_POLICY_ALLOWED);

        // Auto connect to HFP and A2DP but disallow LE Audio
        processInitProfilePriorities_LeAudioHelper(false, true, false, false);
        verify(mAdapterService)
                .setProfileConnectionPolicy(
                        mDevice1, BluetoothProfile.LE_AUDIO, CONNECTION_POLICY_FORBIDDEN);
        verify(mA2dpService, times(2)).setConnectionPolicy(mDevice1, CONNECTION_POLICY_ALLOWED);
        verify(mHeadsetService, times(2)).setConnectionPolicy(mDevice1, CONNECTION_POLICY_ALLOWED);

        // Does not auto connect and disallow LE Audio to be connected
        processInitProfilePriorities_LeAudioHelper(false, false, false, false);
        verify(mAdapterService, times(2))
                .setProfileConnectionPolicy(
                        mDevice1, BluetoothProfile.LE_AUDIO, CONNECTION_POLICY_FORBIDDEN);
        verify(mAdapterService, times(2))
                .setProfileConnectionPolicy(
                        mDevice1, BluetoothProfile.A2DP, CONNECTION_POLICY_ALLOWED);
        verify(mAdapterService, times(2))
                .setProfileConnectionPolicy(
                        mDevice1, BluetoothProfile.HEADSET, CONNECTION_POLICY_ALLOWED);
    }

    @Test
    public void testProcessInitProfilePriorities_LeAudioEnabledByDefault() {
        doReturn(true).when(mAdapterService).isLeAudioAllowed(mDevice1);

        // Auto connect to LE audio, HFP, A2DP
        processInitProfilePriorities_LeAudioHelper(true, true, true, false);
        verify(mLeAudioService).setConnectionPolicy(mDevice1, CONNECTION_POLICY_ALLOWED);
        verify(mA2dpService).setConnectionPolicy(mDevice1, CONNECTION_POLICY_ALLOWED);
        verify(mHeadsetService).setConnectionPolicy(mDevice1, CONNECTION_POLICY_ALLOWED);

        // Does not auto connect and allow HFP and A2DP to be connected
        processInitProfilePriorities_LeAudioHelper(true, false, true, false);
        verify(mAdapterService)
                .setProfileConnectionPolicy(
                        mDevice1, BluetoothProfile.LE_AUDIO, CONNECTION_POLICY_ALLOWED);
        verify(mAdapterService)
                .setProfileConnectionPolicy(
                        mDevice1, BluetoothProfile.A2DP, CONNECTION_POLICY_ALLOWED);
        verify(mAdapterService)
                .setProfileConnectionPolicy(
                        mDevice1, BluetoothProfile.HEADSET, CONNECTION_POLICY_ALLOWED);

        // Auto connect to LE audio but disallow HFP and A2DP
        processInitProfilePriorities_LeAudioHelper(false, true, true, false);
        verify(mLeAudioService, times(2)).setConnectionPolicy(mDevice1, CONNECTION_POLICY_ALLOWED);
        verify(mAdapterService)
                .setProfileConnectionPolicy(
                        mDevice1, BluetoothProfile.HEADSET, CONNECTION_POLICY_FORBIDDEN);
        verify(mAdapterService)
                .setProfileConnectionPolicy(
                        mDevice1, BluetoothProfile.A2DP, CONNECTION_POLICY_FORBIDDEN);

        // Does not auto connect and disallow HFP and A2DP to be connected
        processInitProfilePriorities_LeAudioHelper(false, false, true, false);
        verify(mAdapterService, times(2))
                .setProfileConnectionPolicy(
                        mDevice1, BluetoothProfile.LE_AUDIO, CONNECTION_POLICY_ALLOWED);
        verify(mAdapterService, times(2))
                .setProfileConnectionPolicy(
                        mDevice1, BluetoothProfile.HEADSET, CONNECTION_POLICY_FORBIDDEN);
        verify(mAdapterService, times(2))
                .setProfileConnectionPolicy(
                        mDevice1, BluetoothProfile.A2DP, CONNECTION_POLICY_FORBIDDEN);
    }

    private void processInitProfilePriorities_LeAudioHelper(
            boolean dualModeEnabled,
            boolean autoConnect,
            boolean leAudioEnabledByDefault,
            boolean bypassLeAudioAllowlist) {
        Utils.setDualModeAudioStateForTesting(dualModeEnabled);
        mPhonePolicy.mLeAudioEnabledByDefault = leAudioEnabledByDefault;
        mPhonePolicy.mAutoConnectProfilesSupported = autoConnect;
        mockSystemPropertyGet(BYPASS_LE_AUDIO_ALLOWLIST_PROPERTY, bypassLeAudioAllowlist);

        // Inject an event for UUIDs updated for a remote device with only HFP enabled
        ParcelUuid[] uuids = new ParcelUuid[3];
        uuids[0] = BluetoothUuid.HFP;
        uuids[1] = BluetoothUuid.A2DP_SINK;
        uuids[2] = BluetoothUuid.LE_AUDIO;
        mPhonePolicy.onUuidsDiscovered(mDevice1, uuids);
    }

    /* In this test we want to check following scenario
     * 1. First Le Audio set member bonds/connect and user switch LeAudio toggle
     * 2. Second device connects later, and LeAudio shall be enabled automatically
     */
    @Test
    public void testLateConnectOfLeAudioEnabled_DualModeBud() {
        Utils.setDualModeAudioStateForTesting(false);
        mPhonePolicy.mLeAudioEnabledByDefault = true;
        mPhonePolicy.mAutoConnectProfilesSupported = true;

        mockSystemPropertyGet(BYPASS_LE_AUDIO_ALLOWLIST_PROPERTY, true);

        int csipGroupId = 1;
        int groupSize = 2;

        List<BluetoothDevice> connectedDevices = new ArrayList<>();
        doReturn(groupSize).when(mCsipSetCoordinatorService).getDesiredGroupSize(csipGroupId);
        doReturn(csipGroupId).when(mCsipSetCoordinatorService).getGroupId(any(), any());
        doReturn(csipGroupId).when(mLeAudioService).getGroupId(any());
        doReturn(connectedDevices)
                .when(mCsipSetCoordinatorService)
                .getGroupDevicesOrdered(csipGroupId);

        // Connect first set member
        connectedDevices.add(mDevice1);

        /* Build list of test UUIDs */
        ParcelUuid[] uuids = new ParcelUuid[4];
        uuids[0] = BluetoothUuid.LE_AUDIO;
        uuids[1] = BluetoothUuid.COORDINATED_SET;
        uuids[2] = BluetoothUuid.A2DP_SINK;
        uuids[3] = BluetoothUuid.HFP;

        // Prepare common handlers
        doReturn(CONNECTION_POLICY_ALLOWED).when(mHeadsetService).getConnectionPolicy(any());
        doReturn(CONNECTION_POLICY_ALLOWED).when(mA2dpService).getConnectionPolicy(any());

        doAnswer(
                        invocation -> {
                            return setLeAudioAllowedConnectionPolicy(invocation.getArgument(0));
                        })
                .when(mLeAudioService)
                .setConnectionPolicy(any(BluetoothDevice.class), eq(CONNECTION_POLICY_ALLOWED));
        doAnswer(
                        invocation -> {
                            return getLeAudioConnectionPolicy(invocation.getArgument(0));
                        })
                .when(mLeAudioService)
                .getConnectionPolicy(any(BluetoothDevice.class));
        doReturn(connectedDevices).when(mLeAudioService).getGroupDevices(csipGroupId);

        doReturn(uuids).when(mAdapterService).getRemoteUuids(any(BluetoothDevice.class));
        doReturn(false)
                .when(mAdapterService)
                .isProfileSupported(any(BluetoothDevice.class), eq(BluetoothProfile.HEARING_AID));
        doReturn(true)
                .when(mAdapterService)
                .isProfileSupported(any(BluetoothDevice.class), eq(BluetoothProfile.LE_AUDIO));

        /* Always DualMode for test purpose */
        doReturn(BluetoothDevice.DEVICE_TYPE_DUAL)
                .when(mAdapterService)
                .getRemoteType(any(BluetoothDevice.class));

        doReturn(BluetoothDevice.BOND_BONDED).when(mAdapterService).getBondState(mDevice1);

        // Inject first devices
        mPhonePolicy.onUuidsDiscovered(mDevice1, uuids);
        mPhonePolicy.profileConnectionStateChanged(
                BluetoothProfile.CSIP_SET_COORDINATOR,
                mDevice1,
                STATE_DISCONNECTED,
                STATE_CONNECTED);
        mLooper.dispatchAll();

        // Verify connection policy is set properly
        verify(mLeAudioService).setConnectionPolicy(eq(mDevice1), eq(CONNECTION_POLICY_ALLOWED));

        mPhonePolicy.profileActiveDeviceChanged(BluetoothProfile.LE_AUDIO, mDevice1);
        mLooper.dispatchAll();

        verify(mA2dpService).setConnectionPolicy(eq(mDevice1), eq(CONNECTION_POLICY_FORBIDDEN));
        verify(mHeadsetService).setConnectionPolicy(eq(mDevice1), eq(CONNECTION_POLICY_FORBIDDEN));

        mockSystemPropertyGet(BYPASS_LE_AUDIO_ALLOWLIST_PROPERTY, false);

        doReturn(BluetoothDevice.BOND_BONDED).when(mAdapterService).getBondState(mDevice2);

        // Now connect second device and make sure
        // Connect first set member
        connectedDevices.add(mDevice2);

        // Inject second set member connection
        mPhonePolicy.onUuidsDiscovered(mDevice2, uuids);
        mPhonePolicy.profileConnectionStateChanged(
                BluetoothProfile.CSIP_SET_COORDINATOR,
                mDevice2,
                STATE_DISCONNECTED,
                STATE_CONNECTED);
        mLooper.dispatchAll();

        // Verify connection policy is set properly
        verify(mLeAudioService).setConnectionPolicy(eq(mDevice2), eq(CONNECTION_POLICY_ALLOWED));

        mPhonePolicy.profileActiveDeviceChanged(BluetoothProfile.LE_AUDIO, mDevice2);
        mLooper.dispatchAll();

        verify(mA2dpService).setConnectionPolicy(eq(mDevice2), eq(CONNECTION_POLICY_FORBIDDEN));
        verify(mHeadsetService).setConnectionPolicy(eq(mDevice2), eq(CONNECTION_POLICY_FORBIDDEN));
    }

    @Test
    public void testLateConnectOfLeAudioEnabled_AshaAndLeAudioBud() {
        Utils.setDualModeAudioStateForTesting(false);
        mPhonePolicy.mLeAudioEnabledByDefault = true;
        mPhonePolicy.mAutoConnectProfilesSupported = true;

        mockSystemPropertyGet(BYPASS_LE_AUDIO_ALLOWLIST_PROPERTY, true);

        int csipGroupId = 1;
        int groupSize = 2;

        List<BluetoothDevice> connectedDevices = new ArrayList<>();
        doReturn(groupSize).when(mCsipSetCoordinatorService).getDesiredGroupSize(csipGroupId);
        doReturn(csipGroupId).when(mCsipSetCoordinatorService).getGroupId(any(), any());
        doReturn(csipGroupId).when(mLeAudioService).getGroupId(any());
        doReturn(connectedDevices)
                .when(mCsipSetCoordinatorService)
                .getGroupDevicesOrdered(csipGroupId);

        // Connect first set member
        connectedDevices.add(mDevice1);

        /* Build list of test UUIDs */
        ParcelUuid[] uuids = new ParcelUuid[4];
        uuids[0] = BluetoothUuid.LE_AUDIO;
        uuids[1] = BluetoothUuid.COORDINATED_SET;
        uuids[2] = BluetoothUuid.HEARING_AID;
        uuids[3] = BluetoothUuid.HAS;

        // Prepare common handlers
        doReturn(CONNECTION_POLICY_ALLOWED).when(mHearingAidService).getConnectionPolicy(any());

        doAnswer(
                        invocation -> {
                            return setLeAudioAllowedConnectionPolicy(invocation.getArgument(0));
                        })
                .when(mLeAudioService)
                .setConnectionPolicy(any(BluetoothDevice.class), eq(CONNECTION_POLICY_ALLOWED));
        doAnswer(
                        invocation -> {
                            return getLeAudioConnectionPolicy(invocation.getArgument(0));
                        })
                .when(mLeAudioService)
                .getConnectionPolicy(any(BluetoothDevice.class));
        doReturn(connectedDevices).when(mLeAudioService).getGroupDevices(csipGroupId);

        doReturn(uuids).when(mAdapterService).getRemoteUuids(any(BluetoothDevice.class));
        doReturn(false)
                .when(mAdapterService)
                .isProfileSupported(any(BluetoothDevice.class), eq(BluetoothProfile.HEARING_AID));
        doReturn(true)
                .when(mAdapterService)
                .isProfileSupported(any(BluetoothDevice.class), eq(BluetoothProfile.LE_AUDIO));

        /* Always DualMode for test purpose */
        doReturn(BluetoothDevice.DEVICE_TYPE_LE)
                .when(mAdapterService)
                .getRemoteType(any(BluetoothDevice.class));
        doReturn(BluetoothDevice.BOND_BONDED).when(mAdapterService).getBondState(mDevice1);

        // Inject first devices
        mPhonePolicy.onUuidsDiscovered(mDevice1, uuids);
        mPhonePolicy.profileConnectionStateChanged(
                BluetoothProfile.CSIP_SET_COORDINATOR,
                mDevice1,
                STATE_DISCONNECTED,
                STATE_CONNECTED);
        mLooper.dispatchAll();

        // Verify connection policy is set properly
        verify(mLeAudioService).setConnectionPolicy(eq(mDevice1), eq(CONNECTION_POLICY_ALLOWED));

        mPhonePolicy.profileActiveDeviceChanged(BluetoothProfile.LE_AUDIO, mDevice1);
        mLooper.dispatchAll();

        verify(mHearingAidService)
                .setConnectionPolicy(eq(mDevice1), eq(CONNECTION_POLICY_FORBIDDEN));

        mockSystemPropertyGet(BYPASS_LE_AUDIO_ALLOWLIST_PROPERTY, false);

        doReturn(BluetoothDevice.BOND_BONDED).when(mAdapterService).getBondState(mDevice2);

        // Now connect second device and make sure
        // Connect first set member
        connectedDevices.add(mDevice2);

        // Inject second set member connection
        mPhonePolicy.onUuidsDiscovered(mDevice2, uuids);
        mPhonePolicy.profileConnectionStateChanged(
                BluetoothProfile.CSIP_SET_COORDINATOR,
                mDevice2,
                STATE_DISCONNECTED,
                STATE_CONNECTED);
        mLooper.dispatchAll();

        // Verify connection policy is set properly
        verify(mLeAudioService).setConnectionPolicy(eq(mDevice2), eq(CONNECTION_POLICY_ALLOWED));

        mPhonePolicy.profileActiveDeviceChanged(BluetoothProfile.LE_AUDIO, mDevice2);
        mLooper.dispatchAll();

        verify(mHearingAidService)
                .setConnectionPolicy(eq(mDevice2), eq(CONNECTION_POLICY_FORBIDDEN));
    }

    /**
     * Test that when the adapter is turned ON then we call autoconnect on devices that have HFP and
     * A2DP enabled. NOTE that the assumption is that we have already done the pairing previously
     * and hence the priorities for the device is already set to AUTO_CONNECT over HFP and A2DP (as
     * part of post pairing process).
     */
    @Test
    public void testAdapterOnAutoConnect() {
        // Return desired values from the mocked object(s)
        doReturn(false).when(mAdapterService).isQuietModeEnabled();

        // Return a list of connection order
        doReturn(mDevice1).when(mStorage).getMostRecentlyActiveA2dpDevice();
        doReturn(BluetoothDevice.BOND_BONDED).when(mAdapterService).getBondState(mDevice1);

        doReturn(CONNECTION_POLICY_ALLOWED).when(mHeadsetService).getConnectionPolicy(any());
        doReturn(CONNECTION_POLICY_ALLOWED).when(mA2dpService).getConnectionPolicy(any());

        // Inject an event that the adapter is turned on.
        mPhonePolicy.onBluetoothStateChange(State.OFF, State.ON);

        // Check that we got a request to connect over HFP and A2DP
        verify(mA2dpService).connect(eq(mDevice1));
        verify(mHeadsetService).connect(eq(mDevice1));
    }

    /** Test that when an active device is disconnected, we will not auto connect it */
    @Test
    public void testDisconnectNoAutoConnect() {
        InOrder order = inOrder(mStorage);
        // Return desired values from the mocked object(s)
        doReturn(false).when(mAdapterService).isQuietModeEnabled();

        // Return a list of connection order
        List<BluetoothDevice> connectionOrder = new ArrayList<>();
        connectionOrder.add(mDevice1);
        connectionOrder.add(mDevice2);
        connectionOrder.add(mDevice3);
        connectionOrder.add(mDevice4);

        doReturn(mDevice1).when(mStorage).getMostRecentlyActiveA2dpDevice();

        // Make all devices auto connect
        doReturn(CONNECTION_POLICY_ALLOWED).when(mHeadsetService).getConnectionPolicy(any());
        doReturn(CONNECTION_POLICY_FORBIDDEN)
                .when(mA2dpService)
                .getConnectionPolicy(eq(connectionOrder.get(3)));

        // Make one of the device active
        mPhonePolicy.profileActiveDeviceChanged(BluetoothProfile.A2DP, connectionOrder.get(0));
        mLooper.dispatchAll();

        // Only calls setConnection on device connectionOrder.get(0) with STATE_CONNECTED
        order.verify(mStorage).onDeviceConnected(mDevice1, BluetoothProfile.A2DP);
        order.verify(mStorage, never()).onDeviceConnected(any(), anyInt());

        // Make another device active
        doReturn(STATE_CONNECTED).when(mHeadsetService).getConnectionState(connectionOrder.get(1));
        mPhonePolicy.profileActiveDeviceChanged(BluetoothProfile.A2DP, connectionOrder.get(1));
        mLooper.dispatchAll();

        // Only calls setConnection on device connectionOrder.get(1) with STATE_CONNECTED
        order.verify(mStorage).onDeviceConnected(mDevice2, BluetoothProfile.A2DP);
        order.verify(mStorage, never()).onDeviceConnected(any(), anyInt());

        // Disconnect a2dp for the device from previous STATE_CONNECTED
        doReturn(STATE_DISCONNECTED)
                .when(mHeadsetService)
                .getConnectionState(connectionOrder.get(1));
        mPhonePolicy.profileConnectionStateChanged(
                BluetoothProfile.A2DP, connectionOrder.get(1), STATE_CONNECTED, STATE_DISCONNECTED);
        mLooper.dispatchAll();

        // Verify that we do not call setConnection, nor setDisconnection on disconnect
        // from previous STATE_CONNECTED
        order.verify(mStorage, never()).onDeviceConnected(any(), anyInt());
        order.verify(mStorage, never()).onDeviceDisconnected(any(), anyInt());

        // Disconnect a2dp for the device from previous STATE_DISCONNECTING
        mPhonePolicy.profileConnectionStateChanged(
                BluetoothProfile.A2DP,
                connectionOrder.get(1),
                STATE_DISCONNECTING,
                STATE_DISCONNECTED);
        mLooper.dispatchAll();

        // Verify that we do not call setConnection, but instead setDisconnection on disconnect
        order.verify(mStorage, never()).onDeviceConnected(any(), anyInt());
        order.verify(mStorage).onDeviceDisconnected(mDevice2, BluetoothProfile.A2DP);

        // Make the current active device fail to connect
        doReturn(STATE_DISCONNECTED).when(mA2dpService).getConnectionState(connectionOrder.get(1));
        updateProfileConnectionStateHelper(
                connectionOrder.get(1),
                BluetoothProfile.HEADSET,
                STATE_CONNECTING,
                STATE_DISCONNECTED);
        mLooper.dispatchAll();

        // Verify we don't call deleteConnection as that only happens when we disconnect a2dp
        order.verify(mStorage, never()).onDeviceConnected(any(), anyInt());
        order.verify(mStorage).onDeviceDisconnected(any(), eq(BluetoothProfile.HEADSET));

        // Verify we didn't have any unexpected calls to setConnection or deleteConnection
        verifyNoMoreInteractions(mStorage);
    }

    /**
     * Test that we will try to re-connect to a profile on a device if other profile(s) are
     * connected. This is to add robustness to the connection mechanism
     */
    @Test
    public void testReconnectOnPartialConnect() {
        doReturn(Set.of(mDevice1)).when(mAdapterService).getBondedDevices();

        doReturn(CONNECTION_POLICY_ALLOWED).when(mHeadsetService).getConnectionPolicy(any());
        doReturn(CONNECTION_POLICY_ALLOWED).when(mA2dpService).getConnectionPolicy(any());

        // We want to trigger (in CONNECT_OTHER_PROFILES_TIMEOUT) a call to connect A2DP
        // To enable that we need to make sure that HeadsetService returns the device as list of
        // connected devices
        ArrayList<BluetoothDevice> hsConnectedDevices = new ArrayList<>();
        hsConnectedDevices.add(mDevice1);
        doReturn(hsConnectedDevices).when(mHeadsetService).getConnectedDevices();
        // Also the A2DP should say that its not connected for same device
        doReturn(STATE_DISCONNECTED).when(mA2dpService).getConnectionState(mDevice1);

        // ACL is connected, lets simulate this.
        doReturn(BluetoothDevice.CONNECTION_STATE_ENCRYPTED_BREDR)
                .when(mAdapterService)
                .getConnectionState(mDevice1);

        // We send a connection successful for one profile since the re-connect *only* works if we
        // have already connected successfully over one of the profiles
        updateProfileConnectionStateHelper(
                mDevice1, BluetoothProfile.HEADSET, STATE_DISCONNECTED, STATE_CONNECTED);

        // Check that we get a call to A2DP connect
        verify(mA2dpService).connect(eq(mDevice1));
    }

    /**
     * Test that connectOtherProfile will not trigger any actions when ACL is disconnected. This is
     * to add robustness to the connection mechanism
     */
    @Test
    public void testConnectOtherProfileWhileDeviceIsDisconnected() {
        doReturn(Set.of(mDevice1)).when(mAdapterService).getBondedDevices();

        doReturn(CONNECTION_POLICY_ALLOWED).when(mHeadsetService).getConnectionPolicy(any());
        doReturn(CONNECTION_POLICY_ALLOWED).when(mA2dpService).getConnectionPolicy(any());

        // We want to trigger (in CONNECT_OTHER_PROFILES_TIMEOUT) a call to connect A2DP
        // To enable that we need to make sure that HeadsetService returns the device as list of
        // connected devices
        doReturn(List.of(mDevice1)).when(mHeadsetService).getConnectedDevices();

        // ACL is disconnected just after HEADSET profile got connected and connectOtherProfile
        // was scheduled. Lets simulate this.
        doReturn(BluetoothDevice.CONNECTION_STATE_DISCONNECTED)
                .when(mAdapterService)
                .getConnectionState(mDevice1);

        // We send a connection successful for one profile since the re-connect *only* works if we
        // have already connected successfully over one of the profiles
        updateProfileConnectionStateHelper(
                mDevice1, BluetoothProfile.HEADSET, STATE_DISCONNECTED, STATE_CONNECTED);

        // Check that there will be no A2DP connect
        verify(mA2dpService, never()).connect(eq(mDevice1));
    }

    /**
     * Test that we will try to re-connect to a profile on a device next time if a previous attempt
     * failed partially. This will make sure the connection mechanism still works at next try while
     * the previous attempt is some profiles connected on a device but some not.
     */
    @Test
    public void testReconnectOnPartialConnect_PreviousPartialFail() {
        InOrder mInOrder = inOrder(mA2dpService);
        // ACL is connected, lets simulate this.
        doReturn(STATE_CONNECTED).when(mAdapterService).getConnectionState(mDevice1);
        doReturn(mDevice1).when(mStorage).getMostRecentlyActiveA2dpDevice();
        doReturn(CONNECTION_POLICY_ALLOWED).when(mHeadsetService).getConnectionPolicy(any());
        doReturn(CONNECTION_POLICY_ALLOWED).when(mA2dpService).getConnectionPolicy(any());

        // We want to trigger (in CONNECT_OTHER_PROFILES_TIMEOUT) a call to connect A2DP
        // To enable that we need to make sure that HeadsetService returns the device among a list
        // of connected devices
        doReturn(List.of(mDevice1)).when(mHeadsetService).getConnectedDevices();

        // We send a connection success event for one profile since the re-connect *only* works if
        // we have already connected successfully over one of the profiles
        updateProfileConnectionStateHelper(
                mDevice1, BluetoothProfile.HEADSET, STATE_DISCONNECTED, STATE_CONNECTED);

        // Check that we get a call to A2DP reconnect
        mInOrder.verify(mA2dpService).connect(mDevice1);

        // We send a connection failure event for the attempted profile, and keep the connected
        // profile connected.
        updateProfileConnectionStateHelper(
                mDevice1, BluetoothProfile.A2DP, STATE_CONNECTING, STATE_DISCONNECTED);

        // Verify no one changes the priority of the failed profile
        mInOrder.verify(mA2dpService, never()).setConnectionPolicy(eq(mDevice1), anyInt());

        // Send a connection success event for one profile again without disconnecting all profiles
        updateProfileConnectionStateHelper(
                mDevice1, BluetoothProfile.HEADSET, STATE_DISCONNECTED, STATE_CONNECTED);

        // Check that we won't get a call to A2DP reconnect again before all profiles disconnected
        mInOrder.verify(mA2dpService, never()).connect(mDevice1);

        // Send a disconnection event for all connected profiles
        doReturn(Collections.emptyList()).when(mHeadsetService).getConnectedDevices();
        updateProfileConnectionStateHelper(
                mDevice1, BluetoothProfile.HEADSET, STATE_CONNECTED, STATE_DISCONNECTED);

        // Send a connection success event for one profile again to trigger re-connect
        doReturn(List.of(mDevice1)).when(mHeadsetService).getConnectedDevices();
        updateProfileConnectionStateHelper(
                mDevice1, BluetoothProfile.HEADSET, STATE_DISCONNECTED, STATE_CONNECTED);

        // Check that we get a call to A2DP connect again
        mInOrder.verify(mA2dpService).connect(mDevice1);
    }

    /**
     * Test that when the adapter is turned ON then call auto connect on devices that only has HFP
     * enabled. NOTE that the assumption is that we have already done the pairing previously and
     * hence the priorities for the device is already set to AUTO_CONNECT over HFP (as part of post
     * pairing process).
     */
    @Test
    public void testAutoConnectHfpOnly() {
        mStorage.onDeviceConnected(mDevice1, BluetoothProfile.HEADSET);
        doReturn(CONNECTION_POLICY_ALLOWED).when(mHeadsetService).getConnectionPolicy(eq(mDevice1));

        mPhonePolicy.autoConnect();

        // Check that we got a request to connect over HFP for each device
        verify(mHeadsetService).connect(eq(mDevice1));
    }

    @Test
    public void autoConnect_whenMultiHfp_startConnection() {
        List<BluetoothDevice> devices = List.of(mDevice1, mDevice2, mDevice3);
        for (BluetoothDevice device : devices) {
            mStorage.onDeviceConnected(device, BluetoothProfile.HEADSET);
            doReturn(CONNECTION_POLICY_ALLOWED)
                    .when(mHeadsetService)
                    .getConnectionPolicy(eq(device));
        }

        mPhonePolicy.autoConnect();

        // Check that we got a request to connect over HFP for each device
        for (BluetoothDevice device : devices) {
            verify(mHeadsetService).connect(eq(device));
        }
    }

    @Test
    public void autoConnect_whenMultiHfpAndDisconnection_startConnection() {
        BluetoothDevice deviceToDisconnect = mDevice1;
        mStorage.onDeviceConnected(deviceToDisconnect, BluetoothProfile.HEADSET);
        doReturn(CONNECTION_POLICY_ALLOWED)
                .when(mHeadsetService)
                .getConnectionPolicy(eq(deviceToDisconnect));

        List<BluetoothDevice> devices = List.of(mDevice2, mDevice3, mDevice4);
        for (BluetoothDevice device : devices) {
            mStorage.onDeviceConnected(device, BluetoothProfile.HEADSET);
            doReturn(CONNECTION_POLICY_ALLOWED)
                    .when(mHeadsetService)
                    .getConnectionPolicy(eq(device));
        }

        mStorage.onDeviceDisconnected(deviceToDisconnect, BluetoothProfile.HEADSET);

        mPhonePolicy.autoConnect();

        // Check that we got a request to connect over HFP for each device
        for (BluetoothDevice device : devices) {
            verify(mHeadsetService).connect(eq(device));
        }
        // Except for the device that was manually disconnected
        verify(mHeadsetService, never()).connect(eq(deviceToDisconnect));
    }

    /**
     * Test that a second device will auto-connect if there is already one connected device.
     *
     * <p>Even though we currently only set one device to be auto connect. The consumer of the auto
     * connect property works independently so that we will connect to all devices that are in auto
     * connect mode.
     */
    @Test
    public void testAutoConnectMultipleDevices() {
        final int kMaxTestDevices = 3;
        BluetoothDevice[] testDevices = new BluetoothDevice[kMaxTestDevices];
        ArrayList<BluetoothDevice> hsConnectedDevices = new ArrayList<>();
        ArrayList<BluetoothDevice> a2dpConnectedDevices = new ArrayList<>();

        for (int i = 0; i < kMaxTestDevices; i++) {
            BluetoothDevice testDevice = getTestDevice(i);
            testDevices[i] = testDevice;

            // ACL is connected, lets simulate this.
            doReturn(STATE_CONNECTED).when(mAdapterService).getConnectionState(testDevice);

            // Return PRIORITY_AUTO_CONNECT over HFP and A2DP. This would imply that the profiles
            // are auto-connectable.
            doReturn(CONNECTION_POLICY_ALLOWED)
                    .when(mHeadsetService)
                    .getConnectionPolicy(testDevice);
            doReturn(CONNECTION_POLICY_ALLOWED).when(mA2dpService).getConnectionPolicy(testDevice);
            // We want to trigger (in CONNECT_OTHER_PROFILES_TIMEOUT) a call to connect A2DP
            // To enable that we need to make sure that HeadsetService returns the device as list
            // of connected devices.
            hsConnectedDevices.add(testDevice);
            // Connect A2DP for all devices except the last one
            if (i < (kMaxTestDevices - 2)) {
                a2dpConnectedDevices.add(testDevice);
            }
        }
        BluetoothDevice a2dpNotConnectedDevice1 = hsConnectedDevices.get(kMaxTestDevices - 1);
        BluetoothDevice a2dpNotConnectedDevice2 = hsConnectedDevices.get(kMaxTestDevices - 2);

        doReturn(Set.of(testDevices)).when(mAdapterService).getBondedDevices();
        doReturn(hsConnectedDevices).when(mHeadsetService).getConnectedDevices();
        doReturn(a2dpConnectedDevices).when(mA2dpService).getConnectedDevices();
        // Two of the A2DP devices are not connected
        doReturn(STATE_DISCONNECTED).when(mA2dpService).getConnectionState(a2dpNotConnectedDevice1);
        doReturn(STATE_DISCONNECTED).when(mA2dpService).getConnectionState(a2dpNotConnectedDevice2);

        // We send a connection successful for one profile since the re-connect *only* works if we
        // have already connected successfully over one of the profiles
        updateProfileConnectionStateHelper(
                a2dpNotConnectedDevice1,
                BluetoothProfile.HEADSET,
                STATE_DISCONNECTED,
                STATE_CONNECTED);

        // We send a connection successful for one profile since the re-connect *only* works if we
        // have already connected successfully over one of the profiles
        updateProfileConnectionStateHelper(
                a2dpNotConnectedDevice2,
                BluetoothProfile.HEADSET,
                STATE_DISCONNECTED,
                STATE_CONNECTED);

        // Check that we get a call to A2DP connect
        verify(mA2dpService).connect(eq(a2dpNotConnectedDevice1));
        verify(mA2dpService).connect(eq(a2dpNotConnectedDevice2));
    }

    /**
     * Test that the connection policy of all devices are set as appropriate if there is one
     * connected device. - The HFP and A2DP connect priority for connected devices is set to
     * BluetoothProfile.PRIORITY_AUTO_CONNECT - The HFP and A2DP connect priority for bonded devices
     * is set to CONNECTION_POLICY_ALLOWED
     */
    @Test
    public void testSetConnectionPolicyMultipleDevices() {
        // testDevices[0] - connected for both HFP and A2DP
        // testDevices[1] - connected only for HFP - will auto-connect for A2DP
        // testDevices[2] - connected only for A2DP - will auto-connect for HFP
        // testDevices[3] - not connected
        final int kMaxTestDevices = 4;
        BluetoothDevice[] testDevices = new BluetoothDevice[kMaxTestDevices];
        ArrayList<BluetoothDevice> hsConnectedDevices = new ArrayList<>();
        ArrayList<BluetoothDevice> a2dpConnectedDevices = new ArrayList<>();

        for (int i = 0; i < kMaxTestDevices; i++) {
            BluetoothDevice testDevice = getTestDevice(i);
            testDevices[i] = testDevice;

            // ACL is connected, lets simulate this.
            doReturn(STATE_CONNECTED).when(mAdapterService).getConnectionState(testDevices[i]);

            // Connect HFP and A2DP for each device as appropriate.
            // Return PRIORITY_AUTO_CONNECT only for testDevices[0]
            if (i == 0) {
                hsConnectedDevices.add(testDevice);
                a2dpConnectedDevices.add(testDevice);
                doReturn(CONNECTION_POLICY_ALLOWED)
                        .when(mHeadsetService)
                        .getConnectionPolicy(testDevice);
                doReturn(CONNECTION_POLICY_ALLOWED)
                        .when(mA2dpService)
                        .getConnectionPolicy(testDevice);
            }
            if (i == 1) {
                hsConnectedDevices.add(testDevice);
                doReturn(CONNECTION_POLICY_ALLOWED)
                        .when(mHeadsetService)
                        .getConnectionPolicy(testDevice);
                doReturn(CONNECTION_POLICY_ALLOWED)
                        .when(mA2dpService)
                        .getConnectionPolicy(testDevice);
            }
            if (i == 2) {
                a2dpConnectedDevices.add(testDevice);
                doReturn(CONNECTION_POLICY_ALLOWED)
                        .when(mHeadsetService)
                        .getConnectionPolicy(testDevice);
                doReturn(CONNECTION_POLICY_ALLOWED)
                        .when(mA2dpService)
                        .getConnectionPolicy(testDevice);
            }
            if (i == 3) {
                // Device not connected
                doReturn(CONNECTION_POLICY_ALLOWED)
                        .when(mHeadsetService)
                        .getConnectionPolicy(testDevice);
                doReturn(CONNECTION_POLICY_ALLOWED)
                        .when(mA2dpService)
                        .getConnectionPolicy(testDevice);
            }
        }
        doReturn(Set.of(testDevices)).when(mAdapterService).getBondedDevices();
        doReturn(hsConnectedDevices).when(mHeadsetService).getConnectedDevices();
        doReturn(a2dpConnectedDevices).when(mA2dpService).getConnectedDevices();
        // Some of the devices are not connected
        // testDevices[0] - connected for both HFP and A2DP
        doReturn(STATE_CONNECTED).when(mHeadsetService).getConnectionState(testDevices[0]);
        doReturn(STATE_CONNECTED).when(mA2dpService).getConnectionState(testDevices[0]);
        // testDevices[1] - connected only for HFP - will auto-connect for A2DP
        doReturn(STATE_CONNECTED).when(mHeadsetService).getConnectionState(testDevices[1]);
        doReturn(STATE_DISCONNECTED).when(mA2dpService).getConnectionState(testDevices[1]);
        // testDevices[2] - connected only for A2DP - will auto-connect for HFP
        doReturn(STATE_DISCONNECTED).when(mHeadsetService).getConnectionState(testDevices[2]);
        doReturn(STATE_CONNECTED).when(mA2dpService).getConnectionState(testDevices[2]);
        // testDevices[3] - not connected
        doReturn(STATE_DISCONNECTED).when(mHeadsetService).getConnectionState(testDevices[3]);
        doReturn(STATE_DISCONNECTED).when(mA2dpService).getConnectionState(testDevices[3]);

        // Generate connection state changed for HFP for testDevices[1] and trigger
        // auto-connect for A2DP.
        updateProfileConnectionStateHelper(
                testDevices[1], BluetoothProfile.HEADSET, STATE_DISCONNECTED, STATE_CONNECTED);

        // Check that we get a call to A2DP connect
        verify(mA2dpService).connect(eq(testDevices[1]));

        // testDevices[1] auto-connect completed for A2DP
        a2dpConnectedDevices.add(testDevices[1]);
        doReturn(a2dpConnectedDevices).when(mA2dpService).getConnectedDevices();
        doReturn(STATE_CONNECTED).when(mA2dpService).getConnectionState(testDevices[1]);

        // Check the connect priorities for all devices
        // - testDevices[0] - connected for HFP and A2DP: setConnectionPolicy() should not be called
        // - testDevices[1] - connection state changed for HFP should no longer trigger auto
        //                    connect priority change since it is now triggered by A2DP active
        //                    device change intent
        // - testDevices[2] - connected for A2DP: setConnectionPolicy() should not be called
        // - testDevices[3] - not connected for HFP nor A2DP: setConnectionPolicy() should not be
        //                    called
        verify(mHeadsetService, never()).setConnectionPolicy(eq(testDevices[0]), anyInt());
        verify(mA2dpService, never()).setConnectionPolicy(eq(testDevices[0]), anyInt());
        verify(mHeadsetService, never())
                .setConnectionPolicy(
                        eq(testDevices[1]), eq(BluetoothProfile.PRIORITY_AUTO_CONNECT));
        verify(mA2dpService, never()).setConnectionPolicy(eq(testDevices[1]), anyInt());
        verify(mHeadsetService, never()).setConnectionPolicy(eq(testDevices[2]), anyInt());
        verify(mA2dpService, never()).setConnectionPolicy(eq(testDevices[2]), anyInt());
        verify(mHeadsetService, never()).setConnectionPolicy(eq(testDevices[3]), anyInt());
        verify(mA2dpService, never()).setConnectionPolicy(eq(testDevices[3]), anyInt());
        clearInvocations(mHeadsetService, mA2dpService);

        // Generate connection state changed for A2DP for testDevices[2] and trigger
        // auto-connect for HFP.
        updateProfileConnectionStateHelper(
                testDevices[2], BluetoothProfile.A2DP, STATE_DISCONNECTED, STATE_CONNECTED);

        // Check that we get a call to HFP connect
        verify(mHeadsetService).connect(eq(testDevices[2]));

        // testDevices[2] auto-connect completed for HFP
        hsConnectedDevices.add(testDevices[2]);
        doReturn(hsConnectedDevices).when(mHeadsetService).getConnectedDevices();
        doReturn(STATE_CONNECTED).when(mHeadsetService).getConnectionState(testDevices[2]);

        // Check the connect priorities for all devices
        // - testDevices[0] - connected for HFP and A2DP: setConnectionPolicy() should not be called
        // - testDevices[1] - connected for HFP and A2DP: setConnectionPolicy() should not be called
        // - testDevices[2] - connection state changed for A2DP should no longer trigger auto
        //                    connect priority change since it is now triggered by A2DP
        //                    active device change intent
        // - testDevices[3] - not connected for HFP nor A2DP: setConnectionPolicy() should not be
        //                    called
        verify(mHeadsetService, never()).setConnectionPolicy(eq(testDevices[0]), anyInt());
        verify(mA2dpService, never()).setConnectionPolicy(eq(testDevices[0]), anyInt());
        verify(mHeadsetService, never()).setConnectionPolicy(eq(testDevices[1]), anyInt());
        verify(mA2dpService, never()).setConnectionPolicy(eq(testDevices[1]), anyInt());
        verify(mHeadsetService, never()).setConnectionPolicy(eq(testDevices[2]), anyInt());
        verify(mA2dpService, never())
                .setConnectionPolicy(
                        eq(testDevices[2]), eq(BluetoothProfile.PRIORITY_AUTO_CONNECT));
        verify(mHeadsetService, never()).setConnectionPolicy(eq(testDevices[3]), anyInt());
        verify(mA2dpService, never()).setConnectionPolicy(eq(testDevices[3]), anyInt());
        clearInvocations(mHeadsetService, mA2dpService);
    }

    /** Test that we will not try to reconnect on a profile if all the connections failed */
    @Test
    public void testNoReconnectOnNoConnect() {
        doReturn(Set.of(mDevice1)).when(mAdapterService).getBondedDevices();

        doReturn(CONNECTION_POLICY_ALLOWED).when(mHeadsetService).getConnectionPolicy(any());
        doReturn(CONNECTION_POLICY_ALLOWED).when(mA2dpService).getConnectionPolicy(any());

        mLooper.dispatchAll();

        // Check that we don't get any calls to reconnect
        verify(mA2dpService, never()).connect(any());
        verify(mHeadsetService, never()).connect(any());
    }

    /**
     * Test that we will not try to reconnect on a profile if all the connections failed with
     * multiple devices
     */
    @Test
    public void testNoReconnectOnNoConnect_MultiDevice() {
        doReturn(Set.of(mDevice1, mDevice2)).when(mAdapterService).getBondedDevices();

        doReturn(CONNECTION_POLICY_ALLOWED).when(mHeadsetService).getConnectionPolicy(any());
        doReturn(CONNECTION_POLICY_ALLOWED).when(mA2dpService).getConnectionPolicy(any());

        doReturn(List.of(mDevice2)).when(mHeadsetService).getConnectedDevices();
        doReturn(List.of(mDevice2)).when(mA2dpService).getConnectedDevices();

        doReturn(STATE_CONNECTED).when(mA2dpService).getConnectionState(eq(mDevice2));
        doReturn(STATE_CONNECTED).when(mHeadsetService).getConnectionState(eq(mDevice2));

        doReturn(STATE_CONNECTED).when(mAdapterService).getConnectionState(any());

        // We send a connection successful for one profile since the re-connect *only* works if we
        // have already connected successfully over one of the profiles
        mLooper.dispatchAll();

        // Check that we don't get any calls to reconnect
        verify(mA2dpService, never()).connect(eq(mDevice1));
        verify(mHeadsetService, never()).connect(eq(mDevice1));
    }

    /**
     * Test that we will try to connect to other profiles of a device if it is partially connected
     */
    @Test
    public void testReconnectOnPartialConnect_MultiDevice() {
        doReturn(Set.of(mDevice1, mDevice2)).when(mAdapterService).getBondedDevices();

        doReturn(List.of(mDevice2)).when(mHeadsetService).getConnectedDevices();

        doReturn(STATE_CONNECTED).when(mAdapterService).getConnectionState(eq(mDevice2));
        doReturn(CONNECTION_POLICY_ALLOWED).when(mA2dpService).getConnectionPolicy(any());

        // We send a connection successful for one profile since the re-connect *only* works if we
        // have already connected successfully over one of the profiles
        updateProfileConnectionStateHelper(
                mDevice2, BluetoothProfile.HEADSET, STATE_DISCONNECTED, STATE_CONNECTED);

        // Check that we do get A2DP call to reconnect, because HEADSET just got connected
        verify(mA2dpService).connect(eq(mDevice2));
    }

    @Test
    public void discoversUuids_whenNullUuids_policyIsNotChanged() {
        mPhonePolicy.onUuidsDiscovered(mDevice1, null);

        verify(mHeadsetService, never()).setConnectionPolicy(any(), anyInt());
        verify(mA2dpService, never()).setConnectionPolicy(any(), anyInt());
    }

    private void updateProfileConnectionStateHelper(
            BluetoothDevice device, int profileId, int prevState, int nextState) {
        switch (profileId) {
            case BluetoothProfile.A2DP ->
                    doReturn(nextState).when(mA2dpService).getConnectionState(device);
            case BluetoothProfile.HEADSET ->
                    doReturn(nextState).when(mHeadsetService).getConnectionState(device);
            default -> {} // Nothing to do
        }
        mPhonePolicy.profileConnectionStateChanged(profileId, device, prevState, nextState);
        mLooper.dispatchAll();
        mLooper.moveTimeForward(PhonePolicy.CONNECT_OTHER_PROFILES_TIMEOUT.toMillis());
        mLooper.dispatchAll();
    }

    private void setupCsipGroup(BluetoothDevice leader, List<BluetoothDevice> members) {
        int csipGroupId = 1;
        // Mock getGroupId to return a test group ID for the leader device
        doReturn(csipGroupId)
                .when(mCsipSetCoordinatorService)
                .getGroupId(eq(leader), eq(BluetoothUuid.CAP));

        // Mock getGroupDevicesOrdered to return the member list for the test group ID
        doReturn(members).when(mCsipSetCoordinatorService).getGroupDevicesOrdered(eq(csipGroupId));
    }

    @Test
    public void handleConnectionPolicyAfterCsipConnect_leOnlyMemberRemoved_policyForbidden() {
        // Test Case: LE Audio only member removed from CSIP set

        // 2. Setup CSIP group with device1 and device2
        setupCsipGroup(mDevice1, List.of(mDevice1, mDevice2));

        // 3. Initial bond states: Both bonded
        doReturn(BluetoothDevice.BOND_BONDED).when(mAdapterService).getBondState(mDevice1);
        doReturn(BluetoothDevice.BOND_BONDED).when(mAdapterService).getBondState(mDevice2);

        // 4. Simulate device2 removal (unbonded)
        doReturn(BluetoothDevice.BOND_NONE).when(mAdapterService).getBondState(mDevice2);

        // 5. Trigger the method under test, simulating a reconnect of device1
        mPhonePolicy.handleConnectionPolicyAfterCsipConnect(mDevice1);

        // 6. Verify device2's connection policies and alias
        verify(mLeAudioService, never()).setConnectionPolicy(eq(mDevice2), anyInt());
    }

    @Test
    public void handleConnectionPolicyAfterCsipConnect_dualModeMemberRemoved_noPolicyChange() {
        // Test Case: Dual mode member removed from CSIP set

        // 2. Setup CSIP group
        setupCsipGroup(mDevice1, List.of(mDevice1, mDevice2));

        // 3. Initial bond states: Both bonded
        doReturn(BluetoothDevice.BOND_BONDED).when(mAdapterService).getBondState(mDevice1);
        doReturn(BluetoothDevice.BOND_BONDED).when(mAdapterService).getBondState(mDevice2);

        // 4. Simulate device2 removal (unbonded)
        doReturn(BluetoothDevice.BOND_NONE).when(mAdapterService).getBondState(mDevice2);

        // 5. Trigger the method under test
        mPhonePolicy.handleConnectionPolicyAfterCsipConnect(mDevice1);

        // 6. Verify device2's policies - NO changes expected
        verify(mA2dpService, never()).setConnectionPolicy(eq(mDevice2), anyInt());
        verify(mHeadsetService, never()).setConnectionPolicy(eq(mDevice2), anyInt());
        verify(mLeAudioService, never()).setConnectionPolicy(eq(mDevice2), anyInt());
    }
}
