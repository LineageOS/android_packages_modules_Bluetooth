use crate::gatt::server::att_client::WeakAttClient;
use crate::gatt::server::att_database::AttAttribute;
use crate::packets::att::{self, AttErrorCode};
use pdl_runtime::EncodeError;

use super::helpers::att_range_filter::filter_to_range;
use super::helpers::payload_accumulator::PayloadAccumulator;

pub fn handle_find_information_request(
    request: att::AttFindInformationRequest,
    mtu: usize,
    client: &WeakAttClient,
) -> Result<att::Att, EncodeError> {
    let attrs = client.list_attributes();
    let Some(attrs) = filter_to_range(
        request.starting_handle.clone().into(),
        request.ending_handle.into(),
        attrs.iter(),
    ) else {
        return att::AttErrorResponse {
            opcode_in_error: att::AttOpcode::FindInformationRequest,
            handle_in_error: request.starting_handle.clone(),
            error_code: AttErrorCode::InvalidHandle,
        }
        .try_into();
    };

    if let Some(resp) = handle_find_information_request_short(attrs.clone(), mtu) {
        resp.try_into()
    } else if let Some(resp) = handle_find_information_request_long(attrs, mtu) {
        resp.try_into()
    } else {
        att::AttErrorResponse {
            opcode_in_error: att::AttOpcode::FindInformationRequest,
            handle_in_error: request.starting_handle,
            error_code: AttErrorCode::AttributeNotFound,
        }
        .try_into()
    }
}

/// Returns a builder IF we can return at least one attribute, otherwise returns
/// None
fn handle_find_information_request_short<'a>(
    attributes: impl Iterator<Item = &'a AttAttribute>,
    mtu: usize,
) -> Option<att::AttFindInformationShortResponse> {
    // Core Spec 5.3 Vol 3F 3.4.3.2 gives the ATT_MTU - 2 limit
    let mut out = PayloadAccumulator::new(mtu - 2);
    for AttAttribute { handle, type_: uuid, .. } in attributes {
        if let Ok(uuid) = (*uuid).try_into() {
            if out
                .push(att::AttFindInformationResponseShortEntry { handle: (*handle).into(), uuid })
            {
                // If we successfully pushed a 16-bit UUID, continue. In all other cases, we
                // should break.
                continue;
            }
        }
        break;
    }

    if out.is_empty() {
        None
    } else {
        Some(att::AttFindInformationShortResponse { data: out.into_vec() })
    }
}

fn handle_find_information_request_long<'a>(
    attributes: impl Iterator<Item = &'a AttAttribute>,
    mtu: usize,
) -> Option<att::AttFindInformationLongResponse> {
    // Core Spec 5.3 Vol 3F 3.4.3.2 gives the ATT_MTU - 2 limit
    let mut out = PayloadAccumulator::new(mtu - 2);

    for AttAttribute { handle, type_: uuid, .. } in attributes {
        if !out.push(att::AttFindInformationResponseLongEntry {
            handle: (*handle).into(),
            uuid: (*uuid).into(),
        }) {
            break;
        }
    }

    if out.is_empty() {
        None
    } else {
        Some(att::AttFindInformationLongResponse { data: out.into_vec() })
    }
}

#[cfg(test)]
mod test {
    use crate::core::uuid::Uuid;
    use crate::gatt::ids::TransportIndex;
    use crate::gatt::server::att_client::AttClient;
    use crate::gatt::server::gatt_database::AttPermissions;
    use crate::gatt::server::test::test_att_db::new_test_database;
    use crate::gatt::server::AttHandle;
    use crate::packets::att;

    use super::*;

    const TCB_IDX: TransportIndex = TransportIndex(1);

    #[test]
    fn test_long_uuids() {
        // arrange
        let db = new_test_database(vec![
            (
                AttAttribute {
                    handle: AttHandle(3),
                    type_: Uuid::new(0x01020304),
                    permissions: AttPermissions::READABLE,
                },
                vec![4, 5],
            ),
            (
                AttAttribute {
                    handle: AttHandle(4),
                    type_: Uuid::new(0x01020305),
                    permissions: AttPermissions::READABLE,
                },
                vec![4, 5],
            ),
            (
                AttAttribute {
                    handle: AttHandle(5),
                    type_: Uuid::new(0x01020306),
                    permissions: AttPermissions::READABLE,
                },
                vec![4, 5],
            ),
        ]);
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);

        // act
        let att_view = att::AttFindInformationRequest {
            starting_handle: AttHandle(3).into(),
            ending_handle: AttHandle(4).into(),
        };
        let response = handle_find_information_request(att_view, 128, &client.downgrade());

