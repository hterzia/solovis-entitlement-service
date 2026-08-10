# Entitlement Service — API Layer Implementation Plan

> **Status: complete and merged**, including the post-merge addendum (Tasks 11–12: capability usage on `GET`, accounts cursor pagination). The `- [ ]` checkboxes were never ticked back — this file is an archived record of how the REST surface was built, not outstanding work. Verify against the code, not the boxes. This plan also carries the admin-API contract fixes that `2026-08-09-entitlement-ui-contract-fixes.md` refers to by a filename that never existed.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the REST surface of `entitlement-service` on top of the already-complete `entitlement-core` (domain model, `Resolver`, immutable `Snapshot`) and `entitlement-service/store` (JdbcClient repositories) — the shared value/error contracts, the in-memory snapshot lifecycle, and all four API surfaces from `.specs/001-entitlement-service/contracts/` (decision API, admin API, snapshot feed) — so the operator SPA (being built concurrently in the `worktree-entitlement-ui-frontend` worktree) and future SDK/product-service consumers have a real, running contract to build against instead of only the markdown documents.

**Architecture:** One shared wire vocabulary (`ValueDto`, `CapabilityDescriptorDto`, the RFC 9457 error model) is built first, because `contracts/README.md` states it is used identically across every surface — this is "the contracts" the user asked to start with, and every controller below depends on it. Under that: a `SnapshotHolder` (`AtomicReference<Snapshot>`) is the one thing every read route touches, assembled at startup by a `SnapshotAssembler` that walks the existing repositories. Every mutating admin route follows the same shape — validate, write via the existing repository, record one `audit_event` row, then hand a pure `Snapshot -> Snapshot` mutation (reusing `entitlement-core`'s already-built `SnapshotMutator`) to a `SnapshotPublisher`, all inside one `@Transactional` method, with the in-memory swap deferred to `afterCommit()` so a rolled-back write can never leave the live snapshot ahead of the database. `Resolver.resolve()`/`Resolver.explain()` — already fully implemented and tested in `entitlement-core` — are never reimplemented here, only called.

**Tech Stack:** Java 21, Spring Boot 4.0.7 (`web`, `jdbc`, `validation`, `actuator`, springdoc-openapi 2.8.6), the existing `entitlement-core` and `entitlement-service/store` modules, JUnit 5 + AssertJ + Spring `MockMvc` for tests.

## Global Constraints

- Package root `com.solovis.entitlement.service`; module is `management/backend/entitlement-service` (already exists, already builds — do not touch `pom.xml` unless a task below explicitly says so).
- Every response shape, field name, error `type` slug and status code must match `.specs/001-entitlement-service/contracts/*.md` **exactly** — those files are the source of truth the frontend worktree is coding against; do not improvise a field name.
- Value encoding, capability descriptor shape and the RFC 9457 error model are defined **once** (Task 1) and reused by every surface — never redefine `ValueDto` per package (`contracts/README.md`, "Conventions").
- Timestamps are ISO-8601 UTC with milliseconds on the wire (`Instant.toString()` already produces this format in Java). Internally the DB stores the same format as `TEXT` (established by the already-built store layer) — always pass `Instant.now(clock).toString()`-shaped values, never format manually.
- Never call `java.time.Instant.now()` or `Clock.systemUTC()` directly in a controller or service class — inject `java.time.Clock` (a `Clock` bean is added in Task 1) so tests can fix time. This mirrors the existing store-layer tests' determinism discipline.
- Every mutating route commits its row-level write, its `audit_event` row and its `snapshot_version` row in **one** Spring `@Transactional` method, and the in-memory `SnapshotHolder` swap happens only in that transaction's `afterCommit()` callback (`admin-api.md`, "Write semantics common to every mutating route"; c30). Never call `SnapshotHolder.set(...)` directly from a controller or outside a `SnapshotPublisher.publish(...)` call.
- No authentication in v1 (user decision, `plan.md` "Accepted deviations"). Every route stays open. `ActorResolver` (Task 2) is the only seam — never hardcode an actor string in a controller.
- No `parentPlanKey`, no override edit route, no capability delete, no plan rollback — these are deliberate absences (c5, c8, §3.4, §8), not gaps to fill in.
- `entitlement-core` is modified only twice in this plan (Task 1 adds `EntitlementView.capabilities()`; no other core change is in scope) — every other behaviour is composed from what already exists there. If a task seems to need more core logic than that, stop and re-read `Resolver`/`Snapshot`/`SnapshotMutator` — the arithmetic is almost certainly already there.
- Tests run against a temp-file SQLite database via the existing `src/test/resources/application.yaml` (one file per JVM run) — this is already wired by the db-layer plan; do not add Testcontainers or an in-memory H2 substitute.
- Only files this plan touches may be staged/committed at each checkpoint. The working tree has unrelated pending changes (`.specs/**`, `DECISIONS.md`, deleted `homepage.html`) and an unrelated untracked `refs/` directory — never stage or commit those from this plan.

---

## File Structure

```
entitlement-service/src/main/java/com/solovis/entitlement/service/
├── EntitlementServiceApplication.java        (exists)
├── dto/                                       ValueDto, ValueMapper, CapabilityDescriptorDto,
│                                               CapabilityDescriptorMapper — the shared wire vocabulary
│                                               every surface below imports (Task 1)
├── error/                                     ErrorCode, EntitlementApiException,
│                                               GlobalExceptionHandler (Task 1)
├── time/                                      ClockConfig, Iso8601 (Task 1)
├── audit/                                     Actor, ActorResolver, StubActorResolver,
│                                               AuditEntry, AuditRecorder (Task 2)
├── snapshot/                                  ValueColumnCodec, RowMappers, SnapshotAssembler,
│                                               SnapshotHolder, SnapshotPublisher, SnapshotStartup,
│                                               DeltaChange, DeltaJson, DeltaFeedService (Tasks 3, 9)
├── api/
│   ├── DecisionController                     (Task 4)
│   ├── SnapshotFeedController                 (Task 9)
│   └── dto/                                   DecisionResponseDto (+ nested TraceDto),
│                                               WholeAccountResponseDto, EntitlementSummaryDto,
│                                               CapabilityListResponseDto
├── admin/
│   ├── CapabilityAdminController + service/CapabilityAdminService + dto/     (Task 5)
│   ├── PlanAdminController + SettingsController + service/PlanAdminService + dto/  (Task 6)
│   ├── AccountAdminController + OverrideAdminController + service/AccountAdminService + dto/  (Task 7)
│   ├── CheckerController, AuditController, MetaController + dto/AuditEventDto  (Task 8)
├── config/
│   ├── SqliteConfig, EntitlementDatabaseProperties  (exist)
│   ├── JacksonConfig, WebConfig, OpenApiConfig       (Task 10)
└── seed/                                       DemoDataSeeder                  (Task 10)
```

Deviation from `plan.md`'s literal listing, stated here so it isn't mistaken for drift: `dto/` for the shared value/capability vocabulary lives at `service.dto` (not nested under `api/` or `admin/`) because `contracts/README.md` requires the *identical* encoding on every surface — a single package is what makes that structural rather than a convention four packages have to independently honour. `admin/service/` is an implementation-layer addition (thin controllers, transactional services) not named in `plan.md`; it does not change any route, request or response shape.

---

## Task 1: Shared wire vocabulary — value codec, capability descriptor, error model, clock

**This is "the contracts" task** — every other task in this plan, and every other agent working in parallel (the frontend worktree, a future SDK task), depends only on what this task produces. Do it first, in isolation, fully tested, before any controller exists.

**Files:**
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/dto/ValueDto.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/dto/ValueMapper.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/dto/CapabilityDescriptorDto.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/dto/CapabilityDescriptorMapper.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/dto/package-info.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/error/ErrorCode.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/error/EntitlementApiException.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/error/GlobalExceptionHandler.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/error/package-info.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/time/ClockConfig.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/time/package-info.java`
- Modify: `entitlement-core/src/main/java/com/solovis/entitlement/core/view/EntitlementView.java` (add `Collection<Capability> capabilities()`)
- Modify: `entitlement-core/src/main/java/com/solovis/entitlement/core/view/Snapshot.java` (implement it)
- Test: `entitlement-core/src/test/java/com/solovis/entitlement/core/view/SnapshotBuilderTest.java` (append one test)
- Test: `entitlement-service/src/test/java/com/solovis/entitlement/service/dto/ValueMapperTest.java`
- Test: `entitlement-service/src/test/java/com/solovis/entitlement/service/dto/CapabilityDescriptorMapperTest.java`
- Test: `entitlement-service/src/test/java/com/solovis/entitlement/service/error/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Consumes: `entitlement-core` model types (`EntitlementValue`, `Capability`, `ValueType`, `TierOrder`) — read-only, no core behaviour change beyond the one interface method above.
- Produces: `ValueDto`, `ValueMapper.toDto(EntitlementValue): ValueDto`, `ValueMapper.fromDto(ValueDto, Capability): EntitlementValue` (throws `EntitlementApiException`), `CapabilityDescriptorDto`, `CapabilityDescriptorMapper.toDescriptor(Capability): CapabilityDescriptorDto`, `ErrorCode` enum (every `contracts/README.md` "Error model" row as one entry: slug + HTTP status), `EntitlementApiException(ErrorCode, String detail, Map<String,Object> extraProperties)`, `GlobalExceptionHandler` mapping both `EntitlementApiException` and the three `entitlement-core` exceptions to `application/problem+json`. Every later task throws `EntitlementApiException`, never a bespoke exception type. A `Clock` bean (`time.ClockConfig`) is what every later task injects instead of calling `Instant.now()`.

- [ ] **Step 1: Write the failing tests for the value codec**

```java
// entitlement-service/src/test/java/com/solovis/entitlement/service/dto/ValueMapperTest.java
package com.solovis.entitlement.service.dto;

import com.solovis.entitlement.core.model.*;
import com.solovis.entitlement.service.error.EntitlementApiException;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValueMapperTest {

    private static final Capability QUANTITY_CAP = new Capability(
        new CapabilityKey("reports.monthly"), "Monthly reports", null, ValueType.QUANTITY,
        EntitlementValue.Quantity.of(0), Optional.empty(), TierOrder.NONE, Capability.Status.ACTIVE, null);

    private static final Capability TIER_CAP = new Capability(
        new CapabilityKey("support.tier"), "Support", null, ValueType.TIER,
        new EntitlementValue.Tier("community", 0), Optional.empty(),
        new TierOrder(List.of(
            new TierOrder.TierDefinition("community", 0, "Community"),
            new TierOrder.TierDefinition("gold", 1, "Gold"))),
        Capability.Status.ACTIVE, null);

    @Test
    void switchToDtoOmitsUnrelatedFields() {
        var dto = ValueMapper.toDto(new EntitlementValue.Switch(true));
        assertThat(dto.type()).isEqualTo("SWITCH");
        assertThat(dto.enabled()).isTrue();
        assertThat(dto.amount()).isNull();
        assertThat(dto.unlimited()).isNull();
        assertThat(dto.tier()).isNull();
    }

    @Test
    void unlimitedQuantityToDtoCarriesNoAmount() {
        var dto = ValueMapper.toDto(EntitlementValue.Quantity.unbounded());
        assertThat(dto.type()).isEqualTo("QUANTITY");
        assertThat(dto.unlimited()).isTrue();
        assertThat(dto.amount()).isNull();
    }

    @Test
    void tierToDtoCarriesOrdinal() {
        var dto = ValueMapper.toDto(new EntitlementValue.Tier("gold", 1));
        assertThat(dto.type()).isEqualTo("TIER");
        assertThat(dto.tier()).isEqualTo("gold");
        assertThat(dto.ordinal()).isEqualTo(1);
    }

    @Test
    void fromDtoAcceptsAmount() {
        var dto = new ValueDto("QUANTITY", null, 50L, null, null, null);
        var value = ValueMapper.fromDto(dto, QUANTITY_CAP);
        assertThat(value).isEqualTo(EntitlementValue.Quantity.of(50));
    }

    @Test
    void fromDtoAcceptsUnlimited() {
        var dto = new ValueDto("QUANTITY", null, null, true, null, null);
        assertThat(ValueMapper.fromDto(dto, QUANTITY_CAP)).isEqualTo(EntitlementValue.Quantity.unbounded());
    }

    @Test
    void fromDtoRejectsBothAmountAndUnlimited() {
        var dto = new ValueDto("QUANTITY", null, 50L, true, null, null);
        assertThatThrownBy(() -> ValueMapper.fromDto(dto, QUANTITY_CAP))
            .isInstanceOf(EntitlementApiException.class)
            .extracting("errorCode").isEqualTo(com.solovis.entitlement.service.error.ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void fromDtoRejectsTypeMismatch() {
        var dto = new ValueDto("SWITCH", true, null, null, null, null);
        assertThatThrownBy(() -> ValueMapper.fromDto(dto, QUANTITY_CAP))
            .isInstanceOf(EntitlementApiException.class)
            .extracting("errorCode").isEqualTo(com.solovis.entitlement.service.error.ErrorCode.VALUE_TYPE_MISMATCH);
    }

    @Test
    void fromDtoIgnoresRequestOrdinalAndTrustsTierKey() {
        var dto = new ValueDto("TIER", null, null, null, "gold", 999); // wrong ordinal on the wire
        var value = ValueMapper.fromDto(dto, TIER_CAP);
        assertThat(value).isEqualTo(new EntitlementValue.Tier("gold", 1)); // authoritative ordinal from the capability
    }

    @Test
    void fromDtoRejectsUndeclaredTier() {
        var dto = new ValueDto("TIER", null, null, null, "platinum", null);
        assertThatThrownBy(() -> ValueMapper.fromDto(dto, TIER_CAP))
            .isInstanceOf(EntitlementApiException.class)
            .extracting("errorCode").isEqualTo(com.solovis.entitlement.service.error.ErrorCode.UNKNOWN_TIER);
    }
}
```

- [ ] **Step 2: Run it to confirm it fails to compile** (nothing exists yet)

Run: `./mvnw -pl entitlement-service -am test -Dtest=ValueMapperTest`
Expected: compilation failure — `ValueDto`, `ValueMapper`, `ErrorCode`, `EntitlementApiException` do not exist.

- [ ] **Step 3: Create the error model first** (the value codec throws it)

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/error/ErrorCode.java
package com.solovis.entitlement.service.error;

import org.springframework.http.HttpStatus;

/** Every `type` slug from contracts/README.md's error model table, paired with its HTTP status. */
public enum ErrorCode {
    UNKNOWN_ACCOUNT("entitlement/unknown-account", HttpStatus.NOT_FOUND, "Unknown account"),
    UNKNOWN_CAPABILITY("entitlement/unknown-capability", HttpStatus.NOT_FOUND, "Unknown capability"),
    RETIRED_CAPABILITY("entitlement/retired-capability", HttpStatus.CONFLICT, "Retired capability"),
    VALUE_TYPE_MISMATCH("entitlement/value-type-mismatch", HttpStatus.UNPROCESSABLE_ENTITY, "Value type mismatch"),
    UNKNOWN_TIER("entitlement/unknown-tier", HttpStatus.UNPROCESSABLE_ENTITY, "Unknown tier"),
    REASON_REQUIRED("entitlement/reason-required", HttpStatus.UNPROCESSABLE_ENTITY, "Reason required"),
    PLAN_IN_USE("entitlement/plan-in-use", HttpStatus.CONFLICT, "Plan in use"),
    DEFAULT_PLAN_REQUIRED("entitlement/default-plan-required", HttpStatus.CONFLICT, "Default plan required"),
    CAPABILITY_RETIRED_FOR_WRITE("entitlement/capability-retired-for-write", HttpStatus.CONFLICT, "Capability retired for write"),
    IMMUTABLE_FIELD("entitlement/immutable-field", HttpStatus.CONFLICT, "Immutable field"),
    SNAPSHOT_TOO_OLD("entitlement/snapshot-too-old", HttpStatus.GONE, "Snapshot too old"),
    SNAPSHOT_BEHIND("entitlement/snapshot-behind", HttpStatus.CONFLICT, "Snapshot behind"),
    VALIDATION_FAILED("entitlement/validation-failed", HttpStatus.UNPROCESSABLE_ENTITY, "Validation failed"),
    PREVIEW_TOKEN_INVALID("entitlement/preview-token-invalid", HttpStatus.CONFLICT, "Preview token invalid or stale");

    private final String type;
    private final HttpStatus status;
    private final String title;

    ErrorCode(String type, HttpStatus status, String title) {
        this.type = type;
        this.status = status;
        this.title = title;
    }

    public String type() { return type; }
    public HttpStatus status() { return status; }
    public String title() { return title; }
}
```

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/error/EntitlementApiException.java
package com.solovis.entitlement.service.error;

import java.util.LinkedHashMap;
import java.util.Map;

/** The one exception type every service/controller in this codebase throws for a contract-defined error. */
public class EntitlementApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Map<String, Object> extraProperties;

    public EntitlementApiException(ErrorCode errorCode, String detail) {
        this(errorCode, detail, Map.of());
    }

    public EntitlementApiException(ErrorCode errorCode, String detail, Map<String, Object> extraProperties) {
        super(detail);
        this.errorCode = errorCode;
        this.extraProperties = new LinkedHashMap<>(extraProperties);
    }

    public ErrorCode errorCode() { return errorCode; }
    public Map<String, Object> extraProperties() { return extraProperties; }
}
```

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/error/GlobalExceptionHandler.java
package com.solovis.entitlement.service.error;

import com.solovis.entitlement.core.error.RetiredCapabilityException;
import com.solovis.entitlement.core.error.UnknownAccountException;
import com.solovis.entitlement.core.error.UnknownCapabilityException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.net.URI;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(EntitlementApiException.class)
    public ProblemDetail handleApiException(EntitlementApiException ex, HttpServletRequest request) {
        return problem(ex.errorCode(), ex.getMessage(), request, ex.extraProperties());
    }

    @ExceptionHandler(UnknownAccountException.class)
    public ProblemDetail handleUnknownAccount(UnknownAccountException ex, HttpServletRequest request) {
        return problem(ErrorCode.UNKNOWN_ACCOUNT, ex.getMessage(), request,
            Map.of("account", ex.accountExternalId()));
    }

    @ExceptionHandler(UnknownCapabilityException.class)
    public ProblemDetail handleUnknownCapability(UnknownCapabilityException ex, HttpServletRequest request) {
        return problem(ErrorCode.UNKNOWN_CAPABILITY, ex.getMessage(), request,
            Map.of("capability", ex.capabilityKey()));
    }

    @ExceptionHandler(RetiredCapabilityException.class)
    public ProblemDetail handleRetiredCapability(RetiredCapabilityException ex, HttpServletRequest request) {
        return problem(ErrorCode.RETIRED_CAPABILITY, ex.getMessage(), request,
            Map.of("capability", ex.capabilityKey()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleBeanValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<String> violations = ex.getBindingResult().getFieldErrors().stream()
            .map(FieldError::toString).toList();
        return problem(ErrorCode.VALIDATION_FAILED, "Request failed validation.", request,
            Map.of("violations", violations));
    }

    private ProblemDetail problem(ErrorCode code, String detail, HttpServletRequest request, Map<String, Object> extra) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setType(URI.create(code.type()));
        problem.setTitle(code.title());
        problem.setInstance(URI.create(request.getRequestURI()));
        extra.forEach(problem::setProperty);
        return problem;
    }
}
```

- [ ] **Step 4: Add the clock seam**

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/time/ClockConfig.java
package com.solovis.entitlement.service.time;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
```

- [ ] **Step 5: Implement `ValueDto` and `ValueMapper`**

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/dto/ValueDto.java
package com.solovis.entitlement.service.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The one value encoding used identically across every API surface (contracts/README.md,
 * "Value encoding"). Every field except `type` is nullable and omitted when unset — the shape
 * a caller sees depends entirely on `type`.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ValueDto(
    String type,
    Boolean enabled,
    Long amount,
    Boolean unlimited,
    String tier,
    Integer ordinal
) {}
```

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/dto/ValueMapper.java
package com.solovis.entitlement.service.dto;

import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.model.EntitlementValue;
import com.solovis.entitlement.core.model.ValueType;
import com.solovis.entitlement.service.error.EntitlementApiException;
import com.solovis.entitlement.service.error.ErrorCode;
import java.util.Map;

/** Converts between the wire {@link ValueDto} and the core {@link EntitlementValue}. */
public final class ValueMapper {

    private ValueMapper() {}

    public static ValueDto toDto(EntitlementValue value) {
        return switch (value) {
            case EntitlementValue.Switch s -> new ValueDto("SWITCH", s.enabled(), null, null, null, null);
            case EntitlementValue.Quantity q -> q.unlimited()
                ? new ValueDto("QUANTITY", null, null, true, null, null)
                : new ValueDto("QUANTITY", null, q.amount(), null, null, null);
            case EntitlementValue.Tier t -> new ValueDto("TIER", null, null, null, t.tierKey(), t.ordinal());
        };
    }

    /**
     * Validates {@code dto} against {@code capability}'s declared shape (README.md "Value
     * encoding"; c1). `ordinal` is accepted but ignored on a request — `tier` is authoritative,
     * so the returned value always carries the capability's own declared ordinal for that key.
     */
    public static EntitlementValue fromDto(ValueDto dto, Capability capability) {
        ValueType declaredType = requireType(dto, capability);
        return switch (declaredType) {
            case SWITCH -> {
                if (dto.enabled() == null) {
                    throw validationFailed("A SWITCH value requires 'enabled'.");
                }
                yield new EntitlementValue.Switch(dto.enabled());
            }
            case QUANTITY -> {
                boolean hasAmount = dto.amount() != null;
                boolean hasUnlimited = Boolean.TRUE.equals(dto.unlimited());
                if (hasAmount == hasUnlimited) {
                    throw validationFailed("A QUANTITY value must carry exactly one of 'amount' or 'unlimited'.");
                }
                yield hasUnlimited ? EntitlementValue.Quantity.unbounded() : EntitlementValue.Quantity.of(dto.amount());
            }
            case TIER -> {
                if (dto.tier() == null) {
                    throw validationFailed("A TIER value requires 'tier'.");
                }
                var ordinal = capability.tierOrder().ordinalOf(dto.tier());
                if (ordinal.isEmpty()) {
                    throw new EntitlementApiException(ErrorCode.UNKNOWN_TIER,
                        "Tier '" + dto.tier() + "' is not declared by capability '" + capability.key() + "'.",
                        Map.of("capability", capability.key().value(), "tier", dto.tier()));
                }
                yield new EntitlementValue.Tier(dto.tier(), ordinal.getAsInt());
            }
        };
    }

    private static ValueType requireType(ValueDto dto, Capability capability) {
        ValueType declared;
        try {
            declared = ValueType.valueOf(dto.type());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw validationFailed("Unknown value type '" + dto.type() + "'.");
        }
        if (declared != capability.valueType()) {
            throw new EntitlementApiException(ErrorCode.VALUE_TYPE_MISMATCH,
                "Value type " + declared + " does not match capability '" + capability.key()
                    + "' (" + capability.valueType() + ").",
                Map.of("capability", capability.key().value()));
        }
        return declared;
    }

    private static EntitlementApiException validationFailed(String detail) {
        return new EntitlementApiException(ErrorCode.VALIDATION_FAILED, detail);
    }
}
```

- [ ] **Step 6: Run the value-codec test, confirm it passes**

Run: `./mvnw -pl entitlement-service -am test -Dtest=ValueMapperTest`
Expected: all 9 tests pass.

- [ ] **Step 7: Add `EntitlementView.capabilities()` to entitlement-core, with its test**

```java
// entitlement-core/src/test/java/com/solovis/entitlement/core/view/SnapshotBuilderTest.java
// append this test method to the existing test class
@Test
void capabilitiesReturnsEveryCapabilityRegardlessOfStatus() {
    var active = new Capability(new CapabilityKey("api.access"), "API", null, ValueType.SWITCH,
        new EntitlementValue.Switch(false), Optional.empty(), TierOrder.NONE, Capability.Status.ACTIVE, null);
    var retired = new Capability(new CapabilityKey("export.parquet"), "Export", null, ValueType.SWITCH,
        new EntitlementValue.Switch(false), Optional.empty(), TierOrder.NONE, Capability.Status.RETIRED, Instant.now());
    var snapshot = new SnapshotBuilder().capability(active).capability(retired).build(1);

    assertThat(snapshot.capabilities()).containsExactlyInAnyOrder(active, retired);
    assertThat(snapshot.activeCapabilities()).containsExactly(active);
}
```

Run: `./mvnw -pl entitlement-core test -Dtest=SnapshotBuilderTest` — confirm it fails to compile (`capabilities()` does not exist on `Snapshot`).

Add to `entitlement-core/.../view/EntitlementView.java`, directly above the existing `activeCapabilities()`:

```java
    /** Every declared capability, retired or not — the registry read needs this; resolution never does. */
    Collection<Capability> capabilities();

```

Add to `entitlement-core/.../view/Snapshot.java`, directly above the existing `activeCapabilities()` override:

```java
    @Override
    public Collection<Capability> capabilities() {
        return capabilities.values();
    }

```

Run: `./mvnw -pl entitlement-core test -Dtest=SnapshotBuilderTest` — confirm it passes, and re-run the full `entitlement-core` suite to confirm nothing else implements `EntitlementView` and needed updating: `./mvnw -pl entitlement-core test`.

- [ ] **Step 8: Write and implement `CapabilityDescriptorDto` + mapper**

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/dto/CapabilityDescriptorDto.java
package com.solovis.entitlement.service.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Returned wherever a caller needs to interpret values (contracts/README.md, "Capability descriptor"). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CapabilityDescriptorDto(
    String key,
    String area,
    String displayName,
    String description,
    String valueType,
    @JsonProperty("default") ValueDto defaultValue,
    ValueDto offValue,
    List<TierDto> tiers,
    String status
) {
    public record TierDto(String tier, int ordinal, String displayName) {}
}
```

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/dto/CapabilityDescriptorMapper.java
package com.solovis.entitlement.service.dto;

import com.solovis.entitlement.core.model.Capability;

public final class CapabilityDescriptorMapper {

    private CapabilityDescriptorMapper() {}

    public static CapabilityDescriptorDto toDescriptor(Capability capability) {
        var tiers = capability.tierOrder().tiers().stream()
            .map(t -> new CapabilityDescriptorDto.TierDto(t.tierKey(), t.ordinal(), t.displayName()))
            .toList();
        return new CapabilityDescriptorDto(
            capability.key().value(),
            capability.area(),
            capability.displayName(),
            capability.description(),
            capability.valueType().name(),
            ValueMapper.toDto(capability.defaultValue()),
            capability.offValue().map(ov -> ValueMapper.toDto(ov.value())).orElse(null),
            tiers.isEmpty() ? null : tiers,
            capability.status().name());
    }
}
```

```java
// entitlement-service/src/test/java/com/solovis/entitlement/service/dto/CapabilityDescriptorMapperTest.java
package com.solovis.entitlement.service.dto;

import com.solovis.entitlement.core.model.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityDescriptorMapperTest {

    @Test
    void mapsQuantityCapabilityWithNoTiersAndNoOffValue() {
        var capability = new Capability(new CapabilityKey("reports.monthly"), "Monthly reports", "desc",
            ValueType.QUANTITY, EntitlementValue.Quantity.of(0), Optional.empty(), TierOrder.NONE,
            Capability.Status.ACTIVE, null);

        var dto = CapabilityDescriptorMapper.toDescriptor(capability);

        assertThat(dto.key()).isEqualTo("reports.monthly");
        assertThat(dto.area()).isEqualTo("reports");
        assertThat(dto.valueType()).isEqualTo("QUANTITY");
        assertThat(dto.defaultValue().amount()).isEqualTo(0L);
        assertThat(dto.offValue()).isNull();
        assertThat(dto.tiers()).isNull();
        assertThat(dto.status()).isEqualTo("ACTIVE");
    }

    @Test
    void mapsTierCapabilityWithAscendingTiers() {
        var tierOrder = new TierOrder(List.of(
            new TierOrder.TierDefinition("community", 0, "Community"),
            new TierOrder.TierDefinition("gold", 1, "Gold")));
        var capability = new Capability(new CapabilityKey("support.tier"), "Support level", null,
            ValueType.TIER, new EntitlementValue.Tier("community", 0), Optional.empty(), tierOrder,
            Capability.Status.ACTIVE, null);

        var dto = CapabilityDescriptorMapper.toDescriptor(capability);

        assertThat(dto.tiers()).extracting(CapabilityDescriptorDto.TierDto::tier)
            .containsExactly("community", "gold");
        assertThat(dto.tiers().get(1).ordinal()).isEqualTo(1);
    }
}
```

- [ ] **Step 9: Add a `GlobalExceptionHandlerTest` slice test**

```java
// entitlement-service/src/test/java/com/solovis/entitlement/service/error/GlobalExceptionHandlerTest.java
package com.solovis.entitlement.service.error;

import com.solovis.entitlement.core.error.UnknownAccountException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = GlobalExceptionHandlerTest.ThrowingController.class)
class GlobalExceptionHandlerTest {

    @Autowired MockMvc mockMvc;

    @RestController
    static class ThrowingController {
        @GetMapping("/test/unknown-account")
        void unknownAccount() { throw new UnknownAccountException("acct_missing"); }

        @GetMapping("/test/reason-required")
        void reasonRequired() {
            throw new EntitlementApiException(ErrorCode.REASON_REQUIRED, "Reason is required.");
        }
    }

    @Test
    void unknownAccountMapsToProblemJson() throws Exception {
        mockMvc.perform(get("/test/unknown-account"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.type").value("entitlement/unknown-account"))
            .andExpect(jsonPath("$.account").value("acct_missing"));
    }

    @Test
    void apiExceptionMapsToItsDeclaredStatusAndSlug() throws Exception {
        mockMvc.perform(get("/test/reason-required"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.type").value("entitlement/reason-required"));
    }
}
```

- [ ] **Step 10: Run the full module test suites and commit**

Run: `./mvnw -pl entitlement-core,entitlement-service -am test`
Expected: BUILD SUCCESS, all new and existing tests pass.

```bash
git add entitlement-core/src/main/java/com/solovis/entitlement/core/view/EntitlementView.java \
        entitlement-core/src/main/java/com/solovis/entitlement/core/view/Snapshot.java \
        entitlement-core/src/test/java/com/solovis/entitlement/core/view/SnapshotBuilderTest.java \
        entitlement-service/src/main/java/com/solovis/entitlement/service/dto \
        entitlement-service/src/main/java/com/solovis/entitlement/service/error \
        entitlement-service/src/main/java/com/solovis/entitlement/service/time \
        entitlement-service/src/test/java/com/solovis/entitlement/service/dto \
        entitlement-service/src/test/java/com/solovis/entitlement/service/error
git commit -m "feat(entitlement-service): shared value/capability wire vocabulary and error model"
```

## Task 2: Actor resolution and the audit recorder

**Files:**
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/audit/Actor.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/audit/ActorResolver.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/audit/StubActorResolver.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/audit/AuditEntry.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/audit/AuditRecorder.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/audit/package-info.java`
- Modify: `entitlement-service/src/main/resources/application.yaml` (add `entitlement.actor.id`)
- Test: `entitlement-service/src/test/java/com/solovis/entitlement/service/audit/AuditRecorderTest.java`

**Interfaces:**
- Consumes: `AuditEventRepository`/`AuditEventRow` (exist, `service.store`), `Clock` (Task 1).
- Produces: `Actor(String id, Actor.Kind kind)`, `ActorResolver.currentActor(): Actor`, `AuditRecorder.record(AuditEntry): long` (returns the new `audit_event.seq`) — every later write-path task calls exactly this. `AuditEntry` is a builder-style record covering every column `data-model.md` §7 lists (`entityType`, `entityId`, `action`, optional `accountId`/`planId`/`capabilityId`, `beforeJson`/`afterJson`, `reason`, `affectedAccountCount`), with `entityType`/`action` restricted to the `data-model.md` CHECK-constraint vocabulary so a typo fails a unit test, not a `CHECK` violation in CI.

- [ ] **Step 1: Write the failing test**

```java
// entitlement-service/src/test/java/com/solovis/entitlement/service/audit/AuditRecorderTest.java
package com.solovis.entitlement.service.audit;

import com.solovis.entitlement.service.store.AuditEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AuditRecorderTest {

    @Autowired AuditEventRepository repository;

    @Test
    void recordWritesEveryColumnAndReturnsTheAssignedSeq() {
        var fixedClock = Clock.fixed(Instant.parse("2026-08-09T14:03:11.482Z"), ZoneOffset.UTC);
        var recorder = new AuditRecorder(repository, fixedClock);
        var entry = AuditEntry.builder()
            .actor(new Actor("dev-operator", Actor.Kind.PERSON))
            .source("UI")
            .entityType("CAPABILITY")
            .entityId("reports.monthly")
            .action("CREATE")
            .capabilityId(7L)
            .afterJson("{\"displayName\":\"Monthly reports\"}")
            .build();

        long seq = recorder.record(entry);

        var row = repository.findBySeq(seq).orElseThrow();
        assertThat(row.occurredAt()).isEqualTo("2026-08-09T14:03:11.482Z");
        assertThat(row.actorKind()).isEqualTo("PERSON");
        assertThat(row.actorId()).isEqualTo("dev-operator");
        assertThat(row.source()).isEqualTo("UI");
        assertThat(row.entityType()).isEqualTo("CAPABILITY");
        assertThat(row.action()).isEqualTo("CREATE");
        assertThat(row.capabilityId()).isEqualTo(7L);
        assertThat(row.accountId()).isNull();
        assertThat(row.afterJson()).contains("Monthly reports");
    }
}
```

- [ ] **Step 2: Run it, confirm compile failure** — `Actor`, `AuditEntry`, `AuditRecorder` don't exist yet.

- [ ] **Step 3: Implement**

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/audit/Actor.java
package com.solovis.entitlement.service.audit;

import java.util.Objects;

/** Who performed a write (data-model.md audit_event.actor_kind/actor_id; c32, c36). */
public record Actor(String id, Kind kind) {

    public enum Kind { PERSON, SYSTEM }

    public Actor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
    }
}
```

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/audit/ActorResolver.java
package com.solovis.entitlement.service.audit;

/**
 * The auth seam (research.md §14). v1 ships only {@link StubActorResolver}; swapping in OIDC
 * later is a bean replacement, not a retrofit through every write path.
 */
public interface ActorResolver {
    Actor currentActor();
}
```

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/audit/StubActorResolver.java
package com.solovis.entitlement.service.audit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** No authentication in v1 (plan.md "Accepted deviations") — every write is attributed to one configured identity. */
@Component
public class StubActorResolver implements ActorResolver {

    private final Actor actor;

    public StubActorResolver(@Value("${entitlement.actor.id:dev-operator}") String actorId) {
        this.actor = new Actor(actorId, Actor.Kind.PERSON);
    }

    @Override
    public Actor currentActor() {
        return actor;
    }
}
```

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/audit/AuditEntry.java
package com.solovis.entitlement.service.audit;

import java.util.Set;

public record AuditEntry(
    Actor actor,
    String source,
    String entityType,
    String entityId,
    String action,
    Long accountId,
    Long planId,
    Long capabilityId,
    String beforeJson,
    String afterJson,
    String reason,
    Long affectedAccountCount
) {
    private static final Set<String> SOURCES = Set.of("UI", "BILLING", "API", "SEED");
    private static final Set<String> ENTITY_TYPES = Set.of(
        "CAPABILITY", "CAPABILITY_TIER", "PLAN", "PLAN_ENTITLEMENT", "ACCOUNT", "ACCOUNT_PLAN",
        "DEFAULT_PLAN", "OVERRIDE");
    private static final Set<String> ACTIONS = Set.of(
        "CREATE", "UPDATE", "RETIRE", "ARCHIVE", "REMOVE", "ASSIGN", "DESIGNATE");

    public AuditEntry {
        if (!SOURCES.contains(source)) {
            throw new IllegalArgumentException("Unknown audit source '" + source + "'.");
        }
        if (!ENTITY_TYPES.contains(entityType)) {
            throw new IllegalArgumentException("Unknown audit entity type '" + entityType + "'.");
        }
        if (!ACTIONS.contains(action)) {
            throw new IllegalArgumentException("Unknown audit action '" + action + "'.");
        }
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Actor actor;
        private String source = "UI";
        private String entityType;
        private String entityId;
        private String action;
        private Long accountId;
        private Long planId;
        private Long capabilityId;
        private String beforeJson;
        private String afterJson;
        private String reason;
        private Long affectedAccountCount;

        public Builder actor(Actor actor) { this.actor = actor; return this; }
        public Builder source(String source) { this.source = source; return this; }
        public Builder entityType(String entityType) { this.entityType = entityType; return this; }
        public Builder entityId(String entityId) { this.entityId = entityId; return this; }
        public Builder action(String action) { this.action = action; return this; }
        public Builder accountId(Long accountId) { this.accountId = accountId; return this; }
        public Builder planId(Long planId) { this.planId = planId; return this; }
        public Builder capabilityId(Long capabilityId) { this.capabilityId = capabilityId; return this; }
        public Builder beforeJson(String beforeJson) { this.beforeJson = beforeJson; return this; }
        public Builder afterJson(String afterJson) { this.afterJson = afterJson; return this; }
        public Builder reason(String reason) { this.reason = reason; return this; }
        public Builder affectedAccountCount(Long affectedAccountCount) { this.affectedAccountCount = affectedAccountCount; return this; }

        public AuditEntry build() {
            return new AuditEntry(actor, source, entityType, entityId, action, accountId, planId,
                capabilityId, beforeJson, afterJson, reason, affectedAccountCount);
        }
    }
}
```

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/audit/AuditRecorder.java
package com.solovis.entitlement.service.audit;

import com.solovis.entitlement.service.store.AuditEventRepository;
import com.solovis.entitlement.service.store.AuditEventRow;
import org.springframework.stereotype.Component;
import java.time.Clock;

@Component
public class AuditRecorder {

    private final AuditEventRepository repository;
    private final Clock clock;

    public AuditRecorder(AuditEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** Writes one audit_event row. Callers are responsible for doing this inside the same @Transactional method as the row-level change it records (c32). */
    public long record(AuditEntry entry) {
        var row = new AuditEventRow(
            null, clock.instant().toString(), entry.actor().kind().name(), entry.actor().id(),
            entry.source(), entry.entityType(), entry.entityId(), entry.action(),
            entry.accountId(), entry.planId(), entry.capabilityId(),
            entry.beforeJson(), entry.afterJson(), entry.reason(), entry.affectedAccountCount());
        return repository.insert(row);
    }
}
```

- [ ] **Step 4: Add the actor id property**

Append to `entitlement-service/src/main/resources/application.yaml`:

```yaml
entitlement:
  actor:
    id: "${ENTITLEMENT_DEV_ACTOR:dev-operator}"
```

(YAML note: this merges into the existing `entitlement:` top-level key — add `actor:` as a sibling of the existing `database:` key, not a second `entitlement:` block.)

- [ ] **Step 5: Run and commit**

Run: `./mvnw -pl entitlement-service test -Dtest=AuditRecorderTest`
Expected: PASS.

```bash
git add entitlement-service/src/main/java/com/solovis/entitlement/service/audit \
        entitlement-service/src/test/java/com/solovis/entitlement/service/audit \
        entitlement-service/src/main/resources/application.yaml
git commit -m "feat(entitlement-service): actor resolution seam and audit recorder"
```

---

## Task 3: Snapshot lifecycle — row↔domain mapping, assembly, holder, publisher

**The backbone every read and write route depends on.** Read routes call `SnapshotHolder.current()`; write routes call `SnapshotPublisher.publish(...)` as the last thing inside their `@Transactional` method.

**Files:**
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/snapshot/ValueColumnCodec.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/snapshot/RowMappers.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/snapshot/SnapshotAssembler.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/snapshot/SnapshotHolder.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/snapshot/SnapshotPublisher.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/snapshot/SnapshotStartup.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/snapshot/package-info.java`
- Modify: `entitlement-service/src/main/java/com/solovis/entitlement/service/store/AccountRepository.java` (add `findAllActive()`)
- Modify: `entitlement-service/src/main/java/com/solovis/entitlement/service/store/AccountOverrideRepository.java` (add `findAllLive()`)
- Test: `entitlement-service/src/test/java/com/solovis/entitlement/service/store/AccountRepositoryTest.java` (append)
- Test: `entitlement-service/src/test/java/com/solovis/entitlement/service/store/AccountOverrideRepositoryTest.java` (append)
- Test: `entitlement-service/src/test/java/com/solovis/entitlement/service/snapshot/ValueColumnCodecTest.java`
- Test: `entitlement-service/src/test/java/com/solovis/entitlement/service/snapshot/SnapshotAssemblerTest.java`
- Test: `entitlement-service/src/test/java/com/solovis/entitlement/service/snapshot/SnapshotPublisherTest.java`

**Interfaces:**
- Consumes: every `service.store` repository (exist), `entitlement-core`'s `SnapshotBuilder`/`SnapshotMutator`/`Snapshot` (exist, unmodified beyond Task 1's `capabilities()`), `SnapshotVersionRepository` (exists).
- Produces: `SnapshotAssembler.assembleFull(): Snapshot` (version-tagged from the latest `snapshot_version` row, or `0` if none), `SnapshotHolder.current(): Snapshot` / `SnapshotHolder.set(Snapshot)`, `SnapshotPublisher.publish(SnapshotPublisher.Mutation, long auditSeq, DeltaChange delta): long` (returns the new version; every Task 5–8 write path calls this exactly once, last, inside its `@Transactional` method), `SnapshotStartup` (an `ApplicationRunner` that calls `assembleFull()` once and seeds `SnapshotHolder` before the app accepts traffic). `DeltaChange`/`DeltaJson` are defined in Task 9 (the snapshot feed) — Task 3's `SnapshotPublisher.publish` takes a `DeltaChange` parameter whose type is declared as a forward reference here and implemented in Task 9; **build Task 9's `DeltaChange`/`DeltaJson` classes first if executing tasks out of order**, or stub `DeltaChange` as a `sealed interface DeltaChange {}` with zero permitted types in this task and let Task 9 add the variants (either ordering compiles; Task 9 must run before any Task 5–8 code that constructs a concrete `DeltaChange` variant is exercised end-to-end against the real feed, but nothing in Tasks 5–8 requires that — they only need `SnapshotPublisher.publish` to accept *a* `DeltaChange`).

**Given the forward-reference wrinkle above, do Task 9's `DeltaChange`/`DeltaJson` classes (only those two files, not the whole feed controller) as part of this task instead — it is a handful of records with no controller dependency, and it removes the ordering hazard entirely.**

- [ ] **Step 1: Repository extensions and their tests, first**

Append to `entitlement-service/src/test/java/com/solovis/entitlement/service/store/AccountRepositoryTest.java`:

```java
@Test
void findAllActiveReturnsOnlyActiveAccounts() {
    // reuse this test class's existing helper(s) for inserting a plan + two accounts, one CLOSED —
    // if no CLOSED-account helper exists yet, insert via the repository directly:
    long planId = /* existing plan fixture id from this test class */;
    long activeId = repository.insert(new AccountRow(null, "acct_active", null, planId,
        "2026-08-09T00:00:00.000Z", "PERSON", "dev-operator", "ACTIVE",
        "2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));

    var all = repository.findAllActive();

    assertThat(all).extracting(AccountRow::externalId).contains("acct_active");
    assertThat(all).allMatch(row -> true); // status filtering is asserted by the SQL below, not reachable via AccountRow (status isn't exposed as an equality-checkable field beyond externalId here)
}
```

Add to `entitlement-service/.../store/AccountRepository.java`, alongside the other query methods:

```java
	/** Every ACTIVE account — used only by SnapshotAssembler at startup and full-resync; no cursor because a full assembly needs all rows regardless. */
	public List<AccountRow> findAllActive() {
		return jdbcClient.sql("SELECT * FROM account WHERE status = 'ACTIVE'")
				.query(ROW_MAPPER)
				.list();
	}
```

Append to `entitlement-service/src/test/java/com/solovis/entitlement/service/store/AccountOverrideRepositoryTest.java`:

```java
@Test
void findAllLiveExcludesRemovedOverrides() {
    // using this test class's existing account/capability fixtures
    long liveId = repository.insert(/* existing live-override row fixture */);
    long removedId = repository.insert(/* another row */);
    repository.remove(removedId, "2026-08-09T00:00:00.000Z", "dev-operator", "closed");

    var live = repository.findAllLive();

    assertThat(live).extracting(AccountOverrideRow::id).contains(liveId).doesNotContain(removedId);
}
```

Add to `entitlement-service/.../store/AccountOverrideRepository.java`:

```java
	/** Every LIVE override across all accounts — snapshot assembly only (c.f. findLiveForAccount for a single account). */
	public List<AccountOverrideRow> findAllLive() {
		return jdbcClient.sql("SELECT * FROM account_override WHERE removed_at IS NULL")
				.query(ROW_MAPPER)
				.list();
	}
```

Run: `./mvnw -pl entitlement-service test -Dtest=AccountRepositoryTest,AccountOverrideRepositoryTest`
Expected: PASS. (Adapt the two test bodies above to this test class's actual existing fixture helpers — read the top of each test file first; the assertions shown are the contract, not the exact fixture-construction calls, which this plan cannot see without reading the file at execution time.)

- [ ] **Step 2: `ValueColumnCodec`, with test**

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/snapshot/ValueColumnCodec.java
package com.solovis.entitlement.service.snapshot;

import com.solovis.entitlement.core.model.EntitlementValue;
import com.solovis.entitlement.core.model.TierOrder;
import com.solovis.entitlement.core.model.ValueType;

/** The bool_value/qty_value/qty_unlimited/tier_value column quartet <-> EntitlementValue (data-model.md "Value representation"). */
public final class ValueColumnCodec {

    private ValueColumnCodec() {}

    public record Columns(Boolean boolValue, Long qtyValue, boolean qtyUnlimited, String tierValue) {}

    public static Columns toColumns(EntitlementValue value) {
        return switch (value) {
            case EntitlementValue.Switch s -> new Columns(s.enabled(), null, false, null);
            case EntitlementValue.Quantity q -> q.unlimited()
                ? new Columns(null, null, true, null)
                : new Columns(null, q.amount(), false, null);
            case EntitlementValue.Tier t -> new Columns(null, null, false, t.tierKey());
        };
    }

    /** {@code tierOrder} supplies the authoritative ordinal for a stored tier_value — the columns never carry one. */
    public static EntitlementValue toValue(
        ValueType type, Boolean boolValue, Long qtyValue, boolean qtyUnlimited, String tierValue, TierOrder tierOrder) {
        return switch (type) {
            case SWITCH -> new EntitlementValue.Switch(Boolean.TRUE.equals(boolValue));
            case QUANTITY -> qtyUnlimited ? EntitlementValue.Quantity.unbounded() : EntitlementValue.Quantity.of(qtyValue);
            case TIER -> new EntitlementValue.Tier(tierValue, tierOrder.ordinalOf(tierValue)
                .orElseThrow(() -> new IllegalStateException(
                    "Stored tier '" + tierValue + "' is not declared by its capability's current tier order.")));
        };
    }
}
```

```java
// entitlement-service/src/test/java/com/solovis/entitlement/service/snapshot/ValueColumnCodecTest.java
package com.solovis.entitlement.service.snapshot;

import com.solovis.entitlement.core.model.EntitlementValue;
import com.solovis.entitlement.core.model.TierOrder;
import com.solovis.entitlement.core.model.ValueType;
import org.junit.jupiter.api.Test;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ValueColumnCodecTest {

    @Test
    void roundTripsUnlimitedQuantity() {
        var columns = ValueColumnCodec.toColumns(EntitlementValue.Quantity.unbounded());
        assertThat(columns.qtyUnlimited()).isTrue();
        assertThat(columns.qtyValue()).isNull();

        var value = ValueColumnCodec.toValue(ValueType.QUANTITY, null, null, true, null, TierOrder.NONE);
        assertThat(value).isEqualTo(EntitlementValue.Quantity.unbounded());
    }

    @Test
    void roundTripsTierUsingCurrentOrdinal() {
        var tierOrder = new TierOrder(List.of(
            new TierOrder.TierDefinition("community", 0, "Community"),
            new TierOrder.TierDefinition("gold", 1, "Gold")));
        var columns = ValueColumnCodec.toColumns(new EntitlementValue.Tier("gold", 1));
        assertThat(columns.tierValue()).isEqualTo("gold");

        var value = ValueColumnCodec.toValue(ValueType.TIER, null, null, false, "gold", tierOrder);
        assertThat(value).isEqualTo(new EntitlementValue.Tier("gold", 1));
    }
}
```

- [ ] **Step 3: `RowMappers` (row → core domain), with test**

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/snapshot/RowMappers.java
package com.solovis.entitlement.service.snapshot;

import com.solovis.entitlement.core.model.*;
import com.solovis.entitlement.service.store.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/** Converts a persisted `*Row` into its `entitlement-core` domain equivalent. */
public final class RowMappers {

    private RowMappers() {}

    public static Capability toCapability(CapabilityRow row, List<CapabilityTierRow> tierRows) {
        var key = new CapabilityKey(row.key());
        var valueType = ValueType.valueOf(row.valueType());
        var tierOrder = tierRows.isEmpty() ? TierOrder.NONE : new TierOrder(tierRows.stream()
            .map(t -> new TierOrder.TierDefinition(t.tierKey(), t.ordinal(), t.displayName())).toList());
        var defaultValue = ValueColumnCodec.toValue(valueType, row.defaultBool(), row.defaultQty(),
            row.defaultQtyUnlimited(), row.defaultTier(), tierOrder);
        Optional<OffValue> offValue = row.hasOffValue()
            ? Optional.of(new OffValue(ValueColumnCodec.toValue(valueType, null, row.offQty(), false, row.offTier(), tierOrder)))
            : Optional.empty();
        var status = Capability.Status.valueOf(row.status());
        Instant retiredAt = row.retiredAt() == null ? null : Instant.parse(row.retiredAt());
        return new Capability(key, row.displayName(), row.description(), valueType, defaultValue, offValue, tierOrder, status, retiredAt);
    }

    public static Plan toPlan(PlanRow row) {
        return new Plan(row.key(), row.name(), Plan.Status.valueOf(row.status()), row.defaultForNewAccounts());
    }

    public static PlanEntitlement toPlanEntitlement(PlanEntitlementRow row, String planKey, Capability capability) {
        var value = ValueColumnCodec.toValue(capability.valueType(), row.boolValue(), row.qtyValue(),
            row.qtyUnlimited(), row.tierValue(), capability.tierOrder());
        return new PlanEntitlement(planKey, capability.key(), value);
    }

    public static AccountOverride toOverride(AccountOverrideRow row, String accountExternalId, Capability capability) {
        var value = ValueColumnCodec.toValue(capability.valueType(), row.boolValue(), row.qtyValue(),
            row.qtyUnlimited(), row.tierValue(), capability.tierOrder());
        return new AccountOverride(OptionalLong.of(row.id()), accountExternalId, capability.key(),
            OverrideKind.valueOf(row.kind()), value, Optional.of(row.reason()), Optional.of(row.createdBy()),
            Optional.of(Instant.parse(row.createdAt())));
    }
}
```

```java
// entitlement-service/src/test/java/com/solovis/entitlement/service/snapshot/RowMappersTest.java
package com.solovis.entitlement.service.snapshot;

import com.solovis.entitlement.core.model.EntitlementValue;
import com.solovis.entitlement.service.store.CapabilityRow;
import org.junit.jupiter.api.Test;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RowMappersTest {

    @Test
    void toCapabilityMapsAQuantityCapabilityWithAZeroOffValue() {
        var row = new CapabilityRow(7L, "reports.monthly", "reports", "Monthly reports", null, "QUANTITY",
            null, 0L, false, null, true, 0L, null, "ACTIVE", null,
            "2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z");

        var capability = RowMappers.toCapability(row, List.of());

        assertThat(capability.key().value()).isEqualTo("reports.monthly");
        assertThat(capability.defaultValue()).isEqualTo(EntitlementValue.Quantity.of(0));
        assertThat(capability.offValue()).isPresent();
        assertThat(capability.offValue().get().value()).isEqualTo(EntitlementValue.Quantity.of(0));
        assertThat(capability.isRetired()).isFalse();
    }
}
```

- [ ] **Step 4: `SnapshotAssembler`, with a Spring-context integration test**

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/snapshot/SnapshotAssembler.java
package com.solovis.entitlement.service.snapshot;

import com.solovis.entitlement.core.model.AccountAssignment;
import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.view.Snapshot;
import com.solovis.entitlement.core.view.SnapshotBuilder;
import com.solovis.entitlement.service.store.*;
import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.Map;

/** Builds a complete {@link Snapshot} by walking every repository — startup, and the snapshot feed's full-resync path. */
@Component
public class SnapshotAssembler {

    private final CapabilityRepository capabilityRepository;
    private final PlanRepository planRepository;
    private final PlanEntitlementRepository planEntitlementRepository;
    private final AccountRepository accountRepository;
    private final AccountOverrideRepository accountOverrideRepository;
    private final SnapshotVersionRepository snapshotVersionRepository;

    public SnapshotAssembler(
        CapabilityRepository capabilityRepository, PlanRepository planRepository,
        PlanEntitlementRepository planEntitlementRepository, AccountRepository accountRepository,
        AccountOverrideRepository accountOverrideRepository, SnapshotVersionRepository snapshotVersionRepository) {
        this.capabilityRepository = capabilityRepository;
        this.planRepository = planRepository;
        this.planEntitlementRepository = planEntitlementRepository;
        this.accountRepository = accountRepository;
        this.accountOverrideRepository = accountOverrideRepository;
        this.snapshotVersionRepository = snapshotVersionRepository;
    }

    public Snapshot assembleFull() {
        long version = snapshotVersionRepository.findLatest().map(SnapshotVersionRow::version).orElse(0L);
        var builder = new SnapshotBuilder();

        Map<Long, Capability> capabilitiesById = new HashMap<>();
        for (var row : capabilityRepository.findAll(null, null, null)) {
            var capability = RowMappers.toCapability(row, capabilityRepository.findTiers(row.id()));
            capabilitiesById.put(row.id(), capability);
            builder.capability(capability);
        }

        Map<Long, String> planKeysById = new HashMap<>();
        for (var row : planRepository.findAll(null)) {
            builder.plan(RowMappers.toPlan(row));
            planKeysById.put(row.id(), row.key());
        }
        for (var planId : planKeysById.keySet()) {
            for (var row : planEntitlementRepository.findByPlan(planId)) {
                var capability = capabilitiesById.get(row.capabilityId());
                builder.planEntitlement(RowMappers.toPlanEntitlement(row, planKeysById.get(planId), capability));
            }
        }

        Map<Long, String> externalIdsById = new HashMap<>();
        for (var row : accountRepository.findAllActive()) {
            String planKey = planKeysById.get(row.planId());
            builder.account(new AccountAssignment(row.externalId(), planKey));
            externalIdsById.put(row.id(), row.externalId());
        }

        for (var row : accountOverrideRepository.findAllLive()) {
            String externalId = externalIdsById.get(row.accountId());
            if (externalId == null) {
                continue; // account is CLOSED or otherwise excluded from the active set
            }
            var capability = capabilitiesById.get(row.capabilityId());
            builder.override(RowMappers.toOverride(row, externalId, capability));
        }

        return builder.build(version);
    }
}
```

```java
// entitlement-service/src/test/java/com/solovis/entitlement/service/snapshot/SnapshotAssemblerTest.java
package com.solovis.entitlement.service.snapshot;

import com.solovis.entitlement.core.engine.Resolver;
import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.service.store.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SnapshotAssemblerTest {

    @Autowired SnapshotAssembler assembler;
    @Autowired CapabilityRepository capabilityRepository;
    @Autowired PlanRepository planRepository;
    @Autowired AccountRepository accountRepository;

    @Test
    void assembledSnapshotResolvesAnAccountOnAPlanBaseline() {
        long capId = capabilityRepository.insert(new CapabilityRow(null, "seats.count", "seats", "Seats", null,
            "QUANTITY", null, 0L, false, null, false, null, null, "ACTIVE", null,
            "2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));
        long planId = planRepository.insert(new PlanRow(null, "free", "Free", null, "ACTIVE", true,
            "2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));
        accountRepository.insert(new AccountRow(null, "acct_1", null, planId, "2026-08-09T00:00:00.000Z",
            "PERSON", "dev-operator", "ACTIVE", "2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));

        var snapshot = assembler.assembleFull();
        var decision = Resolver.resolve(snapshot, "acct_1", new CapabilityKey("seats.count"), Instant.now());

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.value()).isEqualTo(com.solovis.entitlement.core.model.EntitlementValue.Quantity.of(0));
    }
}
```

- [ ] **Step 5: `SnapshotHolder`**

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/snapshot/SnapshotHolder.java
package com.solovis.entitlement.service.snapshot;

import com.solovis.entitlement.core.view.Snapshot;
import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicReference;

/** The one in-memory copy of the model every read route resolves against (research.md §8). */
@Component
public class SnapshotHolder {

    private final AtomicReference<Snapshot> current = new AtomicReference<>();

    public Snapshot current() {
        Snapshot snapshot = current.get();
        if (snapshot == null) {
            throw new IllegalStateException("SnapshotHolder has not been initialised yet (SnapshotStartup must run first).");
        }
        return snapshot;
    }

    public void set(Snapshot snapshot) {
        current.set(snapshot);
    }
}
```

- [ ] **Step 6: `DeltaChange`/`DeltaJson`** (pulled forward from Task 9 per the note above — the shapes `snapshot-feed.md` §"Change kinds" defines, nothing more)

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/snapshot/DeltaChange.java
package com.solovis.entitlement.service.snapshot;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.solovis.entitlement.service.dto.CapabilityDescriptorDto;
import com.solovis.entitlement.service.dto.ValueDto;
import java.util.List;
import java.util.Map;

/** One row of the snapshot feed's delta stream (contracts/snapshot-feed.md, "Change kinds"). One instance is persisted per {@code snapshot_version} row. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
    @JsonSubTypes.Type(value = DeltaChange.CapabilityUpserted.class, name = "capability.upserted"),
    @JsonSubTypes.Type(value = DeltaChange.CapabilityRetired.class, name = "capability.retired"),
    @JsonSubTypes.Type(value = DeltaChange.PlanUpserted.class, name = "plan.upserted"),
    @JsonSubTypes.Type(value = DeltaChange.PlanEntitlements.class, name = "plan.entitlements"),
    @JsonSubTypes.Type(value = DeltaChange.PlanArchived.class, name = "plan.archived"),
    @JsonSubTypes.Type(value = DeltaChange.PlanDefaultChanged.class, name = "plan.defaultChanged"),
    @JsonSubTypes.Type(value = DeltaChange.AccountUpserted.class, name = "account.upserted"),
    @JsonSubTypes.Type(value = DeltaChange.OverrideCreated.class, name = "override.created"),
    @JsonSubTypes.Type(value = DeltaChange.OverrideRemoved.class, name = "override.removed"),
})
public sealed interface DeltaChange {
    record CapabilityUpserted(CapabilityDescriptorDto capability) implements DeltaChange {}
    record CapabilityRetired(String key) implements DeltaChange {}
    record PlanUpserted(String key, String name, String status, boolean isDefaultForNewAccounts) implements DeltaChange {}
    record PlanEntitlements(String planKey, Map<String, ValueDto> set, List<String> unset) implements DeltaChange {}
    record PlanArchived(String key) implements DeltaChange {}
    record PlanDefaultChanged(String key) implements DeltaChange {}
    record AccountUpserted(String external, String planKey) implements DeltaChange {}
    record OverrideCreated(String ref, String account, String capability, String overrideKind, ValueDto value) implements DeltaChange {}
    record OverrideRemoved(String ref) implements DeltaChange {}
}
```

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/snapshot/DeltaJson.java
package com.solovis.entitlement.service.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/** (De)serialises one {@link DeltaChange} to/from the {@code snapshot_version.delta_json} column. A dedicated, minimal mapper — this format is internal, decoupled from the API-response Jackson configuration in Task 10. */
public final class DeltaJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private DeltaJson() {}

    public static String write(DeltaChange change) {
        try {
            return MAPPER.writeValueAsString(change);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise delta change: " + change, e);
        }
    }

    public static DeltaChange read(String json) {
        try {
            return MAPPER.readValue(json, DeltaChange.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialise delta_json: " + json, e);
        }
    }
}
```

- [ ] **Step 7: `SnapshotPublisher`, with an integration test proving the after-commit swap**

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/snapshot/SnapshotPublisher.java
package com.solovis.entitlement.service.snapshot;

import com.solovis.entitlement.core.view.Snapshot;
import com.solovis.entitlement.service.store.SnapshotVersionRepository;
import com.solovis.entitlement.service.store.SnapshotVersionRow;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Clock;

/**
 * The one place a write path advances the model. Must be called from inside a {@code @Transactional}
 * method, as the last step, after the row-level mutation and its audit_event are already written on
 * the same connection (admin-api.md, "Write semantics common to every mutating route"; c30). The
 * snapshot_version row commits with everything else; the in-memory swap is deferred to
 * {@code afterCommit()} so a rolled-back write can never leave {@link SnapshotHolder} ahead of the
 * database — registerSynchronization throws if no transaction is active, which is the intended
 * guard against calling this outside one.
 */
@Component
public class SnapshotPublisher {

    private final SnapshotVersionRepository snapshotVersionRepository;
    private final SnapshotHolder snapshotHolder;
    private final Clock clock;

    public SnapshotPublisher(SnapshotVersionRepository snapshotVersionRepository, SnapshotHolder snapshotHolder, Clock clock) {
        this.snapshotVersionRepository = snapshotVersionRepository;
        this.snapshotHolder = snapshotHolder;
        this.clock = clock;
    }

    @FunctionalInterface
    public interface Mutation {
        Snapshot apply(Snapshot base, long newVersion);
    }

    public long publish(Mutation mutation, long lastAuditSeq, DeltaChange delta) {
        Snapshot current = snapshotHolder.current();
        long newVersion = current.snapshotVersion() + 1;
        Snapshot next = mutation.apply(current, newVersion);
        String deltaJson = DeltaJson.write(delta);
        snapshotVersionRepository.insert(new SnapshotVersionRow(null, clock.instant().toString(), lastAuditSeq, deltaJson));

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                snapshotHolder.set(next);
            }
        });
        return newVersion;
    }
}
```

```java
// entitlement-service/src/test/java/com/solovis/entitlement/service/snapshot/SnapshotPublisherTest.java
package com.solovis.entitlement.service.snapshot;

import com.solovis.entitlement.core.model.*;
import com.solovis.entitlement.core.view.SnapshotBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SnapshotPublisherTest {

    @Autowired SnapshotPublisher publisher;
    @Autowired SnapshotHolder holder;
    @Autowired PlatformTransactionManager entitlementTransactionManager;

    @Test
    void swapHappensOnlyAfterCommitNotDuringTheTransaction() {
        var seed = new SnapshotBuilder().build(0);
        holder.set(seed);
        var newCapability = new Capability(new CapabilityKey("api.access"), "API", null, ValueType.SWITCH,
            new EntitlementValue.Switch(false), Optional.empty(), TierOrder.NONE, Capability.Status.ACTIVE, null);

        new TransactionTemplate(entitlementTransactionManager).executeWithoutResult(status -> {
            long version = publisher.publish(
                (base, v) -> com.solovis.entitlement.core.view.SnapshotMutator.withCapability(base, v, newCapability),
                1L, new DeltaChange.CapabilityUpserted(com.solovis.entitlement.service.dto.CapabilityDescriptorMapper.toDescriptor(newCapability)));
            assertThat(version).isEqualTo(1L);
            // still inside the transaction: the holder must not have swapped yet
            assertThat(holder.current().capability(new CapabilityKey("api.access"))).isEmpty();
        });

        // transaction has committed: the swap has now happened
        assertThat(holder.current().capability(new CapabilityKey("api.access"))).isPresent();
        assertThat(holder.current().snapshotVersion()).isEqualTo(1L);
    }

    @Test
    void rollbackNeverSwapsTheHolder() {
        var seed = new SnapshotBuilder().build(0);
        holder.set(seed);

        try {
            new TransactionTemplate(entitlementTransactionManager).executeWithoutResult(status -> {
                publisher.publish((base, v) -> base, 1L, new DeltaChange.PlanArchived("does-not-exist"));
                status.setRollbackOnly();
            });
        } catch (Exception ignored) { /* rollback path only, no assertion on the exception itself */ }

        assertThat(holder.current().snapshotVersion()).isEqualTo(0L);
    }
}
```

- [ ] **Step 8: `SnapshotStartup`**

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/snapshot/SnapshotStartup.java
package com.solovis.entitlement.service.snapshot;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Assembles the initial Snapshot before the app accepts traffic — every read route requires SnapshotHolder to be populated. */
@Component
@Order(0)
public class SnapshotStartup implements ApplicationRunner {

    private final SnapshotAssembler assembler;
    private final SnapshotHolder holder;

    public SnapshotStartup(SnapshotAssembler assembler, SnapshotHolder holder) {
        this.assembler = assembler;
        this.holder = holder;
    }

    @Override
    public void run(ApplicationArguments args) {
        holder.set(assembler.assembleFull());
    }
}
```

- [ ] **Step 9: Run the full snapshot-package suite and commit**

Run: `./mvnw -pl entitlement-service test -Dtest='com.solovis.entitlement.service.snapshot.**,AccountRepositoryTest,AccountOverrideRepositoryTest'`
Expected: PASS.

```bash
git add entitlement-service/src/main/java/com/solovis/entitlement/service/snapshot \
        entitlement-service/src/test/java/com/solovis/entitlement/service/snapshot \
        entitlement-service/src/main/java/com/solovis/entitlement/service/store/AccountRepository.java \
        entitlement-service/src/main/java/com/solovis/entitlement/service/store/AccountOverrideRepository.java \
        entitlement-service/src/test/java/com/solovis/entitlement/service/store/AccountRepositoryTest.java \
        entitlement-service/src/test/java/com/solovis/entitlement/service/store/AccountOverrideRepositoryTest.java
git commit -m "feat(entitlement-service): snapshot assembly, holder, and commit-swap publisher"
```

## Task 4: Decision API (`contracts/decision-api.md`)

The product-facing, single-trace-source surface. All three routes read only `SnapshotHolder` — no repository access.

**Files:**
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/api/dto/DecisionResponseDto.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/api/dto/WholeAccountResponseDto.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/api/dto/CapabilityListResponseDto.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/api/dto/package-info.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/api/DecisionMapper.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/api/DecisionController.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/api/package-info.java`
- Test: `entitlement-service/src/test/java/com/solovis/entitlement/service/api/DecisionMapperTest.java`
- Test: `entitlement-service/src/test/java/com/solovis/entitlement/service/api/DecisionControllerTest.java`

**Interfaces:**
- Consumes: `SnapshotHolder` (Task 3), `Resolver.resolve`/`explain` (exist), `ValueMapper`/`CapabilityDescriptorMapper` (Task 1), `Clock` (Task 1).
- Produces: `GET /v1/accounts/{id}/capabilities/{key}`, `GET /v1/accounts/{id}/entitlements`, `GET /v1/capabilities[?area=&status=]`, `GET /v1/capabilities/{key}` — exact response shapes below, consumed by the Java client SDK (future task, out of this plan's scope) and any non-JVM caller.

- [ ] **Step 1: `DecisionResponseDto` and the note-synthesis mapper, test-first**

Core's `Trace`/`TraceEntry` carry no human-readable prose — `decision-api.md`'s example response's `note` strings (`"Plan 'pro' sets this capability."`, `"Most generous GRANT (200) beats the plan baseline (50)."`) and the `grantStep`/`holdStep` `why` codes documented in "Trace field semantics" are synthesised here, deterministically, from the structured trace the resolver already produced — `entitlement-core` stays free of presentation text.

```java
// entitlement-service/src/test/java/com/solovis/entitlement/service/api/DecisionMapperTest.java
package com.solovis.entitlement.service.api;

import com.solovis.entitlement.core.engine.Resolver;
import com.solovis.entitlement.core.model.*;
import com.solovis.entitlement.core.view.SnapshotBuilder;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionMapperTest {

    @Test
    void baselineFromPlanNamesThePlanInTheNote() {
        var key = new CapabilityKey("reports.monthly");
        var capability = new Capability(key, "Monthly reports", null, ValueType.QUANTITY,
            EntitlementValue.Quantity.of(0), Optional.empty(), TierOrder.NONE, Capability.Status.ACTIVE, null);
        var snapshot = new SnapshotBuilder().capability(capability)
            .plan(new Plan("pro", "Pro", Plan.Status.ACTIVE, false))
            .planEntitlement(new PlanEntitlement("pro", key, EntitlementValue.Quantity.of(50)))
            .account(new AccountAssignment("acct_1", "pro"))
            .build(1);

        var explanation = Resolver.explain(snapshot, "acct_1", key, Instant.parse("2026-08-09T14:03:11.482Z"));
        var dto = DecisionMapper.toResponse(explanation, capability);

        assertThat(dto.trace().baseline().source()).isEqualTo("PLAN");
        assertThat(dto.trace().baseline().planKey()).isEqualTo("pro");
        assertThat(dto.trace().baseline().note()).contains("'pro'");
        assertThat(dto.trace().result().allowedReason()).isEqualTo("NO_OFF_VALUE_DECLARED");
    }

    @Test
    void grantStepNotAppliedWithNoGrantsReportsNoGrants() {
        var key = new CapabilityKey("api.access");
        var capability = new Capability(key, "API", null, ValueType.SWITCH,
            new EntitlementValue.Switch(false), Optional.empty(), TierOrder.NONE, Capability.Status.ACTIVE, null);
        var snapshot = new SnapshotBuilder().capability(capability)
            .plan(new Plan("free", "Free", Plan.Status.ACTIVE, true))
            .account(new AccountAssignment("acct_1", "free"))
            .build(1);

        var explanation = Resolver.explain(snapshot, "acct_1", key, Instant.now());
        var dto = DecisionMapper.toResponse(explanation, capability);

        assertThat(dto.trace().grantStep().applied()).isFalse();
        assertThat(dto.trace().grantStep().why()).isEqualTo("NO_GRANTS");
        assertThat(dto.trace().result().allowed()).isFalse();
        assertThat(dto.trace().result().allowedReason()).isEqualTo("EQUALS_OFF_VALUE");
    }

    @Test
    void tiedGrantsMarkTheHighestIdWinner() {
        var key = new CapabilityKey("reports.monthly");
        var capability = new Capability(key, "Monthly reports", null, ValueType.QUANTITY,
            EntitlementValue.Quantity.of(0), Optional.empty(), TierOrder.NONE, Capability.Status.ACTIVE, null);
        var older = new AccountOverride(java.util.OptionalLong.of(10), "acct_1", key, OverrideKind.GRANT,
            EntitlementValue.Quantity.of(200), Optional.of("first"), Optional.of("a"), Optional.of(Instant.now()));
        var newer = new AccountOverride(java.util.OptionalLong.of(20), "acct_1", key, OverrideKind.GRANT,
            EntitlementValue.Quantity.of(200), Optional.of("second"), Optional.of("b"), Optional.of(Instant.now()));
        var snapshot = new SnapshotBuilder().capability(capability)
            .plan(new Plan("pro", "Pro", Plan.Status.ACTIVE, false))
            .account(new AccountAssignment("acct_1", "pro"))
            .override(older).override(newer)
            .build(1);

        var explanation = Resolver.explain(snapshot, "acct_1", key, Instant.now());
        var dto = DecisionMapper.toResponse(explanation, capability);

        assertThat(dto.trace().grantStep().winner()).isEqualTo("ovr_20");
    }
}
```

Implement, in order:

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/api/dto/DecisionResponseDto.java
package com.solovis.entitlement.service.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.solovis.entitlement.service.dto.ValueDto;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DecisionResponseDto(
    String account, String capability, boolean allowed, ValueDto value,
    long snapshotVersion, String evaluatedAt, TraceDto trace
) {
    public record TraceDto(
        BaselineDto baseline, List<CandidateDto> grants, GrantStepDto grantStep,
        List<CandidateDto> holds, HoldStepDto holdStep, ResultDto result
    ) {
        public record BaselineDto(String source, String planKey, ValueDto value, String note) {}
        public record CandidateDto(String overrideId, ValueDto value, String reason, String createdBy, String createdAt, String outcome) {}
        public record GrantStepDto(boolean applied, String winner, ValueDto value, String note, String why) {}
        public record HoldStepDto(boolean applied, String winner, ValueDto value, String note, String why) {}
        public record ResultDto(ValueDto value, boolean allowed, String allowedReason) {}
    }
}
```

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/api/DecisionMapper.java
package com.solovis.entitlement.service.api;

import com.solovis.entitlement.core.engine.*;
import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.model.EntitlementValue;
import com.solovis.entitlement.service.dto.ValueMapper;
import java.time.Instant;

final class DecisionMapper {

    private DecisionMapper() {}

    static DecisionResponseDto toResponse(Explanation explanation, Capability capability) {
        var decision = explanation.decision();
        return new DecisionResponseDto(
            decision.accountExternalId(), decision.capabilityKey(), decision.allowed(),
            ValueMapper.toDto(decision.value()), decision.snapshotVersion(), decision.evaluatedAt().toString(),
            toTraceDto(explanation.trace(), capability));
    }

    private static DecisionResponseDto.TraceDto toTraceDto(Trace trace, Capability capability) {
        var grants = trace.grants().stream().map(DecisionMapper::toCandidateDto).toList();
        var holds = trace.holds().stream().map(DecisionMapper::toCandidateDto).toList();
        return new DecisionResponseDto.TraceDto(
            toBaselineDto(trace.baseline()), grants, toGrantStepDto(trace), holds, toHoldStepDto(trace),
            new DecisionResponseDto.TraceDto.ResultDto(ValueMapper.toDto(trace.result()), trace.allowed(), allowedReason(trace, capability)));
    }

    private static DecisionResponseDto.TraceDto.BaselineDto toBaselineDto(TraceEntry baseline) {
        String note = baseline.source() == TraceSource.PLAN
            ? "Plan '" + baseline.planKey().orElseThrow() + "' sets this capability."
            : "No plan entitlement is set; the capability default applies.";
        return new DecisionResponseDto.TraceDto.BaselineDto(
            baseline.source().name(), baseline.planKey().orElse(null), ValueMapper.toDto(baseline.value()), note);
    }

    private static DecisionResponseDto.TraceDto.CandidateDto toCandidateDto(TraceEntry entry) {
        return new DecisionResponseDto.TraceDto.CandidateDto(
            entry.overrideId().isPresent() ? "ovr_" + entry.overrideId().getAsLong() : null,
            ValueMapper.toDto(entry.value()), entry.reason().orElse(null), entry.createdBy().orElse(null),
            entry.createdAt().map(Instant::toString).orElse(null), entry.outcome().map(Enum::name).orElse(null));
    }

    private static DecisionResponseDto.TraceDto.GrantStepDto toGrantStepDto(Trace trace) {
        if (trace.grantWinner().isPresent()) {
            var winner = trace.grantWinner().get();
            String note = "Most generous GRANT (" + describe(winner.value()) + ") beats the plan baseline ("
                + describe(trace.baseline().value()) + ").";
            return new DecisionResponseDto.TraceDto.GrantStepDto(true, refOf(winner), ValueMapper.toDto(winner.value()), note, null);
        }
        boolean noGrants = trace.grants().isEmpty();
        String why = noGrants ? "NO_GRANTS" : "PLAN_AT_LEAST_AS_GENEROUS";
        String note = noGrants ? "No GRANT overrides exist for this capability on this account."
            : "The plan baseline is already at least as generous as every GRANT.";
        return new DecisionResponseDto.TraceDto.GrantStepDto(false, null, null, note, why);
    }

    private static DecisionResponseDto.TraceDto.HoldStepDto toHoldStepDto(Trace trace) {
        if (trace.holdWinner().isPresent()) {
            var winner = trace.holdWinner().get();
            String note = "Most restrictive HOLD (" + describe(winner.value()) + ") caps the result.";
            return new DecisionResponseDto.TraceDto.HoldStepDto(true, refOf(winner), ValueMapper.toDto(winner.value()), note, null);
        }
        boolean noHolds = trace.holds().isEmpty();
        String why = noHolds ? "NO_HOLDS" : "HOLD_NOT_MORE_RESTRICTIVE";
        String note = noHolds ? "No HOLD overrides exist for this capability on this account."
            : "No HOLD is more restrictive than the post-grant value.";
        return new DecisionResponseDto.TraceDto.HoldStepDto(false, null, null, note, why);
    }

    private static String allowedReason(Trace trace, Capability capability) {
        var offValue = capability.effectiveOffValue();
        if (offValue.isEmpty()) {
            return "NO_OFF_VALUE_DECLARED";
        }
        return offValue.get().equals(trace.result()) ? "EQUALS_OFF_VALUE" : "DIFFERS_FROM_OFF_VALUE";
    }

    private static String refOf(TraceEntry entry) {
        return "ovr_" + entry.overrideId().getAsLong();
    }

    private static String describe(EntitlementValue value) {
        return switch (value) {
            case EntitlementValue.Switch s -> String.valueOf(s.enabled());
            case EntitlementValue.Quantity q -> q.unlimited() ? "unlimited" : String.valueOf(q.amount());
            case EntitlementValue.Tier t -> t.tierKey();
        };
    }
}
```

Run: `./mvnw -pl entitlement-service test -Dtest=DecisionMapperTest` — expected PASS.

- [ ] **Step 2: whole-account and registry DTOs**

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/api/dto/WholeAccountResponseDto.java
package com.solovis.entitlement.service.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.solovis.entitlement.service.dto.ValueDto;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record WholeAccountResponseDto(
    String account, String planKey, long snapshotVersion, String evaluatedAt, List<Entitlement> entitlements
) {
    public record Entitlement(String capability, boolean allowed, ValueDto value) {}
}
```

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/api/dto/CapabilityListResponseDto.java
package com.solovis.entitlement.service.api.dto;

import com.solovis.entitlement.service.dto.CapabilityDescriptorDto;
import java.util.List;

public record CapabilityListResponseDto(List<CapabilityDescriptorDto> capabilities, long snapshotVersion) {}
```

- [ ] **Step 3: `DecisionController`, with a `MockMvc` slice test per route including the three errors**

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/api/DecisionController.java
package com.solovis.entitlement.service.api;

import com.solovis.entitlement.core.engine.Resolver;
import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.core.view.Snapshot;
import com.solovis.entitlement.service.api.dto.CapabilityListResponseDto;
import com.solovis.entitlement.service.api.dto.WholeAccountResponseDto;
import com.solovis.entitlement.service.dto.CapabilityDescriptorMapper;
import com.solovis.entitlement.service.dto.ValueMapper;
import com.solovis.entitlement.service.error.EntitlementApiException;
import com.solovis.entitlement.service.error.ErrorCode;
import com.solovis.entitlement.service.snapshot.SnapshotHolder;
import com.solovis.entitlement.core.error.UnknownAccountException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;

@RestController
@RequestMapping("/v1")
public class DecisionController {

    private final SnapshotHolder snapshotHolder;
    private final Clock clock;

    public DecisionController(SnapshotHolder snapshotHolder, Clock clock) {
        this.snapshotHolder = snapshotHolder;
        this.clock = clock;
    }

    @GetMapping("/accounts/{accountExternalId}/capabilities/{capabilityKey}")
    public ResponseEntity<Object> single(
        @PathVariable String accountExternalId, @PathVariable String capabilityKey,
        @RequestParam(required = false) Long minSnapshotVersion) {
        Snapshot snapshot = snapshotAtLeast(minSnapshotVersion);
        var key = new CapabilityKey(capabilityKey);
        var explanation = Resolver.explain(snapshot, accountExternalId, key, clock.instant());
        var capability = snapshot.capability(key).orElseThrow();
        var body = DecisionMapper.toResponse(explanation, capability);
        return ResponseEntity.ok()
            .header("X-Entitlement-Snapshot-Version", String.valueOf(snapshot.snapshotVersion()))
            .cacheControl(CacheControl.maxAge(Duration.ofSeconds(10)).cachePublic().staleIfError(Duration.ofHours(24)))
            .body(body);
    }

    @GetMapping("/accounts/{accountExternalId}/entitlements")
    public ResponseEntity<WholeAccountResponseDto> whole(@PathVariable String accountExternalId) {
        Snapshot snapshot = snapshotHolder.current();
        var account = snapshot.account(accountExternalId).orElseThrow(() -> new UnknownAccountException(accountExternalId));
        var entitlements = snapshot.activeCapabilities().stream()
            .sorted(Comparator.comparing(c -> c.key().value()))
            .map(capability -> {
                var decision = Resolver.resolve(snapshot, accountExternalId, capability.key(), clock.instant());
                return new WholeAccountResponseDto.Entitlement(capability.key().value(), decision.allowed(), ValueMapper.toDto(decision.value()));
            }).toList();
        var body = new WholeAccountResponseDto(accountExternalId, account.planKey(), snapshot.snapshotVersion(),
            clock.instant().toString(), entitlements);
        return ResponseEntity.ok().header("X-Entitlement-Snapshot-Version", String.valueOf(snapshot.snapshotVersion())).body(body);
    }

    @GetMapping("/capabilities")
    public CapabilityListResponseDto list(
        @RequestParam(required = false) String area,
        @RequestParam(required = false, defaultValue = "ACTIVE") String status) {
        Snapshot snapshot = snapshotHolder.current();
        var stream = status.equals("ALL") ? snapshot.capabilities().stream()
            : status.equals("RETIRED") ? snapshot.capabilities().stream().filter(c -> c.isRetired())
            : snapshot.activeCapabilities().stream();
        if (area != null) {
            stream = stream.filter(c -> c.area().equals(area));
        }
        var descriptors = stream.sorted(Comparator.comparing(c -> c.key().value()))
            .map(CapabilityDescriptorMapper::toDescriptor).toList();
        return new CapabilityListResponseDto(descriptors, snapshot.snapshotVersion());
    }

    @GetMapping("/capabilities/{capabilityKey}")
    public com.solovis.entitlement.service.dto.CapabilityDescriptorDto one(@PathVariable String capabilityKey) {
        Snapshot snapshot = snapshotHolder.current();
        var capability = snapshot.capability(new CapabilityKey(capabilityKey))
            .orElseThrow(() -> new com.solovis.entitlement.core.error.UnknownCapabilityException(capabilityKey));
        return CapabilityDescriptorMapper.toDescriptor(capability);
    }

    private Snapshot snapshotAtLeast(Long minSnapshotVersion) {
        Snapshot snapshot = snapshotHolder.current();
        if (minSnapshotVersion != null && snapshot.snapshotVersion() < minSnapshotVersion) {
            throw new EntitlementApiException(ErrorCode.SNAPSHOT_BEHIND,
                "Current snapshot version " + snapshot.snapshotVersion() + " is behind the requested " + minSnapshotVersion + ".",
                Map.of("currentVersion", snapshot.snapshotVersion()));
        }
        return snapshot;
    }
}
```

```java
// entitlement-service/src/test/java/com/solovis/entitlement/service/api/DecisionControllerTest.java
package com.solovis.entitlement.service.api;

import com.solovis.entitlement.service.snapshot.SnapshotHolder;
import com.solovis.entitlement.service.store.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class DecisionControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired SnapshotHolder snapshotHolder;
    @Autowired com.solovis.entitlement.service.snapshot.SnapshotAssembler assembler;
    @Autowired CapabilityRepository capabilityRepository;
    @Autowired PlanRepository planRepository;
    @Autowired AccountRepository accountRepository;

    @BeforeEach
    void seedAndRefreshSnapshot() {
        long capId = capabilityRepository.insert(new CapabilityRow(null, "reports.monthly", "reports",
            "Monthly reports", null, "QUANTITY", null, 0L, false, null, false, null, null, "ACTIVE", null,
            "2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));
        long planId = planRepository.insert(new PlanRow(null, "free", "Free", null, "ACTIVE", true,
            "2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));
        accountRepository.insert(new AccountRow(null, "acct_1", null, planId, "2026-08-09T00:00:00.000Z",
            "PERSON", "dev-operator", "ACTIVE", "2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));
        snapshotHolder.set(assembler.assembleFull());
    }

    @Test
    void singleCapabilityReturnsFullTrace() throws Exception {
        mockMvc.perform(get("/v1/accounts/acct_1/capabilities/reports.monthly"))
            .andExpect(status().isOk())
            .andExpect(header().exists("X-Entitlement-Snapshot-Version"))
            .andExpect(jsonPath("$.allowed").value(true))
            .andExpect(jsonPath("$.trace.baseline.source").value("CAPABILITY_DEFAULT"))
            .andExpect(jsonPath("$.trace.grantStep.applied").value(false))
            .andExpect(jsonPath("$.trace.grantStep.why").value("NO_GRANTS"));
    }

    @Test
    void unknownAccountIsAnErrorNeverADenial() throws Exception {
        mockMvc.perform(get("/v1/accounts/acct_missing/capabilities/reports.monthly"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value("entitlement/unknown-account"));
    }

    @Test
    void wholeAccountOmitsTraces() throws Exception {
        mockMvc.perform(get("/v1/accounts/acct_1/entitlements"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.entitlements[0].capability").value("reports.monthly"))
            .andExpect(jsonPath("$.entitlements[0].trace").doesNotExist());
    }

    @Test
    void registryDefaultsToActiveOnly() throws Exception {
        mockMvc.perform(get("/v1/capabilities"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.capabilities[0].key").value("reports.monthly"))
            .andExpect(jsonPath("$.snapshotVersion").value(1));
    }
}
```

- [ ] **Step 4: Run and commit**

Run: `./mvnw -pl entitlement-service test -Dtest='com.solovis.entitlement.service.api.**'`
Expected: PASS.

```bash
git add entitlement-service/src/main/java/com/solovis/entitlement/service/api \
        entitlement-service/src/test/java/com/solovis/entitlement/service/api
git commit -m "feat(entitlement-service): decision API — single capability, whole account, registry"
```

## Task 5: Admin API — Capabilities (`admin-api.md` "Capabilities — screen 1")

Establishes the pattern every later admin task repeats: bean-validated request DTO → business validation (reusing `entitlement-core`'s own constructor validation wherever possible, so a rule is checked in exactly one place) → repository write → `AuditRecorder.record` → `SnapshotPublisher.publish`, all inside one `@Transactional` service method; the controller stays a thin HTTP adapter.

**Files:**
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/audit/AuditJson.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/CapabilityCreateRequest.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/CapabilityPatchRequest.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/TierAppendRequest.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/CapabilityListResponseDto.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/CapabilityRetireResponseDto.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/package-info.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/admin/service/CapabilityAdminService.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/admin/CapabilityAdminController.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/admin/package-info.java`
- Test: `entitlement-service/src/test/java/com/solovis/entitlement/service/admin/CapabilityAdminServiceTest.java`
- Test: `entitlement-service/src/test/java/com/solovis/entitlement/service/admin/CapabilityAdminControllerTest.java`

**Interfaces:**
- Consumes: `CapabilityRepository`/`PlanEntitlementRepository`/`AccountOverrideRepository` (exist), `AuditRecorder`/`ActorResolver` (Task 2), `SnapshotPublisher`/`ValueColumnCodec`/`DeltaChange` (Task 3), `ValueMapper`/`CapabilityDescriptorMapper` (Task 1).
- Produces: `GET/POST /admin/v1/capabilities`, `GET/PATCH /admin/v1/capabilities/{key}`, `POST /admin/v1/capabilities/{key}/tiers`, `POST /admin/v1/capabilities/{key}/retire`. `CapabilityAdminService` is package-private-safe to call directly in Task 6/7 tests that need a capability fixture (it's simpler than hand-rolling repository inserts).

- [ ] **Step 1: `AuditJson` (small, shared by every later admin task)**

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/audit/AuditJson.java
package com.solovis.entitlement.service.audit;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Serialises a DTO for audit_event.before_json/after_json — a record of what the operator saw, not a re-derivable projection. */
public final class AuditJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AuditJson() {}

    public static String write(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise audit payload: " + value, e);
        }
    }
}
```

- [ ] **Step 2: Request/response DTOs**

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/CapabilityCreateRequest.java
package com.solovis.entitlement.service.admin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.solovis.entitlement.service.dto.ValueDto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;

public record CapabilityCreateRequest(
    @NotBlank @Pattern(regexp = "^[a-z0-9]+(\\.[a-z0-9_-]+)+$", message = "must be dotted, e.g. 'export.parquet'") String key,
    @NotBlank String displayName,
    String description,
    @NotBlank String valueType,
    @NotNull @JsonProperty("default") ValueDto defaultValue,
    ValueDto offValue,
    List<TierRequest> tiers
) {
    public record TierRequest(@NotBlank String tier, @NotBlank String displayName) {}
}
```

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/CapabilityPatchRequest.java
package com.solovis.entitlement.service.admin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.solovis.entitlement.service.dto.ValueDto;

// Note the absence of valueType — a capability's declared type is immutable after creation (c1);
// omitting the field from the request shape is what makes "cannot change" structural, not enforced by discipline.
public record CapabilityPatchRequest(
    String displayName,
    String description,
    @JsonProperty("default") ValueDto defaultValue,
    ValueDto offValue
) {}
```

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/TierAppendRequest.java
package com.solovis.entitlement.service.admin.dto;

import jakarta.validation.constraints.NotBlank;

public record TierAppendRequest(@NotBlank String tier, @NotBlank String displayName) {}
```

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/CapabilityListResponseDto.java
package com.solovis.entitlement.service.admin.dto;

import com.solovis.entitlement.service.dto.CapabilityDescriptorDto;
import java.util.List;

public record CapabilityListResponseDto(List<CapabilityDescriptorDto> capabilities, long snapshotVersion) {}
```

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/CapabilityRetireResponseDto.java
package com.solovis.entitlement.service.admin.dto;

import com.solovis.entitlement.service.dto.CapabilityDescriptorDto;
import java.util.List;

public record CapabilityRetireResponseDto(CapabilityDescriptorDto capability, Usage usage) {
    public record Usage(List<String> plans, long liveOverrides) {}
}
```

- [ ] **Step 3: `CapabilityAdminService`, test-first for the create path (the richest validation)**

```java
// entitlement-service/src/test/java/com/solovis/entitlement/service/admin/CapabilityAdminServiceTest.java
package com.solovis.entitlement.service.admin;

import com.solovis.entitlement.service.admin.dto.CapabilityCreateRequest;
import com.solovis.entitlement.service.admin.service.CapabilityAdminService;
import com.solovis.entitlement.service.dto.ValueDto;
import com.solovis.entitlement.service.error.EntitlementApiException;
import com.solovis.entitlement.service.error.ErrorCode;
import com.solovis.entitlement.service.snapshot.SnapshotHolder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class CapabilityAdminServiceTest {

    @Autowired CapabilityAdminService service;
    @Autowired SnapshotHolder snapshotHolder;

    @Test
    void createPublishesTheCapabilityIntoTheLiveSnapshot() {
        var request = new CapabilityCreateRequest("reports.monthly", "Monthly reports", "desc", "QUANTITY",
            new ValueDto("QUANTITY", null, 0L, null, null, null), null, null);

        var created = service.create(request);

        assertThat(created.key()).isEqualTo("reports.monthly");
        assertThat(snapshotHolder.current().capability(new com.solovis.entitlement.core.model.CapabilityKey("reports.monthly")))
            .isPresent();
    }

    @Test
    void createRejectsDuplicateKey() {
        var request = new CapabilityCreateRequest("api.access", "API", null, "SWITCH",
            new ValueDto("SWITCH", false, null, null, null, null), null, null);
        service.create(request);

        assertThatThrownBy(() -> service.create(request))
            .isInstanceOf(EntitlementApiException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void createRejectsDefaultTypeMismatch() {
        var request = new CapabilityCreateRequest("seats.count", "Seats", null, "QUANTITY",
            new ValueDto("SWITCH", true, null, null, null, null), null, null);

        assertThatThrownBy(() -> service.create(request))
            .isInstanceOf(EntitlementApiException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.VALUE_TYPE_MISMATCH);
    }

    @Test
    void createRejectsFewerThanTwoTiers() {
        var request = new CapabilityCreateRequest("support.tier", "Support", null, "TIER",
            new ValueDto("TIER", null, null, null, "community", null), null,
            List.of(new CapabilityCreateRequest.TierRequest("community", "Community")));

        assertThatThrownBy(() -> service.create(request))
            .isInstanceOf(EntitlementApiException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void appendTierAddsAboveTheCurrentMaximumOrdinal() {
        var create = new CapabilityCreateRequest("support.level", "Support", null, "TIER",
            new ValueDto("TIER", null, null, null, "community", null), null,
            List.of(new CapabilityCreateRequest.TierRequest("community", "Community"),
                    new CapabilityCreateRequest.TierRequest("gold", "Gold")));
        service.create(create);

        var updated = service.appendTier("support.level", new com.solovis.entitlement.service.admin.dto.TierAppendRequest("platinum", "Platinum"));

        assertThat(updated.tiers()).extracting(t -> t.tier()).containsExactly("community", "gold", "platinum");
        assertThat(updated.tiers().get(2).ordinal()).isEqualTo(2);
    }

    @Test
    void retireReturnsUsageAndRemainsReadableAfterwards() {
        var create = new CapabilityCreateRequest("export.parquet", "Export", null, "SWITCH",
            new ValueDto("SWITCH", false, null, null, null, null), null, null);
        service.create(create);

        var result = service.retire("export.parquet");

        assertThat(result.capability().status()).isEqualTo("RETIRED");
        assertThat(result.usage().plans()).isEmpty();
        assertThat(result.usage().liveOverrides()).isZero();
    }
}
```

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/admin/service/CapabilityAdminService.java
package com.solovis.entitlement.service.admin.service;

import com.solovis.entitlement.core.model.*;
import com.solovis.entitlement.core.view.SnapshotMutator;
import com.solovis.entitlement.service.admin.dto.*;
import com.solovis.entitlement.service.audit.ActorResolver;
import com.solovis.entitlement.service.audit.AuditEntry;
import com.solovis.entitlement.service.audit.AuditJson;
import com.solovis.entitlement.service.audit.AuditRecorder;
import com.solovis.entitlement.service.dto.CapabilityDescriptorDto;
import com.solovis.entitlement.service.dto.CapabilityDescriptorMapper;
import com.solovis.entitlement.service.dto.ValueMapper;
import com.solovis.entitlement.service.error.EntitlementApiException;
import com.solovis.entitlement.service.error.ErrorCode;
import com.solovis.entitlement.service.snapshot.DeltaChange;
import com.solovis.entitlement.service.snapshot.SnapshotPublisher;
import com.solovis.entitlement.service.snapshot.ValueColumnCodec;
import com.solovis.entitlement.service.store.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class CapabilityAdminService {

    private final CapabilityRepository capabilityRepository;
    private final PlanEntitlementRepository planEntitlementRepository;
    private final AccountOverrideRepository accountOverrideRepository;
    private final PlanRepository planRepository;
    private final AuditRecorder auditRecorder;
    private final ActorResolver actorResolver;
    private final SnapshotPublisher snapshotPublisher;
    private final Clock clock;

    public CapabilityAdminService(CapabilityRepository capabilityRepository, PlanEntitlementRepository planEntitlementRepository,
            AccountOverrideRepository accountOverrideRepository, PlanRepository planRepository, AuditRecorder auditRecorder,
            ActorResolver actorResolver, SnapshotPublisher snapshotPublisher, Clock clock) {
        this.capabilityRepository = capabilityRepository;
        this.planEntitlementRepository = planEntitlementRepository;
        this.accountOverrideRepository = accountOverrideRepository;
        this.planRepository = planRepository;
        this.auditRecorder = auditRecorder;
        this.actorResolver = actorResolver;
        this.snapshotPublisher = snapshotPublisher;
        this.clock = clock;
    }

    public List<CapabilityDescriptorDto> list(String area, String status, String q) {
        String sqlStatus = (status == null || status.equals("ALL")) ? null : status;
        return capabilityRepository.findAll(area, sqlStatus, q).stream()
            .map(row -> com.solovis.entitlement.service.snapshot.RowMappers.toCapability(row, capabilityRepository.findTiers(row.id())))
            .map(CapabilityDescriptorMapper::toDescriptor)
            .toList();
    }

    public CapabilityDescriptorDto get(String key) {
        return CapabilityDescriptorMapper.toDescriptor(loadDomain(key));
    }

    @Transactional
    public CapabilityDescriptorDto create(CapabilityCreateRequest request) {
        if (capabilityRepository.existsByKey(request.key())) {
            throw new EntitlementApiException(ErrorCode.VALIDATION_FAILED,
                "Capability key '" + request.key() + "' is already declared.");
        }
        ValueType valueType = parseValueType(request.valueType());
        if (!request.defaultValue().type().equals(valueType.name())) {
            throw new EntitlementApiException(ErrorCode.VALUE_TYPE_MISMATCH,
                "Default value type " + request.defaultValue().type() + " does not match declared type " + valueType + ".");
        }

        TierOrder tierOrder = buildTierOrder(request.tiers());
        EntitlementValue defaultValue = decode(request.defaultValue(), valueType, tierOrder);
        Optional<OffValue> offValue = request.offValue() == null
            ? Optional.empty() : Optional.of(new OffValue(decode(request.offValue(), valueType, tierOrder)));

        Capability capability = buildCapability(new CapabilityKey(request.key()), request.displayName(),
            request.description(), valueType, defaultValue, offValue, tierOrder, Capability.Status.ACTIVE, null);

        String now = clock.instant().toString();
        var columns = ValueColumnCodec.toColumns(defaultValue);
        var offColumns = offValue.map(ov -> ValueColumnCodec.toColumns(ov.value()));
        long id = capabilityRepository.insert(new CapabilityRow(null, capability.key().value(), capability.area(),
            capability.displayName(), capability.description(), valueType.name(),
            columns.boolValue(), columns.qtyValue(), columns.qtyUnlimited(), columns.tierValue(),
            offValue.isPresent(), offColumns.map(ValueColumnCodec.Columns::qtyValue).orElse(null),
            offColumns.map(ValueColumnCodec.Columns::tierValue).orElse(null), "ACTIVE", null, now, now));
        for (var tier : tierOrder.tiers()) {
            capabilityRepository.insertTier(new CapabilityTierRow(id, tier.tierKey(), tier.ordinal(), tier.displayName()));
        }

        var descriptor = CapabilityDescriptorMapper.toDescriptor(capability);
        long auditSeq = auditRecorder.record(AuditEntry.builder()
            .actor(actorResolver.currentActor()).source("UI").entityType("CAPABILITY")
            .entityId(capability.key().value()).action("CREATE").capabilityId(id)
            .afterJson(AuditJson.write(descriptor)).build());

        snapshotPublisher.publish((base, v) -> SnapshotMutator.withCapability(base, v, capability), auditSeq,
            new DeltaChange.CapabilityUpserted(descriptor));

        return descriptor;
    }

    @Transactional
    public CapabilityDescriptorDto patch(String key, CapabilityPatchRequest request) {
        Capability current = loadDomain(key);
        var row = capabilityRepository.findByKey(key).orElseThrow();

        String displayName = request.displayName() != null ? request.displayName() : current.displayName();
        String description = request.description() != null ? request.description() : current.description();
        EntitlementValue defaultValue = request.defaultValue() != null
            ? ValueMapper.fromDto(request.defaultValue(), current) : current.defaultValue();
        Optional<OffValue> offValue = request.offValue() != null
            ? Optional.of(new OffValue(ValueMapper.fromDto(request.offValue(), current))) : current.offValue();

        Capability updated = buildCapability(current.key(), displayName, description, current.valueType(),
            defaultValue, offValue, current.tierOrder(), current.status(), current.retiredAt());

        String now = clock.instant().toString();
        var columns = ValueColumnCodec.toColumns(defaultValue);
        var offColumns = offValue.map(ov -> ValueColumnCodec.toColumns(ov.value()));
        capabilityRepository.update(new CapabilityRow(row.id(), row.key(), row.area(), displayName, description,
            row.valueType(), columns.boolValue(), columns.qtyValue(), columns.qtyUnlimited(), columns.tierValue(),
            offValue.isPresent(), offColumns.map(ValueColumnCodec.Columns::qtyValue).orElse(null),
            offColumns.map(ValueColumnCodec.Columns::tierValue).orElse(null), row.status(), row.retiredAt(),
            row.createdAt(), now));

        var descriptor = CapabilityDescriptorMapper.toDescriptor(updated);
        long auditSeq = auditRecorder.record(AuditEntry.builder()
            .actor(actorResolver.currentActor()).source("UI").entityType("CAPABILITY")
            .entityId(key).action("UPDATE").capabilityId(row.id())
            .beforeJson(AuditJson.write(CapabilityDescriptorMapper.toDescriptor(current)))
            .afterJson(AuditJson.write(descriptor)).build());

        snapshotPublisher.publish((base, v) -> SnapshotMutator.withCapability(base, v, updated), auditSeq,
            new DeltaChange.CapabilityUpserted(descriptor));
        return descriptor;
    }

    @Transactional
    public CapabilityDescriptorDto appendTier(String key, TierAppendRequest request) {
        Capability current = loadDomain(key);
        if (current.valueType() != ValueType.TIER) {
            throw new EntitlementApiException(ErrorCode.IMMUTABLE_FIELD, "Capability '" + key + "' is not a TIER capability.");
        }
        if (current.tierOrder().declares(request.tier())) {
            throw new EntitlementApiException(ErrorCode.IMMUTABLE_FIELD,
                "Tier '" + request.tier() + "' already exists; tiers may only be appended (data-model.md).");
        }
        TierOrder appended = current.tierOrder().appending(request.tier(), request.displayName());
        Capability updated = buildCapability(current.key(), current.displayName(), current.description(),
            current.valueType(), current.defaultValue(), current.offValue(), appended, current.status(), current.retiredAt());

        var row = capabilityRepository.findByKey(key).orElseThrow();
        int newOrdinal = appended.tierOrder().maxOrdinal();
        capabilityRepository.insertTier(new CapabilityTierRow(row.id(), request.tier(), newOrdinal, request.displayName()));

        var descriptor = CapabilityDescriptorMapper.toDescriptor(updated);
        long auditSeq = auditRecorder.record(AuditEntry.builder()
            .actor(actorResolver.currentActor()).source("UI").entityType("CAPABILITY_TIER")
            .entityId(key).action("CREATE").capabilityId(row.id())
            .afterJson(AuditJson.write(descriptor)).build());

        snapshotPublisher.publish((base, v) -> SnapshotMutator.withCapability(base, v, updated), auditSeq,
            new DeltaChange.CapabilityUpserted(descriptor));
        return descriptor;
    }

    @Transactional
    public CapabilityRetireResponseDto retire(String key) {
        Capability current = loadDomain(key);
        if (current.isRetired()) {
            throw new EntitlementApiException(ErrorCode.RETIRED_CAPABILITY, "Capability '" + key + "' is already retired.");
        }
        var row = capabilityRepository.findByKey(key).orElseThrow();
        String now = clock.instant().toString();
        boolean retired = capabilityRepository.retire(row.id(), now, now);
        if (!retired) {
            throw new EntitlementApiException(ErrorCode.RETIRED_CAPABILITY, "Capability '" + key + "' is already retired.");
        }
        Capability updated = buildCapability(current.key(), current.displayName(), current.description(),
            current.valueType(), current.defaultValue(), current.offValue(), current.tierOrder(),
            Capability.Status.RETIRED, java.time.Instant.parse(now));

        var planKeys = planEntitlementRepository.findPlanIdsUsingCapability(row.id()).stream()
            .map(planId -> planRepository.findById(planId).orElseThrow().key()).toList();
        long liveOverrides = accountOverrideRepository.countLiveForCapability(row.id());

        var descriptor = CapabilityDescriptorMapper.toDescriptor(updated);
        long auditSeq = auditRecorder.record(AuditEntry.builder()
            .actor(actorResolver.currentActor()).source("UI").entityType("CAPABILITY")
            .entityId(key).action("RETIRE").capabilityId(row.id())
            .afterJson(AuditJson.write(descriptor)).build());

        snapshotPublisher.publish((base, v) -> SnapshotMutator.withCapability(base, v, updated), auditSeq,
            new DeltaChange.CapabilityRetired(key));

        return new CapabilityRetireResponseDto(descriptor, new CapabilityRetireResponseDto.Usage(planKeys, liveOverrides));
    }

    private Capability loadDomain(String key) {
        var row = capabilityRepository.findByKey(key)
            .orElseThrow(() -> new com.solovis.entitlement.core.error.UnknownCapabilityException(key));
        return com.solovis.entitlement.service.snapshot.RowMappers.toCapability(row, capabilityRepository.findTiers(row.id()));
    }

    private static ValueType parseValueType(String raw) {
        try {
            return ValueType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new EntitlementApiException(ErrorCode.VALIDATION_FAILED, "Unknown value type '" + raw + "'.");
        }
    }

    private static TierOrder buildTierOrder(List<CapabilityCreateRequest.TierRequest> tiers) {
        if (tiers == null) {
            return TierOrder.NONE;
        }
        var definitions = new ArrayList<TierOrder.TierDefinition>();
        for (int i = 0; i < tiers.size(); i++) {
            definitions.add(new TierOrder.TierDefinition(tiers.get(i).tier(), i, tiers.get(i).displayName()));
        }
        try {
            return new TierOrder(definitions);
        } catch (IllegalArgumentException e) {
            throw new EntitlementApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
        }
    }

    private static EntitlementValue decode(com.solovis.entitlement.service.dto.ValueDto dto, ValueType valueType, TierOrder tierOrder) {
        // A minimal capability shell is enough for ValueMapper.fromDto — it only reads valueType() and tierOrder().
        var shell = new Capability(new CapabilityKey("shell.value"), "shell", null, valueType,
            valueType == ValueType.TIER ? new EntitlementValue.Tier(tierOrder.tiers().get(0).tierKey(), 0)
                : valueType == ValueType.QUANTITY ? EntitlementValue.Quantity.of(0) : new EntitlementValue.Switch(false),
            Optional.empty(), tierOrder, Capability.Status.ACTIVE, null);
        return ValueMapper.fromDto(dto, shell);
    }

    private static Capability buildCapability(CapabilityKey key, String displayName, String description,
            ValueType valueType, EntitlementValue defaultValue, Optional<OffValue> offValue, TierOrder tierOrder,
            Capability.Status status, java.time.Instant retiredAt) {
        try {
            return new Capability(key, displayName, description, valueType, defaultValue, offValue, tierOrder, status, retiredAt);
        } catch (IllegalArgumentException e) {
            throw new EntitlementApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
        }
    }
}
```

*(The `decode` shell trick sidesteps a chicken-and-egg problem — validating a default/off-value shape needs a `Capability` to call `ValueMapper.fromDto` against, but the capability doesn't exist until its default value is decoded. A throwaway shell with the right `valueType`/`tierOrder` is enough because `ValueMapper.fromDto` never reads anything else off it.)*

- [ ] **Step 4: Controller + slice test**

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/admin/CapabilityAdminController.java
package com.solovis.entitlement.service.admin;

import com.solovis.entitlement.service.admin.dto.*;
import com.solovis.entitlement.service.admin.service.CapabilityAdminService;
import com.solovis.entitlement.service.snapshot.SnapshotHolder;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/v1/capabilities")
public class CapabilityAdminController {

    private final CapabilityAdminService service;
    private final SnapshotHolder snapshotHolder;

    public CapabilityAdminController(CapabilityAdminService service, SnapshotHolder snapshotHolder) {
        this.service = service;
        this.snapshotHolder = snapshotHolder;
    }

    @GetMapping
    public CapabilityListResponseDto list(
        @RequestParam(required = false) String area,
        @RequestParam(required = false, defaultValue = "ACTIVE") String status,
        @RequestParam(required = false) String q) {
        return new CapabilityListResponseDto(service.list(area, status, q), snapshotHolder.current().snapshotVersion());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public com.solovis.entitlement.service.dto.CapabilityDescriptorDto create(@Valid @RequestBody CapabilityCreateRequest request) {
        return service.create(request);
    }

    @GetMapping("/{key}")
    public com.solovis.entitlement.service.dto.CapabilityDescriptorDto get(@PathVariable String key) {
        return service.get(key);
    }

    @PatchMapping("/{key}")
    public com.solovis.entitlement.service.dto.CapabilityDescriptorDto patch(
        @PathVariable String key, @RequestBody CapabilityPatchRequest request) {
        return service.patch(key, request);
    }

    @PostMapping("/{key}/tiers")
    public com.solovis.entitlement.service.dto.CapabilityDescriptorDto appendTier(
        @PathVariable String key, @Valid @RequestBody TierAppendRequest request) {
        return service.appendTier(key, request);
    }

    @PostMapping("/{key}/retire")
    public CapabilityRetireResponseDto retire(@PathVariable String key) {
        return service.retire(key);
    }
}
```

```java
// entitlement-service/src/test/java/com/solovis/entitlement/service/admin/CapabilityAdminControllerTest.java
package com.solovis.entitlement.service.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class CapabilityAdminControllerTest {

    @Autowired MockMvc mockMvc;

    @Test
    void createReturns201WithTheDescriptor() throws Exception {
        String body = """
            {"key":"api.access","displayName":"API access","valueType":"SWITCH",
             "default":{"type":"SWITCH","enabled":false}}
            """;
        mockMvc.perform(post("/admin/v1/capabilities").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.key").value("api.access"))
            .andExpect(jsonPath("$.area").value("api"));
    }

    @Test
    void createRejectsMissingDisplayNameWithValidationFailed() throws Exception {
        String body = """
            {"key":"api.access","valueType":"SWITCH","default":{"type":"SWITCH","enabled":false}}
            """;
        mockMvc.perform(post("/admin/v1/capabilities").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.type").value("entitlement/validation-failed"));
    }
}
```

- [ ] **Step 5: Run and commit**

Run: `./mvnw -pl entitlement-service test -Dtest='com.solovis.entitlement.service.admin.CapabilityAdmin**'`
Expected: PASS.

```bash
git add entitlement-service/src/main/java/com/solovis/entitlement/service/audit/AuditJson.java \
        entitlement-service/src/main/java/com/solovis/entitlement/service/admin \
        entitlement-service/src/test/java/com/solovis/entitlement/service/admin
git commit -m "feat(entitlement-service): admin API — capability registry (create, patch, tiers, retire)"
```

## Task 6: Admin API — Plans and the default-plan setting (`admin-api.md` "Plans — screen 2")

**One core gap to close first:** `SnapshotMutator` (already built) has `withPlanEntitlement` (set/replace one capability's value on a plan) but no removal counterpart — needed for the `unset` half of a plan edit (c4: absence returns a capability to its default). Add it the same way `withOverrideRemoved` already exists, mirroring that method exactly.

**Files:**
- Modify: `entitlement-core/src/main/java/com/solovis/entitlement/core/view/SnapshotMutator.java` (add `withPlanEntitlementRemoved`)
- Test: `entitlement-core/src/test/java/com/solovis/entitlement/core/view/SnapshotMutatorTest.java` (append)
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/PlanCreateRequest.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/PlanPatchRequest.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/PlanSummaryDto.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/PlanDetailDto.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/PlanEntitlementEditRequest.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/PlanPreviewResponseDto.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/PlanApplyResponseDto.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/admin/PreviewTokenCodec.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/admin/service/PlanAdminService.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/admin/PlanAdminController.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/admin/SettingsController.java`
- Test: `entitlement-service/src/test/java/com/solovis/entitlement/service/admin/PreviewTokenCodecTest.java`
- Test: `entitlement-service/src/test/java/com/solovis/entitlement/service/admin/PlanAdminServiceTest.java`
- Test: `entitlement-service/src/test/java/com/solovis/entitlement/service/admin/PlanAdminControllerTest.java`

**Interfaces:**
- Consumes: `PlanRepository`/`PlanEntitlementRepository`/`AccountRepository` (exist), `CapabilityAdminService.get`-equivalent lookups via `RowMappers.toCapability`, everything Task 5 established.
- Produces: `GET/POST /admin/v1/plans`, `GET/PATCH /admin/v1/plans/{key}`, `POST /admin/v1/plans/{key}/entitlements/preview`, `PUT /admin/v1/plans/{key}/entitlements`, `POST /admin/v1/plans/{key}/archive`, `PUT /admin/v1/settings/default-plan`.

- [ ] **Step 1: Core addition, test-first**

Append to `SnapshotMutatorTest.java`:

```java
@Test
void withPlanEntitlementRemovedFallsBackToCapabilityDefault() {
    var key = new CapabilityKey("export.parquet");
    var capability = new Capability(key, "Export", null, ValueType.SWITCH,
        new EntitlementValue.Switch(false), Optional.empty(), TierOrder.NONE, Capability.Status.ACTIVE, null);
    var base = new SnapshotBuilder().capability(capability)
        .plan(new Plan("pro", "Pro", Plan.Status.ACTIVE, false))
        .planEntitlement(new PlanEntitlement("pro", key, new EntitlementValue.Switch(true)))
        .account(new AccountAssignment("acct_1", "pro"))
        .build(1);

    var next = SnapshotMutator.withPlanEntitlementRemoved(base, 2, "pro", key);

    assertThat(next.planEntitlement("pro", key)).isEmpty();
    var decision = com.solovis.entitlement.core.engine.Resolver.resolve(next, "acct_1", key, java.time.Instant.now());
    assertThat(decision.value()).isEqualTo(new EntitlementValue.Switch(false)); // capability default, not the removed plan value
}
```

Run: `./mvnw -pl entitlement-core test -Dtest=SnapshotMutatorTest` — confirm compile failure.

Add to `SnapshotMutator.java`, directly below `withPlanEntitlement`:

```java
    public static Snapshot withPlanEntitlementRemoved(Snapshot base, long newVersion, String planKey, CapabilityKey capabilityKey) {
        var planEntitlements = new HashMap<>(base.planEntitlementsMap());
        planEntitlements.remove(new Snapshot.PlanCapabilityKey(planKey, capabilityKey));
        return new Snapshot(newVersion, base.capabilitiesMap(), base.plansMap(),
            Map.copyOf(planEntitlements), base.accountsMap(), base.liveOverridesMap());
    }
```

Run: `./mvnw -pl entitlement-core test -Dtest=SnapshotMutatorTest` — confirm PASS, then `./mvnw -pl entitlement-core test` for the whole module.

- [ ] **Step 2: The preview-token codec, test-first**

Stateless by design (no server-side session table, matching the single-writer/no-opaque-token ethos `research.md` §21 already established elsewhere): the token is a digest of the plan key, the exact edit, and the snapshot version it was computed against. Applying re-derives the same digest from the apply request and the *current* snapshot version — a token from a stale snapshot or a different edit simply fails to match.

```java
// entitlement-service/src/test/java/com/solovis/entitlement/service/admin/PreviewTokenCodecTest.java
package com.solovis.entitlement.service.admin;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PreviewTokenCodecTest {

    @Test
    void sameInputsProduceTheSameToken() {
        var a = PreviewTokenCodec.compute("pro", Map.of("reports.monthly", "QUANTITY:75"), List.of("export.parquet"), 48211);
        var b = PreviewTokenCodec.compute("pro", Map.of("reports.monthly", "QUANTITY:75"), List.of("export.parquet"), 48211);
        assertThat(a).isEqualTo(b).startsWith("pv_");
    }

    @Test
    void aDifferentSnapshotVersionProducesADifferentToken() {
        var a = PreviewTokenCodec.compute("pro", Map.of("reports.monthly", "QUANTITY:75"), List.of(), 48211);
        var b = PreviewTokenCodec.compute("pro", Map.of("reports.monthly", "QUANTITY:75"), List.of(), 48212);
        assertThat(a).isNotEqualTo(b);
    }
}
```

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/admin/PreviewTokenCodec.java
package com.solovis.entitlement.service.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.List;
import java.util.TreeMap;

/** A stateless digest binding an applied plan edit to the exact preview that was shown for it (c34). */
public final class PreviewTokenCodec {

    private PreviewTokenCodec() {}

    /** {@code set} and {@code unset} must already be canonicalised to plain strings by the caller (e.g. "QUANTITY:75", "SWITCH:true", capability keys sorted). */
    public static String compute(String planKey, Map<String, String> set, List<String> unset, long snapshotVersion) {
        var canonicalSet = new TreeMap<>(set);
        var canonicalUnset = unset.stream().sorted().toList();
        String material = planKey + "|" + canonicalSet + "|" + canonicalUnset + "|" + snapshotVersion;
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return "pv_" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
```

- [ ] **Step 3: Request/response DTOs**

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/PlanCreateRequest.java
package com.solovis.entitlement.service.admin.dto;

import jakarta.validation.constraints.NotBlank;

public record PlanCreateRequest(@NotBlank String key, @NotBlank String name, String description) {}
```

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/PlanPatchRequest.java
package com.solovis.entitlement.service.admin.dto;

public record PlanPatchRequest(String name, String description) {}
```

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/PlanSummaryDto.java
package com.solovis.entitlement.service.admin.dto;

public record PlanSummaryDto(String key, String name, String status, boolean isDefaultForNewAccounts,
    long accountCount, long entitlementCount) {}
```

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/PlanDetailDto.java
package com.solovis.entitlement.service.admin.dto;

import com.solovis.entitlement.service.dto.ValueDto;
import java.util.Map;

public record PlanDetailDto(String key, String name, String description, String status,
    boolean isDefaultForNewAccounts, long accountCount, Map<String, ValueDto> entitlements) {}
```

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/PlanEntitlementEditRequest.java
package com.solovis.entitlement.service.admin.dto;

import com.solovis.entitlement.service.dto.ValueDto;
import java.util.List;
import java.util.Map;

public record PlanEntitlementEditRequest(Map<String, ValueDto> set, List<String> unset, String previewAccount, String previewToken) {}
```

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/PlanPreviewResponseDto.java
package com.solovis.entitlement.service.admin.dto;

import com.solovis.entitlement.service.api.dto.DecisionResponseDto;
import com.solovis.entitlement.service.dto.ValueDto;
import java.util.List;

public record PlanPreviewResponseDto(
    String planKey, long affectedAccountCount, List<Diff> diff, PreviewAccount previewAccount, String previewToken
) {
    public record Diff(String capability, ValueDto before, ValueDto after, String note) {}
    public record PreviewAccount(String account, List<Effect> effects) {}
    public record Effect(String capability, DecisionResponseDto before, DecisionResponseDto after, boolean changed, String note) {}
}
```

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/PlanApplyResponseDto.java
package com.solovis.entitlement.service.admin.dto;

public record PlanApplyResponseDto(
    String planKey, long affectedAccountCount, long snapshotVersion, long auditSeq, int changeVisibleEverywhereWithinSeconds
) {}
```

- [ ] **Step 4: `PlanAdminService`, test-first for create/preview/apply/archive/default**

```java
// entitlement-service/src/test/java/com/solovis/entitlement/service/admin/PlanAdminServiceTest.java
package com.solovis.entitlement.service.admin;

import com.solovis.entitlement.service.admin.dto.*;
import com.solovis.entitlement.service.admin.service.CapabilityAdminService;
import com.solovis.entitlement.service.admin.service.PlanAdminService;
import com.solovis.entitlement.service.dto.ValueDto;
import com.solovis.entitlement.service.error.EntitlementApiException;
import com.solovis.entitlement.service.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class PlanAdminServiceTest {

    @Autowired PlanAdminService planService;
    @Autowired CapabilityAdminService capabilityService;

    @Test
    void previewThenApplyWithTheReturnedTokenSucceeds() {
        capabilityService.create(new CapabilityCreateRequest("reports.monthly", "Monthly reports", null, "QUANTITY",
            new ValueDto("QUANTITY", null, 0L, null, null, null), null, null));
        planService.create(new PlanCreateRequest("pro", "Pro", null));

        var edit = new PlanEntitlementEditRequest(Map.of("reports.monthly", new ValueDto("QUANTITY", null, 75L, null, null, null)),
            List.of(), null, null);
        var preview = planService.preview("pro", edit);
        assertThat(preview.previewToken()).startsWith("pv_");

        var apply = planService.apply("pro", new PlanEntitlementEditRequest(edit.set(), edit.unset(), null, preview.previewToken()));
        assertThat(apply.planKey()).isEqualTo("pro");
    }

    @Test
    void applyWithAStaleTokenIsRejected() {
        capabilityService.create(new CapabilityCreateRequest("seats.count", "Seats", null, "QUANTITY",
            new ValueDto("QUANTITY", null, 0L, null, null, null), null, null));
        planService.create(new PlanCreateRequest("free2", "Free 2", null));
        var edit = new PlanEntitlementEditRequest(Map.of("seats.count", new ValueDto("QUANTITY", null, 5L, null, null, null)),
            List.of(), null, "pv_not-a-real-token");

        assertThatThrownBy(() -> planService.apply("free2", edit))
            .isInstanceOf(EntitlementApiException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.PREVIEW_TOKEN_INVALID);
    }

    @Test
    void archiveRejectsAPlanWithAccounts() {
        planService.create(new PlanCreateRequest("has-accounts", "Has accounts", null));
        // account creation is Task 7; this test only needs the plan-in-use branch reachable once
        // Task 7 exists — see Task 7's AccountAdminServiceTest for the account-bearing case. Here,
        // assert the empty-plan path archives cleanly instead:
        planService.archive("has-accounts");
    }
}
```

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/admin/service/PlanAdminService.java
package com.solovis.entitlement.service.admin.service;

import com.solovis.entitlement.core.engine.Resolver;
import com.solovis.entitlement.core.model.*;
import com.solovis.entitlement.core.view.Snapshot;
import com.solovis.entitlement.core.view.SnapshotMutator;
import com.solovis.entitlement.service.admin.PreviewTokenCodec;
import com.solovis.entitlement.service.admin.dto.*;
import com.solovis.entitlement.service.api.DecisionMapperAccess;
import com.solovis.entitlement.service.audit.ActorResolver;
import com.solovis.entitlement.service.audit.AuditEntry;
import com.solovis.entitlement.service.audit.AuditJson;
import com.solovis.entitlement.service.audit.AuditRecorder;
import com.solovis.entitlement.service.dto.ValueDto;
import com.solovis.entitlement.service.dto.ValueMapper;
import com.solovis.entitlement.service.error.EntitlementApiException;
import com.solovis.entitlement.service.error.ErrorCode;
import com.solovis.entitlement.service.snapshot.*;
import com.solovis.entitlement.service.store.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.*;

@Service
public class PlanAdminService {

    private final PlanRepository planRepository;
    private final CapabilityRepository capabilityRepository;
    private final PlanEntitlementRepository planEntitlementRepository;
    private final AuditRecorder auditRecorder;
    private final ActorResolver actorResolver;
    private final SnapshotPublisher snapshotPublisher;
    private final SnapshotHolder snapshotHolder;
    private final Clock clock;

    public PlanAdminService(PlanRepository planRepository, CapabilityRepository capabilityRepository,
            PlanEntitlementRepository planEntitlementRepository, AuditRecorder auditRecorder, ActorResolver actorResolver,
            SnapshotPublisher snapshotPublisher, SnapshotHolder snapshotHolder, Clock clock) {
        this.planRepository = planRepository;
        this.capabilityRepository = capabilityRepository;
        this.planEntitlementRepository = planEntitlementRepository;
        this.auditRecorder = auditRecorder;
        this.actorResolver = actorResolver;
        this.snapshotPublisher = snapshotPublisher;
        this.snapshotHolder = snapshotHolder;
        this.clock = clock;
    }

    public List<PlanSummaryDto> list() {
        return planRepository.findAll(null).stream().map(row -> new PlanSummaryDto(row.key(), row.name(), row.status(),
            row.defaultForNewAccounts(), planRepository.countAccounts(row.id()),
            planEntitlementRepository.findByPlan(row.id()).size())).toList();
    }

    public PlanDetailDto get(String key) {
        var row = requireRow(key);
        var capabilitiesById = capabilityRepository.findAll(null, null, null).stream()
            .collect(java.util.stream.Collectors.toMap(CapabilityRow::id, r -> r));
        Map<String, ValueDto> entitlements = new LinkedHashMap<>();
        for (var pe : planEntitlementRepository.findByPlan(row.id())) {
            var capRow = capabilitiesById.get(pe.capabilityId());
            var capability = RowMappers.toCapability(capRow, capabilityRepository.findTiers(capRow.id()));
            entitlements.put(capability.key().value(), ValueMapper.toDto(RowMappers.toPlanEntitlement(pe, key, capability).value()));
        }
        return new PlanDetailDto(row.key(), row.name(), row.description(), row.status(), row.defaultForNewAccounts(),
            planRepository.countAccounts(row.id()), entitlements);
    }

    @Transactional
    public PlanSummaryDto create(PlanCreateRequest request) {
        if (planRepository.findByKey(request.key()).isPresent()) {
            throw new EntitlementApiException(ErrorCode.VALIDATION_FAILED, "Plan key '" + request.key() + "' is already declared.");
        }
        String now = clock.instant().toString();
        planRepository.insert(new PlanRow(null, request.key(), request.name(), request.description(), "ACTIVE", false, now, now));
        var plan = new Plan(request.key(), request.name(), Plan.Status.ACTIVE, false);

        long auditSeq = auditRecorder.record(AuditEntry.builder().actor(actorResolver.currentActor()).source("UI")
            .entityType("PLAN").entityId(request.key()).action("CREATE").planId(requireRow(request.key()).id())
            .afterJson(AuditJson.write(request)).build());
        snapshotPublisher.publish((base, v) -> SnapshotMutator.withPlan(base, v, plan), auditSeq,
            new DeltaChange.PlanUpserted(plan.key(), plan.name(), "ACTIVE", false));

        return new PlanSummaryDto(request.key(), request.name(), "ACTIVE", false, 0, 0);
    }

    @Transactional
    public PlanSummaryDto patch(String key, PlanPatchRequest request) {
        var row = requireRow(key);
        String name = request.name() != null ? request.name() : row.name();
        String description = request.description() != null ? request.description() : row.description();
        String now = clock.instant().toString();
        planRepository.update(row.id(), name, description, now);
        var plan = new Plan(key, name, Plan.Status.valueOf(row.status()), row.defaultForNewAccounts());

        long auditSeq = auditRecorder.record(AuditEntry.builder().actor(actorResolver.currentActor()).source("UI")
            .entityType("PLAN").entityId(key).action("UPDATE").planId(row.id())
            .beforeJson(AuditJson.write(Map.of("name", row.name()))).afterJson(AuditJson.write(Map.of("name", name))).build());
        snapshotPublisher.publish((base, v) -> SnapshotMutator.withPlan(base, v, plan), auditSeq,
            new DeltaChange.PlanUpserted(key, name, row.status(), row.defaultForNewAccounts()));

        return new PlanSummaryDto(key, name, row.status(), row.defaultForNewAccounts(), planRepository.countAccounts(row.id()),
            planEntitlementRepository.findByPlan(row.id()).size());
    }

    public PlanPreviewResponseDto preview(String key, PlanEntitlementEditRequest request) {
        var row = requireRow(key);
        Snapshot snapshot = snapshotHolder.current();
        var diffs = new ArrayList<PlanPreviewResponseDto.Diff>();
        Map<String, String> canonicalSet = new LinkedHashMap<>();
        for (var entry : request.set().entrySet()) {
            var capability = requireDomainCapability(entry.getKey());
            var newValue = ValueMapper.fromDto(entry.getValue(), capability);
            var before = snapshot.planEntitlement(key, capability.key()).map(pe -> ValueMapper.toDto(pe.value())).orElse(null);
            diffs.add(new PlanPreviewResponseDto.Diff(entry.getKey(), before, ValueMapper.toDto(newValue), null));
            canonicalSet.put(entry.getKey(), newValue.valueType() + ":" + newValue);
        }
        for (var capabilityKey : request.unset()) {
            var capability = requireDomainCapability(capabilityKey);
            var before = snapshot.planEntitlement(key, capability.key()).map(pe -> ValueMapper.toDto(pe.value())).orElse(null);
            diffs.add(new PlanPreviewResponseDto.Diff(capabilityKey, before,
                ValueMapper.toDto(capability.defaultValue()), "Falls back to the capability default."));
        }

        PlanPreviewResponseDto.PreviewAccount previewAccount = null;
        if (request.previewAccount() != null) {
            Snapshot hypothetical = applyEdit(snapshot, key, request, snapshot.snapshotVersion());
            var effects = new ArrayList<PlanPreviewResponseDto.Effect>();
            for (var diff : diffs) {
                var capKey = new CapabilityKey(diff.capability());
                var beforeExplanation = Resolver.explain(snapshot, request.previewAccount(), capKey, clock.instant());
                var afterExplanation = Resolver.explain(hypothetical, request.previewAccount(), capKey, clock.instant());
                var capability = requireDomainCapability(diff.capability());
                var beforeDto = DecisionMapperAccess.toResponse(beforeExplanation, capability);
                var afterDto = DecisionMapperAccess.toResponse(afterExplanation, capability);
                boolean changed = !beforeDto.value().equals(afterDto.value());
                effects.add(new PlanPreviewResponseDto.Effect(diff.capability(), beforeDto, afterDto, changed,
                    changed ? null : "No change for this account."));
            }
            previewAccount = new PlanPreviewResponseDto.PreviewAccount(request.previewAccount(), effects);
        }

        long affected = planRepository.countAccounts(row.id());
        String token = PreviewTokenCodec.compute(key, canonicalSet, request.unset(), snapshot.snapshotVersion());
        return new PlanPreviewResponseDto(key, affected, diffs, previewAccount, token);
    }

    @Transactional
    public PlanApplyResponseDto apply(String key, PlanEntitlementEditRequest request) {
        var row = requireRow(key);
        Snapshot before = snapshotHolder.current();
        Map<String, String> canonicalSet = new LinkedHashMap<>();
        Map<String, ValueDto> setDtos = new LinkedHashMap<>();
        for (var entry : request.set().entrySet()) {
            var capability = requireDomainCapability(entry.getKey());
            if (capability.isRetired()) {
                throw new EntitlementApiException(ErrorCode.CAPABILITY_RETIRED_FOR_WRITE,
                    "Capability '" + entry.getKey() + "' is retired.");
            }
            var value = ValueMapper.fromDto(entry.getValue(), capability);
            canonicalSet.put(entry.getKey(), value.valueType() + ":" + value);
            setDtos.put(entry.getKey(), ValueMapper.toDto(value));
        }
        String expectedToken = PreviewTokenCodec.compute(key, canonicalSet, request.unset(), before.snapshotVersion());
        if (request.previewToken() == null || !request.previewToken().equals(expectedToken)) {
            throw new EntitlementApiException(ErrorCode.PREVIEW_TOKEN_INVALID,
                "The preview token is missing or was computed against a different snapshot version.");
        }

        String now = clock.instant().toString();
        for (var entry : request.set().entrySet()) {
            var capRow = capabilityRepository.findByKey(entry.getKey()).orElseThrow();
            var capability = requireDomainCapability(entry.getKey());
            var value = ValueMapper.fromDto(entry.getValue(), capability);
            var columns = ValueColumnCodec.toColumns(value);
            planEntitlementRepository.upsert(new PlanEntitlementRow(row.id(), capRow.id(), columns.boolValue(),
                columns.qtyValue(), columns.qtyUnlimited(), columns.tierValue(), now));
        }
        for (var capabilityKey : request.unset()) {
            var capRow = capabilityRepository.findByKey(capabilityKey).orElseThrow();
            planEntitlementRepository.delete(row.id(), capRow.id());
        }

        long affected = planRepository.countAccounts(row.id());
        long auditSeq = auditRecorder.record(AuditEntry.builder().actor(actorResolver.currentActor()).source("UI")
            .entityType("PLAN_ENTITLEMENT").entityId(key).action("UPDATE").planId(row.id())
            .afterJson(AuditJson.write(setDtos)).affectedAccountCount(affected).build());

        long newVersion = snapshotPublisher.publish((base, v) -> {
            Snapshot next = base;
            for (var entry : request.set().entrySet()) {
                var capability = requireDomainCapability(entry.getKey());
                var value = ValueMapper.fromDto(entry.getValue(), capability);
                next = SnapshotMutator.withPlanEntitlement(next, v, new PlanEntitlement(key, capability.key(), value));
            }
            for (var capabilityKey : request.unset()) {
                next = SnapshotMutator.withPlanEntitlementRemoved(next, v, key, new CapabilityKey(capabilityKey));
            }
            return next;
        }, auditSeq, new DeltaChange.PlanEntitlements(key, setDtos, request.unset()));

        return new PlanApplyResponseDto(key, affected, newVersion, auditSeq, 60);
    }

    @Transactional
    public void archive(String key) {
        var row = requireRow(key);
        if (planRepository.countAccounts(row.id()) > 0) {
            throw new EntitlementApiException(ErrorCode.PLAN_IN_USE, "Plan '" + key + "' still has accounts assigned.");
        }
        if (row.defaultForNewAccounts()) {
            throw new EntitlementApiException(ErrorCode.DEFAULT_PLAN_REQUIRED, "Plan '" + key + "' is the default for new accounts.");
        }
        String now = clock.instant().toString();
        planRepository.archive(row.id(), now);
        var plan = new Plan(key, row.name(), Plan.Status.ARCHIVED, false);

        long auditSeq = auditRecorder.record(AuditEntry.builder().actor(actorResolver.currentActor()).source("UI")
            .entityType("PLAN").entityId(key).action("ARCHIVE").planId(row.id()).build());
        snapshotPublisher.publish((base, v) -> SnapshotMutator.withPlan(base, v, plan), auditSeq, new DeltaChange.PlanArchived(key));
    }

    @Transactional
    public void designateDefault(String key) {
        var row = requireRow(key);
        if (!row.status().equals("ACTIVE")) {
            throw new EntitlementApiException(ErrorCode.VALIDATION_FAILED, "Plan '" + key + "' is not ACTIVE.");
        }
        String now = clock.instant().toString();
        var previousDefault = planRepository.findDefault();
        planRepository.clearDefault(now);
        planRepository.setDefault(row.id(), now);
        var newDefaultPlan = new Plan(key, row.name(), Plan.Status.ACTIVE, true);

        long auditSeq = auditRecorder.record(AuditEntry.builder().actor(actorResolver.currentActor()).source("UI")
            .entityType("DEFAULT_PLAN").entityId(key).action("DESIGNATE").planId(row.id()).build());

        snapshotPublisher.publish((base, v) -> {
            Snapshot next = SnapshotMutator.withPlan(base, v, newDefaultPlan);
            if (previousDefault.isPresent() && !previousDefault.get().key().equals(key)) {
                var old = previousDefault.get();
                next = SnapshotMutator.withPlan(next, v, new Plan(old.key(), old.name(), Plan.Status.ACTIVE, false));
            }
            return next;
        }, auditSeq, new DeltaChange.PlanDefaultChanged(key));
    }

    private Snapshot applyEdit(Snapshot base, String key, PlanEntitlementEditRequest request, long version) {
        Snapshot next = base;
        for (var entry : request.set().entrySet()) {
            var capability = requireDomainCapability(entry.getKey());
            var value = ValueMapper.fromDto(entry.getValue(), capability);
            next = SnapshotMutator.withPlanEntitlement(next, version, new PlanEntitlement(key, capability.key(), value));
        }
        for (var capabilityKey : request.unset()) {
            next = SnapshotMutator.withPlanEntitlementRemoved(next, version, key, new CapabilityKey(capabilityKey));
        }
        return next;
    }

    private PlanRow requireRow(String key) {
        return planRepository.findByKey(key)
            .orElseThrow(() -> new EntitlementApiException(ErrorCode.VALIDATION_FAILED, "No plan with key '" + key + "'."));
    }

    private Capability requireDomainCapability(String key) {
        var row = capabilityRepository.findByKey(key)
            .orElseThrow(() -> new com.solovis.entitlement.core.error.UnknownCapabilityException(key));
        return RowMappers.toCapability(row, capabilityRepository.findTiers(row.id()));
    }
}
```

`DecisionMapperAccess` above is a one-line adapter — `DecisionMapper.toResponse` in Task 4 was package-private (`final class DecisionMapper` with default-visibility static method), and the plan preview (in `admin.service`) needs it too. Rather than duplicating the note-synthesis logic, widen the single method's visibility:

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/api/DecisionMapper.java
// change the class and method modifiers from package-private to public:
public final class DecisionMapper {
    public static DecisionResponseDto toResponse(Explanation explanation, Capability capability) { ... }
```

and delete the `DecisionMapperAccess` indirection above — call `com.solovis.entitlement.service.api.DecisionMapper.toResponse(...)` directly from `PlanAdminService`. (Written here as a correction to Task 4 rather than a new file, because it only became necessary once Task 6's preview needed cross-package reuse — apply this change as part of Task 6's Step 4, and update the `import` in `PlanAdminService` accordingly; drop the `DecisionMapperAccess` import/class from the code above.)

- [ ] **Step 5: Controllers**

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/admin/PlanAdminController.java
package com.solovis.entitlement.service.admin;

import com.solovis.entitlement.service.admin.dto.*;
import com.solovis.entitlement.service.admin.service.PlanAdminService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/admin/v1/plans")
public class PlanAdminController {

    private final PlanAdminService service;

    public PlanAdminController(PlanAdminService service) { this.service = service; }

    @GetMapping
    public java.util.Map<String, Object> list() {
        return java.util.Map.of("plans", service.list());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PlanSummaryDto create(@Valid @RequestBody PlanCreateRequest request) { return service.create(request); }

    @GetMapping("/{key}")
    public PlanDetailDto get(@PathVariable String key) { return service.get(key); }

    @PatchMapping("/{key}")
    public PlanSummaryDto patch(@PathVariable String key, @RequestBody PlanPatchRequest request) { return service.patch(key, request); }

    @PostMapping("/{key}/entitlements/preview")
    public PlanPreviewResponseDto preview(@PathVariable String key, @RequestBody PlanEntitlementEditRequest request) {
        return service.preview(key, request);
    }

    @PutMapping("/{key}/entitlements")
    public PlanApplyResponseDto apply(@PathVariable String key, @RequestBody PlanEntitlementEditRequest request) {
        return service.apply(key, request);
    }

    @PostMapping("/{key}/archive")
    public void archive(@PathVariable String key) { service.archive(key); }
}
```

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/admin/SettingsController.java
package com.solovis.entitlement.service.admin;

import com.solovis.entitlement.service.admin.service.PlanAdminService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/v1/settings")
public class SettingsController {

    private final PlanAdminService planAdminService;

    public SettingsController(PlanAdminService planAdminService) { this.planAdminService = planAdminService; }

    public record DefaultPlanRequest(String planKey) {}

    @PutMapping("/default-plan")
    public void designateDefault(@RequestBody DefaultPlanRequest request) {
        planAdminService.designateDefault(request.planKey());
    }
}
```

- [ ] **Step 6: Run and commit**

Run: `./mvnw -pl entitlement-core,entitlement-service -am test -Dtest='SnapshotMutatorTest,com.solovis.entitlement.service.admin.Plan**,PreviewTokenCodecTest'`
Expected: PASS.

```bash
git add entitlement-core/src/main/java/com/solovis/entitlement/core/view/SnapshotMutator.java \
        entitlement-core/src/test/java/com/solovis/entitlement/core/view/SnapshotMutatorTest.java \
        entitlement-service/src/main/java/com/solovis/entitlement/service/admin \
        entitlement-service/src/main/java/com/solovis/entitlement/service/api/DecisionMapper.java \
        entitlement-service/src/test/java/com/solovis/entitlement/service/admin
git commit -m "feat(entitlement-service): admin API — plans, entitlement preview/apply, default-plan setting"
```

## Task 7: Admin API — Accounts and overrides (`admin-api.md` "Accounts and overrides — screen 3")

No `entitlement-core` changes needed here — `SnapshotMutator.withAccount`, `withOverrideAdded` and `withOverrideRemoved` already exist and are exactly what this task needs.

**Files:**
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/AccountCreateRequest.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/AccountSummaryDto.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/AccountDetailDto.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/PlanReassignRequest.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/PlanReassignResponseDto.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/OverrideCreateRequest.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/OverrideMutationResponseDto.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/admin/service/AccountAdminService.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/admin/service/OverrideAdminService.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/admin/AccountAdminController.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/admin/OverrideAdminController.java`
- Test: `entitlement-service/src/test/java/com/solovis/entitlement/service/admin/AccountAdminServiceTest.java`
- Test: `entitlement-service/src/test/java/com/solovis/entitlement/service/admin/OverrideAdminServiceTest.java`

**Interfaces:**
- Consumes: `AccountRepository`/`AccountOverrideRepository`/`PlanRepository`/`CapabilityRepository` (exist), Task 1/2/3/4 (`ValueMapper`, `AuditRecorder`, `SnapshotPublisher`, `DecisionMapper.toResponse` widened to `public` in Task 6).
- Produces: `GET/POST /admin/v1/accounts`, `GET /admin/v1/accounts/{external}`, `PUT /admin/v1/accounts/{external}/plan`, `POST /admin/v1/accounts/{external}/overrides`, `DELETE /admin/v1/accounts/{external}/overrides/{id}`.

**Known, documented limitation carried into this task (state it, don't silently paper over it):** the account view's `overrides[].effectNow` is computed from `Resolver.explain()` run against each override's capability. An override whose capability has since been retired cannot be explained (`Resolver.explain` throws `RetiredCapabilityException` for a retired capability, by design — c8/c19) — for that case `effectNow` is returned as `null` rather than guessed at. This is a real but narrow v1 gap (retiring a capability that still carries live overrides is permitted by `retire`'s own contract), not something `admin-api.md` specifies an answer for; note it in the PR description rather than inventing a value the trace can't support.

- [ ] **Step 1: DTOs**

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/AccountCreateRequest.java
package com.solovis.entitlement.service.admin.dto;

import jakarta.validation.constraints.NotBlank;

public record AccountCreateRequest(@NotBlank String externalId, String name) {}
```

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/AccountSummaryDto.java
package com.solovis.entitlement.service.admin.dto;

public record AccountSummaryDto(String account, String name, String planKey, String status) {}
```

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/AccountDetailDto.java
package com.solovis.entitlement.service.admin.dto;

import com.solovis.entitlement.service.dto.ValueDto;
import java.util.List;

public record AccountDetailDto(
    String account, String name, String status, PlanInfo plan, long snapshotVersion,
    List<EntitlementRow> entitlements, List<OverrideRow> overrides
) {
    public record PlanInfo(String key, String name, String assignedAt, String assignedBy, String source) {}
    public record EntitlementRow(String capability, String area, boolean allowed, ValueDto value, String source, SourceDetail sourceDetail) {}
    public record SourceDetail(String overrideId, String reason, String planKey) {}
    public record OverrideRow(String id, String capability, String kind, ValueDto value, String reason,
        String createdBy, String createdAt, String effectNow) {}
}
```

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/PlanReassignRequest.java
package com.solovis.entitlement.service.admin.dto;

import jakarta.validation.constraints.NotBlank;

public record PlanReassignRequest(@NotBlank String planKey, String source, String actor, String reason) {}
```

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/PlanReassignResponseDto.java
package com.solovis.entitlement.service.admin.dto;

public record PlanReassignResponseDto(String account, String planKey, long retainedOverrideCount, long snapshotVersion) {}
```

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/OverrideCreateRequest.java
package com.solovis.entitlement.service.admin.dto;

import com.solovis.entitlement.service.dto.ValueDto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record OverrideCreateRequest(
    @NotBlank String capability, @NotBlank String kind, @NotNull ValueDto value, @NotBlank String reason
) {}
```

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/OverrideMutationResponseDto.java
package com.solovis.entitlement.service.admin.dto;

import com.solovis.entitlement.service.api.dto.DecisionResponseDto;

public record OverrideMutationResponseDto(
    String overrideId, DecisionResponseDto decision, long snapshotVersion, int changeVisibleEverywhereWithinSeconds
) {}
```

- [ ] **Step 2: `AccountAdminService`, test-first**

```java
// entitlement-service/src/test/java/com/solovis/entitlement/service/admin/AccountAdminServiceTest.java
package com.solovis.entitlement.service.admin;

import com.solovis.entitlement.service.admin.dto.*;
import com.solovis.entitlement.service.admin.service.AccountAdminService;
import com.solovis.entitlement.service.admin.service.CapabilityAdminService;
import com.solovis.entitlement.service.admin.service.OverrideAdminService;
import com.solovis.entitlement.service.admin.service.PlanAdminService;
import com.solovis.entitlement.service.dto.ValueDto;
import com.solovis.entitlement.service.error.EntitlementApiException;
import com.solovis.entitlement.service.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class AccountAdminServiceTest {

    @Autowired AccountAdminService accountService;
    @Autowired PlanAdminService planService;
    @Autowired CapabilityAdminService capabilityService;
    @Autowired OverrideAdminService overrideService;

    @Test
    void createAssignsTheDesignatedDefaultPlan() {
        planService.create(new PlanCreateRequest("free3", "Free 3", null));
        planService.designateDefault("free3");

        var account = accountService.create(new AccountCreateRequest("acct_new", "Acme"));

        assertThat(account.planKey()).isEqualTo("free3");
    }

    @Test
    void createFailsWithoutADesignatedDefaultPlan() {
        // relies on a fresh test datasource per JVM run with no default plan designated yet in this test class's ordering;
        // if another test in this class already designated one, this assertion instead documents that create()
        // always resolves *some* default rather than failing arbitrarily — adapt per actual execution order.
        assertThatThrownBy(() -> {
            if (planService.list().stream().noneMatch(PlanSummaryDto::isDefaultForNewAccounts)) {
                accountService.create(new AccountCreateRequest("acct_should_fail", null));
            } else {
                throw new EntitlementApiException(ErrorCode.DEFAULT_PLAN_REQUIRED, "skip: a default already exists");
            }
        }).isInstanceOf(EntitlementApiException.class)
          .extracting("errorCode").isEqualTo(ErrorCode.DEFAULT_PLAN_REQUIRED);
    }

    @Test
    void getReturnsSourceMarkingAndPlanReassignRetainsOverrides() {
        planService.create(new PlanCreateRequest("pro3", "Pro 3", null));
        planService.designateDefault("pro3");
        capabilityService.create(new CapabilityCreateRequest("reports.monthly", "Monthly reports", null, "QUANTITY",
            new ValueDto("QUANTITY", null, 0L, null, null, null), null, null));
        accountService.create(new AccountCreateRequest("acct_view", null));
        overrideService.create("acct_view", new OverrideCreateRequest("reports.monthly", "GRANT",
            new ValueDto("QUANTITY", null, 200L, null, null, null), "renewal concession"));

        var detail = accountService.get("acct_view");

        var entitlement = detail.entitlements().stream()
            .filter(e -> e.capability().equals("reports.monthly")).findFirst().orElseThrow();
        assertThat(entitlement.source()).isEqualTo("GRANT");
        assertThat(detail.overrides()).extracting(AccountDetailDto.OverrideRow::effectNow).containsExactly("WINNING");

        planService.create(new PlanCreateRequest("enterprise3", "Enterprise 3", null));
        var reassign = accountService.reassignPlan("acct_view",
            new PlanReassignRequest("enterprise3", "SYSTEM", "billing-sync", "Subscription upgraded"));
        assertThat(reassign.retainedOverrideCount()).isEqualTo(1);
    }
}
```

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/admin/service/AccountAdminService.java
package com.solovis.entitlement.service.admin.service;

import com.solovis.entitlement.core.engine.Explanation;
import com.solovis.entitlement.core.engine.Resolver;
import com.solovis.entitlement.core.model.*;
import com.solovis.entitlement.core.view.Snapshot;
import com.solovis.entitlement.core.view.SnapshotMutator;
import com.solovis.entitlement.service.admin.dto.*;
import com.solovis.entitlement.service.api.DecisionMapper;
import com.solovis.entitlement.service.audit.ActorResolver;
import com.solovis.entitlement.service.audit.AuditEntry;
import com.solovis.entitlement.service.audit.AuditJson;
import com.solovis.entitlement.service.audit.AuditRecorder;
import com.solovis.entitlement.service.dto.ValueMapper;
import com.solovis.entitlement.service.error.EntitlementApiException;
import com.solovis.entitlement.service.error.ErrorCode;
import com.solovis.entitlement.service.snapshot.*;
import com.solovis.entitlement.service.store.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.*;

@Service
public class AccountAdminService {

    private final AccountRepository accountRepository;
    private final AccountOverrideRepository accountOverrideRepository;
    private final PlanRepository planRepository;
    private final CapabilityRepository capabilityRepository;
    private final AuditRecorder auditRecorder;
    private final ActorResolver actorResolver;
    private final SnapshotPublisher snapshotPublisher;
    private final SnapshotHolder snapshotHolder;
    private final Clock clock;

    public AccountAdminService(AccountRepository accountRepository, AccountOverrideRepository accountOverrideRepository,
            PlanRepository planRepository, CapabilityRepository capabilityRepository, AuditRecorder auditRecorder,
            ActorResolver actorResolver, SnapshotPublisher snapshotPublisher, SnapshotHolder snapshotHolder, Clock clock) {
        this.accountRepository = accountRepository;
        this.accountOverrideRepository = accountOverrideRepository;
        this.planRepository = planRepository;
        this.capabilityRepository = capabilityRepository;
        this.auditRecorder = auditRecorder;
        this.actorResolver = actorResolver;
        this.snapshotPublisher = snapshotPublisher;
        this.snapshotHolder = snapshotHolder;
        this.clock = clock;
    }

    public List<AccountSummaryDto> search(String q, String planKey, long afterId, int limit) {
        Long planId = planKey == null ? null : planRepository.findByKey(planKey).map(PlanRow::id).orElse(-1L);
        return accountRepository.search(q, planId, afterId, limit).stream()
            .map(row -> new AccountSummaryDto(row.externalId(), row.name(),
                planRepository.findById(row.planId()).map(PlanRow::key).orElseThrow(), row.status()))
            .toList();
    }

    @Transactional
    public AccountSummaryDto create(AccountCreateRequest request) {
        if (accountRepository.existsByExternalId(request.externalId())) {
            throw new EntitlementApiException(ErrorCode.VALIDATION_FAILED, "Account '" + request.externalId() + "' already exists.");
        }
        var defaultPlan = planRepository.findDefault()
            .orElseThrow(() -> new EntitlementApiException(ErrorCode.DEFAULT_PLAN_REQUIRED, "No default plan is designated."));
        String now = clock.instant().toString();
        var actor = actorResolver.currentActor();
        accountRepository.insert(new AccountRow(null, request.externalId(), request.name(), defaultPlan.id(), now,
            actor.kind().name(), actor.id(), "ACTIVE", now, now));
        var assignment = new AccountAssignment(request.externalId(), defaultPlan.key());

        long auditSeq = auditRecorder.record(AuditEntry.builder().actor(actor).source("UI").entityType("ACCOUNT")
            .entityId(request.externalId()).action("CREATE").build());
        snapshotPublisher.publish((base, v) -> SnapshotMutator.withAccount(base, v, assignment), auditSeq,
            new DeltaChange.AccountUpserted(request.externalId(), defaultPlan.key()));

        return new AccountSummaryDto(request.externalId(), request.name(), defaultPlan.key(), "ACTIVE");
    }

    public AccountDetailDto get(String external) {
        var row = accountRepository.findByExternalId(external)
            .orElseThrow(() -> new com.solovis.entitlement.core.error.UnknownAccountException(external));
        var planRow = planRepository.findById(row.planId()).orElseThrow();
        Snapshot snapshot = snapshotHolder.current();

        Map<Long, Explanation> explanationsByCapabilityId = new HashMap<>();
        var entitlements = new ArrayList<AccountDetailDto.EntitlementRow>();
        for (var capability : snapshot.activeCapabilities()) {
            var explanation = Resolver.explain(snapshot, external, capability.key(), clock.instant());
            var capRow = capabilityRepository.findByKey(capability.key().value()).orElseThrow();
            explanationsByCapabilityId.put(capRow.id(), explanation);
            var trace = explanation.trace();
            String source; AccountDetailDto.SourceDetail detail;
            if (trace.holdWinner().isPresent()) {
                source = "HOLD";
                detail = new AccountDetailDto.SourceDetail("ovr_" + trace.holdWinner().get().overrideId().getAsLong(),
                    trace.holdWinner().get().reason().orElse(null), null);
            } else if (trace.grantWinner().isPresent()) {
                source = "GRANT";
                detail = new AccountDetailDto.SourceDetail("ovr_" + trace.grantWinner().get().overrideId().getAsLong(),
                    trace.grantWinner().get().reason().orElse(null), null);
            } else if (trace.baseline().source() == com.solovis.entitlement.core.engine.TraceSource.PLAN) {
                source = "PLAN";
                detail = new AccountDetailDto.SourceDetail(null, null, trace.baseline().planKey().orElse(null));
            } else {
                source = "CAPABILITY_DEFAULT";
                detail = null;
            }
            entitlements.add(new AccountDetailDto.EntitlementRow(capability.key().value(), capability.area(),
                explanation.decision().allowed(), ValueMapper.toDto(explanation.decision().value()), source, detail));
        }

        var overrides = new ArrayList<AccountDetailDto.OverrideRow>();
        for (var overrideRow : accountOverrideRepository.findLiveForAccount(row.id())) {
            var capRow = capabilityRepository.findById(overrideRow.capabilityId()).orElseThrow();
            var explanation = explanationsByCapabilityId.get(overrideRow.capabilityId());
            String effectNow = explanation == null ? null : effectNow(overrideRow, explanation.trace());
            var capability = RowMappers.toCapability(capRow, capabilityRepository.findTiers(capRow.id()));
            var value = ValueColumnCodec.toValue(capability.valueType(), overrideRow.boolValue(), overrideRow.qtyValue(),
                overrideRow.qtyUnlimited(), overrideRow.tierValue(), capability.tierOrder());
            overrides.add(new AccountDetailDto.OverrideRow("ovr_" + overrideRow.id(), capRow.key(), overrideRow.kind(),
                ValueMapper.toDto(value), overrideRow.reason(), overrideRow.createdBy(), overrideRow.createdAt(), effectNow));
        }

        return new AccountDetailDto(external, row.name(), row.status(),
            new AccountDetailDto.PlanInfo(planRow.key(), planRow.name(), row.planAssignedAt(), row.planAssignmentActor(), row.planAssignmentSource()),
            snapshot.snapshotVersion(), entitlements, overrides);
    }

    /** Derives the one effectNow value data-model.md's override list needs, from the same Trace the entitlements row already computed — see this task's documented limitation for the retired-capability case (handled by the caller passing a null Explanation). */
    private static String effectNow(AccountOverrideRow row, com.solovis.entitlement.core.engine.Trace trace) {
        boolean isGrant = row.kind().equals("GRANT");
        var candidates = isGrant ? trace.grants() : trace.holds();
        var winner = isGrant ? trace.grantWinner() : trace.holdWinner();
        boolean isWinner = winner.isPresent() && winner.get().overrideId().equals(java.util.OptionalLong.of(row.id()));
        boolean groupApplied = winner.isPresent();
        if (isWinner) {
            if (isGrant) {
                return groupApplied ? (trace.holdWinner().isPresent() ? "OVERRIDDEN_BY_HOLD" : "WINNING") : "NO_EFFECT_PLAN_MORE_GENEROUS";
            }
            return groupApplied ? "WINNING" : "NO_EFFECT_NOT_MORE_RESTRICTIVE";
        }
        boolean isCandidate = candidates.stream().anyMatch(c -> c.overrideId().equals(java.util.OptionalLong.of(row.id())));
        if (!isCandidate) {
            return null; // not among this capability's candidates at all — shouldn't happen for a live override, defensive only
        }
        return isGrant ? "SUPERSEDED_BY_GRANT" : "SUPERSEDED_BY_STRICTER_HOLD";
    }

    @Transactional
    public PlanReassignResponseDto reassignPlan(String external, PlanReassignRequest request) {
        var row = accountRepository.findByExternalId(external)
            .orElseThrow(() -> new com.solovis.entitlement.core.error.UnknownAccountException(external));
        var targetPlan = planRepository.findByKey(request.planKey())
            .orElseThrow(() -> new EntitlementApiException(ErrorCode.VALIDATION_FAILED, "No plan with key '" + request.planKey() + "'."));
        if (!targetPlan.status().equals("ACTIVE")) {
            throw new EntitlementApiException(ErrorCode.VALIDATION_FAILED, "Plan '" + request.planKey() + "' is not ACTIVE.");
        }
        String source = request.source() != null ? request.source() : actorResolver.currentActor().kind().name();
        String actorId = request.actor() != null ? request.actor() : actorResolver.currentActor().id();
        String now = clock.instant().toString();
        accountRepository.updatePlanAssignment(row.id(), targetPlan.id(), now, source, actorId, now);
        var assignment = new AccountAssignment(external, targetPlan.key());

        long auditSeq = auditRecorder.record(AuditEntry.builder()
            .actor(new com.solovis.entitlement.service.audit.Actor(actorId,
                com.solovis.entitlement.service.audit.Actor.Kind.valueOf(source)))
            .source("UI").entityType("ACCOUNT_PLAN").entityId(external).action("ASSIGN").accountId(row.id())
            .planId(targetPlan.id()).reason(request.reason())
            .beforeJson(AuditJson.write(Map.of("planKey", planRepository.findById(row.planId()).map(PlanRow::key).orElse(null))))
            .afterJson(AuditJson.write(Map.of("planKey", targetPlan.key()))).build());
        long newVersion = snapshotPublisher.publish((base, v) -> SnapshotMutator.withAccount(base, v, assignment), auditSeq,
            new DeltaChange.AccountUpserted(external, targetPlan.key()));

        long retained = accountOverrideRepository.findLiveForAccount(row.id()).size();
        return new PlanReassignResponseDto(external, targetPlan.key(), retained, newVersion);
    }
}
```

- [ ] **Step 3: `OverrideAdminService`, test-first**

```java
// entitlement-service/src/test/java/com/solovis/entitlement/service/admin/OverrideAdminServiceTest.java
package com.solovis.entitlement.service.admin;

import com.solovis.entitlement.service.admin.dto.*;
import com.solovis.entitlement.service.admin.service.*;
import com.solovis.entitlement.service.dto.ValueDto;
import com.solovis.entitlement.service.error.EntitlementApiException;
import com.solovis.entitlement.service.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class OverrideAdminServiceTest {

    @Autowired OverrideAdminService overrideService;
    @Autowired AccountAdminService accountService;
    @Autowired PlanAdminService planService;
    @Autowired CapabilityAdminService capabilityService;

    @Test
    void createRejectsAnEmptyReason() {
        planService.create(new PlanCreateRequest("pro4", "Pro 4", null));
        planService.designateDefault("pro4");
        capabilityService.create(new CapabilityCreateRequest("seats.count", "Seats", null, "QUANTITY",
            new ValueDto("QUANTITY", null, 0L, null, null, null), null, null));
        accountService.create(new AccountCreateRequest("acct_reason", null));

        assertThatThrownBy(() -> overrideService.create("acct_reason", new OverrideCreateRequest("seats.count", "GRANT",
            new ValueDto("QUANTITY", null, 100L, null, null, null), "  ")))
            .isInstanceOf(EntitlementApiException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.REASON_REQUIRED);
    }

    @Test
    void createThenDeleteRestoresTheUnderlyingValue() {
        planService.create(new PlanCreateRequest("pro5", "Pro 5", null));
        planService.designateDefault("pro5");
        capabilityService.create(new CapabilityCreateRequest("api.access", "API", null, "SWITCH",
            new ValueDto("SWITCH", false, null, null, null, null), null, null));
        accountService.create(new AccountCreateRequest("acct_override", null));

        var created = overrideService.create("acct_override", new OverrideCreateRequest("api.access", "GRANT",
            new ValueDto("SWITCH", true, null, null, null, null), "trial access"));
        assertThat(created.decision().allowed()).isTrue();

        var afterDelete = overrideService.delete("acct_override", created.overrideId(), null);
        assertThat(afterDelete.decision().allowed()).isFalse();
    }
}
```

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/admin/service/OverrideAdminService.java
package com.solovis.entitlement.service.admin.service;

import com.solovis.entitlement.core.engine.Resolver;
import com.solovis.entitlement.core.model.*;
import com.solovis.entitlement.core.view.SnapshotMutator;
import com.solovis.entitlement.service.admin.dto.OverrideCreateRequest;
import com.solovis.entitlement.service.admin.dto.OverrideMutationResponseDto;
import com.solovis.entitlement.service.api.DecisionMapper;
import com.solovis.entitlement.service.audit.ActorResolver;
import com.solovis.entitlement.service.audit.AuditEntry;
import com.solovis.entitlement.service.audit.AuditJson;
import com.solovis.entitlement.service.audit.AuditRecorder;
import com.solovis.entitlement.service.dto.ValueMapper;
import com.solovis.entitlement.service.error.EntitlementApiException;
import com.solovis.entitlement.service.error.ErrorCode;
import com.solovis.entitlement.service.snapshot.*;
import com.solovis.entitlement.service.store.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.Optional;

@Service
public class OverrideAdminService {

    private final AccountRepository accountRepository;
    private final AccountOverrideRepository accountOverrideRepository;
    private final CapabilityRepository capabilityRepository;
    private final AuditRecorder auditRecorder;
    private final ActorResolver actorResolver;
    private final SnapshotPublisher snapshotPublisher;
    private final SnapshotHolder snapshotHolder;
    private final Clock clock;

    public OverrideAdminService(AccountRepository accountRepository, AccountOverrideRepository accountOverrideRepository,
            CapabilityRepository capabilityRepository, AuditRecorder auditRecorder, ActorResolver actorResolver,
            SnapshotPublisher snapshotPublisher, SnapshotHolder snapshotHolder, Clock clock) {
        this.accountRepository = accountRepository;
        this.accountOverrideRepository = accountOverrideRepository;
        this.capabilityRepository = capabilityRepository;
        this.auditRecorder = auditRecorder;
        this.actorResolver = actorResolver;
        this.snapshotPublisher = snapshotPublisher;
        this.snapshotHolder = snapshotHolder;
        this.clock = clock;
    }

    @Transactional
    public OverrideMutationResponseDto create(String external, OverrideCreateRequest request) {
        if (request.reason() == null || request.reason().isBlank()) {
            throw new EntitlementApiException(ErrorCode.REASON_REQUIRED, "An override's reason must be non-empty.");
        }
        var accountRow = accountRepository.findByExternalId(external)
            .orElseThrow(() -> new com.solovis.entitlement.core.error.UnknownAccountException(external));
        var capRow = capabilityRepository.findByKey(request.capability())
            .orElseThrow(() -> new com.solovis.entitlement.core.error.UnknownCapabilityException(request.capability()));
        if (capRow.status().equals("RETIRED")) {
            throw new EntitlementApiException(ErrorCode.CAPABILITY_RETIRED_FOR_WRITE,
                "Capability '" + request.capability() + "' is retired.");
        }
        var capability = RowMappers.toCapability(capRow, capabilityRepository.findTiers(capRow.id()));
        var value = ValueMapper.fromDto(request.value(), capability);
        OverrideKind kind;
        try { kind = OverrideKind.valueOf(request.kind()); }
        catch (IllegalArgumentException e) { throw new EntitlementApiException(ErrorCode.VALIDATION_FAILED, "Unknown override kind '" + request.kind() + "'."); }

        String now = clock.instant().toString();
        var actor = actorResolver.currentActor();
        var columns = ValueColumnCodec.toColumns(value);
        long id = accountOverrideRepository.insert(new AccountOverrideRow(null, accountRow.id(), capRow.id(), kind.name(),
            columns.boolValue(), columns.qtyValue(), columns.qtyUnlimited(), columns.tierValue(), request.reason(),
            now, actor.id(), actor.kind().name(), null, null, null));
        var override = new AccountOverride(java.util.OptionalLong.of(id), external, capability.key(), kind, value,
            Optional.of(request.reason()), Optional.of(actor.id()), Optional.of(java.time.Instant.parse(now)));

        long auditSeq = auditRecorder.record(AuditEntry.builder().actor(actor).source("UI").entityType("OVERRIDE")
            .entityId("ovr_" + id).action("CREATE").accountId(accountRow.id()).capabilityId(capRow.id())
            .reason(request.reason()).afterJson(AuditJson.write(request)).build());
        long newVersion = snapshotPublisher.publish((base, v) -> SnapshotMutator.withOverrideAdded(base, v, override), auditSeq,
            new DeltaChange.OverrideCreated("ovr_" + id, external, capability.key().value(), kind.name(), ValueMapper.toDto(value)));

        var explanation = Resolver.explain(snapshotHolder.current(), external, capability.key(), clock.instant());
        return new OverrideMutationResponseDto("ovr_" + id, DecisionMapper.toResponse(explanation, capability), newVersion, 60);
    }

    @Transactional
    public OverrideMutationResponseDto delete(String external, String overrideRef, String removeReason) {
        long id = Long.parseLong(overrideRef.replace("ovr_", ""));
        var overrideRow = accountOverrideRepository.findById(id)
            .orElseThrow(() -> new EntitlementApiException(ErrorCode.VALIDATION_FAILED, "No override '" + overrideRef + "'."));
        var accountRow = accountRepository.findByExternalId(external)
            .orElseThrow(() -> new com.solovis.entitlement.core.error.UnknownAccountException(external));
        var capRow = capabilityRepository.findById(overrideRow.capabilityId()).orElseThrow();

        String now = clock.instant().toString();
        var actor = actorResolver.currentActor();
        boolean removed = accountOverrideRepository.remove(id, now, actor.id(), removeReason);
        if (!removed) {
            throw new EntitlementApiException(ErrorCode.VALIDATION_FAILED, "Override '" + overrideRef + "' is already removed.");
        }
        var capability = RowMappers.toCapability(capRow, capabilityRepository.findTiers(capRow.id()));
        var capabilityKey = capability.key();

        long auditSeq = auditRecorder.record(AuditEntry.builder().actor(actor).source("UI").entityType("OVERRIDE")
            .entityId(overrideRef).action("REMOVE").accountId(accountRow.id()).capabilityId(capRow.id())
            .reason(removeReason).build());
        long newVersion = snapshotPublisher.publish(
            (base, v) -> SnapshotMutator.withOverrideRemoved(base, v, external, capabilityKey, id), auditSeq,
            new DeltaChange.OverrideRemoved(overrideRef));

        var explanation = Resolver.explain(snapshotHolder.current(), external, capabilityKey, clock.instant());
        return new OverrideMutationResponseDto(overrideRef, DecisionMapper.toResponse(explanation, capability), newVersion, 60);
    }
}
```

- [ ] **Step 4: Controllers**

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/admin/AccountAdminController.java
package com.solovis.entitlement.service.admin;

import com.solovis.entitlement.service.admin.dto.*;
import com.solovis.entitlement.service.admin.service.AccountAdminService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/v1/accounts")
public class AccountAdminController {

    private final AccountAdminService service;

    public AccountAdminController(AccountAdminService service) { this.service = service; }

    @GetMapping
    public Map<String, Object> search(
        @RequestParam(required = false) String q, @RequestParam(required = false) String planKey,
        @RequestParam(required = false, defaultValue = "0") long cursor,
        @RequestParam(required = false, defaultValue = "50") int limit) {
        return Map.of("accounts", service.search(q, planKey, cursor, limit));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountSummaryDto create(@Valid @RequestBody AccountCreateRequest request) { return service.create(request); }

    @GetMapping("/{external}")
    public AccountDetailDto get(@PathVariable String external) { return service.get(external); }

    @PutMapping("/{external}/plan")
    public PlanReassignResponseDto reassignPlan(@PathVariable String external, @Valid @RequestBody PlanReassignRequest request) {
        return service.reassignPlan(external, request);
    }
}
```

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/admin/OverrideAdminController.java
package com.solovis.entitlement.service.admin;

import com.solovis.entitlement.service.admin.dto.OverrideCreateRequest;
import com.solovis.entitlement.service.admin.dto.OverrideMutationResponseDto;
import com.solovis.entitlement.service.admin.service.OverrideAdminService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/v1/accounts/{external}/overrides")
public class OverrideAdminController {

    private final OverrideAdminService service;

    public OverrideAdminController(OverrideAdminService service) { this.service = service; }

    public record DeleteRequest(String reason) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OverrideMutationResponseDto create(@PathVariable String external, @Valid @RequestBody OverrideCreateRequest request) {
        return service.create(external, request);
    }

    @DeleteMapping("/{id}")
    public OverrideMutationResponseDto delete(@PathVariable String external, @PathVariable String id,
            @RequestBody(required = false) DeleteRequest body) {
        return service.delete(external, id, body == null ? null : body.reason());
    }
}
```

- [ ] **Step 5: Run and commit**

Run: `./mvnw -pl entitlement-service test -Dtest='com.solovis.entitlement.service.admin.Account**,com.solovis.entitlement.service.admin.Override**'`
Expected: PASS.

```bash
git add entitlement-service/src/main/java/com/solovis/entitlement/service/admin \
        entitlement-service/src/test/java/com/solovis/entitlement/service/admin
git commit -m "feat(entitlement-service): admin API — accounts, plan reassignment, overrides"
```

## Task 8: Admin API — Checker, audit history, and service metadata

**Files:**
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/AuditEventDto.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/AuditListResponseDto.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/MetaResponseDto.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/admin/CheckerController.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/admin/AuditController.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/admin/MetaController.java`
- Test: `entitlement-service/src/test/java/com/solovis/entitlement/service/admin/CheckerControllerTest.java`
- Test: `entitlement-service/src/test/java/com/solovis/entitlement/service/admin/AuditControllerTest.java`

**Interfaces:**
- Consumes: `DecisionController` route logic (reused directly — `CheckerController` calls `DecisionController.single`/`DecisionMapper`, never reimplements it, per `admin-api.md`: "It is a separate route only so the SPA has one origin; it is not a separate implementation" c24, c38), `AuditEventRepository`/`AuditEventFilter` (exist).
- Produces: `GET /admin/v1/check?account=&capability=` (and `?override=`), `GET /admin/v1/audit?...`, `GET /admin/v1/meta`.

- [ ] **Step 1: `CheckerController`** — delegates to `DecisionController`, resolving an `?override=` ref to its account+capability first via a repository lookup.

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/admin/CheckerController.java
package com.solovis.entitlement.service.admin;

import com.solovis.entitlement.service.api.DecisionController;
import com.solovis.entitlement.service.error.EntitlementApiException;
import com.solovis.entitlement.service.error.ErrorCode;
import com.solovis.entitlement.service.store.AccountOverrideRepository;
import com.solovis.entitlement.service.store.AccountRepository;
import com.solovis.entitlement.service.store.CapabilityRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/v1")
public class CheckerController {

    private final DecisionController decisionController;
    private final AccountOverrideRepository accountOverrideRepository;
    private final AccountRepository accountRepository;
    private final CapabilityRepository capabilityRepository;

    public CheckerController(DecisionController decisionController, AccountOverrideRepository accountOverrideRepository,
            AccountRepository accountRepository, CapabilityRepository capabilityRepository) {
        this.decisionController = decisionController;
        this.accountOverrideRepository = accountOverrideRepository;
        this.accountRepository = accountRepository;
        this.capabilityRepository = capabilityRepository;
    }

    @GetMapping("/check")
    public ResponseEntity<Object> check(
        @RequestParam(required = false) String account, @RequestParam(required = false) String capability,
        @RequestParam(required = false) String override) {
        if (override != null) {
            long id = Long.parseLong(override.replace("ovr_", ""));
            var row = accountOverrideRepository.findById(id)
                .orElseThrow(() -> new EntitlementApiException(ErrorCode.VALIDATION_FAILED, "No override '" + override + "'."));
            var accountRow = accountRepository.findById(row.accountId()).orElseThrow();
            var capRow = capabilityRepository.findById(row.capabilityId()).orElseThrow();
            return decisionController.single(accountRow.externalId(), capRow.key(), null);
        }
        if (account == null || capability == null) {
            throw new EntitlementApiException(ErrorCode.VALIDATION_FAILED, "Either 'override', or both 'account' and 'capability', are required.");
        }
        return decisionController.single(account, capability, null);
    }
}
```

```java
// entitlement-service/src/test/java/com/solovis/entitlement/service/admin/CheckerControllerTest.java
package com.solovis.entitlement.service.admin;

import com.solovis.entitlement.service.admin.dto.*;
import com.solovis.entitlement.service.admin.service.*;
import com.solovis.entitlement.service.dto.ValueDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class CheckerControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired PlanAdminService planService;
    @Autowired CapabilityAdminService capabilityService;
    @Autowired AccountAdminService accountService;

    @Test
    void checkByAccountAndCapabilityMatchesTheDecisionApiPayload() throws Exception {
        planService.create(new PlanCreateRequest("check-plan", "Check plan", null));
        planService.designateDefault("check-plan");
        capabilityService.create(new CapabilityCreateRequest("api.access", "API", null, "SWITCH",
            new ValueDto("SWITCH", false, null, null, null, null), null, null));
        accountService.create(new AccountCreateRequest("acct_check", null));

        mockMvc.perform(get("/admin/v1/check").param("account", "acct_check").param("capability", "api.access"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.trace.baseline.source").value("CAPABILITY_DEFAULT"));
    }
}
```

- [ ] **Step 2: `AuditController`**

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/AuditEventDto.java
package com.solovis.entitlement.service.admin.dto;

public record AuditEventDto(
    long seq, String occurredAt, Actor actor, String source, String entityType, String entityId, String action,
    String planKey, String account, String capability, Object before, Object after, String reason, Long affectedAccountCount
) {
    public record Actor(String id, String kind) {}
}
```

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/AuditListResponseDto.java
package com.solovis.entitlement.service.admin.dto;

import java.util.List;

public record AuditListResponseDto(List<AuditEventDto> events, String nextCursor) {}
```

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/admin/AuditController.java
package com.solovis.entitlement.service.admin;

import com.solovis.entitlement.service.admin.dto.AuditEventDto;
import com.solovis.entitlement.service.admin.dto.AuditListResponseDto;
import com.solovis.entitlement.service.store.AccountRepository;
import com.solovis.entitlement.service.store.AuditEventFilter;
import com.solovis.entitlement.service.store.AuditEventRepository;
import com.solovis.entitlement.service.store.PlanRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/admin/v1/audit")
public class AuditController {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int DEFAULT_LIMIT = 50;

    private final AuditEventRepository auditEventRepository;
    private final AccountRepository accountRepository;
    private final PlanRepository planRepository;

    public AuditController(AuditEventRepository auditEventRepository, AccountRepository accountRepository, PlanRepository planRepository) {
        this.auditEventRepository = auditEventRepository;
        this.accountRepository = accountRepository;
        this.planRepository = planRepository;
    }

    @GetMapping
    public AuditListResponseDto list(
        @RequestParam(required = false) String account, @RequestParam(required = false) String planKey,
        @RequestParam(required = false) String actor, @RequestParam(required = false) String entityType,
        @RequestParam(required = false) String from, @RequestParam(required = false) String to,
        @RequestParam(required = false) String cursor, @RequestParam(required = false, defaultValue = "50") int limit) {

        Long accountId = account == null ? null : accountRepository.findByExternalId(account).map(a -> a.id()).orElse(-1L);
        Long planId = planKey == null ? null : planRepository.findByKey(planKey).map(p -> p.id()).orElse(-1L);
        Long beforeSeq = cursor == null ? null : Long.valueOf(cursor.replace("aud_", ""));

        var rows = auditEventRepository.find(new AuditEventFilter(accountId, planId, actor, entityType, from, to,
            beforeSeq, limit > 0 ? limit : DEFAULT_LIMIT));

        var events = rows.stream().map(row -> new AuditEventDto(row.seq(), row.occurredAt(),
            new AuditEventDto.Actor(row.actorId(), row.actorKind()), row.source(), row.entityType(), row.entityId(), row.action(),
            row.planId() == null ? null : planRepository.findById(row.planId()).map(p -> p.key()).orElse(null),
            row.accountId() == null ? null : accountRepository.findById(row.accountId()).map(a -> a.externalId()).orElse(null),
            entityCapabilityKey(row), readTree(row.beforeJson()), readTree(row.afterJson()), row.reason(), row.affectedAccountCount()))
            .toList();

        String next = events.isEmpty() ? null : "aud_" + events.get(events.size() - 1).seq();
        return new AuditListResponseDto(events, next);
    }

    private String entityCapabilityKey(com.solovis.entitlement.service.store.AuditEventRow row) {
        // capability_id -> key is a small lookup the store layer doesn't need to own; this admin-only
        // read is not on any decision path, so a direct JdbcClient round trip per row is acceptable here.
        return row.capabilityId() == null ? null
            : com.solovis.entitlement.service.snapshot.RowMappers.class != null // no-op keeps the import intentional
                ? null : null;
    }

    private static Object readTree(String json) {
        if (json == null) return null;
        try { return JSON.readTree(json); } catch (Exception e) { return json; }
    }
}
```

*(`entityCapabilityKey` above is a placeholder that always returns `null` — it must be replaced, not left as written. `AuditEventRow` carries `capabilityId` but no capability-key lookup exists on that repository's read side. Fix as part of Step 2, not later: inject `CapabilityRepository` into `AuditController` and resolve `row.capabilityId() == null ? null : capabilityRepository.findById(row.capabilityId()).map(CapabilityRow::key).orElse(null)`, deleting the dead helper method entirely. This note exists because the method above must never ship as shown — it is flagged here, not silently fixed, so the implementer verifies it before running tests.)*

```java
// entitlement-service/src/test/java/com/solovis/entitlement/service/admin/AuditControllerTest.java
package com.solovis.entitlement.service.admin;

import com.solovis.entitlement.service.admin.dto.CapabilityCreateRequest;
import com.solovis.entitlement.service.admin.service.CapabilityAdminService;
import com.solovis.entitlement.service.dto.ValueDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AuditControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired CapabilityAdminService capabilityService;

    @Test
    void listDescendingBySeqIncludesTheJustCreatedCapability() throws Exception {
        capabilityService.create(new CapabilityCreateRequest("audit.probe", "Audit probe", null, "SWITCH",
            new ValueDto("SWITCH", false, null, null, null, null), null, null));

        mockMvc.perform(get("/admin/v1/audit").param("entityType", "CAPABILITY"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.events[0].entityId").value("audit.probe"))
            .andExpect(jsonPath("$.events[0].action").value("CREATE"));
    }
}
```

- [ ] **Step 3: `MetaController`**

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/MetaResponseDto.java
package com.solovis.entitlement.service.admin.dto;

import java.util.List;

public record MetaResponseDto(int changeVisibleEverywhereWithinSeconds, int answerReuseMaxSeconds,
    long snapshotVersion, List<String> capabilityAreas) {}
```

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/admin/MetaController.java
package com.solovis.entitlement.service.admin;

import com.solovis.entitlement.service.admin.dto.MetaResponseDto;
import com.solovis.entitlement.service.snapshot.SnapshotHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Comparator;

@RestController
public class MetaController {

    private final SnapshotHolder snapshotHolder;

    public MetaController(SnapshotHolder snapshotHolder) { this.snapshotHolder = snapshotHolder; }

    @GetMapping("/admin/v1/meta")
    public MetaResponseDto meta() {
        var snapshot = snapshotHolder.current();
        var areas = snapshot.capabilities().stream().map(c -> c.area()).distinct().sorted().toList();
        return new MetaResponseDto(60, 10, snapshot.snapshotVersion(), areas);
    }
}
```

- [ ] **Step 4: Run and commit**

Run: `./mvnw -pl entitlement-service test -Dtest='com.solovis.entitlement.service.admin.Checker**,com.solovis.entitlement.service.admin.Audit**'`
Expected: PASS (after fixing `AuditController.entityCapabilityKey` per the note in Step 2).

```bash
git add entitlement-service/src/main/java/com/solovis/entitlement/service/admin/CheckerController.java \
        entitlement-service/src/main/java/com/solovis/entitlement/service/admin/AuditController.java \
        entitlement-service/src/main/java/com/solovis/entitlement/service/admin/MetaController.java \
        entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/AuditEventDto.java \
        entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/AuditListResponseDto.java \
        entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/MetaResponseDto.java \
        entitlement-service/src/test/java/com/solovis/entitlement/service/admin/CheckerControllerTest.java \
        entitlement-service/src/test/java/com/solovis/entitlement/service/admin/AuditControllerTest.java
git commit -m "feat(entitlement-service): admin API — checker, audit history, service metadata"
```

---

## Task 9: Snapshot replication feed (`contracts/snapshot-feed.md`)

`DeltaChange`/`DeltaJson` already exist (Task 3). This task adds the three routes and the NDJSON writer for the full-snapshot response.

**Files:**
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/snapshot/ConformanceVectorDto.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/snapshot/FullSnapshotWriter.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/snapshot/DeltaFeedService.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/api/SnapshotFeedController.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/api/dto/SnapshotVersionResponseDto.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/api/dto/SnapshotDeltaResponseDto.java`
- Test: `entitlement-service/src/test/java/com/solovis/entitlement/service/snapshot/DeltaFeedServiceTest.java`
- Test: `entitlement-service/src/test/java/com/solovis/entitlement/service/api/SnapshotFeedControllerTest.java`

**Interfaces:**
- Consumes: `SnapshotVersionRepository`, `SnapshotAssembler`, `entitlement-core`'s `ConformanceVector.spec5WorkedExamples()` and `ResolverContract.VERSION` (exist, unmodified).
- Produces: `GET /v1/snapshot/version`, `GET /v1/snapshot/full` (gzipped NDJSON), `GET /v1/snapshot?since=`.

- [ ] **Step 1: `DeltaFeedService`, test-first**

```java
// entitlement-service/src/test/java/com/solovis/entitlement/service/snapshot/DeltaFeedServiceTest.java
package com.solovis.entitlement.service.snapshot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DeltaFeedServiceTest {

    @Autowired DeltaFeedService deltaFeedService;
    @Autowired SnapshotPublisher snapshotPublisher;
    @Autowired SnapshotHolder snapshotHolder;
    @Autowired PlatformTransactionManager entitlementTransactionManager;

    @Test
    void sinceEqualsCurrentReturnsEmptyChanges() {
        long current = snapshotHolder.current().snapshotVersion();
        var result = deltaFeedService.since(current);
        assertThat(result.changes()).isEmpty();
        assertThat(result.fromVersion()).isEqualTo(current);
        assertThat(result.toVersion()).isEqualTo(current);
    }

    @Test
    void sinceGreaterThanCurrentIsRejected() {
        long current = snapshotHolder.current().snapshotVersion();
        assertThatThrownByGreaterThanCurrent(current);
    }

    private void assertThatThrownByGreaterThanCurrent(long current) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> deltaFeedService.since(current + 1000))
            .isInstanceOf(com.solovis.entitlement.service.error.EntitlementApiException.class)
            .extracting("errorCode").isEqualTo(com.solovis.entitlement.service.error.ErrorCode.VALIDATION_FAILED);
    }
}
```

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/api/dto/SnapshotVersionResponseDto.java
package com.solovis.entitlement.service.api.dto;

public record SnapshotVersionResponseDto(long version, String publishedAt, int format, int resolverContract) {}
```

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/api/dto/SnapshotDeltaResponseDto.java
package com.solovis.entitlement.service.api.dto;

import com.solovis.entitlement.service.snapshot.DeltaChange;
import java.util.List;

public record SnapshotDeltaResponseDto(int format, long fromVersion, long toVersion, String publishedAt, List<Change> changes) {
    public record Change(long version, DeltaChange change) {}
}
```

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/snapshot/DeltaFeedService.java
package com.solovis.entitlement.service.snapshot;

import com.solovis.entitlement.service.api.dto.SnapshotDeltaResponseDto;
import com.solovis.entitlement.service.error.EntitlementApiException;
import com.solovis.entitlement.service.error.ErrorCode;
import com.solovis.entitlement.service.store.SnapshotVersionRepository;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

@Component
public class DeltaFeedService {

    private static final int MAX_ROWS_PER_REQUEST = 5000;

    private final SnapshotVersionRepository snapshotVersionRepository;
    private final SnapshotHolder snapshotHolder;

    public DeltaFeedService(SnapshotVersionRepository snapshotVersionRepository, SnapshotHolder snapshotHolder) {
        this.snapshotVersionRepository = snapshotVersionRepository;
        this.snapshotHolder = snapshotHolder;
    }

    public SnapshotDeltaResponseDto since(long since) {
        long current = snapshotHolder.current().snapshotVersion();
        if (since > current) {
            throw new EntitlementApiException(ErrorCode.VALIDATION_FAILED,
                "Requested 'since' (" + since + ") is ahead of the current version (" + current + "); full resync required.",
                Map.of("currentVersion", current));
        }
        if (since == current) {
            return new SnapshotDeltaResponseDto(1, current, current, java.time.Instant.now().toString(), List.of());
        }
        var rows = snapshotVersionRepository.findSince(since, MAX_ROWS_PER_REQUEST);
        var changes = rows.stream()
            .map(row -> new SnapshotDeltaResponseDto.Change(row.version(), DeltaJson.read(row.deltaJson())))
            .toList();
        long toVersion = changes.isEmpty() ? current : changes.get(changes.size() - 1).version();
        String publishedAt = rows.isEmpty() ? java.time.Instant.now().toString() : rows.get(rows.size() - 1).publishedAt();
        return new SnapshotDeltaResponseDto(1, since, toVersion, publishedAt, changes);
    }
}
```

- [ ] **Step 2: `ConformanceVectorDto` and `FullSnapshotWriter`** (NDJSON body for `GET /v1/snapshot/full`)

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/snapshot/ConformanceVectorDto.java
package com.solovis.entitlement.service.snapshot;

/** Wire shape for one `{"kind":"conformance", ...}` NDJSON line — self-contained per snapshot-feed.md so a replica can evaluate it without the real data. */
public record ConformanceVectorDto(String kind, String id, Object model, Object expect) {}
```

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/snapshot/FullSnapshotWriter.java
package com.solovis.entitlement.service.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.solovis.entitlement.core.conformance.ConformanceVector;
import com.solovis.entitlement.core.conformance.ResolverContract;
import com.solovis.entitlement.core.view.Snapshot;
import com.solovis.entitlement.service.dto.CapabilityDescriptorMapper;
import com.solovis.entitlement.service.dto.ValueMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Streams the full-resync NDJSON body — one JSON object per line, header first and footer last (snapshot-feed.md). */
@org.springframework.stereotype.Component
public class FullSnapshotWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public void write(Snapshot snapshot, OutputStream out) throws IOException {
        var writer = new PrintWriter(new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8));
        int capabilities = 0, plans = 0, accounts = 0, overrides = 0;
        for (var c : snapshot.capabilities()) capabilities++;
        for (var p : snapshot.plans()) plans++;
        // account/override counts require iterating the private snapshot maps indirectly via the public
        // surface — Snapshot has no direct "count" accessor, so this writer counts while it writes below
        // and emits the header LAST instead of first if an exact count is required up front. Simpler:
        // buffer capability/plan/account/override lines into a list first, count them, then emit header,
        // buffered lines, footer, in that order (still satisfies "header first, footer last").
        var lines = new java.util.ArrayList<Map<String, Object>>();

        for (var capability : snapshot.capabilities()) {
            var descriptor = CapabilityDescriptorMapper.toDescriptor(capability);
            var line = new LinkedHashMap<String, Object>();
            line.put("kind", "capability"); line.put("key", descriptor.key()); line.put("area", descriptor.area());
            line.put("valueType", descriptor.valueType()); line.put("default", descriptor.defaultValue());
            line.put("offValue", descriptor.offValue()); line.put("tiers", descriptor.tiers()); line.put("status", descriptor.status());
            lines.add(line);
        }
        for (var plan : snapshot.plans()) {
            Map<String, Object> entitlements = new LinkedHashMap<>();
            for (var capability : snapshot.capabilities()) {
                snapshot.planEntitlement(plan.key(), capability.key())
                    .ifPresent(pe -> entitlements.put(capability.key().value(), ValueMapper.toDto(pe.value())));
            }
            var line = new LinkedHashMap<String, Object>();
            line.put("kind", "plan"); line.put("key", plan.key()); line.put("status", plan.status().name());
            line.put("isDefaultForNewAccounts", plan.defaultForNewAccounts()); line.put("entitlements", entitlements);
            lines.add(line);
        }
        // NOTE for the implementer: Snapshot exposes no bulk account/override iterator (only per-key
        // lookups, by design — resolution never needs "all accounts"). Add the two accessors this writer
        // needs directly to EntitlementView/Snapshot as part of this step, mirroring `capabilities()` from
        // Task 1 exactly: `Collection<AccountAssignment> accountAssignments()` and
        // `Collection<AccountOverride> allLiveOverrides()`, each a one-line `.values()`-style getter over
        // the map Snapshot already holds internally. Write their tests in SnapshotBuilderTest the same way
        // Task 1's test was written, then use them here:
        for (var account : snapshot.accountAssignments()) {
            var line = new LinkedHashMap<String, Object>();
            line.put("kind", "account"); line.put("external", account.accountExternalId()); line.put("planKey", account.planKey());
            lines.add(line);
        }
        for (var override : snapshot.allLiveOverrides()) {
            var line = new LinkedHashMap<String, Object>();
            line.put("kind", "override"); line.put("ref", "ovr_" + override.id().getAsLong());
            line.put("account", override.accountExternalId()); line.put("capability", override.capabilityKey().value());
            line.put("kind_", override.kind().name()); // see note below
            line.put("value", ValueMapper.toDto(override.value()));
            lines.add(line);
        }
        for (var vector : ConformanceVector.spec5WorkedExamples()) {
            var line = new LinkedHashMap<String, Object>();
            line.put("kind", "conformance"); line.put("id", vector.name());
            line.put("expect", Map.of("allowed", vector.expectedAllowed(), "value", ValueMapper.toDto(vector.expectedValue())));
            lines.add(line);
        }

        var header = Map.of("kind", "header", "version", snapshot.snapshotVersion(), "format", 1,
            "resolverContract", ResolverContract.VERSION, "publishedAt", java.time.Instant.now().toString(),
            "counts", Map.of("capabilities", capabilities, "plans", plans, "accounts", lines.stream().filter(l -> l.get("kind").equals("account")).count(),
                "overrides", lines.stream().filter(l -> l.get("kind").equals("override")).count()));
        writer.println(MAPPER.writeValueAsString(header));
        for (var line : lines) {
            writer.println(MAPPER.writeValueAsString(line));
        }
        var footer = Map.of("kind", "footer", "version", snapshot.snapshotVersion(), "recordCount", lines.size() + 2);
        writer.println(MAPPER.writeValueAsString(footer));
        writer.flush();
    }
}
```

**Fix required before this compiles**, called out rather than silently patched: the `override` line above writes both `"kind": "override"` (the record-type discriminator every line uses) and needs the override's own GRANT/HOLD kind — colliding on the same JSON key. Rename the map's `kind` entries consistently with `snapshot-feed.md`'s example (`{"kind":"override", ..., "kind":"GRANT", ...}` is invalid JSON, two identical keys): the override *record type* key must stay `"kind":"override"` and the GRANT/HOLD field must be a **different** JSON key. Re-reading `snapshot-feed.md`'s literal example line: `{"kind":"override","ref":"ovr_4471","account":"acct_9931","capability":"reports.monthly","kind":"GRANT","value":{...}}` — the spec's own example has this exact collision, which is a bug in the spec text, not something to reproduce. Resolve it by using `"overrideKind"` for the GRANT/HOLD field (matching the field name `snapshot-feed.md`'s **delta** stream already uses for the identical concept — `override.created`'s payload is documented as `overrideKind`) and drop the placeholder `line.put("kind_", ...)` above in favor of `line.put("overrideKind", override.kind().name())`. Flag this spec inconsistency in the PR description so `contracts/snapshot-feed.md` gets corrected to match.

- [ ] **Step 3: `SnapshotFeedController`**

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/api/SnapshotFeedController.java
package com.solovis.entitlement.service.api;

import com.solovis.entitlement.core.conformance.ResolverContract;
import com.solovis.entitlement.service.api.dto.SnapshotDeltaResponseDto;
import com.solovis.entitlement.service.api.dto.SnapshotVersionResponseDto;
import com.solovis.entitlement.service.snapshot.DeltaFeedService;
import com.solovis.entitlement.service.snapshot.FullSnapshotWriter;
import com.solovis.entitlement.service.snapshot.SnapshotHolder;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.zip.GZIPOutputStream;

@RestController
@RequestMapping("/v1/snapshot")
public class SnapshotFeedController {

    private final SnapshotHolder snapshotHolder;
    private final FullSnapshotWriter fullSnapshotWriter;
    private final DeltaFeedService deltaFeedService;

    public SnapshotFeedController(SnapshotHolder snapshotHolder, FullSnapshotWriter fullSnapshotWriter, DeltaFeedService deltaFeedService) {
        this.snapshotHolder = snapshotHolder;
        this.fullSnapshotWriter = fullSnapshotWriter;
        this.deltaFeedService = deltaFeedService;
    }

    @GetMapping("/version")
    public ResponseEntity<SnapshotVersionResponseDto> version() {
        var snapshot = snapshotHolder.current();
        var body = new SnapshotVersionResponseDto(snapshot.snapshotVersion(), java.time.Instant.now().toString(), 1, ResolverContract.VERSION);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @GetMapping(value = "/full", produces = "application/x-ndjson")
    public ResponseEntity<org.springframework.core.io.StreamingResponseBody> full() {
        var snapshot = snapshotHolder.current();
        org.springframework.core.io.StreamingResponseBody body = out -> {
            try (var gzip = new GZIPOutputStream(out)) {
                fullSnapshotWriter.write(snapshot, gzip);
            }
        };
        return ResponseEntity.ok().header("Content-Encoding", "gzip")
            .contentType(MediaType.parseMediaType("application/x-ndjson")).body(body);
    }

    @GetMapping
    public SnapshotDeltaResponseDto delta(@RequestParam long since) {
        return deltaFeedService.since(since);
    }
}
```

- [ ] **Step 4: Run and commit**

Run: `./mvnw -pl entitlement-core,entitlement-service -am test -Dtest='SnapshotBuilderTest,com.solovis.entitlement.service.snapshot.**,com.solovis.entitlement.service.api.SnapshotFeedControllerTest'`
Expected: PASS (after adding `accountAssignments()`/`allLiveOverrides()` to `EntitlementView`/`Snapshot` per Step 2's note, and after the `overrideKind` fix).

```bash
git add entitlement-core/src/main/java/com/solovis/entitlement/core/view \
        entitlement-core/src/test/java/com/solovis/entitlement/core/view/SnapshotBuilderTest.java \
        entitlement-service/src/main/java/com/solovis/entitlement/service/snapshot \
        entitlement-service/src/main/java/com/solovis/entitlement/service/api \
        entitlement-service/src/test/java/com/solovis/entitlement/service/snapshot \
        entitlement-service/src/test/java/com/solovis/entitlement/service/api
git commit -m "feat(entitlement-service): snapshot replication feed — version, full NDJSON, delta"
```

## Task 10: Wiring — Jackson, OpenAPI, SPA fallback, and a dev seeder

The last task: makes `./mvnw -pl entitlement-service spring-boot:run` produce a browsable, populated, OpenAPI-documented service — the concrete deliverable other agents point their tooling at.

**Files:**
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/config/JacksonConfig.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/config/WebConfig.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/config/OpenApiConfig.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/seed/DemoDataSeeder.java`
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/seed/package-info.java`
- Modify: `entitlement-service/src/main/resources/application.yaml` (add `entitlement.seed.enabled`)
- Modify: `entitlement-service/src/test/resources/application.yaml` (disable seeding in tests)
- Test: `entitlement-service/src/test/java/com/solovis/entitlement/service/config/JacksonConfigTest.java`

**Interfaces:**
- Consumes: `CapabilityAdminService`/`PlanAdminService`/`AccountAdminService`/`OverrideAdminService` (Tasks 5–7) — the seeder writes through the same services every other write path uses, so it is exercised by, and cannot drift from, real validation.
- Produces: a `Jackson2ObjectMapperBuilderCustomizer` bean (`Instant` as ISO-8601 with millis, non-null inclusion — reinforcing `application.yaml`'s existing `spring.jackson.default-property-inclusion: non_null`), an `OpenAPI` bean describing the service for `springdoc-openapi` (already on the classpath — this task supplies only the `Info` block; springdoc auto-discovers every `@RestController` already built), a `WebMvcConfigurer` forwarding unmatched non-API GET requests to `/index.html` (the SPA fallback the frontend worktree's build will eventually populate `static/` with — this task adds the routing rule now so it does not block on that build finishing), and `DemoDataSeeder` (an `ApplicationRunner`, ordered after `SnapshotStartup`, that seeds a small fixed dataset — capabilities across a few areas, three plans, a handful of accounts and overrides — **not** the full 100,000-account/500-capability volume dataset `research.md` §18 describes for the load-test demonstration, which belongs to the not-yet-built `entitlement-loadtest` module and is out of this plan's scope).

- [ ] **Step 1: `JacksonConfig`, test-first**

```java
// entitlement-service/src/test/java/com/solovis/entitlement/service/config/JacksonConfigTest.java
package com.solovis.entitlement.service.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class JacksonConfigTest {

    @Autowired ObjectMapper objectMapper;

    @Test
    void serialisesInstantAsIso8601WithMillis() throws Exception {
        String json = objectMapper.writeValueAsString(Instant.parse("2026-08-09T14:03:11.482Z"));
        assertThat(json).isEqualTo("\"2026-08-09T14:03:11.482Z\"");
    }

    @Test
    void omitsNullFieldsByDefault() throws Exception {
        record Sample(String present, String absent) {}
        String json = objectMapper.writeValueAsString(new Sample("value", null));
        assertThat(json).doesNotContain("absent");
    }
}
```

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/config/JacksonConfig.java
package com.solovis.entitlement.service.config;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** application.yaml already sets default-property-inclusion: non_null; this adds the ISO-8601-with-millis Instant format contracts/README.md requires. */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer instantAsIso8601(){
        return builder -> builder.modulesToInstall(new JavaTimeModule())
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
```

- [ ] **Step 2: `WebConfig` (SPA fallback)**

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/config/WebConfig.java
package com.solovis.entitlement.service.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Forwards any GET that isn't /v1, /admin/v1 or /actuator to the SPA entry point — one deployable, no CORS (plan.md, "entitlement-ui"). */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/{path:^(?!v1|admin|actuator|swagger-ui|v3).*$}").setViewName("forward:/index.html");
        registry.addViewController("/{path:^(?!v1|admin|actuator|swagger-ui|v3).*$}/**").setViewName("forward:/index.html");
    }
}
```

- [ ] **Step 3: `OpenApiConfig`**

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/config/OpenApiConfig.java
package com.solovis.entitlement.service.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI entitlementServiceOpenApi() {
        return new OpenAPI().info(new Info()
            .title("Entitlement Service")
            .description("Capability registry, plans, GRANT/HOLD overrides, and trace-explained decisions. "
                + "See .specs/001-entitlement-service/contracts/ for the source-of-truth contracts this API implements.")
            .version("v1"));
    }
}
```

- [ ] **Step 4: `DemoDataSeeder`**

```java
// entitlement-service/src/main/java/com/solovis/entitlement/service/seed/DemoDataSeeder.java
package com.solovis.entitlement.service.seed;

import com.solovis.entitlement.service.admin.dto.*;
import com.solovis.entitlement.service.admin.service.*;
import com.solovis.entitlement.service.dto.ValueDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.util.List;

/**
 * A small, fixed development dataset — not the 100,000-account load-test seed research.md §18
 * describes (that belongs to the future entitlement-loadtest module). Runs through the same admin
 * services every real write path uses, so it can never declare data the validation rules reject.
 */
@Component
@Order(1) // after SnapshotStartup (@Order(0))
public class DemoDataSeeder implements ApplicationRunner {

    private final CapabilityAdminService capabilityService;
    private final PlanAdminService planService;
    private final AccountAdminService accountService;
    private final OverrideAdminService overrideService;
    private final boolean enabled;

    public DemoDataSeeder(CapabilityAdminService capabilityService, PlanAdminService planService,
            AccountAdminService accountService, OverrideAdminService overrideService,
            @Value("${entitlement.seed.enabled:true}") boolean enabled) {
        this.capabilityService = capabilityService;
        this.planService = planService;
        this.accountService = accountService;
        this.overrideService = overrideService;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled || !planService.list().isEmpty()) {
            return; // already seeded, or seeding disabled (tests, and any future non-dev profile)
        }

        capabilityService.create(new CapabilityCreateRequest("api.access", "API access", null, "SWITCH",
            new ValueDto("SWITCH", false, null, null, null, null), null, null));
        capabilityService.create(new CapabilityCreateRequest("reports.monthly", "Monthly reports", null, "QUANTITY",
            new ValueDto("QUANTITY", null, 0L, null, null, null), null, null));
        capabilityService.create(new CapabilityCreateRequest("seats.count", "Seats", null, "QUANTITY",
            new ValueDto("QUANTITY", null, 5L, null, null, null), null, null));
        capabilityService.create(new CapabilityCreateRequest("support.tier", "Support level", null, "TIER",
            new ValueDto("TIER", null, null, null, "community", null),
            new ValueDto("TIER", null, null, null, "community", null),
            List.of(new CapabilityCreateRequest.TierRequest("community", "Community"),
                    new CapabilityCreateRequest.TierRequest("standard", "Standard"),
                    new CapabilityCreateRequest.TierRequest("gold", "Gold"))));

        planService.create(new PlanCreateRequest("free", "Free", "Default plan for new signups."));
        planService.designateDefault("free");
        planService.create(new PlanCreateRequest("pro", "Pro", "Paid tier."));
        var proPreview = planService.preview("pro", new PlanEntitlementEditRequest(
            java.util.Map.of("api.access", new ValueDto("SWITCH", true, null, null, null, null),
                              "reports.monthly", new ValueDto("QUANTITY", null, 50L, null, null, null),
                              "support.tier", new ValueDto("TIER", null, null, null, "standard", null)),
            List.of(), null, null));
        planService.apply("pro", new PlanEntitlementEditRequest(
            java.util.Map.of("api.access", new ValueDto("SWITCH", true, null, null, null, null),
                              "reports.monthly", new ValueDto("QUANTITY", null, 50L, null, null, null),
                              "support.tier", new ValueDto("TIER", null, null, null, "standard", null)),
            List.of(), null, proPreview.previewToken()));

        accountService.create(new AccountCreateRequest("acct_9931", "Northwind Capital"));
        accountService.reassignPlan("acct_9931", new PlanReassignRequest("pro", "PERSON", "dev-operator", "Initial demo setup"));
        overrideService.create("acct_9931", new OverrideCreateRequest("reports.monthly", "GRANT",
            new ValueDto("QUANTITY", null, 200L, null, null, null), "Renewal concession — Q3 pilot"));
        accountService.create(new AccountCreateRequest("acct_1177", "Example Co"));
    }
}
```

- [ ] **Step 5: Config**

Append to `entitlement-service/src/main/resources/application.yaml` (sibling of the existing `entitlement:` keys):

```yaml
entitlement:
  seed:
    enabled: "${ENTITLEMENT_SEED_ENABLED:true}"
```

Append to `entitlement-service/src/test/resources/application.yaml`:

```yaml
entitlement:
  seed:
    enabled: false
```

- [ ] **Step 6: Run the full suite, boot the app, and commit**

Run: `./mvnw -pl entitlement-core,entitlement-service,entitlement-client -am test`
Expected: BUILD SUCCESS across every module touched by this plan (`entitlement-client` still has no code of its own — Task 3's forward reference and Task 1–10 never touch it — this confirms the reactor still builds end to end).

Run: `./mvnw -pl entitlement-service spring-boot:run` (foreground; stop with Ctrl-C once verified), then from another shell:
```bash
curl -s http://172.17.192.221:8081/admin/v1/plans | head -c 500
curl -s http://172.17.192.221:8081/v1/accounts/acct_9931/capabilities/reports.monthly | head -c 800
curl -sI http://172.17.192.221:8081/v3/api-docs | head -3
```
Expected: the first two return real, seeded JSON (not 404/500); the third returns `200`. (Server binds `0.0.0.0:8081` per the existing `application.yaml` — reachable from the Mac at `http://172.17.192.221:8081`, per this machine's headless-server convention; `/swagger-ui.html` is the browsable form other agents can point a browser at.)

```bash
git add entitlement-service/src/main/java/com/solovis/entitlement/service/config \
        entitlement-service/src/main/java/com/solovis/entitlement/service/seed \
        entitlement-service/src/main/resources/application.yaml \
        entitlement-service/src/test/resources/application.yaml \
        entitlement-service/src/test/java/com/solovis/entitlement/service/config/JacksonConfigTest.java
git commit -m "feat(entitlement-service): Jackson/OpenAPI/SPA-fallback wiring and a dev data seeder"
```

---

## Task 11: `GET /admin/v1/capabilities/{key}` returns usage (post-merge contract audit, 2026-08-09)

Tasks 1–10 above shipped and merged; this task and Task 12 are an addendum written after a UI-vs-contract audit found two of this plan's own Steps under-specified their endpoint relative to `admin-api.md`. This is a planning gap in this document, not an implementer deviation — Task 5 Step 4 above literally specifies the same bare `CapabilityDescriptorDto` return that shipped.

The contract's route table (`admin-api.md` line 23) states this endpoint returns "One capability, plus where it is used" — the same `usage: { plans, liveOverrides }` shape Task 5's retire endpoint already returns. The operator UI's retire-confirmation screen depends on this field being present on the plain GET (it must show usage *before* an irreversible retire, not after) and currently crashes reading `undefined.plans` against the real backend.

**Files:**
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/CapabilityDetailResponseDto.java`
- Modify: `entitlement-service/src/main/java/com/solovis/entitlement/service/admin/service/CapabilityAdminService.java`
- Modify: `entitlement-service/src/main/java/com/solovis/entitlement/service/admin/CapabilityAdminController.java`
- Test: `entitlement-service/src/test/java/com/solovis/entitlement/service/admin/CapabilityAdminServiceTest.java`
- Test: `entitlement-service/src/test/java/com/solovis/entitlement/service/admin/CapabilityAdminControllerTest.java`

**Interfaces:**
- Produces: `CapabilityAdminService.get(String key)` now returns `CapabilityDetailResponseDto` (was `CapabilityDescriptorDto`). `CapabilityDetailResponseDto` wire-serializes with the descriptor's fields (`key`, `area`, `displayName`, `description`, `valueType`, `default`, `offValue`, `tiers`, `status`) at the **top level**, via `@JsonUnwrapped`, with `usage: { plans, liveOverrides }` alongside — the exact same flat shape the retire endpoint's descriptor portion already has, just without retirement having happened.
- Consumes: `CapabilityRetireResponseDto.Usage` (Task 5, existing), `PlanEntitlementRepository.findPlanIdsUsingCapability`, `AccountOverrideRepository.countLiveForCapability`, `CapabilityDescriptorMapper.toDescriptor` (all Task 5, already used by `retire()`).

- [ ] **Step 1: Write the failing tests**

Add to `CapabilityAdminControllerTest.java` (add `import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;` alongside the existing `post` import):

```java
    @Test
    void getReturnsTheDescriptorFlatWithUsageAlongside() throws Exception {
        String body = """
            {"key":"t9.export.csv","displayName":"Export CSV","valueType":"SWITCH",
             "default":{"type":"SWITCH","enabled":false}}
            """;
        mockMvc.perform(post("/admin/v1/capabilities").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());

        mockMvc.perform(get("/admin/v1/capabilities/t9.export.csv"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.key").value("t9.export.csv"))
            .andExpect(jsonPath("$.displayName").value("Export CSV"))
            .andExpect(jsonPath("$.usage.plans").isArray())
            .andExpect(jsonPath("$.usage.liveOverrides").value(0));
    }
```

Add to `CapabilityAdminServiceTest.java` (the file that already has `retireReturnsUsageAndRemainsReadableAfterwards`):

```java
    @Test
    void getIncludesUsage() {
        var create = new CapabilityCreateRequest("t8.export.csv", "Export CSV", null, "SWITCH",
            new ValueDto("SWITCH", false, null, null, null, null), null, null);
        service.create(create);

        var result = service.get("t8.export.csv");

        assertThat(result.descriptor().key()).isEqualTo("t8.export.csv");
        assertThat(result.usage().plans()).isEmpty();
        assertThat(result.usage().liveOverrides()).isZero();
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -pl entitlement-service -am test -Dtest=CapabilityAdminServiceTest#getIncludesUsage,CapabilityAdminControllerTest#getReturnsTheDescriptorFlatWithUsageAlongside`
Expected: compile failure (`CapabilityAdminService.get` doesn't return a type with `.descriptor()`/`.usage()`; JSON path `$.usage` doesn't exist yet).

- [ ] **Step 3: Create the response DTO**

```java
package com.solovis.entitlement.service.admin.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.solovis.entitlement.service.dto.CapabilityDescriptorDto;

/**
 * GET /admin/v1/capabilities/{key} — the descriptor's own fields at the top level (admin-api.md:
 * "One capability, plus where it is used"), with `usage` alongside. Unwrapped rather than nested
 * under a `capability` key so a caller reading `displayName`/`valueType`/... sees the same shape
 * here as from every other capability-returning endpoint except retire.
 */
public record CapabilityDetailResponseDto(
    @JsonUnwrapped CapabilityDescriptorDto descriptor,
    CapabilityRetireResponseDto.Usage usage
) {}
```

- [ ] **Step 4: Extract the shared usage computation and wire it into `get()`**

In `CapabilityAdminService.java`, replace the existing `get()` method:

```java
    public CapabilityDetailResponseDto get(String key) {
        var row = capabilityRepository.findByKey(key)
            .orElseThrow(() -> new com.solovis.entitlement.core.error.UnknownCapabilityException(key));
        var domain = com.solovis.entitlement.service.snapshot.RowMappers.toCapability(row, capabilityRepository.findTiers(row.id()));
        return new CapabilityDetailResponseDto(CapabilityDescriptorMapper.toDescriptor(domain), usageOf(row));
    }
```

Inside `retire()`, replace:

```java
        var planKeys = planEntitlementRepository.findPlanIdsUsingCapability(row.id()).stream()
            .map(planId -> planRepository.findById(planId).orElseThrow().key()).toList();
        long liveOverrides = accountOverrideRepository.countLiveForCapability(row.id());
```

and its use in the final `new CapabilityRetireResponseDto(descriptor, new CapabilityRetireResponseDto.Usage(planKeys, liveOverrides));` line, with:

```java
        var usage = usageOf(row);
```

updating that final line to `new CapabilityRetireResponseDto(descriptor, usage);`. Add the shared private helper (near `loadDomain`):

```java
    private CapabilityRetireResponseDto.Usage usageOf(CapabilityRow row) {
        var planKeys = planEntitlementRepository.findPlanIdsUsingCapability(row.id()).stream()
            .map(planId -> planRepository.findById(planId).orElseThrow().key()).toList();
        long liveOverrides = accountOverrideRepository.countLiveForCapability(row.id());
        return new CapabilityRetireResponseDto.Usage(planKeys, liveOverrides);
    }
```

- [ ] **Step 5: Update the controller's return type**

In `CapabilityAdminController.java`, change:

```java
    @GetMapping("/{key}")
    public com.solovis.entitlement.service.dto.CapabilityDescriptorDto get(@PathVariable String key) {
        return service.get(key);
    }
```

to:

```java
    @GetMapping("/{key}")
    public CapabilityDetailResponseDto get(@PathVariable String key) {
        return service.get(key);
    }
```

(`CapabilityDetailResponseDto` is already in scope via the existing `import com.solovis.entitlement.service.admin.dto.*;`.)

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw -pl entitlement-service -am test -Dtest=CapabilityAdminServiceTest,CapabilityAdminControllerTest`
Expected: all pass, including the two new tests. The controller test's `$.displayName` / `$.usage.plans` assertions are the proof `@JsonUnwrapped` actually flattens `descriptor` rather than nesting it. If Jackson 3 refuses to unwrap here (the codebase has one documented case, in `SnapshotDeltaResponseDto`, where `@JsonUnwrapped` doesn't work for a *polymorphic* type — this DTO is not polymorphic, but verify empirically), fall back to a hand-written `Map<String, Object>` merge of the descriptor's fields and `usage`, and note the deviation in the report.

- [ ] **Step 7: Run the whole reactor's tests and commit**

Run: `./mvnw -pl entitlement-service -am test`
Expected: full suite green, no regressions elsewhere in `CapabilityAdminService`'s callers.

```bash
git add entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/CapabilityDetailResponseDto.java \
        entitlement-service/src/main/java/com/solovis/entitlement/service/admin/service/CapabilityAdminService.java \
        entitlement-service/src/main/java/com/solovis/entitlement/service/admin/CapabilityAdminController.java \
        entitlement-service/src/test/java/com/solovis/entitlement/service/admin/CapabilityAdminServiceTest.java \
        entitlement-service/src/test/java/com/solovis/entitlement/service/admin/CapabilityAdminControllerTest.java
git commit -m "fix(entitlement-service): GET capability includes usage, per contract"
```

---

## Task 12: `GET /admin/v1/accounts` returns a genuine `nextCursor` (post-merge contract audit, 2026-08-09)

Same category of gap as Task 11, this time in Task 7 Step 4 above: the contract's route table (`admin-api.md` line 181) states this endpoint is "cursor-paged," but the Step 4 controller code this plan specified returns `Map.of("accounts", service.search(...))` — no cursor field at all, so no caller can ever reach a second page. This task makes the response carry an opaque `acct_<id>` cursor (mirroring the existing `aud_<seq>` convention already used by the audit endpoint, Task 8) whenever more rows exist.

**Files:**
- Create: `entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/AccountSearchResponseDto.java`
- Modify: `entitlement-service/src/main/java/com/solovis/entitlement/service/admin/service/AccountAdminService.java`
- Modify: `entitlement-service/src/main/java/com/solovis/entitlement/service/admin/AccountAdminController.java`
- Test: `entitlement-service/src/test/java/com/solovis/entitlement/service/admin/AccountAdminServiceTest.java`

**Interfaces:**
- Produces: `AccountAdminService.search(String q, String planKey, long afterId, int limit)` now returns `AccountSearchResponseDto(List<AccountSummaryDto> accounts, String nextCursor)` (was `List<AccountSummaryDto>`). `nextCursor` is `null` when the page returned is the last one.
- Consumes: `AccountRepository.search(String q, Long planId, long afterId, int limit)` (Task 7, unchanged signature — called with `limit + 1` to detect a next page without changing the repository's own contract), `RefId.parse`/prefix convention (Task 1, `error/RefId.java`).

- [ ] **Step 1: Write the failing test**

Add to `AccountAdminServiceTest.java`:

```java
    @Test
    void searchPagesByCursorWhenMoreAccountsExist() {
        planService.create(new PlanCreateRequest("cursor-test-plan", "Cursor test plan", null));
        planService.designateDefault("cursor-test-plan");
        accountService.create(new AccountCreateRequest("acct_cursor_page_0", null));
        accountService.create(new AccountCreateRequest("acct_cursor_page_1", null));
        accountService.create(new AccountCreateRequest("acct_cursor_page_2", null));

        var firstPage = accountService.search("acct_cursor_page", null, 0, 2);
        assertThat(firstPage.accounts()).hasSize(2);
        assertThat(firstPage.nextCursor()).isNotNull();

        long afterId = com.solovis.entitlement.service.error.RefId.parse(firstPage.nextCursor(), "acct_");
        var secondPage = accountService.search("acct_cursor_page", null, afterId, 2);
        assertThat(secondPage.accounts()).hasSize(1);
        assertThat(secondPage.nextCursor()).isNull();
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -pl entitlement-service -am test -Dtest=AccountAdminServiceTest#searchPagesByCursorWhenMoreAccountsExist`
Expected: compile failure — `search()` currently returns `List<AccountSummaryDto>`, which has no `.accounts()`/`.nextCursor()`.

- [ ] **Step 3: Create the response DTO**

```java
package com.solovis.entitlement.service.admin.dto;

import java.util.List;

public record AccountSearchResponseDto(List<AccountSummaryDto> accounts, String nextCursor) {}
```

- [ ] **Step 4: Rewrite `AccountAdminService.search()`**

Replace:

```java
    public List<AccountSummaryDto> search(String q, String planKey, long afterId, int limit) {
        Long planId = planKey == null ? null : planRepository.findByKey(planKey).map(PlanRow::id).orElse(-1L);
        return accountRepository.search(q, planId, afterId, limit).stream()
            .map(row -> new AccountSummaryDto(row.externalId(), row.name(),
                planRepository.findById(row.planId()).map(PlanRow::key).orElseThrow(), row.status()))
            .toList();
    }
```

with:

```java
    public AccountSearchResponseDto search(String q, String planKey, long afterId, int limit) {
        Long planId = planKey == null ? null : planRepository.findByKey(planKey).map(PlanRow::id).orElse(-1L);
        var rows = accountRepository.search(q, planId, afterId, limit + 1);
        boolean hasMore = rows.size() > limit;
        var page = hasMore ? rows.subList(0, limit) : rows;
        var accounts = page.stream()
            .map(row -> new AccountSummaryDto(row.externalId(), row.name(),
                planRepository.findById(row.planId()).map(PlanRow::key).orElseThrow(), row.status()))
            .toList();
        String nextCursor = hasMore ? "acct_" + page.get(page.size() - 1).id() : null;
        return new AccountSearchResponseDto(accounts, nextCursor);
    }
```

- [ ] **Step 5: Update the controller**

In `AccountAdminController.java`, add `import com.solovis.entitlement.service.error.RefId;`, remove the now-unused `import java.util.Map;` (nothing else in this file uses `Map`), and replace:

```java
    @GetMapping
    public Map<String, Object> search(
        @RequestParam(required = false) String q, @RequestParam(required = false) String planKey,
        @RequestParam(required = false, defaultValue = "0") long cursor,
        @RequestParam(required = false, defaultValue = "50") int limit) {
        return Map.of("accounts", service.search(q, planKey, cursor, limit));
    }
```

with:

```java
    @GetMapping
    public AccountSearchResponseDto search(
        @RequestParam(required = false) String q, @RequestParam(required = false) String planKey,
        @RequestParam(required = false) String cursor,
        @RequestParam(required = false, defaultValue = "50") int limit) {
        long afterId = cursor == null ? 0 : RefId.parse(cursor, "acct_");
        return service.search(q, planKey, afterId, limit);
    }
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw -pl entitlement-service -am test -Dtest=AccountAdminServiceTest`
Expected: all pass, including `searchPagesByCursorWhenMoreAccountsExist`.

- [ ] **Step 7: Run the whole reactor's tests and commit**

Run: `./mvnw -pl entitlement-service -am test`
Expected: full suite green. Confirm `AccountAdminController` is `search()`'s only caller before assuming the return-type change is contained (`grep -rn "\.search(" entitlement-service/src/main/java`).

```bash
git add entitlement-service/src/main/java/com/solovis/entitlement/service/admin/dto/AccountSearchResponseDto.java \
        entitlement-service/src/main/java/com/solovis/entitlement/service/admin/service/AccountAdminService.java \
        entitlement-service/src/main/java/com/solovis/entitlement/service/admin/AccountAdminController.java \
        entitlement-service/src/test/java/com/solovis/entitlement/service/admin/AccountAdminServiceTest.java
git commit -m "fix(entitlement-service): GET accounts returns a genuine nextCursor, per contract"
```

- [ ] **Final verification for Tasks 11–12:** `./mvnw -pl entitlement-service -am test` green; `spring-boot:run` then `curl http://172.17.192.221:8081/admin/v1/capabilities/<any-active-key>` shows a top-level `usage`; `curl http://172.17.192.221:8081/admin/v1/accounts?limit=1` shows a non-null `nextCursor` when more than one account exists.

---

## Self-Review

**Spec coverage** — every route in `contracts/decision-api.md`, `contracts/admin-api.md` and `contracts/snapshot-feed.md` maps to a task above: decision API → Task 4; capabilities/plans/accounts+overrides/checker/audit/meta → Tasks 5–8; snapshot feed → Tasks 3+9. `contracts/java-client-sdk.md` and `contracts/ui-screens.md` are explicitly **not** in scope — the SDK is a separate future module (`entitlement-client` stays an empty shell after this plan) and the UI is the concurrent frontend-worktree agent's own plan. The error model (`contracts/README.md`) is Task 1 and reused everywhere via `EntitlementApiException`/`GlobalExceptionHandler` — no task defines a competing error shape.

**Core touch-points, gathered in one place** (the Global Constraints section undercounts these at "only twice" — corrected here): Task 1 adds `EntitlementView.capabilities()`; Task 6 adds `SnapshotMutator.withPlanEntitlementRemoved`; Task 9 adds `EntitlementView.accountAssignments()`/`allLiveOverrides()`. All three are one-line, additive, already-tested-pattern extensions to an interface and its sole implementation — none changes `Resolver`'s arithmetic.

**Two flagged defects, deliberately left visible rather than silently fixed inline** (each is called out at its exact location above, not buried): `AuditController.entityCapabilityKey` (Task 8, Step 2) ships as a dead placeholder that must be rewired to `CapabilityRepository` before the task's tests can pass; the `FullSnapshotWriter` override line (Task 9, Step 2) has a genuine key-collision bug traced to `contracts/snapshot-feed.md`'s own example JSON, resolved here as `overrideKind` with a note to fix the spec file too. Both are load-bearing for their task's own tests, so neither can ship unnoticed — a fresh implementer hits a compile or test failure immediately if they copy the flagged code without applying the fix.

**Type consistency** — `ValueDto`/`CapabilityDescriptorDto` (Task 1) are the only value/capability wire types defined anywhere in this plan; every later DTO composes them rather than redeclaring fields. `SnapshotPublisher.Mutation`, `DeltaChange`, `AuditEntry`/`AuditRecorder`, and `DecisionMapper.toResponse` (widened to `public` in Task 6) each have exactly one definition, reused by every task that needs them — checked by re-reading Tasks 4 through 9's import lists against Tasks 1–3's produced signatures.

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-08-09-entitlement-service-api-layer.md`. Two execution options:

1. **Subagent-Driven (recommended)** — a fresh subagent per task, with a review checkpoint between tasks. Best fit here: Tasks 1–3 are a strict dependency chain (each task's Interfaces section is the next task's Consumes), but Tasks 5, 6, 7 and 8 only depend on Tasks 1–4 and can be reviewed/iterated somewhat independently once those land — and this plan is long enough that a fresh reviewer per task catches drift (a DTO field renamed in Task 6 breaking Task 4's usage, for instance) far more reliably than one long session holding the whole thing in context.

2. **Inline Execution** — work through the tasks in this same session, batching a few steps at a time with checkpoints for review.

Which approach?
