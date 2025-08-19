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
import json
import logging
from typing import Any, Dict

import pandora_services
from pandora_services import Config, PandoraDevice, serve
from bumble.rfcomm import Server as RFCOMMServer
from pandora_services.asha import AshaService
from pandora_services.avrcp import AvrcpService
from pandora_services.bumble_config import BumbleConfigService
from pandora_services.dck import DckService
from pandora_services.gatt import GATTService
from pandora_services.hid import HIDService
from pandora_services.oob import OOBService
from pandora_services.opp import OppService
from pandora_services.rfcomm import RFCOMMService
from pandora_services.hf import HFService
from pandora.asha_grpc_aio import add_AshaServicer_to_server
from pandora.avrcp_grpc_aio import add_AVRCPServicer_to_server
from pandora.bumble_config_grpc_aio import \
    add_BumbleConfigServicer_to_server
from pandora.dck_grpc_aio import add_DckServicer_to_server
from pandora.gatt_grpc_aio import add_GATTServicer_to_server
from pandora.hid_grpc_aio import add_HIDServicer_to_server
from pandora.oob_grpc_aio import add_OOBServicer_to_server
from pandora.opp_grpc_aio import add_OppServicer_to_server
from pandora.rfcomm_grpc_aio import add_RFCOMMServicer_to_server
from pandora.hfp_grpc_aio import add_HFPServicer_to_server

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

    asyncio.run(serve(device, config=server_config, port=grpc_port))


def args_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Bumble command-line tool")

    parser.add_argument('--grpc-port',
                        type=int,
                        default=BUMBLE_SERVER_GRPC_PORT,
                        help='gRPC port to serve')
    parser.add_argument('--rootcanal-port',
                        type=int,
                        default=ROOTCANAL_PORT_CUTTLEFISH,
                        help='Rootcanal TCP port')
    parser.add_argument('--transport',
                        type=str,
                        default='tcp-client:127.0.0.1:<rootcanal-port>',
                        help='HCI transport (default: tcp-client:127.0.0.1:<rootcanal-port>)')
    parser.add_argument('--config', type=str, help='Bumble json configuration file')

    return parser


def register_rfcomm_dependent_servicers(bumble, _, server) -> None:
    rfcomm_server = RFCOMMServer(bumble.device)
    add_RFCOMMServicer_to_server(RFCOMMService(bumble.device, rfcomm_server), server)
    add_OppServicer_to_server(OppService(bumble.device, rfcomm_server), server)
    add_HFPServicer_to_server(HFService(bumble.device, rfcomm_server), server)


def register_experimental_services() -> None:
    pandora_services.register_servicer_hook(
        lambda bumble, _, server: add_AVRCPServicer_to_server(AvrcpService(bumble.device), server))
    pandora_services.register_servicer_hook(
        lambda bumble, _, server: add_AshaServicer_to_server(AshaService(bumble.device), server))
    pandora_services.register_servicer_hook(
        lambda bumble, _, server: add_DckServicer_to_server(DckService(bumble.device), server))
    pandora_services.register_servicer_hook(
        lambda bumble, _, server: add_GATTServicer_to_server(GATTService(bumble.device), server))
    pandora_services.register_servicer_hook(register_rfcomm_dependent_servicers)
    pandora_services.register_servicer_hook(
        lambda bumble, _, server: add_HIDServicer_to_server(HIDService(bumble.device), server))
    pandora_services.register_servicer_hook(
        lambda bumble, _, server: add_OOBServicer_to_server(OOBService(bumble.device), server))
    pandora_services.register_servicer_hook(
        lambda bumble, config, server: add_BumbleConfigServicer_to_server(
            BumbleConfigService(bumble.device, config), server))


def retrieve_config(config: str) -> Dict[str, Any]:
    if not config:
        return {}

    with open(config, 'r') as f:
        return json.load(f)


if __name__ == '__main__':
    logging.basicConfig(level=logging.DEBUG,
                        format='%(asctime)s.%(msecs).03d %(levelname)-8s %(message)s',
                        datefmt='%m-%d %H:%M:%S')
    args = args_parser().parse_args()
    main(**vars(args))
