import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { server } from "./msw/handlers";
import { AuthProvider } from "@/lib/auth";
import AdminOverviewPage from "@/app/admin/page";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  useParams: () => ({}),
}));

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
beforeEach(() => {
  localStorage.setItem("hireai.token", "t");
  localStorage.setItem("hireai.auth", JSON.stringify({ userId: "u-admin", roles: ["ADMIN"] }));
});
afterEach(() => {
  server.resetHandlers();
  localStorage.clear();
});
afterAll(() => server.close());

describe("admin overview", () => {
  it("renders escrow held and escalated-dispute count", async () => {
    render(
      <AuthProvider>
        <AdminOverviewPage />
      </AuthProvider>,
    );
    expect(await screen.findByText(/escrow held/i)).toBeInTheDocument();
    expect(await screen.findByText("20 cr")).toBeInTheDocument(); // escrowHeld from the mock
    expect(screen.getByText(/^escalated$/i)).toBeInTheDocument();
  });
});
