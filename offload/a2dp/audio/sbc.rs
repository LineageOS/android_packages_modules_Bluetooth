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

use crate::codec::{AudioConfig, CodecConfig, Encode, PcmFrame};
use std::ffi::{c_int, c_short};
use std::fmt;
use std::time::Instant;
use zerocopy::FromZeros;

/// Sampling frequency in Hz.
const SBC_FREQ_16000: i16 = 0;
const SBC_FREQ_32000: i16 = 1;
const SBC_FREQ_44100: i16 = 2;
const SBC_FREQ_48000: i16 = 3;

/// Channel mode: Mono. A single audio channel.
const SBC_MONO: i16 = 0;
/// Channel mode: Dual Channel. Two independent audio streams are encoded.
const SBC_DUAL: i16 = 1;
/// Channel mode: Stereo. Left and right channels are encoded independently.
const SBC_STEREO: i16 = 2;
/// Channel mode: Joint Stereo. An encoding optimization for stereo audio where some information
/// between the left and right channels is shared to improve compression.
const SBC_JOINT_STEREO: i16 = 3;

/// The maximum number of frequency sub-bands the SBC codec can use (for array sizing).
/// The actual number used during encoding is set in `SbcEncParams`.
const SBC_MAX_NUM_OF_SUBBANDS: usize = 8;
/// The maximum number of audio channels the SBC codec can handle (for array sizing).
const SBC_MAX_NUM_OF_CHANNELS: usize = 2;
/// The maximum number of blocks the SBC encoder can process in a frame (for array sizing).
const SBC_MAX_NUM_OF_BLOCKS: usize = 16;

// --- Bluetooth A2DP/RTP Constants ---
/// Size of the A2DP media payload header.
const A2DP_MEDIA_PAYLOAD_HEADER_SIZE: usize = 1;
/// Size of the RTP header.
const RTP_HEADER_SIZE: usize = 12;
/// SBC payload type ID.
const RTP_PAYLOAD_TYPE_SBC: u8 = 96;

// --- SBC Frame & Bitrate Calculation Constants ---
/// Size of the SBC header in bytes.
const SBC_HEADER_SIZE_BYTES: usize = 4;
/// Number of bits used to encode each scale factor.
const BITS_PER_SCALE_FACTOR: usize = 4;
/// Number of bits in a byte.
const BITS_PER_BYTE: usize = 8;
/// Factor to convert bitrate to kilobits per second.
const KBPS_CONVERSION_FACTOR: u32 = 1000;
/// A2DP SBC max bitpool
const A2DP_SBC_MAX_BITPOOL: i32 = 53;

// --- PCM Audio format Constants ---
/// Number of bytes per sample for 16-bit PCM audio.
const BYTES_PER_PCM_SAMPLE: usize = 2;
/// Max number of PCM frames per tick we can hold.
const MAX_PCM_FRAME_NUM_PER_TICK: u64 = 14;

// --- Default Encoder Configuration & Limits ---
/// Initial target bitrate in kbps for the encoder.
const INITIAL_TARGET_BITRATE_KBPS: u16 = 328;

/// This struct mirrors `SBC_ENC_PARAMS` from the C SBC encoder implementation.
/// The original definition can be found in:
/// `packages/modules/Bluetooth/system/embdrv/sbc/encoder/include/sbc_encoder.h`
#[derive(Clone, Copy, FromZeros)]
#[repr(C)]
struct SbcEncParams {
    // --- User-Configurable Parameters ---
    // These fields are set by the user before calling SBC_Encoder_Init.
    /// Sampling frequency of the input audio. Must be one of the `SBC_FREQ_*` constants.
    s16_sampling_freq: i16,
    /// Channel mode (Mono, Stereo, etc.). Must be one of the `SBC_*` channel mode constants.
    s16_channel_mode: i16,
    /// Number of frequency sub-bands to use (4 or 8).
    s16_num_of_sub_bands: i16,
    /// Number of channels in the input audio (1 for mono, 2 for stereo).
    s16_num_of_channels: i16,
    /// Number of blocks to process in each frame (4, 8, 12, or 16).
    s16_num_of_blocks: i16,
    /// Bit allocation method: loudness or snr`.
    s16_allocation_method: i16,
    /// The "bitpool" value. This is a key parameter that controls the bitrate and quality.
    /// A higher bitpool means a higher bitrate and better quality. This value is calculated
    /// and set by `SBC_Encoder_Init` based on the `u16_bit_rate`.
    s16_bit_pool: i16,
    /// Target bitrate in kbps (e.g., 328). The encoder will calculate the best `s16_bit_pool`
    /// to achieve this bitrate, but it may be adjusted to fit MTU constraints.
    u16_bit_rate: u16,

