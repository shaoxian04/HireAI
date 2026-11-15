import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { ResultReviewBar } from "@/components/ResultReviewBar";

const NOW = new Date("2026-11-15T10:00:00Z");

describe("ResultReviewBar review window", () => {
  it("offers accept and reject while the window is open, with the time left", () => {
    render(<ResultReviewBar taskId="t-1" budget={10} reviewDeadline="2026-11-16T10:00:00Z" now={NOW} onResolved={vi.fn()} />);
    expect(screen.getByText("24h left")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Accept result" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Reject" })).toBeTruthy();
  });

  it("takes the decision away once the window has closed", () => {
    render(<ResultReviewBar taskId="t-1" budget={10} reviewDeadline="2026-11-15T09:00:00Z" now={NOW} onResolved={vi.fn()} />);
    expect(screen.getByText(/review window has closed/i)).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Reject" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Accept result" })).toBeNull();
  });
});
