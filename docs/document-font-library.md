# Import and download document fonts

Edit PDF → select a paragraph → Font now offers importing a TTF/OTF file, downloading a font,
and selecting a saved font. Choices open a themed preview before applying anything to the PDF.
The original font name is shown; an exact normalized face-name match offers loading the original
face. Other faces are explicitly presented as replacements. Basic Latin font choices also use
the preview instead of applying immediately.

The initial download catalog includes Regular and Bold faces of Amiri, Scheherazade New and
Fira Sans. Files and OFL licences come from the official
[Google Fonts repository](https://github.com/google/fonts/tree/6c1dbe27dcf7d817d4b62cec033ba77fa8e2a5ec/ofl),
pinned to commit `6c1dbe27dcf7d817d4b62cec033ba77fa8e2a5ec`. Each font and licence has a SHA-256
digest in `font-catalog.json`. Transfers use HTTPS with timeouts, size limits and cancellation;
redirects and unexpected data are rejected. Verified fonts and their licences are kept in the
app's private files and reused without network access. No font binaries are added to the APK.

The system picker copies the selected file with a 16 MiB limit. Native validation checks its
font data, static face, embedding permissions and declared open licence. A new read-only
`text_font_check` verifies character coverage and complex shaping before preview, so a Latin-only
font cannot be applied to Arabic through a misleading system fallback. Fira Sans's shorter OFL
declaration is accepted only with a recognized official SIL/OFL licence URL.

Font choices capture the document, paragraph, pending draft and generation. Apply checks the
same document and generation under its save lock. Preview/cancellation does not register a font
or change document history. Loading a matching original retries the pending text using the
font-preservation policy. Explicit replacements retain source run colors and sizes.

For an added text item, `content_font` atomically converts one unambiguous paragraph into ordinary
editable source text and applies the chosen full face. One Undo restores the original added item.
Joined/ambiguous regions and invalid fonts are refused before changing the document. The text
remains editable after saving and reopening. This avoids leaving stale added-text metadata after
a custom face is applied. It does not support replacing the face of an added item that spans
multiple separate source paragraphs; those cases are refused rather than partially converted.

## Validation

- Native suites passed: 435 edit, 420 engine, 45 automation, 31 mobile; six existing manual tests
  ignored. The 94 font tests passed after extending OFL recognition.
- Android JVM tests: 99 passed, including offline cache reuse, corrupt-cache repair and refusal
  to install tampered transfer data.
- Emulator instrumentation: 17 passed. This includes real HTTPS downloads of Amiri and Fira
  Sans and their licences, digest verification, offline cached reuse, Arabic coverage checks,
  custom-font application, color/size retention, save/reopen, Undo/Redo, invalid-file refusal,
  and the existing editing/export/import/attachment/image suites.
- Manual Arabic UI: browsed the catalog, downloaded and previewed Regular/Bold Amiri, cancelled
  a preview with byte-for-byte unchanged source PDF, applied Amiri Bold to blue Arabic text,
  saved and reopened it. Used the Android Downloads picker to import the already-downloaded
  matching Amiri Bold face and load it successfully; duplicate data is resolved to the existing
  library entry. Preview and catalog screenshots were reviewed in the Colored theme.
- Final instrumentation run: 17 passed in 18.107 seconds.
- Lint: zero errors and one existing newer-AGP warning. Unused resources and KTX suggestions
  introduced during implementation were corrected.

QA uses the newly compiled ARM64 native library on the existing API 37 emulator, under the debug
application ID. No stable release or previous release asset is replaced by this source change.

## Automatic recovery of incomplete original fonts

When a font-preserving text edit is refused, Android now looks for the exact original PostScript
face in the private library and then the pinned download catalog. Subset prefixes and punctuation
are normalized, but ambiguous matches, weight changes and family aliases are rejected. A matching
font is checked for the draft glyphs, imported and the original edit retried under the existing
document identity/generation and save-lock guards. Other fonts still require an explicit preview.
The font dialog also remains available after leaving Edit PDF. If the original is unavailable,
its name is displayed with import/replacement choices and the pending draft is retained.

This is bounded automatic recovery for catalogued open fonts and locally imported exact faces;
it does not promise access to arbitrary proprietary fonts. Download integrity, licences, bounded
transfers, cancellation and offline caching use the existing verified path.

Validation: exact/ambiguous/weight matching and unrelated-error tests added to the JVM suite.
A new emulator test creates a genuinely subset Scheherazade New Bold PDF with Android PdfDocument,
verifies the initial edit is refused, exercises the actual AppViewModel text-commit recovery path,
then saves/reopens and checks the original face, text, color and size. All three font download
instrumentation tests pass, including the existing download/coverage/integrity/Undo checks.

Final recovery validation: 101 JVM tests and all 18 emulator instrumentation tests pass.
