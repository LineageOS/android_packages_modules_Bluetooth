"""LC3 encoder and decoder."""

import array
import enum

Buffer = bytes | bytearray | memoryview


class PcmFormat(enum.IntEnum):
    """PCM format. Same as value defined in liblc3."""

    SIGNED_16 = 0
    FLOAT_32 = 3

    @property
    def sample_width(self) -> int:
        ...

    @property
    def array_type_code(self) -> str:
        ...


class Encoder:
    """LC3 encoder."""

    frame_duration_us: int
    sample_rate_hz: int
    input_sample_rate_hz: int
    num_channels: int

    def __init__(
        self,
        frame_duration_us: int,
        sample_rate_hz: int,
        input_sample_rate_hz: int,
        num_channels: int = 1,
    ) -> None:
        ...

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


class Decoder:
    """LC3 decoder."""

    frame_duration_us: int
    sample_rate_hz: int
    pcm_sample_rate_hz: int
    num_channels: int

    def __init__(
        self,
        frame_duration_us: int,
        sample_rate_hz: int,
        pcm_sample_rate_hz: int,
        num_channels: int = 1,
    ) -> None:
        ...

    def decode(self, frame: Buffer, fmt: PcmFormat) -> bytes:
        """Decodes LC3 a frame.

    Args:
      frame: LC3 frame to decode.
      fmt: PCM sample format.

    Returns:
      Decoded PCM samples.
    """
