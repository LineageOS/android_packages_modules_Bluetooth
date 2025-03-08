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

package com.android.bluetooth.a2dp;

import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;

import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;

import static com.android.bluetooth.TestUtils.MockitoRule;
import static com.android.bluetooth.TestUtils.getTestDevice;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.*;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecStatus;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.companion.CompanionDeviceManager;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.BluetoothProfileConnectionInfo;
import android.os.Looper;
import android.os.ParcelUuid;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.FlagsParameterization;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.bluetooth.TestLooper;
import com.android.bluetooth.TestUtils;
import com.android.bluetooth.btservice.ActiveDeviceManager;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.SilenceDeviceManager;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.bluetooth.flags.Flags;

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
import org.mockito.hamcrest.MockitoHamcrest;

import platform.test.runner.parameterized.ParameterizedAndroidJunit4;
import platform.test.runner.parameterized.Parameters;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

@MediumTest
@RunWith(ParameterizedAndroidJunit4.class)
public class A2dpServiceTest {
    private static final int MAX_CONNECTED_AUDIO_DEVICES = 5;
    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    private final BluetoothDevice mDevice = getTestDevice(5);

    @Rule public final SetFlagsRule mSetFlagsRule;
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private A2dpNativeInterface mMockNativeInterface;
    @Mock private ActiveDeviceManager mActiveDeviceManager;
    @Mock private AdapterService mAdapterService;
    @Mock private AudioManager mAudioManager;
    @Mock private DatabaseManager mDatabaseManager;
    @Mock private SilenceDeviceManager mSilenceDeviceManager;

    private InOrder mInOrder = null;

    private TestLooper mLooper;
    private A2dpService mA2dpService;
    private final CompanionDeviceManager mCompanionDeviceManager =
            InstrumentationRegistry.getInstrumentation()
                    .getContext()
                    .getSystemService(CompanionDeviceManager.class);

    @Parameters(name = "{0}")
    public static List<FlagsParameterization> getParams() {
        return FlagsParameterization.allCombinationsOf(
                Flags.FLAG_A2DP_BROADCAST_CONNECTION_STATE_WHEN_TURNED_OFF);
    }

    public A2dpServiceTest(FlagsParameterization flags) {
        mSetFlagsRule = new SetFlagsRule(flags);
    }

    @Before
    public void setUp() throws Exception {
        mInOrder = inOrder(mAdapterService);
        mLooper = new TestLooper();

        TestUtils.mockGetSystemService(
                mAdapterService, Context.AUDIO_SERVICE, AudioManager.class, mAudioManager);
        TestUtils.mockGetSystemService(
                mAdapterService,
                Context.COMPANION_DEVICE_SERVICE,
                CompanionDeviceManager.class,
                mCompanionDeviceManager);
        doReturn(InstrumentationRegistry.getInstrumentation().getTargetContext().getResources())
                .when(mAdapterService)
                .getResources();

        if (Looper.myLooper() == null) {
            Looper.prepare();
        }

        doReturn(true).when(mAdapterService).isA2dpOffloadEnabled();
        doReturn(MAX_CONNECTED_AUDIO_DEVICES).when(mAdapterService).getMaxConnectedAudioDevices();
        doReturn(false).when(mAdapterService).isQuietModeEnabled();
        doReturn(mDatabaseManager).when(mAdapterService).getDatabase();
        doReturn(mActiveDeviceManager).when(mAdapterService).getActiveDeviceManager();
        doReturn(mSilenceDeviceManager).when(mAdapterService).getSilenceDeviceManager();

        mA2dpService = new A2dpService(mAdapterService, mMockNativeInterface, mLooper.getLooper());
        mA2dpService.setAvailable(true);

        // Get a device for testing
        doReturn(BluetoothDevice.BOND_BONDED)
                .when(mAdapterService)
                .getBondState(any(BluetoothDevice.class));
        doReturn(new ParcelUuid[] {BluetoothUuid.A2DP_SINK})
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
    }

    @After
    public void tearDown() {
        assertThat(mLooper.dispatchAll()).isEqualTo(0);
        mA2dpService.cleanup();
    }

    @SafeVarargs
    private void verifyIntentSent(Matcher<Intent>... matchers) {
        mInOrder.verify(mAdapterService, timeout(TIMEOUT.toMillis() * 2))
                .sendBroadcast(MockitoHamcrest.argThat(AllOf.allOf(matchers)), any(), any());
    }

