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
"""Simple Ogg/Opus container implementation."""

from collections.abc import Sequence
import dataclasses
import enum
import struct
from typing import Self, SupportsBytes


@dataclasses.dataclass(kw_only=True)
class Page:
    """Ogg page.

  See: https://datatracker.ietf.org/doc/html/rfc3533#section-6.
  """

    class HeaderType(enum.IntFlag):
        IS_CONTINUE = 0x01
        IS_FIRST_PAGE = 0x02
        IS_LAST_PAGE = 0x04

    capture_pattern: bytes = b'OggS'
    version: int = 0
    header_type: HeaderType = HeaderType(0)
    granule_position: int = 0
    bitstream_sesial_number: int = 0
    page_sequence_number: int = 0
    segment_table: Sequence[int] = ()
    payload: bytes | SupportsBytes = dataclasses.field(repr=False)

    @classmethod
    def crc32(cls, data: bytes) -> int:
        """Calculates the CRC32 of the given data."""
        crc = 0
        for b in data:
            crc ^= b << 24
            for _ in range(8):
                crc = (crc << 1) ^ 0x104C11DB7 if (crc & 0x80000000) else crc << 1
        return crc

    def to_bytes(self) -> bytes:
        """Converts the page to bytes."""
        payload = (self.payload if isinstance(self.payload, bytes) else bytes(self.payload))
        if not self.segment_table:
            self.segment_table = [255] * (len(payload) // 255) + [len(payload) % 255]
        data = (self.capture_pattern + struct.pack(
            '<BBQIIIB',
            self.version,
            self.header_type,
            self.granule_position,
            self.bitstream_sesial_number,
            self.page_sequence_number,
            0,
            len(self.segment_table),
        ) + bytes(self.segment_table)) + payload
        checksum = self.crc32(data)
        return data[:22] + struct.pack('<I', checksum) + data[26:]

    def __bytes__(self) -> bytes:
        return self.to_bytes()

    @classmethod
    def from_bytes(cls, data: bytes) -> Self:
        """Creates an OggPage from bytes."""
        (
            capture_pattern,
            version,
            header_type,
            granule_position,
            bitstream_sesial_number,
            page_sequence_number,
            checksum,
            segment_table_size,
        ) = struct.unpack_from('<4sBBQIIIB', data)
        del checksum  # Unused.
        segment_table = data[27:27 + segment_table_size]
        payload = data[27 + segment_table_size:]
        page = cls(
            capture_pattern=capture_pattern,
            version=version,
            header_type=header_type,
            granule_position=granule_position,
            bitstream_sesial_number=bitstream_sesial_number,
            page_sequence_number=page_sequence_number,
            segment_table=segment_table,
            payload=payload,
        )
        return page


@dataclasses.dataclass
class OpusCommentHeader:
    """Opus Comment header.

  See: https://datatracker.ietf.org/doc/html/rfc7845#section-5.2.
  """

    magic_signature: bytes = b'OpusTags'
    vendor_string: bytes = b''
    comment_strings: Sequence[bytes] = ()

    def to_bytes(self) -> bytes:
        """Converts the comment header to bytes."""
        return (
            self.magic_signature + struct.pack('<I', len(self.vendor_string)) + self.vendor_string +
            struct.pack('<I', len(self.comment_strings)) +
            b''.join(struct.pack('<I', len(comment)) + comment for comment in self.comment_strings))

    def __bytes__(self) -> bytes:
        return self.to_bytes()

    @classmethod
    def from_bytes(cls, data: bytes) -> Self:
        """Creates an OpusCommentHeader from bytes."""
        magic_signature, vendor_string_size = struct.unpack_from('<8sI', data)
        offset = 12
        vendor_string = data[offset:offset + vendor_string_size]
        offset += vendor_string_size
        comment_string_count = struct.unpack_from('<I', data, offset)[0]
        offset += 4
        comment_strings: list[bytes] = []
        for _ in range(comment_string_count):
            comment_string_size = struct.unpack_from('<I', data, offset)[0]
            offset += 4
            comment_strings.append(data[offset:offset + comment_string_size])
            offset += comment_string_size
        return cls(
            magic_signature=magic_signature,
            vendor_string=vendor_string,
            comment_strings=comment_strings,
        )


@dataclasses.dataclass
class OpusIdHeader:
    """Opus ID header.

  See: https://datatracker.ietf.org/doc/html/rfc7845#section-5.1.
  """

    magic_signature: bytes = b'OpusHead'
    version: int = 1
    channel_count: int = 2
    preskip: int = 0
    sample_rate: int = 48000
    output_gain: int = 0
    mapping_family: int = 0

    def to_bytes(self) -> bytes:
        """Converts the ID header to bytes."""
        return self.magic_signature + struct.pack(
            '<BBHIIB',
            self.version,
            self.channel_count,
            self.preskip,
            self.sample_rate,
            self.output_gain,
            self.mapping_family,
        )

    def __bytes__(self) -> bytes:
        return self.to_bytes()

    @classmethod
    def from_bytes(cls, data: bytes) -> Self:
        (
            magic_signature,
            version,
            channel_count,
            preskip,
            sample_rate,
            output_gain,
            mapping_family,
        ) = struct.unpack_from('<8sBBHIIB', data)
        return cls(
            magic_signature=magic_signature,
            version=version,
            channel_count=channel_count,
            preskip=preskip,
            sample_rate=sample_rate,
            output_gain=output_gain,
            mapping_family=mapping_family,
        )
