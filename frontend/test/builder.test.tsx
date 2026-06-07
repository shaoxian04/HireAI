import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { server } from "./msw/handlers";
import { AuthProvider } from "@/lib/auth";
import BuilderPage from "@/app/builder/page";

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

function renderBuilder() {
  // Seed an authed BUILDER so RoleGuard renders the body: the raw JWT lives under
  // `hireai.token` (read by api() and RoleGuard) and the session {userId,role} under `hireai.auth`.
  localStorage.setItem("hireai.token", "t");
  localStorage.setItem("hireai.auth", JSON.stringify({ userId: "u-1", role: "BUILDER" }));
  return render(
    <AuthProvider>
      <BuilderPage />
    </AuthProvider>,
  );
}

describe("builder earnings", () => {
  it("shows the wallet's available credits as earnings", async () => {
    renderBuilder();
    // From the MSW /api/wallet stub: availableBalance 950.
    expect(await screen.findByText("950.00")).toBeInTheDocument();
    expect(screen.getByText("credits earned")).toBeInTheDocument();
  });
});

describe("builder agent list", () => {
  it("lists an agent and activates it", async () => {
    renderBuilder();
    expect(await screen.findByText("Summariser")).toBeInTheDocument();
    expect(screen.getByText("PENDING_VERIFICATION")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: /activate/i }));

    expect(await screen.findByText("ACTIVE")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /activate/i })).not.toBeInTheDocument();
  });
});
