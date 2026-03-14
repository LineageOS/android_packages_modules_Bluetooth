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
import contextlib
import enum
import itertools
import uuid

from bumble import core
from bumble import data_types
from bumble import device
from bumble import hci
from mobly import test_runner

from navi.tests import navi_test_base
from navi.utils import android_constants
from navi.utils import bl4a_api
from navi.utils import pyee_extensions
from navi.utils import retry

# pylint: disable=cell-var-from-loop

_DEFAULT_TIMEOUT_SECONDS = 15.0
_MIN_ADVERTISING_INTERVAL_MS = 20
_DISCOVERY_TIMEOUT_SECONDS = 12.0

_OwnAddressType = hci.OwnAddressType
_AdvertisingData = core.AdvertisingData


class _AdvertisingVariant(enum.Enum):
    LEGACY_NO_ADV_DATA = enum.auto()
    LEGACY_CCCDK_SERVICE_UUID_AND_DATA = enum.auto()
    EXTENDED_ADV_DATA_1_BYTES = enum.auto()
    EXTENDED_ADV_DATA_200_BYTES = enum.auto()


class LeHostTest(navi_test_base.TwoDevicesTestBase):

    @navi_test_base.parameterized(
        _OwnAddressType.PUBLIC,
        _OwnAddressType.RANDOM,
    )
    @navi_test_base.retry(max_count=2)
    async def test_outgoing_connect_disconnect(self, ref_address_type: hci.OwnAddressType) -> None:
        """Tests outgoing LE connection and disconnection.

    Test steps:
      1. Start advertising on REF.
      2. Connect REF from DUT.
      3. Wait for BLE connected.
      4. Disconnect REF from DUT.

    Args:
      ref_address_type: address type of REF device used in advertisements.
    """
        match ref_address_type:
            case _OwnAddressType.PUBLIC:
                ref_address = str(self.ref.address)
            case _OwnAddressType.RANDOM:
                ref_address = str(self.ref.random_address)
            case _:
                self.fail(f"Invalid address type {ref_address_type}.")

        with self.dut.bl4a.register_callback(bl4a_api.Module.ADAPTER) as dut_cb:

            # [REF] Start advertising.
            await self.ref.device.start_advertising(
                own_address_type=ref_address_type,
                advertising_type=device.AdvertisingType.UNDIRECTED_CONNECTABLE_SCANNABLE,
                advertising_interval_min=_MIN_ADVERTISING_INTERVAL_MS,
                advertising_interval_max=_MIN_ADVERTISING_INTERVAL_MS,
            )

            # [DUT] Connect GATT.
            gatt_client = await self.dut.bl4a.connect_gatt_client(
                address=ref_address,
                transport=android_constants.Transport.LE,
                address_type=android_constants.AddressTypeStatus(ref_address_type.value),
            )
            await dut_cb.wait_for_event(event=bl4a_api.AclConnected(
                address=ref_address, transport=android_constants.Transport.LE),)
            # [DUT] Disconnect GATT.
            await gatt_client.disconnect()
            await dut_cb.wait_for_event(
                bl4a_api.AclDisconnected(
                    address=ref_address,
                    transport=android_constants.Transport.LE,
                ),)

    @navi_test_base.retry(max_count=2)
    async def test_incoming_connect_disconnect(self) -> None:
        """Tests incoming LE connection and disconnection.

    Test steps:
      1. Start advertising on DUT.
      2. Connect DUT from REF.
      3. Wait for BLE connected.
      4. Disconnect DUT from REF.
    """

        # [DUT] Start advertising with Public address.
        await self.dut.bl4a.start_legacy_advertiser(
            bl4a_api.LegacyAdvertiseSettings(own_address_type=_OwnAddressType.PUBLIC),)

        with self.dut.bl4a.register_callback(bl4a_api.Module.ADAPTER) as dut_cb:
            # [REF] Connect GATT.
            ref_dut_acl = await self.ref.device.connect(
                f"{self.dut.address}/P",
                core.BT_LE_TRANSPORT,
                own_address_type=_OwnAddressType.PUBLIC,
            )
            await ref_dut_acl.get_remote_le_features()

            # [DUT] Wait for LE-ACL connected.
            await dut_cb.wait_for_event(event=bl4a_api.AclConnected(
                address=self.ref.address, transport=android_constants.Transport.LE),)

            # [REF] Disconnect.
            await ref_dut_acl.disconnect()
            # [DUT] Wait for LE-ACL disconnected.
            await dut_cb.wait_for_event(
                bl4a_api.AclDisconnected(
                    address=self.ref.address,
                    transport=android_constants.Transport.LE,
                ),)

    @navi_test_base.parameterized(
        _AdvertisingVariant.LEGACY_NO_ADV_DATA,
        _AdvertisingVariant.LEGACY_CCCDK_SERVICE_UUID_AND_DATA,
        _AdvertisingVariant.EXTENDED_ADV_DATA_1_BYTES,
        _AdvertisingVariant.EXTENDED_ADV_DATA_200_BYTES,
    )
    async def test_scan(self, ref_advertising_variant: _AdvertisingVariant) -> None:
        """Tests scanning remote devices.

    Test steps:
      1. Start advertising on REF.
      2. Start scanning on DUT.
      3. Wait for matched scan result.

    Args:
      ref_advertising_variant: advertising variant of REF device.
    """
        scan_filter = bl4a_api.ScanFilter(
            device=self.ref.address,
            address_type=android_constants.AddressTypeStatus.PUBLIC,
        )
        match ref_advertising_variant:
            case _AdvertisingVariant.LEGACY_NO_ADV_DATA:
                advertising_data = b""
                advertising_properties = device.AdvertisingEventProperties(
                    is_connectable=True,
                    is_scannable=True,
                    is_legacy=True,
                )
            case _AdvertisingVariant.EXTENDED_ADV_DATA_1_BYTES:
                advertising_data = bytes(1)
                advertising_properties = device.AdvertisingEventProperties(is_connectable=True,)
            case _AdvertisingVariant.EXTENDED_ADV_DATA_200_BYTES:
                advertising_data = bytes(200)
                advertising_properties = device.AdvertisingEventProperties(is_connectable=True,)
            case _AdvertisingVariant.LEGACY_CCCDK_SERVICE_UUID_AND_DATA:
                advertising_data = bytes(
                    core.AdvertisingData([
                        data_types.CompleteListOf16BitServiceUUIDs([core.UUID("FFF5")]),
                        data_types.ServiceData128BitUUID(
                            core.UUID("5810bbc0-b499-11e9-a2a3-2a2ae2dbcce4"),
                            bytes.fromhex("01") + bytes.fromhex("0002"),
                        ),
                    ]))
                advertising_properties = device.AdvertisingEventProperties(
                    is_connectable=True,
                    is_scannable=True,
                    is_legacy=True,
                )
                scan_filter = bl4a_api.ScanFilter(
                    service_uuids="0000fff5-0000-1000-8000-00805f9b34fb")
            case _:
                self.fail(f"Invalid advertising variant {ref_advertising_variant}.")

        # [REF] Start advertising.
        await self.ref.device.create_advertising_set(
            advertising_parameters=device.AdvertisingParameters(
                primary_advertising_interval_min=_MIN_ADVERTISING_INTERVAL_MS,
                primary_advertising_interval_max=_MIN_ADVERTISING_INTERVAL_MS,
                own_address_type=_OwnAddressType.PUBLIC,
                advertising_event_properties=advertising_properties,
            ),
            advertising_data=advertising_data,
        )
        # [DUT] Start scanning.
        with self.dut.bl4a.start_scanning(
                scan_settings=bl4a_api.ScanSettings(legacy=False,),
                scan_filter=scan_filter,
        ) as scan_cb:
            # [DUT] Wait for advertising report(scan result) from REF.
            event = await scan_cb.wait_for_event(bl4a_api.ScanResult)
            self.assertEqual(event.address, self.ref.address)

    async def test_advertising_with_service_uuid(self) -> None:
        """Tests advertising using RPA, with Service UUID included in AdvertisingData.

    Test steps:
      1. Start advertising on DUT.
      2. Start scanning on REF.
      3. Wait for matched scan result.
    """
        with pyee_extensions.EventWatcher() as watcher:
            # Generate a random UUID for testing.
            service_uuid = str(uuid.uuid4())

            # [DUT] Start advertising with service UUID and RPA.
            advertise = await self.dut.bl4a.start_legacy_advertiser(
                bl4a_api.LegacyAdvertiseSettings(own_address_type=_OwnAddressType.PUBLIC),
                bl4a_api.AdvertisingData(service_uuids=[service_uuid]),
            )

            # [REF] Scan for DUT.
            scan_results = asyncio.Queue[device.Advertisement]()

            @watcher.on(self.ref.device, self.ref.device.EVENT_ADVERTISEMENT)
            def _(adv: device.Advertisement) -> None:
                if (service_uuids := adv.data.get(
                        _AdvertisingData.Type.COMPLETE_LIST_OF_128_BIT_SERVICE_CLASS_UUIDS)
                   ) and service_uuid in service_uuids:
                    scan_results.put_nowait(adv)

            await self.ref.device.start_scanning()
            # [REF] Wait for advertising report(scan result) from DUT.
            async with self.assert_not_timeout(_DEFAULT_TIMEOUT_SECONDS):
                await scan_results.get()
            advertise.stop()

    async def test_advertising_with_public_address(self) -> None:
        """Tests advertising using Public Address.

    Test steps:
      1. Start advertising on DUT.
      2. Start scanning on REF.
      3. Wait for matched scan result.
    """
        with pyee_extensions.EventWatcher() as watcher:
            # [DUT] Start advertising with service UUID and Public address.
            advertise = await self.dut.bl4a.start_legacy_advertiser(
                bl4a_api.LegacyAdvertiseSettings(own_address_type=_OwnAddressType.PUBLIC),)

            # [REF] Scan for DUT.
            scan_results = asyncio.Queue[device.Advertisement]()
            dut_address = hci.Address(f"{self.dut.address}/P")

            @watcher.on(self.ref.device, self.ref.device.EVENT_ADVERTISEMENT)
            def on_advertising_report(adv: device.Advertisement) -> None:
                if adv.address == dut_address:
                    scan_results.put_nowait(adv)

            await self.ref.device.start_scanning()
            # [REF] Wait for advertising report(scan result) from DUT.
            async with self.assert_not_timeout(_DEFAULT_TIMEOUT_SECONDS):
                await scan_results.get()
            advertise.stop()

    @navi_test_base.parameterized(*itertools.product(
        (hci.Phy.LE_1M, hci.Phy.LE_2M, hci.Phy.LE_CODED),
        (
            android_constants.AddressTypeStatus.PUBLIC,
            android_constants.AddressTypeStatus.RANDOM,
            android_constants.AddressTypeStatus.RANDOM_NON_RESOLVABLE,
        ),
    ))
    async def test_extended_advertising(
            self, phy: int, own_address_type: android_constants.AddressTypeStatus) -> None:
        """Tests extended advertising, with different primary Phy settings.

    Test steps:
      1. Start advertising on DUT.
      2. Start scanning on REF.
      3. Wait for matched scan result.

    Args:
      phy: PHY option used in extended advertising.
      own_address_type: type of address used in the advertisement.
    """
        # Generate a random UUID for testing.
        service_uuid = str(uuid.uuid4())

        self.logger.info("[DUT] Start advertising with service UUID.")
        advertise = await self.dut.bl4a.start_extended_advertising_set(
            bl4a_api.AdvertisingSetParameters(
                secondary_phy=phy,
                own_address_type=own_address_type,
            ),
            bl4a_api.AdvertisingData(service_uuids=[service_uuid]),
            duration=0,
        )

        # [REF] Scan for DUT.
        scan_results = asyncio.Queue[device.Advertisement]()

        def on_advertising_report(adv: device.Advertisement) -> None:
            if (service_uuids := adv.data.get(
                    _AdvertisingData.Type.COMPLETE_LIST_OF_128_BIT_SERVICE_CLASS_UUIDS)
               ) and service_uuid in service_uuids:
                scan_results.put_nowait(adv)

        with pyee_extensions.EventWatcher() as watcher:
            watcher.on(self.ref.device, "advertisement", on_advertising_report)

            self.logger.info("[REF] Start scanning for DUT.")
            await self.ref.device.start_scanning()

            self.logger.info("[REF] Wait for advertising report from DUT.")
            async with self.assert_not_timeout(_DEFAULT_TIMEOUT_SECONDS):
                advertisement = await scan_results.get()
            advertise.stop()
            self.assertEqual(advertisement.secondary_phy, phy)

            match own_address_type:
                case android_constants.AddressTypeStatus.PUBLIC:
                    self.assertEqual(advertisement.address, hci.Address(f"{self.dut.address}/P"))
                case android_constants.AddressTypeStatus.RANDOM:
                    self.assertTrue(advertisement.address.is_random)
                    self.assertTrue(advertisement.address.is_resolvable)
                case android_constants.AddressTypeStatus.RANDOM_NON_RESOLVABLE:
                    self.assertTrue(advertisement.address.is_random)
                    self.assertFalse(advertisement.address.is_resolvable)
                case _:
                    self.fail(f"Invalid address type {own_address_type}.")

    async def test_periodic_advertising(self) -> None:
        """Tests periodic advertising.

    Test steps:
      1. Start advertising on DUT.
      2. Start scanning on REF.
      3. Wait for matched scan result.
      4. Create PA sync on REF.
      5. Wait for PA sync establishment.
      6. Wait for periodic advertisement from REF.
      7. Check that the periodic advertisement data contains the service UUID
      from the periodic advertising data.
    """
        if not self.dut.bt.isLePeriodicAdvertisingSupported():
            self.skipTest("DUT does not support periodic advertising.")

        # Generate a random UUID for testing.
        service_uuid = str(uuid.uuid4())
        service_uuid_2 = str(uuid.uuid4())

        self.logger.info("[DUT] Start advertising with service UUID.")
        advertising_set = await self.dut.bl4a.start_extended_advertising_set(
            bl4a_api.AdvertisingSetParameters(),
            bl4a_api.AdvertisingData(service_uuids=[service_uuid]),
            periodic_advertising_parameters=bl4a_api.PeriodicAdvertisingParameters(
                interval=100,
                include_tx_power_level=True,
            ),
            periodic_advertising_data=bl4a_api.AdvertisingData(service_uuids=[service_uuid_2]),
            duration=0,
        )
        self.test_case_context.enter_context(advertising_set)

        # [REF] Scan for DUT.
        advertisements = asyncio.Queue[device.Advertisement]()

        @self.ref.device.on(self.ref.device.EVENT_ADVERTISEMENT)
        def _(adv: device.Advertisement) -> None:
            if (service_uuids := adv.data.get(
                    _AdvertisingData.Type.COMPLETE_LIST_OF_128_BIT_SERVICE_CLASS_UUIDS)
               ) and service_uuid in service_uuids:
                advertisements.put_nowait(adv)

        async with self.assert_not_timeout(_DEFAULT_TIMEOUT_SECONDS):
            self.logger.info("[REF] Start scanning")
            await self.ref.device.start_scanning()
            self.logger.info("[REF] Wait for advertising report from DUT.")
            advertisement = await advertisements.get()

        # Periodic Synchronization may fail, so retry the process.
        @retry.retry_on_exception()
        async def sync_pa() -> device.PeriodicAdvertisingSync:
            self.logger.info("[REF] Creating periodic advertising sync.")
            pa_sync = await self.ref.device.create_periodic_advertising_sync(
                advertiser_address=advertisement.address, sid=advertisement.sid)
            if pa_sync.state != pa_sync.State.ESTABLISHED:
                pa_sync_result = asyncio.get_running_loop().create_future()
                pa_sync.once(pa_sync.EVENT_ESTABLISHMENT, lambda: pa_sync_result.set_result(None))
                pa_sync.once(
                    pa_sync.EVENT_ESTABLISHMENT_ERROR,
                    lambda: pa_sync_result.set_exception(hci.HCI_Error(pa_sync.status)),
                )
                self.logger.info("[REF] Waiting for PA sync establishment.")
                try:
                    await pa_sync_result
                finally:
                    if pa_sync.state == pa_sync.State.PENDING:
                        self.logger.info("[REF] Cancel PA sync.")
                        await pa_sync.terminate()
            return pa_sync

        async with self.assert_not_timeout(_DEFAULT_TIMEOUT_SECONDS):
            pa_sync = await sync_pa()
            periodic_advertisements = asyncio.Queue[device.PeriodicAdvertisement]()
            pa_sync.on(
                pa_sync.EVENT_PERIODIC_ADVERTISEMENT,
                periodic_advertisements.put_nowait,
            )
            self.logger.info("[REF] Wait for periodic advertisement.")
            periodic_advertisement = await periodic_advertisements.get()
            if not periodic_advertisement.data:  # pytype: disable=attribute-error
                self.fail("Periodic advertisement data is empty.")
            # Check that the periodic advertisement data contains the service UUID
            # from the periodic advertising data.
            self.assertEqual(
                periodic_advertisement.data.get(  # pytype: disable=attribute-error
                    _AdvertisingData.Type.COMPLETE_LIST_OF_128_BIT_SERVICE_CLASS_UUIDS),
                [service_uuid_2],
            )

    @navi_test_base.retry(max_count=2)
    async def test_le_discovery(self) -> None:
        """Test discover LE devices.

    Test steps:
      1. Disable Classic scan and start advertising on REF.
      2. Start discovery on REF.
      3. Wait for matched scan result.
    """
        with self.dut.bl4a.register_callback(bl4a_api.Module.ADAPTER) as dut_cb:

            await self.ref.device.set_scan_enable(inquiry_scan_enabled=False,
                                                  page_scan_enabled=False)
            # [REF] Start advertising.
            await self.ref.device.start_advertising(
                own_address_type=_OwnAddressType.PUBLIC,
                advertising_type=device.AdvertisingType.UNDIRECTED_CONNECTABLE_SCANNABLE,
                advertising_interval_min=_MIN_ADVERTISING_INTERVAL_MS,
                advertising_interval_max=_MIN_ADVERTISING_INTERVAL_MS,
                advertising_data=bytes(
                    _AdvertisingData([
                        (
                            _AdvertisingData.FLAGS,
                            bytes([_AdvertisingData.LE_GENERAL_DISCOVERABLE_MODE_FLAG]),
                        ),
                        (
                            _AdvertisingData.COMPLETE_LOCAL_NAME,
                            "Super Bumble".encode(),
                        ),
                    ])),
            )
            self.dut.bt.startInquiry()

            await dut_cb.wait_for_event(
                bl4a_api.DeviceFound,
                lambda e: (e.address == self.ref.address),
                _DISCOVERY_TIMEOUT_SECONDS,
            )

    @navi_test_base.parameterized(
        hci.OwnAddressType.PUBLIC,
        hci.OwnAddressType.RANDOM,
        hci.OwnAddressType.RESOLVABLE_OR_RANDOM,
        hci.OwnAddressType.RESOLVABLE_OR_PUBLIC,
    )
    async def test_scan_and_connect_after_pairing(self,
                                                  ref_address_type: hci.OwnAddressType) -> None:
        """Tests scanning remote devices after pairing(IRK exchanged).

    Test steps:
      1. Pair with REF.
      2. Disconnect from REF.
      3. Start advertising on REF.
      4. Start scanning on DUT.
      5. Wait for matched scan result.

    Args:
      ref_address_type: address type of REF device used in advertisements.
    """
        if ref_address_type in (
                hci.OwnAddressType.RESOLVABLE_OR_RANDOM,
                hci.OwnAddressType.RANDOM,
        ):
            identity_address = self.ref.random_address
            identity_address_type = android_constants.AddressTypeStatus.RANDOM
        else:
            identity_address = self.ref.address
            identity_address_type = android_constants.AddressTypeStatus.PUBLIC

        with self.dut.bl4a.register_callback(bl4a_api.Module.ADAPTER) as dut_cb:
            self.logger.info("[DUT] Pair with REF.")
            await self.le_connect_and_pair(identity_address_type)

            if ref_dut_acl := self.ref.device.find_connection_by_bd_addr(
                    hci.Address(self.dut.address, hci.AddressType.PUBLIC_DEVICE),
                    core.BT_LE_TRANSPORT,
            ):
                self.logger.info("[REF] Disconnect.")
                with contextlib.suppress(hci.HCI_StatusError):
                    await ref_dut_acl.disconnect()
            await dut_cb.wait_for_event(bl4a_api.AclDisconnected)

        self.logger.info("[REF] Start advertising.")
        await self.ref.device.start_advertising(own_address_type=ref_address_type)

        self.logger.info("[DUT] Start scanning for REF.")
        dut_scanner = self.dut.bl4a.start_scanning(scan_filter=bl4a_api.ScanFilter(
            device=identity_address,
            address_type=identity_address_type,
        ),)
        await dut_scanner.wait_for_event(bl4a_api.ScanResult)
        self.logger.info("[DUT] Found REF, start connecting GATT.")
        await self.dut.bl4a.connect_gatt_client(
            address=identity_address,
            address_type=identity_address_type,
            transport=android_constants.Transport.LE,
        )

    async def test_scan_with_identify_address_and_irk(self) -> None:
        """Tests that DUT can scan with identify address and IRK.

    Test steps:
      1. Generate a static address.
      2. Start advertising on REF with RPA.
      3. Start scanning on DUT with static address and REF's IRK.
      4. Check that DUT can receive scan result from REF.
    """
        target_address = hci.Address.generate_static_address().to_string()
        self.logger.info("[REF] Start advertising.")
        async with self.assert_not_timeout(_DEFAULT_TIMEOUT_SECONDS):
            await self.ref.device.start_advertising(
                advertising_type=device.AdvertisingType.UNDIRECTED_CONNECTABLE_SCANNABLE,
                own_address_type=hci.OwnAddressType.RESOLVABLE_OR_RANDOM,
            )
        self.logger.info("[DUT] Start scanning for REF with IRK.")
        with self.dut.bl4a.start_scanning(
                scan_settings=bl4a_api.ScanSettings(
                    scan_mode=android_constants.BleScanMode.LOW_LATENCY,
                    callback_type=android_constants.BleScanCallbackType.ALL_MATCHES,
                    match_mode=android_constants.BleScanMatchMode.STICKY,
                ),
                scan_filter=bl4a_api.ScanFilter(
                    device=target_address,
                    address_type=android_constants.AddressTypeStatus.RANDOM,
                    irk=self.ref.device.irk,
                ),
        ) as scan_cb:
            # [DUT] Wait for advertising report(scan result) from REF.
            event = await scan_cb.wait_for_event(bl4a_api.ScanResult)
            self.assertEqual(event.address, target_address)

    async def test_le_connection_priority(self) -> None:
        """Tests LE connection priority.

    Test steps:
      1. Pair then Disconnect with REF.
      2. Start advertising on REF.
      3. Start scanning on DUT.
      4. Wait for matched scan result.
      5. Connect to REF.
      6. Request connection priority on DUT.
      7. Check that the connection parameters is updated on DUT.
    """
        self.logger.info("[REF] Start advertising")
        await self.ref.device.start_advertising(
            own_address_type=hci.OwnAddressType.RANDOM,
            advertising_type=device.AdvertisingType.UNDIRECTED_CONNECTABLE_SCANNABLE)
        self.logger.info("[DUT] Connect GATT client to REF")
        gatt_client = await self.dut.bl4a.connect_gatt_client(
            address=self.ref.random_address,
            transport=android_constants.Transport.LE,
            address_type=android_constants.AddressTypeStatus.RANDOM,
        )
        self.test_case_context.push(gatt_client)
        self.logger.info("[DUT] GATT client connected")

        ref_connection = list(self.ref.device.connections.values())[0]
        connection_parameters = [
            (android_constants.ConnectionPriority.DCK, 30.0, 30.0),
            (android_constants.ConnectionPriority.HIGH, 11.25, 15.0),
            (android_constants.ConnectionPriority.BALANCED, 30.0, 50.0),
            (android_constants.ConnectionPriority.LOW_POWER, 100.0, 150.0),
        ]
        if (connection_parameters[0][1] < ref_connection.parameters.connection_interval <
                connection_parameters[0][2]):
            # If connection parameters is already in the expected range, reverse the
            # order to make sure the connection parameters is always updated.
            connection_parameters = connection_parameters[::-1]

        condition = asyncio.Condition()

        @ref_connection.on(ref_connection.EVENT_CONNECTION_PARAMETERS_UPDATE)
        async def _() -> None:
            async with condition:
                condition.notify_all()

        for priority, min_interval, max_interval in connection_parameters:
            self.logger.info("[DUT] Request connection priority.")
            await gatt_client.request_connection_priority(priority)
            self.logger.info(
                "[REF] Wait for connection interval update to [%s, %s].",
                min_interval,
                max_interval,
            )
            async with self.assert_not_timeout(_DEFAULT_TIMEOUT_SECONDS), condition:
                await condition.wait_for(
                    lambda: min_interval <= ref_connection.parameters.connection_interval <=
                    max_interval,)
            self.logger.info(
                "[REF] Connection interval is updated to %s",
                ref_connection.parameters.connection_interval,
            )

    async def test_request_subrate(self) -> None:
        """Tests requesting LE subrate.

    Test steps:
      1. Enable subrating on REF.
      2. Start advertising on REF.
      3. Connect GATT client to REF.
      4. Request subrate mode on DUT.
      5. Check that the subrate mode is updated on REF.
    """
        # TODO: Re-enable this when subrate manager is ready.
        if not self.dut.device.is_emulator:
            self.skipTest("Not stable on real device.")

        # TODO: Check if DUT supports LE subrating.
        if not self.ref.device.supports_le_features(hci.LeFeatureMask.CONNECTION_SUBRATING):
            self.skipTest("REF does not support LE subrating.")

        self.logger.info("[REF] Start advertising")
        await self.ref.device.start_advertising(
            own_address_type=hci.OwnAddressType.RANDOM,
            advertising_type=device.AdvertisingType.UNDIRECTED_CONNECTABLE_SCANNABLE,
        )
        self.logger.info("[DUT] Connect GATT client to REF")
        gatt_client = await self.dut.bl4a.connect_gatt_client(
            address=self.ref.random_address,
            transport=android_constants.Transport.LE,
            address_type=android_constants.AddressTypeStatus.RANDOM,
        )
        self.test_case_context.push(gatt_client)
        self.logger.info("[DUT] GATT client connected")

        ref_subrate_changed = asyncio.get_running_loop().create_future()
        ref_connection = list(self.ref.device.connections.values())[0]

        async with self.assert_not_timeout(_DEFAULT_TIMEOUT_SECONDS):
            dut_features = await ref_connection.get_remote_le_features()

        if not (dut_features & hci.LeFeatureMask.CONNECTION_SUBRATING and
                dut_features & hci.LeFeatureMask.CONNECTION_SUBRATING_HOST_SUPPORT):
            self.skipTest("DUT does not support LE subrating.")

        ref_connection.once(
            ref_connection.EVENT_CONNECTION_PARAMETERS_UPDATE,
            lambda: ref_subrate_changed.set_result(None),
        )
        self.logger.info("[DUT] Request subrate mode.")
        await gatt_client.request_subrate_mode(android_constants.LeSubrateMode.HIGH)

        self.logger.info("[REF] Wait for subrate mode change.")
        async with self.assert_not_timeout(_DEFAULT_TIMEOUT_SECONDS):
            await ref_subrate_changed


if __name__ == "__main__":
    test_runner.main()
