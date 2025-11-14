use clap::{App, AppSettings, Arg};
use dbus_projection::DisconnectWatcher;
use dbus_tokio::connection;
use futures::future;
use nix::sys::signal;
use std::error::Error;
use std::sync::{Arc, Condvar, Mutex};
use std::time::Duration;
use tokio::runtime::Builder;
use tokio::sync::mpsc::Sender;

use bt_topshim::btif::get_btinterface;
use bt_topshim::topstack;
use btstack::battery_manager::BatteryManager;
use btstack::battery_provider_manager::BatteryProviderManager;
use btstack::battery_service::BatteryService;
use btstack::bluetooth::{Bluetooth, IBluetooth, SigData};
use btstack::bluetooth_admin::BluetoothAdmin;
use btstack::bluetooth_gatt::BluetoothGatt;
use btstack::bluetooth_logging::BluetoothLogging;
use btstack::bluetooth_media::BluetoothMedia;
use btstack::bluetooth_qa::BluetoothQA;
use btstack::dis::DeviceInformation;
use btstack::socket_manager::BluetoothSocketManager;
use btstack::suspend::Suspend;
use btstack::{Message, Stack};

mod dbus_arg;
mod iface_battery_manager;
mod iface_battery_provider_manager;
mod iface_bluetooth;
mod iface_bluetooth_admin;
mod iface_bluetooth_gatt;
mod iface_bluetooth_media;
mod iface_bluetooth_qa;
mod iface_bluetooth_telephony;
mod iface_logging;
mod interface_manager;

const DBUS_SERVICE_NAME: &str = "org.chromium.bluetooth";
const ADMIN_SETTINGS_FILE_PATH: &str = "/var/lib/bluetooth/admin_policy.json";
// Time to wait for API unregistration in DBus
const API_DISABLE_TIMEOUT_MS: Duration = Duration::from_millis(100);
// The maximum ACL disconnect timeout is 3.5s defined by BTA_DM_DISABLE_TIMER_MS
// and BTA_DM_DISABLE_TIMER_RETRIAL_MS
const STACK_TURN_OFF_TIMEOUT_MS: Duration = Duration::from_millis(4000);
// Time bt_stack_manager waits for cleanup
const STACK_CLEANUP_TIMEOUT_MS: Duration = Duration::from_millis(11000);
// Time bt_stack_manager waits for cleanup profiles
const STACK_CLEANUP_PROFILES_TIMEOUT_MS: Duration = Duration::from_millis(100);
// Extra time to wait before terminating the process
const EXTRA_WAIT_BEFORE_KILL_MS: Duration = Duration::from_millis(1000);

const INIT_LOGGING_MAX_RETRY: u8 = 3;

