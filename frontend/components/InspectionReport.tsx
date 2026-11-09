import type { OutputSpecDTO, ValidationReportDTO } from "@/lib/types";

/** What each automated check actually verified, in the client's words. */
const RULE_TEXT: Record<string, string> = {
  FORMAT_TEXT_NON_EMPTY: "The result is text, and it isn't empty",
  FORMAT_JSON_PARSEABLE: "The result is valid JSON",
  SCHEMA_MATCH: "It matches the schema in the contract",
  SCHEMA_SKIPPED: "No schema in the contract, so there was no shape to check",
  FORMAT_FILE_HTTPS_URL: "The deliverable is a file at a secure (https) link",
};

function ruleText(rule: string): string {
  return RULE_TEXT[rule] ?? rule.toLowerCase().replace(/_/g, " ");
}

function Mark({ state }: { state: "pass" | "fail" | "yours" }) {
  if (state === "yours") {
    return (
      <span aria-hidden className="grid size-6 shrink-0 place-items-center rounded-full border-2 border-dashed border-amber text-amber">
        <svg viewBox="0 0 12 12" className="size-3">
          <circle cx="6" cy="6" r="1.6" fill="currentColor" />
        </svg>
      </span>
    );
  }
  const pass = state === "pass";
  return (
    <span
      aria-hidden
      className={`grid size-6 shrink-0 place-items-center rounded-full text-ink ${pass ? "bg-green" : "bg-red"}`}
    >
      <svg viewBox="0 0 12 12" className="size-3">
        {pass ? (
          <path d="M2.5 6.2 5 8.6l4.5-5" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
        ) : (
          <path d="M3 3l6 6M9 3 3 9" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
        )}
      </svg>
    </span>
  );
}

/**
 * The platform's inspection of a delivered result, set out as the inspector's sheet: each
 * automated check against the frozen contract, ticked or crossed, and then the one line no machine
 * checks, the acceptance criteria, which is the client's call. It tells a client exactly what
 * "validated" covered before they accept, so accepting is an informed decision.
 */
export function InspectionReport({
  report,
  spec,
  awaitingYou,
}: {
  report: ValidationReportDTO;
  spec: OutputSpecDTO;
  /** True while the client still has to accept or dispute; the last line then asks them. */
  awaitingYou: boolean;
}) {
  const passed = report.verdict === "PASS";
  return (
    <section aria-labelledby="inspection-h" className="docket overflow-hidden">
      <div className="flex flex-wrap items-start justify-between gap-3 px-5 pb-3 pt-5">
        <div>
          <h2 id="inspection-h" className="eyebrow">
            Inspection report
          </h2>
          <p className="mt-1 text-sm text-muted">
            Checked automatically against the contract frozen on this task, before you saw the result.
          </p>
        </div>
        <span
          aria-hidden
          style={{ animationDelay: "350ms" }}
          className={`inspect-stamp stamp ${passed ? "text-green" : "text-red"}`}
        >
          {passed ? "Passed" : "Failed"}
        </span>
        <span className="sr-only">Verdict: {passed ? "passed" : "failed"}.</span>
      </div>
      <div className="docket-tear" />
      <ol className="divide-y divide-line">
        {report.checks.map((c, i) => (
          <li key={`${c.rule}-${i}`} className="flex items-start gap-3 px-5 py-3">
            <Mark state={c.passed ? "pass" : "fail"} />
            <div className="min-w-0 pt-0.5">
              <p className="text-[0.9375rem] font-medium text-fg">
                <span className="sr-only">{c.passed ? "Passed: " : "Failed: "}</span>
                {ruleText(c.rule)}
              </p>
              {c.detail && !c.passed && <p className="mt-0.5 break-words text-sm text-red">{c.detail}</p>}
            </div>
          </li>
        ))}
        <li className="flex items-start gap-3 bg-amber/[0.06] px-5 py-3">
          <Mark state="yours" />
          <div className="min-w-0 pt-0.5">
            <p className="text-[0.9375rem] font-medium text-fg">
              <span className="sr-only">{awaitingYou ? "For you to check: " : "Checked by you: "}</span>
              {awaitingYou
                ? "Does it meet the acceptance criteria? That one is yours to judge."
                : "Acceptance criteria, judged by you when you reviewed it"}
            </p>
            <p className="mt-0.5 text-sm text-muted">
              {spec.acceptanceCriteria
                ? spec.acceptanceCriteria
                : "The contract sets no acceptance criteria, so judge it against your brief."}
            </p>
          </div>
        </li>
      </ol>
    </section>
  );
}
