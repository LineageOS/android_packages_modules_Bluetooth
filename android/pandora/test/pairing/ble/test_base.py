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

from mobly.asserts import assert_equal, fail

from pairing.test_base import PairTestBase

from avatar import asynchronous

from bumble.att import (
    ATT_Error,
    ATT_INSUFFICIENT_AUTHENTICATION_ERROR,
    ATT_INSUFFICIENT_ENCRYPTION_ERROR,
)

from bumble.gatt import (
    Service,
    Characteristic,
    CharacteristicValue,
)

from bumble.pairing import PairingConfig

from pandora.host_pb2 import (
    RANDOM,
    DataTypes,
)

from pandora.security_pb2 import (
    LE_LEVEL3,
    PairingEventAnswer,
)

from pandora_experimental.gatt_grpc_aio import GATT as AioGATT

from pandora_experimental.gatt_pb2 import DiscoverServicesRequest

AUTHENTICATION_ERROR_RETURNED = [False, False]


def _gatt_read_with_error(connection):
    if not connection.is_encrypted:
        raise ATT_Error(ATT_INSUFFICIENT_ENCRYPTION_ERROR)

    if AUTHENTICATION_ERROR_RETURNED[0]:
        return bytes([1])

    AUTHENTICATION_ERROR_RETURNED[0] = True
    raise ATT_Error(ATT_INSUFFICIENT_AUTHENTICATION_ERROR)


def _gatt_write_with_error(connection, _value):
    if not connection.is_encrypted:
        raise ATT_Error(ATT_INSUFFICIENT_ENCRYPTION_ERROR)

    if not AUTHENTICATION_ERROR_RETURNED[1]:
        AUTHENTICATION_ERROR_RETURNED[1] = True
        raise ATT_Error(ATT_INSUFFICIENT_AUTHENTICATION_ERROR)


class BLEPairTestBase(PairTestBase):

    async def start_acl_connection(self):
        adv_seed = b'pause cafe'
        # responder starts advertising
        resp_advertisement = self.acl_responder.aio.host.Advertise(
            legacy=True,
            connectable=True,
            own_address_type=PairingConfig.AddressType.RANDOM,
            data=DataTypes(manufacturer_specific_data=adv_seed),
        )

        # initiator starts scanning
        init_scanning = self.acl_initiator.aio.host.Scan(
            own_address_type=PairingConfig.AddressType.RANDOM)
        init_scan_res = await anext(
            (x async for x in init_scanning if adv_seed in x.data.manufacturer_specific_data))
        init_scanning.cancel()

        init_res, resp_res = await asyncio.gather(
            self.acl_initiator.aio.host.ConnectLE(own_address_type=PairingConfig.AddressType.RANDOM,
                                                  **init_scan_res.address_asdict()),
            anext(aiter(resp_advertisement)),
        )

        resp_advertisement.cancel()

        assert_equal(init_res.result_variant(), 'connection')

        return init_res, resp_res

    async def start_pairing(
        self,
        initiator_acl_connection,
        responder_acl_connection,
    ):
        init_res, resp_res = await asyncio.gather(
            self.pairing_initiator.aio.security.Secure(connection=initiator_acl_connection,
                                                       le=LE_LEVEL3),
            self.pairing_responder.aio.security.WaitSecurity(connection=responder_acl_connection,
                                                             le=LE_LEVEL3),
        )

        # verify that pairing succeeded
        assert_equal(init_res.result_variant(), 'success')
        assert_equal(resp_res.result_variant(), 'success')

        return init_res, resp_res

    async def start_service_access(
        self,
        initiator_acl_connection,
        responder_acl_connection,
    ):
        # acl connection initiated from bumble
        assert_equal(self.dut, self.acl_initiator)
        serv_uuid = '50DB505C-8AC4-4738-8448-3B1D9CC09CC5'
        char_uuid = '552957FB-CF1F-4A31-9535-E78847E1A714'

        # to trigger pairing,
        # add a GATT service with some characteristic on bumble
        # access to which will return security error
        self.ref.device.add_service(
            Service(
                serv_uuid,
                [
                    Characteristic(
                        char_uuid,
                        Characteristic.Properties.READ | Characteristic.Properties.WRITE,
                        Characteristic.READABLE | Characteristic.WRITEABLE,
                        CharacteristicValue(read=_gatt_read_with_error,
                                            write=_gatt_write_with_error),
                    )
                ],
            ))

        dut_gatt = AioGATT(self.dut.aio.channel)

        services = await dut_gatt.DiscoverServices(connection=initiator_acl_connection)
        for service in services.services:
            for char in service.characteristics:
                if char.uuid == char_uuid:
                    await dut_gatt.ReadCharacteristicFromHandle(handle=char.handle,
                                                                connection=initiator_acl_connection)

        return True


