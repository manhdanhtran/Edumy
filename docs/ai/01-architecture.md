# 01 — Architecture & folder structure (AUTHORITATIVE — ADR-007)

This file is the single reference for **where every class goes**. If any other doc, prompt or habit suggests a
different package (`web`, `application`, `domain`, `infra`, a global `controller/` folder…), this file wins.

## 1.1 Principles
1. **Root = by business module.** Every direct sub-package of `com.edumy` is one Spring Modulith application module.
   Five business modules map 1:1 to the SRS subsystems; two support modules (`notification`, `shared`) have no business data.
2. **Inside a module = by technical role**, with the folder names every Spring Boot developer knows:
   `controller`, `dto`, `service`, `mapper`, `entity`, `repository` (+ optional folders, §1.3).
3. **One gate per module: `api/`.** It is the only package other modules may import (Spring Modulith named interface).
4. **Only the owner module writes its tables** (SRS §3.1). Other modules read through the owner's `api/`
   (exception: catalog read model, §1.12).
5. Spring Boot itself imposes no layout; it points to Spring Modulith for domain-based structure. Spring Modulith only
   cares about the module base package and named interfaces, so these sub-folder names are a team convention enforced by
   this file, `ModularityTest` and code review.

## 1.2 Repository tree
```
edumy-backend/
├── pom.xml · mvnw · compose.yaml · .env.example
├── AGENTS.md · CLAUDE.md · .cursor/rules/ · .github/
├── docs/ai/ · docs/prompts/ · docs/requirements/          # AI kit + original SRS/DDD
└── src/
    ├── main/java/com/edumy/
    │   ├── EdumyApplication.java                          # @SpringBootApplication, nothing else here
    │   ├── shared/
    │   ├── identity/
    │   ├── catalog/
    │   ├── authoring/
    │   ├── commerce/
    │   ├── learning/
    │   └── notification/
    ├── main/resources/
    │   ├── application.yml · application-dev.yml · application-test.yml
    │   ├── messages_vi.properties
    │   ├── db/migration/   V1__identity.sql … V6__reference_data.sql
    │   ├── db/seed/        V1000__dev_seed.sql             # dev profile only
    │   ├── templates/mail/                                 # notification email templates (Vietnamese)
    │   └── static/sample/                                  # STUB-P1 sample files (certificate PDF)
    └── test/java/com/edumy/
        ├── ModularityTest.java
        ├── support/        # SharedMySql (one container/JVM), @MySql*Test annotations, test users, JWT helper
        └── <module>/       # mirrors main: controller/, service/, repository/, entity/ …
```

## 1.3 Standard module template
```
com.edumy.<module>/
├── package-info.java          # @ApplicationModule(displayName, allowedDependencies)          REQUIRED
├── api/                       # @NamedInterface("api") — public gate for other modules          REQUIRED*
├── controller/                # @RestController                                                REQUIRED*
├── dto/
│   ├── request/               # XxxRequest records (Bean Validation)                           REQUIRED*
│   └── response/              # XxxResponse records                                             REQUIRED*
├── service/                   # @Service, @Transactional, api implementations                   REQUIRED
├── mapper/                    # MapStruct mappers entity/projection → response / api record     REQUIRED*
├── entity/                    # @Entity + enums of this module's tables                         REQUIRED*
├── repository/                # Spring Data JPA interfaces (+ JdbcClient read repos)            REQUIRED*
├── security/                  # access beans for @PreAuthorize (+ SecurityConfig in identity)   optional
├── listener/                  # handlers of OTHER modules' events                               optional
├── integration/               # adapters to external systems: interface + implementations      optional
├── scheduler/                 # @Scheduled jobs (NFR-09)                                        optional
└── config/                    # module-specific @Configuration / @ConfigurationProperties      optional
```
`*` = required unless the module has nothing of that kind (catalog has no `entity/`; notification has no `controller/`).
**Do not create** any other folder (`impl/`, `utils/`, `helper/`, `common/`, `exception/`, `model/`, `web/`, `domain/`,
`infra/`, `application/`) inside a module. Shared helpers go to `shared/`; errors use `shared.error`.

