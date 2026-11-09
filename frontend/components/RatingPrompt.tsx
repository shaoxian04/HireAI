"use client";

import { useState } from "react";
import { api, ApiError } from "@/lib/api";
import { Button, Field } from "@/components/ui";

interface Props {
  taskId: string;
  /** Called once the rating has been accepted, so the parent can stop offering it. */
  onRated?: () => void;
}

const STARS = [1, 2, 3, 4, 5];

const HINT: Record<number, string> = {
  1: "Conformant but useless to me",
  2: "Needed a lot of rework",
  3: "Fine",
  4: "Good — minor nits",
  5: "Exactly what I asked for",
};

/**
 * The client's star rating of a task they accepted.
 *
 * Deliberately a separate call from accept rather than a field on it. A client forced into a snap
 * judgment to get on with their day mostly just skips it, so the prompt is offered inline for
 * capture rate but can be dismissed and answered later — there is no deadline.
 *
 * The rating is the only thing feeding Satisfaction, so leaving it blank is not a neutral act
 * disguised as one: the agent falls back to the prior rather than to the ceiling.
 */
export function RatingPrompt({ taskId, onRated }: Props) {
  const [rating, setRating] = useState<number | null>(null);
  const [hovered, setHovered] = useState<number | null>(null);
  const [reviewText, setReviewText] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);
  const [dismissed, setDismissed] = useState(false);

  async function submit() {
    if (!rating) return;
    setBusy(true);
    setError(null);
    try {
      await api(`/tasks/${taskId}/review`, {
        method: "POST",
        body: JSON.stringify({ rating, reviewText: reviewText.trim() || null }),
      });
      setDone(true);
      onRated?.();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Failed to save your rating");
    } finally {
      setBusy(false);
    }
  }

  if (done) {
    return (
      <div className="rounded-xl border border-line bg-surface-2 p-4">
        <p className="text-sm text-muted">
          Thanks — your rating is now part of this agent&apos;s public record.
        </p>
      </div>
    );
  }

  if (dismissed) {
    return (
      <button
        type="button"
        onClick={() => setDismissed(false)}
        className="text-xs text-dim underline underline-offset-2 hover:text-muted"
      >
        Rate this task
      </button>
    );
  }

  const shown = hovered ?? rating;

  return (
    <div className="space-y-3 rounded-xl border border-line p-4">
      <div className="flex items-start justify-between gap-3">
        <div>
          <p className="eyebrow mb-1">How was it?</p>
          <p className="text-xs text-dim">Optional — you can leave this any time.</p>
        </div>
        <button
          type="button"
          onClick={() => setDismissed(true)}
          className="text-xs text-dim hover:text-muted"
        >
          Not now
        </button>
      </div>

      <div className="flex items-center gap-2">
        <div role="radiogroup" aria-label="Rating" className="flex gap-1">
          {STARS.map((n) => (
            <button
              key={n}
              type="button"
              role="radio"
              aria-checked={rating === n}
              aria-label={`${n} star${n === 1 ? "" : "s"}`}
              onClick={() => setRating(n)}
              onMouseEnter={() => setHovered(n)}
              onMouseLeave={() => setHovered(null)}
              className={`rounded p-0.5 transition ${
                shown != null && n <= shown ? "scale-110 text-amber" : "text-line-bright hover:text-amber/60"
              }`}
            >
              <svg viewBox="0 0 24 24" className="size-8" aria-hidden>
                <path
                  d="M12 2.8l2.85 5.78 6.38.93-4.62 4.5 1.09 6.35L12 17.36l-5.7 3 1.09-6.35-4.62-4.5 6.38-.93z"
                  fill="currentColor"
                  stroke="currentColor"
                  strokeWidth="1"
                  strokeLinejoin="round"
                />
              </svg>
            </button>
          ))}
        </div>
        {shown != null && <span className="text-xs text-dim">{HINT[shown]}</span>}
      </div>

      {rating != null && (
        <>
          <Field label="Anything worth saying? (optional)">
            <textarea
              value={reviewText}
              onChange={(e) => setReviewText(e.target.value)}
              maxLength={2000}
              rows={3}
              className="w-full resize-y rounded-lg border border-line bg-surface px-3 py-2 text-sm outline-none focus:border-accent"
              placeholder="What worked, what didn't."
            />
          </Field>
          <Button onClick={submit} disabled={busy}>
            {busy ? "Saving…" : "Submit rating"}
          </Button>
        </>
      )}

      {error && (
        <p role="alert" className="text-xs text-red">
          {error}
        </p>
      )}
    </div>
  );
}
