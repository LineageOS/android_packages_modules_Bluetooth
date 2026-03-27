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

use crate::codec::{self, CodecConfig, Encode, PcmFrame};
use crate::ffi::CAudioConfig;
use std::cmp::min;
use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex, MutexGuard, Weak};
use std::thread::{self, sleep, JoinHandle};
use std::time::{Duration, Instant};

const L2CAP_HEADER_SIZE: usize = 4; // Length + CID

pub struct Streamer<Cb: Callbacks> {
    state: Mutex<State>,
    audio: AudioConfig,
    callbacks: Arc<Cb>,
}

pub trait Callbacks: Send + Sync + 'static {
    fn start(&self);
    fn stop(&self);
    fn send(&self, handle: u16, data: &[u8]);
}

#[derive(Clone, Copy)]
pub(crate) struct AudioConfig {
    pub channel_mode: usize,
    pub bitdepth: usize,
    pub sample_rate: u32,
    pub frame_duration_us: u32,
    pub codec: CodecConfig,
}

enum State {
    Idle,
    Running { fifo: Weak<Fifo>, _worker: Worker },
}

impl<Cb: Callbacks> Streamer<Cb> {
    /// SAFETY: The caller must ensure that `audio.codec_config` matches `audio.codec_type`.
    pub unsafe fn new(audio: &CAudioConfig, callbacks: Cb) -> Result<Self, String> {
        // SAFETY: The caller guarantees that that `audio.codec_config` matches `audio.codec_type`.
        let audio = unsafe { AudioConfig::from(audio) };

        if !matches!(audio.bitdepth, 16 | 24 | 32) {
            return Err(format!("Invalid bitdepth: {}", audio.bitdepth));
        }

        codec::validate_encoder(&audio, audio.channel_mode)?;

        Ok(Self { state: Mutex::new(State::Idle), callbacks: Arc::new(callbacks), audio })
    }

    pub fn enable(&self, handle: u16, l2cap_channel_id: u16, peer_mtu: u16) {
        let mut state = self.state.lock().unwrap();

        if matches!(*state, State::Idle) {
            let sample_rate = self.audio.sample_rate;
            let frame_duration_us = self.audio.frame_duration_us;
            let frame_len = ((sample_rate as u64 * frame_duration_us as u64) / 1_000_000) as usize;
            let fifo = Arc::new(Fifo::new(self.audio.bitdepth, self.audio.channel_mode, frame_len));

            let cb_clone = self.callbacks.clone();
            *state = State::Running {
                fifo: Arc::downgrade(&fifo),
                _worker: Worker::new(
                    handle,
                    fifo,
                    l2cap_channel_id,
                    peer_mtu,
                    self.audio,
                    move |hdl, data| cb_clone.send(hdl, data),
                ),
            };
            self.callbacks.start();
        } else {
            log::warn!("Streamer::enable: Streamer is already running, ignoring enable request.");
        }
    }

    pub fn disable(&self) {
        let mut state = self.state.lock().unwrap();
        if !matches!(*state, State::Idle) {
            *state = State::Idle;
            self.callbacks.stop();
        }
    }

    pub fn write(&self, chunk: &[u8]) -> Result<usize, String> {
        let fifo = {
            let state = self.state.lock().unwrap();
            if let State::Running { ref fifo, .. } = *state {
                fifo.upgrade()
            } else {
                None
            }
        };
        if let Some(fifo) = fifo {
            Ok(fifo.write(chunk))
        } else {
            Err("A2DP stream is not running".to_string())
        }
    }
}

impl AudioConfig {
    /// SAFETY: The caller must ensure that `config.codec_config` is initialized
    /// with the variant corresponding to `config.codec_type`.
    unsafe fn from(config: &CAudioConfig) -> Self {
        Self {
            channel_mode: config.channel_mode as usize,
            bitdepth: config.bitdepth as usize,
            sample_rate: config.sample_rate as u32,
            frame_duration_us: config.frame_duration_us as u32,
            // SAFETY: The caller of AudioConfig::from guarantees that the
            // config union matches the type tag.
            codec: unsafe { CodecConfig::from(&config.codec_type, &config.codec_config) },
        }
    }
}

struct Worker {
    halt: Arc<AtomicBool>,
    thread: Option<JoinHandle<()>>,
}

