#!/usr/bin/env python3
#
#   Copyright 2020 - The Android Open Source Project
#
#   Licensed under the Apache License, Version 2.0 (the "License");
#   you may not use this file except in compliance with the License.
#   You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an "AS IS" BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#   limitations under the License.

import logging
import sys

from blueberry.utils import bluetooth
import hci_packets as hci


class HciMatchers(object):

    @staticmethod
    def CommandComplete(opcode):
        return lambda msg: HciMatchers._is_matching_command_complete(msg.payload, opcode)

    @staticmethod
    def ExtractMatchingCommandComplete(packet_bytes, opcode=None):
        return HciMatchers._extract_matching_command_complete(packet_bytes, opcode)

    @staticmethod
    def _is_matching_command_complete(packet_bytes, opcode=None):
        return HciMatchers._extract_matching_command_complete(packet_bytes, opcode) is not None

    @staticmethod
    def _extract_matching_command_complete(packet_bytes, opcode=None):
        event = HciMatchers._extract_matching_event(packet_bytes, hci.EventCode.COMMAND_COMPLETE)
        if not isinstance(event, hci.CommandComplete):
            return None
        if opcode and event.command_op_code != opcode:
            return None
        return event

    @staticmethod
    def CommandStatus(opcode=None):
        return lambda msg: HciMatchers._is_matching_command_status(msg.payload, opcode)

    @staticmethod
    def ExtractMatchingCommandStatus(packet_bytes, opcode=None):
        return HciMatchers._extract_matching_command_status(packet_bytes, opcode)

    @staticmethod
    def _is_matching_command_status(packet_bytes, opcode=None):
        return HciMatchers._extract_matching_command_status(packet_bytes, opcode) is not None

    @staticmethod
    def _extract_matching_command_status(packet_bytes, opcode=None):
        event = HciMatchers._extract_matching_event(packet_bytes, hci.EventCode.COMMAND_STATUS)
        if not isinstance(event, hci.CommandStatus):
            return None
        if opcode and event.command_op_code != opcode:
            return None
        return event

    @staticmethod
    def EventWithCode(event_code):
        return lambda msg: HciMatchers._is_matching_event(msg.payload, event_code)

    @staticmethod
    def ExtractEventWithCode(packet_bytes, event_code):
        return HciMatchers._extract_matching_event(packet_bytes, event_code)

    @staticmethod
    def _is_matching_event(packet_bytes, event_code):
        return HciMatchers._extract_matching_event(packet_bytes, event_code) is not None

    @staticmethod
    def _extract_matching_event(packet_bytes, event_code):
        try:
            event = hci.Event.parse_all(packet_bytes)
            return event if event.event_code == event_code else None
        except Exception as exn:
            print(sys.stderr, f"Failed to parse incoming event: {exn}")
            print(sys.stderr, f"Event data: {' '.join([f'{b:02x}' for b in packet_bytes])}")
            return None

    @staticmethod
    def LeEventWithCode(subevent_code):
        return lambda msg: HciMatchers._extract_matching_le_event(msg.payload, subevent_code) is not None

    @staticmethod
    def ExtractLeEventWithCode(packet_bytes, subevent_code):
        return HciMatchers._extract_matching_le_event(packet_bytes, subevent_code)

    @staticmethod
    def _extract_matching_le_event(packet_bytes, subevent_code):
        event = HciMatchers._extract_matching_event(packet_bytes, hci.EventCode.LE_META_EVENT)
        if (not isinstance(event, hci.LeMetaEvent) or event.subevent_code != subevent_code):
            return None

        return event

    @staticmethod
    def LeAdvertisement(subevent_code=hci.SubeventCode.EXTENDED_ADVERTISING_REPORT, address=None, data=None):
        return lambda msg: HciMatchers._extract_matching_le_advertisement(msg.payload, subevent_code, address, data
                                                                         ) is not None

    @staticmethod
    def ExtractLeAdvertisement(packet_bytes,
                               subevent_code=hci.SubeventCode.EXTENDED_ADVERTISING_REPORT,
                               address=None,
                               data=None):
        return HciMatchers._extract_matching_le_advertisement(packet_bytes, subevent_code, address, data)

    @staticmethod
    def _extract_matching_le_advertisement(packet_bytes,
                                           subevent_code=hci.SubeventCode.EXTENDED_ADVERTISING_REPORT,
                                           address=None,
                                           data=None):
        event = HciMatchers._extract_matching_le_event(packet_bytes, subevent_code)
        if event is None:
            return None

        matched = False
        for response in event.responses:
            matched |= (address == None or response.address == bluetooth.Address(address)) and (data == None or
                                                                                                data in packet_bytes)

        return event if matched else None

    @staticmethod
    def LeConnectionComplete():
        return lambda msg: HciMatchers._extract_le_connection_complete(msg.payload) is not None

    @staticmethod
    def ExtractLeConnectionComplete(packet_bytes):
        return HciMatchers._extract_le_connection_complete(packet_bytes)

    @staticmethod
    def _extract_le_connection_complete(packet_bytes):
        event = HciMatchers._extract_matching_le_event(packet_bytes, hci.SubeventCode.CONNECTION_COMPLETE)
        if event is not None:
            return event

        return HciMatchers._extract_matching_le_event(packet_bytes, hci.SubeventCode.ENHANCED_CONNECTION_COMPLETE)

    @staticmethod
    def LogEventCode():
        return lambda event: logging.info("Received event: %x" % hci.Event.parse(event.payload).event_code)

    @staticmethod
    def LinkKeyRequest():
        return HciMatchers.EventWithCode(hci.EventCode.LINK_KEY_REQUEST)

    @staticmethod
    def IoCapabilityRequest():
        return HciMatchers.EventWithCode(hci.EventCode.IO_CAPABILITY_REQUEST)

    @staticmethod
    def IoCapabilityResponse():
        return HciMatchers.EventWithCode(hci.EventCode.IO_CAPABILITY_RESPONSE)

    @staticmethod
    def UserPasskeyNotification():
        return HciMatchers.EventWithCode(hci.EventCode.USER_PASSKEY_NOTIFICATION)

    @staticmethod
    def UserPasskeyRequest():
        return HciMatchers.EventWithCode(hci.EventCode.USER_PASSKEY_REQUEST)

    @staticmethod
    def UserConfirmationRequest():
        return HciMatchers.EventWithCode(hci.EventCode.USER_CONFIRMATION_REQUEST)

    @staticmethod
    def LinkKeyNotification():
        return HciMatchers.EventWithCode(hci.EventCode.LINK_KEY_NOTIFICATION)

    @staticmethod
    def SimplePairingComplete():
        return HciMatchers.EventWithCode(hci.EventCode.SIMPLE_PAIRING_COMPLETE)

    @staticmethod
    def Disconnect():
        return HciMatchers.EventWithCode(hci.EventCode.DISCONNECT)

    @staticmethod
    def DisconnectionComplete():
        return HciMatchers.EventWithCode(hci.EventCode.DISCONNECTION_COMPLETE)

    @staticmethod
    def RemoteOobDataRequest():
        return HciMatchers.EventWithCode(hci.EventCode.REMOTE_OOB_DATA_REQUEST)

    @staticmethod
    def PinCodeRequest():
        return HciMatchers.EventWithCode(hci.EventCode.PIN_CODE_REQUEST)

    @staticmethod
    def LoopbackOf(packet):
        return HciMatchers.Exactly(hci.LoopbackCommand(payload=packet))

    @staticmethod
    def Exactly(packet):
        data = bytes(packet.serialize())
        return lambda event: data == event.payload


