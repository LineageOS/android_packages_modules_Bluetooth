from packets import avdtp
import dataclasses
import enum
import itertools
import numpy as np
import struct
from typing import List, Optional, Set, Iterator


uuid_counter = 1


def next_uuid() -> int:
    global uuid_counter
    uuid = uuid_counter
    uuid_counter += 1
    return uuid


class Direction(enum.IntEnum):
    SENT = 0
    RCVD = 1


class Idc(enum.IntEnum):
    COMMAND = 1
    ACL_DATA = 2
    SCO_DATA = 3
    EVENT = 4
    ISO_DATA = 5


class OpCode(enum.IntEnum):
    RESET = 0x0C03


class EventCode(enum.IntEnum):
    CONNECTION_COMPLETE = 0x03
    DISCONNECTION_COMPLETE = 0x05
    REMOTE_NAME_REQUEST_COMPLETE = 0x07
    NUMBER_OF_COMPLETED_PACKETS = 0x13
    LE_META_EVENT = 0x3E
    VENDOR_SPECIFIC_EVENT = 0xFF


class LeMetaEventCode(enum.IntEnum):
    LE_CONNECTION_COMPLETE = 0x01
    LE_ENHANCED_CONNECTION_COMPLETE = 0x0A
    LE_CIS_ESTABLISHED_V1 = 0x19
    LE_CIS_ESTABLISHED_V2 = 0x2A


class VendorSpecificEventCode(enum.IntEnum):
    ISO_LINK_FEEDBACK = 0x5C


class L2capCommandCode(enum.IntEnum):
    COMMAND_REJECT = 0x01
    CONNECTION_REQUEST = 0x02
    CONNECTION_RESPONSE = 0x03
    DISCONNECTION_REQUEST = 0x06
    DISCONNECTION_RESPONSE = 0x07


@dataclasses.dataclass
class Packet:
    number: int
    direction: Direction
    idc: Idc
    timestamp_us: int
    payload: bytes
    continuing_fragments: List["Packet"] = dataclasses.field(default_factory=list)

    @property
    def timestamp(self):
        return np.datetime64(self.timestamp_us, "us").item().strftime("%H:%M:%S.%f")


@dataclasses.dataclass
class AclConnection:
    """Save all packets sent or received on an ACL connection."""

    btsnoop: 'Btsnoop'
    connection_handle: int
    uuid: int = dataclasses.field(default_factory=next_uuid)
    remote_name: Optional[str] = None
    bd_addr: Optional[bytes] = None
    connected: Optional[Packet] = None
    disconnected: Optional[Packet] = None
    packets: List[Packet] = dataclasses.field(default_factory=list)

    def __str__(self):
        return """ACL Connection ({remote_name}):
    connection_handle: 0x{connection_handle:04x}
    bd_addr: {bd_addr}
    connected: {connected}
    disconnected: {disconnected}
    packet_count: {packet_count}""".format(
            remote_name=self.remote_name or "Unknown",
            connection_handle=self.connection_handle,
            bd_addr=(
                "{:02x}:{:02x}:{:02x}:{:02x}:{:02x}:{:02x}".format(
                    self.bd_addr[5],
                    self.bd_addr[4],
                    self.bd_addr[3],
                    self.bd_addr[2],
                    self.bd_addr[1],
                    self.bd_addr[0],
                )
                if self.bd_addr
                else "Unknown"
            ),
            connected=self.connected.number if self.connected else "Unknown",
            disconnected=self.disconnected.number if self.disconnected else "Unknown",
            packet_count=len(self.packets),
        )

    def append(self, packet: Packet):
        assert packet.idc == Idc.ACL_DATA
        packet_boundary_flag = (packet.payload[1] >> 4) & 0x3

        if packet_boundary_flag == 1:
            # Sometimes packet fragments can be interleaved with packets
            # issued from the opposite direction.
            for start_packet in reversed(self.packets):
                if start_packet.direction == packet.direction:
                    start_packet.continuing_fragments.append(packet)
                    break
        else:
            self.packets.append(packet)


