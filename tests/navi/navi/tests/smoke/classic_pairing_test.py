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
import contextlib
import datetime
import enum
import itertools
from unittest import mock

from bumble import core
from bumble import device
from bumble import hci
from bumble import keys as bumble_keys
from bumble import l2cap
from bumble import pairing
from bumble import smp
from mobly import test_runner

from navi.tests import navi_test_base
from navi.utils import android_constants
from navi.utils import bl4a_api
from navi.utils import constants
from navi.utils import matcher
from navi.utils import pairing as pairing_utils
from navi.utils import pyee_extensions

_TERMINATED_BOND_STATES = (
    android_constants.BondState.BONDED,
    android_constants.BondState.NONE,
)
_DEFAULT_STEP_TIMEOUT = datetime.timedelta(seconds=10)
_DEFAULT_STEP_TIMEOUT_SECONDS = _DEFAULT_STEP_TIMEOUT.total_seconds()
_DEFAULT_PIN_CODE = "834701"


class TestVariant(enum.Enum):
    ACCEPT = "accept"
    REJECT = "reject"
    REJECTED = "rejected"
    DISCONNECTED = "disconnected"


_Direction = constants.Direction
_Role = hci.Role
_KeyDistribution = pairing.PairingDelegate.KeyDistribution
_IoCapability = pairing.PairingDelegate.IoCapability
_AndroidPairingVariant = android_constants.PairingVariant
_BumblePairingVariant = pairing_utils.PairingVariant


