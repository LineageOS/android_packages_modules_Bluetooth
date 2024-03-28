# Copyright 2024 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Client class to access the Floss media interface."""

import logging

from floss.pandora.floss import observer_base
from floss.pandora.floss import utils
from gi.repository import GLib


class BluetoothMediaCallbacks:
    """Callbacks for the media interface.

    Implement this to observe these callbacks when exporting callbacks via register_callback.
    """

    def on_bluetooth_audio_device_added(self, device):
        """Called when a Bluetooth audio device is added.

        Args:
            device: The struct of BluetoothAudioDevice.
        """
        pass

    def on_bluetooth_audio_device_removed(self, addr):
        """Called when a Bluetooth audio device is removed.

        Args:
            addr: Address of device to be removed.
        """
        pass

    def on_absolute_volume_supported_changed(self, supported):
        """Called when the support of using absolute volume is changed.

        Args:
            supported: The boolean value indicates whether the supported volume has changed.
        """
        pass

    def on_absolute_volume_changed(self, volume):
        """Called when the absolute volume is changed.

        Args:
            volume: The value of volume.
        """
        pass

    def on_hfp_volume_changed(self, volume, addr):
        """Called when the HFP volume is changed.

        Args:
            volume: The value of volume.
            addr: Device address to get the HFP volume.
        """
        pass

    def on_hfp_audio_disconnected(self, addr):
        """Called when the HFP audio is disconnected.

        Args:
            addr: Device address to get the HFP state.
        """
        pass