class AdvertisingMatchers(object):

    @staticmethod
    def AdvertisingCallbackMsg(type, advertiser_id=None, status=None, data=None):
        return lambda event: True if event.message_type == type and (advertiser_id == None or advertiser_id == event.advertiser_id) \
            and (status == None or status == event.status) and (data == None or data == event.data) else False

    @staticmethod
    def AddressMsg(type, advertiser_id=None, address=None):
        return lambda event: True if event.message_type == type and (advertiser_id == None or advertiser_id == event.advertiser_id) \
            and (address == None or address == event.address) else False


class ScanningMatchers(object):

    @staticmethod
    def ScanningCallbackMsg(type, status=None, data=None):
        return lambda event: True if event.message_type == type and (status == None or status == event.status) \
            and (data == None or data == event.data) else False


class NeighborMatchers(object):

    @staticmethod
    def InquiryResult(address):
        return lambda msg: NeighborMatchers._is_matching_inquiry_result(msg.packet, address)

    @staticmethod
    def _is_matching_inquiry_result(packet, address):
        event = HciMatchers.ExtractEventWithCode(packet, hci.EventCode.INQUIRY_RESULT)
        if not isinstance(event, hci.InquiryResult):
            return False
        return any((bluetooth.Address(address) == response.bd_addr for response in event.responses))

    @staticmethod
    def InquiryResultwithRssi(address):
        return lambda msg: NeighborMatchers._is_matching_inquiry_result_with_rssi(msg.packet, address)

    @staticmethod
    def _is_matching_inquiry_result_with_rssi(packet, address):
        event = HciMatchers.ExtractEventWithCode(packet, hci.EventCode.INQUIRY_RESULT_WITH_RSSI)
        if not isinstance(event, hci.InquiryResultWithRssi):
            return False
        return any((bluetooth.Address(address) == response.address for response in event.responses))

    @staticmethod
    def ExtendedInquiryResult(address):
        return lambda msg: NeighborMatchers._is_matching_extended_inquiry_result(msg.packet, address)

    @staticmethod
    def _is_matching_extended_inquiry_result(packet, address):
        event = HciMatchers.ExtractEventWithCode(packet, hci.EventCode.EXTENDED_INQUIRY_RESULT)
        if not isinstance(event, (hci.ExtendedInquiryResult, hci.ExtendedInquiryResultRaw)):
            return False
        return bluetooth.Address(address) == event.address


class SecurityMatchers(object):

    @staticmethod
    def UiMsg(type, address=None):
        return lambda event: True if event.message_type == type and (address == None or address == event.peer
                                                                    ) else False

    @staticmethod
    def BondMsg(type, address=None, reason=None):
        return lambda event: True if event.message_type == type and (address == None or address == event.peer) and (
            reason == None or reason == event.reason) else False

    @staticmethod
    def HelperMsg(type, address=None):
        return lambda event: True if event.message_type == type and (address == None or address == event.peer
                                                                    ) else False


class IsoMatchers(object):

    @staticmethod
    def Data(payload):
        return lambda packet: packet.payload == payload

    @staticmethod
    def PacketPayloadWithMatchingCisHandle(cis_handle):
        return lambda packet: None if cis_handle != packet.handle else packet
