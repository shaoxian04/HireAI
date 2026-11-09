"use client";

import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import type { AdminDisputeRowDTO, DisputeMineRowDTO } from "@/lib/types";

/**
 * The nav badge on "Disputes": for a client, their disputes awaiting their decision (RULED); for
 * an admin, the disputes that need a ruling (the "needs attention" view). Best-effort; 0 on error.
 */
export function useDisputeCount(surface: "CLIENT" | "ADMIN" | null): number {
  const [count, setCount] = useState(0);
  useEffect(() => {
    if (!surface) return;
    let cancelled = false;
    const load =
      surface === "ADMIN"
        ? api<AdminDisputeRowDTO[]>("/admin/disputes?filter=needs_attention").then((rows) => rows.length)
        : api<DisputeMineRowDTO[]>("/disputes/mine").then((rows) => rows.filter((r) => r.status === "RULED").length);
    load
      .then((n) => {
        if (!cancelled) setCount(n);
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [surface]);
  return count;
}
