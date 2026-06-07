# Frontend Foundation Implementation Plan (B1 — Next.js shell, typed API client, auth, UI kit)

**REQUIRED SUB-SKILL:** `agentic-workers:executing-plans` — execute this plan one task at a time, run the named test/verify command after every step, and STOP at each checkpoint until the command output matches the stated expectation. The implementer should also load the **`/frontend-design`** skill before writing any component (Task 6 onward) so the UI kit avoids generic AI aesthetics.

## Goal

Stand up the **greenfield `frontend/`** Next.js app and the shared foundation that every screen in plan **B2** imports: the Next.js scaffold, the `/api/*` rewrite proxy, the typed contract layer (`lib/types.ts`), the envelope-unwrapping fetch client (`lib/api.ts`), the localStorage-backed auth context (`lib/auth.tsx`), a hand-rolled Tailwind UI kit, and the root layout + nav + auth guards.

This is **Part B1** of `docs/superpowers/specs/2026-06-06-frontend-demo-slice-design.md` (the screens are B2). It writes **no screen** beyond `/login` wiring needed to prove auth; it produces the primitives the screens depend on, with tests against a mocked `fetch`/API (no live backend).

### Pinned public names (B2 imports these verbatim — do not rename)

| Name | Module | Shape |
|---|---|---|
| `api<T>(path, init?)` | `lib/api.ts` | `(path: string, init?: RequestInit) => Promise<T>` — unwraps `WebResult<T>.data` |
| `ApiError` | `lib/api.ts` | `class ApiError extends Error { code: string; message: string; status: number }` |
| `isPendingError(e)` | `lib/api.ts` | `(e: unknown) => boolean` — true for the result-endpoint 404 "pending" signal |
| `AuthProvider` | `lib/auth.tsx` | React provider, wraps the app in `app/layout.tsx` |
| `useAuth()` | `lib/auth.tsx` | `() => { token, userId, role, login(email,password), logout() }` |
| `RequireAuth` | `lib/auth.tsx` | client guard component; optional `role` prop for role-gating |
| `Button` `Input` `Select` `Card` `Badge` `Field` | `components/ui/*` | typed Tailwind primitives |
| `statusColor(status)` | `components/ui/Badge.tsx` | `(status: string) => string` — Tailwind classes for the task/agent lifecycle |
| `WebResult` `LoginResponse` `WalletDTO` `AgentDTO` `AgentVersionDTO` `OutputSpecDTO` `TaskDTO` `TaskResultDTO` `TaskStatus` `AgentStatus` `OutputFormat` + request bodies | `lib/types.ts` | see Task 3 |

### Enum values (read from the backend — the source of truth)

- **`OutputFormat`** (`task/enums/OutputFormat.java`): `TEXT | JSON | FILE`.
- **`TaskStatus`** (`task/enums/TaskStatus.java`): `SUBMITTED | QUEUED | EXECUTING | RESULT_RECEIVED | PENDING_REVIEW | RESOLVED | AWAITING_CAPACITY | TIMED_OUT | SPEC_VIOLATION | FAILED | CANCELLED`.
- **`AgentStatus`** (`agent/enums/AgentStatus.java`): `PENDING_VERIFICATION | ACTIVE | SUSPENDED | DEACTIVATED`.

## Architecture

- **Proxy, not CORS.** `next.config.ts` rewrites `/api/:path*` → `${BACKEND_URL||'http://localhost:8080'}/api/:path*`. The browser only ever calls same-origin `/api/...`; the `Authorization` header passes straight through.
- **One fetch chokepoint.** Every call goes through `api<T>`: it injects the bearer token, fetches `/api${path}`, parses the `WebResult<T>` envelope, returns `data` on success, and throws `ApiError{code,message,status}` otherwise. `401` clears the token and bounces to `/login`; a `404` is wrapped so `isPendingError` lets the task page treat "no result yet" as keep-polling.
- **Demo-grade auth.** `AuthProvider` holds `{token,userId,role}` in React state mirrored to `localStorage` (key `hireai.auth`). `login()` POSTs `/api/auth/login`; `logout()` clears both. `RequireAuth` is a client component that redirects to `/login` when unauthenticated and (optionally) when the role does not match.
- **Hand-rolled UI kit.** Tailwind-only primitives (no component library), each a typed `forwardRef` where it helps form usage. `statusColor` centralises the lifecycle colour map so badges are consistent across screens.

### Tech stack & conventions

- Next.js (latest, **App Router**, TypeScript, Tailwind, ESLint), **no `src/` dir**, import alias `@/*`, **no Turbopack flag** (default webpack — keeps `next.config` rewrites unsurprising).
- Tests: **Vitest** + `@testing-library/react` + `@testing-library/jest-dom` + `jsdom` + **MSW** (used by B2; installed here) + `@vitejs/plugin-react`. Test files live next to their subject as `*.test.ts(x)`.
- All commands run from the **`frontend/`** directory (the scaffold creates it). Each task ends with a **conventional commit** (`chore:`/`feat:`/`test:`) — **NO `Co-Authored-By` lines.**
- Node 20+ assumed (Next.js requirement). The plan pins exact `npx vitest run <file>` commands per task.

