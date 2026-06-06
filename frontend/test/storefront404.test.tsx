import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { server } from "./msw/handlers";
import { AuthProvider } from "@/lib/auth";
import StorefrontPage from "@/app/client/agents/[id]/page";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  useParams: () => ({ id: "ag-404" }),
  useSearchParams: () => new URLSearchParams(),
}));

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => {
  server.resetHandlers();
  localStorage.clear();
});
afterAll(() => server.close());

describe("agent storefront — 404", () => {
  it("shows an alert when the agent is not found", async () => {
    localStorage.setItem("hireai.token", "t");
    localStorage.setItem("hireai.auth", JSON.stringify({ userId: "u-1", role: "CLIENT" }));
    render(
      <AuthProvider>
        <StorefrontPage />
      </AuthProvider>,
    );
    expect(await screen.findByRole("alert")).toHaveTextContent(/agent not found/i);
  });
});
