import { describe, expect, it } from "vitest";
import { currentDraw, path, ruleText, standing, tally } from "@/lib/sampling";
import type { InspectionDTO, SampleDrawDTO, TaskItemDTO } from "@/lib/types";

const draw = (over: Partial<SampleDrawDTO> = {}): SampleDrawDTO => ({
  round: 1, stage: 1, seed: "42", codeLetter: "H", lotSize: 500, firstSampleSize: 32, secondSampleSize: 32,
  firstAccept: 1, firstReject: 3, secondAccept: 4, secondReject: 5, fullInspection: false,
  itemNos: [1, 2, 3], drawnAt: "2026-11-18T10:00:00Z", ...over,
});

const inspection = (over: Partial<InspectionDTO> = {}): InspectionDTO => ({
  level: "II", round: 1, status: "SAMPLING", aql: 2.5, draws: [draw()], judgements: [], itemRejections: [], ...over,
});

const item = (itemNo: number, status: TaskItemDTO["status"]): TaskItemDTO => ({ itemNo, input: "x", status, price: 1 });

describe("ruleText", () => {
  it("states a double plan's first stage", () => {
    expect(ruleText(draw())).toBe("Up to 1 Item wrong accepts every Item still awaiting review; 2 draws a second "
      + "sample of 32; 3 or more fails the sample.");
  });
  it("states the second stage on both samples", () => {
    expect(ruleText(draw({ stage: 2 }))).toBe("Across both samples, up to 4 Items wrong accepts every Item still "
      + "awaiting review; 5 or more fails the sample.");
  });
  it("states a single zero-acceptance plan", () => {
    expect(ruleText(draw({ secondSampleSize: 0, firstAccept: 0, firstReject: 1 })))
      .toBe("No wrong Item accepts every Item still awaiting review; 1 or more fails the sample.");
  });
});

describe("standing and tally", () => {
  it("counts a stood-by Item as the buyer's call and a conceded one as wrong", () => {
    const insp = inspection({
      itemRejections: [{ itemNo: 2, rejection: { round: 1, reasonCategory: "B_FACTUAL", rejectedAt: "", responseDeadline: "",
        revisionAllowed: true, response: "STAND_BY", responseSilent: false, decisionSilent: false } }],
    });
    const items = new Map([[1, item(1, "ACCEPTED")], [2, item(2, "REJECTED")], [3, item(3, "CONCEDED")]]);
    expect(standing(items.get(2), insp.itemRejections[0].rejection)).toBe("your-call");
    expect(tally(insp.draws[0], items, insp)).toEqual({ size: 3, settled: 2, wrong: 1, toJudge: 0, inProgress: 1 });
  });
});

describe("currentDraw and path", () => {
  it("is the undecided draw while sampling", () => {
    const insp = inspection({ draws: [draw({ decision: "DRAW_SECOND", defects: 2 }), draw({ stage: 2 })] });
    expect(currentDraw(insp)?.stage).toBe(2);
    expect(path(insp).map((s) => s.state)).toEqual(["done", "current", "upcoming", "upcoming", "upcoming"]);
  });
  it("skips what an accepted first sample never needed", () => {
    const insp = inspection({ status: "ACCEPTED", draws: [draw({ decision: "ACCEPT_LOT", defects: 0 })] });
    expect(path(insp).map((s) => s.state)).toEqual(["done", "skipped", "skipped", "skipped", "skipped"]);
  });
  it("walks the whole path to a pro-rata settlement", () => {
    const insp = inspection({
      round: 2, status: "PRO_RATA", errorRate: 0.25,
      draws: [draw({ decision: "REWORK", defects: 3 }), draw({ round: 2, decision: "PRO_RATA", defects: 3 })],
    });
    expect(path(insp).map((s) => s.state)).toEqual(["done", "skipped", "done", "done", "done"]);
  });
});
