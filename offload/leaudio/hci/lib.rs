// Copyright (C) 2024, The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

//! LE Audio HCI-Proxy module
//!
//! The module looks at HCI Commands / Events to control the mux of ISO packet
//! flows coming from the stack and the exposed "LE Audio HCI Proxy" AIDL service:
//!
//!       HCI | ^        | HCI                               AIDL
//!   Command | |        | ISO Packets                     Interface
//!        ___|_|________|__         ___________          ____________
//!       |   : : proxy  |  |       |  arbiter  |        |  service   |
//!       |   : :        |  |       |           |        |            |
//!       |   : :         `-|-------|----   ----|--------|            |
//!       |   : :           |       |    \ /    |        |            |
//!       |   : :           | Ctrl  |     :     |        |            |
//!       |   : :  ---------|------>|     :     |  Ctrl  |            |
//!       |   : :  ---------|------ |     :     | ------>|            |
//!       |___:_:________ __|       |_____:_____|        |____________|
//!           | |                         |
//!           | | HCI                     | HCI
//!           v | Event                   V ISO Packets

mod arbiter;
mod proxy;
mod service;

#[cfg(test)]
mod tests;

pub use proxy::LeAudioModuleBuilder;
