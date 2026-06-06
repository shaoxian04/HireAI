import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { server } from "./msw/handlers";
import { AuthProvider } from "@/lib/auth";
import ClientTasksPage from "@/app/client/tasks/page";

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
});
