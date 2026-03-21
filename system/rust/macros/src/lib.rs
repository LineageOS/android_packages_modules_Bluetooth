//
// Copyright (C) 2026 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//

//! Procedural macros for the Bluetooth Rust stack.

extern crate proc_macro;

use proc_macro::TokenStream;
use quote::quote;
use syn::{parse_macro_input, Error, Expr, ExprLit, Fields, ItemStruct, Lit, Meta};

/// Procedural macro attribute to generate boilerplate code for Bluetooth handles and identifiers
/// defined in the HCI specification (e.g., Connection handles, Sync handles, Advertising handles).
///
/// ### The `mask` parameter:
/// This attribute supports an optional `mask` parameter to handle non-standard bit widths or
/// Reserved for Future Use (RFU) bits, which are common in the Bluetooth Core Specification.
///
/// - **Without `mask`**: The macro implements `From<inner_ty>`.
/// - **With `mask` (e.g., `mask = 0x0EFF`)**:
///     - **Validation (`TryFrom`)**: Implements `TryFrom<inner_ty>`, which returns an error if any
///       bits outside the mask are set. This ensures protocol compliance by rejecting invalid
///       data.
///     - **Explicit Truncation (`from_masked`)**: Provides a `from_masked(val)` associated
///       function to explicitly apply the mask. This is used when ignoring RFU bits is
///       intentionally required.
///
/// ### Generated Implementations:
/// - `From<Self> -> u8/u16`: For easy access to the underlying raw value.
/// - `std::fmt::Display`: For standardized logging and debugging output.
///
/// Example: `#[bt_handle(mask = 0x0EFF)] struct ConnHandle(u16);`
#[proc_macro_attribute]
pub fn bt_handle(attr: TokenStream, item: TokenStream) -> TokenStream {
    let input = parse_macro_input!(item as ItemStruct);
    let name = &input.ident;

    // Retrieve the inner type of the tuple struct (expecting exactly one field).
    let inner_ty = match &input.fields {
        Fields::Unnamed(f) if f.unnamed.len() == 1 => &f.unnamed[0].ty,
        _ => {
            return Error::new_spanned(
                &input.fields,
                "bt_handle macro only supports tuple structs with exactly one field",
            )
            .into_compile_error()
            .into();
        }
    };

    // Parse the optional mask from attributes.
    let mut mask_token = None;
    if !attr.is_empty() {
        let attr_meta = parse_macro_input!(attr as Meta);
        match attr_meta {
            Meta::NameValue(name_val) if name_val.path.is_ident("mask") => {
                if let Expr::Lit(ExprLit { lit: Lit::Int(lit_int), .. }) = name_val.value {
                    mask_token = Some(quote! { #lit_int });
                } else {
                    return Error::new_spanned(
                        &name_val.value,
                        "mask value must be an integer literal",
                    )
                    .into_compile_error()
                    .into();
                }
            }
            _ => {
                return Error::new_spanned(
                    attr_meta,
                    "unsupported attribute; only 'mask = ...' is supported",
                )
                .into_compile_error()
                .into();
            }
        }
    }

    let core_impls = quote! {
        impl From<#name> for #inner_ty {
            #[inline]
            fn from(handle: #name) -> #inner_ty {
                handle.0
            }
        }

        impl std::fmt::Display for #name {
            fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
                write!(f, "{}", self.0)
            }
        }
    };

    let conversion_impl = if let Some(mask) = mask_token {
        quote! {
            impl std::convert::TryFrom<#inner_ty> for #name {
                type Error = #inner_ty;
                #[inline]
                fn try_from(val: #inner_ty) -> std::result::Result<Self, Self::Error> {
                    if (val & !#mask) == 0 {
                        Ok(Self(val))
                    } else {
                        Err(val)
                    }
                }
            }

            impl #name {
                /// Creates a new handle by explicitly applying a bitmask.
                #[inline]
                pub fn from_masked(val: #inner_ty) -> Self {
                    Self(val & #mask)
                }
            }
        }
    } else {
        quote! {
            impl From<#inner_ty> for #name {
                #[inline]
                fn from(val: #inner_ty) -> Self {
                    Self(val)
                }
            }
        }
    };

    let expanded = quote! {
        #input
        #core_impls
        #conversion_impl
    };

    TokenStream::from(expanded)
}
