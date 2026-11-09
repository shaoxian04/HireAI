import { describe, expect, it } from "vitest";
import { disputeSteps } from "./DisputeTrack";
import type { RulingDTO } from "@/lib/types";

const arb: RulingDTO = { tier: 1, decidedBy: "ARBITRATOR", category: "FULFILLED", rationale: null, decidedAt: "2026-07-01T00:00:00Z" };
const admin: RulingDTO = { ...arb, tier: 2, decidedBy: "ADMINISTRATOR" };

describe("disputeSteps", () => {
  const states = (status: string, rulings: RulingDTO[] = []) => disputeSteps(status, rulings).map((s) => s.state);
  it("is with the arbitrator while it arbitrates", () => {
    expect(states("ARBITRATING")).toEqual(["done", "active", "todo", "todo", "todo"]);
  });
  it("waits on the client once ruled", () => {
    expect(states("RULED", [arb])).toEqual(["done", "done", "wait", "todo", "todo"]);
  });
  it("goes to the administrator when escalated", () => {
    expect(states("ESCALATED", [arb])).toEqual(["done", "done", "done", "active", "todo"]);
  });
  it("marks the administrator step skipped when settled without escalation", () => {
    expect(states("RESOLVED", [arb])).toEqual(["done", "done", "done", "skipped", "done"]);
    expect(states("RESOLVED", [arb, admin])).toEqual(["done", "done", "done", "done", "done"]);
  });
});
