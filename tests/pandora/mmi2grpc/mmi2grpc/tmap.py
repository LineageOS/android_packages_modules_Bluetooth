# Copyright (C) 2025 The Android Open Source Project
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

import threading
import time

from mmi2grpc._audio import AudioSignal
from mmi2grpc._helpers import assert_description, match_description
from mmi2grpc._proxy import ProfileProxy
from mmi2grpc._rootcanal import Dongle
from pandora.host_grpc import Host
from pandora.host_pb2 import RANDOM
from pandora.security_grpc import Security, SecurityStorage
from pandora.security_pb2 import LE_LEVEL3, PairingEventAnswer
from pandora.gatt_grpc import GATT
from pandora.le_audio_grpc import LeAudio
from pandora.le_audio_pb2 import LeAudioPlaybackAudioRequest, AUDIO_USAGE_MEDIA, \
    GROUP_STREAM_STATUS_STREAMING, AUDIO_USAGE_VOICE_COMMUNICATION, \
    AUDIO_USAGE_NOTIFICATION_RINGTONE
from pandora.hfp_grpc import HFP
from pandora.vcp_grpc import VCP
from pandora.vcp_pb2 import CONNECTION_POLICY_ALLOWED, CONNECTION_POLICY_FORBIDDEN

IXIT_PHONE_NUMBER = 1234567890
AUDIO_SIGNAL_AMPLITUDE = 0.8
AUDIO_SIGNAL_SAMPLING_RATE = 44100


