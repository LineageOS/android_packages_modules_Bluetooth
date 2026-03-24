#include <android_bluetooth_sysprop.h>
#include <gtest/gtest.h>

#include "bta/mock/mock_bta_hf_client_api.h"
#include "bta_hfp_api.h"
#include "btif/include/btif_common.h"
#include "btif/include/btif_hf_client.h"
#include "btif/include/btif_profile_queue.h"
#include "btif/include/btif_util.h"
#include "btif_status.h"

using testing::_;
using testing::Return;

#define DEFAULT_BTIF_HF_CLIENT_FEATURES                                         \
  (BTA_HF_CLIENT_FEAT_ECNR | BTA_HF_CLIENT_FEAT_3WAY | BTA_HF_CLIENT_FEAT_CLI | \
   BTA_HF_CLIENT_FEAT_VREC | BTA_HF_CLIENT_FEAT_VOL | BTA_HF_CLIENT_FEAT_ECS |  \
   BTA_HF_CLIENT_FEAT_ECC | BTA_HF_CLIENT_FEAT_CODEC)

BtStatus btif_transfer_context(tBTIF_CBACK* /*p_cback*/, uint16_t /*event*/, char* /*p_params*/,
                               int /*param_len*/, tBTIF_COPY_CBACK* /*p_copy_cback*/) {
  return BtifStatus();
}
BtStatus do_in_jni_thread(base::OnceClosure /*task*/) { return BtifStatus(); }
void btif_disable_service(tBTA_SERVICE_ID /* service_id */) {}
void btif_enable_service(tBTA_SERVICE_ID /* service_id */) {}
BtStatus btif_queue_connect(uint16_t /*uuid*/, RawAddress /*bda*/,
                            btif_connect_cb_t /*connect_cb*/) {
  return BtifStatus();
}
void btif_queue_cleanup(uint16_t /*uuid*/) {}
void btif_queue_advance() {}
std::string dump_hf_client_event(uint16_t /*event*/) { return "UNKNOWN MSG ID"; }

class BtifHfClientTest : public ::testing::Test {
protected:
  void SetUp() override { MockBtaHfClientApi::SetInstance(&bta_hf_client_api); }

  void TearDown() override { MockBtaHfClientApi::SetInstance(nullptr); }

  MockBtaHfClientApi bta_hf_client_api;
};

TEST_F(BtifHfClientTest, test_btif_hf_client_service) {
  EXPECT_CALL(bta_hf_client_api, get_default_hfp_version).WillOnce(Return(HFP_VERSION_1_9));

  EXPECT_CALL(bta_hf_client_api, get_default_hf_client_features)
          .WillOnce(Return(DEFAULT_BTIF_HF_CLIENT_FEATURES));

  // btif_hf_client_execute_service will enable additional features
  // when the supported HFP version is greater than 1.7, and the sysprop
  // bluetooth.hfp.swb.supported is set.
  // TODO: additional tests to validate that these features are not set for
  // incompatible versions.
  EXPECT_CALL(
          bta_hf_client_api,
          BTA_HfClientEnable(_, DEFAULT_BTIF_HF_CLIENT_FEATURES | BTA_HF_CLIENT_FEAT_ESCO_S4, _))
          .WillOnce(Return(BTA_SUCCESS));

  btif_hf_client_execute_service(/* enable= */ true);
}
