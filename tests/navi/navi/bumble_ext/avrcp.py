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

from __future__ import annotations

import asyncio
from collections.abc import Mapping, Sequence
import dataclasses
import logging
import pprint
import struct
from typing import cast

from bumble import avctp
from bumble import avrcp
from bumble import core
from bumble import device as bumble_device
from bumble import l2cap

logger = logging.getLogger(__name__)

_AVCTP_MAX_TRANSACTION_LABEL = 16
_CHARSET_ID_UTF_8 = avrcp.CharacterSetId(avrcp.CharacterSetId.UTF_8)


class Error(core.ProtocolError):
    """Base error class for AVRCP."""

    def __init__(self, error_code: avrcp.StatusCode, details: str = "") -> None:
        super().__init__(
            error_code=error_code,
            error_namespace="AVRCP",
            error_name=error_code.name,
            details=details,
        )


@dataclasses.dataclass
class MediaElement:
    """Abstract MediaElement class without some protocol details."""

    media_element_uid: int
    displayable_name: str
    media_type: avrcp.MediaElementItem.MediaType = (avrcp.MediaElementItem.MediaType(
        avrcp.MediaElementItem.MediaType.AUDIO))
    attributes: Mapping[int, str] = dataclasses.field(default_factory=dict)

    def to_avrcp_item(self) -> avrcp.MediaElementItem:
        return avrcp.MediaElementItem(
            media_element_uid=self.media_element_uid,
            media_type=self.media_type,
            character_set_id=_CHARSET_ID_UTF_8,
            displayable_name=self.displayable_name,
            attribute_value_entry_list=[
                avrcp.AttributeValueEntry(
                    attribute_id=avrcp.MediaAttributeId(attribute_id),
                    character_set_id=_CHARSET_ID_UTF_8,
                    attribute_value=attribute_value,
                ) for attribute_id, attribute_value in self.attributes.items()
            ],
        )


@dataclasses.dataclass
class Folder:
    """Abstract Folder class without some protocol details."""

    folder_uid: int
    is_playable: bool
    displayable_name: str
    folder_type: avrcp.FolderItem.FolderType = avrcp.FolderItem.FolderType(
        avrcp.FolderItem.FolderType.MIXED)
    children: Sequence[MediaElement | Folder] = ()

    def to_avrcp_item(self) -> avrcp.FolderItem:
        return avrcp.FolderItem(
            folder_uid=self.folder_uid,
            folder_type=self.folder_type,
            is_playable=cast(
                avrcp.FolderItem.Playable,
                (avrcp.FolderItem.Playable.PLAYABLE
                 if self.is_playable else avrcp.FolderItem.Playable.NOT_PLAYABLE),
            ),
            character_set_id=_CHARSET_ID_UTF_8,
            displayable_name=self.displayable_name,
        )


@dataclasses.dataclass
class Player:
    """Abstract Player class without some protocol details."""

    player_id: int
    feature_bitmask: avrcp.MediaPlayerItem.Features
    displayable_name: str
    root_folder: Folder | None = None
    major_player_type: avrcp.MediaPlayerItem.MajorPlayerType = (
        avrcp.MediaPlayerItem.MajorPlayerType(avrcp.MediaPlayerItem.MajorPlayerType.AUDIO))
    player_sub_type: avrcp.MediaPlayerItem.PlayerSubType = (avrcp.MediaPlayerItem.PlayerSubType(0))
    play_status: avrcp.PlayStatus = avrcp.PlayStatus(avrcp.PlayStatus.STOPPED)

    def to_avrcp_item(self) -> avrcp.MediaPlayerItem:
        return avrcp.MediaPlayerItem(
            player_id=self.player_id,
            major_player_type=self.major_player_type,
            player_sub_type=self.player_sub_type,
            play_status=self.play_status,
            feature_bitmask=self.feature_bitmask,
            character_set_id=_CHARSET_ID_UTF_8,
            displayable_name=self.displayable_name,
        )


