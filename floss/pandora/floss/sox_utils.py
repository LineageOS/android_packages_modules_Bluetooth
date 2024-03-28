# Copyright 2024 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the 'License');
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an 'AS IS' BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

SOX_PATH = 'sox'


def _raw_format_args(channels, bits, rate):
    """Gets raw format args used in sox.

    Args:
        channels: Number of channels.
        bits: Bit length for a sample.
        rate: Sampling rate.

    Returns:
        A list of args.
    """
    args = ['-t', 'raw', '-e', 'signed']
    args += _format_args(channels, bits, rate)
    return args


def _format_args(channels, bits, rate):
    """Gets format args used in sox.

    Args:
        channels: Number of channels.
        bits: Bit length for a sample.
        rate: Sampling rate.

    Returns:
        A list of args.
    """
    return ['-c', str(channels), '-b', str(bits), '-r', str(rate)]


def generate_sine_tone_cmd(filename,
                           channels=2,
                           bits=16,
                           rate=48000,
                           duration=None,
                           frequencies=440,
                           gain=None,
                           vol=None,
                           raw=True):
    """Gets a command to generate sine tones at specified frequencies.

    Args:
        filename: The name of the file to store the sine wave in.
        channels: The number of channels.
        bits: The number of bits of each sample.
        rate: The sampling rate.
        duration: The length of the generated sine tone (in seconds).
        frequencies: The frequencies of the sine wave. Pass a number or a list to specify frequency for each channel.
        gain: The gain (in db).
        vol: A float for volume scale used in sox command.
             E.g. 1.0 is the same. 0.5 to scale volume by
             half. -1.0 to invert the data.
        raw: True to use raw data format. False to use what filename specifies.
    """
    args = [SOX_PATH, '-n']
    if raw:
        args += _raw_format_args(channels, bits, rate)
    else:
        args += _format_args(channels, bits, rate)
    args.append(filename)
    args.append('synth')
    if duration is not None:
        args.append(str(duration))
    if not isinstance(frequencies, list):
        frequencies = [frequencies]
    for freq in frequencies:
        args += ['sine', str(freq)]
    if gain is not None:
        args += ['gain', str(gain)]
    if vol is not None:
        args += ['vol', str(vol)]
    return args
