import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { SampleItemCard } from "@/components/sampling/SampleItemCard";
import { PlanFacts, InspectionOutcome } from "@/components/sampling/InspectionSummary";
import type { InspectionDTO, RejectionDTO, TaskDTO, TaskItemDTO } from "@/lib/types";

const NOW = new Date("2026-11-18T10:00:00Z");
const ITEM: TaskItemDTO = { itemNo: 7, input: "Invoice INV-007 from Acme, total 120.00", status: "PENDING_REVIEW",
  price: 4, resultPayload: "total: 102.00" };

function card(over: Partial<Parameters<typeof SampleItemCard>[0]> = {}) {
  const onJudge = vi.fn().mockResolvedValue(undefined);
  const onAccept = vi.fn().mockResolvedValue(undefined);
  render(<ol><SampleItemCard taskId="t-1" item={ITEM} rejection={null} sampled canJudge busy={false} now={NOW}
    onJudge={onJudge} onAccept={onAccept} {...over} /></ol>);
  return { onJudge, onAccept };
}

describe("SampleItemCard", () => {
  it("shows the source beside the agent's result and passes a correct Item", async () => {
    const { onJudge } = card();
    expect(screen.getByText("Invoice INV-007 from Acme, total 120.00")).toBeInTheDocument();
    expect(screen.getAllByText("total: 102.00").length).toBeGreaterThan(0);
    await userEvent.click(screen.getByRole("button", { name: "Item 7 is correct" }));
    expect(onJudge).toHaveBeenCalledWith(7, { passed: true });
  });

  it("needs a reason before it rejects a wrong Item", async () => {
    const { onJudge } = card();
    await userEvent.click(screen.getByRole("button", { name: "Item 7 is wrong" }));
    const reject = screen.getByRole("button", { name: "Reject Item 7" });
    expect(reject).toBeDisabled();
    await userEvent.type(screen.getByRole("textbox"), "the total is 120.00");
    await userEvent.click(reject);
    expect(onJudge).toHaveBeenCalledWith(7, { passed: false, reasonCategory: "B_FACTUAL", reason: "the total is 120.00" });
  });

  it("marks an extra check as outside the sample", () => {
    card({ sampled: false });
    expect(screen.getByText(/doesn.t count towards the sample/)).toBeInTheDocument();
  });

  it("lets the buyer accept an Item the agent stood by, and says arbitration isn't available yet", async () => {
    const rejection: RejectionDTO = { round: 1, reasonCategory: "B_FACTUAL", reason: "wrong total", rejectedAt: "",
      responseDeadline: "2026-11-18T12:00:00Z", revisionAllowed: true, response: "STAND_BY",
      responseReason: "the total is on page 2", responseSilent: false, decisionDeadline: "2026-11-19T10:00:00Z",
      decisionSilent: false };
    const { onAccept } = card({ item: { ...ITEM, status: "REJECTED" }, rejection });
    expect(screen.getByText(/the total is on page 2/)).toBeInTheDocument();
    expect(screen.getByText(/single Item isn.t available yet/)).toBeInTheDocument();
    expect(screen.getByText("24h left")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Accept the Item" }));
    expect(onAccept).toHaveBeenCalledWith(7);
  });
});

const INSPECTION: InspectionDTO = {
  level: "II", round: 2, status: "PRO_RATA", errorRate: 0.25, aql: 2.5, judgements: [], itemRejections: [],
  draws: [
    { round: 1, stage: 1, seed: "-4411", codeLetter: "F", lotSize: 100, firstSampleSize: 13, secondSampleSize: 13,
      firstAccept: 0, firstReject: 2, secondAccept: 1, secondReject: 2, fullInspection: false, itemNos: [1],
      drawnAt: "2026-11-18T10:00:00Z", defects: 2, decision: "REWORK" },
    { round: 2, stage: 1, seed: "99", codeLetter: "E", lotSize: 87, firstSampleSize: 8, secondSampleSize: 8,
      firstAccept: 0, firstReject: 2, secondAccept: 1, secondReject: 2, fullInspection: false,
      itemNos: [2, 3, 4, 5, 6, 7, 8, 9], drawnAt: "2026-11-18T11:00:00Z", defects: 2, decision: "PRO_RATA" },
  ],
};

describe("inspection summary", () => {
  it("states the plan and the seed", () => {
    render(<PlanFacts inspection={INSPECTION} draw={INSPECTION.draws[1]} />);
    expect(screen.getByText("87 Items")).toBeInTheDocument();
    expect(screen.getByText(/letter E · AQL 2.5/)).toBeInTheDocument();
    expect(screen.getByText("99")).toBeInTheDocument();
  });

  it("explains a pro-rata settlement from the measured error rate", () => {
    render(<InspectionOutcome inspection={INSPECTION} task={{ status: "RESOLVED", payoutAmount: 201.45,
      commissionAmount: 35.55, refundAmount: 163 } as TaskDTO} />);
    expect(screen.getByText(/error rate is 25.00% \(2 of 8 sampled\)/)).toBeInTheDocument();
    expect(screen.getByText(/paid 75.00% of its price/)).toBeInTheDocument();
    expect(screen.getByText(/201.45 cr paid/)).toBeInTheDocument();
  });
});
