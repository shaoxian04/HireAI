"use client";

import { useState } from "react";
import { api, ApiError } from "@/lib/api";
import type { TaskDTO } from "@/lib/types";
import { Button } from "@/components/ui";

interface Props {
  taskId: string;
  /** Called with the RESOLVED task returned by the backend. */
  onResolved: (task: TaskDTO) => void;
}

/**
 * Accept/Reject action bar shown under the result panel while the task is RESULT_RECEIVED.
 * Accept settles escrow to the builder (net of commission); Reject expands an optional
 * reason field first, then refunds the full budget.
 */
export function ResultReviewBar({ taskId, onResolved }: Props) {
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function resolve(path: "accept" | "reject") {
    setBusy(true);
    setError(null);
    try {
      const body = path === "reject" && reason.trim() ? { reason: reason.trim() } : undefined;
      const task = await api<TaskDTO>(`/tasks/${taskId}/${path}`, {
        method: "POST",
        ...(body ? { body: JSON.stringify(body) } : {}),
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
        Accepting pays the builder out of escrow (15% platform commission). Rejecting refunds
        your full budget.
      </p>
      {error && (
        <p role="alert" className="font-mono text-xs text-red">
          {error}
        </p>
      )}
      {!rejecting ? (
        <div className="flex items-center gap-3">
          <Button onClick={() => resolve("accept")} disabled={busy}>
            Accept result ▸
          </Button>
          <Button variant="ghost" onClick={() => setRejecting(true)} disabled={busy}>
            Reject
          </Button>
        </div>
      ) : (
        <div className="space-y-3">
          <label
            htmlFor="reject-reason"
            className="font-mono text-xs uppercase tracking-wider text-dim"
          >
            Reason (optional)
          </label>
          <textarea
            id="reject-reason"
            maxLength={500}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            rows={2}
            className="w-full rounded-md border border-line bg-canvas p-3 font-mono text-xs text-fg"
          />
          <div className="flex items-center gap-3">
            <Button variant="danger" onClick={() => resolve("reject")} disabled={busy}>
              Confirm reject
            </Button>
            <Button variant="ghost" onClick={() => setRejecting(false)} disabled={busy}>
              Back
            </Button>
          </div>
        </div>
      )}
    </section>
  );
}
