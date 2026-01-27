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

from .packets import avdtp as av
import pyee
import typing
from unittest.mock import ANY
from typing import Optional, TypeVar

import asyncio
import bumble.device
from bumble import avdtp
from bumble import l2cap
import logging
import os
import time
from pandora_services import utils

# -----------------------------------------------------------------------------
# Logging
# -----------------------------------------------------------------------------
logger = logging.getLogger(__name__)

setattr(av, "print", lambda *args, **kwargs: logger.debug(" ".join(map(str, args))))

RoleType = Optional[typing.Literal["acceptor", "initiator"]]


class SignalingChannel(pyee.EventEmitter):
    connection: bumble.device.Connection
    signaling_channel: Optional[l2cap.ClassicChannel] = None
    transport_channel: Optional[l2cap.ClassicChannel] = None
    avdtp_server: Optional[l2cap.ClassicChannelServer] = None
    role: RoleType = None
    acp_seid: int = 0
    int_seid: int = 0
    signaling_channel_opened_future: asyncio.Future[None] | None = None
    transport_channel_opened_future: asyncio.Future[None] | None = None

    def __init__(self, connection: bumble.device.Connection):
        super().__init__()
        self.connection = connection
        self.signaling_queue = asyncio.Queue[bytes]()
        self.transport_queue = asyncio.Queue[bytes]()
        self.once('connection', self._on_avdtp_connection)

    def __str__(self):
        return (
            f"SignalingChannel(\n"
            f"  Connection: {self.connection},\n"
            f"  role: {self.role},\n"
            f"  acp_seid: {self.acp_seid},\n"
            f"  int_seid: {self.int_seid},\n"
            f"  waiting for channel to open: {self.signaling_channel_opened_future is not None},\n"
            f"  waiting for transport channel to open: {self.transport_channel_opened_future is not None}\n"
            f")")

    @classmethod
    async def initiate(cls, connection: bumble.device.Connection) -> SignalingChannel:
        channel = cls(connection)
        await channel._initiate_signaling_channel()
        return channel

    @classmethod
    def accept(cls, connection: bumble.device.Connection) -> SignalingChannel:
        channel = cls(connection)
        channel._accept_signaling_channel()
        return channel

    def _on_avdtp_connection(self) -> None:
        logger.info("AVDT signaling channel opened")
        assert self.signaling_channel_opened_future
        self.signaling_channel_opened_future.set_result(None)

    async def wait_signaling_channel_connected(self, timeout: float = 5):
        if (self.role != "acceptor"):
            raise ValueError("wait_signaling_channel_connected failed. role is not acceptor")

        if self.signaling_channel:
            logger.debug("wait_signaling_channel_connected: signalin channel already opened")
            return

        if self.signaling_channel_opened_future == None:
            self.signaling_channel_opened_future = asyncio.get_running_loop().create_future()
        logger.debug("wait_signaling_channel_connected: future gathered")

        try:
            await asyncio.wait_for(self.signaling_channel_opened_future, timeout=timeout)
            logger.debug("wait_signaling_channel_connected: future cleanup")
            self.signaling_channel_opened_future = None
        except TimeoutError:
            raise TimeoutError(
                "TimeoutError while waiting for AVDT signaling channel to open") from None

    async def wait_transport_channel_connected(self, timeout: float = 8.0):
        if (self.role != "acceptor"):
            raise ValueError("wait_transport_channel_connected failed. role is not acceptor")

        if self.transport_channel:
            logger.debug("wait_transport_channel_connected: transport channel already opened")
            return

        if self.transport_channel_opened_future == None:
            self.transport_channel_opened_future = asyncio.get_running_loop().create_future()
        logger.debug("wait_transport_channel_connected: future gathered")

        try:
            await asyncio.wait_for(self.transport_channel_opened_future, timeout=timeout)
            logger.debug("wait_transport_channel_connected: future cleanup")
            self.transport_channel_opened_future = None
        except TimeoutError:
            raise TimeoutError(
                "TimeoutError while waiting for AVDT transport channel to open") from None

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

        def _on_channel_close():
            logger.info('RTP channel closed')
            self.transport_channel = None

        # Register to receive PDUs from the channel
        self.transport_channel.sink = self._on_avdtp_packet
        self.transport_channel.on('close', _on_channel_close)

    async def disconnect_transport_channel(self):
        if not self.transport_channel:
            raise ValueError("No connected RTP channel")
        await self.transport_channel.disconnect()
        self.transport_channel = None

    _SIG = TypeVar('_SIG', bound=av.SignalingPacket)

    async def expect_signal(self, expected_sig: _SIG | type[_SIG], timeout: float = 3) -> _SIG:
        if not self.signaling_channel:
            if (self.role != "acceptor"):
                raise AttributeError("Signaling channel is None")

            try:
                logger.info("Waiting for transport channel")
                await self.wait_signaling_channel_connected()
            except TimeoutError:
                raise AttributeError("Signaling channel is None")

        try:
            packet = await asyncio.wait_for(self.signaling_queue.get(), timeout=timeout)
            sig = av.SignalingPacket.parse_all(packet)
            assert isinstance(sig, av.SignalingPacket)
        except TimeoutError:
            raise TimeoutError(
                f"TimeoutError while waiting for signal: {expected_sig.__class__.__name__}"
            ) from None

        if isinstance(expected_sig, type) and not isinstance(sig, expected_sig):
            logger.error("Received unexpected signal")
            logger.error(f"Expected signal: {expected_sig.__class__.__name__}")
            logger.error("Received signal:")
            sig.show()
            raise ValueError(f"Received unexpected signal")

        if isinstance(expected_sig, av.SignalingPacket) and sig != expected_sig:
            logger.error("Received unexpected signal")
            logger.error(f"Expected signal: {expected_sig.__class__.__name__}")
            logger.error("Received signal:")
            sig.show()
            raise ValueError(f"Received unexpected signal")

        logger.debug(f"<<< {self.connection.self_address} {self.role} received signal: <<<")
        sig.show()
        return sig  # type: ignore

    async def expect_media(self, timeout: float = 5.0) -> avdtp.MediaPacket:
        if not self.transport_channel:
            if self.role != "acceptor":
                raise AttributeError("Transport channel is None")

            try:
                logger.info("Waiting for transport channel")
                await self.wait_transport_channel_connected()
            except (TimeoutError, ValueError):
                raise AttributeError("Transport channel is None")

        try:
            packet = await asyncio.wait_for(self.transport_queue.get(), timeout=timeout)
            logger.debug(f"<<< {self.connection.self_address} {self.role} received media <<<")
            logger.debug(f"RTP Packet: {packet.hex()}")
        except TimeoutError:
            raise TimeoutError(f"TimeoutError while waiting for media") from None

        return avdtp.MediaPacket.from_bytes(packet)

    def send_signal(self, packet: av.SignalingPacket):
        logger.debug(f">>> {self.connection.self_address} {self.role} sending signal: >>>")
        packet.show()
        if not self.signaling_channel:
            raise ValueError("Signaling L2CAP channel doesn't exist")
        self.signaling_channel.send_pdu(packet.serialize())

    def send_media(self, packet: bytes):
        logger.debug(f">>> {self.connection.self_address} {self.role} sending media >>>")
        if not self.transport_channel:
            raise ValueError("Transport L2CAP channel doesn't exist")
        self.transport_channel.send_pdu(packet)

    async def _initiate_signaling_channel(self):
        if self.signaling_channel:
            raise ValueError("Signaling L2CAP channel already exists")
        self.role = "initiator"
        self.signaling_channel = await self.connection.create_l2cap_channel(
            spec=l2cap.ClassicChannelSpec(psm=avdtp.AVDTP_PSM))

        def _on_channel_close() -> None:
            logger.info("Signaling channel closed")
            self.signaling_channel = None

        # Register to receive PDUs from the channel
        self.signaling_channel.sink = self._on_pdu
        self.signaling_channel.on('close', _on_channel_close)

    def _accept_signaling_channel(self):
        if self.avdtp_server:
            raise ValueError("L2CAP server already exists")
        self.role = "acceptor"
        avdtp_server = self.connection.device.l2cap_channel_manager.servers.get(avdtp.AVDTP_PSM)
        if not avdtp_server:
            self.avdtp_server = self.connection.device.create_l2cap_server(
                spec=l2cap.ClassicChannelSpec(psm=avdtp.AVDTP_PSM))
        else:
            self.avdtp_server = avdtp_server
            self.avdtp_server.remove_all_listeners('connection')
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
                if self.transport_channel_opened_future:
                    self.transport_channel_opened_future.set_result(None)

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

    def discard_audio_data(self):
        while not self.transport_queue.empty():
            self.transport_queue.get_nowait()
        logger.info(f"RTP channel queue cleared")

    async def accept_discover(self, seid_information: list[av.SeidInformation]):
        cmd = await self.expect_signal(av.DiscoverCommand(transaction_label=ANY))
        self.send_signal(
            av.DiscoverResponse(transaction_label=cmd.transaction_label,
                                seid_information=seid_information))

    async def initiate_discover(self, transaction_label: int = 0x01) -> av.DiscoverResponse:
        self.send_signal(av.DiscoverCommand(transaction_label=transaction_label))
        return await self.expect_signal(av.DiscoverResponse(transaction_label=transaction_label,
                                                            seid_information=ANY),
                                        timeout=5.0)

    async def accept_get_all_capabilities(self, service_capabilities: list[av.ServiceCapability]):
        cmd = await self.expect_signal(
            av.GetAllCapabilitiesCommand(acp_seid=ANY, transaction_label=ANY))
        self.send_signal(
            av.GetAllCapabilitiesResponse(transaction_label=cmd.transaction_label,
                                          service_capabilities=service_capabilities))

    async def initiate_get_all_capabilities(
            self,
            seid_information: av.SeidInformation,
            transaction_label: int = 0x02) -> av.GetAllCapabilitiesResponse:
        self.send_signal(
            av.GetAllCapabilitiesCommand(transaction_label=transaction_label,
                                         acp_seid=seid_information.acp_seid))
        return await self.expect_signal(
            av.GetAllCapabilitiesResponse(transaction_label=transaction_label,
                                          service_capabilities=ANY))

    async def accept_set_configuration(self, expected_configuration: list[av.ServiceCapability]):
        cmd = await self.expect_signal(
            av.SetConfigurationCommand(transaction_label=ANY,
                                       acp_seid=ANY,
                                       int_seid=ANY,
                                       service_capabilities=expected_configuration))
        assert isinstance(cmd, av.SetConfigurationCommand)
        self.acp_seid = cmd.acp_seid
        self.int_seid = cmd.int_seid
        self.send_signal(av.SetConfigurationResponse(transaction_label=cmd.transaction_label))

    async def initiate_set_configuration(self,
                                         acp_seid: int,
                                         int_seid: int,
                                         configuration: list[av.ServiceCapability],
                                         transaction_label: int = 0x03):
        self.send_signal(
            av.SetConfigurationCommand(transaction_label=transaction_label,
                                       acp_seid=acp_seid,
                                       int_seid=int_seid,
                                       service_capabilities=configuration))
        return await self.expect_signal(
            av.SetConfigurationResponse(transaction_label=transaction_label))

    async def accept_open(self, timeout: float = 3.0) -> int:
        cmd = await self.expect_signal(av.OpenCommand(transaction_label=ANY, acp_seid=ANY),
                                       timeout=timeout)
        self.send_signal(av.OpenResponse(transaction_label=cmd.transaction_label))
        return cmd.acp_seid

    async def initiate_open(self, acp_seid: int, transaction_label: int = 0x04):
        self.send_signal(av.OpenCommand(transaction_label=transaction_label, acp_seid=acp_seid))
        return await self.expect_signal(av.OpenResponse(transaction_label=transaction_label))

    async def accept_start(self, timeout: float = 8.0):
        cmd = await self.expect_signal(av.StartCommand(transaction_label=ANY, acp_seid=ANY),
                                       timeout=timeout)
        self.send_signal(av.StartResponse(transaction_label=cmd.transaction_label))

    async def initiate_start(self, acp_seid: int, transaction_label: int = 0x05):
        self.send_signal(av.StartCommand(transaction_label=transaction_label, acp_seid=acp_seid))
        return await self.expect_signal(av.StartResponse(transaction_label=transaction_label))

    async def accept_suspend(self, timeout: float = 8.0):
        cmd = await self.expect_signal(av.SuspendCommand(transaction_label=ANY, acp_seid=ANY),
                                       timeout=timeout)
        self.send_signal(av.SuspendResponse(transaction_label=cmd.transaction_label))

    async def initiate_suspend(self, acp_seid: int, transaction_label: int = 0x06):
        self.send_signal(av.SuspendCommand(transaction_label=transaction_label, acp_seid=acp_seid))
        return await self.expect_signal(av.SuspendResponse(transaction_label=transaction_label))

    async def accept_close(self, timeout: float = 3.0):
        cmd = await self.expect_signal(av.CloseCommand(transaction_label=ANY, acp_seid=ANY),
                                       timeout=timeout)
        self.send_signal(av.CloseResponse(transaction_label=cmd.transaction_label))

    async def accept_open_stream(self,
                                 seid_information: list[av.SeidInformation],
                                 service_capabilities: list[av.ServiceCapability],
                                 timeout: float = 10.0) -> int:
        await self.wait_signaling_channel_connected(timeout=timeout)

        expected_configuration: list[av.ServiceCapability] = []
        for capability in service_capabilities:
            if isinstance(capability, av.MediaTransportCapability) or isinstance(
                    capability, av.DelayReportingCapability):
                expected_configuration.append(capability)
            else:
                expected_configuration.append(ANY)

        await self.accept_discover(seid_information)
        await self.accept_get_all_capabilities(service_capabilities)
        await self.accept_set_configuration(expected_configuration)
        acp_seid = await self.accept_open()
        await self.wait_transport_channel_connected(timeout=timeout)
        return acp_seid

    async def initiate_delay_report(self, delay_ms: int = 100, timeout: float = 3.0):
        delay_one_tenth = delay_ms * 10
        delay_msb = (delay_one_tenth >> 8) & 0xff
        delay_lsb = delay_one_tenth & 0xff
        self.send_signal(
            av.DelayReportCommand(transaction_label=0x01,
                                  acp_seid=self.acp_seid,
                                  delay_msb=delay_msb,
                                  delay_lsb=delay_lsb))
        await self.expect_signal(av.DelayReportResponse(transaction_label=ANY), timeout=timeout)

    async def receive_audio_data(self, test_log_path: str, filename: str, duration_s: float = 1.0):
        """
        Asynchronously receives and processes audio data for a specified duration.

        Received audio can be converted to *.wav by running:
        ffmpeg -f <codec_name> -i <test_log_path>/receive_audio_data_<filename>.data output.wav

        Args:
            test_log_path: The test specific log directory path.
            duration_s: The duration in seconds for which the audio is being received (default: 1.0 second).
        """
        start_time = time.time()
        test_log_path = os.path.join(test_log_path, f"receive_audio_data_{filename}.data")
        logger.info(f"Saving to: {test_log_path}")
        with open(test_log_path, "wb") as output_file:
            frames = 0
            while time.time() - start_time < duration_s:
                try:
                    frame = await asyncio.wait_for(self.expect_media(), timeout=1.0)
                    logger.info(f"frame {frames}: {frame}")
                    output_file.write(frame.payload[1:])
                    frames += 1
                except asyncio.TimeoutError:
                    logger.info(f"No audio received for 1 second. Finish")
                    return
