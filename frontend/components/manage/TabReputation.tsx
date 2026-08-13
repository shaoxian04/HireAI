"use client";

import { useEffect, useState } from "react";
import { api, ApiError } from "@/lib/api";
import type {
  AgentReputationDTO,
  ReputationComponentDTO,
  ReputationEventDTO,
} from "@/lib/types";

interface Props {
  agentId: string;
}

type Tone = "accent" | "red" | "amber" | "fg" | "dim";

const TEXT: Record<Tone, string> = {
  accent: "text-accent",
  red: "text-red",
  amber: "text-amber",
  fg: "text-fg",
  dim: "text-dim",
};

const FILL: Record<Tone, string> = {
  accent: "bg-accent",
  red: "bg-red",
  amber: "bg-amber",
  fg: "bg-fg",
  dim: "bg-line-bright",
};

/**
 * The neutral starting point every component falls back to, on the 0–100 scale — 100 × p₀. Unlike
 * kR/kS/α this one is not sent per-request: 50.00 is baked into the model as the value every agent
 * has scored since V3, and the whole scale is drawn around it.
 */
const NEUTRAL = 50;

/** Below this, on evidence we actually trust, a component is genuinely weak rather than unproven. */
const WEAK = 60;

/**
 * A component whose prior still owns more than half its value is telling you about the absence of
 * evidence, not about the agent. Derived from the server's `priorWeight` rather than a sample-count
 * threshold of our own: kR and kS live in ReputationPolicy, and a copy here would start lying
 * silently the moment they were tuned.
 */
function isTooEarly(c: ReputationComponentDTO): boolean {
  return c.priorWeight > 0.5;
}

function fmt(value: number): string {
  return value.toFixed(1);
}

function pct(fraction: number): number {
  return Math.round(fraction * 100);
}

function toneFor(c: ReputationComponentDTO): Tone {
  if (c.sampleCount === 0 || isTooEarly(c)) return "dim";
  if (c.value < WEAK) return "red";
  if (c.value < 75) return "amber";
  return "accent";
}

/**
 * A 0–100 track with the neutral start marked, so a builder can see at a glance that 58 is
 * *just above the middle* rather than a failing grade out of 100. The bar is drawn from the
 * neutral mark outward — the distance travelled from the starting point is the only part of the
 * number the agent actually earned.
 */
function Meter({ value, tone }: { value: number; tone: Tone }) {
  const from = Math.min(value, NEUTRAL);
  const width = Math.abs(value - NEUTRAL);
  return (
    <div className="relative h-1.5 w-full overflow-hidden rounded-full bg-surface-2" aria-hidden>
      <div
        className={`absolute inset-y-0 ${FILL[tone]} transition-all`}
        style={{ left: `${from}%`, width: `${width}%` }}
      />
      <div className="absolute inset-y-0 w-px bg-line-bright" style={{ left: `${NEUTRAL}%` }} />
    </div>
  );
}

/**
 * How much of this component is earned rather than assumed. Shown because the value alone cannot
 * distinguish "we do not know yet" from "we know, and it is bad" — the confusion that had this
 * panel telling a builder with a flawless one-task record that the agent was failing to deliver.
 */
function Evidence({ component, noun }: { component: ReputationComponentDTO; noun: string }) {
  const { sampleCount, priorWeight } = component;
  const earned = pct(1 - priorWeight);

  if (sampleCount === 0) {
    return (
      <p className="font-mono text-[0.68rem] text-dim">
        No {noun}s yet · showing the neutral starting point
      </p>
    );
  }

  return (
    <div className="space-y-1.5">
      <div className="flex items-baseline justify-between gap-2 font-mono text-[0.68rem]">
        <span className="text-muted">
          {sampleCount} {noun}
          {sampleCount === 1 ? "" : "s"}
        </span>
        <span className={isTooEarly(component) ? "text-dim" : "text-muted"}>
          {earned}% earned
        </span>
      </div>
      <div className="h-px w-full bg-surface-2" aria-hidden>
        <div className="h-px bg-line-bright" style={{ width: `${earned}%` }} />
      </div>
    </div>
  );
}

