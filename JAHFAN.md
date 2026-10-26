# جحفان | Jahfan

Android PDF reader and editor with the original PdfCraft Android interface, Arabic and English.

## Source provenance

- Android UI and UniFFI bridge: `artcraft-android/pdfcraft-android`, original 0.0.4-beta Android app.
- Engine: `storytold/pdfcraft`, release **v0.9.0**, commit `645016addcfb9078c557f0189559ab47cdcee057`.
- The user-supplied Arabic source archive is a translation reference only. Its older engine and application logic are not used as the foundation.
- Upstream MIT / Apache-2.0 notices remain in the repository. Jahfan is a modified, unofficial application.

Application ID: `com.jahfan.pdf` (debug and optimized preview builds add `.debug`). Interface language follows the device initially and can be changed under Settings → Preferences → Language. Android 13+ also supports the system's per-app language setting. Document text and read-aloud voices are independent of the interface language.

## Mobile compatibility extensions

The upstream 0.9.0 engine is retained with the Android-specific extensions ported forward:

- Original PDF annotation timestamps, including the timezone.
- Annotation opacity and line width in inspection and refreshed comment summaries.
- Sound-annotation WAV extraction for voice comments.
- Selective hiding of sticky-note appearances while the Android viewer draws its own icons.
- On-device Office import, Excel export and photo document-edge detection from the Android source.

The upstream digital-ID creation policy requires at least eight password characters. The Android validation and translated hints follow that policy.

These extensions must be checked during every future upstream upgrade. Never replace the core solely by changing version numbers.

## Build

Use JDK 21, Rust 1.99.0, cargo-ndk 4.1.2, Android SDK API 37 and NDK 29.0.14206865.

The Arabic document font is a required build input. Prepare the pinned source before running Cargo or Gradle:

```powershell
git clone https://github.com/storytold/craft-fonts .build-inputs/craft-fonts
git -C .build-inputs/craft-fonts checkout d1cf47d05e04426615fbda1d637b9ca6b1e7af35
. ./tools/prepare-arabic-fonts.ps1 -SourceDirectory .build-inputs/craft-fonts
cd apps/pdfcraft-android/android
./gradlew.bat assemblePreview testDebugUnitTest lintPreview -Ppdfcraft.abis=arm64-v8a
```

The installed NDK may be selected explicitly with `-Pjahfan.ndkVersion=VERSION`. The default stays pinned for reproducible CI. The `preview` build enables R8 code/resource shrinking and keeps the debug application ID and signing key for local updates. `debug` remains available for development. Preview APKs are debug-signed; a private release signing key must be configured before distribution as a production release.

GitHub Actions builds a private downloadable preview artifact and runs the original mobile compatibility tests and Android unit tests. Manual device checks remain necessary for layout, language switching, reading, editing, forms, signatures and large-file performance. Passing compilation does not prove all features or performance.

## Arabic PDF font correction (0.1.0-beta.2)

Noto Sans Arabic is embedded in the native engine, separately from the interface font. The preparation script verifies the pinned font SHA-256 and licence; Cargo and Android builds require the font input, and the APK includes its OFL-1.1 licence. Only the Arab-script font is selected to avoid embedding unrelated large font families.

The regression test adds Arabic text to a blank PDF, saves and reopens it, searches the original Arabic string and checks rendered ink. The engine's Arabic shaping tests also pass. Replacing a pre-existing Latin-font paragraph with Arabic is a separate upstream limitation; this correction does not add that capability.

The startup coroutine now runs after all ViewModel properties are initialized, avoiding an intermittent null lazy delegate when the engine is already ready.

## Phone touch controls (0.1.0-beta.3)

The text and signature creation flows are unchanged. After placement, the item is selected with finger-sized move and proportional resize controls. Drag its body to move it; drag the diagonal-arrow control to change size. Text size follows the scale, and signatures retain their proportions. Tap Edit text explicitly to type. Touch targets use PDF screen coordinates in both Arabic and English; movement stays on the same page, and each completed gesture is undoable. Added text uses its own engine content identity so Arabic shaping survives manipulation and save/reopen.

## Resize and fit refinement (0.1.0-beta.4)

The separate move handle is removed. The resize handle follows the finger horizontally and vertically, and vertical drags have the same scaling sensitivity as horizontal drags even for thin text. Text selection uses a font-measured compact box; its original wrapping box is retained independently so moving/scaling does not change wrapping or alignment. Signature scaling keeps its proportions.

A lower-corner Fit page button appears after a zoom change. It fits the current page inside the space between toolbars, preserves continuous/single-page mode and hides itself after fitting. Its label follows the application language.


## Audit corrections (0.1.0-beta.5)

See [AUDIT-FIXES.md](AUDIT-FIXES.md) and [VALIDATION.md](VALIDATION.md) for recovery, safe document transitions, continuous resize, live drag preview, retained shares, targeted metadata refresh, accessible transforms, Arabic whole-paragraph fallback and validation. Run `lintDebug` with the build. Release builds require a configured production key (or explicitly unsigned packaging); preview builds remain debug-signed.
