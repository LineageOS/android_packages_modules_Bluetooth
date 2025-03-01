# Copyright (C) 2024 The Android Open Source Project
#
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

import random
import string
import threading
import time
import uuid

from mmi2grpc._audio import AudioSignal
from mmi2grpc._helpers import assert_description, match_description
from mmi2grpc._proxy import ProfileProxy
from mmi2grpc._rootcanal import Dongle
from pandora.host_grpc import Host
from pandora.host_pb2 import RANDOM
from pandora.security_grpc import Security
from pandora.security_pb2 import LE_LEVEL3, PairingEventAnswer
from pandora_experimental.gatt_grpc import GATT
from pandora_experimental.hap_grpc import HAP
from pandora_experimental.hap_pb2 import HaPlaybackAudioRequest

BASE_UUID = uuid.UUID("00000000-0000-1000-8000-00805F9B34FB")
SINK_ASE_UUID = 0x2BC4
ASE_CONTROL_POINT_UUID = 0x2BC6
HEARING_AID_PRESET_CONTROL_POINT_UUID = 0x2BDB
ACTIVE_PRESET_INDEX_UUID = 0x2BDC
CCCD_UUID = 0x2902

AUDIO_SIGNAL_AMPLITUDE = 0.8
AUDIO_SIGNAL_SAMPLING_RATE = 44100


def short_uuid(full: uuid.UUID) -> int:
    return (uuid.UUID(full).int - BASE_UUID.int) >> 96


