from __future__ import annotations
import asyncio
import grpc
import grpc.aio
import logging
import struct
import sys

from bumble.device import Device
from google.protobuf import empty_pb2  # pytype: disable=pyi-error

from pandora.hid_grpc_aio import HIDServicer

from pandora_services import utils
from pandora.hid_pb2 import (
    ProtocolModeEvent,
    ReportEvent,
    PROTOCOL_REPORT_MODE,
    PROTOCOL_BOOT_MODE,
    PROTOCOL_UNSUPPORTED_MODE,
    SERVICE_TYPE_HID,
    SERVICE_TYPE_HOGP,
)

from bumble.core import (
    BT_BR_EDR_TRANSPORT,
    BT_L2CAP_PROTOCOL_ID,
    BT_HUMAN_INTERFACE_DEVICE_SERVICE,
    BT_HIDP_PROTOCOL_ID,
    UUID,
    ProtocolError,
)

from bumble.hci import (
    HCI_StatusError,
    HCI_CONNECTION_ALREADY_EXISTS_ERROR,
    HCI_PAGE_TIMEOUT_ERROR,
)
from bumble.hid import (
    Device as HID_Device,
    HID_CONTROL_PSM,
    HID_INTERRUPT_PSM,
    Message,
)

from bumble.sdp import (
    Client as SDP_Client,
    DataElement,
    ServiceAttribute,
    SDP_PUBLIC_BROWSE_ROOT,
    SDP_PROTOCOL_DESCRIPTOR_LIST_ATTRIBUTE_ID,
    SDP_SERVICE_CLASS_ID_LIST_ATTRIBUTE_ID,
    SDP_BLUETOOTH_PROFILE_DESCRIPTOR_LIST_ATTRIBUTE_ID,
    SDP_ALL_ATTRIBUTES_RANGE,
    SDP_LANGUAGE_BASE_ATTRIBUTE_ID_LIST_ATTRIBUTE_ID,
    SDP_ADDITIONAL_PROTOCOL_DESCRIPTOR_LIST_ATTRIBUTE_ID,
    SDP_SERVICE_RECORD_HANDLE_ATTRIBUTE_ID,
    SDP_BROWSE_GROUP_LIST_ATTRIBUTE_ID,
)
from bumble.utils import AsyncRunner

# -----------------------------------------------------------------------------
# SDP attributes for Bluetooth HID devices
SDP_HID_SERVICE_NAME_ATTRIBUTE_ID = 0x0100
SDP_HID_SERVICE_DESCRIPTION_ATTRIBUTE_ID = 0x0101
SDP_HID_PROVIDER_NAME_ATTRIBUTE_ID = 0x0102
SDP_HID_DEVICE_RELEASE_NUMBER_ATTRIBUTE_ID = 0x0200  # [DEPRECATED]
SDP_HID_PARSER_VERSION_ATTRIBUTE_ID = 0x0201
SDP_HID_DEVICE_SUBCLASS_ATTRIBUTE_ID = 0x0202
SDP_HID_COUNTRY_CODE_ATTRIBUTE_ID = 0x0203
SDP_HID_VIRTUAL_CABLE_ATTRIBUTE_ID = 0x0204
SDP_HID_RECONNECT_INITIATE_ATTRIBUTE_ID = 0x0205
SDP_HID_DESCRIPTOR_LIST_ATTRIBUTE_ID = 0x0206
SDP_HID_LANGID_BASE_LIST_ATTRIBUTE_ID = 0x0207
SDP_HID_SDP_DISABLE_ATTRIBUTE_ID = 0x0208  # [DEPRECATED]
SDP_HID_BATTERY_POWER_ATTRIBUTE_ID = 0x0209
SDP_HID_REMOTE_WAKE_ATTRIBUTE_ID = 0x020A
SDP_HID_PROFILE_VERSION_ATTRIBUTE_ID = 0x020B  # DEPRECATED]
SDP_HID_SUPERVISION_TIMEOUT_ATTRIBUTE_ID = 0x020C
SDP_HID_NORMALLY_CONNECTABLE_ATTRIBUTE_ID = 0x020D
SDP_HID_BOOT_DEVICE_ATTRIBUTE_ID = 0x020E
SDP_HID_SSR_HOST_MAX_LATENCY_ATTRIBUTE_ID = 0x020F
SDP_HID_SSR_HOST_MIN_TIMEOUT_ATTRIBUTE_ID = 0x0210

