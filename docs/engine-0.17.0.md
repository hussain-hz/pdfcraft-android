# Engine upgrade to PdfCraft 0.17.0

The engine is based on the official `v0.17.0` release at `7d78f3e14474b978be014715e0cc64895d77025a`, replacing the [0.14.0 baseline](engine-0.14.0.md). Engine crates, vendor patches and their asset attributions are imported together. The Android application retains its name, package identifiers, icon, themes, translations and stable-only updater.

## Integration

Upstream split annotation appearances, rendering, editing and export into smaller modules. The mobile extensions are carried into those modules, including Arabic contextual shaping and source-font selection, logical ActualText, fixed source rows and mixed-style fragments. Paragraph wrapping retains upstream soft-line, spacing and alignment handling.

Office import (DOCX, XLSX, PPTX and RTF), Excel export, sound annotations and WAV extraction remain available. Excel export now consumes upstream paragraph runs and joins their text into a single cell, preserving the spaces inserted by paragraph grouping. A regression covers a paragraph containing an italic run.

The font build retains the pinned required Arabic input and the `Arab` script filter. Native Chinese font discovery applies when requested; it does not make the Android Arabic-only build require an unrelated font package. Existing Android OCR models and application artwork are preserved.

New engine capabilities are available through engine APIs. Desktop menus and image-editing controls are not automatically added to Android by importing engine code.

## Validation

`cargo check --locked --profile low-disk -j 2 -p pdfcraft-android` passes locally. The [compatibility workflow](https://github.com/hussain-hz/pdfcraft-android/actions/runs/41352194593) passes 1,704 native tests, with 6 manual tests ignored and no failures, across the Android bridge, annotations, COS, creation/Office import, editing, engine, export, fonts, optimizer and rendering. The optimizer suite covers upstream rewrite and page-deletion behavior. Android unit tests, lint and the ARM64 preview build also pass.

The [release build workflow](https://github.com/hussain-hz/pdfcraft-android/actions/runs/41352193918) passes Android unit tests, lint and both ARM64 and ARMv7 builds. Both APK signatures were verified against production certificate SHA-256 `90832588907d0b3836d045455815e0993ba09f7a36e51f32f701b105cff7dfa3`; the ARM64 package is `io.github.artcraftandroid.pdfcraft`, version `0.0.4-rc.1`, code `4012`.

The [0.0.4-rc.1 prerelease](https://github.com/hussain-hz/pdfcraft-android/releases/tag/preview-0.0.4-rc.1) is available for device testing. Stable `0.0.3` remains the latest stable release; automatic stable updates exclude this prerelease. Physical-device validation is still pending.
