"use client";

import { useState } from "react";
import { api, ApiError } from "@/lib/api";
import type { RejectionDTO, RejectReason, TaskDTO } from "@/lib/types";
import { Button, ConfirmDialog } from "@/components/ui";
import { reviewWindowClosed, timeLeft, useNow } from "@/components/ReviewWindowNotice";

/** How each A/B/C reason reads back to the client. */
export const REJECT_REASON_LABEL: Record<RejectReason, string> = {
  A_MISMATCH: "Doesn't match the spec",
  B_FACTUAL: "Contains factual errors",
  C_INCOMPLETE: "Incomplete",
};

function at(iso: string): string {
  return new Date(iso).toLocaleString(undefined, { weekday: "short", hour: "2-digit", minute: "2-digit" });
}

/** The client's own rejection, quoted back. */
function YourRejection({ rejection }: { rejection: RejectionDTO }) {
  return (
    <div className="rounded-lg border border-line bg-surface-2 px-4 py-3">
      <p className="text-xs text-dim">You rejected it: {REJECT_REASON_LABEL[rejection.reasonCategory]}</p>
      {rejection.reason && <p className="mt-1 text-sm text-fg">“{rejection.reason}”</p>}
    </div>
  );
}

/** A countdown chip plus the sentence it belongs to. */
function Countdown({ deadline, now, label, children }: { deadline: string; now: Date; label: string; children: React.ReactNode }) {
  return (
    <div
      role="timer"
      aria-label={label}
      className="flex flex-wrap items-baseline gap-x-4 gap-y-1 rounded-lg border border-amber/30 bg-amber/10 px-4 py-3"
    >
      <span className="tabular font-mono text-base font-bold text-amber">
        {timeLeft(new Date(deadline).getTime() - now.getTime())}
      </span>
      <p className="min-w-0 flex-1 text-sm text-muted">{children}</p>
    </div>
  );
}

/** A window that has closed; the platform is applying what silence means. */
function Closed({ children }: { children: React.ReactNode }) {
  return (
    <div role="status" className="flex items-start gap-3 rounded-lg border border-line bg-surface-2 px-4 py-3">
      <span aria-hidden className="mt-1.5 size-2 shrink-0 animate-pulse rounded-full bg-accent" />
      <p className="text-sm text-muted">{children}</p>
    </div>
  );
}

interface WaitingProps {
  rejection: RejectionDTO;
  now?: Date;
}

/**
 * AWAITING_AGENT_RESPONSE (#95): the client rejected for A/B/C and the agent now has its response
 * window to revise (once), concede or stand by. Nothing is for the client to do yet.
 */
export function AgentResponsePending({ rejection, now }: WaitingProps) {
  const current = useNow(now);
  const closed = reviewWindowClosed(rejection.responseDeadline, current);
  return (
    <section aria-labelledby="agent-response-h" className="space-y-3 border-t border-line pt-5">
      <h3 id="agent-response-h" className="eyebrow">Waiting for the agent</h3>
      <YourRejection rejection={rejection} />
      {closed ? (
        <Closed>
          <span className="font-semibold text-fg">The agent didn&apos;t respond in time.</span> That counts as standing by
          its result; you&apos;ll be asked to accept it or request arbitration. This page updates on its own.
        </Closed>
      ) : (
        <Countdown deadline={rejection.responseDeadline} now={current} label="Time left for the agent to respond">
          for the agent to respond, until <time dateTime={rejection.responseDeadline}>{at(rejection.responseDeadline)}</time>.
          It can {rejection.revisionAllowed ? "send a revised result, " : ""}concede and refund you in full, or stand by
          its result with a reason. If it doesn&apos;t respond, it is treated as standing by.
        </Countdown>
      )}
      {!rejection.revisionAllowed && (
        <p className="text-xs text-dim">The agent already revised this result once, so it can&apos;t revise again.</p>
      )}
      <p className="text-xs text-dim">Your credits stay in escrow until this is settled.</p>
    </section>
  );
}

interface DecisionProps {
  taskId: string;
  budget?: number;
  rejection: RejectionDTO;
  now?: Date;
  /** Called with the task returned by the backend after the client decides. */
  onDecided: (task: TaskDTO) => void;
}

