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

use crate::codec::{AudioConfig, CodecConfig, Encode, PcmFrame};
use std::alloc::{alloc, dealloc, Layout};
use std::cmp::min;
use std::ffi::{c_int, c_longlong, c_uint, c_void};
use std::ptr;

pub(crate) struct Lc3Encoder {
    instances: EncoderInstances,
    frame_samples: usize,
    block_bytes: usize,
}

impl Lc3Encoder {
    pub fn validate(config: &AudioConfig, channels: usize) -> Result<(), String> {
        let lc3_config: &Lc3CConfig = config.into();
        let block_bytes = lc3_config.block_bytes as usize;
        let frame_bytes = block_bytes / channels;
        let frame_bytes_ceiled = block_bytes.div_ceil(channels);

        match (lc3_config.hr_mode, config.frame_duration_us) {
            (false, 2500 | 5000 | 7500 | 10000) => Ok(()),
            (true, 2500 | 5000 | 10000) => Ok(()),
            (_, v) => Err(format!("Invalid frame duration: {v} us")),
        }
        .and(match (lc3_config.hr_mode, config.sample_rate) {
            (false, 8000 | 16000 | 24000 | 32000 | 48000) => Ok(()),
            (true, 48000 | 96000) => Ok(()),
            (_, v) => Err(format!("Invalid sample rate: {v} Hz")),
        })
        .and(match (lc3_config.hr_mode, frame_bytes, frame_bytes_ceiled) {
            (false, 20.., ..=400) => Ok(()),
            (true, 20.., ..=625) => Ok(()),
            (..) => Err(format!("Invalid block size: {block_bytes}")),
        })
    }

    pub fn new(config: &AudioConfig, channels: usize, max_block_bytes: usize) -> Self {
        assert!(Self::validate(config, channels).is_ok());

        let lc3_config: &Lc3CConfig = config.into();
        let block_bytes = lc3_config.block_bytes as usize;
        let hr_mode = lc3_config.hr_mode;
        let dt_us = config.frame_duration_us as c_int;
        let sr_hz = config.sample_rate as c_int;

        Self {
            instances: EncoderInstances::new(channels, hr_mode, dt_us, sr_hz),
            // SAFETY: All the parameters are memory-safe; the number of samples by
            //         frame is stored to validate the length of PCM stream.
            frame_samples: unsafe { lc3_hr_frame_samples(hr_mode, dt_us, sr_hz) } as usize,
            block_bytes: min(block_bytes, max_block_bytes),
        }
    }
}

impl Encode for Lc3Encoder {
    fn encode(&self, pcm: &PcmFrame) -> Vec<u8> {
        let handles = &self.instances.handles;
        let channels = handles.len();

        let pcm = pcm.to_vec_f32();
        assert!(pcm.len() == channels * self.frame_samples);

        let mut data = vec![0u8; self.block_bytes];
        let mut offset = 0;

        for ch in 0..channels {
            let frame_size = data.len() / channels + (ch < data.len() % channels) as usize;
            // SAFETY: The handle points to a memory area valid for `lc3_hr_encoder_size()`
            //         bytes, and set up by `lc3_hr_setup_encoder()`.
            //         The PCM input is valid for the number of samples by frame for
            //         as many frames as the number of channels. The output buffer is
            //         valid from `offet` to `frame_size`, and is initialized to 0.
            unsafe {
                lc3_encode(
                    handles[ch],
                    PcmFormat::Float,
                    pcm[ch..].as_ptr().cast(),
                    channels as c_int,
                    frame_size as c_int,
                    data[offset..].as_mut_ptr().cast(),
                );
            }
            offset += frame_size;
        }

        data
    }
}

struct EncoderInstances {
    layout: Layout,
    handles: Vec<*mut c_void>,
}

impl EncoderInstances {
    fn new(channels: usize, hr_mode: bool, dt_us: c_int, sr_hz: c_int) -> Self {
        let (size, align) = (
            // SAFETY: All the parameters are memory-safe; the size of the encoder
            //         returned is guaranteed to be less than `usize`; the zero value,
            //         in case of error, value is safetely proceeded.
            unsafe { lc3_hr_encoder_size(hr_mode, dt_us, sr_hz) } as usize,
            (c_longlong::BITS / 8) as usize,
        );
        assert_ne!(size, 0);

        let layout = Layout::from_size_align(size, align).unwrap();
        let mut handles = Vec::with_capacity(channels);
        for _ in 0..channels {
            // SAFETY: The C code returned valid allocable memory size;
            //         the alignment is suitable for any C standard types.
            let mem: *mut c_void = unsafe { alloc(layout).cast() };

            // SAFETY: The allocated memory area is valid for the size indicated
            //         for the same encoding parameters.
            let instance = unsafe { lc3_hr_setup_encoder(hr_mode, dt_us, sr_hz, 0, mem) };
            assert_ne!(instance, ptr::null_mut());

            handles.push(instance);
        }

        Self { layout, handles }
    }
}

impl Drop for EncoderInstances {
    fn drop(&mut self) {
        for h in &self.handles {
            // SAFETY: The handles points to memory allocated by `alloc()`
            //         using the same layout.
            unsafe {
                dealloc(h.cast(), self.layout);
            }
        }
    }
}

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct Lc3CConfig {
    hr_mode: bool,
    block_bytes: c_int,
}

impl<'a> From<&'a AudioConfig> for &'a Lc3CConfig {
    #[allow(unreachable_patterns)]
    fn from(audio: &'a AudioConfig) -> Self {
        match audio.codec {
            CodecConfig::Lc3(ref v) => v,
            _ => panic!(),
        }
    }
}

#[repr(C)]
#[allow(dead_code)]
enum PcmFormat {
    S16,
    S24,
    S24_3Le,
    Float,
}

#[rustfmt::skip]
extern "C" {
    fn lc3_hr_frame_samples(
        hrmode: bool, dt_us: c_int, sr_hz: c_int
    ) -> c_int;

    fn lc3_hr_encoder_size(
        hrmode: bool, dt_us: c_int, sr_hz: c_int
    ) -> c_uint;

    fn lc3_hr_setup_encoder(
        hrmode: bool, dt_us: c_int, sr_hz: c_int, sr_pcm_hz: c_int,
        mem: *mut c_void
    ) -> *mut c_void;

    fn lc3_encode(enc: *mut c_void,
        fmt: PcmFormat, pcm: *const c_void, stride: c_int,
        nbytes: c_int, data: *mut c_void,
    ) -> c_int;
}
