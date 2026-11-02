# Android tools for engine 0.17.0

Version 0.0.4-rc.2 exposes new engine capabilities in the existing Android interface:

- Export PDF: Markdown (.md), with `text/markdown` when saving to Downloads. Word, RTF, Excel, PowerPoint and page-image export remain available.
- Edit PDF: eight independently draggable image handles; image-specific crop with Apply/Cancel; remove an editor crop; flip horizontally/vertically; replace and save raster images; move images forward/backward or to either end of the drawing order. Grouped Form artwork retains the engine's restrictions on raster-only actions. Successful mutations use the existing document lock, refresh and undo history.
- Text formatting: align selected lines while editing; a multiline paragraph dialog permits inserting empty lines even when the inline editor retains fixed source rows. The formatting bar scrolls on narrow screens.
- Compress PDF: explicit lossless CCITT Group 4/JBIG2 or retained monochrome compression, plus optional downsampling to 300 dpi above 450 dpi. The selected color/grayscale compression level remains independent.
- Comments: import comments from another PDF onto matching page numbers through the system document picker.
- Bookmarks/contents: select up to nine heading styles in order, or use tagged headings, to generate contents. Generation remains undoable.

New labels are supplied in Arabic, English and Spanish. Dialogs use the selected app palette, including the OLED black theme. Engine rendering, forms, export and unsigned page-deletion fixes are inherited from the engine upgrade; desktop menus are not copied into Android.

## Validation

Local debug builds and all 97 JVM tests pass. Eleven instrumented tests pass on the API 37 emulator using the Android ARM64 native library: Arabic editing/save/reopen; crop/flip/rotation/replacement/resize/image export; all four stacking operations and undo; Markdown/Office export; all three monochrome compression modes; comment import and heading generation with undo; page deletion/save/reopen; paragraph breaks and per-line alignment; form values and deletion of one radio widget; Word import and attachment extraction; WAV extraction; Downloads MIME and retained sharing after deleting the temporary source.

Interactive QA on the optimized preview verified Markdown export to Downloads in English and Arabic; crop/apply/remove-crop; independent image resizing; image flipping/export; replacement through Android Photo Picker; comment import through the document picker; heading generation and automatic navigation to the generated contents; multiline paragraph editing/save/close/reopen; and centering only the cursor line while retaining the other Arabic text. Exporting the reopened document to Markdown preserved both its Arabic text and the inserted blank line and Latin text. Arabic image-tool dialogs were checked under all five appearance choices.

QA caught and fixed stale image selection geometry after Undo/Redo and untranslated Undo notices. The last optimized-preview check resized an image, undid it, and verified that the stale handles disappeared and the notice was Arabic. The generated-contents tab also now opens immediately after successful generation.

The validated application code is `c4a595e3`; [compatibility CI](https://github.com/hussain-hz/pdfcraft-android/actions/runs/41361502640) passes, including the engine suites, Android JVM tests, lint and optimized preview build. Subsequent changes only record validation and move the unchanged Android test dependency versions into the existing version catalog. The final instrumented run passes all 11 tests on the debug build; optimized-preview UI testing is separate. Instrumentation does not require weakening the production R8 rules. Local signed ARM64 and ARMv7 APK builds and release lint pass; final lint reports 0 errors and 1 warning about a newer Android Gradle Plugin. Both APK certificates match the production signing key. ARM64 is version code 4022 and ARMv7 is 4021. ARMv7 was built and signature-checked, but its runtime was not tested on this emulator.

The native library is unchanged from engine 0.17.0 rc.1. Device tests used API 37 with ARM64 translation on the x86_64 emulator. Physical-phone validation remains pending; rc.2 is a trial build, and stable 0.0.3 remains the latest stable release. Candidate updates must pass emulator checks before publishing, as requested by the maintainer.

To run the device suite with a fresh native build, set up the pinned fonts as described in [the build notes](jahfan-import.md), start an emulator with a matching ABI, and run from `apps/pdfcraft-android/android`:

```powershell
./gradlew.bat connectedDebugAndroidTest "-Ppdfcraft.abis=x86_64" --max-workers=2 --no-daemon
```

For local UI-only iteration, the verified ARM64 library from rc.1 was reused because the Rust library and bindings API did not change. Kotlin bindings were generated from an unstripped host build of the same source; Android runtime tests verify compatibility with the APK library. Normal CI/release builds compile the native library from source.
