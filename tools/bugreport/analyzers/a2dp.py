import btsnoop
import dataclasses
import enum
from packets import avdtp
from packets import l2cap
from typing import Optional, List, Iterator
from btsnoop import Direction
import struct
import subprocess

import numpy as np
import matplotlib
import matplotlib.pyplot as plt
from colorama import Fore, Style


def log(ts, msg):
    print(Fore.CYAN + f"  {ts}" + Style.RESET_ALL + f" | {msg}")


@dataclasses.dataclass
class AclPacket:
    packet: btsnoop.Packet
    channel_id: int
    direction: btsnoop.Direction
    payload: bytes

    @staticmethod
    def parse(packet: btsnoop.Packet) -> "AclPacket":
        payload = bytearray(packet.payload[4:])
        for fragment in packet.continuing_fragments:
            payload.extend(fragment.payload[4:])

        pdu_length, channel_id = struct.unpack("<HH", payload[:4])
        assert pdu_length == len(payload[4:])

        acl_packet = AclPacket(packet, channel_id, packet.direction, bytes(payload[4:]))
        return (
            L2capSignalingPacket.parse(acl_packet)
            if channel_id == 0x0001
            else acl_packet
        )


@dataclasses.dataclass
class L2capSignalingPacket(AclPacket):
    signal: l2cap.SignalingPacket

    @staticmethod
    def parse(packet: AclPacket):
        assert packet.channel_id == 0x0001
        return L2capSignalingPacket(
            packet.packet,
            packet.channel_id,
            packet.direction,
            packet.payload,
            l2cap.SignalingPacket.parse_all(packet.payload),
        )


@dataclasses.dataclass
class AvdtpSignalingPacket(AclPacket):
    signal: avdtp.SignalingPacket

    @staticmethod
    def parse(packet: AclPacket):
        return AvdtpSignalingPacket(
            packet.packet,
            packet.channel_id,
            packet.direction,
            packet.payload,
            avdtp.SignalingPacket.parse_all(packet.payload),
        )


@dataclasses.dataclass
class L2capChannel:
    """Save all packets sent or received on an L2CAP channel."""

    connection: btsnoop.AclConnection
    uuid: int = dataclasses.field(default_factory=btsnoop.next_uuid)
    source_cid: Optional[int] = None
    destination_cid: Optional[int] = None
    psm: Optional[int] = None
    connected: Optional[L2capSignalingPacket] = None
    disconnected: Optional[L2capSignalingPacket] = None
    packets: List[AclPacket] = dataclasses.field(default_factory=list)

    def __str__(self):
        tx_packet_count = 0
        rx_packet_count = 0
        for packet in self.packets:
            tx_packet_count += packet.direction == Direction.SENT
            rx_packet_count += packet.direction == Direction.RCVD

        return """    L2CAP Channel ({psm}):
        source_cid: {source_cid}
        destination_cid: {destination_cid}
        connected: {connected}
        disconnected: {disconnected}
        tx_packet_count: {tx_packet_count}
        rx_packet_count: {rx_packet_count}""".format(
            psm=f"0x{self.psm:04x}" if self.psm else "Unknown",
            source_cid=f"0x{self.source_cid:04x}" if self.source_cid else "Unknown",
            destination_cid=(
                f"0x{self.destination_cid:04x}" if self.destination_cid else "Unknown"
            ),
            connected=self.connected.packet.number if self.connected else "Unknown",
            disconnected=(
                self.disconnected.packet.number if self.disconnected else "Unknown"
            ),
            tx_packet_count=tx_packet_count,
            rx_packet_count=rx_packet_count,
        )


class AvdtpState(enum.Enum):
    INIT = "Init"
    CONFIGURED = "Configured"
    OPEN = "Open"
    STREAMING = "Streaming"
    ABORT = "Abort"


@dataclasses.dataclass
class AvdtpStream:
    configuration: avdtp.ServiceCapability
    started: AvdtpSignalingPacket
    suspended: Optional[AvdtpSignalingPacket] = None
    packets: List[AclPacket] = dataclasses.field(default_factory=list)


@dataclasses.dataclass
class AvdtpSession:
    local_cid: int
    remote_cid: int
    stream_cid: Optional[int] = None
    state: AvdtpState = AvdtpState.INIT
    pending_tx_request: Optional[AvdtpSignalingPacket] = None
    pending_rx_request: Optional[AvdtpSignalingPacket] = None
    configuration: Optional[AvdtpSignalingPacket] = None


