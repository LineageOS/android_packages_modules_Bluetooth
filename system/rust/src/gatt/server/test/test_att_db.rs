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
use std::collections::BTreeMap;
use std::rc::Rc;

struct TestDatastore {
    values: BTreeMap<AttHandle, RefCell<Vec<u8>>>,
}

impl TestDatastore {
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
        _tcb_idx: TransportIndex,
        handle: AttHandle,
        attr_type: AttributeBackingType,
        write_type: GattWriteRequestType,
        data: &[u8],
    ) -> Result<(), AttErrorCode> {
        assert_eq!(attr_type, AttributeBackingType::Characteristic);
        assert_eq!(write_type, GattWriteRequestType::Request);
        self.write_impl(handle, data)
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

    async fn execute(&self, _: TransportIndex, _: TransactionDecision) -> Result<(), AttErrorCode> {
        unreachable!();
    }
}

/// Creates a new test database with the specified characteristics.
pub fn new_test_database(
    mut characteristics: Vec<(AttAttribute, Vec<u8>)>,
) -> SharedBox<GattDatabase> {
    let datastore = Rc::new(TestDatastore {
        values: characteristics
            .iter_mut()
            .map(|(a, data)| (a.handle, RefCell::new(std::mem::take(data))))
            .collect(),
    });
    SharedBox::new(GattDatabase::with_characteristics(
        characteristics.into_iter().map(|(a, _)| a),
        datastore,
    ))
}
