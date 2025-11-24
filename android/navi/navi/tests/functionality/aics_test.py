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

import secrets

from bumble import hci
from bumble.profiles import aics
from bumble.profiles import cap
from bumble.profiles import csip
from bumble.profiles import vcs
from mobly import signals
from typing_extensions import override

from navi.bumble_ext import aics as aics_ext
from navi.tests import navi_test_base
from navi.utils import android_constants
from navi.utils import bl4a_api

_Property = android_constants.Property
_TIMEOUT = 10.0


class AicsTest(navi_test_base.TwoDevicesTestBase):
    """Tests LE Audio Input Control Service."""

    @override
    async def async_setup_class(self) -> None:
        await super().async_setup_class()
        if self.dut.device.is_emulator:
            # Force enable VCP controller and CSIP coordinator on the emulator.
            self.dut.shell(['setprop', _Property.VCP_CONTROLLER_ENABLED, 'true'])
            self.dut.shell(['setprop', _Property.CSIP_SET_COORDINATOR_ENABLED, 'true'])
        if self.dut.getprop(_Property.VCP_CONTROLLER_ENABLED) != 'true':
            raise signals.TestAbortClass('VCP Controller is not enabled on DUT.')

    async def _setup_writable_aics_and_connect(self,) -> bl4a_api.AudioInputControl:
        """Sets up VCS with one writable AICS and connects."""
        aics_service = aics_ext.AudioInputControlService()

        volume_control_service = vcs.VolumeControlService(included_services=[aics_service])
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
        with self.dut.bl4a.register_callback(bl4a_api.Module.VOLUME_CONTROL) as aics_cb:
            self.logger.info('[DUT] Setting VCP connection policy...')
            await self.le_connect_and_pair(hci.OwnAddressType.RANDOM, self.ref)
            self.dut.bt.vcpSetConnectionPolicy(self.ref.random_address,
                                               android_constants.ConnectionPolicy.ALLOWED)
            self.logger.info('[DUT] Waiting for VCP connection...')
            await aics_cb.wait_for_event(
                bl4a_api.ProfileConnectionStateChanged(
                    address=self.ref.random_address,
                    state=android_constants.ConnectionState.CONNECTED,
                ))

        # TODO: Must set gain mode via GATT to avoid
        # IllegalArgumentException. Investigate if this requirement is a bug.
        gatt_client = await self.dut.bl4a.connect_gatt_client(
            self.ref.random_address, transport=android_constants.Transport.LE)
        await gatt_client.discover_services()
        await gatt_client.get_services()

        aics_cb = self.dut.bl4a.get_aics(self.ref.random_address, 0)

        async with self.assert_not_timeout(_TIMEOUT):
            await aics_cb.set_gain_mode(aics.GainMode.MANUAL)
        self.test_case_context.enter_context(aics_cb)
        return aics_cb

    async def test_get_default_aics_properties(self) -> None:
        """Tests default AICS properties."""
        aics_cb = await self._setup_writable_aics_and_connect()

        async with self.assert_not_timeout(_TIMEOUT):
            self.assertEqual(await aics_cb.get_description(), 'Bluetooth')
            self.assertEqual(await aics_cb.get_gain_setting_unit(), 1)
            self.assertEqual(await aics_cb.get_gain_setting_min(), 0)
            self.assertEqual(await aics_cb.get_gain_setting_max(), 127)
            self.assertEqual(
                await aics_cb.get_audio_input_status(),
                aics.AudioInputStatus.ACTIVE,
            )
            self.assertEqual(await aics_cb.get_gain_setting(), 0)
            self.assertEqual(await aics_cb.get_mute(), aics.Mute.NOT_MUTED)
            self.assertEqual(
                await aics_cb.get_gain_mode(),
                aics.GainMode.MANUAL,
            )

    async def test_aics_set_description(self) -> None:
        """Tests that AICS description can be set."""
        aics_cb = await self._setup_writable_aics_and_connect()
        async with self.assert_not_timeout(_TIMEOUT):
            is_writable = await aics_cb.is_description_writable()
            self.logger.info('is_writable: %s', is_writable)
            self.assertTrue(is_writable)

            self.assertTrue(await aics_cb.set_description('New Description'))
            await aics_cb.wait_for_event(
                bl4a_api.AicsDescriptionChanged(description='New Description'))
            self.assertEqual(await aics_cb.get_description(), 'New Description')

    async def test_aics_set_gain_setting(self) -> None:
        """Tests that AICS gain setting can be set."""
        aics_cb = await self._setup_writable_aics_and_connect()

        async with self.assert_not_timeout(_TIMEOUT):
            self.assertTrue(await aics_cb.set_gain_setting(100))
            await aics_cb.wait_for_event(bl4a_api.AicsGainSettingChanged(gain_setting=100))
            self.assertEqual(await aics_cb.get_gain_setting(), 100)

    async def test_aics_set_mute(self) -> None:
        """Tests that AICS mute state can be set."""
        aics_cb = await self._setup_writable_aics_and_connect()

        async with self.assert_not_timeout(_TIMEOUT):
            self.assertTrue(await aics_cb.set_mute(aics.Mute.MUTED))
            await aics_cb.wait_for_event(bl4a_api.AicsMuteChanged(mute=aics.Mute.MUTED))
            self.assertEqual(await aics_cb.get_mute(), aics.Mute.MUTED)

    async def test_aics_set_gain_mode(self) -> None:
        """Tests that AICS gain mode can be set."""
        aics_cb = await self._setup_writable_aics_and_connect()

        async with self.assert_not_timeout(_TIMEOUT):
            self.assertTrue(await aics_cb.set_gain_mode(aics.GainMode.AUTOMATIC))
            await aics_cb.wait_for_event(
                bl4a_api.AicsGainModeChanged(gain_mode=aics.GainMode.AUTOMATIC))
            self.assertEqual(
                await aics_cb.get_gain_mode(),
                aics.GainMode.AUTOMATIC,
            )
