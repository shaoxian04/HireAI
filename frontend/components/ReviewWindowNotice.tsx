"use client";

import { useEffect, useState } from "react";

interface Props {
  /** When the Client's review window closes (TaskDTO.reviewDeadline); nothing renders without one. */
  deadline?: string | null;
  /** Fixes the current time (tests, or a parent that already ticks); otherwise the notice ticks on its own. */
  now?: Date;
  /**
   * What the window accepts, for the copy: "the result" (default) or, on a multi-Item task (#103),
   * "the Items still awaiting review", whose window counts business days only.
   */
  subject?: "result" | "items";
}

/** How often the countdown re-reads the clock. Minute precision, so a few ticks a minute is plenty. */
const TICK_MS = 15_000;

/** The current time, re-read every few seconds; a fixed `now` never ticks. */
export function useNow(now?: Date): Date {
  const [clock, setClock] = useState(() => now ?? new Date());
  useEffect(() => {
    if (now) return;
    const timer = setInterval(() => setClock(new Date()), TICK_MS);
    return () => clearInterval(timer);
  }, [now]);
  return now ?? clock;
}

/** Whether a review window closing at `deadline` has closed by `now`; never, without a deadline. */
export function reviewWindowClosed(deadline: string | null | undefined, now: Date): boolean {
  return !!deadline && new Date(deadline).getTime() <= now.getTime();
}

/** "22h 30m left", "12m left", "under a minute left". */
function timeLeft(ms: number): string {
  const minutes = Math.floor(ms / 60_000);
  if (minutes < 1) return "under a minute left";
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  if (h === 0) return `${m}m left`;
  return m ? `${h}h ${m}m left` : `${h}h left`;
}

/**
 * The review window on a result awaiting the Client (#94): how long is left before silence counts
 * as acceptance, and, once it has closed, that the platform is accepting on their behalf.
 */
export function ReviewWindowNotice({ deadline, now, subject = "result" }: Props) {
  const items = subject === "items";
  const current = useNow(now);
  if (!deadline) return null;

  if (reviewWindowClosed(deadline, current)) {
    return (
      <div role="status" className="flex items-start gap-3 rounded-lg border border-line bg-surface-2 px-4 py-3">
        <span aria-hidden className="mt-1.5 size-2 shrink-0 animate-pulse rounded-full bg-green" />
        <p className="text-sm text-muted">
          <span className="font-semibold text-fg">The review window has closed.</span>{" "}
          {items ? "The Items still awaiting review are being " : "The result is being "}
          accepted on your behalf and the builder paid. This page updates when it settles.
        </p>
      </div>
    );
  }

  const closesAt = new Date(deadline);
  const closesLabel = closesAt.toLocaleString(undefined, { weekday: "short", hour: "2-digit", minute: "2-digit" });
  return (
    <div
      role="timer"
      aria-label="Time left to review"
      className="flex flex-wrap items-baseline gap-x-4 gap-y-1 rounded-lg border border-amber/30 bg-amber/10 px-4 py-3"
    >
      <span className="tabular font-mono text-base font-bold text-amber">
        {timeLeft(closesAt.getTime() - current.getTime())}
      </span>
      <p className="min-w-0 flex-1 text-sm text-muted">
        to review, until <time dateTime={deadline}>{closesLabel}</time>. If you don&apos;t decide by then,{" "}
        {items
          ? "the Items still awaiting review are accepted automatically and the builder is paid for them. Weekends don't count toward this window."
          : "the result is accepted automatically and the builder is paid."}
      </p>
    </div>
  );
}
