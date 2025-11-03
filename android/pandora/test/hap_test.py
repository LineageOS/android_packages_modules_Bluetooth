# Copyright (C) 2024 The Android Open Source Project
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""HAP (Hearing Access Profile) tests."""

import asyncio
import secrets
import copy
from typing import List, Optional, Tuple

from avatar import (BumblePandoraDevice, PandoraDevice, PandoraDevices, asynchronous, enableFlag)
from bumble.gatt import (GATT_COORDINATED_SET_IDENTIFICATION_SERVICE, GATT_HEARING_ACCESS_SERVICE)
from bumble.profiles.cap import CommonAudioServiceService
from bumble.profiles.csip import (CoordinatedSetIdentificationService, SirkType, generate_rsi)
from bumble.profiles.hap import (DynamicPresets, HearingAccessService, HearingAidFeatures,
                                 HearingAidFeatures_from_bytes, HearingAidType, IndependentPresets,
                                 PresetChangedOperation, PresetChangedOperationAvailable,
                                 PresetRecord, PresetSynchronizationSupport, WritablePresetsSupport)
from mobly import base_test, signals
from mobly.asserts import assert_equal, assert_not_in, assert_is_not_none
from pandora._utils import AioStream
from pandora.gatt_grpc_aio import GATT
from pandora.hap_grpc_aio import HAP
from pandora.hap_pb2 import PresetRecord as GrpcPresetRecord
from pandora.host_pb2 import (RANDOM, AdvertiseResponse, Connection, DataTypes, ScanningResponse)
from pandora.os_grpc_aio import Os as OsAio
from pandora.security_pb2 import LE_LEVEL3

# Service UUIDs
HAP_UUID = GATT_HEARING_ACCESS_SERVICE.to_hex_str('-')
CSIS_UUID = GATT_COORDINATED_SET_IDENTIFICATION_SERVICE.to_hex_str('-')

# Test constants
LONG_NAME = ("Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do "
             "eiusmod tempor incididunt ut labore et dolore magna aliqua.")
FOO_PRESET = PresetRecord(1, "foo preset")
BAR_PRESET = PresetRecord(50, "bar preset")
LONGNAME_PRESET = PresetRecord(5, f'[{LONG_NAME[:38]}]')
UNAVAILABLE_PRESET = PresetRecord(
    7, "unavailable preset",
    PresetRecord.Property(PresetRecord.Property.Writable.CANNOT_BE_WRITTEN,
                          PresetRecord.Property.IsAvailable.IS_UNAVAILABLE))

SET_IDENTITY_RESOLVING_KEY = secrets.token_bytes(16)

# Timeouts and delays
DEVICE_DEFAULT_DELAY = 1.0  # seconds


def to_bumble_preset(grpc_preset: GrpcPresetRecord) -> PresetRecord:
    """Converts a gRPC PresetRecord to a Bumble PresetRecord."""
    return PresetRecord(
        grpc_preset.index, grpc_preset.name,
        PresetRecord.Property(PresetRecord.Property.Writable(grpc_preset.isWritable),
                              PresetRecord.Property.IsAvailable(grpc_preset.isAvailable)))


def to_bumble_preset_list(grpc_preset_list: List[GrpcPresetRecord]) -> List[PresetRecord]:
    """Converts a list of gRPC PresetRecords to a list of Bumble PresetRecords."""
    return [to_bumble_preset(grpc_preset) for grpc_preset in grpc_preset_list]


def get_server_preset_sorted(has: HearingAccessService) -> List[PresetRecord]:
    """Returns a sorted list of presets from the HearingAccessService."""
    return [has.preset_records[key] for key in sorted(has.preset_records.keys())]


class HearingAidDevice:
    """A wrapper around a BumblePandoraDevice to provide Hearing Aid capabilities."""

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
        """Advertises the Hearing Access Profile."""
        if self.is_monaural:
            return await self.__advertise_monaural()
        return await self.__advertise_binaural()

    def setup_monaural(self):
        """Sets up the device as a monaural hearing aid."""
        self.is_monaural = True
        device_features = HearingAidFeatures(
            hearing_aid_type=HearingAidType.MONAURAL_HEARING_AID,
            preset_synchronization_support=PresetSynchronizationSupport.
            PRESET_SYNCHRONIZATION_IS_NOT_SUPPORTED,
            independent_presets=IndependentPresets.IDENTICAL_PRESET_RECORD,
            dynamic_presets=DynamicPresets.PRESET_RECORDS_MAY_CHANGE,
            writable_presets_support=WritablePresetsSupport.WRITABLE_PRESET_RECORDS_SUPPORTED)
        self.has = HearingAccessService(
            self.ref.device, device_features,
            copy.deepcopy([FOO_PRESET, BAR_PRESET, LONGNAME_PRESET, UNAVAILABLE_PRESET]))
        self.ref.device.add_service(self.has)

    async def __advertise_monaural(self) -> AioStream[AdvertiseResponse]:
        """Advertises as a monaural hearing aid."""
        data = DataTypes(
            complete_local_name=HearingAidDevice.COMPLETE_LOCAL_NAME,
            incomplete_service_class_uuids16=[HAP_UUID],
        )
        return self.ref.aio.host.Advertise(
            legacy=True,
            connectable=True,
            own_address_type=RANDOM,
            data=data,
        )

    def setup_binaural(self):
        """Sets up the device as a binaural hearing aid."""
        self.is_monaural = False
        device_features = HearingAidFeatures(
            hearing_aid_type=HearingAidType.BINAURAL_HEARING_AID,
            preset_synchronization_support=PresetSynchronizationSupport.
            PRESET_SYNCHRONIZATION_IS_SUPPORTED,
            independent_presets=IndependentPresets.IDENTICAL_PRESET_RECORD,
            dynamic_presets=DynamicPresets.PRESET_RECORDS_MAY_CHANGE,
            writable_presets_support=WritablePresetsSupport.WRITABLE_PRESET_RECORDS_SUPPORTED)

        self.has = HearingAccessService(
            self.ref.device, device_features,
            copy.deepcopy([FOO_PRESET, BAR_PRESET, LONGNAME_PRESET, UNAVAILABLE_PRESET]))
        self.ref.device.add_service(self.has)

        self.csis = CoordinatedSetIdentificationService(
            set_identity_resolving_key=SET_IDENTITY_RESOLVING_KEY,
            set_identity_resolving_key_type=SirkType.PLAINTEXT,
            coordinated_set_size=2,
        )
        self.ref.device.add_service(CommonAudioServiceService(self.csis))

    async def __advertise_binaural(self) -> AioStream[AdvertiseResponse]:
        """Advertises as a binaural hearing aid."""
        data = DataTypes(complete_local_name=HearingAidDevice.COMPLETE_LOCAL_NAME,
                         incomplete_service_class_uuids16=[HAP_UUID, CSIS_UUID],
                         resolvable_set_identifier=generate_rsi(SET_IDENTITY_RESOLVING_KEY))
        return self.ref.aio.host.Advertise(
            legacy=True,
            connectable=True,
            own_address_type=RANDOM,
            data=data,
        )

    async def assert_all_presets(self, dut_hap: HAP) -> None:
        """Asserts that all presets on the DUT match the local presets."""
        remote_preset = to_bumble_preset_list(
            (await dut_hap.GetAllPresets(connection=self.to_ref)).preset_record_list)
        assert_equal(remote_preset, get_server_preset_sorted(self.has))

    async def generic_update(self, prev: int, preset: PresetRecord) -> None:
        await self.has.generic_update(
            PresetChangedOperation(
                PresetChangedOperation.ChangeId.GENERIC_UPDATE,
                PresetChangedOperation.Generic(prev, preset),
            ))

    async def assert_active_preset(self,
                                   dut_hap: HAP,
                                   expected_preset: PresetRecord,
                                   has_preset: Optional[int] = None) -> None:
        """Asserts that the active preset is correctly set."""
        # first validate the active preset reported by dut
        assert_equal(
            expected_preset,
            to_bumble_preset((await dut_hap.GetActivePreset(connection=self.to_ref)).preset_record))
        has_expected_preset = expected_preset.index
        if has_preset is not None:
            # Some test are voluntarily setting a different preset in has
            has_expected_preset = has_preset
        assert_equal(has_expected_preset, self.has.active_preset_index)

    async def set_active_preset_and_verify(self, dut_hap: HAP, preset: PresetRecord):
        await asyncio.gather(
            dut_hap.SetActivePreset(connection=self.to_ref, index=preset.index),
            dut_hap.WaitActivePresetChanged(connection=self.to_ref, index=preset.index))
        await self.assert_active_preset(dut_hap, preset)

    async def set_active_preset_for_group_and_verify(self, dut_hap: HAP, other,
                                                     preset: PresetRecord):
        await asyncio.gather(
            dut_hap.SetActivePresetForGroup(connection=self.to_ref, index=preset.index),
            dut_hap.WaitActivePresetChanged(connection=self.to_ref, index=preset.index),
            dut_hap.WaitActivePresetChanged(connection=other.to_ref, index=preset.index))
        await self.assert_active_preset(dut_hap, preset)
        await other.assert_active_preset(dut_hap, preset)


def synchronize_has(left: HearingAidDevice, right: HearingAidDevice):
    """Synchronizes the HearingAccessService between two hearing aid devices."""
    left.has.other_server_in_binaural_set = right.has
    right.has.other_server_in_binaural_set = left.has


class HapTest(base_test.BaseTestClass):
    """Test class for the Hearing Access Profile."""
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
        Scans for a device advertising the Hearing Access Profile.

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
        Connects the DUT to the reference device.
        :return: a Tuple (DUT to REF connection, REF to DUT connection)
        """
        (dut_ref_res, ref_dut_res) = await asyncio.gather(
            self.dut.aio.host.ConnectLE(own_address_type=RANDOM, **ref.address_asdict()),
            anext(aiter(advertisement)),
        )
        assert_equal('connection', dut_ref_res.result_variant())
        dut_ref, ref_dut = dut_ref_res.connection, ref_dut_res.connection
        assert dut_ref
        advertisement.cancel()
        return dut_ref, ref_dut

    async def setupHapConnection(self, hearingAidDevice: HearingAidDevice):
        """Sets up a HAP connection between the DUT and a hearing aid device."""
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

        await self.dut_hap.WaitPeripheral(connection=dut_connection_to_ref)
        advertisement.cancel()

        hearingAidDevice.to_ref = dut_connection_to_ref
        hearingAidDevice.to_dut = ref_connection_to_dut

    async def verify_no_crash(self) -> None:
        """Periodically check that there is no android crash."""
        for __i__ in range(10):
            await asyncio.sleep(.3)
            await self.ref_left.assert_all_presets(self.dut_hap)

    async def setup_monaural(self) -> None:
        """Sets up a monaural hearing aid device and connects to the DUT."""
        self.ref_left.setup_monaural()
        await self.setupHapConnection(self.ref_left)
        await self.ref_left.assert_all_presets(self.dut_hap)

    async def setup_binaural(self) -> None:
        """Sets up a binaural hearing aid set and connects to the DUT."""
        self.ref_left.setup_binaural()
        self.ref_right.setup_binaural()
        synchronize_has(self.ref_left, self.ref_right)

        await self.setupHapConnection(self.ref_left)
        await self.setupHapConnection(self.ref_right)

        await asyncio.gather(self.ref_left.assert_all_presets(self.dut_hap),
                             self.ref_right.assert_all_presets(self.dut_hap))

    @asynchronous
    async def test__monaural__get_features(self) -> None:
        await self.setup_monaural()

        features_bytes = (await self.dut_hap.GetFeatures(connection=self.ref_left.to_ref)).features
        features = HearingAidFeatures_from_bytes(features_bytes)
        assert_equal(self.ref_left.has.server_features, features)

    @asynchronous
    async def test__monaural__remove_preset__is_updated(self) -> None:
        await self.setup_monaural()

        await self.logcat.Log("Remove preset in server")
        await self.ref_left.has.delete_preset(UNAVAILABLE_PRESET.index)
        await self.dut_hap.WaitPresetChanged()

        await self.ref_left.assert_all_presets(self.dut_hap)

    @asynchronous
    async def test__monaural__add_new_preset__is_updated(self) -> None:
        await self.setup_monaural()

        added_preset = PresetRecord(BAR_PRESET.index + 3, "added_preset")
        self.ref_left.has.preset_records[added_preset.index] = added_preset

        await self.logcat.Log("Preset added in server. Notify now")
        await self.ref_left.generic_update(BAR_PRESET.index, added_preset)
        await self.dut_hap.WaitPresetChanged()

        await self.ref_left.assert_all_presets(self.dut_hap)

    @asynchronous
    async def test__monaural__modify_existing_preset__is_updated(self) -> None:
        await self.setup_monaural()

        # Change preset name
        has_preset = self.ref_left.has.preset_records[FOO_PRESET.index]
        has_preset.name = "Very nice name"
        await self.logcat.Log("Preset modified in server. Notify now")
        await self.ref_left.generic_update(0, has_preset)
        preset_changed = await self.dut_hap.WaitPresetChanged()

        updated_list = to_bumble_preset_list(preset_changed.preset_record_list)
        updated_preset = next(p for p in updated_list if p.index == has_preset.index)
        assert_equal(updated_preset.name, has_preset.name)
        assert_equal(updated_list, get_server_preset_sorted(self.ref_left.has))

    @asynchronous
    async def test__monaural__modify_existing_preset_at_end_of_chain__is_updated(self) -> None:
        await self.setup_monaural()

        # Pretent client are disconnected, to force queuing of Operation
        clients = self.ref_left.has.currently_connected_clients
        self.ref_left.has.currently_connected_clients = set()

        await self.ref_left.generic_update(0, FOO_PRESET)  # Not propagated yet

        # Reset clients connection status, next operation will trigger all events
        self.ref_left.has.currently_connected_clients = clients

        # Change preset name
        has_preset = self.ref_left.has.preset_records[LONGNAME_PRESET.index]
        has_preset.name = "Very nice name"

        await self.logcat.Log("Preset modified in server. Notify 2 preset now")
        await self.ref_left.generic_update(FOO_PRESET.index, has_preset)
        preset_changed = await self.dut_hap.WaitPresetChanged()

        updated_list = to_bumble_preset_list(preset_changed.preset_record_list)
        updated_preset = next(p for p in updated_list if p.index == has_preset.index)
        assert_equal(updated_preset.name, has_preset.name)
        assert_equal(updated_list, get_server_preset_sorted(self.ref_left.has))

    @asynchronous
    async def test__monaural__modify_multiples_existing_preset_at_middle_of_chain__is_updated(
            self) -> None:
        await self.setup_monaural()

        # Pretent client are disconnected, to force queuing of Operation
        clients = self.ref_left.has.currently_connected_clients
        self.ref_left.has.currently_connected_clients = set()

        # stack a first change even if this doesn't change anything:
        await self.ref_left.generic_update(0, FOO_PRESET)  # Not propagated yet

        # Change preset name
        has_preset = self.ref_left.has.preset_records[LONGNAME_PRESET.index]
        has_preset.name = "Very nice name"

        await self.ref_left.generic_update(FOO_PRESET.index, has_preset)  # Not propagated yet

        # Reset clients connection status, next operation will trigger all events
        self.ref_left.has.currently_connected_clients = clients

        await self.logcat.Log("Preset modified in server. Notify 3 preset now")
        await self.ref_left.generic_update(UNAVAILABLE_PRESET.index, BAR_PRESET)

        preset_changed = await self.dut_hap.WaitPresetChanged()

        updated_list = to_bumble_preset_list(preset_changed.preset_record_list)
        updated_preset = next(p for p in updated_list if p.index == has_preset.index)
        assert_equal(updated_preset.name, has_preset.name)
        assert_equal(updated_list, get_server_preset_sorted(self.ref_left.has))

    @asynchronous
    async def test__binaural__modify_existing_preset__is_updated(self) -> None:
        await self.setup_binaural()

        # Change preset name
        has_preset = self.ref_left.has.preset_records[FOO_PRESET.index]
        has_preset.name = "Very nice name"
        await self.logcat.Log("Preset modified in server. Notify now")
        await self.ref_left.generic_update(0, has_preset)
        preset_changed = await self.dut_hap.WaitPresetChanged()

        updated_list = to_bumble_preset_list(preset_changed.preset_record_list)
        updated_preset = next(p for p in updated_list if p.index == has_preset.index)
        assert_equal(updated_preset.name, has_preset.name)
        assert_equal(updated_list, get_server_preset_sorted(self.ref_left.has))

        has_preset = self.ref_right.has.preset_records[FOO_PRESET.index]
        has_preset.name = "Very nice name"
        await self.ref_right.generic_update(0, has_preset)
        preset_changed = await self.dut_hap.WaitPresetChanged()

        updated_list = to_bumble_preset_list(preset_changed.preset_record_list)
        updated_preset = next(p for p in updated_list if p.index == has_preset.index)
        assert_equal(updated_preset.name, has_preset.name)
        assert_equal(updated_list, get_server_preset_sorted(self.ref_right.has))

    @asynchronous
    async def test__set_non_existing_preset_as_active__verify_no_crash_and_no_update(self) -> None:
        await self.setup_monaural()

        non_existing_preset_index = 79
        assert_not_in(non_existing_preset_index, self.ref_left.has.preset_records.keys())
        await self.ref_left.assert_active_preset(self.dut_hap, FOO_PRESET)

        await self.logcat.Log("Notify active preset to non existing index")
        # bypass the set_active_preset checks by sending an invalid index on purpose
        self.ref_left.has.active_preset_index = non_existing_preset_index
        await self.ref_left.has.notify_active_preset()

        await self.verify_no_crash()
        await self.ref_left.assert_active_preset(self.dut_hap, FOO_PRESET,
                                                 non_existing_preset_index)

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

        await self.ref_left.set_active_preset_and_verify(self.dut_hap, BAR_PRESET)
        await self.ref_left.set_active_preset_and_verify(self.dut_hap, FOO_PRESET)

    @asynchronous
    async def test__set_active_binaural__when_disconnecting__do_not_crash(self) -> None:
        await self.setup_binaural()

        # preliminary check to be sure we are setting a new & different preset
        await self.ref_left.assert_active_preset(self.dut_hap, FOO_PRESET)

        await self.dut_hap.SetActivePresetForGroup(connection=self.ref_left.to_ref,
                                                   index=BAR_PRESET.index)
        await self.dut.aio.host.Disconnect(connection=self.ref_left.to_ref)

    @asynchronous
    async def test__set_active_monaural__when_disconnecting__do_not_crash(self) -> None:
        await self.setup_monaural()

        # preliminary check to be sure we are setting a new & different preset
        await self.ref_left.assert_active_preset(self.dut_hap, FOO_PRESET)

        await asyncio.gather(
            self.dut_hap.SetActivePreset(connection=self.ref_left.to_ref, index=BAR_PRESET.index),
            self.ref_left.ref.aio.host.Disconnect(connection=self.ref_left.to_ref))

        # Wait to ensure disconnection completes without crashing.
        await asyncio.sleep(3)

    @asynchronous
    async def test__select_left_preset__when_in_synchronized_set__right_is_updated(self) -> None:
        await self.setup_binaural()

        # preliminary check to be sure we are setting a new & different preset
        await self.ref_left.assert_active_preset(self.dut_hap, FOO_PRESET)

        await self.ref_left.set_active_preset_for_group_and_verify(self.dut_hap, self.ref_right,
                                                                   BAR_PRESET)

    @asynchronous
    async def test__synchronize_operation_failed__when_selecting_preset__can_recover(self) -> None:
        await self.setup_binaural()

        # remove synchronization capabilities
        self.ref_left.has.other_server_in_binaural_set = None
        self.ref_right.has.other_server_in_binaural_set = None

        # preliminary check to be sure we are setting a new & different preset
        await self.ref_left.assert_active_preset(self.dut_hap, FOO_PRESET)

        await self.dut_hap.SetActivePresetForGroup(connection=self.ref_left.to_ref,
                                                   index=BAR_PRESET.index)

        # Only left is updated
        await self.dut_hap.WaitActivePresetChanged(connection=self.ref_left.to_ref,
                                                   index=BAR_PRESET.index)

        # Timeout group operation is 10 secondes
        await asyncio.sleep(11)

        # As expected, only left preset has been updated
        await self.ref_left.assert_active_preset(self.dut_hap, BAR_PRESET)
        await self.ref_right.assert_active_preset(self.dut_hap, FOO_PRESET)

        # restore synchronization capabilities
        synchronize_has(self.ref_left, self.ref_right)

        await self.dut_hap.SetActivePresetForGroup(connection=self.ref_left.to_ref,
                                                   index=BAR_PRESET.index)

        # Wait for the DUT to recover and synchronize.
        await self.dut_hap.WaitActivePresetChanged(connection=self.ref_right.to_ref,
                                                   index=BAR_PRESET.index)

        await self.ref_left.assert_active_preset(self.dut_hap, BAR_PRESET)
        await self.ref_right.assert_active_preset(self.dut_hap, BAR_PRESET)
