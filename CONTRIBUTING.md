# Contributing

Thanks for looking. This SDK runs on other people's devices with their
permission, so the bar for changes is a little different from a normal library:
correctness and honesty about what the code does matter more than features.

## Good first contributions

- **Ports of the tunnel client** to other platforms — LG webOS, Samsung Tizen,
  Fire TV, desktop, routers. [`docs/protocol.md`](docs/protocol.md) is the full
  specification and `tools/fakedevice.py` in the coordinator repo is a working
  reference. This is the most useful thing anyone can add.
- **Anything that makes the consent story clearer** — better disclosure copy,
  a drop-in consent screen, translations of it.
- **Device compatibility** — odd TV boxes, vendor Android builds, aggressive
  battery managers. Tell us the device and what happened.
- **Tests.** The coordinator repo has an end-to-end suite; the Android side has
  almost none, and that is a gap worth closing.

## Ground rules

**Never weaken a consent or safety check to make something work.** If a check is
in the way, say so in an issue and explain the case — the check may well be
wrong, but it gets changed deliberately, not quietly. In particular, do not
send a PR that:

- connects without consent, or keeps running after it is withdrawn,
- collects a hardware or advertising identifier,
- lets a device choose its own network identity,
- removes the private-address refusal, or widens the allowed ports by default,
- hides or downgrades the ongoing notification.

## Before you open a PR

```bash
./gradlew :sdk:assembleRelease :app:assembleDebug
```

must pass. If you touched the protocol, the coordinator's suite must pass too:

```bash
cd ../proxy-pool-sdk && python -m pytest tests/test_coordinator.py
```

A protocol change means updating `docs/protocol.md` in the same PR. The spec and
the code disagreeing is worse than either being wrong on its own, because the
next person to port it trusts the spec.

## Style

Match the surrounding code. Comments explain *why* something is the way it is,
not what the line does — a comment that says "this is the backpressure
mechanism, and blocking here is deliberate" saves the next reader an afternoon;
`// increment counter` does not.

Kotlin: the official style, 4-space indent, 100 columns. Python: the coordinator
repo's conventions.

## Reporting bugs

Include the device or emulator, the Android version, the SDK version, and the
coordinator log alongside the app log if you have both. "It disconnects
sometimes" is very hard to act on; "it disconnects ~30 s after the screen turns
off, on a Xiaomi box running Android 11" is a fix.

## Security

Do not open a public issue for a vulnerability — see [SECURITY.md](SECURITY.md).

## License

Contributions are accepted under the [MIT License](LICENSE), the same terms as
the project.
