import type { InspectionDTO, RejectionDTO, SampleDrawDTO, TaskItemDTO } from "@/lib/types";

/** The sample out with the reviewer now: the round's undecided draw, else the last one drawn. */
export function currentDraw(inspection: InspectionDTO): SampleDrawDTO | null {
  const open = inspection.draws.find((d) => d.decision == null);
  return open ?? inspection.draws[inspection.draws.length - 1] ?? null;
}

/** The Items a round's samples hold, both stages. */
export function sampledInRound(inspection: InspectionDTO, round: number): Set<number> {
  const nos = new Set<number>();
  inspection.draws.filter((d) => d.round === round).forEach((d) => d.itemNos.forEach((n) => nos.add(n)));
  return nos;
}

/** An Item's latest rejection round, if the reviewer ever rejected it. */
export function latestRejection(inspection: InspectionDTO, itemNo: number): RejectionDTO | null {
  let latest: RejectionDTO | null = null;
  for (const r of inspection.itemRejections) {
    if (r.itemNo === itemNo) latest = r.rejection;
  }
  return latest;
}

/** Where a sampled Item stands, as the reviewer reads it. */
export type Standing = "to-judge" | "correct" | "wrong" | "agent-turn" | "your-call" | "pending";

export function standing(item: TaskItemDTO | undefined, rejection: RejectionDTO | null): Standing {
  if (!item) return "pending";
  switch (item.status) {
    case "PENDING_REVIEW":
      return "to-judge";
    case "ACCEPTED":
      return "correct";
    case "CONCEDED":
    case "SPEC_VIOLATION":
    case "FAILED":
      return "wrong";
    case "REJECTED":
      return rejection?.response === "STAND_BY" && !rejection.decision ? "your-call" : "agent-turn";
    default:
      return "pending";
  }
}

export interface StageTally {
  size: number;
  /** Judged and settled: correct or wrong. */
  settled: number;
  wrong: number;
  /** Awaiting the reviewer. */
  toJudge: number;
  /** On the rejection response path (the agent's turn or the buyer's decision). */
  inProgress: number;
}

export function tally(draw: SampleDrawDTO, items: Map<number, TaskItemDTO>, inspection: InspectionDTO): StageTally {
  const t: StageTally = { size: draw.itemNos.length, settled: 0, wrong: 0, toJudge: 0, inProgress: 0 };
  for (const n of draw.itemNos) {
    const s = standing(items.get(n), latestRejection(inspection, n));
    if (s === "correct") t.settled++;
    else if (s === "wrong") {
      t.settled++;
      t.wrong++;
    } else if (s === "to-judge") t.toJudge++;
    else if (s === "agent-turn" || s === "your-call") t.inProgress++;
  }
  return t;
}

function items(n: number): string {
  return `${n} ${n === 1 ? "Item" : "Items"}`;
}

/**
 * The stage's rule in plain words: how many wrong Items accept the lot, and what more does. Wrong
 * Items found in the first sample count again in the second.
 */
export function ruleText(draw: SampleDrawDTO): string {
  if (draw.stage === 2) {
    return `Across both samples, ${draw.secondAccept === 0 ? "no wrong Item" : `up to ${items(draw.secondAccept)} wrong`} `
      + `accepts every Item still awaiting review; ${draw.secondReject} or more fails the sample.`;
  }
  const accept = draw.firstAccept === 0 ? "No wrong Item" : `Up to ${items(draw.firstAccept)} wrong`;
  if (draw.secondSampleSize === 0) {
    return `${accept} accepts every Item still awaiting review; ${draw.firstReject} or more fails the sample.`;
  }
  const between = draw.firstReject - draw.firstAccept === 2
    ? `${draw.firstAccept + 1}`
    : `${draw.firstAccept + 1} to ${draw.firstReject - 1}`;
  return `${accept} accepts every Item still awaiting review; ${between} wrong draws a second sample of `
    + `${draw.secondSampleSize}; ${draw.firstReject} or more fails the sample.`;
}

/** The D19 path, step by step, with where this inspection is on it. */
export interface Step {
  key: "sample" | "second" | "rework" | "resample" | "pro-rata";
  label: string;
  state: "done" | "current" | "upcoming" | "skipped";
}

export function path(inspection: InspectionDTO): Step[] {
  const r1 = inspection.draws.filter((d) => d.round === 1);
  const r2 = inspection.draws.filter((d) => d.round === 2);
  const second1 = r1.find((d) => d.stage === 2);
  const reworked = inspection.round === 2 || r1.some((d) => d.decision === "REWORK");
  const proRata = inspection.status === "PRO_RATA";
  const finished = inspection.status === "ACCEPTED" || proRata || inspection.status === "WAIVED";
  // Once the inspection ended, a step that never happened was skipped, never still to come.
  const state = (done: boolean, current: boolean, skipped = false): Step["state"] =>
    done ? "done" : skipped || finished ? "skipped" : current ? "current" : "upcoming";
  const firstDone = r1[0]?.decision != null;
  return [
    { key: "sample", label: "First sample", state: state(firstDone, !firstDone && inspection.round === 1) },
    {
      key: "second",
      label: "Second sample",
      state: state(second1?.decision != null, !!second1 && second1.decision == null,
        firstDone && !second1 && (reworked || finished)),
    },
    {
      key: "rework",
      label: "Whole-task rework",
      state: state(r2.length > 0, inspection.status === "REWORKING", finished && !reworked && !proRata),
    },
    {
      key: "resample",
      label: "Resample",
      state: state(r2.length > 0 && r2.every((d) => d.decision != null), r2.some((d) => d.decision == null),
        finished && !reworked && !proRata),
    },
    { key: "pro-rata", label: "Pro-rata settlement", state: state(proRata, false, finished && !proRata) },
  ];
}
