# Copyright 2022 Google LLC
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

import asyncio
import avatar
import enum
import grpc
import logging
import numpy as np

from avatar import BumblePandoraDevice, PandoraDevice, PandoraDevices, asynchronous
import pandora_services as bumble_server
from bumble.gatt import GATT_ASHA_SERVICE
from bumble.pairing import PairingDelegate
from pandora_services.asha import AshaGattService, AshaService
from mobly import base_test, signals, test_runner
from mobly.asserts import assert_equal  # type: ignore
from mobly.asserts import assert_false  # type: ignore
from mobly.asserts import assert_in  # type: ignore
from mobly.asserts import assert_is_not_none  # type: ignore
from mobly.asserts import assert_not_equal  # type: ignore
from mobly.asserts import assert_true  # type: ignore
from pandora._utils import AioStream
from pandora.host_pb2 import PUBLIC, RANDOM, AdvertiseResponse, Connection, DataTypes, OwnAddressType, ScanningResponse
from pandora.security_pb2 import LE_LEVEL3
from pandora.asha_grpc_aio import Asha as AioAsha, add_AshaServicer_to_server
from pandora.asha_pb2 import PlaybackAudioRequest
from typing import AsyncIterator, ByteString, List, Optional, Tuple

ASHA_UUID = GATT_ASHA_SERVICE.to_hex_str('-')
HISYNCID: List[int] = [0x01, 0x02, 0x03, 0x04, 0x5, 0x6, 0x7, 0x8]
COMPLETE_LOCAL_NAME: str = "Bumble"
AUDIO_SIGNAL_AMPLITUDE = 0.8
AUDIO_SIGNAL_SAMPLING_RATE = 44100
SINE_FREQUENCY = 440
SINE_DURATION = 0.1


class Ear(enum.IntEnum):
    """Reference devices type"""

    LEFT = 0
    RIGHT = 1


def asha_service_data(ear: Ear) -> bytes:
    protocol_version = 0x01
    truncated_hisyncid = HISYNCID[:4]
    capability = ear
    return bytes([protocol_version, capability] + truncated_hisyncid)