def plot_avdtp_stream(ax, stream: AvdtpStream):
    if not stream.packets:
        return
    if stream.configuration.media_codec_type == 0x0:
        plot_sbc_stream(ax, stream)
    elif stream.configuration.media_codec_type == 0x2:
        plot_aac_stream(ax, stream)
    elif stream.configuration.media_codec_type == 0xFF:
        codec_id = stream.configuration.media_codec_specific_information_elements[:6]
        if codec_id == [0x2D, 0x01, 0x00, 0x00, 0xAA, 0x00]:
            plot_ldac_stream(ax, stream)
        elif codec_id == [0x4F, 0x00, 0x00, 0x00, 0x01, 0x00]:
            plot_aptx_stream(ax, stream)
        elif codec_id == [0xD7, 0x00, 0x00, 0x00, 0x24, 0x00]:
            plot_aptx_hd_stream(ax, stream)
        elif codec_id == [0xE0, 0x00, 0x00, 0x00, 0x01, 0x00]:
            plot_opus_stream(ax, stream)
        else:
            raise ValueError(f"Unknown Vendor Codec Id {codec_id}")


def plot_sbc_stream(ax, stream: AvdtpStream):
    """Plot and extract an A2DP audio stream encoded with the SBC codec"""

    match stream.configuration.media_codec_specific_information_elements[0] & 0xf0:
        case 0x10:
            sampling_frequency = 48000.0
        case 0x20:
            sampling_frequency = 44100.0
        case 0x40:
            sampling_frequency = 32000.0
        case 0x80:
            sampling_frequency = 16000.0
        case _:
            raise ValueError("unknown SBC sampling frequency")

    match stream.configuration.media_codec_specific_information_elements[0] & 0x0f:
        case 0x01 | 0x02 | 0x04:
            nr_channels = 2
        case 0x08:
            nr_channels = 1
        case _:
            raise ValueError("unknown SBC channel mode")

    print(f"Plotting SBC stream {nr_channels}x{sampling_frequency}Hz")

    def count_frames(data: bytes) -> int:
        frame_count = 0

        while data:
            assert len(data) > 3, "frame header is 3 bytes"
            assert data[0] == 0x9c, f"invalid syncword : {data[0]:02x}"

            frame_count += 1

            sampling_frequency = (data[1] >> 6) & 0x3
            blocks             = (1 + ((data[1] >> 4) & 0x3)) * 4
            channel_mode       = (data[1] >> 2) & 0x3
            subbands           = (1 + ((data[1] >> 0) & 0x1)) * 4
            bitpool            = data[2]

            match channel_mode:
                case 0x00: # MONO
                    frame_len = 4 + (subbands >> 1) + ((blocks * bitpool + 7) >> 3)
                case 0x01: # DUAL_CHANNEL
                    frame_len = 4 + subbands + ((blocks * bitpool + 3) >> 2)
                case 0x02: # STEREO
                    frame_len = 4 + subbands + ((blocks * bitpool + 7) >> 3)
                case 0x03: # JOINT_STEREO
                    frame_len = 4 + subbands + ((subbands + blocks * bitpool + 7) >> 3)

            data = data[frame_len:]

        return frame_count

    started_ts = (
        np.datetime64(stream.started.packet.timestamp_us, "us")
        .item()
        .strftime("%H:%M:%S.%f")
    )
    f = open(f"stream_SBC_{int(sampling_frequency)}_{started_ts}.bt", "wb")

    current_stream_ts = 0
    real_ts = []
    stream_ts = []
    rtp_ts = []

    for packet in stream.packets:
        data = packet.payload
        (current_rtp_ts,) = struct.unpack(">I", data[4:8])

        real_ts.append(packet.packet.timestamp_us)
        stream_ts.append(current_stream_ts)
        rtp_ts.append(current_rtp_ts / sampling_frequency)

        f.write(data[13:])

        # SBC frames have a fixed number of samples (128),
        # but A2DP frames can have a variable number of SBC frames.
        frame_count = count_frames(data[13:])

        current_stream_ts += 128 * frame_count / sampling_frequency

    real_ts = np.array(real_ts)
    stream_ts = np.array(stream_ts) - (real_ts - real_ts[0]) / 1000000.0
    rtp_ts = np.array(rtp_ts) - rtp_ts[0] - (real_ts - real_ts[0]) / 1000000.0
    real_ts = np.array(real_ts, dtype="datetime64[us]")

    # Run ffmpeg to decode the AptX stream.
    f.close()
    subprocess.Popen([
        'ffmpeg',
        '-f', 'sbc', '-i', f'stream_SBC_{int(sampling_frequency)}_{started_ts}.bt',
        '-f', 'wav', f'stream_SBC_{int(sampling_frequency)}_{started_ts}.wav'])

    ax.plot(real_ts, stream_ts, color="blue")
    ax.plot(real_ts, rtp_ts, color="orange")


