# Third-party components bundled with the backend (beyond the Spring Boot BOM)

| Component | Version | Licence | Where | Why |
|---|---|---|---|---|
| OpenPDF (`com.github.librepdf:openpdf`) | 2.2.5 | LGPL 2.1 or MPL 2.0 | Maven dependency | certificate PDF (UC-S20, ADR-013) |
| Noto Sans Regular / Bold | from github.com/notofonts (latin-greek-cyrillic) | SIL Open Font License 1.1 | `src/main/resources/fonts/NotoSans-*.ttf`, licence text `fonts/OFL-NotoSans.txt` | Vietnamese text in the certificate PDF, embedded (subset) |
| Bucket4j | 8.21.0 | Apache 2.0 | Maven dependency | rate limits (ADR-002) |
| springdoc-openapi | 3.2.0 | Apache 2.0 | Maven dependency | OpenAPI / Swagger UI |
| MapStruct | 1.6.3 | Apache 2.0 | Maven dependency (annotation processor) | mappers |

OFL 1.1 allows bundling and embedding the fonts in documents; the licence text must travel with the font files (it does)
and the fonts may not be sold on their own.
