//! This module is a simple GATT server that shares the ATT channel with the
//! existing C++ GATT client.

pub mod att_client;
mod att_database;
pub mod att_server_bearer;
pub mod gatt_database;
mod indication_handler;
mod request_handler;
pub mod services;
mod transactions;

mod command_handler;
pub mod isolation_manager;
#[cfg(test)]
mod test;

use std::collections::HashMap;
use std::rc::Rc;
use std::sync::{Arc, Mutex, MutexGuard};

use crate::core::shared_box::SharedBox;
use crate::gatt::server::att_client::AttClient;
use crate::gatt::server::gatt_database::GattDatabase;

use self::super::ids::ServerId;
use self::att_server_bearer::AttServerBearer;
use self::gatt_database::GattServiceWithHandle;
use self::isolation_manager::IsolationManager;
use self::services::register_builtin_services;

use super::callbacks::RawGattDatastore;
use super::channel::AttTransport;
use super::ids::{AdvertiserId, AttHandle, TransportIndex};
use anyhow::{anyhow, bail, Result};
use log::info;

pub use indication_handler::IndicationError;

type AttClients = HashMap<TransportIndex, SharedBox<AttClient>>;

#[allow(missing_docs)]
pub struct GattModule {
    clients: AttClients,
    databases: HashMap<ServerId, SharedBox<GattDatabase>>,
    transport: Rc<dyn AttTransport>,
    // NOTE: this is logically owned by the GattModule. We share it behind a Mutex just so we
    // can use it as part of the Arbiter. Once the Arbiter is removed, this should be owned
    // fully by the GattModule.
    isolation_manager: Arc<Mutex<IsolationManager>>,
}

impl GattModule {
    /// Constructor.
    pub fn new(
        transport: Rc<dyn AttTransport>,
        isolation_manager: Arc<Mutex<IsolationManager>>,
    ) -> Self {
        Self { clients: AttClients::new(), databases: HashMap::new(), transport, isolation_manager }
    }

    /// Handle LE link connect
    pub fn on_le_connect(
        &mut self,
        tcb_idx: TransportIndex,
        advertiser_id: Option<AdvertiserId>,
    ) -> Result<()> {
        info!("connected on tcb_idx {tcb_idx:?}");
        self.isolation_manager.lock().unwrap().on_le_connect(tcb_idx, advertiser_id);

        let Some(server_id) = self.isolation_manager.lock().unwrap().get_server_id(tcb_idx) else {
            bail!("non-isolated servers are not yet supported (b/274945531)")
        };
        let database = self.databases.get(&server_id);
        let Some(database) = database else {
            bail!("got connection to {server_id:?} but this server does not exist!");
        };

        let transport = self.transport.clone();
        self.clients.insert(
            tcb_idx,
            AttClient::new_client_and_bearer(
                tcb_idx,
                move |packet| transport.send_packet(tcb_idx, packet),
                database,
            ),
        );
        Ok(())
    }

    /// Handle an LE link disconnect
    pub fn on_le_disconnect(&mut self, tcb_idx: TransportIndex) -> Result<()> {
        info!("disconnected conn_id {tcb_idx:?}");
        self.isolation_manager.lock().unwrap().on_le_disconnect(tcb_idx);
        if self.clients.remove(&tcb_idx).is_none() {
            bail!("got disconnection from {tcb_idx:?} but bearer does not exist");
        };
        Ok(())
    }

    /// Register a new GATT service on a given server
    pub fn register_gatt_service(
        &mut self,
        server_id: ServerId,
        service: GattServiceWithHandle,
        datastore: impl RawGattDatastore + 'static,
    ) -> Result<()> {
        self.databases
            .get(&server_id)
            .ok_or_else(|| anyhow!("server {server_id:?} not opened"))?
            .add_service_with_handles(service, Rc::new(datastore))
    }

    /// Unregister an existing GATT service on a given server
    pub fn unregister_gatt_service(
        &mut self,
        server_id: ServerId,
        service_handle: AttHandle,
    ) -> Result<()> {
        self.databases
            .get(&server_id)
            .ok_or_else(|| anyhow!("server {server_id:?} not opened"))?
            .remove_service_at_handle(service_handle)
    }

