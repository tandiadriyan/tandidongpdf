# Tandidong PDF

A full-featured, offline PDF editor for Android. It is an original app, written from
scratch with open-source libraries; it was not built by reverse engineering any
commercial product. Everything runs on the device and no document is uploaded anywhere.

## Features

**View**
- Smooth continuous scrolling, pinch and double-tap zoom, sharp text at any zoom level
- Search with highlighted results, go to page, fast scroller, bookmarks (outline)
- Night mode, tappable links, select and copy text (long-press)
- Read aloud (text-to-speech) and Liquid mode (reflowed text for small screens)

**Comment**
- Highlight, underline, strikethrough, squiggly (snaps to text)
- Freehand pen, rectangle, ellipse, line and arrow, sticky notes, text boxes, stamps
- Select, move, recolor and delete comments; comments list

**Fill & Sign**
- Fill interactive form fields (text, checkboxes, radio buttons, dropdowns)
- Add text, dates, ✓ / ✗ / ● symbols anywhere on non-interactive forms
- Draw, save and reuse signatures and initials

**Edit**
- Edit existing text (the original text is removed and replaced)
- Add text to the page, add images, whiteout

**Redact**: true redaction, not just black boxes
- Mark areas, mark text, redact whole pages
- Search & redact words, phrases and patterns (emails, phone numbers, card numbers, ID numbers, dates, URLs)
- Applying redactions permanently removes the underlying text characters, image pixels and
  vector graphics from the file (content streams are rewritten), removes overlapping
  comments and form fields, and strips hidden `ActualText`. Pages with content that cannot be
  edited precisely (for example JPEG 2000 or JBIG2 images) are flattened to an image instead,
  so nothing under a redaction mark can survive.
- Sanitize: remove metadata, scripts, attachments, thumbnails, comments, links and more

**Organize pages**
- Drag-and-drop reordering, rotate, delete, duplicate, extract
- Insert blank pages, pages from other PDFs, or images
- Split by page count or ranges, crop margins, reverse order

**More tools**
- Combine PDFs and images into one PDF
- Create PDFs from photos or from the document scanner (edge detection and cropping via Google ML Kit)
- Recognize text (OCR) to make scans searchable and selectable
- Compress (image downsampling and re-encoding)
- Protect with a password (AES-256) and permissions, or remove a password
- Watermarks (text or image), headers and footers, page numbers, Bates numbering
- Export to Word (.docx), plain text, or images (JPEG/PNG at 72/150/300 dpi)
- Compare two documents (word-level differences per page)
- Flatten comments and form fields, edit document properties, print, share
- Unlimited-style undo/redo (last 25 versions) for every operation

## Getting the app

### Download a build from GitHub Actions
Every push builds the app in the **Android CI** workflow. Open the latest run under the
repository's *Actions* tab and download an artifact:

- `tandidong-pdf-release`: minified build, signed with a debug key (recommended for trying the app)
- `tandidong-pdf-debug`: debug build

Unzip the artifact and install the `.apk` on your phone (you may need to allow installs from
unknown sources). The release build is signed with the default debug key so it installs
directly; set up your own signing key before publishing to Google Play.

### Build it yourself
Requirements: Android Studio (Ladybug or newer) or the Android SDK with JDK 17.

```bash
./gradlew :app:assembleRelease     # app/build/outputs/apk/release/
./gradlew :app:assembleDebug       # app/build/outputs/apk/debug/
```

## Architecture

```
app/src/main/java/com/tandidong/pdf/
├── core/        PDF engine on the PDFBox API, with no Android dependencies, unit-tested on the JVM
│   ├── Redactor.kt      content-stream rewriting redaction (text, images, paths, forms, inline images)
│   ├── TextEngine.kt    positioned text extraction, search, selection geometry
│   ├── PageOps.kt       organize, merge, split, extract, crop, with reference scrubbing
│   ├── Annotations.kt   annotations with generated appearance streams, editing
│   ├── Forms.kt, Security.kt, Stamping.kt, Sanitizer.kt (+ Flattener, Compressor), Outline.kt, DocInfo.kt
│   └── TextTools.kt     OCR text layer, text replacement, DOCX export, text diff
├── platform/    Android implementations (Bitmap image codec, fonts, fallback rasterizer)
├── render/      page rendering (PDFium, with a PdfBox fallback)
├── session/     DocumentSession: versioned working copies, undo/redo, caches, saving
├── data/        preferences, recent files, saved signatures, file utilities
└── ui/          Jetpack Compose UI (home, viewer/editor, organize, tools, settings)
```

- **Rendering:** [PdfiumAndroid](https://github.com/johngray1965/PdfiumAndroidKt) (Google's PDFium engine).
- **Editing:** [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android), a port of Apache PDFBox.
- **OCR and scanning:** Google ML Kit text recognition and document scanner (on device).
- Each edit writes a new version of the working copy, which gives reliable undo/redo for every
  operation. The original file is only overwritten when you tap **Save**.

## Tests

The core engine is compiled against desktop Apache PDFBox 2.0.27 (same API, different package)
and tested on the JVM, covering redaction, text extraction, page operations, annotations, forms,
security, stamping, compression, OCR layers, export and diff:

```bash
./gradlew -p tools/core-tests test
```

## Notes and limitations
- OCR covers Latin-script languages (ML Kit's bundled model).
- "Edit text" replaces a line with a standard font; it does not reuse embedded subset fonts.
- The document scanner needs Google Play services.
- Digital (certificate-based) signatures are not supported yet; signatures are drawn signatures.

## Licenses
App source: see repository. Third-party components: PdfBox-Android (Apache 2.0),
PdfiumAndroid (Apache 2.0) / PDFium (BSD-3-Clause / Apache 2.0), Jetpack Compose and AndroidX
(Apache 2.0), Google ML Kit (Google APIs Terms of Service).
