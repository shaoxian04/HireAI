"use client";

import { useState } from "react";
import type { ReactNode } from "react";
import { api, ApiError } from "@/lib/api";
import { Card, Button, ConfirmDialog } from "@/components/ui";
import type { DisputeFeeDTO, DisputeOutcomeDTO, DisputeSide, RulingCategory, RejectReason } from "@/lib/types";
import { rationaleText } from "@/lib/rulings";
import { reviewWindowClosed, timeLeft, useNow } from "@/components/ReviewWindowNotice";

const CATEGORY_LABEL: Record<RulingCategory, string> = {
  FULFILLED: "Fulfilled — pay the builder",
  PARTIALLY_FULFILLED: "Partially fulfilled — split",
  NOT_FULFILLED: "Not fulfilled — full refund",
};

export const REJECT_LABEL: Record<RejectReason, string> = {
  A_MISMATCH: "Output doesn’t match the request",
  B_FACTUAL: "Factually wrong",
  C_INCOMPLETE: "Incomplete",
};

const SIDE_NAME: Record<DisputeSide, string> = { CLIENT: "the client", BUILDER: "the builder" };

const FEE_LABEL: Record<DisputeFeeDTO["type"], string> = {
  ARBITRATION_COST: "Arbitration cost",
  APPEAL_FEE: "Appeal fee",
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

const cr = (n: number | undefined) => `${(n ?? 0).toFixed(2)} cr`;

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
        <p className={`text-xs font-semibold ${TITLE_COLOR[state]}`}>{title}</p>
        {children && <div className="mt-1 space-y-1 text-sm text-muted">{children}</div>}
      </div>
    </div>
  );
}

/** What one settled fee cost the viewer, and what the platform covered. */
function feeLine(fee: DisputeFeeDTO, sides: DisputeSide[]): string {
  const yours =
    (sides.includes("CLIENT") ? fee.clientCharged : 0) + (sides.includes("BUILDER") ? fee.builderCharged : 0);
  const theirs =
    (sides.includes("CLIENT") ? 0 : fee.clientCharged) + (sides.includes("BUILDER") ? 0 : fee.builderCharged);
  const parts = [yours > 0 ? `You paid ${cr(yours)}` : "You paid nothing"];
  if (theirs > 0) parts.push(`${sides.includes("CLIENT") ? "the builder" : "the client"} paid ${cr(theirs)}`);
  if (fee.platformCovered > 0) parts.push(`platform covered ${cr(fee.platformCovered)}`);
  return parts.join(" · ");
}

/**
 * A dispute as one side sees it — the Client on its task page, or the Builder on its dispute page
 * (#96). A fixed vertical timeline (rejection → Agent stood by → arbitrator → administrator) that
 * stays visible through the whole lifecycle; while the arbitrator's ruling is proposed, the side it
 * goes against may accept it or appeal it once to a human, for a capped Appeal Fee the loser bears.
 * Once settled it says who bore the arbitration cost and any Appeal Fee.
 */