def plot_aac_stream(ax, stream: AvdtpStream):
    """Plot and extract an A2DP audio stream encoded with the AAC codec"""

    sampling_frequency = (
        stream.configuration.media_codec_specific_information_elements[1] |
        ((stream.configuration.media_codec_specific_information_elements[2] & 0xf0) << 8)
    )
    match sampling_frequency:
        case 0x01:
            sampling_frequency = 44100.0
        case 0x02:
            sampling_frequency = 32000.0
        case 0x04:
            sampling_frequency = 24000.0
        case 0x08:
            sampling_frequency = 22050.0
        case 0x10:
            sampling_frequency = 16000.0
        case 0x20:
            sampling_frequency = 12000.0
        case 0x40:
            sampling_frequency = 11025.0
        case 0x80:
            sampling_frequency = 8000.0
        case 0x1000:
            sampling_frequency = 96000.0
        case 0x2000:
            sampling_frequency = 88200.0
        case 0x4000:
            sampling_frequency = 64000.0
        case 0x8000:
            sampling_frequency = 48000.0
        case _:
            raise ValueError("unknown AAC sampling frequency")

    match stream.configuration.media_codec_specific_information_elements[2] & 0xf:
        case 0x04:
            nr_channels = 2
        case 0x08:
            nr_channels = 1
        case _:
            raise ValueError("unknown AAC channel mode")

    print(f"Plotting AAC stream {nr_channels}x{sampling_frequency}Hz")

    started_ts = (
        np.datetime64(stream.started.packet.timestamp_us, "us")
        .item()
        .strftime("%H:%M:%S.%f")
    )
    f = open(f"stream_AAC_{int(sampling_frequency)}_{started_ts}.bt", "wb")

    current_stream_ts = 0
    real_ts = []
    stream_ts = []
    rtp_ts = []

    for packet in stream.packets:
        data = packet.payload
        (current_rtp_ts,) = struct.unpack(">I", data[4:8])

        real_ts.append(packet.packet.timestamp_us)
        stream_ts.append(current_stream_ts)
        rtp_ts.append(current_rtp_ts / sampling_frequency)

        f.write(data[12:])

        # AAC frames have a fixed number of samples (1024).
        current_stream_ts += 1024 / sampling_frequency

    real_ts = np.array(real_ts)
    stream_ts = np.array(stream_ts) - (real_ts - real_ts[0]) / 1000000.0
    rtp_ts = np.array(rtp_ts) - rtp_ts[0] - (real_ts - real_ts[0]) / 1000000.0
    real_ts = np.array(real_ts, dtype="datetime64[us]")

    # Run ffmpeg to decode the AptX stream.
    f.close()
    subprocess.Popen([
        'ffmpeg',
        '-f', 'aac', '-i', f'stream_AAC_{int(sampling_frequency)}_{started_ts}.bt',
        '-f', 'wav', f'stream_AAC_{int(sampling_frequency)}_{started_ts}.wav'])

    ax.plot(real_ts, stream_ts, color="blue")
    ax.plot(real_ts, rtp_ts, color="orange")