    /// Open a GATT server
    pub fn open_gatt_server(&mut self, server_id: ServerId) -> Result<()> {
        let mut db = GattDatabase::new();
        register_builtin_services(&mut db)?;
        let old = self.databases.insert(server_id, db.into());
        if old.is_some() {
            bail!("GATT server {server_id:?} already exists but was re-opened, clobbering old value...")
        }
        Ok(())
    }

    /// Close a GATT server
    pub fn close_gatt_server(&mut self, server_id: ServerId) -> Result<()> {
        let old = self.databases.remove(&server_id);
        if old.is_none() {
            bail!("GATT server {server_id:?} did not exist")
        };

        if true {
            // Disable to always use private gatt for debugging
            self.isolation_manager.lock().unwrap().clear_server(server_id);
        }

        Ok(())
    }

    /// Get an ATT bearer for a particular connection
    pub fn get_bearer(&self, tcb_idx: TransportIndex) -> Option<&SharedBox<AttServerBearer>> {
        self.clients.get(&tcb_idx).map(|c| c.bearer())
    }

    /// Get the IsolationManager to manage associations between servers + advertisers
    pub fn get_isolation_manager(&mut self) -> MutexGuard<'_, IsolationManager> {
        self.isolation_manager.lock().unwrap()
    }
}

#[cfg(test)]
mod tests {
    use pdl_runtime::Packet;
    use std::rc::Rc;
    use std::sync::{Arc, Mutex};

    use crate::core::uuid::Uuid;
    use crate::gatt::ffi::AttributeBackingType;
    use crate::gatt::ids::{AdvertiserId, AttHandle, ServerId, TransportIndex};
    use crate::gatt::mocks::mock_datastore::{MockDatastore, MockDatastoreEvents};
    use crate::gatt::mocks::mock_transport::MockAttTransport;
    use crate::gatt::server::gatt_database::{
        AttPermissions, GattCharacteristicWithHandle, GattDescriptorWithHandle,
        GattServiceWithHandle, CHARACTERISTIC_UUID, PRIMARY_SERVICE_DECLARATION_UUID,
    };
    use crate::gatt::server::isolation_manager::IsolationManager;
    use crate::gatt::server::services::gap::{DEVICE_NAME_PREFIX, DEVICE_NAME_UUID};
    use crate::gatt::server::services::gatt::{
        CLIENT_CHARACTERISTIC_CONFIGURATION_UUID, GATT_SERVICE_UUID, SERVICE_CHANGE_UUID,
    };
    use crate::gatt::server::{GattModule, IndicationError};
    use crate::gatt::{self};
    use crate::packets::att::{self, AttErrorCode};
    use crate::utils::task::block_on_locally;

    use tokio::sync::mpsc::error::TryRecvError;
    use tokio::sync::mpsc::UnboundedReceiver;
    use tokio::task::spawn_local;

    const TCB_IDX: TransportIndex = TransportIndex(1);
    const SERVER_ID: ServerId = ServerId(2);
    const ADVERTISER_ID: AdvertiserId = AdvertiserId(3);

    const ANOTHER_TCB_IDX: TransportIndex = TransportIndex(2);
    const ANOTHER_SERVER_ID: ServerId = ServerId(3);
    const ANOTHER_ADVERTISER_ID: AdvertiserId = AdvertiserId(4);

    const SERVICE_HANDLE: AttHandle = AttHandle(6);
    const CHARACTERISTIC_HANDLE: AttHandle = AttHandle(8);
    const DESCRIPTOR_HANDLE: AttHandle = AttHandle(9);

    const SERVICE_TYPE: Uuid = Uuid::new(0x0102);
    const CHARACTERISTIC_TYPE: Uuid = Uuid::new(0x0103);
    const DESCRIPTOR_TYPE: Uuid = Uuid::new(0x0104);

    const DATA: [u8; 4] = [1, 2, 3, 4];
    const ANOTHER_DATA: [u8; 4] = [5, 6, 7, 8];

    fn start_gatt_module(
    ) -> (gatt::server::GattModule, UnboundedReceiver<(TransportIndex, att::Att)>) {
        let (transport, transport_rx) = MockAttTransport::new();
        let arbiter = IsolationManager::new();
        let gatt = GattModule::new(Rc::new(transport), Arc::new(Mutex::new(arbiter)));

        (gatt, transport_rx)
    }

