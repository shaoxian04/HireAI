import { render, screen } from "@testing-library/react";
import { describe, it, expect } from "vitest";
import { DisputeOutcomePanel } from "./DisputeOutcomePanel";
import type { DisputeOutcomeDTO } from "@/lib/types";

const base: DisputeOutcomeDTO = {
  disputeId: "d-1",
  taskId: "t-1",
  status: "RESOLVED",
  effectiveCategory: "PARTIALLY_FULFILLED",
  rulings: [
    {
      tier: 1,
      decidedBy: "ARBITRATOR",
      category: "PARTIALLY_FULFILLED",
      rationale: "Output met sections A and B but omitted the requested summary.",
      decidedAt: "2026-07-01T10:00:00Z",
    },
  ],
};

describe("DisputeOutcomePanel", () => {
  it("renders the arbitrator decision label and rationale", () => {
    render(<DisputeOutcomePanel outcome={base} />);
    expect(screen.getByText(/Partially fulfilled/i)).toBeInTheDocument();
    expect(screen.getByText(/omitted the requested summary/i)).toBeInTheDocument();
  });

  it("shows a fallback note when the ruling was a platform fallback", () => {
    render(
      <DisputeOutcomePanel
        outcome={{
          ...base,
          effectiveCategory: "NOT_FULFILLED",
          rulings: [{ ...base.rulings[0], decidedBy: "FALLBACK", category: "NOT_FULFILLED" }],
        }}
      />,
    );
    expect(screen.getByText(/auto-resolved/i)).toBeInTheDocument();
    expect(screen.getByText(/full refund/i)).toBeInTheDocument();
  });
});
