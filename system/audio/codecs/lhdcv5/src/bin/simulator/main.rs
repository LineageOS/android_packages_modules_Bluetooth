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

use lhdcv5::lhdc_api::*;
use log::{error, info};
use zerocopy::IntoBytes;
mod wave_file;
use wave_file::*;
mod lhdc_abr;
use lhdc_abr::*;

use std::fs::File;
use std::io::Write;

fn bitrate_to_inx(inx: &mut u32, rate: u32) -> i32 {
    match rate {
        64 => {
            *inx = LHDC_QUALITY_LOW0;
        }
        160 => {
            *inx = LHDC_QUALITY_LOW1;
        }
        192 => {
            *inx = LHDC_QUALITY_LOW2;
        }
        240 | 256 => {
            *inx = LHDC_QUALITY_LOW3;
        }
        320 => {
            *inx = LHDC_QUALITY_LOW4;
        }
        400 => {
            *inx = LHDC_QUALITY_LOW;
        }
        480 | 500 => {
            *inx = LHDC_QUALITY_MID;
        }
        900 => {
            *inx = LHDC_QUALITY_HIGH;
        }
        1000 => {
            *inx = LHDC_QUALITY_HIGH1;
        }
        0 => {
            *inx = LHDC_QUALITY_AUTO;
        }
        _ => {}
    }
    LHDC_FRET_SUCCESS
}

fn write_lhdc_file_header(
    f: &mut File,
    channel: libc::c_int,
    sample_rate: libc::c_int,
    bits_per_sample: libc::c_int,
    frm_size: libc::c_int,
    enc_frm_len: libc::c_int,
    _total_samples: libc::c_int,
) {
    f.write_all(b"LHDC").unwrap();
    f.write_all(channel.as_bytes()).unwrap();
    f.write_all(sample_rate.as_bytes()).unwrap();
    f.write_all(bits_per_sample.as_bytes()).unwrap();
    f.write_all(frm_size.as_bytes()).unwrap();
    f.write_all(enc_frm_len.as_bytes()).unwrap();
    f.write_all(_total_samples.as_bytes()).unwrap();
}
fn get_handle(version: u32, handle: &mut Option<HANDLE_LHDC_BT>) -> i32 {
    if version != LHDC_VERSION_1 {
        error!("Invalid version ({version})!");
        return LHDC_FRET_ERROR;
    }
    let h_lhdc_bt = Box::new(Context::new(version).unwrap());
    *handle = Some(h_lhdc_bt);
    LHDC_FRET_SUCCESS
}
fn init_encoder(
    lhdc_abr: &mut LHDC_ABR,
    abr: &mut AutoBitRate,
    sampling_freq: u32,
    bits_per_sample: u32,
    bitrate_inx: u32,
    mtu: u32,
    interval: u32,
) -> i32 {
    if sampling_freq != LHDC_SR_44100HZ
        && sampling_freq != LHDC_SR_48000HZ
        && sampling_freq != LHDC_SR_96000HZ
        && sampling_freq != LHDC_SR_192000HZ
    {
        error!("Invalid sampling frequency ({sampling_freq})!");
        return LHDC_FRET_INVALID_INPUT_PARAM;
    }
    if bits_per_sample != LHDCBT_SMPL_FMT_S16 && bits_per_sample != LHDCBT_SMPL_FMT_S24 {
        error!("Invalid bits per sample ({bits_per_sample})!");
        return LHDC_FRET_INVALID_INPUT_PARAM;
    }
    if bitrate_inx < LHDC_QUALITY_LOW0 || bitrate_inx > LHDC_QUALITY_AUTO {
        error!("Invalid bit rate (index) ({bitrate_inx})!");
        return LHDC_FRET_INVALID_INPUT_PARAM;
    }
    let func_ret = abr.handle.init_encoder(
        sampling_freq,
        bits_per_sample,
        bitrate_inx,
        LHDC_FRAME_5MS,
        mtu,
        interval,
    );
    if func_ret.is_err() {
        error!("Failed to init LHDC encoder ({:?})!", func_ret);
        return LHDC_FRET_ERROR;
    }
    lhdc_abr.lhdcBT_autoBR_adjust_bitrate_init(sampling_freq);
    LHDC_FRET_SUCCESS
}