---

## Task 1 — Scaffold the Next.js app + test tooling

Greenfield: `frontend/` does not exist yet. Run `create-next-app` non-interactively with every flag set so there are no prompts.

### 1a. Create the app

From the **repo root** (`C:/Users/shaoxian04/Documents/HireAI`):

```
npx create-next-app@latest frontend --typescript --tailwind --eslint --app --no-src-dir --import-alias "@/*" --no-turbopack --use-npm --yes
```

Flag choices (stated explicitly): TypeScript **on**, Tailwind **on**, ESLint **on**, App Router **on**, `src/` dir **off** (code lives at `frontend/app`, `frontend/lib`, `frontend/components`), import alias **`@/*`**, Turbopack **off**, package manager **npm**.

### 1b. Add the test toolchain

From **`frontend/`**:

```
npm i -D vitest @testing-library/react @testing-library/jest-dom jsdom msw @vitejs/plugin-react
```

### 1c. Vitest config + jsdom setup

Create `frontend/vitest.config.ts`:

```ts
import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import { fileURLToPath } from "node:url";

export default defineConfig({
  plugins: [react()],
  resolve: {
    // Mirror the tsconfig "@/*" alias so tests import the same way components do.
    alias: { "@": fileURLToPath(new URL("./", import.meta.url)) },
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./vitest.setup.ts"],
    include: ["**/*.test.{ts,tsx}"],
    css: false,
  },
});
```

Create `frontend/vitest.setup.ts`:

```ts
import "@testing-library/jest-dom/vitest";
import { afterEach } from "vitest";
import { cleanup } from "@testing-library/react";

// Unmount React trees and reset jsdom state between tests.
afterEach(() => cleanup());
```

### 1d. Test script

Edit `frontend/package.json` and add to `"scripts"`:

```json
    "test": "vitest run",
    "test:watch": "vitest"
```

### 1e. Verify the scaffold builds and the (empty) test runner starts

From **`frontend/`**:

```
npm run build
npx vitest run
```

Expect: `npm run build` → Next.js compiles the default app successfully. `npx vitest run` → exits 0 reporting **"No test files found"** (no tests yet — that is the expected baseline).

**Commit:**

```
git add frontend
git commit -m "chore: scaffold Next.js app router frontend with vitest + testing-library"
```

---

## Task 2 — `/api/*` rewrite proxy

`create-next-app` emits `next.config.ts` (TS). Replace its body so the browser's same-origin `/api/*` calls proxy to the backend, defaulting to `http://localhost:8080` when `BACKEND_URL` is unset.

Overwrite `frontend/next.config.ts`:

```ts
import type { NextConfig } from "next";

/** Backend origin the /api proxy targets. Override per-env with BACKEND_URL. */
const BACKEND_URL = process.env.BACKEND_URL || "http://localhost:8080";

const nextConfig: NextConfig = {
  async rewrites() {
    // Browser calls same-origin /api/... ; Next forwards to the backend, so no CORS is needed.
    // The Authorization header passes through unchanged.
    return [{ source: "/api/:path*", destination: `${BACKEND_URL}/api/:path*` }];
  },
};

export default nextConfig;
```

Document the env var. Create `frontend/.env.example`:

```
# Backend origin the /api/* proxy forwards to (see next.config.ts).
BACKEND_URL=http://localhost:8080
```

### Verify

```
npm run build
```

Expect: BUILD SUCCESS (the rewrite is config-only; Next validates it at build time). No commit-blocking test here — the proxy is exercised end-to-end by the demo runbook.

**Commit:**

```
git add frontend/next.config.ts frontend/.env.example
git commit -m "feat: proxy /api/* to the backend via next.config rewrite"
```

---

## Task 3 — `lib/types.ts` (contract types)

Pure types mirroring the spec's API contracts and the three backend enums. No runtime, no test (it is type-only; consumers' tests exercise it). String-union types use the **exact** backend enum spellings.

Create `frontend/lib/types.ts`:

