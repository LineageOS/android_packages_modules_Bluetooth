//! FFI interfaces for the GATT module. Some structs are exported so that
//! core::init can instantiate and pass them into the main loop.

use crate::packets::att::{self, AttErrorCode};

use super::arbiter::RegisteredArbiter;
use super::callbacks::{CallbackTransactionManager, GattCallbacks, GattCallbacksImpl};
use super::channel::AttTransport;
use super::ids::{AdvertiserId, AttHandle, ConnectionId, ServerId, TransactionId, TransportIndex};
use super::server::gatt_database::{
    AttPermissions, GattCharacteristicWithHandle, GattDescriptorWithHandle, GattServiceWithHandle,
};
use super::server::isolation_manager::IsolationManager;
use super::server::GattModule;

use pdl_runtime::{EncodeError, Packet};
use std::iter::Peekable;
use std::rc::Rc;
use std::sync::{Arc, Mutex};

use anyhow::{bail, Result};
use cxx::UniquePtr;
use log::{error, info, trace, warn};
use tokio::runtime::Builder;
use tokio::sync::mpsc;
use tokio::task::{spawn_local, LocalSet};

pub use inner::*;

#[cxx::bridge]
#[allow(clippy::needless_lifetimes)]
#[allow(clippy::needless_maybe_sized)]
#[allow(clippy::too_many_arguments)]
#[allow(missing_docs)]
#[allow(unsafe_op_in_unsafe_fn)]
#[allow(unused_attributes)]
mod inner {
    impl UniquePtr<GattServerCallbacks> {}

    #[namespace = "bluetooth"]
    extern "C++" {
        include!("bluetooth/types/uuid.h");
        /// A C++ UUID.
        type Uuid = crate::core::uuid::Uuid;
    }

    /// The GATT entity backing the value of a user-controlled
    /// attribute
    #[derive(Debug)]
    #[namespace = "bluetooth::gatt"]
    enum AttributeBackingType {
        /// A GATT characteristic
        #[cxx_name = "CHARACTERISTIC"]
        Characteristic = 0u32,
        /// A GATT descriptor
        #[cxx_name = "DESCRIPTOR"]
        Descriptor = 1u32,
    }

    #[namespace = "bluetooth::gatt"]
    unsafe extern "C++" {
        include!("src/gatt/ffi/gatt_shim.h");
        type AttributeBackingType;

        /// This contains the callbacks from Rust into C++ JNI needed for GATT
        type GattServerCallbacks;

        /// This callback is invoked when reading - the client
        /// must reply using SendResponse
        #[cxx_name = "OnServerRead"]
        fn on_server_read(
            self: &GattServerCallbacks,
            conn_id: u16,
            trans_id: u32,
            attr_handle: u16,
            attr_type: AttributeBackingType,
            offset: u32,
            is_long: bool,
        );

        /// This callback is invoked when writing - the client
        /// must reply using SendResponse
        #[cxx_name = "OnServerWrite"]
        fn on_server_write(
            self: &GattServerCallbacks,
            conn_id: u16,
            trans_id: u32,
            attr_handle: u16,
            attr_type: AttributeBackingType,
            offset: u32,
            need_response: bool,
            is_prepare: bool,
            value: &[u8],
        );

        /// This callback is invoked when executing / cancelling a write
        #[cxx_name = "OnExecute"]
        fn on_execute(self: &GattServerCallbacks, conn_id: u16, trans_id: u32, execute: bool);

        /// This callback is invoked when an indication has been sent and the
        /// peer device has confirmed it, or if some error occurred.
        #[cxx_name = "OnIndicationSentConfirmation"]
        fn on_indication_sent_confirmation(self: &GattServerCallbacks, conn_id: u16, status: i32);
    }

    /// The type of GATT record supplied over FFI
    #[derive(Debug)]
    #[namespace = "bluetooth::gatt"]
    enum GattRecordType {
        PrimaryService,
        SecondaryService,
        IncludedService,
        Characteristic,
        Descriptor,
    }