# Refer to HID profile specification v1.1.1, "5.3 Service Discovery Protocol (SDP)" for details
# HID SDP attribute values
LANGUAGE = 0x656E  # 0x656E uint16 “en” (English)
ENCODING = 0x6A  # 0x006A uint16 UTF-8 encoding
PRIMARY_LANGUAGE_BASE_ID = 0x100  # 0x0100 uint16 PrimaryLanguageBaseID
VERSION_NUMBER = 0x0101  # 0x0101 uint16 version number (v1.1)
SERVICE_NAME = b'Bumble HID'
SERVICE_DESCRIPTION = b'Bumble'
PROVIDER_NAME = b'Bumble'
HID_PARSER_VERSION = 0x0111  # uint16 0x0111 (v1.1.1)
HID_DEVICE_SUBCLASS = 0xC0  # Combo keyboard/pointing device
HID_COUNTRY_CODE = 0x21  # 0x21 Uint8, USA
HID_VIRTUAL_CABLE = True  # Virtual cable enabled
HID_RECONNECT_INITIATE = True  #  Reconnect initiate enabled
REPORT_DESCRIPTOR_TYPE = 0x22  # 0x22 Type = Report Descriptor
HID_LANGID_BASE_LANGUAGE = 0x0409  # 0x0409 Language = English (United States)
HID_LANGID_BASE_BLUETOOTH_STRING_OFFSET = 0x100  # 0x0100 Default
HID_BATTERY_POWER = True  #  Battery power enabled
HID_REMOTE_WAKE = True  #  Remote wake enabled
HID_SUPERVISION_TIMEOUT = 0xC80  # uint16 0xC80 (2s)
HID_NORMALLY_CONNECTABLE = True  #  Normally connectable enabled
HID_BOOT_DEVICE = True  #  Boot device support enabled
HID_SSR_HOST_MAX_LATENCY = 0x640  # uint16 0x640 (1s)
HID_SSR_HOST_MIN_TIMEOUT = 0xC80  # uint16 0xC80 (2s)
HID_REPORT_MAP = bytes(  # Text String, 50 Octet Report Descriptor
    # pylint: disable=line-too-long
    [
        0x05,
        0x01,  # Usage Page (Generic Desktop Ctrls)
        0x09,
        0x06,  # Usage (Keyboard)
        0xA1,
        0x01,  # Collection (Application)
        0x85,
        0x01,  # . Report ID (1)
        0x05,
        0x07,  # . Usage Page (Kbrd/Keypad)
        0x19,
        0xE0,  # . Usage Minimum (0xE0)
        0x29,
        0xE7,  # . Usage Maximum (0xE7)
        0x15,
        0x00,  # . Logical Minimum (0)
        0x25,
        0x01,  # . Logical Maximum (1)
        0x75,
        0x01,  # . Report Size (1)
        0x95,
        0x08,  # . Report Count (8)
        0x81,
        0x02,  # . Input (Data,Var,Abs,No Wrap,Linear,Preferred State,No Null Position)
        0x95,
        0x01,  # . Report Count (1)
        0x75,
        0x08,  # . Report Size (8)
        0x81,
        0x03,  # . Input (Const,Var,Abs,No Wrap,Linear,Preferred State,No Null Position)
        0x95,
        0x05,  # . Report Count (5)
        0x75,
        0x01,  # . Report Size (1)
        0x05,
        0x08,  # . Usage Page (LEDs)
        0x19,
        0x01,  # . Usage Minimum (Num Lock)
        0x29,
        0x05,  # . Usage Maximum (Kana)
        0x91,
        0x02,  # . Output (Data,Var,Abs,No Wrap,Linear,Preferred State,No Null Position,Non-volatile)
        0x95,
        0x01,  # . Report Count (1)
        0x75,
        0x03,  # . Report Size (3)
        0x91,
        0x03,  # . Output (Const,Var,Abs,No Wrap,Linear,Preferred State,No Null Position,Non-volatile)
        0x95,
        0x06,  # . Report Count (6)
        0x75,
        0x08,  # . Report Size (8)
        0x15,
        0x00,  # . Logical Minimum (0)
        0x25,
        0x65,  # . Logical Maximum (101)
        0x05,
        0x07,  # . Usage Page (Kbrd/Keypad)
        0x19,
        0x00,  # . Usage Minimum (0x00)
        0x29,
        0x65,  # . Usage Maximum (0x65)
        0x81,
        0x00,  # . Input (Data,Array,Abs,No Wrap,Linear,Preferred State,No Null Position)
        0xC0,  # End Collection
        0x05,
        0x01,  # Usage Page (Generic Desktop Ctrls)
        0x09,
        0x02,  # Usage (Mouse)
        0xA1,
        0x01,  # Collection (Application)
        0x85,
        0x02,  # . Report ID (2)
        0x09,
        0x01,  # . Usage (Pointer)
        0xA1,
        0x00,  # . Collection (Physical)
        0x05,
        0x09,  # .   Usage Page (Button)
        0x19,
        0x01,  # .   Usage Minimum (0x01)
        0x29,
        0x03,  # .   Usage Maximum (0x03)
        0x15,
        0x00,  # .   Logical Minimum (0)
        0x25,
        0x01,  # .   Logical Maximum (1)
        0x95,
        0x03,  # .   Report Count (3)
        0x75,
        0x01,  # .   Report Size (1)
        0x81,
        0x02,  # .   Input (Data,Var,Abs,No Wrap,Linear,Preferred State,No Null Position)
        0x95,
        0x01,  # .   Report Count (1)
        0x75,
        0x05,  # .   Report Size (5)
        0x81,
        0x03,  # .   Input (Const,Var,Abs,No Wrap,Linear,Preferred State,No Null Position)
        0x05,
        0x01,  # .   Usage Page (Generic Desktop Ctrls)
        0x09,
        0x30,  # .   Usage (X)
        0x09,
        0x31,  # .   Usage (Y)
        0x15,
        0x81,  # .   Logical Minimum (-127)
        0x25,
        0x7F,  # .   Logical Maximum (127)
        0x75,
        0x08,  # .   Report Size (8)
        0x95,
        0x02,  # .   Report Count (2)
        0x81,
        0x06,  # .   Input (Data,Var,Rel,No Wrap,Linear,Preferred State,No Null Position)
        0xC0,  # . End Collection (Physical)
        0xC0,  # End Collection (Application)
    ])

