# Copyright 2023 Google LLC
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
"""Client Class to access the Floss GATT server interface."""

import collections
import logging

from floss.pandora.floss import bluetooth_gatt_service
from floss.pandora.floss import floss_enums
from floss.pandora.floss import observer_base
from floss.pandora.floss import utils
from gi.repository import GLib


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

    def on_characteristic_write_request(self, addr, trans_id, offset, length, is_prep, need_rsp, handle, value):
        """Called when there is a request to write a characteristic.

        Args:
            addr: Remote device MAC address.
            trans_id: Transaction id.
            offset: Represents the offset at which the attribute value should be written.
            length: The length of the attribute value that should be written.
            is_prep: A boolean value representing whether it's a 'prepare write' command.
            need_rsp: A boolean value representing whether it's a 'write no response' command.
            handle: The characteristic handle.
            value: The value that should be written to the attribute.
        """
        pass

    def on_descriptor_write_request(self, addr, trans_id, offset, length, is_prep, need_rsp, handle, value):
        """Called when there is a request to write a descriptor.

        Args:
            addr: Remote device MAC address.
            trans_id: Transaction id.
            offset: The offset value at which the value should be written.
            length: The length of the value that should be written.
            is_prep: A boolean value representing whether it's a 'prepare write' command.
            need_rsp: A boolean value representing whether it's a 'write no response' command.
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


def uuid16_to_uuid128(uuid):
    """Converts 16-bit UUID to 128-bit UUID.

    Args:
        uuid: 16-bit UUID value.

    Returns:
        128-bit UUID.
    """
    return utils.uuid16_to_uuid128(uuid).upper()


class FlossGattServer(GattServerCallbacks):
    """Handles method calls and callbacks from the GATT server interface."""

    ADAPTER_SERVICE = 'org.chromium.bluetooth'
    GATT_SERVER_INTERFACE = 'org.chromium.bluetooth.BluetoothGatt'
    GATT_OBJECT_PATTERN = '/org/chromium/bluetooth/hci{}/gatt'
    GATT_CB_OBJ_NAME = 'test_gatt_server'
    CB_EXPORTED_INTF = 'org.chromium.bluetooth.BluetoothGattServerCallback'

    # Bluetooth GATT DataBase attribute type.
    BTGATT_DB_PRIMARY_SERVICE = 0
    BTGATT_DB_SECONDARY_SERVICE = 1
    BTGATT_DB_INCLUDED_SERVICE = 2
    BTGATT_DB_CHARACTERISTIC = 3
    BTGATT_DB_DESCRIPTOR = 4

    GATT_READ_AUTH_REQUIRED = floss_enums.GattPermission.PERM_READ_ENCRYPTED
    GATT_READ_MITM_REQUIRED = floss_enums.GattPermission.PERM_READ_ENC_MITM
    GATT_WRITE_AUTH_REQUIRED = (floss_enums.GattPermission.PERM_WRITE_ENCRYPTED |
                                floss_enums.GattPermission.PERM_WRITE_SIGNED)
    GATT_WRITE_MITM_REQUIRED = (floss_enums.GattPermission.PERM_WRITE_ENC_MITM |
                                floss_enums.GattPermission.PERM_WRITE_SIGNED_MITM)

    SERVICE_ATTR_UUID = uuid16_to_uuid128('7777')
    SERVER_CHANGED_CHAR_UUID = uuid16_to_uuid128('2a05')
    SERVER_SUP_FEAT_UUID = uuid16_to_uuid128('2b3a')
    CLIENT_SUP_FEAT_UUID = uuid16_to_uuid128('2b29')
    DATABASE_HASH_UUID = uuid16_to_uuid128('2b2a')
    LONG_CHAR_UUID = uuid16_to_uuid128('b000')
    DESCRIPTOR_UUID = uuid16_to_uuid128('b001')
    KEY_SIZE_CHAR_UUID = uuid16_to_uuid128('b002')
    UNAUTHORIZED_CHAR_UUID = uuid16_to_uuid128('b003')
    AUTHENTICATION_CHAR_UUID = uuid16_to_uuid128('b004')
    INVALID_FOR_LE_UUID = uuid16_to_uuid128('b005')
    INVALID_FOR_BR_EDR_UUID = uuid16_to_uuid128('b006')
    NO_READ_CHAR_UUID = uuid16_to_uuid128('b007')
    NO_WRITE_CHAR_UUID = uuid16_to_uuid128('b008')
    SHORT_CHAR_UUID = uuid16_to_uuid128('b009')
    WRITE_NO_RESPONSE_CHAR_UUID = uuid16_to_uuid128('b00a')
    NOTIFY_CHAR_UUID = uuid16_to_uuid128('b00b')
    AUTHENTICATE_SHORT_CHAR_UUID = uuid16_to_uuid128('b00c')
    CCC_DESCRIPTOR_UUID = uuid16_to_uuid128('2902')
    LONG_CHAR_512_UUID = uuid16_to_uuid128('b00d')
    MITM_SHORT_CHAR_UUID = uuid16_to_uuid128('b00e')

    LONG_TEST_VALUE_512 = [bytes([7])] * 512
    LONG_TEST_VALUE = [bytes([7])] * 48
    SHORT_TEST_VALUE = [bytes([7])] * 2
    NOTIFICATION_VALUE = [bytes([1]), bytes([0])]
    INDICATION_VALUE = [bytes([2]), bytes([0])]

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
                    <arg type="i" name="length" direction="in" />
                    <arg type="b" name="is_prep" direction="in" />
                    <arg type="b" name="need_rsp" direction="in" />
                    <arg type="i" name="handle" direction="in" />
                    <arg type="ay" name="value" direction="in" />
                </method>
                <method name="OnDescriptorWriteRequest">
                    <arg type="s" name="addr" direction="in" />
                    <arg type="i" name="trans_id" direction="in" />
                    <arg type="i" name="offset" direction="in" />
                    <arg type="i" name="length" direction="in" />
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

        def OnCharacteristicWriteRequest(self, addr, trans_id, offset, length, is_prep, need_rsp, handle, value):
            """Handles characteristic write request callback.

            Args:
                addr: Remote device MAC address.
                trans_id: Transaction id.
                offset: Represents the offset from which the attribute value should be read.
                length: The length of the value that should be written.
                is_prep: A boolean value representing whether it's a 'prepare write' command.
                need_rsp: A boolean value representing whether it's a 'write no response' command.
                handle: The descriptor handle.
                value: The value that should be written to the descriptor.
            """
            for observer in self.observers.values():
                observer.on_characteristic_write_request(addr, trans_id, offset, length, is_prep, need_rsp, handle,
                                                         value)

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

        def OnDescriptorWriteRequest(self, addr, trans_id, offset, length, is_prep, need_rsp, handle, value):
            """Handles descriptor write request callback.

            Args:
                addr: Remote device MAC address.
                trans_id: Transaction id.
                offset: The offset value at which the value should be written.
                length: The length of the value that should be written.
                is_prep: A boolean value representing whether it's a 'prepare write' command.
                need_rsp: A boolean value representing whether it's a 'write no response' command.
                handle: The descriptor handle.
                value: The value that should be written to the descriptor.
            """
            for observer in self.observers.values():
                observer.on_descriptor_write_request(addr, trans_id, offset, length, is_prep, need_rsp, handle, value)

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

        # Create and publish callbacks.
        self.callbacks = self.ExportedGattServerCallbacks()
        self.callbacks.add_observer('gatt_testing_server', self)
        self.bus.register_object(self.cb_dbus_objpath, self.callbacks, None)
        self.server_id = None
        self.mtu_value = -1
        self.write_requests = collections.deque([])
        self.gatt_services = []
        # Indicate if PTS attribute values were set or not (set only one time).
        self.pts_set_values = False

    def __del__(self):
        """Destructor."""
        del self.callbacks

    def check_permissions(self, uuid):
        """Checks request UUID permission.

        Args:
            uuid: 128-bit UUID value as string.

        Returns:
            GATT status.
        """
        if uuid is None:
            logging.debug('check_permissions uuid is None or value is None')
            return floss_enums.GattStatus.NOT_FOUND
        elif uuid == self.LONG_CHAR_UUID:
            logging.debug('check_permissions: uuid == long_char, return GATT_SUCCESS')
        elif uuid == self.DESCRIPTOR_UUID:
            logging.debug('check_permissions: uuid == descriptor, return GATT_SUCCESS')
        elif uuid == self.UNAUTHORIZED_CHAR_UUID:
            logging.debug('check_permissions: uuid == unauthorize_char, return GATT_INSUF_AUTHORIZATION')
            return floss_enums.GattStatus.INSUF_AUTHORIZATION
        elif uuid == self.AUTHENTICATION_CHAR_UUID:
            logging.debug('check_permissions: uuid == authenticate_char, return GATT_SUCCESS')
        elif uuid == self.INVALID_FOR_LE_UUID:
            logging.debug('check_permissions: uuid == invalid_for_le, return GATT_SUCCESS')
            return floss_enums.GattStatus.INTERNAL_ERROR
        elif uuid == self.INVALID_FOR_BR_EDR_UUID:
            logging.debug('check_permissions: uuid == invalid_for_bredr, return GATT_SUCCESS')
            return floss_enums.GattStatus.INTERNAL_ERROR
        elif uuid == self.NO_READ_CHAR_UUID:
            logging.debug('check_permissions: uuid == no_read_char, return GATT_READ_NOT_PERMIT')
            return floss_enums.GattStatus.READ_NOT_PERMIT
        elif uuid == self.NO_WRITE_CHAR_UUID:
            logging.debug('check_permissions: uuid == no_write_char, return GATT_WRITE_NOT_PERMIT')
            return floss_enums.GattStatus.WRITE_NOT_PERMIT
        elif uuid == self.SHORT_CHAR_UUID:
            logging.debug('check_permissions: uuid == short_char, return GATT_SUCCESS')
        elif uuid == self.WRITE_NO_RESPONSE_CHAR_UUID:
            logging.debug('check_permissions: uuid == write_no_rsp_char, return GATT_SUCCESS')
        elif uuid == self.AUTHENTICATE_SHORT_CHAR_UUID:
            logging.debug('check_permissions: uuid == authenticate_short_char, return GATT_SUCCESS')
        elif uuid == self.CCC_DESCRIPTOR_UUID:
            logging.debug('check_permissions: uuid == ccc_descriptor, return GATT_SUCCESS')
        elif uuid == self.LONG_CHAR_512_UUID:
            logging.debug('check_permissions: uuid == long_char512, return GATT_SUCCESS')
        elif uuid == self.MITM_SHORT_CHAR_UUID:
            logging.debug('check_permissions: uuid == mitm_short_char, return GATT_SUCCESS')
        else:
            logging.debug('check_permissions: uuid: %s unknown return GATT_NOT_FOUND', uuid)
            return floss_enums.GattStatus.NOT_FOUND
        return floss_enums.GattStatus.SUCCESS

    def generic_write(self, offset, length, handle, value):
        """Writes GATT attribute value.

        Args:
            offset: Represents the offset at which the attribute value should be written.
            length: The length of the attribute value that should be written.
            handle: The attribute handle.
            value: The value that should be written to the attribute.

        Returns:
            (Bluetooth Gatt status, attribute value).
        """
        attribute = self.get_attribute_from_handle(handle)
        attr_value = attribute.value if attribute is not None else []

        if len(attr_value) < offset:
            logging.info('len(char_value) < offset')
            return floss_enums.GattStatus.INVALID_OFFSET, []

        if offset + length > len(attr_value):
            logging.info('offset + len > len(char_value)')
            return floss_enums.GattStatus.INVALID_ATTRLEN, []

        attr_value[offset:(offset + length)] = value
        self.update_attribute_value(attribute.uuid, attr_value)
        return floss_enums.GattStatus.SUCCESS, attr_value

    def generic_read(self, offset, handle):
        """Reads GATT attribute value.

        Args:
            offset: Represents the offset from which the attribute value should be read.
            handle: The attribute handle.

        Returns:
            (Bluetooth Gatt status, attribute value).
        """
        attr_value = self.get_attribute_value_from_handle(handle)

        if offset < 0 or offset > len(attr_value):
            logging.info('generic_read len(value) < offset')
            return floss_enums.GattStatus.INVALID_OFFSET, []

        return floss_enums.GattStatus.SUCCESS, attr_value[offset:]

    def get_uuid_from_handle(self, handle):
        """Gets attribute UUID from handle.

        Args:
            handle: The attribute handle.

        Returns:
            Attribute UUID as string if found, empty string otherwise.
        """
        attribute = self.get_attribute_from_handle(handle)
        return '' if attribute is None else attribute.uuid

    def get_attribute_value_from_handle(self, handle):
        """Gets attribute value from handle.

        Args:
            handle: The attribute handle.

        Returns:
            Attribute value as list if found, empty list otherwise.
        """
        attribute = self.get_attribute_from_handle(handle)
        return [] if attribute is None else attribute.value

    def get_attribute_from_handle(self, handle):
        """Gets GATT attribute from handle.

        Args:
            handle: The attribute handle.

        Returns:
            GATT attribute if found, None otherwise.
        """
        for service in self.gatt_services:
            if int(service.instance_id) == int(handle):
                return service
            for char in service.characteristics:
                if int(char.instance_id) == int(handle):
                    return char
                for desc in char.descriptors:
                    if int(desc.instance_id) == int(handle):
                        return desc

        return None

    def update_attribute_value(self, uuid, value):
        """Update GATT attribute value.

        Args:
            uuid: GATT attribute uuid as string.
            value: Attribute value as list.
        """
        for service in self.gatt_services:
            if service.uuid == uuid:
                service.value = value
                return
            for char in service.characteristics:
                if char.uuid == uuid:
                    char.value = value
                    return
                for desc in char.descriptors:
                    if desc.uuid == uuid:
                        desc.value = value
                        return
        logging.error('No attribute found with uuid = %s!', uuid)

    def on_attr_read(self, addr, trans_id, offset, is_long, handle):
        """Handles the read request for GATT attribute.

        Args:
            addr: Remote device MAC address.
            trans_id: Transaction id.
            offset: Represents the offset from which the attribute value should be read.
            is_long: A boolean value representing whether the attribute size is longer than what we can put in the ATT
                     PDU.
            handle: The attribute handle.
        """
        uuid = self.get_uuid_from_handle(handle)
        status = self.check_permissions(uuid)
        value = []
        if status == floss_enums.GattStatus.SUCCESS:
            if not is_long:
                offset = 0
            status, value = self.generic_read(offset, handle)

        self.proxy().SendResponse(self.server_id, addr, trans_id, status, offset, value)

    def on_attr_write(self, addr, trans_id, offset, length, is_prep, need_rsp, handle, value):
        """Handles the read request for GATT attribute.

        Args:
            addr: Remote device MAC address.
            trans_id: Transaction id.
            offset: Represents the offset at which the attribute value should be written.
            length: The length of the attribute value that should be written.
            is_prep: A boolean value representing whether it's a 'prepare write' command.
            need_rsp: A boolean value representing whether it's a 'write no response' command.
            handle: The attribute handle.
            value: The value that should be written to the attribute.
        """
        uuid = self.get_uuid_from_handle(handle)
        status = self.check_permissions(uuid)

        if status == floss_enums.GattStatus.SUCCESS:
            if is_prep:
                self.write_requests.append((addr, trans_id, offset, length, is_prep, need_rsp, handle, value))
            else:
                # write request.
                status, value = self.generic_write(offset, length, handle, value)
        else:
            value = []

        if need_rsp:
            self.proxy().SendResponse(self.server_id, addr, trans_id, status, offset, value)
        else:
            logging.info('No need to send response.')

    def __set_pts_attributes_value(self):
        """Sets PTS attributes value."""
        if not self.pts_set_values:
            self.update_attribute_value(self.SERVICE_ATTR_UUID, self.SHORT_TEST_VALUE)
            self.update_attribute_value(self.SERVER_CHANGED_CHAR_UUID, self.SHORT_TEST_VALUE)
            self.update_attribute_value(self.SERVER_SUP_FEAT_UUID, self.SHORT_TEST_VALUE)
            self.update_attribute_value(self.CLIENT_SUP_FEAT_UUID, self.SHORT_TEST_VALUE)
            self.update_attribute_value(self.DATABASE_HASH_UUID, self.SHORT_TEST_VALUE)
            self.update_attribute_value(self.LONG_CHAR_UUID, self.LONG_TEST_VALUE)
            self.update_attribute_value(self.DESCRIPTOR_UUID, self.LONG_TEST_VALUE)
            self.update_attribute_value(self.KEY_SIZE_CHAR_UUID, self.LONG_TEST_VALUE)
            self.update_attribute_value(self.UNAUTHORIZED_CHAR_UUID, self.LONG_TEST_VALUE)
            self.update_attribute_value(self.AUTHENTICATION_CHAR_UUID, self.LONG_TEST_VALUE)
            self.update_attribute_value(self.INVALID_FOR_LE_UUID, self.LONG_TEST_VALUE)
            self.update_attribute_value(self.INVALID_FOR_BR_EDR_UUID, self.LONG_TEST_VALUE)
            self.update_attribute_value(self.NO_READ_CHAR_UUID, self.LONG_TEST_VALUE)
            self.update_attribute_value(self.NO_WRITE_CHAR_UUID, self.LONG_TEST_VALUE)
            self.update_attribute_value(self.SHORT_CHAR_UUID, self.SHORT_TEST_VALUE)
            self.update_attribute_value(self.WRITE_NO_RESPONSE_CHAR_UUID, self.LONG_TEST_VALUE)
            self.update_attribute_value(self.NOTIFY_CHAR_UUID, self.SHORT_TEST_VALUE)
            self.update_attribute_value(self.AUTHENTICATE_SHORT_CHAR_UUID, self.SHORT_TEST_VALUE)
            self.update_attribute_value(self.CCC_DESCRIPTOR_UUID, self.INDICATION_VALUE)
            self.update_attribute_value(self.LONG_CHAR_512_UUID, self.LONG_TEST_VALUE_512)
            self.update_attribute_value(self.MITM_SHORT_CHAR_UUID, self.SHORT_TEST_VALUE)
            self.pts_set_values = True

    def __define_services(self):
        """Defines GATT services for PTS testing."""

        service = bluetooth_gatt_service.Service()
        characteristic = bluetooth_gatt_service.Characteristic()
        descriptor = bluetooth_gatt_service.Descriptor()
        service.included_services = []
        service.characteristics = []
        characteristic.descriptors = []

        service.uuid = self.SERVICE_ATTR_UUID
        service.instance_id = 0
        service.service_type = self.BTGATT_DB_PRIMARY_SERVICE

        characteristic.uuid = self.SERVER_CHANGED_CHAR_UUID
        characteristic.instance_id = 1
        characteristic.properties = floss_enums.GattCharacteristicProprieties.CHAR_PROP_BIT_INDICATE
        characteristic.permissions = floss_enums.GattPermission.PERM_READ | floss_enums.GattPermission.PERM_WRITE
        characteristic.key_size = 0
        characteristic.write_type = floss_enums.GattWriteType.WRITE

        descriptor.uuid = self.CCC_DESCRIPTOR_UUID
        descriptor.instance_id = 2
        descriptor.permissions = floss_enums.GattPermission.PERM_READ | floss_enums.GattPermission.PERM_WRITE
        characteristic.descriptors.append(descriptor)

        service.characteristics.append(characteristic)

        # ----------------------------------------------------
        characteristic = bluetooth_gatt_service.Characteristic()
        characteristic.uuid = self.SERVER_SUP_FEAT_UUID
        characteristic.instance_id = 3
        characteristic.properties = floss_enums.GattCharacteristicProprieties.CHAR_PROP_BIT_READ
        characteristic.permissions = floss_enums.GattPermission.PERM_READ
        characteristic.key_size = 0
        characteristic.write_type = floss_enums.GattWriteType.INVALID
        characteristic.descriptors = []

        service.characteristics.append(characteristic)

        # ----------------------------------------------------
        characteristic = bluetooth_gatt_service.Characteristic()
        characteristic.uuid = self.CLIENT_SUP_FEAT_UUID
        characteristic.instance_id = 4
        characteristic.properties = (floss_enums.GattCharacteristicProprieties.CHAR_PROP_BIT_READ |
                                     floss_enums.GattCharacteristicProprieties.CHAR_PROP_BIT_WRITE)
        characteristic.permissions = floss_enums.GattPermission.PERM_READ | floss_enums.GattPermission.PERM_WRITE
        characteristic.key_size = 0
        characteristic.write_type = floss_enums.GattWriteType.INVALID
        characteristic.descriptors = []

        service.characteristics.append(characteristic)

        # ----------------------------------------------------
        characteristic = bluetooth_gatt_service.Characteristic()
        characteristic.uuid = self.DATABASE_HASH_UUID
        characteristic.instance_id = 5
        characteristic.properties = floss_enums.GattCharacteristicProprieties.CHAR_PROP_BIT_READ
        characteristic.permissions = floss_enums.GattPermission.PERM_READ
        characteristic.key_size = 0
        characteristic.write_type = floss_enums.GattWriteType.INVALID
        characteristic.descriptors = []

        service.characteristics.append(characteristic)

        # ----------------------------------------------------
        characteristic = bluetooth_gatt_service.Characteristic()
        characteristic.uuid = self.DATABASE_HASH_UUID
        characteristic.instance_id = 5
        characteristic.properties = floss_enums.GattCharacteristicProprieties.CHAR_PROP_BIT_READ
        characteristic.permissions = floss_enums.GattPermission.PERM_READ
        characteristic.key_size = 0
        characteristic.write_type = floss_enums.GattWriteType.INVALID
        characteristic.descriptors = []

        service.characteristics.append(characteristic)

        # ----------------------------------------------------
        characteristic = bluetooth_gatt_service.Characteristic()
        descriptor = bluetooth_gatt_service.Descriptor()
        characteristic.uuid = self.LONG_CHAR_UUID
        characteristic.instance_id = 6
        characteristic.properties = (floss_enums.GattCharacteristicProprieties.CHAR_PROP_BIT_READ |
                                     floss_enums.GattCharacteristicProprieties.CHAR_PROP_BIT_WRITE)
        characteristic.permissions = floss_enums.GattPermission.PERM_READ | floss_enums.GattPermission.PERM_WRITE
        characteristic.key_size = 0
        characteristic.write_type = floss_enums.GattWriteType.WRITE
        characteristic.descriptors = []

        descriptor.uuid = self.DESCRIPTOR_UUID
        descriptor.instance_id = 7
        descriptor.permissions = floss_enums.GattPermission.PERM_READ | floss_enums.GattPermission.PERM_WRITE
        characteristic.descriptors.append(descriptor)
        service.characteristics.append(characteristic)

        # ----------------------------------------------------
        characteristic = bluetooth_gatt_service.Characteristic()
        characteristic.uuid = self.KEY_SIZE_CHAR_UUID
        characteristic.instance_id = 8
        characteristic.properties = (floss_enums.GattCharacteristicProprieties.CHAR_PROP_BIT_READ |
                                     floss_enums.GattCharacteristicProprieties.CHAR_PROP_BIT_WRITE)
        characteristic.permissions = (
            10 << 12) | floss_enums.GattPermission.PERM_READ_ENCRYPTED | floss_enums.GattPermission.PERM_WRITE_ENCRYPTED
        characteristic.key_size = 10
        characteristic.write_type = floss_enums.GattWriteType.WRITE
        characteristic.descriptors = []

        service.characteristics.append(characteristic)
        # ----------------------------------------------------
        characteristic = bluetooth_gatt_service.Characteristic()
        characteristic.uuid = self.UNAUTHORIZED_CHAR_UUID
        characteristic.instance_id = 9
        characteristic.properties = (floss_enums.GattCharacteristicProprieties.CHAR_PROP_BIT_READ |
                                     floss_enums.GattCharacteristicProprieties.CHAR_PROP_BIT_WRITE)
        characteristic.permissions = floss_enums.GattPermission.PERM_READ | floss_enums.GattPermission.PERM_WRITE
        characteristic.key_size = 0
        characteristic.write_type = floss_enums.GattWriteType.WRITE
        characteristic.descriptors = []

        service.characteristics.append(characteristic)
        # ----------------------------------------------------
        characteristic = bluetooth_gatt_service.Characteristic()
        characteristic.uuid = self.AUTHENTICATION_CHAR_UUID
        characteristic.instance_id = 10
        characteristic.properties = (floss_enums.GattCharacteristicProprieties.CHAR_PROP_BIT_READ |
                                     floss_enums.GattCharacteristicProprieties.CHAR_PROP_BIT_WRITE |
                                     floss_enums.GattCharacteristicProprieties.CHAR_PROP_BIT_AUTH)
        characteristic.permissions = self.GATT_READ_AUTH_REQUIRED | self.GATT_WRITE_AUTH_REQUIRED
        characteristic.key_size = 0
        characteristic.write_type = floss_enums.GattWriteType.WRITE
        characteristic.descriptors = []

        service.characteristics.append(characteristic)

        # ----------------------------------------------------
        characteristic = bluetooth_gatt_service.Characteristic()
        characteristic.uuid = self.INVALID_FOR_LE_UUID
        characteristic.instance_id = 11
        characteristic.properties = (floss_enums.GattCharacteristicProprieties.CHAR_PROP_BIT_READ |
                                     floss_enums.GattCharacteristicProprieties.CHAR_PROP_BIT_WRITE)
        characteristic.permissions = floss_enums.GattPermission.PERM_READ | floss_enums.GattPermission.PERM_WRITE
        characteristic.key_size = 0
        characteristic.write_type = floss_enums.GattWriteType.WRITE
        characteristic.descriptors = []

        service.characteristics.append(characteristic)
        # ----------------------------------------------------
        characteristic = bluetooth_gatt_service.Characteristic()
        characteristic.uuid = self.INVALID_FOR_BR_EDR_UUID
        characteristic.instance_id = 12
        characteristic.properties = (floss_enums.GattCharacteristicProprieties.CHAR_PROP_BIT_READ |
                                     floss_enums.GattCharacteristicProprieties.CHAR_PROP_BIT_WRITE)
        characteristic.permissions = floss_enums.GattPermission.PERM_READ | floss_enums.GattPermission.PERM_WRITE
        characteristic.key_size = 0
        characteristic.write_type = floss_enums.GattWriteType.WRITE
        characteristic.descriptors = []

        service.characteristics.append(characteristic)
        # ----------------------------------------------------
        characteristic = bluetooth_gatt_service.Characteristic()
        characteristic.uuid = self.NO_READ_CHAR_UUID
        characteristic.instance_id = 13
        # Only write property.
        characteristic.properties = floss_enums.GattCharacteristicProprieties.CHAR_PROP_BIT_WRITE
        # Only write permission.
        characteristic.permissions = floss_enums.GattPermission.PERM_WRITE
        characteristic.key_size = 0
        characteristic.write_type = floss_enums.GattWriteType.WRITE
        characteristic.descriptors = []

        service.characteristics.append(characteristic)
        # ----------------------------------------------------
        characteristic = bluetooth_gatt_service.Characteristic()
        characteristic.uuid = self.NO_WRITE_CHAR_UUID
        characteristic.instance_id = 14
        # Only read property.
        characteristic.properties = floss_enums.GattCharacteristicProprieties.CHAR_PROP_BIT_READ
        # Only read permission.
        characteristic.permissions = floss_enums.GattPermission.PERM_READ
        characteristic.key_size = 0
        characteristic.write_type = floss_enums.GattWriteType.INVALID
        characteristic.descriptors = []

        service.characteristics.append(characteristic)
        # ----------------------------------------------------
        characteristic = bluetooth_gatt_service.Characteristic()
        characteristic.uuid = self.SHORT_CHAR_UUID
        characteristic.instance_id = 15
        characteristic.properties = (floss_enums.GattCharacteristicProprieties.CHAR_PROP_BIT_READ |
                                     floss_enums.GattCharacteristicProprieties.CHAR_PROP_BIT_WRITE)
        characteristic.permissions = floss_enums.GattPermission.PERM_READ | floss_enums.GattPermission.PERM_WRITE
        characteristic.key_size = 0
        characteristic.write_type = floss_enums.GattWriteType.WRITE
        characteristic.descriptors = []

        service.characteristics.append(characteristic)
        # ----------------------------------------------------
        characteristic = bluetooth_gatt_service.Characteristic()
        characteristic.uuid = self.WRITE_NO_RESPONSE_CHAR_UUID
        characteristic.instance_id = 16
        # Write without response property.
        characteristic.properties = (floss_enums.GattCharacteristicProprieties.CHAR_PROP_BIT_READ |
                                     floss_enums.GattCharacteristicProprieties.CHAR_PROP_BIT_WRITE_NR)
        characteristic.permissions = floss_enums.GattPermission.PERM_READ | floss_enums.GattPermission.PERM_WRITE
        characteristic.key_size = 0
        characteristic.write_type = floss_enums.GattWriteType.WRITE_NO_RSP
        characteristic.descriptors = []

        service.characteristics.append(characteristic)
        # ----------------------------------------------------
        characteristic = bluetooth_gatt_service.Characteristic()
        descriptor = bluetooth_gatt_service.Descriptor()
        characteristic.uuid = self.NOTIFY_CHAR_UUID
        characteristic.instance_id = 17
        characteristic.properties = (floss_enums.GattCharacteristicProprieties.CHAR_PROP_BIT_NOTIFY |
                                     floss_enums.GattCharacteristicProprieties.CHAR_PROP_BIT_INDICATE)
        characteristic.permissions = floss_enums.GattPermission.PERM_READ | floss_enums.GattPermission.PERM_WRITE
        characteristic.key_size = 0
        characteristic.write_type = floss_enums.GattWriteType.WRITE
        characteristic.descriptors = []

        descriptor.uuid = self.CCC_DESCRIPTOR_UUID
        descriptor.instance_id = 18
        descriptor.permissions = floss_enums.GattPermission.PERM_READ | floss_enums.GattPermission.PERM_WRITE

        characteristic.descriptors.append(descriptor)
        service.characteristics.append(characteristic)
        # ----------------------------------------------------
        characteristic = bluetooth_gatt_service.Characteristic()
        characteristic.uuid = self.AUTHENTICATE_SHORT_CHAR_UUID
        characteristic.instance_id = 19
        characteristic.properties = (floss_enums.GattCharacteristicProprieties.CHAR_PROP_BIT_READ |
                                     floss_enums.GattCharacteristicProprieties.CHAR_PROP_BIT_WRITE |
                                     floss_enums.GattCharacteristicProprieties.CHAR_PROP_BIT_AUTH)
        characteristic.permissions = self.GATT_READ_AUTH_REQUIRED | self.GATT_WRITE_AUTH_REQUIRED
        characteristic.key_size = 0
        characteristic.write_type = floss_enums.GattWriteType.WRITE
        characteristic.descriptors = []

        service.characteristics.append(characteristic)
        # ----------------------------------------------------
        characteristic = bluetooth_gatt_service.Characteristic()
        characteristic.uuid = self.LONG_CHAR_512_UUID
        characteristic.instance_id = 20
        characteristic.properties = (floss_enums.GattCharacteristicProprieties.CHAR_PROP_BIT_READ |
                                     floss_enums.GattCharacteristicProprieties.CHAR_PROP_BIT_WRITE)
        characteristic.permissions = floss_enums.GattPermission.PERM_READ | floss_enums.GattPermission.PERM_WRITE
        characteristic.key_size = 0
        characteristic.write_type = floss_enums.GattWriteType.WRITE
        characteristic.descriptors = []

        service.characteristics.append(characteristic)
        # ----------------------------------------------------
        characteristic = bluetooth_gatt_service.Characteristic()
        characteristic.uuid = self.MITM_SHORT_CHAR_UUID
        characteristic.instance_id = 21
        characteristic.properties = (floss_enums.GattCharacteristicProprieties.CHAR_PROP_BIT_READ |
                                     floss_enums.GattCharacteristicProprieties.CHAR_PROP_BIT_WRITE |
                                     floss_enums.GattCharacteristicProprieties.CHAR_PROP_BIT_AUTH)
        characteristic.permissions = self.GATT_READ_MITM_REQUIRED | self.GATT_WRITE_MITM_REQUIRED
        characteristic.key_size = 0
        characteristic.write_type = floss_enums.GattWriteType.WRITE
        characteristic.descriptors = []
        service.characteristics.append(characteristic)
        # ----------------------------------------------------
        service_dict = bluetooth_gatt_service.convert_object_to_dict(service)
        self.proxy().AddService(self.server_id, self.make_dbus_service(service_dict))

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
        self.server_id = server_id
        self.__define_services()

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
        if status != floss_enums.GattStatus.SUCCESS:
            return
        self.gatt_services.append(bluetooth_gatt_service.create_gatt_service(service))

        logging.debug('on_service_added: status: %s, service: %s', status, service)

        # This function will run once when the server is initiated.
        self.__set_pts_attributes_value()

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

        self.on_attr_read(addr, trans_id, offset, is_long, handle)

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
        self.on_attr_read(addr, trans_id, offset, is_long, handle)

    @utils.glib_callback()
    def on_characteristic_write_request(self, addr, trans_id, offset, length, is_prep, need_rsp, handle, value):
        """Handles the write request of the characteristic callback.

        Args:
            addr: Remote device MAC address.
            trans_id: Transaction id.
            offset: Represents the offset at which the attribute value should be written.
            length: The length of the attribute value that should be written.
            is_prep: A boolean value representing whether it's a 'prepare write' command.
            need_rsp: A boolean value representing whether it's a 'write no response' command.
            handle: The characteristic handle.
            value: The value that should be written to the attribute.
        """
        logging.debug(
            'on_characteristic_write_request: device address: %s, trans_id: %s, offset: %s, length: %s, is_prep: %s, '
            'need_rsp: %s, handle: %s, values: %s', addr, trans_id, offset, length, is_prep, need_rsp, handle, value)
        self.on_attr_write(addr, trans_id, offset, length, is_prep, need_rsp, handle, value)

    @utils.glib_callback()
    def on_descriptor_write_request(self, addr, trans_id, offset, length, is_prep, need_rsp, handle, value):
        """Handles the write request of the descriptor callback.

        Args:
            addr: Remote device MAC address.
            trans_id: Transaction id.
            offset: The offset value at which the value should be written.
            length: The length of the value that should be written.
            is_prep: A boolean value representing whether it's a 'prepare write' command.
            need_rsp: A boolean value representing whether it's a 'write no response' command.
            handle: The descriptor handle.
            value: The value that should be written to the descriptor.
        """
        logging.debug(
            'on_descriptor_write_request: device address: %s, trans_id: %s, offset: %s, length: %s, is_prep: %s, '
            'need_rsp: %s, handle: %s, values: %s', addr, trans_id, offset, length, is_prep, need_rsp, handle, value)
        self.on_attr_write(addr, trans_id, offset, length, is_prep, need_rsp, handle, value)

    @utils.glib_callback()
    def on_execute_write(self, addr, trans_id, exec_write):
        """Handles execute write callback.

        Args:
            addr: Remote device MAC address.
            trans_id: Transaction id.
            exec_write: A boolean value that indicates whether the write operation should be executed or canceled.
        """
        logging.debug('on_execute_write: device address: %s, trans_id: %s, exec_write: %s', addr, trans_id, exec_write)

        if not exec_write:
            self.write_requests.clear()
            status = floss_enums.GattStatus.SUCCESS
        else:
            write_requests, self.write_requests = self.write_requests, collections.deque([])
            for request in write_requests:
                _, _, offset2, length, _, _, handle2, value2 = request
                status, _ = self.generic_write(offset2, length, handle2, value2)
                if status != floss_enums.GattStatus.SUCCESS:
                    break

        self.proxy().SendResponse(self.server_id, addr, trans_id, status, 0, [])

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
        self.mtu_value = mtu

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
    def unregister_server(self):
        """Unregisters GATT server for this client.

        Returns:
            True on success, False otherwise.
        """
        self.proxy().UnregisterServer(self.server_id)
        return True

    @utils.glib_call(None)
    def server_connect(self, addr, is_direct, transport):
        """Connects remote device to GATT server.

        Args:
            addr: Remote device MAC address.
            is_direct: A boolean value that specifies whether the connection should be made using direct connection.
            transport: BtTransport type.

        Returns:
            Server connect as boolean on success, None otherwise.
        """
        return self.proxy().ServerConnect(self.server_id, addr, is_direct, transport)

    @utils.glib_call(None)
    def server_disconnect(self, addr):
        """Disconnects remote device from the GATT server.

        Args:
            addr: Remote device MAC address.

        Returns:
            Server disconnect as boolean on success, None otherwise.
        """
        return self.proxy().ServerDisconnect(self.server_id, addr)

    @utils.glib_call(False)
    def add_service(self, service):
        """Adds GATT service.

        Args:
            service: BluetoothGattService.

        Returns:
            True on success, False otherwise.
        """
        self.proxy().AddService(self.server_id, service)
        return True

    @utils.glib_call(False)
    def remove_service(self, handle):
        """Removes GATT service.

        Args:
            handle: Service record handle.

        Returns:
            True on success, False otherwise.
        """
        self.proxy().RemoveService(self.server_id, handle)
        return True

    @utils.glib_call(False)
    def clear_services(self):
        """Clears GATT services.

        Returns:
            True on success, False otherwise.
        """
        self.proxy().ClearServices(self.server_id)
        return True

    @utils.glib_call(None)
    def send_response(self, addr, request_id, status, offset, value):
        """Sends GATT response.

        Args:
            addr: Remote device MAC address.
            request_id: Request id.
            status: floss_enums.GattStatus.
            offset: The offset value to be sent in the response.
            value: The attribute value to be sent in the response.

        Returns:
            Response send as boolean on success, None otherwise.
        """
        return self.proxy().SendResponse(self.server_id, addr, request_id, status, offset, value)

    @utils.glib_call(None)
    def send_notification(self, addr, handle, confirm, value):
        """Sends GATT notification.

        Args:
            addr: Remote device MAC address.
            handle: The attribute handle of the attribute to send the notification for.
            confirm: A boolean value indicating whether the client should send a confirmation in response to
                     the notification.
            value: The notification data to send.

        Returns:
            Notification send as boolean on success, None otherwise.
        """
        return self.proxy().SendNotification(self.server_id, addr, handle, confirm, value)

    @utils.glib_call(False)
    def server_set_preferred_phy(self, addr, tx_phy, rx_phy, phy_options):
        """Sets preferred phy for server.

        Args:
            addr: Remote device MAC address.
            tx_phy: Preferred PHY for transmitting data.
            rx_phy: Preferred PHY for receiving data.
            phy_options: Preferred Phy options.

        Returns:
            True on success, False otherwise.
        """
        self.proxy().ServerSetPreferredPhy(self.server_id, addr, tx_phy, rx_phy, phy_options)
        return True

    @utils.glib_call(False)
    def server_read_phy(self, addr):
        """Reads phy of server.

        Args:
            addr: Remote device MAC address.

        Returns:
            True on success, False otherwise.
        """
        self.proxy().ServerReadPhy(self.server_id, addr)
        return True

    def register_callback_observer(self, name, observer):
        """Add an observer for all callbacks.

        Args:
            name: Name of the observer.
            observer: Observer that implements all callback classes.
        """
        if isinstance(observer, GattServerCallbacks):
            self.callbacks.add_observer(name, observer)

    def unregister_callback_observer(self, name, observer):
        """Remove an observer for all callbacks.

        Args:
            name: Name of the observer.
            observer: Observer that implements all callback classes.
        """
        if isinstance(observer, GattServerCallbacks):
            self.callbacks.remove_observer(name, observer)

    def make_dbus_descriptor(self, uuid, instance_id, permissions):
        """Makes struct for descriptor D-Bus.

        Args:
            uuid : Descriptor UUID as string.
            instance_id: Descriptor identifier.
            permissions: Descriptor permissions.

        Returns:
            Dictionary of descriptor.
        """
        desc_uuid = utils.get_uuid_as_list(uuid)
        return {
            'uuid': GLib.Variant('ay', desc_uuid),
            'instance_id': GLib.Variant('i', instance_id),
            'permissions': GLib.Variant('i', permissions)
        }

    def make_dbus_characteristic(self, uuid, instance_id, properties, permissions, key_size, write_type, descriptors):
        """Makes struct for characteristic D-Bus.

        Args:
            uuid : Characteristic UUID as string.
            instance_id: Characteristic handle id.
            properties: Characteristic properties.
            permissions: Characteristic permissions.
            key_size: Characteristic key size.
            write_type: Characteristic write type.
            descriptors: Characteristic descriptors.

        Returns:
            Dictionary of characteristic.
        """
        desc = []
        for d in descriptors:
            desc.append(self.make_dbus_descriptor(d['uuid'], d['instance_id'], d['permissions']))
        char_uuid = utils.get_uuid_as_list(uuid)
        return {
            'uuid': GLib.Variant('ay', char_uuid),
            'instance_id': GLib.Variant('i', instance_id),
            'properties': GLib.Variant('i', properties),
            'permissions': GLib.Variant('i', permissions),
            'key_size': GLib.Variant('i', key_size),
            'write_type': GLib.Variant('u', write_type),
            'descriptors': GLib.Variant('aa{sv}', desc)
        }

    def make_dbus_service(self, service):
        """Makes struct for service D-Bus.

        Args:
            service: The struct of BluetoothGattService.

        Returns:
            Dictionary of service.
        """
        characteristics = []
        for c in service['characteristics']:
            characteristics.append(
                self.make_dbus_characteristic(c['uuid'], c['instance_id'], c['properties'], c['permissions'],
                                              c['key_size'], c['write_type'], c['descriptors']))

        included_services = []
        for s in service['included_services']:
            included_services.append(self.make_dbus_service(s))
        service_uuid = utils.get_uuid_as_list(service['uuid'])
        return {
            'uuid': GLib.Variant('ay', service_uuid),
            'instance_id': GLib.Variant('i', service['instance_id']),
            'service_type': GLib.Variant('i', service['service_type']),
            'characteristics': GLib.Variant('aa{sv}', characteristics),
            'included_services': GLib.Variant('aa{sv}', included_services)
        }