    /// An entry in a service definition received from JNI. See GattRecordType
    /// for possible types.
    #[namespace = "bluetooth::gatt"]
    struct GattRecord {
        uuid: Uuid,
        record_type: GattRecordType,
        attribute_handle: u16,

        properties: u8,
        extended_properties: u16,

        permissions: u16,
    }

    #[namespace = "bluetooth::shim::arbiter"]
    unsafe extern "C++" {
        type AclArbiter = crate::gatt::arbiter::ffi::AclArbiter;

        /// Send an outgoing packet on the specified tcb_idx
        fn SendPacketToPeer(tcb_idx: u8, packet: Vec<u8>);
    }

    #[namespace = "bluetooth::gatt"]
    extern "Rust" {
        include!("stack/arbiter/acl_arbiter.h");

        type PrivateGattServerManager;

        #[cxx_name = "NewPrivateGattServerManager"]
        fn new_private_gatt_server_manager(
            gatt_server_callbacks: UniquePtr<GattServerCallbacks>,
            acl_arbiter: &'static AclArbiter,
        ) -> Box<PrivateGattServerManager>;

        // service management
        #[cxx_name = "OpenServer"]
        fn open_server(self: &PrivateGattServerManager, server_id: u8);

        #[cxx_name = "CloseServer"]
        fn close_server(self: &PrivateGattServerManager, server_id: u8);

        #[cxx_name = "AddService"]
        fn add_service(
            self: &PrivateGattServerManager,
            server_id: u8,
            service_records: Vec<GattRecord>,
        );

        #[cxx_name = "RemoveService"]
        fn remove_service(self: &PrivateGattServerManager, server_id: u8, service_handle: u16);

        // att operations
        #[cxx_name = "SendResponse"]
        fn send_response(
            self: &PrivateGattServerManager,
            server_id: u8,
            conn_id: u16,
            trans_id: u32,
            status: u8,
            value: &[u8],
        );

        #[cxx_name = "SendIndication"]
        fn send_indication(
            self: &PrivateGattServerManager,
            _server_id: u8,
            handle: u16,
            conn_id: u16,
            value: &[u8],
        );

        // connection
        #[cxx_name = "IsConnectionIsolated"]
        fn is_connection_isolated(self: &PrivateGattServerManager, conn_id: u16) -> bool;

        // arbitration
        #[cxx_name = "AssociateServerWithAdvertiser"]
        fn associate_server_with_advertiser(
            self: &PrivateGattServerManager,
            server_id: u8,
            advertiser_id: u8,
        );

        #[cxx_name = "ClearAdvertiser"]
        fn clear_advertiser(self: &PrivateGattServerManager, advertiser_id: u8);
    }
}

// SAFETY: Safe because it is just a wrapper (implemented in C++) around a reference to the C++
// callbacks, and it is safe to call any of the callbacks from any thread.
unsafe impl Send for GattServerCallbacks {}

/// Implementation of AttTransport wrapping the corresponding C++ method
struct AttTransportImpl();

impl AttTransport for AttTransportImpl {
    fn send_packet(&self, tcb_idx: TransportIndex, packet: att::Att) -> Result<(), EncodeError> {
        SendPacketToPeer(tcb_idx.0, packet.encode_to_vec()?);
        Ok(())
    }
}

/// The ModuleViews lets us access all publicly accessible Rust modules from
/// Java / C++ while the stack is running. If a module should not be exposed
/// outside of Rust GD, there is no need to include it here.
pub struct ModuleViews<'a> {
    /// Lets us call out into C++
    pub gatt_outgoing_callbacks: Rc<dyn GattCallbacks>,
    /// Receives synchronous callbacks from JNI
    pub gatt_incoming_callbacks: Rc<CallbackTransactionManager>,
    /// Proxies calls into GATT server
    pub gatt_module: &'a mut GattModule,
}

type BoxedMainThreadCallback = Box<dyn for<'a> FnOnce(&'a mut ModuleViews) + Send + 'static>;