    fn create_server_and_open_connection(
        gatt: &mut GattModule,
    ) -> UnboundedReceiver<MockDatastoreEvents> {
        gatt.open_gatt_server(SERVER_ID).unwrap();
        let (datastore, data_rx) = MockDatastore::new();
        gatt.register_gatt_service(
            SERVER_ID,
            GattServiceWithHandle {
                handle: SERVICE_HANDLE,
                type_: SERVICE_TYPE,
                characteristics: vec![GattCharacteristicWithHandle {
                    handle: CHARACTERISTIC_HANDLE,
                    type_: CHARACTERISTIC_TYPE,
                    permissions: AttPermissions::READABLE
                        | AttPermissions::WRITABLE_WITH_RESPONSE
                        | AttPermissions::INDICATE,
                    descriptors: vec![GattDescriptorWithHandle {
                        handle: DESCRIPTOR_HANDLE,
                        type_: DESCRIPTOR_TYPE,
                        permissions: AttPermissions::READABLE
                            | AttPermissions::WRITABLE_WITH_RESPONSE,
                    }],
                }],
            },
            datastore,
        )
        .unwrap();
        gatt.get_isolation_manager().associate_server_with_advertiser(SERVER_ID, ADVERTISER_ID);
        gatt.on_le_connect(TCB_IDX, Some(ADVERTISER_ID)).unwrap();
        data_rx
    }

    #[test]
    fn test_service_read() {
        block_on_locally(async move {
            // arrange
            let (mut gatt, mut transport_rx) = start_gatt_module();

            create_server_and_open_connection(&mut gatt);

            // act
            gatt.get_bearer(TCB_IDX).unwrap().handle_packet(
                att::AttReadRequest { attribute_handle: SERVICE_HANDLE.into() }.try_into().unwrap(),
            );
            let (tcb_idx, resp) = transport_rx.recv().await.unwrap();

            // assert
            assert_eq!(tcb_idx, TCB_IDX);
            assert_eq!(
                Ok(resp),
                att::AttReadResponse {
                    value: att::GattServiceDeclarationValue { uuid: SERVICE_TYPE.into() }
                        .encode_to_vec()
                        .unwrap(),
                }
                .try_into()
            );
        })
    }

    #[test]
    fn test_server_closed_while_connected() {
        block_on_locally(async move {
            // arrange: set up a connection to a closed server
            let (mut gatt, mut transport_rx) = start_gatt_module();

            // open a server and connect
            create_server_and_open_connection(&mut gatt);
            gatt.close_gatt_server(SERVER_ID).unwrap();

            // act: read from the closed server
            gatt.get_bearer(TCB_IDX).unwrap().handle_packet(
                att::AttReadRequest { attribute_handle: SERVICE_HANDLE.into() }.try_into().unwrap(),
            );
            let (_, resp) = transport_rx.recv().await.unwrap();

            // assert that the read failed, but that a response was provided
            assert_eq!(
                Ok(resp),
                att::AttErrorResponse {
                    opcode_in_error: att::AttOpcode::ReadRequest,
                    handle_in_error: SERVICE_HANDLE.into(),
                    error_code: AttErrorCode::InvalidHandle
                }
                .try_into()
            )
        });
    }

    #[test]
    fn test_characteristic_read() {
        block_on_locally(async move {
            // arrange
            let (mut gatt, mut transport_rx) = start_gatt_module();
            let mut data_rx = create_server_and_open_connection(&mut gatt);

            // act
            gatt.get_bearer(TCB_IDX).unwrap().handle_packet(
                att::AttReadRequest { attribute_handle: CHARACTERISTIC_HANDLE.into() }
                    .try_into()
                    .unwrap(),
            );
            let tx = if let MockDatastoreEvents::Read(
                TCB_IDX,
                CHARACTERISTIC_HANDLE,
                AttributeBackingType::Characteristic,
                tx,
            ) = data_rx.recv().await.unwrap()
            {
                tx
            } else {
                unreachable!()
            };
            tx.send(Ok(DATA.to_vec())).unwrap();
            let (tcb_idx, resp) = transport_rx.recv().await.unwrap();

            // assert
            assert_eq!(tcb_idx, TCB_IDX);
            assert_eq!(Ok(resp), att::AttReadResponse { value: DATA.into() }.try_into());
        })
    }