fn encode(
    handle: &mut Context,
    in_pcm: &[u8],
    out: &mut [u8],
    p_out_bytes: &mut u32,
    p_out_frames: &mut u32,
) -> i32 {
    let func_ret = handle.enc_process(in_pcm, out, p_out_bytes, p_out_frames);
    if func_ret.is_err() {
        error!("Failed to encode pcm samples ({:?})!", func_ret);
        return LHDC_FRET_ERROR;
    }
    LHDC_FRET_SUCCESS
}

fn get_block_size(handle: &Context, samples_per_frame: &mut u32) -> i32 {
    match handle.get_block_size() {
        Ok(block_size) => {
            *samples_per_frame = block_size;
            LHDC_FRET_SUCCESS
        }
        Err(err) => {
            error!("Failed to get block size ({:?}) ({})!", err, *samples_per_frame);
            return LHDC_FRET_ERROR;
        }
    }
}

fn set_max_bitrate(handle: &mut Context, max_bitrate_inx: u32) -> i32 {
    if max_bitrate_inx < LHDC_QUALITY_LOW || max_bitrate_inx > LHDC_QUALITY_MAX_BITRATE {
        error!("Invalid max bit rate index ({max_bitrate_inx})!");
        return LHDC_FRET_INVALID_INPUT_PARAM;
    }
    let func_ret = handle.set_max_bitrate_inx(max_bitrate_inx);
    if func_ret.is_err() {
        error!("failed to set max. bit rate index({:?}), ({max_bitrate_inx})!", func_ret);
        return LHDC_FRET_ERROR;
    }

    LHDC_FRET_SUCCESS
}

fn set_min_bitrate(handle: &mut Context, min_bitrate_inx: u32) -> i32 {
    if min_bitrate_inx < LHDC_QUALITY_LOW0 || min_bitrate_inx > LHDC_QUALITY_LOW {
        error!("Invalid min bit rate index ({min_bitrate_inx})!");
        return LHDC_FRET_INVALID_INPUT_PARAM;
    }
    let func_ret = handle.set_min_bitrate_inx(min_bitrate_inx);
    if func_ret.is_err() {
        error!("failed to set min. bit rate ({:?})", func_ret);
        return LHDC_FRET_ERROR;
    }

    LHDC_FRET_SUCCESS
}