    // --- Internal State Buffers ---
    // These fields are used internally by the encoder during the encoding process.
    // They are initialized by `SBC_Encoder_Init` and modified during `SBC_Encode`.
    /// Internal state for joint stereo encoding. Stores which sub-bands are using joint coding.
    /// This array is present because `SBC_JOINT_STE_INCLUDED` is TRUE by default in the C header.
    as16_join: [i16; SBC_MAX_NUM_OF_SUBBANDS],

    /// Maximum number of bits required for a sub-band in the current frame. Used in bit allocation.
    s16_max_bit_need: i16,

    /// Scale factors are calculated for each sub-band to normalize the audio data before quantization.
    /// This helps to efficiently use the available bits.
    as16_scale_factor: [i16; SBC_MAX_NUM_OF_CHANNELS * SBC_MAX_NUM_OF_SUBBANDS],

    /// A temporary buffer used during the bit allocation process.
    s16_scratch_mem_for_bit_alloc: [i16; 16],

    /// This is the main buffer for holding the sub-band samples after the analysis filterbank
    /// has split the PCM signal into different frequency bands.
    s32_sb_buffer: [i32; SBC_MAX_NUM_OF_CHANNELS * SBC_MAX_NUM_OF_SUBBANDS * SBC_MAX_NUM_OF_BLOCKS],

    /// Stores the number of bits allocated to each sub-band for quantization in the current frame.
    as16_bits: [i16; SBC_MAX_NUM_OF_CHANNELS * SBC_MAX_NUM_OF_SUBBANDS],

    /// Stores the SBC frame header bits before they are packed into the final byte stream.
    frame_header: u16,

    /// A byte containing format-specific fields like syncword, etc.
    format: u8,
    // Other private fields might follow, but are not needed for initialization.
    // We rely on SBC_Encoder_Init to fill them correctly.
}

#[rustfmt::skip]
extern "C" {
    fn SBC_Encoder_Init(params: *mut SbcEncParams);
    fn SBC_Encode(params: *mut SbcEncParams, input: *const i16, output: *mut u8) -> u32;
}

impl SbcEncParams {
    fn freq_to_str(&self) -> &'static str {
        match self.s16_sampling_freq {
            SBC_FREQ_16000 => "16kHz",
            SBC_FREQ_32000 => "32kHz",
            SBC_FREQ_44100 => "44.1kHz",
            SBC_FREQ_48000 => "48kHz",
            _ => "Unknown",
        }
    }

    fn mode_to_str(&self) -> &'static str {
        match self.s16_channel_mode {
            SBC_MONO => "Mono",
            SBC_DUAL => "Dual Channel",
            SBC_STEREO => "Stereo",
            SBC_JOINT_STEREO => "Joint Stereo",
            _ => "Unknown",
        }
    }

    fn alloc_to_str(&self) -> &'static str {
        match self.s16_allocation_method {
            0 => "Loudness",
            1 => "SNR",
            _ => "Unknown",
        }
    }

    fn format_to_str(&self) -> &'static str {
        match self.format {
            0 => "General SBC",
            _ => "Other",
        }
    }
}

// Implementing Display handles the "Pretty Printing" logic
impl fmt::Display for SbcEncParams {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        writeln!(f, "SBC_ENC_PARAMS Configuration:")?;
        writeln!(f, "---------------------------")?;

