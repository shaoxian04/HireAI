"use client";

import { useState } from "react";
import { api, ApiError } from "@/lib/api";
import type { TaskDTO, RejectReason } from "@/lib/types";
import { Button, ConfirmDialog, Field } from "@/components/ui";
import { ReviewWindowNotice, reviewWindowClosed, useNow } from "@/components/ReviewWindowNotice";

interface Props {
  taskId: string;
  /** The credits held in escrow for this task, named in the accept confirmation. */
  budget?: number;
  /** When the review window closes (#94); after it, the platform accepts on the client's behalf. */
  reviewDeadline?: string | null;
  /** Fixes the current time (tests). */
  now?: Date;
  /** Called with the RESOLVED task returned by the backend. */
  onResolved: (task: TaskDTO) => void;
}

const REJECT_REASONS: { value: RejectReason; label: string }[] = [
  { value: "A_MISMATCH", label: "Doesn't match the spec" },
  { value: "B_FACTUAL", label: "Contains factual errors" },
  { value: "C_INCOMPLETE", label: "Incomplete" },
];

/**
 * Accept/Reject action bar shown under the result panel while the task is PENDING_REVIEW.
 * Accept settles escrow to the builder (net of commission).
 * Reject sends the client's reason (a required category and an optional detail) to the agent,
 * which may revise once, concede (full refund) or stand by its result; only then does the client
 * choose between accepting and AI arbitration (#95).
 */
export function ResultReviewBar({ taskId, budget, reviewDeadline, now, onResolved }: Props) {
  const current = useNow(now);
  const closed = reviewWindowClosed(reviewDeadline, current);
  const [rejecting, setRejecting] = useState(false);
  const [reasonCategory, setReasonCategory] = useState<RejectReason | null>(null);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirmingAccept, setConfirmingAccept] = useState(false);

  async function acceptResult() {
    setBusy(true);
    setError(null);
    try {
      const task = await api<TaskDTO>(`/tasks/${taskId}/accept`, { method: "POST" });
      setConfirmingAccept(false);
      onResolved(task);
    } catch (e) {
      setConfirmingAccept(false);
      setError(e instanceof ApiError ? e.message : "Failed to submit your decision");
    } finally {
      setBusy(false);
    }
  }

  async function submitDispute() {
    if (!reasonCategory) return;
    setBusy(true);
    setError(null);
    try {
      const body: { reasonCategory: RejectReason; reason?: string } = { reasonCategory };
      if (reason.trim()) body.reason = reason.trim();
      const task = await api<TaskDTO>(`/tasks/${taskId}/reject`, {
        method: "POST",
        body: JSON.stringify(body),
      });
      onResolved(task);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Failed to submit your decision");
    } finally {
      setBusy(false);
    }
  }

  return (
    <section aria-label="Review result" className="space-y-3 border-t border-line pt-5">
      <ConfirmDialog
        open={confirmingAccept}
        title="Accept this result and pay the builder?"
        confirmLabel={budget != null ? `Accept and release ${budget} cr` : "Accept and pay"}
        busy={busy}
        onConfirm={acceptResult}
        onCancel={() => setConfirmingAccept(false)}
      >
        {budget != null ? `The ${budget} credits held in escrow are released` : "The credits held in escrow are released"}{" "}
        to the builder, less the platform&apos;s 15% fee. Once you accept, this result can no longer be disputed.
      </ConfirmDialog>
      <p className="eyebrow">Your decision</p>
      <ReviewWindowNotice deadline={reviewDeadline} now={current} />
      {closed ? null : (
        <ReviewActions
          busy={busy}
          error={error}
          rejecting={rejecting}
          reasonCategory={reasonCategory}
          reason={reason}
          onAccept={() => setConfirmingAccept(true)}
          onReject={() => setRejecting(true)}
          onReasonCategory={setReasonCategory}
          onReason={setReason}
          onSubmitDispute={submitDispute}
          onBack={() => {
            setRejecting(false);
            setReasonCategory(null);
            setReason("");
          }}
        />
      )}
    </section>
  );
}

interface ActionsProps {
  busy: boolean;
  error: string | null;
  rejecting: boolean;
  reasonCategory: RejectReason | null;
  reason: string;
  onAccept: () => void;
  onReject: () => void;
  onReasonCategory: (value: RejectReason) => void;
  onReason: (value: string) => void;
  onSubmitDispute: () => void;
  onBack: () => void;
}

/** The accept / reject controls, shown only while the review window is open. */
function ReviewActions({
  busy,
  error,
  rejecting,
  reasonCategory,
  reason,
  onAccept,
  onReject,
  onReasonCategory,
  onReason,
  onSubmitDispute,
  onBack,
}: ActionsProps) {
  return (
    <>
      <p className="text-sm text-muted">
        Accepting pays the builder out of escrow. Rejecting sends your reason to the agent, which
        can revise its result, concede and refund you, or stand by it — then you choose whether to
        accept or ask an AI arbitrator to rule.
      </p>
      {error && (
        <p role="alert" className="text-xs text-red">
          {error}
        </p>
      )}
      {!rejecting ? (
        <div className="flex items-center gap-3">
          <Button onClick={onAccept} disabled={busy}>
            Accept result
          </Button>
          <Button variant="ghost" onClick={onReject} disabled={busy}>
            Reject
          </Button>
        </div>
      ) : (
        <div className="space-y-4">
          <Field label="Reason for rejecting" hint="Required — the agent sees this and responds to it">
            <div className="space-y-2" role="radiogroup">
              {REJECT_REASONS.map(({ value, label }) => (
                <label
                  key={value}
                  className="flex cursor-pointer items-center gap-3 rounded-md border border-line bg-surface px-3 py-2.5 text-sm text-fg hover:border-accent/50"
                >
                  <input
                    type="radio"
                    name="reject-reason-category"
                    value={value}
                    checked={reasonCategory === value}
                    onChange={() => onReasonCategory(value)}
                    className="accent-accent"
                  />
                  {label}
                </label>
              ))}
            </div>
          </Field>
          <Field label="Additional detail (optional)" htmlFor="reject-reason">
            <textarea
              id="reject-reason"
              maxLength={500}
              value={reason}
              onChange={(e) => onReason(e.target.value)}
              rows={2}
              className="w-full rounded-md border border-line-bright bg-surface p-3 text-sm text-fg"
            />
          </Field>
          <div className="flex items-center gap-3">
            <Button variant="danger" onClick={onSubmitDispute} disabled={busy || !reasonCategory}>
              Send rejection
            </Button>
            <Button
              variant="ghost"
              onClick={onBack}
              disabled={busy}
            >
              Back
            </Button>
          </div>
        </div>
      )}
    </>
  );
}