/// Handler is used to handle requests that require access ModuleViews.  It is clonable.
#[derive(Clone)]
pub(super) struct Handler(mpsc::UnboundedSender<Option<BoxedMainThreadCallback>>);

impl Handler {
    /// Posts `f` to be executed on a thread with exclusive acecss to a `ModuleViews` instance.
    pub fn handle<F>(&self, f: F)
    where
        F: for<'a> FnOnce(&'a mut ModuleViews) + Send + 'static,
    {
        self.0.send(Some(Box::new(f))).unwrap();
    }
}

/// PrivateGattServerManager is the top-level object that manages private Gatt servers.
pub struct PrivateGattServerManager {
    handler: Handler,
    thread: Option<std::thread::JoinHandle<()>>,
    arbiter: RegisteredArbiter,
}

/// This is the main entry point.  `callbacks` holds the necessary callbacks that are used to notify
/// users of various events.  `acl_arbiter` is used to register our arbiter which allows us to
/// intercept incoming data and connections.
pub fn new_private_gatt_server_manager(
    callbacks: UniquePtr<GattServerCallbacks>,
    acl_arbiter: &'static AclArbiter,
) -> Box<PrivateGattServerManager> {
    crate::utils::init_logging();

    let (tx, rx) = mpsc::unbounded_channel();
    let handler = Handler(tx);
    let arbiter = RegisteredArbiter::new(acl_arbiter, handler.clone());
    let isolation_manager = Arc::clone(arbiter.isolation_manager());
    let thread = std::thread::spawn(move || {
        PrivateGattServerManager::run(Rc::new(GattCallbacksImpl(callbacks)), rx, isolation_manager)
    });
    Box::new(PrivateGattServerManager { handler, thread: Some(thread), arbiter })
}

impl PrivateGattServerManager {
    /// Runs a thread that allows operations to gain exclusive access to an instance of
    /// `ModuleViews`.
    fn run(
        gatt_callbacks: Rc<GattCallbacksImpl>,
        mut rx: mpsc::UnboundedReceiver<Option<BoxedMainThreadCallback>>,
        isolation_manager: Arc<Mutex<IsolationManager>>,
    ) {
        let rt = Builder::new_current_thread()
            .enable_all()
            .build()
            .expect("failed to start tokio runtime");
        let local = LocalSet::new();

        // Now enter the runtime
        local.block_on(&rt, async move {
            // Then follow the pure-Rust modules
            let gatt_incoming_callbacks =
                Rc::new(CallbackTransactionManager::new(gatt_callbacks.clone()));
            let gatt_module = &mut GattModule::new(Rc::new(AttTransportImpl()), isolation_manager);

            // All modules that are visible from incoming JNI / top-level interfaces should
            // be exposed here
            let mut modules = ModuleViews {
                gatt_outgoing_callbacks: gatt_callbacks,
                gatt_incoming_callbacks,
                gatt_module,
            };

            // This is the core event loop that serializes incoming requests.
            info!("starting event loop");
            while let Some(Some(f)) = rx.recv().await {
                f(&mut modules);
            }
        });

        info!("PrivateGattServerManager thread stopped");
    }

    pub fn open_server(&self, server_id: u8) {
        let server_id = ServerId(server_id);

        self.handler.handle(move |modules| {
            if false {
                // Enable to always use private GATT for debugging
                modules
                    .gatt_module
                    .get_isolation_manager()
                    .associate_server_with_advertiser(server_id, AdvertiserId(0))
            }
            if let Err(err) = modules.gatt_module.open_gatt_server(server_id) {
                error!("{err:?}")
            }
        })
    }

    pub fn close_server(&self, server_id: u8) {
        let server_id = ServerId(server_id);

        self.handler.handle(move |modules| {
            if let Err(err) = modules.gatt_module.close_gatt_server(server_id) {
                error!("{err:?}")
            }
        })
    }

