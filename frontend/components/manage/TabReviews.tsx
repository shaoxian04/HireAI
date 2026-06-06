"use client";

import { useEffect, useState } from "react";
import { api, ApiError } from "@/lib/api";
import type { BuilderReviewDTO } from "@/lib/types";
import { RatingStars } from "@/components/RatingStars";
import { Button } from "@/components/ui/Button";

interface Props {
  agentId: string;
}

function ReviewItem({
  review,
  agentId,
  onUpdated,
}: {
  review: BuilderReviewDTO;
  agentId: string;
  onUpdated: (r: BuilderReviewDTO) => void;
}) {
  const [response, setResponse] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleRespond() {
    setSaving(true);
    setError(null);
    try {
      const updated = await api<BuilderReviewDTO>(
        `/agents/${agentId}/reviews/${review.id}/response`,
        { method: "PUT", body: JSON.stringify({ response }) },
      );
      onUpdated(updated);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Failed to submit response");
    } finally {
      setSaving(false);
    }
  }

  return (
    <li className="rounded-md border border-line bg-surface-2 p-4 space-y-3">
      <div className="flex items-center justify-between gap-3">
        <RatingStars avg={review.rating} count={1} showCount={false} />
        <span className="font-mono text-[0.65rem] text-dim">
          {new Date(review.createdAt).toLocaleDateString()}
        </span>
      </div>

      {review.reviewText && (
        <p className="text-sm text-muted">{review.reviewText}</p>
      )}

      {review.builderResponse ? (
        <div className="border-l-2 border-accent/40 pl-3">
          <p className="font-mono text-[0.6rem] uppercase tracking-wider text-accent">
            Your response
          </p>
          <p className="mt-1 text-sm text-muted">{review.builderResponse}</p>
        </div>
      ) : (
        <div className="space-y-2">
          <textarea
            rows={3}
            value={response}
            onChange={(e) => setResponse(e.target.value)}
            aria-label="Response"
            placeholder="Write a response…"
            className="block w-full rounded-md border border-line bg-surface px-3 py-2 font-mono text-sm text-fg shadow-inner transition placeholder:text-dim focus:border-accent focus:outline-none focus:ring-2 focus:ring-accent/25 disabled:opacity-50"
          />
          {error && (
            <p role="alert" className="font-mono text-xs text-red">
              {error}
            </p>
          )}
          <Button
            type="button"
            onClick={handleRespond}
            disabled={saving || !response.trim()}
          >
            {saving ? "Saving…" : "Respond ▸"}
          </Button>
        </div>
      )}
    </li>
  );
}

export function TabReviews({ agentId }: Props) {
  const [reviews, setReviews] = useState<BuilderReviewDTO[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api<BuilderReviewDTO[]>(`/agents/${agentId}/reviews`)
      .then(setReviews)
      .catch((e) => setError(e instanceof ApiError ? e.message : "Failed to load reviews"));
  }, [agentId]);

  function handleUpdated(updated: BuilderReviewDTO) {
    setReviews((prev) => prev?.map((r) => (r.id === updated.id ? updated : r)) ?? null);
  }

  if (error) {
    return (
      <p role="alert" className="font-mono text-xs text-red">
        {error}
      </p>
    );
  }

  if (!reviews) {
    return <p className="font-mono text-sm text-dim">Loading…</p>;
  }

  if (reviews.length === 0) {
    return <p className="font-mono text-xs text-dim">No reviews yet.</p>;
  }

  return (
    <ul className="space-y-4">
      {reviews.map((r) => (
        <ReviewItem key={r.id} review={r} agentId={agentId} onUpdated={handleUpdated} />
      ))}
    </ul>
  );
}
