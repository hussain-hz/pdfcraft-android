# Jahfan preview validation — 2026-10-20

Engine: PdfCraft **0.9.0**, upstream release commit `645016addcfb9078c557f0189559ab47cdcee057`, plus the preserved Android compatibility extensions documented in JAHFAN.md.

## Automated checks

| Suite | Passed | Failed |
| --- | ---: | ---: |
| Android mobile bridge / engine workflows | 25 | 0 |
| Android JVM unit tests | 60 | 0 |
| Annotation library | 42 | 0 |
| PDF creation and Office import | 49 | 0 |
| PDF export | 26 | 0 |
| PDF rendering and inspection | 301 | 0 |
| Total | 503 | 0 |

The original mobile suite also passed all 16 tests before the engine update. The upgraded suite includes explicit checks for Office import → Excel export → PDF save/reopen and invalid photo detection. Voice comment creation, extraction and save/reopen, digital signatures and signer trust, forms, search, selection, annotations, rendering, passwords, attachments and undo are covered by the mobile tests.

The digital-ID test uses an eight-character password and confirms that the older seven-character sample is rejected, matching the upstream 0.9.0 password policy. The annotation-display and Office-detection test expectations retain the capabilities already present in the Android source.

## Build and emulator checks

- Local Android builds and JVM tests succeeded with JDK 17, Rust 1.99.0, SDK 37 and the installed NDK 30.0.16248370 override. CI defaults to JDK 21 and pinned NDK 29.0.14206865; a cloud CI result is reported separately.
- Both ARM64 and x86_64 native libraries were built; the downloadable phone preview contains ARM64 only.
- The app was installed and launched on the existing Android API 37 x86_64 emulator.
- A synthetic PDF was opened through Android's document URI and its text was visually verified.
- Arabic and English were selected through the app's own language preferences. Arabic layout direction and English layout direction were observed, including the navigation labels.
- Arabic toolbar labels were adjusted to avoid splitting connected words across lines and visually checked.

## Remaining validation

This preview is debug-signed. Production signing, full manual testing of every tool on physical devices, long-session stability, large-file speed and memory benchmarks, and comprehensive Arabic layout review remain before a production release. Automated tests do not prove every feature is unchanged or that performance is better. OCR language limitations remain those of the engine; this work does not add Arabic OCR.

## Arabic font correction — 0.1.0-beta.2

