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

import asyncio

from mobly import test_runner

from navi.bumble_ext import a2dp as a2dp_ext
from navi.tests import navi_test_base
from navi.utils import android_constants
from navi.utils import bl4a_api

_TIMEOUT_SECONDS = 5.0
_A2DP_SERVICE_RECORD_HANDLE = 1
_DEFAULT_DISCONNECTION_TIMEOUT_SECONDS = 10.0
_A2dpCodec = a2dp_ext.A2dpCodec


class BluetoothServiceTest(navi_test_base.TwoDevicesTestBase):

    @navi_test_base.named_parameterized(
        ("ble_scan_enabled", 1, android_constants.AdapterState.BLE_ON),
        ("ble_scan_disabled", 0, android_constants.AdapterState.OFF),
    )
    async def test_bt_disabled(self, scan_mode: int,
                               target_state: android_constants.AdapterState) -> None:
        """Tests adapter state after BT off.

    Test steps:
      1. Enable/Disable ble_scan_always_enabled.
      2. Disable bluetooth on DUT.
      3. Check if bluetooth state is ble_on/off.

    Args:
      scan_mode: Enable/Disable ble_scan_always_enabled.
      target_state: Expected adapter state after BT off.
    """

        current_mode = self.dut.shell(["settings get global ble_scan_always_enabled"])
        self.test_case_context.callback(
            lambda: self.dut.shell(f"settings put global ble_scan_always_enabled {current_mode}"))

        self.logger.info("[DUT] Set ble_scan_always_enabled to %s", scan_mode)
        self.dut.shell(["settings put global ble_scan_always_enabled", str(scan_mode)])

        self.logger.info("[DUT] Disable bluetooth.")
        self.assertTrue(self.dut.bt.disable())

        self.logger.info("[DUT] Check adapter state is at %s.", target_state.name)
        self.dut.bt.waitForAdapterState(target_state)

    @navi_test_base.named_parameterized(
        ("ble_scan_enabled", 1, android_constants.AdapterState.BLE_ON),
        ("ble_scan_disabled", 0, android_constants.AdapterState.OFF),
    )
    async def test_no_connection_after_bt_disabled(
            self, scan_mode: int, target_state: android_constants.AdapterState) -> None:
        """Tests no connection after BT off.

    Test steps:
      1. Setup A2DP connection between DUT and REF.
      2. Connect and pair DUT and REF.
      3. Enable/Disable ble_scan_always_enabled.
      4. Disable bluetooth on DUT.
      5. Check if acl is disconnected.

    Args:
      scan_mode: Enable/Disable ble_scan_always_enabled.
      target_state: Expected adapter state after BT off.
    """
        a2dp_ext.setup_sink_server(
            self.ref.device,
            [_A2dpCodec.SBC.get_default_capabilities()],
            _A2DP_SERVICE_RECORD_HANDLE,
        )

        with self.dut.bl4a.register_callback(bl4a_api.Module.A2DP) as dut_cb:
            connection = await self.classic_connect_and_pair()
            self.logger.info("[DUT] Connection: %s", connection)

            disconnection = asyncio.Queue[int]()
            connection.on(connection.EVENT_DISCONNECTION, disconnection.put_nowait)

            self.logger.info("[DUT] Wait for A2DP connected.")
            await dut_cb.wait_for_event(
                bl4a_api.ProfileConnectionStateChanged(
                    address=self.ref.address,
                    state=android_constants.ConnectionState.CONNECTED,
                ),)

            await self.test_bt_disabled(scan_mode, target_state)

            self.logger.info("[DUT] Wait for A2DP disconnected.")
            await dut_cb.wait_for_event(
                bl4a_api.ProfileConnectionStateChanged(
                    address=self.ref.address,
                    state=android_constants.ConnectionState.DISCONNECTED,
                ),)

            async with self.assert_not_timeout(
                    _DEFAULT_DISCONNECTION_TIMEOUT_SECONDS,
                    msg="[REF] Wait for acl disconnection.",
            ):
                await disconnection.get()


if __name__ == "__main__":
    test_runner.main()
