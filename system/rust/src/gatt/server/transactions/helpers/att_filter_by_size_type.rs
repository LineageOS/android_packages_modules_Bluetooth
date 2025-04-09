//! This module extracts the common logic in filtering attributes by type +
//! length, used in READ_BY_TYPE_REQ and READ_BY_GROUP_TYPE_REQ

use crate::core::uuid::Uuid;
use crate::gatt::server::att_client::WeakAttClient;
use crate::gatt::server::att_database::AttAttribute;
use crate::packets::att::AttErrorCode;

/// An attribute and the value
#[derive(Debug, PartialEq, Eq)]
pub struct AttributeWithValue {
    /// The attribute
    pub attr: AttAttribute,
    pub value: Vec<u8>,
}

/// Takes a StableAttDatabase, a range of handles, a target type, and a
/// size limit.
///
/// Returns an iterator of attributes in the range and matching the type,
/// with the max number of elements such that each attribute has the same
/// size.
///
/// Attributes are truncated to the attr_size limit before size comparison.
/// If an error occurs while reading, do not output further attributes.
pub async fn filter_read_attributes_by_size_type(
    client: &WeakAttClient,
    attrs: impl IntoIterator<Item = &AttAttribute>,
    target: Uuid,
    size_limit: usize,
) -> Result<impl Iterator<Item = AttributeWithValue>, AttErrorCode> {
    let target_attrs = attrs.into_iter().filter(|attr| attr.type_ == target);

    let mut out = vec![];
    let mut curr_elem_size = None;

    for attr @ AttAttribute { handle, .. } in target_attrs {
        match client.read_attribute(*handle).await {
            Ok(mut value) => {
                value.truncate(size_limit);
                let value_size = value.len();
                if let Some(curr_elem_size) = curr_elem_size {
                    if curr_elem_size != value_size {
                        // no more attributes of the same size
                        break;
                    }
                } else {
                    curr_elem_size = Some(value_size)
                }

                out.push(AttributeWithValue { attr: *attr, value });
            }
            Err(err) => {
                if out.is_empty() {
                    return Err(err);
                }
                break;
            }
        }
    }

    Ok(out.into_iter())
}

#[cfg(test)]
mod test {
    use super::*;

    use crate::core::uuid::Uuid;
    use crate::gatt::ids::{AttHandle, TransportIndex};
    use crate::gatt::server::att_client::AttClient;
    use crate::gatt::server::att_database::AttAttribute;
    use crate::gatt::server::gatt_database::AttPermissions;
    use crate::gatt::server::test::test_att_db::new_test_database;

    const UUID: Uuid = Uuid::new(1234);
    const ANOTHER_UUID: Uuid = Uuid::new(2345);
    const TCB_IDX: TransportIndex = TransportIndex(1);

    #[test]
    fn test_single_matching_attr() {
        // arrange
        let db = new_test_database(vec![(
            AttAttribute {
                handle: AttHandle(3),
                type_: UUID,
                permissions: AttPermissions::READABLE,
            },
            vec![4, 5],
        )]);
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);

        // act
        let response: Vec<_> = tokio_test::block_on(async {
            filter_read_attributes_by_size_type(
                &client.downgrade(),
                &client.list_attributes(),
                UUID,
                31,
            )
            .await
            .unwrap()
            .collect()
        });

