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
"""Extended Bluetooth Host Controller Interface (HCI) commands."""

from __future__ import annotations

from collections.abc import Sequence
import dataclasses
from typing import Any, ClassVar, Self, TypeVar

from bumble import hci


def parse_address_followed_by_type(data: bytes, offset: int = 0) -> tuple[int, hci.Address]:
    return offset + 7, hci.Address(data[offset:offset + 6], hci.AddressType(data[offset + 6]))


@dataclasses.dataclass(frozen=True, kw_only=True)
class _HciPacket(hci.HCI_Packet):
    """Base extended HCI packet."""

    PARSE_OFFSET: ClassVar[int] = 0

    @classmethod
    def from_parameters(cls: type[Self], parameters: bytes) -> Self:
        """Creates an HCI packet from the given parameters."""
        offset = cls.PARSE_OFFSET
        values: dict[str, Any] = {}
        for field in dataclasses.fields(cls):
            if not (metadata := getattr(field.type, "__metadata__", None)):
                continue
            value, size = hci.HCI_Object.parse_field(parameters, offset, metadata[0])
            offset += size
            values[field.name] = value
        return cls(**values)

    @property
    def name(self) -> str:
        return self.__class__.__name__

    @property
    def parameters(self) -> bytes:
        return b"".join(
            hci.HCI_Object.serialize_field(getattr(self, field_name), field_type)
            for field_name, field_type in self.fields)

    @property
    def fields(self) -> list[tuple[str, Any]]:
        return [(field.name, field.type.__metadata__[0])
                for field in dataclasses.fields(self)
                if hasattr(field.type, "__metadata__")]


class Command(_HciPacket, hci.HCI_Command):
    """Base extended HCI command."""

    op_code: int
    return_parameters_fields: ClassVar[Sequence[tuple[str, Any]]] = ()

    @classmethod
    def register(cls: type[Self], clazz: type[_C]) -> type[_C]:
        """Registers the Command with the HCI module."""

        hci.HCI_Command.command_classes[clazz.op_code] = clazz
        return clazz


class Event(_HciPacket, hci.HCI_Event):
    """Base extended HCI Event."""

    event_code: int

    @classmethod
    def register(cls: type[Self], clazz: type[_E]) -> type[_E]:
        """Registers the Event with the HCI module."""

        hci.HCI_Event.event_classes[clazz.event_code] = clazz
        return clazz


class LeMetaEvent(_HciPacket, hci.HCI_LE_Meta_Event):
    """Base extended HCI Event."""

    subevent_code: int

    @classmethod
    def register(cls: type[Self], clazz: type[_LEME]) -> type[_LEME]:
        """Registers the Event with the HCI module."""

        hci.HCI_LE_Meta_Event.subevent_classes[clazz.subevent_code] = clazz
        return clazz


class VendorEvent(_HciPacket, hci.HCI_Vendor_Event):
    """Base extended HCI Vendor Event."""

    PARSE_OFFSET = 1

    @classmethod
    def register(cls: type[Self], clazz: type[_VE]) -> type[_VE]:
        """Registers the VendorEvent with the HCI module."""

        hci.HCI_Event.add_vendor_factory(clazz.from_parameters)
        return clazz


_C = TypeVar("_C", bound=Command)
_VE = TypeVar("_VE", bound=VendorEvent)
_LEME = TypeVar("_LEME", bound=LeMetaEvent)
_E = TypeVar("_E", bound=VendorEvent)