# Default protocol mode set to report protocol
protocol_mode = Message.ProtocolMode.REPORT_PROTOCOL

from bumble.core import AdvertisingData
from bumble.device import Device, Connection, Peer
from bumble.gatt import (
    Descriptor,
    Service,
    Characteristic,
    CharacteristicValue,
    GATT_DEVICE_INFORMATION_SERVICE,
    GATT_HUMAN_INTERFACE_DEVICE_SERVICE,
    GATT_BATTERY_SERVICE,
    GATT_BATTERY_LEVEL_CHARACTERISTIC,
    GATT_MANUFACTURER_NAME_STRING_CHARACTERISTIC,
    GATT_REPORT_CHARACTERISTIC,
    GATT_REPORT_MAP_CHARACTERISTIC,
    GATT_PROTOCOL_MODE_CHARACTERISTIC,
    GATT_HID_INFORMATION_CHARACTERISTIC,
    GATT_HID_CONTROL_POINT_CHARACTERISTIC,
    GATT_REPORT_REFERENCE_DESCRIPTOR,
)

# -----------------------------------------------------------------------------

# Protocol Modes (HID Specification V1.1.1 Section 2.1.2)
HID_BOOT_PROTOCOL = 0x00
HID_REPORT_PROTOCOL = 0x01

# Report Types (HID Specification V1.1.1 Section 2.1.1)
HID_INPUT_REPORT = 0x01
HID_OUTPUT_REPORT = 0x02
HID_FEATURE_REPORT = 0x03

