fn main() {
    // A few dynamic links
    println!("cargo:rustc-link-lib=dylib=flatbuffers");
    println!("cargo:rustc-link-lib=dylib=protobuf");
    println!("cargo:rustc-link-lib=dylib=resolv");
    println!("cargo:rustc-link-lib=dylib=lc3");
    println!("cargo:rustc-link-lib=dylib=fmt");
    println!("cargo:rustc-link-lib=dylib=crypto");

    println!("cargo:rerun-if-changed=build.rs");
}
