# API-key lockout renders as 401, not 403 (and slice tests said 403)

**Date:** 2026-07-17
**Severity:** low. Caught pre-merge by a live E2E; nothing shipped.
**Area:** Spring Security exception handling; `@WebMvcTest` slice vs full-application behaviour.
**Fix commit:** `d5ad67d`. **Open decision:** [issue #23](https://github.com/shaoxian04/HireAI/issues/23).

## Summary

`ProgrammaticSubmissionIntegrationTest` asserted that an API-key caller hitting `/api/keys` and `/api/wallet` gets **403**. The running application returns **401**, for those and for every authenticated-but-forbidden request. The test is Docker-gated and skips locally, so it would first have failed in CI. A live E2E against a real backend caught it. The security property held throughout: access was denied. Only the status code differed.

## Root cause

1. **The full app returns 401 for a role denial.** `SecurityConfig.securedFilterChain` sets an `authenticationEntryPoint` (`HttpStatusEntryPoint(UNAUTHORIZED)`) and no `accessDeniedHandler`. This is app-wide and pre-existing: a JWT `CLIENT` hitting `/api/admin/**` behaves the same way.
2. **`@WebMvcTest` slices render the same denial as 403.** `AdminControllerTest` asserts `isForbidden()` and passes, so the slice reinforced the wrong expectation.

**Likely mechanism (inferred from Spring Security 6 behaviour, not verified at runtime).** The default `AccessDeniedHandlerImpl` calls `response.sendError(403)`. The container then makes an ERROR dispatch to `/error`, which also passes through the security chain. The auth filters are `OncePerRequestFilter`s, which skip ERROR dispatches, so that second pass is anonymous. `/error` falls under `anyRequest()`, so the entry point answers 401. MockMvc performs no ERROR dispatch, which would explain why slices see the original 403.

## Why it wasn't caught earlier

Unit tests mock the auth service, slices render 403, and the one full-application test that would catch it skips without Docker. Only a live run exercised the real chain.

## Fix

The integration test now asserts **401** for the lockout (`apiKeyIsLockedOutOfKeyManagementAndWallet`), with a comment on the full-app-vs-slice difference, and `identity-and-authz.md` documents the behaviour.

## Lessons

1. **Trust a denied-status assertion only at the full-application level.** Verify against an integration test or live, or assert "access was denied" rather than a code.
2. **Docker-gated tests that never run locally are a blind spot.** Run them, or run a live E2E, before trusting them.
3. **Merely adding an `accessDeniedHandler` isn't enough for 403.** See below.

## If 403 semantics are wanted

This is a deliberate, app-wide change, tracked in issue #23.

- The handler must set the status directly, for example `(req, res, e) -> res.setStatus(403)`.
- A handler that calls `sendError`, as `AccessDeniedHandlerImpl` does, would by the mechanism above still end as 401. Permitting `/error` is the alternative.
- `HttpStatusEntryPoint` can't be used: it is an `AuthenticationEntryPoint`, not an `AccessDeniedHandler`. Spring Security 6.4.13 ships no status-only denied handler.
- Reconcile the full-app tests that assert 401, and note that `frontend/lib/api.ts` treats any 401 as an expired session and redirects to `/login`, so forbidden requests currently do too.
