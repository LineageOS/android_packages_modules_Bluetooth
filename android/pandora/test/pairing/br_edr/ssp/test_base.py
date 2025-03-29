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

from avatar import asynchronous

from mobly.asserts import fail

from pairing.br_edr.test_base import BREDRPairTestBase


class BREDRSSPPairTestBase(BREDRPairTestBase):

    @asynchronous
    async def test_general_pairing(self) -> None:

        self.acl_initiator = self.ref
        self.acl_responder = self.dut
        self.pairing_initiator = self.ref
        self.pairing_responder = self.dut
        self.service_initiator = self.ref
        self.service_responder = self.dut

        self.prepare_pairing()

        # first initiate an ACL connection from bumble to android
        bumble_res, android_res = await self.start_acl_connection()

        service_access_task = asyncio.create_task(
            self.start_service_access(bumble_res.connection, android_res.connection))

        # at this point, android expects bumble to initiate pairing to compete the
        # service access
        pairing_task = asyncio.create_task(
            self.start_pairing(bumble_res.connection, android_res.connection))

        await self.accept_pairing()

        try:
            await asyncio.wait_for(service_access_task, timeout=10.0)
            await asyncio.wait_for(pairing_task, timeout=10.0)
        except:
            fail("service access and pairing should have succeeded")