class FlossMediaClient(BluetoothMediaCallbacks):
    """Handles method calls to and callbacks from the media interface."""

    MEDIA_SERVICE = 'org.chromium.bluetooth'
    MEDIA_INTERFACE = 'org.chromium.bluetooth.BluetoothMedia'
    MEDIA_OBJECT_PATTERN = '/org/chromium/bluetooth/hci{}/media'

    MEDIA_CB_INTF = 'org.chromium.bluetooth.BluetoothMediaCallback'
    MEDIA_CB_OBJ_NAME = 'test_media_client'

    class ExportedMediaCallbacks(observer_base.ObserverBase):
        """
        <node>
            <interface name="org.chromium.bluetooth.BluetoothMediaCallback">
                <method name="OnBluetoothAudioDeviceAdded">
                    <arg type="a{sv}" name="device" direction="in" />
                </method>
                <method name="OnBluetoothAudioDeviceRemoved">
                    <arg type="s" name="addr" direction="in" />
                </method>
                <method name="OnAbsoluteVolumeSupportedChanged">
                    <arg type="b" name="supported" direction="in" />
                </method>
                <method name="OnAbsoluteVolumeChanged">
                    <arg type="y" name="volume" direction="in" />
                </method>
                <method name="OnHfpVolumeChanged">
                    <arg type="y" name="volume" direction="in" />
                    <arg type="s" name="addr" direction="in" />
                </method>
                <method name="OnHfpAudioDisconnected">
                    <arg type="s" name="addr" direction="in" />
                </method>
            </interface>
        </node>
        """

        def __init__(self):
            """Constructs exported callbacks object."""
            observer_base.ObserverBase.__init__(self)

        def OnBluetoothAudioDeviceAdded(self, device):
            """Handles Bluetooth audio device added callback.

            Args:
                device: The struct of BluetoothAudioDevice.
            """
            for observer in self.observers.values():
                observer.on_bluetooth_audio_device_added(device)

        def OnBluetoothAudioDeviceRemoved(self, addr):
            """Handles Bluetooth audio device removed callback.

            Args:
                addr: Address of device to be removed.
            """
            for observer in self.observers.values():
                observer.on_bluetooth_audio_device_removed(addr)

        def OnAbsoluteVolumeSupportedChanged(self, supported):
            """Handles absolute volume supported changed callback.

            Args:
                supported: The boolean value indicates whether the supported volume has changed.
            """
            for observer in self.observers.values():
                observer.on_absolute_volume_supported_changed(supported)

        def OnAbsoluteVolumeChanged(self, volume):
            """Handles absolute volume changed callback.

            Args:
                volume: The value of volume.
            """
            for observer in self.observers.values():
                observer.on_absolute_volume_changed(volume)

        def OnHfpVolumeChanged(self, volume, addr):
            """Handles HFP volume changed callback.

            Args:
                volume: The value of volume.
                addr: Device address to get the HFP volume.
            """
            for observer in self.observers.values():
                observer.on_hfp_volume_changed(volume, addr)

        def OnHfpAudioDisconnected(self, addr):
            """Handles HFP audio disconnected callback.

            Args:
                addr: Device address to get the HFP state.
            """
            for observer in self.observers.values():
                observer.on_hfp_audio_disconnected(addr)

    def __init__(self, bus, hci):
        """Constructs the client.

        Args:
            bus: D-Bus bus over which we'll establish connections.
            hci: HCI adapter index. Get this value from 'get_default_adapter' on FlossManagerClient.
        """
        self.bus = bus
        self.hci = hci
        self.objpath = self.MEDIA_OBJECT_PATTERN.format(hci)
        self.devices = []

        # We don't register callbacks by default.
        self.callbacks = None

    def __del__(self):
        """Destructor."""
        del self.callbacks

    @utils.glib_callback()
    def on_bluetooth_audio_device_added(self, device):
        """Handles Bluetooth audio device added callback.

        Args:
            device: The struct of BluetoothAudioDevice.
        """
        logging.debug('on_bluetooth_audio_device_added: device: %s', device)
        if device['address'] in self.devices:
            logging.debug("Device already added")
        self.devices.append(device['address'])

    @utils.glib_callback()
    def on_bluetooth_audio_device_removed(self, addr):
        """Handles Bluetooth audio device removed callback.

        Args:
            addr: Address of device to be removed.
        """
        logging.debug('on_bluetooth_audio_device_removed: address: %s', addr)
        if addr in self.devices:
            self.devices.remove(addr)

    @utils.glib_callback()
    def on_absolute_volume_supported_changed(self, supported):
        """Handles absolute volume supported changed callback.

        Args:
            supported: The boolean value indicates whether the supported volume has changed.
        """
        logging.debug('on_absolute_volume_supported_changed: supported: %s', supported)

    @utils.glib_callback()
    def on_absolute_volume_changed(self, volume):
        """Handles absolute volume changed callback.

        Args:
            volume: The value of volume.
        """
        logging.debug('on_absolute_volume_changed: volume: %s', volume)

    @utils.glib_callback()
    def on_hfp_volume_changed(self, volume, addr):
        """Handles HFP volume changed callback.

        Args:
            volume: The value of volume.
            addr: Device address to get the HFP volume.
        """
        logging.debug('on_hfp_volume_changed: volume: %s, address: %s', volume, addr)

    @utils.glib_callback()
    def on_hfp_audio_disconnected(self, addr):
        """Handles HFP audio disconnected callback.

        Args:
            addr: Device address to get the HFP state.
        """
        logging.debug('on_hfp_audio_disconnected: address: %s', addr)

    def make_dbus_player_metadata(self, title, artist, album, length):
        """Makes struct for player metadata D-Bus.

        Args:
            title: The title of player metadata.
            artist: The artist of player metadata.
            album: The album of player metadata.
            length: The value of length metadata.

        Returns:
            Dictionary of player metadata.
        """
        return {
            'title': GLib.Variant('s', title),
            'artist': GLib.Variant('s', artist),
            'album': GLib.Variant('s', album),
            'length': GLib.Variant('x', length)
        }

    @utils.glib_call(False)
    def has_proxy(self):
        """Checks whether the media proxy is present."""
        return bool(self.proxy())

    def proxy(self):
        """Gets a proxy object to media interface for method calls."""
        return self.bus.get(self.MEDIA_SERVICE, self.objpath)[self.MEDIA_INTERFACE]

    @utils.glib_call(None)
    def register_callback(self):
        """Registers a media callback if it doesn't exist.

        Returns:
            True on success, False on failure, None on DBus error.
        """
        if self.callbacks:
            return True

        # Create and publish callbacks
        self.callbacks = self.ExportedMediaCallbacks()
        self.callbacks.add_observer('media_client', self)
        objpath = utils.generate_dbus_cb_objpath(self.MEDIA_CB_OBJ_NAME, self.hci)
        self.bus.register_object(objpath, self.callbacks, None)

        # Register published callbacks with media daemon
        return self.proxy().RegisterCallback(objpath)

    @utils.glib_call(None)
    def initialize(self):
        """Initializes the media (both A2DP and AVRCP) stack.

        Returns:
            True on success, False on failure, None on DBus error.
        """
        return self.proxy().Initialize()

    @utils.glib_call(None)
    def cleanup(self):
        """Cleans up media stack.

        Returns:
            True on success, False on failure, None on DBus error.
        """
        return self.proxy().Cleanup()

    @utils.glib_call(False)
    def connect(self, address):
        """Connects to a Bluetooth media device with the specified address.

        Args:
            address: Device address to connect.

        Returns:
            True on success, False otherwise.
        """
        self.proxy().Connect(address)
        return True

    @utils.glib_call(False)
    def disconnect(self, address):
        """Disconnects the specified Bluetooth media device.

        Args:
            address: Device address to disconnect.

        Returns:
            True on success, False otherwise.
        """
        self.proxy().Disconnect(address)
        return True

    @utils.glib_call(False)
    def set_active_device(self, address):
        """Sets the device as the active A2DP device.

        Args:
            address: Device address to set as an active A2DP device.

        Returns:
            True on success, False otherwise.
        """
        self.proxy().SetActiveDevice(address)
        return True

    @utils.glib_call(False)
    def set_hfp_active_device(self, address):
        """Sets the device as the active HFP device.

        Args:
            address: Device address to set as an active HFP device.

        Returns:
            True on success, False otherwise.
        """
        self.proxy().SetHfpActiveDevice(address)
        return True

    @utils.glib_call(None)
    def set_audio_config(self, sample_rate, bits_per_sample, channel_mode):
        """Sets audio configuration.

        Args:
            sample_rate: Value of sample rate.
            bits_per_sample: Number of bits per sample.
            channel_mode: Value of channel mode.

        Returns:
            True on success, False on failure, None on DBus error.
        """
        return self.proxy().SetAudioConfig(sample_rate, bits_per_sample, channel_mode)

    @utils.glib_call(False)
    def set_volume(self, volume):
        """Sets the A2DP/AVRCP volume.

        Args:
            volume: The value of volume to set it.

        Returns:
            True on success, False otherwise.
        """
        self.proxy().SetVolume(volume)
        return True

    @utils.glib_call(False)
    def set_hfp_volume(self, volume, address):
        """Sets the HFP speaker volume.

        Args:
            volume: The value of volume.
            address: Device address to set the HFP volume.

        Returns:
            True on success, False otherwise.
        """
        self.proxy().SetHfpVolume(volume, address)
        return True

    @utils.glib_call(False)
    def start_audio_request(self):
        """Starts audio request.

        Returns:
            True on success, False otherwise.
        """
        self.proxy().StartAudioRequest()
        return True

    @utils.glib_call(None)
    def get_a2dp_audio_started(self, address):
        """Gets A2DP audio started.

        Args:
            address: Device address to get the A2DP state.

        Returns:
            Non-zero value iff A2DP audio has started, None on D-Bus error.
        """
        return self.proxy().GetA2dpAudioStarted(address)

    @utils.glib_call(False)
    def stop_audio_request(self):
        """Stops audio request.

        Returns:
            True on success, False otherwise.
        """
        self.proxy().StopAudioRequest()
        return True

    @utils.glib_call(False)
    def start_sco_call(self, address, sco_offload, force_cvsd):
        """Starts the SCO call.

        Args:
            address: Device address to make SCO call.
            sco_offload: Whether SCO offload is enabled.
            force_cvsd: True to force the stack to use CVSD even if mSBC is supported.

        Returns:
            True on success, False otherwise.
        """
        self.proxy().StartScoCall(address, sco_offload, force_cvsd)
        return True

    @utils.glib_call(None)
    def get_hfp_audio_started(self, address):
        """Gets HFP audio started.

        Args:
            address: Device address to get the HFP state.

        Returns:
            The negotiated codec (CVSD=1, mSBC=2) to use if HFP audio has started; 0 if HFP audio hasn't started,
            None on DBus error.
        """
        return self.proxy().GetHfpAudioStarted(address)

    @utils.glib_call(False)
    def stop_sco_call(self, address):
        """Stops the SCO call.

        Args:
            address: Device address to stop SCO call.

        Returns:
            True on success, False otherwise.
        """
        self.proxy().StopScoCall(address)
        return True

    @utils.glib_call(None)
    def get_presentation_position(self):
        """Gets presentation position.

        Returns:
            PresentationPosition struct on success, None otherwise.
        """
        return self.proxy().GetPresentationPosition()

    @utils.glib_call(False)
    def set_player_position(self, position_us):
        """Sets player position.

        Args:
            position_us: The player position in microsecond.

        Returns:
            True on success, False otherwise.
        """
        self.proxy().SetPlayerPosition(position_us)
        return True

    @utils.glib_call(False)
    def set_player_playback_status(self, status):
        """Sets player playback status.

        Args:
            status: Playback status such as 'playing', 'paused', 'stopped' as string.

        Returns:
            True on success, False otherwise.
        """
        self.proxy().SetPlayerPlaybackStatus(status)
        return True

    @utils.glib_call(False)
    def set_player_metadata(self, metadata):
        """Sets player metadata.

        Args:
            metadata: The media metadata to set it.

        Returns:
            True on success, False otherwise.
        """
        self.proxy().SetPlayerMetadata(metadata)
        return True

    def register_callback_observer(self, name, observer):
        """Adds an observer for all callbacks.

        Args:
            name: Name of the observer.
            observer: Observer that implements all callback classes.
        """
        if isinstance(observer, BluetoothMediaCallbacks):
            self.callbacks.add_observer(name, observer)

    def unregister_callback_observer(self, name, observer):
        """Removes an observer for all callbacks.

        Args:
            name: Name of the observer.
            observer: Observer that implements all callback classes.
        """
        if isinstance(observer, BluetoothMediaCallbacks):
            self.callbacks.remove_observer(name, observer)
