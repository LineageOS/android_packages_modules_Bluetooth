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
"""Tests for HID Device over GATT Profile(GATT) implementation on Android."""
import asyncio
import datetime

from bumble import hid
from mobly import test_runner
from mobly import signals
from typing_extensions import override

from navi.bumble_ext import hid as hid_ext
from navi.tests import navi_test_base
from navi.utils import android_constants
from navi.utils import bl4a_api
from navi.utils import constants

_DEFAULT_STEP_TIMEOUT = datetime.timedelta(seconds=10)
_DEFAULT_STEP_TIMEOUT_SECONDS = _DEFAULT_STEP_TIMEOUT.total_seconds()


class HidDeviceTest(navi_test_base.TwoDevicesTestBase):
    ref_hid_server: hid_ext.Server[hid_ext.HostProtocol]
    ref_hid_host: hid_ext.HostProtocol

    def _setup_hid_service(self) -> None:
        self.ref_hid_server = hid_ext.Server(self.ref.device, hid_ext.HostProtocol)

    @override
    async def async_setup_class(self) -> None:
        await super().async_setup_class()
        if (self.dut.device.adb.getprop(android_constants.Property.HID_DEVICE_ENABLED) != "true"):
            raise signals.TestAbortClass("HID device is not supported on DUT")

    async def _setup_connection(self) -> bl4a_api.CallbackHandler:
        self._setup_hid_service()

        self.logger.info("[DUT] Register HID Device App")
        dut_hid_cb = self.dut.bl4a.register_hid_device_app()
        self.test_case_context.push(dut_hid_cb)

        self.logger.info("[DUT] Pair with REF")
        await self.classic_connect_and_pair(direction=constants.Direction.INCOMING)

        self.logger.info("[DUT] Connect to HID Device")
        self.dut.bt.hidDeviceConnect(self.ref.address)

        self.logger.info("[DUT] Wait for HID Device connected")
        await dut_hid_cb.wait_for_event(
            bl4a_api.ProfileConnectionStateChanged(
                address=self.ref.address,
                state=android_constants.ConnectionState.CONNECTED,
            ),)

        self.logger.info("[REF] Wait for HID Host connected")
        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            self.ref_hid_host = await self.ref_hid_server.wait_connection()

        return dut_hid_cb

    async def test_connect(self) -> None:
        """Tests establishing the HID connection from DUT to REF.

    Test steps:
      1. Register HID Device App on DUT.
      2. Establish the HID connection between DUT and REF.
      3. Verify the HID connection is established.
    """
        await self._setup_connection()

    async def test_disconnect(self) -> None:
        """Tests reconnecting the HID connection.

    Test steps:
      1. Register HID Device App on DUT.
      2. Establish the HID connection between DUT and REF.
      3. Terminate the connection.
      4. Verify the HID connection is terminated.
    """
        dut_hid_cb = await self._setup_connection()

        self.logger.info("[REF] Disconnect")
        self.dut.bt.hidDeviceDisconnect(self.ref.address)

        self.logger.info("[DUT] Wait for HID Device disconnected")
        await dut_hid_cb.wait_for_event(
            bl4a_api.ProfileConnectionStateChanged(
                address=self.ref.address,
                state=android_constants.ConnectionState.DISCONNECTED,
            ),)

    async def test_unable_to_connect_without_sdp_settings(self) -> None:
        """Tests unable to connect to the HID Device without SDP settings.

    Test steps:
      1. Connect to HID Device without SDP settings.
    """
        self._setup_hid_service()

        self.logger.info("[DUT] Connect to HID Device without SDP settings")
        self.assertFalse(self.dut.bt.hidDeviceConnect(self.ref.address))

    async def test_send_report(self) -> None:
        """Tests sending report data from the HID Device.

    Test steps:
      1. Register HID Device App on DUT.
      2. Establish the HID connection between DUT and REF.
      3. Send report data from the HID Device.
      4. Verify the report data is received by the HID host.
    """
        await self._setup_connection()

        report_queue = asyncio.Queue[bytes]()

        def on_interrupt_pdu(pdu: bytes) -> None:
            report_queue.put_nowait(pdu)

        self.ref_hid_host.on(hid_ext.DeviceProtocol.Event.INTERRUPT_DATA, on_interrupt_pdu)

        self.logger.info("[DUT] Send report")
        self.dut.bt.hidDeviceSendReport(self.ref.address, 0x01, [0x00, 0x01, 0x02, 0x03])

        self.logger.info("[REF] Check report")
        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            report = await report_queue.get()
        self.assertEqual(report, bytes([0xa1, 0x00, 0x01, 0x02, 0x03]))

    async def test_reply_report(self) -> None:
        """Tests replying report data to the HID Device.

    Test steps:
      1. Register HID Device App on DUT.
      2. Establish the HID connection between DUT and REF.
      3. Reply report data to the ref.
      4. Verify the reply report is received.
    """
        await self._setup_connection()

        report_queue = asyncio.Queue[bytes]()

        def on_control_pdu(pdu: bytes) -> None:
            report_queue.put_nowait(pdu)

        self.ref_hid_host.on(hid_ext.DeviceProtocol.Event.CONTROL_DATA, on_control_pdu)

        self.logger.info("[DUT] Reply report")
        self.dut.bt.hidDeviceReplyReport(
            self.ref.address,
            hid.Message.ReportType.INPUT_REPORT,
            0x01,
            [0x00, 0x01, 0x02, 0x03],
        )

        self.logger.info("[REF] Check report")
        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            report = await report_queue.get()
        self.assertSequenceEqual(report, bytes([0xa1, 0x00, 0x01, 0x02, 0x03]))

    async def test_report_error(self) -> None:
        """Tests reporting error data to the HID Device.

    Test steps:
      1. Register HID Device App on DUT.
      2. Establish the HID connection between DUT and REF.
      3. Report error data to the HID Device.
      4. Verify the error is received by the HID Host.
    """
        await self._setup_connection()

        report_queue = asyncio.Queue[bytes]()

        def on_handshake_pdu(pdu: bytes) -> None:
            report_queue.put_nowait(pdu)

        self.ref_hid_host.on(hid_ext.DeviceProtocol.Event.HANDSHAKE, on_handshake_pdu)

        self.logger.info("[DUT] Report error")
        self.dut.bt.hidDeviceReportError(self.ref.address, hid.Message.Handshake.NOT_READY)

        self.logger.info("[REF] Check error")
        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            report = await report_queue.get()
        self.assertEqual(report, hid.Message.Handshake.NOT_READY)


if __name__ == "__main__":
    test_runner.main()
