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
"""Client Class to access the Floss GATT server interface."""

import logging

from floss.pandora.floss import floss_enums
from floss.pandora.floss import observer_base
from floss.pandora.floss import utils


class GattServerCallbacks:
    """Callbacks for the GATT server interface.

    Implement this to observe these callbacks when exporting callbacks via register_server.
    """

    def on_server_registered(self, status, server_id):
        """Called when GATT server registered.

        Args:
            status: floss_enums.GattStatus.
            server_id: Bluetooth GATT server id.
        """
        pass

    def on_server_connection_state(self, server_id, connected, addr):
        """Called when GATT server connection state changed.

        Args:
            server_id: Bluetooth GATT server id.
            connected: A boolean value that indicates whether the GATT server is connected.
            addr: Remote device MAC address.
        """
        pass

    def on_service_added(self, status, service):
        """Called when service added.

        Args:
            status: floss_enums.GattStatus.
            service: BluetoothGattService.
        """
        pass

    def on_service_removed(self, status, handle):
        """Called when service removed.

        Args:
            status: floss_enums.GattStatus.
            handle: Service record handle.
        """
        pass

    def on_characteristic_read_request(self, addr, trans_id, offset, is_long, handle):
        """Called when there is a request to read a characteristic.

        Args:
            addr: Remote device MAC address.
            trans_id: Transaction id.
            offset: Represents the offset from which the attribute value should be read.
            is_long: A boolean value representing whether the characteristic size is longer than what we can put in the
                     ATT PDU.
            handle: The characteristic handle.
        """
        pass

    def on_descriptor_read_request(self, addr, trans_id, offset, is_long, handle):
        """Called when there is a request to read a descriptor.

        Args:
            addr: Remote device MAC address.
            trans_id: Transaction id.
            offset: Represents the offset from which the descriptor value should be read.
            is_long: A boolean value representing whether the descriptor size is longer than what we can put in the
                     ATT PDU.
            handle: The descriptor handle.
        """
        pass

    def on_characteristic_write_request(self, addr, trans_id, offset, len, is_prep, need_rsp, handle, value):
        """Called when there is a request to write a characteristic.

        Args:
            addr: Remote device MAC address.
            trans_id: Transaction id.
            offset: Represents the offset at which the attribute value should be written.
            len: The length of the attribute value that should be written.
            is_prep: A boolean value representing whether it's a "prepare write" command.
            need_rsp: A boolean value representing whether it's a "write no response" command.
            handle: The characteristic handle.
            value: The value that should be written to the attribute.
        """
        pass

    def on_descriptor_write_request(self, addr, trans_id, offset, len, is_prep, need_rsp, handle, value):
        """Called when there is a request to write a descriptor.

        Args:
            addr: Remote device MAC address.
            trans_id: Transaction id.
            offset: The offset value at which the value should be written.
            len: The length of the value that should be written.
            is_prep: A boolean value representing whether it's a "prepare write" command.
            need_rsp: A boolean value representing whether it's a "write no response" command.
            handle: The descriptor handle.
            value: The value that should be written to the descriptor.
        """
        pass

    def on_execute_write(self, addr, trans_id, exec_write):
        """Called when execute write.

        Args:
            addr: Remote device MAC address.
            trans_id: Transaction id.
            exec_write: A boolean value that indicates whether the write operation should be executed or canceled.
        """
        pass

    def on_notification_sent(self, addr, status):
        """Called when notification sent.

        Args:
            addr: Remote device MAC address.
            status: floss_enums.GattStatus.
        """
        pass

    def on_mtu_changed(self, addr, mtu):
        """Called when the MTU changed.

        Args:
            addr: Remote device MAC address.
            mtu: Maximum transmission unit.
        """
        pass

    def on_phy_update(self, addr, tx_phy, rx_phy, status):
        """Called when physical update.

        Args:
            addr: Remote device MAC address.
            tx_phy: The new TX PHY for the connection.
            rx_phy: The new RX PHY for the connection.
            status: floss_enums.GattStatus.
        """
        pass

    def on_phy_read(self, addr, tx_phy, rx_phy, status):
        """Called when physical read.

        Args:
            addr: Remote device MAC address.
            tx_phy: The current transmit PHY for the connection.
            rx_phy: The current receive PHY for the connection.
            status: floss_enums.GattStatus.
        """
        pass

    def on_connection_updated(self, addr, interval, latency, timeout, status):
        """Called when connection updated.

        Args:
            addr: Remote device MAC address.
            interval: Connection interval value.
            latency: The number of consecutive connection events during which the device doesn't have to be listening.
            timeout: Supervision timeout for this connection in milliseconds.
            status: floss_enums.GattStatus.
        """
        pass

    def on_subrate_change(self, addr, subrate_factor, latency, cont_num, timeout, status):
        """Called when subrate changed.

        Args:
            addr: Remote device MAC address.
            subrate_factor: Subrate factor value.
            latency: The number of consecutive connection events during which the device doesn't have to be listening.
            cont_num: Continuation number.
            timeout: Supervision timeout for this connection in milliseconds.
            status: floss_enums.GattStatus.
        """
        pass


