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
"""Tests related to Bluetooth A2DP Source role on Pixel."""

import asyncio

from bumble import avdtp
from bumble import core
from bumble import device as bumble_device
from bumble import hci
from mobly import test_runner
from mobly import signals
from typing_extensions import override

from navi.bumble_ext import a2dp as a2dp_ext
from navi.tests import navi_test_base
from navi.utils import android_constants
from navi.utils import bl4a_api

_A2DP_SERVICE_RECORD_HANDLE = 1
_DEFAULT_STEP_TIMEOUT_SECONDS = 15.0

_A2dpCodec = a2dp_ext.A2dpCodec
_Module = bl4a_api.Module


class A2dpSourceTest(navi_test_base.TwoDevicesTestBase):
    """A2DP Source (DUT) tests."""

    @override
    async def async_setup_class(self) -> None:
        await super().async_setup_class()
        if (self.dut.getprop(android_constants.Property.A2DP_SOURCE_ENABLED) != "true"):
            raise signals.TestAbortClass("A2DP Source is not enabled on DUT.")

    def _setup_a2dp_sink_from_ref(self, codecs: list[_A2dpCodec]) -> avdtp.Listener:
        """Sets up A2DP Sink profile on REF.

    Args:
      codecs: A2DP codecs supported by REF.

    Returns:
      An avdtp.Listener.
    """
        self.logger.info("[REF]setup_a2dp_sink_from_ref")
        listener = a2dp_ext.setup_sink_server(
            self.ref.device,
            [codec.get_default_capabilities() for codec in codecs],
            _A2DP_SERVICE_RECORD_HANDLE,
        )

        return listener

    async def _pair_and_connect_from_dut(self) -> None:
        """Tests A2DP connection establishment right after a pairing session."""
        with self.dut.bl4a.register_callback(_Module.A2DP) as dut_cb:
            self._setup_a2dp_sink_from_ref([_A2dpCodec.SBC])
            self.logger.info("[DUT] Connect and pair REF.")
            await self.classic_connect_and_pair(connect_profiles=True)

            self.logger.info("[DUT] Wait for A2DP connected.")
            await dut_cb.wait_for_event(
                bl4a_api.ProfileConnectionStateChanged(
                    address=self.ref.address,
                    state=android_constants.ConnectionState.CONNECTED,
                ),
                timeout=_DEFAULT_STEP_TIMEOUT_SECONDS,
            )
            self.logger.info("[DUT] Wait for A2DP becomes active.")
            await dut_cb.wait_for_event(
                bl4a_api.ProfileActiveDeviceChanged(address=self.ref.address),
                timeout=_DEFAULT_STEP_TIMEOUT_SECONDS,
            )

    async def _find_or_connect_acl_from_ref(self, dut_address: str) -> bumble_device.Connection:
        """Finds or creates an ACL connection from REF to DUT."""
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

    async def _connect_a2dp_from_dut(self, ref_address: str) -> None:
        """Initiates A2DP connection from DUT to REF."""
        self.logger.info("[DUT] Initiating A2DP connection.")
        self.dut.bt.connect(ref_address)

    async def _connect_a2dp_from_ref(self, dut_ref_acl: bumble_device.Connection) -> None:
        """Initiates A2DP (AVDTP) connection from REF to DUT."""
        self.logger.info("[REF] Initiating AVDTP connection.")
        await avdtp.Protocol.connect(dut_ref_acl)

    async def test_paired_connect_a2dp_simultaneous(self) -> None:
        """Tests A2DP connection establishment with simultaneous connection.

    Test steps:
      1. Setup pairing between DUT(A2DP Source) and REF(A2DP Sink).
      2. Terminate ACL connection from DUT.
      3. Setup ACL connection from REF.
      4. Trigger A2DP connection from DUT and REF at same time.
      5. Wait A2DP connected on DUT.
      6. Disconnect from DUT.
      7. Wait A2DP disconnected on DUT.

    Test Results:
      DUT should be able to establish A2DP connection successfully even in
      conflicting scenarios.
    """

        with self.dut.bl4a.register_callback(_Module.A2DP) as dut_cb:
            # Step 1: Setup pairing and initial A2DP connection
            async with self.assert_not_timeout(
                    _DEFAULT_STEP_TIMEOUT_SECONDS,
                    msg="[DUT] Setup pairing and initial A2DP connection.",
            ):
                await self._pair_and_connect_from_dut()

            # Step 2: Terminate ACL connection
            await self.disconnect_with_check(self.ref.address, android_constants.Transport.CLASSIC)

            # Step 3: Setup ACL connection from REF
            async with self.assert_not_timeout(
                _DEFAULT_STEP_TIMEOUT_SECONDS,
                msg="[REF] Find or connect ACL connection from DUT.",
            ):
                dut_ref_acl = await self._find_or_connect_acl_from_ref(self.dut.address)

            # Step 4: Trigger connection from DUT and REF at same time
            self.logger.info("[DUT & REF] Triggering simultaneous A2DP (AVDTP) connection.")

            # Use asyncio.gather to run both connection attempts concurrently
            try:
                await asyncio.wait_for(
                    asyncio.gather(
                        self._connect_a2dp_from_dut(self.ref.address),
                        self._connect_a2dp_from_ref(dut_ref_acl),
                    ),
                    timeout=_DEFAULT_STEP_TIMEOUT_SECONDS,
                )
            except (core.BaseBumbleError, TimeoutError):
                self.logger.warning(
                    "[REF & DUT] Simultaneous A2DP connection exception.",
                    stack_info=True,
                )

            # Step 5: Wait for A2DP to be connected on DUT
            self.logger.info("[DUT] Wait for A2DP connected.")
            await dut_cb.wait_for_event(
                bl4a_api.ProfileConnectionStateChanged(
                    address=self.ref.address,
                    state=android_constants.ConnectionState.CONNECTED,
                ),
                timeout=_DEFAULT_STEP_TIMEOUT_SECONDS,
            )
            self.logger.info("[DUT] Wait for A2DP becomes active.")
            await dut_cb.wait_for_event(
                bl4a_api.ProfileActiveDeviceChanged(address=self.ref.address),
                timeout=_DEFAULT_STEP_TIMEOUT_SECONDS,
            )

            # Step 6: Disconnect from DUT
            self.logger.info("Step 6: Disconnect from DUT.")
            self.dut.bt.disconnect(self.ref.address)

            # Step 7: Wait for A2DP to be disconnected on DUT
            self.logger.info("Step 7: Wait for A2DP disconnected.")
            await dut_cb.wait_for_event(
                bl4a_api.ProfileConnectionStateChanged(
                    address=self.ref.address,
                    state=android_constants.ConnectionState.DISCONNECTED,
                ),
                timeout=_DEFAULT_STEP_TIMEOUT_SECONDS,
            )


if __name__ == "__main__":
    test_runner.main()