    pub fn add_service(&self, server_id: u8, service_records: Vec<GattRecord>) {
        // marshal into the form expected by GattModule
        let server_id = ServerId(server_id);

        match records_to_service(&service_records) {
            Ok(service) => {
                let handle = service.handle;
                self.handler.handle(move |modules| {
                    let ok = modules.gatt_module.register_gatt_service(
                        server_id,
                        service.clone(),
                        modules.gatt_incoming_callbacks.get_datastore(server_id),
                    );
                    match ok {
                        Ok(_) => info!(
                            "successfully registered service for server {server_id:?} with handle \
                             {handle:?} (service={service:?})"
                        ),
                        Err(err) => error!(
                            "failed to register GATT service for server {server_id:?} with error: \
                             {err},  (service={service:?})"
                        ),
                    }
                });
            }
            Err(err) => {
                error!("failed to register service for server {server_id:?}, err: {err:?}")
            }
        }
    }

    pub fn remove_service(&self, server_id: u8, service_handle: u16) {
        let server_id = ServerId(server_id);
        let service_handle = AttHandle(service_handle);
        self.handler.handle(move |modules| {
            let ok = modules.gatt_module.unregister_gatt_service(server_id, service_handle);
            match ok {
                Ok(_) => info!(
                    "successfully removed service {service_handle:?} for server {server_id:?}"
                ),
                Err(err) => error!(
                    "failed to remove GATT service {service_handle:?} for server {server_id:?} \
                     with error: {err}"
                ),
            }
        })
    }

    pub fn send_response(
        &self,
        _server_id: u8,
        conn_id: u16,
        trans_id: u32,
        status: u8,
        value: &[u8],
    ) {
        // TODO(aryarahul): fixup error codes to allow app-specific values (i.e. don't
        // make it an enum in PDL)
        let value = if status == 0 {
            Ok(value.to_vec())
        } else {
            Err(AttErrorCode::try_from(status).unwrap_or(AttErrorCode::UnlikelyError))
        };

        trace!("send_response {conn_id:?}, {trans_id:?}, {:?}", value.as_ref().err());

        self.handler.handle(move |modules| {
            match modules.gatt_incoming_callbacks.send_response(
                ConnectionId(conn_id),
                TransactionId(trans_id),
                value,
            ) {
                Ok(()) => { /* no-op */ }
                Err(err) => warn!("{err:?}"),
            }
        })
    }

    pub fn send_indication(&self, _server_id: u8, handle: u16, conn_id: u16, value: &[u8]) {
        let handle = AttHandle(handle);
        let conn_id = ConnectionId(conn_id);
        let value = value.to_vec();

        trace!("send_indication {handle:?}, {conn_id:?}");

        self.handler.handle(move |modules| {
            let Some(bearer) = modules.gatt_module.get_bearer(conn_id.get_tcb_idx()) else {
                error!("connection {conn_id:?} does not exist");
                return;
            };
            let pending_indication = bearer.send_indication(handle, value);
            let gatt_outgoing_callbacks = modules.gatt_outgoing_callbacks.clone();
            spawn_local(async move {
                gatt_outgoing_callbacks
                    .on_indication_sent_confirmation(conn_id, pending_indication.await);
            });
        })
    }

    pub fn associate_server_with_advertiser(&self, server_id: u8, advertiser_id: u8) {
        let server_id = ServerId(server_id);
        let advertiser_id = AdvertiserId(advertiser_id);
        self.handler.handle(move |modules| {
            modules
                .gatt_module
                .get_isolation_manager()
                .associate_server_with_advertiser(server_id, advertiser_id);
        })
    }

    pub fn clear_advertiser(&self, advertiser_id: u8) {
        let advertiser_id = AdvertiserId(advertiser_id);

        self.handler.handle(move |modules| {
            modules.gatt_module.get_isolation_manager().clear_advertiser(advertiser_id);
        })
    }

    fn is_connection_isolated(&self, conn_id: u16) -> bool {
        self.arbiter.with_arbiter(|arbiter| {
            arbiter.is_connection_isolated(ConnectionId(conn_id).get_tcb_idx())
        })
    }
}

