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

from mmi2grpc._helpers import assert_description, match_description
from mmi2grpc._proxy import ProfileProxy
from mmi2grpc._rootcanal import Dongle
from pandora.host_grpc import Host
from pandora.host_pb2 import RANDOM
from pandora.mediaplayer_grpc import MediaPlayer
from pandora.security_grpc import Security, SecurityStorage
from pandora.security_pb2 import PairingEventAnswer, LE_LEVEL3
from pandora.gatt_grpc import GATT
from pandora.le_audio_grpc import LeAudio


class GMCSProxy(ProfileProxy):

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
        self.media_player = MediaPlayer(channel)

    def test_started(self, test: str, **kwargs):
        self.rootcanal.select_pts_dongle(Dongle.LAIRD_BL654)

        if test in [
                "GMCS/SR/MCP/BV-01-C",
                "GMCS/SR/MCP/BV-03-C",
                "GMCS/SR/SP/BV-02-C",
                "GMCS/SR/SPE/BI-01-C",
                "GMCS/SR/SPE/BI-07-C",
        ]:
            self.media_player.StartTestPlayback()

            if test == "GMCS/SR/MCP/BV-01-C":
                self.media_player.Pause()
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

    @assert_description
    def _mmi_4(self, **kwargs):
        """
        Please configure an initial state to Inactive state
        """
        self.media_player.StopTestPlayback()
        return "OK"
