"use client";

import { useState } from "react";
import Link from "next/link";
import { api, ApiError } from "@/lib/api";
import type { ItemStatus, TaskDTO, TaskItemDTO, TaskStatus } from "@/lib/types";
import { Badge, Button, ConfirmDialog } from "@/components/ui";
import { ResultView } from "@/components/ResultView";
import { ruleText } from "@/components/InspectionReport";
import { ReviewWindowNotice, reviewWindowClosed, useNow } from "@/components/ReviewWindowNotice";

interface Props {
  /** A multi-Item task (`task.items` set). */
  task: TaskDTO;
  /** Fixes the current time (tests). */
  now?: Date;
  /** Called with the task the backend returns after an accept. */
  onChange: (task: TaskDTO) => void;
}

/** Items listed before "Show all": a 500-Item task stays readable. */
const PAGE = 50;

/** Task statuses after which an Item still awaiting its result will never get one. */
const ENDED: ReadonlySet<TaskStatus> = new Set<TaskStatus>(["TIMED_OUT", "FAILED", "CANCELLED", "SPEC_VIOLATION", "RESOLVED"]);

/** Whether a link from an Agent is safe to make clickable: absolute https only (never javascript: etc.). */
export function isHttpsUrl(url: string | null | undefined): boolean {
  if (!url) return false;
  try {
    return new URL(url).protocol === "https:";
  } catch {
    return false;
  }
}

function cr(amount: number | null | undefined): string {
  return `${Number(amount ?? 0).toFixed(2)} cr`;
}

/** The pill for one Item: its outcome in words, coloured like the task lifecycle. */
function ItemBadge({ item, taskStatus }: { item: TaskItemDTO; taskStatus: TaskStatus }) {
  const label: Record<ItemStatus, string> = {
    AWAITING_RESULT: ENDED.has(taskStatus) ? "Not delivered" : "Waiting for the agent",
    PENDING_REVIEW: "Needs your review",
    ACCEPTED: item.autoAccepted ? "Accepted automatically" : "Accepted",
    SPEC_VIOLATION: "Failed its checks",
    FAILED: "Agent couldn't do it",
    REJECTED: "Rejected · being answered",
    CONCEDED: "Wrong · refunded",
    PRO_RATA: "Paid pro rata",
  };
  const COLOUR: Partial<Record<ItemStatus, string>> = {
    ACCEPTED: "RESOLVED",
    REJECTED: "AWAITING_AGENT_RESPONSE",
    CONCEDED: "FAILED",
    PRO_RATA: "PARTIALLY_ACCEPTED",
  };
  const colour = item.status === "AWAITING_RESULT" && ENDED.has(taskStatus) ? "CANCELLED" : item.status === "AWAITING_RESULT" ? "QUEUED" : COLOUR[item.status] ?? item.status;
  return <Badge status={colour}>{label[item.status]}</Badge>;
}

/** Where this Item's share of the escrow went. */
function Money({ item }: { item: TaskItemDTO }) {
  if (item.payoutAmount != null) {
    return (
      <p className="tabular text-xs text-dim">
        <span className="font-semibold text-green">{cr(item.payoutAmount)} paid</span> to the builder
        {item.commissionAmount ? ` · ${cr(item.commissionAmount)} fee` : null}
        {item.refundAmount ? ` · ${cr(item.refundAmount)} refunded` : null}
      </p>
    );
  }
  if (item.refundAmount != null) {
    return <p className="tabular text-xs font-semibold text-muted">{cr(item.refundAmount)} refunded</p>;
  }
  return <p className="tabular text-xs text-dim">{cr(item.price)} in escrow</p>;
}

function DocIcon() {
  return (
    <svg viewBox="0 0 16 16" className="size-3" aria-hidden>
      <path d="M4 1.5h5L12.5 5v9.5h-8.5zM9 1.5V5h3.5" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" />
    </svg>
  );
}

function ItemRow({ item, task, canDecide, busy, onAccept }: {
  item: TaskItemDTO;
  task: TaskDTO;
  canDecide: boolean;
  busy: boolean;
  onAccept: (itemNo: number) => void;
}) {
  const failed = (item.checks ?? []).filter((c) => !c.passed);
  const hasResult = item.resultPayload != null && item.resultPayload !== "";
  return (
    <li className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 px-5 py-4 sm:grid-cols-[auto_1fr_auto]">
      <span className="tabular font-mono text-xs font-bold text-dim pt-0.5">#{String(item.itemNo).padStart(2, "0")}</span>
      <div className="min-w-0 space-y-2">
        <div className="flex flex-wrap items-center gap-2">
          <ItemBadge item={item} taskStatus={task.status} />
        </div>
        <p className="break-words text-[0.9375rem] text-fg">
          {item.sourceId && (
            <span className="mr-2 inline-flex items-center gap-1 rounded border border-line-bright bg-surface-2 px-1.5 py-0.5 align-middle text-[0.6875rem] font-semibold uppercase tracking-wide text-muted">
              <DocIcon /> Document
            </span>
          )}
          {item.input}
        </p>
        {failed.length > 0 && (
          <div aria-label={`Checks Item ${item.itemNo} failed`} className="space-y-1">
            {failed.map((c) => (
              <p key={c.rule} className="text-sm text-red">
                <span aria-hidden>✕ </span>
                {ruleText(c.rule)}
                {c.detail ? <span className="text-muted"> — {c.detail}</span> : null}
              </p>
            ))}
          </div>
        )}
        {hasResult && (
          <details className="group rounded-lg border border-line bg-surface-2 px-3 py-2">
            <summary className="cursor-pointer text-xs font-semibold text-muted group-open:mb-2">Agent&apos;s result</summary>
            <ResultView payload={item.resultPayload as string} />
          </details>
        )}
        {item.resultUrl && (isHttpsUrl(item.resultUrl) ? (
          <a href={item.resultUrl} target="_blank" rel="noopener noreferrer"
            className="inline-flex text-sm font-medium text-accent underline-offset-4 hover:underline">
            Open deliverable ↗
          </a>
        ) : (
          <p className="break-all font-mono text-xs text-dim">{item.resultUrl}</p>
        ))}
      </div>
      <div className="col-span-2 flex items-center justify-between gap-3 sm:col-span-1 sm:flex-col sm:items-end sm:justify-start">
        <Money item={item} />
        {canDecide && item.status === "PENDING_REVIEW" && (
          <Button variant="secondary" size="sm" disabled={busy} onClick={() => onAccept(item.itemNo)}
            aria-label={`Accept Item ${item.itemNo}`}>
            Accept
          </Button>
        )}
      </div>
    </li>
  );
}