    private void verifyConnectionStateIntent(BluetoothDevice device, int newState, int prevState) {
        verifyIntentSent(
                hasAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
                hasExtra(BluetoothProfile.EXTRA_STATE, newState),
                hasExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, prevState));
    }

    @Test
    public void testGetA2dpService() {
        assertThat(A2dpService.getA2dpService()).isEqualTo(mA2dpService);
    }

    @Test
    public void testStopA2dpService() {
        // Prepare: connect and set active device
        doReturn(true).when(mMockNativeInterface).setActiveDevice(any(BluetoothDevice.class));
        connectDevice(mDevice);
        assertThat(mA2dpService.setActiveDevice(mDevice)).isTrue();
        verify(mMockNativeInterface).setActiveDevice(mDevice);

        mA2dpService.cleanup();
        dispatchAtLeastOneMessage();

        if (Flags.a2dpBroadcastConnectionStateWhenTurnedOff()) {
            // Verify that the intent CONNECTION_STATE_CHANGED is generated
            // for the existing connections.
            verifyConnectionStateIntent(mDevice, STATE_DISCONNECTED, STATE_CONNECTED);
        }

        // Verify that setActiveDevice(null) was called during shutdown
        verify(mMockNativeInterface).setActiveDevice(null);
    }

    /** Test get priority for BluetoothDevice */
    @Test
    public void testGetPriority() {
        when(mDatabaseManager.getProfileConnectionPolicy(mDevice, BluetoothProfile.A2DP))
                .thenReturn(CONNECTION_POLICY_UNKNOWN);
        assertThat(mA2dpService.getConnectionPolicy(mDevice)).isEqualTo(CONNECTION_POLICY_UNKNOWN);

        when(mDatabaseManager.getProfileConnectionPolicy(mDevice, BluetoothProfile.A2DP))
                .thenReturn(CONNECTION_POLICY_FORBIDDEN);
        assertThat(mA2dpService.getConnectionPolicy(mDevice))
                .isEqualTo(CONNECTION_POLICY_FORBIDDEN);

        when(mDatabaseManager.getProfileConnectionPolicy(mDevice, BluetoothProfile.A2DP))
                .thenReturn(CONNECTION_POLICY_ALLOWED);
        assertThat(mA2dpService.getConnectionPolicy(mDevice)).isEqualTo(CONNECTION_POLICY_ALLOWED);
    }

    /** Test okToConnect method using various test cases */
    @Test
    public void testOkToConnect() {
        int badPriorityValue = 1024;
        int badBondState = 42;
        testOkToConnectCase(
                mDevice,
                BluetoothDevice.BOND_NONE,
                CONNECTION_POLICY_UNKNOWN,
                Flags.donotValidateBondStateFromProfiles());
        testOkToConnectCase(mDevice, BluetoothDevice.BOND_NONE, CONNECTION_POLICY_FORBIDDEN, false);
        testOkToConnectCase(
                mDevice,
                BluetoothDevice.BOND_NONE,
                CONNECTION_POLICY_ALLOWED,
                Flags.donotValidateBondStateFromProfiles());
        testOkToConnectCase(mDevice, BluetoothDevice.BOND_NONE, badPriorityValue, false);
        testOkToConnectCase(
                mDevice,
                BluetoothDevice.BOND_BONDING,
                CONNECTION_POLICY_UNKNOWN,
                Flags.donotValidateBondStateFromProfiles());
        testOkToConnectCase(
                mDevice, BluetoothDevice.BOND_BONDING, CONNECTION_POLICY_FORBIDDEN, false);
        testOkToConnectCase(
                mDevice,
                BluetoothDevice.BOND_BONDING,
                CONNECTION_POLICY_ALLOWED,
                Flags.donotValidateBondStateFromProfiles());
        testOkToConnectCase(mDevice, BluetoothDevice.BOND_BONDING, badPriorityValue, false);
        testOkToConnectCase(mDevice, BluetoothDevice.BOND_BONDED, CONNECTION_POLICY_UNKNOWN, true);
        testOkToConnectCase(
                mDevice, BluetoothDevice.BOND_BONDED, CONNECTION_POLICY_FORBIDDEN, false);
        testOkToConnectCase(mDevice, BluetoothDevice.BOND_BONDED, CONNECTION_POLICY_ALLOWED, true);
        testOkToConnectCase(mDevice, BluetoothDevice.BOND_BONDED, badPriorityValue, false);
        testOkToConnectCase(
                mDevice,
                badBondState,
                CONNECTION_POLICY_UNKNOWN,
                Flags.donotValidateBondStateFromProfiles());
        testOkToConnectCase(mDevice, badBondState, CONNECTION_POLICY_FORBIDDEN, false);
        testOkToConnectCase(
                mDevice,
                badBondState,
                CONNECTION_POLICY_ALLOWED,
                Flags.donotValidateBondStateFromProfiles());
        testOkToConnectCase(mDevice, badBondState, badPriorityValue, false);
    }

    /** Test that an outgoing connection to device that does not have A2DP Sink UUID is rejected */
    @Test
    public void testOutgoingConnectMissingAudioSinkUuid() {
        // Update the device priority so okToConnect() returns true
        when(mDatabaseManager.getProfileConnectionPolicy(mDevice, BluetoothProfile.A2DP))
                .thenReturn(CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mMockNativeInterface).connectA2dp(any(BluetoothDevice.class));
        doReturn(true).when(mMockNativeInterface).disconnectA2dp(any(BluetoothDevice.class));

        // Return AudioSource UUID instead of AudioSink
        doReturn(new ParcelUuid[] {BluetoothUuid.A2DP_SOURCE})
                .when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));

        // Send a connect request
        assertThat(mA2dpService.connect(mDevice)).isFalse();
    }

    /** Test that an outgoing connection to device with PRIORITY_OFF is rejected */
    @Test
    public void testOutgoingConnectPriorityOff() {
        doReturn(true).when(mMockNativeInterface).connectA2dp(any(BluetoothDevice.class));
        doReturn(true).when(mMockNativeInterface).disconnectA2dp(any(BluetoothDevice.class));

        // Set the device priority to PRIORITY_OFF so connect() should fail
        when(mDatabaseManager.getProfileConnectionPolicy(mDevice, BluetoothProfile.A2DP))
                .thenReturn(CONNECTION_POLICY_FORBIDDEN);

        // Send a connect request
        assertThat(mA2dpService.connect(mDevice)).isFalse();
    }

    /** Test that an outgoing connection times out */
    @Test
    public void testOutgoingConnectTimeout() {
        // Update the device priority so okToConnect() returns true
        when(mDatabaseManager.getProfileConnectionPolicy(mDevice, BluetoothProfile.A2DP))
                .thenReturn(CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mMockNativeInterface).connectA2dp(any(BluetoothDevice.class));
        doReturn(true).when(mMockNativeInterface).disconnectA2dp(any(BluetoothDevice.class));

        // Send a connect request
        assertThat(mA2dpService.connect(mDevice)).isTrue();
        dispatchAtLeastOneMessage();

        // Verify the connection state broadcast, and that we are in Connecting state
        verifyConnectionStateIntent(mDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mA2dpService.getConnectionState(mDevice)).isEqualTo(STATE_CONNECTING);

        // Verify a timeout after 1sec.
        moveTimeForward(A2dpStateMachine.CONNECT_TIMEOUT.toMillis());
        dispatchAtLeastOneMessage();

        // Verify the connection state broadcast, and that we are in Disconnected state
        verifyConnectionStateIntent(mDevice, STATE_DISCONNECTED, STATE_CONNECTING);
        assertThat(mA2dpService.getConnectionState(mDevice)).isEqualTo(STATE_DISCONNECTED);
    }

    /** Test that an outgoing connection/disconnection succeeds */
    @Test
    public void testOutgoingConnectDisconnectSuccess() {
        A2dpStackEvent connCompletedEvent;

        // Update the device priority so okToConnect() returns true
        when(mDatabaseManager.getProfileConnectionPolicy(mDevice, BluetoothProfile.A2DP))
                .thenReturn(CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mMockNativeInterface).connectA2dp(any(BluetoothDevice.class));
        doReturn(true).when(mMockNativeInterface).disconnectA2dp(any(BluetoothDevice.class));

        // Send a connect request
        assertThat(mA2dpService.connect(mDevice)).isTrue();
        dispatchAtLeastOneMessage();

        // Verify the connection state broadcast, and that we are in Connecting state
        verifyConnectionStateIntent(mDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mA2dpService.getConnectionState(mDevice)).isEqualTo(STATE_CONNECTING);

        // Send a message to trigger connection completed
        connCompletedEvent = new A2dpStackEvent(A2dpStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connCompletedEvent.device = mDevice;
        connCompletedEvent.valueInt = STATE_CONNECTED;
        mA2dpService.messageFromNative(connCompletedEvent);
        dispatchAtLeastOneMessage();

        // Verify the connection state broadcast, and that we are in Connected state
        verifyConnectionStateIntent(mDevice, STATE_CONNECTED, STATE_CONNECTING);
        assertThat(mA2dpService.getConnectionState(mDevice)).isEqualTo(STATE_CONNECTED);

        // Verify the list of connected devices
        assertThat(mA2dpService.getConnectedDevices()).contains(mDevice);

        // Send a disconnect request
        assertThat(mA2dpService.disconnect(mDevice)).isTrue();
        dispatchAtLeastOneMessage();

        // Verify the connection state broadcast, and that we are in Disconnecting state
        verifyConnectionStateIntent(mDevice, STATE_DISCONNECTING, STATE_CONNECTED);
        assertThat(mA2dpService.getConnectionState(mDevice)).isEqualTo(STATE_DISCONNECTING);

        // Send a message to trigger disconnection completed
        connCompletedEvent = new A2dpStackEvent(A2dpStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connCompletedEvent.device = mDevice;
        connCompletedEvent.valueInt = STATE_DISCONNECTED;
        mA2dpService.messageFromNative(connCompletedEvent);
        dispatchAtLeastOneMessage();

        // Verify the connection state broadcast, and that we are in Disconnected state
        verifyConnectionStateIntent(mDevice, STATE_DISCONNECTED, STATE_DISCONNECTING);
        assertThat(mA2dpService.getConnectionState(mDevice)).isEqualTo(STATE_DISCONNECTED);

        // Verify the list of connected devices
        assertThat(mA2dpService.getConnectedDevices()).doesNotContain(mDevice);
    }

    /** Test that an outgoing connection/disconnection succeeds */
    @Test
    public void testMaxConnectDevices() {
        A2dpStackEvent connCompletedEvent;
        BluetoothDevice[] testDevices = new BluetoothDevice[MAX_CONNECTED_AUDIO_DEVICES];
        BluetoothDevice extraTestDevice;

        doReturn(true).when(mMockNativeInterface).connectA2dp(any(BluetoothDevice.class));
        doReturn(true).when(mMockNativeInterface).disconnectA2dp(any(BluetoothDevice.class));

        // Prepare and connect all test devices
        for (int i = 0; i < MAX_CONNECTED_AUDIO_DEVICES; i++) {
            BluetoothDevice testDevice = getTestDevice(i);
            testDevices[i] = testDevice;
            when(mDatabaseManager.getProfileConnectionPolicy(testDevice, BluetoothProfile.A2DP))
                    .thenReturn(CONNECTION_POLICY_ALLOWED);
            // Send a connect request
            assertThat(mA2dpService.connect(testDevice)).isTrue();
            dispatchAtLeastOneMessage();
            // Verify the connection state broadcast, and that we are in Connecting state
            verifyConnectionStateIntent(testDevice, STATE_CONNECTING, STATE_DISCONNECTED);
            assertThat(mA2dpService.getConnectionState(testDevice)).isEqualTo(STATE_CONNECTING);
            // Send a message to trigger connection completed
            connCompletedEvent =
                    new A2dpStackEvent(A2dpStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
            connCompletedEvent.device = testDevice;
            connCompletedEvent.valueInt = STATE_CONNECTED;
            mA2dpService.messageFromNative(connCompletedEvent);
            dispatchAtLeastOneMessage();
            // Verify the connection state broadcast, and that we are in Connected state
            verifyConnectionStateIntent(testDevice, STATE_CONNECTED, STATE_CONNECTING);
            assertThat(mA2dpService.getConnectionState(testDevice)).isEqualTo(STATE_CONNECTED);
            // Verify the list of connected devices
            assertThat(mA2dpService.getConnectedDevices()).contains(testDevice);
        }

        // Prepare and connect the extra test device. The connect request should fail
        extraTestDevice = getTestDevice(MAX_CONNECTED_AUDIO_DEVICES);
        when(mDatabaseManager.getProfileConnectionPolicy(extraTestDevice, BluetoothProfile.A2DP))
                .thenReturn(CONNECTION_POLICY_ALLOWED);
        // Send a connect request
        assertThat(mA2dpService.connect(extraTestDevice)).isFalse();
    }

    /**
     * Test that only CONNECTION_STATE_CONNECTED or CONNECTION_STATE_CONNECTING A2DP stack events
     * will create a state machine.
     */
    @Test
    public void testCreateStateMachineStackEvents() {
        // Update the device priority so okToConnect() returns true
        when(mDatabaseManager.getProfileConnectionPolicy(mDevice, BluetoothProfile.A2DP))
                .thenReturn(CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mMockNativeInterface).connectA2dp(any(BluetoothDevice.class));
        doReturn(true).when(mMockNativeInterface).disconnectA2dp(any(BluetoothDevice.class));

        // A2DP stack event: CONNECTION_STATE_CONNECTING - state machine should be created
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mA2dpService.getConnectionState(mDevice)).isEqualTo(STATE_CONNECTING);
        assertThat(mA2dpService.getDevices()).contains(mDevice);

        // A2DP stack event: CONNECTION_STATE_DISCONNECTED - state machine should be removed
        generateConnectionMessageFromNative(mDevice, STATE_DISCONNECTED, STATE_CONNECTING);
        assertThat(mA2dpService.getConnectionState(mDevice)).isEqualTo(STATE_DISCONNECTED);
        assertThat(mA2dpService.getDevices()).contains(mDevice);

        mA2dpService.bondStateChanged(mDevice, BluetoothDevice.BOND_NONE);
        dispatchAtLeastOneMessage();
        assertThat(mA2dpService.getDevices()).doesNotContain(mDevice);

        // A2DP stack event: CONNECTION_STATE_CONNECTED - state machine should be created
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mA2dpService.getConnectionState(mDevice)).isEqualTo(STATE_CONNECTED);
        assertThat(mA2dpService.getDevices()).contains(mDevice);

        // A2DP stack event: EVENT_TYPE_AUDIO_STATE_CHANGED - Intent broadcast should be generated
        // NOTE: The first message (STATE_PLAYING -> STATE_NOT_PLAYING) is generated internally
        // by the state machine when Connected, and needs to be extracted first before generating
        // the actual message from native.
        verifyIntentSent(
                hasAction(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice),
                hasExtra(BluetoothProfile.EXTRA_STATE, BluetoothA2dp.STATE_NOT_PLAYING),
                hasExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothA2dp.STATE_PLAYING));

        // A2DP stack event: CONNECTION_STATE_DISCONNECTED - state machine should be removed
        generateConnectionMessageFromNative(mDevice, STATE_DISCONNECTED, STATE_CONNECTED);
        assertThat(mA2dpService.getConnectionState(mDevice)).isEqualTo(STATE_DISCONNECTED);
        assertThat(mA2dpService.getDevices()).contains(mDevice);

        mA2dpService.bondStateChanged(mDevice, BluetoothDevice.BOND_NONE);
        dispatchAtLeastOneMessage();
        assertThat(mA2dpService.getDevices()).doesNotContain(mDevice);

        // A2DP stack event: CONNECTION_STATE_DISCONNECTING - state machine should not be created
        generateUnexpectedConnectionMessageFromNative(mDevice, STATE_DISCONNECTING);
        assertThat(mA2dpService.getConnectionState(mDevice)).isEqualTo(STATE_DISCONNECTED);
        assertThat(mA2dpService.getDevices()).doesNotContain(mDevice);

        // A2DP stack event: CONNECTION_STATE_DISCONNECTED - state machine should not be created
        generateUnexpectedConnectionMessageFromNative(mDevice, STATE_DISCONNECTED);
        assertThat(mA2dpService.getConnectionState(mDevice)).isEqualTo(STATE_DISCONNECTED);
        assertThat(mA2dpService.getDevices()).doesNotContain(mDevice);
    }

    /**
     * Test that EVENT_TYPE_AUDIO_STATE_CHANGED and EVENT_TYPE_CODEC_CONFIG_CHANGED events are
     * processed.
     */
    @Test
    public void testProcessAudioStateChangedCodecConfigChangedEvents() {
        BluetoothCodecConfig codecConfigSbc =
                buildBluetoothCodecConfig(
                        BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                        BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                        BluetoothCodecConfig.SAMPLE_RATE_44100,
                        BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                        BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                        0,
                        0,
                        0,
                        0); // Codec-specific fields
        BluetoothCodecConfig codecConfig = codecConfigSbc;
        BluetoothCodecConfig[] codecsLocalCapabilities = new BluetoothCodecConfig[1];
        BluetoothCodecConfig[] codecsSelectableCapabilities = new BluetoothCodecConfig[1];
        codecsLocalCapabilities[0] = codecConfigSbc;
        codecsSelectableCapabilities[0] = codecConfigSbc;
        BluetoothCodecStatus codecStatus =
                new BluetoothCodecStatus(
                        codecConfig,
                        Arrays.asList(codecsLocalCapabilities),
                        Arrays.asList(codecsSelectableCapabilities));

        // Update the device priority so okToConnect() returns true
        when(mDatabaseManager.getProfileConnectionPolicy(mDevice, BluetoothProfile.A2DP))
                .thenReturn(CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mMockNativeInterface).connectA2dp(any(BluetoothDevice.class));
        doReturn(true).when(mMockNativeInterface).disconnectA2dp(any(BluetoothDevice.class));

        // A2DP stack event: EVENT_TYPE_AUDIO_STATE_CHANGED - state machine should not be created
        generateUnexpectedAudioMessageFromNative(mDevice, A2dpStackEvent.AUDIO_STATE_STARTED);
        assertThat(mA2dpService.getConnectionState(mDevice)).isEqualTo(STATE_DISCONNECTED);
        assertThat(mA2dpService.getDevices()).doesNotContain(mDevice);

        // A2DP stack event: EVENT_TYPE_CODEC_CONFIG_CHANGED - state machine should not be created
        generateUnexpectedCodecMessageFromNative(mDevice, codecStatus);
        assertThat(mA2dpService.getConnectionState(mDevice)).isEqualTo(STATE_DISCONNECTED);
        assertThat(mA2dpService.getDevices()).doesNotContain(mDevice);

        // A2DP stack event: CONNECTION_STATE_CONNECTED - state machine should be created
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTED, STATE_DISCONNECTED);
        assertThat(mA2dpService.getConnectionState(mDevice)).isEqualTo(STATE_CONNECTED);
        assertThat(mA2dpService.getDevices()).contains(mDevice);

        // A2DP stack event: EVENT_TYPE_AUDIO_STATE_CHANGED - Intent broadcast should be generated
        // NOTE: The first message (STATE_PLAYING -> STATE_NOT_PLAYING) is generated internally
        // by the state machine when Connected, and needs to be extracted first before generating
        // the actual message from native.
        verifyIntentSent(
                hasAction(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice),
                hasExtra(BluetoothProfile.EXTRA_STATE, BluetoothA2dp.STATE_NOT_PLAYING),
                hasExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothA2dp.STATE_PLAYING));

        generateAudioMessageFromNative(
                mDevice,
                A2dpStackEvent.AUDIO_STATE_STARTED,
                BluetoothA2dp.STATE_PLAYING,
                BluetoothA2dp.STATE_NOT_PLAYING);
        assertThat(mA2dpService.getConnectionState(mDevice)).isEqualTo(STATE_CONNECTED);
        assertThat(mA2dpService.getDevices()).contains(mDevice);

        // A2DP stack event: EVENT_TYPE_CODEC_CONFIG_CHANGED - Intent broadcast should be generated
        generateCodecMessageFromNative(mDevice, codecStatus);
        assertThat(mA2dpService.getConnectionState(mDevice)).isEqualTo(STATE_CONNECTED);
        assertThat(mA2dpService.getDevices()).contains(mDevice);

        // A2DP stack event: CONNECTION_STATE_DISCONNECTED - state machine should be removed
        generateConnectionMessageFromNative(mDevice, STATE_DISCONNECTED, STATE_CONNECTED);
        assertThat(mA2dpService.getConnectionState(mDevice)).isEqualTo(STATE_DISCONNECTED);
        assertThat(mA2dpService.getDevices()).contains(mDevice);
        mA2dpService.bondStateChanged(mDevice, BluetoothDevice.BOND_NONE);
        dispatchAtLeastOneMessage();
        assertThat(mA2dpService.getDevices()).doesNotContain(mDevice);
    }

    /**
     * Test that a state machine in DISCONNECTED state is removed only after the device is unbond.
     */
    @Test
    @DisableFlags(Flags.FLAG_A2DP_CLEANUP_ON_REMOVE_DEVICE)
    public void testDeleteDisconnectedStateMachineUnbondEvents() {
        // Update the device priority so okToConnect() returns true
        when(mDatabaseManager.getProfileConnectionPolicy(mDevice, BluetoothProfile.A2DP))
                .thenReturn(CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mMockNativeInterface).connectA2dp(any(BluetoothDevice.class));
        doReturn(true).when(mMockNativeInterface).disconnectA2dp(any(BluetoothDevice.class));

        // A2DP stack event: CONNECTION_STATE_CONNECTING - state machine should be created
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mA2dpService.getConnectionState(mDevice)).isEqualTo(STATE_CONNECTING);
        assertThat(mA2dpService.getDevices()).contains(mDevice);
        // Device unbond - state machine is not removed
        mA2dpService.bondStateChanged(mDevice, BluetoothDevice.BOND_NONE);
        assertThat(mA2dpService.getDevices()).contains(mDevice);

        // A2DP stack event: CONNECTION_STATE_CONNECTED - state machine is not removed
        mA2dpService.bondStateChanged(mDevice, BluetoothDevice.BOND_BONDED);
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTED, STATE_CONNECTING);
        assertThat(mA2dpService.getConnectionState(mDevice)).isEqualTo(STATE_CONNECTED);
        assertThat(mA2dpService.getDevices()).contains(mDevice);
        // Device unbond - state machine is not removed
        mA2dpService.bondStateChanged(mDevice, BluetoothDevice.BOND_NONE);
        assertThat(mA2dpService.getDevices()).contains(mDevice);

        // A2DP stack event: CONNECTION_STATE_DISCONNECTING - state machine is not removed
        mA2dpService.bondStateChanged(mDevice, BluetoothDevice.BOND_BONDED);
        generateConnectionMessageFromNative(mDevice, STATE_DISCONNECTING, STATE_CONNECTED);
        assertThat(mA2dpService.getConnectionState(mDevice)).isEqualTo(STATE_DISCONNECTING);
        assertThat(mA2dpService.getDevices()).contains(mDevice);
        // Device unbond - state machine is not removed
        mA2dpService.bondStateChanged(mDevice, BluetoothDevice.BOND_NONE);
        assertThat(mA2dpService.getDevices()).contains(mDevice);

        // A2DP stack event: CONNECTION_STATE_DISCONNECTED - state machine is not removed
        mA2dpService.bondStateChanged(mDevice, BluetoothDevice.BOND_BONDED);
        generateConnectionMessageFromNative(mDevice, STATE_DISCONNECTED, STATE_DISCONNECTING);
        assertThat(mA2dpService.getConnectionState(mDevice)).isEqualTo(STATE_DISCONNECTED);
        assertThat(mA2dpService.getDevices()).contains(mDevice);
        // Device unbond - state machine is removed
        mA2dpService.bondStateChanged(mDevice, BluetoothDevice.BOND_NONE);
        dispatchAtLeastOneMessage();
        assertThat(mA2dpService.getDevices()).doesNotContain(mDevice);
    }

    /** Test that a state machine is removed after the device is unbond. */
    @Test
    @EnableFlags(Flags.FLAG_A2DP_CLEANUP_ON_REMOVE_DEVICE)
    public void testDeleteStateMachineUnbondEvents() {
        // TODO: b/395691070 delete this log
        android.util.Log.d(
                "A2dpServiceTest",
                "run test while A2DP_CLEANUP_ON_REMOVE_DEVICE is set to "
                        + Flags.a2dpCleanupOnRemoveDevice());

        // Update the device priority so okToConnect() returns true
        when(mDatabaseManager.getProfileConnectionPolicy(mDevice, BluetoothProfile.A2DP))
                .thenReturn(CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mMockNativeInterface).connectA2dp(any(BluetoothDevice.class));
        doReturn(true).when(mMockNativeInterface).disconnectA2dp(any(BluetoothDevice.class));

        // A2DP stack event: CONNECTION_STATE_CONNECTING - state machine should be created
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mA2dpService.getConnectionState(mDevice)).isEqualTo(STATE_CONNECTING);
        assertThat(mA2dpService.getDevices()).contains(mDevice);
        // Device unbond - state machine is not removed
        mA2dpService.bondStateChangedFromTest(mDevice, BluetoothDevice.BOND_NONE);
        if (Flags.a2dpBroadcastConnectionStateWhenTurnedOff()) {
            // Verify that the intent CONNECTION_STATE_CHANGED is generated
            // for the existing connections.
            verifyConnectionStateIntent(mDevice, STATE_DISCONNECTED, STATE_CONNECTING);
        }
        assertThat(mA2dpService.getDevices()).doesNotContain(mDevice);

        // A2DP stack event: CONNECTION_STATE_CONNECTED - state machine is not removed
        mA2dpService.bondStateChangedFromTest(mDevice, BluetoothDevice.BOND_BONDED);
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTED, STATE_CONNECTING);
        assertThat(mA2dpService.getConnectionState(mDevice)).isEqualTo(STATE_CONNECTED);
        assertThat(mA2dpService.getDevices()).contains(mDevice);
        // Device unbond - state machine is not removed
        mA2dpService.bondStateChangedFromTest(mDevice, BluetoothDevice.BOND_NONE);
        if (Flags.a2dpBroadcastConnectionStateWhenTurnedOff()) {
            // Verify that the intent CONNECTION_STATE_CHANGED is generated
            // for the existing connections.
            verifyConnectionStateIntent(mDevice, STATE_DISCONNECTED, STATE_CONNECTED);
        }
        assertThat(mA2dpService.getDevices()).doesNotContain(mDevice);

        // A2DP stack event: CONNECTION_STATE_DISCONNECTING - state machine is not removed
        mA2dpService.bondStateChangedFromTest(mDevice, BluetoothDevice.BOND_BONDED);
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTED, STATE_CONNECTING);
        generateConnectionMessageFromNative(mDevice, STATE_DISCONNECTING, STATE_CONNECTED);
        assertThat(mA2dpService.getConnectionState(mDevice)).isEqualTo(STATE_DISCONNECTING);
        assertThat(mA2dpService.getDevices()).contains(mDevice);
        // Device unbond - state machine is not removed
        mA2dpService.bondStateChangedFromTest(mDevice, BluetoothDevice.BOND_NONE);
        if (Flags.a2dpBroadcastConnectionStateWhenTurnedOff()) {
            // Verify that the intent CONNECTION_STATE_CHANGED is generated
            // for the existing connections.
            verifyConnectionStateIntent(mDevice, STATE_DISCONNECTED, STATE_DISCONNECTING);
        }
        assertThat(mA2dpService.getDevices()).doesNotContain(mDevice);

        // A2DP stack event: CONNECTION_STATE_DISCONNECTED - state machine is not removed
        mA2dpService.bondStateChangedFromTest(mDevice, BluetoothDevice.BOND_BONDED);
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTED, STATE_CONNECTING);
        generateConnectionMessageFromNative(mDevice, STATE_DISCONNECTING, STATE_CONNECTED);
        generateConnectionMessageFromNative(mDevice, STATE_DISCONNECTED, STATE_DISCONNECTING);
        assertThat(mA2dpService.getConnectionState(mDevice)).isEqualTo(STATE_DISCONNECTED);
        assertThat(mA2dpService.getDevices()).contains(mDevice);
        // Device unbond - state machine is removed
        mA2dpService.bondStateChangedFromTest(mDevice, BluetoothDevice.BOND_NONE);
        dispatchAtLeastOneMessage();
        assertThat(mA2dpService.getDevices()).doesNotContain(mDevice);
    }

    /**
     * Test that a CONNECTION_STATE_DISCONNECTED A2DP stack event will remove the state machine only
     * if the device is unbond.
     */
    @Test
    public void testDeleteStateMachineDisconnectEvents() {
        // Update the device priority so okToConnect() returns true
        when(mDatabaseManager.getProfileConnectionPolicy(mDevice, BluetoothProfile.A2DP))
                .thenReturn(CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mMockNativeInterface).connectA2dp(any(BluetoothDevice.class));
        doReturn(true).when(mMockNativeInterface).disconnectA2dp(any(BluetoothDevice.class));

        // A2DP stack event: CONNECTION_STATE_CONNECTING - state machine should be created
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mA2dpService.getConnectionState(mDevice)).isEqualTo(STATE_CONNECTING);
        assertThat(mA2dpService.getDevices()).contains(mDevice);

        // A2DP stack event: CONNECTION_STATE_DISCONNECTED - state machine is not removed
        generateConnectionMessageFromNative(mDevice, STATE_DISCONNECTED, STATE_CONNECTING);
        assertThat(mA2dpService.getConnectionState(mDevice)).isEqualTo(STATE_DISCONNECTED);
        assertThat(mA2dpService.getDevices()).contains(mDevice);

        // A2DP stack event: CONNECTION_STATE_CONNECTING - state machine remains
        generateConnectionMessageFromNative(mDevice, STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mA2dpService.getConnectionState(mDevice)).isEqualTo(STATE_CONNECTING);
        assertThat(mA2dpService.getDevices()).contains(mDevice);

        // Device bond state marked as unbond - state machine is not removed
        doReturn(BluetoothDevice.BOND_NONE)
                .when(mAdapterService)
                .getBondState(any(BluetoothDevice.class));
        assertThat(mA2dpService.getDevices()).contains(mDevice);

        // A2DP stack event: CONNECTION_STATE_DISCONNECTED - state machine is removed
        generateConnectionMessageFromNative(mDevice, STATE_DISCONNECTED, STATE_CONNECTING);
        assertThat(mA2dpService.getConnectionState(mDevice)).isEqualTo(STATE_DISCONNECTED);
        assertThat(mA2dpService.getDevices()).doesNotContain(mDevice);
    }

    /** Test that whether active device been removed after enable silence mode */
    @Test
    public void testSetSilenceMode() {
        BluetoothDevice otherDevice = getTestDevice(237);
        connectDevice(mDevice);
        connectDevice(otherDevice);
        doReturn(true).when(mMockNativeInterface).setActiveDevice(any(BluetoothDevice.class));
        doReturn(true)
                .when(mMockNativeInterface)
                .setSilenceDevice(any(BluetoothDevice.class), anyBoolean());

        // Test whether active device been removed after enable silence mode.
        assertThat(mA2dpService.setActiveDevice(mDevice)).isTrue();
        assertThat(mA2dpService.getActiveDevice()).isEqualTo(mDevice);
        assertThat(mA2dpService.setSilenceMode(mDevice, true)).isTrue();
        verify(mMockNativeInterface).setSilenceDevice(mDevice, true);
        assertThat(mA2dpService.getActiveDevice()).isNull();

        // Test whether active device been resumed after disable silence mode.
        assertThat(mA2dpService.setSilenceMode(mDevice, false)).isTrue();
        verify(mMockNativeInterface).setSilenceDevice(mDevice, false);
        assertThat(mA2dpService.getActiveDevice()).isEqualTo(mDevice);

        // Test that active device should not be changed when silence a non-active device
        assertThat(mA2dpService.setActiveDevice(mDevice)).isTrue();
        assertThat(mA2dpService.getActiveDevice()).isEqualTo(mDevice);
        assertThat(mA2dpService.setSilenceMode(otherDevice, true)).isTrue();
        verify(mMockNativeInterface).setSilenceDevice(otherDevice, true);
        assertThat(mA2dpService.getActiveDevice()).isEqualTo(mDevice);

        // Test that active device should not be changed when another device exits silence mode
        assertThat(mA2dpService.setSilenceMode(otherDevice, false)).isTrue();
        verify(mMockNativeInterface).setSilenceDevice(otherDevice, false);
        assertThat(mA2dpService.getActiveDevice()).isEqualTo(mDevice);
    }

    @Test
    public void testSetActiveDevice_withNull_returnsFalse() {
        // Null is not accepted.
        assertThat(mA2dpService.setActiveDevice(null)).isFalse();
    }

    /**
     * Test whether removeActiveDevice(false) suppresses noisy intent. Music should keep playing.
     * (e.g. user selected LE headset via UI)
     */
    @Test
    public void testRemoveActiveDevice_whenStopAudioIsFalse_suppressNoisyIntent() {
        connectDevice(mDevice);
        doReturn(true).when(mMockNativeInterface).setActiveDevice(any(BluetoothDevice.class));
        assertThat(mA2dpService.setActiveDevice(mDevice)).isTrue();
        assertThat(mA2dpService.getActiveDevice()).isEqualTo(mDevice);

        assertThat(mA2dpService.disconnect(mDevice)).isTrue();
        dispatchAtLeastOneMessage();

        verifyConnectionStateIntent(mDevice, STATE_DISCONNECTING, STATE_CONNECTED);
        mA2dpService.removeActiveDevice(false);

        ArgumentCaptor<BluetoothProfileConnectionInfo> connectionInfoArgumentCaptor =
                ArgumentCaptor.forClass(BluetoothProfileConnectionInfo.class);
        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        isNull(), eq(mDevice), connectionInfoArgumentCaptor.capture());
        BluetoothProfileConnectionInfo connectionInfo = connectionInfoArgumentCaptor.getValue();
        // Should suppress noisy intent. (i.e. Music should keep playing)
        assertThat(connectionInfo.isSuppressNoisyIntent()).isTrue();
    }

    /**
     * Test whether removeActiveDevice(true) does not suppress noisy intent. Music should pause.
     * (e.g. The solely connected BT device is disconnected)
     */
    @Test
    public void testRemoveActiveDevice_whenStopAudioIsFalse_doesNotSuppressNoisyIntent() {
        connectDevice(mDevice);
        doReturn(true).when(mMockNativeInterface).setActiveDevice(any(BluetoothDevice.class));
        assertThat(mA2dpService.setActiveDevice(mDevice)).isTrue();
        assertThat(mA2dpService.getActiveDevice()).isEqualTo(mDevice);

        assertThat(mA2dpService.disconnect(mDevice)).isTrue();
        dispatchAtLeastOneMessage();

        verifyConnectionStateIntent(mDevice, STATE_DISCONNECTING, STATE_CONNECTED);
        mA2dpService.removeActiveDevice(true);

        ArgumentCaptor<BluetoothProfileConnectionInfo> connectionInfoArgumentCaptor =
                ArgumentCaptor.forClass(BluetoothProfileConnectionInfo.class);
        verify(mAudioManager)
                .handleBluetoothActiveDeviceChanged(
                        isNull(), eq(mDevice), connectionInfoArgumentCaptor.capture());
        BluetoothProfileConnectionInfo connectionInfo = connectionInfoArgumentCaptor.getValue();
        // Should not suppress noisy intent. (i.e. Music should pause)
        assertThat(connectionInfo.isSuppressNoisyIntent()).isFalse();
    }

    /**
     * Test that whether updateOptionalCodecsSupport() method is working as intended when a
     * Bluetooth device is connected with A2DP.
     */
    @Test
    public void testUpdateOptionalCodecsSupport() {
        int verifySupportTime = 0;
        int verifyNotSupportTime = 0;
        int verifyEnabledTime = 0;
        // Test for device supports optional codec
        testUpdateOptionalCodecsSupportCase(
                BluetoothA2dp.OPTIONAL_CODECS_SUPPORT_UNKNOWN,
                true,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_UNKNOWN,
                ++verifySupportTime,
                verifyNotSupportTime,
                ++verifyEnabledTime);
        testUpdateOptionalCodecsSupportCase(
                BluetoothA2dp.OPTIONAL_CODECS_SUPPORT_UNKNOWN,
                true,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_ENABLED,
                ++verifySupportTime,
                verifyNotSupportTime,
                verifyEnabledTime);
        testUpdateOptionalCodecsSupportCase(
                BluetoothA2dp.OPTIONAL_CODECS_SUPPORT_UNKNOWN,
                true,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_DISABLED,
                ++verifySupportTime,
                verifyNotSupportTime,
                verifyEnabledTime);
        testUpdateOptionalCodecsSupportCase(
                BluetoothA2dp.OPTIONAL_CODECS_SUPPORTED,
                true,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_UNKNOWN,
                verifySupportTime,
                verifyNotSupportTime,
                ++verifyEnabledTime);
        testUpdateOptionalCodecsSupportCase(
                BluetoothA2dp.OPTIONAL_CODECS_SUPPORTED,
                true,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_ENABLED,
                verifySupportTime,
                verifyNotSupportTime,
                verifyEnabledTime);
        testUpdateOptionalCodecsSupportCase(
                BluetoothA2dp.OPTIONAL_CODECS_SUPPORTED,
                true,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_DISABLED,
                verifySupportTime,
                verifyNotSupportTime,
                verifyEnabledTime);
        testUpdateOptionalCodecsSupportCase(
                BluetoothA2dp.OPTIONAL_CODECS_NOT_SUPPORTED,
                true,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_UNKNOWN,
                ++verifySupportTime,
                verifyNotSupportTime,
                ++verifyEnabledTime);
        testUpdateOptionalCodecsSupportCase(
                BluetoothA2dp.OPTIONAL_CODECS_NOT_SUPPORTED,
                true,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_ENABLED,
                ++verifySupportTime,
                verifyNotSupportTime,
                verifyEnabledTime);
        testUpdateOptionalCodecsSupportCase(
                BluetoothA2dp.OPTIONAL_CODECS_NOT_SUPPORTED,
                true,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_DISABLED,
                ++verifySupportTime,
                verifyNotSupportTime,
                verifyEnabledTime);

        // Test for device not supports optional codec
        testUpdateOptionalCodecsSupportCase(
                BluetoothA2dp.OPTIONAL_CODECS_SUPPORT_UNKNOWN,
                false,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_UNKNOWN,
                verifySupportTime,
                ++verifyNotSupportTime,
                verifyEnabledTime);
        testUpdateOptionalCodecsSupportCase(
                BluetoothA2dp.OPTIONAL_CODECS_SUPPORT_UNKNOWN,
                false,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_ENABLED,
                verifySupportTime,
                ++verifyNotSupportTime,
                verifyEnabledTime);
        testUpdateOptionalCodecsSupportCase(
                BluetoothA2dp.OPTIONAL_CODECS_SUPPORT_UNKNOWN,
                false,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_DISABLED,
                verifySupportTime,
                ++verifyNotSupportTime,
                verifyEnabledTime);
        testUpdateOptionalCodecsSupportCase(
                BluetoothA2dp.OPTIONAL_CODECS_SUPPORTED,
                false,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_UNKNOWN,
                verifySupportTime,
                ++verifyNotSupportTime,
                verifyEnabledTime);
        testUpdateOptionalCodecsSupportCase(
                BluetoothA2dp.OPTIONAL_CODECS_SUPPORTED,
                false,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_ENABLED,
                verifySupportTime,
                ++verifyNotSupportTime,
                verifyEnabledTime);
        testUpdateOptionalCodecsSupportCase(
                BluetoothA2dp.OPTIONAL_CODECS_SUPPORTED,
                false,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_DISABLED,
                verifySupportTime,
                ++verifyNotSupportTime,
                verifyEnabledTime);
        testUpdateOptionalCodecsSupportCase(
                BluetoothA2dp.OPTIONAL_CODECS_NOT_SUPPORTED,
                false,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_UNKNOWN,
                verifySupportTime,
                verifyNotSupportTime,
                verifyEnabledTime);
        testUpdateOptionalCodecsSupportCase(
                BluetoothA2dp.OPTIONAL_CODECS_NOT_SUPPORTED,
                false,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_ENABLED,
                verifySupportTime,
                verifyNotSupportTime,
                verifyEnabledTime);
        testUpdateOptionalCodecsSupportCase(
                BluetoothA2dp.OPTIONAL_CODECS_NOT_SUPPORTED,
                false,
                BluetoothA2dp.OPTIONAL_CODECS_PREF_DISABLED,
                verifySupportTime,
                verifyNotSupportTime,
                verifyEnabledTime);
    }

    /**
     * Tests that {@link A2dpService#sendPreferredAudioProfileChangeToAudioFramework()} sends
     * requests to the audio framework when there is an active A2DP device.
     */
    @Test
    public void testSendPreferredAudioProfileChangeToAudioFramework() {
        doReturn(true).when(mMockNativeInterface).setActiveDevice(any(BluetoothDevice.class));
        assertThat(mA2dpService.removeActiveDevice(true)).isTrue();
        assertThat(mA2dpService.getActiveDevice()).isNull();

        // Send 0 requests when the active device is null
        assertThat(mA2dpService.sendPreferredAudioProfileChangeToAudioFramework()).isEqualTo(0);

        // Send 1 request when there is an A2DP active device
        connectDevice(mDevice);
        assertThat(mA2dpService.setActiveDevice(mDevice)).isTrue();
        assertThat(mA2dpService.getActiveDevice()).isEqualTo(mDevice);
        assertThat(mA2dpService.sendPreferredAudioProfileChangeToAudioFramework()).isEqualTo(1);
    }

    @Test
    public void testDumpDoesNotCrash() {
        mA2dpService.dump(new StringBuilder());
    }

    private void connectDevice(BluetoothDevice device) {
        connectDeviceWithCodecStatus(device, null);
    }

    @Test
    public void testActiveDevice() {
        connectDevice(mDevice);

        /* Trigger setting active device */
        doReturn(true).when(mMockNativeInterface).setActiveDevice(any(BluetoothDevice.class));
        assertThat(mA2dpService.setActiveDevice(mDevice)).isTrue();

        /* Check if setting active devices sets right device */
        assertThat(mA2dpService.getActiveDevice()).isEqualTo(mDevice);

        /* Since A2dpService called AudioManager - assume Audio manager calls properly callback
         * mAudioManager.onAudioDeviceAdded
         */
        mA2dpService.updateAndBroadcastActiveDevice(mDevice);

        verifyIntentSent(
                hasAction(BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice));
    }

    private void connectDeviceWithCodecStatus(
            BluetoothDevice device, BluetoothCodecStatus codecStatus) {
        A2dpStackEvent connCompletedEvent;

        List<BluetoothDevice> prevConnectedDevices = mA2dpService.getConnectedDevices();

        // Update the device priority so okToConnect() returns true
        when(mDatabaseManager.getProfileConnectionPolicy(device, BluetoothProfile.A2DP))
                .thenReturn(CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mMockNativeInterface).connectA2dp(device);
        doReturn(true).when(mMockNativeInterface).disconnectA2dp(device);
        doReturn(true)
                .when(mMockNativeInterface)
                .setCodecConfigPreference(
                        any(BluetoothDevice.class), any(BluetoothCodecConfig[].class));

        // Send a connect request
        assertThat(mA2dpService.connect(device)).isTrue();
        dispatchAtLeastOneMessage();

        // Verify the connection state broadcast, and that we are in Connecting state
        verifyConnectionStateIntent(device, STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mA2dpService.getConnectionState(device)).isEqualTo(STATE_CONNECTING);

        if (codecStatus != null) {
            generateCodecMessageFromNative(device, codecStatus);
        }

        // Send a message to trigger connection completed
        connCompletedEvent = new A2dpStackEvent(A2dpStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connCompletedEvent.device = device;
        connCompletedEvent.valueInt = STATE_CONNECTED;
        mA2dpService.messageFromNative(connCompletedEvent);
        dispatchAtLeastOneMessage();

        // Verify the connection state broadcast, and that we are in Connected state
        verifyConnectionStateIntent(device, STATE_CONNECTED, STATE_CONNECTING);
        assertThat(mA2dpService.getConnectionState(device)).isEqualTo(STATE_CONNECTED);

        // Verify that the device is in the list of connected devices
        assertThat(mA2dpService.getConnectedDevices()).contains(device);
        // Verify the list of previously connected devices
        for (BluetoothDevice prevDevice : prevConnectedDevices) {
            assertThat(mA2dpService.getConnectedDevices()).contains(prevDevice);
        }

        dispatchNoMessages();
    }

    private void generateConnectionMessageFromNative(
            BluetoothDevice device, int newConnectionState, int oldConnectionState) {
        A2dpStackEvent stackEvent =
                new A2dpStackEvent(A2dpStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        stackEvent.device = device;
        stackEvent.valueInt = newConnectionState;
        mA2dpService.messageFromNative(stackEvent);
        dispatchAtLeastOneMessage();
        // Verify the connection state broadcast
        verifyConnectionStateIntent(device, newConnectionState, oldConnectionState);
        dispatchNoMessages();
    }

    private void generateUnexpectedConnectionMessageFromNative(
            BluetoothDevice device, int newConnectionState) {
        A2dpStackEvent stackEvent =
                new A2dpStackEvent(A2dpStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        stackEvent.device = device;
        stackEvent.valueInt = newConnectionState;
        mA2dpService.messageFromNative(stackEvent);
        // Verify the connection state broadcast
        mInOrder.verify(mAdapterService, timeout(TIMEOUT.toMillis()).times(0))
                .sendBroadcast(any(), any(), any());
        dispatchNoMessages();
    }

    private void generateAudioMessageFromNative(
            BluetoothDevice device, int audioStackEvent, int newAudioState, int oldAudioState) {
        A2dpStackEvent stackEvent =
                new A2dpStackEvent(A2dpStackEvent.EVENT_TYPE_AUDIO_STATE_CHANGED);
        stackEvent.device = device;
        stackEvent.valueInt = audioStackEvent;
        mA2dpService.messageFromNative(stackEvent);
        dispatchAtLeastOneMessage();
        // Verify the audio state broadcast
        verifyIntentSent(
                hasAction(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
                hasExtra(BluetoothProfile.EXTRA_STATE, newAudioState),
                hasExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, oldAudioState));
        dispatchNoMessages();
    }

    private void generateUnexpectedAudioMessageFromNative(
            BluetoothDevice device, int audioStackEvent) {
        A2dpStackEvent stackEvent =
                new A2dpStackEvent(A2dpStackEvent.EVENT_TYPE_AUDIO_STATE_CHANGED);
        stackEvent.device = device;
        stackEvent.valueInt = audioStackEvent;
        mA2dpService.messageFromNative(stackEvent);
        // Verify the audio state broadcast
        mInOrder.verify(mAdapterService, timeout(TIMEOUT.toMillis()).times(0))
                .sendBroadcast(any(), any(), any());
    }

    private void generateCodecMessageFromNative(
            BluetoothDevice device, BluetoothCodecStatus codecStatus) {
        A2dpStackEvent stackEvent =
                new A2dpStackEvent(A2dpStackEvent.EVENT_TYPE_CODEC_CONFIG_CHANGED);
        stackEvent.device = device;
        stackEvent.codecStatus = codecStatus;
        mA2dpService.messageFromNative(stackEvent);
        dispatchAtLeastOneMessage();
        verifyIntentSent(
                hasAction(BluetoothA2dp.ACTION_CODEC_CONFIG_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, device),
                hasExtra(BluetoothCodecStatus.EXTRA_CODEC_STATUS, codecStatus));
    }

    private void generateUnexpectedCodecMessageFromNative(
            BluetoothDevice device, BluetoothCodecStatus codecStatus) {
        A2dpStackEvent stackEvent =
                new A2dpStackEvent(A2dpStackEvent.EVENT_TYPE_CODEC_CONFIG_CHANGED);
        stackEvent.device = device;
        stackEvent.codecStatus = codecStatus;
        mA2dpService.messageFromNative(stackEvent);
        // Verify the codec status broadcast
        mInOrder.verify(mAdapterService, timeout(TIMEOUT.toMillis()).times(0))
                .sendBroadcast(any(), any(), any());
    }

    /**
     * Helper function to test okToConnect() method.
     *
     * @param device test device
     * @param bondState bond state value, could be invalid
     * @param priority value, could be invalid, could be invalid
     * @param expected expected result from okToConnect()
     */
    private void testOkToConnectCase(
            BluetoothDevice device, int bondState, int priority, boolean expected) {
        doReturn(bondState).when(mAdapterService).getBondState(device);
        when(mDatabaseManager.getProfileConnectionPolicy(device, BluetoothProfile.A2DP))
                .thenReturn(priority);

        // Test when the AdapterService is in non-quiet mode: the result should not depend
        // on whether the connection request is outgoing or incoming.
        doReturn(false).when(mAdapterService).isQuietModeEnabled();
        assertThat(mA2dpService.okToConnect(device, true)).isEqualTo(expected); // Outgoing
        assertThat(mA2dpService.okToConnect(device, false)).isEqualTo(expected); // Incoming

        // Test when the AdapterService is in quiet mode: the result should always be
        // false when the connection request is incoming.
        doReturn(true).when(mAdapterService).isQuietModeEnabled();
        assertThat(mA2dpService.okToConnect(device, true)).isEqualTo(expected); // Outgoing
        assertThat(mA2dpService.okToConnect(device, false)).isFalse(); // Incoming
    }

    /**
     * Helper function to test updateOptionalCodecsSupport() method
     *
     * @param previousSupport previous optional codec support status
     * @param support new optional codec support status
     * @param previousEnabled previous optional codec enable status
     * @param verifySupportTime verify times of optional codec set to support
     * @param verifyNotSupportTime verify times of optional codec set to not support
     * @param verifyEnabledTime verify times of optional codec set to enabled
     */
    private void testUpdateOptionalCodecsSupportCase(
            int previousSupport,
            boolean support,
            int previousEnabled,
            int verifySupportTime,
            int verifyNotSupportTime,
            int verifyEnabledTime) {
        doReturn(true).when(mMockNativeInterface).setActiveDevice(any(BluetoothDevice.class));

        BluetoothCodecConfig codecConfigSbc =
                buildBluetoothCodecConfig(
                        BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC,
                        BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                        BluetoothCodecConfig.SAMPLE_RATE_44100,
                        BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                        BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                        0,
                        0,
                        0,
                        0); // Codec-specific fields
        BluetoothCodecConfig codecConfigAac =
                buildBluetoothCodecConfig(
                        BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC,
                        BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                        BluetoothCodecConfig.SAMPLE_RATE_44100,
                        BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                        BluetoothCodecConfig.CHANNEL_MODE_STEREO,
                        0,
                        0,
                        0,
                        0); // Codec-specific fields

        BluetoothCodecConfig[] codecsLocalCapabilities;
        BluetoothCodecConfig[] codecsSelectableCapabilities;
        if (support) {
            codecsLocalCapabilities = new BluetoothCodecConfig[2];
            codecsSelectableCapabilities = new BluetoothCodecConfig[2];
            codecsLocalCapabilities[0] = codecConfigSbc;
            codecsLocalCapabilities[1] = codecConfigAac;
            codecsSelectableCapabilities[0] = codecConfigSbc;
            codecsSelectableCapabilities[1] = codecConfigAac;
        } else {
            codecsLocalCapabilities = new BluetoothCodecConfig[1];
            codecsSelectableCapabilities = new BluetoothCodecConfig[1];
            codecsLocalCapabilities[0] = codecConfigSbc;
            codecsSelectableCapabilities[0] = codecConfigSbc;
        }
        BluetoothCodecConfig[] badCodecsSelectableCapabilities;
        badCodecsSelectableCapabilities = new BluetoothCodecConfig[1];
        badCodecsSelectableCapabilities[0] = codecConfigAac;

        BluetoothCodecStatus codecStatus =
                new BluetoothCodecStatus(
                        codecConfigSbc,
                        Arrays.asList(codecsLocalCapabilities),
                        Arrays.asList(codecsSelectableCapabilities));
        BluetoothCodecStatus badCodecStatus =
                new BluetoothCodecStatus(
                        codecConfigAac,
                        Arrays.asList(codecsLocalCapabilities),
                        Arrays.asList(badCodecsSelectableCapabilities));

        when(mDatabaseManager.getA2dpSupportsOptionalCodecs(mDevice)).thenReturn(previousSupport);
        when(mDatabaseManager.getA2dpOptionalCodecsEnabled(mDevice)).thenReturn(previousEnabled);

        // Generate connection request from native with bad codec status
        connectDeviceWithCodecStatus(mDevice, badCodecStatus);
        generateConnectionMessageFromNative(mDevice, STATE_DISCONNECTED, STATE_CONNECTED);

        // Generate connection request from native with good codec status
        connectDeviceWithCodecStatus(mDevice, codecStatus);
        generateConnectionMessageFromNative(mDevice, STATE_DISCONNECTED, STATE_CONNECTED);

        // Check optional codec status is set properly
        verify(mDatabaseManager, times(verifyNotSupportTime))
                .setA2dpSupportsOptionalCodecs(
                        mDevice, BluetoothA2dp.OPTIONAL_CODECS_NOT_SUPPORTED);
        verify(mDatabaseManager, times(verifySupportTime))
                .setA2dpSupportsOptionalCodecs(mDevice, BluetoothA2dp.OPTIONAL_CODECS_SUPPORTED);
        verify(mDatabaseManager, times(verifyEnabledTime))
                .setA2dpOptionalCodecsEnabled(mDevice, BluetoothA2dp.OPTIONAL_CODECS_PREF_ENABLED);
    }

    private static BluetoothCodecConfig buildBluetoothCodecConfig(
            int sourceCodecType,
            int codecPriority,
            int sampleRate,
            int bitsPerSample,
            int channelMode,
            long codecSpecific1,
            long codecSpecific2,
            long codecSpecific3,
            long codecSpecific4) {
        return new BluetoothCodecConfig.Builder()
                .setCodecType(sourceCodecType)
                .setCodecPriority(codecPriority)
                .setSampleRate(sampleRate)
                .setBitsPerSample(bitsPerSample)
                .setChannelMode(channelMode)
                .setCodecSpecific1(codecSpecific1)
                .setCodecSpecific2(codecSpecific2)
                .setCodecSpecific3(codecSpecific3)
                .setCodecSpecific4(codecSpecific4)
                .build();
    }

    // Dispatch messages for the A2dpService looper, and validate
    // that at least one message was handled.
    private void dispatchAtLeastOneMessage() {
        assertThat(mLooper.dispatchAll()).isGreaterThan(0);
    }

    // Validate that no messages are pending on the A2dpService looper.
    private void dispatchNoMessages() {
        assertThat(mLooper.dispatchAll()).isEqualTo(0);
    }

    private void moveTimeForward(long millis) {
        mLooper.moveTimeForward(millis);
    }
}
