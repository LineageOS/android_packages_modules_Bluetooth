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

package com.android.bluetooth.a2dp;

import static android.bluetooth.BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED;
import static android.bluetooth.BluetoothA2dp.STATE_NOT_PLAYING;
import static android.bluetooth.BluetoothA2dp.STATE_PLAYING;
import static android.bluetooth.BluetoothProfile.EXTRA_PREVIOUS_STATE;
import static android.bluetooth.BluetoothProfile.EXTRA_STATE;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;

import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra;

import static com.android.bluetooth.TestUtils.getTestDevice;
import static com.android.bluetooth.a2dp.A2dpStateMachine.MESSAGE_AUDIO_STATE_CHANGED;
import static com.android.bluetooth.a2dp.A2dpStateMachine.MESSAGE_CONNECT;
import static com.android.bluetooth.a2dp.A2dpStateMachine.MESSAGE_CONNECTION_STATE_CHANGED;
import static com.android.bluetooth.a2dp.A2dpStateMachine.MESSAGE_DISCONNECT;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecStatus;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.os.Bundle;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;

import com.android.bluetooth.TestLooper;
import com.android.bluetooth.flags.Flags;
import com.android.tests.bluetooth.MockitoRule;

import org.hamcrest.Matcher;
import org.hamcrest.core.AllOf;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.hamcrest.MockitoHamcrest;

import java.util.Arrays;
import java.util.List;

/** Test cases for {@link A2dpStateMachine}. */
@MediumTest
@RunWith(AndroidJUnit4.class)
public class A2dpStateMachineTest {
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private A2dpService mService;
    @Mock private A2dpNativeInterface mNativeInterface;

    private final BluetoothDevice mDevice = getTestDevice(39);
    private final BluetoothCodecConfig mCodecConfigSbc =
            new BluetoothCodecConfig.Builder()
                    .setCodecType(BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC)
                    .setCodecPriority(BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT)
                    .setSampleRate(BluetoothCodecConfig.SAMPLE_RATE_44100)
                    .setBitsPerSample(BluetoothCodecConfig.BITS_PER_SAMPLE_16)
                    .setChannelMode(BluetoothCodecConfig.CHANNEL_MODE_STEREO)
                    .setCodecSpecific1(0)
                    .setCodecSpecific2(0)
                    .setCodecSpecific3(0)
                    .setCodecSpecific4(0)
                    .build();
    private final BluetoothCodecConfig mCodecConfigAac =
            new BluetoothCodecConfig.Builder()
                    .setCodecType(BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC)
                    .setCodecPriority(BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT)
                    .setSampleRate(BluetoothCodecConfig.SAMPLE_RATE_48000)
                    .setBitsPerSample(BluetoothCodecConfig.BITS_PER_SAMPLE_16)
                    .setChannelMode(BluetoothCodecConfig.CHANNEL_MODE_STEREO)
                    .setCodecSpecific1(0)
                    .setCodecSpecific2(0)
                    .setCodecSpecific3(0)
                    .setCodecSpecific4(0)
                    .build();
    private final BluetoothCodecConfig mCodecConfigOpus =
            new BluetoothCodecConfig.Builder()
                    .setCodecType(BluetoothCodecConfig.SOURCE_CODEC_TYPE_OPUS)
                    .setCodecPriority(BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT)
                    .setSampleRate(BluetoothCodecConfig.SAMPLE_RATE_48000)
                    .setBitsPerSample(BluetoothCodecConfig.BITS_PER_SAMPLE_16)
                    .setChannelMode(BluetoothCodecConfig.CHANNEL_MODE_STEREO)
                    .setCodecSpecific1(0)
                    .setCodecSpecific2(0)
                    .setCodecSpecific3(0)
                    .setCodecSpecific4(0)
                    .build();

    private InOrder mInOrder;
    private TestLooper mLooper;
    private A2dpStateMachine mStateMachine;