| Folder | Contains | Must not contain / do |
|---|---|---|
| `api/` | interfaces `XxxApi`, records they return/accept, event records. **Flat** (no sub-packages) so one `@NamedInterface` covers all of it | entities, Spring Data types in return values except `Page`, implementation classes, logic |
| `controller/` | `@RestController` classes: routing, `@Valid`, `@PreAuthorize`, `@CurrentUserId`, OpenAPI annotations | repositories, entities, `@Transactional`, business rules |
| `dto/request/` | `record XxxRequest` with Bean Validation (limits from SRS) | entities, logic, records used by other modules |
| `dto/response/` | `record XxxResponse` (JSON contract of docs/ai/05) | entities, lazy proxies, records used by other modules |
| `service/` | `XxxService` (writes), `XxxQueryService` (reads, `readOnly`), `XxxApiImpl` (implements `api/`), event publishing | HTTP types (`HttpServletRequest`, `ResponseEntity`), SQL strings |
| `mapper/` | MapStruct `@Mapper(componentModel = "spring")` interfaces | business rules, repository calls |
| `entity/` | `@Entity` classes, status/type enums + their `CodeEnumConverter`s, state-transition methods | Spring beans, repository calls, other modules' entities |
| `repository/` | `interface XxxRepository extends JpaRepository`, projections, `@Query`; catalog's `JdbcClient` read repositories | business rules, writes to other modules' tables |
| `security/` | `@Component("courseAccess") class CourseAccess` …; identity also: `SecurityConfig`, `JwtIssuer`, `SessionStatusCache`, `LoginAttemptLimiter` | business writes |
| `listener/` | `@ApplicationModuleListener` / `@TransactionalEventListener` methods reacting to other modules' events; delegate to `service/` | logic beyond calling a service |
| `integration/` | port interface (e.g. `PaymentGateway`, `MailGateway`) **and** its implementations (`FakeQrPaymentGateway`) | business rules; services depend on the interface only |
| `scheduler/` | `@Scheduled` classes guarded by `edumy.scheduler.enabled`; delegate to `service/` | logic beyond calling a service |
| `config/` | module `@Configuration`, `@ConfigurationProperties` records | beans of other modules |

Visibility: sub-packages force most types to be `public` (a controller in `controller/` must see a service in `service/`).
That is fine — boundaries **between modules** are enforced by Spring Modulith, not by Java modifiers. Still prefer
package-private for classes nobody outside their folder uses (e.g. `XxxApiImpl`, `@Configuration` classes).

## 1.4 Allowed calls inside a module
```
controller ──► service ──► repository ──► entity
     │            │  └──► mapper
     │            ├──► integration (interface only)
     │            └──► other module's api/   (only modules allowed in §1.8)
     └──► dto/request, dto/response
listener, scheduler ──► service
security ──► repository (own module) | other module's api/
api/ ◄── implemented by service/XxxApiImpl
```
Forbidden: `controller → repository`, `controller → entity`, `entity → service/repository`, `repository → service`,
`service → controller/dto.request` (services take primitives/commands, return response records or api records),
any folder → another module's non-`api` package.

Service signature rule: services accept ids, primitives or a `record XxxCommand` defined next to the service, and return
`dto/response` records (for controllers) or `api/` records (for other modules). Never return entities.

## 1.5 Concrete tree per module
Names below are the expected classes. New classes follow §1.7 naming and go to the folder from §1.6.

### shared (OPEN module, organised by topic, no layers)
```
shared/
├── package-info.java                 # @ApplicationModule(type = Type.OPEN)
├── config/      ClockConfig, CacheConfig (Caffeine per-cache specs), OpenApiConfig, WebConfig (CORS, paging), EdumyProperties
├── error/       ErrorCode, BusinessException, RetryAfterException, GlobalExceptionHandler, ConstraintViolationTranslator,
│                ProblemResponder
├── security/    CurrentUser, @CurrentUserId, CurrentUserIdArgumentResolver, RoleCode
├── jpa/         CodeEnum, CodeEnumConverter
├── text/        VietnameseNormalizer, SlugGenerator
├── validation/  StrongPassword (+ validator): password policy NFR-02; CoursePrice (+ validator): BR-07 price rule;
│                texts in ValidationMessages.properties
└── web/         StubSupport (X-Edumy-Stub), PageResponses
```

