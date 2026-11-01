# Engine upgrade to PdfCraft 0.17.0

The engine is based on the official `v0.17.0` release at `7d78f3e14474b978be014715e0cc64895d77025a`, replacing the [0.14.0 baseline](engine-0.14.0.md). Engine crates, vendor patches and their asset attributions are imported together. The Android application retains its name, package identifiers, icon, themes, translations and stable-only updater.

## Integration

Upstream split annotation appearances, rendering, editing and export into smaller modules. The mobile extensions are carried into those modules, including Arabic contextual shaping and source-font selection, logical ActualText, fixed source rows and mixed-style fragments. Paragraph wrapping retains upstream soft-line, spacing and alignment handling.

Office import (DOCX, XLSX, PPTX and RTF), Excel export, sound annotations and WAV extraction remain available. Excel export now consumes upstream paragraph runs and joins their text into a single cell, preserving the spaces inserted by paragraph grouping. A regression covers a paragraph containing an italic run.

The font build retains the pinned required Arabic input and the `Arab` script filter. Native Chinese font discovery applies when requested; it does not make the Android Arabic-only build require an unrelated font package. Existing Android OCR models and application artwork are preserved.

New engine capabilities are available through engine APIs. Desktop menus and image-editing controls are not automatically added to Android by importing engine code.

## Validation

`cargo check --locked --profile low-disk -j 2 -p pdfcraft-android` passes locally. Runtime suites and the Android APK build are pending; the compile check does not establish physical-device compatibility. The preview workflow also tests `pdfcraft-optimize` to cover upstream rewrite and page-deletion behavior.

The application version is `0.0.4-rc.1`, separate from the installed stable `0.0.3`. This source change does not publish a stable release.
