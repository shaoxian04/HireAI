import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { server } from "./msw/handlers";
import { AuthProvider } from "@/lib/auth";
import MarketplacePage from "@/app/client/page";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  useParams: () => ({}),
  useSearchParams: () => new URLSearchParams(),
}));

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => {
  server.resetHandlers();
  localStorage.clear();
});
afterAll(() => server.close());

function renderMarketplace() {
  localStorage.setItem("hireai.token", "t");
  localStorage.setItem("hireai.auth", JSON.stringify({ userId: "u-1", role: "CLIENT" }));
  return render(
    <AuthProvider>
      <MarketplacePage />
    </AuthProvider>,
  );
}

describe("marketplace", () => {
  it("lists hot agents with category chips and a storefront link", async () => {
    renderMarketplace();
    // Card appears in both the hot strip and the all-agents grid (CARD.featured=true)
    expect(await screen.findAllByText("Summariser Bot")).not.toHaveLength(0);
    expect(screen.getAllByText(/by builder/i)[0]).toBeInTheDocument();
    expect(await screen.findByRole("button", { name: /translation/i })).toBeInTheDocument();
    // At least one link points to the storefront
    expect(screen.getAllByRole("link", { name: /summariser bot/i })[0]).toHaveAttribute(
      "href",
      "/client/agents/ag-1",
    );
  });

  it("search narrows results and an empty result state shows", async () => {
    renderMarketplace();
    await screen.findAllByText("Summariser Bot");
    await userEvent.type(screen.getByPlaceholderText(/search agents/i), "zzz-no-match");
    expect(await screen.findByText(/no agents match/i)).toBeInTheDocument();
  });

  it("category chip filters the grid", async () => {
    renderMarketplace();
    await screen.findAllByText("Summariser Bot");
    await userEvent.click(await screen.findByRole("button", { name: /translation/i }));
    expect(await screen.findByText(/no agents match/i)).toBeInTheDocument();
  });
});
