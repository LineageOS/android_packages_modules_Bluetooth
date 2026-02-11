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
"""A2DP vendor-specific codec helpers.

There isn't an opened specification for most vendor-specific codecs, so this
module majorly refers to the implementation of AOSP:
* packages/modules/Bluetooth/system/stack/a2dp/
* packages/modules/Bluetooth/system/stack/include/
"""

import asyncio
from collections.abc import Sequence
import dataclasses
import enum
import struct
from typing import ClassVar, TypeVar

from bumble import a2dp
from bumble import avdtp
from bumble import codecs
from bumble import device as bumble_device

from navi.bumble_ext import ogg
from navi.utils import constants


class AptxChannelMode(enum.IntFlag):
    MONO = 0x01
    STEREO = 0x02


class AptxSamplingRate(enum.IntFlag):
    RATE_44100 = 0x20
    RATE_48000 = 0x10


class LdacSamplingRate(enum.IntFlag):
    RATE_44100 = 0x20
    RATE_48000 = 0x10
    RATE_88200 = 0x08
    RATE_96000 = 0x04
    RATE_176400 = 0x02
    RATE_192000 = 0x01


class LdacChannelMode(enum.IntFlag):
    MONO = 0x04
    DUAL = 0x02
    STEREO = 0x01


@dataclasses.dataclass(frozen=True)
class AptxCodecInformation:
    """APT-X codec information."""

    sample_rate: AptxSamplingRate
    channel_mode: AptxChannelMode

    VENDOR_ID: ClassVar[int] = 0x4F
    CODEC_ID: ClassVar[int] = 0x01

    def __bytes__(self) -> bytes:
        return struct.pack(
            '<IHB',
            self.VENDOR_ID,
            self.CODEC_ID,
            self.sample_rate | self.channel_mode,
        )


@dataclasses.dataclass(frozen=True)
class AptxHdCodecInformation:
    """APT-X HD codec information."""

    sample_rate: AptxSamplingRate
    channel_mode: AptxChannelMode

    VENDOR_ID: ClassVar[int] = 0xD7
    CODEC_ID: ClassVar[int] = 0x24

    def __bytes__(self) -> bytes:
        return struct.pack(
            '<IHB4s',
            self.VENDOR_ID,
            self.CODEC_ID,
            self.sample_rate | self.channel_mode,
            bytes(4),  # RFU
        )


@dataclasses.dataclass(frozen=True)
class LdacCodecInformation:
    """LDAC codec information."""

    sample_rate: LdacSamplingRate
    channel_mode: LdacChannelMode

    VENDOR_ID: ClassVar[int] = 0x012D
    CODEC_ID: ClassVar[int] = 0xAA

    def __bytes__(self) -> bytes:
        return struct.pack(
            '<IHBB',
            self.VENDOR_ID,
            self.CODEC_ID,
            self.sample_rate,
            self.channel_mode,
        )


