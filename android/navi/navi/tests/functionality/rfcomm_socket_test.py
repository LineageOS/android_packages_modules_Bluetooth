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
from typing import Coroutine
from unittest import mock
import uuid

from bumble import core
from bumble import device
from bumble import l2cap
from bumble import pairing
from bumble import rfcomm
from bumble import smp
from mobly import test_runner
from typing_extensions import override

from navi.tests import navi_test_base
from navi.utils import bl4a_api
from navi.utils import errors

_DEFAULT_STEP_TIMEOUT_SECONDS = 5.0

_PairingDelegate = pairing.PairingDelegate


class RfcommSocketTest(navi_test_base.TwoDevicesTestBase):

    @override
    async def async_setup_test(self) -> None:
        await super().async_setup_test()

        # Using highest authentication level to allow secure sockets.
        def pairing_config_factory(connection: device.Connection,) -> pairing.PairingConfig:
            del connection  # Unused parameter.
            return pairing.PairingConfig(delegate=_PairingDelegate(
                io_capability=(_PairingDelegate.IoCapability.DISPLAY_OUTPUT_AND_YES_NO_INPUT)))

        self.ref.device.pairing_config_factory = pairing_config_factory
        # Disable CTKD.
        self.ref.device.l2cap_channel_manager.deregister_fixed_channel(smp.SMP_BR_CID)
        # Clear SDP records.
        self.ref.device.sdp_service_records.clear()

    async def test_concurrent_rfcomm_connect_fail_raises_exception(self) -> None:
        """Tests concurrent RFCOMM connect fail should raises exception.

    Typical duration: 30-60s.

    Test steps:
      1. Create TWO RFCOMM sockets server on REF.
      2. Connect TWO RFCOMM sockets from DUT to REF at the same time.
      3. Reject the Rfcomm connection request on REF by l2cap connection
      request with No resources available.
      4. Verify the DUT can catch the exceptions raised for both RFCOMM
      connections .
    """
        # TODO: Remove this skip once the flag is removed.
        if not self.dut.bluetooth_flags.get("fix_socket_connection_failed_no_callback", False):
            self.skipTest("Skip until the fix flag(ag/33721603) is enabled.")

        original_on_l2cap_connection_request = (
            self.ref.device.l2cap_channel_manager.on_l2cap_connection_request)

        def custom_on_l2cap_connection_request(
            connection: device.Connection,
            cid: int,
            request: l2cap.L2CAP_Connection_Request,
        ) -> None:
            self.logger.info(" _custom_on_l2cap_connection_request:: psm: %s", request.psm)

            if request.psm == rfcomm.RFCOMM_PSM:
                self.logger.info(" RFCOMM L2CAP connection request rejected")
                self.ref.device.l2cap_channel_manager.send_control_frame(
                    connection,
                    cid,
                    l2cap.L2CAP_Connection_Response(
                        identifier=request.identifier,
                        destination_cid=0,
                        source_cid=request.source_cid,
                        result=l2cap.L2CAP_Connection_Response.Result.
                        CONNECTION_REFUSED_NO_RESOURCES_AVAILABLE,
                        status=0x0000,
                    ),
                )
            else:
                original_on_l2cap_connection_request(connection, cid, request)

        # Replace the original on_l2cap_connection_request with the custom one.
        self.ref.device.l2cap_channel_manager.on_l2cap_connection_request = (
            custom_on_l2cap_connection_request)

        ref_accept_future = asyncio.get_running_loop().create_future()
        rfcomm_connection_coroutines_list: list[Coroutine[None, None, bl4a_api.RfcommChannel]] = []

        rfcomm_server = rfcomm.Server(self.ref.device)
        for i in range(2):
            # Create RFCOMM sockets server on REF.
            rfcomm_channel = rfcomm_server.listen(
                acceptor=ref_accept_future.set_result,
                channel=i,
            )
            rfcomm_uuid = str(uuid.uuid4())
            self.logger.info(
                "[REF] Create %d RFCOMM socket server with rfcomm_uuid %s.",
                i,
                rfcomm_uuid,
            )
            self.ref.device.sdp_service_records[i] = rfcomm.make_service_sdp_records(
                service_record_handle=i,
                channel=rfcomm_channel,
                uuid=core.UUID(rfcomm_uuid),
            )

            # Create RFCOMM socket connection from DUT to REF.
            rfcomm_connection_coroutines_list.append(
                self.dut.bl4a.create_rfcomm_channel_async(
                    address=self.ref.address,
                    secure=True,
                    channel_or_uuid=rfcomm_uuid,
                ))

        # Await the pairing request from DUT and accept the request.
        with self.dut.bl4a.register_callback(bl4a_api.Module.ADAPTER) as dut_cb:
            self.logger.info("[DUT] Wait for pairing request.")
            await dut_cb.wait_for_event(
                bl4a_api.PairingRequest(address=self.ref.address, variant=mock.ANY, pin=mock.ANY),
                timeout=10,
            )

            # Wait for 5 seconds for:
            #   1. Simulate user interaction delay with a pop-up.
            #   2. Ensures both RFCOMM sockets complete SDP, creating an L2CAP
            #     connection collision.
            #   3. RFCOMM L2CAP connection request will be pending due to incomplete
            #     encryption.
            self.logger.info("[DUT] setPairingConfirmation Wait for 5 seconds.")
            await asyncio.sleep(_DEFAULT_STEP_TIMEOUT_SECONDS)
            self.assertTrue(self.dut.bt.setPairingConfirmation(self.ref.address, True))

            # wait for both RFCOMM sockets connection to fail.
            async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
                for coro in rfcomm_connection_coroutines_list:
                    with self.assertRaises(errors.SnippetError):
                        await coro


if __name__ == "__main__":
    test_runner.main()
