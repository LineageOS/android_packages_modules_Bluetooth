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

//! C entry points for the V3 encoder, for the A2DP codec to drive.

use super::encoder::Config;
use super::tables::kbps_for_index;
use super::session::Session;
use lhdcv3_format::BLOCK_SIZE;
use std::slice::{from_raw_parts, from_raw_parts_mut};

#[allow(non_camel_case_types)]
pub type HANDLE_LHDC_V3 = *mut Encoder;

#[allow(non_camel_case_types)]
pub type STATUS_LHDC_V3 = i32;

pub const LHDC_V3_SUCCESS: STATUS_LHDC_V3 = 0;
pub const LHDC_V3_ERROR_PARAM: STATUS_LHDC_V3 = -1;
pub const LHDC_V3_ERROR_STATE: STATUS_LHDC_V3 = -2;
pub const LHDC_V3_ERROR_ROOM: STATUS_LHDC_V3 = -3;

/// What one connection holds between calls.
pub struct Encoder {
    session: Option<Session>,
    sample_bytes: usize,
}

impl Encoder {
    fn new() -> Encoder {
        Encoder { session: None, sample_bytes: 2 }
    }
}

/// Splits interleaved stereo PCM into the two channels the encoder codes.
fn deinterleave(pcm: &[u8], sample_bytes: usize) -> (Vec<i32>, Vec<i32>) {
    let frames = pcm.len() / (sample_bytes * 2);
    let mut left = Vec::with_capacity(frames);
    let mut right = Vec::with_capacity(frames);
    for i in 0..frames {
        for (side, k) in [(&mut left, 0usize), (&mut right, 1usize)] {
            let at = (i * 2 + k) * sample_bytes;
            side.push(match sample_bytes {
                2 => i16::from_le_bytes([pcm[at], pcm[at + 1]]) as i32,
                _ => i32::from_le_bytes([0, pcm[at], pcm[at + 1], pcm[at + 2]]) >> 8,
            });
        }
    }
    (left, right)
}

/// # Safety
///
/// `handle` must be a valid pointer.
#[no_mangle]
pub unsafe extern "C" fn lhdcv3_enc_ffi_get_handle(
    handle: *mut HANDLE_LHDC_V3,
) -> STATUS_LHDC_V3 {
    if handle.is_null() {
        return LHDC_V3_ERROR_PARAM;
    }
    // SAFETY: `handle` has been checked non-null.
    unsafe { *handle = Box::into_raw(Box::new(Encoder::new())) };
    LHDC_V3_SUCCESS
}

/// # Safety
///
/// `handle` must be a value previously returned by `lhdcv3_enc_ffi_get_handle`
/// and not yet released.
#[no_mangle]
pub unsafe extern "C" fn lhdcv3_enc_ffi_free_handle(
    handle: HANDLE_LHDC_V3,
) -> STATUS_LHDC_V3 {
    if handle.is_null() {
        return LHDC_V3_ERROR_PARAM;
    }
    // SAFETY: the caller guarantees the handle came from get_handle and has
    // not been freed.
    drop(unsafe { Box::from_raw(handle) });
    LHDC_V3_SUCCESS
}

/// # Safety
///
/// `handle` must be a live handle from `lhdcv3_enc_ffi_get_handle`.
#[no_mangle]
pub unsafe extern "C" fn lhdcv3_enc_ffi_init_encoder(
    handle: HANDLE_LHDC_V3,
    sample_rate: u32,
    bits_per_sample: u32,
    bitrate_index: u32,
    mtu: u32,
    interval_ms: u32,
) -> STATUS_LHDC_V3 {
    if handle.is_null() {
        return LHDC_V3_ERROR_PARAM;
    }
    if !matches!(sample_rate, 44100 | 48000 | 96000) {
        return LHDC_V3_ERROR_PARAM;
    }
    if !matches!(bits_per_sample, 16 | 24) || mtu == 0 || interval_ms == 0 {
        return LHDC_V3_ERROR_PARAM;
    }
    // SAFETY: the caller guarantees the handle is live.
    let enc = unsafe { &mut *handle };
    let config = Config {
        sample_rate,
        bits_per_sample,
        bitrate_kbps: kbps_for_index(bitrate_index as usize),
    };
    enc.sample_bytes = if bits_per_sample > 16 { 3 } else { 2 };
    enc.session = Some(Session::new(config, mtu as usize, interval_ms));
    LHDC_V3_SUCCESS
}

/// Samples per channel one `lhdcv3_enc_ffi_encode` call takes.
///
/// # Safety
///
/// `samples` must be a valid pointer.
#[no_mangle]
pub unsafe extern "C" fn lhdcv3_enc_ffi_get_block_size(
    _handle: HANDLE_LHDC_V3,
    samples: *mut u32,
) -> STATUS_LHDC_V3 {
    if samples.is_null() {
        return LHDC_V3_ERROR_PARAM;
    }
    // SAFETY: `samples` has been checked non-null.
    unsafe { *samples = BLOCK_SIZE as u32 };
    LHDC_V3_SUCCESS
}

/// Sets the rate the coder aims at, for a stream whose rate adapts.
///
/// Refused where the rate would change whether the stream is halved, since
/// that would step the audio part way through.
///
/// # Safety
///
/// `handle` must be live and initialised.
#[no_mangle]
pub unsafe extern "C" fn lhdcv3_enc_ffi_set_bitrate(
    handle: HANDLE_LHDC_V3,
    kbps: u32,
) -> STATUS_LHDC_V3 {
    if handle.is_null() {
        return LHDC_V3_ERROR_PARAM;
    }
    // SAFETY: the caller guarantees the handle is live.
    let enc = unsafe { &mut *handle };
    let Some(session) = enc.session.as_mut() else {
        return LHDC_V3_ERROR_STATE;
    };
    if session.set_bitrate(kbps) {
        LHDC_V3_SUCCESS
    } else {
        LHDC_V3_ERROR_PARAM
    }
}