        // 1. Basic Configuration
        writeln!(f, "  Format:            {} (ID: {})", self.format_to_str(), self.format)?;
        writeln!(f, "  Sampling Freq:     {}", self.freq_to_str())?;
        writeln!(f, "  Channel Mode:      {}", self.mode_to_str())?;
        writeln!(f, "  Num Channels:      {}", self.s16_num_of_channels)?;
        writeln!(f, "  Subbands:          {}", self.s16_num_of_sub_bands)?;
        writeln!(f, "  Blocks:            {}", self.s16_num_of_blocks)?;
        writeln!(f, "  Alloc Method:      {}", self.alloc_to_str())?;

        // 2. Bitrate and Bitpool
        writeln!(f, "  Bitpool:           {}", self.s16_bit_pool)?;
        writeln!(f, "  Bitrate:           {} bps", self.u16_bit_rate)?;
        writeln!(f, "  Max Bit Need:      {}", self.s16_max_bit_need)?;

        // 3. Technical Header (Hex)
        writeln!(f, "  Frame Header:      0x{:04X}", self.frame_header)?;

        // 4. Joint Stereo Data (Only show relevant subbands)
        if self.s16_channel_mode == 3 {
            let active_subbands = self.s16_num_of_sub_bands as usize;
            // Safely slice the array to only show active subbands
            let slice = if active_subbands <= SBC_MAX_NUM_OF_SUBBANDS {
                &self.as16_join[0..active_subbands]
            } else {
                &self.as16_join[..]
            };
            writeln!(f, "  Joint Stereo Join: {:?}", slice)?;
        }

        writeln!(f, "---------------------------")
    }
}

pub(crate) struct SbcEncoder {
    audio_config: AudioConfig,
    params: SbcEncParams,
    current_sbc_frames_per_packet: usize,
    max_sbc_frames_per_packet: usize,
    pcm_int16_per_packet: usize,
    bytes_per_single_sbc_frame: usize,
    pcm_int16_per_sbc_frame: usize,
    rtp_session: RtpSbcSession,
    bytes_per_second: u32,
    last_timestamp: Option<Instant>,
    pending_bytes: u64,
}

impl SbcEncoder {
    /// Calculates the expected SBC frame length in bytes.
    /// This function's logic is based on the calculation found in the AOSP SBC encoder C code.
    fn calculate_sbc_frame_size(params: &SbcEncParams, bitpool: i16) -> usize {
        let num_subbands = params.s16_num_of_sub_bands as usize;
        let num_channels = params.s16_num_of_channels as usize;
        let num_blocks = params.s16_num_of_blocks as usize;

        // Calculate the size of a single SBC frame in bytes.
        let mut bytes_per_single_sbc_frame = SBC_HEADER_SIZE_BYTES
            + (BITS_PER_SCALE_FACTOR * num_subbands * num_channels) / BITS_PER_BYTE;

        // Use integer division (floor) to be consistent with the C code's
        // own bitrate-to-bitpool calculation.
        match params.s16_channel_mode {
            SBC_MONO | SBC_DUAL => {
                bytes_per_single_sbc_frame +=
                    (num_blocks * num_channels * bitpool as usize) / BITS_PER_BYTE;
            }
            SBC_STEREO => {
                bytes_per_single_sbc_frame += (num_blocks * bitpool as usize) / BITS_PER_BYTE;
            }
            SBC_JOINT_STEREO => {
                bytes_per_single_sbc_frame +=
                    (num_subbands + num_blocks * bitpool as usize) / BITS_PER_BYTE;
            }
            _ => {
                // Should not happen with valid params
                panic!("Unsupported channel mode");
            }
        }
        bytes_per_single_sbc_frame
    }

    /// Validates that the audio configuration is supported for SBC encoding.
    pub fn validate(config: &AudioConfig, channels: usize) -> Result<(), String> {
        let sbc_config: &CSbcConfig = config.into();

        if ![1, 2].contains(&channels) {
            return Err(format!("Unsupported number of channels for SBC: {}", channels));
        }
        if config.bitdepth != 16 {
            return Err("SBC encoder requires 16-bit PCM samples".to_string());
        }
        if ![1, 2].contains(&config.channel_mode) {
            return Err(format!("Unsupported channel mode for SBC: {}", config.channel_mode));
        }
        if ![16000, 32000, 44100, 48000].contains(&config.sample_rate) {
            return Err(format!("Unsupported sample rate for SBC: {}", config.sample_rate));
        }
        if ![4, 8, 12, 16].contains(&sbc_config.block_length) {
            return Err(format!("Invalid SBC block length: {}", sbc_config.block_length));
        }
        if ![4, 8].contains(&sbc_config.subbands) {
            return Err(format!("Invalid SBC subbands: {}", sbc_config.subbands));
        }
        if sbc_config.min_bitpool < 2 || sbc_config.max_bitpool > 250 {
            return Err("SBC bitpool values are out of range [2, 250]".to_string());
        }

        Ok(())
    }

