# Real NYM Integration Guide

## Overview

Current state: **Stub transport** (mock mode)  
Target state: **Real mixnet communication**

---

## Part 1: Integrate Real NYM SDK (Rust Side)

### Step 1: Update Cargo.toml

```toml
[dependencies]
nym-sdk = "1.20"
tokio = { version = "1.0", features = ["rt-multi-thread", "sync"] }
uniffi = { version = "0.25", features = ["cli"] }
thiserror = "1.0"
```

### Step 2: Update lib.rs with Real NYM Client

Replace the stub implementation with:

```rust
use nym_sdk::mixnet::{MixnetClient, MixnetClientBuilder, Recipient};
use std::sync::Arc;
use tokio::runtime::Runtime;

pub struct NymTransportClient {
    runtime: Runtime,
    client: Option<Arc<MixnetClient>>,
    gateway_url: Mutex<Option<String>>,
}

impl NymTransportClient {
    pub fn connect(&self, gateway_url: String) -> Result<(), TransportError> {
        let client = self.runtime.block_on(async {
            MixnetClientBuilder::new()
                .network_details_from_env()  // Or configure manually
                .build()?
                .connect_to_mixnet()
                .await
        }).map_err(|e| TransportError::ConnectionFailed { 
            reason: e.to_string() 
        })?;
        
        self.client = Some(Arc::new(client));
        Ok(())
    }
    
    pub fn send_message(&self, recipient: &[u8], data: &[u8]) -> Result<(), TransportError> {
        let client = self.client.as_ref()
            .ok_or(TransportError::NotConnected)?;
            
        let recipient = Recipient::try_from_bytes(recipient)
            .map_err(|_| TransportError::SendFailed { 
                reason: "Invalid recipient".into() 
            })?;
            
        self.runtime.block_on(async {
            client.send_plain_message(recipient, data).await
        }).map_err(|e| TransportError::SendFailed { 
            reason: e.to_string() 
        })?;
        
        Ok(())
    }
}
```

### Step 3: Rebuild Native Lib

```powershell
cd nym-transport
$env:ANDROID_NDK_HOME = "C:\Users\hp\AppData\Local\Android\Sdk\ndk\26.3.11579264"
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -o ..\app\src\main\jniLibs build --release
```

---

## Part 2: NYM Gateway Infrastructure

### Option A: Use Public NYM Network (Easiest)

NYM has a public mixnet. Configure your client to use it:

```rust
MixnetClientBuilder::new()
    .network_details_from_env()  // Uses mainnet by default
    .build()?
```

**Pros:** No infrastructure to maintain  
**Cons:** Less control, potential latency

### Option B: Self-Hosted Gateway (Maximum Privacy)

#### Requirements:
- Linux server (Ubuntu 22.04 recommended)
- Static public IP
- Open ports: 1789, 1790, 8080, 9000

#### Install NYM Binaries:

```bash
# On your Linux server
curl -L https://github.com/nymtech/nym/releases/latest/download/nym-gateway -o nym-gateway
chmod +x nym-gateway

# Initialize gateway
./nym-gateway init --id my-gateway

# Run gateway
./nym-gateway run --id my-gateway
```

#### Configure App to Use Your Gateway:

```rust
MixnetClientBuilder::new()
    .custom_gateway("ws://your-server-ip:9000")
    .build()?
```

---

## Part 3: Enable Real Transport in App

In `AppModule.kt`, switch:

```kotlin
fun provideNymTransport(): NymTransport {
    return RealNymTransport()  // Enable real transport
}
```

---

## Architecture Summary

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│   ZeroChat      │────▶│   NYM Gateway   │────▶│   NYM Mixnet    │
│   (Android)     │     │   (Your Server) │     │   (Public Net)  │
└─────────────────┘     └─────────────────┘     └─────────────────┘
         │                                               │
         └───────────── Encrypted via Mix ──────────────┘
```

---

## Next Steps

1. [ ] Decide: Public NYM or Self-hosted gateway?
2. [ ] If self-hosted: Set up Linux server with NYM gateway
3. [ ] Update `nym-transport/src/lib.rs` with real NYM SDK code
4. [ ] Rebuild native libs
5. [ ] Switch `AppModule.kt` to `RealNymTransport`
6. [ ] Test end-to-end messaging

## Part 4: Oracle Cloud Free Tier Setup

Oracle offers **always-free** ARM instances perfect for NYM gateway.

### Step 1: Create Oracle Cloud Account
1. Go to https://www.oracle.com/cloud/free/
2. Sign up (requires credit card for verification, but free tier is truly free)
3. Select your home region (choose closest to your users)

### Step 2: Create VM Instance
1. Go to **Compute** → **Instances** → **Create Instance**
2. Configure:
   - **Name:** `nym-gateway`
   - **Image:** Ubuntu 22.04 (Canonical)
   - **Shape:** VM.Standard.A1.Flex (ARM) - **Always Free**
     - 1 OCPU, 6GB RAM (or up to 4 OCPU/24GB within free limits)
   - **Networking:** Create new VCN with public subnet
   - **Add SSH keys:** Upload your public key or generate new

3. Click **Create**

### Step 3: Configure Security List (Firewall)
1. Go to **Networking** → **Virtual Cloud Networks** → Your VCN
2. Click **Security Lists** → **Default Security List**
3. Add **Ingress Rules**:

| Source CIDR   | Protocol | Port Range | Description      |
|---------------|----------|------------|------------------|
| 0.0.0.0/0     | TCP      | 1789       | NYM Mixnet       |
| 0.0.0.0/0     | TCP      | 1790       | NYM Mixnet       |
| 0.0.0.0/0     | TCP      | 9000       | NYM WebSocket    |
| 0.0.0.0/0     | TCP      | 8080       | NYM HTTP API     |

### Step 4: SSH and Install NYM Gateway

```bash
# SSH into your instance
ssh ubuntu@<your-public-ip>

# Update system
sudo apt update && sudo apt upgrade -y

# Download NYM gateway (ARM64 version for Oracle free tier)
wget https://github.com/nymtech/nym/releases/latest/download/nym-gateway_linux_aarch64
chmod +x nym-gateway_linux_aarch64
sudo mv nym-gateway_linux_aarch64 /usr/local/bin/nym-gateway

# Initialize gateway
nym-gateway init --id my-gateway --host <your-public-ip>

# Open ports in Ubuntu firewall
sudo ufw allow 1789/tcp
sudo ufw allow 1790/tcp
sudo ufw allow 9000/tcp
sudo ufw allow 8080/tcp
sudo ufw enable

# Run gateway (foreground for testing)
nym-gateway run --id my-gateway
```

### Step 5: Run as Systemd Service

```bash
# Create service file
sudo nano /etc/systemd/system/nym-gateway.service
```

Paste:
```ini
[Unit]
Description=NYM Gateway
After=network.target

[Service]
Type=simple
User=ubuntu
ExecStart=/usr/local/bin/nym-gateway run --id my-gateway
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

Enable and start:
```bash
sudo systemctl daemon-reload
sudo systemctl enable nym-gateway
sudo systemctl start nym-gateway
sudo systemctl status nym-gateway
```

### Step 6: Get Your Gateway Address

```bash
cat ~/.nym/gateways/my-gateway/data/gateway_identity
```

Use this in your app: `ws://<your-public-ip>:9000`

---

## Resources

- [NYM SDK Docs](https://nymtech.net/docs/sdk/rust.html)
- [NYM Gateway Setup](https://nymtech.net/docs/nodes/gateways.html)
- [NYM GitHub Releases](https://github.com/nymtech/nym/releases)
