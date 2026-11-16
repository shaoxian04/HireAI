"use client";

import { useState } from "react";
import type { RejectionDTO, RejectReason, TaskItemDTO } from "@/lib/types";
import { Badge, Button, Select, Textarea } from "@/components/ui";
import { ResultView } from "@/components/ResultView";
import { REJECT_REASON_LABEL } from "@/components/RejectionPanel";
import { timeLeft } from "@/components/ReviewWindowNotice";
import { SourceDocument } from "@/components/sampling/SourceDocument";
import { standing, type Standing } from "@/lib/sampling";

export interface Judgement {
  passed: boolean;
  reasonCategory?: RejectReason;
  reason?: string;
}

interface Props {
  taskId: string;
  item: TaskItemDTO;
  rejection: RejectionDTO | null;
  /** In the random sample (counts towards the error rate), or an extra check. */
  sampled: boolean;
  canJudge: boolean;
  busy: boolean;
  now: Date;
  onJudge: (itemNo: number, judgement: Judgement) => Promise<void>;
  onAccept: (itemNo: number) => Promise<void>;
}

const STANDING: Record<Standing, { label: string; status: string }> = {
  "to-judge": { label: "To check", status: "PENDING_REVIEW" },
  correct: { label: "Correct", status: "RESOLVED" },
  wrong: { label: "Wrong · refunded", status: "FAILED" },
  "agent-turn": { label: "Agent responding", status: "AWAITING_AGENT_RESPONSE" },
  "your-call": { label: "Your call", status: "AWAITING_CLIENT_DECISION" },
  pending: { label: "Not delivered", status: "CANCELLED" },
};

function at(iso: string): string {
  return new Date(iso).toLocaleString(undefined, { weekday: "short", hour: "2-digit", minute: "2-digit" });
}

/** What became of the reviewer's rejection of this Item (the response path, D17′). */
function RejectionStatus({ item, rejection, now, busy, canAccept, onAccept }: {
  item: TaskItemDTO;
  rejection: RejectionDTO;
  now: Date;
  busy: boolean;
  canAccept: boolean;
  onAccept: () => void;
}) {
  const quoted = (
    <p className="text-xs text-dim">
      You rejected it: {REJECT_REASON_LABEL[rejection.reasonCategory]}
      {rejection.reason ? <span className="text-muted">{` — “${rejection.reason}”`}</span> : null}
    </p>
  );
  if (item.status === "CONCEDED") {
    return (
      <div className="space-y-1 rounded-lg border border-line bg-surface-2 px-4 py-3">
        {quoted}
        <p className="text-sm text-fg">The agent conceded. This Item is refunded and counts as wrong.</p>
      </div>
    );
  }
  if (item.status === "SPEC_VIOLATION") {
    return (
      <div className="space-y-1 rounded-lg border border-line bg-surface-2 px-4 py-3">
        {quoted}
        <p className="text-sm text-fg">The agent&apos;s revision failed the contract&apos;s checks, so the Item is refunded.</p>
      </div>
    );
  }
  if (item.status === "ACCEPTED") {
    return (
      <div className="space-y-1 rounded-lg border border-line bg-surface-2 px-4 py-3">
        {quoted}
        <p className="text-sm text-fg">
          The agent stood by its result and it was accepted{rejection.decisionSilent ? " when your decision window closed" : ""}.
        </p>
      </div>
    );
  }
  if (item.status !== "REJECTED") return null;
  if (rejection.response === "STAND_BY") {
    const deadline = rejection.decisionDeadline;
    return (
      <div className="space-y-3 rounded-lg border border-amber/30 bg-amber/10 px-4 py-3">
        {quoted}
        <p className="text-sm text-fg">
          {rejection.responseSilent
            ? "The agent didn't respond in time, which counts as standing by its result."
            : <>The agent stands by its result: <span className="italic">“{rejection.responseReason ?? "no reason given"}”</span></>}
        </p>
        {deadline && (
          <p className="text-xs text-muted">
            <span className="tabular font-mono font-bold text-amber">{timeLeft(new Date(deadline).getTime() - now.getTime())}</span>
            {` to decide, until ${at(deadline)}. If you don't, the Item is accepted.`}
          </p>
        )}
        <div className="flex flex-wrap items-center gap-3">
          {canAccept && (
            <Button size="sm" variant="secondary" disabled={busy} onClick={onAccept}>Accept the Item</Button>
          )}
          <p className="text-xs text-dim">Arbitration of a single Item isn&apos;t available yet.</p>
        </div>
      </div>
    );
  }
  return (
    <div className="space-y-1 rounded-lg border border-cyan/30 bg-cyan/10 px-4 py-3">
      {quoted}
      <p className="text-sm text-fg">
        {`Waiting for the agent until ${at(rejection.responseDeadline)}: it can `}
        {rejection.revisionAllowed ? "send a revised result, " : ""}
        concede and refund it, or stand by it with a reason. Silence counts as standing by.
      </p>
    </div>
  );
}