def plot_ldac_stream(ax, stream: AvdtpStream):
    """Plot and extract an A2DP audio stream encoded with the LDAC codec"""

    match stream.configuration.media_codec_specific_information_elements[6]:
        case 0x01:
            sampling_frequency = 192000.0
        case 0x02:
            sampling_frequency = 176400.0
        case 0x04:
            sampling_frequency = 96000.0
        case 0x08:
            sampling_frequency = 88200.0
        case 0x10:
            sampling_frequency = 48000.0
        case 0x20:
            sampling_frequency = 44100.0
        case _:
            raise ValueError("unknown LDAC sampling frequency")

    match stream.configuration.media_codec_specific_information_elements[7]:
        case 0x01 | 0x02:
            nr_channels = 2
        case 0x04:
            nr_channels = 1
        case _:
            raise ValueError("unknown LDAC channel mode")

    print(f"Plotting LDAC stream {nr_channels}x{sampling_frequency}Hz")

    started_ts = (
        np.datetime64(stream.started.packet.timestamp_us, "us")
        .item()
        .strftime("%H:%M:%S.%f")
    )
    f = open(f"stream_LDAC_{int(sampling_frequency)}_{started_ts}.bt", "wb")

    current_stream_ts = 0
    real_ts = []
    stream_ts = []
    rtp_ts = []

    smpl_rate_ids = [44100.0, 48000.0, 88200.0, 96000.0]
    frame_sizes = [128, 128, 256, 256]

    for packet in stream.packets:
        data = packet.payload
        (current_rtp_ts,) = struct.unpack(">I", data[4:8])

        real_ts.append(packet.packet.timestamp_us)
        stream_ts.append(current_stream_ts)
        rtp_ts.append(current_rtp_ts / sampling_frequency)

        nr_samples = 0
        data = data[13:]

        f.write(data)

        while data:
            header = (data[0] << 16) | (data[1] << 8) | data[2]

            sync_word = (header >> 16) & 0xFF
            smpl_rate_id = (header >> 13) & 0x7
            ch_config = (header >> 11) & 0x3
            frame_len = (header >> 2) & 0x1FF
            frame_stat = (header >> 0) & 0x3

            assert sync_word == 0xAA
            assert smpl_rate_ids[smpl_rate_id] == sampling_frequency, (
                "Sampling frequency does not match session configuration:" +
                 f" {smpl_rate_ids[smpl_rate_id]} != {sampling_frequency}"
            )

            nr_samples += frame_sizes[smpl_rate_id]
            data = data[4 + frame_len :]

        current_stream_ts += nr_samples / sampling_frequency

    real_ts = np.array(real_ts)
    stream_ts = np.array(stream_ts) - (real_ts - real_ts[0]) / 1000000.0
    rtp_ts = np.array(rtp_ts) - rtp_ts[0] - (real_ts - real_ts[0]) / 1000000.0
    real_ts = np.array(real_ts, dtype="datetime64[us]")

    ax.plot(real_ts, stream_ts, color="blue")
    ax.plot(real_ts, rtp_ts, color="orange")


def plot_aptx_stream(ax, stream: AvdtpStream):
    """Plot and extract an A2DP audio stream encoded with the AptX codec"""

    sampling_frequency = (
        stream.configuration.media_codec_specific_information_elements[6] >> 4
    ) & 0xF
    channel_mode = (
        stream.configuration.media_codec_specific_information_elements[6]
    ) & 0xF

    match sampling_frequency:
        case 0x01:
            sampling_frequency = 48000.0
        case 0x02:
            sampling_frequency = 44100.0
        case 0x04:
            sampling_frequency = 32000.0
        case 0x08:
            sampling_frequency = 16000.0
        case _:
            raise ValueError("unknown AptX sampling frequency")

    match channel_mode:
        case 0x01:
            channel_mode = 0
        case 0x02:
            channel_mode = 1
        case 0x04:
            channel_mode = 2
        case 0x08:
            channel_mode = 3
        case _:
            raise ValueError("unknown AptX channel mode")

    channel_modes = ["Joint Stereo", "Stereo", "Dual Channel", "Mono"]

    print(f"Plotting AptX stream {channel_modes[channel_mode]} @{sampling_frequency}Hz")

    started_ts = (
        np.datetime64(stream.started.packet.timestamp_us, "us")
        .item()
        .strftime("%H:%M:%S.%f")
    )
    f = open(f"stream_AptX_{int(sampling_frequency)}_{started_ts}.bt", "wb")

    current_stream_ts = 0
    real_ts = []
    stream_ts = []
    rtp_ts = []

    for packet in stream.packets:
        # There is no RTP header for AptX classic.
        data = packet.payload

        real_ts.append(packet.packet.timestamp_us)
        stream_ts.append(current_stream_ts)

        f.write(data)

        # The compression ratio is 4:1 for AptX classic.
        # The input stream is 16-bits per sample, with two channels that
        # represents 1 encoded byte per sample.
        # In theory AptX could encode mono stream but it does not seem
        # to be supported by the AOSP integration.
        nr_samples = len(data)
        current_stream_ts += nr_samples / sampling_frequency

    real_ts = np.array(real_ts)
    stream_ts = np.array(stream_ts) - (real_ts - real_ts[0]) / 1000000.0
    real_ts = np.array(real_ts, dtype="datetime64[us]")

    # Run ffmpeg to decode the AptX stream.
    f.close()
    subprocess.Popen([
        'ffmpeg',
        '-f', 'aptx', '-i', f'stream_AptX_{int(sampling_frequency)}_{started_ts}.bt',
        '-f', 'wav', f'stream_AptX_{int(sampling_frequency)}_{started_ts}.wav'])

    ax.plot(real_ts, stream_ts, color="blue")


