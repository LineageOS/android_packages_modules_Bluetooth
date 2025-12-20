//! Build file to generate packets
//!
//! Run `cargo install --path .` in `external/rust/crates/pdl-compiler` to ensure `pdlc`
//! is in your path.
use std::env;
use std::fs::File;
use std::io::Write;
use std::path::Path;

fn main() {
    let out_dir = env::var_os("OUT_DIR").unwrap();
    let dest_path = Path::new(&out_dir).join("_packets.rs");
    let mut dest_file = File::create(dest_path).unwrap();

    let mut sources = pdl_compiler::ast::SourceDatabase::new();
    let file = pdl_compiler::parser::parse_file(&mut sources, "src/packets.pdl")
        .expect("failed to parse input pdl file");

    let generated = pdl_compiler::backends::rust::generate(&sources, &file, &[]);
    dest_file.write_all(generated.as_bytes()).unwrap();

    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-changed=src/packets.pdl");
}
