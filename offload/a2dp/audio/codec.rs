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

use crate::ffi::{CCodecConfig, CCodecType};
use crate::streamer::FifoFrame;

#[cfg(feature = "sbc")]
use crate::sbc::{CSbcConfig, SbcEncoder};

pub(crate) use crate::streamer::AudioConfig;

use std::time::Instant;
use zerocopy::Ref;

#[derive(Clone, Copy)]
pub enum CodecConfig {
    #[cfg(feature = "sbc")]
    Sbc(CSbcConfig),
}

impl CodecConfig {
    /// SAFETY: The caller must ensure that the `config` union is initialized with the
    /// variant corresponding to `ty`.
    pub unsafe fn from(ty: &CCodecType, config: &CCodecConfig) -> Self {
        match ty {
            #[cfg(feature = "sbc")]
            // SAFETY: The caller guarantees that the config union is initialized with the variant corresponding to ty.
            CCodecType::Sbc => CodecConfig::Sbc(unsafe { config.sbc }),
            #[cfg(not(feature = "sbc"))]
            CCodecType::Sbc => panic!("SBC feature not enabled"),
        }
    }
}

pub fn validate_encoder(config: &AudioConfig, channels: usize) -> Result<(), String> {
    match config.codec {
        #[cfg(feature = "sbc")]
        CodecConfig::Sbc(..) => SbcEncoder::validate(config, channels),
    }
}

pub fn new_encoder(config: &AudioConfig, channels: usize, l2cap_mtu: u16) -> Box<dyn Encode> {
    match config.codec {
        #[cfg(feature = "sbc")]
        CodecConfig::Sbc(..) => Box::new(SbcEncoder::new(config, channels, l2cap_mtu)),
    }
}

pub trait Encode {
    fn encode(&mut self, pcm: &PcmFrame) -> Vec<u8>;
    fn get_pcm_frame_len(&mut self, timestamp: Instant, lost_packets_count: u32) -> usize;
}

pub struct PcmFrame<'a> {
    bitdepth: usize,
    data: &'a [u8],
}

impl<'a> PcmFrame<'a> {
    pub fn from_fifo(fifo: &'a FifoFrame<'a>) -> Self {
        Self { data: fifo.data(), bitdepth: fifo.bitdepth }
    }

    /// Returns the frame data as a slice of 16-bit signed integers.
    ///
    /// This function is only valid when the `bitdepth` is 16.
    /// This function will panic if the `bitdepth` is not 16, or if the
    /// underlying data is not aligned for an `i16` slice.
    pub fn as_i16_slice(&self) -> &'a [i16] {
        assert_eq!(self.bitdepth, 16, "as_i16_slice is only valid for 16-bit audio");

        // Use zerocopy to safely cast &[u8] to &[i16].
        let slice_ref = Ref::<&[u8], [i16]>::new_slice(self.data)
            .expect("PCM buffer is not aligned or has invalid length for i16");

        slice_ref.into_slice()
    }
}
