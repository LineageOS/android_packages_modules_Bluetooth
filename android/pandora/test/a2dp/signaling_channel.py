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

# -----------------------------------------------------------------------------
# Imports
# -----------------------------------------------------------------------------
from __future__ import annotations

from bumble.device import Connection
try:
    from packets import avdtp as av
    from packets.avdtp import *
except ImportError:
    from .packets import avdtp as av
    from .packets.avdtp import *
from pyee import EventEmitter
from typing import List, Literal, Union

import asyncio
import bumble.avdtp as avdtp
import bumble.l2cap as l2cap
import logging

# -----------------------------------------------------------------------------
# Logging
# -----------------------------------------------------------------------------
logger = logging.getLogger(__name__)

av.print = lambda *args, **kwargs: logger.debug(" ".join(map(str, args)))


class Any:
    """Helper class that will match all other values.
       Use an element of this class in expected packets to match any value
      returned by the AVDTP signaling."""

    def __eq__(self, other) -> bool:
        return True

    def __format__(self, format_spec: str) -> str:
        return "_"

    def __len__(self) -> int:
        return 1

    def show(self, prefix: str = "") -> str:
        return prefix + "_"


RoleType = Optional[Literal["acceptor", "initiator"]]


class SignalingChannel(EventEmitter):
    connection: Connection
    signaling_channel: Optional[l2cap.ClassicChannel] = None
    transport_channel: Optional[l2cap.ClassicChannel] = None
    avdtp_server: Optional[l2cap.ClassicChannelServer] = None
    role: RoleType = None
    any: Any = Any()
    acp_seid: int = 0
    int_seid: int = 0

    def __init__(self, connection: Connection):
        super().__init__()
        self.connection = connection
        self.signaling_queue = asyncio.Queue()
        self.transport_queue = asyncio.Queue()

    @classmethod
    async def initiate(cls, connection: Connection) -> SignalingChannel:
        channel = cls(connection)
        await channel._initiate_signaling_channel()
        return channel

    @classmethod
    def accept(cls, connection: Connection) -> SignalingChannel:
        channel = cls(connection)
        channel._accept_signaling_channel()
        return channel

    async def disconnect(self):
        if not self.signaling_channel:
            raise ValueError("No connected signaling channel")
        await self.signaling_channel.disconnect()
        self.signaling_channel = None

    async def initiate_transport_channel(self):
        if self.transport_channel:
            raise ValueError("RTP L2CAP channel already exists")
        self.transport_channel = await self.connection.create_l2cap_channel(
            l2cap.ClassicChannelSpec(psm=avdtp.AVDTP_PSM))

    async def disconnect_transport_channel(self):
        if not self.transport_channel:
            raise ValueError("No connected RTP channel")
        await self.transport_channel.disconnect()
        self.transport_channel = None

    async def expect_signal(self, expected_sig: Union[SignalingPacket, type], timeout: float = 3) -> SignalingPacket:
        packet = await asyncio.wait_for(self.signaling_queue.get(), timeout=timeout)
        sig = SignalingPacket.parse_all(packet)

        if isinstance(expected_sig, type) and not isinstance(sig, expected_sig):
            logger.error("Received unexpected signal")
            logger.error(f"Expected signal: {expected_sig.__class__.__name__}")
            logger.error("Received signal:")
            sig.show()
            raise ValueError(f"Received unexpected signal")

        if isinstance(expected_sig, SignalingPacket) and sig != expected_sig:
            logger.error("Received unexpected signal")
            logger.error("Expected signal:")
            expected_sig.show()
            logger.error("Received signal:")
            sig.show()
            raise ValueError(f"Received unexpected signal")

        logger.debug(f"<<< {self.connection.self_address} {self.role} received signal: <<<")
        sig.show()
        return sig

    async def expect_media(self, timeout: float = 3.0) -> bytes:
        packet = await asyncio.wait_for(self.transport_queue.get(), timeout=timeout)
        logger.debug(f"<<< {self.connection.self_address} {self.role} received media <<<")
        logger.debug(f"RTP Packet: {packet.hex()}")
        return packet

    def send_signal(self, packet: SignalingPacket):
        logger.debug(f">>> {self.connection.self_address} {self.role} sending signal: >>>")
        packet.show()
        self.signaling_channel.send_pdu(packet.serialize())

    def send_media(self, packet: bytes):
        logger.debug(f">>> {self.connection.self_address} {self.role} sending media >>>")
        self.transport_channel.send_pdu(packet)

    async def _initiate_signaling_channel(self):
        if self.signaling_channel:
            raise ValueError("Signaling L2CAP channel already exists")
        self.role = "initiator"
        self.signaling_channel = await self.connection.create_l2cap_channel(spec=l2cap.ClassicChannelSpec(
            psm=avdtp.AVDTP_PSM))
        # Register to receive PDUs from the channel
        self.signaling_channel.sink = self._on_pdu

    def _accept_signaling_channel(self):
        if self.avdtp_server:
            raise ValueError("L2CAP server already exists")
        self.role = "acceptor"
        avdtp_server = self.connection.device.l2cap_channel_manager.servers.get(avdtp.AVDTP_PSM)
        if not avdtp_server:
            self.avdtp_server = self.connection.device.create_l2cap_server(spec=l2cap.ClassicChannelSpec(
                psm=avdtp.AVDTP_PSM))
        else:
            self.avdtp_server = avdtp_server
        self.avdtp_server.on('connection', self._on_l2cap_connection)

    def _on_l2cap_connection(self, channel: l2cap.ClassicChannel):
        logger.info(f"Incoming L2CAP channel: {channel}")

        if not self.signaling_channel:

            def _on_channel_open():
                logger.info(f"Signaling opened on channel {self.signaling_channel}")
                # Register to receive PDUs from the channel
                self.signaling_channel.sink = self._on_pdu
                self.emit('connection')

            def _on_channel_close():
                logger.info("Signaling channel closed")
                self.signaling_channel = None

            self.signaling_channel = channel
            self.signaling_channel.on('open', _on_channel_open)
            self.signaling_channel.on('close', _on_channel_close)
        elif not self.transport_channel:

            def _on_channel_open():
                logger.info(f"RTP opened on channel {self.transport_channel}")
                # Register to receive PDUs from the channel
                self.transport_channel.sink = self._on_avdtp_packet

            def _on_channel_close():
                logger.info('RTP channel closed')
                self.transport_channel = None

            self.transport_channel = channel
            self.transport_channel.on('open', _on_channel_open)
            self.transport_channel.on('close', _on_channel_close)

    def _on_pdu(self, pdu: bytes):
        self.signaling_queue.put_nowait(pdu)

    def _on_avdtp_packet(self, packet):
        self.transport_queue.put_nowait(packet)

    async def accept_discover(self, seid_information: List[av.SeidInformation]):
        cmd = await self.expect_signal(av.DiscoverCommand(transaction_label=self.any))
        self.send_signal(av.DiscoverResponse(transaction_label=cmd.transaction_label,
                                             seid_information=seid_information))

    async def accept_get_all_capabilities(self, service_capabilities: List[ServiceCapability]):
        cmd = await self.expect_signal(av.GetAllCapabilitiesCommand(acp_seid=self.any, transaction_label=self.any))
        self.send_signal(
            av.GetAllCapabilitiesResponse(transaction_label=cmd.transaction_label,
                                          service_capabilities=service_capabilities))

    async def accept_set_configuration(self, expected_configuration: List[ServiceCapability]):
        cmd = await self.expect_signal(
            av.SetConfigurationCommand(transaction_label=self.any,
                                       acp_seid=self.any,
                                       int_seid=self.any,
                                       service_capabilities=expected_configuration))
        self.acp_seid = cmd.acp_seid
        self.int_seid = cmd.int_seid
        self.send_signal(SetConfigurationResponse(transaction_label=cmd.transaction_label))

    async def accept_open(self, timeout: float = 3.0):
        cmd = await self.expect_signal(av.OpenCommand(transaction_label=self.any, acp_seid=self.any), timeout=timeout)
        self.send_signal(av.OpenResponse(transaction_label=cmd.transaction_label))

    async def accept_start(self, timeout: float = 3.0):
        cmd = await self.expect_signal(av.StartCommand(transaction_label=self.any, acp_seid=self.any), timeout=timeout)
        self.send_signal(av.StartResponse(transaction_label=cmd.transaction_label))

    async def accept_suspend(self, timeout: float = 3.0):
        cmd = await self.expect_signal(av.SuspendCommand(transaction_label=self.any, acp_seid=self.any),
                                       timeout=timeout)
        self.send_signal(av.SuspendResponse(transaction_label=cmd.transaction_label))

    async def accept_close(self, timeout: float = 3.0):
        cmd = await self.expect_signal(av.CloseCommand(transaction_label=self.any, acp_seid=self.any), timeout=timeout)
        self.send_signal(av.CloseResponse(transaction_label=cmd.transaction_label))

    async def accept_open_stream(self,
                                 seid_information: List[av.SeidInformation],
                                 service_capabilities: List[ServiceCapability],
                                 timeout: float = 10.0):
        avdtp_future = asyncio.get_running_loop().create_future()

        def on_avdtp_connection():
            logger.info(f"AVDTP Opened")
            nonlocal avdtp_future
            avdtp_future.set_result(None)

        self.on('connection', on_avdtp_connection)

        expected_configuration: List[ServiceCapability] = []
        for capability in service_capabilities:
            if isinstance(capability, av.MediaTransportCapability) or isinstance(capability,
                                                                                 av.DelayReportingCapability):
                expected_configuration.append(capability)
            else:
                expected_configuration.append(self.any)

        await self.accept_discover(seid_information)
        await self.accept_get_all_capabilities(service_capabilities)
        await self.accept_set_configuration(expected_configuration)
        await self.accept_open()

        await asyncio.wait_for(avdtp_future, timeout=timeout)

    async def initiate_delay_report(self, delay_ms: int = 100, timeout: float = 3.0):
        delay_one_tenth = delay_ms * 10
        delay_msb = (delay_one_tenth >> 8) & 0xff
        delay_lsb = delay_one_tenth & 0xff
        self.send_signal(
            av.DelayReportCommand(transaction_label=0x01,
                                  acp_seid=self.acp_seid,
                                  delay_msb=delay_msb,
                                  delay_lsb=delay_lsb))
        await self.expect_signal(av.DelayReportResponse(transaction_label=self.any), timeout=timeout)
