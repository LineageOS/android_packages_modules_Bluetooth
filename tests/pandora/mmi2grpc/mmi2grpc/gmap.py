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

from mmi2grpc._audio import AudioSignal
from mmi2grpc._helpers import assert_description, match_description
from mmi2grpc._proxy import ProfileProxy
from mmi2grpc._rootcanal import Dongle
from pandora.host_grpc import Host
from pandora.host_pb2 import PUBLIC, RANDOM
from pandora.security_grpc import Security, SecurityStorage
from pandora.security_pb2 import LE_LEVEL3, PairingEventAnswer
from pandora.gatt_grpc import GATT
from pandora.le_audio_pb2 import LeAudioPlaybackAudioRequest, AUDIO_USAGE_GAME, AUDIO_SOURCE_VOICE_PERFORMANCE
from pandora.le_audio_grpc import LeAudio

AUDIO_SIGNAL_AMPLITUDE = 0.8
AUDIO_SIGNAL_SAMPLING_RATE = 44100


class GMAPProxy(ProfileProxy):

    def __init__(self, channel, rootcanal):
        super().__init__(channel)
        self.security_storage = SecurityStorage(channel)
        self.rootcanal = rootcanal
        self.gatt = GATT(channel)
        self.host = Host(channel)
        self.le_audio = LeAudio(channel)
        self.security = Security(channel)
        self.pairing_events = self.security.OnPairing()
        self.discovered_services = None
        self.connection = None
        self.is_recording_active = False

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

    @match_description
    def _mmi_20106(self, test: str, characteristic_name: str, type: str, **kwargs):
        """
        Please write to Client Characteristic Configuration Descriptor
        of (?P<characteristic_name>(ASE Control Point|Sink Audio Stream Endpoint|Active Preset Index))
        characteristic to enable (?P<type>(notification|indication)).
        """

        return "OK"

    @match_description
    def _mmi_311(self, num_ases: int, ase_role: str, **kwargs):
        """
        Please configure (?P<num_ases>\d) (?P<ase_role>SINK|SOURCE) ASE[s]? with Config Setting: .
        After that, configure
        to streaming state.
        """

        def start_capture():
            self.capture_stream = self.le_audio.LeAudioCaptureAudio(connection=self.connection)
            self.is_recording_active = True

        self.log(f"ASE role: {ase_role}")
        self.le_audio.Open(connection=self.connection)
        if "SINK" in ase_role:
            self.le_audio.LeAudioStart(connection=self.connection, audioUsage=AUDIO_USAGE_GAME)
            self.audio.start()
        elif "SOURCE" in ase_role:
            capture_thread = threading.Thread(target=start_capture)
            self.le_audio.LeAudioPrepareRecorder(connection=self.connection,
                                                 audioSource=AUDIO_SOURCE_VOICE_PERFORMANCE)
            capture_thread.start()
        else:
            assert False

        return "OK"

    @match_description
    def _mmi_313(self, num_sink_ases: int, num_source_ases: int, **kwargs):
        """
        Please configure (?P<num_sink_ases>\d) SINK and (?P<num_source_ases>\d) SOURCE ASE with Config Setting: .
        After
        that, configure both ASEes to streaming state.
        """

        def start_playback():
            self.audio.start()

        def start_capture():
            self.capture_stream = self.le_audio.LeAudioCaptureAudio(connection=self.connection)
            self.is_recording_active = True

        capture_thread = threading.Thread(target=start_capture)
        playback_thread = threading.Thread(target=start_playback)
        self.le_audio.Open(connection=self.connection)
        self.le_audio.LeAudioPrepareRecorder(connection=self.connection,
                                             audioSource=AUDIO_SOURCE_VOICE_PERFORMANCE)
        self.le_audio.LeAudioStart(connection=self.connection,
                                   audioUsage=AUDIO_USAGE_GAME,
                                   metadataTag="VX_AOSP_bidirectional")

        capture_thread.start()
        playback_thread.start()
        playback_thread.join()
        capture_thread.join()

        return "OK"

    @assert_description
    def _mmi_368(self, **kwargs):
        """
        After processed audio stream data, please release the audio stream
        """
        if self.audio.thread is not None:
            self.audio.wait_complete()
            self.le_audio.LeAudioStop(connection=self.connection)

        if self.is_recording_active:
            self.le_audio.LeAudioStopRecorder(connection=self.connection)

        return "OK"

    @assert_description
    def _mmi_20001(self, **kwargs):
        """
        Please prepare IUT into a connectable mode.

        Description: Verify that
        the Implementation Under Test (IUT) can accept GATT connect request from
        PTS.
        """
        self.advertise = self.host.Advertise(
            legacy=True,
            connectable=True,
            own_address_type=PUBLIC,
        )
        return "OK"

    @match_description
    def _mmi_20206(self, gmap_role_att_handle: str, gmap_role_handle: str,
                   ugg_features_att_handle: str, ugg_features_handle: str,
                   ugt_features_att_handle: str, ugt_features_handle: str,
                   bgs_features_att_handle: str, bgs_features_handle: str,
                   bgr_features_att_handle: str, bgr_features_handle: str, **kwargs):
        """
        Please verify that for each supported characteristic, attribute
        handle\/UUID pair\(s\) is returned to the upper tester.GMAP Role: Attribute
        Handle = (?P<gmap_role_att_handle>\S*)
        Characteristic Properties = 0x02
        Handle = (?P<gmap_role_handle>\S*)
        UUID = 0x2C00

        UGG Features: Attribute Handle = (?P<ugg_features_att_handle>\S*)
        Characteristic
        Properties = 0x02
        Handle = (?P<ugg_features_handle>\S*)
        UUID = 0x2C01

        UGT Features: Attribute
        Handle = (?P<ugt_features_att_handle>\S*)
        Characteristic Properties = 0x02
        Handle = (?P<ugt_features_handle>\S*)
        UUID = 0x2C02

        BGS Features: Attribute Handle = (?P<bgs_features_att_handle>\S*)
        Characteristic
        Properties = 0x02
        Handle = (?P<bgs_features_handle>\S*)
        UUID = 0x2C03

        BGR Features: Attribute
        Handle = (?P<bgr_features_att_handle>\S*)
        Characteristic Properties = 0x02
        Handle = (?P<bgr_features_handle>\S*)
        UUID = 0x2C04
        """

        return "OK"
