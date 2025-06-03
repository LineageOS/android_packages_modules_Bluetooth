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
import secrets

from avatar import BumblePandoraDevice, PandoraDevice, PandoraDevices, asynchronous
from bumble.gatt import GATT_HEARING_ACCESS_SERVICE, GATT_AUDIO_STREAM_CONTROL_SERVICE, GATT_PUBLISHED_AUDIO_CAPABILITIES_SERVICE, GATT_COORDINATED_SET_IDENTIFICATION_SERVICE

from bumble.profiles import hap
from bumble.device import Device
from bumble.core import AdvertisingData
from bumble.profiles.csip import CoordinatedSetIdentificationService, SirkType, generate_rsi
from bumble.profiles.cap import CommonAudioServiceService
from bumble.profiles.hap import DynamicPresets, HearingAccessService, HearingAidFeatures, HearingAidType, IndependentPresets, PresetChangedOperation, PresetChangedOperationAvailable, PresetRecord, PresetSynchronizationSupport, WritablePresetsSupport

from pandora.os_grpc_aio import Os as OsAio
from pandora.gatt_grpc_aio import GATT
from pandora.hap_grpc_aio import HAP  # type: ignore
from pandora.hap_pb2 import PresetRecord as grpcPresetRecord  # type: ignore
from pandora._utils import AioStream
from pandora.security_pb2 import LE_LEVEL3
from pandora.host_pb2 import RANDOM, AdvertiseResponse, Connection, DataTypes, ScanningResponse
from mobly import base_test, signals
from mobly.asserts import assert_equal, assert_is_not_none, assert_not_in  # type: ignore
from typing import List, Tuple

HAP_UUID = GATT_HEARING_ACCESS_SERVICE.to_hex_str('-')
CSIS_UUID = GATT_COORDINATED_SET_IDENTIFICATION_SERVICE.to_hex_str('-')

long_name = "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua."
foo_preset = PresetRecord(1, "foo preset")
bar_preset = PresetRecord(50, "bar preset")
longname_preset = PresetRecord(5, f'[{long_name[:38]}]')
unavailable_preset = PresetRecord(
    7, "unavailable preset",
    PresetRecord.Property(PresetRecord.Property.Writable.CANNOT_BE_WRITTEN,
                          PresetRecord.Property.IsAvailable.IS_UNAVAILABLE))


def to_bumble_preset(grpc_preset: grpcPresetRecord) -> PresetRecord:  # type: ignore
    return PresetRecord(
        grpc_preset.index,
        grpc_preset.name,  # type: ignore
        PresetRecord.Property(
            PresetRecord.Property.Writable(grpc_preset.isWritable),  # type: ignore
            PresetRecord.Property.IsAvailable(grpc_preset.isAvailable)))  # type: ignore


def to_bumble_preset_list(
        grpc_preset_list: List[grpcPresetRecord]) -> List[PresetRecord]:  # type: ignore
    return [to_bumble_preset(grpc_preset) for grpc_preset in grpc_preset_list]  # type: ignore


def get_server_preset_sorted(has: HearingAccessService) -> List[PresetRecord]:
    return [has.preset_records[key] for key in sorted(has.preset_records.keys())]


set_identity_resolving_key = secrets.token_bytes(16)


