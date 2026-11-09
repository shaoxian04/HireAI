import { Fragment } from "react";
import type { RulingDTO } from "@/lib/types";

type Step = { label: string; state: "done" | "active" | "wait" | "skipped" | "todo" };

/**
 * Where a dispute is in its tiers: raised → arbitrator ruling → client's response → administrator
 * review → settled. The administrator step is shown as skipped when a case settles without
 * escalation. Same visual language as the task StatusTrack.
 */
export function disputeSteps(status: string, rulings: RulingDTO[]): Step[] {
  const arbRuled = rulings.some((r) => r.decidedBy !== "ADMINISTRATOR");
  const adminRuled = rulings.some((r) => r.decidedBy === "ADMINISTRATOR");
  const resolved = status === "RESOLVED";
  return [
    { label: "Raised", state: "done" },
    {
      label: "Arbitrator",
      state: arbRuled || ["RULED", "ESCALATED", "RESOLVED"].includes(status) ? "done" : "active",
    },
    {
      label: "Client responds",
      state: status === "RULED" ? "wait" : status === "ESCALATED" || resolved ? "done" : "todo",
    },
    {
      label: "Administrator",
      state: adminRuled ? "done" : status === "ESCALATED" ? "active" : resolved ? "skipped" : "todo",
    },
    { label: "Settled", state: resolved ? "done" : "todo" },
  ];
}

const DOT: Record<Step["state"], string> = {
  done: "bg-accent",
  active: "bg-cyan dot-live text-cyan",
  wait: "bg-amber dot-live text-amber",
  skipped: "border-2 border-dashed border-line-bright bg-transparent",
  todo: "bg-line-bright",
};

export function DisputeTrack({ status, rulings }: { status: string; rulings: RulingDTO[] }) {
  const steps = disputeSteps(status, rulings);
  const current = steps.find((s) => s.state === "active" || s.state === "wait");
  const summary = current
    ? `Dispute progress: ${current.state === "wait" ? "waiting on" : "at"} ${current.label.toLowerCase()}`
    : status === "RESOLVED"
      ? "Dispute progress: settled"
      : "Dispute progress";
  return (
    <div role="img" aria-label={summary} className="flex items-start">
      {steps.map((s, i) => (
        <Fragment key={s.label}>
          <div className="flex w-20 flex-col items-center gap-2 text-center">
            <span className={`relative size-3 rounded-full ring-4 ring-surface ${DOT[s.state]}`} />
            <span
              className={`text-xs ${s.state === "active" ? "font-bold text-cyan" : s.state === "wait" ? "font-bold text-amber" : s.state === "done" ? "font-medium text-fg" : "text-dim"} ${s.state === "skipped" ? "line-through" : ""}`}
            >
              {s.label}
            </span>
          </div>
          {i < steps.length - 1 && (
            <span
              aria-hidden
              className={`mt-[0.33rem] h-0.5 flex-1 rounded-full ${steps[i + 1].state === "done" || steps[i + 1].state === "active" || steps[i + 1].state === "wait" ? "bg-accent" : "bg-line"}`}
            />
          )}
        </Fragment>
      ))}
    </div>
  );
}