class AshaTest(base_test.BaseTestClass):  # type: ignore[misc]
    devices: Optional[PandoraDevices] = None

    # pandora devices.
    dut: PandoraDevice
    ref_left: BumblePandoraDevice
    ref_right: BumblePandoraDevice

    def setup_class(self) -> None:
        # Register experimental bumble servicers hook.
        bumble_server.register_servicer_hook(lambda bumble, _, server: add_AshaServicer_to_server(
            AshaService(bumble.device), server))

        self.devices = PandoraDevices(self)
        self.dut, ref_left, ref_right, *_ = self.devices

        if isinstance(self.dut, BumblePandoraDevice):
            raise signals.TestAbortClass('DUT Bumble does not support Asha source')
        if not isinstance(ref_left, BumblePandoraDevice):
            raise signals.TestAbortClass('Test require Bumble as reference device(s)')
        if not isinstance(ref_right, BumblePandoraDevice):
            raise signals.TestAbortClass('Test require Bumble as reference device(s)')

        self.ref_left, self.ref_right = ref_left, ref_right

    def teardown_class(self) -> None:
        if self.devices:
            self.devices.stop_all()

    @avatar.asynchronous
    async def setup_test(self) -> None:
        await asyncio.gather(self.dut.reset(), self.ref_left.reset(), self.ref_right.reset())

        # ASHA hearing aid's IO capability is NO_OUTPUT_NO_INPUT
        self.ref_left.server_config.io_capability = PairingDelegate.NO_OUTPUT_NO_INPUT
        self.ref_right.server_config.io_capability = PairingDelegate.NO_OUTPUT_NO_INPUT

    async def accept_connection(self, device: BumblePandoraDevice, own_address_type: OwnAddressType,
                                ear: Ear) -> Connection:
        """Start advertising with support for the ASHA profile and wait for the
        remote device connection."""

        asha = AioAsha(device.aio.channel)

        await asha.Register(capability=ear, hisyncid=HISYNCID)

        advertisement = device.aio.host.Advertise(
            legacy=True,
            connectable=True,
            own_address_type=own_address_type,
            data=DataTypes(
                complete_local_name=COMPLETE_LOCAL_NAME,
                incomplete_service_class_uuids16=[ASHA_UUID],
            ),
        )

        result = await anext(aiter(advertisement))
        advertisement.cancel()

        return result.connection

    async def initiate_connection(self, device: PandoraDevice, own_address_type: OwnAddressType,
                                  ear: Ear) -> Connection:
        """Scan for an ASHA capable device and initiate an LE connection."""

        scan = device.aio.host.Scan(own_address_type=own_address_type)
        expected_service_data = asha_service_data(ear)

        def supports_asha(result):
            return (ASHA_UUID in result.data.incomplete_service_class_uuids16 and
                    result.data.service_data_uuid16[ASHA_UUID] == expected_service_data)

        scan_result = await anext((x async for x in scan if supports_asha(x)))
        scan.cancel()

        result = await device.aio.host.ConnectLE(own_address_type=own_address_type,
                                                 **scan_result.address_asdict())

        return result.connection

    async def accept_pairing(self, device: PandoraDevice, connection: Connection):
        wait_security = await device.aio.security.WaitSecurity(connection=connection, le=LE_LEVEL3)
        assert_equal(wait_security.result_variant(), 'success')

    async def initiate_pairing(self, device: PandoraDevice, connection: Connection):
        secure = await device.aio.security.Secure(connection=connection, le=LE_LEVEL3)
        assert_equal(secure.result_variant(), 'success')

    def get_le_psm_future(self, ref_device: BumblePandoraDevice) -> asyncio.Future[int]:
        asha_service = next(
            (x for x in ref_device.device.gatt_server.attributes if isinstance(x, AshaGattService)))
        le_psm_future = asyncio.get_running_loop().create_future()

        def le_psm_handler(connection: Connection, data: int) -> None:
            le_psm_future.set_result(data)

        asha_service.on('le_psm_out', le_psm_handler)
        return le_psm_future

    def get_read_only_properties_future(self,
                                        ref_device: BumblePandoraDevice) -> asyncio.Future[bytes]:
        asha_service = next(
            (x for x in ref_device.device.gatt_server.attributes if isinstance(x, AshaGattService)))
        read_only_properties_future = asyncio.get_running_loop().create_future()

        def read_only_properties_handler(connection: Connection, data: bytes) -> None:
            read_only_properties_future.set_result(data)

        asha_service.on('read_only_properties', read_only_properties_handler)
        return read_only_properties_future

    def get_start_future(self, ref_device: BumblePandoraDevice) -> asyncio.Future[dict[str, int]]:
        asha_service = next(
            (x for x in ref_device.device.gatt_server.attributes if isinstance(x, AshaGattService)))
        start_future = asyncio.get_running_loop().create_future()

        def start_command_handler(connection: Connection, data: dict[str, int]) -> None:
            start_future.set_result(data)

        asha_service.on('start', start_command_handler)
        return start_future

    def get_stop_future(self, ref_device: BumblePandoraDevice) -> asyncio.Future[Connection]:
        asha_service = next(
            (x for x in ref_device.device.gatt_server.attributes if isinstance(x, AshaGattService)))
        stop_future = asyncio.get_running_loop().create_future()

        def stop_command_handler(connection: Connection) -> None:
            stop_future.set_result(connection)

        asha_service.on('stop', stop_command_handler)
        return stop_future

    async def get_audio_data(self, ref_asha: AioAsha, connection: Connection,
                             timeout: int) -> ByteString:
        audio_data = bytearray()
        try:
            captured_data = ref_asha.CaptureAudio(connection=connection, timeout=timeout)
            async for data in captured_data:
                audio_data.extend(data.data)

        except grpc.aio.AioRpcError as e:
            if e.code() == grpc.StatusCode.DEADLINE_EXCEEDED:
                pass
            else:
                raise

        return audio_data

    async def generate_sine(self, connection: Connection) -> AsyncIterator[PlaybackAudioRequest]:
        # generate sine wave audio
        sine = AUDIO_SIGNAL_AMPLITUDE * np.sin(
            2 * np.pi * np.arange(AUDIO_SIGNAL_SAMPLING_RATE * SINE_DURATION) *
            (SINE_FREQUENCY / AUDIO_SIGNAL_SAMPLING_RATE))
        s16le = (sine * 32767).astype('<i2')

        # Interleaved audio.
        stereo = np.zeros(s16le.size * 2, dtype=sine.dtype)
        stereo[0::2] = s16le

        # Send 4 second of audio.
        for _ in range(0, int(4 / SINE_DURATION)):
            yield PlaybackAudioRequest(connection=connection, data=stereo.tobytes())

    @avatar.parameterized(
        (RANDOM, PUBLIC),
        (RANDOM, RANDOM),
    )  # type: ignore[misc]
    @asynchronous
    async def test_pairing(
        self,
        dut_address_type: OwnAddressType,
        ref_address_type: OwnAddressType,
    ) -> None:
        """
        DUT discovers Ref.
        DUT initiates connection to Ref.
        Verify that DUT and Ref are bonded and connected.
        """

        logging.info("connecting left device")
        dut_ref, ref_dut = await asyncio.gather(
            self.initiate_connection(self.dut, dut_address_type, Ear.LEFT),
            self.accept_connection(self.ref_left, ref_address_type, Ear.LEFT),
        )

        logging.info("pairing left device")
        await asyncio.gather(
            self.initiate_pairing(self.dut, dut_ref),
            self.accept_pairing(self.ref_left, ref_dut),
        )

        logging.info("disconnecting left device")
        await asyncio.gather(
            self.ref_left.aio.host.Disconnect(connection=ref_dut),
            self.dut.aio.host.WaitDisconnection(connection=dut_ref),
        )

    @avatar.parameterized(
        (RANDOM, PUBLIC),
        (RANDOM, RANDOM),
    )  # type: ignore[misc]
    @asynchronous
    async def test_pairing_dual_device(
        self,
        dut_address_type: OwnAddressType,
        ref_address_type: OwnAddressType,
    ) -> None:
        """
        DUT discovers Ref.
        DUT initiates connection to Ref.
        Verify that DUT and Ref are bonded and connected.
        """

        logging.info("connecting left and right devices")
        dut_ref_left, dut_ref_right, ref_left_dut, ref_right_dut = await asyncio.gather(
            self.initiate_connection(self.dut, dut_address_type, Ear.LEFT),
            self.initiate_connection(self.dut, dut_address_type, Ear.RIGHT),
            self.accept_connection(self.ref_left, ref_address_type, Ear.LEFT),
            self.accept_connection(self.ref_right, ref_address_type, Ear.RIGHT),
        )

        logging.info("pairing left device")
        await asyncio.gather(
            self.initiate_pairing(self.dut, dut_ref_left),
            self.accept_pairing(self.ref_left, ref_left_dut),
        )

        logging.info("pairing right device")
        await asyncio.gather(
            self.initiate_pairing(self.dut, dut_ref_right),
            self.accept_pairing(self.ref_right, ref_right_dut),
        )

        logging.info("disconnecting left and right devices")
        await asyncio.gather(
            self.ref_left.aio.host.Disconnect(connection=ref_left_dut),
            self.ref_right.aio.host.Disconnect(connection=ref_right_dut),
            self.dut.aio.host.WaitDisconnection(connection=dut_ref_left),
            self.dut.aio.host.WaitDisconnection(connection=dut_ref_right),
        )

    @avatar.parameterized(
        (RANDOM, RANDOM),
        (RANDOM, PUBLIC),
    )  # type: ignore[misc]
    @asynchronous
    async def test_auto_connection(
        self,
        dut_address_type: OwnAddressType,
        ref_address_type: OwnAddressType,
    ) -> None:
        """
        Ref initiates disconnection to DUT.
        Ref starts sending ASHA advertisements.
        Verify that DUT auto-connects to Ref.
        """

        logging.info("connecting left device")
        dut_ref, ref_dut = await asyncio.gather(
            self.initiate_connection(self.dut, dut_address_type, Ear.LEFT),
            self.accept_connection(self.ref_left, ref_address_type, Ear.LEFT),
        )

        logging.info("pairing left device")
        await asyncio.gather(
            self.initiate_pairing(self.dut, dut_ref),
            self.accept_pairing(self.ref_left, ref_dut),
        )

        logging.info("initiating ASHA profile connection")
        dut_asha = self.dut.aio.asha
        _ = await dut_asha.OpenSource(connection=dut_ref)

        # Disconnect the tested device and immediately start advertising
        # again to observe a reconnection.
        logging.info("disconnecting left device")
        await asyncio.gather(
            self.ref_left.aio.host.Disconnect(connection=ref_dut),
            self.dut.aio.host.WaitDisconnection(connection=dut_ref),
        )

        logging.info("waiting for left device reconnection")
        await asyncio.gather(
            self.accept_connection(self.ref_left, ref_address_type, Ear.LEFT),
            dut_asha.WaitSource(connection=dut_ref),
        )

    @avatar.parameterized(
        (RANDOM, RANDOM, Ear.LEFT),
        (RANDOM, RANDOM, Ear.RIGHT),
        (RANDOM, PUBLIC, Ear.LEFT),
        (RANDOM, PUBLIC, Ear.RIGHT),
    )  # type: ignore[misc]
    @asynchronous
    async def test_auto_connection_dual_device(self, dut_address_type: OwnAddressType,
                                               ref_address_type: OwnAddressType,
                                               tested_device: Ear) -> None:
        """
        Prerequisites: DUT and Ref are connected and bonded. Ref is a dual device.
        Description:
           1. One peripheral of Ref initiates disconnection to DUT.
           2. The disconnected peripheral starts sending ASHA advertisements.
           3. Verify that DUT will automatically reconnect the disconnected peripheral.
        """

        logging.info("connecting left and right devices")
        dut_ref_left, dut_ref_right, ref_left_dut, ref_right_dut = await asyncio.gather(
            self.initiate_connection(self.dut, dut_address_type, Ear.LEFT),
            self.initiate_connection(self.dut, dut_address_type, Ear.RIGHT),
            self.accept_connection(self.ref_left, ref_address_type, Ear.LEFT),
            self.accept_connection(self.ref_right, ref_address_type, Ear.RIGHT),
        )

        logging.info("pairing left device")
        await asyncio.gather(
            self.initiate_pairing(self.dut, dut_ref_left),
            self.accept_pairing(self.ref_left, ref_left_dut),
        )

        logging.info("pairing right device")
        await asyncio.gather(
            self.initiate_pairing(self.dut, dut_ref_right),
            self.accept_pairing(self.ref_right, ref_right_dut),
        )

        logging.info("initiating ASHA profile connection")
        dut_asha = self.dut.aio.asha
        _ = await asyncio.gather(dut_asha.OpenSource(connection=dut_ref_left),
                                 dut_asha.OpenSource(connection=dut_ref_right))

        if tested_device == Ear.LEFT:
            ref = self.ref_left
            ref_dut = ref_left_dut
            dut_ref = dut_ref_left
        else:
            ref = self.ref_right
            ref_dut = ref_right_dut
            dut_ref = dut_ref_right

        # Disconnect the tested device and immediately start advertising
        # again to observe a reconnection.
        logging.info("disconnecting test device")
        await asyncio.gather(
            ref.aio.host.Disconnect(connection=ref_dut),
            self.dut.aio.host.WaitDisconnection(connection=dut_ref),
        )

        logging.info("waiting for test device reconnection")
        await asyncio.gather(
            self.accept_connection(ref, ref_address_type, tested_device),
            dut_asha.WaitSource(connection=dut_ref),
        )

    @asynchronous
    async def test_music_start(self) -> None:
        """
        DUT discovers Ref.
        DUT initiates connection to Ref.
        Verify that DUT and Ref are bonded and connected.
        DUT starts media streaming.
        Verify that DUT sends a correct AudioControlPoint `Start` command (codec=1,
        audiotype=0, volume=<volume set on DUT>, otherstate=<state of Ref aux if dual devices>).
        """

        logging.info("connecting left device")
        dut_ref, ref_dut = await asyncio.gather(
            self.initiate_connection(self.dut, RANDOM, Ear.LEFT),
            self.accept_connection(self.ref_left, RANDOM, Ear.LEFT),
        )

        le_psm_future = self.get_le_psm_future(self.ref_left)
        read_only_properties_future = self.get_read_only_properties_future(self.ref_left)

        logging.info("pairing left device")
        await asyncio.gather(
            self.initiate_pairing(self.dut, dut_ref),
            self.accept_pairing(self.ref_left, ref_dut),
        )

        le_psm_out_result = await asyncio.wait_for(le_psm_future, timeout=3.0)
        assert_is_not_none(le_psm_out_result)

        read_only_properties_result = await asyncio.wait_for(read_only_properties_future,
                                                             timeout=3.0)
        assert_is_not_none(read_only_properties_result)

        dut_asha = AioAsha(self.dut.aio.channel)
        start_future = self.get_start_future(self.ref_left)

        logging.info("initiating ASHA profile connection")
        await dut_asha.OpenSource(connection=dut_ref)

        logging.info("starting audio stream")
        _, start_result = await asyncio.gather(dut_asha.Start(connection=dut_ref),
                                               asyncio.wait_for(start_future, timeout=3.0))

        assert_is_not_none(start_result)
        assert_equal(start_result['codec'], 1)
        assert_equal(start_result['audiotype'], 0)
        assert_is_not_none(start_result['volume'])
        assert_equal(start_result['otherstate'], 0)

    @asynchronous
    async def test_set_volume(self) -> None:
        """
        DUT discovers Ref.
        DUT initiates connection to Ref.
        Verify that DUT and Ref are bonded and connected.
        DUT is streaming media to Ref.
        Change volume on DUT.
        Verify DUT writes the correct value to ASHA `Volume` characteristic.
        """
        raise signals.TestSkip("TODO: update bt test interface for SetVolume to retry")

        logging.info("connecting left device")
        dut_ref, ref_dut = await asyncio.gather(
            self.initiate_connection(self.dut, RANDOM, Ear.LEFT),
            self.accept_connection(self.ref_left, RANDOM, Ear.LEFT),
        )

        logging.info("pairing left device")
        await asyncio.gather(
            self.initiate_pairing(self.dut, dut_ref),
            self.accept_pairing(self.ref_left, ref_dut),
        )

        asha_service = next((x for x in self.ref_left.device.gatt_server.attributes
                             if isinstance(x, AshaGattService)))
        dut_asha = AioAsha(self.dut.aio.channel)

        volume_future = asyncio.get_running_loop().create_future()

        def volume_command_handler(connection: Connection, data: int):
            volume_future.set_result(data)

        asha_service.on('volume', volume_command_handler)

        await dut_asha.WaitSource(connection=dut_ref)
        await dut_asha.Start(connection=dut_ref)
        # set volume to max volume
        _, volume_result = await asyncio.gather(dut_asha.SetVolume(1),
                                                asyncio.wait_for(volume_future, timeout=3.0))

        logging.info(f"start_result:{volume_result}")
        assert_is_not_none(volume_result)
        assert_equal(volume_result, 0)

    @asynchronous
    async def test_music_stop(self) -> None:
        """
        DUT discovers Ref.
        DUT initiates connection to Ref.
        Verify that DUT and Ref are bonded and connected.
        DUT is streaming media to Ref.
        DUT stops media streaming on Ref.
        Verify that DUT sends a correct AudioControlPoint `Stop` command.
        """

        logging.info("connecting left device")
        dut_ref, ref_dut = await asyncio.gather(
            self.initiate_connection(self.dut, RANDOM, Ear.LEFT),
            self.accept_connection(self.ref_left, RANDOM, Ear.LEFT),
        )

        logging.info("pairing left device")
        await asyncio.gather(
            self.initiate_pairing(self.dut, dut_ref),
            self.accept_pairing(self.ref_left, ref_dut),
        )

        dut_asha = AioAsha(self.dut.aio.channel)
        stop_future = self.get_stop_future(self.ref_left)

        await dut_asha.OpenSource(connection=dut_ref)
        await dut_asha.Start(connection=dut_ref)
        logging.info("send stop")
        _, stop_result = await asyncio.gather(dut_asha.Stop(),
                                              asyncio.wait_for(stop_future, timeout=10.0))

        logging.info(f"stop_result:{stop_result}")
        assert_is_not_none(stop_result)

        # Sleep 0.5 second to mitigate flaky test first.
        await asyncio.sleep(0.5)

        audio_data = await self.get_audio_data(ref_asha=AioAsha(self.ref_left.aio.channel),
                                               connection=ref_dut,
                                               timeout=10)
        assert_equal(len(audio_data), 0)

    @asynchronous
    async def test_music_restart(self) -> None:
        """
        DUT discovers Ref.
        DUT initiates connection to Ref.
        Verify that DUT and Ref are bonded and connected.
        DUT starts media streaming.
        DUT stops media streaming.
        Verify that DUT sends a correct AudioControlPoint `Stop` command.
        DUT starts media streaming again.
        Verify that DUT sends a correct AudioControlPoint `Start` command.
        """

        logging.info("connecting left device")
        dut_ref, ref_dut = await asyncio.gather(
            self.initiate_connection(self.dut, RANDOM, Ear.LEFT),
            self.accept_connection(self.ref_left, RANDOM, Ear.LEFT),
        )

        logging.info("pairing left device")
        await asyncio.gather(
            self.initiate_pairing(self.dut, dut_ref),
            self.accept_pairing(self.ref_left, ref_dut),
        )

        dut_asha = AioAsha(self.dut.aio.channel)
        stop_future = self.get_stop_future(self.ref_left)

        await dut_asha.OpenSource(connection=dut_ref)
        await dut_asha.Start(connection=dut_ref)
        _, stop_result = await asyncio.gather(dut_asha.Stop(),
                                              asyncio.wait_for(stop_future, timeout=10.0))

        logging.info(f"stop_result:{stop_result}")
        assert_is_not_none(stop_result)

        # restart music streaming
        logging.info("restart music streaming")

        start_future = self.get_start_future(self.ref_left)

        await dut_asha.WaitSource(connection=dut_ref)
        _, start_result = await asyncio.gather(dut_asha.Start(connection=dut_ref),
                                               asyncio.wait_for(start_future, timeout=3.0))

        logging.info(f"start_result:{start_result}")
        assert_is_not_none(start_result)

    @asynchronous
    async def test_music_start_dual_device(self) -> None:
        """
        DUT discovers Ref.
        DUT initiates connection to Ref.
        Verify that DUT and Ref are bonded and connected.
        DUT starts media streaming.
        Verify that DUT sends a correct AudioControlPoint `Start` command (codec=1,
        audiotype=0, volume=<volume set on DUT>, otherstate=<state of Ref aux if dual devices>).
        """

        logging.info("connecting left device")
        dut_ref_left, ref_left_dut = await asyncio.gather(
            self.initiate_connection(self.dut, RANDOM, Ear.LEFT),
            self.accept_connection(self.ref_left, RANDOM, Ear.LEFT),
        )

        le_psm_future_left = self.get_le_psm_future(self.ref_left)
        read_only_properties_future_left = self.get_read_only_properties_future(self.ref_left)

        logging.info("pairing left device")
        await asyncio.gather(
            self.initiate_pairing(self.dut, dut_ref_left),
            self.accept_pairing(self.ref_left, ref_left_dut),
        )

        le_psm_out_result_left = await asyncio.wait_for(le_psm_future_left, timeout=3.0)
        assert_is_not_none(le_psm_out_result_left)

        read_only_properties_result_left = await asyncio.wait_for(read_only_properties_future_left,
                                                                  timeout=3.0)
        assert_is_not_none(read_only_properties_result_left)

        dut_asha = AioAsha(self.dut.aio.channel)
        start_future_left = self.get_start_future(self.ref_left)

        logging.info("initiating ASHA profile connection")
        await dut_asha.OpenSource(connection=dut_ref_left)

        logging.info("starting the audio stream")
        _, start_result_left = await asyncio.gather(
            dut_asha.Start(connection=dut_ref_left), asyncio.wait_for(start_future_left,
                                                                      timeout=3.0))

        logging.info(f"start_result_left:{start_result_left}")
        assert_is_not_none(start_result_left)
        assert_equal(start_result_left['codec'], 1)
        assert_equal(start_result_left['audiotype'], 0)
        assert_is_not_none(start_result_left['volume'])
        assert_equal(start_result_left['otherstate'], 0)

        # Start playing audio before connecting to ref_right
        generated_audio = self.generate_sine(connection=dut_ref_left)
        dut_asha.PlaybackAudio(generated_audio)

        logging.info("connecting right device")
        dut_ref_right, ref_right_dut = await asyncio.gather(
            self.initiate_connection(self.dut, RANDOM, Ear.RIGHT),
            self.accept_connection(self.ref_right, RANDOM, Ear.RIGHT),
        )

        le_psm_future_right = self.get_le_psm_future(self.ref_right)
        read_only_properties_future_right = self.get_read_only_properties_future(self.ref_right)

        logging.info("pairing right device")
        await asyncio.gather(
            self.initiate_pairing(self.dut, dut_ref_right),
            self.accept_pairing(self.ref_right, ref_right_dut),
        )

        le_psm_out_result_right = await asyncio.wait_for(le_psm_future_right, timeout=3.0)
        assert_is_not_none(le_psm_out_result_right)

        read_only_properties_result_right = await asyncio.wait_for(
            read_only_properties_future_right, timeout=3.0)
        assert_is_not_none(read_only_properties_result_right)

        start_future_right = self.get_start_future(self.ref_right)

        await dut_asha.OpenSource(connection=dut_ref_right)

        logging.info("send start_right")
        start_result_right = await asyncio.wait_for(start_future_right, timeout=10.0)

        logging.info(f"start_result_right:{start_result_right}")
        assert_is_not_none(start_result_right)
        assert_equal(start_result_right['codec'], 1)
        assert_equal(start_result_right['audiotype'], 0)
        assert_is_not_none(start_result_right['volume'])
        # ref_left already connected, otherstate = 1
        assert_equal(start_result_right['otherstate'], 1)

    @asynchronous
    async def test_music_stop_dual_device(self) -> None:
        """
        DUT discovers Refs.
        DUT initiates connection to Refs.
        Verify that DUT and Refs are bonded and connected.
        DUT is streaming media to Refs.
        DUT stops media streaming on Refs.
        Verify that DUT sends a correct AudioControlPoint `Stop` command.
        Verify Refs cannot recevice audio data after DUT stops media streaming.
        """

        logging.info("connecting left and right devices")
        dut_ref_left, dut_ref_right, ref_left_dut, ref_right_dut = await asyncio.gather(
            self.initiate_connection(self.dut, RANDOM, Ear.LEFT),
            self.initiate_connection(self.dut, RANDOM, Ear.RIGHT),
            self.accept_connection(self.ref_left, RANDOM, Ear.LEFT),
            self.accept_connection(self.ref_right, RANDOM, Ear.RIGHT),
        )

        logging.info("pairing left device")
        await asyncio.gather(
            self.initiate_pairing(self.dut, dut_ref_left),
            self.accept_pairing(self.ref_left, ref_left_dut),
        )

        logging.info("pairing right device")
        await asyncio.gather(
            self.initiate_pairing(self.dut, dut_ref_right),
            self.accept_pairing(self.ref_right, ref_right_dut),
        )

        logging.info("initiating ASHA profile connection")
        dut_asha = self.dut.aio.asha
        _ = await asyncio.gather(dut_asha.OpenSource(connection=dut_ref_left),
                                 dut_asha.OpenSource(connection=dut_ref_right))

        logging.info("starting the audio stream")
        await dut_asha.Start(connection=dut_ref_left)

        # Stop audio and wait until ref_device connections stopped.
        stop_future_left = self.get_stop_future(self.ref_left)
        stop_future_right = self.get_stop_future(self.ref_right)

        logging.info("stopping the audio stream")
        _, stop_result_left, stop_result_right = await asyncio.gather(
            dut_asha.Stop(),
            asyncio.wait_for(stop_future_left, timeout=10.0),
            asyncio.wait_for(stop_future_right, timeout=10.0),
        )

        logging.debug(f"stop_result_left:{stop_result_left}")
        logging.debug(f"stop_result_right:{stop_result_right}")
        assert_is_not_none(stop_result_left)
        assert_is_not_none(stop_result_right)

        # Sleep 0.5 second to mitigate flaky test first.
        await asyncio.sleep(0.5)

        (audio_data_left, audio_data_right) = await asyncio.gather(
            self.get_audio_data(ref_asha=self.ref_left.aio.asha,
                                connection=ref_left_dut,
                                timeout=10),
            self.get_audio_data(ref_asha=self.ref_right.aio.asha,
                                connection=ref_right_dut,
                                timeout=10),
        )

        assert_equal(len(audio_data_left), 0)
        assert_equal(len(audio_data_right), 0)

    @asynchronous
    async def test_music_audio_playback(self) -> None:
        """
        DUT discovers Ref.
        DUT initiates connection to Ref.
        Verify that DUT and Ref are bonded and connected.
        DUT is streaming media to Ref using playback API.
        Verify that Ref has received audio data.
        """

        logging.info("connecting left device")
        dut_ref, ref_dut = await asyncio.gather(
            self.initiate_connection(self.dut, RANDOM, Ear.LEFT),
            self.accept_connection(self.ref_left, RANDOM, Ear.LEFT),
        )

        logging.info("pairing left device")
        await asyncio.gather(
            self.initiate_pairing(self.dut, dut_ref),
            self.accept_pairing(self.ref_left, ref_dut),
        )

        logging.info("initiating ASHA profile connection")
        dut_asha = self.dut.aio.asha
        await dut_asha.OpenSource(connection=dut_ref)

        logging.info("starting the audio stream")
        await dut_asha.Start(connection=dut_ref)

        # Clear audio data before start audio playback testing
        await self.get_audio_data(ref_asha=self.ref_left.aio.asha, connection=ref_dut, timeout=10)

        generated_audio = self.generate_sine(connection=dut_ref)

        _, audio_data = await asyncio.gather(
            dut_asha.PlaybackAudio(generated_audio),
            self.get_audio_data(ref_asha=self.ref_left.aio.asha, connection=ref_dut, timeout=10),
        )

        assert_not_equal(len(audio_data), 0)
        # TODO(duoho): decode audio_data and verify the content


if __name__ == "__main__":
    logging.basicConfig(level=logging.DEBUG)
    test_runner.main()  # type: ignore
