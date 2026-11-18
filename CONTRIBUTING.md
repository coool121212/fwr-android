# Contributing to FWR

Thanks for your interest in contributing to **FWR (Fun With Reddit)**!

## Getting Started

### Prerequisites
- **JDK 17**
- **Android SDK** (`compileSdk = 35`, `minSdk = 26`)

### Build & Run Tests
From the repository root:

```bash
./gradlew testDebugUnitTest assembleDebug
```

On Windows PowerShell:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

## Guidelines

- **Code style:** Follow the official Kotlin coding conventions (`kotlin.code.style=official`) and keep UI 100% Jetpack Compose + Material 3.
- **Tests:** Add or update unit tests under `app/src/test/` for bug fixes and new logic, and make sure `./gradlew testDebugUnitTest assembleDebug` passes before opening a PR.
- **No hardcoded secrets:** Never commit personal Reddit OAuth Client IDs, tokens, keystores, or `local.properties`.
- **License:** By contributing, you agree that your contributions will be licensed under the **GNU General Public License v3.0 (GPL-3.0)** (see [LICENSE](LICENSE)).

## Pull Requests

1. Fork the repository and create a branch from `main`.
2. Keep PRs focused on a single feature or bug fix.
3. Describe what changed, why, and how you tested it (device/emulator or unit tests).