impl Worker {
    fn new<F>(
        handle: u16,
        fifo: Arc<Fifo>,
        l2cap_channel_id: u16,
        peer_mtu: u16,
        audio: AudioConfig,
        send: F,
    ) -> Self
    where
        F: Fn(u16, &[u8]) + Send + 'static,
    {
        let frame_duration_us = audio.frame_duration_us;

        let halt = Arc::new(AtomicBool::new(false));
        let halt_clone = halt.clone();
        let thread = thread::Builder::new()
            .name("a2dp_swoff".to_owned())
            .spawn(move || {
                #[cfg(not(target_env = "musl"))]
                {
                    // Configure the thread scheduling to be SCHED_FIFO with priority 1.
                    // SAFETY: sched_param is passed by reference and thus is a valid pointer.
                    let res = unsafe {
                        let sched_param = libc::sched_param { sched_priority: 1 };
                        libc::sched_setscheduler(
                            // If pid equals zero, the scheduling policy and parameters of
                            // the calling thread will be set.
                            0,
                            libc::SCHED_FIFO,
                            &sched_param,
                        )
                    };

                    if res != 0 {
                        log::warn!("Worker::a2dp_swoff: failed to configure sched priority");
                    }
                }

                let mut worker = WorkerThread::new(audio, l2cap_channel_id, peer_mtu, send);
                let mut clocker = Clocker::new(audio.frame_duration_us);

                let mut underrun : u32 = 0;

                log::info!("Worker::a2dp_swoff: Streaming started for handle {}", handle);

                while !halt_clone.load(Ordering::Relaxed) {
                    let now = Instant::now();
                    let Some((deadline, _seq_num)) = clocker.deadline(now) else {
                        // This happens if the clock is not started.
                        log::warn!("Worker::a2dp_swoff: Clocker not ready, stopping stream.");
                        break;
                    };

                    if deadline > now {
                        sleep(deadline - now);
                    }

                    // Recalculate `now` after sleeping for a more accurate timeout.
                    let now = Instant::now();
                    let Some(frame) = fifo.get(worker.get_pcm_frame_len(now, underrun), now.saturating_duration_since(deadline)) else {
                        if underrun == 0 {
                            log::warn!("Worker::a2dp_swoff: PCM underrun starts");
                        } else if underrun.is_multiple_of(1_000_000 / frame_duration_us) {
                            log::warn!("Worker::a2dp_swoff: PCM underrun: {underrun} SDU starved");
                        }
                        underrun += 1;
                        continue;
                    };
                    if underrun > 0 {
                        log::warn!("Worker::a2dp_swoff: PCM underrun ends: {underrun} SDU starved");
                        underrun = 0;
                    }

                    worker.run(frame, handle);
                }

                if underrun > 0 {
                    log::warn!("Worker::a2dp_swoff: PCM underrun ends: {underrun} SDU starved before stopped");
                }
                log::info!("Worker::a2dp_swoff: Streaming stopped for handle {}", handle);
            })
            .expect("failed to spawn worker thread");

        Self { thread: Some(thread), halt }
    }
}

impl Drop for Worker {
    fn drop(&mut self) {
        self.halt.store(true, Ordering::Relaxed);
        if let Some(thread) = self.thread.take() {
            thread.join().expect("End of thread loop");
        }
    }
}

struct WorkerThread<F> {
    l2cap_channel_id: u16,
    peer_mtu: u16,
    encoder: Box<dyn Encode>,
    send: F,
}

impl<F> WorkerThread<F>
where
    F: Fn(u16, &[u8]),
{
    fn new(audio: AudioConfig, l2cap_channel_id: u16, peer_mtu: u16, send: F) -> Self {
        let encoder = codec::new_encoder(&audio, audio.channel_mode, peer_mtu);
        Self { l2cap_channel_id, peer_mtu, encoder, send }
    }

    fn get_pcm_frame_len(&mut self, timestamp: Instant, lost_packets_count: u32) -> usize {
        self.encoder.get_pcm_frame_len(timestamp, lost_packets_count)
    }

    fn run(&mut self, frame: FifoFrame, handle: u16) {
        let pcm = PcmFrame::from_fifo(&frame);
        let encoded_data = self.encoder.encode(&pcm);
        let enc_data_len = encoded_data.len() as u16;
        if enc_data_len > self.peer_mtu {
            log::error!(
                "WorkerThread::run: Encoded data len: {} is greater than peer MTU: {}",
                encoded_data.len(),
                self.peer_mtu
            );
            return;
        }
        let mut l2cap_packet = Vec::with_capacity(L2CAP_HEADER_SIZE + enc_data_len as usize);
        l2cap_packet.extend_from_slice(&enc_data_len.to_le_bytes());
        l2cap_packet.extend_from_slice(&self.l2cap_channel_id.to_le_bytes());
        l2cap_packet.extend(encoded_data);
        (self.send)(handle, &l2cap_packet);
    }
}

pub struct Clocker {
    /// The absolute start time of the stream.
    t0: Option<Instant>,

    /// The fixed duration between packets.
    interval: Duration,

    /// The current packet sequence number.
    /// Used to calculate the target time: t0 + (seq_num * interval).
    seq_num: u64,