    /// Creates a new SBC encoder instance.
    pub fn new(config: &AudioConfig, channels: usize, l2cap_mtu: u16) -> Self {
        let sbc_config: &CSbcConfig = config.into();
        let mut max_sbc_frames_per_packet: usize = 0;
        #[allow(unused_assignments)]
        let mut bytes_per_single_sbc_frame: usize = 0;
        let max_encoded_bytes_per_packet: usize =
            l2cap_mtu as usize - RTP_HEADER_SIZE - A2DP_MEDIA_PAYLOAD_HEADER_SIZE;

        log::debug!("SbcEncoder::new: Initializing SBC Encoder");

        let mut params = SbcEncParams::new_zeroed();
        params.s16_sampling_freq = match config.sample_rate {
            16000 => SBC_FREQ_16000,
            32000 => SBC_FREQ_32000,
            44100 => SBC_FREQ_44100,
            48000 => SBC_FREQ_48000,
            _ => panic!("Unsupported sample rate"),
        };
        params.s16_channel_mode = match config.channel_mode {
            1 => SBC_MONO,
            2 => SBC_JOINT_STEREO,
            _ => panic!("Unsupported channel mode"),
        };
        params.s16_num_of_sub_bands = sbc_config.subbands as i16;
        params.s16_num_of_channels = channels as i16;
        params.s16_num_of_blocks = sbc_config.block_length as i16;
        params.s16_allocation_method = sbc_config.allocation_method as i16;
        params.u16_bit_rate = INITIAL_TARGET_BITRATE_KBPS;

        let min_bitpool = sbc_config.min_bitpool;
        let mut max_bitpool = sbc_config.max_bitpool;
        let mut best_bitpool = 0;

        log::debug!(
            "SbcEncoder::new: target MTU: {}, max encoded bytes per packet: {}",
            l2cap_mtu,
            max_encoded_bytes_per_packet
        );

        // The Android stack clamps SBC max bitpool to 53, as it is a recommended value in the
        // specification for Joint Sterero with sampling frequency 44.1 kHz.
        // (A2DP Specification v1.4, Section 4.3.2.6 Minimum / Maximum Bitpool Value, Table 4.7).
        if max_bitpool > A2DP_SBC_MAX_BITPOOL {
            log::debug!(
                "SbcEncoder::new: Adjust max bitpool from: {} to: {}",
                max_bitpool,
                A2DP_SBC_MAX_BITPOOL
            );
            max_bitpool = A2DP_SBC_MAX_BITPOOL;
        }

        // To ensure the C encoder derives the exact bitpool we want, we can't just
        // reverse-calculate the bitrate. The C encoder has an adjustment step: it
        // calculates a bitpool, then calculates the frame size and effective bitrate,
        // and if the effective bitrate is higher than the target, it decrements the bitpool.
        //
        // To avoid this, we calculate the exact frame length for our desired `best_bitpool`
        // and then calculate the *effective bitrate* for that frame length. By providing
        // this effective bitrate as the target, we ensure the C encoder's check
        // `effective_bitrate > target_bitrate` will be false, preventing the decrement.
        for bitpool in (min_bitpool..=max_bitpool).rev() {
            bytes_per_single_sbc_frame =
                Self::calculate_sbc_frame_size(&params, bitpool.try_into().unwrap());
            if bytes_per_single_sbc_frame <= max_encoded_bytes_per_packet {
                best_bitpool = bitpool;
                log::debug!(
                    "SbcEncoder::new: found best fit! bitpool: {}, SBC frame size: {}",
                    best_bitpool,
                    bytes_per_single_sbc_frame
                );
                max_sbc_frames_per_packet =
                    max_encoded_bytes_per_packet / bytes_per_single_sbc_frame;
                log::debug!(
                    "SbcEncoder::new: max SBC frames per packet: {}, total SBC frames size: {}",
                    max_sbc_frames_per_packet,
                    max_sbc_frames_per_packet * bytes_per_single_sbc_frame
                );
                break;
            }
        }

        if best_bitpool == 0 {
            panic!("Could not find a bitpool value that fits the MTU {} size.", l2cap_mtu);
        }

        bytes_per_single_sbc_frame =
            Self::calculate_sbc_frame_size(&params, best_bitpool.try_into().unwrap());
        let num_subbands = params.s16_num_of_sub_bands as u32;
        let num_blocks = params.s16_num_of_blocks as u32;

        // This formula mirrors the C code's recalculation of bitrate from frame length.
        // bitrate (kbps) = (8 * frame_size * sample_rate) / (n_subbands * n_blocks * 1000)
        let bitrate_num =
            BITS_PER_BYTE as u32 * bytes_per_single_sbc_frame as u32 * config.sample_rate;
        let bitrate_den = num_subbands * num_blocks * KBPS_CONVERSION_FACTOR;
        let target_bitrate = bitrate_num / bitrate_den;
        params.u16_bit_rate = target_bitrate as u16;

        if max_encoded_bytes_per_packet < max_sbc_frames_per_packet * bytes_per_single_sbc_frame {
            panic!(
                "Max encoded bytes per packet ({}) is smaller than the expected frame size ({})",
                max_encoded_bytes_per_packet,
                max_sbc_frames_per_packet * bytes_per_single_sbc_frame
            );
        }

        log::debug!(
            "SbcEncoder::new: adjusted target bitrate to {} kbps to achieve bitpool of {}",
            target_bitrate,
            best_bitpool
        );

        // SAFETY: `params` is a valid, mutable reference to the encoder parameters struct.
        // The struct has been initialized with the necessary configuration before calling init.
        unsafe { SBC_Encoder_Init(&mut params) };

        // Calculate the number of samples required for a single SBC frame.
        let pcm_int16_per_sbc_frame = (params.s16_num_of_blocks
            * params.s16_num_of_sub_bands
            * params.s16_num_of_channels) as usize;
        // Calculate the number of samples required for a single A2DP packet.
        // This is the chunk size we will use to feed the encoder.
        let pcm_int16_per_packet = max_sbc_frames_per_packet * pcm_int16_per_sbc_frame;
        let bytes_per_second =
            config.sample_rate * params.s16_num_of_channels as u32 * (config.bitdepth as u32 / 8);

        log::info!("SbcEncoder::new: {}", params);
        log::info!("SbcEncoder::new: max SBC frames per packet: {}", max_sbc_frames_per_packet);
        log::info!("SbcEncoder::new: PCM samples per packet: {}", pcm_int16_per_packet);
        log::info!(
            "SbcEncoder::new: max encoded bytes per packet: {}",
            max_encoded_bytes_per_packet
        );
        log::info!("SbcEncoder::new: bytes per second: {}", bytes_per_second);
        log::info!("---------------------------------");

        let rtp_session = RtpSbcSession::new(
            0,
            RTP_PAYLOAD_TYPE_SBC,
            pcm_int16_per_sbc_frame.try_into().unwrap(),
        );

        Self {
            audio_config: *config,
            params,
            current_sbc_frames_per_packet: max_sbc_frames_per_packet,
            max_sbc_frames_per_packet,
            pcm_int16_per_packet,
            bytes_per_single_sbc_frame,
            pcm_int16_per_sbc_frame,
            rtp_session,
            bytes_per_second,
            last_timestamp: None,
            pending_bytes: 0,
        }
    }
}