def plot_aptx_hd_stream(ax, stream: AvdtpStream):
    sampling_frequency = (
        stream.configuration.media_codec_specific_information_elements[6] >> 4
    ) & 0xF
    channel_mode = (
        stream.configuration.media_codec_specific_information_elements[6]
    ) & 0xF

    match sampling_frequency:
        case 0x01:
            sampling_frequency = 48000.0
        case 0x02:
            sampling_frequency = 44100.0
        case 0x04:
            sampling_frequency = 32000.0
        case 0x08:
            sampling_frequency = 16000.0
        case _:
            raise ValueError("unknown AptX HD sampling frequency")

    match channel_mode:
        case 0x01:
            channel_mode = 0
        case 0x02:
            channel_mode = 1
        case 0x04:
            channel_mode = 2
        case 0x08:
            channel_mode = 3
        case _:
            raise ValueError("unknown AptX HD channel mode")

    channel_modes = ["Joint Stereo", "Stereo", "Dual Channel", "Mono"]

    print(
        f"Plotting AptX HD stream {channel_modes[channel_mode]} @{sampling_frequency}Hz"
    )

    started_ts = (
        np.datetime64(stream.started.packet.timestamp_us, "us")
        .item()
        .strftime("%H:%M:%S.%f")
    )
    f = open(f"stream_AptX_HD_{int(sampling_frequency)}_{started_ts}.bt", "wb")

    current_stream_ts = 0
    real_ts = []
    stream_ts = []
    rtp_ts = []

    for packet in stream.packets:
        data = packet.payload
        (current_rtp_ts,) = struct.unpack(">I", data[4:8])

        real_ts.append(packet.packet.timestamp_us)
        stream_ts.append(current_stream_ts)
        rtp_ts.append(current_rtp_ts / sampling_frequency)

        f.write(data[12:])

        # The compression ratio is 3:1 for AptX HD.
        # The input stream is 16-bits per sample, with two channels that
        # represents 1 encoded byte per sample.
        # In theory AptX could encode mono stream but it does not seem
        # to be supported by the AOSP integration.
        nr_samples = len(data[12:]) / 1.5
        current_stream_ts += nr_samples / sampling_frequency

    real_ts = np.array(real_ts)
    stream_ts = np.array(stream_ts) - (real_ts - real_ts[0]) / 1000000.0
    rtp_ts = np.array(rtp_ts) - rtp_ts[0] - (real_ts - real_ts[0]) / 1000000.0
    real_ts = np.array(real_ts, dtype="datetime64[us]")

    # Run ffmpeg to decode the AptX HD stream.
    f.close()
    subprocess.Popen([
        'ffmpeg',
        '-f', 'aptx_hd', '-i', f'stream_AptX_HD_{int(sampling_frequency)}_{started_ts}.bt',
        '-f', 'wav', f'stream_AptX_HD_{int(sampling_frequency)}_{started_ts}.wav'])

    ax.plot(real_ts, stream_ts, color="blue")
    ax.plot(real_ts, rtp_ts, color="orange")


def plot_opus_stream(ax, stream: AvdtpStream):
    """Plot and extract an A2DP audio stream encoded with the Opus codec"""

    match stream.configuration.media_codec_specific_information_elements[6] & 0x80:
        case 0x80:
            sampling_frequency = 48000.0
        case _:
            raise ValueError("unknown Opus sampling frequency")

    match stream.configuration.media_codec_specific_information_elements[6] & 0x07:
        case 0x01 | 0x04:
            nr_channels = 2
        case 0x02:
            nr_channels = 1
        case _:
            raise ValueError("unknown Opus channel mode")

    match stream.configuration.media_codec_specific_information_elements[6] & 0x18:
        case 0x08:
            frame_size = 0.010
        case 0x10:
            frame_size = 0.020
        case _:
            raise ValueError("unknown Opus frame size")

    print(f"Plotting Opus stream {nr_channels}x{sampling_frequency}Hz")

    started_ts = (
        np.datetime64(stream.started.packet.timestamp_us, "us")
        .item()
        .strftime("%H:%M:%S.%f")
    )
    f = open(f"stream_Opus_{int(sampling_frequency)}_{started_ts}.bt", "wb")

    current_stream_ts = 0
    real_ts = []
    stream_ts = []
    rtp_ts = []

    for packet in stream.packets:
        data = packet.payload
        (current_rtp_ts,) = struct.unpack(">I", data[4:8])

        real_ts.append(packet.packet.timestamp_us)
        stream_ts.append(current_stream_ts)
        rtp_ts.append(current_rtp_ts / sampling_frequency)

        f.write(data)

        current_stream_ts += frame_size

    real_ts = np.array(real_ts)
    stream_ts = np.array(stream_ts) - (real_ts - real_ts[0]) / 1000000.0
    rtp_ts = np.array(rtp_ts) - rtp_ts[0] - (real_ts - real_ts[0]) / 1000000.0
    real_ts = np.array(real_ts, dtype="datetime64[us]")

    ax.plot(real_ts, stream_ts, color="blue")
    ax.plot(real_ts, rtp_ts, color="orange")