class FlossGattServer(GattServerCallbacks):
    """Handles method calls and callbacks from the GATT server interface."""

    ADAPTER_SERVICE = 'org.chromium.bluetooth'
    GATT_SERVER_INTERFACE = 'org.chromium.bluetooth.BluetoothGatt'
    GATT_OBJECT_PATTERN = '/org/chromium/bluetooth/hci{}/gatt'
    GATT_CB_OBJ_NAME = 'test_gatt_server'
    CB_EXPORTED_INTF = 'org.chromium.bluetooth.BluetoothGattServerCallback'

    class ExportedGattServerCallbacks(observer_base.ObserverBase):
        """
        <node>
            <interface name="org.chromium.bluetooth.BluetoothGattServerCallback">
                <method name="OnServerRegistered">
                    <arg type="u" name="status" direction="in" />
                    <arg type="i" name="server_id" direction="in" />
                </method>
                <method name="OnServerConnectionState">
                    <arg type="i" name="server_id" direction="in" />
                    <arg type="b" name="connected" direction="in" />
                    <arg type="s" name="addr" direction="in" />
                </method>
                <method name="OnServiceAdded">
                    <arg type="u" name="status" direction="in" />
                    <arg type="a{sv}" name="service" direction="in" />
                </method>
                <method name="OnServiceRemoved">
                    <arg type="u" name="status" direction="in" />
                    <arg type="i" name="handle" direction="in" />
                </method>
                <method name="OnCharacteristicReadRequest">
                    <arg type="s" name="addr" direction="in" />
                    <arg type="i" name="trans_id" direction="in" />
                    <arg type="i" name="offset" direction="in" />
                    <arg type="b" name="is_long" direction="in" />
                    <arg type="i" name="handle" direction="in" />
                </method>
                <method name="OnDescriptorReadRequest">
                    <arg type="s" name="addr" direction="in" />
                    <arg type="i" name="trans_id" direction="in" />
                    <arg type="i" name="offset" direction="in" />
                    <arg type="b" name="is_long" direction="in" />
                    <arg type="i" name="handle" direction="in" />
                </method>
                <method name="OnCharacteristicWriteRequest">
                    <arg type="s" name="addr" direction="in" />
                    <arg type="i" name="trans_id" direction="in" />
                    <arg type="i" name="offset" direction="in" />
                    <arg type="i" name="len" direction="in" />
                    <arg type="b" name="is_prep" direction="in" />
                    <arg type="b" name="need_rsp" direction="in" />
                    <arg type="i" name="handle" direction="in" />
                    <arg type="ay" name="value" direction="in" />
                </method>
                <method name="OnDescriptorWriteRequest">
                    <arg type="s" name="addr" direction="in" />
                    <arg type="i" name="trans_id" direction="in" />
                    <arg type="i" name="offset" direction="in" />
                    <arg type="i" name="len" direction="in" />
                    <arg type="b" name="is_prep" direction="in" />
                    <arg type="b" name="need_rsp" direction="in" />
                    <arg type="i" name="handle" direction="in" />
                    <arg type="ay" name="value" direction="in" />
                </method>
                <method name="OnExecuteWrite">
                    <arg type="s" name="addr" direction="in" />
                    <arg type="i" name="trans_id" direction="in" />
                    <arg type="b" name="exec_write" direction="in" />
                </method>
                <method name="OnNotificationSent">
                    <arg type="s" name="addr" direction="in" />
                    <arg type="u" name="status" direction="in" />
                </method>
                <method name="OnMtuChanged">
                    <arg type="s" name="addr" direction="in" />
                    <arg type="i" name="mtu" direction="in" />
                </method>
                <method name="OnPhyUpdate">
                    <arg type="s" name="addr" direction="in" />
                    <arg type="u" name="tx_phy" direction="in" />
                    <arg type="u" name="rx_phy" direction="in" />
                    <arg type="u" name="status" direction="in" />
                </method>
                <method name="OnPhyRead">
                    <arg type="s" name="addr" direction="in" />
                    <arg type="u" name="tx_phy" direction="in" />
                    <arg type="u" name="rx_phy" direction="in" />
                    <arg type="u" name="status" direction="in" />
                </method>
                <method name="OnConnectionUpdated">
                    <arg type="s" name="addr" direction="in" />
                    <arg type="i" name="interval" direction="in" />
                    <arg type="i" name="latency" direction="in" />
                    <arg type="i" name="timeout" direction="in" />
                    <arg type="u" name="status" direction="in" />
                </method>
                <method name="OnSubrateChange">
                    <arg type="s" name="addr" direction="in" />
                    <arg type="i" name="subrate_factor" direction="in" />
                    <arg type="i" name="latency" direction="in" />
                    <arg type="i" name="cont_num" direction="in" />
                    <arg type="i" name="timeout" direction="in" />
                    <arg type="u" name="status" direction="in" />
                </method>
            </interface>
        </node>
        """

        def __init__(self):
            """Constructs exported callbacks object."""
            observer_base.ObserverBase.__init__(self)

        def OnServerRegistered(self, status, server_id):
            """Handles server registration callback.

            Args:
                status: floss_enums.GattStatus.
                server_id: Bluetooth GATT server id.
            """
            for observer in self.observers.values():
                observer.on_server_registered(status, server_id)

        def OnServerConnectionState(self, server_id, connected, addr):
            """Handles server connection state callback.

            Args:
                server_id: Bluetooth GATT server id.
                connected: A boolean value that indicates whether the GATT server is connected.
                addr: Remote device MAC address.
            """
            for observer in self.observers.values():
                observer.on_server_connection_state(server_id, connected, addr)

        def OnServiceAdded(self, status, service):
            """Handles service added callback.

            Args:
                status: floss_enums.GattStatus.
                service: BluetoothGattService.
            """
            for observer in self.observers.values():
                observer.on_service_added(status, service)

        def OnServiceRemoved(self, status, handle):
            """Handles service removed callback.

            Args:
                status: floss_enums.GattStatus.
                handle: Service record handle.
            """
            for observer in self.observers.values():
                observer.on_service_removed(status, handle)

        def OnCharacteristicReadRequest(self, addr, trans_id, offset, is_long, handle):
            """Handles characteristic read request callback.

            Args:
                addr: Remote device MAC address.
                trans_id: Transaction id.
                offset: Represents the offset from which the attribute value should be read.
                is_long: A boolean value representing whether the characteristic size is longer than what we can put in
                         the ATT PDU.
                handle: The characteristic handle.
            """
            for observer in self.observers.values():
                observer.on_characteristic_read_request(addr, trans_id, offset, is_long, handle)

        def OnDescriptorReadRequest(self, addr, trans_id, offset, is_long, handle):
            """Handles descriptor read request callback.

            Args:
                addr: Remote device MAC address.
                trans_id: Transaction id.
                offset: Represents the offset from which the descriptor value should be read.
                is_long: A boolean value representing whether the descriptor size is longer than what we can put in the
                         ATT PDU.
                handle: The descriptor handle.
            """
            for observer in self.observers.values():
                observer.on_descriptor_read_request(addr, trans_id, offset, is_long, handle)

        def OnCharacteristicWrite(self, addr, trans_id, offset, len, is_prep, need_rsp, handle, value):
            """Handles characteristic write request callback.

            Args:
                addr: Remote device MAC address.
                trans_id: Transaction id.
                offset: Represents the offset at which the attribute value should be written.
                len: The length of the attribute value that should be written.
                is_prep: A boolean value representing whether it's a "prepare write" command.
                need_rsp: A boolean value representing whether it's a "write no response" command.
                handle: The characteristic handle.
                value: The value that should be written to the attribute.
            """
            for observer in self.observers.values():
                observer.on_characteristic_write_request(addr, trans_id, offset, len, is_prep, need_rsp, handle, value)

        def OnDescriptorWriteRequest(self, addr, trans_id, offset, len, is_prep, need_rsp, handle, value):
            """Handles descriptor write request callback.

            Args:
                addr: Remote device MAC address.
                trans_id: Transaction id.
                offset: The offset value at which the value should be written.
                len: The length of the value that should be written.
                is_prep: A boolean value representing whether it's a "prepare write" command.
                need_rsp: A boolean value representing whether it's a "write no response" command.
                handle: The descriptor handle.
                value: The value that should be written to the descriptor.
            """
            for observer in self.observers.values():
                observer.on_descriptor_write_request(addr, trans_id, offset, len, is_prep, need_rsp, handle, value)

        def OnExecuteWrite(self, addr, trans_id, exec_write):
            """Handles execute write callback.

            Args:
                addr: Remote device MAC address.
                trans_id: Transaction id.
                exec_write: A boolean value that indicates whether the write operation should be executed or canceled.
            """
            for observer in self.observers.values():
                observer.on_execute_write(addr, trans_id, exec_write)

        def OnNotificationSent(self, addr, status):
            """Handles notification sent callback.

            Args:
                addr: Remote device MAC address.
                status: floss_enums.GattStatus.
            """
            for observer in self.observers.values():
                observer.on_notification_sent(addr, status)

        def OnMtuChanged(self, addr, mtu):
            """Handles MTU changed callback.

            Args:
                addr: Remote device MAC address.
                mtu: Maximum transmission unit.
            """
            for observer in self.observers.values():
                observer.on_mtu_changed(addr, mtu)

        def OnPhyUpdate(self, addr, tx_phy, rx_phy, status):
            """Handles physical update callback.

            Args:
                addr: Remote device MAC address.
                tx_phy: The new TX PHY for the connection.
                rx_phy: The new RX PHY for the connection.
                status: floss_enums.GattStatus.
            """
            for observer in self.observers.values():
                observer.on_phy_update(addr, tx_phy, rx_phy, status)

        def OnPhyRead(self, addr, tx_phy, rx_phy, status):
            """Handles physical read callback.

            Args:
                addr: Remote device MAC address.
                tx_phy: The current transmit PHY for the connection.
                rx_phy: The current receive PHY for the connection.
                status: floss_enums.GattStatus.
            """
            for observer in self.observers.values():
                observer.on_phy_read(addr, tx_phy, rx_phy, status)

        def OnConnectionUpdated(self, addr, interval, latency, timeout, status):
            """Handles connection updated callback.

            Args:
                addr: Remote device MAC address.
                interval: Connection interval value.
                latency: The number of consecutive connection events during which the device doesn't have to be
                         listening.
                timeout: Supervision timeout for this connection in milliseconds.
                status: floss_enums.GattStatus.
            """
            for observer in self.observers.values():
                observer.on_connection_updated(addr, interval, latency, timeout, status)

        def on_subrate_change(self, addr, subrate_factor, latency, cont_num, timeout, status):
            """Handles subrate changed callback.

            Args:
                addr: Remote device MAC address.
                subrate_factor: Subrate factor value.
                latency: The number of consecutive connection events during which the device doesn't have to be
                         listening.
                cont_num: Continuation number.
                timeout: Supervision timeout for this connection in milliseconds.
                status: floss_enums.GattStatus.
            """
            for observer in self.observers.values():
                observer.on_subrate_change(addr, subrate_factor, latency, cont_num, timeout, status)

    def __init__(self, bus, hci):
        """Constructs the client.

        Args:
            bus: D-Bus bus over which we'll establish connections.
            hci: HCI adapter index. Get this value from 'get_default_adapter' on FlossManagerClient.
        """
        self.bus = bus
        self.hci = hci
        self.objpath = self.GATT_OBJECT_PATTERN.format(hci)
        self.cb_dbus_objpath = utils.generate_dbus_cb_objpath(self.GATT_CB_OBJ_NAME, self.hci)

        # Create and publish callbacks
        self.callbacks = self.ExportedGattServerCallbacks()
        self.callbacks.add_observer('gatt_testing_server', self)
        self.bus.register_object(self.cb_dbus_objpath, self.callbacks, None)

    def __del__(self):
        """Destructor."""
        del self.callbacks

    @utils.glib_callback()
    def on_server_registered(self, status, server_id):
        """Handles server registration callback.

        Args:
            status: floss_enums.GattStatus.
            server_id: Bluetooth GATT server id.
        """
        logging.debug('on_server_registered: status: %s, server_id: %s', status, server_id)

        if status != floss_enums.GattStatus.SUCCESS:
            logging.error('Failed to register server with id: %s, status = %s', server_id, status)
            return

    @utils.glib_callback()
    def on_server_connection_state(self, server_id, connected, addr):
        """Handles GATT server connection state callback.

        Args:
            server_id: Bluetooth GATT server id.
            connected: A boolean value that indicates whether the GATT server is connected.
            addr: Remote device MAC address.
        """
        logging.debug('on_server_connection_state: server_id: %s, connection_state: %s, device address: %s', server_id,
                      connected, addr)

    @utils.glib_callback()
    def on_service_added(self, status, service):
        """Handles service added callback.

        Args:
            status: floss_enums.GattStatus.
            service: BluetoothGattService.
        """
        logging.debug('on_service_added: status: %s, service: %s', status, service)

    @utils.glib_callback()
    def on_service_removed(self, status, handle):
        """Handles service removed callback.

        Args:
            status: floss_enums.GattStatus.
            handle: Service record handle.
        """
        logging.debug('on_service_removed: status: %s, handle: %s', status, handle)

    @utils.glib_callback()
    def on_characteristic_read_request(self, addr, trans_id, offset, is_long, handle):
        """Handles the read request of the characteristic callback.

        Args:
            addr: Remote device MAC address.
            trans_id: Transaction id.
            offset: Represents the offset from which the attribute value should be read.
            is_long: A boolean value representing whether the characteristic size is longer than what we can put in the
                     ATT PDU.
            handle: The characteristic handle.
        """
        logging.debug(
            'on_characteristic_read_request: device address: %s, trans_id: %s, offset: %s, is_long: %s, handle: %s',
            addr, trans_id, offset, is_long, handle)

    @utils.glib_callback()
    def on_descriptor_read_request(self, addr, trans_id, offset, is_long, handle):
        """Handles the read request of the descriptor callback.

        Args:
            addr: Remote device MAC address.
            trans_id: Transaction id.
            offset: Represents the offset from which the descriptor value should be read.
            is_long: A boolean value representing whether the descriptor size is longer than what we can put in the
                     ATT PDU.
            handle: The descriptor handle.
        """
        logging.debug(
            'on_descriptor_read_request: device address: %s, trans_id: %s, offset: %s, is_long: %s, handle: %s', addr,
            trans_id, offset, is_long, handle)

    @utils.glib_callback()
    def on_characteristic_write_request(self, addr, trans_id, offset, len, is_prep, need_rsp, handle, value):
        """Handles the write request of the characteristic callback.

        Args:
            addr: Remote device MAC address.
            trans_id: Transaction id.
            offset: Represents the offset at which the attribute value should be written.
            len: The length of the attribute value that should be written.
            is_prep: A boolean value representing whether it's a "prepare write" command.
            need_rsp: A boolean value representing whether it's a "write no response" command.
            handle: The characteristic handle.
            value: The value that should be written to the attribute.
        """
        logging.debug(
            'on_characteristic_write_request: device address: %s, trans_id: %s, offset: %s, length: %s, is_prep: %s, '
            'need_rsp: %s, handle: %s, values: %s', addr, trans_id, offset, len, is_prep, need_rsp, handle, value)

    @utils.glib_callback()
    def on_descriptor_write_request(self, addr, trans_id, offset, len, is_prep, need_rsp, handle, value):
        """Handles the write request of the descriptor callback.

        Args:
            addr: Remote device MAC address.
            trans_id: Transaction id.
            offset: The offset value at which the value should be written.
            len: The length of the value that should be written.
            is_prep: A boolean value representing whether it's a "prepare write" command.
            need_rsp: A boolean value representing whether it's a "write no response" command.
            handle: The descriptor handle.
            value: The value that should be written to the descriptor.
        """
        logging.debug(
            'on_descriptor_write_request: device address: %s, trans_id: %s, offset: %s, length: %s, is_prep: %s, '
            'need_rsp: %s, handle: %s, values: %s', addr, trans_id, offset, len, is_prep, need_rsp, handle, value)

    @utils.glib_callback()
    def on_execute_write(self, addr, trans_id, exec_write):
        """Handles execute write callback.

        Args:
            addr: Remote device MAC address.
            trans_id: Transaction id.
            exec_write: A boolean value that indicates whether the write operation should be executed or canceled.
        """
        logging.debug('on_execute_write: device address: %s, trans_id: %s, exec_write: %s', addr, trans_id, exec_write)

    @utils.glib_callback()
    def on_notification_sent(self, addr, status):
        """Handles notification sent callback.

        Args:
            addr: Remote device MAC address.
            status: floss_enums.GattStatus.
        """
        logging.debug('on_notification_sent: device address: %s, status: %s', addr, status)

    @utils.glib_callback()
    def on_mtu_changed(self, addr, mtu):
        """Handles MTU changed callback.

        Args:
            addr: Remote device MAC address.
            mtu: Maximum transmission unit.
        """
        logging.debug('on_mtu_changed: device address: %s, mtu : %s', addr, mtu)

    @utils.glib_callback()
    def on_phy_update(self, addr, tx_phy, rx_phy, status):
        """Handles physical update callback.

        Args:
            addr: Remote device MAC address.
            tx_phy: The new TX PHY for the connection.
            rx_phy: The new RX PHY for the connection.
            status: floss_enums.GattStatus.
        """
        logging.debug('on_phy_update: device address: %s, tx_phy: %s, rx_phy: %s, status: %s', addr, tx_phy, rx_phy,
                      status)

    @utils.glib_callback()
    def on_phy_read(self, addr, tx_phy, rx_phy, status):
        """Handles physical read callback.

        Args:
            addr: Remote device MAC address.
            tx_phy: The current transmit PHY for the connection.
            rx_phy: The current receive PHY for the connection.
            status: floss_enums.GattStatus.
        """
        logging.debug('on_phy_read: device address: %s, tx_phy: %s, rx_phy: %s, status: %s', addr, tx_phy, rx_phy,
                      status)

    @utils.glib_callback()
    def on_connection_updated(self, addr, interval, latency, timeout, status):
        """Handles connection updated callback.

        Args:
            addr: Remote device MAC address.
            interval: Connection interval value.
            latency: The number of consecutive connection events during which the device doesn't have to be listening.
            timeout: Supervision timeout for this connection in milliseconds.
            status: floss_enums.GattStatus.
        """
        logging.debug('on_connection_updated: device address: %s, interval: %s, latency: %s, timeout: %s, status: %s',
                      addr, interval, latency, timeout, status)

    @utils.glib_callback()
    def on_subrate_change(self, addr, subrate_factor, latency, cont_num, timeout, status):
        """Handles subrate changed callback.

        Args:
            addr: Remote device MAC address.
            subrate_factor: Subrate factor value.
            latency: The number of consecutive connection events during which the device doesn't have to be listening.
            cont_num: Continuation number.
            timeout: Supervision timeout for this connection in milliseconds.
            status: floss_enums.GattStatus.
        """
        logging.debug(
            'on_subrate_change: device address: %s, subrate_factor: %s, latency: %s, cont_num: %s, timeout: %s, '
            'status: %s', addr, subrate_factor, latency, cont_num, timeout, status)

    @utils.glib_call(False)
    def has_proxy(self):
        """Checks whether GATT server proxy can be acquired."""
        return bool(self.proxy())

    def proxy(self):
        """Gets proxy object to GATT server interface for method calls."""
        return self.bus.get(self.ADAPTER_SERVICE, self.objpath)[self.GATT_SERVER_INTERFACE]

    @utils.glib_call(False)
    def register_server(self, app_uuid, eatt_support):
        """Registers GATT server with provided UUID.

        Args:
            app_uuid: GATT application uuid.
            eatt_support: A boolean value that indicates whether EATT is supported.

        Returns:
            True on success, False otherwise.
        """
        self.proxy().RegisterServer(app_uuid, self.cb_dbus_objpath, eatt_support)
        return True

    @utils.glib_call(False)
    def unregister_server(self, server_id):
        """Unregisters GATT server for this client.

        Args:
            server_id: Bluetooth GATT server id.

        Returns:
            True on success, False otherwise.
        """
        self.proxy().UnregisterServer(server_id)
        return True

    @utils.glib_call(None)
    def server_connect(self, server_id, addr, is_direct, transport):
        """Connects remote device to GATT server.

        Args:
            server_id: Bluetooth GATT server id.
            addr: Remote device MAC address.
            is_direct: A boolean value that specifies whether the connection should be made using direct connection.
            transport: BtTransport type.

        Returns:
            Server connect as boolean on success, None otherwise.
        """
        return self.proxy().ServerConnect(server_id, addr, is_direct, transport)

    @utils.glib_call(None)
    def server_disconnect(self, server_id, addr):
        """Disconnects remote device from the GATT server.

        Args:
            server_id: Bluetooth GATT server id.
            addr: Remote device MAC address.

        Returns:
            Server disconnect as boolean on success, None otherwise.
        """
        return self.proxy().ServerDisconnect(server_id, addr)

    @utils.glib_call(False)
    def add_service(self, server_id, service):
        """Adds GATT service.

        Args:
            server_id: Bluetooth GATT server id.
            service: BluetoothGattService.

        Returns:
            True on success, False otherwise.
        """
        self.proxy().AddService(server_id, service)
        return True

    @utils.glib_call(False)
    def remove_service(self, server_id, handle):
        """Removes GATT service.

        Args:
            server_id: Bluetooth GATT server id.
            handle: Service record handle.

        Returns:
            True on success, False otherwise.
        """
        self.proxy().RemoveService(server_id, handle)
        return True

    @utils.glib_call(False)
    def clear_services(self, server_id):
        """Clears GATT services.

        Args:
            server_id: Bluetooth GATT server id.

        Returns:
            True on success, False otherwise.
        """
        self.proxy().ClearServices(server_id)
        return True

    @utils.glib_call(None)
    def send_response(self, server_id, addr, request_id, status, offset, value):
        """Sends GATT response.

        Args:
            server_id: Bluetooth GATT server id.
            addr: Remote device MAC address.
            request_id: Request id.
            status: floss_enums.GattStatus.
            offset: The offset value to be sent in the response.
            value: The attribute value to be sent in the response.

        Returns:
            Response send as boolean on success, None otherwise.
        """
        return self.proxy().SendResponse(server_id, addr, request_id, status, offset, value)

    @utils.glib_call(None)
    def send_notification(self, server_id, addr, handle, confirm, value):
        """Sends GATT notification.

        Args:
            server_id: Bluetooth GATT server id.
            addr: Remote device MAC address.
            handle: The attribute handle of the attribute to send the notification for.
            confirm: A boolean value indicating whether the client should send a confirmation in response to
                     the notification.
            value: The notification data to send.

        Returns:
            Notification send as boolean on success, None otherwise.
        """
        return self.proxy().SendNotification(server_id, addr, handle, confirm, value)

    @utils.glib_call(False)
    def server_set_preferred_phy(self, server_id, addr, tx_phy, rx_phy, phy_options):
        """Sets preferred phy for server.

        Args:
            server_id: Bluetooth GATT server id.
            addr: Remote device MAC address.
            tx_phy: Preferred PHY for transmitting data.
            rx_phy: Preferred PHY for receiving data.
            phy_options: Preferred Phy options.

        Returns:
            True on success, False otherwise.
        """
        self.proxy().ServerSetPreferredPhy(server_id, addr, tx_phy, rx_phy, phy_options)
        return True

    @utils.glib_call(False)
    def server_read_phy(self, server_id, addr):
        """Reads phy of server.

        Args:
            server_id: Bluetooth GATT server id.
            addr: Remote device MAC address.

        Returns:
            True on success, False otherwise.
        """
        self.proxy().ServerReadPhy(server_id, addr)
        return True