    #[test]
    fn test_characteristic_write() {
        block_on_locally(async move {
            // arrange
            let (mut gatt, mut transport_rx) = start_gatt_module();
            let mut data_rx = create_server_and_open_connection(&mut gatt);

            // act
            gatt.get_bearer(TCB_IDX).unwrap().handle_packet(
                att::AttWriteRequest { handle: CHARACTERISTIC_HANDLE.into(), value: DATA.into() }
                    .try_into()
                    .unwrap(),
            );
            let (tx, written_data) = if let MockDatastoreEvents::Write(
                TCB_IDX,
                CHARACTERISTIC_HANDLE,
                AttributeBackingType::Characteristic,
                written_data,
                tx,
            ) = data_rx.recv().await.unwrap()
            {
                (tx, written_data)
            } else {
                unreachable!()
            };
            tx.send(Ok(())).unwrap();
            let (tcb_idx, resp) = transport_rx.recv().await.unwrap();

            // assert
            assert_eq!(tcb_idx, TCB_IDX);
            assert_eq!(Ok(resp), att::AttWriteResponse {}.try_into());
            assert_eq!(&DATA, written_data.as_slice());
        })
    }

    #[test]
    fn test_send_indication() {
        block_on_locally(async move {
            // arrange
            let (mut gatt, mut transport_rx) = start_gatt_module();
            create_server_and_open_connection(&mut gatt);

            // act
            let pending_indication = spawn_local(
                gatt.get_bearer(TCB_IDX)
                    .unwrap()
                    .send_indication(CHARACTERISTIC_HANDLE, DATA.into()),
            );

            let (tcb_idx, resp) = transport_rx.recv().await.unwrap();

            gatt.get_bearer(TCB_IDX)
                .unwrap()
                .handle_packet(att::AttHandleValueConfirmation {}.try_into().unwrap());

            // assert
            assert!(matches!(pending_indication.await.unwrap(), Ok(())));
            assert_eq!(tcb_idx, TCB_IDX);
            assert_eq!(
                Ok(resp),
                att::AttHandleValueIndication {
                    handle: CHARACTERISTIC_HANDLE.into(),
                    value: DATA.into(),
                }
                .try_into()
            );
        })
    }

    #[test]
    fn test_send_indication_and_disconnect() {
        block_on_locally(async move {
            // arrange
            let (mut gatt, mut transport_rx) = start_gatt_module();

            create_server_and_open_connection(&mut gatt);

            // act: send an indication, then disconnect
            let pending_indication = spawn_local(
                gatt.get_bearer(TCB_IDX)
                    .unwrap()
                    .send_indication(CHARACTERISTIC_HANDLE, vec![1, 2, 3, 4]),
            );
            transport_rx.recv().await.unwrap();
            gatt.on_le_disconnect(TCB_IDX).unwrap();

            // assert: the pending indication resolves appropriately
            assert!(matches!(
                pending_indication.await.unwrap(),
                Err(IndicationError::ConnectionDroppedWhileWaitingForConfirmation)
            ));
        })
    }

    #[test]
    fn test_write_to_descriptor() {
        block_on_locally(async move {
            // arrange
            let (mut gatt, mut transport_rx) = start_gatt_module();
            let mut data_rx = create_server_and_open_connection(&mut gatt);

            // act
            gatt.get_bearer(TCB_IDX).unwrap().handle_packet(
                att::AttWriteRequest { handle: DESCRIPTOR_HANDLE.into(), value: DATA.into() }
                    .try_into()
                    .unwrap(),
            );
            let (tx, written_data) = if let MockDatastoreEvents::Write(
                TCB_IDX,
                DESCRIPTOR_HANDLE,
                AttributeBackingType::Descriptor,
                written_data,
                tx,
            ) = data_rx.recv().await.unwrap()
            {
                (tx, written_data)
            } else {
                unreachable!()
            };
            tx.send(Ok(())).unwrap();
            let (tcb_idx, resp) = transport_rx.recv().await.unwrap();

            // assert
            assert_eq!(tcb_idx, TCB_IDX);
            assert_eq!(Ok(resp), att::AttWriteResponse {}.try_into());
            assert_eq!(&DATA, written_data.as_slice());
        })
    }

