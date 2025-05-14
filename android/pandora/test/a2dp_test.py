# Copyright 2024 Google LLC
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

import bumble.avdtp
import bumble.device
import avatar
import bumble
import dataclasses
import logging
import numpy as np
import time
from unittest.mock import ANY

from a2dp.packets.avdtp import *
from a2dp.signaling_channel import SignalingChannel
from avatar import BumblePandoraDevice, PandoraDevice, PandoraDevices, pandora_snippet, enableFlag
from bumble.a2dp import (
    A2DP_MPEG_2_4_AAC_CODEC_TYPE,
    A2DP_SBC_CODEC_TYPE,
    MPEG_2_AAC_LC_OBJECT_TYPE,
    SBC_DUAL_CHANNEL_MODE,
    SBC_JOINT_STEREO_CHANNEL_MODE,
    SBC_LOUDNESS_ALLOCATION_METHOD,
    SBC_MONO_CHANNEL_MODE,
    SBC_SNR_ALLOCATION_METHOD,
    SBC_STEREO_CHANNEL_MODE,
    AacMediaCodecInformation,
    SbcMediaCodecInformation,
    make_audio_sink_service_sdp_records,
)
from bumble.avctp import AVCTP_PSM
from bumble.avdtp import (
    AVDTP_AUDIO_MEDIA_TYPE,
    AVDTP_BAD_STATE_ERROR,
    AVDTP_OPEN_STATE,
    AVDTP_PSM,
    AVDTP_STREAMING_STATE,
    AVDTP_TSEP_SRC,
    Listener,
    MediaCodecCapabilities,
    Protocol,
    Stream,
)
from bumble.l2cap import (
    L2CAP_SIGNALING_CID,
    ChannelManager,
    ClassicChannel,
    ClassicChannelSpec,
    L2CAP_Configure_Request,
    L2CAP_Connection_Request,
    L2CAP_Connection_Response,
)
from bumble.pairing import PairingDelegate
from pandora_services import utils
from google.protobuf import empty_pb2
from mobly import base_test, test_runner, signals
from mobly.asserts import assert_equal  # type: ignore
from mobly.asserts import assert_greater_equal  # type: ignore
from mobly.asserts import assert_in  # type: ignore
from mobly.asserts import assert_less_equal  # type: ignore
from mobly.asserts import assert_raises  # type: ignore
from mobly.asserts import fail  # type: ignore
from pandora.a2dp_grpc_aio import A2DP
from pandora.a2dp_pb2 import STEREO, CodecId, CodecParameters, Configuration, PlaybackAudioRequest, Source
from pandora.host_pb2 import Connection, ConnectResponse
from pandora.security_pb2 import LEVEL2
from typing import AsyncIterator, Awaitable, Optional

logger = logging.getLogger(__name__)

AVDTP_HANDLE_SUSPEND_CFM_BAD_STATE = 'com.android.bluetooth.flags.avdt_handle_suspend_cfm_bad_state'
AVDTP_HANDLE_SIGNALING_ON_PEER_FAILURE = 'com.android.bluetooth.flags.avdt_handle_signaling_on_peer_failure'
A2DP_SM_IGNORE_CONNECT_EVENTS_IN_CONNECTING_STATE = 'com.android.bluetooth.flags.a2dp_sm_ignore_connect_events_in_connecting_state'
AVDT_WAIT_FOR_INITIAL_DELAY_REPORT_AS_INITIATOR = 'com.android.bluetooth.flags.avdt_wait_for_initial_delay_report_as_initiator'

AUDIO_SIGNAL_AMPLITUDE = 0.8
AUDIO_SIGNAL_FREQUENCY = 440
AUDIO_SIGNAL_PAN_VALUE = 0.5  # 0.0 (left) to 1.0 (right)
AUDIO_SIGNAL_SAMPLING_RATE = 44100
AUDIO_SIGNAL_SINE_DURATION = 0.1
MAX_INT16 = 2**15 - 1


async def initiate_pairing(device: PandoraDevice, address: bytes) -> Connection:
    """Connect and pair a remote device."""

    result = await device.aio.host.Connect(address=address)
    connection = result.connection
    assert connection

    bond = await device.aio.security.Secure(connection=connection, classic=LEVEL2)
    assert bond.success

    return connection


async def accept_pairing(device: PandoraDevice, address: bytes) -> Connection:
    """Accept connection and pairing from a remote device."""

    result = await device.aio.host.WaitConnection(address=address)
    connection = result.connection
    assert connection

    bond = await device.aio.security.WaitSecurity(connection=connection, classic=LEVEL2)
    assert bond.success

    return connection


async def open_source(a2dp_service: A2DP, connection: Connection) -> Source:
    """Initiate AVDTP connection from Android device."""

    result = await a2dp_service.OpenSource(connection=connection)
    source = result.source
    assert source

    return source


def sbc_codec_capabilities() -> MediaCodecCapabilities:
    """Codec capabilities for the Bumble sink devices."""

    return MediaCodecCapabilities(
        media_type=AVDTP_AUDIO_MEDIA_TYPE,
        media_codec_type=A2DP_SBC_CODEC_TYPE,
        media_codec_information=SbcMediaCodecInformation.from_lists(
            sampling_frequencies=[48000, 44100, 32000, 16000],
            channel_modes=[
                SBC_MONO_CHANNEL_MODE,
                SBC_DUAL_CHANNEL_MODE,
                SBC_STEREO_CHANNEL_MODE,
                SBC_JOINT_STEREO_CHANNEL_MODE,
            ],
            block_lengths=[4, 8, 12, 16],
            subbands=[4, 8],
            allocation_methods=[
                SBC_LOUDNESS_ALLOCATION_METHOD,
                SBC_SNR_ALLOCATION_METHOD,
            ],
            minimum_bitpool_value=2,
            maximum_bitpool_value=53,
        ),
    )


def sbc_service_capabilites() -> List[ServiceCapability]:
    return [
        MediaTransportCapability(),
        MediaCodecCapability(
            service_category=ServiceCategory.MEDIA_CODEC,
            media_type=0x00,  # Audio
            media_codec_type=0x00,  # SBC
            # 0x3f
            # Sampling Frequency: 44100, 48000 Hz
            # Channel Mode: Mono, Dual Channel, Stereo, Joint Stereo
            # 0xff
            # Block Length: 4, 8, 12, 16
            # Subbands: 4, 8
            # Allocation method: SNR, Loudness
            # 0x02
            # Min bitpool: 2
            # 0x37
            # Max bitpool: 55
            media_codec_specific_information_elements=bytearray([0x3f, 0xff, 0x02, 0x37]))
    ]


def aac_codec_capabilities() -> MediaCodecCapabilities:
    """Codec capabilities for the Bumble sink devices."""

    return MediaCodecCapabilities(
        media_type=AVDTP_AUDIO_MEDIA_TYPE,
        media_codec_type=A2DP_MPEG_2_4_AAC_CODEC_TYPE,
        media_codec_information=AacMediaCodecInformation.from_lists(
            object_types=[MPEG_2_AAC_LC_OBJECT_TYPE],
            sampling_frequencies=[48000, 44100],
            channels=[1, 2],
            vbr=1,
            bitrate=256000,
        ),
    )


def aac_service_capabilites() -> List[ServiceCapability]:
    return [
        MediaTransportCapability(),
        MediaCodecCapability(
            service_category=ServiceCategory.MEDIA_CODEC,
            media_type=0x00,  # Audio
            media_codec_type=0x02,  # AAC
            # 0xc0
            # MPEG2 AAC LC, MPEG4 AAC LC
            # 0xff
            # Sampling Frequency: 8000 - 44100 Hz
            # 0xbc
            # Sampling Frequency: 48000 - 96000 Hz
            # Channels: 1, 2
            # 0x89, 0x00, 0x00
            # VBR
            # Bit Rate: 0x090000
            media_codec_specific_information_elements=bytearray(
                [0xc0, 0xff, 0xbc, 0x89, 0x00, 0x00])),
        ContentProtectionCapability(cp_type=2)
    ]


