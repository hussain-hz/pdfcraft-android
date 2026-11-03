# Windows Core Fonts and document font selection

Settings → Fonts manages the optional Microsoft Core Fonts pack and licensed user font imports. This is the classic **11-family, 30-face** pack, not every font shipped with Windows. Calibri, Tahoma and Segoe UI are not included. The user can import static TTF/OTF files or ZIP archives when licensed to use them; collections and variable fonts remain unsupported.

## Distribution and cache

- The original installers come from the [Core Fonts distribution](https://corefonts.sourceforge.net/). Their SHA-256 values are independently checked against [Debian msttcorefonts 3.8.1](https://sources.debian.org/src/msttcorefonts/3.8.1/cabfiles.sha256sums/).
- `core-font-catalog.json` pins each original archive's size and SHA-256, and each extracted face's size, SHA-256 and PostScript name. These were verified against Windows `expand.exe` output. No Microsoft font binaries are bundled in this repository or the APK.
- The original [Microsoft Core Fonts EULA](https://corefonts.sourceforge.net/eula.htm) is presented before installing. Modern Windows fonts have different redistribution terms; see [Microsoft's font FAQ](https://learn.microsoft.com/en-us/typography/fonts/font-faq).
- Downloads allow only HTTPS SourceForge distribution/mirror hops, bounded redirects, timeouts, cancellation, size caps and exact hashes. Native CAB/LZX decoding uses `cab` 0.6.0, returns only explicitly requested font basenames, and never executes an installer or writes archive paths.
- Original installers are retained unchanged in private storage. Complete verified faces are reusable offline, including after cancellation or partial failure. Cache writes share the existing font-cache mutex and publish completed files through temporary files.
- ZIP imports use bounded private staging, reject traversal paths, limit entry count and expanded bytes, and publish only files recognized by native font validation. They never modify the user's originals or change an open document merely by installing fonts.

## Licensed document inputs

External font inputs can be proprietary when the user has the necessary licence. Their static font structure, Unicode mapping and editable outline embedding flags are still validated. Fonts restricted to preview/print, bitmap-only or restricted embedding remain refused. The existing repository asset exclusions are retained. Optional licensed inputs do not become bundled app assets.

## Editing and adding text

The editor font picker has search, Phone/Added filters, family groups, style choices, live font previews and a current-face indicator. System fonts retain priority over duplicate extra faces. Font download actions remain at the end of the installed list; the download sheet also links to the Microsoft pack settings.

Existing paragraphs retain their original font by default. Missing full faces are matched by normalized PostScript name and checked for character coverage; no different family is substituted automatically. A previously accepted Core Fonts licence permits fetching a missing matching package. Google Fonts retain their existing pinned download mechanism.

New text inherits the nearest paragraph's face, size, color, bold and italic settings on the same page. It uses an exact installed/downloadable face or a font resource belonging to that document page. Only actual standard PDF fonts use the standard-font path; commercial names such as Arial are not silently aliased to Helvetica. Failure keeps the typed draft for retry, with actions to manage fonts or cancel. Saving and closing wait for asynchronous font resolution; a failed draft prevents premature close.

The `page_add_text` tool accepts `font_path` or `font_resource` and an optional generation guard. The custom-font addition is one native batch on a disposable document candidate. Failed glyph checks or font decoding leave no placeholder or partial document change; one Undo removes a successful addition. The resulting custom-font text is ordinary editable source text and survives save/reopen.

## Validation

- 114 Android JVM tests pass, including cache integrity/offline repair, safe ZIP staging, source-first family grouping and nearest-text style selection.
- 31 emulator instrumentation tests pass: all 28 existing regressions plus three new Windows font cases. The new cases download and decode all 30 real faces, verify native PostScript names, reuse the cache offline, import a Microsoft family through ZIP, add text in its original face/color/size, save/reopen, close immediately during font resolution, and preserve/cancel a draft when the exact face is unavailable.
- Native checks pass: 33 mobile tests (four existing manual tests ignored), 94 font library tests, and 358 automation tests. The three font-build manifest tests also pass without the inherited Arabic-script override used to build this fork.
- Android debug and signed release builds succeed. Lint reports zero errors and one existing Android Gradle Plugin advisory.
- Manual Arabic/English review verifies the settings page, licence flow, family search/expansion, selected Bold style, and the colored theme. Arabic count labels use words rather than a bidirectional slash expression.
- Signed ARM64 0.0.6-rc.1 (6012) installs over stable 0.0.5 (5992) with the production certificate. All 30 faces were also downloaded and installed through the signed APK settings UI; cache verification reuses the accepted licence.

The existing app icon and five themes are retained. Stable 0.0.5 stays published while the owner tests this preview.