# Report Map
HID_KEYBOARD_REPORT_MAP = bytes(
    # pylint: disable=line-too-long
    [
        0x05,
        0x01,  # Usage Page (Generic Desktop Controls)
        0x09,
        0x06,  # Usage (Keyboard)
        0xA1,
        0x01,  # Collection (Application)
        0x85,
        0x01,  # . Report ID (1)
        0x05,
        0x07,  # . Usage Page (Keyboard/Keypad)
        0x19,
        0xE0,  # . Usage Minimum (0xE0)
        0x29,
        0xE7,  # . Usage Maximum (0xE7)
        0x15,
        0x00,  # . Logical Minimum (0)
        0x25,
        0x01,  # . Logical Maximum (1)
        0x75,
        0x01,  # . Report Size (1)
        0x95,
        0x08,  # . Report Count (8)
        0x81,
        0x02,  # . Input (Data,Var,Abs,No Wrap,Linear,Preferred State,No Null Position)
        0x95,
        0x01,  # . Report Count (1)
        0x75,
        0x08,  # . Report Size (8)
        0x81,
        0x01,  # . Input (Const,Array,Abs,No Wrap,Linear,Preferred State,No Null Position)
        0x95,
        0x06,  # . Report Count (6)
        0x75,
        0x08,  # . Report Size (8)
        0x15,
        0x00,  # . Logical Minimum (0x00)
        0x25,
        0x94,  # . Logical Maximum (0x94)
        0x05,
        0x07,  # . Usage Page (Keyboard/Keypad)
        0x19,
        0x00,  # . Usage Minimum (0x00)
        0x29,
        0x94,  # . Usage Maximum (0x94)
        0x81,
        0x00,  # . Input (Data,Array,Abs,No Wrap,Linear,Preferred State,No Null Position)
        0x95,
        0x05,  # . Report Count (5)
        0x75,
        0x01,  # . Report Size (1)
        0x05,
        0x08,  # . Usage Page (LEDs)
        0x19,
        0x01,  # . Usage Minimum (Num Lock)
        0x29,
        0x05,  # . Usage Maximum (Kana)
        0x91,
        0x02,  # . Output (Data,Var,Abs,No Wrap,Linear,Preferred State,No Null Position,Non-volatile)
        0x95,
        0x01,  # . Report Count (1)
        0x75,
        0x03,  # . Report Size (3)
        0x91,
        0x01,  # . Output (Const,Array,Abs,No Wrap,Linear,Preferred State,No Null Position,Non-volatile)
        0xC0,  # End Collection
    ])


# -----------------------------------------------------------------------------
# pylint: disable=invalid-overridden-method
class ServerListener(Device.Listener, Connection.Listener):

    def __init__(self, device):
        self.device = device

    @AsyncRunner.run_in_task()
    async def on_connection(self, connection):
        logging.info(f'=== Connected to {connection}')
        connection.listener = self

    @AsyncRunner.run_in_task()
    async def on_disconnection(self, reason):
        logging.info(f'### Disconnected, reason={reason}')


# -----------------------------------------------------------------------------
def on_hid_control_point_write(_connection, value):
    logging.info(f'Control Point Write: {value}')


