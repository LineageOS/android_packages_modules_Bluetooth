/*
 * Copyright (C) 2018 The Android Open Source Project
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
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import static com.android.bluetooth.TestUtils.getTestDevice;
import static com.android.bluetooth.TestUtils.mockGetSystemService;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSinkAudioPolicy;
import android.media.AudioManager;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.filters.MediumTest;

import com.android.bluetooth.BluetoothMethodProxy;
import com.android.bluetooth.TestLooper;
import com.android.bluetooth.Utils;
import com.android.bluetooth.a2dp.A2dpService;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.hearingaid.HearingAidService;
import com.android.bluetooth.hfp.HeadsetService;
import com.android.bluetooth.le_audio.LeAudioService;
import com.android.bluetooth.storage.BluetoothStorageManager;
import com.android.tests.bluetooth.FlagsWrapper;
import com.android.tests.bluetooth.MockitoRule;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;

import platform.test.runner.parameterized.ParameterizedAndroidJunit4;
import platform.test.runner.parameterized.Parameters;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Test cases for {@link ActiveDeviceManager}. */
@MediumTest
@RunWith(ParameterizedAndroidJunit4.class)
public class ActiveDeviceManagerTest {
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();
    @Rule public final SetFlagsRule mSetFlagsRule;

    @Mock private AdapterService mAdapterService;
    @Mock private A2dpService mA2dpService;
    @Mock private HeadsetService mHeadsetService;
    @Mock private HearingAidService mHearingAidService;
    @Mock private LeAudioService mLeAudioService;
    @Mock private AudioManager mAudioManager;
    @Mock private BluetoothStorageManager mStorage;
    @Mock private DatabaseManager mDatabaseManager;

    @Spy private BluetoothMethodProxy mMethodProxy = BluetoothMethodProxy.getInstance();

    private static final int A2DP_HFP_SYNC_CONNECTION_TIMEOUT_MS =
            ActiveDeviceManager.A2DP_HFP_SYNC_CONNECTION_TIMEOUT_MS + 2_000;
    private static final long HEARING_AID_HI_SYNC_ID = 1010;
    private static final long DUAL_MODE_HEARING_AID_HI_SYNC_ID = 2020;

    private final BluetoothDevice mA2dpDevice = getTestDevice(0);
    private final BluetoothDevice mHeadsetDevice = getTestDevice(1);
    private final BluetoothDevice mA2dpHeadsetDevice = getTestDevice(2);
    private final BluetoothDevice mHearingAidDevice = getTestDevice(3);
    private final BluetoothDevice mLeAudioDevice = getTestDevice(4);
    private final BluetoothDevice mLeAudioDevice2 = getTestDevice(5);
    private final BluetoothDevice mLeAudioDevice3 = getTestDevice(6);
    private final BluetoothDevice mLeAudioDevice4 = getTestDevice(7);
    private final BluetoothDevice mLeHearingAidDevice = getTestDevice(8);
    private final BluetoothDevice mSecondaryAudioDevice = getTestDevice(9);
    private final BluetoothDevice mDualModeAudioDevice = getTestDevice(10);
    private final BluetoothDevice mDualModeHearingAidDevice = getTestDevice(11);
    private final BluetoothDevice mDualModeAudioDevice2 = getTestDevice(12);

    private ArrayList<BluetoothDevice> mDeviceConnectionStack;
    private BluetoothDevice mMostRecentDevice;
    private ActiveDeviceManager mActiveDeviceManager;
    private boolean mOriginalDualModeAudioState;
    private TestLooper mTestLooper;

    @Parameters(name = "{0}")
    public static List<FlagsWrapper> getParams() {
        return FlagsWrapper.progressionOf(Flags.FLAG_MAINLINE_BETA_STORAGE);
    }

    public ActiveDeviceManagerTest(FlagsWrapper flags) {
        mSetFlagsRule = new SetFlagsRule(flags.getFlags());
    }

    @Before
    public void setUp() throws Exception {
        mTestLooper = new TestLooper();
        BluetoothMethodProxy.setInstanceForTesting(mMethodProxy);
        doReturn(mTestLooper.getLooper()).when(mMethodProxy).handlerThreadGetLooper(any());
        doNothing().when(mMethodProxy).threadStart(any());

        doAnswer(invocation -> getMostRecentlyConnectedDeviceInList(invocation.getArgument(0)))
                .when(mDatabaseManager)
                .getMostRecentlyConnectedDevicesInList(any());
        doAnswer(invocation -> getMostRecentlyConnectedDeviceInList(invocation.getArgument(0)))
                .when(mStorage)
                .getMostRecentlyConnectedDeviceInList(any());
        doAnswer(invocation -> getMostRecentlyConnectedDevices())
                .when(mDatabaseManager)
                .getMostRecentlyConnectedDevices();
        doAnswer(invocation -> getMostRecentlyConnectedDevices())
                .when(mStorage)
                .getMostRecentlyConnectedDevices();

        mockGetSystemService(mAdapterService, AudioManager.class, mAudioManager);
        when(mAdapterService.getDatabaseManager()).thenReturn(mDatabaseManager);
        doReturn(Optional.of(mA2dpService)).when(mAdapterService).getA2dpService();
        doReturn(Optional.of(mHeadsetService)).when(mAdapterService).getHeadsetService();
        doReturn(Optional.of(mHearingAidService)).when(mAdapterService).getHearingAidService();
        doReturn(Optional.of(mLeAudioService)).when(mAdapterService).getLeAudioService();
        doReturn(true)
                .when(mAdapterService)
                .isProfileSupported(mLeHearingAidDevice, BluetoothProfile.HAP_CLIENT);

        mActiveDeviceManager = new ActiveDeviceManager(mAdapterService, mStorage);
        mActiveDeviceManager.start();

        // Get devices for testing
        mDeviceConnectionStack = new ArrayList<>();
        mMostRecentDevice = null;
        mOriginalDualModeAudioState = Utils.isDualModeAudioEnabled();

        when(mA2dpService.setActiveDevice(any())).thenReturn(true);
        when(mHeadsetService.getHfpCallAudioPolicy(any()))
                .thenReturn(new BluetoothSinkAudioPolicy.Builder().build());
        when(mHeadsetService.setActiveDevice(any())).thenReturn(true);
        when(mHearingAidService.setActiveDevice(any())).thenReturn(true);
        when(mLeAudioService.setActiveDevice(any())).thenReturn(true);
        when(mLeAudioService.isGroupAvailableForStream(anyInt())).thenReturn(true);
        when(mLeAudioService.removeActiveDevice(anyBoolean())).thenReturn(true);

        when(mLeAudioService.getLeadDevice(mLeAudioDevice)).thenReturn(mLeAudioDevice);
        when(mLeAudioService.getLeadDevice(mLeAudioDevice2)).thenReturn(mLeAudioDevice2);
        when(mLeAudioService.getLeadDevice(mLeAudioDevice3)).thenReturn(mLeAudioDevice3);
        when(mLeAudioService.getLeadDevice(mLeAudioDevice4)).thenReturn(mLeAudioDevice4);
        when(mLeAudioService.getLeadDevice(mDualModeAudioDevice)).thenReturn(mDualModeAudioDevice);
        when(mLeAudioService.getLeadDevice(mDualModeAudioDevice2))
                .thenReturn(mDualModeAudioDevice2);
        when(mLeAudioService.getLeadDevice(mLeHearingAidDevice)).thenReturn(mLeHearingAidDevice);
        when(mLeAudioService.getLeadDevice(mDualModeHearingAidDevice))
                .thenReturn(mDualModeHearingAidDevice);

        List<BluetoothDevice> connectedHearingAidDevices = new ArrayList<>();
        List<BluetoothDevice> connectedDualModeHearingAidDevices = new ArrayList<>();
        connectedHearingAidDevices.add(mHearingAidDevice);
        connectedDualModeHearingAidDevices.add(mDualModeHearingAidDevice);
        when(mHearingAidService.getHiSyncId(mHearingAidDevice)).thenReturn(HEARING_AID_HI_SYNC_ID);
        when(mHearingAidService.getHiSyncId(mDualModeHearingAidDevice))
                .thenReturn(DUAL_MODE_HEARING_AID_HI_SYNC_ID);
        when(mHearingAidService.getConnectedPeerDevices(HEARING_AID_HI_SYNC_ID))
                .thenReturn(connectedHearingAidDevices);
        when(mHearingAidService.getConnectedPeerDevices(DUAL_MODE_HEARING_AID_HI_SYNC_ID))
                .thenReturn(connectedDualModeHearingAidDevices);

        when(mA2dpService.getConnectionPolicy(mA2dpDevice)).thenReturn(CONNECTION_POLICY_ALLOWED);
        when(mHeadsetService.getConnectionPolicy(mHeadsetDevice))
                .thenReturn(CONNECTION_POLICY_ALLOWED);
        when(mA2dpService.getConnectionPolicy(mA2dpHeadsetDevice))
                .thenReturn(CONNECTION_POLICY_ALLOWED);
        when(mHeadsetService.getConnectionPolicy(mA2dpHeadsetDevice))
                .thenReturn(CONNECTION_POLICY_ALLOWED);
        when(mHearingAidService.getConnectionPolicy(mHearingAidDevice))
                .thenReturn(CONNECTION_POLICY_ALLOWED);
        when(mLeAudioService.getConnectionPolicy(mLeAudioDevice))
                .thenReturn(CONNECTION_POLICY_ALLOWED);
        when(mLeAudioService.getConnectionPolicy(mLeAudioDevice2))
                .thenReturn(CONNECTION_POLICY_ALLOWED);
        when(mLeAudioService.getConnectionPolicy(mLeAudioDevice3))
                .thenReturn(CONNECTION_POLICY_ALLOWED);
        when(mLeAudioService.getConnectionPolicy(mLeAudioDevice4))
                .thenReturn(CONNECTION_POLICY_ALLOWED);
        when(mLeAudioService.getConnectionPolicy(mDualModeAudioDevice))
                .thenReturn(CONNECTION_POLICY_ALLOWED);
        when(mA2dpService.getConnectionPolicy(mDualModeAudioDevice))
                .thenReturn(CONNECTION_POLICY_ALLOWED);
        when(mLeAudioService.getConnectionPolicy(mDualModeHearingAidDevice))
                .thenReturn(CONNECTION_POLICY_ALLOWED);
        when(mA2dpService.getConnectionPolicy(mDualModeHearingAidDevice))
                .thenReturn(CONNECTION_POLICY_ALLOWED);
        when(mHearingAidService.getConnectionPolicy(mDualModeHearingAidDevice))
                .thenReturn(CONNECTION_POLICY_ALLOWED);

        when(mA2dpService.getFallbackDevice())
                .thenAnswer(
                        invocation -> {
                            if (!mDeviceConnectionStack.isEmpty()
                                    && Objects.equals(
                                            mA2dpDevice,
                                            mDeviceConnectionStack.get(
                                                    mDeviceConnectionStack.size() - 1))) {
                                return mA2dpDevice;
                            }
                            return null;
                        });
        when(mHeadsetService.getFallbackDevice())
                .thenAnswer(
                        invocation -> {
                            if (!mDeviceConnectionStack.isEmpty()
                                    && Objects.equals(
                                            mHeadsetDevice,
                                            mDeviceConnectionStack.get(
                                                    mDeviceConnectionStack.size() - 1))) {
                                return mHeadsetDevice;
                            }
                            return null;
                        });
    }

    @After
    public void tearDown() throws Exception {
        BluetoothMethodProxy.setInstanceForTesting(null);
        if (mActiveDeviceManager != null) {
            mActiveDeviceManager.cleanup();
        }
        Utils.setDualModeAudioStateForTesting(mOriginalDualModeAudioState);
        assertThat(mTestLooper.nextMessage()).isNull();
    }

    private BluetoothDevice getMostRecentlyConnectedDeviceInList(List<BluetoothDevice> devices) {
        if (devices.isEmpty()) {
            return null;
        } else if (devices.contains(mLeHearingAidDevice)) {
            return mLeHearingAidDevice;
        } else if (devices.contains(mHearingAidDevice)) {
            return mHearingAidDevice;
        } else if (mMostRecentDevice != null && devices.contains(mMostRecentDevice)) {
            return mMostRecentDevice;
        }
        return devices.get(0);
    }

    private List<BluetoothDevice> getMostRecentlyConnectedDevices() {
        return mDeviceConnectionStack;
    }

    @Test
    public void testSetUpAndTearDown() {}

    /** One A2DP is connected. */
    @Test
    public void onlyA2dpConnected_setA2dpActive() {
        a2dpConnected(mA2dpDevice, false);
        mTestLooper.dispatchAll();
        verify(mA2dpService).setActiveDevice(mA2dpDevice);
    }

