//! The GATT service as defined in Core Spec 5.3 Vol 3G Section 7

use pdl_runtime::Packet;
use std::cell::RefCell;
use std::collections::HashMap;
use std::ops::RangeInclusive;
use std::rc::Rc;

use anyhow::Result;
use async_trait::async_trait;
use log::{error, warn};
use tokio::task::spawn_local;

use crate::core::shared_box::SharedBox;
use crate::core::uuid::Uuid;
use crate::gatt::callbacks::GattDatastore;
use crate::gatt::ffi::AttributeBackingType;
use crate::gatt::ids::{AttHandle, TransportIndex};
use crate::gatt::server::att_client::{AttClient, WeakAttClient};
use crate::gatt::server::gatt_database::{
    AttPermissions, GattCharacteristicWithHandle, GattDatabase, GattDatabaseCallbacks,
    GattDescriptorWithHandle, GattServiceWithHandle,
};
use crate::packets::att::{self, AttErrorCode};

#[derive(Default)]
struct GattService {
    clients: RefCell<HashMap<TransportIndex, ClientState>>,
}

#[derive(Clone)]
struct ClientState {
    client: WeakAttClient,
    registered_for_service_change: bool,
}

// Must lie in the range specified by GATT_GATT_START_HANDLE from legacy stack
const GATT_SERVICE_HANDLE: AttHandle = AttHandle(1);
const SERVICE_CHANGE_HANDLE: AttHandle = AttHandle(3);
const SERVICE_CHANGE_CCC_DESCRIPTOR_HANDLE: AttHandle = AttHandle(4);

/// The UUID used for the GATT service (Assigned Numbers 3.4.1 Services by Name)
pub const GATT_SERVICE_UUID: Uuid = Uuid::new(0x1801);
/// The UUID used for the Service Changed characteristic (Assigned Numbers 3.8.1 Characteristics by Name)
pub const SERVICE_CHANGE_UUID: Uuid = Uuid::new(0x2A05);
/// The UUID used for the Client Characteristic Configuration descriptor (Assigned Numbers 3.7 Descriptors)
pub const CLIENT_CHARACTERISTIC_CONFIGURATION_UUID: Uuid = Uuid::new(0x2902);

#[async_trait(?Send)]
impl GattDatastore for GattService {
    async fn read(
        &self,
        tcb_idx: TransportIndex,
        handle: AttHandle,
        _: AttributeBackingType,
    ) -> Result<Vec<u8>, AttErrorCode> {
        if handle == SERVICE_CHANGE_CCC_DESCRIPTOR_HANDLE {
            att::GattClientCharacteristicConfiguration {
                notification: 0,
                indication: self
                    .clients
                    .borrow()
                    .get(&tcb_idx)
                    .map(|state| state.registered_for_service_change)
                    .unwrap_or(false)
                    .into(),
            }
            .encode_to_vec()
            .map_err(|_| AttErrorCode::UnlikelyError)
        } else {
            unreachable!()
        }
    }

    async fn write(
        &self,
        tcb_idx: TransportIndex,
        handle: AttHandle,
        _: AttributeBackingType,
        data: &[u8],
    ) -> Result<(), AttErrorCode> {
        if handle == SERVICE_CHANGE_CCC_DESCRIPTOR_HANDLE {
            let ccc =
                att::GattClientCharacteristicConfiguration::decode_full(data).map_err(|err| {
                    warn!("failed to parse CCC descriptor, got: {err:?}");
                    AttErrorCode::ApplicationError
                })?;
            let mut clients = self.clients.borrow_mut();
            let state = clients.get_mut(&tcb_idx);
            let Some(state) = state else {
                error!("Received write request from disconnected client...");
                return Err(AttErrorCode::UnlikelyError);
            };
            state.registered_for_service_change = ccc.indication != 0;
            Ok(())
        } else {
            unreachable!()
        }
    }
}

impl GattDatabaseCallbacks for GattService {
    fn on_le_connect(&self, client: &SharedBox<AttClient>) {
        // TODO(aryarahul): registered_for_service_change may not be false for bonded devices
        self.clients.borrow_mut().insert(
            client.tcb_idx(),
            ClientState { client: client.downgrade(), registered_for_service_change: false },
        );
    }

    fn on_le_disconnect(&self, tcb_idx: TransportIndex) {
        self.clients.borrow_mut().remove(&tcb_idx);
    }

