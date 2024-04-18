# Copyright 2023 Google LLC
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
"""Client class to access the Floss telephony interface."""
import logging

from floss.pandora.floss import observer_base
from floss.pandora.floss import utils


class BluetoothTelephonyCallbacks:
    """Callbacks for the telephony interface.

    Implement this to observe these callbacks when exporting callbacks via register_callback.
    """

    def on_telephony_use(self, addr, state):
        """Called when telephony is in use.

        Args:
            addr: The address of the telephony device.
            state: The boolean value indicating the telephony state.
        """
        pass


class FlossTelephonyClient:
    """Handles method calls and callbacks from the telephony interface."""

    TELEPHONY_SERVICE = 'org.chromium.bluetooth'
    TELEPHONY_INTERFACE = 'org.chromium.bluetooth.BluetoothTelephony'
    TELEPHONY_OBJECT_PATTERN = '/org/chromium/bluetooth/hci{}/telephony'
    TELEPHONY_CB_INTF = 'org.chromium.bluetooth.BluetoothTelephonyCallback'
    TELEPHONY_CB_OBJ_NAME = 'test_telephony_client'

    class ExportedTelephonyCallbacks(observer_base.ObserverBase):
        """
        <node>
            <interface name="org.chromium.bluetooth.BluetoothTelephonyCallback">
                <method name="OnTelephonyUse">
                    <arg type="s" name="add" direction="in" />
                    <arg type="b" name="state" direction="in" />
                </method>
            </interface>
        </node>
        """

        def __init__(self):
            """Constructs exported callbacks object."""
            observer_base.ObserverBase.__init__(self)

        def OnTelephonyUse(self, addr, state):
            """Handles telephony use callback.

            Args:
                addr: The address of the telephony device.
                state: The boolean value indicating the telephony state.
            """

            for observer in self.observers.values():
                observer.on_telephony_use(addr, state)

    def __init__(self, bus, hci):
        """Constructs the client.

        Args:
            bus: D-Bus bus over which we'll establish connections.
            hci: HCI adapter index. Get this value from `get_default_adapter` on FlossManagerClient.
        """
        self.bus = bus
        self.hci = hci
        self.objpath = self.TELEPHONY_OBJECT_PATTERN.format(hci)

        # We don't register callbacks by default.
        self.callbacks = None

    def __del__(self):
        """Destructor."""
        del self.callbacks

    @utils.glib_callback()
    def on_telephony_use(self, addr, state):
        """Handles telephony use callback.

        Args:
            addr: The address of the telephony device.
            state: The boolean value indicating the telephony state.
        """
        logging.debug('on_telephony_use: addr: %s, state: %s', addr, state)

    def _make_dbus_phone_number(self, number):
        """Makes struct for phone number D-Bus.

        Args:
            number : The phone number to use.

        Returns:
            Dictionary of phone number.
        """
        return utils.dbus_optional_value('s', number)

    @utils.glib_call(False)
    def has_proxy(self):
        """Checks whether telephony proxy can be acquired."""
        return bool(self.proxy())

    def proxy(self):
        """Gets proxy object to telephony interface for method calls."""
        return self.bus.get(self.TELEPHONY_SERVICE, self.objpath)[self.TELEPHONY_INTERFACE]

    @utils.glib_call(None)
    def register_telephony_callback(self):
        """Registers telephony callback for this client if one doesn't already exist.

        Returns:
            True on success, False on failure, None on DBus error.
        """
        if self.callbacks:
            return True

        # Create and publish callbacks
        self.callbacks = self.ExportedTelephonyCallbacks()
        self.callbacks.add_observer('telephony_client', self)
        objpath = utils.generate_dbus_cb_objpath(self.TELEPHONY_CB_OBJ_NAME, self.hci)
        self.bus.register_object(objpath, self.callbacks, None)

        # Register published callbacks with manager daemon
        return self.proxy().RegisterTelephonyCallback(objpath)

    @utils.glib_call(False)
    def set_network_available(self, network_available):
        """Sets network availability status.

        Args:
            network_available: A boolean value indicating whether the device is connected to the cellular network.

        Returns:
            True on success, False otherwise.
        """
        self.proxy().SetNetworkAvailable(network_available)
        return True

    @utils.glib_call(False)
    def set_roaming(self, roaming):
        """Sets roaming mode.

        Args:
            roaming: A boolean value indicating whether the device is in roaming mode.

        Returns:
            True on success, False otherwise.
        """
        self.proxy().SetRoaming(roaming)
        return True

    @utils.glib_call(None)
    def set_signal_strength(self, signal_strength):
        """Sets signal strength.

        Args:
            signal_strength: The signal strength value to be set, ranging from 0 to 5.

        Returns:
            True on success, False on failure, None on DBus error.
        """
        return self.proxy().SetSignalStrength(signal_strength)

    @utils.glib_call(None)
    def set_battery_level(self, battery_level):
        """Sets battery level.

        Args:
            battery_level: The battery level value to be set, ranging from 0 to 5.

        Returns:
            True on success, False on failure, None on DBus error.
        """
        return self.proxy().SetBatteryLevel(battery_level)

    @utils.glib_call(False)
    def set_phone_ops_enabled(self, enable):
        """Sets phone operations status.

        Args:
            enable: A boolean value indicating whether phone operations are enabled.

        Returns:
            True on success, False otherwise.
        """
        self.proxy().SetPhoneOpsEnabled(enable)
        return True

    @utils.glib_call(False)
    def set_mps_qualification_enabled(self, enable):
        """Sets MPS qualification status.

        Args:
            enable: A boolean value indicating whether MPS qualification is enabled.

        Returns:
            True on success, False otherwise.
        """
        self.proxy().SetMpsQualificationEnabled(enable)
        return True

    @utils.glib_call(None)
    def incoming_call(self, number):
        """Initiates an incoming call with the specified phone number.

        Args:
            number: The phone number of the incoming call.

        Returns:
            True on success, False on failure, None on DBus error.
        """

        return self.proxy().IncomingCall(number)

    @utils.glib_call(None)
    def dialing_call(self, number):
        """Initiates a dialing call with the specified phone number.

        Args:
            number: The phone number to dial.

        Returns:
            True on success, False on failure, None on DBus error.
        """
        return self.proxy().DialingCall(number)

    @utils.glib_call(None)
    def answer_call(self):
        """Answers an incoming or dialing call.

        Returns:
            True on success, False on failure, None on DBus error.
        """
        return self.proxy().AnswerCall()

    @utils.glib_call(None)
    def hangup_call(self):
        """Hangs up an active, incoming, or dialing call.

        Returns:
            True on success, False on failure, None on DBus error.
        """
        return self.proxy().HangupCall()

    @utils.glib_call(None)
    def set_last_call(self, number=None):
        """Sets last call with the specified phone number.

        Args:
            number: Optional phone number value to be set as the last call, Defaults to None if not provided.
        Returns:
            True on success, False on failure, None on DBus error.
        """
        number = self._make_dbus_phone_number(number)
        return self.proxy().SetLastCall(number)

    @utils.glib_call(None)
    def set_memory_call(self, number=None):
        """Sets memory call with the specified phone number.

        Args:
            number: Optional phone number value to be set as the last call, Defaults to None if not provided.

        Returns:
            True on success, False on failure, None on DBus error.
        """
        number = self._make_dbus_phone_number(number)
        return self.proxy().SetMemoryCall(number)

    @utils.glib_call(None)
    def release_held(self):
        """Releases all of the held calls.

        Returns:
            True on success, False on failure, None on DBus error.
        """
        return self.proxy().ReleaseHeld()

    @utils.glib_call(None)
    def release_active_accept_held(self):
        """Releases the active call and accepts a held call.

        Returns:
            True on success, False on failure, None on DBus error.
        """
        return self.proxy().ReleaseActiveAcceptHeld()

    @utils.glib_call(None)
    def hold_active_accept_held(self):
        """Holds the active call and accepts a held call.

        Returns:
            True on success, False on failure, None on DBus error.
        """
        return self.proxy().HoldActiveAcceptHeld()

    @utils.glib_call(None)
    def audio_connect(self, address):
        """Initiates an audio connection to the remote device.

        Args:
            address: The address of the remote device for audio connection.

        Returns:
            True on success, False on failure, None on DBus error.
        """
        return self.proxy().AudioConnect(address)

    @utils.glib_call(False)
    def audio_disconnect(self, address):
        """Disconnects the audio connection to the remote device.

        Args:
            address: The address of the remote device for audio disconnection.

        Returns:
            True on success, False otherwise.
        """
        self.proxy().AudioDisconnect(address)
        return True
