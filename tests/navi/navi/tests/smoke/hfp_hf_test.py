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
"""Tests related to Bluetooth HFP(Hands-Free Profile) HF role on Pixel."""

import asyncio
from unittest import mock

from bumble import core
from bumble import device as bumble_device
from bumble import hci
from bumble import hfp
from bumble import rfcomm
from mobly import test_runner
from mobly import signals
from typing_extensions import override

from navi.bumble_ext import hfp as hfp_ext
from navi.tests import navi_test_base
from navi.utils import android_constants
from navi.utils import bl4a_api
from navi.utils import constants

_HfpState = android_constants.ConnectionState
_CallState = android_constants.CallState
_Callback = bl4a_api.CallbackHandler
_DEFAULT_STEP_TIMEOUT_SECONDS = 5.0
_HFP_AG_SDP_HANDLE = 1
_PROPERTY_SWB_SUPPORTED = "bluetooth.hfp.swb.supported"
_PROPERTY_HF_CLIENT_FEATURES = "bluetooth.hfp.hf_client_features.config"
_MIN_HFP_VOLUME = 1
_MAX_HFP_VOLUME = 15
_STREAM_TYPE_CALL = android_constants.StreamType.CALL


class HfpHfTest(navi_test_base.TwoDevicesTestBase):
    ag_protocol: hfp.AgProtocol | None = None
    ref_hfp_protocols: asyncio.Queue[hfp.AgProtocol]

    @override
    async def async_setup_class(self) -> None:
        await super().async_setup_class()

        if self.dut.device.is_emulator:
            self.setprop_for_class_context(android_constants.Property.HFP_HF_ENABLED, "true")

            self.setprop_for_class_context(_PROPERTY_HF_CLIENT_FEATURES, "0x1b5")

        if self.dut.getprop(android_constants.Property.HFP_HF_ENABLED) != "true":
            raise signals.TestAbortClass("DUT does not have HFP HF enabled.")

    @override
    async def async_setup_test(self) -> None:
        await super().async_setup_test()
        self.ref_hfp_protocols = asyncio.Queue[hfp.AgProtocol]()

    async def _terminate_connection_from_ref(self) -> None:
        if not (dut_ref_acl := self.ref.device.find_connection_by_bd_addr(
                hci.Address(self.dut.address))):
            return

        self.logger.info("[REF] Terminate connection.")
        with self.dut.bl4a.register_callback(bl4a_api.Module.ADAPTER) as dut_cb:
            await dut_ref_acl.disconnect()
            await dut_cb.wait_for_event(
                bl4a_api.AclDisconnected(
                    address=self.ref.address,
                    transport=android_constants.Transport.CLASSIC,
                ),)

    async def _wait_for_hfp_state(self, dut_cb: _Callback, state: _HfpState) -> None:
        self.logger.info("[DUT] Wait for HFP state %s.", state)
        await dut_cb.wait_for_event(
            bl4a_api.ProfileConnectionStateChanged(
                address=self.ref.address,
                state=state,
            ),)

    def _setup_ag_device(self, configuration: hfp.AgConfiguration) -> None:

        def on_dlc(dlc: rfcomm.DLC):
            self.ref_hfp_protocols.put_nowait(hfp.AgProtocol(dlc, configuration))

        self.ref.device.sdp_service_records = {
            _HFP_AG_SDP_HANDLE: (hfp_ext.AudioGatewaySdpRecord(
                service_record_handle=_HFP_AG_SDP_HANDLE,
                rfcomm_channel=rfcomm.Server(self.ref.device).listen(on_dlc),
                version=hfp.ProfileVersion.V1_8,
                supported_features=hfp_ext.make_ag_sdp_features(configuration),
            ).to_service_attributes())
        }

    async def _connect_hfp_from_ref(self, config: hfp.AgConfiguration) -> hfp.AgProtocol:
        if not (dut_ref_acl := self.ref.device.find_connection_by_bd_addr(
                hci.Address(self.dut.address))):
            self.logger.info("[REF] Connect.")
            dut_ref_acl = await self.ref.device.connect(
                self.dut.address,
                core.BT_BR_EDR_TRANSPORT,
                timeout=_DEFAULT_STEP_TIMEOUT_SECONDS,
            )

            self.logger.info("[REF] Authenticate and encrypt connection.")
            await dut_ref_acl.authenticate()
            await dut_ref_acl.encrypt()

        sdp_records = await hfp_ext.HandsfreeSdpRecord.find(dut_ref_acl)
        if not sdp_records:
            self.fail("DUT does not have HFP SDP record.")
        rfcomm_channel = sdp_records[0].rfcomm_channel

        self.logger.info("[REF] Found HFP RFCOMM channel %s.", rfcomm_channel)

        self.logger.info("[REF] Open RFCOMM Channel.")
        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            multiplexer = await rfcomm.Client(dut_ref_acl).start()
            dlc = await multiplexer.open_dlc(rfcomm_channel)
        return hfp.AgProtocol(dlc, config)

    async def test_pair_and_connect(self) -> None:
        """Tests HFP connection establishment right after a pairing session.

    Test steps:
      1. Setup HFP on REF.
      2. Create bond from DUT.
      3. Wait HFP connected on DUT.(Android should autoconnect HFP as HF)
    """
        self._setup_ag_device(hfp_ext.make_ag_configuration())

        self.logger.info("[DUT] Connect and pair REF.")
        with self.dut.bl4a.register_callback(bl4a_api.Module.HFP_HF) as dut_cb:
            await self.classic_connect_and_pair(connect_profiles=True)

            self.logger.info("[DUT] Wait for HFP connected.")
            await self._wait_for_hfp_state(dut_cb, _HfpState.CONNECTED)

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
        await self.test_pair_and_connect()
        await self._terminate_connection_from_ref()

        with self.dut.bl4a.register_callback(bl4a_api.Module.HFP_HF) as dut_cb:

            self.logger.info("[DUT] Reconnect.")
            self.dut.bt.connect(self.ref.address)
            await self._wait_for_hfp_state(dut_cb, _HfpState.CONNECTED)

            self.logger.info("[DUT] Disconnect.")
            self.dut.bt.disconnect(self.ref.address)
            await self._wait_for_hfp_state(dut_cb, _HfpState.DISCONNECTED)

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
        await self.test_pair_and_connect()
        await self._terminate_connection_from_ref()

        with self.dut.bl4a.register_callback(bl4a_api.Module.HFP_HF) as dut_cb:
            await self._connect_hfp_from_ref(hfp_ext.make_ag_configuration())

            self.logger.info("[DUT] Wait for HFP connected.")
            await self._wait_for_hfp_state(dut_cb, _HfpState.CONNECTED)

            await self._terminate_connection_from_ref()
            await self._wait_for_hfp_state(dut_cb, _HfpState.DISCONNECTED)

    @navi_test_base.parameterized(
        hfp.AudioCodec.CVSD,
        hfp.AudioCodec.MSBC,
        hfp.AudioCodec.LC3_SWB,
    )
    async def test_sco_connection_with_codec_negotiation(self, codec: hfp.AudioCodec) -> None:
        """Tests SCO connection establishment.

    Test steps:
      1. Setup pairing between DUT and REF.
      2. Make SCO connection from AG(REF).
      3. Terminate SCO connection from AG(REF).

    Args:
      codec: Codec used in the SCO connection.
    """

        self._setup_ag_device(
            hfp_ext.make_ag_configuration(
                supported_audio_codecs=[
                    hfp.AudioCodec.CVSD,
                    hfp.AudioCodec.MSBC,
                    hfp.AudioCodec.LC3_SWB,
                ],
                supported_ag_features=[
                    hfp.AgFeature.ENHANCED_CALL_STATUS,
                    hfp.AgFeature.CODEC_NEGOTIATION,
                ],
            ))

        match codec:
            case hfp.AudioCodec.CVSD:
                esco_parameters = hfp.ESCO_PARAMETERS[hfp.DefaultCodecParameters.ESCO_CVSD_S4]
            case hfp.AudioCodec.MSBC:
                esco_parameters = hfp.ESCO_PARAMETERS[hfp.DefaultCodecParameters.ESCO_MSBC_T2]
            case hfp.AudioCodec.LC3_SWB:
                if self.dut.getprop(_PROPERTY_SWB_SUPPORTED) != "true":
                    raise signals.TestSkip("SWB is not supported on the device.")
                esco_parameters = hfp_ext.ESCO_PARAMETERS_LC3_T2
            case _:
                self.fail(f"Unsupported codec: {codec}")

        with (self.dut.bl4a.register_callback(bl4a_api.Module.HFP_HF) as dut_hfp_cb,):
            await self.classic_connect_and_pair(connect_profiles=True)

            self.logger.info("[DUT] Wait for HFP connected.")
            await self._wait_for_hfp_state(dut_hfp_cb, _HfpState.CONNECTED)

            async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
                ref_hfp_protocol = await self.ref_hfp_protocols.get()

            # WearService may disable the audio route.
            self.dut.bt.hfpHfSetAudioRouteAllowed(self.ref.address, True)

            self.logger.info("[REF] Negotiate codec.")
            async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
                await ref_hfp_protocol.negotiate_codec(codec)

            self.logger.info("[REF] Create SCO.")
            connection = ref_hfp_protocol.dlc.multiplexer.l2cap_channel.connection
            sco_links = asyncio.Queue[bumble_device.ScoLink]()
            self.ref.device.on(self.ref.device.EVENT_SCO_CONNECTION, sco_links.put_nowait)
            await self.ref.device.send_command(
                hci.HCI_Enhanced_Setup_Synchronous_Connection_Command(
                    connection_handle=connection.handle, **esco_parameters.asdict()))

            self.logger.info("[DUT] Wait for SCO connected.")
            await dut_hfp_cb.wait_for_event(
                bl4a_api.HfpHfAudioStateChanged(address=self.ref.address,
                                                state=_HfpState.CONNECTED),)

            self.logger.info("[REF] Terminate SCO.")
            async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
                sco_link = await sco_links.get()
                await sco_link.disconnect()

            self.logger.info("[DUT] Wait for SCO disconnected.")
            await dut_hfp_cb.wait_for_event(
                bl4a_api.HfpHfAudioStateChanged(address=self.ref.address,
                                                state=_HfpState.DISCONNECTED),)

    @navi_test_base.parameterized(
        constants.TestRole.DUT,
        constants.TestRole.REF,
    )
    async def test_set_volume(self, issuer: constants.TestRole) -> None:
        """Tests setting speaker volume over HFP.

    Test steps:
      1. Setup HFP connection between DUT and REF.
      2. Set volume from DUT or REF.
      3. Check the volume on the other side.

    Args:
      issuer: Device which requests the volume.
    """
        self._setup_ag_device(hfp_ext.make_ag_configuration())

        max_system_call_volume = self.dut.bt.getMaxVolume(_STREAM_TYPE_CALL)
        min_system_call_volume = self.dut.bt.getMinVolume(_STREAM_TYPE_CALL)

        def system_call_volume_to_hfp_volume(system_call_volume: int) -> int:
            return ((system_call_volume - min_system_call_volume) *
                    (_MAX_HFP_VOLUME - _MIN_HFP_VOLUME) //
                    (max_system_call_volume - min_system_call_volume)) + _MIN_HFP_VOLUME

        expect_dut_volume = max(
            min_system_call_volume,
            (self.dut.bt.getVolume(_STREAM_TYPE_CALL) + 1) % (max_system_call_volume + 1),
        )
        expect_hfp_volume = system_call_volume_to_hfp_volume(expect_dut_volume)

        with (
                self.dut.bl4a.register_callback(bl4a_api.Module.HFP_HF) as dut_hfp_cb,
                self.dut.bl4a.register_callback(bl4a_api.Module.AUDIO) as dut_audio_cb,
        ):
            await self.classic_connect_and_pair(connect_profiles=True)

            ref_volumes = asyncio.Queue[int]()
            self.logger.info("[REF] Wait for HFP connected.")
            async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
                ref_hfp_protocol = await self.ref_hfp_protocols.get()
            ref_hfp_protocol.on(ref_hfp_protocol.EVENT_SPEAKER_VOLUME, ref_volumes.put_nowait)

            self.logger.info("[DUT] Wait for HFP connected.")
            await self._wait_for_hfp_state(dut_hfp_cb, _HfpState.CONNECTED)

            self.logger.info("[REF] Wait for initial volume.")
            async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
                await ref_volumes.get()

            if issuer == constants.TestRole.DUT:
                self.logger.info("[DUT] Set volume to %d.", expect_dut_volume)
                self.dut.bt.setVolume(_STREAM_TYPE_CALL, expect_dut_volume)

                self.logger.info("[REF] Wait for volume changed.")
                async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
                    actual_hfp_volume = await ref_volumes.get()
                    self.assertEqual(actual_hfp_volume, expect_hfp_volume)
            else:
                self.logger.info("[REF] Set volume to %d.", expect_hfp_volume)
                ref_hfp_protocol.set_speaker_volume(expect_hfp_volume)

            self.logger.info("[DUT] Wait for volume changed.")
            await dut_audio_cb.wait_for_event(
                bl4a_api.VolumeChanged(stream_type=_STREAM_TYPE_CALL,
                                       volume_value=expect_dut_volume))

    async def test_update_battery_level(self) -> None:
        """Tests updating battery level indicator from HF.

    Test steps:
      1. Setup HFP connection between DUT and REF.
      2. Update battery indicator from HF.
      3. Check the battery indicator from AG.
    """
        configuration = hfp_ext.make_ag_configuration(
            supported_ag_features=[
                hfp.AgFeature.HF_INDICATORS,
                hfp.AgFeature.ENHANCED_CALL_STATUS,
            ],
            supported_hf_indicators=[
                hfp.HfIndicator.BATTERY_LEVEL,
            ],
        )
        self._setup_ag_device(configuration)
        # Mock battery level to avoid unexpected changing during the test.
        initial_battery_level = 50
        self.dut.shell(f"dumpsys battery set level {initial_battery_level}")

        with self.dut.bl4a.register_callback(bl4a_api.Module.HFP_HF) as dut_cb:
            await self.classic_connect_and_pair(connect_profiles=True)

            self.logger.info("[REF] Wait for HFP connected.")
            async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
                ref_hfp_protocol = await self.ref_hfp_protocols.get()

            hf_indicators = asyncio.Queue[hfp.HfIndicatorState]()
            ref_hfp_protocol.on(ref_hfp_protocol.EVENT_HF_INDICATOR, hf_indicators.put_nowait)

            self.logger.info("[DUT] Wait for HFP connected.")
            await self._wait_for_hfp_state(dut_cb, _HfpState.CONNECTED)

            self.logger.info("[REF] Wait for initial battery level.")
            async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
                hf_indicator = await hf_indicators.get()
                # The battery initial battery level cannot be mocked, so we don't check
                # the value here.
                self.assertEqual(hf_indicator.indicator, hfp.HfIndicator.BATTERY_LEVEL)

            # Set battery level.
            expected_battery_level = 100
            self.dut.shell(f"dumpsys battery set level {expected_battery_level}")

            self.logger.info("[REF] Wait for updated battery level.")
            async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
                hf_indicator = await hf_indicators.get()
                self.assertEqual(hf_indicator.indicator, hfp.HfIndicator.BATTERY_LEVEL)
                self.assertEqual(hf_indicator.current_status, expected_battery_level)

    @navi_test_base.named_parameterized(
        accept=True,
        reject=False,
    )
    async def test_answer_call_from_hf(self, accepted: bool) -> None:
        """Tests answering call from HF.

    Test steps:
      1. Setup HFP connection between DUT and REF.
      2. Answer call from HF.
      3. Check the call state from AG.

    Args:
      accepted: Whether the call is accepted or rejected.
    """
        configuration = hfp_ext.make_ag_configuration(
            supported_ag_features=[
                hfp.AgFeature.ENHANCED_CALL_STATUS,
                hfp.AgFeature.CODEC_NEGOTIATION,
            ],
            supported_ag_indicators=[
                hfp.AgIndicatorState.call(),
                hfp.AgIndicatorState.callsetup(),
            ],
        )
        self._setup_ag_device(configuration)

        hf_cb = self.dut.bl4a.register_callback(bl4a_api.Module.HFP_HF)
        telecom_cb = self.dut.bl4a.register_callback(bl4a_api.Module.TELECOM)
        self.test_case_context.push(hf_cb)
        self.test_case_context.push(telecom_cb)

        await self.classic_connect_and_pair(connect_profiles=True)

        self.logger.info("[REF] Wait for HFP connected.")
        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            ref_hfp_protocol = await self.ref_hfp_protocols.get()

        self.logger.info("[DUT] Wait for HFP connected.")
        await self._wait_for_hfp_state(hf_cb, _HfpState.CONNECTED)

        self.logger.info("[REF] Update call state.")
        call_info = hfp.CallInfo(
            index=1,
            direction=hfp.CallInfoDirection.MOBILE_TERMINATED_CALL,
            status=hfp.CallInfoStatus.INCOMING,
            mode=hfp.CallInfoMode.VOICE,
            multi_party=hfp.CallInfoMultiParty.NOT_IN_CONFERENCE,
            number="+1234567890",
        )
        ref_hfp_protocol.calls.append(call_info)
        ref_hfp_protocol.update_ag_indicator(
            hfp.AgIndicator.CALL_SETUP,
            hfp.CallSetupAgIndicator.INCOMING_CALL_PROCESS,
        )

        self.logger.info("[DUT] Wait for call ringing.")
        await telecom_cb.wait_for_event(
            bl4a_api.CallStateChanged(
                handle=mock.ANY,
                name=mock.ANY,
                state=_CallState.RINGING,
            ))
        if accepted:
            answered = asyncio.Event()
            ref_hfp_protocol.once(ref_hfp_protocol.EVENT_ANSWER, answered.set)
            self.logger.info("[DUT] Answer call.")
            self.dut.shell("input keyevent KEYCODE_CALL")
            self.logger.info("[REF] Wait for call answered.")
            async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
                await answered.wait()
        else:
            rejected = asyncio.Event()
            ref_hfp_protocol.once(ref_hfp_protocol.EVENT_HANG_UP, rejected.set)
            self.logger.info("[DUT] Reject call.")
            self.dut.shell("input keyevent KEYCODE_ENDCALL")
            self.logger.info("[REF] Wait for call rejected.")
            async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
                await rejected.wait()

        self.logger.info("[REF] Update call state.")
        ref_hfp_protocol.update_ag_indicator(
            hfp.AgIndicator.CALL_SETUP,
            hfp.CallSetupAgIndicator.NOT_IN_CALL_SETUP,
        )
        if accepted:
            call_info.status = hfp.CallInfoStatus.ACTIVE
            ref_hfp_protocol.update_ag_indicator(hfp.AgIndicator.CALL, 1)
        else:
            ref_hfp_protocol.calls.clear()

        self.logger.info("[DUT] Wait for call state changed.")
        await telecom_cb.wait_for_event(
            bl4a_api.CallStateChanged(
                handle=mock.ANY,
                name=mock.ANY,
                state=(_CallState.ACTIVE if accepted else _CallState.DISCONNECTED),
            ))


if __name__ == "__main__":
    test_runner.main()
