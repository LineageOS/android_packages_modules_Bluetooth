import btsnoop
import dataclasses
import enum
from packets import avdtp
from packets import l2cap
from typing import Optional, List, Iterator
from btsnoop import Direction, Idc, EventCode, LeMetaEventCode, VendorSpecificEventCode
import struct
import subprocess

import numpy as np
import matplotlib
import matplotlib.pyplot as plt
from colorama import Fore, Style


def log(ts, msg):
    print(Fore.CYAN + f"  {ts}" + Style.RESET_ALL + f" | {msg}")


@dataclasses.dataclass
class IsoLinkFeedback:
    packet: btsnoop.Packet
    connection_handle: int
    sequence_number: int
    anchor_point_delay: int
    in_status: int
    tx_status: int

    @property
    def timestamp(self):
        return self.packet.timestamp


@dataclasses.dataclass
class NumberOfCompletedPackets:
    packet: btsnoop.Packet
    connection_handle: int
    num_completed_packets: int

    @property
    def timestamp(self):
        return self.packet.timestamp


@dataclasses.dataclass
class IsoData:
    packet: btsnoop.Packet
    connection_handle: int
    packet_length: int
    sequence_number: int
    sdu_length: int

    @property
    def timestamp(self):
        return self.packet.timestamp


@dataclasses.dataclass
class CisConnection:
    connection_handle: int
    t0: int
    packets: list