    fn on_service_change(&self, range: RangeInclusive<AttHandle>) {
        for (conn_id, client) in self.clients.borrow().clone() {
            if client.registered_for_service_change {
                client.client.with(|client| match client {
                    Some(client) => {
                        spawn_local(
                            client.bearer().send_indication(
                                SERVICE_CHANGE_HANDLE,
                                att::GattServiceChanged {
                                    start_handle: (*range.start()).into(),
                                    end_handle: (*range.end()).into(),
                                }
                                .encode_to_vec()
                                .unwrap(),
                            ),
                        );
                    }
                    None => {
                        error!("Registered client has been destructed ({conn_id:?})")
                    }
                });
            }
        }
    }
}

/// Register the GATT service in the provided GATT database.
pub fn register_gatt_service(database: &mut GattDatabase) -> Result<()> {
    let this = Rc::new(GattService::default());
    database.add_service_with_handles(
        // GATT Service
        GattServiceWithHandle {
            handle: GATT_SERVICE_HANDLE,
            type_: GATT_SERVICE_UUID,
            // Service Changed Characteristic
            characteristics: vec![GattCharacteristicWithHandle {
                handle: SERVICE_CHANGE_HANDLE,
                type_: SERVICE_CHANGE_UUID,
                permissions: AttPermissions::INDICATE,
                descriptors: vec![GattDescriptorWithHandle {
                    handle: SERVICE_CHANGE_CCC_DESCRIPTOR_HANDLE,
                    type_: CLIENT_CHARACTERISTIC_CONFIGURATION_UUID,
                    permissions: AttPermissions::READABLE | AttPermissions::WRITABLE_WITH_RESPONSE,
                }],
            }],
        },
        this.clone(),
    )?;
    database.register_listener(this);
    Ok(())
}
#[cfg(test)]
mod test {
    use super::*;

    use crate::core::shared_box::SharedBox;
    use crate::gatt::mocks::mock_datastore::MockDatastore;
    use crate::gatt::server::att_client::{AttClient, WeakAttClient};
    use crate::gatt::server::gatt_database::{
        GattDatabase, CHARACTERISTIC_UUID, PRIMARY_SERVICE_DECLARATION_UUID,
    };
    use crate::packets::att;
    use crate::utils::task::{block_on_locally, try_await};

    const TCB_IDX: TransportIndex = TransportIndex(1);
    const ANOTHER_TCB_IDX: TransportIndex = TransportIndex(2);
    const SERVICE_TYPE: Uuid = Uuid::new(0x1234);
    const CHARACTERISTIC_TYPE: Uuid = Uuid::new(0x5678);

    fn init_gatt_db() -> SharedBox<GattDatabase> {
        let mut gatt_database = GattDatabase::new();
        register_gatt_service(&mut gatt_database).unwrap();
        SharedBox::new(gatt_database)
    }

    #[test]
    fn test_gatt_service_discovery() {
        // arrange
        let gatt_db = init_gatt_db();
        let (client, _) = AttClient::new_test_client(TCB_IDX, &gatt_db);

        // act: discover all services
        let attrs = client.list_attributes();

        // assert: 1 service + 1 char decl + 1 char value + 1 char descriptor = 4 attrs
        assert_eq!(attrs.len(), 4);
        // assert: value handles are correct
        assert_eq!(attrs[0].handle, GATT_SERVICE_HANDLE);
        assert_eq!(attrs[2].handle, SERVICE_CHANGE_HANDLE);
        // assert: types are correct
        assert_eq!(attrs[0].type_, PRIMARY_SERVICE_DECLARATION_UUID);
        assert_eq!(attrs[1].type_, CHARACTERISTIC_UUID);
        assert_eq!(attrs[2].type_, SERVICE_CHANGE_UUID);
        assert_eq!(attrs[3].type_, CLIENT_CHARACTERISTIC_CONFIGURATION_UUID);
        // assert: permissions of value attrs are correct
        assert_eq!(attrs[2].permissions, AttPermissions::INDICATE);
        assert_eq!(
            attrs[3].permissions,
            AttPermissions::READABLE | AttPermissions::WRITABLE_WITH_RESPONSE
        );
    }

    #[test]
    fn test_default_indication_subscription() {
        // arrange
        let gatt_db = init_gatt_db();
        let (client, _) = AttClient::new_test_client(TCB_IDX, &gatt_db);

        // act: try to read the CCC descriptor
        let resp =
            block_on_locally(client.read_attribute(SERVICE_CHANGE_CCC_DESCRIPTOR_HANDLE)).unwrap();

        assert_eq!(
            Ok(resp),
            att::GattClientCharacteristicConfiguration { notification: 0, indication: 0 }
                .encode_to_vec()
        );
    }

