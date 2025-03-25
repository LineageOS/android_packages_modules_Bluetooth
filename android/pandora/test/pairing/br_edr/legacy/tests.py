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
import logging

from pairing.br_edr.test_base import BREDRPairTestBase

from mobly.asserts import assert_equal, fail

from avatar import asynchronous

from pandora.security_pb2 import PairingEventAnswer


class BREDRLegacyTestClass(BREDRPairTestBase):

    def _setup_devices(self) -> None:

        self.ref.config.setdefault('classic_enabled', True)
        self.ref.config.setdefault('le_enabled', False)
        self.ref.config.setdefault('classic_ssp_enabled', False)

        self.ref.config.setdefault(
            'server',
            {
                # Android io_capability: display_yesno
                'io_capability': 'keyboard_input_only',
            },
        )

    async def accept_pairing(self):
        expected_pairing_method = 'pin_code_request'
        pairing_pin_code = b'123456'

        # initiator receives pin code request
        init_ev = await anext(self.initiator_pairing_event_stream)
        logging.debug(f'init_ev.method_variant():{init_ev.method_variant()}')
        assert_equal(init_ev.method_variant(), expected_pairing_method)
        init_ev_ans = PairingEventAnswer(event=init_ev, pin=pairing_pin_code)

        # accept pairing on initiator with pairing pin code
        self.initiator_pairing_event_stream.send_nowait(init_ev_ans)

        # responder receives pin code request
        responder_ev = await anext(self.responder_pairing_event_stream)
        logging.debug(f'responder_ev.method_variant():{responder_ev.method_variant()}')
        assert_equal(responder_ev.method_variant(), expected_pairing_method)
        responder_ev_ans = PairingEventAnswer(event=responder_ev, pin=pairing_pin_code)

        # accept pairing on responder with pairing pin code
        self.responder_pairing_event_stream.send_nowait(responder_ev_ans)

    @asynchronous
    async def test_dedicated_pairing_acl_init_by_bumble_and_bumble_as_pair_initiator(self) -> None:
        '''
        acl:
            ref: initiator
            dut: responder

        pairing:
            ref: initiator
            dut: responder
        '''

        # setting up roles
        self.acl_initiator = self.ref
        self.acl_responder = self.dut
        self.pairing_initiator = self.ref
        self.pairing_responder = self.dut

        # do pairing test
        self.prepare_pairing()

        # first initiate an ACL connection from bumble to android
        bumble_res, android_res = await self.start_acl_connection()

        # bumble initiates the pairing
        pairing_task = asyncio.create_task(
            self.start_pairing(bumble_res.connection, android_res.connection))

        await self.accept_pairing()

        await asyncio.wait_for(pairing_task, timeout=10.0)

    @asynchronous
    async def test_dedicated_pairing_acl_init_by_bumble_and_bumble_as_pair_responder(self) -> None:
        '''
        acl:
            ref: initiator
            dut: responder

        pairing:
            ref: responder
            dut: initiator
        '''

        # role setup
        self.acl_initiator = self.ref
        self.acl_responder = self.dut
        self.pairing_initiator = self.dut
        self.pairing_responder = self.ref

        self.prepare_pairing()

        # first initiate an ACL connection from bumble to android
        bumble_res, android_res = await self.start_acl_connection()

        # Android initiates the pairing
        pairing_task = asyncio.create_task(
            self.start_pairing(android_res.connection, bumble_res.connection))

        await self.accept_pairing()

        await asyncio.wait_for(pairing_task, timeout=10.0)

    @asynchronous
    async def test_dedicated_pairing_acl_init_by_phone_and_bumble_as_pair_responder(self) -> None:
        '''
        acl:
            ref: responder
            dut: initiator

        pairing:
            ref: responder
            dut: initiator

        Note: we can not change the role of pairing actions in the current avatar
        implementation, as the implementation of Connect (initiating acl connection)
        on Android will initiate pairing.

        Pairing initiated from ref is not supported yet
        '''
        # role setup
        self.acl_initiator = self.dut
        self.acl_responder = self.ref
        self.pairing_initiator = self.dut
        self.pairing_responder = self.ref

        self.prepare_pairing()

        acl_connection_task = asyncio.create_task(self.start_acl_connection())

        # with the ACL connection, pairing will be automatically started
        # on Android
        await self.accept_pairing()

        await asyncio.wait_for(acl_connection_task, timeout=10.0)

    @asynchronous
    async def test_general_pairing(self) -> None:
        # role setup
        self.acl_initiator = self.ref
        self.acl_responder = self.dut
        self.pairing_initiator = self.dut
        self.pairing_responder = self.ref
        self.service_initiator = self.ref
        self.service_responder = self.dut

        self.prepare_pairing()

        # first initiate an ACL connection from bumble to android
        android_res, bumble_res = await self.start_acl_connection()

        service_access_task = asyncio.create_task(
            self.start_service_access(bumble_res.connection, android_res.connection))

        # pairing will be started automatically when a secure service is
        # accessed
        await self.accept_pairing()

        try:
            _ = await asyncio.wait_for(service_access_task, timeout=5.0)
        except:
            fail("access should have succeeded")
