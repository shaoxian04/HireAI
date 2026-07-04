import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { http } from "msw";
import { server, ok } from "./msw/handlers";
import { AuthProvider } from "@/lib/auth";
import ClientDisputesPage from "@/app/client/disputes/page";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  useParams: () => ({}),
}));

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
beforeEach(() => {
  localStorage.setItem("hireai.token", "t");
  localStorage.setItem("hireai.auth", JSON.stringify({ userId: "u-1", role: "CLIENT" }));
});
afterEach(() => {
  server.resetHandlers();
  localStorage.clear();
});
afterAll(() => server.close());

describe("client disputes list", () => {
  it("lists both disputes and flags the ruled one as awaiting the client's decision", async () => {
    server.use(
      http.get("*/api/disputes/mine", () =>
        ok([
          {
            disputeId: "d-1",
            taskId: "t-1",
            taskTitle: "Summarise Q2 report",
            status: "RULED",
            proposedCategory: "FULFILLED",
            updatedAt: "2026-07-03T10:00:00Z",
          },
          {
            disputeId: "d-2",
            taskId: "t-2",
            taskTitle: "Translate the brief",
            status: "RESOLVED",
            proposedCategory: "NOT_FULFILLED",
            updatedAt: "2026-07-01T10:00:00Z",
          },
        ]),
      ),
    );

    render(
      <AuthProvider>
        <ClientDisputesPage />
      </AuthProvider>,
    );

    expect(await screen.findByText("Summarise Q2 report")).toBeInTheDocument();
    expect(await screen.findByText("Translate the brief")).toBeInTheDocument();

    const rows = await screen.findAllByRole("row");
    // rows[0] is the header row; rows[1] is the first data row (RULED, per the MSW order above).
    expect(rows[1]).toHaveTextContent("Awaiting your decision");
  });
});
