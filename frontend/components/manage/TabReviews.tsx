"use client";

import { useEffect, useMemo, useState } from "react";
import { api, ApiError } from "@/lib/api";
import type { BuilderReviewDTO } from "@/lib/types";
import { Button } from "@/components/ui/Button";

interface Props {
  agentId: string;
}

type Filter = "all" | "unanswered";

function Stars({ rating }: { rating: number }) {
  return (
    <span
      className="font-mono text-sm tracking-tight"
      aria-label={`${rating} out of 5 stars`}
    >
      <span aria-hidden className="text-amber">{"★".repeat(rating)}</span>
      <span aria-hidden className="text-line-bright">{"★".repeat(5 - rating)}</span>
    </span>
  );
}

/**
 * The distribution, not just the mean. A 4.0 built from 4s and a 4.0 built from 5s and 2s are very
 * different problems, and the average alone hides which one a builder has.
 */
function Summary({ reviews }: { reviews: BuilderReviewDTO[] }) {
  const total = reviews.length;
  const avg = reviews.reduce((sum, r) => sum + r.rating, 0) / total;
  const unanswered = reviews.filter((r) => !r.builderResponse).length;
  const buckets = [5, 4, 3, 2, 1].map((star) => ({
    star,
    count: reviews.filter((r) => r.rating === star).length,
  }));

  return (
    <section className="grid grid-cols-1 gap-6 rounded-xl border border-line bg-surface p-5 sm:grid-cols-[auto_1fr]">
      <div className="sm:w-40">
        <p className="eyebrow mb-2">Average</p>
        <p className="tabular text-5xl font-extrabold leading-none text-fg">{avg.toFixed(1)}</p>
        {/* Decorative here: these are the *rounded* average, and the exact figure is announced
            by the number above them. */}
        <div className="mt-2" aria-hidden>
          <Stars rating={Math.round(avg)} />
        </div>
        <p className="mt-2 font-mono text-[0.68rem] text-dim">
          {total} review{total === 1 ? "" : "s"}
          {unanswered > 0 && <> · {unanswered} unanswered</>}
        </p>
      </div>

      <div className="space-y-1.5 sm:border-l sm:border-line sm:pl-6">
        {buckets.map(({ star, count }) => (
          <div key={star} className="flex items-center gap-3">
            <span className="w-6 shrink-0 font-mono text-[0.68rem] text-dim">{star}★</span>
            <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-surface-2">
              <div
                className={`h-full ${star >= 4 ? "bg-accent" : star === 3 ? "bg-amber" : "bg-red"}`}
                style={{ width: `${(count / total) * 100}%` }}
              />
            </div>
            <span className="tabular w-5 shrink-0 text-right font-mono text-[0.68rem] text-muted">
              {count}
            </span>
          </div>
        ))}
      </div>
    </section>
  );
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
  const [editing, setEditing] = useState(false);
  const [response, setResponse] = useState(review.builderResponse ?? "");
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
      setEditing(false);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Failed to submit response");
    } finally {
      setSaving(false);
    }
  }

  function cancel() {
    setResponse(review.builderResponse ?? "");
    setError(null);
    setEditing(false);
  }

  return (
    <li className="space-y-3 rounded-xl border border-line bg-surface-2 p-4">
      <div className="flex items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <Stars rating={review.rating} />
          {!review.builderResponse && (
            <span className="rounded border border-amber/40 px-1.5 py-0.5 font-mono text-[0.6rem] uppercase tracking-wider text-amber">
              Needs reply
            </span>
          )}
        </div>
        <span className="tabular shrink-0 font-mono text-[0.65rem] text-dim">
          {new Date(review.createdAt).toLocaleDateString()}
        </span>
      </div>

      {review.reviewText ? (
        <p className="text-sm leading-relaxed text-muted">{review.reviewText}</p>
      ) : (
        <p className="font-mono text-xs text-dim">Rating only — no comment left.</p>
      )}

      {/* Responding is a deliberate act, so it costs a click. An always-open editor per review
          turns a page of feedback into a wall of empty textareas. */}
      {editing ? (
        <div className="space-y-2">
          <textarea
            rows={3}
            autoFocus
            value={response}
            onChange={(e) => setResponse(e.target.value)}
            aria-label="Response"
            placeholder="Reply publicly — clients see this on your storefront."
            className="block w-full rounded-md border border-line bg-surface px-3 py-2 font-mono text-sm text-fg shadow-inner transition placeholder:text-dim focus:border-accent focus:outline-none focus:ring-2 focus:ring-accent/25 disabled:opacity-50"
          />
          {error && (
            <p role="alert" className="font-mono text-xs text-red">
              {error}
            </p>
          )}
          <div className="flex gap-2">
            <Button type="button" onClick={handleRespond} disabled={saving || !response.trim()}>
              {saving ? "Saving…" : "Publish reply ▸"}
            </Button>
            <Button type="button" variant="secondary" onClick={cancel} disabled={saving}>
              Cancel
            </Button>
          </div>
        </div>
      ) : review.builderResponse ? (
        <div className="border-l-2 border-accent/40 pl-3">
          <div className="flex items-center gap-2">
            <p className="font-mono text-[0.6rem] uppercase tracking-wider text-accent">
              Your reply
            </p>
            {/* The domain has always allowed replacing a response; the UI used to hide it forever
                after the first save, stranding builders with a reply they could not correct. */}
            <button
              type="button"
              onClick={() => setEditing(true)}
              className="font-mono text-[0.6rem] uppercase tracking-wider text-dim transition hover:text-accent"
            >
              Edit
            </button>
          </div>
          <p className="mt-1 text-sm leading-relaxed text-muted">{review.builderResponse}</p>
        </div>
      ) : (
        <Button type="button" variant="secondary" onClick={() => setEditing(true)}>
          Reply
        </Button>
      )}
    </li>
  );
}

