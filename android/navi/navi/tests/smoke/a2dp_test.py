#  Copyright 2025 Google LLC
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.

from __future__ import annotations

import asyncio
from typing import TypeAlias

from bumble import avdtp
from bumble import avrcp
from bumble import hci
import bumble.core
from mobly import test_runner
from mobly import signals
from typing_extensions import override

from navi.bumble_ext import a2dp as a2dp_ext
from navi.bumble_ext import avrcp as avrcp_ext
from navi.tests import navi_test_base
from navi.utils import android_constants
from navi.utils import audio
from navi.utils import bl4a_api
from navi.utils import constants

_A2DP_SERVICE_RECORD_HANDLE = 1
_AVRCP_CONTROLLER_RECORD_HANDLE = 2
_AVRCP_TARGET_RECORD_HANDLE = 3
_DEFAULT_STEP_TIMEOUT_SECONDS = 5.0
_PROPERTY_CODEC_PRIORITY = "bluetooth.a2dp.source.%s_priority.config"
_PROPERTY_OPUS_ENABLED = "persist.bluetooth.opus.enabled"
_VALUE_CODEC_DISABLED = -1

_Issuer = constants.TestRole
_A2dpState = android_constants.A2dpState
_StreamType: TypeAlias = android_constants.StreamType
_A2dpCodec = a2dp_ext.A2dpCodec


class LocalSinkWrapper:
    """Wrapper for LocalSink to provide start/suspend events."""

    def __init__(self, impl: avdtp.LocalSink):
        self.impl = impl
        self.condition = asyncio.Condition()
        for command in (
                impl.EVENT_CONFIGURATION,
                impl.EVENT_OPEN,
                impl.EVENT_START,
                impl.EVENT_SUSPEND,
                impl.EVENT_CLOSE,
                impl.EVENT_ABORT,
        ):
            self.impl.on(command, self._on_command)

    async def _on_command(self) -> None:
        async with self.condition:
            self.condition.notify_all()

    @property
    def stream_state(self) -> int | None:
        return self.impl.stream.state if self.impl.stream else None


