import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { server } from "./msw/handlers";
import { DisputeProgressPanel } from "@/components/DisputeProgressPanel";
import type { DisputeOutcomeDTO } from "@/lib/types";

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => {
  server.resetHandlers();
  localStorage.clear();
});
afterAll(() => server.close());

const arbRuling = {
  tier: 1,
  decidedBy: "ARBITRATOR" as const,
  category: "NOT_FULFILLED" as const,
  rationale: "Output is unrelated to the article.",
  decidedAt: "2026-07-03T10:00:00Z",
};

const ruled: DisputeOutcomeDTO = {
  disputeId: "d-1",
  taskId: "t-1",
  status: "RULED",
  reasonCategory: "B_FACTUAL",
  effectiveCategory: "NOT_FULFILLED",
  rulings: [arbRuling],
};

function renderPanel(outcome: DisputeOutcomeDTO, onChange = vi.fn()) {
  return render(
    <DisputeProgressPanel outcome={outcome} rejectionReason="This is off-topic." onChange={onChange} />,
  );
}

describe("DisputeProgressPanel (dispute timeline)", () => {
  it("always shows the reject stage with the reason category + the client's note", () => {
    renderPanel(ruled);
    expect(screen.getByText(/you rejected the result/i)).toBeInTheDocument();
    expect(screen.getByText(/factually wrong/i)).toBeInTheDocument();
    expect(screen.getByText(/this is off-topic/i)).toBeInTheDocument();
  });

  it("RULED: arbitrator 'proposed' with accept/appeal actions; admin pending", () => {
    renderPanel(ruled);
    expect(screen.getByText(/arbitrator proposed/i)).toBeInTheDocument();
    expect(screen.getByText(/only if you appeal/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /accept ruling/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /appeal to a human/i })).toBeInTheDocument();
  });

  it("ARBITRATING: arbitrator reviewing, no action buttons", () => {
    renderPanel({ ...ruled, status: "ARBITRATING", effectiveCategory: null, rulings: [] });
    expect(screen.getByText(/reviewing/i)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /accept/i })).toBeNull();
  });

  it("ESCALATED: administrator stage awaits a human; no actions", () => {
    renderPanel({ ...ruled, status: "ESCALATED", effectiveCategory: null });
    expect(screen.getByText(/awaiting a human administrator/i)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /accept/i })).toBeNull();
  });

  it("RESOLVED via admin: shows both rulings + the final outcome; no actions", () => {
    const resolved: DisputeOutcomeDTO = {
      ...ruled,
      status: "RESOLVED",
      effectiveCategory: "FULFILLED",
      rulings: [
        arbRuling,
        {
          tier: 2,
          decidedBy: "ADMINISTRATOR",
          category: "FULFILLED",
          rationale: "Human override on appeal.",
          decidedAt: "2026-07-03T11:00:00Z",
        },
      ],
    };
    renderPanel(resolved);
    expect(screen.getByText(/resolved/i)).toBeInTheDocument();
    expect(screen.getByText(/arbitrator ruled/i)).toBeInTheDocument();
    expect(screen.getByText(/administrator ruled/i)).toBeInTheDocument();
    expect(screen.getByText(/human override/i)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /accept/i })).toBeNull();
  });

  it("RESOLVED via accept (no admin ruling): admin stage marked not needed", () => {
    renderPanel({ ...ruled, status: "RESOLVED", effectiveCategory: "NOT_FULFILLED" });
    expect(screen.getByText(/not needed/i)).toBeInTheDocument();
  });

  it("RULED: clicking Accept ruling posts and reports the refreshed outcome", async () => {
    const onChange = vi.fn();
    renderPanel(ruled, onChange);
    fireEvent.click(screen.getByRole("button", { name: /accept ruling/i }));
    await waitFor(() => expect(onChange).toHaveBeenCalledTimes(1));
    const next = onChange.mock.calls[0][0] as DisputeOutcomeDTO;
    expect(next.disputeId).toBe("d-1");
    expect(next.status).toBe("RESOLVED");
  });

  it("RULED: clicking Appeal posts and reports the escalated outcome", async () => {
    const onChange = vi.fn();
    renderPanel(ruled, onChange);
    fireEvent.click(screen.getByRole("button", { name: /appeal to a human/i }));
    await waitFor(() => expect(onChange).toHaveBeenCalledTimes(1));
    const next = onChange.mock.calls[0][0] as DisputeOutcomeDTO;
    expect(next.status).toBe("ESCALATED");
  });
});
