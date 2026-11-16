import type { TaskItemDTO } from "@/lib/types";
import { findingText, ruleText } from "@/components/InspectionReport";

/** The checks that sent an Item to the buyer's review (outcome REVIEW, #106 / D7). */
export function reviewChecks(item: TaskItemDTO) {
  return (item.checks ?? []).filter((c) => c.outcome === "REVIEW");
}

/**
 * What the platform and the agent said about one Item besides pass or fail (#106, spec D7): the checks that
 * could not decide and so need your own look, what the agent reported about the source, and Findings about
 * the input (duplicates, figures that don't add up), which never change the pay.
 */
export function ItemNotes({ item }: { item: TaskItemDTO }) {
  const review = reviewChecks(item);
  // An anomaly already quoted in the check it sent to review ("The Agent reported: …") is not repeated.
  const anomalies = (item.anomalies ?? []).filter((a) => !review.some((c) => c.detail?.includes(a.description)));
  const findings = item.findings ?? [];
  if (review.length === 0 && anomalies.length === 0 && findings.length === 0) return null;
  return (
    <div className="space-y-3">
      {review.length > 0 && (
        <div aria-label={`Why Item ${item.itemNo} needs your review`}
          className="space-y-1.5 rounded-lg border border-amber/30 bg-amber/10 px-4 py-3">
          <p className="eyebrow text-amber">Sent to your review</p>
          {review.map((c) => (
            <p key={c.rule} className="text-sm text-fg">
              <span className="font-medium">{ruleText(c.rule)}</span>
              {c.detail ? <span className="text-muted">{` — ${c.detail}`}</span> : null}
            </p>
          ))}
          <p className="text-xs text-dim">
            {item.status === "PENDING_REVIEW"
              ? "This Item is outside the random sample: it is paid only if you confirm it against the document. If nobody checks it before the review window closes, it is refunded."
              : "This Item was held for your own check, outside the random sample."}
          </p>
        </div>
      )}
      {anomalies.length > 0 && (
        <div className="space-y-1.5 rounded-lg border border-line bg-surface-2 px-4 py-3">
          <p className="eyebrow">The agent reported</p>
          {anomalies.map((a, i) => (
            <p key={i} className="text-sm text-fg">
              {a.check && <span className="mr-1.5 text-muted">{`${ruleText(a.check)}: `}</span>}
              {`“${a.description}”`}
            </p>
          ))}
        </div>
      )}
      {findings.length > 0 && (
        <div aria-label={`Findings about Item ${item.itemNo}`} className="space-y-1.5 rounded-lg border border-line bg-surface-2 px-4 py-3">
          <p className="eyebrow">Findings</p>
          {findings.map((f, i) => (
            <div key={i} className="text-sm">
              <p className="font-medium text-fg">{findingText(f.code)}</p>
              {f.detail && <p className="mt-0.5 break-words text-muted">{f.detail}</p>}
            </div>
          ))}
          <p className="text-xs text-dim">For your information only: a Finding never changes what the agent is paid.</p>
        </div>
      )}
    </div>
  );
}
