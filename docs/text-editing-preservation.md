# Text editing: preserve document fonts and formatting

Typing in a PDF paragraph now uses its embedded face, or an approved complete face of the
same family, instead of always drawing the editor with an Android generic font. The source
font programs are passed through a read-only mobile API and kept in memory for that page.
Known outline-only (Type3) faces also get a matching preview. Existing Inter, JetBrains Mono
and static Noto Sans Arabic assets complete matching subsets; no new font assets are added.

The Arabic rewrite searches the page for a usable complete source face before selecting a
fallback. Ordinary Android text edits request `preserve_font`: if the source or a matching
complete face cannot show the replacement, the edit is refused on a document copy. Working
bytes and history remain unchanged. A refused draft remains available in the paragraph editor
during the session. Choosing a font or style explicitly through the formatting controls remains
an intentional formatting operation. Other tool clients retain the previous default policy.

Paragraph listings now report their fill color, weight and italic state; added-item listings
report color too. The Android editor consumes these values and associates added text with
its extracted source face. This fixes the black editor text shown for colored paragraphs.
Text-only edits continue to inherit source run colors, sizes, weights, italics and row breaks.
Mixed-style paragraphs retain the styles of both edited and untouched words.

## Validation

Validated locally on the existing API 37 emulator, running the newly compiled ARM64 library
through its ARM64 compatibility layer. The production app's data was not cleared. This is a
development build, not a replacement for the published rc.2 release assets.

- Native suites: 434 edit, 420 engine, 94 font, 30 mobile and 45 automation tests passed;
  six existing manual tests were ignored. The mixed-style preservation test was additionally
  rerun using the Android `preserve_font` policy after extending its assertions to the edited word.
- Android JVM tests: 97 passed.
- Android instrumentation: **15 passed**, including all previous document tools and the new
  font/formatting cases. The final run completed in 11.363 seconds.
- Android lint: zero errors; one existing warning about a newer Android Gradle Plugin version.
- Manual UI: edited Inter text in the inline editor, committed, saved and reopened it. Edited a
  blue, bold Arabic paragraph, committed, saved and reopened it; its blue color, bold face,
  font size and two source rows remained intact.

The new device cases check source-font preview measurements against the actual Inter resource,
font identity after save/reopen, unchanged bytes/history on refused substitution, Arabic and
English color/size/weight/italic/run baselines, and added-text formatting metadata after retyping.
Test-only source fixtures rename equal-length private added-object keys, so the source paragraph
path is exercised rather than only the added-object update path.

The local QA build uses a fresh `cargo ndk ... build --profile low-disk` library from these
sources, with bindings generated from that same library. It does not reuse the previous APK's
native library. An official release still needs its normal optimized build and physical-device
trial; an unavailable original font cannot be reconstructed from missing PDF glyphs.

The follow-up [document font library](document-font-library.md) adds original-font import,
verified on-demand downloads and explicit replacement previews.