export function TabReviews({ agentId }: Props) {
  const [reviews, setReviews] = useState<BuilderReviewDTO[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState<Filter>("all");

  useEffect(() => {
    api<BuilderReviewDTO[]>(`/agents/${agentId}/reviews`)
      .then(setReviews)
      .catch((e) => setError(e instanceof ApiError ? e.message : "Failed to load reviews"));
  }, [agentId]);

  function handleUpdated(updated: BuilderReviewDTO) {
    setReviews((prev) => prev?.map((r) => (r.id === updated.id ? updated : r)) ?? null);
  }

  const unansweredCount = useMemo(
    () => reviews?.filter((r) => !r.builderResponse).length ?? 0,
    [reviews],
  );
  // Derived, not stored: answering the last outstanding review would otherwise strand the builder
  // on an empty "needs reply" list with the filter control already gone.
  const activeFilter: Filter = unansweredCount === 0 ? "all" : filter;
  const visible = useMemo(
    () =>
      activeFilter === "unanswered"
        ? (reviews ?? []).filter((r) => !r.builderResponse)
        : reviews ?? [],
    [reviews, activeFilter],
  );

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
    return (
      <div className="rounded-xl border border-dashed border-line px-6 py-10 text-center">
        <p className="text-sm font-semibold text-fg">No reviews yet</p>
        <p className="mx-auto mt-2 max-w-md text-sm leading-relaxed text-muted">
          Reviews are earned, not solicited — only a client who accepted and paid for a task can
          leave one, and each task can be rated once. Complete work and they will start to arrive.
        </p>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <Summary reviews={reviews} />

      {/* Only worth offering once there is actually something to filter out. */}
      {unansweredCount > 0 && unansweredCount < reviews.length && (
        <div className="flex gap-1" role="group" aria-label="Filter reviews">
          {([
            { id: "all" as const, label: `All ${reviews.length}` },
            { id: "unanswered" as const, label: `Needs reply ${unansweredCount}` },
          ]).map((f) => (
            <button
              key={f.id}
              type="button"
              aria-pressed={activeFilter === f.id}
              onClick={() => setFilter(f.id)}
              className={`rounded-md border px-3 py-1.5 font-mono text-[0.68rem] uppercase tracking-wider transition ${
                activeFilter === f.id
                  ? "border-accent/50 bg-accent/10 text-accent"
                  : "border-line text-muted hover:text-fg"
              }`}
            >
              {f.label}
            </button>
          ))}
        </div>
      )}

      <ul className="space-y-3">
        {visible.map((r) => (
          <ReviewItem key={r.id} review={r} agentId={agentId} onUpdated={handleUpdated} />
        ))}
      </ul>
    </div>
  );
}
