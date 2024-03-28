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
"""This module provides cras audio utilities."""

import dbus
import logging
import re
import subprocess

from floss.pandora.floss import cmd_utils
from floss.pandora.floss import utils

_CRAS_TEST_CLIENT = '/usr/bin/cras_test_client'


class CrasUtilsError(Exception):
    """Error in CrasUtils."""
    pass


def playback(blocking=True, stdin=None, *args, **kargs):
    """A helper function to execute the playback_cmd.

    Args:
        blocking: Blocks this call until playback finishes.
        stdin: The standard input of playback process.
        args: Args passed to playback_cmd.
        kargs: Kargs passed to playback_cmd.

    Returns:
        The process running the playback command. Note that if the
        blocking parameter is true, this will return a finished process.
    """
    process = cmd_utils.popen(playback_cmd(*args, **kargs), stdin=stdin)
    if blocking:
        cmd_utils.wait_and_check_returncode(process)
    return process


def capture(*args, **kargs):
    """A helper function to execute the capture_cmd.

    Args:
        args: Args passed to capture_cmd.
        kargs: Kargs passed to capture_cmd.
    """
    cmd_utils.execute(capture_cmd(*args, **kargs))


def playback_cmd(playback_file, block_size=None, duration=None, pin_device=None, channels=2, rate=48000):
    """Gets a command to playback a file with given settings.

    Args:
        playback_file: The name of the file to play. '-' indicates to playback raw audio from the stdin.
        pin_device: The device id to playback on.
        block_size: The number of frames per callback(dictates latency).
        duration: Seconds to playback.
        channels: Number of channels.
        rate: The sampling rate.

    Returns:
        The command args put in a list of strings.
    """
    args = [_CRAS_TEST_CLIENT]
    args += ['--playback_file', playback_file]
    if pin_device is not None:
        args += ['--pin_device', str(pin_device)]
    if block_size is not None:
        args += ['--block_size', str(block_size)]
    if duration is not None:
        args += ['--duration', str(duration)]
    args += ['--num_channels', str(channels)]
    args += ['--rate', str(rate)]
    return args


def capture_cmd(capture_file,
                block_size=None,
                duration=10,
                sample_format='S16_LE',
                pin_device=None,
                channels=1,
                rate=48000):
    """Gets a command to capture the audio into the file with given settings.

    Args:
        capture_file: The name of file the audio to be stored in.
        block_size: The number of frames per callback(dictates latency).
        duration: Seconds to record. If it is None, duration is not set,
                  and command will keep capturing audio until it is terminated.
        sample_format: The sample format; possible choices: 'S16_LE', 'S24_LE',
                       and 'S32_LE' default to S16_LE: signed 16 bits/sample, little endian.
        pin_device: The device id to record from.
        channels: Number of channels.
        rate: The sampling rate.

    Returns:
        The command args put in a list of strings.
    """
    args = [_CRAS_TEST_CLIENT]
    args += ['--capture_file', capture_file]
    if pin_device is not None:
        args += ['--pin_device', str(pin_device)]
    if block_size is not None:
        args += ['--block_size', str(block_size)]
    if duration is not None:
        args += ['--duration', str(duration)]
    args += ['--num_channels', str(channels)]
    args += ['--rate', str(rate)]
    args += ['--format', str(sample_format)]
    return args


def listen_cmd(capture_file, block_size=None, duration=10, channels=1, rate=48000):
    """Gets a command to listen on hotword and record audio into the file with given settings.

    Args:
        capture_file: The name of file the audio to be stored in.
        block_size: The number of frames per callback(dictates latency).
        duration: Seconds to record. If it is None, duration is not set, and command
                  will keep capturing audio until it is terminated.
        channels: Number of channels.
        rate: The sampling rate.

    Returns:
        The command args put in a list of strings.
    """
    args = [_CRAS_TEST_CLIENT]
    args += ['--listen_for_hotword', capture_file]
    if block_size is not None:
        args += ['--block_size', str(block_size)]
    if duration is not None:
        args += ['--duration', str(duration)]
    args += ['--num_channels', str(channels)]
    args += ['--rate', str(rate)]
    return args


