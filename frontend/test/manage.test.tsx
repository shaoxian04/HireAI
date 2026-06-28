import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { server, resetProfileState } from "./msw/handlers";
import { AuthProvider } from "@/lib/auth";
import ManagePage from "@/app/builder/agents/[id]/page";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  useParams: () => ({ id: "a-1" }),
  useSearchParams: () => new URLSearchParams(),
}));

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => {
  server.resetHandlers();
  localStorage.clear();
  resetProfileState();
});
afterAll(() => server.close());

function renderBuilder() {
  localStorage.setItem("hireai.token", "t");
  localStorage.setItem("hireai.auth", JSON.stringify({ userId: "u-1", role: "BUILDER" }));
  return render(
    <AuthProvider>
      <ManagePage />
    </AuthProvider>,
  );
}

describe("manage agent console", () => {
  it("storefront tab edits and saves", async () => {
    renderBuilder();

    // Tagline is pre-filled from MSW fixture
    const taglineInput = await screen.findByRole("textbox", { name: /tagline/i });
    expect(taglineInput).toHaveValue("Fast, spec-true summaries");

    // Toggle the listed checkbox
    const listedCheckbox = screen.getByRole("checkbox", { name: /listed on marketplace/i });
    await userEvent.click(listedCheckbox);

    // Click Save ▸
    await userEvent.click(screen.getByRole("button", { name: /save/i }));

    // Expect "Saved" status message
    const status = await screen.findByRole("status");
    expect(status).toHaveTextContent(/saved/i);
  });

  it("stats tab renders tiles and activity", async () => {
    renderBuilder();

    // Wait for initial load then switch to Stats tab
    await screen.findByRole("textbox", { name: /tagline/i });
    await userEvent.click(screen.getByRole("button", { name: /stats/i }));

    // total volume tile
    expect(await screen.findByText("7")).toBeInTheDocument();

    // success rate tile: 0.857 → 86%
    expect(await screen.findByText(/86%/)).toBeInTheDocument();

    // escrow tile value
    expect(await screen.findByText("40 cr")).toBeInTheDocument();
    // escrow tile label (the StatTile label, not the caption sentence)
    const escrowLabels = await screen.findAllByText(/escrow/i);
    expect(escrowLabels.length).toBeGreaterThanOrEqual(1);

    // recent task title
    expect(await screen.findByText("Summarise Q2 report")).toBeInTheDocument();
  });

  it("reviews tab responds", async () => {
    renderBuilder();

    // Wait for initial load then switch to Reviews tab
    await screen.findByRole("textbox", { name: /tagline/i });
    await userEvent.click(screen.getByRole("button", { name: /reviews/i }));

    // Review text is visible
    expect(await screen.findByText(/output matched the spec exactly/i)).toBeInTheDocument();

    // Type a response
    const responseTextarea = await screen.findByRole("textbox", { name: /response/i });
    await userEvent.type(responseTextarea, "Thanks — glad it helped!");

    // Submit
    await userEvent.click(screen.getByRole("button", { name: /respond/i }));

    // Response should appear
    expect(await screen.findByText(/thanks — glad it helped/i)).toBeInTheDocument();
  });

  it("pricing tab publishes a new version", async () => {
    renderBuilder();

    // Wait for initial load then switch to Pricing tab
    await screen.findByRole("textbox", { name: /tagline/i });
    await userEvent.click(screen.getByRole("button", { name: /pricing/i }));

    // Price input shows current value (10)
    const priceInput = await screen.findByRole("spinbutton", { name: /price/i });
    expect(priceInput).toHaveValue(10);

    // Update price to 12
    await userEvent.clear(priceInput);
    await userEvent.type(priceInput, "12");

    // Click Publish version ▸
    await userEvent.click(screen.getByRole("button", { name: /publish/i }));

    // Expect "Published" status message
    const status = await screen.findByRole("status");
    expect(status).toHaveTextContent(/published/i);
  });

  it("suspends an active agent from the header", async () => {
    renderBuilder();
    await screen.findByRole("textbox", { name: /tagline/i }); // page loaded (agent is ACTIVE)
    await userEvent.click(screen.getByRole("button", { name: /^suspend$/i }));
    expect(await screen.findByText("SUSPENDED")).toBeInTheDocument();
  });
});
