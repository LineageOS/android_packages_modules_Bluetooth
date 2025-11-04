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
"""Tests for Audio Streaming for Hearing Aid (ASHA) profile implementation on Android."""

import asyncio
import contextlib
import secrets

from bumble import device as bumble_device
from bumble import hci
from mobly import test_runner
from mobly import signals
from typing_extensions import override

from navi.bumble_ext import asha
from navi.tests import navi_test_base
from navi.utils import android_constants
from navi.utils import audio
from navi.utils import bl4a_api
from navi.utils import constants
from navi.utils import pyee_extensions

_PROPERTY_ASHA_CENTRAL_ENABLED = "bluetooth.profile.asha.central.enabled"
_DEFAULT_TIMEOUT_SECONDS = 10.0
_DEFAULT_ADVERTISING_INTERVAL = 100
_STREAMING_TIME_SECONDS = 1.0

# ASHA Volume Constants (dB attenuation, 0 is max)
_ASHA_VOLUME_MAX = 0
# ASHA minimum volume for music stream type (-128 dB)
_ASHA_VOLUME_MUSIC_MIN = -128
# ASHA minimum volume for call stream type (-127 dB)
_ASHA_VOLUME_CALL_MIN = -127


class AshaTest(navi_test_base.TwoDevicesTestBase):
    ref_asha_service: asha.AshaService

    @override
    async def async_setup_class(self) -> None:
        await super().async_setup_class()

        # Force enabling ASHA Central on emulator.
        if self.dut.device.is_emulator:
            self.dut.setprop(_PROPERTY_ASHA_CENTRAL_ENABLED, "true")

        if self.dut.getprop(_PROPERTY_ASHA_CENTRAL_ENABLED) != "true":
            raise signals.TestAbortClass("ASHA Central is disabled.")

    @override
    async def async_setup_test(self) -> None:
        await super().async_setup_test()
        self.ref_asha_service = asha.AshaService(
            capability=asha.DeviceCapabilities(0),
            hisyncid=secrets.token_bytes(8),
            device=self.ref.device,
        )
        self.ref.device.add_service(self.ref_asha_service)
        self.ref.device.advertising_data = (self.ref_asha_service.get_advertising_data())

    async def _setup_paired_devices(self) -> None:
        with self.dut.bl4a.register_callback(bl4a_api.Module.ASHA) as dut_cb:
            await self.le_connect_and_pair(ref_address_type=hci.OwnAddressType.RANDOM,
                                           connect_profiles=True)

            self.logger.info("[DUT] Wait for ASHA connected.")
            await dut_cb.wait_for_event(
                bl4a_api.ProfileActiveDeviceChanged(address=self.ref.random_address),)

    async def test_connect(self) -> None:
        """Tests ASHA connection.

    Test steps:
      1. Pair with REF.
      2. Verify ASHA is connected.
    """
        await self._setup_paired_devices()
        self.assertIn(
            self.ref.random_address,
            self.dut.bt.getActiveDevices(android_constants.Profile.HEARING_AID),
        )

    async def test_reconnect(self) -> None:
        """Tests ASHA reconnection.

    Test steps:
      1. Pair with REF.
      2. Verify ASHA is connected.
      3. Disconnect from REF.
      4. Restart advertising on REF.
      5. Verify ASHA is connected.
    """
        await self._setup_paired_devices()

        ref_dut_acl = self.ref.device.find_connection_by_bd_addr(hci.Address(self.dut.address))
        if ref_dut_acl is None:
            self.fail("No ACL connection found.")
        self.logger.info("[REF] Disconnect")
        await ref_dut_acl.disconnect()

        with self.dut.bl4a.register_callback(bl4a_api.Module.ASHA) as dut_cb:
            async with self.assert_not_timeout(_DEFAULT_TIMEOUT_SECONDS):
                self.logger.info("[REF] Start advertising")
                await self.ref.device.create_advertising_set(
                    advertising_data=self.ref_asha_service.get_advertising_data(),
                    advertising_parameters=bumble_device.AdvertisingParameters(
                        own_address_type=hci.OwnAddressType.RANDOM,
                        primary_advertising_interval_min=_DEFAULT_ADVERTISING_INTERVAL,
                        primary_advertising_interval_max=_DEFAULT_ADVERTISING_INTERVAL,
                    ),
                    auto_restart=False,
                )

            self.logger.info("[DUT] Wait for ASHA connected.")
            await dut_cb.wait_for_event(
                bl4a_api.ProfileActiveDeviceChanged(address=self.ref.random_address),)

    @navi_test_base.parameterized(
        bl4a_api.AudioAttributes.Usage.VOICE_COMMUNICATION,
        bl4a_api.AudioAttributes.Usage.MEDIA,
    )
    async def test_streaming(self, usage: bl4a_api.AudioAttributes.Usage) -> None:
        """Tests ASHA streaming.

    Test steps:
      1. Establish ASHA connection.
      2. (Optional) Start phone call.
      3. Start streaming.
      4. Verify audio data is received.
      5. Stop streaming.

    Args:
      usage: The type of stream to test.
    """
        await self._setup_paired_devices()

        sink_buffer = bytearray()
        self.ref_asha_service.audio_sink = sink_buffer.extend
        watcher = pyee_extensions.EventWatcher()
        start_events = watcher.async_monitor(self.ref_asha_service, asha.AshaService.Event.STARTED)
        stop_events = watcher.async_monitor(self.ref_asha_service, asha.AshaService.Event.STOPPED)

        with contextlib.ExitStack() as exit_stack:
            if usage == bl4a_api.AudioAttributes.Usage.VOICE_COMMUNICATION:
                self.logger.info("[DUT] Start phone call")
                exit_stack.enter_context(
                    self.dut.bl4a.make_phone_call(
                        caller_name="Pixel Bluetooth",
                        caller_number="123456789",
                        direction=constants.Direction.OUTGOING,
                    ))
            self.dut.bl4a.set_audio_attributes(bl4a_api.AudioAttributes(usage=usage),
                                               handle_audio_focus=False)

            self.logger.info("[DUT] Start streaming")
            await asyncio.to_thread(self.dut.bt.audioPlaySine)
            async with self.assert_not_timeout(_DEFAULT_TIMEOUT_SECONDS):
                self.logger.info("[REF] Wait for audio started")
                await start_events.get()

            await asyncio.sleep(_STREAMING_TIME_SECONDS)

            self.logger.info("[DUT] Stop streaming")
            await asyncio.to_thread(self.dut.bt.audioStop)
            async with self.assert_not_timeout(_DEFAULT_TIMEOUT_SECONDS):
                self.logger.info("[REF] Wait for audio stopped")
                await stop_events.get()

            if (self.user_params.get(navi_test_base.RECORD_FULL_DATA) and sink_buffer):
                self.write_test_output_data(
                    "asha_data.g722",
                    sink_buffer,
                )

            if audio.SUPPORT_AUDIO_PROCESSING:
                dominant_frequency = audio.get_dominant_frequency(sink_buffer, format="g722")
                self.logger.info("Dominant frequency: %.2f", dominant_frequency)
                # Dominant frequency is not accurate on emulator.
                if not self.dut.device.is_emulator:
                    self.assertAlmostEqual(dominant_frequency, 1000, delta=10)

    @navi_test_base.named_parameterized(
        music=(android_constants.StreamType.MUSIC, _ASHA_VOLUME_MUSIC_MIN),
        call=(android_constants.StreamType.CALL, _ASHA_VOLUME_CALL_MIN),
    )
    async def test_set_volume(
        self,
        stream_type: android_constants.StreamType,
        min_volume_target: int,
    ) -> None:
        """Tests ASHA set volume.

    Test steps:
      1. Establish ASHA connection.
      2. If call stream, start a call.
      3. Set volume to max and verify volume changed to _ASHA_VOLUME_MAX.
      4. Set volume to min and verify volume changed to min_volume_target.
      5. Set volume to max and verify volume changed to _ASHA_VOLUME_MAX.

    Args:
      stream_type: The stream type to cycle volume for.
      min_volume_target: The expected volume target for min volume.
    """
        await self._setup_paired_devices()

        with contextlib.ExitStack() as exit_stack:
            if stream_type == android_constants.StreamType.CALL:
                self.logger.info("[DUT] Start phone call")
                call_context = self.dut.bl4a.make_phone_call(
                    caller_name="Pixel Bluetooth",
                    caller_number="123456789",
                    direction=constants.Direction.OUTGOING,
                )
                exit_stack.enter_context(call_context)
                await asyncio.to_thread(call_context.answer)

            volumes = pyee_extensions.EventTriggeredValueObserver(
                self.ref_asha_service,
                asha.AshaService.Event.VOLUME_CHANGED,
                lambda: self.ref_asha_service.volume,
            )
            for volume, target, name in [
                (self.dut.bt.getMaxVolume(stream_type), _ASHA_VOLUME_MAX, "max"),
                (self.dut.bt.getMinVolume(stream_type), min_volume_target, "min"),
                (self.dut.bt.getMaxVolume(stream_type), _ASHA_VOLUME_MAX, "max"),
            ]:
                async with self.assert_not_timeout(_DEFAULT_TIMEOUT_SECONDS):
                    self.logger.info(
                        "[DUT] Set volume to %s for stream %s",
                        name,
                        stream_type.name,
                    )
                    self.dut.bt.setVolume(stream_type, volume)
                    self.logger.info("[REF] Wait for volume to be %s (%d)", name, target)
                    await volumes.wait_for_target_value(target)


if __name__ == "__main__":
    test_runner.main()
