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

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothCsipSetCoordinator;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.os.ParcelUuid;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.room.Room;
import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestLooper;
import com.android.bluetooth.TestUtils;
import com.android.bluetooth.Utils;
import com.android.bluetooth.a2dp.A2dpService;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.bluetooth.btservice.storage.MetadataDatabase;
import com.android.bluetooth.csip.CsipSetCoordinatorService;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.hap.HapClientService;
import com.android.bluetooth.hearingaid.HearingAidService;
import com.android.bluetooth.hfp.HeadsetService;
import com.android.bluetooth.le_audio.LeAudioService;
import com.android.bluetooth.util.SystemProperties;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class PhonePolicyTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Mock private AdapterService mAdapterService;
    @Mock private ServiceFactory mServiceFactory;
    @Mock private HeadsetService mHeadsetService;
    @Mock private A2dpService mA2dpService;
    @Mock private LeAudioService mLeAudioService;
    @Mock private DatabaseManager mDatabaseManager;
    @Mock private CsipSetCoordinatorService mCsipSetCoordinatorService;
    @Mock private HearingAidService mHearingAidService;
    @Mock private HapClientService mHapClientService;
    @Mock private SystemProperties.MockableSystemProperties mProperties;

    private static final int MAX_CONNECTED_AUDIO_DEVICES = 5;

    private final BluetoothDevice mDevice = getTestDevice(0);
    private final BluetoothDevice mDevice2 = getTestDevice(1);
    private PhonePolicy mPhonePolicy;
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

        doReturn(BluetoothAdapter.STATE_ON).when(mAdapterService).getState();
        doReturn(MAX_CONNECTED_AUDIO_DEVICES).when(mAdapterService).getMaxConnectedAudioDevices();
        doReturn(mDatabaseManager).when(mAdapterService).getDatabase();
        // Setup the mocked factory to return mocked services
        doReturn(mHeadsetService).when(mServiceFactory).getHeadsetService();
        doReturn(mA2dpService).when(mServiceFactory).getA2dpService();
        doReturn(mLeAudioService).when(mServiceFactory).getLeAudioService();
        doReturn(mCsipSetCoordinatorService).when(mServiceFactory).getCsipSetCoordinatorService();
        doReturn(mHearingAidService).when(mServiceFactory).getHearingAidService();
        doReturn(mHapClientService).when(mServiceFactory).getHapClientService();

        // Most common default
        doReturn(CONNECTION_POLICY_UNKNOWN).when(mHeadsetService).getConnectionPolicy(any());
        doReturn(CONNECTION_POLICY_UNKNOWN).when(mA2dpService).getConnectionPolicy(any());
        doReturn(CONNECTION_POLICY_UNKNOWN).when(mLeAudioService).getConnectionPolicy(any());
        doReturn(STATE_DISCONNECTED).when(mA2dpService).getConnectionState(any());
        doReturn(STATE_DISCONNECTED).when(mHeadsetService).getConnectionState(any());
        doReturn(Collections.emptyList()).when(mA2dpService).getConnectedDevices();
        doReturn(Collections.emptyList()).when(mHeadsetService).getConnectedDevices();

        SystemProperties.mProperties = mProperties;

        mPhonePolicy = new PhonePolicy(mAdapterService, mLooper.getLooper(), mServiceFactory);
        mOriginalDualModeState = Utils.isDualModeAudioEnabled();
    }

    @After
    public void tearDown() throws Exception {
        SystemProperties.mProperties = null;
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
        mPhonePolicy.onUuidsDiscovered(mDevice, uuids);

        verify(mDatabaseManager)
                .setProfileConnectionPolicy(
                        mDevice, BluetoothProfile.HEADSET, CONNECTION_POLICY_ALLOWED);
        verify(mDatabaseManager)
                .setProfileConnectionPolicy(
                        mDevice, BluetoothProfile.A2DP, CONNECTION_POLICY_ALLOWED);
    }

    private void processInitProfilePriorities_LeAudioOnlyHelper(
            int csipGroupId, int groupSize, boolean dualMode, boolean ashaDevice) {
        Utils.setDualModeAudioStateForTesting(false);
        mPhonePolicy.mLeAudioEnabledByDefault = true;
        mPhonePolicy.mAutoConnectProfilesSupported = true;
        doReturn(false)
                .when(mProperties)
                .getBoolean(eq(PhonePolicy.BYPASS_LE_AUDIO_ALLOWLIST_PROPERTY), anyBoolean());

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
            when(mLeAudioService.setConnectionPolicy(dev, CONNECTION_POLICY_ALLOWED))
                    .thenAnswer(
                            invocation -> {
                                return setLeAudioAllowedConnectionPolicy(dev);
                            });
            when(mLeAudioService.getConnectionPolicy(dev))
                    .thenAnswer(
                            invocation -> {
                                return getLeAudioConnectionPolicy(dev);
                            });

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
    @EnableFlags(Flags.FLAG_LEAUDIO_ALLOW_LEAUDIO_ONLY_DEVICES)
    public void testConnectLeAudioOnlyDevices_BandedHeadphones() {
        // Single device, no CSIP
        processInitProfilePriorities_LeAudioOnlyHelper(
                BluetoothCsipSetCoordinator.GROUP_ID_INVALID, 1, false, false);
        verify(mLeAudioService)
                .setConnectionPolicy(any(BluetoothDevice.class), eq(CONNECTION_POLICY_ALLOWED));
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_ALLOW_LEAUDIO_ONLY_DEVICES)
    public void testConnectLeAudioOnlyDevices_CsipSet() {
        // CSIP Le Audio only devices
        processInitProfilePriorities_LeAudioOnlyHelper(1, 2, false, false);
        verify(mLeAudioService, times(2))
                .setConnectionPolicy(any(BluetoothDevice.class), eq(CONNECTION_POLICY_ALLOWED));
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_ALLOW_LEAUDIO_ONLY_DEVICES)
    public void testConnectLeAudioOnlyDevices_DualModeCsipSet() {
        // CSIP Dual mode devices
        processInitProfilePriorities_LeAudioOnlyHelper(1, 2, true, false);
        verify(mLeAudioService, never())
                .setConnectionPolicy(any(BluetoothDevice.class), eq(CONNECTION_POLICY_ALLOWED));
    }

    @Test
    @EnableFlags(Flags.FLAG_LEAUDIO_ALLOW_LEAUDIO_ONLY_DEVICES)
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
        mPhonePolicy.onUuidsDiscovered(mDevice, uuids);

        // Check auto connect
        verify(mA2dpService).setConnectionPolicy(mDevice, CONNECTION_POLICY_ALLOWED);
        verify(mHeadsetService).setConnectionPolicy(mDevice, CONNECTION_POLICY_ALLOWED);
    }

    @Test
    public void testProcessInitProfilePriorities_LeAudioDisabledByDefault() {
        when(mAdapterService.isLeAudioAllowed(mDevice)).thenReturn(true);

        // Auto connect to LE audio, HFP, A2DP
        processInitProfilePriorities_LeAudioHelper(true, true, false, false);
        verify(mLeAudioService).setConnectionPolicy(mDevice, CONNECTION_POLICY_ALLOWED);
        verify(mA2dpService).setConnectionPolicy(mDevice, CONNECTION_POLICY_ALLOWED);
        verify(mHeadsetService).setConnectionPolicy(mDevice, CONNECTION_POLICY_ALLOWED);

        // Does not auto connect and allow HFP and A2DP to be connected
        processInitProfilePriorities_LeAudioHelper(true, false, false, false);
        verify(mDatabaseManager)
                .setProfileConnectionPolicy(
                        mDevice, BluetoothProfile.LE_AUDIO, CONNECTION_POLICY_ALLOWED);
        verify(mDatabaseManager)
                .setProfileConnectionPolicy(
                        mDevice, BluetoothProfile.A2DP, CONNECTION_POLICY_ALLOWED);
        verify(mDatabaseManager)
                .setProfileConnectionPolicy(
                        mDevice, BluetoothProfile.HEADSET, CONNECTION_POLICY_ALLOWED);

        // Auto connect to HFP and A2DP but disallow LE Audio
        processInitProfilePriorities_LeAudioHelper(false, true, false, false);
        verify(mDatabaseManager)
                .setProfileConnectionPolicy(
                        mDevice, BluetoothProfile.LE_AUDIO, CONNECTION_POLICY_FORBIDDEN);
        verify(mA2dpService, times(2)).setConnectionPolicy(mDevice, CONNECTION_POLICY_ALLOWED);
        verify(mHeadsetService, times(2)).setConnectionPolicy(mDevice, CONNECTION_POLICY_ALLOWED);

        // Does not auto connect and disallow LE Audio to be connected
        processInitProfilePriorities_LeAudioHelper(false, false, false, false);
        verify(mDatabaseManager, times(2))
                .setProfileConnectionPolicy(
                        mDevice, BluetoothProfile.LE_AUDIO, CONNECTION_POLICY_FORBIDDEN);
        verify(mDatabaseManager, times(2))
                .setProfileConnectionPolicy(
                        mDevice, BluetoothProfile.A2DP, CONNECTION_POLICY_ALLOWED);
        verify(mDatabaseManager, times(2))
                .setProfileConnectionPolicy(
                        mDevice, BluetoothProfile.HEADSET, CONNECTION_POLICY_ALLOWED);
    }

    @Test
    public void testProcessInitProfilePriorities_LeAudioEnabledByDefault() {
        when(mAdapterService.isLeAudioAllowed(mDevice)).thenReturn(true);

        // Auto connect to LE audio, HFP, A2DP
        processInitProfilePriorities_LeAudioHelper(true, true, true, false);
        verify(mLeAudioService).setConnectionPolicy(mDevice, CONNECTION_POLICY_ALLOWED);
        verify(mA2dpService).setConnectionPolicy(mDevice, CONNECTION_POLICY_ALLOWED);
        verify(mHeadsetService).setConnectionPolicy(mDevice, CONNECTION_POLICY_ALLOWED);

        // Does not auto connect and allow HFP and A2DP to be connected
        processInitProfilePriorities_LeAudioHelper(true, false, true, false);
        verify(mDatabaseManager)
                .setProfileConnectionPolicy(
                        mDevice, BluetoothProfile.LE_AUDIO, CONNECTION_POLICY_ALLOWED);
        verify(mDatabaseManager)
                .setProfileConnectionPolicy(
                        mDevice, BluetoothProfile.A2DP, CONNECTION_POLICY_ALLOWED);
        verify(mDatabaseManager)
                .setProfileConnectionPolicy(
                        mDevice, BluetoothProfile.HEADSET, CONNECTION_POLICY_ALLOWED);

        // Auto connect to LE audio but disallow HFP and A2DP
        processInitProfilePriorities_LeAudioHelper(false, true, true, false);
        verify(mLeAudioService, times(2)).setConnectionPolicy(mDevice, CONNECTION_POLICY_ALLOWED);
        verify(mDatabaseManager)
                .setProfileConnectionPolicy(
                        mDevice, BluetoothProfile.HEADSET, CONNECTION_POLICY_FORBIDDEN);
        verify(mDatabaseManager)
                .setProfileConnectionPolicy(
                        mDevice, BluetoothProfile.A2DP, CONNECTION_POLICY_FORBIDDEN);

        // Does not auto connect and disallow HFP and A2DP to be connected
        processInitProfilePriorities_LeAudioHelper(false, false, true, false);
        verify(mDatabaseManager, times(2))
                .setProfileConnectionPolicy(
                        mDevice, BluetoothProfile.LE_AUDIO, CONNECTION_POLICY_ALLOWED);
        verify(mDatabaseManager, times(2))
                .setProfileConnectionPolicy(
                        mDevice, BluetoothProfile.HEADSET, CONNECTION_POLICY_FORBIDDEN);
        verify(mDatabaseManager, times(2))
                .setProfileConnectionPolicy(
                        mDevice, BluetoothProfile.A2DP, CONNECTION_POLICY_FORBIDDEN);
    }

    private void processInitProfilePriorities_LeAudioHelper(
            boolean dualModeEnabled,
            boolean autoConnect,
            boolean leAudioEnabledByDefault,
            boolean bypassLeAudioAllowlist) {
        Utils.setDualModeAudioStateForTesting(dualModeEnabled);
        mPhonePolicy.mLeAudioEnabledByDefault = leAudioEnabledByDefault;
        mPhonePolicy.mAutoConnectProfilesSupported = autoConnect;
        doReturn(bypassLeAudioAllowlist)
                .when(mProperties)
                .getBoolean(eq(PhonePolicy.BYPASS_LE_AUDIO_ALLOWLIST_PROPERTY), anyBoolean());

        // Inject an event for UUIDs updated for a remote device with only HFP enabled
        ParcelUuid[] uuids = new ParcelUuid[3];
        uuids[0] = BluetoothUuid.HFP;
        uuids[1] = BluetoothUuid.A2DP_SINK;
        uuids[2] = BluetoothUuid.LE_AUDIO;
        mPhonePolicy.onUuidsDiscovered(mDevice, uuids);
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

        /* Just for the moment, set to true to setup first device */
        doReturn(true)
                .when(mProperties)
                .getBoolean(eq(PhonePolicy.BYPASS_LE_AUDIO_ALLOWLIST_PROPERTY), anyBoolean());

        int csipGroupId = 1;
        int groupSize = 2;

        List<BluetoothDevice> connectedDevices = new ArrayList<>();
        when(mCsipSetCoordinatorService.getDesiredGroupSize(csipGroupId)).thenReturn(groupSize);
        when(mCsipSetCoordinatorService.getGroupId(any(), any())).thenReturn(csipGroupId);
        when(mLeAudioService.getGroupId(any())).thenReturn(csipGroupId);
        when(mCsipSetCoordinatorService.getGroupDevicesOrdered(csipGroupId))
                .thenReturn(connectedDevices);

        // Connect first set member
        connectedDevices.add(mDevice);

        /* Build list of test UUIDs */
        ParcelUuid[] uuids = new ParcelUuid[4];
        uuids[0] = BluetoothUuid.LE_AUDIO;
        uuids[1] = BluetoothUuid.COORDINATED_SET;
        uuids[2] = BluetoothUuid.A2DP_SINK;
        uuids[3] = BluetoothUuid.HFP;

        // Prepare common handlers
        doReturn(CONNECTION_POLICY_ALLOWED).when(mHeadsetService).getConnectionPolicy(any());
        doReturn(CONNECTION_POLICY_ALLOWED).when(mA2dpService).getConnectionPolicy(any());

        when(mLeAudioService.setConnectionPolicy(
                        any(BluetoothDevice.class), eq(CONNECTION_POLICY_ALLOWED)))
                .thenAnswer(
                        invocation -> {
                            return setLeAudioAllowedConnectionPolicy(invocation.getArgument(0));
                        });
        when(mLeAudioService.getConnectionPolicy(any(BluetoothDevice.class)))
                .thenAnswer(
                        invocation -> {
                            return getLeAudioConnectionPolicy(invocation.getArgument(0));
                        });
        when(mLeAudioService.getGroupDevices(csipGroupId)).thenReturn(connectedDevices);

        when(mAdapterService.getRemoteUuids(any(BluetoothDevice.class))).thenReturn(uuids);
        when(mAdapterService.isProfileSupported(
                        any(BluetoothDevice.class), eq(BluetoothProfile.HEARING_AID)))
                .thenReturn(false);
        when(mAdapterService.isProfileSupported(
                        any(BluetoothDevice.class), eq(BluetoothProfile.LE_AUDIO)))
                .thenReturn(true);

        /* Always DualMode for test purpose */
        when(mAdapterService.getRemoteType(any(BluetoothDevice.class)))
                .thenReturn(BluetoothDevice.DEVICE_TYPE_DUAL);

        // Inject first devices
        mPhonePolicy.onUuidsDiscovered(mDevice, uuids);
        mPhonePolicy.profileConnectionStateChanged(
                BluetoothProfile.CSIP_SET_COORDINATOR,
                mDevice,
                STATE_DISCONNECTED,
                STATE_CONNECTED);
        mLooper.dispatchAll();

        // Verify connection policy is set properly
        verify(mLeAudioService).setConnectionPolicy(eq(mDevice), eq(CONNECTION_POLICY_ALLOWED));

        mPhonePolicy.profileActiveDeviceChanged(BluetoothProfile.LE_AUDIO, mDevice);
        mLooper.dispatchAll();

        verify(mA2dpService).setConnectionPolicy(eq(mDevice), eq(CONNECTION_POLICY_FORBIDDEN));
        verify(mHeadsetService).setConnectionPolicy(eq(mDevice), eq(CONNECTION_POLICY_FORBIDDEN));

        /* Remove bypass and check that second set member will be added*/
        doReturn(false)
                .when(mProperties)
                .getBoolean(eq(PhonePolicy.BYPASS_LE_AUDIO_ALLOWLIST_PROPERTY), anyBoolean());

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

        /* Just for the moment, set to true to setup first device */
        doReturn(true)
                .when(mProperties)
                .getBoolean(eq(PhonePolicy.BYPASS_LE_AUDIO_ALLOWLIST_PROPERTY), anyBoolean());

        int csipGroupId = 1;
        int groupSize = 2;

        List<BluetoothDevice> connectedDevices = new ArrayList<>();
        when(mCsipSetCoordinatorService.getDesiredGroupSize(csipGroupId)).thenReturn(groupSize);
        when(mCsipSetCoordinatorService.getGroupId(any(), any())).thenReturn(csipGroupId);
        when(mLeAudioService.getGroupId(any())).thenReturn(csipGroupId);
        when(mCsipSetCoordinatorService.getGroupDevicesOrdered(csipGroupId))
                .thenReturn(connectedDevices);

        // Connect first set member
        connectedDevices.add(mDevice);

        /* Build list of test UUIDs */
        ParcelUuid[] uuids = new ParcelUuid[4];
        uuids[0] = BluetoothUuid.LE_AUDIO;
        uuids[1] = BluetoothUuid.COORDINATED_SET;
        uuids[2] = BluetoothUuid.HEARING_AID;
        uuids[3] = BluetoothUuid.HAS;

        // Prepare common handlers
        doReturn(CONNECTION_POLICY_ALLOWED).when(mHearingAidService).getConnectionPolicy(any());

        when(mLeAudioService.setConnectionPolicy(
                        any(BluetoothDevice.class), eq(CONNECTION_POLICY_ALLOWED)))
                .thenAnswer(
                        invocation -> {
                            return setLeAudioAllowedConnectionPolicy(invocation.getArgument(0));
                        });
        when(mLeAudioService.getConnectionPolicy(any(BluetoothDevice.class)))
                .thenAnswer(
                        invocation -> {
                            return getLeAudioConnectionPolicy(invocation.getArgument(0));
                        });
        when(mLeAudioService.getGroupDevices(csipGroupId)).thenReturn(connectedDevices);

        when(mAdapterService.getRemoteUuids(any(BluetoothDevice.class))).thenReturn(uuids);
        when(mAdapterService.isProfileSupported(
                        any(BluetoothDevice.class), eq(BluetoothProfile.HEARING_AID)))
                .thenReturn(false);
        when(mAdapterService.isProfileSupported(
                        any(BluetoothDevice.class), eq(BluetoothProfile.LE_AUDIO)))
                .thenReturn(true);

        /* Always DualMode for test purpose */
        when(mAdapterService.getRemoteType(any(BluetoothDevice.class)))
                .thenReturn(BluetoothDevice.DEVICE_TYPE_LE);

        // Inject first devices
        mPhonePolicy.onUuidsDiscovered(mDevice, uuids);
        mPhonePolicy.profileConnectionStateChanged(
                BluetoothProfile.CSIP_SET_COORDINATOR,
                mDevice,
                STATE_DISCONNECTED,
                STATE_CONNECTED);
        mLooper.dispatchAll();

        // Verify connection policy is set properly
        verify(mLeAudioService).setConnectionPolicy(eq(mDevice), eq(CONNECTION_POLICY_ALLOWED));

        mPhonePolicy.profileActiveDeviceChanged(BluetoothProfile.LE_AUDIO, mDevice);
        mLooper.dispatchAll();

        verify(mHearingAidService)
                .setConnectionPolicy(eq(mDevice), eq(CONNECTION_POLICY_FORBIDDEN));

        /* Remove bypass and check that second set member will be added*/
        doReturn(false)
                .when(mProperties)
                .getBoolean(eq(PhonePolicy.BYPASS_LE_AUDIO_ALLOWLIST_PROPERTY), anyBoolean());

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
        when(mAdapterService.isQuietModeEnabled()).thenReturn(false);

        // Return a list of connection order
        when(mDatabaseManager.getMostRecentlyConnectedA2dpDevice()).thenReturn(mDevice);
        when(mAdapterService.getBondState(mDevice)).thenReturn(BluetoothDevice.BOND_BONDED);

        doReturn(CONNECTION_POLICY_ALLOWED).when(mHeadsetService).getConnectionPolicy(any());
        doReturn(CONNECTION_POLICY_ALLOWED).when(mA2dpService).getConnectionPolicy(any());

        // Inject an event that the adapter is turned on.
        mPhonePolicy.onBluetoothStateChange(BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_ON);

        // Check that we got a request to connect over HFP and A2DP
        verify(mA2dpService).connect(eq(mDevice));
        verify(mHeadsetService).connect(eq(mDevice));
    }

    /** Test that when an active device is disconnected, we will not auto connect it */
    @Test
    public void testDisconnectNoAutoConnect() {
        // Return desired values from the mocked object(s)
        when(mAdapterService.isQuietModeEnabled()).thenReturn(false);

        // Return a list of connection order
        List<BluetoothDevice> connectionOrder = new ArrayList<>();
        connectionOrder.add(mDevice);
        connectionOrder.add(mDevice2);
        connectionOrder.add(getTestDevice(2));
        connectionOrder.add(getTestDevice(3));

        doReturn(mDevice).when(mDatabaseManager).getMostRecentlyConnectedA2dpDevice();

        // Make all devices auto connect
        doReturn(CONNECTION_POLICY_ALLOWED).when(mHeadsetService).getConnectionPolicy(any());
        doReturn(CONNECTION_POLICY_FORBIDDEN)
                .when(mA2dpService)
                .getConnectionPolicy(eq(connectionOrder.get(3)));

        // Make one of the device active
        mPhonePolicy.profileActiveDeviceChanged(BluetoothProfile.A2DP, connectionOrder.get(0));
        mLooper.dispatchAll();

        // Only calls setConnection on device connectionOrder.get(0) with STATE_CONNECTED
        verify(mDatabaseManager).setConnection(mDevice, BluetoothProfile.A2DP);
        verify(mDatabaseManager, never()).setConnection(eq(connectionOrder.get(1)), anyInt());
        verify(mDatabaseManager, never()).setConnection(eq(connectionOrder.get(2)), anyInt());
        verify(mDatabaseManager, never()).setConnection(eq(connectionOrder.get(3)), anyInt());

        // Make another device active
        when(mHeadsetService.getConnectionState(connectionOrder.get(1)))
                .thenReturn(STATE_CONNECTED);
        mPhonePolicy.profileActiveDeviceChanged(BluetoothProfile.A2DP, connectionOrder.get(1));
        mLooper.dispatchAll();

        // Only calls setConnection on device connectionOrder.get(1) with STATE_CONNECTED
        verify(mDatabaseManager).setConnection(connectionOrder.get(0), BluetoothProfile.A2DP);
        verify(mDatabaseManager).setConnection(connectionOrder.get(1), BluetoothProfile.A2DP);
        verify(mDatabaseManager, never()).setConnection(eq(connectionOrder.get(2)), anyInt());
        verify(mDatabaseManager, never()).setConnection(eq(connectionOrder.get(3)), anyInt());

        // Disconnect a2dp for the device from previous STATE_CONNECTED
        when(mHeadsetService.getConnectionState(connectionOrder.get(1)))
                .thenReturn(STATE_DISCONNECTED);
        mPhonePolicy.profileConnectionStateChanged(
                BluetoothProfile.A2DP, connectionOrder.get(1), STATE_CONNECTED, STATE_DISCONNECTED);
        mLooper.dispatchAll();

        // Verify that we do not call setConnection, nor setDisconnection on disconnect
        // from previous STATE_CONNECTED
        verify(mDatabaseManager)
                .setConnection(eq(connectionOrder.get(1)), eq(BluetoothProfile.A2DP));
        verify(mDatabaseManager, never())
                .setDisconnection(eq(connectionOrder.get(1)), eq(BluetoothProfile.A2DP));

        // Disconnect a2dp for the device from previous STATE_DISCONNECTING
        mPhonePolicy.profileConnectionStateChanged(
                BluetoothProfile.A2DP,
                connectionOrder.get(1),
                STATE_DISCONNECTING,
                STATE_DISCONNECTED);
        mLooper.dispatchAll();

        // Verify that we do not call setConnection, but instead setDisconnection on disconnect
        verify(mDatabaseManager)
                .setConnection(eq(connectionOrder.get(1)), eq(BluetoothProfile.A2DP));
        verify(mDatabaseManager)
                .setDisconnection(eq(connectionOrder.get(1)), eq(BluetoothProfile.A2DP));

        // Make the current active device fail to connect
        when(mA2dpService.getConnectionState(connectionOrder.get(1)))
                .thenReturn(STATE_DISCONNECTED);
        updateProfileConnectionStateHelper(
                connectionOrder.get(1),
                BluetoothProfile.HEADSET,
                STATE_CONNECTING,
                STATE_DISCONNECTED);
        mLooper.dispatchAll();

        // Verify we don't call deleteConnection as that only happens when we disconnect a2dp
        verify(mDatabaseManager)
                .setDisconnection(eq(connectionOrder.get(1)), eq(BluetoothProfile.A2DP));

        // Verify we didn't have any unexpected calls to setConnection or deleteConnection
        verify(mDatabaseManager, times(2)).setConnection(any(BluetoothDevice.class), anyInt());
        verify(mDatabaseManager)
                .setDisconnection(eq(connectionOrder.get(1)), eq(BluetoothProfile.HEADSET));
    }

    /**
     * Test that we will try to re-connect to a profile on a device if other profile(s) are
     * connected. This is to add robustness to the connection mechanism
     */
    @Test
    public void testReconnectOnPartialConnect() {
        BluetoothDevice[] bondedDevices = {mDevice};
        doReturn(bondedDevices).when(mAdapterService).getBondedDevices();

        doReturn(CONNECTION_POLICY_ALLOWED).when(mHeadsetService).getConnectionPolicy(any());
        doReturn(CONNECTION_POLICY_ALLOWED).when(mA2dpService).getConnectionPolicy(any());

        // We want to trigger (in CONNECT_OTHER_PROFILES_TIMEOUT) a call to connect A2DP
        // To enable that we need to make sure that HeadsetService returns the device as list of
        // connected devices
        ArrayList<BluetoothDevice> hsConnectedDevices = new ArrayList<>();
        hsConnectedDevices.add(mDevice);
        when(mHeadsetService.getConnectedDevices()).thenReturn(hsConnectedDevices);
        // Also the A2DP should say that its not connected for same device
        when(mA2dpService.getConnectionState(mDevice)).thenReturn(STATE_DISCONNECTED);

        // ACL is connected, lets simulate this.
        when(mAdapterService.getConnectionState(mDevice))
                .thenReturn(BluetoothDevice.CONNECTION_STATE_ENCRYPTED_BREDR);

        // We send a connection successful for one profile since the re-connect *only* works if we
        // have already connected successfully over one of the profiles
        updateProfileConnectionStateHelper(
                mDevice, BluetoothProfile.HEADSET, STATE_DISCONNECTED, STATE_CONNECTED);

        // Check that we get a call to A2DP connect
        verify(mA2dpService).connect(eq(mDevice));
    }

    /**
     * Test that connectOtherProfile will not trigger any actions when ACL is disconnected. This is
     * to add robustness to the connection mechanism
     */
    @Test
    public void testConnectOtherProfileWhileDeviceIsDisconnected() {
        BluetoothDevice[] bondedDevices = {mDevice};
        doReturn(bondedDevices).when(mAdapterService).getBondedDevices();

        doReturn(CONNECTION_POLICY_ALLOWED).when(mHeadsetService).getConnectionPolicy(any());
        doReturn(CONNECTION_POLICY_ALLOWED).when(mA2dpService).getConnectionPolicy(any());

        // We want to trigger (in CONNECT_OTHER_PROFILES_TIMEOUT) a call to connect A2DP
        // To enable that we need to make sure that HeadsetService returns the device as list of
        // connected devices
        doReturn(List.of(mDevice)).when(mHeadsetService).getConnectedDevices();

        // ACL is disconnected just after HEADSET profile got connected and connectOtherProfile
        // was scheduled. Lets simulate this.
        when(mAdapterService.getConnectionState(mDevice))
                .thenReturn(BluetoothDevice.CONNECTION_STATE_DISCONNECTED);

        // We send a connection successful for one profile since the re-connect *only* works if we
        // have already connected successfully over one of the profiles
        updateProfileConnectionStateHelper(
                mDevice, BluetoothProfile.HEADSET, STATE_DISCONNECTED, STATE_CONNECTED);

        // Check that there will be no A2DP connect
        verify(mA2dpService, never()).connect(eq(mDevice));
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
        doReturn(STATE_CONNECTED).when(mAdapterService).getConnectionState(mDevice);
        doReturn(mDevice).when(mDatabaseManager).getMostRecentlyConnectedA2dpDevice();
        doReturn(CONNECTION_POLICY_ALLOWED).when(mHeadsetService).getConnectionPolicy(any());
        doReturn(CONNECTION_POLICY_ALLOWED).when(mA2dpService).getConnectionPolicy(any());

        // We want to trigger (in CONNECT_OTHER_PROFILES_TIMEOUT) a call to connect A2DP
        // To enable that we need to make sure that HeadsetService returns the device among a list
        // of connected devices
        doReturn(List.of(mDevice)).when(mHeadsetService).getConnectedDevices();

        // We send a connection success event for one profile since the re-connect *only* works if
        // we have already connected successfully over one of the profiles
        updateProfileConnectionStateHelper(
                mDevice, BluetoothProfile.HEADSET, STATE_DISCONNECTED, STATE_CONNECTED);

        // Check that we get a call to A2DP reconnect
        mInOrder.verify(mA2dpService).connect(mDevice);

        // We send a connection failure event for the attempted profile, and keep the connected
        // profile connected.
        updateProfileConnectionStateHelper(
                mDevice, BluetoothProfile.A2DP, STATE_CONNECTING, STATE_DISCONNECTED);

        // Verify no one changes the priority of the failed profile
        mInOrder.verify(mA2dpService, never()).setConnectionPolicy(eq(mDevice), anyInt());

        // Send a connection success event for one profile again without disconnecting all profiles
        updateProfileConnectionStateHelper(
                mDevice, BluetoothProfile.HEADSET, STATE_DISCONNECTED, STATE_CONNECTED);

        // Check that we won't get a call to A2DP reconnect again before all profiles disconnected
        mInOrder.verify(mA2dpService, never()).connect(mDevice);

        // Send a disconnection event for all connected profiles
        doReturn(Collections.emptyList()).when(mHeadsetService).getConnectedDevices();
        updateProfileConnectionStateHelper(
                mDevice, BluetoothProfile.HEADSET, STATE_CONNECTED, STATE_DISCONNECTED);

        // Send a connection success event for one profile again to trigger re-connect
        doReturn(List.of(mDevice)).when(mHeadsetService).getConnectedDevices();
        updateProfileConnectionStateHelper(
                mDevice, BluetoothProfile.HEADSET, STATE_DISCONNECTED, STATE_CONNECTED);

        // Check that we get a call to A2DP connect again
        mInOrder.verify(mA2dpService).connect(mDevice);
    }

    /**
     * Test that when the adapter is turned ON then call auto connect on devices that only has HFP
     * enabled. NOTE that the assumption is that we have already done the pairing previously and
     * hence the priorities for the device is already set to AUTO_CONNECT over HFP (as part of post
     * pairing process).
     */
    @Test
    @DisableFlags(Flags.FLAG_AUTO_CONNECT_ON_MULTIPLE_HFP_WHEN_NO_A2DP_DEVICE)
    public void testAutoConnectHfpOnly() {

        // Return desired values from the mocked object(s)
        doReturn(false).when(mAdapterService).isQuietModeEnabled();

        MetadataDatabase mDatabase =
                Room.inMemoryDatabaseBuilder(
                                InstrumentationRegistry.getInstrumentation().getTargetContext(),
                                MetadataDatabase.class)
                        .build();
        DatabaseManager db = new DatabaseManager(mAdapterService);
        doReturn(db).when(mAdapterService).getDatabase();
        PhonePolicy phonePolicy =
                new PhonePolicy(mAdapterService, mLooper.getLooper(), mServiceFactory);

        db.start(mDatabase);
        TestUtils.waitForLooperToFinishScheduledTask(db.getHandlerLooper());

        // Return a device that is HFP only

        db.setConnection(mDevice, BluetoothProfile.HEADSET);
        doReturn(CONNECTION_POLICY_ALLOWED).when(mHeadsetService).getConnectionPolicy(eq(mDevice));

        // wait for all MSG_UPDATE_DATABASE
        TestUtils.waitForLooperToFinishScheduledTask(db.getHandlerLooper());

        phonePolicy.autoConnect();

        // Check that we got a request to connect over HFP for each device
        verify(mHeadsetService).connect(eq(mDevice));
    }

    @Test
    @EnableFlags(Flags.FLAG_AUTO_CONNECT_ON_MULTIPLE_HFP_WHEN_NO_A2DP_DEVICE)
    public void autoConnect_whenMultiHfp_startConnection() {
        // Return desired values from the mocked object(s)
        doReturn(false).when(mAdapterService).isQuietModeEnabled();

        MetadataDatabase mDatabase =
                Room.inMemoryDatabaseBuilder(
                                InstrumentationRegistry.getInstrumentation().getTargetContext(),
                                MetadataDatabase.class)
                        .build();
        DatabaseManager db = new DatabaseManager(mAdapterService);
        doReturn(db).when(mAdapterService).getDatabase();
        PhonePolicy phonePolicy =
                new PhonePolicy(mAdapterService, mLooper.getLooper(), mServiceFactory);

        db.start(mDatabase);
        TestUtils.waitForLooperToFinishScheduledTask(db.getHandlerLooper());

        List<BluetoothDevice> devices =
                List.of(getTestDevice(1), getTestDevice(2), getTestDevice(3));

        for (BluetoothDevice device : devices) {
            db.setConnection(device, BluetoothProfile.HEADSET);
            doReturn(CONNECTION_POLICY_ALLOWED)
                    .when(mHeadsetService)
                    .getConnectionPolicy(eq(device));
        }
        // wait for all MSG_UPDATE_DATABASE
        TestUtils.waitForLooperToFinishScheduledTask(db.getHandlerLooper());

        phonePolicy.autoConnect();

        // Check that we got a request to connect over HFP for each device
        for (BluetoothDevice device : devices) {
            verify(mHeadsetService).connect(eq(device));
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_AUTO_CONNECT_ON_MULTIPLE_HFP_WHEN_NO_A2DP_DEVICE)
    public void autoConnect_whenMultiHfpAndDisconnection_startConnection() {
        // Return desired values from the mocked object(s)
        doReturn(false).when(mAdapterService).isQuietModeEnabled();

        MetadataDatabase mDatabase =
                Room.inMemoryDatabaseBuilder(
                                InstrumentationRegistry.getInstrumentation().getTargetContext(),
                                MetadataDatabase.class)
                        .build();
        DatabaseManager db = new DatabaseManager(mAdapterService);
        doReturn(db).when(mAdapterService).getDatabase();
        PhonePolicy phonePolicy =
                new PhonePolicy(mAdapterService, mLooper.getLooper(), mServiceFactory);

        db.start(mDatabase);
        TestUtils.waitForLooperToFinishScheduledTask(db.getHandlerLooper());

        BluetoothDevice deviceToDisconnect = getTestDevice(0);
        db.setConnection(deviceToDisconnect, BluetoothProfile.HEADSET);
        doReturn(CONNECTION_POLICY_ALLOWED)
                .when(mHeadsetService)
                .getConnectionPolicy(eq(deviceToDisconnect));

        List<BluetoothDevice> devices =
                List.of(getTestDevice(1), getTestDevice(2), getTestDevice(3));

        for (BluetoothDevice device : devices) {
            db.setConnection(device, BluetoothProfile.HEADSET);
            doReturn(CONNECTION_POLICY_ALLOWED)
                    .when(mHeadsetService)
                    .getConnectionPolicy(eq(device));
        }

        db.setDisconnection(deviceToDisconnect, BluetoothProfile.HEADSET);

        // wait for all MSG_UPDATE_DATABASE
        TestUtils.waitForLooperToFinishScheduledTask(db.getHandlerLooper());

        phonePolicy.autoConnect();

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
            when(mAdapterService.getConnectionState(testDevice)).thenReturn(STATE_CONNECTED);

            // Return PRIORITY_AUTO_CONNECT over HFP and A2DP. This would imply that the profiles
            // are auto-connectable.
            when(mHeadsetService.getConnectionPolicy(testDevice))
                    .thenReturn(CONNECTION_POLICY_ALLOWED);
            when(mA2dpService.getConnectionPolicy(testDevice))
                    .thenReturn(CONNECTION_POLICY_ALLOWED);
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

        when(mAdapterService.getBondedDevices()).thenReturn(testDevices);
        when(mHeadsetService.getConnectedDevices()).thenReturn(hsConnectedDevices);
        when(mA2dpService.getConnectedDevices()).thenReturn(a2dpConnectedDevices);
        // Two of the A2DP devices are not connected
        when(mA2dpService.getConnectionState(a2dpNotConnectedDevice1))
                .thenReturn(STATE_DISCONNECTED);
        when(mA2dpService.getConnectionState(a2dpNotConnectedDevice2))
                .thenReturn(STATE_DISCONNECTED);

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
            when(mAdapterService.getConnectionState(testDevices[i])).thenReturn(STATE_CONNECTED);

            // Connect HFP and A2DP for each device as appropriate.
            // Return PRIORITY_AUTO_CONNECT only for testDevices[0]
            if (i == 0) {
                hsConnectedDevices.add(testDevice);
                a2dpConnectedDevices.add(testDevice);
                when(mHeadsetService.getConnectionPolicy(testDevice))
                        .thenReturn(CONNECTION_POLICY_ALLOWED);
                when(mA2dpService.getConnectionPolicy(testDevice))
                        .thenReturn(CONNECTION_POLICY_ALLOWED);
            }
            if (i == 1) {
                hsConnectedDevices.add(testDevice);
                when(mHeadsetService.getConnectionPolicy(testDevice))
                        .thenReturn(CONNECTION_POLICY_ALLOWED);
                when(mA2dpService.getConnectionPolicy(testDevice))
                        .thenReturn(CONNECTION_POLICY_ALLOWED);
            }
            if (i == 2) {
                a2dpConnectedDevices.add(testDevice);
                when(mHeadsetService.getConnectionPolicy(testDevice))
                        .thenReturn(CONNECTION_POLICY_ALLOWED);
                when(mA2dpService.getConnectionPolicy(testDevice))
                        .thenReturn(CONNECTION_POLICY_ALLOWED);
            }
            if (i == 3) {
                // Device not connected
                when(mHeadsetService.getConnectionPolicy(testDevice))
                        .thenReturn(CONNECTION_POLICY_ALLOWED);
                when(mA2dpService.getConnectionPolicy(testDevice))
                        .thenReturn(CONNECTION_POLICY_ALLOWED);
            }
        }
        when(mAdapterService.getBondedDevices()).thenReturn(testDevices);
        when(mHeadsetService.getConnectedDevices()).thenReturn(hsConnectedDevices);
        when(mA2dpService.getConnectedDevices()).thenReturn(a2dpConnectedDevices);
        // Some of the devices are not connected
        // testDevices[0] - connected for both HFP and A2DP
        when(mHeadsetService.getConnectionState(testDevices[0])).thenReturn(STATE_CONNECTED);
        when(mA2dpService.getConnectionState(testDevices[0])).thenReturn(STATE_CONNECTED);
        // testDevices[1] - connected only for HFP - will auto-connect for A2DP
        when(mHeadsetService.getConnectionState(testDevices[1])).thenReturn(STATE_CONNECTED);
        when(mA2dpService.getConnectionState(testDevices[1])).thenReturn(STATE_DISCONNECTED);
        // testDevices[2] - connected only for A2DP - will auto-connect for HFP
        when(mHeadsetService.getConnectionState(testDevices[2])).thenReturn(STATE_DISCONNECTED);
        when(mA2dpService.getConnectionState(testDevices[2])).thenReturn(STATE_CONNECTED);
        // testDevices[3] - not connected
        when(mHeadsetService.getConnectionState(testDevices[3])).thenReturn(STATE_DISCONNECTED);
        when(mA2dpService.getConnectionState(testDevices[3])).thenReturn(STATE_DISCONNECTED);

        // Generate connection state changed for HFP for testDevices[1] and trigger
        // auto-connect for A2DP.
        updateProfileConnectionStateHelper(
                testDevices[1], BluetoothProfile.HEADSET, STATE_DISCONNECTED, STATE_CONNECTED);

        // Check that we get a call to A2DP connect
        verify(mA2dpService).connect(eq(testDevices[1]));

        // testDevices[1] auto-connect completed for A2DP
        a2dpConnectedDevices.add(testDevices[1]);
        when(mA2dpService.getConnectedDevices()).thenReturn(a2dpConnectedDevices);
        when(mA2dpService.getConnectionState(testDevices[1])).thenReturn(STATE_CONNECTED);

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
        when(mHeadsetService.getConnectedDevices()).thenReturn(hsConnectedDevices);
        when(mHeadsetService.getConnectionState(testDevices[2])).thenReturn(STATE_CONNECTED);

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
        BluetoothDevice[] bondedDevices = {mDevice};
        doReturn(bondedDevices).when(mAdapterService).getBondedDevices();

        doReturn(CONNECTION_POLICY_ALLOWED).when(mHeadsetService).getConnectionPolicy(any());
        doReturn(CONNECTION_POLICY_ALLOWED).when(mA2dpService).getConnectionPolicy(any());

        mPhonePolicy.handleAclConnected(mDevice);
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
        BluetoothDevice[] bondedDevices = {mDevice, mDevice2};
        doReturn(bondedDevices).when(mAdapterService).getBondedDevices();

        doReturn(CONNECTION_POLICY_ALLOWED).when(mHeadsetService).getConnectionPolicy(any());
        doReturn(CONNECTION_POLICY_ALLOWED).when(mA2dpService).getConnectionPolicy(any());

        doReturn(List.of(mDevice2)).when(mHeadsetService).getConnectedDevices();
        doReturn(List.of(mDevice2)).when(mA2dpService).getConnectedDevices();

        doReturn(STATE_CONNECTED).when(mA2dpService).getConnectionState(eq(mDevice2));
        doReturn(STATE_CONNECTED).when(mHeadsetService).getConnectionState(eq(mDevice2));

        doReturn(STATE_CONNECTED).when(mAdapterService).getConnectionState(any());

        // We send a connection successful for one profile since the re-connect *only* works if we
        // have already connected successfully over one of the profiles
        mPhonePolicy.handleAclConnected(mDevice);
        mLooper.dispatchAll();

        // Check that we don't get any calls to reconnect
        verify(mA2dpService, never()).connect(eq(mDevice));
        verify(mHeadsetService, never()).connect(eq(mDevice));
    }

    /**
     * Test that we will try to connect to other profiles of a device if it is partially connected
     */
    @Test
    public void testReconnectOnPartialConnect_MultiDevice() {
        BluetoothDevice[] bondedDevices = {mDevice, mDevice2};
        doReturn(bondedDevices).when(mAdapterService).getBondedDevices();

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
        mPhonePolicy.onUuidsDiscovered(mDevice, null);

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
        }
        mPhonePolicy.profileConnectionStateChanged(profileId, device, prevState, nextState);
        mLooper.dispatchAll();
        mLooper.moveTimeForward(PhonePolicy.CONNECT_OTHER_PROFILES_TIMEOUT.toMillis());
        mLooper.dispatchAll();
    }
}
