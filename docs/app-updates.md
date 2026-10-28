# Application updates and first production release

Settings opens the preferences page directly. Appearance and Language are compact rows showing the current selection; tapping either opens a rounded choice dialog with a highlighted, checked selection and a close button. Appearance offers Follow system (the default), Colored, White, Dark gray and Dark (OLED), with small previews. Language offers Follow system, Arabic, English and Spanish; following the system clears the explicit app locale. Earlier Light/Dark preferences migrate naturally to White/Dark without resetting the selection. Help & Support and About appear at the bottom of that page. About retains the manual update check. Opening the app also starts an asynchronous check; immediate foreground resumes within one minute share the previous check to avoid duplicate requests. Only stable published releases in hussain-hz/pdfcraft-android are eligible, even when the installed app is a beta. Drafts, prereleases, preview tags and versions with prerelease identifiers are excluded. Versions are compared semantically and APKs are selected for the device ABI.

A circular download-arrow button beside Settings is visible only for an available stable update. The last valid update notice survives process restart and temporary network failures, and clears after that version is installed or a successful check finds no newer stable release.

The explicit Dark appearance uses pure black (#000000) for OLED screens: main surfaces, raised surfaces, menus, page surroundings and navigation bands. Text, outlines and selection feedback remain visible. Its display name stays Dark (داكن). Dark gray remains a separate appearance; following the system retains the normal system dark palette. The informational Notifications section has been removed from Settings; this does not change automatic update checks or the conditional download button.

Downloads run inside the app through Android DownloadManager, with progress, cancel, retry and restoration after app restart. APKs stay in the app-specific updates directory. Before installation, the app verifies the published SHA-256 when available, package identity, a higher version code, the expected stable version and the installed signing certificate. Android's installer performs its own signature checks and requires user confirmation. If necessary, the user grants installation permission for PdfCraft through the system screen. Update checks send no document contents or account credentials.

The normal INTERNET permission enables checks and downloads. REQUEST_INSTALL_PACKAGES enables the user-requested installer flow; no installation occurs silently. The existing file provider exposes only the dedicated updates directory for APK installation.

## Production identity

The first official release is 0.0.1, engine 0.14.0, with production application ID io.github.artcraftandroid.pdfcraft. Preview builds have the separate .debug suffix and cannot be upgraded in place by a production APK. The first production installation may coexist with a preview; subsequent signed production releases update the production installation normally.

The release signing key is private, outside Git, under the owner's .android/pdfcraft-release directory. GitHub uses four encrypted repository secrets: PDFCRAFT_KEYSTORE_BASE64, PDFCRAFT_KEYSTORE_PASSWORD, PDFCRAFT_KEY_ALIAS and PDFCRAFT_KEY_PASSWORD. Back up this private directory securely; future updates require the same key. Never upload the key or credentials as release assets.

Release tags use v0.0.1, v0.0.2, and so on. The workflow builds and verifies signed ARM64 and ARMv7 APKs, running the shared unit tests and release lint first. It creates a draft if none exists. If a verified local APK was already published for the same tag, its asset remains intact and missing architectures are added without replacement.

## Local validation

The RC.3 ARM64 build, testDebugUnitTest and lintRelease passed: 84 JVM tests, no failures; lint reported no issues. The candidate has production package io.github.artcraftandroid.pdfcraft, version 0.0.1-rc.3 and ARM64 version code 1032, min SDK 29 and target SDK 37. Its production signing certificate matches RC.1/RC.2, allowing an in-place trial update. The planned stable 0.0.1 must be rebuilt from the approved source before publishing; official publication remains held pending the user's trial.

Emulator checks covered direct entry to preferences, System as the default appearance, manual Light/Dark, persistence after process restart, live system night/day changes, footer Help/About navigation and absence of the download button when no newer stable release exists. The language context overrides only locale and direction, so it does not pin the initial system appearance. Native engine source is unchanged from the validated 0.14.0 integration. Full physical-device update download/install testing still needs a newer stable release; no fake production release is published for testing.

RC.3 emulator validation also covered rounded appearance/language dialogs, all five appearance choices, checked selection semantics, system/Arabic/Spanish language changes and persistence after process restart.

RC.4 validation: 84 JVM tests passed and release lint reported no issues. Emulator pixels confirm #000000 in Dark Settings and the appearance dialog, with the retained داكن label and no Notifications section.

Colored appearance uses the original soft Home/Settings palette. The viewer surround matches Home (#EEF1FF), toolbars match the original Settings surface (#F5F6FF), and paper keeps its light tint (#F0F3FF). Paper tint is a display-only multiply filter shared by the base image and zoom tiles; cached source pixels and saved/shared PDF bytes are unchanged. Explicit viewer Night Mode takes priority. Reading Mode uses the same paper color. Returning to White restores untinted rendering.

Final RC.4 PDF checks verified all three colored viewer tones on a rendered Arabic PDF, paper tint after double-tap zoom, and removal of the tint when switching to White. The source PDF SHA-256 remained f5cc8c555c262372f94a0e2510806b079b69d0902ce34ffa397c9415cb6e8198.

RC.5 restores the original RC.3 surface, raised surface, pressed-state and navigation-band colors, without changing the Home background. The viewer surround now uses that same Home background rather than the stronger RC.4 blue.

RC.5 validation: signed ARM64 build, preview QA build, 84 JVM tests and release lint passed. Emulator screenshots verified the original Home (#EEF1FF) and Settings (#F5F6FF) backgrounds, matching viewer surround and retained light paper tint on the Arabic PDF, at fit and enlarged zoom.
