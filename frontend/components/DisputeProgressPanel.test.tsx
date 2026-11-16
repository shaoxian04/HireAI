import { describe, it, expect, vi, beforeAll, afterAll, afterEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "@/test/msw/handlers";
import { DisputeProgressPanel } from "@/components/DisputeProgressPanel";
import type { DisputeOutcomeDTO } from "@/lib/types";

beforeAll(() => server.listen({ onUnhandledRequest: "warn" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const NOW = new Date("2026-11-16T10:00:00Z");

const ruledAgainstBuilder: DisputeOutcomeDTO = {
  disputeId: "d-1",
  taskId: "t-1",
  status: "RULED",
  reasonCategory: "A_MISMATCH",
  effectiveCategory: "NOT_FULFILLED",
  rulings: [
    {
      tier: 1,
      decidedBy: "ARBITRATOR",
      category: "NOT_FULFILLED",
      rationale: "The summary covers the wrong quarter.",
      decidedAt: "2026-11-16T09:00:00Z",
    },
  ],
  yourSides: ["BUILDER"],
  awaiting: ["BUILDER"],
  appealedBy: null,
  appealDeadline: "2026-11-17T09:00:00Z",
  arbitrationCost: 2,
  appealFee: 2,
  fees: [],
  clientReason: "wrong quarter",
  agentResponse: "STAND_BY",
  agentReason: "the quarter is the one in the brief",
  agentSilent: false,
};

describe("DisputeProgressPanel", () => {
  it("lets the builder accept or appeal a ruling against it, and says what an appeal costs", () => {
    render(<DisputeProgressPanel outcome={ruledAgainstBuilder} onChange={() => {}} now={NOW} />);

    expect(screen.getByText(/the client rejected the result/i)).toBeTruthy();
    expect(screen.getByText("“wrong quarter”")).toBeTruthy();
    expect(screen.getByText(/your agent stood by its result/i)).toBeTruthy();
    expect(screen.getByText("“the quarter is the one in the brief”")).toBeTruthy();
    expect(screen.getByRole("button", { name: /accept ruling/i })).toBeTruthy();
    expect(screen.getByRole("button", { name: /appeal to a human/i })).toBeTruthy();
    expect(screen.getByText(/2\.00 cr appeal fee/i)).toBeTruthy();
    expect(screen.getByText("23h left")).toBeTruthy();
  });

  it("tells the side a ruling favours that it is waiting on the other side, with no actions", () => {
    render(
      <DisputeProgressPanel
        outcome={{ ...ruledAgainstBuilder, yourSides: ["CLIENT"] }}
        onChange={() => {}}
        now={NOW}
      />,
    );

    expect(screen.getByText(/you rejected the result/i)).toBeTruthy();
    expect(screen.getByText(/in your favour/i)).toBeTruthy();
    expect(screen.getByText(/waiting for the builder/i)).toBeTruthy();
    expect(screen.queryByRole("button", { name: /appeal/i })).toBeNull();
  });

  it("asks for confirmation before appealing, then shows the escalated dispute", async () => {
    const onChange = vi.fn();
    server.use(
      http.post("*/api/disputes/d-1/appeal", () =>
        HttpResponse.json({
          success: true,
          code: "OK",
          message: null,
          data: { ...ruledAgainstBuilder, status: "ESCALATED", appealedBy: "BUILDER", awaiting: [] },
        }),
      ),
    );
    render(<DisputeProgressPanel outcome={ruledAgainstBuilder} onChange={onChange} now={NOW} />);

    await userEvent.click(screen.getByRole("button", { name: /appeal to a human/i }));
    expect(screen.getByRole("dialog")).toBeTruthy();
    await userEvent.click(screen.getByRole("button", { name: /^appeal$/i }));

    await waitFor(() => expect(onChange).toHaveBeenCalled());
    expect(onChange.mock.calls[0][0].appealedBy).toBe("BUILDER");
  });

  it("shows who bore each fee once the dispute settled", () => {
    render(
      <DisputeProgressPanel
        outcome={{
          ...ruledAgainstBuilder,
          status: "RESOLVED",
          appealedBy: "BUILDER",
          awaiting: [],
          rulings: [
            ...ruledAgainstBuilder.rulings,
            { tier: 2, decidedBy: "ADMINISTRATOR", category: "NOT_FULFILLED", rationale: "upheld", decidedAt: "2026-11-16T09:30:00Z" },
          ],
          fees: [
            { type: "ARBITRATION_COST", fullCost: 3, cappedAmount: 2, clientCharged: 0, builderCharged: 2, platformCovered: 1 },
            { type: "APPEAL_FEE", fullCost: 5, cappedAmount: 2, clientCharged: 0, builderCharged: 0.5, platformCovered: 4.5 },
          ],
        }}
        onChange={() => {}}
        now={NOW}
      />,
    );

    expect(screen.getByText(/you appealed/i)).toBeTruthy();
    expect(screen.getByText("Arbitration cost")).toBeTruthy();
    expect(screen.getByText("Appeal fee")).toBeTruthy();
    expect(screen.getByText("You paid 2.00 cr · platform covered 1.00 cr")).toBeTruthy();
    expect(screen.getByText("You paid 0.50 cr · platform covered 4.50 cr")).toBeTruthy();
  });

  it("says nobody pays the arbitration cost when the arbitrator gave no ruling", () => {
    const noAiRuling: DisputeOutcomeDTO = {
      ...ruledAgainstBuilder, status: "ESCALATED", effectiveCategory: null, rulings: [], awaiting: [],
      appealDeadline: null, arbitrationCost: 0, arbitrationCostWaived: true,
    };
    const { unmount } = render(<DisputeProgressPanel outcome={noAiRuling} onChange={() => {}} now={NOW} />);
    expect(screen.getByText(/couldn.t give a ruling.*nobody pays the arbitration cost/i)).toBeTruthy();
    unmount();

    render(
      <DisputeProgressPanel
        outcome={{
          ...noAiRuling, status: "RESOLVED", effectiveCategory: "PARTIALLY_FULFILLED",
          rulings: [{ tier: 2, decidedBy: "ADMINISTRATOR", category: "PARTIALLY_FULFILLED", rationale: "half",
            decidedAt: "2026-11-16T09:30:00Z" }],
          fees: [{ type: "ARBITRATION_COST", fullCost: 3, cappedAmount: 0, clientCharged: 0, builderCharged: 0,
            platformCovered: 3, waivedReason: "NO_AI_RULING" }],
        }}
        onChange={() => {}}
        now={NOW}
      />,
    );
    expect(screen.getByText("Nobody paid: the AI arbitrator gave no ruling, so the platform covered 3.00 cr")).toBeTruthy();
  });
});
