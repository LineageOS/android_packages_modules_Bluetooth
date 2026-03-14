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
"""Voice Assistant Profile (VAP) client implementation for Bumble."""

import asyncio
import enum
import logging
from typing import TypeAlias

from bumble import core
from bumble import device
from bumble import gatt
from bumble import gatt_adapters
from bumble import gatt_client
from bumble import utils

from navi.bumble_ext import gatt_helper

GENERIC_VOICE_ASSISTANT_SERVICE_UUID = core.UUID.from_16_bits(0x185F,
                                                              'Generic Voice Assistant Service')
VOICE_ASSISTANT_SERVICE_UUID = core.UUID.from_16_bits(0x185E, 'Voice Assistant Service')
VA_NAME_CHARACTERISTIC_UUID = core.UUID.from_16_bits(0x2C31, 'Voice Assistant Name')
VAS_CONTROL_POINT_CHARACTERISTIC_UUID = core.UUID.from_16_bits(
    0x2C33, 'Voice Assistant Service Control Point')
VA_SESSION_FLAG_CHARACTERISTIC_UUID = core.UUID.from_16_bits(0x2C36, 'Voice Assistant Session Flag')
VA_SESSION_STATE_CHARACTERISTIC_UUID = core.UUID.from_16_bits(0x2C35,
                                                              'Voice Assistant Session State')
VA_SUPPORTED_FEATURES_CHARACTERISTIC_UUID = core.UUID.from_16_bits(
    0x2C38, 'Voice Assistant Supported Features')
VA_SUPPORTED_LANGUAGES_CHARACTERISTIC_UUID = core.UUID.from_16_bits(
    0x2C37, 'Voice Assistant Supported Languages')
VA_UUID_CHARACTERISTIC_UUID = core.UUID.from_16_bits(0x2C32, 'Voice Assistant UUID')

_DEFAULT_CCID = 1
_DEFAULT_VA_NAME = 'Voice Assistant'
_DEFAULT_UUID = b'\xb5\x80(\x1a4WF>\x82\x85\xe5%\x1dz)#'


class VapError(core.ProtocolError):

    def __init__(self, error_code: int | None, error_name: str = '', details: str = ''):
        super().__init__(error_code, 'VAP', error_name, details)


class ControlPointOpcode(utils.OpenIntEnum):
    INITIALIZE_VA_SESSION = 0x00
    START_VA_SESSION = 0x01
    STOP_VA_SESSION = 0x02
    INVALID_OPCODE = 0x03


class ResponseCodeValue(utils.OpenIntEnum):
    RESERVED_FOR_FUTURE_USE = 0x00
    SUCCESS = 0x01
    OP_CODE_NOT_SUPPORTED = 0x02
    OPERATION_FAILED = 0x03
    CCID_VALUE = 0x03
    INVALID_SESSION_STATE = 0x04


class VaSessionState(utils.OpenIntEnum):
    VA_SESSION_UNAVAILABLE = 0x00
    VA_SESSION_RESET = 0x01
    VA_SESSION_READY = 0x02
    VA_SESSION_ACTIVE = 0x03


class VaSupportedFeatures(enum.IntFlag):
    SESSION_FLAGS_ENABLED = 0x01


_Properties: TypeAlias = gatt.Characteristic.Properties


class VoiceAssistantService(gatt.TemplateService):
    """Voice Assistant Profile Service server implementation, only for testing currently."""

    UUID = VOICE_ASSISTANT_SERVICE_UUID

    va_name: gatt_adapters.UTF8CharacteristicAdapter
    va_uuid: gatt.Characteristic[bytes]
    vas_control_point: gatt.Characteristic[bytes]
    va_ccid: gatt_adapters.PackedCharacteristicAdapter
    va_session_state: gatt_adapters.PackedCharacteristicAdapter

    def __init__(
            self,
            name: str = _DEFAULT_VA_NAME,
            ccid: int = _DEFAULT_CCID,
            uuid: bytes = _DEFAULT_UUID,
            supported_features: VaSupportedFeatures = VaSupportedFeatures(0),
    ) -> None:
        self.va_name = gatt_adapters.UTF8CharacteristicAdapter(gatt.Characteristic[str](
            uuid=VA_NAME_CHARACTERISTIC_UUID,
            properties=_Properties.READ | _Properties.NOTIFY,
            permissions=gatt.Characteristic.Permissions.READABLE,
            value=name,
        ))
        self.va_uuid = gatt.Characteristic[bytes](
            uuid=VA_UUID_CHARACTERISTIC_UUID,
            properties=_Properties.READ | _Properties.NOTIFY,
            permissions=gatt.Characteristic.Permissions.READABLE,
            value=uuid,
        )
        self.va_ccid = gatt_adapters.PackedCharacteristicAdapter(
            gatt.Characteristic[int](
                uuid=gatt.GATT_CONTENT_CONTROL_ID_CHARACTERISTIC,
                properties=_Properties.READ | _Properties.NOTIFY,
                permissions=gatt.Characteristic.Permissions.READABLE,
                value=ccid,
            ),
            '<B',
        )
        self.va_session_state = gatt_adapters.PackedCharacteristicAdapter(
            gatt.Characteristic[int](
                uuid=VA_SESSION_STATE_CHARACTERISTIC_UUID,
                properties=_Properties.READ | _Properties.NOTIFY,
                permissions=gatt.Characteristic.Permissions.READABLE,
                value=VaSessionState.VA_SESSION_READY,
            ),
            '<B',
        )
        self.vas_control_point = gatt.Characteristic[bytes](
            uuid=VAS_CONTROL_POINT_CHARACTERISTIC_UUID,
            properties=_Properties.WRITE_WITHOUT_RESPONSE | _Properties.NOTIFY,
            permissions=gatt.Characteristic.Permissions.WRITEABLE,
            value=gatt.CharacteristicValue(write=self._on_vas_control_point_write),
        )
        self.va_supported_features = gatt_adapters.EnumCharacteristicAdapter[VaSupportedFeatures](
            gatt.Characteristic[VaSupportedFeatures](
                uuid=VA_SUPPORTED_FEATURES_CHARACTERISTIC_UUID,
                properties=_Properties.READ | _Properties.NOTIFY,
                permissions=gatt.Characteristic.Permissions.READABLE,
                value=supported_features,
            ),
            VaSupportedFeatures,
            length=1,
        )

        super().__init__([
            self.va_name,
            self.va_uuid,
            self.vas_control_point,
            self.va_ccid,
            self.va_session_state,
            self.va_supported_features,
        ])

    async def _on_vas_control_point_write(self, connection: device.Connection, data: bytes) -> None:
        opcode = data[0]
        if opcode in (
                ControlPointOpcode.INITIALIZE_VA_SESSION,
                ControlPointOpcode.STOP_VA_SESSION,
                ControlPointOpcode.START_VA_SESSION,
        ):
            response_code = ResponseCodeValue.SUCCESS
        else:
            response_code = ResponseCodeValue.OP_CODE_NOT_SUPPORTED
        response = bytes([opcode, response_code])

        await connection.device.notify_subscriber(connection,
                                                  self.vas_control_point,
                                                  value=response)


