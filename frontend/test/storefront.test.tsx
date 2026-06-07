import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { server } from "./msw/handlers";
import { AuthProvider } from "@/lib/auth";
import StorefrontPage from "@/app/client/agents/[id]/page";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  useParams: () => ({ id: "ag-1" }),
  useSearchParams: () => new URLSearchParams(),
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
    expect(screen.getByRole("link", { name: /book this agent/i })).toHaveAttribute(
      "href",
      "/client/agents/ag-1/book",
    );
  });
});
