# Tandidong PDF

A full-featured, offline PDF editor for Android. It is an original app, written from
scratch with open-source libraries; it was not built by reverse engineering any
commercial product. Everything runs on the device and no document is uploaded anywhere.

## Features

**View**
- Smooth continuous scrolling, pinch and double-tap zoom, sharp text at any zoom level
- Search with highlighted results, go to page, page thumbnails, fast scroller, bookmarks (outline)
- Night mode, tappable links, select and copy text (long-press, then drag the selection handles)
- Read aloud (text-to-speech) and Liquid mode (reflowed text for small screens)
- File attachments: open, save, add and remove embedded files

**Comment**
- Highlight, underline, strikethrough, squiggly (snaps to text)
- Freehand pen, rectangle, ellipse, line and arrow, sticky notes, text boxes, stamps (with name and date)
- Measure distances with a dimension line (mm, cm, m, in, ft, pt; scales 1:1 to 1:1000)
- Select, move, recolor and delete comments; threaded comments list with replies

**Fill & Sign**
- Fill interactive form fields (text, checkboxes, radio buttons, dropdowns)
- Add text, dates, ✓ / ✗ / ● symbols anywhere on non-interactive forms
- Autofill: save your name, email, phone and address once, then tap to place them or accept
  the suggestion offered for matching form fields
- Draw, save and reuse signatures and initials
- **Digital signatures** with a certificate (digital ID): create a self-signed ID or import a
  `.p12`/`.pfx` from a certificate authority, sign visibly (optionally with your handwritten
  signature) or into an existing signature field. Signatures are standard CMS
  (`adbe.pkcs7.detached`) written as incremental updates, so earlier signatures stay valid.
- Signature validation: a banner and panel show who signed, when, whether the document changed
  afterwards, and the certificate details

**Prepare form**
- Create fillable fields: text, multi-line text, checkboxes, radio button groups, dropdowns,
  list boxes and signature fields, with names, tooltips, default values and required flags
- Rename or delete fields; field outlines with names while preparing

**Edit**
- Edit existing text line by line or as a whole paragraph (re-wrapped to the original width and
  line spacing). The original font is reused when it contains every character you type;
  otherwise the closest standard font (same family, weight and style) is used, with the
  original size, color and baseline. Font, bold, italic, size and color can be changed.
- Images: select, move, resize (corner handles), replace, save to the gallery or delete
- Links: draw a link to a web address, email or page; remove links
- Add text, add images, whiteout, page background color

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
- Split by page count, ranges or top-level bookmarks, crop margins, reverse order

**More tools**
- Combine PDFs and images into one PDF
- Create PDFs from photos or from the document scanner (edge detection and cropping via Google ML Kit)
- Recognize text (OCR) to make scans searchable and selectable
- Compress (image downsampling and re-encoding)
- Protect with a password (AES-256) and permissions, or remove a password
- Watermarks (text or image), headers and footers, page numbers, Bates numbering
- Export to Word (.docx), Excel (.xlsx, with tables rebuilt into rows and columns and numbers
  as real numbers), PowerPoint (.pptx, one slide per page), plain text, or images
  (JPEG/PNG at 72/150/300 dpi)
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
│   ├── Forms.kt, FormBuilder.kt   fill, flatten, and create form fields
│   ├── DigitalSignatures.kt       CMS signing (BouncyCastle), incremental save, validation
│   ├── TextStyle.kt, TextTools.kt font/color matching, paragraph detection, text replacement, OCR layer, DOCX, diff
│   ├── PageImages.kt              image listing and in-place move/resize/replace/delete
│   ├── Attachments.kt             embedded files and link annotations
│   ├── OfficeExport.kt            table detection, XLSX and PPTX writers
│   └── Security.kt, Stamping.kt, Sanitizer.kt (+ Flattener, Compressor), Outline.kt, DocInfo.kt, Autofill.kt
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
form creation, digital signatures, image editing, font matching, security, stamping,
compression, OCR layers, export (exported .xlsx/.pptx files are read back with Apache POI) and diff:

```bash
./gradlew -p tools/core-tests test
```

CI also boots an Android 14 emulator and drives the app through its main tools (comments,
redaction, Fill & Sign, edit text, images, links, organize, compress, watermark, Prepare form,
digital ID creation and signing), failing on any crash; screenshots are kept as an artifact.

## Notes and limitations
- OCR covers Latin-script languages (ML Kit's bundled model).
- Text editing works on one line or one paragraph at a time; text inside images needs OCR first.
- Images drawn inside form XObjects (rare) can be saved by exporting pages, but not moved.
- PowerPoint export makes one picture per page (text is not editable in the slides).
- Self-signed digital IDs prove integrity; others see your identity as trusted only if they
  trust your certificate. Long-term validation (timestamps, revocation data) is not embedded.
- The document scanner needs Google Play services.

## Licenses
App source: see repository. Third-party components: PdfBox-Android (Apache 2.0),
PdfiumAndroid (Apache 2.0) / PDFium (BSD-3-Clause / Apache 2.0), Bouncy Castle (MIT),
Jetpack Compose and AndroidX (Apache 2.0), Google ML Kit (Google APIs Terms of Service).
