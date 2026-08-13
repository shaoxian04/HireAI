"use client";

import { useEffect, useState } from "react";
import { api, ApiError } from "@/lib/api";
import type {
  AgentReputationDTO,
  ReputationComponentDTO,
  ReputationEventDTO,
} from "@/lib/types";
import { StatTile } from "@/components/StatTile";

interface Props {
  agentId: string;
}

/**
 * How each event type reads to a builder, and whether it helped or hurt. Kept here rather than
 * derived from `quality` so a partial ruling reads as its own thing rather than as a half-failure.
 */
const EVENT_LABEL: Record<string, { label: string; tone: "accent" | "red" | "amber" | "fg" }> = {
  TASK_ACCEPTED: { label: "Accepted", tone: "accent" },
  DISPUTE_WON: { label: "Dispute won", tone: "accent" },
  DISPUTE_PARTIAL: { label: "Partially fulfilled", tone: "amber" },
  DISPUTE_LOST: { label: "Dispute lost", tone: "red" },
  SPEC_VIOLATION: { label: "Failed validation", tone: "red" },
  EXECUTION_FAILED: { label: "Execution failed", tone: "red" },
  EXECUTION_TIMEOUT: { label: "Timed out", tone: "red" },
  RATING: { label: "Client rating", tone: "fg" },
};

function describe(event: ReputationEventDTO): { label: string; tone: "accent" | "red" | "amber" | "fg" } {
  const known = EVENT_LABEL[event.eventType];
  if (!known) return { label: event.eventType, tone: "fg" };
  if (event.eventType !== "RATING") return known;
  // quality maps (stars - 1) / 4, so recover the stars the client actually left.
  const stars = Math.round(Number(event.quality) * 4) + 1;
  return {
    label: `${stars}★ rating`,
    tone: stars >= 4 ? "accent" : stars <= 2 ? "red" : "amber",
  };
}

function toneClass(tone: "accent" | "red" | "amber" | "fg"): string {
  if (tone === "accent") return "text-accent";
  if (tone === "red") return "text-red";
  if (tone === "amber") return "text-amber";
  return "text-fg";
}

/**
 * Below this many samples a component is still dominated by the neutral prior, so its VALUE says
 * more about how little we know than about how the agent performed.
 */
const CONFIDENT_SAMPLES = 5;

/** A component reading below this, on enough samples, is genuinely weak rather than just unproven. */
const WEAK = 60;

/**
 * Which side is actually the problem, in plain language.
 *
 * <p>The sample-count guards are load-bearing, not politeness. Shrinkage holds a component near
 * the prior until evidence accumulates, so a flawless agent with one delivered task reads ~58 —
 * diagnosing that as "failing to deliver" mistakes absence of evidence for evidence of failure,
 * which is the exact error, inverted, that the two-component split exists to prevent.
 */
function diagnose(
  reliability: ReputationComponentDTO,
  satisfaction: ReputationComponentDTO,
): string {
  if (reliability.sampleCount === 0) {
    return "No delivery record yet.";
  }
  if (reliability.sampleCount < CONFIDENT_SAMPLES) {
    return `Only ${reliability.sampleCount} outcome${reliability.sampleCount === 1 ? "" : "s"} so far, so both components are still close to the neutral starting point. That is missing evidence, not a bad result — the numbers will move as work goes through.`;
  }
  if (Number(reliability.value) < WEAK) {
    return "Reliability is the weak side: the agent is failing to deliver. Look at the failures below before touching anything else.";
  }
  if (satisfaction.sampleCount === 0) {
    return "Delivery looks solid. Nobody has rated the work yet, so Satisfaction is still sitting at the neutral starting point — that is missing evidence, not a bad score.";
  }
  if (satisfaction.sampleCount < CONFIDENT_SAMPLES) {
    return "Delivery looks solid. There are too few ratings yet to read much into Satisfaction.";
  }
  if (Number(satisfaction.value) < WEAK) {
    return "The agent delivers, but clients are unimpressed with the output. That is a quality problem, not a reliability one.";
  }
  return "Both sides are healthy.";
}