/// Runs the Bluetooth daemon serving D-Bus IPC.
fn main() -> Result<(), Box<dyn Error>> {
    let matches = App::new("Bluetooth Adapter Daemon")
        .setting(AppSettings::TrailingVarArg)
        .arg(
            Arg::with_name("hci")
                .long("hci")
                .value_name("HCI")
                .takes_value(true)
                .help("The HCI index"),
        )
        .arg(
            Arg::with_name("index")
                .long("index")
                .value_name("INDEX")
                .takes_value(true)
                .help("The Virtual index"),
        )
        .arg(Arg::with_name("debug").long("debug").short("d").help("Enables debug level logs"))
        .arg(
            Arg::with_name("verbose-debug")
                .long("verbose-debug")
                .short("v")
                .help("Enables VERBOSE and additional tags for debug logging. Use with --debug."),
        )
        .arg(
            Arg::with_name("log-output")
                .long("log-output")
                .takes_value(true)
                .possible_values(&["syslog", "stderr"])
                .default_value("syslog")
                .help("Select log output"),
        )
        .get_matches();

    let is_debug = matches.is_present("debug");
    let is_verbose_debug = matches.is_present("verbose-debug");
    let log_output = matches.value_of("log-output").unwrap_or("syslog");

    let virt_index = matches.value_of("index").map_or(0, |idx| idx.parse::<i32>().unwrap_or(0));
    let hci_index = matches.value_of("hci").map_or(0, |idx| idx.parse::<i32>().unwrap_or(0));

    let logging = Arc::new(Mutex::new(Box::new(BluetoothLogging::new(
        is_debug,
        is_verbose_debug,
        log_output,
    ))));
    // TODO(b/307171804): Investigate why connecting to unix syslog might fail.
    // Retry it a few times. Ignore the failure if fails too many times.
    for _ in 0..INIT_LOGGING_MAX_RETRY {
        match logging.lock().unwrap().initialize() {
            Ok(_) => break,
            Err(_) => continue,
        }
    }

    let (tx, rx) = Stack::create_channel();
    let (api_tx, api_rx) = interface_manager::InterfaceManager::create_channel();
    let sig_notifier = Arc::new(SigData {
        enabled: Mutex::new(false),
        enabled_notify: Condvar::new(),
        thread_attached: Mutex::new(false),
        thread_notify: Condvar::new(),
        api_enabled: Mutex::new(false),
        api_notify: Condvar::new(),
    });

    // This needs to be built before any |topstack::get_runtime()| call!
    let bt_sock_mgr_runtime = Arc::new(
        Builder::new_multi_thread()
            .worker_threads(1)
            .max_blocking_threads(1)
            .enable_all()
            .build()
            .expect("Failed to make socket runtime."),
    );

    topstack::get_runtime().block_on(async {
        // Connect to D-Bus system bus.
        let (resource, conn) = connection::new_system_sync()?;

        // The `resource` is a task that should be spawned onto a tokio compatible
        // reactor ASAP. If the resource ever finishes, we lost connection to D-Bus.
        let conn_join_handle = tokio::spawn(async {
            let err = resource.await;
            panic!("Lost connection to D-Bus: {}", err);
        });

        // Request a service name and quit if not able to.
        conn.request_name(DBUS_SERVICE_NAME, false, true, false).await?;

        // Install SIGTERM handler so that we can properly shutdown
        *SIG_DATA.lock().unwrap() = Some((tx.clone(), sig_notifier.clone()));
        let sig_action_term = signal::SigAction::new(
            signal::SigHandler::Handler(handle_sigterm),
            signal::SaFlags::empty(),
            signal::SigSet::empty(),
        );
        let sig_action_usr1 = signal::SigAction::new(
            signal::SigHandler::Handler(handle_sigusr1),
            signal::SaFlags::empty(),
            signal::SigSet::empty(),
        );
        unsafe {
            signal::sigaction(signal::SIGTERM, &sig_action_term).unwrap();
            signal::sigaction(signal::SIGUSR1, &sig_action_usr1).unwrap();
        }

        // Construct btstack profiles.
        let intf = Arc::new(Mutex::new(get_btinterface()));
        let bluetooth = Arc::new(Mutex::new(Box::new(Bluetooth::new(
            virt_index,
            hci_index,
            tx.clone(),
            api_tx.clone(),
            sig_notifier.clone(),
            intf.clone(),
        ))));
        let bluetooth_qa = Arc::new(Mutex::new(Box::new(BluetoothQA::new(tx.clone()))));
        let battery_provider_manager =
            Arc::new(Mutex::new(Box::new(BatteryProviderManager::new(tx.clone()))));

        bluetooth.lock().unwrap().init(hci_index);
        bluetooth.lock().unwrap().enable();

        // These constructions require |intf| to be already init-ed.
        let bt_sock_mgr = Arc::new(Mutex::new(Box::new(BluetoothSocketManager::new(
            tx.clone(),
            bt_sock_mgr_runtime,
            intf.clone(),
            bluetooth.clone(),
        ))));
        let bluetooth_media = Arc::new(Mutex::new(Box::new(BluetoothMedia::new(
            tx.clone(),
            api_tx.clone(),
            intf.clone(),
            bluetooth.clone(),
            battery_provider_manager.clone(),
        ))));
        let bluetooth_gatt =
            Arc::new(Mutex::new(Box::new(BluetoothGatt::new(intf.clone(), tx.clone()))));

        // These constructions don't need |intf| to be init-ed, but just depend on those who need.
        let bluetooth_admin = Arc::new(Mutex::new(Box::new(BluetoothAdmin::new(
            String::from(ADMIN_SETTINGS_FILE_PATH),
            tx.clone(),
            bluetooth.clone(),
            bluetooth_media.clone(),
            bt_sock_mgr.clone(),
        ))));
        let suspend = Arc::new(Mutex::new(Box::new(Suspend::new(
            bluetooth.clone(),
            intf.clone(),
            bluetooth_gatt.clone(),
            bluetooth_media.clone(),
            tx.clone(),
        ))));
        let battery_service = Arc::new(Mutex::new(Box::new(BatteryService::new(
            bluetooth_gatt.clone(),
            battery_provider_manager.clone(),
            tx.clone(),
            api_tx.clone(),
        ))));
        let battery_manager = Arc::new(Mutex::new(Box::new(BatteryManager::new(
            battery_provider_manager.clone(),
            tx.clone(),
        ))));
        let dis = Arc::new(Mutex::new(Box::new(DeviceInformation::new(
            bluetooth_gatt.clone(),
            tx.clone(),
        ))));

        // Run the stack main dispatch loop.
        topstack::get_runtime().spawn(Stack::dispatch(
            rx,
            tx.clone(),
            api_tx.clone(),
            bluetooth.clone(),
            bluetooth_gatt.clone(),
            battery_service.clone(),
            battery_manager.clone(),
            battery_provider_manager.clone(),
            bluetooth_media.clone(),
            suspend.clone(),
            bt_sock_mgr.clone(),
            bluetooth_admin.clone(),
            dis.clone(),
            bluetooth_qa.clone(),
        ));

        // Set up the disconnect watcher to monitor client disconnects.
        let mut disconnect_watcher = DisconnectWatcher::new();
        disconnect_watcher.setup_watch(conn.clone()).await;
        let disconnect_watcher = Arc::new(Mutex::new(disconnect_watcher));

        tokio::spawn(interface_manager::InterfaceManager::dispatch(
            api_rx,
            virt_index,
            conn,
            conn_join_handle,
            disconnect_watcher.clone(),
            sig_notifier.clone(),
            bluetooth.clone(),
            bluetooth_admin.clone(),
            bluetooth_gatt.clone(),
            battery_manager.clone(),
            battery_provider_manager.clone(),
            bluetooth_media.clone(),
            bluetooth_qa.clone(),
            bt_sock_mgr.clone(),
            suspend.clone(),
            logging.clone(),
        ));

        // Serve clients forever.
        future::pending::<()>().await;
        unreachable!()
    })
}

