import enum


class PcmFormat(enum.IntEnum):
    S16 = 0
    S24 = 1
    S24_3LE = 2
    FLOAT = 3


class Encoder:

    def __init__(self, duration_us: int, sample_rate_hz: int, pcm_sample_rate_hz: int):
        ...

    def encode(
        self,
        fmt: int,
        input: bytes | bytearray | memoryview,
        output_size: int,
    ) -> bytes:
        ...

    def get_frame_samples(self) -> int:
        ...


class Decoder:

    def __init__(self, duration_us: int, sample_rate_hz: int, pcm_sample_rate_hz: int):
        ...

    def decode(self, input: bytes | bytearray | memoryview, fmt: int) -> bytes:
        ...

    def get_frame_samples(self) -> int:
        ...
