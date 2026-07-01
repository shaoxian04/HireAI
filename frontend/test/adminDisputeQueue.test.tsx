import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { server } from "./msw/handlers";
import { AuthProvider } from "@/lib/auth";
import AdminDisputesPage from "@/app/admin/disputes/page";

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

describe("admin dispute queue", () => {
  it("lists a needs-attention dispute with its task title", async () => {
    render(
      <AuthProvider>
        <AdminDisputesPage />
      </AuthProvider>,
    );
    expect(await screen.findByText("Summarise the Q3 report")).toBeInTheDocument();
    expect(await screen.findByText("ESCALATED")).toBeInTheDocument();
  });
});
