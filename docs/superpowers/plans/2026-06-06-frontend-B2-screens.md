# Frontend B2 — Screens (Next.js App Router) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the six Client + Builder screens of the HireAI demo slice — login, builder agent list/register/activate, client wallet/top-up + task list/submit, and the live-polling task-detail-with-result view — on top of the B1 foundation, with MSW-mocked component tests for the critical flows.

**Architecture:** Next.js App Router (TypeScript + Tailwind). Every screen is a `'use client'` component that calls the backend through the B1 typed client `api<T>(path, init?)` (same-origin `/api/*`, proxied by `next.config` rewrites; `Authorization: Bearer` injected from `useAuth()`). Identity + role come from `useAuth()`; role areas (`/client`, `/builder`) are separated by client-side guards. The task-detail page polls `GET /api/tasks/{id}` every ~2s and, once `RESULT_RECEIVED`, fetches `GET /api/tasks/{id}/result`, treating the result endpoint's 404 as a "pending" signal. UI is assembled from the B1 kit (`Button/Input/Select/Card/Badge/Field`) and `statusColor()`.

**Tech Stack:** Next.js (App Router) 14+, React 18, TypeScript, Tailwind CSS; Vitest + React Testing Library + `@testing-library/user-event` + MSW (`msw`, `setupServer`) for component/flow tests; Playwright (optional smoke). The implementer SHOULD invoke the **`/frontend-design`** skill before writing JSX so the screens are polished rather than generic.

**Assumes B1 is done.** This plan consumes these exact B1 exports without redefining them:
- `lib/api.ts` → `api<T>(path, init?)`, `ApiError` (`ApiError.code: string`, `ApiError.message: string`; `ApiError.status?: number`). The result-endpoint 404 is surfaced as `ApiError` with a recognizable code — this plan keys off `err instanceof ApiError && err.status === 404` (fallback: `err.code === 'NOT_FOUND'`).
- `lib/auth.tsx` → `AuthProvider`, `useAuth()` returning `{ token, userId, role, login, logout }` where `login(body: LoginRequest) => Promise<LoginResponse>` performs the POST, persists `{token,userId,role}` to `localStorage`, and updates context; `logout()` clears it.
- `lib/types.ts` → `LoginResponse, WalletDTO, AgentDTO, TaskDTO, TaskResultDTO`, the request bodies (`LoginRequest, TopupRequest, CreateAgentRequest, CreateTaskRequest, OutputSpec`), and the unions `TaskStatus`, `AgentStatus`, `OutputFormat`.
- `components/ui/` → `Button, Input, Select, Card, Badge, Field`; and `statusColor(status: string): string` (a Tailwind class string for a `Badge`).