```ts
// ── Backend enum mirrors (exact spellings — see OutputFormat/TaskStatus/AgentStatus .java) ──

/** Declared deliverable shape (task/enums/OutputFormat.java). */
export type OutputFormat = "TEXT" | "JSON" | "FILE";

/** Full task lifecycle (task/enums/TaskStatus.java). */
export type TaskStatus =
  | "SUBMITTED"
  | "QUEUED"
  | "EXECUTING"
  | "RESULT_RECEIVED"
  | "PENDING_REVIEW"
  | "RESOLVED"
  | "AWAITING_CAPACITY"
  | "TIMED_OUT"
  | "SPEC_VIOLATION"
  | "FAILED"
  | "CANCELLED";

/** Agent lifecycle (agent/enums/AgentStatus.java). */
export type AgentStatus =
  | "PENDING_VERIFICATION"
  | "ACTIVE"
  | "SUSPENDED"
  | "DEACTIVATED";

export type Role = "CLIENT" | "BUILDER" | "ADMIN";

// ── Response envelope ──

/** Every backend response is wrapped in this. `data` is null on error. */
export interface WebResult<T> {
  success: boolean;
  code: string;
  message: string;
  data: T | null;
}

// ── Auth ──

export interface LoginRequest {
  email: string;
  password: string;
}

export interface LoginResponse {
  token: string;
  userId: string;
  role: Role;
}

// ── Wallet ──

export interface WalletDTO {
  walletId: string;
  availableBalance: number;
  escrowBalance: number;
}

export interface TopupRequest {
  amount: number;
}

// ── Output spec (shared by agent registration & task submission) ──

export interface OutputSpecDTO {
  format: OutputFormat;
  schema: string;
  acceptanceCriteria: string;
}

// ── Agents ──

export interface AgentVersionDTO {
  outputSpec: OutputSpecDTO;
  capabilityCategories: string[];
  webhookUrl: string;
  maxExecutionSeconds: number;
  price: number;
}

export interface AgentDTO {
  id: string;
  ownerId: string;
  name: string;
  status: AgentStatus;
  currentVersionId: string;
  reputationScore: number;
  currentVersion: AgentVersionDTO;
  createdAt: string;
}

export interface CreateAgentRequest {
  name: string;
  outputSpec: OutputSpecDTO;
  capabilityCategories: string[];
  webhookUrl: string;
  maxExecutionSeconds: number;
  price: number;
}

// ── Tasks ──

export interface TaskDTO {
  id: string;
  clientId: string;
  title: string;
  description: string;
  budget: number;
  status: TaskStatus;
  outputSpec: OutputSpecDTO;
  createdAt: string;
}

export interface CreateTaskRequest {
  title: string;
  description: string;
  category: string;
  budget: number;
  outputSpec: OutputSpecDTO;
}

export interface TaskResultDTO {
  taskId: string;
  agentStatus: string;
  resultPayloadJson: string;
  resultUrl: string | null;
  receivedAt: string;
}
```

### Verify (typecheck only)

```
npx tsc --noEmit
```

Expect: no type errors.

**Commit:**

```
git add frontend/lib/types.ts
git commit -m "feat: add lib/types.ts mirroring backend API contracts and enums"
```

---

## Task 4 — `lib/api.ts` (typed fetch client) — RED → GREEN

### 4a. Failing test (RED)

Create `frontend/lib/api.test.ts`:

```ts
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api, ApiError, isPendingError } from "./api";

const ok = <T>(data: T) =>
  new Response(JSON.stringify({ success: true, code: "OK", message: "", data }), { status: 200 });

const fail = (status: number, code: string, message = "") =>
  new Response(JSON.stringify({ success: false, code, message, data: null }), { status });

describe("api()", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
  });
  afterEach(() => vi.unstubAllGlobals());

  it("unwraps WebResult.data on success", async () => {
    const fetchMock = vi.fn().mockResolvedValue(ok({ id: "t1" }));
    vi.stubGlobal("fetch", fetchMock);

    const data = await api<{ id: string }>("/tasks/t1");

    expect(data).toEqual({ id: "t1" });
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("/api/tasks/t1");
    expect((init.headers as Record<string, string>).Authorization).toBeUndefined();
  });

  it("attaches the bearer token from localStorage", async () => {
    localStorage.setItem("hireai.token", "jwt-123");
    const fetchMock = vi.fn().mockResolvedValue(ok({ id: "t1" }));
    vi.stubGlobal("fetch", fetchMock);

    await api("/tasks/t1");

    const [, init] = fetchMock.mock.calls[0];
    expect((init.headers as Record<string, string>).Authorization).toBe("Bearer jwt-123");
  });

  it("throws ApiError with code/message/status on !success", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(fail(400, "VALIDATION_ERROR", "bad input")));

    await expect(api("/tasks")).rejects.toMatchObject({
      name: "ApiError",
      code: "VALIDATION_ERROR",
      message: "bad input",
      status: 400,
    });
  });

  it("clears the token and redirects to /login on 401", async () => {
    localStorage.setItem("hireai.token", "jwt-123");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(fail(401, "UNAUTHORIZED")));
    // window.location.assign is read-only in jsdom; spy on the method, not the whole object.
    const assign = vi.spyOn(window.location, "assign").mockImplementation(() => {});

    await expect(api("/wallet")).rejects.toBeInstanceOf(ApiError);
    expect(localStorage.getItem("hireai.token")).toBeNull();
    expect(assign).toHaveBeenCalledWith("/login");
  });

  it("isPendingError() is true only for a 404 ApiError", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(fail(404, "NOT_FOUND")));
    const err = await api("/tasks/x/result").catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(isPendingError(err)).toBe(true);
    expect(isPendingError(new ApiError("OTHER", "", 500))).toBe(false);
  });
});
```

Run (RED):

```
npx vitest run lib/api.test.ts
```

Expect: FAIL — `./api` does not exist (import error).

### 4b. Implementation (GREEN)

Create `frontend/lib/api.ts`:

