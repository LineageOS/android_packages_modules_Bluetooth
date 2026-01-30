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
import decimal
import sys
import tempfile
from typing import Iterable, TypeAlias
import wave

from bumble import avc
from bumble import avdtp
from bumble import avrcp
from mobly import test_runner
from mobly import signals
from typing_extensions import override

from navi.bumble_ext import a2dp as a2dp_ext
from navi.bumble_ext import avrcp as avrcp_ext
from navi.tests import navi_test_base
from navi.utils import android_constants
from navi.utils import bl4a_api
from navi.utils import constants
from navi.utils import matcher

_A2DP_SERVICE_RECORD_HANDLE = 1
_AVRCP_CONTROLLER_RECORD_HANDLE = 2
_AVRCP_TARGET_RECORD_HANDLE = 3
_DEFAULT_STEP_TIMEOUT_SECONDS = 5.0
_AVRCP_MAX_VOLUME = 127
_PREPARE_TIME_SECONDS = 0.5

_Issuer = constants.TestRole
_StreamType: TypeAlias = android_constants.StreamType
_A2dpCodec = a2dp_ext.A2dpCodec


class AvrcpDelegate(avrcp.Delegate):

    def __init__(self, supported_events: Iterable[avrcp.EventId] = ()):
        super().__init__(supported_events)
        self.condition = asyncio.Condition()

    async def set_absolute_volume(self, volume: int) -> None:
        await super().set_absolute_volume(volume)

        async with self.condition:
            self.condition.notify_all()


