# QuietNet

Block ads and trackers in every app on your Android phone. Tap one button and it works. You don't need root.

## How it works

QuietNet starts a local VPN that only carries DNS lookups, the step where an app asks "where is ads.example.com?". If the name is on a blocklist, QuietNet answers right away that it doesn't exist, so the ad never loads. All other lookups go to your normal DNS server, and all other traffic goes straight to the internet. Nothing passes through a remote server, so browsing speed and battery life stay the same.

- **One switch.** Use the big shield in the app, or the **Block ads** tile in Quick Settings.
- **Over half a million ad, tracker and malware domains** from well-known community blocklists: HaGeZi, OISD, AdGuard DNS filter, StevenBlack, Peter Lowe, EasyList and EasyPrivacy. More lists are available as options: HaGeZi Ultimate, Threat protection and Samsung tracking.
- **Activity view** of what was blocked. Allow a domain that broke something with one tap, or block one that got through.
- **Per-app switch** to skip apps that don't like it, such as some banking apps.
- **Choice of DNS server**: your network's own server, AdGuard DNS (blocks even more), Cloudflare, Quad9 or Google.
- **Turns itself back on** after a reboot or an update. It also works with Android's Always-on VPN setting.
- **Updates the lists** every few days.
- **YouTube without ads.** Tap the YouTube card on the Home screen, long-press the QuietNet icon, or share a video from the YouTube app to **Watch without ads**:
  - YouTube's mobile site opens inside QuietNet, and our own script removes the ad data from YouTube's responses before the player reads it. Videos start straight away.
  - Playback continues with the screen off or the app in the background.
  - Controls appear on the lock screen and in the notification, and headset buttons work.

## Limits

- **YouTube, Instagram and Facebook apps:** their ads come from the same servers as the videos and posts, so no DNS blocker can remove them. That includes every non-root app. For YouTube, use QuietNet's YouTube screen instead.
- **YouTube changes its site from time to time.** If an ad gets through after a change, the script needs an update.
- **Strict Private DNS:** with Settings → Private DNS set to a specific provider, Android skips QuietNet. Set it to **Off** or **Automatic**. The app warns you when this happens.
- **One VPN at a time:** Android allows only one VPN, so QuietNet can't run next to another VPN app.

## Install

Download `QuietNet.apk` from the latest [release](../../releases/latest), open it on your phone and allow installs from your browser or file manager. Google Play doesn't allow apps that block ads in other apps, so it isn't there.

## Build

You need JDK 17 or newer and the Android SDK (platform 36).

```sh
./gradlew assembleRelease   # app/build/outputs/apk/release/
```

Release signing reads `~/.android-signing/quietnet.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). Without it, the release APK is unsigned.

## Project

| File | What it does |
|---|---|
| `BlockerService.kt` | The local VPN: reads DNS packets, answers blocked names and forwards the rest |
| `Packet.kt` | Minimal IPv4, UDP and DNS reading and writing |
| `Rules.kt` | Blocklist lookups (sorted 64-bit hashes, subdomain matching) and your own allow and block rules |
| `Filters.kt` | The list catalog, downloads, and a parser for hosts, domain and adblock-style lists |
| `Ui.kt`, `MainActivity.kt` | The screens (Jetpack Compose) |
| `ToggleTile.kt`, `BootReceiver.kt` | The Quick Settings tile and restart after a reboot |
| `YouTubeActivity.kt`, `assets/youtube.js` | YouTube screen: removes ads from YouTube's data, blocks ad requests, keeps playback going in the background |
| `PlaybackService.kt`, `Player.kt` | Background playback, the media notification and lock-screen controls |

## License

MIT. The blocklists belong to their maintainers and have their own licenses. QuietNet downloads them onto your phone and doesn't include them.
