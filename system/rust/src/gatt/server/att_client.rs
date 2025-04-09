use crate::core::shared_box::{SharedBox, WeakBox};
use crate::gatt::callbacks::GattWriteRequestType;
use crate::gatt::ffi::AttributeBackingType;
use crate::gatt::ids::{AttHandle, TransportIndex};
use crate::gatt::server::att_database::AttAttribute;
use crate::gatt::server::att_server_bearer::AttServerBearer;
use crate::gatt::server::gatt_database::{
    AttAttributeBackingValue, AttAttributeWithBackingValue, GattDatabase,
};
use crate::packets::att::{self, AttErrorCode};
use log::{error, warn};
use pdl_runtime::EncodeError;
#[cfg(test)]
use std::future::Future;

/// AttClient represents a connection from a *remote* device to a *local* server i.e. it is for
/// *inbound* connections but it holds the required state for our server. All connections with the
/// same remote address map to the same client, and there is a one-to-one mapping to the transport
/// index which identifies the client in the legacy stack. For now, we only support a single bearer
/// but in future, with EATT, there may be more bearers. A client has access to only one particular
/// database (which may be shared amongst many clients).
pub struct AttClient {
    // The identifier for the client in the legacy stack.
    tcb_idx: TransportIndex,

    bearer: SharedBox<AttServerBearer>,

    // The underlying database for this client. This is weak because databases can be destroyed
    // independently of clients.
    gatt_db: WeakBox<GattDatabase>,
}

impl AttClient {
    pub fn new_client_and_bearer(
        tcb_idx: TransportIndex,
        send_packet: impl Fn(att::Att) -> Result<(), EncodeError> + 'static,
        db: &SharedBox<GattDatabase>,
    ) -> SharedBox<Self> {
        let this = SharedBox::new_cyclic(|weak| Self {
            tcb_idx,
            bearer: SharedBox::new(AttServerBearer::new(weak, send_packet)),
            gatt_db: db.downgrade(),
        });
        db.on_client_connect(&this);
        this
    }

    /// Returns a test client and a receiver for all packets sent.
    #[cfg(test)]
    pub fn new_test_client(
        tcb_idx: TransportIndex,
        db: &SharedBox<GattDatabase>,
    ) -> (SharedBox<Self>, tokio::sync::mpsc::UnboundedReceiver<att::Att>) {
        let (tx, rx) = tokio::sync::mpsc::unbounded_channel();
        (
            Self::new_client_and_bearer(
                tcb_idx,
                move |packet| {
                    tx.send(packet).unwrap();
                    Ok(())
                },
                db,
            ),
            rx,
        )
    }

    pub fn tcb_idx(&self) -> TransportIndex {
        self.tcb_idx
    }

    pub fn bearer(&self) -> &SharedBox<AttServerBearer> {
        &self.bearer
    }

    pub fn list_attributes(&self) -> Vec<AttAttribute> {
        self.gatt_db.with(|db| db.map(|db| db.list_attributes()).unwrap_or_default())
    }

    pub fn write_no_response_attribute(&self, handle: AttHandle, data: &[u8]) {
        if self.gatt_db.with(|db| {
            db.and_then(|db| db.with_attribute(handle, |attr| {
                if !attr.attribute.permissions.writable_without_response() {
                    warn!("trying to write without response to {handle:?}, which doesn't support it");
                    return;
                }
                match &attr.value {
                    AttAttributeBackingValue::Static(val) => {
                        error!("A static attribute {val:?} is marked as writable - ignoring it and rejecting the write...");
                    }
                    AttAttributeBackingValue::DynamicCharacteristic(datastore) => {
                        datastore.write_no_response(
                            self.tcb_idx,
                            handle,
                            AttributeBackingType::Characteristic,
                            data,
                        );
                    }
                    AttAttributeBackingValue::DynamicDescriptor(datastore) => {
                        datastore.write_no_response(
                            self.tcb_idx,
                            handle,
                            AttributeBackingType::Descriptor,
                            data,
                        );
                    }
                }
            }))}).is_none() {
            warn!("cannot find handle {handle:?}");
        }
    }
}

impl Drop for AttClient {
    fn drop(&mut self) {
        self.gatt_db.with(|db| {
            if let Some(db) = db {
                db.on_client_dropped(self.tcb_idx);
            }
        });
    }
}

impl SharedBox<AttClient> {
    #[cfg(test)]
    pub fn read_attribute(
        &self,
        handle: AttHandle,
    ) -> impl Future<Output = Result<Vec<u8>, AttErrorCode>> {
        let this = self.downgrade();
        async move { this.read_attribute(handle).await }
    }

