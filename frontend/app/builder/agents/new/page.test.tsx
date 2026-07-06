/**
 * Tests for the agent-registration form, focused on the "Max parallel tasks"
 * (maxConcurrent) field: it must default to 5 and be included in the POST body.
 */
import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { http } from "msw";
import { server, ok } from "../../../../test/msw/handlers";
import { AuthProvider } from "@/lib/auth";
import RegisterAgentPage from "@/app/builder/agents/new/page";

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

function renderPage() {
  localStorage.setItem("hireai.token", "t");
  localStorage.setItem("hireai.auth", JSON.stringify({ userId: "u-1", role: "BUILDER" }));
  return render(
    <AuthProvider>
      <RegisterAgentPage />
    </AuthProvider>,
  );
}

describe("agent registration — max parallel tasks", () => {
  it("submits maxConcurrent (default 5) with the registration", async () => {
    let captured: Record<string, unknown> | null = null;
    server.use(
      http.post("*/api/agents", async ({ request }) => {
        captured = (await request.json()) as Record<string, unknown>;
        return ok({ id: "a-1" });
      }),
    );
    renderPage();
    fireEvent.change(screen.getByLabelText(/name/i), { target: { value: "Bot" } });
    fireEvent.change(screen.getByLabelText(/categories/i), { target: { value: "summarisation" } });
    fireEvent.change(screen.getByLabelText(/webhook/i), { target: { value: "https://a.example/run" } });
    fireEvent.click(screen.getByRole("button", { name: /register agent/i }));
    await waitFor(() => expect(captured).not.toBeNull());
    expect(captured!.maxConcurrent).toBe(5);
  });
});