def plot_tx_queue(ax, acl_connection: btsnoop.AclConnection):
    """Plot the depth of the HCI Tx queue."""

    snoop = acl_connection.btsnoop
    connected = acl_connection.connected
    disconnected = acl_connection.disconnected

    # Contents of the TX queue.
    # The offset represents the number of packets that were present in the
    # queue before the stream started, in the case of truncated snoop logs.
    tx_queue_offset = 0
    tx_queue = []

    real_ts = []
    tx_delay = []

    for packet in snoop.packets:
        if connected and packet.timestamp_us < connected.timestamp_us:
            continue
        if disconnected and packet.timestamp_us > disconnected.timestamp_us:
            continue

        if (packet.idc == btsnoop.Idc.EVENT and
            packet.payload[0] == btsnoop.EventCode.NUMBER_OF_COMPLETED_PACKETS):
            num_handles = packet.payload[2]

            for n in range(0, num_handles):
                connection_handle, num_completed_packets = struct.unpack(
                    "<HH", packet.payload[3 + n * 4: 7 + n * 4]
                )

                if connection_handle == acl_connection.connection_handle:
                    for _ in range(0, num_completed_packets):
                        if tx_queue:
                            acked = tx_queue.pop(0)
                            real_ts.append(acked.timestamp_us)
                            tx_delay.append(packet.timestamp_us - acked.timestamp_us)
                        else:
                            tx_queue_offset += 1

        elif (packet.idc == btsnoop.Idc.ACL_DATA and
              packet.direction == btsnoop.Direction.SENT):
            (connection_handle,) = struct.unpack("<H", packet.payload[:2])
            connection_handle &= 0xFFF

            if connection_handle == acl_connection.connection_handle:
                tx_queue.append(packet)

    real_ts = np.array(real_ts)
    real_ts = np.array(real_ts, dtype="datetime64[us]")
    tx_delay = np.array(tx_delay)

    ax.scatter(real_ts, tx_delay / 1000000.0, color="green", marker=".")


def generate_media_codec_capability(codec_type: Optional[str],
                                    sampling_frequency: Optional[int]) -> avdtp.MediaCodecCapability:
    match codec_type:
        case 'sbc':
            return avdtp.MediaCodecCapability(
                media_codec_type=0x0,
                media_codec_specific_information_elements=[
                    # XXX
                ],
            )
        case 'aac':
            return avdtp.MediaCodecCapability(
                media_codec_type=0x2,
                media_codec_specific_information_elements=[
                    # XXX
                ],
            )
        case 'ldac':
            sampling_frequencies = dict([
                (192000, 0x01),
                (176000, 0x02),
                (96000, 0x04),
                (88200, 0x08),
                (48000, 0x10),
                (44100, 0x20)
            ])
            if sampling_frequency not in sampling_frequencies:
                raise ValueError(f"unknown sampling frequency {sampling_frequency}")
            return avdtp.MediaCodecCapability(
                media_codec_type=0xff,
                media_codec_specific_information_elements=[
                    0x2D, 0x01, 0x00, 0x00, 0xAA, 0x00,
                    sampling_frequencies[sampling_frequency],
                    0x01, # Stereo
                ],
            )
        case 'aptx':
            sampling_frequencies = dict([
                (48000, 0x10),
                (44100, 0x20),
                (32000, 0x40),
                (16000, 0x80),
            ])
            if sampling_frequency not in sampling_frequencies:
                raise ValueError(f"unknown sampling frequency {sampling_frequency}")
            return avdtp.MediaCodecCapability(
                media_codec_type=0xff,
                media_codec_specific_information_elements=[
                    0x4F, 0x00, 0x00, 0x00, 0x01, 0x00,
                    sampling_frequencies[sampling_frequency] | 0x01,
                ],
            )
        case 'aptx_hd':
            sampling_frequencies = dict([
                (48000, 0x10),
                (44100, 0x20),
                (32000, 0x40),
                (16000, 0x80),
            ])
            if sampling_frequency not in sampling_frequencies:
                raise ValueError(f"unknown sampling frequency {sampling_frequency}")
            return avdtp.MediaCodecCapability(
                media_codec_type=0xff,
                media_codec_specific_information_elements=[
                    0xD7, 0x00, 0x00, 0x00, 0x24, 0x00,
                    sampling_frequencies[sampling_frequency] | 0x01,
                ],
            )
        case 'opus':
            assert False, "TODO opus support"
            return avdtp.MediaCodecCapability(
                media_codec_type=0xff,
                media_codec_specific_information_elements=[
                    0xE0, 0x00, 0x00, 0x00, 0x01, 0x00,
                    # XXX
                ],
            )
        case _:
            raise ValueError(f"unknown codec type '{codec_type}'")