class BrowsingController:
    """AVRCP Browsing Channel controller implementation."""

    pending_commands: dict[int, asyncio.Future[avrcp.Response]]  # Pending commands, by label

    def __init__(self, l2cap_channel: l2cap.ClassicChannel):

        # Create an initial pool of free commands
        self.pending_commands = {}
        self.avctp_protocol = avctp.Protocol(l2cap_channel)

        self.avctp_protocol.register_response_handler(avrcp.AVRCP_PID, self._on_avctp_response)

    @classmethod
    async def connect(
        cls,
        connection: bumble_device.Connection,
    ) -> BrowsingController:
        return cls(await connection.create_l2cap_channel(
            l2cap.ClassicChannelSpec(
                psm=avctp.AVCTP_BROWSING_PSM,
                mode=l2cap.TransmissionMode.ENHANCED_RETRANSMISSION,
                fcs_enabled=True,
            )))

    async def _allocate_transaction_label(self) -> int | None:
        return next(
            (i for i in range(_AVCTP_MAX_TRANSACTION_LABEL) if i not in self.pending_commands),
            None,
        )

    async def send_command(self, command: avrcp.Command) -> avrcp.Response:
        """Sends an AVRCP command to the device.

    Args:
      command: The AVRCP command to send.

    Returns:
      The response from the device.
    """

        # Wait for a free command slot.
        if (transaction_label := await self._allocate_transaction_label()) is None:
            raise core.OutOfResourcesError("No free command slots available")

        logger.debug(">>> AVRCP Browsing command PDU: %s", pprint.pformat(command))
        payload = bytes(command)
        response_future = self.pending_commands[transaction_label] = (
            asyncio.get_running_loop().create_future())
        self.avctp_protocol.send_command(
            transaction_label,
            avrcp.AVRCP_PID,
            struct.pack(">BH", command.pdu_id, len(payload)) + payload,
        )

        # Wait for the response.
        return await response_future

    def send_response(self, transaction_label: int, response: avrcp.Response) -> None:
        logger.debug(">>> AVRCP Browsing response PDU: %s", pprint.pformat(response))
        pdu = bytes(response)
        self.avctp_protocol.send_response(
            transaction_label,
            avrcp.AVRCP_PID,
            struct.pack(">BH", response.pdu_id, len(pdu)) + pdu,
        )

    def _on_avctp_response(self, transaction_label: int, payload: bytes | None) -> None:
        """Handles an AVRCP response from the device."""
        if not payload:
            raise core.InvalidPacketError("Browsing Channel should not receive empty payload")
        pending_command = self.pending_commands[transaction_label]
        pdu_id = avrcp.PduId(payload[0])
        pdu = payload[3:]
        response: avrcp.Response
        if (pdu_id in (
                avrcp.PduId.SET_BROWSED_PLAYER,
                avrcp.PduId.GET_FOLDER_ITEMS,
                avrcp.PduId.CHANGE_PATH,
                avrcp.PduId.GET_ITEM_ATTRIBUTES,
                avrcp.PduId.GET_TOTAL_NUMBER_OF_ITEMS,
                avrcp.PduId.SEARCH,
        ) and len(pdu) <= 1):
            # On error, remaining parameters are not present.
            response = avrcp.RejectedResponse.from_bytes(pdu, pdu_id)
        else:
            response = avrcp.Response.from_bytes(pdu, pdu_id)
        logger.debug("<<< AVRCP response PDU: %s", pprint.pformat(response))
        pending_command.set_result(response)

    async def change_path(
        self,
        direction: avrcp.ChangePathCommand.Direction,
        folder_uid: int = 0,
    ) -> int:
        """Changes the path of the browsing channel.

    Args:
      direction: The direction to change the path.
      folder_uid: The folder uid to change the path. Ignored if direction is UP.

    Returns:
      The number of items in the folder.

    Raises:
      core.InvalidPacketError: If the response is not a ChangePathResponse.
      Error: If the response is a RejectedResponse.
    """

        response = await self.send_command(
            avrcp.ChangePathCommand(
                uid_counter=0,
                direction=direction,
                folder_uid=folder_uid,
            ))
        if isinstance(response, avrcp.RejectedResponse):
            raise Error(response.status_code)
        if not isinstance(response, avrcp.ChangePathResponse):
            raise core.InvalidPacketError(f"Invalid response type: {type(response)}")
        return response.number_of_items

    async def get_total_number_of_items(self, scope: avrcp.Scope) -> int:
        """Gets the total number of items of the browsing channel."""
        response = await self.send_command(avrcp.GetTotalNumberOfItemsCommand(scope=scope))
        if isinstance(response, avrcp.RejectedResponse):
            raise Error(response.status_code)
        if not isinstance(response, avrcp.GetTotalNumberOfItemsResponse):
            raise core.InvalidPacketError(f"Invalid response type: {type(response)}")
        return response.number_of_items

    async def get_folder_items(
            self,
            scope: avrcp.Scope,
            start_item: int = 0,
            end_item: int = 0,
            attributes: Sequence[avrcp.MediaAttributeId] = (),
    ) -> Sequence[avrcp.BrowseableItem]:
        """Gets the folder items of the browsing channel.

    Args:
      scope: The scope of the folder items.
      start_item: The start item of the folder items.
      end_item: The end item of the folder items.
      attributes: The attributes of the folder items.

    Returns:
      The folder items of the browsing channel.

    Raises:
      core.InvalidArgumentError: If the start_item is less than 0 or the
        end_item is less than the start_item.
      core.InvalidPacketError: If the response is not a GetFolderItemsResponse.
      Error: If the response is a RejectedResponse.
    """

        if start_item < 0 or start_item > end_item:
            raise core.InvalidArgumentError(
                f"Invalid start_item: {start_item} or end_item: {end_item}")
        if end_item == 0:
            end_item = await self.get_total_number_of_items(scope)
        response = await self.send_command(
            avrcp.GetFolderItemsCommand(
                scope=scope,
                start_item=start_item,
                end_item=end_item,
                attributes=attributes,
            ))
        if isinstance(response, avrcp.RejectedResponse):
            raise Error(response.status_code)
        if not isinstance(response, avrcp.GetFolderItemsResponse):
            raise core.InvalidPacketError(f"Invalid response type: {type(response)}")
        return response.items

    async def set_browsed_player(self, player_id: int) -> avrcp.SetBrowsedPlayerResponse:
        """Sets the browsed player of the browsing channel.

    Args:
      player_id: The player id of the browsed player.

    Returns:
      The response of the set browsed player command.

    Raises:
      core.InvalidPacketError: If the response is not a GetFolderItemsResponse.
      Error: If the response is a RejectedResponse.
    """
        response = await self.send_command(avrcp.SetBrowsedPlayerCommand(player_id=player_id))
        if isinstance(response, avrcp.RejectedResponse):
            raise Error(response.status_code)
        if not isinstance(response, avrcp.SetBrowsedPlayerResponse):
            raise core.InvalidPacketError(f"Invalid response type: {type(response)}")
        return response


