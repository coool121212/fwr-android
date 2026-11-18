# FWR (Fun With Reddit)

An open-source Android client for Reddit built from scratch in Kotlin and Jetpack Compose, featuring a high-density two-pane/three-column layout and compact comment threading.

## Features

- **Classic High-Density Feed & Thread UI:** 3-column post rows (`score | title + metadata | thumbnail`), collapsible action strips, indented comment threads with guide lines, parent/root/prev/next comment navigation, and inline media/gallery previews.
- **Anonymous Browsing & BYOK OAuth2:** Supports anonymous app-only browsing (`installed_client`) or full signed-in access using your own Reddit Installed App Client ID and matching Redirect URI in **Settings → BYOK & API Credentials** (voting, commenting, saved posts, subscribed subreddits, and inbox/modmail). Note: Reddit's edge gateway now blocks unauthenticated `www.reddit.com/*.json` requests with HTTP 403, so a personal Client ID is required to browse via `oauth.reddit.com`.
- **Customizable Appearance & Scaling:** Dark, Light, and AMOLED Black themes, card or compact list layouts, left-handed mode, per-element UI/title/comment/thumbnail scaling sliders, and content/keyword filters.
- **Built-in Media & Link Handling:** Supports Reddit galleries, v.redd.it video, RedGIFs, Imgur albums, inline comment images/GIFs, and Reddit link intent routing.

## OAuth2 / BYOK Setup

> **Note:** Reddit blocks keyless `www.reddit.com/*.json` requests with HTTP 403 and restricts new self-service app creation at `reddit.com/prefs/apps` under its *Responsible Builder Policy*. You will need an existing or approved Reddit **installed app** Client ID and its matching Redirect URI (`fwr://reddit-oauth` by default) to browse via `oauth.reddit.com`.

1. Open **Settings → BYOK & API Credentials** in FWR.
2. Paste your Reddit **Client ID** and matching **OAuth Redirect URI** (e.g. `fwr://reddit-oauth`).
3. Tap **Save Key Only** for anonymous read-only browsing via `oauth.reddit.com`, or **Connect Reddit Account** to sign in.

## Building from Source

### Prerequisites
- Android SDK (compileSdk 35, minSdk 26)
- JDK 17

### Build Debug APK
```bash
cd rwf-android
./gradlew assembleDebug
```
On Windows PowerShell:
```powershell
cd rwf-android
.\gradlew.bat assembleDebug
```

## License

FWR is free software licensed under the **GNU General Public License v3.0 (GPL-3.0)**. See [LICENSE](LICENSE) for the full license text.
