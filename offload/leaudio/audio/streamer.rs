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

use crate::codec::{self, CodecConfig, Encode, PcmFrame};
use crate::ffi::{CAudioConfig, CIsoStream};
use std::cmp::{max, min};
use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex, MutexGuard, RwLock};
use std::thread::{self, sleep, JoinHandle};
use std::time::{Duration, Instant};

pub struct Streamer<Cb: Callbacks> {
    state: Mutex<State>,
    iso: Arc<RwLock<IsoState>>,
    audio: AudioConfig,
    anchor_delay: Duration,
    callbacks: Arc<Cb>,
}

pub trait Callbacks: Send + Sync + 'static {
    fn start(&self);
    fn stop(&self);
    fn send(&self, handle: u16, sequence_number: u16, data: &[u8]);
}

#[derive(Clone, Copy)]
pub(crate) struct AudioConfig {
    pub bitdepth: usize,
    pub sample_rate: u32,
    pub frame_duration_us: u32,
    pub codec: CodecConfig,
}

#[derive(Clone, Copy, Default)]
struct IsoState {
    streams: [IsoStream; 2],
    count: usize,
}

#[derive(Clone, Copy, Default)]
struct IsoStream {
    handle: u16,
    channels: usize,
    t0: Option<Instant>,
    drift_us: i64,
}

enum State {
    Idle,
    Running { fifo: Fifo, _worker: Worker },
}

impl<Cb: Callbacks> Streamer<Cb> {
    pub fn new(
        iso: &[CIsoStream],
        audio: &CAudioConfig,
        anchor_delay: Duration,
        callbacks: Cb,
    ) -> Result<Self, String> {
        let iso = IsoState::from(iso)?;
        let channels_by_stream = match iso.streams().count() {
            1 => iso.streams[0].channels,
            _ => 1,
        };

        let audio = AudioConfig::from(audio);
        if !matches!(audio.bitdepth, 16 | 24 | 32) {
            return Err(format!("Invalid bitdepth: {}", audio.bitdepth));
        }

        codec::validate_encoder(&audio, channels_by_stream)?;

        Ok(Self {
            state: Mutex::new(State::Idle),
            iso: Arc::new(RwLock::new(iso)),
            callbacks: Arc::new(callbacks),
            audio,
            anchor_delay,
        })
    }

    pub fn enable(
        &self,
        handle: u16,
        t0: Instant,
        link_feedback: bool,
        sdu_interval_us: u32,
        max_sdu_size: usize,
    ) {
        let mut state = self.state.lock().unwrap();

        {
            let mut iso = self.iso.write().unwrap();
            iso.enable(handle, t0);
        }

        if matches!(*state, State::Idle) {
            if self.audio.frame_duration_us != sdu_interval_us {
                log::error!(
                    "Unframed SDU is not supported\n\
                    SDU interval MUST match the audio frame duration ({} != {})",
                    sdu_interval_us,
                    self.audio.frame_duration_us
                );
                return;
            }

            let sample_rate = self.audio.sample_rate;
            let frame_duration_us = self.audio.frame_duration_us;
            let frame_len = ((sample_rate as u64 * frame_duration_us as u64) / 1_000_000) as usize;
            let fifo = Fifo::new(self.audio.bitdepth, frame_len);

            let cb_clone = self.callbacks.clone();
            *state = State::Running {
                fifo: fifo.clone(),
                _worker: Worker::new(
                    self.iso.clone(),
                    fifo,
                    max_sdu_size,
                    self.audio,
                    if link_feedback { self.anchor_delay } else { Duration::ZERO },
                    move |hdl, sn, data| cb_clone.send(hdl, sn, data),
                ),
            };
            self.callbacks.start();
        }
    }

    pub fn disable(&self, handle: u16) {
        let mut state = self.state.lock().unwrap();

        let active = {
            let mut iso = self.iso.write().unwrap();
            iso.disable(handle);
            iso.active()
        };

        if !active {
            *state = State::Idle;
            self.callbacks.stop();
        }
    }

    pub fn anchor(&self, handle: u16, sn: i64, drift: i64) {
        let mut iso = self.iso.write().unwrap();
        iso.anchor(handle, sn, drift);
    }

