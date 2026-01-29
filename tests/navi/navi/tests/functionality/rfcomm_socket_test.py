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
import contextlib
import datetime
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
_PENDING_CONNECTION_WAIT_SECONDS = 3.0

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

    async def _setup_pairing(self) -> None:
        ref_dut_acl = await self.classic_connect_and_pair()

        # Terminate ACL connection after pairing.
        with self.dut.bl4a.register_callback(bl4a_api.Module.ADAPTER) as dut_cb:
            # Disconnection may "fail" if the ACL is already disconnecting or
            # disconnected.
            with contextlib.suppress(core.BaseBumbleError):
                await ref_dut_acl.disconnect()
            self.logger.info("[DUT] Wait for disconnected.")
            await dut_cb.wait_for_event(bl4a_api.AclDisconnected)

        # Wait for 2 seconds to let controllers become idle.
        await asyncio.sleep(datetime.timedelta(seconds=2).total_seconds())

    def _setup_rfcomm_server_on_ref(
        self,
        service_record_handle: int,
        rfcomm_uuid: str,
        rfcomm_server: rfcomm.Server,
    ) -> asyncio.Queue[rfcomm.DLC]:
        """Sets up RFCOMM server on REF and returns a queue for its result.

    Args:
      service_record_handle: The service record handle for the RFCOMM server.
      rfcomm_uuid: The UUID of the RFCOMM server.
      rfcomm_server: The RFCOMM server to listen on.

    Returns:
      An asyncio queue for the RFCOMM server's incoming DLC.
    """
        accept_queue = asyncio.Queue[rfcomm.DLC](maxsize=1)
        rfcomm_channel = rfcomm_server.listen(acceptor=accept_queue.put_nowait,)
        self.logger.info(
            "[REF] Create RFCOMM socket server with rfcomm_uuid %s.",
            rfcomm_uuid,
        )

        self.ref.device.sdp_service_records[service_record_handle] = (
            rfcomm.make_service_sdp_records(
                service_record_handle=service_record_handle,
                channel=rfcomm_channel,
                uuid=core.UUID(rfcomm_uuid),
            ))

        return accept_queue

    @navi_test_base.parameterized(1, 2)
    async def test_rfcomm_socket_connections_simultaneously(self, num_connections: int) -> None:
        """Tests one or two RFCOMM socket connections simultaneously.

    Typical duration: 30-50s.

    Test steps:
      1. Create RFCOMM sockets server on REF.
      2. Pair DUT and REF.
      3. Create RFCOMM sockets connection from DUT to REF.
      4. Verify RFCOMM sockets connection are successful.

    Args:
      num_connections: The number of RFCOMM socket connections to create.
    """
        # Initialize RFCOMM sockets server on REF.
        rfcomm_server = rfcomm.Server(self.ref.device)

        # Create RFCOMM sockets server on REF.
        rfcomm_uuid_list = [str(uuid.uuid4()) for _ in range(num_connections)]
        ref_accept_queues = [
            self._setup_rfcomm_server_on_ref(i, rfcomm_uuid, rfcomm_server)
            for i, rfcomm_uuid in enumerate(rfcomm_uuid_list)
        ]

        # Pair DUT and REF.
        await self._setup_pairing()

        # Create RFCOMM sockets connection from DUT to REF.
        rfcomm_sockets = [
            self.dut.bl4a.create_rfcomm_channel_async(
                address=self.ref.address,
                secure=True,
                uuid=rfcomm_uuid,
            ) for rfcomm_uuid in rfcomm_uuid_list
        ]

        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            # Wait for all RFCOMM sockets connection to complete, and get the results.
            self.logger.info("[REF] Wait for all RFCOMM connections to be accepted.")
            server_accept_results = await asyncio.gather(*[q.get() for q in ref_accept_queues])
            self.logger.info("[DUT] Wait for all RFCOMM connections to complete.")
            await asyncio.gather(
                *[rfcomm_socket.wait_for_connected() for rfcomm_socket in rfcomm_sockets])
            self.logger.info("[DUT] All RFCOMM connections completed.")

            # Verify both RFCOMM sockets connection are successful.
            for dlc_result in server_accept_results:
                self.logger.info("dlc_result: %s", dlc_result)
                self.assertEqual(
                    dlc_result.state,
                    rfcomm.DLC.State.CONNECTED,
                    "DLC connection failed. Expected state: CONNECTED, but got:"
                    f" {dlc_result.state.name}",
                )

    async def test_concurrent_rfcomm_connect_fail_raises_exception(self,) -> None:
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
        rfcomm_sockets: list[bl4a_api.RfcommChannel] = []

        rfcomm_server = rfcomm.Server(self.ref.device)
        for i in range(2):
            # Create RFCOMM sockets server on REF.
            rfcomm_channel = rfcomm_server.listen(acceptor=ref_accept_future.set_result,)
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
            rfcomm_sockets.append(
                self.dut.bl4a.create_rfcomm_channel_async(
                    address=self.ref.address,
                    secure=True,
                    uuid=rfcomm_uuid,
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
                for rfcomm_socket in rfcomm_sockets:
                    with self.assertRaises(errors.ConnectionError):
                        await rfcomm_socket.wait_for_connected()

    async def test_rfcomm_connect_after_page_timeout(self) -> None:
        """Tests RFCOMM connect after page timeout.

    Typical duration: 30-60s.

    Test steps:
      1. Pair DUT and REF.
      2. Disable inquiry and page scan of REF.
      4. Create and connect to an RFCOMM socket - expect not connected because
      of REF non-connectable.
      5. Wait 3 seconds.
      6. Before page timeout of 5 seconds, close the socket.
      7. Enable inquiry and page scan of REF.
      8. Create and connect to an RFCOMM socket - verify proper should be
      connected.
    """
        # Pair DUT and REF.
        await self._setup_pairing()

        # Set REF to be non-connectable and non-discoverable.
        self.logger.info("[REF] Setting device to be non-connectable.")
        await self.ref.device.set_discoverable(False)
        await self.ref.device.set_connectable(False)

        # Create RFCOMM sockets server on REF.
        rfcomm_server = rfcomm.Server(self.ref.device)
        rfcomm_uuid = str(uuid.uuid4())
        accept_queue = self._setup_rfcomm_server_on_ref(0, rfcomm_uuid, rfcomm_server)

        self.logger.info("[DUT] Attempting to connect to non-connectable REF (expecting to"
                         " hang).")
        rfcomm_socket = self.dut.bl4a.create_rfcomm_channel_async(
            address=self.ref.address,
            secure=False,
            uuid=rfcomm_uuid,
        )

        # For Android device, the page timeout is 5 seconds.
        # Wait for 3 seconds before page timeout of 5 seconds
        await asyncio.sleep(_PENDING_CONNECTION_WAIT_SECONDS)

        # Close the RFCOMM socket.
        await rfcomm_socket.close()

        # Set REF to be connectable and discoverable.
        self.logger.info("[REF] Setting device to be connectable and discoverable.")
        await self.ref.device.set_discoverable(True)
        await self.ref.device.set_connectable(True)

        self.logger.info("[DUT] Connect RFCOMM channel to REF.")
        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS * 2):
            ref_dut_dlc, dut_ref_dlc = await asyncio.gather(
                accept_queue.get(),
                self.dut.bl4a.create_rfcomm_channel(
                    address=self.ref.address,
                    secure=False,
                    uuid=rfcomm_uuid,
                ),
            )

        self.logger.info("[DUT] Verify RFCOMM channel is connected.")
        self.assertEqual(
            ref_dut_dlc.state,
            rfcomm.DLC.State.CONNECTED,
            "DLC connection failed. Expected state: CONNECTED, but got:"
            f" {ref_dut_dlc.state.name}",
        )

        self.logger.info("[DUT] Disconnect RFCOMM channel.")
        await dut_ref_dlc.close()


if __name__ == "__main__":
    test_runner.main()
