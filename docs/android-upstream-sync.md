# Android upstream sync: 2026-11-02

Source: [artcraft-android/pdfcraft-android](https://github.com/artcraft-android/pdfcraft-android), from `1efb657f008e3867613caa786a60d0f9f542fdb9` through `66e832c5dec7bd3b81e1ca94a6544b53a10b4cda`. GitHub's main branch was checked again on 2026-11-02; it still ends at the latter commit.

## Complete audit

There are 11 commits in this interval, including seven functional changes. This is an audit of merged Android main changes, not all historical engine changes or unmerged translation pull requests.

| Commit | Change | Treatment in this fork |
| --- | --- | --- |
| [c1199027](https://github.com/artcraft-android/pdfcraft-android/commit/c1199027) | Android JavaScript tagged-pointer fix | Enable Boa `jsvalue-enum` on Android; preserve detailed worker panic reporting. |
| [49cdab1e](https://github.com/artcraft-android/pdfcraft-android/commit/49cdab1e) | Form button scripts, script alerts and field validation feedback | Adapt to the 0.17 structured script output and document edit serialization. |
| [e3066849](https://github.com/artcraft-android/pdfcraft-android/commit/e3066849) | Upstream release version | Keep our version sequence; prepare 0.0.5-rc.1. |
| [75565b15](https://github.com/artcraft-android/pdfcraft-android/commit/75565b15) | Engine 0.14.0 merge | Keep our newer 0.17.0 engine and Arabic fixes. |
| [796fa928](https://github.com/artcraft-android/pdfcraft-android/commit/796fa928) | Read from tapped word/selection; restart after end | Integrate while retaining localized UI and existing saved preferences. |
| [fc3d06a4](https://github.com/artcraft-android/pdfcraft-android/commit/fc3d06a4) | Mouse wheel, Ctrl zoom and mouse text selection | Integrate with themed page rendering and cancellation of pending selection updates. |
| [bed49ce8](https://github.com/artcraft-android/pdfcraft-android/commit/bed49ce8) | Desktop zoom buttons and shared zoom steps | Integrate while keeping this fork's fit-page control and scroll bounds. |
| [6c7ec752](https://github.com/artcraft-android/pdfcraft-android/commit/6c7ec752) | F-Droid badge | Link to the upstream listing with explicit upstream labeling; this fork remains distributed through its GitHub releases. |
| [3b8300a8](https://github.com/artcraft-android/pdfcraft-android/commit/3b8300a8) | Preserve scan after process death behind camera | Extend saved state to retain our per-page crop, filter, strength, rotation, order and searchable/name preferences. |
| [315a9c1d](https://github.com/artcraft-android/pdfcraft-android/commit/315a9c1d) | Optional search result counter | Add localized preference, disabled by default, and viewer count. |
| [66e832c5](https://github.com/artcraft-android/pdfcraft-android/commit/66e832c5) | Red icon | Intentionally excluded at the owner's request. |

## Compatibility decisions

- Preserve the original app icon, all five themes, colored paper/toolbars, document night mode, status-bar preference, Arabic font preservation and downloads, attachments and advanced scan review.
- The 0.17 `js_run` response contains alert objects rather than strings. Android extracts their message. Edits that run field calculations also return structured alerts/requests, so the viewer does not lose those messages.
- Scan state contains paths and small metadata, never bitmaps or live native document IDs. Only files directly inside the app's own camera cache are restored. Missing files are skipped, and pending retakes retain the correct page index.
- A scan that was being inserted into an open native document is recovered as a new scan, with an explanatory message. A native document handle cannot survive process death. An interrupted Add image operation with no restored target drops its own temporary photo.
- The owner confirmed that phone testing of 0.0.5-rc.1 succeeded before preparing stable 0.0.5. Stable metadata uses base version code 599 (ARM64 5992; ARMv7 5991), above the preview and 0.0.4.

## Validation

- ARM64 native low-disk build and Android debug/test APK builds succeed.
- 109 JVM tests pass, including saved scan state validation and zoom/page geometry.
- 28 instrumentation tests pass on the API 37 emulator, including the existing Arabic editing/style/font, Office attachment and scan review regressions. After removing the test-only default SavedStateHandle constructor and obsolete script resource, all three new instrumentation tests pass again.
- New device tests exercise real Android JavaScript calculations and button actions, alert message decoding, save/reopen, pending scan retakes with custom review settings, and rejection of foreign saved paths without deleting the source.
- Real process-death exercise: import a page, select grayscale and rotate, open the external phone camera, kill the background PdfCraft process with `am kill`, capture/confirm a photo, and return. The scan returns with two pages; page one's rotation and grayscale settings remain intact.
- 92 JavaScript crate tests pass. Automation tests pass: 45 unit and 313 integration tests. An older multi-file import assertion was updated to expect the existing damaged-Word diagnostic rather than the pre-Office-support unsupported-conversion message.
- Android lint: zero errors, one existing Android Gradle Plugin version advisory. `git diff --check` is clean; icon assets are unchanged.

Physical desktop/DeX behavior and spoken audio need compatible hardware/voice data; emulator checks do not establish those hardware capabilities.
