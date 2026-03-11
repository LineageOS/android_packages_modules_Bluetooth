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

import asyncio
import contextlib
import enum
import itertools
from unittest import mock
import uuid

from bumble import a2dp
from bumble import core
from bumble import device
from bumble import gatt
from bumble import hci
from bumble import pairing
from mobly import test_runner

from navi.tests import navi_test_base
from navi.utils import android_constants
from navi.utils import bl4a_api
from navi.utils import constants
from navi.utils import pairing as pairing_utils
from navi.utils import pyee_extensions

_DEFAULT_STEP_TIMEOUT_SECONDS = 10.0
_DEFAULT_ACL_DISCONNECTION_TIMEOUT_SECONDS = 60.0
_DEFAUILT_ADVERTISING_PARAMETERS = device.AdvertisingParameters(
    own_address_type=hci.OwnAddressType.RANDOM,
    primary_advertising_interval_min=20,
    primary_advertising_interval_max=20,
)


class TestVariant(enum.Enum):
    ACCEPT = "accept"
    REJECTED = "rejected"
    DISCONNECTED = "disconnected"
    NOT_RESPONDED = "not_responded"


_Role = hci.Role
_IoCapability = pairing.PairingDelegate.IoCapability


class AutonomousRepairingTest(navi_test_base.TwoDevicesTestBase):
    """Test Bluetooth Autonomous Repairing."""

    async def _wait_for_repairing_success(
        self,
        ref_address: str,
        adapter_cb: bl4a_api.CallbackHandler,
    ) -> None:
        """Waits for bonding events."""
        self.logger.info("[DUT] Wait for bond state change to none.")
        await adapter_cb.wait_for_event(
            bl4a_api.BondStateChanged(
                address=ref_address,
                state=android_constants.BondState.NONE,
            ))

        self.logger.info("[DUT] Wait for bond state change to bonding.")
        await adapter_cb.wait_for_event(
            bl4a_api.BondStateChanged(
                address=ref_address,
                state=android_constants.BondState.BONDING,
            ))

        self.logger.info("[DUT] Wait for encryption changed.")
        await adapter_cb.wait_for_event(bl4a_api.EncryptionChanged(address=ref_address))

        self.logger.info("[DUT] Wait for bond state change to bonded.")
        await adapter_cb.wait_for_event(
            bl4a_api.BondStateChanged(
                address=ref_address,
                state=android_constants.BondState.BONDED,
            ))

        if not self.ref.device.keystore:
            self.fail("[REF] Keystore is not initialized.")

        self.assertIsNotNone(await self.ref.device.keystore.get(f"{self.dut.address}/P"))

    async def _wait_for_repairing_fail(
        self,
        ref_address: str,
        adapter_cb: bl4a_api.CallbackHandler,
        transport: android_constants.Transport,
    ) -> None:
        """Waits for repairing fail events."""
        self.logger.info("[DUT] Wait for ACL disconnection.")
        await adapter_cb.wait_for_event(
            bl4a_api.AclDisconnected(
                address=ref_address,
                transport=transport,
            ),
            timeout=_DEFAULT_ACL_DISCONNECTION_TIMEOUT_SECONDS,
        )

        self.logger.info("[DUT] Wait for key missing.")
        await adapter_cb.wait_for_event(bl4a_api.KeyMissing(address=ref_address,))

        if not self.ref.device.keystore:
            self.fail("[REF] Keystore is not initialized.")

        self.assertIsNone(await self.ref.device.keystore.get(f"{self.dut.address}/P"))

    # TODO: Remove this skip once the bug is fixed.
    @navi_test_base.TwoDevicesTestBase.require_flag(
        "com.android.bluetooth.flags.autonomous_repairing_initiation",
        "android.bluetooth.platform.flags.autonomous_repairing_initiation",
    )
    @navi_test_base.parameterized(*itertools.product(
        [
            TestVariant.ACCEPT,
            TestVariant.REJECTED,
            TestVariant.DISCONNECTED,
            TestVariant.NOT_RESPONDED,
        ],
        [constants.Direction.OUTGOING, constants.Direction.INCOMING],
    ))
    async def test_repairing_classic(
        self,
        variant: TestVariant,
        pairing_direction: constants.Direction,
    ) -> None:
        """Tests re-pairing when the remote device loses the bond over BR/EDR.

    Test steps:
      1. Bond DUT and REF over BR/EDR.
      2. Disconnect from DUT.
      3. Remove the bond on REF.
      4. Initiate connection depending on pairing_direction.
      5. Verify DUT detects bond loss and initiates re-pairing.
      6. Verify DUT bond with REF.
      7. Accept or reject pairing requests on REF.
      8. [If accepted] Verify REF has the key for DUT.
      9. Verify DUT has the key for REF.

    Args:
      variant: Whether to accept or reject the pairing request on REF.
      pairing_direction: The direction of the pairing request.
    """
        self.logger.info("[REF] Setup A2DP record.")
        self.ref.device.sdp_service_records = {
            1: a2dp.make_audio_sink_service_sdp_records(1),
        }

        await self.classic_connect_and_pair()

        await self.disconnect_with_check(self.ref.address, android_constants.Transport.CLASSIC)

        pairing_delegate = pairing_utils.PairingDelegate(
            io_capability=_IoCapability.DISPLAY_OUTPUT_AND_YES_NO_INPUT,
            auto_accept=True,
        )

        def pairing_config_factory(_: device.Connection,) -> pairing.PairingConfig:
            return pairing.PairingConfig(
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

        self.logger.info("[REF] Clear resolving list in the controller.")
        await self.ref.device.send_command(hci.HCI_LE_Clear_Resolving_List_Command())

        self.assertIsNone(await self.ref.device.keystore.get(f"{self.dut.address}/P"))

        adapter_cb = self.dut.bl4a.register_callback(bl4a_api.Module.ADAPTER)
        self.test_case_context.push(adapter_cb)

        auth_task: asyncio.tasks.Task | None = None
        ref_dut_acl: device.Connection | None

        if pairing_direction == constants.Direction.OUTGOING:
            self.logger.info("[DUT] Initiate ACL connection from DUT.")
            self.dut.bt.connect(self.ref.address)
        else:
            self.logger.info("[REF] Connect to DUT.")
            ref_dut_acl = await self.ref.device.connect(
                f"{self.dut.address}/P",
                transport=core.BT_BR_EDR_TRANSPORT,
                timeout=_DEFAULT_STEP_TIMEOUT_SECONDS,
            )

            self.logger.info("[REF] Create bond.")
            auth_task = asyncio.tasks.create_task(ref_dut_acl.authenticate())

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

        ref_accept = variant == TestVariant.ACCEPT

        match variant:
            case TestVariant.ACCEPT:
                self.logger.info("[REF] Accept pairing request.")
                pairing_delegate.pairing_answers.put_nowait(True)

            case TestVariant.REJECTED:
                self.logger.info("[REF] Reject pairing request.")
                pairing_delegate.pairing_answers.put_nowait(False)

            case TestVariant.NOT_RESPONDED:
                self.logger.info("[REF] No response.")

            case TestVariant.DISCONNECTED:
                ref_dut_acl = self.ref.device.find_connection_by_bd_addr(
                    hci.Address(self.dut.address),
                    transport=core.PhysicalTransport.BR_EDR,
                )
                if not ref_dut_acl:
                    self.fail("[REF] No ACL connection found.")

                self.logger.info("[REF] Disconnect from DUT.")
                await ref_dut_acl.disconnect()

        if ref_accept:
            await self._wait_for_repairing_success(
                ref_address=self.ref.address,
                adapter_cb=adapter_cb,
            )
        else:
            await self._wait_for_repairing_fail(
                ref_address=self.ref.address,
                adapter_cb=adapter_cb,
                transport=android_constants.Transport.CLASSIC,
            )

        self.assertIn(self.ref.address, self.dut.bt.getBondedDevices())

        if auth_task:
            self.logger.info("[REF] Wait for authentication complete.")
            expected_errors = ([] if variant == TestVariant.ACCEPT else
                               [hci.HCI_Error, asyncio.CancelledError])
            with contextlib.suppress(*expected_errors):
                async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
                    await auth_task

    # # TODO: Remove this skip once the bug is fixed.
    @navi_test_base.TwoDevicesTestBase.require_flag(
        "com.android.bluetooth.flags.autonomous_repairing_initiation",
        "android.bluetooth.platform.flags.autonomous_repairing_initiation",
    )
    @navi_test_base.parameterized(*itertools.product(
        [
            TestVariant.ACCEPT,
            TestVariant.REJECTED,
            TestVariant.DISCONNECTED,
            TestVariant.NOT_RESPONDED,
        ],
        [constants.Direction.OUTGOING, constants.Direction.INCOMING],
    ))
    async def test_repairing_le(
        self,
        variant: TestVariant,
        pairing_direction: constants.Direction,
    ) -> None:
        """Tests re-pairing when the remote device loses the bond over LE.

    Test steps:
      1. Bond DUT and REF over LE.
      2. Disconnect from DUT.
      3. Remove the bond on REF.
      4. Initiate connection depending on pairing_direction.
      5. Verify DUT detects bond loss and initiates re-pairing.
      6. Verify DUT bond with REF.
      7. Accept or reject pairing requests on REF.
      8. [If accepted] Verify REF has the key for DUT.
      9. Verify DUT has the key for REF.

    Args:
      variant: Whether to accept or reject the pairing request on REF.
      pairing_direction: The direction of the pairing request.
    """
        service_uuid = str(uuid.uuid4())

        self.logger.info("[REF] Add GATT service with UUID: %s", service_uuid)
        self.ref.device.add_service(gatt.Service(uuid=service_uuid, characteristics=[]))

        await self.le_connect_and_pair(ref_address_type=hci.OwnAddressType.RANDOM,)

        await self.disconnect_with_check(self.ref.random_address, android_constants.Transport.LE)

        pairing_delegate = pairing_utils.PairingDelegate(
            io_capability=_IoCapability.DISPLAY_OUTPUT_AND_YES_NO_INPUT,
            auto_accept=True,
        )

        def pairing_config_factory(_: device.Connection,) -> pairing.PairingConfig:
            return pairing.PairingConfig(
                identity_address_type=pairing.PairingConfig.AddressType.RANDOM,
                delegate=pairing_delegate,
            )

        self.logger.info("[REF] Set pairing config factory.")
        self.ref.device.pairing_config_factory = pairing_config_factory

        if not self.ref.device.keystore:
            self.fail("[REF] Keystore is not initialized.")

        self.assertIsNotNone(await self.ref.device.keystore.get(f"{self.dut.address}/P"))

        self.logger.info("[REF] Delete all keys.")
        await self.ref.device.keystore.delete_all()

        self.logger.info("[REF] Clear resolving list in the controller.")
        await self.ref.device.send_command(hci.HCI_LE_Clear_Resolving_List_Command())

        self.assertIsNone(await self.ref.device.keystore.get(f"{self.dut.address}/P"))

        adapter_cb = self.dut.bl4a.register_callback(bl4a_api.Module.ADAPTER)
        self.test_case_context.push(adapter_cb)

        pair_task: asyncio.tasks.Task | None = None

        if pairing_direction == constants.Direction.OUTGOING:
            self.logger.info("[REF] Start advertising")
            await self.ref.device.create_advertising_set(
                advertising_parameters=_DEFAUILT_ADVERTISING_PARAMETERS,)

            self.logger.info("[DUT] Initiate ACL connection from DUT.")
            gatt_client = await self.dut.bl4a.connect_gatt_client(
                address=self.ref.random_address,
                transport=android_constants.Transport.LE,
            )
            self.test_case_context.push(gatt_client)
        else:
            self.logger.info("[DUT] Start advertising.")
            advertise = await self.dut.bl4a.start_legacy_advertiser(
                settings=bl4a_api.LegacyAdvertiseSettings(
                    own_address_type=android_constants.AddressTypeStatus.RANDOM),
                advertising_data=bl4a_api.AdvertisingData(service_uuids=[service_uuid]),
            )

            self.logger.info("[REF] Scan for DUT.")
            scan_result = asyncio.get_running_loop().create_future()
            with advertise, pyee_extensions.EventWatcher() as watcher:

                def on_advertising_report(adv: device.Advertisement) -> None:
                    if service_uuids := adv.data.get(
                            core.AdvertisingData.Type.COMPLETE_LIST_OF_128_BIT_SERVICE_CLASS_UUIDS):
                        if service_uuid in service_uuids and not scan_result.done():
                            scan_result.set_result(adv.address)

                watcher.on(self.ref.device, "advertisement", on_advertising_report)

                self.logger.info("[REF] Start scanning.")
                await self.ref.device.start_scanning()

                self.logger.info("[REF] Wait for advertising report from DUT.")
                async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
                    dut_addr = await scan_result

                self.logger.info("[REF] Stop scanning.")
                await self.ref.device.stop_scanning()

                ref_dut_acl: device.Connection | None
                self.logger.info("[REF] Connect to DUT.")
                ref_dut_acl = await self.ref.device.connect(
                    dut_addr,
                    transport=core.BT_LE_TRANSPORT,
                    own_address_type=hci.OwnAddressType.RANDOM,
                    timeout=_DEFAULT_STEP_TIMEOUT_SECONDS,
                )

                self.logger.info("[REF] Get remote LE features.")
                async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
                    await ref_dut_acl.get_remote_le_features()

                self.logger.info("[REF] Pair.")
                pair_task = asyncio.create_task(ref_dut_acl.pair())

        self.logger.info("[DUT] Wait for connection.")
        await adapter_cb.wait_for_event(event=bl4a_api.AclConnected(
            address=self.ref.random_address,
            transport=android_constants.Transport.LE,
        ),)

        self.logger.info("[DUT] Wait for pairing request.")
        await adapter_cb.wait_for_event(
            bl4a_api.PairingRequest(address=self.ref.random_address, variant=mock.ANY,
                                    pin=mock.ANY))

        self.logger.info("[DUT] Get bonded devices.")
        self.assertIn(self.ref.random_address, self.dut.bt.getBondedDevices())

        self.logger.info("[REF] Wait for pairing request.")
        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            await pairing_delegate.pairing_events.get()

        self.logger.info("[DUT] Accept pairing request.")
        self.dut.bt.setPairingConfirmation(self.ref.random_address, True)

        ref_accept = variant == TestVariant.ACCEPT

        match variant:
            case TestVariant.ACCEPT:
                self.logger.info("[REF] Accept pairing request.")
                pairing_delegate.pairing_answers.put_nowait(True)

            case TestVariant.REJECTED:
                self.logger.info("[REF] Reject pairing request.")
                pairing_delegate.pairing_answers.put_nowait(False)

            case TestVariant.NOT_RESPONDED:
                self.logger.info("[REF] No response.")

            case TestVariant.DISCONNECTED:
                ref_dut_acl = self.ref.device.find_connection_by_bd_addr(
                    hci.Address(self.dut.address),
                    transport=core.PhysicalTransport.LE,
                )
                if not ref_dut_acl:
                    self.fail("[REF] No ACL connection found.")

                self.logger.info("[REF] Disconnect from DUT.")
                await ref_dut_acl.disconnect()

        if ref_accept:
            await self._wait_for_repairing_success(
                ref_address=self.ref.random_address,
                adapter_cb=adapter_cb,
            )
        else:
            await self._wait_for_repairing_fail(
                ref_address=self.ref.random_address,
                adapter_cb=adapter_cb,
                transport=android_constants.Transport.LE,
            )

        self.assertIn(self.ref.random_address, self.dut.bt.getBondedDevices())

        if pair_task:
            self.logger.info("[REF] Wait pairing complete.")
            if variant == TestVariant.ACCEPT:
                await pair_task
            else:
                with self.assertRaises((core.ProtocolError, asyncio.CancelledError)):
                    await pair_task


if __name__ == "__main__":
    test_runner.main()