```ts
import type { WebResult } from "./types";

/** localStorage key for the raw JWT (the auth context owns the richer session). */
export const TOKEN_KEY = "hireai.token";

/** Thrown for any non-success response. `status` is the HTTP code; `code` is the backend ResultCode. */
export class ApiError extends Error {
  readonly code: string;
  readonly status: number;
  constructor(code: string, message: string, status: number) {
    super(message);
    this.name = "ApiError";
    this.code = code;
    this.status = status;
  }
}

/** True for the result-endpoint 404 the UI treats as "pending, keep polling". */
export function isPendingError(e: unknown): boolean {
  return e instanceof ApiError && e.status === 404;
}

function readToken(): string | null {
  if (typeof localStorage === "undefined") return null;
  return localStorage.getItem(TOKEN_KEY);
}

function handleUnauthorized(): void {
  if (typeof localStorage !== "undefined") localStorage.removeItem(TOKEN_KEY);
  if (typeof window !== "undefined") window.location.assign("/login");
}

/**
 * The single HTTP chokepoint. Injects the bearer token, calls same-origin `/api${path}`, parses the
 * `WebResult<T>` envelope, returns `data` on success, and throws `ApiError{code,message,status}`
 * otherwise. A 401 clears the token and redirects to /login; a 404 surfaces as an ApiError that
 * `isPendingError` recognises.
 */
export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const token = readToken();
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    ...(init?.headers as Record<string, string> | undefined),
  };
  if (token) headers.Authorization = `Bearer ${token}`;

  const res = await fetch(`/api${path}`, { ...init, headers });

  if (res.status === 401) {
    handleUnauthorized();
    throw new ApiError("UNAUTHORIZED", "Session expired", 401);
  }

  let body: WebResult<T> | null = null;
  try {
    body = (await res.json()) as WebResult<T>;
  } catch {
    // Non-JSON body (e.g. gateway error) — surface the raw status.
    throw new ApiError("UNKNOWN", res.statusText || "Request failed", res.status);
  }

  if (!res.ok || !body.success) {
    throw new ApiError(body.code || "UNKNOWN", body.message || res.statusText, res.status);
  }
  return body.data as T;
}
```

Run (GREEN):

```
npx vitest run lib/api.test.ts
```

Expect: PASS (5 tests).

**Commit:**

```
git add frontend/lib/api.ts frontend/lib/api.test.ts
git commit -m "feat: add lib/api typed fetch client with WebResult unwrap and ApiError"
```

---

## Task 5 — `lib/auth.tsx` (auth context) — RED → GREEN

`login` persists the JWT under the **same `TOKEN_KEY`** `lib/api.ts` reads, plus the session (`userId`, `role`) under `hireai.auth`, so the two modules stay in sync.

### 5a. Failing test (RED)

Create `frontend/lib/auth.test.tsx`:

```tsx
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AuthProvider, useAuth } from "./auth";
import { TOKEN_KEY } from "./api";

// userEvent ships with testing-library/react in recent versions; if absent, swap for fireEvent.

function Harness() {
  const { token, role, userId, login, logout } = useAuth();
  return (
    <div>
      <span data-testid="token">{token ?? "none"}</span>
      <span data-testid="role">{role ?? "none"}</span>
      <span data-testid="userId">{userId ?? "none"}</span>
      <button onClick={() => login("a@b.c", "pw")}>login</button>
      <button onClick={() => logout()}>logout</button>
    </div>
  );
}

const loginOk = () =>
  new Response(
    JSON.stringify({ success: true, code: "OK", message: "", data: { token: "jwt-123", userId: "u1", role: "CLIENT" } }),
    { status: 200 },
  );

describe("AuthProvider / useAuth", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
  });
  afterEach(() => vi.unstubAllGlobals());

  it("starts unauthenticated", () => {
    render(<AuthProvider><Harness /></AuthProvider>);
    expect(screen.getByTestId("token").textContent).toBe("none");
  });

  it("login() stores token + session and exposes identity", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(loginOk()));
    render(<AuthProvider><Harness /></AuthProvider>);

    await userEvent.click(screen.getByText("login"));

    await waitFor(() => expect(screen.getByTestId("token").textContent).toBe("jwt-123"));
    expect(screen.getByTestId("role").textContent).toBe("CLIENT");
    expect(screen.getByTestId("userId").textContent).toBe("u1");
    expect(localStorage.getItem(TOKEN_KEY)).toBe("jwt-123");
  });

  it("logout() clears token + session", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(loginOk()));
    render(<AuthProvider><Harness /></AuthProvider>);
    await userEvent.click(screen.getByText("login"));
    await waitFor(() => expect(screen.getByTestId("token").textContent).toBe("jwt-123"));

    await act(async () => { await userEvent.click(screen.getByText("logout")); });

    expect(screen.getByTestId("token").textContent).toBe("none");
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull();
  });

  it("rehydrates a persisted session on mount", () => {
    localStorage.setItem(TOKEN_KEY, "jwt-xyz");
    localStorage.setItem("hireai.auth", JSON.stringify({ userId: "u9", role: "BUILDER" }));
    render(<AuthProvider><Harness /></AuthProvider>);
    expect(screen.getByTestId("token").textContent).toBe("jwt-xyz");
    expect(screen.getByTestId("role").textContent).toBe("BUILDER");
  });
});
```

