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
"""Client class to access the Floss socket manager interface."""

import logging

from floss.pandora.floss import floss_enums
from floss.pandora.floss import observer_base
from floss.pandora.floss import utils
from gi.repository import GLib


class SocketManagerCallbacks:
    """Callbacks for the socket manager interface.

    Implement this to observe these callbacks when exporting callbacks via register_callback.
    """

    def on_incoming_socket_ready(self, socket, status):
        """Called when incoming socket is ready.

        Args:
            socket: BluetoothServerSocket.
            status: BtStatus.
        """
        pass

    def on_incoming_socket_closed(self, listener_id, reason):
        """Called when incoming socket is closed.

        Args:
            listener_id: SocketId.
            reason: BtStatus.
        """
        pass

    def on_handle_incoming_connection(
            self,
            listener_id,
            connection,
            *,  # Keyword only after bare asterisk
            dbus_unix_fd_list=None):
        """Called when incoming connection is handled.

        Args:
            listener_id: SocketId.
            connection: BluetoothSocket.
            dbus_unix_fd_list: List of fds. Use the handle inside connection to look up the target fd. If it would be
                               kept, dup it with os.dup first. CrOS specific pydbus feature.
        """
        pass

    def on_outgoing_connection_result(
            self,
            connecting_id,
            result,
            socket,
            *,  # Keyword only after bare asterisk
            dbus_unix_fd_list=None):
        """Called when outgoing connection is handled.

        Args:
            connecting_id: SocketId.
            result: BtStatus.
            socket: BluetoothSocket.
            dbus_unix_fd_list: List of fds. Use the handle inside socket to look up the target fd. If it would be kept,
                               dup it with os.dup first. CrOS specific pydbus feature.
        """
        pass


