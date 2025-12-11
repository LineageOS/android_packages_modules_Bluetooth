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

use crate::client::Stream;
use std::ffi::{c_int, c_uint, c_void};
use std::slice;
use std::time::Duration;

#[cfg(feature = "lc3")]
use crate::lc3::Lc3CConfig;

#[cfg(feature = "opus")]
use crate::opus::OpusCConfig;

/// Codec type selection, match C enum `swoff_codec_type`,
/// discriminates the `CCodecConfig` configuration
#[repr(C)]
#[allow(dead_code)]
#[derive(Clone, Copy, PartialEq)]
pub enum CCodecType {
    Lc3,
    Opus,
}

/// Codec configuration, match C union `swoff_audio_config.codec`.
/// The structure type is discriminated by `CCodecType`.
#[repr(C)]
#[derive(Clone, Copy)]
pub union CCodecConfig {
    #[cfg(feature = "lc3")]
    pub lc3: Lc3CConfig,
    #[cfg(feature = "opus")]
    pub opus: OpusCConfig,
}

/// Audio configuration, match the C struct `swoff_audio_config`.
#[repr(C)]
pub struct CAudioConfig {
    pub bitdepth: c_int,
    pub sample_rate: c_int,
    pub frame_duration_us: c_int,
    pub codec_type: CCodecType,
    pub codec_config: CCodecConfig,
}

/// BIS/CIS definition, match the C struct `swoff_iso_stream`.
#[repr(C)]
#[derive(Clone, Copy)]
pub struct CIsoStream {
    pub handle: u16,
    pub channel_allocation: u32,
}

/// C Callbacks called from Rust, match the C struct `swoff_lea_callbacks`.
/// `handle` is a pointer initialized by the C code and passed to all other functions.
#[repr(C)]
#[derive(Clone, Copy)]
pub struct CCallbacks {
    handle: *const c_void,
    start: unsafe extern "C" fn(handle: *const c_void),
    stop: unsafe extern "C" fn(handle: *const c_void),
}

//SAFETY: CCallbacks is safe to send between threads because we require the C code
//        which initialises it to only use pointers to functions which are safe
//        to call from any thread.
unsafe impl Send for CCallbacks {}

impl CCallbacks {
    pub fn start(&self) {
        // SAFETY: It's required that C code returned a valid address of function,
        //         living until `swoff_leaudio_drop()` call.
        unsafe { (self.start)(self.handle) };
    }

    pub fn stop(&self) {
        // SAFETY: It's required that C code returned a valid address of function,
        //         living until `swoff_leaudio_drop()` call.
        unsafe { (self.stop)(self.handle) };
    }
}

/// C Interface that setup an input PCM audio stream transported on one or more
/// CIS/BIS Isochronous streams
#[no_mangle]
pub extern "C" fn swoff_leaudio_setup(
    iso_streams: *const CIsoStream,
    num_iso_streams: usize,
    audio: *const CAudioConfig,
    anchor_delay_us: c_uint,
    callbacks: *const CCallbacks,
) -> *mut Stream {
    crate::utils::init_logging();

    match Stream::new(
        // SAFETY: `iso_streams` points to a table of `CIsoStream` with `num_iso_streams` entries,
        //         valid until this function returns.
        unsafe { slice::from_raw_parts(iso_streams, num_iso_streams) },
        // SAFETY: `audio` points to a `CAudioConfig` structure, valid until this function returns.
        unsafe { audio.as_ref() }.unwrap(),
        Duration::from_micros(anchor_delay_us.into()),
        // SAFETY: `callbacks` points to `CCallbacks`, valid until this function returns.
        unsafe { callbacks.as_ref() }.unwrap(),
    ) {
        Ok(stream) => Box::into_raw(Box::new(stream)),
        Err(e) => {
            log::error!("Failed to setup stream: {e}");
            std::ptr::null_mut()
        }
    }
}

/// C Interface that drop the allocated `handle`, returned by `swoff_leaudio_setup()`
#[no_mangle]
pub extern "C" fn swoff_leaudio_drop(handle: *mut Stream) {
    // SAFETY: The C code returns `handle` pointing `Stream` object, which has been
    //         allocated by `swoff_leaudio_setup()`. It's required that this function
    //         is called only for a specific `handle`, and any other function
    //         relative to this object is not called after this function returns.
    let _ = unsafe { Box::from_raw(handle) };
}

/// C Interface writing the PCM input stream
#[no_mangle]
pub extern "C" fn swoff_leaudio_write(
    handle: *mut Stream,
    data: *const c_void,
    len: usize,
) -> usize {
    // SAFETY: The C code returns `handle` pointing `Stream` object, which has been
    //         allocated by `swoff_leaudio_setup()`. It's required that this function
    //         is not called after `swoff_leaudio_drop()`, that drops the object.
    let stream = unsafe { Box::from_raw(handle) };

    // SAFETY: `data` points to a buffer of `len` bytes valid until the function returns.
    let result = match stream.write(unsafe { slice::from_raw_parts(data.cast(), len) }) {
        Ok(n) => n,
        Err(reason) => {
            log::warn!("Unable to write PCM data: {reason}");
            0
        }
    };

    let _ = Box::into_raw(stream);
    result
}
