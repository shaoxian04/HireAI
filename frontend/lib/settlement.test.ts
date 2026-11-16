import { describe, expect, it } from "vitest";
import { settlementStamp } from "@/lib/settlement";
import type { TaskDTO, TaskItemDTO } from "@/lib/types";

const task = (over: Partial<TaskDTO>): TaskDTO => ({
  id: "t", clientId: "c", title: "x", description: "d", budget: 10, status: "RESOLVED",
  outputSpec: { format: "TEXT" }, createdAt: "2026-11-18T09:00:00Z", ...over,
}) as TaskDTO;

const items = (...statuses: TaskItemDTO["status"][]): TaskItemDTO[] =>
  statuses.map((status, i) => ({ itemNo: i + 1, input: "x", status, price: 4 }));

describe("settlementStamp", () => {
  it("stamps an accepted task PAID, saying how it was accepted", () => {
    expect(settlementStamp(task({ resolution: "ACCEPTED" }))).toEqual({ stamp: "PAID", note: "work accepted", tone: "paid" });
    expect(settlementStamp(task({ resolution: "ACCEPTED", autoAccepted: true })).note).toBe("auto-accepted");
  });

  it("stamps a rejected task REFUNDED", () => {
    expect(settlementStamp(task({ resolution: "REJECTED" }))).toEqual({ stamp: "REFUNDED", note: "work rejected", tone: "refunded" });
  });

  it("never stamps a split ruling as refunded work", () => {
    const s = settlementStamp(task({ resolution: "PARTIALLY_ACCEPTED", payoutAmount: 4.25, refundAmount: 5 }));
    expect(s.stamp).toBe("SPLIT");
    expect(s.note).toBe("partly fulfilled");
    expect(s.tone).toBe("split");
  });

  it("counts the accepted Items of a multi-Item task, and stamps one with nothing paid REFUNDED", () => {
    expect(settlementStamp(task({ resolution: "ACCEPTED", itemCount: 2, items: items("ACCEPTED", "ACCEPTED") })))
      .toEqual({ stamp: "PAID", note: "2 of 2 accepted", tone: "paid" });
    expect(settlementStamp(task({ resolution: "PARTIALLY_ACCEPTED", itemCount: 3,
      items: items("ACCEPTED", "SPEC_VIOLATION", "CONCEDED") })))
      .toEqual({ stamp: "PART PAID", note: "1 of 3 accepted", tone: "split" });
    expect(settlementStamp(task({ resolution: "REJECTED", itemCount: 2, items: items("SPEC_VIOLATION", "FAILED") })))
      .toEqual({ stamp: "REFUNDED", note: "no Item accepted", tone: "refunded" });
  });

  it("stamps a pro-rata settlement as such", () => {
    expect(settlementStamp(task({ resolution: "PARTIALLY_ACCEPTED", itemCount: 3,
      items: items("ACCEPTED", "PRO_RATA", "PRO_RATA") })))
      .toEqual({ stamp: "PRO RATA", note: "1 accepted, 2 pro rata", tone: "split" });
  });
});