fn set_bitrate(abr_handle: &mut LHDC_ABR, abr: &mut AutoBitRate, bitrate_inx: u32) -> i32 {
    let upd_qual_status: bool;
    let mut func_ret = LHDC_FRET_SUCCESS;

    match bitrate_inx {
        LHDC_QUALITY_CTRL_RESET_ABR => {
            abr_handle.lhdcBT_autoBR_reset_abr_index();
            let func_ret = abr.handle.set_target_bitrate_inx(LHDC_QUALITY_LOW, false);
            if func_ret.is_err() {
                error!("lhdcv5_enc_util_set_target_bitrate_inx error ({:?})!", func_ret);
                return LHDC_FRET_ERROR;
            }
        }
        0..=13 => {
            if bitrate_inx == LHDC_QUALITY_AUTO {
                abr_handle.lhdcBT_autoBR_reset_abr_index();
            } else {
                upd_qual_status = true;
                let func_ret = abr.handle.set_target_bitrate_inx(bitrate_inx, upd_qual_status);
                if func_ret.is_err() {
                    error!("lhdcv5_enc_util_set_target_bitrate_inx error ({:?})!", func_ret);
                    return LHDC_FRET_ERROR;
                }
            }
        }
        _ => {
            func_ret = LHDC_FRET_ERROR;
        }
    }
    if func_ret != LHDC_FRET_SUCCESS {
        error!("failed to set bitrate ({bitrate_inx}) err({func_ret})!");
        return LHDC_FRET_ERROR;
    }

    LHDC_FRET_SUCCESS
}
fn encode_main(in_0: &str, bitrate: u32) -> i32 {
    let mut fmt: wav_chunk_fmt_t = wav_chunk_fmt_t {
        type_0: 0,
        channel: 0,
        sample_rate: 0,
        bytes_per_sec: 0,
        block_align: 0,
        bits_per_sample: 0,
    };
    let channels: u8 = 2;
    let mut bitrate_inx: u32 = 0;
    let mut samples_per_frame: u32 = 0;
    let mut _pcm_bytes_per_frame: usize = 0;
    let mut read_buffer: [u8; 15360] = [0; 15360];
    let mut write_buffer: [u8; 15360] = [0; 15360];
    let enc_flen: u32 = 0;
    let mut byte_written: u32 = 0;
    let mut out_frames: u32 = 0;
    let mtu: u32 = LHDC_MTU_3MBPS;
    let mut abr_queue_len: u32 = 0;
    let mut win = WavFile::open(&mut fmt, in_0).unwrap();
    let mut _total_samples: u32 = 0;
    let mut _total_frm_num: u32 = 0;
    let mut _byte_of_frame: u32 = 0;
    info!("input wav file opened: {in_0}");
    let type_0 = fmt.type_0;
    let channel = fmt.channel;
    let sample_rate = fmt.sample_rate;
    let bits_per_sample = fmt.bits_per_sample;
    let data_size = win.data_size;
    let mut lhdc_abr = LHDC_ABR::new();
    info!(
        "Input wav: type={type_0} chan={channel} sample_rate={sample_rate} bits_per_sample={bits_per_sample} size={data_size}"
    );
    let mut handle = Box::new(None);
    let mut func_ret = get_handle(LHDC_VERSION_1, &mut handle);
    if func_ret != LHDC_FRET_SUCCESS {
        error!("lhdcBT_get_handle error {func_ret}");
        return func_ret;
    }
    info!("get handle done!");
    let inner_handle: &mut Context = match handle.as_mut() {
        Some(inner) => inner.as_mut(),
        None => {
            error!("handle none");
            return LHDC_FRET_ERROR;
        }
    };
    // Get bitrate index from bitrate
    if bitrate != 0 {
        // adopt fixed bit rate
        func_ret = bitrate_to_inx(&mut bitrate_inx, bitrate);
        if func_ret != LHDC_FRET_SUCCESS {
            error!("fail to lhdcBT_bitrate_to_inx {}", func_ret);
            return func_ret;
        }
        info!("QualityMode(Fixed Bitrate): {}(idx:{})", bitrate, bitrate_inx);
    } else {
        // adopt auto bit rate mechanism
        bitrate_inx = LHDC_QUALITY_AUTO;
        info!("QualityMode(Auto Bitrate): {}(idx:{})", bitrate, bitrate_inx);
    }
    let mut abr = AutoBitRate { handle: inner_handle, table_index: 0 };
    func_ret = init_encoder(
        &mut lhdc_abr,
        &mut abr,
        fmt.sample_rate,
        u32::from(fmt.bits_per_sample),
        bitrate_inx,
        mtu,
        LHDC_ENC_INTERVAL_20MS,
    );
    if func_ret != LHDC_FRET_SUCCESS {
        error!("fail to lhdcBT_init_encoder {}", func_ret);
        return func_ret;
    }
    info!("init encoder done!");

    // setup after encoder initialized
    func_ret = set_min_bitrate(abr.handle, LHDC_QUALITY_LOW1);
    if func_ret != LHDC_FRET_SUCCESS {
        return func_ret;
    }

    func_ret = set_max_bitrate(abr.handle, LHDC_QUALITY_HIGH1);
    if func_ret != LHDC_FRET_SUCCESS {
        return func_ret;
    }

    func_ret = set_bitrate(&mut lhdc_abr, &mut abr, bitrate_inx);
    if func_ret != LHDC_FRET_SUCCESS {
        return func_ret;
    }
    //4. Prepare encode...
    func_ret = get_block_size(abr.handle, &mut samples_per_frame);
    if func_ret != LHDC_FRET_SUCCESS {
        error!("fail to lhdcBT_get_block_Size");
        return func_ret;
    }
    info!("get samples_per_frame {}", samples_per_frame);
    _pcm_bytes_per_frame =
        samples_per_frame as usize * channels as usize * fmt.bits_per_sample as usize / 8;

    if _pcm_bytes_per_frame > std::mem::size_of_val(&read_buffer) {
        error!("expected read size error");
        return func_ret;
    }
    info!("set mtu {}", mtu);
    _total_samples = win.data_size / u32::from(fmt.channel) / (u32::from(fmt.bits_per_sample) / 8);
    _total_frm_num = _total_samples.div_ceil(samples_per_frame) + 1; // extra 1 zero frame to push out delay
    _byte_of_frame =
        samples_per_frame * (u32::from(fmt.bits_per_sample) / 8) * (u32::from(channels));

    //Open and configure output encoded file header
    let len = in_0.len().wrapping_sub(4) as u32;
    let in_1: String = in_0.chars().take(len as usize).collect();
    let out = if bitrate == 0 {
        format!("{in_1}_abr.lhdcRs")
    } else {
        format!("{in_1}_{}.lhdcRs", (bitrate * 1000))
    };
    let mut fout = File::create(out).unwrap();
    write_lhdc_file_header(
        &mut fout,
        fmt.channel as libc::c_int,
        fmt.sample_rate as libc::c_int,
        fmt.bits_per_sample as libc::c_int,
        samples_per_frame as libc::c_int,
        enc_flen as libc::c_int,
        _total_samples as libc::c_int,
    );

    info!("encoder ready, press any key to encode...");
    info!("START TO ENCODE: _total_samples:{_total_samples} _total_frm_num:{_total_frm_num}");
    for frm in 0.._total_frm_num {
        win.read(&mut read_buffer, _byte_of_frame as libc::c_int);
        func_ret =
            encode(abr.handle, &read_buffer, &mut write_buffer, &mut byte_written, &mut out_frames);
        if func_ret != LHDC_FRET_SUCCESS {
            error!("fail to lhdcBT_encode");
            return func_ret;
        }
        //// LHDC "Auto Bit Rate mechanism" tutorial
        //// 1. LHDC "Auto Bit Rate Mechanism" provides different flows for three kinds of scenarios:
        ////   Lossy-Only: (ABR)
        ////      The maximum bitrate stage is limited under 500kbps.
        ////      The behavior of ABR is customizable.
        //// 2. Frequency to invoke the auto bitrate mechanism:
        ////   The frequency, for example, can be defined from interval of the streaming encoding process.
        ////   In AOSP, it is usually 10ms or 20ms per interval.
        ////   You may design your own interval.
        ////   However do not adjust bitrate when a frame is encoding.
        ////   Changing the bitrate during a frame encoding may cause unexpected results.
        //// 3. The abr_queue_len in auto bitrate:
        ////   In the tutorial, the abr_queue_len is a measuring parameter to determine
        ////   whether if doing downtier or uptier the bitrate.
        ////   This is referred from original AOSP A2DP audio streaming subsystem,
        ////   where it notifies the remaining packets of the output buffer periodically
        ////   to feedback current transmission quality to the encoder.
        ////   You can design your own measurement schema based on your system.
        if bitrate_inx == LHDC_QUALITY_AUTO {
            // An example for Auto BitRate simulation:
            // phase I: [0~60] (sec): abr_queue_len increase by 1 every 5 sec
            // phase II: (60~] (sec): abr_queue_len always set to 0
            if frm <= 12000 {
                //0.005(s) * 12000 = 60s
                if frm % 1000 == 0 {
                    //0.005(s) * 1000 = 5s
                    abr_queue_len += 1;
                }
            } else {
                abr_queue_len = 0;
            }
            if frm % 20 == 0 {
                //check interval = 0.005 * 20 = 0.1 (s)
                lhdc_abr.lhdcBT_autoBR_adjust_bitrate_process(&mut abr, abr_queue_len);
            }
        }
        if byte_written == 0 {
            continue;
        }

        if frm % 1000 == 0 {
            log::info!(
                "[{}/{}] {:.2} - written:{} out_frame:{}",
                frm,
                _total_frm_num,
                frm as f32 / _total_frm_num as f32,
                byte_written,
                out_frames
            );
        }
        fout.write_all(&write_buffer[0..byte_written as usize]).unwrap();
    }

    info!("exit");
    func_ret
}

fn main_usage() {
    println!("LHDC Encoding tester");
    println!("Usage: (2 args required)");
    println!("arg 1: file path of the wave file");
    println!("arg 2: target bit rate(bps), default: adopt auto bitrate mode if not set(or given value:0)");
}

pub fn main() {
    env_logger::init();
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 2 {
        main_usage();
    }
    if args.len() == 2 {
        encode_main(args[1].as_str(), 0);
    } else {
        let br = args[2].parse().unwrap();
        encode_main(args[1].as_str(), br);
    }
}
