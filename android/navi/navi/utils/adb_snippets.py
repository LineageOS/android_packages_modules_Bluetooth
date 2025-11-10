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
"""Bluetooth ADB helper snippets."""

import contextlib
import datetime
import logging
import pathlib
import time

from mobly.controllers import android_device
from mobly.controllers.android_device_lib import adb

_logger = logging.getLogger('AdbSnippets')


def sync_time(device: android_device.AndroidDevice) -> None:
    """Sync device time and timezone with host machine.

  Args:
    device: Android device to sync time.
  """
    device.adb.shell([
        'date',
        '-u',
        datetime.datetime.now(tz=datetime.timezone.utc).strftime('%m%d%H%M%Y.%S'),
    ])
    # Sync timezone.
    # Blaze test always uses "Google Standard Time" - America/Los_Angeles.
    device.adb.shell(['setprop', 'persist.sys.timezone', 'America/Los_Angeles'])


def enable_btsnoop(device: android_device.AndroidDevice) -> None:
    """Enable Bluetooth snoop logging on Android device.

  Args:
    device: Android device to enable btsnoop.
  """
    # Starting from mainline 25.08, persist.bluetooth.btsnooplogmode will be reset
    # on factory reset, but default snoop mode will be kept.
    device.adb.shell(['settings', 'put', 'global', 'bluetooth_btsnoop_default_mode', 'full'])
    device.adb.shell(['setprop', 'persist.bluetooth.btsnoopdefaultmode', 'full'])
    device.adb.shell(['setprop', 'persist.bluetooth.btsnooplogmode', 'full'])


def download_btsnoop(
    device: android_device.AndroidDevice,
    destination_base_path: str,
    filename_prefix: str = '',
) -> None:
    """Download Bluetooth snoop log from Android device.

  Args:
    device: Android device to download log.
    destination_base_path: destination base path.
    filename_prefix: (Optional) destination file name prefix.
  """
    filename_prefix = '_'.join(([filename_prefix] if filename_prefix else []) + [device.serial])
    dest = pathlib.Path(destination_base_path)

    for directory in (
            '/data/misc/bluetooth/logs',
            '/data/vendor/bluetooth',
    ):
        files = (device.adb.shell(['ls', directory, '||', 'true']).decode('utf-8').splitlines())
        for filename in files:
            device_snoop_path = pathlib.Path(directory, filename).as_posix()
            host_snoop_path = dest / f'{filename_prefix}_{filename}'
            device.adb.pull([device_snoop_path, str(host_snoop_path)])


def cleanup_btsnoop(device: android_device.AndroidDevice) -> None:
    """Cleanup Bluetooth snoop log from Android device.

  Args:
    device: Android device to download log.
  """
    for path in (
            '/data/misc/bluetooth/logs/*',
            '/data/vendor/bluetooth/*',
    ):
        with contextlib.suppress(adb.AdbError):
            device.adb.shell(['rm', '-rf', path])


def download_dumpsys(
    device: android_device.AndroidDevice,
    destination_base_path: str,
    filename: str | None = None,
) -> None:
    """Download Bluetooth Dumpsys Information from Android device.

  Args:
    device: Android device to download log.
    destination_base_path: destination base path.
    filename: (Optional) destination file name.
  """
    if filename is None:
        filename = f'{device.serial}_dumpsys.txt'

    # If target file doesn't exist, an AdbError will be raised.
    with contextlib.suppress(adb.AdbError):
        with open(
                str(pathlib.Path(destination_base_path).joinpath(filename)),
                'wb',
        ) as outfile:
            outfile.write(device.adb.shell(['dumpsys', 'bluetooth_manager']))


def enable_bluetooth(device: android_device.AndroidDevice, enable: bool) -> None:
    """Enable or disable Bluetooth on Android device.

  Args:
    device: Android device to toggle Bluetooth.
    enable: Target state.
  """
    device.adb.shell(['cmd', 'bluetooth_manager', 'enable' if enable else 'disable'])
    try:
        device.adb.shell([
            'cmd',
            'bluetooth_manager',
            f'wait-for-state:STATE_{"ON" if enable else "OFF"}',
        ])
    except android_device.adb.AdbError:
        time.sleep(1)
