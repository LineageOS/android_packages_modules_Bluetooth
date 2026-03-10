#!/usr/bin/env python3

# Copyright 2023 Google LLC
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

import argparse
import dataclasses
from pathlib import Path
import re
import sys
import os
from typing import List
import subprocess


def unique_tidy_errors(stdout):
    unique_errors = set()
    current_error = None

    while l := stdout.readline():
        l = l.decode("utf-8")
        print(l[:-1])

        if current_error:
            if l.startswith("["):
                line_report = re.search(
                    r"packages/modules/Bluetooth/.*?:[0-9]+:[0-9]+", current_error
                ).group(0)
                current_error = re.sub(r"FAILED:.*?\n", "", current_error)
                current_error = re.sub(r"CLANG_CMD=.*?\n", "", current_error)
                current_error = re.sub(r"stdout:.*\n", "", current_error)
                current_error = re.sub(r"\n+", "\n", current_error)
                current_error = current_error.strip(" \n\r")
                if line_report not in unique_errors:
                    yield current_error
                    unique_errors.add(line_report)
                current_error = None
            else:
                current_error += l
        else:
            if l.startswith("FAILED:"):
                current_error = l


def run(fix: bool, modules: List[str]):
    environ = os.environ
    args = ["-k"]

    if not environ.get("ANDROID_BUILD_TOP"):
        print(
            "tidy.py must be run within an android build environment."
            + " Run build/envsetup.sh, lunch first.",
            file=sys.stderr,
        )
        sys.exit(-1)

    if fix:
        environ["WITH_TIDY_FLAGS"] = "--fix"
        args.append("-j1")

    if not modules:
        modules = [
            # "libbt-audio-asrc",
            "avrcp-target-service",
            "lib-bt-packets-avrcp",
            "lib-bt-packets-base",
            "libbluetooth_types",
            "libbluetooth_crypto_toolbox",
            "libbluetooth_gd",
            "libbluetooth_metrics",
            "libbt-audio-hal-interface",
            "libbt-bta",
            "libbt-btu-main-thread",
            "libbt-common",
            "libbt-hci",
            "libbt-jni-thread",
            "libbt-stack",
            "libbtcore",
            "libbtdevice",
            "libbte",
            "libbtif",
            "libosi",
        ]

    p = subprocess.Popen(
        ["m"] + args + [module + "-tidy" for module in modules],
        env=environ,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )

    with open("tidy.log", "wb") as out:
        for e in unique_tidy_errors(p.stdout):
            out.write(e.encode("utf-8"))
            out.write(b"\n")
            out.flush()


def main():
    """Invoke clang-tidy and apply fixes for all the selected module targets."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fix", help="Apply automatic fixes")
    parser.add_argument(
        "modules", metavar="MODULE", nargs="*", type=str, help="Modules to test"
    )
    run(**vars(parser.parse_args()))


if __name__ == "__main__":
    main()
