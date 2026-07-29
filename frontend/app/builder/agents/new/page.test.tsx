/**
 * Tests for the agent-registration form, focused on the "Max parallel tasks"
 * (maxConcurrent) field: it must default to 5 and be included in the POST body.
 */
import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
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

  it("clears and retypes price without a stray leading zero, submitting the typed value", async () => {
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
    const priceInput = screen.getByLabelText(/price/i) as HTMLInputElement;
    expect(priceInput.value).toBe("10");
    await userEvent.clear(priceInput);
    expect(priceInput.value).toBe("");
    await userEvent.type(priceInput, "25");
    expect(priceInput.value).toBe("25");
    fireEvent.click(screen.getByRole("button", { name: /register agent/i }));
    await waitFor(() => expect(captured).not.toBeNull());
    expect(captured!.price).toBe(25);
  });

  it("blocks registration with a validation error when price is cleared and left empty", async () => {
    renderPage();
    fireEvent.change(screen.getByLabelText(/name/i), { target: { value: "Bot" } });
    fireEvent.change(screen.getByLabelText(/categories/i), { target: { value: "summarisation" } });
    fireEvent.change(screen.getByLabelText(/webhook/i), { target: { value: "https://a.example/run" } });
    await userEvent.clear(screen.getByLabelText(/price/i));
    fireEvent.click(screen.getByRole("button", { name: /register agent/i }));
    expect(await screen.findByRole("alert")).toHaveTextContent(/price/i);
  });

  it("clears and retypes max execution seconds without a stray leading zero", async () => {
    renderPage();
    const input = screen.getByLabelText(/max execution seconds/i) as HTMLInputElement;
    expect(input.value).toBe("60");
    await userEvent.clear(input);
    expect(input.value).toBe("");
    await userEvent.type(input, "120");
    expect(input.value).toBe("120");
  });

  it("clears and retypes max parallel tasks without a stray leading zero, submitting the typed value", async () => {
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
    const input = screen.getByLabelText(/max parallel tasks/i) as HTMLInputElement;
    expect(input.value).toBe("5");
    await userEvent.clear(input);
    expect(input.value).toBe("");
    await userEvent.type(input, "8");
    expect(input.value).toBe("8");
    fireEvent.click(screen.getByRole("button", { name: /register agent/i }));
    await waitFor(() => expect(captured).not.toBeNull());
    expect(captured!.maxConcurrent).toBe(8);
  });
});
