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

The earlier local concurrent native/Android build exhausted memory. Final checks ran with limited parallelism and passed. Manual physical-phone checks, especially language changes with unsaved documents and Arabic touch editing, remain outside this validation. Historical `VALIDATION.md` reports describe the original Jahfan builds separately.
