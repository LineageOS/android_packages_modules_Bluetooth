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
"""Tests Bluetooth Autonomous Repairing."""

from __future__ import annotations

from unittest import mock

from bumble import a2dp
from bumble import device
from bumble import hci
from bumble import pairing
from mobly import test_runner

from navi.tests import navi_test_base
from navi.utils import android_constants
from navi.utils import bl4a_api
from navi.utils import pairing as pairing_utils

_DEFAULT_STEP_TIMEOUT_SECONDS = 10.0

_Role = hci.Role
_IoCapability = pairing.PairingDelegate.IoCapability


class AutonomousRepairingTest(navi_test_base.TwoDevicesTestBase):
    """Test Bluetooth Autonomous Repairing."""

    # TODO: Remove this skip once the bug is fixed.
    @navi_test_base.TwoDevicesTestBase.require_flag(
        "com.android.bluetooth.flags.autonomous_repairing_initiation",
        "android.bluetooth.platform.flags.autonomous_repairing_initiation",
    )
    async def test_pairing_with_ref_bond_removed(self) -> None:
        """Tests re-pairing behavior when the remote device loses the bond.

    Test steps:
      1. Bond DUT and REF over BR/EDR.
      2. Disconnect from DUT.
      3. Remove the bond on REF.
      4. Initiate connection from DUT.
      5. Verify DUT detects bond loss and initiates re-pairing.
      6. Verify DUT bond with REF.
      7. Accept pairing requests on both DUT and REF.
      8. Verify DUT bond with REF.
    """
        self.logger.info("[REF] Setup A2DP record.")
        self.ref.device.sdp_service_records = {
            1: a2dp.make_audio_sink_service_sdp_records(1),
        }

        with self.dut.bl4a.register_callback(bl4a_api.Module.ADAPTER) as adapter_cb:
            self.logger.info("[DUT] Pair DUT and REF.")
            await self.classic_connect_and_pair()

            self.logger.info("[REF] Disconnect from DUT.")
            self.dut.bt.disconnect(self.ref.address)

            self.logger.info("[DUT] Wait for ACL disconnection.")
            await adapter_cb.wait_for_event(event=bl4a_api.AclDisconnected(
                address=self.ref.address,
                transport=android_constants.Transport.CLASSIC,
            ),)

        pairing_delegate = pairing_utils.PairingDelegate(
            io_capability=_IoCapability.DISPLAY_OUTPUT_AND_YES_NO_INPUT,
            auto_accept=True,
        )

        def pairing_config_factory(_: device.Connection,) -> pairing.PairingConfig:
            return pairing.PairingConfig(
                sc=True,
                mitm=True,
                bonding=True,
                identity_address_type=pairing.PairingConfig.AddressType.PUBLIC,
                delegate=pairing_delegate,
            )

        self.logger.info("[REF] Set pairing config factory.")
        self.ref.device.pairing_config_factory = pairing_config_factory

        if not self.ref.device.keystore:
            self.fail("[REF] Keystore is not initialized.")

        self.assertIsNotNone(await self.ref.device.keystore.get(f"{self.dut.address}/P"))

        self.logger.info("[REF] Delete all keys.")
        await self.ref.device.keystore.delete_all()

        self.assertIsNone(await self.ref.device.keystore.get(f"{self.dut.address}/P"))

        with self.dut.bl4a.register_callback(bl4a_api.Module.ADAPTER) as adapter_cb:
            self.logger.info("[DUT] Initiate connection from DUT.")
            self.dut.bt.connect(self.ref.address)

            self.logger.info("[DUT] Wait for connection.")
            await adapter_cb.wait_for_event(event=bl4a_api.AclConnected(
                address=self.ref.address,
                transport=android_constants.Transport.CLASSIC,
            ),)

            self.logger.info("[DUT] Wait for pairing request.")
            await adapter_cb.wait_for_event(
                bl4a_api.PairingRequest(address=self.ref.address, variant=mock.ANY, pin=mock.ANY))

            self.logger.info("[DUT] Get bonded devices.")
            self.assertIn(self.ref.address, self.dut.bt.getBondedDevices())

            self.logger.info("[REF] Wait for pairing request.")
            async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
                await pairing_delegate.pairing_events.get()

            self.logger.info("[DUT] Accept pairing request.")
            self.dut.bt.setPairingConfirmation(self.ref.address, True)

            self.logger.info("[REF] Accept pairing request.")
            pairing_delegate.pairing_answers.put_nowait(True)

            self.logger.info("[DUT] Wait for bond state change to none.")
            await adapter_cb.wait_for_event(
                bl4a_api.BondStateChanged(
                    address=self.ref.address,
                    state=android_constants.BondState.NONE,
                ))

            self.logger.info("[DUT] Wait for bond state change to bonding.")
            await adapter_cb.wait_for_event(
                bl4a_api.BondStateChanged(
                    address=self.ref.address,
                    state=android_constants.BondState.BONDING,
                ))

            self.logger.info("[DUT] Wait for encryption changed.")
            await adapter_cb.wait_for_event(bl4a_api.EncryptionChanged(address=self.ref.address))

            self.logger.info("[DUT] Wait for bond state change to bonded.")
            await adapter_cb.wait_for_event(
                bl4a_api.BondStateChanged(
                    address=self.ref.address,
                    state=android_constants.BondState.BONDED,
                ))

            self.assertIn(self.ref.address, self.dut.bt.getBondedDevices())

            self.assertIsNotNone(await self.ref.device.keystore.get(f"{self.dut.address}/P"))


if __name__ == "__main__":
    test_runner.main()