impl Drop for PrivateGattServerManager {
    fn drop(&mut self) {
        if let Some(thread) = self.thread.take() {
            // This should make the thread terminate.
            self.handler.0.send(None).unwrap();
            let _ = thread.join();
        }
    }
}

fn consume_descriptors<'a>(
    records: &mut Peekable<impl Iterator<Item = &'a GattRecord>>,
) -> Vec<GattDescriptorWithHandle> {
    let mut out = vec![];
    while let Some(GattRecord { uuid, attribute_handle, permissions, .. }) =
        records.next_if(|record| record.record_type == GattRecordType::Descriptor)
    {
        let mut att_permissions = AttPermissions::empty();
        att_permissions.set(AttPermissions::READABLE, permissions & 0x01 != 0);
        att_permissions.set(AttPermissions::WRITABLE_WITH_RESPONSE, permissions & 0x10 != 0);

        out.push(GattDescriptorWithHandle {
            handle: AttHandle(*attribute_handle),
            type_: *uuid,
            permissions: att_permissions,
        })
    }
    out
}

fn records_to_service(service_records: &[GattRecord]) -> Result<GattServiceWithHandle> {
    let mut characteristics = vec![];
    let mut service_handle_uuid = None;

    let mut service_records = service_records.iter().peekable();

    while let Some(record) = service_records.next() {
        match record.record_type {
            GattRecordType::PrimaryService => {
                if service_handle_uuid.is_some() {
                    bail!("got service registration but with duplicate primary service! \
                           {service_records:?}"
                        .to_string());
                }
                service_handle_uuid = Some((record.attribute_handle, record.uuid));
            }
            GattRecordType::Characteristic => {
                characteristics.push(GattCharacteristicWithHandle {
                    handle: AttHandle(record.attribute_handle),
                    type_: record.uuid,
                    permissions: AttPermissions::from_bits_truncate(record.properties),
                    descriptors: consume_descriptors(&mut service_records),
                });
            }
            GattRecordType::Descriptor => {
                bail!("Got unexpected descriptor outside of characteristic declaration")
            }
            _ => {
                warn!("ignoring unsupported database entry of type {:?}", record.record_type)
            }
        }
    }

    let Some((handle, uuid)) = service_handle_uuid else {
        bail!(
            "got service registration but with no primary service! {characteristics:?}".to_string()
        )
    };

    Ok(GattServiceWithHandle { handle: AttHandle(handle), type_: uuid, characteristics })
}

#[cfg(test)]
mod test {
    use super::*;

    const SERVICE_HANDLE: AttHandle = AttHandle(1);
    const SERVICE_UUID: Uuid = Uuid::new(0x1234);

    const CHARACTERISTIC_HANDLE: AttHandle = AttHandle(2);
    const CHARACTERISTIC_UUID: Uuid = Uuid::new(0x5678);

    const DESCRIPTOR_UUID: Uuid = Uuid::new(0x4321);
    const ANOTHER_DESCRIPTOR_UUID: Uuid = Uuid::new(0x5432);

    const ANOTHER_CHARACTERISTIC_HANDLE: AttHandle = AttHandle(10);
    const ANOTHER_CHARACTERISTIC_UUID: Uuid = Uuid::new(0x9ABC);

    fn make_service_record(uuid: Uuid, handle: AttHandle) -> GattRecord {
        GattRecord {
            uuid,
            record_type: GattRecordType::PrimaryService,
            attribute_handle: handle.0,
            properties: 0,
            extended_properties: 0,
            permissions: 0,
        }
    }

    fn make_characteristic_record(uuid: Uuid, handle: AttHandle, properties: u8) -> GattRecord {
        GattRecord {
            uuid,
            record_type: GattRecordType::Characteristic,
            attribute_handle: handle.0,
            properties,
            extended_properties: 0,
            permissions: 0,
        }
    }