/**
 * AWAITING_CLIENT_DECISION (#95): the agent stood by its result (or stayed silent). The client sees
 * its reason and accepts, or requests AI arbitration; silence until the deadline counts as acceptance.
 */
export function ClientDecisionPanel({ taskId, budget, rejection, now, onDecided }: DecisionProps) {
  const current = useNow(now);
  const deadline = rejection.decisionDeadline ?? null;
  const closed = reviewWindowClosed(deadline, current);
  const [confirming, setConfirming] = useState<"accept" | "arbitrate" | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function decide(kind: "accept" | "arbitrate") {
    setBusy(true);
    setError(null);
    try {
      const path = kind === "accept" ? `/tasks/${taskId}/accept` : `/tasks/${taskId}/arbitration`;
      const task = await api<TaskDTO>(path, { method: "POST" });
      setConfirming(null);
      onDecided(task);
    } catch (e) {
      setConfirming(null);
      setError(e instanceof ApiError ? e.message : "Failed to submit your decision");
    } finally {
      setBusy(false);
    }
  }

  return (
    <section aria-labelledby="decision-h" className="space-y-3 border-t border-line pt-5">
      <ConfirmDialog
        open={confirming === "accept"}
        title="Accept this result and pay the builder?"
        confirmLabel={budget != null ? `Accept and release ${budget} cr` : "Accept and pay"}
        busy={busy}
        onConfirm={() => decide("accept")}
        onCancel={() => setConfirming(null)}
      >
        The credits held in escrow are released to the builder, less the platform&apos;s 15% fee. The result can no
        longer be disputed.
      </ConfirmDialog>
      <ConfirmDialog
        open={confirming === "arbitrate"}
        title="Ask an AI arbitrator to rule?"
        confirmLabel="Request arbitration"
        busy={busy}
        onConfirm={() => decide("arbitrate")}
        onCancel={() => setConfirming(null)}
      >
        An arbitrator reviews the result against the contract, your rejection and the agent&apos;s reason. It may refund
        you in full, split the credits, or uphold payment to the builder. No deposit is taken: your credits simply stay in
        escrow until the ruling.
      </ConfirmDialog>

      <h3 id="decision-h" className="eyebrow">Your decision</h3>
      <YourRejection rejection={rejection} />
      <div className="rounded-lg border border-line-bright bg-surface px-4 py-3">
        <p className="text-xs text-dim">The agent stands by its result</p>
        {rejection.responseSilent || !rejection.responseReason ? (
          <p className="mt-1 text-sm text-muted">
            {rejection.responseSilent
              ? "It didn't respond within its window, which counts as standing by — it gave no reason."
              : "It gave no reason."}
          </p>
        ) : (
          <p className="mt-1 text-sm text-fg">“{rejection.responseReason}”</p>
        )}
      </div>
      {deadline && closed ? (
        <Closed>
          <span className="font-semibold text-fg">The decision window has closed.</span> The result is being accepted on
          your behalf and the builder paid. This page updates when it settles.
        </Closed>
      ) : (
        <>
          {deadline && (
            <Countdown deadline={deadline} now={current} label="Time left to decide">
              to decide, until <time dateTime={deadline}>{at(deadline)}</time>. If you don&apos;t decide by then, the
              result is accepted and the builder is paid.
            </Countdown>
          )}
          {error && (
            <p role="alert" className="text-xs text-red">
              {error}
            </p>
          )}
          <div className="flex flex-wrap items-center gap-3">
            <Button onClick={() => setConfirming("accept")} disabled={busy}>
              Accept result
            </Button>
            <Button variant="danger" onClick={() => setConfirming("arbitrate")} disabled={busy}>
              Request AI arbitration
            </Button>
          </div>
        </>
      )}
    </section>
  );
}

/** PENDING_REVIEW after a revision: say that this is the agent's second try, and the last. */
export function RevisedResultNotice({ rejection }: { rejection: RejectionDTO }) {
  return (
    <div role="note" className="rounded-lg border border-violet/30 bg-violet/10 px-4 py-3 text-sm text-muted">
      <span className="font-semibold text-violet">Revised result.</span> You rejected the first one (
      {REJECT_REASON_LABEL[rejection.reasonCategory].toLowerCase()}) and the agent sent this revision. Review it again:
      if you reject it, the agent can concede or stand by, but not revise a second time.
    </div>
  );
}
