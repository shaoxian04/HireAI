"use client";

import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { ButtonLink } from "@/components/ui";
import { currentDraw, path, ruleText, tally } from "@/lib/sampling";
import type { InspectionDTO, SampleDrawDTO, TaskDTO } from "@/lib/types";

function cr(n: number | null | undefined): string {
  return `${Number(n ?? 0).toFixed(2)} cr`;
}

/** The plan the lot-size table set for the current sample, and the rule it is judged by. */
export function PlanFacts({ inspection, draw }: { inspection: InspectionDTO; draw: SampleDrawDTO | null }) {
  const [copied, setCopied] = useState(false);
  if (!draw) return null;
  const size = draw.stage === 1 ? draw.firstSampleSize : draw.secondSampleSize;
  return (
    <section aria-label="Sampling plan" className="docket overflow-hidden">
      <dl className="grid grid-cols-2 gap-x-6 gap-y-4 px-6 py-5 text-sm sm:grid-cols-4">
        <div>
          <dt className="text-xs text-dim">Lot</dt>
          <dd className="tabular font-semibold text-fg">{draw.lotSize} Items</dd>
        </div>
        <div>
          <dt className="text-xs text-dim">Inspection level</dt>
          <dd className="font-semibold text-fg">{inspection.level}{inspection.level === "II" ? " (normal)" : " (stricter)"}</dd>
        </div>
        <div>
          <dt className="text-xs text-dim">Sample</dt>
          <dd className="tabular font-semibold text-fg">
            {draw.fullInspection ? `every Item (${size})` : `${size} Items`}
          </dd>
        </div>
        <div>
          <dt className="text-xs text-dim">Table</dt>
          <dd className="tabular font-semibold text-fg">ISO 2859-1 · letter {draw.codeLetter} · AQL {inspection.aql}</dd>
        </div>
      </dl>
      <div className="space-y-2 border-t border-dashed border-line px-6 py-4">
        <p className="text-sm text-fg">{ruleText(draw)}</p>
        <p className="flex flex-wrap items-center gap-2 text-xs text-dim">
          <span>Seed</span>
          <code className="max-w-full break-all rounded bg-surface-2 px-1.5 py-0.5 font-mono text-muted">{draw.seed}</code>
          <button type="button" className="font-medium text-accent hover:underline"
            onClick={() => {
              navigator.clipboard?.writeText(draw.seed).then(() => setCopied(true), () => setCopied(false));
            }}>
            {copied ? "Copied" : "Copy"}
          </button>
          <span>· drawn {new Date(draw.drawnAt).toLocaleString()}; anyone with the seed can redraw this sample.</span>
        </p>
      </div>
    </section>
  );
}

const MARK: Record<string, string> = {
  done: "bg-green border-green",
  current: "bg-accent border-accent animate-pulse",
  upcoming: "bg-surface border-line-bright",
  skipped: "bg-surface-2 border-line",
};

/** The path a failed sample takes (D19), with where this inspection is on it. */
export function SamplingPath({ inspection }: { inspection: InspectionDTO }) {
  return (
    <ol aria-label="Sampling steps" className="flex flex-wrap gap-x-6 gap-y-3 text-sm">
      {path(inspection).map((step, i) => (
        <li key={step.key} className={`flex items-center gap-2 ${step.state === "skipped" ? "text-dim line-through decoration-line-bright" : step.state === "upcoming" ? "text-muted" : "text-fg"}`}
          aria-current={step.state === "current" ? "step" : undefined}>
          <span aria-hidden className={`size-2.5 rounded-full border ${MARK[step.state]}`} />
          <span className="tabular text-xs text-dim">{i + 1}</span>
          <span className={step.state === "current" ? "font-semibold" : undefined}>{step.label}</span>
          <span className="sr-only">({step.state})</span>
        </li>
      ))}
    </ol>
  );
}

