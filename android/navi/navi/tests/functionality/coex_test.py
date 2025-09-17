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
"""Tests switching between multiple devices."""

import asyncio
from typing import TypeAlias
from unittest import mock

from bumble import device
from bumble import hci
from bumble import hfp
from bumble import rfcomm
from bumble import smp
from mobly import test_runner
from typing_extensions import override

from navi.bumble_ext import a2dp as a2dp_ext
from navi.bumble_ext import hfp as hfp_ext
from navi.tests import navi_test_base
from navi.utils import android_constants
from navi.utils import bl4a_api
from navi.utils import constants

_DEFAULT_STEP_TIMEOUT_SECONDS = 5.0
_A2DP_SERVICE_RECORD_HANDLE = 1
_AVRCP_CONTROLLER_RECORD_HANDLE = 2
_AVRCP_TARGET_RECORD_HANDLE = 3
_HFP_SDP_HANDLE = 4
_CALLER_NAME = "Pixel Bluetooth"
_CALLER_NUMBER = "123456789"

_AudioCodec = hfp.AudioCodec
_Module: TypeAlias = bl4a_api.Module
_ScoState = android_constants.ScoState
_HfpAgAudioStateChange = bl4a_api.HfpAgAudioStateChanged
_CallState = android_constants.CallState
_CallbackHandler = bl4a_api.CallbackHandler

_DEFAULT_HF_CONFIGURATION = hfp.HfConfiguration(
    supported_hf_features=[],
    supported_hf_indicators=[],
    supported_audio_codecs=[
        _AudioCodec.CVSD,
        _AudioCodec.MSBC,
    ],
)

_DEFAULT_AG_CONFIGURATION = hfp.AgConfiguration(
    supported_ag_features=(hfp.AgFeature.ENHANCED_CALL_STATUS,),
    supported_ag_indicators=([
        hfp.AgIndicatorState.call(),
        hfp.AgIndicatorState.callsetup(),
        hfp.AgIndicatorState.service(),
        hfp.AgIndicatorState.signal(),
        hfp.AgIndicatorState.roam(),
        hfp.AgIndicatorState.callheld(),
        hfp.AgIndicatorState.battchg(),
    ]),
    supported_hf_indicators=[],
    supported_ag_call_hold_operations=[],
    supported_audio_codecs=[
        _AudioCodec.CVSD,
        _AudioCodec.MSBC,
    ],
)


