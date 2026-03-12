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
"""Tests for HID Headtracker implementation on Android."""

import dataclasses
import enum
import struct
from typing import Self

from bumble import core
from bumble import gatt
from bumble import gatt_adapters
from bumble import hci
from bumble.profiles import bap
from bumble.profiles import le_audio
from bumble.profiles import pacs
from mobly import test_runner
from mobly import signals
from typing_extensions import override

from navi.bumble_ext import ascs
from navi.bumble_ext import hid
from navi.bumble_ext import pacs as pacs_ext
from navi.tests import navi_test_base
from navi.utils import android_constants
from navi.utils import bl4a_api

_SERVICE_UUID = core.UUID("109b862f-50e3-45cc-8ea1-ac62de4846d1")
_VERSION_CHARACTERISTIC_UUID = core.UUID("b4eb9919-a910-46a2-a9dd-fec2525196fd")
_CONTROL_CHARACTERISTIC_UUID = core.UUID("8584cbb5-2d58-45a3-ab9d-583e0958b067")
_REPORT_CHACTERISTIC_UUID = core.UUID("e66dd173-b2ae-4f5a-ae16-0162af8038ae")

_VENDOR_COMPANY_ID_GOOGLE = 0x00E0
_HEADTACKER_METADATA_LENGTH = 1
_HEADTACKER_METADATA_TYPE_VALUE = 1


class _HeadtrackerTransport(enum.IntFlag):
    ACL = 0x01
    ISO = 0x02


_AndroidProperty = android_constants.Property


@dataclasses.dataclass
class _HeadtrackerReport:

    class Transport(enum.IntEnum):
        ACL = 0
        ISO = 1

    reporting_state: bool
    power_state: bool
    report_interval_ms: int
    transport: Transport

    def __bytes__(self) -> bytes:
        return bytes([
            ((1 if self.reporting_state else 0) | (1 << 1 if self.power_state else 0) |
             self.report_interval_ms << 2),
            self.transport.value,
        ])

    @classmethod
    def from_bytes(cls, data: bytes) -> Self:
        return cls(
            reporting_state=bool(data[0] & 1),
            power_state=bool(data[0] & (1 << 1)),
            report_interval_ms=data[0] >> 2,
            transport=cls.Transport(data[1]),
        )


