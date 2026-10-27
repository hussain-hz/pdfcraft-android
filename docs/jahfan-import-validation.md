# PdfCraft Android import validation - 2026-10-27

The Jahfan changes through `a23c160` were imported onto destination `723fb92`. The native crates, vendored libraries and Android Rust bridge are identical to the source Jahfan commit. The engine remains 0.9.0.

## Automated results

| Suite | Passed | Failed | Ignored |
| --- | ---: | ---: | ---: |
| Mobile bridge and engine compatibility | 28 | 0 | 4 |
| Annotation library | 42 | 0 | 0 |
| PDF creation and Office import | 49 | 0 | 0 |
| Text editing library | 173 | 0 | 0 |
| PDF export | 26 | 0 | 0 |
| Rendering and inspection | 301 | 0 | 0 |
| Android JVM unit tests | 65 | 0 | 0 |
| Total | 684 | 0 | 4 |

All 619 native tests passed in GitHub Actions run 40414135361. The 28 mobile tests also passed locally. The original CI run then failed on the duplicate locale-config setting; the integration now uses the destination's generated locale config. The native sources did not change during that correction. A subsequent local attempt to repeat all library tests ran out of disk space; its generated temporary host build files were removed. These facts do not change the successful CI native-test results.

Final local `testDebugUnitTest lintPreview assemblePreview` succeeded using Rust 1.99.0, JDK 17, SDK 37, NDK 30.0.16248370 and two Cargo/Gradle workers. The repository/CI NDK default remains 29.0.14206865. Lint reported **0 errors and 20 warnings**: 11 Spanish plural-quantity suggestions, four ellipsis suggestions, three KTX suggestions, one older-API attribute warning and one storage-space API suggestion. The repeated final check passed all 65 JVM tests.

## APK checks

- Optimized ARM64 preview, debug-signed: `apps/pdfcraft-android/android/app/build/outputs/apk/preview/app-preview.apk`.
- Application label: **PdfCraft**; application ID: `io.github.artcraftandroid.pdfcraft.debug` (the production ID remains `io.github.artcraftandroid.pdfcraft`).
- Version: `0.1.0-beta.10`, version code `100102`; minimum SDK 29, target SDK 37.
- `apksigner verify --print-certs` succeeded; this is a testing key, not a production release signature.
- Both OCR models and their licences, the native engine, the Arabic font notice and the destination's other font/icon/CMap notices are packaged.
- The generated app-languages configuration contains English, Arabic and Spanish. Resource checks found no missing string references, duplicate resource keys or Arabic format-placeholder mismatches.
- APK SHA-256: `83e28a2e46273e284f57ac28fc13f9895d7eef37497eb078f04e19275a1e8139`.

The earlier local concurrent native/Android build exhausted memory. Final checks ran with limited parallelism and passed. Manual physical-phone checks, especially language changes with unsaved documents and Arabic touch editing, remain outside this validation.

## Warning fixes - 0.1.0-beta.11

The updated local `testDebugUnitTest lintPreview assemblePreview` build passed. All **69 JVM tests passed**, including four new share-copy regression cases. **Lint: no issues found (0 errors, 0 warnings).** The 619 native tests are unchanged and will be rechecked by the updated GitHub workflow.

- Added the Spanish `many` plural forms using the existing plural translations and placeholders; replaced four three-dot menu suffixes with the ellipsis character; adopted the three suggested Kotlin extensions.
- Kept the intentional predictive-back opt-in. The manifest explicitly documents `tools:targetApi="33"` so lint understands that older platforms are expected to ignore this API-33 attribute. This is a scoped compatibility declaration, not a runtime behavior change or a global lint disable.
- Sharing now reserves each output file through `StorageManager.allocateBytes` where supported, and diagnoses failure using allocatable space with a filesystem fallback. Allocation and copying run on the existing I/O paths.
- New share copies live under private app files so cache reclamation cannot remove them while receivers are reading. Existing cache-based FileProvider grants remain supported. All source streams are opened before allocation, and failed batches clean up their own partial copies while preserving sources and earlier shares.
- Regression cases cover successful reservation/copy/truncation, allocation failure after a partial batch, a missing source before any allocation and refusal to overwrite a previous share directory.
- CI previously hit its one-hour limit. The timeout is now two hours, with Rust cache saving enabled on failed runs; the test suites are retained.

The updated preview is `0.1.0-beta.11` (ARM64 version code `100112`), with the same PdfCraft label, package and debug signing identity. APK SHA-256: `95eb95757f408740eb05a6ac79409aee1a04008799fcbfa543b4e7ffc7a48c8a`.

## Final attachment dispatch correction - 0.1.0-beta.12

Review of all share call sites found one non-PDF attachment path calling the synchronous share preparation from the UI coroutine. It now uses Dispatchers.IO, matching the other share paths and keeping storage allocation/copying off the UI thread.

The final beta.12 local build, all 69 JVM tests and lint passed again: zero errors, zero warnings. The native source remains unchanged. The preceding beta.11 GitHub run 40442015537 completed successfully, including the full native and Android suites; a fresh run will validate this last dispatch correction before merging.

Preview version: 0.1.0-beta.12; ARM64 version code 100122. APK SHA-256: `ca399cfe68f7073391b1b268437b240da358b47bb208ef5a818e5e13d3a9d4ac`.
