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
"""This module provides audio test data."""

import os


class AudioTestDataException(Exception):
    """Exception for audio test data."""
    pass


class AudioTestData(object):
    """Class to represent audio test data."""

    def __init__(self, data_format=None, path=None, frequencies=None, duration_secs=None):
        """Initializes an audio test file.

        Args:
            data_format: A dict containing data format including
                         file_type, sample_format, channel, and rate.
                         file_type: file type e.g. 'raw' or 'wav'.
                         sample_format: One of the keys in audio_utils.SAMPLE_FORMAT.
                         channel: number of channels.
                         rate: sampling rate.
            path: The path to the file.
            frequencies: A list containing the frequency of each channel in this file.
                         Only applicable to data of sine tone.
            duration_secs: Duration of test file in seconds.

        Raises:
            AudioTestDataException if the path does not exist.
        """
        self.data_format = data_format
        if not os.path.exists(path):
            raise AudioTestDataException('Can not find path %s' % path)
        self.path = path
        self.frequencies = frequencies
        self.duration_secs = duration_secs