# -----------------------------------------------------------------------------
def sdp_records():
    service_record_handle = 0x00010006
    return {
        service_record_handle: [
            ServiceAttribute(
                SDP_SERVICE_RECORD_HANDLE_ATTRIBUTE_ID,
                DataElement.unsigned_integer_32(service_record_handle),
            ),
            ServiceAttribute(
                SDP_BROWSE_GROUP_LIST_ATTRIBUTE_ID,
                DataElement.sequence([DataElement.uuid(SDP_PUBLIC_BROWSE_ROOT)]),
            ),
            ServiceAttribute(
                SDP_SERVICE_CLASS_ID_LIST_ATTRIBUTE_ID,
                DataElement.sequence([DataElement.uuid(BT_HUMAN_INTERFACE_DEVICE_SERVICE)]),
            ),
            ServiceAttribute(
                SDP_PROTOCOL_DESCRIPTOR_LIST_ATTRIBUTE_ID,
                DataElement.sequence([
                    DataElement.sequence([
                        DataElement.uuid(BT_L2CAP_PROTOCOL_ID),
                        DataElement.unsigned_integer_16(HID_CONTROL_PSM),
                    ]),
                    DataElement.sequence([
                        DataElement.uuid(BT_HIDP_PROTOCOL_ID),
                    ]),
                ]),
            ),
            ServiceAttribute(
                SDP_LANGUAGE_BASE_ATTRIBUTE_ID_LIST_ATTRIBUTE_ID,
                DataElement.sequence([
                    DataElement.unsigned_integer_16(LANGUAGE),
                    DataElement.unsigned_integer_16(ENCODING),
                    DataElement.unsigned_integer_16(PRIMARY_LANGUAGE_BASE_ID),
                ]),
            ),
            ServiceAttribute(
                SDP_BLUETOOTH_PROFILE_DESCRIPTOR_LIST_ATTRIBUTE_ID,
                DataElement.sequence([
                    DataElement.sequence([
                        DataElement.uuid(BT_HUMAN_INTERFACE_DEVICE_SERVICE),
                        DataElement.unsigned_integer_16(VERSION_NUMBER),
                    ]),
                ]),
            ),
            ServiceAttribute(
                SDP_ADDITIONAL_PROTOCOL_DESCRIPTOR_LIST_ATTRIBUTE_ID,
                DataElement.sequence([
                    DataElement.sequence([
                        DataElement.sequence([
                            DataElement.uuid(BT_L2CAP_PROTOCOL_ID),
                            DataElement.unsigned_integer_16(HID_INTERRUPT_PSM),
                        ]),
                        DataElement.sequence([
                            DataElement.uuid(BT_HIDP_PROTOCOL_ID),
                        ]),
                    ]),
                ]),
            ),
            ServiceAttribute(
                SDP_HID_SERVICE_NAME_ATTRIBUTE_ID,
                DataElement(DataElement.TEXT_STRING, SERVICE_NAME),
            ),
            ServiceAttribute(
                SDP_HID_SERVICE_DESCRIPTION_ATTRIBUTE_ID,
                DataElement(DataElement.TEXT_STRING, SERVICE_DESCRIPTION),
            ),
            ServiceAttribute(
                SDP_HID_PROVIDER_NAME_ATTRIBUTE_ID,
                DataElement(DataElement.TEXT_STRING, PROVIDER_NAME),
            ),
            ServiceAttribute(
                SDP_HID_PARSER_VERSION_ATTRIBUTE_ID,
                DataElement.unsigned_integer_32(HID_PARSER_VERSION),
            ),
            ServiceAttribute(
                SDP_HID_DEVICE_SUBCLASS_ATTRIBUTE_ID,
                DataElement.unsigned_integer_32(HID_DEVICE_SUBCLASS),
            ),
            ServiceAttribute(
                SDP_HID_COUNTRY_CODE_ATTRIBUTE_ID,
                DataElement.unsigned_integer_32(HID_COUNTRY_CODE),
            ),
            ServiceAttribute(
                SDP_HID_VIRTUAL_CABLE_ATTRIBUTE_ID,
                DataElement.boolean(HID_VIRTUAL_CABLE),
            ),
            ServiceAttribute(
                SDP_HID_RECONNECT_INITIATE_ATTRIBUTE_ID,
                DataElement.boolean(HID_RECONNECT_INITIATE),
            ),
            ServiceAttribute(
                SDP_HID_DESCRIPTOR_LIST_ATTRIBUTE_ID,
                DataElement.sequence([
                    DataElement.sequence([
                        DataElement.unsigned_integer_16(REPORT_DESCRIPTOR_TYPE),
                        DataElement(DataElement.TEXT_STRING, HID_REPORT_MAP),
                    ]),
                ]),
            ),
            ServiceAttribute(
                SDP_HID_LANGID_BASE_LIST_ATTRIBUTE_ID,
                DataElement.sequence([
                    DataElement.sequence([
                        DataElement.unsigned_integer_16(HID_LANGID_BASE_LANGUAGE),
                        DataElement.unsigned_integer_16(HID_LANGID_BASE_BLUETOOTH_STRING_OFFSET),
                    ]),
                ]),
            ),
            ServiceAttribute(
                SDP_HID_BATTERY_POWER_ATTRIBUTE_ID,
                DataElement.boolean(HID_BATTERY_POWER),
            ),
            ServiceAttribute(
                SDP_HID_REMOTE_WAKE_ATTRIBUTE_ID,
                DataElement.boolean(HID_REMOTE_WAKE),
            ),
            ServiceAttribute(
                SDP_HID_SUPERVISION_TIMEOUT_ATTRIBUTE_ID,
                DataElement.unsigned_integer_16(HID_SUPERVISION_TIMEOUT),
            ),
            ServiceAttribute(
                SDP_HID_NORMALLY_CONNECTABLE_ATTRIBUTE_ID,
                DataElement.boolean(HID_NORMALLY_CONNECTABLE),
            ),
            ServiceAttribute(
                SDP_HID_BOOT_DEVICE_ATTRIBUTE_ID,
                DataElement.boolean(HID_BOOT_DEVICE),
            ),
            ServiceAttribute(
                SDP_HID_SSR_HOST_MAX_LATENCY_ATTRIBUTE_ID,
                DataElement.unsigned_integer_16(HID_SSR_HOST_MAX_LATENCY),
            ),
            ServiceAttribute(
                SDP_HID_SSR_HOST_MIN_TIMEOUT_ATTRIBUTE_ID,
                DataElement.unsigned_integer_16(HID_SSR_HOST_MIN_TIMEOUT),
            ),
        ]
    }