> **RECONCILE WITH B1 (authoritative — overrides any snippet below that disagrees):**
> - **Auth storage:** B1 stores the raw JWT under `localStorage["hireai.token"]` (what `api()` reads) and the session `{ userId, role }` (NO token) under `localStorage["hireai.auth"]`. In EVERY test that needs an authenticated user, seed **both** keys — e.g. `localStorage.setItem('hireai.token','t'); localStorage.setItem('hireai.auth', JSON.stringify({ userId:'u-1', role:'CLIENT' }))`. Do **not** put the token inside `hireai.auth`, and do not use a bare `token` key — fix any snippet below that does.
> - **`AgentDTO` is NESTED:** `capabilityCategories`, `price`, `webhookUrl`, etc. live on `agent.currentVersion` (per B1's `lib/types.ts`), so read `a.currentVersion?.capabilityCategories` / `a.currentVersion?.price`. Ignore the "if B1 flattens" fallback notes — B1 does not flatten.

**Canonical enum values (read from the backend, match exactly):**
- `OutputFormat = 'TEXT' | 'JSON' | 'FILE'` (`backend/.../task/enums/OutputFormat.java`).
- `AgentStatus = 'PENDING_VERIFICATION' | 'ACTIVE' | 'SUSPENDED' | 'DEACTIVATED'` (`backend/.../agent/enums/AgentStatus.java`).
- `TaskStatus = 'SUBMITTED' | 'QUEUED' | 'EXECUTING' | 'RESULT_RECEIVED' | 'PENDING_REVIEW' | 'RESOLVED' | 'AWAITING_CAPACITY' | 'TIMED_OUT' | 'SPEC_VIOLATION' | 'FAILED' | 'CANCELLED'` (`backend/.../task/enums/TaskStatus.java`).

**Conventions for every task:** conventional commits (`feat:` / `test:` / `chore:`); **NO `Co-Authored-By` lines**. Test commands run from `frontend/` via `npx vitest run <file>`. Files are kept focused (one screen per route file; shared helpers extracted).

---

## File structure

| File | Responsibility |
|---|---|
| `frontend/lib/outputSpecFields.tsx` | Shared `OutputSpec` sub-form (format Select + schema + acceptanceCriteria) reused by agent-new and task-new — DRY |
| `frontend/components/RoleGuard.tsx` | Client guard: redirect to `/login` when no token; optionally enforce a required role |
| `frontend/app/login/page.tsx` | Screen 1 — login form → `useAuth().login` → role redirect |
| `frontend/app/builder/page.tsx` | Screen 2 — agent list + Activate |
| `frontend/app/builder/agents/new/page.tsx` | Screen 3 — register agent form |
| `frontend/app/client/page.tsx` | Screen 4 — wallet + top-up + task list |
| `frontend/app/client/tasks/new/page.tsx` | Screen 5 — submit task form |
| `frontend/app/client/tasks/[id]/page.tsx` | Screen 6 — polled task detail + result (FULL code) |
| `frontend/test/msw/handlers.ts` | MSW request handlers + `server` for all flow tests |
| `frontend/test/login.test.tsx` | Flow test — login + role redirect |
| `frontend/test/builder.test.tsx` | Flow test — agent register + activate |
| `frontend/test/taskSubmit.test.tsx` | Flow test — task submit + redirect |
| `frontend/test/taskDetail.test.tsx` | Flow test — status poll → result render |
| `frontend/e2e/smoke.spec.ts` | Optional Playwright happy-path smoke |

These build only on B1; the shared `outputSpecFields.tsx` and `RoleGuard.tsx` are introduced first because three later screens depend on them.

---

## Task 1: Shared `OutputSpec` sub-form

**Files:**
- Create: `frontend/lib/outputSpecFields.tsx`

The `outputSpec` block (`{ format, schema, acceptanceCriteria }`) is identical in the agent-register and task-submit forms. Extract it once as a controlled fieldset so both screens stay DRY and the format `Select` lists the exact `OutputFormat` values.

- [ ] **Step 1: Write the component**

```tsx
'use client';

import { Field } from '@/components/ui/Field';
import { Select } from '@/components/ui/Select';
import { Input } from '@/components/ui/Input';
import type { OutputSpec, OutputFormat } from '@/lib/types';

const OUTPUT_FORMATS: OutputFormat[] = ['TEXT', 'JSON', 'FILE'];

export const EMPTY_OUTPUT_SPEC: OutputSpec = {
  format: 'JSON',
  schema: '',
  acceptanceCriteria: '',
};

/**
 * Controlled sub-form for the binding output contract, reused by the agent-register and
 * task-submit screens. The parent owns the `OutputSpec` state and passes `value` + `onChange`.
 */
export function OutputSpecFields({
  value,
  onChange,
}: {
  value: OutputSpec;
  onChange: (next: OutputSpec) => void;
}) {
  const set = <K extends keyof OutputSpec>(key: K, v: OutputSpec[K]) =>
    onChange({ ...value, [key]: v });

  return (
    <fieldset>
      <legend>Output contract</legend>
      <Field label="Format" htmlFor="outputSpec.format">
        <Select
          id="outputSpec.format"
          value={value.format}
          onChange={(e) => set('format', e.target.value as OutputFormat)}
        >
          {OUTPUT_FORMATS.map((f) => (
            <option key={f} value={f}>{f}</option>
          ))}
        </Select>
      </Field>
      <Field label="Schema" htmlFor="outputSpec.schema">
        <Input
          id="outputSpec.schema"
          value={value.schema}
          onChange={(e) => set('schema', e.target.value)}
          placeholder='e.g. {"type":"object"}'
        />
      </Field>
      <Field label="Acceptance criteria" htmlFor="outputSpec.acceptanceCriteria">
        <Input
          id="outputSpec.acceptanceCriteria"
          value={value.acceptanceCriteria}
          onChange={(e) => set('acceptanceCriteria', e.target.value)}
          placeholder="Plain-language criteria the deliverable must meet"
        />
      </Field>
    </fieldset>
  );
}
```

- [ ] **Step 2: Type-check it compiles**

Run: `npx tsc --noEmit`
Expected: no errors referencing `outputSpecFields.tsx` (B1 types `OutputSpec`/`OutputFormat` resolve; B1 kit imports resolve).

- [ ] **Step 3: Commit**

```bash
git add frontend/lib/outputSpecFields.tsx
git commit -m "feat: add reusable OutputSpec sub-form for agent + task forms"
```

---

## Task 2: Client-side `RoleGuard`

**Files:**
- Create: `frontend/components/RoleGuard.tsx`

Every authed screen wraps its body in `RoleGuard` to enforce Hard Invariant #5's client-side counterpart: no token → bounce to `/login`; wrong role → bounce to that role's home. Identity is read from `useAuth()` (never hardcoded).

- [ ] **Step 1: Write the component**

```tsx
'use client';

import { useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { useAuth } from '@/lib/auth';

/**
 * Client guard for authed screens. Redirects to /login when there is no token; when `role` is
 * given, redirects a mismatched user to their own home. Renders nothing until the check passes
 * (prevents a flash of protected content).
 */
export function RoleGuard({
  role,
  children,
}: {
  role?: 'CLIENT' | 'BUILDER';
  children: React.ReactNode;
}) {
  const { token, role: current } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (!token) {
      router.replace('/login');
    } else if (role && current && current !== role) {
      router.replace(current === 'CLIENT' ? '/client' : '/builder');
    }
  }, [token, role, current, router]);

  if (!token || (role && current !== role)) return null;
  return <>{children}</>;
}
```

- [ ] **Step 2: Type-check**

Run: `npx tsc --noEmit`
Expected: no errors referencing `RoleGuard.tsx`.

- [ ] **Step 3: Commit**

```bash
git add frontend/components/RoleGuard.tsx
git commit -m "feat: add RoleGuard for client-side auth + role redirects"
```

---

## Task 3: Login screen (`/login`)

**Route file:** `frontend/app/login/page.tsx`
**API call:** `POST /api/auth/login` via `useAuth().login(body: LoginRequest)` → `LoginResponse { token, userId, role }`.
**Form fields (match `LoginRequest`):** `email` (type=email), `password` (type=password).
**State:** `email: string`, `password: string`, `error: string | null`, `submitting: boolean`.
**Behaviour:** on submit → `login({email,password})`; on success `router.replace(role === 'CLIENT' ? '/client' : '/builder')`; on `ApiError` set `error` to its message. No `RoleGuard` (this is the unauthenticated entry).

**Files:**
- Create: `frontend/app/login/page.tsx`

- [ ] **Step 1: Write the screen (skeleton — flesh Tailwind via `/frontend-design`)**

```tsx
'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { useAuth } from '@/lib/auth';
import { ApiError } from '@/lib/api';
import { Card } from '@/components/ui/Card';
import { Field } from '@/components/ui/Field';
import { Input } from '@/components/ui/Input';
import { Button } from '@/components/ui/Button';

export default function LoginPage() {
  const { login } = useAuth();
  const router = useRouter();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      const res = await login({ email, password });
      router.replace(res.role === 'CLIENT' ? '/client' : '/builder');
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Login failed');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main>
      <Card>
        <h1>Sign in</h1>
        <form onSubmit={onSubmit}>
          <Field label="Email" htmlFor="email">
            <Input id="email" type="email" autoComplete="username"
              value={email} onChange={(e) => setEmail(e.target.value)} required />
          </Field>
          <Field label="Password" htmlFor="password">
            <Input id="password" type="password" autoComplete="current-password"
              value={password} onChange={(e) => setPassword(e.target.value)} required />
          </Field>
          {error && <p role="alert">{error}</p>}
          <Button type="submit" disabled={submitting}>
            {submitting ? 'Signing in…' : 'Sign in'}
          </Button>
        </form>
      </Card>
    </main>
  );
}
```

- [ ] **Step 2: Type-check**

Run: `npx tsc --noEmit`
Expected: no errors. (The login flow test lives in Task 9.)

- [ ] **Step 3: Commit**

```bash
git add frontend/app/login/page.tsx
git commit -m "feat: add login screen with role-based redirect"
```

---

## Task 4: Builder agent list (`/builder`)

**Route file:** `frontend/app/builder/page.tsx`
**API calls:** `GET /api/agents` → `AgentDTO[]`; per-agent `POST /api/agents/{id}/activate` → `AgentDTO` (only when `status === 'PENDING_VERIFICATION'`).
**State:** `agents: AgentDTO[] | null` (null = loading), `error: string | null`, `activatingId: string | null`.
**Behaviour:** load agents on mount; render a `Card` per agent with name, status `Badge` (colour via `statusColor`), categories (`capabilityCategories`), and price; an **Activate** `Button` shown only for `PENDING_VERIFICATION` agents that POSTs activate and replaces that agent in state with the returned DTO (immutable update); a header link to `/builder/agents/new`. Wrapped in `RoleGuard role="BUILDER"`.

**Files:**
- Create: `frontend/app/builder/page.tsx`

- [ ] **Step 1: Write the screen (skeleton)**

```tsx
'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { api, ApiError } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { RoleGuard } from '@/components/RoleGuard';
import type { AgentDTO } from '@/lib/types';
import { Card } from '@/components/ui/Card';
import { Badge } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';
import { statusColor } from '@/components/ui/statusColor';

function BuilderDashboard() {
  const { logout } = useAuth();
  const [agents, setAgents] = useState<AgentDTO[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [activatingId, setActivatingId] = useState<string | null>(null);

  useEffect(() => {
    api<AgentDTO[]>('/api/agents')
      .then(setAgents)
      .catch((e) => setError(e instanceof ApiError ? e.message : 'Failed to load agents'));
  }, []);

  async function activate(id: string) {
    setActivatingId(id);
    try {
      const updated = await api<AgentDTO>(`/api/agents/${id}/activate`, { method: 'POST' });
      setAgents((prev) => prev?.map((a) => (a.id === id ? updated : a)) ?? null);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Activation failed');
    } finally {
      setActivatingId(null);
    }
  }

  return (
    <main>
      <header>
        <h1>My agents</h1>
        <Link href="/builder/agents/new">Register agent</Link>
        <Button onClick={logout}>Log out</Button>
      </header>
      {error && <p role="alert">{error}</p>}
      {agents === null ? (
        <p>Loading…</p>
      ) : agents.length === 0 ? (
        <p>No agents yet. Register one to get started.</p>
      ) : (
        <ul>
          {agents.map((a) => (
            <li key={a.id}>
              <Card>
                <h2>{a.name}</h2>
                <Badge className={statusColor(a.status)}>{a.status}</Badge>
                <p>{a.currentVersion?.capabilityCategories?.join(', ')}</p>
                <p>{a.currentVersion?.price} credits</p>
                {a.status === 'PENDING_VERIFICATION' && (
                  <Button onClick={() => activate(a.id)} disabled={activatingId === a.id}>
                    {activatingId === a.id ? 'Activating…' : 'Activate'}
                  </Button>
                )}
              </Card>
            </li>
          ))}
        </ul>
      )}
    </main>
  );
}

export default function Page() {
  return (
    <RoleGuard role="BUILDER">
      <BuilderDashboard />
    </RoleGuard>
  );
}
```

> Note: `capabilityCategories` and `price` live on the nested `currentVersion` per the `AgentDTO` contract (`currentVersion{...}`). If B1's `AgentDTO` flattens them onto the root, read `a.capabilityCategories` / `a.price` instead — match B1's actual `lib/types.ts`.

- [ ] **Step 2: Type-check**

Run: `npx tsc --noEmit`
Expected: no errors. (The register+activate flow test lives in Task 10.)

- [ ] **Step 3: Commit**

```bash
git add frontend/app/builder/page.tsx
git commit -m "feat: add builder agent list with activate action"
```

---

## Task 5: Builder agent register (`/builder/agents/new`)

**Route file:** `frontend/app/builder/agents/new/page.tsx`
**API call:** `POST /api/agents` with `CreateAgentRequest` → `AgentDTO`.
**Form fields (match `CreateAgentRequest`):** `name` (text), `capabilityCategories` (CSV input split on commas → `string[]`), `webhookUrl` (type=url), `maxExecutionSeconds` (type=number), `price` (type=number), `outputSpec` (via `OutputSpecFields`).
**State:** `name`, `categoriesCsv`, `webhookUrl`, `maxExecutionSeconds` (number), `price` (number), `outputSpec: OutputSpec` (init `EMPTY_OUTPUT_SPEC`), `error`, `submitting`.
**Behaviour:** on submit → build the request (split CSV, trim, drop empties), POST, on success `router.push('/builder')`; on `ApiError` show message. Wrapped in `RoleGuard role="BUILDER"`.

**Files:**
- Create: `frontend/app/builder/agents/new/page.tsx`

- [ ] **Step 1: Write the screen (skeleton)**

```tsx
'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { api, ApiError } from '@/lib/api';
import { RoleGuard } from '@/components/RoleGuard';
import { OutputSpecFields, EMPTY_OUTPUT_SPEC } from '@/lib/outputSpecFields';
import type { AgentDTO, CreateAgentRequest, OutputSpec } from '@/lib/types';
import { Card } from '@/components/ui/Card';
import { Field } from '@/components/ui/Field';
import { Input } from '@/components/ui/Input';
import { Button } from '@/components/ui/Button';

function RegisterAgent() {
  const router = useRouter();
  const [name, setName] = useState('');
  const [categoriesCsv, setCategoriesCsv] = useState('');
  const [webhookUrl, setWebhookUrl] = useState('');
  const [maxExecutionSeconds, setMaxExecutionSeconds] = useState(60);
  const [price, setPrice] = useState(10);
  const [outputSpec, setOutputSpec] = useState<OutputSpec>(EMPTY_OUTPUT_SPEC);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    const body: CreateAgentRequest = {
      name,
      capabilityCategories: categoriesCsv.split(',').map((s) => s.trim()).filter(Boolean),
      webhookUrl,
      maxExecutionSeconds,
      price,
      outputSpec,
    };
    try {
      await api<AgentDTO>('/api/agents', { method: 'POST', body: JSON.stringify(body) });
      router.push('/builder');
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Registration failed');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main>
      <Card>
        <h1>Register agent</h1>
        <form onSubmit={onSubmit}>
          <Field label="Name" htmlFor="name">
            <Input id="name" value={name} onChange={(e) => setName(e.target.value)} required />
          </Field>
          <Field label="Capability categories (comma-separated)" htmlFor="categories">
            <Input id="categories" value={categoriesCsv}
              onChange={(e) => setCategoriesCsv(e.target.value)}
              placeholder="summarisation, translation" required />
          </Field>
          <Field label="Webhook URL" htmlFor="webhookUrl">
            <Input id="webhookUrl" type="url" value={webhookUrl}
              onChange={(e) => setWebhookUrl(e.target.value)} required />
          </Field>
          <Field label="Max execution seconds" htmlFor="maxExec">
            <Input id="maxExec" type="number" min={1} value={maxExecutionSeconds}
              onChange={(e) => setMaxExecutionSeconds(Number(e.target.value))} required />
          </Field>
          <Field label="Price (credits)" htmlFor="price">
            <Input id="price" type="number" min={0} value={price}
              onChange={(e) => setPrice(Number(e.target.value))} required />
          </Field>
          <OutputSpecFields value={outputSpec} onChange={setOutputSpec} />
          {error && <p role="alert">{error}</p>}
          <Button type="submit" disabled={submitting}>
            {submitting ? 'Registering…' : 'Register'}
          </Button>
        </form>
      </Card>
    </main>
  );
}

export default function Page() {
  return (
    <RoleGuard role="BUILDER">
      <RegisterAgent />
    </RoleGuard>
  );
}
```

- [ ] **Step 2: Type-check**

Run: `npx tsc --noEmit`
Expected: no errors.

- [ ] **Step 3: Commit**

```bash
git add frontend/app/builder/agents/new/page.tsx
git commit -m "feat: add builder agent registration form"
```

---

## Task 6: Client wallet + task list (`/client`)

**Route file:** `frontend/app/client/page.tsx`
**API calls:** `GET /api/wallet` → `WalletDTO { walletId, availableBalance, escrowBalance }`; `POST /api/wallet/topup` with `TopupRequest { amount }` → `WalletDTO`; `GET /api/tasks` → `TaskDTO[]`.
**State:** `wallet: WalletDTO | null`, `tasks: TaskDTO[] | null`, `topupAmount: number`, `error: string | null`, `toppingUp: boolean`.
**Behaviour:** on mount load wallet + tasks in parallel; show `availableBalance` and `escrowBalance`; a top-up `Input`(number)+`Button` that POSTs and replaces `wallet` with the returned DTO; a task list, each row showing title + status `Badge`(`statusColor`) linking to `/client/tasks/{id}`; a "Submit task" link to `/client/tasks/new`. Wrapped in `RoleGuard role="CLIENT"`.

**Files:**
- Create: `frontend/app/client/page.tsx`

- [ ] **Step 1: Write the screen (skeleton)**

```tsx
'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { api, ApiError } from '@/lib/api';
import { useAuth } from '@/lib/auth';
import { RoleGuard } from '@/components/RoleGuard';
import type { WalletDTO, TaskDTO, TopupRequest } from '@/lib/types';
import { Card } from '@/components/ui/Card';
import { Badge } from '@/components/ui/Badge';
import { Input } from '@/components/ui/Input';
import { Button } from '@/components/ui/Button';
import { statusColor } from '@/components/ui/statusColor';

function ClientDashboard() {
  const { logout } = useAuth();
  const [wallet, setWallet] = useState<WalletDTO | null>(null);
  const [tasks, setTasks] = useState<TaskDTO[] | null>(null);
  const [topupAmount, setTopupAmount] = useState(50);
  const [error, setError] = useState<string | null>(null);
  const [toppingUp, setToppingUp] = useState(false);

  useEffect(() => {
    api<WalletDTO>('/api/wallet').then(setWallet).catch(showError);
    api<TaskDTO[]>('/api/tasks').then(setTasks).catch(showError);
    function showError(e: unknown) {
      setError(e instanceof ApiError ? e.message : 'Failed to load');
    }
  }, []);

  async function topup(e: React.FormEvent) {
    e.preventDefault();
    setToppingUp(true);
    try {
      const body: TopupRequest = { amount: topupAmount };
      const updated = await api<WalletDTO>('/api/wallet/topup', {
        method: 'POST',
        body: JSON.stringify(body),
      });
      setWallet(updated);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Top-up failed');
    } finally {
      setToppingUp(false);
    }
  }

  return (
    <main>
      <header>
        <h1>My wallet</h1>
        <Button onClick={logout}>Log out</Button>
      </header>
      {error && <p role="alert">{error}</p>}
      <Card>
        {wallet === null ? (
          <p>Loading wallet…</p>
        ) : (
          <>
            <p>Available: <strong>{wallet.availableBalance}</strong></p>
            <p>In escrow: <strong>{wallet.escrowBalance}</strong></p>
            <form onSubmit={topup}>
              <Input type="number" min={1} value={topupAmount}
                aria-label="Top-up amount"
                onChange={(e) => setTopupAmount(Number(e.target.value))} />
              <Button type="submit" disabled={toppingUp}>
                {toppingUp ? 'Topping up…' : 'Top up'}
              </Button>
            </form>
          </>
        )}
      </Card>

      <section>
        <header>
          <h2>My tasks</h2>
          <Link href="/client/tasks/new">Submit task</Link>
        </header>
        {tasks === null ? (
          <p>Loading tasks…</p>
        ) : tasks.length === 0 ? (
          <p>No tasks yet.</p>
        ) : (
          <ul>
            {tasks.map((t) => (
              <li key={t.id}>
                <Link href={`/client/tasks/${t.id}`}>
                  <span>{t.title}</span>
                  <Badge className={statusColor(t.status)}>{t.status}</Badge>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </section>
    </main>
  );
}

export default function Page() {
  return (
    <RoleGuard role="CLIENT">
      <ClientDashboard />
    </RoleGuard>
  );
}
```

- [ ] **Step 2: Type-check**

Run: `npx tsc --noEmit`
Expected: no errors.

- [ ] **Step 3: Commit**

```bash
git add frontend/app/client/page.tsx
git commit -m "feat: add client wallet, top-up, and task list"
```

---

## Task 7: Client task submit (`/client/tasks/new`)

**Route file:** `frontend/app/client/tasks/new/page.tsx`
**API call:** `POST /api/tasks` with `CreateTaskRequest` → `TaskDTO { id, ... }`.
**Form fields (match `CreateTaskRequest`):** `title` (text), `description` (textarea), `category` (text), `budget` (number), `outputSpec` (via `OutputSpecFields`).
**State:** `title`, `description`, `category`, `budget` (number), `outputSpec: OutputSpec`, `error`, `submitting`.
**Behaviour:** on submit → POST; on success `router.push('/client/tasks/${created.id}')`; on `ApiError` show message. Wrapped in `RoleGuard role="CLIENT"`.

**Files:**
- Create: `frontend/app/client/tasks/new/page.tsx`

- [ ] **Step 1: Write the screen (skeleton)**

```tsx
'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { api, ApiError } from '@/lib/api';
import { RoleGuard } from '@/components/RoleGuard';
import { OutputSpecFields, EMPTY_OUTPUT_SPEC } from '@/lib/outputSpecFields';
import type { TaskDTO, CreateTaskRequest, OutputSpec } from '@/lib/types';
import { Card } from '@/components/ui/Card';
import { Field } from '@/components/ui/Field';
import { Input } from '@/components/ui/Input';
import { Button } from '@/components/ui/Button';

function SubmitTask() {
  const router = useRouter();
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [category, setCategory] = useState('');
  const [budget, setBudget] = useState(30);
  const [outputSpec, setOutputSpec] = useState<OutputSpec>(EMPTY_OUTPUT_SPEC);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    const body: CreateTaskRequest = { title, description, category, budget, outputSpec };
    try {
      const created = await api<TaskDTO>('/api/tasks', {
        method: 'POST',
        body: JSON.stringify(body),
      });
      router.push(`/client/tasks/${created.id}`);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Submit failed');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main>
      <Card>
        <h1>Submit task</h1>
        <form onSubmit={onSubmit}>
          <Field label="Title" htmlFor="title">
            <Input id="title" value={title} onChange={(e) => setTitle(e.target.value)} required />
          </Field>
          <Field label="Description" htmlFor="description">
            <textarea id="description" value={description}
              onChange={(e) => setDescription(e.target.value)} required />
          </Field>
          <Field label="Category" htmlFor="category">
            <Input id="category" value={category}
              onChange={(e) => setCategory(e.target.value)}
              placeholder="must match an active agent's category" required />
          </Field>
          <Field label="Budget (credits)" htmlFor="budget">
            <Input id="budget" type="number" min={0} value={budget}
              onChange={(e) => setBudget(Number(e.target.value))} required />
          </Field>
          <OutputSpecFields value={outputSpec} onChange={setOutputSpec} />
          {error && <p role="alert">{error}</p>}
          <Button type="submit" disabled={submitting}>
            {submitting ? 'Submitting…' : 'Submit'}
          </Button>
        </form>
      </Card>
    </main>
  );
}

export default function Page() {
  return (
    <RoleGuard role="CLIENT">
      <SubmitTask />
    </RoleGuard>
  );
}
```

- [ ] **Step 2: Type-check**

Run: `npx tsc --noEmit`
Expected: no errors. (The submit+redirect flow test lives in Task 11.)

- [ ] **Step 3: Commit**

```bash
git add frontend/app/client/tasks/new/page.tsx
git commit -m "feat: add client task submission form"
```

---

## Task 8: Client task detail with live status + result (`/client/tasks/[id]`) — FULL code

**Route file:** `frontend/app/client/tasks/[id]/page.tsx`
**API calls:**
- `GET /api/tasks/{id}` → `TaskDTO` — polled every ~2s until a terminal/holding status.
- `GET /api/tasks/{id}/result` → `TaskResultDTO` — fetched once status is `RESULT_RECEIVED`; its 404 (`ApiError`, `status === 404`) means "result not written yet" → keep polling, do not treat as an error.

**State:** `task: TaskDTO | null`, `result: TaskResultDTO | null`, `error: string | null`.
**Behaviour:**
- A `useEffect` keyed on `id` opens an interval (2000ms) that fetches the task. When `task.status === 'RESULT_RECEIVED'` and `result` is not yet loaded, it fetches the result; a 404 there is swallowed (keep polling). When the status is **terminal** (`RESOLVED | TIMED_OUT | SPEC_VIOLATION | FAILED | CANCELLED`) or the result has loaded, the interval is cleared so polling stops.
- The status badge colour comes from `statusColor(task.status)` across the whole lifecycle.
- `AWAITING_CAPACITY` renders a graceful "no agent available yet" panel (still polling — capacity can appear).
- The result renders `agentStatus`, the pretty-printed `resultPayloadJson` (parsed then `JSON.stringify(parsed, null, 2)`; on parse failure show the raw string), and `resultUrl` as a link when present.

This page is the heart of the demo. Full implementation:

```tsx
'use client';

import { useEffect, useRef, useState } from 'react';
import { useParams } from 'next/navigation';
import { api, ApiError } from '@/lib/api';
import { RoleGuard } from '@/components/RoleGuard';
import type { TaskDTO, TaskResultDTO, TaskStatus } from '@/lib/types';
import { Card } from '@/components/ui/Card';
import { Badge } from '@/components/ui/Badge';
import { statusColor } from '@/components/ui/statusColor';

const POLL_MS = 2000;

/** Statuses after which there is nothing left to poll for. */
const TERMINAL: ReadonlySet<TaskStatus> = new Set<TaskStatus>([
  'RESOLVED',
  'TIMED_OUT',
  'SPEC_VIOLATION',
  'FAILED',
  'CANCELLED',
]);

/** Pretty-print a JSON string; fall back to the raw text if it does not parse. */
function prettyJson(raw: string): string {
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}

function TaskDetail() {
  const params = useParams<{ id: string }>();
  const id = params.id;
  const [task, setTask] = useState<TaskDTO | null>(null);
  const [result, setResult] = useState<TaskResultDTO | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Keep the latest result in a ref so the interval closure can read it without re-subscribing.
  const resultRef = useRef<TaskResultDTO | null>(null);
  resultRef.current = result;

  useEffect(() => {
    if (!id) return;
    let cancelled = false;
    let timer: ReturnType<typeof setInterval> | null = null;

    const stop = () => {
      if (timer) {
        clearInterval(timer);
        timer = null;
      }
    };

    async function tick() {
      try {
        const t = await api<TaskDTO>(`/api/tasks/${id}`);
        if (cancelled) return;
        setTask(t);

        if (t.status === 'RESULT_RECEIVED' && !resultRef.current) {
          try {
            const r = await api<TaskResultDTO>(`/api/tasks/${id}/result`);
            if (cancelled) return;
            setResult(r);
            stop(); // result is in — nothing more to poll
          } catch (e) {
            // 404 = result row not written yet; keep polling. Anything else is a real error.
            if (!(e instanceof ApiError && e.status === 404)) {
              setError(e instanceof ApiError ? e.message : 'Failed to load result');
              stop();
            }
          }
        } else if (TERMINAL.has(t.status)) {
          stop();
        }
      } catch (e) {
        if (cancelled) return;
        setError(e instanceof ApiError ? e.message : 'Failed to load task');
        stop();
      }
    }

    tick(); // immediate first fetch, then interval
    timer = setInterval(tick, POLL_MS);
    return () => {
      cancelled = true;
      stop();
    };
  }, [id]);

  if (error) {
    return (
      <main>
        <Card><p role="alert">{error}</p></Card>
      </main>
    );
  }

  if (!task) {
    return (
      <main>
        <Card><p>Loading task…</p></Card>
      </main>
    );
  }

  return (
    <main>
      <Card>
        <header>
          <h1>{task.title}</h1>
          <Badge className={statusColor(task.status)}>{task.status}</Badge>
        </header>
        <p>Budget: {task.budget} credits</p>
        <p>{task.description}</p>

        {task.status === 'AWAITING_CAPACITY' && (
          <section aria-live="polite">
            <h2>Waiting for an available agent</h2>
            <p>
              No agent currently has capacity for this category. We&apos;ll keep checking and start
              your task as soon as one is free.
            </p>
          </section>
        )}

        {task.status !== 'RESULT_RECEIVED' && !result && !TERMINAL.has(task.status) &&
          task.status !== 'AWAITING_CAPACITY' && (
            <p aria-live="polite">Working… (status updates automatically)</p>
          )}

        {result && (
          <section>
            <h2>Result</h2>
            <p>Agent status: <strong>{result.agentStatus}</strong></p>
            <pre>{prettyJson(result.resultPayloadJson)}</pre>
            {result.resultUrl && (
              <p>
                <a href={result.resultUrl} target="_blank" rel="noreferrer">
                  Open deliverable
                </a>
              </p>
            )}
          </section>
        )}
      </Card>
    </main>
  );
}

export default function Page() {
  return (
    <RoleGuard role="CLIENT">
      <TaskDetail />
    </RoleGuard>
  );
}
```

- [ ] **Step 1: Write the full screen** (code above, verbatim).

- [ ] **Step 2: Type-check**

Run: `npx tsc --noEmit`
Expected: no errors. (The status→result rendering test lives in Task 12.)

- [ ] **Step 3: Commit**

```bash
git add frontend/app/client/tasks/[id]/page.tsx
git commit -m "feat: add polled task detail with live status and result rendering"
```

---

## Task 9: Test scaffolding (MSW) + login flow test

**Files:**
- Create: `frontend/test/msw/handlers.ts`
- Create: `frontend/test/login.test.tsx`

Establish the MSW server once (reused by every flow test), then test the login screen against it. Assumes B1 left a Vitest config with `environment: 'jsdom'` and a setup file; if MSW lifecycle is not yet wired, add the three lifecycle hooks in this test file (and reuse in later tests) — they are idempotent across files.

> **next/navigation in tests:** `useRouter`/`useParams` need mocking under jsdom. Each test file mocks `next/navigation` with `vi.mock`, exposing a shared `replace`/`push` spy and a settable `useParams` return. The pattern is shown in full here and repeated in Tasks 10–12.

- [ ] **Step 1: Write the MSW handlers**

```ts
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';

/** WebResult envelope helper — every backend response is wrapped like this. */
function ok<T>(data: T) {
  return HttpResponse.json({ success: true, code: 'OK', message: null, data });
}
function fail(code: string, message: string, status = 400) {
  return HttpResponse.json({ success: false, code, message, data: null }, { status });
}

export const handlers = [
  http.post('/api/auth/login', async ({ request }) => {
    const body = (await request.json()) as { email: string; password: string };
    if (body.password !== 'pw') return fail('UNAUTHORIZED', 'Bad credentials', 401);
    const role = body.email.startsWith('builder') ? 'BUILDER' : 'CLIENT';
    return ok({ token: 'test-jwt', userId: 'u-1', role });
  }),
];

export const server = setupServer(...handlers);
export { ok, fail };
```

- [ ] **Step 2: Write the login flow test**

```tsx
import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from './msw/handlers';
import { AuthProvider } from '@/lib/auth';
import LoginPage from '@/app/login/page';

const replace = vi.fn();
vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace, push: vi.fn() }),
  useParams: () => ({}),
}));

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  localStorage.clear();
  replace.mockClear();
});
afterAll(() => server.close());

function renderLogin() {
  return render(
    <AuthProvider>
      <LoginPage />
    </AuthProvider>
  );
}

describe('login screen', () => {
  it('logs a CLIENT in and redirects to /client', async () => {
    renderLogin();
    await userEvent.type(screen.getByLabelText(/email/i), 'client@test.local');
    await userEvent.type(screen.getByLabelText(/password/i), 'pw');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));
    await vi.waitFor(() => expect(replace).toHaveBeenCalledWith('/client'));
    expect(localStorage.getItem('token') ?? localStorage.getItem('hireai.token')).toBeTruthy();
  });

  it('redirects a BUILDER to /builder', async () => {
    renderLogin();
    await userEvent.type(screen.getByLabelText(/email/i), 'builder@test.local');
    await userEvent.type(screen.getByLabelText(/password/i), 'pw');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));
    await vi.waitFor(() => expect(replace).toHaveBeenCalledWith('/builder'));
  });

  it('shows an error on bad credentials', async () => {
    renderLogin();
    await userEvent.type(screen.getByLabelText(/email/i), 'client@test.local');
    await userEvent.type(screen.getByLabelText(/password/i), 'wrong');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/bad credentials/i);
  });
});
```

> The `localStorage` key assertion accepts either `token` or `hireai.token` — use whichever key B1's `AuthProvider` actually writes and delete the other branch.

- [ ] **Step 3: Run the login test**

Run: `npx vitest run test/login.test.tsx`
Expected: PASS (3 tests).

- [ ] **Step 4: Commit**

```bash
git add frontend/test/msw/handlers.ts frontend/test/login.test.tsx
git commit -m "test: add MSW handlers and login flow test"
```

---

## Task 10: Builder register + activate flow test

**Files:**
- Modify: `frontend/test/msw/handlers.ts` (add agent handlers)
- Create: `frontend/test/builder.test.tsx`

- [ ] **Step 1: Add agent handlers to `frontend/test/msw/handlers.ts`**

Append these to the `handlers` array (keep `login` in place):

```ts
  http.get('/api/agents', () =>
    ok([
      {
        id: 'a-1',
        ownerId: 'u-1',
        name: 'Summariser',
        status: 'PENDING_VERIFICATION',
        currentVersionId: 'v-1',
        reputationScore: 0,
        currentVersion: {
          capabilityCategories: ['summarisation'],
          price: 10,
          webhookUrl: 'http://localhost:9000/run',
          maxExecutionSeconds: 60,
          outputSpec: { format: 'JSON', schema: '{}', acceptanceCriteria: 'valid JSON' },
        },
        createdAt: '2026-06-06T10:00:00Z',
      },
    ])
  ),
  http.post('/api/agents/:id/activate', ({ params }) =>
    ok({
      id: params.id,
      ownerId: 'u-1',
      name: 'Summariser',
      status: 'ACTIVE',
      currentVersionId: 'v-1',
      reputationScore: 0,
      currentVersion: {
        capabilityCategories: ['summarisation'],
        price: 10,
        webhookUrl: 'http://localhost:9000/run',
        maxExecutionSeconds: 60,
        outputSpec: { format: 'JSON', schema: '{}', acceptanceCriteria: 'valid JSON' },
      },
      createdAt: '2026-06-06T10:00:00Z',
    })
  ),
```

> Match the nested-vs-flat shape to B1's real `AgentDTO`. If B1 flattens `price`/`capabilityCategories` onto the root, move those two fields out of `currentVersion` here and in the screen.

- [ ] **Step 2: Write the builder flow test**

```tsx
import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from './msw/handlers';
import { AuthProvider } from '@/lib/auth';
import BuilderPage from '@/app/builder/page';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  useParams: () => ({}),
}));

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  localStorage.clear();
});
afterAll(() => server.close());

function renderBuilder() {
  // Seed an authed BUILDER so RoleGuard renders the body.
  localStorage.setItem('hireai.auth', JSON.stringify({ token: 't', userId: 'u-1', role: 'BUILDER' }));
  return render(
    <AuthProvider>
      <BuilderPage />
    </AuthProvider>
  );
}

describe('builder agent list', () => {
  it('lists an agent and activates it', async () => {
    renderBuilder();
    expect(await screen.findByText('Summariser')).toBeInTheDocument();
    expect(screen.getByText('PENDING_VERIFICATION')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: /activate/i }));

    expect(await screen.findByText('ACTIVE')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /activate/i })).not.toBeInTheDocument();
  });
});
```

> The `renderBuilder` seed writes whatever key + shape B1's `AuthProvider` hydrates from on mount. If B1 reads separate keys (`token`/`role`), set those instead. The goal: `useAuth()` returns `{token:'t', role:'BUILDER'}` so `RoleGuard` passes.

- [ ] **Step 3: Run the builder test**

Run: `npx vitest run test/builder.test.tsx`
Expected: PASS (1 test). The agent shows `PENDING_VERIFICATION`, clicking Activate flips it to `ACTIVE`, and the button disappears.

- [ ] **Step 4: Commit**

```bash
git add frontend/test/msw/handlers.ts frontend/test/builder.test.tsx
git commit -m "test: add builder register/activate flow test"
```

---

## Task 11: Task submit flow test

**Files:**
- Modify: `frontend/test/msw/handlers.ts` (add task create handler)
- Create: `frontend/test/taskSubmit.test.tsx`

- [ ] **Step 1: Add the task-create handler to `frontend/test/msw/handlers.ts`**

Append to the `handlers` array:

```ts
  http.post('/api/tasks', async ({ request }) => {
    const body = (await request.json()) as { title: string; budget: number };
    return ok({
      id: 't-99',
      clientId: 'u-1',
      title: body.title,
      description: 'desc',
      budget: body.budget,
      status: 'SUBMITTED',
      outputSpec: { format: 'JSON', schema: '{}', acceptanceCriteria: 'valid JSON' },
      createdAt: '2026-06-06T10:00:00Z',
    });
  }),
```

- [ ] **Step 2: Write the submit flow test**

```tsx
import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { server } from './msw/handlers';
import { AuthProvider } from '@/lib/auth';
import SubmitTaskPage from '@/app/client/tasks/new/page';

const push = vi.fn();
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push, replace: vi.fn() }),
  useParams: () => ({}),
}));

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  localStorage.clear();
  push.mockClear();
});
afterAll(() => server.close());

function renderSubmit() {
  localStorage.setItem('hireai.auth', JSON.stringify({ token: 't', userId: 'u-1', role: 'CLIENT' }));
  return render(
    <AuthProvider>
      <SubmitTaskPage />
    </AuthProvider>
  );
}

describe('task submit', () => {
  it('submits a task and redirects to its detail page', async () => {
    renderSubmit();
    await userEvent.type(screen.getByLabelText(/title/i), 'Summarise Q2 report');
    await userEvent.type(screen.getByLabelText(/description/i), 'Summarise it');
    await userEvent.type(screen.getByLabelText(/category/i), 'summarisation');
    // budget + outputSpec defaults are valid; submit.
    await userEvent.click(screen.getByRole('button', { name: /submit/i }));
    await vi.waitFor(() => expect(push).toHaveBeenCalledWith('/client/tasks/t-99'));
  });
});
```

- [ ] **Step 3: Run the submit test**

Run: `npx vitest run test/taskSubmit.test.tsx`
Expected: PASS (1 test) — POST returns `{id:'t-99'}` and the router pushes `/client/tasks/t-99`.

- [ ] **Step 4: Commit**

```bash
git add frontend/test/msw/handlers.ts frontend/test/taskSubmit.test.tsx
git commit -m "test: add task submit + redirect flow test"
```

---

## Task 12: Task-detail status→result flow test

**Files:**
- Modify: `frontend/test/msw/handlers.ts` (add task-by-id + result handlers, with a status that advances)
- Create: `frontend/test/taskDetail.test.tsx`

This is the highest-value test: it proves the page polls, swaps the badge through the lifecycle, treats the result-endpoint 404 as "pending", and renders the result once it lands. Use fake timers to drive the 2s interval deterministically.

- [ ] **Step 1: Add advancing task + result handlers to `frontend/test/msw/handlers.ts`**

Append to the `handlers` array. A module-scoped counter advances the status across polls; the result 404s until the task reports `RESULT_RECEIVED`:

```ts
  // Drives a deterministic lifecycle: EXECUTING (poll 1) -> RESULT_RECEIVED (poll 2+).
  http.get('/api/tasks/:id', ({ params }) => {
    taskDetailPolls += 1;
    const status = taskDetailPolls >= 2 ? 'RESULT_RECEIVED' : 'EXECUTING';
    return ok({
      id: params.id,
      clientId: 'u-1',
      title: 'Summarise Q2 report',
      description: 'Summarise it',
      budget: 30,
      status,
      outputSpec: { format: 'JSON', schema: '{}', acceptanceCriteria: 'valid JSON' },
      createdAt: '2026-06-06T10:00:00Z',
    });
  }),
  http.get('/api/tasks/:id/result', ({ params }) => {
    if (taskDetailPolls < 2) return fail('NOT_FOUND', 'No result yet', 404);
    return ok({
      taskId: params.id,
      agentStatus: 'COMPLETED',
      resultPayloadJson: '{"summary":"all good"}',
      resultUrl: 'https://example.com/out.json',
      receivedAt: '2026-06-06T10:05:00Z',
    });
  }),
```

Add this module-scoped counter + reset near the top of `handlers.ts` (after the imports):

```ts
export let taskDetailPolls = 0;
export function resetTaskDetailPolls() {
  taskDetailPolls = 0;
}
```

- [ ] **Step 2: Write the task-detail flow test**

```tsx
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { server } from './msw/handlers';
import { resetTaskDetailPolls } from './msw/handlers';
import { AuthProvider } from '@/lib/auth';
import TaskDetailPage from '@/app/client/tasks/[id]/page';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  useParams: () => ({ id: 't-99' }),
}));

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
beforeEach(() => {
  resetTaskDetailPolls();
  localStorage.setItem('hireai.auth', JSON.stringify({ token: 't', userId: 'u-1', role: 'CLIENT' }));
});
afterEach(() => {
  server.resetHandlers();
  localStorage.clear();
});
afterAll(() => server.close());

describe('task detail polling', () => {
  it('shows EXECUTING then renders the result once RESULT_RECEIVED', async () => {
    render(
      <AuthProvider>
        <TaskDetailPage />
      </AuthProvider>
    );

    // First poll: EXECUTING badge.
    expect(await screen.findByText('EXECUTING')).toBeInTheDocument();

    // Subsequent polls (2s interval) advance to RESULT_RECEIVED and load the result.
    expect(await screen.findByText('RESULT_RECEIVED', {}, { timeout: 5000 })).toBeInTheDocument();
    expect(await screen.findByText(/agent status/i)).toHaveTextContent(/COMPLETED/);
    // Pretty-printed payload is present.
    expect(screen.getByText(/"summary": "all good"/)).toBeInTheDocument();
    // Result URL link rendered.
    expect(screen.getByRole('link', { name: /open deliverable/i }))
      .toHaveAttribute('href', 'https://example.com/out.json');
  }, 10000);
});
```

> Uses real timers with generous `findBy` timeouts (the interval is 2s) to stay robust. If the suite is slow, switch to `vi.useFakeTimers()` + `await vi.advanceTimersByTimeAsync(2000)` between assertions — but real timers keep the test simple and the `POLL_MS=2000` interval fires twice within the 10s budget.

- [ ] **Step 3: Run the task-detail test**

Run: `npx vitest run test/taskDetail.test.tsx`
Expected: PASS (1 test) — badge shows `EXECUTING`, then `RESULT_RECEIVED`; agent status `COMPLETED`, pretty-printed payload, and the deliverable link all render.

- [ ] **Step 4: Commit**

```bash
git add frontend/test/msw/handlers.ts frontend/test/taskDetail.test.tsx
git commit -m "test: add task-detail status poll to result rendering test"
```

---

## Task 13: Full frontend test suite green (regression gate)

No new code. Confirm every flow test passes together and types are clean.

- [ ] **Step 1: Run the whole suite**

Run: `npx vitest run`
Expected: PASS — login (3), builder (1), task submit (1), task detail (1); 6 tests across 4 files.

- [ ] **Step 2: Type-check the whole app**

Run: `npx tsc --noEmit`
Expected: no errors.

- [ ] **Step 3: Commit** (only if anything was fixed; otherwise skip — verification only)

```bash
git add -A
git commit -m "chore: green frontend screen suite"
```

---

## Task 14 (optional): Playwright happy-path smoke

**Files:**
- Create: `frontend/e2e/smoke.spec.ts`

Optional, time-permitting, and requires a running stack (frontend dev server + backend + seeded users + RabbitMQ + demo-agent stub per the demo runbook). Skipped by default in CI without that stack.

- [ ] **Step 1: Write the smoke spec**

```ts
import { test, expect } from '@playwright/test';

// Requires the full stack running (see demo runbook). Run: npx playwright test e2e/smoke.spec.ts
test('client logs in, submits a task, sees it progress', async ({ page }) => {
  await page.goto('/login');
  await page.getByLabel(/email/i).fill('client@hireai.local');
  await page.getByLabel(/password/i).fill('password');
  await page.getByRole('button', { name: /sign in/i }).click();

  await expect(page).toHaveURL(/\/client$/);
  await page.getByRole('link', { name: /submit task/i }).click();

  await page.getByLabel(/title/i).fill('Smoke summary');
  await page.getByLabel(/description/i).fill('Summarise this');
  await page.getByLabel(/category/i).fill('summarisation');
  await page.getByRole('button', { name: /submit/i }).click();

  // Lands on the task detail page; a status badge is visible.
  await expect(page).toHaveURL(/\/client\/tasks\//);
  await expect(page.getByText(/SUBMITTED|QUEUED|EXECUTING|RESULT_RECEIVED/)).toBeVisible();
});
```

- [ ] **Step 2: Run (only with the stack up)**

Run: `npx playwright test e2e/smoke.spec.ts`
Expected: PASS against a live stack; otherwise leave unrun (documented as optional).

- [ ] **Step 3: Commit**

```bash
git add frontend/e2e/smoke.spec.ts
git commit -m "test: add optional Playwright happy-path smoke"
```

---

## Definition of done

- All six screens exist at their exact routes and call exactly the spec's endpoints with request bodies matching the B1 request DTOs.
- `/login` redirects by role; `/builder` lists agents and activates `PENDING_VERIFICATION` ones; `/builder/agents/new` posts a full `CreateAgentRequest`; `/client` shows wallet (available + escrow) with top-up and the task list; `/client/tasks/new` posts a `CreateTaskRequest` and redirects to the new task; `/client/tasks/[id]` polls, colour-badges the lifecycle, handles `AWAITING_CAPACITY` gracefully, and renders `agentStatus` + pretty-printed `resultPayloadJson` + `resultUrl` once `RESULT_RECEIVED`.
- Authed screens are wrapped in `RoleGuard`; identity/role always come from `useAuth()` (Hard Invariant #5, client-side).
- The `OutputSpec` sub-form is shared (DRY) and the format `Select` lists exactly `TEXT | JSON | FILE`.
- Vitest + RTL + MSW flow tests pass for login, register+activate, task submit, and status→result; `npx vitest run` and `npx tsc --noEmit` are both green.
- Playwright smoke is present but optional.
- All commits conventional; no `Co-Authored-By` lines.

## Files added / changed

**Added**
- `frontend/lib/outputSpecFields.tsx`
- `frontend/components/RoleGuard.tsx`
- `frontend/app/login/page.tsx`
- `frontend/app/builder/page.tsx`
- `frontend/app/builder/agents/new/page.tsx`
- `frontend/app/client/page.tsx`
- `frontend/app/client/tasks/new/page.tsx`
- `frontend/app/client/tasks/[id]/page.tsx`
- `frontend/test/msw/handlers.ts`
- `frontend/test/login.test.tsx`
- `frontend/test/builder.test.tsx`
- `frontend/test/taskSubmit.test.tsx`
- `frontend/test/taskDetail.test.tsx`
- `frontend/e2e/smoke.spec.ts` (optional)

**Depends on (from B1, unchanged)**
- `frontend/lib/api.ts`, `frontend/lib/auth.tsx`, `frontend/lib/types.ts`
- `frontend/components/ui/{Button,Input,Select,Card,Badge,Field}.tsx`, `frontend/components/ui/statusColor.ts`
- `frontend/next.config.*` (the `/api/*` rewrite proxy), the Vitest config + jsdom setup
