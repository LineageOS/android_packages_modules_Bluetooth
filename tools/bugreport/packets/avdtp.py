# File generated from <stdin>, with the command:
#  /home/henrichataing/Projects/github/google/pdl/pdl-compiler/scripts/generate_python_backend.py
# /!\ Do not edit by hand.
from dataclasses import dataclass, field, fields
from typing import Optional, List, Tuple, Union
import enum
import inspect
import math


@dataclass
class Packet:
    payload: Optional[bytes] = field(repr=False, default_factory=bytes, compare=False)

    @classmethod
    def parse_all(cls, span: bytes) -> "Packet":
        packet, remain = getattr(cls, "parse")(span)
        if len(remain) > 0:
            raise Exception("Unexpected parsing remainder")
        return packet

    @property
    def size(self) -> int:
        pass

    def show(self, prefix: str = ""):
        print(f"{self.__class__.__name__}")

        def print_val(p: str, pp: str, name: str, align: int, typ, val):
            if name == "payload":
                pass

            # Scalar fields.
            elif typ is int:
                print(f"{p}{name:{align}} = {val} (0x{val:x})")

            # Byte fields.
            elif typ is bytes:
                print(f"{p}{name:{align}} = [", end="")
                line = ""
                n_pp = ""
                for idx, b in enumerate(val):
                    if idx > 0 and idx % 8 == 0:
                        print(f"{n_pp}{line}")
                        line = ""
                        n_pp = pp + (" " * (align + 4))
                    line += f" {b:02x}"
                print(f"{n_pp}{line} ]")

            # Enum fields.
            elif inspect.isclass(typ) and issubclass(typ, enum.IntEnum):
                print(f"{p}{name:{align}} = {typ.__name__}::{val.name} (0x{val:x})")

            # Struct fields.
            elif inspect.isclass(typ) and issubclass(typ, globals().get("Packet")):
                print(f"{p}{name:{align}} = ", end="")
                val.show(prefix=pp)

            # Array fields.
            elif getattr(typ, "__origin__", None) == list:
                print(f"{p}{name:{align}}")
                last = len(val) - 1
                align = 5
                for idx, elt in enumerate(val):
                    n_p = pp + ("├── " if idx != last else "└── ")
                    n_pp = pp + ("│   " if idx != last else "    ")
                    print_val(n_p, n_pp, f"[{idx}]", align, typ.__args__[0], val[idx])

            # Custom fields.
            elif inspect.isclass(typ):
                print(f"{p}{name:{align}} = {repr(val)}")

            else:
                print(f"{p}{name:{align}} = ##{typ}##")

        last = len(fields(self)) - 1
        align = max(len(f.name) for f in fields(self) if f.name != "payload")

        for idx, f in enumerate(fields(self)):
            p = prefix + ("├── " if idx != last else "└── ")
            pp = prefix + ("│   " if idx != last else "    ")
            val = getattr(self, f.name)

            print_val(p, pp, f.name, align, f.type, val)


class PacketType(enum.IntEnum):
    SINGLE_PACKET = 0x0
    START_PACKET = 0x1
    CONTINUE_PACKET = 0x2
    END_PACKET = 0x3

    @staticmethod
    def from_int(v: int) -> Union[int, "PacketType"]:
        try:
            return PacketType(v)
        except ValueError as exn:
            raise exn


class MessageType(enum.IntEnum):
    COMMAND = 0x0
    GENERAL_REJECT = 0x1
    RESPONSE_ACCEPT = 0x2
    RESPONSE_REJECT = 0x3

    @staticmethod
    def from_int(v: int) -> Union[int, "MessageType"]:
        try:
            return MessageType(v)
        except ValueError as exn:
            raise exn


class SignalIdentifier(enum.IntEnum):
    AVDTP_DISCOVER = 0x1
    AVDTP_GET_CAPABILITIES = 0x2
    AVDTP_SET_CONFIGURATION = 0x3
    AVDTP_GET_CONFIGURATION = 0x4
    AVDTP_RECONFIGURE = 0x5
    AVDTP_OPEN = 0x6
    AVDTP_START = 0x7
    AVDTP_CLOSE = 0x8
    AVDTP_SUSPEND = 0x9
    AVDTP_ABORT = 0xA
    AVDTP_SECURITY_CONTROL = 0xB
    AVDTP_GET_ALL_CAPABILITIES = 0xC
    AVDTP_DELAYREPORT = 0xD

    @staticmethod
    def from_int(v: int) -> Union[int, "SignalIdentifier"]:
        try:
            return SignalIdentifier(v)
        except ValueError as exn:
            raise exn


class ErrorCode(enum.IntEnum):
    SUCCESS = 0x0
    AVDTP_BAD_HEADER_FORMAT = 0x1
    AVDTP_BAD_LENGTH = 0x11
    AVDTP_BAD_ACP_SEID = 0x12
    AVDTP_SEP_IN_USE = 0x13
    AVDTP_SEP_NOT_IN_USE = 0x14
    AVDTP_BAD_SERV_CATEGORY = 0x17
    AVDTP_BAD_PAYLOAD_FORMAT = 0x18
    AVDTP_NOT_SUPPORTED_COMMAND = 0x19
    AVDTP_INVALID_CAPABILITIES = 0x1A
    AVDTP_BAD_RECOVERY_TYPE = 0x22
    AVDTP_BAD_MEDIA_TRANSPORT_FORMAT = 0x23
    AVDTP_BAD_RECOVERY_FORMAT = 0x25
    AVDTP_BAD_ROHC_FORMAT = 0x26
    AVDTP_BAD_CP_FORMAT = 0x27
    AVDTP_BAD_MULTIPLEXING_FORMAT = 0x28
    AVDTP_UNSUPPORTED_CONFIGURATION = 0x29
    AVDTP_BAD_STATE = 0x31
    GAVDTP_BAD_SERVICE = 0x80
    GAVDTP_INSUFFICIENT_RESOURCES = 0x81
    A2DP_INVALID_CODEC_TYPE = 0xC1
    A2DP_NOT_SUPPORTED_CODEC_TYPE = 0xC2
    A2DP_INVALID_SAMPLING_FREQUENCY = 0xC3
    A2DP_NOT_SUPPORTED_SAMPLING_FREQUENCY = 0xC4
    A2DP_INVALID_CHANNEL_MODE = 0xC5
    A2DP_NOT_SUPPORTED_CHANNEL_MODE = 0xC6
    A2DP_INVALID_SUBBANDS = 0xC7
    A2DP_NOT_SUPPORTED_SUBBANDS = 0xC8
    A2DP_INVALID_ALLOCATION_METHOD = 0xC9
    A2DP_NOT_SUPPORTED_ALLOCATION_METHOD = 0xCA
    A2DP_INVALID_MINIMUM_BITPOOL_VALUE = 0xCB
    A2DP_NOT_SUPPORTED_MINIMUM_BITPOOL_VALUE = 0xCC
    A2DP_INVALID_MAXIMUM_BITPOOL_VALUE = 0xCD
    A2DP_NOT_SUPPORTED_MAXIMUM_BITPOOL_VALUE = 0xCE
    A2DP_INVALID_LAYER = 0xCF
    A2DP_NOT_SUPPORTED_LAYER = 0xD0
    A2DP_NOT_SUPPORTED_CRC = 0xD1
    A2DP_NOT_SUPPORTED_MPF = 0xD2
    A2DP_NOT_SUPPORTED_VBR = 0xD3
    A2DP_INVALID_BIT_RATE = 0xD4
    A2DP_NOT_SUPPORTED_BIT_RATE = 0xD5
    A2DP_INVALID_OBJECT_TYPE = 0xD6
    A2DP_NOT_SUPPORTED_OBJECT_TYPE = 0xD7
    A2DP_INVALID_CHANNELS = 0xD8
    A2DP_NOT_SUPPORTED_CHANNELS = 0xD9
    A2DP_INVALID_BLOCK_LENGTH = 0xDD
    A2DP_INVALID_CP_TYPE = 0xE0
    A2DP_INVALID_CP_FORMAT = 0xE1
    A2DP_INVALID_CODEC_PARAMETER = 0xE2
    A2DP_NOT_SUPPORTED_CODEC_PARAMETER = 0xE3

    @staticmethod
    def from_int(v: int) -> Union[int, "ErrorCode"]:
        try:
            return ErrorCode(v)
        except ValueError as exn:
            raise exn


