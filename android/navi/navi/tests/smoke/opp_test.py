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
"""Tests for Object Push Profile (OPP) implementation on Android."""

import asyncio
import contextlib
import datetime
import pathlib
import sys
import tempfile
from typing import TypeAlias
import uuid

from bumble import core
from bumble import l2cap
from bumble import rfcomm
from mobly import test_runner
from mobly import signals
from mobly.controllers.android_device_lib import adb
from typing_extensions import override

from navi.bumble_ext import obex
from navi.bumble_ext import opp
from navi.tests import navi_test_base
from navi.utils import android_constants
from navi.utils import bl4a_api
from navi.utils import retry

_OPP_SERVICE_RECORD_HANDLE = 1
_DEFAULT_TIMEOUT_SECONDS = 30.0
_UI_TIMEOUT = datetime.timedelta(seconds=10.0)
_TEST_FILE_MIME_TYPE = 'text/plain'
_VIDEO_SERVICE_NAME = 'video'
_TEST_DATA = bytes(i % 256 for i in range(200000))
_POSITIVE_BUTTON_RESOURCE_ID = 'android:id/button1'
_NEGATIVE_BUTTON_RESOURCE_ID = 'android:id/button2'

_CallbackHandler: TypeAlias = bl4a_api.CallbackHandler
_Module: TypeAlias = bl4a_api.Module


