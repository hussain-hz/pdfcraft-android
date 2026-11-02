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

## Grouped font installation

The download catalog now shows one entry per family, containing every catalogued weight, plus
one button to install the complete catalog. Group downloads share aggregate byte progress and
reuse each verified cached face/licence, including after interruption; a family is displayed as
saved only when all its face/licence files are present. Installing a group opens Saved fonts for
explicit face preview and selection, without changing the PDF. Automatic missing-font recovery
also downloads the matching family together when it needs a remote face, then imports only the
exact original face for its edit retry.

Tests cover interrupted family download/resume without retransferring completed faces and
fully offline reuse. Device coverage downloads all six real catalogued fonts, checks their digests,
licences and native glyph support, and repeats the complete operation without network access.

Grouped-download validation: 102 JVM tests and 19 emulator instrumentation tests pass.

## Phone fonts

Android 10+ SystemFonts.getAvailableFonts supplies registered font directories, supplemented by
standard system/product/system_ext/vendor and downloadable-system-font locations. The scan
uses only accessible canonical roots (at most 16), runs off the UI thread and retains the existing
native byte/entry/depth bounds, name-table verification and document-embedding validation.
No storage permission, font copying, redistribution or system-font changes are involved.

Phone fonts has its own screen under Saved fonts, with matching face names first and an explicit
preview for replacements. Original-font recovery checks eligible saved/phone faces for exact
PostScript identity and draft glyph support before trying a network download. System results are
cached per controller and refreshed when reopening the Phone fonts screen. Variable fonts,
collections and fonts that fail the existing licensing/embedding checks remain unsupported.

Validation: 102 JVM tests pass. All 19 existing device tests passed with this implementation;
a new twentieth test independently creates an Arabic subset PDF from the emulator's actual
/system/fonts/NotoNaskhArabic-Bold.ttf, verifies an initial font-preserving edit is refused,
then exercises AppViewModel recovery using the original phone font. Save/reopen retains the
original face, text, color and size, and no Noto Naskh file is downloaded/copied into the private
font library. The first test fixture used Latin text and correctly detected Android's Roboto
fallback rather than Noto; the fixture was corrected to Arabic before the successful test.
Samsung's SEC Naskh cannot be runtime-verified on this AOSP emulator; device availability and
embedding eligibility still determine whether that specific face can be used.

## Unified font list and automatic first-run setup

The font toolbar now opens one Fonts dialog directly. Eligible system faces appear first, followed
by downloaded/imported faces, deduplicated by normalized face name and alphabetized within each
source. Import remains available and Download fonts is the last entry. The separate Saved fonts
and Phone fonts navigation options are removed; replacement previews return to the same list.
Existing Latin standard-face controls remain in that list before import/download.

After engine initialization, Android prepares the phone catalog and downloads the catalogued font
families in the background without waiting for an open PDF. Subsequent launches verify/reuse
cached files offline. Failed transfers leave phone fonts usable and show a nonmodal retry hint
inside the font list. Manual download remains available for retry. All font cache writes share a
mutex so startup, user requests and font recovery cannot race on the same .part files. No document
is modified by this setup.

Validation: 104 JVM tests pass, including ordering/deduplication and simultaneous startup/manual
cache writes. All 21 emulator instrumentation tests pass, including setup against an empty
isolated font directory without opening a PDF, complete verified download and combined system /
private list checks. The user confirmed successful phone use of RC.6 before these list changes;
this UI/setup revision still requires its own phone trial.
