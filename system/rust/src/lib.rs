// Copyright 2022, The Android Open Source Project
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

//! The core event loop for Rust modules. Here Rust modules are started in
//! dependency order.

use gatt::channel::AttTransport;
use gatt::GattCallbacks;
use log::{info, warn};
use tokio::task::LocalSet;

use std::rc::Rc;
use std::sync::Mutex;
use std::thread::JoinHandle;
use tokio::runtime::Builder;

use tokio::sync::mpsc;

pub mod core;
pub mod gatt;
pub mod packets;
pub mod utils;

/// The Rust Modules runner. Starts and processes messages from Java / C++
/// while the Rust thread is running.  Starts in an idle state.
#[derive(Default, Debug)]
enum RustModuleRunner {
    #[default]
    NotRunning,
    /// Main event loop is running and messages can be processed.  Use [`RustModuleRunner::send`] to
    /// queue a callback to be sent.
    Running {
        thread: JoinHandle<()>,
        tx: mpsc::UnboundedSender<BoxedMainThreadCallback>,
    },
    DisabledInTest,
}

/// The ModuleViews lets us access all publicly accessible Rust modules from
/// Java / C++ while the stack is running. If a module should not be exposed
/// outside of Rust GD, there is no need to include it here.
pub struct ModuleViews<'a> {
    /// Lets us call out into C++
    pub gatt_outgoing_callbacks: Rc<dyn GattCallbacks>,
    /// Receives synchronous callbacks from JNI
    pub gatt_incoming_callbacks: Rc<gatt::callbacks::CallbackTransactionManager>,
    /// Proxies calls into GATT server
    pub gatt_module: &'a mut gatt::server::GattModule,
}

static GLOBAL_MODULE_RUNNER: Mutex<RustModuleRunner> = Mutex::new(RustModuleRunner::new());

impl RustModuleRunner {
    const fn new() -> Self {
        Self::NotRunning
    }

    /// Handles bringup of all Rust modules. This occurs after GD C++ modules
    /// have started, but before the legacy stack has initialized.
    /// Must be invoked from the Rust thread after JNI initializes it and passes
    /// in JNI modules.
    pub fn start(
        gatt_callbacks: impl GattCallbacks + Send + 'static,
        att_transport: impl AttTransport + Send + 'static,
        on_started: impl FnOnce() + Send + 'static,
    ) {
        let mut runner = GLOBAL_MODULE_RUNNER.lock().unwrap();

        if let Self::Running { .. } = &*runner {
            panic!("Already running");
        }

        let (tx, rx) = mpsc::unbounded_channel();
        let thread = std::thread::spawn(move || {
            RustModuleRunner::run(Rc::new(gatt_callbacks), Rc::new(att_transport), on_started, rx)
        });

        *runner = Self::Running { thread, tx };
    }

    /// Externally stop the global runner.
    pub fn stop() {
        match std::mem::replace(&mut *GLOBAL_MODULE_RUNNER.lock().unwrap(), Self::NotRunning) {
            Self::NotRunning => warn!("Already not running"),
            Self::Running { thread, tx } => {
                // Dropping the send end of the channel should cause the runner to stop.
                std::mem::drop(tx);

                // Wait for the thread to terminate.
                let _ = thread.join();
            }
            Self::DisabledInTest => {}
        }
    }

    pub fn set_disabled_in_test() {
        let mut runner = GLOBAL_MODULE_RUNNER.lock().unwrap();
        match &*runner {
            RustModuleRunner::NotRunning => *runner = Self::DisabledInTest,
            _ => warn!("Unexpected state {:?}", &*runner),
        }
    }

    fn run(
        gatt_callbacks: Rc<dyn GattCallbacks>,
        att_transport: Rc<dyn AttTransport>,
        on_started: impl FnOnce(),
        mut rx: mpsc::UnboundedReceiver<BoxedMainThreadCallback>,
    ) {
        info!("starting Rust modules");

        let rt = Builder::new_current_thread()
            .enable_all()
            .build()
            .expect("failed to start tokio runtime");
        let local = LocalSet::new();

        // Setup FFI and C++ modules
        let arbiter = gatt::arbiter::initialize_arbiter();

        // Now enter the runtime
        local.block_on(&rt, async move {
            // Then follow the pure-Rust modules
            let gatt_incoming_callbacks =
                Rc::new(gatt::callbacks::CallbackTransactionManager::new(gatt_callbacks.clone()));
            let gatt_module = &mut gatt::server::GattModule::new(att_transport.clone(), arbiter);

            // All modules that are visible from incoming JNI / top-level interfaces should
            // be exposed here
            let mut modules = ModuleViews {
                gatt_outgoing_callbacks: gatt_callbacks,
                gatt_incoming_callbacks,
                gatt_module,
            };

            // notify upper layer that we are ready to receive messages
            on_started();

            // This is the core event loop that serializes incoming requests into the Rust
            // thread do_in_rust_thread lets us post into here from foreign
            // threads
            info!("starting event loop");
            while let Some(f) = rx.recv().await {
                f(&mut modules);
            }
        });

        info!("RustModuleRunner has stopped, shutting down executor thread");

        gatt::arbiter::clean_arbiter();
    }

    #[allow(dead_code)]
    fn send(&self, f: BoxedMainThreadCallback) -> Result<(), (String, BoxedMainThreadCallback)> {
        match self {
            Self::Running { tx, .. } => tx.send(f).map_err(|e| ("Failed to send".to_string(), e.0)),
            _ => Err((format!("Bad state {self:?}"), f)),
        }
    }
}

type BoxedMainThreadCallback = Box<dyn for<'a> FnOnce(&'a mut ModuleViews) + Send + 'static>;

/// Posts a callback to the Rust thread and gives it access to public Rust
/// modules, used from JNI.
///
/// Do not call this from Rust modules / the Rust thread! Instead, Rust modules should receive
/// references to their dependent modules at startup. If passing callbacks into C++, don't use this
/// method either - instead, acquire a clone of RustModule's `tx` when the callback is created. This
/// ensures that there never are "invalid" callbacks that may still work depending on when the
/// GLOBAL_MODULE_REGISTRY is initialized.
pub fn do_in_rust_thread<F>(f: F)
where
    F: for<'a> FnOnce(&'a mut ModuleViews) + Send + 'static,
{
    if let Err((s, _f)) = GLOBAL_MODULE_RUNNER.lock().expect("lock not poisoned").send(Box::new(f))
    {
        panic!("Rust call failed: {s}");
    }
}