async def generate_sine(source: Source,
                        duration_s: float = 4.0) -> AsyncIterator[PlaybackAudioRequest]:
    num_samples = int(AUDIO_SIGNAL_SAMPLING_RATE * duration_s)

    time_vector = np.arange(num_samples) / AUDIO_SIGNAL_SAMPLING_RATE

    sine_wave = AUDIO_SIGNAL_AMPLITUDE * np.sin(2 * np.pi * AUDIO_SIGNAL_FREQUENCY * time_vector)

    audio_data = (sine_wave * MAX_INT16).astype(np.int16)

    right_amplitude = np.sqrt(AUDIO_SIGNAL_PAN_VALUE)
    left_amplitude = np.sqrt(1 - AUDIO_SIGNAL_PAN_VALUE)

    left_channel = (sine_wave * left_amplitude * MAX_INT16).astype(np.int16)
    right_channel = (sine_wave * right_amplitude * MAX_INT16).astype(np.int16)

    audio_data = np.vstack((left_channel, right_channel)).T.reshape(-1, 2)

    samples_per_frame = int(AUDIO_SIGNAL_SAMPLING_RATE * AUDIO_SIGNAL_SINE_DURATION)

    for i in range(0, int(num_samples / samples_per_frame)):
        frame_samples = samples_per_frame
        if i + samples_per_frame > num_samples:
            frame_samples = num_samples - i
        frame_data = audio_data[i:i + frame_samples]
        yield PlaybackAudioRequest(source=source, data=frame_data.tobytes())