/** How the inspection ended, once it did. */
export function InspectionOutcome({ inspection, task }: { inspection: InspectionDTO; task: TaskDTO }) {
  const decided = inspection.draws.filter((d) => d.round === inspection.round);
  const wrong = decided.reduce((n, d) => n + (d.defects ?? 0), 0);
  const sampled = decided.reduce((n, d) => n + d.itemNos.length, 0);
  let headline: string | null = null;
  let body: string | null = null;
  if (inspection.status === "ACCEPTED") {
    headline = "The sample passed";
    body = `${wrong} of ${sampled} sampled Items were wrong, within what the table accepts, so every Item still `
      + "awaiting review was accepted with it. Wrong Items were refunded on their own."
      + ((task.items ?? []).some((i) => i.needsReview)
        ? " Items flagged for your own check were not part of it: each is settled by your judgement."
        : "");
  } else if (inspection.status === "PRO_RATA") {
    const rate = Number(inspection.errorRate ?? 0);
    headline = "Settled pro rata";
    body = `The resample failed too. The measured error rate is ${(rate * 100).toFixed(2)}% (${wrong} of ${sampled} `
      + `sampled), so each unchecked Item was paid ${(100 - rate * 100).toFixed(2)}% of its price and the rest refunded.`;
  } else if (inspection.status === "CLOSED") {
    headline = "Nothing left to sample";
    body = "No Item that passed its checks was left to sample, so no further sample is drawn. Items that failed "
      + "their checks were refunded on their own.";
  } else if (inspection.status === "WAIVED") {
    headline = "Accepted without finishing the sample";
    body = "You accepted the Items awaiting review, or the review window closed, before the sample was judged.";
  }
  if (!headline) return null;
  return (
    <div role="status" className="space-y-2 rounded-xl border border-line bg-surface px-5 py-4">
      <p className="font-semibold text-fg">{headline}</p>
      <p className="text-sm text-muted">{body}</p>
      {task.status === "RESOLVED" && (
        <p className="tabular text-sm text-fg">
          {cr(task.payoutAmount)} paid to the builder · {cr(task.commissionAmount)} fee · {cr(task.refundAmount)} refunded
        </p>
      )}
    </div>
  );
}

/**
 * The inspection on the task page: where the sample stands, and the way to the reviewer. Polls with
 * the task (the parent re-renders it on every task change).
 */
export function InspectionCard({ task }: { task: TaskDTO }) {
  const [inspection, setInspection] = useState<InspectionDTO | null>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    let cancelled = false;
    api<InspectionDTO | null>(`/tasks/${task.id}/inspection`)
      .then((i) => { if (!cancelled) { setInspection(i); setFailed(false); } })
      .catch(() => { if (!cancelled) setFailed(true); });
    return () => { cancelled = true; };
  }, [task]);

  if (failed) {
    return <p role="alert" className="text-sm text-red">Couldn&apos;t load the sampling inspection; it is retried on the next update.</p>;
  }
  // Closed before any sample was drawn: there is no inspection to open, so the task page shows no entry.
  if (!inspection || (inspection.status === "CLOSED" && inspection.draws.length === 0)) return null;
  const draw = currentDraw(inspection);
  const items = new Map((task.items ?? []).map((i) => [i.itemNo, i]));
  const t = draw ? tally(draw, items, inspection) : null;
  const open = inspection.status === "SAMPLING" && draw?.decision == null;
  return (
    <section aria-labelledby="inspection-h" className="docket overflow-hidden">
      <div className="flex flex-wrap items-start justify-between gap-4 px-6 py-5">
        <div className="min-w-0 space-y-1">
          <p id="inspection-h" className="eyebrow">Sampling inspection</p>
          <p className="text-[0.9375rem] text-fg">
            {open && draw && t
              ? `${inspection.round === 2 ? "Resample" : draw.stage === 2 ? "Second sample" : "Sample"}: `
                + `${t.settled} of ${t.size} settled, ${t.wrong} wrong.`
              : inspection.status === "REWORKING"
                ? "The sample failed; the task is back with the agent for its one rework."
                : inspection.status === "ACCEPTED" ? "The sample passed; the lot was accepted."
                  : inspection.status === "PRO_RATA"
                    ? `Settled pro rata at a ${(Number(inspection.errorRate ?? 0) * 100).toFixed(2)}% error rate.`
                    : inspection.status === "WAIVED" ? "Accepted before the sample was judged."
                      : inspection.status === "CLOSED" ? "Closed: no Item was left to sample."
                        : "Judging the sample…"}
          </p>
          <p className="text-xs text-dim">
            {`Level ${inspection.level} · round ${inspection.round} of 2`}
            {draw ? ` · ${draw.lotSize} Items in the lot, letter ${draw.codeLetter}` : ""}
          </p>
        </div>
        <ButtonLink href={`/client/tasks/${task.id}/review`} variant={open ? "primary" : "secondary"} size="md">
          {open && task.status === "PENDING_REVIEW" ? "Review the sample" : "Open the inspection"}
        </ButtonLink>
      </div>
    </section>
  );
}