class HearingAidDevice:
    COMPLETE_LOCAL_NAME: str = "Bumble"
    ref: BumblePandoraDevice
    has: HearingAccessService
    is_monaural: bool
    csis: CoordinatedSetIdentificationService  # Only set in binaural mode
    to_ref: Connection
    to_dut: Connection

    def __init__(self, device: BumblePandoraDevice) -> None:
        self.ref = device

    async def advertise_hap(self) -> AioStream[AdvertiseResponse]:
        if (self.is_monaural):
            return await self.__advertise_monaural()
        else:
            return await self.__advertise_binaural()

    def setup_monaural(self):
        self.is_monaural = True
        device_features = HearingAidFeatures(
            HearingAidType.MONAURAL_HEARING_AID,
            PresetSynchronizationSupport.PRESET_SYNCHRONIZATION_IS_NOT_SUPPORTED,
            IndependentPresets.IDENTICAL_PRESET_RECORD, DynamicPresets.PRESET_RECORDS_MAY_CHANGE,
            WritablePresetsSupport.WRITABLE_PRESET_RECORDS_SUPPORTED)
        self.has = HearingAccessService(
            self.ref.device, device_features,
            [foo_preset, bar_preset, longname_preset, unavailable_preset])
        self.ref.device.add_service(self.has)  # type: ignore

    async def __advertise_monaural(self) -> AioStream[AdvertiseResponse]:
        return self.ref.aio.host.Advertise(
            legacy=True,
            connectable=True,
            own_address_type=RANDOM,
            data=DataTypes(
                complete_local_name=HearingAidDevice.COMPLETE_LOCAL_NAME,
                incomplete_service_class_uuids16=[HAP_UUID],
            ),
        )

    def setup_binaural(self):
        self.is_monaural = False
        device_features = HearingAidFeatures(
            HearingAidType.BINAURAL_HEARING_AID,
            PresetSynchronizationSupport.PRESET_SYNCHRONIZATION_IS_SUPPORTED,
            IndependentPresets.IDENTICAL_PRESET_RECORD, DynamicPresets.PRESET_RECORDS_MAY_CHANGE,
            WritablePresetsSupport.WRITABLE_PRESET_RECORDS_SUPPORTED)

        self.has = HearingAccessService(
            self.ref.device, device_features,
            [foo_preset, bar_preset, longname_preset, unavailable_preset])
        self.ref.device.add_service(self.has)  # type: ignore

        self.csis = CoordinatedSetIdentificationService(
            set_identity_resolving_key=set_identity_resolving_key,
            set_identity_resolving_key_type=SirkType.PLAINTEXT,
            coordinated_set_size=2,
        )
        self.ref.device.add_service(CommonAudioServiceService(self.csis))

    async def __advertise_binaural(self) -> AioStream[AdvertiseResponse]:
        return self.ref.aio.host.Advertise(
            legacy=True,
            connectable=True,
            own_address_type=RANDOM,
            data=DataTypes(complete_local_name=HearingAidDevice.COMPLETE_LOCAL_NAME,
                           incomplete_service_class_uuids16=[HAP_UUID, CSIS_UUID],
                           resolvable_set_identifier=generate_rsi(set_identity_resolving_key)),
        )

    async def assert_all_presets(self, dut_hap: HAP) -> None:
        remote_preset = to_bumble_preset_list(
            (await dut_hap.GetAllPresets(connection=self.to_ref)).preset_record_list)
        assert_equal(remote_preset, get_server_preset_sorted(self.has))

    async def assert_active_preset(self, dut_hap: HAP, expected_preset: PresetRecord) -> None:
        # first validate the active preset reported by dut
        assert_equal(
            expected_preset,
            to_bumble_preset((await dut_hap.GetActivePreset(connection=self.to_ref)).preset_record))
        # then validate the active preset reported by ref
        assert_equal(expected_preset.index, self.has.active_preset_index)


def synchronize_has(left: HearingAidDevice, right: HearingAidDevice):
    left.has.other_server_in_binaural_set = right.has
    right.has.other_server_in_binaural_set = left.has


