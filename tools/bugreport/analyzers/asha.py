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
            if channel_id in [0x0001, 0x0005]
            else acl_packet
        )


@dataclasses.dataclass
class L2capSignalingPacket(AclPacket):
    signal: l2cap.SignalingPacket

    @staticmethod
    def parse(packet: AclPacket):
        assert packet.channel_id in [0x0001, 0x0005]
        return L2capSignalingPacket(
            packet.packet,
            packet.channel_id,
            packet.direction,
            packet.payload,
            l2cap.SignalingPacket.parse_all(packet.payload),
        )


@dataclasses.dataclass
class AshaStream:
    opened: AclPacket
    packets: List[AclPacket] = dataclasses.field(default_factory=list)
    acks: List[AclPacket] = dataclasses.field(default_factory=list)


def plot_asha_stream(ax, stream: AshaStream):
    if not stream.packets:
        return

    started_ts = (
        np.datetime64(stream.opened.packet.timestamp_us, "us")
        .item()
        .strftime("%H:%M:%S.%f")
    )
    f = open(f"stream_g722_{started_ts}.bt", "wb")

    current_stream_ts = 0
    real_ts = []
    stream_ts = []

    for packet in stream.packets:
        data = packet.payload

        real_ts.append(packet.packet.timestamp_us)
        stream_ts.append(current_stream_ts)

        f.write(data)

        # ASHA frames have a fixed interval of 20ms.
        current_stream_ts += 0.020

    real_ts = np.array(real_ts)
    stream_ts = np.array(stream_ts) - (real_ts - real_ts[0]) / 1000000.0
    real_ts = np.array(real_ts, dtype="datetime64[us]")

    f.close()
    ax.plot(real_ts, stream_ts, color="blue")

    current_stream_ts = 0
    real_ts = []
    stream_ts = []

    for packet in stream.acks:
        real_ts.append(packet.packet.timestamp_us)
        stream_ts.append(current_stream_ts)

        # ASHA frames have a fixed interval of 20ms.
        current_stream_ts += 0.020 * packet.signal.credits

    real_ts = np.array(real_ts)
    stream_ts = np.array(stream_ts) - (real_ts - real_ts[0]) / 1000000.0
    real_ts = np.array(real_ts, dtype="datetime64[us]")

    ax.plot(real_ts, stream_ts, color="orange")


def plot_credit_count(ax, stream: AshaStream):
    tx_queue = []

    real_ts = []
    tx_delay = []

    packets = stream.packets + stream.acks
    packets = [(p.packet.timestamp_us, p) for p in packets]

    for (timestamp_us, packet) in sorted(packets):
        if isinstance(packet, L2capSignalingPacket):
            for _ in range(0, packet.signal.credits):
                acked = tx_queue.pop(0)
                real_ts.append(acked.packet.timestamp_us)
                tx_delay.append(timestamp_us - acked.packet.timestamp_us)
        else:
            tx_queue.append(packet)

    real_ts = np.array(real_ts, dtype="datetime64[us]")
    tx_delay = np.array(tx_delay)

    ax.scatter(real_ts, tx_delay / 1000000.0, color="orange", marker=".")


def plot_acl_connection(acl_connection: btsnoop.AclConnection,
                        psm: Optional[int] = None,
                        **kwargs):
    # 0x80 is a common PSM value for ASHA stream channels.
    psm = psm or 0x80

    # Prepare packets for ACL parsing.
    acl_packets = [AclPacket.parse(packet) for packet in acl_connection.packets]

    # ASHA state
    all_streams = []
    active_stream = None
    stream_cid = 0
    pending_connection = None

    started_ts = acl_connection.connected.timestamp if acl_connection.connected else acl_packets[0].packet.timestamp
    print(f"---- {started_ts} | 0x{acl_connection.connection_handle:04x} ----")

    for packet in acl_packets:
        if isinstance(packet, L2capSignalingPacket):
            signal = packet.signal
            identifier = signal.identifier

            if isinstance(signal, l2cap.LeCreditBasedConnectionRequest):
                # Exchanged L2CAP Connection Request.
                # Filter out requests for PSMs other than the AVDTP PSM 0x19,
                # and check for connection collision.
                if signal.psm != psm:
                    continue
                if pending_connection:
                    raise Exception(f"L2CAP ASHA Channel collision")
                log(packet.packet.timestamp, "L2CAP connection request")
                pending_connection = packet

            elif isinstance(signal, l2cap.LeCreditBasedConnectionResponse):
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
                source_cid = pending_connection.signal.source_cid

                if packet.direction == Direction.SENT:
                    destination_cid, source_cid = source_cid, destination_cid

                stream_cid = destination_cid

                if signal.result == 0:
                    # The L2CAP connection request is accepted, and
                    # either the signaling or transport channel is open.
                    log(packet.packet.timestamp, f"ASHA stream channel connected 0x{stream_cid:x}")
                    active_stream = AshaStream(packet)
                    all_streams.append(active_stream)
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

                if destination_cid == stream_cid:
                    log(packet.packet.timestamp, f"ASHA stream channel disconnected 0x{stream_cid:x}")
                    active_stream = None
                    stream_cid = 0

            elif isinstance(signal, l2cap.LeFlowControlCreditInd):
                if packet.direction == Direction.RCVD and signal.cid == stream_cid:
                    active_stream.acks.append(packet)

            else:
                pass

        elif (
            packet.direction == Direction.SENT
            and packet.channel_id == stream_cid
        ):
            active_stream.packets.append(packet)

    print(f"Extracted {len(all_streams)} audio streams")

    fig, axs = plt.subplots(2, sharex=True)
    axs[0].xaxis.set_major_formatter(matplotlib.dates.DateFormatter("%H:%M:%S.%f"))
    axs[0].xaxis.set_tick_params(rotation=45)

    for stream in all_streams:
        plot_asha_stream(axs[0], stream)
        plot_credit_count(axs[1], stream)

    plt.show()
