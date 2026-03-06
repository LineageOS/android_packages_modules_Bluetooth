/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.bluetooth.a2dpsink;

import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_UNKNOWN;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_CONNECTING;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTED;
import static android.bluetooth.BluetoothProfile.STATE_DISCONNECTING;

import static com.android.bluetooth.TestUtils.getTestDevice;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothDevice;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.bluetooth.TestLooper;
import com.android.bluetooth.TestUtils;
import com.android.bluetooth.flags.Flags;
import com.android.bluetooth.media_audio.sink.AudioSource;
import com.android.bluetooth.media_audio.sink.MediaAudioServer;
import com.android.tests.bluetooth.MockitoRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

/** Test cases for {@link A2dpSinkStateMachine}. */
@RunWith(AndroidJUnit4.class)
public class A2dpSinkStateMachineTest {
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();
    @Rule public final MockitoRule mMockitoRule = new MockitoRule();

    @Mock private A2dpSinkService mService;
    @Mock private MediaAudioServer mMediaAudioServer;
    @Mock private A2dpSinkNativeInterface mNativeInterface;

    private final BluetoothDevice mDevice = getTestDevice(11);

    private TestLooper mLooper;
    private A2dpSinkStateMachine mStateMachine;

    private AudioSource mAudioSource;
    @Mock private AudioSource.Callback mAudioSourceCallbacks;

    @Before
    public void setUp() throws Exception {
        mLooper = new TestLooper();

        mStateMachine =
                new A2dpSinkStateMachine(
                        mService,
                        mDevice,
                        mMediaAudioServer,
                        mLooper.getLooper(),
                        mNativeInterface);

        assertThat(mStateMachine.getDevice()).isEqualTo(mDevice);
        assertThat(mStateMachine.isPlaying()).isFalse();
        assertThat(mStateMachine.getState()).isEqualTo(STATE_DISCONNECTED);
    }

    @After
    public void tearDown() throws Exception {
        assertThat(mLooper.nextMessage()).isNull();
    }

    private void syncHandler(int... what) {
        TestUtils.syncHandler(mLooper, what);
    }

    private void mockDeviceConnectionPolicy(BluetoothDevice device, int policy) {
        doReturn(policy).when(mService).getConnectionPolicy(device);
    }

    private void sendConnectionEvent(int state) {
        mStateMachine.sendMessage(A2dpSinkStateMachine.MESSAGE_CONNECTION_STATE_CHANGED, state);
        syncHandler(A2dpSinkStateMachine.MESSAGE_CONNECTION_STATE_CHANGED);
    }

    private void sendAudioConfigChangedEvent(int sampleRate, int channelCount) {
        mStateMachine.onAudioConfigChanged(sampleRate, channelCount);
        syncHandler(A2dpSinkStateMachine.MESSAGE_AUDIO_CONFIG_CHANGED);
    }

    private void sendAudioStateChangedEvent(int state) {
        mStateMachine.onAudioStateChanged(state);
        syncHandler(A2dpSinkStateMachine.MESSAGE_AUDIO_STATE_CHANGED);
    }

    private void verifyAndCaptureAudioSourceRegistration() {
        var captor = ArgumentCaptor.forClass(AudioSource.class);
        verify(mMediaAudioServer).registerAudioSource(captor.capture());
        assertThat(captor.getAllValues()).hasSize(1);
        mAudioSource = captor.getValue();

        assertThat(mAudioSource).isNotNull();
        assertThat(mAudioSource.getStreamState()).isEqualTo(AudioSource.StreamState.IDLE);
        mAudioSource.registerCallback(mAudioSourceCallbacks);
    }

    /**********************************************************************************************
     * DISCONNECTED STATE TESTS                                                                   *
     *********************************************************************************************/

