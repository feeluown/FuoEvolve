use std::env;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;

fn main() {
    let manifest_dir = PathBuf::from(env::var_os("CARGO_MANIFEST_DIR").expect("CARGO_MANIFEST_DIR"));
    let fingerprint_dir = manifest_dir
        .parent()
        .expect("native directory")
        .join("audio-fingerprint");
    let wasm_source = manifest_dir.join(
        "../../../shared/src/commonMain/resources/audio_recognition/afp.wasm",
    );

    println!("cargo:rerun-if-changed={}", fingerprint_dir.join("main.go").display());
    println!("cargo:rerun-if-changed={}", fingerprint_dir.join("go.mod").display());
    println!("cargo:rerun-if-changed={}", wasm_source.display());

    let staging_dir = PathBuf::from(env::var_os("OUT_DIR").expect("OUT_DIR"))
        .join("audio-fingerprint-go");
    if staging_dir.exists() {
        fs::remove_dir_all(&staging_dir).expect("remove old fingerprint staging directory");
    }
    fs::create_dir_all(&staging_dir).expect("create fingerprint staging directory");
    copy(&fingerprint_dir.join("main.go"), &staging_dir.join("main.go"));
    copy(&fingerprint_dir.join("go.mod"), &staging_dir.join("go.mod"));
    let go_sum = fingerprint_dir.join("go.sum");
    if go_sum.is_file() {
        copy(&go_sum, &staging_dir.join("go.sum"));
        println!("cargo:rerun-if-changed={}", go_sum.display());
    }
    copy(&wasm_source, &staging_dir.join("afp.wasm"));

    // Resolve checksums in the disposable staging module before compiling. Go 1.25
    // no longer lets `go build` silently populate missing go.sum entries in this setup.
    let status = Command::new("go")
        .current_dir(&staging_dir)
        .arg("mod")
        .arg("tidy")
        .status()
        .expect("failed to resolve Go modules; Go 1.22+ is required for desktop packaging");
    if !status.success() {
        panic!("failed to resolve headless audio fingerprint helper dependencies");
    }

    let executable_name = if env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("windows") {
        "fuoevolve-web-login.exe"
    } else {
        "fuoevolve-web-login"
    };
    let profile = env::var("PROFILE").unwrap_or_else(|_| "release".to_string());
    let executable = manifest_dir.join("target").join(profile).join(executable_name);
    fs::create_dir_all(executable.parent().expect("fingerprint output parent"))
        .expect("create fingerprint output directory");

    let status = Command::new("go")
        .current_dir(&staging_dir)
        .env("CGO_ENABLED", "0")
        .arg("build")
        .arg("-trimpath")
        .arg("-ldflags=-s -w")
        .arg("-o")
        .arg(&executable)
        .arg(".")
        .status()
        .expect("failed to start Go compiler; Go 1.22+ is required for desktop packaging");
    if !status.success() {
        panic!("failed to build headless audio fingerprint helper");
    }

    let status = Command::new(&executable)
        .arg("--self-test")
        .status()
        .expect("failed to run audio fingerprint helper self-test");
    if !status.success() {
        panic!("audio fingerprint helper self-test failed");
    }
}

fn copy(source: &Path, destination: &Path) {
    fs::copy(source, destination).unwrap_or_else(|error| {
        panic!(
            "failed to copy {} to {}: {error}",
            source.display(),
            destination.display(),
        )
    });
}