        // assert
        assert_eq!(
            response,
            att::AttFindInformationLongResponse {
                data: vec![
                    att::AttFindInformationResponseLongEntry {
                        handle: AttHandle(3).into(),
                        uuid: Uuid::new(0x01020304).into(),
                    },
                    att::AttFindInformationResponseLongEntry {
                        handle: AttHandle(4).into(),
                        uuid: Uuid::new(0x01020305).into(),
                    }
                ]
            }
            .try_into()
        );
    }

    #[test]
    fn test_short_uuids() {
        // arrange
        let db = new_test_database(vec![
            (
                AttAttribute {
                    handle: AttHandle(3),
                    type_: Uuid::new(0x0102),
                    permissions: AttPermissions::READABLE,
                },
                vec![4, 5],
            ),
            (
                AttAttribute {
                    handle: AttHandle(4),
                    type_: Uuid::new(0x0103),
                    permissions: AttPermissions::READABLE,
                },
                vec![4, 5],
            ),
            (
                AttAttribute {
                    handle: AttHandle(5),
                    type_: Uuid::new(0x01020306),
                    permissions: AttPermissions::READABLE,
                },
                vec![4, 5],
            ),
        ]);
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);

        // act
        let att_view = att::AttFindInformationRequest {
            starting_handle: AttHandle(3).into(),
            ending_handle: AttHandle(5).into(),
        };
        let response = handle_find_information_request(att_view, 128, &client.downgrade());

        // assert
        assert_eq!(
            response,
            att::AttFindInformationShortResponse {
                data: vec![
                    att::AttFindInformationResponseShortEntry {
                        handle: AttHandle(3).into(),
                        uuid: Uuid::new(0x0102).try_into().unwrap(),
                    },
                    att::AttFindInformationResponseShortEntry {
                        handle: AttHandle(4).into(),
                        uuid: Uuid::new(0x0103).try_into().unwrap(),
                    }
                ]
            }
            .try_into()
        );
    }

    #[test]
    fn test_handle_validation() {
        // arrange: empty db
        let db = new_test_database(vec![]);
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);

        // act: use an invalid handle range
        let att_view = att::AttFindInformationRequest {
            starting_handle: AttHandle(3).into(),
            ending_handle: AttHandle(2).into(),
        };
        let response = handle_find_information_request(att_view, 128, &client.downgrade());

        // assert: got INVALID_HANDLE
        assert_eq!(
            response,
            att::AttErrorResponse {
                opcode_in_error: att::AttOpcode::FindInformationRequest,
                handle_in_error: AttHandle(3).into(),
                error_code: AttErrorCode::InvalidHandle,
            }
            .try_into()
        );
    }

    #[test]
    fn test_limit_total_size() {
        // arrange
        let db = new_test_database(vec![
            (
                AttAttribute {
                    handle: AttHandle(3),
                    type_: Uuid::new(0x0102),
                    permissions: AttPermissions::READABLE,
                },
                vec![4, 5],
            ),
            (
                AttAttribute {
                    handle: AttHandle(4),
                    type_: Uuid::new(0x0103),
                    permissions: AttPermissions::READABLE,
                },
                vec![4, 5],
            ),
        ]);
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);

        // act: use MTU = 6, so only one entry can fit
        let att_view = att::AttFindInformationRequest {
            starting_handle: AttHandle(3).into(),
            ending_handle: AttHandle(5).into(),
        };
        let response = handle_find_information_request(att_view, 6, &client.downgrade());

        // assert: only one entry (not two) provided
        assert_eq!(
            response,
            att::AttFindInformationShortResponse {
                data: vec![att::AttFindInformationResponseShortEntry {
                    handle: AttHandle(3).into(),
                    uuid: Uuid::new(0x0102).try_into().unwrap(),
                },]
            }
            .try_into()
        );
    }

    #[test]
    fn test_empty_output() {
        // arrange
        let db = new_test_database(vec![(
            AttAttribute {
                handle: AttHandle(3),
                type_: Uuid::new(0x0102),
                permissions: AttPermissions::READABLE,
            },
            vec![4, 5],
        )]);
        let (client, _) = AttClient::new_test_client(TCB_IDX, &db);

        // act: use a range that matches no attributes
        let att_view = att::AttFindInformationRequest {
            starting_handle: AttHandle(4).into(),
            ending_handle: AttHandle(5).into(),
        };
        let response = handle_find_information_request(att_view, 6, &client.downgrade());

        // assert: got ATTRIBUTE_NOT_FOUND
        assert_eq!(
            response,
            att::AttErrorResponse {
                opcode_in_error: att::AttOpcode::FindInformationRequest,
                handle_in_error: AttHandle(4).into(),
                error_code: AttErrorCode::AttributeNotFound,
            }
            .try_into()
        );
    }
}
