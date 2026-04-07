# MasterDnsVPN Integration Walkthrough (Replacing Slipstream)

This document explains **feasibility** and a practical **step-by-step plan** to integrate [MasterDnsVPN](https://github.com/masterking32/MasterDnsVPN) into Gap Mesh instead of Slipstream.

## Feasibility (Short Answer)

**Feasible with moderate effort**, because Gap Mesh already has a transport abstraction pattern based on a local SOCKS proxy process:

- `SlipstreamManager` starts/stops a local tunnel client process and exposes a SOCKS endpoint.
- `ArtiTorManager` can route Tor through that local SOCKS endpoint.
- `OkHttpProvider` can also use the same SOCKS endpoint when Tor is off.

So the fastest path is to **replace the Slipstream process layer** with a `MasterDnsVpnManager` that provides the same outcome: `127.0.0.1:<port>` SOCKS proxy readiness.

---

## Current Integration Points in Gap Mesh

Use these files as your migration map:

1. `app/src/main/java/com/gap/droid/net/SlipstreamManager.kt`
2. `app/src/main/java/com/gap/droid/net/SlipstreamPreferenceManager.kt`
3. `app/src/full/java/com/gap/droid/net/ArtiTorManager.kt`
4. `app/src/main/java/com/gap/droid/net/OkHttpProvider.kt`
5. `app/src/main/java/com/gap/droid/ui/AboutSheet.kt`
6. `app/src/main/res/values/strings.xml` (Slipstream UI strings)
7. `tools/build_slipstream_android.sh` (build pipeline reference)

---

## Recommended Integration Strategy

### Phase 1 — Validate external prerequisites

1. Confirm MasterDnsVPN licensing and redistribution compatibility for app-store distribution.
2. Confirm you can produce Android-compatible client binaries for target ABIs (`arm64-v8a`, `x86_64`).
3. Confirm MasterDnsVPN client can expose local SOCKS4/5 in a stable way on Android.
4. Confirm server-side deployment model (domain/NS delegation, UDP/53 reachability, key handling).

> If Android-native client binaries are not practical, this integration is blocked.  
> In that case, keep Slipstream disabled and postpone replacement.

### Phase 2 — Add runtime manager (minimal-change architecture)

1. Introduce `MasterDnsVpnManager` in `app/src/main/java/com/gap/droid/net/`.
2. Mirror `SlipstreamManager` behavior:
   - start/stop/restart
   - process log capture
   - readiness probe on local SOCKS port
   - health checks and retry policy
3. Start by cloning `SlipstreamManager` structure and replacing:
   - binary name
   - command-line arguments
   - parsing/health assumptions specific to MasterDnsVPN

### Phase 3 — Add preferences and config surface

1. Add `MasterDnsVpnPreferenceManager` (enabled flag, domain, resolver(s), key, transport mode).
2. Keep secure storage through `SecurePrefsFactory`.
3. Include strict validation before launch (blank domain, invalid resolver format, missing key).

### Phase 4 — Wire transport into Tor and HTTP stack

1. In `ArtiTorManager`, replace Slipstream calls with MasterDnsVPN calls:
   - startup before Tor bootstrap (when enabled)
   - shutdown when Tor is disabled
   - Arti rebuild when tunnel state changes
2. In `OkHttpProvider`, fallback proxy should use `MasterDnsVpnManager.currentProxyAddress()` when Tor is off.
3. Keep the same sequencing guarantees: proxy must be ready before traffic reroute.

### Phase 5 — UI migration

1. Replace Slipstream sections in `AboutSheet.kt` with MasterDnsVPN toggles/status.
2. Update string resources and localization entries.
3. Keep “advanced settings” collapsed by default and show actionable error text.

### Phase 6 — Build and packaging

1. Add the MasterDnsVPN native/client artifacts under app packaging for required ABIs.
2. Remove Slipstream-only packaging rules once migration is complete.
3. Create a dedicated build helper script in `tools/` (similar role to `build_slipstream_android.sh`) only if needed for reproducible local builds.

### Phase 7 — Validation plan

1. Unit tests for:
   - preference validation
   - command generation
   - readiness transitions (STARTING → RUNNING / ERROR)
2. Integration checks:
   - MasterDnsVPN on + Tor on (Arti outbound proxy set)
   - MasterDnsVPN on + Tor off (`OkHttpProvider` proxy applied)
   - toggle on/off while app is running
3. Network checks:
   - no direct connection leaks when enabled
   - behavior on DNS failures / packet loss
   - retry and failover behavior

---

## Risk Areas to Address Early

1. **Binary portability** on Android (ABI coverage, executable permissions, SELinux constraints).
2. **Battery impact** if aggressive retry/duplication is enabled.
3. **Security model** of key/config storage and process logs.
4. **Operational complexity** (DNS delegation, resolver quality, server maintenance).

---

## Suggested Rollout

1. Keep feature behind a runtime toggle.
2. Start internal dogfooding on heavily filtered networks.
3. Keep Tor-only path as fallback until stability metrics are proven.
4. Remove Slipstream-specific code only after MasterDnsVPN reaches parity.
