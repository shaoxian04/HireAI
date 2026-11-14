import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http } from "msw";
import { server, ok } from "./msw/handlers";
import { AuthProvider } from "@/lib/auth";
import BuilderPage from "@/app/builder/page";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  useParams: () => ({}),
  usePathname: () => "/builder",
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
  it("shows the wallet balance tile linking to the earnings page", async () => {
    renderBuilder();
    // From the MSW /api/wallet stub: availableBalance 950.
    expect(await screen.findByText("950.00")).toBeInTheDocument();
    expect(screen.getByText(/wallet balance/i)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /view earnings/i })).toHaveAttribute(
      "href",
      "/builder/earnings",
    );
  });

  it("shows builder nav links", async () => {
    renderBuilder();
    expect(await screen.findByRole("link", { name: "Earnings" })).toHaveAttribute(
      "href",
      "/builder/earnings",
    );
    expect(screen.getByRole("link", { name: "My agents" })).toHaveAttribute("href", "/builder");
  });
});

describe("builder agent list", () => {
  it("lists an agent and activates it", async () => {
    renderBuilder();
    expect(await screen.findByText("Summariser")).toBeInTheDocument();
    expect(screen.getByText("Not activated")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: /activate/i }));

    expect(await screen.findByText("Active", { selector: "[data-status=ACTIVE]" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /activate/i })).not.toBeInTheDocument();
    // and says so
    expect(screen.getByRole("status")).toHaveTextContent(/summariser is active/i);
  });

  it("narrows the list by search and shows a way out when nothing matches", async () => {
    renderBuilder();
    expect(await screen.findByText("Summariser")).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText(/find an agent/i), "zzz-no-such-agent");
    expect(screen.queryByText("Summariser")).not.toBeInTheDocument();
    expect(screen.getByText(/no agents match/i)).toBeInTheDocument();
    await userEvent.clear(screen.getByLabelText(/find an agent/i));
    expect(screen.getByText("Summariser")).toBeInTheDocument();
  });
});

describe("builder unlisted warning", () => {
  it("warns that an unlisted agent receives no work and points to its storefront", async () => {
    renderBuilder();
    expect(await screen.findByText("Summariser")).toBeInTheDocument();
    const warning = screen.getByRole("note", { name: /unlisted agents/i });
    expect(warning).toHaveTextContent(/1 of your agents is not listed/i);
    expect(warning).toHaveTextContent(/receives no work/i);
    expect(screen.getByRole("link", { name: /list summariser/i })).toHaveAttribute("href", "/builder/agents/a-1");
  });

  it("shows no warning when every agent is listed", async () => {
    server.use(
      http.get("*/api/agents", () =>
        ok([
          {
            id: "a-2",
            ownerId: "u-1",
            name: "Listed one",
            status: "ACTIVE",
            currentVersionId: "v-2",
            reputationScore: 50,
            currentVersion: {
              capabilityCategories: ["summarisation"],
              price: 10,
              webhookUrl: "https://agent.example.com/run",
              maxExecutionSeconds: 60,
              outputSpec: { format: "JSON", schema: "{}", acceptanceCriteria: "valid JSON" },
            },
            createdAt: "2026-06-06T10:00:00Z",
            hasSigningSecret: true,
            listed: true,
          },
        ]),
      ),
    );
    renderBuilder();
    expect(await screen.findByText("Listed one")).toBeInTheDocument();
    expect(screen.queryByRole("note", { name: /unlisted agents/i })).not.toBeInTheDocument();
    expect(screen.queryByText(/not listed/i)).not.toBeInTheDocument();
  });
});
