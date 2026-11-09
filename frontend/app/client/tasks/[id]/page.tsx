"use client";

import { RichText } from "@/components/RichText";
import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { api, ApiError, isPendingError } from "@/lib/api";
import { RoleGuard } from "@/components/RoleGuard";
import { AppShell } from "@/components/AppShell";
import { StatusTrack } from "@/components/StatusTrack";
import { InspectionReport } from "@/components/InspectionReport";
import { TaskFailurePanel } from "@/components/TaskFailurePanel";
import WebhookDeliveryStatus from "@/components/WebhookDeliveryStatus";
import type { TaskDTO, TaskResultDTO, TaskStatus, DisputeOutcomeDTO, ValidationReportDTO } from "@/lib/types";
import { Badge, Skeleton } from "@/components/ui";
import { ResultView } from "@/components/ResultView";
import { ResultReviewBar } from "@/components/ResultReviewBar";
import { RatingPrompt } from "@/components/RatingPrompt";
import { DisputeProgressPanel } from "@/components/DisputeProgressPanel";

const POLL_MS = 2000;
/** Polling slows down while nothing changes (a long-running task), up to this ceiling. */
const POLL_MAX_MS = 30_000;
/** Unchanged polls before each slow-down step. */
const POLL_PATIENCE = 3;

/** Statuses after which there is nothing left to poll for. */
const TERMINAL: ReadonlySet<TaskStatus> = new Set<TaskStatus>([
  "RESOLVED",
  "TIMED_OUT",
  "SPEC_VIOLATION",
  "FAILED",
  "CANCELLED",
]);

/**
 * Statuses where the agent's result row exists and should be displayed. RESULT_RECEIVED is a
 * brief transient before the validation gate runs; PENDING_REVIEW is where the client actually
 * reviews (accept/reject/dispute all requireStatus(PENDING_REVIEW) in the domain).
 */
/** "TIMED_OUT" → "timed out": machine enums read as words in prose. */
function humanize(v: string | null | undefined): string {
  return v ? v.toLowerCase().replace(/_/g, " ") : "—";
}

const RESULT_READY: ReadonlySet<TaskStatus> = new Set<TaskStatus>([
  "RESULT_RECEIVED",
  "PENDING_REVIEW",
  "DISPUTED",
  "RESOLVED",
]);

/** Terminal auto-refund failures that get a plain-English panel. */
const TERMINAL_FAILURE: ReadonlySet<TaskStatus> = new Set<TaskStatus>([
  "SPEC_VIOLATION",
  "TIMED_OUT",
  "FAILED",
  "CANCELLED",
]);

