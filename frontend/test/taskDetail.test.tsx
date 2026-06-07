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
  it("shows EXECUTING then renders the result once RESULT_RECEIVED", async () => {
    render(
      <AuthProvider>
        <TaskDetailPage />
      </AuthProvider>,
    );

    // First poll: EXECUTING badge.
    expect(await screen.findByText("EXECUTING")).toBeInTheDocument();

    // Subsequent polls (2s interval) advance to RESULT_RECEIVED and load the result.
    expect(
      await screen.findByText("RESULT_RECEIVED", {}, { timeout: 5000 }),
    ).toBeInTheDocument();
    expect(await screen.findByText(/agent status/i)).toHaveTextContent(/COMPLETED/);
    // Pretty-printed payload is present.
    expect(screen.getByText(/"summary": "all good"/)).toBeInTheDocument();
    // Result URL link rendered.
    expect(screen.getByRole("link", { name: /open deliverable/i })).toHaveAttribute(
      "href",
      "https://example.com/out.json",
    );
  }, 10000);
});
