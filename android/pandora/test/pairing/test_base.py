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

from abc import ABC, abstractmethod

from mobly import base_test

from avatar import BumblePandoraDevice, PandoraDevice, PandoraDevices
from avatar.aio import asynchronous
from typing import Optional


class PairTestBase(ABC, base_test.BaseTestClass):  # type: ignore[misc]

    devices: Optional[PandoraDevices] = None

    dut: PandoraDevice
    ref: PandoraDevice

    @abstractmethod
    def _setup_devices(self):
        raise NotImplementedError

    def setup_class(self) -> None:
        self.devices = PandoraDevices(self)
        self.dut, self.ref, *_ = self.devices

        self._setup_devices()

    def teardown_class(self) -> None:
        if self.devices:
            self.devices.stop_all()

    @asynchronous
    async def setup_test(self) -> None:
        await asyncio.gather(self.dut.reset(), self.ref.reset())

    def teardown_test(self):
        pass

    @property
    def acl_initiator(self):
        return self._acl_initiator

    @acl_initiator.setter
    def acl_initiator(self, acl_initiator: PandoraDevice):
        self._acl_initiator = acl_initiator

    @property
    def acl_responder(self):
        return self._acl_responder

    @acl_responder.setter
    def acl_responder(self, acl_responder: PandoraDevice):
        self._acl_responder = acl_responder

    @property
    def service_initiator(self):
        return self._service_initiator

    @service_initiator.setter
    def service_initiator(self, service_initiator: PandoraDevice):
        self._service_initiator = service_initiator

    @property
    def service_responder(self):
        return self._service_responder

    @service_responder.setter
    def service_responder(self, service_responder: PandoraDevice):
        self._service_responder = service_responder

    @property
    def pairing_initiator(self):
        return self._pairing_initiator

    @pairing_initiator.setter
    def pairing_initiator(self, pairing_initiator: PandoraDevice):
        self._pairing_initiator = pairing_initiator

    @property
    def initiator_pairing_event_stream(self):
        if self.pairing_initiator == self.dut:
            return self.android_pairing_stream

        return self.bumble_pairing_stream

    @property
    def pairing_responder(self):
        return self._pairing_responder

    @pairing_responder.setter
    def pairing_responder(self, pairing_responder: PandoraDevice):
        self._pairing_responder = pairing_responder

    @property
    def responder_pairing_event_stream(self):
        if self.pairing_initiator == self.dut:
            return self.bumble_pairing_stream

        return self.android_pairing_stream

    @abstractmethod
    async def start_acl_connection(self):
        raise NotImplementedError

    @abstractmethod
    async def start_pairing(
        self,
        initiator_acl_connection,
        responder_acl_connection,
    ):
        raise NotImplementedError

    @abstractmethod
    async def start_service_access(
        self,
        initiator_acl_connection,
        responder_acl_connection,
    ):
        raise NotImplementedError

    @abstractmethod
    async def accept_pairing(self):
        raise NotImplementedError

    def prepare_pairing(self):
        self.android_pairing_stream = self.dut.aio.security.OnPairing()
        setattr(self.android_pairing_stream, 'device', self.dut)

        self.bumble_pairing_stream = self.ref.aio.security.OnPairing()
        setattr(self.bumble_pairing_stream, 'device', self.ref)