impl Encode for SbcEncoder {
    /// Encodes a frame of PCM data into an SBC-encoded frame.
    fn encode(&mut self, pcm_frame: &PcmFrame) -> Vec<u8> {
        let pcm_data = pcm_frame.as_i16_slice();

        if pcm_data.len() != self.pcm_int16_per_packet {
            log::error!(
                "SbcEncoder::encode: PCM data size mismatch: got {} samples, expected {}",
                pcm_data.len(),
                self.pcm_int16_per_packet
            );
            return Vec::new();
        }

        let mut sbc_buffer = vec![
            0u8;
            A2DP_MEDIA_PAYLOAD_HEADER_SIZE
                + self.current_sbc_frames_per_packet
                    * self.bytes_per_single_sbc_frame
        ];
        sbc_buffer[0] = self.current_sbc_frames_per_packet as u8;

        let pcm_chunks = pcm_data.chunks_exact(self.pcm_int16_per_sbc_frame);
        let sbc_chunks = sbc_buffer[1..].chunks_exact_mut(self.bytes_per_single_sbc_frame);

        log::debug!(
            "SbcEncoder::encode: pcm_chunks len: {:?}, sbc_chunks len: {:?}",
            pcm_chunks.len(),
            sbc_chunks.len()
        );
        let mut counter = 1;
        // The zip ensures we don't process more chunks than available in either buffer.
        for (pcm_chunk, sbc_chunk) in pcm_chunks.zip(sbc_chunks) {
            // SAFETY:
            // - `self.params` is guaranteed to be initialized by `new()`.
            // - `pcm_chunk.as_ptr()` points to a valid slice of the correct size.
            // - `sbc_chunk.as_mut_ptr()` points to a valid, mutable slice of sufficient size.
            // - The loop structure ensures we do not go out of bounds.
            let encoded_len = unsafe {
                SBC_Encode(
                    &mut self.params,
                    pcm_chunk.as_ptr() as *const c_short,
                    sbc_chunk.as_mut_ptr(),
                )
            } as usize;

            if encoded_len != self.bytes_per_single_sbc_frame {
                log::error!(
                    "SbcEncoder::encode: SBC_Encode returned unexpected length: {}, expected {}",
                    encoded_len,
                    self.bytes_per_single_sbc_frame
                );
                return Vec::new();
            }
            counter += 1;
        }
        log::debug!("SbcEncoder::encode: Encoded {:?} frame/s", counter);

        self.rtp_session.wrap_sbc_packet(sbc_buffer.to_vec())
    }

