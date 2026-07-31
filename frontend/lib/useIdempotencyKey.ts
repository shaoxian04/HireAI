"use client";

import { useCallback, useRef } from "react";
import type { DirectBookRequest } from "./types";

/**
 * Field delimiter, matching `SubmitFingerprint.SEP` on the backend: RECORD SEPARATOR, chosen
 * because it is not expected in payloads. A printable separator like `|` would collide —
 * `{title:"a|b", description:"c"}` and `{title:"a", description:"b|c"}` would share a signature,
 * so the key would not rotate even though the server's fingerprint had changed, and the retry
 * would be rejected as `409 IDEMPOTENCY_CONFLICT`.
 */
const SEP = "␞";

/**
 * Signature of a direct-booking payload, mirroring the fields the backend fingerprints in
 * `SubmitOrchestrationAppServiceImpl.fingerprintDirect` (title, description, agentId, budget).
 * Takes the request body itself so the signature cannot drift from what is actually sent.
 * `null` (nothing bookable yet) has no meaningful signature.
 */
export function directBookSignature(body: DirectBookRequest | null): string {
  if (!body) return "";
  return [body.title, body.description, body.budget, body.agentId].join(SEP);
}

/**
 * Supplies the `Idempotency-Key` header for a task submit.
 *
 * The backend honours the header on `POST /api/tasks` and `POST /api/tasks/direct`, checking
 * `UNIQUE (owner_id, idempotency_key)` inside the *same* transaction as the escrow freeze. Without
 * it, a submit that commits and then fails during after-commit routing returns a `500` to a client
 * whose budget is already frozen — and the obvious retry creates a second task with a second freeze.
 *
 * The key's lifetime is one submit *attempt*, not one session:
 * - generated lazily on first use and reused while the payload is unchanged, so a double-click or a
 *   retry after a post-commit `500` resolves to the original task;
 * - regenerated whenever `signature` changes, because the same key with a different request
 *   fingerprint is rejected as `409 IDEMPOTENCY_CONFLICT` — editing the form is a new intent;
 * - held in a ref, so unmounting discards it. That is deliberate: `idempotency_keys` rows never
 *   expire, so a key persisted across navigation would pin the client to a stale task forever (one
 *   stuck in `AWAITING_CAPACITY`, say, or already `CANCELLED`). Both submit pages navigate away on
 *   success, so a later deliberate resubmit correctly creates a new task.
 *
 * Returns a getter rather than the key itself so the ref is only read/written inside an event
 * handler, never during render. Key and signature live in one ref: they always change together,
 * so pairing them makes it impossible to update one without the other.
 */
export function useIdempotencyKey(signature: string): () => string {
  const issued = useRef<{ signature: string; key: string } | null>(null);

  return useCallback(() => {
    if (issued.current?.signature !== signature) {
      issued.current = { signature, key: newKey() }; // new payload → a genuinely different submit
    }
    return issued.current.key;
  }, [signature]);
}

/** `crypto.randomUUID` requires a secure context; fall back so plain-HTTP dev can still submit. */
function newKey(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  const rand = () => Math.random().toString(36).slice(2, 10);
  return `${Date.now().toString(36)}-${rand()}-${rand()}`;
}