    @Before
    public void setUp() throws Exception {
        doReturn(true).when(mService).okToConnect(any(), anyBoolean());

        doReturn(true).when(mNativeInterface).connectA2dp(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectA2dp(any(BluetoothDevice.class));

        mInOrder = inOrder(mService);
        mLooper = new TestLooper();

        mStateMachine =
                new A2dpStateMachine(
                        mService, mDevice, mNativeInterface, false, mLooper.getLooper());
    }

    @Test
    public void initialState_isDisconnected() {
        assertThat(mStateMachine.getConnectionState()).isEqualTo(STATE_DISCONNECTED);
    }

    @Test
    public void incomingConnect_whenNotOkToConnect_isRejected() {
        doReturn(false).when(mService).okToConnect(any(), anyBoolean());

        // Inject an event for when incoming connection is requested
        sendAndDispatchMessage(MESSAGE_CONNECTION_STATE_CHANGED, STATE_CONNECTED);

        verify(mService, never()).sendBroadcast(any(Intent.class), anyString(), any(Bundle.class));
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(A2dpStateMachine.Disconnected.class);
    }

    @Test
    public void incomingConnect_whenOkToConnect_isConnected() {
        generateConnectionMessageFromNative(STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mStateMachine.getCurrentState()).isInstanceOf(A2dpStateMachine.Connecting.class);

        generateConnectionMessageFromNative(STATE_CONNECTED, STATE_CONNECTING);
        assertThat(mStateMachine.getCurrentState()).isInstanceOf(A2dpStateMachine.Connected.class);

        verifyIntentSent(
                hasAction(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED),
                hasExtra(EXTRA_STATE, BluetoothA2dp.STATE_NOT_PLAYING),
                hasExtra(EXTRA_PREVIOUS_STATE, BluetoothA2dp.STATE_PLAYING));
    }

    @Test
    public void outgoingConnect_whenTimeOut_isDisconnectedAndInAcceptList() {
        sendAndDispatchMessage(MESSAGE_CONNECT, mDevice);
        verifyConnectionStateIntent(STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mStateMachine.getCurrentState()).isInstanceOf(A2dpStateMachine.Connecting.class);

        mLooper.moveTimeForward(A2dpStateMachine.CONNECT_TIMEOUT.toMillis());
        mLooper.dispatchAll();

        verifyConnectionStateIntent(STATE_DISCONNECTED, STATE_CONNECTING);
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(A2dpStateMachine.Disconnected.class);
    }

    @Test
    public void incomingConnect_whenTimeOut_isDisconnectedAndInAcceptList() {
        generateConnectionMessageFromNative(STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mStateMachine.getCurrentState()).isInstanceOf(A2dpStateMachine.Connecting.class);

        mLooper.moveTimeForward(A2dpStateMachine.CONNECT_TIMEOUT.toMillis());
        mLooper.dispatchAll();

        verifyConnectionStateIntent(STATE_DISCONNECTED, STATE_CONNECTING);
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(A2dpStateMachine.Disconnected.class);
    }

    /** Test that codec config change been reported to A2dpService properly. */
    @Test
    public void testProcessCodecConfigEvent() {
        testProcessCodecConfigEventCase(false);
    }

    /**
     * Test that codec config change been reported to A2dpService properly when A2DP hardware
     * offloading is enabled.
     */
    @Test
    public void testProcessCodecConfigEvent_OffloadEnabled() {
        // create mStateMachine with offload enabled
        mStateMachine =
                new A2dpStateMachine(
                        mService, mDevice, mNativeInterface, true, mLooper.getLooper());

        testProcessCodecConfigEventCase(true);
    }

    /** Helper method to test processCodecConfigEvent() */
    public void testProcessCodecConfigEventCase(boolean offloadEnabled) {
        doNothing()
                .when(mService)
                .codecConfigUpdated(
                        any(BluetoothDevice.class), any(BluetoothCodecStatus.class), anyBoolean());

        BluetoothCodecConfig[] codecsSelectableSbc;
        codecsSelectableSbc = new BluetoothCodecConfig[1];
        codecsSelectableSbc[0] = mCodecConfigSbc;

        BluetoothCodecConfig[] codecsSelectableSbcAac;
        codecsSelectableSbcAac = new BluetoothCodecConfig[2];
        codecsSelectableSbcAac[0] = mCodecConfigSbc;
        codecsSelectableSbcAac[1] = mCodecConfigAac;

        BluetoothCodecConfig[] codecsSelectableSbcAacOpus;
        codecsSelectableSbcAacOpus = new BluetoothCodecConfig[3];
        codecsSelectableSbcAacOpus[0] = mCodecConfigSbc;
        codecsSelectableSbcAacOpus[1] = mCodecConfigAac;
        codecsSelectableSbcAacOpus[2] = mCodecConfigOpus;

        BluetoothCodecStatus codecStatusSbcAndSbc =
                new BluetoothCodecStatus(
                        mCodecConfigSbc,
                        Arrays.asList(codecsSelectableSbcAac),
                        Arrays.asList(codecsSelectableSbc));
        BluetoothCodecStatus codecStatusSbcAndSbcAac =
                new BluetoothCodecStatus(
                        mCodecConfigSbc,
                        Arrays.asList(codecsSelectableSbcAac),
                        Arrays.asList(codecsSelectableSbcAac));
        BluetoothCodecStatus codecStatusAacAndSbcAac =
                new BluetoothCodecStatus(
                        mCodecConfigAac,
                        Arrays.asList(codecsSelectableSbcAac),
                        Arrays.asList(codecsSelectableSbcAac));
        BluetoothCodecStatus codecStatusOpusAndSbcAacOpus =
                new BluetoothCodecStatus(
                        mCodecConfigOpus,
                        Arrays.asList(codecsSelectableSbcAacOpus),
                        Arrays.asList(codecsSelectableSbcAacOpus));

        // Set default codec status when device disconnected
        // Selected codec = SBC, selectable codec = SBC
        mStateMachine.processCodecConfigEvent(codecStatusSbcAndSbc);
        verify(mService).codecConfigUpdated(mDevice, codecStatusSbcAndSbc, false);
        verify(mService).updateLowLatencyAudioSupport(mDevice);

        generateConnectionMessageFromNative(STATE_CONNECTED, STATE_DISCONNECTED);

        // Verify that state machine update optional codec when enter connected state
        verify(mService).updateOptionalCodecsSupport(mDevice);
        verify(mService, times(2)).updateLowLatencyAudioSupport(mDevice);

        // Change codec status when device connected.
        // Selected codec = SBC, selectable codec = SBC+AAC
        mStateMachine.processCodecConfigEvent(codecStatusSbcAndSbcAac);
        if (!offloadEnabled) {
            verify(mService).codecConfigUpdated(mDevice, codecStatusSbcAndSbcAac, true);
        }
        verify(mService, times(2)).updateOptionalCodecsSupport(mDevice);
        verify(mService, times(3)).updateLowLatencyAudioSupport(mDevice);

        // Update selected codec with selectable codec unchanged.
        // Selected codec = AAC, selectable codec = SBC+AAC
        mStateMachine.processCodecConfigEvent(codecStatusAacAndSbcAac);
        verify(mService).codecConfigUpdated(mDevice, codecStatusAacAndSbcAac, false);
        verify(mService, times(2)).updateOptionalCodecsSupport(mDevice);
        verify(mService, times(4)).updateLowLatencyAudioSupport(mDevice);

        // Update selected codec
        // Selected codec = OPUS, selectable codec = SBC+AAC+OPUS
        mStateMachine.processCodecConfigEvent(codecStatusOpusAndSbcAacOpus);
        if (!offloadEnabled) {
            verify(mService).codecConfigUpdated(mDevice, codecStatusOpusAndSbcAacOpus, true);
        }
        verify(mService, times(3)).updateOptionalCodecsSupport(mDevice);
        // Check if low latency audio been updated.
        verify(mService, times(5)).updateLowLatencyAudioSupport(mDevice);

        // Update selected codec with selectable codec changed.
        // Selected codec = SBC, selectable codec = SBC+AAC
        mStateMachine.processCodecConfigEvent(codecStatusSbcAndSbcAac);
        if (!offloadEnabled) {
            verify(mService).codecConfigUpdated(mDevice, codecStatusSbcAndSbcAac, true);
        }
        // Check if low latency audio been update.
        verify(mService, times(6)).updateLowLatencyAudioSupport(mDevice);
    }

    @Test
    public void dump_doesNotCrash() {
        BluetoothCodecConfig[] codecsSelectableSbc;
        codecsSelectableSbc = new BluetoothCodecConfig[1];
        codecsSelectableSbc[0] = mCodecConfigSbc;

        BluetoothCodecConfig[] codecsSelectableSbcAac;
        codecsSelectableSbcAac = new BluetoothCodecConfig[2];
        codecsSelectableSbcAac[0] = mCodecConfigSbc;
        codecsSelectableSbcAac[1] = mCodecConfigAac;

        BluetoothCodecStatus codecStatusSbcAndSbc =
                new BluetoothCodecStatus(
                        mCodecConfigSbc,
                        Arrays.asList(codecsSelectableSbcAac),
                        Arrays.asList(codecsSelectableSbc));
        mStateMachine.processCodecConfigEvent(codecStatusSbcAndSbc);

        mStateMachine.dump(new StringBuilder());
    }

    @Test
    public void connectEventNeglectedWhileInConnectingState() {
        sendAndDispatchMessage(MESSAGE_CONNECT, mDevice);
        verifyConnectionStateIntent(STATE_CONNECTING, STATE_DISCONNECTED);
        assertThat(mStateMachine.getCurrentState()).isInstanceOf(A2dpStateMachine.Connecting.class);

        // Dispatch CONNECT event twice more
        sendAndDispatchMessage(MESSAGE_CONNECT, mDevice);
        sendAndDispatchMessage(MESSAGE_CONNECT, mDevice);
        sendAndDispatchMessage(MESSAGE_DISCONNECT, mDevice);
        verifyConnectionStateIntent(STATE_DISCONNECTED, STATE_CONNECTING);
        assertThat(mStateMachine.getCurrentState())
                .isInstanceOf(A2dpStateMachine.Disconnected.class);
        assertThat(mLooper.dispatchAll()).isEqualTo(0);
    }

    @Test
    public void audioStateChange_sendsBroadcast() {
        // Start in connected state
        generateConnectionMessageFromNative(STATE_CONNECTING, STATE_DISCONNECTED);
        generateConnectionMessageFromNative(STATE_CONNECTED, STATE_CONNECTING);
        assertThat(mStateMachine.getCurrentState()).isInstanceOf(A2dpStateMachine.Connected.class);
        // Verify initial audio state broadcast. This is also verified in another test,
        // but serves as a good baseline here.
        verifyIntentSent(
                hasAction(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED),
                hasExtra(EXTRA_STATE, STATE_NOT_PLAYING),
                hasExtra(EXTRA_PREVIOUS_STATE, STATE_PLAYING));
        assertThat(mStateMachine.isPlaying()).isFalse();

        // --- Test audio started ---
        sendAndDispatchMessage(MESSAGE_AUDIO_STATE_CHANGED, A2dpNativeCallback.AUDIO_STATE_STARTED);

        // Verify broadcast and state for playing
        verifyIntentSent(
                hasAction(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED),
                hasExtra(EXTRA_STATE, STATE_PLAYING),
                hasExtra(EXTRA_PREVIOUS_STATE, STATE_NOT_PLAYING));
        assertThat(mStateMachine.isPlaying()).isTrue();

        // --- Test audio stopped ---
        sendAndDispatchMessage(MESSAGE_AUDIO_STATE_CHANGED, A2dpNativeCallback.AUDIO_STATE_STOPPED);

        // Verify broadcast and state for not playing
        verifyIntentSent(
                hasAction(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED),
                hasExtra(EXTRA_STATE, STATE_NOT_PLAYING),
                hasExtra(EXTRA_PREVIOUS_STATE, STATE_PLAYING));
        assertThat(mStateMachine.isPlaying()).isFalse();
    }

    private void sendAndDispatchMessage(int what, Object obj) {
        mStateMachine.sendMessage(what, obj);
        mLooper.dispatchAll();
    }

    private void sendAndDispatchMessage(int what, int obj) {
        mStateMachine.sendMessage(what, obj);
        mLooper.dispatchAll();
    }

    @SafeVarargs
    private void verifyIntentSent(Matcher<Intent>... matchers) {
        mInOrder.verify(mService)
                .sendBroadcast(MockitoHamcrest.argThat(AllOf.allOf(matchers)), any(), any());
    }

    private void verifyConnectionStateIntent(int newState, int oldState) {
        verifyIntentSent(
                hasAction(ACTION_CONNECTION_STATE_CHANGED),
                hasExtra(BluetoothDevice.EXTRA_DEVICE, mDevice),
                hasExtra(EXTRA_STATE, newState),
                hasExtra(EXTRA_PREVIOUS_STATE, oldState));
        assertThat(mStateMachine.getConnectionState()).isEqualTo(newState);
    }

    private void generateConnectionMessageFromNative(int newState, int oldState) {
        sendAndDispatchMessage(MESSAGE_CONNECTION_STATE_CHANGED, newState);
        verifyConnectionStateIntent(newState, oldState);
    }
}
