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
"""Bumble extension for Volume Offset Control Service (VOCS)."""

from __future__ import annotations

import asyncio
import logging
import struct
from typing import Any

from bumble import core
from bumble import device
from bumble import gatt
from bumble import gatt_adapters
from bumble import gatt_client
from bumble import utils
from bumble.profiles import bap

from navi.bumble_ext import gatt_helper


class VocsError(core.ProtocolError):

    def __init__(self, error_code: int | None, error_name: str = '', details: str = ''):
        super().__init__(error_code, 'VOCS', error_name, details)


class VocsControlPointOpcode(utils.OpenIntEnum):
    """Volume Offset Control Service - 3.3.2.1 Set Volume Offset Control Point procedure requirements."""

    SET_VOLUME_OFFSET = 0x01


class ResponseCodeValue(utils.OpenIntEnum):
    UNSUPPORTED_OPCODE = 0x81
    ERROR_CODE = 0x80
    SUCCESS = 0x01


class VolumeOffsetControlService(gatt.TemplateService):
    """Volume Offset Control Service."""

    UUID = gatt.GATT_VOLUME_OFFSET_CONTROL_SERVICE
    volume_offset: int
    change_counter: int
    audio_output_description: str

    def __init__(
        self,
        change_counter: int = 0,
        volume_offset: int = 0,
        audio_location: bap.AudioLocation = bap.AudioLocation.FRONT_LEFT,
        audio_output_description: str = '',
    ) -> None:
        self.volume_offset = volume_offset
        self.change_counter = change_counter
        self.audio_output_description = audio_output_description
        self.audio_location = audio_location

        self.state_characteristic = gatt.Characteristic[bytes](
            uuid=gatt.GATT_VOLUME_OFFSET_STATE_CHARACTERISTIC,
            properties=gatt.Characteristic.Properties.READ | gatt.Characteristic.Properties.NOTIFY,
            permissions=gatt.Characteristic.Permissions.READABLE,
            value=gatt.CharacteristicValue(read=lambda _: bytes(self)),
        )

        self.location_characteristic = gatt_adapters.EnumCharacteristicAdapter(
            gatt.Characteristic(
                uuid=gatt.GATT_AUDIO_LOCATION_CHARACTERISTIC,
                properties=gatt.Characteristic.Properties.READ |
                gatt.Characteristic.Properties.WRITE_WITHOUT_RESPONSE,
                permissions=gatt.Characteristic.Permissions.READABLE |
                gatt.Characteristic.Permissions.WRITEABLE,
                value=gatt.CharacteristicValue(read=self._read_audio_location_value),
            ),
            cls=bap.AudioLocation,
            length=4,
            byteorder='little',
        )
        self.control_point_characteristic = gatt.Characteristic[bytes](
            uuid=gatt.GATT_VOLUME_OFFSET_CONTROL_POINT_CHARACTERISTIC,
            properties=gatt.Characteristic.Properties.WRITE_WITHOUT_RESPONSE |
            gatt.Characteristic.Properties.NOTIFY,
            permissions=gatt.Characteristic.Permissions.WRITEABLE,
            value=gatt.CharacteristicValue(write=self.on_control_point_write),
        )
        self.description_characteristic = gatt_adapters.UTF8CharacteristicAdapter(
            gatt.Characteristic(
                uuid=gatt.GATT_AUDIO_OUTPUT_DESCRIPTION_CHARACTERISTIC,
                properties=gatt.Characteristic.Properties.READ |
                gatt.Characteristic.Properties.WRITE_WITHOUT_RESPONSE,
                permissions=gatt.Characteristic.Permissions.READABLE |
                gatt.Characteristic.Permissions.WRITEABLE,
                value=gatt.CharacteristicValue(
                    read=self._read_audio_output_description_value,
                    write=self.on_description_write,
                ),
            ))

        characteristics = [
            self.state_characteristic,
            self.location_characteristic,
            self.control_point_characteristic,
            self.description_characteristic,
        ]
        super().__init__(characteristics)

    def _read_audio_location_value(self, connection: device.Connection) -> bap.AudioLocation:
        del connection  # Unused
        return self.audio_location

    def _read_audio_output_description_value(self, connection: device.Connection) -> str:
        del connection  # Unused
        return self.audio_output_description

    def __bytes__(self) -> bytes:
        return struct.pack('<hB', self.volume_offset, self.change_counter)

    async def on_description_write(self, connection: device.Connection, value: str) -> None:
        self.audio_output_description = value
        await self.on_location_or_description_write(connection, value)

    async def on_location_or_description_write(self, connection: device.Connection, _: Any) -> None:
        self.change_counter = (self.change_counter + 1) % 256
        await connection.device.notify_subscribers(self.state_characteristic, bytes(self))

    async def on_control_point_write(self, connection: device.Connection, value: bytes) -> None:
        opcode = value[0]
        if opcode == VocsControlPointOpcode.SET_VOLUME_OFFSET:
            self.change_counter = (self.change_counter + 1) % 256
            (self.volume_offset,) = struct.unpack_from('<h', value, 1)
            await connection.device.notify_subscribers(self.state_characteristic, bytes(self))
            await connection.device.notify_subscribers(self.control_point_characteristic,
                                                       bytes([0x01, 0x01]))
        else:
            await connection.device.notify_subscribers(
                self.control_point_characteristic,
                bytes([opcode, ResponseCodeValue.UNSUPPORTED_OPCODE]),
            )