def loopback(*args, **kargs):
    """A helper function to execute loopback_cmd.

    Args:
        args: Args passed to loopback_cmd.
        kargs: Kargs passed to loopback_cmd.
    """
    cmd_utils.execute(loopback_cmd(*args, **kargs))


def loopback_cmd(output_file, duration=10, channels=2, rate=48000):
    """Gets a command to record the loopback.

    Args:
        output_file: The name of the file the loopback to be stored in.
        channels: The number of channels of the recorded audio.
        duration: Seconds to record.
        rate: The sampling rate.

    Returns:
        The command args put in a list of strings.
    """
    args = [_CRAS_TEST_CLIENT]
    args += ['--loopback_file', output_file]
    args += ['--duration_seconds', str(duration)]
    args += ['--num_channels', str(channels)]
    args += ['--rate', str(rate)]
    return args


def get_cras_nodes_cmd():
    """Gets a command to query the nodes from Cras.

    Returns:
        The command to query nodes information from Cras using dbus-send.
    """
    return ('dbus-send --system --type=method_call --print-reply '
            '--dest=org.chromium.cras /org/chromium/cras '
            'org.chromium.cras.Control.GetNodes')


def _dbus_uint64(x):
    """Returns a UINT64 python-dbus object.

    *Sometimes* python-dbus fails into the following cases:
      - Attempt to convert a 64-bit integer into int32 and overflow
      - Convert `dbus.UInt64(12345678900, variant_level=1)` (we usually get
        this from some DBus calls) into VARIANT rather than UINT64

    This function is a helper to avoid the above flakiness.
    """
    return dbus.types.UInt64(int(x), variant_level=0)


def set_system_volume(volume):
    """Sets the system volume.

    Args:
        volume: The system output vlume to be set(0 - 100).
    """
    get_cras_control_interface().SetOutputVolume(volume)


def set_node_volume(node_id, volume):
    """Sets the volume of the given output node.

    Args:
        node_id: The id of the output node to be set the volume.
        volume: The volume to be set(0-100).
    """
    get_cras_control_interface().SetOutputNodeVolume(_dbus_uint64(node_id), volume)


def get_cras_control_interface(private=False):
    """Gets Cras DBus control interface.

    Args:
        private: Set to True to use a new instance for dbus.SystemBus instead of the shared instance.

    Returns:
        A dBus.Interface object with Cras Control interface.
    """
    bus = dbus.SystemBus(private=private)
    cras_object = bus.get_object('org.chromium.cras', '/org/chromium/cras')
    return dbus.Interface(cras_object, 'org.chromium.cras.Control')


def get_cras_nodes():
    """Gets nodes information from Cras.

    Returns:
        A dict containing information of each node.
    """
    return get_cras_control_interface().GetNodes()


def get_selected_nodes():
    """Gets selected output nodes and input nodes.

    Returns:
        A tuple (output_nodes, input_nodes) where each field is a list of selected
        node IDs returned from Cras DBus API. Note that there may be multiple output/input
        nodes being selected at the same time.
    """
    output_nodes = []
    input_nodes = []
    nodes = get_cras_nodes()
    for node in nodes:
        if node['Active']:
            if node['IsInput']:
                input_nodes.append(node['Id'])
            else:
                output_nodes.append(node['Id'])
    return (output_nodes, input_nodes)


def set_selected_output_node_volume(volume):
    """Sets the selected output node volume.

    Args:
        volume: The volume to be set (0-100).
    """
    selected_output_node_ids, _ = get_selected_nodes()
    for node_id in selected_output_node_ids:
        set_node_volume(node_id, volume)


def get_active_stream_count():
    """Gets the number of active streams.

    Returns:
        The number of active streams.
    """
    return int(get_cras_control_interface().GetNumberOfActiveStreams())


def set_system_mute(is_mute):
    """Sets the system mute switch.

    Args:
        is_mute: Set True to mute the system playback.
    """
    get_cras_control_interface().SetOutputMute(is_mute)


