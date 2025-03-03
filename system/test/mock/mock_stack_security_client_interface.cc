/*
 * Copyright 2024 The Android Open Source Project
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
 */

#include "test/mock/mock_stack_security_client_interface.h"

#include "stack/include/security_client_callbacks.h"

namespace {

// Initialize the working btm client interface to the default
MockSecurityClientInterface default_mock_security_client_interface;

// Initialize the working btm client interface to the default
SecurityClientInterface* mock_security_client_interface = &default_mock_security_client_interface;

}  // namespace

// Reset the working btm client interface to the default
void reset_mock_security_client_interface() {
  mock_security_client_interface = &default_mock_security_client_interface;
}

// Serve the working mock security interface
const SecurityClientInterface& get_security_client_interface() {
  return *mock_security_client_interface;
}

void set_security_client_interface(SecurityClientInterface& interface) {
  mock_security_client_interface = &interface;
}
