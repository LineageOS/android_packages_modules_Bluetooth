/*
 * Copyright 2018 The Android Open Source Project
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

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;
import static com.android.bluetooth.TestUtils.mockGetSystemService;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSinkAudioPolicy;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.util.ArrayMap;
import android.util.SparseIntArray;

import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.BluetoothMethodProxy;
import com.android.bluetooth.TestLooper;
import com.android.bluetooth.TestUtils;
import com.android.bluetooth.Utils;
import com.android.bluetooth.a2dp.A2dpService;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.hearingaid.HearingAidService;
import com.android.bluetooth.hfp.HeadsetService;
import com.android.bluetooth.le_audio.LeAudioService;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class ActiveDeviceManagerTest {
    private BluetoothDevice mA2dpDevice;
    private BluetoothDevice mHeadsetDevice;
    private BluetoothDevice mA2dpHeadsetDevice;
    private BluetoothDevice mHearingAidDevice;
    private BluetoothDevice mLeAudioDevice;
    private BluetoothDevice mLeAudioDevice2;
    private BluetoothDevice mLeAudioDevice3;
    private BluetoothDevice mLeHearingAidDevice;
    private BluetoothDevice mSecondaryAudioDevice;
    private BluetoothDevice mDualModeAudioDevice;
    private ArrayList<BluetoothDevice> mDeviceConnectionStack;
    private BluetoothDevice mMostRecentDevice;
    private ActiveDeviceManager mActiveDeviceManager;
    private static final long HEARING_AID_HI_SYNC_ID = 1010;

    private static final int A2DP_HFP_SYNC_CONNECTION_TIMEOUT_MS =
            ActiveDeviceManager.A2DP_HFP_SYNC_CONNECTION_TIMEOUT_MS + 2_000;
    private boolean mOriginalDualModeAudioState;
    private TestDatabaseManager mDatabaseManager;
    private TestLooper mTestLooper;
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private AdapterService mAdapterService;
    @Mock private ServiceFactory mServiceFactory;
    @Mock private A2dpService mA2dpService;
    @Mock private HeadsetService mHeadsetService;
    @Mock private HearingAidService mHearingAidService;
    @Mock private LeAudioService mLeAudioService;
    @Mock private AudioManager mAudioManager;
    @Spy private BluetoothMethodProxy mMethodProxy = BluetoothMethodProxy.getInstance();

    @Before
    public void setUp() throws Exception {
        mTestLooper = new TestLooper();
        BluetoothMethodProxy.setInstanceForTesting(mMethodProxy);
        doReturn(mTestLooper.getLooper()).when(mMethodProxy).handlerThreadGetLooper(any());
        doNothing().when(mMethodProxy).threadStart(any());
        TestUtils.setAdapterService(mAdapterService);

        mDatabaseManager = new TestDatabaseManager(mAdapterService);

        mockGetSystemService(
                mAdapterService, Context.AUDIO_SERVICE, AudioManager.class, mAudioManager);
        when(mAdapterService.getDatabase()).thenReturn(mDatabaseManager);
        when(mServiceFactory.getA2dpService()).thenReturn(mA2dpService);
        when(mServiceFactory.getHeadsetService()).thenReturn(mHeadsetService);
        when(mServiceFactory.getHearingAidService()).thenReturn(mHearingAidService);
        when(mServiceFactory.getLeAudioService()).thenReturn(mLeAudioService);

        mActiveDeviceManager = new ActiveDeviceManager(mAdapterService, mServiceFactory);
        mActiveDeviceManager.start();

        // Get devices for testing
        mA2dpDevice = getTestDevice(0);
        mHeadsetDevice = getTestDevice(1);
        mA2dpHeadsetDevice = getTestDevice(2);
        mHearingAidDevice = getTestDevice(3);
        mLeAudioDevice = getTestDevice(4);
        mLeHearingAidDevice = getTestDevice(5);
        mSecondaryAudioDevice = getTestDevice(6);
        mDualModeAudioDevice = getTestDevice(7);
        mLeAudioDevice2 = getTestDevice(8);
        mLeAudioDevice3 = getTestDevice(9);
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
        when(mLeAudioService.getLeadDevice(mDualModeAudioDevice)).thenReturn(mDualModeAudioDevice);
        when(mLeAudioService.getLeadDevice(mLeHearingAidDevice)).thenReturn(mLeHearingAidDevice);

        List<BluetoothDevice> connectedHearingAidDevices = new ArrayList<>();
        connectedHearingAidDevices.add(mHearingAidDevice);
        when(mHearingAidService.getHiSyncId(mHearingAidDevice)).thenReturn(HEARING_AID_HI_SYNC_ID);
        when(mHearingAidService.getConnectedPeerDevices(HEARING_AID_HI_SYNC_ID))
                .thenReturn(connectedHearingAidDevices);

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
        TestUtils.clearAdapterService(mAdapterService);
        Utils.setDualModeAudioStateForTesting(mOriginalDualModeAudioState);
        assertThat(mTestLooper.nextMessage()).isNull();
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

        mDatabaseManager.setProfileConnectionPolicy(
                mA2dpHeadsetDevice, BluetoothProfile.A2DP, CONNECTION_POLICY_FORBIDDEN);
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

        mDatabaseManager.setProfileConnectionPolicy(
                mSecondaryAudioDevice, BluetoothProfile.HEADSET, CONNECTION_POLICY_FORBIDDEN);
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
     * LE Audio is connected but is not ready for stream (no available context types). Check if it's
     * not used as fallback device from A2DP
     */
    @Test
    public void leAudioFallbackLeaudioToLeaudio_notReadyForStream() {
        /* LeAudio device from group 1 - not ready for stream */
        when(mLeAudioService.getGroupId(mLeAudioDevice)).thenReturn(1);
        /* LeAudio device from group 1 - ready for stream */
        when(mLeAudioService.getGroupId(mSecondaryAudioDevice)).thenReturn(2);
        when(mLeAudioService.isGroupAvailableForStream(1)).thenReturn(false);
        when(mLeAudioService.isGroupAvailableForStream(2)).thenReturn(true);
        leAudioConnected(mLeAudioDevice);
        leAudioConnected(mSecondaryAudioDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService, never()).setActiveDevice(mLeAudioDevice);
        verify(mLeAudioService).setActiveDevice(mSecondaryAudioDevice);

        leAudioDisconnected(mSecondaryAudioDevice);
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

        leAudioConnected(mSecondaryAudioDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mSecondaryAudioDevice);
    }

    /** One LE Audio is connected and disconnected later. Should then set active device to null. */
    @Test
    @EnableFlags(Flags.FLAG_ADM_FIX_DISCONNECT_OF_SET_MEMBER)
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

    /** One LE Audio is connected and disconnected later. Should then set active device to null. */
    @Test
    @DisableFlags(Flags.FLAG_ADM_FIX_DISCONNECT_OF_SET_MEMBER)
    public void lastLeAudioDisconnected_clearLeAudioActive_NoFixDisconnectFlag() {
        when(mLeAudioService.getGroupId(mLeAudioDevice)).thenReturn(1);
        when(mLeAudioService.getLeadDevice(mLeAudioDevice)).thenReturn(mLeAudioDevice);

        leAudioConnected(mLeAudioDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice);

        leAudioDisconnected(mLeAudioDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).removeActiveDevice(false);
    }

    /** Two LE Audio are connected and active device is explicitly set. */
    @Test
    public void leAudioActiveDeviceSelected_setActive() {
        leAudioConnected(mLeAudioDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice);

        leAudioConnected(mSecondaryAudioDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mSecondaryAudioDevice);

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
     * One LE Audio set, containing two buds, is connected. When one device got disconnected
     * fallback device should not be set to true active device to fallback device.
     */
    @Test
    @EnableFlags(Flags.FLAG_ADM_FIX_DISCONNECT_OF_SET_MEMBER)
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
    @EnableFlags(Flags.FLAG_ADM_FIX_DISCONNECT_OF_SET_MEMBER)
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
    @EnableFlags(Flags.FLAG_ADM_FIX_DISCONNECT_OF_SET_MEMBER)
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
    @EnableFlags(Flags.FLAG_ADM_FIX_DISCONNECT_OF_SET_MEMBER)
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
    @EnableFlags(Flags.FLAG_ADM_FIX_DISCONNECT_OF_SET_MEMBER)
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

    @Test
    @DisableFlags(Flags.FLAG_ADM_FIX_DISCONNECT_OF_SET_MEMBER)
    public void leAudioSetConnectedGroupThenDisconnected_noFallback_NoFixDisconnectFlag() {
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
        order.verify(mLeAudioService).removeActiveDevice(false);
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
    @EnableFlags(Flags.FLAG_ADM_VERIFY_ACTIVE_FALLBACK_DEVICE)
    public void sameDeviceAsAshaAndLeAudio_noFallbackOnSwitch() {
        /* Dual mode ASHA/LeAudio device from group 1 */
        when(mLeAudioService.getGroupId(mHearingAidDevice)).thenReturn(1);
        /* Different LeAudio only device from group 2 */
        when(mLeAudioService.getGroupId(mLeAudioDevice)).thenReturn(2);

        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_NORMAL);

        /* Connect first device as ASHA */
        hearingAidConnected(mHearingAidDevice);
        mTestLooper.dispatchAll();
        verify(mHearingAidService).setActiveDevice(mHearingAidDevice);

        /* Disconnect ASHA and connect first device as LE Audio */
        hearingAidDisconnected(mHearingAidDevice);
        mTestLooper.dispatchAll();
        verify(mHearingAidService).removeActiveDevice(true /* stop audio */);
        leAudioConnected(mHearingAidDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mHearingAidDevice);

        /* Connect second device as LE Audio. First device is disconnected with fallback to
         * new one.
         */
        leAudioConnected(mLeAudioDevice);
        leAudioDisconnected(mHearingAidDevice);
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
    @EnableFlags(Flags.FLAG_ADM_VERIFY_ACTIVE_FALLBACK_DEVICE)
    public void sameDeviceAsLeAudioAndAsha_noFallbackOnSwitch() {
        // Turn on the dual mode audio flag so the A2DP won't disconnect LE Audio
        when(mAudioManager.getMode()).thenReturn(AudioManager.MODE_NORMAL);
        List<BluetoothDevice> list = new ArrayList<>();
        when(mLeAudioService.getActiveDevices()).thenReturn(list);

        /* Connect first device as LE Audio */
        leAudioConnected(mHearingAidDevice);
        list.add(mHearingAidDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mHearingAidDevice);

        /* Connect first device as ASHA */
        hearingAidConnected(mHearingAidDevice);
        leAudioDisconnected(mHearingAidDevice);
        mTestLooper.dispatchAll();
        verify(mHearingAidService).setActiveDevice(mHearingAidDevice);
        verify(mLeAudioService).removeActiveDevice(false);

        /* Connect second device as ASHA. It is set as fallback device for LE Audio Service
         */
        hearingAidConnected(mSecondaryAudioDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).removeActiveDevice(true);
        verify(mHearingAidService).setActiveDevice(mSecondaryAudioDevice);
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
        verify(mLeAudioService, times(2)).setActiveDevice(mLeHearingAidDevice);
        verify(mA2dpService).removeActiveDevice(anyBoolean());
        verify(mHeadsetService).setActiveDevice(null);
        verify(mHearingAidService).removeActiveDevice(anyBoolean());
    }

    /** A wired audio device is connected. Then all active devices are set to null. */
    @Test
    @DisableFlags(Flags.FLAG_ADM_REMOVE_HANDLING_WIRED)
    public void wiredAudioDeviceConnected_setAllActiveDevicesNull() {
        a2dpConnected(mA2dpDevice, false);
        headsetConnected(mHeadsetDevice, false);
        mTestLooper.dispatchAll();
        verify(mA2dpService).setActiveDevice(mA2dpDevice);
        verify(mHeadsetService).setActiveDevice(mHeadsetDevice);

        mActiveDeviceManager.wiredAudioDeviceConnected();
        verify(mA2dpService).removeActiveDevice(false);
        verify(mHeadsetService).setActiveDevice(isNull());
        verify(mHearingAidService).removeActiveDevice(false);
    }

    /** A wired audio device is disconnected. Check if falls back to connected A2DP. */
    @Test
    @DisableFlags(Flags.FLAG_ADM_REMOVE_HANDLING_WIRED)
    public void wiredAudioDeviceDisconnected_setFallbackDevice() throws Exception {
        AudioDeviceInfo a2dpDevice = mock(AudioDeviceInfo.class);
        doReturn(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP).when(a2dpDevice).getType();

        AudioDeviceInfo usbDevice = mock(AudioDeviceInfo.class);
        doReturn(AudioDeviceInfo.TYPE_USB_HEADSET).when(usbDevice).getType();

        AudioDeviceInfo[] testDevices = new AudioDeviceInfo[] {a2dpDevice, usbDevice};

        // Connect A2DP headphones
        a2dpConnected(mA2dpDevice, false);
        mTestLooper.dispatchAll();
        verify(mA2dpService).setActiveDevice(mA2dpDevice);
        verify(mLeAudioService).removeActiveDevice(true);

        // Connect wired audio device
        mActiveDeviceManager.mAudioManagerAudioDeviceCallback.onAudioDevicesAdded(testDevices);

        // Check wiredAudioDeviceConnected invoked properly
        verify(mA2dpService).removeActiveDevice(false);
        verify(mHeadsetService).setActiveDevice(isNull());
        verify(mHearingAidService).removeActiveDevice(false);
        verify(mLeAudioService, times(2)).removeActiveDevice(true);

        // Disconnect wired audio device
        mActiveDeviceManager.mAudioManagerAudioDeviceCallback.onAudioDevicesRemoved(testDevices);

        // Verify fallback to A2DP device
        verify(mA2dpService, times(2)).setActiveDevice(mA2dpDevice);
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
        mDatabaseManager.setProfileConnectionPolicy(
                device,
                BluetoothProfile.HEADSET,
                supportHfp ? CONNECTION_POLICY_ALLOWED : CONNECTION_POLICY_UNKNOWN);

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
        mDatabaseManager.setProfileConnectionPolicy(
                device,
                BluetoothProfile.A2DP,
                supportA2dp ? CONNECTION_POLICY_ALLOWED : CONNECTION_POLICY_UNKNOWN);

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

    private class TestDatabaseManager extends DatabaseManager {
        final ArrayMap<BluetoothDevice, SparseIntArray> mProfileConnectionPolicy;

        TestDatabaseManager(AdapterService service) {
            super(service);
            mProfileConnectionPolicy = new ArrayMap<>();
        }

        @Override
        public BluetoothDevice getMostRecentlyConnectedDevicesInList(
                List<BluetoothDevice> devices) {
            if (devices == null || devices.size() == 0) {
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

        @Override
        public boolean setProfileConnectionPolicy(BluetoothDevice device, int profile, int policy) {
            if (device == null) {
                return false;
            }
            if (policy != CONNECTION_POLICY_UNKNOWN
                    && policy != CONNECTION_POLICY_FORBIDDEN
                    && policy != CONNECTION_POLICY_ALLOWED) {
                return false;
            }
            SparseIntArray policyMap = mProfileConnectionPolicy.get(device);
            if (policyMap == null) {
                policyMap = new SparseIntArray();
                mProfileConnectionPolicy.put(device, policyMap);
            }
            policyMap.put(profile, policy);
            return true;
        }

        @Override
        public int getProfileConnectionPolicy(BluetoothDevice device, int profile) {
            SparseIntArray policy = mProfileConnectionPolicy.get(device);
            if (policy == null) {
                return CONNECTION_POLICY_FORBIDDEN;
            }
            return policy.get(profile, CONNECTION_POLICY_FORBIDDEN);
        }
    }
}
