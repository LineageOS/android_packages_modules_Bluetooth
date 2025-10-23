// Copyright (C) 2025, The Android Open Source Project
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

pub mod common {
    pub mod lhdc_level;
}

mod kiss_fft;
pub mod lhdc_enc {
    pub mod lhdc_enc_freq_process;
    pub mod lhdc_enc_header;
    pub mod lhdc_enc_workspace;
}

pub mod lhdc_api;

mod arith;
mod bits;
pub mod enc;
mod ffi;
mod math;

/// Inits logging for Android
#[cfg(target_os = "android")]
pub fn init_logging() {
    android_logger::init_once(android_logger::Config::default().with_tag("bluetooth"));
}

/// Inits logging for host
#[cfg(not(target_os = "android"))]
pub fn init_logging() {
    env_logger::Builder::new().parse_default_env().try_init().ok();
}
