from __future__ import annotations
import grpc
import grpc.aio
import logging

from pandora.oob_grpc_aio import OOBServicer
from pandora.oob_pb2 import (
    OobDataRequest,
    OobDataResponse,
)

from bumble.smp import OobContext, OobSharedData
from bumble.pairing import PairingConfig, PairingDelegate
from bumble.device import Device
from pandora_services import utils


# This class implements the Hid Pandora interface.
class OOBService(OOBServicer):

    def __init__(self, device: Device) -> None:
        super().__init__()
        self.log = utils.BumbleServerLoggerAdapter(logging.getLogger(), {
            'service_name': 'oob',
            'device': device
        })
        self.device = device

    def configure_oob_pairing(self, peer_oob: OobSharedData | None) -> str:
        our_oob_context = OobContext()
        share_oob = our_oob_context.share().__str__()
        self.log.debug(f"Local oob data: {share_oob}")
        oob_contexts = PairingConfig.OobConfig(our_context=our_oob_context,
                                               peer_data=peer_oob,
                                               legacy_context=None)
        self.device.pairing_config_factory = lambda connection: PairingConfig(
            sc=True,
            mitm=True,
            bonding=True,
            oob=oob_contexts,
        )

        return share_oob

    @utils.rpc
    async def ShareOobData(self, request: OobDataRequest,
                           context: grpc.ServicerContext) -> OobDataResponse:

        if request.oob:
            data = str(bytes(request.oob).hex())
            oob_c, oob_r = data[:len(data) // 2], data[len(data) // 2:]
            peer_oob = OobSharedData(c=bytearray.fromhex(oob_c), r=bytearray.fromhex(oob_r))
            self.log.debug(f'peer oob data {peer_oob}')
        else:
            peer_oob = None
        share_oob = self.configure_oob_pairing(peer_oob)
        # Extract data from string `OOB(C=XXXXXXXXXXXXXXXX, R=YYYYYYYYYYYYYYYY)`
        extracted_oob = share_oob.strip("OOB()C=").replace(", R=", "")
        return OobDataResponse(oob=bytes(bytearray.fromhex(extracted_oob)))
