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
import avatar
import a2dp_test
import aics_test
import asha_test
import host_test
import le_security_test
import security_test
import gatt_test
import hap_test
import rfcomm_test
import sdp_test

from pandora.host_pb2 import PrimaryPhy, PRIMARY_1M, PRIMARY_CODED

_TEST_CLASSES_LIST = [
    host_test.HostTest,
    security_test.SecurityTest,
    le_security_test.LeSecurityTest,
    a2dp_test.A2dpTest,
    aics_test.AicsTest,
    sdp_test.SdpTest,
    gatt_test.GattTest,
    hap_test.HapTest,
    asha_test.AshaTest,
    rfcomm_test.RfcommTest,
]


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