### identity (01) — users, roles, user_roles, user_tokens, refresh_tokens
```
identity/
├── package-info.java                 # allowedDependencies = {"shared"}
├── api/          UserQueryApi, RoleQueryApi, UserSummary,
│                 UserRegistered, EmailVerificationRequested, PasswordResetRequested, PasswordChanged
├── controller/   AuthController, MeController, AdminUserController
├── dto/request/  RegisterRequest, LoginRequest, VerifyEmailRequest, ForgotPasswordRequest,
│                 ResetPasswordRequest, UpdateProfileRequest, ChangePasswordRequest
├── dto/response/ AuthTokenResponse, ForgotPasswordResponse, MeResponse, AdminUserResponse
├── service/      AuthService, SessionService, AccountTokenService, ProfileService, InstructorRegistrationService,
│                 UserAdminService, UserAdminQueryService, UserQueryApiImpl, RoleQueryApiImpl, RoleCacheEvictor
├── mapper/       UserMapper
├── entity/       User, Role, UserRole, UserToken, RefreshToken, UserStatus, TokenType, RevokeReason
├── repository/   UserRepository, RoleRepository, UserRoleRepository, UserTokenRepository, RefreshTokenRepository
├── security/     SecurityConfig, JwtIssuer, JwtSessionValidator, SessionStatusCache, LoginAttemptLimiter,
│                 CertificateVerifyRateLimiter
└── scheduler/    TokenCleanupScheduler                 # phase 2
```

### catalog (02) — owns no table (read model, ADR-003)
```
catalog/
├── package-info.java                 # allowedDependencies = {"shared", "authoring :: api", "learning :: api"}
├── api/          (empty package-info only — nobody depends on catalog)
├── controller/   CatalogCourseController, CatalogCategoryController, CertificateVerificationController
├── dto/request/  CourseSearchRequest (q, categoryId, price, sort — bound from query params)
├── dto/response/ CourseCardResponse, CourseDetailResponse, LessonPreviewResponse, CategoryResponse,
│                 ReviewItemResponse, CertificateVerificationResponse
├── service/      CourseCatalogQueryService, CategoryCatalogQueryService, CertificateVerificationService,
│                 CachedCourseQueryService (list + page-by-slug caches), CatalogCacheEvictor (@CacheEvict, called by the listener)
├── repository/   CourseCatalogRepository, CategoryCatalogRepository, CertificateLookupRepository   # JdbcClient, SELECT only
└── listener/     CatalogCacheEvictionListener         # reacts to authoring/learning events
```
No `entity/`, no `mapper/` (SQL maps straight into response records).

### authoring (03) — categories, courses, course_co_instructors, chapters, lessons
```
authoring/
├── package-info.java                 # allowedDependencies = {"shared", "identity :: api"}
├── api/          CourseQueryApi, CourseAccessApi, LessonQueryApi, SellableCourse, LessonRef,
│                 CourseApproved, CourseRejected, CourseHidden, CourseUnhidden, CourseContentChanged, CoInstructorInvited,
│                 CategoryChanged
├── controller/   InstructorCourseController, ChapterController, LessonController, CoInstructorController,
│                 InvitationController, AdminCategoryController, AdminCourseReviewController
├── dto/request/  CreateCourseRequest, UpdateCourseRequest, CreateChapterRequest, UpdateChapterRequest, ReorderRequest,
│                 CreateLessonRequest, UpdateLessonRequest, InviteCoInstructorRequest, RejectCourseRequest,
│                 HideCourseRequest, CategoryRequest
├── dto/response/ InstructorCourseSummaryResponse, InstructorCourseDetailResponse, ChapterResponse, LessonResponse,
│                 InvitationResponse, ReviewStatusResponse, PendingCourseResponse, AdminCategoryResponse
├── service/      CourseService, CourseQueryService, ChapterService, LessonService, CoInstructorService,
│                 SubmitForReviewService, CourseModerationService, CategoryService,
│                 CourseQueryApiImpl, CourseAccessApiImpl, LessonQueryApiImpl
├── mapper/       CourseMapper, ChapterMapper, LessonMapper, CategoryMapper
├── entity/       Category, Course, CourseCoInstructor, Chapter, Lesson, CourseStatus, InvitationStatus, LessonType
├── repository/   CategoryRepository, CourseRepository, CourseCoInstructorRepository, ChapterRepository, LessonRepository
└── security/     CourseAccess ("courseAccess"), ChapterAccess ("chapterAccess"), LessonAccess ("lessonAccess")
```