    #[test]
    fn test_multiple_servers() {
        block_on_locally(async move {
            // arrange
            let (mut gatt, mut transport_rx) = start_gatt_module();
            // open the default server (SERVER_ID on CONN_ID)
            let mut data_rx_1 = create_server_and_open_connection(&mut gatt);
            // open a second server and connect to it (ANOTHER_SERVER_ID on ANOTHER_CONN_ID)
            let (datastore, mut data_rx_2) = MockDatastore::new();
            gatt.open_gatt_server(ANOTHER_SERVER_ID).unwrap();
            gatt.register_gatt_service(
                ANOTHER_SERVER_ID,
                GattServiceWithHandle {
                    handle: SERVICE_HANDLE,
                    type_: SERVICE_TYPE,
                    characteristics: vec![GattCharacteristicWithHandle {
                        handle: CHARACTERISTIC_HANDLE,
                        type_: CHARACTERISTIC_TYPE,
                        permissions: AttPermissions::READABLE,
                        descriptors: vec![],
                    }],
                },
                datastore,
            )
            .unwrap();
            gatt.get_isolation_manager()
                .associate_server_with_advertiser(ANOTHER_SERVER_ID, ANOTHER_ADVERTISER_ID);
            gatt.on_le_connect(ANOTHER_TCB_IDX, Some(ANOTHER_ADVERTISER_ID)).unwrap();

            // act: read from both connections
            gatt.get_bearer(TCB_IDX).unwrap().handle_packet(
                att::AttReadRequest { attribute_handle: CHARACTERISTIC_HANDLE.into() }
                    .try_into()
                    .unwrap(),
            );
            gatt.get_bearer(ANOTHER_TCB_IDX).unwrap().handle_packet(
                att::AttReadRequest { attribute_handle: CHARACTERISTIC_HANDLE.into() }
                    .try_into()
                    .unwrap(),
            );
            // service the first read with `data`
            let MockDatastoreEvents::Read(TCB_IDX, _, _, tx) = data_rx_1.recv().await.unwrap()
            else {
                unreachable!()
            };
            tx.send(Ok(DATA.to_vec())).unwrap();
            // and then the second read with `another_data`
            let MockDatastoreEvents::Read(ANOTHER_TCB_IDX, _, _, tx) =
                data_rx_2.recv().await.unwrap()
            else {
                unreachable!()
            };
            tx.send(Ok(ANOTHER_DATA.to_vec())).unwrap();

            // receive both response packets
            let (tcb_idx_1, resp_1) = transport_rx.recv().await.unwrap();
            let (tcb_idx_2, resp_2) = transport_rx.recv().await.unwrap();

            // assert: the responses were routed to the correct connections
            assert_eq!(tcb_idx_1, TCB_IDX);
            assert_eq!(Ok(resp_1), att::AttReadResponse { value: DATA.to_vec() }.try_into());
            assert_eq!(tcb_idx_2, ANOTHER_TCB_IDX);
            assert_eq!(
                Ok(resp_2),
                att::AttReadResponse { value: ANOTHER_DATA.to_vec() }.try_into()
            );
        })
    }

    #[test]
    fn test_read_device_name() {
        block_on_locally(async move {
            // arrange
            let (mut gatt, mut transport_rx) = start_gatt_module();
            create_server_and_open_connection(&mut gatt);

            // act: try to read the device name
            gatt.get_bearer(TCB_IDX).unwrap().handle_packet(
                att::AttReadByTypeRequest {
                    starting_handle: AttHandle(1).into(),
                    ending_handle: AttHandle(0xFFFF).into(),
                    attribute_type: DEVICE_NAME_UUID.into(),
                }
                .try_into()
                .unwrap(),
            );
            let (tcb_idx, resp) = transport_rx.recv().await.unwrap();

            // assert: the name is readable and in the correct format
            assert_eq!(tcb_idx, TCB_IDX);
            let resp: att::AttReadByTypeResponse = resp.try_into().unwrap();
            assert_eq!(resp.data.len(), 1);
            let attr = &resp.data[0];

            // The handle is defined in services/gap.rs
            let device_name_handle = AttHandle(22);
            assert_eq!(attr.handle, device_name_handle.into());
            let name_str = std::str::from_utf8(&attr.value).unwrap();
            assert!(name_str.starts_with(DEVICE_NAME_PREFIX));
            let suffix = &name_str[DEVICE_NAME_PREFIX.len()..];
            assert_eq!(suffix.len(), 4);
            assert!(suffix.parse::<u16>().is_ok());
        });
    }

