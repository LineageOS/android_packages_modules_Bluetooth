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

from setuptools.command.build_py import build_py as _build_py

import glob
import os
import pathlib
import subprocess


class build_py(_build_py):
    """Custom build command to process non-Python targets."""

    def run(self) -> None:
        input_files = glob.glob("pandora/interfaces/**/*.proto")
        self.build_lib
        output_path = pathlib.Path("pandora", "interfaces", "python").absolute()
        # Generate proto python stub
        cmds = [
            "python",
            "-m",
            "grpc_tools.protoc",
            "-I",
            "pandora/interfaces",
            "-I",
            "../../../external/protobuf/src",
            *input_files,
            "--python_out",
            output_path,
            "--grpc_out",
            output_path,
            "--plugin=protoc-gen-grpc=pandora/interfaces/python/_build/protoc-gen-custom_grpc",
        ]
        subprocess.run(cmds).check_returncode()
        return super().run()
