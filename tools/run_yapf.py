#!/usr/bin/env python3
#
# Copyright (C) 2025 The Android Open Source Project
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import argparse
import logging
import os
import subprocess
import sys


def main():
    logging.basicConfig()
    logger = logging.getLogger('yapf')

    yapf_dir = os.path.abspath(os.path.join(__file__, '../../../../../external/yapf'))
    if not os.path.isdir(yapf_dir):
        logger.error('Failed to found yapf binary')
        exit(1)

    parser = argparse.ArgumentParser()
    parser.add_argument('files', nargs='*')

    args = parser.parse_args()

    pyfiles = [os.path.abspath(f) for f in args.files if f.endswith('.py')]
    if not len(pyfiles):
        exit(0)

    environment = {**os.environ}
    environment['PYTHONPATH'] += f':{yapf_dir}'
    environment['PYTHONPATH'] += f':{yapf_dir}/third_party'

    yapf_command = [sys.executable, '-m', 'yapf', '--parallel', *pyfiles]
    result = subprocess.run(yapf_command + ['--diff'],
                            env=environment,
                            stderr=subprocess.STDOUT,
                            stdout=subprocess.PIPE)
    if result.returncode != 0 or result.stdout:
        logger.error(result.stdout.decode('utf-8').strip())
        logger.error(
            'Some python formatting error have been detected.\n'
            'Please run the following manual fix-up:\n\n'
            f'\tPYTHONPATH=$PYTHONPATH:{yapf_dir}:{yapf_dir}/third_party \\\n\t\t{" ".join(yapf_command + ["--in-place"])}\n'
        )
        exit(1)


if __name__ == '__main__':
    main()
