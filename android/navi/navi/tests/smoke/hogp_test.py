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
"""Tests for HID over GATT Profile(GATT) implementation on Android."""

import contextlib
import struct

from bumble import gatt
from bumble import hci
from mobly import test_runner
from mobly import signals
from typing_extensions import override

from navi.bumble_ext import hid
from navi.tests import navi_test_base
from navi.utils import android_constants
from navi.utils import bl4a_api
from navi.utils import constants
from navi.utils import input as input_utils

_VIDEO_SERVICE_NAME = "video"
_DEFAULT_STEP_TIMEOUT_SECONDS = 10.0


class HogpTest(navi_test_base.TwoDevicesTestBase):
    ref_hogp_service: gatt.Service
    ref_keyboard_input_report_characteristic: gatt.Characteristic
    ref_keyboard_output_report_characteristic: gatt.Characteristic
    ref_mouse_input_report_characteristic: gatt.Characteristic

    def _setup_hid_service(self) -> None:
        self.ref_keyboard_input_report_characteristic = gatt.Characteristic(
            gatt.GATT_REPORT_CHARACTERISTIC,
            gatt.Characteristic.Properties.READ | gatt.Characteristic.Properties.WRITE |
            gatt.Characteristic.Properties.NOTIFY,
            gatt.Characteristic.READABLE | gatt.Characteristic.WRITEABLE,
            bytes(8),
            [
                gatt.Descriptor(
                    gatt.GATT_REPORT_REFERENCE_DESCRIPTOR,
                    gatt.Descriptor.READABLE,
                    bytes([0x01, hid.ReportType.INPUT_REPORT.value]),
                )
            ],
        )

        self.ref_keyboard_output_report_characteristic = gatt.Characteristic(
            gatt.GATT_REPORT_CHARACTERISTIC,
            gatt.Characteristic.Properties.READ | gatt.Characteristic.Properties.WRITE |
            gatt.Characteristic.WRITE_WITHOUT_RESPONSE,
            gatt.Characteristic.READABLE | gatt.Characteristic.WRITEABLE,
            bytes([0]),
            [
                gatt.Descriptor(
                    gatt.GATT_REPORT_REFERENCE_DESCRIPTOR,
                    gatt.Descriptor.READABLE,
                    bytes([0x01, hid.ReportType.OUTPUT_REPORT.value]),
                )
            ],
        )
        self.ref_mouse_input_report_characteristic = gatt.Characteristic(
            gatt.GATT_REPORT_CHARACTERISTIC,
            gatt.Characteristic.Properties.READ | gatt.Characteristic.Properties.WRITE |
            gatt.Characteristic.Properties.NOTIFY,
            gatt.Characteristic.READABLE | gatt.Characteristic.WRITEABLE,
            bytes(6),
            [
                gatt.Descriptor(
                    gatt.GATT_REPORT_REFERENCE_DESCRIPTOR,
                    gatt.Descriptor.READABLE,
                    bytes([0x02, hid.ReportType.INPUT_REPORT.value]),
                )
            ],
        )
        self.ref_hogp_service = gatt.Service(
            gatt.GATT_HUMAN_INTERFACE_DEVICE_SERVICE,
            [
                gatt.Characteristic(
                    gatt.GATT_PROTOCOL_MODE_CHARACTERISTIC,
                    gatt.Characteristic.Properties.READ,
                    gatt.Characteristic.READABLE,
                    bytes([hid.ProtocolMode.REPORT_PROTOCOL.value]),
                ),
                gatt.Characteristic(
                    gatt.GATT_HID_INFORMATION_CHARACTERISTIC,
                    gatt.Characteristic.Properties.READ,
                    gatt.Characteristic.READABLE,
                    # bcdHID=1.1, bCountryCode=0x00,
                    # Flags=RemoteWake|NormallyConnectable
                    bytes([0x11, 0x01, 0x00, 0x03]),
                ),
                gatt.Characteristic(
                    gatt.GATT_HID_CONTROL_POINT_CHARACTERISTIC,
                    gatt.Characteristic.WRITE_WITHOUT_RESPONSE,
                    gatt.Characteristic.WRITEABLE,
                ),
                gatt.Characteristic(
                    gatt.GATT_REPORT_MAP_CHARACTERISTIC,
                    gatt.Characteristic.Properties.READ,
                    gatt.Characteristic.READABLE,
                    hid.DEFAULT_REPORT_MAP,
                ),
                self.ref_keyboard_input_report_characteristic,
                self.ref_keyboard_output_report_characteristic,
                self.ref_mouse_input_report_characteristic,
            ],
        )
        self.ref.device.add_service(self.ref_hogp_service)

    @override
    async def async_setup_class(self) -> None:
        await super().async_setup_class()
        if self.dut.device.adb.getprop(hid.PROPERTY_HID_HOST_SUPPORTED) != "true":
            raise signals.TestAbortClass("HID host is not supported on DUT")

        # Stay awake during the test.
        self.dut.shell("svc power stayon true")
        # Dismiss the keyguard.
        self.dut.shell("wm dismiss-keyguard")

    @override
    async def async_teardown_class(self) -> None:
        await super().async_teardown_class()
        # Stop staying awake during the test.
        self.dut.shell("svc power stayon false")

    @override
    async def async_setup_test(self) -> None:
        await super().async_setup_test()

    async def test_connect(self) -> None:
        """Tests establishing the HID connection from DUT to REF.

    Test steps:
      1. Establish the HID connection between DUT and REF.
      2. Verify the HID connection is established.
    """
        self._setup_hid_service()
        with self.dut.bl4a.register_callback(bl4a_api.Module.HID_HOST) as dut_hid_cb:
            self.logger.info("[DUT] Pair with REF")
            await self.le_connect_and_pair(hci.OwnAddressType.RANDOM, connect_profiles=True)
            self.logger.info("[DUT] Wait for HID connected")
            await dut_hid_cb.wait_for_event(
                bl4a_api.ProfileConnectionStateChanged(
                    address=self.ref.random_address,
                    state=android_constants.ConnectionState.CONNECTED,
                ),)

    async def test_reconnect(self) -> None:
        """Tests reconnecting the HID connection with the background scanner.

    Test steps:
      1. Pair with REF.
      2. Terminate the connection.
      3. Start advertising on REF.
      4. Verify the HID connection is re-established by the background scanner.
    """
        await self.test_connect()

        ref_dut_acl = self.ref.device.find_connection_by_bd_addr(hci.Address(self.dut.address))
        assert ref_dut_acl is not None
        self.logger.info("[REF] Disconnect")
        await ref_dut_acl.disconnect()

        with self.dut.bl4a.register_callback(bl4a_api.Module.HID_HOST) as dut_hid_cb:
            self.logger.info("[REF] Restart advertising")
            await self.ref.device.start_advertising(own_address_type=hci.OwnAddressType.RANDOM,)
            self.logger.info("[DUT] Wait for connected")
            await dut_hid_cb.wait_for_event(
                bl4a_api.ProfileConnectionStateChanged(
                    address=self.ref.random_address,
                    state=android_constants.ConnectionState.CONNECTED,
                ),)

    async def test_keyboard_input(self) -> None:
        """Tests the HID keyboard input.

    Test steps:
      1. Establish the HID connection between DUT and REF.
      2. Press each key on the keyboard and verify the key down and up events
         on DUT.
    """
        # Leverage the test_connect() to establish the connection.
        await self.test_connect()
        report_characteristic = self.ref_keyboard_input_report_characteristic

        input_monitor = await input_utils.InputMonitor.create(self.dut.device.serial)
        self.test_case_context.push(input_monitor)

        self.logger.info("[DUT] Wait for input ready")
        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            await input_monitor.wait_for_event(["Bumble Keyboard"])

        for hid_key in range(constants.UsbHidKeyCode.A, constants.UsbHidKeyCode.Z + 1):
            hid_key_code = constants.UsbHidKeyCode(hid_key)
            android_key_code = android_constants.KeyCode[hid_key_code.name]
            self.logger.info("[REF] Press HID key %s", hid_key_code.name)
            report_characteristic.value = bytes([0x00, 0x00, hid_key, 0x00, 0x00, 0x00, 0x00, 0x00])
            await self.ref.device.notify_subscribers(report_characteristic)
            self.logger.info("[DUT] Wait for key %s down", android_key_code.name)
            async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
                await input_monitor.wait_for_event(["EV_KEY", f"KEY_{hid_key_code.name}", "DOWN"])

            self.logger.info("[REF] Release HID key %s", hid_key_code.name)
            report_characteristic.value = bytes(8)

            self.logger.info("[DUT] Wait for key %s up", android_key_code.name)
            await self.ref.device.notify_subscribers(report_characteristic)
            async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
                await input_monitor.wait_for_event(["EV_KEY", f"KEY_{hid_key_code.name}", "UP"])

    async def test_mouse_click(self) -> None:
        """Tests the HID mouse click.

    Test steps:
      1. Leverage the test_connect() to establish the connection.
      2. Press primary button and wait for button press.
      3. Release primary button and wait for button down.
    """
        # Leverage the test_connect() to establish the connection.
        await self.test_connect()
        report_characteristic = self.ref_mouse_input_report_characteristic

        input_monitor = await input_utils.InputMonitor.create(self.dut.device.serial)
        self.test_case_context.push(input_monitor)

        self.logger.info("[DUT] Wait for input ready")
        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            await input_monitor.wait_for_event(["Bumble Mouse"])

        self.logger.info("[REF] Press Primary button")
        report_characteristic.value = struct.pack("<BhhB", 0x01, 0, 0, 0)
        await self.ref.device.notify_subscribers(report_characteristic)

        self.logger.info("[DUT] Wait for button press")
        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            await input_monitor.wait_for_event(["EV_KEY", "BTN_MOUSE", "DOWN"])

        self.logger.info("[REF] Release Primary button")
        report_characteristic.value = struct.pack("<BhhB", 0x00, 0, 0, 0)
        await self.ref.device.notify_subscribers(report_characteristic)

        self.logger.info("[DUT] Wait for button up")
        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            await input_monitor.wait_for_event(["EV_KEY", "BTN_MOUSE", "UP"])

    async def test_mouse_movement(self) -> None:
        """Tests the HID mouse movement.

    Test steps:
      1. Leverage the test_connect() to establish the connection.
      2. Move on X axis and wait for hover movement.
      3. Move on Y axis and wait for hover movement.
    """
        # Leverage the test_connect() to establish the connection.
        await self.test_connect()
        report_characteristic = self.ref_mouse_input_report_characteristic

        input_monitor = await input_utils.InputMonitor.create(self.dut.device.serial)
        self.test_case_context.push(input_monitor)

        self.logger.info("[DUT] Wait for input ready")
        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            await input_monitor.wait_for_event(["Bumble Mouse"])

        self.logger.info("[REF] Move on X axis")
        report_characteristic.value = struct.pack("<BhhB", 0, 1, 0, 0)
        await self.ref.device.notify_subscribers(report_characteristic)

        self.logger.info("[DUT] Wait for hover movement")
        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            await input_monitor.wait_for_event(["EV_REL", " REL_X"])

        self.logger.info("[REF] Move on Y axis")
        report_characteristic.value = struct.pack("<BhhB", 0x00, 0, 1, 0)
        await self.ref.device.notify_subscribers(report_characteristic)

        self.logger.info("[DUT] Wait for hover movement")
        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            await input_monitor.wait_for_event(["EV_REL", " REL_Y"])


if __name__ == "__main__":
    test_runner.main()
