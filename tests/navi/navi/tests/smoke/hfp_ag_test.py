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
"""Tests related to Bluetooth HFP(Hands-Free Profile) AG role on Pixel."""

import asyncio
import collections
import enum
import itertools
import re

from bumble import core
from bumble import device
from bumble import hci
from bumble import hfp
from bumble import rfcomm
from mobly import test_runner
from mobly import signals
from mobly.controllers import android_device
from typing_extensions import override

from navi.bumble_ext import hfp as hfp_ext
from navi.tests import navi_test_base
from navi.utils import android_constants
from navi.utils import audio
from navi.utils import bl4a_api
from navi.utils import constants

_DEFAULT_STEP_TIMEOUT_SECONDS = 15.0
_HFP_SDP_HANDLE = 1
_CALLER_NAME = "Pixel Bluetooth"
_CALLER_NUMBER = "123456789"
_HFP_MAX_VOLUME = 15
_STREAM_TYPE_CALL = android_constants.StreamType.CALL
_PROPERTY_SWB_SUPPORTED = "bluetooth.hfp.swb.supported"
_RECORDING_PATH = "/storage/self/primary/Recordings/record.wav"
_HFP_FRAME_DURATION = 0.0075  # 7.5ms
_MAX_FRAME_SIZE = 240
_ACTION_VOICE_COMMAND = "android.intent.action.VOICE_COMMAND"

_AudioCodec = hfp.AudioCodec
_AgIndicator = hfp.AgIndicator
_CallState = android_constants.CallState
_CallbackHandler = bl4a_api.CallbackHandler
_HfpAgAudioStateChange = bl4a_api.HfpAgAudioStateChanged
_Module = bl4a_api.Module
_ScoState = android_constants.ScoState


@enum.unique
class _CallAnswer(enum.Enum):
    ACCEPT = enum.auto()
    REJECT = enum.auto()


@enum.unique
class _CallAgIndicator(enum.IntEnum):
    INACTIVE = 0
    ACTIVE = 1


