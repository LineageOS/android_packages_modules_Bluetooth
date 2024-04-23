/******************************************************************************
 *
 *  Copyright 2019 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

#include <bluetooth/log.h>

#include "hci/octets.h"
#include "os/rand.h"
#include "security/pairing_handler_le.h"

using bluetooth::os::GenerateRandom;

namespace bluetooth {
namespace security {

using hci::Octet16;

LegacyStage1ResultOrFailure PairingHandlerLe::DoLegacyStage1(const InitialInformations& i,
                                                             const PairingRequestView& pairing_request,
                                                             const PairingResponseView& pairing_response) {
  if (((pairing_request.GetAuthReq() | pairing_response.GetAuthReq()) & AuthReqMaskMitm) == 0) {
    // If both devices have not set MITM option, Just Works shall be used
    return LegacyJustWorks();
  }

  if (pairing_request.GetOobDataFlag() == OobDataFlag::PRESENT &&
      pairing_response.GetOobDataFlag() == OobDataFlag::PRESENT) {
    // OobDataFlag remote_oob_flag = IAmCentral(i) ? pairing_response.GetOobDataFlag() :
    // pairing_request.GetOobDataFlag(); OobDataFlag my_oob_flag = IAmCentral(i) ? pairing_request.GetOobDataFlag() :
    // pairing_response.GetOobDataFlag();
    return LegacyOutOfBand(i);
  }

  const auto& iom = pairing_request.GetIoCapability();
  const auto& ios = pairing_response.GetIoCapability();

  if (iom == IoCapability::NO_INPUT_NO_OUTPUT || ios == IoCapability::NO_INPUT_NO_OUTPUT) {
    return LegacyJustWorks();
  }

  if ((iom == IoCapability::DISPLAY_ONLY || iom == IoCapability::DISPLAY_YES_NO) &&
      (ios == IoCapability::DISPLAY_ONLY || ios == IoCapability::DISPLAY_YES_NO)) {
    return LegacyJustWorks();
  }

  // This if() should not be needed, these are only combinations left.
  if (iom == IoCapability::KEYBOARD_DISPLAY || iom == IoCapability::KEYBOARD_ONLY ||
      ios == IoCapability::KEYBOARD_DISPLAY || ios == IoCapability::KEYBOARD_ONLY) {
    IoCapability my_iocaps = IAmCentral(i) ? iom : ios;
    IoCapability remote_iocaps = IAmCentral(i) ? ios : iom;
    return LegacyPasskeyEntry(i, my_iocaps, remote_iocaps);
  }

  // We went through all possble combinations.
  log::fatal("This should never happen");
  return LegacyJustWorks();
}

LegacyStage1ResultOrFailure PairingHandlerLe::LegacyJustWorks() {
  log::info("Legacy Just Works start");
  return Octet16{0};
}

LegacyStage1ResultOrFailure PairingHandlerLe::LegacyPasskeyEntry(const InitialInformations& i,
                                                                 const IoCapability& my_iocaps,
                                                                 const IoCapability& remote_iocaps) {
  bool i_am_displaying = false;
  if (my_iocaps == IoCapability::DISPLAY_ONLY || my_iocaps == IoCapability::DISPLAY_YES_NO) {
    i_am_displaying = true;
  } else if (
      IAmCentral(i) && remote_iocaps == IoCapability::KEYBOARD_DISPLAY && my_iocaps == IoCapability::KEYBOARD_DISPLAY) {
    i_am_displaying = true;
  } else if (my_iocaps == IoCapability::KEYBOARD_DISPLAY && remote_iocaps == IoCapability::KEYBOARD_ONLY) {
    i_am_displaying = true;
  }

  log::info("Passkey Entry start {}", i_am_displaying ? "displaying" : "accepting");

  uint32_t passkey;
  if (i_am_displaying) {
    // generate passkey in a valid range
    passkey = GenerateRandom();
    passkey &= 0x0fffff; /* maximum 20 significant bits */
    constexpr uint32_t PASSKEY_MAX = 999999;
    if (passkey > PASSKEY_MAX) passkey >>= 1;

    ConfirmationData data(i.remote_connection_address, i.remote_name, passkey);
    i.user_interface_handler->Post(
        common::BindOnce(&UI::DisplayConfirmValue, common::Unretained(i.user_interface), data));
  } else {
    ConfirmationData data(i.remote_connection_address, i.remote_name);
    i.user_interface_handler->Post(
        common::BindOnce(&UI::DisplayEnterPasskeyDialog, common::Unretained(i.user_interface), data));
    std::optional<PairingEvent> response = WaitUiPasskey();
    if (!response) return PairingFailure("Passkey did not arrive!");

    passkey = response->ui_value;
  }

  Octet16 tk{0};
  tk[0] = (uint8_t)(passkey);
  tk[1] = (uint8_t)(passkey >> 8);
  tk[2] = (uint8_t)(passkey >> 16);
  tk[3] = (uint8_t)(passkey >> 24);

  log::info("Passkey Entry finish");
  return tk;
}

LegacyStage1ResultOrFailure PairingHandlerLe::LegacyOutOfBand(const InitialInformations& i) {
  return i.remote_oob_data->security_manager_tk_value;
}

