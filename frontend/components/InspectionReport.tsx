import type { OutputSpecDTO, ValidationCheckDTO, ValidationReportDTO } from "@/lib/types";

/** What each automated check actually verified, in the client's words. */
const RULE_TEXT: Record<string, string> = {
  FORMAT_TEXT_NON_EMPTY: "The result is text, and it isn't empty",
  TEXT_MIN_LENGTH: "The text is at least as long as the contract asks",
  TEXT_MAX_LENGTH: "The text is no longer than the contract allows",
  FORMAT_JSON_PARSEABLE: "The result is valid JSON",
  SCHEMA_MATCH: "It matches the schema in the contract",
  SCHEMA_SKIPPED: "No schema in the contract, so there was no shape to check",
  FORMAT_FILE_HTTPS_URL: "The deliverable is a file at a secure (https) link",
  FILE_FETCHED: "The platform fetched the file and froze a copy of it",
  FILE_MEDIA_TYPE: "The file is a type the contract accepts",
  FILE_MAX_BYTES: "The file is no larger than the contract allows",
  IMAGE_FORMAT: "The image is a format the contract accepts",
  IMAGE_DIMENSIONS: "The image is exactly the size the contract asks",
  IMAGE_BACKGROUND_COLOUR: "The background is the colour the contract asks",
  SELF_REPORTED_ANOMALY: "The agent flagged something about your brief",
  INVOICE_JSON: "The result is one JSON object with the invoice's fields",
  INVOICE_FIELDS: "Every invoice field is there and well formed",
  INVOICE_LINE_ARITHMETIC: "Each line's quantity × unit price is its amount",
  INVOICE_LINES_SUM: "The lines add up to the subtotal",
  INVOICE_TAX_ARITHMETIC: "The tax total matches the lines' tax or the tax rate",
  INVOICE_TOTAL: "Subtotal plus tax is the grand total",
};

/** Findings, in the client's words. */
const FINDING_TEXT: Record<string, string> = {
  DUPLICATE_BRIEF: "You have sent this exact brief before",
  DUPLICATE_DOCUMENT: "You sent this very file before",
  DUPLICATE_INVOICE: "This invoice number from this supplier was extracted before",
  INVOICE_ARITHMETIC: "The invoice's figures don't add up",
};

/** A check's short name, for "failed the … check". */
const CHECK_NAME: Record<string, string> = {
  FORMAT_TEXT_NON_EMPTY: "non-empty text",
  TEXT_MIN_LENGTH: "minimum length",
  TEXT_MAX_LENGTH: "maximum length",
  FORMAT_JSON_PARSEABLE: "valid JSON",
  SCHEMA_MATCH: "schema",
  FORMAT_FILE_HTTPS_URL: "secure link",
  FILE_FETCHED: "file fetch",
  FILE_MEDIA_TYPE: "file type",
  FILE_MAX_BYTES: "file size",
  IMAGE_FORMAT: "image format",
  IMAGE_DIMENSIONS: "image size",
  IMAGE_BACKGROUND_COLOUR: "background colour",
  INVOICE_JSON: "invoice JSON",
  INVOICE_FIELDS: "invoice fields",
  INVOICE_LINE_ARITHMETIC: "line arithmetic",
  INVOICE_LINES_SUM: "lines sum",
  INVOICE_TAX_ARITHMETIC: "tax arithmetic",
  INVOICE_TOTAL: "grand total",
};

export function checkName(rule: string): string {
  return CHECK_NAME[rule] ?? rule.toLowerCase().replace(/_/g, " ");
}

/** A check rule in plain words (unknown rules fall back to their name). */
/** A Finding's headline, in the client's words. */
export function findingText(code: string): string {
  return FINDING_TEXT[code] ?? ruleText(code);
}

export function ruleText(rule: string): string {
  return RULE_TEXT[rule] ?? rule.toLowerCase().replace(/_/g, " ");
}

type MarkState = "pass" | "fail" | "review" | "yours";

/** A check's state: passed, rejected, or left to the client because it could not decide. */
export function checkState(c: ValidationCheckDTO): "pass" | "fail" | "review" {
  if (c.passed) return "pass";
  return c.outcome === "REVIEW" ? "review" : "fail";
}

