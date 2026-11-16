import { fireEvent, render, screen } from "@testing-library/react";
import { useState } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AiReviewOption, filledCriteria } from "./AiReviewOption";
import type { AiReviewQuoteDTO } from "@/lib/types";

const apiMock = vi.fn();
vi.mock("@/lib/api", async () => {
  const actual = await vi.importActual<typeof import("@/lib/api")>("@/lib/api");
  return { ...actual, api: (path: string, init?: RequestInit) => apiMock(path, init) };
});

function Harness({ accepts = true, onQuote = () => {} }: { accepts?: boolean; onQuote?: (q: AiReviewQuoteDTO | null) => void }) {
  const [criteria, setCriteria] = useState<string[] | null>(null);
  return (
    <AiReviewOption agentId="a1" budget={10} offerAccepts={accepts} criteria={criteria} onChange={setCriteria} onQuote={onQuote} />
  );
}

describe("AiReviewOption", () => {
  beforeEach(() => {
    apiMock.mockReset();
  });

  it("says so when the agent's Offer does not accept AI Review", () => {
    render(<Harness accepts={false} />);
    expect(screen.getByText(/This agent doesn't accept AI Review/)).toBeInTheDocument();
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
  });

  it("prices the review once it is turned on and reports the fee to freeze", async () => {
    apiMock.mockResolvedValue({ fee: 0.2, maxFee: 1.2, total: 12.2, maxFeeShare: 0.12, available: true, reason: null, maxCriteria: 5 });
    const onQuote = vi.fn();
    render(<Harness onQuote={onQuote} />);

    fireEvent.click(screen.getByRole("checkbox", { name: /Add an AI Review/ }));
    expect(screen.getByRole("status")).toHaveTextContent("Pricing the review");
    expect(await screen.findByText("0.20 cr")).toBeInTheDocument();
    // The share is the server's, never a hard-coded 10%.
    expect(screen.getByText(/frozen with the price \(at most 1\.20 cr, 12% of it\)/)).toBeInTheDocument();
    expect(onQuote).toHaveBeenLastCalledWith(expect.objectContaining({ fee: 0.2 }));
    const [path, init] = apiMock.mock.calls[0];
    expect(path).toBe("/tasks/direct/ai-review-quote");
    expect(JSON.parse(init.body)).toEqual({ agentId: "a1", budget: 10, criteria: 1 });

    fireEvent.change(screen.getByLabelText("AI Review criterion 1"), { target: { value: "Polite" } });
    fireEvent.click(screen.getByRole("button", { name: "Add a criterion" }));
    expect(screen.getByLabelText("AI Review criterion 2")).toBeInTheDocument();
  });

  it("shows why it isn't offered and freezes nothing for it", async () => {
    apiMock.mockResolvedValue({ fee: 0.3, maxFee: 0.29, total: 3.2, maxFeeShare: 0.1, available: false,
      reason: "AI Review would cost 0.30, more than 10% of the Item price (0.29), so it is not offered", maxCriteria: 5 });
    const onQuote = vi.fn();
    render(<Harness onQuote={onQuote} />);
    fireEvent.click(screen.getByRole("checkbox", { name: /Add an AI Review/ }));
    expect(await screen.findByRole("alert")).toHaveTextContent("more than 10% of the Item price");
    expect(onQuote).toHaveBeenLastCalledWith(null);
  });

  it("sends only the criteria that were written", () => {
    expect(filledCriteria([" Polite ", "", "  "])).toEqual(["Polite"]);
  });
});
