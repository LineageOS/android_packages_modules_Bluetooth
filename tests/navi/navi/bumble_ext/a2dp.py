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
from typing import ClassVar, Self, TypeVar

from bumble import a2dp
from bumble import avdtp
from bumble import codecs
from bumble import core
from bumble import device as device_lib
from bumble import sdp

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
    device: device_lib.Device,
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
        a2dp_sink_handle:
            (SinkSdpRecord(service_record_handle=a2dp_sink_handle).to_service_attributes()),
    })
    return listener


@dataclasses.dataclass
class SourceSdpRecord:
    """A2DP source SDP record."""

    class Features(enum.IntFlag):
        """A2DP source SDP record features."""

        PLAYER = 0x01
        MICROPHONE = 0x02
        TUNER = 0x04
        MIXER = 0x08

    service_record_handle: int
    avdtp_version: tuple[int, int] = (1, 3)
    a2dp_version: tuple[int, int] = (1, 3)
    supported_features: Features | None = None

    def to_service_attributes(self) -> list[sdp.ServiceAttribute]:
        """Converts the SDP record to a list of SDP service attributes.

    The record exposes the features supported in the input configuration,
    and the allocated RFCOMM channel.

    Returns:
      A list of SDP service attributes.
    """
        attributes = [
            sdp.ServiceAttribute(
                sdp.SDP_SERVICE_RECORD_HANDLE_ATTRIBUTE_ID,
                sdp.DataElement.unsigned_integer_32(self.service_record_handle),
            ),
            sdp.ServiceAttribute(
                sdp.SDP_BROWSE_GROUP_LIST_ATTRIBUTE_ID,
                sdp.DataElement.sequence([sdp.DataElement.uuid(sdp.SDP_PUBLIC_BROWSE_ROOT)]),
            ),
            sdp.ServiceAttribute(
                sdp.SDP_SERVICE_CLASS_ID_LIST_ATTRIBUTE_ID,
                sdp.DataElement.sequence([sdp.DataElement.uuid(core.BT_AUDIO_SOURCE_SERVICE)]),
            ),
            sdp.ServiceAttribute(
                sdp.SDP_PROTOCOL_DESCRIPTOR_LIST_ATTRIBUTE_ID,
                sdp.DataElement.sequence([
                    sdp.DataElement.sequence([
                        sdp.DataElement.uuid(core.BT_L2CAP_PROTOCOL_ID),
                        sdp.DataElement.unsigned_integer_16(avdtp.AVDTP_PSM),
                    ]),
                    sdp.DataElement.sequence([
                        sdp.DataElement.uuid(core.BT_AVDTP_PROTOCOL_ID),
                        sdp.DataElement.unsigned_integer_16(self.avdtp_version[0] << 8 |
                                                            self.avdtp_version[1]),
                    ]),
                ]),
            ),
            sdp.ServiceAttribute(
                sdp.SDP_BLUETOOTH_PROFILE_DESCRIPTOR_LIST_ATTRIBUTE_ID,
                sdp.DataElement.sequence([
                    sdp.DataElement.sequence([
                        sdp.DataElement.uuid(core.BT_ADVANCED_AUDIO_DISTRIBUTION_SERVICE),
                        sdp.DataElement.unsigned_integer_16(self.a2dp_version[0] << 8 |
                                                            self.a2dp_version[1]),
                    ])
                ]),
            ),
        ]
        if self.supported_features is not None:
            attributes.append(
                sdp.ServiceAttribute(
                    sdp.SDP_SUPPORTED_FEATURES_ATTRIBUTE_ID,
                    sdp.DataElement.unsigned_integer_16(self.supported_features),
                ))
        return attributes

    @classmethod
    async def find(
        cls,
        connection: device_lib.Connection,
    ) -> list[Self]:
        """Searches for A2DP source SDP records from remote device.

    Args:
        connection: ACL connection to make SDP search.

    Returns:
        A list of A2DP source SDP records.
    """
        records = []
        async with sdp.Client(connection) as sdp_client:
            search_result = await sdp_client.search_attributes(
                uuids=[core.BT_AUDIO_SOURCE_SERVICE],
                attribute_ids=[
                    sdp.SDP_SERVICE_RECORD_HANDLE_ATTRIBUTE_ID,
                    sdp.SDP_BLUETOOTH_PROFILE_DESCRIPTOR_LIST_ATTRIBUTE_ID,
                    sdp.SDP_SUPPORTED_FEATURES_ATTRIBUTE_ID,
                    sdp.SDP_PROTOCOL_DESCRIPTOR_LIST_ATTRIBUTE_ID,
                ],
            )
            for attribute_lists in search_result:
                avdtp_version: tuple[int, int] | None = None
                a2dp_version: tuple[int, int] | None = None
                service_record_handle: int | None = None
                features: SourceSdpRecord.Features | None = None
                for attribute in attribute_lists:
                    match attribute.id:
                        case sdp.SDP_SERVICE_RECORD_HANDLE_ATTRIBUTE_ID:
                            service_record_handle = attribute.value.value
                        case sdp.SDP_BLUETOOTH_PROFILE_DESCRIPTOR_LIST_ATTRIBUTE_ID:
                            profile_descriptor_list = attribute.value.value
                            a2dp_version = (
                                profile_descriptor_list[0].value[1].value >> 8,
                                profile_descriptor_list[0].value[1].value & 0xFF,
                            )
                        case sdp.SDP_PROTOCOL_DESCRIPTOR_LIST_ATTRIBUTE_ID:
                            protocol_descriptor_list = attribute.value.value
                            avdtp_version = (
                                protocol_descriptor_list[1].value[1].value >> 8,
                                protocol_descriptor_list[1].value[1].value & 0xFF,
                            )
                        case sdp.SDP_SUPPORTED_FEATURES_ATTRIBUTE_ID:
                            features = SourceSdpRecord.Features(attribute.value.value)

                if (avdtp_version is None or a2dp_version is None or service_record_handle is None):
                    continue
                records.append(
                    cls(
                        service_record_handle=service_record_handle,
                        avdtp_version=avdtp_version,
                        a2dp_version=a2dp_version,
                        supported_features=features,
                    ))
        return records


