# Copyright 2023 Google LLC
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

import argparse
import asyncio
import logging
import json

from bumble import pandora as bumble_server
from bumble.pandora import PandoraDevice, Config, serve

from bumble_experimental.asha import AshaService
from bumble_experimental.dck import DckService
from bumble_experimental.gatt import GATTService

from pandora_experimental.asha_grpc_aio import add_AshaServicer_to_server
from pandora_experimental.dck_grpc_aio import add_DckServicer_to_server
from pandora_experimental.gatt_grpc_aio import add_GATTServicer_to_server

from typing import Dict, Any

BUMBLE_SERVER_GRPC_PORT = 7999
ROOTCANAL_PORT_CUTTLEFISH = 7300


def main(grpc_port: int, rootcanal_port: int, transport: str, config: str) -> None:
    register_experimental_services()
    if '<rootcanal-port>' in transport:
        transport = transport.replace('<rootcanal-port>', str(rootcanal_port))

    bumble_config = retrieve_config(config)
    bumble_config.setdefault('transport', transport)
    device = PandoraDevice(bumble_config)

    server_config = Config()
    server_config.load_from_dict(bumble_config.get('server', {}))

    logging.basicConfig(level=logging.DEBUG,
                        format='%(asctime)s.%(msecs).03d %(levelname)-8s %(message)s',
                        datefmt='%m-%d %H:%M:%S')
    asyncio.run(serve(device, config=server_config, port=grpc_port))


def args_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Bumble command-line tool")

    parser.add_argument('--grpc-port', type=int, default=BUMBLE_SERVER_GRPC_PORT, help='gRPC port to serve')
    parser.add_argument('--rootcanal-port', type=int, default=ROOTCANAL_PORT_CUTTLEFISH, help='Rootcanal TCP port')
    parser.add_argument('--transport',
                        type=str,
                        default='tcp-client:127.0.0.1:<rootcanal-port>',
                        help='HCI transport (default: tcp-client:127.0.0.1:<rootcanal-port>)')
    parser.add_argument('--config', type=str, help='Bumble json configuration file')

    return parser


def register_experimental_services():
    bumble_server.register_servicer_hook(
        lambda bumble, _, server: add_AshaServicer_to_server(AshaService(bumble.device), server))
    bumble_server.register_servicer_hook(
        lambda bumble, _, server: add_DckServicer_to_server(DckService(bumble.device), server))
    bumble_server.register_servicer_hook(
        lambda bumble, _, server: add_GATTServicer_to_server(GATTService(bumble.device), server))


def retrieve_config(config: str) -> Dict[str, Any]:
    if not config:
        return {}

    with open(config, 'r') as f:
        return json.load(f)


if __name__ == '__main__':
    args = args_parser().parse_args()
    main(**vars(args))