@enum.unique
class A2dpCodec(constants.ShortReprEnum):
    """A2DP codecs.

  Codecs are following the order of
  packages/modules/Bluetooth/android/app/res/values/config.xml
  """

    OPUS = enum.auto()
    LDAC = enum.auto()
    APTX_HD = enum.auto()
    APTX = enum.auto()
    AAC = enum.auto()
    SBC = enum.auto()

    def get_default_capabilities(self) -> avdtp.MediaCodecCapabilities:
        match self:
            case A2dpCodec.AAC:
                return avdtp.MediaCodecCapabilities(
                    media_type=avdtp.MediaType.AUDIO,
                    media_codec_type=a2dp.CodecType.MPEG_2_4_AAC,
                    media_codec_information=a2dp.AacMediaCodecInformation(
                        object_type=(a2dp.AacMediaCodecInformation.ObjectType.MPEG_2_AAC_LC),
                        sampling_frequency=(
                            a2dp.AacMediaCodecInformation.SamplingFrequency.SF_44100 |
                            a2dp.AacMediaCodecInformation.SamplingFrequency.SF_48000),
                        channels=(a2dp.AacMediaCodecInformation.Channels.MONO |
                                  a2dp.AacMediaCodecInformation.Channels.STEREO),
                        vbr=1,
                        bitrate=256000,
                    ),
                )
            case A2dpCodec.SBC:
                return avdtp.MediaCodecCapabilities(
                    media_type=avdtp.MediaType.AUDIO,
                    media_codec_type=a2dp.CodecType.SBC,
                    media_codec_information=a2dp.SbcMediaCodecInformation(
                        sampling_frequency=(
                            a2dp.SbcMediaCodecInformation.SamplingFrequency.SF_16000 |
                            a2dp.SbcMediaCodecInformation.SamplingFrequency.SF_32000 |
                            a2dp.SbcMediaCodecInformation.SamplingFrequency.SF_44100 |
                            a2dp.SbcMediaCodecInformation.SamplingFrequency.SF_48000),
                        channel_mode=(a2dp.SbcMediaCodecInformation.ChannelMode.MONO |
                                      a2dp.SbcMediaCodecInformation.ChannelMode.JOINT_STEREO |
                                      a2dp.SbcMediaCodecInformation.ChannelMode.DUAL_CHANNEL |
                                      a2dp.SbcMediaCodecInformation.ChannelMode.STEREO),
                        block_length=(a2dp.SbcMediaCodecInformation.BlockLength.BL_4 |
                                      a2dp.SbcMediaCodecInformation.BlockLength.BL_8 |
                                      a2dp.SbcMediaCodecInformation.BlockLength.BL_12 |
                                      a2dp.SbcMediaCodecInformation.BlockLength.BL_16),
                        subbands=(a2dp.SbcMediaCodecInformation.Subbands.S_4 |
                                  a2dp.SbcMediaCodecInformation.Subbands.S_8),
                        allocation_method=(a2dp.SbcMediaCodecInformation.AllocationMethod.SNR |
                                           a2dp.SbcMediaCodecInformation.AllocationMethod.LOUDNESS),
                        minimum_bitpool_value=2,
                        maximum_bitpool_value=53,
                    ),
                )
            case A2dpCodec.APTX:
                return avdtp.MediaCodecCapabilities(
                    media_type=avdtp.MediaType.AUDIO,
                    media_codec_type=a2dp.CodecType.NON_A2DP,
                    media_codec_information=AptxCodecInformation(
                        sample_rate=AptxSamplingRate.RATE_48000,
                        channel_mode=AptxChannelMode.STEREO,
                    ),
                )
            case A2dpCodec.APTX_HD:
                return avdtp.MediaCodecCapabilities(
                    media_type=avdtp.MediaType.AUDIO,
                    media_codec_type=a2dp.CodecType.NON_A2DP,
                    media_codec_information=AptxHdCodecInformation(
                        sample_rate=AptxSamplingRate.RATE_48000,
                        channel_mode=AptxChannelMode.STEREO,
                    ),
                )
            case A2dpCodec.LDAC:
                return avdtp.MediaCodecCapabilities(
                    media_type=avdtp.MediaType.AUDIO,
                    media_codec_type=a2dp.CodecType.NON_A2DP,
                    media_codec_information=LdacCodecInformation(
                        sample_rate=LdacSamplingRate.RATE_48000,
                        channel_mode=LdacChannelMode.STEREO,
                    ),
                )
            case A2dpCodec.OPUS:
                return avdtp.MediaCodecCapabilities(
                    media_type=avdtp.MediaType.AUDIO,
                    media_codec_type=a2dp.CodecType.NON_A2DP,
                    media_codec_information=a2dp.OpusMediaCodecInformation(
                        sampling_frequency=a2dp.OpusMediaCodecInformation.SamplingFrequency.
                        SF_48000,
                        channel_mode=a2dp.OpusMediaCodecInformation.ChannelMode.STEREO,
                        frame_size=a2dp.OpusMediaCodecInformation.FrameSize.FS_20MS,
                    ),
                )

    def get_media_packet_pump(self, peer_mtu: int) -> avdtp.MediaPacketPump:
        """Returns an empty packet pump for the given codec."""

        # Empty packet source.
        # TODO: Implement valid packet source.
        async def read(size: int) -> bytes:
            del size
            return b''

        source: a2dp.SbcPacketSource | a2dp.AacPacketSource | a2dp.OpusPacketSource
        match self:
            case A2dpCodec.SBC:
                source = a2dp.SbcPacketSource(read, peer_mtu)
            case A2dpCodec.AAC:
                source = a2dp.AacPacketSource(read, peer_mtu)
            case A2dpCodec.OPUS:
                source = a2dp.OpusPacketSource(read, peer_mtu)
            case _:
                raise ValueError(f'Unsupported codec: {self}')
        return avdtp.MediaPacketPump(source.packets)

    @property
    def format(self) -> str:
        """Container format of the codec.

    Older ffmpeg doesn't support "opus" format and so we use "ogg" instead.
    """
        if self == A2dpCodec.OPUS:
            return 'ogg'
        return self.name.lower()

    @property
    def codec_id(self) -> int:
        return {
            A2dpCodec.SBC: 0,
            A2dpCodec.AAC: 0,
            A2dpCodec.APTX: AptxCodecInformation.CODEC_ID,
            A2dpCodec.APTX_HD: AptxHdCodecInformation.CODEC_ID,
            A2dpCodec.LDAC: LdacCodecInformation.CODEC_ID,
            A2dpCodec.OPUS: a2dp.OpusMediaCodecInformation.CODEC_ID,
        }[self]

    @property
    def vendor_id(self) -> int:
        return {
            A2dpCodec.SBC: 0,
            A2dpCodec.AAC: 0,
            A2dpCodec.APTX: AptxCodecInformation.VENDOR_ID,
            A2dpCodec.APTX_HD: AptxHdCodecInformation.VENDOR_ID,
            A2dpCodec.LDAC: LdacCodecInformation.VENDOR_ID,
            A2dpCodec.OPUS: a2dp.OpusMediaCodecInformation.VENDOR_ID,
        }[self]

    @property
    def codec_type(self) -> int:
        return {
            A2dpCodec.SBC: a2dp.A2DP_SBC_CODEC_TYPE,
            A2dpCodec.AAC: a2dp.A2DP_MPEG_2_4_AAC_CODEC_TYPE,
            A2dpCodec.APTX: a2dp.CodecType.NON_A2DP,
            A2dpCodec.APTX_HD: a2dp.CodecType.NON_A2DP,
            A2dpCodec.LDAC: a2dp.CodecType.NON_A2DP,
            A2dpCodec.OPUS: a2dp.CodecType.NON_A2DP,
        }[self]