class HapTest(base_test.BaseTestClass):
    devices: PandoraDevices
    dut: PandoraDevice
    dut_hap: HAP
    ref_left: HearingAidDevice
    ref_right: HearingAidDevice

    def setup_class(self):
        self.devices = PandoraDevices(self)
        dut, left, right, *_ = self.devices  # type: ignore

        if isinstance(dut, BumblePandoraDevice):
            raise signals.TestAbortClass('DUT Bumble does not support HAP')
        self.dut = dut
        if not isinstance(left, BumblePandoraDevice):
            raise signals.TestAbortClass('Test require Bumble as reference device(s)')
        self.ref_left = HearingAidDevice(left)
        if not isinstance(right, BumblePandoraDevice):
            raise signals.TestAbortClass('Test require Bumble as reference device(s)')
        self.ref_right = HearingAidDevice(right)

    def teardown_class(self):
        self.devices.stop_all()

    @asynchronous
    async def setup_test(self) -> None:
        await asyncio.gather(self.dut.reset(), self.ref_left.ref.reset(),
                             self.ref_right.ref.reset())
        self.logcat = OsAio(channel=self.dut.aio.channel)
        await self.logcat.Log(f'{self.current_test_info.name}: setup_test')
        self.dut_hap = HAP(channel=self.dut.aio.channel)
        self.dut_gatt = GATT(channel=self.dut.aio.channel)

        await self.logcat.Log(f'{self.current_test_info.name}: completed setup_test')

    @asynchronous
    async def teardown_test(self) -> None:
        await self.logcat.Log(f'{self.current_test_info.name}: completed teardown_test')

    async def dut_scan_for_hap(self) -> ScanningResponse:
        """
        DUT starts to scan for the Ref device.
        :return: ScanningResponse for HAP
        """
        dut_scan = self.dut.aio.host.Scan(RANDOM)  # type: ignore
        scan_response = await anext(
            (x async for x in dut_scan if HAP_UUID in x.data.incomplete_service_class_uuids16))
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
        assert_equal('connection', dut_ref_res.result_variant())  # type: ignore
        dut_ref, ref_dut = dut_ref_res.connection, ref_dut_res.connection
        assert_is_not_none(dut_ref)
        advertisement.cancel()
        return dut_ref, ref_dut

    async def setupHapConnection(self, hearingAidDevice: HearingAidDevice):
        advertisement = await hearingAidDevice.advertise_hap()
        scan_response = await self.dut_scan_for_hap()
        dut_connection_to_ref, ref_connection_to_dut = await self.dut_connect_to_ref(
            advertisement, scan_response)

        await self.dut_gatt.ExchangeMTU(mtu=512, connection=dut_connection_to_ref)

        (secure, wait_security) = await asyncio.gather(
            self.dut.aio.security.Secure(connection=dut_connection_to_ref, le=LE_LEVEL3),
            hearingAidDevice.ref.aio.security.WaitSecurity(connection=ref_connection_to_dut,
                                                           le=LE_LEVEL3),
        )

        assert_equal('success', secure.result_variant())
        assert_equal('success', wait_security.result_variant())

        await asyncio.sleep(1)  # TODO wait on UUID discovered
        await self.dut_hap.WaitPeripheral(connection=dut_connection_to_ref)  # type: ignore
        advertisement.cancel()

        hearingAidDevice.to_ref = dut_connection_to_ref
        hearingAidDevice.to_dut = ref_connection_to_dut

    async def verify_no_crash(self) -> None:
        ''' Periodically check that there is no android crash '''
        for __i__ in range(10):
            await asyncio.sleep(.3)
            await self.ref_left.assert_all_presets(self.dut_hap)

    async def setup_monaural(self) -> None:
        self.ref_left.setup_monaural()
        await self.setupHapConnection(self.ref_left)
        await self.ref_left.assert_all_presets(self.dut_hap)

    async def setup_binaural(self) -> None:
        self.ref_left.setup_binaural()
        self.ref_right.setup_binaural()
        synchronize_has(self.ref_left, self.ref_right)

        await self.setupHapConnection(self.ref_left)
        await self.setupHapConnection(self.ref_right)

        await asyncio.gather(self.ref_left.assert_all_presets(self.dut_hap),
                             self.ref_right.assert_all_presets(self.dut_hap))

    @asynchronous
    async def test_get_features(self) -> None:
        await self.setup_monaural()

        features = hap.HearingAidFeatures_from_bytes(
            (await self.dut_hap.GetFeatures(connection=self.ref_left.to_ref)).features)
        assert_equal(self.ref_left.has.server_features, features)

    @asynchronous
    async def test_preset__remove_preset__verify_dut_is_updated(self) -> None:
        await self.setup_monaural()

        await self.logcat.Log("Remove preset in server")
        await self.ref_left.has.delete_preset(unavailable_preset.index)
        await asyncio.sleep(1)  # wait event

        await self.ref_left.assert_all_presets(self.dut_hap)

    @asynchronous
    async def test__add_preset__verify_dut_is_updated(self) -> None:
        await self.setup_monaural()

        added_preset = PresetRecord(bar_preset.index + 3, "added_preset")
        self.ref_left.has.preset_records[added_preset.index] = added_preset

        await self.logcat.Log("Preset added in server. Notify now")
        await self.ref_left.has.generic_update(
            PresetChangedOperation(PresetChangedOperation.ChangeId.GENERIC_UPDATE,
                                   PresetChangedOperation.Generic(bar_preset.index, added_preset)))
        await asyncio.sleep(1)  # wait event

        await self.ref_left.assert_all_presets(self.dut_hap)

    @asynchronous
    async def test__set_non_existing_preset_as_active__verify_no_crash_and_no_update(self) -> None:
        await self.setup_monaural()

        non_existing_preset_index = 79
        assert_not_in(non_existing_preset_index, self.ref_left.has.preset_records.keys())
        await self.ref_left.assert_active_preset(self.dut_hap, foo_preset)

        await self.logcat.Log("Notify active preset to non existing index")
        # bypass the set_active_preset checks by sending an invalid index on purpose
        self.ref_left.has.active_preset_index = non_existing_preset_index
        await self.ref_left.has.notify_active_preset()

        await self.verify_no_crash()
        await self.ref_left.assert_active_preset(self.dut_hap, foo_preset)

    @asynchronous
    async def test__set_non_existing_preset_as_available__verify_no_crash_and_no_update(
            self) -> None:
        await self.setup_monaural()
        non_existing_preset_index = 79
        assert_not_in(non_existing_preset_index, self.ref_left.has.preset_records.keys())

        await self.logcat.Log("Notify available preset to non existing index")
        await self.ref_left.has.generic_update(
            PresetChangedOperationAvailable(non_existing_preset_index))

        await self.verify_no_crash()

    @asynchronous
    async def test_set_active_preset(self) -> None:
        await self.setup_monaural()

        await self.dut_hap.SetActivePreset(connection=self.ref_left.to_ref, index=bar_preset.index)
        await asyncio.sleep(1)  # TODO wait event
        await self.ref_left.assert_active_preset(self.dut_hap, bar_preset)

        await self.dut_hap.SetActivePreset(connection=self.ref_left.to_ref, index=foo_preset.index)
        await asyncio.sleep(1)  # TODO wait event
        await self.ref_left.assert_active_preset(self.dut_hap, foo_preset)

    @asynchronous
    async def test__set_active_binaural__when_disconnecting__do_not_crash(self) -> None:
        await self.setup_binaural()

        # preliminary check to be sure we are setting a new & different preset
        await self.ref_left.assert_active_preset(self.dut_hap, foo_preset)

        await self.dut_hap.SetActivePresetForGroup(connection=self.ref_left.to_ref,
                                                   index=bar_preset.index)
        await self.dut.aio.host.Disconnect(connection=self.ref_left.to_ref)
        await asyncio.gather(self.ref_left.ref.reset())

    @asynchronous
    async def test__set_active_monaural__when_disconnecting__do_not_crash(self) -> None:
        await self.setup_monaural()

        # preliminary check to be sure we are setting a new & different preset
        await self.ref_left.assert_active_preset(self.dut_hap, foo_preset)

        await asyncio.gather(
            self.dut_hap.SetActivePreset(connection=self.ref_left.to_ref, index=bar_preset.index),
            self.ref_left.ref.aio.host.Disconnect(connection=self.ref_left.to_ref))
        await asyncio.sleep(3)  # TODO wait event

    @asynchronous
    async def test__select_left_preset__when_in_synchronized_set__right_is_updated(self) -> None:
        await self.setup_binaural()

        # preliminary check to be sure we are setting a new & different preset
        await self.ref_left.assert_active_preset(self.dut_hap, foo_preset)

        await self.dut_hap.SetActivePresetForGroup(connection=self.ref_left.to_ref,
                                                   index=bar_preset.index)
        await asyncio.sleep(3)  # TODO wait event

        await self.ref_left.assert_active_preset(self.dut_hap, bar_preset)
        await self.ref_right.assert_active_preset(self.dut_hap, bar_preset)
