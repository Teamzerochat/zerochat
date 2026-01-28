# NYM Transport Build Script
# Run after installing Rust toolchain (see docs/RUST_SETUP.md)

# Set Android NDK path
$env:ANDROID_NDK_HOME = "C:\Users\hp\AppData\Local\Android\Sdk\ndk\<VERSION>"

Write-Host "Building NYM transport for Android..."

# Navigate to Rust crate
Set-Location nym-transport

# Build for all Android architectures
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -o ../app/src/main/jniLibs build --release

# Generate Kotlin bindings
cargo run --bin uniffi-bindgen generate src/lib.rs --language kotlin --out-dir ../app/src/main/java/com/zerochat/app/nym_ffi

Write-Host "Done! Native libs in app/src/main/jniLibs"
Write-Host "Kotlin bindings in app/src/main/java/com/zerochat/app/nym_ffi"

Set-Location ..
