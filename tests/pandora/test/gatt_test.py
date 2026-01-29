# Copyright 2022 Google LLC
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
import dataclasses
import grpc
import logging

from avatar import BumblePandoraDevice, PandoraDevice, PandoraDevices
import pandora_services as bumble_server
from bumble import hci
from bumble import l2cap
from bumble.gatt import (Characteristic, Service, GATT_VOLUME_CONTROL_SERVICE,
                         GATT_AUDIO_INPUT_CONTROL_SERVICE)
from bumble.pairing import PairingConfig
from pandora_services.gatt import GATTService
from mobly import base_test, signals, test_runner
from mobly.asserts import assert_equal  # type: ignore
from mobly.asserts import assert_in  # type: ignore
from mobly.asserts import assert_is_not_none  # type: ignore
from mobly.asserts import assert_not_in  # type: ignore
from mobly.asserts import assert_true  # type: ignore
from pandora.host_pb2 import RANDOM, Connection, DataTypes
from pandora.security_pb2 import LE_LEVEL3, PairingEventAnswer, SecureResponse
from pandora.gatt_grpc import GATT
from pandora.gatt_grpc_aio import GATT as AioGATT, add_GATTServicer_to_server
from pandora.gatt_pb2 import SUCCESS, ReadCharacteristicsFromUuidResponse
from typing import Optional, Tuple