# -----------------------------------------------------------------------------
def hogp_device(device):
    # Create an 'input report' characteristic to send keyboard reports to the host
    input_report_kb_characteristic = Characteristic(
        GATT_REPORT_CHARACTERISTIC,
        Characteristic.Properties.READ | Characteristic.Properties.WRITE |
        Characteristic.Properties.NOTIFY,
        Characteristic.READABLE | Characteristic.WRITEABLE,
        bytes([0, 0, 0, 0, 0, 0, 0, 0, 0]),
        [
            Descriptor(
                GATT_REPORT_REFERENCE_DESCRIPTOR,
                Descriptor.READABLE,
                bytes([0x01, HID_INPUT_REPORT]),
            )
        ],
    )
    # Create an 'input report' characteristic to send mouse reports to the host
    input_report_mouse_characteristic = Characteristic(
        GATT_REPORT_CHARACTERISTIC,
        Characteristic.Properties.READ | Characteristic.Properties.WRITE |
        Characteristic.Properties.NOTIFY,
        Characteristic.READABLE | Characteristic.WRITEABLE,
        bytes([0, 0, 0, 0]),
        [
            Descriptor(
                GATT_REPORT_REFERENCE_DESCRIPTOR,
                Descriptor.READABLE,
                bytes([0x02, HID_INPUT_REPORT]),
            )
        ],
    )
    # Create an 'output report' characteristic to receive keyboard reports from the host
    output_report_characteristic = Characteristic(
        GATT_REPORT_CHARACTERISTIC,
        Characteristic.Properties.READ | Characteristic.Properties.WRITE |
        Characteristic.WRITE_WITHOUT_RESPONSE,
        Characteristic.READABLE | Characteristic.WRITEABLE,
        bytes([0]),
        [
            Descriptor(
                GATT_REPORT_REFERENCE_DESCRIPTOR,
                Descriptor.READABLE,
                bytes([0x01, HID_OUTPUT_REPORT]),
            )
        ],
    )

    # Add the services to the GATT sever
    device.add_services([
        Service(
            GATT_DEVICE_INFORMATION_SERVICE,
            [
                Characteristic(
                    GATT_MANUFACTURER_NAME_STRING_CHARACTERISTIC,
                    Characteristic.Properties.READ,
                    Characteristic.READABLE,
                    'Bumble',
                )
            ],
        ),
        Service(
            GATT_HUMAN_INTERFACE_DEVICE_SERVICE,
            [
                Characteristic(
                    GATT_PROTOCOL_MODE_CHARACTERISTIC,
                    Characteristic.Properties.READ,
                    Characteristic.READABLE,
                    bytes([HID_REPORT_PROTOCOL]),
                ),
                Characteristic(
                    GATT_HID_INFORMATION_CHARACTERISTIC,
                    Characteristic.Properties.READ,
                    Characteristic.READABLE,
                    # bcdHID=1.1, bCountryCode=0x00,
                    # Flags=RemoteWake|NormallyConnectable
                    bytes([0x11, 0x01, 0x00, 0x03]),
                ),
                Characteristic(
                    GATT_HID_CONTROL_POINT_CHARACTERISTIC,
                    Characteristic.WRITE_WITHOUT_RESPONSE,
                    Characteristic.WRITEABLE,
                    CharacteristicValue(write=on_hid_control_point_write),
                ),
                Characteristic(
                    GATT_REPORT_MAP_CHARACTERISTIC,
                    Characteristic.Properties.READ,
                    Characteristic.READABLE,
                    HID_KEYBOARD_REPORT_MAP,
                ),
                input_report_kb_characteristic,
                input_report_mouse_characteristic,
                output_report_characteristic,
            ],
        ),
        Service(
            GATT_BATTERY_SERVICE,
            [
                Characteristic(
                    GATT_BATTERY_LEVEL_CHARACTERISTIC,
                    Characteristic.Properties.READ,
                    Characteristic.READABLE,
                    bytes([100]),
                )
            ],
        ),
    ])

    # Debug print
    for attribute in device.gatt_server.attributes:
        logging.info(attribute)

    # Set the advertising data
    device.advertising_data = bytes(
        AdvertisingData([
            (
                AdvertisingData.COMPLETE_LOCAL_NAME,
                bytes('Bumble Keyboard', 'utf-8'),
            ),
            (
                AdvertisingData.INCOMPLETE_LIST_OF_16_BIT_SERVICE_CLASS_UUIDS,
                bytes(GATT_HUMAN_INTERFACE_DEVICE_SERVICE),
            ),
            (AdvertisingData.APPEARANCE, struct.pack('<H', 0x03C1)),
            (AdvertisingData.FLAGS, bytes([0x05])),
        ]))

    # Attach a listener
    device.listener = ServerListener(device)


