/**
 * Tests for the arbitration outcome expand-on-click feature on the builder earnings page.
 *
 * Limitation documented here and in the implementation: disputes that resolved to a
 * FULL REFUND produce no payout row, so they are not discoverable on this view.
 * A future builder dispute-list endpoint would surface them — deferred.
 */
import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { http } from "msw";
import { server, ok, fail } from "../../../test/msw/handlers";
import { AuthProvider } from "@/lib/auth";
import EarningsPage from "@/app/builder/earnings/page";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  useParams: () => ({}),
}));

// The default earnings handler (handlers.ts) returns payouts with taskId "t-1" and "t-2".
// These tests add dispute handlers per-test and reset in afterEach.

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => {
  server.resetHandlers();
  localStorage.clear();
});
afterAll(() => server.close());

const DISPUTE_OUTCOME = {
  taskId: "t-1",
  status: "RESOLVED",
  effectiveCategory: "FULFILLED",
  rulings: [
    {
      tier: 1,
      decidedBy: "ARBITRATOR",
      category: "FULFILLED",
      rationale: "Output fully satisfied the spec requirements.",
      decidedAt: "2026-07-01T10:05:00Z",
    },
  ],
};

function renderEarnings() {
  localStorage.setItem("hireai.token", "t");
  localStorage.setItem("hireai.auth", JSON.stringify({ userId: "u-1", role: "BUILDER" }));
  return render(
    <AuthProvider>
      <EarningsPage />
    </AuthProvider>,
  );
}

describe("builder earnings — arbitration outcome on payout rows", () => {
  it("shows the arbitrator rationale after expanding a disputed payout row", async () => {
    server.use(
      http.get("*/api/disputes/by-task/t-1", () => ok(DISPUTE_OUTCOME)),
      http.get("*/api/disputes/by-task/t-2", () =>
        fail("NOT_FOUND", "No dispute", 404),
      ),
    );

    renderEarnings();

    // Wait for payouts to render (from the default MSW earnings handler)
    expect(await screen.findByText("Summarize the article")).toBeInTheDocument();

    // Each payout row exposes an "Arbitration outcome" toggle
    const buttons = screen.getAllByRole("button", { name: /arbitration outcome/i });
    expect(buttons.length).toBeGreaterThan(0);

    // Click the first row (taskId t-1, which has a dispute)
    fireEvent.click(buttons[0]);

    // Arbitrator rationale must appear
    expect(
      await screen.findByText(/fully satisfied the spec requirements/i),
    ).toBeInTheDocument();
  });

  it("shows no dispute panel when the payout has no recorded dispute (404)", async () => {
    server.use(
      http.get("*/api/disputes/by-task/t-1", () =>
        fail("NOT_FOUND", "No dispute", 404),
      ),
      http.get("*/api/disputes/by-task/t-2", () =>
        fail("NOT_FOUND", "No dispute", 404),
      ),
    );

    renderEarnings();
    expect(await screen.findByText("Summarize the article")).toBeInTheDocument();

    const buttons = screen.getAllByRole("button", { name: /arbitration outcome/i });
    fireEvent.click(buttons[0]);

    // No rationale or arbitration label should appear after a 404
    await waitFor(() => {
      expect(screen.queryByText(/rationale/i)).not.toBeInTheDocument();
      expect(screen.queryByText(/arbitrator · tier/i)).not.toBeInTheDocument();
    });
  });
});
