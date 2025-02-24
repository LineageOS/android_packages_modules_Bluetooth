use crate::core::shared_box::SharedBox;
use crate::gatt::callbacks::{GattWriteRequestType, TransactionDecision};
use crate::gatt::ffi::AttributeBackingType;
use crate::gatt::ids::{AttHandle, TransportIndex};
use crate::gatt::server::att_database::AttAttribute;
use crate::gatt::server::gatt_database::GattDatabase;
use crate::gatt::server::RawGattDatastore;
use crate::packets::att::AttErrorCode;

use async_trait::async_trait;
use std::cell::RefCell;
use std::collections::{BTreeMap, HashMap};
use std::rc::Rc;

pub struct TestDatastore {
    values: BTreeMap<AttHandle, RefCell<Vec<u8>>>,
    queued_writes: RefCell<HashMap<TransportIndex, Vec<(AttHandle, u32, Vec<u8>)>>>,
}

impl TestDatastore {
    pub fn new(values: impl IntoIterator<Item = (AttHandle, Vec<u8>)>) -> Rc<Self> {
        Rc::new(Self {
            values: values.into_iter().map(|(handle, data)| (handle, RefCell::new(data))).collect(),
            queued_writes: RefCell::default(),
        })
    }

    fn write_impl(&self, handle: AttHandle, data: &[u8]) -> Result<(), AttErrorCode> {
        match self.values.get(&handle) {
            Some(value) => {
                *value.borrow_mut() = data.into();
                Ok(())
            }
            None => Err(AttErrorCode::InvalidHandle),
        }
    }
}

#[async_trait(?Send)]
impl RawGattDatastore for TestDatastore {
    async fn read(
        &self,
        _tcb_idx: TransportIndex,
        handle: AttHandle,
        offset: u32,
        attr_type: AttributeBackingType,
    ) -> Result<Vec<u8>, AttErrorCode> {
        assert_eq!(offset, 0);
        assert_eq!(attr_type, AttributeBackingType::Characteristic);
        match self.values.get(&handle) {
            Some(value) => Ok(value.borrow().clone()),
            None => Err(AttErrorCode::InvalidHandle),
        }
    }

    async fn write(
        &self,
        tcb_idx: TransportIndex,
        handle: AttHandle,
        attr_type: AttributeBackingType,
        write_type: GattWriteRequestType,
        data: &[u8],
    ) -> Result<(), AttErrorCode> {
        assert_eq!(attr_type, AttributeBackingType::Characteristic);
        match write_type {
            GattWriteRequestType::Request => self.write_impl(handle, data),
            GattWriteRequestType::Prepare { offset } => {
                if !self.values.contains_key(&handle) {
                    return Err(AttErrorCode::InvalidHandle);
                }
                self.queued_writes.borrow_mut().entry(tcb_idx).or_default().push((
                    handle,
                    offset,
                    data.to_vec(),
                ));
                Ok(())
            }
        }
    }

    fn write_no_response(
        &self,
        _tcb_idx: TransportIndex,
        handle: AttHandle,
        attr_type: AttributeBackingType,
        data: &[u8],
    ) {
        assert_eq!(attr_type, AttributeBackingType::Characteristic);
        let _ = self.write_impl(handle, data);
    }

    async fn execute(
        &self,
        tcb_idx: TransportIndex,
        decision: TransactionDecision,
    ) -> Result<(), AttErrorCode> {
        if let Some(writes) = self.queued_writes.borrow_mut().remove(&tcb_idx) {
            if decision == TransactionDecision::Cancel {
                return Ok(());
            }
            for (handle, offset, data) in &writes {
                let value = self.values[handle].borrow();
                let offset = *offset as usize;
                if offset > value.len() {
                    return Err(AttErrorCode::InvalidOffset);
                }
                if offset + data.len() > value.len() {
                    return Err(AttErrorCode::InvalidAttributeValueLength);
                }
            }
            for (handle, offset, data) in writes {
                let offset = offset as usize;
                self.values[&handle].borrow_mut()[offset..offset + data.len()]
                    .copy_from_slice(&data);
            }
        }
        Ok(())
    }
}

/// Creates a new test database with the specified characteristics.
pub fn new_test_database(
    mut characteristics: Vec<(AttAttribute, Vec<u8>)>,
) -> SharedBox<GattDatabase> {
    let datastore = TestDatastore::new(
        characteristics.iter_mut().map(|(a, data)| (a.handle, std::mem::take(data))),
    );
    SharedBox::new(GattDatabase::with_characteristics(
        characteristics.into_iter().map(|(a, _)| a),
        datastore,
    ))
}
