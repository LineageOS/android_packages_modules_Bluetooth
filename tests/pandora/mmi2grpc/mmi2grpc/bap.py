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
from pandora.host_pb2 import PUBLIC, RANDOM, DataTypes
from pandora.security_grpc import Security, SecurityStorage
from pandora.security_pb2 import LE_LEVEL3, PairingEventAnswer
from pandora.gatt_grpc import GATT
from pandora.le_audio_pb2 import LeAudioPlaybackAudioRequest, AUDIO_SOURCE_VOICE_COMMUNICATION, \
    AUDIO_USAGE_NOTIFICATION_RINGTONE, GROUP_STREAM_STATUS_STREAMING
from pandora.le_audio_grpc import LeAudio
import time

ASCS_UUID16 = "184E"
AUDIO_SIGNAL_AMPLITUDE = 0.8
AUDIO_SIGNAL_SAMPLING_RATE = 44100


class BAPProxy(ProfileProxy):

    def __init__(self, channel, rootcanal):
        super().__init__(channel)
        self.advertise = None
        self.security_storage = SecurityStorage(channel)
        self.rootcanal = rootcanal
        self.gatt = GATT(channel)
        self.host = Host(channel)
        self.le_audio = LeAudio(channel)
        self.security = Security(channel)
        self.pairing_events = self.security.OnPairing()
        self.discovered_services = None
        self.connection = None

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

        raise AssertionError(
            f"Passkey {passkey} not matched with any pairing event for {pts_addr}.")

    @match_description
    def _mmi_20106(self, test: str, characteristic_name: str, type: str, **kwargs):
        """
        Please write to Client Characteristic Configuration Descriptor
        of (?P<characteristic_name>(ASE Control Point|Sink Audio Stream Endpoint|Active Preset Index|Broadcast Receive State|Sink Audio Locations))
        characteristic to enable (?P<type>(notification|indication)).
        """

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
    def _mmi_20103(self, **kwargs):
        """
        Please take action to discover the (Sink PAC|Sink Audio Locations|Source PAC|
        |Source Audio Locations|Available Audio Contexts|Supported Audio Contexts|
        |Sink Audio Stream Endpoint|Source Audio Stream Endpoint|ASE Control Point|
        |Broadcast Audio Scan Control Point|Broadcast Receive State) characteristic from the
        (Published Audio Capability|Audio Stream Control|Broadcast Audio Scan).
        Discover the primary service if needed.
        Description: Verify that the Implementation Under Test \(IUT\) can send
        Discover All Characteristics command.
        """
        self.gatt.DiscoverServices(connection=self.connection, force=True)
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
    def _mmi_20145(self, **kwargs):
        """
        Please click Yes if IUT support Write Request, otherwise click No.
        """

        return "OK"

    @match_description
    def _mmi_20110(self, handle: str, **kwargs):
        """
        Please send write request to handle 0x(?P<handle>[0-9A-Fa-f]{4}) with following value.
        Any
        attribute value
        """

        self.gatt.WriteAttFromHandle(connection=self.connection,
                                     handle=int(handle, 16),
                                     value=bytes(0x01))

        return "OK"

    @match_description
    def _mmi_20206(self, body: str, **kwargs):
        """
        Please verify that for each supported characteristic, attribute
        handle/UUID pair\(s\) is returned to the (.*)\.(?P<body>.*)
        """

        return "OK"

    @assert_description
    def MMI_IUT_CONFIRM_ADV(self, **kwargs):
        """
        Please scan for Advertising Packets and Press OK to confirm receiving
        the ASCS UUID and Available Audio Contexts.
        """

        scan_stream = self.host.Scan()
        timeout_seconds = 10
        start_time = time.time()

        try:
            self.log(f"Scanning for advertisement with ASCS UUID ({ASCS_UUID16})...")
            for report in scan_stream:
                if time.time() - start_time > timeout_seconds:
                    raise AssertionError(
                        f"Failed to find advertisement with UUID 0x{ASCS_UUID16} within {timeout_seconds}s."
                    )

                ad_data = report.data
                self.log(f"Received advertising report. Data: {ad_data}")
                uuid_is_advertised = (ASCS_UUID16 in ad_data.complete_service_class_uuids16 or
                                      ASCS_UUID16 in ad_data.incomplete_service_class_uuids16 or
                                      ASCS_UUID16 in ad_data.service_data_uuid16)

                contexts_are_present = (ASCS_UUID16 in ad_data.service_data_uuid16 and
                                        ad_data.service_data_uuid16[ASCS_UUID16])
                self.log(f"Service data: {ad_data.service_data_uuid16}")
                if uuid_is_advertised and contexts_are_present:
                    self.log(
                        "Success: Found advertisement with ASCS UUID and Available Audio Contexts.")
                    return "OK"

        finally:
            scan_stream.cancel()

    @assert_description
    def _mmi_20144(self, **kwargs):
        """
        Please click Yes if IUT support Write Command(without response),
        otherwise click No.
        """

        return "OK"

    @match_description
    def _mmi_20121(self, handle: str, **kwargs):
        """
        Please write value with write command \(without response\) to handle
        0x(?P<handle>[0-9A-Fa-f]{4}) with following value. Any attribute value
        """

        self.gatt.WriteAttFromHandleWithoutResponse(connection=self.connection,
                                                    handle=int(handle, 16),
                                                    value=bytes(0x01))
        return "OK"

    @assert_description
    def _mmi_20115(self, **kwargs):
        """
        Please initiate a GATT disconnection to the PTS.

        Description: Verify
        that the Implementation Under Test (IUT) can initiate GATT disconnect
        request to PTS.
        """

        assert self.connection is not None
        self.host.Disconnect(connection=self.connection)
        self.connection = None

        return "OK"

    @assert_description
    def MMI_IUT_CONFIRM_BASE(self, **kwargs):
        """
        Please confirm received BASE entry Basic Audio Announcements:
        Length: [39 (0x27)]
            AD Type: [22 (0x16)]
            Basic Audio
        Announcement Service UUID: [6225 (0x1851)] Service UUID
            Presentation
        Delay: [40000 (0x009C40)]
            Num Subgroups: [1 (0x01)]
            Codec And
        Metadata Subgroups: {
            Num BIS: [1 (0x01)]
            Codec And Metadata
        Lv2:
                Codec Configuration:
                    Codec ID: [6 (0x06)]
        Codec ID Company ID: [0 (0x0000)]
                    Codec ID Vendor ID: [0
        (0x0000)]
                    Codec Specific Configuration Length: [10 (0x0A)]
        Codec Specific Configuration LTV:
                        LTV Wrapper: {
        Length: [2 (0x02)]
            Type and Value:
                Type: [1 (0x01)]
        Value: [0x03],
            Length: [2 (0x02)]
            Type and Value:
        Type: [2 (0x02)]
                Value: [0x01],
            Length: [3 (0x03)]
            Type
        and Value:
                Type: [4 (0x04)]
                Value: [0x28, 0x00]}
        Metadata Length: [6 (0x06)]
                Metadata:
                    LTV Wrapper:
        {
            Length: [3 (0x03)]
            Type and Value:
                Type: [2 (0x02)]
        Value: [0x04, 0x00],
            Length: [1 (0x01)]
            Type and Value:
        Type: [9 (0x09)]}
            BIS Codec Subgroup Lv3: {
            BIS index: [1
        (0x01)]
            Codec Specific Configuration Length: [6 (0x06)]
            Codec
        Specific Configuration LTV:
                Length: [5 (0x05)]
                Type and
        Value:
                    Type: [3 (0x03)]
                    Value: [0x01, 0x00,
        0x00, 0x00]}}
        """

        return "OK"

    @assert_description
    def MMI_IUT_READY_TO_RECEIVE_NOTIFICATION(self, **kwargs):
        """
        When IUT is ready to receive notification. Please click OK.
        """

        return "OK"

    @match_description
    def MMI_BAP_CONFIGURE_ASE_TO_QOS_CONFIGURED_STATE_LC3_SETTING(self, lc3_config: str,
                                                                  ase_role: str, **kwargs):
        """
        Please configure ASE state to QoS Configured with (?P<lc3_config>\d+(?:_\d+)*) in (?P<ase_role>SINK|SOURCE)
        direction.
        """

        def start_capture():
            self.capture_stream = self.le_audio.LeAudioCaptureAudio(connection=self.connection)

        self.log(f"ASE role: {ase_role}")
        self.le_audio.Open(connection=self.connection)
        if "SINK" in ase_role:
            self.le_audio.LeAudioStart(connection=self.connection)
            self.audio.start()
        elif "SOURCE" in ase_role:
            capture_thread = threading.Thread(target=start_capture)
            self.le_audio.LeAudioPrepareRecorder(connection=self.connection,
                                                 audioSource=AUDIO_SOURCE_VOICE_COMMUNICATION)
            capture_thread.start()
        else:
            raise AssertionError(f"Invalid ASE role: {ase_role}.")

        return "OK"

    @match_description
    def MMI_BAP_CONFIGURE_ASE_TO_CODEC_CONFIGURED_STATE_CODEC_PARAM(self, ase_role: str,
                                                                    frequency: int,
                                                                    frame_duration: int, **kwargs):
        """
        Please configure ASE state to CODEC configured with (?P<ase_role>SINK|SOURCE) ASE, Freq: (?P<frequency>\d+(?:\.\d+)?)
        KHz, Frame Duration: (?P<frame_duration>\d+(?:\.\d+)?) ms
        """

        def start_capture():
            self.capture_stream = self.le_audio.LeAudioCaptureAudio(connection=self.connection)

        self.log(f"ASE role: {ase_role}")
        self.le_audio.Open(connection=self.connection)
        if "SINK" in ase_role:
            self.le_audio.LeAudioStart(connection=self.connection)
            self.audio.start()
        elif "SOURCE" in ase_role:
            capture_thread = threading.Thread(target=start_capture)
            self.le_audio.LeAudioPrepareRecorder(connection=self.connection,
                                                 audioSource=AUDIO_SOURCE_VOICE_COMMUNICATION)
            capture_thread.start()
        else:
            raise AssertionError(f"Invalid ASE role: {ase_role}.")
        return "OK"

    @match_description
    def MMI_BAP_IUT_WRITE_CONFIG_CODEC_STREAMING_STATE(self, num_ases: int, ase_role: str,
                                                       **kwargs):
        """
        Please configure (?P<num_ases>\d) (?P<ase_role>SINK|SOURCE) ASE with Config Setting: IXIT.
        After that,
        configure to streaming state.
        """

        def start_capture():
            self.capture_stream = self.le_audio.LeAudioCaptureAudio(connection=self.connection)

        self.log(f"ASE role: {ase_role}")
        self.le_audio.Open(connection=self.connection)
        if "SINK" in ase_role:
            self.le_audio.LeAudioStart(connection=self.connection)
            self.audio.start()
        elif "SOURCE" in ase_role:
            capture_thread = threading.Thread(target=start_capture)
            self.le_audio.LeAudioPrepareRecorder(connection=self.connection,
                                                 audioSource=AUDIO_SOURCE_VOICE_COMMUNICATION)
            capture_thread.start()
        else:
            raise AssertionError(f"Invalid ASE role: {ase_role}.")
        return "OK"

    @match_description
    def MMI_BAP_IUT_WRITE_CONFIG_CODEC_BIDIRECTION_STREAMING_STATE(self, test: str,
                                                                   num_sink_ases: int,
                                                                   num_source_ases: int, **kwargs):
        """
        Please configure (?P<num_sink_ases>\d) SINK and (?P<num_source_ases>\d) SOURCE ASE with Config Setting: IXIT.
        After that, configure both ASEes to streaming state.
        """

        def start_playback():
            self.audio.start()

        def start_capture():
            self.capture_stream = self.le_audio.LeAudioCaptureAudio(connection=self.connection)

        capture_thread = threading.Thread(target=start_capture)
        playback_thread = threading.Thread(target=start_playback)
        self.le_audio.Open(connection=self.connection)
        self.le_audio.LeAudioStart(connection=self.connection,
                                   audioUsage=AUDIO_USAGE_NOTIFICATION_RINGTONE,
                                   metadataTag="VX_AOSP_bidirectional")
        playback_thread.start()
        playback_thread.join()

        if "BAP/UCL/STR/BV-543-C" in test:
            self.log("Waiting for the group to enter the streaming state.")
            self.le_audio.LeAudioWaitGroupStreamStatusChanged(
                connection=self.connection, groupStreamStatus=GROUP_STREAM_STATUS_STREAMING)
            self.log("The group is in the streaming state.")

        self.le_audio.LeAudioPrepareRecorder(connection=self.connection,
                                             audioSource=AUDIO_SOURCE_VOICE_COMMUNICATION)
        capture_thread.start()
        capture_thread.join()

        return "OK"

    @assert_description
    def MMI_IUT_VERIFY_AUDIO_STREAM_DATA(self, **kwargs):
        """
        After processed audio stream data, please click OK.
        """

        return "OK"
