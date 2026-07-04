"use client";

import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import type { DisputeMineRowDTO } from "@/lib/types";

/** Count of the client's disputes awaiting their decision (RULED). Best-effort; 0 on error. */
export function useDisputeCount(enabled: boolean): number {
  const [count, setCount] = useState(0);
  useEffect(() => {
    if (!enabled) return;
    let cancelled = false;
    api<DisputeMineRowDTO[]>("/disputes/mine")
      .then((rows) => {
        if (!cancelled) setCount(rows.filter((r) => r.status === "RULED").length);
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [enabled]);
  return count;
}