class VolumeOffsetControlServiceProxy(gatt_client.ProfileServiceProxy):
    """Proxy for Volume Offset Control Service (VOCS)."""

    service: gatt_client.ServiceProxy
    state: gatt_helper.MutableCharacteristicState
    location: gatt_client.CharacteristicProxy
    control_point: gatt_client.CharacteristicProxy
    description: gatt_client.CharacteristicProxy

    SERVICE_CLASS = VolumeOffsetControlService

    def __init__(self, service_proxy: gatt_client.ServiceProxy):
        self.service = service_proxy
        self.lock = asyncio.Lock()
        self.vocs_control_point_notifications = asyncio.Queue[bytes]()
        self.logger = logging.getLogger(__name__)

        self.location = service_proxy.get_required_characteristic_by_uuid(
            gatt.GATT_AUDIO_LOCATION_CHARACTERISTIC)
        self.control_point = service_proxy.get_required_characteristic_by_uuid(
            gatt.GATT_VOLUME_OFFSET_CONTROL_POINT_CHARACTERISTIC)
        self.description = service_proxy.get_required_characteristic_by_uuid(
            gatt.GATT_AUDIO_OUTPUT_DESCRIPTION_CHARACTERISTIC)
        self.state_proxy = service_proxy.get_required_characteristic_by_uuid(
            gatt.GATT_VOLUME_OFFSET_STATE_CHARACTERISTIC)

    async def subscribe_characteristics(self) -> None:
        """Subscribes to characteristics that have the notify property."""
        await self.control_point.subscribe(self.vocs_control_point_notifications.put_nowait)
        self.state = await gatt_helper.MutableCharacteristicState.create(self.state_proxy)

    async def write_control_point(self, opcode: VocsControlPointOpcode) -> ResponseCodeValue:
        async with self.lock:
            await self.control_point.write_value(
                bytes([opcode]),
                with_response=False,
            )
            (
                response_opcode,
                response_code,
            ) = await self.vocs_control_point_notifications.get()
            if response_opcode != opcode:
                self.logger.info(
                    'Expected Response Code %s notification, but get %s',
                    opcode,
                    response_opcode,
                )
            if opcode == VocsControlPointOpcode.SET_VOLUME_OFFSET:
                if response_code != ResponseCodeValue.SUCCESS:
                    raise VocsError(
                        error_code=response_code,
                        error_name='Invalid Session State',
                        details=('Expected Response Code'
                                 f' {ResponseCodeValue.SUCCESS} notification, but get'
                                 f' {response_code}'),
                    )
            return ResponseCodeValue(response_code)

    async def get_audio_location(self) -> bap.AudioLocation:
        value = await self.location.read_value()
        return bap.AudioLocation(int.from_bytes(value, 'little'))

    async def set_audio_location(self, audio_location: bap.AudioLocation) -> None:
        await self.location.write_value(int(audio_location).to_bytes(4, 'little'))

    async def get_audio_output_description(self) -> str:
        value = await self.description.read_value()
        return value.decode('utf-8')

    async def set_audio_output_description(self, description: str) -> None:
        await self.description.write_value(description.encode('utf-8'))

    async def set_volume_offset(self, volume_offset: int) -> None:
        await self.control_point.write_value(
            bytes([VocsControlPointOpcode.SET_VOLUME_OFFSET]) + struct.pack('<h', volume_offset),)
