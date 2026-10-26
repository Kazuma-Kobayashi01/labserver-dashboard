# LabLink for Android (+ Lab bot)

A native Android app (Kotlin, Jetpack Compose, **Material 3**, with Material 3 Expressive touches). Chat is the home
screen, as in Gemini, ChatGPT and Claude; everything else is in the navigation drawer. Design notes and what was
borrowed from which product: [DESIGN.md](DESIGN.md).

<img src="../docs/images/lablink-android-zh.png" alt="LabLink for Android: chat, empty state, drawer, home lab, bot editor" />

| Where | What it does |
|---|---|
| **Chat** (home) | Talk to the current bot: streamed replies as full-width text, collapsible "thoughts", light Markdown, one big rounded composer. Tap the bot's name in the top bar to switch, edit or add a bot. |
| **Bots** (drawer) | Several bots, each with its own name, one-line role, system prompt, orb colour and conversation. The editor starts from templates (translator, coder, writing coach, lab helper, buddy) and previews the bot live. |
| **Home lab** (drawer) | The connection switch, then Dashboard, Incus, Mihomo, Git, Nextcloud and Hermes as tiles. They open in an in-app WebView, **never** in the phone's browser. |
| **Settings** (drawer) | This device's public key, hub settings, Lab bot address and key. |

## The tunnel lives inside the app

Normal VPN and proxy apps use Android's `VpnService`, which takes over the whole phone. LabLink does not:

* WireGuard (wireguard-go on a userspace TCP/IP stack) runs **in the app process**. No VPN permission, no key icon in the
  status bar, no effect on other apps. Close the app and the tunnel is gone.
* The app reaches home through a small HTTP proxy bound to `127.0.0.1` on a random port. The WebView is pointed at it
  (`ProxyController`, with no DIRECT fallback) and so is the chat client.
* The proxy only dials the home domain (`*.labserver.internal`) and the tunnel host, only on ports 80/443. Anything
  else gets a 502, so another app that finds the port still cannot use it as a gateway.
* WebViews can only navigate to the home domain.
* Secrets (the device's WireGuard private key, the Lab bot key) are encrypted with an Android Keystore key. The private
  key is generated on the phone and never leaves it; only the public key is given to the hub.

```
Lab bot chat ──┐                      ┌── hub (VPS) ── home gateway ── nginx ── Hermes API :8642 (/p/labbot/)
Panels WebView ┴─ 127.0.0.1:proxy ─ in-app WireGuard (UDP 51820) ─┘
```

## Build

You need the Android SDK (platform 37, NDK), a JDK 17+, and Go for the tunnel library.

```bash
brew install go
go install golang.org/x/mobile/cmd/gomobile@latest golang.org/x/mobile/cmd/gobind@latest
./tunnel/build-aar.sh              # builds app/libs/labtunnel.aar (arm64 phones + x86_64 emulator)
./gradlew assembleDebug            # app/build/outputs/apk/debug/app-debug.apk
```

Install with `adb install -r app/build/outputs/apk/debug/app-debug.apk` or copy the APK to the phone. The tunnel library
can also be tried on a desktop: `go run ./tunnel/cmd/labtunnel-cli config.json` prints the proxy port; then
`curl -x http://127.0.0.1:<port> http://labserver.internal/`.

## First run

1. **Settings** (drawer) → copy the **public key** (the phone generates its own key pair).
2. On the server side, add the phone as a WireGuard device: `deploy/wireguard/add-device.sh` (see that README).
3. **Settings** → fill in the hub endpoint (`host:port`) and the hub's public key. Or copy a wg-quick config to the
   clipboard and tap **Paste a config**. The default overlay address is `10.77.0.4`.
4. Lab bot: set the API key (see `deploy/hermes/labbot.md`); the default address is `http://bot.<domain>/p/labbot/v1`.
5. **Connect to home**. Panels and Lab bot work while it says *Connected*.

## Notes

* Plain HTTP is allowed only for the home hosts and the loopback proxy (`res/xml/network_security_config.xml`). If you
  use a different home domain, change it there as well as in **Settings → Advanced**.
* The logo is a flat orb with two slanted pill eyes (`ui/BotAvatar.kt`, `res/drawable/ic_launcher_foreground.xml`). Each
  bot gets its own orb colour, and the orb acts out what the bot is doing: thinking, searching, answering
  (see [DESIGN.md](DESIGN.md)).
* The debug APK is signed with the local debug key. For a release build create your own keystore (it is git-ignored).
* Not done yet: a foreground service to keep the tunnel alive in the background, light/dark WebView theming, file
  downloads from panels.