function ComponentCard({
  name,
  component,
  noun,
  blurb,
}: {
  name: string;
  component: ReputationComponentDTO;
  noun: string;
  blurb: string;
}) {
  const tone = toneFor(component);
  return (
    <section className="space-y-3 rounded-xl border border-line bg-surface p-5">
      <div className="flex items-center justify-between gap-2">
        <p className="eyebrow">{name}</p>
        <span className="rounded border border-line bg-surface-2 px-2 py-0.5 font-mono text-[0.6rem] uppercase tracking-wider text-dim">
          {pct(component.weight)}% of score
        </span>
      </div>

      <p className={`tabular text-4xl font-extrabold ${TEXT[tone]}`}>
        {component.sampleCount === 0 ? "—" : fmt(component.value)}
      </p>

      <Meter value={component.value} tone={tone} />
      <Evidence component={component} noun={noun} />
      <p className="text-xs leading-relaxed text-dim">{blurb}</p>
    </section>
  );
}

type Verdict = { tone: Tone; headline: string; detail: string };

/**
 * The one thing worth reading on this page: which side is the problem, in plain language.
 *
 * <p>The evidence guards are load-bearing, not politeness. Shrinkage holds a component near the
 * prior until evidence accumulates, so a flawless agent with one delivered task reads ~58 —
 * diagnosing that as "failing to deliver" mistakes absence of evidence for evidence of failure,
 * the exact error, inverted, that the two-component split exists to prevent.
 */
function verdict(
  reliability: ReputationComponentDTO,
  satisfaction: ReputationComponentDTO,
): Verdict {
  if (isTooEarly(reliability)) {
    const n = reliability.sampleCount;
    return {
      tone: "dim",
      headline: "Too early to tell",
      detail: `${n} outcome${n === 1 ? "" : "s"} so far, so most of these numbers are still the neutral starting point. That is missing evidence, not a bad result — they move as work goes through.`,
    };
  }
  if (reliability.value < WEAK) {
    return {
      tone: "red",
      headline: "Delivery is the problem",
      detail:
        "The agent is failing to complete work to spec. Fix that before anything else: clients cannot rate work that never arrives, so a delivery problem holds both components down at once.",
    };
  }
  if (satisfaction.sampleCount === 0) {
    return {
      tone: "amber",
      headline: "Delivering, unrated",
      detail:
        "Delivery looks solid and nobody has rated the work yet, so Satisfaction sits at the neutral starting point. Silence is not a compliment here — it holds the score down until clients rate you.",
    };
  }
  if (isTooEarly(satisfaction)) {
    return {
      tone: "amber",
      headline: "Delivering, lightly rated",
      detail:
        "Delivery looks solid. There are too few ratings to read anything into Satisfaction yet — it will firm up as more clients rate completed work.",
    };
  }
  if (satisfaction.value < WEAK) {
    return {
      tone: "amber",
      headline: "Delivers, but clients are unimpressed",
      detail:
        "The work arrives and conforms to spec, but clients are not happy with it. That is a quality problem, not a reliability one — the fix is in the output, not the uptime.",
    };
  }
  return {
    tone: "accent",
    headline: "Both sides healthy",
    detail: "The agent delivers reliably and clients rate the results well.",
  };
}

/**
 * How each event type reads to a builder, and whether it helped or hurt. Kept here rather than
 * derived from `quality` so a partial ruling reads as its own thing rather than as a half-failure.
 */
const EVENT_LABEL: Record<string, { label: string; tone: Tone }> = {
  TASK_ACCEPTED: { label: "Accepted", tone: "accent" },
  DISPUTE_WON: { label: "Dispute won", tone: "accent" },
  DISPUTE_PARTIAL: { label: "Partially fulfilled", tone: "amber" },
  DISPUTE_LOST: { label: "Dispute lost", tone: "red" },
  SPEC_VIOLATION: { label: "Failed validation", tone: "red" },
  EXECUTION_FAILED: { label: "Execution failed", tone: "red" },
  EXECUTION_TIMEOUT: { label: "Timed out", tone: "red" },
  RATING: { label: "Client rating", tone: "fg" },
};

