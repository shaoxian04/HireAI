"use client";

import { useState } from "react";
import type { ReactNode } from "react";
import { api, ApiError } from "@/lib/api";
import { Card, Button } from "@/components/ui";
import type { DisputeOutcomeDTO, RulingCategory, RejectReason } from "@/lib/types";

const CATEGORY_LABEL: Record<RulingCategory, string> = {
  FULFILLED: "Fulfilled — pay the builder",
  PARTIALLY_FULFILLED: "Partially fulfilled — split",
  NOT_FULFILLED: "Not fulfilled — full refund",
};

const REJECT_LABEL: Record<RejectReason, string> = {
  A_MISMATCH: "Output doesn’t match the request",
  B_FACTUAL: "Factually wrong",
  C_INCOMPLETE: "Incomplete",
};

type StageState = "done" | "active" | "pending" | "skipped" | "unavailable";

const DOT: Record<StageState, string> = {
  done: "bg-accent border-accent",
  active: "border-cyan bg-cyan dot-live",
  pending: "border-line bg-transparent",
  skipped: "border-line bg-transparent",
  unavailable: "border-amber bg-amber",
};

const TITLE_COLOR: Record<StageState, string> = {
  done: "text-fg",
  active: "text-cyan",
  pending: "text-dim",
  skipped: "text-dim",
  unavailable: "text-amber",
};

/** One node of the vertical dispute timeline. */
function Stage({
  state,
  title,
  last,
  children,
}: {
  state: StageState;
  title: string;
  last?: boolean;
  children?: ReactNode;
}) {
  return (
    <div className="flex gap-3">
      <div className="flex flex-col items-center">
        <span className={`mt-1 size-2.5 shrink-0 rounded-full border ${DOT[state]}`} />
        {!last && <span className="w-px flex-1 bg-line" />}
      </div>
      <div className={last ? "" : "pb-5"}>
        <p className={`font-mono text-[0.7rem] font-semibold uppercase tracking-wider ${TITLE_COLOR[state]}`}>
          {title}
        </p>
        {children && <div className="mt-1 space-y-1 text-sm text-muted">{children}</div>}
      </div>
    </div>
  );
}

/**
 * Client-facing dispute view: a fixed three-stage vertical timeline
 * (You rejected → Arbitrator → Administrator) that stays visible through the whole
 * lifecycle, plus the Accept/Appeal actions while a proposed ruling awaits the client.
 * Distinct from the read-only DisputeOutcomePanel (builder earnings).
 */
export function DisputeProgressPanel({
  outcome,
  rejectionReason,
  onChange,
}: {
  outcome: DisputeOutcomeDTO;
  rejectionReason: string | null;
  onChange: (next: DisputeOutcomeDTO) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const s = outcome.status;
  const arb = outcome.rulings.find((r) => r.decidedBy === "ARBITRATOR");
  const admin = outcome.rulings.find((r) => r.decidedBy === "ADMINISTRATOR");

  async function act(kind: "accept-ruling" | "appeal") {
    setBusy(true);
    setError(null);
    try {
      const next = await api<DisputeOutcomeDTO>(`/disputes/${outcome.disputeId}/${kind}`, { method: "POST" });
      onChange(next);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Action failed");
    } finally {
      setBusy(false);
    }
  }

  // Stage 2 — arbitrator
  let arbState: StageState;
  let arbTitle: string;
  let arbBody: ReactNode = null;
  if (arb) {
    arbState = "done";
    arbTitle = s === "RULED" ? "Arbitrator proposed" : "Arbitrator ruled";
    arbBody = (
      <>
        <p className="text-fg">{CATEGORY_LABEL[arb.category]}</p>
        {arb.rationale && <p>{arb.rationale}</p>}
      </>
    );
  } else if (s === "OPEN" || s === "ARBITRATING") {
    arbState = "active";
    arbTitle = "Arbitrator reviewing…";
  } else {
    arbState = "unavailable";
    arbTitle = "Arbitrator unavailable";
    arbBody = <p>Timed out or couldn’t be reached — a human administrator is handling it.</p>;
  }

  // Stage 3 — administrator
  let admState: StageState;
  let admTitle: string;
  let admBody: ReactNode = null;
  if (admin) {
    admState = "done";
    admTitle = "Administrator ruled";
    admBody = (
      <>
        <p className="text-fg">{CATEGORY_LABEL[admin.category]}</p>
        {admin.rationale && <p>{admin.rationale}</p>}
      </>
    );
  } else if (s === "ESCALATED") {
    admState = "active";
    admTitle = "Awaiting a human administrator…";
  } else if (s === "RESOLVED") {
    admState = "skipped";
    admTitle = "Administrator";
    admBody = <p className="text-dim">Not needed — you accepted the arbitrator’s ruling.</p>;
  } else {
    admState = "pending";
    admTitle = "Administrator";
    admBody = <p className="text-dim">Only if you appeal the arbitrator’s ruling.</p>;
  }

  const rejectLabel = outcome.reasonCategory ? REJECT_LABEL[outcome.reasonCategory] : "Rejected";

  return (
    <Card className="space-y-4 border-t border-line">
      <div className="flex items-center justify-between gap-3">
        <p className="eyebrow">Dispute</p>
        {s === "RESOLVED" && outcome.effectiveCategory && (
          <span className="font-mono text-xs text-accent">Resolved · {CATEGORY_LABEL[outcome.effectiveCategory]}</span>
        )}
      </div>

      <div>
        <Stage state="done" title="You rejected the result">
          <p className="text-fg">{rejectLabel}</p>
          {rejectionReason && <p>“{rejectionReason}”</p>}
        </Stage>
        <Stage state={arbState} title={arbTitle}>
          {arbBody}
        </Stage>
        <Stage state={admState} title={admTitle} last>
          {admBody}
        </Stage>
      </div>

      {s === "RULED" && (
        <div className="space-y-3 border-t border-line pt-4">
          <p className="text-sm text-muted">
            Escrow is still held. Accept the arbitrator’s proposed ruling, or appeal to a human administrator for
            a final decision.
          </p>
          {error && (
            <p role="alert" className="font-mono text-xs text-red">
              {error}
            </p>
          )}
          <div className="flex gap-2">
            <Button onClick={() => act("accept-ruling")} disabled={busy}>
              Accept ruling ▸
            </Button>
            <Button variant="ghost" onClick={() => act("appeal")} disabled={busy}>
              Appeal to a human
            </Button>
          </div>
        </div>
      )}
    </Card>
  );
}
