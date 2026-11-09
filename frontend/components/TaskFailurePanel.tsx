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
  headline: string;
  why: string;
  action: { label: string; href: string };
}

const COPY: Partial<Record<TaskStatus, Copy>> = {
  SPEC_VIOLATION: {
    tone: "red",
    headline: "The result didn't meet the spec",
    why: "The agent returned something, but it failed the automated output check for this task — so you were never charged for it.",
    action: { label: "Submit a new task", href: "/client/tasks/new" },
  },
  TIMED_OUT: {
    tone: "amber",
    headline: "The agent ran out of time",
    why: "This agent accepted your task but didn't return a result within its deadline. That's on the agent, not you.",
    action: { label: "Try another agent", href: "/client/tasks/new" },
  },
  FAILED: {
    tone: "red",
    headline: "We couldn't reach the agent",
    why: "We tried to hand your task to the agent but its service didn't respond. No work started, so there's nothing to review.",
    action: { label: "Try another agent", href: "/client/tasks/new" },
  },
  CANCELLED: {
    tone: "dim",
    headline: "No agent was available",
    why: "We kept looking for a free agent in this category but none opened up in time, so we released your task rather than hold your credits.",
    action: { label: "Browse the marketplace", href: "/client" },
  },
};

const TONE_CLS: Record<Tone, string> = {
  red: "border-l-red",
  amber: "border-l-amber",
  dim: "border-l-line-bright",
};

const HEAD_CLS: Record<Tone, string> = {
  red: "text-red",
  amber: "text-amber",
  dim: "text-fg",
};

export function TaskFailurePanel({ status, budget, detail }: Props) {
  const copy = COPY[status];
  if (!copy) return null;
  const failedChecks = detail?.checks.filter((c) => !c.passed) ?? [];

  return (
    <section
      aria-live="polite"
      className={`space-y-4 rounded-xl border border-l-4 border-line bg-surface p-6 ${TONE_CLS[copy.tone]}`}
    >
      <div className="flex flex-wrap items-start justify-between gap-6">
        <div className="max-w-lg space-y-1.5">
          <h2 className={`text-lg font-bold ${HEAD_CLS[copy.tone]}`}>{copy.headline}</h2>
          <p className="text-sm leading-relaxed text-muted">{copy.why}</p>
          <p className="pt-2 text-sm font-semibold text-fg">{budget} cr refunded to your wallet</p>
        </div>
        <span aria-hidden style={{ animationDelay: "250ms" }} className="inspect-stamp stamp mr-2 text-fg">
          REFUNDED
          <small>{budget} cr returned</small>
        </span>
      </div>

      {status === "SPEC_VIOLATION" && failedChecks.length > 0 && (
        <details className="group text-sm">
          <summary className="cursor-pointer list-none text-muted transition hover:text-fg">
            <span className="mr-1.5 inline-block transition group-open:rotate-90">›</span>
            Show what failed
          </summary>
          <ul className="mt-2 space-y-1 rounded-md border border-line bg-surface-2 p-3 font-mono text-xs text-muted">
            {failedChecks.map((c, i) => (
              <li key={i}>
                <span className="text-red">{c.rule}</span>
                {c.detail ? ` — ${c.detail}` : ""}
              </li>
            ))}
          </ul>
        </details>
      )}

      <Link
        href={copy.action.href}
        className="inline-flex h-10 items-center rounded-md bg-accent px-4 text-sm font-semibold text-ink transition hover:bg-accent-2"
      >
        {copy.action.label}
      </Link>
    </section>
  );
}