> If `@testing-library/user-event` is not already present (it ships transitively with recent `@testing-library/react`, but pin it to be safe), add it: `npm i -D @testing-library/user-event`. Do this in **5a** before running the test.

Run (RED):

```
npm i -D @testing-library/user-event
npx vitest run lib/auth.test.tsx
```

Expect: FAIL — `./auth` does not exist.

### 5b. Implementation (GREEN)

Create `frontend/lib/auth.tsx`:

```tsx
"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import { api, TOKEN_KEY } from "./api";
import type { LoginResponse, Role } from "./types";

const SESSION_KEY = "hireai.auth";

interface Session {
  userId: string;
  role: Role;
}

interface AuthValue {
  token: string | null;
  userId: string | null;
  role: Role | null;
  login: (email: string, password: string) => Promise<LoginResponse>;
  logout: () => void;
}

const AuthContext = createContext<AuthValue | null>(null);

function readPersisted(): { token: string | null; session: Session | null } {
  if (typeof localStorage === "undefined") return { token: null, session: null };
  const token = localStorage.getItem(TOKEN_KEY);
  const raw = localStorage.getItem(SESSION_KEY);
  let session: Session | null = null;
  if (raw) {
    try {
      session = JSON.parse(raw) as Session;
    } catch {
      session = null; // corrupt session → treat as logged out
    }
  }
  return { token, session };
}

/** Demo-grade auth context: JWT + identity in React state mirrored to localStorage. */
export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [token, setToken] = useState<string | null>(null);
  const [session, setSession] = useState<Session | null>(null);

  // Rehydrate after mount so SSR and the first client render agree (no hydration mismatch).
  useEffect(() => {
    const { token: t, session: s } = readPersisted();
    setToken(t);
    setSession(s);
  }, []);

  const login = useCallback(async (email: string, password: string) => {
    const res = await api<LoginResponse>("/auth/login", {
      method: "POST",
      body: JSON.stringify({ email, password }),
    });
    localStorage.setItem(TOKEN_KEY, res.token);
    localStorage.setItem(SESSION_KEY, JSON.stringify({ userId: res.userId, role: res.role }));
    setToken(res.token);
    setSession({ userId: res.userId, role: res.role });
    return res;
  }, []);

  const logout = useCallback(() => {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(SESSION_KEY);
    setToken(null);
    setSession(null);
  }, []);

  const value = useMemo<AuthValue>(
    () => ({ token, userId: session?.userId ?? null, role: session?.role ?? null, login, logout }),
    [token, session, login, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within <AuthProvider>");
  return ctx;
}
```

Run (GREEN):

```
npx vitest run lib/auth.test.tsx
```

Expect: PASS (4 tests).

**Commit:**

```
git add frontend/lib/auth.tsx frontend/lib/auth.test.tsx frontend/package.json frontend/package-lock.json
git commit -m "feat: add lib/auth AuthProvider + useAuth with localStorage persistence"
```

---

## Task 6 — UI kit (`components/ui/*`) + `statusColor`

Hand-rolled Tailwind primitives. **Load the `/frontend-design` skill first** so these avoid generic AI styling — the design notes below are intent, not pixel specs; the implementer should make them genuinely polished (consistent spacing scale, focus rings, sensible disabled states). Tests here are light (one render smoke + the `statusColor` map, which is pure logic and worth asserting because B2's badges depend on exact lifecycle coverage).

### 6a. `statusColor` test (RED)

Create `frontend/components/ui/Badge.test.tsx`:

```tsx
import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { Badge, statusColor } from "./Badge";

describe("statusColor", () => {
  it("maps every task lifecycle status to a non-empty class string", () => {
    const statuses = [
      "SUBMITTED", "QUEUED", "EXECUTING", "RESULT_RECEIVED", "PENDING_REVIEW",
      "RESOLVED", "AWAITING_CAPACITY", "TIMED_OUT", "SPEC_VIOLATION", "FAILED", "CANCELLED",
    ];
    for (const s of statuses) expect(statusColor(s).length).toBeGreaterThan(0);
  });

  it("maps agent statuses too", () => {
    for (const s of ["PENDING_VERIFICATION", "ACTIVE", "SUSPENDED", "DEACTIVATED"]) {
      expect(statusColor(s).length).toBeGreaterThan(0);
    }
  });

  it("falls back to a neutral class for unknown statuses", () => {
    expect(statusColor("WHATEVER").length).toBeGreaterThan(0);
  });
});

describe("Badge", () => {
  it("renders its status text", () => {
    render(<Badge status="ACTIVE" />);
    expect(screen.getByText("ACTIVE")).toBeInTheDocument();
  });
});
```

Run (RED):

```
npx vitest run components/ui/Badge.test.tsx
```

Expect: FAIL — `./Badge` does not exist.

### 6b. Implement the kit (GREEN)

Create `frontend/components/ui/Badge.tsx`:

```tsx
import type { ReactNode } from "react";

/**
 * Tailwind colour classes per lifecycle status. Covers the full TaskStatus and AgentStatus
 * enums (see lib/types.ts); unknown values fall back to neutral so the UI never crashes on a
 * status the backend adds later. Greens = good/terminal-success, blues = in-flight,
 * ambers = waiting/attention, reds = failure/terminal-bad, slate = neutral/inactive.
 */
const STATUS_CLASSES: Record<string, string> = {
  // task lifecycle
  SUBMITTED: "bg-slate-100 text-slate-700",
  QUEUED: "bg-blue-100 text-blue-700",
  EXECUTING: "bg-indigo-100 text-indigo-700",
  RESULT_RECEIVED: "bg-emerald-100 text-emerald-700",
  PENDING_REVIEW: "bg-amber-100 text-amber-700",
  RESOLVED: "bg-emerald-100 text-emerald-800",
  AWAITING_CAPACITY: "bg-amber-100 text-amber-700",
  TIMED_OUT: "bg-red-100 text-red-700",
  SPEC_VIOLATION: "bg-red-100 text-red-700",
  FAILED: "bg-red-100 text-red-700",
  CANCELLED: "bg-slate-200 text-slate-600",
  // agent lifecycle
  PENDING_VERIFICATION: "bg-amber-100 text-amber-700",
  ACTIVE: "bg-emerald-100 text-emerald-700",
  SUSPENDED: "bg-red-100 text-red-700",
  DEACTIVATED: "bg-slate-200 text-slate-600",
};

const NEUTRAL = "bg-slate-100 text-slate-700";

/** Tailwind classes for a lifecycle status badge. Falls back to neutral for unknown values. */
export function statusColor(status: string): string {
  return STATUS_CLASSES[status] ?? NEUTRAL;
}

export function Badge({ status, children }: { status: string; children?: ReactNode }) {
  return (
    <span
      className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ${statusColor(
        status,
      )}`}
    >
      {children ?? status}
    </span>
  );
}
```

Create `frontend/components/ui/Button.tsx`:

```tsx
import { forwardRef, type ButtonHTMLAttributes } from "react";

