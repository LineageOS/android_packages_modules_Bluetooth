/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#pragma once

#include <bluetooth/log.h>

#include <cstdint>
#include <memory>

#include "avrcp_sdp_records.h"

namespace bluetooth::avrcp {

/**
 * Service to add AVRCP sdp record for control and target services.
 * Clients should use the singleton instance to add SDP records for the AVRCP
 * service. The singleton instance assigns a unique handle for the respective
 * services. This allows additive updates to the SDP records from different
 * services.
 */
class AvrcpSdpService {
public:
  /**
   * Creates an instance of the service. If instance is already created, then
   * it returns the previous instance.
   * @return singleton instance of the class.
   */
  static std::shared_ptr<AvrcpSdpService> Get();

  /**
   * Add the sdp record for the service based on the UUID.
   * @param add_sdp_record_request request to the add the sdp record.
   * @param request_id unique request id that needs to be assigned.
   * @return AVRC_SUCCESS if successful.
   *         AVRC_FAIL otherwise
   */
  uint16_t AddRecord(const AvrcpSdpRecord& add_sdp_record_request, uint16_t& request_id);

  /**
   * Enables the cover art dynamically for the Target SDP records. It also sets
   * the cover art bit in the supported categories. Enabling cover art
   * dynamically for Control SDP records is not supported yet.
   * @param service_uuid service uuid for which the cover art needs to be
   * enabled.
   * @param cover_art_psm Hanle for the cover art psm.
   * @param request_id id of the previous request for which cover art needs to be enabled.
   * @return AVRC_SUCCESS if successful.
   *         AVRC_FAIL otherwise
   */
  uint16_t EnableCoverArt(const uint16_t service_uuid, uint16_t cover_art_psm,
                          const uint16_t request_id);

  /**
   * Dynamically disable the cover art for Control SDP records. It also removes
   * the cover art bit in the supported categories.
   * @param service_uuid service UUID for which the cover art needs to be
   * disabled.
   * @param request_id id of the previous request for which cover art needs to be disabled.
   * @return AVRC_SUCCESS if successful.
   *         AVRC_FAIL otherwise
   */
  uint16_t DisableCoverArt(const uint16_t service_uuid, const uint16_t request_id);

  /**
   * Removes the entire record for the corresponding service.
   * @param service_uuid
   * @param request_id id of the previous request that needs to be removed.
   * @return AVRC_SUCCESS if successful.
   *         AVRC_FAIL otherwise
   */
  uint16_t RemoveRecord(const uint16_t service_uuid, const uint16_t request_id);

  ~AvrcpSdpService() = default;

private:
  /**
   * Helper instance to add the AVRCP Control SDP record for control service.
   */
  ControlAvrcSdpRecordHelper control_sdp_record_helper_;

  /**
   * Helper instance to add the AVRCP Target SDP record for target service.
   */
  TargetAvrcSdpRecordHelper target_sdp_record_helper_;

  /**
   * Singleton instance of the class.
   */
  static std::shared_ptr<AvrcpSdpService> instance_;
};

}  // namespace bluetooth::avrcp