def set_capture_mute(is_mute):
    """Sets the capture mute switch.

    Args:
        is_mute: Set True to mute the capture.
    """
    get_cras_control_interface().SetInputMute(is_mute)


def node_type_is_plugged(node_type, nodes_info):
    """Determines if there is any node of node_type plugged.

    This method is used in the AudioLoopbackDongleLabel class, where the call is
    executed on autotest server. Use get_cras_nodes instead if the call can be executed
    on Cros device.

    Since Cras only reports the plugged node in GetNodes, we can parse the return value
    to see if there is any node with the given type. For example, if INTERNAL_MIC is of
    intereset, the pattern we are looking for is:

    dict entry(
       string "Type"
       variant             string "INTERNAL_MIC"
    )

    Args:
        node_type: A str representing node type defined in CRAS_NODE_TYPES.
        nodes_info: A str containing output of command get_nodes_cmd.

    Returns:
        True if there is any node of node_type plugged. False otherwise.
    """
    match = re.search(r'string "Type"\s+variant\s+string "%s"' % node_type, nodes_info)
    return True if match else False


# Cras node types reported from Cras DBus control API.
CRAS_OUTPUT_NODE_TYPES = [
    'HEADPHONE', 'INTERNAL_SPEAKER', 'HDMI', 'USB', 'BLUETOOTH', 'LINEOUT', 'UNKNOWN', 'ALSA_LOOPBACK'
]
CRAS_INPUT_NODE_TYPES = [
    'MIC', 'INTERNAL_MIC', 'USB', 'BLUETOOTH', 'POST_DSP_LOOPBACK', 'POST_MIX_LOOPBACK', 'UNKNOWN', 'KEYBOARD_MIC',
    'HOTWORD', 'FRONT_MIC', 'REAR_MIC', 'ECHO_REFERENCE'
]
CRAS_NODE_TYPES = CRAS_OUTPUT_NODE_TYPES + CRAS_INPUT_NODE_TYPES


def get_filtered_node_types(callback):
    """Returns the pair of filtered output node types and input node types.

    Args:
        callback: A callback function which takes a node as input parameter
                  and filter the node based on its return value.

    Returns:
        A tuple (output_node_types, input_node_types) where each field is
        a list of node types defined in CRAS_NODE_TYPES, and their 'attribute_name' is True.
    """
    output_node_types = []
    input_node_types = []
    nodes = get_cras_nodes()
    for node in nodes:
        if callback(node):
            node_type = str(node['Type'])
            if node_type not in CRAS_NODE_TYPES:
                logging.warning('node type %s is not in known CRAS_NODE_TYPES', node_type)
            if node['IsInput']:
                input_node_types.append(node_type)
            else:
                output_node_types.append(node_type)
    return (output_node_types, input_node_types)


def get_selected_node_types():
    """Returns the pair of active output node types and input node types.

    Returns:
         A tuple (output_node_types, input_node_types) where each field is a list
         of selected node types defined in CRAS_NODE_TYPES.
    """

    def is_selected(node):
        """Checks if a node is selected.

        A node is selected if its Active attribute is True.

        Returns:
            True is a node is selected, False otherwise.
        """
        return node['Active']

    return get_filtered_node_types(is_selected)


def get_selected_output_device_name():
    """Returns the device name of the active output node.

    Returns:
        device name string. E.g. mtk-rt5650: :0,0.
    """
    nodes = get_cras_nodes()
    for node in nodes:
        if node['Active'] and not node['IsInput']:
            return node['DeviceName']
    return None


def get_selected_output_device_type():
    """Returns the device type of the active output node.

    Returns:
        device type string. E.g. INTERNAL_SPEAKER.
    """
    nodes = get_cras_nodes()
    for node in nodes:
        if node['Active'] and not node['IsInput']:
            return node['Type']
    return None


