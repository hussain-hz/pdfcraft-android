# Application updates and first production release

The settings menu is ordered Settings, Help & Support, About. About retains the manual update check. Opening the app also starts an asynchronous check; immediate foreground resumes within one minute share the previous check to avoid duplicate requests. Only stable published releases in hussain-hz/pdfcraft-android are eligible, even when the installed app is a beta. Drafts, prereleases, preview tags and versions with prerelease identifiers are excluded. Versions are compared semantically and APKs are selected for the device ABI.

A circular bell beside Settings shows a dot for an available update. The last valid update notice survives process restart and temporary network failures, and clears after that version is installed or a successful check finds no newer stable release.

Downloads run inside the app through Android DownloadManager, with progress, cancel, retry and restoration after app restart. APKs stay in the app-specific updates directory. Before installation, the app verifies the published SHA-256 when available, package identity, a higher version code, the expected stable version and the installed signing certificate. Android's installer performs its own signature checks and requires user confirmation. If necessary, the user grants installation permission for PdfCraft through the system screen. Update checks send no document contents or account credentials.

The normal INTERNET permission enables checks and downloads. REQUEST_INSTALL_PACKAGES enables the user-requested installer flow; no installation occurs silently. The existing file provider exposes only the dedicated updates directory for APK installation.

## Production identity

The first official release is 0.0.1, engine 0.14.0, with production application ID io.github.artcraftandroid.pdfcraft. Preview builds have the separate .debug suffix and cannot be upgraded in place by a production APK. The first production installation may coexist with a preview; subsequent signed production releases update the production installation normally.

The release signing key is private, outside Git, under the owner's .android/pdfcraft-release directory. GitHub uses four encrypted repository secrets: PDFCRAFT_KEYSTORE_BASE64, PDFCRAFT_KEYSTORE_PASSWORD, PDFCRAFT_KEY_ALIAS and PDFCRAFT_KEY_PASSWORD. Back up this private directory securely; future updates require the same key. Never upload the key or credentials as release assets.

Release tags use v0.0.1, v0.0.2, and so on. The workflow builds and verifies signed ARM64 and ARMv7 APKs, running the shared unit tests and release lint first. It creates a draft if none exists. If a verified local APK was already published for the same tag, its asset remains intact and missing architectures are added without replacement.

## Local validation

Production ARM64 build, testDebugUnitTest and lintRelease passed: 84 JVM tests, no failures; lint reported no issues. The APK has package io.github.artcraftandroid.pdfcraft, version 0.0.1, ARM64 version code 1992, min SDK 29 and target SDK 37. Its release signature was verified. Native engine source is unchanged from the already validated 0.14.0 integration. Full physical-device download/install testing awaits a newer stable release; no fake production release is published for testing.
