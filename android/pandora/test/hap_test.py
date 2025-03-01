# Copyright (C) 2024 The Android Open Source Project
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at

# http://www.apache.org/licenses/LICENSE-2.0

# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import asyncio

from avatar import BumblePandoraDevice, PandoraDevice, PandoraDevices, asynchronous
from bumble.gatt import GATT_HEARING_ACCESS_SERVICE, GATT_AUDIO_STREAM_CONTROL_SERVICE, GATT_PUBLISHED_AUDIO_CAPABILITIES_SERVICE
from bumble.profiles import hap
from bumble.profiles.hap import DynamicPresets, HearingAccessService, HearingAidFeatures, HearingAidType, IndependentPresets, PresetChangedOperation, PresetChangedOperationAvailable, PresetRecord, PresetSynchronizationSupport, WritablePresetsSupport

from pandora_experimental.os_grpc_aio import Os as OsAio
from pandora_experimental.gatt_grpc_aio import GATT
from pandora_experimental.hap_grpc_aio import HAP
from pandora_experimental.hap_pb2 import PresetRecord as grpcPresetRecord  # type: ignore
from pandora._utils import AioStream
from pandora.security_pb2 import LE_LEVEL3
from pandora.host_pb2 import RANDOM, AdvertiseResponse, Connection, DataTypes, ScanningResponse
from mobly import base_test, signals
from typing import List, Tuple

COMPLETE_LOCAL_NAME: str = "Bumble"
HAP_UUID = GATT_HEARING_ACCESS_SERVICE.to_hex_str('-')
ASCS_UUID = GATT_AUDIO_STREAM_CONTROL_SERVICE.to_hex_str('-')
PACS_UUID = GATT_PUBLISHED_AUDIO_CAPABILITIES_SERVICE.to_hex_str('-')

long_name = "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua."
foo_preset = PresetRecord(1, "foo preset")
bar_preset = PresetRecord(50, "bar preset")
longname_preset = PresetRecord(5, f'[{long_name[:38]}]')
unavailable_preset = PresetRecord(
    7, "unavailable preset",
    PresetRecord.Property(PresetRecord.Property.Writable.CANNOT_BE_WRITTEN,
                          PresetRecord.Property.IsAvailable.IS_UNAVAILABLE))


def toBumblePreset(grpc_preset: grpcPresetRecord) -> PresetRecord:
    return PresetRecord(
        grpc_preset.index,
        grpc_preset.name,  # type: ignore
        PresetRecord.Property(
            PresetRecord.Property.Writable(grpc_preset.isWritable),  # type: ignore
            PresetRecord.Property.IsAvailable(grpc_preset.isAvailable)))  # type: ignore


def toBumblePresetList(grpc_preset_list: List[grpcPresetRecord]) -> List[PresetRecord]:  # type: ignore
    return [toBumblePreset(grpc_preset) for grpc_preset in grpc_preset_list]  # type: ignore


def get_server_preset_sorted(has: HearingAccessService) -> List[PresetRecord]:
    return [has.preset_records[key] for key in sorted(has.preset_records.keys())]


