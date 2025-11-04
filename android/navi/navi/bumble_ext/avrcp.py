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
"""Custom AVRCP Bumble implementation."""

from bumble import avrcp
from bumble import device as bumble_device


def setup_server(
    device: bumble_device.Device,
    avrcp_controller_handle: int,
    avrcp_target_handle: int,
    delegate: avrcp.Delegate | None = None,
    avrcp_controller_features: int = 0x01,
    avrcp_target_features: int = 0x23,
) -> avrcp.Protocol:
    """Sets up the AVRCP server on the device.

  Args:
    device: The device to set up the AVRCP server on.
    avrcp_controller_handle: The handle of the AVRCP service record.
    avrcp_target_handle: The handle of the AVRCP target service record.
    delegate: The delegate to handle AVRCP events.
    avrcp_controller_features: The features of the AVRCP controller.
    avrcp_target_features: The features of the AVRCP target.

  Returns:
    The AVRCP protocol.
  """
    avrcp_protocol = avrcp.Protocol(delegate)
    avrcp_protocol.listen(device)
    device.sdp_service_records.update({
        avrcp_controller_handle:
            avrcp.make_controller_service_sdp_records(avrcp_controller_handle,
                                                      supported_features=avrcp_controller_features),
        avrcp_target_handle:
            avrcp.make_target_service_sdp_records(avrcp_target_handle,
                                                  supported_features=avrcp_target_features),
    })
    return avrcp_protocol
