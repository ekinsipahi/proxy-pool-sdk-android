# Tunnel protocol v1

The complete contract between a device and the coordinator. Anything that can
open a WebSocket and a TCP socket can implement it — this document plus
`tools/fakedevice.py` (a working 150-line reference in the coordinator repo) is
everything a port needs.

WebSocket was chosen over a raw TLS socket for two reasons: it survives the
middleboxes and captive portals that a home or mobile connection is full of, and
it is the *only* transport available in the environments this is meant to reach
next — LG webOS and Samsung Tizen TV apps are JavaScript sandboxes with no raw
sockets.

## Transport

One WebSocket per device, dialled **outward** (a device behind carrier NAT
cannot be dialled inward). `wss://` in production. All customer connections for
that device are multiplexed over that one socket.

Two frame kinds share it:

- **text** — JSON control messages, one `op` key each. Low volume.
- **binary** — data frames, `[1B op][4B streamId big-endian][payload]`.

Maximum frame size is 1 MiB. A larger frame means the peer is broken or hostile
and the connection is closed.

## Handshake

The device sends **exactly one** text frame first, within 10 seconds:

```json
{
  "op": "hello",
  "v": 1,
  "apiKey": "partner key",
  "token": "",
  "platform": "android",
  "model": "Google Pixel 8",
  "appId": "com.example.app",
  "sdk": "1.0.0",
  "maxStreams": 8,
  "consent": true
}
```

| Field | Meaning |
|---|---|
| `v` | Protocol version. A mismatch is refused, never negotiated down. |
| `apiKey` | The partner key. Decides who is paid. |
| `token` | The signed identity from a previous `welcome`, or `""` when new. |
| `maxStreams` | What the device is willing to carry. The coordinator clamps it; it can only ever lower the number. |
| `consent` | Must be `true`. See below. |

The coordinator answers with either:

```json
{
  "op": "welcome",
  "v": 1,
  "deviceId": "bab1580c6e2242bba48784f5111dfe67",
  "token": "eyJ...signed...",
  "country": "TR",
  "mobile": false,
  "config": { "maxStreams": 8, "heartbeatS": 30, "statsIntervalS": 60 }
}
```

or a refusal, followed by a close:

```json
{ "op": "error", "code": "bad_key", "message": "unknown partner API key" }
```

Refusal codes: `hello_timeout`, `bad_hello`, `bad_version`, `bad_key`,
`no_consent`. **A refusal is a decision, not a blip** — a client that reconnects
in a loop against a bad key burns the user's battery for nothing. Stop and
surface it.

### Identity is assigned, never claimed

A device does not choose its id. It presents the token it was given last time
and the coordinator reads the id out of it; a device with no token, or a forged
one, is issued a fresh identity. A `deviceId` field in the hello is ignored
outright.

This is not ceremony. Earnings are attributed to an id, so a self-declared id is
a self-declared claim on someone else's money — and nothing running on a device
can be trusted to make that claim honestly. **Store the token, present it,
nothing else.**

### Consent

`consent: true` asserts that the user has been shown a disclosure and agreed.
The coordinator refuses the connection without it. It cannot verify the claim —
consent happens on the device — so this is a contractual gate, not a
cryptographic one, and a partner that lies here is in breach of its agreement
and of several privacy laws. Do not connect before the user has actually agreed.

## Data frames

```
[1B op][4B streamId big-endian][payload]
```

| op | Name | Direction | Payload |
|---:|---|---|---|
| 1 | `OPEN` | coordinator → device | `"host:port"`, UTF-8 |
| 2 | `DATA` | both | raw bytes |
| 3 | `CLOSE` | both | empty |
| 4 | `OPEN_OK` | device → coordinator | empty |
| 5 | `OPEN_ERR` | device → coordinator | short reason, UTF-8 |

Stream ids are allocated by the coordinator (the only side that opens streams)
and are unique for the life of the connection.

### The one rule that is easy to get wrong

**`OPEN` must be answered with `OPEN_OK` or `OPEN_ERR`, and the device must not
send any `DATA` before `OPEN_OK`.**

The coordinator cannot tell its customer "connected" until the device's TCP
connect actually succeeded. A gateway that answers optimistically produces a
connection that dies mid-TLS-handshake, which every HTTP client reports as an
unhelpful "connection reset" — and the customer blames the proxy, correctly.

### Device obligations on `OPEN`

1. Resolve and connect to `host:port`, with a timeout (15 s is what the SDK uses).
2. **Refuse the connection if it resolved to a private, loopback or link-local
   address**, and answer `OPEN_ERR`. This is checked *after* resolution, because
   the interesting case is a public hostname pointing at `192.168.1.1`. Without
   it, every device is a pivot into its owner's home network. The coordinator
   checks this too; do not treat that as a reason to skip it.
3. Answer `OPEN_OK`, then stream the socket back as `DATA` frames.
4. Answer `CLOSE` when the destination closes, and close the socket on an
   inbound `CLOSE`.

### Backpressure — do not skip this

A home uplink is a fraction of what a customer pulls through it. A device that
reads its destination socket as fast as it can and pushes every byte into the
WebSocket will buffer the difference in memory until it is killed.

Before reading more from a destination, wait while the WebSocket's outbound
queue is above a watermark (the Android SDK uses 512 KiB via
`WebSocket.queueSize()`; the Python reference uses `await ws.send(...)`, which
blocks naturally). Parking the reader is the whole mechanism: it stops draining
the socket, the receive window closes, and the sender slows down.

The coordinator applies the mirror image in the other direction.

## Keepalive and stats

- The coordinator sends WebSocket pings every 20 s. Answer them (every library
  does automatically). A device silent for 90 s is dropped.
- Optionally send, every 60 s:
  `{"op": "stats", "up": 123, "down": 456, "streams": 2}`.
  **Diagnostics only.** Payouts are computed from what the coordinator relayed,
  never from what a device reports — otherwise this frame would be the
  payout-fraud surface.
- `{"op": "bye"}` before a clean shutdown lets the coordinator drop the node
  immediately instead of waiting for the heartbeat to expire.

## Reconnecting

Exponential backoff starting at 1 s, capped at 60 s, **with jitter**. Without
jitter every device in a country reconnects in lockstep after a coordinator
restart, and the stampede takes it down again.

On reconnect, present the stored token. Any streams from the old connection are
dead; drop their sockets.

## Worked example

```
device → {"op":"hello","v":1,"apiKey":"K","token":"","consent":true,...}
device ← {"op":"welcome","v":1,"deviceId":"bab15...","token":"eyJ...","country":"TR"}

device ← [01][00000001]"api.ipify.org:443"
                                       device connects, resolves to 104.26.x.x
device → [04][00000001]
device ← [02][00000001]<TLS ClientHello>
device → [02][00000001]<TLS ServerHello ...>
        ... bytes both ways ...
device ← [03][00000001]
device → [03][00000001]
```
