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
"""Generic functionality test suite for Bluetooth."""

import sys
from mobly import suite_runner
from navi.tests.smoke import a2dp_test
from navi.tests.smoke import asha_test
from navi.tests.smoke import classic_host_test
from navi.tests.smoke import gatt_client_test
from navi.tests.smoke import gatt_server_test
from navi.tests.smoke import hfp_ag_test
from navi.tests.smoke import hid_test
from navi.tests.smoke import hogp_test
from navi.tests.smoke import l2cap_test
from navi.tests.smoke import le_host_test
from navi.tests.smoke import map_test
from navi.tests.smoke import opp_test
from navi.tests.smoke import pan_test
from navi.tests.smoke import pbap_test
from navi.tests.smoke import rfcomm_test

if __name__ == "__main__":
    # Take test args
    if "--" in sys.argv:
        index = sys.argv.index("--")
        sys.argv = sys.argv[:1] + sys.argv[index + 1:]
    suite_runner.run_suite([
        a2dp_test.A2dpTest,
        asha_test.AshaTest,
        classic_host_test.ClassicHostTest,
        gatt_client_test.GattClientTest,
        gatt_server_test.GattServerTest,
        hfp_ag_test.HfpAgTest,
        hid_test.HidTest,
        hogp_test.HogpTest,
        l2cap_test.L2capTest,
        le_host_test.LeHostTest,
        map_test.MapTest,
        opp_test.OppTest,
        pan_test.PanTest,
        pbap_test.PbapTest,
        rfcomm_test.RfcommTest,
    ])
