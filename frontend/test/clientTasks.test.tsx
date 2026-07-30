import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http } from "msw";
import { server, ok } from "./msw/handlers";
import { AuthProvider } from "@/lib/auth";
import ClientTasksPage from "@/app/client/tasks/page";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  useParams: () => ({}),
  useSearchParams: () => new URLSearchParams(),
  usePathname: () => "/client/tasks",
}));

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => {
  server.resetHandlers();
  localStorage.clear();
});
afterAll(() => server.close());

function renderClientTasks() {
  localStorage.setItem("hireai.token", "t");
  localStorage.setItem("hireai.auth", JSON.stringify({ userId: "u-1", role: "CLIENT" }));
  return render(
    <AuthProvider>
      <ClientTasksPage />
    </AuthProvider>,
  );
}

describe("client tasks console", () => {
  it("renders the wallet treasury with available balance", async () => {
    renderClientTasks();
    // Multiple "available" labels exist (label + ratio bar legend) — use getAllByText
    expect((await screen.findAllByText(/available/i))[0]).toBeInTheDocument();
    // Available balance from MSW: 950
    expect(await screen.findByText("950")).toBeInTheDocument();
  });

  it("lists the seeded task with its title and status", async () => {
    renderClientTasks();
    expect(await screen.findByText("Summarise Q2 report")).toBeInTheDocument();
    expect(await screen.findByText("EXECUTING")).toBeInTheDocument();
  });

  it("shows a link to submit a new task", async () => {
    renderClientTasks();
    await screen.findByText(/treasury/i);
    expect(screen.getByRole("link", { name: /submit task/i })).toHaveAttribute(
      "href",
      "/client/tasks/new",
    );
  });

  it("clears and retypes the top-up amount without a stray leading zero, then submits the typed value", async () => {
    let capturedBody: Record<string, unknown> | null = null;
    server.use(
      http.post("*/api/wallet/topup", async ({ request }) => {
        capturedBody = (await request.json()) as Record<string, unknown>;
        return ok({ availableBalance: 1025, escrowBalance: 50 });
      }),
    );
    renderClientTasks();
    const topupInput = (await screen.findByLabelText(/top-up amount/i)) as HTMLInputElement;
    expect(topupInput.value).toBe("50");
    await userEvent.clear(topupInput);
    expect(topupInput.value).toBe("");
    await userEvent.type(topupInput, "75");
    expect(topupInput.value).toBe("75");
    await userEvent.click(screen.getByRole("button", { name: /add/i }));
    await waitFor(() => expect(capturedBody).not.toBeNull());
    expect(capturedBody!.amount).toBe(75);
  });

  it("shows a validation error instead of submitting 0 when top-up is cleared and left empty", async () => {
    renderClientTasks();
    const topupInput = (await screen.findByLabelText(/top-up amount/i)) as HTMLInputElement;
    await userEvent.clear(topupInput);
    await userEvent.click(screen.getByRole("button", { name: /add/i }));
    expect(await screen.findByRole("alert")).toHaveTextContent(/amount/i);
  });
});
