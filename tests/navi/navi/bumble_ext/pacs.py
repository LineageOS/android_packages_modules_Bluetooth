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
"""Simple pacs configuration."""

from collections.abc import Sequence

from bumble import hci
from bumble.profiles import bap
from bumble.profiles import pacs


def make_pacs(
    audio_location: bap.AudioLocation | None = None,
    source_pacs: Sequence[pacs.PacRecord] | None = None,
    sink_pacs: Sequence[pacs.PacRecord] | None = None,
) -> pacs.PublishedAudioCapabilitiesService:
    """Creates a PACS service.

  Args:
    audio_location: The audio location of the PACS. If None, defaults to
      (FRONT_LEFT | FRONT_RIGHT) for sink and (FRONT_LEFT) for source.
    source_pacs: The source PACs. If None, defaults to LC3 with sampling
      frequencies 16000, 32000 Hz and channel count 1.
    sink_pacs: The sink PACs. If None, defaults to LC3 with sampling frequencies
      16000, 32000, 48000 Hz and channel count 1, 2.

  Returns:
    A PACS service.
  """
    return pacs.PublishedAudioCapabilitiesService(
        supported_source_context=bap.ContextType(0xFFFF),
        available_source_context=bap.ContextType(0xFFFF),
        supported_sink_context=bap.ContextType(0xFFFF),
        available_sink_context=bap.ContextType(0xFFFF),
        sink_audio_locations=audio_location or
        (bap.AudioLocation.FRONT_LEFT | bap.AudioLocation.FRONT_RIGHT),
        source_audio_locations=audio_location or (bap.AudioLocation.FRONT_LEFT),
        sink_pac=sink_pacs or [
            pacs.PacRecord(
                coding_format=hci.CodingFormat(hci.CodecID.LC3),
                codec_specific_capabilities=bap.CodecSpecificCapabilities(
                    supported_sampling_frequencies=(bap.SupportedSamplingFrequency.FREQ_16000 |
                                                    bap.SupportedSamplingFrequency.FREQ_32000 |
                                                    bap.SupportedSamplingFrequency.FREQ_48000),
                    supported_frame_durations=(
                        bap.SupportedFrameDuration.DURATION_10000_US_SUPPORTED),
                    supported_audio_channel_count=[1, 2],
                    min_octets_per_codec_frame=26,
                    max_octets_per_codec_frame=240,
                    supported_max_codec_frames_per_sdu=2,
                ),
            )
        ],
        source_pac=source_pacs or [
            pacs.PacRecord(
                coding_format=hci.CodingFormat(hci.CodecID.LC3),
                codec_specific_capabilities=bap.CodecSpecificCapabilities(
                    supported_sampling_frequencies=(bap.SupportedSamplingFrequency.FREQ_16000 |
                                                    bap.SupportedSamplingFrequency.FREQ_32000),
                    supported_frame_durations=(
                        bap.SupportedFrameDuration.DURATION_10000_US_SUPPORTED),
                    supported_audio_channel_count=[1],
                    min_octets_per_codec_frame=13,
                    max_octets_per_codec_frame=120,
                    supported_max_codec_frames_per_sdu=1,
                ),
            )
        ],
    )
