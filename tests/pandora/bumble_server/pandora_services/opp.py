# Copyright 2025 Google LLC
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

from bumble.core import (
    BT_OBEX_OBJECT_PUSH_SERVICE,
    BT_L2CAP_PROTOCOL_ID,
    BT_RFCOMM_PROTOCOL_ID,
    BT_OBEX_PROTOCOL_ID,
)
from bumble.device import Device
from bumble.l2cap import ClassicChannelSpec
from pandora_services import utils
from bumble.rfcomm import Server
from bumble.sdp import (
    DataElement,
    ServiceAttribute,
    SDP_PUBLIC_BROWSE_ROOT,
    SDP_PROTOCOL_DESCRIPTOR_LIST_ATTRIBUTE_ID,
    SDP_SERVICE_CLASS_ID_LIST_ATTRIBUTE_ID,
    SDP_BLUETOOTH_PROFILE_DESCRIPTOR_LIST_ATTRIBUTE_ID,
    SDP_SERVICE_RECORD_HANDLE_ATTRIBUTE_ID,
    SDP_BROWSE_GROUP_LIST_ATTRIBUTE_ID,
)
from google.protobuf.empty_pb2 import Empty
import grpc
import logging
from pandora.opp_grpc_aio import OppServicer
from pandora.opp_pb2 import AcceptPutOperationResponse

OBEX_RFCOMM_CHANNEL = 0x07

SUPPORTED_FORMAT_VCARD_2_1 = 0x01
SUPPORTED_FORMAT_VCARD_3_0 = 0x02
SUPPORTED_FORMAT_VCAL_1_0 = 0x03
SUPPORTED_FORMAT_VCAL_2_0 = 0x04
SUPPORTED_FORMAT_VNOTE = 0x05
SUPPORTED_FORMAT_VMESSAGE = 0x06
SUPPORTED_FORMAT_ANY = 0xFF

BT_OBEX_OBJECT_PUSH_SERVICE_VERSION = 0x0102

OPP_GOEM_L2CAP_PSM_ATTRIBUTE_ID = 0x0200
OPP_SUPPORTED_FORMATS_LIST_ATTRIBUTE_ID = 0x0303

# See Bluetooth spec @ Vol 3, Part B - 5.1.1. ServiceRecordHandle attribute
SDP_SERVICE_RECORD_HANDLE_NON_RESERVED_START = 0x00010000
SDP_SERVICE_RECORD_HANDLE_NON_RESERVED_END = 0xFFFFFFFF


def find_free_sdp_record_handle(device, start=SDP_SERVICE_RECORD_HANDLE_NON_RESERVED_START):
    for candidate_handle in range(start, SDP_SERVICE_RECORD_HANDLE_NON_RESERVED_END):
        if candidate_handle not in device.sdp_service_records:
            return candidate_handle
    raise RuntimeError("No available sdp record handles!")


# See Bluetooth Object Push Profile Spec v1.1 - 6.1 SDP Service Records
def sdp_records(device, l2cap_psm, rfcomm_channel):
    service_record_handle = find_free_sdp_record_handle(device)
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
                DataElement.sequence([DataElement.uuid(BT_OBEX_OBJECT_PUSH_SERVICE)]),
            ),
            ServiceAttribute(
                SDP_PROTOCOL_DESCRIPTOR_LIST_ATTRIBUTE_ID,
                DataElement.sequence([
                    DataElement.sequence([
                        DataElement.uuid(BT_L2CAP_PROTOCOL_ID),
                    ]),
                    DataElement.sequence([
                        DataElement.uuid(BT_RFCOMM_PROTOCOL_ID),
                        DataElement.unsigned_integer_8(rfcomm_channel)
                    ]),
                    DataElement.sequence([
                        DataElement.uuid(BT_OBEX_PROTOCOL_ID),
                    ]),
                ]),
            ),
            ServiceAttribute(
                SDP_BLUETOOTH_PROFILE_DESCRIPTOR_LIST_ATTRIBUTE_ID,
                DataElement.sequence([
                    DataElement.sequence([
                        DataElement.uuid(BT_OBEX_OBJECT_PUSH_SERVICE),
                        DataElement.unsigned_integer_16(BT_OBEX_OBJECT_PUSH_SERVICE_VERSION),
                    ]),
                ]),
            ),
            ServiceAttribute(
                OPP_SUPPORTED_FORMATS_LIST_ATTRIBUTE_ID,
                DataElement.sequence([
                    DataElement.unsigned_integer_8(SUPPORTED_FORMAT_VCARD_2_1),
                    DataElement.unsigned_integer_8(SUPPORTED_FORMAT_VCARD_3_0),
                    DataElement.unsigned_integer_8(SUPPORTED_FORMAT_VCAL_1_0),
                    DataElement.unsigned_integer_8(SUPPORTED_FORMAT_VCAL_2_0),
                    DataElement.unsigned_integer_8(SUPPORTED_FORMAT_VNOTE),
                    DataElement.unsigned_integer_8(SUPPORTED_FORMAT_VMESSAGE),
                    DataElement.unsigned_integer_8(SUPPORTED_FORMAT_ANY),
                ]),
            ),
            ServiceAttribute(
                OPP_GOEM_L2CAP_PSM_ATTRIBUTE_ID,
                DataElement.unsigned_integer_16(l2cap_psm),
            ),
        ]
    }


# This class implements the Opp Pandora interface.
class OppService(OppServicer):

    def __init__(self, device: Device, rfcomm_server: Server) -> None:
        self.device = device
        self.l2cap_server = self.device.create_l2cap_server(ClassicChannelSpec())
        self.rfcomm_server = rfcomm_server
        self.log = utils.BumbleServerLoggerAdapter(logging.getLogger(), {
            'service_name': 'opp',
            'device': device
        })
        self.setup_channel_and_sdp_records()

    def acceptor(self, dlc) -> None:
        dlc.sink = self.rx_bytes

    def rx_bytes(self, bytes):
        self.log.debug(f"Received bytes")

    def setup_channel_and_sdp_records(self):
        rfcomm_channel = self.rfcomm_server.listen(acceptor=self.acceptor)
        self.device.sdp_service_records.update(
            sdp_records(self.device, self.l2cap_server.psm, rfcomm_channel))

    @utils.rpc
    async def AcceptPutOperation(self, request: Empty,
                                 context: grpc.ServicerContext) -> AcceptPutOperationResponse:
        self.log.info(f"AcceptPutOperation")
        return AcceptPutOperationResponse()