    pub fn write(&self, chunk: &[u8]) -> Result<usize, String> {
        let fifo = {
            let state = self.state.lock().unwrap();
            if let State::Running { ref fifo, .. } = *state {
                Some(fifo.inner.clone())
            } else {
                None
            }
        };
        if let Some(fifo) = fifo {
            Ok(fifo.write(chunk))
        } else {
            Err("ISO stream(s) is not running".to_string())
        }
    }
}

impl AudioConfig {
    fn from(config: &CAudioConfig) -> Self {
        Self {
            bitdepth: config.bitdepth as usize,
            sample_rate: config.sample_rate as u32,
            frame_duration_us: config.frame_duration_us as u32,
            codec: CodecConfig::from(&config.codec_type, &config.codec_config),
        }
    }
}

impl IsoState {
    fn from(src: &[CIsoStream]) -> Result<Self, String> {
        if !matches!(src.len(), 1..=2) {
            return Err(format!("Invalid stream count: {}", src.len()));
        }

        let mut streams = [Default::default(); 2];
        let mut channels_mask = 0;

        for s in src.iter() {
            let idx = s.channel_allocation.trailing_zeros() as usize;
            if idx >= 2 || channels_mask & s.channel_allocation != 0 {
                return Err("Invalid channel allocation definition".to_string());
            }
            channels_mask |= s.channel_allocation;

            streams[idx] = IsoStream {
                handle: s.handle,
                channels: s.channel_allocation.count_ones() as usize,
                ..Default::default()
            }
        }

        if channels_mask != 0b11 {
            return Err("Missing allocation for Left or Right channel".to_string());
        }

        Ok(Self { streams, count: src.len() })
    }

    fn streams(&self) -> impl Iterator<Item = &'_ IsoStream> {
        self.streams.iter().take(self.count)
    }

    fn active(&self) -> bool {
        self.streams.iter().any(|s| s.t0.is_some())
    }

    fn enable(&mut self, handle: u16, t0: Instant) {
        if let Some(s) = self.streams.iter_mut().find(|s| s.handle == handle) {
            s.t0 = Some(t0);
        };
    }

    fn disable(&mut self, handle: u16) {
        if let Some(s) = self.streams.iter_mut().find(|s| s.handle == handle) {
            s.t0 = None;
        };
    }

    fn anchor(&mut self, handle: u16, _sn: i64, drift_us: i64) {
        if let Some(s) = self.streams.iter_mut().find(|s| s.handle == handle) {
            s.drift_us = drift_us;
        };
    }
}

struct Worker {
    thread: Option<JoinHandle<()>>,
    halt: Arc<AtomicBool>,
}

impl Worker {
    fn new<F>(
        iso: Arc<RwLock<IsoState>>,
        fifo: Fifo,
        max_sdu_size: usize,
        audio: AudioConfig,
        anchor_delay: Duration,
        send: F,
    ) -> Self
    where
        F: Fn(u16, u16, &[u8]) + Send + 'static,
    {
        let sample_rate = audio.sample_rate;
        let frame_duration_us = audio.frame_duration_us;
        let frame_len = ((sample_rate as u64 * frame_duration_us as u64) / 1_000_000) as usize;

        let halt = Arc::new(AtomicBool::new(false));
        let halt_clone = halt.clone();
        let thread = thread::Builder::new()
            .name("lea_swoff".to_owned())
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
                        log::warn!("failed to configure sched priority");
                    }
                }

                let min_delay = min(Duration::from_millis(10), anchor_delay);
                let mut worker = WorkerThread::new(audio, max_sdu_size, send);
                let mut clocker = Clocker::new(audio.frame_duration_us, min_delay, anchor_delay);
                let mut underrun = 0;

                log::info!("Streaming started");

                while !halt_clone.load(Ordering::Relaxed) {
                    let iso_snapshot = { *iso.read().unwrap() };
                    let now = Instant::now();
                    let Some((deadline, seq_nums)) = clocker.deadline(iso_snapshot, now) else {
                        break;
                    };

                    sleep(deadline - now);

                    let timeout = anchor_delay - min_delay - (now - deadline);
                    let Some(frame) = fifo.get(frame_len, timeout) else {
                        if underrun == 0 {
                            log::warn!("PCM underrun starts");
                        } else if underrun % (1_000_000 / frame_duration_us) == 0 {
                            log::warn!("PCM underrun: {underrun} SDU starved");
                        }
                        underrun += 1;
                        continue;
                    };
                    if underrun > 0 {
                        log::warn!("PCM underrun ends: {underrun} SDU starved");
                        underrun = 0;
                    }

                    worker.run(frame, iso_snapshot, seq_nums);
                }

                if underrun > 0 {
                    log::warn!("PCM underrun ends: {underrun} SDU starved before stopped");
                }
                log::info!("Streaming stopped");
            })
            .expect("failed to spawn worker thread");

        Self { thread: Some(thread), halt }
    }
}

