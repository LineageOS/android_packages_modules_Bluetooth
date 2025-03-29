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
import avatar

from avatar import PandoraDevices, BumblePandoraDevice
from mobly import base_test, signals
from mobly.asserts import assert_in, assert_not_in  # type: ignore

from pandora_experimental.os_grpc_aio import Os as OsAio
from pandora.host_pb2 import RANDOM
from pandora_experimental.gatt_grpc import GATT
from pandora_experimental.gatt_pb2 import PRIMARY

from bumble.att import UUID
from bumble.gatt import GATT_VOLUME_CONTROL_SERVICE, GATT_AUDIO_INPUT_CONTROL_SERVICE
from bumble.profiles.aics import AICSService
from bumble.profiles.vcp import VolumeControlService


class AicsTest(base_test.BaseTestClass):

    def setup_class(self) -> None:
        self.devices = PandoraDevices(self)
        self.dut, self.ref, *_ = self.devices

        if not isinstance(self.ref, BumblePandoraDevice):
            raise signals.TestAbortClass('Test require Bumble as reference device.')

    def teardown_class(self) -> None:
        if self.devices:
            self.devices.stop_all()

    @avatar.asynchronous
    async def setup_test(self) -> None:
        await asyncio.gather(self.dut.reset(), self.ref.reset())
        self.logcat = OsAio(channel=self.dut.aio.channel)
        await self.logcat.Log(f'{self.current_test_info.name}: setup_test')

        aics_service = AICSService()
        volume_control_service = VolumeControlService(included_services=[aics_service])
        self.ref.device.add_service(aics_service)  # type: ignore
        self.ref.device.add_service(volume_control_service)  # type: ignore
        await self.logcat.Log(f'{self.current_test_info.name}: completed setup_test')

    @avatar.asynchronous
    async def teardown_test(self) -> None:
        await self.logcat.Log(f'{self.current_test_info.name}: completed teardown_test')

    def connect_dut_to_ref(self):
        advertise = self.ref.host.Advertise(legacy=True, connectable=True)
        dut_ref_connection = self.dut.host.ConnectLE(public=self.ref.address,
                                                     own_address_type=RANDOM).connection
        assert dut_ref_connection
        advertise.cancel()  # type: ignore

        return dut_ref_connection

    def test_do_not_discover_aics_as_primary_service(self) -> None:
        dut_ref_connection = self.connect_dut_to_ref()
        dut_gatt = GATT(self.dut.channel)

        services = dut_gatt.DiscoverServices(dut_ref_connection).services
        uuids = [UUID(service.uuid) for service in services if service.service_type == PRIMARY]

        assert_in(GATT_VOLUME_CONTROL_SERVICE, uuids)
        assert_not_in(GATT_AUDIO_INPUT_CONTROL_SERVICE, uuids)

    def test_gatt_discover_aics_service(self) -> None:
        dut_ref_connection = self.connect_dut_to_ref()
        dut_gatt = GATT(self.dut.channel)

        services = dut_gatt.DiscoverServices(dut_ref_connection).services

        filtered_services = [
            service for service in services if UUID(service.uuid) == GATT_VOLUME_CONTROL_SERVICE
        ]
        assert len(filtered_services) == 1
        vcp_service = filtered_services[0]

        included_services_uuids = [
            UUID(included_service.uuid) for included_service in vcp_service.included_services
        ]
        assert_in(GATT_AUDIO_INPUT_CONTROL_SERVICE, included_services_uuids)