class TMAPProxy(ProfileProxy):

    def __init__(self, channel, rootcanal, modem):
        super().__init__(channel)
        self.security_storage = SecurityStorage(channel)
        self.rootcanal = rootcanal
        self.gatt = GATT(channel)
        self.host = Host(channel)
        self.le_audio = LeAudio(channel)
        self.hfp = HFP(channel)
        self.vcp = VCP(channel)
        self.security = Security(channel)
        self.pairing_events = self.security.OnPairing()
        self.discovered_services = None
        self.connection = None
        self.modem = modem

        def convert_frame(data):
            return LeAudioPlaybackAudioRequest(data=data)

        self.audio = AudioSignal(
            lambda frames: self.le_audio.LeAudioPlaybackAudio(map(convert_frame, frames)),
            AUDIO_SIGNAL_AMPLITUDE, AUDIO_SIGNAL_SAMPLING_RATE)

    def test_started(self, test: str, **kwargs):
        self.rootcanal.select_pts_dongle(Dongle.LAIRD_BL654)
        return "OK"

    @assert_description
    def _mmi_20100(self, test, pts_addr: bytes, **kwargs):
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

        threading.Thread(target=secure).start()

        # It may happen that VCP write triggered by Audio Framework happens too early,
        # when the service is not yet ready on the remote side. We will enable VCP
        # later in the MMI that requires it.
        self.vcp.SetConnectionPolicy(connection=self.connection, policy=CONNECTION_POLICY_FORBIDDEN)

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

        raise AssertionError(
            f"Passkey {passkey} not matched with any pairing event for {pts_addr}.")

    @match_description
    def _mmi_20106(self, test: str, characteristic_name: str, type: str, **kwargs):
        """
        Please write to Client Characteristic Configuration Descriptor
        of (?P<characteristic_name>(ASE Control Point))
        characteristic to enable (?P<type>(notification|indication)).
        """

        return "OK"

    @assert_description
    def MMI_TMAP_CONFIGURE_TO_STREAMING(self, test: str, **kwargs):
        """
        Please configure to Streaming state by starting the CAP Unicast Audio
        Start Procedure. In order to start the CAP Unicast Audio Start
        Procedure, the IUT may simulate/receive an incoming call, accepts and
        establishes the call with confirmation from the Upper Tester.
        """

        def start_playback():
            self.audio.start()

        playback_thread = threading.Thread(target=start_playback)
        self.le_audio.Open(connection=self.connection)
        self.le_audio.LeAudioStart(connection=self.connection,
                                   audioUsage=AUDIO_USAGE_VOICE_COMMUNICATION |
                                   AUDIO_USAGE_NOTIFICATION_RINGTONE)
        playback_thread.start()
        playback_thread.join()
        return "OK"

    @assert_description
    def MMI_TMAP_START_CCP_DISCOVERY(self, **kwargs):
        """
        Please click ok when the tester is ready to discover Generic Telephone
        Bearer Service
        """

        return "OK"

    @assert_description
    def MMI_TMAP_ORDER_IUT_START_INCOMING_CALL(self, **kwargs):
        """
        Please order IUT to receive an incoming call, accepts and establishes
        the call with confirmation from the Upper Tester
        """

        def enable_call():
            self.modem.call(IXIT_PHONE_NUMBER)

        threading.Thread(target=enable_call).start()
        self.log("Waiting for the group to enter the streaming state.")
        self.le_audio.LeAudioWaitGroupStreamStatusChanged(
            connection=self.connection, groupStreamStatus=GROUP_STREAM_STATUS_STREAMING)
        self.log("The group is in the streaming state.")
        self.hfp.AnswerCall()
        return "OK"

    @assert_description
    def MMI_TMAP_IUT_START_CAP_UNICAST_AUDIO_START_PROCEDURE_UMS(self, **kwargs):
        """
        Please click Ok when IUT is ready to start the CAP Unicast Audio Start
        Procedure.
        """

        def start_playback():
            self.audio.start()

        playback_thread = threading.Thread(target=start_playback)
        self.le_audio.Open(connection=self.connection)
        self.le_audio.LeAudioStart(connection=self.connection, audioUsage=AUDIO_USAGE_MEDIA)
        playback_thread.start()
        playback_thread.join()
        return "OK"

    @match_description
    def MMI_BAP_IUT_WRITE_CONFIG_CODEC_STREAMING_STATE(self, ase_count: int, ase_direction: str,
                                                       config_setting: str, **kwargs):
        """
        Please configure (?P<ase_count>\d+) (?P<ase_direction>SINK|SOURCE) ASE with Config Setting:
         (?P<config_setting>[\w_]+).
        After that,
        configure to streaming state.
        """

        def start_playback():
            self.audio.start()

        playback_thread = threading.Thread(target=start_playback)
        self.le_audio.Open(connection=self.connection)
        self.le_audio.LeAudioStart(connection=self.connection, audioUsage=AUDIO_USAGE_MEDIA)
        playback_thread.start()
        playback_thread.join()
        return "OK"

    @assert_description
    def MMI_IUT_VERIFY_AUDIO_STREAM_DATA(self, **kwargs):
        """
        After processed audio stream data, please click OK.
        """
        return "OK"

    @match_description
    def _mmi_20107(self, test: str, characteristic_name: str, handle: str, **kwargs):
        """
        Please send Read Request to read (?P<characteristic_name>.*) characteristic
        with handle = (?P<handle>\S*).
        """

        # Enable VCP, so that the notifications are sent from the remote.
        self.vcp.SetConnectionPolicy(connection=self.connection, policy=CONNECTION_POLICY_ALLOWED)

        handle = int(handle, base=16)
        self.gatt.ReadCharacteristicFromHandle(
            connection=self.connection,
            handle=handle,
        )

        return "OK"

    @match_description
    def _mmi_20110(self, volume_setting: str, **kwargs):
        """
        Please send write request to handle 0x024A with following value.
        Volume Control Point:
            Op Code: \[4 \(0x04\)]\ Set Absolute Volume
            Change
            Counter: <WildCard: Exists>
            Volume Setting: \[(?P<volume_setting>\d+) \(0x[0-9a-fA-F]+\)\].*
        """
        # Wait a couple seconds so the VCP is ready (subscriptions and reads are completed)
        time.sleep(4)
        volume_value = int(volume_setting)
        self.log(f"Set device volume={volume_value}")
        self.vcp.SetDeviceVolume(connection=self.connection, volume=volume_value)

        return "OK"

    @assert_description
    def MMI_TMAP_SEND_ORIGINATE_CALL(self, **kwargs):
        """
        Click OK will send CCP Originate Call.
        """
        return "OK"

    @assert_description
    def MMI_TMAP_ORDER_IUT_TERMINATE_CALL(self, **kwargs):
        """
        Please order IUT to hang up the call
        """
        self.hfp.DeclineCall()
        return "OK"
