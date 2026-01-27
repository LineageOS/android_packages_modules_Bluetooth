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
"""A2DP proxy module."""

import time
import threading
from typing import Optional

from grpc import RpcError
from mmi2grpc._audio import AudioSignal
from mmi2grpc._helpers import assert_description, match_description
from mmi2grpc._proxy import ProfileProxy
from mmi2grpc._rootcanal import Dongle
from pandora.a2dp_grpc import A2DP
from pandora.a2dp_pb2 import MONO, PlaybackAudioRequest, Sink, Source, STEREO
from pandora.host_grpc import Host
from pandora.host_pb2 import Connection

AUDIO_SIGNAL_AMPLITUDE = 0.8
AUDIO_SIGNAL_SAMPLING_RATE = 44100


class A2DPProxy(ProfileProxy):
    """A2DP proxy.

    Implements A2DP and AVDTP PTS MMIs.
    """

    connection: Optional[Connection] = None
    sink: Optional[Sink] = None
    source: Optional[Source] = None

    def __init__(self, channel, rootcanal):
        super().__init__(channel)

        self.host = Host(channel)
        self.a2dp = A2DP(channel)
        self.rootcanal = rootcanal

        def convert_frame(data):
            return PlaybackAudioRequest(data=data, source=self.source)

        self.audio = AudioSignal(lambda frames: self.a2dp.PlaybackAudio(map(convert_frame, frames)),
                                 AUDIO_SIGNAL_AMPLITUDE, AUDIO_SIGNAL_SAMPLING_RATE)

        print("Selecting the INTEL_BE200 dongle")
        self.rootcanal.select_pts_dongle(Dongle.INTEL_BE200)

    @assert_description
    def TSC_AVDTP_mmi_iut_accept_connect(self, test: str, pts_addr: bytes, **kwargs):
        """
        If necessary, take action to accept the AVDTP Signaling Channel
        Connection initiated by the tester.

        Description: Make sure the IUT
        (Implementation Under Test) is in a state to accept incoming Bluetooth
        connections.  Some devices may need to be on a specific screen, like a
        Bluetooth settings screen, in order to pair with PTS.  If the IUT is
        still having problems pairing with PTS, try running a test case where
        the IUT connects to PTS to establish pairing.
        """

        # The PTS tool is not always initiating the connection before the
        # interaction is started. To work around this we need to make the MMI
        # asynchronous.

        def wait_source():
            self.connection = self.connection or self.host.WaitConnection(
                address=pts_addr).connection
            self.source = self.source or self.a2dp.WaitSource(connection=self.connection).source

        if test in [
                "A2DP/SRC/SET/BV-05-C",
        ]:
            # This MMI must be synchronous for the above listed tests.
            wait_source()

        elif test in [
                "A2DP/SRC/CC/BV-09-C",
                "A2DP/SRC/CC/BV-10-C",
                "A2DP/SRC/SET/BV-02-C",
                "A2DP/SRC/SET/BV-04-C",
                "A2DP/SRC/SET/BV-06-C",
                "AVDTP/SRC/ACP/SIG/SMG/BI-14-C",
                "AVDTP/SRC/ACP/SIG/SMG/BI-23-C",
                "AVDTP/SRC/ACP/SIG/SMG/BI-26-C",
                "AVDTP/SRC/ACP/SIG/SMG/BV-16-C",
                "AVDTP/SRC/ACP/SIG/SMG/BV-18-C",
                "AVDTP/SRC/ACP/SIG/SMG/BV-20-C",
                "AVDTP/SRC/ACP/SIG/SMG/BV-22-C",
                "AVDTP/SRC/ACP/SIG/SYN/BV-06-C",
        ]:
            # This MMI must be asynchronous for the above listed tests.
            threading.Thread(target=wait_source).start()

        elif "A2DP/SRC/AVP" in test or "A2DP/SNK/AVP" in test:
            # WaitSource is blocking and cannot be invoked in these tests
            # because Android will initiate the AVDTP connection after a
            # timeout if the remote device is inactive.
            self.connection = self.host.WaitConnection(address=pts_addr).connection

        elif "SRC" in test:
            self.connection = self.host.WaitConnection(address=pts_addr).connection
            try:
                if "INT" in test:
                    self.source = self.a2dp.OpenSource(connection=self.connection).source
                else:
                    self.source = self.a2dp.WaitSource(connection=self.connection).source
            except RpcError:
                pass

        else:
            self.connection = self.connection or self.host.WaitConnection(
                address=pts_addr).connection
            try:
                self.sink = self.a2dp.WaitSink(connection=self.connection).sink
            except RpcError:
                pass

        return "OK"

    @assert_description
    def TSC_AVDTP_mmi_iut_accept_disconnect(self, **kwargs):
        """
        If necessary, take action to accept the AVDTP Signaling Channnel
        Disconnection initiated by the tester.

        Note: If an AVCTP signaling
        channel was established it will also be disconnected.
        """

        return "OK"

    @assert_description
    def TSC_AVDTP_mmi_iut_initiate_discover(self, **kwargs):
        """
        Send a discover command to PTS.

        Action: If the IUT (Implementation
        Under Test) is already connected to PTS, attempting to send or receive
        streaming media should trigger this action.  If the IUT is not connected
        to PTS, attempting to connect may trigger this action.
        """

        return "OK"

    @assert_description
    def TSC_AVDTP_mmi_iut_initiate_start(self, test: str, **kwargs):
        """
        Send a start command to PTS.

        Action: If the IUT (Implementation Under
        Test) is already connected to PTS, attempting to send or receive
        streaming media should trigger this action.  If the IUT is not connected
        to PTS, attempting to connect may trigger this action.
        """

        if test in [
                "A2DP/SNK/SYN/BV-01-C",
        ]:
            # Ignore the PTS start request for the tests listed above,
            # initiating the stream is not in the TSPC requirements.
            pass

        elif "SRC" in test:
            self.a2dp.Start(source=self.source)
            self.audio.start()
        else:
            self.a2dp.Start(sink=self.sink)

        return "OK"

    @assert_description
    def TSC_AVDTP_mmi_iut_initiate_suspend(self, test: str, **kwargs):
        """
        Suspend the streaming channel.
        """

        if "SRC" in test:
            self.a2dp.Suspend(source=self.source)
        else:
            self.a2dp.Suspend(sink=self.sink)
        return "OK"

    @assert_description
    def TSC_AVDTP_mmi_iut_initiate_close_stream(self, test: str, **kwargs):
        """
        Close the streaming channel.

        Action: Disconnect the streaming channel,
        or close the Bluetooth connection to the PTS.
        """

        if "SRC" in test:
            self.a2dp.Close(source=self.source)
            self.source = None
        else:
            self.a2dp.Close(sink=self.sink)
            self.sink = None
        return "OK"

    @assert_description
    def TSC_AVDTP_mmi_iut_initiate_out_of_range(self, pts_addr: bytes, **kwargs):
        """
        Move the IUT out of range to create a link loss scenario.

        Action: This
        can be also be done by placing the IUT or PTS in an RF shielded box.
         """

        assert self.connection, "Connection is not established"

        self.rootcanal.move_out_of_range()
        self.host.WaitDisconnection(connection=self.connection)

        self.connection = None
        self.source = None
        self.sink = None

        self.rootcanal.move_in_range()

        return "OK"

    @assert_description
    def TSC_AVDTP_mmi_iut_begin_streaming(self, test: str, **kwargs):
        """
        Begin streaming media ...

        Note: If the IUT has suspended the stream
        please restart the stream to begin streaming media.
        """

        if test == "AVDTP/SRC/ACP/SIG/SMG/BI-29-C":
            time.sleep(2)  # TODO: Remove, AVRCP SegFault
        if test in ("A2DP/SRC/CC/BV-09-I", "A2DP/SRC/SET/BV-04-I", "AVDTP/SRC/ACP/SIG/SMG/BV-18-C",
                    "AVDTP/SRC/ACP/SIG/SMG/BV-20-C", "AVDTP/SRC/ACP/SIG/SMG/BV-22-C"):
            time.sleep(1)  # TODO: Remove, AVRCP SegFault
        if test == "A2DP/SRC/SUS/BV-01-I":
            # Stream is not suspended when we receive the interaction
            time.sleep(1)
        if test != "A2DP/SRC/SET/BV-03-I":  # Not initiating a2dp start again for this test case
            self.a2dp.Start(source=self.source)

        self.audio.start()
        return "OK"

    @assert_description
    def TSC_AVDTP_mmi_iut_initiate_media(self, **kwargs):
        """
        Take action if necessary to start streaming media to the tester.
        """

        self.audio.start()
        return "OK"

    @assert_description
    def TSC_AVDTP_mmi_iut_initiate_stream_media(self, **kwargs):
        """
        Stream media to PTS.  If the IUT is a SNK, wait for PTS to start
        streaming media.

        Action: If the IUT (Implementation Under Test) is
        already connected to PTS, attempting to send or receive streaming media
        should trigger this action.  If the IUT is not connected to PTS,
        attempting to connect may trigger this action.
        """

        self.audio.start()
        return "OK"

    @assert_description
    def TSC_AVDTP_mmi_user_verify_media_playback(self, **kwargs):
        """
        Is the test system properly playing back the media being sent by the
        IUT?
        """

        # TODO(302136232): audio validation is disabled on cuttlefish (too laggy)
        #result = self.audio.verify()
        #assert result

        return "Yes"

    @assert_description
    def TSC_AVDTP_mmi_iut_initiate_get_capabilities(self, **kwargs):
        """
        Send a get capabilities command to PTS.

        Action: If the IUT
        (Implementation Under Test) is already connected to PTS, attempting to
        send or receive streaming media should trigger this action.  If the IUT
        is not connected to PTS, attempting to connect may trigger this action.
        """

        # This will be done as part as the a2dp.OpenSource or a2dp.WaitSource
        return "OK"

    @assert_description
    def TSC_AVDTP_mmi_iut_accept_discover(self, **kwargs):
        """
        If necessary, take action to accept the AVDTP Discover operation
        initiated by the tester.
        """

        return "OK"

    @assert_description
    def TSC_AVDTP_mmi_iut_initiate_set_configuration(self, **kwargs):
        """
        Send a set configuration command to PTS.

        Action: If the IUT
        (Implementation Under Test) is already connected to PTS, attempting to
        send or receive streaming media should trigger this action.  If the IUT
        is not connected to PTS, attempting to connect may trigger this action.
        """

        return "OK"

    @assert_description
    def TSC_AVDTP_mmi_iut_accept_close_stream(self, **kwargs):
        """
        If necessary, take action to accept the AVDTP Close operation initiated
        by the tester.
        """

        return "OK"

    @assert_description
    def TSC_AVDTP_mmi_iut_accept_abort(self, **kwargs):
        """
        If necessary, take action to accept the AVDTP Abort operation initiated
        by the tester..
        """

        return "OK"

    @assert_description
    def TSC_AVDTP_mmi_iut_accept_get_all_capabilities(self, **kwargs):
        """
        If necessary, take action to accept the AVDTP Get All Capabilities
        operation initiated by the tester.
        """

        return "OK"

    @assert_description
    def TSC_AVDTP_mmi_iut_accept_get_capabilities(self, **kwargs):
        """
        If necessary, take action to accept the AVDTP Get Capabilities operation
        initiated by the tester.
        """

        return "OK"

    @assert_description
    def TSC_AVDTP_mmi_iut_accept_set_configuration(self, **kwargs):
        """
        If necessary, take action to accept the AVDTP Set Configuration
        operation initiated by the tester.
        """

        return "OK"

    @assert_description
    def TSC_AVDTP_mmi_iut_accept_get_configuration(self, **kwargs):
        """
        Take action to accept the AVDTP Get Configuration command from the
        tester.
        """

        return "OK"

    @assert_description
    def TSC_AVDTP_mmi_iut_accept_open_stream(self, **kwargs):
        """
        If necessary, take action to accept the AVDTP Open operation initiated
        by the tester.
        """

        return "OK"

    @assert_description
    def TSC_AVDTP_mmi_iut_accept_start(self, **kwargs):
        """
        If necessary, take action to accept the AVDTP Start operation initiated
        by the tester.
        """

        return "OK"

    @assert_description
    def TSC_AVDTP_mmi_iut_accept_suspend(self, **kwargs):
        """
        If necessary, take action to accept the AVDTP Suspend operation
        initiated by the tester.
        """

        return "OK"

    @assert_description
    def TSC_AVDTP_mmi_iut_accept_reconfigure(self, **kwargs):
        """
        If necessary, take action to accept the AVDTP Reconfigure operation
        initiated by the tester.
        """

        return "OK"

    @assert_description
    def TSC_AVDTP_mmi_iut_accept_media_transports(self, **kwargs):
        """
        Take action to accept transport channels for the recently configured
        media stream.
        """

        return "OK"

    @assert_description
    def TSC_AVDTP_mmi_iut_confirm_streaming(self, **kwargs):
        """
        Is the IUT (Implementation Under Test) receiving streaming media from
        PTS?

        Action: Press 'Yes' if the IUT is receiving streaming data from
        the PTS (in some cases the sound may not be clear, this is normal).
        """

        # TODO: verify
        return "OK"

    @assert_description
    def TSC_AVDTP_mmi_iut_initiate_open_stream(self, **kwargs):
        """
        Open a streaming media channel.

        Action: If the IUT (Implementation
        Under Test) is already connected to PTS, attempting to send or receive
        streaming media should trigger this action.  If the IUT is not connected
        to PTS, attempting to connect may trigger this action.
        """

        return "OK"

    @assert_description
    def TSC_AVDTP_mmi_iut_accept_reconnect(self, pts_addr: bytes, **kwargs):
        """
        Press OK when the IUT (Implementation Under Test) is ready to allow the
        PTS to reconnect the AVDTP signaling channel.

        Action: Press OK when the
        IUT is ready to accept Bluetooth connections again.
        """

        # The PTS tool is not always initiating the connection before the
        # interaction is started. To work around this we need to make the MMI
        # asynchronous.

        def wait_connection():
            self.connection = self.host.WaitConnection(address=pts_addr).connection

        threading.Thread(target=wait_connection).start()

        return "OK"

    @assert_description
    def TSC_AVDTP_mmi_iut_initiate_get_all_capabilities(self, **kwargs):
        """
        Send a GET ALL CAPABILITIES command to PTS.

        Action: If the IUT
        (Implementation Under Test) is already connected to PTS, attempting to
        send or receive streaming media should trigger this action.  If the IUT
        is not connected to PTS, attempting to connect may trigger this action.
        """

        return "OK"

    @assert_description
    def TSC_AVDTP_mmi_tester_verifying_suspend(self, **kwargs):
        """
        Please wait while the tester verifies the IUT does not send media during
        suspend ...
        """

        return "Yes"

    @assert_description
    def TSC_A2DP_mmi_user_confirm_optional_data_attribute(self, **kwargs):
        """
        Tester found the optional SDP attribute named 'Supported Features'.
        Press 'Yes' if the data displayed below is correct.

        Value: 0x0001
        """

        # TODO: Extract and verify attribute name and value from description
        return "OK"

    @match_description
    def TSC_A2DP_mmi_user_confirm_optional_string_attribute(self, name: str, test: str, **kwargs):
        """
        Tester found the optional SDP attribute named 'Service Name'.  Press
        'Yes' if the string displayed below is correct.

        Value: (?P<name>[\w\s]+)
        """

        if "SRC" in test:
            assert name == "Advanced Audio Source", name
        else:
            assert name == "Advanced Audio Sink", name

        return "OK"

    @match_description
    def TSC_A2DP_mmi_user_confirm_no_optional_attribute_support(self, **kwargs):
        """
        (Tester could not find the optional SDP attribute named 'Provider Name'. Is this correct?|Take
         action if necessary to initiate a Delay Reporting command.)
        """

        return "OK"

    @assert_description
    def TSC_AVDTPEX_mmi_iut_accept_delayreport(self, **kwargs):
        """
        Take action if necessary to accept the Delay Reportl command from the
        tester.
        """

        return "OK"

    @assert_description
    def TSC_AVDTPEX_mmi_iut_initiate_media_transport_connect(self, **kwargs):
        """
        Take action to initiate an AVDTP media transport.
        """

        return "OK"

    @assert_description
    def TSC_AVDTPEX_mmi_user_confirm_SIG_SMG_BV_28_C(self, **kwargs):
        """
        Were all the service capabilities reported to the upper tester valid?
        """

        # TODO: verify
        return "Yes"

    @assert_description
    def TSC_AVDTPEX_mmi_iut_reject_invalid_command(self, **kwargs):
        """
        Take action to reject the invalid command sent by the tester.
        """

        return "OK"

    @assert_description
    def TSC_AVDTPEX_mmi_iut_reject_open(self, **kwargs):
        """
        Take action to reject the invalid OPEN command sent by the tester.
        """

        return "OK"

    @assert_description
    def TSC_AVDTPEX_mmi_iut_reject_start(self, **kwargs):
        """
        Take action to reject the invalid START command sent by the tester.
        """

        return "OK"

    @assert_description
    def TSC_AVDTPEX_mmi_iut_reject_suspend(self, **kwargs):
        """
        Take action to reject the invalid SUSPEND command sent by the tester.
        """

        return "OK"

    @assert_description
    def TSC_AVDTPEX_mmi_iut_reject_reconfigure(self, **kwargs):
        """
        Take action to reject the invalid or incompatible RECONFIGURE command
        sent by the tester.
        """

        return "OK"

    @assert_description
    def TSC_AVDTPEX_mmi_iut_reject_get_all_capabilities(self, **kwargs):
        """
        Take action to reject the invalid GET ALL CAPABILITIES command with the
        error code BAD_LENGTH.
        """

        return "OK"

    @assert_description
    def TSC_AVDTPEX_mmi_iut_reject_get_capabilities(self, **kwargs):
        """
        Take action to reject the invalid GET CAPABILITIES command with the
        error code BAD_LENGTH.
        """

        return "OK"

    @assert_description
    def TSC_AVDTPEX_mmi_iut_reject_set_configuration(self, **kwargs):
        """
        Take action to reject the SET CONFIGURATION sent by the tester.  The IUT
        is expected to respond with SEP_IN_USE because the SEP requested was
        previously configured.
        """

        return "OK"

    def TSC_AVDTPEX_mmi_iut_reject_get_configuration(self, **kwargs):
        """
        Take action to reject the GET CONFIGURATION sent by the tester.  The IUT
        is expected to respond with BAD_ACP_SEID because the SEID requested was
        not previously configured.
        """

        return "OK"

    @assert_description
    def TSC_AVDTPEX_mmi_iut_reject_close(self, **kwargs):
        """
        Take action to reject the invalid CLOSE command sent by the tester.
        """

        return "OK"

    @assert_description
    def TSC_AVDTPEX_mmi_user_confirm_SIG_SMG_BV_18_C(self, **kwargs):
        """
        Did the IUT receive media with the following information?

        - V = RTP_Ver
        - P = 0 (no padding bits)
        - X = 0 (no extension)
        - CC = 0 (no
        contributing source)
        - M = 0
        """

        # TODO: verify
        return "OK"

    @assert_description
    def TSC_AVDTPEX_mmi_iut_initiate_delayreport(self, **kwargs):
        """
        Take action if necessary to initiate a Delay Reporting command.
        """

        return "OK"

    @assert_description
    def TSC_AVDTPEX_mmi_iut_initiate_set_configuration_delay_reporting(self, **kwargs):
        """
        Take action to configure a stream with Delay Reporting.
        """

        return "OK"

    @assert_description
    def TSC_AVDTPEX_mmi_iut_initiate_set_configuration_delayreport(self, **kwargs):
        """
        Take action to initiate a stream using delay reporting.

        Note: The IUT
        must send a Delay Report command immediately after configuration of the
        stream.
        """

        return "OK"

    @assert_description
    def TSC_AVDTP_mmi_iut_initiate_delayreport(self, **kwargs):
        """
        Take action if necessary to initiate a Delay Reporting command.
        """

        return "OK"

    @assert_description
    def TSC_A2DP_mmi_user_verify_delay_report_value(self, **kwargs):
        """
        Is the delay value 3000, within a device acceptable range?
        """
        # TODO: verify
        return "OK"

    @match_description
    def TSC_A2DP_mmi_iut_reject_set_configuration_error_code(self, **kwargs):
        """
        Please prepare the IUT to reject an AVDTP SET CONFIGURATION command with
        error code (?P<errorcode>[A-Z_]+), then press 'OK' to continue.
        """

        return "OK"

    @assert_description
    def TSC_AVDTP_mmi_iut_initiate_reconfigure(self, **kwargs):
        """
        Send a reconfigure command to PTS.
        """

        currentConfig = self.a2dp.GetConfiguration(connection=self.connection)
        if currentConfig.configuration.id.HasField('sbc'):
            currentConfig.configuration.parameters.channel_mode = (
                STEREO if currentConfig.configuration.parameters.channel_mode == MONO else MONO)
        elif currentConfig.configuration.id.HasField('mpeg_aac'):
            currentConfig.configuration.parameters.sampling_frequency_hz = (
                48000
                if currentConfig.configuration.parameters.sampling_frequency_hz == 44100 else 44100)
        else:
            raise Exception(f"Unsupported codec type {currentConfig.configuration.id}")

        self.a2dp.SetConfiguration(connection=self.connection,
                                   configuration=currentConfig.configuration)
        return "OK"

    @assert_description
    def _mmi_33(self, test: str, pts_addr: bytes, **kwargs):
        """
        Please make IUT general discoverable.
        """

        return "OK"

    @assert_description
    def TSC_A2DP_mmi_wait_delay_reporting(self, test: str, **kwargs):
        """
        Please wait while the tester periodically adjusts the delay reporting
        during the stream ...

        Note: This will take approximately 30 seconds.
        """

        if test in [
                "A2DP/SRC/SYN/BV-02-C",
        ]:
            # The PTS expects the host stack to start the stream for the above
            # listed tests.
            self.a2dp.Start(source=self.source)
            self.audio.start(duration_s=60.)

        return "OK"

    @assert_description
    def TSC_A2DP_mmi_sbc_playback_is_correct(self, **kwargs):
        """
        Take action if necessary to accept the Delay Reportl command from the
        tester.
        """

        return "OK"

    @assert_description
    def _mmi_20000(self, **kwargs):
        """
        Please prepare IUT into a connectable mode in BR/EDR.

        Description:
        Verify that the Implementation Under Test (IUT) can accept GATT connect
        request from PTS.
        """

        # TODO This MMI has an inconsistent name and message?
        return "OK"

    @assert_description
    def TSC_A2DP_mmi_user_delete_link_key(self, **kwargs):
        """
        Delete the link key with PTS on the Implementation Under Test (IUT), and
        then click OK to continue.

        Description: For end product, this can be
        achieved by forgetting PTS from the IUT.
        """

        return "OK"

    @assert_description
    def TSC_A2DP_mmi_iopt_iut_able_to_pair(self, **kwargs):
        """
        Is the IUT capable of establishing connection to an unpaired device?
        """

        return "OK"

    @assert_description
    def _mmi_102(self, pts_addr: bytes, **kwargs):
        """
        Please send an HCI connect request to establish a basic rate connection
        after the IUT discovers the Lower Tester over BR and LE.
        """

        self.connection = self.host.Connect(address=pts_addr).connection
        return "OK"

    @assert_description
    def TSC_AVDTPEX_mmi_iut_initiate_open_with_reporting(self, **kwargs):
        """
        Action: Place the IUT in connectable mode.

        Description : PTS requires
        that the IUT be in connectable mode.The PTS will attempt to establish an
        ACL connection.
        """

        return "OK"

    @assert_description
    def TSC_AVDTPEX_mmi_iut_reject_discover(self, **kwargs):
        """
        Please wait while the tester verifies that the IUT does not respond to
        the invalid DISCOVER command sent by the tester.
        """

        return "OK"

    @assert_description
    def TSC_AVDTPEX_mmi_user_verify_tester_discover_discarded(self, **kwargs):
        """
        Did the IUT successfully discard the invalid DISCOVER command sent by
        the tester?
        """

        return "OK"

    @assert_description
    def TSC_A2DP_mmi_user_verify_audio_video_in_sync(self, **kwargs):
        """
        Were the spoken numbers the same as the number shown on the screen?
        """

        return "OK"

    def TSC_A2DP_mmi_iut_initiate_local_video_remote_audio_sync(self, **kwargs):
        """
        Take action to start streaming the Conformance Test Video provided by
        the Bluetooth SIG, with delay reporting configured.  The video should
        playback locally, while the audio is streamed to the tester.

        Note:
        Conformance Test Video can be found at https://www.bluetooth.org/en-
        us/test-qualification/qualification-overview/test-requirements.  The
        video can also be found in the PTS installation directory at /Bluetooth
        PTS/bin/audio/conformanceTestVideo.3gp
        """

        self.a2dp.Start(source=self.source)
        self.audio.start(duration_s=60.)
        return "OK"