    /// Returns the number of samples required for a single SBC frame.
    /// Should be called only after the encoder is initialized.
    fn get_pcm_frame_len(&mut self, timestamp: Instant, lost_packets_count: u32) -> usize {
        let mut this_tick_duration_us = match self.last_timestamp {
            None => self.audio_config.frame_duration_us as u64,
            Some(prev_timestamp) => timestamp.duration_since(prev_timestamp).as_micros() as u64,
        };
        self.last_timestamp = Some(timestamp);

        // Check for packet loss/delays. Equivalent to: if duration > frame_duration * 1.6
        //let threshold = (self.audio_config.frame_duration_us as u64 * 8) / 5;

        if lost_packets_count != 0 {
            //this_tick_duration_us > threshold {
            log::warn!(
                "SbcEncoder::get_pcm_frame_len: Clamping this_tick_duration from {} us to {} us as {} packets were lost",
                this_tick_duration_us,
                self.audio_config.frame_duration_us,
                lost_packets_count
            );
            this_tick_duration_us = self.audio_config.frame_duration_us as u64;
            self.pending_bytes = 0;
        }

        // Calculate pending bytes based on duration and bytes per second.
        // Formula: (BytesPerSec * DurationUs) / 1,000,000
        // We use u64 for the multiplication to avoid overflow (BytesPerSec ~384k * 20k us ~ 7.6B > u32::MAX).
        self.pending_bytes += (self.bytes_per_second as u64 * this_tick_duration_us) / 1_000_000;

        // Calculate bytes per single SBC frame (PCM side).
        let bytes_per_sbc_frame_pcm = (self.pcm_int16_per_sbc_frame * BYTES_PER_PCM_SAMPLE) as u64;

        // Calculate number of frames that fit into the pending bytes.
        let mut nof = self.pending_bytes / bytes_per_sbc_frame_pcm;

        log::debug!(
            "SbcEncoder::get_pcm_frame_len: Calculated nof: {}, pending_bytes: {}",
            nof,
            self.pending_bytes
        );

        if nof > MAX_PCM_FRAME_NUM_PER_TICK {
            log::warn!(
                "SbcEncoder::get_pcm_frame_len: Limiting frames to be sent from {} to {}",
                nof,
                self.max_sbc_frames_per_packet
            );
            nof = MAX_PCM_FRAME_NUM_PER_TICK;
            self.pending_bytes = nof * bytes_per_sbc_frame_pcm;
        }

        // Clamp to max frames per packet.
        self.current_sbc_frames_per_packet =
            std::cmp::min(nof as usize, self.max_sbc_frames_per_packet);

        self.pcm_int16_per_packet =
            self.pcm_int16_per_sbc_frame * self.current_sbc_frames_per_packet;
        let bytes_per_pcm_frame = self.pcm_int16_per_packet * BYTES_PER_PCM_SAMPLE;
        self.pending_bytes -= bytes_per_pcm_frame as u64;
        log::debug!("SbcEncoder::get_pcm_frame_len: this_tick_duration_us: {}, pending_bytes: {}, nof: {}, current_sbc_frames_per_packet: {}, bytes_per_pcm_frame: {}",
            this_tick_duration_us, self.pending_bytes, nof, self.current_sbc_frames_per_packet, bytes_per_pcm_frame);
        bytes_per_pcm_frame
    }
}