/**
 * One Item for the reviewer: its source (the document, or the text input) beside the agent's result,
 * and the judgement. A wrong Item needs a reason the agent can answer.
 */
export function SampleItemCard({ taskId, item, rejection, sampled, canJudge, busy, now, onJudge, onAccept }: Props) {
  const [rejecting, setRejecting] = useState(false);
  const [category, setCategory] = useState<RejectReason>("B_FACTUAL");
  const [reason, setReason] = useState("");
  const s = standing(item, rejection);
  const revised = item.status === "PENDING_REVIEW" && rejection?.response === "REVISE";
  const hasResult = item.resultPayload != null && item.resultPayload !== "";

  async function submitReject() {
    await onJudge(item.itemNo, { passed: false, reasonCategory: category, reason: reason.trim() });
    setRejecting(false);
    setReason("");
  }

  return (
    <li className="space-y-4 px-5 py-5" aria-label={`Item ${item.itemNo}`}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-3">
          <span className="tabular font-mono text-sm font-bold text-fg">#{String(item.itemNo).padStart(3, "0")}</span>
          <Badge status={STANDING[s].status}>{STANDING[s].label}</Badge>
          {!sampled && <span className="text-xs text-dim">Extra check · doesn&apos;t count towards the sample</span>}
        </div>
        {revised && <span className="text-xs font-semibold text-accent">Revised by the agent: check it again</span>}
      </div>

      <div className="grid gap-4 md:grid-cols-2">
        <div className="min-w-0 space-y-2 rounded-lg border border-line bg-surface-2 px-4 py-3">
          <p className="eyebrow">Source</p>
          {item.sourceId
            ? <SourceDocument taskId={taskId} itemNo={item.itemNo} label={item.input} />
            : <p className="whitespace-pre-wrap break-words text-sm text-fg">{item.input}</p>}
        </div>
        <div className="min-w-0 space-y-2 rounded-lg border border-line bg-surface px-4 py-3">
          <p className="eyebrow">Agent&apos;s result</p>
          {hasResult ? <ResultView payload={item.resultPayload as string} />
            : <p className="text-sm text-dim">No result text{item.resultUrl ? " (a file link only)" : ""}.</p>}
        </div>
      </div>

      {rejection && s !== "to-judge" && (
        <RejectionStatus item={item} rejection={rejection} now={now} busy={busy} canAccept={canJudge}
          onAccept={() => onAccept(item.itemNo)} />
      )}

      {canJudge && item.status === "PENDING_REVIEW" && !rejecting && (
        <div className="flex flex-wrap gap-3">
          <Button size="sm" disabled={busy} onClick={() => onJudge(item.itemNo, { passed: true })}
            aria-label={`Item ${item.itemNo} is correct`}>
            Correct
          </Button>
          <Button size="sm" variant="danger" disabled={busy} onClick={() => setRejecting(true)}
            aria-label={`Item ${item.itemNo} is wrong`}>
            Wrong…
          </Button>
        </div>
      )}

      {rejecting && (
        <div className="space-y-3 rounded-lg border border-red/30 bg-red/5 px-4 py-4">
          <label className="block space-y-1">
            <span className="text-xs font-semibold text-muted">What&apos;s wrong</span>
            <Select value={category} onChange={(e) => setCategory(e.target.value as RejectReason)}>
              {(Object.keys(REJECT_REASON_LABEL) as RejectReason[]).map((r) => (
                <option key={r} value={r}>{REJECT_REASON_LABEL[r]}</option>
              ))}
            </Select>
          </label>
          <label className="block space-y-1">
            <span className="text-xs font-semibold text-muted">Tell the agent where (it answers this)</span>
            <Textarea rows={2} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)}
              placeholder="e.g. The total is 1,240.00 on the invoice, not 1,204.00" />
          </label>
          <div className="flex flex-wrap gap-3">
            <Button size="sm" variant="danger" disabled={busy || reason.trim() === ""} onClick={submitReject}>
              Reject Item {item.itemNo}
            </Button>
            <Button size="sm" variant="ghost" disabled={busy} onClick={() => setRejecting(false)}>Cancel</Button>
          </div>
          <p className="text-xs text-dim">
            The agent can revise it once, concede (you are refunded), or stand by it; then you decide.
          </p>
        </div>
      )}
    </li>
  );
}
