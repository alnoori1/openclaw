# Gateway Setup Guide

Claw Companion supports three practical connection patterns:

1. Standard OpenClaw QR for normal local or remote gateways
2. Tailscale Serve/Funnel for remote `wss://...` access
3. Rabbit-style direct LAN pairing over `ws://...` on a trusted network

Use the flow that matches how your gateway is exposed.

## Quick Matrix

| Flow | Transport | Auth | Best for |
| --- | --- | --- | --- |
| Standard QR | `ws://` or `wss://` from the gateway setup code | token or password | Normal OpenClaw installs |
| Tailscale Serve/Funnel | `wss://<host>.ts.net` | usually password for managed Tailscale mode | Remote access without manual reverse proxy work |
| Rabbit-style LAN | `ws://<lan-ip>:<port>` | token | Trusted LAN or tailnet operator use |

## 1. Standard OpenClaw QR

This is the default path.

Run on the gateway host:

```bash
openclaw qr
```

Then in Claw Companion:

1. Open onboarding or the `Connect` tab.
2. Tap `Scan QR code`.
3. Grant camera permission if Android asks.
4. Review the parsed endpoint and connect.

Useful variants:

```bash
openclaw qr --json
```

Use `--json` if you want to inspect the setup payload or paste it manually into the app.

If your gateway already has a remote URL configured, the QR will usually point at that remote URL instead of a local LAN address.

## 2. Tailscale Serve / Funnel

Claw Companion supports trusted public `wss://...` endpoints, including Tailscale-hosted URLs.

### Official managed Tailscale mode

OpenClaw documents a managed Serve/Funnel path that binds the gateway to loopback and exposes it through Tailscale. The official docs recommend password auth for this mode.

Example launch:

```bash
openclaw gateway --tailscale funnel --auth password
```

Or configure the equivalent gateway settings in your OpenClaw config and restart the gateway. After that:

```bash
openclaw qr
```

Then scan the QR in Claw Companion.

Notes:

- Managed Tailscale mode is the cleanest public-remote setup.
- If your gateway uses password auth, enter the password when the app prompts for it.
- Keep the gateway bound to `loopback` unless you intentionally want direct LAN exposure too.

### Existing custom Funnel or reverse proxy setup

If you already expose the gateway through a trusted remote URL such as:

```text
wss://your-node-name.ts.net
```

and your gateway config sets:

```text
gateway.remote.url = wss://your-node-name.ts.net
```

the normal `openclaw qr` flow is enough. Claw Companion will follow the remote `wss://...` URL from the QR payload.

That is the right setup when:

- the gateway itself still listens on loopback
- Tailscale Serve/Funnel or another TLS-capable proxy terminates the public connection
- you want the phone to connect over the remote URL instead of direct LAN `ws://`

## 3. Rabbit-Style LAN Pairing

This is the direct token-auth `ws://` flow used by Rabbit-style LAN pairing. Use it only on a trusted LAN or tailnet because it intentionally avoids TLS.

### Gateway-side settings

Set the gateway to LAN binding with token auth and allow insecure Control UI auth for local operator flows:

```bash
openclaw config set gateway.bind lan
openclaw config set gateway.auth.mode token
openclaw config set gateway.auth.token YOUR_TOKEN_HERE
openclaw config set gateway.controlUi.allowInsecureAuth true
openclaw gateway restart
```

If you do not already have a token, generate one and store it in `gateway.auth.token` before restarting.

### Build a setup payload

Create a JSON payload like this:

```json
{
  "type": "clawdbot-gateway",
  "version": 1,
  "ips": ["192.168.1.24"],
  "port": 18789,
  "token": "YOUR_TOKEN_HERE",
  "protocol": "ws"
}
```

Replace:

- `192.168.1.24` with the gateway machine's LAN or tailnet IP
- `18789` with your real gateway port
- `YOUR_TOKEN_HERE` with the configured token

Then either:

1. turn that payload into a QR code and scan it in Claw Companion, or
2. paste it directly into the app's setup payload field if you are using manual onboarding

If you already use Rabbit's helper script, it generates a compatible QR payload for this style of pairing.

## Manual Connection Fallback

If scanning is inconvenient, Claw Companion can connect manually from onboarding or the `Connect` tab.

Fill in:

- host or IP
- port
- TLS on or off
- token or password, depending on your gateway auth mode

Examples:

- `ws://192.168.1.24:18789` with a token for trusted LAN pairing
- `wss://your-node-name.ts.net` with a password or token for remote proxy/Funnel access

## Troubleshooting

### QR keeps sending you to TLS verification

That means the setup code is still pointing at a `wss://...` endpoint. If you wanted Rabbit-style LAN pairing, regenerate the payload with `"protocol":"ws"` and your LAN/tailnet IP instead of using the normal `openclaw qr` output.

### Phone cannot connect to `127.0.0.1`

`127.0.0.1` only works on the gateway host itself. Use a LAN IP, tailnet IP, or remote `wss://...` hostname.

### Channel auth still not complete after saving config

Some providers, especially WhatsApp or browser-driven flows, need a second login step on the gateway after the base channel config is saved. Use Claw Companion's channel status view or the OpenClaw Control UI to finish the provider login.

## References

- [OpenClaw Android docs](https://docs.openclaw.ai/platforms/android)
- [OpenClaw Tailscale docs](https://docs.openclaw.ai/platforms/tailscale)
- [Rabbit OpenClaw guide](https://www.rabbit.tech/blog/connect-your-r1-to-openclaw)