type Variant = "primary" | "secondary" | "ghost" | "danger";

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
}

const VARIANTS: Record<Variant, string> = {
  primary: "bg-slate-900 text-white hover:bg-slate-800 focus-visible:ring-slate-900",
  secondary: "bg-white text-slate-900 border border-slate-300 hover:bg-slate-50 focus-visible:ring-slate-400",
  ghost: "bg-transparent text-slate-700 hover:bg-slate-100 focus-visible:ring-slate-300",
  danger: "bg-red-600 text-white hover:bg-red-500 focus-visible:ring-red-600",
};

/** Typed Tailwind button with variants, focus ring, and a disabled state. */
export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = "primary", className = "", ...props },
  ref,
) {
  return (
    <button
      ref={ref}
      className={`inline-flex items-center justify-center rounded-md px-4 py-2 text-sm font-medium transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50 ${VARIANTS[variant]} ${className}`}
      {...props}
    />
  );
});
```

Create `frontend/components/ui/Input.tsx`:

```tsx
import { forwardRef, type InputHTMLAttributes } from "react";

/** Typed Tailwind text input. Use inside <Field> for a label + error message. */
export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(
  function Input({ className = "", ...props }, ref) {
    return (
      <input
        ref={ref}
        className={`block w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900 shadow-sm placeholder:text-slate-400 focus:border-slate-500 focus:outline-none focus:ring-1 focus:ring-slate-500 disabled:bg-slate-50 ${className}`}
        {...props}
      />
    );
  },
);
```

Create `frontend/components/ui/Select.tsx`:

```tsx
import { forwardRef, type SelectHTMLAttributes } from "react";

/** Typed Tailwind select. Pass <option>s as children; e.g. the OutputFormat enum values. */
export const Select = forwardRef<HTMLSelectElement, SelectHTMLAttributes<HTMLSelectElement>>(
  function Select({ className = "", children, ...props }, ref) {
    return (
      <select
        ref={ref}
        className={`block w-full rounded-md border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 shadow-sm focus:border-slate-500 focus:outline-none focus:ring-1 focus:ring-slate-500 ${className}`}
        {...props}
      >
        {children}
      </select>
    );
  },
);
```

Create `frontend/components/ui/Card.tsx`:

```tsx
import type { HTMLAttributes } from "react";

/** Surface container with border + subtle shadow. Compose freely for list items and panels. */
export function Card({ className = "", ...props }: HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={`rounded-lg border border-slate-200 bg-white p-5 shadow-sm ${className}`}
      {...props}
    />
  );
}
```

Create `frontend/components/ui/Field.tsx`:

```tsx
import type { ReactNode } from "react";

interface FieldProps {
  label: string;
  htmlFor?: string;
  error?: string | null;
  hint?: string;
  children: ReactNode;
}

