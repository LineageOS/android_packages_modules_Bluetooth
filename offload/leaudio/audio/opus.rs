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
use core::cmp::min;
use core::ffi::{c_int, c_void};

pub(crate) struct OpusEncoder {
    st: *mut c_void,
    channels: usize,
    frame_samples: usize,
    frame_bytes: usize,
}

impl OpusEncoder {
    pub fn validate(config: &AudioConfig, channels: usize) -> Result<(), String> {
        let opus_config: &OpusCConfig = config.into();
        match channels {
            1..=2 => Ok(()),
            n => Err(format!("Invalid number of channels: {n}")),
        }
        .and(match config.sample_rate {
            48_000 | 96_000 => Ok(()),
            n => Err(format!("Invalid sample rate: {n} Hz")),
        })
        .and(match config.frame_duration_us {
            20_000 => Ok(()),
            n => Err(format!("Invalid frame duration: {n} us")),
        })
        .and(match Self::bitrate(config) {
            64_000..=510_000 => Ok(()),
            n => Err(format!(
                "Out of range frame frame_bytes: {}, resulting to bitrate: {} bps",
                opus_config.frame_bytes, n
            )),
        })
        .and(match opus_config.complexity {
            0..=10 => Ok(()),
            n => Err(format!("Invalid complexity: {n}")),
        })
    }

    pub fn new(config: &AudioConfig, channels: usize, max_frame_bytes: usize) -> Self {
        assert!(Self::validate(config, channels).is_ok());
        let opus_config: &OpusCConfig = config.into();

        let mut error = 0;
        // SAFETY: The error indicator lives the time of the procedure call.
        //         Unless error indication, an encoder state is returned, that
        //         lives as long as `self`.
        let st = unsafe {
            opus_encoder_create(
                config.sample_rate as i32,
                channels as c_int,
                OPUS_APPLICATION_AUDIO,
                &mut error,
            )
        };
        assert_eq!(error, 0, "Opus encoder creation failed with error: {error}");

        // SAFETY: The encoder state `st` points to a valid instance allocated above
        //         by `opus_encoder_create()`.
        unsafe { Self::set_bitrate(st, Self::bitrate(config)) };

        // SAFETY: The encoder state `st` points to a valid instance allocated above
        //         by `opus_encoder_create()`.
        unsafe { Self::set_vbr(st, opus_config.vbr) };

        // SAFETY: The encoder state `st` points to a valid instance allocated above
        //         by `opus_encoder_create()`.
        unsafe { Self::set_complexity(st, opus_config.complexity) };

        // SAFETY: The encoder state `st` points to a valid instance allocated above
        //         by `opus_encoder_create()`.
        unsafe { Self::set_qext(st, config.sample_rate > 48000) };

        Self {
            st,
            channels,
            frame_samples: Self::frame_samples(config),
            frame_bytes: min(opus_config.frame_bytes as usize, max_frame_bytes),
        }
    }

    fn frame_samples(config: &AudioConfig) -> usize {
        ((config.frame_duration_us as u64 * config.sample_rate as u64) / 1_000_000) as usize
    }

    fn bitrate(config: &AudioConfig) -> usize {
        let opus_config: &OpusCConfig = config.into();
        (1_000_000 / config.frame_duration_us as usize) * opus_config.frame_bytes as usize * 8
    }

    unsafe fn set_bitrate(st: *mut c_void, value: usize) {
        const SET_BITRATE_REQUEST: c_int = 4002;
        // SAFETY: The encoder state `st` points to a valid instance, as required by this
        //         function; The first argument of the variable argument list is interpreted
        //         as int32_t, as defined by the `OPUS_SET_BITRATE()` macro.
        let result = unsafe { opus_encoder_ctl(st, SET_BITRATE_REQUEST, value as i32) };
        assert_eq!(result, 0, "Failed to set bitrate to {value}, with error: {result}");
    }

    unsafe fn set_vbr(st: *mut c_void, value: bool) {
        const SET_VBR_REQUEST: c_int = 4006;
        // SAFETY: The encoder state `st` points to a valid instance, as required by this
        //         function; The first argument of the variable argument list is interpreted
        //         as int32_t, as defined by the `OPUS_SET_VBR()` macro.
        let result = unsafe { opus_encoder_ctl(st, SET_VBR_REQUEST, value as i32) };
        assert_eq!(result, 0, "Failed to set VBR to {value}, with error: {result}");
    }

    unsafe fn set_complexity(st: *mut c_void, value: c_int) {
        const SET_COMPLEXITY_REQUEST: c_int = 4010;
        // SAFETY: The encoder state `st` points to a valid instance, as required by this
        //         function; The first argument of the variable argument list is interpreted
        //         as int32_t, as defined by the `OPUS_SET_COMPLEXITY()` macro.
        let result = unsafe { opus_encoder_ctl(st, SET_COMPLEXITY_REQUEST, value) };
        assert_eq!(result, 0, "Failed to set complexity to {value}, with error: {result}");
    }

    unsafe fn set_qext(st: *mut c_void, value: bool) {
        const SET_QEXT_REQUEST: c_int = 4056;
        // SAFETY: The encoder state `st` points to a valid instance, as required by this
        //         function; The first argument of the variable argument list is interpreted
        //         as int32_t, as defined by the `OPUS_SET_QEXT()` macro.
        let result = unsafe { opus_encoder_ctl(st, SET_QEXT_REQUEST, value as i32) };
        assert_eq!(result, 0, "Failed to set qext to {value}, with error: {result}");
    }
}

impl Encode for OpusEncoder {
    fn encode(&self, pcm: &PcmFrame) -> Vec<u8> {
        let pcm = pcm.to_vec_f32();
        assert!(pcm.len() == self.channels * self.frame_samples);

        let mut data = vec![0u8; self.frame_bytes];

        // SAFETY: The handle points to an encoder state, with the self-lifetime; The PCM input
        //         points to a frame of samples to encode. The output buffer is valid for
        //         the desired size of the encoded audio frame.
        let result = unsafe {
            opus_encode_float(
                self.st,
                pcm.as_ptr(),
                (pcm.len() / self.channels) as c_int,
                data.as_mut_ptr(),
                data.len() as c_int,
            )
        };
        assert!(result >= 0, "Opus encoding failed with error: {result}");

        data.truncate(result as usize);
        data
    }
}

impl Drop for OpusEncoder {
    fn drop(&mut self) {
        // SAFETY: The handle points to an encoder state, living as long as self.
        unsafe { opus_encoder_destroy(self.st) };
    }
}

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub(crate) struct OpusCConfig {
    frame_bytes: c_int,
    vbr: bool,
    complexity: c_int,
}

impl<'a> From<&'a AudioConfig> for &'a OpusCConfig {
    #[allow(unreachable_patterns)]
    fn from(audio: &'a AudioConfig) -> Self {
        match audio.codec {
            CodecConfig::Opus(ref v) => v,
            _ => panic!(),
        }
    }
}

const OPUS_APPLICATION_AUDIO: c_int = 2049;

#[rustfmt::skip]
extern "C" {
    fn opus_encoder_create(
        fs: i32,
        channels: c_int,
        application: c_int,
        error: *mut c_int,
    ) -> *mut c_void;

    fn opus_encoder_destroy(st: *mut c_void);

    fn opus_encoder_ctl(st: *mut c_void, request: c_int, ...) -> c_int;

    fn opus_encode_float(
        st: *mut c_void,
        pcm: *const f32,
        frame_size: c_int,
        compressed: *mut u8,
        max_compressed_bytes: c_int,
    ) -> c_int;
}
