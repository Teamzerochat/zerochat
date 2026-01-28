# Rust Toolchain Setup for Android

## Prerequisites: Visual Studio Build Tools

Rust on Windows requires the MSVC linker. Install Visual Studio Build Tools:

1. Download from: https://visualstudio.microsoft.com/visual-cpp-build-tools/
2. Run installer and select **"Desktop development with C++"**
3. Complete installation and restart terminal

## Prerequisites: Windows Defender Exclusion

Rust builds can fail with "file in use" errors due to real-time scanning. Add exclusions:

1. Open Windows Security → Virus & threat protection
2. Under "Virus & threat protection settings", click **Manage settings**
3. Scroll to "Exclusions" and click **Add or remove exclusions**
4. Add folder exclusions for:
   - `C:\Users\hp\.cargo`
   - `C:\Users\hp\StudioProjects\zerochat\nym-transport\target`

## Step 1: Install Rust

Run this in PowerShell (downloads rustup installer):

```powershell
# Download and run rustup installer
Invoke-WebRequest -Uri https://win.rustup.rs/x86_64 -OutFile rustup-init.exe
.\rustup-init.exe -y
```

**After installation, RESTART your terminal/IDE to load PATH.**

## Step 2: Add Android NDK Targets

```powershell
# Add cross-compilation targets for Android
rustup target add aarch64-linux-android   # ARM64 (most devices)
rustup target add armv7-linux-androideabi # ARM32 (older devices)
rustup target add x86_64-linux-android    # x86_64 (emulators)
```

## Step 3: Install cargo-ndk

```powershell
cargo install cargo-ndk
```

## Step 4: Set Android NDK Path

Add to your environment (or PowerShell profile):

```powershell
$env:ANDROID_NDK_HOME = "C:\Users\hp\AppData\Local\Android\Sdk\ndk\<version>"
```

Replace `<version>` with your NDK version (check folder exists).

## Verification

```powershell
rustc --version
cargo --version
cargo ndk --version
```

---

**After completing these steps, let me know and I'll continue with the NYM transport implementation.**
