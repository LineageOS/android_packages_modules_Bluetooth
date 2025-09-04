#!/usr/bin/env python3
# Copyright 2025 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may not
# use this file except in compliance with the License. You may obtain a copy of
# the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations under
# the License.
#
#
#
#
# This script extracts Hearing Aid audio data from btsnoop.
# Generates a valid audio file which can be played using player like smplayer.
#
# Audio File Name Format:
# [PEER_ADDRESS]-[START_TIMESTAMP]-[AUDIO_TYPE]-[SAMPLE_RATE].[CODEC]
#
# Player:
# smplayer
#
# NOTE:
# Please make sure you HCI Snoop data file includes the following frames:
# HearingAid "LE Enhanced Connection Complete", GATT write for Audio Control
# Point with "Start cmd", and the data frames.

from collections import defaultdict
import argparse
import os
import struct
import sys
import time

BTSNOOP_HEADER = b'btsnoop\x00\x00\x00\x00\x01\x00\x00\x03\xea'

COMMADN_PACKET = 1
ACL_PACKET = 2
SCO_PACKET = 3
EVENT_PACKET = 4
ISO_PACKET = 5

DATA = 0

SENT = 0
RECEIVED = 1

L2CAP_ATT_CID = 0x0004
L2CAP_SIGNAL = 0x0005

OPCODE_ATT_READ_BY_TYPE_RSP = 0x09
OPCODE_ATT_READ_REQ = 0x0A
OPCODE_ATT_READ_RSP = 0x0B
OPCODE_ATT_WRITE_REQ = 0x12

HCI_LE_ENANCED_CONNECTION_COMPLETE_V1 = 0x0A
HCI_LE_ENANCED_CONNECTION_COMPLETE_V2 = 0x29

OPCODE_HA_START = 0x01
OPCODE_HA_STOP = 0x02

AUDIO_CONTROL_POINT_UUID = "f0d4de7e4a88476c9d9f1937b0996cc0"
LE_PSM_OUT_UUID = "2d41033982b642aab34ee2e01df8cc1a"

L2CAP_LE_CREDIT_BASED_CONNECTION_REQ = 0x14
L2CAP_LE_CREDIT_BASED_CONNECTION_RSP = 0x15

# HCI event
EVENT_CODE_LE_META_EVENT = 0x3E

HCI_SUCCESS = 0x00

folder = None
full_debug = False
simple_debug = False
packet_number = 0

audio_control_attr_handle_manual_setting = 0xFF
psm_attr_handle_manual_setting = 0xFF
default_peer_address = ""
default_codec = ""
default_sample_rate = ""
default_audio_type = ""
default_timestamp = ""
default_start_state = False


class AudioStream:

    def __init__(self):
        self.peer_address = default_peer_address
        self.connection_handle = 0xFF
        self.audio_control_attr_handle = audio_control_attr_handle_manual_setting
        self.psm_attr_handle = psm_attr_handle_manual_setting
        self.psm = 0xFFFF
        self.cid = 0xFFFF
        self.start = default_start_state
        self.timestamp = default_timestamp
        self.codec = default_codec
        self.sample_rate = default_sample_rate
        self.audio_type = default_audio_type
        self.audio_data = []
        self.pending_psm_rsp = False
        self.pending_cid_rsp = False

    def dump(self):
        print("peer_address: " + self.peer_address)
        print("connection_handle: " + str(hex(self.connection_handle)))
        print("audio_control_attr_handle: " + str(hex(self.audio_control_attr_handle)))
        print("psm_attr_handle: " + str(self.psm_attr_handle))
        print("psm: " + str(self.psm))
        print("cid: " + str(self.cid))
        print("start:  " + str(self.start))
        print("timestamp:  " + self.timestamp)
        print("codec:  " + self.codec)
        print("sample_rate:  " + str(self.sample_rate))
        print("audio_type:  " + str(self.audio_type))


audio_stream = defaultdict(AudioStream)