class ClassicPairingTest(navi_test_base.TwoDevicesTestBase):
    """Test Bluetooth Classic pairing."""

    pairing_delegate: pairing_utils.PairingDelegate

    async def _test_ssp_pairing_async(
        self,
        variant: TestVariant,
        pairing_direction: _Direction,
        ref_io_capability: _IoCapability,
        ref_role: _Role,
    ) -> None:
        """Tests Classic SSP pairing.

    Test steps:
      1. Setup configurations.
      2. Make ACL connections.
      3. Start pairing.
      4. Wait for pairing requests and verify pins.
      5. Make actions corresponding to variants.
      6. Verify final states.

    Args:
      variant: Action to perform in the pairing procedure.
      pairing_direction: Direction of pairing. DUT->REF is outgoing, and vice
        versa.
      ref_io_capability: IO Capability on the REF device.
      ref_role: HCI role on the REF device.
    """
        pairing_delegate = self.pairing_delegate

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

        self.logger.info("[REF] Allow role switch.")
        await self.ref.device.send_command(
            hci.HCI_Write_Default_Link_Policy_Settings_Command(default_link_policy_settings=0x01),
            check_result=True,
        )

        dut_cb = self.dut.bl4a.register_callback(bl4a_api.Module.ADAPTER)
        self.test_case_context.push(dut_cb)

        ref_dut_acl: device.Connection
        auth_task: asyncio.tasks.Task | None = None
        if pairing_direction == _Direction.OUTGOING:
            self.logger.info("[REF] Prepare to accept connection.")
            ref_accept_task = asyncio.tasks.create_task(
                self.ref.device.accept(
                    f"{self.dut.address}/P",
                    role=ref_role,
                    timeout=_DEFAULT_STEP_TIMEOUT_SECONDS,
                ))

            self.logger.info("[DUT] Create bond and connect implicitly.")
            self.dut.bt.createBond(self.ref.address, android_constants.Transport.CLASSIC)

            self.logger.info("[REF] Accept connection.")
            ref_dut_acl = await ref_accept_task
        else:
            self.logger.info("[REF] Connect to DUT.")
            ref_dut_acl = await self.ref.device.connect(
                f"{self.dut.address}/P",
                transport=core.BT_BR_EDR_TRANSPORT,
                timeout=_DEFAULT_STEP_TIMEOUT_SECONDS,
            )

            self.logger.info("[REF] Create bond.")
            auth_task = asyncio.tasks.create_task(ref_dut_acl.authenticate())

            self.logger.info("[DUT] Wait for incoming connection.")
            await dut_cb.wait_for_event(
                event=bl4a_api.AclConnected(
                    address=self.ref.address,
                    transport=android_constants.Transport.CLASSIC,
                ),
                timeout=_DEFAULT_STEP_TIMEOUT,
            )

        self.logger.info("[DUT] Wait for pairing request.")
        dut_pairing_event = await dut_cb.wait_for_event(
            event=bl4a_api.PairingRequest(address=self.ref.address, variant=mock.ANY, pin=mock.ANY),
            timeout=_DEFAULT_STEP_TIMEOUT,
        )

        ref_accept = variant != TestVariant.REJECTED
        dut_accept = variant != TestVariant.REJECT
        ref_answer: pairing_utils.PairingAnswer

        self.logger.info("[DUT] Check reported pairing method.")
        match ref_io_capability:
            case _IoCapability.NO_OUTPUT_NO_INPUT:
                expected_dut_pairing_variant = _AndroidPairingVariant.CONSENT
                expected_ref_pairing_variant = _BumblePairingVariant.JUST_WORK
                ref_answer = ref_accept
            case _IoCapability.KEYBOARD_INPUT_ONLY:
                expected_dut_pairing_variant = _AndroidPairingVariant.DISPLAY_PASSKEY
                expected_ref_pairing_variant = (_BumblePairingVariant.PASSKEY_ENTRY_REQUEST)
                ref_answer = dut_pairing_event.pin if ref_accept else None
            case _IoCapability.DISPLAY_OUTPUT_ONLY:
                expected_dut_pairing_variant = (_AndroidPairingVariant.PASSKEY_CONFIRMATION)
                expected_ref_pairing_variant = (_BumblePairingVariant.PASSKEY_ENTRY_NOTIFICATION)
                # For SSP PASSKEY pairing, Bumble will invoke display_number, and then
                # confirm, so we need to unblock both events.
                pairing_delegate.pairing_answers.put_nowait(None)
                ref_answer = ref_accept
            case _IoCapability.DISPLAY_OUTPUT_AND_YES_NO_INPUT:
                expected_dut_pairing_variant = (_AndroidPairingVariant.PASSKEY_CONFIRMATION)
                expected_ref_pairing_variant = _BumblePairingVariant.NUMERIC_COMPARISON
                ref_answer = ref_accept
            case _:
                raise ValueError(f"Unsupported IO capability: {ref_io_capability}")

        self.assertEqual(dut_pairing_event.variant, expected_dut_pairing_variant)

        self.logger.info("[REF] Wait for pairing request.")
        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            ref_pairing_event = await pairing_delegate.pairing_events.get()

        self.assertEqual(ref_pairing_event.variant, expected_ref_pairing_variant)

        if expected_ref_pairing_variant == _BumblePairingVariant.NUMERIC_COMPARISON:
            self.assertEqual(ref_pairing_event.arg, dut_pairing_event.pin)

        if dut_accept:
            self.logger.info("[DUT] Accept pairing.")
            self.dut.bt.setPairingConfirmation(self.ref.address, True)
        else:
            self.logger.info("[DUT] Cancel bond to reject pairing.")
            self.dut.bt.cancelBond(self.ref.address)

        if variant == TestVariant.DISCONNECTED:
            self.logger.info("[REF] Disconnect.")
            await ref_dut_acl.disconnect()
        else:
            self.logger.info("[REF] Answer pairing request.")
            pairing_delegate.pairing_answers.put_nowait(ref_answer)

        self.logger.info("[DUT] Check final state.")
        expect_state = (android_constants.BondState.BONDED
                        if variant == TestVariant.ACCEPT else android_constants.BondState.NONE)
        event = await dut_cb.wait_for_event(
            bl4a_api.BondStateChanged(
                address=self.ref.address,
                state=matcher.any_of(*_TERMINATED_BOND_STATES),
            ),)
        self.assertEqual(event.state, expect_state)

        if auth_task:
            self.logger.info("[REF] Wait for authentication complete.")
            expected_errors = ([] if variant == TestVariant.ACCEPT else
                               [hci.HCI_Error, asyncio.CancelledError])
            with contextlib.suppress(*expected_errors):
                async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
                    await auth_task

    async def _test_smp_over_classic(
        self,
        expected_key_distribution: _KeyDistribution,
    ) -> None:
        """Tests CTKD procedure with SMP over Classic channel.

    Prerequisite:
      * A Classic ACL connection has been established.

    Test steps:
      1. Initiate or accept CTKD on REF.
      2. Wait for CTKD complete.
      3. Make an LE connection. Use RPA if IRK is exchanged, otherwise use
      identity address.
      4. [If LTK] Encrypt the link to verify LTK.
      5. [If CSRK] Verify CSRK lierally.

    Args:
      expected_key_distribution: Keys expected to be distributed.
    """
        pairing_delegate = self.pairing_delegate

        if not (ref_dut_acl := self.ref.device.find_connection_by_bd_addr(
                hci.Address(f"{self.dut.address}/P"))):
            self.fail("[REF] Failed to find ACL connection with DUT.")

        ref_pairing_future = asyncio.futures.Future[bumble_keys.PairingKeys]()
        ref_key_updates: asyncio.Queue[None] | None = None

        with pyee_extensions.EventWatcher() as watcher:
            # [REF] Watch pairing complete.
            @watcher.once(ref_dut_acl, ref_dut_acl.EVENT_PAIRING)
            def _(keys: bumble_keys.PairingKeys) -> None:
                ref_pairing_future.set_result(keys)

            if _KeyDistribution.DISTRIBUTE_IDENTITY_KEY in expected_key_distribution:
                # [REF] IRK exchange will trigger an async resolving list update.
                ref_key_updates = watcher.async_monitor(self.ref.device, "key_store_update")

            pair_task: asyncio.tasks.Task | None = None
            if ref_dut_acl.role == _Role.CENTRAL:
                self.logger.info("[REF] Initiate SMP pairing.")
                pair_task = asyncio.tasks.create_task(ref_dut_acl.pair())
            else:
                self.logger.info("[REF] Accept SMP pairing request.")
                pairing_delegate.acceptions.put_nowait(True)

            self.logger.info("[REF] Wait for CTKD complete.")
            async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
                if pair_task:
                    await pair_task
                if ref_key_updates:
                    await ref_key_updates.get()
                keys = await ref_pairing_future

        # If IRK is not exchanged, devices cannot recognize each other from RPA,
        # so they will use identity address for verification.
        ref_address_type = hci.OwnAddressType.PUBLIC
        if _KeyDistribution.DISTRIBUTE_IDENTITY_KEY in expected_key_distribution:
            self.assertIsNotNone(keys.irk)
            ref_address_type = hci.OwnAddressType.RESOLVABLE_OR_PUBLIC

        self.logger.info("[REF] Create LE L2CAP server.")
        ref_l2cap_server = self.ref.device.create_l2cap_server(l2cap.LeCreditBasedChannelSpec())

        self.logger.info("[REF] Start advertising.")
        await self.ref.device.start_advertising(own_address_type=ref_address_type)

        self.logger.info("[DUT] Make LE connection.")
        secure_connection = (_KeyDistribution.DISTRIBUTE_ENCRYPTION_KEY
                             in expected_key_distribution)
        await self.dut.bl4a.create_l2cap_channel(
            address=self.ref.address,
            secure=secure_connection,
            psm=ref_l2cap_server.psm,
            address_type=android_constants.AddressTypeStatus.PUBLIC,
        )

        if _KeyDistribution.DISTRIBUTE_SIGNING_KEY in expected_key_distribution:
            self.assertIsNotNone(keys.csrk)

    @navi_test_base.parameterized(
        *((variant, ref_io_capability, ref_role)
          for (variant, ref_io_capability, ref_role) in itertools.product(
              list(TestVariant),
              (
                  _IoCapability.NO_OUTPUT_NO_INPUT,
                  _IoCapability.KEYBOARD_INPUT_ONLY,
                  _IoCapability.DISPLAY_OUTPUT_ONLY,
                  _IoCapability.DISPLAY_OUTPUT_AND_YES_NO_INPUT,
              ),
              (_Role.CENTRAL, _Role.PERIPHERAL),
          )
          if not (variant == TestVariant.REJECT and
                  ref_io_capability == _IoCapability.KEYBOARD_INPUT_ONLY)))
    @navi_test_base.retry(max_count=2)
    async def test_outgoing_pairing_ssp_only(
        self,
        variant: TestVariant,
        ref_io_capability: _IoCapability,
        ref_role: _Role,
    ) -> None:
        """Tests outgoing Simple Secure Pairing.

    Test steps:
      1. Perform SSP.

    Args:
      variant: variant of pairing actions performmed in the test.
      ref_io_capability: IO capabilities of the REF device.
      ref_role: ACL role of the REF device.
    """
        self.logger.info("[REF] Disable SMP over Classic L2CAP channel.")
        self.ref.device.l2cap_channel_manager.deregister_fixed_channel(smp.SMP_BR_CID)

        self.logger.info("[REF] Set pairing delegate.")
        self.pairing_delegate = pairing_utils.PairingDelegate(
            io_capability=ref_io_capability,
            auto_accept=True,
        )

        await self._test_ssp_pairing_async(
            variant=variant,
            pairing_direction=_Direction.OUTGOING,
            ref_io_capability=ref_io_capability,
            ref_role=ref_role,
        )

    @navi_test_base.parameterized(
        *((variant, ref_io_capability) for (variant, ref_io_capability) in itertools.product(
            list(TestVariant),
            (
                _IoCapability.NO_OUTPUT_NO_INPUT,
                _IoCapability.KEYBOARD_INPUT_ONLY,
                _IoCapability.DISPLAY_OUTPUT_ONLY,
                _IoCapability.DISPLAY_OUTPUT_AND_YES_NO_INPUT,
            ),
        ) if not (variant == TestVariant.REJECT and
                  ref_io_capability == _IoCapability.KEYBOARD_INPUT_ONLY)))
    @navi_test_base.retry(max_count=2)
    async def test_incoming_pairing_ssp_only(
        self,
        variant: TestVariant,
        ref_io_capability: _IoCapability,
    ) -> None:
        """Tests incoming Simple Secure Pairing.

    Test steps:
      1. Perform SSP.

    Args:
      variant: variant of pairing actions performmed in the test.
      ref_io_capability: IO capabilities of the REF device.
    """
        self.logger.info("[REF] Disable SMP over Classic L2CAP channel.")
        self.ref.device.l2cap_channel_manager.deregister_fixed_channel(smp.SMP_BR_CID)

        self.logger.info("[REF] Set pairing delegate.")
        self.pairing_delegate = pairing_utils.PairingDelegate(
            io_capability=ref_io_capability,
            auto_accept=True,
        )

        await self._test_ssp_pairing_async(
            variant=variant,
            pairing_direction=_Direction.INCOMING,
            ref_io_capability=ref_io_capability,
            ref_role=_Role.CENTRAL,  # unused
        )

    @navi_test_base.parameterized(
        *itertools.product(
            (_Role.CENTRAL, _Role.PERIPHERAL),
            (
                # LTK + IRK
                (_KeyDistribution.DISTRIBUTE_ENCRYPTION_KEY |
                 _KeyDistribution.DISTRIBUTE_IDENTITY_KEY),
                # LTK + IRK + CSRK
                (_KeyDistribution.DISTRIBUTE_ENCRYPTION_KEY |
                 _KeyDistribution.DISTRIBUTE_IDENTITY_KEY | _KeyDistribution.DISTRIBUTE_SIGNING_KEY
                ),
            ),
        ),)
    @navi_test_base.retry(max_count=2)
    async def test_outgoing_pairing_ssp_ctkd(
        self,
        ref_role: _Role,
        key_distribution: _KeyDistribution,
    ) -> None:
        """Tests outgoing Simple Secure Pairing with CTKD.

    Test steps:
      1. Perform SSP.
      2. Perform CTKD (Cross-Transport Key Derivation).

    Args:
      ref_role: ACL role of the REF device.
      key_distribution: key distribution in SMP preferred by the REF device.
    """
        ref_io_capability = _IoCapability.DISPLAY_OUTPUT_AND_YES_NO_INPUT
        self.logger.info("[REF] Enable SMP over Classic L2CAP channel.")
        self.ref.device.l2cap_channel_manager.register_fixed_channel(smp.SMP_BR_CID,
                                                                     self.ref.device.on_smp_pdu)

        self.logger.info("[REF] Set pairing delegate.")
        self.pairing_delegate = pairing_utils.PairingDelegate(
            io_capability=ref_io_capability,
            local_initiator_key_distribution=key_distribution,
            local_responder_key_distribution=key_distribution,
            auto_accept=False,
        )

        await self._test_ssp_pairing_async(
            variant=TestVariant.ACCEPT,
            pairing_direction=_Direction.OUTGOING,
            ref_io_capability=ref_io_capability,
            ref_role=ref_role,
        )

        await self._test_smp_over_classic(expected_key_distribution=key_distribution)

    @navi_test_base.retry(max_count=2)
    async def test_legacy_pairing_incoming(self) -> None:
        """Tests incoming Legacy Pairing.

    Test steps:
      1. Disable SSP on REF.
      2. Pair DUT from REF.
      3. Wait for pairing requests on REF.
      4. Set pairing PIN on REF.
      5. Wait for pairing requests on DUT.
      6. Set pairing PIN on DUT.
      7. Verify final states.
    """
        self.ref.device.classic_sc_enabled = False
        self.ref.device.classic_ssp_enabled = False

        self.logger.info("[REF] Power on.")
        await self.ref.device.power_on()

        dut_cb = self.dut.bl4a.register_callback(bl4a_api.Module.ADAPTER)
        self.test_case_context.push(dut_cb)

        self.logger.info("[REF] Set pairing delegate.")
        pairing_delegate = pairing_utils.PairingDelegate(
            io_capability=_IoCapability.KEYBOARD_INPUT_ONLY,
            auto_accept=False,
        )

        def pairing_config_factory(connection: device.Connection,) -> pairing.PairingConfig:
            del connection  # Unused.
            return pairing.PairingConfig(delegate=pairing_delegate)

        self.ref.device.pairing_config_factory = pairing_config_factory

        self.logger.info("[REF] Connect to DUT.")
        ref_dut_acl = await self.ref.device.connect(
            f"{self.dut.address}/P",
            transport=core.BT_BR_EDR_TRANSPORT,
            timeout=_DEFAULT_STEP_TIMEOUT_SECONDS,
        )
        self.logger.info("[REF] Create bond.")
        auth_task = asyncio.tasks.create_task(ref_dut_acl.authenticate())

        self.logger.info("[DUT] Wait for incoming connection.")
        await dut_cb.wait_for_event(event=bl4a_api.AclConnected(
            address=self.ref.address,
            transport=android_constants.Transport.CLASSIC,
        ),)

        self.logger.info("[REF] Wait for pairing request.")
        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            ref_pairing_request = await pairing_delegate.pairing_events.get()

        self.assertEqual(
            ref_pairing_request.variant,
            _BumblePairingVariant.PIN_CODE_REQUEST,
        )

        self.logger.info("[REF] Handle pairing confirmation.")
        pairing_delegate.pairing_answers.put_nowait(_DEFAULT_PIN_CODE)

        self.logger.info("[DUT] Wait for pairing request.")
        dut_pairing_request = await dut_cb.wait_for_event(
            bl4a_api.PairingRequest(address=self.ref.address, variant=mock.ANY, pin=mock.ANY))

        self.assertEqual(dut_pairing_request.variant, _AndroidPairingVariant.PIN)

        self.logger.info("[DUT] Handle pairing confirmation.")
        self.dut.bt.setPin(self.ref.address, _DEFAULT_PIN_CODE)

        self.logger.info("[DUT] Check final state.")
        await dut_cb.wait_for_event(
            bl4a_api.BondStateChanged(
                address=self.ref.address,
                state=android_constants.BondState.BONDED,
            ),)

        self.logger.info("[REF] Wait authentication complete.")
        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            await auth_task

    @navi_test_base.retry(max_count=2)
    async def test_legacy_pairing_outgoing(self) -> None:
        """Tests outgoing Legacy Pairing.

    Test steps:
      1. Disable SSP on REF.
      2. Pair REF from DUT.
      3. Wait for pairing requests on DUT.
      4. Set pairing PIN on DUT.
      5. Wait for pairing requests on REF.
      6. Set pairing PIN on REF.
      7. Verify final states.
    """
        self.ref.device.classic_sc_enabled = False
        self.ref.device.classic_ssp_enabled = False

        self.logger.info("[REF] Power on.")
        await self.ref.device.power_on()

        dut_cb = self.dut.bl4a.register_callback(bl4a_api.Module.ADAPTER)
        self.test_case_context.push(dut_cb)

        self.logger.info("[REF] Set pairing delegate.")
        pairing_delegate = pairing_utils.PairingDelegate(
            io_capability=_IoCapability.KEYBOARD_INPUT_ONLY,
            auto_accept=False,
        )

        def pairing_config_factory(connection: device.Connection,) -> pairing.PairingConfig:
            del connection
            return pairing.PairingConfig(delegate=pairing_delegate)

        self.ref.device.pairing_config_factory = pairing_config_factory

        self.logger.info("[DUT] Search for REF to update CoD.")
        self.dut.bt.startInquiry()

        await dut_cb.wait_for_event(
            bl4a_api.DeviceFound(address=self.ref.address, name=mock.ANY, rssi=mock.ANY),)

        self.logger.info("[DUT] Create bond.")
        self.dut.bt.createBond(self.ref.address, android_constants.Transport.CLASSIC)

        self.logger.info("[DUT] Wait for pairing request.")
        dut_pairing_request = await dut_cb.wait_for_event(
            bl4a_api.PairingRequest(address=self.ref.address, variant=mock.ANY, pin=mock.ANY))

        self.assertEqual(dut_pairing_request.variant, _AndroidPairingVariant.PIN)

        self.logger.info("[DUT] Handle pairing confirmation.")
        self.dut.bt.setPin(self.ref.address, _DEFAULT_PIN_CODE)

        self.logger.info("[REF] Wait for pairing request.")
        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            ref_pairing_request = await pairing_delegate.pairing_events.get()

        self.assertEqual(
            ref_pairing_request.variant,
            _BumblePairingVariant.PIN_CODE_REQUEST,
        )

        self.logger.info("[REF] Handle pairing confirmation.")
        pairing_delegate.pairing_answers.put_nowait(_DEFAULT_PIN_CODE)

        self.logger.info("[DUT] Check final state.")
        await dut_cb.wait_for_event(
            bl4a_api.BondStateChanged(
                address=self.ref.address,
                state=android_constants.BondState.BONDED,
            ),)

    async def test_remove_bond(self) -> None:
        """Tests removing bond.

    Test steps:
      1. Pair DUT and REF.
      2. Remove bond on DUT.
      3. Verify bond state change on DUT.
    """
        await self.classic_connect_and_pair()

        with self.dut.bl4a.register_callback(bl4a_api.Module.ADAPTER) as dut_cb:
            self.logger.info("[DUT] Remove bond.")
            self.dut.bt.removeBond(self.ref.address)

            self.logger.info("[DUT] Wait for bond state change.")
            await dut_cb.wait_for_event(
                bl4a_api.BondStateChanged(address=self.ref.address,
                                          state=android_constants.BondState.NONE))

    async def test_remove_bond_when_no_bond_exists(self) -> None:
        """Tests removing bond when no bond exists.

    Test steps:
      1. Remove bond on DUT.
      2. Verify remove bond failure.
    """
        self.logger.info("[DUT] Remove bond.")
        self.assertFalse(self.dut.bt.removeBond(self.ref.address))

    async def test_remove_bond_when_bluetooth_is_disabled(self) -> None:
        """Tests removing bond when Bluetooth is disabled.

    Test steps:
      1. Pair DUT and REF.
      2. Disable Bluetooth on DUT.
      3. Remove bond on DUT.
      4. Verify remove bond failure.
      5. Enable Bluetooth on DUT and check if bond is not removed.
    """
        self.logger.info("[DUT] Pair DUT and REF.")
        await self.classic_connect_and_pair()

        self.logger.info("[DUT] Disable Bluetooth.")
        self.assertTrue(self.dut.bt.disable())

        self.logger.info("[DUT] Wait for Bluetooth to be disabled.")
        self.dut.bt.waitForAdapterState(android_constants.AdapterState.OFF)

        self.logger.info("[DUT] Remove bond.")
        self.assertFalse(self.dut.bt.removeBond(self.ref.address))

        self.logger.info("[DUT] Enable Bluetooth.")
        self.assertTrue(self.dut.bt.enable())

        self.logger.info("[DUT] Wait for Bluetooth to be enabled.")
        self.dut.bt.waitForAdapterState(android_constants.AdapterState.ON)

        self.logger.info("[DUT] Check if bond is not removed.")
        self.assertIn(self.ref.address, self.dut.bt.getBondedDevices())


if __name__ == "__main__":
    test_runner.main()
