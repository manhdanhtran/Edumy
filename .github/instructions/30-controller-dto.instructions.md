---
applyTo: "src/main/java/com/edumy/**/controller/**/*.java,src/main/java/com/edumy/**/dto/**/*.java"
---
# Controller & DTO rules (controller/, dto/)
- Contract source: docs/ai/05-api-contract.md. Base path /api/v1.
- dto/request/: `record XxxRequest` with Bean Validation limits from the SRS. dto/response/: `record XxxResponse`. No files directly in dto/, no other sub-folders.
- Records used by OTHER modules never go in dto/ — they belong in api/.
- Controllers call service/ only; never repositories, never entities, no @Transactional, no business rules.
- Every protected endpoint has @PreAuthorize (role and/or @courseAccess / @enrollmentAccess / @qaAccess bean from the module's security/). Not yours → 404.
- Errors via BusinessException(ErrorCode) → ProblemDetail; Vietnamese titles from messages_vi.properties.
- springdoc: @Tag per controller, @Operation(summary = "UC-xx …") per method.
- Phase-1 stubs: // STUB-P1 comment + StubSupport.mark(response) + full validation/authorization + no DB writes.