    #[test]
    fn test_ignored_service_change_indication() {
        block_on_locally(async move {
            // arrange
            let (mut gatt, mut transport_rx) = start_gatt_module();
            create_server_and_open_connection(&mut gatt);

            // act: add a new service
            let (datastore, _) = MockDatastore::new();
            gatt.register_gatt_service(
                SERVER_ID,
                GattServiceWithHandle {
                    handle: AttHandle(30),
                    type_: SERVICE_TYPE,
                    characteristics: vec![],
                },
                datastore,
            )
            .unwrap();

            // assert: no packets should be sent
            assert_eq!(transport_rx.try_recv().unwrap_err(), TryRecvError::Empty);
        });
    }

    #[test]
    fn test_service_change_indication() {
        block_on_locally(async move {
            // arrange
            let (mut gatt, mut transport_rx) = start_gatt_module();
            create_server_and_open_connection(&mut gatt);

            // act: discover the GATT server
            gatt.get_bearer(TCB_IDX).unwrap().handle_packet(
                att::AttFindByTypeValueRequest {
                    starting_handle: AttHandle::MIN.into(),
                    ending_handle: AttHandle::MAX.into(),
                    attribute_type: PRIMARY_SERVICE_DECLARATION_UUID.try_into().unwrap(),
                    attribute_value: att::UuidAsAttData { uuid: GATT_SERVICE_UUID.into() }
                        .encode_to_vec()
                        .unwrap(),
                }
                .try_into()
                .unwrap(),
            );
            let Ok(resp): Result<att::AttFindByTypeValueResponse, _> =
                transport_rx.recv().await.unwrap().1.try_into()
            else {
                unreachable!()
            };
            let (starting_handle, ending_handle) = (
                resp.handles_info[0].clone().found_attribute_handle,
                resp.handles_info[0].clone().group_end_handle,
            );
            // act: discover the service changed characteristic
            gatt.get_bearer(TCB_IDX).unwrap().handle_packet(
                att::AttReadByTypeRequest {
                    starting_handle,
                    ending_handle,
                    attribute_type: CHARACTERISTIC_UUID.into(),
                }
                .try_into()
                .unwrap(),
            );

            let Ok(resp): Result<att::AttReadByTypeResponse, _> =
                transport_rx.recv().await.unwrap().1.try_into()
            else {
                unreachable!()
            };
            let service_change_char_handle: AttHandle = resp
                .data
                .into_iter()
                .find_map(|characteristic| {
                    let value = characteristic.value.to_vec();
                    let decl =
                        att::GattCharacteristicDeclarationValue::decode_full(value.as_slice())
                            .unwrap();

                    if SERVICE_CHANGE_UUID == decl.uuid.try_into().unwrap() {
                        Some(decl.handle.into())
                    } else {
                        None
                    }
                })
                .unwrap();
            // act: find the CCC descriptor for the service changed characteristic
            gatt.get_bearer(TCB_IDX).unwrap().handle_packet(
                att::AttFindInformationRequest {
                    starting_handle: service_change_char_handle.into(),
                    ending_handle: AttHandle::MAX.into(),
                }
                .try_into()
                .unwrap(),
            );
            let Ok(resp): Result<att::AttFindInformationResponse, _> =
                transport_rx.recv().await.unwrap().1.try_into()
            else {
                unreachable!()
            };
            let Ok(resp): Result<att::AttFindInformationShortResponse, _> = resp.try_into() else {
                unreachable!()
            };
            let service_change_descriptor_handle = resp
                .data
                .into_iter()
                .find_map(|attr| {
                    if attr.uuid == CLIENT_CHARACTERISTIC_CONFIGURATION_UUID.try_into().unwrap() {
                        Some(attr.handle)
                    } else {
                        None
                    }
                })
                .unwrap();
            // act: register for indications on this handle
            gatt.get_bearer(TCB_IDX).unwrap().handle_packet(
                att::AttWriteRequest {
                    handle: service_change_descriptor_handle,
                    value: att::GattClientCharacteristicConfiguration {
                        notification: 0,
                        indication: 1,
                    }
                    .encode_to_vec()
                    .unwrap(),
                }
                .try_into()
                .unwrap(),
            );
            let Ok(_): Result<att::AttWriteResponse, _> =
                transport_rx.recv().await.unwrap().1.try_into()
            else {
                unreachable!()
            };
            // act: add a new service
            let (datastore, _) = MockDatastore::new();
            gatt.register_gatt_service(
                SERVER_ID,
                GattServiceWithHandle {
                    handle: AttHandle(30),
                    type_: SERVICE_TYPE,
                    characteristics: vec![],
                },
                datastore,
            )
            .unwrap();

            // assert: we got an indication
            let Ok(indication): Result<att::AttHandleValueIndication, _> =
                transport_rx.recv().await.unwrap().1.try_into()
            else {
                unreachable!()
            };
            assert_eq!(indication.handle, service_change_char_handle.into());
            assert_eq!(
                Ok(indication.value),
                att::GattServiceChanged {
                    start_handle: AttHandle(30).into(),
                    end_handle: AttHandle(30).into(),
                }
                .encode_to_vec()
            );
        });
    }