export function DisputeProgressPanel({
  outcome,
  rejectionReason,
  onChange,
  now: fixedNow,
}: {
  outcome: DisputeOutcomeDTO;
  /** Fallback for the rejection text when the outcome does not carry it. */
  rejectionReason?: string | null;
  onChange: (next: DisputeOutcomeDTO) => void;
  now?: Date;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirmAppeal, setConfirmAppeal] = useState(false);
  const now = useNow(fixedNow);

  const s = outcome.status;
  const sides: DisputeSide[] = outcome.yourSides?.length ? outcome.yourSides : ["CLIENT"];
  const builderView = !sides.includes("CLIENT");
  // An outcome without `awaiting` (older API) reads as before #96: the client decides a proposed ruling.
  const awaiting = outcome.awaiting ?? (s === "RULED" ? sides : []);
  const mayAct = sides.some((side) => awaiting.includes(side));
  const arb = outcome.rulings.find((r) => r.decidedBy === "ARBITRATOR");
  const admin = outcome.rulings.find((r) => r.decidedBy === "ADMINISTRATOR");
  const appealedByYou = !!outcome.appealedBy && sides.includes(outcome.appealedBy);
  const otherSide: DisputeSide = builderView ? "CLIENT" : "BUILDER";

  async function act(kind: "accept-ruling" | "appeal") {
    setBusy(true);
    setError(null);
    try {
      const next = await api<DisputeOutcomeDTO>(`/disputes/${outcome.disputeId}/${kind}`, { method: "POST" });
      setConfirmAppeal(false);
      onChange(next);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Action failed");
    } finally {
      setBusy(false);
    }
  }

  // Stage 1 — the rejection
  const reason = outcome.clientReason ?? rejectionReason ?? null;
  const rejectLabel = outcome.reasonCategory ? REJECT_LABEL[outcome.reasonCategory] : "Rejected";

  // Stage 2 — the Agent's answer (D20: it stood by its result, so the client asked for arbitration)
  const stoodBy = outcome.agentResponse === "STAND_BY";

  // Stage 3 — arbitrator
  let arbState: StageState;
  let arbTitle: string;
  let arbBody: ReactNode = null;
  if (arb) {
    arbState = "done";
    arbTitle = s === "RULED" ? "Arbitrator proposed" : "Arbitrator ruled";
    arbBody = (
      <>
        <p className="text-fg">{CATEGORY_LABEL[arb.category]}</p>
        {rationaleText(arb.rationale) && <p>{rationaleText(arb.rationale)}</p>}
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

  // Stage 4 — administrator
  let admState: StageState;
  let admTitle: string;
  let admBody: ReactNode = null;
  const appealNote = outcome.appealedBy ? (
    <p>{appealedByYou ? "You appealed the arbitrator’s ruling." : `Appealed by ${SIDE_NAME[outcome.appealedBy]}.`}</p>
  ) : null;
  if (admin) {
    admState = "done";
    admTitle = "Administrator ruled";
    admBody = (
      <>
        {appealNote}
        <p className="text-fg">{CATEGORY_LABEL[admin.category]}</p>
        {rationaleText(admin.rationale) && <p>{rationaleText(admin.rationale)}</p>}
      </>
    );
  } else if (s === "ESCALATED") {
    admState = "active";
    admTitle = "Awaiting a human administrator…";
    admBody = appealNote;
  } else if (s === "RESOLVED") {
    admState = "skipped";
    admTitle = "Administrator";
    admBody = <p className="text-dim">Not needed — the arbitrator’s ruling was accepted.</p>;
  } else {
    admState = "pending";
    admTitle = "Administrator";
    admBody = <p className="text-dim">Only if the side the ruling goes against appeals it.</p>;
  }

  const deadlineOpen = !!outcome.appealDeadline && !reviewWindowClosed(outcome.appealDeadline, now);
  const fees = outcome.fees ?? [];

  return (
    <Card className="space-y-4 border-t border-line">
      <div className="flex items-center justify-between gap-3">
        <p className="eyebrow">Dispute</p>
        {s === "RESOLVED" && outcome.effectiveCategory && (
          <span className="text-xs text-accent">Resolved · {CATEGORY_LABEL[outcome.effectiveCategory]}</span>
        )}
      </div>

      <div>
        <Stage state="done" title={builderView ? "The client rejected the result" : "You rejected the result"}>
          <p className="text-fg">{rejectLabel}</p>
          {reason && <p>“{reason}”</p>}
        </Stage>
        {stoodBy && (
          <Stage
            state="done"
            title={builderView ? "Your agent stood by its result" : "The agent stood by its result"}
          >
            {outcome.agentSilent ? (
              <p>It did not answer in time, which counts as standing by.</p>
            ) : outcome.agentReason ? (
              <p>“{outcome.agentReason}”</p>
            ) : (
              <p>No reason given.</p>
            )}
          </Stage>
        )}
        <Stage state={arbState} title={arbTitle}>
          {arbBody}
        </Stage>
        <Stage state={admState} title={admTitle} last>
          {admBody}
        </Stage>
      </div>

      {s === "RULED" && (
        <div className="space-y-3 border-t border-line pt-4">
          {outcome.appealDeadline && deadlineOpen && (
            <div
              role="timer"
              aria-label="Time left to accept or appeal"
              className="flex flex-wrap items-baseline gap-x-4 gap-y-1 rounded-lg border border-amber/30 bg-amber/10 px-4 py-3"
            >
              <span className="tabular font-mono text-base font-bold text-amber">
                {timeLeft(new Date(outcome.appealDeadline).getTime() - now.getTime())}
              </span>
              <p className="min-w-0 flex-1 text-sm text-muted">
                {mayAct
                  ? "to accept or appeal. After that the ruling is accepted for you and settles."
                  : "until the ruling settles on its own."}
              </p>
            </div>
          )}
          {mayAct ? (
            <>
              <p className="text-sm text-muted">
                Escrow is still held. Accept the arbitrator’s proposed ruling, or appeal it once to a human
                administrator for a final decision.
              </p>
              <p className="text-sm text-muted">
                Whoever the final ruling goes against bears the {cr(outcome.arbitrationCost)} arbitration cost and,
                after an appeal, the {cr(outcome.appealFee)} appeal fee. Both are capped at 20% of the task’s price;
                the platform covers the rest.
              </p>
              {error && (
                <p role="alert" className="text-xs text-red">
                  {error}
                </p>
              )}
              <div className="flex flex-wrap gap-2">
                <Button onClick={() => act("accept-ruling")} disabled={busy}>
                  Accept ruling
                </Button>
                <Button variant="ghost" onClick={() => setConfirmAppeal(true)} disabled={busy}>
                  Appeal to a human
                </Button>
              </div>
            </>
          ) : (
            <p role="status" className="text-sm text-muted">
              The proposed ruling is in your favour. Waiting for {awaiting.map((a) => SIDE_NAME[a]).join(" and ") || SIDE_NAME[otherSide]} to
              accept it or appeal it to a human administrator; escrow stays held until then.
            </p>
          )}
        </div>
      )}

      {s === "RESOLVED" && fees.length > 0 && (
        <dl className="space-y-2 border-t border-line pt-4 text-sm">
          {fees.map((fee) => (
            <div key={fee.type} className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-0.5">
              <dt className="text-fg">{FEE_LABEL[fee.type]}</dt>
              <dd className="tabular text-muted">{feeLine(fee, sides)}</dd>
            </div>
          ))}
        </dl>
      )}

      <ConfirmDialog
        open={confirmAppeal}
        title="Appeal this ruling to a human administrator?"
        confirmLabel="Appeal"
        busy={busy}
        onConfirm={() => act("appeal")}
        onCancel={() => setConfirmAppeal(false)}
      >
        <p>
          An administrator makes the final decision; a ruling can be appealed only once. If the administrator rules
          against you, you bear the {cr(outcome.appealFee)} appeal fee as well as the {cr(outcome.arbitrationCost)}{" "}
          arbitration cost. If they rule for you, {SIDE_NAME[otherSide]} bears them.
        </p>
        {error && (
          <p role="alert" className="mt-2 text-xs text-red">
            {error}
          </p>
        )}
      </ConfirmDialog>
    </Card>
  );
}
