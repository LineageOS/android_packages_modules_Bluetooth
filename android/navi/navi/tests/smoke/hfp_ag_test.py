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
        # Make sure Bumble is on.
        await self.ref.open()

    @override
    async def async_setup_test(self) -> None:
        await super().async_setup_test()

    @override
    async def async_teardown_test(self) -> None:
        await super().async_teardown_test()
        # Make sure Bumble is off to cancel any running tasks.
        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            await self.ref.close()

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

    @classmethod
    def _default_hfp_configuration(cls) -> hfp.HfConfiguration:
        return hfp.HfConfiguration(
            supported_hf_features=[],
            supported_hf_indicators=[],
            supported_audio_codecs=[
                _AudioCodec.CVSD,
                _AudioCodec.MSBC,
            ],
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
                configuration=self._default_hfp_configuration(),
            )

            self.logger.info("[DUT] Connect and pair REF.")
            await self.classic_connect_and_pair()

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
        ref_hfp_protocol = hfp_ext.HfProtocol(dlc, self._default_hfp_configuration())
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

    @navi_test_base.parameterized(
        ([_AudioCodec.CVSD],),
        ([_AudioCodec.CVSD, _AudioCodec.MSBC],),
        ([_AudioCodec.LC3_SWB, _AudioCodec.CVSD, _AudioCodec.MSBC],),
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
        hfp_configuration = hfp.HfConfiguration(
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
        elif _AudioCodec.MSBC in supported_audio_codecs:
            preferred_codec = _AudioCodec.MSBC
        else:
            preferred_codec = _AudioCodec.CVSD

        dut_hfp_cb = self.dut.bl4a.register_callback(_Module.HFP_AG)
        dut_telecom_cb = self.dut.bl4a.register_callback(_Module.TELECOM)
        self.test_case_context.push(dut_hfp_cb)
        self.test_case_context.push(dut_telecom_cb)

        self.logger.info("[DUT] Connect and pair REF.")
        await self.classic_connect_and_pair()

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
        self.ref.device.on("sco_connection", sco_links.put_nowait)

        self.logger.info("[DUT] Add call.")
        with self.dut.bl4a.make_phone_call(
                _CALLER_NAME,
                _CALLER_NUMBER,
                constants.Direction.OUTGOING,
        ) as call:
            await self._wait_for_call_state(dut_telecom_cb, _CallState.CONNECTING,
                                            _CallState.DIALING)

            self.logger.info("[DUT] Wait for SCO connected.")
            await self._wait_for_sco_state(dut_hfp_cb, _ScoState.CONNECTED)

            async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
                self.logger.info("[REF] Wait for SCO connected.")
                await sco_links.get()

                self.assertEqual(ref_hfp_protocol.active_codec, preferred_codec)

            self.logger.info("[DUT] Start streaming.")
            await asyncio.to_thread(self.dut.bt.audioPlaySine)
            self.logger.info("[DUT] Start recording.")
            recorder = await asyncio.to_thread(
                lambda: self.dut.bl4a.start_audio_recording(_RECORDING_PATH))

            # Streaming for 5 seconds.
            await asyncio.sleep(5.0)

            self.logger.info("[DUT] Terminate call.")
            call.close()
            await self._wait_for_call_state(dut_telecom_cb, _CallState.DISCONNECTED)

        self.logger.info("[DUT] Wait for SCO disconnected.")
        await self._wait_for_sco_state(dut_hfp_cb, _ScoState.DISCONNECTED)

        self.logger.info("[DUT] Stop recording.")
        await asyncio.to_thread(recorder.close)

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
        await self.classic_connect_and_pair()

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

        self.logger.info("[DUT] Make incoming call.")
        with self.dut.bl4a.make_phone_call(
                _CALLER_NAME,
                _CALLER_NUMBER,
                constants.Direction.INCOMING,
        ):
            await self._wait_for_call_state(dut_telecom_cb, _CallState.RINGING)

            self.logger.info("[DUT] Wait for SCO connected.")
            await self._wait_for_sco_state(dut_hfp_cb, _ScoState.CONNECTED)

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
            await self.classic_connect_and_pair()

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

        ref_hfp_protocol.on("ag_indicator", on_ag_indicator)

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
            await self.classic_connect_and_pair()
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
            await self._wait_for_call_state(dut_telecom_cb, _CallState.CONNECTING,
                                            _CallState.DIALING)

            self.logger.info("[DUT] Connect and pair REF.")
            with self.dut.bl4a.register_callback(_Module.HFP_AG) as dut_hfp_cb:
                await self.classic_connect_and_pair()

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
            await self.classic_connect_and_pair()

            self.logger.info("[DUT] Wait for SCO connected.")
            await self._wait_for_sco_state(dut_hfp_cb, _ScoState.CONNECTED)
            async with self.assert_not_timeout(
                    _DEFAULT_STEP_TIMEOUT_SECONDS,
                    msg="[REF] Wait for HFP connected.",
            ):
                ref_hfp_protocol = await ref_hfp_protocol_queue.get()
            # Somehow volume change cannot be broadcasted to Bluetooth at the moment
            # when SCO becomes active.
            await asyncio.sleep(0.5)

            for expected_volume in range(1, _HFP_MAX_VOLUME + 1):
                if expected_volume == self.dut.bt.getVolume(_STREAM_TYPE_CALL):
                    continue

                if issuer == constants.TestRole.DUT:
                    volumes = asyncio.Queue[int]()
                    ref_hfp_protocol.on("speaker_volume", volumes.put_nowait)

                    self.logger.info("[DUT] Set volume to %d.", expected_volume)
                    self.dut.bt.setVolume(_STREAM_TYPE_CALL, expected_volume)

                    self.logger.info("[REF] Wait for volume changed event.")
                    async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
                        actual_volume = await volumes.get()
                    self.assertEqual(actual_volume, expected_volume)
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
            await self.classic_connect_and_pair()
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

        ref_hfp_protocol.on("ag_indicator", on_ag_indicator)

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
            await self.classic_connect_and_pair()
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

        ref_hfp_protocol.on("ag_indicator", on_ag_indicator)

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


if __name__ == "__main__":
    test_runner.main()
