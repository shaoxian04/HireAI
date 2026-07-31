import { describe, it, expect } from "vitest";
import { renderHook } from "@testing-library/react";
import { directBookSignature, useIdempotencyKey } from "./useIdempotencyKey";

describe("directBookSignature", () => {
  it("separates payloads that differ only in where a printable character falls", () => {
    // A printable delimiter like "|" would make these two collide, so the key would not rotate
    // even though the server's fingerprint changed — surfacing as a spurious 409.
    const a = directBookSignature({ title: "a|b", description: "c", budget: 12, agentId: "ag-1" });
    const b = directBookSignature({ title: "a", description: "b|c", budget: 12, agentId: "ag-1" });
    expect(a).not.toBe(b);
  });

  it("is stable for an identical payload and changes with any field", () => {
    const base = { title: "t", description: "d", budget: 12, agentId: "ag-1" };
    expect(directBookSignature({ ...base })).toBe(directBookSignature({ ...base }));
    expect(directBookSignature({ ...base, budget: 40 })).not.toBe(directBookSignature(base));
    expect(directBookSignature({ ...base, agentId: "ag-2" })).not.toBe(directBookSignature(base));
  });

  it("has no signature until something is bookable", () => {
    expect(directBookSignature(null)).toBe("");
  });
});

describe("useIdempotencyKey", () => {
  it("hands back the same key on repeated calls within one attempt", () => {
    // The double-click / retry-after-500 case: the backend must see one key, so the
    // second submit resolves to the first task instead of freezing escrow twice.
    const { result } = renderHook(() => useIdempotencyKey("summary|report|12|a-1"));
    const first = result.current();
    expect(result.current()).toBe(first);
    expect(result.current()).toBe(first);
  });

  it("keeps the key stable across re-renders that do not change the payload", () => {
    const { result, rerender } = renderHook(({ sig }) => useIdempotencyKey(sig), {
      initialProps: { sig: "summary|report|12|a-1" },
    });
    const first = result.current();
    rerender({ sig: "summary|report|12|a-1" });
    expect(result.current()).toBe(first);
  });

  it("issues a fresh key once the payload changes", () => {
    // Editing the form after a failure is a new intent. Reusing the key here would be
    // the same key with a different request fingerprint → 409 IDEMPOTENCY_CONFLICT.
    const { result, rerender } = renderHook(({ sig }) => useIdempotencyKey(sig), {
      initialProps: { sig: "summary|report|12|a-1" },
    });
    const first = result.current();
    rerender({ sig: "summary|report|40|a-2" });
    expect(result.current()).not.toBe(first);
  });

  it("does not reuse a key across mounts, even for an identical payload", () => {
    // Load-bearing: idempotency_keys rows never expire. A key that survived navigation
    // would pin the client to a stale task (e.g. stuck AWAITING_CAPACITY, or CANCELLED)
    // and a deliberate resubmit could never create a new one.
    const first = renderHook(() => useIdempotencyKey("summary|report|12|a-1"));
    const firstKey = first.result.current();
    first.unmount();

    const second = renderHook(() => useIdempotencyKey("summary|report|12|a-1"));
    expect(second.result.current()).not.toBe(firstKey);
  });

  it("returns a non-empty key even without crypto.randomUUID (plain-HTTP dev)", () => {
    const original = globalThis.crypto;
    // Non-secure contexts do not expose randomUUID; submit must still work.
    Object.defineProperty(globalThis, "crypto", { value: {}, configurable: true });
    try {
      const { result } = renderHook(() => useIdempotencyKey("summary|report|12|a-1"));
      const key = result.current();
      expect(key).toBeTruthy();
      expect(result.current()).toBe(key);
    } finally {
      Object.defineProperty(globalThis, "crypto", { value: original, configurable: true });
    }
  });
});