class HidHeadtrackerTest(navi_test_base.TwoDevicesTestBase):
    ref_headtracker_service: gatt.Service
    ref_headtracker_report_characteristic: gatt.Characteristic

    def _setup_lea_services(self) -> None:
        self.ref.device.add_service(
            pacs_ext.make_pacs(source_pacs=[
                pacs.PacRecord(
                    coding_format=hci.CodingFormat(hci.CodecID.LC3),
                    codec_specific_capabilities=bap.CodecSpecificCapabilities(
                        supported_sampling_frequencies=(bap.SupportedSamplingFrequency.FREQ_16000 |
                                                        bap.SupportedSamplingFrequency.FREQ_32000 |
                                                        bap.SupportedSamplingFrequency.FREQ_48000),
                        supported_frame_durations=(
                            bap.SupportedFrameDuration.DURATION_7500_US_SUPPORTED |
                            bap.SupportedFrameDuration.DURATION_10000_US_SUPPORTED),
                        supported_audio_channel_count=[1],
                        min_octets_per_codec_frame=13,
                        max_octets_per_codec_frame=120,
                        supported_max_codec_frames_per_sdu=1,
                    ),
                ),
                pacs.PacRecord(
                    coding_format=hci.CodingFormat(
                        codec_id=hci.CodecID.VENDOR_SPECIFIC,
                        company_id=_VENDOR_COMPANY_ID_GOOGLE,
                        vendor_specific_codec_id=0x0002,
                    ),
                    codec_specific_capabilities=bap.CodecSpecificCapabilities(
                        supported_sampling_frequencies=bap.SupportedSamplingFrequency.FREQ_48000,
                        supported_frame_durations=(
                            bap.SupportedFrameDuration.DURATION_7500_US_SUPPORTED |
                            bap.SupportedFrameDuration.DURATION_10000_US_SUPPORTED),
                        supported_audio_channel_count=[1],
                        min_octets_per_codec_frame=13,
                        max_octets_per_codec_frame=120,
                        supported_max_codec_frames_per_sdu=1,
                    ),
                    metadata=le_audio.Metadata([
                        le_audio.Metadata.Entry(
                            le_audio.Metadata.Tag.VENDOR_SPECIFIC,
                            data=struct.pack(
                                "<HBBB",
                                _VENDOR_COMPANY_ID_GOOGLE,
                                _HEADTACKER_METADATA_LENGTH,
                                _HEADTACKER_METADATA_TYPE_VALUE,
                                _HeadtrackerTransport.ACL.value,
                            ),
                        )
                    ]),
                ),
            ],))
        self.ref_ascs = ascs.AudioStreamControlService(
            self.ref.device,
            sink_ase_id=[1],
            source_ase_id=[2],
        )
        self.ref.device.add_service(self.ref_ascs)

    def _setup_hid_service(self) -> None:
        self.ref_headtracker_report_characteristic = gatt.Characteristic(
            _REPORT_CHACTERISTIC_UUID,
            gatt.Characteristic.Properties.READ | gatt.Characteristic.Properties.WRITE |
            gatt.Characteristic.Properties.NOTIFY,
            gatt.Characteristic.READABLE | gatt.Characteristic.WRITEABLE,
        )
        self.ref_headtracker_service = gatt.Service(
            _SERVICE_UUID,
            [
                gatt.Characteristic(
                    _VERSION_CHARACTERISTIC_UUID,
                    gatt.Characteristic.Properties.READ,
                    gatt.Characteristic.READABLE,
                    b"#AndroidHeadTracker#2.0#1" + bytes(8) + b"BT" +
                    bytes(self.ref.device.random_address)[::-1],
                ),
                gatt_adapters.SerializableCharacteristicAdapter(
                    gatt.Characteristic(
                        _CONTROL_CHARACTERISTIC_UUID,
                        gatt.Characteristic.Properties.READ,
                        gatt.Characteristic.READABLE,
                        value=_HeadtrackerReport(
                            reporting_state=True,
                            power_state=True,
                            report_interval_ms=10,
                            transport=_HeadtrackerReport.Transport.ACL,
                        ),
                    ),
                    _HeadtrackerReport,
                ),
                self.ref_headtracker_report_characteristic,
            ],
        )
        self.ref.device.add_service(self.ref_headtracker_service)

    @override
    async def async_setup_class(self) -> None:
        await super().async_setup_class()
        if self.dut.device.adb.getprop(hid.PROPERTY_HID_HOST_SUPPORTED) != "true":
            raise signals.TestAbortClass("HID host is not supported on DUT")
        if not self.dut.is_le_audio_supported:
            raise signals.TestAbortClass("[DUT] Unicast client is not enabled")
        if self.dut.getprop("ro.audio.spatializer_enabled") != "true":
            raise signals.TestAbortClass("Spatializer is not enabled")

        if (self.dut.bt.getSdkVersion() >= 35 and android_constants.AudioDeviceType.BLE_HEADSET
                not in self.dut.bt.getSupportedAudioDeviceTypes(
                    android_constants.AudioDeviceRole.OUTPUT)):
            raise signals.TestAbortClass("Device does not support LE Audio.")

        self.ref.config.cis_enabled = True
        self.ref.device.cis_enabled = True

        self.setprop_for_class_context(_AndroidProperty.LEAUDIO_BYPASS_ALLOW_LIST, "true")

    @override
    async def async_teardown_test(self) -> None:
        self.dut.bt.clearCompatibleSpatizlierDevices()
        await super().async_teardown_test()

    async def test_enable_headtracker(self) -> None:
        """Tests enabling headtracker.

    Test steps:
      1. Establish the HID connection between DUT and REF.
      2. Verify the HID connection is established.
      3. Verify the LE Audio connection is established.
      4. Add compatible spatizlier device.
      5. Verify the compatible spatizlier device is added.
      6. Enable headtracker.
      7. Verify the headtracker is enabled.
    """
        self.dut.bt.setSpatializerEnabled(True)
        self._setup_hid_service()
        self._setup_lea_services()
        dut_hid_cb = self.dut.bl4a.register_callback(bl4a_api.Module.HID_HOST)
        dut_lea_cb = self.dut.bl4a.register_callback(bl4a_api.Module.LE_AUDIO)
        self.test_case_context.enter_context(dut_hid_cb)
        self.test_case_context.enter_context(dut_lea_cb)

        self.logger.info("[DUT] Pair with REF")
        await self.le_connect_and_pair(hci.OwnAddressType.RANDOM, connect_profiles=True)
        self.logger.info("[DUT] Wait for HID connected")
        await dut_hid_cb.wait_for_event(
            bl4a_api.ProfileConnectionStateChanged(
                address=self.ref.random_address,
                state=android_constants.ConnectionState.CONNECTED,
            ),)
        self.logger.info("[DUT] Wait for LE Audio active device changed")
        await dut_lea_cb.wait_for_event(
            bl4a_api.ProfileActiveDeviceChanged(address=self.ref.random_address),)

        self.logger.info("[DUT] Add compatible spatizlier device")
        self.dut.bt.addCompatibleSpatizlierDevice(
            android_constants.AudioDeviceRole.OUTPUT,
            android_constants.AudioDeviceType.BLE_HEADSET,
            self.ref.random_address,
        )

        compatible_spatizlier_devices = self.dut.bt.getCompatibleSpatizlierDevices()
        self.logger.info(
            "[DUT] Compatible Spatizlier devices: %s",
            compatible_spatizlier_devices,
        )
        self.assertIn(self.ref.random_address, compatible_spatizlier_devices)

        self.logger.info("[DUT] Set headtracker enabled")
        self.dut.bt.setHeadtrackerEnabled(
            android_constants.AudioDeviceRole.OUTPUT,
            android_constants.AudioDeviceType.BLE_HEADSET,
            self.ref.random_address,
            True,
        )

        is_headtracker_enabled = self.dut.bt.getHeadtrackerEnabled(
            android_constants.AudioDeviceRole.OUTPUT,
            android_constants.AudioDeviceType.BLE_HEADSET,
            self.ref.random_address,
        )
        self.logger.info("[DUT] Is headtracker enabled: %s", is_headtracker_enabled)
        self.assertTrue(is_headtracker_enabled)


if __name__ == "__main__":
    test_runner.main()