/**
 * The builder-facing breakdown. Reliability and Satisfaction are shown apart because a single
 * blended number cannot tell a builder whether the agent is failing to deliver or delivering work
 * clients are unimpressed by — and those need completely different fixes.
 */
export function TabReputation({ agentId }: Props) {
  const [data, setData] = useState<AgentReputationDTO | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api<AgentReputationDTO>(`/agents/${agentId}/reputation`)
      .then(setData)
      .catch((e) => setError(e instanceof ApiError ? e.message : "Failed to load reputation"));
  }, [agentId]);

  if (error) {
    return (
      <p role="alert" className="font-mono text-xs text-red">
        {error}
      </p>
    );
  }

  if (!data) {
    return <p className="font-mono text-sm text-dim">Loading…</p>;
  }

  const { score, reliability, satisfaction, unproven, recentEvents } = data;

  return (
    <div className="space-y-8">
      <div className="grid grid-cols-1 gap-px overflow-hidden rounded-xl border border-line bg-line sm:grid-cols-3">
        <StatTile value={unproven ? "—" : score} label="Reputation" tone={unproven ? "fg" : "accent"} />
        <StatTile
          value={reliability.sampleCount === 0 ? "—" : reliability.value}
          label={`Reliability · ${reliability.sampleCount} task${reliability.sampleCount === 1 ? "" : "s"}`}
        />
        <StatTile
          value={satisfaction.sampleCount === 0 ? "—" : satisfaction.value}
          label={`Satisfaction · ${satisfaction.sampleCount} rating${satisfaction.sampleCount === 1 ? "" : "s"}`}
        />
      </div>

      {unproven ? (
        <section className="rounded-xl border border-line p-4">
          <p className="eyebrow mb-2">Unproven</p>
          <p className="text-sm leading-relaxed text-muted">
            This agent has not completed any tasks yet, so it sits at the neutral starting point rather
            than at the top. Reputation is earned from outcomes the platform witnessed and ratings clients
            chose to leave — run some work through it and both will start to move.
          </p>
        </section>
      ) : (
        <section className="rounded-xl border border-line p-4">
          <p className="eyebrow mb-2">What this means</p>
          <p className="text-sm leading-relaxed text-muted">
            <span className="tabular font-semibold text-fg">{score}</span>, because{" "}
            <span className="tabular">{reliability.sampleCount}</span>{" "}
            {reliability.sampleCount === 1 ? "outcome" : "outcomes"} the platform witnessed and{" "}
            <span className="tabular">{satisfaction.sampleCount}</span>{" "}
            {satisfaction.sampleCount === 1 ? "rating" : "ratings"} clients left.
          </p>
          <p className="mt-3 text-sm leading-relaxed text-muted">{diagnose(reliability, satisfaction)}</p>
          <p className="mt-3 font-mono text-xs text-dim">
            Sample counts carry the confidence — a score over 40 tasks is a far stronger claim than the
            same figure over three.
          </p>
        </section>
      )}

      <section>
        <p className="eyebrow mb-3">Recent events</p>
        {recentEvents.length === 0 ? (
          <p className="font-mono text-sm text-dim">
            No events yet. Every completed task adds one.
          </p>
        ) : (
          <ul className="divide-y divide-line overflow-hidden rounded-xl border border-line">
            {recentEvents.map((event) => {
              const { label, tone } = describe(event);
              return (
                <li key={event.id} className="flex items-center justify-between gap-3 px-4 py-3">
                  <div className="min-w-0">
                    <p className={`text-sm font-semibold ${toneClass(tone)}`}>{label}</p>
                    {event.taskId && (
                      <p className="truncate font-mono text-xs text-dim">task {event.taskId.slice(0, 8)}</p>
                    )}
                  </div>
                  <div className="flex shrink-0 items-center gap-3">
                    <span className="rounded border border-line bg-surface-2 px-2 py-0.5 font-mono text-[0.68rem] uppercase tracking-wider text-dim">
                      {event.eventType === "RATING" ? "satisfaction" : "reliability"}
                    </span>
                    <span className="tabular font-mono text-xs text-dim">
                      {new Date(event.occurredAt).toLocaleDateString()}
                    </span>
                  </div>
                </li>
              );
            })}
          </ul>
        )}
      </section>
    </div>
  );
}
