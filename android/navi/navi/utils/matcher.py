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
"""Utilities for matching arguments."""

import dataclasses
from typing import Any
from unittest import mock

ANY = mock.ANY


@dataclasses.dataclass(frozen=True)
class _AnyOf:
    values: tuple[Any, ...]

    def __eq__(self, other: Any) -> bool:
        return other in self.values

    def __ne__(self, other: Any) -> bool:
        return other not in self.values


def any_of(*values) -> Any:
    """Returns an object that matches any of the given values."""
    return _AnyOf(values)