impl Drop for Worker {
    fn drop(&mut self) {
        self.halt.store(true, Ordering::Relaxed);
        let thread = self.thread.take().unwrap();
        thread.join().expect("End of thread loop");
    }
}

struct WorkerThread<F> {
    audio: AudioConfig,
    max_sdu_size: usize,
    encoders: [Option<Box<dyn Encode>>; 2],
    send: F,
}

impl<F> WorkerThread<F>
where
    F: Fn(u16, u16, &[u8]),
{
    fn new(audio: AudioConfig, max_sdu_size: usize, send: F) -> Self {
        Self { audio, max_sdu_size, encoders: [None, None], send }
    }

    fn run(&mut self, frame: FifoFrame, iso: IsoState, seq_nums: [Option<u64>; 2]) {
        for (i, (iso, sn)) in iso.streams.iter().zip(seq_nums).enumerate() {
            let Some(sn) = sn else {
                self.encoders[i] = None;
                continue;
            };

            let encoder = self.encoders[i].get_or_insert_with(|| {
                codec::new_encoder(&self.audio, iso.channels, self.max_sdu_size)
            });

            let pcm = match iso.channels {
                0 => continue,
                1 => PcmFrame::from_fifo(&frame).channel(i),
                2.. => PcmFrame::from_fifo(&frame),
            };

            (self.send)(iso.handle, sn as u16, &encoder.encode(&pcm));
        }
    }
}

struct Clocker {
    seq_nums: [Option<u64>; 2],
    interval_us: u32,
    min_delay: Duration,
    anchor_delay: Duration,
}

impl Clocker {
    fn new(interval_us: u32, min_delay: Duration, anchor_delay: Duration) -> Self {
        Self { seq_nums: [None; 2], interval_us, min_delay, anchor_delay }
    }

    fn deadline(&mut self, iso: IsoState, now: Instant) -> Option<(Instant, [Option<u64>; 2])> {
        let (i_ref, s_ref) = iso.streams.iter().enumerate().find(|(_, &s)| s.t0.is_some())?;
        let interval_us = self.interval_us as u64;

        let mut sn_ref = match self.seq_nums[i_ref] {
            None => 0,
            Some(n) => n + 1,
        };
        let pos_ref_us = sn_ref as i64 * interval_us as i64 + s_ref.drift_us;

        let t0_ref = s_ref.t0.unwrap();
        let pos_ref = t0_ref + Duration::from_micros(max(pos_ref_us, 0) as u64);
        let mut deadline = pos_ref - self.anchor_delay;

        if now > pos_ref - self.min_delay {
            let gap = ((now - deadline).as_micros() as u64).div_ceil(interval_us);
            if self.seq_nums[i_ref].is_some() {
                log::warn!("Real-time loss: {gap} packet(s) skipped");
            }

            sn_ref += gap;
            deadline += Duration::from_micros(gap * interval_us);
        }

        self.seq_nums[i_ref] = Some(sn_ref);
        for (i, &s) in iso.streams.iter().enumerate() {
            self.seq_nums[i] = s.t0.map(|t0| {
                if t0_ref > t0 {
                    sn_ref + ((t0_ref - t0).as_micros() as u64 + interval_us / 2) / interval_us
                } else {
                    sn_ref - ((t0 - t0_ref).as_micros() as u64 + interval_us / 2) / interval_us
                }
            });
        }

        Some((deadline, self.seq_nums))
    }
}

struct InnerFifo {
    channels: usize,
    bitdepth: usize,
    queue: Mutex<VecDeque<u8>>,
    cvar_rd: Condvar,
    cvar_wr: Condvar,
    is_shutdown: AtomicBool,
}

