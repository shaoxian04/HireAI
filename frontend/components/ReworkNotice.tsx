import { ruleText } from "@/components/InspectionReport";
import type { ValidationReportDTO } from "@/lib/types";
import { checkDetail } from "@/lib/checkDetail";

/**
 * Shown while a result an automatic check rejected is back with its agent for rework (#98, spec
 * D17): which rework this is, what failed, and why there is nothing to dispute. Renders nothing
 * unless the latest report is a rejection.
 */
export function ReworkNotice({ report }: { report: ValidationReportDTO | null }) {
  if (!report || report.verdict !== "FAIL") return null;
  const failed = report.checks.filter((c) => !c.passed && c.outcome !== "REVIEW");
  const reworkNo = report.rejectedAttempts ?? 1;
  const max = report.maxReworks;
  return (
    <section aria-live="polite" className="rounded-xl border border-l-4 border-line border-l-amber bg-surface p-5">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="text-base font-bold text-amber">Sent back to the agent for rework</h2>
        {max != null && (
          <span className="tabular text-xs font-semibold uppercase tracking-wide text-muted">
            {`Rework ${reworkNo} of ${max}`}
          </span>
        )}
      </div>
      <p className="mt-1.5 text-sm leading-relaxed text-muted">
        {failed.length === 1
          ? "The result failed an automatic check in your contract, so you weren't shown it."
          : "The result failed automatic checks in your contract, so you weren't shown it."}{" "} The agent has been told exactly what failed and is fixing it.
      </p>
      {failed.length > 0 && (
        <ul className="mt-3 space-y-1.5 rounded-md border border-line bg-surface-2 p-3 text-sm">
          {failed.map((c, i) => (
            <li key={`${c.rule}-${i}`}>
              <span className="font-medium text-fg">{ruleText(c.rule)}</span>
              {c.detail && <span className="block break-words text-red">{checkDetail(c.detail)}</span>}
            </li>
          ))}
        </ul>
      )}
      <p className="mt-3 text-xs text-dim">
        An automatic check isn&apos;t something to dispute: if the result still fails after the last rework, the task
        ends and your credits are refunded in full.
      </p>
    </section>
  );
}