    #[test]
    fn test_closing_gatt_server_unisolates_advertiser() {
        block_on_locally(async move {
            // arrange
            let (mut gatt, _) = start_gatt_module();
            gatt.open_gatt_server(SERVER_ID).unwrap();
            gatt.get_isolation_manager().associate_server_with_advertiser(SERVER_ID, ADVERTISER_ID);

            // act
            gatt.close_gatt_server(SERVER_ID).unwrap();

            // assert
            let is_advertiser_isolated =
                gatt.get_isolation_manager().is_advertiser_isolated(ADVERTISER_ID);
            assert!(!is_advertiser_isolated);
        });
    }

    #[test]
    fn test_disconnection_unisolates_connection() {
        block_on_locally(async move {
            // arrange
            let (mut gatt, _) = start_gatt_module();
            create_server_and_open_connection(&mut gatt);

            // act
            gatt.on_le_disconnect(TCB_IDX).unwrap();

            // assert
            let is_connection_isolated =
                gatt.get_isolation_manager().is_connection_isolated(TCB_IDX);
            assert!(!is_connection_isolated);
        });
    }

    #[test]
    fn test_on_le_connect_no_advertiser() {
        let (mut gatt, _) = start_gatt_module();
        gatt.open_gatt_server(SERVER_ID).unwrap();
        gatt.get_isolation_manager().associate_server_with_advertiser(SERVER_ID, ADVERTISER_ID);
        assert!(gatt.on_le_connect(TCB_IDX, None).is_err());
        assert!(gatt.get_bearer(TCB_IDX).is_none());
    }

    #[test]
    fn test_on_le_connect_no_server() {
        let (mut gatt, _) = start_gatt_module();
        gatt.get_isolation_manager().associate_server_with_advertiser(SERVER_ID, ADVERTISER_ID);
        assert!(gatt.on_le_connect(TCB_IDX, Some(ADVERTISER_ID)).is_err());
        assert!(gatt.get_bearer(TCB_IDX).is_none());
    }

    #[test]
    fn test_on_le_disconnect_no_bearer() {
        let (mut gatt, _) = start_gatt_module();
        assert!(gatt.on_le_disconnect(TCB_IDX).is_err());
    }

    #[test]
    fn test_unregister_gatt_service_no_server() {
        let (mut gatt, _) = start_gatt_module();
        assert!(gatt.unregister_gatt_service(SERVER_ID, SERVICE_HANDLE).is_err());
    }

    #[test]
    fn test_open_gatt_server_twice() {
        let (mut gatt, _) = start_gatt_module();
        gatt.open_gatt_server(SERVER_ID).unwrap();
        assert!(gatt.open_gatt_server(SERVER_ID).is_err());
    }

    #[test]
    fn test_close_gatt_server_no_server() {
        let (mut gatt, _) = start_gatt_module();
        assert!(gatt.close_gatt_server(SERVER_ID).is_err());
    }

    #[test]
    fn test_get_bearer_nonexistent() {
        let (gatt, _) = start_gatt_module();
        assert!(gatt.get_bearer(TCB_IDX).is_none());
    }
}
