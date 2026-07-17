# API-key lockout renders as 401, not 403 (and slice tests said 403)

**Date:** 2026-07-17
**Severity:** low — caught pre-merge by a live E2E; nothing shipped, no production impact.
**Area:** Spring Security exception handling; `@WebMvcTest` slice vs full-application behavior.
**Branch:** `feat/programmatic-spine` (Phase 3, programmatic submission spine). Fix commit `d5ad67d`.

## Summary

The Phase-3 integration test `ProgrammaticSubmissionIntegrationTest` asserted that an API-key caller hitting `/api/keys` and `/api/wallet` gets **403 Forbidden**. The running application actually returns **401 Unauthorized** for those (and for *every* authenticated-but-forbidden request). The test would have failed the first time it ran — which is only in CI, because it is Docker-gated (Testcontainers) and skips locally. A manual live E2E against a real backend caught it before merge.

## What happened

While answering "did you actually test this end-to-end?", the backend was booted against Supabase (no Docker) and driven with real HTTP. 10 of 12 edge checks passed; the two failures were:

```
ApiKey -> GET /api/keys   expected 403, got 401
ApiKey -> GET /api/wallet expected 403, got 401
```

The security property under test — the key is **denied** access (submit-scoped `ROLE_API_CLIENT` cannot reach key-management or the wallet) — held perfectly. Only the status *code* differed from what the test asserted.

## Root cause

Two independent facts combined:

1. **The application returns 401 for any authenticated-but-forbidden request.** `SecurityConfig.securedFilterChain` configures an `authenticationEntryPoint` (`HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)`) but **no `accessDeniedHandler`**. In this app's full filter chain, an authenticated principal that lacks the required authority is rendered as **401**, not 403. This is **app-wide and pre-existing** — a JWT `CLIENT` hitting `/api/admin/**` returns 401 the same way. It was not introduced by Phase 3.

2. **`@WebMvcTest` slices render the same denial as 403.** `AdminControllerTest` (a slice) asserts `status().isForbidden()` for a `CLIENT` hitting an admin route — and passes. The MockMvc slice and the full application disagree on 401-vs-403 for authorization failures. The Phase-3 integration test author reasonably assumed 403 (the slice intuition and the spec's stated intent), so the assertion baked in the wrong number.

## Why it wasn't caught earlier

- **Unit tests mock** the auth service and never exercise the real chain.
- **`@WebMvcTest` slices render 403**, reinforcing the wrong expectation.
- **The one full-application test that would catch it (`ProgrammaticSubmissionIntegrationTest`, RANDOM_PORT + Testcontainers) is Docker-gated and skips locally** — it had never actually run. Its first run would be in CI, where it would have failed.
- A **live E2E** (backend on Supabase, RabbitMQ down — fine, since routing dispatch is a post-commit event) exercised the real chain and surfaced the gap immediately.

## Fix

Commit `d5ad67d`:
- `ProgrammaticSubmissionIntegrationTest` now asserts **401** for the `/api/keys` and `/api/wallet` lockout (renamed `apiKeyIsLockedOutOfKeyManagementAndWallet`), with a comment explaining the full-app-vs-slice distinction.
- `docs/details/identity-and-authz.md` corrected: an API-key caller on `/api/wallet` is **denied as 401** (not 403), noting the missing `accessDeniedHandler` and that slices render it as 403.

After the fix the live E2E is 12/12 green.

## Lessons

1. **A controller test's forbidden-status assertion is only trustworthy at the full-application level.** `@WebMvcTest` renders authorization failures as 403; this app's full chain renders them as **401**. When a test asserts a *denied* status, verify it against the full app (integration test or live), not just the slice — or assert on "access was denied" rather than a specific code.
2. **This app returns 401 for authenticated-but-forbidden because `SecurityConfig` has no `accessDeniedHandler`.** If genuine RESTful **403** semantics are wanted (they'd be more correct: the caller *is* authenticated), that's a deliberate, app-wide change — add an `accessDeniedHandler` and update the slice/full-app tests together. Until then, expect 401 for forbidden everywhere.
3. **Docker-gated integration tests that never run locally are a real blind spot.** A live E2E — boot the backend against Supabase (no Docker needed; RabbitMQ-down is acceptable because the submit edge commits before the post-commit routing dispatch) — verifies the real security chain and idempotency/escrow behavior that mocks and slices cannot.

## Follow-up (optional, not blocking this PR)

- Decide whether to add an `accessDeniedHandler` to `securedFilterChain` for true 403 semantics; if adopted, reconcile the `@WebMvcTest` `isForbidden()` assertions (they'd then match the full app).
