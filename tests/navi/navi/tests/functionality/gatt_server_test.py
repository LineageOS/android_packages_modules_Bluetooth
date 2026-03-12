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
"""Tests for GATT Server."""

from __future__ import annotations

import asyncio
import secrets
import uuid

from bumble import core
from bumble import device
from bumble import gatt
from bumble import gatt_client
from bumble import hci
from bumble import l2cap
from mobly import test_runner
from mobly import signals
from typing_extensions import override

from navi.tests import navi_test_base
from navi.utils import android_constants
from navi.utils import bl4a_api
from navi.utils import bluetooth_constants
from navi.utils import retry

_DEFAULT_STEP_TIMEOUT_SECONDS = 10.0
_CCCD_UUID = (
    bluetooth_constants.BluetoothAssignedUuid.CLIENT_CHARACTERISTIC_CONFIGURATION_DESCRIPTOR)

_Property = android_constants.GattCharacteristicProperty
_Permission = android_constants.GattCharacteristicPermission


class GattServerVentiTest(navi_test_base.TwoDevicesTestBase):
    """Tests for GATT Server role."""

    dut_name: str

    @override
    async def async_setup_class(self) -> None:
        await super().async_setup_class()
        if self.dut.getprop(android_constants.Property.GATT_ENABLED) != "true":
            raise signals.TestAbortClass("GATT is not enabled on DUT.")

    @override
    async def async_setup_test(self) -> None:
        await super().async_setup_test()

        # Use a unique name to avoid conflicts.
        self.dut_name = f"gatt_server_test_{uuid.uuid4().hex[:8]}"
        self.dut.bt.setName(self.dut_name)
        self.logger.info("dut_name: %s", self.dut.bt.getName())

    async def _setup_gatt_server(self, is_private: bool = False) -> bl4a_api.GattServer:
        """Sets up a private GATT server on DUT."""
        dut_gatt_server = self.dut.bl4a.create_gatt_server()
        self.test_case_context.enter_context(dut_gatt_server)
        self.logger.info("[DUT] Start advertising with Non-resolvable private address.")
        advertiser = await self.dut.bl4a.start_extended_advertising_set(
            bl4a_api.AdvertisingSetParameters(
                connectable=True,
                own_address_type=(android_constants.AddressTypeStatus.RANDOM_NON_RESOLVABLE
                                  if is_private else android_constants.AddressTypeStatus.RANDOM),
            ),
            gatt_server=dut_gatt_server if is_private else None,
            advertising_data=bl4a_api.AdvertisingData(include_device_name=True),
            scan_response=bl4a_api.AdvertisingData(),
            periodic_advertising_parameters=None,
            periodic_advertising_data=None,
            duration=0,
            max_extended_advertising_events=0,
        )
        self.test_case_context.enter_context(advertiser)
        return dut_gatt_server

    @retry.retry_on_exception()
    async def _make_le_connection(self) -> device.Connection:
        """Connects to DUT over LE and returns the connection."""
        ref_dut_acl = await self.ref.device.connect(
            self.dut_name,
            transport=core.BT_LE_TRANSPORT,
            timeout=_DEFAULT_STEP_TIMEOUT_SECONDS,
            own_address_type=hci.OwnAddressType.RANDOM,
        )
        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            await ref_dut_acl.get_remote_le_features()
        return ref_dut_acl

    async def test_private_server_add_service(self) -> None:
        """Tests opening a GATT server on DUT, adding a service discovered by REF.

    Test steps:
      1. Open a GATT server on DUT.
      2. Add a GATT service to the server instance.
      3. Discover services from REF.
      4. Verify added service is discovered.
    """
        service_uuid = str(uuid.uuid4())
        characteristic_uuid = str(uuid.uuid4())
        dut_gatt_server = await self._setup_gatt_server(is_private=True)

        self.logger.info("[DUT] Add a service.")
        await dut_gatt_server.add_service(
            bl4a_api.GattService(
                uuid=service_uuid,
                characteristics=[
                    bl4a_api.GattCharacteristic(
                        uuid=characteristic_uuid,
                        properties=_Property.READ,
                        permissions=_Permission.READ,
                    )
                ],
            ),)

        self.logger.info("[REF] Connect to DUT.")
        ref_dut_acl = await self._make_le_connection()

        async with device.Peer(ref_dut_acl) as peer:
            self.logger.info("[REF] Check services.")
            services = await peer.discover_services([core.UUID(service_uuid)])
            self.assertLen(services, 1)
            characteristics = await peer.discover_characteristics([core.UUID(characteristic_uuid)],
                                                                  services[0])
            self.assertLen(characteristics, 1)
            self.assertEqual(characteristics[0].properties, gatt.Characteristic.Properties.READ)

    async def test_private_server_handle_characteristic_read_request(self,) -> None:
        """Tests handling a characteristic read request.

    Test steps:
      1. Open a GATT server on DUT.
      2. Add a GATT service including a readable characteristic to the server
      instance.
      3. Read characteristic from REF.
      4. Handle the read request and send response from DUT.
      5. Check read result from REF.
    """
        # UUID must be random here, otherwise there might be interference when
        # multiple tests run in the same box.
        service_uuid = str(uuid.uuid4())
        characteristic_uuid = str(uuid.uuid4())
        dut_gatt_server = await self._setup_gatt_server(is_private=True)

        self.logger.info("[DUT] Add a service.")
        await dut_gatt_server.add_service(
            bl4a_api.GattService(
                uuid=service_uuid,
                characteristics=[
                    bl4a_api.GattCharacteristic(
                        uuid=characteristic_uuid,
                        properties=_Property.READ,
                        permissions=_Permission.READ,
                    )
                ],
            ),)

        self.logger.info("[REF] Connect to DUT.")
        ref_dut_acl = await self._make_le_connection()

        async with device.Peer(ref_dut_acl) as peer:
            services = await peer.discover_services([core.UUID(service_uuid)])
            self.assertLen(services, 1)
            characteristics = await peer.discover_characteristics([core.UUID(characteristic_uuid)],
                                                                  services[0])
            self.assertLen(characteristics, 1)
            characteristic = characteristics[0]

            self.logger.info("[REF] Read characteristic.")
            read_task = asyncio.create_task(characteristic.read_value())

            read_request = await dut_gatt_server.wait_for_event(
                event=bl4a_api.GattCharacteristicReadRequest,
                predicate=lambda request: (request.characteristic_uuid == characteristic_uuid),
            )
            expected_data = secrets.token_bytes(16)
            dut_gatt_server.send_response(
                address=read_request.address,
                request_id=read_request.request_id,
                status=android_constants.GattStatus.SUCCESS,
                value=expected_data,
            )
            self.assertEqual(await read_task, expected_data)

    @navi_test_base.named_parameterized(
        with_response=True,
        without_response=False,
    )
    async def test_private_server_handle_characteristic_write_request(self,
                                                                      with_response: bool) -> None:
        """Tests handling a characteristic write request.

    Test steps:
      1. Open a GATT server on DUT.
      2. Add a GATT service including a writable characteristic to the server
      instance.
      3. Write characteristic from REF.
      4. Handle the write request and send response from DUT.
      5. Check write result from REF.

    Args:
      with_response: Whether to test write with response or without response. If
        True, test write with response; otherwise, test write without response.
    """

        # UUID must be random here, otherwise there might be interference when
        # multiple tests run in the same box.
        service_uuid = str(uuid.uuid4())
        characteristic_uuid = str(uuid.uuid4())
        dut_gatt_server = await self._setup_gatt_server(is_private=True)

        self.logger.info("[DUT] Add a service.")
        await dut_gatt_server.add_service(
            bl4a_api.GattService(
                uuid=service_uuid,
                characteristics=[
                    bl4a_api.GattCharacteristic(
                        uuid=characteristic_uuid,
                        properties=_Property.WRITE | _Property.WRITE_NO_RESPONSE,
                        permissions=_Permission.WRITE,
                    )
                ],
            ),)

        self.logger.info("[REF] Connect to DUT.")
        ref_dut_acl = await self._make_le_connection()

        async with device.Peer(ref_dut_acl) as peer:
            services = await peer.discover_services([core.UUID(service_uuid)])
            self.assertLen(services, 1)
            characteristics = await peer.discover_characteristics([core.UUID(characteristic_uuid)],
                                                                  services[0])
            self.assertLen(characteristics, 1)
            characteristic = characteristics[0]

            self.logger.info("[REF] Write characteristic.")
            expected_data = secrets.token_bytes(16)
            write_task = asyncio.create_task(
                characteristic.write_value(expected_data, with_response=with_response))

            write_request = await dut_gatt_server.wait_for_event(
                event=bl4a_api.GattCharacteristicWriteRequest,
                predicate=lambda request: (request.characteristic_uuid == characteristic_uuid),
            )
            self.assertEqual(write_request.value, expected_data)
            self.assertEqual(write_request.response_needed, with_response)

            dut_gatt_server.send_response(
                address=write_request.address,
                request_id=write_request.request_id,
                status=android_constants.GattStatus.SUCCESS,
                value=b"",
            )
            await write_task

    async def test_private_server_handle_subscription(self) -> None:
        """Tests sending GATT notification / indication to REF.

    Test steps:
      1. Add a GATT service including a characteristic to the server instance.
      2. Subscribe GATT characteristic from REF.
      3. Handle the subscribe request (CCCD write) from DUT.
      4. Send notification from DUT.
      5. Check notification from REF.
    """

        # UUID must be random here, otherwise there might be interference when
        # multiple tests run in the same box.
        service_uuid = str(uuid.uuid4())
        characteristic_uuid = str(uuid.uuid4())
        dut_gatt_server = await self._setup_gatt_server(is_private=True)

        self.logger.info("[DUT] Add a service.")
        await dut_gatt_server.add_service(
            bl4a_api.GattService(
                uuid=service_uuid,
                characteristics=[
                    bl4a_api.GattCharacteristic(
                        uuid=characteristic_uuid,
                        properties=(_Property.READ | _Property.NOTIFY | _Property.INDICATE),
                        permissions=_Permission.READ,
                        descriptors=[
                            bl4a_api.GattDescriptor(
                                uuid=_CCCD_UUID,
                                permissions=_Permission.READ | _Permission.WRITE,
                            )
                        ],
                    )
                ],
            ),)
        dut_characteristic = bl4a_api.find_characteristic_by_uuid(characteristic_uuid,
                                                                  dut_gatt_server.services)
        if not dut_characteristic.handle:
            self.fail("Cannot find characteristic.")

        self.logger.info("[REF] Connect to DUT.")
        ref_dut_acl = await self._make_le_connection()

        async with device.Peer(ref_dut_acl) as peer:
            target_services = peer.get_services_by_uuid(uuid=core.UUID(service_uuid))

            if not target_services:
                self.fail("Cannot find service.")

            ref_characteristics = peer.get_characteristics_by_uuid(
                core.UUID(characteristic_uuid),
                target_services[0],
            )

            if not ref_characteristics:
                self.fail("Cannot find characteristic.")

            ref_characteristic = ref_characteristics[0]
            self.logger.info("[REF] Subscribe characteristic.")
            notification_queue = asyncio.Queue[bytes]()
            expected_data = secrets.token_bytes(16)
            subscribe_task = asyncio.create_task(
                ref_characteristic.subscribe(notification_queue.put_nowait, prefer_notify=False))

            self.logger.info("[DUT] Wait for CCCD write.")
            subscribe_request = await dut_gatt_server.wait_for_event(
                event=bl4a_api.GattDescriptorWriteRequest,
                predicate=lambda request: (request.characteristic_handle == dut_characteristic.
                                           handle and request.descriptor_uuid == _CCCD_UUID),
            )

            self.logger.info("[DUT] Respond to CCCD write.")
            dut_gatt_server.send_response(
                address=subscribe_request.address,
                request_id=subscribe_request.request_id,
                status=android_constants.GattStatus.SUCCESS,
                value=b"",
            )

            self.logger.info("[REF] Wait subscription complete.")
            async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
                await subscribe_task

            self.logger.info("[DUT] Send notification.")
            dut_gatt_server.send_notification(
                address=self.ref.random_address,
                characteristic_handle=dut_characteristic.handle,
                # True for indication, False for notification. Currently rust
                # implementation only supports indication due to which notification
                # test fails.
                # TODO: Add NOTIFY (0x10) in rust/private_gatt
                confirm=True,
                value=expected_data,
            )

            self.logger.info("[REF] Wait for notification.")
            async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
                self.assertEqual(await notification_queue.get(), expected_data)

    async def test_eatt_connection_without_encryption(self) -> None:
        """Tests EATT connection without encryption should fail.

    Test steps:
      1. Start advertising on DUT.
      2. Connect to DUT over LE.
      3. Try to connect to EATT.
      4. Verify that EATT connection fails.
    """

        self.logger.info("[DUT] Start advertising.")
        advertiser = await self.dut.bl4a.start_extended_advertising_set(
            bl4a_api.AdvertisingSetParameters(
                connectable=True,
                own_address_type=(android_constants.AddressTypeStatus.RANDOM),
            ),
            advertising_data=bl4a_api.AdvertisingData(include_device_name=True),
        )
        self.test_case_context.enter_context(advertiser)

        self.logger.info("[REF] Connect to DUT.")
        ref_dut_acl = await self._make_le_connection()

        self.logger.info("[REF] Try to connect to EATT.")
        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            with self.assertRaises(l2cap.L2capError):
                await gatt_client.Client.connect_eatt(ref_dut_acl)


if __name__ == "__main__":
    test_runner.main()