class CoexTest(navi_test_base.MultiDevicesTestBase):
    ref_supports_lc3: bool

    @override
    async def async_setup_class(self) -> None:
        await super().async_setup_class()
        for ref in self.refs:
            response = await ref.device.send_command(
                hci.HCI_Read_Local_Supported_Codecs_Command(),
                check_result=True,
            )
            supported_codecs = list(
                hci.CodecID(codec) for codec in response.return_parameters.standard_codec_ids)
            self.logger.info("[REF] Supported codecs: %s", supported_codecs)
            self.ref_supports_lc3 = hci.CodecID.LC3 in supported_codecs

    @override
    async def async_teardown_test(self) -> None:
        await super().async_teardown_test()
        self.dut.bt.audioStop()
        # Reset audio attributes.
        self.dut.bt.setAudioAttributes(None, False)

    def _setup_headset_device(
        self,
        hfp_configuration: hfp.HfConfiguration,
        a2dp_codecs: list[a2dp_ext.A2dpCodec],
    ) -> None:
        """Setup HFP and A2DP servicer on the REF device."""
        for ref in self.refs:
            hfp_ext.HfProtocol.setup_server(
                ref.device,
                sdp_handle=_HFP_SDP_HANDLE,
                configuration=hfp_configuration,
            )
            a2dp_ext.setup_sink_server(
                ref.device,
                [codec.get_default_capabilities() for codec in a2dp_codecs],
                _A2DP_SERVICE_RECORD_HANDLE,
            )
            a2dp_ext.setup_avrcp_server(
                ref.device,
                avrcp_controller_handle=_AVRCP_CONTROLLER_RECORD_HANDLE,
                avrcp_target_handle=_AVRCP_TARGET_RECORD_HANDLE,
            )

    async def test_pair_and_connect(self) -> None:
        """Tests HFP connection establishment right after a pairing session.

    Test steps:
      1. Setup HFP and A2DP on REF.
      2. Create bond from DUT.
      3. Wait HFP and A2DP connected on DUT.
      (Android should autoconnect HFP as AG)
    """
        self.ref = self.refs[0]
        with (
                self.dut.bl4a.register_callback(_Module.A2DP) as dut_cb_a2dp,
                self.dut.bl4a.register_callback(_Module.HFP_AG) as dut_cb_hfp,
        ):
            self._setup_headset_device(
                hfp_configuration=_DEFAULT_HF_CONFIGURATION,
                a2dp_codecs=[a2dp_ext.A2dpCodec.SBC],
            )
            self.logger.info("[DUT] Connect and pair REF.")
            await self.classic_connect_and_pair()
            self.logger.info("[DUT] Wait for A2DP connected.")
            await dut_cb_a2dp.wait_for_event(
                bl4a_api.ProfileConnectionStateChanged(
                    address=self.ref.address,
                    state=android_constants.ConnectionState.CONNECTED,
                ),)
            self.logger.info("[DUT] Wait for HFP connected.")
            await dut_cb_hfp.wait_for_event(bl4a_api.ProfileActiveDeviceChanged(self.ref.address))

    @navi_test_base.named_parameterized(
        cvsd=dict(supported_audio_codecs=[_AudioCodec.CVSD],),
        cvsd_and_msbc=dict(supported_audio_codecs=[_AudioCodec.CVSD, _AudioCodec.MSBC],),
        cvsd_msbc_and_lc3_swb=dict(supported_audio_codecs=[
            _AudioCodec.LC3_SWB,
            _AudioCodec.CVSD,
            _AudioCodec.MSBC,
        ],),
        handle_audio_focus=dict(
            supported_audio_codecs=[
                _AudioCodec.LC3_SWB,
                _AudioCodec.CVSD,
                _AudioCodec.MSBC,
            ],
            handle_audio_focus=True,
        ),
    )
    async def test_call_during_a2dp_playback(
        self,
        supported_audio_codecs: list[hfp.AudioCodec],
        handle_audio_focus: bool = False,
    ) -> None:
        """Tests making an outgoing phone call, observing SCO connection status.

    Test steps:
      1. Setup HFP and A2DP connection.
      2. Play sine and check A2DP is playing.
      3. Place an outgoing call.
      4. Check A2DP is stopped.
      5. Verify SCO connected.
      6. Terminate the call.
      7. Verify SCO disconnected.
      8. Verify A2DP resumed.

    Args:
      supported_audio_codecs: Audio codecs supported by REF device.
      handle_audio_focus: Whether to enable audio focus handling.
    """
        self.ref = self.refs[0]
        if (_AudioCodec.LC3_SWB in supported_audio_codecs and not self.ref_supports_lc3):
            self.skipTest("LC3 not supported on REF.")

        # Enable audio focus handling.
        self.dut.bt.setAudioAttributes(None, handle_audio_focus)

        # [REF] Setup HFP.
        hfp_configuration = hfp.HfConfiguration(
            supported_hf_features=[hfp.HfFeature.CODEC_NEGOTIATION],
            supported_hf_indicators=[],
            supported_audio_codecs=supported_audio_codecs,
        )
        self._setup_headset_device(
            hfp_configuration=hfp_configuration,
            a2dp_codecs=[a2dp_ext.A2dpCodec.SBC],
        )

        dut_hfp_cb = self.dut.bl4a.register_callback(_Module.HFP_AG)
        dut_a2dp_cb = self.dut.bl4a.register_callback(_Module.A2DP)
        self.test_case_context.push(dut_hfp_cb)
        self.test_case_context.push(dut_a2dp_cb)

        self.logger.info("[DUT] Connect and pair REF.")
        await self.classic_connect_and_pair()

        self.logger.info("[DUT] Wait for A2DP connected.")
        await dut_a2dp_cb.wait_for_event(
            bl4a_api.ProfileConnectionStateChanged(
                address=self.ref.address,
                state=android_constants.ConnectionState.CONNECTED,
            ),)
        self.logger.info("[DUT] Wait for HFP connected.")
        await dut_hfp_cb.wait_for_event(bl4a_api.ProfileActiveDeviceChanged(self.ref.address))

        self.logger.info("[DUT] Start stream.")
        self.dut.bt.audioSetRepeat(android_constants.RepeatMode.ALL)
        self.dut.bt.audioPlaySine()

        self.logger.info("[DUT] Check A2DP is playing.")
        await dut_a2dp_cb.wait_for_event(
            bl4a_api.A2dpPlayingStateChanged(address=self.ref.address,
                                             state=android_constants.A2dpState.PLAYING),)

        sco_links = asyncio.Queue[device.ScoLink]()
        self.ref.device.on(self.ref.device.EVENT_SCO_CONNECTION, sco_links.put_nowait)

        dut_player_cb = self.dut.bl4a.register_callback(_Module.PLAYER)
        self.test_case_context.push(dut_player_cb)

        self.logger.info("[DUT] Add call.")
        call = self.dut.bl4a.make_phone_call(
            _CALLER_NAME,
            _CALLER_NUMBER,
            constants.Direction.OUTGOING,
        )
        with call:
            self.logger.info("[DUT] Check A2DP is not playing.")
            await dut_a2dp_cb.wait_for_event(
                bl4a_api.A2dpPlayingStateChanged(
                    address=self.ref.address,
                    state=android_constants.A2dpState.NOT_PLAYING,
                ),)
            self.logger.info("[DUT] Wait for SCO connected.")
            await dut_hfp_cb.wait_for_event(
                _HfpAgAudioStateChange(address=self.ref.address, state=_ScoState.CONNECTED),)
            if handle_audio_focus:
                self.logger.info("[DUT] Wait for player paused.")
                await dut_player_cb.wait_for_event(
                    bl4a_api.PlayerIsPlayingChanged(is_playing=False),)

            async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
                self.logger.info("[REF] Wait for SCO connected.")
                sco_link = await sco_links.get()

            sco_disconnected = asyncio.Event()
            sco_link.once(sco_link.EVENT_DISCONNECTION, lambda *_: sco_disconnected.set())

            self.logger.info("[DUT] Terminate call.")
            call.close()

        self.logger.info("[DUT] Wait for SCO disconnected.")
        await dut_hfp_cb.wait_for_event(
            _HfpAgAudioStateChange(address=self.ref.address, state=_ScoState.DISCONNECTED),)
        self.logger.info("[REF] Wait for SCO disconnected.")
        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            await sco_disconnected.wait()

        self.logger.info("[DUT] Wait for A2DP resume.")
        await dut_a2dp_cb.wait_for_event(
            bl4a_api.A2dpPlayingStateChanged(address=self.ref.address,
                                             state=android_constants.A2dpState.PLAYING),)
        if handle_audio_focus:
            self.logger.info("[DUT] Wait for player resumed.")
            await dut_player_cb.wait_for_event(bl4a_api.PlayerIsPlayingChanged(is_playing=True),)

    async def test_multidevice_hf_switch(self) -> None:
        """Tests DUT switch active hfp devices.

    Test steps:
      1. Setup two HFP HF devices.
      2. DUT pair with REF0.
      3. DUT pair with REF1.
      4. DUT make outgoing call.
      5. DUT answer the call.
      6. DUT switch active device to REF0.
      7. DUT switch active device to REF1.
    """
        if self.dut.bt.maxConnectedAudioDevices() < 2:
            self.skipTest("[DUT] Multi-device HF is not supported.")

        with self.dut.bl4a.register_callback(_Module.HFP_AG) as dut_hfp_cb:
            for i, ref in enumerate(self.refs):
                self.logger.info("[REF-%d] Setup HFP HF", i)
                hfp_ext.HfProtocol.setup_server(
                    ref.device,
                    sdp_handle=_HFP_SDP_HANDLE,
                    configuration=_DEFAULT_HF_CONFIGURATION,
                )

                # Disable CTKD to stop the DUT from connecting to REF on LE transport.
                # Otherwise, the wait time for le connection on ref-0 will block the sdp
                # on ref-1.
                self.logger.info("[REF-%d] Disable CTKD.", i)
                ref.device.l2cap_channel_manager.deregister_fixed_channel(smp.SMP_BR_CID)

                await self.classic_connect_and_pair(ref)

                self.logger.info("[DUT] Wait for HFP connected to REF-%d", i)
                await dut_hfp_cb.wait_for_event(
                    bl4a_api.ProfileActiveDeviceChanged(address=ref.address),)

        with (
                self.dut.bl4a.register_callback(bl4a_api.Module.TELECOM) as dut_telecom_cb,
                self.dut.bl4a.make_phone_call(
                    _CALLER_NAME,
                    _CALLER_NUMBER,
                    constants.Direction.OUTGOING,
                ) as call,
        ):
            self.logger.info("[DUT] Wait for call dialing.")
            await dut_telecom_cb.wait_for_event(
                bl4a_api.CallStateChanged(
                    handle=mock.ANY,
                    name=mock.ANY,
                    state=android_constants.CallState.DIALING,
                ),)

            self.logger.info("[DUT] Answer call.")
            call.answer()

            self.logger.info("[DUT] Wait for call active.")
            await dut_telecom_cb.wait_for_event(
                bl4a_api.CallStateChanged(
                    handle=mock.ANY,
                    name=mock.ANY,
                    state=android_constants.CallState.ACTIVE,
                ),)

            self.logger.info("[DUT] Start streaming.")
            self.dut.bt.audioSetRepeat(android_constants.RepeatMode.ONE)
            await asyncio.to_thread(self.dut.bt.audioPlaySine)

            # The default route should be REF1.
            for i, ref in enumerate(self.refs):
                with self.dut.bl4a.register_callback(_Module.HFP_AG) as dut_hfp_cb:
                    self.assertNotEqual(
                        self.dut.bt.hfpAgGetAudioState(ref.address),
                        _ScoState.CONNECTED,
                        f"SCO is already connected to REF{i}.",
                    )

                    self.logger.info("[DUT] Switch to REF-%d", i)
                    self.dut.bt.setActiveDevice(
                        ref.address,
                        android_constants.ActiveDeviceUse.PHONE_CALL,
                    )

                    self.logger.info("[DUT] Wait for HFP connected to REF-%d", i)
                    await dut_hfp_cb.wait_for_event(bl4a_api.ProfileActiveDeviceChanged(ref.address)
                                                   )

                    self.logger.info("[DUT] Wait for SCO connected to REF-%d", i)
                    await dut_hfp_cb.wait_for_event(event=_HfpAgAudioStateChange(
                        address=ref.address, state=_ScoState.CONNECTED),)

            self.logger.info("[DUT] Terminate call.")
            call.close()

    async def test_multidevice_a2dp_switch(self) -> None:
        """Tests DUT switch active a2dp devices.

    Test steps:
      1. Setup two A2DP devices.
      2. DUT pair with REF0.
      3. DUT pair with REF1.
      4. DUT switch active device to REF0.
      5. DUT switch active device to REF1.
    """
        if self.dut.bt.maxConnectedAudioDevices() < 2:
            self.skipTest("[DUT] Multi-device A2DP is not supported.")

        with self.dut.bl4a.register_callback(_Module.A2DP) as dut_a2dp_cb:
            for i, ref in enumerate(self.refs):
                self.logger.info("[REF-%d] Setup A2DP", i)
                a2dp_ext.setup_sink_server(
                    ref.device,
                    [a2dp_ext.A2dpCodec.SBC.get_default_capabilities()],
                    _A2DP_SERVICE_RECORD_HANDLE,
                )

                # Disable CTKD to stop the DUT from connecting to REF on LE transport.
                # Otherwise, the wait time for le connection on ref-0 will block the sdp
                # on ref-1.
                self.logger.info("[REF-%d] Disable CTKD.", i)
                ref.device.l2cap_channel_manager.deregister_fixed_channel(smp.SMP_BR_CID)

                await self.classic_connect_and_pair(ref)

                self.logger.info("[DUT] Wait for A2DP connected to REF-%d", i)
                await dut_a2dp_cb.wait_for_event(
                    bl4a_api.ProfileActiveDeviceChanged(address=ref.address),)

        with self.dut.bl4a.register_callback(_Module.A2DP) as dut_a2dp_cb:
            self.logger.info("[DUT] Start playing music.")
            self.dut.bt.audioSetRepeat(android_constants.RepeatMode.ONE)
            await asyncio.to_thread(self.dut.bt.audioPlaySine)

            # The default route should be REF-1.
            for i, ref in enumerate(self.refs):
                self.assertFalse(
                    self.dut.bt.isA2dpPlaying(ref.address),
                    f"A2DP is already playing on REF{i}.",
                )

                self.logger.info("[DUT] Switch to REF-%d", i)
                self.dut.bt.setActiveDevice(
                    ref.address,
                    android_constants.ActiveDeviceUse.AUDIO,
                )

                self.logger.info("[DUT] Wait for A2DP connected to REF-%d", i)
                await dut_a2dp_cb.wait_for_event(bl4a_api.ProfileActiveDeviceChanged(ref.address))

                if not self.dut.bt.isA2dpPlaying(ref.address):
                    self.logger.info("[DUT] Wait for A2DP playing on REF-%d.", i)
                    await dut_a2dp_cb.wait_for_event(
                        bl4a_api.A2dpPlayingStateChanged(ref.address,
                                                         android_constants.A2dpState.PLAYING),)

    async def test_multipoint_ringtone(self) -> None:
        """Tests phone call, ringtone is played on both REF-HF and DUT.

    Test steps:
      1. Setup HFP HF on REF-HF.
      2. Setup HFP AG on REF-AG.
      3. Connect and pair DUT to REF-HF.
      4. Connect and pair DUT to REF-AG.
      5. Make a phone call from REF-AG.
    """
        if self.dut.getprop(android_constants.Property.HFP_HF_ENABLED) != "true":
            self.skipTest("DUT does not have HFP HF enabled.")

        if self.dut.getprop(android_constants.Property.HFP_AG_ENABLED) != "true":
            self.skipTest("DUT does not have HFP AG enabled.")

        ref_hf_protocol_queue = hfp_ext.HfProtocol.setup_server(
            self.refs[0].device,
            sdp_handle=_HFP_SDP_HANDLE,
            configuration=_DEFAULT_HF_CONFIGURATION,
        )

        self.ref_ag_protocols = asyncio.Queue[hfp.AgProtocol]()

        def on_dlc(dlc: rfcomm.DLC):
            self.ref_ag_protocols.put_nowait(hfp.AgProtocol(dlc, _DEFAULT_AG_CONFIGURATION))

        self.refs[1].device.sdp_service_records = {
            _HFP_SDP_HANDLE:
                hfp.make_ag_sdp_records(
                    service_record_handle=_HFP_SDP_HANDLE,
                    rfcomm_channel=rfcomm.Server(self.refs[1].device).listen(on_dlc),
                    configuration=_DEFAULT_AG_CONFIGURATION,
                )
        }

        dut_ag_cb = self.dut.bl4a.register_callback(_Module.HFP_AG)
        dut_hf_cb = self.dut.bl4a.register_callback(_Module.HFP_HF)
        dut_telecom_cb = self.dut.bl4a.register_callback(_Module.TELECOM)
        self.test_case_context.push(dut_ag_cb)
        self.test_case_context.push(dut_hf_cb)
        self.test_case_context.push(dut_telecom_cb)

        await self.classic_connect_and_pair(self.refs[0])

        self.logger.info("[DUT] Wait for HFP AG connected on REF-HF.")
        await dut_ag_cb.wait_for_event(
            bl4a_api.ProfileConnectionStateChanged(
                address=self.refs[0].address,
                state=android_constants.ConnectionState.CONNECTED,
            ),)

        self.logger.info("[REF-HF] Wait for HF protocol connected.")
        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            ref_hf_protocol = await ref_hf_protocol_queue.get()

        ref_hf_ring_event = asyncio.Event()
        ref_hf_protocol.on(hfp.HfProtocol.EVENT_RING, ref_hf_ring_event.set)

        await self.classic_connect_and_pair(self.refs[1])

        self.logger.info("[DUT] Wait for HFP HF connected on REF-AG.")
        await dut_hf_cb.wait_for_event(
            bl4a_api.ProfileConnectionStateChanged(
                address=self.refs[1].address,
                state=android_constants.ConnectionState.CONNECTED,
            ),)

        self.logger.info("[REF-AG] Wait for AG protocol connected.")
        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            ref_ag_protocol = await self.ref_ag_protocols.get()

        self.logger.info("[REF-AG] Update call state.")
        call_info = hfp.CallInfo(
            index=1,
            direction=hfp.CallInfoDirection.MOBILE_TERMINATED_CALL,
            status=hfp.CallInfoStatus.INCOMING,
            mode=hfp.CallInfoMode.VOICE,
            multi_party=hfp.CallInfoMultiParty.NOT_IN_CONFERENCE,
            number="+1234567890",
        )
        ref_ag_protocol.calls.append(call_info)
        ref_ag_protocol.update_ag_indicator(
            hfp.AgIndicator.CALL_SETUP,
            hfp.CallSetupAgIndicator.INCOMING_CALL_PROCESS,
        )

        self.logger.info("[DUT] Wait for call ringing.")
        await dut_telecom_cb.wait_for_event(
            bl4a_api.CallStateChanged(
                handle=mock.ANY,
                name=mock.ANY,
                state=_CallState.RINGING,
            ))

        async with self.assert_not_timeout(
                _DEFAULT_STEP_TIMEOUT_SECONDS,
                msg="[REF-HF] Wait for ringtone.",
        ):
            await ref_hf_ring_event.wait()

    async def test_multipoint_call(self) -> None:
        """Tests phone call, SCO connection is only connected to REF-AG.

    Test steps:
      1. Setup HFP HF on REF-HF.
      2. Setup HFP AG on REF-AG.
      3. Connect and pair DUT to REF-HF.
      4. Connect and pair DUT to REF-AG.
      5. Make a phone call from REF-AG.
      6. Answer the call on DUT.
      7. Wait for SCO connected only on REF-AG.
    """
        await self.test_multipoint_ringtone()

        sco_link_hf = asyncio.Queue[device.ScoLink]()
        self.refs[0].device.on(self.refs[0].device.EVENT_SCO_CONNECTION, sco_link_hf.put_nowait)

        self.logger.info("[DUT] Answer call.")
        self.dut.shell("input keyevent KEYCODE_CALL")

        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            self.logger.info("[REF-HF] Wait for SCO connected.")
            await sco_link_hf.get()

        self.logger.info("[REF-AG] Check SCO is not connected.")
        self.assertEmpty(self.refs[1].device.sco_links)


if __name__ == "__main__":
    test_runner.main()