/// Drops whatever the coder holds, for a stream being flushed.
///
/// # Safety
///
/// `handle` must be live and initialised.
#[no_mangle]
pub unsafe extern "C" fn lhdcv3_enc_ffi_flush(handle: HANDLE_LHDC_V3) -> STATUS_LHDC_V3 {
    if handle.is_null() {
        return LHDC_V3_ERROR_PARAM;
    }
    // SAFETY: the caller guarantees the handle is live.
    let enc = unsafe { &mut *handle };
    match enc.session.as_mut() {
        None => LHDC_V3_ERROR_STATE,
        Some(session) => {
            session.discard();
            LHDC_V3_SUCCESS
        }
    }
}

/// Codes one block, writing a packet's worth of frames when one is ready.
///
/// # Safety
///
/// `handle` must be live and initialised. `pcm` and `out` must point to
/// readable and writable regions of at least the given lengths.
#[no_mangle]
pub unsafe extern "C" fn lhdcv3_enc_ffi_encode(
    handle: HANDLE_LHDC_V3,
    pcm: *const u8,
    pcm_len: usize,
    out: *mut u8,
    out_len: usize,
    written_bytes: *mut u32,
    written_frames: *mut u32,
) -> STATUS_LHDC_V3 {
    if handle.is_null() || pcm.is_null() || out.is_null() {
        return LHDC_V3_ERROR_PARAM;
    }
    if written_bytes.is_null() || written_frames.is_null() {
        return LHDC_V3_ERROR_PARAM;
    }
    // SAFETY: the caller guarantees the handle is live.
    let enc = unsafe { &mut *handle };
    let sample_bytes = enc.sample_bytes;
    let Some(session) = enc.session.as_mut() else {
        return LHDC_V3_ERROR_STATE;
    };
    if pcm_len < BLOCK_SIZE * sample_bytes * 2 {
        return LHDC_V3_ERROR_PARAM;
    }
    // SAFETY: `pcm` has been checked non-null and long enough.
    let (left, right) = deinterleave(unsafe { from_raw_parts(pcm, pcm_len) }, sample_bytes);

    let mut packet = Vec::new();
    let frames = session.encode(&left, &right, &mut packet);
    if packet.len() > out_len {
        return LHDC_V3_ERROR_ROOM;
    }
    // SAFETY: `out` has been checked non-null and long enough for the packet.
    unsafe { from_raw_parts_mut(out, out_len)[..packet.len()].copy_from_slice(&packet) };
    // SAFETY: both counters have been checked non-null.
    unsafe {
        *written_bytes = packet.len() as u32;
        *written_frames = frames as u32;
    }
    LHDC_V3_SUCCESS
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_handle_codes_a_block_and_is_released() {
        let mut h: HANDLE_LHDC_V3 = std::ptr::null_mut();
        unsafe {
            assert_eq!(lhdcv3_enc_ffi_get_handle(&mut h), LHDC_V3_SUCCESS);
            assert_eq!(
                lhdcv3_enc_ffi_init_encoder(h, 48000, 16, 7, 679, 20),
                LHDC_V3_SUCCESS
            );
            let mut samples = 0u32;
            assert_eq!(lhdcv3_enc_ffi_get_block_size(h, &mut samples), LHDC_V3_SUCCESS);
            assert_eq!(samples as usize, BLOCK_SIZE);

            let pcm = vec![0u8; BLOCK_SIZE * 2 * 2];
            let mut out = vec![0u8; 1024];
            let (mut bytes, mut frames) = (0u32, 0u32);
            assert_eq!(
                lhdcv3_enc_ffi_encode(
                    h,
                    pcm.as_ptr(),
                    pcm.len(),
                    out.as_mut_ptr(),
                    out.len(),
                    &mut bytes,
                    &mut frames,
                ),
                LHDC_V3_SUCCESS
            );
            assert_eq!(lhdcv3_enc_ffi_free_handle(h), LHDC_V3_SUCCESS);
        }
    }

    #[test]
    fn coding_before_init_is_refused() {
        let mut h: HANDLE_LHDC_V3 = std::ptr::null_mut();
        unsafe {
            lhdcv3_enc_ffi_get_handle(&mut h);
            let pcm = vec![0u8; BLOCK_SIZE * 4];
            let mut out = vec![0u8; 64];
            let (mut bytes, mut frames) = (0u32, 0u32);
            assert_eq!(
                lhdcv3_enc_ffi_encode(
                    h,
                    pcm.as_ptr(),
                    pcm.len(),
                    out.as_mut_ptr(),
                    out.len(),
                    &mut bytes,
                    &mut frames,
                ),
                LHDC_V3_ERROR_STATE
            );
            lhdcv3_enc_ffi_free_handle(h);
        }
    }

    #[test]
    fn a_bad_configuration_is_refused() {
        let mut h: HANDLE_LHDC_V3 = std::ptr::null_mut();
        unsafe {
            lhdcv3_enc_ffi_get_handle(&mut h);
            assert_eq!(
                lhdcv3_enc_ffi_init_encoder(h, 22050, 16, 7, 679, 20),
                LHDC_V3_ERROR_PARAM
            );
            assert_eq!(
                lhdcv3_enc_ffi_init_encoder(h, 48000, 32, 7, 679, 20),
                LHDC_V3_ERROR_PARAM
            );
            lhdcv3_enc_ffi_free_handle(h);
        }
    }
}
