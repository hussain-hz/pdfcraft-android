# Engine upgrade to PdfCraft 0.14.0

The engine is based on the official `v0.14.0` release at `5a78b223871add8e3883856205cc653d741e0e94`, replacing the 0.9.0 baseline `645016addcfb9078c557f0189559ab47cdcee057`. Upstream engine crates, patched vendor libraries, assets and attribution are imported together. The Android Kotlin UI and UniFFI bridge remain the application frontend; desktop UI changes do not automatically become Android features.

## Preserved application extensions

- Arabic full-word extraction, contextual shaping across style fragments, embedded regular/bold Arabic fonts, source-row preservation and per-row editing styles.
- Local Office import (DOCX, XLSX, PPTX and RTF), XLSX export, sound annotations and WAV extraction, and photo-edge detection.
- Mobile annotation opacity, border width and raw timestamps; compact text selection bounds and page-filtered content queries.
- Existing Android translations, touch transforms, recovery, serialized saves and retained share-copy allocation. The beta.12 attachment IO-dispatch correction is retained.
- PdfCraft production and preview package IDs, application name, icons and signing configuration.

## Integration decisions

Upstream paragraph tracking, source paint-state preservation, vertical text and tracking-aware word spacing are combined with the Arabic extensions. New comment parent references, upright icons and geometry fields coexist with Android styling metadata. The font build keeps the mobile script filter while incorporating upstream font handling. Vendored CMap and PostScript fixes are included rather than relying on unpatched registry copies.

Historical 0.9.0 import/build results are in [the original integration report](jahfan-import-validation.md). The build recipe and pinned Arabic font source remain in [the import notes](jahfan-import.md).

## Validation

Validation of this upgrade is in progress. Results will be recorded after native, Android, lint and APK checks complete. The earlier beta.12 physical-phone result does not validate engine 0.14.0.
