"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { api, ApiError } from "@/lib/api";
import { RoleGuard } from "@/components/RoleGuard";
import { AppShell } from "@/components/AppShell";
import { StatusTrack } from "@/components/StatusTrack";
import type { TaskDTO, TaskResultDTO, TaskStatus } from "@/lib/types";
import { Badge, Card } from "@/components/ui";
import { ResultReviewBar } from "@/components/ResultReviewBar";

const POLL_MS = 2000;

/** Statuses after which there is nothing left to poll for. */
const TERMINAL: ReadonlySet<TaskStatus> = new Set<TaskStatus>([
  "RESOLVED",
  "TIMED_OUT",
  "SPEC_VIOLATION",
  "FAILED",
  "CANCELLED",
]);

/** Pretty-print a JSON string; fall back to the raw text if it does not parse. */
function prettyJson(raw: string): string {
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}

function TaskDetail() {
  const params = useParams<{ id: string }>();
  const id = params.id;
  const [task, setTask] = useState<TaskDTO | null>(null);
  const [result, setResult] = useState<TaskResultDTO | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Keep the latest result in a ref so the interval closure can read it without re-subscribing.
  const resultRef = useRef<TaskResultDTO | null>(null);
  useEffect(() => {
    resultRef.current = result;
  }, [result]);

  useEffect(() => {
    if (!id) return;
    let cancelled = false;
    let timer: ReturnType<typeof setInterval> | null = null;

    const stop = () => {
      if (timer) {
        clearInterval(timer);
        timer = null;
      }
    };

    async function tick() {
      try {
        const t = await api<TaskDTO>(`/tasks/${id}`);
        if (cancelled) return;
        setTask(t);

        if ((t.status === "RESULT_RECEIVED" || t.status === "RESOLVED") && !resultRef.current) {
          try {
            const r = await api<TaskResultDTO>(`/tasks/${id}/result`);
            if (cancelled) return;
            setResult(r);
            stop(); // result is in — nothing more to poll
          } catch (e) {
            // 404 = result row not written yet; keep polling. Anything else is a real error.
            if (!(e instanceof ApiError && e.status === 404)) {
              setError(e instanceof ApiError ? e.message : "Failed to load result");
              stop();
            }
          }
        } else if (TERMINAL.has(t.status)) {
          stop();
        }
      } catch (e) {
        if (cancelled) return;
        setError(e instanceof ApiError ? e.message : "Failed to load task");
        stop();
      }
    }

    tick(); // immediate first fetch, then interval
    timer = setInterval(tick, POLL_MS);
    return () => {
      cancelled = true;
      stop();
    };
  }, [id]);

  if (error) {
    return (
      <div className="mx-auto max-w-2xl">
        <Card>
          <p role="alert" className="font-mono text-sm text-red">
            {error}
          </p>
        </Card>
      </div>
    );
  }

  if (!task) {
    return (
      <div className="mx-auto max-w-2xl">
        <Card>
          <p className="font-mono text-sm text-dim">Loading task…</p>
        </Card>
      </div>
    );
  }

  const inFlight =
    task.status !== "RESULT_RECEIVED" &&
    !result &&
    !TERMINAL.has(task.status) &&
    task.status !== "AWAITING_CAPACITY";

  return (
    <div className="mx-auto max-w-2xl space-y-6">
      <Link href="/client/tasks" className="font-mono text-xs text-dim transition hover:text-accent">
        ← my tasks
      </Link>

      <Card className="space-y-5">
        <header className="flex items-start justify-between gap-4">
          <div className="min-w-0">
            <h1 className="text-xl font-extrabold tracking-tight">{task.title}</h1>
            <p className="mt-1 font-mono text-xs text-dim">
              #{task.id.slice(0, 8)} ·{" "}
              <span className="tabular text-accent">{task.budget}</span> cr{" "}
              {task.resolution ? "settled" : "in escrow"}
            </p>
          </div>
          <Badge status={task.status}>
            {task.resolution ? `RESOLVED · ${task.resolution}` : task.status}
          </Badge>
        </header>

        {/* pipeline */}
        <div className="rounded-md border border-line bg-surface-2 p-4">
          <p className="eyebrow mb-4">Pipeline</p>
          <StatusTrack status={task.status} labels />
        </div>

        <p className="text-sm leading-relaxed text-muted">{task.description}</p>

        {task.status === "AWAITING_CAPACITY" && (
          <section
            aria-live="polite"
            className="rounded-md border border-amber/30 bg-amber/10 p-4"
          >
            <h2 className="font-mono text-xs font-semibold uppercase tracking-wider text-amber">
              Waiting for an available agent
            </h2>
            <p className="mt-1.5 text-sm text-muted">
              No agent currently has capacity for this category. We&apos;ll keep checking and start
              your task as soon as one is free.
            </p>
          </section>
        )}

        {inFlight && (
          <p
            aria-live="polite"
            className="flex items-center gap-2 font-mono text-xs uppercase tracking-wider text-cyan"
          >
            <span className="size-1.5 rounded-full bg-cyan dot-live text-cyan" />
            working
            <span className="animate-blink">— status updates automatically</span>
          </p>
        )}

        {result && (
          <section className="space-y-3 border-t border-line pt-5">
            <div className="flex items-center justify-between">
              <h2 className="eyebrow">Result</h2>
              <p className="font-mono text-xs text-muted">
                Agent status: <strong className="text-accent">{result.agentStatus}</strong>
              </p>
            </div>
            <pre className="overflow-auto rounded-md border border-line bg-canvas p-4 font-mono text-xs leading-relaxed text-fg">
              {prettyJson(result.resultPayloadJson)}
            </pre>
            {result.resultUrl && (
              <a
                href={result.resultUrl}
                target="_blank"
                rel="noreferrer"
                className="inline-flex items-center gap-1 font-mono text-xs font-semibold uppercase tracking-wider text-accent underline-offset-4 hover:underline"
              >
                Open deliverable →
              </a>
            )}
            {task.status === "RESULT_RECEIVED" && (
              <ResultReviewBar taskId={task.id} onResolved={setTask} />
            )}
          </section>
        )}

        {task.resolution && (
          <section aria-live="polite" className="space-y-1 border-t border-line pt-5">
            <p className="eyebrow">Settled</p>
            {task.resolution === "ACCEPTED" ? (
              <p className="font-mono text-sm text-accent">
                {task.payoutAmount} cr paid to the builder · {task.commissionAmount} cr platform
                commission
              </p>
            ) : (
              <p className="font-mono text-sm text-red">
                {task.refundAmount} cr refunded to your wallet
                {task.rejectionReason ? ` — "${task.rejectionReason}"` : ""}
              </p>
            )}
          </section>
        )}
      </Card>
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
