# Engine upgrade to PdfCraft 0.18.1

The Android engine imports the stable upstream `v0.18.1` release at `8bb0528bed785283452263b0290321ccf95859be`, following the [0.17.0 integration](engine-0.17.0.md). No unreleased upstream main changes are included.

## Integration

The engine, annotation and export crates, image decoders and vendor patches are imported together. This includes bounded image decoding, JBIG2 rendering and monochrome optimization, redaction fixes, page-tree construction, form/XFA fixes, and CSS-free HTML export. Desktop frontend changes are kept in their upstream Rust crate; they do not replace the Android interface.

The Android export screen offers formatted HTML and simple HTML without CSS. Both produce `.html` files with the correct MIME type; the simple mode retains text, emphasis, tables and inline images while omitting CSS colors and layout.

Fork extensions remain: contextual Arabic shaping and ActualText, positioned source words, exact document/system/imported font selection, Microsoft Core Fonts, mixed formatting/color preservation, Office import, Excel export and sound annotations. The Office importer now accumulates its page tree and writes it once, following the upstream creation API. Multiline Fill & Sign measurements use the same font measurements as annotation appearances. The app identity, icon, five themes, scanner and stable-only updater are retained.

## Release and validation

The owner approved `0.0.7-rc.1` for stable `0.0.7`. Stable base code 799 produces ARM64 7992 and ARMv7 7991, above preview 7012/7011 and stable 0.0.6 (6992/6991).

- 2,773 native tests pass across the mobile bridge, annotation, audit, automation, COS, creation/Office import, text editing, engine, export, fonts, optimizer, redaction, renderer, XFA, cryptography, image filters and renderer integration; 12 existing manual cases are ignored. The three font-build fixtures pass when run without the inherited Arabic script filter (the first aggregate invocation exposed that environment mismatch).
- 114 Android JVM tests pass. Lint has zero errors and one existing Android Gradle Plugin advisory.
- 32 ARM64 emulator instrumentation tests pass, including original font/style preservation, Arabic edits and save/reopen, Microsoft font packs/imports, scanner and upstream mobile regressions. The new HTML test verifies Arabic and inline images in both modes, no CSS in simple mode, unchanged source text and save/reopen.
- Manual Arabic/colored-theme review confirms both HTML export options and successful simple HTML delivery to Downloads with the correct `.html` name and CSS-free content.
- Debug and signed release ARM64 builds succeed. The production-signed preview installs over stable 0.0.6 without clearing data (7012 > 6992); existing files and settings remain. The signed About page reports engine 0.18.1 and version 0.0.7-rc.1. The signed APK opens Arabic text and inline artwork and exports CSS-free HTML through the actual UI.
