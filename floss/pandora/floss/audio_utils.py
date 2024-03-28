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

import logging
import os
import subprocess
import wave

from floss.pandora.floss import audio_test_data
from floss.pandora.floss import cras_utils
from floss.pandora.floss import sox_utils
from floss.pandora.floss import utils

CRAS_BLUETOOTH_OUTPUT_NODE_TYPE = 'BLUETOOTH'

AUDIO_TEST_DIR = '/tmp/audio'

A2DP_TEST_DATA = {
    'rate': 48000,
    'channels': 2,
    'frequencies': (440, 20000),
    'file': os.path.join(AUDIO_TEST_DIR, 'binaural_sine_440hz_20000hz_rate48000_5secs.wav'),
    'recorded_by_sink': os.path.join(AUDIO_TEST_DIR, 'a2dp_recorded_by_sink.wav'),
    'chunk_in_secs': 5,
    'bit_width': 16,
    'format': 'S16_LE',
    'duration': 5,
}

A2DP_PLAYBACK_DATA = {
    'rate': 44100,
    'channels': 2,
    'file': os.path.join(AUDIO_TEST_DIR, 'audio_playback.wav'),
    'sample_width': 2
}

SAMPLE_FORMATS = dict(S32_LE=dict(message='Signed 32-bit integer, little-endian', dtype_str='<i', size_bytes=4),
                      S16_LE=dict(message='Signed 16-bit integer, little-endian', dtype_str='<i', size_bytes=2))


@utils.dbus_safe(None)
def get_selected_output_device_type():
    """Gets the selected audio output node type.

    Returns:
        The node type of the selected output device.
    """
    return str(cras_utils.get_selected_output_device_type())


@utils.dbus_safe(None)
def select_output_node(node_type):
    """Selects the audio output node.

    Args:
        node_type: The node type of the Bluetooth peer device.

    Returns:
        True if the operation succeeds.
    """
    return cras_utils.set_single_selected_output_node(node_type)


def select_audio_output_node():
    """Selects the audio output node through cras."""

    def bluetooth_type_selected(node_type):
        """Checks if the bluetooth node type is selected."""
        selected = get_selected_output_device_type()
        logging.debug('active output node type: %s, expected %s', selected, node_type)
        return selected == node_type

    node_type = CRAS_BLUETOOTH_OUTPUT_NODE_TYPE
    if not select_output_node(node_type):
        return False

    desc = 'waiting for %s as active cras audio output node type' % node_type
    try:
        utils.poll_for_condition(condition=lambda: bluetooth_type_selected(node_type),
                                 timeout=20,
                                 sleep_interval=1,
                                 desc=desc)
    except TimeoutError:
        return False
    return True


def generate_audio_test_data(path, data_format=None, frequencies=None, duration_secs=None, volume_scale=None):
    """Generates audio test data with specified format and frequencies.

    Args:
        path: The path to the file.
        data_format: A dict containing data format including
                     file_type, sample_format, channel, and rate.
                     file_type: file type e.g. 'raw' or 'wav'.
                     sample_format: One of the keys in audio_data.SAMPLE_FORMAT.
                     channel: number of channels.
                     ate: sampling rate.
        frequencies: A list containing the frequency of each channel in this file.
                     Only applicable to data of sine tone.
        duration_secs: Duration of test file in seconds.
        volume_scale: A float for volume scale used in sox command.
                      E.g. 0.5 to scale volume by half. -1.0 to invert.

    Returns:
        An AudioTestData object.
    """
    sox_file_path = path

    if data_format is None:
        data_format = dict(file_type='wav', sample_format='S16_LE', channel=2, rate=48000)

    sample_format = SAMPLE_FORMATS[data_format['sample_format']]
    bits = sample_format['size_bytes'] * 8

    command = sox_utils.generate_sine_tone_cmd(filename=sox_file_path,
                                               channels=data_format['channel'],
                                               bits=bits,
                                               rate=data_format['rate'],
                                               duration=duration_secs,
                                               frequencies=frequencies,
                                               vol=volume_scale,
                                               raw=(data_format['file_type'] == 'raw'))

    logging.info(' '.join(command))
    subprocess.check_call(command)

    test_data = audio_test_data.AudioTestData(data_format=data_format,
                                              path=sox_file_path,
                                              frequencies=frequencies,
                                              duration_secs=duration_secs)

    return test_data


def generate_playback_file(audio_data):
    """Generates the playback file if it does not exist yet.

    Some audio test files may be large. Generate them on the fly to save the storage of the source tree.

    Args:
        audio_data: The audio test data.
    """
    directory = os.path.dirname(audio_data['file'])
    if not os.path.exists(directory):
        os.makedirs(directory)

    if not os.path.exists(audio_data['file']):
        data_format = dict(file_type='wav',
                           sample_format='S16_LE',
                           channel=audio_data['channels'],
                           rate=audio_data['rate'])
        generate_audio_test_data(data_format=data_format,
                                 path=audio_data['file'],
                                 duration_secs=audio_data['duration'],
                                 frequencies=audio_data['frequencies'])
        logging.debug('Audio file generated: %s', audio_data['file'])


def generate_playback_file_from_binary_data(audio_data):
    """Generates wav audio file from binary audio data.

    Args:
        audio_data: The binary audio data.
    """
    directory = os.path.dirname(A2DP_PLAYBACK_DATA['file'])
    if not os.path.exists(directory):
        os.makedirs(directory)

    with wave.open(A2DP_PLAYBACK_DATA['file'], 'wb') as wav_file:
        wav_file.setnchannels(A2DP_PLAYBACK_DATA['channels'])
        wav_file.setframerate(A2DP_PLAYBACK_DATA['rate'])
        wav_file.setsampwidth(A2DP_PLAYBACK_DATA['sample_width'])
        wav_file.writeframes(audio_data)

    logging.debug('wav file generated from binary data: %s', A2DP_PLAYBACK_DATA['file'])
