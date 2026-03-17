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
import math
import struct
import bumble.avdtp
import bumble.device
import avatar
import bumble
import dataclasses
import logging
import time
from unittest.mock import ANY

from a2dp.packets.avdtp import (
    ErrorCode,
    SeidInformation,
    Tsep,
    ServiceCategory,
    MediaCodecCapability,
    ServiceCapability,
    MediaTransportCapability,
    ContentProtectionCapability,
    DelayReportingCapability,
    OpenCommand,
    OpenResponse,
    StartCommand,
    StartReject,
    SuspendCommand,
    SuspendReject,
    DiscoverCommand,
    DiscoverResponse,
)
from a2dp.signaling_channel import SignalingChannel
from avatar import BumblePandoraDevice, PandoraDevice, PandoraDevices, pandora_snippet, enableFlag
from bumble.a2dp import (
    A2DP_MPEG_2_4_AAC_CODEC_TYPE,
    A2DP_SBC_CODEC_TYPE,
    AacMediaCodecInformation,
    SbcMediaCodecInformation,
    make_audio_sink_service_sdp_records,
)
from bumble.avctp import AVCTP_PSM
from bumble.avdtp import (
    AVDTP_AUDIO_MEDIA_TYPE,
    AVDTP_PSM,
    AVDTP_TSEP_SRC,
    Listener,
    MediaCodecCapabilities,
    Protocol,
    Stream,
    State,
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
from google.protobuf import empty_pb2
from mobly import base_test, test_runner, signals
from mobly.asserts import assert_equal  # type: ignore
from mobly.asserts import assert_greater_equal  # type: ignore
from mobly.asserts import assert_in  # type: ignore
from mobly.asserts import assert_less_equal  # type: ignore
from mobly.asserts import assert_raises  # type: ignore
from pandora.a2dp_grpc_aio import A2DP
from pandora.a2dp_pb2 import STEREO, CodecId, CodecParameters, Configuration, PlaybackAudioRequest, Source
from pandora.host_pb2 import Connection, ConnectResponse
from pandora.security_pb2 import LEVEL2
from typing import AsyncIterator, Awaitable, Optional
from typing_extensions import override

logger = logging.getLogger(__name__)

AUDIO_SIGNAL_AMPLITUDE = 0.8
AUDIO_SIGNAL_FREQUENCY = 440
AUDIO_SIGNAL_PAN_VALUE = 0.5  # 0.0 (left) to 1.0 (right)
AUDIO_SIGNAL_SAMPLING_RATE = 44100
AUDIO_SIGNAL_SINE_DURATION = 0.1
MAX_INT16 = 2**15 - 1

A2DP_OFFLOAD_USER_CODEC_SELECTION_FLAG = "com.android.bluetooth.flags.a2dp_offload_user_codec_selection"

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
        media_codec_information=SbcMediaCodecInformation(
            sampling_frequency=(SbcMediaCodecInformation.SamplingFrequency.SF_16000 |
                                SbcMediaCodecInformation.SamplingFrequency.SF_32000 |
                                SbcMediaCodecInformation.SamplingFrequency.SF_44100 |
                                SbcMediaCodecInformation.SamplingFrequency.SF_48000),
            channel_mode=(SbcMediaCodecInformation.ChannelMode.STEREO |
                          SbcMediaCodecInformation.ChannelMode.DUAL_CHANNEL |
                          SbcMediaCodecInformation.ChannelMode.JOINT_STEREO |
                          SbcMediaCodecInformation.ChannelMode.MONO),
            block_length=(SbcMediaCodecInformation.BlockLength.BL_4 |
                          SbcMediaCodecInformation.BlockLength.BL_8 |
                          SbcMediaCodecInformation.BlockLength.BL_12 |
                          SbcMediaCodecInformation.BlockLength.BL_16),
            subbands=(SbcMediaCodecInformation.Subbands.S_4 |
                      SbcMediaCodecInformation.Subbands.S_8),
            allocation_method=(SbcMediaCodecInformation.AllocationMethod.LOUDNESS |
                               SbcMediaCodecInformation.AllocationMethod.SNR),
            minimum_bitpool_value=2,
            maximum_bitpool_value=53,
        ),
    )