class BLEPairTestBaseWithGeneralPairingTests(BLEPairTestBase):

    @asynchronous
    async def test_general_pairing(self) -> None:
        # role setup
        self.acl_initiator = self.dut
        self.acl_responder = self.ref
        self.pairing_initiator = self.dut
        self.pairing_responder = self.ref
        self.service_initiator = self.dut
        self.service_responder = self.ref

        self.prepare_pairing()

        android_res, bumble_res = await self.start_acl_connection()

        service_access_task = asyncio.create_task(
            self.start_service_access(android_res.connection, bumble_res.connection))

        await self.accept_pairing()

        try:
            _ = await asyncio.wait_for(service_access_task, timeout=5.0)
        except:
            fail("connection should have succeeded")


class BLEPairTestBaseWithGeneralAndDedicatedPairingTests(BLEPairTestBaseWithGeneralPairingTests):

    @asynchronous
    async def test_dedicated_pairing_ref_initiate_1(self) -> None:
        '''
        acl:
            ref: initiator
            dut: responder

        pairing:
            ref: initiator
            dut: responder
        '''

        self.acl_initiator = self.ref
        self.acl_responder = self.dut
        self.pairing_initiator = self.ref
        self.pairing_responder = self.dut

        self.prepare_pairing()

        bumble_res, android_res = await self.start_acl_connection()

        pairing_task = asyncio.create_task(
            self.start_pairing(bumble_res.connection, android_res.connection))
        await self.accept_pairing()
        await asyncio.wait_for(pairing_task, timeout=10.0)

    @asynchronous
    async def test_dedicated_pairing_ref_initiate_2(self) -> None:
        '''
        acl:
            ref: initiator
            dut: responder

        pairing:
            ref: responder
            dut: initiator
        '''

        self.acl_initiator = self.ref
        self.acl_responder = self.dut
        self.pairing_initiator = self.dut
        self.pairing_responder = self.ref

        self.prepare_pairing()

        bumble_res, android_res = await self.start_acl_connection()

        pairing_task = asyncio.create_task(
            self.start_pairing(android_res.connection, bumble_res.connection))
        await self.accept_pairing()
        await asyncio.wait_for(pairing_task, timeout=10.0)

    @asynchronous
    async def test_dedicated_pairing_dut_initiate_1(self) -> None:
        '''
        acl:
            ref: responder
            dut: initiator

        pairing:
            ref: responder
            dut: initiator
        '''

        self.acl_initiator = self.dut
        self.acl_responder = self.ref
        self.pairing_initiator = self.dut
        self.pairing_responder = self.ref

        self.prepare_pairing()

        android_res, bumble_res = await self.start_acl_connection()

        pairing_task = asyncio.create_task(
            self.start_pairing(android_res.connection, bumble_res.connection))
        await self.accept_pairing()
        await asyncio.wait_for(pairing_task, timeout=10.0)

    @asynchronous
    async def test_dedicated_pairing_dut_initiate_2(self) -> None:
        '''
        acl:
            ref: responder
            dut: initiator

        pairing:
            ref: responder
            dut: initiator
        '''

        self.acl_initiator = self.dut
        self.acl_responder = self.ref
        self.pairing_initiator = self.ref
        self.pairing_responder = self.dut

        self.prepare_pairing()

        android_res, bumble_res = await self.start_acl_connection()

        pairing_task = asyncio.create_task(
            self.start_pairing(bumble_res.connection, android_res.connection))
        await self.accept_pairing()
        await asyncio.wait_for(pairing_task, timeout=10.0)