class BrowsingTarget:
    """AVRCP Browsing Channel target implementation."""

    browsed_player: Player | None = None

    def __init__(
        self,
        l2cap_channel: l2cap.ClassicChannel,
        players: Sequence[Player],
    ):
        self.players = players
        self.browsed_player = None
        self.current_path: list[Folder] = []
        self.avctp_protocol = avctp.Protocol(l2cap_channel)

        self.avctp_protocol.register_command_handler(avrcp.AVRCP_PID, self._on_avctp_command)

    @classmethod
    def listen(
        cls,
        device: bumble_device.Device,
        players: Sequence[Player],
    ) -> asyncio.Queue[BrowsingTarget]:
        """Creates a browsing target and starts listening on the device.

    Args:
      device: The device to listen on.
      players: The players to serve.

    Returns:
      A queue of browsing targets.
    """

        queue = asyncio.Queue[BrowsingTarget]()

        def on_channel(channel: l2cap.ClassicChannel) -> None:
            target = cls(channel, players)
            channel.once(channel.EVENT_OPEN, lambda: queue.put_nowait(target))

        device.create_l2cap_server(
            l2cap.ClassicChannelSpec(
                psm=avctp.AVCTP_BROWSING_PSM,
                mode=l2cap.TransmissionMode.ENHANCED_RETRANSMISSION,
                fcs_enabled=True,
            ),
            handler=on_channel,
        )
        return queue

    @property
    def current_folder(self) -> Folder | None:
        if not self.browsed_player:
            return None
        if not self.current_path:
            return self.browsed_player.root_folder
        return self.current_path[-1]

    def send_response(self, transaction_label: int, response: avrcp.Response) -> None:
        logger.debug(">>> AVRCP Browsing response PDU: %s", pprint.pformat(response))
        pdu = bytes(response)
        self.avctp_protocol.send_response(
            transaction_label,
            avrcp.AVRCP_PID,
            struct.pack(">BH", response.pdu_id, len(pdu)) + pdu,
        )

    def _on_avctp_command(self, transaction_label: int, payload: bytes) -> None:
        """Handles an AVRCP command from the device."""
        pdu_id = avrcp.PduId(payload[0])
        command = avrcp.Command.from_bytes(pdu_id, payload[3:])
        logger.debug("<<< AVRCP command PDU: %s", pprint.pformat(command))
        if isinstance(command, avrcp.GetFolderItemsCommand):
            self._on_get_folder_items_command(transaction_label, command)
        elif isinstance(command, avrcp.GetTotalNumberOfItemsCommand):
            self._on_get_total_number_of_items_command(transaction_label, command)
        elif isinstance(command, avrcp.SetBrowsedPlayerCommand):
            self._on_set_browsed_player_command(transaction_label, command)
        elif isinstance(command, avrcp.ChangePathCommand):
            self._on_change_path_command(transaction_label, command)
        else:
            self.send_response(
                transaction_label,
                avrcp.RejectedResponse(pdu_id,
                                       cast(avrcp.StatusCode, avrcp.StatusCode.INTERNAL_ERROR)),
            )

    def _on_get_folder_items_command(self, transaction_label: int,
                                     command: avrcp.GetFolderItemsCommand) -> None:
        """Handles a GetFolderItemsCommand."""
        response: avrcp.Response
        items: list[avrcp.BrowseableItem]
        if command.scope == avrcp.Scope.MEDIA_PLAYER_LIST:
            items = [player.to_avrcp_item() for player in self.players]
            response = avrcp.GetFolderItemsResponse(
                status=cast(avrcp.StatusCode, avrcp.StatusCode.OPERATION_COMPLETED),
                uid_counter=0,
                items=items[command.start_item:command.end_item + 1],
            )
        elif command.scope == avrcp.Scope.MEDIA_PLAYER_VIRTUAL_FILESYSTEM:
            if not self.browsed_player:
                response = avrcp.RejectedResponse(
                    command.pdu_id,
                    cast(avrcp.StatusCode, avrcp.StatusCode.PLAYER_NOT_BROWSABLE),
                )
            else:
                folder = self.current_folder
                if folder is None:
                    response = avrcp.RejectedResponse(
                        command.pdu_id,
                        cast(avrcp.StatusCode, avrcp.StatusCode.INVALID_DIRECTION),
                    )
                else:
                    items = [child.to_avrcp_item() for child in folder.children]
                    response = avrcp.GetFolderItemsResponse(
                        status=cast(avrcp.StatusCode, avrcp.StatusCode.OPERATION_COMPLETED),
                        uid_counter=0,
                        items=items[command.start_item:command.end_item + 1],
                    )
        else:
            response = avrcp.RejectedResponse(
                command.pdu_id,
                cast(avrcp.StatusCode, avrcp.StatusCode.INVALID_COMMAND),
            )
        self.send_response(transaction_label, response)

    def _on_get_total_number_of_items_command(self, transaction_label: int,
                                              command: avrcp.GetTotalNumberOfItemsCommand) -> None:
        """Handles a GetTotalNumberOfItemsCommand."""
        if command.scope == avrcp.Scope.MEDIA_PLAYER_LIST:
            count = len(self.players)
        elif command.scope == avrcp.Scope.MEDIA_PLAYER_VIRTUAL_FILESYSTEM:
            if not self.browsed_player:
                count = 0
            else:
                folder = self.current_folder
                count = len(folder.children) if folder else 0
        else:
            count = 0

        self.send_response(
            transaction_label,
            avrcp.GetTotalNumberOfItemsResponse(
                status=cast(avrcp.StatusCode, avrcp.StatusCode.OPERATION_COMPLETED),
                uid_counter=0,
                number_of_items=count,
            ),
        )

    def _on_set_browsed_player_command(self, transaction_label: int,
                                       command: avrcp.SetBrowsedPlayerCommand) -> None:
        """Handles a SetBrowsedPlayerCommand."""
        player = next((p for p in self.players if p.player_id == command.player_id), None)
        if not player:
            self.send_response(
                transaction_label,
                avrcp.RejectedResponse(
                    command.pdu_id,
                    cast(avrcp.StatusCode, avrcp.StatusCode.INVALID_PLAYER_ID),
                ),
            )
            return

        self.browsed_player = player
        self.current_path = []

        num_items = len(player.root_folder.children) if player.root_folder else 0

        self.send_response(
            transaction_label,
            avrcp.SetBrowsedPlayerResponse(
                status=cast(avrcp.StatusCode, avrcp.StatusCode.OPERATION_COMPLETED),
                uid_counter=0,
                numbers_of_items=num_items,
                character_set_id=_CHARSET_ID_UTF_8,
                folder_names=[],
            ),
        )

    def _on_change_path_command(self, transaction_label: int,
                                command: avrcp.ChangePathCommand) -> None:
        """Handles a ChangePathCommand."""
        if not self.browsed_player:
            self.send_response(
                transaction_label,
                avrcp.RejectedResponse(
                    command.pdu_id,
                    cast(avrcp.StatusCode, avrcp.StatusCode.PLAYER_NOT_BROWSABLE),
                ),
            )
            return

        if command.direction == avrcp.ChangePathCommand.Direction.UP:
            if not self.current_path:
                self.send_response(
                    transaction_label,
                    avrcp.RejectedResponse(
                        command.pdu_id,
                        cast(avrcp.StatusCode, avrcp.StatusCode.INVALID_DIRECTION),
                    ),
                )
                return
            self.current_path.pop()
        else:  # DOWN
            folder = self.current_folder
            if folder is None:
                self.send_response(
                    transaction_label,
                    avrcp.RejectedResponse(
                        command.pdu_id,
                        cast(avrcp.StatusCode, avrcp.StatusCode.INVALID_DIRECTION),
                    ),
                )
                return
            target = next(
                (child for child in folder.children
                 if isinstance(child, Folder) and child.folder_uid == command.folder_uid),
                None,
            )
            if not target:
                self.send_response(
                    transaction_label,
                    avrcp.RejectedResponse(
                        command.pdu_id,
                        cast(avrcp.StatusCode, avrcp.StatusCode.DOES_NOT_EXIST),
                    ),
                )
                return
            self.current_path.append(target)

        num_items = len(self.current_folder.children) if self.current_folder else 0
        self.send_response(
            transaction_label,
            avrcp.ChangePathResponse(
                status=cast(avrcp.StatusCode, avrcp.StatusCode.OPERATION_COMPLETED),
                number_of_items=num_items,
            ),
        )


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
        avrcp_controller_handle: (avrcp.ControllerServiceSdpRecord(
            avrcp_controller_handle,
            supported_features=avrcp_controller_features,
        ).to_service_attributes()),
        avrcp_target_handle: (avrcp.TargetServiceSdpRecord(
            avrcp_target_handle, supported_features=avrcp_target_features).to_service_attributes()),
    })
    return avrcp_protocol