class Tsep(enum.IntEnum):
    SOURCE = 0x0
    SINK = 0x1

    @staticmethod
    def from_int(v: int) -> Union[int, "Tsep"]:
        try:
            return Tsep(v)
        except ValueError as exn:
            raise exn


class ServiceCategory(enum.IntEnum):
    MEDIA_TRANSPORT = 0x1
    REPORTING = 0x2
    RECOVERY = 0x3
    CONTENT_PROTECTION = 0x4
    HEADER_COMPRESSION = 0x5
    MULTIPLEXING = 0x6
    MEDIA_CODEC = 0x7
    DELAY_REPORTING = 0x8

    @staticmethod
    def from_int(v: int) -> Union[int, "ServiceCategory"]:
        try:
            return ServiceCategory(v)
        except ValueError as exn:
            raise exn


@dataclass
class SeidInformation(Packet):
    in_use: int = field(kw_only=True, default=0)
    acp_seid: int = field(kw_only=True, default=0)
    tsep: Tsep = field(kw_only=True, default=Tsep.SOURCE)
    media_type: int = field(kw_only=True, default=0)

    def __post_init__(self):
        pass

    @staticmethod
    def parse(span: bytes) -> Tuple["SeidInformation", bytes]:
        fields = {"payload": None}
        if len(span) < 2:
            raise Exception("Invalid packet size")
        fields["in_use"] = (span[0] >> 1) & 0x1
        fields["acp_seid"] = (span[0] >> 2) & 0x3F
        fields["tsep"] = Tsep.from_int((span[1] >> 3) & 0x1)
        fields["media_type"] = (span[1] >> 4) & 0xF
        span = span[2:]
        return SeidInformation(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        if self.in_use > 1:
            print(
                f"Invalid value for field SeidInformation::in_use: {self.in_use} > 1; the value will be truncated"
            )
            self.in_use &= 1
        if self.acp_seid > 63:
            print(
                f"Invalid value for field SeidInformation::acp_seid: {self.acp_seid} > 63; the value will be truncated"
            )
            self.acp_seid &= 63
        _value = (self.in_use << 1) | (self.acp_seid << 2)
        _span.append(_value)
        if self.media_type > 15:
            print(
                f"Invalid value for field SeidInformation::media_type: {self.media_type} > 15; the value will be truncated"
            )
            self.media_type &= 15
        _value = (self.tsep << 3) | (self.media_type << 4)
        _span.append(_value)
        return bytes(_span)

    @property
    def size(self) -> int:
        return 2


@dataclass
class ServiceCapability(Packet):
    service_category: ServiceCategory = field(
        kw_only=True, default=ServiceCategory.MEDIA_TRANSPORT
    )

    def __post_init__(self):
        pass

    @staticmethod
    def parse(span: bytes) -> Tuple["ServiceCapability", bytes]:
        fields = {"payload": None}
        if len(span) < 2:
            raise Exception("Invalid packet size")
        fields["service_category"] = ServiceCategory.from_int(span[0])
        _payload__size = span[1]
        span = span[2:]
        if len(span) < _payload__size:
            raise Exception("Invalid packet size")
        payload = span[:_payload__size]
        span = span[_payload__size:]
        fields["payload"] = payload
        try:
            child, remain = MediaTransportCapability.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        try:
            child, remain = ReportingCapability.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        try:
            child, remain = RecoveryCapability.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        try:
            child, remain = ContentProtectionCapability.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        try:
            child, remain = HeaderCompressionCapability.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        try:
            child, remain = MultiplexingCapability.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        try:
            child, remain = MediaCodecCapability.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        try:
            child, remain = DelayReportingCapability.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        return ServiceCapability(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        _span.append((self.service_category << 0))
        _payload_size = len(payload or self.payload or [])
        if _payload_size > 255:
            print(
                f"Invalid length for payload field:  {_payload_size} > 255; the packet cannot be generated"
            )
            raise Exception("Invalid payload length")
        _span.append((_payload_size << 0))
        _span.extend(payload or self.payload or [])
        return bytes(_span)

    @property
    def size(self) -> int:
        return len(self.payload) + 2


@dataclass
class MediaTransportCapability(ServiceCapability):

    def __post_init__(self):
        self.service_category = ServiceCategory.MEDIA_TRANSPORT

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["MediaTransportCapability", bytes]:
        if fields["service_category"] != ServiceCategory.MEDIA_TRANSPORT:
            raise Exception("Invalid constraint field values")
        return MediaTransportCapability(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        return ServiceCapability.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return 0


@dataclass
class ReportingCapability(ServiceCapability):

    def __post_init__(self):
        self.service_category = ServiceCategory.REPORTING

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["ReportingCapability", bytes]:
        if fields["service_category"] != ServiceCategory.REPORTING:
            raise Exception("Invalid constraint field values")
        return ReportingCapability(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        return ServiceCapability.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return 0


@dataclass
class RecoveryCapability(ServiceCapability):
    recovery_type: int = field(kw_only=True, default=0)
    maximum_recovery_window_size: int = field(kw_only=True, default=0)
    maximum_number_of_media_packets_in_parity_code: int = field(kw_only=True, default=0)

    def __post_init__(self):
        self.service_category = ServiceCategory.RECOVERY

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["RecoveryCapability", bytes]:
        if fields["service_category"] != ServiceCategory.RECOVERY:
            raise Exception("Invalid constraint field values")
        if len(span) < 3:
            raise Exception("Invalid packet size")
        fields["recovery_type"] = span[0]
        fields["maximum_recovery_window_size"] = span[1]
        fields["maximum_number_of_media_packets_in_parity_code"] = span[2]
        span = span[3:]
        return RecoveryCapability(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        if self.recovery_type > 255:
            print(
                f"Invalid value for field RecoveryCapability::recovery_type: {self.recovery_type} > 255; the value will be truncated"
            )
            self.recovery_type &= 255
        _span.append((self.recovery_type << 0))
        if self.maximum_recovery_window_size > 255:
            print(
                f"Invalid value for field RecoveryCapability::maximum_recovery_window_size: {self.maximum_recovery_window_size} > 255; the value will be truncated"
            )
            self.maximum_recovery_window_size &= 255
        _span.append((self.maximum_recovery_window_size << 0))
        if self.maximum_number_of_media_packets_in_parity_code > 255:
            print(
                f"Invalid value for field RecoveryCapability::maximum_number_of_media_packets_in_parity_code: {self.maximum_number_of_media_packets_in_parity_code} > 255; the value will be truncated"
            )
            self.maximum_number_of_media_packets_in_parity_code &= 255
        _span.append((self.maximum_number_of_media_packets_in_parity_code << 0))
        return ServiceCapability.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return 3


@dataclass
class ContentProtectionCapability(ServiceCapability):
    cp_type: int = field(kw_only=True, default=0)

    def __post_init__(self):
        self.service_category = ServiceCategory.CONTENT_PROTECTION

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["ContentProtectionCapability", bytes]:
        if fields["service_category"] != ServiceCategory.CONTENT_PROTECTION:
            raise Exception("Invalid constraint field values")
        if len(span) < 2:
            raise Exception("Invalid packet size")
        value_ = int.from_bytes(span[0:2], byteorder="little")
        fields["cp_type"] = value_
        span = span[2:]
        payload = span
        span = bytes([])
        fields["payload"] = payload
        return ContentProtectionCapability(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        if self.cp_type > 65535:
            print(
                f"Invalid value for field ContentProtectionCapability::cp_type: {self.cp_type} > 65535; the value will be truncated"
            )
            self.cp_type &= 65535
        _span.extend(int.to_bytes((self.cp_type << 0), length=2, byteorder="little"))
        _span.extend(payload or self.payload or [])
        return ServiceCapability.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return len(self.payload) + 2


@dataclass
class HeaderCompressionCapability(ServiceCapability):
    recovery: int = field(kw_only=True, default=0)
    media: int = field(kw_only=True, default=0)
    back_ch: int = field(kw_only=True, default=0)

    def __post_init__(self):
        self.service_category = ServiceCategory.HEADER_COMPRESSION

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["HeaderCompressionCapability", bytes]:
        if fields["service_category"] != ServiceCategory.HEADER_COMPRESSION:
            raise Exception("Invalid constraint field values")
        if len(span) < 1:
            raise Exception("Invalid packet size")
        fields["recovery"] = (span[0] >> 5) & 0x1
        fields["media"] = (span[0] >> 6) & 0x1
        fields["back_ch"] = (span[0] >> 7) & 0x1
        span = span[1:]
        return HeaderCompressionCapability(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        if self.recovery > 1:
            print(
                f"Invalid value for field HeaderCompressionCapability::recovery: {self.recovery} > 1; the value will be truncated"
            )
            self.recovery &= 1
        if self.media > 1:
            print(
                f"Invalid value for field HeaderCompressionCapability::media: {self.media} > 1; the value will be truncated"
            )
            self.media &= 1
        if self.back_ch > 1:
            print(
                f"Invalid value for field HeaderCompressionCapability::back_ch: {self.back_ch} > 1; the value will be truncated"
            )
            self.back_ch &= 1
        _value = (self.recovery << 5) | (self.media << 6) | (self.back_ch << 7)
        _span.append(_value)
        return ServiceCapability.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return 1


@dataclass
class MultiplexingCapability(ServiceCapability):
    frag: int = field(kw_only=True, default=0)

    def __post_init__(self):
        self.service_category = ServiceCategory.MULTIPLEXING

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["MultiplexingCapability", bytes]:
        if fields["service_category"] != ServiceCategory.MULTIPLEXING:
            raise Exception("Invalid constraint field values")
        if len(span) < 1:
            raise Exception("Invalid packet size")
        fields["frag"] = (span[0] >> 7) & 0x1
        span = span[1:]
        payload = span
        span = bytes([])
        fields["payload"] = payload
        return MultiplexingCapability(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        if self.frag > 1:
            print(
                f"Invalid value for field MultiplexingCapability::frag: {self.frag} > 1; the value will be truncated"
            )
            self.frag &= 1
        _span.append((self.frag << 7))
        _span.extend(payload or self.payload or [])
        return ServiceCapability.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return len(self.payload) + 1


@dataclass
class MediaCodecCapability(ServiceCapability):
    media_type: int = field(kw_only=True, default=0)
    media_codec_type: int = field(kw_only=True, default=0)
    media_codec_specific_information_elements: bytearray = field(
        kw_only=True, default_factory=bytearray
    )

    def __post_init__(self):
        self.service_category = ServiceCategory.MEDIA_CODEC

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["MediaCodecCapability", bytes]:
        if fields["service_category"] != ServiceCategory.MEDIA_CODEC:
            raise Exception("Invalid constraint field values")
        if len(span) < 2:
            raise Exception("Invalid packet size")
        fields["media_type"] = (span[0] >> 4) & 0xF
        fields["media_codec_type"] = span[1]
        span = span[2:]
        fields["media_codec_specific_information_elements"] = list(span)
        span = bytes()
        return MediaCodecCapability(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        if self.media_type > 15:
            print(
                f"Invalid value for field MediaCodecCapability::media_type: {self.media_type} > 15; the value will be truncated"
            )
            self.media_type &= 15
        _span.append((self.media_type << 4))
        if self.media_codec_type > 255:
            print(
                f"Invalid value for field MediaCodecCapability::media_codec_type: {self.media_codec_type} > 255; the value will be truncated"
            )
            self.media_codec_type &= 255
        _span.append((self.media_codec_type << 0))
        _span.extend(self.media_codec_specific_information_elements)
        return ServiceCapability.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return len(self.media_codec_specific_information_elements) * 1 + 2


@dataclass
class DelayReportingCapability(ServiceCapability):

    def __post_init__(self):
        self.service_category = ServiceCategory.DELAY_REPORTING

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["DelayReportingCapability", bytes]:
        if fields["service_category"] != ServiceCategory.DELAY_REPORTING:
            raise Exception("Invalid constraint field values")
        return DelayReportingCapability(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        return ServiceCapability.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return 0


@dataclass
class SignalingPacket(Packet):
    message_type: MessageType = field(kw_only=True, default=MessageType.COMMAND)
    packet_type: PacketType = field(kw_only=True, default=PacketType.SINGLE_PACKET)
    transaction_label: int = field(kw_only=True, default=0)
    signal_identifier: SignalIdentifier = field(
        kw_only=True, default=SignalIdentifier.AVDTP_DISCOVER
    )

    def __post_init__(self):
        pass

    @staticmethod
    def parse(span: bytes) -> Tuple["SignalingPacket", bytes]:
        fields = {"payload": None}
        if len(span) < 2:
            raise Exception("Invalid packet size")
        fields["message_type"] = MessageType.from_int((span[0] >> 0) & 0x3)
        fields["packet_type"] = PacketType.from_int((span[0] >> 2) & 0x3)
        fields["transaction_label"] = (span[0] >> 4) & 0xF
        fields["signal_identifier"] = SignalIdentifier.from_int((span[1] >> 0) & 0x3F)
        span = span[2:]
        payload = span
        span = bytes([])
        fields["payload"] = payload
        try:
            child, remain = DiscoverCommand.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        try:
            child, remain = DiscoverResponse.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        try:
            child, remain = DiscoverReject.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        try:
            child, remain = GetCapabilitiesCommand.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        try:
            child, remain = GetCapabilitiesResponse.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        try:
            child, remain = GetCapabilitiesReject.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        try:
            child, remain = GetAllCapabilitiesCommand.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        try:
            child, remain = GetAllCapabilitiesResponse.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        try:
            child, remain = GetAllCapabilitiesReject.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        try:
            child, remain = SetConfigurationCommand.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        try:
            child, remain = SetConfigurationResponse.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        try:
            child, remain = SetConfigurationReject.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        try:
            child, remain = GetConfigurationCommand.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        try:
            child, remain = GetConfigurationResponse.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        try:
            child, remain = GetConfigurationReject.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        try:
            child, remain = ReconfigureCommand.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        try:
            child, remain = ReconfigureResponse.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        try:
            child, remain = ReconfigureReject.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        try:
            child, remain = OpenCommand.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        try:
            child, remain = OpenResponse.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        try:
            child, remain = OpenReject.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        try:
            child, remain = StartCommand.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        try:
            child, remain = StartResponse.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        try:
            child, remain = StartReject.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        try:
            child, remain = CloseCommand.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        try:
            child, remain = CloseResponse.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        try:
            child, remain = CloseReject.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        try:
            child, remain = SuspendCommand.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        try:
            child, remain = SuspendResponse.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        try:
            child, remain = SuspendReject.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        try:
            child, remain = AbortCommand.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        try:
            child, remain = AbortResponse.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        try:
            child, remain = SecurityControlCommand.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        try:
            child, remain = SecurityControlResponse.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        try:
            child, remain = SecurityControlReject.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        try:
            child, remain = GeneralReject.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        try:
            child, remain = DelayReportCommand.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        try:
            child, remain = DelayReportResponse.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        try:
            child, remain = DelayReportReject.parse(fields.copy(), payload)
            assert len(remain) == 0
            return child, span
        except Exception as exn:
            pass
        return SignalingPacket(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        if self.transaction_label > 15:
            print(
                f"Invalid value for field SignalingPacket::transaction_label: {self.transaction_label} > 15; the value will be truncated"
            )
            self.transaction_label &= 15
        _value = (
            (self.message_type << 0)
            | (self.packet_type << 2)
            | (self.transaction_label << 4)
        )
        _span.append(_value)
        _span.append((self.signal_identifier << 0))
        _span.extend(payload or self.payload or [])
        return bytes(_span)

    @property
    def size(self) -> int:
        return len(self.payload) + 2


@dataclass
class DiscoverCommand(SignalingPacket):

    def __post_init__(self):
        self.message_type = MessageType.COMMAND
        self.signal_identifier = SignalIdentifier.AVDTP_DISCOVER

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["DiscoverCommand", bytes]:
        if (
            fields["message_type"] != MessageType.COMMAND
            or fields["signal_identifier"] != SignalIdentifier.AVDTP_DISCOVER
        ):
            raise Exception("Invalid constraint field values")
        return DiscoverCommand(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        return SignalingPacket.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return 0


@dataclass
class DiscoverResponse(SignalingPacket):
    seid_information: List[SeidInformation] = field(kw_only=True, default_factory=list)

    def __post_init__(self):
        self.message_type = MessageType.RESPONSE_ACCEPT
        self.signal_identifier = SignalIdentifier.AVDTP_DISCOVER

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["DiscoverResponse", bytes]:
        if (
            fields["message_type"] != MessageType.RESPONSE_ACCEPT
            or fields["signal_identifier"] != SignalIdentifier.AVDTP_DISCOVER
        ):
            raise Exception("Invalid constraint field values")
        if len(span) % 2 != 0:
            raise Exception("Array size is not a multiple of the element size")
        seid_information_count = int(len(span) / 2)
        seid_information = []
        for n in range(seid_information_count):
            seid_information.append(
                SeidInformation.parse_all(span[n * 2 : (n + 1) * 2])
            )
        fields["seid_information"] = seid_information
        span = bytes()
        return DiscoverResponse(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        for _elt in self.seid_information:
            _span.extend(_elt.serialize())
        return SignalingPacket.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return sum([elt.size for elt in self.seid_information])


@dataclass
class DiscoverReject(SignalingPacket):
    error_code: ErrorCode = field(kw_only=True, default=ErrorCode.SUCCESS)

    def __post_init__(self):
        self.message_type = MessageType.RESPONSE_REJECT
        self.signal_identifier = SignalIdentifier.AVDTP_DISCOVER

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["DiscoverReject", bytes]:
        if (
            fields["message_type"] != MessageType.RESPONSE_REJECT
            or fields["signal_identifier"] != SignalIdentifier.AVDTP_DISCOVER
        ):
            raise Exception("Invalid constraint field values")
        if len(span) < 1:
            raise Exception("Invalid packet size")
        fields["error_code"] = ErrorCode.from_int(span[0])
        span = span[1:]
        return DiscoverReject(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        _span.append((self.error_code << 0))
        return SignalingPacket.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return 1


@dataclass
class GetCapabilitiesCommand(SignalingPacket):
    acp_seid: int = field(kw_only=True, default=0)

    def __post_init__(self):
        self.message_type = MessageType.COMMAND
        self.signal_identifier = SignalIdentifier.AVDTP_GET_CAPABILITIES

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["GetCapabilitiesCommand", bytes]:
        if (
            fields["message_type"] != MessageType.COMMAND
            or fields["signal_identifier"] != SignalIdentifier.AVDTP_GET_CAPABILITIES
        ):
            raise Exception("Invalid constraint field values")
        if len(span) < 1:
            raise Exception("Invalid packet size")
        fields["acp_seid"] = (span[0] >> 2) & 0x3F
        span = span[1:]
        return GetCapabilitiesCommand(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        if self.acp_seid > 63:
            print(
                f"Invalid value for field GetCapabilitiesCommand::acp_seid: {self.acp_seid} > 63; the value will be truncated"
            )
            self.acp_seid &= 63
        _span.append((self.acp_seid << 2))
        return SignalingPacket.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return 1


@dataclass
class GetCapabilitiesResponse(SignalingPacket):
    service_capabilities: List[ServiceCapability] = field(
        kw_only=True, default_factory=list
    )

    def __post_init__(self):
        self.message_type = MessageType.RESPONSE_ACCEPT
        self.signal_identifier = SignalIdentifier.AVDTP_GET_CAPABILITIES

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["GetCapabilitiesResponse", bytes]:
        if (
            fields["message_type"] != MessageType.RESPONSE_ACCEPT
            or fields["signal_identifier"] != SignalIdentifier.AVDTP_GET_CAPABILITIES
        ):
            raise Exception("Invalid constraint field values")
        service_capabilities = []
        while len(span) > 0:
            element, span = ServiceCapability.parse(span)
            service_capabilities.append(element)
        fields["service_capabilities"] = service_capabilities
        return GetCapabilitiesResponse(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        for _elt in self.service_capabilities:
            _span.extend(_elt.serialize())
        return SignalingPacket.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return sum([elt.size for elt in self.service_capabilities])


@dataclass
class GetCapabilitiesReject(SignalingPacket):
    error_code: ErrorCode = field(kw_only=True, default=ErrorCode.SUCCESS)

    def __post_init__(self):
        self.message_type = MessageType.RESPONSE_REJECT
        self.signal_identifier = SignalIdentifier.AVDTP_GET_CAPABILITIES

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["GetCapabilitiesReject", bytes]:
        if (
            fields["message_type"] != MessageType.RESPONSE_REJECT
            or fields["signal_identifier"] != SignalIdentifier.AVDTP_GET_CAPABILITIES
        ):
            raise Exception("Invalid constraint field values")
        if len(span) < 1:
            raise Exception("Invalid packet size")
        fields["error_code"] = ErrorCode.from_int(span[0])
        span = span[1:]
        return GetCapabilitiesReject(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        _span.append((self.error_code << 0))
        return SignalingPacket.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return 1


@dataclass
class GetAllCapabilitiesCommand(SignalingPacket):
    acp_seid: int = field(kw_only=True, default=0)

    def __post_init__(self):
        self.message_type = MessageType.COMMAND
        self.signal_identifier = SignalIdentifier.AVDTP_GET_ALL_CAPABILITIES

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["GetAllCapabilitiesCommand", bytes]:
        if (
            fields["message_type"] != MessageType.COMMAND
            or fields["signal_identifier"]
            != SignalIdentifier.AVDTP_GET_ALL_CAPABILITIES
        ):
            raise Exception("Invalid constraint field values")
        if len(span) < 1:
            raise Exception("Invalid packet size")
        fields["acp_seid"] = (span[0] >> 2) & 0x3F
        span = span[1:]
        return GetAllCapabilitiesCommand(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        if self.acp_seid > 63:
            print(
                f"Invalid value for field GetAllCapabilitiesCommand::acp_seid: {self.acp_seid} > 63; the value will be truncated"
            )
            self.acp_seid &= 63
        _span.append((self.acp_seid << 2))
        return SignalingPacket.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return 1


@dataclass
class GetAllCapabilitiesResponse(SignalingPacket):
    service_capabilities: List[ServiceCapability] = field(
        kw_only=True, default_factory=list
    )

    def __post_init__(self):
        self.message_type = MessageType.RESPONSE_ACCEPT
        self.signal_identifier = SignalIdentifier.AVDTP_GET_ALL_CAPABILITIES

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["GetAllCapabilitiesResponse", bytes]:
        if (
            fields["message_type"] != MessageType.RESPONSE_ACCEPT
            or fields["signal_identifier"]
            != SignalIdentifier.AVDTP_GET_ALL_CAPABILITIES
        ):
            raise Exception("Invalid constraint field values")
        service_capabilities = []
        while len(span) > 0:
            element, span = ServiceCapability.parse(span)
            service_capabilities.append(element)
        fields["service_capabilities"] = service_capabilities
        return GetAllCapabilitiesResponse(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        for _elt in self.service_capabilities:
            _span.extend(_elt.serialize())
        return SignalingPacket.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return sum([elt.size for elt in self.service_capabilities])


@dataclass
class GetAllCapabilitiesReject(SignalingPacket):
    error_code: ErrorCode = field(kw_only=True, default=ErrorCode.SUCCESS)

    def __post_init__(self):
        self.message_type = MessageType.RESPONSE_REJECT
        self.signal_identifier = SignalIdentifier.AVDTP_GET_ALL_CAPABILITIES

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["GetAllCapabilitiesReject", bytes]:
        if (
            fields["message_type"] != MessageType.RESPONSE_REJECT
            or fields["signal_identifier"]
            != SignalIdentifier.AVDTP_GET_ALL_CAPABILITIES
        ):
            raise Exception("Invalid constraint field values")
        if len(span) < 1:
            raise Exception("Invalid packet size")
        fields["error_code"] = ErrorCode.from_int(span[0])
        span = span[1:]
        return GetAllCapabilitiesReject(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        _span.append((self.error_code << 0))
        return SignalingPacket.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return 1


@dataclass
class SetConfigurationCommand(SignalingPacket):
    acp_seid: int = field(kw_only=True, default=0)
    int_seid: int = field(kw_only=True, default=0)
    service_capabilities: List[ServiceCapability] = field(
        kw_only=True, default_factory=list
    )

    def __post_init__(self):
        self.message_type = MessageType.COMMAND
        self.signal_identifier = SignalIdentifier.AVDTP_SET_CONFIGURATION

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["SetConfigurationCommand", bytes]:
        if (
            fields["message_type"] != MessageType.COMMAND
            or fields["signal_identifier"] != SignalIdentifier.AVDTP_SET_CONFIGURATION
        ):
            raise Exception("Invalid constraint field values")
        if len(span) < 2:
            raise Exception("Invalid packet size")
        fields["acp_seid"] = (span[0] >> 2) & 0x3F
        fields["int_seid"] = (span[1] >> 2) & 0x3F
        span = span[2:]
        service_capabilities = []
        while len(span) > 0:
            element, span = ServiceCapability.parse(span)
            service_capabilities.append(element)
        fields["service_capabilities"] = service_capabilities
        return SetConfigurationCommand(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        if self.acp_seid > 63:
            print(
                f"Invalid value for field SetConfigurationCommand::acp_seid: {self.acp_seid} > 63; the value will be truncated"
            )
            self.acp_seid &= 63
        _span.append((self.acp_seid << 2))
        if self.int_seid > 63:
            print(
                f"Invalid value for field SetConfigurationCommand::int_seid: {self.int_seid} > 63; the value will be truncated"
            )
            self.int_seid &= 63
        _span.append((self.int_seid << 2))
        for _elt in self.service_capabilities:
            _span.extend(_elt.serialize())
        return SignalingPacket.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return sum([elt.size for elt in self.service_capabilities]) + 2


@dataclass
class SetConfigurationResponse(SignalingPacket):

    def __post_init__(self):
        self.message_type = MessageType.RESPONSE_ACCEPT
        self.signal_identifier = SignalIdentifier.AVDTP_SET_CONFIGURATION

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["SetConfigurationResponse", bytes]:
        if (
            fields["message_type"] != MessageType.RESPONSE_ACCEPT
            or fields["signal_identifier"] != SignalIdentifier.AVDTP_SET_CONFIGURATION
        ):
            raise Exception("Invalid constraint field values")
        return SetConfigurationResponse(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        return SignalingPacket.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return 0


@dataclass
class SetConfigurationReject(SignalingPacket):
    service_category: int = field(kw_only=True, default=0)
    error_code: ErrorCode = field(kw_only=True, default=ErrorCode.SUCCESS)

    def __post_init__(self):
        self.message_type = MessageType.RESPONSE_REJECT
        self.signal_identifier = SignalIdentifier.AVDTP_SET_CONFIGURATION

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["SetConfigurationReject", bytes]:
        if (
            fields["message_type"] != MessageType.RESPONSE_REJECT
            or fields["signal_identifier"] != SignalIdentifier.AVDTP_SET_CONFIGURATION
        ):
            raise Exception("Invalid constraint field values")
        if len(span) < 2:
            raise Exception("Invalid packet size")
        fields["service_category"] = span[0]
        fields["error_code"] = ErrorCode.from_int(span[1])
        span = span[2:]
        return SetConfigurationReject(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        if self.service_category > 255:
            print(
                f"Invalid value for field SetConfigurationReject::service_category: {self.service_category} > 255; the value will be truncated"
            )
            self.service_category &= 255
        _span.append((self.service_category << 0))
        _span.append((self.error_code << 0))
        return SignalingPacket.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return 2


@dataclass
class GetConfigurationCommand(SignalingPacket):
    acp_seid: int = field(kw_only=True, default=0)

    def __post_init__(self):
        self.message_type = MessageType.COMMAND
        self.signal_identifier = SignalIdentifier.AVDTP_GET_CONFIGURATION

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["GetConfigurationCommand", bytes]:
        if (
            fields["message_type"] != MessageType.COMMAND
            or fields["signal_identifier"] != SignalIdentifier.AVDTP_GET_CONFIGURATION
        ):
            raise Exception("Invalid constraint field values")
        if len(span) < 1:
            raise Exception("Invalid packet size")
        fields["acp_seid"] = (span[0] >> 2) & 0x3F
        span = span[1:]
        return GetConfigurationCommand(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        if self.acp_seid > 63:
            print(
                f"Invalid value for field GetConfigurationCommand::acp_seid: {self.acp_seid} > 63; the value will be truncated"
            )
            self.acp_seid &= 63
        _span.append((self.acp_seid << 2))
        return SignalingPacket.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return 1


@dataclass
class GetConfigurationResponse(SignalingPacket):
    service_capabilities: List[ServiceCapability] = field(
        kw_only=True, default_factory=list
    )

    def __post_init__(self):
        self.message_type = MessageType.RESPONSE_ACCEPT
        self.signal_identifier = SignalIdentifier.AVDTP_GET_CONFIGURATION

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["GetConfigurationResponse", bytes]:
        if (
            fields["message_type"] != MessageType.RESPONSE_ACCEPT
            or fields["signal_identifier"] != SignalIdentifier.AVDTP_GET_CONFIGURATION
        ):
            raise Exception("Invalid constraint field values")
        service_capabilities = []
        while len(span) > 0:
            element, span = ServiceCapability.parse(span)
            service_capabilities.append(element)
        fields["service_capabilities"] = service_capabilities
        return GetConfigurationResponse(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        for _elt in self.service_capabilities:
            _span.extend(_elt.serialize())
        return SignalingPacket.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return sum([elt.size for elt in self.service_capabilities])


@dataclass
class GetConfigurationReject(SignalingPacket):
    error_code: ErrorCode = field(kw_only=True, default=ErrorCode.SUCCESS)

    def __post_init__(self):
        self.message_type = MessageType.RESPONSE_REJECT
        self.signal_identifier = SignalIdentifier.AVDTP_GET_CONFIGURATION

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["GetConfigurationReject", bytes]:
        if (
            fields["message_type"] != MessageType.RESPONSE_REJECT
            or fields["signal_identifier"] != SignalIdentifier.AVDTP_GET_CONFIGURATION
        ):
            raise Exception("Invalid constraint field values")
        if len(span) < 1:
            raise Exception("Invalid packet size")
        fields["error_code"] = ErrorCode.from_int(span[0])
        span = span[1:]
        return GetConfigurationReject(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        _span.append((self.error_code << 0))
        return SignalingPacket.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return 1


@dataclass
class ReconfigureCommand(SignalingPacket):
    acp_seid: int = field(kw_only=True, default=0)
    service_capabilities: List[ServiceCapability] = field(
        kw_only=True, default_factory=list
    )

    def __post_init__(self):
        self.message_type = MessageType.COMMAND
        self.signal_identifier = SignalIdentifier.AVDTP_RECONFIGURE

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["ReconfigureCommand", bytes]:
        if (
            fields["message_type"] != MessageType.COMMAND
            or fields["signal_identifier"] != SignalIdentifier.AVDTP_RECONFIGURE
        ):
            raise Exception("Invalid constraint field values")
        if len(span) < 1:
            raise Exception("Invalid packet size")
        fields["acp_seid"] = (span[0] >> 2) & 0x3F
        span = span[1:]
        service_capabilities = []
        while len(span) > 0:
            element, span = ServiceCapability.parse(span)
            service_capabilities.append(element)
        fields["service_capabilities"] = service_capabilities
        return ReconfigureCommand(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        if self.acp_seid > 63:
            print(
                f"Invalid value for field ReconfigureCommand::acp_seid: {self.acp_seid} > 63; the value will be truncated"
            )
            self.acp_seid &= 63
        _span.append((self.acp_seid << 2))
        for _elt in self.service_capabilities:
            _span.extend(_elt.serialize())
        return SignalingPacket.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return sum([elt.size for elt in self.service_capabilities]) + 1


@dataclass
class ReconfigureResponse(SignalingPacket):

    def __post_init__(self):
        self.message_type = MessageType.RESPONSE_ACCEPT
        self.signal_identifier = SignalIdentifier.AVDTP_RECONFIGURE

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["ReconfigureResponse", bytes]:
        if (
            fields["message_type"] != MessageType.RESPONSE_ACCEPT
            or fields["signal_identifier"] != SignalIdentifier.AVDTP_RECONFIGURE
        ):
            raise Exception("Invalid constraint field values")
        return ReconfigureResponse(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        return SignalingPacket.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return 0


@dataclass
class ReconfigureReject(SignalingPacket):
    service_category: int = field(kw_only=True, default=0)
    error_code: ErrorCode = field(kw_only=True, default=ErrorCode.SUCCESS)

    def __post_init__(self):
        self.message_type = MessageType.RESPONSE_REJECT
        self.signal_identifier = SignalIdentifier.AVDTP_RECONFIGURE

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["ReconfigureReject", bytes]:
        if (
            fields["message_type"] != MessageType.RESPONSE_REJECT
            or fields["signal_identifier"] != SignalIdentifier.AVDTP_RECONFIGURE
        ):
            raise Exception("Invalid constraint field values")
        if len(span) < 2:
            raise Exception("Invalid packet size")
        fields["service_category"] = span[0]
        fields["error_code"] = ErrorCode.from_int(span[1])
        span = span[2:]
        return ReconfigureReject(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        if self.service_category > 255:
            print(
                f"Invalid value for field ReconfigureReject::service_category: {self.service_category} > 255; the value will be truncated"
            )
            self.service_category &= 255
        _span.append((self.service_category << 0))
        _span.append((self.error_code << 0))
        return SignalingPacket.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return 2


@dataclass
class OpenCommand(SignalingPacket):
    acp_seid: int = field(kw_only=True, default=0)

    def __post_init__(self):
        self.message_type = MessageType.COMMAND
        self.signal_identifier = SignalIdentifier.AVDTP_OPEN

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["OpenCommand", bytes]:
        if (
            fields["message_type"] != MessageType.COMMAND
            or fields["signal_identifier"] != SignalIdentifier.AVDTP_OPEN
        ):
            raise Exception("Invalid constraint field values")
        if len(span) < 1:
            raise Exception("Invalid packet size")
        fields["acp_seid"] = (span[0] >> 2) & 0x3F
        span = span[1:]
        return OpenCommand(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        if self.acp_seid > 63:
            print(
                f"Invalid value for field OpenCommand::acp_seid: {self.acp_seid} > 63; the value will be truncated"
            )
            self.acp_seid &= 63
        _span.append((self.acp_seid << 2))
        return SignalingPacket.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return 1


@dataclass
class OpenResponse(SignalingPacket):

    def __post_init__(self):
        self.message_type = MessageType.RESPONSE_ACCEPT
        self.signal_identifier = SignalIdentifier.AVDTP_OPEN

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["OpenResponse", bytes]:
        if (
            fields["message_type"] != MessageType.RESPONSE_ACCEPT
            or fields["signal_identifier"] != SignalIdentifier.AVDTP_OPEN
        ):
            raise Exception("Invalid constraint field values")
        return OpenResponse(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        return SignalingPacket.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return 0


@dataclass
class OpenReject(SignalingPacket):
    error_code: ErrorCode = field(kw_only=True, default=ErrorCode.SUCCESS)

    def __post_init__(self):
        self.message_type = MessageType.RESPONSE_REJECT
        self.signal_identifier = SignalIdentifier.AVDTP_OPEN

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["OpenReject", bytes]:
        if (
            fields["message_type"] != MessageType.RESPONSE_REJECT
            or fields["signal_identifier"] != SignalIdentifier.AVDTP_OPEN
        ):
            raise Exception("Invalid constraint field values")
        if len(span) < 1:
            raise Exception("Invalid packet size")
        fields["error_code"] = ErrorCode.from_int(span[0])
        span = span[1:]
        return OpenReject(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        _span.append((self.error_code << 0))
        return SignalingPacket.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return 1


@dataclass
class StartCommand(SignalingPacket):
    acp_seid: int = field(kw_only=True, default=0)

    def __post_init__(self):
        self.message_type = MessageType.COMMAND
        self.signal_identifier = SignalIdentifier.AVDTP_START

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["StartCommand", bytes]:
        if (
            fields["message_type"] != MessageType.COMMAND
            or fields["signal_identifier"] != SignalIdentifier.AVDTP_START
        ):
            raise Exception("Invalid constraint field values")
        if len(span) < 1:
            raise Exception("Invalid packet size")
        fields["acp_seid"] = (span[0] >> 2) & 0x3F
        span = span[1:]
        return StartCommand(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        if self.acp_seid > 63:
            print(
                f"Invalid value for field StartCommand::acp_seid: {self.acp_seid} > 63; the value will be truncated"
            )
            self.acp_seid &= 63
        _span.append((self.acp_seid << 2))
        return SignalingPacket.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return 1


@dataclass
class StartResponse(SignalingPacket):

    def __post_init__(self):
        self.message_type = MessageType.RESPONSE_ACCEPT
        self.signal_identifier = SignalIdentifier.AVDTP_START

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["StartResponse", bytes]:
        if (
            fields["message_type"] != MessageType.RESPONSE_ACCEPT
            or fields["signal_identifier"] != SignalIdentifier.AVDTP_START
        ):
            raise Exception("Invalid constraint field values")
        return StartResponse(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        return SignalingPacket.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return 0


@dataclass
class StartReject(SignalingPacket):
    acp_seid: int = field(kw_only=True, default=0)
    error_code: ErrorCode = field(kw_only=True, default=ErrorCode.SUCCESS)

    def __post_init__(self):
        self.message_type = MessageType.RESPONSE_REJECT
        self.signal_identifier = SignalIdentifier.AVDTP_START

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["StartReject", bytes]:
        if (
            fields["message_type"] != MessageType.RESPONSE_REJECT
            or fields["signal_identifier"] != SignalIdentifier.AVDTP_START
        ):
            raise Exception("Invalid constraint field values")
        if len(span) < 2:
            raise Exception("Invalid packet size")
        fields["acp_seid"] = (span[0] >> 2) & 0x3F
        fields["error_code"] = ErrorCode.from_int(span[1])
        span = span[2:]
        return StartReject(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        if self.acp_seid > 63:
            print(
                f"Invalid value for field StartReject::acp_seid: {self.acp_seid} > 63; the value will be truncated"
            )
            self.acp_seid &= 63
        _span.append((self.acp_seid << 2))
        _span.append((self.error_code << 0))
        return SignalingPacket.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return 2


@dataclass
class CloseCommand(SignalingPacket):
    acp_seid: int = field(kw_only=True, default=0)

    def __post_init__(self):
        self.message_type = MessageType.COMMAND
        self.signal_identifier = SignalIdentifier.AVDTP_CLOSE

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["CloseCommand", bytes]:
        if (
            fields["message_type"] != MessageType.COMMAND
            or fields["signal_identifier"] != SignalIdentifier.AVDTP_CLOSE
        ):
            raise Exception("Invalid constraint field values")
        if len(span) < 1:
            raise Exception("Invalid packet size")
        fields["acp_seid"] = span[0]
        span = span[1:]
        return CloseCommand(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        if self.acp_seid > 255:
            print(
                f"Invalid value for field CloseCommand::acp_seid: {self.acp_seid} > 255; the value will be truncated"
            )
            self.acp_seid &= 255
        _span.append((self.acp_seid << 0))
        return SignalingPacket.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return 1


@dataclass
class CloseResponse(SignalingPacket):

    def __post_init__(self):
        self.message_type = MessageType.RESPONSE_ACCEPT
        self.signal_identifier = SignalIdentifier.AVDTP_CLOSE

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["CloseResponse", bytes]:
        if (
            fields["message_type"] != MessageType.RESPONSE_ACCEPT
            or fields["signal_identifier"] != SignalIdentifier.AVDTP_CLOSE
        ):
            raise Exception("Invalid constraint field values")
        return CloseResponse(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        return SignalingPacket.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return 0


@dataclass
class CloseReject(SignalingPacket):
    error_code: ErrorCode = field(kw_only=True, default=ErrorCode.SUCCESS)

    def __post_init__(self):
        self.message_type = MessageType.RESPONSE_REJECT
        self.signal_identifier = SignalIdentifier.AVDTP_CLOSE

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["CloseReject", bytes]:
        if (
            fields["message_type"] != MessageType.RESPONSE_REJECT
            or fields["signal_identifier"] != SignalIdentifier.AVDTP_CLOSE
        ):
            raise Exception("Invalid constraint field values")
        if len(span) < 1:
            raise Exception("Invalid packet size")
        fields["error_code"] = ErrorCode.from_int(span[0])
        span = span[1:]
        return CloseReject(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        _span.append((self.error_code << 0))
        return SignalingPacket.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return 1


@dataclass
class SuspendCommand(SignalingPacket):
    acp_seid: int = field(kw_only=True, default=0)

    def __post_init__(self):
        self.message_type = MessageType.COMMAND
        self.signal_identifier = SignalIdentifier.AVDTP_SUSPEND

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["SuspendCommand", bytes]:
        if (
            fields["message_type"] != MessageType.COMMAND
            or fields["signal_identifier"] != SignalIdentifier.AVDTP_SUSPEND
        ):
            raise Exception("Invalid constraint field values")
        if len(span) < 1:
            raise Exception("Invalid packet size")
        fields["acp_seid"] = (span[0] >> 2) & 0x3F
        span = span[1:]
        return SuspendCommand(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        if self.acp_seid > 63:
            print(
                f"Invalid value for field SuspendCommand::acp_seid: {self.acp_seid} > 63; the value will be truncated"
            )
            self.acp_seid &= 63
        _span.append((self.acp_seid << 2))
        return SignalingPacket.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return 1


@dataclass
class SuspendResponse(SignalingPacket):

    def __post_init__(self):
        self.message_type = MessageType.RESPONSE_ACCEPT
        self.signal_identifier = SignalIdentifier.AVDTP_SUSPEND

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["SuspendResponse", bytes]:
        if (
            fields["message_type"] != MessageType.RESPONSE_ACCEPT
            or fields["signal_identifier"] != SignalIdentifier.AVDTP_SUSPEND
        ):
            raise Exception("Invalid constraint field values")
        return SuspendResponse(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        return SignalingPacket.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return 0


@dataclass
class SuspendReject(SignalingPacket):
    acp_seid: int = field(kw_only=True, default=0)
    error_code: ErrorCode = field(kw_only=True, default=ErrorCode.SUCCESS)

    def __post_init__(self):
        self.message_type = MessageType.RESPONSE_REJECT
        self.signal_identifier = SignalIdentifier.AVDTP_SUSPEND

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["SuspendReject", bytes]:
        if (
            fields["message_type"] != MessageType.RESPONSE_REJECT
            or fields["signal_identifier"] != SignalIdentifier.AVDTP_SUSPEND
        ):
            raise Exception("Invalid constraint field values")
        if len(span) < 2:
            raise Exception("Invalid packet size")
        fields["acp_seid"] = (span[0] >> 2) & 0x3F
        fields["error_code"] = ErrorCode.from_int(span[1])
        span = span[2:]
        return SuspendReject(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        if self.acp_seid > 63:
            print(
                f"Invalid value for field SuspendReject::acp_seid: {self.acp_seid} > 63; the value will be truncated"
            )
            self.acp_seid &= 63
        _span.append((self.acp_seid << 2))
        _span.append((self.error_code << 0))
        return SignalingPacket.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return 2


@dataclass
class AbortCommand(SignalingPacket):
    acp_seid: int = field(kw_only=True, default=0)

    def __post_init__(self):
        self.message_type = MessageType.COMMAND
        self.signal_identifier = SignalIdentifier.AVDTP_ABORT

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["AbortCommand", bytes]:
        if (
            fields["message_type"] != MessageType.COMMAND
            or fields["signal_identifier"] != SignalIdentifier.AVDTP_ABORT
        ):
            raise Exception("Invalid constraint field values")
        if len(span) < 1:
            raise Exception("Invalid packet size")
        fields["acp_seid"] = (span[0] >> 2) & 0x3F
        span = span[1:]
        return AbortCommand(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        if self.acp_seid > 63:
            print(
                f"Invalid value for field AbortCommand::acp_seid: {self.acp_seid} > 63; the value will be truncated"
            )
            self.acp_seid &= 63
        _span.append((self.acp_seid << 2))
        return SignalingPacket.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return 1


@dataclass
class AbortResponse(SignalingPacket):

    def __post_init__(self):
        self.message_type = MessageType.RESPONSE_ACCEPT
        self.signal_identifier = SignalIdentifier.AVDTP_ABORT

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["AbortResponse", bytes]:
        if (
            fields["message_type"] != MessageType.RESPONSE_ACCEPT
            or fields["signal_identifier"] != SignalIdentifier.AVDTP_ABORT
        ):
            raise Exception("Invalid constraint field values")
        return AbortResponse(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        return SignalingPacket.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return 0


@dataclass
class SecurityControlCommand(SignalingPacket):
    acp_seid: int = field(kw_only=True, default=0)
    content_protection_data: bytearray = field(kw_only=True, default_factory=bytearray)

    def __post_init__(self):
        self.message_type = MessageType.COMMAND
        self.signal_identifier = SignalIdentifier.AVDTP_SECURITY_CONTROL

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["SecurityControlCommand", bytes]:
        if (
            fields["message_type"] != MessageType.COMMAND
            or fields["signal_identifier"] != SignalIdentifier.AVDTP_SECURITY_CONTROL
        ):
            raise Exception("Invalid constraint field values")
        if len(span) < 1:
            raise Exception("Invalid packet size")
        fields["acp_seid"] = (span[0] >> 2) & 0x3F
        span = span[1:]
        fields["content_protection_data"] = list(span)
        span = bytes()
        return SecurityControlCommand(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        if self.acp_seid > 63:
            print(
                f"Invalid value for field SecurityControlCommand::acp_seid: {self.acp_seid} > 63; the value will be truncated"
            )
            self.acp_seid &= 63
        _span.append((self.acp_seid << 2))
        _span.extend(self.content_protection_data)
        return SignalingPacket.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return len(self.content_protection_data) * 1 + 1


@dataclass
class SecurityControlResponse(SignalingPacket):
    content_protection_data: bytearray = field(kw_only=True, default_factory=bytearray)

    def __post_init__(self):
        self.message_type = MessageType.RESPONSE_ACCEPT
        self.signal_identifier = SignalIdentifier.AVDTP_SECURITY_CONTROL

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["SecurityControlResponse", bytes]:
        if (
            fields["message_type"] != MessageType.RESPONSE_ACCEPT
            or fields["signal_identifier"] != SignalIdentifier.AVDTP_SECURITY_CONTROL
        ):
            raise Exception("Invalid constraint field values")
        fields["content_protection_data"] = list(span)
        span = bytes()
        return SecurityControlResponse(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        _span.extend(self.content_protection_data)
        return SignalingPacket.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return len(self.content_protection_data) * 1


@dataclass
class SecurityControlReject(SignalingPacket):
    error_code: ErrorCode = field(kw_only=True, default=ErrorCode.SUCCESS)

    def __post_init__(self):
        self.message_type = MessageType.RESPONSE_REJECT
        self.signal_identifier = SignalIdentifier.AVDTP_SECURITY_CONTROL

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["SecurityControlReject", bytes]:
        if (
            fields["message_type"] != MessageType.RESPONSE_REJECT
            or fields["signal_identifier"] != SignalIdentifier.AVDTP_SECURITY_CONTROL
        ):
            raise Exception("Invalid constraint field values")
        if len(span) < 1:
            raise Exception("Invalid packet size")
        fields["error_code"] = ErrorCode.from_int(span[0])
        span = span[1:]
        return SecurityControlReject(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        _span.append((self.error_code << 0))
        return SignalingPacket.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return 1


@dataclass
class GeneralReject(SignalingPacket):

    def __post_init__(self):
        self.message_type = MessageType.GENERAL_REJECT

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["GeneralReject", bytes]:
        if fields["message_type"] != MessageType.GENERAL_REJECT:
            raise Exception("Invalid constraint field values")
        return GeneralReject(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        return SignalingPacket.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return 0


@dataclass
class DelayReportCommand(SignalingPacket):
    acp_seid: int = field(kw_only=True, default=0)
    delay_msb: int = field(kw_only=True, default=0)
    delay_lsb: int = field(kw_only=True, default=0)

    def __post_init__(self):
        self.message_type = MessageType.COMMAND
        self.signal_identifier = SignalIdentifier.AVDTP_DELAYREPORT

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["DelayReportCommand", bytes]:
        if (
            fields["message_type"] != MessageType.COMMAND
            or fields["signal_identifier"] != SignalIdentifier.AVDTP_DELAYREPORT
        ):
            raise Exception("Invalid constraint field values")
        if len(span) < 3:
            raise Exception("Invalid packet size")
        fields["acp_seid"] = (span[0] >> 2) & 0x3F
        fields["delay_msb"] = span[1]
        fields["delay_lsb"] = span[2]
        span = span[3:]
        return DelayReportCommand(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        if self.acp_seid > 63:
            print(
                f"Invalid value for field DelayReportCommand::acp_seid: {self.acp_seid} > 63; the value will be truncated"
            )
            self.acp_seid &= 63
        _span.append((self.acp_seid << 2))
        if self.delay_msb > 255:
            print(
                f"Invalid value for field DelayReportCommand::delay_msb: {self.delay_msb} > 255; the value will be truncated"
            )
            self.delay_msb &= 255
        _span.append((self.delay_msb << 0))
        if self.delay_lsb > 255:
            print(
                f"Invalid value for field DelayReportCommand::delay_lsb: {self.delay_lsb} > 255; the value will be truncated"
            )
            self.delay_lsb &= 255
        _span.append((self.delay_lsb << 0))
        return SignalingPacket.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return 3


@dataclass
class DelayReportResponse(SignalingPacket):

    def __post_init__(self):
        self.message_type = MessageType.RESPONSE_ACCEPT
        self.signal_identifier = SignalIdentifier.AVDTP_DELAYREPORT

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["DelayReportResponse", bytes]:
        if (
            fields["message_type"] != MessageType.RESPONSE_ACCEPT
            or fields["signal_identifier"] != SignalIdentifier.AVDTP_DELAYREPORT
        ):
            raise Exception("Invalid constraint field values")
        return DelayReportResponse(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        return SignalingPacket.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return 0


@dataclass
class DelayReportReject(SignalingPacket):
    error_code: ErrorCode = field(kw_only=True, default=ErrorCode.SUCCESS)

    def __post_init__(self):
        self.message_type = MessageType.RESPONSE_REJECT
        self.signal_identifier = SignalIdentifier.AVDTP_DELAYREPORT

    @staticmethod
    def parse(fields: dict, span: bytes) -> Tuple["DelayReportReject", bytes]:
        if (
            fields["message_type"] != MessageType.RESPONSE_REJECT
            or fields["signal_identifier"] != SignalIdentifier.AVDTP_DELAYREPORT
        ):
            raise Exception("Invalid constraint field values")
        if len(span) < 1:
            raise Exception("Invalid packet size")
        fields["error_code"] = ErrorCode.from_int(span[0])
        span = span[1:]
        return DelayReportReject(**fields), span

    def serialize(self, payload: bytes = None) -> bytes:
        _span = bytearray()
        _span.append((self.error_code << 0))
        return SignalingPacket.serialize(self, payload=bytes(_span))

    @property
    def size(self) -> int:
        return 1