def set_single_selected_output_node(node_type):
    """Sets one selected output node.

    Note that Chrome UI uses SetActiveOutputNode of Cras DBus API to select one output node.

    Args:
        node_type: A node type.

    Returns:
        True if the output node type is found and set active.
    """
    nodes = get_cras_nodes()
    for node in nodes:
        if node['IsInput']:
            continue
        if node['Type'] == node_type:
            set_active_output_node(node['Id'])
            return True
    return False


def set_selected_output_nodes(types):
    """Sets selected output node types.

    Note that Chrome UI uses SetActiveOutputNode of Cras DBus API to select one
    output node. Here we use add/remove active output node to support multiple nodes.

    Args:
        types: A list of output node types.
    """
    nodes = get_cras_nodes()
    for node in nodes:
        if node['IsInput']:
            continue
        if node['Type'] in types:
            add_active_output_node(node['Id'])
        elif node['Active']:
            remove_active_output_node(node['Id'])


def set_active_output_node(node_id):
    """Sets one active output node.

    Args:
        node_id: node id.
    """
    get_cras_control_interface().SetActiveOutputNode(_dbus_uint64(node_id))


def add_active_output_node(node_id):
    """Adds an active output node.

    Args:
        node_id: node id.
    """
    get_cras_control_interface().AddActiveOutputNode(_dbus_uint64(node_id))


def remove_active_output_node(node_id):
    """Removes an active output node.

    Args:
        node_id: node id.
    """
    get_cras_control_interface().RemoveActiveOutputNode(_dbus_uint64(node_id))


def get_node_id_from_node_type(node_type, is_input):
    """Gets node id from node type.

    Args:
        node_type: A node type defined in CRAS_NODE_TYPES.
        is_input: True if the node is input. False otherwise.

    Returns:
        A string for node id.

    Raises:
        CrasUtilsError: if unique node id can not be found.
    """
    nodes = get_cras_nodes()
    find_ids = []
    for node in nodes:
        if node['Type'] == node_type and node['IsInput'] == is_input:
            find_ids.append(node['Id'])
    if len(find_ids) != 1:
        raise CrasUtilsError('Can not find unique node id from node type %s' % node_type)
    return find_ids[0]


def get_device_id_of(node_id):
    """Gets the device id of the node id.

    The conversion logic is replicated from the CRAS's type definition at
    third_party/adhd/cras/src/common/cras_types.h.

    Args:
        node_id: A string for node id.

    Returns:
        A string for device id.

    Raises:
        CrasUtilsError: if device id is invalid.
    """
    device_id = str(int(node_id) >> 32)
    if device_id == "0":
        raise CrasUtilsError('Got invalid device_id: 0')
    return device_id


def get_device_id_from_node_type(node_type, is_input):
    """Gets device id from node type.

    Args:
        node_type: A node type defined in CRAS_NODE_TYPES.
        is_input: True if the node is input. False otherwise.

    Returns:
        A string for device id.
    """
    node_id = get_node_id_from_node_type(node_type, is_input)
    return get_device_id_of(node_id)


def set_floss_enabled(enabled):
    """Sets whether CRAS stack expects to use Floss.

    Args:
        enabled: True for Floss, False for Bluez.
    """
    get_cras_control_interface().SetFlossEnabled(enabled)