class HfpAgTest(navi_test_base.TwoDevicesTestBase):

    @override
    async def async_setup_class(self) -> None:
        await super().async_setup_class()
        if self.dut.getprop(android_constants.Property.HFP_AG_ENABLED) != "true":
            raise signals.TestAbortClass("HFP(AG) is not enabled on DUT.")

        # Disable all other voice command apps to prevent choosing activities.
        voice_command_packages: set[str] = set(
            re.findall(
                r"packageName=(.+)",
                self.dut.shell([
                    "pm",
                    "query-activities",
                    "-a",
                    _ACTION_VOICE_COMMAND,
                ]),
            ))

        def callback(package: str) -> None:
            self.logger.info("[DUT] Re-Enable voice command app: %s.", package)
            self.dut.shell(["pm", "enable", package])

        for package in voice_command_packages:
            if package == android_constants.PACKAGE_NAME_BLUETOOTH_SNIPPET:
                continue
            self.logger.info("[DUT] Disable voice command app: %s.", package)
            self.dut.shell(["pm", "disable", package])
            self.test_class_context.callback(callback, package)

    @override
    async def async_teardown_test(self) -> None:
        self.dut.bt.audioStop()
        # Make sure Bumble is off to cancel any running tasks.
        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            await self.ref.close()
        await super().async_teardown_test()

    def _is_ranchu_emulator(self, dev: android_device.AndroidDevice) -> bool:
        return (build_info := dev.build_info) and build_info["hardware"] == "ranchu"

    async def _wait_for_sco_state(
        self,
        dut_hfp_ag_callback: _CallbackHandler,
        state: _ScoState,
    ) -> None:
        await dut_hfp_ag_callback.wait_for_event(event=_HfpAgAudioStateChange(
            address=self.ref.address, state=state),)

    async def _wait_for_call_state(
        self,
        dut_telecom_callback: _CallbackHandler,
        *states,
    ) -> None:
        await dut_telecom_callback.wait_for_event(
            event=bl4a_api.CallStateChanged,
            predicate=lambda e: (e.state in states),
        )

    async def _terminate_connection_from_dut(self) -> None:
        with (self.dut.bl4a.register_callback(_Module.ADAPTER) as dut_cb,):
            self.logger.info("[DUT] Terminate connection.")
            self.dut.bt.disconnect(self.ref.address)
            await dut_cb.wait_for_event(
                bl4a_api.AclDisconnected(
                    address=self.ref.address,
                    transport=android_constants.Transport.CLASSIC,
                ),)

    async def test_pair_and_connect(self) -> None:
        """Tests HFP connection establishment right after a pairing session.

    Test steps:
      1. Setup HFP on REF.
      2. Create bond from DUT.
      3. Wait HFP connected on DUT.(Android should autoconnect HFP as AG)
    """
        with (self.dut.bl4a.register_callback(_Module.HFP_AG) as dut_cb,):
            hfp_ext.HfProtocol.setup_server(
                self.ref.device,
                sdp_handle=_HFP_SDP_HANDLE,
                configuration=hfp_ext.make_hf_configuration(),
            )

            self.logger.info("[DUT] Connect and pair REF.")
            await self.classic_connect_and_pair(connect_profiles=True)

            self.logger.info("[DUT] Wait for HFP connected.")
            await dut_cb.wait_for_event(
                bl4a_api.ProfileActiveDeviceChanged(address=self.ref.address),
                timeout=_DEFAULT_STEP_TIMEOUT_SECONDS,
            )

    async def test_paired_connect_outgoing(self) -> None:
        """Tests HFP connection establishment where pairing is not involved.

    Test steps:
      1. Setup pairing between DUT and REF.
      2. Terminate ACL connection.
      3. Trigger connection from DUT.
      4. Wait HFP connected on DUT.
      5. Disconnect from DUT.
      6. Wait HFP disconnected on DUT.
    """
        with (self.dut.bl4a.register_callback(_Module.HFP_AG) as dut_cb,):
            await self.test_pair_and_connect()
            ref_address = self.ref.address

            await self._terminate_connection_from_dut()

            self.logger.info("[DUT] Reconnect.")
            self.dut.bt.connect(ref_address)

            self.logger.info("[DUT] Wait for HFP connected.")
            await dut_cb.wait_for_event(
                bl4a_api.ProfileActiveDeviceChanged(address=self.ref.address),
                timeout=_DEFAULT_STEP_TIMEOUT_SECONDS,
            )

            self.logger.info("[DUT] Disconnect.")
            self.dut.bt.disconnect(ref_address)

            self.logger.info("[DUT] Wait for HFP disconnected.")
            await dut_cb.wait_for_event(
                bl4a_api.ProfileActiveDeviceChanged(address=None),
                timeout=_DEFAULT_STEP_TIMEOUT_SECONDS,
            )

    async def test_paired_connect_incoming(self) -> None:
        """Tests HFP connection establishment where pairing is not involved.

    Test steps:
      1. Setup pairing between DUT and REF.
      2. Terminate ACL connection.
      3. Trigger connection from REF.
      4. Wait HFP connected on DUT.
      5. Disconnect from REF.
      6. Wait HFP disconnected on DUT.
    """
        dut_cb = self.dut.bl4a.register_callback(_Module.HFP_AG)
        self.test_case_context.push(dut_cb)
        await self.test_pair_and_connect()

        await self._terminate_connection_from_dut()

        self.logger.info("[REF] Reconnect.")
        dut_ref_acl = await self.ref.device.connect(
            self.dut.address,
            core.BT_BR_EDR_TRANSPORT,
            timeout=_DEFAULT_STEP_TIMEOUT_SECONDS,
        )

        self.logger.info("[REF] Authenticate and encrypt connection.")
        await dut_ref_acl.authenticate()
        await dut_ref_acl.encrypt()

        rfcomm_channel = await rfcomm.find_rfcomm_channel_with_uuid(
            dut_ref_acl, core.BT_HANDSFREE_AUDIO_GATEWAY_SERVICE)
        if rfcomm_channel is None:
            self.fail("No HFP RFCOMM channel found on REF.")
        self.logger.info("[REF] Found HFP RFCOMM channel %s.", rfcomm_channel)

        self.logger.info("[REF] Open RFCOMM Multiplexer.")
        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            multiplexer = await rfcomm.Client(dut_ref_acl).start()

        self.logger.info("[REF] Open RFCOMM DLC.")
        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            dlc = await multiplexer.open_dlc(rfcomm_channel)

        self.logger.info("[REF] Establish SLC.")
        ref_hfp_protocol = hfp_ext.HfProtocol(dlc, hfp_ext.make_hf_configuration())
        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            await ref_hfp_protocol.initiate_slc()

        self.logger.info("[DUT] Wait for HFP connected.")
        await dut_cb.wait_for_event(
            bl4a_api.ProfileActiveDeviceChanged(address=self.ref.address),
            timeout=_DEFAULT_STEP_TIMEOUT_SECONDS,
        )

        self.logger.info("[REF] Disconnect.")
        await dut_ref_acl.disconnect()

        self.logger.info("[DUT] Wait for HFP disconnected.")
        await dut_cb.wait_for_event(
            bl4a_api.ProfileActiveDeviceChanged(address=None),
            timeout=_DEFAULT_STEP_TIMEOUT_SECONDS,
        )

    @navi_test_base.named_parameterized(
        cvsd_only=dict(supported_audio_codecs=[
            _AudioCodec.CVSD,
        ]),
        cvsd_msbc=dict(supported_audio_codecs=[
            _AudioCodec.CVSD,
            _AudioCodec.MSBC,
        ]),
        cvsd_msbc_lc3_swb=dict(supported_audio_codecs=[
            _AudioCodec.CVSD,
            _AudioCodec.MSBC,
            _AudioCodec.LC3_SWB,
        ]),
    )
    async def test_call_sco_connection_with_codec_negotiation(
        self,
        supported_audio_codecs: list[hfp.AudioCodec],
    ) -> None:
        """Tests making an outgoing phone call, observing SCO connection status.

    Test steps:
      1. Setup HFP connection.
      2. Place an outgoing call.
      3. Verify SCO connected.
      4. Terminate the call.
      5. Verify SCO disconnected.

    Args:
      supported_audio_codecs: Audio codecs supported by REF device.
    """

        # [REF] Setup HFP.
        hfp_configuration = hfp_ext.make_hf_configuration(
            supported_hf_features=[hfp.HfFeature.CODEC_NEGOTIATION],
            supported_hf_indicators=[],
            supported_audio_codecs=supported_audio_codecs,
        )
        ref_hfp_protocol_queue = hfp_ext.HfProtocol.setup_server(
            self.ref.device,
            sdp_handle=_HFP_SDP_HANDLE,
            configuration=hfp_configuration,
        )

        if (_AudioCodec.LC3_SWB in supported_audio_codecs and
                self.dut.getprop(_PROPERTY_SWB_SUPPORTED) == "true"):
            preferred_codec = _AudioCodec.LC3_SWB
            # Sample rate is defined in HFP 1.9 spec.
            sample_rate = 32000
        elif _AudioCodec.MSBC in supported_audio_codecs:
            preferred_codec = _AudioCodec.MSBC
            sample_rate = 16000
        else:
            preferred_codec = _AudioCodec.CVSD
            sample_rate = 8000
        # PCM frame size = sample_rate * frame_duration (7.5ms) * sample_width (2)
        pcm_frame_size = int(sample_rate * _HFP_FRAME_DURATION * 2)

        dut_hfp_cb = self.dut.bl4a.register_callback(_Module.HFP_AG)
        dut_telecom_cb = self.dut.bl4a.register_callback(_Module.TELECOM)
        self.test_case_context.push(dut_hfp_cb)
        self.test_case_context.push(dut_telecom_cb)

        self.logger.info("[DUT] Connect and pair REF.")
        await self.classic_connect_and_pair(connect_profiles=True)

        self.logger.info("[DUT] Wait for HFP connected.")
        await dut_hfp_cb.wait_for_event(
            bl4a_api.ProfileActiveDeviceChanged(address=self.ref.address),
            timeout=_DEFAULT_STEP_TIMEOUT_SECONDS,
        )

        async with self.assert_not_timeout(
                _DEFAULT_STEP_TIMEOUT_SECONDS,
                msg="[REF] Wait for HFP connected.",
        ):
            ref_hfp_protocol = await ref_hfp_protocol_queue.get()

        sco_links = asyncio.Queue[device.ScoLink]()
        self.ref.device.on(self.ref.device.EVENT_SCO_CONNECTION, sco_links.put_nowait)

        self.logger.info("[DUT] Add call.")
        with self.dut.bl4a.make_phone_call(
                _CALLER_NAME,
                _CALLER_NUMBER,
                constants.Direction.OUTGOING,
        ) as call:
            await self._wait_for_call_state(dut_telecom_cb, _CallState.CONNECTING,
                                            _CallState.DIALING)

            self.logger.info("[DUT] Start streaming.")
            self.dut.bt.audioSetRepeat(android_constants.RepeatMode.ONE)
            await asyncio.to_thread(self.dut.bt.audioPlaySine)

            self.logger.info("[DUT] Wait for SCO connected.")
            await self._wait_for_sco_state(dut_hfp_cb, _ScoState.CONNECTED)

            async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
                self.logger.info("[REF] Wait for SCO connected.")
                sco_link = await sco_links.get()

                self.assertEqual(ref_hfp_protocol.active_codec, preferred_codec)

            self.logger.info("[DUT] Start recording.")
            recorder = await asyncio.to_thread(
                lambda: self.dut.bl4a.start_audio_recording(_RECORDING_PATH))
            # Make sure the recorder is closed after the test.
            self.test_case_context.push(recorder)

            esco_parameters = await ref_hfp_protocol.get_esco_parameters()
            check_audio_correctness = (
                # We don't support transparent audio packets for now.
                esco_parameters.input_coding_format.codec_id == hci.CodecID.LINEAR_PCM
                # Skip audio correctness check on emulators.
                and not self.dut.device.is_emulator and audio.SUPPORT_AUDIO_PROCESSING)
            ref_sink_buffer = bytearray()
            if check_audio_correctness:
                sine_tone_batch_iterator = itertools.cycle(
                    audio.batched(
                        audio.generate_sine_tone(
                            frequency=1000,
                            duration=1.0,
                            sample_rate=sample_rate,
                            data_type="int16",
                        ),
                        n=pcm_frame_size,
                    ))

                async def source_streamer() -> None:
                    while sco_link.handle in self.ref.device.sco_links:
                        tx_data = next(sine_tone_batch_iterator)
                        for offset in range(0, len(tx_data), _MAX_FRAME_SIZE):
                            buffer = tx_data[offset:offset + _MAX_FRAME_SIZE]
                            self.ref.device.host.send_hci_packet(
                                hci.HCI_SynchronousDataPacket(
                                    connection_handle=sco_link.handle,
                                    packet_status=0,
                                    data_total_length=len(buffer),
                                    data=bytes(buffer),
                                ))
                        # Sleep for 90% of the frame duration, or packets might be dropped.
                        await asyncio.sleep(_HFP_FRAME_DURATION * 0.9)

                def on_sco_packet(packet: hci.HCI_SynchronousDataPacket) -> None:
                    ref_sink_buffer.extend(packet.data)

                sco_link.sink = on_sco_packet
                sco_link.abort_on(sco_link.EVENT_DISCONNECTION, source_streamer())

            # Streaming for 5 seconds.
            await asyncio.sleep(5.0)

            self.logger.info("[DUT] Terminate call.")
            call.close()
            await self._wait_for_call_state(dut_telecom_cb, _CallState.DISCONNECTED)

        self.logger.info("[DUT] Wait for SCO disconnected.")
        await self._wait_for_sco_state(dut_hfp_cb, _ScoState.DISCONNECTED)

        self.logger.info("[DUT] Stop recording.")
        await asyncio.to_thread(recorder.close)

        # Get recording from DUT.
        rx_received_buffer = self.dut.adb.shell([
            "cat",
            f"/data/media/{self.dut.adb.current_user_id}/Recordings/record.wav",
        ])

        if (self.user_params.get(navi_test_base.RECORD_FULL_DATA) and rx_received_buffer):
            self.write_test_output_data(
                f"hfp_ag_data.{preferred_codec.name.lower()}",
                rx_received_buffer,
            )

        if check_audio_correctness:
            tx_dominant_frequency = audio.get_dominant_frequency(
                ref_sink_buffer,
                format="pcm",
                frame_rate=sample_rate,
                channels=1,
                sample_width=2,  # 16-bit
            )
            self.logger.info("[Tx] Dominant frequency: %.2f", tx_dominant_frequency)
            self.assertAlmostEqual(tx_dominant_frequency, 1000, delta=10)
            rx_dominant_frequency = audio.get_dominant_frequency(rx_received_buffer, format="wav")
            self.logger.info("[Rx] Dominant frequency: %.2f", rx_dominant_frequency)
            self.assertAlmostEqual(rx_dominant_frequency, 1000, delta=10)

    @navi_test_base.parameterized(_CallAnswer.ACCEPT, _CallAnswer.REJECT)
    async def test_answer_call_from_ref(self, call_answer: _CallAnswer) -> None:
        """Tests answering an incoming phone call from REF.

    Test steps:
      1. Setup HFP connection.
      2. Place an incoming call.
      3. Answer call on REF.
      4. Verify call status.

    Args:
      call_answer: Answer type of call.
    """
        if self._is_ranchu_emulator(self.dut.device):
            self.skipTest("Call control is not supported on Ranchu emulator")

        # [REF] Setup HFP.
        hfp_configuration = hfp.HfConfiguration(
            supported_hf_features=[],
            supported_hf_indicators=[],
            supported_audio_codecs=[hfp.AudioCodec.CVSD],
        )
        ref_hfp_protocol_queue = hfp_ext.HfProtocol.setup_server(
            self.ref.device,
            sdp_handle=_HFP_SDP_HANDLE,
            configuration=hfp_configuration,
        )

        dut_hfp_cb = self.dut.bl4a.register_callback(_Module.HFP_AG)
        dut_telecom_cb = self.dut.bl4a.register_callback(_Module.TELECOM)
        self.test_case_context.push(dut_hfp_cb)
        self.test_case_context.push(dut_telecom_cb)

        self.logger.info("[DUT] Connect and pair REF.")
        await self.classic_connect_and_pair(connect_profiles=True)

        async with self.assert_not_timeout(
                _DEFAULT_STEP_TIMEOUT_SECONDS,
                msg="[REF] Wait for HFP connected.",
        ):
            ref_hfp_protocol = await ref_hfp_protocol_queue.get()

        self.logger.info("[DUT] Wait for HFP connected.")
        await dut_hfp_cb.wait_for_event(
            bl4a_api.ProfileActiveDeviceChanged(address=self.ref.address),
            timeout=_DEFAULT_STEP_TIMEOUT_SECONDS,
        )

        condition = asyncio.Condition()

        @ref_hfp_protocol.on(ref_hfp_protocol.EVENT_AG_INDICATOR)
        async def _(*_) -> None:
            async with condition:
                condition.notify_all()

        self.logger.info("[DUT] Make incoming call.")
        with self.dut.bl4a.make_phone_call(
                _CALLER_NAME,
                _CALLER_NUMBER,
                constants.Direction.INCOMING,
        ):
            await self._wait_for_call_state(dut_telecom_cb, _CallState.RINGING)

            self.logger.info("[REF] Wait for callsetup.")
            async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
                async with condition:
                    call_setup = next(indicator for indicator in ref_hfp_protocol.ag_indicators
                                      if indicator.indicator == hfp.AgIndicator.CALL_SETUP)
                    await condition.wait_for(lambda: (call_setup.current_status == 1))

            if call_answer == _CallAnswer.ACCEPT:
                self.logger.info("[REF] Answer call.")
                await ref_hfp_protocol.answer_incoming_call()
                await self._wait_for_call_state(dut_telecom_cb, _CallState.ACTIVE)
            else:
                self.logger.info("[REF] Reject call.")
                await ref_hfp_protocol.reject_incoming_call()
                await self._wait_for_call_state(dut_telecom_cb, _CallState.DISCONNECTED)

    @navi_test_base.parameterized(
        constants.Direction.INCOMING,
        constants.Direction.OUTGOING,
    )
    async def test_callsetup_ag_indicator(
        self,
        direction: constants.Direction,
    ) -> None:
        """Tests making phone call, observing AG indicator.

    Test steps:
      1. Setup HFP connection.
      2. Place a phone call.
      3. Verify callsetup ag indicator.
      4. Answer the call
      5. Verify callsetup and call ag indicator.
      6. Terminate the call.
      7. Verify call ag indicator.

    Args:
      direction: The direction of phone call.
    """

        # [REF] Setup HFP.
        hfp_configuration = hfp.HfConfiguration(
            supported_hf_features=[],
            supported_hf_indicators=[],
            supported_audio_codecs=[hfp.AudioCodec.CVSD],
        )
        ref_hfp_protocol_queue = hfp_ext.HfProtocol.setup_server(
            self.ref.device,
            sdp_handle=_HFP_SDP_HANDLE,
            configuration=hfp_configuration,
        )

        self.logger.info("[DUT] Connect and pair REF.")
        with self.dut.bl4a.register_callback(_Module.HFP_AG) as dut_hfp_cb:
            await self.classic_connect_and_pair(connect_profiles=True)

            self.logger.info("[DUT] Wait for HFP connected.")
            await dut_hfp_cb.wait_for_event(
                bl4a_api.ProfileActiveDeviceChanged(address=self.ref.address),
                timeout=_DEFAULT_STEP_TIMEOUT_SECONDS,
            )

        async with self.assert_not_timeout(
                _DEFAULT_STEP_TIMEOUT_SECONDS,
                msg="[REF] Wait for HFP connected.",
        ):
            ref_hfp_protocol = await ref_hfp_protocol_queue.get()

        ag_indicators = collections.defaultdict[hfp.AgIndicator, asyncio.Queue[int]](asyncio.Queue)

        def on_ag_indicator(ag_indicator: hfp.AgIndicatorState) -> None:
            ag_indicators[ag_indicator.indicator].put_nowait(ag_indicator.current_status)

        ref_hfp_protocol.on(ref_hfp_protocol.EVENT_AG_INDICATOR, on_ag_indicator)

        self.logger.info("[DUT] Make phone call.")
        with self.dut.bl4a.make_phone_call(_CALLER_NAME, _CALLER_NUMBER, direction) as call:
            if direction == constants.Direction.INCOMING:
                self.logger.info("[REF] Wait for (callsetup, 1 - incoming).")
                async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
                    self.assertEqual(
                        await ag_indicators[_AgIndicator.CALL_SETUP].get(),
                        hfp.CallSetupAgIndicator.INCOMING_CALL_PROCESS,
                    )
            else:
                self.logger.info("[REF] Wait for (callsetup, 2 - outgoing).")
                async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
                    self.assertEqual(
                        await ag_indicators[_AgIndicator.CALL_SETUP].get(),
                        hfp.CallSetupAgIndicator.OUTGOING_CALL_SETUP,
                    )
                self.logger.info("[REF] Wait for (callsetup, 3 - remote alerted).")
                async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
                    self.assertEqual(
                        await ag_indicators[_AgIndicator.CALL_SETUP].get(),
                        hfp.CallSetupAgIndicator.REMOTE_ALERTED,
                    )

            self.logger.info("[DUT] Answer Call.")
            call.answer()

            self.logger.info("[REF] Wait for (callsetup, 0 - not in setup).")
            async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
                self.assertEqual(
                    await ag_indicators[_AgIndicator.CALL_SETUP].get(),
                    hfp.CallSetupAgIndicator.NOT_IN_CALL_SETUP,
                )

            self.logger.info("[REF] Wait for (call, 1 - active).")
            async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
                self.assertEqual(
                    await ag_indicators[_AgIndicator.CALL].get(),
                    _CallAgIndicator.ACTIVE,
                )

        self.logger.info("[REF] Wait for (call, 0 - inactive).")
        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            self.assertEqual(
                await ag_indicators[_AgIndicator.CALL].get(),
                _CallAgIndicator.INACTIVE,
            )

    async def test_update_battery_level(self) -> None:
        """Tests updating battery level indicator from HF.

    Test steps:
      1. Setup HFP connection.
      2. Send battery level indicator from HF.
      3. Verify call ag indicator.
    """

        # [REF] Setup HFP.
        hfp_configuration = hfp.HfConfiguration(
            supported_hf_features=[hfp.HfFeature.HF_INDICATORS],
            supported_hf_indicators=[hfp.HfIndicator.BATTERY_LEVEL],
            supported_audio_codecs=[hfp.AudioCodec.CVSD],
        )
        ref_hfp_protocol_queue = hfp_ext.HfProtocol.setup_server(
            self.ref.device,
            sdp_handle=_HFP_SDP_HANDLE,
            configuration=hfp_configuration,
        )

        with (
                self.dut.bl4a.register_callback(_Module.HFP_AG) as dut_hfp_cb,
                self.dut.bl4a.register_callback(_Module.ADAPTER) as dut_adapter_cb,
        ):
            await self.classic_connect_and_pair(connect_profiles=True)
            self.logger.info("[DUT] Wait for HFP connected.")
            await dut_hfp_cb.wait_for_event(
                bl4a_api.ProfileActiveDeviceChanged(address=self.ref.address),
                timeout=_DEFAULT_STEP_TIMEOUT_SECONDS,
            )

            async with self.assert_not_timeout(
                    _DEFAULT_STEP_TIMEOUT_SECONDS,
                    msg="[REF] Wait for HFP connected.",
            ):
                ref_hfp_protocol = await ref_hfp_protocol_queue.get()

            if not ref_hfp_protocol.supports_ag_feature(hfp.AgFeature.HF_INDICATORS):
                raise signals.TestSkip("DUT doesn't support HF Indicator")

            for i in range(101):
                await ref_hfp_protocol.execute_command(
                    f"AT+BIEV={hfp.HfIndicator.BATTERY_LEVEL.value},{i}")
                event = await dut_adapter_cb.wait_for_event(
                    bl4a_api.BatteryLevelChanged,
                    predicate=lambda e: (e.address == self.ref.address),
                )
                self.assertEqual(event.level, i)

    async def test_connect_hf_during_call_should_route_to_hf(self) -> None:
        """Tests connecting HFP during phone call should route to HFP.

    Test steps:
      1. Place a call.
      2. Setup HFP connection.
    """

        # [REF] Setup HFP.
        hfp_configuration = hfp.HfConfiguration(
            supported_hf_features=[],
            supported_hf_indicators=[],
            supported_audio_codecs=[hfp.AudioCodec.CVSD],
        )
        hfp_ext.HfProtocol.setup_server(
            self.ref.device,
            sdp_handle=_HFP_SDP_HANDLE,
            configuration=hfp_configuration,
        )

        self.logger.info("[DUT] Make outgoing call.")
        with (
                self.dut.bl4a.register_callback(_Module.TELECOM) as dut_telecom_cb,
                self.dut.bl4a.make_phone_call(
                    _CALLER_NAME,
                    _CALLER_NUMBER,
                    constants.Direction.OUTGOING,
                ),
        ):
            self.dut.bt.audioSetRepeat(android_constants.RepeatMode.ONE)
            self.dut.bt.audioPlaySine()

            await self._wait_for_call_state(dut_telecom_cb, _CallState.CONNECTING,
                                            _CallState.DIALING)

            self.logger.info("[DUT] Connect and pair REF.")
            with self.dut.bl4a.register_callback(_Module.HFP_AG) as dut_hfp_cb:
                await self.classic_connect_and_pair(connect_profiles=True)

                self.logger.info("[DUT] Wait for SCO connected.")
                await self._wait_for_sco_state(dut_hfp_cb, _ScoState.CONNECTED)

    @navi_test_base.parameterized(constants.TestRole.DUT, constants.TestRole.REF)
    @navi_test_base.retry(max_count=2)
    async def test_adjust_speaker_volume(self, issuer: constants.TestRole) -> None:
        """Tests adjusting speaker volume with HFP.

    Test steps:
      1. Place a call.
      2. Setup HFP connection.
      3. Adjust volume.

    Args:
      issuer: The issuer of volume adjustment.
    """
        if self._is_ranchu_emulator(self.dut.device):
            self.skipTest("Volume control is not supported on Ranchu emulator")
        if self.dut.device.is_emulator:
            self.skipTest("b/422822911: SCO is not fully supported on emulator")

        # [REF] Setup HFP.
        hfp_configuration = hfp.HfConfiguration(
            supported_hf_features=[hfp.HfFeature.REMOTE_VOLUME_CONTROL],
            supported_hf_indicators=[],
            supported_audio_codecs=[hfp.AudioCodec.CVSD],
        )
        ref_hfp_protocol_queue = hfp_ext.HfProtocol.setup_server(
            self.ref.device,
            sdp_handle=_HFP_SDP_HANDLE,
            configuration=hfp_configuration,
        )

        self.logger.info("[DUT] Connect and pair REF.")
        with (
                self.dut.bl4a.register_callback(_Module.HFP_AG) as dut_hfp_cb,
                self.dut.bl4a.register_callback(_Module.AUDIO) as dut_audio_cb,
                self.dut.bl4a.make_phone_call(
                    _CALLER_NAME,
                    _CALLER_NUMBER,
                    constants.Direction.OUTGOING,
                ),
        ):
            await self.classic_connect_and_pair(connect_profiles=True)

            self.dut.bt.audioSetRepeat(android_constants.RepeatMode.ONE)
            self.dut.bt.audioPlaySine()

            self.logger.info("[DUT] Wait for SCO connected.")
            await self._wait_for_sco_state(dut_hfp_cb, _ScoState.CONNECTED)
            async with self.assert_not_timeout(
                    _DEFAULT_STEP_TIMEOUT_SECONDS,
                    msg="[REF] Wait for HFP connected.",
            ):
                ref_hfp_protocol = await ref_hfp_protocol_queue.get()

            if not self.dut.device.is_emulator:
                self.logger.info("[DUT] Wait for SCO active.")
                await dut_audio_cb.wait_for_event(
                    bl4a_api.CommunicationDeviceChanged(
                        self.ref.address,
                        device_type=android_constants.AudioDeviceType.BLUETOOTH_SCO,
                    ))

            # Somehow volume change cannot be broadcasted to Bluetooth at the moment
            # when SCO becomes active.
            await asyncio.sleep(0.5)

            for expected_volume in range(1, _HFP_MAX_VOLUME + 1):
                if expected_volume == self.dut.bt.getVolume(_STREAM_TYPE_CALL):
                    continue

                if issuer == constants.TestRole.DUT:
                    self.logger.info("[DUT] Set volume to %d.", expected_volume)
                    self.dut.bt.setVolume(_STREAM_TYPE_CALL, expected_volume)

                    self.logger.info("[REF] Wait for volume changed event.")
                    async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
                        async with ref_hfp_protocol.speaker_volume_condition:
                            await ref_hfp_protocol.speaker_volume_condition.wait_for(
                                lambda: ref_hfp_protocol.speaker_volume == expected_volume,  # pylint: disable=cell-var-from-loop
                            )
                else:
                    self.logger.info("[REF] Set volume to %d.", expected_volume)
                    await ref_hfp_protocol.execute_command(f"AT+VGS={expected_volume}")

                    self.logger.info("[DUT] Wait for volume changed event.")
                    await dut_audio_cb.wait_for_event(event=bl4a_api.VolumeChanged(
                        stream_type=_STREAM_TYPE_CALL, volume_value=expected_volume),)

    async def test_query_call_status(self) -> None:
        """Tests querying call status from HF.

    Test steps:
      1. Setup HFP connection.
      2. Place a call.
      3. Query call status from HF.
      4. Terminate the call.
      5. Query call status from HF.
    """

        # [REF] Setup HFP.
        hfp_configuration = hfp.HfConfiguration(
            supported_hf_features=[],
            supported_hf_indicators=[],
            supported_audio_codecs=[hfp.AudioCodec.CVSD],
        )
        ref_hfp_protocol_queue = hfp_ext.HfProtocol.setup_server(
            self.ref.device,
            sdp_handle=_HFP_SDP_HANDLE,
            configuration=hfp_configuration,
        )

        self.logger.info("[DUT] Connect and pair REF.")
        with self.dut.bl4a.register_callback(_Module.HFP_AG) as dut_hfp_cb:
            await self.classic_connect_and_pair(connect_profiles=True)
            await dut_hfp_cb.wait_for_event(
                bl4a_api.ProfileActiveDeviceChanged(address=self.ref.address),
                timeout=_DEFAULT_STEP_TIMEOUT_SECONDS,
            )

            async with self.assert_not_timeout(
                    _DEFAULT_STEP_TIMEOUT_SECONDS,
                    msg="[REF] Wait for HFP connected.",
            ):
                ref_hfp_protocol = await ref_hfp_protocol_queue.get()

        ag_indicators = collections.defaultdict[hfp.AgIndicator, asyncio.Queue[int]](asyncio.Queue)

        def on_ag_indicator(ag_indicator: hfp.AgIndicatorState) -> None:
            ag_indicators[ag_indicator.indicator].put_nowait(ag_indicator.current_status)

        ref_hfp_protocol.on(ref_hfp_protocol.EVENT_AG_INDICATOR, on_ag_indicator)

        self.logger.info("[DUT] Make incoming call.")
        with self.dut.bl4a.make_phone_call(
                _CALLER_NAME,
                _CALLER_NUMBER,
                constants.Direction.INCOMING,
        ):
            async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
                call_setup_state = await ag_indicators[_AgIndicator.CALL_SETUP].get()
                self.assertEqual(call_setup_state, 1)

            calls = await ref_hfp_protocol.query_current_calls()
            self.assertLen(calls, 1)
            self.assertEqual(
                calls[0].direction,
                hfp.CallInfoDirection.MOBILE_TERMINATED_CALL,
            )
            self.assertEqual(calls[0].status, hfp.CallInfoStatus.INCOMING)
            self.assertEqual(calls[0].number, _CALLER_NUMBER)

        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            call_setup_state = await ag_indicators[_AgIndicator.CALL_SETUP].get()
            self.assertEqual(call_setup_state, 0)

        calls = await ref_hfp_protocol.query_current_calls()
        self.assertEmpty(calls)

    async def test_hold_unhold_call(self) -> None:
        """Tests holding and unholding call with HFP.

    Test steps:
      1. Setup HFP connection.
      2. Place an outgoing call.
      3. Hold the call.
      4. Unhold the call.
    """
        if self._is_ranchu_emulator(self.dut.device):
            self.skipTest("Call hold is not supported on Ranchu emulator")

        # [REF] Setup HFP.
        hfp_configuration = hfp.HfConfiguration(
            supported_hf_features=[hfp.HfFeature.THREE_WAY_CALLING],
            supported_hf_indicators=[],
            supported_audio_codecs=[hfp.AudioCodec.CVSD],
        )
        ref_hfp_protocol_queue = hfp_ext.HfProtocol.setup_server(
            self.ref.device,
            sdp_handle=_HFP_SDP_HANDLE,
            configuration=hfp_configuration,
        )

        self.logger.info("[DUT] Connect and pair REF.")
        with self.dut.bl4a.register_callback(_Module.HFP_AG) as dut_hfp_cb:
            await self.classic_connect_and_pair(connect_profiles=True)
            await dut_hfp_cb.wait_for_event(
                bl4a_api.ProfileActiveDeviceChanged(address=self.ref.address),
                timeout=_DEFAULT_STEP_TIMEOUT_SECONDS,
            )

            async with self.assert_not_timeout(
                    _DEFAULT_STEP_TIMEOUT_SECONDS,
                    msg="[REF] Wait for HFP connected.",
            ):
                ref_hfp_protocol = await ref_hfp_protocol_queue.get()

        ag_indicators = collections.defaultdict[hfp.AgIndicator, asyncio.Queue[int]](asyncio.Queue)

        def on_ag_indicator(ag_indicator: hfp.AgIndicatorState) -> None:
            ag_indicators[ag_indicator.indicator].put_nowait(ag_indicator.current_status)

        ref_hfp_protocol.on(ref_hfp_protocol.EVENT_AG_INDICATOR, on_ag_indicator)

        self.logger.info("[DUT] Make incoming call.")
        with (
                self.dut.bl4a.register_callback(_Module.TELECOM) as dut_telecom_cb,
                self.dut.bl4a.make_phone_call(
                    _CALLER_NAME,
                    _CALLER_NUMBER,
                    constants.Direction.OUTGOING,
                ) as call,
        ):
            # 25Q1 => CONNECTING, 25Q2 -> DIALING
            await self._wait_for_call_state(dut_telecom_cb, _CallState.CONNECTING,
                                            _CallState.DIALING)
            call.answer()
            await self._wait_for_call_state(dut_telecom_cb, _CallState.ACTIVE)

            self.logger.info("[REF] Hold call.")
            await ref_hfp_protocol.execute_command("AT+CHLD=2")

            self.logger.info("[DUT] Wait for call state to be HOLDING.")
            await self._wait_for_call_state(dut_telecom_cb, _CallState.HOLDING)

            async with self.assert_not_timeout(
                    _DEFAULT_STEP_TIMEOUT_SECONDS,
                    msg="[REF] Wait for call state to be HOLDING.",
            ):
                call_setup_state = await ag_indicators[_AgIndicator.CALL_HELD].get()
                self.assertEqual(
                    call_setup_state,
                    hfp.CallHeldAgIndicator.CALL_ON_HOLD_NO_ACTIVE_CALL,
                )

            self.logger.info("[REF] Unhold call.")
            await ref_hfp_protocol.execute_command("AT+CHLD=2")

            self.logger.info("[DUT] Wait for call state to be ACTIVE.")
            await self._wait_for_call_state(dut_telecom_cb, _CallState.ACTIVE)

            async with self.assert_not_timeout(
                    _DEFAULT_STEP_TIMEOUT_SECONDS,
                    msg="[REF] Wait for call state to be NO_CALLS_HELD.",
            ):
                call_setup_state = await ag_indicators[_AgIndicator.CALL_HELD].get()
                self.assertEqual(call_setup_state, hfp.CallHeldAgIndicator.NO_CALLS_HELD)

    @navi_test_base.named_parameterized(
        from_ag=constants.TestRole.DUT,
        from_hf=constants.TestRole.REF,
    )
    async def test_voice_recognition(self, initiator: constants.TestRole) -> None:
        """Tests voice recognition.

    Test steps:
      1. Setup HFP connection.
      2. Start voice recognition.
      3. Stop voice recognition.

    Args:
      initiator: The initiator of voice recognition process.
    """
        # [REF] Setup HFP.
        hfp_configuration = hfp.HfConfiguration(
            supported_hf_features=[
                hfp.HfFeature.THREE_WAY_CALLING,
                hfp.HfFeature.VOICE_RECOGNITION_ACTIVATION,
            ],
            supported_hf_indicators=[],
            supported_audio_codecs=[hfp.AudioCodec.CVSD],
        )
        ref_hfp_protocol_queue = hfp_ext.HfProtocol.setup_server(
            self.ref.device,
            sdp_handle=_HFP_SDP_HANDLE,
            configuration=hfp_configuration,
        )

        dut_hfp_cb = self.dut.bl4a.register_callback(_Module.HFP_AG)
        self.test_case_context.push(dut_hfp_cb)

        await self.classic_connect_and_pair(connect_profiles=True)
        self.logger.info("[DUT] Wait for HFP connected.")
        await dut_hfp_cb.wait_for_event(
            bl4a_api.ProfileActiveDeviceChanged(address=self.ref.address),
            timeout=_DEFAULT_STEP_TIMEOUT_SECONDS,
        )

        self.logger.info("[REF] Wait for HFP connected.")
        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            ref_hfp_protocol = await ref_hfp_protocol_queue.get()
            await ref_hfp_protocol.slc_initialized.wait()

        if not ref_hfp_protocol.supports_ag_feature(hfp.AgFeature.VOICE_RECOGNITION_FUNCTION):
            self.skipTest("REF doesn't support voice recognition activation")

        vr_state = [hfp.VoiceRecognitionState.DISABLE]
        condition = asyncio.Condition()

        @ref_hfp_protocol.on(ref_hfp_protocol.EVENT_VOICE_RECOGNITION)
        async def _(state: hfp.VoiceRecognitionState):
            async with condition:
                vr_state[0] = state
                condition.notify_all()

        voice_command_callback = self.dut.bl4a.register_voice_command_callback()
        self.test_case_context.push(voice_command_callback)
        activation_task: asyncio.Task | None = None
        if initiator == constants.TestRole.REF:
            self.logger.info("[REF] Start voice recognition.")
            # Android stack doesn't reply BVRA until
            # BluetoothHeadset.startVoiceRecognition() is called, so it must be
            # executed asynchronously.
            activation_task = asyncio.create_task(
                ref_hfp_protocol.execute_command("AT+BVRA=1",
                                                 timeout=_DEFAULT_STEP_TIMEOUT_SECONDS))
            self.logger.info("[DUT] Wait for voice recognition to be enabled.")
            await voice_command_callback.wait_for_event(
                bl4a_api.VoiceCommand(state=True),
                timeout=_DEFAULT_STEP_TIMEOUT_SECONDS,
            )
        # DUT always needs startVoiceRecognition() to:
        #   1. (HF-initiated) Send OK response for AT+BVRA.
        #   2. (AG-initiated) Send +BVRA=1 as AG-initiated voice recognition.
        self.logger.info("[DUT] Start voice recognition.")
        self.dut.bt.hfpAgStartVoiceRecognition(self.ref.address)

        if activation_task:
            self.logger.info("[REF] Wait for AT+BVRA result.")
            await activation_task

        # Only AG-initiated voice recognition should send +BVRA to the HF.
        if initiator == constants.TestRole.DUT:
            self.logger.info("[REF] Wait for voice recognition to be enabled.")
            async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
                async with condition:
                    await condition.wait_for(lambda: vr_state[0] == hfp.VoiceRecognitionState.ENABLE
                                            )

        recorder = self.dut.bl4a.start_audio_recording(_RECORDING_PATH)
        self.test_case_context.push(recorder)

        self.logger.info("[DUT] Wait for SCO connected.")
        await self._wait_for_sco_state(dut_hfp_cb, _ScoState.CONNECTED)

        if initiator == constants.TestRole.DUT:
            self.logger.info("[DUT] Stop voice recognition.")
            self.dut.bt.hfpAgStopVoiceRecognition(self.ref.address)
            self.logger.info("[REF] Wait for voice recognition to be disabled.")
            async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
                async with condition:
                    await condition.wait_for(
                        lambda: vr_state[0] == hfp.VoiceRecognitionState.DISABLE)
        else:
            self.logger.info("[REF] Stop voice recognition.")
            await ref_hfp_protocol.execute_command("AT+BVRA=0",
                                                   timeout=_DEFAULT_STEP_TIMEOUT_SECONDS)
            if self.dut.bluetooth_mainline_version < 361000000:
                self.logger.info("[DUT] Mainline version is too old, skip waiting for voice"
                                 " recognition to be disabled.")
            else:
                self.logger.info("[DUT] Wait for voice recognition to be disabled.")
                await voice_command_callback.wait_for_event(
                    bl4a_api.VoiceCommand(state=False),
                    timeout=_DEFAULT_STEP_TIMEOUT_SECONDS,
                )

        recorder.close()

        self.logger.info("[DUT] Wait for SCO disconnected.")
        await self._wait_for_sco_state(dut_hfp_cb, _ScoState.DISCONNECTED)


if __name__ == "__main__":
    test_runner.main()
