import dataclasses
import struct
import enum


class CommandCode(enum.IntEnum):
    COMMAND_REJECT = 0x01
    CONNECTION_REQUEST = 0x02
    CONNECTION_RESPONSE = 0x03
    DISCONNECTION_REQUEST = 0x06
    DISCONNECTION_RESPONSE = 0x07


@dataclasses.dataclass
class SignalingPacket:
    command_code: CommandCode
    identifier: int

    def parse_all(span: bytes) -> "SignalingPacket":
        command_code, identifier, data_length = struct.unpack("<BBH", span[:4])
        assert data_length == len(span[4:]), "Invalid L2CAP Data Length field"

        if command_code == CommandCode.CONNECTION_REQUEST:
            psm, source_cid = struct.unpack("<HH", span[4:])
            return ConnectionRequest(command_code, identifier, psm, source_cid)

        elif command_code == CommandCode.CONNECTION_RESPONSE:
            destination_cid, source_cid, result, status = struct.unpack(
                "<HHHH", span[4:]
            )
            return ConnectionResponse(
                command_code, identifier, destination_cid, source_cid, result, status
            )

        elif command_code == CommandCode.DISCONNECTION_REQUEST:
            destination_cid, source_cid = struct.unpack("<HH", span[4:])
            return DisconnectionRequest(
                command_code, identifier, destination_cid, source_cid
            )

        elif command_code == CommandCode.DISCONNECTION_RESPONSE:
            destination_cid, source_cid = struct.unpack("<HH", span[4:])
            return DisconnectionResponse(
                command_code, identifier, destination_cid, source_cid
            )

        else:
            return SignalingPacket(command_code, identifier)


@dataclasses.dataclass
class ConnectionRequest(SignalingPacket):
    psm: int
    source_cid: int


@dataclasses.dataclass
class ConnectionResponse(SignalingPacket):
    destination_cid: int
    source_cid: int
    result: int
    status: int


@dataclasses.dataclass
class DisconnectionRequest(SignalingPacket):
    destination_cid: int
    source_cid: int


@dataclasses.dataclass
class DisconnectionResponse(SignalingPacket):
    destination_cid: int
    source_cid: int
