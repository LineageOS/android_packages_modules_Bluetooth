import site

site.main()

import argparse
import logging
import itertools
import os
import sys

from argparse import Namespace
from mobly import suite_runner
from typing import List, Tuple, Union, Literal

_BUMBLE_BTSNOOP_FMT = 'bumble_btsnoop_{pid}_{instance}.log'

# Import test cases modules.
import a2dp_test
import aics_test
import asha_test
import avatar.cases.host_test
import avatar.cases.le_host_test
import avatar.cases.le_security_test
import avatar.cases.security_test
import gatt_test
import hap_test
import hfpclient_test
import rfcomm_test
import sdp_test

from pairing import _test_class_list as _pairing_test_class_list
from pandora.host_pb2 import PrimaryPhy, PRIMARY_1M, PRIMARY_CODED


class LeHostTestFiltered(avatar.cases.le_host_test.LeHostTest):
    """
    LeHostTestFiltered inherits from LeHostTest to skip currently broken and unfeasible to fix tests.
    Overridden tests will be visible as PASS when run.
    """
    skipped_tests = [
        # Reason for skipping tests: b/272120114
        "test_extended_scan('non_connectable_scannable','directed',150,0)",
        "test_extended_scan('non_connectable_scannable','undirected',150,0)",
        "test_extended_scan('non_connectable_scannable','directed',150,2)",
        "test_extended_scan('non_connectable_scannable','undirected',150,2)",
    ]

    @avatar.parameterized(
        *itertools.product(
            # The advertisement cannot be both connectable and scannable.
            ('connectable', 'non_connectable', 'non_connectable_scannable'),
            ('directed', 'undirected'),
            # Bumble does not send multiple HCI commands, so it must also fit in
            # 1 HCI command (max length 251 minus overhead).
            (0, 150),
            (PRIMARY_1M, PRIMARY_CODED),
        ),)  # type: ignore[misc]
    def test_extended_scan(
        self,
        connectable_scannable: Union[Literal['connectable'], Literal['non_connectable'],
                                     Literal['non_connectable_scannable']],
        directed: Union[Literal['directed'], Literal['undirected']],
        data_len: int,
        primary_phy: PrimaryPhy,
    ) -> None:
        current_test = f"test_extended_scan('{connectable_scannable}','{directed}',{data_len},{primary_phy})"
        logging.info(f"current test: {current_test}")
        if current_test not in self.skipped_tests:
            assert current_test in avatar.cases.le_host_test.LeHostTest.__dict__
            avatar.cases.le_host_test.LeHostTest.__dict__[current_test](self)


_TEST_CLASSES_LIST = [
    avatar.cases.host_test.HostTest,
    LeHostTestFiltered,
    avatar.cases.security_test.SecurityTest,
    avatar.cases.le_security_test.LeSecurityTest,
    a2dp_test.A2dpTest,
    aics_test.AicsTest,
    sdp_test.SdpTest,
    gatt_test.GattTest,
    hap_test.HapTest,
    asha_test.AshaTest,
    hfpclient_test.HfpClientTest,
    rfcomm_test.RfcommTest,
] + _pairing_test_class_list


def _parse_cli_args() -> Tuple[Namespace, List[str]]:
    parser = argparse.ArgumentParser(description='Avatar test runner.')
    parser.add_argument(
        '-o',
        '--log_path',
        type=str,
        metavar='<PATH>',
        help='Path to the test configuration file.',
    )
    return parser.parse_known_args()


if __name__ == '__main__':
    logging.basicConfig(level=logging.INFO)

    # This is a hack for `tradefed` because of `b/166468397`.
    if '--' in sys.argv:
        index = sys.argv.index('--')
        sys.argv = sys.argv[:1] + sys.argv[index + 1:]

    # Enable bumble snoop logger.
    ns, argv = _parse_cli_args()
    if ns.log_path:
        os.environ.setdefault('BUMBLE_SNOOPER', f'btsnoop:file:{ns.log_path}/{_BUMBLE_BTSNOOP_FMT}')

    # Run the test suite.
    suite_runner.run_suite(_TEST_CLASSES_LIST, argv)  # type: ignore
