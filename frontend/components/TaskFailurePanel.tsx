"use client";

import Link from "next/link";
import type { TaskStatus, ValidationReportDTO } from "@/lib/types";

interface Props {
  status: TaskStatus;
  budget: number;
  detail?: ValidationReportDTO | null;
}

type Tone = "red" | "amber" | "dim";
interface Copy {
  tone: Tone;
  icon: string;
  headline: string;
  why: string;
  action: { label: string; href: string };
}

const COPY: Partial<Record<TaskStatus, Copy>> = {
  SPEC_VIOLATION: {
    tone: "red",
    icon: "⛔",
    headline: "The result didn't meet the spec",
    why: "The agent returned something, but it failed the automated output check for this task — so you were never charged for it.",
    action: { label: "Submit a new task", href: "/client/tasks/new" },
  },
  TIMED_OUT: {
    tone: "amber",
    icon: "⏱",
    headline: "The agent ran out of time",
    why: "This agent accepted your task but didn't return a result within its deadline. That's on the agent, not you.",
    action: { label: "Try another agent", href: "/client/tasks/new" },
  },
  FAILED: {
    tone: "red",
    icon: "⚡",
    headline: "We couldn't reach the agent",
    why: "We tried to hand your task to the agent but its service didn't respond. No work started, so there's nothing to review.",
    action: { label: "Try another agent", href: "/client/tasks/new" },
  },
  CANCELLED: {
    tone: "dim",
    icon: "◎",
    headline: "No agent was available",
    why: "We kept looking for a free agent in this category but none opened up in time, so we released your task rather than hold your credits.",
    action: { label: "Browse the marketplace", href: "/marketplace" },
  },
};

const TONE_CLS: Record<Tone, string> = {
  red: "border-red/30 bg-red/10",
  amber: "border-amber/30 bg-amber/10",
  dim: "border-line-bright bg-surface-2",
};

const ICON_CLS: Record<Tone, string> = {
  red: "text-red",
  amber: "text-amber",
  dim: "text-muted",
};

export function TaskFailurePanel({ status, budget, detail }: Props) {
  const copy = COPY[status];
  if (!copy) return null;
  const failedChecks = detail?.checks.filter((c) => !c.passed) ?? [];

  return (
    <section aria-live="polite" className={`space-y-3 rounded-md border p-5 ${TONE_CLS[copy.tone]}`}>
      <div className="flex items-start gap-4">
        <span
          aria-hidden
          className={`grid size-10 shrink-0 place-items-center rounded-lg border border-line-bright bg-surface text-xl ${ICON_CLS[copy.tone]}`}
        >
          {copy.icon}
        </span>
        <div className="space-y-1.5">
          <h2 className="text-base font-extrabold tracking-tight">{copy.headline}</h2>
          <p className="text-sm text-muted">{copy.why}</p>
        </div>
      </div>

      <p className="inline-flex items-center gap-2 rounded-md border border-accent/30 bg-accent/10 px-3 py-1.5 font-mono text-xs text-accent">
        ✓ {budget} cr refunded to your wallet
      </p>

      {status === "SPEC_VIOLATION" && failedChecks.length > 0 && (
        <details className="font-mono text-xs">
          <summary className="cursor-pointer text-muted">Show what failed ▸</summary>
          <div className="mt-2 space-y-1 rounded-md border border-line bg-canvas p-3 text-muted">
            {failedChecks.map((c, i) => (
              <p key={i}>
                <span className="text-red">{c.rule}</span>
                {c.detail ? ` — ${c.detail}` : ""}
              </p>
            ))}
          </div>
        </details>
      )}

      <div>
        <Link
          href={copy.action.href}
          className="font-mono text-xs text-dim transition hover:text-accent"
        >
          {copy.action.label} ▸
        </Link>
      </div>
    </section>
  );
}
