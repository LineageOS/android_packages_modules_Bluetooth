/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

#include "device.h"
#include "fuzzer/FuzzedDataProvider.h"
#include "internal_include/stack_config.h"
#include "packet_test_helper.h"
#include "pass_through_packet.h"

bool btif_av_src_sink_coexist_enabled(void) { return true; }

namespace bluetooth {
namespace avrcp {

static uint32_t kMinSize = 0;
static uint32_t kMaxSize = 10;
static uint32_t kMaxLen = 100;
static uint8_t kMinScope = 0;
static uint8_t kMaxScope = 3;
static uint8_t kMediaOpId = 0x44;
static uint8_t kMask = 0xFF;
static uint8_t k8BitShift = 8;

const Opcode kValidOpCodes[] = {Opcode::VENDOR, Opcode::UNIT_INFO, Opcode::SUBUNIT_INFO,
                                Opcode::PASS_THROUGH};

const CType kValidCTypes[] = {CType::CONTROL,         CType::STATUS,   CType::NOTIFY,
                              CType::NOT_IMPLEMENTED, CType::ACCEPTED, CType::REJECTED,
                              CType::STABLE,          CType::CHANGED,  CType::INTERIM};

const BrowsePdu kPduVal[] = {BrowsePdu::SET_BROWSED_PLAYER,
                             BrowsePdu::GET_FOLDER_ITEMS,
                             BrowsePdu::CHANGE_PATH,
                             BrowsePdu::GET_ITEM_ATTRIBUTES,
                             BrowsePdu::GET_TOTAL_NUMBER_OF_ITEMS,
                             BrowsePdu::GENERAL_REJECT};

const CommandPdu kCommandPduVal[] = {CommandPdu::GET_CAPABILITIES,
                                     CommandPdu::LIST_PLAYER_APPLICATION_SETTING_ATTRIBUTES,
                                     CommandPdu::LIST_PLAYER_APPLICATION_SETTING_VALUES,
                                     CommandPdu::GET_CURRENT_PLAYER_APPLICATION_SETTING_VALUE,
                                     CommandPdu::SET_PLAYER_APPLICATION_SETTING_VALUE,
                                     CommandPdu::GET_ELEMENT_ATTRIBUTES,
                                     CommandPdu::GET_PLAY_STATUS,
                                     CommandPdu::REGISTER_NOTIFICATION,
                                     CommandPdu::SET_ABSOLUTE_VOLUME,
                                     CommandPdu::SET_ADDRESSED_PLAYER,
                                     CommandPdu::PLAY_ITEM};

class FakeMediaInterface : public MediaInterface {
public:
  FakeMediaInterface(FuzzedDataProvider* fdp) : mFdp(fdp) {}
  void SendKeyEvent(uint8_t /* key */, KeyState /* state */) { return; }
  using SongInfoCallback = base::Callback<void(SongInfo)>;
  void GetSongInfo(SongInfoCallback info_cb) {
    SongInfo sInfo;
    sInfo.media_id = mFdp->ConsumeRandomLengthString(kMaxLen);
    sInfo.attributes.insert(AttributeEntry(
            Attribute(mFdp->ConsumeIntegralInRange<uint8_t>(uint8_t(Attribute::TITLE),
                                                            uint8_t(Attribute::DEFAULT_COVER_ART))),
            mFdp->ConsumeRandomLengthString(kMaxLen)));
    info_cb.Run(sInfo);
    return;
  }
  using PlayStatusCallback = base::Callback<void(PlayStatus)>;
  void GetPlayStatus(PlayStatusCallback status_cb) {
    PlayStatus pst;
    status_cb.Run(pst);
    return;
  }
  using NowPlayingCallback = base::Callback<void(std::string, std::vector<SongInfo>)>;
  void GetNowPlayingList(NowPlayingCallback now_playing_cb) {
    std::string currentSongId = mFdp->ConsumeRandomLengthString(kMaxLen);
    size_t size = mFdp->ConsumeIntegralInRange<uint8_t>(kMinSize, kMaxSize);
    std::vector<SongInfo> songInfoVec;
    for (size_t iter = 0; iter < size; ++iter) {
      SongInfo tempSongInfo;
      tempSongInfo.media_id = mFdp->ConsumeRandomLengthString(kMaxLen);
      tempSongInfo.attributes.insert(AttributeEntry(
              Attribute(mFdp->ConsumeIntegralInRange<uint8_t>(
                      uint8_t(Attribute::TITLE), uint8_t(Attribute::DEFAULT_COVER_ART))),
              mFdp->ConsumeRandomLengthString(kMaxLen)));
      songInfoVec.push_back(tempSongInfo);
    }
    now_playing_cb.Run(currentSongId, songInfoVec);
    return;
  }
  using MediaListCallback =
          base::Callback<void(uint16_t curr_player, std::vector<MediaPlayerInfo>)>;
  void GetMediaPlayerList(MediaListCallback list_cb) {
    uint16_t currentPlayer = mFdp->ConsumeIntegral<uint16_t>();
    size_t size = mFdp->ConsumeIntegralInRange<uint8_t>(kMinSize, kMaxSize);
    std::vector<MediaPlayerInfo> playerList;
    for (size_t iter = 0; iter < size; ++iter) {
      MediaPlayerInfo tempInfo;
      tempInfo.id = mFdp->ConsumeIntegral<uint16_t>();
      tempInfo.name = mFdp->ConsumeRandomLengthString(kMaxLen);
      tempInfo.browsing_supported = mFdp->ConsumeBool();
      playerList.push_back(tempInfo);
    }
    list_cb.Run(currentPlayer, playerList);
    return;
  }
  using FolderItemsCallback = base::Callback<void(std::vector<ListItem>)>;
  void GetFolderItems(uint16_t /* player_id */, std::string /* media_id */,
                      FolderItemsCallback folder_cb) {
    size_t size = mFdp->ConsumeIntegralInRange<uint8_t>(kMinSize, kMaxSize);
    std::vector<ListItem> list;
    for (size_t iter = 0; iter < size; ++iter) {
      ListItem tempList;
      tempList.type = mFdp->ConsumeBool() ? ListItem::FOLDER : ListItem::SONG;
      tempList.folder.media_id = mFdp->ConsumeRandomLengthString(kMaxLen);
      tempList.folder.name = mFdp->ConsumeRandomLengthString(kMaxLen);
      tempList.folder.is_playable = mFdp->ConsumeBool();
      tempList.song.media_id = mFdp->ConsumeRandomLengthString(kMaxLen);
      tempList.song.attributes.insert(AttributeEntry(
              Attribute(mFdp->ConsumeIntegralInRange<uint8_t>(
                      uint8_t(Attribute::TITLE), uint8_t(Attribute::DEFAULT_COVER_ART))),
              mFdp->ConsumeRandomLengthString(kMaxLen)));
      list.push_back(tempList);
    }
    folder_cb.Run(list);
  }
  using GetAddressedPlayerCallback = base::Callback<void(uint16_t)>;
  void GetAddressedPlayer(GetAddressedPlayerCallback addressed_player) {
    uint16_t currentPlayer = mFdp->ConsumeIntegral<uint16_t>();
    addressed_player.Run(currentPlayer);
  }
  using SetBrowsedPlayerCallback =
          base::Callback<void(bool success, std::string current_path, uint32_t num_items)>;
  void SetBrowsedPlayer(uint16_t player_id, std::string /* current_path */,
                        SetBrowsedPlayerCallback browse_cb) {
    std::string rootId = mFdp->ConsumeRandomLengthString(kMaxLen);
    uint32_t numItems = mFdp->ConsumeIntegral<uint32_t>();
    browse_cb.Run(player_id, rootId, numItems);
    return;
  }
  using SetAddressedPlayerCallback = base::Callback<void(uint16_t)>;
  void SetAddressedPlayer(uint16_t player_id, SetAddressedPlayerCallback new_player) {
    new_player.Run(player_id);
  }
  void PlayItem(uint16_t /* player_id */, bool /* now_playing */, std::string /* media_id */) {
    return;
  }
  void SetActiveDevice(const RawAddress& /* address */) { return; }
  void RegisterUpdateCallback(MediaCallbacks* /* callback */) { return; }
  void UnregisterUpdateCallback(MediaCallbacks* /* callback */) { return; }

private:
  FuzzedDataProvider* mFdp;
};

class FakeVolumeInterface : public VolumeInterface {
public:
  FakeVolumeInterface(FuzzedDataProvider* fdp) : mFdp(fdp) {}
  void DeviceConnected(const RawAddress& /* bdaddr */) { return; }
  void DeviceConnected(const RawAddress& /* bdaddr */, VolumeChangedCb cb) {
    uint8_t volume = mFdp->ConsumeIntegral<uint8_t>();
    cb.Run(volume);
    return;
  }
  void DeviceDisconnected(const RawAddress& /* bdaddr */) { return; }
  void SetVolume(int8_t /* volume */) { return; }

private:
  FuzzedDataProvider* mFdp;
};

class FakePlayerSettingsInterface : public PlayerSettingsInterface {
public:
  FakePlayerSettingsInterface(FuzzedDataProvider* fdp) : mFdp(fdp) {}
  void ListPlayerSettings(ListPlayerSettingsCallback cb) {
    uint8_t label = mFdp->ConsumeIntegral<uint8_t>();
    size_t size = mFdp->ConsumeIntegralInRange<uint8_t>(kMinSize, kMaxSize);
    std::vector<PlayerAttribute> attributes;
    for (size_t iter = 0; iter < size; ++iter) {
      PlayerAttribute playerAttr = (PlayerAttribute)mFdp->ConsumeIntegralInRange<uint8_t>(
              uint8_t(PlayerAttribute::EQUALIZER), uint8_t(PlayerAttribute::SCAN));
      attributes.push_back(playerAttr);
    }
    cb.Run(attributes);
    return;
  }
  void ListPlayerSettingValues(PlayerAttribute setting, ListPlayerSettingValuesCallback cb) {
    size_t size = mFdp->ConsumeIntegralInRange<size_t>(kMinSize, kMaxSize);
    std::vector<uint8_t> values = mFdp->ConsumeBytes<uint8_t>(size);
    cb.Run(setting, values);
    return;
  }
  void GetCurrentPlayerSettingValue(std::vector<PlayerAttribute> attributes,
                                    GetCurrentPlayerSettingValueCallback cb) {
    std::vector<uint8_t> values(attributes.size());
    for (size_t iter = 0; iter < attributes.size(); ++iter) {
      values.push_back(mFdp->ConsumeIntegral<uint8_t>());
    }
    cb.Run(attributes, values);
    return;
  }
  void SetPlayerSettings(std::vector<PlayerAttribute> /* attributes */,
                         std::vector<uint8_t> /* values */, SetPlayerSettingValueCallback cb) {
    bool success = mFdp->ConsumeBool();
    cb.Run(success);
    return;
  }

private:
  FuzzedDataProvider* mFdp;
};

class FakeA2dpInterface : public A2dpInterface {
public:
  RawAddress active_peer() { return RawAddress::kAny; }
  bool is_peer_in_silence_mode(const RawAddress& /* peer_address */) { return false; }
  void connect_audio_sink_delayed(uint8_t /* handle */, const RawAddress& /* peer_address */) {
    return;
  }
  uint16_t find_audio_sink_service(const RawAddress& /* peer_address */,
                                   tA2DP_FIND_CBACK /* p_cback */) override {
    return 0;
  }
};

bool get_pts_avrcp_test(void) { return false; }

const stack_config_t interface = {get_pts_avrcp_test,
                                  nullptr,
                                  nullptr,
                                  nullptr,
                                  nullptr,
                                  nullptr,
                                  nullptr,
                                  nullptr,
                                  nullptr,
                                  nullptr,
                                  nullptr,
                                  nullptr,
                                  nullptr,
                                  nullptr,
                                  nullptr,
                                  nullptr,
                                  nullptr,
                                  nullptr,
                                  nullptr,
                                  nullptr,
                                  nullptr,
                                  nullptr};

void Callback(uint8_t, bool, std::unique_ptr<::bluetooth::PacketBuilder>) {}

class AVRCPDeviceFuzzer {
public:
  AVRCPDeviceFuzzer(const uint8_t* Data, size_t Size) : mFdp(Data, Size) {}
  void Process();

private:
  void CreateBrowsePacket(std::vector<uint8_t>& packet);
  void CreateAvrcpPacket(std::vector<uint8_t>& packet);
  FuzzedDataProvider mFdp;
};

void AVRCPDeviceFuzzer::CreateBrowsePacket(std::vector<uint8_t>& packet) {
  // Used to consume a maximum of 50% of the data to create packet.
  // This ensures that we don't completely exhaust data and use the rest 50% for
  // fuzzing of APIs.
  packet = mFdp.ConsumeBytes<uint8_t>(
          mFdp.ConsumeIntegralInRange<size_t>(0, mFdp.remaining_bytes() * 50 / 100));

  if (packet.size() < Packet::kMinSize()) {
    packet.resize(Packet::kMinSize());
  }
  packet[0] = (uint8_t)mFdp.PickValueInArray(kPduVal);
  // Set packet[1] and packet[2] to corresponding value of little endian
  uint16_t size = packet.size() - BrowsePacket::kMinSize();
  packet[1] = (size >> k8BitShift) & kMask;
  packet[2] = size & kMask;
}

void AVRCPDeviceFuzzer::CreateAvrcpPacket(std::vector<uint8_t>& packet) {
  // Used to consume a maximum of 50% of the data to create packet.
  // This ensures that we don't completely exhaust data and use the rest 50% for
  // fuzzing of APIs.
  packet = mFdp.ConsumeBytes<uint8_t>(
          mFdp.ConsumeIntegralInRange<size_t>(0, mFdp.remaining_bytes() * 50 / 100));
  if (packet.size() < Packet::kMinSize()) {
    packet.resize(Packet::kMinSize());
  }
  packet[0] = (uint8_t)mFdp.PickValueInArray(kValidCTypes);
  packet[2] = (uint8_t)mFdp.PickValueInArray(kValidOpCodes);
  if (packet[2] == uint8_t(Opcode::PASS_THROUGH)) {
    packet.resize(PassThroughPacket::kMinSize());
    packet[3] = mFdp.ConsumeBool() ? kMediaOpId : mFdp.ConsumeIntegral<uint8_t>();
  } else if (packet[2] == uint8_t(Opcode::VENDOR)) {
    if (packet.size() <= VendorPacket::kMinSize()) {
      packet.resize(VendorPacket::kMinSize() + 1);
    }
    packet[3] = mFdp.ConsumeIntegralInRange<uint8_t>(kMinScope, kMaxScope);
    packet[5] = (uint8_t)mFdp.ConsumeBool();  // Direction
    packet[6] = (uint8_t)mFdp.PickValueInArray(kCommandPduVal);
    // Set packet[8] and packet[9] to corresponding value of little endian
    uint16_t size = packet.size() - VendorPacket::kMinSize();
    packet[8] = (size >> k8BitShift) & kMask;
    packet[9] = size & kMask;
  }
}
void AVRCPDeviceFuzzer::Process() {
  FakeMediaInterface fmi(&mFdp);
  FakeVolumeInterface fvi(&mFdp);
  FakeA2dpInterface fai;
  FakePlayerSettingsInterface fpsi(&mFdp);

  Device device(RawAddress::kAny /* bdaddr */, mFdp.ConsumeBool() /* avrcp13_compatibility */,
                base::BindRepeating([](uint8_t, bool, std::unique_ptr<::bluetooth::PacketBuilder>) {
                }) /* send_msg_cb */,
                mFdp.ConsumeIntegral<uint16_t>() /* ctrl_mtu */,
                mFdp.ConsumeIntegral<uint16_t>() /* browse_mtu */);

  device.RegisterInterfaces(&fmi, &fai, &fvi, &fpsi);

  while (mFdp.remaining_bytes()) {
    auto invokeAVRCP = mFdp.PickValueInArray<const std::function<void()>>({
            [&]() { device.SetBrowseMtu(mFdp.ConsumeIntegral<uint16_t>() /*  browse_mtu */); },
            [&]() { device.SetBipClientStatus(mFdp.ConsumeBool() /* connected */); },
            [&]() {
              std::vector<uint8_t> browse_packet;
              CreateBrowsePacket(browse_packet);
              auto browse_request = TestPacketType<BrowsePacket>::Make(browse_packet);
              device.BrowseMessageReceived(mFdp.ConsumeIntegral<uint8_t>() /* label */,
                                           browse_request);
            },
            [&]() {
              /* Crafting PassThroughPacket Packets */
              std::vector<uint8_t> avrcp_packet;
              CreateAvrcpPacket(avrcp_packet);
              auto avrcp_request = TestPacketType<avrcp::Packet>::Make(avrcp_packet);
              device.MessageReceived(mFdp.ConsumeIntegral<uint8_t>() /* label */, avrcp_request);
            },
            [&]() {
              device.SendMediaUpdate(mFdp.ConsumeBool() /* metadata */,
                                     mFdp.ConsumeBool() /* play_status */,
                                     mFdp.ConsumeBool() /* queue */);
            },
            [&]() {
              device.SendFolderUpdate(mFdp.ConsumeBool() /* available_players */,
                                      mFdp.ConsumeBool() /* addressed_player */,
                                      mFdp.ConsumeBool() /* uids */);
            },
    });
    invokeAVRCP();
  }
  device.DeviceDisconnected();
}

extern "C" int LLVMFuzzerTestOneInput(const uint8_t* Data, size_t Size) {
  AVRCPDeviceFuzzer avrcp_device_fuzzer(Data, Size);
  avrcp_device_fuzzer.Process();
  return 0;
}
}  // namespace avrcp
}  // namespace bluetooth

const stack_config_t* stack_config_get_interface(void) { return &bluetooth::avrcp::interface; }