async def handle_virtual_cable_unplug():
    hid_host_bd_addr = str(hid_device.remote_device_bd_address)
    await hid_device.disconnect_interrupt_channel()
    await hid_device.disconnect_control_channel()
    await hid_device.device.keystore.delete(hid_host_bd_addr)  # type: ignore
    connection = hid_device.connection
    if connection is not None:
        await connection.disconnect()


def on_get_report_cb(report_id: int, report_type: int, buffer_size: int):
    retValue = hid_device.GetSetStatus()
    logging.info("GET_REPORT report_id: " + str(report_id) + "report_type: " + str(report_type) +
                 "buffer_size:" + str(buffer_size))
    if report_type == Message.ReportType.INPUT_REPORT:
        if report_id == 1:
            retValue.data = bytearray([0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00])
            retValue.status = hid_device.GetSetReturn.SUCCESS
        elif report_id == 2:
            retValue.data = bytearray([0x02, 0x00, 0x00, 0x00])
            retValue.status = hid_device.GetSetReturn.SUCCESS
        else:
            retValue.status = hid_device.GetSetReturn.REPORT_ID_NOT_FOUND

    return retValue


def on_set_report_cb(report_id: int, report_type: int, report_size: int, data: bytes):
    retValue = hid_device.GetSetStatus()
    logging.info("SET_REPORT report_id: " + str(report_id) + "report_type: " + str(report_type) +
                 "report_size " + str(report_size) + "data:" + str(data))

    report = ReportEvent()
    report.report_type = report_type
    report.report_id = report_id
    report.report_data = str(data.hex())

    if hid_report_queue:
        hid_report_queue.put_nowait(report)

    if report_type == Message.ReportType.FEATURE_REPORT:
        retValue.status = hid_device.GetSetReturn.ERR_INVALID_PARAMETER
    elif report_type == Message.ReportType.INPUT_REPORT:
        if report_id == 1 and report_size != 9:
            retValue.status = hid_device.GetSetReturn.ERR_INVALID_PARAMETER
        elif report_id == 2 and report_size != 4:
            retValue.status = hid_device.GetSetReturn.ERR_INVALID_PARAMETER
        elif report_id == 3:
            retValue.status = hid_device.GetSetReturn.REPORT_ID_NOT_FOUND
        else:
            retValue.status = hid_device.GetSetReturn.SUCCESS
    else:
        retValue.status = hid_device.GetSetReturn.SUCCESS

    return retValue


def on_get_protocol_cb():
    retValue = hid_device.GetSetStatus()
    retValue.data = protocol_mode.to_bytes(length=1, byteorder=sys.byteorder)
    retValue.status = hid_device.GetSetReturn.SUCCESS
    return retValue


def on_set_protocol_cb(protocol: int):
    retValue = hid_device.GetSetStatus()
    # We do not support SET_PROTOCOL.
    logging.info(f"SET_PROTOCOL mode: {protocol}")
    mode = ProtocolModeEvent()
    if protocol == PROTOCOL_REPORT_MODE:
        mode.protocol_mode = PROTOCOL_REPORT_MODE
    elif protocol == PROTOCOL_BOOT_MODE:
        mode.protocol_mode = PROTOCOL_BOOT_MODE
    else:
        mode.protocol_mode = PROTOCOL_UNSUPPORTED_MODE
    hid_protoMode_queue.put_nowait(mode)
    retValue.status = hid_device.GetSetReturn.ERR_UNSUPPORTED_REQUEST
    return retValue


def on_virtual_cable_unplug_cb():
    logging.info('Received Virtual Cable Unplug')
    asyncio.create_task(handle_virtual_cable_unplug())


hid_protoMode_queue = None
hid_report_queue = None
hid_device = None