    fn make_descriptor_record(uuid: Uuid, handle: AttHandle, permissions: u16) -> GattRecord {
        GattRecord {
            uuid,
            record_type: GattRecordType::Descriptor,
            attribute_handle: handle.0,
            properties: 0,
            extended_properties: 0,
            permissions,
        }
    }

    #[test]
    fn test_empty_records() {
        let res = records_to_service(&[]);
        assert!(res.is_err());
    }

    #[test]
    fn test_primary_service() {
        let service =
            records_to_service(&[make_service_record(SERVICE_UUID, SERVICE_HANDLE)]).unwrap();

        assert_eq!(service.handle, SERVICE_HANDLE);
        assert_eq!(service.type_, SERVICE_UUID);
        assert_eq!(service.characteristics.len(), 0);
    }

    #[test]
    fn test_dupe_primary_service() {
        let res = records_to_service(&[
            make_service_record(SERVICE_UUID, SERVICE_HANDLE),
            make_service_record(SERVICE_UUID, SERVICE_HANDLE),
        ]);

        assert!(res.is_err());
    }

    #[test]
    fn test_service_with_single_characteristic() {
        let service = records_to_service(&[
            make_service_record(SERVICE_UUID, SERVICE_HANDLE),
            make_characteristic_record(CHARACTERISTIC_UUID, CHARACTERISTIC_HANDLE, 0),
        ])
        .unwrap();

        assert_eq!(service.handle, SERVICE_HANDLE);
        assert_eq!(service.type_, SERVICE_UUID);

        assert_eq!(service.characteristics.len(), 1);
        assert_eq!(service.characteristics[0].handle, CHARACTERISTIC_HANDLE);
        assert_eq!(service.characteristics[0].type_, CHARACTERISTIC_UUID);
    }

    #[test]
    fn test_multiple_characteristics() {
        let service = records_to_service(&[
            make_service_record(SERVICE_UUID, SERVICE_HANDLE),
            make_characteristic_record(CHARACTERISTIC_UUID, CHARACTERISTIC_HANDLE, 0),
            make_characteristic_record(
                ANOTHER_CHARACTERISTIC_UUID,
                ANOTHER_CHARACTERISTIC_HANDLE,
                0,
            ),
        ])
        .unwrap();

        assert_eq!(service.characteristics.len(), 2);
        assert_eq!(service.characteristics[0].handle, CHARACTERISTIC_HANDLE);
        assert_eq!(service.characteristics[0].type_, CHARACTERISTIC_UUID);
        assert_eq!(service.characteristics[1].handle, ANOTHER_CHARACTERISTIC_HANDLE);
        assert_eq!(service.characteristics[1].type_, ANOTHER_CHARACTERISTIC_UUID);
    }

    #[test]
    fn test_characteristic_readable_property() {
        let service = records_to_service(&[
            make_service_record(SERVICE_UUID, SERVICE_HANDLE),
            make_characteristic_record(CHARACTERISTIC_UUID, CHARACTERISTIC_HANDLE, 0x02),
        ])
        .unwrap();

        assert_eq!(service.characteristics[0].permissions, AttPermissions::READABLE);
    }

    #[test]
    fn test_characteristic_writable_property() {
        let service = records_to_service(&[
            make_service_record(SERVICE_UUID, SERVICE_HANDLE),
            make_characteristic_record(CHARACTERISTIC_UUID, CHARACTERISTIC_HANDLE, 0x08),
        ])
        .unwrap();

        assert_eq!(service.characteristics[0].permissions, AttPermissions::WRITABLE_WITH_RESPONSE);
    }

    #[test]
    fn test_characteristic_readable_and_writable_property() {
        let service = records_to_service(&[
            make_service_record(SERVICE_UUID, SERVICE_HANDLE),
            make_characteristic_record(CHARACTERISTIC_UUID, CHARACTERISTIC_HANDLE, 0x02 | 0x08),
        ])
        .unwrap();

        assert_eq!(
            service.characteristics[0].permissions,
            AttPermissions::READABLE | AttPermissions::WRITABLE_WITH_RESPONSE
        );
    }