/// Data needed for signal handling.
static SIG_DATA: Mutex<Option<(Sender<Message>, Arc<SigData>)>> = Mutex::new(None);

/// Try to cleanup the whole stack. Returns whether to clean up.
extern "C" fn try_cleanup_stack(abort: bool) -> bool {
    let lock = SIG_DATA.try_lock();

    // If SIG_DATA is locked, it is likely the cleanup procedure is ongoing. No
    // need to do anything here.
    if lock.is_err() {
        return false;
    }

    if let Some((tx, notifier)) = lock.unwrap().as_ref() {
        log::info!("Cleanup stack: disabling the adapter!");

        // Remove the API first to prevent clients calling while shutting down.
        let guard = notifier.api_enabled.lock().unwrap();
        if *guard {
            let txl = tx.clone();
            topstack::get_runtime().spawn(async move {
                // Remove the API first to prevent clients calling while shutting down.
                let _ = txl.send(Message::InterfaceShutdown).await;
            });
            log::info!(
                "Cleanup stack: Waiting for API shutdown to complete for {:?}",
                API_DISABLE_TIMEOUT_MS
            );
            let _ = notifier.api_notify.wait_timeout(guard, API_DISABLE_TIMEOUT_MS);
        }

        let guard = notifier.enabled.lock().unwrap();
        if *guard {
            let txl = tx.clone();
            topstack::get_runtime().spawn(async move {
                let _ = txl.send(Message::AdapterShutdown(abort)).await;
            });
            log::info!(
                "Cleanup stack: Waiting for stack to turn off for {:?}",
                STACK_TURN_OFF_TIMEOUT_MS
            );
            let _ = notifier.enabled_notify.wait_timeout(guard, STACK_TURN_OFF_TIMEOUT_MS);
        }

        let guard = notifier.thread_attached.lock().unwrap();
        if *guard {
            let txl = tx.clone();
            topstack::get_runtime().spawn(async move {
                // Clean up the profiles first as some of them might require main thread to clean up.
                let _ = txl.send(Message::CleanupProfiles).await;
                // Currently there is no good way to know when the profile is cleaned.
                // Simply add a small delay here.
                tokio::time::sleep(STACK_CLEANUP_PROFILES_TIMEOUT_MS).await;
                // Send the cleanup message to clean up the main thread.
                let _ = txl.send(Message::Cleanup).await;
            });
            log::info!(
                "Cleanup stack: Waiting for libbluetooth stack to clean up for {:?}",
                STACK_CLEANUP_TIMEOUT_MS
            );
            let _ = notifier.thread_notify.wait_timeout(guard, STACK_CLEANUP_TIMEOUT_MS);
        }

        // Extra delay to give the rest of the cleanup processes some time to finish after
        // finishing btif cleanup.
        std::thread::sleep(EXTRA_WAIT_BEFORE_KILL_MS);
    }
    return true;
}

extern "C" fn handle_sigterm(_signum: i32) {
    log::info!("SIGTERM received");
    if !try_cleanup_stack(false) {
        log::info!("Skipped to handle SIGTERM");
        return;
    }
    log::info!("SIGTERM completed");
    std::process::exit(0);
}

/// Used to indicate controller needs reset
extern "C" fn handle_sigusr1(_signum: i32) {
    log::info!("SIGUSR1 received");
    if !try_cleanup_stack(true) {
        log::info!("Skipped to handle SIGUSR1");
        return;
    }
    log::info!("SIGUSR1 completed");
    std::process::exit(0);
}