### commerce (04) — carts, cart_items, orders, order_items, payment_transactions
```
commerce/
├── package-info.java                 # allowedDependencies = {"shared", "identity :: api", "authoring :: api", "learning :: api"}
├── api/          OrderPaid, PaymentReconciled
├── controller/   CartController, OrderController, PaymentController, PaymentWebhookController, AdminOrderController
├── dto/request/  AddCartItemRequest, CreateOrderRequest, CancelOrderRequest, PaymentWebhookRequest, ResolvePaymentRequest
├── dto/response/ CartResponse, OrderSummaryResponse, OrderDetailResponse, PaymentQrResponse, PaymentStatusResponse,
│                 AdminPaymentResponse, AdminPaymentDetailResponse
├── service/      CartService, CartQueryService, OrderService, OrderQueryService, PaymentService, PaymentQueryService,
│                 ReconciliationService, CommerceExpiryService, PaymentResultApplier (T2), PendingPaymentSettler,
│                 CommerceCodeGenerator; commands / criteria: CreateOrderCommand, ResolvePaymentCommand,
│                 OrderSearchCriteria, PaymentSearchCriteria
├── mapper/       CartMapper, OrderMapper, PaymentMapper
├── entity/       Cart, CartItem, Order, OrderItem, PaymentTransaction, OrderStatus, CancelReason, PaymentStatus, HandledAction
├── repository/   CartRepository, CartItemRepository, OrderRepository, PaymentTransactionRepository
├── listener/     CourseHiddenListener                 # flags cart items of hidden courses (UC-A12)
├── integration/  PaymentGateway (port), FakeQrPaymentGateway (profile !payos), WebhookSignatureVerifier, QrCode,
│                 PaymentLookup, PaymentResult, PaymentGatewayException, FakePaymentNotification   # ADR-011
├── scheduler/    PendingOrderExpiryScheduler, PaymentExpiryScheduler   # edumy.scheduler.enabled (P11-e)
└── config/       PaymentProperties
```

### learning (05) — enrollments, lesson_progresses, course_reviews, course_questions, question_answers, certificates
```
learning/
├── package-info.java                 # allowedDependencies = {"shared", "identity :: api", "authoring :: api"}
├── api/          EnrollmentApi, GrantEnrollmentCommand, ReviewChanged, QuestionAnsweredByInstructor, CertificateIssued
├── controller/   EnrollmentController, LearningController, ReviewController, QuestionController, AnswerController,
│                 InstructorStudentController, InstructorQuestionController, AdminModerationController,
│                 CertificateController
├── dto/request/  CompletionRequest, CreateReviewRequest, UpdateReviewRequest, CreateQuestionRequest, UpdateQuestionRequest,
│                 CreateAnswerRequest, UpdateAnswerRequest, ModerationRequest
├── dto/response/ EnrollmentResponse, LearningOutlineResponse, LessonContentResponse, ProgressResponse, ReviewResponse,
│                 QuestionSummaryResponse, QuestionThreadResponse, AnswerResponse, StudentProgressResponse,
│                 CertificateResponse
├── service/      EnrollmentService, EnrollmentQueryService, ProgressService, CertificateService, ReviewService,
│                 QuestionService, AnswerService, QaQueryService, ModerationService, EnrollmentApiImpl
├── mapper/       EnrollmentMapper, ReviewMapper, QaMapper, CertificateMapper
├── entity/       Enrollment, LessonProgress, CourseReview, CourseQuestion, QuestionAnswer, Certificate,
│                 EnrollmentSource, ReviewStatus, ContentStatus, AuthorType
├── repository/   EnrollmentRepository, LessonProgressRepository, CourseReviewRepository, CourseQuestionRepository,
│                 QuestionAnswerRepository, CertificateRepository
├── security/     EnrollmentAccess ("enrollmentAccess"), QaAccess ("qaAccess")
├── listener/     CertificateIssuedListener            # logs CertificateIssued after commit (P06-G6)
└── integration/  CertificatePdfRenderer (port), CertificatePdfData, OpenPdfCertificateRenderer, CertificateRenderingException   # P12, ADR-013
```

### notification — no tables, no HTTP
```
notification/
├── package-info.java                 # allowedDependencies = {"shared", "identity :: api", "authoring :: api", "commerce :: api", "learning :: api"}
├── listener/     IdentityMailListener, AuthoringMailListener, CommerceMailListener (P11-d), LearningMailListener
├── service/      MailComposer (picks template, fills Vietnamese text), MailService (compose + send)
└── integration/  MailGateway (interface), MailMessage (record), SmtpMailGateway (Spring Mail → Mailpit in dev)
```

