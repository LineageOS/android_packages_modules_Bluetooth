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
import pathlib
import subprocess

_BASE_DIR = pathlib.Path("tests", "pandora", "interfaces")
_BASE_PYTHON_DIR = _BASE_DIR / "python"


class build_py(_build_py):
    """Custom build command to process non-Python targets."""

    def run(self) -> None:
        input_files = glob.glob("**/*.proto", root_dir=_BASE_DIR)
        self.build_lib
        output_path = _BASE_PYTHON_DIR.absolute()
        # Generate proto python stub
        cmds = [
            "python",
            "-m",
            "grpc_tools.protoc",
            "-I",
            _BASE_DIR,
            "-I",
            "../../../external/protobuf/src",
            *input_files,
            "--python_out",
            output_path,
            "--grpc_out",
            output_path,
            f"--plugin=protoc-gen-grpc={_BASE_PYTHON_DIR}/_build/protoc-gen-custom_grpc",
        ]
        subprocess.run(cmds).check_returncode()
        return super().run()
