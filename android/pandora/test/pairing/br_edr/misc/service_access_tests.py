# Copyright 2024 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import asyncio
import logging

from pairing.br_edr.test_base import BREDRPairTestBase

from avatar import BumblePandoraDevice, PandoraDevice, PandoraDevices
from avatar.aio import asynchronous
from bumble.colors import color
from bumble.core import BT_L2CAP_PROTOCOL_ID, BT_RFCOMM_PROTOCOL_ID, UUID, ConnectionError, ProtocolError
from bumble.hci import Address
from bumble.l2cap import ClassicChannelSpec
from bumble.rfcomm import Client

from bumble.sdp import (
    SDP_ADDITIONAL_PROTOCOL_DESCRIPTOR_LIST_ATTRIBUTE_ID,
    SDP_ALL_ATTRIBUTES_RANGE,
    SDP_BROWSE_GROUP_LIST_ATTRIBUTE_ID,
    SDP_PROTOCOL_DESCRIPTOR_LIST_ATTRIBUTE_ID,
    SDP_SERVICE_CLASS_ID_LIST_ATTRIBUTE_ID,
    SDP_SERVICE_RECORD_HANDLE_ATTRIBUTE_ID,
    Client as SDPClient,
)

from mobly.asserts import assert_equal, assert_is_not_none, assert_raises, fail
from pandora_experimental.rfcomm_grpc_aio import RFCOMM as AioRFCOMM
from pandora.security_pb2 import PairingEventAnswer


