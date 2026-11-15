import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import { server } from "./msw/handlers";
import { AuthProvider } from "@/lib/auth";
import StorefrontPage from "@/app/client/agents/[id]/page";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  useParams: () => ({ id: "ag-1" }),
  useSearchParams: () => new URLSearchParams(),
  usePathname: () => "/client/agents/ag-1",
}));

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => {
  server.resetHandlers();
  localStorage.clear();
});
afterAll(() => server.close());

function renderWithClientAuth() {
  localStorage.setItem("hireai.token", "t");
  localStorage.setItem("hireai.auth", JSON.stringify({ userId: "u-1", role: "CLIENT" }));
  return render(
    <AuthProvider>
      <StorefrontPage />
    </AuthProvider>,
  );
}

describe("agent storefront", () => {
  it("renders profile content, output contract, stats, reviews and the book CTA", async () => {
    renderWithClientAuth();
    expect(await screen.findByText("Summariser Bot")).toBeInTheDocument();
    expect(screen.getByText(/summarises long documents/i)).toBeInTheDocument();
    expect(screen.getByText(/example output/i)).toBeInTheDocument(); // sample output block
    expect(screen.getByText(/valid json/i)).toBeInTheDocument(); // output contract
    expect(screen.getByText(/output matched the spec exactly/i)).toBeInTheDocument(); // review
    // The public contract-decline rate (#93), in the track record.
    expect(screen.getByText(/declined terms on/i)).toHaveTextContent("Declined terms on 1 of 6 tasks (17%)");
    expect(screen.getAllByRole("link", { name: /book this agent/i })[0]).toHaveAttribute(
      "href",
      "/client/agents/ag-1/book",
    );
  });

  it("shows the Offer: the Templates the agent accepts and the range of each parameter", async () => {
    renderWithClientAuth();
    const offer = await screen.findByRole("region", { name: /what this agent accepts/i });
    expect(await within(offer).findByText("File")).toBeInTheDocument();
    expect(within(offer).getByText("file_basic@1")).toBeInTheDocument();
    expect(within(offer).getByText("10 MB or more")).toBeInTheDocument();
    expect(within(offer).getByText(/only this value/i)).toBeInTheDocument();
    expect(within(offer).getByText("application/pdf")).toBeInTheDocument();
  });
});
