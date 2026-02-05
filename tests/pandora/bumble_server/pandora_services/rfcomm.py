# Copyright (C) 2024 The Android Open Source Project
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

from __future__ import annotations

import asyncio
import logging
from typing import Optional

from bumble import core
from bumble.device import Device
from bumble.hci import Address
from bumble import rfcomm
from pandora_services import utils
import grpc
from pandora.rfcomm_grpc_aio import RFCOMMServicer
from pandora.rfcomm_pb2 import (
    AcceptConnectionRequest,
    AcceptConnectionResponse,
    ConnectionRequest,
    ConnectionResponse,
    DisconnectionRequest,
    DisconnectionResponse,
    RfcommConnection,
    RxRequest,
    RxResponse,
    ServerId,
    StartServerRequest,
    StartServerResponse,
    StopServerRequest,
    StopServerResponse,
    TxRequest,
    TxResponse,
)

FIRST_SERVICE_RECORD_HANDLE = 0x00010010


class RFCOMMService(RFCOMMServicer):
    device: Device

    def __init__(self, device: Device, server: rfcomm.Server) -> None:
        super().__init__()
        self.server = server
        self.device = device
        self.server_ports: dict[int, RFCOMMService.ServerPort] = {
        }  # key = channel, value = ServerInstance
        self.connections: dict[int, RFCOMMService.Connection] = {}  # key = id, value = dlc
        self.next_conn_id = 1
        self.next_scn = 7

    class Connection:

        client: Optional[rfcomm.Client]

        def __init__(self, dlc, client=None):
            self.dlc = dlc
            self.data_queue = asyncio.Queue()
            self.client = client

    class ServerPort:

        def __init__(self, name, uuid, wait_dlc):
            self.name = name
            self.uuid = uuid
            self.wait_dlc = wait_dlc
            self.accepted = False
            self.saved_dlc = None

        def accept(self):
            self.accepted = True
            if self.saved_dlc is not None:
                self.wait_dlc.set_result(self.saved_dlc)

        def acceptor(self, dlc):
            if self.accepted:
                self.wait_dlc.set_result(dlc)
            else:
                self.saved_dlc = dlc

    @utils.rpc
    async def ConnectToServer(self, request: ConnectionRequest,
                              context: grpc.ServicerContext) -> ConnectionResponse:
        logging.info(f"ConnectToServer")
        address = Address(address=bytes(reversed(request.address)),
                          address_type=Address.PUBLIC_DEVICE_ADDRESS)
        acl_connection = self.device.find_connection_by_bd_addr(address, transport=0)  # BR/EDR
        if acl_connection is None:
            acl_connection = await self.device.connect(address,
                                                       transport=core.PhysicalTransport.BR_EDR
                                                      )  # BR/EDR transport

        channel = await rfcomm.find_rfcomm_channel_with_uuid(acl_connection, request.uuid)
        assert channel is not None

        client = rfcomm.Client(acl_connection)
        mux = await client.start()
        assert mux is not None

        dlc = await mux.open_dlc(channel)
        id = self.next_conn_id
        self.next_conn_id += 1
        self.connections[id] = self.Connection(dlc=dlc, client=client)
        self.connections[id].dlc.sink = self.connections[id].data_queue.put_nowait
        return ConnectionResponse(connection=RfcommConnection(id=id))

    @utils.rpc
    async def StartServer(self, request: StartServerRequest,
                          context: grpc.ServicerContext) -> StartServerResponse:
        uuid = core.UUID(request.uuid)
        logging.info(f"StartServer {uuid}")

        for existing_id, port in self.server_ports.items():
            if port.uuid == uuid:
                logging.warning(f"Server port already started for {uuid}, returning existing port")
                return StartServerResponse(server=ServerId(id=existing_id))

        wait_dlc = asyncio.get_running_loop().create_future()
        server_port = self.ServerPort(name=request.name, uuid=uuid, wait_dlc=wait_dlc)
        open_channel = self.server.listen(acceptor=server_port.acceptor, channel=self.next_scn)
        self.next_scn += 1
        handle = FIRST_SERVICE_RECORD_HANDLE + open_channel
        self.device.sdp_service_records[handle] = rfcomm.make_service_sdp_records(
            handle, open_channel, uuid)
        self.server_ports[open_channel] = server_port
        return StartServerResponse(server=ServerId(id=open_channel))

    @utils.rpc
    async def AcceptConnection(self, request: AcceptConnectionRequest,
                               context: grpc.ServicerContext) -> AcceptConnectionResponse:
        logging.info(f"AcceptConnection")
        assert self.server_ports[request.server.id] is not None
        self.server_ports[request.server.id].accept()
        dlc = await self.server_ports[request.server.id].wait_dlc
        id = self.next_conn_id
        self.next_conn_id += 1
        self.connections[id] = self.Connection(dlc=dlc)
        self.connections[id].dlc.sink = self.connections[id].data_queue.put_nowait
        return AcceptConnectionResponse(connection=RfcommConnection(id=id))

    @utils.rpc
    async def Disconnect(self, request: DisconnectionRequest,
                         context: grpc.ServicerContext) -> DisconnectionResponse:
        logging.info(f"Disconnect")
        rfcomm_connection = self.connections[request.connection.id]
        assert rfcomm_connection is not None
        if rfcomm_connection.client is not None:
            await rfcomm_connection.client.shutdown()
        del rfcomm_connection
        return DisconnectionResponse()

    @utils.rpc
    async def StopServer(self, request: StopServerRequest,
                         context: grpc.ServicerContext) -> StopServerResponse:
        logging.info(f"StopServer")
        assert self.server_ports[request.server.id] is not None
        del self.server_ports[request.server.id]

        return StopServerResponse()

    @utils.rpc
    async def Send(self, request: TxRequest, context: grpc.ServicerContext) -> TxResponse:
        logging.info(f"Send")
        assert self.connections[request.connection.id] is not None
        self.connections[request.connection.id].dlc.write(request.data)
        return TxResponse()

    @utils.rpc
    async def Receive(self, request: RxRequest, context: grpc.ServicerContext) -> RxResponse:
        logging.info(f"Receive")
        assert self.connections[request.connection.id] is not None
        received_data = await self.connections[request.connection.id].data_queue.get()
        return RxResponse(data=received_data)
