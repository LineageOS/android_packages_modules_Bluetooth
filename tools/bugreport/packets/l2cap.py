import dataclasses
import struct
import enum


class CommandCode(enum.IntEnum):
    COMMAND_REJECT_RSP = 0x01
    CONNECTION_REQ = 0x02
    CONNECTION_RSP = 0x03
    DISCONNECTION_REQ = 0x06
    DISCONNECTION_RSP = 0x07
    LE_CREDIT_BASED_CONNECTION_REQ = 0x14
    LE_CREDIT_BASED_CONNECTION_RSP = 0x15
    LE_FLOW_CONTROL_CREDIT_IND = 0x16


@dataclasses.dataclass
class SignalingPacket:
    command_code: CommandCode
    identifier: int

    def parse_all(span: bytes) -> "SignalingPacket":
        command_code, identifier, data_length = struct.unpack("<BBH", span[:4])
        assert data_length == len(span[4:]), "Invalid L2CAP Data Length field"

        if command_code == CommandCode.CONNECTION_REQ:
            psm, source_cid = struct.unpack("<HH", span[4:])
            return ConnectionRequest(command_code, identifier, psm, source_cid)

        elif command_code == CommandCode.CONNECTION_RSP:
            destination_cid, source_cid, result, status = struct.unpack(
                "<HHHH", span[4:]
            )
            return ConnectionResponse(
                command_code, identifier, destination_cid, source_cid, result, status
            )

        elif command_code == CommandCode.DISCONNECTION_REQ:
            destination_cid, source_cid = struct.unpack("<HH", span[4:])
            return DisconnectionRequest(
                command_code, identifier, destination_cid, source_cid
            )

        elif command_code == CommandCode.DISCONNECTION_RSP:
            destination_cid, source_cid = struct.unpack("<HH", span[4:])
            return DisconnectionResponse(
                command_code, identifier, destination_cid, source_cid
            )

        elif command_code == CommandCode.LE_CREDIT_BASED_CONNECTION_REQ:
            psm, source_cid, mtu, mps, initial_credits = struct.unpack("<HHHHH", span[4:])
            return LeCreditBasedConnectionRequest(command_code, identifier, psm, source_cid, mtu, mps, initial_credits)

        elif command_code == CommandCode.LE_CREDIT_BASED_CONNECTION_RSP:
            destination_cid, mtu, mps, initial_credits, result = struct.unpack(
                "<HHHHH", span[4:]
            )
            return LeCreditBasedConnectionResponse(
                command_code, identifier, destination_cid, mtu, mps, initial_credits, result
            )

        elif command_code == CommandCode.LE_FLOW_CONTROL_CREDIT_IND:
            cid, credits = struct.unpack("<HH", span[4:])
            return LeFlowControlCreditInd(command_code, identifier, cid, credits)

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


@dataclasses.dataclass
class LeCreditBasedConnectionRequest(SignalingPacket):
    psm: int
    source_cid: int
    mtu: int
    mps: int
    initial_credits: int


@dataclasses.dataclass
class LeCreditBasedConnectionResponse(SignalingPacket):
    destination_cid: int
    mtu: int
    mps: int
    initial_credits: int
    result: int


@dataclasses.dataclass
class LeFlowControlCreditInd(SignalingPacket):
    cid: int
    credits: int