struct RtpSbcSession {
    sequence_number: u16,
    timestamp: u32,
    ssrc: u32,
    payload_type: u8,
    samples_per_frame: u32,
}

impl RtpSbcSession {
    pub fn new(ssrc: u32, payload_type: u8, samples_per_frame: u32) -> Self {
        Self { sequence_number: 1, timestamp: 0, ssrc, payload_type, samples_per_frame }
    }

    /// Wraps an existing SBC payload (Header + Frames) with an RTP Header.
    pub fn wrap_sbc_packet(&mut self, sbc_payload: Vec<u8>) -> Vec<u8> {
        if sbc_payload.is_empty() {
            return Vec::new();
        }
        // 1. Parse Frame Count from the SBC Payload Header (First Byte)
        // Format: [F S L RFA | Number_of_Frames (4 bits)]
        let sbc_header_byte = sbc_payload[0];
        let frame_count = sbc_header_byte & 0x0F;
        // 2. Create the RTP Header (12 Bytes)
        let mut rtp_packet = Vec::with_capacity(12 + sbc_payload.len());
        // Byte 0: Version(2) | Padding(0) | Extension(0) | CSRC Count(0) -> 0x80
        rtp_packet.push(0x80);
        // Byte 1: Marker(0) | Payload Type
        rtp_packet.push(self.payload_type & 0x7F);
        // Bytes 2-3: Sequence Number (Big Endian)
        rtp_packet.extend_from_slice(&self.sequence_number.to_be_bytes());
        // Bytes 4-7: Timestamp (Big Endian)
        rtp_packet.extend_from_slice(&self.timestamp.to_be_bytes());
        // Bytes 8-11: SSRC (Big Endian)
        rtp_packet.extend_from_slice(&self.ssrc.to_be_bytes());
        // 3. Merge: Append the provided SBC payload (Header + Frames)
        rtp_packet.extend(sbc_payload);
        // 4. Update State for the NEXT packet
        self.sequence_number = self.sequence_number.wrapping_add(1);
        // Timestamp increases by (Number of Frames * Samples per Frame)
        let ticks_advanced = (frame_count as u32) * self.samples_per_frame;
        self.timestamp = self.timestamp.wrapping_add(ticks_advanced);
        rtp_packet
    }
}

#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CAllocationMethod {
    Loudness = 0,
    SNR = 1,
}

#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct CSbcConfig {
    pub allocation_method: CAllocationMethod,
    pub block_length: c_int,
    pub subbands: c_int,
    pub min_bitpool: c_int,
    pub max_bitpool: c_int,
}

impl<'a> From<&'a AudioConfig> for &'a CSbcConfig {
    #[allow(unreachable_patterns)]
    fn from(audio: &'a AudioConfig) -> Self {
        match audio.codec {
            CodecConfig::Sbc(ref v) => v,
            _ => panic!("AudioConfig does not contain CSbcConfig"),
        }
    }
}
