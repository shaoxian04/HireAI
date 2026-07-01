"use client";

import { useState } from "react";
import { api, ApiError } from "@/lib/api";
import type { TaskDTO, RejectReason } from "@/lib/types";
import { Button, Field } from "@/components/ui";

interface Props {
  taskId: string;
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
 * Reject opens a dispute: the client picks a required reason category and an optional detail;
 * an arbitrator reviews the dispute and issues a ruling (refund, split, or uphold payment).
 */
export function ResultReviewBar({ taskId, onResolved }: Props) {
  const [rejecting, setRejecting] = useState(false);
  const [reasonCategory, setReasonCategory] = useState<RejectReason | null>(null);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function acceptResult() {
    setBusy(true);
    setError(null);
    try {
      const task = await api<TaskDTO>(`/tasks/${taskId}/accept`, { method: "POST" });
      onResolved(task);
    } catch (e) {
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
      <p className="eyebrow">Your decision</p>
      <p className="text-sm text-muted">
        Accepting pays the builder out of escrow (15% platform commission). Rejecting opens a
        dispute that an arbitrator will review — the outcome may be a full refund, a partial
        settlement, or upholding payment to the builder.
      </p>
      {error && (
        <p role="alert" className="font-mono text-xs text-red">
          {error}
        </p>
      )}
      {!rejecting ? (
        <div className="flex items-center gap-3">
          <Button onClick={acceptResult} disabled={busy}>
            Accept result ▸
          </Button>
          <Button variant="ghost" onClick={() => setRejecting(true)} disabled={busy}>
            Reject
          </Button>
        </div>
      ) : (
        <div className="space-y-4">
          <Field label="Dispute reason" hint="Required — an arbitrator will review this">
            <div className="space-y-2" role="radiogroup">
              {REJECT_REASONS.map(({ value, label }) => (
                <label
                  key={value}
                  className="flex cursor-pointer items-center gap-3 rounded-md border border-line bg-canvas px-3 py-2.5 font-mono text-xs text-fg hover:border-line-bright"
                >
                  <input
                    type="radio"
                    name="reject-reason-category"
                    value={value}
                    checked={reasonCategory === value}
                    onChange={() => setReasonCategory(value)}
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
              onChange={(e) => setReason(e.target.value)}
              rows={2}
              className="w-full rounded-md border border-line bg-canvas p-3 font-mono text-xs text-fg"
            />
          </Field>
          <div className="flex items-center gap-3">
            <Button variant="danger" onClick={submitDispute} disabled={busy || !reasonCategory}>
              Submit dispute
            </Button>
            <Button
              variant="ghost"
              onClick={() => {
                setRejecting(false);
                setReasonCategory(null);
                setReason("");
              }}
              disabled={busy}
            >
              Back
            </Button>
          </div>
        </div>
      )}
    </section>
  );
}