def plot_acl_connection(acl_connection: btsnoop.AclConnection,
                        signal_lcid: Optional[int] = None,
                        signal_rcid: Optional[int] = None,
                        stream_cid: Optional[int] = None,
                        codec_type: Optional[str] = None,
                        sampling_frequency: Optional[int] = None,
                        **kwargs):

    try:
        # Prepare packets for ACL parsing.
        acl_packets = [AclPacket.parse(packet) for packet in acl_connection.packets]
    except Exception as exn:
        # Some connection handles are used to report vendor events.
        # The format in this case is not compatible with L2CAP PDU.
        return

    # AVDTP state.
    session = None
    all_streams = []
    active_stream = None
    pending_connection = None

    # If override parameters are present:
    # generate the initial session state based on the provided parameters.
    if stream_cid:
        print(f"Overriding the session with stream CID 0x{stream_cid:04x}")
        session = AvdtpSession(signal_lcid or 0x00, signal_rcid or 0x00)
        session.stream_cid = stream_cid
        session.configuration = AvdtpSignalingPacket(
            packet=None,
            channel_id=0,
            direction=btsnoop.Direction.SENT,
            payload=bytes(),
            signal=avdtp.SetConfigurationCommand(
                acp_seid=0,
                int_seid=0,
                service_capabilities=[
                    generate_media_codec_capability(
                        codec_type or 'ldac',
                        sampling_frequency or 96000,
                    )
                ],
            ))
        active_stream = AvdtpStream(
            generate_media_codec_capability(
                codec_type or 'ldac',
                sampling_frequency or 96000,
            ),
            acl_packets[0])
        all_streams = [active_stream]

    started_ts = acl_connection.connected.timestamp if acl_connection.connected else acl_packets[0].packet.timestamp
    print(f"---- {started_ts} | 0x{acl_connection.connection_handle:04x} ----")

    for packet in acl_packets:
        if isinstance(packet, L2capSignalingPacket):
            signal = packet.signal
            identifier = signal.identifier

            if isinstance(signal, l2cap.ConnectionRequest):
                # Exchanged L2CAP Connection Request.
                # Filter out requests for PSMs other than the AVDTP PSM 0x19,
                # and check for connection collision.
                if signal.psm != 0x19:
                    continue
                if pending_connection:
                    raise Exception(f"L2CAP AVDTP Channel collision")
                log(packet.packet.timestamp, "L2CAP connection request")
                pending_connection = packet

            elif isinstance(signal, l2cap.ConnectionResponse):
                # Exchanged L2CAP Connection Response.
                # Check the command identifier, and update the AVDTP
                # state based on the status.
                if (
                    not pending_connection
                    or identifier != pending_connection.signal.identifier
                    or packet.direction == pending_connection.direction
                ):
                    continue

                destination_cid = signal.destination_cid
                source_cid = signal.source_cid

                if packet.direction == Direction.SENT:
                    destination_cid, source_cid = source_cid, destination_cid

                if signal.result == 0:
                    # The L2CAP connection request is accepted, and
                    # either the signaling or transport channel is open.
                    if session:
                        log(packet.packet.timestamp, f"AVDTP transport channel connected (0x{destination_cid:x})")
                        assert not session.stream_cid
                        session.stream_cid = destination_cid
                        pending_connection = None
                    else:
                        log(packet.packet.timestamp, f"AVDTP signaling channel connected (0x{source_cid:x} - 0x{destination_cid:x})")
                        session = AvdtpSession(source_cid, destination_cid)
                        pending_connection = None

                elif signal.result == 1:
                    # The L2CAP connection request is pending.
                    pass

                else:
                    pending_connection = None

            elif isinstance(signal, l2cap.DisconnectionRequest):
                pass

            elif isinstance(signal, l2cap.DisconnectionResponse):
                # Exchanged L2CAP Disconnection Response.
                # Check if the signaling or transport channel is being
                # disconnected, and update the state based on this.
                destination_cid = signal.destination_cid
                source_cid = signal.source_cid

                if packet.direction == Direction.SENT:
                    destination_cid, source_cid = source_cid, destination_cid

                if session and destination_cid == session.stream_cid:
                    log(packet.packet.timestamp, "AVDTP transport channel disconnected")
                    session.stream_cid = None

                if session and destination_cid == session.remote_cid:
                    log(packet.packet.timestamp, "AVDTP signaling channel disconnected")
                    assert not session.stream_cid
                    session = None
            else:
                pass

        elif (
            packet.direction == Direction.RCVD
            and session
            and packet.channel_id == session.local_cid
        ):
            packet = AvdtpSignalingPacket.parse(packet)
            log(packet.packet.timestamp, f"<-- {type(packet.signal).__name__}")

            if (isinstance(packet.signal, avdtp.SetConfigurationCommand) or
                isinstance(packet.signal, avdtp.ReconfigureCommand)):
                session.configuration = packet

            elif isinstance(packet.signal, avdtp.StartResponse):
                media_codec_capability = None
                for (
                    service_capability
                ) in session.configuration.signal.service_capabilities:
                    if isinstance(service_capability, avdtp.MediaCodecCapability):
                        media_codec_capability = service_capability

                if not media_codec_capability:
                    raise ValueError("Media Codec Capability not found")

                active_stream = AvdtpStream(media_codec_capability, packet)
                all_streams.append(active_stream)

            elif isinstance(packet.signal, avdtp.SuspendResponse):
                active_stream.suspended = packet
                active_stream = None

        elif (
            packet.direction == Direction.SENT
            and session
            and packet.channel_id == session.remote_cid
        ):
            packet = AvdtpSignalingPacket.parse(packet)
            log(packet.packet.timestamp, f"--> {type(packet.signal).__name__}")

            if (isinstance(packet.signal, avdtp.SetConfigurationCommand) or
                isinstance(packet.signal, avdtp.ReconfigureCommand)):
                session.configuration = packet

            elif isinstance(packet.signal, avdtp.StartResponse):
                media_codec_capability = None
                for (
                    service_capability
                ) in session.configuration.signal.service_capabilities:
                    if isinstance(service_capability, avdtp.MediaCodecCapability):
                        media_codec_capability = service_capability

                if not media_codec_capability:
                    raise ValueError("Media Codec Capability not found")

                active_stream = AvdtpStream(media_codec_capability, packet)
                all_streams.append(active_stream)

        elif (
            packet.direction == Direction.SENT
            and session
            and packet.channel_id == session.stream_cid
        ):
            assert active_stream
            active_stream.packets.append(packet)

    if not all_streams:
        return

    print(f"Extracted {len(all_streams)} audio streams")

    fig, axs = plt.subplots(2, sharex=True)

    axs[0].xaxis.set_major_formatter(matplotlib.dates.DateFormatter("%H:%M:%S.%f"))
    axs[0].xaxis.set_tick_params(rotation=45)
    axs[1].xaxis.set_major_formatter(matplotlib.dates.DateFormatter("%H:%M:%S.%f"))
    axs[1].xaxis.set_tick_params(rotation=45)

    for stream in all_streams:
        if stream.suspended and stream.started:
            start_ts = stream.started.packet.timestamp_us
            suspend_ts = stream.suspended.packet.timestamp_us
            axs[0].axvspan(np.datetime64(start_ts, 'us'), np.datetime64(suspend_ts, 'us'), color='lightblue')
            axs[1].axvspan(np.datetime64(start_ts, 'us'), np.datetime64(suspend_ts, 'us'), color='lightblue')

    for stream in all_streams:
        plot_avdtp_stream(axs[0], stream)

    plot_tx_queue(axs[1], acl_connection)

    plt.show()
