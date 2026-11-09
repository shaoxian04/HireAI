import { describe, expect, it } from "vitest";
import { formatDuration, moneyState } from "./format";

describe("formatDuration", () => {
  it("reads like speech at every scale", () => {
    expect(formatDuration(null)).toBe("—");
    expect(formatDuration(6.24)).toBe("6.2s");
    expect(formatDuration(42.4)).toBe("42s");
    expect(formatDuration(263)).toBe("4m 23s");
    expect(formatDuration(120)).toBe("2m");
    expect(formatDuration(4523)).toBe("1h 15m");
  });
});

describe("moneyState", () => {
  it("says where the budget is for each stage of a task", () => {
    expect(moneyState({ status: "EXECUTING", resolution: null }).label).toBe("in escrow");
    expect(moneyState({ status: "DISPUTED", resolution: null }).label).toBe("in escrow");
    expect(moneyState({ status: "RESOLVED", resolution: "ACCEPTED" }).label).toBe("paid out");
    expect(moneyState({ status: "RESOLVED", resolution: "PARTIALLY_ACCEPTED" }).label).toBe("split");
    expect(moneyState({ status: "RESOLVED", resolution: "REJECTED" }).label).toBe("refunded");
    expect(moneyState({ status: "TIMED_OUT", resolution: null }).label).toBe("refunded");
  });
});
