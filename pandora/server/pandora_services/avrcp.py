# Copyright 2024 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

from bumble.avdtp import Listener as AvdtpListener, MediaCodecCapabilities, AVDTP_AUDIO_MEDIA_TYPE
from bumble.avrcp import Protocol as AvrcpProtocol, make_target_service_sdp_records, make_controller_service_sdp_records
from bumble.a2dp import (A2DP_SBC_CODEC_TYPE, SBC_DUAL_CHANNEL_MODE, SBC_JOINT_STEREO_CHANNEL_MODE,
                         SBC_LOUDNESS_ALLOCATION_METHOD, SBC_MONO_CHANNEL_MODE,
                         SBC_SNR_ALLOCATION_METHOD, SBC_STEREO_CHANNEL_MODE,
                         SbcMediaCodecInformation, make_audio_sink_service_sdp_records,
                         make_audio_source_service_sdp_records)
from bumble.device import Device
from pandora_experimental.avrcp_grpc_aio import AVRCPServicer


class AvrcpService(AVRCPServicer):
    device: Device

    def __init__(self, device: Device) -> None:
        super().__init__()
        self.device = device

        sdp_records = {
            0x00010002: make_audio_source_service_sdp_records(0x00010002),  # A2DP Source
            0x00010003: make_audio_sink_service_sdp_records(0x00010003),  # A2DP Sink
            0x00010004: make_controller_service_sdp_records(0x00010004),  # AVRCP Controller
            0x00010005: make_target_service_sdp_records(0x00010005),  # AVRCP Target
        }
        self.device.sdp_service_records.update(sdp_records)

        # Register AVDTP L2cap
        avdtp_listener = AvdtpListener.for_device(device)

        def on_avdtp_connection(server) -> None:  # type: ignore
            server.add_sink(codec_capabilities())  # type: ignore

        avdtp_listener.on('connection', on_avdtp_connection)  # type: ignore

        # Register AVRCP L2cap
        avrcp_protocol = AvrcpProtocol(delegate=None)
        avrcp_protocol.listen(device)


def codec_capabilities() -> MediaCodecCapabilities:
    """Codec capabilities for the Bumble sink devices."""

    return MediaCodecCapabilities(
        media_type=AVDTP_AUDIO_MEDIA_TYPE,
        media_codec_type=A2DP_SBC_CODEC_TYPE,
        media_codec_information=SbcMediaCodecInformation.from_lists(
            sampling_frequencies=[48000, 44100, 32000, 16000],
            channel_modes=[
                SBC_MONO_CHANNEL_MODE,
                SBC_DUAL_CHANNEL_MODE,
                SBC_STEREO_CHANNEL_MODE,
                SBC_JOINT_STEREO_CHANNEL_MODE,
            ],
            block_lengths=[4, 8, 12, 16],
            subbands=[4, 8],
            allocation_methods=[
                SBC_LOUDNESS_ALLOCATION_METHOD,
                SBC_SNR_ALLOCATION_METHOD,
            ],
            minimum_bitpool_value=2,
            maximum_bitpool_value=53,
        ),
    )