class HapTest(base_test.BaseTestClass):
    devices: PandoraDevices
    dut: PandoraDevice
    ref_left: BumblePandoraDevice
    hap_grpc: HAP
    has: HearingAccessService

    def setup_class(self):
        self.devices = PandoraDevices(self)
        dut, ref_left, *_ = self.devices

        if isinstance(dut, BumblePandoraDevice):
            raise signals.TestAbortClass('DUT Bumble does not support HAP')
        self.dut = dut
        if not isinstance(ref_left, BumblePandoraDevice):
            raise signals.TestAbortClass('Test require Bumble as reference device(s)')
        self.ref_left = ref_left

    def teardown_class(self):
        self.devices.stop_all()

    @asynchronous
    async def setup_test(self) -> None:
        await asyncio.gather(self.dut.reset(), self.ref_left.reset())
        self.logcat = OsAio(channel=self.dut.aio.channel)
        await self.logcat.Log("setup test")
        self.hap_grpc = HAP(channel=self.dut.aio.channel)
        device_features = HearingAidFeatures(HearingAidType.MONAURAL_HEARING_AID,
                                             PresetSynchronizationSupport.PRESET_SYNCHRONIZATION_IS_NOT_SUPPORTED,
                                             IndependentPresets.IDENTICAL_PRESET_RECORD,
                                             DynamicPresets.PRESET_RECORDS_DOES_NOT_CHANGE,
                                             WritablePresetsSupport.WRITABLE_PRESET_RECORDS_SUPPORTED)
        self.has = HearingAccessService(self.ref_left.device, device_features,
                                        [foo_preset, bar_preset, longname_preset, unavailable_preset])
        self.dut_gatt = GATT(channel=self.dut.aio.channel)

        self.ref_left.device.add_service(self.has)  # type: ignore

    async def advertise_hap(self, device: PandoraDevice) -> AioStream[AdvertiseResponse]:
        return device.aio.host.Advertise(
            legacy=True,
            connectable=True,
            own_address_type=RANDOM,
            data=DataTypes(
                complete_local_name=COMPLETE_LOCAL_NAME,
                incomplete_service_class_uuids16=[HAP_UUID],
            ),
        )

    async def dut_scan_for_hap(self) -> ScanningResponse:
        """
        DUT starts to scan for the Ref device.
        :return: ScanningResponse for ASHA
        """
        dut_scan = self.dut.aio.host.Scan(RANDOM)  # type: ignore
        scan_response = await anext((x async for x in dut_scan if HAP_UUID in x.data.incomplete_service_class_uuids16))
        dut_scan.cancel()
        return scan_response

    async def dut_connect_to_ref(self, advertisement: AioStream[AdvertiseResponse],
                                 ref: ScanningResponse) -> Tuple[Connection, Connection]:
        """
        Helper method for Dut connects to Ref
        :return: a Tuple (DUT to REF connection, REF to DUT connection)
        """
        (dut_ref_res, ref_dut_res) = await asyncio.gather(
            self.dut.aio.host.ConnectLE(own_address_type=RANDOM, **ref.address_asdict()),
            anext(aiter(advertisement)),
        )
        assert dut_ref_res.result_variant() == 'connection'  # type: ignore
        dut_ref, ref_dut = dut_ref_res.connection, ref_dut_res.connection
        assert dut_ref
        advertisement.cancel()
        return dut_ref, ref_dut

    async def setupHapConnection(self):
        advertisement = await self.advertise_hap(self.ref_left)
        scan_response = await self.dut_scan_for_hap()
        dut_connection_to_ref, ref_connection_to_dut = await self.dut_connect_to_ref(advertisement, scan_response)

        await self.dut_gatt.ExchangeMTU(mtu=512, connection=dut_connection_to_ref)

        (secure, wait_security) = await asyncio.gather(
            self.dut.aio.security.Secure(connection=dut_connection_to_ref, le=LE_LEVEL3),
            self.ref_left.aio.security.WaitSecurity(connection=ref_connection_to_dut, le=LE_LEVEL3),
        )

        assert secure.result_variant() == 'success'  # type: ignore
        assert wait_security.result_variant() == 'success'  # type: ignore

        await self.hap_grpc.WaitPeripheral(connection=dut_connection_to_ref)  # type: ignore
        advertisement.cancel()

        return dut_connection_to_ref

    async def assertIdenticalPreset(self, dut_connection_to_ref: Connection) -> None:
        remote_preset = toBumblePresetList(
            (await self.hap_grpc.GetAllPresetRecords(connection=dut_connection_to_ref)).preset_record_list)
        assert remote_preset == get_server_preset_sorted(self.has)

    async def verify_no_crash(self, dut_connection_to_ref: Connection) -> None:
        ''' Periodically check that there is no android crash '''
        for __i__ in range(10):
            await asyncio.sleep(.3)
            await self.assertIdenticalPreset(dut_connection_to_ref)

    @asynchronous
    async def test_get_features(self) -> None:
        await self.logcat.Log("test_get_features")
        dut_connection_to_ref = await self.setupHapConnection()

        features = hap.HearingAidFeatures_from_bytes(
            (await self.hap_grpc.GetFeatures(connection=dut_connection_to_ref)).features)
        assert features == self.has.server_features  # type: ignore

    @asynchronous
    async def test_get_preset(self) -> None:
        await self.logcat.Log("test_get_preset")
        dut_connection_to_ref = await self.setupHapConnection()

        await self.assertIdenticalPreset(dut_connection_to_ref)

    @asynchronous
    async def test_preset__remove_preset__verify_dut_is_updated(self) -> None:
        await self.logcat.Log("test_preset__remove_preset__verify_dut_is_updated")
        dut_connection_to_ref = await self.setupHapConnection()

        await self.assertIdenticalPreset(dut_connection_to_ref)

        await self.logcat.Log("Remove preset in server")
        await self.has.delete_preset(unavailable_preset.index)
        await asyncio.sleep(1)  # wait event

        await self.assertIdenticalPreset(dut_connection_to_ref)

    @asynchronous
    async def test__add_preset__verify_dut_is_updated(self) -> None:
        await self.logcat.Log("test__add_preset__verify_dut_is_updated")
        dut_connection_to_ref = await self.setupHapConnection()

        await self.assertIdenticalPreset(dut_connection_to_ref)

        added_preset = PresetRecord(bar_preset.index + 3, "added_preset")
        self.has.preset_records[added_preset.index] = added_preset

        await self.logcat.Log("Preset added in server. Notify now")
        await self.has.generic_update(
            PresetChangedOperation(PresetChangedOperation.ChangeId.GENERIC_UPDATE,
                                   PresetChangedOperation.Generic(bar_preset.index, added_preset)))
        await asyncio.sleep(1)  # wait event

        await self.assertIdenticalPreset(dut_connection_to_ref)

    @asynchronous
    async def test__set_non_existing_preset_as_active__verify_no_crash_and_no_update(self) -> None:
        await self.logcat.Log("test__set_non_existing_preset_as_active__verify_no_crash_and_no_update")
        non_existing_preset_index = 79
        assert non_existing_preset_index not in self.has.preset_records.keys()  # type: ignore
        dut_connection_to_ref = await self.setupHapConnection()
        assert foo_preset == toBumblePreset(  # type: ignore
            (await self.hap_grpc.GetActivePresetRecord(connection=dut_connection_to_ref)).preset_record)

        await self.logcat.Log("Notify active update to non existing index")
        # bypass the set_active_preset checks by sending an invalid index on purpose
        self.has.active_preset_index = non_existing_preset_index
        await self.has.notify_active_preset()

        await self.verify_no_crash(dut_connection_to_ref)
        assert foo_preset == toBumblePreset(
            (await self.hap_grpc.GetActivePresetRecord(connection=dut_connection_to_ref)).preset_record)

    @asynchronous
    async def test__set_non_existing_preset_as_available__verify_no_crash_and_no_update(self) -> None:
        await self.logcat.Log("test__set_non_existing_preset_as_available__verify_no_crash_and_no_update")
        non_existing_preset_index = 79
        assert non_existing_preset_index not in self.has.preset_records.keys()
        dut_connection_to_ref = await self.setupHapConnection()

        await self.logcat.Log("Notify available preset to non existing index")
        await self.has.generic_update(PresetChangedOperationAvailable(non_existing_preset_index))

        await self.verify_no_crash(dut_connection_to_ref)
