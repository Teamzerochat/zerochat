# coturn TURN Server Setup (Oracle Cloud)

Quick setup guide for coturn on your Oracle Cloud free tier instance.

## Install coturn

```bash
sudo apt update
sudo apt install coturn -y
```

## Configure coturn

```bash
sudo nano /etc/turnserver.conf
```

Paste this configuration:

```ini
# Network
listening-port=3478
tls-listening-port=5349
listening-ip=0.0.0.0
external-ip=YOUR_ORACLE_PUBLIC_IP

# Authentication
realm=zerochat
server-name=zerochat
lt-cred-mech
user=zerochat:YOUR_SECRET_PASSWORD

# Security - NO STUN (relay only)
no-stun

# Logging
log-file=/var/log/turnserver.log
simple-log

# Performance
total-quota=100
max-bps=1000000
```

Replace:
- `YOUR_ORACLE_PUBLIC_IP` - Your server's public IP
- `YOUR_SECRET_PASSWORD` - A strong password

## Enable coturn Service

```bash
# Edit default config
sudo nano /etc/default/coturn
# Uncomment: TURNSERVER_ENABLED=1

# Start service
sudo systemctl enable coturn
sudo systemctl start coturn
sudo systemctl status coturn
```

## Open Oracle Cloud Ports

Add to Security List ingress rules:

| Source | Protocol | Port | Description |
|--------|----------|------|-------------|
| 0.0.0.0/0 | TCP | 3478 | TURN TCP |
| 0.0.0.0/0 | UDP | 3478 | TURN UDP |
| 0.0.0.0/0 | TCP | 5349 | TURN TLS |
| 0.0.0.0/0 | UDP | 49152-65535 | TURN relay range |

And Ubuntu firewall:
```bash
sudo ufw allow 3478/tcp
sudo ufw allow 3478/udp
sudo ufw allow 5349/tcp
sudo ufw allow 49152:65535/udp
```

## Test TURN Server

Use https://webrtc.github.io/samples/src/content/peerconnection/trickle-ice/ to test.

## Update App Config

In `WebRtcConfig.kt`, update:

```kotlin
WebRtcConfig(
    turnServerUrl = "turn:YOUR_ORACLE_PUBLIC_IP:3478",
    turnUsername = "zerochat",
    turnPassword = "YOUR_SECRET_PASSWORD"
)
```
