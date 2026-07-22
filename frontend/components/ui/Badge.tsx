import type { ReactNode } from "react";

/**
 * Tailwind colour classes per lifecycle status. Covers the full TaskStatus and AgentStatus
 * enums, the dispute lifecycle, and the webhook delivery status (see lib/types.ts); unknown
 * values fall back to neutral so the UI never crashes on a status the backend adds later.
 * Lime = good/terminal-success + live, cyan = in-flight, amber = waiting/attention, red =
 * failure/terminal-bad, neutral/dim = inactive.
 */
const ACCENT = "text-accent border-accent/40 bg-accent/10";
const CYAN = "text-cyan border-cyan/35 bg-cyan/10";
const AMBER = "text-amber border-amber/35 bg-amber/10";
const RED = "text-red border-red/35 bg-red/10";
const VIOLET = "text-violet border-violet/35 bg-violet/10";
const NEUTRAL = "text-muted border-line bg-surface-2";
const DIM = "text-dim border-line bg-surface-2";

const STATUS_CLASSES: Record<string, string> = {
  // task lifecycle
  SUBMITTED: NEUTRAL,
  QUEUED: CYAN,
  EXECUTING: ACCENT,
  RESULT_RECEIVED: VIOLET,
  PENDING_REVIEW: AMBER,
  RESOLVED: ACCENT,
  AWAITING_CAPACITY: AMBER,
  TIMED_OUT: RED,
  SPEC_VIOLATION: RED,
  FAILED: RED,
  CANCELLED: DIM,
  // agent lifecycle
  PENDING_VERIFICATION: AMBER,
  ACTIVE: ACCENT,
  SUSPENDED: RED,
  DEACTIVATED: DIM,
  // dispute / arbitration
  DISPUTED: AMBER,
  PARTIALLY_ACCEPTED: VIOLET,
  // dispute lifecycle (admin surface)
  OPEN: AMBER,
  ARBITRATING: CYAN,
  ESCALATED: RED,
  RULED: VIOLET,
  // webhook delivery log
  PENDING: AMBER,
  DELIVERED: ACCENT,
  DEAD: RED,
};

/** Tailwind classes for a lifecycle status badge. Falls back to neutral for unknown values. */
export function statusColor(status: string): string {
  return STATUS_CLASSES[status] ?? NEUTRAL;
}

export function Badge({ status, children }: { status: string; children?: ReactNode }) {
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded border px-2 py-0.5 font-mono text-[0.68rem] font-medium uppercase tracking-wider ${statusColor(
        status,
      )}`}
    >
      <span className="size-1.5 rounded-full bg-current" aria-hidden />
      {children ?? status}
    </span>
  );
}