class CrasTestClient(object):
    """An object to perform cras_test_client functions."""

    BLOCK_SIZE = None
    PIN_DEVICE = None
    SAMPLE_FORMAT = 'S16_LE'
    DURATION = 10
    CHANNELS = 2
    RATE = 48000

    def __init__(self):
        self._proc = None
        self._capturing_proc = None
        self._playing_proc = None
        self._capturing_msg = 'capturing audio file'
        self._playing_msg = 'playing audio file'
        self._wbs_cmd = '%s --set_wbs_enabled ' % _CRAS_TEST_CLIENT
        self._enable_wbs_cmd = ('%s 1' % self._wbs_cmd).split()
        self._disable_wbs_cmd = ('%s 0' % self._wbs_cmd).split()
        self._info_cmd = [
            _CRAS_TEST_CLIENT,
        ]
        self._select_input_cmd = '%s --select_input ' % _CRAS_TEST_CLIENT

    def start_subprocess(self, proc, proc_cmd, filename, proc_msg):
        """Starts a capture or play subprocess

        Args:
            proc: The process.
            proc_cmd: The process command and its arguments.
            filename: The file name to capture or play.
            proc_msg: The message to display in logging.

        Returns:
            True if the process is started successfully
        """
        if proc is None:
            try:
                self._proc = subprocess.Popen(proc_cmd)
                logging.debug('Start %s %s on the DUT', proc_msg, filename)
            except Exception as e:
                logging.error('Failed to popen: %s (%s)', proc_msg, e)
                return False
        else:
            logging.error('cannot run the command twice: %s', proc_msg)
            return False
        return True

    def stop_subprocess(self, proc, proc_msg):
        """Stops a subprocess

        Args:
            proc: The process to stop.
            proc_msg: The message to display in logging.

        Returns:
            True if the process is stopped successfully.
        """
        if proc is None:
            logging.error('cannot run stop %s before starting it.', proc_msg)
            return False

        proc.terminate()
        try:
            utils.poll_for_condition(condition=lambda: proc.poll() is not None,
                                     exception=CrasUtilsError,
                                     timeout=10,
                                     sleep_interval=0.5,
                                     desc='Waiting for subprocess to terminate')
        except Exception:
            logging.warn('Killing subprocess due to timeout')
            proc.kill()
            proc.wait()

        logging.debug('stop %s on the DUT', proc_msg)
        return True

    def start_capturing_subprocess(self,
                                   capture_file,
                                   block_size=BLOCK_SIZE,
                                   duration=DURATION,
                                   pin_device=PIN_DEVICE,
                                   sample_format=SAMPLE_FORMAT,
                                   channels=CHANNELS,
                                   rate=RATE):
        """Starts capturing in a subprocess.

        Args:
            capture_file: The name of file the audio to be stored in.
            block_size: The number of frames per callback(dictates latency).
            duration: Seconds to record. If it is None, duration is not set, and
                      will keep capturing audio until terminated.
            sample_format: The sample format.
            pin_device: The device id to record from.
            channels: Number of channels.
            rate: The sampling rate.

        Returns:
            True if the process is started successfully.
        """
        proc_cmd = capture_cmd(capture_file,
                               block_size=block_size,
                               duration=duration,
                               sample_format=sample_format,
                               pin_device=pin_device,
                               channels=channels,
                               rate=rate)
        result = self.start_subprocess(self._capturing_proc, proc_cmd, capture_file, self._capturing_msg)
        if result:
            self._capturing_proc = self._proc
        return result

    def stop_capturing_subprocess(self):
        """Stops the capturing subprocess."""
        result = self.stop_subprocess(self._capturing_proc, self._capturing_msg)
        if result:
            self._capturing_proc = None
        return result

    def start_playing_subprocess(self,
                                 audio_file,
                                 block_size=BLOCK_SIZE,
                                 duration=DURATION,
                                 pin_device=PIN_DEVICE,
                                 channels=CHANNELS,
                                 rate=RATE):
        """Starts playing the audio file in a subprocess.

        Args:
            audio_file: The name of audio file to play.
            block_size: The number of frames per callback(dictates latency).
            duration: Seconds to play. If it is None, duration is not set, and
                      will keep playing audio until terminated.
            pin_device: The device id to play to.
            channels: Number of channels.
            rate: The sampling rate.

        Returns:
            True if the process is started successfully.
        """
        proc_cmd = playback_cmd(audio_file, block_size, duration, pin_device, channels, rate)
        result = self.start_subprocess(self._playing_proc, proc_cmd, audio_file, self._playing_msg)
        if result:
            self._playing_proc = self._proc
        return result

    def stop_playing_subprocess(self):
        """Stops the playing subprocess."""
        result = self.stop_subprocess(self._playing_proc, self._playing_msg)
        if result:
            self._playing_proc = None
        return result

    def play(self,
             audio_file,
             block_size=BLOCK_SIZE,
             duration=DURATION,
             pin_device=PIN_DEVICE,
             channels=CHANNELS,
             rate=RATE):
        """Plays the audio file.

        This method will get blocked until it has completed playing back. If you
        do not want to get blocked, use start_playing_subprocess() above instead.

        Args:
            audio_file: The name of audio file to play.
            block_size: The number of frames per callback(dictates latency).
            duration: Seconds to play. If it is None, duration is not set, and
                      will keep playing audio until terminated.
            pin_device: The device id to play to.
            channels: Number of channels.
            rate: The sampling rate.

        Returns:
            True if the process is started successfully.
        """
        proc_cmd = playback_cmd(audio_file, block_size, duration, pin_device, channels, rate)
        try:
            self._proc = subprocess.call(proc_cmd)
            logging.debug('call "%s" on the DUT', proc_cmd)
        except Exception as e:
            logging.error('Failed to call: %s (%s)', proc_cmd, e)
            return False
        return True

    def enable_wbs(self, value):
        """Enables or disable wideband speech (wbs) per the value.

        Args:
            value: True to enable wbs.

        Returns:
            True if the operation succeeds.
        """
        cmd = self._enable_wbs_cmd if value else self._disable_wbs_cmd
        logging.debug('call "%s" on the DUT', cmd)
        if subprocess.call(cmd):
            logging.error('Failed to call: %s (%s)', cmd)
            return False
        return True

    def select_input_device(self, device_name):
        """Selects the audio input device.

        Args:
            device_name: The name of the Bluetooth peer device.

        Returns:
            True if the operation succeeds.
        """
        logging.debug('to select input device for device_name: %s', device_name)
        try:
            info = subprocess.check_output(self._info_cmd)
            logging.debug('info: %s', info)
        except Exception as e:
            logging.error('Failed to call: %s (%s)', self._info_cmd, e)
            return False

        flag_input_nodes = False
        audio_input_node = None
        for line in info.decode().splitlines():
            if 'Input Nodes' in line:
                flag_input_nodes = True
            elif 'Attached clients' in line:
                flag_input_nodes = False

            if flag_input_nodes:
                if device_name in line:
                    audio_input_node = line.split()[1]
                    logging.debug('%s', audio_input_node)
                    break

        if audio_input_node is None:
            logging.error('Failed to find audio input node: %s', device_name)
            return False

        select_input_cmd = (self._select_input_cmd + audio_input_node).split()
        if subprocess.call(select_input_cmd):
            logging.error('Failed to call: %s (%s)', select_input_cmd)
            return False

        logging.debug('call "%s" on the DUT', select_input_cmd)
        return True

    def set_player_playback_status(self, status):
        """Sets playback status for the registered media player.

        Args:
            status: Playback status in string.
        """
        try:
            get_cras_control_interface().SetPlayerPlaybackStatus(status)
        except Exception as e:
            logging.error('Failed to set player playback status: %s', e)
            return False

        return True

    def set_player_position(self, position):
        """Sets media position for the registered media player.

        Args:
            position: Position in micro seconds.
        """
        try:
            get_cras_control_interface().SetPlayerPosition(position)
        except Exception as e:
            logging.error('Failed to set player position: %s', e)
            return False

        return True

    def set_player_metadata(self, metadata):
        """Sets title, artist, and album for the registered media player.

        Args:
            metadata: Dictionary of media metadata.
        """
        try:
            get_cras_control_interface().SetPlayerMetadata(metadata)
        except Exception as e:
            logging.error('Failed to set player metadata: %s', e)
            return False

        return True

    def set_player_length(self, length):
        """Sets metadata length for the registered media player.

        Media length is a part of metadata information. However, without specify
        its type to int64. dbus-python will guess the variant type to be int32 by
        default. Separate it from the metadata function to help prepare the data
        differently.

        Args:
            length: DBUS dictionary that contains a variant of int64.
        """
        try:
            get_cras_control_interface().SetPlayerMetadata(length)
        except Exception as e:
            logging.error('Failed to set player length: %s', e)
            return False
        return True
