# Jahfan improvements imported into PdfCraft for Android

The local Jahfan source at commit `a23c160d662a8828e105b2346b1fe73fa68dfc2f` was merged with this repository's `main` at `723fb92`. Their common Android ancestor is `62f378a`.

For the subsequent upgrade to engine 0.14.0, see [the engine upgrade notes](engine-0.14.0.md). The 0.9.0 details below describe the original import.

## Included improvements

- PdfCraft engine 0.9.0, based on upstream release commit `645016addcfb9078c557f0189559ab47cdcee057`, including Jahfan's Android compatibility extensions for voice comments, Office import, Excel export and photo-edge detection.
- Bundled Arabic document fonts, Arabic shaping in added text and annotations, replacement of source text with compatible Arabic fonts, complete editable Arabic words, preserved source rows and per-row styling in the inline editor.
- Touch-friendly movement and proportional resizing for text and signatures, live drag previews, accessible transform actions, compact selection bounds and a contextual Fit page control.
- Serialized saves, durable recovery checkpoints, recovery after process restart, safe document transitions and retained shared copies.
- Targeted metadata refresh, a memory budget based on the app's actual heap, asynchronous storage checks before voice recording and the startup initialization fix.
- Optimized preview builds, Android SDK 37 and the updated Gradle/Android dependencies from Jahfan.

## Preserved destination behavior

The app is still **PdfCraft**. Its application ID remains `io.github.artcraftandroid.pdfcraft`; preview/debug builds retain the `.debug` suffix. Existing icons, signing configuration, attribution and licence notices are retained. Exported images use `Pictures/PdfCraft`.

The destination's Spanish translation, resource-based UI text, storage-error handling during sharing, complete bundled licence viewer and certificate-issuer improvement are preserved. Arabic translations are imported into Android string/plural resources rather than replacing the destination's translation system. Settings allow choosing Arabic, English or Spanish.

The engine remains **0.9.0**. This import does not upgrade it to 0.14.0. The imported native engine and mobile bridge sources are unchanged from the Jahfan source commit.

This document describes the PdfCraft integration. See [the import validation](jahfan-import-validation.md) for the associated build and test results.

## Build and verify

Use Rust 1.99.0, JDK 21, Android SDK 37 and NDK 29.0.14206865. The minimum Android version remains API 29. JDK 17 can also compile the Java 17 sources. A locally installed NDK can be selected with `-Ppdfcraft.ndkVersion=VERSION`.

Prepare the required Arabic font before native compilation:

```powershell
git clone https://github.com/storytold/craft-fonts .build-inputs/craft-fonts
git -C .build-inputs/craft-fonts checkout d1cf47d05e04426615fbda1d637b9ca6b1e7af35
. ./tools/prepare-arabic-fonts.ps1 -SourceDirectory .build-inputs/craft-fonts
cargo test --locked --profile low-disk -j 2 -p pdfcraft-android --lib
cargo test --locked --profile low-disk -j 2 -p pdfcraft-create -p pdfcraft-export -p pdfcraft-annot -p pdfcraft-render --lib
./apps/pdfcraft-android/android/gradlew.bat -p apps/pdfcraft-android/android assemblePreview testDebugUnitTest lintPreview "-Ppdfcraft.abis=arm64-v8a" --no-daemon
```

For memory-constrained machines, set `CARGO_BUILD_JOBS=2`, limit Gradle workers and run native tests separately from Android builds. Preview APKs are debug-signed; production release signing uses the existing private signing secrets.

The **PdfCraft preview and compatibility** workflow prepares the pinned Arabic font, runs engine/mobile and Android tests, checks lint and uploads the ARM64 preview APK. Manual checks on physical phones remain necessary for touch interactions, Arabic rendering and language changes with unsaved documents.

See [the current import validation](jahfan-import-validation.md) for test, lint and APK results.
