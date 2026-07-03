import {
  describe,
  it,
  expect,
  beforeAll,
  afterAll,
  afterEach,
  beforeEach,
  vi,
} from "vitest";
import { render, screen } from "@testing-library/react";
import { server, resetTaskDetailPolls } from "./msw/handlers";
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

describe("task detail polling", () => {
  it("shows EXECUTING, then renders the result and review controls at PENDING_REVIEW", async () => {
    render(
      <AuthProvider>
        <TaskDetailPage />
      </AuthProvider>,
    );

    // First poll: EXECUTING badge.
    expect(await screen.findByText("EXECUTING")).toBeInTheDocument();

    // Subsequent polls (2s interval) advance through RESULT_RECEIVED to PENDING_REVIEW — the
    // state the client actually reviews. RESULT_RECEIVED is a transient the poller may skip.
    expect(
      await screen.findByText("PENDING_REVIEW", {}, { timeout: 8000 }),
    ).toBeInTheDocument();
    expect(await screen.findByText(/agent status/i)).toHaveTextContent(/COMPLETED/);
    // Pretty-printed payload is present.
    expect(screen.getByText(/"summary": "all good"/)).toBeInTheDocument();
    // Result URL link rendered.
    expect(screen.getByRole("link", { name: /open deliverable/i })).toHaveAttribute(
      "href",
      "https://example.com/out.json",
    );

    // Regression guard: the accept/reject controls MUST appear at PENDING_REVIEW (not only at
    // the transient RESULT_RECEIVED). This is exactly the gap that left the UI stuck on "working".
    expect(
      await screen.findByRole("button", { name: /accept result/i }),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /^reject$/i })).toBeInTheDocument();
  }, 12000);
});