    async fn register_for_indication(
        client: WeakAttClient,
        handle: AttHandle,
    ) -> Result<(), AttErrorCode> {
        client
            .write_attribute(
                handle,
                &att::GattClientCharacteristicConfiguration { notification: 0, indication: 1 }
                    .encode_to_vec()
                    .unwrap(),
            )
            .await
    }

    #[test]
    fn test_subscribe_to_indication() {
        // arrange
        let gatt_db = init_gatt_db();
        let (client, _) = AttClient::new_test_client(TCB_IDX, &gatt_db);

        // act: register for service change indication
        block_on_locally(register_for_indication(
            client.downgrade(),
            SERVICE_CHANGE_CCC_DESCRIPTOR_HANDLE,
        ))
        .unwrap();
        // read our registration status
        let resp =
            block_on_locally(client.read_attribute(SERVICE_CHANGE_CCC_DESCRIPTOR_HANDLE)).unwrap();

        // assert: we are registered for indications
        assert_eq!(
            Ok(resp),
            att::GattClientCharacteristicConfiguration { notification: 0, indication: 1 }
                .encode_to_vec()
        );
    }

    #[test]
    fn test_subscribe_to_indication_malformed() {
        // arrange
        let gatt_db = init_gatt_db();
        let (client, _) = AttClient::new_test_client(TCB_IDX, &gatt_db);

        // act: register for service change indication with a malformed value
        let result = block_on_locally(
            client.write_attribute(SERVICE_CHANGE_CCC_DESCRIPTOR_HANDLE, &[0x01, 0x02, 0x03]),
        );

        // assert: we get an application error
        assert_eq!(result, Err(AttErrorCode::ApplicationError));
    }

    #[test]
    fn test_unsubscribe_to_indication() {
        // arrange
        let gatt_db = init_gatt_db();
        let (client, _) = AttClient::new_test_client(TCB_IDX, &gatt_db);

        // act: register for service change indication
        block_on_locally(
            client.write_attribute(
                SERVICE_CHANGE_CCC_DESCRIPTOR_HANDLE,
                &att::GattClientCharacteristicConfiguration { notification: 0, indication: 1 }
                    .encode_to_vec()
                    .unwrap(),
            ),
        )
        .unwrap();
        // act: next, unregister from this indication
        block_on_locally(
            client.write_attribute(
                SERVICE_CHANGE_CCC_DESCRIPTOR_HANDLE,
                &att::GattClientCharacteristicConfiguration { notification: 0, indication: 0 }
                    .encode_to_vec()
                    .unwrap(),
            ),
        )
        .unwrap();
        // read our registration status
        let resp =
            block_on_locally(client.read_attribute(SERVICE_CHANGE_CCC_DESCRIPTOR_HANDLE)).unwrap();

        // assert: we are not registered for indications
        assert_eq!(
            Ok(resp),
            att::GattClientCharacteristicConfiguration { notification: 0, indication: 0 }
                .encode_to_vec()
        );
    }

    #[test]
    fn test_single_registered_service_change_indication() {
        block_on_locally(async {
            // arrange
            let gatt_db = init_gatt_db();
            let (client, mut rx) = AttClient::new_test_client(TCB_IDX, &gatt_db);
            let (gatt_datastore, _) = MockDatastore::new();
            let gatt_datastore = Rc::new(gatt_datastore);
            register_for_indication(client.downgrade(), SERVICE_CHANGE_CCC_DESCRIPTOR_HANDLE)
                .await
                .unwrap();

            // act: register some new service
            gatt_db
                .add_service_with_handles(
                    GattServiceWithHandle {
                        handle: AttHandle(15),
                        type_: SERVICE_TYPE,
                        characteristics: vec![GattCharacteristicWithHandle {
                            handle: AttHandle(17),
                            type_: CHARACTERISTIC_TYPE,
                            permissions: AttPermissions::empty(),
                            descriptors: vec![],
                        }],
                    },
                    gatt_datastore,
                )
                .unwrap();

            // assert: we received the service change indication
            let resp = rx.recv().await.unwrap();
            let Ok(resp): Result<att::AttHandleValueIndication, _> = resp.try_into() else {
                unreachable!();
            };
            let Ok(resp) = att::GattServiceChanged::decode_full(resp.value.as_slice()) else {
                unreachable!();
            };
            assert_eq!(resp.start_handle.handle, 15);
            assert_eq!(resp.end_handle.handle, 17);
        });
    }

