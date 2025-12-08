"""Utilities for audio processing."""

from typing import Any, Iterable, Iterator, Sequence, TypeVar

SUPPORT_AUDIO_PROCESSING: bool


def get_dominant_frequency(
    buffer: bytes | bytearray | memoryview,
    codec: str | None = None,
    format: str | None = None,  # pylint: disable=redefined-builtin
    sample_width: int | None = None,
    frame_rate: int | None = None,
    channels: int | None = None,
) -> float:
    """Gets the dominant frequency of the audio buffer.

  Args:
    buffer: The audio buffer.
    codec: The audio codec.
    format: The audio format.
    sample_width: [Optional for pcm/raw] The sample width.
    frame_rate: [Optional for pcm/raw] The frame rate.
    channels: [Optional for pcm/raw] The number of channels.

  Returns:
    The dominant frequency of the audio buffer.
  """


def generate_sine_tone(
    frequency: float,
    duration: float = 1.0,
    sample_rate: int = 48000,
    data_type: Any = 'int16',
) -> bytes:
    """Generates a sine tone PCM buffer.

  Args:
    frequency: The frequency of the sine tone.
    duration: The duration of the sine tone in seconds.
    sample_rate: The sample rate of the sine tone.
    data_type: The data type of the sine tone.

  Returns:
    The sine tone PCM buffer.
  """


_T = TypeVar('_T')


def batched(iterable: Iterable[_T], n: int) -> Iterator[Sequence[_T]]:
    """Batches an iterator into batches of the given size.

  Similar to Python 3.12 `itertools.batched`, but it's not available in google3
  yet.

  Args:
    iterable: The iterable to batch.
    n: The size of each batch.

  Yields:
    Batches of the given size.
  """
