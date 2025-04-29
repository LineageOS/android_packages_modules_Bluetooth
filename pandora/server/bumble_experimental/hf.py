from __future__ import annotations
import asyncio
import grpc
import grpc.aio
import logging
from typing import Optional

from bumble import core
from bumble import rfcomm
from bumble.rfcomm import Server
from bumble import hci

from bumble.device import Device, Connection
from google.protobuf import empty_pb2

from bumble import hfp
from bumble.hfp import HfProtocol

hf_protocol: Optional[HfProtocol] = None
from pandora_experimental.hfp_grpc_aio import HFPServicer

from bumble.pandora import utils

from bumble.core import (
    BT_BR_EDR_TRANSPORT,
    ProtocolError,
)

hf_protocol: Optional[HfProtocol] = None


def on_dlc(dlc: rfcomm.DLC, configuration: hfp.HfConfiguration):
    logging.info(f'DLC connected {dlc}')
    global hf_protocol

    # Start the HFP
    hf_protocol = HfProtocol(dlc, configuration)
    asyncio.create_task(hf_protocol.run())


def on_connect(connection: Connection):
    logging.info(f'ACL connection with peer {connection.peer_address}')


def _default_hf_configuration():
    # Hands-Free profile configuration.
    configuration = hfp.HfConfiguration(
        supported_hf_features=[
            hfp.HfFeature.THREE_WAY_CALLING,
            hfp.HfFeature.REMOTE_VOLUME_CONTROL,
            hfp.HfFeature.ENHANCED_CALL_STATUS,
            hfp.HfFeature.ENHANCED_CALL_CONTROL,
            hfp.HfFeature.CODEC_NEGOTIATION,
            hfp.HfFeature.HF_INDICATORS,
            hfp.HfFeature.ESCO_S4_SETTINGS_SUPPORTED,
        ],
        supported_hf_indicators=[
            hfp.HfIndicator.BATTERY_LEVEL,
        ],
        supported_audio_codecs=[
            hfp.AudioCodec.CVSD,
            hfp.AudioCodec.MSBC,
        ],
    )
    return configuration


# This class implements the Hid Pandora interface.
class HFService(HFPServicer):

    hf_config = None

    def __init__(self, device: Device, rfcomm_server: Server) -> None:
        super().__init__()
        self.device = device

        global hf_config
        hf_config = _default_hf_configuration()
        self.rfcomm_server = rfcomm_server
        self.rfcomm_client = None

        # Listen for incoming DLC connections
        global channel_number
        channel_number = self.rfcomm_server.listen(lambda dlc: on_dlc(dlc, hf_config))
        logging.info(f'Listening for connection on channel {channel_number}')

        # Advertise the HFP RFComm channel in the SDP
        self.device.sdp_service_records.update(
            {0x00010001: hfp.make_hf_sdp_records(0x00010001, channel_number, hf_config)})

        self.device.on('connection', on_connect)

    @utils.rpc
    async def EnableSlcAsHandsfree(self, request: EnableSlcAsHandsfreeRequest,
                                   context: grpc.ServicerContext) -> empty_pb2.Empty:
        logging.info(f'EnableSlcAsHandsfree RPC Function')
        logging.info(f'Cookie value {request.connection.cookie.value}')

        # Lookup for the connection with connection handle
        conn = self.device.lookup_connection(
            int.from_bytes(request.connection.cookie.value, byteorder='big'))
        logging.info(f'ACL Active Connection {conn}')

        if not (hfp_record := await hfp.find_ag_sdp_record(conn)):
            logging.error('no service found')
            return empty_pb2.Empty()

        try:
            await conn.authenticate()

        except ProtocolError as e:
            if e.error_code == hci.HCI_CONNECTION_ALREADY_EXISTS_ERROR:
                logging.warning(f'Connection with {conn.peer_address} already exsist')
                logging.warning(f'Connection Details {conn}')
            else:
                logging.error(f'Failed to establish connection')

        if not conn.is_encrypted:
            await conn.encrypt()

        channel, version, hf_sdp_features = hfp_record
        logging.info(f'HF version: {version}')
        logging.info(f'HF features: {hf_sdp_features}')

        # Create a client and start it
        logging.info('Starting to RFCOMM client')
        self.rfcomm_client = rfcomm.Client(conn)
        rfcomm_mux = await self.rfcomm_client.start()
        logging.info('RFComm client Started')
        logging.info(f'Opening session for channel {channel}...')
        try:
            session = await rfcomm_mux.open_dlc(channel)
            logging.info(f'Session open {session}')

        except bumble.core.ConnectionError as error:
            logging.error(f'Session open failed: {error}')
            await rfcomm_mux.disconnect()
            logging.error('Disconnected from RFCOMM server')
            return

        hf_protocol = HfProtocol(session, hf_config)
        asyncio.create_task(hf_protocol.run())

        return empty_pb2.Empty()

    @utils.rpc
    async def DisableSlcAsHandsfree(self, request: DisableSlcAsHandsfreeRequest,
                                    context: grpc.ServicerContext) -> empty_pb2.Empty:
        logging.info(f'DisableSlcAsHandsfree RPC Function')

        # Lookup for the connection with connection handle
        conn = self.device.lookup_connection(
            int.from_bytes(request.connection.cookie.value, byteorder='big'))
        await conn.disconnect()

        return empty_pb2.Empty()
