"use client";

import { useEffect, useState } from "react";
import { api, ApiError } from "@/lib/api";
import type { AiReviewDTO, TaskStatus } from "@/lib/types";

const POLL_MS = 2000;

const STAMP = {
  FULFILLED: { text: "All met", cls: "text-green" },
  PARTIALLY_FULFILLED: { text: "Some met", cls: "text-amber" },
  NOT_FULFILLED: { text: "None met", cls: "text-red" },
} as const;

/** The verdict in one sentence, for the channel the task was booked on. */
function summary(review: AiReviewDTO): string {
  const met = review.verdicts.filter((v) => v.passed).length;
  const of = `${met} of ${review.verdicts.length} ${review.verdicts.length === 1 ? "criterion" : "criteria"} met.`;
  if (review.decisive) {
    return `${of} You booked this through the API, so the verdict settled the task and is final.`;
  }
  return `${of} This is advice for your review: you still decide whether to accept or reject.`;
}

function VerdictMark({ passed }: { passed: boolean }) {
  return (
    <span
      aria-hidden
      className={`grid size-6 shrink-0 place-items-center rounded-full text-ink ${passed ? "bg-green" : "bg-red"}`}
    >
      <svg viewBox="0 0 12 12" className="size-3">
        {passed ? (
          <path d="M2.5 6.2 5 8.6l4.5-5" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
        ) : (
          <path d="M3 3l6 6M9 3 3 9" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
        )}
      </svg>
    </span>
  );
}

/**
 * The AI Review of a task (spec S10): a model's pass or fail on each criterion the client set, with
 * its reasons. On the web it is the pre-review report, shown before the client decides; booked
 * through the API it is the verdict that settled the task. Re-read every few seconds while the review
 * is waiting or running; while it waits it shows the criteria and the frozen fee.
 */
export function AiReviewReport({
  taskId,
  taskStatus,
  frozen,
}: {
  taskId: string;
  taskStatus: TaskStatus;
  /** The criteria and fee frozen in the task's Contract, shown until the review itself loads. */
  frozen: { criteria: string[]; fee: number };
}) {
  const [review, setReview] = useState<AiReviewDTO | null>(null);
  const [failed, setFailed] = useState(false);
  const waiting = !review || review.status === "FROZEN" || review.status === "REQUESTED";

  useEffect(() => {
    let cancelled = false;
    const load = () =>
      api<AiReviewDTO>(`/tasks/${taskId}/ai-review`)
        .then((r) => {
          if (cancelled) return;
          setReview(r);
          setFailed(false);
        })
        .catch((e) => {
          if (!cancelled && !(e instanceof ApiError && e.status === 404)) setFailed(true);
        });
    load();
    const t = waiting ? setInterval(load, POLL_MS) : undefined;
    return () => {
      cancelled = true;
      if (t) clearInterval(t);
    };
  }, [taskId, taskStatus, waiting]);

  const status = review?.status ?? "FROZEN";
  const completed = review?.status === "COMPLETED" && review.rulingCategory;
  const stamp = completed ? STAMP[review.rulingCategory!] : null;
  const fee = (review?.fee ?? frozen.fee).toFixed(2);

  return (
    <section aria-labelledby="ai-review-h" aria-busy={status === "REQUESTED"} className="docket overflow-hidden">
      <div className="flex items-start justify-between gap-3 px-5 pb-3 pt-5">
        <div className="min-w-0 flex-1">
          <h2 id="ai-review-h" className="eyebrow">
            {review?.decisive ? "AI Review verdict" : "AI pre-review report"}
          </h2>
          <p className="mt-1 text-sm text-muted">
            {completed
              ? summary(review)
              : "A model judges the result against the criteria you set, once it passes the automatic checks."}
          </p>
        </div>
        {stamp && (
          <>
            <span aria-hidden style={{ animationDelay: "350ms" }} className={`inspect-stamp stamp shrink-0 ${stamp.cls}`}>
              {stamp.text}
            </span>
            <span className="sr-only">AI Review: {stamp.text}.</span>
          </>
        )}
      </div>
      <div className="docket-tear" />

      {completed ? (
        <ol className="divide-y divide-line">
          {review.verdicts.map((v, i) => (
            <li key={`${v.criterion}-${i}`} className="flex items-start gap-3 px-5 py-3">
              <VerdictMark passed={v.passed} />
              <div className="min-w-0 pt-0.5">
                <p className="text-[0.9375rem] font-medium text-fg">
                  <span className="sr-only">{v.passed ? "Met: " : "Not met: "}</span>
                  {v.criterion}
                </p>
                <p className={`mt-0.5 break-words text-sm ${v.passed ? "text-muted" : "text-red"}`}>{v.reason}</p>
              </div>
            </li>
          ))}
        </ol>
      ) : (
        <ol className="divide-y divide-line">
          {(review?.criteria ?? frozen.criteria).map((c, i) => (
            <li key={`${c}-${i}`} className="flex items-start gap-3 px-5 py-3">
              <span aria-hidden className="grid size-6 shrink-0 place-items-center rounded-full border-2 border-dashed border-line text-dim">
                <span className="tabular text-[0.625rem]">{i + 1}</span>
              </span>
              <p className="min-w-0 pt-0.5 text-[0.9375rem] text-fg">{c}</p>
            </li>
          ))}
        </ol>
      )}

      <div className="border-t border-line px-5 py-3 text-sm">
        {failed && (
          <p role="alert" className="text-red">
            Couldn&apos;t load the AI Review. It is retried every few seconds.
          </p>
        )}
        {!failed && status === "FROZEN" && (
          <p className="text-muted">
            {`Waiting for a result that passes the automatic checks. The ${fee} cr fee is frozen with the price and refunded if no result is ever reviewed.`}
          </p>
        )}
        {!failed && status === "REQUESTED" && (
          <p role="status" className="flex items-center gap-2 text-muted">
            <span aria-hidden className="size-2 shrink-0 animate-pulse rounded-full bg-accent" />
            The reviewer is reading the result. This usually takes under a minute.
          </p>
        )}
        {!failed && status === "COMPLETED" && (
          <p className="text-xs text-dim">
            {`Fee ${fee} cr, charged for the review${review?.model ? `, by ${review.model}` : ""}. A model can be wrong: read the result yourself before you decide.`}
          </p>
        )}
        {!failed && status === "FAILED" && (
          <p className="text-amber">
            {review?.decisive
              ? `The AI Review couldn't give a usable verdict, so nothing was settled and the ${fee} cr fee was refunded. The result now waits for your review: accept or reject it yourself.`
              : `The AI Review couldn't give a usable verdict, so there is no report and the ${fee} cr fee was refunded. Judge the result yourself.`}
          </p>
        )}
        {!failed && status === "RELEASED" && (
          <p className="text-muted">{`No result was reviewed, so the ${fee} cr fee was refunded.`}</p>
        )}
      </div>
    </section>
  );
}