def parse_ha_codec(connection_handle, codec):
    """This function parses HA audio control cmd codec and sample rate."""
    if codec == 0x01:
        codec_name = "G722"
        sample_rate = "16KHZ"
    elif codec == 0x02:
        codec_name = "G722"
        sample_rate = "24KHZ"
    else:
        codec = "Unknown"
        sample_rate = "Unknown"
    audio_stream[connection_handle].codec = codec_name
    audio_stream[connection_handle].sample_rate = sample_rate


def parse_ha_audio_type(connection_handle, audio_type):
    """This function parses HA audio control cmd audio type."""
    if audio_type == 0x01:
        audio_type = "Ringtone"
    elif audio_type == 0x02:
        audio_type = "Phonecall"
    elif audio_type == 0x03:
        audio_type = "Media"
    else:
        audio_type = "Unknown"

    audio_stream[connection_handle].audio_type = audio_type


def parse_ha_audio_data(packet, connection_handle):
    """This function extracts HA audio data."""
    packet = unpack_data(packet, 2, True)  #ignore sdu lengh
    packet = unpack_data(packet, 1, True)  #ignore sequence number

    if audio_stream[connection_handle].start != True:
        return

    audio_stream[connection_handle].audio_data.extend(list(packet))


def parse_att_read_by_type_rsp(packet, connection_handle):
    length, packet = unpack_data(packet, 1, False)
    if length != 21:
        #ignore the packet, we're only interested in this packet for the characteristic type UUID
        return

    if length > len(packet):
        debug_print("Invalid att packet length")
        return

    packet = unpack_data(packet, 2, True)  #ignore start attr_handle
    packet = unpack_data(packet, 1, True)  #ignore properties
    attribute_handle, packet = unpack_data(packet, 2, False)

    long_uuid_1, packet = unpack_data(packet, 8, False)
    long_uuid_2, packet = unpack_data(packet, 8, False)
    characteristic_uuid = str(hex(long_uuid_2))[2:] + str(hex(long_uuid_1))[2:]
    if characteristic_uuid == AUDIO_CONTROL_POINT_UUID:
        if simple_debug or full_debug:
            debug_print("Found AUDIO Control Point UUID with " +
                        audio_stream[connection_handle].peer_address)
        audio_stream[connection_handle].audio_control_attr_handle = attribute_handle
    elif characteristic_uuid == LE_PSM_OUT_UUID:
        if simple_debug or full_debug:
            debug_print("Found LE_PSM_OUT UUID with " +
                        audio_stream[connection_handle].peer_address)
        audio_stream[connection_handle].psm_attr_handle = attribute_handle


def parse_att_write_req(packet, connection_handle, timestamp):
    attr_handle, packet = unpack_data(packet, 2, False)
    if full_debug:
        debug_print("parse_att_write_req with attribute handle " + str(attr_handle))
    if audio_stream[connection_handle].audio_control_attr_handle != attr_handle:
        return
    opcode, packet = unpack_data(packet, 1, False)
    if opcode == OPCODE_HA_START:
        if simple_debug or full_debug:
            debug_print("OPCODE_HA_START")
        audio_stream[connection_handle].start = True
        audio_stream[connection_handle].timestamp = convert_time_str(timestamp)
        codec, packet = unpack_data(packet, 1, False)
        parse_ha_codec(connection_handle, codec)
        audio_type, packet = unpack_data(packet, 1, False)
        parse_ha_audio_type(connection_handle, audio_type)

    elif opcode == OPCODE_HA_STOP:
        if simple_debug or full_debug:
            debug_print("OPCODE_HA_STOP")
        dump_audio_data(connection_handle)


def parse_att_read_req(packet, connection_handle):
    attr_handle, packet = unpack_data(packet, 1, False)
    if full_debug:
        debug_print("parse_att_read_req with attribute handle " + str(attr_handle))
    if audio_stream[connection_handle].psm_attr_handle != attr_handle:
        return
    audio_stream[connection_handle].pending_psm_rsp = True