    @Test
    public void a2dpHeadsetConnected_setA2dpActiveShouldBeCalledAfterHeadsetConnected() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_IN_CALL);

        a2dpConnected(mA2dpHeadsetDevice, true);
        mTestLooper.dispatchAll();
        verify(mA2dpService, never()).setActiveDevice(mA2dpHeadsetDevice);

        headsetConnected(mA2dpHeadsetDevice, true);
        mTestLooper.dispatchAll();
        verify(mA2dpService).setActiveDevice(mA2dpHeadsetDevice);
        verify(mHeadsetService).setActiveDevice(mA2dpHeadsetDevice);
    }

    @Test
    public void a2dpAndHfpConnectedAtTheSameTime_setA2dpActiveShouldBeCalled() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_IN_CALL);

        a2dpConnected(mA2dpHeadsetDevice, true);
        headsetConnected(mA2dpHeadsetDevice, true);
        mTestLooper.dispatchAll();
        verify(mA2dpService).setActiveDevice(mA2dpHeadsetDevice);
        verify(mHeadsetService).setActiveDevice(mA2dpHeadsetDevice);
    }

    /** Two A2DP are connected. Should set the second one active. */
    @Test
    public void secondA2dpConnected_setSecondA2dpActive() {
        a2dpConnected(mA2dpDevice, false);
        mTestLooper.dispatchAll();
        verify(mA2dpService).setActiveDevice(mA2dpDevice);

        a2dpConnected(mSecondaryAudioDevice, false);
        mTestLooper.dispatchAll();
        verify(mA2dpService).setActiveDevice(mSecondaryAudioDevice);
    }

    /** One A2DP is connected and disconnected later. Should then set active device to null. */
    @Test
    public void lastA2dpDisconnected_clearA2dpActive() {
        a2dpConnected(mA2dpDevice, false);
        mTestLooper.dispatchAll();
        verify(mA2dpService).setActiveDevice(mA2dpDevice);

        a2dpDisconnected(mA2dpDevice);
        mTestLooper.dispatchAll();
        verify(mA2dpService).removeActiveDevice(true);
    }

    /** Two A2DP are connected and active device is explicitly set. */
    @Test
    public void a2dpActiveDeviceSelected_setActive() {
        a2dpConnected(mA2dpDevice, false);
        mTestLooper.dispatchAll();
        verify(mA2dpService).setActiveDevice(mA2dpDevice);

        a2dpConnected(mSecondaryAudioDevice, false);
        mTestLooper.dispatchAll();
        verify(mA2dpService).setActiveDevice(mSecondaryAudioDevice);

        a2dpActiveDeviceChanged(mA2dpDevice);
        // Don't call mA2dpService.setActiveDevice()
        mTestLooper.dispatchAll();
        verify(mA2dpService).setActiveDevice(mA2dpDevice);
        assertThat(mActiveDeviceManager.getA2dpActiveDevice()).isEqualTo(mA2dpDevice);
    }

    /**
     * Two A2DP devices are connected and the current active is then disconnected. Should then set
     * active device to fallback device.
     */
    @Test
    public void a2dpSecondDeviceDisconnected_fallbackDeviceActive() {
        a2dpConnected(mA2dpDevice, false);
        mTestLooper.dispatchAll();
        verify(mA2dpService).setActiveDevice(mA2dpDevice);

        a2dpConnected(mSecondaryAudioDevice, false);
        mTestLooper.dispatchAll();
        verify(mA2dpService).setActiveDevice(mSecondaryAudioDevice);

        Mockito.clearInvocations(mA2dpService);
        a2dpDisconnected(mSecondaryAudioDevice);
        mTestLooper.dispatchAll();
        verify(mA2dpService).setActiveDevice(mA2dpDevice);
    }

    /** One Headset is connected. */
    @Test
    public void onlyHeadsetConnected_setHeadsetActive() {
        headsetConnected(mHeadsetDevice, false);
        mTestLooper.dispatchAll();
        verify(mHeadsetService).setActiveDevice(mHeadsetDevice);
    }

    /** Two Headset are connected. Should set the second one active. */
    @Test
    public void secondHeadsetConnected_setSecondHeadsetActive() {
        headsetConnected(mHeadsetDevice, false);
        mTestLooper.dispatchAll();
        verify(mHeadsetService).setActiveDevice(mHeadsetDevice);

        headsetConnected(mSecondaryAudioDevice, false);
        mTestLooper.dispatchAll();
        verify(mHeadsetService).setActiveDevice(mSecondaryAudioDevice);
    }

    /** One Headset is connected and disconnected later. Should then set active device to null. */
    @Test
    public void lastHeadsetDisconnected_clearHeadsetActive() {
        headsetConnected(mHeadsetDevice, false);
        mTestLooper.dispatchAll();
        verify(mHeadsetService).setActiveDevice(mHeadsetDevice);

        headsetDisconnected(mHeadsetDevice);
        mTestLooper.dispatchAll();
        verify(mHeadsetService).setActiveDevice(isNull());
    }

    /** Two Headset are connected and active device is explicitly set. */
    @Test
    public void headsetActiveDeviceSelected_setActive() {
        headsetConnected(mHeadsetDevice, false);
        mTestLooper.dispatchAll();
        verify(mHeadsetService).setActiveDevice(mHeadsetDevice);

        headsetConnected(mSecondaryAudioDevice, false);
        mTestLooper.dispatchAll();
        verify(mHeadsetService).setActiveDevice(mSecondaryAudioDevice);

        headsetActiveDeviceChanged(mHeadsetDevice);
        // Don't call mHeadsetService.setActiveDevice()
        mTestLooper.dispatchAll();
        verify(mHeadsetService).setActiveDevice(mHeadsetDevice);
        assertThat(mActiveDeviceManager.getHfpActiveDevice()).isEqualTo(mHeadsetDevice);
    }

    /**
     * Two Headsets are connected and the current active is then disconnected. Should then set
     * active device to fallback device.
     */
    @Test
    public void headsetSecondDeviceDisconnected_fallbackDeviceActive() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_IN_CALL);

        headsetConnected(mHeadsetDevice, false);
        mTestLooper.dispatchAll();
        verify(mHeadsetService).setActiveDevice(mHeadsetDevice);

        headsetConnected(mSecondaryAudioDevice, false);
        mTestLooper.dispatchAll();
        verify(mHeadsetService).setActiveDevice(mSecondaryAudioDevice);

        Mockito.clearInvocations(mHeadsetService);
        headsetDisconnected(mSecondaryAudioDevice);
        mTestLooper.dispatchAll();
        verify(mHeadsetService).setActiveDevice(mHeadsetDevice);
    }

    @Test
    public void headsetRemoveActive_fallbackToLeAudio() {
        when(mHeadsetService.getFallbackDevice()).thenReturn(mHeadsetDevice);
        when(mLeAudioService.getGroupId(mLeAudioDevice)).thenReturn(1);

        InOrder order = inOrder(mLeAudioService);

        leAudioConnected(mLeAudioDevice);
        mTestLooper.dispatchAll();
        order.verify(mLeAudioService, times(1)).setActiveDevice(mLeAudioDevice);

        headsetConnected(mHeadsetDevice, false);
        mTestLooper.dispatchAll();
        verify(mHeadsetService).setActiveDevice(mHeadsetDevice);

        // HFP active device to null. Expect to fallback to LeAudio.
        headsetActiveDeviceChanged(null);
        mTestLooper.dispatchAll();
        verify(mLeAudioService, times(2)).setActiveDevice(mLeAudioDevice);
    }

    @Test
    public void a2dpConnectedButHeadsetNotConnected_setA2dpActive() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_IN_CALL);
        a2dpConnected(mA2dpHeadsetDevice, true);

        mTestLooper.moveTimeForward(ActiveDeviceManager.A2DP_HFP_SYNC_CONNECTION_TIMEOUT_MS / 2);
        mTestLooper.dispatchAll();
        verify(mA2dpService, never()).setActiveDevice(mA2dpHeadsetDevice);
        mTestLooper.moveTimeForward(A2DP_HFP_SYNC_CONNECTION_TIMEOUT_MS);
        mTestLooper.dispatchAll();
        verify(mA2dpService).setActiveDevice(mA2dpHeadsetDevice);
    }

    @Test
    public void headsetConnectedButA2dpNotConnected_setHeadsetActive() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_NORMAL);
        headsetConnected(mA2dpHeadsetDevice, true);

        mTestLooper.moveTimeForward(ActiveDeviceManager.A2DP_HFP_SYNC_CONNECTION_TIMEOUT_MS / 2);
        mTestLooper.dispatchAll();
        verify(mHeadsetService, never()).setActiveDevice(mA2dpHeadsetDevice);
        mTestLooper.moveTimeForward(A2DP_HFP_SYNC_CONNECTION_TIMEOUT_MS);
        mTestLooper.dispatchAll();
        verify(mHeadsetService).setActiveDevice(mA2dpHeadsetDevice);
    }

    @Test
    public void hfpActivatedAfterA2dpActivated_shouldNotActivateA2dpAgain() {
        a2dpConnected(mA2dpHeadsetDevice, true);
        a2dpConnected(mSecondaryAudioDevice, true);
        headsetConnected(mA2dpHeadsetDevice, true);
        headsetConnected(mSecondaryAudioDevice, true);

        mTestLooper.dispatchAll();
        assertThat(mActiveDeviceManager.getA2dpActiveDevice()).isEqualTo(mSecondaryAudioDevice);
        assertThat(mActiveDeviceManager.getHfpActiveDevice()).isEqualTo(mSecondaryAudioDevice);

        Mockito.clearInvocations(mHeadsetService);
        Mockito.clearInvocations(mA2dpService);

        // When A2DP is activated, then it should activate HFP
        a2dpActiveDeviceChanged(mA2dpHeadsetDevice);
        mTestLooper.dispatchAll();
        verify(mHeadsetService).setActiveDevice(mA2dpHeadsetDevice);

        // If HFP activated already, it should not activate A2DP again
        headsetActiveDeviceChanged(mA2dpHeadsetDevice);
        mTestLooper.dispatchAll();
        verify(mA2dpService, never()).setActiveDevice(mA2dpHeadsetDevice);
    }

    @Test
    public void switchActiveDeviceFromLeToHfp_noFallbackToLe() {
        // Turn off the dual mode audio flag
        Utils.setDualModeAudioStateForTesting(false);

        // Connect A2DP + HFP device, set it not active
        a2dpConnected(mA2dpHeadsetDevice, true);
        headsetConnected(mA2dpHeadsetDevice, true);
        a2dpActiveDeviceChanged(null);
        headsetActiveDeviceChanged(null);
        mTestLooper.dispatchAll();

        Mockito.clearInvocations(mHeadsetService);
        Mockito.clearInvocations(mA2dpService);
        Mockito.clearInvocations(mLeAudioService);

        // Connect LE Audio device, set it to inactive
        leAudioConnected(mLeAudioDevice);
        leAudioActiveDeviceChanged(mLeAudioDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice);
        assertThat(mActiveDeviceManager.getLeAudioActiveDevice()).isEqualTo(mLeAudioDevice);

        Mockito.clearInvocations(mHeadsetService);
        Mockito.clearInvocations(mA2dpService);
        Mockito.clearInvocations(mLeAudioService);

        // Set LE Audio device to inactive
        // Set A2DP + HFP device to active
        leAudioActiveDeviceChanged(null);
        headsetActiveDeviceChanged(mA2dpHeadsetDevice);
        mTestLooper.dispatchAll();
        // A2DP + HFP should now be active
        verify(mLeAudioService, never()).setActiveDevice(mLeAudioDevice);
        verify(mA2dpService).setActiveDevice(mA2dpHeadsetDevice);
    }

    @Test
    public void hfpActivatedAfterTimeout_shouldActivateA2dpAgain() {
        a2dpConnected(mA2dpHeadsetDevice, true);
        headsetConnected(mA2dpHeadsetDevice, true);
        a2dpActiveDeviceChanged(null);
        headsetActiveDeviceChanged(null);

        mTestLooper.dispatchAll();
        Mockito.clearInvocations(mHeadsetService);
        Mockito.clearInvocations(mA2dpService);

        // When A2DP is activated, then it should activate HFP
        a2dpActiveDeviceChanged(mA2dpHeadsetDevice);
        mTestLooper.moveTimeForward(A2DP_HFP_SYNC_CONNECTION_TIMEOUT_MS);
        mTestLooper.dispatchAll();
        verify(mA2dpService, never()).setActiveDevice(any());
        verify(mHeadsetService).setActiveDevice(mA2dpHeadsetDevice);

        a2dpActiveDeviceChanged(null);
        // When HFP activated after timeout, it should activate A2DP again
        headsetActiveDeviceChanged(mA2dpHeadsetDevice);
        mTestLooper.dispatchAll();
        verify(mA2dpService).setActiveDevice(mA2dpHeadsetDevice);
    }

    @Test
    public void a2dpHeadsetActivated_whileActivatingAnotherA2dpHeadset() {
        a2dpConnected(mA2dpHeadsetDevice, true);
        a2dpConnected(mSecondaryAudioDevice, true);
        headsetConnected(mA2dpHeadsetDevice, true);
        headsetConnected(mSecondaryAudioDevice, true);

        mTestLooper.dispatchAll();
        assertThat(mActiveDeviceManager.getA2dpActiveDevice()).isEqualTo(mSecondaryAudioDevice);
        assertThat(mActiveDeviceManager.getHfpActiveDevice()).isEqualTo(mSecondaryAudioDevice);

        Mockito.clearInvocations(mHeadsetService);
        Mockito.clearInvocations(mA2dpService);

        // Test HS1 A2DP -> HS2 A2DP -> HS1 HFP -> HS2 HFP
        a2dpActiveDeviceChanged(mA2dpHeadsetDevice);
        mTestLooper.dispatchAll();
        assertThat(mActiveDeviceManager.getA2dpActiveDevice()).isEqualTo(mA2dpHeadsetDevice);
        assertThat(mActiveDeviceManager.getHfpActiveDevice()).isEqualTo(mA2dpHeadsetDevice);
        verify(mHeadsetService).setActiveDevice(mA2dpHeadsetDevice);

        a2dpActiveDeviceChanged(mSecondaryAudioDevice);
        mTestLooper.dispatchAll();
        assertThat(mActiveDeviceManager.getA2dpActiveDevice()).isEqualTo(mSecondaryAudioDevice);
        assertThat(mActiveDeviceManager.getHfpActiveDevice()).isEqualTo(mSecondaryAudioDevice);
        verify(mHeadsetService).setActiveDevice(mSecondaryAudioDevice);

        headsetActiveDeviceChanged(mA2dpHeadsetDevice);
        mTestLooper.dispatchAll();
        assertThat(mActiveDeviceManager.getA2dpActiveDevice()).isEqualTo(mSecondaryAudioDevice);
        assertThat(mActiveDeviceManager.getHfpActiveDevice()).isEqualTo(mA2dpHeadsetDevice);
        verify(mA2dpService, never()).setActiveDevice(any());

        headsetActiveDeviceChanged(mSecondaryAudioDevice);
        mTestLooper.dispatchAll();
        assertThat(mActiveDeviceManager.getA2dpActiveDevice()).isEqualTo(mSecondaryAudioDevice);
        assertThat(mActiveDeviceManager.getHfpActiveDevice()).isEqualTo(mSecondaryAudioDevice);
        verify(mA2dpService, never()).setActiveDevice(any());

        Mockito.clearInvocations(mHeadsetService);
        Mockito.clearInvocations(mA2dpService);

        // Test HS1 HFP -> HS2 HFP -> HS1 A2DP -> HS2 A2DP
        headsetActiveDeviceChanged(mA2dpHeadsetDevice);
        mTestLooper.dispatchAll();
        assertThat(mActiveDeviceManager.getA2dpActiveDevice()).isEqualTo(mA2dpHeadsetDevice);
        assertThat(mActiveDeviceManager.getHfpActiveDevice()).isEqualTo(mA2dpHeadsetDevice);
        verify(mA2dpService).setActiveDevice(mA2dpHeadsetDevice);

        headsetActiveDeviceChanged(mSecondaryAudioDevice);
        mTestLooper.dispatchAll();
        assertThat(mActiveDeviceManager.getA2dpActiveDevice()).isEqualTo(mSecondaryAudioDevice);
        assertThat(mActiveDeviceManager.getHfpActiveDevice()).isEqualTo(mSecondaryAudioDevice);
        verify(mA2dpService).setActiveDevice(mSecondaryAudioDevice);

        a2dpActiveDeviceChanged(mA2dpHeadsetDevice);
        mTestLooper.dispatchAll();
        assertThat(mActiveDeviceManager.getA2dpActiveDevice()).isEqualTo(mA2dpHeadsetDevice);
        assertThat(mActiveDeviceManager.getHfpActiveDevice()).isEqualTo(mSecondaryAudioDevice);
        verify(mHeadsetService, never()).setActiveDevice(any());

        a2dpActiveDeviceChanged(mSecondaryAudioDevice);
        mTestLooper.dispatchAll();
        assertThat(mActiveDeviceManager.getA2dpActiveDevice()).isEqualTo(mSecondaryAudioDevice);
        assertThat(mActiveDeviceManager.getHfpActiveDevice()).isEqualTo(mSecondaryAudioDevice);
        verify(mHeadsetService, never()).setActiveDevice(any());

        Mockito.clearInvocations(mHeadsetService);
        Mockito.clearInvocations(mA2dpService);
    }

    @Test
    public void a2dpHeadsetActivated_checkFallbackMechanismOneA2dpOneHeadset() {
        // Active call
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_IN_CALL);

        // Connect 1st device
        a2dpConnected(mA2dpHeadsetDevice, true);
        headsetConnected(mA2dpHeadsetDevice, true);
        a2dpActiveDeviceChanged(mA2dpHeadsetDevice);
        headsetActiveDeviceChanged(mA2dpHeadsetDevice);
        mTestLooper.dispatchAll();
        verify(mA2dpService).setActiveDevice(mA2dpHeadsetDevice);
        verify(mHeadsetService).setActiveDevice(mA2dpHeadsetDevice);
        assertThat(mActiveDeviceManager.getA2dpActiveDevice()).isEqualTo(mA2dpHeadsetDevice);
        assertThat(mActiveDeviceManager.getHfpActiveDevice()).isEqualTo(mA2dpHeadsetDevice);

        // Disable audio for the first device
        when(mA2dpService.getFallbackDevice()).thenReturn(null);
        when(mA2dpService.removeActiveDevice(anyBoolean())).thenReturn(true);
        when(mHeadsetService.getFallbackDevice()).thenReturn(mA2dpHeadsetDevice);

        doReturn(CONNECTION_POLICY_FORBIDDEN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(mA2dpHeadsetDevice, BluetoothProfile.A2DP);
        a2dpDisconnected(mA2dpHeadsetDevice);
        mTestLooper.dispatchAll();
        verify(mHeadsetService, times(2)).setActiveDevice(mA2dpHeadsetDevice);
        verify(mA2dpService).removeActiveDevice(anyBoolean());

        a2dpActiveDeviceChanged(null);
        mTestLooper.dispatchAll();
        assertThat(mActiveDeviceManager.getA2dpActiveDevice()).isNull();
        assertThat(mActiveDeviceManager.getHfpActiveDevice()).isEqualTo(mA2dpHeadsetDevice);

        // Connect 2nd device
        a2dpConnected(mSecondaryAudioDevice, true);
        headsetConnected(mSecondaryAudioDevice, true);
        mTestLooper.dispatchAll();
        verify(mA2dpService).setActiveDevice(mSecondaryAudioDevice);
        verify(mHeadsetService).setActiveDevice(mSecondaryAudioDevice);

        a2dpActiveDeviceChanged(mSecondaryAudioDevice);
        headsetActiveDeviceChanged(mSecondaryAudioDevice);
        mTestLooper.dispatchAll();
        assertThat(mActiveDeviceManager.getA2dpActiveDevice()).isEqualTo(mSecondaryAudioDevice);
        assertThat(mActiveDeviceManager.getHfpActiveDevice()).isEqualTo(mSecondaryAudioDevice);

        // Disable phone calls for the second device
        when(mA2dpService.getFallbackDevice()).thenReturn(mSecondaryAudioDevice);
        when(mHeadsetService.getFallbackDevice()).thenReturn(mA2dpHeadsetDevice);

        doReturn(CONNECTION_POLICY_FORBIDDEN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(mSecondaryAudioDevice, BluetoothProfile.HEADSET);
        headsetDisconnected(mSecondaryAudioDevice);
        mTestLooper.dispatchAll();
        verify(mHeadsetService, times(3)).setActiveDevice(mA2dpHeadsetDevice);
        verify(mA2dpService, times(2)).setActiveDevice(mSecondaryAudioDevice);
        assertThat(mActiveDeviceManager.getA2dpActiveDevice()).isEqualTo(mSecondaryAudioDevice);
        assertThat(mActiveDeviceManager.getHfpActiveDevice()).isEqualTo(mA2dpHeadsetDevice);
    }

    @Test
    public void hfpActivated_whileActivatingA2dpHeadset() {
        headsetConnected(mHeadsetDevice, false);
        a2dpConnected(mA2dpHeadsetDevice, true);
        headsetConnected(mA2dpHeadsetDevice, true);
        a2dpActiveDeviceChanged(null);
        headsetActiveDeviceChanged(null);

        mTestLooper.dispatchAll();
        Mockito.clearInvocations(mHeadsetService);
        Mockito.clearInvocations(mA2dpService);

        // Test HS1 HFP -> HFP only -> HS1 A2DP
        headsetActiveDeviceChanged(mA2dpHeadsetDevice);
        mTestLooper.dispatchAll();
        assertThat(mActiveDeviceManager.getA2dpActiveDevice()).isEqualTo(mA2dpHeadsetDevice);
        assertThat(mActiveDeviceManager.getHfpActiveDevice()).isEqualTo(mA2dpHeadsetDevice);
        verify(mA2dpService).setActiveDevice(mA2dpHeadsetDevice);

        headsetActiveDeviceChanged(mHeadsetDevice);
        mTestLooper.dispatchAll();
        assertThat(mActiveDeviceManager.getA2dpActiveDevice()).isEqualTo(mA2dpHeadsetDevice);
        assertThat(mActiveDeviceManager.getHfpActiveDevice()).isEqualTo(mHeadsetDevice);
        verify(mA2dpService, never()).setActiveDevice(mHeadsetDevice);

        a2dpActiveDeviceChanged(mA2dpHeadsetDevice);
        mTestLooper.dispatchAll();
        assertThat(mActiveDeviceManager.getA2dpActiveDevice()).isEqualTo(mA2dpHeadsetDevice);
        assertThat(mActiveDeviceManager.getHfpActiveDevice()).isEqualTo(mHeadsetDevice);
        verify(mHeadsetService, never()).setActiveDevice(any());
    }

    @Test
    public void a2dpDeactivated_makeSureToNotRemoveLeAudioDevice() {
        a2dpActiveDeviceChanged(null);
        mTestLooper.dispatchAll();
        verify(mLeAudioService, never()).removeActiveDevice(anyBoolean());
    }

    @Test
    public void hfpDeactivated_makeSureToNotRemoveLeAudioDevice() {
        headsetActiveDeviceChanged(null);
        mTestLooper.dispatchAll();
        verify(mLeAudioService, never()).removeActiveDevice(anyBoolean());
    }

    @Test
    public void a2dpActivated_whileActivatingA2dpHeadset() {
        a2dpConnected(mA2dpDevice, false);
        a2dpConnected(mA2dpHeadsetDevice, true);
        headsetConnected(mA2dpHeadsetDevice, true);
        a2dpActiveDeviceChanged(null);
        headsetActiveDeviceChanged(null);

        mTestLooper.dispatchAll();
        Mockito.clearInvocations(mHeadsetService);
        Mockito.clearInvocations(mA2dpService);

        // Test HS1 HFP -> A2DP only -> HS1 A2DP
        headsetActiveDeviceChanged(mA2dpHeadsetDevice);
        mTestLooper.dispatchAll();
        assertThat(mActiveDeviceManager.getA2dpActiveDevice()).isEqualTo(mA2dpHeadsetDevice);
        assertThat(mActiveDeviceManager.getHfpActiveDevice()).isEqualTo(mA2dpHeadsetDevice);
        verify(mA2dpService).setActiveDevice(mA2dpHeadsetDevice);

        a2dpActiveDeviceChanged(mA2dpDevice);
        mTestLooper.dispatchAll();
        assertThat(mActiveDeviceManager.getA2dpActiveDevice()).isEqualTo(mA2dpDevice);
        assertThat(mActiveDeviceManager.getHfpActiveDevice()).isEqualTo(mA2dpHeadsetDevice);
        verify(mHeadsetService, never()).setActiveDevice(mA2dpDevice);

        a2dpActiveDeviceChanged(mA2dpHeadsetDevice);
        mTestLooper.dispatchAll();
        assertThat(mActiveDeviceManager.getA2dpActiveDevice()).isEqualTo(mA2dpHeadsetDevice);
        assertThat(mActiveDeviceManager.getHfpActiveDevice()).isEqualTo(mA2dpHeadsetDevice);
        verify(mHeadsetService, never()).setActiveDevice(any());
    }

    /** A headset device with connecting audio policy set to NOT ALLOWED. */
    @Test
    public void notAllowedConnectingPolicyHeadsetConnected_noSetActiveDevice() {
        // setting connecting policy to NOT ALLOWED
        when(mHeadsetService.getHfpCallAudioPolicy(mHeadsetDevice))
                .thenReturn(
                        new BluetoothSinkAudioPolicy.Builder()
                                .setCallEstablishPolicy(BluetoothSinkAudioPolicy.POLICY_ALLOWED)
                                .setActiveDevicePolicyAfterConnection(
                                        BluetoothSinkAudioPolicy.POLICY_NOT_ALLOWED)
                                .setInBandRingtonePolicy(BluetoothSinkAudioPolicy.POLICY_ALLOWED)
                                .build());

        headsetConnected(mHeadsetDevice, false);
        mTestLooper.dispatchAll();
        verify(mHeadsetService, never()).setActiveDevice(mHeadsetDevice);
    }

    @Test
    public void twoHearingAidDevicesConnected_WithTheSameHiSyncId() {
        Assume.assumeTrue(
                "Ignore test when HearingAidService is not enabled", HearingAidService.isEnabled());

        when(mHearingAidService.getHiSyncId(mSecondaryAudioDevice))
                .thenReturn(HEARING_AID_HI_SYNC_ID);

        hearingAidConnected(mHearingAidDevice);
        hearingAidConnected(mSecondaryAudioDevice);
        mTestLooper.dispatchAll();
        verify(mHearingAidService).setActiveDevice(mHearingAidDevice);
        verify(mHearingAidService, never()).setActiveDevice(mSecondaryAudioDevice);
    }

    /** A combo (A2DP + Headset) device is connected. Then a Hearing Aid is connected. */
    @Test
    public void hearingAidActive_clearA2dpAndHeadsetActive() {
        a2dpConnected(mA2dpHeadsetDevice, true);
        headsetConnected(mA2dpHeadsetDevice, true);
        mTestLooper.dispatchAll();
        verify(mA2dpService, atLeastOnce()).setActiveDevice(mA2dpHeadsetDevice);
        verify(mHeadsetService, atLeastOnce()).setActiveDevice(mA2dpHeadsetDevice);

        hearingAidActiveDeviceChanged(mHearingAidDevice);
        mTestLooper.dispatchAll();
        verify(mA2dpService).removeActiveDevice(false);
        verify(mHeadsetService).setActiveDevice(null);
    }

    /** A Hearing Aid is connected. Then a combo (A2DP + Headset) device is connected. */
    @Test
    public void hearingAidActive_dontSetA2dpAndHeadsetActive() {
        hearingAidActiveDeviceChanged(mHearingAidDevice);
        mTestLooper.dispatchAll();
        verify(mHeadsetService).setActiveDevice(null);

        a2dpConnected(mA2dpHeadsetDevice, true);
        headsetConnected(mA2dpHeadsetDevice, true);
        mTestLooper.dispatchAll();
        verify(mA2dpService, never()).setActiveDevice(mA2dpHeadsetDevice);
        verify(mHeadsetService, never()).setActiveDevice(mA2dpHeadsetDevice);
    }

    /** A Hearing Aid is connected. Then an A2DP active device is explicitly set. */
    @Test
    public void hearingAidActive_setA2dpActiveExplicitly() {
        when(mHearingAidService.removeActiveDevice(anyBoolean())).thenReturn(true);

        hearingAidActiveDeviceChanged(mHearingAidDevice);
        mTestLooper.dispatchAll();
        verify(mHeadsetService).setActiveDevice(null);

        a2dpConnected(mA2dpDevice, false);
        a2dpActiveDeviceChanged(mA2dpDevice);
        mTestLooper.dispatchAll();
        verify(mHearingAidService).removeActiveDevice(false);
        // Don't call mA2dpService.setActiveDevice()
        verify(mA2dpService, never()).setActiveDevice(mA2dpDevice);
        assertThat(mActiveDeviceManager.getA2dpActiveDevice()).isEqualTo(mA2dpDevice);
        assertThat(mActiveDeviceManager.getHearingAidActiveDevices()).isEmpty();
    }

    /** A Hearing Aid is connected. Then a Headset active device is explicitly set. */
    @Test
    public void hearingAidActive_setHeadsetActiveExplicitly() {
        when(mHearingAidService.removeActiveDevice(anyBoolean())).thenReturn(true);

        hearingAidActiveDeviceChanged(mHearingAidDevice);
        mTestLooper.dispatchAll();
        verify(mHeadsetService).setActiveDevice(null);

        headsetConnected(mHeadsetDevice, false);
        headsetActiveDeviceChanged(mHeadsetDevice);
        mTestLooper.dispatchAll();
        verify(mHearingAidService).removeActiveDevice(false);
        // Don't call mHeadsetService.setActiveDevice()
        verify(mHeadsetService, never()).setActiveDevice(mHeadsetDevice);
        assertThat(mActiveDeviceManager.getHfpActiveDevice()).isEqualTo(mHeadsetDevice);
        assertThat(mActiveDeviceManager.getHearingAidActiveDevices()).isEmpty();
    }

    @Test
    public void hearingAidActiveWithNull_clearHearingAidActiveDevices() {
        hearingAidActiveDeviceChanged(null);
        mTestLooper.dispatchAll();
        assertThat(mActiveDeviceManager.getHearingAidActiveDevices()).isEmpty();
    }

    /** One LE Audio is connected. */
    @Test
    public void onlyLeAudioConnected_setHeadsetActive() {
        leAudioConnected(mLeAudioDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice);
    }

    /** LE Audio is connected but is not ready for stream (no available context types). */
    @Test
    public void leAudioConnected_notReadyForStream() {
        when(mLeAudioService.isGroupAvailableForStream(anyInt())).thenReturn(false);
        leAudioConnected(mLeAudioDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService, never()).setActiveDevice(mLeAudioDevice);
    }

    /**
     * LE Audio is connected but is not ready for stream (no available context types). Check if it's
     * not used as fallback device from A2DP
     */
    @Test
    public void leAudioFallbackA2dpToLeaudio_notReadyForStream() {
        when(mLeAudioService.isGroupAvailableForStream(anyInt())).thenReturn(false);
        leAudioConnected(mLeAudioDevice);
        a2dpConnected(mA2dpDevice, true);
        mTestLooper.dispatchAll();
        verify(mLeAudioService, never()).setActiveDevice(mLeAudioDevice);
        verify(mA2dpService).setActiveDevice(mA2dpDevice);

        a2dpDisconnected(mA2dpDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService, never()).setActiveDevice(mLeAudioDevice);
    }

    /**
     * Two LE Audio are connected and ready to stream. Most recently connected, active device,
     * becomes autonomously inactive (released its ASE). Check if fallback set previous device as
     * active
     */
    @Test
    @EnableFlags(Flags.FLAG_ADM_ITERATE_DEVICES_ON_FALLBACK)
    public void leAudioFallbackLeaudioToLeaudio_autonomousInactive() {
        /* LeAudio device from group 1 - not ready for stream */
        when(mLeAudioService.getGroupId(mLeAudioDevice)).thenReturn(1);
        /* LeAudio device from group 1 - ready for stream */
        when(mLeAudioService.getGroupId(mLeAudioDevice2)).thenReturn(2);
        when(mLeAudioService.isGroupAvailableForStream(1)).thenReturn(true);
        when(mLeAudioService.isGroupAvailableForStream(2)).thenReturn(true);
        leAudioConnected(mLeAudioDevice);
        leAudioConnected(mLeAudioDevice2);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice);
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice2);

        /* Active device autonomously inactivates */
        mActiveDeviceManager.profileActiveDeviceChanged(BluetoothProfile.LE_AUDIO, null);
        Mockito.clearInvocations(mLeAudioService);
        /* LeAudio device from group 1 - not ready for stream */
        when(mLeAudioService.getGroupId(mLeAudioDevice)).thenReturn(1);
        /* LeAudio device from group 1 - ready for stream */
        when(mLeAudioService.getGroupId(mLeAudioDevice2)).thenReturn(2);
        when(mLeAudioService.isGroupAvailableForStream(1)).thenReturn(true);
        when(mLeAudioService.isGroupAvailableForStream(2)).thenReturn(true);

        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice);
    }

    /**
     * LE Audio is connected but is not ready for stream (no available context types). Check if it's
     * not used as fallback device from LE Audio
     */
    @Test
    public void leAudioFallbackLeaudioToLeaudio_notReadyForStream() {
        /* LeAudio device from group 1 - not ready for stream */
        when(mLeAudioService.getGroupId(mLeAudioDevice)).thenReturn(1);
        /* LeAudio device from group 1 - ready for stream */
        when(mLeAudioService.getGroupId(mLeAudioDevice2)).thenReturn(2);
        when(mLeAudioService.isGroupAvailableForStream(1)).thenReturn(false);
        when(mLeAudioService.isGroupAvailableForStream(2)).thenReturn(true);
        leAudioConnected(mLeAudioDevice);
        leAudioConnected(mLeAudioDevice2);
        mTestLooper.dispatchAll();
        verify(mLeAudioService, never()).setActiveDevice(mLeAudioDevice);
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice2);

        leAudioDisconnected(mLeAudioDevice2);
        mTestLooper.dispatchAll();
        verify(mLeAudioService, never()).setActiveDevice(mLeAudioDevice);
    }

    /**
     * LE Audio is connected but is not ready for stream (no available context types). Check if it's
     * not used as fallback device from ASHA
     */
    @Test
    public void leAudioFallbackAshaToLeaudio_notReadyForStream() {
        when(mLeAudioService.isGroupAvailableForStream(anyInt())).thenReturn(false);

        leAudioConnected(mLeAudioDevice);
        hearingAidConnected(mHearingAidDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService, never()).setActiveDevice(mLeAudioDevice);
        verify(mHearingAidService).setActiveDevice(mHearingAidDevice);

        hearingAidDisconnected(mHearingAidDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService, never()).setActiveDevice(mLeAudioDevice);
    }

    /** Two LE Audio are connected. Should set the second one active. */
    @Test
    public void secondLeAudioConnected_setSecondLeAudioActive() {
        leAudioConnected(mLeAudioDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice);

        leAudioConnected(mLeAudioDevice2);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice2);
    }

    /** One LE Audio is connected and disconnected later. Should then set active device to null. */
    @Test
    public void lastLeAudioDisconnected_clearLeAudioActive() {
        when(mLeAudioService.getGroupId(mLeAudioDevice)).thenReturn(1);
        when(mLeAudioService.getLeadDevice(mLeAudioDevice)).thenReturn(mLeAudioDevice);

        leAudioConnected(mLeAudioDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice);

        leAudioDisconnected(mLeAudioDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService, never()).removeActiveDevice(anyBoolean());
        verify(mLeAudioService).deviceDisconnected(mLeAudioDevice, false);
    }

    /** Two LE Audio are connected and active device is explicitly set. */
    @Test
    public void leAudioActiveDeviceSelected_setActive() {
        leAudioConnected(mLeAudioDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice);

        leAudioConnected(mLeAudioDevice2);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice2);

        Mockito.clearInvocations(mLeAudioService);
        leAudioActiveDeviceChanged(mLeAudioDevice);
        // Don't call mLeAudioService.setActiveDevice()
        mTestLooper.dispatchAll();
        verify(mLeAudioService, never()).setActiveDevice(any(BluetoothDevice.class));
        assertThat(mActiveDeviceManager.getLeAudioActiveDevice()).isEqualTo(mLeAudioDevice);
    }

    /**
     * Two LE Audio Sets are connected and the current active Set is disconnected. The other
     * connected LeAudio Set shall become an active device.
     */
    @Test
    public void leAudioSecondDeviceDisconnected_fallbackDeviceActive() {
        when(mLeAudioService.getGroupId(mLeAudioDevice)).thenReturn(1);
        when(mLeAudioService.getGroupId(mLeAudioDevice2)).thenReturn(2);
        when(mLeAudioService.getLeadDevice(mLeAudioDevice)).thenReturn(mLeAudioDevice);
        when(mLeAudioService.getLeadDevice(mLeAudioDevice2)).thenReturn(mLeAudioDevice2);

        leAudioConnected(mLeAudioDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice);

        leAudioConnected(mLeAudioDevice2);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice2);

        Mockito.clearInvocations(mLeAudioService);
        leAudioDisconnected(mLeAudioDevice2);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice);
    }

    /**
     * There are two LE Audio Sets: A and B. First device from set A is connected and set to active.
     * Then, both devices from set B are connected - First device from B is lead, and set to active.
     * Finally, second device from set A connects - setActiveDevice should not be called, and device
     * from set B should remain active.
     */
    @Test
    public void leAudioSecondDeviceConnectedAfterOtherGroupConnected_setActive() {
        when(mLeAudioService.getGroupId(mLeAudioDevice)).thenReturn(1);
        when(mLeAudioService.getGroupId(mLeAudioDevice2)).thenReturn(1);
        when(mLeAudioService.getGroupId(mLeAudioDevice3)).thenReturn(2);
        when(mLeAudioService.getGroupId(mLeAudioDevice4)).thenReturn(2);
        when(mLeAudioService.getLeadDevice(mLeAudioDevice)).thenReturn(mLeAudioDevice);
        when(mLeAudioService.getLeadDevice(mLeAudioDevice2)).thenReturn(mLeAudioDevice);
        when(mLeAudioService.getLeadDevice(mLeAudioDevice3)).thenReturn(mLeAudioDevice3);
        when(mLeAudioService.getLeadDevice(mLeAudioDevice4)).thenReturn(mLeAudioDevice3);

        leAudioConnected(mLeAudioDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice);

        leAudioConnected(mLeAudioDevice3);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice3);

        leAudioConnected(mLeAudioDevice4);
        mTestLooper.dispatchAll();
        verify(mLeAudioService, never()).setActiveDevice(mLeAudioDevice4);

        leAudioConnected(mLeAudioDevice2);
        mTestLooper.dispatchAll();
        verify(mLeAudioService, never()).setActiveDevice(mLeAudioDevice2);
    }

    /**
     * One LE Audio set, containing two buds, is connected. When one device got disconnected
     * fallback device should not be set to true active device to fallback device.
     */
    @Test
    public void leAudioSecondDeviceDisconnected_noFallbackDeviceActive_ModeNormal() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_NORMAL);

        InOrder order = inOrder(mLeAudioService);

        int groupId = 1;
        List<BluetoothDevice> groupDevices = List.of(mLeAudioDevice, mLeAudioDevice2);
        when(mLeAudioService.getLeadDevice(mLeAudioDevice2)).thenReturn(mLeAudioDevice);

        leAudioConnected(mLeAudioDevice);
        mTestLooper.dispatchAll();
        order.verify(mLeAudioService, times(1)).setActiveDevice(mLeAudioDevice);

        leAudioConnected(mLeAudioDevice2);
        mTestLooper.dispatchAll();
        order.verify(mLeAudioService, never()).setActiveDevice(any());

        when(mLeAudioService.getGroupId(any())).thenReturn(groupId);
        when(mLeAudioService.getGroupDevices(groupId)).thenReturn(groupDevices);

        leAudioDisconnected(mLeAudioDevice2);
        mTestLooper.dispatchAll();
        order.verify(mLeAudioService, never()).setActiveDevice(any());
    }

    /**
     * One LE Audio set, containing two buds, is connected. When one device got disconnected
     * fallback device should not be set to true active device to fallback device.
     */
    @Test
    public void leAudioSecondDeviceDisconnected_noFallbackDeviceActive_ModeInCall() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_IN_CALL);

        InOrder order = inOrder(mLeAudioService);

        int groupId = 1;
        List<BluetoothDevice> groupDevices = List.of(mLeAudioDevice, mLeAudioDevice2);
        when(mLeAudioService.getLeadDevice(mLeAudioDevice2)).thenReturn(mLeAudioDevice);

        leAudioConnected(mLeAudioDevice);
        mTestLooper.dispatchAll();
        order.verify(mLeAudioService, times(1)).setActiveDevice(mLeAudioDevice);

        leAudioConnected(mLeAudioDevice2);
        mTestLooper.dispatchAll();
        order.verify(mLeAudioService, never()).setActiveDevice(any());

        when(mLeAudioService.getGroupId(any())).thenReturn(groupId);
        when(mLeAudioService.getGroupDevices(groupId)).thenReturn(groupDevices);

        leAudioDisconnected(mLeAudioDevice2);
        mTestLooper.dispatchAll();
        order.verify(mLeAudioService, never()).setActiveDevice(any());
    }

    /**
     * One LE Audio set, containing two buds, is connected. When one device got disconnected
     * fallback device should not be set to true active device to fallback device.
     */
    @Test
    public void twoLeAudioSets_OneSetDisconnected_FallbackToAnotherOne_ModeNormal() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_NORMAL);

        InOrder order = inOrder(mLeAudioService);

        int groupId = 1;
        List<BluetoothDevice> groupDevices = List.of(mLeAudioDevice, mLeAudioDevice2);

        when(mLeAudioService.getLeadDevice(mLeAudioDevice2)).thenReturn(mLeAudioDevice);
        when(mLeAudioService.getGroupId(mLeAudioDevice)).thenReturn(groupId);
        when(mLeAudioService.getGroupId(mLeAudioDevice2)).thenReturn(groupId);
        when(mLeAudioService.getGroupDevices(groupId)).thenReturn(groupDevices);

        int groupId2 = 2;
        List<BluetoothDevice> groupDevicesId2 = List.of(mLeAudioDevice3);

        when(mLeAudioService.getGroupId(mLeAudioDevice3)).thenReturn(groupId2);
        when(mLeAudioService.getGroupDevices(groupId2)).thenReturn(groupDevicesId2);

        leAudioConnected(mLeAudioDevice3);
        mTestLooper.dispatchAll();
        order.verify(mLeAudioService, times(1)).setActiveDevice(mLeAudioDevice3);

        leAudioConnected(mLeAudioDevice);
        mTestLooper.dispatchAll();
        order.verify(mLeAudioService).setActiveDevice(mLeAudioDevice);

        leAudioConnected(mLeAudioDevice2);
        mTestLooper.dispatchAll();
        order.verify(mLeAudioService, never()).setActiveDevice(mLeAudioDevice2);

        leAudioDisconnected(mLeAudioDevice2);
        mTestLooper.dispatchAll();
        // Should not increase a number of this call.
        order.verify(mLeAudioService, never()).setActiveDevice(any());

        leAudioDisconnected(mLeAudioDevice);
        mTestLooper.dispatchAll();
        order.verify(mLeAudioService, times(1)).setActiveDevice(mLeAudioDevice3);
    }

    /**
     * One LE Audio set, containing two buds, is connected. When one device got disconnected
     * fallback device should not be set to true active device to fallback device.
     */
    @Test
    public void twoLeAudioSets_OneSetDisconnected_FallbackToAnotherOne_ModeInCall() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_IN_CALL);

        InOrder order = inOrder(mLeAudioService);

        int groupId = 1;
        List<BluetoothDevice> groupDevices = List.of(mLeAudioDevice, mLeAudioDevice2);

        when(mLeAudioService.getLeadDevice(mLeAudioDevice2)).thenReturn(mLeAudioDevice);
        when(mLeAudioService.getGroupId(mLeAudioDevice)).thenReturn(groupId);
        when(mLeAudioService.getGroupId(mLeAudioDevice2)).thenReturn(groupId);
        when(mLeAudioService.getGroupDevices(groupId)).thenReturn(groupDevices);

        int groupId2 = 2;
        List<BluetoothDevice> groupDevicesId2 = List.of(mLeAudioDevice3);

        when(mLeAudioService.getGroupId(mLeAudioDevice3)).thenReturn(groupId2);
        when(mLeAudioService.getGroupDevices(groupId2)).thenReturn(groupDevicesId2);

        leAudioConnected(mLeAudioDevice3);
        mTestLooper.dispatchAll();
        order.verify(mLeAudioService).setActiveDevice(mLeAudioDevice3);

        leAudioConnected(mLeAudioDevice);
        mTestLooper.dispatchAll();
        order.verify(mLeAudioService).setActiveDevice(mLeAudioDevice);

        leAudioConnected(mLeAudioDevice2);
        mTestLooper.dispatchAll();
        order.verify(mLeAudioService, never()).setActiveDevice(any());

        leAudioDisconnected(mLeAudioDevice2);
        mTestLooper.dispatchAll();
        order.verify(mLeAudioService, never()).setActiveDevice(any());

        leAudioDisconnected(mLeAudioDevice);
        mTestLooper.dispatchAll();
        order.verify(mLeAudioService, times(1)).setActiveDevice(mLeAudioDevice3);
    }

    /** A combo (A2DP + Headset) device is connected. Then an LE Audio is connected. */
    @Test
    public void leAudioActive_clearA2dpAndHeadsetActive() {
        a2dpConnected(mA2dpHeadsetDevice, true);
        headsetConnected(mA2dpHeadsetDevice, true);
        mTestLooper.dispatchAll();
        verify(mA2dpService, atLeastOnce()).setActiveDevice(mA2dpHeadsetDevice);
        verify(mHeadsetService, atLeastOnce()).setActiveDevice(mA2dpHeadsetDevice);

        leAudioConnected(mLeAudioDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice);
        verify(mA2dpService).removeActiveDevice(false);
        verify(mHeadsetService).setActiveDevice(isNull());
    }

    /** An LE Audio is connected. Then a combo (A2DP + Headset) device is connected. */
    @Test
    public void leAudioActive_setA2dpAndHeadsetActive() {
        leAudioConnected(mLeAudioDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice);
        verify(mHeadsetService).setActiveDevice(null);

        a2dpConnected(mA2dpHeadsetDevice, true);
        headsetConnected(mA2dpHeadsetDevice, true);
        mTestLooper.dispatchAll();
        verify(mA2dpService, atLeastOnce()).setActiveDevice(mA2dpHeadsetDevice);
        verify(mHeadsetService, atLeastOnce()).setActiveDevice(mA2dpHeadsetDevice);
    }

    /** An LE Audio is connected. Then an A2DP active device is explicitly set. */
    @Test
    public void leAudioActive_setA2dpActiveExplicitly() {
        leAudioConnected(mLeAudioDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice);
        verify(mHeadsetService).setActiveDevice(null);

        a2dpConnected(mA2dpDevice, false);
        a2dpActiveDeviceChanged(mA2dpDevice);

        mTestLooper.dispatchAll();
        verify(mLeAudioService).removeActiveDevice(true);
        verify(mA2dpService).setActiveDevice(mA2dpDevice);
        assertThat(mActiveDeviceManager.getA2dpActiveDevice()).isEqualTo(mA2dpDevice);
        assertThat(mActiveDeviceManager.getLeAudioActiveDevice()).isNull();
    }

    /** An LE Audio is connected. Then a Headset active device is explicitly set. */
    @Test
    public void leAudioActive_setHeadsetActiveExplicitly() {
        leAudioConnected(mLeAudioDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice);
        verify(mHeadsetService).setActiveDevice(null);

        headsetConnected(mHeadsetDevice, false);
        headsetActiveDeviceChanged(mHeadsetDevice);

        mTestLooper.dispatchAll();
        verify(mLeAudioService).removeActiveDevice(true);
        verify(mHeadsetService).setActiveDevice(mHeadsetDevice);
        assertThat(mActiveDeviceManager.getHfpActiveDevice()).isEqualTo(mHeadsetDevice);
        assertThat(mActiveDeviceManager.getLeAudioActiveDevice()).isNull();
    }

    /**
     * An LE Audio connected. An A2DP connected. The A2DP disconnected. Then the LE Audio should be
     * the active one.
     */
    @Test
    public void leAudioAndA2dpConnectedThenA2dpDisconnected_fallbackToLeAudio() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_NORMAL);
        when(mLeAudioService.getGroupId(mLeAudioDevice)).thenReturn(1);

        leAudioConnected(mLeAudioDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice);

        a2dpConnected(mA2dpDevice, false);
        mTestLooper.dispatchAll();
        verify(mA2dpService).setActiveDevice(mA2dpDevice);

        Mockito.clearInvocations(mLeAudioService);
        a2dpDisconnected(mA2dpDevice);
        mTestLooper.dispatchAll();
        verify(mA2dpService, atLeastOnce()).removeActiveDevice(false);
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice);
    }

    /**
     * An LE Audio set connected. The not active bud disconnected. Then the active device should not
     * change and hasFallback should be set to false.
     */
    @Test
    public void leAudioSetConnectedThenNotActiveOneDisconnected_noFallback() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_NORMAL);

        leAudioConnected(mLeAudioDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice);

        leAudioConnected(mLeAudioDevice2);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice2);

        Mockito.clearInvocations(mLeAudioService);

        leAudioDisconnected(mLeAudioDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService, never()).removeActiveDevice(false);
        verify(mLeAudioService, never()).setActiveDevice(mLeAudioDevice2);
        verify(mLeAudioService).deviceDisconnected(mLeAudioDevice, false);
    }

    /**
     * An LE Audio set connected. The active bud disconnected. Active device manager should not
     * choose other set member as active device.
     */
    @Test
    public void leAudioSetConnectedThenActiveOneDisconnected_noFallback() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_NORMAL);
        when(mLeAudioService.getLeadDevice(any())).thenReturn(mLeAudioDevice);

        leAudioConnected(mLeAudioDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice);

        leAudioConnected(mLeAudioDevice2);
        mTestLooper.dispatchAll();
        verify(mLeAudioService, never()).setActiveDevice(mLeAudioDevice2);

        leAudioDisconnected(mLeAudioDevice2);
        mTestLooper.dispatchAll();
        verify(mLeAudioService, never()).removeActiveDevice(anyBoolean());
        verify(mLeAudioService).deviceDisconnected(mLeAudioDevice2, false);
    }

    @Test
    public void leAudioSetConnectedGroupThenDisconnected_noFallback() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_NORMAL);

        when(mLeAudioService.getGroupId(mLeAudioDevice)).thenReturn(1);
        when(mLeAudioService.getGroupId(mLeAudioDevice2)).thenReturn(1);
        when(mLeAudioService.getLeadDevice(mLeAudioDevice2)).thenReturn(mLeAudioDevice);
        when(mLeAudioService.getLeadDevice(mLeAudioDevice)).thenReturn(mLeAudioDevice);

        InOrder order = inOrder(mLeAudioService);

        leAudioConnected(mLeAudioDevice);
        mTestLooper.dispatchAll();
        order.verify(mLeAudioService).setActiveDevice(mLeAudioDevice);

        leAudioConnected(mLeAudioDevice2);
        mTestLooper.dispatchAll();
        order.verify(mLeAudioService, never()).setActiveDevice(any());

        leAudioDisconnected(mLeAudioDevice2);
        mTestLooper.dispatchAll();
        order.verify(mLeAudioService, never()).setActiveDevice(any());
        order.verify(mLeAudioService, never()).removeActiveDevice(anyBoolean());
        order.verify(mLeAudioService).deviceDisconnected(mLeAudioDevice2, false);

        leAudioDisconnected(mLeAudioDevice);
        mTestLooper.dispatchAll();
        order.verify(mLeAudioService, never()).removeActiveDevice(anyBoolean());
        order.verify(mLeAudioService).deviceDisconnected(mLeAudioDevice, false);
    }

    /**
     * An A2DP connected. An LE Audio connected. The LE Audio disconnected. Then the A2DP should be
     * the active one.
     */
    @Test
    public void a2dpAndLeAudioConnectedThenLeAudioDisconnected_fallbackToA2dp() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_NORMAL);

        a2dpConnected(mA2dpDevice, false);
        mTestLooper.dispatchAll();
        verify(mA2dpService).setActiveDevice(mA2dpDevice);

        leAudioConnected(mLeAudioDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice);

        Mockito.clearInvocations(mA2dpService);
        leAudioDisconnected(mLeAudioDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService, atLeastOnce()).removeActiveDevice(true);
        verify(mA2dpService).setActiveDevice(mA2dpDevice);
    }

    /**
     * An ASHA device connected and set to active. Same device connected as a LE Audio device. ASHA
     * disconnects with no fallback and LE Audio is set to active. New LE Audio device is connected
     * and selected as active. First LE Audio device disconnects with fallback to new one.
     */
    @Test
    public void sameDeviceAsAshaAndLeAudio_noFallbackOnSwitch() {
        /* Dual mode ASHA/LeAudio device from group 1 */
        when(mLeAudioService.getGroupId(mDualModeHearingAidDevice)).thenReturn(1);
        /* Different LeAudio only device from group 2 */
        when(mLeAudioService.getGroupId(mLeAudioDevice)).thenReturn(2);

        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_NORMAL);

        /* Connect first device as ASHA */
        hearingAidConnected(mDualModeHearingAidDevice);
        mTestLooper.dispatchAll();
        verify(mHearingAidService).setActiveDevice(mDualModeHearingAidDevice);

        /* Disconnect ASHA and connect first device as LE Audio */
        hearingAidDisconnected(mDualModeHearingAidDevice);
        mTestLooper.dispatchAll();
        verify(mHearingAidService).removeActiveDevice(true /* stop audio */);
        leAudioConnected(mDualModeHearingAidDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mDualModeHearingAidDevice);

        /* Connect second device as LE Audio. First device is disconnected with fallback to
         * new one.
         */
        leAudioConnected(mLeAudioDevice);
        leAudioDisconnected(mDualModeHearingAidDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).removeActiveDevice(true);
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice);
    }

    /**
     * A LE Audio device connected and set to active. Same device connected as an ASHA device. LE
     * Audio disconnects with no fallback and ASHA is set to active. New ASHA device is connected
     * and selected as active. First ASHA device disconnects with fallback to new one.
     */
    @Test
    public void sameDeviceAsLeAudioAndAsha_noFallbackOnSwitch() {
        // Turn on the dual mode audio flag so the A2DP won't disconnect LE Audio
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_NORMAL);
        List<BluetoothDevice> list = new ArrayList<>();
        when(mLeAudioService.getActiveDevices()).thenReturn(list);

        /* Connect first device as LE Audio */
        leAudioConnected(mDualModeHearingAidDevice);
        list.add(mDualModeHearingAidDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mDualModeHearingAidDevice);

        /* Connect first device as ASHA */
        hearingAidConnected(mDualModeHearingAidDevice);
        leAudioDisconnected(mDualModeHearingAidDevice);
        mTestLooper.dispatchAll();
        verify(mHearingAidService).setActiveDevice(mDualModeHearingAidDevice);
        verify(mLeAudioService).removeActiveDevice(false);

        /* Connect second device as ASHA. It is set as fallback device for LE Audio Service
         */
        hearingAidConnected(mSecondaryAudioDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).removeActiveDevice(true);
        verify(mHearingAidService).setActiveDevice(mSecondaryAudioDevice);
    }

    /**
     * Dual mode device is active. New A2DP device connects. A2DP device is set as active. LE Audio
     * device is set as inactive.
     */
    @Test
    public void dualModeDeviceActive_newA2dpDeviceConnected() {
        /* Turn on the dual mode audio flag */
        Utils.setDualModeAudioStateForTesting(true);
        /* A2DP device connected and set as active*/
        a2dpConnected(mA2dpDevice, false);
        a2dpActiveDeviceChanged(mA2dpDevice);

        reset(mLeAudioService);
        when(mLeAudioService.getLeadDevice(mDualModeAudioDevice)).thenReturn(mDualModeAudioDevice);
        when(mLeAudioService.isGroupAvailableForStream(anyInt())).thenReturn(true);

        when(mAdapterService.isAllSupportedClassicAudioProfilesActive(mDualModeAudioDevice))
                .thenReturn(false);

        /* LE Audio is the active device */
        leAudioConnected(mDualModeAudioDevice);
        mTestLooper.dispatchAll();

        verify(mA2dpService).setActiveDevice(mA2dpDevice);
        verify(mLeAudioService).setActiveDevice(mDualModeAudioDevice);

        Mockito.clearInvocations(mLeAudioService);
        Mockito.clearInvocations(mA2dpDevice);

        /* A2DP is set as active device. Check if LE Audio device is set as inactive */
        a2dpActiveDeviceChanged(mA2dpDevice);
        mTestLooper.dispatchAll();
        verify(mA2dpService).setActiveDevice(mA2dpDevice);
        verify(mLeAudioService).removeActiveDevice(true);
    }

    /**
     * Dual mode device is active. New HFP device connects. HFP device is set as active. LE Audio
     * device is set as inactive.
     */
    @Test
    public void dualModeDeviceActive_newHfpDeviceConnected() {
        /* Turn on the dual mode audio flag */
        Utils.setDualModeAudioStateForTesting(true);
        /* HFP device connected and set as active*/
        headsetConnected(mHeadsetDevice, false);
        mTestLooper.dispatchAll();
        verify(mHeadsetService).setActiveDevice(mHeadsetDevice);

        reset(mLeAudioService);
        when(mLeAudioService.getLeadDevice(mDualModeAudioDevice)).thenReturn(mDualModeAudioDevice);
        when(mLeAudioService.isGroupAvailableForStream(anyInt())).thenReturn(true);

        when(mAdapterService.isAllSupportedClassicAudioProfilesActive(mDualModeAudioDevice))
                .thenReturn(false);

        /* LE Audio is the active device */
        leAudioConnected(mDualModeAudioDevice);
        mTestLooper.dispatchAll();

        verify(mHeadsetService).setActiveDevice(mHeadsetDevice);
        verify(mLeAudioService).setActiveDevice(mDualModeAudioDevice);

        Mockito.clearInvocations(mLeAudioService);
        Mockito.clearInvocations(mHeadsetDevice);

        /* HFP is set as active device. Check if LE Audio device is set as inactive */
        headsetActiveDeviceChanged(mHeadsetDevice);
        mTestLooper.dispatchAll();
        verify(mHeadsetService).setActiveDevice(mHeadsetDevice);
        verify(mLeAudioService).removeActiveDevice(true);
    }

    /**
     * Two Hearing Aid are connected and the current active is then disconnected. Should then set
     * active device to fallback device.
     */
    @Test
    public void hearingAidSecondDeviceDisconnected_fallbackDeviceActive() {
        hearingAidConnected(mHearingAidDevice);
        mTestLooper.dispatchAll();
        verify(mHearingAidService).setActiveDevice(mHearingAidDevice);

        List<BluetoothDevice> connectedHearingAidDevices = new ArrayList<>();
        connectedHearingAidDevices.add(mSecondaryAudioDevice);
        when(mHearingAidService.getHiSyncId(mSecondaryAudioDevice))
                .thenReturn(HEARING_AID_HI_SYNC_ID + 1);
        when(mHearingAidService.getConnectedPeerDevices(HEARING_AID_HI_SYNC_ID + 1))
                .thenReturn(connectedHearingAidDevices);

        hearingAidConnected(mSecondaryAudioDevice);
        mTestLooper.dispatchAll();
        verify(mHearingAidService).setActiveDevice(mSecondaryAudioDevice);

        Mockito.clearInvocations(mHearingAidService);
        hearingAidDisconnected(mSecondaryAudioDevice);
        mTestLooper.dispatchAll();
        verify(mHearingAidService).setActiveDevice(mHearingAidDevice);
    }

    /**
     * Hearing aid is connected, but active device is different BT. When the active device is
     * disconnected, the hearing aid should be the active one.
     */
    @Test
    public void activeDeviceDisconnected_fallbackToHearingAid() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_NORMAL);
        when(mHearingAidService.removeActiveDevice(anyBoolean())).thenReturn(true);

        hearingAidConnected(mHearingAidDevice);
        mTestLooper.dispatchAll();
        verify(mHearingAidService).setActiveDevice(mHearingAidDevice);

        leAudioConnected(mLeAudioDevice);
        a2dpConnected(mA2dpDevice, false);
        a2dpActiveDeviceChanged(mA2dpDevice);
        mTestLooper.dispatchAll();
        verify(mHearingAidService).removeActiveDevice(false);
        verify(mLeAudioService, never()).setActiveDevice(mLeAudioDevice);
        verify(mA2dpService, never()).setActiveDevice(mA2dpDevice);

        a2dpDisconnected(mA2dpDevice);
        mTestLooper.dispatchAll();
        verify(mA2dpService, atLeastOnce()).removeActiveDevice(false);
        verify(mHearingAidService, times(2)).setActiveDevice(mHearingAidDevice);
    }

    /** One LE Hearing Aid is connected. */
    @Test
    public void onlyLeHearingAidConnected_setLeAudioActive() {
        leHearingAidConnected(mLeHearingAidDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService, never()).setActiveDevice(mLeHearingAidDevice);

        leAudioConnected(mLeHearingAidDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mLeHearingAidDevice);
    }

    /** LE audio is connected after LE Hearing Aid device. Keep LE hearing Aid active. */
    @Test
    public void leAudioConnectedAfterLeHearingAid_setLeAudioActiveShouldNotBeCalled() {
        leHearingAidConnected(mLeHearingAidDevice);
        leAudioConnected(mLeHearingAidDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mLeHearingAidDevice);

        leAudioConnected(mLeAudioDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService, never()).setActiveDevice(mLeAudioDevice);
    }

    /**
     * Test connect/disconnect of devices. Hearing Aid, LE Hearing Aid, A2DP connected, then LE
     * hearing Aid and hearing aid disconnected.
     */
    @Test
    public void activeDeviceChange_withHearingAidLeHearingAidAndA2dpDevices() {
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_NORMAL);
        when(mHearingAidService.removeActiveDevice(anyBoolean())).thenReturn(true);

        hearingAidConnected(mHearingAidDevice);
        mTestLooper.dispatchAll();
        verify(mHearingAidService).setActiveDevice(mHearingAidDevice);

        leHearingAidConnected(mLeHearingAidDevice);
        leAudioConnected(mLeHearingAidDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mLeHearingAidDevice);

        a2dpConnected(mA2dpDevice, false);
        mTestLooper.dispatchAll();
        verify(mA2dpService, never()).setActiveDevice(mA2dpDevice);

        Mockito.clearInvocations(mHearingAidService, mA2dpService);
        leHearingAidDisconnected(mLeHearingAidDevice);
        leAudioDisconnected(mLeHearingAidDevice);
        mTestLooper.dispatchAll();
        verify(mHearingAidService).setActiveDevice(mHearingAidDevice);
        verify(mA2dpService).removeActiveDevice(false);

        hearingAidDisconnected(mHearingAidDevice);
        mTestLooper.dispatchAll();
        verify(mA2dpService).setActiveDevice(mA2dpDevice);
    }

    @Test
    @EnableFlags(Flags.FLAG_ADM_SUSPEND_FALLBACK_DURING_CHANGE)
    public void fallbackNotTriggeredWhenDevicePendingActive() {
        // Three devices connected: LE Audio active, ASHA as fallback and A2DP
        hearingAidConnected(mHearingAidDevice);
        leAudioConnected(mLeAudioDevice);
        a2dpConnected(mA2dpDevice, false);
        hearingAidActiveDeviceChanged(null);
        a2dpActiveDeviceChanged(null);
        leAudioActiveDeviceChanged(mLeAudioDevice);
        mTestLooper.dispatchAll();
        assertThat(mActiveDeviceManager.getLeAudioActiveDevice()).isEqualTo(mLeAudioDevice);
        Mockito.clearInvocations(mLeAudioService);
        Mockito.clearInvocations(mHearingAidService);
        Mockito.clearInvocations(mA2dpService);

        when(mLeAudioService.getActiveDevices()).thenReturn(List.of(mLeAudioDevice));

        // Set A2DP device as active.
        mActiveDeviceManager.setActiveDevice(mA2dpDevice, BluetoothAdapter.ACTIVE_DEVICE_ALL);

        // Simulate LE Audio device disconnecting.
        leAudioDisconnected(mLeAudioDevice);
        mTestLooper.dispatchAll();

        // Fallback should be prevented because mA2dpDevice is pending to be active.
        // So, no other device should become active for LE audio.
        verify(mLeAudioService, never()).setActiveDevice(any());
        verify(mHearingAidService, never()).setActiveDevice(any());
        // In handleLeAudioDisconnected -> deviceDisconnected() hasFallbackDevice is false.
        verify(mLeAudioService).deviceDisconnected(mLeAudioDevice, false);

        // Now, let the A2DP active device change happen.
        a2dpActiveDeviceChanged(mA2dpDevice);
        mTestLooper.dispatchAll();
        assertThat(mActiveDeviceManager.getA2dpActiveDevice()).isEqualTo(mA2dpDevice);
    }

    @Test
    @EnableFlags(Flags.FLAG_ADM_SUSPEND_FALLBACK_DURING_CHANGE)
    public void fallbackAllowedWhenPendingDeviceDisconnects() {
        // Three devices connected: LE Audio active, ASHA as fallback and A2DP
        hearingAidConnected(mHearingAidDevice);
        leAudioConnected(mLeAudioDevice);
        a2dpConnected(mA2dpDevice, false);
        hearingAidActiveDeviceChanged(null);
        a2dpActiveDeviceChanged(null);

        // set LE Audio as active device
        leAudioActiveDeviceChanged(mLeAudioDevice);
        mTestLooper.dispatchAll();
        assertThat(mActiveDeviceManager.getLeAudioActiveDevice()).isEqualTo(mLeAudioDevice);
        Mockito.clearInvocations(mLeAudioService);
        Mockito.clearInvocations(mHearingAidService);
        Mockito.clearInvocations(mA2dpService);

        when(mLeAudioService.getActiveDevices()).thenReturn(List.of(mLeAudioDevice));

        // Set A2DP device as active.
        mActiveDeviceManager.setActiveDevice(mA2dpDevice, BluetoothAdapter.ACTIVE_DEVICE_ALL);

        Mockito.clearInvocations(mA2dpService);

        // A2DP disconnects before becomes active
        a2dpDisconnected(mA2dpDevice);
        // LE Audio device (current active) disconnects
        leAudioDisconnected(mLeAudioDevice);

        mTestLooper.dispatchAll();

        // Fall back to ASHA successful
        verify(mA2dpService, never()).setActiveDevice(any());
        verify(mLeAudioService, never()).setActiveDevice(any());
        verify(mHearingAidService).setActiveDevice(any());
        // In handleLeAudioDisconnected -> deviceDisconnected() hasFallbackDevice is false.
        verify(mLeAudioService).deviceDisconnected(mLeAudioDevice, true);
    }

    /**
     * Verifies that we mutually exclude classic audio profiles (A2DP & HFP) and LE Audio when the
     * dual mode feature is disabled.
     */
    @Test
    public void dualModeAudioDeviceConnected_withDualModeFeatureDisabled() {
        // Turn off the dual mode audio flag
        Utils.setDualModeAudioStateForTesting(false);

        // Ensure we remove the LEA active device when classic audio profiles are made active
        a2dpConnected(mDualModeAudioDevice, true);
        headsetConnected(mDualModeAudioDevice, true);
        mTestLooper.dispatchAll();
        verify(mA2dpService, atLeastOnce()).setActiveDevice(mDualModeAudioDevice);
        verify(mHeadsetService, atLeastOnce()).setActiveDevice(mDualModeAudioDevice);
        verify(mLeAudioService, atLeastOnce()).removeActiveDevice(true);
        assertThat(mActiveDeviceManager.getA2dpActiveDevice()).isEqualTo(mDualModeAudioDevice);
        assertThat(mActiveDeviceManager.getHfpActiveDevice()).isEqualTo(mDualModeAudioDevice);

        // Ensure we make classic audio profiles inactive when LEA is made active
        leAudioConnected(mDualModeAudioDevice);
        mTestLooper.dispatchAll();
        verify(mA2dpService).removeActiveDevice(false);
        verify(mHeadsetService).setActiveDevice(isNull());
        verify(mLeAudioService).setActiveDevice(mDualModeAudioDevice);
        assertThat(mActiveDeviceManager.getLeAudioActiveDevice()).isEqualTo(mDualModeAudioDevice);
    }

    /**
     * Verifies that we connect and make active both classic audio profiles (A2DP & HFP) and LE
     * Audio when the dual mode feature is enabled.
     */
    @Test
    public void dualModeAudioDeviceConnected_withDualModeFeatureEnabled() {
        // Turn on the dual mode audio flag
        Utils.setDualModeAudioStateForTesting(true);
        reset(mLeAudioService);
        when(mLeAudioService.getLeadDevice(mDualModeAudioDevice)).thenReturn(mDualModeAudioDevice);
        when(mLeAudioService.isGroupAvailableForStream(anyInt())).thenReturn(true);

        when(mAdapterService.isAllSupportedClassicAudioProfilesActive(mDualModeAudioDevice))
                .thenReturn(false);

        leAudioConnected(mDualModeAudioDevice);
        mTestLooper.dispatchAll();
        // Verify setting LEA active fails when all supported classic audio profiles are not active
        verify(mLeAudioService).setActiveDevice(mDualModeAudioDevice);
        assertThat(mActiveDeviceManager.getLeAudioActiveDevice()).isNull();
        assertThat(mActiveDeviceManager.getA2dpActiveDevice()).isNull();
        assertThat(mActiveDeviceManager.getHfpActiveDevice()).isNull();

        when(mLeAudioService.setActiveDevice(any())).thenReturn(true);
        when(mLeAudioService.removeActiveDevice(anyBoolean())).thenReturn(true);
        when(mLeAudioService.getLeadDevice(mDualModeAudioDevice)).thenReturn(mDualModeAudioDevice);

        Mockito.clearInvocations(mLeAudioService);

        // Ensure we make LEA active after all supported classic profiles are active
        a2dpActiveDeviceChanged(mDualModeAudioDevice);
        mTestLooper.dispatchAll();

        when(mAdapterService.isAllSupportedClassicAudioProfilesActive(mDualModeAudioDevice))
                .thenReturn(true);
        headsetActiveDeviceChanged(mDualModeAudioDevice);
        mTestLooper.dispatchAll();

        // When Hfp device is getting active and it is dual mode device LeAudioDevice will be added.
        verify(mLeAudioService, never()).removeActiveDevice(anyBoolean());
        verify(mLeAudioService).setActiveDevice(mDualModeAudioDevice);

        assertThat(mActiveDeviceManager.getA2dpActiveDevice()).isEqualTo(mDualModeAudioDevice);
        assertThat(mActiveDeviceManager.getHfpActiveDevice()).isEqualTo(mDualModeAudioDevice);
        assertThat(mActiveDeviceManager.getLeAudioActiveDevice()).isEqualTo(mDualModeAudioDevice);

        // Verify LEA made inactive when a supported classic audio profile is made inactive
        a2dpActiveDeviceChanged(null);
        mTestLooper.dispatchAll();
        assertThat(mActiveDeviceManager.getA2dpActiveDevice()).isNull();
        assertThat(mActiveDeviceManager.getLeAudioActiveDevice()).isNull();
    }

    /**
     * HFP device is connected. LE Audio device is connected. HFP is set to active. This should
     * remove LE Audio active device.
     */
    @Test
    @EnableFlags(Flags.FLAG_ADM_UNSET_OTHERS_ON_HFP_CHANGED)
    public void activeDeviceChange_withHeadsetAndLeAudioDevices() {
        Utils.setDualModeAudioStateForTesting(false);
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_NORMAL);

        headsetConnected(mHeadsetDevice, false);
        mTestLooper.dispatchAll();
        verify(mHeadsetService).setActiveDevice(mHeadsetDevice);

        leAudioConnected(mLeAudioDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice);

        Mockito.clearInvocations(mLeAudioService, mHeadsetService);

        headsetActiveDeviceChanged(mHeadsetDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).removeActiveDevice(true);
    }

    /**
     * HFP device is connected. Dual mode device is connected. HFP is set to active. This should
     * remove LE Audio device.
     */
    @Test
    @EnableFlags(Flags.FLAG_ADM_UNSET_OTHERS_ON_HFP_CHANGED)
    public void activeDeviceChange_withHeadsetAndDualModeAudioDevices() {
        Utils.setDualModeAudioStateForTesting(true);
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_NORMAL);

        headsetConnected(mHeadsetDevice, false);
        mTestLooper.dispatchAll();
        verify(mHeadsetService).setActiveDevice(mHeadsetDevice);

        leAudioConnected(mDualModeAudioDevice);
        a2dpConnected(mDualModeAudioDevice, true);
        headsetConnected(mDualModeAudioDevice, true);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mDualModeAudioDevice);

        Mockito.clearInvocations(mLeAudioService, mA2dpService, mHeadsetService);

        headsetActiveDeviceChanged(mHeadsetDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).removeActiveDevice(true);
    }

    /**
     * Dual mode device is connected. Second dual mode device is connected. HFP is set to active.
     * This should set LE Audio active device.
     */
    @Test
    @EnableFlags(Flags.FLAG_ADM_UNSET_OTHERS_ON_HFP_CHANGED)
    public void activeDeviceChange_withTwoDualModeAudioDevices() {
        Utils.setDualModeAudioStateForTesting(true);
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_NORMAL);
        when(mAdapterService.isAllSupportedClassicAudioProfilesActive(mDualModeAudioDevice))
                .thenReturn(true);
        when(mAdapterService.isAllSupportedClassicAudioProfilesActive(mDualModeAudioDevice2))
                .thenReturn(true);

        headsetConnected(mDualModeAudioDevice, true);
        a2dpConnected(mDualModeAudioDevice, true);
        leAudioConnected(mDualModeAudioDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mDualModeAudioDevice);
        Mockito.clearInvocations(mLeAudioService);
        Mockito.clearInvocations(mA2dpService);
        Mockito.clearInvocations(mHeadsetService);

        headsetConnected(mDualModeAudioDevice2, true);
        a2dpConnected(mDualModeAudioDevice2, true);
        leAudioConnected(mDualModeAudioDevice2);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mDualModeAudioDevice2);

        Mockito.clearInvocations(mLeAudioService);
        Mockito.clearInvocations(mA2dpService);
        Mockito.clearInvocations(mHeadsetService);

        headsetActiveDeviceChanged(mDualModeAudioDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService, never()).removeActiveDevice(anyBoolean());
        verify(mLeAudioService).setActiveDevice(mDualModeAudioDevice);
    }

    /**
     * Verifies that other profiles do not have their active device cleared when we fail to make a
     * newly connected device active.
     */
    @Test
    public void setActiveDeviceFailsUponConnection() {
        Utils.setDualModeAudioStateForTesting(false);
        when(mHeadsetService.setActiveDevice(any())).thenReturn(false);
        when(mA2dpService.setActiveDevice(any())).thenReturn(false);
        when(mHearingAidService.setActiveDevice(any())).thenReturn(false);
        when(mLeAudioService.setActiveDevice(any())).thenReturn(false);

        leAudioConnected(mDualModeAudioDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mDualModeAudioDevice);

        leAudioActiveDeviceChanged(mDualModeAudioDevice);
        mTestLooper.dispatchAll();
        verify(mA2dpService).removeActiveDevice(anyBoolean());
        verify(mHeadsetService).setActiveDevice(null);
        verify(mHearingAidService).removeActiveDevice(anyBoolean());

        a2dpConnected(mA2dpDevice, false);
        mTestLooper.dispatchAll();
        verify(mA2dpService).setActiveDevice(mA2dpDevice);
        verify(mLeAudioService, never()).removeActiveDevice(anyBoolean());

        a2dpConnected(mA2dpHeadsetDevice, true);
        headsetConnected(mA2dpHeadsetDevice, true);
        mTestLooper.dispatchAll();
        verify(mA2dpService, atLeastOnce()).setActiveDevice(mA2dpHeadsetDevice);
        verify(mHeadsetService, atLeastOnce()).setActiveDevice(mA2dpHeadsetDevice);
        verify(mLeAudioService, never()).removeActiveDevice(anyBoolean());

        headsetConnected(mHeadsetDevice, false);
        mTestLooper.dispatchAll();
        verify(mHeadsetService).setActiveDevice(mHeadsetDevice);
        verify(mLeAudioService, never()).removeActiveDevice(anyBoolean());

        hearingAidConnected(mHearingAidDevice);
        mTestLooper.dispatchAll();
        verify(mHearingAidService).setActiveDevice(mHearingAidDevice);
        verify(mLeAudioService, never()).removeActiveDevice(anyBoolean());
        verify(mA2dpService).removeActiveDevice(anyBoolean());
        verify(mHeadsetService).setActiveDevice(null);

        leAudioConnected(mLeHearingAidDevice);
        leHearingAidConnected(mLeHearingAidDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService, atLeastOnce()).setActiveDevice(mLeHearingAidDevice);
        verify(mA2dpService).removeActiveDevice(anyBoolean());
        verify(mHeadsetService).setActiveDevice(null);
        verify(mHearingAidService).removeActiveDevice(anyBoolean());
    }

    /**
     * Verifies if Le Audio Broadcast is streaming, connected a2dp device should not be set as
     * active.
     */
    @Test
    public void a2dpConnectedWhenBroadcasting_notSetA2dpActive() {
        when(mLeAudioService.isBroadcastStarted()).thenReturn(true);
        a2dpConnected(mA2dpDevice, false);
        mTestLooper.dispatchAll();
        verify(mA2dpService, never()).setActiveDevice(any());
        a2dpConnected(mA2dpDevice, true);
        mTestLooper.dispatchAll();
        verify(mA2dpService, never()).setActiveDevice(any());
    }

    /**
     * Verifies if Le Audio Broadcast is streaming, connected headset device should not be set as
     * active.
     */
    @Test
    public void headsetConnectedWhenBroadcasting_notSetHeadsetActive() {
        when(mLeAudioService.isBroadcastStarted()).thenReturn(true);
        headsetConnected(mHeadsetDevice, false);
        mTestLooper.dispatchAll();
        verify(mHeadsetService, never()).setActiveDevice(any());
        headsetConnected(mHeadsetDevice, true);
        mTestLooper.dispatchAll();
        verify(mHeadsetService, never()).setActiveDevice(any());
    }

    /**
     * Verifies if Le Audio Broadcast is streaming, connected hearing aid device should not be set
     * as active.
     */
    @Test
    public void hearingAidConnectedWhenBroadcasting_notSetHearingAidActive() {
        when(mLeAudioService.isBroadcastStarted()).thenReturn(true);
        hearingAidConnected(mHearingAidDevice);
        mTestLooper.dispatchAll();
        verify(mHearingAidService, never()).setActiveDevice(any());
    }

    /**
     * Verifies if Le Audio Broadcast is streaming, connected LE hearing aid device should not be
     * set as active.
     */
    @Test
    public void leHearingAidConnectedWhenBroadcasting_notSetLeHearingAidActive() {
        when(mLeAudioService.isBroadcastStarted()).thenReturn(true);
        leHearingAidConnected(mLeHearingAidDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService, never()).setActiveDevice(any());
    }

    @Test
    @EnableFlags(Flags.FLAG_ADM_CENTRALIZE_ACTIVE_DEVICE_HANDLING)
    public void hearingAidConnected_leAudioSetActive_ashaSetInactive() {
        leAudioConnected(mLeAudioDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice);

        Mockito.clearInvocations(mLeAudioService);

        hearingAidConnected(mHearingAidDevice);
        mTestLooper.dispatchAll();
        verify(mHearingAidService).setActiveDevice(mHearingAidDevice);
        verify(mLeAudioService).removeActiveDevice(true);

        Mockito.clearInvocations(mLeAudioService);
        Mockito.clearInvocations(mHearingAidService);

        when(mHearingAidService.getActiveDevices()).thenReturn(List.of(mHearingAidDevice));
        mActiveDeviceManager.setActiveDevice(mLeAudioDevice, BluetoothAdapter.ACTIVE_DEVICE_ALL);
        mTestLooper.dispatchAll();
        verify(mHearingAidService).removeActiveDevice(false);
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice);
    }

    /**
     * Verifies that a Le Audio Unicast device is not treated as connected when an active device
     * change is received after the device has been disconnected.
     */
    @Test
    public void leAudioActiveDeviceChangeBeforeConnectedEvent() {
        /* Active device change comes after disconnection (device considered as not connected) */
        leAudioActiveDeviceChanged(mLeAudioDevice);
        verify(mLeAudioService, never()).setActiveDevice(mLeAudioDevice);
        mTestLooper.dispatchAll();

        /* Device is connected back */
        leAudioConnected(mLeAudioDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice);
    }

    /** Helper to indicate A2dp connected for a device. */
    private void a2dpConnected(BluetoothDevice device, boolean supportHfp) {
        doReturn(supportHfp ? CONNECTION_POLICY_ALLOWED : CONNECTION_POLICY_UNKNOWN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(device, BluetoothProfile.HEADSET);

        mDeviceConnectionStack.add(device);
        mMostRecentDevice = device;

        mActiveDeviceManager.profileConnectionStateChanged(
                BluetoothProfile.A2DP, device, STATE_DISCONNECTED, STATE_CONNECTED);
    }

    /** Helper to indicate A2dp disconnected for a device. */
    private void a2dpDisconnected(BluetoothDevice device) {
        mDeviceConnectionStack.remove(device);
        mMostRecentDevice =
                (mDeviceConnectionStack.size() > 0)
                        ? mDeviceConnectionStack.get(mDeviceConnectionStack.size() - 1)
                        : null;

        mActiveDeviceManager.profileConnectionStateChanged(
                BluetoothProfile.A2DP, device, STATE_CONNECTED, STATE_DISCONNECTED);
    }

    /** Helper to indicate A2dp active device changed for a device. */
    private void a2dpActiveDeviceChanged(BluetoothDevice device) {
        mDeviceConnectionStack.remove(device);
        mDeviceConnectionStack.add(device);
        mMostRecentDevice = device;

        mActiveDeviceManager.profileActiveDeviceChanged(BluetoothProfile.A2DP, device);
    }

    /** Helper to indicate Headset connected for a device. */
    private void headsetConnected(BluetoothDevice device, boolean supportA2dp) {
        doReturn(supportA2dp ? CONNECTION_POLICY_ALLOWED : CONNECTION_POLICY_UNKNOWN)
                .when(mAdapterService)
                .getProfileConnectionPolicy(device, BluetoothProfile.A2DP);

        mDeviceConnectionStack.add(device);
        mMostRecentDevice = device;

        mActiveDeviceManager.profileConnectionStateChanged(
                BluetoothProfile.HEADSET, device, STATE_DISCONNECTED, STATE_CONNECTED);
    }

    /** Helper to indicate Headset disconnected for a device. */
    private void headsetDisconnected(BluetoothDevice device) {
        mDeviceConnectionStack.remove(device);
        mMostRecentDevice =
                (mDeviceConnectionStack.size() > 0)
                        ? mDeviceConnectionStack.get(mDeviceConnectionStack.size() - 1)
                        : null;

        mActiveDeviceManager.profileConnectionStateChanged(
                BluetoothProfile.HEADSET, device, STATE_CONNECTED, STATE_DISCONNECTED);
    }

    /** Helper to indicate Headset active device changed for a device. */
    private void headsetActiveDeviceChanged(BluetoothDevice device) {
        mDeviceConnectionStack.remove(device);
        mDeviceConnectionStack.add(device);
        mMostRecentDevice = device;

        mActiveDeviceManager.profileActiveDeviceChanged(BluetoothProfile.HEADSET, device);
    }

    /** Helper to indicate Hearing Aid connected for a device. */
    private void hearingAidConnected(BluetoothDevice device) {
        mDeviceConnectionStack.add(device);
        mMostRecentDevice = device;

        mActiveDeviceManager.profileConnectionStateChanged(
                BluetoothProfile.HEARING_AID, device, STATE_DISCONNECTED, STATE_CONNECTED);
    }

    /** Helper to indicate Hearing Aid disconnected for a device. */
    private void hearingAidDisconnected(BluetoothDevice device) {
        mDeviceConnectionStack.remove(device);
        mMostRecentDevice =
                (mDeviceConnectionStack.size() > 0)
                        ? mDeviceConnectionStack.get(mDeviceConnectionStack.size() - 1)
                        : null;

        mActiveDeviceManager.profileConnectionStateChanged(
                BluetoothProfile.HEARING_AID, device, STATE_CONNECTED, STATE_DISCONNECTED);
    }

    /** Helper to indicate Hearing Aid active device changed for a device. */
    private void hearingAidActiveDeviceChanged(BluetoothDevice device) {
        mDeviceConnectionStack.remove(device);
        mDeviceConnectionStack.add(device);
        mMostRecentDevice = device;

        mActiveDeviceManager.profileActiveDeviceChanged(BluetoothProfile.HEARING_AID, device);
    }

    /** Helper to indicate LE Audio connected for a device. */
    private void leAudioConnected(BluetoothDevice device) {
        mDeviceConnectionStack.add(device);
        mMostRecentDevice = device;

        mActiveDeviceManager.profileConnectionStateChanged(
                BluetoothProfile.LE_AUDIO, device, STATE_DISCONNECTED, STATE_CONNECTED);
    }

    /** Helper to indicate LE Audio disconnected for a device. */
    private void leAudioDisconnected(BluetoothDevice device) {
        mDeviceConnectionStack.remove(device);
        mMostRecentDevice =
                (mDeviceConnectionStack.size() > 0)
                        ? mDeviceConnectionStack.get(mDeviceConnectionStack.size() - 1)
                        : null;

        mActiveDeviceManager.profileConnectionStateChanged(
                BluetoothProfile.LE_AUDIO, device, STATE_CONNECTED, STATE_DISCONNECTED);
    }

    /** Helper to indicate LE Audio active device changed for a device. */
    private void leAudioActiveDeviceChanged(BluetoothDevice device) {
        mDeviceConnectionStack.remove(device);
        mDeviceConnectionStack.add(device);
        mMostRecentDevice = device;

        mActiveDeviceManager.profileActiveDeviceChanged(BluetoothProfile.LE_AUDIO, device);
    }

    /** Helper to indicate LE Hearing Aid connected for a device. */
    private void leHearingAidConnected(BluetoothDevice device) {
        mDeviceConnectionStack.add(device);
        mMostRecentDevice = device;

        mActiveDeviceManager.profileConnectionStateChanged(
                BluetoothProfile.HAP_CLIENT, device, STATE_DISCONNECTED, STATE_CONNECTED);
    }

    /** Helper to indicate LE Hearing Aid disconnected for a device. */
    private void leHearingAidDisconnected(BluetoothDevice device) {
        mDeviceConnectionStack.remove(device);
        mMostRecentDevice =
                (mDeviceConnectionStack.size() > 0)
                        ? mDeviceConnectionStack.get(mDeviceConnectionStack.size() - 1)
                        : null;

        mActiveDeviceManager.profileConnectionStateChanged(
                BluetoothProfile.HAP_CLIENT, device, STATE_CONNECTED, STATE_DISCONNECTED);
    }
}
