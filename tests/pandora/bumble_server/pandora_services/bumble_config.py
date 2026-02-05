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

import logging

import grpc
from bumble.device import Connection as BumbleConnection
from bumble.device import Device
from bumble.pairing import PairingConfig
from bumble.pairing import PairingDelegate as BasePairingDelegate
from pandora_services import Config, utils
from pandora_services.security import PairingDelegate, SecurityService
from google.protobuf.empty_pb2 import Empty
from pandora.host_pb2 import PUBLIC
from pandora.bumble_config_grpc_aio import BumbleConfigServicer
from pandora.bumble_config_pb2 import (KeyDistribution, OverrideRequest)


class BumbleConfigService(BumbleConfigServicer):
    device: Device

    def __init__(self, device: Device, server_config: Config) -> None:
        self.log = utils.BumbleServerLoggerAdapter(logging.getLogger(), {
            "service_name": "BumbleConfig",
            "device": device,
        })
        self.device = device
        self.server_config = server_config

    @utils.rpc
    async def Override(self, request: OverrideRequest, context: grpc.ServicerContext) -> Empty:

        def parseProtoKeyDistribution(key: KeyDistribution,) -> BasePairingDelegate.KeyDistribution:
            return [
                BasePairingDelegate.KeyDistribution.DISTRIBUTE_ENCRYPTION_KEY,
                BasePairingDelegate.KeyDistribution.DISTRIBUTE_IDENTITY_KEY,
                BasePairingDelegate.KeyDistribution.DISTRIBUTE_SIGNING_KEY,
                BasePairingDelegate.KeyDistribution.DISTRIBUTE_LINK_KEY,
            ][key]  # type: ignore

        local_initiator_key_distribution = 0b0000
        local_responder_key_distribution = 0b0000
        for initiator_key_distribution in request.initiator_key_distribution:
            local_initiator_key_distribution |= parseProtoKeyDistribution(
                initiator_key_distribution)
        for responder_key_distribution in request.responder_key_distribution:
            local_responder_key_distribution |= parseProtoKeyDistribution(
                responder_key_distribution)

        def pairing_config_factory(connection: BumbleConnection) -> PairingConfig:
            pairing_delegate = PairingDelegate(
                connection=connection,
                service=SecurityService(self.device, self.server_config),
                io_capability=BasePairingDelegate.IoCapability(request.io_capability),
                local_initiator_key_distribution=BasePairingDelegate.KeyDistribution(
                    local_initiator_key_distribution),
                local_responder_key_distribution=BasePairingDelegate.KeyDistribution(
                    local_responder_key_distribution),
            )

            pc_req = request.pairing_config
            pairing_config = PairingConfig(
                sc=pc_req.sc,
                mitm=pc_req.mitm,
                bonding=pc_req.bonding,
                identity_address_type=PairingConfig.AddressType.PUBLIC
                if pc_req.identity_address_type == PUBLIC else PairingConfig.AddressType.RANDOM,
                delegate=pairing_delegate,
            )
            self.log.debug(f"Override: {pairing_config}")

            return pairing_config

        self.device.pairing_config_factory = pairing_config_factory

        return Empty()