def parse_att_read_rsp(packet, connection_handle):
    if audio_stream[connection_handle].pending_psm_rsp == False:
        return

    psm, packet = unpack_data(packet, 2, False)
    audio_stream[connection_handle].psm = psm
    audio_stream[connection_handle].pending_psm_rsp = False


def parse_att_packet(packet, connection_handle, flags, timestamp):
    opcode, packet = unpack_data(packet, 1, False)
    packet_handle = {
        (OPCODE_ATT_READ_BY_TYPE_RSP, RECEIVED): (lambda x, y, z: parse_att_read_by_type_rsp(x, y)),
        (OPCODE_ATT_READ_REQ, SENT): (lambda x, y, z: parse_att_read_req(x, y)),
        (OPCODE_ATT_WRITE_REQ, SENT): (lambda x, y, z: parse_att_write_req(x, y, z)),
        (OPCODE_ATT_READ_RSP, RECEIVED): (lambda x, y, z: parse_att_read_rsp(x, y))
    }
    packet_handle.get((opcode, flags), lambda x, y, z: None)(packet, connection_handle, timestamp)


def parse_event_packet(packet):
    event_code, packet = unpack_data(packet, 1, False)
    if event_code != EVENT_CODE_LE_META_EVENT:
        return

    length, packet = unpack_data(packet, 1, False)
    if len(packet) != length:
        print("Invalid LE mata event length")
        return

    subevent_code, packet = unpack_data(packet, 1, False)
    if subevent_code != HCI_LE_ENANCED_CONNECTION_COMPLETE_V1 and subevent_code != HCI_LE_ENANCED_CONNECTION_COMPLETE_V2:
        return

    status, packet = unpack_data(packet, 1, False)
    if status != HCI_SUCCESS:
        return

    connection_handle, packet = unpack_data(packet, 2, False)
    connection_handle = connection_handle & 0x0FFF
    if connection_handle > 0x0EFF:
        debug_print("Invalid packet handle, skip")
        return

    if full_debug:
        debug_print("HCI_LE_ENANCED_CONNECTION_COMPLETE with connection handle " +
                    str(connection_handle))

    #ignore role and address type
    packet = unpack_data(packet, 2, True)
    address_back, packet = unpack_data(packet, 2, False)
    address_front, packet = unpack_data(packet, 4, False)
    peer_address = str(hex(address_front))[2:] + str(hex(address_back))[2:]
    audio_stream[connection_handle].peer_address = peer_address
    if audio_stream[connection_handle].connection_handle != 0xFF and audio_stream[
            connection_handle].start:
        if simple_debug or full_debug:
            debug_print("new connection")
        dump_audio_data(connection_handle)
    audio_stream[connection_handle].connection_handle = connection_handle


def parse_acl_packet(packet, flags, timestamp):
    # Check the minimum acl length, HCI leader (4 bytes)
    # + L2CAP header (4 bytes)
    if len(packet) < 8:
        debug_print("Invalid acl data length.")
        return

    connection_handle, packet = unpack_data(packet, 2, False)
    connection_handle = connection_handle & 0x0FFF
    if connection_handle > 0x0EFF:
        debug_print("Invalid packet handle, skip")
        return
    total_length, packet = unpack_data(packet, 2, False)
    if total_length != len(packet):
        debug_print("Invalid total length, skip")
        return
    pdu_length, packet = unpack_data(packet, 2, False)
    channel_id, packet = unpack_data(packet, 2, False)
    if pdu_length != len(packet):
        debug_print("Invalid pdu length, skip")
        return

    # Parse ATT protocol
    if channel_id == L2CAP_ATT_CID:
        parse_att_packet(packet, connection_handle, flags, timestamp)
    elif channel_id == L2CAP_SIGNAL:
        code, packet = unpack_data(packet, 1, False)
        packet = unpack_data(packet, 1, True)  #ignore identifier
        packet = unpack_data(packet, 2, True)  #ignore length
        if code == L2CAP_LE_CREDIT_BASED_CONNECTION_REQ:
            psm, packet = unpack_data(packet, 2, False)
            if psm == audio_stream[connection_handle].psm:
                audio_stream[connection_handle].pending_cid_rsp = True
                if simple_debug or full_debug:
                    debug_print("found PSM")
        elif code == L2CAP_LE_CREDIT_BASED_CONNECTION_RSP and audio_stream[
                connection_handle].pending_cid_rsp == True:
            cid, packet = unpack_data(packet, 2, False)
            audio_stream[connection_handle].cid = cid
            audio_stream[connection_handle].pending_cid_rsp = False
            if full_debug:
                debug_print("cid: " + str(hex(cid)))
    elif channel_id == audio_stream[connection_handle].cid:
        parse_ha_audio_data(packet, connection_handle)


