import type { TaskDTO } from "./types";

/** Seconds as a reader would say them: "6.2s", "4m 23s", "1h 15m". */
export function formatDuration(seconds: number | null | undefined): string {
  if (seconds == null) return "—";
  if (seconds < 60) return `${seconds < 10 ? seconds.toFixed(1) : Math.round(seconds)}s`;
  if (seconds < 3600) {
    const m = Math.floor(seconds / 60);
    const s = Math.round(seconds % 60);
    return s ? `${m}m ${s}s` : `${m}m`;
  }
  const h = Math.floor(seconds / 3600);
  const m = Math.round((seconds % 3600) / 60);
  return m ? `${h}h ${m}m` : `${h}h`;
}

export type MoneyState = { label: string; tone: "held" | "paid" | "back" | "split" };

/**
 * Where a task's budget is right now, from the task alone. Credits freeze on submit, so until a
 * task settles they are held in escrow; a failure refunds them; a settlement pays, refunds or splits.
 */
export function moneyState(t: Pick<TaskDTO, "status" | "resolution">): MoneyState {
  switch (t.status) {
    case "RESOLVED":
      if (t.resolution === "ACCEPTED") return { label: "paid out", tone: "paid" };
      if (t.resolution === "PARTIALLY_ACCEPTED") return { label: "split", tone: "split" };
      return { label: "refunded", tone: "back" };
    case "TIMED_OUT":
    case "SPEC_VIOLATION":
    case "FAILED":
    case "CANCELLED":
      return { label: "refunded", tone: "back" };
    default:
      return { label: "in escrow", tone: "held" };
  }
}