class AvrcpTest(navi_test_base.TwoDevicesTestBase):

    @override
    async def async_setup_class(self) -> None:
        await super().async_setup_class()

        if (self.dut.getprop(android_constants.Property.A2DP_SOURCE_ENABLED) != "true"):
            raise signals.TestAbortClass("[DUT] A2DP is not enabled.")

    @override
    async def async_teardown_test(self) -> None:
        await super().async_teardown_test()

        self.logger.info("[DUT] Stop audio.")
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
        avrcp_delegator = AvrcpDelegate(
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
            self.logger.info("[REF] Setup A2DP.")
            ref_avdtp_listener, ref_avrcp_protocol = self._setup_a2dp_device(ref_codecs)

            ref_avdtp_connections = asyncio.Queue[avdtp.Protocol]()
            ref_avdtp_listener.on(ref_avdtp_listener.EVENT_CONNECTION, ref_avdtp_connections.put)

            ref_acl = await self.classic_connect_and_pair(connect_profiles=True)

            self.logger.info("[DUT] Wait for A2DP connected.")
            await dut_cb.wait_for_event(
                bl4a_api.ProfileConnectionStateChanged(
                    address=self.ref.address,
                    state=android_constants.ConnectionState.CONNECTED,
                ),)

            self.logger.info("[REF] Wait for A2DP connected.")
            async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
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

        return ref_avrcp_protocol, ref_avdtp_connection

    def _generate_and_push_wave_file(self, path_on_device: str, duration_seconds: int = 5) -> None:
        with tempfile.NamedTemporaryFile(
                # On Windows, NamedTemporaryFile cannot be deleted if used multiple
                # times.
                delete=(sys.platform != "win32")) as local_file:
            with wave.open(local_file.name, "wb") as wave_file:
                wave_file.setnchannels(1)
                wave_file.setsampwidth(2)
                wave_file.setframerate(48000)
                wave_file.writeframes(bytes(48000 * 2 * duration_seconds))
            self.dut.adb.push([local_file.name, path_on_device])

    async def _avrcp_key_click(
        self,
        ref_avrcp_protocol: avrcp.Protocol,
        key: avc.PassThroughFrame.OperationId,
    ) -> None:
        self.logger.info("[REF] Press %s.", key)
        await ref_avrcp_protocol.send_key_event(key, pressed=True)

        self.logger.info("[REF] Release %s.", key)
        await ref_avrcp_protocol.send_key_event(key, pressed=False)

    @navi_test_base.parameterized(_Issuer.DUT, _Issuer.REF)
    async def test_set_absolute_volume(self, issuer: _Issuer) -> None:
        """Tests setting absolute volume.

    Test steps:
      1. Setup pairing between DUT and REF.
      2. Set absolute volume from issuer.
      3. Verify the volume is changed on DUT and REF.

    Args:
      issuer: device to issue the volume change command.
    """
        ref_avrcp_protocol, _ = await self._setup_a2dp_connection([_A2dpCodec.SBC])

        ref_avrcp_delegator = ref_avrcp_protocol.delegate
        assert isinstance(ref_avrcp_delegator, AvrcpDelegate)

        self.logger.info("[DUT] Get max volume.")
        dut_max_volume = self.dut.bt.getMaxVolume(_StreamType.MUSIC)

        self.logger.info("[DUT] Get min volume.")
        dut_min_volume = self.dut.bt.getMinVolume(_StreamType.MUSIC)

        def android_to_avrcp_volume(volume: int) -> int:
            # Android JVM uses ROUND_HALF_UP policy, while Python uses ROUND_HALF_EVEN
            # by default, so we need to specify policy here.
            return int(
                decimal.Decimal(volume / dut_max_volume * _AVRCP_MAX_VOLUME).to_integral_exact(
                    rounding=decimal.ROUND_HALF_UP))

        self.logger.info("[REF] Wait for initial volume indicator.")
        async with (
                self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS,),
                ref_avrcp_delegator.condition,
        ):
            await ref_avrcp_delegator.condition.wait_for(lambda: (android_to_avrcp_volume(
                self.dut.bt.getVolume(_StreamType.MUSIC)) == ref_avrcp_delegator.volume))

        # DUT's VCS client might not be stable at the beginning. If we set volume
        # immediately, the volume might not be set correctly.
        await asyncio.sleep(_PREPARE_TIME_SECONDS)

        with self.dut.bl4a.register_callback(bl4a_api.Module.AUDIO) as dut_audio_cb:
            for dut_expected_volume in range(dut_min_volume, dut_max_volume + 1):
                if self.dut.bt.getVolume(_StreamType.MUSIC) == dut_expected_volume:
                    continue

                ref_expected_volume = android_to_avrcp_volume(dut_expected_volume)

                if issuer == _Issuer.DUT:
                    self.logger.info("[DUT] Set volume to %d.", dut_expected_volume)
                    self.dut.bt.setVolume(_StreamType.MUSIC, dut_expected_volume)
                else:
                    self.logger.info("[REF] Set volume to %d.", ref_expected_volume)
                    ref_avrcp_delegator.volume = ref_expected_volume
                    ref_avrcp_protocol.notify_volume_changed(ref_expected_volume)

                self.logger.info("[DUT] Wait for volume changed.")
                volume_changed_event = await dut_audio_cb.wait_for_event(
                    bl4a_api.VolumeChanged(stream_type=_StreamType.MUSIC,
                                           volume_value=matcher.ANY),)

                self.logger.info("[DUT] Check the volume.")
                self.assertEqual(volume_changed_event.volume_value, dut_expected_volume)

                # There won't be volume changed events on REF as issuer.
                self.logger.info("[REF] Wait for volume changed.")
                if issuer == _Issuer.DUT:
                    async with (
                            self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS,),
                            ref_avrcp_delegator.condition,
                    ):
                        await ref_avrcp_delegator.condition.wait_for(
                            lambda: ref_avrcp_delegator.volume == ref_expected_volume  # pylint: disable=cell-var-from-loop
                        )

    @navi_test_base.retry(3)
    async def test_previous_next_track(self) -> None:
        """Tests moving to previous and next track over AVRCP.

    Test steps:
      1. Setup pairing between DUT and REF.
      2. Start stream from REF.
      3. Move to the next track from REF.
      4. Move back to the previous track from REF.
    """
        ref_avrcp_protocol, _ = await self._setup_a2dp_connection([_A2dpCodec.SBC])

        self.logger.info("[DUT] Set repeat mode to ONE.")
        self.dut.bt.audioSetRepeat(android_constants.RepeatMode.ONE)

        self.logger.info("[DUT] Generate two wave files.")
        for i in range(2):
            self._generate_and_push_wave_file(
                f"/data/media/{self.dut.adb.current_user_id}/Music/sample-{i}.mp3")

        with self.dut.bl4a.register_callback(bl4a_api.Module.PLAYER) as dut_player_cb:
            self.logger.info("[DUT] Play the first track.")
            self.dut.bt.audioPlayFile("/storage/self/primary/Music/sample-0.mp3")

            self.logger.info("[DUT] Add the second track to the queue.")
            self.dut.bt.addMediaItem("/storage/self/primary/Music/sample-1.mp3")

            self.logger.info("[DUT] Wait for playback started.")
            await dut_player_cb.wait_for_event(bl4a_api.PlayerIsPlayingChanged(is_playing=True))

            self.logger.info("[REF] Go to the next track.")
            async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
                await self._avrcp_key_click(ref_avrcp_protocol,
                                            avc.PassThroughFrame.OperationId.FORWARD)

            self.logger.info("[DUT] Wait for track transition.")
            await dut_player_cb.wait_for_event(
                bl4a_api.PlayerMediaItemTransition,
                lambda e: (e.uri is not None and "sample-1.mp3" in e.uri),
            )

            self.logger.info("[REF] Go back to the previous track.")
            async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
                await self._avrcp_key_click(ref_avrcp_protocol,
                                            avc.PassThroughFrame.OperationId.BACKWARD)

            self.logger.info("[DUT] Wait for track transition.")
            await dut_player_cb.wait_for_event(
                bl4a_api.PlayerMediaItemTransition,
                lambda e: (e.uri is not None and "sample-0.mp3" in e.uri),
            )

    @navi_test_base.retry(3)
    async def test_pause_and_resume(self) -> None:
        """Tests pause and resume over AVRCP.

    Test steps:
      1. Setup pairing between DUT and REF.
      2. Start stream from DUT.
      3. Pause stream from REF.
    """
        self.logger.info("[DUT] Set repeat mode to ONE.")
        self.dut.bt.audioSetRepeat(android_constants.RepeatMode.ONE)

        dut_player_cb = self.dut.bl4a.register_callback(bl4a_api.Module.PLAYER)
        self.test_case_context.enter_context(dut_player_cb)

        ref_avrcp_protocol, _ = await self._setup_a2dp_connection([_A2dpCodec.SBC])

        self.logger.info("[DUT] Start playback.")
        self.dut.bt.audioPlaySine()

        self.logger.info("[DUT] Wait for playback started.")
        await dut_player_cb.wait_for_event(bl4a_api.PlayerIsPlayingChanged(is_playing=True))

        self.logger.info("[REF] Pause playback.")
        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            await self._avrcp_key_click(ref_avrcp_protocol, avc.PassThroughFrame.OperationId.PAUSE)

        self.logger.info("[DUT] Wait for playback stopped.")
        await dut_player_cb.wait_for_event(bl4a_api.PlayerIsPlayingChanged(is_playing=False))

        self.logger.info("[REF] Resume playback.")
        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            await self._avrcp_key_click(ref_avrcp_protocol, avc.PassThroughFrame.OperationId.PLAY)

        self.logger.info("[DUT] Wait for playback resumed.")
        await dut_player_cb.wait_for_event(bl4a_api.PlayerIsPlayingChanged(is_playing=True))

    @navi_test_base.retry(3)
    async def test_fast_forward_rewind(self) -> None:
        """Tests fast forward and rewind over AVRCP.

    Test steps:
      1. Setup pairing between DUT and REF.
      2. Start stream from REF.
      3. Fast forward from REF.
      4. Rewind from REF.
    """
        ref_avrcp_protocol, _ = await self._setup_a2dp_connection([_A2dpCodec.SBC])

        dut_player_cb = self.dut.bl4a.register_callback(bl4a_api.Module.PLAYER)
        self.test_case_context.enter_context(dut_player_cb)

        self.logger.info("[DUT] Generate wave file.")
        self._generate_and_push_wave_file(
            f"/data/media/{self.dut.adb.current_user_id}/Music/sample.mp3",
            duration_seconds=60,
        )

        self.logger.info("[DUT] Play audio file.")
        self.dut.bt.audioPlayFile("/storage/self/primary/Music/sample.mp3")

        self.logger.info("[DUT] Wait for playback started.")
        await dut_player_cb.wait_for_event(bl4a_api.PlayerIsPlayingChanged(is_playing=True))

        self.logger.info("[REF] Fast forward.")
        async with asyncio.timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            await self._avrcp_key_click(ref_avrcp_protocol,
                                        avc.PassThroughFrame.OperationId.FAST_FORWARD)

        self.logger.info("[DUT] Wait for position discontinuity.")
        await dut_player_cb.wait_for_event(
            bl4a_api.PositionDiscontinuity,
            lambda e: (e.new_position_ms > e.old_position_ms),
            timeout=_DEFAULT_STEP_TIMEOUT_SECONDS,
        )

        self.logger.info("[REF] Rewind.")
        async with asyncio.timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            await self._avrcp_key_click(ref_avrcp_protocol, avc.PassThroughFrame.OperationId.REWIND)

        self.logger.info("[DUT] Wait for position discontinuity.")
        await dut_player_cb.wait_for_event(
            bl4a_api.PositionDiscontinuity,
            lambda e: (e.new_position_ms < e.old_position_ms),
            timeout=_DEFAULT_STEP_TIMEOUT_SECONDS,
        )

    @navi_test_base.retry(3)
    async def test_notification_on_playback_state_change(self) -> None:
        """Tests notification on playback state change.

    Test steps:
      1. Setup pairing between DUT and REF.
      2. Start stream from DUT and check notification.
      3. Pause stream from DUT and check notification.
      4. Resume stream from DUT and check notification.
      5. Stop stream from DUT and check notification.
    """
        ref_avrcp_protocol, _ = await self._setup_a2dp_connection([_A2dpCodec.SBC])

        self.dut.bt.audioSetRepeat(android_constants.RepeatMode.ONE)

        self.logger.info("[DUT] Stop playback.")
        self.dut.bt.audioStop()

        self.logger.info("[REF] Register for the playback status.")
        playback_status_iter = ref_avrcp_protocol.monitor_playback_status()

        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            status = await anext(playback_status_iter)
            if status == avrcp.PlayStatus.PAUSED:
                self.logger.info("[REF] Wait for playback stopped.")
                await anext(playback_status_iter)

                # Interim response of current playback state.
                await anext(playback_status_iter)

        self.logger.info("[DUT] Start playback.")
        self.dut.bt.audioPlaySine()

        self.logger.info("[REF] Wait for playback started.")
        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            status = await anext(playback_status_iter)
        self.assertEqual(status, avrcp.PlayStatus.PLAYING)

        # Interim response of current playback state.
        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            status = await anext(playback_status_iter)
        self.assertEqual(status, avrcp.PlayStatus.PLAYING)

        self.logger.info("[DUT] Pause playback.")
        self.dut.bt.audioPause()

        self.logger.info("[REF] Wait for playback state changed to paused.")
        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            status = await anext(playback_status_iter)
        self.assertEqual(status, avrcp.PlayStatus.PAUSED)

        # Interim response of current playback state.
        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            status = await anext(playback_status_iter)
        self.assertEqual(status, avrcp.PlayStatus.PAUSED)

        self.logger.info("[DUT] Resume playback.")
        self.dut.bt.audioResume()

        self.logger.info("[REF] Wait for playback state changed to playing.")
        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            status = await anext(playback_status_iter)
        self.assertEqual(status, avrcp.PlayStatus.PLAYING)

        # Interim response of current playback state.
        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            status = await anext(playback_status_iter)
        self.assertEqual(status, avrcp.PlayStatus.PLAYING)

        self.logger.info("[DUT] Stop playback.")
        self.dut.bt.audioStop()

        self.logger.info("[REF] Wait for playback state changed to stopped.")
        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            status = await anext(playback_status_iter)
        self.assertEqual(status, avrcp.PlayStatus.STOPPED)

    @navi_test_base.retry(3)
    async def test_notification_on_playback_position_change(self) -> None:
        """Tests notification on play position change.

    Test steps:
      1. Setup pairing between DUT and REF.
      2. Start stream from DUT and check notification.
      3. Fast forward from DUT and check notification.
      4. Rewind from DUT and check notification.
    """
        ref_avrcp_protocol, _ = await self._setup_a2dp_connection([_A2dpCodec.SBC])

        self.dut.bt.audioSetRepeat(android_constants.RepeatMode.ONE)

        dut_player_cb = self.dut.bl4a.register_callback(bl4a_api.Module.PLAYER)
        self.test_case_context.enter_context(dut_player_cb)

        self.logger.info("[DUT] Start playback.")
        self.dut.bt.audioPlaySine()

        self.logger.info("[DUT] Wait for playback started.")
        await dut_player_cb.wait_for_event(bl4a_api.PlayerIsPlayingChanged(is_playing=True))

        pb_position_iter = ref_avrcp_protocol.monitor_playback_position(1)
        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            self.logger.info("[REF] Wait for playback position.")
            first_position = await anext(pb_position_iter)

            self.logger.info("[REF] Wait for playback position again.")
            second_position = await anext(pb_position_iter)

        self.assertGreater(second_position, first_position)

    @navi_test_base.retry(3)
    async def test_notification_on_now_playing_content_change(self) -> None:
        """Tests notification on now playing content change.

    Test steps:
      1. Setup pairing between DUT and REF.
      2. Start stream from DUT and check notification.
      3. Add a media item to the player and check notification.
    """
        ref_avrcp_protocol, _ = await self._setup_a2dp_connection([_A2dpCodec.SBC])

        self.logger.info("[DUT] Generate two wave files.")
        for i in range(2):
            self._generate_and_push_wave_file(
                f"/data/media/{self.dut.adb.current_user_id}/Music/sample-{i}.mp3")

        with self.dut.bl4a.register_callback(bl4a_api.Module.PLAYER) as dut_player_cb:
            self.logger.info("[DUT] Play the first track.")
            self.dut.bt.audioPlayFile("/storage/self/primary/Music/sample-0.mp3")

            self.logger.info("[DUT] Wait for playback started.")
            await dut_player_cb.wait_for_event(bl4a_api.PlayerIsPlayingChanged(is_playing=True))

            now_playing_content_changed_iter = (ref_avrcp_protocol.monitor_now_playing_content())
            # First yield is from INTERIM response
            async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
                await anext(now_playing_content_changed_iter)

            self.logger.info("[DUT] Add a media item.")
            self.dut.bt.addMediaItem("/storage/self/primary/Music/sample-1.mp3")

            self.logger.info("[REF] Wait for now playing content changed.")
            async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
                await anext(now_playing_content_changed_iter)


if __name__ == "__main__":
    test_runner.main()
