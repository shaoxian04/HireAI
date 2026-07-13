# Submit-flow Enhancements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the client submit flow clearer — a searchable category picker, a focused modal popout of ranked agent cards (with profile pictures), and plain-English failure panels that confirm the refund and (for spec-violation) show the real reason.

**Architecture:** Almost entirely frontend presentation over existing endpoints (`GET /catalogue/categories`, `GET /tasks/match-preview`). One new owner-scoped backend read — `GET /api/tasks/{id}/validation` — exposes the persisted validation-report reason. No DB migration, no money-path change, no new task states.

**Tech Stack:** Next.js 16 (App Router, TypeScript, Tailwind v4, "Mission Control" tokens) + vitest/@testing-library/msw on the frontend; Spring Boot COLA modules + JUnit/Mockito/MockMvc on the backend.

**Design spec:** `docs/superpowers/specs/2026-07-13-submit-flow-enhancements-design.md`

## Global Constraints

- **No DB migration**, no new task lifecycle state, no change to matching/escrow/settlement.
- **Hard Invariant #5:** the new read derives the user from the JWT (`CurrentUserProvider.currentUserId()`) and requires task ownership before returning anything; a non-owner gets `NOT_FOUND` (never a leak). No other invariant is touched (no money movement, no dispatch, no schema).
- **Booking still pays the agent's price**, not the typed budget (unchanged confirm/escrow path).
- **Mission Control aesthetic:** reuse the existing tokens/utilities (`bg-surface-2`, `text-accent`, `panel`, `eyebrow`, `font-mono`, `glow`, `reveal`) and the `@/components/ui` kit. No new colors.
- **Immutability & small files:** new React components are focused single-purpose files; no shared mutable state.
- **Images:** render remote images with `<img>` preceded by `// eslint-disable-next-line @next/next/no-img-element` (the established pattern in `components/AgentCard.tsx`).
- **Green gate before hand-off:** `npm run lint` **and** `npx vitest run` (in `frontend/`) and `mvn -f backend/pom.xml -B test` must all pass. The lint gate is CI-enforced — a `react-hooks/set-state-in-effect` on a localStorage/external-sync effect gets a scoped `// eslint-disable-next-line` with a one-line reason (precedent: `lib/auth.tsx`, `app/client/tasks/new/page.tsx:40`).
- **Branch:** `feat/shortlist-selection` (updates PR #21). Commit per task.

---

## File Structure

**Backend (Task 1):**
- Modify `hireai-domain/.../adjudication/repository/ValidationReportRepository.java` — add `findLatestByTaskId`.
- Modify `hireai-repository/.../adjudication/ValidationReportRepositoryImpl.java` + `ValidationReportJpaRepository.java` — derived query.
- Create `hireai-application/.../biz/adjudication/validation/ValidationReadAppService.java` (+ `impl/ValidationReadAppServiceImpl.java`) — ownership-agnostic report read.
- Create `hireai-controller/.../biz/adjudication/dto/ValidationReportDTO.java` + `.../biz/adjudication/ValidationReport2DTOConverter.java`.
- Modify `hireai-controller/.../biz/task/TaskController.java` — inject the read service, add `GET /{id}/validation` (ownership via `TaskReadAppService.getForClient`).
- Tests: create `application/biz/adjudication/ValidationReadAppServiceImplTest.java`; modify `controller/biz/task/TaskControllerTest.java`.

**Frontend:**
- Create `frontend/components/ui/Modal.tsx` (+ export) — accessible overlay dialog (Task 2).
- Create `frontend/components/CategoryCombobox.tsx` — strict searchable picker (Task 3).
- Modify `frontend/components/ShortlistPanel.tsx` — render inside `Modal` as a ranked card grid with avatars (Task 4).
- Modify `frontend/app/client/tasks/new/page.tsx` — wire combobox + modal (Task 5).
- Create `frontend/components/TaskFailurePanel.tsx` (Task 6).
- Modify `frontend/app/client/tasks/[id]/page.tsx` — render failure panel + fetch spec-violation reason (Task 7).
- Modify `frontend/lib/types.ts` — `ValidationCheckDTO` / `ValidationReportDTO` (Task 6).
- Tests: create `Modal.test.tsx`, `CategoryCombobox.test.tsx`, `TaskFailurePanel.test.tsx`, `taskFailure.test.tsx`; modify `ShortlistPanel.test.tsx`, `app/client/tasks/new/page.test.tsx`; modify `test/msw/handlers.ts` (default `/validation` 404).

---

## Task 1: Backend — owner-scoped validation-report read

**Files:**
- Modify: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/adjudication/repository/ValidationReportRepository.java`
- Modify: `backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/adjudication/ValidationReportJpaRepository.java`
- Modify: `backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/adjudication/ValidationReportRepositoryImpl.java`
- Create: `backend/hireai-application/src/main/java/com/hireai/application/biz/adjudication/validation/ValidationReadAppService.java`
- Create: `backend/hireai-application/src/main/java/com/hireai/application/biz/adjudication/validation/impl/ValidationReadAppServiceImpl.java`
- Create: `backend/hireai-controller/src/main/java/com/hireai/controller/biz/adjudication/dto/ValidationReportDTO.java`
- Create: `backend/hireai-controller/src/main/java/com/hireai/controller/biz/adjudication/ValidationReport2DTOConverter.java`
- Modify: `backend/hireai-controller/src/main/java/com/hireai/controller/biz/task/TaskController.java`
- Test (create): `backend/hireai-main/src/test/java/com/hireai/application/biz/adjudication/ValidationReadAppServiceImplTest.java`
- Test (modify): `backend/hireai-main/src/test/java/com/hireai/controller/biz/task/TaskControllerTest.java`

**Interfaces:**
- Consumes: `ValidationReportModel { verdict(): Verdict; checks(): List<CheckResult> }`, `CheckResult(String rule, boolean passed, String detail)`, `TaskReadAppService.getForClient(UUID, UUID)` (throws `DomainException(NOT_FOUND)` if absent/non-owner), `ResultCode.NOT_FOUND` (→ HTTP 404 via `GlobalExceptionConfiguration`).
- Produces: `ValidationReadAppService.latestForTask(UUID taskId): Optional<ValidationReportModel>`; `GET /api/tasks/{id}/validation` → `WebResult<ValidationReportDTO>` where `ValidationReportDTO(String verdict, List<CheckDTO> checks)` and `CheckDTO(String rule, boolean passed, String detail)`.

- [ ] **Step 1: Write the failing app-service test**

Create `backend/hireai-main/src/test/java/com/hireai/application/biz/adjudication/ValidationReadAppServiceImplTest.java`:

```java
package com.hireai.application.biz.adjudication;

import com.hireai.application.biz.adjudication.validation.impl.ValidationReadAppServiceImpl;
import com.hireai.domain.biz.adjudication.model.CheckResult;
import com.hireai.domain.biz.adjudication.model.ValidationReportModel;
import com.hireai.domain.biz.adjudication.repository.ValidationReportRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ValidationReadAppServiceImplTest {

    private final ValidationReportRepository repo = mock(ValidationReportRepository.class);
    private final ValidationReadAppServiceImpl service = new ValidationReadAppServiceImpl(repo);

    @Test
    void returnsTheLatestReportForTheTask() {
        UUID taskId = UUID.randomUUID();
        ValidationReportModel report = ValidationReportModel.of(taskId, 1,
                List.of(new CheckResult("format", false, "expected FILE, got none")));
        when(repo.findLatestByTaskId(eq(taskId))).thenReturn(Optional.of(report));

        assertThat(service.latestForTask(taskId)).containsSame(report);
    }

    @Test
    void returnsEmptyWhenNoReportExists() {
        UUID taskId = UUID.randomUUID();
        when(repo.findLatestByTaskId(eq(taskId))).thenReturn(Optional.empty());

        assertThat(service.latestForTask(taskId)).isEmpty();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -f backend/pom.xml -pl hireai-main -am test -Dtest=ValidationReadAppServiceImplTest`
Expected: FAIL — compilation error (`ValidationReadAppServiceImpl`, `findLatestByTaskId` don't exist).

- [ ] **Step 3: Add the repository method**

In `ValidationReportRepository.java`, add below `findByTaskIdAndAttemptNo`:

```java
    /** The most recent report for a task (highest attempt), or empty if it was never validated. */
    Optional<ValidationReportModel> findLatestByTaskId(UUID taskId);
```

In `ValidationReportJpaRepository.java`, add:

```java
    Optional<ValidationReportDO> findFirstByTaskIdOrderByAttemptNoDesc(UUID taskId);
```

In `ValidationReportRepositoryImpl.java`, add below `findByTaskIdAndAttemptNo`:

```java
    @Override
    public Optional<ValidationReportModel> findLatestByTaskId(UUID taskId) {
        return jpa.findFirstByTaskIdOrderByAttemptNoDesc(taskId).map(this::toModel);
    }
```

- [ ] **Step 4: Create the read app service**

Create `ValidationReadAppService.java`:

```java
package com.hireai.application.biz.adjudication.validation;

import com.hireai.domain.biz.adjudication.model.ValidationReportModel;
import org.jspecify.annotations.NonNull;
import org.springframework.validation.annotation.Validated;

import java.util.Optional;
import java.util.UUID;

/**
 * Read use case for the automated validation outcome. Ownership-agnostic: the caller (controller)
 * enforces task ownership before invoking this. Returns the latest report for the task, or empty.
 */
@Validated
public interface ValidationReadAppService {
    Optional<ValidationReportModel> latestForTask(@NonNull UUID taskId);
}
```

Create `impl/ValidationReadAppServiceImpl.java`:

```java
package com.hireai.application.biz.adjudication.validation.impl;

import com.hireai.application.biz.adjudication.validation.ValidationReadAppService;
import com.hireai.domain.biz.adjudication.model.ValidationReportModel;
import com.hireai.domain.biz.adjudication.repository.ValidationReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ValidationReadAppServiceImpl implements ValidationReadAppService {

    private final ValidationReportRepository validationReportRepository;

    @Override
    public Optional<ValidationReportModel> latestForTask(UUID taskId) {
        return validationReportRepository.findLatestByTaskId(taskId);
    }
}
```

- [ ] **Step 5: Run the app-service test to verify it passes**

Run: `mvn -f backend/pom.xml -pl hireai-main -am test -Dtest=ValidationReadAppServiceImplTest`
Expected: PASS (2 tests).

- [ ] **Step 6: Write the failing controller tests**

In `TaskControllerTest.java`, add the mock field alongside the others:

```java
    @MockBean com.hireai.application.biz.adjudication.validation.ValidationReadAppService validationReadAppService;
```

Add these tests (and imports: `com.hireai.application.biz.adjudication.validation.ValidationReadAppService`, `com.hireai.domain.biz.adjudication.model.CheckResult`, `com.hireai.domain.biz.adjudication.model.ValidationReportModel`, `java.util.Optional`):

```java
    // ---- GET /api/tasks/{id}/validation ----

    @Test
    void validationReturns200WithFailedChecksForOwner() throws Exception {
        UUID clientId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(currentUserProvider.currentUserId()).thenReturn(clientId);
        when(taskReadAppService.getForClient(eq(taskId), eq(clientId))).thenReturn(null);
        when(validationReadAppService.latestForTask(eq(taskId)))
                .thenReturn(Optional.of(ValidationReportModel.of(taskId, 1,
                        List.of(new CheckResult("format", false, "expected FILE, got none")))));

        mockMvc.perform(get("/api/tasks/{id}/validation", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verdict").value("FAIL"))
                .andExpect(jsonPath("$.data.checks[0].rule").value("format"))
                .andExpect(jsonPath("$.data.checks[0].passed").value(false))
                .andExpect(jsonPath("$.data.checks[0].detail").value("expected FILE, got none"));
    }

    @Test
    void validationReturns404WhenNoReport() throws Exception {
        UUID clientId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(currentUserProvider.currentUserId()).thenReturn(clientId);
        when(taskReadAppService.getForClient(eq(taskId), eq(clientId))).thenReturn(null);
        when(validationReadAppService.latestForTask(eq(taskId))).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/tasks/{id}/validation", taskId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void validationReturns404ForNonOwner() throws Exception {
        UUID clientId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(currentUserProvider.currentUserId()).thenReturn(clientId);
        when(taskReadAppService.getForClient(eq(taskId), eq(clientId)))
                .thenThrow(new DomainException(ResultCode.NOT_FOUND, "Task not found: " + taskId));

        mockMvc.perform(get("/api/tasks/{id}/validation", taskId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
```

- [ ] **Step 7: Run the controller tests to verify they fail**

Run: `mvn -f backend/pom.xml -pl hireai-main -am test -Dtest=TaskControllerTest`
Expected: FAIL — `ValidationReadAppService` bean unknown / endpoint `/{id}/validation` returns 404-from-no-mapping (not the asserted body). (The context may fail to load until Step 8 adds the constructor dep — that is the expected red.)

- [ ] **Step 8: Add the DTO, converter, and controller endpoint**

Create `ValidationReportDTO.java`:

```java
package com.hireai.controller.biz.adjudication.dto;

import java.util.List;

/** Read view of a task's automated validation outcome. */
public record ValidationReportDTO(String verdict, List<CheckDTO> checks) {
    public record CheckDTO(String rule, boolean passed, String detail) {
    }
}
```

Create `ValidationReport2DTOConverter.java`:

```java
package com.hireai.controller.biz.adjudication;

import com.hireai.controller.biz.adjudication.dto.ValidationReportDTO;
import com.hireai.domain.biz.adjudication.model.ValidationReportModel;

/** Maps the ValidationReport aggregate to its read DTO. */
public final class ValidationReport2DTOConverter {

    private ValidationReport2DTOConverter() {
    }

    public static ValidationReportDTO toDTO(ValidationReportModel m) {
        return new ValidationReportDTO(
                m.verdict().name(),
                m.checks().stream()
                        .map(c -> new ValidationReportDTO.CheckDTO(c.rule(), c.passed(), c.detail()))
                        .toList());
    }
}
```

In `TaskController.java`: add imports

```java
import com.hireai.application.biz.adjudication.validation.ValidationReadAppService;
import com.hireai.controller.biz.adjudication.ValidationReport2DTOConverter;
import com.hireai.controller.biz.adjudication.dto.ValidationReportDTO;
import com.hireai.domain.biz.adjudication.model.ValidationReportModel;
```

Add the field + constructor param (append last, keeping the others unchanged):

```java
    private final ValidationReadAppService validationReadAppService;
```

Constructor: add `ValidationReadAppService validationReadAppService` as the final parameter and `this.validationReadAppService = validationReadAppService;` in the body.

Add the endpoint (place after `getResult`):

```java
    @GetMapping("/{id}/validation")
    public WebResult<ValidationReportDTO> getValidation(@PathVariable("id") UUID id) {
        UUID clientId = currentUser.currentUserId();
        readAppService.getForClient(id, clientId); // ownership guard (Hard Invariant #5)
        ValidationReportModel report = validationReadAppService.latestForTask(id)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND,
                        "No validation report for task: " + id));
        return ok(ValidationReport2DTOConverter.toDTO(report));
    }
```

- [ ] **Step 9: Run the controller tests to verify they pass**

Run: `mvn -f backend/pom.xml -pl hireai-main -am test -Dtest=TaskControllerTest`
Expected: PASS (all existing + 3 new).

- [ ] **Step 10: Full backend build**

Run: `mvn -f backend/pom.xml -q -B test`
Expected: BUILD SUCCESS (Testcontainers integration tests auto-skip without Docker).

- [ ] **Step 11: Commit**

```bash
git add backend/hireai-domain backend/hireai-repository backend/hireai-application backend/hireai-controller backend/hireai-main
git commit -m "feat(backend): owner-scoped GET /api/tasks/{id}/validation read"
```

---

## Task 2: Frontend — `Modal` primitive

**Files:**
- Create: `frontend/components/ui/Modal.tsx`
- Modify: `frontend/components/ui/index.ts`
- Test: `frontend/components/ui/Modal.test.tsx`

**Interfaces:**
- Produces: `Modal({ open: boolean, onClose: () => void, ariaLabel: string, children: ReactNode })` — renders nothing when `open` is false; otherwise an overlay `role="dialog" aria-modal="true"` with Esc-to-close, backdrop-click-to-close, focus trap, and body scroll-lock. Exported from `@/components/ui`.

- [ ] **Step 1: Write the failing test**

Create `frontend/components/ui/Modal.test.tsx`:

```tsx
import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { Modal } from "@/components/ui/Modal";

describe("Modal", () => {
  it("renders children when open, nothing when closed", () => {
    const { rerender } = render(
      <Modal open onClose={() => {}} ariaLabel="Picker"><p>Body</p></Modal>,
    );
    expect(screen.getByRole("dialog", { name: "Picker" })).toBeTruthy();
    expect(screen.getByText("Body")).toBeTruthy();
    rerender(<Modal open={false} onClose={() => {}} ariaLabel="Picker"><p>Body</p></Modal>);
    expect(screen.queryByText("Body")).toBeNull();
  });

  it("calls onClose on Escape", () => {
    const onClose = vi.fn();
    render(<Modal open onClose={onClose} ariaLabel="Picker"><p>Body</p></Modal>);
    fireEvent.keyDown(document, { key: "Escape" });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("closes on backdrop click but not on content click", () => {
    const onClose = vi.fn();
    render(<Modal open onClose={onClose} ariaLabel="Picker"><p>Content</p></Modal>);
    fireEvent.click(screen.getByText("Content"));
    expect(onClose).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("dialog").parentElement!);
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("locks body scroll while open and restores on close", () => {
    const { rerender } = render(<Modal open onClose={() => {}} ariaLabel="P"><p>x</p></Modal>);
    expect(document.body.style.overflow).toBe("hidden");
    rerender(<Modal open={false} onClose={() => {}} ariaLabel="P"><p>x</p></Modal>);
    expect(document.body.style.overflow).toBe("");
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd frontend && npx vitest run components/ui/Modal.test.tsx`
Expected: FAIL — cannot resolve `@/components/ui/Modal`.

- [ ] **Step 3: Create the component**

Create `frontend/components/ui/Modal.tsx`:

```tsx
"use client";

import { useEffect, useRef, type ReactNode } from "react";

interface ModalProps {
  open: boolean;
  onClose: () => void;
  ariaLabel: string;
  children: ReactNode;
}

/** Accessible overlay dialog: backdrop + Esc close, focus trap, body scroll-lock, focus restore. */
export function Modal({ open, onClose, ariaLabel, children }: ModalProps) {
  const dialogRef = useRef<HTMLDivElement>(null);
  const restoreRef = useRef<HTMLElement | null>(null);

  useEffect(() => {
    if (!open) return;
    restoreRef.current = document.activeElement as HTMLElement | null;
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    dialogRef.current?.focus();

    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") {
        onClose();
        return;
      }
      if (e.key !== "Tab") return;
      const focusables = dialogRef.current?.querySelectorAll<HTMLElement>(
        'a[href], button:not([disabled]), input:not([disabled]), [tabindex]:not([tabindex="-1"])',
      );
      if (!focusables || focusables.length === 0) return;
      const first = focusables[0];
      const last = focusables[focusables.length - 1];
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault();
        first.focus();
      }
    }

    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("keydown", onKey);
      document.body.style.overflow = prevOverflow;
      restoreRef.current?.focus?.();
    };
  }, [open, onClose]);

  if (!open) return null;

  return (
    <div
      className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-canvas/70 p-4 backdrop-blur-sm sm:p-8"
      onClick={onClose}
    >
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-label={ariaLabel}
        tabIndex={-1}
        onClick={(e) => e.stopPropagation()}
        className="panel reveal my-6 w-full max-w-2xl outline-none"
      >
        {children}
      </div>
    </div>
  );
}
```

Add to `frontend/components/ui/index.ts`:

```ts
export { Modal } from "./Modal";
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd frontend && npx vitest run components/ui/Modal.test.tsx`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add frontend/components/ui/Modal.tsx frontend/components/ui/Modal.test.tsx frontend/components/ui/index.ts
git commit -m "feat(frontend): accessible Modal primitive"
```

---

## Task 3: Frontend — `CategoryCombobox`

**Files:**
- Create: `frontend/components/CategoryCombobox.tsx`
- Test: `frontend/components/CategoryCombobox.test.tsx`

**Interfaces:**
- Consumes: `api<CategoryCountDTO[]>("/catalogue/categories")`; `CategoryCountDTO { category: string; agentCount: number }` (already in `lib/types.ts`).
- Produces: `CategoryCombobox({ value: string, onChange: (v: string) => void, id?: string })`. `onChange` is called **only** with a real category (on selection) or `""` (while editing) — so a non-empty `value` is guaranteed valid. Falls back to a plain `<input>` if the categories fetch fails.

- [ ] **Step 1: Write the failing test**

Create `frontend/components/CategoryCombobox.test.tsx`:

```tsx
import { describe, it, expect, vi, beforeAll, afterAll, afterEach } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { http } from "msw";
import { server, ok, fail } from "../test/msw/handlers";
import { CategoryCombobox } from "@/components/CategoryCombobox";

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const CATS = [
  { category: "summarisation", agentCount: 4 },
  { category: "translation", agentCount: 2 },
];

function useCats() {
  server.use(http.get("*/api/catalogue/categories", () => ok(CATS)));
}

describe("CategoryCombobox", () => {
  it("opens on focus and lists categories with counts", async () => {
    useCats();
    render(<CategoryCombobox value="" onChange={vi.fn()} />);
    fireEvent.focus(screen.getByRole("combobox"));
    expect(await screen.findByRole("option", { name: /summarisation/i })).toBeTruthy();
    expect(screen.getByText(/4 agents/i)).toBeTruthy();
  });

  it("filters as the user types", async () => {
    useCats();
    render(<CategoryCombobox value="" onChange={vi.fn()} />);
    const input = screen.getByRole("combobox");
    fireEvent.focus(input);
    await screen.findByRole("option", { name: /summarisation/i });
    fireEvent.change(input, { target: { value: "trans" } });
    expect(screen.queryByRole("option", { name: /summarisation/i })).toBeNull();
    expect(screen.getByRole("option", { name: /translation/i })).toBeTruthy();
  });

  it("commits the real category on click", async () => {
    useCats();
    const onChange = vi.fn();
    render(<CategoryCombobox value="" onChange={onChange} />);
    fireEvent.focus(screen.getByRole("combobox"));
    fireEvent.mouseDown(await screen.findByRole("option", { name: /translation/i }));
    expect(onChange).toHaveBeenCalledWith("translation");
  });

  it("selects the highlighted option with ArrowDown + Enter", async () => {
    useCats();
    const onChange = vi.fn();
    render(<CategoryCombobox value="" onChange={onChange} />);
    const input = screen.getByRole("combobox");
    fireEvent.focus(input);
    await screen.findByRole("option", { name: /summarisation/i });
    fireEvent.keyDown(input, { key: "ArrowDown" });
    fireEvent.keyDown(input, { key: "Enter" });
    expect(onChange).toHaveBeenCalledWith("translation");
  });

  it("invalidates the committed value while the user edits", async () => {
    useCats();
    const onChange = vi.fn();
    render(<CategoryCombobox value="summarisation" onChange={onChange} />);
    fireEvent.change(screen.getByRole("combobox"), { target: { value: "summ" } });
    expect(onChange).toHaveBeenCalledWith("");
  });

  it("shows an empty hint when nothing matches", async () => {
    useCats();
    render(<CategoryCombobox value="" onChange={vi.fn()} />);
    const input = screen.getByRole("combobox");
    fireEvent.focus(input);
    await screen.findByRole("option", { name: /summarisation/i });
    fireEvent.change(input, { target: { value: "zzz" } });
    expect(screen.getByText(/no matching category/i)).toBeTruthy();
  });

  it("falls back to a plain input when the categories fetch fails", async () => {
    server.use(http.get("*/api/catalogue/categories", () => fail("INTERNAL_ERROR", "boom", 500)));
    const onChange = vi.fn();
    render(<CategoryCombobox value="" onChange={onChange} />);
    const input = await screen.findByPlaceholderText("category");
    fireEvent.change(input, { target: { value: "anything" } });
    expect(onChange).toHaveBeenCalledWith("anything");
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd frontend && npx vitest run components/CategoryCombobox.test.tsx`
Expected: FAIL — cannot resolve `@/components/CategoryCombobox`.

- [ ] **Step 3: Create the component**

Create `frontend/components/CategoryCombobox.tsx`:

```tsx
"use client";

import { useEffect, useId, useRef, useState, type KeyboardEvent } from "react";
import { api } from "@/lib/api";
import type { CategoryCountDTO } from "@/lib/types";

interface Props {
  value: string;
  onChange: (value: string) => void;
  id?: string;
}

const INPUT_CLS =
  "block w-full rounded-md border border-line bg-surface-2 px-3 py-2 font-mono text-sm text-fg shadow-inner transition placeholder:text-dim focus:border-accent focus:outline-none focus:ring-2 focus:ring-accent/25";

/**
 * Strict, searchable category picker. Options come from GET /catalogue/categories. `onChange` is
 * only ever called with a REAL category or "" — so a non-empty value is guaranteed valid and the
 * parent can gate submission on `!!value`. Falls back to a plain text input if the fetch fails.
 */
export function CategoryCombobox({ value, onChange, id }: Props) {
  const [categories, setCategories] = useState<CategoryCountDTO[]>([]);
  const [failed, setFailed] = useState(false);
  const [query, setQuery] = useState(value);
  const [open, setOpen] = useState(false);
  const [highlight, setHighlight] = useState(0);
  const rootRef = useRef<HTMLDivElement>(null);
  const listId = useId();

  useEffect(() => {
    let cancelled = false;
    api<CategoryCountDTO[]>("/catalogue/categories")
      .then((c) => { if (!cancelled) setCategories(c); })
      .catch(() => { if (!cancelled) setFailed(true); });
    return () => { cancelled = true; };
  }, []);

  // Reflect an external commit (e.g. a restored draft) into the visible text. `value` is only ever
  // a real category or "" (this component guarantees it), so a non-empty value is safe to display.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- one-way sync of an external committed value into local display text; guarded so it never fights user typing
    if (value && value !== query) setQuery(value);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value]);

  useEffect(() => {
    function onDoc(e: MouseEvent) {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(false);
    }
    document.addEventListener("mousedown", onDoc);
    return () => document.removeEventListener("mousedown", onDoc);
  }, []);

  if (failed) {
    return (
      <input
        id={id}
        className={INPUT_CLS}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder="category"
      />
    );
  }

  const filtered = categories.filter((c) =>
    c.category.toLowerCase().includes(query.trim().toLowerCase()),
  );

  function commit(cat: string) {
    setQuery(cat);
    onChange(cat);
    setOpen(false);
  }

  function onInput(text: string) {
    setQuery(text);
    setOpen(true);
    setHighlight(0);
    if (value) onChange(""); // strict: editing invalidates any committed category
  }

  function onKeyDown(e: KeyboardEvent) {
    if (e.key === "ArrowDown") {
      e.preventDefault();
      setOpen(true);
      setHighlight((h) => Math.min(h + 1, filtered.length - 1));
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setHighlight((h) => Math.max(h - 1, 0));
    } else if (e.key === "Enter") {
      if (open && filtered[highlight]) {
        e.preventDefault();
        commit(filtered[highlight].category);
      }
    } else if (e.key === "Escape") {
      setOpen(false);
    }
  }

  return (
    <div ref={rootRef} className="relative">
      <input
        id={id}
        role="combobox"
        aria-expanded={open}
        aria-controls={listId}
        aria-autocomplete="list"
        className={INPUT_CLS}
        value={query}
        onChange={(e) => onInput(e.target.value)}
        onFocus={() => setOpen(true)}
        onKeyDown={onKeyDown}
        placeholder="search categories…"
      />
      {open && (
        <ul
          id={listId}
          role="listbox"
          className="absolute z-20 mt-2 max-h-64 w-full overflow-auto rounded-md border border-line-bright bg-surface shadow-xl"
        >
          {filtered.length === 0 ? (
            <li className="px-3.5 py-3 font-mono text-xs text-dim">
              No matching category — try the marketplace.
            </li>
          ) : (
            filtered.map((c, i) => (
              <li
                key={c.category}
                role="option"
                aria-selected={i === highlight}
                onMouseDown={(e) => { e.preventDefault(); commit(c.category); }}
                onMouseEnter={() => setHighlight(i)}
                className={`flex cursor-pointer items-center justify-between gap-3 border-b border-line px-3.5 py-2.5 last:border-b-0 ${
                  i === highlight ? "bg-surface-2" : ""
                }`}
              >
                <span className="font-medium">{c.category}</span>
                <span className="rounded-full border border-line px-2 py-0.5 font-mono text-[0.66rem] text-muted">
                  {c.agentCount} {c.agentCount === 1 ? "agent" : "agents"}
                </span>
              </li>
            ))
          )}
        </ul>
      )}
    </div>
  );
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd frontend && npx vitest run components/CategoryCombobox.test.tsx`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add frontend/components/CategoryCombobox.tsx frontend/components/CategoryCombobox.test.tsx
git commit -m "feat(frontend): strict searchable CategoryCombobox"
```

---

## Task 4: Frontend — `ShortlistPanel` as a ranked card grid in the modal

**Files:**
- Modify: `frontend/components/ShortlistPanel.tsx`
- Test: `frontend/components/ShortlistPanel.test.tsx` (rewrite)

**Interfaces:**
- Consumes: `Modal` (Task 2); `AgentOptionDTO { agentId, agentVersionId, agentName, tagline, logoUrl, price, reputationScore, availability: "AVAILABLE"|"BUSY", outputFormat, capabilityCategories }`.
- Produces: `ShortlistPanel({ open, shortlist, nearMisses, budget, onSelect, onClose })`. In-budget cards keep the accessible button name **"Select"** (the ▸ glyph is `aria-hidden`); near-miss buttons are named `Select · pays {price} cr`; rank #1 shows "★ BEST MATCH".

- [ ] **Step 1: Rewrite the failing test**

Replace `frontend/components/ShortlistPanel.test.tsx` with:

```tsx
import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { ShortlistPanel } from "@/components/ShortlistPanel";
import type { AgentOptionDTO } from "@/lib/types";

const opt = (over: Partial<AgentOptionDTO>): AgentOptionDTO => ({
  agentId: "a", agentVersionId: "v", agentName: "Agent", tagline: null, logoUrl: null,
  price: 10, reputationScore: 80, availability: "AVAILABLE", outputFormat: "JSON",
  capabilityCategories: ["summarisation"], ...over,
});

const noop = () => {};

describe("ShortlistPanel", () => {
  it("renders nothing when closed", () => {
    render(<ShortlistPanel open={false} budget={30} shortlist={[opt({})]} nearMisses={[]}
      onSelect={noop} onClose={noop} />);
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("renders ranked cards, flags the best match, and fires onSelect", () => {
    const onSelect = vi.fn();
    render(<ShortlistPanel open budget={30} nearMisses={[]} onSelect={onSelect} onClose={noop}
      shortlist={[
        opt({ agentVersionId: "v1", agentName: "Alpha" }),
        opt({ agentVersionId: "v2", agentName: "Beta", price: 8 }),
      ]} />);
    expect(screen.getByText("Alpha")).toBeTruthy();
    expect(screen.getByText(/best match/i)).toBeTruthy();
    fireEvent.click(screen.getAllByRole("button", { name: "Select" })[0]);
    expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({ agentVersionId: "v1" }));
  });

  it("renders the near-miss drawer with a pays-its-price button", () => {
    const onSelect = vi.fn();
    render(<ShortlistPanel open budget={20} shortlist={[]} onSelect={onSelect} onClose={noop}
      nearMisses={[opt({ agentVersionId: "v9", agentName: "Pricey", price: 25 })]} />);
    fireEvent.click(screen.getByRole("button", { name: /pays 25 cr/i }));
    expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({ agentVersionId: "v9" }));
  });

  it("renders the agent's profile picture when logoUrl is present", () => {
    const { container } = render(<ShortlistPanel open budget={30} nearMisses={[]} onSelect={noop} onClose={noop}
      shortlist={[opt({ agentName: "Logo Co", logoUrl: "https://cdn.test/l.png" })]} />);
    expect(container.querySelector('img[src="https://cdn.test/l.png"]')).toBeTruthy();
  });

  it("shows the empty state when both lists are empty", () => {
    render(<ShortlistPanel open budget={30} shortlist={[]} nearMisses={[]} onSelect={noop} onClose={noop} />);
    expect(screen.getByText(/no agents match/i)).toBeTruthy();
  });

  it("fires onClose from the close button", () => {
    const onClose = vi.fn();
    render(<ShortlistPanel open budget={30} shortlist={[opt({})]} nearMisses={[]} onSelect={noop} onClose={onClose} />);
    fireEvent.click(screen.getByRole("button", { name: /close/i }));
    expect(onClose).toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd frontend && npx vitest run components/ShortlistPanel.test.tsx`
Expected: FAIL — `ShortlistPanel` does not accept `open`/`onClose`; no dialog rendered.

- [ ] **Step 3: Rewrite the component**

Replace `frontend/components/ShortlistPanel.tsx` with:

```tsx
"use client";

import type { AgentOptionDTO } from "@/lib/types";
import { Button, Modal } from "@/components/ui";

interface Props {
  open: boolean;
  shortlist: AgentOptionDTO[];
  nearMisses: AgentOptionDTO[];
  budget: number;
  onSelect: (option: AgentOptionDTO) => void;
  onClose: () => void;
}

export function ShortlistPanel({ open, shortlist, nearMisses, budget, onSelect, onClose }: Props) {
  const empty = shortlist.length === 0 && nearMisses.length === 0;
  return (
    <Modal open={open} onClose={onClose} ariaLabel="Pick your agent">
      <div className="flex items-center justify-between gap-4 border-b border-line px-6 py-4">
        <div>
          <h2 className="text-lg font-extrabold tracking-tight">Pick your agent</h2>
          <p className="mt-0.5 font-mono text-xs text-muted">
            {shortlist.length} in budget · ranked for your task · budget {budget} cr
          </p>
        </div>
        <button
          type="button"
          onClick={onClose}
          aria-label="Close"
          className="grid size-8 place-items-center rounded-md border border-line font-mono text-dim transition hover:border-line-bright hover:text-fg"
        >
          ✕
        </button>
      </div>

      {empty ? (
        <p className="px-6 py-8 font-mono text-sm text-dim">
          No agents match this category yet. Adjust the category or budget and search again.
        </p>
      ) : (
        <>
          {shortlist.length > 0 && (
            <div className="grid grid-cols-1 gap-3 px-6 py-5 sm:grid-cols-2">
              {shortlist.map((o, i) => (
                <OptionCard key={o.agentVersionId} option={o} rank={i + 1} onSelect={onSelect} />
              ))}
            </div>
          )}
          {nearMisses.length > 0 && (
            <details className="border-t border-line px-6 py-4">
              <summary className="cursor-pointer font-mono text-xs uppercase tracking-wider text-muted">
                Above your budget · {nearMisses.length}
              </summary>
              <p className="mt-2 font-mono text-xs text-dim">
                These cost more than your {budget} cr budget — selecting one pays its price.
              </p>
              <div className="mt-3 space-y-3">
                {nearMisses.map((o) => (
                  <NearMissRow key={o.agentVersionId} option={o} onSelect={onSelect} />
                ))}
              </div>
            </details>
          )}
        </>
      )}
    </Modal>
  );
}

function Avatar({ name, logoUrl, size = 44 }: { name: string; logoUrl: string | null; size?: number }) {
  const dim = { width: size, height: size };
  if (logoUrl) {
    // eslint-disable-next-line @next/next/no-img-element
    return (
      <img
        src={logoUrl}
        alt=""
        style={dim}
        className="shrink-0 rounded-full border border-line-bright object-cover"
      />
    );
  }
  return (
    <span
      aria-hidden
      style={dim}
      className="grid shrink-0 place-items-center rounded-full border border-line-bright bg-surface font-mono text-base font-bold text-muted"
    >
      {name.trim().charAt(0).toUpperCase() || "?"}
    </span>
  );
}

function Stars({ score }: { score: number }) {
  const filled = Math.max(0, Math.min(5, Math.round(score / 20)));
  return (
    <span aria-label={`${score} reputation`} className="text-sm">
      <span className="text-accent">{"★".repeat(filled)}</span>
      <span className="text-line-bright">{"★".repeat(5 - filled)}</span>
    </span>
  );
}

function OptionCard({
  option,
  rank,
  onSelect,
}: {
  option: AgentOptionDTO;
  rank: number;
  onSelect: (o: AgentOptionDTO) => void;
}) {
  const best = rank === 1;
  return (
    <div
      className={`flex flex-col gap-3 rounded-lg border p-4 transition ${
        best ? "border-accent/55 glow" : "border-line bg-surface-2 hover:border-line-bright"
      }`}
    >
      <div className="flex items-start justify-between gap-2">
        <span
          className={`rounded px-1.5 py-0.5 font-mono text-[0.6rem] font-bold tracking-wider ${
            best ? "bg-accent text-ink" : "border border-line text-dim"
          }`}
        >
          {best ? "★ BEST MATCH" : `#${rank}`}
        </span>
        <span className="font-mono text-lg font-bold text-accent tabular">
          {option.price}
          <span className="text-xs font-medium text-muted"> cr</span>
        </span>
      </div>
      <div className="flex items-center gap-3">
        <Avatar name={option.agentName} logoUrl={option.logoUrl} />
        <div className="min-w-0">
          <p className="truncate font-semibold">{option.agentName}</p>
          {option.tagline && <p className="truncate text-xs text-muted">{option.tagline}</p>}
        </div>
      </div>
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 font-mono text-xs text-muted">
        <Stars score={option.reputationScore} />
        <span className="tabular">{option.reputationScore} rep</span>
        {option.outputFormat && (
          <span className="rounded border border-line px-1.5 py-0.5">{option.outputFormat}</span>
        )}
      </div>
      <div className="mt-auto flex items-center justify-between pt-1">
        <span className="inline-flex items-center gap-1.5 font-mono text-xs">
          <span
            className={`size-1.5 rounded-full ${
              option.availability === "AVAILABLE" ? "bg-accent" : "bg-amber"
            }`}
          />
          {option.availability === "AVAILABLE" ? "available" : "busy"}
        </span>
        <Button
          variant={best ? "primary" : "secondary"}
          className="!px-3 !py-1.5"
          onClick={() => onSelect(option)}
        >
          Select<span aria-hidden> ▸</span>
        </Button>
      </div>
    </div>
  );
}

function NearMissRow({
  option,
  onSelect,
}: {
  option: AgentOptionDTO;
  onSelect: (o: AgentOptionDTO) => void;
}) {
  return (
    <div className="flex items-center justify-between gap-3">
      <div className="flex min-w-0 items-center gap-2.5">
        <Avatar name={option.agentName} logoUrl={option.logoUrl} size={36} />
        <div className="min-w-0">
          <p className="truncate text-sm font-semibold">{option.agentName}</p>
          <p className="truncate font-mono text-xs text-dim">★ {option.reputationScore} rep</p>
        </div>
      </div>
      <Button
        variant="secondary"
        className="!whitespace-nowrap !px-3 !py-1.5"
        onClick={() => onSelect(option)}
      >
        Select · pays {option.price} cr
      </Button>
    </div>
  );
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd frontend && npx vitest run components/ShortlistPanel.test.tsx`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add frontend/components/ShortlistPanel.tsx frontend/components/ShortlistPanel.test.tsx
git commit -m "feat(frontend): shortlist as a ranked card grid with avatars in a modal"
```

---

## Task 5: Frontend — wire combobox + modal into the submit page

**Files:**
- Modify: `frontend/app/client/tasks/new/page.tsx`
- Test: `frontend/app/client/tasks/new/page.test.tsx` (rewrite the interactions)

**Interfaces:**
- Consumes: `CategoryCombobox` (Task 3), `ShortlistPanel` (Task 4, now modal-based).
- Produces: submit page where category is a strict combobox (Find agents disabled until a real category is picked); the shortlist opens as a modal on "Find agents"; selecting closes it into the confirm-booking card; closing offers a "Show N matched agents" reopen button.

- [ ] **Step 1: Rewrite the failing page test**

Replace `frontend/app/client/tasks/new/page.test.tsx` with:

```tsx
import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { http } from "msw";
import { server, ok } from "../../../../test/msw/handlers";
import { AuthProvider } from "@/lib/auth";
import SubmitTaskPage from "@/app/client/tasks/new/page";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  useParams: () => ({}),
}));

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => {
  server.resetHandlers();
  localStorage.clear();
});
afterAll(() => server.close());

const previewBody = {
  shortlist: [{
    agentId: "a-1", agentVersionId: "v-1", agentName: "Alpha", tagline: null, logoUrl: null,
    price: 12, reputationScore: 80, availability: "AVAILABLE", outputFormat: "JSON",
    capabilityCategories: ["summarisation"],
  }],
  nearMisses: [{
    agentId: "a-2", agentVersionId: "v-2", agentName: "Pricey", tagline: null, logoUrl: null,
    price: 40, reputationScore: 90, availability: "BUSY", outputFormat: "JSON",
    capabilityCategories: ["summarisation"],
  }],
};

function renderPage() {
  localStorage.setItem("hireai.token", "t");
  localStorage.setItem("hireai.auth", JSON.stringify({ userId: "u-1", role: "CLIENT" }));
  return render(<AuthProvider><SubmitTaskPage /></AuthProvider>);
}

function fillBasics(budget: string) {
  fireEvent.change(screen.getByLabelText(/title/i), { target: { value: "Summarise" } });
  fireEvent.change(screen.getByLabelText(/description/i), { target: { value: "the report" } });
  fireEvent.change(screen.getByLabelText(/budget/i), { target: { value: budget } });
}

// The default msw categories handler returns "summarisation" + "translation".
async function pickCategory() {
  fireEvent.change(screen.getByLabelText(/category/i), { target: { value: "summar" } });
  fireEvent.click(await screen.findByRole("option", { name: /summarisation/i }));
}

describe("submit task — shortlist flow", () => {
  it("finds agents then books an in-budget pick at the agent's price", async () => {
    let captured: Record<string, unknown> | null = null;
    server.use(
      http.get("*/api/tasks/match-preview", () => ok(previewBody)),
      http.post("*/api/tasks/direct", async ({ request }) => {
        captured = (await request.json()) as Record<string, unknown>;
        return ok({ id: "t-9", status: "SUBMITTED" });
      }),
    );
    renderPage();
    fillBasics("30");
    await pickCategory();
    fireEvent.click(screen.getByRole("button", { name: /find agents/i }));
    await screen.findByRole("dialog", { name: /pick your agent/i });
    fireEvent.click(screen.getByRole("button", { name: "Select" }));
    await screen.findByText(/confirm booking/i);
    fireEvent.click(screen.getByRole("button", { name: /confirm & book/i }));
    await waitFor(() => expect(captured).not.toBeNull());
    expect(captured!.agentId).toBe("a-1");
    expect(captured!.budget).toBe(12); // pays the agent's price, not the typed budget
  });

  it("books a near-miss at its higher price", async () => {
    let captured: Record<string, unknown> | null = null;
    server.use(
      http.get("*/api/tasks/match-preview", () => ok(previewBody)),
      http.post("*/api/tasks/direct", async ({ request }) => {
        captured = (await request.json()) as Record<string, unknown>;
        return ok({ id: "t-10", status: "SUBMITTED" });
      }),
    );
    renderPage();
    fillBasics("20");
    await pickCategory();
    fireEvent.click(screen.getByRole("button", { name: /find agents/i }));
    await screen.findByRole("dialog", { name: /pick your agent/i });
    fireEvent.click(screen.getByRole("button", { name: /above your budget/i }));
    fireEvent.click(screen.getByRole("button", { name: /pays 40 cr/i }));
    await screen.findByText(/confirm booking/i);
    fireEvent.click(screen.getByRole("button", { name: /confirm & book/i }));
    await waitFor(() => expect(captured).not.toBeNull());
    expect(captured!.agentId).toBe("a-2");
    expect(captured!.budget).toBe(40);
  });

  it("keeps Find agents disabled until a real category is selected", async () => {
    renderPage();
    fillBasics("30");
    expect(screen.getByRole("button", { name: /find agents/i })).toBeDisabled();
    await pickCategory();
    expect(screen.getByRole("button", { name: /find agents/i })).toBeEnabled();
  });

  it("persists the form draft to localStorage", async () => {
    renderPage();
    fillBasics("25");
    await waitFor(() =>
      expect(localStorage.getItem("hireai.taskDraft")).toContain("Summarise"),
    );
  });

  it("restores a saved draft on mount without blanking it in localStorage", async () => {
    localStorage.setItem(
      "hireai.taskDraft",
      JSON.stringify({ title: "Restored", description: "d", category: "summarisation", budget: 42 }),
    );
    renderPage();
    await screen.findByDisplayValue("Restored");
    const stored = JSON.parse(localStorage.getItem("hireai.taskDraft")!) as { title: string };
    expect(stored.title).toBe("Restored");
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd frontend && npx vitest run "app/client/tasks/new/page.test.tsx"`
Expected: FAIL — no dialog role (`Pick your agent`), Find agents not gated on category.

- [ ] **Step 3: Update the page**

In `frontend/app/client/tasks/new/page.tsx`:

Add the import:

```tsx
import { CategoryCombobox } from "@/components/CategoryCombobox";
```

Add `previewOpen` state next to the others:

```tsx
  const [previewOpen, setPreviewOpen] = useState(false);
```

In `onFind`, after `setPreview(result);` also open the modal:

```tsx
      setPreview(result);
      setPreviewOpen(true);
```

Replace the Category `<Field>` block:

```tsx
          <Field label="Category" htmlFor="category">
            <Input
              id="category"
              value={category}
              onChange={(e) => setCategory(e.target.value)}
              placeholder="must match an active agent's category"
              required
            />
          </Field>
```

with:

```tsx
          <Field label="Category" htmlFor="category">
            <CategoryCombobox id="category" value={category} onChange={setCategory} />
          </Field>
```

Update the Find-agents button `disabled` to also require a committed category:

```tsx
          <Button type="submit" disabled={loading || !category} className="w-full">
            {loading ? "Searching…" : "Find agents ▸"}
          </Button>
```

Replace the shortlist render block:

```tsx
      {preview && !selected && (
        <ShortlistPanel
          shortlist={preview.shortlist}
          nearMisses={preview.nearMisses}
          budget={budget}
          onSelect={setSelected}
        />
      )}
```

with:

```tsx
      {preview && (
        <ShortlistPanel
          open={previewOpen && !selected}
          shortlist={preview.shortlist}
          nearMisses={preview.nearMisses}
          budget={budget}
          onSelect={(o) => {
            setSelected(o);
            setPreviewOpen(false);
          }}
          onClose={() => setPreviewOpen(false)}
        />
      )}

      {preview && !selected && !previewOpen && (
        <Button variant="secondary" onClick={() => setPreviewOpen(true)} className="w-full">
          Show {preview.shortlist.length + preview.nearMisses.length} matched agents ▸
        </Button>
      )}
```

(The `Input` import stays — it is still used by Title/Budget. Add `variant` support: `Button` already accepts `variant`.)

- [ ] **Step 4: Run the page test to verify it passes**

Run: `cd frontend && npx vitest run "app/client/tasks/new/page.test.tsx"`
Expected: PASS (5 tests).

- [ ] **Step 5: Run the full frontend suite + lint**

Run: `cd frontend && npx vitest run && npm run lint`
Expected: all suites PASS; lint clean (0 errors).

- [ ] **Step 6: Commit**

```bash
git add frontend/app/client/tasks/new/page.tsx "frontend/app/client/tasks/new/page.test.tsx"
git commit -m "feat(frontend): submit page uses category combobox + shortlist modal"
```

---

## Task 6: Frontend — `TaskFailurePanel`

**Files:**
- Modify: `frontend/lib/types.ts`
- Create: `frontend/components/TaskFailurePanel.tsx`
- Test: `frontend/components/TaskFailurePanel.test.tsx`

**Interfaces:**
- Produces: types `ValidationCheckDTO { rule: string; passed: boolean; detail: string | null }` and `ValidationReportDTO { verdict: "PASS"|"FAIL"; checks: ValidationCheckDTO[] }`; component `TaskFailurePanel({ status: TaskStatus, budget: number, detail?: ValidationReportDTO | null })` — renders a panel for `SPEC_VIOLATION`/`TIMED_OUT`/`FAILED`/`CANCELLED`, else `null`. On `SPEC_VIOLATION` with `detail`, shows a "Show what failed" drawer listing failed checks.

- [ ] **Step 1: Add the types**

Append to `frontend/lib/types.ts` (near the Matching/Shortlist section):

```ts
// ── Validation report (spec-violation reason) ───────────────────────────────

export interface ValidationCheckDTO {
  rule: string;
  passed: boolean;
  detail: string | null;
}

export interface ValidationReportDTO {
  verdict: "PASS" | "FAIL";
  checks: ValidationCheckDTO[];
}
```

- [ ] **Step 2: Write the failing test**

Create `frontend/components/TaskFailurePanel.test.tsx`:

```tsx
import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { TaskFailurePanel } from "@/components/TaskFailurePanel";
import type { ValidationReportDTO } from "@/lib/types";

describe("TaskFailurePanel", () => {
  it("renders nothing for a non-failure status", () => {
    const { container } = render(<TaskFailurePanel status="EXECUTING" budget={10} />);
    expect(container.firstChild).toBeNull();
  });

  it.each([
    ["SPEC_VIOLATION", /didn't meet the spec/i],
    ["TIMED_OUT", /ran out of time/i],
    ["FAILED", /couldn't reach the agent/i],
    ["CANCELLED", /no agent was available/i],
  ] as const)("renders %s headline and the refund line", (status, headline) => {
    render(<TaskFailurePanel status={status} budget={17} />);
    expect(screen.getByText(headline)).toBeTruthy();
    expect(screen.getByText(/17 cr refunded/i)).toBeTruthy();
  });

  it("shows the failed-check reason for spec-violation when detail is provided", () => {
    const detail: ValidationReportDTO = {
      verdict: "FAIL",
      checks: [
        { rule: "schema", passed: true, detail: null },
        { rule: "format", passed: false, detail: "expected FILE, got none" },
      ],
    };
    render(<TaskFailurePanel status="SPEC_VIOLATION" budget={10} detail={detail} />);
    expect(screen.getByText(/format/)).toBeTruthy();
    expect(screen.getByText(/expected FILE, got none/)).toBeTruthy();
    // Passed checks are not listed.
    expect(screen.queryByText(/^schema$/)).toBeNull();
  });

  it("omits the drawer when there is no detail", () => {
    render(<TaskFailurePanel status="SPEC_VIOLATION" budget={10} detail={null} />);
    expect(screen.queryByText(/show what failed/i)).toBeNull();
  });
});
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd frontend && npx vitest run components/TaskFailurePanel.test.tsx`
Expected: FAIL — cannot resolve `@/components/TaskFailurePanel`.

- [ ] **Step 4: Create the component**

Create `frontend/components/TaskFailurePanel.tsx`:

```tsx
"use client";

import Link from "next/link";
import type { TaskStatus, ValidationReportDTO } from "@/lib/types";

interface Props {
  status: TaskStatus;
  budget: number;
  detail?: ValidationReportDTO | null;
}

type Tone = "red" | "amber" | "dim";
interface Copy {
  tone: Tone;
  icon: string;
  headline: string;
  why: string;
  action: { label: string; href: string };
}

const COPY: Partial<Record<TaskStatus, Copy>> = {
  SPEC_VIOLATION: {
    tone: "red",
    icon: "⛔",
    headline: "The result didn't meet the spec",
    why: "The agent returned something, but it failed the automated output check for this task — so you were never charged for it.",
    action: { label: "Submit a new task", href: "/client/tasks/new" },
  },
  TIMED_OUT: {
    tone: "amber",
    icon: "⏱",
    headline: "The agent ran out of time",
    why: "This agent accepted your task but didn't return a result within its deadline. That's on the agent, not you.",
    action: { label: "Try another agent", href: "/client/tasks/new" },
  },
  FAILED: {
    tone: "red",
    icon: "⚡",
    headline: "We couldn't reach the agent",
    why: "We tried to hand your task to the agent but its service didn't respond. No work started, so there's nothing to review.",
    action: { label: "Try another agent", href: "/client/tasks/new" },
  },
  CANCELLED: {
    tone: "dim",
    icon: "◎",
    headline: "No agent was available",
    why: "We kept looking for a free agent in this category but none opened up in time, so we released your task rather than hold your credits.",
    action: { label: "Browse the marketplace", href: "/marketplace" },
  },
};

const TONE_CLS: Record<Tone, string> = {
  red: "border-red/30 bg-red/10",
  amber: "border-amber/30 bg-amber/10",
  dim: "border-line-bright bg-surface-2",
};

const ICON_CLS: Record<Tone, string> = {
  red: "text-red",
  amber: "text-amber",
  dim: "text-muted",
};

export function TaskFailurePanel({ status, budget, detail }: Props) {
  const copy = COPY[status];
  if (!copy) return null;
  const failedChecks = detail?.checks.filter((c) => !c.passed) ?? [];

  return (
    <section aria-live="polite" className={`space-y-3 rounded-md border p-5 ${TONE_CLS[copy.tone]}`}>
      <div className="flex items-start gap-4">
        <span
          aria-hidden
          className={`grid size-10 shrink-0 place-items-center rounded-lg border border-line-bright bg-surface text-xl ${ICON_CLS[copy.tone]}`}
        >
          {copy.icon}
        </span>
        <div className="space-y-1.5">
          <h2 className="text-base font-extrabold tracking-tight">{copy.headline}</h2>
          <p className="text-sm text-muted">{copy.why}</p>
        </div>
      </div>

      <p className="inline-flex items-center gap-2 rounded-md border border-accent/30 bg-accent/10 px-3 py-1.5 font-mono text-xs text-accent">
        ✓ {budget} cr refunded to your wallet
      </p>

      {status === "SPEC_VIOLATION" && failedChecks.length > 0 && (
        <details className="font-mono text-xs">
          <summary className="cursor-pointer text-muted">Show what failed ▸</summary>
          <div className="mt-2 space-y-1 rounded-md border border-line bg-canvas p-3 text-muted">
            {failedChecks.map((c, i) => (
              <p key={i}>
                <span className="text-red">{c.rule}</span>
                {c.detail ? ` — ${c.detail}` : ""}
              </p>
            ))}
          </div>
        </details>
      )}

      <div>
        <Link
          href={copy.action.href}
          className="font-mono text-xs text-dim transition hover:text-accent"
        >
          {copy.action.label} ▸
        </Link>
      </div>
    </section>
  );
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd frontend && npx vitest run components/TaskFailurePanel.test.tsx`
Expected: PASS (7 assertions across the cases).

- [ ] **Step 6: Commit**

```bash
git add frontend/lib/types.ts frontend/components/TaskFailurePanel.tsx frontend/components/TaskFailurePanel.test.tsx
git commit -m "feat(frontend): TaskFailurePanel with per-failure copy + spec-violation reason"
```

---

## Task 7: Frontend — wire the failure panel + validation fetch into task detail

**Files:**
- Modify: `frontend/app/client/tasks/[id]/page.tsx`
- Modify: `frontend/test/msw/handlers.ts` (default `/validation` 404)
- Test: `frontend/test/taskFailure.test.tsx`

**Interfaces:**
- Consumes: `TaskFailurePanel` (Task 6), `ValidationReportDTO` (Task 6), `GET /api/tasks/{id}/validation` (Task 1).
- Produces: task-detail page renders `TaskFailurePanel` for the four terminal-failure statuses and fetches the validation reason on `SPEC_VIOLATION`.

- [ ] **Step 1: Add a default validation handler to msw**

In `frontend/test/msw/handlers.ts`, add inside the `handlers` array (near the other `/api/tasks/:id/...` handlers):

```ts
  // Default: a task has no validation report unless a test overrides this (only fetched on
  // SPEC_VIOLATION). Keeps onUnhandledRequest:"error" happy for the failure-panel path.
  http.get("*/api/tasks/:id/validation", () => fail("NOT_FOUND", "No validation report", 404)),
```

- [ ] **Step 2: Write the failing test**

Create `frontend/test/taskFailure.test.tsx`:

```tsx
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { http } from "msw";
import { server, ok, resetTaskDetailPolls } from "./msw/handlers";
import { AuthProvider } from "@/lib/auth";
import TaskDetailPage from "@/app/client/tasks/[id]/page";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  useParams: () => ({ id: "t-fail" }),
}));

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
beforeEach(() => {
  resetTaskDetailPolls();
  localStorage.setItem("hireai.token", "t");
  localStorage.setItem("hireai.auth", JSON.stringify({ userId: "u-1", role: "CLIENT" }));
});
afterEach(() => {
  server.resetHandlers();
  localStorage.clear();
});
afterAll(() => server.close());

function specViolationTask() {
  return {
    id: "t-fail",
    clientId: "u-1",
    title: "Summarise Q3 memo",
    description: "d",
    budget: 10,
    status: "SPEC_VIOLATION",
    outputSpec: { format: "FILE", schema: "{}", acceptanceCriteria: "a file" },
    createdAt: "2026-07-13T10:00:00Z",
  };
}

describe("task detail — failure panel", () => {
  it("shows the spec-violation panel, refund, and the real reason drawer", async () => {
    server.use(
      http.get("*/api/tasks/t-fail", () => ok(specViolationTask())),
      http.get("*/api/tasks/t-fail/validation", () =>
        ok({
          verdict: "FAIL",
          checks: [{ rule: "format", passed: false, detail: "expected FILE, got none" }],
        }),
      ),
    );

    render(<AuthProvider><TaskDetailPage /></AuthProvider>);

    expect(await screen.findByText(/didn't meet the spec/i)).toBeInTheDocument();
    expect(screen.getByText(/10 cr refunded/i)).toBeInTheDocument();
    fireEvent.click(screen.getByText(/show what failed/i));
    expect(await screen.findByText(/expected FILE, got none/i)).toBeInTheDocument();
  });

  it("renders the timeout panel with no drawer", async () => {
    server.use(
      http.get("*/api/tasks/t-fail", () =>
        ok({ ...specViolationTask(), status: "TIMED_OUT" }),
      ),
    );
    render(<AuthProvider><TaskDetailPage /></AuthProvider>);
    expect(await screen.findByText(/ran out of time/i)).toBeInTheDocument();
    expect(screen.queryByText(/show what failed/i)).toBeNull();
  });
});
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd frontend && npx vitest run test/taskFailure.test.tsx`
Expected: FAIL — no "didn't meet the spec" text (panel not wired yet).

- [ ] **Step 4: Wire the page**

In `frontend/app/client/tasks/[id]/page.tsx`:

Add imports:

```tsx
import { TaskFailurePanel } from "@/components/TaskFailurePanel";
import type { TaskDTO, TaskResultDTO, TaskStatus, DisputeOutcomeDTO, ValidationReportDTO } from "@/lib/types";
```

(Replace the existing `import type { TaskDTO, TaskResultDTO, TaskStatus, DisputeOutcomeDTO } from "@/lib/types";` line with the one above.)

Add the terminal-failure set below `RESULT_READY`:

```tsx
/** Terminal auto-refund failures that get a plain-English panel. */
const TERMINAL_FAILURE: ReadonlySet<TaskStatus> = new Set<TaskStatus>([
  "SPEC_VIOLATION",
  "TIMED_OUT",
  "FAILED",
  "CANCELLED",
]);
```

Add validation state next to the others:

```tsx
  const [validation, setValidation] = useState<ValidationReportDTO | null>(null);
```

Add a fetch effect (place after the RESOLVED-outcome effect):

```tsx
  // On a spec-violation, fetch the real failing-check reason for the "show what failed" drawer.
  // Best-effort: a 404 (no report) or any error just leaves the drawer off.
  useEffect(() => {
    if (task?.status !== "SPEC_VIOLATION") return;
    let cancelled = false;
    api<ValidationReportDTO>(`/tasks/${id}/validation`)
      .then((v) => { if (!cancelled) setValidation(v); })
      .catch(() => { /* no report — panel still renders without the drawer */ });
    return () => { cancelled = true; };
  }, [task?.status, id]);
```

Render the panel — add it right after the `AWAITING_CAPACITY` `<section>` block (they are mutually exclusive statuses):

```tsx
        {TERMINAL_FAILURE.has(task.status) && (
          <TaskFailurePanel status={task.status} budget={task.budget} detail={validation} />
        )}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd frontend && npx vitest run test/taskFailure.test.tsx`
Expected: PASS (2 tests).

- [ ] **Step 6: Run the full frontend suite + lint**

Run: `cd frontend && npx vitest run && npm run lint`
Expected: all suites PASS; lint clean.

- [ ] **Step 7: Commit**

```bash
git add "frontend/app/client/tasks/[id]/page.tsx" frontend/test/msw/handlers.ts frontend/test/taskFailure.test.tsx
git commit -m "feat(frontend): show failure panels + spec-violation reason on task detail"
```

---

## Final verification (after all tasks)

- [ ] `mvn -f backend/pom.xml -q -B test` → BUILD SUCCESS.
- [ ] `cd frontend && npm run lint` → 0 errors.
- [ ] `cd frontend && npx vitest run` → all suites green.
- [ ] `cd frontend && npm run build` → succeeds (Next 16 production build).
- [ ] Live E2E (per `docs/details/demo-runbook.md`): category dropdown filters + gates submit; shortlist opens as a modal with avatars + best-match; book at agent price; drive a `TEST SpecViolation` / `TEST Timeout` / `TEST DeadWebhook` task and confirm the failure panel + refund line (and the reason drawer for spec-violation).
- [ ] Update `CLAUDE.md` build-status + `docs/details/frontend.md` route/component notes and test counts; push the branch (updates PR #21). **Do not merge** without explicit go-ahead.

---

## Self-Review

**Spec coverage:**
- Feature 1 (strict searchable category picker, counts, fallback) → Task 3 + Task 5. ✓
- Feature 2 (modal popout, ranked cards, best-match, avatar, near-miss drawer, pay-the-price) → Task 2 + Task 4 + Task 5. ✓
- Feature 3 (failure panels + refund line + real spec-violation reason via owner-scoped endpoint) → Task 1 + Task 6 + Task 7. ✓
- Non-goals honored: no migration, no money-path change, no new states. ✓
- Invariant #5 (ownership on the read) → Task 1 Step 8 + controller tests (Step 6). ✓
- Testing strategy (vitest per component, backend unit + MockMvc, lint gate) → covered per task + final verification. ✓

**Placeholder scan:** No TBD/TODO; every code step carries full code and exact run commands. ✓

**Type consistency:** `ValidationReadAppService.latestForTask(UUID): Optional<ValidationReportModel>` used identically in Task 1 impl, controller, and tests. `ValidationReportDTO(verdict, checks[])` / `CheckDTO(rule, passed, detail)` consistent backend↔frontend (`ValidationReportDTO`/`ValidationCheckDTO`). `ShortlistPanel` prop set `{open, shortlist, nearMisses, budget, onSelect, onClose}` consistent across Task 4 component, Task 4 test, and Task 5 page wiring. In-budget select button accessible name is exactly **"Select"** (▸ is `aria-hidden`) in both the component and the page/panel tests. `CategoryCombobox({value,onChange,id})` consistent across Task 3 and Task 5. ✓