def sbc_service_capabilites() -> list[ServiceCapability]:
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
        media_codec_information=AacMediaCodecInformation(
            object_type=AacMediaCodecInformation.ObjectType.MPEG_2_AAC_LC,
            sampling_frequency=(AacMediaCodecInformation.SamplingFrequency.SF_44100 |
                                AacMediaCodecInformation.SamplingFrequency.SF_48000),
            channels=(AacMediaCodecInformation.Channels.MONO |
                      AacMediaCodecInformation.Channels.STEREO),
            vbr=1,
            bitrate=256000,
        ),
    )


def aac_service_capabilites() -> list[ServiceCapability]:
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
    samples_per_frame = int(AUDIO_SIGNAL_SAMPLING_RATE * AUDIO_SIGNAL_SINE_DURATION)
    num_frames = int(duration_s / AUDIO_SIGNAL_SINE_DURATION)

    right_amplitude = math.sqrt(AUDIO_SIGNAL_PAN_VALUE)
    left_amplitude = math.sqrt(1.0 - AUDIO_SIGNAL_PAN_VALUE)

    for i in range(num_frames):
        frame_data = bytearray()
        for j in range(samples_per_frame):
            sample_index = i * samples_per_frame + j
            time_s = sample_index / AUDIO_SIGNAL_SAMPLING_RATE
            sine_value = AUDIO_SIGNAL_AMPLITUDE * math.sin(
                2 * math.pi * AUDIO_SIGNAL_FREQUENCY * time_s)

            left_sample = int(sine_value * left_amplitude * MAX_INT16)
            right_sample = int(sine_value * right_amplitude * MAX_INT16)

            frame_data += struct.pack('<hh', left_sample, right_sample)

        yield PlaybackAudioRequest(source=source, data=bytes(frame_data))


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
    @enableFlag(A2DP_OFFLOAD_USER_CODEC_SELECTION_FLAG)
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
        assert_in(self.ref1_a2dp_sink.stream.state, [State.OPEN, State.STREAMING])

        # Start streaming to RD1.
        await self.dut_a2dp.Start(source=dut_ref1_source)

        generated_audio = generate_sine(source=dut_ref1_source, duration_s=4.0)
        await self.dut_a2dp.PlaybackAudio(generated_audio)
        assert_equal(self.ref1_a2dp_sink.stream.state, State.STREAMING)

        # Stop streaming to RD1.
        await self.dut_a2dp.Suspend(source=dut_ref1_source)
        assert_equal(self.ref1_a2dp_sink.stream.state, State.OPEN)

    @avatar.asynchronous
    @enableFlag(A2DP_OFFLOAD_USER_CODEC_SELECTION_FLAG)
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
                             channel.accept_suspend())

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

        def on_avdtp_connection(server: bumble.avdtp.Protocol):
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
    @enableFlag(A2DP_OFFLOAD_USER_CODEC_SELECTION_FLAG)
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
        assert_in(self.ref2_a2dp_sink.stream.state, [State.OPEN, State.STREAMING])

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
        assert_in(self.ref2_a2dp_sink.stream.state, [State.OPEN, State.STREAMING])

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
        assert_in(self.ref2_a2dp_sink.stream.state, [State.OPEN, State.STREAMING])

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
    @enableFlag(A2DP_OFFLOAD_USER_CODEC_SELECTION_FLAG)
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
        await channel.expect_media()

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
    @enableFlag(A2DP_OFFLOAD_USER_CODEC_SELECTION_FLAG)
    async def test_avdt_open_after_timeout(self) -> None:
        """Test AVDTP automatically opens stream after timeout if peer device only configures codec.

        1. Pair and Connect RD1 -> DUT
        2. Connect AVDTP RD1 -> DUT but do not send AVDT Open Command
        3. Check that the DUT will abort and reopen the AVDTP as initiator
        """

        # Create a listener to wait for AVDTP open
        avdtp_future = asyncio.get_running_loop().create_future()

        class TestAvdtProtocol(Protocol):

            @override
            def on_open_command(self, command: bumble.avdtp.Open_Command):
                logger.info("<< AVDTP Open received >>")
                avdtp_future.set_result(None)
                return super().on_open_command(command)

        # Connect and pair RD1.
        ref1_dut, dut_ref1 = await asyncio.gather(
            initiate_pairing(self.ref1, self.dut.address),
            accept_pairing(self.dut, self.ref1.address),
        )

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
            media_codec_information=SbcMediaCodecInformation(
                sampling_frequency=SbcMediaCodecInformation.SamplingFrequency.SF_44100,
                channel_mode=SbcMediaCodecInformation.ChannelMode.JOINT_STEREO,
                block_length=SbcMediaCodecInformation.BlockLength.BL_16,
                subbands=SbcMediaCodecInformation.Subbands.S_8,
                allocation_method=SbcMediaCodecInformation.AllocationMethod.LOUDNESS,
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

        # Prepare a function to call when expecting DUT to connect AVDTP
        def dut_open_source() -> None:
            pass

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
                            result=L2CAP_Connection_Response.Result.
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

            def on_connection_response(self, response: L2CAP_Connection_Response) -> None:
                assert_equal(self.state, self.State.WAIT_CONNECT_RSP)
                assert_equal(response.result,
                             L2CAP_Connection_Response.Result.CONNECTION_SUCCESSFUL)
                self.destination_cid = response.destination_cid
                self._change_state(self.State.WAIT_CONFIG)
                logger.info("<< 2. RD1 connected DUT, configuration postponed >>")

            def on_configure_request(self, request: L2CAP_Configure_Request) -> None:
                nonlocal pending_configuration_request
                if pending_configuration_request is not None:
                    logger.info("<< 3. Block RD1 until DUT tries AVDTP channel connection >>")
                    pending_configuration_request.connection = self.connection
                    pending_configuration_request.cid = self.source_cid
                    pending_configuration_request.request = request
                    dut_open_source()
                else:
                    super().on_configure_request(request)

        # Override L2CAP Channel Manager to control signaling
        self.ref1.device.l2cap_channel_manager = TestChannelManager(self.ref1.device)
        # Connect and pair DUT -> RD1.
        dut_ref1, ref1_dut = await asyncio.gather(
            initiate_pairing(self.dut, self.ref1.address),
            accept_pairing(self.ref1, self.dut.address),
        )

        def dut_open_source() -> None:  # type: ignore[no-redef]
            self.dut_a2dp.OpenSource(connection=dut_ref1)

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
            spec,
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
                            result=L2CAP_Connection_Response.Result.
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
                        spec,
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

        # Start AVDT connection from DUT
        self.dut_a2dp.OpenSource(connection=dut_ref1)

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
                media_codec_information=SbcMediaCodecInformation(
                    sampling_frequency=SbcMediaCodecInformation.SamplingFrequency.SF_44100,
                    channel_mode=SbcMediaCodecInformation.ChannelMode.JOINT_STEREO,
                    block_length=SbcMediaCodecInformation.BlockLength.BL_16,
                    subbands=SbcMediaCodecInformation.Subbands.S_8,
                    allocation_method=SbcMediaCodecInformation.AllocationMethod.LOUDNESS,
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
    @enableFlag(A2DP_OFFLOAD_USER_CODEC_SELECTION_FLAG)
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
        await channel.expect_media()

        # Stop streaming to RD1.
        await asyncio.gather(self.dut_a2dp.Suspend(source=dut_ref1_source),
                             channel.accept_suspend())

    @avatar.asynchronous
    @enableFlag(A2DP_OFFLOAD_USER_CODEC_SELECTION_FLAG)
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
        await channel.expect_media()

        # Stop streaming to RD1.
        await asyncio.gather(self.dut_a2dp.Suspend(source=dut_ref1_source),
                             channel.accept_suspend())

    @avatar.asynchronous
    @enableFlag(A2DP_OFFLOAD_USER_CODEC_SELECTION_FLAG)
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
    @enableFlag(A2DP_OFFLOAD_USER_CODEC_SELECTION_FLAG)
    async def test_codec_reconfiguration(self) -> None:
        """Basic A2DP connection with SignalingChannel and codec reconfiguration while streaming.

        1. Pair and Connect RD1
        2. Setup the acceptor expectations on signalling channel
        3. Start streaming
        4. Reconfigure codec from AAC to SBC
        5. Check the codec reconfigured and stream resumed
        """

        logger.info("<< 1. Pair and Connect RD1 >>")
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

        logger.info("<< 2. Setup the acceptor expectations on signalling channel >>")
        # Connect AVDTP to RD1.
        _, dut_ref1_source = await asyncio.gather(accept_open_stream_with_aac(channel),
                                                  open_source(self.dut_a2dp, dut_ref1))

        logger.info("<< 3. Start streaming >>")
        # Start streaming to RD1.
        await asyncio.gather(self.dut_a2dp.Start(source=dut_ref1_source), channel.accept_start())

        # Verify that audio is received on the transport channel.
        generated_audio = generate_sine(source=dut_ref1_source, duration_s=4.0)
        self.dut_a2dp.PlaybackAudio(generated_audio)
        logger.debug("Receive AAC audio data.")
        await channel.receive_audio_data(test_log_path=self.log_path,
                                         filename="aac",
                                         duration_s=1.0)
        logger.debug("Finished receiving AAC audio data.")

        # Get current codec status
        configurationResponse = await self.dut_a2dp.GetConfiguration(connection=dut_ref1)
        logger.debug(f"Current codec configuration: {configurationResponse.configuration}")
        assert configurationResponse.configuration.id.HasField('mpeg_aac')

        new_configuration = Configuration(id=CodecId(sbc=empty_pb2.Empty()),
                                          parameters=CodecParameters(channel_mode=STEREO,
                                                                     sampling_frequency_hz=44100,
                                                                     bit_depth=16))

        async def handle_reconfiguration(channel: SignalingChannel):
            logger.info("handle_reconfiguration")
            await channel.accept_suspend()

            # Discard the received audio data from internal queue
            channel.discard_audio_data()

            await channel.accept_close()

            acceptor_configuration_sbc = [
                MediaTransportCapability(),
                MediaCodecCapability(
                    service_category=ServiceCategory.MEDIA_CODEC,
                    media_type=0x00,  # Audio
                    media_codec_type=0x00,  # SBC
                    media_codec_specific_information_elements=ANY)
            ]
            await channel.accept_set_configuration(acceptor_configuration_sbc)
            await channel.accept_open()
            await channel.accept_start()

        logger.info("4. Reconfigure codec from AAC to SBC")
        # Set new codec
        logger.debug(f"Switching to codec: {new_configuration}")
        await asyncio.gather(
            self.dut_a2dp.SetConfiguration(connection=dut_ref1, configuration=new_configuration),
            handle_reconfiguration(channel))

        logger.info("5. Check the codec reconfigured and stream resumed")
        # Get current codec status
        configurationResponse = await self.dut_a2dp.GetConfiguration(connection=dut_ref1)
        logger.debug(f"Current codec configuration: {configurationResponse.configuration}")
        assert configurationResponse.configuration.id.HasField('sbc')

        logger.debug("Receive SBC audio data.")
        await channel.receive_audio_data(test_log_path=self.log_path,
                                         filename="sbc",
                                         duration_s=1.0)
        logger.debug("Finished receiving SBC audio data.")

        # # Stop streaming to RD1.
        await asyncio.gather(self.dut_a2dp.Suspend(source=dut_ref1_source),
                             channel.accept_suspend())

    @avatar.asynchronous
    @enableFlag(A2DP_OFFLOAD_USER_CODEC_SELECTION_FLAG)
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

    @avatar.asynchronous
    @enableFlag(A2DP_OFFLOAD_USER_CODEC_SELECTION_FLAG)
    async def test_avdt_handle_start_cfm_bad_state_error(self) -> None:
        """Test AVDTP handling of start confirmation BAD_STATE error.

        Test steps after DUT and RD1 connected and paired:
        1. Start streaming to RD1.
        2. RD1 will simulate failure response - AVDTP_BAD_STATE.
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
        self.dut_a2dp.Start(source=dut_ref1_source)

        cmd = await channel.expect_signal(StartCommand(transaction_label=ANY, acp_seid=ANY),
                                          timeout=10.0)

        # Simulate AVDTP_BAD_STATE response.
        channel.send_signal(
            StartReject(transaction_label=cmd.transaction_label,
                        acp_seid=0x01,
                        error_code=ErrorCode.AVDTP_BAD_STATE))

        # Expect the DUT to close connection.
        await channel.accept_close(timeout=10.0)

    @avatar.asynchronous
    async def test_avdt_handles_stream_discover_response_in_open_state(self) -> None:
        """Test AVDTP handling stream discovery response in open state.

        Test steps after DUT and RD1 connected and paired:
        1. RD1 initiates AVDT signalling channel connection to DUT.
        2. RD1 configures AVDT.
        3. RD1 sets AVDT configuration and expects discover command from DUT.
        4. RD1 initiates AVDT open before responding to discover command.
        5. RD1 resposonds to discover command after media channel is opened.
        6. DUT should be able to get capabilities from RD1.
        """

        # Connect and pair RD1.
        dut_ref1, ref1_dut = await asyncio.gather(
            initiate_pairing(self.dut, self.ref1.address),
            accept_pairing(self.ref1, self.dut.address),
        )

        logger.info("1. RD1 initiates AVDT signalling channel connection to DUT")
        connection = pandora_snippet.get_raw_connection(device=self.ref1, connection=ref1_dut)
        assert connection is not None, "Unable to find connection!"
        channel = await SignalingChannel.initiate(connection)

        logger.info("2. RD1 configures AVDT")
        discover_rsp = await channel.initiate_discover()
        logger.debug(f"SEID information: {discover_rsp.seid_information}")

        service_capabilities: list[list[ServiceCapability]] = []
        for seid in discover_rsp.seid_information:
            getcap_rsp = await channel.initiate_get_all_capabilities(seid_information=seid)
            service_capabilities.append(getcap_rsp.service_capabilities)
        assert_equal(len(service_capabilities), len(discover_rsp.seid_information))
        logger.debug(f"Service capabilities: {service_capabilities}")

        acp_seid, aac_configuration = next(
            (seid_info.acp_seid, capabilities)
            for capabilities, seid_info in zip(service_capabilities, discover_rsp.seid_information)
            if isinstance(capabilities[1], MediaCodecCapability) and
            capabilities[1].media_codec_type == A2DP_MPEG_2_4_AAC_CODEC_TYPE)

        local_seid_information = [
            SeidInformation(acp_seid=0x01, tsep=Tsep.SINK, media_type=AVDTP_AUDIO_MEDIA_TYPE),
            SeidInformation(acp_seid=0x02, tsep=Tsep.SINK, media_type=AVDTP_AUDIO_MEDIA_TYPE)
        ]

        logger.info("3. RD1 sets AVDT configuration and expects discover command from DUT")
        await channel.initiate_set_configuration(acp_seid=acp_seid,
                                                 int_seid=local_seid_information[1].acp_seid,
                                                 configuration=aac_configuration)
        discover_cmd = await channel.expect_signal(DiscoverCommand(transaction_label=ANY))

        logger.info("4. RD1 initiates AVDT open before responding to discover command")
        await channel.initiate_open(acp_seid=acp_seid)
        await channel.initiate_transport_channel()

        logger.info("5. RD1 resposonds to discover command after media channel is opened")

        channel.send_signal(
            DiscoverResponse(transaction_label=discover_cmd.transaction_label,
                             seid_information=local_seid_information))

        logger.info("6. DUT should be able to get capabilities from RD1")
        await channel.accept_get_all_capabilities(sbc_service_capabilites())
        await channel.accept_get_all_capabilities(aac_service_capabilites())

    @avatar.asynchronous
    @enableFlag(A2DP_OFFLOAD_USER_CODEC_SELECTION_FLAG)
    async def test_avdt_suspend_and_start_from_remote(self) -> None:
        """Test AVDTP suspend and start from remote.

        Test steps after DUT and RD1 connected and paired:
        1. DUT starts stream.
        2. RD1 sends AVDT suspend request.
        3. RD1 sends AVDT start request - the stream should restart.
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
        acp_seid, dut_ref1_source = await asyncio.gather(
            channel.accept_open_stream(seid_information=seid_information,
                                       service_capabilities=sbc_service_capabilites()),
            open_source(self.dut_a2dp, dut_ref1))

        logger.info("1. DUT starts stream")
        await asyncio.gather(self.dut_a2dp.Start(source=dut_ref1_source), channel.accept_start())

        # Verify that audio is received on the transport channel.
        generated_audio = generate_sine(source=dut_ref1_source, duration_s=10.0)

        async def suspend_after_timeout(timeout: float = 4.0) -> None:
            await asyncio.sleep(timeout)
            logger.info("2. RD1 sends AVDT suspend request")
            await channel.initiate_suspend(acp_seid=acp_seid)

        self.dut_a2dp.PlaybackAudio(generated_audio)
        await asyncio.gather(
            channel.receive_audio_data(test_log_path=self.log_path, filename="sbc",
                                       duration_s=10.0), suspend_after_timeout())

        await asyncio.sleep(3)

        logger.info("3. RD1 sends AVDT start request - the stream should restart")
        await channel.initiate_start(acp_seid=acp_seid)

        await channel.receive_audio_data(test_log_path=self.log_path,
                                         filename="sbc2",
                                         duration_s=10.0)


if __name__ == '__main__':
    logging.basicConfig(level=logging.DEBUG)
    test_runner.main()  # type: ignore
