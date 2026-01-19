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

from __future__ import annotations

import asyncio
from collections.abc import Sequence

from bumble import a2dp
from bumble import avdtp
from bumble import avrcp
from bumble import device
from mobly import test_runner
from mobly import signals
from typing_extensions import override

from navi.bumble_ext import a2dp as a2dp_ext
from navi.tests import navi_test_base
from navi.utils import android_constants
from navi.utils import bl4a_api

_A2DP_SERVICE_RECORD_HANDLE = 1
_AVRCP_TARGET_RECORD_HANDLE = 2

_DEFAULT_STEP_TIMEOUT_SECONDS = 5.0
_DEFAULT_STREAM_DURATION_SECONDS = 1.0

_Property = android_constants.Property


class A2dpSinkTest(navi_test_base.TwoDevicesTestBase):
    """Tests A2DP Sink and AVRCP Controller profiles."""

    @override
    async def async_setup_class(self) -> None:
        await super().async_setup_class()

        if self.dut.device.is_emulator:
            # Force enable A2DP Sink and AVRCP Controller on emulator.
            self.dut.setprop(_Property.A2DP_SINK_ENABLED, "true")
            self.dut.setprop(_Property.AVRCP_CONTROLLER_ENABLED, "true")

        if self.dut.getprop(_Property.A2DP_SINK_ENABLED) != "true":
            raise signals.TestAbortClass("A2DP Sink is not enabled on DUT.")
        if self.dut.getprop(_Property.AVRCP_CONTROLLER_ENABLED) != "true":
            raise signals.TestAbortClass("AVRCP Controller is not enabled on DUT.")

    @override
    async def async_setup_test(self) -> None:
        await super().async_setup_test()
        # Setup SDP service records.
        self.ref.device.sdp_service_records = {
            _A2DP_SERVICE_RECORD_HANDLE:
                a2dp.make_audio_source_service_sdp_records(_A2DP_SERVICE_RECORD_HANDLE),
            _AVRCP_TARGET_RECORD_HANDLE:
                avrcp.make_target_service_sdp_records(_AVRCP_TARGET_RECORD_HANDLE),
        }

    def _setup_a2dp_source_device(
            self,
            bumble_device: device.Device,
            codecs: Sequence[a2dp_ext.A2dpCodec] = (
                a2dp_ext.A2dpCodec.SBC,
                a2dp_ext.A2dpCodec.AAC,
            ),
    ):
        # Setup AVDTP server.
        avdtp_protocol_queue = asyncio.Queue[avdtp.Protocol]()
        avdtp_listener = avdtp.Listener.for_device(device=bumble_device)

        def on_avdtp_connection(protocol: avdtp.Protocol) -> None:
            for codec in codecs:
                protocol.add_source(
                    codec.get_default_capabilities(),
                    codec.get_media_packet_pump(protocol.l2cap_channel.peer_mtu),
                )
            avdtp_protocol_queue.put_nowait(protocol)

        avdtp_listener.on(avdtp_listener.EVENT_CONNECTION, on_avdtp_connection)
        # Setup AVRCP server.
        avrcp_delegate = avrcp.Delegate()
        avrcp_protocol_starts = asyncio.Queue[None]()
        avrcp_protocol = avrcp.Protocol(delegate=avrcp_delegate)
        avrcp_protocol.listen(bumble_device)
        avrcp_protocol.on(
            avrcp_protocol.EVENT_START,
            lambda: avrcp_protocol_starts.put_nowait(None),
        )
        return avdtp_protocol_queue, avrcp_protocol, avrcp_protocol_starts

    async def test_paired_connect_outgoing(self) -> None:
        """Tests A2DP connection establishment right after a pairing session.

    Test steps:
      1. Connect and pair REF.
      2. Make A2DP and AVRCP connection from DUT.
    """
        ref_avdtp_protocol_queue, ref_avrcp_protocol, ref_avrcp_protocol_queue = (
            self._setup_a2dp_source_device(self.ref.device))
        del ref_avrcp_protocol

        self.logger.info("[DUT] Connect and pair REF.")
        await self.classic_connect_and_pair(connect_profiles=True)

        dut_a2dp_sink_callback = self.dut.bl4a.register_callback(bl4a_api.Module.A2DP_SINK)
        self.test_case_context.push(dut_a2dp_sink_callback)
        dut_avrcp_controller_callback = self.dut.bl4a.register_callback(
            bl4a_api.Module.AVRCP_CONTROLLER)
        self.test_case_context.push(dut_avrcp_controller_callback)

        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            self.logger.info("[REF] Wait for AVDTP connection")
            avdtp_protocol = await ref_avdtp_protocol_queue.get()
            self.logger.info("[REF] Discover remote endpoints")
            await avdtp_protocol.discover_remote_endpoints()

        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            self.logger.info("[REF] Wait for AVRCP connection")
            await ref_avrcp_protocol_queue.get()

        self.logger.info("[DUT] Waiting for A2DP connection state changed.")
        await dut_a2dp_sink_callback.wait_for_event(
            bl4a_api.ProfileConnectionStateChanged(
                address=self.ref.address,
                state=android_constants.ConnectionState.CONNECTED,
            ))

        self.logger.info("[DUT] Waiting for AVRCP connection state changed.")
        await dut_avrcp_controller_callback.wait_for_event(
            bl4a_api.ProfileConnectionStateChanged(
                address=self.ref.address,
                state=android_constants.ConnectionState.CONNECTED,
            ))

    async def test_streaming_sbc(self) -> None:
        """Tests streaming SBC.

    Test steps:
      1. Connect and pair REF.
      2. Make A2DP and AVRCP connection from DUT.
      3. Start streaming SBC.
      4. Stop streaming SBC.
    """
        ref_avdtp_protocol_queue, ref_avrcp_protocol, ref_avrcp_protocol_queue = (
            self._setup_a2dp_source_device(self.ref.device, codecs=[a2dp_ext.A2dpCodec.SBC]))
        del ref_avrcp_protocol, ref_avrcp_protocol_queue

        self.logger.info("[DUT] Connect and pair REF.")
        await self.classic_connect_and_pair(connect_profiles=True)

        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            self.logger.info("[REF] Wait for AVDTP connection")
            avdtp_protocol = await ref_avdtp_protocol_queue.get()
            self.logger.info("[REF] Discover remote endpoints")
            await avdtp_protocol.discover_remote_endpoints()

        sources = a2dp_ext.find_local_endpoints_by_codec(avdtp_protocol, a2dp.A2DP_SBC_CODEC_TYPE,
                                                         avdtp.LocalSource)
        if not sources:
            self.fail("No A2DP local SBC source found")

        if not (stream := sources[0].stream):
            # If there is only one source, DUT will automatically create a stream.
            self.fail("No A2DP SBC stream found")

        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            self.logger.info("[REF] Start stream")
            await stream.start()

        await asyncio.sleep(_DEFAULT_STREAM_DURATION_SECONDS)

        async with self.assert_not_timeout(_DEFAULT_STEP_TIMEOUT_SECONDS):
            self.logger.info("[REF] Stop stream")
            await stream.stop()


if __name__ == "__main__":
    test_runner.main()
