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
"""LC3 encoder and decoder."""

import array
import enum
import logging

# pylint: disable=g-import-not-at-top
try:
    from navi.utils import lc3_pybind

    AVAILABLE = True
except ImportError:
    logging.warning("LC3 encoder and decoder are not available.")
    AVAILABLE = False

Buffer = bytes | bytearray | memoryview


class PcmFormat(enum.IntEnum):
    """PCM format. Same as value defined in liblc3."""

    SIGNED_16 = 0
    FLOAT_32 = 3

    @property
    def sample_width(self) -> int:
        return 2 if self == PcmFormat.SIGNED_16 else 4

    @property
    def array_type_code(self) -> str:
        return "h" if self == PcmFormat.SIGNED_16 else "f"


class Encoder:
    """LC3 encoder."""

    def __init__(
        self,
        frame_duration_us: int,
        sample_rate_hz: int,
        input_sample_rate_hz: int,
        num_channels: int = 1,
    ) -> None:
        self.frame_duration_us = frame_duration_us
        self.sample_rate_hz = sample_rate_hz
        self.input_sample_rate_hz = input_sample_rate_hz
        self.num_channels = num_channels
        self._impls = [
            lc3_pybind.Encoder(frame_duration_us, sample_rate_hz, input_sample_rate_hz)
            for _ in range(num_channels)
        ]

    def encode(self, pcm: Buffer, num_bytes: int, fmt: PcmFormat) -> bytes:
        """Encodes LC3 frame(s).

    Args:
      pcm: PCM samples, as a byte-like object or a vector of floating point
        values.
      num_bytes: Target size, in bytes, of the frame (20 to 400).
      fmt: PCM sample format.

    Returns:
      Encoded LC3 frame(s).
    """
        samples = array.array(fmt.array_type_code, pcm)
        # De-interleave the PCM samples.
        channels = [samples[i::self.num_channels].tobytes() for i in range(self.num_channels)]
        return b"".join(
            impl.encode(
                fmt,  # fmt
                channel,  # pcm
                num_bytes // self.num_channels,  # output_size
            ) for impl, channel in zip(self._impls, channels))


class Decoder:
    """LC3 decoder."""

    def __init__(
        self,
        frame_duration_us: int,
        sample_rate_hz: int,
        pcm_sample_rate_hz: int,
        num_channels: int = 1,
    ) -> None:
        self.frame_duration_us = frame_duration_us
        self.sample_rate_hz = sample_rate_hz
        self.pcm_sample_rate_hz = pcm_sample_rate_hz
        self.num_channels = num_channels
        self._impls = [
            lc3_pybind.Decoder(frame_duration_us, sample_rate_hz, pcm_sample_rate_hz)
            for _ in range(num_channels)
        ]

    def decode(self, frame: Buffer, fmt: PcmFormat) -> bytes:
        """Decodes LC3 a frame.

    Args:
      frame: LC3 frame to decode.
      fmt: PCM sample format.

    Returns:
      Decoded PCM samples.
    """
        bytes_per_channel = len(frame) // self.num_channels
        channels = [
            array.array(
                fmt.array_type_code,
                impl.decode(frame[bytes_per_channel * i:bytes_per_channel * (i + 1)], fmt),
            ) for i, impl in enumerate(self._impls)
        ]
        total_size = sum(channel.itemsize * len(channel) for channel in channels)
        buffer = array.array(fmt.array_type_code, bytes(total_size))
        # Interleave the PCM samples.
        for i, channel in enumerate(channels):
            buffer[i::self.num_channels] = channel
        return buffer.tobytes()