- Embedded the pinned Noto Sans Arabic font from craft-fonts revision `d1cf47d05e04426615fbda1d637b9ca6b1e7af35`, SHA-256 `63111b5b2e074dd48cc67692e0a2726d86ee94c1c37fe8598257b7b4e87e869e`.
- All 19 mobile tests passed with the required font, including Arabic addition on a blank page, save/reopen, search and rendering. The 13 Arabic-specific edit-library tests passed as well (additional to the table's suites).
- The previous build lacked the font. The new build requires a font source and includes the Noto OFL licence in its licences screen.
- Replacing an existing Latin-font paragraph with Arabic still reports the engine's missing-glyph/fallback error; the correction covers Arabic added text and its supported editing paths.
- Fixed an intermittent startup race observed on the emulator: the engine startup coroutine could access a lazy delegate before its property initializer ran.

- The saved Arabic sample was displayed on the API 37 emulator: connected letters and correct reading order were visually verified. The corrected app started successfully without a crash.
- The ARM64 APK built successfully; its signature and embedded Arabic OFL licence were verified. Android JVM tests passed.

## Touch manipulation — 0.1.0-beta.3

- Creation dialogs and signature capture remain the same. Placed items are selected automatically; selecting existing items allows a 20 dp proximity margin and prefers a direct hit over a nearby object.
- Selected text and Fill & Sign items have 48 dp move/resize touch controls. Their bodies can be dragged without opening the keyboard. Scaling keeps the signature aspect ratio and changes a text item's font size, not just its wrapping width. One completed gesture creates one undo step.
- Added page text is addressed by its content index using `content_update`, preserving Arabic shaping metadata. It is excluded from source paragraph selections using the engine's mixed-object inventory. Its ordinary text edits/deletion also use the added-content API.
- All 21 native mobile tests passed, including Arabic/English text transforms, image signatures, real Fill & Sign font sizes, undo/redo and save/reopen. All 52 JVM tests passed; six new cases cover selection proximity, zoom-independent deltas, minimum size, page-edge limits and signature proportions.
- On the API 37 emulator, Arabic text was selected, moved by dragging its body, and enlarged with the resize handle. A signature was drawn using the existing capture screen, placed, moved by its body and enlarged with the handle. Both results were visually checked in the Arabic interface. An English-interface text transform was also checked.
- Screen-space overlays use absolute top-left alignment as well as absolute offsets. This prevents RTL layout from moving the touch targets away from the PDF object. The corrected menu positions stay within the viewport width.
- The transform preview is an outline; the PDF artwork updates when the finger lifts. The preview remains a debug-signed ARM64 build, with physical-phone testing still needed.

## Resize and fit refinement — 2026-10-20, 0.1.0-beta.4

- Removed the separate four-arrow move control; the object body remains draggable.
- Replaced diagonal projection with equal horizontal/vertical scale sensitivity. The resize control follows the finger on both axes during a gesture. On the Arabic API 37 emulator, a purely downward drag enlarged the text selection from approximately 401 to 540 screen pixels; a purely upward drag reduced it to approximately 411 pixels.
- Added font-measured compact `ink_rect` data for added text while keeping its wrapping rectangle separate. Selection no longer spans the unused 200-point default wrap width for short text; movement/scaling preserves the source wrapping/alignment box. The mobile regression checks compact bounds for English and Arabic.
- Added a lower-corner Fit page button, initially hidden, shown after zoom changes, hidden again after fitting. On the emulator it appeared after double-tap zoom in the Arabic viewer and disappeared after fitting. The button remains available with the viewer bars hidden.
- All 21 mobile engine tests and all 55 JVM tests passed. New JVM cases cover equal vertical/horizontal resize sensitivity and fitting continuous/single-page mode between unequal toolbar insets, including the first and last page.
- Android ARM64/x86_64 build and the Arabic emulator visual checks succeeded. The deliverable remains a debug-signed preview for physical-phone feedback.

## Audit corrections — 2026-10-20, 0.1.0-beta.5

- Added `DocumentSaver` and a durable `RecoveryStore`: saves serialize local persistence and external publication; local checkpoints run after 600 ms without edits. Opening a second document uses the same failure prompt as closing. A pending working copy is reused after process restart instead of overwritten from the old source. File-URI publication uses a synced staging file and atomic move; arbitrary document providers retain the recovery copy until successful publication (their external writes cannot be assumed atomic).
- On the API 37 emulator, moved Arabic text, observed its recovery marker, force-stopped the process and reopened the source. The app displayed the recovery message. Opening another file while the original was read-only displayed Save copy/Discard and retained the current document.
- Added regression coverage for a simulated interrupted external write and retry, recovery across a recreated store, and retention of recent share files. Shares are retained for at least 24 hours; old entries expire after seven days or under the 256 MiB cleanup policy.
- Opposing diagonal resize deltas are continuous; horizontal and vertical drags retain equal sensitivity. Dragging shows a translucent crop preview of the selected content, with a single final undoable edit. TalkBack actions provide movement and size changes. The preview also includes background pixels within the selection; it is not a separate vector extraction of the object.
- Text-only edits skip unrelated widget/comment/link reloads; annotation edits retain comments refresh, and unknown/structural edits conservatively refresh everything. Added-content queries accept a page filter. Page-image cache sizing uses the real app memory class.
- A uniformly styled, upright source paragraph can fall back to the bundled Arabic shaped-content path when its source font cannot support a whole-text replacement. The original paragraph is removed and its replacement added in one atomic undoable batch. Tests cover Arabic and Arabic/English text, save/reopen/render, one-step undo/redo and rollback for unsupported characters. Mixed styles, rotations and explicit formatting are conservatively refused rather than flattened.
- Fixed all four Lint errors, primitive state boxing, dynamic window sizing, scrolling composition reads, backup configuration, language bundle configuration and KTX suggestions. Preview CI now runs Lint. Remaining Lint warnings concern pinned dependency/toolchain versions, target-SDK/API compatibility, and an optional storage-allocation API improvement; none are suppressed. Production builds now require a release key or an explicitly unsigned build instead of silently using a debug key.
- Automated validation: 60 Android JVM tests and 25 mobile native tests passed. The manual host benchmark is an additional ignored-by-default test, run separately. The full automation-library suite also passed all 35 tests (additional to the cumulative table above). The older 418 core-library results in that table were not rerun in this audit-fix turn.

### Reproducible host baseline

`python tools/generate-benchmark.py <directory>` creates deterministic text/vector fixtures. Set `JAHFAN_BENCHMARK_DIR` and run `cargo test -p pdfcraft-android large_document_benchmark -- --ignored --nocapture` with the required Arabic font.

| Pages | Open ms | First-page render ms | Add Arabic text ms |
| --- | ---: | ---: | ---: |
| 150 | 302 | 10 | 5 |
| 500 | 14 | 10 | 5 |
| 1000 | 21 | 11 | 11 |

These are single host measurements, with cold initialization affecting the first case. They are not before/after comparisons, phone-frame-rate results, or image-heavy/scanned-PDF benchmarks. Physical-device and long-session profiling remain necessary before production.


## Optimized preview — 2026-10-20, 0.1.0-beta.6

- Added a non-debuggable `preview` build using release R8/resource shrinking with the existing debug package ID and signing key. Production release signing requirements remain enforced. CI now publishes this optimized preview and retains lint reports and the R8 mapping.
- ARM64 APK: beta.5 was 108,319,904 bytes (103.30 MiB); beta.6 is 46,280,209 bytes (44.14 MiB), a 57.27% reduction. The old incremental ZIP contained about 31.6 MB of gaps; the new APK was freshly packaged. The remainder of the reduction comes from release shrinking/packaging. This measures the APK file, not installed storage or runtime speed.
- The ARM64 engine shared library, both OCR model files and Arabic font licence are byte-identical to beta.5. Arabic remains embedded in the unchanged native library. Only ARM64 native libraries are in the phone APK.
- Updated Gradle 9.8.2 (official distribution SHA-256 verified), AGP 9.4.1, Kotlin Compose 2.4.21, Core 1.19.1, Lifecycle 2.11.0, Activity 1.13.0, Coroutines 1.11.0 and ExifInterface 1.4.2. Target SDK is now 37; minimum remains 29. Reviewed official Android 17 behavior changes; no app use of MessageQueue reflection, static-final mutation, RemoteViews widgets or locked orientation was found.
- Voice-comment storage preflight runs on Dispatchers.IO, uses StorageManager allocatable space and allocation to reclaim eligible cache, and falls back to usable space on I/O/security failures. A closed/replaced document cannot open a recording prompt after the asynchronous check.
- All 60 JVM tests passed. Final `assemblePreview testDebugUnitTest lintPreview` succeeded. Lint: zero errors, two UnusedAttribute warnings for localeConfig/enableOnBackInvokedCallback on API <33, intentionally retained without suppression. Compiler/tool deprecation notices are separate from this lint count; AGP still calls deprecated Gradle Configuration.setVisible.
- On API 37 x86_64, installed the optimized preview over the existing app without clearing data, opened/rendered Arabic, dragged its body and resized vertically (selection width 286 to 417 px). After the toolchain upgrade, installed again, reopened the modified document, verified the read-only save protection prompt, saved a new copy through the system picker and reopened it; connected Arabic and transformed placement/size remained visible.
- apksigner verified the final ARM64 APK. Its certificate matches beta.5. SHA-256: `f872e29a5c445047cb27e48ca31f9c8ad83c2c6a0bab6f6252b16a57116d2240`.
- Native engine code did not change in this turn; its previous native regression results were not rerun. Physical ARM64 device testing, older Android device coverage, low-storage fault injection and a production signing key remain outstanding. Local preview update compatibility was verified; independently generated CI debug keys are not guaranteed to match local keys.

References: [Android 17 behavior changes](https://developer.android.com/about/versions/17/behavior-changes-17), [AGP compatibility](https://developer.android.com/build/releases/agp-9-4-0-release-notes), [AndroidX release notes](https://developer.android.com/jetpack/androidx/versions), [Kotlin releases](https://kotlinlang.org/docs/releases.html).