StkOrFailure PairingHandlerLe::DoLegacyStage2(const InitialInformations& i, const PairingRequestView& pairing_request,
                                              const PairingResponseView& pairing_response, const Octet16& tk) {
  log::info("Legacy Step 2 start");
  std::vector<uint8_t> preq(pairing_request.begin(), pairing_request.end());
  std::vector<uint8_t> pres(pairing_response.begin(), pairing_response.end());

  Octet16 mrand, srand;
  if (IAmCentral(i)) {
    mrand = GenerateRandom<16>();

    // log::info("{} tk = {}", IAmCentral(i), base::HexEncode(tk.data(), tk.size()));
    // log::info("{} mrand = {}", IAmCentral(i), base::HexEncode(mrand.data(), mrand.size()));
    // log::info("{} pres = {}", IAmCentral(i), base::HexEncode(pres.data(), pres.size()));
    // log::info("{} preq = {}", IAmCentral(i), base::HexEncode(preq.data(), preq.size()));

    Octet16 mconfirm = crypto_toolbox::c1(
        tk,
        mrand,
        preq.data(),
        pres.data(),
        (uint8_t)i.my_connection_address.GetAddressType(),
        i.my_connection_address.GetAddress().data(),
        (uint8_t)i.remote_connection_address.GetAddressType(),
        i.remote_connection_address.GetAddress().data());

    // log::info("{} mconfirm = {}", IAmCentral(i), base::HexEncode(mconfirm.data(),
    // mconfirm.size()));

    log::info("Central sends Mconfirm");
    SendL2capPacket(i, PairingConfirmBuilder::Create(mconfirm));

    log::info("Central waits for the Sconfirm");
    auto sconfirm_pkt = WaitPairingConfirm();
    if (std::holds_alternative<PairingFailure>(sconfirm_pkt)) {
      return std::get<PairingFailure>(sconfirm_pkt);
    }
    Octet16 sconfirm = std::get<PairingConfirmView>(sconfirm_pkt).GetConfirmValue();

    log::info("Central sends Mrand");
    SendL2capPacket(i, PairingRandomBuilder::Create(mrand));

    log::info("Central waits for Srand");
    auto random_pkt = WaitPairingRandom();
    if (std::holds_alternative<PairingFailure>(random_pkt)) {
      return std::get<PairingFailure>(random_pkt);
    }
    srand = std::get<PairingRandomView>(random_pkt).GetRandomValue();

    // log::info("{} srand = {}", IAmCentral(i), base::HexEncode(srand.data(), srand.size()));

    Octet16 sconfirm_generated = crypto_toolbox::c1(
        tk,
        srand,
        preq.data(),
        pres.data(),
        (uint8_t)i.my_connection_address.GetAddressType(),
        i.my_connection_address.GetAddress().data(),
        (uint8_t)i.remote_connection_address.GetAddressType(),
        i.remote_connection_address.GetAddress().data());

    if (sconfirm != sconfirm_generated) {
      log::info("sconfirm does not match generated value");

      SendL2capPacket(i, PairingFailedBuilder::Create(PairingFailedReason::CONFIRM_VALUE_FAILED));
      return PairingFailure("sconfirm does not match generated value");
    }
  } else {
    srand = GenerateRandom<16>();

    std::vector<uint8_t> preq(pairing_request.begin(), pairing_request.end());
    std::vector<uint8_t> pres(pairing_response.begin(), pairing_response.end());

    Octet16 sconfirm = crypto_toolbox::c1(
        tk,
        srand,
        preq.data(),
        pres.data(),
        (uint8_t)i.remote_connection_address.GetAddressType(),
        i.remote_connection_address.GetAddress().data(),
        (uint8_t)i.my_connection_address.GetAddressType(),
        i.my_connection_address.GetAddress().data());

    log::info("Peripheral waits for the Mconfirm");
    auto mconfirm_pkt = WaitPairingConfirm();
    if (std::holds_alternative<PairingFailure>(mconfirm_pkt)) {
      return std::get<PairingFailure>(mconfirm_pkt);
    }
    Octet16 mconfirm = std::get<PairingConfirmView>(mconfirm_pkt).GetConfirmValue();

    log::info("Peripheral sends Sconfirm");
    SendL2capPacket(i, PairingConfirmBuilder::Create(sconfirm));

    log::info("Peripheral waits for Mrand");
    auto random_pkt = WaitPairingRandom();
    if (std::holds_alternative<PairingFailure>(random_pkt)) {
      return std::get<PairingFailure>(random_pkt);
    }
    mrand = std::get<PairingRandomView>(random_pkt).GetRandomValue();

    Octet16 mconfirm_generated = crypto_toolbox::c1(
        tk,
        mrand,
        preq.data(),
        pres.data(),
        (uint8_t)i.remote_connection_address.GetAddressType(),
        i.remote_connection_address.GetAddress().data(),
        (uint8_t)i.my_connection_address.GetAddressType(),
        i.my_connection_address.GetAddress().data());

    if (mconfirm != mconfirm_generated) {
      log::info("mconfirm does not match generated value");
      SendL2capPacket(i, PairingFailedBuilder::Create(PairingFailedReason::CONFIRM_VALUE_FAILED));
      return PairingFailure("mconfirm does not match generated value");
    }

    log::info("Peripheral sends Srand");
    SendL2capPacket(i, PairingRandomBuilder::Create(srand));
  }

  log::info("Legacy stage 2 finish");

  /* STK */
  return crypto_toolbox::s1(tk, mrand, srand);
}
}  // namespace security
}  // namespace bluetooth
