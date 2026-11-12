@AGENTS.md

## Claude Code specifics
- Use plan mode for any task touching `commerce` payment/order code, `identity` security code, or `db/migration`.
- Before creating any class, look up its folder in `@docs/ai/01-architecture.md` §1.6 and its name in §1.7.
- Before editing, read the matching file in `docs/ai/` (e.g. `@docs/ai/03-data-model-mysql.md` for SQL/entities).
- Prompts for each build step are in `docs/prompts/` (start with `docs/prompts/README.md`).
- After changes run `./mvnw test` (or the narrowest test class) and paste the summary line of the result.
