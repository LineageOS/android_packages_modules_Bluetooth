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

import asyncio
import secrets
import struct
import uuid

from bumble import device
from bumble import gatt
from bumble import hci
from bumble.profiles import gatt_service
from mobly import test_runner
from mobly import signals
from typing_extensions import override

from navi.tests import navi_test_base
from navi.utils import android_constants
from navi.utils import bl4a_api

_DEFAULT_TIMEOUT = 10.0


class GattClientTest(navi_test_base.TwoDevicesTestBase):

    @override
    async def async_setup_class(self) -> None:
        await super().async_setup_class()

        if self.dut.getprop(android_constants.Property.GATT_ENABLED) != "true":
            raise signals.TestAbortClass("GATT is not enabled on DUT.")

    async def test_discover_services(self) -> None:
        """Test connect GATT as client.

    Test steps:
      1. Add a GATT server on REF.
      2. Start advertising on REF.
      3. Connect GATT and LE-ACL to REF from DUT.
      4. Discover GATT services from DUT.
      5. Check discovered services.
    """

        self.logger.info("[REF] Add GATT service.")
        service_uuid = str(uuid.uuid4())
        included_service_uuid = str(uuid.uuid4())
        included_service = gatt.Service(
            uuid=included_service_uuid,
            primary=False,
            characteristics=[],
        )
        self.ref.device.add_service(included_service)
        self.ref.device.add_service(
            gatt.Service(
                uuid=service_uuid,
                characteristics=[],
                included_services=[included_service],
            ))

        self.logger.info("[REF] Start advertising.")
        await self.ref.device.start_advertising(own_address_type=hci.OwnAddressType.RANDOM)

        self.logger.info("[DUT] Connect to REF.")
        gatt_client = await self.dut.bl4a.connect_gatt_client(
            self.ref.random_address,
            android_constants.Transport.LE,
            android_constants.AddressTypeStatus.RANDOM,
        )

        self.logger.info("[DUT] Discover services.")
        services = await gatt_client.discover_services()

        self.logger.info("[DUT] Check services.")
        primary_service = next((service for service in services if service.uuid == service_uuid),
                               None)
        if not primary_service:
            self.fail("Cannot find primary service.")

        self.logger.info("[DUT] Check included services.")
        self.assertNotEmpty(primary_service.included_services)
        self.assertEqual(
            primary_service.included_services[0].type,
            android_constants.GattServiceType.SECONDARY,
        )

    @navi_test_base.named_parameterized(
        no_requirements=gatt.Characteristic.Permissions.WRITEABLE,
        insufficient_authentication=gatt.Characteristic.Permissions.WRITE_REQUIRES_AUTHENTICATION,
        insufficient_encryption=gatt.Characteristic.Permissions.WRITE_REQUIRES_ENCRYPTION,
    )
    async def test_write_characteristic(self, permissions: gatt.Characteristic.Permissions) -> None:
        """Test write value to characteristics.

    Test steps:
      1. Add a GATT server with a writable characteristic on REF.
      2. Start advertising on REF.
      3. Connect GATT and LE-ACL to REF from DUT.
      4. Discover GATT services from DUT.
      5. Write characteristic value on REF from DUT.
      6. Check written value.

    Args:
      permissions: The permissions of the characteristic.
    """
        service_uuid = str(uuid.uuid4())
        characteristic_uuid = str(uuid.uuid4())

        write_future = asyncio.get_running_loop().create_future()

        def on_write(connection: device.Connection, value: bytes) -> None:
            del connection  # Unused.
            write_future.set_result(value)

        self.logger.info("[REF] Add GATT service.")
        self.ref.device.add_service(
            gatt.Service(
                uuid=service_uuid,
                characteristics=[
                    gatt.Characteristic(
                        uuid=characteristic_uuid,
                        properties=gatt.Characteristic.Properties.WRITE,
                        permissions=permissions,
                        value=gatt.CharacteristicValue(write=on_write),
                    )
                ],
            ))

        self.logger.info("[REF] Start advertising.")
        async with self.assert_not_timeout(_DEFAULT_TIMEOUT):
            await self.ref.device.start_advertising(own_address_type=hci.OwnAddressType.RANDOM)

        self.logger.info("[DUT] Connect to REF.")
        gatt_client = await self.dut.bl4a.connect_gatt_client(
            self.ref.random_address,
            android_constants.Transport.LE,
            android_constants.AddressTypeStatus.RANDOM,
        )

        self.logger.info("[DUT] Discover services.")
        services = await gatt_client.discover_services()

        characteristic = bl4a_api.find_characteristic_by_uuid(characteristic_uuid, services)

        if not characteristic.handle:
            self.fail("Cannot find characteristic handle.")

        expected_value = secrets.token_bytes(16)
        # When receiving insufficient_authentication or insufficient_encryption
        # error, Android should start pairing process.
        if permissions > gatt.Characteristic.Permissions.WRITEABLE:
            with self.dut.bl4a.register_callback(bl4a_api.Module.ADAPTER) as adapter_cb:
                self.logger.info("[DUT] Write characteristic.")
                write_task = asyncio.create_task(
                    gatt_client.write_characteristic(
                        characteristic.handle,
                        expected_value,
                        android_constants.GattWriteType.DEFAULT,
                    ))
                self.test_case_context.callback(write_task.cancel)

                self.logger.info("[DUT] Wait for pairing request.")
                await adapter_cb.wait_for_event(bl4a_api.PairingRequest)
        else:
            self.logger.info("[DUT] Write characteristic.")
            await gatt_client.write_characteristic(
                characteristic.handle,
                expected_value,
                android_constants.GattWriteType.DEFAULT,
            )

            self.logger.info("[REF] Check write value.")
            async with self.assert_not_timeout(_DEFAULT_TIMEOUT):
                self.assertEqual(expected_value, await write_future)

    @navi_test_base.named_parameterized(
        no_requirements=gatt.Characteristic.Permissions.READABLE,
        insufficient_authentication=gatt.Characteristic.Permissions.READ_REQUIRES_AUTHENTICATION,
        insufficient_encryption=gatt.Characteristic.Permissions.READ_REQUIRES_ENCRYPTION,
    )
    async def test_read_characteristic(self, permissions: gatt.Characteristic.Permissions) -> None:
        """Test read value from characteristics.

    Test steps:
      1. Add a GATT server with a readable characteristic on REF.
      2. Start advertising on REF.
      3. Connect GATT and LE-ACL to REF from DUT.
      4. Discover GATT services from DUT.
      5. Read characteristic value on REF from DUT.
      6. Check read value.

    Args:
      permissions: The permissions of the characteristic.
    """
        service_uuid = str(uuid.uuid4())
        characteristic_uuid = str(uuid.uuid4())
        expected_value = secrets.token_bytes(256)

        self.logger.info("[REF] Add GATT service.")
        self.ref.device.add_service(
            gatt.Service(
                uuid=service_uuid,
                characteristics=[
                    gatt.Characteristic(
                        uuid=characteristic_uuid,
                        properties=gatt.Characteristic.Properties.READ,
                        permissions=permissions,
                        value=expected_value,
                    )
                ],
            ))

        self.logger.info("[REF] Start advertising.")
        async with self.assert_not_timeout(_DEFAULT_TIMEOUT):
            await self.ref.device.start_advertising(own_address_type=hci.OwnAddressType.RANDOM)

        self.logger.info("[DUT] Connect to REF.")
        gatt_client = await self.dut.bl4a.connect_gatt_client(
            self.ref.random_address,
            android_constants.Transport.LE,
            android_constants.AddressTypeStatus.RANDOM,
        )

        self.logger.info("[DUT] Discover services.")
        services = await gatt_client.discover_services()
        characteristic = bl4a_api.find_characteristic_by_uuid(characteristic_uuid, services)

        if not characteristic.handle:
            self.fail("Cannot find characteristic handle.")

        # When receiving insufficient_authentication or insufficient_encryption
        # error, Android should start pairing process.
        if permissions > gatt.Characteristic.Permissions.READABLE:
            with self.dut.bl4a.register_callback(bl4a_api.Module.ADAPTER) as adapter_cb:
                self.logger.info("[DUT] Read characteristic.")
                read_task = asyncio.create_task(
                    gatt_client.read_characteristic(characteristic.handle))
                self.test_case_context.callback(read_task.cancel)

                self.logger.info("[DUT] Wait for pairing request.")
                await adapter_cb.wait_for_event(bl4a_api.PairingRequest)
        else:
            self.logger.info("[DUT] Read characteristic.")
            actual_value = await gatt_client.read_characteristic(characteristic.handle)

            self.logger.info("Check read value.")
            self.assertEqual(expected_value, actual_value)

    async def test_subscribe_characteristic(self) -> None:
        """Test subscribe value from characteristics.

    Test steps:
      1. Add a GATT server with a notifyable characteristic on REF.
      2. Start advertising on REF.
      3. Connect GATT(and LE-ACL) to REF from DUT.
      4. Discover GATT services from DUT.
      5. Subscribe characteristic value on REF from DUT.
      6. Notify subscribers from REF.
      7. Check read value.
    """
        service_uuid = str(uuid.uuid4())
        characteristic_uuid = str(uuid.uuid4())
        expected_value = secrets.token_bytes(256)

        ref_characteristic = gatt.Characteristic(
            uuid=characteristic_uuid,
            properties=(gatt.Characteristic.Properties.READ |
                        gatt.Characteristic.Properties.NOTIFY),
            permissions=gatt.Characteristic.Permissions.READABLE,
            value=expected_value,
        )

        self.logger.info("[REF] Add GATT service.")
        self.ref.device.add_service(
            gatt.Service(
                uuid=service_uuid,
                characteristics=[ref_characteristic],
            ))

        self.logger.info("[REF] Start advertising.")
        await self.ref.device.start_advertising(own_address_type=hci.OwnAddressType.RANDOM)
        self.logger.info("[DUT] Connect to REF.")
        gatt_client = await self.dut.bl4a.connect_gatt_client(
            self.ref.random_address,
            android_constants.Transport.LE,
            android_constants.AddressTypeStatus.RANDOM,
        )
        self.logger.info("[DUT] Discover services.")
        services = await gatt_client.discover_services()
        characteristic = bl4a_api.find_characteristic_by_uuid(characteristic_uuid, services)

        if not characteristic.handle:
            self.fail("Cannot find characteristic handle.")

        self.logger.info("[DUT] Subscribe characteristic.")
        await gatt_client.subscribe_characteristic_notifications(characteristic.handle)

        self.logger.info("[REF] Notify subscribers.")
        expected_value = secrets.token_bytes(16)
        await self.ref.device.notify_subscribers(ref_characteristic, expected_value)

        self.logger.info("Check notified value.")
        await gatt_client.wait_for_event(
            bl4a_api.GattCharacteristicChanged(
                address=self.ref.random_address,
                handle=characteristic.handle,
                value=expected_value,
            ),)

    async def test_service_changed_indication(self) -> None:
        """Test service changed indication.

    Test steps:
      1. Connect GATT to REF from DUT.
      2. Discover services from DUT.
      3. Add a new GATT service on REF.
      4. Check services are cached correctly.
      5. Notify service changed from REF.
      6. Wait for service changed indication from DUT.
      7. Re-discover services from DUT.
      8. Check new service is discovered.
    """
        ref_gatt_service = self.ref.device.gatt_service
        assert isinstance(ref_gatt_service, gatt_service.GenericAttributeProfileService)
        ref_service_changed_characteristic = (ref_gatt_service.service_changed_characteristic)
        assert isinstance(ref_service_changed_characteristic, gatt.Characteristic)

        self.logger.info("[REF] Start advertising.")
        async with self.assert_not_timeout(_DEFAULT_TIMEOUT):
            await self.ref.device.start_advertising(own_address_type=hci.OwnAddressType.RANDOM)

        self.logger.info("[DUT] Connect to REF.")
        gatt_client = await self.dut.bl4a.connect_gatt_client(
            self.ref.random_address,
            android_constants.Transport.LE,
            android_constants.AddressTypeStatus.RANDOM,
        )

        self.logger.info("[DUT] Discover services.")
        services = await gatt_client.discover_services()

        # When service changed characteristic is present, Android should cache
        # the services and not re-discover them unless service changed indication
        # is received.
        self.logger.info("[REF] Add a new GATT service.")
        new_service = gatt.Service(
            uuid=str(uuid.uuid4()),
            characteristics=[],
        )
        self.ref.device.add_service(new_service)
        self.assertCountEqual(
            services,
            await gatt_client.discover_services(),
            "Services are not cached correctly.",
        )

        self.logger.info("[REF] Notify service changed.")
        async with self.assert_not_timeout(_DEFAULT_TIMEOUT):
            for connection in self.ref.device.connections.values():
                # This is a workaround - Currently, Android doesn't always subscribe to
                # the service changed characteristic after connection.
                await self.ref.device.indicate_subscriber(
                    connection=connection,
                    attribute=ref_service_changed_characteristic,
                    value=struct.pack("<HH", 0x0000, 0xFFFF),
                    force=True,
                )

        self.logger.info("[DUT] Wait for service changed.")
        await gatt_client.wait_for_event(bl4a_api.GattServiceChanged)

        self.logger.info("[DUT] Re-discover services.")
        services = await gatt_client.discover_services()
        self.assertIn(
            new_service.uuid,
            (service.uuid for service in services),
            "New service is not discovered.",
        )

    async def test_reconnect_after_disconnect(self) -> None:
        """Test disconnect connection.

    Test steps:
      1. Connect GATT to REF from DUT.
      2. Discover services from DUT.
      3. Disconnect GATT from DUT.
      4. Reconnect GATT to REF from DUT.
      5. Check connection state.
    """
        for _ in range(2):
            service_uuid = str(uuid.uuid4())
            self.ref.device.add_service(gatt.Service(uuid=service_uuid, characteristics=[]))

            self.logger.info("[REF] Start advertising.")
            await self.ref.device.start_advertising(own_address_type=hci.OwnAddressType.RANDOM)

            self.logger.info("[DUT] Connect to REF.")
            gatt_client = await self.dut.bl4a.connect_gatt_client(
                self.ref.random_address,
                android_constants.Transport.LE,
                android_constants.AddressTypeStatus.RANDOM,
            )

            self.logger.info("[DUT] Discover services.")
            services = await gatt_client.discover_services()

            self.logger.info("[DUT] Check services.")
            service_uuids = [service.uuid for service in services]
            self.assertIn(service_uuid, service_uuids)

            self.logger.info("[DUT] Disconnect.")
            await gatt_client.disconnect()


if __name__ == "__main__":
    test_runner.main()
