# FWR (Fun With Reddit)

An open-source Android client for Reddit built from scratch in Kotlin and Jetpack Compose, featuring a high-density two-pane/three-column layout and compact comment threading.

## Features

- **Classic High-Density Feed & Thread UI:** 3-column post rows (`score | title + metadata | thumbnail`), collapsible action strips, indented comment threads with guide lines, parent/root/prev/next comment navigation, and inline media/gallery previews.
- **Anonymous Browsing & BYOK OAuth2:** Works out of the box in keyless anonymous read-only mode (`www.reddit.com/*.json`), or bring your own personal Reddit Installed App Client ID to sign in and unlock voting, commenting, saved posts, subscribed subreddits, and inbox/modmail.
- **Customizable Appearance & Scaling:** Dark, Light, and AMOLED Black themes, card or compact list layouts, left-handed mode, per-element UI/title/comment/thumbnail scaling sliders, and content/keyword filters.
- **Built-in Media & Link Handling:** Supports Reddit galleries, v.redd.it video, RedGIFs, Imgur albums, inline comment images/GIFs, and Reddit link intent routing.

## OAuth2 / BYOK Setup

1. Open **Settings → BYOK & API Credentials** in FWR.
2. Create a personal **installed app** at [`reddit.com/prefs/apps`](https://www.reddit.com/prefs/apps) using the Redirect URI:
   ```text
   fwr://reddit-oauth
   ```
3. Copy your generated Client ID into FWR's **Reddit Client ID** field and tap **Connect Reddit Account**.

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
