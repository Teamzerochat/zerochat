# Server Requirements for Nym Integration and TURN Server

This document outlines the complete server infrastructure requirements for deploying ZeroChat with Nym mixnet integration and TURN server capabilities.

---

## Executive Summary

ZeroChat requires two types of server infrastructure:
1. **Nym Gateway Server** - For privacy-preserving mixnet communication
2. **TURN Server** - For WebRTC NAT traversal and relay functionality

Both can be hosted on **Oracle Cloud Free Tier** (recommended for cost-effectiveness).

---

## 1. Nym Gateway Server Requirements

### 1.1 Hardware Specifications

#### Minimum Requirements
- **CPU:** 1 vCPU (ARM64 or x86_64)
- **RAM:** 2GB
- **Storage:** 20GB SSD
- **Network:** 100 Mbps bandwidth

#### Recommended Requirements (Oracle Cloud Free Tier)
- **CPU:** 1-4 OCPU (ARM-based VM.Standard.A1.Flex)
- **RAM:** 6-24GB (within free tier limits)
- **Storage:** 50GB+ SSD
- **Network:** Static public IP with unlimited bandwidth

### 1.2 Operating System
- **Recommended:** Ubuntu 22.04 LTS (ARM64 for Oracle Free Tier)
- **Alternatives:** Ubuntu 20.04, Debian 11+, CentOS 8+

### 1.3 Network Requirements

#### Required Open Ports
| Port | Protocol | Purpose | Direction |
|------|----------|---------|-----------|
| 1789 | TCP | Nym Mixnet Communication | Inbound |
| 1790 | TCP | Nym Mixnet Communication | Inbound |
| 8080 | TCP | Nym HTTP API | Inbound |
| 9000 | TCP | Nym WebSocket Gateway | Inbound |
| 22 | TCP | SSH Management | Inbound |

#### Firewall Configuration
```bash
# Ubuntu UFW
sudo ufw allow 1789/tcp
sudo ufw allow 1790/tcp
sudo ufw allow 8080/tcp
sudo ufw allow 9000/tcp
sudo ufw allow 22/tcp
sudo ufw enable
```

#### Oracle Cloud Security List Rules
- **Source CIDR:** 0.0.0.0/0
- **Protocols:** TCP
- **Ports:** 1789, 1790, 8080, 9000

