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

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSinkAudioPolicy;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.filters.MediumTest;

import com.android.bluetooth.BluetoothMethodProxy;
import com.android.bluetooth.TestLooper;
import com.android.bluetooth.Utils;
import com.android.bluetooth.a2dp.A2dpService;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;

import platform.test.runner.parameterized.ParameterizedAndroidJunit4;
import platform.test.runner.parameterized.Parameters;

import java.util.ArrayList;
import java.util.Arrays;
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
    private final List<BluetoothDevice> mEmptyDevices = Arrays.asList(null, null);

    private ArrayList<BluetoothDevice> mDeviceConnectionStack;
    private BluetoothDevice mMostRecentDevice;
    private ActiveDeviceManager mActiveDeviceManager;
    private boolean mOriginalDualModeAudioState;
    private TestLooper mTestLooper;

    private AudioDeviceCallback mAudioDeviceCallback;

    @Parameters(name = "{0}")
    public static List<FlagsWrapper> getParams() {
        return FlagsWrapper.progressionOf();
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
                .when(mStorage)
                .getMostRecentlyConnectedDeviceInList(any());
        doAnswer(invocation -> getMostRecentlyConnectedDevices())
                .when(mStorage)
                .getMostRecentlyConnectedDevices();

        mockGetSystemService(mAdapterService, AudioManager.class, mAudioManager);
        doReturn(Optional.of(mA2dpService)).when(mAdapterService).getA2dpService();
        doReturn(Optional.of(mHeadsetService)).when(mAdapterService).getHeadsetService();
        doReturn(Optional.of(mHearingAidService)).when(mAdapterService).getHearingAidService();
        doReturn(mEmptyDevices).when(mHearingAidService).getActiveDevices();
        doReturn(mEmptyDevices).when(mLeAudioService).getActiveDevices();
        doReturn(Optional.of(mLeAudioService)).when(mAdapterService).getLeAudioService();
        doReturn(true)
                .when(mAdapterService)
                .isProfileSupported(mLeHearingAidDevice, BluetoothProfile.HAP_CLIENT);
        doReturn(true)
                .when(mAdapterService)
                .isProfileSupported(mDualModeAudioDevice, BluetoothProfile.LE_AUDIO);

        mActiveDeviceManager = new ActiveDeviceManager(mAdapterService, mStorage);
        mActiveDeviceManager.start();
        // Capture the Audio Manager callback
        if (Flags.admCentralizeActiveDeviceHandling()) {
            ArgumentCaptor<AudioDeviceCallback> captor =
                    ArgumentCaptor.forClass(AudioDeviceCallback.class);
            verify(mAudioManager).registerAudioDeviceCallback(captor.capture(), any(Handler.class));
            mAudioDeviceCallback = captor.getValue();
        }

        // Get devices for testing
        mDeviceConnectionStack = new ArrayList<>();
        mMostRecentDevice = null;
        mOriginalDualModeAudioState = Utils.isDualModeAudioEnabled();

        doReturn(true).when(mA2dpService).setActiveDevice(any());
        doReturn(true).when(mA2dpService).removeActiveDevice(anyBoolean());
        doReturn(new BluetoothSinkAudioPolicy.Builder().build())
                .when(mHeadsetService)
                .getHfpCallAudioPolicy(any());
        doReturn(true).when(mHeadsetService).setActiveDevice(any());
        doReturn(true).when(mHearingAidService).setActiveDevice(any());
        doReturn(true).when(mHearingAidService).removeActiveDevice(anyBoolean());
        doReturn(true).when(mLeAudioService).setActiveDevice(any());
        doReturn(true).when(mLeAudioService).isGroupAvailableForStream(anyInt());
        doReturn(true).when(mLeAudioService).removeActiveDevice(anyBoolean());

        doReturn(mLeAudioDevice).when(mLeAudioService).getLeadDevice(mLeAudioDevice);
        doReturn(mLeAudioDevice2).when(mLeAudioService).getLeadDevice(mLeAudioDevice2);
        doReturn(mLeAudioDevice3).when(mLeAudioService).getLeadDevice(mLeAudioDevice3);
        doReturn(mLeAudioDevice4).when(mLeAudioService).getLeadDevice(mLeAudioDevice4);
        doReturn(mDualModeAudioDevice).when(mLeAudioService).getLeadDevice(mDualModeAudioDevice);
        doReturn(mDualModeAudioDevice2).when(mLeAudioService).getLeadDevice(mDualModeAudioDevice2);
        doReturn(mLeHearingAidDevice).when(mLeAudioService).getLeadDevice(mLeHearingAidDevice);
        doReturn(mDualModeHearingAidDevice)
                .when(mLeAudioService)
                .getLeadDevice(mDualModeHearingAidDevice);

        List<BluetoothDevice> connectedHearingAidDevices = new ArrayList<>();
        List<BluetoothDevice> connectedDualModeHearingAidDevices = new ArrayList<>();
        connectedHearingAidDevices.add(mHearingAidDevice);
        connectedDualModeHearingAidDevices.add(mDualModeHearingAidDevice);
        doReturn(HEARING_AID_HI_SYNC_ID).when(mHearingAidService).getHiSyncId(mHearingAidDevice);
        doReturn(DUAL_MODE_HEARING_AID_HI_SYNC_ID)
                .when(mHearingAidService)
                .getHiSyncId(mDualModeHearingAidDevice);
        doReturn(connectedHearingAidDevices)
                .when(mHearingAidService)
                .getConnectedPeerDevices(HEARING_AID_HI_SYNC_ID);
        doReturn(connectedDualModeHearingAidDevices)
                .when(mHearingAidService)
                .getConnectedPeerDevices(DUAL_MODE_HEARING_AID_HI_SYNC_ID);

        doReturn(CONNECTION_POLICY_ALLOWED).when(mA2dpService).getConnectionPolicy(mA2dpDevice);
        doReturn(CONNECTION_POLICY_ALLOWED)
                .when(mHeadsetService)
                .getConnectionPolicy(mHeadsetDevice);
        doReturn(CONNECTION_POLICY_ALLOWED)
                .when(mA2dpService)
                .getConnectionPolicy(mA2dpHeadsetDevice);
        doReturn(CONNECTION_POLICY_ALLOWED)
                .when(mHeadsetService)
                .getConnectionPolicy(mA2dpHeadsetDevice);
        doReturn(CONNECTION_POLICY_ALLOWED)
                .when(mHearingAidService)
                .getConnectionPolicy(mHearingAidDevice);
        doReturn(CONNECTION_POLICY_ALLOWED)
                .when(mLeAudioService)
                .getConnectionPolicy(mLeAudioDevice);
        doReturn(CONNECTION_POLICY_ALLOWED)
                .when(mLeAudioService)
                .getConnectionPolicy(mLeAudioDevice2);
        doReturn(CONNECTION_POLICY_ALLOWED)
                .when(mLeAudioService)
                .getConnectionPolicy(mLeAudioDevice3);
        doReturn(CONNECTION_POLICY_ALLOWED)
                .when(mLeAudioService)
                .getConnectionPolicy(mLeAudioDevice4);
        doReturn(CONNECTION_POLICY_ALLOWED)
                .when(mLeAudioService)
                .getConnectionPolicy(mDualModeAudioDevice);
        doReturn(CONNECTION_POLICY_ALLOWED)
                .when(mA2dpService)
                .getConnectionPolicy(mDualModeAudioDevice);
        doReturn(CONNECTION_POLICY_ALLOWED)
                .when(mLeAudioService)
                .getConnectionPolicy(mDualModeHearingAidDevice);
        doReturn(CONNECTION_POLICY_ALLOWED)
                .when(mA2dpService)
                .getConnectionPolicy(mDualModeHearingAidDevice);
        doReturn(CONNECTION_POLICY_ALLOWED)
                .when(mHearingAidService)
                .getConnectionPolicy(mDualModeHearingAidDevice);

        doAnswer(
                        invocation -> {
                            if (!mDeviceConnectionStack.isEmpty()
                                    && Objects.equals(
                                            mA2dpDevice,
                                            mDeviceConnectionStack.get(
                                                    mDeviceConnectionStack.size() - 1))) {
                                return mA2dpDevice;
                            }
                            return null;
                        })
                .when(mA2dpService)
                .getFallbackDevice();
        doAnswer(
                        invocation -> {
                            if (!mDeviceConnectionStack.isEmpty()
                                    && Objects.equals(
                                            mHeadsetDevice,
                                            mDeviceConnectionStack.get(
                                                    mDeviceConnectionStack.size() - 1))) {
                                return mHeadsetDevice;
                            }
                            return null;
                        })
                .when(mHeadsetService)
                .getFallbackDevice();
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

    /**
     * One A2DP is connected.
     *
     * <pre>
     * Steps:
     * 1. Connect A2DP device.
     * 2. Verify A2DP service is called to setActiveDevice.
     * </pre>
     */
    @Test
    public void onlyA2dpConnected_setA2dpActive() {
        a2dpConnected(mA2dpDevice, false);
        mTestLooper.dispatchAll();
        verify(mA2dpService).setActiveDevice(mA2dpDevice);
    }

    /**
     * A2DP and Headset connected. A2DP active should be set after Headset is connected.
     *
     * <pre>
     * Steps:
     * 1. Set audio mode to IN_CALL.
     * 2. Connect A2DP device (supports HFP).
     * 3. Verify A2DP service does not set device as active yet.
     * 4. Connect Headset profile.
     * 5. Verify A2DP service is called to setActiveDevice.
     * 6. Verify Headset service is called to setActiveDevice.
     * </pre>
     */
    @Test
    public void a2dpHeadsetConnected_setA2dpActiveShouldBeCalledAfterHeadsetConnected() {
        doReturn(AudioManager.MODE_IN_CALL).when(mAudioManager).getMode();

        a2dpConnected(mA2dpHeadsetDevice, true);
        mTestLooper.dispatchAll();
        verify(mA2dpService, never()).setActiveDevice(mA2dpHeadsetDevice);

        headsetConnected(mA2dpHeadsetDevice, true);
        mTestLooper.dispatchAll();
        verify(mA2dpService).setActiveDevice(mA2dpHeadsetDevice);
        verify(mHeadsetService).setActiveDevice(mA2dpHeadsetDevice);
    }

    /**
     * A2DP and HFP device is connected.
     *
     * <pre>
     * Steps:
     * 1. Set audio mode to IN_CALL.
     * 2. Connect A2DP device.
     * 3. Connect Headset device.
     * 4. Verify A2DP service is called to setActiveDevice.
     * 5. Verify Headset service is called to setActiveDevice.
     * </pre>
     */
    @Test
    public void a2dpAndHfpConnectedAtTheSameTime_setA2dpActiveShouldBeCalled() {
        doReturn(AudioManager.MODE_IN_CALL).when(mAudioManager).getMode();

        a2dpConnected(mA2dpHeadsetDevice, true);
        headsetConnected(mA2dpHeadsetDevice, true);
        mTestLooper.dispatchAll();
        verify(mA2dpService).setActiveDevice(mA2dpHeadsetDevice);
        verify(mHeadsetService).setActiveDevice(mA2dpHeadsetDevice);
    }

    /**
     * Two A2DP devices are connected. Should set the second one active.
     *
     * <pre>
     * Steps:
     * 1. Connect first A2DP device.
     * 2. Verify A2DP service is called to set first device as active.
     * 3. Connect second A2DP device.
     * 4. Verify A2DP service is called to set second device as active.
     * </pre>
     */
    @Test
    public void secondA2dpConnected_setSecondA2dpActive() {
        a2dpConnected(mA2dpDevice, false);
        mTestLooper.dispatchAll();
        verify(mA2dpService).setActiveDevice(mA2dpDevice);

        a2dpConnected(mSecondaryAudioDevice, false);
        mTestLooper.dispatchAll();
        verify(mA2dpService).setActiveDevice(mSecondaryAudioDevice);
    }

    /**
     * One A2DP device is connected and disconnected later. Should then set active device to null.
     *
     * <pre>
     * Steps:
     * 1. Connect A2DP device.
     * 2. Verify A2DP service is called to setActiveDevice.
     * 3. Disconnect A2DP device.
     * 4. Verify A2DP service removes active device.
     * </pre>
     */
    @Test
    public void lastA2dpDisconnected_clearA2dpActive() {
        a2dpConnected(mA2dpDevice, false);
        mTestLooper.dispatchAll();
        verify(mA2dpService).setActiveDevice(mA2dpDevice);

        a2dpDisconnected(mA2dpDevice);
        mTestLooper.dispatchAll();
        verify(mA2dpService).removeActiveDevice(true);
    }

    /**
     * Two A2DP devices are connected and active device is explicitly set.
     *
     * <pre>
     * Steps:
     * 1. Connect first A2DP device.
     * 2. Connect second A2DP device.
     * 3. Explicitly change active device to first A2DP device.
     * 4. Verify A2DP service is called to set first device as active.
     * 5. Verify ActiveDeviceManager has first device as A2DP active device.
     * </pre>
     */
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
     *
     * <pre>
     * Steps:
     * 1. Connect first A2DP device.
     * 2. Connect second A2DP device.
     * 3. Disconnect second A2DP device.
     * 4. Verify A2DP service is called to set first device as active.
     * </pre>
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

    /**
     * One Headset device is connected.
     *
     * <pre>
     * Steps:
     * 1. Connect Headset device.
     * 2. Verify Headset service is called to setActiveDevice.
     * </pre>
     */
    @Test
    public void onlyHeadsetConnected_setHeadsetActive() {
        headsetConnected(mHeadsetDevice, false);
        mTestLooper.dispatchAll();
        verify(mHeadsetService).setActiveDevice(mHeadsetDevice);
    }

    /**
     * Two Headset devices are connected. Should set the second one active.
     *
     * <pre>
     * Steps:
     * 1. Connect first Headset device.
     * 2. Connect second Headset device.
     * 3. Verify Headset service is called to set second device as active.
     * </pre>
     */
    @Test
    public void secondHeadsetConnected_setSecondHeadsetActive() {
        headsetConnected(mHeadsetDevice, false);
        mTestLooper.dispatchAll();
        verify(mHeadsetService).setActiveDevice(mHeadsetDevice);

        headsetConnected(mSecondaryAudioDevice, false);
        mTestLooper.dispatchAll();
        verify(mHeadsetService).setActiveDevice(mSecondaryAudioDevice);
    }

    /**
     * One Headset device is connected and disconnected later. Should then set active device to
     * null.
     *
     * <pre>
     * Steps:
     * 1. Connect Headset device.
     * 2. Disconnect Headset device.
     * 3. Verify Headset service is called to set active device to null.
     * </pre>
     */
    @Test
    public void lastHeadsetDisconnected_clearHeadsetActive() {
        headsetConnected(mHeadsetDevice, false);
        mTestLooper.dispatchAll();
        verify(mHeadsetService).setActiveDevice(mHeadsetDevice);

        headsetDisconnected(mHeadsetDevice);
        mTestLooper.dispatchAll();
        verify(mHeadsetService).setActiveDevice(isNull());
    }

    /**
     * Two Headset devices are connected and active device is explicitly set.
     *
     * <pre>
     * Steps:
     * 1. Connect first Headset device.
     * 2. Connect second Headset device.
     * 3. Explicitly change active device to first Headset device.
     * 4. Verify Headset service is called to set first device as active.
     * </pre>
     */
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
     * Two Headset devices are connected and the current active device is then disconnected. Should
     * then set active device to fallback device.
     *
     * <pre>
     * Steps:
     * 1. Set audio mode to IN_CALL.
     * 2. Connect first Headset device.
     * 3. Connect second Headset device.
     * 4. Disconnect second Headset device.
     * 5. Verify Headset service is called to set first device as active.
     * </pre>
     */
    @Test
    public void headsetSecondDeviceDisconnected_fallbackDeviceActive() {
        doReturn(AudioManager.MODE_IN_CALL).when(mAudioManager).getMode();

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

    /**
     * Headset active device is removed. Fallback to LeAudio.
     *
     * <pre>
     * Steps:
     * 1. Connect LeAudio device.
     * 2. Connect Headset device.
     * 3. Remove Headset active device.
     * 4. Verify LeAudio service is called to setActiveDevice.
     * </pre>
     */
    @Test
    public void headsetRemoveActive_fallbackToLeAudio() {
        doReturn(mHeadsetDevice).when(mHeadsetService).getFallbackDevice();
        doReturn(1).when(mLeAudioService).getGroupId(mLeAudioDevice);

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

    /**
     * Device supporting A2DP and Headset is connected but Headset profile does not connect. Set
     * A2DP active after timeout.
     *
     * <pre>
     * Steps:
     * 1. Set audio mode to IN_CALL.
     * 2. Connect A2DP device (supports HFP).
     * 3. Wait for half of timeout.
     * 4. Verify A2DP service does not set device as active.
     * 5. Wait for full timeout.
     * 6. Verify A2DP service is called to setActiveDevice.
     * </pre>
     */
    @Test
    public void a2dpConnectedButHeadsetNotConnected_setA2dpActive() {
        doReturn(AudioManager.MODE_IN_CALL).when(mAudioManager).getMode();
        a2dpConnected(mA2dpHeadsetDevice, true);

        mTestLooper.moveTimeForward(ActiveDeviceManager.A2DP_HFP_SYNC_CONNECTION_TIMEOUT_MS / 2);
        mTestLooper.dispatchAll();
        verify(mA2dpService, never()).setActiveDevice(mA2dpHeadsetDevice);
        mTestLooper.moveTimeForward(A2DP_HFP_SYNC_CONNECTION_TIMEOUT_MS);
        mTestLooper.dispatchAll();
        verify(mA2dpService).setActiveDevice(mA2dpHeadsetDevice);
    }

    /**
     * Device supporting A2DP and Headset is connected but A2DP profile does not connect. Set
     * Headset active after timeout.
     *
     * <pre>
     * Steps:
     * 1. Set audio mode to NORMAL.
     * 2. Connect Headset device (supports A2DP).
     * 3. Wait for half of timeout.
     * 4. Verify Headset service does not set device as active.
     * 5. Wait for full timeout.
     * 6. Verify Headset service is called to setActiveDevice.
     * </pre>
     */
    @Test
    public void headsetConnectedButA2dpNotConnected_setHeadsetActive() {
        doReturn(AudioManager.MODE_NORMAL).when(mAudioManager).getMode();
        headsetConnected(mA2dpHeadsetDevice, true);

        mTestLooper.moveTimeForward(ActiveDeviceManager.A2DP_HFP_SYNC_CONNECTION_TIMEOUT_MS / 2);
        mTestLooper.dispatchAll();
        verify(mHeadsetService, never()).setActiveDevice(mA2dpHeadsetDevice);
        mTestLooper.moveTimeForward(A2DP_HFP_SYNC_CONNECTION_TIMEOUT_MS);
        mTestLooper.dispatchAll();
        verify(mHeadsetService).setActiveDevice(mA2dpHeadsetDevice);
    }

    /**
     * HFP activated after A2DP activated. Should not activate A2DP again.
     *
     * <pre>
     * Steps:
     * 1. Connect two A2DP+HFP devices.
     * 2. Activate A2DP on first device.
     * 3. Verify Headset service is called to set first device as active.
     * 4. Activate HFP on first device.
     * 5. Verify A2DP service does not set device as active again.
     * </pre>
     */
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

    /**
     * Test that receiving AudioManager onAudioDevicesRemoved callback for HFP device doesn't cause
     * the fallback mechanism to trigger.
     *
     * <pre>
     * Steps:
     * 1. Connect HFP device.
     * 2. Connect A2DP+HFP device. This is currently active device.
     * 3. Inject onAudioDevicesRemoved callback, with the second device.
     * 4. Last connected device, and still connected one, is returned as fallback device.
     * 5. Assert that no device is set as fallback.
     * </pre>
     */
    @Test
    @EnableFlags(Flags.FLAG_ADM_CENTRALIZE_ACTIVE_DEVICE_HANDLING)
    public void hfpActiveAudioDeviceRemovedNoFallbackSearch() {
        headsetConnected(mHeadsetDevice, false);
        mTestLooper.dispatchAll();
        verify(mHeadsetService).setActiveDevice(mHeadsetDevice);

        a2dpConnected(mA2dpHeadsetDevice, true);
        headsetConnected(mA2dpHeadsetDevice, true);
        mTestLooper.dispatchAll();
        verify(mA2dpService, atLeastOnce()).setActiveDevice(mA2dpHeadsetDevice);
        verify(mHeadsetService, atLeastOnce()).setActiveDevice(mA2dpHeadsetDevice);

        headsetActiveDeviceChanged(mA2dpHeadsetDevice);
        a2dpActiveDeviceChanged(mA2dpHeadsetDevice);
        mTestLooper.dispatchAll();

        Mockito.clearInvocations(mHeadsetService, mA2dpService);
        doReturn(AudioManager.MODE_IN_CALL).when(mAudioManager).getMode();

        // Prepare AudioDeviceInfo
        AudioDeviceInfo deviceInfo = Mockito.mock(AudioDeviceInfo.class);
        doReturn("00:01:02:03:04:02").when(deviceInfo).getAddress();
        doReturn(AudioDeviceInfo.TYPE_BLUETOOTH_SCO).when(deviceInfo).getType();

        doReturn(mA2dpHeadsetDevice).when(mAdapterService).getDeviceFromByte(any());
        doReturn(true).when(mAdapterService).isAvailable();

        doReturn(mA2dpHeadsetDevice).when(mHeadsetService).getFallbackDevice();

        mAudioDeviceCallback.onAudioDevicesRemoved(new AudioDeviceInfo[] {deviceInfo});
        mTestLooper.dispatchAll();
        verify(mHeadsetService, never()).setActiveDevice(any());
        verify(mA2dpService, never()).setActiveDevice(any());
    }

    /**
     * Switch active device from LE Audio to HFP. No fallback to LE Audio.
     *
     * <pre>
     * Steps:
     * 1. Disable dual mode audio.
     * 2. Connect A2DP+HFP device.
     * 3. Connect LE Audio device and set as active.
     * 4. Set A2DP+HFP device as active.
     * 5. Verify LE Audio service does not set device as active.
     * 6. Verify A2DP service is called to setActiveDevice.
     * </pre>
     */
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

    /**
     * HFP activated after timeout. Should activate A2DP again.
     *
     * <pre>
     * Steps:
     * 1. Connect A2DP+HFP device.
     * 2. Activate A2DP on device.
     * 3. Wait for timeout.
     * 4. Verify Headset service is called to setActiveDevice.
     * 5. Activate HFP on device.
     * 6. Verify A2DP service is called to setActiveDevice.
     * </pre>
     */
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

    /**
     * A2DP Headset activated while activating another A2DP Headset.
     *
     * <pre>
     * Steps:
     * 1. Connect two A2DP+HFP devices.
     * 2. Scenario 1:
     *    a) Activate A2DP on first device.
     *    b) Activate A2DP on second device.
     *    c) Activate HFP on first device.
     *    d) Activate HFP on second device.
     *    e) Verify that second device is active for both A2DP and HFP.
     * 3. Scenario 2:
     *    a) Activate HFP on first device.
     *    b) Activate HFP on second device.
     *    c) Activate A2DP on first device.
     *    d) Activate A2DP on second device.
     *    e) Verify that second device is active for both A2DP and HFP.
     * </pre>
     */
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

    /**
     * Check fallback mechanism with one A2DP and one Headset.
     *
     * <pre>
     * Steps:
     * 1. Set audio mode to IN_CALL.
     * 2. Connect first A2DP+HFP device and set active.
     * 3. Disable A2DP for first device and disconnect A2DP.
     * 4. Verify Headset service is called to setActiveDevice.
     * 5. Connect second A2DP+HFP device and set active.
     * 6. Disable Headset for second device and disconnect Headset.
     * 7. Verify fallback behavior: A2DP active remains the second device, HFP active falls back to
     *    the first device.
     * </pre>
     */
    @Test
    public void a2dpHeadsetActivated_checkFallbackMechanismOneA2dpOneHeadset() {
        // Active call
        doReturn(AudioManager.MODE_IN_CALL).when(mAudioManager).getMode();

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
        doReturn(null).when(mA2dpService).getFallbackDevice();
        doReturn(true).when(mA2dpService).removeActiveDevice(anyBoolean());
        doReturn(mA2dpHeadsetDevice).when(mHeadsetService).getFallbackDevice();

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
        doReturn(mSecondaryAudioDevice).when(mA2dpService).getFallbackDevice();
        doReturn(mA2dpHeadsetDevice).when(mHeadsetService).getFallbackDevice();

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

    /**
     * HFP activated while activating A2DP Headset.
     *
     * <pre>
     * Steps:
     * 1. Connect Headset device.
     * 2. Connect A2DP+HFP device.
     * 3. Activate HFP on A2DP+HFP device.
     * 4. Verify A2DP service is called to setActiveDevice.
     * 5. Activate HFP on Headset device.
     * 6. Verify A2DP service does not set device as active.
     * 7. Activate A2DP on A2DP+HFP device.
     * 8. Verify Headset service does not set device as active.
     * </pre>
     */
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

    /**
     * A2DP deactivated. Make sure to not remove LE Audio device.
     *
     * <pre>
     * Steps:
     * 1. Deactivate A2DP device.
     * 2. Verify LE Audio service does not remove active device.
     * </pre>
     */
    @Test
    public void a2dpDeactivated_makeSureToNotRemoveLeAudioDevice() {
        a2dpActiveDeviceChanged(null);
        mTestLooper.dispatchAll();
        verify(mLeAudioService, never()).removeActiveDevice(anyBoolean());
    }

    /**
     * HFP deactivated. Make sure to not remove LE Audio device.
     *
     * <pre>
     * Steps:
     * 1. Deactivate HFP device.
     * 2. Verify LE Audio service does not remove active device.
     * </pre>
     */
    @Test
    public void hfpDeactivated_makeSureToNotRemoveLeAudioDevice() {
        headsetActiveDeviceChanged(null);
        mTestLooper.dispatchAll();
        verify(mLeAudioService, never()).removeActiveDevice(anyBoolean());
    }

    /**
     * A2DP activated while activating A2DP Headset.
     *
     * <pre>
     * Steps:
     * 1. Connect A2DP device.
     * 2. Connect A2DP+HFP device.
     * 3. Activate HFP on A2DP+HFP device.
     * 4. Verify A2DP service is called to setActiveDevice.
     * 5. Activate A2DP on A2DP device.
     * 6. Verify Headset service does not set device as active.
     * 7. Activate A2DP on A2DP+HFP device.
     * 8. Verify Headset service does not set device as active.
     * </pre>
     */
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

    /**
     * A Headset device with connecting audio policy set to NOT ALLOWED gets connected. It should
     * not be set as active.
     *
     * <pre>
     * Steps:
     * 1. Set connecting policy to NOT ALLOWED for Headset device.
     * 2. Connect Headset device.
     * 3. Verify Headset service does not set device as active.
     * </pre>
     */
    @Test
    public void notAllowedConnectingPolicyHeadsetConnected_noSetActiveDevice() {
        // setting connecting policy to NOT ALLOWED
        doReturn(
                        new BluetoothSinkAudioPolicy.Builder()
                                .setCallEstablishPolicy(BluetoothSinkAudioPolicy.POLICY_ALLOWED)
                                .setActiveDevicePolicyAfterConnection(
                                        BluetoothSinkAudioPolicy.POLICY_NOT_ALLOWED)
                                .setInBandRingtonePolicy(BluetoothSinkAudioPolicy.POLICY_ALLOWED)
                                .build())
                .when(mHeadsetService)
                .getHfpCallAudioPolicy(mHeadsetDevice);

        headsetConnected(mHeadsetDevice, false);
        mTestLooper.dispatchAll();
        verify(mHeadsetService, never()).setActiveDevice(mHeadsetDevice);
    }

    /**
     * Two Hearing Aid devices connected with the same HiSyncId.
     *
     * <pre>
     * Steps:
     * 1. Connect first Hearing Aid device.
     * 2. Connect second Hearing Aid device with same HiSyncId.
     * 3. Verify Hearing Aid service is called to set first device as active.
     * 4. Verify Hearing Aid service does not set second device as active.
     * </pre>
     */
    @Test
    public void twoHearingAidDevicesConnected_WithTheSameHiSyncId() {
        Assume.assumeTrue(
                "Ignore test when HearingAidService is not enabled", HearingAidService.isEnabled());

        doReturn(HEARING_AID_HI_SYNC_ID)
                .when(mHearingAidService)
                .getHiSyncId(mSecondaryAudioDevice);

        hearingAidConnected(mHearingAidDevice);
        hearingAidConnected(mSecondaryAudioDevice);
        mTestLooper.dispatchAll();
        verify(mHearingAidService).setActiveDevice(mHearingAidDevice);
        verify(mHearingAidService, never()).setActiveDevice(mSecondaryAudioDevice);
    }

    /**
     * A2DP + Headset device is connected. Active device is changed to a Hearing Aid.
     *
     * <pre>
     * Steps:
     * 1. Connect A2DP+HFP device.
     * 2. Hearing Aid device becomes active.
     * 3. Verify A2DP service removes active device.
     * 4. Verify Headset service is called to set active device to null.
     * </pre>
     */
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

    /**
     * A Hearing Aid is connected. Then a A2DP + Headset device is connected.
     *
     * <pre>
     * Steps:
     * 1. Connect Hearing Aid device and set as active.
     * 2. Connect A2DP+HFP device.
     * 3. Verify A2DP service does not set device as active.
     * 4. Verify Headset service does not set device as active.
     * </pre>
     */
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

    /**
     * A Hearing Aid is currently active. Then an A2DP active device is explicitly set.
     *
     * <pre>
     * Steps:
     * 1. Hearing Aid is active.
     * 2. Connect A2DP device.
     * 3. Explicitly set A2DP device as active.
     * 4. Verify Hearing Aid service removes active device.
     * 5. Verify A2DP active device is set.
     * </pre>
     */
    @Test
    public void hearingAidActive_setA2dpActiveExplicitly() {
        doReturn(true).when(mHearingAidService).removeActiveDevice(anyBoolean());

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

    /**
     * A Hearing Aid is currently active. Then a Headset active device is explicitly set.
     *
     * <pre>
     * Steps:
     * 1. Hearing Aid is active.
     * 2. Connect Headset device.
     * 3. Explicitly set Headset device as active.
     * 4. Verify Hearing Aid service removes active device.
     * 5. Verify Headset active device is set.
     * </pre>
     */
    @Test
    public void hearingAidActive_setHeadsetActiveExplicitly() {
        doReturn(true).when(mHearingAidService).removeActiveDevice(anyBoolean());

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

    /**
     * Hearing Aid active device set to null. Clear Hearing Aid active devices.
     *
     * <pre>
     * Steps:
     * 1. Set Hearing Aid active device to null.
     * 2. Verify Hearing Aid active devices list is empty.
     * </pre>
     */
    @Test
    public void hearingAidActiveWithNull_clearHearingAidActiveDevices() {
        hearingAidActiveDeviceChanged(null);
        mTestLooper.dispatchAll();
        assertThat(mActiveDeviceManager.getHearingAidActiveDevices()).isEmpty();
    }

    /**
     * One LE Audio is connected.
     *
     * <pre>
     * Steps:
     * 1. Connect LE Audio device.
     * 2. Verify LE Audio service is called to setActiveDevice.
     * </pre>
     */
    @Test
    public void onlyLeAudioConnected_setHeadsetActive() {
        leAudioConnected(mLeAudioDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice);
    }

    /**
     * LE Audio is connected but is not ready for stream (no available context types).
     *
     * <pre>
     * Steps:
     * 1. Set LE Audio group not available for stream.
     * 2. Connect LE Audio device.
     * 3. Verify LE Audio service does not set device as active.
     * </pre>
     */
    @Test
    public void leAudioConnected_notReadyForStream() {
        doReturn(false).when(mLeAudioService).isGroupAvailableForStream(anyInt());
        leAudioConnected(mLeAudioDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService, never()).setActiveDevice(mLeAudioDevice);
    }

    /**
     * LE Audio is connected but is not ready for stream. Check if it's not used as fallback device
     * from A2DP.
     *
     * <pre>
     * Steps:
     * 1. Set LE Audio group not available for stream.
     * 2. Connect LE Audio device.
     * 3. Connect A2DP device.
     * 4. Disconnect A2DP device.
     * 5. Verify LE Audio service does not set device as active.
     * </pre>
     */
    @Test
    public void leAudioFallbackA2dpToLeaudio_notReadyForStream() {
        doReturn(false).when(mLeAudioService).isGroupAvailableForStream(anyInt());
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
     * becomes autonomously inactive. Check if fallback set previous device as active.
     *
     * <pre>
     * Steps:
     * 1. Connect two LE Audio devices (different groups).
     * 2. Active device becomes autonomously inactive.
     * 3. Verify LE Audio service is called to set previous device as active.
     * </pre>
     */
    @Test
    @EnableFlags(Flags.FLAG_ADM_FIX_FALLBACK_DEVICE_SEARCH)
    public void leAudioFallbackLeaudioToLeaudio_autonomousInactive() {
        /* LeAudio device from group 1 - not ready for stream */
        doReturn(1).when(mLeAudioService).getGroupId(mLeAudioDevice);
        /* LeAudio device from group 1 - ready for stream */
        doReturn(2).when(mLeAudioService).getGroupId(mLeAudioDevice2);
        doReturn(true).when(mLeAudioService).isGroupAvailableForStream(1);
        doReturn(true).when(mLeAudioService).isGroupAvailableForStream(2);
        leAudioConnected(mLeAudioDevice);
        leAudioConnected(mLeAudioDevice2);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice);
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice2);

        /* Active device autonomously inactivates */
        mActiveDeviceManager.profileActiveDeviceChanged(BluetoothProfile.LE_AUDIO, null);
        Mockito.clearInvocations(mLeAudioService);
        /* LeAudio device from group 1 - not ready for stream */
        doReturn(1).when(mLeAudioService).getGroupId(mLeAudioDevice);
        /* LeAudio device from group 1 - ready for stream */
        doReturn(2).when(mLeAudioService).getGroupId(mLeAudioDevice2);
        doReturn(true).when(mLeAudioService).isGroupAvailableForStream(1);
        doReturn(true).when(mLeAudioService).isGroupAvailableForStream(2);

        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice);
    }

    /**
     * LE Audio is connected but is not ready for stream. Check if it's not used as fallback device
     * from LE Audio.
     *
     * <pre>
     * Steps:
     * 1. Connect two LE Audio devices (different groups), one not ready for stream.
     * 2. Disconnect the one ready for stream.
     * 3. Verify LE Audio service does not set the other device as active.
     * </pre>
     */
    @Test
    public void leAudioFallbackLeaudioToLeaudio_notReadyForStream() {
        /* LeAudio device from group 1 - not ready for stream */
        doReturn(1).when(mLeAudioService).getGroupId(mLeAudioDevice);
        /* LeAudio device from group 1 - ready for stream */
        doReturn(2).when(mLeAudioService).getGroupId(mLeAudioDevice2);
        doReturn(false).when(mLeAudioService).isGroupAvailableForStream(1);
        doReturn(true).when(mLeAudioService).isGroupAvailableForStream(2);
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
     * LE Audio is connected but is not ready for stream. Check if it's not used as fallback device
     * from ASHA.
     *
     * <pre>
     * Steps:
     * 1. Set LE Audio group not available for stream.
     * 2. Connect LE Audio device.
     * 3. Connect Hearing Aid device.
     * 4. Disconnect Hearing Aid device.
     * 5. Verify LE Audio service does not set device as active.
     * </pre>
     */
    @Test
    public void leAudioFallbackAshaToLeaudio_notReadyForStream() {
        doReturn(false).when(mLeAudioService).isGroupAvailableForStream(anyInt());

        leAudioConnected(mLeAudioDevice);
        hearingAidConnected(mHearingAidDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService, never()).setActiveDevice(mLeAudioDevice);
        verify(mHearingAidService).setActiveDevice(mHearingAidDevice);

        hearingAidDisconnected(mHearingAidDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService, never()).setActiveDevice(mLeAudioDevice);
    }

    /**
     * Two LE Audio are connected. Should set the second one active.
     *
     * <pre>
     * Steps:
     * 1. Connect first LE Audio device.
     * 2. Connect second LE Audio device.
     * 3. Verify LE Audio service is called to set second device as active.
     * </pre>
     */
    @Test
    public void secondLeAudioConnected_setSecondLeAudioActive() {
        leAudioConnected(mLeAudioDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice);

        leAudioConnected(mLeAudioDevice2);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice2);
    }

    /**
     * One LE Audio is connected and disconnected later. Should then set active device to null.
     *
     * <pre>
     * Steps:
     * 1. Connect LE Audio device.
     * 2. Disconnect LE Audio device.
     * 3. Verify that ActiveDeviceManager doesn't remove active device from service - service
     *    should do that itself, call would be redundant
     * 4. Verify LE Audio service is informed about disconnection.
     * </pre>
     */
    @Test
    public void lastLeAudioDisconnected_clearLeAudioActive() {
        doReturn(1).when(mLeAudioService).getGroupId(mLeAudioDevice);
        doReturn(mLeAudioDevice).when(mLeAudioService).getLeadDevice(mLeAudioDevice);

        leAudioConnected(mLeAudioDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice);

        leAudioDisconnected(mLeAudioDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService, never()).removeActiveDevice(anyBoolean());
        verify(mLeAudioService).deviceDisconnected(mLeAudioDevice, false);
    }

    /**
     * Two LE Audio are connected and active device is explicitly set.
     *
     * <pre>
     * Steps:
     * 1. Connect first LE Audio device.
     * 2. Connect second LE Audio device.
     * 3. Explicitly set first LE Audio device as active.
     * 4. Verify LE Audio active device is set.
     * </pre>
     */
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
     *
     * <pre>
     * Steps:
     * 1. Connect first LE Audio device.
     * 2. Connect second LE Audio device.
     * 3. Disconnect second LE Audio device.
     * 4. Verify LE Audio service is called to set first device as active.
     * </pre>
     */
    @Test
    public void leAudioSecondDeviceDisconnected_fallbackDeviceActive() {
        doReturn(1).when(mLeAudioService).getGroupId(mLeAudioDevice);
        doReturn(2).when(mLeAudioService).getGroupId(mLeAudioDevice2);
        doReturn(mLeAudioDevice).when(mLeAudioService).getLeadDevice(mLeAudioDevice);
        doReturn(mLeAudioDevice2).when(mLeAudioService).getLeadDevice(mLeAudioDevice2);

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
     *
     * <pre>
     * Steps:
     * 1. Connect first device from Set A.
     * 2. Connect first device from Set B.
     * 3. Connect second device from Set B.
     * 4. Connect second device from Set A.
     * 5. Verify LE Audio service does not set second device from Set A as active.
     * </pre>
     */
    @Test
    public void leAudioSecondDeviceConnectedAfterOtherGroupConnected_setActive() {
        doReturn(1).when(mLeAudioService).getGroupId(mLeAudioDevice);
        doReturn(1).when(mLeAudioService).getGroupId(mLeAudioDevice2);
        doReturn(2).when(mLeAudioService).getGroupId(mLeAudioDevice3);
        doReturn(2).when(mLeAudioService).getGroupId(mLeAudioDevice4);
        doReturn(mLeAudioDevice).when(mLeAudioService).getLeadDevice(mLeAudioDevice);
        doReturn(mLeAudioDevice).when(mLeAudioService).getLeadDevice(mLeAudioDevice2);
        doReturn(mLeAudioDevice3).when(mLeAudioService).getLeadDevice(mLeAudioDevice3);
        doReturn(mLeAudioDevice3).when(mLeAudioService).getLeadDevice(mLeAudioDevice4);

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
     * Verify set active device calls when two sets connect in parallel.
     *
     * <pre>
     * Steps:
     * 1. There are two LE Audio Sets: A and B with devices in them: A = {1, 2}, B={3, 4}.
     * 2. Devices connect:
     *    i) device 1 - set active device is called
     *    ii) device 3 - set active device is called
     *    iii) devices 2 - set active device is not called
     *    iv) device 4 - set active device is not called
     * 3. After this operation Set B={3, 4} remains Active as it was connected as the second set.
     * </pre>
     */
    @Test
    public void leAudioTwoSetsAlternatingConnections_setActive() {
        doReturn(1).when(mLeAudioService).getGroupId(mLeAudioDevice);
        doReturn(1).when(mLeAudioService).getGroupId(mLeAudioDevice2);
        doReturn(2).when(mLeAudioService).getGroupId(mLeAudioDevice3);
        doReturn(2).when(mLeAudioService).getGroupId(mLeAudioDevice4);
        doReturn(mLeAudioDevice).when(mLeAudioService).getLeadDevice(mLeAudioDevice);
        doReturn(mLeAudioDevice).when(mLeAudioService).getLeadDevice(mLeAudioDevice2);
        doReturn(mLeAudioDevice3).when(mLeAudioService).getLeadDevice(mLeAudioDevice3);
        doReturn(mLeAudioDevice3).when(mLeAudioService).getLeadDevice(mLeAudioDevice4);

        InOrder order = inOrder(mLeAudioService);
        leAudioConnected(mLeAudioDevice);
        mTestLooper.dispatchAll();
        order.verify(mLeAudioService).setActiveDevice(mLeAudioDevice);

        leAudioConnected(mLeAudioDevice3);
        mTestLooper.dispatchAll();
        order.verify(mLeAudioService).setActiveDevice(mLeAudioDevice3);

        leAudioConnected(mLeAudioDevice2);
        mTestLooper.dispatchAll();
        order.verify(mLeAudioService, never()).setActiveDevice(mLeAudioDevice4);

        leAudioConnected(mLeAudioDevice4);
        mTestLooper.dispatchAll();
        order.verify(mLeAudioService, never()).setActiveDevice(mLeAudioDevice2);
    }

    /**
     * In NORMAL mode, one LE Audio set, containing two buds, is connected. When one device got
     * disconnected fallback mechanism should not be triggered, and active device should not be
     * changed.
     *
     * <pre>
     * Steps:
     * 1. Set audio mode to NORMAL.
     * 2. Connect first device of the set.
     * 3. Connect second device of the set.
     * 4. Disconnect second device.
     * 5. Verify LE Audio service does not set any device as active (fallback).
     * </pre>
     */
    @Test
    public void leAudioSecondDeviceDisconnected_noFallbackDeviceActive_ModeNormal() {
        doReturn(AudioManager.MODE_NORMAL).when(mAudioManager).getMode();

        InOrder order = inOrder(mLeAudioService);

        int groupId = 1;
        List<BluetoothDevice> groupDevices = List.of(mLeAudioDevice, mLeAudioDevice2);
        doReturn(mLeAudioDevice).when(mLeAudioService).getLeadDevice(mLeAudioDevice2);

        leAudioConnected(mLeAudioDevice);
        mTestLooper.dispatchAll();
        order.verify(mLeAudioService, times(1)).setActiveDevice(mLeAudioDevice);

        leAudioConnected(mLeAudioDevice2);
        mTestLooper.dispatchAll();
        order.verify(mLeAudioService, never()).setActiveDevice(any());

        doReturn(groupId).when(mLeAudioService).getGroupId(any());
        doReturn(groupDevices).when(mLeAudioService).getGroupDevices(groupId);

        leAudioDisconnected(mLeAudioDevice2);
        mTestLooper.dispatchAll();
        order.verify(mLeAudioService, never()).setActiveDevice(any());
    }

    /**
     * In IN_CALL mode, one LE Audio set, containing two buds, is connected. When one device got
     * disconnected fallback mechanism should not be triggered, and active device should not be
     * changed.
     *
     * <pre>
     * Steps:
     * 1. Set audio mode to IN_CALL.
     * 2. Connect first device of the set.
     * 3. Connect second device of the set.
     * 4. Disconnect second device.
     * 5. Verify LE Audio service does not set any device as active (fallback).
     * </pre>
     */
    @Test
    public void leAudioSecondDeviceDisconnected_noFallbackDeviceActive_ModeInCall() {
        doReturn(AudioManager.MODE_IN_CALL).when(mAudioManager).getMode();

        InOrder order = inOrder(mLeAudioService);

        int groupId = 1;
        List<BluetoothDevice> groupDevices = List.of(mLeAudioDevice, mLeAudioDevice2);
        doReturn(mLeAudioDevice).when(mLeAudioService).getLeadDevice(mLeAudioDevice2);

        leAudioConnected(mLeAudioDevice);
        mTestLooper.dispatchAll();
        order.verify(mLeAudioService, times(1)).setActiveDevice(mLeAudioDevice);

        leAudioConnected(mLeAudioDevice2);
        mTestLooper.dispatchAll();
        order.verify(mLeAudioService, never()).setActiveDevice(any());

        doReturn(groupId).when(mLeAudioService).getGroupId(any());
        doReturn(groupDevices).when(mLeAudioService).getGroupDevices(groupId);

        leAudioDisconnected(mLeAudioDevice2);
        mTestLooper.dispatchAll();
        order.verify(mLeAudioService, never()).setActiveDevice(any());
    }

    /**
     * In NORMAL mode, two LE Audio sets are connected. One set disconnected. Fallback to another
     * one.
     *
     * <pre>
     * Steps:
     * 1. Set audio mode to NORMAL.
     * 2. Connect device from Set 2.
     * 3. Connect device from Set 1.
     * 4. Connect another device from Set 1.
     * 5. Disconnect device from Set 1.
     * 6. Disconnect another device from Set 1.
     * 7. Verify LE Audio service is called to set device from Set 2 as active.
     * </pre>
     */
    @Test
    public void twoLeAudioSets_OneSetDisconnected_FallbackToAnotherOne_ModeNormal() {
        doReturn(AudioManager.MODE_NORMAL).when(mAudioManager).getMode();

        InOrder order = inOrder(mLeAudioService);

        int groupId = 1;
        List<BluetoothDevice> groupDevices = List.of(mLeAudioDevice, mLeAudioDevice2);

        doReturn(mLeAudioDevice).when(mLeAudioService).getLeadDevice(mLeAudioDevice2);
        doReturn(groupId).when(mLeAudioService).getGroupId(mLeAudioDevice);
        doReturn(groupId).when(mLeAudioService).getGroupId(mLeAudioDevice2);
        doReturn(groupDevices).when(mLeAudioService).getGroupDevices(groupId);

        int groupId2 = 2;
        List<BluetoothDevice> groupDevicesId2 = List.of(mLeAudioDevice3);

        doReturn(groupId2).when(mLeAudioService).getGroupId(mLeAudioDevice3);
        doReturn(groupDevicesId2).when(mLeAudioService).getGroupDevices(groupId2);

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
     * In NORMAL mode, two LE Audio sets are connected. One set disconnected. Fallback to another
     * one.
     *
     * <pre>
     * Steps:
     * 1. Set audio mode to IN_CALL.
     * 2. Connect device from Set 2.
     * 3. Connect device from Set 1.
     * 4. Connect another device from Set 1.
     * 5. Disconnect device from Set 1.
     * 6. Disconnect another device from Set 1.
     * 7. Verify LE Audio service is called to set device from Set 2 as active.
     * </pre>
     */
    @Test
    public void twoLeAudioSets_OneSetDisconnected_FallbackToAnotherOne_ModeInCall() {
        doReturn(AudioManager.MODE_IN_CALL).when(mAudioManager).getMode();

        InOrder order = inOrder(mLeAudioService);

        int groupId = 1;
        List<BluetoothDevice> groupDevices = List.of(mLeAudioDevice, mLeAudioDevice2);

        doReturn(mLeAudioDevice).when(mLeAudioService).getLeadDevice(mLeAudioDevice2);
        doReturn(groupId).when(mLeAudioService).getGroupId(mLeAudioDevice);
        doReturn(groupId).when(mLeAudioService).getGroupId(mLeAudioDevice2);
        doReturn(groupDevices).when(mLeAudioService).getGroupDevices(groupId);

        int groupId2 = 2;
        List<BluetoothDevice> groupDevicesId2 = List.of(mLeAudioDevice3);

        doReturn(groupId2).when(mLeAudioService).getGroupId(mLeAudioDevice3);
        doReturn(groupDevicesId2).when(mLeAudioService).getGroupDevices(groupId2);

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

    /**
     * A2DP + Headset device is connected. Then an LE Audio is connected.
     *
     * <pre>
     * Steps:
     * 1. Connect A2DP+HFP device.
     * 2. Connect LE Audio device.
     * 3. Verify LE Audio service is called to setActiveDevice.
     * 4. Verify A2DP service removes active device.
     * 5. Verify Headset service is called to set active device to null.
     * </pre>
     */
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

    /**
     * An LE Audio device is connected. Then a A2DP + Headset device is connected.
     *
     * <pre>
     * Steps:
     * 1. Connect LE Audio device.
     * 2. Connect A2DP+HFP device.
     * 3. Verify A2DP service is called to setActiveDevice.
     * 4. Verify Headset service is called to setActiveDevice.
     * </pre>
     */
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

    /**
     * An LE Audio device is connected. Then an A2DP active device is explicitly set.
     *
     * <pre>
     * Steps:
     * 1. Connect LE Audio device.
     * 2. Connect A2DP device.
     * 3. Explicitly set A2DP device as active.
     * 4. Verify LE Audio service removes active device.
     * 5. Verify A2DP service is called to setActiveDevice.
     * </pre>
     */
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

    /**
     * An LE Audio device is connected. Then a Headset active device is explicitly set.
     *
     * <pre>
     * Steps:
     * 1. Connect LE Audio device.
     * 2. Connect Headset device.
     * 3. Explicitly set Headset device as active.
     * 4. Verify LE Audio service removes active device.
     * 5. Verify Headset service is called to setActiveDevice.
     * </pre>
     */
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
     * An LE Audio device connected. A A2DP device is connected. The A2DP device is disconnected.
     * Then the LE Audio devices should be the active one.
     *
     * <pre>
     * Steps:
     * 1. Connect LE Audio device.
     * 2. Connect A2DP device.
     * 3. Disconnect A2DP device.
     * 4. Verify LE Audio service is called to setActiveDevice.
     * </pre>
     */
    @Test
    public void leAudioAndA2dpConnectedThenA2dpDisconnected_fallbackToLeAudio() {
        doReturn(AudioManager.MODE_NORMAL).when(mAudioManager).getMode();
        doReturn(1).when(mLeAudioService).getGroupId(mLeAudioDevice);

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
     * An LE Audio set is connected. The not active bud is disconnected. Then the active device
     * should not change and hasFallback should be set to false.
     *
     * <pre>
     * Steps:
     * 1. Connect first LE Audio device.
     * 2. Connect second LE Audio device.
     * 3. Disconnect first LE Audio device.
     * 4. Verify LE Audio service does not remove active device.
     * </pre>
     */
    @Test
    public void leAudioSetConnectedThenNotActiveOneDisconnected_noFallback() {
        doReturn(AudioManager.MODE_NORMAL).when(mAudioManager).getMode();

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
     * An LE Audio set is connected. The active bud is disconnected. Active device manager should
     * not choose other set member as active device.
     *
     * <pre>
     * Steps:
     * 1. Connect first LE Audio device.
     * 2. Connect second LE Audio device.
     * 3. Disconnect second LE Audio device.
     * 4. Verify LE Audio service does not set other device as active.
     * </pre>
     */
    @Test
    public void leAudioSetConnectedThenActiveOneDisconnected_noFallback() {
        doReturn(AudioManager.MODE_NORMAL).when(mAudioManager).getMode();
        doReturn(mLeAudioDevice).when(mLeAudioService).getLeadDevice(any());
        doReturn(List.of(mLeAudioDevice, mLeAudioDevice2))
                .when(mLeAudioService)
                .getGroupDevices(mLeAudioDevice);
        doReturn(1).when(mLeAudioService).getGroupId(any());

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

    /**
     * An LE Audio set connected. Group is disconnected. No fallback.
     *
     * <pre>
     * Steps:
     * 1. Connect first LE Audio device.
     * 2. Connect second LE Audio device.
     * 3. Disconnect second LE Audio device.
     * 4. Disconnect first LE Audio device.
     * 5. Verify LE Audio service does not set any device as active.
     * </pre>
     */
    @Test
    public void leAudioSetConnectedGroupThenDisconnected_noFallback() {
        doReturn(AudioManager.MODE_NORMAL).when(mAudioManager).getMode();

        doReturn(1).when(mLeAudioService).getGroupId(mLeAudioDevice);
        doReturn(1).when(mLeAudioService).getGroupId(mLeAudioDevice2);
        doReturn(mLeAudioDevice).when(mLeAudioService).getLeadDevice(mLeAudioDevice2);
        doReturn(mLeAudioDevice).when(mLeAudioService).getLeadDevice(mLeAudioDevice);
        doReturn(List.of(mLeAudioDevice, mLeAudioDevice2))
                .when(mLeAudioService)
                .getGroupDevices(mLeAudioDevice);

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
     * An A2DP is connected. An LE Audio is connected. The LE Audio gets disconnected. Then the A2DP
     * should be the active one.
     *
     * <pre>
     * Steps:
     * 1. Connect A2DP device.
     * 2. Connect LE Audio device.
     * 3. Disconnect LE Audio device.
     * 4. Verify A2DP service is called to setActiveDevice.
     * </pre>
     */
    @Test
    public void a2dpAndLeAudioConnectedThenLeAudioDisconnected_fallbackToA2dp() {
        doReturn(AudioManager.MODE_NORMAL).when(mAudioManager).getMode();

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
     * An ASHA device connected and set to active. Same device connected as an LE Audio device. ASHA
     * disconnects with no fallback and LE Audio is set to active. New LE Audio device is connected
     * and selected as active. First LE Audio device disconnects with fallback to new one.
     *
     * <pre>
     * Steps:
     * 1. Connect ASHA device.
     * 2. Disconnect ASHA device.
     * 3. Connect same device as LE Audio.
     * 4. Connect new LE Audio device.
     * 5. Disconnect first LE Audio device.
     * 6. Verify LE Audio service is called to set new device as active.
     * </pre>
     */
    @Test
    public void sameDeviceAsAshaAndLeAudio_noFallbackOnSwitch() {
        /* Dual mode ASHA/LeAudio device from group 1 */
        doReturn(1).when(mLeAudioService).getGroupId(mDualModeHearingAidDevice);
        /* Different LeAudio only device from group 2 */
        doReturn(2).when(mLeAudioService).getGroupId(mLeAudioDevice);

        doReturn(AudioManager.MODE_NORMAL).when(mAudioManager).getMode();

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
     * An LE Audio device connected and set to active. Same device connected as an ASHA device. LE
     * Audio disconnects with no fallback and ASHA is set to active. New ASHA device is connected
     * and selected as active. First ASHA device disconnects with fallback to new one.
     *
     * <pre>
     * Steps:
     * 1. Connect LE Audio device.
     * 2. Connect same device as ASHA.
     * 3. Disconnect LE Audio device.
     * 4. Connect new ASHA device.
     * 5. Verify Hearing Aid service is called to set new device as active.
     * </pre>
     */
    @Test
    public void sameDeviceAsLeAudioAndAsha_noFallbackOnSwitch() {
        // Turn on the dual mode audio flag so the A2DP won't disconnect LE Audio
        doReturn(AudioManager.MODE_NORMAL).when(mAudioManager).getMode();
        List<BluetoothDevice> list = new ArrayList<>();
        doReturn(list).when(mLeAudioService).getActiveDevices();

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
     * A Dual mode device is active. New A2DP device connects. A2DP device is set as active. LE
     * Audio device is set as inactive.
     *
     * <pre>
     * Steps:
     * 1. Enable dual mode audio.
     * 2. Connect A2DP device and set active.
     * 3. Connect Dual Mode device (LE Audio).
     * 4. Set A2DP device as active.
     * 5. Verify LE Audio service removes active device.
     * </pre>
     */
    @Test
    public void dualModeDeviceActive_newA2dpDeviceConnected() {
        /* Turn on the dual mode audio flag */
        Utils.setDualModeAudioStateForTesting(true);
        /* A2DP device connected and set as active*/
        a2dpConnected(mA2dpDevice, false);
        a2dpActiveDeviceChanged(mA2dpDevice);

        reset(mLeAudioService);
        doReturn(mDualModeAudioDevice).when(mLeAudioService).getLeadDevice(mDualModeAudioDevice);
        doReturn(true).when(mLeAudioService).isGroupAvailableForStream(anyInt());

        doReturn(false)
                .when(mAdapterService)
                .isAllSupportedClassicAudioProfilesActive(mDualModeAudioDevice);

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
     * A Dual mode device is active. New HFP device connects. HFP device is set as active. LE Audio
     * device is set as inactive.
     *
     * <pre>
     * Steps:
     * 1. Enable dual mode audio.
     * 2. Connect HFP device.
     * 3. Connect Dual Mode device (LE Audio).
     * 4. Set HFP device as active.
     * 5. Verify LE Audio service removes active device.
     * </pre>
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
        doReturn(mDualModeAudioDevice).when(mLeAudioService).getLeadDevice(mDualModeAudioDevice);
        doReturn(true).when(mLeAudioService).isGroupAvailableForStream(anyInt());

        doReturn(false)
                .when(mAdapterService)
                .isAllSupportedClassicAudioProfilesActive(mDualModeAudioDevice);

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
     *
     * <pre>
     * Steps:
     * 1. Connect first Hearing Aid device.
     * 2. Connect second Hearing Aid device.
     * 3. Disconnect second Hearing Aid device.
     * 4. Verify Hearing Aid service is called to set first device as active.
     * </pre>
     */
    @Test
    public void hearingAidSecondDeviceDisconnected_fallbackDeviceActive() {
        hearingAidConnected(mHearingAidDevice);
        mTestLooper.dispatchAll();
        verify(mHearingAidService).setActiveDevice(mHearingAidDevice);

        List<BluetoothDevice> connectedHearingAidDevices = new ArrayList<>();
        connectedHearingAidDevices.add(mSecondaryAudioDevice);
        doReturn(HEARING_AID_HI_SYNC_ID + 1)
                .when(mHearingAidService)
                .getHiSyncId(mSecondaryAudioDevice);
        doReturn(connectedHearingAidDevices)
                .when(mHearingAidService)
                .getConnectedPeerDevices(HEARING_AID_HI_SYNC_ID + 1);

        hearingAidConnected(mSecondaryAudioDevice);
        mTestLooper.dispatchAll();
        verify(mHearingAidService).setActiveDevice(mSecondaryAudioDevice);

        Mockito.clearInvocations(mHearingAidService);
        hearingAidDisconnected(mSecondaryAudioDevice);
        mTestLooper.dispatchAll();
        verify(mHearingAidService).setActiveDevice(mHearingAidDevice);
    }

    /**
     * Verifies that the ActiveDeviceManager handles a second connection event for the same Hearing
     * Aid device correctly.
     *
     * <pre>
     * Steps
     * 1. Hearing Aid device connected.
     * 2. Verify setActiveDevice is called.
     * 3. Connect the same Hearing Aid device again.
     * 4. Verify setActiveDevice is not called again.
     * </pre>
     */
    @Test
    public void hearingAidDeviceConnectsTwice_setActiveIsCalledOnce() {
        hearingAidConnected(mHearingAidDevice);
        mTestLooper.dispatchAll();
        verify(mHearingAidService).setActiveDevice(mHearingAidDevice);

        // Connect again
        hearingAidConnected(mHearingAidDevice);
        mTestLooper.dispatchAll();
        // setActiveDevice should not be called again
        verify(mHearingAidService, times(1)).setActiveDevice(mHearingAidDevice);
    }

    /**
     * Hearing aid is connected, but active device is HFP. When the active HFP device is
     * disconnected, the hearing aid should be the active one.
     *
     * <pre>
     * Steps
     * 1. Connect HA device. Verify it is active.
     * 2. Connect and activate HFP device. Verify HA is removed active and HFP is active.
     * 3. Disconnect HFP device.
     * 4. Verify fallback to HA (HA becomes active).
     * </pre>
     */
    @Test
    public void hfpDeviceDisconnected_fallbackToHearingAid() {
        doReturn(AudioManager.MODE_NORMAL).when(mAudioManager).getMode();
        doReturn(true).when(mHearingAidService).removeActiveDevice(anyBoolean());

        // Connect HA device
        hearingAidConnected(mHearingAidDevice);
        mTestLooper.dispatchAll();
        verify(mHearingAidService).setActiveDevice(mHearingAidDevice);

        Mockito.clearInvocations(mHearingAidService);

        // Connect and activate HFP device, which deactivates HA
        headsetConnected(mHeadsetDevice, false);
        headsetActiveDeviceChanged(mHeadsetDevice);
        mTestLooper.dispatchAll();
        verify(mHearingAidService).removeActiveDevice(false);
        assertThat(mActiveDeviceManager.getHfpActiveDevice()).isEqualTo(mHeadsetDevice);
        assertThat(mActiveDeviceManager.getHearingAidActiveDevices()).isEmpty();

        // Disconnect HFP device
        headsetDisconnected(mHeadsetDevice);
        mTestLooper.dispatchAll();

        // Verify fallback to HA
        verify(mHearingAidService).setActiveDevice(mHearingAidDevice);
        assertThat(mActiveDeviceManager.getHearingAidActiveDevices()).contains(mHearingAidDevice);
    }

    /**
     * Hearing aid is connected, but active device is a different device. When the active device is
     * disconnected, the hearing aid should be the active one.
     *
     * <pre>
     * Steps:
     * 1. Connect Hearing Aid device.
     * 2. Connect LE Audio device.
     * 3. Connect A2DP device and set active.
     * 4. Disconnect A2DP device.
     * 5. Verify Hearing Aid service is called to setActiveDevice.
     * </pre>
     */
    @Test
    public void activeDeviceDisconnected_fallbackToHearingAid() {
        doReturn(AudioManager.MODE_NORMAL).when(mAudioManager).getMode();
        doReturn(true).when(mHearingAidService).removeActiveDevice(anyBoolean());

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

    /**
     * One LE Hearing Aid is connected.
     *
     * <pre>
     * Steps:
     * 1. Connect LE Hearing Aid device.
     * 2. Connect LE Audio profile for LE Hearing Aid device.
     * 3. Verify LE Audio service is called to setActiveDevice.
     * </pre>
     */
    @Test
    public void onlyLeHearingAidConnected_setLeAudioActive() {
        leHearingAidConnected(mLeHearingAidDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService, never()).setActiveDevice(mLeHearingAidDevice);

        leAudioConnected(mLeHearingAidDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mLeHearingAidDevice);
    }

    /**
     * LE audio is connected after LE Hearing Aid device. Keep LE hearing Aid active.
     *
     * <pre>
     * Steps:
     * 1. Connect LE Hearing Aid device.
     * 2. Connect LE Audio profile for LE Hearing Aid device.
     * 3. Connect another LE Audio device.
     * 4. Verify LE Audio service does not set new device as active.
     * </pre>
     */
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
     *
     * <pre>
     * Steps:
     * 1. Connect Hearing Aid device.
     * 2. Connect LE Hearing Aid device.
     * 3. Connect A2DP device.
     * 4. Disconnect LE Hearing Aid device.
     * 5. Disconnect Hearing Aid device.
     * 6. Verify A2DP service is called to setActiveDevice.
     * </pre>
     */
    @Test
    public void activeDeviceChange_withHearingAidLeHearingAidAndA2dpDevices() {
        doReturn(AudioManager.MODE_NORMAL).when(mAudioManager).getMode();
        doReturn(true).when(mHearingAidService).removeActiveDevice(anyBoolean());

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
     * Fallback not triggered when device is pending active.
     *
     * <pre>
     * Steps:
     * 1. Connect Hearing Aid, LE Audio, and A2DP devices.
     * 2. Set A2DP as active.
     * 3. Set LE Audio as pending active.
     * 4. Disconnect A2DP device.
     * 5. Verify no fallback to Hearing Aid.
     * 6. Complete LE Audio activation.
     * 7. Verify LE Audio is active.
     * </pre>
     */
    @Test
    @EnableFlags(Flags.FLAG_ADM_SUSPEND_FALLBACK_DURING_CHANGE)
    public void fallbackNotTriggeredWhenDevicePendingActive() {
        // Three devices connected: A2DP active, ASHA as fallback and Le Audio
        hearingAidConnected(mHearingAidDevice);
        leAudioConnected(mLeAudioDevice);
        a2dpConnected(mA2dpDevice, false);
        hearingAidActiveDeviceChanged(null);
        leAudioActiveDeviceChanged(null);
        a2dpActiveDeviceChanged(mA2dpDevice);
        mTestLooper.dispatchAll();
        assertThat(mActiveDeviceManager.getA2dpActiveDevice()).isEqualTo(mA2dpDevice);
        Mockito.clearInvocations(mLeAudioService);
        Mockito.clearInvocations(mHearingAidService);
        Mockito.clearInvocations(mA2dpService);

        doReturn(mA2dpDevice).when(mA2dpService).getActiveDevice();

        // Set LE Audio device as active.
        doReturn(List.of(mHearingAidDevice)).when(mHearingAidService).getActiveDevices();
        mActiveDeviceManager.setActiveDevice(mLeAudioDevice, BluetoothAdapter.ACTIVE_DEVICE_ALL);

        // Simulate A2DP device disconnecting.
        leAudioDisconnected(mA2dpDevice);
        mTestLooper.dispatchAll();

        // Fallback should be prevented because mLeAudioDevice is pending to be active.
        // So, no other device should become active for A2DP.
        verify(mA2dpService, never()).setActiveDevice(any());
        verify(mHearingAidService, never()).setActiveDevice(any());

        // Now, let the LE Audio active device change happen.
        leAudioActiveDeviceChanged(mLeAudioDevice);
        mTestLooper.dispatchAll();
        assertThat(mActiveDeviceManager.getLeAudioActiveDevice()).isEqualTo(mLeAudioDevice);
    }

    /**
     * Fallback allowed when pending device disconnects.
     *
     * <pre>
     * Steps:
     * 1. Connect Hearing Aid, LE Audio, and A2DP devices.
     * 2. Set A2DP as active.
     * 3. Set LE Audio as pending active.
     * 4. Disconnect LE Audio device.
     * 5. Disconnect A2DP device.
     * 6. Verify fallback to Hearing Aid.
     * </pre>
     */
    @Test
    @EnableFlags(Flags.FLAG_ADM_SUSPEND_FALLBACK_DURING_CHANGE)
    public void fallbackAllowedWhenPendingDeviceDisconnects() {
        // Three devices connected: LE Audio active, ASHA as fallback and A2DP
        hearingAidConnected(mHearingAidDevice);
        leAudioConnected(mLeAudioDevice);
        a2dpConnected(mA2dpDevice, false);
        hearingAidActiveDeviceChanged(null);
        leAudioActiveDeviceChanged(null);

        // set A2DP as active device
        a2dpActiveDeviceChanged(mA2dpDevice);
        mTestLooper.dispatchAll();
        assertThat(mActiveDeviceManager.getA2dpActiveDevice()).isEqualTo(mA2dpDevice);
        Mockito.clearInvocations(mLeAudioService);
        Mockito.clearInvocations(mHearingAidService);
        Mockito.clearInvocations(mA2dpService);

        // Set LE Audio device as active.
        doReturn(List.of(mHearingAidDevice)).when(mHearingAidService).getActiveDevices();
        mActiveDeviceManager.setActiveDevice(mLeAudioDevice, BluetoothAdapter.ACTIVE_DEVICE_ALL);

        // LE Audio disconnects before becomes active
        leAudioDisconnected(mLeAudioDevice);

        // A2DP device (current active) disconnects
        a2dpDisconnected(mA2dpDevice);

        mTestLooper.dispatchAll();

        // Fall back to ASHA successful
        verify(mA2dpService, never()).setActiveDevice(any());
        verify(mLeAudioService).setActiveDevice(any());
        assertThat(mActiveDeviceManager.getHearingAidActiveDevices()).contains(mHearingAidDevice);
    }

    /**
     * Verifies that we mutually exclude classic audio profiles (A2DP & HFP) and LE Audio when the
     * dual mode feature is disabled.
     *
     * <pre>
     * Steps:
     * 1. Disable dual mode audio.
     * 2. Connect Dual Mode device (A2DP+HFP).
     * 3. Verify LE Audio service removes active device.
     * 4. Connect Dual Mode device (LE Audio).
     * 5. Verify A2DP and Headset services remove active device.
     * </pre>
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
     *
     * <pre>
     * Steps:
     * 1. Enable dual mode audio.
     * 2. Connect Dual Mode device (LE Audio).
     * 3. Verify LE Audio active device is not set (waiting for classic).
     * 4. Connect Dual Mode device (A2DP+HFP).
     * 5. Verify all profiles set device as active.
     * 6. Deactivate A2DP.
     * 7. Verify LE Audio active device is removed.
     * </pre>
     */
    @Test
    public void dualModeAudioDeviceConnected_withDualModeFeatureEnabled() {
        // Turn on the dual mode audio flag
        Utils.setDualModeAudioStateForTesting(true);
        reset(mLeAudioService);
        doReturn(mDualModeAudioDevice).when(mLeAudioService).getLeadDevice(mDualModeAudioDevice);
        doReturn(true).when(mLeAudioService).isGroupAvailableForStream(anyInt());

        doReturn(false)
                .when(mAdapterService)
                .isAllSupportedClassicAudioProfilesActive(mDualModeAudioDevice);

        leAudioConnected(mDualModeAudioDevice);
        mTestLooper.dispatchAll();
        // Verify setting LEA active fails when all supported classic audio profiles are not active
        verify(mLeAudioService).setActiveDevice(mDualModeAudioDevice);
        assertThat(mActiveDeviceManager.getLeAudioActiveDevice()).isNull();
        assertThat(mActiveDeviceManager.getA2dpActiveDevice()).isNull();
        assertThat(mActiveDeviceManager.getHfpActiveDevice()).isNull();

        doReturn(true).when(mLeAudioService).setActiveDevice(any());
        doReturn(true).when(mLeAudioService).removeActiveDevice(anyBoolean());
        doReturn(mDualModeAudioDevice).when(mLeAudioService).getLeadDevice(mDualModeAudioDevice);

        Mockito.clearInvocations(mLeAudioService);

        // Ensure we make LEA active after all supported classic profiles are active
        a2dpActiveDeviceChanged(mDualModeAudioDevice);
        mTestLooper.dispatchAll();

        doReturn(true)
                .when(mAdapterService)
                .isAllSupportedClassicAudioProfilesActive(mDualModeAudioDevice);
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
     * Test that active LE Audio device is cleared, when the only device in group disconnects.
     *
     * <pre>
     * Steps:
     * 1. LE Audio device is connected and becomes active.
     * 2. Device disconnects.
     * 3. Active device is set to null
     * </pre>
     */
    @Test
    @EnableFlags(Flags.FLAG_ADM_CENTRALIZE_ACTIVE_DEVICE_HANDLING)
    public void leAudioDeviceConnected_clearActiveWhenDisconnected() {
        leAudioConnected(mLeAudioDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice);

        leAudioActiveDeviceChanged(mLeAudioDevice);
        mTestLooper.dispatchAll();

        leAudioDisconnected(mLeAudioDevice);
        mTestLooper.dispatchAll();
        assertThat(mActiveDeviceManager.getLeAudioActiveDevice()).isNull();
    }

    /**
     * Test that active LE Audio device is cleared, only when all devices in group disconnect.
     *
     * <pre>
     * Steps:
     * 1. Two LE Audio devices are connected and lead device becomes active.
     * 2. Secondary device disconnects.
     * 3. Assert that device is still active.
     * 4. Lead device disconnects.
     * 5. Active device is set to null
     * </pre>
     */
    @Test
    @EnableFlags(Flags.FLAG_ADM_CENTRALIZE_ACTIVE_DEVICE_HANDLING)
    public void leAudioGroupConnected_clearActiveDeviceWhenAllDisconnected() {
        doReturn(mLeAudioDevice).when(mLeAudioService).getLeadDevice(mLeAudioDevice2);
        leAudioConnected(mLeAudioDevice);
        leAudioConnected(mLeAudioDevice2);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice);

        leAudioActiveDeviceChanged(mLeAudioDevice);
        mTestLooper.dispatchAll();

        leAudioDisconnected(mLeAudioDevice2);
        mTestLooper.dispatchAll();

        verify(mLeAudioService, never()).setActiveDevice(null);
        assertThat(mActiveDeviceManager.getLeAudioActiveDevice()).isEqualTo(mLeAudioDevice);

        Mockito.clearInvocations(mLeAudioService);

        leAudioDisconnected(mLeAudioDevice);
        mTestLooper.dispatchAll();
        assertThat(mActiveDeviceManager.getLeAudioActiveDevice()).isNull();
    }

    /**
     * Test that when LE Audio set disconnects with primary device disconnecting first, fallback
     * will be found after secondary device disconnects.
     *
     * <pre>
     * Steps:
     * 1. A2DP and two LE Audio devices are connected and lead LE Audio device becomes active.
     * 2. Lead device disconnects.
     * 3. Assert that device is still active.
     * 4. Secondary device disconnects.
     * 5. Active Device is removed.
     * </pre>
     */
    @Test
    @EnableFlags(Flags.FLAG_ADM_CENTRALIZE_ACTIVE_DEVICE_HANDLING)
    public void leAudioPrimaryDeviceDisconnectedFirst_fallbackToA2dp() {
        doReturn(mLeAudioDevice).when(mLeAudioService).getLeadDevice(mLeAudioDevice2);
        doReturn(List.of(mLeAudioDevice, mLeAudioDevice2))
                .when(mLeAudioService)
                .getGroupDevices(mLeAudioDevice);
        a2dpConnected(mA2dpDevice, false);
        leAudioConnected(mLeAudioDevice);
        leAudioConnected(mLeAudioDevice2);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice);

        Mockito.clearInvocations(mLeAudioService, mA2dpService);

        leAudioActiveDeviceChanged(mLeAudioDevice);
        mTestLooper.dispatchAll();

        a2dpActiveDeviceChanged(null);
        mTestLooper.dispatchAll();

        Mockito.clearInvocations(mLeAudioService, mHearingAidService);

        leAudioDisconnected(mLeAudioDevice);
        mTestLooper.dispatchAll();

        verify(mLeAudioService, never()).setActiveDevice(null);
        verify(mA2dpService, never()).setActiveDevice(mA2dpDevice);
        assertThat(mActiveDeviceManager.getLeAudioActiveDevice()).isEqualTo(mLeAudioDevice);

        Mockito.clearInvocations(mLeAudioService, mA2dpService);
        doReturn(mA2dpDevice).when(mA2dpService).getFallbackDevice();

        leAudioDisconnected(mLeAudioDevice2);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).removeActiveDevice(true);
        verify(mA2dpService).setActiveDevice(mA2dpDevice);
    }

    /**
     * Test that when LE Audio set disconnects with primary device disconnecting first, fallback
     * will be found after secondary device disconnects.
     *
     * <pre>
     * Steps:
     * 1. A2DP and two LE Audio devices are connected and lead LE Audio device becomes active.
     * 2. Lead device disconnects.
     * 3. Assert that device is still active.
     * 4. Secondary device disconnects.
     * 5. Active Device is removed.
     * </pre>
     */
    @Test
    @EnableFlags(Flags.FLAG_ADM_CENTRALIZE_ACTIVE_DEVICE_HANDLING)
    public void leAudioPrimaryDeviceDisconnectedFirst_fallbackToAsha() {
        doReturn(mLeAudioDevice).when(mLeAudioService).getLeadDevice(mLeAudioDevice2);
        doReturn(List.of(mLeAudioDevice, mLeAudioDevice2))
                .when(mLeAudioService)
                .getGroupDevices(mLeAudioDevice);
        hearingAidConnected(mHearingAidDevice);
        leAudioConnected(mLeAudioDevice);
        leAudioConnected(mLeAudioDevice2);
        mTestLooper.dispatchAll();

        Mockito.clearInvocations(mLeAudioService, mHearingAidService);

        leAudioActiveDeviceChanged(mLeAudioDevice);
        mTestLooper.dispatchAll();

        hearingAidActiveDeviceChanged(null);
        mTestLooper.dispatchAll();

        Mockito.clearInvocations(mLeAudioService, mHearingAidService);

        leAudioDisconnected(mLeAudioDevice);
        mTestLooper.dispatchAll();

        verify(mLeAudioService, never()).setActiveDevice(null);
        verify(mHearingAidService, never()).setActiveDevice(mHearingAidDevice);
        assertThat(mActiveDeviceManager.getLeAudioActiveDevice()).isEqualTo(mLeAudioDevice);

        Mockito.clearInvocations(mLeAudioService, mA2dpService);

        leAudioDisconnected(mLeAudioDevice2);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).removeActiveDevice(true);
        verify(mHearingAidService).setActiveDevice(mHearingAidDevice);
    }

    /**
     * Test that when LE Audio set disconnects with primary device disconnecting first, fallback
     * will be found after secondary device disconnects.
     *
     * <pre>
     * Steps:
     * 1. A2DP and two LE Audio devices are connected and lead LE Audio device becomes active.
     * 2. Lead device disconnects.
     * 3. Assert that device is still active.
     * 4. Secondary device disconnects.
     * 5. Active Device is removed.
     * </pre>
     */
    @Test
    @EnableFlags(Flags.FLAG_ADM_CENTRALIZE_ACTIVE_DEVICE_HANDLING)
    public void leAudioPrimaryDeviceDisconnectedFirst_fallbackToLeAudio() {
        doReturn(mLeAudioDevice).when(mLeAudioService).getLeadDevice(mLeAudioDevice2);
        doReturn(List.of(mLeAudioDevice, mLeAudioDevice2))
                .when(mLeAudioService)
                .getGroupDevices(mLeAudioDevice);
        leAudioConnected(mLeAudioDevice);
        leAudioConnected(mLeAudioDevice2);
        leAudioConnected(mLeAudioDevice3);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice);

        Mockito.clearInvocations(mLeAudioService);

        leAudioActiveDeviceChanged(mLeAudioDevice);
        mTestLooper.dispatchAll();

        Mockito.clearInvocations(mLeAudioService);
        doReturn(1).when(mLeAudioService).getGroupId(mLeAudioDevice);
        doReturn(1).when(mLeAudioService).getGroupId(mLeAudioDevice2);
        doReturn(2).when(mLeAudioService).getGroupId(mLeAudioDevice3);

        leAudioDisconnected(mLeAudioDevice);
        mTestLooper.dispatchAll();

        verify(mLeAudioService, never()).setActiveDevice(null);
        verify(mLeAudioService, never()).setActiveDevice(mLeAudioDevice3);
        assertThat(mActiveDeviceManager.getLeAudioActiveDevice()).isEqualTo(mLeAudioDevice);

        Mockito.clearInvocations(mLeAudioService);

        doReturn(1).when(mLeAudioService).getGroupId(mLeAudioDevice);
        doReturn(1).when(mLeAudioService).getGroupId(mLeAudioDevice2);
        doReturn(2).when(mLeAudioService).getGroupId(mLeAudioDevice3);

        leAudioDisconnected(mLeAudioDevice2);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice3);
    }

    /**
     * HFP device is connected. LE Audio device is connected. HFP is set to active. This should
     * remove LE Audio active device.
     *
     * <pre>
     * Steps:
     * 1. Disable dual mode audio.
     * 2. Connect Headset device.
     * 3. Connect LE Audio device.
     * 4. Set Headset device as active.
     * 5. Verify LE Audio service removes active device.
     * </pre>
     */
    @Test
    public void activeDeviceChange_withHeadsetAndLeAudioDevices() {
        Utils.setDualModeAudioStateForTesting(false);
        doReturn(AudioManager.MODE_NORMAL).when(mAudioManager).getMode();

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
     *
     * <pre>
     * Steps:
     * 1. Enable dual mode audio.
     * 2. Connect Headset device.
     * 3. Connect Dual Mode device.
     * 4. Set Headset device as active.
     * 5. Verify LE Audio service removes active device.
     * </pre>
     */
    @Test
    public void activeDeviceChange_withHeadsetAndDualModeAudioDevices() {
        Utils.setDualModeAudioStateForTesting(true);
        doReturn(AudioManager.MODE_NORMAL).when(mAudioManager).getMode();

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
     *
     * <pre>
     * Steps:
     * 1. Enable dual mode audio.
     * 2. Connect first Dual Mode device.
     * 3. Connect second Dual Mode device.
     * 4. Set first Dual Mode device as active (HFP).
     * 5. Verify LE Audio service is called to set first device as active.
     * </pre>
     */
    @Test
    public void activeDeviceChange_withTwoDualModeAudioDevices() {
        Utils.setDualModeAudioStateForTesting(true);
        doReturn(AudioManager.MODE_NORMAL).when(mAudioManager).getMode();
        doReturn(true)
                .when(mAdapterService)
                .isAllSupportedClassicAudioProfilesActive(mDualModeAudioDevice);
        doReturn(true)
                .when(mAdapterService)
                .isAllSupportedClassicAudioProfilesActive(mDualModeAudioDevice2);

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
     *
     * <pre>
     * Steps:
     * 1. Disable dual mode audio.
     * 2. Mock setActiveDevice to fail.
     * 3. Connect Dual Mode device (LE Audio).
     * 4. Set LE Audio active.
     * 5. Verify other profiles active devices are cleared.
     * 6. Connect A2DP device.
     * 7. Verify A2DP active device is set.
     * 8. Connect other devices and verify behavior.
     * </pre>
     */
    @Test
    public void setActiveDeviceFailsUponConnection() {
        Utils.setDualModeAudioStateForTesting(false);
        doReturn(false).when(mHeadsetService).setActiveDevice(any());
        doReturn(false).when(mA2dpService).setActiveDevice(any());
        doReturn(false).when(mHearingAidService).setActiveDevice(any());
        doReturn(false).when(mLeAudioService).setActiveDevice(any());

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
     * Verifies if Le Audio Broadcast is streaming, connected A2DP device should not be set as
     * active.
     *
     * <pre>
     * Steps:
     * 1. Mock Broadcast started.
     * 2. Connect A2DP device.
     * 3. Verify A2DP service does not set device as active.
     * </pre>
     */
    @Test
    public void a2dpConnectedWhenBroadcasting_notSetA2dpActive() {
        doReturn(true).when(mLeAudioService).isBroadcastStarted();
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
     *
     * <pre>
     * Steps:
     * 1. Mock Broadcast started.
     * 2. Connect Headset device.
     * 3. Verify Headset service does not set device as active.
     * </pre>
     */
    @Test
    public void headsetConnectedWhenBroadcasting_notSetHeadsetActive() {
        doReturn(true).when(mLeAudioService).isBroadcastStarted();
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
     *
     * <pre>
     * Steps:
     * 1. Mock Broadcast started.
     * 2. Connect Hearing Aid device.
     * 3. Verify Hearing Aid service does not set device as active.
     * </pre>
     */
    @Test
    public void hearingAidConnectedWhenBroadcasting_notSetHearingAidActive() {
        doReturn(true).when(mLeAudioService).isBroadcastStarted();
        hearingAidConnected(mHearingAidDevice);
        mTestLooper.dispatchAll();
        verify(mHearingAidService, never()).setActiveDevice(any());
    }

    /**
     * Verifies if Le Audio Broadcast is streaming, connected LE hearing aid device should not be
     * set as active.
     *
     * <pre>
     * Steps:
     * 1. Mock Broadcast started.
     * 2. Connect LE Hearing Aid device.
     * 3. Verify LE Audio service does not set device as active.
     * </pre>
     */
    @Test
    public void leHearingAidConnectedWhenBroadcasting_notSetLeHearingAidActive() {
        doReturn(true).when(mLeAudioService).isBroadcastStarted();
        leHearingAidConnected(mLeHearingAidDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService, never()).setActiveDevice(any());
    }

    /**
     * Hearing Aid connected. LE Audio set active. ASHA set inactive.
     *
     * <pre>
     * Steps:
     * 1. Connect LE Audio device.
     * 2. Connect Hearing Aid device.
     * 3. Verify Hearing Aid service is called to setActiveDevice.
     * 4. Verify LE Audio service removes active device.
     * 5. Set LE Audio device as active.
     * 6. Verify Hearing Aid service removes active device.
     * </pre>
     */
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

        doReturn(List.of(mHearingAidDevice)).when(mHearingAidService).getActiveDevices();
        mActiveDeviceManager.setActiveDevice(mLeAudioDevice, BluetoothAdapter.ACTIVE_DEVICE_ALL);
        mTestLooper.dispatchAll();
        verify(mHearingAidService).removeActiveDevice(false);
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice);
    }

    /**
     * Verifies that an Le Audio Unicast device is not treated as connected when an active device
     * change is received after the device has been disconnected.
     *
     * <pre>
     * Steps:
     * 1. Trigger LE Audio active device change (device not connected).
     * 2. Verify LE Audio service does not set device as active.
     * 3. Connect LE Audio device.
     * 4. Verify LE Audio service is called to setActiveDevice.
     * </pre>
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

    /**
     * Verifies that when connecting an LE hearing aid, if setting it as active fails, it is not
     * stored as the active device.
     *
     * <pre>
     * Steps:
     * 1. Mock LE Audio setActiveDevice to fail.
     * 2. Connect LE Hearing Aid device.
     * 3. Verify LE Audio service tried to set active.
     * 4. Verify ActiveDeviceManager does not have LE Audio active device.
     * </pre>
     */
    @Test
    @EnableFlags(Flags.FLAG_ADM_REMOVE_HAP_VARIABLES)
    public void leAudioConnected_hearingAid_setActiveFails() {
        // LE Audio service fails to set active device
        doReturn(false).when(mLeAudioService).setActiveDevice(mLeHearingAidDevice);

        // Connect LE Hearing Aid as LE Audio
        leAudioConnected(mLeHearingAidDevice);
        mTestLooper.dispatchAll();

        // Verify that we tried to set it active but it failed, and it's not the active device.
        verify(mLeAudioService).setActiveDevice(mLeHearingAidDevice);
        assertThat(mActiveDeviceManager.getLeAudioActiveDevice()).isNull();
    }

    /**
     * Verifies that if setting an LE Audio device as active fails during a fallback, it is not
     * stored as the active device.
     *
     * <pre>
     * Steps:
     * 1. Connect LE Audio device.
     * 2. Connect A2DP device.
     * 3. Mock LE Audio setActiveDevice to fail.
     * 4. Disconnect A2DP device.
     * 5. Verify LE Audio service tried to set active.
     * 6. Verify ActiveDeviceManager does not have LE Audio active device.
     * </pre>
     */
    @Test
    public void fallbackToLeAudio_setActiveFails() {
        doReturn(AudioManager.MODE_NORMAL).when(mAudioManager).getMode();
        doReturn(1).when(mLeAudioService).getGroupId(mLeAudioDevice);

        // Connect LE Audio device, but don't make it active
        leAudioConnected(mLeAudioDevice);
        leAudioActiveDeviceChanged(null);
        mTestLooper.dispatchAll();
        assertThat(mActiveDeviceManager.getLeAudioActiveDevice()).isNull();
        Mockito.clearInvocations(mLeAudioService);

        // Connect and disconnect A2DP device to trigger fallback
        a2dpConnected(mA2dpDevice, false);
        mTestLooper.dispatchAll();
        verify(mA2dpService).setActiveDevice(mA2dpDevice);

        // Make setActive fail for LE Audio
        doReturn(false).when(mLeAudioService).setActiveDevice(mLeAudioDevice);

        a2dpDisconnected(mA2dpDevice);
        mTestLooper.dispatchAll();

        // Verify that we tried to set LE Audio active but it failed.
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice);
        assertThat(mActiveDeviceManager.getLeAudioActiveDevice()).isNull();
    }

    /**
     * Test that {@link AudioManager.AudioDeviceCallback} for A2DP device type is handled correctly.
     *
     * <pre>.
     * Steps:
     * 1.  Simulate adding an A2DP audio device.
     * 2.  Verify {@link A2dpService#handleAudioDeviceAdded} is called.
     * 3.  Simulate removing the A2DP audio device.
     * 4.  Verify {@link A2dpService#handleAudioDeviceRemoved} is called.
     * </pre>
     */
    @Test
    @EnableFlags(Flags.FLAG_ADM_CENTRALIZE_ACTIVE_DEVICE_HANDLING)
    public void testAudioManagerCallback_a2dp() {
        // Prepare AudioDeviceInfo
        AudioDeviceInfo deviceInfo = Mockito.mock(AudioDeviceInfo.class);
        doReturn("00:11:22:33:44:55").when(deviceInfo).getAddress();
        doReturn(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP).when(deviceInfo).getType();

        doReturn(mA2dpDevice).when(mAdapterService).getDeviceFromByte(any());
        doReturn(true).when(mAdapterService).isAvailable();

        mAudioDeviceCallback.onAudioDevicesAdded(new AudioDeviceInfo[] {deviceInfo});
        mTestLooper.dispatchAll();
        verify(mA2dpService).handleAudioDeviceAdded(mA2dpDevice);

        mAudioDeviceCallback.onAudioDevicesRemoved(new AudioDeviceInfo[] {deviceInfo});
        mTestLooper.dispatchAll();
        verify(mA2dpService).handleAudioDeviceRemoved();
    }

    /**
     * Test that {@link AudioManager.AudioDeviceCallback} for Headset (SCO) device type is handled
     * correctly.
     *
     * <pre>
     * Steps:
     * 1.  Simulate adding a SCO audio device.
     * 2.  Verify {@link HeadsetService#handleAudioDeviceAdded} is called.
     * 3.  Simulate removing the SCO audio device.
     * 4.  Verify {@link HeadsetService#handleAudioDeviceRemoved} is called.
     * </pre>
     */
    @Test
    @EnableFlags(Flags.FLAG_ADM_CENTRALIZE_ACTIVE_DEVICE_HANDLING)
    public void testAudioManagerCallback_headset() {
        // Prepare AudioDeviceInfo
        AudioDeviceInfo deviceInfo = Mockito.mock(AudioDeviceInfo.class);
        doReturn("00:11:22:33:44:55").when(deviceInfo).getAddress();
        doReturn(AudioDeviceInfo.TYPE_BLUETOOTH_SCO).when(deviceInfo).getType();

        doReturn(mA2dpDevice).when(mAdapterService).getDeviceFromByte(any());
        doReturn(true).when(mAdapterService).isAvailable();

        mAudioDeviceCallback.onAudioDevicesAdded(new AudioDeviceInfo[] {deviceInfo});
        mTestLooper.dispatchAll();
        verify(mHeadsetService).handleAudioDeviceAdded(mA2dpDevice);

        mAudioDeviceCallback.onAudioDevicesRemoved(new AudioDeviceInfo[] {deviceInfo});
        mTestLooper.dispatchAll();
        verify(mHeadsetService).handleAudioDeviceRemoved(mA2dpDevice);
    }

    /**
     * Test that {@link AudioManager.AudioDeviceCallback} for Hearing Aid device type is handled
     * correctly.
     *
     * <pre>
     * Steps:
     * 1.  Simulate adding a Hearing Aid audio device.
     * 2.  Verify {@link HearingAidService#handleAudioDeviceAdded} is called.
     * 3.  Simulate removing the Hearing Aid audio device.
     * 4.  Verify {@link HearingAidService#handleAudioDeviceRemoved} is called.
     * </pre>
     */
    @Test
    @EnableFlags(Flags.FLAG_ADM_CENTRALIZE_ACTIVE_DEVICE_HANDLING)
    public void testAudioManagerCallback_hearingAid() {
        // Prepare AudioDeviceInfo
        AudioDeviceInfo deviceInfo = Mockito.mock(AudioDeviceInfo.class);
        doReturn("00:11:22:33:44:55").when(deviceInfo).getAddress();
        doReturn(AudioDeviceInfo.TYPE_HEARING_AID).when(deviceInfo).getType();

        doReturn(mHearingAidDevice).when(mAdapterService).getDeviceFromByte(any());
        doReturn(true).when(mAdapterService).isAvailable();

        mAudioDeviceCallback.onAudioDevicesAdded(new AudioDeviceInfo[] {deviceInfo});
        mTestLooper.dispatchAll();
        verify(mHearingAidService).handleAudioDeviceAdded();

        mAudioDeviceCallback.onAudioDevicesRemoved(new AudioDeviceInfo[] {deviceInfo});
        mTestLooper.dispatchAll();
        verify(mHearingAidService).handleAudioDeviceRemoved();
    }

    /**
     * Test that {@link AudioManager.AudioDeviceCallback} for BLE Headset device type is handled
     * correctly.
     *
     * <pre>
     * Steps:
     * 1.  Simulate adding a BLE Headset audio device.
     * 2.  Verify {@link LeAudioService#handleAudioDeviceAdded} is called with sink and source true.
     * 3.  Simulate removing the BLE Headset audio device.
     * 4.  Verify {@link LeAudioService#handleAudioDeviceRemoved} is called with sink and source
     *     true.
     * </pre>
     */
    @Test
    @EnableFlags(Flags.FLAG_ADM_CENTRALIZE_ACTIVE_DEVICE_HANDLING)
    public void testAudioManagerCallback_leAudioHeadset() {
        // Prepare AudioDeviceInfo
        AudioDeviceInfo deviceInfo = Mockito.mock(AudioDeviceInfo.class);
        doReturn("00:11:22:33:44:55").when(deviceInfo).getAddress();
        doReturn(AudioDeviceInfo.TYPE_BLE_HEADSET).when(deviceInfo).getType();
        doReturn(true).when(deviceInfo).isSink();
        doReturn(true).when(deviceInfo).isSource();

        doReturn(mLeAudioDevice).when(mAdapterService).getDeviceFromByte(any());
        doReturn(true).when(mAdapterService).isAvailable();

        mAudioDeviceCallback.onAudioDevicesAdded(new AudioDeviceInfo[] {deviceInfo});
        mTestLooper.dispatchAll();
        verify(mLeAudioService)
                .handleAudioDeviceAdded(
                        mLeAudioDevice, AudioDeviceInfo.TYPE_BLE_HEADSET, true, true);

        mAudioDeviceCallback.onAudioDevicesRemoved(new AudioDeviceInfo[] {deviceInfo});
        mTestLooper.dispatchAll();
        verify(mLeAudioService)
                .handleAudioDeviceRemoved(
                        mLeAudioDevice, AudioDeviceInfo.TYPE_BLE_HEADSET, true, true);
    }

    /**
     * Test that {@link AudioManager.AudioDeviceCallback} for BLE Speaker device type is handled
     * correctly.
     *
     * <pre>
     * Steps:
     * 1.  Simulate adding a BLE Speaker audio device.
     * 2.  Verify {@link LeAudioService#handleAudioDeviceAdded} is called with sink and source true.
     * 3.  Simulate removing the BLE Speaker audio device.
     * 4.  Verify {@link LeAudioService#handleAudioDeviceRemoved} is called with sink and source
     *     true.
     * </pre>
     */
    @Test
    @EnableFlags(Flags.FLAG_ADM_CENTRALIZE_ACTIVE_DEVICE_HANDLING)
    public void testAudioManagerCallback_leAudioSpeaker() {
        // Prepare AudioDeviceInfo
        AudioDeviceInfo deviceInfo = Mockito.mock(AudioDeviceInfo.class);
        doReturn("00:11:22:33:44:55").when(deviceInfo).getAddress();
        doReturn(AudioDeviceInfo.TYPE_BLE_SPEAKER).when(deviceInfo).getType();
        doReturn(true).when(deviceInfo).isSink();
        doReturn(true).when(deviceInfo).isSource();

        doReturn(mLeAudioDevice).when(mAdapterService).getDeviceFromByte(any());
        doReturn(true).when(mAdapterService).isAvailable();

        mAudioDeviceCallback.onAudioDevicesAdded(new AudioDeviceInfo[] {deviceInfo});
        mTestLooper.dispatchAll();
        verify(mLeAudioService)
                .handleAudioDeviceAdded(
                        mLeAudioDevice, AudioDeviceInfo.TYPE_BLE_SPEAKER, true, true);

        mAudioDeviceCallback.onAudioDevicesRemoved(new AudioDeviceInfo[] {deviceInfo});
        mTestLooper.dispatchAll();
        verify(mLeAudioService)
                .handleAudioDeviceRemoved(
                        mLeAudioDevice, AudioDeviceInfo.TYPE_BLE_SPEAKER, true, true);
    }

    /**
     * Set Hearing Aid device as active.
     *
     * <pre>
     * Steps:
     * 1. Connect Hearing Aid device.
     * 2. Set active device to null.
     * 3. Set Hearing Aid device as active.
     * 4. Verify Hearing Aid service is called to setActiveDevice.
     * 5. Verify ActiveDeviceManager does not have Hearing Aid active device yet.
     * 6. Hearing Aid active device changed.
     * 7. Verify ActiveDeviceManager has Hearing Aid active device.
     * </pre>
     */
    @Test
    public void setActiveDevice_hearingAid() {
        hearingAidConnected(mHearingAidDevice);
        hearingAidActiveDeviceChanged(null);
        mTestLooper.dispatchAll();
        Mockito.clearInvocations(mHearingAidService);

        mActiveDeviceManager.setActiveDevice(mHearingAidDevice, BluetoothAdapter.ACTIVE_DEVICE_ALL);
        mTestLooper.dispatchAll();
        verify(mHearingAidService).setActiveDevice(mHearingAidDevice);

        assertThat(mActiveDeviceManager.getHearingAidActiveDevices())
                .doesNotContain(mHearingAidDevice);
        Mockito.clearInvocations(mHearingAidService);

        hearingAidActiveDeviceChanged(mHearingAidDevice);
        mTestLooper.dispatchAll();
        assertThat(mActiveDeviceManager.getHearingAidActiveDevices()).contains(mHearingAidDevice);
    }

    /**
     * Set A2DP device as active.
     *
     * <pre>
     * Steps:
     * 1. Connect A2DP device.
     * 2. Set active device to null.
     * 3. Set A2DP device as active.
     * 4. Verify A2DP service is called to setActiveDevice.
     * 5. Verify ActiveDeviceManager does not have A2DP active device yet.
     * 6. A2DP active device changed.
     * 7. Verify ActiveDeviceManager has A2DP active device.
     * </pre>
     */
    @Test
    public void setActiveDevice_a2dp() {
        a2dpConnected(mA2dpDevice, false);
        a2dpActiveDeviceChanged(null);
        mTestLooper.dispatchAll();
        Mockito.clearInvocations(mA2dpService);

        mActiveDeviceManager.setActiveDevice(mA2dpDevice, BluetoothAdapter.ACTIVE_DEVICE_ALL);
        mTestLooper.dispatchAll();
        verify(mA2dpService).setActiveDevice(mA2dpDevice);

        assertThat(mActiveDeviceManager.getA2dpActiveDevice()).isNotEqualTo(mA2dpDevice);
        Mockito.clearInvocations(mA2dpService);

        a2dpActiveDeviceChanged(mA2dpDevice);
        mTestLooper.dispatchAll();
        assertThat(mActiveDeviceManager.getA2dpActiveDevice()).isEqualTo(mA2dpDevice);
    }

    /**
     * Set Headset device as active.
     *
     * <pre>
     * Steps:
     * 1. Connect Headset device.
     * 2. Set active device to null.
     * 3. Set Headset device as active.
     * 4. Verify Headset service is called to setActiveDevice.
     * 5. Verify ActiveDeviceManager does not have Headset active device yet.
     * 6. Headset active device changed.
     * 7. Verify ActiveDeviceManager has Headset active device.
     * </pre>
     */
    @Test
    public void setActiveDevice_headset() {
        headsetConnected(mHeadsetDevice, false);
        headsetActiveDeviceChanged(null);
        mTestLooper.dispatchAll();
        Mockito.clearInvocations(mHeadsetService);

        mActiveDeviceManager.setActiveDevice(mHeadsetDevice, BluetoothAdapter.ACTIVE_DEVICE_ALL);
        mTestLooper.dispatchAll();
        verify(mHeadsetService).setActiveDevice(mHeadsetDevice);

        assertThat(mActiveDeviceManager.getHfpActiveDevice()).isNotEqualTo(mHeadsetDevice);
        Mockito.clearInvocations(mHeadsetService);

        headsetActiveDeviceChanged(mHeadsetDevice);
        mTestLooper.dispatchAll();
        assertThat(mActiveDeviceManager.getHfpActiveDevice()).isEqualTo(mHeadsetDevice);
    }

    /**
     * Set A2DP Headset device as active.
     *
     * <pre>
     * Steps:
     * 1. Connect A2DP Headset device.
     * 2. Set active device to null.
     * 3. Set A2DP Headset device as active.
     * 4. Verify A2DP and Headset services are called to setActiveDevice.
     * 5. Verify ActiveDeviceManager does not have A2DP and Headset active device yet.
     * 6. A2DP active device changed.
     * 7. Verify ActiveDeviceManager has A2DP active device.
     * 8. Headset active device changed.
     * 9. Verify ActiveDeviceManager has Headset active device.
     * </pre>
     */
    @Test
    public void setActiveDevice_a2dpHeadset() {
        a2dpConnected(mA2dpHeadsetDevice, true);
        a2dpActiveDeviceChanged(null);
        headsetActiveDeviceChanged(null);
        mTestLooper.dispatchAll();
        Mockito.clearInvocations(mA2dpService, mHeadsetService);

        mActiveDeviceManager.setActiveDevice(
                mA2dpHeadsetDevice, BluetoothAdapter.ACTIVE_DEVICE_ALL);
        mTestLooper.dispatchAll();
        verify(mA2dpService).setActiveDevice(mA2dpHeadsetDevice);
        verify(mHeadsetService).setActiveDevice(mA2dpHeadsetDevice);

        assertThat(mActiveDeviceManager.getHfpActiveDevice()).isNotEqualTo(mA2dpHeadsetDevice);
        Mockito.clearInvocations(mA2dpService, mHeadsetService);

        a2dpActiveDeviceChanged(mA2dpHeadsetDevice);
        mTestLooper.dispatchAll();
        assertThat(mActiveDeviceManager.getA2dpActiveDevice()).isEqualTo(mA2dpHeadsetDevice);
        assertThat(mActiveDeviceManager.getHfpActiveDevice()).isNotEqualTo(mA2dpHeadsetDevice);

        Mockito.clearInvocations(mA2dpService, mHeadsetService);

        headsetActiveDeviceChanged(mA2dpHeadsetDevice);
        mTestLooper.dispatchAll();
        assertThat(mActiveDeviceManager.getHfpActiveDevice()).isEqualTo(mA2dpHeadsetDevice);
        assertThat(mActiveDeviceManager.getA2dpActiveDevice()).isEqualTo(mA2dpHeadsetDevice);
    }

    /**
     * Set LE Audio device as active.
     *
     * <pre>
     * Steps:
     * 1. Connect LE Audio device.
     * 2. Set active device to null.
     * 3. Set LE Audio device as active.
     * 4. Verify LE Audio service is called to setActiveDevice.
     * 5. Verify ActiveDeviceManager does not have LE Audio active device yet.
     * 6. LE Audio active device changed.
     * 7. Verify ActiveDeviceManager has LE Audio active device.
     * </pre>
     */
    @Test
    public void setActiveDevice_leAudio() {
        leAudioConnected(mLeAudioDevice);
        leAudioActiveDeviceChanged(null);
        mTestLooper.dispatchAll();
        Mockito.clearInvocations(mLeAudioService);

        mActiveDeviceManager.setActiveDevice(mLeAudioDevice, BluetoothAdapter.ACTIVE_DEVICE_ALL);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice);

        assertThat(mActiveDeviceManager.getLeAudioActiveDevice()).isNotEqualTo(mLeAudioDevice);
        Mockito.clearInvocations(mLeAudioService);

        leAudioActiveDeviceChanged(mLeAudioDevice);
        mTestLooper.dispatchAll();
        assertThat(mActiveDeviceManager.getLeAudioActiveDevice()).isEqualTo(mLeAudioDevice);
    }

    /**
     * Set Dual Mode device as active.
     *
     * <pre>
     * Steps:
     * 1. Enable dual mode audio.
     * 2. Connect Dual Mode device (LE Audio, A2DP, Headset).
     * 3. Set active device to null.
     * 4. Set Dual Mode device as active.
     * 5. Verify all service are called to setActiveDevice.
     * 6. Verify ActiveDeviceManager does not have active device yet.
     * 7. LE Audio active device changed.
     * 8. Verify ActiveDeviceManager has LE Audio active device.
     * 9. A2DP active device changed.
     * 10. Verify ActiveDeviceManager has A2DP active device.
     * 11. Headset active device changed.
     * 12. Verify ActiveDeviceManager has Headset active device.
     * </pre>
     */
    @Test
    public void setActiveDevice_dualMode() {
        Utils.setDualModeAudioStateForTesting(true);
        leAudioConnected(mDualModeAudioDevice);
        a2dpConnected(mDualModeAudioDevice, true);
        leAudioActiveDeviceChanged(null);
        a2dpActiveDeviceChanged(null);
        headsetActiveDeviceChanged(null);
        mTestLooper.dispatchAll();
        Mockito.clearInvocations(mA2dpService, mHeadsetService, mLeAudioService);

        doReturn(CONNECTION_POLICY_ALLOWED)
                .when(mHeadsetService)
                .getConnectionPolicy(mDualModeAudioDevice);

        mActiveDeviceManager.setActiveDevice(
                mDualModeAudioDevice, BluetoothAdapter.ACTIVE_DEVICE_ALL);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mDualModeAudioDevice);
        verify(mA2dpService).setActiveDevice(mDualModeAudioDevice);
        verify(mHeadsetService).setActiveDevice(mDualModeAudioDevice);

        assertThat(mActiveDeviceManager.getLeAudioActiveDevice())
                .isNotEqualTo(mDualModeAudioDevice);
        assertThat(mActiveDeviceManager.getHfpActiveDevice()).isNotEqualTo(mDualModeAudioDevice);
        assertThat(mActiveDeviceManager.getA2dpActiveDevice()).isNotEqualTo(mDualModeAudioDevice);
        Mockito.clearInvocations(mA2dpService, mHeadsetService, mLeAudioService);

        leAudioActiveDeviceChanged(mDualModeAudioDevice);
        mTestLooper.dispatchAll();
        assertThat(mActiveDeviceManager.getLeAudioActiveDevice()).isEqualTo(mDualModeAudioDevice);
        assertThat(mActiveDeviceManager.getHfpActiveDevice()).isNotEqualTo(mDualModeAudioDevice);
        assertThat(mActiveDeviceManager.getA2dpActiveDevice()).isNotEqualTo(mDualModeAudioDevice);
        verify(mA2dpService, never()).removeActiveDevice(anyBoolean());
        verify(mHeadsetService, never()).setActiveDevice(null);

        Mockito.clearInvocations(mA2dpService, mHeadsetService, mLeAudioService);

        a2dpActiveDeviceChanged(mDualModeAudioDevice);
        mTestLooper.dispatchAll();
        assertThat(mActiveDeviceManager.getA2dpActiveDevice()).isEqualTo(mDualModeAudioDevice);
        assertThat(mActiveDeviceManager.getLeAudioActiveDevice()).isEqualTo(mDualModeAudioDevice);
        assertThat(mActiveDeviceManager.getHfpActiveDevice()).isNotEqualTo(mDualModeAudioDevice);
        verify(mLeAudioService, never()).removeActiveDevice(anyBoolean());
        verify(mHeadsetService, never()).setActiveDevice(null);

        Mockito.clearInvocations(mA2dpService, mHeadsetService, mLeAudioService);

        headsetActiveDeviceChanged(mDualModeAudioDevice);
        mTestLooper.dispatchAll();
        assertThat(mActiveDeviceManager.getA2dpActiveDevice()).isEqualTo(mDualModeAudioDevice);
        assertThat(mActiveDeviceManager.getLeAudioActiveDevice()).isEqualTo(mDualModeAudioDevice);
        assertThat(mActiveDeviceManager.getHfpActiveDevice()).isEqualTo(mDualModeAudioDevice);
        verify(mLeAudioService, never()).removeActiveDevice(false);
        verify(mA2dpService, never()).removeActiveDevice(anyBoolean());
    }

    /**
     * Verifies setActiveDevice for scenario: Hearing Aid to A2DP+HFP.
     *
     * <pre>
     * Steps:
     * 1. Connect A2DP+HFP device and assure it's active.
     * 2. Connect HA device and assure it's active.
     * 3. Set A2DP+HFP as active device.
     * 4. Make sure A2DP+HFP is active and HA is deactivated.
     * </pre>
     */
    @Test
    @DisableFlags(Flags.FLAG_ADM_USE_SET_ACTIVE_DEVICE_HELPERS)
    public void setActiveDevice_hearingAidToA2dpHfp() {
        // 1. Connect A2DP+HFP device
        a2dpConnected(mA2dpHeadsetDevice, true);
        headsetConnected(mA2dpHeadsetDevice, true);
        mTestLooper.dispatchAll();
        verify(mA2dpService, times(2)).setActiveDevice(mA2dpHeadsetDevice);
        verify(mHeadsetService).setActiveDevice(mA2dpHeadsetDevice);

        // Assure A2DP+HFP is active
        a2dpActiveDeviceChanged(mA2dpHeadsetDevice);
        headsetActiveDeviceChanged(mA2dpHeadsetDevice);
        mTestLooper.dispatchAll();
        assertThat(mActiveDeviceManager.getA2dpActiveDevice()).isEqualTo(mA2dpHeadsetDevice);
        assertThat(mActiveDeviceManager.getHfpActiveDevice()).isEqualTo(mA2dpHeadsetDevice);
        assertThat(mActiveDeviceManager.getHearingAidActiveDevices()).isEmpty();

        // 2. Connect HA device
        hearingAidConnected(mHearingAidDevice);
        mTestLooper.dispatchAll();
        verify(mHearingAidService).setActiveDevice(mHearingAidDevice);
        verify(mA2dpService).removeActiveDevice(false);
        verify(mHeadsetService).setActiveDevice(null);

        // Assure HA is active
        hearingAidActiveDeviceChanged(mHearingAidDevice);
        mTestLooper.dispatchAll();
        assertThat(mActiveDeviceManager.getHearingAidActiveDevices()).contains(mHearingAidDevice);
        assertThat(mActiveDeviceManager.getA2dpActiveDevice()).isNull();
        assertThat(mActiveDeviceManager.getHfpActiveDevice()).isNull();
        assertThat(mActiveDeviceManager.getLeAudioActiveDevice()).isNull();

        Mockito.clearInvocations(mA2dpService, mHeadsetService, mHearingAidService);

        // 3. Set A2DP+HFP as active device (simulating manual set active device from BT settings)
        mActiveDeviceManager.setActiveDevice(
                mA2dpHeadsetDevice, BluetoothAdapter.ACTIVE_DEVICE_AUDIO);
        mTestLooper.dispatchAll();
        verify(mA2dpService).setActiveDevice(mA2dpHeadsetDevice);
        mActiveDeviceManager.setActiveDevice(
                mA2dpHeadsetDevice, BluetoothAdapter.ACTIVE_DEVICE_PHONE_CALL);
        mTestLooper.dispatchAll();
        verify(mHeadsetService).setActiveDevice(mA2dpHeadsetDevice);

        // 4. Make sure A2DP+HFP is active and HA is deactivated
        a2dpActiveDeviceChanged(mA2dpHeadsetDevice);
        headsetActiveDeviceChanged(mA2dpHeadsetDevice);
        mTestLooper.dispatchAll();
        assertThat(mActiveDeviceManager.getHearingAidActiveDevices()).isEmpty();
        assertThat(mActiveDeviceManager.getA2dpActiveDevice()).isEqualTo(mA2dpHeadsetDevice);
        assertThat(mActiveDeviceManager.getHfpActiveDevice()).isEqualTo(mA2dpHeadsetDevice);
    }

    /**
     * Verifies setActiveDevice for scenario: LE Audio to HFP.
     *
     * <pre>
     * Steps:
     * 1. Connect A2DP+HFP device and assure it's active.
     * 2. Connect LEA device and assure it's active.
     * 3. Set A2DP+HFP as active device.
     * 4. Make sure A2DP+HFP is active and LEA is deactivated.
     * </pre>
     */
    @Test
    @DisableFlags(Flags.FLAG_ADM_USE_SET_ACTIVE_DEVICE_HELPERS)
    @DisableFlags(Flags.FLAG_ADM_FIX_FALLBACK_DEVICE_SEARCH)
    public void setActiveDevice_leAudioToHfp() {
        doReturn(List.of(mLeAudioDevice)).when(mLeAudioService).getActiveDevices();
        doReturn(null).when(mLeAudioService).getLeadDevice(null);

        // 1. Connect A2DP+HFP device
        a2dpConnected(mA2dpHeadsetDevice, true);
        headsetConnected(mA2dpHeadsetDevice, true);
        mTestLooper.dispatchAll();
        verify(mA2dpService, times(2)).setActiveDevice(mA2dpHeadsetDevice);
        verify(mHeadsetService).setActiveDevice(mA2dpHeadsetDevice);

        // Assure A2DP+HFP is active
        a2dpActiveDeviceChanged(mA2dpHeadsetDevice);
        headsetActiveDeviceChanged(mA2dpHeadsetDevice);
        mTestLooper.dispatchAll();
        assertThat(mActiveDeviceManager.getA2dpActiveDevice()).isEqualTo(mA2dpHeadsetDevice);
        assertThat(mActiveDeviceManager.getHfpActiveDevice()).isEqualTo(mA2dpHeadsetDevice);
        assertThat(mActiveDeviceManager.getLeAudioActiveDevice()).isNull();

        // 2. Connect LEA device
        leAudioConnected(mLeAudioDevice);
        mTestLooper.dispatchAll();
        verify(mLeAudioService).setActiveDevice(mLeAudioDevice);
        verify(mA2dpService).removeActiveDevice(false);
        verify(mHeadsetService).setActiveDevice(null);

        // Assure LEA is active
        leAudioActiveDeviceChanged(mLeAudioDevice);
        mTestLooper.dispatchAll();
        assertThat(mActiveDeviceManager.getLeAudioActiveDevice()).isEqualTo(mLeAudioDevice);
        assertThat(mActiveDeviceManager.getA2dpActiveDevice()).isNull();
        assertThat(mActiveDeviceManager.getHfpActiveDevice()).isNull();

        Mockito.clearInvocations(mA2dpService, mHeadsetService, mLeAudioService);

        // 3. Set A2DP+HFP as active device (simulating manual set active device from BT settings)
        mActiveDeviceManager.setActiveDevice(
                mA2dpHeadsetDevice, BluetoothAdapter.ACTIVE_DEVICE_AUDIO);
        mTestLooper.dispatchAll();
        verify(mA2dpService).setActiveDevice(mA2dpHeadsetDevice);
        mActiveDeviceManager.setActiveDevice(
                mA2dpHeadsetDevice, BluetoothAdapter.ACTIVE_DEVICE_PHONE_CALL);
        mTestLooper.dispatchAll();
        verify(mHeadsetService).setActiveDevice(mA2dpHeadsetDevice);
        verify(mLeAudioService).removeActiveDevice(true);

        doReturn(mA2dpHeadsetDevice).when(mA2dpService).getFallbackDevice();
        doReturn(mA2dpHeadsetDevice).when(mHeadsetService).getFallbackDevice();

        leAudioActiveDeviceChanged(null);
        mTestLooper.dispatchAll();
        verify(mLeAudioService, never()).setActiveDevice(mLeAudioDevice);

        // 4. Make sure A2DP+HFP is active and LEA is deactivated
        a2dpActiveDeviceChanged(mA2dpHeadsetDevice);
        headsetActiveDeviceChanged(mA2dpHeadsetDevice);
        mTestLooper.dispatchAll();
        assertThat(mActiveDeviceManager.getA2dpActiveDevice()).isEqualTo(mA2dpHeadsetDevice);
        assertThat(mActiveDeviceManager.getHfpActiveDevice()).isEqualTo(mA2dpHeadsetDevice);
        assertThat(mActiveDeviceManager.getLeAudioActiveDevice()).isNull();
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
