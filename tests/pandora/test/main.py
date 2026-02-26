import argparse
import logging
import os
import sys

from argparse import Namespace
from mobly import suite_runner
from typing import List, Tuple

# Import test cases modules.
import a2dp_test
import gatt_test
import hap_test
import sdp_test

_BUMBLE_BTSNOOP_FMT = 'bumble_btsnoop_{pid}_{instance}.log'

_TEST_CLASSES_LIST = [
    a2dp_test.A2dpTest,
    sdp_test.SdpTest,
    gatt_test.GattTest,
    hap_test.HapTest,
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
