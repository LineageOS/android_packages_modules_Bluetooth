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
import secrets

from bumble import hci
from bumble.profiles import bap
from bumble.profiles import cap
from bumble.profiles import csip
from bumble.profiles import vcs
from mobly import signals
from typing_extensions import override

from navi.bumble_ext import vocs
from navi.tests import navi_test_base
from navi.utils import android_constants
from navi.utils import bl4a_api

_TIMEOUT = 10.0
_Property = android_constants.Property


class VocsTest(navi_test_base.TwoDevicesTestBase):
    """Tests LE Audio Volume Offset Control Service."""

    @override
    async def async_setup_class(self) -> None:
        await super().async_setup_class()
        if self.dut.device.is_emulator:
            # Force enable VCP controller and CSIP coordinator on the emulator.
            self.dut.setprop(_Property.VCP_CONTROLLER_ENABLED, 'true')
            self.dut.setprop(_Property.CSIP_SET_COORDINATOR_ENABLED, 'true')
        if self.dut.getprop(_Property.VCP_CONTROLLER_ENABLED) != 'true':
            raise signals.TestAbortClass('VCP Controller is not enabled on DUT.')

    async def _setup_vocs_and_connect(self,) -> bl4a_api.CallbackHandler:
        """Sets up VCS with one VOCS and connects."""
        volume_control_service = vcs.VolumeControlService(included_services=[
            vocs.VolumeOffsetControlService(
                change_counter=0,
                volume_offset=0,
                audio_location=bap.AudioLocation.FRONT_LEFT,
            )
        ])
        sirk = secrets.token_bytes(csip.SET_IDENTITY_RESOLVING_KEY_LENGTH)
        self.ref.device.add_services([
            volume_control_service,
            cap.CommonAudioServiceService(
                csip.CoordinatedSetIdentificationService(
                    set_identity_resolving_key=sirk,
                    set_identity_resolving_key_type=csip.SirkType.PLAINTEXT,
                    coordinated_set_size=1,
                )),
        ])

        self.logger.info('[DUT] Create bond with REF')
        vcp_cb = self.dut.bl4a.register_callback(bl4a_api.Module.VOLUME_CONTROL)
        self.test_case_context.callback(vcp_cb.close)
        await self.le_connect_and_pair(hci.OwnAddressType.RANDOM, self.ref)
        self.logger.info('[DUT] Setting VCP connection policy...')
        self.dut.bt.vcpSetConnectionPolicy(self.ref.random_address,
                                           android_constants.ConnectionPolicy.ALLOWED)
        self.logger.info('[DUT] Waiting for VCP connection...')
        await vcp_cb.wait_for_event(
            bl4a_api.ProfileConnectionStateChanged(
                address=self.ref.random_address,
                state=android_constants.ConnectionState.CONNECTED,
            ))

        self.logger.info('[DUT] Getting VOCS...')

        self.logger.info('[DUT] Waiting for VOCS properties to be ready...')
        await vcp_cb.wait_for_event(
            bl4a_api.VocsOffsetStateChanged(
                address=self.ref.random_address,
                instance_id=1,
                offset=0,
            ))
        await vcp_cb.wait_for_event(
            bl4a_api.VocsAudioLocationChanged(
                address=self.ref.random_address,
                instance_id=1,
                audio_location=int(bap.AudioLocation.FRONT_LEFT),
            ))
        self.logger.info('[DUT] VOCS is ready.')
        return vcp_cb

    async def test_vocs_set_volume_offset(self) -> None:
        """Tests that VOCS volume offset can be set."""
        with await self._setup_vocs_and_connect() as vcp_cb:
            self.assertTrue(self.dut.bt.isVolumeOffsetAvailable(self.ref.random_address))
            self.assertEqual(
                self.dut.bt.getNumberofVocsInstances(self.ref.random_address),
                1,
            )

            async with self.assert_not_timeout(_TIMEOUT):
                await asyncio.to_thread(self.dut.bt.setVolumeOffset, self.ref.random_address, 1,
                                        100)
                self.logger.info('[DUT] Waiting for VOCS offset to be changed...')
                # we are getting the offset 256 times the value we pass
                await vcp_cb.wait_for_event(event=bl4a_api.VocsOffsetStateChanged(
                    address=self.ref.random_address,
                    instance_id=1,
                    offset=25600,
                ))

    async def test_vocs_set_device_volume(self) -> None:
        """Tests that VOCS device volume can be set."""
        with await self._setup_vocs_and_connect() as vcp_cb:
            self.assertTrue(self.dut.bt.isVolumeOffsetAvailable(self.ref.random_address))
            self.assertEqual(
                self.dut.bt.getNumberofVocsInstances(self.ref.random_address),
                1,
            )
            async with self.assert_not_timeout(_TIMEOUT):
                await asyncio.to_thread(self.dut.bt.vcpSetDeviceVolume, self.ref.random_address,
                                        100, True)
                await vcp_cb.wait_for_event(
                    bl4a_api.DeviceVolumeChanged(
                        address=self.ref.random_address,
                        volume=100,
                    ))
