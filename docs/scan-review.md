# Scan review and export

The scanner continues using the phone camera via the existing TakePicture activity contract.
No camera SDK, network scanner or external processing service is introduced. Importing JPEG/PNG
photographs is also supported; input images are copied into the app cache with per-file (32 MiB)
and per-scan (50-page) bounds. Original gallery photographs are never altered or deleted.

## Review

Each page retains independent crop corners, filter, strength and quarter-turn rotation in the
view model. The selected page has a processed preview and an original-photo comparison. Preview
and full-resolution export call the same engine enhancement path: convex perspective crop,
lighting/contrast cleanup, color/grayscale/black-white look, then rotation. Preview uses a bounded,
EXIF-normalized thumbnail and never becomes export input. All eight EXIF orientations, including
mirrors/transposes, are applied to thumbnails. The native engine reads the untouched full original
for export and retains its existing resolution and lossless black/white handling.

Pages can be imported, retaken, deleted or moved earlier/later. Apply to all explicitly targets the
current scan only and allows selecting image enhancement/strength, absolute rotation and automatic
crop. Auto-crop uses each page's detected edges; it never blindly copies one sheet's crop to another.
The source page's manual crop remains intact. Late edge detection cannot overwrite a manual crop.
Cancelling the phone camera leaves the review screen available for gallery import, without a
camera-relaunch loop on recreation.

## Export and failure handling

Export snapshots each page's settings and does not swallow enhancement failures. Photos/settings
are cleared only after successful download delivery or synchronous insertion into the captured
open document under its save mutex. Failed processing keeps review inputs for correction/retry.
Text recognition is optional; it uses installed OCR models only. A missing recognizer produces a
saved-PDF notice explaining that the text layer was not added. No new Arabic OCR claim is made.
Temporary preview documents are always closed in a bounded non-cancellable ownership region;
a mutex and debounce limit overlapping preview work. Final export temporary files/documents are
cleaned in finally blocks. The preview cannot replace the original-resolution image.

## Validation

- 106 JVM tests pass, including malformed/crossed/tiny/nonfinite crop rejection.
- All 25 Android instrumentation tests pass, including four scanner-specific tests: preview/export
  crop+rotation+grayscale and save/reopen, batch import/reorder/apply-all/delete ownership,
  failed-save retention, and mirrored EXIF normalization. Existing engine, font and tool coverage
  remains green.
- Arabic Colored-theme UI was exercised on API 37: actual external camera launch, cancellation,
  gallery import, detected crop, color cleanup, grayscale, rotation, two-page apply-all dialog,
  OCR-off PDF save and reopening the result. Original-photo comparison and independent page controls
  were reviewed. QA screenshots remain outside the repository.
- Camera capture latency, edge detection on real photographs, and phone memory use still require
  physical-device trial of the new build. OCR coverage depends on installed models.

The user approved RC.8 for stable release after the phone trial on 2026-11-02. Stable 0.0.4
contains the same application/engine behavior; the stable version metadata and release packaging
are verified separately before publication.
