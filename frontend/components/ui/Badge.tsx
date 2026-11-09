import type { ReactNode } from "react";

/**
 * Tailwind colour classes per lifecycle status. Covers the full TaskStatus and AgentStatus
 * enums, the dispute lifecycle, and the webhook delivery status (see lib/types.ts); unknown
 * values fall back to neutral so the UI never crashes on a status the backend adds later.
 * Green = good/terminal-success, cobalt = executing, cyan = in-flight, amber = waiting/attention, red =
 * failure/terminal-bad, neutral/dim = inactive.
 */
const GREEN = "text-green border-green/30 bg-green/10";
const ACCENT = "text-accent border-accent/30 bg-accent/10";
const CYAN = "text-cyan border-cyan/35 bg-cyan/10";
const AMBER = "text-amber border-amber/35 bg-amber/10";
const RED = "text-red border-red/35 bg-red/10";
const VIOLET = "text-violet border-violet/35 bg-violet/10";
const NEUTRAL = "text-muted border-line-bright bg-surface-2";
const DIM = "text-dim border-line bg-surface-2";

const STATUS_CLASSES: Record<string, string> = {
  // task lifecycle
  SUBMITTED: NEUTRAL,
  QUEUED: CYAN,
  EXECUTING: ACCENT,
  RESULT_RECEIVED: VIOLET,
  PENDING_REVIEW: AMBER,
  RESOLVED: GREEN,
  AWAITING_AGENT: AMBER,
  TIMED_OUT: RED,
  SPEC_VIOLATION: RED,
  FAILED: RED,
  CANCELLED: DIM,
  // agent lifecycle
  PENDING_VERIFICATION: AMBER,
  ACTIVE: GREEN,
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
  // task resolution (a resolved task's badge is coloured by how it settled)
  ACCEPTED: GREEN,
  REJECTED: NEUTRAL,
  // webhook delivery log
  PENDING: AMBER,
  DELIVERED: GREEN,
  DEAD: RED,
};

/** Human-readable label per status. Unknown values fall back to a sentence-cased version. */
const STATUS_LABELS: Record<string, string> = {
  SUBMITTED: "Submitted",
  QUEUED: "Queued",
  EXECUTING: "Executing",
  RESULT_RECEIVED: "Validating",
  PENDING_REVIEW: "Needs review",
  RESOLVED: "Resolved",
  AWAITING_AGENT: "Awaiting agent",
  TIMED_OUT: "Timed out",
  SPEC_VIOLATION: "Failed validation",
  FAILED: "Failed",
  CANCELLED: "Cancelled",
  PENDING_VERIFICATION: "Not activated",
  ACTIVE: "Active",
  SUSPENDED: "Suspended",
  DEACTIVATED: "Deactivated",
  DISPUTED: "In dispute",
  PARTIALLY_ACCEPTED: "Partially accepted",
  OPEN: "Open",
  ARBITRATING: "Arbitrating",
  ESCALATED: "Escalated",
  RULED: "Ruling proposed",
  PENDING: "Pending",
  DELIVERED: "Delivered",
  DEAD: "Failed",
  ACCEPTED: "Accepted",
  REJECTED: "Rejected",
};

/** Readable label for a backend status enum (e.g. `PENDING_REVIEW` → "Needs review"). */
export function statusLabel(status: string): string {
  const known = STATUS_LABELS[status];
  if (known) return known;
  const words = status.toLowerCase().replace(/_/g, " ");
  return words.charAt(0).toUpperCase() + words.slice(1);
}

/** Tailwind classes for a lifecycle status badge. Falls back to neutral for unknown values. */
export function statusColor(status: string): string {
  return STATUS_CLASSES[status] ?? NEUTRAL;
}

/**
 * Status pill. Shows the readable label by default; the raw enum stays available as a
 * tooltip and `data-status` so it is still inspectable.
 */
export function Badge({ status, children }: { status: string; children?: ReactNode }) {
  return (
    <span
      title={status}
      data-status={status}
      className={`inline-flex items-center gap-1.5 whitespace-nowrap rounded-full border px-2 py-0.5 text-xs font-semibold ${statusColor(
        status,
      )}`}
    >
      <span className="size-1.5 rounded-full bg-current" aria-hidden />
      {children ?? statusLabel(status)}
    </span>
  );
}