def parse_next_packet(btsnoop_file):
    global packet_number
    packet_number += 1
    packet_header = btsnoop_file.read(25)
    if len(packet_header) != 25:
        debug_print("Invalid packet header length")
        return False

    (length_original, length_captured, flags, dropped_packets, timestamp,
     type) = struct.unpack(">IIIIqB", packet_header)

    if length_original != length_captured:
        debug_print("Filtered btnsoop, can not be parsed")
        return False

    packet = btsnoop_file.read(length_captured - 1)
    if len(packet) != length_original - 1:
        debug_print("Invalid packet length!")
        return False

    if dropped_packets:
        debug_print("Invalid droped value")
        return False

    packet_handle = {
        COMMADN_PACKET: (lambda x, y, z: None),
        ACL_PACKET: (lambda x, y, z: parse_acl_packet(x, y, z)),
        SCO_PACKET: (lambda x, y, z: None),
        EVENT_PACKET: (lambda x, y, z: parse_event_packet(x)),
        ISO_PACKET: (lambda x, y, z: None),
    }
    packet_handle.get(type, lambda x, y, z: None)(packet, flags, timestamp)
    return True


def dump_audio_data(connection_handle):
    file_name = ""
    if folder is not None:
        if not os.path.exists(folder):
            os.makedirs(folder)
        file_name = os.path.join(folder, file_name)

    file_name += audio_stream[connection_handle].peer_address
    file_name += "_" + audio_stream[connection_handle].timestamp
    file_name += "_" + audio_stream[connection_handle].audio_type
    file_name += "_" + audio_stream[connection_handle].sample_rate
    file_name += "." + audio_stream[connection_handle].codec

    if audio_stream[connection_handle].audio_data != []:
        if simple_debug or full_debug:
            debug_print("Dump_audio_data to " + file_name)
            audio_stream[connection_handle].dump()
        f = open(file_name, 'wb')
        arr = bytearray(audio_stream[connection_handle].audio_data)
        f.write(arr)
        f.close()
        audio_stream[connection_handle].start = False
        audio_stream[connection_handle].audio_data = []
        audio_stream[connection_handle].pending_psm_rsp = False
        audio_stream[connection_handle].pending_cid_rsp = False


def debug_print(log):
    global packet_number
    print("#" + str(packet_number) + ": " + log)


def unpack_data(data, byte, ignore):
    if ignore:
        return data[byte:]

    value = 0
    if byte == 1:
        value = struct.unpack("<B", data[:byte])[0]
    elif byte == 2:
        value = struct.unpack("<H", data[:byte])[0]
    elif byte == 4:
        value = struct.unpack("<I", data[:byte])[0]
    elif byte == 8:
        value = struct.unpack("<Q", data[:byte])[0]
    return value, data[byte:]


def convert_time_str(timestamp):
    """This function converts time to string format."""
    timestamp_sec = float(timestamp) / 1000000
    local_timestamp = time.localtime(timestamp_sec)
    ms = timestamp_sec - int(timestamp_sec)
    ms_str = "{0:06}".format(int(round(ms * 1000000)))

    str_format = time.strftime("%m_%d__%H_%M_%S", local_timestamp)
    full_str_format = str_format + "_" + ms_str

    return full_str_format