class Btsnoop:
    VERSION = 1
    DATALINK_HCI_H4 = 1002

    packets: List[Packet]
    acl_connections: List[AclConnection]
    le_acl_connections: List[AclConnection]

    def __init__(self, data: Optional[bytes], data_last: Optional[bytes]):
        def take(iterable, n):
            return bytes(itertools.islice(iterable, n))

        def parse(data: Optional[bytes]) -> Iterator[Packet]:
            if not data:
                return iter([])

            data = iter(data or bytes())
            header = struct.unpack(">8sII", take(data, 16))
            number = 0

            if header != (b"btsnoop\0", Btsnoop.VERSION, Btsnoop.DATALINK_HCI_H4):
                raise Exception("Invalide BTSnoop file format")

            while header := take(data, 24):
                (
                    original_length,
                    included_length,
                    packet_flags,
                    cumulative_drops,
                    timestamp_us,
                ) = struct.unpack(">IIIIQ", header)

                number += 1
                payload = take(data, included_length)
                direction = Direction(packet_flags & 1)
                idc = Idc(payload[0])

                yield Packet(
                    number,
                    direction,
                    idc,
                    timestamp_us, # - 0x00DCDDB30F2F8000,
                    payload[1:],
                )

        self.packets = list(itertools.chain(parse(data_last), parse(data)))
        remote_names = dict()

        for packet in self.packets:
            if packet.idc == Idc.EVENT:
                event_code = packet.payload[0]

                if event_code == EventCode.REMOTE_NAME_REQUEST_COMPLETE:
                    status, bd_addr, remote_name = struct.unpack(
                        "<B6s248s", packet.payload[2:]
                    )

                    if status == 0:
                        remote_name = remote_name.split(b'\x00')[0]
                        remote_names[bd_addr] = remote_name.decode("utf-8", errors="replace")

        self.acl_connections = []
        self.le_acl_connections = []
        active_acl_connections = dict()

        for packet in self.packets:

            if packet.idc == Idc.COMMAND:
                (op_code,) = struct.unpack("<H", packet.payload[:2])

                if op_code == OpCode.RESET:
                    for acl_connection in active_acl_connections.values():
                        acl_connection.disconnected = packet
                    active_acl_connections = dict()

            elif packet.idc == Idc.EVENT:
                event_code = packet.payload[0]
                subevent_code = packet.payload[2]

                if event_code == EventCode.CONNECTION_COMPLETE:
                    status, connection_handle, bd_addr, link_type, _ = struct.unpack(
                        "<BH6sBB", packet.payload[2:]
                    )

                    if status == 0 and link_type == 1:
                        assert connection_handle not in active_acl_connections, f"oops {connection_handle:x} {packet.number}"

                        acl_connection = AclConnection(
                            self,
                            connection_handle,
                            bd_addr=bd_addr,
                            connected=packet,
                            remote_name=remote_names.get(bd_addr),
                        )
                        active_acl_connections[connection_handle] = acl_connection
                        self.acl_connections.append(acl_connection)

                elif event_code == EventCode.DISCONNECTION_COMPLETE:
                    status, connection_handle, _ = struct.unpack(
                        "<BHB", packet.payload[2:]
                    )

                    if acl_connection := active_acl_connections.get(connection_handle):
                        acl_connection.disconnected = packet
                        del active_acl_connections[connection_handle]

                elif event_code == EventCode.LE_META_EVENT and (
                    subevent_code == LeMetaEventCode.LE_CONNECTION_COMPLETE
                    or subevent_code == LeMetaEventCode.LE_ENHANCED_CONNECTION_COMPLETE
                ):
                    status, connection_handle = struct.unpack(
                        "<BH", packet.payload[3:6]
                    )

                    if status == 0:
                        assert connection_handle not in active_acl_connections, f"oops {connection_handle:x} {packet.number}"

                        bd_addr = 0
                        acl_connection = AclConnection(
                            self,
                            connection_handle,
                            bd_addr=bd_addr,
                            connected=packet,
                            remote_name=remote_names.get(bd_addr),
                        )
                        active_acl_connections[connection_handle] = acl_connection
                        self.le_acl_connections.append(acl_connection)

            elif packet.idc == Idc.ACL_DATA:
                (connection_handle,) = struct.unpack("<H", packet.payload[:2])
                connection_handle &= 0xFFF

                if acl_connection := active_acl_connections.get(connection_handle):
                    acl_connection.append(packet)
                else:
                    acl_connection = AclConnection(self, connection_handle)
                    acl_connection.append(packet)
                    active_acl_connections[connection_handle] = acl_connection
                    self.acl_connections.append(acl_connection)
