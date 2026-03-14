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

from bumble import core
from bumble import device as bumble_device
from bumble import hci
from bumble import hfp
from bumble import rfcomm
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

_AudioCodec = hfp.AudioCodec
_Module = bl4a_api.Module


class NoReplyCodecNegotiationHfProtocol(hfp_ext.HfProtocol):
    """A custom HFP HF protocol that does not reply to codec negotiation."""

    @override
    async def setup_codec_connection(self, codec_id: int) -> None:
        # Do nothing, to simulate a timeout
        pass


class HfpAgVentiTest(navi_test_base.TwoDevicesTestBase):

    @override
    async def async_teardown_test(self) -> None:
        self.dut.bt.audioStop()
        # Make sure Bumble is off to cancel any running tasks.
        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            await self.ref.close()
        await super().async_teardown_test()

    async def _pair_and_connect_with_hfp_server_on_ref(self) -> None:
        """Setup HFP connection establishment right after a pairing session."""
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

    async def _find_or_connect_acl_from_ref(self, dut_address: str) -> bumble_device.Connection:
        if not (dut_ref_acl := self.ref.device.find_connection_by_bd_addr(
                hci.Address(dut_address))):
            dut_ref_acl = await self.ref.device.connect(
                dut_address,
                core.BT_BR_EDR_TRANSPORT,
                timeout=_DEFAULT_STEP_TIMEOUT_SECONDS,
            )
            self.logger.info("[REF] Authenticate and encrypt connection.")
            await dut_ref_acl.authenticate()
            await dut_ref_acl.encrypt()
        return dut_ref_acl

    async def _connect_hfp_from_dut(self, ref_address: str) -> None:
        """Initiates connection(HFP) from DUT to REF."""
        self.logger.info("[DUT] Initiating HFP connection.")
        self.dut.bt.connect(ref_address)

    async def _connect_hfp_from_ref(self, dut_ref_acl: bumble_device.Connection) -> None:
        """Initiates HFP connection from REF to DUT."""
        self.logger.info("[REF] Initiating HFP connection.")
        # If ACL connection is not established, establish it.
        if dut_ref_acl is None:
            dut_ref_acl = await self._find_or_connect_acl_from_ref(self.dut.address)

        rfcomm_channel = await rfcomm.find_rfcomm_channel_with_uuid(
            dut_ref_acl, core.BT_HANDSFREE_AUDIO_GATEWAY_SERVICE)
        if rfcomm_channel is None:
            self.fail("No HFP RFCOMM channel found on REF.")
        self.logger.info("[REF] Found HFP RFCOMM channel %s.", rfcomm_channel)

        self.logger.info("[REF] Open RFCOMM Multiplexer.")
        multiplexer = await rfcomm.Client(dut_ref_acl).start()

        self.logger.info("[REF] Open RFCOMM DLC.")
        dlc = await multiplexer.open_dlc(rfcomm_channel)

        self.logger.info("[REF] Establish SLC.")
        ref_hfp_protocol = hfp_ext.HfProtocol(dlc, hfp_ext.make_hf_configuration())
        await ref_hfp_protocol.initiate_slc()

    async def test_paired_connect_hfp_ag_simultaneous(self) -> None:
        """Tests HFP connection establishment with simultaneous connection.

    Test steps:
      1. Setup pairing between DUT and REF.
      2. Terminate ACL connection.
      3. Setup ACL connection from REF.
      4. Trigger HFP connection from DUT and REF at same time.
      5. Wait HFP connected on DUT.
      6. Disconnect from DUT.
      7. Wait HFP disconnected on DUT.

    Test Result:
      AOSP has the ability to handle HFP Connection Collision. Even in
      conflicting scenarios, HFP must eventually connect successfully.
    """
        with self.dut.bl4a.register_callback(_Module.HFP_AG) as dut_cb:
            # Step 1: Setup pairing and initial hfp connection
            async with self.assert_not_timeout(
                    _DEFAULT_STEP_TIMEOUT_SECONDS,
                    msg="[DUT] Setup pairing and initial HFP connection.",
            ):
                await self._pair_and_connect_with_hfp_server_on_ref()

            # Step 2: Terminate ACL connection
            await self.disconnect_with_check(self.ref.address, android_constants.Transport.CLASSIC)

            # Step 3: Setup ACL connection from REF
            async with self.assert_not_timeout(
                _DEFAULT_STEP_TIMEOUT_SECONDS,
                msg="[REF] Find or connect ACL connection from DUT.",
            ):
                dut_ref_acl = await self._find_or_connect_acl_from_ref(self.dut.address)

            # Step 4: Trigger connection from DUT and REF at same time
            self.logger.info("[DUT & REF] Triggering simultaneous HFP connection.")

            # Use asyncio.gather to run both connection attempts concurrently
            try:
                await asyncio.wait_for(
                    asyncio.gather(
                        self._connect_hfp_from_dut(self.ref.address),
                        self._connect_hfp_from_ref(dut_ref_acl),
                    ),
                    timeout=_DEFAULT_STEP_TIMEOUT_SECONDS,
                )
            # Raise error from REF side for HFP connection collision.
            # This is to avoid the test case being ignored due to the DUT side
            # will check HFP connection successfully.
            except (core.BaseBumbleError, TimeoutError):
                self.logger.warning(
                    "[REF & DUT] Simultaneous HFP connection exception.",
                    stack_info=True,
                )

            # Step 5: Wait for HFP to be connected on DUT
            self.logger.info("[DUT] Wait for HFP connected.")
            await dut_cb.wait_for_event(
                bl4a_api.ProfileActiveDeviceChanged(address=self.ref.address),
                timeout=_DEFAULT_STEP_TIMEOUT_SECONDS,
            )

            # Step 6: Disconnect from DUT
            self.logger.info("[DUT] Disconnect.")
            self.dut.bt.disconnect(self.ref.address)

            # Step 7: Wait for HFP to be disconnected on DUT
            self.logger.info("[DUT] Wait for HFP disconnected.")
            await dut_cb.wait_for_event(
                bl4a_api.ProfileActiveDeviceChanged(address=None),
                timeout=_DEFAULT_STEP_TIMEOUT_SECONDS,
            )

    async def _pair_and_connect_with_custom_hfp_server_on_ref(
        self,
        hfp_protocol_class: type[hfp_ext.HfProtocol],
    ) -> None:
        """Setup HFP connection with a custom HF protocol."""
        with (self.dut.bl4a.register_callback(_Module.HFP_AG) as dut_cb,):
            hfp_protocol_class.setup_server(
                self.ref.device,
                sdp_handle=_HFP_SDP_HANDLE,
                configuration=hfp_ext.make_hf_configuration(
                    supported_hf_features=[hfp.HfFeature.CODEC_NEGOTIATION],
                    supported_audio_codecs=[
                        _AudioCodec.CVSD,
                        _AudioCodec.MSBC,
                        _AudioCodec.LC3_SWB,
                    ],
                ),
            )

            self.logger.info("[DUT] Connect and pair REF with %s.", hfp_protocol_class.__name__)
            await self.classic_connect_and_pair()

            self.logger.info("[DUT] Wait for HFP connected.")
            await dut_cb.wait_for_event(
                bl4a_api.ProfileActiveDeviceChanged(address=self.ref.address),
                timeout=_DEFAULT_STEP_TIMEOUT_SECONDS,
            )

    async def test_sco_codec_negotiation_timeout(self) -> None:
        """Tests DUT handling of SCO codec negotiation timeout.

    Test steps:
      1. DUT and REF pair and connect HFP.
      2. DUT initiates an outgoing call, starting SCO connection.
      3. REF (custom HFP HF) does not reply to codec negotiation.
      4. DUT experiences codec negotiation timeout.
      5. Verify communication device falls back to non BT SCO device.

    Test Result:
      DUT should gracefully handle the SCO codec negotiation timeout.
    """

        # Step 1: DUT and REF pair and connect HFP.
        self.logger.info("[DUT] Setup pairing and initial HFP connection with no reply HF.")
        await self._pair_and_connect_with_custom_hfp_server_on_ref(NoReplyCodecNegotiationHfProtocol
                                                                  )

        # Step 2: DUT initiates an outgoing call, starting SCO connection.
        self.logger.info("[DUT] Initiating outgoing call to trigger SCO.")

        with self.dut.bl4a.register_callback(bl4a_api.Module.HFP_AG) as dut_hfp_ag_cb:
            with self.dut.bl4a.make_phone_call(
                    caller_name=_CALLER_NAME,
                    caller_number=_CALLER_NUMBER,
                    direction=constants.Direction.OUTGOING,
            ):
                # Step 3: REF (custom HFP HF) does not reply to codec negotiation.
                # Step 4: DUT experiences codec negotiation timeout.

                self.logger.info("[DUT] Start streaming.")
                self.dut.bt.audioSetRepeat(android_constants.RepeatMode.ONE)
                await asyncio.to_thread(self.dut.bt.audioPlaySine)

                self.logger.info("[DUT] Waiting for SCO negotiation to timeout.")
                async with self.assert_timeout(
                        delay=5.0,
                        msg="SCO audio should not become active due to timeout.",
                ):
                    await dut_hfp_ag_cb.wait_for_event(
                        bl4a_api.HfpAgAudioStateChanged(
                            address=self.ref.address,
                            state=android_constants.ScoState.CONNECTED,
                        ))
                self.logger.info("[DUT] SCO codec negotiation timeout, as expected.")

                # Step 5: Verify communication device falls back to non BT SCO device.
                # After SCO codec negotiation timeout, Bluetooth notifies Audio of the
                # SCO connection failure, prompting Audio to switch the communication
                # device from SCO to the default device.
                self.logger.info("[DUT] Check communication device fallback to non BT SCO device.")
                info_dict = self.dut.bt.getCommunicationDevice()
                if not info_dict:
                    self.fail("Communication device is empty.")
                device = bl4a_api.AudioDeviceInfo.from_mapping(info_dict)
                self.logger.info("[DUT] Current communication device: %s", device)
                self.assertNotEqual(
                    device.device_type,
                    android_constants.AudioDeviceType.BLUETOOTH_SCO,
                    "Communication device should not the Bluetooth SCO.",
                )