## 1.6 "Where does this class go?"
| I am writing… | Folder | Example |
|---|---|---|
| An HTTP endpoint | `controller/` | `CartController.addItem` |
| JSON body / query object coming in | `dto/request/` | `AddCartItemRequest` |
| JSON returned to the front end | `dto/response/` | `CartResponse` |
| A use case that changes data | `service/` (`XxxService`) | `CartService.addItem` |
| A read for the front end | `service/` (`XxxQueryService`) | `OrderQueryService.listMine` |
| Something another module must call | interface + record in `api/`, implementation `XxxApiImpl` in `service/` | `EnrollmentApi` / `EnrollmentApiImpl` |
| Something another module must react to | event record in `api/`, published from `service/` | `CourseHidden` |
| Reacting to another module's event | `listener/` | `CourseHiddenListener` |
| Table mapping or status enum | `entity/` | `Order`, `OrderStatus` |
| Rule about allowed state change | method on the entity in `entity/` | `Course.submitForReview(now)` |
| Database query | `repository/` | `OrderRepository.findByOrderCodeAndStudentId` |
| Converting entity → response | `mapper/` | `OrderMapper` |
| `@PreAuthorize` helper | `security/` | `CourseAccess.isMain(courseId)` |
| Call to an outside system | `integration/` | `PaymentGateway` |
| Periodic job | `scheduler/` | `PaymentExpiryScheduler` |
| Error code / exception | `shared/error` (add a constant to `ErrorCode`) | `CART_OWN_COURSE` |
| Helper used by ≥2 modules | `shared/<topic>/` | `VietnameseNormalizer` |
| Command object for a service | `service/` next to the service | `record CreateOrderCommand(...)` |

## 1.7 Naming conventions
| Kind | Pattern | Notes |
|---|---|---|
| Controller | `<Area>Controller`; admin ones `Admin<Area>Controller`; instructor ones `Instructor<Area>Controller` | base path `/api/v1/...` per docs/ai/05 |
| Request / Response | `<Verb><Noun>Request`, `<Noun>[Detail|Summary]Response` | Java `record` only |
| Write service | `<Noun>Service` | `@Transactional` on public methods |
| Read service | `<Noun>QueryService` | `@Transactional(readOnly = true)` at class level |
| Public API | `<Noun>QueryApi`, `<Noun>AccessApi`, `<Noun>Api`; impl `<Interface>Impl` | impl package-private in `service/` |
| Event | past tense, no suffix: `OrderPaid`, `CourseHidden` | record in `api/`, ids + minimal data, no entities |
| Entity | singular PascalCase of the table: `orders` → `Order`, `lesson_progresses` → `LessonProgress` | `@Table(name = "<exact table>")` |
| Enum | `<Noun>Status`, `<Noun>Type`, `<Noun>Source` | implements `CodeEnum`, lowercase DB code |
| Repository | `<Entity>Repository`; catalog `<Area>CatalogRepository` | |
| Mapper | `<Noun>Mapper` | |
| Access bean | class `<Noun>Access`, bean name lowerCamel: `@Component("courseAccess")` | used as `@courseAccess.isMain(#id)` |
| Listener | `<Event>Listener` or `<Module>MailListener` | |
| Integration | interface = capability (`PaymentGateway`), impl = `<Provider><Capability>` (`FakeQrPaymentGateway`) | |
| Scheduler | `<What>Scheduler` | |
| Tests | `<ClassUnderTest>Test` (unit/slice), `<Feature>IT` (Testcontainers) | same folder as the class under test |

## 1.8 Allowed module dependencies (acyclic)
| Module | May depend on |
|---|---|
| identity | shared |
| authoring | shared, identity::api |
| learning | shared, identity::api, authoring::api |
| commerce | shared, identity::api, authoring::api, learning::api |
| catalog | shared, authoring::api, learning::api (events for cache eviction) |
| notification | shared, identity::api, authoring::api, commerce::api, learning::api |

