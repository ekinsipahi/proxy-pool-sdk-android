# Proxy Pool SDK for Android

An **opt-in** bandwidth-sharing SDK. A consenting user's device carries other
people's web requests while it is idle, acting as a residential or mobile exit
node, and the app's developer is paid per active device per day.

It is small (one dependency, ~44 KB AAR), it works on phones, tablets, Android
TV and TV boxes, and it is built so the user can always see it running and stop
it in one tap.

```kotlin
ProxyPoolSdk.init(context, SdkConfig(apiKey = "your-partner-key",
                                     coordinatorUrl = "wss://coordinator.example.com"))

// only after the user has read a disclosure and agreed:
ProxyPoolSdk.setConsent(context, true)
ProxyPoolSdk.start(context)
```

---

## The one rule

**Nothing runs without explicit, informed consent, and withdrawing it stops the
tunnel immediately.**

This is not boilerplate. Every bandwidth network that has ended up in court
ended up there over *disclosure*, not over the technology. Before you call
`setConsent(context, true)`, your user must have been told, in plain language:

- their internet connection will carry other people's web traffic while idle,
- what they get in return,
- and how to turn it off.

The SDK enforces its half: it refuses to connect without consent, the
coordinator refuses a device whose handshake does not assert it, a persistent
notification is shown the whole time it is sharing, and tapping that
notification opens your app so the off switch is always one tap away.

`app/` is a working reference for the disclosure screen. Read it before you
write your own.

---

## What it actually does

A device behind carrier NAT cannot be dialled, so it dials **out** and holds one
WebSocket open to a coordinator. Customer connections are multiplexed down that
socket:

```
customer ──SOCKS5/HTTP──▶ coordinator ──WebSocket──▶ device ──▶ destination
```

The device opens an ordinary TCP connection to the destination and relays bytes.
It never sees who the customer is, and the customer never sees the device's
address.

What it does **not** do, by construction:

| | |
|---|---|
| Read your traffic | No. The SDK relays bytes for connections the coordinator opens; it has no access to the host app's data, and TLS traffic is opaque to it. |
| Collect identifiers | No IMEI, ANDROID_ID or advertising id. A random UUID per install, reset when app data is cleared. |
| Reach your home network | Refused. A destination that resolves to a private, loopback or link-local address is rejected *after* resolution, on the device. |
| Open arbitrary ports | Web ports only by default (80/443/8080/8443). A residential exit that can reach port 25 is an open spam relay. |
| Run behind your back | Foreground service with a permanent notification, and it stops within seconds of the user withdrawing consent. |

---

## Install

The AAR is published from `sdk/`. Until it is on Maven Central, build and
consume it locally:

```bash
./gradlew :sdk:assembleRelease       # -> sdk/build/outputs/aar/sdk-release.aar
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(files("libs/sdk-release.aar"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
```

Requirements: **minSdk 24**, JDK 17+, AGP 8.7+.

## Use

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ProxyPoolSdk.init(this, SdkConfig(
            apiKey = BuildConfig.PROXYPOOL_KEY,
            coordinatorUrl = "wss://coordinator.example.com",
        ))
        ProxyPoolSdk.notificationTitle = "MyApp — sharing idle bandwidth"
    }
}
```

`init` in `Application.onCreate` matters: the service restarts itself after a
reboot or a low-memory kill, and it stops itself if no config is present.

```kotlin
ProxyPoolSdk.onState = { state ->
    statusLabel.text = when (state.status) {
        SdkState.Status.CONNECTED -> "Sharing — ${state.bytesDown / 1_000_000} MB"
        SdkState.Status.WAITING   -> state.detail      // "Paused on mobile data"
        else                      -> "Not sharing"
    }
}
```

`onState` is always delivered on the main thread.

### Configuration

| Option | Default | Notes |
|---|---|---|
| `apiKey` | required | Your partner key; decides who gets paid. |
| `coordinatorUrl` | `ws://10.0.2.2:8765` | The emulator's view of your dev machine. Production is always `wss://`. |
| `wifiOnly` | `true` | Off means you are spending the user's mobile data. Cellular devices earn the premium rate — but only tell users the truth about it. |
| `requireCharging` | `false` | |
| `minBatteryPercent` | `20` | `0` disables. |
| `onlyWhenScreenOff` | `true` | "Idle bandwidth" is the promise; competing with the app the user is looking at is not idle. |
| `maxStreams` | `8` | Concurrent customer connections. |
| `debugLogging` | `false` | |

Conditions are re-evaluated continuously. A user who walks off Wi-Fi mid-transfer
stops sharing within seconds, and the notification says why.

### Android TV and TV boxes

These are the best nodes in the network — a stable residential IP, online 24/7,
nothing competing for the uplink — so there is a preset for them:

```kotlin
ProxyPoolSdk.init(this, SdkConfig.forAndroidBox(apiKey, coordinatorUrl))
```

It drops the battery and screen rules, raises `maxStreams` to 24, and the
service takes a partial wake lock so a relay is not cut short by CPU sleep. The
bundled `BootReceiver` brings sharing back after a power cut, which is how you
keep those nodes through a 4 a.m. outage.

For a TV app, also declare in your manifest (the demo app does):

```xml
<uses-feature android:name="android.hardware.touchscreen" android:required="false" />
<uses-feature android:name="android.software.leanback"    android:required="false" />
```

---

## Try it end to end, locally

You need the coordinator, which lives in
[`proxy-pool-sdk`](../proxy-pool-sdk) (`coordinator/`).

```bash
# 1. terminal one — the coordinator
cd ../proxy-pool-sdk
pip install -r requirements.txt
python -m coordinator
#   prints the generated customer secret and admin key

# 2. terminal two — build and install the demo app
cd ../proxy-pool-sdk-android
./gradlew :app:installDebug

# 3. on the device: tap "I agree — start sharing"

# 4. terminal three — send a real request through that device
curl -x "socks5h://demo:<customer secret>@127.0.0.1:1080" https://api.ipify.org
#   -> the device's public IP

curl -H "X-Admin-Key: <admin key>" http://127.0.0.1:8799/v1/dashboard
```

`ws://10.0.2.2:8765` — the SDK's default — is the host machine as seen from the
Android emulator, so the demo app works with no configuration.

To debug the coordinator without an Android device at all, run the reference
device client: `python tools/fakedevice.py`.

---

## Porting to other platforms

The device side is deliberately simple: one WebSocket, five opcodes, no
dependency on anything Android-specific. `docs/protocol.md` is the complete
specification, and `tools/fakedevice.py` in the coordinator repo is a working
150-line implementation that doubles as the reference.

Anything that can open a WebSocket and a TCP socket can be a node — which
includes **LG webOS** and **Samsung Tizen** TV apps (both are JavaScript, and
WebSocket is the only transport they have, which is why the protocol is built on
it), desktop, and routers.

---

## Repository layout

```
sdk/   the library
  ProxyPoolSdk      public API: init, consent, start/stop, state
  SdkConfig         sharing rules (network, battery, screen, concurrency)
  TunnelService     foreground service; owns the policy
  TunnelClient      the WebSocket tunnel; owns the socket
  NetworkGate       continuously decides whether sharing is allowed
  BootReceiver      resume after a reboot, if consent was already given
app/   reference integration: a disclosure screen and a live status readout
docs/  protocol specification, integration guide, disclosure template
```

## Contributing

Issues and pull requests are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md).
The parts most worth contributing are ports of the tunnel client to other
platforms, and anything that makes the consent story clearer.

Security issues: please read [SECURITY.md](SECURITY.md) rather than opening a
public issue.

## License

[MIT](LICENSE).
