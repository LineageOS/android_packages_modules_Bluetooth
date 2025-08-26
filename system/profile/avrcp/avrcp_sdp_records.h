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

#include <atomic>
#include <cstdint>
#include <map>
#include <string>

namespace bluetooth::avrcp {
constexpr uint32_t RECORD_NOT_ASSIGNED = -1u;
constexpr uint16_t UNASSIGNED_REQUEST_ID = -1;

/**
 * Struct containing all the required data to add the AVRC SDP records.
 */
struct AvrcpSdpRecord {
  /**
   * Service uuid for the SDP record.
   */
  uint16_t service_uuid;

  /**
   * Service name for the record.
   */
  std::string service_name;

  /**
   * Provider name for the record.
   */
  std::string provider_name;

  /**
   * Categories of features that are supported.
   * Each bit represents the feature that is supported.
   */
  uint16_t categories;

  /**
   * Is browse supported by the service.
   */
  bool browse_supported;

  /**
   * Profile version for the service.
   */
  uint16_t profile_version;

  /**
   * Cover art psm for the service.
   */
  uint16_t cover_art_psm;

  /***
   *
   * Sets the category bit to the existing categories.
   * @param category category bit that needs to be added.
   */
  void AddToExistingCategories(uint16_t category) { categories |= category; }

  /**
   * Remove the category bit from the existing set of categories.
   * @param category category bit that needs to be removed.
   */
  void RemoveCategory(uint16_t category) { categories &= ~category; }
};

/**
 * Abstract class to add, remove AVRC SDP records.
 */
class AvrcSdpRecordHelper {
public:
  /**
   * Default constructor.
   */
  AvrcSdpRecordHelper() : request_id_counter_(0) {}

  /**
   * Default virtual destructor.
   */
  virtual ~AvrcSdpRecordHelper() = default;

  /**
   * Adds the records if none exists. If records already exists, then it only
   * updates the categories that can be supported.
   * @param request_id unique request id that needs to be assigned. It generate unique id only if
   * the previous request doesn't exist.
   * @param add_record_request record request that needs
   * @return the request id.
   */
  virtual uint16_t AddRecord(const AvrcpSdpRecord& add_record_request, uint16_t& request_id,
                             bool add_sys_uid = true);

  /**
   * Removes the SDP records. If there were multiple SDP record request, it would remove the
   * corresponding request and use the rest of the records to identify the SDP record that needs
   * to be created.
   * @param request_id id of the previous request for which the record needs to be removed.
   * @return AVRC_SUCCESS if successful.
   *         AVRC_FAIL otherwise
   */
  uint16_t RemoveRecord(uint16_t request_id);

  /**
   * Abstract method for child class to implement.
   * @param cover_art_psm cover art protocol service multiplexor.
   * @param request_id id of the previous request for which cover art needs to be enabled.
   * @return AVRC_SUCCESS if successful.
   *         AVRC_FAIL otherwise
   */
  virtual uint16_t EnableCoverArt(uint16_t cover_art_psm, uint16_t request_id) = 0;

  /**
   * Abstract method for child class to implement.
   * @param request_id id of the previous request for which cover art needs to be disabled.
   * @return AVRC_SUCCESS if successful.
   *         AVRC_FAIL otherwise
   */
  virtual uint16_t DisableCoverArt(uint16_t request_id) = 0;

protected:
  /**
   * Record handle for the SDP records.
   */
  uint32_t sdp_record_handle_ = -1;

  /**
   * Update the SDP record with the new set of categories.
   * @param updated_categories new categories bits that needs to be added.
   * @return AVRC_SUCCESS if successful.
   *         AVRC_FAIL otherwise
   */
  virtual uint16_t UpdateRecord(uint16_t updated_categories);

  /**
   * Merges all the cached requests submitted to the service.
   * @param add_sdp_record_request reference of sdp record that needs to be merged.
   * @return
   */
  virtual bool MergeSdpRecords(AvrcpSdpRecord& add_sdp_record_request);

  /**
   * Map of request id v/s individual sdp record requested by individual clients.
   */
  std::map<uint16_t, AvrcpSdpRecord> sdp_record_request_map_;

private:
  std::atomic<uint16_t> request_id_counter_;
};

/**
 * Helper class to add Control AVRC SDP records.
 */
class ControlAvrcSdpRecordHelper : public AvrcSdpRecordHelper {
public:
  /**
   * Default constructor.
   */
  ControlAvrcSdpRecordHelper() = default;

  /**
   * Unsupported method for control SDP records.
   * @param cover_art_psm no-op.
   * @param request_id no-op
   * @return AVRC_FAIL as it's unsupported.
   */
  uint16_t EnableCoverArt(uint16_t cover_art_psm, uint16_t request_id) override;

  /**
   * Unsupported method for control SDP records.
   * @param request_id no-op
   * @return AVRC_FAIL as it's unsupported.
   */
  uint16_t DisableCoverArt(uint16_t request_id) override;

protected:
  /**
   * Invokes the base class's UpdateRecord. Also updates the class attributes based on the
   * profile version.
   * @param updated_categories new categories that needs to be updated.
   * @return AVRC_SUCCESS if successful.
   *         AVRC_FAIL otherwise
   */
  uint16_t UpdateRecord(uint16_t updated_categories) override;
};

/**
 * Helper class to add Target AVRC SDP records.
 */
class TargetAvrcSdpRecordHelper : public AvrcSdpRecordHelper {
public:
  /**
   * Default constructor.
   */
  TargetAvrcSdpRecordHelper() = default;

  /**
   * Enables cover art support. It removes the existing SDP records, updates the
   * cached SDP record request with cover art attributes (categories & cover art
   * psm), creates new AVRC SDP records.
   * @param cover_art_psm cover art protocol service multiplexor.
   * @return AVRC_SUCCESS if successful.
   *         AVRC_FAIL otherwise
   */
  uint16_t EnableCoverArt(uint16_t cover_art_psm, uint16_t request_id) override;

  /**
   * Disables cover art support. It removes the existing SDP records, removes
   * the cached SDP record request with cover art attributes (categories & cover
   * art psm), creates new AVRC SDP records w/o cover art support.
   * @param cover_art_psm cover art protocol service multiplexor
   * @return AVRC_SUCCESS if successful.
   *         AVRC_FAIL otherwise
   */
  uint16_t DisableCoverArt(uint16_t request_id) override;
};
}  // namespace bluetooth::avrcp
