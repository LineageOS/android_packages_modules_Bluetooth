from __future__ import annotations
import asyncio
import grpc
import logging
import functools

from bumble import utils as bumble_utils
from bumble import core
from bumble import rfcomm
from bumble import hci
import bumble.device
from google.protobuf import empty_pb2
from bumble import hfp
from bumble.hfp import HfProtocol
from pandora.hfp_grpc_aio import HFPServicer
from pandora.hfp_pb2 import EnableSlcAsHandsfreeRequest, DisableSlcAsHandsfreeRequest
from pandora_services import utils


def _default_hf_configuration():
    # Hands-Free profile configuration.
    configuration = hfp.HfConfiguration(
        supported_hf_features=[
            hfp.HfFeature.THREE_WAY_CALLING,
            hfp.HfFeature.VOICE_RECOGNITION_ACTIVATION,
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
    hf_protocol: hfp.HfProtocol

    def __init__(self, device: bumble.device.Device, rfcomm_server: rfcomm.Server) -> None:
        super().__init__()
        self.device = device

        self.hf_config = _default_hf_configuration()
        self.rfcomm_server = rfcomm_server

        # Listen for incoming DLC connections
        channel_number = self.rfcomm_server.listen(self.on_dlc)
        logging.info(f'Listening for connection on channel {channel_number}')

        # Advertise the HFP RFComm channel in the SDP
        self.device.sdp_service_records.update(
            {0x00010001: hfp.make_hf_sdp_records(0x00010001, channel_number, self.hf_config)})

        self.device.on('connection', self.on_connect)

    def on_connect(self, connection: bumble.device.Connection):
        logging.info(f'ACL connection with peer {connection.peer_address}')

    def on_sco_request(self, connection: bumble.device.Connection, link_type: int,
                       protocol: HfProtocol):
        logging.info('SCO request received')
        if connection == protocol.dlc.multiplexer.l2cap_channel.connection:
            if link_type == hci.HCI_Connection_Complete_Event.LinkType.SCO:
                esco_parameters = hfp.ESCO_PARAMETERS[hfp.DefaultCodecParameters.SCO_CVSD_D1]
            elif protocol.active_codec == hfp.AudioCodec.MSBC:
                esco_parameters = hfp.ESCO_PARAMETERS[hfp.DefaultCodecParameters.ESCO_MSBC_T2]
            elif protocol.active_codec == hfp.AudioCodec.CVSD:
                esco_parameters = hfp.ESCO_PARAMETERS[hfp.DefaultCodecParameters.ESCO_CVSD_S4]
            else:
                raise RuntimeError("unknown active codec")

            bumble_utils.cancel_on_event(
                connection,
                connection.EVENT_DISCONNECTION,
                connection.device.send_command(
                    hci.HCI_Enhanced_Accept_Synchronous_Connection_Request_Command(
                        bd_addr=connection.peer_address, **esco_parameters.asdict())),
            )

    def register_sco_handler(self, session: rfcomm.DLC, conn: bumble.device.Connection):
        logging.info('Registering SCO handler')
        handler = functools.partial(self.on_sco_request, protocol=self.hf_protocol)
        conn.device.on(self.device.EVENT_SCO_REQUEST, handler)
        session.multiplexer.l2cap_channel.once(
            session.multiplexer.l2cap_channel.EVENT_CLOSE,
            lambda: session.multiplexer.l2cap_channel.connection.device.remove_listener(
                self.device.EVENT_SCO_REQUEST, handler),
        )

    def on_dlc(self, dlc: rfcomm.DLC):
        logging.info(f'DLC connected {dlc}')

        # Start the HFP
        self.hf_protocol = HfProtocol(dlc, self.hf_config)
        asyncio.create_task(self.hf_protocol.run())

        # Register the SCO handler for incoming connections
        conn = dlc.multiplexer.l2cap_channel.connection
        self.register_sco_handler(dlc, conn)

    @utils.rpc
    async def EnableSlcAsHandsfree(self, request: EnableSlcAsHandsfreeRequest,
                                   context: grpc.ServicerContext) -> empty_pb2.Empty:
        logging.info('EnableSlcAsHandsfree RPC Function')
        logging.info(f'Cookie value {request.connection.cookie.value!r}')

        # Lookup for the connection with connection handle
        conn = self.device.lookup_connection(
            int.from_bytes(request.connection.cookie.value, byteorder='big'))
        assert conn, "Connection not found"
        logging.info(f'ACL Active Connection {conn}')

        if not (hfp_record := await hfp.find_ag_sdp_record(conn)):
            logging.error('no service found')
            return empty_pb2.Empty()

        try:
            await conn.authenticate()

        except core.ProtocolError as e:
            if e.error_code == hci.HCI_CONNECTION_ALREADY_EXISTS_ERROR:
                logging.warning(f'Connection with {conn.peer_address} already exsist')
                logging.warning(f'Connection Details {conn}')
            else:
                logging.error('Failed to establish connection')

        if not conn.is_encrypted:
            await conn.encrypt()

        channel, version, hf_sdp_features = hfp_record
        logging.info(f'HF version: {version}')
        logging.info(f'HF features: {hf_sdp_features}')

        # Create a client and start it
        logging.info('Starting to RFCOMM client')
        rfcomm_client = rfcomm.Client(conn)
        rfcomm_mux = await rfcomm_client.start()
        logging.info('RFComm client Started')
        logging.info(f'Opening session for channel {channel}...')
        try:
            session = await rfcomm_mux.open_dlc(channel)
            logging.info(f'Session open {session}')

        except core.ConnectionError:
            logging.exception('Session open failed')
            await rfcomm_mux.disconnect()
            logging.error('Disconnected from RFCOMM server')
            raise

        self.hf_protocol = HfProtocol(session, self.hf_config)
        asyncio.create_task(self.hf_protocol.run())

        # Register the SCO handler for outgoing connections
        self.register_sco_handler(session, conn)

        return empty_pb2.Empty()

    @utils.rpc
    async def DisableSlcAsHandsfree(self, request: DisableSlcAsHandsfreeRequest,
                                    context: grpc.ServicerContext) -> empty_pb2.Empty:
        logging.info('DisableSlcAsHandsfree RPC Function')

        # Lookup for the connection with connection handle
        conn = self.device.lookup_connection(
            int.from_bytes(request.connection.cookie.value, byteorder='big'))
        assert conn, "Connection not found"
        await conn.disconnect()

        return empty_pb2.Empty()
