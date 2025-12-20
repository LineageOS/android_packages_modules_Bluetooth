// Copyright 2022, The Android Open Source Project
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

use cxx::{type_id, ExternType};
pub use inner::*;

/// SAFETY: Our Uuid matches the C++ Uuid.
unsafe impl ExternType for Uuid {
    type Id = type_id!("bluetooth::Uuid");
    type Kind = cxx::kind::Trivial;
}

#[allow(dead_code)]
#[cxx::bridge]
mod inner {
    #[namespace = "bluetooth"]
    extern "C++" {
        include!("bluetooth/types/uuid.h");
        type Uuid = crate::core::uuid::Uuid;
    }
}