class OppTest(navi_test_base.TwoDevicesTestBase):
    ref_opp_server: opp.Server

    @override
    async def async_setup_class(self) -> None:
        await super().async_setup_class()

        if self.dut.getprop(android_constants.Property.OPP_ENABLED) != 'true':
            raise signals.TestAbortClass('OPP is not enabled on DUT.')

        # Disable Better Bug to avoid unexpected popups.
        with contextlib.suppress(adb.AdbError):
            self.dut.shell([
                'pm',
                'disable-user',
                '--user',
                f'{self.dut.adb.current_user_id}',
                'com.google.android.apps.internal.betterbug',
            ])

        # Stay awake during the test.
        self.dut.shell('svc power stayon true')
        # Dismiss the keyguard.
        self.dut.shell('wm dismiss-keyguard')

        await self._setup_paired_devices()

    @retry.retry_on_exception()
    async def _setup_paired_devices(self) -> None:
        # Reset devices.
        self.assertTrue(self.dut.bt.enable())
        self.dut.bt.waitForAdapterState(android_constants.AdapterState.ON)
        self.dut.bt.factoryReset()
        async with self.assert_not_timeout(_DEFAULT_TIMEOUT_SECONDS):
            await self.ref.reset()

        # Set up OPP server on REF.
        self.ref_opp_server = opp.Server(self.ref.device)
        self.ref.device.sdp_service_records = {
            _OPP_SERVICE_RECORD_HANDLE:
                opp.make_sdp_records(
                    opp.SdpInfo(
                        service_record_handle=_OPP_SERVICE_RECORD_HANDLE,
                        rfcomm_channel=self.ref_opp_server.rfcomm_channel,
                        profile_version=opp.Version.V_1_2,
                        goep_l2cap_psm=self.ref_opp_server.l2cap_server.psm,
                    ))
        }

        # Setup pairing and terminate connection.
        with self.dut.bl4a.register_callback(_Module.ADAPTER) as dut_cb:
            await self.classic_connect_and_pair()
            # Wait for ACL disconnection (since there isn't any active profile, it
            # should be disconnected immediately).
            await dut_cb.wait_for_event(
                bl4a_api.AclDisconnected(
                    address=self.ref.address,
                    transport=android_constants.Transport.CLASSIC,
                ),)

    @override
    async def async_teardown_class(self) -> None:
        await super().async_teardown_class()
        # Stop staying awake during the test.
        self.dut.shell('svc power stayon false')

    @override
    @retry.retry_on_exception()
    async def async_setup_test(self) -> None:
        # Restart Bluetooth on DUT to clear any stale state.
        self.assertTrue(self.dut.bt.disable())
        self.dut.bt.waitForAdapterState(android_constants.AdapterState.OFF)
        self.assertTrue(self.dut.bt.enable())
        self.dut.bt.waitForAdapterState(android_constants.AdapterState.ON)
        self.dut.shell('input keyevent KEYCODE_HOME')

    async def _make_opp_client_from_ref(self, use_l2cap: bool) -> opp.Client:
        async with self.assert_not_timeout(_DEFAULT_TIMEOUT_SECONDS):
            self.logger.info('[REF] Connect to DUT.')
            ref_dut_acl = await self.ref.device.connect(self.dut.address,
                                                        transport=core.BT_BR_EDR_TRANSPORT)
            self.logger.info('[REF] Authenticate and encrypt.')
            await ref_dut_acl.authenticate()
            await ref_dut_acl.encrypt()

            self.logger.info('[REF] Find SDP record.')
            sdp_info = await opp.find_sdp_record(ref_dut_acl)
            if not sdp_info:
                self.fail('Failed to find SDP record for OPP.')

            bearer: obex.Bearer
            if use_l2cap:
                if not sdp_info.goep_l2cap_psm:
                    self.fail('Failed to find L2CAP PSM for OPP.')
                self.logger.info('[REF] Connect to OPP over L2CAP.')
                bearer = await ref_dut_acl.create_l2cap_channel(
                    l2cap.ClassicChannelSpec(
                        psm=sdp_info.goep_l2cap_psm,
                        mode=l2cap.TransmissionMode.ENHANCED_RETRANSMISSION,
                        fcs_enabled=True,
                    ))
            else:
                self.logger.info('[REF] Connect to OPP.')
                rfcomm_client = await rfcomm.Client(ref_dut_acl).start()
                bearer = await rfcomm_client.open_dlc(sdp_info.rfcomm_channel)
            return opp.Client(bearer)

    @navi_test_base.named_parameterized(
        rfcomm=False,
        l2cap=True,
    )
    async def test_outbound_single_file(self, use_l2cap: bool) -> None:
        """Tests sending a single file from DUT to REF.

    Test steps:
      1. Generate a test file on DUT.
      2. Set a random alias to avoid collision with other tests.
      3. Send a sharing file intent from DUT.
      4. Select the target device on DUT.
      5. Wait for OPP connection on REF.
      6. Wait for file transfer to complete on REF.
      7. Check the received file on REF.

    Args:
      use_l2cap: Whether to use L2CAP for OPP connection.
    """
        self.ref.device.sdp_service_records = {
            _OPP_SERVICE_RECORD_HANDLE:
                opp.make_sdp_records(
                    opp.SdpInfo(
                        service_record_handle=_OPP_SERVICE_RECORD_HANDLE,
                        rfcomm_channel=self.ref_opp_server.rfcomm_channel,
                        profile_version=opp.Version.V_1_2,
                        goep_l2cap_psm=(self.ref_opp_server.l2cap_server.psm
                                        if use_l2cap else None),
                    ))
        }

        user_id = self.dut.adb.current_user_id
        # [DUT] Generate a test file.
        with tempfile.NamedTemporaryFile(
                mode='wb',
                # On Windows, NamedTemporaryFile cannot be deleted if used multiple
                # times.
                delete=(sys.platform != 'win32'),
        ) as temp_file:
            temp_file.write(_TEST_DATA)
            self.dut.adb.push([temp_file.name, f'/data/media/{user_id}/opp_test_file.txt'])

        # [DUT] Set a random alias to avoid collision with other tests.
        self.dut.bt.setAlias(self.ref.address, str(uuid.uuid4()))

        self.logger.info('[DUT] Send sharing file intent.')
        # The file path is different here:
        #  - /storage/ is accessible for Android apps.
        #  - /data/media/ is accessible for adb.
        self.dut.bt.oppShareFiles(['/storage/self/primary/opp_test_file.txt'], _TEST_FILE_MIME_TYPE)

        self.logger.info('[DUT] Select the target device')
        # After receiving the sharing file intent, OPP service will pop a Device
        # Selector Activity, showing all available devices with their alias names.
        ui_result = await asyncio.to_thread(lambda: self.dut.ui(textContains=self.dut.bt.getAlias(
            self.ref.address)).wait.click(timeout=_UI_TIMEOUT))
        self.assertTrue(ui_result, 'Failed to click the target device.')

        self.logger.info('[REF] Wait for OPP connection.')
        async with self.assert_not_timeout(_DEFAULT_TIMEOUT_SECONDS):
            opp_server_connection = await self.ref_opp_server.wait_connection()

        self.logger.info('[REF] Wait file transfer to complete.')
        async with self.assert_not_timeout(_DEFAULT_TIMEOUT_SECONDS):
            received_file = await opp_server_connection.completed_sessions.get()

        # [REF] Check the received file.
        self.assertEqual(received_file.name, 'opp_test_file.txt')
        self.assertStartsWith(received_file.file_type, _TEST_FILE_MIME_TYPE)
        self.assertEqual(received_file.body, _TEST_DATA)

    @navi_test_base.named_parameterized(
        rfcomm=False,
        l2cap=True,
    )
    async def test_inbound_single_file(self, use_l2cap: bool) -> None:
        """Tests sending a single file from REF to DUT.

    Test steps:
      1. Connect ACL to DUT.
      2. Find SDP record for OPP.
      3. Connect OPP to DUT.
      4. Start file transfer from REF.
      5. Accept file transfer on DUT.
      6. Wait for file transfer to complete on REF.

    Args:
      use_l2cap: Whether to use L2CAP for OPP connection.
    """
        user_id = self.dut.adb.current_user_id
        file_name = 'opp_test_file.txt'
        file_name_pattern_android = (f'/data/media/{user_id}/Download/opp_test_file*.txt')
        # Make sure there isn't any similar file on DUT.
        with contextlib.suppress(adb.AdbError):
            self.dut.shell(f'test -f {file_name_pattern_android} && '
                           f'rm {file_name_pattern_android}')

        opp_client = await self._make_opp_client_from_ref(use_l2cap)
        async with self.assert_not_timeout(_DEFAULT_TIMEOUT_SECONDS):
            await opp_client.connect(count=1)

        self.logger.info('[REF] Start file transfer.')
        transfer_task = asyncio.create_task(
            opp_client.transmit_file(
                file_name=file_name,
                file_content=_TEST_DATA,
                file_type=_TEST_FILE_MIME_TYPE,
            ))
        self.logger.info('[DUT] Accept file transfer.')
        # A notification will be popped up on DUT when there is an incoming file
        # transfer request. We need to click the notification to pop a dialog, and
        # then click the ACCEPT button to accept the file transfer.
        self.dut.shell('cmd statusbar expand-notifications')
        ui_result = await asyncio.to_thread(
            lambda: self.dut.ui(textContains=file_name).wait.click(timeout=_UI_TIMEOUT))
        self.assertTrue(ui_result, 'Failed to click the file name.')
        ui_result = await asyncio.to_thread(lambda: self.dut.ui(
            res=_POSITIVE_BUTTON_RESOURCE_ID,
            clickable=True,
        ).wait.click(timeout=_UI_TIMEOUT))
        self.assertTrue(ui_result, 'Failed to click the accept button.')

        self.logger.info('[REF] Wait file transfer to complete.')
        async with self.assert_not_timeout(_DEFAULT_TIMEOUT_SECONDS):
            await transfer_task

        @retry.retry_on_exception()
        def check_file_on_dut() -> None:
            # Android always generate a new file name with timestamp, so we need to
            # find the file with the same prefix.
            dut_file_path = self.dut.shell(['ls', file_name_pattern_android])
            with tempfile.TemporaryDirectory() as temp_dir:
                self.dut.adb.pull([dut_file_path, temp_dir])
                with open(pathlib.Path(temp_dir, pathlib.Path(dut_file_path).name), 'rb') as f:
                    self.assertEqual(f.read(), _TEST_DATA)

        check_file_on_dut()

    @navi_test_base.named_parameterized(
        rfcomm=False,
        l2cap=True,
    )
    async def test_inbound_transfer_reject(self, use_l2cap: bool) -> None:
        """Tests sending files from REF to DUT and reject the transfer on DUT.

    Test steps:
      1. Connect ACL to DUT.
      2. Find SDP record for OPP.
      3. Connect OPP to DUT.
      4. Start file transfer from REF.
      5. Reject file transfer on DUT.
      6. Wait for file transfer to complete on REF.

    Args:
      use_l2cap: Whether to use L2CAP for OPP connection.
    """
        file_name = 'opp_test_file.txt'

        opp_client = await self._make_opp_client_from_ref(use_l2cap)
        async with self.assert_not_timeout(_DEFAULT_TIMEOUT_SECONDS):
            await opp_client.connect(count=1)

        self.logger.info('[REF] Start file transfer.')
        transfer_task = asyncio.create_task(
            opp_client.transmit_file(
                file_name=file_name,
                file_content=_TEST_DATA,
                file_type=_TEST_FILE_MIME_TYPE,
            ))
        self.logger.info('[DUT] Reject file transfer.')
        self.dut.shell('cmd statusbar expand-notifications')
        ui_result = await asyncio.to_thread(
            lambda: self.dut.ui(textContains=file_name).wait.click(timeout=_UI_TIMEOUT))
        self.assertTrue(ui_result, 'Failed to click the file name.')
        ui_result = await asyncio.to_thread(lambda: self.dut.ui(
            res=_NEGATIVE_BUTTON_RESOURCE_ID,
            clickable=True,
        ).wait.click(timeout=_UI_TIMEOUT))
        self.assertTrue(ui_result, 'Failed to click the reject button.')

        self.logger.info('[REF] Wait file transfer to complete.')
        async with self.assert_not_timeout(_DEFAULT_TIMEOUT_SECONDS):
            with self.assertRaises(opp.OppError) as e:
                await transfer_task
            self.assertEqual(e.exception.error_code, obex.ResponseCode.FORBIDDEN)


if __name__ == '__main__':
    test_runner.main()
