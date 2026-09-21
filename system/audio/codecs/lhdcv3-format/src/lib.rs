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


//! The LHDC V3 bitstream: what a frame carries and how to read one back.
//!
//! A block of [`BLOCK_SIZE`] samples becomes one frame. Two of the
//! three signals derived from the stereo pair are coded, which two being the
//! frame's channel field. Each coded signal is predicted, the residual mapped
//! to unsigned by zigzag and written as a variable length code, and the frame
//! emitted with every four byte word reversed.

pub mod bitpack;
pub mod codec_info;
pub mod decoder;
pub mod frame;
pub mod lz;
pub mod predictor;
pub mod value;

/// Samples in one block, at every sample rate and depth.
pub const BLOCK_SIZE: usize = 256;
