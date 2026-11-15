"use client";

import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import type { AdminDisputeRowDTO, DisputeMineRowDTO } from "@/lib/types";

/**
 * The nav badge on "Disputes": for a client or a builder, their disputes whose proposed ruling goes
 * against them and awaits their accept-or-appeal (`awaitingYou`); for an admin, the disputes that
 * need a ruling (the "needs attention" view). Best-effort; 0 on error.
 */
export function useDisputeCount(surface: "CLIENT" | "BUILDER" | "ADMIN" | null): number {
  const [count, setCount] = useState(0);
  useEffect(() => {
    if (!surface) return;
    let cancelled = false;
    const load =
      surface === "ADMIN"
        ? api<AdminDisputeRowDTO[]>("/admin/disputes?filter=needs_attention").then((rows) => rows.length)
        : api<DisputeMineRowDTO[]>(surface === "BUILDER" ? "/disputes/builder" : "/disputes/mine").then(
            (rows) => rows.filter((r) => r.awaitingYou).length,
          );
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
