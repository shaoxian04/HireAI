"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { api, ApiError } from "@/lib/api";
import { AppShell } from "@/components/AppShell";
import { RoleGuard } from "@/components/RoleGuard";
import { Button, Input, Skeleton } from "@/components/ui";
import { ReviewWindowNotice, reviewWindowClosed, useNow } from "@/components/ReviewWindowNotice";
import { SampleItemCard, type Judgement } from "@/components/sampling/SampleItemCard";
import { SamplingPath, PlanFacts, InspectionOutcome } from "@/components/sampling/InspectionSummary";
import { currentDraw, latestRejection, sampledInRound, tally } from "@/lib/sampling";
import type { InspectionDTO, TaskDTO, TaskItemDTO } from "@/lib/types";

const POLL_MS = 4000;

/**
 * The buyer's reviewer (#105, spec S8): the sampled Items of a multi-Item task beside their sources,
 * judged one by one. The platform judges the sample once every sampled Item has settled, and draws the
 * next sample, sends the task back for its rework, or settles, as the lot-size table says.
 */
function Reviewer() {
  const { id } = useParams<{ id: string }>();
  const now = useNow();
  const [task, setTask] = useState<TaskDTO | null>(null);
  const [inspection, setInspection] = useState<InspectionDTO | null | undefined>(undefined);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [showAll, setShowAll] = useState(false);
  const [extraNo, setExtraNo] = useState("");
  const [extra, setExtra] = useState<number[]>([]);

  const load = useCallback(async () => {
    const [t, i] = await Promise.all([
      api<TaskDTO>(`/tasks/${id}`),
      api<InspectionDTO | null>(`/tasks/${id}/inspection`),
    ]);
    setTask(t);
    setInspection(i);
  }, [id]);

  useEffect(() => {
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | null = null;
    async function tick() {
      try {
        await load();
        if (!cancelled) setLoadError(null);
      } catch (e) {
        if (!cancelled) setLoadError(e instanceof ApiError ? e.message : "Couldn't load the inspection");
      }
      if (!cancelled) timer = setTimeout(tick, POLL_MS);
    }
    tick();
    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
  }, [load]);

  const items = useMemo(() => new Map((task?.items ?? []).map((i) => [i.itemNo, i])), [task]);

  async function act(path: string, body: unknown) {
    setBusy(true);
    setActionError(null);
    try {
      setTask(await api<TaskDTO>(path, { method: "POST", body: JSON.stringify(body) }));
      setInspection(await api<InspectionDTO | null>(`/tasks/${id}/inspection`));
    } catch (e) {
      setActionError(e instanceof ApiError ? e.message : "Couldn't save that. Try again.");
    } finally {
      setBusy(false);
    }
  }

  const judge = (itemNo: number, j: Judgement) => act(`/tasks/${id}/items/${itemNo}/judgement`, j);
  const accept = (itemNo: number) => act(`/tasks/${id}/items/${itemNo}/decision`, { decision: "ACCEPT" });

  if (loadError && !task) {
    return (
      <div className="mx-auto max-w-4xl space-y-4">
        <BackLink id={id} />
        <p role="alert" className="rounded-lg border border-red/30 bg-red/10 px-4 py-3 text-sm text-red">{loadError}</p>
      </div>
    );
  }
  if (!task || inspection === undefined) {
    return (
      <div role="status" className="mx-auto max-w-4xl space-y-4">
        <span className="sr-only">Loading the inspection…</span>
        <Skeleton className="h-3 w-24" />
        <Skeleton className="h-8 w-2/3" />
        <Skeleton className="h-28 w-full" />
        <Skeleton className="h-64 w-full" />
      </div>
    );
  }
  if (!inspection) {
    return (
      <div className="mx-auto max-w-4xl space-y-6">
        <BackLink id={id} />
        <div className="panel space-y-2 px-6 py-8 text-center">
          <p className="display text-2xl text-fg">Nothing to sample here</p>
          <p className="text-sm text-muted">
            {task.items?.length
              ? "Sampling starts once the agent has delivered the Items. This page updates on its own."
              : "Only a task with several Items is inspected by sampling. Review this one on its task page."}
          </p>
        </div>
      </div>
    );
  }

  const draw = currentDraw(inspection);
  const sampled = sampledInRound(inspection, inspection.round);
  const windowOpen = !reviewWindowClosed(task.reviewDeadline, now);
  const canJudge = task.status === "PENDING_REVIEW" && windowOpen;
  const open = inspection.status === "SAMPLING" && draw?.decision == null;
  const t = draw ? tally(draw, items, inspection) : null;
  const sampleItems = (draw?.itemNos ?? []).map((n) => items.get(n)).filter((i): i is TaskItemDTO => !!i);
  const listed = showAll ? sampleItems : sampleItems.filter((i) => i.status === "PENDING_REVIEW" || i.status === "REJECTED");
  const earlier = inspection.draws.filter((d) => d !== draw);

  function addExtra() {
    const n = Number(extraNo);
    if (!Number.isInteger(n) || !items.has(n)) {
      setActionError(`There is no Item ${extraNo || "?"}; this task has Items 1 to ${task!.itemCount}.`);
      return;
    }
    setActionError(null);
    if (!extra.includes(n) && !sampled.has(n)) setExtra([n, ...extra]);
    setExtraNo("");
  }

  return (
    <div className="mx-auto max-w-4xl space-y-8">
      <BackLink id={id} />

      <header className="space-y-3">
        <p className="eyebrow">Reviewer · sampling inspection</p>
        <h1 className="display text-[2.4rem] text-fg">{task.title}</h1>
        <p className="max-w-2xl text-sm text-muted">
          {`You check a random sample of the ${task.itemCount} Items against their sources, instead of all of them. `
            + "The sample was drawn after the agent delivered, from a recorded seed, so the agent couldn't know which "
            + "Items you would check. Only the sample decides what happens to the rest."}
        </p>
      </header>

      <PlanFacts inspection={inspection} draw={draw} />
      <SamplingPath inspection={inspection} />
      <InspectionOutcome inspection={inspection} task={task} />

      {inspection.status === "REWORKING" && (
        <div role="status" className="flex items-start gap-3 rounded-lg border border-cyan/30 bg-cyan/10 px-4 py-3">
          <span aria-hidden className="mt-1.5 size-2 shrink-0 animate-pulse rounded-full bg-cyan" />
          <p className="text-sm text-muted">
            <span className="font-semibold text-fg">The sample failed, so the task is back with the agent for its one rework.</span>{" "}
            Every Item not yet accepted was sent back. When they return, a new sample is drawn here.
          </p>
        </div>
      )}

      {open && draw && t && (
        <section aria-labelledby="sample-h" className="space-y-4">
          <div className="flex flex-wrap items-baseline justify-between gap-2">
            <h2 id="sample-h" className="display text-[2rem]">
              {draw.stage === 2 ? "Second sample" : inspection.round === 2 ? "Resample" : "Sample"}
            </h2>
            <p className="tabular text-sm text-muted">
              {t.settled} of {t.size} settled
              <span className="text-dim">
                {` · ${t.wrong} wrong`}{t.inProgress ? ` · ${t.inProgress} with the agent or you` : ""}
              </span>
            </p>
          </div>
          <div className="h-1.5 overflow-hidden rounded-full bg-surface-2" aria-hidden>
            <div className="h-full rounded-full bg-accent transition-[width]" style={{ width: `${(t.settled / t.size) * 100}%` }} />
          </div>
          <ReviewWindowNotice deadline={task.reviewDeadline} now={now} subject="items" />

          <div className="flex flex-wrap items-center gap-3 text-sm">
            <Button size="sm" variant={showAll ? "ghost" : "secondary"} onClick={() => setShowAll(false)}>
              To do ({t.toJudge + t.inProgress})
            </Button>
            <Button size="sm" variant={showAll ? "secondary" : "ghost"} onClick={() => setShowAll(true)}>
              Whole sample ({t.size})
            </Button>
          </div>

          {actionError && (
            <p role="alert" className="rounded-lg border border-red/30 bg-red/10 px-4 py-3 text-sm text-red">{actionError}</p>
          )}

          {listed.length === 0 ? (
            <div className="panel px-6 py-6 text-center text-sm text-muted">
              Every Item in this sample is judged. The platform is judging the sample.
            </div>
          ) : (
            <ol className="panel divide-y divide-line overflow-hidden p-0">
              {listed.map((item) => (
                <SampleItemCard key={item.itemNo} taskId={id} item={item} sampled
                  rejection={latestRejection(inspection, item.itemNo)} canJudge={canJudge} busy={busy} now={now}
                  onJudge={judge} onAccept={accept} />
              ))}
            </ol>
          )}
        </section>
      )}

      {task.status === "PENDING_REVIEW" && (
        <section aria-labelledby="extra-h" className="space-y-4">
          <h2 id="extra-h" className="display text-[1.6rem]">Check another Item</h2>
          <p className="text-sm text-muted">
            You may check any Item while the window is open. A wrong one outside the sample is refunded on its own
            and never counts towards the sample.
          </p>
          <form className="flex flex-wrap items-end gap-3" onSubmit={(e) => { e.preventDefault(); addExtra(); }}>
            <label className="space-y-1">
              <span className="block text-xs font-semibold text-muted">Item number</span>
              <Input inputMode="numeric" className="w-32" value={extraNo} onChange={(e) => setExtraNo(e.target.value)}
                placeholder={`1–${task.itemCount}`} />
            </label>
            <Button type="submit" size="md" variant="secondary">Check it</Button>
          </form>
          {!open && actionError && (
            <p role="alert" className="rounded-lg border border-red/30 bg-red/10 px-4 py-3 text-sm text-red">{actionError}</p>
          )}
          {extra.length > 0 && (
            <ol className="panel divide-y divide-line overflow-hidden p-0">
              {extra.map((n) => items.get(n)).filter((i): i is TaskItemDTO => !!i).map((item) => (
                <SampleItemCard key={item.itemNo} taskId={id} item={item} sampled={false}
                  rejection={latestRejection(inspection, item.itemNo)} canJudge={canJudge} busy={busy} now={now}
                  onJudge={judge} onAccept={accept} />
              ))}
            </ol>
          )}
        </section>
      )}

      {earlier.length > 0 && (
        <section aria-labelledby="earlier-h" className="space-y-3">
          <h2 id="earlier-h" className="display text-[1.6rem]">Samples so far</h2>
          <ol className="panel divide-y divide-line overflow-hidden p-0">
            {inspection.draws.map((d) => (
              <li key={`${d.round}-${d.stage}`} className="flex flex-wrap items-baseline justify-between gap-3 px-5 py-3 text-sm">
                <span className="font-semibold text-fg">
                  {`Round ${d.round}, ${d.stage === 1 ? "first" : "second"} sample · ${d.itemNos.length} Items`}
                </span>
                <span className="tabular text-muted">
                  {d.decision == null ? "being judged" : `${d.defects} wrong → ${DECISION[d.decision]}`}
                </span>
              </li>
            ))}
          </ol>
        </section>
      )}
    </div>
  );
}

const DECISION: Record<string, string> = {
  ACCEPT_LOT: "lot accepted",
  DRAW_SECOND: "second sample drawn",
  REWORK: "whole task sent back for rework",
  PRO_RATA: "settled pro rata",
};

function BackLink({ id }: { id: string }) {
  return (
    <Link href={`/client/tasks/${id}`} className="inline-block text-sm font-medium text-muted transition hover:text-accent">
      ← Back to the task
    </Link>
  );
}

export default function Page() {
  return (
    <AppShell>
      <RoleGuard role="CLIENT">
        <Reviewer />
      </RoleGuard>
    </AppShell>
  );
}

