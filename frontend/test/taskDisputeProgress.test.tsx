import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { http } from "msw";
import { server, resetTaskDetailPolls, ok } from "./msw/handlers";
import { AuthProvider } from "@/lib/auth";
import TaskDetailPage from "@/app/client/tasks/[id]/page";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  useParams: () => ({ id: "t-99" }),
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

describe("dispute outcome poll — live update while DISPUTED", () => {
  it("renders the arbitrator's proposed ruling for a live DISPUTED task", async () => {
    // The task is already DISPUTED from the very first poll (no need to walk through the
    // EXECUTING -> RESULT_RECEIVED -> PENDING_REVIEW lifecycle for this regression).
    server.use(
      http.get("*/api/tasks/:id", ({ params }) =>
        ok({
          id: params.id,
          clientId: "u-1",
          title: "Summarise Q2 report",
          description: "Summarise it",
          budget: 30,
          status: "DISPUTED",
          outputSpec: { format: "JSON", schema: "{}", acceptanceCriteria: "valid JSON" },
          createdAt: "2026-06-06T10:00:00Z",
          resolution: null,
        }),
      ),
      http.get("*/api/disputes/by-task/:taskId", ({ params }) =>
        ok({
          disputeId: "d-live-1",
          taskId: params.taskId as string,
          status: "RULED",
          effectiveCategory: null,
          rulings: [
            {
              tier: 1,
              decidedBy: "ARBITRATOR",
              category: "FULFILLED",
              rationale: "Output matched the acceptance criteria.",
              decidedAt: "2026-07-03T10:00:00Z",
            },
          ],
        }),
      ),
    );

    render(
      <AuthProvider>
        <TaskDetailPage />
      </AuthProvider>,
    );

    expect(await screen.findByText("DISPUTED")).toBeInTheDocument();

    // Regression guard: the outcome-poll effect used to depend on the whole `task` object,
    // which the main poller replaces with a brand-new object every POLL_MS (2000ms) tick
    // regardless of whether anything changed. That tore the outcome interval down and
    // recreated it every tick, in phase-lock with the main poll — its own setInterval
    // callback then essentially never got a chance to fire before being torn down again, so
    // /disputes/by-task was never (or only very unreliably, on a lucky race) re-fetched and
    // this panel never reliably appeared for a live dispute.
    //
    // The fix fetches immediately on the DISPUTED transition (before any interval tick), so
    // the panel must appear well inside a single POLL_MS window — a generous 1500ms real-timer
    // budget for one mocked HTTP round trip, but comfortably short of the 2000ms mark the old
    // interval-only path was gated behind. This bound is what makes the test fail against the
    // buggy `[task]` dependency and pass against the fixed `[task?.status, id]` dependency.
    expect(
      await screen.findByText(/proposed ruling/i, {}, { timeout: 1500 }),
    ).toBeInTheDocument();
    expect(
      await screen.findByRole("button", { name: /accept ruling/i }, { timeout: 1500 }),
    ).toBeInTheDocument();
  }, 12000);
});