function Mark({ state }: { state: MarkState }) {
  if (state === "yours") {
    return (
      <span aria-hidden className="grid size-6 shrink-0 place-items-center rounded-full border-2 border-dashed border-amber text-amber">
        <svg viewBox="0 0 12 12" className="size-3">
          <circle cx="6" cy="6" r="1.6" fill="currentColor" />
        </svg>
      </span>
    );
  }
  if (state === "review") {
    return (
      <span aria-hidden className="grid size-6 shrink-0 place-items-center rounded-full bg-amber text-ink">
        <svg viewBox="0 0 12 12" className="size-3">
          <path d="M6 2.6v4.2" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
          <circle cx="6" cy="9.2" r="1" fill="currentColor" />
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

const SR_STATE = { pass: "Passed: ", fail: "Failed: ", review: "For you to judge: " } as const;
const STAMP = {
  PASS: { text: "Passed", cls: "text-green", sr: "passed" },
  REVIEW: { text: "Your call", cls: "text-amber", sr: "sent to your review" },
  FAIL: { text: "Failed", cls: "text-red", sr: "failed" },
} as const;

/**
 * The platform's inspection of a delivered result, set out as the inspector's sheet: each
 * automated check against the frozen contract, ticked, crossed, or (when no machine could decide,
 * or the agent flagged something) marked for the client; what the agent flagged; any earlier
 * attempts the checks sent back for rework; and Findings about the brief, which never change the
 * agent's pay. Last comes the one line no machine checks, the acceptance criteria, which is the
 * client's call. It tells a client exactly what "validated" covered before they accept.
 */
export function InspectionReport({
  report,
  spec,
  awaitingYou,
  autoAccepted = false,
}: {
  report: ValidationReportDTO;
  spec: OutputSpecDTO;
  /** True while the client still has to accept or dispute; the last line then asks them. */
  awaitingYou: boolean;
  /** The review window closed unreviewed (#94), so nobody judged the acceptance criteria. */
  autoAccepted?: boolean;
}) {
  const stamp = STAMP[report.verdict] ?? STAMP.FAIL;
  const anomalies = report.anomalies ?? [];
  const findings = report.findings ?? [];
  // Only results a check rejected were sent back for rework; a passed earlier result was replaced by the
  // agent revising it after the client's rejection, which the rejection panel already explains.
  const previous = (report.previousAttempts ?? []).filter((a) => a.verdict === "FAIL");
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
        <span aria-hidden style={{ animationDelay: "350ms" }} className={`inspect-stamp stamp ${stamp.cls}`}>
          {stamp.text}
        </span>
        <span className="sr-only">Verdict: {stamp.sr}.</span>
      </div>
      {previous.length > 0 && (
        <p className="border-t border-line px-5 py-2.5 text-sm text-muted">
          <span className="font-semibold text-fg">
            Reworked {previous.length === 1 ? "once" : `${previous.length} times`}.
          </span>{" "}
          {previous
            .map(
              (a) =>
                `${a.attemptNo === previous[0].attemptNo ? "Result" : "result"} ${a.attemptNo} failed the ${a.failedChecks.map((c) => checkName(c.rule)).join(" and ") || "automatic"} ${
                  a.failedChecks.length > 1 ? "checks" : "check"
                }`,
            )
            .join("; ")}
          {". The agent was sent what failed and fixed it before you saw it."}
        </p>
      )}
      <div className="docket-tear" />
      <ol className="divide-y divide-line">
        {report.checks.map((c, i) => {
          const state = checkState(c);
          return (
            <li key={`${c.rule}-${i}`} className={`flex items-start gap-3 px-5 py-3 ${state === "review" ? "bg-amber/[0.06]" : ""}`}>
              <Mark state={state} />
              <div className="min-w-0 pt-0.5">
                <p className="text-[0.9375rem] font-medium text-fg">
                  <span className="sr-only">{SR_STATE[state]}</span>
                  {ruleText(c.rule)}
                </p>
                {c.detail && state !== "pass" && (
                  <p className={`mt-0.5 break-words text-sm ${state === "review" ? "text-amber" : "text-red"}`}>{c.detail}</p>
                )}
              </div>
            </li>
          );
        })}
        <li className="flex items-start gap-3 bg-amber/[0.06] px-5 py-3">
          <Mark state="yours" />
          <div className="min-w-0 pt-0.5">
            <p className="text-[0.9375rem] font-medium text-fg">
              <span className="sr-only">
                {awaitingYou ? "For you to check: " : autoAccepted ? "Not checked: " : "Checked by you: "}
              </span>
              {awaitingYou
                ? "Does it meet the acceptance criteria? That one is yours to judge."
                : autoAccepted
                  ? "Acceptance criteria not judged: the review window closed first"
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
      {anomalies.length > 0 && (
        <div className="border-t border-line px-5 py-4">
          <h3 className="eyebrow text-amber">The agent flagged</h3>
          <ul className="mt-2 space-y-1.5">
            {anomalies.map((a, i) => (
              <li key={i} className="text-sm text-fg">
                {a.check && <span className="mr-1.5 text-muted">{ruleText(a.check)}:</span>}
                &ldquo;{a.description}&rdquo;
              </li>
            ))}
          </ul>
          <p className="mt-2 text-xs text-dim">
            Flagging it sent the result to you instead of an automatic rejection. Accept if you agree; the agent is
            paid only then.
          </p>
        </div>
      )}
      {findings.length > 0 && (
        <div className="border-t border-line px-5 py-4">
          <h3 className="eyebrow">Findings about your brief</h3>
          <ul className="mt-2 space-y-2">
            {findings.map((f, i) => (
              <li key={i} className="text-sm">
                <p className="font-medium text-fg">{FINDING_TEXT[f.code] ?? ruleText(f.code)}</p>
                {f.detail && <p className="mt-0.5 break-words text-muted">{f.detail}</p>}
              </li>
            ))}
          </ul>
          <p className="mt-2 text-xs text-dim">For your information only: a Finding never changes what the agent is paid.</p>
        </div>
      )}
    </section>
  );
}
