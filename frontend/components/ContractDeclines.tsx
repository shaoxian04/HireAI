import type { ContractDeclineRateDTO, DispatchAttemptDTO, DispatchOutcome } from "@/lib/types";

/** At or above this share of declined terms the rate is flagged: the agent may be cherry-picking. */
const HIGH_DECLINE_RATE = 0.25;

/**
 * An agent's public contract-decline rate (#93): of the tasks whose terms it answered, how many it
 * turned down before starting. Declining costs no reputation, so this is the only place it shows.
 * An agent that has answered nothing reads as such, never as "declines nothing".
 */
export function ContractDeclines({
  rate,
  className = "",
}: {
  rate?: ContractDeclineRateDTO | null;
  className?: string;
}) {
  if (!rate) return null;
  if (!rate.answered) {
    return <span className={`text-dim ${className}`}>No terms answered yet</span>;
  }
  const high = rate.rate != null && rate.declined > 0 && rate.rate >= HIGH_DECLINE_RATE;
  return (
    <span className={`${high ? "text-amber" : ""} ${className}`.trim()}>
      Declined terms on <span className="tabular">{rate.declined}</span> of{" "}
      <span className="tabular">{rate.answered}</span> {rate.answered === 1 ? "task" : "tasks"}
      {rate.declined > 0 && rate.rate != null && (
        <span className="tabular"> ({Math.round(rate.rate * 100)}%)</span>
      )}
    </span>
  );
}

const OUTCOME_COPY: Record<DispatchOutcome, string> = {
  ACCEPTED: "took the task",
  CONTRACT_DECLINED: "declined your terms",
  BUSY: "was busy",
  UNREACHABLE: "couldn’t be reached",
  UNAVAILABLE: "was no longer available",
};

const OUTCOME_TONE: Record<DispatchOutcome, string> = {
  ACCEPTED: "text-accent",
  CONTRACT_DECLINED: "text-amber",
  BUSY: "text-amber",
  UNREACHABLE: "text-red",
  UNAVAILABLE: "text-dim",
};

/**
 * The task's hand-offs: shown only when the task did not simply go to the first agent, so the client
 * can see who turned it down and why (an agent that declines the terms may say why).
 */
export function DispatchHistory({ attempts }: { attempts: DispatchAttemptDTO[] }) {
  if (!attempts.some((a) => a.outcome !== "ACCEPTED")) return null;
  return (
    <section aria-labelledby="handoffs-heading" className="space-y-3 rounded-xl border border-line bg-surface p-5">
      <h2 id="handoffs-heading" className="eyebrow">Hand-offs</h2>
      <ol className="space-y-2.5">
        {attempts.map((a) => (
          <li key={a.attemptNumber} className="flex gap-3 text-sm">
            <span className="mt-0.5 w-5 shrink-0 font-mono text-xs text-dim tabular">{a.attemptNumber}</span>
            <div className="min-w-0">
              <p>
                <span className="font-semibold text-fg">{a.agentName ?? "An agent"}</span>{" "}
                <span className={OUTCOME_TONE[a.outcome]}>{OUTCOME_COPY[a.outcome]}</span>
              </p>
              {a.declineReason && (
                <p className="mt-0.5 break-words text-xs text-muted">“{a.declineReason}”</p>
              )}
            </div>
          </li>
        ))}
      </ol>
      {attempts.some((a) => a.outcome === "CONTRACT_DECLINED") && (
        <p className="text-xs text-dim">
          Agents may turn down terms they can&apos;t meet before starting. It costs them no reputation,
          but it shows in their public decline rate.
        </p>
      )}
    </section>
  );
}