/// A handle to the FIFO that automatically shuts down the stream when dropped.
/// **Warning**: Cloning creates a new handle to the *same* FIFO. Dropping *any* clone will
///              trigger the shutdown, terminating the stream for all other handles.
#[derive(Clone)]
struct Fifo {
    inner: Arc<InnerFifo>,
}

impl std::ops::Deref for Fifo {
    type Target = InnerFifo;

    fn deref(&self) -> &Self::Target {
        &self.inner
    }
}

impl Drop for Fifo {
    fn drop(&mut self) {
        if bluetooth_swoffload_aconfig_flags_rust::swoff_streamer_deadlock_protection() {
            self.inner.shutdown();
        }
    }
}

pub struct FifoFrame<'a> {
    pub channels: usize,
    pub bitdepth: usize,
    queue: MutexGuard<'a, VecDeque<u8>>,
    cvar: &'a Condvar,
    length: usize,
}

impl Fifo {
    fn new(bitdepth: usize, length: usize) -> Self {
        let channels = 2;
        let capacity = channels * length * (bitdepth / 8);
        Self {
            inner: Arc::new(InnerFifo {
                channels: 2,
                bitdepth,
                queue: Mutex::new(VecDeque::with_capacity(capacity)),
                cvar_rd: Condvar::new(),
                cvar_wr: Condvar::new(),
                is_shutdown: AtomicBool::new(false),
            }),
        }
    }
}

impl InnerFifo {
    fn is_shutdown(&self) -> bool {
        bluetooth_swoffload_aconfig_flags_rust::swoff_streamer_deadlock_protection()
            && self.is_shutdown.load(Ordering::Relaxed)
    }

    fn shutdown(&self) {
        if bluetooth_swoffload_aconfig_flags_rust::swoff_streamer_deadlock_protection() {
            let _lock = self.queue.lock().unwrap();
            self.is_shutdown.store(true, Ordering::Relaxed);
            self.cvar_wr.notify_all();
            self.cvar_rd.notify_all();
        }
    }

    fn get(&self, length: usize, timeout: Duration) -> Option<FifoFrame> {
        let queue = self.queue.lock().unwrap();
        if queue.capacity() == 0 {
            return None;
        }

        let cvar = &self.cvar_rd;
        let size = self.channels * length * (self.bitdepth / 8);
        let (queue, result) = cvar
            .wait_timeout_while(queue, timeout, |q| !self.is_shutdown() && q.len() < size)
            .unwrap();

        if self.is_shutdown() {
            return None;
        }

        if result.timed_out() {
            None
        } else {
            Some(FifoFrame {
                channels: self.channels,
                bitdepth: self.bitdepth,
                length,
                queue,
                cvar: &self.cvar_wr,
            })
        }
    }

    pub fn write(&self, mut chunk: &[u8]) -> usize {
        let write_len = chunk.len();

        let mut queue = self.queue.lock().unwrap();
        let cvar = &self.cvar_wr;

        while !chunk.is_empty() {
            queue = cvar
                .wait_while(queue, |q| {
                    !self.is_shutdown() && q.capacity() > 0 && q.len() >= q.capacity()
                })
                .unwrap();

            if self.is_shutdown() {
                break;
            }

            if queue.capacity() == 0 {
                break;
            }

            let len = min(chunk.len(), queue.capacity() - queue.len());
            queue.extend(&chunk[..len]);
            self.cvar_rd.notify_one();

            chunk = &chunk[len..];
        }

        write_len - chunk.len()
    }
}

impl Drop for InnerFifo {
    fn drop(&mut self) {
        let mut queue = self.queue.lock().unwrap();
        queue.clear();
        queue.shrink_to_fit();
        self.cvar_wr.notify_one();
    }
}

impl<'a> FifoFrame<'a> {
    pub fn data(&'a self) -> &'a [u8] {
        let size = self.channels * self.length * (self.bitdepth / 8);
        &self.queue.as_slices().0[..size]
    }
}

impl Drop for FifoFrame<'_> {
    fn drop(&mut self) {
        let size = self.data().len();
        self.queue.drain(..size);
        self.cvar.notify_one();
    }
}
