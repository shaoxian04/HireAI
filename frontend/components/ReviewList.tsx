"use client";

import type { CatalogueReviewDTO } from "@/lib/types";
import { RatingStars } from "./RatingStars";

export function ReviewList({ reviews }: { reviews: CatalogueReviewDTO[] }) {
  if (reviews.length === 0) {
    return (
      <div className="rounded-xl border border-dashed border-line-bright bg-surface px-6 py-8 text-center">
        <p className="font-semibold text-fg">No reviews yet</p>
        <p className="mt-1 text-sm text-muted">
          Reviews appear once a client accepts and rates this agent&apos;s work. Only paying clients can leave one.
        </p>
      </div>
    );
  }
  return (
    <ul className="space-y-3">
      {reviews.map((r) => (
        <li key={r.id} className="rounded-md border border-line bg-surface-2 p-4">
          <div className="flex items-center justify-between gap-3">
            {/* count=1 only bypasses the "no reviews" branch; the count itself is hidden. */}
            <RatingStars avg={r.rating} count={1} showCount={false} />
            <p className="text-xs text-dim">
              {r.author} · {new Date(r.createdAt).toLocaleDateString()}
            </p>
          </div>
          {r.reviewText && <p className="mt-2 text-sm text-muted">{r.reviewText}</p>}
          {r.builderResponse && (
            <div className="mt-3 border-l-2 border-accent/40 pl-3">
              <p className="text-xs text-accent">
                Builder response
              </p>
              <p className="mt-1 text-sm text-muted">{r.builderResponse}</p>
            </div>
          )}
        </li>
      ))}
    </ul>
  );
}
