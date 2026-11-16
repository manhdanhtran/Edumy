# ADR-013 — Certificate PDF with OpenPDF 2.2.5 and embedded Noto Sans
Status: Accepted · Date: 2026-11-16 · P12 (dependency approved in the brief: `com.github.librepdf:openpdf`)

## Context
UC-S20 / SRS §7 "Thành phần tạo PDF (nội bộ)": a PDF from the certificate snapshot, correct Vietnamese with diacritics,
code and verification link, generated on demand and never stored (D-48), under 5 s (NFR-05). The official template is
open (Q-11). The brief approves OpenPDF (a stable release at least two weeks old) and an OFL Unicode font.

## Decision
- **OpenPDF 2.2.5** (Maven Central, published 2026-03-09), pinned in `pom.xml` (`openpdf.version`). Licence LGPL 2.1 /
  MPL 2.0 (dual). Every dependency of the 2.x line is optional, so nothing else enters the runtime classpath.
  Not 3.0.5 (2026-05-22, the newest): its `openpdf` artifact has a **mandatory** dependency on `brotli4j`, which ships
  platform-specific native libraries — a second dependency the brief did not approve. 2.2.5 is the most recently
  published release without it; API `com.lowagie.text.*`, `PdfTextExtractor` included (used by the tests).
- **Font:** Noto Sans Regular and Bold (`src/main/resources/fonts/`, from github.com/notofonts, SIL Open Font License
  1.1; the licence text ships next to the fonts as `OFL-NotoSans.txt`). Embedded with Identity-H encoding (subset), so
  every Vietnamese letter is drawn and extractable. Loaded once at start-up: a missing font fails the start, not a request.
- **Port:** `learning.integration.CertificatePdfRenderer#render(CertificatePdfData)`; `CertificatePdfData` = stored
  snapshot (student, course, main instructor, issue time, code) + verification link `{edumy.frontend.base-url}/
  certificates/{code}`. The layout lives in `OpenPdfCertificateRenderer` (A4 landscape, centred lines); the Q-11
  template replaces that bean only.
- **Errors (UC-S20 E2):** a rendering failure → 500 `CERTIFICATE_PDF_FAILED` (ProblemDetail, Vietnamese text), logged with
  the code and the stack trace; nothing is written (the endpoint only reads).

## Consequences
- Jar size: OpenPDF ~2.2 MB + fonts ~1.25 MB.
- `SampleCertificatePdfRenderer` and `static/sample/certificate-sample.pdf` are gone; the endpoint has no stub marker.
- Upgrading to OpenPDF 3.x later means approving `brotli4j` (native) or excluding it if the project confirms it is only
  needed for Brotli-compressed input.
