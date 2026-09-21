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

//! LHDC V3.
//!
//! V3 shares a vendor id and a ten bit frame length field with V5 and nothing
//! else. V5 is a transform codec: it quantizes in the frequency domain and
//! codes with an arithmetic coder. V3 is built like a lossless coder, running
//! prediction over raw time domain PCM and coding the residual with
//! Golomb-Rice. The coding level is chosen against a noise allowance set by
//! the power of the block.
//!
//! A frame is built in memory and emitted with every four byte word reversed,
//! so the sync byte sits at memory offset 0 but at frame offset 3.

pub use lhdcv3_format as format;

pub mod encoder;
pub mod ffi;
pub mod layout;
pub mod lpc;
pub mod lpf;
pub mod session;
pub mod tables;