class LocalSinkWrapper:
    """Wrapper for LocalSink to provide start/suspend events."""

    def __init__(self, impl: avdtp.LocalSink):
        self.impl = impl
        self.condition = asyncio.Condition()
        for command in (
                impl.EVENT_CONFIGURATION,
                impl.EVENT_OPEN,
                impl.EVENT_START,
                impl.EVENT_SUSPEND,
                impl.EVENT_CLOSE,
                impl.EVENT_ABORT,
        ):
            self.impl.on(command, self._on_command)

    async def _on_command(self) -> None:
        async with self.condition:
            self.condition.notify_all()

    @property
    def stream_state(self) -> int | None:
        return self.impl.stream.state if self.impl.stream else None


def register_sink_buffer(sink: avdtp.LocalSink, codec: A2dpCodec) -> bytearray | None:
    """Registers the sink buffer to receive the packets.

  Args:
    sink: The sink to register the buffer to.
    codec: The codec of the sink.

  Returns:
    The sink buffer, or None if the codec is not supported.
  """
    buffer = bytearray()
    match codec:
        case A2dpCodec.SBC | A2dpCodec.LDAC:

            @sink.on(avdtp.LocalSink.EVENT_RTP_PACKET)
            def _(packet: avdtp.MediaPacket) -> None:
                buffer.extend(packet.payload[1:])

        case A2dpCodec.AAC:

            @sink.on(avdtp.LocalSink.EVENT_RTP_PACKET)
            def _(packet: avdtp.MediaPacket) -> None:
                buffer.extend(codecs.AacAudioRtpPacket.from_bytes(packet.payload).to_adts())

        case A2dpCodec.APTX:

            def on_avdtp_packet(packet: bytes) -> None:
                buffer.extend(packet)

            sink.on_avdtp_packet = on_avdtp_packet  # type: ignore[method-assign]
            if sink.stream and sink.stream.rtp_channel:
                sink.stream.rtp_channel.sink = sink.on_avdtp_packet

        case A2dpCodec.APTX_HD:

            @sink.on(avdtp.LocalSink.EVENT_RTP_PACKET)
            def _(packet: avdtp.MediaPacket) -> None:
                buffer.extend(packet.payload)

        case A2dpCodec.OPUS:

            # https://datatracker.ietf.org/doc/html/rfc7845#section-3
            # First page must be the ID header.
            buffer.extend(
                ogg.Page(
                    # Change this when we support other codec configurations.
                    payload=ogg.OpusIdHeader(sample_rate=48000, channel_count=2),
                    header_type=ogg.Page.HeaderType.IS_FIRST_PAGE,
                    page_sequence_number=0,
                ).to_bytes())
            # Second page must be the comment header. It can be empty.
            buffer.extend(
                ogg.Page(
                    payload=ogg.OpusCommentHeader(),
                    page_sequence_number=1,
                ).to_bytes())
            page_sequence_number = 2

            @sink.on(avdtp.LocalSink.EVENT_RTP_PACKET)
            def _(packet: avdtp.MediaPacket) -> None:
                nonlocal page_sequence_number
                buffer.extend(
                    ogg.Page(
                        payload=packet.payload[1:],
                        page_sequence_number=page_sequence_number,
                    ).to_bytes())
                page_sequence_number += 1

        case _:
            # Unexpected codec or no decoder.
            return None
    return buffer