/** Form-row wrapper: label, control (children), optional hint, and an error message. */
export function Field({ label, htmlFor, error, hint, children }: FieldProps) {
  return (
    <div className="space-y-1.5">
      <label htmlFor={htmlFor} className="block text-sm font-medium text-slate-700">
        {label}
      </label>
      {children}
      {hint && !error ? <p className="text-xs text-slate-500">{hint}</p> : null}
      {error ? <p className="text-xs text-red-600">{error}</p> : null}
    </div>
  );
}
```

Create a barrel `frontend/components/ui/index.ts` so B2 can `import { Button, ... } from "@/components/ui"`:

```ts
export { Button } from "./Button";
export { Input } from "./Input";
export { Select } from "./Select";
export { Card } from "./Card";
export { Field } from "./Field";
export { Badge, statusColor } from "./Badge";
```

Run (GREEN):

```
npx vitest run components/ui/Badge.test.tsx
```

Expect: PASS (4 tests).

**Commit:**

```
git add frontend/components/ui
git commit -m "feat: add Tailwind UI kit (Button/Input/Select/Card/Badge/Field) + statusColor"
```

---

## Task 7 — Root layout, Nav, and auth guards

Wire `AuthProvider` into the root layout, add a top `Nav` (app name, role, logout), a client `RequireAuth` guard, and a `/login` page minimal enough to prove the loop. The guard and Nav are client components (they read `useAuth`).

### 7a. `RequireAuth` test (RED)

Create `frontend/components/RequireAuth.test.tsx`:

```tsx
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { AuthProvider } from "@/lib/auth";
import { TOKEN_KEY } from "@/lib/api";
import { RequireAuth } from "./RequireAuth";

const replace = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace, push: vi.fn() }) }));

describe("RequireAuth", () => {
  beforeEach(() => {
    localStorage.clear();
    replace.mockClear();
  });
  afterEach(() => vi.restoreAllMocks());

  it("redirects to /login when there is no token", async () => {
    render(
      <AuthProvider>
        <RequireAuth><div>secret</div></RequireAuth>
      </AuthProvider>,
    );
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/login"));
    expect(screen.queryByText("secret")).not.toBeInTheDocument();
  });

  it("renders children when authenticated", async () => {
    localStorage.setItem(TOKEN_KEY, "jwt-123");
    localStorage.setItem("hireai.auth", JSON.stringify({ userId: "u1", role: "CLIENT" }));
    render(
      <AuthProvider>
        <RequireAuth><div>secret</div></RequireAuth>
      </AuthProvider>,
    );
    await waitFor(() => expect(screen.getByText("secret")).toBeInTheDocument());
    expect(replace).not.toHaveBeenCalled();
  });

  it("redirects to /login when the role does not match", async () => {
    localStorage.setItem(TOKEN_KEY, "jwt-123");
    localStorage.setItem("hireai.auth", JSON.stringify({ userId: "u1", role: "CLIENT" }));
    render(
      <AuthProvider>
        <RequireAuth role="BUILDER"><div>builder-only</div></RequireAuth>
      </AuthProvider>,
    );
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/login"));
    expect(screen.queryByText("builder-only")).not.toBeInTheDocument();
  });
});
```

Run (RED):

```
npx vitest run components/RequireAuth.test.tsx
```

Expect: FAIL — `./RequireAuth` does not exist.

### 7b. Implement guard + Nav (GREEN)

Create `frontend/components/RequireAuth.tsx`:

```tsx
"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";
import type { Role } from "@/lib/types";

interface RequireAuthProps {
  children: React.ReactNode;
  /** When set, the user must also have this role; otherwise they are redirected to /login. */
  role?: Role;
}

/**
 * Client guard: redirects to /login when unauthenticated (or when `role` is set and does not match).
 * Renders nothing until the auth context has rehydrated, then either the children or null while the
 * redirect runs. Hard Invariant #5 is enforced server-side; this is UX-level gating only.
 */
export function RequireAuth({ children, role }: RequireAuthProps) {
  const { token, role: userRole } = useAuth();
  const router = useRouter();

  const allowed = !!token && (!role || userRole === role);

  useEffect(() => {
    // Wait for rehydration: token is null on the very first render even when persisted.
    if (typeof window === "undefined") return;
    const persisted = localStorage.getItem("hireai.token");
    if (!persisted) {
      router.replace("/login");
      return;
    }
    if (role && userRole && userRole !== role) router.replace("/login");
  }, [token, userRole, role, router]);

  return allowed ? <>{children}</> : null;
}
```

Create `frontend/components/Nav.tsx`:

```tsx
"use client";

import Link from "next/link";
import { useAuth } from "@/lib/auth";
import { Button } from "@/components/ui";

/** Top bar: app name (links to the role home), the current role, and a logout action. */
export function Nav() {
  const { role, logout } = useAuth();
  const home = role === "BUILDER" ? "/builder" : "/client";

  return (
    <header className="border-b border-slate-200 bg-white">
      <div className="mx-auto flex h-14 max-w-5xl items-center justify-between px-4">
        <Link href={role ? home : "/login"} className="text-base font-semibold text-slate-900">
          HireAI
        </Link>
        {role ? (
          <div className="flex items-center gap-3">
            <span className="text-sm text-slate-500">{role}</span>
            <Button variant="secondary" onClick={logout}>
              Log out
            </Button>
          </div>
        ) : null}
      </div>
    </header>
  );
}
```

Overwrite the generated `frontend/app/layout.tsx`:

```tsx
import type { Metadata } from "next";
import "./globals.css";
import { AuthProvider } from "@/lib/auth";
import { Nav } from "@/components/Nav";