class GenericVoiceAssistantService(VoiceAssistantService):
    """Generic Voice Assistant Profile Service server implementation, only for testing currently."""

    UUID = GENERIC_VOICE_ASSISTANT_SERVICE_UUID


class VoiceAssistantServiceProxy(gatt_client.ProfileServiceProxy):
    """Voice Assistant Profile client implementation."""

    SERVICE_CLASS = VoiceAssistantService
    va_name: gatt_adapters.UTF8CharacteristicProxyAdapter
    va_uuid: gatt_client.CharacteristicProxy[bytes]
    vas_control_point: gatt_client.CharacteristicProxy[bytes]
    va_ccid: gatt_adapters.PackedCharacteristicProxyAdapter
    va_session_state: gatt_helper.MutableCharacteristicState

    def __init__(self, service_proxy: gatt_client.ServiceProxy) -> None:
        self.service_proxy = service_proxy
        self.lock = asyncio.Lock()
        self.vas_control_point_notifications = asyncio.Queue[bytes]()
        self.logger = logging.getLogger(__name__)

        self.va_name = gatt_adapters.UTF8CharacteristicProxyAdapter(
            service_proxy.get_required_characteristic_by_uuid(VA_NAME_CHARACTERISTIC_UUID))
        self.va_uuid = service_proxy.get_required_characteristic_by_uuid(
            VA_UUID_CHARACTERISTIC_UUID)
        self.vas_control_point = service_proxy.get_required_characteristic_by_uuid(
            VAS_CONTROL_POINT_CHARACTERISTIC_UUID)
        self.va_ccid = gatt_adapters.PackedCharacteristicProxyAdapter(
            service_proxy.get_required_characteristic_by_uuid(
                gatt.GATT_CONTENT_CONTROL_ID_CHARACTERISTIC),
            '<B',
        )
        self.va_session_state_proxy = (
            service_proxy.get_required_characteristic_by_uuid(VA_SESSION_STATE_CHARACTERISTIC_UUID))
        self.va_supported_features_proxy = (
            gatt_adapters.EnumCharacteristicProxyAdapter[VaSupportedFeatures](
                service_proxy.get_required_characteristic_by_uuid(
                    VA_SUPPORTED_FEATURES_CHARACTERISTIC_UUID),
                VaSupportedFeatures,
                length=1,
            ))

    async def subscribe_characteristics(self) -> None:
        """Subscribes to characteristics that have the notify property."""
        await self.vas_control_point.subscribe(self.vas_control_point_notifications.put_nowait)
        self.va_session_state = await gatt_helper.MutableCharacteristicState.create(
            self.va_session_state_proxy)

    async def write_control_point(self, opcode: ControlPointOpcode) -> ResponseCodeValue:
        async with self.lock:
            await self.vas_control_point.write_value(
                bytes([opcode]),
                with_response=False,
            )
            (
                response_opcode,
                response_code,
            ) = await self.vas_control_point_notifications.get()
            if response_opcode != opcode:
                self.logger.info(
                    'Expected Response Code %s notification, but get %s',
                    opcode,
                    response_opcode,
                )
            if opcode == ControlPointOpcode.START_VA_SESSION:
                if response_code != ResponseCodeValue.SUCCESS:
                    raise VapError(
                        error_code=response_code,
                        error_name='Invalid Session State',
                        details=('Expected Response Code'
                                 f' {ResponseCodeValue.SUCCESS} notification, but get'
                                 f' {response_code}'),
                    )
            return ResponseCodeValue(response_code)

    async def initialize_va_session(self) -> ResponseCodeValue:
        return await self.write_control_point(ControlPointOpcode.INITIALIZE_VA_SESSION)

    async def start_va_session(self) -> None:
        await self.write_control_point(ControlPointOpcode.START_VA_SESSION)

    async def stop_va_session(self) -> ResponseCodeValue:
        return await self.write_control_point(ControlPointOpcode.STOP_VA_SESSION)


class GenericVoiceAssistantServiceProxy(VoiceAssistantServiceProxy):
    """Voice Assistant Profile client implementation."""

    SERVICE_CLASS = GenericVoiceAssistantService
