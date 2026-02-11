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

from bumble import a2dp
from bumble import avrcp
from bumble.avdtp import Listener as AvdtpListener, MediaCodecCapabilities, AVDTP_AUDIO_MEDIA_TYPE
from bumble.device import Device
from pandora.avrcp_grpc_aio import AVRCPServicer


class AvrcpService(AVRCPServicer):
    device: Device

    def __init__(self, device: Device) -> None:
        super().__init__()
        self.device = device

        sdp_records = {
            0x00010002: a2dp.make_audio_source_service_sdp_records(0x00010002),  # A2DP Source
            0x00010003: a2dp.make_audio_sink_service_sdp_records(0x00010003),  # A2DP Sink
            0x00010004: avrcp.ControllerServiceSdpRecord(
                0x00010004).to_service_attributes(),  # AVRCP Controller
            0x00010005:
                avrcp.TargetServiceSdpRecord(0x00010005).to_service_attributes(),  # AVRCP Target
        }
        self.device.sdp_service_records.update(sdp_records)

        # Register AVDTP L2cap
        avdtp_listener = AvdtpListener.for_device(device)

        def on_avdtp_connection(server) -> None:  # type: ignore
            server.add_sink(codec_capabilities())  # type: ignore

        avdtp_listener.on('connection', on_avdtp_connection)  # type: ignore

        # Register AVRCP L2cap
        avrcp_protocol = avrcp.Protocol(delegate=None)
        avrcp_protocol.listen(device)


def codec_capabilities() -> MediaCodecCapabilities:
    """Codec capabilities for the Bumble sink devices."""

    return MediaCodecCapabilities(
        media_type=AVDTP_AUDIO_MEDIA_TYPE,
        media_codec_type=a2dp.A2DP_SBC_CODEC_TYPE,
        media_codec_information=a2dp.SbcMediaCodecInformation(
            sampling_frequency=(a2dp.SbcMediaCodecInformation.SamplingFrequency.SF_16000 |
                                a2dp.SbcMediaCodecInformation.SamplingFrequency.SF_32000 |
                                a2dp.SbcMediaCodecInformation.SamplingFrequency.SF_44100 |
                                a2dp.SbcMediaCodecInformation.SamplingFrequency.SF_48000),
            channel_mode=(a2dp.SbcMediaCodecInformation.ChannelMode.MONO |
                          a2dp.SbcMediaCodecInformation.ChannelMode.DUAL_CHANNEL |
                          a2dp.SbcMediaCodecInformation.ChannelMode.STEREO |
                          a2dp.SbcMediaCodecInformation.ChannelMode.JOINT_STEREO),
            block_length=(a2dp.SbcMediaCodecInformation.BlockLength.BL_4 |
                          a2dp.SbcMediaCodecInformation.BlockLength.BL_8 |
                          a2dp.SbcMediaCodecInformation.BlockLength.BL_12 |
                          a2dp.SbcMediaCodecInformation.BlockLength.BL_16),
            subbands=(a2dp.SbcMediaCodecInformation.Subbands.S_4 |
                      a2dp.SbcMediaCodecInformation.Subbands.S_8),
            allocation_method=(a2dp.SbcMediaCodecInformation.AllocationMethod.LOUDNESS |
                               a2dp.SbcMediaCodecInformation.AllocationMethod.SNR),
            minimum_bitpool_value=2,
            maximum_bitpool_value=53,
        ),
    )
