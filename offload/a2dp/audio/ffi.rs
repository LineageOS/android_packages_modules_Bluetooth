// Copyright (C) 2026, The Android Open Source Project
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

use crate::client;
use std::ffi::{c_int, c_void};
use std::{fmt, slice};

#[cfg(feature = "sbc")]
use crate::sbc::CSbcConfig;

/// Codec type selection, match C enum `swoff_codec_type`,
/// discriminates the `CCodecConfig` configuration
#[repr(C)]
#[allow(dead_code)]
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum CCodecType {
    Sbc = 0,
}

/// Codec configuration, match C union `swoff_audio_config.codec`.
/// The structure type is discriminated by `CCodecType`.
#[repr(C)]
#[derive(Clone, Copy)]
pub union CCodecConfig {
    #[cfg(feature = "sbc")]
    pub sbc: CSbcConfig,
}

/// Audio configuration, match the C struct `swoff_audio_config`.
#[repr(C)]
pub struct CAudioConfig {
    pub channel_mode: c_int,
    pub bitdepth: c_int,
    pub sample_rate: c_int,
    pub frame_duration_us: c_int,
    pub codec_type: CCodecType,
    pub codec_config: CCodecConfig,
}

// Manually implement Debug for CAudioConfig to handle the union.
impl fmt::Debug for CAudioConfig {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let mut debug_struct = f.debug_struct("CAudioConfig");
        debug_struct
            .field("channel_mode", &self.channel_mode)
            .field("bitdepth", &self.bitdepth)
            .field("sample_rate", &self.sample_rate)
            .field("frame_duration_us", &self.frame_duration_us)
            .field("codec_type", &self.codec_type);

        // SAFETY: The `codec_type` field determines which field of the
        // `codec_config` union is active and safe to access.
        unsafe {
            match self.codec_type {
                #[cfg(feature = "sbc")]
                CCodecType::Sbc => debug_struct.field("codec_config", &self.codec_config.sbc),
            };
        }

        debug_struct.finish()
    }
}

/// C Callbacks called from Rust, match the C struct `A2dpAudio::Callbacks`.
/// `handle` is a pointer initialized by the C code and passed to all other functions.
#[repr(C)]
#[derive(Clone, Copy)]
pub struct CCallbacks {
    handle: *const c_void,
    /// Called when the A2DP stream has started.
    ///
    /// SAFETY: The caller must ensure that the `handle` passed to this function is valid
    /// and points to the context expected by the C implementation.
    start: unsafe extern "C" fn(handle: *const c_void),
    /// Called when the A2DP stream has stopped.
    ///
    /// SAFETY: The caller must ensure that the `handle` passed to this function is valid
    /// and points to the context expected by the C implementation.
    stop: unsafe extern "C" fn(handle: *const c_void),
}

//SAFETY: CCallbacks is safe to send between threads because we require the C code
//        which initialises it to only use pointers to functions which are safe
//        to call from any thread.
unsafe impl Send for CCallbacks {}

impl CCallbacks {
    pub fn start(&self) {
        // SAFETY: The `start` callback requires that the passed `handle` is valid
        // and points to the expected context. We satisfy this by passing `self.handle`,
        // which was initialized by the C code specifically for this callback.
        unsafe { (self.start)(self.handle) };
    }

    pub fn stop(&self) {
        // SAFETY: The `stop` callback requires that the passed `handle` is valid
        // and points to the expected context. We satisfy this by passing `self.handle`,
        // which was initialized by the C code specifically for this callback.
        unsafe { (self.stop)(self.handle) };
    }
}

/// C Interface to set up the A2DP audio offload stream.
/// Returns 0 on success, -1 on failure.
///
/// SAFETY:
/// The caller must ensure that:
/// * `audio` is a valid, non-null pointer to a `CAudioConfig`.
/// * `callbacks` is a valid, non-null pointer to a `CCallbacks`.
/// * The `audio` struct is initialized correctly, with the `codec_config` union
///   matching the `codec_type`.
#[no_mangle]
pub unsafe extern "C" fn swoff_a2dp_setup(
    audio: *const CAudioConfig,
    callbacks: *const CCallbacks,
) -> c_int {
    crate::utils::init_logging();

    log::info!("A2dpModule::swoff_a2dp_setup: audio: {:?}", audio);

    // SAFETY: The caller guarantees that `audio` is a valid pointer if not null.
    let Some(audio_ref) = (unsafe { audio.as_ref() }) else {
        log::error!("swoff_a2dp_setup: audio pointer is null");
        return -1;
    };

    // SAFETY: The caller guarantees that `callbacks` is a valid pointer if not null.
    let Some(callbacks_ref) = (unsafe { callbacks.as_ref() }) else {
        log::error!("swoff_a2dp_setup: callbacks pointer is null");
        return -1;
    };

    // SAFETY: We have verified the pointers are not null.
    // The caller guarantees the `audio` union matches the type tag.
    let result = unsafe { client::setup(audio_ref, callbacks_ref) };

    match result {
        Ok(_) => 0,
        Err(e) => {
            log::error!("Failed to setup stream: {e}");
            -1
        }
    }
}

/// C Interface to tear down the A2DP audio offload stream.
#[no_mangle]
pub extern "C" fn swoff_a2dp_teardown() {
    log::info!("A2dpModule::swoff_a2dp_teardown");
    client::teardown();
}

/// C Interface to write PCM data to the stream.
/// Returns the number of bytes written, or a negative value on error.
///
/// SAFETY:
/// The caller must ensure that:
/// * `data` is a valid, non-null pointer to a buffer of at least `len` bytes.
/// * The memory range `[data, data + len)` is valid for reading.
#[no_mangle]
pub unsafe extern "C" fn swoff_a2dp_write(data: *const c_void, len: usize) -> isize {
    if data.is_null() {
        log::error!("swoff_a2dp_write: data pointer is null");
        return -1;
    }

    // SAFETY: We have verified that `data` is not null.
    // The caller guarantees that `data` points to a valid buffer of `len` bytes.
    let chunk = unsafe { slice::from_raw_parts(data.cast(), len) };

    match client::write(chunk) {
        Ok(n) => n as isize,
        Err(reason) => {
            log::warn!("Unable to write PCM data: {reason}");
            -1
        }
    }
}
