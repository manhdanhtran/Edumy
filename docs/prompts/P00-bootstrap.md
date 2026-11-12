# P00 — Bootstrap project

## Người làm trước (thủ công, đúng yêu cầu của thầy)
Vào https://start.spring.io:
- Project **Maven** · Language **Java** · Spring Boot **4.1.x** (bản patch mới nhất, không chọn SNAPSHOT/M/RC) · Java **21**
- Group `com.edumy` · Artifact `edumy-backend` · Package name `com.edumy` · Packaging Jar
- Dependencies: **Spring Web, Spring Data JPA, Lombok, MySQL Driver** (thầy yêu cầu) + Spring Security, OAuth2 Resource Server,
  Validation, Flyway Migration, Spring Cache Abstraction, Spring Boot Actuator, Docker Compose Support, Testcontainers,
  Spring Modulith, Java Mail Sender
- Generate → giải nén → commit "chore: initial project from start.spring.io".

## Prompt (PLAN FIRST)
```text
Task: bootstrap the Edumy backend skeleton. Follow AGENTS.md and docs/ai/01-architecture.md.
The project was just generated from start.spring.io (Spring Boot 4.1, Java 21, Maven). Do NOT regenerate it.

1. pom.xml
   - Keep Boot-managed dependencies without versions. Make sure Boot 4 modular starters are used
     (spring-boot-starter-webmvc, spring-boot-starter-flyway + flyway-mysql, matching *-test starters).
   - Add: com.github.ben-manes.caffeine:caffeine (Boot-managed), MapStruct + mapstruct-processor,
     org.projectlombok:lombok-mapstruct-binding, springdoc-openapi-starter-webmvc-ui (3.x line compatible with Boot 4.1),
     Bucket4j core (jdk17 artifact). For every non-Boot-managed dependency put the version in <properties> as
     `TODO-VERIFY` and list them at the end so a human pins real versions from Maven Central. Do not guess versions.
   - maven-compiler-plugin annotationProcessorPaths order: lombok, lombok-mapstruct-binding, mapstruct-processor.
2. compose.yaml: mysql:8.4 (db edumy, user edumy, password from .env, port 3306,
   command: --default-time-zone=+00:00 --character-set-server=utf8mb4 --collation-server=utf8mb4_0900_ai_ci)
   and axllent/mailpit (1025 SMTP, 8025 UI). Add .env.example, .gitignore entries for .env.
3. Config: application.yml (common: open-in-view false, ddl-auto validate, hibernate.jdbc.time_zone UTC,
   JDBC URL with connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true, flyway locations db/migration,
   jackson defaults, actuator health/info only), application-dev.yml (adds db/seed location, swagger on,
   dev JWT secret, CORS localhost:5173/3000, mail → localhost:1025), application-test.yml.
   Typed config: `@ConfigurationProperties("edumy")` record EdumyProperties (security.jwt.accessTtl=15m,
   refreshTtl=14d, secret; cors.allowedOrigins; scheduler.enabled=false; payment.provider=fake).
4. Packages: create the 7 modules with EXACTLY the folders of docs/ai/01-architecture.md §1.5 (controller, dto/request,
   dto/response, service, mapper, entity, repository + the optional folders listed for that module). Each module gets
   package-info.java (@ApplicationModule displayName + allowedDependencies exactly as §1.8; shared is Type.OPEN) and
   `api/package-info.java` with @NamedInterface("api") (§1.13). Every empty folder gets a package-info.java only.
   Do not create any folder that §1.3 forbids.
5. shared: ErrorCode enum (start with the codes in docs/ai/05-api-contract.md §5.2), BusinessException,
   GlobalExceptionHandler (ProblemDetail + code + traceId + field errors), CodeEnum + abstract CodeEnumConverter,
   VietnameseNormalizer (+ unit tests incl. "Lập trình Đồ họa" → "lap trinh do hoa"), StubSupport,
   CurrentUser record, a `Clock` bean (UTC), messages_vi.properties.
6. Tests: ModularityTest (verify + Documenter), EdumyApplicationTests with Testcontainers mysql:8.4 @ServiceConnection.
No entities, no controllers, no SQL yet.

First output a plan (files to create/modify, ≤15 bullets) and wait for my approval.
After approval implement, run `./mvnw test`, and report: files, test result, TODO-VERIFY versions list.
```
## Kiểm tra
`docker compose up -d` → `./mvnw test` xanh · mở `target/spring-modulith-docs` thấy 7 module · không có version tự bịa.
