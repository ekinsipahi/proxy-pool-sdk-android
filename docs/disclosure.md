# Telling your users — a template

This is the part that decides whether the integration is fine or a lawsuit. Every
bandwidth network that has been sued — Hola most famously — was sued over what
its users were told, not over how the tunnel worked. The technology in this repo
is unremarkable; the disclosure is the product.

You are responsible for the wording you ship. This is a starting point written
to be adapted, not copied blindly, and it is not legal advice.

## The four things that must be true

1. **The user agreed before anything ran.** Not buried in a EULA, not a
   pre-ticked box, not "by continuing you accept". An affirmative action, on a
   screen that says what is happening.
2. **They knew what they were agreeing to** — that their internet connection
   will carry other people's web traffic.
3. **They knew what they got for it** — ad-free, a premium feature, credits,
   whatever it genuinely is. If the answer is "nothing", say that; some users
   will still agree, and the ones who would not agree are exactly the ones you
   must not enrol.
4. **They can stop, easily, and it stops.** One control, reachable from your app,
   and the tunnel ends immediately — not at the next launch.

## Screen copy

> **Share your idle bandwidth**
>
> With your permission, this device will carry other people's web requests while
> it is idle. It acts as an exit point on the Proxy Pool network, which is how
> this app stays free.
>
> - Only ordinary web traffic (ports 80 and 443)
> - Your own data is never read, stored or sent
> - Never on mobile data unless you allow it
> - You can turn it off at any time in Settings, and it stops right away
>
> [ Learn more ]   [ No thanks ]   [ **I agree — share my bandwidth** ]

Notes on the wording, because the details are where these go wrong:

- "Carry other people's web requests" beats "share your bandwidth". The second
  is what a user will later say they did not understand.
- Both buttons must be real. A screen where declining is a grey link and
  agreeing is a big button is a dark pattern, and a regulator reads it as one.
- Do not say "anonymous", "secure" or "encrypted" about the traffic being
  carried. It is other people's traffic; the user's connection is what carries
  it, and their IP is what the destination sees.

## What to say in your privacy policy

- That the app includes a bandwidth-sharing SDK, opt-in, and names the network.
- What is collected: a random per-install identifier, the device's IP address
  (which is inherent — it is the exit address), coarse country and network
  operator derived from it, and byte counts. **Not** contents, not browsing,
  not identifiers tied to the person.
- That the identifier is forgotten when the user withdraws consent or clears app
  data.
- Retention, and who to contact.

## Play Store

Google's User Data and Device and Network Abuse policies both apply. Declare the
SDK in Data safety, disclose the network-usage behaviour, and make sure the
in-app disclosure appears **before** consent is taken — Play reviewers check
exactly this sequence. Apps have been removed for shipping this kind of SDK
silently; none, to our knowledge, for shipping it with a clear opt-in.

## The off switch

```kotlin
Switch(
    checked = ProxyPoolSdk.hasConsent(context),
    onCheckedChange = { on ->
        ProxyPoolSdk.setConsent(context, on)     // false stops the tunnel now
        if (on) ProxyPoolSdk.start(context)
    },
)
```

`setConsent(context, false)` tears the tunnel down immediately and forgets the
device's network identity. "Stop" should mean stop.

## Keep the record

`ProxyPoolSdk.consentGrantedAt(context)` returns when the user agreed. Keep it,
along with the version of the disclosure text they saw. If anyone ever asks —
a user, a store reviewer, a regulator — "we showed this text, on this date, and
they pressed this button" is the answer that ends the conversation.
