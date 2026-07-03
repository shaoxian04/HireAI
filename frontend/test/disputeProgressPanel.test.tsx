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

const ruled: DisputeOutcomeDTO = {
  disputeId: "d-1",
  taskId: "t-1",
  status: "RULED",
  effectiveCategory: "FULFILLED",
  rulings: [
    {
      tier: 1,
      decidedBy: "ARBITRATOR",
      category: "FULFILLED",
      rationale: "ok",
      decidedAt: "2026-07-03T10:00:00Z",
    },
  ],
};

describe("DisputeProgressPanel", () => {
  it("RULED: shows proposed ruling + accept/appeal", () => {
    render(<DisputeProgressPanel outcome={ruled} onChange={vi.fn()} />);
    expect(screen.getByText(/proposed ruling/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /accept/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /appeal/i })).toBeInTheDocument();
  });

  it("ARBITRATING: shows under-review, no buttons", () => {
    render(
      <DisputeProgressPanel outcome={{ ...ruled, status: "ARBITRATING", rulings: [] }} onChange={vi.fn()} />,
    );
    expect(screen.getByText(/under review/i)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /accept/i })).toBeNull();
  });

  it("RESOLVED: shows final outcome", () => {
    render(<DisputeProgressPanel outcome={{ ...ruled, status: "RESOLVED" }} onChange={vi.fn()} />);
    expect(screen.getByText(/resolved/i)).toBeInTheDocument();
  });

  it("RULED: clicking Accept ruling posts to accept-ruling and reports the refreshed outcome", async () => {
    const onChange = vi.fn();
    render(<DisputeProgressPanel outcome={ruled} onChange={onChange} />);

    fireEvent.click(screen.getByRole("button", { name: /accept ruling/i }));

    await waitFor(() => expect(onChange).toHaveBeenCalledTimes(1));
    const next = onChange.mock.calls[0][0] as DisputeOutcomeDTO;
    expect(next.disputeId).toBe("d-1");
    expect(next.status).toBe("RESOLVED");
  });

  it("RULED: clicking Appeal posts to appeal and reports the escalated outcome", async () => {
    const onChange = vi.fn();
    render(<DisputeProgressPanel outcome={ruled} onChange={onChange} />);

    fireEvent.click(screen.getByRole("button", { name: /appeal to a human/i }));

    await waitFor(() => expect(onChange).toHaveBeenCalledTimes(1));
    const next = onChange.mock.calls[0][0] as DisputeOutcomeDTO;
    expect(next.status).toBe("ESCALATED");
  });
});