function describe(event: ReputationEventDTO): { label: string; tone: Tone } {
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
  const v = verdict(reliability, satisfaction);

  return (
    <div className="space-y-6">
      {/* Headline: the score, placed on its scale, with the verdict beside it. */}
      <section className="rounded-xl border border-line bg-surface p-6">
        <div className="flex flex-col gap-6 sm:flex-row sm:items-start">
          <div className="sm:w-64 sm:shrink-0">
            <p className="eyebrow mb-2">Reputation</p>
            <p
              className={`tabular text-6xl font-extrabold leading-none ${
                unproven ? "text-dim" : TEXT[v.tone === "dim" ? "fg" : v.tone]
              }`}
            >
              {unproven ? "—" : fmt(score)}
            </p>
            <div className="mt-4">
              <Meter value={unproven ? NEUTRAL : score} tone={unproven ? "dim" : v.tone} />
              <div className="mt-1.5 flex justify-between font-mono text-[0.6rem] text-dim">
                <span>0</span>
                <span>50 · neutral start</span>
                <span>100</span>
              </div>
            </div>
          </div>

          <div className="min-w-0 flex-1 border-line sm:border-l sm:pl-6">
            <p className={`text-lg font-bold ${TEXT[v.tone === "dim" ? "fg" : v.tone]}`}>
              {unproven ? "Unproven" : v.headline}
            </p>
            <p className="mt-2 text-sm leading-relaxed text-muted">
              {unproven
                ? "Nothing has run through this agent yet, so it sits at the neutral starting point rather than at the top. Reputation is earned from outcomes the platform witnessed and ratings clients chose to leave."
                : v.detail}
            </p>
          </div>
        </div>
      </section>

      {/* The two components, apart, each with the evidence behind it. */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <ComponentCard
          name="Reliability"
          component={reliability}
          noun="outcome"
          blurb="What the platform witnessed: accepted work, failed validation, crashes, timeouts, dispute rulings. You cannot influence this except by delivering."
        />
        <ComponentCard
          name="Satisfaction"
          component={satisfaction}
          noun="rating"
          blurb="What clients said when they rated completed work. Held to a higher evidential bar than Reliability, because it is the signal that could be gamed."
        />
      </div>

      <section>
        <p className="eyebrow mb-3">Recent events</p>
        {recentEvents.length === 0 ? (
          <p className="rounded-xl border border-dashed border-line px-4 py-6 text-center font-mono text-xs text-dim">
            No events yet. Every completed task adds one.
          </p>
        ) : (
          <ul className="divide-y divide-line overflow-hidden rounded-xl border border-line">
            {recentEvents.map((event) => {
              const { label, tone } = describe(event);
              const counted = Math.round(Number(event.quality) * 100);
              return (
                <li key={event.id} className="flex items-center justify-between gap-3 px-4 py-3">
                  <div className="min-w-0">
                    <p className={`text-sm font-semibold ${TEXT[tone]}`}>{label}</p>
                    <p className="font-mono text-[0.65rem] text-dim">
                      {event.eventType === "RATING" ? "Satisfaction" : "Reliability"}
                      {event.taskId && ` · task ${event.taskId.slice(0, 8)}`}
                    </p>
                  </div>
                  <div className="flex shrink-0 items-center gap-4">
                    {/* On the same 0–100 scale as everything else on the page: this is what the
                        event actually contributed, which is how a 4★ rating turns out to be a 75. */}
                    <span
                      className="tabular font-mono text-xs"
                      aria-label={`counted ${counted} out of 100`}
                    >
                      <span aria-hidden className={TEXT[tone]}>{counted}</span>
                      <span aria-hidden className="text-dim">/100</span>
                    </span>
                    <span className="tabular font-mono text-[0.65rem] text-dim">
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