class ServiceAccessTempBondingTest(BREDRPairTestBase):  # type: ignore[misc]
    """
    This test verifies that access to services (on BR/EDR transport) from
    a peer device via a temporary bonding is properly arbitrated.
    """

    def _setup_devices(self):
        self.ref.config.setdefault('classic_enabled', True)
        self.ref.config.setdefault('classic_ssp_enabled', True)
        self.ref.config.setdefault('classic_sc_enabled', True)
        self.ref.config.setdefault(
            'server',
            {
                'io_capability': 'no_output_no_input',
                # create a temp bonding
                'pairing_bonding_enable': False,
                'pairing_mitm_enable': False,
                'pairing_sc_enable': True,
            },
        )

    async def start_service_access(
        self,
        initiator_acl_connection,
        responder_acl_connection,
    ):
        '''
        this method is not used in this test class
        '''
        pass

    async def accept_pairing(self):
        try:
            bumble_ev = await anext(self.bumble_pairing_stream)
            assert_equal(bumble_ev.method_variant(), 'just_works')
            bumble_ev_answer = PairingEventAnswer(event=bumble_ev, confirm=True)

            # accept the pairing from bumble
            self.bumble_pairing_stream.send_nowait(bumble_ev_answer)

            # pairing on android side is auto-accepted
            # so no pairing event will be triggered here
            # ignore it now
        except:
            fail('no exception should have happened during pairing')

    @asynchronous
    async def setup_test(self) -> None:
        await asyncio.gather(self.dut.reset(), self.ref.reset())

        self.acl_initiator = self.ref
        self.acl_responder = self.dut
        self.pairing_initiator = self.ref
        self.pairing_responder = self.dut

        self.prepare_pairing()

        # first initiate an ACL connection from bumble to android
        bumble_res, android_res = await self.start_acl_connection()

        pairing_task = asyncio.create_task(
            self.start_pairing(
                initiator_acl_connection=bumble_res.connection,
                responder_acl_connection=android_res.connection,
            ))
        await self.accept_pairing()
        await asyncio.wait_for(pairing_task, timeout=10.0)

        android_addr = Address.from_string_for_transport(str(self.dut.address),
                                                         Address.PUBLIC_DEVICE_ADDRESS)
        self.bumble_raw_acl_connection = self.ref.device.find_connection_by_bd_addr(android_addr)

    @asynchronous
    async def test_access_sdp_service(self):
        sdp_psm = 0x0001
        sdp_channel = self.bumble_raw_acl_connection.create_l2cap_channel(spec=ClassicChannelSpec(
            psm=sdp_psm))
        try:
            _ = await sdp_channel
        except:
            fail("access to SDP service should be allowed")

    @asynchronous
    async def test_access_rfcomm_service(self):
        rfc_psm = 0x0003
        rfcomm_channel = self.bumble_raw_acl_connection.create_l2cap_channel(
            spec=ClassicChannelSpec(psm=rfc_psm))
        try:
            _ = await rfcomm_channel
        except:
            fail("access to RFCOMM service should be allowed")

    @asynchronous
    async def test_access_rfcomm_mx_secure_service(self):
        rfcomm_client = Client(self.bumble_raw_acl_connection)
        rfcomm_mux = await rfcomm_client.start()

        # hfp rfcomm mx service
        # it is a secure service exposed in the layer of rfcomm
        # access should be blocked
        hfp_rfcomm_chan = 0x0002
        with assert_raises(ConnectionError):
            _ = await rfcomm_mux.open_dlc(hfp_rfcomm_chan)

    def _parse_rfcomm_channel_from_sdp_service_attributes(self, attributes):
        '''
        The SDP_PROTOCOL_DESCRIPTOR_LIST_ATTRIBUTE_ID attribute of an insecure
        rfcomm service record should look like this
        id=SDP_PROTOCOL_DESCRIPTOR_LIST_ATTRIBUTE_ID,
        value=SEQUENCE([
                SEQUENCE([UUID(UUID-16:0100 (L2CAP))]),
                SEQUENCE([
                    UUID(UUID-16:0003 (RFCOMM)),
                    UNSIGNED_INTEGER(7#1)])
                ])
        '''

        for attribute in attributes:
            print(f"attribute: {attribute.to_string(with_colors=True)}")
            if attribute.id == SDP_PROTOCOL_DESCRIPTOR_LIST_ATTRIBUTE_ID and len(
                    attribute.value.value) >= 2:
                proto0 = attribute.value.value[0]
                proto1 = attribute.value.value[1]

                if proto0.value[0].value == BT_L2CAP_PROTOCOL_ID and proto1.value[
                        0].value == BT_RFCOMM_PROTOCOL_ID:
                    return proto1.value[1].value

        return None

    async def _lookup_rfcomm_channel_with_sdp(self, uuid):
        sdp_client = SDPClient(self.bumble_raw_acl_connection)
        await sdp_client.connect()

        service_record_handles = await sdp_client.search_services([UUID(uuid)])

        if len(service_record_handles) < 1:
            await sdp_client.disconnect()
            raise Exception(color(f'service not found on peer device!!!!', 'red'))

        ret = None
        for service_record_handle in service_record_handles:
            attributes = await sdp_client.get_attributes(service_record_handle,
                                                         [SDP_ALL_ATTRIBUTES_RANGE])

            print(color(f'SERVICE {service_record_handle:04X} attributes:', 'yellow'))
            ret = self._parse_rfcomm_channel_from_sdp_service_attributes(attributes)
            if ret is None:
                continue
            else:
                break

        assert_is_not_none(ret)
        await sdp_client.disconnect()
        return ret

    @asynchronous
    async def test_access_rfcomm_mx_insecure_service(self):
        uuid = "F6FB4732-A802-487D-A9FA-9664D5C91F13"
        name = "test_rfcomm_server"

        # StartServer implementation on Android uses
        # listenUsingInsecureRfcommWithServiceRecord
        dut_rfcomm = AioRFCOMM(self.dut.aio.channel)
        _ = dut_rfcomm.StartServer(name=name, uuid=uuid)

        rfc_channel = await self._lookup_rfcomm_channel_with_sdp(uuid)
        rfcomm_client = Client(self.bumble_raw_acl_connection)
        rfcomm_mux = await rfcomm_client.start()

        try:
            _ = await rfcomm_mux.open_dlc(rfc_channel)
        except:
            fail("access to insecure rfcomm service should be allowed")

    @asynchronous
    async def test_access_hid_control_service(self):
        # HID control service (secure)
        # should be blocked
        with assert_raises(ProtocolError):
            hid_control_psm = 0x0011
            connector_hid_control = self.bumble_raw_acl_connection.create_l2cap_channel(
                spec=ClassicChannelSpec(psm=hid_control_psm))
            _ = await connector_hid_control

    @asynchronous
    async def test_access_hid_interrupt_service(self):
        # HID interrupt service (secure)
        # should be blocked
        with assert_raises(ProtocolError):
            hid_interrupt_psm = 0x0013
            connector_hid_interrupt = self.bumble_raw_acl_connection.create_l2cap_channel(
                spec=ClassicChannelSpec(psm=hid_interrupt_psm))
            _ = await connector_hid_interrupt
