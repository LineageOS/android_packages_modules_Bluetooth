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

use crate::ffi::{CCodecConfig, CCodecType};
use crate::streamer::FifoFrame;

#[cfg(feature = "lc3")]
use crate::lc3::{Lc3CConfig, Lc3Encoder};

#[cfg(feature = "opus")]
use crate::opus::{OpusCConfig, OpusEncoder};

pub(crate) use crate::streamer::AudioConfig;

#[derive(Clone, Copy)]
pub enum CodecConfig {
    #[cfg(feature = "lc3")]
    Lc3(Lc3CConfig),
    #[cfg(feature = "opus")]
    Opus(OpusCConfig),
}

impl CodecConfig {
    pub fn from(ty: &CCodecType, config: &CCodecConfig) -> Self {
        match ty {
            #[cfg(feature = "lc3")]
            CCodecType::Lc3 => {
                // SAFETY: The C Code gives an LC3 configuration type according
                //         to the selected LC3 codec type.
                unsafe { CodecConfig::Lc3(config.lc3) }
            }
            #[cfg(feature = "opus")]
            CCodecType::Opus => {
                // SAFETY: The C Code gives an Opus configuration type according
                //         to the selected Opus codec type.
                unsafe { CodecConfig::Opus(config.opus) }
            }
            #[cfg(not(feature = "lc3"))]
            CCodecType::Lc3 => panic!("LC3 feature not enabled"),
            #[cfg(not(feature = "opus"))]
            CCodecType::Opus => panic!("Opus feature not enabled"),
        }
    }
}

pub fn validate_encoder(config: &AudioConfig, channels: usize) -> Result<(), String> {
    match config.codec {
        #[cfg(feature = "lc3")]
        CodecConfig::Lc3(..) => Lc3Encoder::validate(config, channels),
        #[cfg(feature = "opus")]
        CodecConfig::Opus(..) => OpusEncoder::validate(config, channels),
    }
}

pub fn new_encoder(config: &AudioConfig, channels: usize, max_enc_bytes: usize) -> Box<dyn Encode> {
    match config.codec {
        #[cfg(feature = "lc3")]
        CodecConfig::Lc3(..) => Box::new(Lc3Encoder::new(config, channels, max_enc_bytes)),
        #[cfg(feature = "opus")]
        CodecConfig::Opus(..) => Box::new(OpusEncoder::new(config, channels, max_enc_bytes)),
    }
}

pub trait Encode {
    fn encode(&self, pcm: &PcmFrame) -> Vec<u8>;
}

pub struct PcmFrame<'a> {
    channels: usize,
    bitdepth: usize,
    stride: usize,
    data: &'a [u8],
}

impl<'a> PcmFrame<'a> {
    pub fn from_fifo(fifo: &'a FifoFrame<'a>) -> Self {
        Self { data: fifo.data(), bitdepth: fifo.bitdepth, channels: fifo.channels, stride: 1 }
    }

    pub fn channel(self, idx: usize) -> Self {
        assert!(idx < self.channels);
        let offset = idx * (self.bitdepth / 8);
        Self { data: &self.data[offset..], channels: 1, stride: self.channels, ..self }
    }

    pub fn to_vec_f32(&self) -> Vec<f32> {
        match self.bitdepth {
            16 => Self::to_vec_f32_impl::<16usize>(self.data, self.stride),
            24 => Self::to_vec_f32_impl::<24usize>(self.data, self.stride),
            32 => Self::to_vec_f32_impl::<32usize>(self.data, self.stride),
            _ => unimplemented!(),
        }
    }

    fn to_vec_f32_impl<const BITDEPTH: usize>(data: &[u8], stride: usize) -> Vec<f32> {
        data.chunks(BITDEPTH / 8)
            .step_by(stride)
            .map(|bytes| {
                let it = bytes.iter().enumerate();
                let v = it.fold(0i32, |v, (i, b)| v | ((*b as i32) << (i * 8 + (32 - BITDEPTH))));
                v as f32 * (-31f32).exp2()
            })
            .collect()
    }
}