    /// Threshold to determine if we are "too late".
    /// If we wake up and (TargetTime - Now) < -min_delay, we skip a packet.
    min_delay: Duration,
}

impl Clocker {
    /// Creates a new Clocker.
    /// interval_us: The duration of one packet in microseconds.
    pub fn new(interval_us: u32) -> Self {
        Self {
            t0: Some(Instant::now()),
            interval: Duration::from_micros(interval_us as u64),
            seq_num: 0,
            // Allow up to 5ms of jitter before we consider it a "loss" and skip ahead.
            min_delay: Duration::from_millis(5),
        }
    }

    /// Calculates the deadline for the next packet.
    /// Returns:
    /// - Some(Instant): The absolute time when the thread should wake up next.
    /// - Some(u64): The sequence number for the packet to be sent.
    pub fn deadline(&mut self, now: Instant) -> Option<(Instant, u64)> {
        let t0 = self.t0?;

        // 1. Calculate the Ideal Target Time
        // Target = StartTime + (PacketCount * Interval)
        let mut target_time = t0 + (self.seq_num as u32 * self.interval);

        // 2. Check for Real-Time Loss
        // If 'now' is already past 'target_time' by more than 'min_delay',
        // we are running behind.
        if now > target_time + self.min_delay {
            // Calculate how many intervals we missed
            let time_behind = now - target_time;
            let missed_packets = time_behind.as_micros() as u64 / self.interval.as_micros() as u64;

            // Skip the missed packets
            let gap = missed_packets + 1;
            self.seq_num += gap;

            // Recalculate target time for the new sequence number
            target_time = t0 + (self.interval * self.seq_num as u32);

            log::warn!("Clocker::deadline: Skipped {} packets to catch up", gap);
        }

        // 3. Prepare for the next call
        let current_seq = self.seq_num;
        self.seq_num += 1;

        Some((target_time, current_seq))
    }
}

struct Fifo {
    bitdepth: usize,
    queue: Mutex<VecDeque<u8>>,
    cvar_rd: Condvar,
    cvar_wr: Condvar,
}

pub struct FifoFrame<'a> {
    pub bitdepth: usize,
    queue: MutexGuard<'a, VecDeque<u8>>,
    cvar: &'a Condvar,
    length: usize,
}

impl Fifo {
    fn new(bitdepth: usize, channels: usize, length: usize) -> Self {
        let capacity = 3 * channels * length * (bitdepth / 8);
        log::debug!("Fifo::new: bitdepth: {bitdepth}, length: {length}, capacity: {capacity}");
        Self {
            bitdepth,
            queue: Mutex::new(VecDeque::with_capacity(capacity)),
            cvar_rd: Condvar::new(),
            cvar_wr: Condvar::new(),
        }
    }

    fn get(&self, length: usize, timeout: Duration) -> Option<FifoFrame> {
        let queue = self.queue.lock().unwrap();
        if queue.capacity() == 0 {
            return None;
        }

        let cvar = &self.cvar_rd;
        log::debug!("Fifo::get: length: {length}");
        let (mut queue, result) =
            cvar.wait_timeout_while(queue, timeout, |q| q.len() < length).unwrap();
        if result.timed_out() {
            log::debug!("Fifo::get: timeout");
            None
        } else {
            queue.make_contiguous();
            Some(FifoFrame { bitdepth: self.bitdepth, length, queue, cvar: &self.cvar_wr })
        }
    }

    pub fn write(&self, mut chunk: &[u8]) -> usize {
        let write_len = chunk.len();

        let mut queue = self.queue.lock().unwrap();
        let cvar = &self.cvar_wr;

        while !chunk.is_empty() {
            queue =
                cvar.wait_while(queue, |q| q.capacity() > 0 && q.len() >= q.capacity()).unwrap();
            let cap = queue.capacity();

            if cap == 0 {
                break;
            }

            let len = min(chunk.len(), queue.capacity() - queue.len());
            queue.extend(&chunk[..len]);
            self.cvar_rd.notify_one();

            chunk = &chunk[len..];
        }
        let write_total = write_len - chunk.len();
        log::debug!("Fifo::write: Written total: {write_total}");
        write_total
    }
}

impl Drop for Fifo {
    fn drop(&mut self) {
        let mut queue = self.queue.lock().unwrap();
        queue.clear();
        queue.shrink_to_fit();
        self.cvar_wr.notify_one();
    }
}

impl<'a> FifoFrame<'a> {
    pub fn data(&'a self) -> &'a [u8] {
        let length = self.length;
        &self.queue.as_slices().0[..length]
    }
}

impl Drop for FifoFrame<'_> {
    fn drop(&mut self) {
        let size = self.data().len();
        self.queue.drain(..size);
        self.cvar.notify_one();
    }
}