@dataclasses.dataclass
class SinkSdpRecord:
    """A2DP sink SDP record."""

    class Features(enum.IntFlag):
        """A2DP sink SDP record features."""

        HEADPHONE = 0x01
        SPEAKER = 0x02
        RECORDER = 0x04
        AMPLIFIER = 0x08

    service_record_handle: int
    avdtp_version: tuple[int, int] = (1, 3)
    a2dp_version: tuple[int, int] = (1, 3)
    supported_features: Features | None = None

    def to_service_attributes(self) -> list[sdp.ServiceAttribute]:
        """Converts the SDP record to a list of SDP service attributes.

    The record exposes the features supported in the input configuration,
    and the allocated RFCOMM channel.

    Returns:
      A list of SDP service attributes.
    """
        attributes = [
            sdp.ServiceAttribute(
                sdp.SDP_SERVICE_RECORD_HANDLE_ATTRIBUTE_ID,
                sdp.DataElement.unsigned_integer_32(self.service_record_handle),
            ),
            sdp.ServiceAttribute(
                sdp.SDP_SERVICE_CLASS_ID_LIST_ATTRIBUTE_ID,
                sdp.DataElement.sequence([sdp.DataElement.uuid(core.BT_AUDIO_SINK_SERVICE)]),
            ),
            sdp.ServiceAttribute(
                sdp.SDP_PROTOCOL_DESCRIPTOR_LIST_ATTRIBUTE_ID,
                sdp.DataElement.sequence([
                    sdp.DataElement.sequence([
                        sdp.DataElement.uuid(core.BT_L2CAP_PROTOCOL_ID),
                        sdp.DataElement.unsigned_integer_16(avdtp.AVDTP_PSM),
                    ]),
                    sdp.DataElement.sequence([
                        sdp.DataElement.uuid(core.BT_AVDTP_PROTOCOL_ID),
                        sdp.DataElement.unsigned_integer_16(self.avdtp_version[0] << 8 |
                                                            self.avdtp_version[1]),
                    ]),
                ]),
            ),
            sdp.ServiceAttribute(
                sdp.SDP_BLUETOOTH_PROFILE_DESCRIPTOR_LIST_ATTRIBUTE_ID,
                sdp.DataElement.sequence([
                    sdp.DataElement.sequence([
                        sdp.DataElement.uuid(core.BT_ADVANCED_AUDIO_DISTRIBUTION_SERVICE),
                        sdp.DataElement.unsigned_integer_16(self.a2dp_version[0] << 8 |
                                                            self.a2dp_version[1]),
                    ])
                ]),
            ),
        ]
        if self.supported_features is not None:
            attributes.append(
                sdp.ServiceAttribute(
                    sdp.SDP_SUPPORTED_FEATURES_ATTRIBUTE_ID,
                    sdp.DataElement.unsigned_integer_16(self.supported_features),
                ))
        return attributes

    @classmethod
    async def find(
        cls,
        connection: device_lib.Connection,
    ) -> list[Self]:
        """Searches for A2DP sink SDP records from remote device.

    Args:
        connection: ACL connection to make SDP search.

    Returns:
        A list of A2DP source SDP records.
    """
        records = []
        async with sdp.Client(connection) as sdp_client:
            search_result = await sdp_client.search_attributes(
                uuids=[core.BT_AUDIO_SINK_SERVICE],
                attribute_ids=[
                    sdp.SDP_SERVICE_RECORD_HANDLE_ATTRIBUTE_ID,
                    sdp.SDP_BLUETOOTH_PROFILE_DESCRIPTOR_LIST_ATTRIBUTE_ID,
                    sdp.SDP_SUPPORTED_FEATURES_ATTRIBUTE_ID,
                    sdp.SDP_PROTOCOL_DESCRIPTOR_LIST_ATTRIBUTE_ID,
                ],
            )
            for attribute_lists in search_result:
                avdtp_version: tuple[int, int] | None = None
                a2dp_version: tuple[int, int] | None = None
                service_record_handle: int | None = None
                features: SinkSdpRecord.Features | None = None
                for attribute in attribute_lists:
                    match attribute.id:
                        case sdp.SDP_SERVICE_RECORD_HANDLE_ATTRIBUTE_ID:
                            service_record_handle = attribute.value.value
                        case sdp.SDP_BLUETOOTH_PROFILE_DESCRIPTOR_LIST_ATTRIBUTE_ID:
                            profile_descriptor_list = attribute.value.value
                            a2dp_version = (
                                profile_descriptor_list[0].value[1].value >> 8,
                                profile_descriptor_list[0].value[1].value & 0xFF,
                            )
                        case sdp.SDP_PROTOCOL_DESCRIPTOR_LIST_ATTRIBUTE_ID:
                            protocol_descriptor_list = attribute.value.value
                            avdtp_version = (
                                protocol_descriptor_list[1].value[1].value >> 8,
                                protocol_descriptor_list[1].value[1].value & 0xFF,
                            )
                        case sdp.SDP_SUPPORTED_FEATURES_ATTRIBUTE_ID:
                            features = SinkSdpRecord.Features(attribute.value.value)

                if (avdtp_version is None or a2dp_version is None or service_record_handle is None):
                    continue
                records.append(
                    cls(
                        service_record_handle=service_record_handle,
                        avdtp_version=avdtp_version,
                        a2dp_version=a2dp_version,
                        supported_features=features,
                    ))
        return records