        // assert
        assert_eq!(
            response,
            vec![AttributeWithValue {
                attr: db.get(AttHandle(3)).map(|a| a.attribute).unwrap(),
                value: vec![4, 5],
            }]
        )
    }

    #[test]
    fn test_skip_mismatching_attrs() {
        // arrange
        let db = new_test_database(vec![
            (
                AttAttribute {
                    handle: AttHandle(3),
                    type_: UUID,
                    permissions: AttPermissions::READABLE,
                },
                vec![4, 5],
            ),
            (
                AttAttribute {
                    handle: AttHandle(5),
                    type_: ANOTHER_UUID,
                    permissions: AttPermissions::READABLE,
                },
                vec![5, 6],
            ),
            (
                AttAttribute {
                    handle: AttHandle(6),
                    type_: UUID,
                    permissions: AttPermissions::READABLE,
                },
                vec![6, 7],
            ),
        ]);
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);

        // act
        let response: Vec<_> = tokio_test::block_on(async {
            filter_read_attributes_by_size_type(
                &client.downgrade(),
                &client.list_attributes(),
                UUID,
                31,
            )
            .await
            .unwrap()
            .collect()
        });

        // assert
        assert_eq!(
            response,
            vec![
                AttributeWithValue {
                    attr: db.get(AttHandle(3)).map(|a| a.attribute).unwrap(),
                    value: vec![4, 5],
                },
                AttributeWithValue {
                    attr: db.get(AttHandle(6)).map(|a| a.attribute).unwrap(),
                    value: vec![6, 7],
                }
            ]
        );
    }

    #[test]
    fn test_stop_once_length_changes() {
        // arrange
        let db = new_test_database(vec![
            (
                AttAttribute {
                    handle: AttHandle(3),
                    type_: UUID,
                    permissions: AttPermissions::READABLE,
                },
                vec![4, 5],
            ),
            (
                AttAttribute {
                    handle: AttHandle(5),
                    type_: UUID,
                    permissions: AttPermissions::READABLE,
                },
                vec![5],
            ),
            (
                AttAttribute {
                    handle: AttHandle(7),
                    type_: UUID,
                    permissions: AttPermissions::READABLE,
                },
                vec![6, 7],
            ),
        ]);
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);

        // act
        let response: Vec<_> = tokio_test::block_on(async {
            filter_read_attributes_by_size_type(
                &client.downgrade(),
                &client.list_attributes(),
                UUID,
                31,
            )
            .await
            .unwrap()
            .collect()
        });

        // assert
        assert_eq!(
            response,
            vec![AttributeWithValue {
                attr: db.get(AttHandle(3)).map(|a| a.attribute).unwrap(),
                value: vec![4, 5],
            },]
        );
    }

    #[test]
    fn test_truncate_to_mtu() {
        // arrange: attr with data of length 3
        let db = new_test_database(vec![(
            AttAttribute {
                handle: AttHandle(3),
                type_: UUID,
                permissions: AttPermissions::READABLE,
            },
            vec![4, 5, 6],
        )]);
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);

        // act: read the attribute with max_size = 2
        let response: Vec<_> = tokio_test::block_on(async {
            filter_read_attributes_by_size_type(
                &client.downgrade(),
                &client.list_attributes(),
                UUID,
                2,
            )
            .await
            .unwrap()
            .collect()
        });

        // assert: the length of the read attribute is 2
        assert_eq!(
            response,
            vec![AttributeWithValue {
                attr: db.get(AttHandle(3)).map(|a| a.attribute).unwrap(),
                value: vec![4, 5],
            },]
        );
    }

    #[test]
    fn test_no_results() {
        // arrange: an empty database
        let db = new_test_database(vec![]);
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);

        // act
        let response: Vec<_> = tokio_test::block_on(async {
            filter_read_attributes_by_size_type(
                &client.downgrade(),
                &client.list_attributes(),
                UUID,
                31,
            )
            .await
            .unwrap()
            .collect()
        });

        // assert: no results
        assert!(response.is_empty())
    }

    #[test]
    fn test_read_failure_on_first_attr() {
        // arrange: put a non-readable attribute in the db with the right type
        let db = new_test_database(vec![(
            AttAttribute {
                handle: AttHandle(3),
                type_: UUID,
                permissions: AttPermissions::empty(),
            },
            vec![4, 5, 6],
        )]);
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);

        let weak_client = client.downgrade();
        let attrs = client.list_attributes();

        // act
        let response = tokio_test::block_on(filter_read_attributes_by_size_type(
            &weak_client,
            &attrs,
            UUID,
            31,
        ));

        // assert: got READ_NOT_PERMITTED
        assert!(matches!(response, Err(AttErrorCode::ReadNotPermitted)));
    }

    #[test]
    fn test_read_failure_on_subsequent_attr() {
        // arrange: put a non-readable attribute in the db with the right
        // type
        let db = new_test_database(vec![
            (
                AttAttribute {
                    handle: AttHandle(3),
                    type_: UUID,
                    permissions: AttPermissions::READABLE,
                },
                vec![4, 5, 6],
            ),
            (
                AttAttribute {
                    handle: AttHandle(4),
                    type_: UUID,
                    permissions: AttPermissions::empty(),
                },
                vec![5, 6, 7],
            ),
            (
                AttAttribute {
                    handle: AttHandle(5),
                    type_: UUID,
                    permissions: AttPermissions::READABLE,
                },
                vec![8, 9, 10],
            ),
        ]);
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);

        // act
        let response: Vec<_> = tokio_test::block_on(async {
            filter_read_attributes_by_size_type(
                &client.downgrade(),
                &client.list_attributes(),
                UUID,
                31,
            )
            .await
            .unwrap()
            .collect()
        });

        // assert: we reply with the first attribute, but not the second or third
        // (since we stop on the first failure)
        assert_eq!(
            response,
            vec![AttributeWithValue {
                attr: db.get(AttHandle(3)).map(|a| a.attribute).unwrap(),
                value: vec![4, 5, 6],
            },]
        );
    }

    #[test]
    fn test_skip_unreadable_mismatching_attr() {
        // arrange: put a non-readable attribute in the db with the wrong type
        // between two attributes of interest
        let db = new_test_database(vec![
            (
                AttAttribute {
                    handle: AttHandle(3),
                    type_: UUID,
                    permissions: AttPermissions::READABLE,
                },
                vec![4, 5, 6],
            ),
            (
                AttAttribute {
                    handle: AttHandle(4),
                    type_: ANOTHER_UUID,
                    permissions: AttPermissions::empty(),
                },
                vec![5, 6, 7],
            ),
            (
                AttAttribute {
                    handle: AttHandle(5),
                    type_: UUID,
                    permissions: AttPermissions::READABLE,
                },
                vec![6, 7, 8],
            ),
        ]);
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);

        // act
        let response: Vec<_> = tokio_test::block_on(async {
            filter_read_attributes_by_size_type(
                &client.downgrade(),
                &client.list_attributes(),
                UUID,
                31,
            )
            .await
            .unwrap()
            .collect()
        });

        // assert: we reply with the first and third attributes, but not the second
        assert_eq!(
            response,
            vec![
                AttributeWithValue {
                    attr: db.get(AttHandle(3)).map(|a| a.attribute).unwrap(),
                    value: vec![4, 5, 6],
                },
                AttributeWithValue {
                    attr: db.get(AttHandle(5)).map(|a| a.attribute).unwrap(),
                    value: vec![6, 7, 8],
                }
            ]
        );
    }
}