    #[test]
    fn test_multiple_registered_service_change_indication() {
        block_on_locally(async {
            // arrange: two connections, both registered
            let gatt_db = init_gatt_db();
            let (client1, mut rx1) = AttClient::new_test_client(TCB_IDX, &gatt_db);
            let (client2, mut rx2) = AttClient::new_test_client(ANOTHER_TCB_IDX, &gatt_db);

            register_for_indication(client1.downgrade(), SERVICE_CHANGE_CCC_DESCRIPTOR_HANDLE)
                .await
                .unwrap();
            register_for_indication(client2.downgrade(), SERVICE_CHANGE_CCC_DESCRIPTOR_HANDLE)
                .await
                .unwrap();

            let (gatt_datastore, _) = MockDatastore::new();
            let gatt_datastore = Rc::new(gatt_datastore);

            // act: register some new service
            gatt_db
                .add_service_with_handles(
                    GattServiceWithHandle {
                        handle: AttHandle(15),
                        type_: SERVICE_TYPE,
                        characteristics: vec![GattCharacteristicWithHandle {
                            handle: AttHandle(17),
                            type_: CHARACTERISTIC_TYPE,
                            permissions: AttPermissions::empty(),
                            descriptors: vec![],
                        }],
                    },
                    gatt_datastore,
                )
                .unwrap();

            // assert: both connections received the service change indication
            let resp1 = rx1.recv().await.unwrap();
            let resp2 = rx2.recv().await.unwrap();
            assert!(matches!(resp1.try_into(), Ok(att::AttHandleValueIndication { .. })));
            assert!(matches!(resp2.try_into(), Ok(att::AttHandleValueIndication { .. })));
        });
    }

    #[test]
    fn test_one_unregistered_service_change_indication() {
        block_on_locally(async {
            // arrange: two connections, only the first is registered
            let gatt_db = init_gatt_db();
            let (client1, mut rx1) = AttClient::new_test_client(TCB_IDX, &gatt_db);
            let (_client2, mut rx2) = AttClient::new_test_client(ANOTHER_TCB_IDX, &gatt_db);

            register_for_indication(client1.downgrade(), SERVICE_CHANGE_CCC_DESCRIPTOR_HANDLE)
                .await
                .unwrap();

            let (gatt_datastore, _) = MockDatastore::new();
            let gatt_datastore = Rc::new(gatt_datastore);

            // act: register some new service
            gatt_db
                .add_service_with_handles(
                    GattServiceWithHandle {
                        handle: AttHandle(15),
                        type_: SERVICE_TYPE,
                        characteristics: vec![GattCharacteristicWithHandle {
                            handle: AttHandle(17),
                            type_: CHARACTERISTIC_TYPE,
                            permissions: AttPermissions::empty(),
                            descriptors: vec![],
                        }],
                    },
                    gatt_datastore,
                )
                .unwrap();

            // assert: the first connection received the service change indication
            let resp1 = rx1.recv().await.unwrap();
            assert!(matches!(resp1.try_into(), Ok(att::AttHandleValueIndication { .. })));
            // assert: the second connection received nothing
            assert!(try_await(async move { rx2.recv().await }).await.is_err());
        });
    }

    #[test]
    fn test_one_disconnected_service_change_indication() {
        block_on_locally(async {
            // arrange: two connections, both register, but the second one disconnects
            let gatt_db = init_gatt_db();
            let (client1, mut rx1) = AttClient::new_test_client(TCB_IDX, &gatt_db);
            let (client2, mut rx2) = AttClient::new_test_client(ANOTHER_TCB_IDX, &gatt_db);

            register_for_indication(client1.downgrade(), SERVICE_CHANGE_CCC_DESCRIPTOR_HANDLE)
                .await
                .unwrap();
            register_for_indication(client2.downgrade(), SERVICE_CHANGE_CCC_DESCRIPTOR_HANDLE)
                .await
                .unwrap();

            drop(client2);

            let (gatt_datastore, _) = MockDatastore::new();
            let gatt_datastore = Rc::new(gatt_datastore);

            // act: register some new service
            gatt_db
                .add_service_with_handles(
                    GattServiceWithHandle {
                        handle: AttHandle(15),
                        type_: SERVICE_TYPE,
                        characteristics: vec![GattCharacteristicWithHandle {
                            handle: AttHandle(17),
                            type_: CHARACTERISTIC_TYPE,
                            permissions: AttPermissions::empty(),
                            descriptors: vec![],
                        }],
                    },
                    gatt_datastore,
                )
                .unwrap();

            // assert: the first connection received the service change indication
            let resp1 = rx1.recv().await.unwrap();
            assert!(matches!(resp1.try_into(), Ok(att::AttHandleValueIndication { .. })));
            // assert: the second connection is closed
            assert!(rx2.recv().await.is_none());
        });
    }
}
