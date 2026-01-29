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
import datetime
from unittest import mock

from bumble import core
from bumble import hci
from mobly import test_runner

from navi.tests import navi_test_base
from navi.utils import android_constants
from navi.utils import bl4a_api
from navi.utils import pyee_extensions

_DEFAULT_DISCOVER_TIMEOUT = 15


class ClassicHostTest(navi_test_base.TwoDevicesTestBase):

    @navi_test_base.retry(max_count=2)
    async def test_outgoing_classic_acl(self) -> None:
        """Test outgoing Classic ACL connection.

    Test steps:
      1. Create bond from DUT.
      2. Accept connection from REF.
      3. Wait for ACL connected on DUT.
      4. Cancel bond from DUT.
      5. Wait for ACL disconnected on DUT.
    """
        with self.dut.bl4a.register_callback(bl4a_api.Module.ADAPTER) as dut_cb:
            self.logger.info("[DUT] Create bond.")
            self.dut.bt.createBond(self.ref.address, android_constants.Transport.CLASSIC)

            self.logger.info("[REF] Accept connection.")
            await self.ref.device.accept(
                f"{self.dut.address}/P",
                timeout=datetime.timedelta(seconds=15).total_seconds(),
            )

            self.logger.info("[DUT] Wait for ACL connected.")
            await dut_cb.wait_for_event(
                bl4a_api.AclConnected(
                    address=self.ref.address,
                    transport=android_constants.Transport.CLASSIC,
                ),)
            # disconnect() doesn"t work, because it only remove profile connections.
            self.logger.info("[DUT] Cancel bond.")
            self.dut.bt.cancelBond(self.ref.address)

            self.logger.info("[DUT] Wait for ACL disconnected.")
            await dut_cb.wait_for_event(
                bl4a_api.AclDisconnected(
                    address=self.ref.address,
                    transport=android_constants.Transport.CLASSIC,
                ),
                timeout=datetime.timedelta(seconds=30),
            )

    @navi_test_base.retry(max_count=2)
    async def test_incoming_classic_acl(self) -> None:
        """Test incoming Classic ACL connection.

    Test steps:
      1. Create connection from REF.
      2. Wait for ACL connected on DUT.
      3. Disconnect from REF.
      4. Wait for ACL disconnected DUT.
    """
        with self.dut.bl4a.register_callback(bl4a_api.Module.ADAPTER) as dut_cb:
            self.logger.info("[REF] Create connection.")
            ref_dut_acl = await self.ref.device.connect(f"{self.dut.address}/P",
                                                        transport=core.BT_BR_EDR_TRANSPORT)

            self.logger.info("[DUT] Wait for ACL connected.")
            await dut_cb.wait_for_event(
                bl4a_api.AclConnected(
                    address=self.ref.address,
                    transport=android_constants.Transport.CLASSIC,
                ),)

            self.logger.info("[REF] Disconnect.")
            await ref_dut_acl.disconnect()

            self.logger.info("[DUT] Wait for ACL disconnected.")
            await dut_cb.wait_for_event(
                bl4a_api.AclDisconnected(
                    address=self.ref.address,
                    transport=android_constants.Transport.CLASSIC,
                ),)

    @navi_test_base.retry(max_count=2)
    async def test_inquiry(self) -> None:
        """Test inquiry.

    Test steps:
      1. Set REF in discoverable mode.
      2. Start discovery on DUT.
      3. Wait for DUT discovered.
    """
        with self.dut.bl4a.register_callback(bl4a_api.Module.ADAPTER) as dut_cb:
            self.logger.info("[REF] Set discoverable.")
            await self.ref.device.set_discoverable(True)

            self.logger.info("[DUT] Start inquiry.")
            self.dut.bt.startInquiry()

            self.logger.info("[DUT] Wait for DUT discovered.")
            await dut_cb.wait_for_event(
                bl4a_api.DeviceFound(address=self.ref.address, name=mock.ANY))

    async def test_discoverable(self) -> None:
        """Test ref discover DUT.

    Test steps:
      1. Set DUT in discoverable mode.
      2. Start discovery on REF.
      3. Wait for DUT discovered.
    """
        self.logger.info("[DUT] Set scan mode to CONNECTABLE_DISCOVERABLE.")
        self.dut.bt.setScanMode(android_constants.ScanMode.CONNECTABLE_DISCOVERABLE)

        with pyee_extensions.EventWatcher() as watcher:
            inquiry = asyncio.Event()

            @watcher.on(self.ref.device, "inquiry_result")
            def on_inquiry_result(address: hci.Address, *_) -> None:
                if address == hci.Address(f"{self.dut.address}/P"):
                    inquiry.set()

            self.logger.info("[REF] Start discovery.")
            await self.ref.device.start_discovery()

            self.logger.info("[REF] Wait for DUT discover timeout.")
            async with self.assert_not_timeout(_DEFAULT_DISCOVER_TIMEOUT):
                await inquiry.wait()

    async def test_not_discoverable(self) -> None:
        """Test ref can not discover DUT.

    Test steps:
      1. Set DUT scan mode to NONE.
      2. Start discovery on REF.
      3. Wait for DUT discovered timeout.
    """
        self.logger.info("[DUT] Set scan mode to NONE.")
        self.dut.bt.setScanMode(android_constants.ScanMode.NONE)

        with pyee_extensions.EventWatcher() as watcher:
            inquiry = asyncio.Event()

            @watcher.on(self.ref.device, "inquiry_result")
            def on_inquiry_result(address: hci.Address, *_) -> None:
                if address == hci.Address(f"{self.dut.address}/P"):
                    inquiry.set()

            self.logger.info("[REF] Start discovery.")
            await self.ref.device.start_discovery()

            self.logger.info("[REF] Wait for DUT discover timeout.")
            async with self.assert_timeout(_DEFAULT_DISCOVER_TIMEOUT):
                await inquiry.wait()


if __name__ == "__main__":
    test_runner.main()