    #[cfg(test)]
    pub fn write_attribute(
        &self,
        handle: AttHandle,
        data: &[u8],
    ) -> impl Future<Output = Result<(), AttErrorCode>> {
        let this = self.downgrade();
        let data = data.to_vec();
        async move { this.write_attribute(handle, &data).await }
    }
}

// Strong references to AttClient should not be held over await points (since that has the potential
// to lead to reference cycles), so async methods operate need to operate on `WeakAttClient`.
pub type WeakAttClient = WeakBox<AttClient>;

impl WeakAttClient {
    /// Calls `f` with the client and database. Returns an `InvalidHandle` error if either the
    /// client or database has been closed.
    fn with_db<T, E: From<AttErrorCode>>(
        &self,
        f: impl FnOnce(&AttClient, &GattDatabase) -> Result<T, E>,
    ) -> Result<T, E> {
        self.with(|client| {
            client.map_or(Err(AttErrorCode::InvalidHandle.into()), |client| {
                client.gatt_db.with(|db| {
                    db.map_or(Err(AttErrorCode::InvalidHandle.into()), |db| f(client, db))
                })
            })
        })
    }

    /// Calls `f` with the client and attribute. Returns an `InvalidHandle` error if either the
    /// client or database has been closed, or if the handle doesn't exist.
    pub fn with_attribute<T, E: From<AttErrorCode>>(
        &self,
        handle: AttHandle,
        f: impl FnOnce(&AttClient, &AttAttributeWithBackingValue) -> Result<T, E>,
    ) -> Result<T, E> {
        self.with_db(|client, db| {
            db.with_attribute(handle, |attr| f(client, attr))
                .unwrap_or(Err(AttErrorCode::InvalidHandle.into()))
        })
    }

    pub async fn read_attribute(&self, handle: AttHandle) -> Result<Vec<u8>, AttErrorCode> {
        let (tcb_idx, value) = self.with_attribute(handle, |client, attr| {
            if !attr.attribute.permissions.readable() {
                Err(AttErrorCode::ReadNotPermitted)
            } else {
                Ok((client.tcb_idx, attr.value.clone()))
            }
        })?;

        match value {
            AttAttributeBackingValue::Static(val) => Ok(val),
            AttAttributeBackingValue::DynamicCharacteristic(datastore) => {
                datastore
                    .read(
                        tcb_idx,
                        handle,
                        /* offset */ 0,
                        AttributeBackingType::Characteristic,
                    )
                    .await
            }
            AttAttributeBackingValue::DynamicDescriptor(datastore) => {
                datastore
                    .read(tcb_idx, handle, /* offset */ 0, AttributeBackingType::Descriptor)
                    .await
            }
        }
    }

    pub async fn write_attribute(
        &self,
        handle: AttHandle,
        data: &[u8],
    ) -> Result<(), AttErrorCode> {
        let (tcb_idx, value) = self.with_attribute(handle, |client, attr| {
            if !attr.attribute.permissions.writable_with_response() {
                Err(AttErrorCode::WriteNotPermitted)
            } else {
                Ok((client.tcb_idx, attr.value.clone()))
            }
        })?;

        match value {
            AttAttributeBackingValue::Static(val) => {
                error!(
                    "A static attribute {val:?} (handle={handle:?}) is marked as writable - \
                     ignoring it and rejecting the write..."
                );
                Err(AttErrorCode::WriteNotPermitted)
            }
            AttAttributeBackingValue::DynamicCharacteristic(datastore) => {
                datastore
                    .write(
                        tcb_idx,
                        handle,
                        AttributeBackingType::Characteristic,
                        GattWriteRequestType::Request,
                        data,
                    )
                    .await
            }
            AttAttributeBackingValue::DynamicDescriptor(datastore) => {
                datastore
                    .write(
                        tcb_idx,
                        handle,
                        AttributeBackingType::Descriptor,
                        GattWriteRequestType::Request,
                        data,
                    )
                    .await
            }
        }
    }

    /// Returns the list of attributes or an empty list if either the client or database has been
    /// closed. This isn't async, but it exists as a convenience method since this often needs to be
    /// called on `WeakAttClient`.
    pub fn list_attributes(&self) -> Vec<AttAttribute> {
        self.with(|client| client.map(|c| c.list_attributes()).unwrap_or_default())
    }

    pub fn write_no_response_attribute(&self, handle: AttHandle, data: &[u8]) {
        self.with(|client| {
            if let Some(client) = client {
                client.write_no_response_attribute(handle, data);
            }
        });
    }
}
