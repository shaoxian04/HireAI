import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { http } from "msw";
import { server, ok } from "./msw/handlers";
import { AuthProvider } from "@/lib/auth";
import EarningsPage from "@/app/builder/earnings/page";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  useParams: () => ({}),
}));

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => {
  server.resetHandlers();
  localStorage.clear();
});
afterAll(() => server.close());

function renderEarnings() {
  localStorage.setItem("hireai.token", "t");
  localStorage.setItem("hireai.auth", JSON.stringify({ userId: "u-1", role: "BUILDER" }));
  return render(
    <AuthProvider>
      <EarningsPage />
    </AuthProvider>,
  );
}

describe("builder earnings page", () => {
  it("renders totals, per-agent breakdown and payout history", async () => {
    renderEarnings();

    // summary tiles (from the MSW stub)
    expect(await screen.findByText("27.20")).toBeInTheDocument();
    expect(screen.getByText("17.00")).toBeInTheDocument();
    expect(screen.getByText("lifetime earned")).toBeInTheDocument();
    expect(screen.getByText("pending · if accepted")).toBeInTheDocument();

    // per-agent rows — zero-row agent included
    expect(screen.getByText("Analyst")).toBeInTheDocument();

    // payout history
    expect(screen.getByText("Summarize the article")).toBeInTheDocument();
    expect(screen.getByText("+10.20 cr")).toBeInTheDocument();
    expect(screen.getByText("+17.00 cr")).toBeInTheDocument();
  });

  it("shows the empty state when there are no payouts", async () => {
    server.use(
      http.get("*/api/builder/earnings", () =>
        ok({
          lifetimeEarned: 0,
          pendingIfAccepted: 0,
          paidTaskCount: 0,
          perAgent: [],
          payouts: [],
        }),
      ),
    );
    renderEarnings();

    expect(await screen.findByText(/No payouts yet/)).toBeInTheDocument();
  });
});