    @Test
    public void testConnectInDisconnected() {
        mStateMachine.sendMessage(A2dpSinkStateMachine.MESSAGE_CONNECT);
        syncHandler(A2dpSinkStateMachine.MESSAGE_CONNECT);
        verify(mNativeInterface).connectA2dpSink(mDevice);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_CONNECTING);
    }

    @Test
    public void testDisconnectInDisconnected() {
        mStateMachine.disconnect();
        syncHandler(A2dpSinkStateMachine.MESSAGE_DISCONNECT);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_DISCONNECTED);
    }

    @Test
    public void testAudioConfigChangedInDisconnected() {
        sendAudioConfigChangedEvent(44, 1);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_DISCONNECTED);
    }

    @Test
    public void testAllowedConnectedInDisconnected() {
        mockDeviceConnectionPolicy(mDevice, CONNECTION_POLICY_ALLOWED);
        sendConnectionEvent(STATE_CONNECTED);
        verify(mService).connectionStateChanged(mDevice, STATE_DISCONNECTED, STATE_CONNECTING);
        verify(mService).connectionStateChanged(mDevice, STATE_CONNECTING, STATE_CONNECTED);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_CONNECTED);
    }

    @Test
    public void testForbiddenConnectedInDisconnected() {
        mockDeviceConnectionPolicy(mDevice, CONNECTION_POLICY_FORBIDDEN);

        sendConnectionEvent(STATE_CONNECTED);
        verify(mNativeInterface).disconnectA2dpSink(mDevice);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_DISCONNECTED);
    }

    @Test
    public void testAllowedIncomingConnectionInDisconnected() {
        mockDeviceConnectionPolicy(mDevice, CONNECTION_POLICY_ALLOWED);

        sendConnectionEvent(STATE_CONNECTING);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_CONNECTING);
        verify(mNativeInterface, times(0)).connectA2dpSink(mDevice);
    }

    @Test
    public void testForbiddenIncomingConnectionInDisconnected() {
        mockDeviceConnectionPolicy(mDevice, CONNECTION_POLICY_FORBIDDEN);

        sendConnectionEvent(STATE_CONNECTING);
        verify(mNativeInterface).disconnectA2dpSink(mDevice);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_DISCONNECTED);
    }

    @Test
    public void testUnknownIncomingConnectionInDisconnected() {
        mockDeviceConnectionPolicy(mDevice, CONNECTION_POLICY_UNKNOWN);

        sendConnectionEvent(STATE_CONNECTING);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_CONNECTING);
        verify(mNativeInterface, times(0)).connectA2dpSink(mDevice);
    }

    @Test
    public void testIncomingDisconnectInDisconnected() {
        sendConnectionEvent(STATE_DISCONNECTED);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_DISCONNECTED);

        syncHandler(A2dpSinkStateMachine.CLEANUP);
        verify(mService).removeStateMachine(mStateMachine);
    }

    @Test
    public void testIncomingDisconnectingInDisconnected() {
        sendConnectionEvent(STATE_DISCONNECTING);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_DISCONNECTED);
        verify(mService, times(0)).removeStateMachine(mStateMachine);
    }

    @Test
    public void testIncomingConnectingInDisconnected() {
        sendConnectionEvent(STATE_CONNECTING);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_DISCONNECTED);
    }

    @Test
    public void testUnhandledMessageInDisconnected() {
        final int UNHANDLED_MESSAGE = 9999;
        mStateMachine.sendMessage(UNHANDLED_MESSAGE);
        mStateMachine.sendMessage(UNHANDLED_MESSAGE, 0 /* arbitrary payload */);
        syncHandler(UNHANDLED_MESSAGE, UNHANDLED_MESSAGE);
    }

    /**********************************************************************************************
     * CONNECTING STATE TESTS                                                                     *
     *********************************************************************************************/

    @Test
    public void testConnectedInConnecting() {
        testConnectInDisconnected();

        sendConnectionEvent(STATE_CONNECTED);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_CONNECTED);
    }

    @Test
    public void testConnectingInConnecting() {
        testConnectInDisconnected();

        sendConnectionEvent(STATE_CONNECTING);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_CONNECTING);
    }

    @Test
    public void testDisconnectingInConnecting() {
        testConnectInDisconnected();

        sendConnectionEvent(STATE_DISCONNECTING);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_CONNECTING);
    }

    @Test
    public void testDisconnectedInConnecting() {
        testConnectInDisconnected();

        sendConnectionEvent(STATE_DISCONNECTED);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_DISCONNECTED);

        syncHandler(A2dpSinkStateMachine.CLEANUP);
        verify(mService).removeStateMachine(mStateMachine);
    }

    @Test
    public void testConnectionTimeoutInConnecting() {
        testConnectInDisconnected();

        mLooper.moveTimeForward(120_000); // Skip time so the timeout fires
        syncHandler(A2dpSinkStateMachine.MESSAGE_CONNECT_TIMEOUT);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_DISCONNECTED);

        syncHandler(A2dpSinkStateMachine.CLEANUP);
        verify(mService).removeStateMachine(mStateMachine);
    }

    @Test
    public void testAudioConfigChangeInConnecting() {
        testConnectInDisconnected();

        sendAudioConfigChangedEvent(44, 1);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_CONNECTING);
    }

    @Test
    public void testConnectInConnecting() {
        testConnectInDisconnected();

        mStateMachine.sendMessage(A2dpSinkStateMachine.MESSAGE_CONNECT);
        syncHandler(A2dpSinkStateMachine.MESSAGE_CONNECT);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_CONNECTING);
    }

    @Test
    public void testDisconnectInConnecting_disconnectDeferredAndProcessed() {
        testConnectInDisconnected();

        mStateMachine.disconnect();
        syncHandler(A2dpSinkStateMachine.MESSAGE_DISCONNECT);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_CONNECTING);

        // send connected, disconnect should get processed
        sendConnectionEvent(STATE_CONNECTED);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_CONNECTED);

        syncHandler(A2dpSinkStateMachine.MESSAGE_DISCONNECT); // message was defer
        verify(mNativeInterface).disconnectA2dpSink(mDevice);
        sendConnectionEvent(STATE_DISCONNECTING);
        sendConnectionEvent(STATE_DISCONNECTED);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_DISCONNECTED);

        syncHandler(A2dpSinkStateMachine.CLEANUP);
        verify(mService).removeStateMachine(mStateMachine);
    }

    /**********************************************************************************************
     * CONNECTED STATE TESTS                                                                      *
     *********************************************************************************************/

    @Test
    public void testConnectInConnected() {
        testConnectedInConnecting();

        mStateMachine.sendMessage(A2dpSinkStateMachine.MESSAGE_CONNECT);
        syncHandler(A2dpSinkStateMachine.MESSAGE_CONNECT);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_CONNECTED);
    }

    @Test
    public void testDisconnectInConnected() {
        testConnectedInConnecting();

        mStateMachine.disconnect();
        syncHandler(A2dpSinkStateMachine.MESSAGE_DISCONNECT);
        verify(mNativeInterface).disconnectA2dpSink(mDevice);
        sendConnectionEvent(STATE_DISCONNECTING);
        sendConnectionEvent(STATE_DISCONNECTED);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_DISCONNECTED);

        syncHandler(A2dpSinkStateMachine.CLEANUP);
        verify(mService).removeStateMachine(mStateMachine);
    }

    @Test
    public void testAudioConfigChangeInConnected() {
        testConnectedInConnecting();

        sendAudioConfigChangedEvent(44, 1);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_CONNECTED);
    }

    @Test
    public void testConnectedInConnected() {
        testConnectedInConnecting();

        sendConnectionEvent(STATE_CONNECTED);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_CONNECTED);
    }

    @Test
    public void testConnectingInConnected() {
        testConnectedInConnecting();

        sendConnectionEvent(STATE_CONNECTING);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_CONNECTED);
    }

    @Test
    public void testDisconnectingInConnected() {
        testConnectedInConnecting();

        sendConnectionEvent(STATE_DISCONNECTING);

        verify(mService).connectionStateChanged(mDevice, STATE_CONNECTED, STATE_DISCONNECTING);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_DISCONNECTING);
    }

    @Test
    public void testDisconnectedInConnected() {
        testConnectedInConnecting();

        sendConnectionEvent(STATE_DISCONNECTED);

        verify(mService).connectionStateChanged(mDevice, STATE_CONNECTED, STATE_DISCONNECTING);
        verify(mService).connectionStateChanged(mDevice, STATE_DISCONNECTING, STATE_DISCONNECTED);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_DISCONNECTED);

        syncHandler(A2dpSinkStateMachine.CLEANUP);
        verify(mService).removeStateMachine(mStateMachine);
    }

    @Test
    @EnableFlags(Flags.FLAG_MEDIA_AUDIO_SERVER)
    public void testConnectedEnter_audioSourceRegisteredWithMediaServer() {
        // TODO(Flags.media_audio_server): Can move this as a pass criteria for all tests entering
        // connected. This is easy for now though, and provides a flagged basis for other tests too
        testConnectedInConnecting();
        verifyAndCaptureAudioSourceRegistration();
    }

    @Test
    @EnableFlags(Flags.FLAG_MEDIA_AUDIO_SERVER)
    public void testConnectedExit_audioSourceUnregisteredWithMediaServer() {
        // TODO(Flags.media_audio_server): Can move this as a pass criteria for all tests exiting
        // connected. This is easy for now though
        testConnectedEnter_audioSourceRegisteredWithMediaServer();

        sendConnectionEvent(STATE_DISCONNECTING);

        verify(mMediaAudioServer).unregisterAudioSource(mAudioSource);
        verify(mService).connectionStateChanged(mDevice, STATE_CONNECTED, STATE_DISCONNECTING);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_DISCONNECTING);
    }

    @Test
    @EnableFlags(Flags.FLAG_MEDIA_AUDIO_SERVER)
    public void testAudioStateChangedInConnected_toStopped_stateOpen() {
        testConnectedEnter_audioSourceRegisteredWithMediaServer();

        sendAudioStateChangedEvent(A2dpSinkNativeInterface.AUDIO_STATE_STOPPED);
        verify(mAudioSourceCallbacks).onStreamStateChanged(AudioSource.StreamState.OPEN);
        assertThat(mAudioSource.getStreamState()).isEqualTo(AudioSource.StreamState.OPEN);
    }

    @Test
    @EnableFlags(Flags.FLAG_MEDIA_AUDIO_SERVER)
    public void testAudioStateChangedInConnected_toRemoteSuspended_stateOpen() {
        testConnectedEnter_audioSourceRegisteredWithMediaServer();

        sendAudioStateChangedEvent(A2dpSinkNativeInterface.AUDIO_STATE_REMOTE_SUSPEND);
        verify(mAudioSourceCallbacks).onStreamStateChanged(AudioSource.StreamState.OPEN);
        assertThat(mAudioSource.getStreamState()).isEqualTo(AudioSource.StreamState.OPEN);
    }

    @Test
    @EnableFlags(Flags.FLAG_MEDIA_AUDIO_SERVER)
    public void testAudioStateChangedInConnected_toStarted_stateStreaming() {
        testConnectedEnter_audioSourceRegisteredWithMediaServer();

        sendAudioStateChangedEvent(A2dpSinkNativeInterface.AUDIO_STATE_STARTED);
        verify(mAudioSourceCallbacks).onStreamStateChanged(AudioSource.StreamState.STREAMING);
        assertThat(mAudioSource.getStreamState()).isEqualTo(AudioSource.StreamState.STREAMING);
        assertThat(mStateMachine.isPlaying()).isTrue();
    }

    @Test
    @EnableFlags(Flags.FLAG_MEDIA_AUDIO_SERVER)
    public void testAudioStateChangedInConnected_toRemoteSuspendFromStarted_stateOpen() {
        testConnectedEnter_audioSourceRegisteredWithMediaServer();

        sendAudioStateChangedEvent(A2dpSinkNativeInterface.AUDIO_STATE_STARTED);
        verify(mAudioSourceCallbacks).onStreamStateChanged(AudioSource.StreamState.STREAMING);

        sendAudioStateChangedEvent(A2dpSinkNativeInterface.AUDIO_STATE_REMOTE_SUSPEND);
        verify(mAudioSourceCallbacks).onStreamStateChanged(AudioSource.StreamState.OPEN);
        assertThat(mAudioSource.getStreamState()).isEqualTo(AudioSource.StreamState.OPEN);
    }

    @Test
    @EnableFlags(Flags.FLAG_MEDIA_AUDIO_SERVER)
    public void testAudioStateChangedInConnected_toStoppedFromStarted_stateOpen() {
        testConnectedEnter_audioSourceRegisteredWithMediaServer();

        sendAudioStateChangedEvent(A2dpSinkNativeInterface.AUDIO_STATE_STARTED);
        verify(mAudioSourceCallbacks).onStreamStateChanged(AudioSource.StreamState.STREAMING);

        sendAudioStateChangedEvent(A2dpSinkNativeInterface.AUDIO_STATE_STOPPED);
        verify(mAudioSourceCallbacks).onStreamStateChanged(AudioSource.StreamState.OPEN);
        assertThat(mAudioSource.getStreamState()).isEqualTo(AudioSource.StreamState.OPEN);
    }

    @Test
    @EnableFlags(Flags.FLAG_MEDIA_AUDIO_SERVER)
    public void testAudioConfigChangedInConnected_stateConfiguredToStateOpen() {
        testConnectedEnter_audioSourceRegisteredWithMediaServer();

        sendAudioConfigChangedEvent(44, 1);
        verify(mAudioSourceCallbacks).onStreamStateChanged(AudioSource.StreamState.CONFIGURED);
        verify(mAudioSourceCallbacks).onStreamStateChanged(AudioSource.StreamState.OPEN);
        assertThat(mAudioSource.getStreamState()).isEqualTo(AudioSource.StreamState.OPEN);
    }

    /**********************************************************************************************
     * AUDIO SOURCE TESTS                                                                         *
     *********************************************************************************************/

    @Test
    @EnableFlags(Flags.FLAG_MEDIA_AUDIO_SERVER)
    public void testPrepare_activeDeviceSet() {
        testConnectedEnter_audioSourceRegisteredWithMediaServer();

        mAudioSource.prepare();

        verify(mNativeInterface).setActiveDevice(mDevice);
    }

    @Test
    @EnableFlags(Flags.FLAG_MEDIA_AUDIO_SERVER)
    public void testStartStream_gainSetToFullAndFocusNotified() {
        testConnectedEnter_audioSourceRegisteredWithMediaServer();

        mAudioSource.start();

        verify(mNativeInterface).informAudioTrackGain(1.0f);
        verify(mNativeInterface).informAudioFocusState(A2dpSinkNativeInterface.STATE_FOCUS_GRANTED);
    }

    @Test
    @EnableFlags(Flags.FLAG_MEDIA_AUDIO_SERVER)
    public void testSuspendStream_gainSetToZeroAndFocusNotified() {
        testConnectedEnter_audioSourceRegisteredWithMediaServer();

        mAudioSource.suspend();

        verify(mNativeInterface).informAudioTrackGain(0.0f);
        verify(mNativeInterface).informAudioFocusState(A2dpSinkNativeInterface.STATE_FOCUS_LOST);
    }

    @Test
    @EnableFlags(Flags.FLAG_MEDIA_AUDIO_SERVER)
    public void testRelease_activeDeviceRemovedGainSetToZeroAndFocusNotified() {
        testPrepare_activeDeviceSet();

        mAudioSource.release();

        verify(mNativeInterface).setActiveDevice(null);
        verify(mNativeInterface).informAudioTrackGain(0.0f);
        verify(mNativeInterface).informAudioFocusState(A2dpSinkNativeInterface.STATE_FOCUS_LOST);
    }

    /**********************************************************************************************
     * DISCONNECTING STATE TESTS                                                                  *
     *********************************************************************************************/

    @Test
    public void testDisconnectedInDisconnecting_proceedsToDisconnected() {
        testDisconnectingInConnected();

        sendConnectionEvent(STATE_DISCONNECTED);

        verify(mService).connectionStateChanged(mDevice, STATE_DISCONNECTING, STATE_DISCONNECTED);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_DISCONNECTED);

        syncHandler(A2dpSinkStateMachine.CLEANUP);
        verify(mService).removeStateMachine(mStateMachine);
    }

    @Test
    public void testDisconnectTimeoutInDisconnecting_proceedsToDisconnected() {
        testDisconnectingInConnected();

        mLooper.moveTimeForward(120_000); // Skip time so the timeout fires
        syncHandler(A2dpSinkStateMachine.MESSAGE_DISCONNECT_TIMEOUT);

        verify(mService).connectionStateChanged(mDevice, STATE_DISCONNECTING, STATE_DISCONNECTED);
        assertThat(mStateMachine.getState()).isEqualTo(STATE_DISCONNECTED);

        syncHandler(A2dpSinkStateMachine.CLEANUP);
        verify(mService).removeStateMachine(mStateMachine);
    }

    @Test
    public void testDisconnectRequestInDisconnecting_requestDeferred() {
        testDisconnectingInConnected();
        clearInvocations(mNativeInterface);

        mStateMachine.sendMessage(A2dpSinkStateMachine.MESSAGE_DISCONNECT);
        syncHandler(A2dpSinkStateMachine.MESSAGE_DISCONNECT);

        verify(mNativeInterface, never()).disconnectA2dpSink(any());
        assertThat(mStateMachine.getState()).isEqualTo(STATE_DISCONNECTING);
    }

    @Test
    public void testConnectRequestInDisconnecting_requestDeferred() {
        testDisconnectingInConnected();
        clearInvocations(mNativeInterface);

        mStateMachine.sendMessage(A2dpSinkStateMachine.MESSAGE_CONNECT);
        syncHandler(A2dpSinkStateMachine.MESSAGE_CONNECT);

        verify(mNativeInterface, never()).connectA2dpSink(any());
        assertThat(mStateMachine.getState()).isEqualTo(STATE_DISCONNECTING);
    }

    /**********************************************************************************************
     * OTHER TESTS                                                                                *
     *********************************************************************************************/

    @Test
    public void testDump() {
        StringBuilder sb = new StringBuilder();
        mStateMachine.dump(sb);
        assertThat(sb.toString()).isNotNull();
    }
}
