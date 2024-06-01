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

import asyncio
import logging
from typing import Dict, Optional

from bumble import core
from bumble.device import Device
from bumble.rfcomm import (
    Server,
    make_service_sdp_records,
    DLC,
)
from bumble.pandora import utils
import grpc
from pandora_experimental.rfcomm_grpc_aio import RFCOMMServicer
from pandora_experimental.rfcomm_pb2 import (
    AcceptConnectionRequest,
    AcceptConnectionResponse,
    ConnectionRequest,
    ConnectionResponse,
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


class RFCOMMService(RFCOMMServicer):
    #TODO Add support for multiple servers
    device: Device
    server_id: Optional[ServerId]
    server: Optional[Server]

    def __init__(self, device: Device) -> None:
        super().__init__()
        self.device = device
        self.server_id = None
        self.server = None
        self.server_name = None
        self.server_uuid = None
        self.connections = {}  # key = id, value = dlc
        self.next_server_id = 1
        self.next_conn_id = 1
        self.open_channel = None
        self.wait_dlc = None
        self.dlc = None
        self.data_queue = asyncio.Queue()

    @utils.rpc
    async def StartServer(self, request: StartServerRequest, context: grpc.ServicerContext) -> StartServerResponse:
        logging.info(f"StartServer")
        if self.server_id:
            logging.warning(f"Server already started, returning existing server")
            return StartServerResponse(server=self.server_id)
        else:
            self.server_id = ServerId(id=self.next_server_id)
            self.next_server_id += 1
            self.server = Server(self.device)
            self.server_name = request.name
            self.server_uuid = core.UUID(request.uuid)
        self.wait_dlc = asyncio.get_running_loop().create_future()
        handle = 1
        #TODO Add support for multiple clients
        self.open_channel = self.server.listen(acceptor=self.wait_dlc.set_result, channel=2)
        records = make_service_sdp_records(handle, self.open_channel, self.server_uuid)
        self.device.sdp_service_records[handle] = records
        return StartServerResponse(server=self.server_id)

    @utils.rpc
    async def AcceptConnection(self, request: AcceptConnectionRequest,
                               context: grpc.ServicerContext) -> AcceptConnectionResponse:
        logging.info(f"AcceptConnection")
        assert self.server_id.id == request.server.id
        self.dlc = await self.wait_dlc
        self.dlc.sink = self.data_queue.put_nowait
        new_conn = RfcommConnection(id=self.next_conn_id)
        self.next_conn_id += 1
        self.connections[new_conn.id] = self.dlc
        return AcceptConnectionResponse(connection=new_conn)

    @utils.rpc
    async def StopServer(self, request: StopServerRequest, context: grpc.ServicerContext) -> StopServerResponse:
        logging.info(f"StopServer")
        assert self.server_id.id == request.server.id
        self.server = None
        self.server_id = None
        self.server_name = None
        self.server_uuid = None

        return StopServerResponse()

    @utils.rpc
    async def Send(self, request: TxRequest, context: grpc.ServicerContext) -> TxResponse:
        logging.info(f"Send")
        dlc = self.connections[request.connection.id]
        dlc.write(request.data)
        return TxResponse()

    @utils.rpc
    async def Receive(self, request: RxRequest, context: grpc.ServicerContext) -> RxResponse:
        logging.info(f"Receive")
        received_data = await self.data_queue.get()
        return RxResponse(data=received_data)