class FlossSocketManagerClient(SocketManagerCallbacks):
    """Handles method calls and callbacks from the socket manager interface."""

    ADAPTER_SERVICE = 'org.chromium.bluetooth'
    SOCKET_MANAGER_INTERFACE = 'org.chromium.bluetooth.SocketManager'
    ADAPTER_OBJECT_PATTERN = '/org/chromium/bluetooth/hci{}/adapter'
    SOCKET_CB_OBJ_NAME = 'test_socket_client'
    CB_EXPORTED_INTF = 'org.chromium.bluetooth.SocketManagerCallback'
    FLOSS_RESPONSE_LATENCY_SECS = 3

    class ExportedSocketManagerCallbacks(observer_base.ObserverBase):
        """
        <node>
            <interface name="org.chromium.bluetooth.SocketManagerCallback">
                <method name="OnIncomingSocketReady">
                    <arg type="a{sv}" name="socket" direction="in" />
                    <arg type="u" name="status" direction="in" />
                </method>
                <method name="OnIncomingSocketClosed">
                    <arg type="t" name="listener_id" direction="in" />
                    <arg type="u" name="reason" direction="in" />
                </method>
                <method name="OnHandleIncomingConnection">
                    <arg type="t" name="listener_id" direction="in" />
                    <arg type="a{sv}" name="connection" direction="in" />
                </method>
                <method name="OnOutgoingConnectionResult">
                    <arg type="t" name="connecting_id" direction="in" />
                    <arg type="u" name="result" direction="in" />
                    <arg type="a{sv}" name="socket" direction="in" />
                </method>
            </interface>
        </node>
        """

        def __init__(self):
            """Constructs exported callbacks object."""
            observer_base.ObserverBase.__init__(self)

        def OnIncomingSocketReady(self, socket, status):
            """Handles incoming socket ready callback."""
            for observer in self.observers.values():
                observer.on_incoming_socket_ready(socket, status)

        def OnIncomingSocketClosed(self, listener_id, reason):
            """Handles incoming socket closed callback."""
            for observer in self.observers.values():
                observer.on_incoming_socket_closed(listener_id, reason)

        def OnHandleIncomingConnection(self, listener_id, connection, *, dbus_unix_fd_list=None):
            """Handles incoming socket connection callback."""
            for observer in self.observers.values():
                observer.on_handle_incoming_connection(listener_id, connection, dbus_unix_fd_list=dbus_unix_fd_list)

        def OnOutgoingConnectionResult(self, connecting_id, result, socket, *, dbus_unix_fd_list=None):
            """Handles outgoing socket connection callback."""
            for observer in self.observers.values():
                observer.on_outgoing_connection_result(connecting_id,
                                                       result,
                                                       socket,
                                                       dbus_unix_fd_list=dbus_unix_fd_list)

    def __init__(self, bus, hci):
        self.bus = bus
        self.hci = hci
        self.callbacks = None
        self.callback_id = None
        self.objpath = self.ADAPTER_OBJECT_PATTERN.format(hci)

    def __del__(self):
        """Destructor."""
        del self.callbacks

    @utils.glib_callback()
    def on_incoming_socket_ready(self, socket, status):
        """Handles incoming socket ready callback."""
        logging.debug('on_incoming_socket_ready: socket: %s, status: %s', socket, status)

    @utils.glib_callback()
    def on_incoming_socket_closed(self, listener_id, reason):
        """Handles incoming socket closed callback."""
        logging.debug('on_incoming_socket_closed: listener_id: %s, reason: %s', listener_id, reason)

    @utils.glib_callback()
    def on_handle_incoming_connection(self, listener_id, connection, *, dbus_unix_fd_list=None):
        """Handles incoming socket connection callback."""
        logging.debug('on_handle_incoming_connection: listener_id: %s, connection: %s', listener_id, connection)

    @utils.glib_callback()
    def on_outgoing_connection_result(self, connecting_id, result, socket, *, dbus_unix_fd_list=None):
        """Handles outgoing socket connection callback."""
        logging.debug('on_outgoing_connection_result: connecting_id: %s, result: %s, socket: %s', connecting_id, result,
                      socket)

    def make_dbus_device(self, address, name):
        return {'address': GLib.Variant('s', address), 'name': GLib.Variant('s', name)}

    def _make_dbus_timeout(self, timeout):
        return utils.dbus_optional_value('u', timeout)

    @utils.glib_call(False)
    def has_proxy(self):
        """Checks whether manager proxy can be acquired."""
        return bool(self.proxy())

    def proxy(self):
        """Gets proxy object to socket manager interface for method calls."""
        return self.bus.get(self.ADAPTER_SERVICE, self.objpath)[self.SOCKET_MANAGER_INTERFACE]

    @utils.glib_call(False)
    def register_callbacks(self):
        """Registers socket manager callbacks if one doesn't already exist.

        Returns:
            True on success, False otherwise.
        """
        # Callbacks already registered
        if self.callbacks:
            return True

        # Create and publish callbacks
        self.callbacks = self.ExportedSocketManagerCallbacks()
        self.callbacks.add_observer('socket_client', self)
        objpath = utils.generate_dbus_cb_objpath(self.SOCKET_CB_OBJ_NAME, self.hci)
        self.bus.register_object(objpath, self.callbacks, None)

        # Register published callbacks with adapter daemon
        self.callback_id = self.proxy().RegisterCallback(objpath)
        return True

    def register_callback_observer(self, name, observer):
        """Add an observer for all callbacks.

        Args:
            name: Name of the observer.
            observer: Observer that implements all callback classes.
        """
        if isinstance(observer, SocketManagerCallbacks):
            self.callbacks.add_observer(name, observer)

    def unregister_callback_observer(self, name, observer):
        """Remove an observer for all callbacks.

        Args:
            name: Name of the observer.
            observer: Observer that implements all callback classes.
        """
        if isinstance(observer, SocketManagerCallbacks):
            self.callbacks.remove_observer(name, observer)

    @utils.glib_call(None)
    def listen_using_l2cap_channel(self):
        """Listens using L2CAP channel.

        Returns:
            SocketResult as {status:BtStatus, id:int} on success, None otherwise.
        """
        return self.proxy().ListenUsingL2capChannel(self.callback_id)

    @utils.glib_call(None)
    def listen_using_insecure_l2cap_channel(self):
        """Listens using insecure L2CAP channel.

        Returns:
            SocketResult as {status:BtStatus, id:int} on success, None otherwise.
        """

        return self.proxy().ListenUsingInsecureL2capChannel(self.callback_id)

    @utils.glib_call(None)
    def listen_using_insecure_rfcomm_with_service_record(self, name, uuid):
        """Listens using insecure RFCOMM channel with service record.

        Args:
            name: Service name.
            uuid: 128-bit service UUID.

        Returns:
            SocketResult as {status:BtStatus, id:int} on success, None otherwise.
        """
        return self.proxy().ListenUsingInsecureRfcommWithServiceRecord(self.callback_id, name, uuid)

    @utils.glib_call(None)
    def listen_using_rfcomm_with_service_record(self, name, uuid):
        """Listens using RFCOMM channel with service record.

        Args:
            name: Service name.
            uuid: 128-bit service UUID.

        Returns:
            SocketResult as {status:BtStatus, id:int} on success, None otherwise.
        """
        return self.proxy().ListenUsingRfcommWithServiceRecord(self.callback_id, name, uuid)

    @utils.glib_call(None)
    def create_insecure_l2cap_channel(self, device, psm):
        """Creates insecure L2CAP channel.

        Args:
            device: D-bus device.
            psm: Protocol Service Multiplexor.

        Returns:
            SocketResult as {status:BtStatus, id:int} on success, None otherwise.
        """
        return self.proxy().CreateInsecureL2capChannel(self.callback_id, device, psm)

    @utils.glib_call(None)
    def create_l2cap_channel(self, device, psm):
        """Creates L2CAP channel.

        Args:
            device: D-bus device.
            psm: Protocol Service Multiplexor.

        Returns:
            SocketResult as {status:BtStatus, id:int} on success, None otherwise.
        """
        return self.proxy().CreateL2capChannel(self.callback_id, device, psm)

    @utils.glib_call(None)
    def create_insecure_rfcomm_socket_to_service_record(self, device, uuid):
        """Creates insecure RFCOMM socket to service record.

        Args:
            device: New D-bus device.
            uuid: 128-bit service UUID.

        Returns:
            SocketResult as {status:BtStatus, id:int} on success, None otherwise.
        """
        return self.proxy().CreateInsecureRfcommSocketToServiceRecord(self.callback_id, device, uuid)

    @utils.glib_call(None)
    def create_rfcomm_socket_to_service_record(self, device, uuid):
        """Creates RFCOMM socket to service record.

        Args:
            device: D-bus device.
            uuid: 128-bit service UUID.

        Returns:
            SocketResult as {status:BtStatus, id:int} on success, None otherwise.
        """
        return self.proxy().CreateRfcommSocketToServiceRecord(self.callback_id, device, uuid)

    @utils.glib_call(None)
    def accept(self, socket_id, timeout_ms=None):
        """Accepts socket connection.

        Args:
            socket_id: New address of the adapter.
            timeout_ms: Timeout in ms.

        Returns:
            BtStatus as int on success, None otherwise.
        """
        timeout_ms = self._make_dbus_timeout(timeout_ms)
        return self.proxy().Accept(self.callback_id, socket_id, timeout_ms)

    @utils.glib_call(False)
    def close(self, socket_id):
        """Closes socket connection.

        Args:
            socket_id: Socket id to be closed.

        Returns:
            True on success, False otherwise.
        """
        status = self.proxy().Close(self.callback_id, socket_id)
        if floss_enums.BtStatus(status) != floss_enums.BtStatus.SUCCESS:
            logging.error('Failed to close socket with id: %s, status = %s', socket_id, status)
        return floss_enums.BtStatus(status) == floss_enums.BtStatus.SUCCESS
