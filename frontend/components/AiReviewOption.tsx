"use client";

import { useEffect, useId, useState } from "react";
import { api, ApiError } from "@/lib/api";
import { Button, Input } from "@/components/ui";
import type { AiReviewQuoteDTO } from "@/lib/types";

/** The criteria a client typed, without blanks: what the booking sends and the quote is priced on. */
export function filledCriteria(criteria: string[]): string[] {
  return criteria.map((c) => c.trim()).filter((c) => c.length > 0);
}

type QuoteState =
  | { status: "idle" }
  | { status: "loading" }
  | { status: "ready"; quote: AiReviewQuoteDTO }
  | { status: "error"; message: string };

/**
 * Opting into AI Review on a booking (spec S10, D37): the criteria code can't check, in the client's
 * words, and the fee they would freeze for it. The fee is quoted by the platform from the agent's
 * output type and the number of criteria, and it is offered only when it is at most 10% of the price
 * and the agent's Offer accepts AI Review. `criteria` is null while the option is off.
 *
 * `onQuote` reports the fee to freeze, or null when nothing will be frozen for it (off, still
 * pricing, or not offered), so the page can total the escrow and hold the booking back until priced.
 */
export function AiReviewOption({
  agentId,
  budget,
  offerAccepts,
  criteria,
  onChange,
  onQuote,
}: {
  agentId: string;
  budget: number;
  offerAccepts: boolean;
  criteria: string[] | null;
  onChange: (criteria: string[] | null) => void;
  onQuote: (quote: AiReviewQuoteDTO | null) => void;
}) {
  const toggleId = useId();
  const count = criteria ? Math.max(1, filledCriteria(criteria).length) : 0;
  // What is being priced; a quote answers one key, so a stale answer is never shown as current.
  const key = offerAccepts && count > 0 ? `${agentId}|${budget}|${count}` : null;
  const [answer, setAnswer] = useState<{ key: string; state: QuoteState } | null>(null);
  const quote: QuoteState =
    key === null ? { status: "idle" } : answer?.key === key ? answer.state : { status: "loading" };
  const maxCriteria = quote.status === "ready" ? quote.quote.maxCriteria : 5;

  useEffect(() => {
    onQuote(null); // nothing to freeze until this key is priced
    if (key === null) return;
    let cancelled = false;
    const timer = setTimeout(() => {
      api<AiReviewQuoteDTO>("/tasks/direct/ai-review-quote", {
        method: "POST",
        body: JSON.stringify({ agentId, budget, criteria: count }),
      })
        .then((q) => {
          if (cancelled) return;
          setAnswer({ key, state: { status: "ready", quote: q } });
          onQuote(q.available ? q : null);
        })
        .catch((e) => {
          if (cancelled) return;
          setAnswer({
            key,
            state: { status: "error", message: e instanceof ApiError ? e.message : "Couldn't price the AI Review." },
          });
        });
    }, 250);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
    // onQuote is a setter from the page; re-pricing on its identity would loop.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  if (!offerAccepts) {
    return (
      <p className="rounded-lg border border-dashed border-line px-4 py-3 text-sm text-muted">
        This agent doesn&apos;t accept AI Review, so you&apos;ll judge the result yourself.
      </p>
    );
  }

  const on = criteria !== null;
  const setAt = (i: number, value: string) => onChange((criteria ?? []).map((c, j) => (j === i ? value : c)));

  return (
    <section aria-labelledby={`${toggleId}-h`} className="rounded-lg border border-line bg-surface">
      <label htmlFor={toggleId} className="flex cursor-pointer items-start gap-3 px-4 py-3">
        <input
          id={toggleId}
          type="checkbox"
          className="mt-1 size-4 accent-[var(--color-accent)]"
          checked={on}
          onChange={(e) => onChange(e.target.checked ? [""] : null)}
        />
        <span>
          <span id={`${toggleId}-h`} className="block text-sm font-medium text-fg">
            Add an AI Review
          </span>
          <span className="mt-0.5 block text-sm text-muted">
            A model reads the result against criteria code can&apos;t check, like tone or style, and reports before
            you review. It advises; you still accept or reject.
          </span>
        </span>
      </label>

      {on && (
        <div className="space-y-3 border-t border-line px-4 py-3">
          <ol className="space-y-2">
            {criteria.map((c, i) => (
              <li key={i} className="flex items-center gap-2">
                <span className="tabular w-5 shrink-0 text-right text-xs text-dim">{i + 1}.</span>
                <Input
                  aria-label={`AI Review criterion ${i + 1}`}
                  value={c}
                  maxLength={300}
                  onChange={(e) => setAt(i, e.target.value)}
                  placeholder={i === 0 ? "e.g. The tone is warm and apologetic" : "Another criterion"}
                />
                {criteria.length > 1 && (
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    aria-label={`Remove criterion ${i + 1}`}
                    onClick={() => onChange(criteria.filter((_, j) => j !== i))}
                  >
                    Remove
                  </Button>
                )}
              </li>
            ))}
          </ol>
          {criteria.length < maxCriteria && (
            <Button type="button" variant="secondary" size="sm" onClick={() => onChange([...criteria, ""])}>
              Add a criterion
            </Button>
          )}
          <QuoteLine state={quote} />
        </div>
      )}
    </section>
  );
}

function QuoteLine({ state }: { state: QuoteState }) {
  if (state.status === "loading" || state.status === "idle") {
    return (
      <p role="status" className="text-sm text-muted">
        Pricing the review…
      </p>
    );
  }
  if (state.status === "error") {
    return (
      <p role="alert" className="rounded-md border border-red/30 bg-red/10 px-3 py-2 text-sm text-red">
        {state.message}
      </p>
    );
  }
  const { quote } = state;
  if (!quote.available) {
    return (
      <p role="alert" className="rounded-md border border-amber/40 bg-amber/10 px-3 py-2 text-sm text-amber">
        {quote.reason ?? "AI Review isn't offered on this booking."}
      </p>
    );
  }
  return (
    <p className="text-sm text-muted" aria-live="polite">
      <span className="display tabular text-base text-fg">{`${quote.fee.toFixed(2)} cr`}</span>
      {` fee, frozen with the price (at most ${quote.maxFee.toFixed(2)} cr, 10% of it). It is charged once the review runs, and refunded if it never does.`}
    </p>
  );
}
