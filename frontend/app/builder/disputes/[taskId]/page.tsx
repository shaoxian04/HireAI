"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { api, ApiError } from "@/lib/api";
import { AppShell } from "@/components/AppShell";
import { RoleGuard } from "@/components/RoleGuard";
import { Badge, Card, Skeleton } from "@/components/ui";
import { DisputeProgressPanel } from "@/components/DisputeProgressPanel";
import type { DisputeOutcomeDTO } from "@/lib/types";

const POLL_MS = 5000;

/** Where the escrowed credits are, from the builder's side. */
function moneyText(o: DisputeOutcomeDTO): string {
  if (o.status !== "RESOLVED") return "Held in escrow";
  if (o.effectiveCategory === "FULFILLED") return "Paid to you";
  if (o.effectiveCategory === "PARTIALLY_FULFILLED") return "Split";
  return "Refunded to the client";
}

/** One dispute on a task the builder's agent ran (#96): the timeline, and accept/appeal when it is theirs to decide. */
function BuilderDispute() {
  const params = useParams<{ taskId: string }>();
  const taskId = params?.taskId;
  const [outcome, setOutcome] = useState<DisputeOutcomeDTO | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    if (!taskId) return Promise.resolve();
    return api<DisputeOutcomeDTO>(`/disputes/by-task/${taskId}`)
      .then((o) => {
        setOutcome(o);
        setError(null);
      })
      .catch((e) => setError(e instanceof ApiError ? e.message : "Failed to load the dispute"));
  }, [taskId]);

  useEffect(() => {
    void load();
  }, [load]);

  // Until it settles the dispute moves without the builder: the arbitrator, the client, an admin.
  const settled = outcome?.status === "RESOLVED";
  useEffect(() => {
    if (!outcome || settled) return;
    const timer = setInterval(() => void load(), POLL_MS);
    return () => clearInterval(timer);
  }, [outcome, settled, load]);

  return (
    <div className="mx-auto max-w-3xl space-y-8">
      <Link href="/builder/disputes" className="inline-block text-sm font-medium text-muted transition hover:text-accent">
        ← Disputes
      </Link>

      {error && !outcome && (
        <Card>
          <p role="alert" className="text-sm text-red">
            {error}
          </p>
        </Card>
      )}
      {!outcome && !error && (
        <div className="space-y-3" aria-busy>
          <Skeleton className="h-40 w-full" />
          <Skeleton className="h-64 w-full" />
        </div>
      )}

      {outcome && (
        <>
          <article className="docket overflow-hidden">
            <header className="space-y-4 px-6 pb-6 pt-5">
              <div className="flex flex-wrap items-center justify-between gap-3 text-sm">
                <span className="font-mono text-xs text-dim">Work order #{outcome.taskId.slice(0, 8)}</span>
                <Badge status={outcome.status} />
              </div>
              <h1 className="display text-[2.6rem] text-fg">{outcome.taskTitle ?? "Disputed task"}</h1>
              <dl className="flex flex-wrap gap-x-10 gap-y-3 text-sm">
                {outcome.price != null && (
                  <div>
                    <dt className="text-xs text-dim">Price</dt>
                    <dd className="tabular mt-0.5 text-base font-bold text-fg">{outcome.price.toFixed(2)} credits</dd>
                  </div>
                )}
                <div>
                  <dt className="text-xs text-dim">Money</dt>
                  <dd className={`mt-0.5 text-base font-bold ${settled ? "text-fg" : "text-accent"}`}>
                    {moneyText(outcome)}
                  </dd>
                </div>
              </dl>
            </header>
          </article>
          {error && (
            <p role="alert" className="text-xs text-red">
              {error}
            </p>
          )}
          <DisputeProgressPanel outcome={outcome} onChange={setOutcome} />
        </>
      )}
    </div>
  );
}

export default function Page() {
  return (
    <AppShell>
      <RoleGuard role="BUILDER">
        <BuilderDispute />
      </RoleGuard>
    </AppShell>
  );
}