    #[test]
    fn test_multiple_descriptors() {
        let service = records_to_service(&[
            make_service_record(SERVICE_UUID, AttHandle(1)),
            make_characteristic_record(CHARACTERISTIC_UUID, AttHandle(2), 0),
            make_descriptor_record(DESCRIPTOR_UUID, AttHandle(3), 0),
            make_descriptor_record(ANOTHER_DESCRIPTOR_UUID, AttHandle(4), 0),
        ])
        .unwrap();

        assert_eq!(service.characteristics[0].descriptors.len(), 2);
        assert_eq!(service.characteristics[0].descriptors[0].handle, AttHandle(3));
        assert_eq!(service.characteristics[0].descriptors[0].type_, DESCRIPTOR_UUID);
        assert_eq!(service.characteristics[0].descriptors[1].handle, AttHandle(4));
        assert_eq!(service.characteristics[0].descriptors[1].type_, ANOTHER_DESCRIPTOR_UUID);
    }

    #[test]
    fn test_descriptor_permissions() {
        let service = records_to_service(&[
            make_service_record(SERVICE_UUID, AttHandle(1)),
            make_characteristic_record(CHARACTERISTIC_UUID, AttHandle(2), 0),
            make_descriptor_record(DESCRIPTOR_UUID, AttHandle(3), 0x01),
            make_descriptor_record(DESCRIPTOR_UUID, AttHandle(4), 0x10),
            make_descriptor_record(DESCRIPTOR_UUID, AttHandle(5), 0x11),
        ])
        .unwrap();

        assert_eq!(service.characteristics[0].descriptors[0].permissions, AttPermissions::READABLE);
        assert_eq!(
            service.characteristics[0].descriptors[1].permissions,
            AttPermissions::WRITABLE_WITH_RESPONSE
        );
        assert_eq!(
            service.characteristics[0].descriptors[2].permissions,
            AttPermissions::READABLE | AttPermissions::WRITABLE_WITH_RESPONSE
        );
    }

    #[test]
    fn test_descriptors_multiple_characteristics() {
        let service = records_to_service(&[
            make_service_record(SERVICE_UUID, AttHandle(1)),
            make_characteristic_record(CHARACTERISTIC_UUID, AttHandle(2), 0),
            make_descriptor_record(DESCRIPTOR_UUID, AttHandle(3), 0),
            make_characteristic_record(CHARACTERISTIC_UUID, AttHandle(4), 0),
            make_descriptor_record(DESCRIPTOR_UUID, AttHandle(5), 0),
        ])
        .unwrap();

        assert_eq!(service.characteristics[0].descriptors.len(), 1);
        assert_eq!(service.characteristics[0].descriptors[0].handle, AttHandle(3));
        assert_eq!(service.characteristics[1].descriptors.len(), 1);
        assert_eq!(service.characteristics[1].descriptors[0].handle, AttHandle(5));
    }

    #[test]
    fn test_unexpected_descriptor() {
        let res = records_to_service(&[
            make_service_record(SERVICE_UUID, AttHandle(1)),
            make_descriptor_record(DESCRIPTOR_UUID, AttHandle(3), 0),
        ]);

        assert!(res.is_err());
    }

    #[test]
    fn test_ignored_records() {
        let records = vec![
            make_service_record(SERVICE_UUID, SERVICE_HANDLE),
            GattRecord {
                uuid: Uuid::new(0),
                record_type: GattRecordType::SecondaryService,
                attribute_handle: 100,
                properties: 0,
                extended_properties: 0,
                permissions: 0,
            },
            GattRecord {
                uuid: Uuid::new(0),
                record_type: GattRecordType::IncludedService,
                attribute_handle: 101,
                properties: 0,
                extended_properties: 0,
                permissions: 0,
            },
        ];
        let service = records_to_service(&records).unwrap();
        assert_eq!(service.handle, SERVICE_HANDLE);
        assert_eq!(service.type_, SERVICE_UUID);
        assert_eq!(service.characteristics.len(), 0);
    }
}
