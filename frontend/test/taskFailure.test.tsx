import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { http } from "msw";
import { server, ok, resetTaskDetailPolls } from "./msw/handlers";
import { AuthProvider } from "@/lib/auth";
import TaskDetailPage from "@/app/client/tasks/[id]/page";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  useParams: () => ({ id: "t-fail" }),
}));

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
beforeEach(() => {
  resetTaskDetailPolls();
  localStorage.setItem("hireai.token", "t");
  localStorage.setItem("hireai.auth", JSON.stringify({ userId: "u-1", role: "CLIENT" }));
});
afterEach(() => {
  server.resetHandlers();
  localStorage.clear();
});
afterAll(() => server.close());

function specViolationTask() {
  return {
    id: "t-fail",
    clientId: "u-1",
    title: "Summarise Q3 memo",
    description: "d",
    budget: 10,
    status: "SPEC_VIOLATION",
    outputSpec: { format: "FILE", schema: "{}", acceptanceCriteria: "a file" },
    createdAt: "2026-07-13T10:00:00Z",
  };
}

describe("task detail — failure panel", () => {
  it("shows the spec-violation panel, refund, and the real reason drawer", async () => {
    server.use(
      http.get("*/api/tasks/t-fail", () => ok(specViolationTask())),
      http.get("*/api/tasks/t-fail/validation", () =>
        ok({
          verdict: "FAIL",
          checks: [{ rule: "format", passed: false, detail: "expected FILE, got none" }],
        }),
      ),
    );

    render(<AuthProvider><TaskDetailPage /></AuthProvider>);

    expect(await screen.findByText(/didn't meet the spec/i)).toBeInTheDocument();
    expect(screen.getByText(/10 cr refunded/i)).toBeInTheDocument();
    fireEvent.click(await screen.findByText(/show what failed/i));
    expect(await screen.findByText(/expected FILE, got none/i)).toBeInTheDocument();
  });

  it("renders the timeout panel with no drawer", async () => {
    server.use(
      http.get("*/api/tasks/t-fail", () =>
        ok({ ...specViolationTask(), status: "TIMED_OUT" }),
      ),
    );
    render(<AuthProvider><TaskDetailPage /></AuthProvider>);
    expect(await screen.findByText(/ran out of time/i)).toBeInTheDocument();
    expect(screen.queryByText(/show what failed/i)).toBeNull();
  });
});