/**
 * The Items of a multi-Item task (#103) and what became of each: every Item is checked, accepted and
 * settled on its own, so one bad Item never holds the rest back. While the task awaits review the
 * Client accepts Items one at a time or all at once; the review window (counted in business days)
 * accepts whatever is left when it closes.
 */
export function ItemsPanel({ task, now, onChange }: Props) {
  const current = useNow(now);
  const items = task.items ?? [];
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirmingAll, setConfirmingAll] = useState(false);
  const [shown, setShown] = useState(PAGE);

  const awaiting = items.filter((i) => i.status === "PENDING_REVIEW");
  const accepted = items.filter((i) => i.status === "ACCEPTED").length;
  const refunded = items.filter((i) => i.status === "SPEC_VIOLATION" || i.status === "FAILED" || i.status === "CONCEDED").length;
  const proRata = items.filter((i) => i.status === "PRO_RATA").length;
  const rejected = items.filter((i) => i.status === "REJECTED").length;
  const reviewing = task.status === "PENDING_REVIEW";
  const canDecide = reviewing && !reviewWindowClosed(task.reviewDeadline, current);
  const releaseOnAll = awaiting.reduce((sum, i) => sum + Number(i.price), 0);

  async function accept(path: string) {
    setBusy(true);
    setError(null);
    try {
      onChange(await api<TaskDTO>(path, { method: "POST" }));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Failed to submit your decision");
    } finally {
      setBusy(false);
      setConfirmingAll(false);
    }
  }

  const tally = [
    accepted ? `${accepted} accepted` : null,
    awaiting.length ? `${awaiting.length} awaiting your review` : null,
    rejected ? `${rejected} rejected` : null,
    refunded ? `${refunded} refunded` : null,
    proRata ? `${proRata} paid pro rata` : null,
  ].filter(Boolean).join(" · ");

  return (
    <section aria-labelledby="items-h" className="space-y-4">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 id="items-h" className="display text-[2rem]">Items</h2>
        <p className="tabular text-sm text-muted">
          {items.length} items × {cr(task.itemPrice)}
          {tally ? <span className="text-dim"> · {tally}</span> : null}
        </p>
      </div>
      <p className="text-sm text-muted">
        Each Item is checked against the contract and settled on its own: an Item that fails its checks, or that
        the agent couldn&apos;t do, is refunded straight away. The rest are inspected by sampling: you check a random
        sample against the sources, and the sample decides the others.
      </p>

      {reviewing && (
        <div className="space-y-3">
          <ReviewWindowNotice deadline={task.reviewDeadline} now={current} subject="items" />
          {canDecide && awaiting.length > 0 && (
            <div className="flex flex-wrap items-center gap-3">
              <Button variant="secondary" disabled={busy} onClick={() => setConfirmingAll(true)}>
                {awaiting.length === 1 ? "Accept the last Item awaiting review" : `Accept all ${awaiting.length} awaiting review`}
              </Button>
              <p className="text-xs text-dim">
                Found a wrong Item? Reject it on the{" "}
                <Link href={`/client/tasks/${task.id}/review`} className="font-medium text-accent hover:underline">reviewer page</Link>.
              </p>
            </div>
          )}
        </div>
      )}

      {error && (
        <p role="alert" className="rounded-lg border border-red/30 bg-red/10 px-4 py-3 text-sm text-red">{error}</p>
      )}

      <ol className="panel divide-y divide-line overflow-hidden p-0">
        {items.slice(0, shown).map((item) => (
          <ItemRow key={item.itemNo} item={item} task={task} canDecide={canDecide} busy={busy}
            onAccept={(n) => accept(`/tasks/${task.id}/items/${n}/accept`)} />
        ))}
      </ol>
      {items.length > shown && (
        <div className="flex justify-center">
          <Button variant="secondary" onClick={() => setShown(items.length)}>
            {`Show all ${items.length} Items (${items.length - shown} more)`}
          </Button>
        </div>
      )}

      <ConfirmDialog
        open={confirmingAll}
        title={awaiting.length === 1 ? "Accept the last Item and pay the builder?" : `Accept ${awaiting.length} Items and pay the builder?`}
        confirmLabel={`Accept and release ${cr(releaseOnAll)}`}
        busy={busy}
        onConfirm={() => accept(`/tasks/${task.id}/accept`)}
        onCancel={() => setConfirmingAll(false)}
      >
        {`The ${cr(releaseOnAll)} held for ${awaiting.length === 1 ? "this Item" : "these Items"} is released to the `
          + "builder, less the platform's 15% fee. Accepted Items can no longer be disputed."}
      </ConfirmDialog>
    </section>
  );
}