class GattTest(base_test.BaseTestClass):  # type: ignore[misc]
    devices: Optional[PandoraDevices] = None

    # pandora devices.
    dut: PandoraDevice
    ref: PandoraDevice

    def setup_class(self) -> None:
        # Register experimental bumble servicers hook.
        bumble_server.register_servicer_hook(lambda bumble, _, server: add_GATTServicer_to_server(
            GATTService(bumble.device), server))

        self.devices = PandoraDevices(self)
        self.dut, self.ref, *_ = self.devices

    def teardown_class(self) -> None:
        if self.devices:
            self.devices.stop_all()

    @avatar.asynchronous
    async def setup_test(self) -> None:
        await asyncio.gather(self.dut.reset(), self.ref.reset())

    def test_print_dut_gatt_services(self) -> None:
        advertise = self.ref.host.Advertise(legacy=True, connectable=True)
        dut_ref = self.dut.host.ConnectLE(public=self.ref.address,
                                          own_address_type=RANDOM).connection
        assert_is_not_none(dut_ref)
        assert dut_ref
        advertise.cancel()

        gatt = GATT(self.dut.channel)
        services = gatt.DiscoverServices(dut_ref)
        self.dut.log.info(f'DUT services: {services}')

    def test_print_ref_gatt_services(self) -> None:
        advertise = self.dut.host.Advertise(
            legacy=True,
            connectable=True,
            own_address_type=RANDOM,
            data=DataTypes(manufacturer_specific_data=b'pause cafe'),
        )

        scan = self.ref.host.Scan()
        dut = next((x for x in scan if b'pause cafe' in x.data.manufacturer_specific_data))
        scan.cancel()

        ref_dut = self.ref.host.ConnectLE(own_address_type=RANDOM,
                                          **dut.address_asdict()).connection
        assert_is_not_none(ref_dut)
        assert ref_dut
        advertise.cancel()

        gatt = GATT(self.ref.channel)
        services = gatt.DiscoverServices(ref_dut)
        self.ref.log.info(f'REF services: {services}')

    async def connect_dut_to_ref(self) -> Tuple[Connection, Connection]:
        ref_advertisement = self.ref.aio.host.Advertise(
            legacy=True,
            connectable=True,
        )

        dut_connection_to_ref = (await
                                 self.dut.aio.host.ConnectLE(public=self.ref.address,
                                                             own_address_type=RANDOM)).connection
        assert_is_not_none(dut_connection_to_ref)
        assert dut_connection_to_ref

        ref_connection_to_dut = (await anext(aiter(ref_advertisement))).connection
        ref_advertisement.cancel()

        return dut_connection_to_ref, ref_connection_to_dut

    @avatar.asynchronous
    async def test_read_characteristic_while_pairing(self) -> None:
        if isinstance(self.dut, BumblePandoraDevice):
            raise signals.TestSkip('TODO: b/273941061')
        if not isinstance(self.ref, BumblePandoraDevice):
            raise signals.TestSkip('Test require Bumble as reference device(s)')

        # arrange: set up GATT service on REF side with a characteristic
        # that can only be read after pairing
        SERVICE_UUID = "00005A00-0000-1000-8000-00805F9B34FB"
        CHARACTERISTIC_UUID = "00006A00-0000-1000-8000-00805F9B34FB"
        service = Service(
            SERVICE_UUID,
            [
                Characteristic(
                    CHARACTERISTIC_UUID,
                    Characteristic.READ,
                    Characteristic.READ_REQUIRES_ENCRYPTION,
                    b"Hello, world!",
                ),
            ],
        )
        self.ref.device.add_service(service)  # type:ignore
        # disable MITM requirement on REF side (since it only does just works)
        self.ref.device.pairing_config_factory = lambda _: PairingConfig(  # type:ignore
            sc=True, mitm=False, bonding=True)
        # manually handle pairing on the DUT side
        dut_pairing_events = self.dut.aio.security.OnPairing()
        # set up connection
        dut_connection_to_ref, ref_connection_to_dut = await self.connect_dut_to_ref()

        # act: initiate pairing from REF side (send a security request)
        async def ref_secure() -> SecureResponse:
            return await self.ref.aio.security.Secure(connection=ref_connection_to_dut,
                                                      le=LE_LEVEL3)

        ref_secure_task = asyncio.create_task(ref_secure())

        # wait for pairing to start
        event = await anext(dut_pairing_events)

        # before acknowledging pairing, start a GATT read
        dut_gatt = AioGATT(self.dut.aio.channel)

        async def dut_read() -> ReadCharacteristicsFromUuidResponse:
            return await dut_gatt.ReadCharacteristicsFromUuid(dut_connection_to_ref,
                                                              CHARACTERISTIC_UUID, 1, 0xFFFF)

        dut_read_task = asyncio.create_task(dut_read())

        await asyncio.sleep(3)

        # now continue with pairing
        dut_pairing_events.send_nowait(PairingEventAnswer(event=event, confirm=True))

        # android pops up a second pairing notification for some reason, accept it
        event = await anext(dut_pairing_events)
        dut_pairing_events.send_nowait(PairingEventAnswer(event=event, confirm=True))

        # assert: that the read succeeded (so Android re-tried the read after pairing)
        read_response = await dut_read_task
        self.ref.log.info(read_response)
        assert_equal(read_response.characteristics_read[0].status, SUCCESS)
        assert_equal(read_response.characteristics_read[0].value.value, b"Hello, world!")

        # make sure pairing was successful
        ref_secure_res = await ref_secure_task
        assert_equal(ref_secure_res.result_variant(), 'success')

    @avatar.asynchronous
    async def test_rediscover_whenever_unbonded(self) -> None:
        if not isinstance(self.ref, BumblePandoraDevice):
            raise signals.TestSkip('Test require Bumble as reference device(s)')

        # arrange: set up one GATT service on REF side
        dut_gatt = AioGATT(self.dut.aio.channel)
        SERVICE_UUID_1 = "00005A00-0000-1000-8000-00805F9B34FB"
        SERVICE_UUID_2 = "00005A01-0000-1000-8000-00805F9B34FB"
        self.ref.device.add_service(Service(SERVICE_UUID_1, []))  # type:ignore
        # connect both devices
        dut_connection_to_ref, ref_connection_to_dut = await self.connect_dut_to_ref()

        # act: perform service discovery, disconnect, add the second service, reconnect, and try discovery again
        first_discovery = await dut_gatt.DiscoverServices(dut_connection_to_ref)
        await self.ref.aio.host.Disconnect(ref_connection_to_dut)

        # assert: that we found only one service in the first discovery
        assert_in(SERVICE_UUID_1, (service.uuid for service in first_discovery.services))
        assert_not_in(SERVICE_UUID_2, (service.uuid for service in first_discovery.services))

        self.ref.device.add_service(Service(SERVICE_UUID_2, []))  # type:ignore
        dut_connection_to_ref, _ = await self.connect_dut_to_ref()
        second_discovery = await dut_gatt.DiscoverServices(dut_connection_to_ref)

        # assert: but found both in the second discovery
        assert_in(SERVICE_UUID_1, (service.uuid for service in second_discovery.services))
        assert_in(SERVICE_UUID_2, (service.uuid for service in second_discovery.services))

    @avatar.asynchronous
    async def test_do_not_discover_when_bonded(self) -> None:
        # NOTE: if service change indication is ever enabled in Bumble, both this test and `test_rediscover_whenever_unbonded`
        # must DISABLE IT otherwise this test will fail, and the previous test will pass even on a broken implementation
        if not isinstance(self.ref, BumblePandoraDevice):
            raise signals.TestSkip('Test require Bumble as reference device(s)')
        if self.ref.device.gatt_service and self.ref.device.gatt_service.service_changed_characteristic:
            raise signals.TestSkip('Service change indication is enabled')

        # arrange: set up one GATT service on REF side
        dut_gatt = AioGATT(self.dut.aio.channel)
        SERVICE_UUID_1 = "00005A00-0000-1000-8000-00805F9B34FB"
        SERVICE_UUID_2 = "00005A01-0000-1000-8000-00805F9B34FB"
        self.ref.device.add_service(Service(SERVICE_UUID_1, []))  # type:ignore
        # connect both devices
        dut_connection_to_ref, ref_connection_to_dut = await self.connect_dut_to_ref()
        # bond devices and disconnect
        await self.dut.aio.security.Secure(connection=dut_connection_to_ref, le=LE_LEVEL3)
        await self.ref.aio.host.Disconnect(ref_connection_to_dut)
        await self.dut.aio.host.WaitDisconnection(connection=dut_connection_to_ref)

        # act: connect, perform service discovery, disconnect, add the second service, reconnect, and try discovery again
        dut_connection_to_ref, ref_connection_to_dut = await self.connect_dut_to_ref()
        first_discovery = await dut_gatt.DiscoverServices(dut_connection_to_ref)
        await self.ref.aio.host.Disconnect(ref_connection_to_dut)
        await self.dut.aio.host.WaitDisconnection(connection=dut_connection_to_ref)

        # assert: that we found only one service in the first discovery
        assert_in(SERVICE_UUID_1, (service.uuid for service in first_discovery.services))
        assert_not_in(SERVICE_UUID_2, (service.uuid for service in first_discovery.services))

        self.ref.device.add_service(Service(SERVICE_UUID_2, []))  # type:ignore
        dut_connection_to_ref, _ = await self.connect_dut_to_ref()
        second_discovery = await dut_gatt.DiscoverServices(dut_connection_to_ref)

        # assert: that we found only one service in the second discovery as well when it is bonded
        assert_in(SERVICE_UUID_1, (service.uuid for service in second_discovery.services))
        assert_not_in(SERVICE_UUID_2, (service.uuid for service in second_discovery.services))

    @avatar.asynchronous
    async def test_eatt_when_not_encrypted_no_timeout(self) -> None:
        if not isinstance(self.ref, BumblePandoraDevice):
            raise signals.TestSkip('Test require Bumble as reference device(s)')
        advertise = self.dut.aio.host.Advertise(
            legacy=True,
            connectable=True,
            own_address_type=RANDOM,
            data=DataTypes(manufacturer_specific_data=b'pause cafe'),
        )

        scan = self.ref.aio.host.Scan()
        dut = await anext(
            (x async for x in scan if b'pause cafe' in x.data.manufacturer_specific_data))
        scan.cancel()

        ref_dut = (await self.ref.aio.host.ConnectLE(own_address_type=RANDOM,
                                                     **dut.address_asdict())).connection
        assert_is_not_none(ref_dut)
        assert ref_dut
        advertise.cancel()

        connection = self.ref.device.lookup_connection(int.from_bytes(ref_dut.cookie.value, 'big'))
        assert connection

        fut: asyncio.Future[
            l2cap.L2CAP_Credit_Based_Connection_Response] = asyncio.get_running_loop(
            ).create_future()
        setattr(self.ref.device.l2cap_channel_manager, "on_l2cap_credit_based_connection_response",
                lambda _, _1, frame: fut.set_result(frame))
        self.ref.device.l2cap_channel_manager.send_control_frame(
            connection, 0x05,
            l2cap.L2CAP_Credit_Based_Connection_Request(
                identifier=self.ref.device.l2cap_channel_manager.next_identifier(connection),
                spsm=0x27,
                mtu=0x64,
                mps=0x64,
                initial_credits=0x64,
                source_cid=[0x40]))
        control_frame = await asyncio.wait_for(fut, 15.0)

        assert_equal(control_frame.result,
                     0x05)  # All connections refused – insufficient authentication
        assert_true(await is_connected(self.ref, ref_dut), "Device is no longer connected")

    @avatar.parameterized(
        ('primary_service',),
        ('secondary_service',),
    )
    def test_discover_included_service(self, attribute_type: str) -> None:
        PRIMARY_SERVICE_UUID = GATT_VOLUME_CONTROL_SERVICE
        INCLUDED_SERVICE_UUID = GATT_AUDIO_INPUT_CONTROL_SERVICE

        is_primary_service = True if attribute_type == 'primary_service' else False
        included_service = Service(INCLUDED_SERVICE_UUID, [], primary=is_primary_service)
        primary_service = Service(PRIMARY_SERVICE_UUID, [], included_services=[included_service])
        self.ref.device.add_service(included_service)  # type: ignore
        self.ref.device.add_service(primary_service)  # type: ignore

        advertise = self.ref.host.Advertise(legacy=True, connectable=True)
        dut_ref_connection = self.dut.host.ConnectLE(public=self.ref.address,
                                                     own_address_type=RANDOM).connection
        assert dut_ref_connection
        advertise.cancel()  # type: ignore

        dut_gatt = GATT(self.dut.channel)  # type: ignore
        services = dut_gatt.DiscoverServices(dut_ref_connection).services

        filtered_services = [
            service for service in services if service.uuid == PRIMARY_SERVICE_UUID
        ]
        assert len(filtered_services) == 1
        discovered_primary_service = filtered_services[0]

        included_services_uuids = [
            included_service.uuid
            for included_service in discovered_primary_service.included_services
        ]
        assert_in(INCLUDED_SERVICE_UUID, included_services_uuids)


async def is_connected(device: PandoraDevice, connection: Connection) -> bool:
    try:
        await device.aio.host.WaitDisconnection(connection=connection, timeout=5)
        return False
    except grpc.RpcError as e:
        assert_equal(e.code(), grpc.StatusCode.DEADLINE_EXCEEDED)  # type: ignore
        return True


if __name__ == '__main__':
    logging.basicConfig(level=logging.DEBUG)
    test_runner.main()  # type: ignore