class HAPProxy(ProfileProxy):

    def __init__(self, channel, rootcanal):
        super().__init__(channel)
        self.hap = HAP(channel)
        self.gatt = GATT(channel)
        self.host = Host(channel)
        self.security = Security(channel)
        self.pairing_events = self.security.OnPairing()
        self.discovered_services = None
        self.preset_list = None
        self.rootcanal = rootcanal
        self.connection = None

        def convert_frame(data):
            return HaPlaybackAudioRequest(data=data, source=self.source)

        self.audio = AudioSignal(lambda frames: self.hap.HaPlaybackAudio(map(convert_frame, frames)),
                                 AUDIO_SIGNAL_AMPLITUDE, AUDIO_SIGNAL_SAMPLING_RATE)

    def test_started(self, test: str, **kwargs):
        self.rootcanal.select_pts_dongle(Dongle.LAIRD_BL654)
        return "OK"

    @assert_description
    def MMI_IUT_MTU_EXCHANGE(self, **kwargs):
        """
        Please send exchange MTU command to the PTS with MTU size greater than
        49.
        """

        assert self.connection is not None
        self.gatt.ExchangeMTU(mtu=512, connection=self.connection)
        return "OK"

    @match_description
    def ORDER_IUT_SEND_PRESET_WRITE_NAME(self, test: str, index: str, **kwargs):
        """
        Please write preset name to index: (?P<index>[0-9]*) with random string.
        """

        assert self.connection is not None

        # This MMI can be called multiple times, wait only for the first time
        if not self.preset_list:
            # Android reads all presets after device is paired. Wait for initial preset record before sending next request.
            self.preset_list = self.hap.WaitPresetChanged().preset_record_list
            assert self.preset_list

        preset_name = ''.join(random.choice(string.ascii_lowercase) for i in range(10))

        self.hap.WritePresetName(connection=self.connection, index=int(index), name=preset_name)

        return "OK"

    @assert_description
    def IUT_ORDER_WRITE_READ_ALL_PRESET(self, test: str, **kwargs):
        """
        Please write Read Preset opcode with index 0x01 and num presets to 0xff.
        """

        assert self.connection is not None

        self.preset_list = self.hap.WaitPresetChanged()
        assert self.preset_list

        responseMessage = self.hap.GetAllPresetRecords(connection=self.connection)

        self.log(f"Received preset record:\n")
        for presetRecord in responseMessage.preset_record_list:
            self.log(f"    Index: {presetRecord.index}\n"
                     f"    Name: {presetRecord.name}\n"
                     f"    Writable: {presetRecord.isWritable}\n"
                     f"    Available: {presetRecord.isAvailable}\n")

        return "OK"

    @assert_description
    def IUT_CONFIRM_READY_TO_RECEIVE_preset_list(self, test: str, **kwargs):
        """
        Please click OK when IUT is ready to receive Preset Changed message.
        """

        self.preset_list = self.hap.WaitPresetChanged().preset_record_list
        assert self.preset_list

        return "OK"

    @assert_description
    def IUT_CONFIRM_NEW_PRESET_RECORD(self, test: str, **kwargs):
        """
        Please confirm that new preset record was added to IUT's internal list.
        """

        return "OK"

    @match_description
    def IUT_READ_PRESET_INDEX(self, test: str, index: str, num_presets: str, **kwargs):
        """
        Please write Read Preset Index opcode with index: (?P<index>[0-9]*), numPresets: (?P<num_presets>[0-9]*).
        """

        assert self.connection is not None

        # Android reads all presets after device is paired. Wait for initial preset record before sending next request.
        self.preset_list = self.hap.WaitPresetChanged().preset_record_list
        assert self.preset_list

        responseMessage = self.hap.GetPresetRecord(connection=self.connection, index=int(index))

        self.log(f"Received preset record:\n"
                 f"    Index: {responseMessage.preset_record.index}\n"
                 f"    Name: {responseMessage.preset_record.name}\n"
                 f"    Writable: {responseMessage.preset_record.isWritable}\n"
                 f"    Available: {responseMessage.preset_record.isAvailable}\n")

        return "OK"

    @assert_description
    def IUT_CONFIGURE_TO_STREAMING_STATE(self, test: str, **kwargs):
        """
        Please configure to Streaming state.
        """

        self.audio.start()

        return "OK"

    @match_description
    def IUT_ORDER_WRITE_SET_ACTIVE_PRESET_INDEX_SYNC_LOCALLY(self, test: str, index: str, **kwargs):
        """
        Please write Set Active Preset Synchronized Locally opcode with index: (?P<index>[0-9]*).
        """

        self.hap.SetActivePreset(connection=self.connection, index=int(index))

        return "OK"

    @assert_description
    def IUT_ORDER_WRITE_SET_NEXT_PRESET_INDEX_SYNC_LOCALLY(self, test: str, **kwargs):
        """
        Please write Set Next Preset Synchronized Locally opcode
        """

        self.hap.SetNextPreset(connection=self.connection)

        return "OK"

    @assert_description
    def IUT_ORDER_WRITE_SET_PREVIOUS_PRESET_INDEX_SYNC_LOCALLY(self, test: str, **kwargs):
        """
        Please write Set Previous Preset Synchronized Locally opcode.
        """

        self.hap.SetPreviousPreset(connection=self.connection)

        return "OK"

    @match_description
    def IUT_ORDER_WRITE_SET_ACTIVE_PRESET_INDEX_DO_NOT_EXPECT_TO_RECEIVE(self, test: str, index: str, **kwargs):
        """
        Please write Set Active Preset opcode with index: (?P<index>[0-9]*). Do not expect to
        receive the message
        """

        self.hap.SetActivePreset(connection=self.connection, index=int(index))

        return "OK"

    @assert_description
    def IUT_CONFIRM_READY_TO_RECEIVE_PRESET_CHANGED(self, test: str, **kwargs):
        """
      Please click OK when IUT is ready to receive Preset Changed message.
      """

        return "OK"

    @match_description
    def IUT_ORDER_WRITE_SET_ACTIVE_PRESET_INDEX(self, test: str, index: str, **kwargs):
        """
        Please write Set Active Preset opcode with index: (?P<index>[0-9]*).
        """

        self.hap.SetActivePreset(connection=self.connection, index=int(index))

        return "OK"

    @match_description
    def _mmi_2004(self, pts_addr: bytes, passkey: str, **kwargs):
        """
        Please confirm that 6 digit number is matched with (?P<passkey>[0-9]+).
        """

        for event in self.pairing_events:
            if event.address == pts_addr and event.numeric_comparison == int(passkey):
                self.pairing_events.send(PairingEventAnswer(
                    event=event,
                    confirm=True,
                ))
                return "OK"

        assert False

    @assert_description
    def _mmi_20100(self, test, pts_addr: bytes, **kwargs):
        """
        Please initiate a GATT connection to the PTS.

        Description: Verify that
        the Implementation Under Test (IUT) can initiate a GATT connect request
        to the PTS.
        """

        self.connection = self.host.ConnectLE(own_address_type=RANDOM, public=pts_addr).connection

        def secure():
            self.security.Secure(connection=self.connection, le=LE_LEVEL3)

        threading.Thread(target=secure).start()

        return "OK"

    @match_description
    def _mmi_20103(self, **kwargs):
        """
        Please take action to discover the (Active Preset Index|Hearing Aid Features) characteristic
        from the Hearing Access. Discover the primary service if needed.
        Description: Verify that the Implementation Under Test \(IUT\) can send
        Discover All Characteristics command.
        """

        # Clear GATT cache to make sure discovery takes place.
        self.gatt.ClearCache(connection=self.connection)
        return "OK"

    @match_description
    def _mmi_20106(self, test: str, characteristic_name: str, type: str, **kwargs):
        """
        Please write to Client Characteristic Configuration Descriptor
        of (?P<characteristic_name>(ASE Control Point|Sink Audio Stream Endpoint|Active Preset Index))
        characteristic to enable (?P<type>(notification|indication)).
        """

        return "OK"

    @match_description
    def _mmi_20107(self, test: str, characteristic_name: str, handle: str, **kwargs):
        """
        Please send Read Request to read (?P<characteristic_name>.*) characteristic with handle = (?P<handle>\S*).
        """

        handle = int(handle, base=16)

        self.gatt.ReadCharacteristicFromHandle(
            connection=self.connection,
            handle=handle,
        )

        return "OK"

    @assert_description
    def _mmi_20206(self, **kwargs):
        """
        Please verify that for each supported characteristic, attribute
        handle/UUID pair(s) is returned to the upper tester.Hearing Aid
        Features: Attribute Handle = 0x00D4
        Characteristic Properties = 0x12
        Handle = 0x00D5
        UUID = 0x2BDA

        Hearing Aid Preset Control Point:
        Attribute Handle = 0x00D1
        Characteristic Properties = 0x38
        Handle =
        0x00D2
        UUID = 0x2BDB

        Active Preset Index: Attribute Handle = 0x00D7
        Characteristic Properties = 0x12
        Handle = 0x00D8
        UUID = 0x2BDC
        """

        return "OK"
