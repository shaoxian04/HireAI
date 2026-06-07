import type { TaskStatus } from "./types";

/** The six-stage money path every task travels (mirrors the domain lifecycle + invariants). */
export const STAGES = ["SUBMIT", "ESCROW", "ROUTE", "EXECUTE", "VALIDATE", "SETTLE"] as const;

export type StageKind = "active" | "wait" | "done" | "fail";

export interface StageState {
  /** Index (0–5) of the stage the task currently sits at. */
  index: number;
  kind: StageKind;
}

/** Map a backend TaskStatus onto a position + tone in the SUBMIT→SETTLE pipeline. */
export function taskStage(status: TaskStatus): StageState {
  switch (status) {
    case "SUBMITTED":
    case "QUEUED":
      return { index: 2, kind: "active" }; // escrow frozen on submit; now routing
    case "AWAITING_CAPACITY":
      return { index: 2, kind: "wait" };
    case "EXECUTING":
      return { index: 3, kind: "active" };
    case "RESULT_RECEIVED":
      return { index: 4, kind: "active" }; // result in, validation runs
    case "PENDING_REVIEW":
      return { index: 4, kind: "wait" };
    case "RESOLVED":
      return { index: 5, kind: "done" };
    case "FAILED":
      return { index: 3, kind: "fail" };
    case "TIMED_OUT":
    case "SPEC_VIOLATION":
      return { index: 4, kind: "fail" };
    case "CANCELLED":
      return { index: 2, kind: "fail" };
    default:
      return { index: 0, kind: "active" };
  }
}