class A2dpTest(base_test.BaseTestClass):  # type: ignore[misc]
    """A2DP test suite."""

    devices: Optional[PandoraDevices] = None

    # pandora devices.
    dut: PandoraDevice
    ref1: BumblePandoraDevice
    ref2: BumblePandoraDevice

    @avatar.asynchronous
    async def setup_class(self) -> None:
        self.devices = PandoraDevices(self)
        self.dut, ref1, ref2, *_ = self.devices

        if not isinstance(ref1, BumblePandoraDevice):
            raise signals.TestAbortClass('Test require Bumble as reference device(s)')
        if not isinstance(ref2, BumblePandoraDevice):
            raise signals.TestAbortClass('Test require Bumble as reference device(s)')
        self.ref1 = ref1
        self.ref2 = ref2

        # Enable BR/EDR mode and SSP for Bumble devices.
        for device in self.devices:
            if isinstance(device, BumblePandoraDevice):
                device.config.setdefault('classic_enabled', True)
                device.config.setdefault('classic_ssp_enabled', True)
                device.config.setdefault('classic_smp_enabled', False)
                device.server_config.io_capability = PairingDelegate.NO_OUTPUT_NO_INPUT

    def teardown_class(self) -> None:
        if self.devices:
            self.devices.stop_all()

    @avatar.asynchronous
    async def setup_test(self) -> None:
        await asyncio.gather(self.dut.reset(), self.ref1.reset(), self.ref2.reset())

        self.dut_a2dp = A2DP(channel=self.dut.aio.channel)

        handle = 0x00010001
        self.ref1.device.sdp_service_records = {handle: make_audio_sink_service_sdp_records(handle)}
        self.ref2.device.sdp_service_records = {handle: make_audio_sink_service_sdp_records(handle)}

        self.ref1_a2dp = Listener.for_device(self.ref1.device)
        self.ref2_a2dp = Listener.for_device(self.ref2.device)
        self.ref1_a2dp_sink: bumble.avdtp.LocalSink | None = None
        self.ref2_a2dp_sink: bumble.avdtp.LocalSink | None = None

        def on_ref1_avdtp_connection(server: bumble.avdtp.Protocol):
            self.ref1_a2dp_sink = server.add_sink(sbc_codec_capabilities())

        def on_ref2_avdtp_connection(server: bumble.avdtp.Protocol):
            self.ref2_a2dp_sink = server.add_sink(sbc_codec_capabilities())
            self.ref2_a2dp_sink = server.add_sink(aac_codec_capabilities())

        self.ref1_a2dp.on('connection', on_ref1_avdtp_connection)
        self.ref2_a2dp.on('connection', on_ref2_avdtp_connection)

    @avatar.asynchronous
    async def test_connect_and_stream(self) -> None:
        """Basic A2DP connection and streaming test.
        This test wants to be a template to be reused for other tests.

        1. Pair and Connect RD1
        2. Start streaming
        3. Check AVDTP status on RD1
        4. Stop streaming
        5. Check AVDTP status on RD1
        """

        # Connect and pair RD1.
        dut_ref1, ref1_dut = await asyncio.gather(
            initiate_pairing(self.dut, self.ref1.address),
            accept_pairing(self.ref1, self.dut.address),
        )

        # Connect AVDTP to RD1.
        dut_ref1_source = await open_source(self.dut_a2dp, dut_ref1)
        assert self.ref1_a2dp_sink is not None and self.ref1_a2dp_sink.stream is not None
        assert_in(self.ref1_a2dp_sink.stream.state, [AVDTP_OPEN_STATE, AVDTP_STREAMING_STATE])

        # Start streaming to RD1.
        await self.dut_a2dp.Start(source=dut_ref1_source)

        generated_audio = generate_sine(source=dut_ref1_source, duration_s=4.0)
        await self.dut_a2dp.PlaybackAudio(generated_audio)
        assert_equal(self.ref1_a2dp_sink.stream.state, AVDTP_STREAMING_STATE)

        # Stop streaming to RD1.
        await self.dut_a2dp.Suspend(source=dut_ref1_source)
        assert_equal(self.ref1_a2dp_sink.stream.state, AVDTP_OPEN_STATE)

    @avatar.asynchronous
    async def test_signaling_channel_and_streaming(self) -> None:
        """Basic A2DP connection and streaming with SignalingChannel used by acceptor device test.

        1. Pair and Connect RD1
        2. Setup the acceptor expectations on signalling channel
        2. Start streaming
        4. Stop streaming
        """

        # Connect and pair RD1.
        dut_ref1, ref1_dut = await asyncio.gather(
            initiate_pairing(self.dut, self.ref1.address),
            accept_pairing(self.ref1, self.dut.address),
        )

        connection = pandora_snippet.get_raw_connection(device=self.ref1, connection=ref1_dut)
        assert connection is not None, "Unable to find connection!"
        channel = SignalingChannel.accept(connection)

        seid_information = [
            SeidInformation(acp_seid=0x01, tsep=Tsep.SINK, media_type=AVDTP_AUDIO_MEDIA_TYPE)
        ]

        # Connect AVDTP to RD1.
        _, dut_ref1_source = await asyncio.gather(
            channel.accept_open_stream(seid_information=seid_information,
                                       service_capabilities=sbc_service_capabilites()),
            open_source(self.dut_a2dp, dut_ref1))

        # Start streaming to RD1.
        await asyncio.gather(self.dut_a2dp.Start(source=dut_ref1_source), channel.accept_start())

        # Verify that audio is received on the transport channel.
        generated_audio = generate_sine(source=dut_ref1_source, duration_s=4.0)
        await asyncio.gather(
            self.dut_a2dp.PlaybackAudio(generated_audio),
            channel.receive_audio_data(test_log_path=self.log_path, filename="sbc", duration_s=2.0))

        # Stop streaming to RD1.
        await asyncio.gather(self.dut_a2dp.Suspend(source=dut_ref1_source),
                             channel.accept_suspend(timeout=8.0))

    @avatar.asynchronous
    async def test_avdtp_autoconnect_when_only_avctp_connected(self) -> None:
        """Test AVDTP automatically connects if peer device connects only AVCTP.

        1. Pair and Connect RD1 -> DUT
        2. Connect AVCTP RD1 -> DUT
        3. Check AVDTP status on RD1
        """

        # Connect and pair RD1.
        ref1_dut, dut_ref1 = await asyncio.gather(
            initiate_pairing(self.ref1, self.dut.address),
            accept_pairing(self.dut, self.ref1.address),
        )

        # Create a listener to wait for AVDTP connections
        avdtp_future = asyncio.get_running_loop().create_future()

        def on_avdtp_connection(server):
            nonlocal avdtp_future
            self.ref1_a2dp_sink = server.add_sink(sbc_codec_capabilities())
            self.ref1.log.info(f'Sink: {self.ref1_a2dp_sink}')
            avdtp_future.set_result(None)

        self.ref1_a2dp.on('connection', on_avdtp_connection)

        # Retrieve Bumble connection object from Pandora connection token
        connection = pandora_snippet.get_raw_connection(device=self.ref1, connection=ref1_dut)
        assert connection is not None, "Unable to find connection!"

        # Open AVCTP L2CAP channel
        avctp = await connection.create_l2cap_channel(spec=ClassicChannelSpec(AVCTP_PSM))
        self.ref1.log.info(f'AVCTP: {avctp}')

        # Wait for AVDTP L2CAP channel
        await asyncio.wait_for(avdtp_future, timeout=10.0)

    @avatar.asynchronous
    async def test_reconfigure_codec_success(self) -> None:
        """Basic A2DP connection and codec reconfiguration.

        1. Pair and Connect RD2
        2. Check current codec configuration - should be AAC
        3. Set SBC codec configuration
        """
        # Connect and pair RD2.
        dut_ref2, ref2_dut = await asyncio.gather(
            initiate_pairing(self.dut, self.ref2.address),
            accept_pairing(self.ref2, self.dut.address),
        )

        # Connect AVDTP to RD2.
        dut_ref2_source = await open_source(self.dut_a2dp, dut_ref2)
        assert self.ref2_a2dp_sink is not None and self.ref2_a2dp_sink.stream is not None
        assert_in(self.ref2_a2dp_sink.stream.state, [AVDTP_OPEN_STATE, AVDTP_STREAMING_STATE])

        # Get current codec status
        configurationResponse = await self.dut_a2dp.GetConfiguration(connection=dut_ref2)
        logger.info(f"Current codec configuration: {configurationResponse.configuration}")
        assert configurationResponse.configuration.id.HasField('mpeg_aac')

        new_configuration = Configuration(id=CodecId(sbc=empty_pb2.Empty()),
                                          parameters=CodecParameters(channel_mode=STEREO,
                                                                     sampling_frequency_hz=44100,
                                                                     bit_depth=16))

        # Set new codec
        logger.info(f"Switching to codec: {new_configuration}")
        result = await self.dut_a2dp.SetConfiguration(connection=dut_ref2,
                                                      configuration=new_configuration)
        assert result.success

        # Get current codec status
        configurationResponse = await self.dut_a2dp.GetConfiguration(connection=dut_ref2)
        logger.info(f"Current codec configuration: {configurationResponse.configuration}")
        assert configurationResponse.configuration.id.HasField('sbc')

    @avatar.asynchronous
    async def test_reconfigure_codec_error_unsupported(self) -> None:
        """Basic A2DP connection and codec reconfiguration failure.

        1. Pair and Connect RD2
        2. Check current codec configuration - should be AAC
        3. Set SBC codec configuration with unsupported parameters
        """
        # Connect and pair RD2.
        dut_ref2, ref2_dut = await asyncio.gather(
            initiate_pairing(self.dut, self.ref2.address),
            accept_pairing(self.ref2, self.dut.address),
        )

        # Connect AVDTP to RD2.
        dut_ref2_source = await open_source(self.dut_a2dp, dut_ref2)
        assert self.ref2_a2dp_sink is not None and self.ref2_a2dp_sink.stream is not None
        assert_in(self.ref2_a2dp_sink.stream.state, [AVDTP_OPEN_STATE, AVDTP_STREAMING_STATE])

        # Get current codec status
        configurationResponse = await self.dut_a2dp.GetConfiguration(connection=dut_ref2)
        logger.info(f"Current codec configuration: {configurationResponse.configuration}")
        assert configurationResponse.configuration.id.HasField('mpeg_aac')

        new_configuration = Configuration(id=CodecId(sbc=empty_pb2.Empty()),
                                          parameters=CodecParameters(channel_mode=STEREO,
                                                                     sampling_frequency_hz=176400,
                                                                     bit_depth=24))

        # Set new codec
        logger.info(f"Switching to codec: {new_configuration}")
        result = await self.dut_a2dp.SetConfiguration(connection=dut_ref2,
                                                      configuration=new_configuration)
        assert result.success == False

        # Get current codec status, assure it did not change
        configurationResponse = await self.dut_a2dp.GetConfiguration(connection=dut_ref2)
        logger.info(f"Current codec configuration: {configurationResponse.configuration}")
        assert configurationResponse.configuration.id.HasField('mpeg_aac')

    @avatar.asynchronous
    async def test_reconfigure_codec_aac_error(self) -> None:
        # Connect and pair RD2.
        dut_ref2, ref2_dut = await asyncio.gather(
            initiate_pairing(self.dut, self.ref2.address),
            accept_pairing(self.ref2, self.dut.address),
        )

        # Connect AVDTP to RD2.
        dut_ref2_source = await open_source(self.dut_a2dp, dut_ref2)
        assert self.ref2_a2dp_sink is not None and self.ref2_a2dp_sink.stream is not None
        assert_in(self.ref2_a2dp_sink.stream.state, [AVDTP_OPEN_STATE, AVDTP_STREAMING_STATE])

        # Get current codec status
        configurationResponse = await self.dut_a2dp.GetConfiguration(connection=dut_ref2)
        logger.info(f"Current codec configuration: {configurationResponse.configuration}")
        assert configurationResponse.configuration.id.HasField('mpeg_aac')

        new_configuration = Configuration(id=CodecId(sbc=empty_pb2.Empty()),
                                          parameters=CodecParameters(channel_mode=STEREO,
                                                                     sampling_frequency_hz=176400,
                                                                     bit_depth=24))

        # Set new codec
        logger.info(f"Switching to codec: {new_configuration}")
        result = await self.dut_a2dp.SetConfiguration(connection=dut_ref2,
                                                      configuration=new_configuration)
        assert result.success == False

        # Get current codec status, assure it did not change
        configurationResponse = await self.dut_a2dp.GetConfiguration(connection=dut_ref2)
        logger.info(f"Current codec configuration: {configurationResponse.configuration}")
        assert configurationResponse.configuration.id.HasField('mpeg_aac')

    @avatar.asynchronous
    @enableFlag(AVDTP_HANDLE_SUSPEND_CFM_BAD_STATE)
    async def test_avdt_handle_suspend_cfm_bad_state_error(self) -> None:
        """Test AVDTP handling of suspend confirmation BAD_STATE error.

        Test steps after DUT and RD1 connected and paired:
        1. Start streaming to RD1.
        2. Suspend streaming, RD1 will simulate failure response - AVDTP_BAD_STATE.
        3. The DUT closes the AVDTP connection.
        """

        # Connect and pair RD1.
        dut_ref1, ref1_dut = await asyncio.gather(
            initiate_pairing(self.dut, self.ref1.address),
            accept_pairing(self.ref1, self.dut.address),
        )

        connection = pandora_snippet.get_raw_connection(device=self.ref1, connection=ref1_dut)
        assert connection is not None, "Unable to find connection!"
        channel = SignalingChannel.accept(connection)

        # Connect AVDTP to RD1.
        _, dut_ref1_source = await asyncio.gather(
            channel.accept_open_stream(seid_information=[
                SeidInformation(acp_seid=0x01, tsep=Tsep.SINK, media_type=AVDTP_AUDIO_MEDIA_TYPE)
            ],
                                       service_capabilities=sbc_service_capabilites()),
            open_source(self.dut_a2dp, dut_ref1))

        # Start streaming to RD1.
        await asyncio.gather(self.dut_a2dp.Start(source=dut_ref1_source), channel.accept_start())

        generated_audio = generate_sine(source=dut_ref1_source, duration_s=4.0)
        await self.dut_a2dp.PlaybackAudio(generated_audio)

        # Verify that at least one audio frame is received on the transport channel.
        await channel.expect_media(timeout=5.0)

        # Stop streaming to RD1.
        _, cmd = await asyncio.gather(
            self.dut_a2dp.Suspend(source=dut_ref1_source),
            channel.expect_signal(SuspendCommand(transaction_label=ANY, acp_seid=ANY), timeout=8.0))

        # Simulate AVDTP_BAD_STATE response.
        channel.send_signal(
            SuspendReject(transaction_label=cmd.transaction_label,
                          error_code=ErrorCode.AVDTP_BAD_STATE))

        # Expect the DUT to close connection.
        await channel.accept_close(timeout=10.0)

    @avatar.asynchronous
    @enableFlag(AVDTP_HANDLE_SIGNALING_ON_PEER_FAILURE)
    async def test_avdt_open_after_timeout(self) -> None:
        """Test AVDTP automatically opens stream after timeout if peer device only configures codec.

        1. Pair and Connect RD1 -> DUT
        2. Connect AVDTP RD1 -> DUT but do not send AVDT Open Command
        3. Check that the DUT will abort and reopen the AVDTP as initiator
        """

        class TestAvdtProtocol(Protocol):

            def on_open_command(self, command):
                nonlocal avdtp_future
                logger.info("<< AVDTP Open received >>")
                avdtp_future.set_result(None)
                return super().on_open_command(command)

        # Connect and pair RD1.
        ref1_dut, dut_ref1 = await asyncio.gather(
            initiate_pairing(self.ref1, self.dut.address),
            accept_pairing(self.dut, self.ref1.address),
        )

        # Create a listener to wait for AVDTP open
        avdtp_future = asyncio.get_running_loop().create_future()

        # Retrieve Bumble connection object from Pandora connection token
        connection = pandora_snippet.get_raw_connection(device=self.ref1, connection=ref1_dut)
        assert connection is not None, "Unable to find connection!"
        assert connection is not None

        channel = await connection.create_l2cap_channel(spec=ClassicChannelSpec(psm=AVDTP_PSM))
        client = TestAvdtProtocol(channel)
        sink = client.add_sink(sbc_codec_capabilities())
        endpoints = await client.discover_remote_endpoints()
        logger.info(f"endpoints: {endpoints}")
        assert endpoints
        remote_source = list(endpoints)[0]
        assert remote_source.in_use == 0
        assert remote_source.media_type == AVDTP_AUDIO_MEDIA_TYPE
        assert remote_source.tsep == AVDTP_TSEP_SRC
        logger.info(f"remote_source: {remote_source}")

        configuration = MediaCodecCapabilities(
            media_type=AVDTP_AUDIO_MEDIA_TYPE,
            media_codec_type=A2DP_SBC_CODEC_TYPE,
            media_codec_information=SbcMediaCodecInformation.from_lists(
                sampling_frequencies=[44100],
                channel_modes=[SBC_JOINT_STEREO_CHANNEL_MODE],
                block_lengths=[16],
                subbands=[8],
                allocation_methods=[SBC_LOUDNESS_ALLOCATION_METHOD],
                minimum_bitpool_value=2,
                maximum_bitpool_value=53,
            ),
        )

        response = await remote_source.set_configuration(sink.seid, [configuration])
        logger.info(f"response: {response}")

        # Wait for AVDTP Open from DUT
        await asyncio.wait_for(avdtp_future, timeout=20.0)

    @avatar.asynchronous
    async def test_avdt_signaling_channel_connection_collision_case1(self) -> None:
        """Test AVDTP signaling channel connection collision.

        Test steps after DUT and RD1 connected and paired:
        1. RD1 connects DUT over AVDTP - first AVDTP signaling channel
        2. AVDTP signaling channel configuration postponed until DUT tries to initiate AVDTP signaling channel connection
        3. DUT tries connecting RD1 - collision simulated
        4. RD1 rejects AVDTP signaling channel connection request from DUT
        5. RD1 proceeds with first AVDTP signaling channel configuration
        6. Channel established - collision avoided
        """

        @dataclasses.dataclass
        class L2capConfigurationRequest:
            connection: Optional[bumble.device.Connection] = None
            cid: Optional[int] = None
            request: Optional[L2CAP_Configure_Request] = None

        pending_configuration_request: L2capConfigurationRequest | None = L2capConfigurationRequest(
        )

        class TestChannelManager(ChannelManager):

            def __init__(
                self,
                device: bumble.device.Device,
            ) -> None:
                super().__init__(
                    device.l2cap_channel_manager.extended_features,
                    device.l2cap_channel_manager.connectionless_mtu,
                )
                self.register_fixed_channel(bumble.smp.SMP_CID, device.on_smp_pdu)
                device.sdp_server.register(self)
                self.register_fixed_channel(bumble.att.ATT_CID, device.on_gatt_pdu)
                self.host = device.host

            def on_l2cap_connection_request(self, connection: bumble.device.Connection, cid: int,
                                            request) -> None:
                nonlocal pending_configuration_request
                if request.psm == AVDTP_PSM and pending_configuration_request is not None:
                    logger.info("<< 4. RD1 rejects AVDTP connection request from DUT >>")
                    self.send_control_frame(
                        connection,
                        cid,
                        L2CAP_Connection_Response(
                            identifier=request.identifier,
                            destination_cid=0,
                            source_cid=request.source_cid,
                            result=L2CAP_Connection_Response.
                            CONNECTION_REFUSED_NO_RESOURCES_AVAILABLE,
                            status=0x0000,
                        ),
                    )
                    logger.info("<< 5. RD1 proceeds with first AVDTP channel configuration >>")
                    chan_connection = pending_configuration_request.connection
                    chan_cid = pending_configuration_request.cid
                    chan_request = pending_configuration_request.request
                    assert chan_connection is not None
                    assert chan_cid is not None
                    assert chan_request is not None
                    pending_configuration_request = None
                    super().on_control_frame(connection=chan_connection,
                                             cid=chan_cid,
                                             control_frame=chan_request)
                    return
                super().on_l2cap_connection_request(connection, cid, request)

        class TestClassicChannel(ClassicChannel):

            def on_connection_response(self, response):
                assert self.state == self.State.WAIT_CONNECT_RSP
                assert (response.result == L2CAP_Connection_Response.CONNECTION_SUCCESSFUL
                       ), f"Connection response: {response}"
                self.destination_cid = response.destination_cid
                self._change_state(self.State.WAIT_CONFIG)
                logger.info("<< 2. RD1 connected DUT, configuration postponed >>")

            def on_configure_request(self, request) -> None:
                nonlocal pending_configuration_request
                if pending_configuration_request is not None:
                    logger.info("<< 3. Block RD1 until DUT tries AVDTP channel connection >>")
                    pending_configuration_request.connection = self.connection
                    pending_configuration_request.cid = self.source_cid
                    pending_configuration_request.request = request
                else:
                    super().on_configure_request(request)

        # Override L2CAP Channel Manager to control signaling
        self.ref1.device.l2cap_channel_manager = TestChannelManager(self.ref1.device)

        # Connect and pair DUT -> RD1.
        dut_ref1, ref1_dut = await asyncio.gather(
            initiate_pairing(self.dut, self.ref1.address),
            accept_pairing(self.ref1, self.dut.address),
        )

        # Retrieve Bumble connection object from Pandora connection token
        connection = pandora_snippet.get_raw_connection(device=self.ref1, connection=ref1_dut)
        assert connection is not None, "Unable to find connection!"
        # Find a free CID for a new channel
        connection_channels = self.ref1.device.l2cap_channel_manager.channels.setdefault(
            connection.handle, {})
        source_cid = self.ref1.device.l2cap_channel_manager.find_free_br_edr_cid(
            connection_channels)
        assert source_cid is not None, "source_cid is None"

        spec = ClassicChannelSpec(AVDTP_PSM)
        channel = TestClassicChannel(
            self.ref1.device.l2cap_channel_manager,
            connection,
            L2CAP_SIGNALING_CID,
            AVDTP_PSM,
            source_cid,
            spec.mtu,
        )
        connection_channels[source_cid] = channel

        logger.info("<< 1. RD1 connects DUT over AVDTP - first channel >>")
        await channel.connect()
        logger.info(f"<< 6. Channel established: {channel} >>")
        assert channel.state == ClassicChannel.State.OPEN

        # Initiate AVDTP with connected L2CAP signaling channel
        protocol = Protocol(channel)
        protocol.add_sink(sbc_codec_capabilities())
        logger.info("<< Test finished! >>")

    @avatar.asynchronous
    @enableFlag(A2DP_SM_IGNORE_CONNECT_EVENTS_IN_CONNECTING_STATE)
    async def test_avdt_signaling_channel_connection_collision_case2(self) -> None:
        """Test AVDTP signaling channel connection collision with Android as initiator.

        Test steps after DUT and RD1 connected and paired:
        1. RD1 waits for connection request from DUT
        2. DUT connects RD1 over AVDTP - first AVDTP signaling channel
        3. RD1 sends connection request to DUT to simulate collision
        4. RD1 rejects connection from DUT
        5. DUT closed initiated connection and allowed for the incoming to proceed. RD1 opens AVDT connection
        6. DUT A2DP source configured and connected
        """

        wait_for_l2cap_open = asyncio.get_running_loop().create_future()

        class TestClassicChannel(ClassicChannel):

            def test_connect(self, connection: bumble.device.Connection, cid: int,
                             request: L2CAP_Connection_Request) -> None:
                assert self.state == self.State.CLOSED

                # Check that we can start a new connection
                assert not self.connection_result

                self._change_state(self.State.WAIT_CONNECT_RSP)
                logger.info("<< 3. RD1 sends connection request to DUT to simulate collision >>")
                self.send_control_frame(
                    L2CAP_Connection_Request(
                        identifier=self.manager.next_identifier(self.connection),
                        psm=self.psm,
                        source_cid=self.source_cid,
                    ))
                if (self.psm == AVDTP_PSM):
                    logger.info("<< 4. RD1 rejects connection from DUT >>")
                    self.manager.send_control_frame(
                        connection, cid,
                        L2CAP_Connection_Response(
                            identifier=request.identifier,
                            destination_cid=0,
                            source_cid=request.source_cid,
                            result=L2CAP_Connection_Response.
                            CONNECTION_REFUSED_NO_RESOURCES_AVAILABLE,
                            status=0x0000,
                        ))

        class TestChannelManager(ChannelManager):

            def __init__(
                self,
                device: bumble.device.Device,
            ) -> None:
                super().__init__(
                    device.l2cap_channel_manager.extended_features,
                    device.l2cap_channel_manager.connectionless_mtu,
                )
                self.register_fixed_channel(bumble.smp.SMP_CID, device.on_smp_pdu)
                device.sdp_server.register(self)
                self.register_fixed_channel(bumble.att.ATT_CID, device.on_gatt_pdu)
                self.host = device.host

            def on_l2cap_connection_request(self, connection: bumble.device.Connection, cid: int,
                                            request: L2CAP_Connection_Request) -> None:
                if (request.psm == AVDTP_PSM):
                    logger.info(
                        "<< 2. DUT connects RD1 over AVDTP - first AVDTP signaling channel >>")
                    spec = ClassicChannelSpec(AVDTP_PSM)
                    assert spec.psm is not None

                    # Find a free CID for a new channel
                    connection_channels = self.channels.setdefault(connection.handle, {})
                    source_cid = self.find_free_br_edr_cid(connection_channels)
                    assert source_cid is not None

                    # Create the channel
                    logger.debug(
                        f'creating client channel with cid={source_cid} for psm {spec.psm}')
                    channel = TestClassicChannel(
                        self,
                        connection,
                        L2CAP_SIGNALING_CID,
                        AVDTP_PSM,
                        source_cid,
                        spec.mtu,
                    )
                    connection_channels[source_cid] = channel

                    def on_channel_open():
                        # Initiate AVDTP with connected L2CAP signaling channel
                        nonlocal wait_for_l2cap_open
                        wait_for_l2cap_open.set_result(channel)

                    channel.on('open', on_channel_open)
                    channel.test_connect(connection, cid, request)
                    return

                super().on_l2cap_connection_request(connection, cid, request)

        handle = 0x00010001
        self.ref1.device.sdp_service_records = {handle: make_audio_sink_service_sdp_records(handle)}

        # Override L2CAP Channel Manager to control signaling
        self.ref1.device.l2cap_channel_manager = TestChannelManager(self.ref1.device)

        # Create listener on RD1 for initial incoming AVDT connection from DUT
        self.ref1_a2dp = Listener.for_device(self.ref1.device)

        logger.info("<< 1. RD1 waits for connection request from DUT >>")

        # Connect and pair DUT -> RD1.
        dut_ref1, ref1_dut = await asyncio.gather(
            initiate_pairing(self.dut, self.ref1.address),
            accept_pairing(self.ref1, self.dut.address),
        )

        # Wait until RD1 will initiate and open L2CAP channel for AVDTP
        channel = await asyncio.wait_for(wait_for_l2cap_open, timeout=10.0)

        logger.info(
            "<< 5. DUT closed initiated connection and allowed for the incoming to proceed. RD1 opens AVDT connection >>"
        )

        protocol = Protocol(channel)
        sink = protocol.add_sink(sbc_codec_capabilities())
        endpoints = await protocol.discover_remote_endpoints()
        logger.debug(f"endpoints: {endpoints}")
        assert endpoints
        remote_source = list(endpoints)[0]
        assert remote_source.in_use == 0
        assert remote_source.media_type == AVDTP_AUDIO_MEDIA_TYPE
        assert remote_source.tsep == AVDTP_TSEP_SRC
        logger.debug(f"remote_source: {remote_source}")

        sink.configuration = [
            MediaCodecCapabilities(
                media_type=AVDTP_AUDIO_MEDIA_TYPE,
                media_codec_type=A2DP_SBC_CODEC_TYPE,
                media_codec_information=SbcMediaCodecInformation.from_lists(
                    sampling_frequencies=[44100],
                    channel_modes=[SBC_JOINT_STEREO_CHANNEL_MODE],
                    block_lengths=[16],
                    subbands=[8],
                    allocation_methods=[SBC_LOUDNESS_ALLOCATION_METHOD],
                    minimum_bitpool_value=2,
                    maximum_bitpool_value=53,
                ),
            )
        ]

        # Start waiting for DUT A2DP source configured and connected
        wait_source = self.dut_a2dp.WaitSource(connection=dut_ref1)

        # Open stream
        stream = Stream(protocol, sink, remote_source)
        protocol.streams[sink.seid] = stream
        await stream.configure()
        await stream.open()

        # Check that DUT source is configured and connected
        result = await wait_source
        assert result.source

        logger.info("<< 6. DUT A2DP source configured and connected >>")

    @avatar.asynchronous
    @enableFlag(AVDT_WAIT_FOR_INITIAL_DELAY_REPORT_AS_INITIATOR)
    async def test_avdt_wait_before_sending_open_command__no_delay_report_sent(self) -> None:
        """Test if AOSP DUT will wait for 2 seconds before sending AVDT Open command.
        DUT should wait for that time to allow RD1 sink device to send AVDT Delay Report command
        before it receives Open command. If the RD1 will send AVDT Delay Report the Open command
        will be sent immediately after. In this test the AVDT Delay Report is not sent.

        1. Pair and connect RD1
        2. Setup the acceptor expectations on signalling channel
        3. Wait for the RD1 device to send the set configuration response and start timer
        4. Receive open command and assert that it was received after 2s from the timer start
        5. Start streaming - to confirm channel established properly
        6. Stop streaming - to confirm channel established properly
        """

        # Connect and pair RD1.
        dut_ref1, ref1_dut = await asyncio.gather(
            initiate_pairing(self.dut, self.ref1.address),
            accept_pairing(self.ref1, self.dut.address),
        )

        connection = pandora_snippet.get_raw_connection(device=self.ref1, connection=ref1_dut)
        assert connection is not None, "Unable to find connection!"
        channel = SignalingChannel.accept(connection)

        async def accept_open(channel: SignalingChannel):
            seid_information = [
                SeidInformation(acp_seid=0x01, tsep=Tsep.SINK, media_type=AVDTP_AUDIO_MEDIA_TYPE)
            ]
            sbc_capabilites = sbc_service_capabilites()
            sbc_capabilites.append(DelayReportingCapability())

            await channel.wait_signaling_channel_connected()
            await channel.accept_discover(seid_information)
            await channel.accept_get_all_capabilities(sbc_capabilites)
            await channel.accept_set_configuration(expected_configuration=[
                MediaTransportCapability(), ANY,
                DelayReportingCapability()
            ])

            start_time = time.perf_counter()

            cmd = await channel.expect_signal(OpenCommand(transaction_label=ANY, acp_seid=ANY))

            elapsed_time = time.perf_counter() - start_time
            assert_greater_equal(elapsed_time, 2.0)

            channel.send_signal(OpenResponse(transaction_label=cmd.transaction_label))

        # Connect AVDTP to RD1.
        _, dut_ref1_source = await asyncio.gather(accept_open(channel),
                                                  open_source(self.dut_a2dp, dut_ref1))

        # Start streaming to RD1.
        await asyncio.gather(self.dut_a2dp.Start(source=dut_ref1_source), channel.accept_start())

        generated_audio = generate_sine(source=dut_ref1_source, duration_s=4.0)
        await self.dut_a2dp.PlaybackAudio(generated_audio)

        # Verify that at least one audio frame is received on the transport channel.
        await channel.expect_media(timeout=5.0)

        # Stop streaming to RD1.
        await asyncio.gather(self.dut_a2dp.Suspend(source=dut_ref1_source),
                             channel.accept_suspend(timeout=8.0))

    @avatar.asynchronous
    @enableFlag(AVDT_WAIT_FOR_INITIAL_DELAY_REPORT_AS_INITIATOR)
    async def test_avdt_wait_before_sending_open_command__delay_report_sent(self) -> None:
        """Test if AOSP DUT will wait for 2 seconds before sending AVDT Open command.
        DUT should wait for that time to allow REF sink device to send AVDT Delay Report command
        before it receives Open command. If the REF will send AVDT Delay Report the Open command
        will be sent immediately after. In this test the AVDT Delay Report is sent.

        1. Pair and connect RD1
        2. Setup the acceptor expectations on signalling channel
        3. Wait for the RD1 device to send the set configuration response and start timer
        4. Wait for the RD1 device to send AVDT Delay Report and expect response
        5. Receive open command on RD1 and assert that it was received before 2s from the timer start
        6. Start streaming - to confirm channel established properly
        7. Stop streaming - to confirm channel established properly
        """

        # Connect and pair RD1.
        dut_ref1, ref1_dut = await asyncio.gather(
            initiate_pairing(self.dut, self.ref1.address),
            accept_pairing(self.ref1, self.dut.address),
        )

        connection = pandora_snippet.get_raw_connection(device=self.ref1, connection=ref1_dut)
        assert connection is not None, "Unable to find connection!"
        channel = SignalingChannel.accept(connection)

        async def accept_open(channel: SignalingChannel):
            seid_information = [
                SeidInformation(acp_seid=0x01, tsep=Tsep.SINK, media_type=AVDTP_AUDIO_MEDIA_TYPE)
            ]
            sbc_capabilites = sbc_service_capabilites()
            sbc_capabilites.append(DelayReportingCapability())

            await channel.wait_signaling_channel_connected()
            await channel.accept_discover(seid_information)
            await channel.accept_get_all_capabilities(sbc_capabilites)
            await channel.accept_set_configuration(expected_configuration=[
                MediaTransportCapability(), ANY,
                DelayReportingCapability()
            ])

            start_time = time.perf_counter()

            await channel.initiate_delay_report()

            cmd = await channel.expect_signal(OpenCommand(transaction_label=ANY, acp_seid=ANY))

            elapsed_time = time.perf_counter() - start_time
            assert_less_equal(elapsed_time, 2.0)

            channel.send_signal(OpenResponse(transaction_label=cmd.transaction_label))

        # Connect AVDTP to RD1.
        _, dut_ref1_source = await asyncio.gather(accept_open(channel),
                                                  open_source(self.dut_a2dp, dut_ref1))

        # Start streaming to RD1.
        await asyncio.gather(self.dut_a2dp.Start(source=dut_ref1_source), channel.accept_start())

        generated_audio = generate_sine(source=dut_ref1_source, duration_s=4.0)
        await self.dut_a2dp.PlaybackAudio(generated_audio)

        # Verify that at least one audio frame is received on the transport channel.
        await channel.expect_media(timeout=5.0)

        # Stop streaming to RD1.
        await asyncio.gather(self.dut_a2dp.Suspend(source=dut_ref1_source),
                             channel.accept_suspend(timeout=8.0))

    @avatar.asynchronous
    async def test_dut_disconnects_after_no_avdt_start_response(self) -> None:
        """Test that DUT disconnects L2CAP Channel after no response for AVDT Start for 15 seconds.

        1. Pair and Connect RD1
        2. Setup the acceptor expectations on signalling channel
        2. Start streaming
        4. Simulate no response for 15 seconds and expect AVDT Signalling L2CAP Channel disconnection
        """

        # Connect and pair RD1.
        dut_ref1, ref1_dut = await asyncio.gather(
            initiate_pairing(self.dut, self.ref1.address),
            accept_pairing(self.ref1, self.dut.address),
        )

        # Create a listener to wait for AVDT L2CAP channel disconnection
        avdtp_future = asyncio.get_running_loop().create_future()

        # Create a wrapper to catch the L2CAP Channel disconnection
        def catch_on_disconnection_request(original_request):

            def wrapper(self, *args, **kwargs):
                logger.info("<< Received AVDT Signalling L2CAP Channel Disconnection  >>")
                nonlocal avdtp_future
                avdtp_future.set_result(None)

                result = original_request(self, *args, **kwargs)

                return result

            return wrapper

        connection = pandora_snippet.get_raw_connection(device=self.ref1, connection=ref1_dut)
        assert connection is not None, "Unable to find connection!"
        channel = SignalingChannel.accept(connection)

        seid_information = [
            SeidInformation(acp_seid=0x01, tsep=Tsep.SINK, media_type=AVDTP_AUDIO_MEDIA_TYPE)
        ]

        # Connect AVDTP to RD1.
        _, dut_ref1_source = await asyncio.gather(
            channel.accept_open_stream(seid_information=seid_information,
                                       service_capabilities=sbc_service_capabilites()),
            open_source(self.dut_a2dp, dut_ref1))

        assert channel.signaling_channel is not None
        channel.signaling_channel.on_disconnection_request = catch_on_disconnection_request(  # type: ignore[method-assign]
            channel.signaling_channel.on_disconnection_request.__get__(
                channel.signaling_channel, ClassicChannel))

        # Start streaming to RD1.
        self.dut_a2dp.Start(source=dut_ref1_source)

        # Expect AVDT Start on RD1.
        await channel.expect_signal(StartCommand(transaction_label=ANY, acp_seid=ANY))

        # Simulate no response for 15 seconds and wait for AVDT Singalling L2CAP Channel disconnect
        await asyncio.gather(asyncio.sleep(15), asyncio.wait_for(avdtp_future, timeout=20.0))

    @avatar.asynchronous
    async def test_sink_as_initiator__no_reconnect_after_acl_disconnect(self) -> None:
        """Test that Android DUT does not retry connection when remote started AVDT and disconnected ACL.

        1. Pair and Connect RD1 -> DUT
        2. Initiate AVDT RD1 -> DUT
        3. Disconnect ACL RD1 -> DUT
        4. Check that DUT does not retry connection
        """
        # 1. Pair and Connect RD1 -> DUT
        ref1_dut, dut_ref1 = await asyncio.gather(
            initiate_pairing(self.ref1, self.dut.address),
            accept_pairing(self.dut, self.ref1.address),
        )

        # 2. Initiate AVDT RD1 -> DUT
        connection = pandora_snippet.get_raw_connection(device=self.ref1, connection=ref1_dut)
        assert connection is not None, "Unable to find connection!"
        await SignalingChannel.initiate(connection)

        # 3. Disconnect ACL RD1 -> DUT
        await self.ref1.aio.host.Disconnect(connection=ref1_dut)
        await self.dut.aio.host.WaitDisconnection(connection=dut_ref1, timeout=5)

        # 4. Check that DUT does not retry connection
        with assert_raises(asyncio.TimeoutError):
            await asyncio.wait_for(
                self.ref1.aio.host.WaitConnection(address=self.dut.address, timeout=15), 10.0)
        logger.info(
            "No new connection for 10 seconds on DUT. accept_signalling_timer properly canceled.")

    @avatar.asynchronous
    async def test_codec_reconfiguration(self) -> None:
        """Basic A2DP connection with SignalingChannel and codec reconfiguration while streaming.

        1. Pair and Connect RD1
        2. Setup the acceptor expectations on signalling channel
        3. Start streaming
        4. Reconfigure codec from AAC to SBC
        4. Check the codec reconfigured and stream resumed
        """

        # Connect and pair RD1.
        dut_ref1, ref1_dut = await asyncio.gather(
            initiate_pairing(self.dut, self.ref1.address),
            accept_pairing(self.ref1, self.dut.address),
        )

        connection = pandora_snippet.get_raw_connection(device=self.ref1, connection=ref1_dut)
        assert connection is not None, "Unable to find connection!"
        channel = SignalingChannel.accept(connection)

        async def accept_open_stream_with_aac(channel: SignalingChannel):
            seid_information = [
                SeidInformation(acp_seid=0x01, tsep=Tsep.SINK, media_type=AVDTP_AUDIO_MEDIA_TYPE),
                SeidInformation(acp_seid=0x02, tsep=Tsep.SINK, media_type=AVDTP_AUDIO_MEDIA_TYPE)
            ]

            acceptor_configuration_aac = [
                MediaTransportCapability(),
                MediaCodecCapability(
                    service_category=ServiceCategory.MEDIA_CODEC,
                    media_type=0x00,  # Audio
                    media_codec_type=0x02,  # AAC
                    media_codec_specific_information_elements=ANY)
            ]

            await channel.wait_signaling_channel_connected()
            await channel.accept_discover(seid_information)
            await channel.accept_get_all_capabilities(sbc_service_capabilites())
            await channel.accept_get_all_capabilities(aac_service_capabilites())
            await channel.accept_set_configuration(acceptor_configuration_aac)
            await channel.accept_open()

        # Connect AVDTP to RD1.
        _, dut_ref1_source = await asyncio.gather(accept_open_stream_with_aac(channel),
                                                  open_source(self.dut_a2dp, dut_ref1))

        # Start streaming to RD1.
        await asyncio.gather(self.dut_a2dp.Start(source=dut_ref1_source), channel.accept_start())

        # Verify that audio is received on the transport channel.
        generated_audio = generate_sine(source=dut_ref1_source, duration_s=4.0)
        self.dut_a2dp.PlaybackAudio(generated_audio)
        logger.info(f"Receive AAC audio data.")
        await channel.receive_audio_data(test_log_path=self.log_path,
                                         filename="aac",
                                         duration_s=1.0)
        logger.info(f"Finished receiving AAC audio data.")

        # Get current codec status
        configurationResponse = await self.dut_a2dp.GetConfiguration(connection=dut_ref1)
        logger.info(f"Current codec configuration: {configurationResponse.configuration}")
        assert configurationResponse.configuration.id.HasField('mpeg_aac')

        new_configuration = Configuration(id=CodecId(sbc=empty_pb2.Empty()),
                                          parameters=CodecParameters(channel_mode=STEREO,
                                                                     sampling_frequency_hz=44100,
                                                                     bit_depth=16))

        async def handle_reconfiguration(channel: SignalingChannel):
            logger.info(f"Waiting for suspend")
            await channel.accept_suspend()

            # Discard the received audio data from internal queue
            channel.discard_audio_data()

            logger.info(f"Waiting for close")
            await channel.accept_close()

            acceptor_configuration_sbc = [
                MediaTransportCapability(),
                MediaCodecCapability(
                    service_category=ServiceCategory.MEDIA_CODEC,
                    media_type=0x00,  # Audio
                    media_codec_type=0x00,  # SBC
                    media_codec_specific_information_elements=ANY)
            ]
            logger.info(f"Waiting for set configuration")
            await channel.accept_set_configuration(acceptor_configuration_sbc)
            logger.info(f"Waiting for open")
            await channel.accept_open()
            logger.info(f"Waiting for start")
            await channel.accept_start(timeout=8.0)

        # Set new codec
        logger.info(f"Switching to codec: {new_configuration}")
        await asyncio.gather(
            self.dut_a2dp.SetConfiguration(connection=dut_ref1, configuration=new_configuration),
            handle_reconfiguration(channel))

        # Get current codec status
        configurationResponse = await self.dut_a2dp.GetConfiguration(connection=dut_ref1)
        logger.info(f"Current codec configuration: {configurationResponse.configuration}")
        assert configurationResponse.configuration.id.HasField('sbc')

        logger.info(f"Receive SBC audio data.")
        await channel.receive_audio_data(test_log_path=self.log_path,
                                         filename="sbc",
                                         duration_s=1.0)
        logger.info(f"Finished receiving SBC audio data.")

        # # Stop streaming to RD1.
        await asyncio.gather(self.dut_a2dp.Suspend(source=dut_ref1_source),
                             channel.accept_suspend(timeout=8.0))

    @avatar.asynchronous
    async def test_delay_report_after_full_codec_reconfiguration(self) -> None:
        """Test if AOSP properly sets configuration when the peer device supports/doesn't support
           delay report service capability.

        1. Connect, pair, open AVDTP and disconnect with remote REF1 - delay report supported by REF1
        2. Connect, open AVDTP and disconnect with remote REF1 - delay report not supported by REF1
        3. Connect, open AVDTP and disconnect with remote REF1 - delay report supported by REF1
        4. Connect and open AVDTP with remote REF1 - delay report not supported by REF1
        5. Reconfigure codec AAC to SBC - delay report not supported by both codecs
        6. Connect, open AVDTP and disconnect with remote REF1 - delay report supported by REF1
        """

        seid_information = [
            SeidInformation(acp_seid=0x01, tsep=Tsep.SINK, media_type=AVDTP_AUDIO_MEDIA_TYPE),
            SeidInformation(acp_seid=0x02, tsep=Tsep.SINK, media_type=AVDTP_AUDIO_MEDIA_TYPE)
        ]

        async def connect_and_pair() -> tuple[SignalingChannel, Connection, Connection]:
            logger.info("connect ACL and pair")
            # Connect and pair RD1.
            dut_ref1, ref1_dut = await asyncio.gather(
                initiate_pairing(self.dut, self.ref1.address),
                accept_pairing(self.ref1, self.dut.address),
            )
            logger.info("connect signaling channel")
            connection = pandora_snippet.get_raw_connection(device=self.ref1, connection=ref1_dut)
            assert connection is not None, "Unable to find connection!"
            channel = SignalingChannel.accept(connection)
            return channel, dut_ref1, ref1_dut

        async def reconnect() -> tuple[SignalingChannel, Awaitable[ConnectResponse], Connection]:
            logger.info("connect ACL")
            connect_awaitable = self.dut.aio.host.Connect(address=self.ref1.address)
            result = await self.ref1.aio.host.WaitConnection(address=self.dut.address)
            ref1_dut = result.connection
            assert ref1_dut
            logger.info("connect signaling channel")
            connection = pandora_snippet.get_raw_connection(device=self.ref1, connection=ref1_dut)
            assert connection is not None, "Unable to find connection!"
            channel = SignalingChannel.accept(connection)
            return channel, connect_awaitable, ref1_dut

        async def disconnect(dut_ref1: Connection, ref1_dut: Connection):
            logger.info("disconnect ACL")
            await self.dut.aio.host.Disconnect(connection=dut_ref1)
            await self.ref1.aio.host.WaitDisconnection(connection=ref1_dut, timeout=5)
            logger.info("disconnected ACL")

        async def accept_open_with_delay_report(channel: SignalingChannel):
            sbc_capabilities = sbc_service_capabilites()
            sbc_capabilities.append(DelayReportingCapability())
            aac_capabilities = aac_service_capabilites()
            aac_capabilities.append(DelayReportingCapability())
            logger.info("connect_with_delay_report: waiting for channel to be established")
            await channel.wait_signaling_channel_connected()
            logger.info("connect_with_delay_report: waiting for discover")
            await channel.accept_discover(seid_information)
            logger.info("connect_with_delay_report: waiting for accept_get_all_capabilities")
            await channel.accept_get_all_capabilities(sbc_capabilities)
            logger.info("connect_with_delay_report: waiting for accept_get_all_capabilities")
            await channel.accept_get_all_capabilities(aac_capabilities)
            logger.info("connect_with_delay_report: waiting for accept_set_configuration")
            await channel.accept_set_configuration(expected_configuration=[
                MediaTransportCapability(), ANY,
                DelayReportingCapability()
            ])
            logger.info("connect_with_delay_report: initiate delay report")
            await channel.initiate_delay_report()
            logger.info("connect_with_delay_report: waiting for accept_open")
            await channel.accept_open()

        async def accept_open_without_delay_report(channel: SignalingChannel):
            logger.info("connect_without_delay_report: waiting for channel to be established")
            await channel.wait_signaling_channel_connected()
            logger.info("connect_without_delay_report: waiting for discover")
            await channel.accept_discover(seid_information)
            logger.info("connect_without_delay_report: waiting for accept_get_all_capabilities")
            await channel.accept_get_all_capabilities(sbc_service_capabilites())
            logger.info("connect_without_delay_report: waiting for accept_get_all_capabilities")
            await channel.accept_get_all_capabilities(aac_service_capabilites())
            logger.info("connect_without_delay_report: waiting for accept_set_configuration")
            await channel.accept_set_configuration(
                expected_configuration=[MediaTransportCapability(), ANY])
            logger.info("connect_without_delay_report: waiting for accept_open")
            await channel.accept_open()

        async def handle_reconfiguration(channel: SignalingChannel):
            logger.info("handle_reconfiguration: waiting for close")
            await channel.accept_close()
            acceptor_configuration_sbc = [MediaTransportCapability(), ANY]
            logger.info("handle_reconfiguration: waiting for set configuration")
            await channel.accept_set_configuration(
                expected_configuration=[MediaTransportCapability(), ANY])
            logger.info("handle_reconfiguration: waiting for open")
            await channel.accept_open()

        # 1. Validate connection with remote supporting delay report
        channel, dut_ref1, ref1_dut = await connect_and_pair()
        logger.info("channel: %s, dut_ref1: %s, ref1_dut: %s", channel, dut_ref1, ref1_dut)
        await asyncio.gather(accept_open_with_delay_report(channel),
                             open_source(self.dut_a2dp, dut_ref1))
        await disconnect(dut_ref1, ref1_dut)

        # 2. Validate connection with remote not supporting delay report
        channel, connect_awaitable, ref1_dut = await reconnect()
        await accept_open_without_delay_report(channel)
        result = await connect_awaitable
        assert result.connection is not None, "connection is None!"
        await disconnect(result.connection, ref1_dut)

        # 3. Validate connection with remote supporting delay report
        channel, connect_awaitable, ref1_dut = await reconnect()
        await accept_open_with_delay_report(channel)
        result = await connect_awaitable
        assert result.connection is not None, "connection is None!"
        await disconnect(result.connection, ref1_dut)

        # 4. Connect with remote device not supporting delay report
        channel, connect_awaitable, ref1_dut = await reconnect()
        await accept_open_without_delay_report(channel)
        result = await connect_awaitable
        assert result.connection is not None, "connection is None!"
        dut_ref1 = result.connection

        # 5. Reconfigure codec AAC to SBC (delay report not supported)
        configurationResponse = await self.dut_a2dp.GetConfiguration(connection=dut_ref1)
        logger.info("Current codec configuration: %s", configurationResponse.configuration)
        assert configurationResponse.configuration.id.HasField('mpeg_aac')

        new_configuration = Configuration(id=CodecId(sbc=empty_pb2.Empty()),
                                          parameters=CodecParameters(channel_mode=STEREO,
                                                                     sampling_frequency_hz=44100,
                                                                     bit_depth=16))

        logger.info("Switching to codec: %s", new_configuration)
        await asyncio.gather(
            self.dut_a2dp.SetConfiguration(connection=dut_ref1, configuration=new_configuration),
            handle_reconfiguration(channel))

        configurationResponse = await self.dut_a2dp.GetConfiguration(connection=dut_ref1)
        logger.info("Current codec configuration: %s", configurationResponse.configuration)
        assert configurationResponse.configuration.id.HasField('sbc')
        await disconnect(dut_ref1, ref1_dut)

        # 6. Connect to remote supporting delay report
        channel, connect_awaitable, ref1_dut = await reconnect()
        await accept_open_with_delay_report(channel)
        result = await connect_awaitable
        assert result.connection is not None, "connection is None!"
        await disconnect(result.connection, ref1_dut)


if __name__ == '__main__':
    logging.basicConfig(level=logging.DEBUG)
    test_runner.main()  # type: ignore
