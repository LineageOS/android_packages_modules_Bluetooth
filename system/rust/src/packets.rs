// Casing inherited from PDL
#![allow(non_snake_case)]
#![allow(non_camel_case_types)]
#![allow(warnings, missing_docs)]
#![allow(clippy::all)]
// this is now stable
#![feature(mixed_integer_ops)]

include!(concat!(env!("OUT_DIR"), "/_packets.rs"));

impl std::cmp::PartialEq for SerializeError {
    fn eq(&self, rhs: &Self) -> bool {
        std::mem::discriminant(self) == std::mem::discriminant(rhs)
    }
}
