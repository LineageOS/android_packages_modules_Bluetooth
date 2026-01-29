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
"""VCP proxy module."""
import threading

from mmi2grpc._helpers import assert_description, match_description
from mmi2grpc._proxy import ProfileProxy
from mmi2grpc._rootcanal import Dongle

from pandora.vcp_grpc import VCP
from pandora.gatt_grpc import GATT
from pandora.security_grpc import Security, SecurityStorage
from pandora.security_pb2 import LE_LEVEL3, PairingEventAnswer
from pandora.host_grpc import Host
from pandora.host_pb2 import PUBLIC, RANDOM
from pandora.security_grpc import Security
from pandora.security_pb2 import LE_LEVEL3, PairingEventAnswer
from pandora.le_audio_grpc import LeAudio

from time import sleep


class VCPProxy(ProfileProxy):

    def __init__(self, channel, rootcanal):
        super().__init__(channel)
        self.vcp = VCP(channel)
        self.gatt = GATT(channel)
        self.security_storage = SecurityStorage(channel)
        self.host = Host(channel)
        self.security = Security(channel)
        self.le_audio = LeAudio(channel)
        self.rootcanal = rootcanal
        self.connection = None
        self.pairing_stream = self.security.OnPairing()

    def test_started(self, test: str, description: str, pts_addr: bytes):
        self.rootcanal.select_pts_dongle(Dongle.LAIRD_BL654)

        return "OK"

    @assert_description
    def IUT_INITIATE_CONNECTION(self, pts_addr: bytes, **kwargs):
        """
        Please initiate a GATT connection to the PTS.

        Description: Verify that
        the Implementation Under Test (IUT) can initiate a GATT connect request
        to the PTS.
        """
        self.security_storage.DeleteBond(public=pts_addr)
        self.connection = self.host.ConnectLE(own_address_type=RANDOM, public=pts_addr).connection

        def secure():
            self.security.Secure(connection=self.connection, le=LE_LEVEL3)

        def vcp_connect():
            self.vcp.WaitConnect(connection=self.connection)

        threading.Thread(target=secure).start()
        threading.Thread(target=vcp_connect).start()

        return "OK"

    @match_description
    def _mmi_2004(self, pts_addr: bytes, passkey: str, **kwargs):
        """
        Please confirm that 6 digit number is matched with (?P<passkey>[0-9]*).
        """
        received = []
        for event in self.pairing_stream:
            if event.address == pts_addr and event.numeric_comparison == int(passkey):
                self.pairing_stream.send(PairingEventAnswer(
                    event=event,
                    confirm=True,
                ))
                return "OK"
            received.append(event.numeric_comparison)

        assert False, f"mismatched passcode: expected {passkey}, received {received}"

    @match_description
    def IUT_INITIATE_DISCOVER_CHARACTERISTIC(self, **kwargs):
        """
        Please take action to discover the
        (Volume (Control Point|State|Flags|Offset Control Point)|Offset State|Audio Input (State|Type|Status|Control Point|Description)|Gain Setting Properties)
        characteristic from the (Volume( Offset)?|Audio Input) Control. Discover the primary service if needed.
        Description: Verify that the Implementation Under Test \(IUT\) can send
        Discover All Characteristics command.
        """
        # PTS expects us to do discovery after bonding, but in fact Android does it as soon as
        # encryption is completed. Invalidate GATT cache so the discovery takes place again
        self.gatt.ClearCache(connection=self.connection)

        return "OK"

    @match_description
    def IUT_READ_CHARACTERISTIC(self, name: str, handle: str, **kwargs):
        """
        Please send Read Request to read (?P<name>(Volume State|Volume Flags|Offset State|Audio Input (State|Status|Type)|Gain Setting Properties)) characteristic with handle
        = (?P<handle>(0x[0-9A-Fa-f]{4})).
        """

        # After discovery Android reads these values by itself, after profile connection.
        # Although, for some tests, this is used as validation, for example for tests with invalid
        # behavior (BI tests). Just send GATT read to sattisfy this conditions, as VCP has no exposed
        # (or even existing, native) interface to trigger read on demand.
        def read():
            nonlocal handle
            self.gatt.ReadCharacteristicFromHandle(\
                    connection=self.connection, handle=int(handle, base=16))

        worker = threading.Thread(target=read)
        worker.start()
        worker.join(timeout=30)

        return "OK"

    @match_description
    def USER_CONFIRM_SUPPORTED_CHARACTERISTIC(self, body: str, **kwargs):
        """
        Please verify that for each supported characteristic, attribute
        handle/UUID pair\(s\) is returned to the (.*)\.(?P<body>.*)
        """

        return "OK"

    @match_description
    def IUT_CONFIG_NOTIFICATION(self, name: str, **kwargs):
        """
        Please write to Client Characteristic Configuration Descriptor of
        (?P<name>(Volume State|Offset State|Audio Input State)) characteristic to enable notification.(.*)
        """

        # After discovery Android subscribes by itself, after profile connection
        return "OK"

    @match_description
    def IUT_SEND_WRITE_REQUEST(self, description: str, chr_name: str, op_code: str, **kwargs):
        r"""
        Please send write request to handle 0x([0-9A-Fa-f]{4}) with following value.
        (?P<chr_name>(Volume Control Point|Volume Offset Control Point|Audio Input Control Point)):
            Op Code: (?P<op_code>((<WildCard: Exists>)|(\[[0-9] \(0x0[0-9]\)\]\s([\w]*\s){1,4})))(.*)
        """

        # Wait a couple seconds so the VCP is ready (subscriptions and reads are completed)
        sleep(4)

        if (chr_name == "Volume Control Point"):
            if "Set Absolute Volume" in op_code:
                self.vcp.SetDeviceVolume(connection=self.connection, volume=42)
            elif ("Unmute" in op_code):
                # for now, there is no way to trigger this, and tests are skipped
                return "No"
            elif ("<WildCard: Exists>" in op_code):
                # Handles sending *any* OP Code on Volume Control Point
                self.vcp.SetDeviceVolume(connection=self.connection, volume=42)
        elif (chr_name == "Volume Offset Control Point"):
            if ("Set Volume Offset" in op_code or "<WildCard: Exists>" in op_code):
                self.vcp.SetVolumeOffset(connection=self.connection, offset=42)
        elif (chr_name == "Audio Input Control Point"):
            if "[1 (0x01)] Set Gain Setting" in op_code:
                self.vcp.SetGainSetting(connection=self.connection, gainSetting=42)
            elif "[2 (0x02)] Unmute" in op_code:
                self.vcp.SetMute(connection=self.connection, mute=0x00)
            elif "[3 (0x03)] Mute" in op_code:
                self.vcp.SetMute(connection=self.connection, mute=0x01)
            elif "[4 (0x04)] Set Manual Gain Mode" in op_code:
                self.vcp.SetGainMode(connection=self.connection, gainMode=0x02)
            elif "[5 (0x05)] Set Automatic Gain Mode" in op_code:
                self.vcp.SetGainMode(connection=self.connection, gainMode=0x03)
            elif "<WildCard: Exists>" in op_code:
                self.vcp.SetMute(connection=self.connection, mute=0)
            else:
                assert False, f'Unhandled op_code in IUT_SEND_WRITE_REQUEST:\n{op_code}'
        else:
            return "No"

        return "OK"

    @assert_description
    def _mmi_20501(self, **kwargs):
        """
        Please start general inquiry. Click 'Yes' If IUT does discovers PTS
        otherwise click 'No'.
        """
        return "OK"

    @assert_description
    def IUT_WRITE_GAIN_SETTING_MAX(self, **kwargs):
        """
        Please write to Audio Input Control Point with the Set Gain Setting Op
        Code value of 0x01, the Gain Setting parameters set to a random value
        greater than 100 and the Change Counter parameter set.
        """
        # Wait a couple seconds so the VCP is ready (subscriptions and reads are completed)
        sleep(4)

        self.vcp.SetGainSetting(connection=self.connection, gainSetting=101)

        return "OK"

    @assert_description
    def IUT_WRITE_UNMUTE_OPCODE(self, **kwargs):
        """
        Please write to Audio Input Control Point with the Unmute Op Code.
        """
        # Wait a couple seconds so the VCP is ready (subscriptions and reads are completed)
        sleep(4)

        self.vcp.SetMute(connection=self.connection, mute=0x00)

        return "OK"

    @assert_description
    def IUT_WRITE_MUTE_OPCODE(self, **kwargs):
        """
        Please write to Audio Input Control Point with the Mute Op Code.
        """
        # Wait a couple seconds so the VCP is ready (subscriptions and reads are completed)
        sleep(4)

        self.vcp.SetMute(connection=self.connection, mute=0x01)

        return "OK"

    @assert_description
    def IUT_WRITE_SET_MANUAL_GAIN_MODE_OPCODE(self, **kwargs):
        """
        Please write to Audio Input Control Point with the Set Manual Op Code.
        """
        # Wait a couple seconds so the VCP is ready (subscriptions and reads are completed)
        sleep(4)

        self.vcp.SetGainMode(connection=self.connection, gainMode=0x02)

        return "OK"

    @assert_description
    def IUT_WRITE_SET_AUTOMATIC_GAIN_MODE_OPCODE(self, **kwargs):
        """
        Please write to Audio Input Control Point with the Set Automatic Op
        Code.
        """
        # Wait a couple seconds so the VCP is ready (subscriptions and reads are completed)
        sleep(4)

        self.vcp.SetGainMode(connection=self.connection, gainMode=0x03)

        return "OK"
