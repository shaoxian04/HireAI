"use client";

import { useEffect, useRef, useState } from "react";
import { useParams } from "next/navigation";
import { api, ApiError } from "@/lib/api";
import { RoleGuard } from "@/components/RoleGuard";
import type { TaskDTO, TaskResultDTO, TaskStatus } from "@/lib/types";
import { Badge, Card } from "@/components/ui";

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

        if (t.status === "RESULT_RECEIVED" && !resultRef.current) {
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
          <p role="alert" className="text-sm text-red-700">
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
          <p className="text-sm text-slate-500">Loading task…</p>
        </Card>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-2xl space-y-6">
      <Card className="space-y-4">
        <header className="flex items-start justify-between gap-4">
          <h1 className="text-xl font-semibold tracking-tight text-slate-900">{task.title}</h1>
          <Badge status={task.status}>{task.status}</Badge>
        </header>
        <p className="text-sm text-slate-500">
          Budget: <span className="font-medium text-slate-900">{task.budget}</span> credits
        </p>
        <p className="text-sm text-slate-700">{task.description}</p>

        {task.status === "AWAITING_CAPACITY" && (
          <section
            aria-live="polite"
            className="rounded-lg border border-amber-200 bg-amber-50 p-4"
          >
            <h2 className="text-sm font-semibold text-amber-800">Waiting for an available agent</h2>
            <p className="mt-1 text-sm text-amber-700">
              No agent currently has capacity for this category. We&apos;ll keep checking and start
              your task as soon as one is free.
            </p>
          </section>
        )}

        {task.status !== "RESULT_RECEIVED" &&
          !result &&
          !TERMINAL.has(task.status) &&
          task.status !== "AWAITING_CAPACITY" && (
            <p aria-live="polite" className="text-sm text-slate-500">
              Working… (status updates automatically)
            </p>
          )}

        {result && (
          <section className="space-y-3 border-t border-slate-200 pt-4">
            <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-500">Result</h2>
            <p className="text-sm text-slate-700">
              Agent status: <strong className="text-slate-900">{result.agentStatus}</strong>
            </p>
            <pre className="overflow-auto rounded-md bg-slate-900 p-4 text-xs leading-relaxed text-slate-100">
              {prettyJson(result.resultPayloadJson)}
            </pre>
            {result.resultUrl && (
              <p>
                <a
                  href={result.resultUrl}
                  target="_blank"
                  rel="noreferrer"
                  className="text-sm font-medium text-indigo-600 underline-offset-2 hover:underline"
                >
                  Open deliverable
                </a>
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
    <RoleGuard role="CLIENT">
      <TaskDetail />
    </RoleGuard>
  );
}
