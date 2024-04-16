/*
 * Copyright 2019 The Android Open Source Project
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

#include "neighbor/facade/facade.h"

#include <bluetooth/log.h>

#include <memory>

#include "blueberry/facade/neighbor/facade.grpc.pb.h"

using ::grpc::ServerAsyncResponseWriter;
using ::grpc::ServerAsyncWriter;
using ::grpc::ServerContext;

namespace bluetooth {
namespace neighbor {
namespace facade {

using namespace blueberry::facade::neighbor;

class NeighborFacadeService : public NeighborFacade::Service {
 public:
  NeighborFacadeService(
      ScanModule* scan_module)
      : scan_module_(scan_module) {}

  ::grpc::Status EnablePageScan(
      ::grpc::ServerContext* /* context */,
      const EnableMsg* request,
      ::google::protobuf::Empty* /* response */) override {
    if (request->enabled()) {
      scan_module_->SetPageScan();
    } else {
      scan_module_->ClearPageScan();
    }
    return ::grpc::Status::OK;
  }

 private:
  ScanModule* scan_module_;
};

void NeighborFacadeModule::ListDependencies(ModuleList* list) const {
  ::bluetooth::grpc::GrpcFacadeModule::ListDependencies(list);
  list->add<ScanModule>();
}

void NeighborFacadeModule::Start() {
  ::bluetooth::grpc::GrpcFacadeModule::Start();
  service_ = new NeighborFacadeService(GetDependency<ScanModule>());
}

void NeighborFacadeModule::Stop() {
  delete service_;
  ::bluetooth::grpc::GrpcFacadeModule::Stop();
}

::grpc::Service* NeighborFacadeModule::GetService() const {
  return service_;
}

const ModuleFactory NeighborFacadeModule::Factory =
    ::bluetooth::ModuleFactory([]() { return new NeighborFacadeModule(); });

}  // namespace facade
}  // namespace neighbor
}  // namespace bluetooth