def register_hid(self) -> None:
    self.device.sdp_service_records.update(sdp_records())
    global hid_device
    hid_device = HID_Device(self.device)
    # Register for  call backs
    hid_device.register_get_report_cb(on_get_report_cb)
    hid_device.register_set_report_cb(on_set_report_cb)
    hid_device.register_get_protocol_cb(on_get_protocol_cb)
    hid_device.register_set_protocol_cb(on_set_protocol_cb)
    # Register for virtual cable unplug call back
    hid_device.on('virtual_cable_unplug', on_virtual_cable_unplug_cb)


# This class implements the Hid Pandora interface.
class HIDService(HIDServicer):

    hid_device = None

    def __init__(self, device: Device) -> None:
        super().__init__()
        self.device = device
        self.event_queue: Optional[asyncio.Queue[ProtocolModeEvent]] = None

    @utils.rpc
    async def RegisterService(self, request: empty_pb2.Empty,
                              context: grpc.ServicerContext) -> empty_pb2.Empty:

        if request.service_type == SERVICE_TYPE_HID:
            logging.info(f'Registering HID')
            register_hid(self)
        elif request.service_type == SERVICE_TYPE_HOGP:
            logging.info(f'Registering HOGP')
            hogp_device(self.device)
        else:
            logging.info(f'Registering both HID and HOGP')
            register_hid(self)
            hogp_device(self.device)

        return empty_pb2.Empty()

    @utils.rpc
    async def ConnectHost(self, request: empty_pb2.Empty,
                          context: grpc.ServicerContext) -> empty_pb2.Empty:

        logging.info(f'ConnectHost')
        try:
            hid_host_bd_addr = str(hid_device.remote_device_bd_address)
            connection = await self.device.connect(hid_host_bd_addr, transport=BT_BR_EDR_TRANSPORT)
            await connection.authenticate()
            await connection.encrypt()
            await hid_device.connect_control_channel()
            await hid_device.connect_interrupt_channel()
        except AttributeError as e:
            logging.error(f'Device does not exist')
            raise e
        except (HCI_StatusError, ProtocolError) as e:
            logging.error(f"Connection failure error: {e}")
            raise e

        return empty_pb2.Empty()

    @utils.rpc
    async def DisconnectHost(self, request: empty_pb2.Empty,
                             context: grpc.ServicerContext) -> empty_pb2.Empty:

        logging.info(f'DisconnectHost')
        try:
            await hid_device.disconnect_interrupt_channel()
            await hid_device.disconnect_control_channel()
            connection = hid_device.connection
            if connection is not None:
                await connection.disconnect()
            else:
                logging.info(f'Already disconnected from Hid Host')
        except AttributeError as e:
            logging.error(f'Device does not exist')
            raise e

        return empty_pb2.Empty()

    @utils.rpc
    async def VirtualCableUnplugHost(self, request: empty_pb2.Empty,
                                     context: grpc.ServicerContext) -> empty_pb2.Empty:

        logging.info(f'VirtualCableUnplugHost')
        try:
            hid_device.virtual_cable_unplug()
            try:
                hid_host_bd_addr = str(hid_device.remote_device_bd_address)
                await hid_device.device.keystore.delete(hid_host_bd_addr)
            except KeyError:
                logging.error(f'Device not found or Device already unpaired.')
                raise
        except AttributeError as e:
            logging.exception(f'Device does not exist')
            raise e
        return empty_pb2.Empty()

    @utils.rpc
    async def OnSetProtocolMode(
            self, request: empty_pb2.Empty,
            context: grpc.ServicerContext) -> AsyncGenerator[ProtocolModeEvent, None]:
        logging.info(f'OnSetProtocolMode')

        if self.event_queue is not None:
            raise RuntimeError('already streaming OnSetProtocolMode events')

        self.event_queue = asyncio.Queue()
        global hid_protoMode_queue
        hid_protoMode_queue = self.event_queue

        try:
            while event := await hid_protoMode_queue.get():
                yield event

        finally:
            self.event_queue = None
            hid_protoMode_queue = None

    @utils.rpc
    async def OnSetReport(self, request: empty_pb2.Empty,
                          context: grpc.ServicerContext) -> AsyncGenerator[ReportEvent, None]:
        logging.info(f'OnSetReport')

        if self.event_queue is not None:
            raise RuntimeError('already streaming OnSetReport events')

        self.event_queue = asyncio.Queue()
        global hid_report_queue
        hid_report_queue = self.event_queue

        try:
            while event := await hid_report_queue.get():
                yield event

        finally:
            self.event_queue = None
            hid_report_queue = None