Consequences:
- `authoring` never depends on `learning`/`commerce`. So UC-I19 (student list) lives in `learning`
  (`InstructorStudentController`, `GET /api/v1/instructor/courses/{courseId}/students`) and checks access through
  `authoring::api.CourseAccessApi` (P10: `@enrollmentAccess.isActiveInstructor()` / `isCourseInstructor(#courseId)`,
  never authoring's internal `@courseAccess` bean). BR-32 relation independent of the role:
  `CourseAccessApi.hasInstructorRelation` (learning `OwnCourseGuard`).
- UC-A12 hide course → `authoring` publishes `CourseHidden` → `commerce.listener.CourseHiddenListener` flags cart items.
- Revoking the instructor role (UC-A05, D-07) only changes `identity`; `authoring` re-checks the role at action time via
  `identity::api.RoleQueryApi` (BR-26).

## 1.9 Public APIs (initial set — extend, never bypass)
| Module | Interface | Purpose |
|---|---|---|
| identity | `UserQueryApi` | `Optional<UserSummary> findSummary(Long id)` → id, fullName, email, status (lower-case code); `Optional<Long> findIdByEmail(String)` (P05-commerce); `Map<Long, UserSummary> findSummaries(Collection<Long> ids)` — one query for a whole list (no N+1), unknown ids absent (P05-authoring) |
| identity | `RoleQueryApi` | `Set<RoleCode> activeRoles(Long userId)`, `boolean hasActiveRole(Long userId, RoleCode role)` — cached 60 s (`identity.active-roles`, key = user id), evicted after commit by `service/RoleCacheEvictor` on every grant/revoke |
| authoring | `CourseQueryApi` | `boolean exists(Long courseId)` (P04-e, admin content access D-32); `Map<Long, CourseSummary> findSummaries(Collection<Long>)` (P05-commerce: title, slug, thumbnail, price, `published`, mainInstructorId; any status, one query); later `Optional<SellableCourse> findSellable(Long courseId)` (published only, BR-31) |
| authoring | `CourseAccessApi` | `isMainInstructor`, `isCourseInstructor` (main or accepted co **and** active instructor role); `Set<Long> findInstructedCourseIds(userId)` (P05-learning, one query, empty without an active instructor role); `Set<Long> findInstructorRelationCourseIds(userId, courseIds)` (P11-b, BR-32 by relation whatever the role, one query) |
| authoring | `LessonQueryApi` | `Optional<Long> findCourseId(lessonId)`; `Map<Long,Integer> countLessons(Collection<Long>)` (one query); `List<ChapterOutline> findOutline(courseId)` (2 queries); `Optional<LessonContent> findContent(lessonId)` (P05-learning) |
| learning | `EnrollmentApi` | `boolean owns(studentId, courseId)`; `Set<Long> findOwnedCourseIds(studentId, Collection<Long>)` (P05-commerce, one query); `void grantForPaidOrder(GrantEnrollmentCommand)` — `@Transactional(propagation = MANDATORY)` (ADR-004) |

## 1.10 Events
| Event (in `api/`) | Publisher | Listeners | Delivery |
|---|---|---|---|
| `UserRegistered`, `EmailVerificationRequested`, `PasswordResetRequested`, `PasswordChanged` | identity | notification | `@ApplicationModuleListener` (async, after commit) |
| `CourseApproved`, `CourseRejected`, `CourseHidden`, `CourseUnhidden`, `CourseContentChanged` | authoring | notification, catalog (cache), commerce (`CourseHidden`) | after commit |
| `CourseApproved` (courseId, slug) · `CourseRejected` (courseId, slug, reason) · `CourseHidden` (courseId, slug, reason) · `CourseUnhidden` (courseId, slug) | authoring (UC-A10, A11, A12, A16, P06-G4) | notification (`AuthoringMailListener` → review-result mails for approved / rejected / hidden to the main instructor; title + main instructor read through `authoring::api.CourseQueryApi`, name / email through `identity::api`), catalog (cache, P08), commerce (`CourseHidden`, P06-G5) | async, after commit |
|  ↳ `CourseContentChanged` (courseId, slug) | published only for a **published or hidden** course (D-14 edit without re-review; P06-G1: UC-I03; P06-G2: UC-I05…I12 chapter / lesson writes; P06-G3: accepted co-instructors changed — accept, remove accepted, leave) | catalog (`catalog.course-detail` by slug, P08) | after commit |
| `CategoryChanged` (categoryId) | authoring (UC-A06…A08: create, update, deactivate, delete) | catalog (`catalog.categories` eviction, P08) | after commit |
| `CoInstructorInvited` (invitationId, courseId, instructorId, invitedBy) | authoring (UC-I13 invite and D-18 re-invite, P06-G3) | notification (`AuthoringMailListener` → invitation mail; names, email, course title read through `identity::api` / `authoring::api` by `MailService`) | async, after commit |
| `OrderPaid` (orderId, orderCode, studentId, totalAmount, courseIds, paidAt) | commerce T2 (P11-a: webhook, lookups) | notification (`CommerceMailListener` → purchase confirmation, BR-20) | async, after commit — mail failure never touches the order (AC-03) |
| `PaymentReconciled` (paymentId, orderCode, studentId, action, grantedCourseIds) | commerce UC-A18 (P11-d) | notification (`CommerceMailListener` → reconciliation result, the admin note is never mailed) | async, after commit |
| `ReviewChanged` (courseId, slug) | learning (UC-S12…S14, A15, A17, P06-G7: create, re-rate, edit, withdraw, hide, restore) | catalog (`catalog.rating` by courseId + `catalog.course-detail` by slug, P08) | after commit |
| `QuestionAnsweredByInstructor` (questionId, answerId, courseId, askerId, instructorId, questionTitle) | learning (UC-I21, P06-G8: instructor-type answers only) | notification (`LearningMailListener` → mail to the asker; names, email, course title / slug read through `identity::api` / `authoring::api` by `MailService`) | async, after commit — mail failure never touches the answer |
| `CertificateIssued` (certificateId, enrollmentId, studentId, courseId) | learning (UC-S10, P06-G6) | logging (NFR-08, `learning.listener.CertificateIssuedListener`: ids only) | after commit |

## 1.11 Transactions
- `@Transactional` only in `service/`. Never in controllers, listeners (they call a service), entities.
- DDD §11 (T1–T7) is missing from our copy; reconstruct from UC/BR and mark `// derived from UC-xx/BR-xx (DDD §11 unavailable)`:
  - **T1 create order** (`OrderService.create`): lock the cart; re-validate items (D-26); replace an old pending order after a
    provider lookup (D-24); insert order + items with price snapshot; `total_amount = Σ unit_price` (BR-27).
  - **T2 apply payment result** (`PaymentService.applyResult`): `SELECT … FOR UPDATE` on the payment row; idempotent by
    `transaction_ref` (NFR-04); amount/order validation (BR-14); conflict or mismatch → `needs_review` (BR-30); else
    payment `success`, order `paid`, `EnrollmentApi.grantForPaidOrder(...)`, remove bought cart items — one transaction.
  - **T7 consume token** (`AccountTokenService.consume`): single atomic `UPDATE … WHERE used_at IS NULL AND expires_at > ?`;
    1 row updated = success (BR-34).
- Commerce T1 / QR / T2 / cancel / UC-A18 / timers: lock order, scalar-first reads and provider calls inside a
  `TransactionTemplate` with `edumy.payment.tx-timeout` — **ADR-011** (P11).
- Schedulers (P11-e): one row per transaction claimed with `SELECT … LIMIT 1 FOR UPDATE [OF o] SKIP LOCKED`; on with
  `edumy.scheduler.enabled=true` (`application.yml`), off in the test profile.

## 1.12 Catalog read model (ADR-003)
`catalog.repository.*CatalogRepository` classes use `JdbcClient` with hand-written `SELECT`s mapped straight into
`dto/response` records. Visibility rules in SQL: only `published` courses (D-08), only `visible` reviews. Never write,
never import another module's entity or repository.

## 1.13 Spring Modulith declarations
```java
// src/main/java/com/edumy/commerce/package-info.java
@org.springframework.modulith.ApplicationModule(
    displayName = "04 Commerce",
    allowedDependencies = {"shared", "identity :: api", "authoring :: api", "learning :: api"})
package com.edumy.commerce;

// src/main/java/com/edumy/commerce/api/package-info.java
@org.springframework.modulith.NamedInterface("api")
package com.edumy.commerce.api;

// src/main/java/com/edumy/shared/package-info.java
@org.springframework.modulith.ApplicationModule(type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.edumy.shared;
```
```java
// src/test/java/com/edumy/ModularityTest.java
class ModularityTest {
    static final ApplicationModules modules = ApplicationModules.of(EdumyApplication.class);
    @Test void verifiesModularStructure() { modules.verify(); }
    @Test void writesDocumentation() { new Documenter(modules).writeDocumentation(); }
}
```
A module folder that is empty in phase 1 still gets its `package-info.java` so the structure is visible from day one.
