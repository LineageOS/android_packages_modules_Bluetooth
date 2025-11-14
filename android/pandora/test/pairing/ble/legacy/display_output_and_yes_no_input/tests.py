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

import logging

from mobly.asserts import assert_equal

from pairing.ble.test_base import BLEPairTestBaseWithGeneralAndDedicatedPairingTests

from pandora.security_pb2 import PairingEventAnswer


class BLELegDisplayYesNoTestClass(BLEPairTestBaseWithGeneralAndDedicatedPairingTests):

    def _setup_devices(self) -> None:
        self.ref.config.setdefault('le_enabled', True)

        # Explicitly disable BR/EDR
        self.ref.config.setdefault('classic_enabled', False)
        self.ref.config.setdefault('classic_ssp_enabled', False)
        self.ref.config.setdefault('classic_sc_enabled', False)

        self.ref.config.setdefault(
            'server',
            {
                # legacy pairing
                'pairing_sc_enable': False,
                'pairing_mitm_enable': True,
                'pairing_bonding_enable': True,
                # Android IO CAP: Display_KBD
                # Ref IO CAP:
                'io_capability': 'display_output_and_yes_no_input',
            },
        )

    async def accept_pairing(self):
        notif_expected_pairing_method = 'passkey_entry_notification'
        req_expected_pairing_method = 'passkey_entry_request'

        responder_ev = await anext(self.responder_pairing_event_stream)
        logging.debug(f'responder_ev.method_variant()[1]:{responder_ev.method_variant()}')

        if responder_ev.method_variant() == 'just_works':
            # when pairing is initiated from bumble,
            logging.debug('>>> confirming pairing request')
            ans = PairingEventAnswer(event=responder_ev, confirm=True)
            self.responder_pairing_event_stream.send_nowait(ans)

            responder_ev = await anext(self.responder_pairing_event_stream)
            logging.debug(f'responder_ev.method_variant()[2]:{responder_ev.method_variant()}')

        init_ev = await anext(self.initiator_pairing_event_stream)
        logging.debug(f'init_ev.method_variant():{init_ev.method_variant()}')

        if self.initiator_pairing_event_stream == self.android_pairing_stream:
            notif_ev = responder_ev
            req_ev = init_ev
            req_stream = self.initiator_pairing_event_stream
        else:
            notif_ev = init_ev
            req_ev = responder_ev
            req_stream = self.responder_pairing_event_stream

        assert_equal(notif_ev.method_variant(), notif_expected_pairing_method)
        assert_equal(req_ev.method_variant(), req_expected_pairing_method)

        notified_passkey = notif_ev.passkey_entry_notification

        ans = PairingEventAnswer(event=req_ev, passkey=notified_passkey)
        req_stream.send_nowait(ans)