def _endpoint_supports_codec(
    endpoint: avdtp.LocalStreamEndPoint,
    codec_type: int,
    vendor_id: int = 0,
    codec_id: int = 0,
) -> bool:
    """Checks if the endpoint supports the codec."""
    for capability in endpoint.capabilities:
        if not (isinstance(capability, avdtp.MediaCodecCapabilities) and capability.media_type
                == avdtp.MediaType.AUDIO and capability.media_codec_type == codec_type):
            continue
        codec_info = capability.media_codec_information
        if not isinstance(codec_info, a2dp.VendorSpecificMediaCodecInformation) or (
                codec_info.vendor_id == vendor_id and codec_info.codec_id == codec_id):
            return True
    return False


_ENDPOINT = TypeVar('_ENDPOINT', bound=avdtp.LocalStreamEndPoint)


def find_local_endpoints_by_codec(
    protocol: avdtp.Protocol,
    codec_type: int,
    endpoint_type: type[_ENDPOINT],
    vendor_id: int = 0,
    codec_id: int = 0,
) -> list[_ENDPOINT]:
    """Finds the local source by codec type and vendor/codec ID."""
    return [
        endpoint for endpoint in protocol.local_endpoints if isinstance(endpoint, endpoint_type) and
        _endpoint_supports_codec(endpoint, codec_type, vendor_id, codec_id)
    ]


def setup_sink_server(
    device: bumble_device.Device,
    supported_capabilities: Sequence[avdtp.MediaCodecCapabilities],
    a2dp_sink_handle: int,
) -> avdtp.Listener:
    """Sets up the sink server on the device.

  Args:
    device: The device to set up the sink server on.
    supported_capabilities: The capabilities of the sink server.
    a2dp_sink_handle: The handle of the A2DP sink service record.

  Returns:
    The AVDTP listener.
  """
    listener = avdtp.Listener.for_device(device)

    @listener.on(listener.EVENT_CONNECTION)
    def _(server: avdtp.Protocol) -> None:
        for capability in supported_capabilities:
            server.add_sink(capability)

    device.sdp_service_records.update({
        a2dp_sink_handle: a2dp.make_audio_sink_service_sdp_records(a2dp_sink_handle),
    })
    return listener