def plot_cis_connections(snoop: btsnoop.Btsnoop, plot: bool = False, extract: bool = False, **kwargs):
    """Plot CIS stream information."""

    # Pre-parse ISO data packets and ISO_LINK_FEEDBACK events.
    all_packets = []
    cis_connections = []
    active_cis_connections = dict()

    for packet in snoop.packets:
        match packet.idc:
            case Idc.EVENT:
                event_code = packet.payload[0]
                subevent_code = packet.payload[2]

                match event_code:
                    case EventCode.LE_META_EVENT:
                        match subevent_code:
                            case LeMetaEventCode.LE_CIS_ESTABLISHED_V1:
                                (connection_handle,) = struct.unpack("<H", packet.payload[4:6])
                                log(packet.timestamp, f'LE_CIS_ESTABLISHED_V1 0x{connection_handle:04x}')
                                assert not connection_handle in active_cis_connections

                                cis = CisConnection(connection_handle, packet.timestamp_us, [])
                                cis_connections.append(cis)
                                active_cis_connections[connection_handle] = cis

                            case LeMetaEventCode.LE_CIS_ESTABLISHED_V2:
                                log(packet.timestamp, 'LE_CIS_ESTABLISHED_V2')
                            case _:
                                pass

                    case EventCode.VENDOR_SPECIFIC_EVENT:
                        match subevent_code:
                            case VendorSpecificEventCode.ISO_LINK_FEEDBACK:
                                (
                                    connection_handle,
                                    sequence_number,
                                    anchor_point_delay,
                                    in_status,
                                    tx_status,
                                ) = struct.unpack("<HHHHH", packet.payload[3:])

                                event = IsoLinkFeedback(packet, connection_handle,
                                        sequence_number, anchor_point_delay,
                                        in_status, tx_status)

                                all_packets.append(event)
                                if cis := active_cis_connections.get(connection_handle):
                                    cis.packets.append(event)

                            case _:
                                pass

                    case EventCode.NUMBER_OF_COMPLETED_PACKETS:
                        num_handles = packet.payload[2]
                        for n in range(0, num_handles):
                            (
                                connection_handle,
                                num_completed_packets,
                            ) = struct.unpack("<HH", packet.payload[3 + n * 4: 7 + n * 4])

                            event = NumberOfCompletedPackets(
                                packet, connection_handle, num_completed_packets)

                            all_packets.append(event)
                            if cis := active_cis_connections.get(connection_handle):
                                cis.packets.append(event)

                    case EventCode.DISCONNECTION_COMPLETE:
                        (status, connection_handle, _) = struct.unpack(
                            "<BHB", packet.payload[2:]
                        )
                        log(packet.timestamp, f'DISCONNECTION_COMPLETE 0x{connection_handle:04x}')
                        if connection_handle in active_cis_connections:
                            del active_cis_connections[connection_handle]

                    case _:
                        pass

            case Idc.ISO_DATA:
                (
                    connection_handle,
                    data_length,
                    sequence_number,
                    sdu_length,
                ) = struct.unpack("<HHHH", packet.payload[:8])
                connection_handle = connection_handle & 0xfff

                data = IsoData(packet, connection_handle, data_length,
                        sequence_number, sdu_length)

                all_packets.append(data)
                if cis := active_cis_connections.get(connection_handle):
                    cis.packets.append(data)
                else:
                    cis = CisConnection(connection_handle, packet.timestamp_us, [])
                    cis_connections.append(cis)
                    active_cis_connections[connection_handle] = cis
                    cis.packets.append(data)

            case _:
                pass

    # Check for skips in the sequence numbers.
    # Check for packets falsely reported as missing.
    for cis in cis_connections:
        data_sequence_number = -1
        feedback_sequence_number = 0

        data_ts = dict()

        for packet in cis.packets:
            match packet:
                case IsoData(_):
                    # log(packet.timestamp, f'DATA {packet.sequence_number}')

                    if packet.sequence_number != data_sequence_number + 1:
                        log(packet.timestamp, 'incorrect ISO_DATA.Sequence_Number ' +
                            f'{packet.sequence_number} != {data_sequence_number + 1}')
                    data_ts[packet.sequence_number] = packet
                    data_sequence_number = packet.sequence_number

                case IsoLinkFeedback(_):
                    # log(packet.timestamp, f'FEED {packet.sequence_number}')

                    if packet.sequence_number != feedback_sequence_number + 1:
                        log(packet.timestamp, 'incorrect ISO_LINK_FEEDBACK.Sequence_Number ' +
                            f'{packet.sequence_number} != {feedback_sequence_number + 1}')
                    feedback_sequence_number = packet.sequence_number

                    if packet.in_status != 1:
                        log(packet.timestamp, 'unexpected ISO_LINK_FEEDBACK.In_Status ' +
                            f'{packet.in_status:b} (Sequence_Number {packet.sequence_number})')

                        if packet.sequence_number in data_ts:
                            log(packet.timestamp, f' --> packet was sent at {data_ts[packet.sequence_number].timestamp}')

                    if packet.tx_status != 1:
                        log(packet.timestamp, 'unexpected ISO_LINK_FEEDBACK.Tx_Status ' +
                            f'{packet.tx_status:b} (Sequence_Number {packet.sequence_number})')

    # Extract audio.
    # ./opus_demo -d 96000 2 -24 <encoded_bit_file.bit> <decoded_24bit_raw_audio_file.raw>
    if extract:
        for cis in cis_connections:
            with open(f'stream_Opus_HiRes_{cis.packets[0].timestamp}.bt', 'wb') as f:
                for packet in cis.packets:
                    if isinstance(packet, IsoData):
                        # Write packet header.
                        f.write(struct.pack('>I', 1255))
                        f.write(b'\x00\x00\x00\x00')
                        # Write packet data.
                        f.write(packet.packet.payload[8:])

    # Plot the deviation from the CIS clock for ISO_LINK_FEEDBACK and
    # ISO_DATA.
    if plot:
        fig, axs = plt.subplots(2, sharex=True)

        axs[0].xaxis.set_major_formatter(matplotlib.dates.DateFormatter("%H:%M:%S.%f"))
        axs[0].xaxis.set_tick_params(rotation=45)

        for cis in cis_connections:
            data_ts = []
            data_delay = []
            feedback_ts = []
            feedback_delay = []
            anchor_point_delay = []

            data_rollover_count = 0
            feedback_rollover_count = 0

            for packet in cis.packets:
                match packet:
                    case IsoData(_):
                        if packet.sequence_number == 0:
                            data_rollover_count += 1
                        sequence_number = packet.sequence_number + 0x10000 * data_rollover_count
                        data_ts.append(packet.packet.timestamp_us)
                        data_delay.append(packet.packet.timestamp_us - (cis.t0 + sequence_number * 20000))

                    case IsoLinkFeedback(_):
                        if packet.sequence_number == 0:
                            feedback_rollover_count += 1
                        sequence_number = packet.sequence_number + 0x10000 * feedback_rollover_count
                        feedback_ts.append(packet.packet.timestamp_us)
                        feedback_delay.append(packet.packet.timestamp_us - packet.anchor_point_delay - (cis.t0 + sequence_number * 20000))
                        anchor_point_delay.append(packet.anchor_point_delay)

            data_ts = np.array(data_ts, dtype="datetime64[us]")
            data_delay = np.array(data_delay)
            feedback_ts = np.array(feedback_ts, dtype="datetime64[us]")
            feedback_delay = np.array(feedback_delay)
            anchor_point_delay = np.array(anchor_point_delay)

            data_delay = (data_delay - data_delay[0]) / 1000000.0
            feedback_delay = (feedback_delay - feedback_delay[0]) / 1000000.0
            anchor_point_delay = anchor_point_delay / 1000000.0

            axs[0].scatter(data_ts, data_delay, color="blue", marker=".")
            axs[0].scatter(feedback_ts, feedback_delay, color="orange", marker=".")
            axs[1].scatter(feedback_ts, anchor_point_delay, color="green", marker=".")

        plt.show()