### 1.4 Software Dependencies
- **Nym Gateway Binary:** Latest stable release from [Nym GitHub](https://github.com/nymtech/nym/releases)
- **Architecture:** ARM64 (for Oracle Free Tier) or x86_64
- **Runtime:** No additional dependencies (statically compiled binary)

### 1.5 Estimated Costs

#### Oracle Cloud Free Tier (Recommended)
- **Cost:** $0/month (Always Free)
- **Limits:** 
  - Up to 4 ARM-based Ampere A1 cores
  - Up to 24GB RAM
  - 200GB block storage
  - 10TB outbound data transfer/month

#### Alternative Cloud Providers
| Provider | Instance Type | Monthly Cost |
|----------|---------------|--------------|
| AWS | t3.small (2 vCPU, 2GB RAM) | ~$15-20 |
| DigitalOcean | Basic Droplet (1 vCPU, 2GB RAM) | $12 |
| Vultr | Cloud Compute (1 vCPU, 2GB RAM) | $10 |
| Hetzner | CX11 (1 vCPU, 2GB RAM) | €4.15 (~$4.50) |

---

## 2. TURN Server Requirements

### 2.1 Hardware Specifications

#### Minimum Requirements
- **CPU:** 1 vCPU
- **RAM:** 1GB
- **Storage:** 10GB
- **Network:** 100 Mbps bandwidth

#### Recommended Requirements (Production)
- **CPU:** 2+ vCPU
- **RAM:** 4GB+ (scales with concurrent users)
- **Storage:** 20GB SSD
- **Network:** 1 Gbps bandwidth, unlimited data transfer

### 2.2 Operating System
- **Recommended:** Ubuntu 22.04 LTS
- **Alternatives:** Ubuntu 20.04, Debian 11+, CentOS 8+

### 2.3 Network Requirements

#### Required Open Ports
| Port Range | Protocol | Purpose | Direction |
|------------|----------|---------|-----------|
| 3478 | TCP/UDP | TURN Server | Inbound |
| 5349 | TCP | TURN over TLS | Inbound |
| 49152-65535 | UDP | TURN Relay Range | Inbound |
| 22 | TCP | SSH Management | Inbound |

#### Firewall Configuration
```bash
# Ubuntu UFW
sudo ufw allow 3478/tcp
sudo ufw allow 3478/udp
sudo ufw allow 5349/tcp
sudo ufw allow 49152:65535/udp
sudo ufw allow 22/tcp
sudo ufw enable
```

#### Oracle Cloud Security List Rules
| Source CIDR | Protocol | Port Range | Description |
|-------------|----------|------------|-------------|
| 0.0.0.0/0 | TCP | 3478 | TURN TCP |
| 0.0.0.0/0 | UDP | 3478 | TURN UDP |
| 0.0.0.0/0 | TCP | 5349 | TURN TLS |
| 0.0.0.0/0 | UDP | 49152-65535 | TURN Relay Range |

### 2.4 Software Dependencies
- **coturn:** Latest stable version (apt package)
- **OpenSSL:** For TLS support (optional but recommended)

### 2.5 Bandwidth Considerations

#### Estimated Bandwidth Usage per User
- **Audio only:** ~50-100 Kbps
- **Video (720p):** ~1-2 Mbps
- **Video (1080p):** ~2-4 Mbps

#### Concurrent User Capacity
| RAM | Max Concurrent Sessions |
|-----|-------------------------|
| 1GB | ~50 users |
| 2GB | ~100 users |
| 4GB | ~200 users |
| 8GB | ~500 users |

### 2.6 Estimated Costs

#### Oracle Cloud Free Tier
- **Cost:** $0/month (if combined with Nym gateway on same instance)
- **Limitation:** Limited bandwidth may restrict concurrent users

#### Dedicated TURN Server
| Provider | Instance Type | Monthly Cost |
|----------|---------------|--------------|
| AWS | t3.medium (2 vCPU, 4GB RAM) | ~$30-40 |
| DigitalOcean | General Purpose (2 vCPU, 4GB RAM) | $24 |
| Vultr | High Frequency (2 vCPU, 4GB RAM) | $18 |
| Hetzner | CX21 (2 vCPU, 4GB RAM) | €5.83 (~$6.30) |

---

## 3. Combined Deployment Architecture

### 3.1 Single Server Deployment (Recommended for Testing)

**Pros:**
- Cost-effective (Oracle Free Tier = $0)
- Simplified management
- Single point of configuration

**Cons:**
- Shared resources
- Single point of failure
- Limited scalability

#### Combined Server Specs (Oracle Free Tier)
- **Instance:** VM.Standard.A1.Flex (ARM)
- **CPU:** 2-4 OCPU
- **RAM:** 12-24GB
- **Storage:** 100GB
- **Cost:** $0/month

### 3.2 Dual Server Deployment (Recommended for Production)

**Pros:**
- Better resource isolation
- Independent scaling
- Fault tolerance
- Optimized performance

**Cons:**
- Higher cost
- More complex management

#### Architecture Diagram
```
┌─────────────────────────────────────────────────────────┐
│                     ZeroChat Client                      │
│                      (Android App)                       │
└─────────────┬─────────────────────────┬─────────────────┘
              │                         │
              │ Nym Mixnet              │ WebRTC Signaling
              │ (Privacy Layer)         │ (TURN Relay)
              │                         │
              ▼                         ▼
┌─────────────────────────┐   ┌─────────────────────────┐
│   Nym Gateway Server    │   │    TURN Server          │
│   - Port 1789, 1790     │   │    - Port 3478, 5349    │
│   - Port 8080, 9000     │   │    - Port 49152-65535   │
│   - Ubuntu 22.04 ARM    │   │    - Ubuntu 22.04       │
│   - 2 OCPU, 12GB RAM    │   │    - 2 vCPU, 4GB RAM    │
└─────────────────────────┘   └─────────────────────────┘
         │                             │
         └─────────────┬───────────────┘
                       │
                       ▼
              Nym Public Mixnet
         (Distributed Privacy Network)
```

---

## 4. Deployment Options Comparison

### Option A: Public Nym Network + Self-Hosted TURN

**Setup:**
- Use Nym's public mixnet (no gateway needed)
- Deploy only TURN server

**Pros:**
- Minimal infrastructure (1 server)
- Nym handles mixnet complexity
- Lower maintenance

**Cons:**
- Less privacy control
- Dependent on Nym's public network
- Potential latency variations

**Cost:** ~$0-12/month (depending on TURN server choice)

### Option B: Self-Hosted Nym Gateway + Self-Hosted TURN

**Setup:**
- Deploy Nym gateway on Oracle Free Tier
- Deploy TURN server (can be same instance)

**Pros:**
- Maximum privacy and control
- Can use Oracle Free Tier ($0)
- Full infrastructure ownership

**Cons:**
- More complex setup
- Requires maintenance of both services

**Cost:** $0/month (Oracle Free Tier)

### Option C: Self-Hosted Nym Gateway + Third-Party TURN Service

**Setup:**
- Deploy Nym gateway
- Use managed TURN service (e.g., Twilio, Xirsys)

**Pros:**
- Simplified TURN management
- Guaranteed TURN uptime
- Scalable TURN infrastructure

**Cons:**
- Ongoing TURN service costs
- Less control over TURN server

**Cost:** ~$0-50/month (depending on usage)

---

## 5. Recommended Deployment Strategy

### Phase 1: Development/Testing
- **Nym:** Use public Nym network
- **TURN:** Oracle Cloud Free Tier (single instance)
- **Cost:** $0/month
- **Timeline:** Immediate deployment

### Phase 2: Beta/Limited Release
- **Nym:** Self-hosted gateway (Oracle Free Tier)
- **TURN:** Same Oracle instance
- **Cost:** $0/month
- **Timeline:** 1-2 days setup

### Phase 3: Production
- **Nym:** Dedicated Oracle Free Tier instance
- **TURN:** Separate instance (Hetzner or DigitalOcean)
- **Cost:** ~$6-12/month
- **Timeline:** 1 week setup + testing

### Phase 4: Scale
- **Nym:** Multiple gateways across regions
- **TURN:** Load-balanced TURN servers
- **Cost:** ~$50-200/month (based on user growth)
- **Timeline:** Ongoing optimization

---

## 6. Security Considerations

### 6.1 Nym Gateway Security
- **SSH:** Use key-based authentication only
- **Firewall:** Restrict SSH to known IPs
- **Updates:** Enable automatic security updates
- **Monitoring:** Set up systemd service monitoring
- **Backups:** Regular gateway identity backups

### 6.2 TURN Server Security
- **Authentication:** Use strong credentials (lt-cred-mech)
- **No STUN:** Disable STUN to prevent IP leakage
- **TLS:** Enable TURN over TLS (port 5349)
- **Rate Limiting:** Configure max-bps and total-quota
- **Logging:** Monitor for abuse patterns

### 6.3 Recommended Security Hardening
```bash
# Disable password authentication
sudo sed -i 's/PasswordAuthentication yes/PasswordAuthentication no/' /etc/ssh/sshd_config
sudo systemctl restart sshd

# Enable automatic security updates
sudo apt install unattended-upgrades -y
sudo dpkg-reconfigure --priority=low unattended-upgrades

# Install fail2ban
sudo apt install fail2ban -y
sudo systemctl enable fail2ban
```

---

## 7. Monitoring and Maintenance

### 7.1 Nym Gateway Monitoring
```bash
# Check service status
sudo systemctl status nym-gateway

# View logs
sudo journalctl -u nym-gateway -f

# Check gateway identity
cat ~/.nym/gateways/my-gateway/data/gateway_identity
```

### 7.2 TURN Server Monitoring
```bash
# Check service status
sudo systemctl status coturn

# View logs
sudo tail -f /var/log/turnserver.log

# Monitor active sessions
sudo netstat -anp | grep turnserver
```

### 7.3 Recommended Monitoring Tools
- **Uptime monitoring:** UptimeRobot (free tier)
- **Server metrics:** Netdata (free, self-hosted)
- **Log aggregation:** Loki + Grafana (optional)

---

## 8. Quick Start Checklist

### For Nym Gateway (Oracle Cloud Free Tier)
- [ ] Create Oracle Cloud account
- [ ] Deploy VM.Standard.A1.Flex instance (Ubuntu 22.04 ARM)
- [ ] Configure security list (ports 1789, 1790, 8080, 9000)
- [ ] SSH into instance
- [ ] Download and install nym-gateway binary
- [ ] Initialize gateway with public IP
- [ ] Configure systemd service
- [ ] Test gateway connectivity
- [ ] Note gateway address for app configuration

### For TURN Server (Same or Separate Instance)
- [ ] Install coturn package
- [ ] Configure /etc/turnserver.conf
- [ ] Set external-ip to public IP
- [ ] Create strong credentials
- [ ] Enable coturn service
- [ ] Configure firewall (ports 3478, 5349, 49152-65535)
- [ ] Test TURN server with WebRTC test tool
- [ ] Update app configuration with TURN credentials

---

## 9. Support and Resources

### Official Documentation
- [Nym SDK Documentation](https://nymtech.net/docs/sdk/rust.html)
- [Nym Gateway Setup Guide](https://nymtech.net/docs/nodes/gateways.html)
- [coturn Documentation](https://github.com/coturn/coturn/wiki)
- [Oracle Cloud Free Tier](https://www.oracle.com/cloud/free/)

### Testing Tools
- [WebRTC Trickle ICE Test](https://webrtc.github.io/samples/src/content/peerconnection/trickle-ice/)
- [TURN Server Test](https://icetest.info/)

### Community Support
- Nym Discord: https://discord.gg/nym
- WebRTC Community: https://groups.google.com/g/discuss-webrtc

---

## 10. Cost Summary

### Minimal Setup (Recommended for Start)
| Component | Provider | Cost |
|-----------|----------|------|
| Nym Gateway | Oracle Cloud Free Tier | $0 |
| TURN Server | Oracle Cloud Free Tier (same instance) | $0 |
| **Total** | | **$0/month** |

### Production Setup (Recommended for Scale)
| Component | Provider | Cost |
|-----------|----------|------|
| Nym Gateway | Oracle Cloud Free Tier | $0 |
| TURN Server | Hetzner CX21 | $6.30 |
| **Total** | | **$6.30/month** |

### Enterprise Setup (High Availability)
| Component | Provider | Cost |
|-----------|----------|------|
| Nym Gateway (Primary) | Oracle Cloud Free Tier | $0 |
| Nym Gateway (Backup) | Oracle Cloud Free Tier | $0 |
| TURN Server (Load Balanced x2) | Hetzner CX31 x2 | $25.20 |
| **Total** | | **$25.20/month** |

---

## Conclusion

ZeroChat can be deployed with **zero infrastructure costs** using Oracle Cloud Free Tier for both Nym gateway and TURN server. This setup is suitable for development, testing, and small-scale production deployments (up to 50-100 concurrent users).

For larger scale deployments, a dedicated TURN server is recommended, with costs starting at ~$6/month using budget cloud providers like Hetzner.

The architecture is designed to scale incrementally as user base grows, with clear upgrade paths from free tier to production-grade infrastructure.
