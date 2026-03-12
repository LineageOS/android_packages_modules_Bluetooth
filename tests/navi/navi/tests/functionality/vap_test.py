#  Copyright 2025 Google LLC
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.

from __future__ import annotations

import asyncio
import re

from bumble import hci
import bumble.device
from bumble.profiles import vcs
from mobly import signals
from typing_extensions import override

from navi.bumble_ext import ascs
from navi.bumble_ext import pacs
from navi.bumble_ext import vap
from navi.tests import navi_test_base
from navi.utils import android_constants
from navi.utils import bl4a_api

_DEFAULT_STEP_TIMEOUT_SECONDS = 10.0
_STREAMING_TIME_SECONDS = 5.0
_SINK_ASE_ID = 1
_SOURCE_ASE_ID = 2
_RECORDING_PATH = "/storage/self/primary/Recordings/record.mp3"
_ACTION_VOICE_COMMAND = "android.intent.action.VOICE_COMMAND"
_TEST_TIMEOUT = 30.0
_ASSISTANT_NAME = "Navi"

_AndroidProperty = android_constants.Property


class VapTest(navi_test_base.TwoDevicesTestBase):
    """Tests of VAP (Voice Assistant Profile) server implementation."""

    ref_ascs: ascs.AudioStreamControlService

    def _setup_unicast_server(self) -> None:
        self.ref.device.add_service(pacs.make_pacs())
        self.ref_ascs = ascs.AudioStreamControlService(
            self.ref.device,
            sink_ase_id=[_SINK_ASE_ID],
            source_ase_id=[_SOURCE_ASE_ID],
        )
        self.ref_vcs = vcs.VolumeControlService(volume_setting=vcs.MAX_VOLUME // 2)
        self.ref.device.add_service(self.ref_ascs)
        self.ref.device.add_service(self.ref_vcs)

    async def _prepare_paired_devices(self) -> None:
        with self.dut.bl4a.register_callback(bl4a_api.Module.LE_AUDIO) as dut_lea_cb:
            self.logger.info("[DUT] Pair with REF")
            await self.le_connect_and_pair(ref_address_type=hci.OwnAddressType.RANDOM,
                                           connect_profiles=True)
            await dut_lea_cb.wait_for_event(
                bl4a_api.ProfileActiveDeviceChanged(self.ref.random_address))

    def _disable_voice_command_apps_except_snippet(self) -> None:
        # Disable all other voice command apps to prevent choosing activities.
        voice_command_packages: set[str] = set(
            re.findall(
                r"packageName=(.+)",
                self.dut.shell([
                    "pm",
                    "query-activities",
                    "-a",
                    _ACTION_VOICE_COMMAND,
                ]),
            ))

        def voice_command_package_callback(package: str) -> None:
            self.logger.info("[DUT] Re-Enable voice command app: %s.", package)
            self.dut.shell(["pm", "enable", package])

        for package in voice_command_packages:
            if package == android_constants.PACKAGE_NAME_BLUETOOTH_SNIPPET:
                continue
            self.logger.info("[DUT] Disable voice command app: %s.", package)
            self.dut.shell(["pm", "disable", package])
            self.test_class_context.callback(voice_command_package_callback, package)

    @override
    async def async_setup_class(self) -> None:
        await super().async_setup_class()

        if self.dut.device.is_emulator:
            self.setprop_for_class_context(_AndroidProperty.VAP_SERVER_ENABLED, "true")

        if not self.dut.is_le_audio_supported:
            raise signals.TestAbortClass("[DUT] Device does not support LE Audio.")

        if self.dut.getprop(_AndroidProperty.VAP_SERVER_ENABLED) != "true":
            raise signals.TestAbortClass("VAP server is not enabled")

        # Disable all other voice command apps to prevent choosing activities.
        self._disable_voice_command_apps_except_snippet()

        self.ref.config.cis_enabled = True
        self.ref.device.cis_enabled = True

        # b/480360111: Having some problems with EATT and VAP.
        self.ref.config.eatt_enabled = False

        self.setprop_for_class_context(_AndroidProperty.LEAUDIO_BYPASS_ALLOW_LIST, "true")

    @override
    async def async_teardown_class(self) -> None:
        await super().async_teardown_class()
        self.dut.shell(["settings", "reset", "secure", "assistant"])

    @override
    async def async_setup_test(self) -> None:
        await super().async_setup_test()

        self._setup_unicast_server()
        self.logger.info("[DUT] Open server.")
        await self._prepare_paired_devices()

        self.dut.shell(["settings", "put", "secure", "assistant", _ASSISTANT_NAME])

    @override
    async def async_teardown_test(self) -> None:
        await super().async_teardown_test()
        await asyncio.to_thread(self.dut.bt.audioStop)

    async def _init_vas_client(self,) -> vap.GenericVoiceAssistantServiceProxy:
        self.logger.info("[REF] Init VAS client.")
        ref_dut_acl = list(self.ref.device.connections.values())[0]
        async with bumble.device.Peer(ref_dut_acl) as peer:
            if not (vas_client := peer.create_service_proxy(vap.GenericVoiceAssistantServiceProxy)):
                self.fail("VAS server not found.")
        await vas_client.subscribe_characteristics()
        return vas_client

    async def test_discover_and_read_vas_properties(self) -> None:
        """Tests VAP discovery and reading VAS properties.

    Test steps:
      1. Discover VAS Service and Characteristics.
      2. VAS Service and Characteristics should be discovered successfully.
      3. Read VA Name, VA UUID, VA CCID.
      4. VA name should not be None.
      5. VA UUID should be all zeros.
      6. CCID value read should be 3.
    """
        async with self.assert_not_timeout(_TEST_TIMEOUT):
            vas_client = await self._init_vas_client()

            # VA Name
            self.logger.info("[REF] Read VA Name.")
            va_name = await vas_client.va_name.read_value()
            self.assertStartsWith(va_name, _ASSISTANT_NAME)

            # VA UUID
            self.logger.info("[REF] Read VA UUID.")
            va_uuid = await vas_client.va_uuid.read_value()
            self.assertStartsWith(va_uuid, _ASSISTANT_NAME.encode("utf-8"))

            # VA CCID
            self.logger.info("[REF] Read VA CCID Value.")
            self.assertNotEqual(await vas_client.va_ccid.read_value(), 0)

    async def test_invalid_opcode(self) -> None:
        """Tests Invalid opcode for VAP.

    Test steps:
      1. Discover VAP service.
      2. Read and subscribe to VAP characteristics.
      3. Write invalid opcode to VAP control point (other than 0x00, 0x01,
      0x02).
      4. Result should be opcode OPCODE_NOT_SUPPORTED.
    """
        async with self.assert_not_timeout(_TEST_TIMEOUT):
            vas_client = await self._init_vas_client()
            self.logger.info("[REF] Init VAP Session.")
            self.assertEqual(
                await vas_client.write_control_point(vap.ControlPointOpcode.INVALID_OPCODE),
                vap.ResponseCodeValue.OP_CODE_NOT_SUPPORTED,
            )

    async def test_stop_va_ready_state(self) -> None:
        """Tests Stop VAP when VA is ready.

    Test steps:
      1. Discover VAP service.
      2. Read and subscribe to VAP characteristics.
      3. Initialize VA.
      4. Stop VA
      5. Result should be opcode INVALID_SESSION_STATE.
    """
        async with self.assert_not_timeout(_TEST_TIMEOUT):
            vas_client = await self._init_vas_client()
            self.logger.info("[REF] Init VAP Session.")
            self.assertEqual(
                await vas_client.initialize_va_session(),
                vap.ResponseCodeValue.SUCCESS,
            )

            self.logger.info("[REF] Stop VAP Session.")
            self.assertEqual(
                await vas_client.stop_va_session(),
                vap.ResponseCodeValue.INVALID_SESSION_STATE,
            )

    async def test_va_stop_without_va_initialize(self) -> None:
        """Tests Stop VAP when VA is ready.

    Test steps:
      1. Discover VAP service.
      2. Read and subscribe to VAP characteristics.
      3. Stop VA, ensure that the VA is not in VA_SESSION_ACTIVE state.
      4. Result should be INVALID_SESSION_STATE.
    """
        async with self.assert_not_timeout(_TEST_TIMEOUT):
            vas_client = await self._init_vas_client()

            self.logger.info("[REF] Stop VAP Session.")
            self.assertEqual(
                await vas_client.stop_va_session(),
                vap.ResponseCodeValue.INVALID_SESSION_STATE,
            )

    async def test_va_start_without_va_initialize(self) -> None:
        """Tests VAP VA start from VAT.

    Test steps:
      1. Discover VAP service.
      2. Read and subscribe to VAP characteristics.
      3. Start VA.
      4. Result should be opcode INVALID_SESSION_STATE.
    """
        async with self.assert_not_timeout(_TEST_TIMEOUT):
            vas_client = await self._init_vas_client()
            self.logger.info("[REF] Start VAP Service.")
            with self.assertRaises(vap.VapError) as e:
                await vas_client.start_va_session()
            self.assertEqual(e.exception.error_code, vap.ResponseCodeValue.INVALID_SESSION_STATE)

    async def test_start_and_stop_va_session(self) -> None:
        """Tests VAP VA start from VAT.

    Test steps:
      1. Discover VAP service.
      2. Read and subscribe to VAP characteristics.
      3. Initialize VA.
      4. Start VA.
      5. Stop VA.
    """
        async with self.assert_not_timeout(_TEST_TIMEOUT):
            voice_command_cb = self.test_case_context.enter_context(
                self.dut.bl4a.register_voice_command_callback())
            vas_client = await self._init_vas_client()
            self.dut.bl4a.set_audio_attributes(
                bl4a_api.AudioAttributes(usage=bl4a_api.AudioAttributes.Usage.ASSISTANT),
                handle_audio_focus=False,
            )

            self.logger.info("[REF] Init VAP Session.")
            self.assertEqual(
                await vas_client.initialize_va_session(),
                vap.ResponseCodeValue.SUCCESS,
            )

            self.logger.info("[REF] Start VAP Service.")
            start_task = asyncio.create_task(vas_client.start_va_session())

            self.logger.info("[DUT] Wait for Voice command.")
            await voice_command_cb.wait_for_event(
                bl4a_api.VoiceCommand(state=True),
                timeout=_DEFAULT_STEP_TIMEOUT_SECONDS,
            )
            self.logger.info("[DUT] Voice command received.")

            # Initialize Voice Recognition as audio source
            self.logger.info("[DUT] Start audio playback and recording.")
            audio_recorder = await asyncio.to_thread(lambda: self.dut.bl4a.start_audio_recording(
                _RECORDING_PATH,
                source=bl4a_api.AudioRecorder.Source.VOICE_RECOGNITION,
            ))
            self.test_case_context.push(audio_recorder)
            await asyncio.to_thread(self.dut.bt.audioPlaySine)

            self.logger.info("[REF] Wait for Start VA Session to complete.")
            await start_task

            self.logger.info("[REF] Verify Session State.")
            await vas_client.va_session_state.wait_for_target_value(
                bytes([vap.VaSessionState.VA_SESSION_ACTIVE]))

            self.logger.info("Streaming for %s seconds.", _STREAMING_TIME_SECONDS)
            await asyncio.sleep(_STREAMING_TIME_SECONDS)

            self.logger.info("[REF] Stop VAP Session.")
            await vas_client.stop_va_session()
            await vas_client.va_session_state.wait_for_target_value(
                bytes([vap.VaSessionState.VA_SESSION_READY]))
