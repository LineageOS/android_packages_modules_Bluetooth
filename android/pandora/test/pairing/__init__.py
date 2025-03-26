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

from pairing.br_edr.legacy.tests import BREDRLegacyTestClass
from pairing.br_edr.misc.service_access_tests import ServiceAccessTempBondingTest

from pairing.br_edr.ssp.display_output_and_yes_no_input.tests import BREDRDisplayYesNoTestClass
from pairing.br_edr.ssp.display_output_only.tests import BREDRDisplayOnlyTestClass
from pairing.br_edr.ssp.keyboard_input_only.tests import BREDRKeyboardOnlyTestClass
from pairing.br_edr.ssp.no_output_no_input.tests import BREDRNoOutputNoInputTestClass

from pairing.ble.legacy.display_output_and_keyboard_input.tests import BLELegDisplayKbdTestClass
from pairing.ble.legacy.display_output_and_yes_no_input.tests import BLELegDisplayYesNoTestClass
from pairing.ble.legacy.display_output_only.tests import BLELegDisplayOnlyTestClass
from pairing.ble.legacy.keyboard_input_only.tests import BLELegKbdOnlyTestClass
from pairing.ble.legacy.no_output_no_input.tests import BLELegNoInputNoOutputTestClass

from pairing.ble.sc.display_output_and_keyboard_input.tests import BLESCDisplayKbdTestClass
from pairing.ble.sc.display_output_and_yes_no_input.tests import BLESCDisplayYesNoTestClass
from pairing.ble.sc.display_output_only.tests import BLESCDisplayOnlyTestClass
from pairing.ble.sc.keyboard_input_only.tests import BLESCKbdOnlyTestClass
from pairing.ble.sc.no_output_no_input.tests import BLESCNoInputNoOutputTestClass

from pairing.smp_test import SmpTest

_test_class_list = [
    BLELegDisplayKbdTestClass,
    BLELegDisplayOnlyTestClass,
    BLELegDisplayYesNoTestClass,
    BLELegKbdOnlyTestClass,
    # BLELegNoInputNoOutputTestClass,
    BLESCDisplayKbdTestClass,
    # BLESCDisplayOnlyTestClass,
    BLESCDisplayYesNoTestClass,
    # BLESCKbdOnlyTestClass,
    BLESCNoInputNoOutputTestClass,
    BREDRDisplayYesNoTestClass,
    BREDRDisplayOnlyTestClass,
    # BREDRKeyboardOnlyTestClass,
    BREDRNoOutputNoInputTestClass,
    BREDRLegacyTestClass,
    # ServiceAccessTempBondingTest,
    SmpTest,
]