export const metadata: Metadata = {
  title: "HireAI",
  description: "Task-driven AI agent marketplace",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body className="min-h-screen bg-slate-50 text-slate-900 antialiased">
        <AuthProvider>
          <Nav />
          <main className="mx-auto max-w-5xl px-4 py-8">{children}</main>
        </AuthProvider>
      </body>
    </html>
  );
}
```

Overwrite the generated home page `frontend/app/page.tsx` with a redirector to `/login` (B2 replaces the role homes; this keeps the root meaningful):

```tsx
import { redirect } from "next/navigation";

export default function Home() {
  redirect("/login");
}
```

Add a minimal `/login` page that uses the kit + `useAuth` (B2 will polish; this proves the loop and gives the guards a destination). Create `frontend/app/login/page.tsx`:

```tsx
"use client";

import { useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { ApiError } from "@/lib/api";
import { Button, Card, Field, Input } from "@/components/ui";

export default function LoginPage() {
  const { login } = useAuth();
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const res = await login(email, password);
      router.replace(res.role === "BUILDER" ? "/builder" : "/client");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Login failed");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mx-auto max-w-sm">
      <Card>
        <h1 className="mb-4 text-lg font-semibold">Sign in</h1>
        <form onSubmit={onSubmit} className="space-y-4">
          <Field label="Email" htmlFor="email">
            <Input id="email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
          </Field>
          <Field label="Password" htmlFor="password" error={error}>
            <Input id="password" type="password" value={password} onChange={(e) => setPassword(e.target.value)} required />
          </Field>
          <Button type="submit" disabled={busy} className="w-full">
            {busy ? "Signing in…" : "Sign in"}
          </Button>
        </form>
      </Card>
    </div>
  );
}
```

Run (GREEN):

```
npx vitest run components/RequireAuth.test.tsx
```

Expect: PASS (3 tests).

**Commit:**

```
git add frontend/app/layout.tsx frontend/app/page.tsx frontend/app/login frontend/components/Nav.tsx frontend/components/RequireAuth.tsx frontend/components/RequireAuth.test.tsx
git commit -m "feat: add root layout, Nav, RequireAuth guard, and minimal /login"
```

---

## Task 8 — Full foundation green (regression gate)

No new code. Confirm the whole frontend suite, typecheck, and production build all pass together.

```
npx vitest run
npx tsc --noEmit
npm run build
```

Expect:
- `npx vitest run` → all suites PASS (`lib/api.test.ts` 5, `lib/auth.test.tsx` 4, `components/ui/Badge.test.tsx` 4, `components/RequireAuth.test.tsx` 3 — **16 tests**).
- `npx tsc --noEmit` → no type errors.
- `npm run build` → BUILD SUCCESS (routes `/`, `/login` compile; `app/page.tsx` redirects).

If anything fails, fix the implementation (not the tests, unless a test is demonstrably wrong) — see `agentic-workers:systematic-debugging`.

**No commit** (verification only). If the working tree is clean, the foundation is complete and B2 can import every pinned name.

---

## Definition of done

- `frontend/` is a working Next.js (App Router, TS, Tailwind) app with Vitest + Testing Library + MSW installed and a `test` script.
- `/api/:path*` rewrites to `${BACKEND_URL||'http://localhost:8080'}/api/:path*` (no CORS).
- `lib/types.ts` mirrors every spec contract and the **exact** `OutputFormat`/`TaskStatus`/`AgentStatus` enum spellings.
- `api<T>(path, init?)` unwraps `WebResult<T>`, injects the bearer token, throws `ApiError{code,message,status}`, redirects on 401, and exposes `isPendingError` for the result-endpoint 404.
- `AuthProvider`/`useAuth()` persist `{token,userId,role}` to localStorage and rehydrate on mount; `RequireAuth` gates by auth and (optionally) role.
- UI kit `Button/Input/Select/Card/Badge/Field` + `statusColor` cover the full lifecycle; root layout wires `AuthProvider` + `Nav`; `/login` proves the loop.
- All pinned names exist with the exact signatures in the table above. 16 tests pass; `tsc --noEmit` and `npm run build` are clean.
- All commits conventional; **no `Co-Authored-By` lines.**

## Files added / changed

**Added**
- `frontend/vitest.config.ts`, `frontend/vitest.setup.ts`, `frontend/.env.example`
- `frontend/lib/types.ts`, `frontend/lib/api.ts`, `frontend/lib/api.test.ts`
- `frontend/lib/auth.tsx`, `frontend/lib/auth.test.tsx`
- `frontend/components/ui/{Button,Input,Select,Card,Badge,Field}.tsx`, `frontend/components/ui/index.ts`, `frontend/components/ui/Badge.test.tsx`
- `frontend/components/Nav.tsx`, `frontend/components/RequireAuth.tsx`, `frontend/components/RequireAuth.test.tsx`
- `frontend/app/login/page.tsx`

**Changed (from the scaffold)**
- `frontend/next.config.ts` (rewrite proxy)
- `frontend/package.json` (test scripts, dev deps)
- `frontend/app/layout.tsx` (AuthProvider + Nav), `frontend/app/page.tsx` (redirect to /login)
