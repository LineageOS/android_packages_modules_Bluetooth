# Copyright 2024 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""
Bumble tests for SignalingChannel implementation.

Create venv and upgrade pip:

    python -m venv .venv
    source .venv/bin/activate
    python -m pip install --upgrade pip

Install the required dependencies using pip:

    pip install pyee pytest bumble

Run the tests:
    python /path/signaling_channel_test.py
"""

# -----------------------------------------------------------------------------
# Imports
# -----------------------------------------------------------------------------
import asyncio
import logging
import os
import pytest
import bumble.avdtp as avdtp
from bumble.a2dp import (A2DP_SBC_CODEC_TYPE, SbcMediaCodecInformation)
from bumble.controller import Controller
from bumble.core import BT_BR_EDR_TRANSPORT
from bumble.device import Device
from bumble.host import Host
from bumble.link import LocalLink
from bumble.transport.common import AsyncPipeSink
from .packets import avdtp as av
from .signaling_channel import SignalingChannel
from unittest.mock import ANY

# -----------------------------------------------------------------------------
# Logging
# -----------------------------------------------------------------------------
logger = logging.getLogger(__name__)


# -----------------------------------------------------------------------------
class TwoDevices:

    def __init__(self):
        self.connections = [None, None]

        addresses = ['F0:F1:F2:F3:F4:F5', 'F5:F4:F3:F2:F1:F0']
        self.link = LocalLink()
        self.controllers = [
            Controller('C1', link=self.link, public_address=addresses[0]),
            Controller('C2', link=self.link, public_address=addresses[1]),
        ]
        self.devices = [
            Device(
                address=addresses[0],
                host=Host(self.controllers[0], AsyncPipeSink(self.controllers[0])),
            ),
            Device(
                address=addresses[1],
                host=Host(self.controllers[1], AsyncPipeSink(self.controllers[1])),
            ),
        ]

        self.paired = [None, None]

    def on_connection(self, which, connection):
        self.connections[which] = connection

    def on_paired(self, which, keys):
        self.paired[which] = keys


# -----------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_self_connection():
    # Create two devices, each with a controller, attached to the same link
    two_devices = TwoDevices()

    # Attach listeners
    two_devices.devices[0].on('connection',
                              lambda connection: two_devices.on_connection(0, connection))
    two_devices.devices[1].on('connection',
                              lambda connection: two_devices.on_connection(1, connection))

    # Enable Classic connections
    two_devices.devices[0].classic_enabled = True
    two_devices.devices[1].classic_enabled = True

    # Start
    await two_devices.devices[0].power_on()
    await two_devices.devices[1].power_on()

    # Connect the two devices
    await asyncio.gather(
        two_devices.devices[0].connect(two_devices.devices[1].public_address,
                                       transport=BT_BR_EDR_TRANSPORT),
        two_devices.devices[1].accept(two_devices.devices[0].public_address),
    )

    # Check the post conditions
    assert two_devices.connections[0] is not None
    assert two_devices.connections[1] is not None


# -----------------------------------------------------------------------------
def sink_codec_capabilities():
    return avdtp.MediaCodecCapabilities(
        media_type=avdtp.AVDTP_AUDIO_MEDIA_TYPE,
        media_codec_type=A2DP_SBC_CODEC_TYPE,
        media_codec_information=SbcMediaCodecInformation.from_bytes(bytes([255, 255, 2, 53])),
    )


# -----------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_signaling_channel_as_source():
    two_devices = TwoDevices()
    # Enable Classic connections
    two_devices.devices[0].classic_enabled = True
    two_devices.devices[1].classic_enabled = True
    await two_devices.devices[0].power_on()
    await two_devices.devices[1].power_on()

    def on_rtp_packet(packet):
        rtp_packets.append(packet)
        if len(rtp_packets) == rtp_packets_expected:
            rtp_packets_fully_received.set_result(None)

    device_1_avdt_sink = None
    avdtp_future = asyncio.get_running_loop().create_future()

    def on_avdtp_connection(server):
        logger.info("AVDTP Opened")
        nonlocal device_1_avdt_sink
        device_1_avdt_sink = server.add_sink(sink_codec_capabilities())
        device_1_avdt_sink.on('rtp_packet', on_rtp_packet)
        nonlocal avdtp_future
        avdtp_future.set_result(None)

    # Create a listener to wait for AVDTP connections
    listener = avdtp.Listener.for_device(two_devices.devices[1])
    listener.on('connection', on_avdtp_connection)

    async def make_connection():
        connections = await asyncio.gather(
            two_devices.devices[0].connect(two_devices.devices[1].public_address,
                                           BT_BR_EDR_TRANSPORT),
            two_devices.devices[1].accept(two_devices.devices[0].public_address),
        )
        return connections[0]

    connection = await make_connection()

    channel_int = await SignalingChannel.initiate(connection)

    channel_int.send_signal(av.DiscoverCommand())

    result = await channel_int.expect_signal(
        av.DiscoverResponse(transaction_label=ANY,
                            seid_information=[av.SeidInformation(acp_seid=1, tsep=av.Tsep.SINK)]))

    acp_seid = result.seid_information[0].acp_seid

    channel_int.send_signal(av.GetAllCapabilitiesCommand(acp_seid=acp_seid))

    result = await channel_int.expect_signal(
        av.GetAllCapabilitiesResponse(
            transaction_label=ANY,
            service_capabilities=[
                av.MediaTransportCapability(),
                av.MediaCodecCapability(service_category=av.ServiceCategory.MEDIA_CODEC,
                                        media_codec_specific_information_elements=[255, 255, 2, 53])
            ]))

    channel_int.send_signal(
        av.SetConfigurationCommand(acp_seid=acp_seid,
                                   service_capabilities=[result.service_capabilities[0]]))

    await channel_int.expect_signal(av.SetConfigurationResponse(transaction_label=ANY))

    channel_int.send_signal(av.OpenCommand(acp_seid=acp_seid))

    await channel_int.expect_signal(av.OpenResponse(transaction_label=ANY))

    await asyncio.wait_for(avdtp_future, timeout=10.0)

    assert device_1_avdt_sink.in_use == 1
    assert device_1_avdt_sink.stream is not None
    assert device_1_avdt_sink.stream.state == avdtp.AVDTP_OPEN_STATE

    async def generate_packets(packet_count):
        sequence_number = 0
        timestamp = 0
        for i in range(packet_count):
            payload = bytes([sequence_number % 256])
            packet = avdtp.MediaPacket(2, 0, 0, 0, sequence_number, timestamp, 0, [], 96, payload)
            packet.timestamp_seconds = timestamp / 44100
            timestamp += 10
            sequence_number += 1
            yield packet

    # # Send packets using a pump object
    rtp_packets_fully_received = asyncio.get_running_loop().create_future()
    rtp_packets_expected = 3
    rtp_packets = []
    pump = avdtp.MediaPacketPump(generate_packets(3))

    await channel_int.initiate_transport_channel()

    channel_int.send_signal(av.StartCommand(acp_seid=acp_seid))

    await channel_int.expect_signal(av.StartResponse(transaction_label=ANY))

    assert device_1_avdt_sink.in_use == 1
    assert device_1_avdt_sink.stream is not None
    assert device_1_avdt_sink.stream.state == avdtp.AVDTP_STREAMING_STATE

    await pump.start(channel_int.transport_channel)

    await rtp_packets_fully_received

    await pump.stop()

    channel_int.send_signal(av.CloseCommand(acp_seid=acp_seid))

    await channel_int.expect_signal(av.CloseResponse(transaction_label=ANY))

    await channel_int.disconnect_transport_channel()

    assert device_1_avdt_sink.in_use == 0
    assert device_1_avdt_sink.stream.state == avdtp.AVDTP_IDLE_STATE


# -----------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_signaling_channel_as_sink():
    two_devices = TwoDevices()
    # Enable Classic connections
    two_devices.devices[0].classic_enabled = True
    two_devices.devices[1].classic_enabled = True
    await two_devices.devices[0].power_on()
    await two_devices.devices[1].power_on()

    dev_0_dev_1_conn, dev_1_dev_0_conn = await asyncio.gather(
        two_devices.devices[0].connect(two_devices.devices[1].public_address, BT_BR_EDR_TRANSPORT),
        two_devices.devices[1].accept(two_devices.devices[0].public_address),
    )

    channel_acp = SignalingChannel.accept(dev_1_dev_0_conn)

    avdtp_future = asyncio.get_running_loop().create_future()

    def on_avdtp_connection():
        logger.info(f" AVDTP Opened")
        nonlocal avdtp_future
        avdtp_future.set_result(None)

    channel_acp.on('connection', on_avdtp_connection)

    channel_int = await SignalingChannel.initiate(dev_0_dev_1_conn)

    channel_int.send_signal(av.DiscoverCommand())

    cmd = await channel_acp.expect_signal(av.DiscoverCommand(transaction_label=ANY))

    seid_information = [
        av.SeidInformation(tsep=av.Tsep.SINK, media_type=avdtp.AVDTP_AUDIO_MEDIA_TYPE)
    ]

    channel_acp.send_signal(
        av.DiscoverResponse(transaction_label=cmd.transaction_label,
                            seid_information=seid_information))

    result = await channel_int.expect_signal(
        av.DiscoverResponse(seid_information=[
            av.SeidInformation(
                acp_seid=0x0, tsep=av.Tsep.SINK, media_type=avdtp.AVDTP_AUDIO_MEDIA_TYPE)
        ]))

    int_to_acp_seid = result.seid_information[0].acp_seid

    channel_int.send_signal(av.GetAllCapabilitiesCommand(acp_seid=int_to_acp_seid))

    cmd = await channel_acp.expect_signal(
        av.GetAllCapabilitiesCommand(acp_seid=int_to_acp_seid, transaction_label=ANY))

    acceptor_service_capabilities = [
        av.MediaTransportCapability(),
        av.MediaCodecCapability(service_category=av.ServiceCategory.MEDIA_CODEC,
                                media_codec_specific_information_elements=[255, 255, 2, 53])
    ]

    channel_acp.send_signal(
        av.GetAllCapabilitiesResponse(transaction_label=cmd.transaction_label,
                                      service_capabilities=acceptor_service_capabilities))

    result = await channel_int.expect_signal(
        av.GetAllCapabilitiesResponse(
            transaction_label=ANY,
            service_capabilities=[
                av.MediaTransportCapability(),
                av.MediaCodecCapability(service_category=av.ServiceCategory.MEDIA_CODEC,
                                        media_codec_specific_information_elements=[255, 255, 2, 53])
            ]))

    channel_int.send_signal(
        av.SetConfigurationCommand(acp_seid=int_to_acp_seid,
                                   service_capabilities=[result.service_capabilities[0]]))

    cmd = await channel_acp.expect_signal(
        av.SetConfigurationCommand(transaction_label=ANY,
                                   acp_seid=int_to_acp_seid,
                                   service_capabilities=[result.service_capabilities[0]]))

    channel_acp.send_signal(av.SetConfigurationResponse(transaction_label=cmd.transaction_label))

    await channel_int.expect_signal(av.SetConfigurationResponse(transaction_label=ANY))

    channel_int.send_signal(av.OpenCommand(acp_seid=int_to_acp_seid))

    cmd = await channel_acp.expect_signal(
        av.OpenCommand(transaction_label=ANY, acp_seid=int_to_acp_seid))

    channel_acp.send_signal(av.OpenResponse(transaction_label=cmd.transaction_label))

    await channel_int.expect_signal(av.OpenResponse(transaction_label=ANY))

    await asyncio.wait_for(avdtp_future, timeout=10.0)

    rtp_packets_expected = 3
    received_rtp_packets = []
    source_packets = [
        avdtp.MediaPacket(2, 0, 0, 0, i, i * 10, 0, [], 96, bytes([i]))
        for i in range(rtp_packets_expected)
    ]

    await channel_int.initiate_transport_channel()

    channel_int.send_signal(av.StartCommand(acp_seid=int_to_acp_seid))

    cmd = await channel_acp.expect_signal(
        av.StartCommand(transaction_label=ANY, acp_seid=int_to_acp_seid))

    channel_acp.send_signal(av.StartResponse(transaction_label=cmd.transaction_label))

    await channel_int.expect_signal(av.StartResponse(transaction_label=ANY))

    channel_int.send_media(bytes(source_packets[0]))
    channel_int.send_media(bytes(source_packets[1]))
    channel_int.send_media(bytes(source_packets[2]))

    for _ in range(rtp_packets_expected):
        received_rtp_packets.append(await channel_acp.expect_media())
    assert channel_acp.transport_queue.empty()

    channel_int.send_signal(av.CloseCommand(acp_seid=int_to_acp_seid))

    cmd = await channel_acp.expect_signal(
        av.CloseCommand(transaction_label=ANY, acp_seid=int_to_acp_seid))

    channel_acp.send_signal(av.CloseResponse(transaction_label=cmd.transaction_label))

    await channel_int.expect_signal(av.CloseResponse(transaction_label=ANY))

    await channel_int.disconnect_transport_channel()
    await channel_int.disconnect()


# -----------------------------------------------------------------------------
async def run_test_self():
    await test_self_connection()
    await test_signaling_channel_as_source()
    await test_signaling_channel_as_sink()


# -----------------------------------------------------------------------------
if __name__ == '__main__':
    logging.basicConfig(level=os.environ.get('BUMBLE_LOGLEVEL', 'DEBUG').upper())
    asyncio.run(run_test_self())