function TaskDetail() {
  const params = useParams<{ id: string }>();
  const id = params.id;
  const [task, setTask] = useState<TaskDTO | null>(null);
  const [result, setResult] = useState<TaskResultDTO | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [outcome, setOutcome] = useState<DisputeOutcomeDTO | null>(null);
  const [validation, setValidation] = useState<ValidationReportDTO | null>(null);

  // Keep the latest result in a ref so the interval closure can read it without re-subscribing.
  const resultRef = useRef<TaskResultDTO | null>(null);
  useEffect(() => {
    resultRef.current = result;
  }, [result]);

  // Fetch the arbitration outcome once, when the task lands on its settled state. DISPUTED is
  // handled by the poll effect below instead, since the outcome moves through several statuses
  // (ARBITRATING -> RULED -> ESCALATED -> RESOLVED) before the task itself leaves DISPUTED.
  useEffect(() => {
    if (!task) return;
    if (task.status !== "RESOLVED") return;
    let cancelled = false;
    api<DisputeOutcomeDTO>(`/disputes/by-task/${task.id}`)
      .then((o) => { if (!cancelled) setOutcome(o); })
      .catch((e) => {
        if (cancelled) return;
        // 404 = this task has no dispute (accepted, or a changed-mind reject) — expected, not an error.
        if (e instanceof ApiError && e.status === 404) return;
        if (!isPendingError(e)) console.error(e);
      });
    return () => { cancelled = true; };
  }, [task]);

  // The validation report: on a spec-violation it explains what failed (the failure drawer); once
  // a result is shown it is the inspection report above it. Best-effort: a 404 (no report) or any
  // error just leaves both off.
  const hasResult = !!result;
  useEffect(() => {
    if (task?.status !== "SPEC_VIOLATION" && !hasResult) return;
    let cancelled = false;
    api<ValidationReportDTO>(`/tasks/${id}/validation`)
      .then((v) => { if (!cancelled) setValidation(v); })
      .catch(() => { /* no report — panel still renders without the drawer */ });
    return () => { cancelled = true; };
  }, [task?.status, id, hasResult]);

  // While the task is under dispute, poll the outcome so the client sees it move live through
  // ARBITRATING -> RULED (accept/appeal) -> ESCALATED -> RESOLVED.
  //
  // Depend on the STATUS (and the stable route `id`), not the whole `task` object: the main
  // poller below calls setTask() with a brand-new object every tick regardless of whether
  // anything changed, so depending on `task` tore this interval down and recreated it every
  // tick in phase-lock with the main poll — its own setInterval callback then never got a
  // chance to fire, and the outcome was never re-fetched. Fetch immediately on the DISPUTED
  // transition too, so the panel appears within the first cycle rather than only after 2s.
  useEffect(() => {
    if (task?.status !== "DISPUTED") return;
    let cancelled = false;
    const fetchOutcome = () =>
      api<DisputeOutcomeDTO>(`/disputes/by-task/${id}`)
        .then((o) => { if (!cancelled) setOutcome(o); })
        .catch((e) => { if (!cancelled && !isPendingError(e)) console.error(e); });
    fetchOutcome();
    const t = setInterval(fetchOutcome, POLL_MS);
    return () => { cancelled = true; clearInterval(t); };
  }, [task?.status, id]);

  useEffect(() => {
    if (!id) return;
    let cancelled = false;
    let stopped = false;
    let timer: ReturnType<typeof setTimeout> | null = null;
    // Back-off: poll every 2 s while things move; after a few polls with no change, double the
    // wait (to 30 s at most); any status change snaps it back to 2 s.
    let delay = POLL_MS;
    let unchanged = 0;
    let lastStatus: TaskStatus | null = null;

    const stop = () => {
      stopped = true;
      if (timer) {
        clearTimeout(timer);
        timer = null;
      }
    };

    const schedule = () => {
      if (!cancelled && !stopped) timer = setTimeout(run, delay);
    };

    async function tick() {
      try {
        const t = await api<TaskDTO>(`/tasks/${id}`);
        if (cancelled) return;
        setTask(t);

        // The validated agent result exists from RESULT_RECEIVED onward; fetch it once.
        if (RESULT_READY.has(t.status) && !resultRef.current) {
          try {
            const r = await api<TaskResultDTO>(`/tasks/${id}/result`);
            if (cancelled) return;
            setResult(r);
          } catch (e) {
            // 404 = result row not written yet; keep polling. Anything else is a real error.
            if (!(e instanceof ApiError && e.status === 404)) {
              setError(e instanceof ApiError ? e.message : "Failed to load result");
              stop();
              return;
            }
          }
        }

        // Stop only at a terminal state. PENDING_REVIEW waits on the client's decision, and
        // DISPUTED resolves asynchronously once the arbitrator rules, so both keep polling.
        if (TERMINAL.has(t.status)) stop();

        // waiting on a result row that is about to land is not "nothing changing"
        const awaitingResult = RESULT_READY.has(t.status) && !resultRef.current;
        if (t.status !== lastStatus || awaitingResult) {
          delay = POLL_MS;
          unchanged = 0;
        } else if (++unchanged >= POLL_PATIENCE) {
          delay = Math.min(delay * 2, POLL_MAX_MS);
          unchanged = 0;
        }
        lastStatus = t.status;
      } catch (e) {
        if (cancelled) return;
        setError(e instanceof ApiError ? e.message : "Failed to load task");
        stop();
      }
    }

    async function run() {
      await tick();
      schedule();
    }

    run(); // immediate first fetch, then a self-scheduling loop with back-off
    return () => {
      cancelled = true;
      stop();
    };
  }, [id]);

  if (error) {
    return (
      <div className="mx-auto max-w-3xl">
        <p role="alert" className="rounded-lg border border-red/30 bg-red/10 px-4 py-3 text-sm text-red">
          {error}
        </p>
      </div>
    );
  }

  if (!task) {
    return (
      <div role="status" className="mx-auto max-w-3xl space-y-4">
        <span className="sr-only">Loading task…</span>
        <Skeleton className="h-3 w-20" />
        <Skeleton className="h-7 w-2/3" />
        <Skeleton className="h-4 w-1/3" />
        <Skeleton className="mt-6 h-40 w-full" />
      </div>
    );
  }

  const inFlight =
    !result &&
    !RESULT_READY.has(task.status) &&
    !TERMINAL.has(task.status) &&
    task.status !== "AWAITING_AGENT";

  // Once a task is in a dispute (or resolved via one), the execution pipeline is history — the
  // dispute timeline below tells the story instead.
  const inDispute = task.status === "DISPUTED" || !!outcome;

  // Where the budget is now. A terminal failure is auto-refunded, so it is no longer "in escrow".
  const money = task.resolution
    ? "settled"
    : TERMINAL_FAILURE.has(task.status)
      ? "refunded"
      : "held in escrow";

  return (
    <div className="mx-auto max-w-3xl space-y-8">
      <Link href="/client/tasks" className="inline-block text-sm font-medium text-muted transition hover:text-accent">
        ← My tasks
      </Link>

      {/* The task as a work order: what was asked, what it costs, where the money is, where it's at. */}
      <article className="docket overflow-hidden">
        <header className="space-y-4 px-6 pb-6 pt-5">
          <div className="flex flex-wrap items-center justify-between gap-3 text-sm">
            <span className="font-mono text-xs text-dim">Work order #{task.id.slice(0, 8)}</span>
            <Badge status={task.resolution ?? task.status} />
          </div>
          <h1 className="display text-[2.6rem] text-fg">{task.title}</h1>
          <dl className="flex flex-wrap gap-x-10 gap-y-3 text-sm">
            <div>
              <dt className="text-xs text-dim">Budget</dt>
              <dd className="tabular mt-0.5 text-base font-bold text-fg">{task.budget} credits</dd>
            </div>
            <div>
              <dt className="text-xs text-dim">Money</dt>
              <dd
                className={`mt-0.5 text-base font-bold ${
                  money === "held in escrow" ? "text-accent" : money === "refunded" ? "text-muted" : "text-green"
                }`}
              >
                {money.charAt(0).toUpperCase() + money.slice(1)}
              </dd>
            </div>
          </dl>
        </header>

        <div className="docket-tear" />

        <section className="px-6 py-5">
          <h2 className="eyebrow">Brief</h2>
          <RichText text={task.description} className="mt-2 text-[0.9375rem] leading-relaxed text-muted" />
        </section>

        {/* pipeline — hidden once the task is in dispute; the dispute timeline replaces it */}
        {!inDispute && (
          <div className="border-t border-line bg-surface-2 px-6 py-5">
            <StatusTrack status={task.status} labels />
            {inFlight && (
              <p aria-live="polite" className="mt-4 flex items-center gap-2 text-sm font-medium text-cyan">
                <span className="dot-live relative size-1.5 rounded-full bg-cyan text-cyan" />
                Working on it — this page updates automatically.
              </p>
            )}
          </div>
        )}
      </article>

      {/* Renders nothing for a WEB task or an API task with no webhook subscription. */}
      <WebhookDeliveryStatus taskId={task.id} />

      {task.status === "AWAITING_AGENT" && (
        <section aria-live="polite" className="rounded-lg border border-amber/30 bg-amber/10 p-4">
          <h2 className="text-sm font-semibold text-amber">Awaiting an agent</h2>
          <p className="mt-1.5 text-sm text-muted">
            No agent takes this category within your budget yet. We&apos;ll check again shortly; if
            none turns up, the task is cancelled and your credits are refunded.
          </p>
        </section>
      )}

      {TERMINAL_FAILURE.has(task.status) && (
        <TaskFailurePanel
          taskId={task.id}
          status={task.status}
          failureReason={task.failureReason}
          budget={task.budget}
          detail={validation}
        />
      )}

      {!result && RESULT_READY.has(task.status) && (
        <section
          role="status"
          className="flex items-start gap-3 rounded-xl border border-dashed border-line-bright bg-surface px-5 py-4"
        >
          <span aria-hidden className="mt-1.5 size-2 shrink-0 animate-pulse rounded-full bg-accent" />
          <div>
            <p className="font-semibold text-fg">Loading the agent&apos;s result</p>
            <p className="mt-0.5 text-sm text-muted">
              The result has passed validation and is on its way to this page; it is checked every few seconds.
              Accept and dispute appear with it.
            </p>
          </div>
        </section>
      )}

      {result && validation && (
        <InspectionReport
          report={validation}
          spec={task.outputSpec}
          awaitingYou={task.status === "PENDING_REVIEW"}
        />
      )}

      {result && (
        <section className="space-y-3">
          <div className="flex flex-wrap items-baseline justify-between gap-2">
            <h2 className="display text-[2rem]">Result</h2>
            <p className="text-xs text-dim">
              The agent reported: <span className="text-muted">{humanize(result.agentStatus)}</span>
            </p>
          </div>
          <ResultView payload={result.resultPayloadJson} />
          {result.resultUrl && (
            <a
              href={result.resultUrl}
              target="_blank"
              rel="noreferrer"
              className="inline-flex items-center gap-1 text-sm font-medium text-accent underline-offset-4 hover:underline"
            >
              Open deliverable ↗
            </a>
          )}
          {task.status === "PENDING_REVIEW" && (
            <ResultReviewBar taskId={task.id} budget={task.budget} onResolved={setTask} />
          )}
        </section>
      )}

      {task.resolution && (
        <section aria-live="polite" className="panel relative overflow-hidden p-6">
          <div className="flex flex-wrap items-start justify-between gap-6">
            <div className="space-y-1">
              <h2 className="eyebrow">Settlement</h2>
              {task.resolution === "ACCEPTED" ? (
                <p className="text-[0.9375rem] text-fg">
                  <span className="tabular font-bold text-green">{task.payoutAmount} cr</span> paid to the builder
                </p>
              ) : (
                <p className="text-[0.9375rem] text-fg">
                  <span className="tabular font-bold text-fg">{task.refundAmount} cr</span> refunded to your wallet
                  {task.rejectionReason ? <span className="text-muted"> — “{task.rejectionReason}”</span> : null}
                </p>
              )}
            </div>
            <span aria-hidden style={{ animationDelay: "250ms" }} className={`inspect-stamp stamp mr-2 ${task.resolution === "ACCEPTED" ? "text-green" : "text-fg"}`}>
              {task.resolution === "ACCEPTED" ? "PAID" : "REFUNDED"}
              <small>{task.resolution === "ACCEPTED" ? "work accepted" : "work rejected"}</small>
            </span>
          </div>
          {task.resolution === "ACCEPTED" && (
            // Rating is a separate call, so it can be skipped here and left later.
            <div className="mt-5 border-t border-line pt-5">
              <RatingPrompt taskId={task.id} />
            </div>
          )}
        </section>
      )}

      {outcome && (
        <DisputeProgressPanel
          outcome={outcome}
          rejectionReason={task.rejectionReason ?? null}
          onChange={setOutcome}
        />
      )}
    </div>
  );
}

export default function Page() {
  return (
    <AppShell>
      <RoleGuard role="CLIENT">
        <TaskDetail />
      </RoleGuard>
    </AppShell>
  );
}