class A2dpTest(navi_test_base.TwoDevicesTestBase):
    dut_supported_codecs: list[_A2dpCodec]

    @override
    async def async_setup_class(self) -> None:
        await super().async_setup_class()
        if (self.dut.getprop(android_constants.Property.A2DP_SOURCE_ENABLED) != "true"):
            raise signals.TestAbortClass("A2DP is not enabled on DUT.")
        self.dut_supported_codecs = [
            codec for codec in _A2dpCodec
            if int(self.dut.getprop(_PROPERTY_CODEC_PRIORITY %
                                    codec.name.lower()) or "0") > _VALUE_CODEC_DISABLED and
            (codec != _A2dpCodec.OPUS or self.dut.getprop(_PROPERTY_OPUS_ENABLED) == "true")
        ]

    @override
    async def async_teardown_test(self) -> None:
        await super().async_teardown_test()
        self.dut.bt.audioStop()

    def _setup_a2dp_device(self, codecs: list[_A2dpCodec]) -> tuple[avdtp.Listener, avrcp.Protocol]:
        """Sets up A2DP profile on REF.

    Args:
      codecs: A2DP codecs supported by REF.

    Returns:
      A tuple of (avdtp.Listener, avrcp.Protocol).
    """
        listener = a2dp_ext.setup_sink_server(
            self.ref.device,
            [codec.get_default_capabilities() for codec in codecs],
            _A2DP_SERVICE_RECORD_HANDLE,
        )
        avrcp_delegator = avrcp.Delegate(
            supported_events=(avrcp.EventId.VOLUME_CHANGED,)  # type: ignore[wrong-arg-types]
        )
        avrcp_protocol = avrcp_ext.setup_server(
            self.ref.device,
            avrcp_controller_handle=_AVRCP_CONTROLLER_RECORD_HANDLE,
            avrcp_target_handle=_AVRCP_TARGET_RECORD_HANDLE,
            delegate=avrcp_delegator,
        )

        return listener, avrcp_protocol

    async def _setup_a2dp_connection(
            self, ref_codecs: list[_A2dpCodec]) -> tuple[
                avrcp.Protocol,
                avdtp.Protocol,
            ]:
        """Sets up A2DP connection between DUT and REF.

    Args:
      ref_codecs: A2DP codecs supported by REF.

    Returns:
      A tuple of (avrcp.Protocol, avdtp.Protocol).
    """
        with self.dut.bl4a.register_callback(bl4a_api.Module.A2DP) as dut_cb:
            ref_avdtp_listener, ref_avrcp_protocol = self._setup_a2dp_device(ref_codecs)
            ref_avdtp_connections = asyncio.Queue[avdtp.Protocol]()
            ref_avdtp_listener.on(ref_avdtp_listener.EVENT_CONNECTION, ref_avdtp_connections.put)

            self.logger.info("[DUT] Connect and pair REF.")
            ref_acl = await self.classic_connect_and_pair()

            self.logger.info("[DUT] Wait for A2DP connected.")
            await dut_cb.wait_for_event(
                bl4a_api.ProfileConnectionStateChanged(
                    address=self.ref.address,
                    state=android_constants.ConnectionState.CONNECTED,
                ),)
            async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS,
                                               msg="[REF] Wait for A2DP connected."):
                ref_avdtp_connection = await ref_avdtp_connections.get()
            self.logger.info("[DUT] Wait for A2DP becomes active.")
            await dut_cb.wait_for_event(
                bl4a_api.ProfileActiveDeviceChanged(address=self.ref.address),
                timeout=_DEFAULT_STEP_TIMEOUT_SECONDS,
            )

            if ref_avrcp_protocol.avctp_protocol is not None:
                self.logger.info("[REF] AVRCP already connected.")
            else:
                self.logger.info("[REF] Connect AVRCP.")
                async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
                    await ref_avrcp_protocol.connect(ref_acl)
                self.logger.info("[REF] AVRCP connected.")
        return ref_avrcp_protocol, ref_avdtp_connection

    async def _terminate_connection_from_ref(self) -> None:
        self.logger.info("[DUT] Terminate connection.")
        with self.dut.bl4a.register_callback(bl4a_api.Module.ADAPTER) as dut_cb:
            ref_acl = self.ref.device.find_connection_by_bd_addr(
                hci.Address(self.dut.address),
                transport=bumble.core.PhysicalTransport.BR_EDR,
            )
            if ref_acl is None:
                self.logger.info("[REF] No ACL connection found.")
                return
            await ref_acl.disconnect()
            await dut_cb.wait_for_event(
                bl4a_api.AclDisconnected(
                    address=self.ref.address,
                    transport=android_constants.Transport.CLASSIC,
                ),)

    async def test_pair_and_connect(self) -> None:
        """Tests A2DP connection establishment right after a pairing session.

    Test steps:
      1. Setup A2DP on REF.
      2. Create bond from DUT.
      3. Wait A2DP connected on DUT (Android should autoconnect A2DP as AG).
    """
        with self.dut.bl4a.register_callback(bl4a_api.Module.A2DP) as dut_cb:
            self._setup_a2dp_device([_A2dpCodec.SBC])

            self.logger.info("[DUT] Connect and pair REF.")
            await self.classic_connect_and_pair()

            self.logger.info("[DUT] Wait for A2DP connected.")
            await dut_cb.wait_for_event(
                bl4a_api.ProfileConnectionStateChanged(
                    address=self.ref.address,
                    state=android_constants.ConnectionState.CONNECTED,
                ),)
            self.logger.info("[DUT] Wait for A2DP becomes active.")
            await dut_cb.wait_for_event(
                bl4a_api.ProfileActiveDeviceChanged(address=self.ref.address),
                timeout=_DEFAULT_STEP_TIMEOUT_SECONDS,
            )

    async def test_outgoing_reconnect(self) -> None:
        """Tests A2DP connection establishment where DUT initiates the reconnection.

    Test steps:
      1. Setup pairing between DUT and REF.
      2. Terminate ACL connection.
      3. Trigger connection from DUT.
      4. Wait A2DP connected on DUT.
      5. Disconnect from DUT.
      6. Wait A2DP disconnected on DUT.
    """
        await self.test_pair_and_connect()
        await self._terminate_connection_from_ref()

        with self.dut.bl4a.register_callback(bl4a_api.Module.A2DP) as dut_cb:
            self.logger.info("[DUT] Reconnect.")
            self.dut.bt.connect(self.ref.address)

            self.logger.info("[DUT] Wait for A2DP connected.")
            await dut_cb.wait_for_event(
                bl4a_api.ProfileConnectionStateChanged(
                    address=self.ref.address,
                    state=android_constants.ConnectionState.CONNECTED,
                ),)
            self.logger.info("[DUT] Wait for A2DP becomes active.")
            await dut_cb.wait_for_event(
                bl4a_api.ProfileActiveDeviceChanged(address=self.ref.address),
                timeout=_DEFAULT_STEP_TIMEOUT_SECONDS,
            )

            self.logger.info("[DUT] Disconnect.")
            self.dut.bt.disconnect(self.ref.address)

            self.logger.info("[DUT] Wait for A2DP disconnected.")
            await dut_cb.wait_for_event(
                bl4a_api.ProfileConnectionStateChanged(
                    address=self.ref.address,
                    state=android_constants.ConnectionState.DISCONNECTED,
                ),)

    async def test_incoming_reconnect(self) -> None:
        """Tests A2DP connection establishment where REF initiates the reconnection.

    Test steps:
      1. Setup pairing between DUT and REF.
      2. Terminate ACL connection.
      3. Trigger connection from REF.
      4. Wait A2DP connected on DUT.
      5. Disconnect from REF.
      6. Wait A2DP disconnected on DUT.
    """
        await self.test_pair_and_connect()
        await self._terminate_connection_from_ref()

        with self.dut.bl4a.register_callback(bl4a_api.Module.A2DP) as dut_cb:
            self.logger.info("[REF] Reconnect.")
            dut_ref_acl = await self.ref.device.connect(
                str(self.dut.address),
                bumble.core.BT_BR_EDR_TRANSPORT,
                timeout=_DEFAULT_STEP_TIMEOUT_SECONDS,
            )

            self.logger.info("[REF] Authenticate and encrypt connection.")
            await dut_ref_acl.authenticate()
            await dut_ref_acl.encrypt()

            self.logger.info("[REF] Connect A2DP.")
            server = await avdtp.Protocol.connect(dut_ref_acl)
            server.add_sink(_A2dpCodec.AAC.get_default_capabilities())

            self.logger.info("[DUT] Wait for A2DP connected.")
            await dut_cb.wait_for_event(
                bl4a_api.ProfileConnectionStateChanged(
                    address=self.ref.address,
                    state=android_constants.ConnectionState.CONNECTED,
                ),)
            self.logger.info("[DUT] Wait for A2DP becomes active.")
            await dut_cb.wait_for_event(
                bl4a_api.ProfileActiveDeviceChanged(address=self.ref.address),
                timeout=_DEFAULT_STEP_TIMEOUT_SECONDS,
            )

            self.logger.info("[REF] Disconnect.")
            await dut_ref_acl.disconnect()

            self.logger.info("[DUT] Wait for A2DP disconnected.")
            await dut_cb.wait_for_event(
                bl4a_api.ProfileConnectionStateChanged(
                    address=self.ref.address,
                    state=android_constants.ConnectionState.DISCONNECTED,
                ),)

    @navi_test_base.parameterized(
        ([_A2dpCodec.SBC],),
        ([_A2dpCodec.SBC, _A2dpCodec.AAC],),
        ([_A2dpCodec.SBC, _A2dpCodec.AAC, _A2dpCodec.APTX],),
        ([_A2dpCodec.SBC, _A2dpCodec.AAC, _A2dpCodec.APTX_HD],),
        ([_A2dpCodec.SBC, _A2dpCodec.AAC, _A2dpCodec.LDAC],),
    )
    @navi_test_base.retry(2)
    async def test_stream_start_and_stop(
        self,
        ref_codecs: list[_A2dpCodec],
    ) -> None:
        """Tests A2DP streaming controlled by the given issuer (DUT or REF).

    Test steps:
      1. Setup pairing between DUT and REF.
      2. Start stream from the given issuer.
      3. Stop stream from DUT from the given issuer.

    Args:
      ref_codecs: A2DP codecs supported by REF.
    """
        # Select preferred codec and sink.
        for codec in _A2dpCodec:
            if codec in ref_codecs and codec in self.dut_supported_codecs:
                preferred_codec = codec
                break
        else:
            self.fail("No supported codec found on REF.")

        self.logger.info("Preferred codec: %s", preferred_codec.name)

        self.dut.bt.audioSetRepeat(android_constants.RepeatMode.ONE)

        with self.dut.bl4a.register_callback(bl4a_api.Module.A2DP) as dut_cb:
            _, ref_avdtp_connection = await self._setup_a2dp_connection(ref_codecs)

            ref_sinks = a2dp_ext.find_local_endpoints_by_codec(
                ref_avdtp_connection,
                preferred_codec.codec_type,
                avdtp.LocalSink,
                vendor_id=preferred_codec.vendor_id,
                codec_id=preferred_codec.codec_id,
            )
            if not ref_sinks:
                self.fail("No sink found for codec %s." % preferred_codec.name)
            ref_sink = LocalSinkWrapper(ref_sinks[0])

            # If there is a playback, wait until it ends.
            if self.dut.bt.isA2dpPlaying(self.ref.address):
                self.logger.info("[DUT] A2DP is streaming, wait for A2DP stopped.")
                await dut_cb.wait_for_event(
                    bl4a_api.A2dpPlayingStateChanged(self.ref.address, _A2dpState.NOT_PLAYING),)
            async with (
                    self.assert_not_timeout(
                        _DEFAULT_STEP_TIMEOUT_SECONDS,
                        msg="[REF] A2DP is streaming, wait for A2DP stopped.",
                    ),
                    ref_sink.condition,
            ):
                await ref_sink.condition.wait_for(
                    lambda: ref_sink.stream_state != avdtp.State.STREAMING)

            # Register the sink buffer to receive the packets.
            buffer = a2dp_ext.register_sink_buffer(ref_sink.impl, preferred_codec)

            self.logger.info("[DUT] Start stream.")
            self.dut.bt.audioPlaySine()

            self.logger.info("[DUT] Wait for A2DP started.")
            await dut_cb.wait_for_event(
                bl4a_api.A2dpPlayingStateChanged(address=self.ref.address,
                                                 state=_A2dpState.PLAYING))
            async with (
                    self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS,
                                            msg="[REF] Wait for A2DP started."),
                    ref_sink.condition,
            ):
                await ref_sink.condition.wait_for(
                    lambda: ref_sink.stream_state == avdtp.State.STREAMING)

            # Streaming for 1 second.
            await asyncio.sleep(1.0)

            self.logger.info("[DUT] Stop stream.")
            self.dut.bt.audioPause()

            self.logger.info("[DUT] Wait for A2DP stopped.")
            await dut_cb.wait_for_event(
                bl4a_api.A2dpPlayingStateChanged(address=self.ref.address,
                                                 state=_A2dpState.NOT_PLAYING))
            async with (
                    self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS,
                                            msg="[REF] Wait for A2DP stopped."),
                    ref_sink.condition,
            ):
                await ref_sink.condition.wait_for(
                    lambda: ref_sink.stream_state != avdtp.State.STREAMING)
            if self.user_params.get(navi_test_base.RECORD_FULL_DATA) and buffer:
                self.write_test_output_data(
                    f"a2dp_data.{preferred_codec.format}",
                    buffer,
                )

            if (buffer is not None and preferred_codec != _A2dpCodec.LDAC and
                    audio.SUPPORT_AUDIO_PROCESSING):
                dominant_frequency = audio.get_dominant_frequency(buffer,
                                                                  format=preferred_codec.format)
                self.logger.info("Dominant frequency: %.2f", dominant_frequency)
                # Dominant frequency is not accurate on emulator.
                if not self.dut.device.is_emulator:
                    self.assertAlmostEqual(dominant_frequency, 1000, delta=10)

    async def test_noisy_handling(self) -> None:
        """Tests enabling noisy handling, and verify the player is paused after A2DP disconnected.

    Test steps:
      1. Enable noisy handling.
      2. Setup A2DP connection.
      3. Start streaming.
      4. Disconnect from REF.
      5. Wait for player paused.
    """
        if self.dut.device.is_emulator:
            self.skipTest("b/406208447 - Noisy handling is flaky on emulator.")

        # Enable audio noisy handling.
        self.dut.bt.setHandleAudioBecomingNoisy(True)

        with (
                self.dut.bl4a.register_callback(bl4a_api.Module.A2DP) as dut_a2dp_cb,
                self.dut.bl4a.register_callback(bl4a_api.Module.PLAYER) as dut_player_cb,
        ):
            await self._setup_a2dp_connection([_A2dpCodec.SBC])
            self.logger.info("[DUT] Start stream.")
            self.dut.bt.audioSetRepeat(android_constants.RepeatMode.ALL)
            self.dut.bt.audioPlaySine()

            self.logger.info("[DUT] Wait for playback started.")
            await dut_player_cb.wait_for_event(bl4a_api.PlayerIsPlayingChanged(is_playing=True),)
            if not self.dut.bt.isA2dpPlaying(self.ref.address):
                self.logger.info("[DUT] Wait for A2DP playing.")
                await dut_a2dp_cb.wait_for_event(
                    bl4a_api.A2dpPlayingStateChanged(self.ref.address, _A2dpState.PLAYING),)

        # Streaming for 1 second.
        await asyncio.sleep(1.0)

        with self.dut.bl4a.register_callback(bl4a_api.Module.PLAYER) as dut_player_cb:
            ref_dut_acl = self.ref.device.find_connection_by_bd_addr(
                hci.Address(self.dut.address),
                transport=bumble.core.PhysicalTransport.BR_EDR,
            )
            if ref_dut_acl is None:
                self.fail("No ACL connection found?")
            async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
                self.logger.info("[REF] Disconnect.")
                await ref_dut_acl.disconnect()

            self.logger.info("[DUT] Wait for player paused.")
            await dut_player_cb.wait_for_event(bl4a_api.PlayerIsPlayingChanged(is_playing=False),)

    @navi_test_base.retry(3)
    async def test_disconnection_from_source_during_streaming(self) -> None:
        """Tests A2DP disconnection initiated by the Source (DUT).

    1. Connect DUT and REF.
    2. Play the audio streaming.
    3. Initiate disconnection from Source (DUT) .
    4. Verify DUT is disconnected successfully.
    """
        # 1. Connect DUT and REF (A2DP Source and Sink).
        await self._setup_a2dp_connection([_A2dpCodec.SBC])

        # 2. Play the audio streaming.
        with (
                self.dut.bl4a.register_callback(bl4a_api.Module.A2DP) as dut_a2dp_cb,
                self.dut.bl4a.register_callback(bl4a_api.Module.PLAYER) as dut_player_cb,
        ):
            self.logger.info("[DUT] Start streaming.")
            # Allow repeating to avoid the end of the track.
            self.dut.bt.audioSetRepeat(android_constants.RepeatMode.ONE)
            # Play the sine wave track.
            self.dut.bt.audioPlaySine()

            self.logger.info("[DUT] Wait for playback started.")
            await dut_player_cb.wait_for_event(
                bl4a_api.PlayerIsPlayingChanged(is_playing=True),
                timeout=_DEFAULT_STEP_TIMEOUT_SECONDS,
            )

            if not self.dut.bt.isA2dpPlaying(self.ref.address):
                self.logger.info("[DUT] Wait for A2DP playing.")
                await dut_a2dp_cb.wait_for_event(
                    bl4a_api.A2dpPlayingStateChanged(self.ref.address, _A2dpState.PLAYING),
                    timeout=_DEFAULT_STEP_TIMEOUT_SECONDS,
                )
            self.logger.info("[TEST] Audio streaming started.")

            # Streaming for 1 second.
            await asyncio.sleep(1.0)

            # 3. Initiate disconnection and Verify disconnected successfully.
            self.logger.info("[DUT] Disconnect.")
            self.dut.bt.disconnect(self.ref.address)

            self.logger.info("[DUT] Wait for A2DP disconnected.")
            await dut_a2dp_cb.wait_for_event(
                bl4a_api.ProfileConnectionStateChanged(
                    address=self.ref.address,
                    state=android_constants.ConnectionState.DISCONNECTED,
                ),
                timeout=_DEFAULT_STEP_TIMEOUT_SECONDS,
            )


if __name__ == "__main__":
    test_runner.main()
