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

import grpc
import logging

from bumble.core import UUID as BumbleUUID, AdvertisingData
from bumble.device import Connection, Device
from bumble.gatt import Characteristic, CharacteristicValue, TemplateService
from bumble.l2cap import LeCreditBasedChannel, LeCreditBasedChannelSpec
from pandora_services import utils
from google.protobuf.empty_pb2 import Empty
from pandora.dck_grpc_aio import DckServicer
from typing import Optional


class DckGattService(TemplateService):
    CCC_DK_UUID: BumbleUUID = BumbleUUID.from_16_bits(0xFFF5, 'Car Connectivity Consortium, LLC')
    UUID = CCC_DK_UUID
    UUID_SPSM = BumbleUUID("D3B5A130-9E23-4B3A-8BE4-6B1EE5F980A3", "Vehicle SPSM")
    UUID_SPSM_DK_VERSION = BumbleUUID("D3B5A130-9E23-4B3A-8BE4-6B1EE5B780A3", "DK version")
    UUID_DEVICE_DK_VERSION = BumbleUUID("BD4B9502-3F54-11EC-B919-0242AC120005",
                                        "Device Selected DK version")
    UUID_ANTENNA_IDENTIFIER = BumbleUUID("c6d7d4a1-e2b0-4e95-b576-df983d1a5d9f",
                                         "Vehicle Antenna Identifier")

    def __init__(self, device: Device):
        logger = logging.getLogger(__name__)

        def on_l2cap_channel(channel: LeCreditBasedChannel) -> None:
            logger.info(f"--- DckGattService on_l2cap_channel {channel}")

        self.device_dk_version_value = None
        self.l2cap_server = device.create_l2cap_server(
            spec=LeCreditBasedChannelSpec(),
            handler=on_l2cap_channel,
        )
        self.psm = self.l2cap_server.psm

        def on_device_version_write(connection: Connection | None, value: bytes) -> None:
            logger.info(f"--- DK Device Version Write: {value!r}")
            self.device_dk_version_value = value

        characteristics = [
            Characteristic[bytes](
                DckGattService.UUID_SPSM,
                Characteristic.Properties.READ,
                Characteristic.READABLE,
                # CCC Specification Digital-Key R3-1.2.3
                # 19.2.1.6 DK Service
                self.psm.to_bytes(2, 'big'),
            ),
            Characteristic[bytes](
                DckGattService.UUID_SPSM_DK_VERSION,
                Characteristic.Properties.READ,
                Characteristic.READ_REQUIRES_ENCRYPTION,
                b'',
            ),
            Characteristic[bytes](
                DckGattService.UUID_DEVICE_DK_VERSION,
                Characteristic.Properties.WRITE,
                Characteristic.READ_REQUIRES_ENCRYPTION,
                CharacteristicValue(write=on_device_version_write),
            ),
            Characteristic[bytes](
                DckGattService.UUID_ANTENNA_IDENTIFIER,
                Characteristic.READ,
                Characteristic.READABLE,
                b'',
            ),
        ]

        super().__init__(characteristics)

    def __del__(self):
        if self.l2cap_server:
            self.l2cap_server.close()

    def get_advertising_data(self) -> bytes:
        # CCC Specification Digital-Key R3-1.2.0-r14
        # 19.2 LE Procedures AdvData field of ADV_IND

        return bytes(
            AdvertisingData([(AdvertisingData.SERVICE_DATA_16_BIT_UUID,
                              bytes(DckGattService.CCC_DK_UUID))]))


class DckService(DckServicer):
    device: Device
    dck_gatt_service: Optional[DckGattService]

    def __init__(self, device: Device) -> None:
        self.log = utils.BumbleServerLoggerAdapter(logging.getLogger(), {
            "service_name": "Dck",
            "device": device
        })
        self.device = device
        self.dck_gatt_service = None

    @utils.rpc
    def Register(self, request: Empty, context: grpc.ServicerContext) -> Empty:
        if self.dck_gatt_service is None:
            self.dck_gatt_service = DckGattService(self.device)
            self.device.add_service(self.dck_gatt_service)

        return Empty()
