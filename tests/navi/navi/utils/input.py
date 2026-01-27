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
"""Utils for Input System."""

import asyncio
from collections.abc import Sequence
import dataclasses
import logging
from typing import Any, Self

logger = logging.getLogger(__name__)


@dataclasses.dataclass
class InputMonitor:
    """Monitor for input events."""

    process: asyncio.subprocess.Process

    @classmethod
    async def create(cls, serial: str) -> Self:
        return cls(await asyncio.create_subprocess_exec(
            *["adb", "-s", serial, "shell", "getevent", "-l"],
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        ))

    async def wait_for_event(self, filters: Sequence[str]) -> None:
        """Waits for an event matching the filters."""
        if not (stdout := self.process.stdout):
            raise RuntimeError("Failed to get stdout.")
        async for raw_line in stdout:
            line = raw_line.decode("utf-8").strip()
            logger.debug("line: %s", line)
            if all(filter in line for filter in filters):
                return
        raise AssertionError("EOF reached.")

    def __enter__(self) -> Self:
        return self

    def __exit__(self, exc_type: Any, exc_value: Any, traceback: Any) -> None:
        self.process.kill()
