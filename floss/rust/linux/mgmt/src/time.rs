//! Waking timers for Bluetooth. Implemented using timerfd, but supposed to feel similar to
///Tokio's time
use nix::sys::time::TimeSpec;
use nix::sys::timerfd::{ClockId, Expiration, TimerFd, TimerFlags, TimerSetTimeFlags};
use std::time::Duration;
use tokio::io::unix::AsyncFd;

/// A single shot Alarm
pub struct Alarm {
    fd: AsyncFd<TimerFd>,
}

impl Alarm {
    /// Construct a new alarm
    pub fn new() -> Self {
        let timer = TimerFd::new(get_clock(), TimerFlags::empty()).unwrap();
        Self { fd: AsyncFd::new(timer).unwrap() }
    }

    /// Reset the alarm to duration, starting from now
    pub fn reset(&self, duration: Duration) {
        self.fd
            .get_ref()
            .set(Expiration::OneShot(TimeSpec::from(duration)), TimerSetTimeFlags::empty())
            .unwrap();
    }

    /// Completes when the alarm has expired
    pub async fn expired(&self) {
        let mut read_ready =
            self.fd.readable().await.expect("TimerFd is never expected to fail to be readable");
        read_ready.clear_ready();
        drop(read_ready);
        // Will not block, since we have confirmed it is readable
        if self.fd.get_ref().get().unwrap().is_some() {
            self.fd.get_ref().wait().unwrap();
        }
    }
}

impl Default for Alarm {
    fn default() -> Self {
        Alarm::new()
    }
}

fn get_clock() -> ClockId {
    if cfg!(target_os = "android") {
        ClockId::CLOCK_BOOTTIME_ALARM
    } else {
        ClockId::CLOCK_BOOTTIME
    }
}

#[cfg(test)]
mod tests {
    use super::Alarm;
    use std::time::Duration;

    #[test]
    fn alarm_cancel_after_expired() {
        let runtime = tokio::runtime::Runtime::new().unwrap();
        runtime.block_on(async {
            let alarm = Alarm::new();
            alarm.reset(Duration::from_millis(10));
            tokio::time::sleep(Duration::from_millis(30)).await;
            alarm.reset(Duration::from_millis(0));

            for _ in 0..10 {
                let ready_in_10_ms = async {
                    tokio::time::sleep(Duration::from_millis(10)).await;
                };

                tokio::select! {
                    _ = alarm.expired() => (),
                    _ = ready_in_10_ms => (),
                }
            }
        });
    }

    #[test]
    fn alarm_clear_ready_after_expired() {
        // After an alarm expired, we need to make sure we clear ready from AsyncFdReadyGuard.
        // Otherwise it's still ready and select! won't work.
        let runtime = tokio::runtime::Runtime::new().unwrap();
        runtime.block_on(async {
            let alarm = Alarm::new();
            alarm.reset(Duration::from_millis(10));
            alarm.expired().await;
            let ready_in_10_ms = async {
                tokio::time::sleep(Duration::from_millis(10)).await;
            };
            tokio::select! {
                _ = alarm.expired() => (),
                _ = ready_in_10_ms => (),
            }
        });
    }
}