def set_config():
    """This function is for set config by flag and check the argv is correct."""
    argv_parser = argparse.ArgumentParser(
        description="Extracts Hearing Aid audio data from btsnoop.")
    argv_parser.add_argument("btsnoop", help="bluetooth btsnoop file.")
    argv_parser.add_argument("-f", "--folder", help="select output folder.", dest="folder")
    argv_parser.add_argument("-c1",
                             "--connection-handle1",
                             help="set a fake connection handle 1 to capture audio dump.",
                             dest="connection_handle1",
                             type=int)
    argv_parser.add_argument("-c2",
                             "--connection-handle2",
                             help="set a fake connection handle 2 to capture audio dump.",
                             dest="connection_handle2",
                             type=int)
    argv_parser.add_argument("-ns",
                             "--no-start",
                             help="No audio 'Start' cmd is needed before extracting audio data.",
                             dest="no_start",
                             action="store_true")
    argv_parser.add_argument("-dc",
                             "--default-codec",
                             help="set a default codec.",
                             dest="codec",
                             default="G722")
    argv_parser.add_argument("-a",
                             "--attr-handle",
                             help="force to select audio control attr handle.",
                             dest="audio_control_attr_handle",
                             type=int)
    argv_parser.add_argument("-p",
                             "--psm",
                             help="force to psm attr handle.",
                             dest="psm_attr_handle",
                             type=int)
    argv_parser.add_argument("-v",
                             "--verbose",
                             help="dump full debug buffer content.",
                             dest="full_debug",
                             action="store_true")
    argv_parser.add_argument("-d",
                             "--debug",
                             help="dump debug buffer header content.",
                             dest="simple_debug",
                             action="store_true")
    arg = argv_parser.parse_args()

    if arg.folder is not None:
        global folder
        folder = arg.folder

    if arg.no_start:
        global default_peer_address
        global default_start_state
        global default_codec
        global default_sample_rate
        global default_audio_type
        global default_timestamp
        default_peer_address = "no_name"
        default_start_state = True
        default_codec = arg.codec
        default_sample_rate = "Unknown"
        default_audio_type = "Unknown"
        default_timestamp = "Unknown"

    if arg.audio_control_attr_handle is not None:
        global audio_control_attr_handle_manual_setting
        audio_control_attr_handle_manual_setting = arg.audio_control_attr_handle

    if arg.psm_attr_handle is not None:
        global psm_attr_handle_manual_setting
        psm_attr_handle_manual_setting = arg.psm_attr_handle

    if arg.connection_handle1:
        audio_stream[arg.connection_handle1].peer_address = "ConnectionHandle_" + str(
            arg.connection_handle1)
        audio_stream[arg.connection_handle1].connection_handle = arg.connection_handle1

    if arg.connection_handle2:
        audio_stream[arg.connection_handle2].peer_address = "ConnectionHandle_" + str(
            arg.connection_handle2)
        audio_stream[arg.connection_handle2].connection_handle = arg.connection_handle2

    global full_debug
    global simple_debug
    if arg.full_debug:
        full_debug = True
    elif arg.simple_debug:
        simple_debug = True

    if os.path.isfile(arg.btsnoop):
        return arg.btsnoop
    else:
        argv_parser.error("btsnoop file not found: %s" % arg.btsnoop)
        exit(1)


def main():
    btsnoop_file_name = set_config()

    with open(btsnoop_file_name, "rb") as btsnoop_file:
        if btsnoop_file.read(16) != BTSNOOP_HEADER:
            print("Invalid btsnoop header")
            exit(1)

        while True:
            if not parse_next_packet(btsnoop_file):
                break

    for connection_handle in audio_stream.keys():
        dump_audio_data(connection_handle)


if __name__ == "__main__":
    main()
