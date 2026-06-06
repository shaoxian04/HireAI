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
